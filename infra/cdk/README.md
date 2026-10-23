# infra/cdk

AWS CDK v2 viết bằng **Java** ([ADR-0012](../../docs/04-adr/0012-iac-aws-cdk-java.md)). Toàn bộ hạ tầng của Jars Platform nằm ở đây.

> Trạng thái (P1.1, 2026-10-23): stack **`JarsAuth`** (`jars-dev-auth`) đã deploy ở ap-southeast-1; đã chạy `cdk bootstrap` cho ap-southeast-1.

## Version

| Thành phần | Version | Ghi chú |
|---|---|---|
| `aws-cdk-lib` | 2.273.0 | `pom.xml` |
| `constructs` | 10.8.1 | |
| `cdk-nag` | 3.0.2 | **API v3** chạy qua `Validations.of(app).addPlugins(...)` của CDK. Dùng `Validations.of(construct).acknowledge(...)` để chấp nhận vi phạm, ID dạng `AwsSolutions::AwsSolutions-XXX` (v2 dùng `NagSuppressions`, không còn nữa) |
| CDK CLI | 2.1145.0 | Ghim trong `package.json`; chạy bằng `npx cdk ...` sau khi `npm ci` |
| JUnit / AssertJ | 6.0.3 / 3.27.7 | Cùng version mà Boot 4.1.2 dùng cho `jars-api` |

## Lệnh

```bash
cd infra/cdk
npm ci                                   # cài CDK CLI đã ghim (lần đầu)
./mvnw verify                            # test template + cdk-nag + Spotless (không cần AWS)
npx cdk synth JarsAuth                   # sinh CloudFormation; cdk-nag fail nếu có vi phạm chưa acknowledge
npx cdk diff  JarsAuth                   # xem thay đổi trước khi deploy
npx cdk deploy JarsAuth --outputs-file cdk-outputs.json
```

- `cdk.json` gọi `node scripts/synth-app.mjs`. Launcher này chạy `mvnw.cmd` trên Windows và `./mvnw` trên Linux/macOS, bằng **đường dẫn tuyệt đối**. Lý do: Windows có thể bật `NoDefaultCurrentDirectoryInExePath=1`, khi đó `cmd` không tìm thấy `mvnw` trong thư mục hiện tại.
- Account **không hardcode**: CDK lấy từ credential đang dùng (`CDK_DEFAULT_ACCOUNT`); region và các tham số khác nằm trong `cdk.json` → `context` (`jars:*`), có thể ghi đè bằng `-c key=value`.
- `cdk.json` có **87 feature flag khuyến nghị**, lấy từ `cdk init` của CLI 2.1145.0.

## Stacks

| Stack | Tên CloudFormation | Trạng thái | Nội dung |
|---|---|---|---|
| `JarsAuth` | `jars-dev-auth` | ✅ deploy 23/10 | User pool `jars-dev-users` (Essentials, MFA TOTP tùy chọn, RETAIN), domain managed login `jars-dev-thphuc06`, resource server `jars-api` (`read`, `write`), app client `jars-dev-web-local` (public, PKCE, chỉ code flow, callback `http://localhost:5173/auth/callback`) + managed login branding |
| Foundation, Network, Data, Messaging, App, Quant, Observability | | ⬜ | Xem [aws-infrastructure.md §1](../../docs/02-architecture/aws-infrastructure.md#1-cdk-stacks) |

Output của `JarsAuth` (xem bằng `aws cloudformation describe-stacks --stack-name jars-dev-auth --region ap-southeast-1`): `UserPoolId`, `LocalWebClientId`, `IssuerUri`, `ManagedLoginBaseUrl`. Những giá trị này **không phải secret** (SPA nào cũng phải biết).

## Test bắt buộc

| Test | Bảo vệ điều gì |
|---|---|
| `AuthStackTest` (7 test) | User pool Essentials + RETAIN; managed login v2; scope read/write; client public, **chỉ code flow**, `ExplicitAuthFlows` khai báo tường minh (bỏ trống thì Cognito mặc định bật cả `CUSTOM_AUTH`); tag chung; cdk-nag pass; **cdk-nag fail với resource sai** (test của test) |
| Sẽ thêm (Phase 3) | Không có `AWS::EC2::NatGateway`; Fargate ARM64; không SG nào mở `0.0.0.0/0`; log retention ≤ 14 ngày; Aurora `MinCapacity = 0` |

## Lưu ý khi deploy bằng root

`cdk deploy` báo *"current credentials could not be used to assume ...deploy-role... Proceeding anyway"*. **Root user không thể assume IAM role**, nên CDK bỏ qua các role bootstrap và dùng thẳng quyền root. Deploy vẫn chạy được, nhưng đây là một lý do nữa để thực hiện [ADR-0018](../../docs/04-adr/0018-human-access-iam-user-aws-login.md) trước khi bật CI/CD deploy (P3.6). CI/CD sẽ assume role qua OIDC.
