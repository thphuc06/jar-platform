# ADR-0014: Biểu diễn tiền bằng BigDecimal/NUMERIC(19,2); sổ cái hũ append-only + số dư cache có optimistic lock

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
Bản cũ lưu `jars.allocated_amount` và `used_amount` rồi cập nhật đè trực tiếp. Khi có 2 request đồng thời (ví dụ 2 tab cùng ghi chi tiêu) sẽ xảy ra **lost update**, và không còn lịch sử để đối soát. Phỏng vấn fintech rất hay hỏi "vì sao không dùng double cho tiền" và "xử lý cập nhật số dư đồng thời thế nào".

## Quyết định
1. **Kiểu dữ liệu:** `NUMERIC(19,2)` trong DB; `BigDecimal` trong Java, bọc trong `record Money(BigDecimal amount, Currency currency)`. Phép chia luôn chỉ rõ `RoundingMode.HALF_EVEN` (banker's rounding). JSON gửi tiền dưới dạng **string**.
2. **Sổ cái hũ:** bảng `jar_entries` **chỉ được INSERT**; mỗi entry có `amount` có dấu và `balance_after`.
3. **Số dư cache:** `jars.balance` + `jars.version` (`@Version`). Mỗi bút toán: đọc hũ → kiểm tra quy tắc → insert entry → update balance kèm điều kiện version, tất cả trong 1 transaction. Xung đột thì retry tối đa 3 lần.
4. **Giao dịch không bao giờ bị xóa hay sửa số tiền.** Muốn sửa thì ghi bút toán `ADJUST`, hoặc đổi danh mục/hũ (sinh cặp entry đảo).
5. **Bất biến được kiểm tra bằng test:** `jars.balance == SUM(jar_entries.amount)` cho mọi hũ; có một job đối soát chạy hằng đêm (Phase 6) để phát hiện lệch.

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| `double` / `float` | Sai số nhị phân (0.1 + 0.2 ≠ 0.3), không chấp nhận với tiền |
| `long` lưu đơn vị nhỏ nhất (minor units) | Rất phổ biến ở hệ thống thanh toán, nhanh. Không chọn vì JPA/JSON với `BigDecimal` dễ đọc hơn cho người học, và VND không có minor unit (dễ nhầm). **Ghi lại để kể khi phỏng vấn** |
| Chỉ tính số dư bằng `SUM()` mỗi lần đọc | Đúng tuyệt đối nhưng chậm dần theo số giao dịch; vẫn cần khóa để kiểm tra "đủ số dư" |
| Pessimistic lock (`SELECT ... FOR UPDATE`) | Đúng và đơn giản, nhưng giữ khóa lâu hơn. Optimistic phù hợp vì xung đột hiếm (1 user ít khi ghi đồng thời). Viết test minh họa cả hai cách |
| Double-entry đầy đủ (tài khoản Nợ/Có) | Quá mức cần thiết cho app cá nhân; `jar_entries` là "single-entry có kiểm soát" |

## Hệ quả
- ✅ Không mất cập nhật; có lịch sử đầy đủ; dễ đối soát.
- ✅ Là chủ đề phỏng vấn tốt: isolation level, lost update, optimistic vs pessimistic locking.
- ⚠️ Tốn thêm bảng và code. Nhớ đánh index `jar_entries(jar_id, created_at)`.

## Xem lại khi
Hỗ trợ đa tiền tệ có quy đổi tỷ giá, hoặc chia sẻ hũ giữa nhiều người (xung đột tăng).
