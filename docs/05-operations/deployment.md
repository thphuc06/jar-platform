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
| 6 | `npx aws-cdk@2 bootstrap aws://626212071763/ap-southeast-1 --profile jars-dev` | CDK cần bucket và role bootstrap ở mỗi region | Hiện mới bootstrap ở us-east-1 |
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
| Phase 1 | `cdk deploy JarsAuth` | Cần Cognito để có JWT thật khi chạy local |
| Phase 3, lần đầu | `cdk deploy JarsFoundation JarsNetwork JarsData JarsMessaging` | Hạ tầng nền (ECR, VPC, Aurora, SQS) |
| Phase 3 | Push image đầu tiên lên ECR (bằng CI hoặc tay), rồi `cdk deploy JarsApp -c imageTag=<sha>` | Lên app |
| Phase 4 | `cdk deploy JarsQuant JarsApp` | Thêm quant-fn, Valkey, quyền Bedrock |
| Phase 5 | `cdk deploy JarsMessaging JarsApp` (+ service worker) | SNS/SQS FIFO + insights-worker |
| Phase 6 | `cdk deploy JarsObservability` | Dashboard, alarm |

Tất cả lệnh chạy trong `infra/cdk` với `--profile jars-dev`. Trước mỗi lần deploy nên chạy `cdk diff` để xem thay đổi.

### Dọn stack cũ trước lần deploy đầu

Ba stack `jars-dev-foundation`, `jars-dev-network`, `jars-dev-auth` được deploy hồi 10/2026 từ code không còn nữa. Stack mới có id khác (`JarsFoundation`, `JarsNetwork`, `JarsAuth`...) nhưng một số tài nguyên có tên duy nhất trong account nên **phải xóa stack cũ trước**:

| Tài nguyên | Vì sao đụng | Việc cần làm |
|---|---|---|
| GitHub OIDC provider `token.actions.githubusercontent.com` | Mỗi account chỉ có một provider cho một URL | Xóa `jars-dev-foundation` |
| Role `jars-dev-github-deploy`, SNS topic `jars-dev-alarms`, budget `jars-dev-monthly` | Cùng tên với tài nguyên mới | Xóa theo `jars-dev-foundation` |
| ECR repo `jars/jars-api` | Repo cũ có `DeletionPolicy: Retain`: xóa stack **không** xóa repo, và stack mới không tạo được repo cùng tên | Sau khi xóa stack, xóa tay (kiểm tra ngày 2026-11-12: 0 image): `aws ecr delete-repository --repository-name jars/jars-api --force --region ap-southeast-1` |
| Cognito domain `jars-dev-thphuc06` | Domain gắn với user pool cũ | Xóa `jars-dev-auth` (domain xóa theo stack) |
| User pool cũ `jars-dev-users` | Có `Retain`, vẫn còn sau khi xóa stack (kiểm tra ngày 2026-11-12: 0 user) | Xóa tay nếu không cần. Pool do `JarsAuth` tạo là một pool khác, nên `JARS_ISSUER_URI` đổi theo |

Sau đó: `cdk deploy JarsFoundation JarsNetwork JarsAuth JarsData JarsMessaging -c alertEmail=<địa chỉ>`, bấm xác nhận email SNS, rồi push image và `cdk deploy JarsApp -c imageTag=<sha>`. Các giá trị script smoke test cần nằm trong CloudFormation outputs: `CloudFrontDomain`, `SpaClientId` (JarsApp), `UserPoolId`, `SmokeClientId` (JarsAuth), `EcrRepositoryUri` (JarsFoundation), `DbClusterIdentifier` (JarsData).

### Lần đầu deploy `JarsApp`: hai lượt

Web cần `SpaClientId`, mà client đó do chính `JarsApp` tạo ra, nên lần đầu phải deploy hai lượt (từ lần sau chỉ cần lượt 2, vì Client ID không đổi). Trình tự đã chạy thật ngày 2026-11-12, trong `infra/cdk` trừ khi ghi khác:

| Bước | Lệnh | Thời gian đo được |
|---|---|---|
| 1 | `pwsh scripts/push-image.ps1` (build `linux/arm64`, tag = 12 ký tự đầu của git SHA, từ chối chạy khi working tree bẩn) | đẩy layer lên ECR mất 80 giây (thời gian build không đo riêng) |
| 2 | `cdk deploy JarsApp -c imageTag=<sha> --outputs-file cdk-outputs.json` (chưa có web) | **18 phút** (ALB 2, VPC origin 9, distribution 3, ECS 3,4) |
| 3 | Đọc `SpaClientId` và `CloudFrontDomain` từ outputs; trong `apps/web` đặt `VITE_COGNITO_USER_POOL_ID`, `VITE_COGNITO_CLIENT_ID`, `VITE_COGNITO_DOMAIN` rồi `npm run build` | 12 giây |
| 4 | `cdk deploy JarsApp -c imageTag=<sha> -c deployWeb=true -c webDistDir=<đường dẫn tuyệt đối tới apps/web/dist> --exclusively` | 4 phút (upload S3 + invalidate CloudFront) |
| 5 | `pwsh scripts/smoke-test.ps1` | 50 giây, 11 kiểm tra |

`imageTag` phải truyền ở **cả hai** lượt (để trống thì synth báo lỗi cho `JarsApp`). Callback URL của Cognito client (`https://<CloudFrontDomain>/auth/callback` và `http://localhost:5173/auth/callback`) do CDK ghi vào, không phải sửa tay.

Một điều chưa giải thích được: lượt 2 cũng đăng ký task definition revision 2 và thay task một lần (2 phút), dù nội dung khác revision 1 chỉ ở thứ tự của mục `compatibilities`. Vô hại, nhưng nếu muốn lượt web không đụng tới service thì cần tìm ra thay đổi nào trong template làm CloudFormation thay task definition (`cdk diff` trước lượt 2 sẽ cho biết).

## 3. Cấu hình theo môi trường (`infra/cdk/cdk.json` → `context`)

| Key | Mặc định | Ý nghĩa |
|---|---|---|
| `env` | `dev` | Tiền tố tên resource |
| `account` / `region` | `626212071763` / `ap-southeast-1` | |
| `imageTag` | (trống; bắt buộc với `JarsApp`) | Git SHA của image jars-api. Khi trống, `JarsApp` **không** được synth (các stack khác vẫn deploy được), `cdk deploy JarsApp` báo "No stacks match" |
| `apiDesiredCount` | `1` | `0` = chế độ SLEEP |
| `dbMode` | `aurora` | `rds` = chuyển sang RDS t4g.micro dự phòng (ADR-0008) |
| `enableCache` | `false` (Phase 3) / `true` (Phase 4+) | Tạo Valkey Serverless |
| `customDomain` | (trống) | Nếu có domain riêng: tạo ACM ở us-east-1 + bản ghi Route 53 |
| `alertEmail` | (trống, không bao giờ commit email thật) | Nhận budget alarm và CloudWatch alarm. Truyền `-c alertEmail=<địa chỉ>` hoặc đặt biến môi trường `JARS_ALERT_EMAIL`; trống thì topic không có người đăng ký và synth in cảnh báo. SNS gửi email xác nhận, phải bấm xác nhận một lần |
| `deployWeb` | `false` | `true` = `BucketDeployment` upload `apps/web/dist` (build bởi người khác) và invalidate CloudFront |
| `webDistDir` | `../../apps/web/dist` | Thư mục build của SPA, chỉ đọc khi `deployWeb=true` (đường dẫn tính từ `infra/cdk`) |
| `cloudFrontPrefixListId` | `pl-31a34658` (ap-southeast-1) | ID của managed prefix list `com.amazonaws.global.cloudfront.origin-facing`; region khác phải truyền vào |

## 4. Pipeline

### `ci.yml` (mỗi PR và mỗi lần push lên `main`)

**Đang có (P0.8):** [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)

| Job | Làm gì | Ghi chú |
|---|---|---|
| `jars-api` | `setup-java` Temurin 21 (có cache Maven) → `./mvnw --batch-mode --no-transfer-progress verify` | `verify` gồm unit test + Testcontainers (Docker của runner) + Modulith `verify()` + Spotless check. Fail thì upload `surefire-reports`; pass thì upload `modulith-docs` |
| `gitleaks` | Quét **toàn bộ lịch sử** git (`fetch-depth: 0`) | Action pin theo commit SHA (v3.0.0); tắt comment vào PR nên chỉ cần quyền `contents: read`; repo cá nhân nên không cần license |

Toàn workflow: `permissions: contents: read`; dùng `concurrency` để push mới hủy lần chạy cũ; mỗi job có `timeout-minutes`. Cấu hình [`.github/dependabot.yml`](../../.github/dependabot.yml) mở PR cập nhật Maven và Actions hằng tuần (gom nhóm Spring và Spring Cloud AWS để các version khớp nhau luôn đi cùng).

**Thêm sau** (khi có app tương ứng, khi đó dùng path filter để chỉ build phần bị thay đổi):

```
paths: apps/web/**           → npm ci → lint → test → build                 (P1.11)
paths: functions/quant-fn/** → pip install → pytest                           (P4.2)
paths: infra/cdk/**          → ./mvnw test (CDK assertions + cdk-nag) → cdk synth      (đã có: .github/workflows/infra.yml)
```

Kiểm tra workflow ở local trước khi push: `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12`. Quét secret ở local: `docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo --redact`.

### `deploy-dev.yml` (merge `main` hoặc `workflow_dispatch`)

```
permissions: id-token: write, contents: read
1. aws-actions/configure-aws-credentials (role-to-assume: jars-github-deploy, region ap-southeast-1)
2. docker buildx build --platform linux/arm64 -t <ecr>/jars/jars-api:${GITHUB_SHA} --push
3. cd infra/cdk && npx aws-cdk@2 deploy JarsApp JarsQuant -c imageTag=${GITHUB_SHA} --require-approval never
4. (BucketDeployment trong JarsApp upload apps/web/dist và invalidate CloudFront)
5. Smoke test: curl https://<cf-domain>/api/v1/health → 200
```

> ⚠️ **Quyền ECR của role deploy:** `jars-dev-github-deploy` chỉ được assume các role bootstrap của CDK ([security.md §3](../02-architecture/security.md#3-iam)). Bước 2 (`docker push` vào `jars/jars-api`) cần thêm quyền push ECR trên đúng repo đó (stack cũ có statement này). Việc thêm quyền là quyết định bảo mật riêng, chưa làm trong `JarsFoundation`.

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

Kết quả lần deploy đầu (2026-11-12): các mục đã tick là đã kiểm bằng `scripts/smoke-test.ps1` hoặc lệnh AWS CLI; mục chưa tick là chưa kiểm.

- [x] `GET /api/v1/health` trả 200 qua CloudFront.
- [x] Token Cognito thật (lấy bằng `admin-initiate-auth`) → `GET /api/v1/me` trả 200 và tạo user cùng 6 hũ. Đăng nhập bằng **trình duyệt qua managed login** thì chưa kiểm tay: mới xác nhận bundle web chứa đúng pool/client/domain và callback URL của client khớp domain CloudFront.
- [x] Idempotency, user khác nhận 404, anomaly → notification → SSE qua CloudFront (smoke test: 11/11).
- [x] Gọi thẳng DNS của ALB từ internet → **không kết nối được** (ALB `scheme=internal`; smoke test kiểm bằng `describe-load-balancers`, chưa thử kết nối từ ngoài).
- [x] CloudWatch Logs có log JSON của task mới (`/jars/dev/api`, retention 14 ngày, định dạng ECS). Dòng khởi động: `Started JarsApplication in 53.275 seconds`, đo trên Fargate 0,5 vCPU khi Aurora vừa resume từ trạng thái paused (so với khoảng 31 giây khi chạy image với Postgres local).
- [x] Aurora tự pause khi không dùng: connection cuối lúc 18:59 (giờ VN), sự kiện RDS `Initiated pause` lúc 19:05:02, tức khoảng 6 phút sau (`SecondsUntilAutoPause = 300` cộng độ trễ metric); `ServerlessDatabaseCapacity` về 0 từ 19:06. Lúc app khởi động, `Initiated resume ... due to user activity` đến `Successfully resumed` mất 15 giây.
- [ ] Cập nhật [roadmap](../06-plan/roadmap.md) và [nhật ký chi phí](cost-and-budget.md#6-nhật-ký-chi-phí-thực-tế).
