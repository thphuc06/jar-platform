# AWS Infrastructure

> Region: **ap-southeast-1 (Singapore)** ([ADR-0005](../04-adr/0005-region-ap-southeast-1.md)) · Môi trường: **`dev`** (duy nhất) · IaC: **AWS CDK v2 (Java)** ([ADR-0012](../04-adr/0012-iac-aws-cdk-java.md))
> Giá trích dẫn ở đây lấy từ AWS Pricing API cho ap-southeast-1 ngày **2026-10-23**. Chi tiết xem [cost-and-budget.md](../05-operations/cost-and-budget.md).

![AWS target architecture](../diagrams/aws-architecture.png)

## 1. CDK stacks

Hạ tầng chia thành nhiều stack theo **vòng đời**: stack nào hay bị xóa và tạo lại (App) thì không chứa dữ liệu, còn stack chứa dữ liệu (Data) thì giữ lâu dài.

| Stack | Chứa gì | Vòng đời | Phụ thuộc |
|---|---|---|---|
| `JarsFoundation` | ECR repositories (immutable tags, scan on push), GitHub OIDC provider + deploy role, SNS topic cho alarm, AWS Budget theo tag `Project=jars` | Tạo 1 lần, giữ mãi | – |
| *(không phải stack)* account baseline | S3 Block Public Access (account), EBS default encryption, IAM Access Analyzer, Cost Anomaly Detection: làm bằng tay ở Phase 0 vì áp dụng cho cả account dùng chung ([deployment.md §1](../05-operations/deployment.md#1-chuẩn-bị-tài-khoản-làm-1-lần)) | Giữ mãi | – |
| `JarsNetwork` | VPC 2 AZ (public / private / isolated subnets), security groups | Giữ mãi (không tốn phí giờ) | – |
| `JarsData` | Aurora PostgreSQL Serverless v2 + secret, S3 `statements` bucket | Giữ mãi (`RemovalPolicy.SNAPSHOT`) | Network |
| `JarsAuth` | Cognito User Pool, managed login domain, resource server/scopes | Giữ mãi (user không mất) | – |
| `JarsMessaging` | SQS (jobs, imports) + DLQ, EventBridge rules, Scheduler; từ Phase 5: SNS FIFO + SQS FIFO | Giữ mãi (gần như $0) | Data (bucket) |
| `JarsApp` | ECS cluster, task definition, service, internal ALB, CloudFront + VPC origin, web bucket, Cognito app client, (Phase 4) Valkey | **Xóa khi không demo lâu ngày** | Network, Data, Auth, Messaging |
| `JarsQuant` | Lambda `quant-fn` (container image) | Giữ (chỉ tính tiền khi gọi) | Foundation (ECR) |
| `JarsObservability` | CloudWatch dashboard, alarm (5xx, latency, DLQ depth, Aurora ACU) | Phase 6 | App |

**Thứ tự deploy lần đầu:** Foundation → Network → Auth → Data → Messaging → Quant → App → Observability. CDK tự suy ra thứ tự khi chạy `cdk deploy --all`.

> **Vì sao Cognito *app client* nằm ở `JarsApp` chứ không ở `JarsAuth`?** Callback URL của app client phải là domain CloudFront, mà CloudFront nằm trong `JarsApp`. Đặt app client ở `JarsAuth` sẽ tạo phụ thuộc vòng. User pool (chứa user) vẫn nằm ở `JarsAuth` nên xóa `JarsApp` không làm mất user.

**Tag chung** cho mọi resource: `Project=jars`, `Env=dev`, `ManagedBy=cdk`, `Owner=thphuc06`. Hãy bật `Project` làm *cost allocation tag* để lọc chi phí trong Cost Explorer.

## 2. Edge: CloudFront + S3 + VPC origin

| Hạng mục | Cấu hình |
|---|---|
| Distribution | Một distribution, domain mặc định `dxxxx.cloudfront.net` (tùy chọn: domain riêng qua Route 53 + ACM ở us-east-1) |
| Behavior `/*` (default) | Origin = S3 `web` bucket qua **Origin Access Control (OAC)**; cache policy `CachingOptimized`; SPA fallback (403/404 → `/index.html`) |
| Behavior `/api/*` | Origin = **VPC origin** trỏ tới **internal ALB**; cache policy `CachingDisabled`; origin request policy `AllViewerExceptHostHeader`; allowed methods = ALL. CloudFront **mặc định bỏ header `Authorization`** và không cho thêm header này riêng lẻ vào origin request policy. Chỉ khi chuyển *toàn bộ* viewer header (và tắt cache) thì `Authorization` mới tới origin ([re:Post](https://repost.aws/knowledge-center/cloudfront-authorization-header), [managed policies](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/using-managed-origin-request-policies.html)) |
| Timeout | Origin response timeout mặc định 30 s. SSE gửi **heartbeat mỗi 15 s** nên kết nối không bị coi là idle. Keep-alive timeout chỉnh được với custom/VPC origin |
| Response headers policy | HSTS, `X-Content-Type-Options`, `Referrer-Policy`, CSP cho SPA |
| WAF | Tùy chọn ở Phase 6 (tốn phí theo web ACL + rule) |

**Vì sao dùng VPC origin:** CloudFront kết nối thẳng vào ALB nằm trong private subnet thông qua một ENI do AWS quản lý. ALB **không có IP public**, nên không ai bỏ qua CloudFront để gọi thẳng API được. Yêu cầu khi cấu hình:
- Security group của ALB phải cho phép CloudFront managed prefix list.
- Subnet phải còn IPv4 trống cho ENI.
- VPC phải có Internet Gateway (IGW chỉ đánh dấu VPC nhận traffic internet, không dùng để route).

Docs: [Restrict access with VPC origins](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-vpc-origins.html).

## 3. Network: VPC

![VPC](../diagrams/aws-network.png)

| Subnet group (×2 AZ) | CDK `SubnetType` | CIDR | Chứa | Route ra internet |
|---|---|---|---|---|
| `public` | `PUBLIC` | 10.20.0.0/24, 10.20.1.0/24 | ECS tasks (`assignPublicIp=ENABLED`) | IGW |
| `lb` ("private") | `PRIVATE_ISOLATED` | 10.20.10.0/24, 10.20.11.0/24 | internal ALB, ENI của CloudFront VPC origin | Không |
| `data` ("isolated") | `PRIVATE_ISOLATED` | 10.20.20.0/24, 10.20.21.0/24 | Aurora, Valkey | Không |

> Trong CDK, `PRIVATE_WITH_EGRESS` bắt buộc phải có NAT, nên cả hai nhóm không có NAT đều khai báo là `PRIVATE_ISOLATED`. Tách làm hai nhóm `lb` và `data` để quy tắc security group và NACL rõ ràng hơn.

**Không có NAT Gateway** ([ADR-0013](../04-adr/0013-networking-no-nat.md)). Task cần gọi ra ngoài (Bedrock, SQS, ECR, Cognito JWKS...) thì đi qua IP public của chính nó. An toàn được đảm bảo bằng security group:

| Security group | Inbound | Outbound |
|---|---|---|
| `sg-alb` | TCP 80 từ CloudFront managed prefix list (origin-facing) | TCP 8080 tới `sg-app` |
| `sg-app` | TCP 8080 **chỉ từ `sg-alb`** | 443 ra mọi nơi; 5432 tới `sg-db`; 6379 tới `sg-cache` |
| `sg-db` | TCP 5432 từ `sg-app` (và `sg-worker` ở Phase 5) | – |
| `sg-cache` | TCP 6379 từ `sg-app`, `sg-worker` | – |

> Lộ trình nâng cấp lên "chuẩn production": chuyển task vào private subnet và thêm NAT Gateway (khoảng $43/tháng mỗi AZ) **hoặc** VPC interface endpoint ($0.013/giờ mỗi endpoint mỗi AZ) **hoặc** dùng fck-nat trên EC2 t4g.nano (khoảng $3.9/tháng). Chi tiết trong ADR-0013.

## 4. Compute: ECS on Fargate

| Hạng mục | `jars-api` | `insights-worker` (Phase 5) |
|---|---|---|
| Launch type | Fargate, **ARM64 (Graviton)** | Fargate ARM64 |
| CPU / RAM | 0.5 vCPU / 1 GB (JVM `-XX:MaxRAMPercentage=75`) | 0.25 vCPU / 0.5 GB |
| Desired count | 1 (dev). Đặt về 0 khi "ngủ" | 1 / 0 |
| Image | ECR `jars/jars-api:<git-sha>`, base `eclipse-temurin:25-jre` (multi-arch) | `jars/insights-worker:<sha>` |
| Port | 8080 (ALB target group, health check `/actuator/health/readiness`) | không mở port |
| Deployment | Rolling + **deployment circuit breaker (auto rollback)**; Phase 6 thử **ECS native blue/green** | Rolling |
| Logs | `awslogs` driver → CloudWatch Logs, log JSON, retention 14 ngày | như bên trái |
| Secrets | `secrets:` trong task definition lấy từ Secrets Manager (DB credentials) | như bên trái |
| Config | Biến môi trường (không phải secret): `SPRING_PROFILES_ACTIVE=aws`, tên queue/topic, model id... | |

**Task role (quyền của ứng dụng)** và **execution role (quyền của agent ECS để kéo image, đọc secret, ghi log)** là 2 role khác nhau, xem [security.md](security.md#3-iam).

### Vòng đời task: khởi động, dừng, draining

| Thiết lập | Giá trị | Vì sao |
|---|---|---|
| `healthCheckGracePeriod` (service) | 90 s | JVM + Spring Boot + Flyway cần thời gian khởi động. Không có grace period thì ECS có thể giết task trước khi task kịp healthy |
| Health check của target group | `/actuator/health/readiness`, interval 15 s, healthy threshold 2 | Readiness phản ánh "sẵn sàng nhận traffic", liveness phản ánh "process còn sống". Không check DB |
| `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=20s` | | Khi nhận SIGTERM: ngừng nhận request mới, xử lý xong request đang chạy, dừng SQS listener |
| `stopTimeout` (container) | 30 s (lớn hơn 20 s của Spring) | Hết thời gian này ECS gửi SIGKILL |
| `initProcessEnabled` | `true` | Có init process chuyển tiếp tín hiệu và dọn process con |
| Deregistration delay (target group) | **30 s** (mặc định là 300 s) | Rút ngắn thời gian deploy. Response thường < 1 s. Kết nối SSE dài sẽ bị ALB cắt khi hết draining → client **tự reconnect với `Last-Event-ID`** (đã thiết kế ở [api-contract §3](../03-api/api-contract.md#3-hợp-đồng-sse)) |
| `readonlyRootFilesystem` | `true` + volume tạm mount vào `/tmp` | Giảm bề mặt tấn công; Tomcat cần `/tmp` để ghi |
| User trong container | non-root (UID cố định) | Best practice bảo mật container |

Docs: [ECS connection draining](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/load-balancer-connection-draining.html), [ECS graceful shutdown (re:Post)](https://repost.aws/knowledge-center/ecs-graceful-shutdown-connection-draining-deployments).

**Health check không được chạm DB.** Nếu health check gọi DB, Aurora sẽ không bao giờ pause. Vì vậy readiness/liveness **không gồm `db`**; trạng thái DB xem ở `/actuator/health` (endpoint đầy đủ, không dùng cho ALB).

Docs: [ECS blue/green deployments](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/migrate-codedeploy-to-ecs-bluegreen.html).

## 5. Database: Aurora PostgreSQL Serverless v2

| Hạng mục | Cấu hình |
|---|---|
| Engine | Aurora PostgreSQL 17.x (bản mới nhất có ở region; scale-to-zero yêu cầu ≥ 16.3) |
| Capacity | **min 0 ACU, max 2 ACU**; auto-pause sau **300 s** không có kết nối (`SecondsUntilAutoPause`) |
| Topology | 1 writer instance (Serverless v2), không có reader |
| Credentials | `credentials: fromGeneratedSecret` → Secrets Manager; app đọc qua ECS secrets |
| TLS | Tham số `rds.force_ssl=1` |
| Backup | Automated backup 1 ngày (tối thiểu); xóa stack thì tạo snapshot |
| Kết nối từ app | HikariCP `minimum-idle=0`, `idle-timeout=60s`, `max-lifetime=5m`, `spring.datasource.connection-fetch=lazy` (tính năng mới của Boot 4.1) → **không giữ kết nối khi không có request** |
| Schema | `jars` (jars-api), `insights` (insights-worker, Phase 5). Mỗi service có DB user riêng |

**Bẫy cần nhớ:**
- Còn **bất kỳ kết nối nào** mở (pool, health check, scheduled job, tool GUI quên đóng) thì Aurora **không pause**, và 0.5 ACU × 24 h × 30 ngày × $0.20 = **$72/tháng**. Có alarm `ServerlessDatabaseCapacity > 0` liên tục 6 giờ để cảnh báo.
- Resume mất khoảng vài giây đến khoảng 15 s. Request đầu tiên sau khi pause sẽ chậm. Timeout kết nối của Hikari cần ≥ 30 s.
- Autovacuum không đánh thức DB đang pause.

**Phương án dự phòng:** RDS PostgreSQL `db.t4g.micro` ($0.025/giờ ≈ $18/tháng, cố định). Chỉ cần đổi một CDK context flag `-c dbMode=rds`. Xem [ADR-0008](../04-adr/0008-database-aurora-serverless-v2.md).

Docs: [Aurora Serverless v2 auto-pause](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-serverless-v2-auto-pause.html).

## 6. Auth: Cognito

| Hạng mục | Cấu hình |
|---|---|
| User pool | Feature plan **Essentials**. Đăng nhập bằng email; password policy mặc định; MFA tùy chọn (TOTP) |
| Managed login | Domain `jars-dev-<suffix>.auth.ap-southeast-1.amazoncognito.com` |
| App client (SPA) | **Public client, không có client secret**; flow **Authorization Code + PKCE**; callback `https://<cloudfront-domain>/auth/callback` và `http://localhost:5173/auth/callback` |
| Token | Access token 60 phút, refresh token 30 ngày; API **chỉ nhận access token** (`token_use=access`) |
| Resource server | Identifier `jars-api`, scope `jars-api/read`, `jars-api/write` |
| Lambda trigger | Không có. User nội bộ được tạo lười (lazy) ở request đầu tiên (xem [flows.md](flows.md#f1)) |

Docs: [Cognito pricing](https://aws.amazon.com/cognito/pricing/). Tier Lite/Essentials có free tier hằng tháng không hết hạn.

## 7. Messaging

| Resource | Loại | Producer → Consumer | Cấu hình chính | Phase |
|---|---|---|---|---|
| `jars-jobs` | SQS standard + DLQ | EventBridge Scheduler → jars-api | visibility 5 phút, `maxReceiveCount=5` | 3 |
| `jars-imports` | SQS standard + DLQ | S3 → EventBridge rule → jars-api | visibility 15 phút | 3 |
| `jars-ledger-events.fifo` | **SNS FIFO** | jars-api (outbox) → subscribers | content-based dedup, message group = `userId` | 5 |
| `jars-insights-ledger.fifo` | SQS FIFO + DLQ | SNS ledger → insights-worker | raw message delivery | 5 |
| `jars-insights-events.fifo` | SNS FIFO | insights-worker → subscribers | | 5 |
| `jars-api-notifications.fifo` | SQS FIFO + DLQ | SNS insights → jars-api | | 5 |
| Rule `statement-uploaded` | EventBridge (default bus) | S3 `Object Created` (prefix `uploads/`) → `jars-imports` | bucket bật `eventBridgeEnabled` | 3 |
| Schedule `month-close` | EventBridge Scheduler | → `jars-jobs` | `cron(5 0 1 * ? *)`, timezone **Asia/Ho_Chi_Minh**, flexible window off | 3 |

Chi tiết schema và đảm bảo giao nhận: [event-catalog.md](event-catalog.md). Lý do không dùng Kafka/MSK: [ADR-0009](../04-adr/0009-messaging-sns-sqs-eventbridge.md).

## 8. AI: Bedrock

| Hạng mục | Cấu hình |
|---|---|
| Model chat | `global.anthropic.claude-haiku-5-5` (mặc định); `global.anthropic.claude-sonnet-4-6` (tùy chọn, cho lộ trình phức tạp) |
| Loại endpoint | **Global cross-region inference profile**: request có thể được xử lý ở region khác. Chấp nhận được cho dev; nếu có yêu cầu lưu trữ dữ liệu trong nước thì đây là điểm cần xem lại |
| API | `ConverseStream` qua Spring AI `spring-ai-starter-model-bedrock-converse` |
| Guardrails | Một guardrail: denied topic "khuyến nghị mua/bán chứng khoán cụ thể", lọc PII (số tài khoản, CCCD), content filter mức medium, **prompt attack filter** (chống prompt injection, theo khuyến nghị trong AWS Prescriptive Guidance). Gắn vào request Converse; **Phase 4 cần kiểm tra Spring AI có truyền `guardrailConfig` không**, nếu không thì gọi `ApplyGuardrail` bằng AWS SDK |
| RAG (tùy chọn) | Bedrock Knowledge Base + **S3 Vectors** (GA 12/2025, có ở Singapore) với `cohere.embed-multilingual-v3`, nguồn là các file `kb/*.md` về sản phẩm ngân hàng |
| Quyền IAM | `bedrock:InvokeModel*` trên đúng ARN của inference profile và foundation model; `bedrock:ApplyGuardrail` trên guardrail ARN |
| Kiểm soát chi phí | Rate limit theo user (Bucket4j + Valkey), `max-tokens` giới hạn, metric token in/out qua Micrometer |

## 9. Quant: Lambda `quant-fn`

| Hạng mục | Cấu hình |
|---|---|
| Runtime | Container image `public.ecr.aws/lambda/python:3.13` (arm64; nếu wheel `cvxpy` gặp vấn đề trên arm64 thì chuyển x86_64) |
| Memory / timeout | 1024 MB / 30 s |
| Gọi từ | jars-api qua `LambdaClient.invoke` (IAM); **không** có Function URL hay API Gateway |
| Operations | `quote`, `history`, `optimize` (JSON contract trong `contracts/quant/`, tạo ở Phase 4) |
| Network | Không đặt trong VPC (cần internet để gọi nguồn dữ liệu của vnstock) |
| Rủi ro | Nguồn dữ liệu có thể chặn IP datacenter → làm spike sớm ở Phase 4; kiểm tra license của vnstock (dùng phi thương mại) |

## 10. Cache: ElastiCache Serverless (Valkey), Phase 4

Dùng cho: cache giá cổ phiếu (TTL 60 s), cache dashboard, **rate limit chat**, **pub/sub fan-out thông báo** khi có ≥ 2 task jars-api. Chi phí tối thiểu khoảng $6/tháng (tối thiểu 100 MB dữ liệu được tính phí, theo nguồn bên thứ ba, cần kiểm tra lại). Resource này nằm trong `JarsApp` nên bị xóa cùng app khi teardown. Ở local dùng container `valkey/valkey`.

## 11. Observability

| Tín hiệu | Phase 3 | Phase 6 |
|---|---|---|
| Logs | stdout JSON → CloudWatch Logs (retention 14 ngày) | Thêm `traceId` / `spanId` vào mọi dòng log |
| Metrics | Container Insights cơ bản + metric mặc định của ALB, Aurora, SQS | Micrometer → OTLP → **ADOT collector sidecar** → CloudWatch (EMF). Cẩn thận cardinality vì mỗi custom metric tốn phí |
| Traces | – | OpenTelemetry (`spring-boot-starter-opentelemetry`) → ADOT → **X-Ray** |
| Alarm | Budget alarm | 5xx > 1%, p95 > 1 s, DLQ có message, Aurora ACU > 0 liên tục 6 giờ, `jars-jobs` tồn quá 1 giờ |

## 12. Dịch vụ dùng chung

| Dịch vụ | Dùng làm gì | Ghi chú |
|---|---|---|
| ECR | `jars/jars-api`, `jars/insights-worker`, `jars/quant-fn` | Lifecycle: giữ 10 image gần nhất; bật basic scan on push; **tag immutable** (git SHA không bị ghi đè) |
| S3 (`statements`, `web`) | Upload sao kê, static web | Block Public Access; bucket policy `enforceSSL`; SSE-S3; `statements/uploads/` **tự xóa sau 7 ngày** (lifecycle) |
| Cost Anomaly Detection | Phát hiện chi phí tăng bất thường | Không tính phí; email alert hằng ngày |
| IAM Access Analyzer | Phát hiện resource bị chia sẻ public hoặc cross-account | External access analyzer miễn phí (unused access analyzer **có phí**, không bật) |
| Secrets Manager | DB credentials (CDK sinh ra) | $0.40/secret/tháng. Config không nhạy cảm thì để biến môi trường. Chưa bật rotation tự động vì không có NAT ([runbook](../05-operations/runbook.md#đổi-secret-db)) |
| AWS Budgets | Budget riêng cho project, lọc theo tag `Project=jars` | Cảnh báo ở 50/80/100% |
| CloudTrail | Event history 90 ngày (mặc định, miễn phí) | Không tạo trail riêng ở dev |
| IAM user `phuc-account` + `aws login` | Truy cập của người (console + CLI), thay cho root; bắt buộc MFA; không có access key dài hạn | Không dùng Identity Center: [ADR-0018](../04-adr/0018-human-access-iam-user-aws-login.md) |

## 13. Quy ước đặt tên

- Resource: `jars-<env>-<component>`, ví dụ `jars-dev-api-alb`, `jars-dev-db`.
- SQS/SNS FIFO phải có hậu tố `.fifo`.
- SSM / Secrets: `/jars/dev/<component>/<name>`.
- CloudWatch Logs: `/jars/dev/<service>`.
