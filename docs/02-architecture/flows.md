# Luồng chính (Runtime flows)

Mỗi luồng ghi rõ **có từ phase nào** và **điểm kỹ thuật cần học**. Ký hiệu: `CF` = CloudFront, `ALB` = internal ALB, `API` = jars-api.

| ID | Luồng | Phase |
|---|---|---|
| [F1](#f1) | Đăng nhập + request đầu tiên (tạo user lazy) | 1 |
| [F2](#f2) | Ghi một khoản chi (idempotency, optimistic lock, outbox) | 1 |
| [F3](#f3) | Phát hiện bất thường → thông báo realtime (SSE) | 2 (in-process), 5 (qua SNS/SQS) |
| [F4](#f4) | Chat AI streaming với tool calling + guardrails | 4 |
| [F5](#f5) | Import sao kê CSV | 2 (local), 3 (S3 + EventBridge) |
| [F6](#f6) | Khép sổ cuối tháng theo lịch | 3 |
| [F7](#f7) | Định tuyến request ở edge | 3 |
| [F8](#f8) | Aurora auto-pause / resume | 3 |

---

<a id="f1"></a>
## F1. Đăng nhập + request đầu tiên

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant W as web (SPA)
    participant C as Cognito managed login
    participant CF as CloudFront
    participant API as jars-api
    participant DB as Aurora

    U->>W: Bấm "Đăng nhập"
    W->>W: Tạo code_verifier + code_challenge (PKCE)
    W->>C: Redirect /oauth2/authorize?code_challenge=...
    U->>C: Nhập email + mật khẩu
    C-->>W: Redirect /auth/callback?code=...
    W->>C: POST /oauth2/token (code + code_verifier)
    C-->>W: access_token, id_token, refresh_token
    W->>CF: GET /api/v1/me (Authorization: Bearer access_token)
    CF->>API: (VPC origin, giữ nguyên header)
    API->>API: Xác thực JWT: chữ ký (JWKS cache), iss, exp, token_use=access, client_id
    API->>DB: SELECT users WHERE cognito_sub = ?
    alt chưa có user
        API->>C: GET /oauth2/userInfo (Bearer access_token) → email
        Note over API,C: Ngoài transaction DB, chỉ 1 lần mỗi user (access token không có claim email)
        API->>DB: INSERT ... ON CONFLICT (cognito_sub) DO NOTHING + publish UserRegistered (ghi outbox event_publication)
        API->>DB: SELECT users WHERE cognito_sub = ?
        Note over API,DB: Cùng 1 transaction (READ COMMITTED)
        API--)API: AFTER_COMMIT, thread khác, transaction mới → ledger tạo 6 hũ + POOL, xóa dòng outbox
    end
    API-->>W: 200 {id, email, onboardingCompleted}
```

**Điểm cần học:**
- **PKCE** thay cho client secret, vì SPA không giữ được bí mật.
- API chỉ nhận **access token**. ID token dùng để hiển thị thông tin user ở frontend, không dùng để gọi API.
- **Race condition:** 2 request đầu tiên đến cùng lúc thì cả hai cùng thấy "chưa có user". Xử lý bằng `INSERT ... ON CONFLICT (cognito_sub) DO NOTHING` rồi SELECT lại. Transaction thứ hai **chờ** transaction thứ nhất commit (vì đụng unique index), rồi không làm gì, và SELECT sau đó thấy được dòng đã commit. *(Đã cài đặt ở P1.2; `UserProvisioningIntegrationTest` chạy 8 luồng cùng lúc: luôn ra 1 dòng, 1 event.)*
- **Email lấy từ `/oauth2/userInfo`**, vì access token của Cognito không có claim `email`. Gọi **ngoài** transaction DB (không giữ connection trong lúc chờ mạng); timeout 2 s connect, 3 s read.
- Spring Security xác thực JWT **offline** bằng JWKS được cache, nên không phải gọi Cognito ở mỗi request.
- **Hũ mặc định được tạo bất đồng bộ** (P1.4): `identity` không biết `ledger` tồn tại, chỉ phát `UserRegistered`. Event được ghi vào outbox **trong cùng transaction** tạo user, nên user được tạo thì event không thể mất; rollback thì không có event. Cái giá: response `/me` có thể về **trước** khi 7 hũ xuất hiện (vài chục ms). `GET /jars` (P1.5) phải xử lý trường hợp "chưa có hũ" (gọi lại đoạn code tạo hũ, vốn idempotent, hoặc trả danh sách rỗng để UI thử lại). Nếu listener lỗi, publication ở trạng thái `FAILED` và được gửi lại (`DefaultJarsProvisionerTests`).

---

<a id="f2"></a>
## F2. Ghi một khoản chi (Transfer / quick add)

```mermaid
sequenceDiagram
    autonumber
    participant W as web
    participant API as jars-api (ledger)
    participant DB as Aurora

    W->>API: POST /api/v1/transactions<br/>Idempotency-Key: 7f9c...<br/>{type: EXPENSE, amount: 85000, jarId, categoryId}
    API->>DB: SELECT idempotency_keys WHERE user_id, key
    alt key đã tồn tại với cùng request hash
        API-->>W: Trả lại response đã lưu (không ghi lần 2)
    else key tồn tại nhưng request khác
        API-->>W: 422 idempotency-key-reused
    end
    API->>DB: BEGIN
    API->>DB: SELECT jars WHERE id AND user_id (version = 7)
    API->>API: Kiểm tra quy tắc: hũ thuộc user, chưa archived, số dư đủ (nếu bật chặn chi vượt)
    API->>DB: INSERT transactions
    API->>DB: INSERT jar_entries (SPEND, -85000, balance_after)
    API->>DB: UPDATE jars SET balance=..., version=8 WHERE id=? AND version=7
    API->>DB: INSERT event_publication (TransactionPosted)
    API->>DB: INSERT idempotency_keys (response)
    API->>DB: COMMIT
    alt UPDATE trả về 0 dòng (đã có request khác sửa trước)
        API->>API: ObjectOptimisticLockingFailureException → rollback, retry trong transaction MỚI (tối đa 3 lần)
    end
    API-->>W: 201 Created + Location {transaction, jars: [{id, code, balance}]}
    API--)API: AFTER_COMMIT: phát TransactionPosted cho insights và budgeting (async)
```

**Điểm cần học:** `@Transactional` (proxy, rollback rules), optimistic locking với `@Version`, isolation `READ COMMITTED` (mặc định của PostgreSQL) và vì sao nó đủ dùng khi đã có `@Version`, idempotency key, outbox.

**Đã cài đặt (P1.5)**, trừ Idempotency-Key (P1.6): `PostingService` lồng **retry → transaction → work** tường minh. Mỗi lần thử đọc lại hũ (số dư và version mới), nên một khoản chi bị retry có thể trả 422 `insufficient-jar-balance`; đây là kết quả đúng. `ConcurrentPostingIntegrationTest`: 2 khoản chi 60k tranh 100k → đúng 1 thành công; 8 luồng cùng chi → không mất cập nhật; 6 luồng chuyển ngược chiều → không deadlock, tổng tiền bảo toàn; sau mọi test `jars.balance = SUM(jar_entries.amount)`.

---

<a id="f3"></a>
## F3. Phát hiện bất thường → thông báo realtime

### Phase 2–4 (in-process)

```mermaid
sequenceDiagram
    autonumber
    participant L as ledger
    participant R as Modulith event registry
    participant I as insights
    participant N as notification
    participant DB as Aurora
    participant W as web (SSE đang mở)

    L->>R: publish TransactionPosted (trong transaction của F2)
    R->>DB: event_publication: PUBLISHED
    Note over R: Sau COMMIT
    R->>I: @ApplicationModuleListener (async, REQUIRES_NEW)
    I->>DB: SELECT anomaly_detector_state FOR jar (version)
    I->>I: CUSUM: S+ = max(0, S+ + (x - mu)/sigma - k)
    alt S+ > h
        I->>DB: INSERT anomalies + reset state + event_publication(AnomalyDetected)
    else
        I->>DB: UPDATE state
    end
    I->>DB: UPSERT monthly_jar_spend
    R->>DB: event_publication: COMPLETED
    R->>N: AnomalyDetected
    N->>DB: INSERT notifications
    N-->>W: SSE event: notification {title, severity, anomalyId}
```

### Phase 5 (externalized, qua SNS/SQS)

```mermaid
sequenceDiagram
    autonumber
    participant API as jars-api
    participant SNS1 as SNS jars-ledger-events.fifo
    participant Q1 as SQS jars-insights-ledger.fifo
    participant WK as insights-worker
    participant SNS2 as SNS jars-insights-events.fifo
    participant Q2 as SQS jars-api-notifications.fifo
    participant W as web

    API->>SNS1: Publish (group=userId, dedupId=eventId)
    SNS1->>Q1: fan-out
    WK->>Q1: ReceiveMessage (long polling)
    WK->>WK: processed_messages check, CUSUM, ghi DB (1 transaction)
    WK->>SNS2: AnomalyDetected (qua outbox của worker)
    WK->>Q1: DeleteMessage
    SNS2->>Q2: fan-out
    API->>Q2: ReceiveMessage
    API-->>W: SSE notification
```

**Fan-out SSE khi có nhiều task:** user có thể đang giữ kết nối SSE ở task A trong khi task B nhận message. Cách xử lý (Phase 4+): task nhận message publish lên kênh Valkey `notif:{userId}`, task nào đang giữ kết nối của user đó thì đẩy xuống. Khi chỉ có 1 task thì không cần.

---

<a id="f4"></a>
## F4. Chat AI streaming

```mermaid
sequenceDiagram
    autonumber
    participant W as web
    participant API as jars-api (advisor)
    participant RL as Valkey (rate limit)
    participant G as Bedrock Guardrails
    participant B as Bedrock ConverseStream
    participant T as Tools (ledger, goals, insights, market)

    W->>API: POST /api/v1/chat/sessions/{id}/messages (Accept: text/event-stream)
    API->>RL: Bucket4j: còn token cho user?
    alt hết quota
        API-->>W: 429 + Retry-After
    end
    API->>API: Nạp lịch sử chat (N tin gần nhất) + system prompt + ngữ cảnh risk profile
    API->>B: ConverseStream (tools = [getJarSummary, getSpending, getGoalFeasibility, getQuote], guardrail)
    B-->>API: toolUse getSpending(month=2026-10)
    API->>T: Gọi Java service với userId lấy từ JWT (KHÔNG lấy từ tham số LLM)
    T-->>API: Kết quả có cấu trúc (số do Java tính)
    API-->>W: event: tool {name: getSpending, status: done}
    API->>B: toolResult
    loop streaming
        B-->>API: text delta
        API-->>W: event: token {text}
    end
    B-->>API: stop + usage (tokens in/out)
    API->>API: Lưu chat_messages, ghi metric token
    API-->>W: event: done {messageId, usage}
```

**Điểm cần học:**
- Tool chỉ đọc dữ liệu, và `userId` luôn lấy từ security context, nên prompt injection không đọc được dữ liệu của người khác.
- Guardrail chặn nội dung khuyến nghị mua/bán cổ phiếu cụ thể.
- Streaming bằng `Flux` (Spring AI) chuyển sang `SseEmitter` (MVC), hoặc trả `Flux<ServerSentEvent>` trực tiếp.
- Chi phí được kiểm soát nhờ rate limit + `max-tokens`.

---

<a id="f5"></a>
## F5. Import sao kê CSV

```mermaid
sequenceDiagram
    autonumber
    participant W as web
    participant API as jars-api (imports)
    participant S3 as S3 statements
    participant EB as EventBridge
    participant Q as SQS jars-imports
    participant DB as Aurora

    W->>API: POST /api/v1/imports {fileName, size}
    API->>DB: INSERT statement_imports (PENDING)
    API-->>W: {importId, uploadUrl (presigned PUT, 5 phút, giới hạn content-length)}
    W->>S3: PUT uploads/{userId}/{importId}.csv
    S3->>EB: Object Created
    EB->>Q: rule statement-uploaded
    API->>Q: @SqsListener nhận message
    API->>DB: Kiểm tra importId thuộc userId và đang PENDING → RUNNING
    API->>API: Spring Batch job: FlatFileItemReader(S3) → processor (chuẩn hóa, phân loại) → writer (ledger.postTransaction)
    Note over API: chunk=100, dòng lỗi được skip và ghi lại, job restart được
    API->>DB: statement_imports COMPLETED (rows_ok, rows_failed) + StatementImportCompleted
    API-->>W: SSE notification "Đã import 120 giao dịch, 3 dòng lỗi"
```

---

<a id="f6"></a>
## F6. Khép sổ cuối tháng

```mermaid
sequenceDiagram
    autonumber
    participant SCH as EventBridge Scheduler
    participant Q as SQS jars-jobs
    participant API as jars-api (budgeting)
    participant DB as Aurora

    SCH->>Q: 00:05 ngày 1 hằng tháng (Asia/Ho_Chi_Minh): {job: MONTH_CLOSE, month: 2026-10}
    API->>Q: @SqsListener
    API->>DB: processed_messages check (job + month là khóa idempotency)
    loop từng user (theo trang 500 user, mỗi user 1 transaction)
        API->>DB: Tổng kết jar_periods tháng cũ, tạo jar_periods tháng mới
        API->>DB: event_publication(MonthClosed)
    end
    API->>Q: DeleteMessage
```

**Vì sao không dùng `@Scheduled`:** khi có 2 task, cả hai sẽ cùng chạy job (khi đó cần ShedLock). Đưa lịch ra EventBridge Scheduler thì chỉ có **một message**, và chỉ một task nhận được nó. Ngoài ra Scheduler hỗ trợ timezone, có retry và DLQ.

---

<a id="f7"></a>
## F7. Định tuyến request ở edge

```mermaid
flowchart LR
    B[Browser] -->|HTTPS d123.cloudfront.net| CF{CloudFront}
    CF -->|"/*  (cache)"| S3[(S3 web bucket<br/>OAC)]
    CF -->|"/api/*  (no cache, forward Authorization)"| VO[VPC origin ENI]
    VO -->|HTTP :80| ALB[internal ALB<br/>private subnet]
    ALB -->|:8080 health /actuator/health/readiness| T1[jars-api task]
    S3 -.->|403/404 → /index.html| B
```

---

<a id="f8"></a>
## F8. Aurora auto-pause / resume

```mermaid
stateDiagram-v2
    [*] --> Running: kết nối đầu tiên
    Running --> Running: còn kết nối hoặc đang có query
    Running --> Pausing: 300 s không còn kết nối nào
    Pausing --> Paused
    Paused --> Resuming: có yêu cầu kết nối mới
    Resuming --> Running: khoảng vài giây đến 15 s
    note right of Paused: Không tính phí ACU, chỉ tính storage
    note right of Running: $0.20 / ACU-giờ (min 0.5 ACU khi chạy)
```

Điều kiện để thật sự pause được: Hikari `minimum-idle=0`; health check của ALB không chạm DB; không có `@Scheduled` nào query DB; không mở pgAdmin hay DBeaver quên đóng; không dùng RDS Proxy.
