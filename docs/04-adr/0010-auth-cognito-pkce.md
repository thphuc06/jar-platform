# ADR-0010: Cognito managed login + Authorization Code/PKCE; jars-api là OAuth2 Resource Server

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
Bản cũ: UI gọi `InitiateAuth` với `USER_PASSWORD_AUTH` và **client secret nằm trong bundle JS**; signup là giả; dữ liệu không gắn với user. JD hay hỏi về OAuth2/JWT và Spring Security.

## Quyết định
- **Cognito User Pool** (feature plan Essentials) với **managed login**. App client **public, không có secret**, flow **Authorization Code + PKCE**.
- Frontend dùng **Amplify Auth** (`signInWithRedirect`, tự refresh token).
- jars-api dùng `spring-boot-starter-oauth2-resource-server`, validate thêm `token_use=access` và `client_id` (Cognito access token không có `aud`).
- User nội bộ được tạo lazy ở request đầu tiên (map `sub` → `users.id`); không dùng Lambda trigger.
- Không tự làm đăng ký/đăng nhập (không lưu password trong DB của mình).

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| Tự làm auth (Spring Security + bảng users + bcrypt + JWT tự ký) | Học được nhiều, nhưng tự chịu trách nhiệm về password, reset, MFA. Ở doanh nghiệp thường dùng IdP. Có thể làm như một bài tập phụ |
| Keycloak | Phổ biến ở ngân hàng Việt Nam, nhưng phải tự host (EC2/ECS + DB), tốn tiền và công vận hành; không AWS-native |
| Giữ `USER_PASSWORD_AUTH` + form tự làm | Password đi qua code của mình; phải giữ client secret ở browser (sai) hoặc bỏ secret; không có SSO hay MFA sẵn |
| Pattern BFF (session cookie HttpOnly, token giữ ở server) | Bảo mật hơn trước XSS; phức tạp hơn. Ghi lại để làm sau |

## Hệ quả
- ✅ Không giữ password; có MFA/passkey sẵn; Spring chỉ cần cấu hình resource server.
- ✅ Free tier Lite/Essentials không hết hạn, đủ cho quy mô demo.
- ⚠️ Token nằm trong localStorage (mặc định của Amplify) nên có rủi ro XSS → CSP chặt, token sống ngắn ([security.md](../02-architecture/security.md#1-xác-thực-authentication)).
- ⚠️ Môi trường local cũng dùng user pool dev thật (miễn phí). Test dùng `spring-security-test` (`jwt()`), không gọi Cognito.

## Xem lại khi
Cần đăng nhập bằng mạng xã hội hoặc doanh nghiệp (federation), hoặc chuyển sang BFF.
