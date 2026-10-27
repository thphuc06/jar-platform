# Deployment

![CI/CD](../diagrams/aws-cicd.png)

## 1. Chuẩn bị tài khoản (làm 1 lần)

> Làm ở **Phase 0**. Các bước đều **miễn phí**.

| # | Việc | Vì sao | Cách làm |
|---|---|---|---|
| 1 | Bật **MFA cho root**, ngừng dùng root cho CLI | CLI hiện đăng nhập bằng `arn:aws:iam::626212071763:root`. Root lộ thì mất toàn bộ tài khoản | Console → Security credentials → MFA; xóa access key của root nếu có |
| 2 | **Bật MFA cho IAM user `phuc-account`**, sau đó **vô hiệu hóa** access key dài hạn của user này ([ADR-0018](../04-adr/0018-human-access-iam-user-aws-login.md)). Identity Center **không dùng được** vì cần Organizations, mà Organizations làm mất credit | Không còn credential dài hạn; tài khoản admin có MFA | Console → IAM → Users → `phuc-account` → Security credentials → Assign MFA device; Access keys → Deactivate |
| 3 | `aws login --profile jars-dev` (đăng nhập bằng `phuc-account`, region `ap-southeast-1`) | CLI/SDK dùng credential tạm thời (tối đa 12 giờ) | [local-development.md §5](local-development.md#5-aws-credentials-cho-local-không-dùng-root) |
| 4 | Bật cost allocation tag `Project` | Lọc chi phí theo project | Billing → Cost allocation tags (tag xuất hiện sau khi resource đầu tiên được gắn tag, mất tới 24 giờ) |
| 5 | Kiểm tra **Bedrock model access** ở ap-southeast-1 | Gọi model lần đầu cần được phép | Console Bedrock → Model access (hoặc thử gọi `converse` bằng CLI) |
| 6 | ✅ (23/10) `cd infra/cdk && npx cdk bootstrap aws://<account>/ap-southeast-1` | CDK cần bucket và role bootstrap ở mỗi region | Stack `CDKToolkit` ở ap-southeast-1; execution policy mặc định `AdministratorAccess` (giai đoạn dev) |
| 7 | Tạo repo GitHub `thphuc06/jar-platform` (repo cá nhân), bật secret scanning + push protection | Chặn commit secret | Settings → Code security |
| 8 | Bật **S3 Block Public Access ở cấp account** | Không bucket nào lỡ bị public | S3 console → Block Public Access settings for this account |
| 9 | Bật **EBS encryption by default** (ap-southeast-1) | Volume mới tự được mã hóa (instance `sidewalk` hiện có cũng có EBS) | EC2 console → Settings → EBS encryption |
| 10 | Tạo **IAM Access Analyzer** loại external access (cấp account) | Phát hiện resource bị chia sẻ ra ngoài; miễn phí | IAM → Access Analyzer → Create analyzer |
| 11 | Bật **Cost Anomaly Detection** (monitor theo service + email hằng ngày) | Cảnh báo chi phí tăng bất thường; không tính phí | Billing → Cost Anomaly Detection |

> **Bước 8–11 là "account baseline"**, không thuộc CDK stack nào của project. Chúng bảo vệ **cả account dùng chung** (có cả `sidewalk`, `FairTable`). Nếu đặt vào `JarsFoundation` thì `cdk destroy` sẽ gỡ luôn bảo vệ của các project khác, và tài nguyên tạo bằng tay sẽ bị trùng. Các bước này làm một lần bằng console hoặc CLI, rồi ghi lại trạng thái ở đây.

Tham chiếu đầy đủ: [well-architected-review.md](../02-architecture/well-architected-review.md).

> **Lưu ý:** **Không** tham gia AWS Organizations hay Control Tower với tài khoản này. Theo FAQ của AWS Free Tier, làm vậy khiến credit **hết hạn ngay lập tức**.

## 2. Thứ tự deploy

| Lần | Lệnh | Khi nào |
|---|---|---|
| Phase 1 ✅ (23/10) | `npx cdk deploy JarsAuth` → stack `jars-dev-auth` | Cần Cognito để có JWT thật khi chạy local |
| Phase 3, P3.2 ✅ (27/10) | `cdk deploy JarsFoundation JarsNetwork` → `jars-dev-foundation`, `jars-dev-network` | ECR, OIDC deploy role, SNS alarm, Budget; VPC + security group. Không tính phí theo giờ |
| Phase 3, P3.3 | `cdk deploy JarsData` → `jars-dev-data` | Aurora (tính phí theo ACU khi thức; pause sau 5 phút không có kết nối). Xóa stack: tắt deletion protection trước, stack để lại snapshot |
| Phase 3 | Push image đầu tiên lên ECR (bằng CI hoặc tay), rồi `cdk deploy JarsApp -c imageTag=<sha>` | Lên app |
| Phase 4 | `cdk deploy JarsQuant JarsApp` | Thêm quant-fn, Valkey, quyền Bedrock |
| Phase 5 | `cdk deploy JarsMessaging JarsApp` (+ service worker) | SNS/SQS FIFO + insights-worker |
| Phase 6 | `cdk deploy JarsObservability` | Dashboard, alarm |

Tất cả lệnh chạy trong `infra/cdk` với profile đã `aws login` (máy hiện tại: `default`; phiên hết hạn sau tối đa 12 giờ. Không mở được trình duyệt trên máy chạy CLI thì dùng `aws login --remote`: CLI in một link, đăng nhập ở thiết bị khác rồi dán **mã một lần** vào CLI. Mã gắn với PKCE của đúng phiên CLI đó; không bao giờ gửi access key hay mật khẩu qua chat. Trình duyệt đang có phiên console của account khác có thể gây lỗi 400: dùng cửa sổ ẩn danh). Trước mỗi lần deploy nên chạy `cdk diff` để xem thay đổi.

**Trước lần deploy `JarsFoundation` đầu tiên (làm một lần, chỉ đọc):**

1. Account đã có GitHub OIDC provider chưa: `aws iam list-open-id-connect-providers`. Mỗi account chỉ có **một** provider cho `token.actions.githubusercontent.com`; nếu project khác đã tạo thì deploy với `-c jars:githubOidcProviderArn=<arn>` để import thay vì tạo (tạo trùng sẽ fail `EntityAlreadyExists`).
2. Tag `Project` đã bật làm cost allocation tag chưa: `aws ce list-cost-allocation-tags --tag-keys Project`. Chưa bật thì Budget lọc theo tag luôn thấy $0 (bật ở Billing → Cost allocation tags; có hiệu lực sau tối đa 24 giờ).
3. (Không cần kiểm tra số Budget: Budget **không có action** thì miễn phí; chỉ Budget có action mới giới hạn 2 cái miễn phí, từ cái thứ 3 tính $0.10/ngày. Nguồn: [AWS Budgets pricing](https://aws.amazon.com/aws-cost-management/aws-budgets/pricing/), đọc ngày 2026-10-27. `jars-dev-monthly` không có action.)

**Sau khi deploy:** đăng ký email nhận cảnh báo **bằng tay** (không nằm trong CDK để địa chỉ email không vào repo public): `aws sns subscribe --topic-arn <AlarmTopicArn> --protocol email --notification-endpoint <email>`, rồi bấm xác nhận trong email. Subscription tạo ngoài stack nên deploy lại không xóa nó.

## 3. Cấu hình theo môi trường (`infra/cdk/cdk.json` → `context`)

| Key | Mặc định | Ý nghĩa |
|---|---|---|
| `env` | `dev` | Tiền tố tên resource |
| `account` / `region` | `626212071763` / `ap-southeast-1` | |
| `imageTag` | (bắt buộc với `JarsApp`) | Git SHA của image jars-api |
| `apiDesiredCount` | `1` | `0` = chế độ SLEEP |
| `dbMode` | `aurora` | `rds` = chuyển sang RDS t4g.micro dự phòng (ADR-0008) |
| `enableCache` | `false` (Phase 3) / `true` (Phase 4+) | Tạo Valkey Serverless |
| `customDomain` | (trống) | Nếu có domain riêng: tạo ACM ở us-east-1 + bản ghi Route 53 |
| `alertEmail` | (email của bạn) | Nhận budget alarm và CloudWatch alarm |

## 4. Pipeline

### `ci.yml` (mỗi PR và mỗi lần push lên `main`)

**Đang có (P0.8):** [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)

| Job | Làm gì | Ghi chú |
|---|---|---|
| `jars-api` | `setup-java` Temurin 25 (có cache Maven) → `./mvnw --batch-mode --no-transfer-progress verify` | `verify` gồm unit test + Testcontainers (Docker của runner) + Modulith `verify()` + Spotless check. Fail thì upload `surefire-reports`; pass thì upload `modulith-docs` |
| `jars-api-image` (✅ P3.1) | Runner **`ubuntu-24.04-arm`** (arm64 thật, không giả lập) → `docker build --target plain` → kiểm tra kiến trúc arm64, user `10001:10001`, `java -version` | Dockerfile không bị hỏng dần giữa các lần deploy. Target `aot` cần Postgres nên để cho pipeline deploy (P3.6) |
| `gitleaks` | Quét **toàn bộ lịch sử** git (`fetch-depth: 0`) | Action pin theo commit SHA (v3.0.0); tắt comment vào PR nên chỉ cần quyền `contents: read`; repo cá nhân nên không cần license |

Toàn workflow: `permissions: contents: read`; dùng `concurrency` để push mới hủy lần chạy cũ; mỗi job có `timeout-minutes`. Cấu hình [`.github/dependabot.yml`](../../.github/dependabot.yml) mở PR cập nhật Maven và Actions hằng tuần (gom nhóm Spring và Spring Cloud AWS để các version khớp nhau luôn đi cùng).

**Thêm sau** (khi có app tương ứng, khi đó dùng path filter để chỉ build phần bị thay đổi):

```
paths: apps/web/**           → npm ci → lint → test → build                 (P1.11)
paths: functions/quant-fn/** → pip install → pytest                           (P4.2)
paths: infra/cdk/**          → ./mvnw test (CDK assertions) → cdk synth (cdk-nag) (P1.1)
```

Kiểm tra workflow ở local trước khi push: `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12`. Quét secret ở local: `docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo --redact`.

### `deploy-dev.yml` (merge `main` hoặc `workflow_dispatch`)

```
permissions: id-token: write, contents: read
1. aws-actions/configure-aws-credentials (role-to-assume: jars-github-deploy, region ap-southeast-1)
2. (runner ubuntu-24.04-arm, service container postgres:17 cho training run của AOT cache)
   docker build --target aot --secret id=training_db_password,env=PGPASSWORD \
     --build-arg TRAINING_DB_URL=jdbc:postgresql://host.docker.internal:5432/jars --add-host host.docker.internal=host-gateway \
     -t <ecr>/jars/jars-api:${GITHUB_SHA} . && docker push ...
3. cd infra/cdk && npx aws-cdk@2 deploy JarsApp JarsQuant -c imageTag=${GITHUB_SHA} --require-approval never
4. (BucketDeployment trong JarsApp upload apps/web/dist và invalidate CloudFront)
5. Smoke test: curl https://<cf-domain>/api/v1/health → 200
```

> Workflow deploy **không tự chạy khi đang TEARDOWN**. Dùng biến repo `DEPLOY_ENABLED=false` để bỏ qua bước deploy, tránh vô tình dựng lại stack tốn tiền.

## 5. Chiến lược release và rollback

| Cơ chế | Mô tả |
|---|---|
| ECS deployment circuit breaker (`rollback=true`) | Task mới không healthy thì ECS tự quay về task definition cũ |
| `minimumHealthyPercent=100`, `maximumPercent=200` | Task mới healthy rồi mới tắt task cũ (zero-downtime với 1 task) |
| Rollback thủ công | `cdk deploy JarsApp -c imageTag=<sha-cũ>` |
| DB migration | Flyway chạy lúc app khởi động. **Migration phải backward-compatible** (expand → migrate → contract) để rollback image không làm hỏng DB |
| Blue/green (Phase 6) | Thử ECS native blue/green với test listener + lifecycle hook (Lambda smoke test) |

## 6. Checklist sau deploy

- [ ] `GET /api/v1/health` trả 200 qua CloudFront.
- [ ] Đăng nhập bằng managed login → `GET /api/v1/me` trả 200.
- [ ] Gọi thẳng DNS của ALB từ internet → **không kết nối được** (ALB internal).
- [ ] CloudWatch Logs có log JSON của task mới.
- [ ] Sau 10 phút không dùng, Aurora về trạng thái paused (`ServerlessDatabaseCapacity = 0`).
- [ ] Cập nhật [roadmap](../06-plan/roadmap.md) và [nhật ký chi phí](cost-and-budget.md#6-nhật-ký-chi-phí-thực-tế).
