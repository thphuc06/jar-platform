# API Contract

> Nguồn sự thật cuối cùng là file **OpenAPI** do `jars-api` sinh ra (springdoc), được commit ở `contracts/openapi/jars-api.yaml`. Từ file này sinh TypeScript client cho `web` (`openapi-typescript`). Tài liệu này là bản thiết kế và giải thích các quy ước.

## 1. Quy ước chung

| Chủ đề | Quy ước |
|---|---|
| Base path | `/api/v1` (CloudFront chuyển `/api/*` sang ALB) |
| Định dạng | JSON UTF-8, field `camelCase`; tiền là **string** dạng số thập phân (`"85000.00"`) kèm `currency`, tránh JS làm tròn số |
| Thời gian | ISO-8601 UTC (`2026-11-15T03:21:09Z`); tháng dạng `YYYY-MM` |
| Auth | `Authorization: Bearer <Cognito access token>` cho mọi endpoint trừ `/api/v1/health`. Metadata RFC 9728 công khai tại `/.well-known/oauth-protected-resource` |
| Lỗi | **RFC 9457 `application/problem+json`**, luôn có `type`, `title`, `status`, `detail`, `instance`, **`traceId`**. `type` = `https://jars.local/problems/{code}`: `code` là định danh ổn định (UI dịch thông báo theo `code`, còn `detail` là tiếng Anh dành cho developer). Validation có thêm `errors: [{field, message}]` |
| Phân trang | **Cursor (keyset)**: `?limit=50&cursor=<opaque>` → `{items, nextCursor}` |
| Idempotency | Header `Idempotency-Key` (UUID) **bắt buộc** với các POST làm thay đổi tiền |
| Versioning | Chỉ đổi `/v1` → `/v2` khi phá vỡ tương thích; thêm field optional thì không đổi |
| Correlation | Gửi `traceparent` (W3C) thì server tiếp tục trace đó; không gửi thì server tạo mới. Response **luôn** có header `traceparent`. Mọi dòng log phía server đều có `traceId` (✅ P1.3) |

### Mã lỗi (`code`) hiện có (✅ P1.3)

| `code` | HTTP | Khi nào |
|---|---|---|
| `validation-failed` | 422 | `@Valid` body hoặc ràng buộc trên `@RequestParam`/`@PathVariable` sai; kèm `errors[]` |
| `{resource}-not-found` | 404 | Ví dụ `jar-not-found`. Không tồn tại **hoặc** không thuộc user |
| *(mã nghiệp vụ)* | 422 | ✅ P1.5: `insufficient-jar-balance`, `expense-requires-jar`, `expense-from-pool`, `jar-archived`, `category-kind-mismatch`, `same-jar-transfer`, `use-transfer-endpoint`, `occurred-at-in-future` (lệch quá 5 phút) |
| `email-already-registered` | 409 | Email đã gắn với tài khoản Cognito khác |
| `concurrent-modification` | 409 | Request khác sửa cùng hũ cùng lúc và vẫn xung đột sau 3 lần retry phía server; không có gì được ghi, client gửi lại được (✅ P1.5) |
| `conflict` | 409 | Ràng buộc DB bị vi phạm mà code chưa kiểm tra trước (không lộ SQL) |
| `unauthorized` / `forbidden` | 401 / 403 | Lỗi xác thực / thiếu scope (do Spring Security sinh ra) |
| `internal-error` | 500 | Lỗi không lường trước; không có stack trace; client nên gửi kèm `traceId` khi báo lỗi |
| `about:blank` | 400, 404, 405, 415 | Lỗi chuẩn của Spring MVC (JSON hỏng, sai kiểu tham số, đường dẫn không tồn tại, sai method) |

### Ví dụ lỗi

```json
{
  "type": "https://jars.local/problems/insufficient-jar-balance",
  "title": "Số dư hũ không đủ",
  "status": 422,
  "detail": "Hũ PLAY còn 120000.00 VND, yêu cầu chi 200000.00 VND",
  "instance": "/api/v1/transactions",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

| HTTP | Khi nào |
|---|---|
| 400 | JSON sai cú pháp, sai kiểu dữ liệu |
| 401 | Thiếu hoặc sai token (kể cả **ID token**: chỉ nhận access token). Có header `WWW-Authenticate: Bearer ...`; nội dung là ProblemDetail `type=.../unauthorized` |
| 403 | Thiếu scope: **mọi GET `/api/**` cần `jars-api/read`; mọi method khác cần `jars-api/write`**. ProblemDetail `type=.../forbidden`. Đường dẫn ngoài danh sách cho phép bị chặn mặc định (`denyAll`) |
| 404 | Không tồn tại **hoặc không thuộc user** (chống IDOR) |
| 409 | Xung đột version (sau khi đã retry vẫn thất bại) |
| 422 | Sai quy tắc nghiệp vụ (validation, số dư, Idempotency-Key bị dùng lại với request khác) |
| 429 | Vượt rate limit (có header `Retry-After`) |
| 503 | Phụ thuộc bên ngoài lỗi (quant-fn, Bedrock); circuit đang mở |

## 2. Endpoint

### Identity
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| GET | `/me` | ✅ (P1.2) Thông tin user `{id, email, displayName, locale, createdAt}`; tự tạo lazy ở lần gọi đầu. Cần scope `jars-api/read` | 1 |
| PATCH | `/me` | Đổi `displayName`, `locale` | 1 |
| PUT | `/me/risk-profile` | Nộp câu trả lời onboarding → `{score, riskLevel}` | 1 |
| GET | `/me/risk-profile` | Risk profile hiện tại | 1 |

### Ledger: hũ và giao dịch
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| GET | `/jars` | ✅ (P1.5) `{jars: [{id, code, name, targetPct, balance}], unallocated, total, currency}`: `POOL` bị ẩn khỏi `jars`, số dư của nó là `unallocated`; `total` gồm cả `POOL` ([BR-1, BR-2](../01-product/business-rules.md)). Lần gọi đầu tự tạo hũ mặc định nếu listener chưa chạy xong | 1 |
| POST | `/jars` | Tạo hũ tùy chỉnh | 1 |
| PATCH | `/jars/{id}` | Đổi tên, `targetPct` (tổng các hũ phải = 100) | 1 |
| DELETE | `/jars/{id}` | Archive (chỉ khi số dư = 0) | 1 |
| POST | `/jars/transfers` | ✅ (P1.5) `{fromJarId, toJarId, amount, note?, occurredAt?}` → 201 + `Location`. Chuyển tiền giữa 2 hũ, **gồm cả phân bổ từ `POOL`** (`fromJarId` = id của `POOL`) (**Idempotency-Key** từ P1.6) | 1 |
| GET | `/categories` | ✅ (P1.5) `{items: [{id, name, kind, defaultJarCode, system}]}`: danh mục hệ thống (BR-3) + của user | 1 |
| GET | `/transactions?limit&cursor&jarId&categoryId&type&from&to` | Money Inbox | 1 |
| POST | `/transactions` | ✅ (P1.5) `{type: INCOME/EXPENSE, amount, jarId?, categoryId?, counterparty?, note?, occurredAt?}` → 201 + `Location` + `{transaction, jars: [{id, code, balance}]}` (số dư mới của các hũ bị ảnh hưởng). INCOME không có `jarId` thì vào `POOL`; EXPENSE bắt buộc có `jarId` khác `POOL` ([BR-2](../01-product/business-rules.md)). `amount` nhận string hoặc number, phải là số đồng nguyên dương (BR-5, `@VndAmount`) (**Idempotency-Key** từ P1.6) | 1 |
| PATCH | `/transactions/{id}` | Đổi danh mục / hũ, sinh bút toán điều chỉnh | 1 |
| GET | `/balances/daily?days=30` | Chuỗi số dư theo ngày (biểu đồ) | 1 |

### Budgeting
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| GET | `/budget/periods/{month}` | Kế hoạch và thực chi theo hũ trong tháng | 2 |
| POST | `/budget/allocation-suggestions` | Gợi ý tỷ lệ phân bổ cho khoản thu nhập `{amount}` | 2 |
| POST | `/budget/allocations` | Áp dụng phân bổ thu nhập vào các hũ (**Idempotency-Key**) | 2 |

### Goals
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| GET / POST | `/goals` | Danh sách / tạo mục tiêu | 1 |
| PATCH / DELETE | `/goals/{id}` | Sửa / archive | 1 |
| GET | `/goals/{id}/feasibility` | Khả thi? Cần tiết kiệm bao nhiêu mỗi tháng? | 2 |
| POST | `/goals/{id}/scenarios` | What-if `{monthlySavingDelta, incomeDelta, ...}` | 2 |
| GET | `/goals/{id}/roadmap` | Lộ trình theo giai đoạn (khớp type `RoadmapResponse` của UI) | 2 |

### Insights và notification
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| GET | `/dashboard?month=YYYY-MM` | Một request trả đủ: số dư, thu/chi tháng, chi theo danh mục, chi theo hũ, xu hướng 6 tháng | 1–2 |
| GET | `/anomalies?status=OPEN` | Danh sách cảnh báo | 2 |
| POST | `/anomalies/{id}/feedback` | `{label: CONFIRMED / EXPECTED / FALSE_POSITIVE}` | 2 |
| GET | `/notifications?limit&cursor` | Danh sách thông báo | 2 |
| POST | `/notifications/{id}/read` | Đánh dấu đã đọc | 2 |
| GET | `/notifications/stream` | **SSE** thông báo realtime | 2 |

### Imports
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| POST | `/imports` | Tạo phiên import → `{importId, uploadUrl, expiresAt}` | 2–3 |
| GET | `/imports/{id}` | Trạng thái + số dòng ok/lỗi | 2–3 |

### Advisor (AI)
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| POST | `/chat/sessions` | Tạo phiên chat | 4 |
| GET | `/chat/sessions/{id}/messages` | Lịch sử chat | 4 |
| POST | `/chat/sessions/{id}/messages` | Gửi tin nhắn → **SSE** stream | 4 |

### Market (giáo dục)
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| GET | `/market/quotes/{symbol}` | Giá (cache 60 s) | 4 |
| GET | `/market/history/{symbol}?from&to` | Lịch sử giá | 4 |
| POST | `/market/portfolio/optimize` | `{symbols[], riskLevel}` → trọng số (tham khảo) | 4 |

## 3. Hợp đồng SSE

Dùng **`fetch` + ReadableStream** (ví dụ thư viện `@microsoft/fetch-event-source`). `EventSource` gốc của trình duyệt **không gửi được header `Authorization`** và chỉ hỗ trợ GET.

### Chat: `POST /chat/sessions/{id}/messages`

```
event: tool
data: {"name":"getSpending","status":"started"}

event: tool
data: {"name":"getSpending","status":"done"}

event: token
data: {"text":"Tháng 10 bạn chi "}

event: token
data: {"text":"nhiều nhất cho hũ PLAY..."}

event: roadmap
data: { ...RoadmapResponse JSON... }

event: done
data: {"messageId":"0193...","usage":{"inputTokens":812,"outputTokens":164},"disclaimer":"Thông tin mang tính tham khảo..."}
```

Nếu có lỗi giữa chừng: `event: error` với `data` là ProblemDetail, sau đó đóng stream.

### Thông báo: `GET /notifications/stream`

```
id: 0193b2...            ← client gửi lại qua header Last-Event-ID khi reconnect
event: notification
data: {"id":"0193b2...","type":"ANOMALY","severity":"HIGH","title":"Hũ PLAY tăng chi bất thường","refType":"ANOMALY","refId":"..."}

: heartbeat              ← comment line mỗi 15 s, giữ kết nối qua CloudFront/ALB
```

> **Khác bản hackathon:** bản cũ trộn text và metadata (`Trace:`, `Tools:`...) trong các dòng `data:`, rồi UI phải lọc theo prefix. Bản mới dùng **named events**, nên `useChat.ts` sẽ được viết lại ở Phase 4.

## 4. Idempotency

1. Client sinh `Idempotency-Key` (UUIDv4) **một lần cho mỗi ý định của người dùng**. Nếu bấm lại do mạng lỗi thì vẫn gửi **cùng key**.
2. Server: `INSERT idempotency_keys (user_id, key, request_hash, status='IN_PROGRESS')`.
   - Nếu bị trùng khóa chính và `request_hash` giống: trả response đã lưu, hoặc trả 409 `request-in-progress` nếu request trước vẫn đang chạy.
   - Nếu `request_hash` khác: trả 422.
3. Xử lý xong thì lưu `status_code` + `response_body` **trong cùng transaction** với nghiệp vụ.
4. Key hết hạn sau 24 giờ (có job dọn dẹp).

## 5. Mapping màn hình UI → API

UI hiện tại (`ius_financial_ui`) gọi thẳng Supabase. Bảng dưới đây là danh sách việc khi chuyển UI sang API mới (Phase 1–4).

| Màn hình (file) | Hook / lời gọi hiện tại | API mới |
|---|---|---|
| Login (`pages/Login.tsx`) | `InitiateAuth USER_PASSWORD_AUTH` + client secret | Amplify Auth `signInWithRedirect` (managed login + PKCE) |
| Signup (`pages/Signup.tsx`) | **Giả** (`setTimeout`) | Managed login sign-up, rồi gọi `GET /me` |
| Onboarding (`pages/Onboarding.tsx`) | Không lưu | `PUT /me/risk-profile` |
| Profile (`pages/Profile.tsx`) | State local | `GET/PATCH /me`, `GET /me/risk-profile` |
| Dashboard (`pages/Index.tsx`) | `useLatestBalance`, `useMonthlyFlow`, `useSpendingByCategory`, `useTransactions` | `GET /dashboard?month=` + `GET /transactions?limit=5` |
| Money Inbox (`pages/MoneyInbox.tsx`) | `useTransactions`, `useCategories` | `GET /transactions`, `GET /categories`, `PATCH /transactions/{id}` |
| Transfer (`pages/Transfer.tsx`) | `useInsertTransaction`, `useJars` | `POST /transactions` với `type=EXPENSE`, `jarId` = hũ đã chọn, `counterparty` = "số tài khoản (ngân hàng)" (màn này là **chi tiền từ hũ**, không phải chuyển giữa các hũ) |
| Budget Jars (`pages/BudgetJars.tsx`) | `useJars`, `useAddJar`, `useUpdateJarAllocation`, `useDeleteJar`, `useJarSpending`, `useJarMonthlyTrend`, `useLatestBalance` | `GET/POST/PATCH/DELETE /jars`, `GET /budget/periods/{month}`, `GET /dashboard` |
| Goal Planner (`pages/GoalPlanner.tsx`) + `components/roadmap/*` (đang dùng mock) | `useGoals`, `useAddGoal`, `useDeleteGoal`, `useBalanceHistory`, `mock-roadmap.ts` | `/goals/*`, `/goals/{id}/roadmap`, `/balances/daily` |
| Alerts (`pages/Alerts.tsx`) | `useNotifications`, `useDismissNotification` | `/notifications`, `/notifications/stream`, `/anomalies/{id}/feedback` |
| AI Chat (`pages/AIChat.tsx`, `FloatingChat.tsx`) | `POST {CHAT_STREAM_URL}` + `POST {STOCK_AGENT_URL}/ask` | `/chat/sessions/*` (một endpoint; câu hỏi cổ phiếu do tool `market` xử lý) |
| `lib/api.ts` | **Không file nào dùng** | Xóa; thay bằng client sinh từ OpenAPI |
| `lib/supabase.ts`, `hooks/useSupabaseData.ts` | Supabase client | Xóa sau khi chuyển xong |
