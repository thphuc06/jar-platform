# ADR-0023: Trợ lý AI: guardrail qua ApplyGuardrail, rate limit trong PostgreSQL

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-13
- **Bổ sung cho:** [ADR-0011](0011-ai-spring-ai-bedrock.md) (Spring AI + Bedrock); thay phần "rate limit Bucket4j + Valkey" của thiết kế ban đầu.
- **Được sửa bởi:** [ADR-0026](0026-guardrail-input-pii-masking.md) và [ADR-0027](0027-advisor-product-catalog-and-profile.md) (định nghĩa denied topic): PII ở đầu vào được che, denied topic chỉ áp dụng cho đầu vào, thông điệp chặn theo lý do. Bảng và đoạn guardrail dưới đây là bản gốc.

## Bối cảnh
Khi cài đặt module `advisor` (Phase 2 WP4) có hai điểm thiết kế không làm được hoặc không đáng làm như đã viết:

1. **Guardrail.** ADR-0011 định gắn Bedrock Guardrails vào request Converse, và ghi chú "nếu Spring AI không truyền `guardrailConfig` thì gọi `ApplyGuardrail` bằng AWS SDK". Kiểm tra Spring AI 2.0.2: `BedrockChatOptions` không có tham số guardrail (chỉ model, max tokens, temperature, tool, cache...).
2. **Rate limit.** Thiết kế dùng Bucket4j trên ElastiCache Serverless Valkey, khoảng 6 USD/tháng và thêm một dịch vụ, trong khi hiện chỉ chạy **một** task API và mỗi lượt chat vốn đã ghi vào PostgreSQL (lưu tin nhắn).

## Quyết định

**Guardrail bằng `bedrock-runtime:ApplyGuardrail`, hai lần mỗi lượt chat:**

| Lần | Khi nào | Bị chặn thì |
|---|---|---|
| Đầu vào (`source=INPUT`) | Trước khi gọi model | Bị chặn: không gọi model (không tốn token); trả câu trả lời của guardrail như một tin nhắn của trợ lý. PII: che rồi đi tiếp (ADR-0026) |
| Đầu ra (`source=OUTPUT`) | Sau khi stream xong toàn bộ câu trả lời | Gửi event SSE `replace` với nội dung đã che; lưu bản đã che vào lịch sử |

Guardrail tạo bằng CDK trong `JarsApp` (không tốn phí theo giờ, chỉ tính theo lượng chữ được kiểm): denied topic "khuyến nghị mua/bán chứng khoán cụ thể" (chỉ đầu vào, ADR-0026), che PII (số thẻ, email, CCCD, số điện thoại, số tài khoản sau từ báo hiệu; ADR-0026), content filter mức medium, prompt attack filter mức high. Không cấu hình guardrail (local, test) thì bỏ qua bước này.

**Rate limit bằng PostgreSQL:** bảng `advisor_usage (user_id, window_start, window_kind)` với cửa sổ cố định theo giờ và theo ngày. Mỗi tin nhắn tăng bộ đếm bằng một câu `INSERT ... ON CONFLICT DO UPDATE ... WHERE count < limit RETURNING count`; không có dòng trả về nghĩa là đã hết lượt (429 kèm `Retry-After`). Mặc định 20 tin nhắn/giờ và 60 tin nhắn/ngày cho mỗi user; `max-tokens` 800 cho mỗi câu trả lời.

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| Không stream, kiểm guardrail đầu ra trước khi trả | Mất mục tiêu "token đầu tiên < 2 giây" (overview.md §6) |
| Kiểm đầu ra theo từng câu trong lúc stream | Nhiều lần gọi `ApplyGuardrail` (tính theo text unit), logic tách câu phức tạp; chưa cần ở quy mô demo |
| Tự viết request Converse bằng AWS SDK để gắn `guardrailConfig` | Mất tool calling, streaming và observation của Spring AI, là lý do chọn Spring AI |
| Bucket4j + Valkey | Đúng khi có nhiều task API; hiện 1 task, thêm dịch vụ và khoảng 6 USD/tháng |
| Bucket4j in-memory | Mất trạng thái khi task khởi động lại; sai khi có hơn 1 task |

## Hệ quả
- ✅ Không thêm dịch vụ trả phí; guardrail chặn được prompt injection trước khi tốn token.
- ✅ Câu "Java tính, LLM diễn giải" vẫn giữ: tool chỉ đọc, `userId` lấy từ JWT qua `ToolContext`, model không truyền được.
- ⚠️ Phần câu trả lời bị chặn ở đầu ra **đã hiện trên màn hình** trong vài giây trước khi bị thay (event `replace`). Chấp nhận được với một demo; production nên kiểm theo đoạn.
- ⚠️ Rate limit theo cửa sổ cố định cho phép dồn tới 2 lần giới hạn quanh ranh giới cửa sổ. Đủ cho mục đích chống spam chi phí.

## Xem lại khi
Có hơn một task API (chuyển rate limit sang Valkey), Spring AI hỗ trợ `guardrailConfig`, hoặc cần chặn đầu ra trước khi hiển thị.
