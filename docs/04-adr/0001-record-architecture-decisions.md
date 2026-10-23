# ADR-0001: Ghi quyết định kiến trúc bằng ADR

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
Project vừa là sản phẩm, vừa là portfolio để đi phỏng vấn. Vài tháng sau, chính tác giả sẽ quên vì sao đã chọn Aurora thay vì RDS, hay vì sao không dùng Kafka. Interviewer cũng thường hỏi "vì sao bạn chọn X?".

## Quyết định
Mỗi quyết định ảnh hưởng tới cấu trúc, chi phí, bảo mật hoặc khó đảo ngược đều được ghi thành một ADR (Markdown) trong `docs/04-adr/`, theo [template](0000-template.md). ADR đã Accepted thì không sửa; muốn đổi thì viết ADR mới thay thế.

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| Ghi trong Notion / Google Docs | Tách khỏi code, không review cùng PR, dễ lỗi thời |
| Chỉ comment trong code | Không chứa được các phương án đã loại bỏ và lý do |
| Không ghi | Mất ngữ cảnh; khó trả lời phỏng vấn |

## Hệ quả
- ✅ Có câu trả lời sẵn cho câu hỏi "vì sao"; người mới đọc là hiểu ngay.
- ⚠️ Tốn công viết. Giảm thiểu: mỗi ADR khoảng 1 trang, viết ngay lúc quyết định.

## Xem lại khi
Không cần xem lại.
