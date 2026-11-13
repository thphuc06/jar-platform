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

**Lưu token ở frontend:** dùng thư viện Amplify Auth (cấu hình mặc định lưu ở localStorage). Đây là trade-off được chấp nhận ở MVP: nếu có lỗi XSS thì token có thể bị đọc. Cách giảm thiểu: CSP chặt, React tự escape, không dùng `dangerouslySetInnerHTML`, access token sống ngắn (60 phút). Phương án mạnh hơn là pattern BFF (cookie HttpOnly), ghi lại để làm sau nếu có thời gian.

## 2. Phân quyền (Authorization)

- Mọi bảng nghiệp vụ có `user_id`. **`userId` luôn lấy từ security context**, không bao giờ lấy từ body hay path.
- Repository dùng method dạng `findByIdAndUserId(id, userId)`. Không tìm thấy thì trả **404** (không phải 403), để không lộ là tài nguyên đó có tồn tại.
- **Test IDOR bắt buộc** cho mọi endpoint có `{id}`: user B gọi tài nguyên của user A thì nhận 404.
- **Không phân quyền theo scope ở MVP.** App chỉ có một vai trò (người dùng thường) và mọi endpoint (trừ health) chỉ cần token hợp lệ cộng kiểm tra quyền sở hữu dữ liệu. Resource server `jars-api` với scope `read`/`write` vẫn được tạo ở Cognito để mở rộng sau, nhưng code không kiểm tra scope. Lý do: token lấy bằng luồng admin (smoke test) hoặc luồng PKCE mặc định không tự mang scope tùy chỉnh, và scope không thêm bảo vệ thật khi mọi user có cùng quyền.

## 3. IAM

Nguyên tắc **least privilege**: không dùng `*` ở resource, trừ khi API của AWS không hỗ trợ resource-level permission.

| Role | Ai assume | Quyền |
|---|---|---|
| `jars-dev-api-exec` (execution role) | ECS agent | `ecr:GetAuthorizationToken` (`Resource: *` vì IAM không hỗ trợ resource-level cho action này); `ecr:BatchCheckLayerAvailability`, `ecr:BatchGetImage`, `ecr:GetDownloadUrlForLayer` trên repo `jars/jars-api`; `logs:CreateLogStream`, `logs:PutLogEvents` trên log group `/jars/dev/api`; `secretsmanager:GetSecretValue`, `secretsmanager:DescribeSecret` trên secret DB |
| `jars-dev-api-task` (task role) | Code của jars-api | `bedrock:InvokeModel`, `bedrock:InvokeModelWithResponseStream` trên ARN inference profile + foundation model đã chọn; `bedrock:ApplyGuardrail` trên guardrail; `sqs:ReceiveMessage`, `sqs:DeleteMessage`, `sqs:ChangeMessageVisibility`, `sqs:GetQueueAttributes`, `sqs:GetQueueUrl` (app cấu hình theo tên queue nên Spring Cloud AWS phải tra URL) trên các queue của API; `sns:Publish` trên topic ledger; `s3:PutObject` (để ký presigned URL) + `s3:GetObject` trên `jars-dev-statements-<account>/uploads/*` (đã cài ở Phase 2 WP3, cùng quyền consume `jars-imports`) |
| `jars-dev-worker-task` (Phase 5) | insights-worker | Nhận và xóa message trên `jars-insights-ledger.fifo`; publish lên `jars-insights-events.fifo` |
| `jars-dev-github-deploy` | GitHub Actions qua OIDC | Trust policy chỉ chấp nhận `StringEquals` với `token.actions.githubusercontent.com:sub = repo:thphuc06@185325027/jar-platform@1431052736:ref:refs/heads/main` và `aud = sts.amazonaws.com` (không wildcard, không environment). Repo dùng định dạng subject bất biến của GitHub (có id số của owner và repo), nên một repo tạo lại cùng tên không assume được role. Quyền: (1) `sts:AssumeRole` trên 4 role bootstrap của CDK (`cdk-hnb659fds-deploy-role`, `-file-publishing-role`, `-image-publishing-role`, `-lookup-role`) của đúng account và region này; (2) policy riêng `GitHubDeployRuntimePolicy` cho việc làm ngoài CloudFormation: push image vào **đúng repo** `jars/jars-api` (+ `ecr:GetAuthorizationToken`), `cloudformation:DescribeStacks` trên stack `Jars*`, `elasticloadbalancing:DescribeLoadBalancers` (smoke test kiểm ALB internal); (3) policy trong `JarsAuth`: `AdminCreateUser/SetUserPassword/InitiateAuth/DeleteUser` trên **đúng user pool** cho user tạm của smoke test. Workflow chỉ deploy `JarsApp` (`--exclusively`), không đụng stack chứa chính role này |
| `jars-dev-scheduler` | EventBridge Scheduler (kèm điều kiện `aws:SourceAccount`) | `sqs:SendMessage` trên `jars-jobs` và trên `jars-jobs-scheduler-dlq` (DLQ cho lần gửi lỗi của schedule) |

> **Tài khoản người dùng** ([ADR-0018](../04-adr/0018-human-access-iam-user-aws-login.md)): thiết kế đích là IAM user **`phuc-account` (bật MFA) + `aws login`** để có credential tạm thời. **Trạng thái thực tế (2026-11-12): ADR-0018 đang hoãn, CLI vẫn đăng nhập bằng root** (root có MFA, không có access key; access key dài hạn của `phuc-account` đã bị vô hiệu hóa). Rủi ro này được chấp nhận trong giai đoạn dev và phải được xử lý trước khi bật CI/CD deploy. **Không dùng IAM Identity Center** vì tài khoản standalone; muốn có permission set thì phải có Organizations, mà Organizations làm mất credit.

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
| Lộ thông tin qua lỗi | `ProblemDetail` không chứa stack trace; log chi tiết có `traceId` |
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
