# ADR-0020: SPA đăng nhập bằng `oidc-client-ts` (thay Amplify Auth), token ở sessionStorage

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-27
- **Supersedes:** ADR-0010, **chỉ phần frontend** ("Frontend dùng Amplify Auth" và hệ quả "token nằm trong localStorage"). Phần Cognito managed login + PKCE và phía resource server vẫn giữ nguyên.

## Bối cảnh

ADR-0010 chọn Amplify Auth cho SPA, khi đó chưa thử với user pool thật. Roadmap P1.11 để mở câu hỏi "Amplify Auth hay `oidc-client-ts`". Ở P1.11a mình đã thử thật trên user pool dev (chỉ gửi request GET đọc, không đổi cấu hình). Kết quả:

| Kiểm tra trên Cognito thật | Kết quả |
|---|---|
| OIDC discovery | Có `authorization_endpoint`, `token_endpoint`, `revocation_endpoint`, `userinfo_endpoint`, `end_session_endpoint` (`/logout`), nên một client OIDC chuẩn tự cấu hình được từ issuer |
| `authorize` có PKCE S256 và đúng `redirect_uri` | 302 tới trang login; sai cổng → `redirect_mismatch` |
| Scope `offline_access` | `invalid_scope`; code grant vẫn trả refresh token mà không cần scope này |
| Logout theo chuẩn OIDC (`post_logout_redirect_uri`) | **Bị bỏ qua** (hiện trang login). Cognito cần tham số riêng: `client_id` + `logout_uri` (đã đăng ký); có thêm `id_token_hint` thì vẫn chạy đúng |
| Bundle (esbuild, minify, chỉ gồm các hàm cần dùng) | Amplify Auth 6.22: **30,7 kB** gzip; `oidc-client-ts` 3.5 + `react-oidc-context` 3.3: **19,3 kB** gzip |

## Quyết định

1. SPA dùng **`oidc-client-ts` + `react-oidc-context`**: Authorization Code + PKCE với managed login, endpoint đọc từ discovery, không có client secret.
2. Token lưu ở **sessionStorage** (`WebStorageStateStore`): mất khi đóng tab, không chia sẻ giữa các tab. Access token được làm mới bằng refresh token (`automaticSilentRenew`). Khi logout, refresh token **bị thu hồi** ở Cognito (`revokeTokensOnSignout`), rồi app chuyển tới `/logout` với `client_id` + `logout_uri`.
3. Một `UserManager` dùng chung cho toàn app: provider React và middleware của API client đọc cùng một user. Middleware chỉ gửi **access token** (API từ chối ID token).
4. Sau khi đăng nhập, `returnTo` chỉ nhận đường dẫn trên cùng origin (`/...`, không `//`, không `\`), để chống open redirect.

## Các phương án đã cân nhắc

| Phương án | Ưu | Nhược | Vì sao không chọn |
|---|---|---|---|
| Amplify Auth (`signInWithRedirect`) như ADR-0010 | Tự xử lý các điểm riêng của Cognito (logout, refresh); có sẵn Amplify UI | Gắn chặt với Cognito và hệ Amplify; mặc định lưu localStorage (sống lâu hơn, chung mọi tab); bundle lớn hơn khoảng 11 kB gzip | Kích thước không phải lý do chính. Lý do chính là kiến thức OIDC chuẩn dùng được với mọi IdP (Keycloak, Entra ID, Auth0), và các điểm riêng của Cognito chỉ có 2 chỗ, đều đã được test |
| Tự viết PKCE bằng `fetch` | Không thêm thư viện | Tự lo `state`/`nonce`, xác thực ID token, refresh, xử lý nhiều tab | Dễ sai ở chỗ nhạy cảm nhất của app |
| BFF (cookie HttpOnly, token giữ ở server) | Token không bao giờ nằm trong JS, nên XSS không đọc được token. IETF *OAuth 2.0 for Browser-Based Applications* khuyên dùng | Cần thêm một tầng server (Spring Cloud Gateway hoặc session ở jars-api) và chống CSRF | Ghi lại để làm sau (giống ADR-0010); `UserManager` đặt sau middleware nên đổi được mà không sửa từng màn hình |
| localStorage thay sessionStorage | Mở tab mới không phải đăng nhập lại | Token sống qua lần đóng trình duyệt và chung mọi tab, nên khi bị XSS thì cửa sổ bị lộ dài hơn | Với app tài chính, phải đăng nhập lại ở tab mới là chấp nhận được (managed login vẫn còn session cookie nên thường chỉ cần một lần redirect) |

## Hệ quả

- ✅ OIDC chuẩn, có test cho đúng 2 điểm riêng của Cognito (`logout_uri`, không xin `offline_access`). Đổi IdP chỉ cần sửa `oidc.ts`.
- ✅ Logout thật sự: refresh token bị thu hồi, session của managed login bị xóa.
- ⚠️ Token vẫn đọc được bằng JavaScript, nên XSS vẫn là rủi ro chính. Cách giảm thiểu:
  - Không dùng `dangerouslySetInnerHTML`.
  - Font tự host, không tải script từ bên thứ ba, để CSP chặt được ở Phase 3 (CloudFront response headers).
  - Access token chỉ sống 60 phút.
- ⚠️ Mỗi tab có phiên riêng. Mở tab mới thì đi lại một vòng redirect, thường không phải gõ mật khẩu.

## Xem lại khi

- Có yêu cầu bảo mật cao hơn (ví dụ thanh toán thật): chuyển sang BFF.
- Cần federation hoặc Amplify UI: cân nhắc lại Amplify Auth.
