# Business Rules

> Quy tắc nghiệp vụ được rút ra ở kickoff Phase 1 (2026-10-23), từ UI cũ (`ius_financial_ui`: `mock-data.ts`, `Onboarding.tsx`, `Transfer.tsx`, `BudgetJars.tsx`) và backend cũ (`taxonomy.py`, `sparkov_category_mapping.json`). Code phải khớp tài liệu này. Khi đổi quy tắc thì sửa ở đây trước, sau đó mới sửa code và test.

## BR-1. Hũ mặc định

Khi user được tạo (event `UserRegistered`), module `ledger` tạo **6 hũ theo phương pháp 6 Jars** cùng **1 hũ hệ thống**:

| Code | Tên hiển thị (vi) | Tên UI cũ | `target_pct` | Ghi chú |
|---|---|---|---|---|
| `NEC` | Thiết yếu | Necessities | 55 | Ăn uống, nhà ở, hóa đơn, đi lại |
| `FFA` | Tự do tài chính | Investment | 10 | Đầu tư, tạo thu nhập thụ động; **không tiêu** |
| `LTSS` | Tiết kiệm dài hạn | Savings | 10 | Tiết kiệm cho khoản chi lớn trong tương lai |
| `EDU` | Giáo dục | Education | 10 | Học tập, sách, khóa học |
| `PLAY` | Hưởng thụ | Play | 10 | Giải trí, mua sắm, du lịch |
| `GIVE` | Cho đi | Giving | 5 | Từ thiện, quà tặng |
| `POOL` | Chưa phân bổ | (tổng trừ phần đã phân bổ) | – | **Hũ hệ thống, ẩn**: thu nhập vào đây trước. Không xóa được, không đổi tỷ lệ |

- Tổng `target_pct` của các hũ **không phải** `POOL` phải bằng **100** (kiểm tra khi PATCH).
- User được tạo thêm hũ `CUSTOM` và đổi tên hũ. Hũ chỉ archive được khi số dư = 0. `POOL` thì không bao giờ archive.

## BR-2. Các loại giao dịch và cách đi qua sổ cái

| Loại (`type`) | Ví dụ | Bút toán `jar_entries` | Màn hình |
|---|---|---|---|
| `INCOME` | Lương, freelance | `+amount` vào `POOL` (hoặc vào hũ chỉ định nếu user chọn) | Thêm thu nhập |
| `EXPENSE` | Ăn trưa, trả tiền nhà, chuyển khoản cho người khác | `−amount` ở hũ được chọn (**bắt buộc** có `jarId`, không được là `POOL`) | Quick add, **Transfer** (UI cũ: chọn hũ → ngân hàng + số tài khoản = `counterparty`) |
| `JAR_TRANSFER` | Phân bổ lương vào các hũ; chuyển từ PLAY sang LTSS | 2 bút toán: `−amount` ở hũ nguồn, `+amount` ở hũ đích, trong **cùng 1 transaction DB** | Budget Jars (phân bổ), chuyển hũ |

- `amount` luôn **> 0**; hướng tiền do `type` quyết định.
- **Không cho chi vượt số dư hũ** (mặc định). Thiếu số dư thì trả 422 `insufficient-jar-balance`. Cờ cho phép âm (thấu chi) để sau, nếu cần.
- Tổng số dư của user = Σ `balance` mọi hũ (kể cả `POOL`). "Đã phân bổ" = Σ các hũ ≠ `POOL`. Hai con số này đúng với màn Budget Jars cũ.
- Sửa giao dịch: chỉ đổi được `categoryId`, `note`, và **`jarId` (đổi hũ thì sinh cặp bút toán đảo)**. Không sửa `amount`; muốn đổi số tiền thì tạo giao dịch điều chỉnh. Chi tiết (✅ P1.7):
  - Chuyển một **EXPENSE** sang hũ khác: hũ cũ được hoàn tiền, hũ mới phải đủ số dư và không được là `POOL`.
  - Chuyển một **INCOME** sang hũ khác: hũ cũ phải **còn đủ** số tiền đó. Nếu thu nhập đã được phân bổ hoặc tiêu từ `POOL` thì không lấy lại được (422 `insufficient-jar-balance`).
  - **JAR_TRANSFER**: chỉ sửa `note`. Không có danh mục; muốn đổi hũ thì ghi một lần chuyển mới.

## BR-3. Danh mục mặc định (`categories.user_id IS NULL`)

Mỗi danh mục có `default_jar_code` để UI gợi ý hũ khi user chọn danh mục (map từ `taxonomy.py` và mapping Sparkov cũ sang 6 hũ).

| Danh mục | `kind` | Hũ gợi ý |
|---|---|---|
| Ăn uống | EXPENSE | NEC |
| Đi chợ / siêu thị | EXPENSE | NEC |
| Nhà ở & hóa đơn (điện, nước, internet) | EXPENSE | NEC |
| Di chuyển | EXPENSE | NEC |
| Sức khỏe | EXPENSE | NEC |
| Học tập | EXPENSE | EDU |
| Mua sắm | EXPENSE | PLAY |
| Giải trí | EXPENSE | PLAY |
| Du lịch | EXPENSE | PLAY |
| Đầu tư | EXPENSE | FFA |
| Từ thiện & quà tặng | EXPENSE | GIVE |
| Khác (chi) | EXPENSE | NEC |
| Lương | INCOME | POOL |
| Thu nhập tự do | INCOME | POOL |
| Thu nhập khác | INCOME | POOL |

## BR-4. Bảng hỏi khẩu vị rủi ro (onboarding)

5 câu lấy nguyên từ `Onboarding.tsx` cũ. Code cũ **không chấm điểm**; quy tắc chấm dưới đây là **thiết kế mới**.

| # | Câu hỏi | Lựa chọn → điểm |
|---|---|---|
| Q1 | Mức chấp nhận rủi ro | Conservative 0 · Moderate 2 · Aggressive 4 |
| Q2 | Khi nào cần dùng tiền | < 2 năm 0 · 2–5 năm 1 · 5–10 năm 3 · > 10 năm 4 |
| Q3 | Nhu cầu thanh khoản | Cần ngay 0 · Trong 1 tháng 1 · Linh hoạt 3 |
| Q4 | Kinh nghiệm đầu tư | Mới bắt đầu 0 · Có kinh nghiệm 1 · Nhiều kinh nghiệm 2 |
| Q5 | Mục tiêu chính | Quỹ khẩn cấp · Tăng tài sản dài hạn · Mục tiêu cụ thể · Thu nhập thụ động (**không tính điểm**, chỉ lưu lại để advisor dùng) |

- Tổng điểm 0–13 → **`CONSERVATIVE`** (0–4), **`BALANCED`** (5–8), **`GROWTH`** (9–13).
- **Quy tắc trần:** Q3 = "Cần ngay" **hoặc** Q2 = "< 2 năm" thì mức tối đa là `CONSERVATIVE`, bất kể tổng điểm (tiền cần dùng sớm thì không nên chịu rủi ro).
- Có thể làm lại bảng hỏi; mỗi lần làm lưu thành một bản ghi `risk_assessments` mới; bản mới nhất là bản có hiệu lực.
- Mã gửi qua API (P1.8), theo đúng thứ tự cột "Lựa chọn" ở trên:

| Field | Giá trị |
|---|---|
| `riskTolerance` (Q1) | `CONSERVATIVE`, `MODERATE`, `AGGRESSIVE` |
| `horizon` (Q2) | `UNDER_2_YEARS`, `YEARS_2_TO_5`, `YEARS_5_TO_10`, `OVER_10_YEARS` |
| `liquidity` (Q3) | `IMMEDIATE`, `WITHIN_A_MONTH`, `FLEXIBLE` |
| `experience` (Q4) | `BEGINNER`, `EXPERIENCED`, `VERY_EXPERIENCED` |
| `mainGoal` (Q5) | `EMERGENCY_FUND`, `LONG_TERM_GROWTH`, `SPECIFIC_GOAL`, `PASSIVE_INCOME` |

- Mỗi bản ghi lưu kèm `questionnaire_version` (hiện là `1`): khi đổi câu hỏi hoặc cách chấm thì tăng version, các bản ghi cũ vẫn đọc được đúng như lúc chấm.

## BR-5. Tiền tệ và làm tròn

- MVP chỉ dùng **VND**; `currency` luôn là `VND`. Số tiền lưu `NUMERIC(19,2)`, nhưng VND nhập và hiển thị **không có phần lẻ**: API từ chối số tiền có phần thập phân khác 0 (422).
- Phép chia (ví dụ chia lương theo %) làm tròn `HALF_EVEN` về đơn vị đồng. **Phần dư do làm tròn dồn vào `NEC`**, để tổng phân bổ luôn bằng đúng số tiền gốc.

## BR-6. Mục tiêu tiết kiệm (goals)

| Quy tắc | Chi tiết |
|---|---|
| Trường bắt buộc | `name` (1–100 ký tự), `targetAmount` (số đồng nguyên dương, BR-5), `targetDate` |
| Ngày đích | Phải **sau hôm nay theo giờ Việt Nam** (`Asia/Ho_Chi_Minh`), kiểm tra khi tạo và khi đổi ngày. Server chạy UTC nên "hôm nay" phải tính theo múi giờ VN, không theo đồng hồ máy |
| Hũ nguồn (`fundingJarId`) | Tùy chọn. Phải là hũ của chính user, chưa archive, **không phải `POOL`** (tiền chưa phân bổ không dành cho mục tiêu nào). Gửi `null` để bỏ liên kết |
| Tiến độ | Khi có hũ nguồn: `progressPct = floor(số dư hũ × 100 / targetAmount)`, tối đa 100. Làm tròn **xuống** để không bao giờ báo 100% khi chưa đủ tiền. Nhiều mục tiêu có thể dùng chung một hũ (MVP không chia tiền giữa các mục tiêu) |
| Trạng thái | `ACTIVE` ⇄ `ACHIEVED` do user tự đánh dấu (chưa tự động); `DELETE` chuyển sang `ARCHIVED` (xóa mềm). Mục tiêu đã archive không hiện trong danh sách và không sửa được |
| Khả thi, what-if, lộ trình | Phase 2 (P2.7), port từ `goal.py`, `scenario.py` cũ |
