# ADR-0005: Region ap-southeast-1 (Singapore)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
Người dùng ở Việt Nam. Bản hackathon chạy ở us-east-1 (rẻ nhất, có đủ model). Tài khoản hiện có resource ở cả us-east-1 (FairTable) lẫn ap-southeast-1 (sidewalk).

## Quyết định
Mọi resource của Jars Platform đặt ở **ap-southeast-1**, trừ những thứ AWS bắt buộc đặt ở us-east-1 (ACM certificate cho CloudFront nếu dùng domain riêng; CloudFront và WAF cho CloudFront là global).

Đã kiểm tra trên tài khoản ngày 2026-10-23: ở ap-southeast-1 có inference profile `global.anthropic.claude-haiku-5-5`, `global.anthropic.claude-sonnet-4-6` và model `cohere.embed-multilingual-v3`. S3 Vectors có ở Singapore từ khi GA (12/2025).

> **Đính chính (P0.5, 2026-10-23):** "có inference profile" **không đồng nghĩa** với "gọi được". Khi gọi thật thì `claude-haiku-5-5` bị `AccessDenied` với account này; gọi được `claude-haiku-4-5`, `claude-sonnet-4-6`, `claude-sonnet-4-5` (xem [ADR-0004](0004-java-25-spring-boot-4.md#kết-quả-smoke-test-p05-2026-10-23)). Quyết định chọn region không đổi.

## Các phương án đã cân nhắc

| Phương án | Ưu | Nhược |
|---|---|---|
| us-east-1 | Rẻ hơn khoảng 10–25%; có model và tính năng mới sớm nhất | Độ trễ từ Việt Nam khoảng 200–250 ms mỗi lượt; SSE chat cảm giác chậm |
| ap-southeast-1 ✅ | Độ trễ khoảng 30–50 ms; gần người dùng; phù hợp câu chuyện "hệ thống cho người Việt" | Giá cao hơn (ví dụ Fargate ARM $0.04045 so với $0.03238 mỗi vCPU-giờ); model Claude mới thường đi qua **global** inference profile |

## Hệ quả
- ✅ Trải nghiệm tốt hơn; dữ liệu nghiệp vụ (Aurora, S3) nằm ở Singapore.
- ⚠️ Global inference profile có thể xử lý prompt ở region khác. Với dev thì chấp nhận được. Một ngân hàng thật sẽ phải đánh giá theo quy định về dữ liệu cá nhân và nội địa hóa dữ liệu.
- ⚠️ Phải chạy `cdk bootstrap` cho ap-southeast-1 (hiện chỉ đã bootstrap us-east-1).

## Xem lại khi
Model hoặc dịch vụ cần dùng không có ở Singapore, hoặc chi phí chênh lệch trở nên đáng kể.
