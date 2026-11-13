# Local Development

> Phase 0–2 làm **hoàn toàn ở local**, chỉ có Cognito (miễn phí) và Bedrock (tính theo lượng gọi) là dùng trên cloud. Mục tiêu là không tốn tiền AWS khi đang viết code nghiệp vụ.

## 1. Cài đặt trên máy

| Công cụ | Version | Trạng thái máy dev (2026-10-23) | Cài bằng |
|---|---|---|---|
| JDK | **21** (Temurin) | ✅ 21.0.13 ([ADR-0019](../04-adr/0019-java-21-lts.md)); máy còn JDK 17 và 8 nên phải trỏ `JAVA_HOME` đúng bản 21 | `winget install EclipseAdoptium.Temurin.21.JDK` |
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
| `postgres` ✅ (P0.6) | `postgres:17-alpine` (đang là 17.11) | 5432 | DB chính, khớp major version với Aurora 17. User/db `jars`, password chỉ dùng local. Chỉ đổi sang `pgvector/pgvector:pg17` nếu làm RAG bằng pgvector (P4.9 hiện chọn S3 Vectors) |
| `valkey` | `valkey/valkey:8` | 6379 | **Không còn kế hoạch dùng**: rate limit của advisor nằm trong PostgreSQL ([ADR-0023](../04-adr/0023-advisor-guardrail-and-rate-limit.md)); chỉ cần khi chạy ≥ 2 task (Phase 3+ backlog) |
| `lgtm` | `grafana/otel-lgtm` | 3000, 4317, 4318 | Xem trace, metric, log ở local (tùy chọn, backlog Phase 3+) |
| `localstack` | `localstack/localstack` (cần `LOCALSTACK_AUTH_TOKEN`) | 4566 | S3, SQS, SNS, EventBridge ở local (tùy chọn, hiện chưa dùng: test dùng Testcontainers Postgres và mock) |
| `kafka` | `apache/kafka` (KRaft) | 9092 | **Lab tùy chọn** (ADR-0009) |

> **LocalStack:** từ bản 2026.03.0, image yêu cầu **auth token**. Gói Hobby (phi thương mại) và gói Student là miễn phí. Đăng ký tài khoản, đặt `LOCALSTACK_AUTH_TOKEN` trong file `local/.env` (đã gitignore) và trong GitHub Secrets cho CI. Nhớ **pin version image**, không dùng `latest`.
> Nếu không muốn dùng LocalStack: test dùng mock (`@MockitoBean SqsTemplate`), hoặc chạy thử với SQS thật trên tài khoản dev (chi phí gần $0).

## 3. Spring profiles

| Profile | Khi nào | DB | Auth | Messaging | AI |
|---|---|---|---|---|---|
| `local` (**mặc định** khi không đặt `SPRING_PROFILES_ACTIVE`) | Chạy app trên máy | compose `postgres`, Spring Boot **tự khởi động** `local/compose.yaml` (`spring-boot-docker-compose`, chế độ `start-only`) | App tự phát token: `POST /dev/token` (chỉ có ở profile `local`, khóa RSA sinh ngẫu nhiên mỗi lần chạy), **không cần Cognito** | in-process (Modulith) | chưa có (ngoài phạm vi Phase 1) |
| (test) | `./mvnw verify` | Testcontainers `postgres:17-alpine` (`@ServiceConnection`, class `TestcontainersConfiguration`) | `spring-security-test` `jwt()` | in-process; LocalStack container cho test tích hợp SQS | Stub `ChatModel` |
| `aws` | ECS | Aurora (`SPRING_DATASOURCE_*` do task definition cấp, secret từ Secrets Manager) | Cognito (chỉ nhận access token của các app client trong `JARS_ALLOWED_CLIENT_IDS`) | SQS thật cho job khép sổ; log JSON; không có `/dev/token` và không publish OpenAPI | – |

## 4. Chạy

```bash
# 1. Backend: Spring Boot tự chạy local/compose.yaml (Postgres) và chờ healthy, rồi Flyway migrate
cd apps/jars-api
./mvnw spring-boot:run                                          # http://localhost:8080
curl localhost:8080/actuator/health                             # có "db":{"status":"UP"}

#    Cách khác: DB mới tinh mỗi lần chạy → chạy class TestJarsApplication (src/test) trong IDE (Testcontainers)
#    Dừng Postgres khi không dùng (dữ liệu giữ trong volume jars_pgdata):
docker compose -f ../../local/compose.yaml stop
#    Xóa sạch dữ liệu local:  docker compose -f ../../local/compose.yaml down -v

# 2. Lấy token và gọi API (profile local, không cần Cognito)
curl -s -X POST localhost:8080/dev/token -H 'Content-Type: application/json' -d '{"sub":"demo"}'   # -> {"accessToken":"..."}
curl -s localhost:8080/api/v1/jars -H "Authorization: Bearer <accessToken>"
# Swagger UI: http://localhost:8080/swagger-ui.html (nút Authorize, dán accessToken)

#    Dữ liệu mẫu (3 tháng giao dịch + 2 khoản chi bất thường để thấy cảnh báo) cho subject "demo":
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--jars.seed.enabled=true"
#    chạy lại không nhân đôi dữ liệu; mất khoảng 30 giây để các detector học xong, sau đó GET /api/v1/anomalies có 2 cảnh báo

# 3. Frontend (Vite proxy /api -> localhost:8080, nên cùng origin, không cần CORS)
cd apps/web
npm ci && npm run dev                                           # http://localhost:5173

# 4. Test toàn bộ backend (cần Docker đang chạy)
cd apps/jars-api && ./mvnw clean verify
#    Nếu IDE (VS Code Java) vẫn cấu hình JDK cũ, nó có thể ghi class file sai phiên bản vào target/ trong lúc Maven chạy
#    (lỗi "class file has wrong version 69.0"): reload project Java trong IDE hoặc build từ một bản sao của repo.

# 5. Image Docker như trên Fargate (đã thử: sẵn sàng sau ~30 giây với 0.5 vCPU / 1 GB, dùng ~355 MiB)
docker build -t jars-api:dev apps/jars-api
docker run --rm --read-only --tmpfs /tmp -p 8080:8080 -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/jars -e SPRING_DATASOURCE_USERNAME=jars -e SPRING_DATASOURCE_PASSWORD=jars-local jars-api:dev
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
| `jars-api needs JDK 21 or newer` (từ maven-enforcer) hoặc `Unsupported class file major version 65` | Chạy bằng JDK 17 hoặc 8 | Đặt `JAVA_HOME` trỏ tới JDK 21 |
| LocalStack thoát ngay khi start | Thiếu `LOCALSTACK_AUTH_TOKEN` | Xem mục 2 |
| `Migration checksum mismatch for migration version 7/8` khi `spring-boot:run` | Volume Postgres của Docker Compose còn schema của bản cài đặt đầu tiên (trước khi hợp nhất `main` ngày 12/11/2026), migration cùng số nhưng khác nội dung | Dữ liệu dev, xóa được: `docker compose -f local/compose.yaml down -v` rồi chạy lại |
| `Vitest failed to find the current suite` khi `npm test` trên Windows | Đường dẫn làm việc có chữ ổ đĩa viết thường (`c:\...`), Vitest nạp hai bản | Mở terminal ở `C:\...` (chữ hoa) rồi chạy lại; CI trên Linux không bị |

## 8. Nhập sao kê ở local

Không có S3/SQS ở local: `jars.imports.storage=local` (mặc định) lưu tệp vào `${java.io.tmpdir}/jars-statements`, và `upload.url` trả về là `PUT /api/v1/imports/{id}/file` cùng origin (web gửi kèm token). Endpoint này chạy job import ngay trong request. Thử bằng curl:

```bash
TOKEN=$(curl -s -X POST localhost:8080/dev/token -H 'Content-Type: application/json' -d '{"sub":"demo"}' | jq -r .accessToken)
ID=$(curl -s -X POST localhost:8080/api/v1/imports -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"fileName":"thang-10.csv"}' | jq -r .statementImport.id)
printf 'date,description,amount\n2026-10-02,GRAB *TRIP,-45000\n' | curl -s -X PUT "localhost:8080/api/v1/imports/$ID/file" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: text/csv' --data-binary @- | jq '{status, postedRows, skippedRows}'
```
| `AccessDeniedException` / `ExpiredToken` từ AWS | Phiên `aws login` hết hạn (12 giờ) hoặc model Bedrock chưa được bật | `aws login --profile jars-dev`; kiểm tra model access ở console Bedrock (ap-southeast-1) |
