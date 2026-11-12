# API Contract

> Nguồn sự thật cuối cùng là file **OpenAPI** do `jars-api` sinh ra (springdoc), được commit ở `contracts/openapi/jars-api.yaml`. Từ file này sinh TypeScript client cho `web` (`openapi-typescript`). Tài liệu này là bản thiết kế và giải thích các quy ước.

## 1. Quy ước chung

| Chủ đề | Quy ước |
|---|---|
| Base path | `/api/v1` (CloudFront chuyển `/api/*` sang ALB) |
| Định dạng | JSON UTF-8, field `camelCase`; tiền là **string** dạng số thập phân (`"85000.00"`) kèm `currency`, tránh JS làm tròn số |
| Thời gian | ISO-8601 UTC (`2026-11-15T03:21:09Z`); tháng dạng `YYYY-MM` |
| Auth | `Authorization: Bearer <Cognito access token>` cho mọi endpoint trừ `/api/v1/health` |
| Lỗi | **RFC 9457 `application/problem+json`** |
| Phân trang | **Cursor (keyset)**: `?limit=50&cursor=<opaque>` → `{items, nextCursor}` |
| Idempotency | Header `Idempotency-Key` (UUID) **bắt buộc** với các POST làm thay đổi tiền |
| Versioning | Chỉ đổi `/v1` → `/v2` khi phá vỡ tương thích; thêm field optional thì không đổi |
| Correlation | Response có header `traceparent` (W3C). Nên log `traceId` |

### Ví dụ lỗi

```json
{
  "type": "https://jars.local/problems/insufficient-jar-balance",
  "title": "Số dư hũ không đủ",
  "status": 422,
  "detail": "Hũ PLAY còn 120000.00 VND, yêu cầu chi 200000.00 VND",
  "instance": "/api/v1/transactions",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "errors": [{ "field": "amount", "message": "vượt số dư" }]
}
```

| HTTP | Khi nào |
|---|---|
| 400 | JSON sai cú pháp, sai kiểu dữ liệu |
| 401 | Thiếu hoặc sai token |
| 403 | Thiếu scope |
| 404 | Không tồn tại **hoặc không thuộc user** (chống IDOR) |
| 409 | Xung đột đồng thời (version hoặc deadlock) còn lại sau khi đã retry tối đa 5 lần |
| 422 | Sai quy tắc nghiệp vụ (validation, số dư, Idempotency-Key bị dùng lại với request khác) |
| 429 | Vượt rate limit (có header `Retry-After`) |
| 503 | Phụ thuộc bên ngoài lỗi (quant-fn, Bedrock); circuit đang mở |

## 2. Endpoint

### Identity
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| GET | `/me` | Thông tin user (tự tạo lazy ở lần gọi đầu) | 1 |
| PATCH | `/me` | Đổi `displayName`, `locale` | 1 |
| PUT | `/me/risk-profile` | Nộp 5 câu trả lời onboarding `{"answers":[2,3,2,2,1]}` (mỗi phần tử là chỉ số lựa chọn, thứ tự câu hỏi như UI) → `{score 0..100, riskLevel, assessedAt}`. Mỗi lần nộp thêm một dòng lịch sử; bảng điểm ở `RiskQuestionnaire` | 1 |
| GET | `/me/risk-profile` | Risk profile hiện tại (404 nếu chưa làm onboarding) | 1 |

### Ledger: hũ và giao dịch
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| GET | `/jars` | Danh sách hũ + số dư | 1 |
| POST | `/jars` | Tạo hũ tùy chỉnh | 1 |
| PATCH | `/jars/{id}` | Đổi tên, `targetPct`. Tổng tỷ lệ các hũ đang dùng phải **≤ 100** (422 `allocation-exceeds-100`); phải bằng đúng 100 khi áp dụng một lần phân bổ thu nhập | 1 |
| DELETE | `/jars/{id}` | Archive (chỉ khi số dư = 0) | 1 |
| POST | `/jars/transfers` | Chuyển tiền giữa 2 hũ (**Idempotency-Key**); hũ nguồn phải đủ tiền | 1 |
| GET | `/categories` | Danh mục (mặc định của hệ thống + của user) | 1 |
| GET | `/transactions?limit&cursor&jarId&categoryId&type&from&to` | Money Inbox | 1 |
| POST | `/transactions` | Ghi thu/chi (**Idempotency-Key**). Chi bắt buộc có `jarId`; thu có thể không có `jarId` (chỉ ghi nhận thu nhập, chưa vào hũ). Trả `201 {transaction, jarBalances[]}` và header `Idempotency-Replayed: true` khi là bản phát lại | 1 |
| PATCH | `/transactions/{id}` | Đổi danh mục và/hoặc hũ của một khoản **chi** (đổi hũ sinh bút toán `ADJUST` trả tiền về hũ cũ và `SPEND` trừ hũ mới). Thu nhập chỉ đổi được danh mục; giao dịch chuyển hũ không sửa được | 1 |
| GET | `/balances/daily?days=30` | Chuỗi số dư theo ngày (biểu đồ) | 1 |

### Budgeting
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| GET | `/budget/periods/{month}` | `{month}` dạng `YYYY-MM` (sai định dạng: 400). Kế hoạch và thực chi của **mọi hũ đang dùng** trong tháng, hũ chưa có dòng nào hiện với số 0: `{month, closed, planned, spent, jars[{jarId, code, name, planned, spent, remaining, closedAt}]}`. `planned` là tiền đã phân bổ vào hũ bằng `POST /budget/allocations` trong tháng đó; `spent` là chi tiêu trong tháng, cập nhật bất đồng bộ từ event nên có thể trễ vài trăm ms. `closed` là true khi mọi hũ đã khép sổ | 2 |
| POST | `/budget/allocation-suggestions` | Body `{amount}` (> 0, tối đa 2 chữ số thập phân; sai: 422). Trả `200 {amount, lines[{jarId, code, name, targetPct, sharePct, amount}], monthsOfHistory, historyWeight}`: chia `amount` cho các hũ đang dùng theo `targetPct`, điều chỉnh nhẹ theo chi tiêu thực tế 6 tháng gần nhất (quy tắc ở dưới). `sharePct` cộng đúng 100.00 và `amount` các dòng cộng đúng bằng số tiền nhập. Không ghi gì | 2 |
| POST | `/budget/allocations` | **Idempotency-Key bắt buộc** (thiếu hoặc sai định dạng: 400). Body `{amount, splits[{jarId, amount}], categoryId?, counterparty?, note?, occurredAt?, skip?}`. Ghi **một** khoản thu nhập qua ledger và chia vào các hũ (`LedgerApi.allocateIncome`: một giao dịch `INCOME` không gắn hũ + mỗi hũ một bút toán `ALLOCATE`, cùng một transaction), rồi ghi một dòng `allocation_decisions`. Trả `201 {decisionId, decision, transactionId, amount, month, splits[{jarId, amount, balance}]}` và `Idempotency-Replayed: true` khi là bản phát lại. `decision` là `ACCEPTED` nếu `splits` đúng bằng gợi ý hiện tại cho số tiền đó (hũ gợi ý 0 đồng có thể bỏ khỏi `splits`), ngược lại `EDITED`. `skip: true` (không kèm `splits`) chỉ ghi quyết định `SKIPPED`, không ghi giao dịch nào. Lỗi: 404 hũ không tồn tại **hoặc của người khác**; 422 `invalid-allocation` (không có `splits`, `skip` kèm `splits`), `allocation-sum-mismatch` (tổng các phần khác `amount`), hũ lặp lại, số tiền không dương, hũ đã archive; 422 `idempotency-key-reused` khi dùng lại key cho request khác (thứ tự `splits` và cách viết số như `1000` / `1000.00` không làm request khác đi); 409 khi va chạm đồng thời quá 5 lần | 2 |

**Quy tắc gợi ý phân bổ** (`AllocationPlanner`, port rút gọn từ `allocation.py` cũ): (1) bắt đầu từ `targetPct` của từng hũ (tổng không cần bằng 100, chuẩn hóa lại; không hũ nào có tỷ lệ thì chia đều); (2) nếu có lịch sử chi tiêu (số tháng trọn vẹn có chi trong 6 tháng trước tháng hiện tại, `monthsOfHistory`), trộn thêm tỷ lệ chi thực tế của từng hũ với trọng số `0.20 × min(tháng, 6) / 6` (`historyWeight`); hũ có `targetPct = 0` luôn giữ 0; (3) chuẩn hóa về đúng 100% và làm tròn tiền bằng **largest remainder** nên tổng các phần đúng bằng số tiền nhập. Bỏ so với bản cũ: suy luận "vai trò" của hũ từ tên, thác nước cố định (hóa đơn, 20% khẩn cấp, mục tiêu, sinh hoạt), thu nhập ước từ lịch sử, đề xuất chuyển tiền giữa hũ, độ tin cậy/forecast.

**Áp dụng phân bổ gồm hai bước, mỗi bước idempotent theo cùng một key:** ledger ghi tiền (idempotent theo `(user, key)`), rồi budgeting ghi `allocation_decisions` và cộng `planned_amount` đúng một lần (unique `(user, key)`). Hai bước không chung một transaction vì ledger tự retry khi va chạm optimistic lock. Nếu tiến trình chết giữa hai bước thì client gửi lại cùng request là hoàn tất được; `Idempotency-Replayed` là true chỉ khi quyết định đã được ghi từ trước.

### Goals
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| GET / POST | `/goals` | Danh sách / tạo mục tiêu | 1 |
| PATCH / DELETE | `/goals/{id}` | Sửa / archive | 1 |
| GET | `/goals/{id}/feasibility` | Khả thi? Cần tiết kiệm bao nhiêu mỗi tháng? | 2 |
| POST | `/goals/{id}/scenarios` | What-if `{monthlySavingDelta, incomeDelta, ...}` | 2 |
| GET | `/goals/{id}/roadmap` | Lộ trình theo giai đoạn. Cùng cấu trúc với `RoadmapResponse` của prototype hackathon nhưng tên field là **camelCase** (`roadmapContract`, không phải `roadmap_contract`), số tiền là số JSON thuần; kiểu của UI sinh từ OpenAPI | 2 |

### Insights và notification
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| GET | `/dashboard?month=YYYY-MM` | Một request trả đủ: số dư, thu/chi tháng, chi theo danh mục, chi theo hũ, xu hướng 6 tháng | 1–2 |
| GET | `/anomalies?status&limit&cursor` | `{items, nextCursor}`, mới nhất trước; `status` = OPEN / CONFIRMED / EXPECTED / FALSE_POSITIVE | 2 |
| POST | `/anomalies/{id}/feedback` | `{label: CONFIRMED / EXPECTED / FALSE_POSITIVE}` (nhãn khác, kể cả OPEN, trả 422; cảnh báo của người khác trả 404) | 2 |
| GET | `/notifications?limit&cursor&unreadOnly` | `{items, nextCursor, unreadCount}`, mới nhất trước | 2 |
| POST | `/notifications/{id}/read` | Đánh dấu đã đọc | 2 |
| GET | `/notifications/stream` | **SSE** thông báo realtime. Gửi comment `: connected` khi mở, `: heartbeat` mỗi 15 s; header `Last-Event-ID` phát lại tối đa 100 thông báo mới hơn id đó | 2 |

### Imports
| Method | Path | Mô tả | Phase |
|---|---|---|---|
| POST | `/imports` | `{fileName}` (đuôi `.csv`, `.tsv`, `.txt`) → 201 `{statementImport, upload: {url, method, headers, expiresAt, sameOrigin}}`. Client gửi tệp tới `upload.url`: **không** kèm token khi `sameOrigin=false` (presigned S3), kèm token khi `true` (local) | 2 ✅ |
| GET | `/imports` | 20 lần nhập gần nhất (không kèm danh sách lỗi) | 2 ✅ |
| GET | `/imports/{id}` | `status` (`AWAITING_UPLOAD`, `PROCESSING`, `COMPLETED`, `FAILED`), `totalRows`, `postedRows`, `skippedRows`, `failureReason`, `errors[]` (100 dòng lỗi đầu: `line`, `reason`). Client poll đến khi `COMPLETED`/`FAILED` | 2 ✅ |

Định dạng tệp (port từ `ingest_statement_vn` của bản hackathon, xem [hackathon-migration](../07-legacy/hackathon-migration.md)): một dòng tiêu đề, phân cách bằng dấu phẩy hoặc tab, UTF-8. Cột nhận diện không phân biệt hoa thường và dấu tiếng Việt: ngày (`date`, `transaction_date`, `ngay_giao_dich`...), số tiền có dấu (`amount`, `so_tien`) hoặc cặp ghi nợ/ghi có (`debit`/`credit`, `ghi_no`/`ghi_co`), nội dung (`description`, `memo`, `noi_dung`, `dien_giai`...), chiều tiền (`direction`, `type`). Ngày thử theo thứ tự `yyyy-MM-dd`, `dd/MM/yyyy`, `dd-MM-yyyy`, `MM/dd/yyyy`, `yyyy/MM/dd`. Số tiền đọc được `1.500.000`, `1,500,000`, `1.500.000,50`, `(45,000)`.

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
2. Server, **trong cùng transaction với nghiệp vụ**: `INSERT idempotency_keys (user_id, key, request_hash) ON CONFLICT DO NOTHING`.
   - Chèn được: tiếp tục xử lý nghiệp vụ.
   - Không chèn được (key đã có) và `request_hash` giống: trả response đã lưu. Nếu request trước chưa commit thì INSERT chờ khóa unique tới khi nó commit hoặc rollback.
   - Không chèn được và `request_hash` khác: trả 422.
3. Xử lý xong thì `UPDATE` dòng key với `status_code` + `response_body` rồi commit. Nghiệp vụ lỗi thì rollback cả dòng key, nên client gửi lại cùng key vẫn chạy được.
4. Key hết hạn sau 24 giờ (có job dọn dẹp).

> Thiết kế này thay cho bản trước dùng trạng thái `IN_PROGRESS` và mã 409 `request-in-progress`: vì INSERT nằm trong transaction nghiệp vụ nên request đồng thời tự xếp hàng trên unique index, không cần trạng thái trung gian.

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
| Transfer (`pages/Transfer.tsx`) | `useInsertTransaction`, `useJars` | `POST /transactions` hoặc `POST /jars/transfers` |
| Budget Jars (`pages/BudgetJars.tsx`) | `useJars`, `useAddJar`, `useUpdateJarAllocation`, `useDeleteJar`, `useJarSpending`, `useJarMonthlyTrend`, `useLatestBalance` | `GET/POST/PATCH/DELETE /jars`, `GET /budget/periods/{month}`, `GET /dashboard` |
| Goal Planner (`pages/GoalPlanner.tsx`) + `components/roadmap/*` (đang dùng mock) | `useGoals`, `useAddGoal`, `useDeleteGoal`, `useBalanceHistory`, `mock-roadmap.ts` | `/goals/*`, `/goals/{id}/roadmap`, `/balances/daily` |
| Alerts (`pages/Alerts.tsx`) | `useNotifications`, `useDismissNotification` | `/notifications`, `/notifications/stream`, `/anomalies/{id}/feedback` |
| AI Chat (`pages/AIChat.tsx`, `FloatingChat.tsx`) | `POST {CHAT_STREAM_URL}` + `POST {STOCK_AGENT_URL}/ask` | `/chat/sessions/*` (một endpoint; câu hỏi cổ phiếu do tool `market` xử lý) |
| `lib/api.ts` | **Không file nào dùng** | Xóa; thay bằng client sinh từ OpenAPI |
| `lib/supabase.ts`, `hooks/useSupabaseData.ts` | Supabase client | Xóa sau khi chuyển xong |
