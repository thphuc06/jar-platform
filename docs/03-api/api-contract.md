# API Contract

> Nguồn sự thật cuối cùng là file **OpenAPI** do `jars-api` sinh ra (springdoc), được commit ở [`contracts/openapi/jars-api.yaml`](../../contracts/openapi/jars-api.yaml) (✅ P1.10, [ADR-0019](../04-adr/0019-code-first-openapi-contract.md)). `OpenApiContractTest` bảo đảm file đó khớp với API, security trong file đúng với filter chain, và response thật của mọi operation khớp schema. Từ file này sinh TypeScript client cho `web` (`openapi-typescript`). Tài liệu này là bản thiết kế và giải thích các quy ước.

## 1. Quy ước chung

| Chủ đề | Quy ước |
|---|---|
| Base path | `/api/v1` (CloudFront chuyển `/api/*` sang ALB) |
| Định dạng | JSON UTF-8, field `camelCase`; tiền là **string** dạng số thập phân (`"85000.00"`) kèm `currency`, tránh JS làm tròn số |
| Thời gian | ISO-8601 UTC (`2026-11-15T03:21:09Z`); tháng dạng `YYYY-MM` |
| `null` và field vắng | Response **luôn có đủ mọi field**, kể cả khi giá trị là `null` (OpenAPI: mọi property đều `required`). Field có thể `null` được khai báo bằng JSpecify `@Nullable` trên DTO, hiện trong OpenAPI là `type: [..., "null"]` hoặc `oneOf: [$ref, null]`, và trong TypeScript là `T \| null` (✅ P1.10). Request: field không bắt buộc thì có thể bỏ qua hoặc gửi `null`; với PATCH, bỏ qua và `null` khác nghĩa nhau (§2) |
| Auth | `Authorization: Bearer <Cognito access token>` cho mọi endpoint trừ `/api/v1/health`. Metadata RFC 9728 công khai tại `/.well-known/oauth-protected-resource` |
| Lỗi | **RFC 9457 `application/problem+json`**, luôn có `type`, `title`, `status`, `detail`, `instance`, **`traceId`**. `type` = `https://jars.local/problems/{code}`: `code` là định danh ổn định (UI dịch thông báo theo `code`, còn `detail` là tiếng Anh dành cho developer). Validation có thêm `errors: [{field, message}]` |
| Phân trang | **Cursor (keyset)**: `?limit=50&cursor=<opaque>` → `{items, nextCursor}`; `nextCursor = null` ở trang cuối; cursor sửa tay → 422 `invalid-cursor` (✅ P1.7). Không có tổng số dòng: đếm `COUNT(*)` mỗi trang là tốn kém và UI cuộn vô hạn không cần |
| Idempotency | Header `Idempotency-Key` (UUID) **bắt buộc** với các POST làm thay đổi tiền |
| Versioning | Chỉ đổi `/v1` → `/v2` khi phá vỡ tương thích; thêm field optional thì không đổi |
| Correlation | Gửi `traceparent` (W3C) thì server tiếp tục trace đó; không gửi thì server tạo mới. Response **luôn** có header `traceparent`. Mọi dòng log phía server đều có `traceId` (✅ P1.3) |

### Mã lỗi (`code`) hiện có (✅ P1.3)

| `code` | HTTP | Khi nào |
|---|---|---|
| `validation-failed` | 422 | `@Valid` body hoặc ràng buộc trên `@RequestParam`/`@PathVariable` sai; kèm `errors[]` |
| `{resource}-not-found` | 404 | Ví dụ `jar-not-found`. Không tồn tại **hoặc** không thuộc user |
| *(mã nghiệp vụ)* | 422 | ✅ P1.5: `insufficient-jar-balance`, `expense-requires-jar`, `expense-from-pool`, `jar-archived`, `category-kind-mismatch`, `same-jar-transfer`, `use-transfer-endpoint`, `occurred-at-in-future` (lệch quá 5 phút); P1.7: `transfer-has-no-category`, `transfer-jars-immutable`; P1.8: `target-date-not-in-future`, `funding-jar-is-pool`, `funding-jar-archived`, `use-delete-to-archive` |
| `email-already-registered` | 409 | Email đã gắn với tài khoản Cognito khác |
| `invalid-cursor` | 422 | `cursor` không phải do API trả về (✅ P1.7) |
| `idempotency-key-reused` | 422 | Cùng `Idempotency-Key` đã dùng cho một request khác (✅ P1.6, [§4](#4-idempotency)) |
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
| PUT | `/me/risk-profile` | ✅ (P1.8) `{riskTolerance, horizon, liquidity, experience, mainGoal}` (mã ở [BR-4](../01-product/business-rules.md#br-4-bảng-hỏi-khẩu-vị-rủi-ro-onboarding), đủ 5 câu) → 200 `{answers, score, maxScore, riskLevel, capped, questionnaireVersion, assessedAt}`. Mỗi lần gửi lưu một bản ghi mới | 1 |
| GET | `/me/risk-profile` | ✅ (P1.8) Hồ sơ hiện tại (bản mới nhất); chưa làm bảng hỏi → 404 `risk-profile-not-found` | 1 |

### Ledger: hũ và giao dịch
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| GET | `/jars` | ✅ (P1.5) `{jars: [{id, code, name, targetPct, balance}], unallocated, total, currency}`: `POOL` bị ẩn khỏi `jars`, số dư của nó là `unallocated`; `total` gồm cả `POOL` ([BR-1, BR-2](../01-product/business-rules.md)). Lần gọi đầu tự tạo hũ mặc định nếu listener chưa chạy xong | 1 |
| POST | `/jars` | Tạo hũ tùy chỉnh | 1 |
| PATCH | `/jars/{id}` | Đổi tên, `targetPct` (tổng các hũ phải = 100) | 1 |
| DELETE | `/jars/{id}` | Archive (chỉ khi số dư = 0) | 1 |
| POST | `/jars/transfers` | ✅ (P1.5) `{fromJarId, toJarId, amount, note?, occurredAt?}` → 201 + `Location`. Chuyển tiền giữa 2 hũ, **gồm cả phân bổ từ `POOL`** (`fromJarId` = id của `POOL`) (**Idempotency-Key bắt buộc**, ✅ P1.6) | 1 |
| GET | `/categories` | ✅ (P1.5) `{items: [{id, name, kind, defaultJarCode, system}]}`: danh mục hệ thống (BR-3) + của user | 1 |
| GET | `/transactions?limit&cursor&jarId&categoryId&type&from&to` | ✅ (P1.7) Money Inbox, mới nhất trước. `limit` 1–100 (mặc định 50); `jarId` khớp cả giao dịch chuyển **vào** hũ; `from` bao gồm, `to` không bao gồm (ISO-8601). Mỗi item có `jar {id, code, name}`, `toJar`, `category {id, name}`, nên UI không phải tra thêm | 1 |
| GET | `/transactions/{id}` | ✅ (P1.7) Một giao dịch, cùng dạng với item ở trên; của user khác → 404 | 1 |
| POST | `/transactions` | ✅ (P1.5) `{type: INCOME/EXPENSE, amount, jarId?, categoryId?, counterparty?, note?, occurredAt?}` → 201 + `Location` + `{transaction, jars: [{id, code, balance}]}` (số dư mới của các hũ bị ảnh hưởng). INCOME không có `jarId` thì vào `POOL`; EXPENSE bắt buộc có `jarId` khác `POOL` ([BR-2](../01-product/business-rules.md)). `amount` nhận string hoặc number, phải là số đồng nguyên dương (BR-5, `@VndAmount`) (**Idempotency-Key bắt buộc**, ✅ P1.6) | 1 |
| PATCH | `/transactions/{id}` | ✅ (P1.7) `{categoryId?, note?, jarId?}` theo kiểu merge-patch: **không gửi** = giữ nguyên, gửi **`null`** = xóa (`categoryId`, `note`). Đổi hũ sinh cặp bút toán `ADJUST`; trả 200 `{transaction, jars}` (các hũ đổi số dư). Tự nhiên idempotent nên **không** cần Idempotency-Key. Giao dịch `JAR_TRANSFER` chỉ sửa được `note` | 1 |
| GET | `/balances/daily?days=30` | ✅ (P1.9) `{items: [{date, balance}], currency}`: tổng số dư cuối mỗi ngày (giờ VN) trong `days` ngày (1–366), cũ nhất trước, hôm nay cuối; tính theo `occurredAt` (số dư đầu kỳ + cộng dồn) | 1 |

### Budgeting
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| GET | `/budget/periods/{month}` | Kế hoạch và thực chi theo hũ trong tháng | 2 |
| POST | `/budget/allocation-suggestions` | Gợi ý tỷ lệ phân bổ cho khoản thu nhập `{amount}` | 2 |
| POST | `/budget/allocations` | Áp dụng phân bổ thu nhập vào các hũ (**Idempotency-Key**) | 2 |

### Goals
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| GET / POST | `/goals` | ✅ (P1.8) Danh sách `{items}` (chưa archive, ngày đích gần nhất trước) / tạo `{name, targetAmount, targetDate, fundingJarId?}` → 201 + `Location`. Mỗi mục tiêu có `fundingJar {id, code, name, balance, archived}` và `progressPct` ([BR-6](../01-product/business-rules.md#br-6-mục-tiêu-tiết-kiệm-goals)) | 1 |
| GET / PATCH / DELETE | `/goals/{id}` | ✅ (P1.8) Xem / sửa (merge-patch: không gửi = giữ, `"fundingJarId": null` = bỏ hũ; `status` ACTIVE ⇄ ACHIEVED) / archive (204, idempotent) | 1 |
| GET | `/goals/{id}/feasibility` | Khả thi? Cần tiết kiệm bao nhiêu mỗi tháng? | 2 |
| POST | `/goals/{id}/scenarios` | What-if `{monthlySavingDelta, incomeDelta, ...}` | 2 |
| GET | `/goals/{id}/roadmap` | Lộ trình theo giai đoạn (khớp type `RoadmapResponse` của UI) | 2 |

### Insights và notification
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| GET | `/dashboard?month=YYYY-MM` | ✅ (P1.9, trong module ledger) Một request trả đủ: `balances {total, unallocated, allocated}`, `income`, `expense`, `net` của tháng, `byCategory [{categoryId, name, amount}]` (lớn nhất trước; không có danh mục → `null`), `byJar [{jarId, code, name, balance, spent}]` (mọi hũ trừ POOL), `trend` 6 tháng kết thúc ở `month` (tháng trống = 0). Tháng theo **giờ Việt Nam**, mặc định là tháng hiện tại. Chuyển sang read model của `insights` ở P2.3 | 1–2 |
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

✅ Cài đặt ở P1.6 cho `POST /transactions` và `POST /jars/transfers`.

1. Client sinh `Idempotency-Key` (UUID) **một lần cho mỗi ý định của người dùng**. Nếu gửi lại do mạng lỗi hoặc timeout thì vẫn dùng **cùng key và cùng body**. Header **bắt buộc**: thiếu hoặc không phải UUID thì 400.
2. Key được tính **theo từng user** (khóa chính `(user_id, idempotency_key)`) và sống **24 giờ**.
3. Server giữ chỗ key **ngay đầu transaction nghiệp vụ** (`INSERT ... ON CONFLICT`) và lưu kết quả **ở cuối cùng transaction đó**. Key và việc trừ tiền vì vậy cùng commit hoặc cùng rollback, không có lúc tiền đã đi mà key chưa được lưu.
4. Kết quả:

| Tình huống | Response |
|---|---|
| Key mới | Thực hiện, 201 |
| Cùng key, cùng request (kể cả `85000` và `"85000.00"`, vì số tiền được chuẩn hóa trước khi băm) | **201 với đúng body và `Location` của lần đầu**, kèm header `Idempotent-Replayed: true`; không ghi gì thêm |
| Cùng key, request khác, hoặc cùng key nhưng ở endpoint khác | 422 `idempotency-key-reused` |
| Hai request cùng key đến **đồng thời** | Request thứ hai **chờ** khóa của dòng key cho tới khi request đầu kết thúc, rồi replay kết quả của nó. Không trả 409 `request-in-progress` như thiết kế ban đầu: client không phải xử lý thêm trường hợp nào |
| Lần đầu thất bại (ví dụ 422 thiếu số dư) | **Không lưu**: transaction rollback kéo theo dòng key. Gửi lại cùng key sau khi sửa nguyên nhân thì được thực hiện. (Stripe lưu cả response lỗi; ở đây lần lỗi không thay đổi gì nên không cần) |
| Key đã hết hạn | Request mới chiếm lại key (`ON CONFLICT DO UPDATE ... WHERE expires_at <= now`) |

5. Dọn key hết hạn: chưa có job (key hết hạn vẫn được chiếm lại đúng; bảng chỉ lớn dần). Job dọn dẹp ghi ở P2.1.

## 5. Mapping màn hình UI → API

UI cũ (`ius_financial_ui`) gọi thẳng Supabase. Bảng dưới đây là danh sách việc khi chuyển UI sang API mới (Phase 1–4); ✅ = đã chuyển vào `apps/web`.

| Màn hình (file) | Hook / lời gọi hiện tại | API mới |
|---|---|---|
| Login (`pages/Login.tsx`) | `InitiateAuth USER_PASSWORD_AUTH` + client secret | ✅ P1.11a: bỏ trang; `oidc-client-ts` chuyển tới managed login (Authorization Code + PKCE), về `/auth/callback` ([ADR-0020](../04-adr/0020-spa-auth-oidc-client-ts.md)) |
| Signup (`pages/Signup.tsx`) | **Giả** (`setTimeout`) | ✅ P1.11a: bỏ trang; đăng ký nằm trên managed login. Request đầu tiên tạo user (F1) |
| Onboarding (`pages/Onboarding.tsx`) | Không lưu | ✅ P1.11a: `PUT /me/risk-profile` (5 câu khớp 1:1 với enum); user chưa có hồ sơ được đưa tới đây ngay sau đăng nhập |
| Profile (`pages/Profile.tsx`) | State local (tên, điện thoại, ngày sinh gõ cứng) | ✅ P1.11a: viết lại với `GET /me`, `GET /me/risk-profile`, logout. Sửa tên (`PATCH /me`) chưa có API |
| Dashboard (`pages/Index.tsx`) | `useLatestBalance`, `useMonthlyFlow`, `useSpendingByCategory`, `useTransactions` | `GET /dashboard?month=` + `GET /transactions?limit=5` |
| Money Inbox (`pages/MoneyInbox.tsx`) | `useTransactions`, `useCategories` | `GET /transactions`, `GET /categories`, `PATCH /transactions/{id}` |
| Transfer (`pages/Transfer.tsx`) | `useInsertTransaction`, `useJars` | `POST /transactions` với `type=EXPENSE`, `jarId` = hũ đã chọn, `counterparty` = "số tài khoản (ngân hàng)" (màn này là **chi tiền từ hũ**, không phải chuyển giữa các hũ) |
| Budget Jars (`pages/BudgetJars.tsx`) | `useJars`, `useAddJar`, `useUpdateJarAllocation`, `useDeleteJar`, `useJarSpending`, `useJarMonthlyTrend`, `useLatestBalance` | `GET/POST/PATCH/DELETE /jars`, `GET /budget/periods/{month}`, `GET /dashboard` |
| Goal Planner (`pages/GoalPlanner.tsx`) + `components/roadmap/*` (đang dùng mock) | `useGoals`, `useAddGoal`, `useDeleteGoal`, `useBalanceHistory`, `mock-roadmap.ts` | `/goals/*`, `/goals/{id}/roadmap`, `/balances/daily` |
| Alerts (`pages/Alerts.tsx`) | `useNotifications`, `useDismissNotification` | `/notifications`, `/notifications/stream`, `/anomalies/{id}/feedback` |
| AI Chat (`pages/AIChat.tsx`, `FloatingChat.tsx`) | `POST {CHAT_STREAM_URL}` + `POST {STOCK_AGENT_URL}/ask` | `/chat/sessions/*` (một endpoint; câu hỏi cổ phiếu do tool `market` xử lý) |
| `lib/api.ts` | **Không file nào dùng** | Xóa; thay bằng client sinh từ OpenAPI |
| `lib/supabase.ts`, `hooks/useSupabaseData.ts` | Supabase client | Xóa sau khi chuyển xong |
