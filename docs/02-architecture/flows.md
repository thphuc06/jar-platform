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
        API->>DB: BEGIN, INSERT users ... ON CONFLICT (cognito_sub) DO NOTHING
        API->>API: nếu chèn được: publish UserRegistered, ledger nghe đồng bộ và tạo 6 hũ
        API->>DB: COMMIT (user và 6 hũ cùng một transaction)
        Note over API,DB: Không chèn được nghĩa là request khác đã thắng: SELECT lại id
    end
    API-->>W: 200 {id, email, displayName, locale, onboardingCompleted}
```

**Điểm cần học:**
- **PKCE** thay cho client secret, vì SPA không giữ được bí mật.
- API chỉ nhận **access token**. ID token dùng để hiển thị thông tin user ở frontend, không dùng để gọi API.
- **Race condition:** 2 request đầu tiên đến cùng lúc thì cả hai cùng thấy "chưa có user". Xử lý bằng `INSERT ... ON CONFLICT (cognito_sub) DO NOTHING` rồi SELECT lại (có test 12 luồng đồng thời).
- **Email:** access token của Cognito không có claim `email` nên `users.email` hiện để trống; UI đã có email từ ID token. Khi cần gửi mail sẽ lấy qua endpoint `/oauth2/userInfo`.
- Spring Security xác thực JWT **offline** bằng JWKS được cache, nên không phải gọi Cognito ở mỗi request.

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
    loop tối đa 3 lần (vòng retry nằm NGOÀI transaction, mỗi lần là một transaction mới)
        API->>DB: BEGIN
        API->>DB: INSERT idempotency_keys (user_id, key, request_hash) ON CONFLICT DO NOTHING
        alt không chèn được dòng nào (key đã có)
            Note over API,DB: Nếu request đầu còn chưa commit, INSERT chờ khóa unique tới khi nó commit hoặc rollback
            alt request_hash khác
                API-->>W: 422 idempotency-key-reused
            else cùng request
                API-->>W: Trả lại response đã lưu (không ghi lần 2)
            end
        end
        API->>DB: SELECT jars WHERE id AND user_id (version = 7)
        API->>API: Kiểm tra quy tắc: hũ thuộc user, chưa archived, số dư đủ (nếu bật chặn chi vượt)
        API->>DB: INSERT transactions
        API->>DB: INSERT jar_entries (SPEND, -85000, balance_after)
        API->>DB: UPDATE jars SET balance=..., version=8 WHERE id=? AND version=7
        API->>DB: INSERT event_publication (TransactionPosted)
        API->>DB: UPDATE idempotency_keys SET status_code, response_body
        API->>DB: COMMIT
        Note over API,DB: UPDATE jars trả về 0 dòng thì ném OptimisticLockException, transaction rollback (cả dòng idempotency) rồi vòng lặp thử lại
    end
    API-->>W: 201 Created {transaction, jarBalance} (409 nếu hết 3 lần vẫn xung đột)
    API--)API: AFTER_COMMIT: phát TransactionPosted cho insights và budgeting (async)
```

**Điểm cần học:** `@Transactional` (proxy, rollback rules), optimistic locking với `@Version`, isolation `READ COMMITTED` (mặc định của PostgreSQL) và vì sao nó đủ dùng khi đã có `@Version`, idempotency key, outbox.

**Vì sao retry phải nằm ngoài transaction:** transaction đã gặp `OptimisticLockException` bị đánh dấu rollback-only và entity trong persistence context đã cũ. Thử lại trong cùng transaction vẫn dùng dữ liệu cũ. Vòng retry phải gọi một method `@Transactional` từ bên ngoài proxy (bean khác), để mỗi lần có transaction và persistence context mới.

**Thử lại tối đa 5 lần với backoff ngẫu nhiên** (`jars.ledger.max-attempts`, nâng từ 3 sau khi test đồng thời 12 luồng trên một hũ). Vòng retry bắt `ConcurrencyFailureException`, tức cả xung đột `@Version` lẫn nạn nhân của deadlock; hết lượt thì trả 409 `version-conflict`.

**Deadlock khi chuyển hũ hai chiều (đã gặp thật khi viết test):** chuyển A→B và B→A cùng lúc làm hai transaction `UPDATE` hai dòng `jars` theo thứ tự ngược nhau, PostgreSQL báo `deadlock detected` (SQLSTATE 40P01). Cách xử lý: bật `hibernate.order_updates` để mọi transaction ghi các dòng theo thứ tự khóa chính (nguyên nhân gốc), và coi nạn nhân deadlock như xung đột đồng thời (retry, rồi 409). Test `concurrentTransfersBetweenTwoJars_keepBothLedgersConsistent` giữ cho lỗi này không quay lại.

**Chi tiêu vượt số dư:** mặc định vẫn ghi (tiền đã tiêu rồi, số dư âm cho thấy tiêu lố); đặt `jars.ledger.block-overspend=true` để từ chối với 422 `insufficient-jar-balance`. Chuyển hũ luôn cần đủ tiền ở hũ nguồn.

**Vì sao INSERT key nằm trong cùng transaction:** hai request cùng key chạy đồng thời sẽ va vào unique index. Request sau chờ cho tới khi request đầu commit, rồi thấy key đã tồn tại và trả response đã lưu. Nếu request đầu rollback thì dòng key cũng biến mất và request sau được chạy bình thường. Không cần trạng thái `IN_PROGRESS` riêng.

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
    I->>I: MAD (outlier đơn lẻ) rồi CUSUM: S+ = max(0, S+ + (x - mu)/sigma - k)
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

**Thuật toán đã cài (`SpendingDetector`)**, chạy trên mỗi khoản chi của một hũ:

1. 5 khoản chi đầu tiên chỉ để học baseline (trung bình/độ lệch chuẩn chạy bằng Welford).
2. **MAD:** với trung vị m và MAD d của tối đa 30 khoản gần nhất, điểm z hiệu chỉnh = 0.6745 (x - m) / d; lớn hơn 3.5 là *spike* (HIGH nếu > 7). Spike **không** được đưa vào baseline hay CUSUM để không làm nhiễm chúng.
3. **CUSUM** trên z = (x - mean)/sigma với k = 0.5, h = 5: S+ vượt h là chi tăng kéo dài, S- dưới -h là chi giảm kéo dài (LOW). Sigma có sàn 10% của mean và MAD có sàn 5% của trung vị, để hũ có lịch sử toàn khoản bằng nhau không coi chênh lệch 1 đồng là vô cùng bất thường.
4. Trạng thái của hũ bị khóa bi quan (`SELECT ... FOR UPDATE`) trong lúc xử lý, nên các event của một hũ được áp dụng lần lượt.

Dữ liệu chuẩn của test lấy từ `test_cusum_sliding_window_local.py` của bản hackathon (baseline 100k, sigma 52k: lệch tăng dần báo ở khoản thứ 10, không đổi 200k báo ở khoản thứ 4).

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
    participant T as Tools (ledger, goals, insights)

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

    W->>API: POST /api/v1/imports {fileName}
    API->>DB: INSERT statement_imports (AWAITING_UPLOAD)
    API-->>W: {statementImport, upload: {url (presigned PUT, 10 phút), method, headers, sameOrigin=false}}
    W->>S3: PUT uploads/{userId}/{importId}.csv (không kèm token của API)
    S3->>EB: Object Created
    EB->>Q: rule statement-uploaded
    API->>Q: @SqsListener nhận message (chỉ profile aws)
    API->>DB: statement_imports → PROCESSING
    API->>API: Spring Batch: reader (HEAD + GET từ S3, ≤ 5 MB, SHA-256, dòng tiêu đề) → processor (đọc dòng, đoán hũ/danh mục) → writer (LedgerApi.recordImported)
    Note over API,DB: chunk 50 dòng = 1 transaction: bút toán, idempotency key import_<sha>_<dòng>, dòng lỗi, vị trí đọc
    API->>DB: statement_imports COMPLETED/FAILED + StatementImportCompleted (outbox)
    API-->>W: SSE notification "Đã ghi 120 giao dịch vào sổ. 3 dòng không nhập được"
    loop mỗi 1,5 giây đến khi xong
        W->>API: GET /api/v1/imports/{id}
    end
```

Ở local và trong test không có S3/SQS: `upload.url` là `PUT /api/v1/imports/{id}/file` cùng origin (`sameOrigin=true`, gửi kèm token), endpoint này ghi tệp ra thư mục tạm và chạy job ngay trong request.

| Tình huống | Kết quả |
|---|---|
| Dòng sai ngày, số tiền bằng 0, ngày ở tương lai, ledger từ chối (`DomainException`) | Dòng bị skip, ghi vào `statement_import_errors`; các dòng khác vẫn nhập |
| Thiếu cột ngày hoặc số tiền, tệp không phải UTF-8, lớn hơn 5 MB, **cùng tệp đã nhập xong trước đó** | Cả tệp bị từ chối: `FAILED` + `failureReason` |
| Xung đột optimistic lock với giao dịch user đang ghi cùng lúc | Chunk bị rollback và chạy lại (tối đa 3 lần) |
| Lỗi hạ tầng giữa chừng | Job `FAILED`; S3 event giao lại (hoặc user tải lại đúng tệp) → **cùng job instance** chạy tiếp từ chunk cuối đã commit; idempotency key chặn ghi trùng |
| S3 giao event hai lần | Lần thứ hai thấy job đang chạy hoặc đã xong → bỏ qua |

---

<a id="f6"></a>
## F6. Khép sổ cuối tháng

```mermaid
sequenceDiagram
    autonumber
    participant SCH as EventBridge Scheduler
    participant Q as SQS jars-jobs
    participant L as MonthCloseQueueListener (chỉ profile aws)
    participant J as MonthCloseJob
    participant S as MonthCloseService (budgeting)
    participant ID as IdentityApi
    participant DB as Aurora
    participant N as notification

    SCH->>Q: 00:05 ngày 1 hằng tháng (Asia/Ho_Chi_Minh): {"job":"MONTH_CLOSE"}
    L->>Q: @SqsListener(${jars.jobs.queue-name})
    L->>J: handle(payload)
    J->>J: tháng cần khép = tháng trước tháng hiện tại, tính theo giờ VN (hoặc "month" trong payload)
    J->>S: closeAll(month)
    S->>DB: processed_messages có (budgeting.month-close.batch, month)? có thì dừng
    loop từng trang 500 user (IdentityApi.userIdsAfter, keyset theo id)
        loop từng user, mỗi user MỘT transaction riêng
            S->>DB: INSERT processed_messages (budgeting.month-close, userId:month), có rồi thì bỏ qua user
            S->>DB: INSERT jar_periods tháng cũ và tháng mới cho mọi hũ đang dùng (ON CONFLICT DO NOTHING)
            S->>DB: UPDATE jar_periods SET closed_at WHERE closed_at IS NULL
            S->>DB: event_publication(MonthClosed)
        end
    end
    S->>DB: tất cả user xong thì ghi processed_messages (budgeting.month-close.batch, month)
    J->>DB: dọn idempotency_keys hết hạn (IdempotencyService.purgeExpired)
    L->>Q: DeleteMessage (khi handle không ném lỗi)
    DB-->>N: sau commit, MonthClosedNotifier tạo notification "Đã khép sổ tháng MM/YYYY"
```

**Idempotent ở hai tầng (đã cài đặt):**

- **Từng user:** marker `(budgeting.month-close, userId:month)` được ghi trong **cùng transaction** với việc khép sổ, nên một tháng của một user chỉ được khép một lần; chạy lại thì thấy marker và không làm gì (không đổi `closed_at`, không phát event thứ hai). Ngoài marker, `closed_at` chỉ được đặt khi còn null và không bao giờ dời. Hai task nhận trùng message cùng lúc thì task sau chờ trên unique index rồi thấy marker.
- **Cả đợt:** marker `(budgeting.month-close.batch, month)` được ghi **sau khi mọi user thành công**. Message giao trùng sau khi đợt đã xong thì trả về ngay, không duyệt user nào. Đợt chết giữa chừng thì chưa có marker, lần giao lại sẽ duyệt tiếp và bỏ qua user đã khép. Nếu có user lỗi, `closeAll` vẫn xử lý hết các user còn lại rồi `MonthCloseJob` ném lỗi, message quay lại hàng đợi và sau `maxReceiveCount=5` lần vào DLQ (có alarm); marker của đợt chỉ ghi khi không user nào lỗi.

**Quy tắc khép sổ một user:** mọi hũ đang dùng có một dòng `jar_periods` tháng cũ (dù chưa dùng) để bản tóm tắt đầy đủ; hũ đã archive nhưng có dòng của tháng đó vẫn có trong bản tóm tắt (tên "Hũ đã lưu trữ"). Chi tiêu đến muộn sau khi khép sổ vẫn cộng vào `spent_amount` của tháng cũ nhưng `closed_at` giữ nguyên và không phát lại `MonthClosed`. Tháng chưa kết thúc (theo giờ VN) không khép được (`IllegalArgumentException`), để một message sai không khép tháng đang chạy. `MonthClosed` luôn được phát (kể cả tháng không có gì); module `notification` bỏ qua tháng có kế hoạch và chi đều bằng 0 để tài khoản không dùng không nhận thông báo mỗi tháng.

**Payload:** lịch gửi `{"job":"MONTH_CLOSE"}` (không có tháng); job tự tính tháng trước theo giờ VN, đúng cả lúc 00:05 ngày 1 khi UTC vẫn là ngày cuối tháng. Có thể gửi tay `{"job":"MONTH_CLOSE","month":"2026-10"}` để chạy lại một tháng. Job lạ bị bỏ qua (và xác nhận message), payload không phải JSON hoặc tháng sai định dạng thì ném lỗi để vào DLQ.

**Chỉ ở profile `aws`:** listener SQS là `@Profile("aws")` với tên hàng đợi từ `jars.jobs.queue-name` (biến môi trường `JARS_JOBS_QUEUE`); local và test không bao giờ kết nối AWS, logic nằm trong `MonthCloseJob` / `MonthCloseService` và được test gọi trực tiếp.

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
