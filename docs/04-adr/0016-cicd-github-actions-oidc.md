# ADR-0016: CI/CD bằng GitHub Actions, xác thực AWS qua OIDC

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
Code nằm trên GitHub. Bản cũ dùng CodeBuild + script PowerShell chạy tay. Cần CI cho mỗi PR (test, build, scan) và CD lên môi trường dev.

## Quyết định
- **CI** (`.github/workflows/ci.yml`, chạy mỗi PR, có path filter):
  - jars-api: `./mvnw verify` (unit test + Testcontainers + Modulith verify).
  - web: lint + test + build.
  - quant-fn: `pytest`.
  - infra: `cdk synth` + cdk-nag.
  - Toàn repo: gitleaks.
- **CD** (`deploy-dev.yml`, chạy khi merge `main` hoặc chạy tay):
  1. Assume role `jars-github-deploy` qua **OIDC**.
  2. Build image arm64 và push ECR (tag = git SHA).
  3. Chạy `cdk deploy JarsApp JarsQuant -c imageTag=<sha>`.
  4. Web được deploy qua `BucketDeployment` + invalidation CloudFront.
- Dùng GitHub Environment `dev` có yêu cầu approve thủ công (tùy chọn) để tránh deploy ngoài ý muốn khi đang "ngủ" để tiết kiệm.

> **Cập nhật 2026-11-12 (Phase 2 WP1), khi cài đặt:** CD chỉ chạy tay (`workflow_dispatch`, input `deploy`/`destroy`), không chạy khi merge `main`, vì môi trường mặc định là TEARDOWN; nhờ vậy không cần biến `DEPLOY_ENABLED`. Không dùng GitHub Environment: environment đổi claim `sub` thành `repo:...:environment:dev`, sẽ phải nới trust policy. Workflow chỉ deploy `JarsApp` với `--exclusively`; các stack nền vẫn deploy từ máy dev. Quyền push ECR được thêm vào role, giới hạn đúng repo `jars/jars-api` ([security.md §3](../02-architecture/security.md#3-iam)). `JarsQuant` thêm vào bước deploy khi có ở WP5.

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| AWS CodePipeline + CodeBuild | AWS-native hơn; nhưng code ở GitHub, Actions miễn phí cho repo public và phổ biến hơn trong JD. Ghi lại như kiến thức tương đương |
| Access key lưu trong GitHub secrets | Key sống lâu, dễ lộ. OIDC phát credential tạm thời cho từng job |
| Jenkins | Phổ biến ở ngân hàng Việt Nam, nhưng phải tự host |

## Hệ quả
- ✅ Không có long-lived credential; mọi lần deploy đều truy vết được theo commit.
- ✅ Miễn phí với repo public.
- ⚠️ Trust policy phải giới hạn đúng `repo:thphuc06/jar-platform:ref:refs/heads/main` (hoặc environment `dev`), không dùng wildcard `*`.
- ⚠️ Testcontainers trên runner `ubuntu-latest` có sẵn Docker. LocalStack cần `LOCALSTACK_AUTH_TOKEN` trong GitHub secrets (gói Hobby/Student miễn phí, chỉ dùng phi thương mại).

## Xem lại khi
Chuyển sang GitLab, hoặc cần pipeline nhiều môi trường có approval phức tạp.
