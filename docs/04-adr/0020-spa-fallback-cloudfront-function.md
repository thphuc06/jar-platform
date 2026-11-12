# ADR-0020: SPA fallback bằng CloudFront Function, không dùng custom error response

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-12
- **Liên quan:** bổ sung [ADR-0007](0007-edge-cloudfront-vpc-origin.md) (không thay thế). Điều chỉnh dòng "SPA fallback (403/404 → `/index.html`)" ở [aws-infrastructure.md §2](../02-architecture/aws-infrastructure.md#2-edge-cloudfront--s3--vpc-origin) và sơ đồ F7 trong [flows.md](../02-architecture/flows.md#f7).

## Bối cảnh
Thiết kế ban đầu cho SPA quay về `/index.html` khi S3 trả 403/404 bằng **custom error response** của CloudFront (route như `/auth/callback` không phải là file trong bucket). Nhưng custom error response được cấu hình ở **cấp distribution**, không theo từng behavior. Distribution của project phục vụ cả `/*` (S3) lẫn `/api/*` (ALB qua VPC origin), nên mọi 403/404 của **API** cũng bị thay bằng nội dung `/index.html` với status 200.

Hậu quả cụ thể:
- API cố ý trả **404** khi user B gọi tài nguyên của user A ([security.md §2](../02-architecture/security.md#2-phân-quyền-authorization), test IDOR). Qua CloudFront, 404 đó thành HTML 200, frontend không phân biệt được "không tìm thấy" với "thành công" và test IDOR qua edge sẽ báo sai.
- `ProblemDetail` 403/404 khác của API cũng mất.

## Quyết định
- **Không cấu hình custom error response** cho distribution.
- Gắn một **CloudFront Function** `jars-dev-spa-rewrite` (runtime `cloudfront-js-2.0`, sự kiện `viewer-request`) **chỉ vào default behavior** (S3): nếu đoạn cuối của đường dẫn **không có dấu chấm** thì viết lại thành `/index.html`. Đường dẫn có đuôi file (`/assets/app-abc.js`) đi thẳng tới S3.
- Behavior `/api/*` không có function nên giữ nguyên mọi status của API.

## Các phương án đã cân nhắc

| Phương án | Ưu | Nhược | Kết luận |
|---|---|---|---|
| Custom error response 403/404 → `/index.html` (thiết kế cũ) | Cấu hình sẵn có, không cần code | Áp dụng cho cả `/api/*`: nuốt 403/404 của API | Loại |
| Hai distribution (web và API) | Cách ly hoàn toàn | Hai domain: phải cấu hình CORS, đi ngược [ADR-0007](0007-edge-cloudfront-vpc-origin.md); thêm chi phí và cấu hình | Loại |
| Lambda@Edge | Linh hoạt | Đắt và phức tạp hơn nhiều so với nhu cầu; VPC origin không hỗ trợ trigger origin request/response của Lambda@Edge | Loại |
| S3 static website hosting (index/error document) | Có sẵn index/error document | Bucket phải public, mất Origin Access Control | Loại |
| Route SPA có tiền tố riêng (ví dụ `/app/*`) | Không cần function | Ràng buộc UI; vẫn cần quy tắc rewrite | Loại |
| **CloudFront Function ở viewer-request của default behavior** | Chỉ tác động `/*`; không cần quyền `s3:ListBucket` cho OAC; rẻ | Một đoạn JS nhỏ phải bảo trì | **Chọn** |

## Hệ quả
- ✅ API trả đúng 401/403/404/5xx; test IDOR qua CloudFront có nghĩa.
- ✅ Origin Access Control chỉ cần `s3:GetObject`; file thiếu có đuôi (ví dụ `/assets/x.js`) vẫn trả lỗi của S3 thay vì trang SPA, đúng ý.
- ⚠️ Route của SPA **không được có dấu chấm ở đoạn cuối** (ví dụ `/report/v1.2` sẽ bị coi là file). UI dùng route dạng `/jars/123`.
- ⚠️ CloudFront Functions có free tier hằng tháng, sau đó tính phí theo triệu lượt gọi (khoảng vài cent ở quy mô demo; xem lại trang giá CloudFront).
- ⚠️ Function phải được publish cùng distribution; CDK làm việc này (`autoPublish`).

## Xem lại khi
UI có route chứa dấu chấm, hoặc CloudFront hỗ trợ custom error response theo từng behavior.
