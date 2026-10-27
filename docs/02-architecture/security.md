# Security

## 1. Xác thực (Authentication)

| Lớp | Cơ chế |
|---|---|
| Người dùng ↔ web | Cognito managed login, **Authorization Code + PKCE**, app client **không có secret** |
| web ↔ jars-api | `Authorization: Bearer <access_token>` qua cùng origin CloudFront |
| jars-api xác thực | `spring-boot-starter-oauth2-resource-server`, `issuer-uri=https://cognito-idp.ap-southeast-1.amazonaws.com/<userPoolId>` |

Cognito access token **không có claim `aud`** mà có `client_id`, nên phải viết thêm validator:

```java
// Phác thảo — code thật viết ở Phase 1
OAuth2TokenValidator<Jwt> tokenUse = jwt -> "access".equals(jwt.getClaimAsString("token_use"))
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "token_use must be access", null));
OAuth2TokenValidator<Jwt> clientId = jwt -> allowedClientIds.contains(jwt.getClaimAsString("client_id")) ? ... ;
```

**Lưu token ở frontend** (✅ P1.11a, [ADR-0020](../04-adr/0020-spa-auth-oidc-client-ts.md)): `oidc-client-ts` lưu ở **sessionStorage** (mất khi đóng tab, không chung giữa các tab); refresh token làm mới access token và **bị thu hồi khi logout**. Đây là trade-off được chấp nhận ở MVP: nếu có lỗi XSS thì token có thể bị đọc. Cách giảm thiểu: CSP chặt (Phase 3; vì vậy font tự host và không có script bên thứ ba), React tự escape, không dùng `dangerouslySetInnerHTML`, access token sống ngắn (60 phút), `returnTo` sau đăng nhập chỉ nhận đường dẫn cùng origin (chống open redirect). Phương án mạnh hơn là pattern BFF (cookie HttpOnly), ghi lại để làm sau nếu có thời gian.

## 2. Phân quyền (Authorization)

- Mọi bảng nghiệp vụ có `user_id`. **`userId` luôn lấy từ security context**, không bao giờ lấy từ body hay path.
- Repository dùng method dạng `findByIdAndUserId(id, userId)`. Không tìm thấy thì trả **404** (không phải 403), để không lộ là tài nguyên đó có tồn tại.
- **Test IDOR bắt buộc** cho mọi endpoint có `{id}`: user B gọi tài nguyên của user A thì nhận 404.
- Scope `jars-api/write` cho các method làm thay đổi dữ liệu (`@PreAuthorize("hasAuthority('SCOPE_jars-api/write')")`).

## 3. IAM

Nguyên tắc **least privilege**: không dùng `*` ở resource, trừ khi API của AWS không hỗ trợ resource-level permission.

| Role | Ai assume | Quyền |
|---|---|---|
| `jars-dev-api-exec` (execution role) | ECS agent | `ecr:GetAuthorizationToken`, `ecr:BatchGetImage` trên repo `jars/*`; `logs:CreateLogStream`, `logs:PutLogEvents` trên log group `/jars/dev/api`; `secretsmanager:GetSecretValue` trên secret DB |
| `jars-dev-api-task` (task role) | Code của jars-api | `bedrock:InvokeModel`, `bedrock:InvokeModelWithResponseStream` trên ARN inference profile + foundation model đã chọn; `bedrock:ApplyGuardrail` trên guardrail; `sqs:ReceiveMessage`, `sqs:DeleteMessage`, `sqs:ChangeMessageVisibility`, `sqs:GetQueueAttributes` trên các queue của API; `sns:Publish` trên topic ledger; `s3:PutObject` (để ký presigned URL) + `s3:GetObject` trên `jars-dev-statements/uploads/*`; `lambda:InvokeFunction` trên `quant-fn` |
| `jars-dev-worker-task` (Phase 5) | insights-worker | Nhận và xóa message trên `jars-insights-ledger.fifo`; publish lên `jars-insights-events.fifo` |
| `jars-dev-quant-fn` | Lambda | Chỉ có `AWSLambdaBasicExecutionRole` (ghi log) |
| `jars-dev-github-deploy` (✅ P3.2) | GitHub Actions qua OIDC | Trust policy **chính xác** (`StringEquals`, không `StringLike`): `aud = sts.amazonaws.com`, `sub = repo:thphuc06/jar-platform:environment:dev`. Lưu ý: job khai báo `environment: dev` thì `sub` là `...:environment:dev`, **không** phải `...:ref:refs/heads/main` như thiết kế ban đầu; nhánh nào được deploy do protection rule của Environment `dev` quyết định. Phiên tối đa 1 giờ. Quyền: `sts:AssumeRole` lên 4 role bootstrap của CDK (`cdk-hnb659fds-*` của account/region) + push image vào **đúng** repo `jars/jars-api` (+ `ecr:GetAuthorizationToken`, vốn không giới hạn được theo resource). Không có quyền nào khác |
| `jars-dev-scheduler` | EventBridge Scheduler | `sqs:SendMessage` trên `jars-jobs` |

> **Tài khoản người dùng** ([ADR-0018](../04-adr/0018-human-access-iam-user-aws-login.md)): CLI đang đăng nhập bằng **root**. Phase 0 chuyển sang IAM user **`phuc-account` (bật MFA) + `aws login`** để có credential tạm thời. Vô hiệu hóa access key dài hạn của user này. Root giữ MFA và không có access key (đã đúng từ 2026-10-23). **Không dùng IAM Identity Center** vì tài khoản standalone; muốn có permission set thì phải có Organizations, mà Organizations làm mất credit.

## 4. Network

Xem [aws-infrastructure.md §3](aws-infrastructure.md#3-network-vpc).
- ALB **internal**, chỉ nhận traffic từ CloudFront (VPC origin).
- Task nhận inbound **chỉ từ SG của ALB** (dù task có IP public).
- DB và cache nằm ở subnet không có route ra internet.
- Aurora bắt buộc TLS (`rds.force_ssl=1`); Valkey Serverless dùng TLS mặc định.

## 5. Secret và cấu hình

| Loại | Lưu ở đâu | Không bao giờ |
|---|---|---|
| DB credentials | Secrets Manager (CDK `Credentials.fromGeneratedSecret`) → ECS `secrets:`. Rotation thủ công ở dev ([runbook](../05-operations/runbook.md#đổi-secret-db)) | Ghi trong biến môi trường plain text của CDK, file `.env` commit |
| Cấu hình không nhạy cảm (tên queue, model id) | Biến môi trường do CDK truyền vào | – |
| Cấu hình local | `apps/jars-api/.env.local` (**đã gitignore**) hoặc profile AWS SSO | Commit file `.env` |
| GitHub → AWS | OIDC, **không có access key** | `AWS_ACCESS_KEY_ID` trong GitHub secrets |

**Kiểm soát rò rỉ:**
- `gitleaks` chạy ở pre-commit (tùy chọn) và **bắt buộc trong CI**.
- Bật **GitHub secret scanning + push protection** cho repo.
- Bài học từ repo cũ: file env mã hóa **UTF-16** lọt qua grep thông thường. Gitleaks quét được nhiều encoding hơn; ngoài ra `.gitignore` chặn luôn `*env*.json`.

## 6. Bảo mật ứng dụng

| Rủi ro | Biện pháp |
|---|---|
| Injection (SQL) | JPA/Spring Data với tham số bind; không nối chuỗi SQL |
| Mass assignment | DTO `record` riêng cho request; không bind thẳng vào entity |
| Upload file độc | Presigned URL giới hạn `content-length` ≤ 5 MB, content-type `text/csv`; key do server sinh; parser CSV giới hạn số dòng và kích thước dòng |
| SSRF | Không gọi URL do user cung cấp. Boot 4.1 có `InetAddressFilter` cho HTTP client nếu sau này cần |
| Lạm dụng LLM (chi phí, prompt injection) | Rate limit theo user; `max-tokens`; tool chỉ đọc + scope theo user; Guardrails |
| Lộ thông tin qua lỗi | ✅ (P1.3) `ProblemDetail` không chứa stack trace, tên exception hay SQL (có test kiểm tra: lỗi 500 với message chứa "password" không lọt ra response); log chi tiết phía server có `traceId`. ⚠️ Message của exception vẫn được ghi vào **log**, nên không bao giờ đưa secret hoặc PII vào message |
| Dependency có CVE | Dependabot + `mvn org.owasp:dependency-check` (hoặc GitHub dependency review); ECR scan on push |
| Header bảo mật | CloudFront response headers policy (HSTS, CSP, nosniff, frame-ancestors none) |
| Lộ dữ liệu cá nhân trong log | Không log body request có số tiền/ghi chú ở mức INFO; mask email trong log |

## 7. Threat model (STRIDE rút gọn)

| Mối đe dọa | Ví dụ | Giảm thiểu |
|---|---|---|
| **S**poofing | Dùng JWT giả hoặc đã hết hạn | Xác thực chữ ký qua JWKS, kiểm tra `iss`, `exp`, `token_use`, `client_id` |
| **T**ampering | Sửa `jarId` của người khác trong body | Kiểm tra quyền sở hữu ở service; trả 404 |
| **R**epudiation | "Tôi không tạo giao dịch này" | `audit_log` + `traceId` + CloudTrail cho thao tác hạ tầng |
| **I**nformation disclosure | Gọi thẳng ALB, bỏ qua CloudFront | ALB internal, SG chỉ cho phép CloudFront prefix list |
| **D**enial of service | Spam endpoint chat | Rate limit; (Phase 6) AWS WAF rate-based rule |
| **E**levation of privilege | Prompt injection khiến AI gọi tool với `userId` khác | Tool bỏ qua mọi `userId` do LLM truyền vào, chỉ dùng security context |

## 8. Sự cố của repo cũ (hackathon) và việc cần làm

Phát hiện ngày 2026-10-23 khi đọc `swinhackathon/ius_backend_agent_mcp` (repo **public**). **Không ghi giá trị secret ở đây.**

| # | Phát hiện | Mức | Việc phải làm | Trạng thái |
|---|---|---|---|---|
| 1 | `infrastructure/lambda/cusum_worker/current_env.json` (UTF-16) chứa connection string Supabase Postgres **có mật khẩu** | 🔴 | Đổi mật khẩu DB trên Supabase; kiểm tra log kết nối; xóa file + purge lịch sử (`git filter-repo`) hoặc archive và chuyển repo sang private | ⏭️ Bỏ qua |
| 2 | `ops/aws/deploy.env` bị track dù đã nằm trong `.gitignore` | 🟠 | `git rm --cached` | ⏭️ Bỏ qua |
| 3 | Endpoint stock agent `/ask` không có auth, gọi Claude Sonnet; URL nằm công khai trong `.env.example` | 🟠 | Xác nhận service App Runner đã tắt (tài khoản `626212071763` hiện **không có** App Runner/ECS, có thể nằm ở tài khoản khác) | ⏭️ Bỏ qua |
| 4 | UI dùng Supabase anon key với `DEFAULT_USER_ID` hardcode | 🟠 | Kiểm tra RLS; tắt project Supabase sau khi chuyển xong | ⏭️ Bỏ qua |
| 5 | `VITE_COGNITO_CLIENT_SECRET` sẽ bị đóng gói vào JS | 🟠 | Bản mới dùng public client + PKCE | ✅ (thiết kế mới) |
| 6 | Commit file zip Lambda 4 MB + 27 MB và thư mục `package/` | 🟡 | Không lặp lại: build trong CI | ✅ (thiết kế mới) |

> **Quyết định 2026-10-23:** mục 1–4 thuộc repo của org cũ (dữ liệu và hạ tầng của team hackathon), nằm **ngoài phạm vi** project này nên không xử lý. `jar-platform` không dùng Supabase, App Runner hay bất kỳ credential nào của repo cũ. Bài học được áp dụng cho repo mới: gitleaks trong CI, push protection, `.gitignore` chặn `*env*.json`, không commit file build.
