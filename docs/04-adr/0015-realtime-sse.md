# ADR-0015: Realtime bằng Server-Sent Events (SSE)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
Có 2 nhu cầu realtime: (1) stream token của chat AI, (2) đẩy thông báo (cảnh báo bất thường, import xong). Cả hai đều theo **một chiều server → client**.

## Quyết định
Dùng **SSE** (`text/event-stream`) phục vụ từ jars-api:
- Chat: `POST /chat/sessions/{id}/messages` trả stream.
- Thông báo: `GET /notifications/stream`, có `id:` để client gửi lại `Last-Event-ID` khi reconnect, và heartbeat mỗi 15 s.
- Client dùng `fetch` + ReadableStream (gửi được `Authorization`), không dùng `EventSource` gốc.
- Khi có ≥ 2 task: fan-out qua Valkey pub/sub (Phase 4+).

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| WebSocket (Spring STOMP) | Hai chiều là thừa; phải xử lý upgrade qua CloudFront/ALB; phức tạp hơn khi scale |
| API Gateway WebSocket API | Serverless và AWS-native, nhưng phải tự quản lý bảng connection (DynamoDB) và đẩy bằng `PostToConnection`; tách khỏi Spring |
| AWS AppSync Events | Pub/sub WebSocket serverless, có auth Cognito; rất hợp cho thông báo. **Phương án thay thế tốt** nếu phải scale nhiều task; bỏ qua vì muốn học SSE trong Spring và giữ ít dịch vụ |
| Polling 30 s | Đơn giản nhưng trễ và lãng phí request; không dùng được cho chat |

## Hệ quả
- ✅ Đơn giản, chỉ là HTTP; hoạt động qua CloudFront và ALB; có sẵn trong Spring MVC (`SseEmitter`) và Spring AI (`Flux`).
- ⚠️ Mỗi kết nối SSE chiếm một request đang mở. Với virtual threads thì chi phí luồng thấp, nhưng vẫn cần giới hạn số kết nối mỗi user.
- ⚠️ Cần heartbeat để không bị timeout ở các lớp proxy.

## Xem lại khi
Cần giao tiếp hai chiều thật sự (cộng tác realtime), hoặc số kết nối đồng thời vượt khả năng của vài task.
