# ADR-0007: CloudFront là cửa vào duy nhất; S3 qua OAC; API qua VPC origin tới ALB internal

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
Cần host SPA (static) và public API. Nếu UI và API ở 2 domain khác nhau thì phải cấu hình **CORS**; nếu ALB public thì ai cũng có thể bỏ qua CloudFront để gọi thẳng. Bản cũ gọi App Runner URL công khai, không có auth.

## Quyết định
- Một CloudFront distribution:
  - `/*` → S3 bucket private qua **Origin Access Control**.
  - `/api/*` → **CloudFront VPC origin** → **ALB internal** (trong private subnet).
- UI gọi API theo **đường dẫn tương đối** (`/api/v1/...`). Cùng origin nên không cần CORS ở môi trường deploy. Ở local, Vite dev server proxy `/api` sang `localhost:8080`.
- `/api/*`: `CachingDisabled` + `AllViewerExceptHostHeader`; SSE gửi heartbeat 15 s.

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| ALB public + CORS | Lộ ALB; tốn IP public cho ALB; phải lo CORS và chặn traffic không qua CloudFront (custom header bí mật) |
| API Gateway **REST API** + private integration (VPC Link) | **Khả thi:** từ 11/2025 REST API hỗ trợ response streaming (timeout tới 15 phút), nên SSE chạy được. Không chọn vì thêm một lớp nữa (API GW + VPC Link + NLB/ALB), tính phí theo request, và CloudFront + ALB là combo quen thuộc hơn trong JD. Có thể làm bài tập so sánh sau |
| API Gateway **HTTP API** + VPC Link | Rẻ, nhưng chưa xác minh được HTTP API có hỗ trợ streaming hay không (thông báo 11/2025 chỉ nói tới REST API) → không dùng cho SSE |
| Amplify Hosting cho UI | Tiện, nhưng vẫn phải giải bài toán API riêng; tự cấu hình CloudFront + S3 giúp hiểu sâu hơn |
| Domain riêng (Route 53 + ACM) | Tốt cho demo; tốn khoảng $3–15/năm. **Tùy chọn**, có thể thêm sau mà không đổi kiến trúc |

## Hệ quả
- ✅ Không cần CORS; ALB không có IP public; TLS kết thúc ở CloudFront.
- ✅ Trả lời được câu phỏng vấn kinh điển "Làm sao chặn truy cập trực tiếp vào origin?".
- ⚠️ CloudFront → ALB đi HTTP bên trong mạng AWS (qua VPC origin). Muốn end-to-end TLS thì cần certificate trên ALB, tức là cần domain riêng.
- ⚠️ Request đầu tiên sau khi Aurora pause có thể lâu (khoảng 15 s); origin response timeout mặc định 30 s là đủ.
- ⚠️ Domain CloudFront đổi nếu xóa rồi tạo lại `JarsApp`. Cognito callback được CDK cập nhật tự động, nhưng bookmark cũ sẽ hỏng. Nếu teardown thường xuyên thì dùng domain riêng.

## Xem lại khi
Cần WebSocket hai chiều, hoặc cần multi-region.
