# Local Development

> Phase 0–2 làm **hoàn toàn ở local**, chỉ có Cognito (miễn phí) và Bedrock (tính theo lượng gọi) là dùng trên cloud. Mục tiêu là không tốn tiền AWS khi đang viết code nghiệp vụ.

## 1. Cài đặt trên máy

| Công cụ | Version | Trạng thái máy dev (2026-10-23) | Cài bằng |
|---|---|---|---|
| JDK | **25** (Temurin) | ✅ 25.0.4 (cài 23/10; 21.0.11 vẫn còn, nên phải trỏ `JAVA_HOME` đúng bản) | `winget install EclipseAdoptium.Temurin.25.JDK` |
| Maven | dùng **Maven Wrapper** (`./mvnw`) | ❌ chưa có `mvn` (không cần nếu dùng wrapper) | đi kèm repo |
| Docker Desktop | mới nhất | ✅ 29.5.3 đang chạy | mở Docker Desktop |
| Node.js | 22/24 LTS | ✅ 24.15 | – |
| Python | 3.13 | ✅ 3.13.9 | – |
| AWS CLI v2 | ≥ 2.32 (cần cho `aws login`) | ✅ 2.35.5 (đang dùng root qua `aws login`, xem ADR-0018) | – |
| AWS CDK CLI | 2.x | dùng `npx aws-cdk@2` | – |
| Graphviz | (chỉ để render diagram) | ❌ | `winget install Graphviz.Graphviz` |
| IDE | IntelliJ IDEA (Community đủ dùng) hoặc VS Code + Extension Pack for Java | | |

## 2. Thành phần chạy local (`local/compose.yaml`, tạo ở Phase 0)

| Service | Image | Port | Dùng cho |
|---|---|---|---|
| `postgres` | `pgvector/pgvector:pg17` | 5432 | DB chính (khớp major version với Aurora 17) |
| `valkey` | `valkey/valkey:8` | 6379 | Cache, rate limit (Phase 4) |
| `lgtm` | `grafana/otel-lgtm` | 3000, 4317, 4318 | Xem trace, metric, log ở local (Phase 6, tùy chọn) |
| `localstack` | `localstack/localstack` (cần `LOCALSTACK_AUTH_TOKEN`) | 4566 | S3, SQS, SNS, EventBridge ở local (Phase 2–5) |
| `kafka` | `apache/kafka` (KRaft) | 9092 | **Lab tùy chọn** (ADR-0009) |

> **LocalStack:** từ bản 2026.03.0, image yêu cầu **auth token**. Gói Hobby (phi thương mại) và gói Student là miễn phí. Đăng ký tài khoản, đặt `LOCALSTACK_AUTH_TOKEN` trong file `local/.env` (đã gitignore) và trong GitHub Secrets cho CI. Nhớ **pin version image**, không dùng `latest`.
> Nếu không muốn dùng LocalStack: test dùng mock (`@MockitoBean SqsTemplate`), hoặc chạy thử với SQS thật trên tài khoản dev (chi phí gần $0).

## 3. Spring profiles

| Profile | Khi nào | DB | Auth | Messaging | AI |
|---|---|---|---|---|---|
| `local` | Chạy app trên máy | compose `postgres` | JWT thật từ Cognito user pool dev (`JarsAuth` deploy sớm) | in-process (Modulith) | Bedrock thật qua SSO profile, **hoặc** `jars.advisor.stub=true` |
| `test` | `./mvnw verify` | Testcontainers PostgreSQL (`@ServiceConnection`) | `spring-security-test` `jwt()` | in-process; LocalStack container cho test tích hợp SQS | Stub `ChatModel` |
| `aws` | ECS | Aurora (secret từ Secrets Manager) | Cognito | SQS/SNS thật | Bedrock |

## 4. Chạy (sẽ hoàn thiện ở Phase 0)

```bash
# 1. Hạ tầng local
docker compose -f local/compose.yaml up -d postgres

# 2. Backend
cd apps/jars-api
./mvnw spring-boot:run -Dspring-boot.run.profiles=local      # http://localhost:8080

# 3. Frontend (Vite proxy /api -> localhost:8080, nên cùng origin, không cần CORS)
cd apps/web
npm ci && npm run dev                                           # http://localhost:5173

# 4. Test toàn bộ backend (cần Docker đang chạy)
cd apps/jars-api && ./mvnw verify
```

## 5. AWS credentials cho local (không dùng root)

Dùng IAM user `phuc-account` (có MFA) + `aws login`, credential tạm thời tối đa 12 giờ ([ADR-0018](../04-adr/0018-human-access-iam-user-aws-login.md)):

```bash
aws configure set region ap-southeast-1 --profile jars-dev
aws login --profile jars-dev              # mở trình duyệt → đăng nhập phuc-account + MFA
export AWS_PROFILE=jars-dev               # PowerShell: $env:AWS_PROFILE="jars-dev"
aws sts get-caller-identity               # phải ra ...:user/phuc-account, KHÔNG phải :root
```

> Hiện tại (quyết định ngày 23/10) bạn đang dùng **root** qua `aws login` với profile `default` (region mặc định **us-east-1**). App luôn đặt region `ap-southeast-1` qua cấu hình Spring, nên không phụ thuộc vào region của profile.

Spring AI và AWS SDK tự đọc credential theo default provider chain, nên **không ghi access key vào file config**. Credential của `aws login` (`login_session`) **chỉ đọc được khi có module `software.amazon.awssdk:signin`** trên classpath (đã kiểm chứng ở P0.5). Nếu thiếu, SDK báo lỗi `To use login_session property ... the 'signin' service module must be on the class path`. Thêm vào `pom.xml` với scope `runtime`. Nếu vẫn không được (SDK quá cũ), dùng `credential_process` làm cầu nối:

```ini
# ~/.aws/config
[profile jars-dev-sdk]
region = ap-southeast-1
credential_process = aws configure export-credentials --profile jars-dev --format process
```

## 6. Dữ liệu mẫu

Phase 1 sẽ viết `SeedDataRunner` (chỉ chạy ở profile `local`): tạo 1 user demo, 6 hũ, khoảng 3 tháng giao dịch giả lập (ý tưởng từ `backend/seed/seed.json` của bản cũ), và một vài "cú sốc chi tiêu" để demo CUSUM.

## 7. Lỗi hay gặp

| Triệu chứng | Nguyên nhân | Cách sửa |
|---|---|---|
| `Could not find a valid Docker environment` khi test | Docker Desktop chưa chạy | Mở Docker Desktop |
| AWS CLI báo `Invalid name` khi tên bắt đầu bằng `/` (ví dụ `/jars/dev/...`) | Git Bash (MSYS) tự đổi tham số thành đường dẫn `C:/Program Files/Git/...` | Thêm `MSYS_NO_PATHCONV=1` trước lệnh, hoặc chạy trong PowerShell |
| Test (kể cả test Modulith) đòi credential AWS | `spring.config.import=aws-secretsmanager:` đặt trong `application.yaml` chung | Chỉ đặt trong `application-aws.yaml`, có tiền tố `optional:` |
| Bedrock `AccessDeniedException ... is not available for this account` | Model có inference profile nhưng account chưa được phép gọi (ví dụ Haiku 5.5) | Dùng model đã kiểm chứng: `global.anthropic.claude-haiku-4-5-20251001-v1:0` |
| `401` dù đã đăng nhập | Gửi **ID token** thay vì access token | Lấy `accessToken` từ Amplify `fetchAuthSession()` |
| `Unsupported class file major version 69` | Chạy bằng JDK 21 | Đặt `JAVA_HOME` trỏ tới JDK 25 |
| LocalStack thoát ngay khi start | Thiếu `LOCALSTACK_AUTH_TOKEN` | Xem mục 2 |
| `AccessDeniedException` / `ExpiredToken` từ AWS | Phiên `aws login` hết hạn (12 giờ) hoặc model Bedrock chưa được bật | `aws login --profile jars-dev`; kiểm tra model access ở console Bedrock (ap-southeast-1) |
