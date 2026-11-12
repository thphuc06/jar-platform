# Chi phí & Ngân sách

> Giá lấy từ **AWS Pricing API, region ap-southeast-1, ngày 2026-10-23**, trừ các dòng ghi "(ước tính)". 1 tháng = 730 giờ. Mọi số đều **trước khi trừ credit**. Kiểm tra lại bằng [AWS Pricing Calculator](https://calculator.aws/) trước khi bật dịch vụ mới.

## 1. Bối cảnh tài khoản (kiểm tra ngày 2026-10-23)

| Hạng mục | Giá trị |
|---|---|
| Tài khoản | `626212071763` (`phuclearning2006`), tạo ngày 2026-04-09, gói **PAID** |
| Credit còn lại | **$197.02** (`aws freetier get-account-plan-state`) |
| Credit hết hạn | Khoảng **2027-04-09** (credit Free Tier hết hạn 12 tháng sau ngày tạo tài khoản; xem chính xác ở Billing → Credits) |
| Free tier RDS/EC2 750 giờ kiểu cũ | **Không có** (chỉ áp dụng cho tài khoản tạo trước 15/07/2025) |
| Always-free vẫn có | Lambda (1M request + 400k GB-s/tháng), SQS/SNS (1M request), Cognito Lite/Essentials (free tier hằng tháng), CloudFront (theo free tier hiện hành) |
| Tài nguyên **không thuộc project** cũng đang trừ vào credit | EC2 `sidewalk-labelstudio` t3a.medium (ap-southeast-1) + EBS gp3 30 GB + 1 public IPv4: khoảng **$41/tháng**; các stack `FairTable*` (us-east-1, chủ yếu serverless) |
| Budget hiện có | `My Monthly Cost Budget` $10, `fairtable-cap-20usd` $20 |

> ⚠️ **Cảnh báo:** nếu `sidewalk-labelstudio` chạy liên tục tới 04/2027 (khoảng 5.5 tháng × $41 ≈ $225) thì **riêng nó đã vượt toàn bộ credit**. **Đã stop ngày 2026-10-23.** Khi stop vẫn còn tính EBS gp3 30 GB ($2.88) + Elastic IP ($3.65) ≈ **$6.5/tháng**. Release EIP nếu không cần giữ IP.

## 2. Bảng đơn giá dùng trong project

| Dịch vụ | Đơn vị | Giá (USD) |
|---|---|---|
| Fargate ARM vCPU | vCPU-giờ | 0.04045 |
| Fargate ARM memory | GB-giờ | 0.00442 |
| Application Load Balancer | giờ | 0.0252 |
| ALB LCU | LCU-giờ | 0.008 |
| Public IPv4 (đang dùng) | giờ | 0.005 |
| NAT Gateway (**không dùng**) | giờ | 0.059 |
| VPC interface endpoint (**không dùng**) | giờ / AZ | 0.013 |
| Aurora Serverless v2 (PostgreSQL) | ACU-giờ | 0.20 |
| Aurora storage | GB-tháng | 0.11 |
| RDS PostgreSQL db.t4g.micro (dự phòng) | giờ | 0.025 |
| EC2 t4g.nano (fck-nat, tùy chọn) | giờ | 0.0053 |
| Secrets Manager | secret-tháng | 0.40 |
| ECR storage | GB-tháng | 0.10 |
| CloudWatch Logs ingest (Standard) | GB | 0.70 |
| EBS gp3 | GB-tháng | 0.096 |
| ElastiCache Serverless Valkey (ước tính) | tối thiểu 100 MB được tính phí | khoảng $6/tháng (nguồn bên thứ ba) |
| Bedrock Claude | token | Xem [Bedrock pricing](https://aws.amazon.com/bedrock/pricing/); phụ thuộc lượng dùng |

## 3. Ba chế độ vận hành

| Chế độ | Mô tả | Phase 3 | Phase 4 | Phase 5 |
|---|---|---|---|---|
| **RUNNING** | jars-api chạy 24/7; Aurora dùng khoảng 3 giờ/ngày | **≈ $51** | **≈ $61** | **≈ $74** |
| **SLEEP** | `desiredCount=0`; giữ ALB, CloudFront, Valkey; Aurora pause | ≈ $19 | ≈ $25 | ≈ $25 |
| **TEARDOWN** | `cdk destroy JarsApp`; chỉ còn Data/Auth/Network/Messaging/Foundation | **≈ $1** | ≈ $1 | ≈ $1 |

### Chi tiết RUNNING (Phase 3)

| Thành phần | Tính | USD/tháng |
|---|---|---|
| jars-api Fargate 0.5 vCPU + 1 GB | (0.5×0.04045 + 1×0.00442) × 730 | 17.99 |
| IPv4 public của task | 0.005 × 730 | 3.65 |
| ALB internal | 0.0252 × 730 | 18.40 |
| ALB LCU (traffic thấp, khoảng 0.1 LCU trung bình) | 0.008 × 0.1 × 730 | 0.58 |
| Aurora 0.5 ACU × 3 giờ/ngày | 0.5 × 0.20 × 90 | 9.00 |
| Aurora storage 1 GB | | 0.11 |
| Secrets Manager (1 secret) | | 0.40 |
| CloudWatch Logs (khoảng 0.5 GB) | | 0.35 |
| ECR (khoảng 1 GB) | | 0.10 |
| S3, CloudFront, Cognito, SQS, EventBridge, Scheduler | free tier / gần $0 | ~0 |
| **Tổng** | | **≈ 50.6** |

Phase 4 cộng thêm: Valkey khoảng $6 + Bedrock khoảng $3–5 (có rate limit). Phase 5 cộng thêm: insights-worker (0.25 vCPU + 0.5 GB) ≈ $9.00 + IPv4 $3.65.

### Bẫy chi phí "ẩn"

| Bẫy | Hậu quả | Phòng tránh |
|---|---|---|
| Aurora không pause (pool giữ kết nối, health check gọi DB, DBeaver mở quên tắt) | 0.5 ACU × 730 × $0.20 = **$73/tháng** | Hikari `minimum-idle=0`; readiness không có `db`; alarm ACU > 0 liên tục 6 giờ |
| Quên NAT Gateway khi copy ví dụ CDK (`natGateways` mặc định = số AZ!) | **$43–86/tháng** | Đặt rõ `natGateways(0)` trong `JarsNetwork`; viết CDK test assert không có `AWS::EC2::NatGateway` |
| Log quá nhiều (DEBUG ở môi trường cloud) | $0.70/GB | Mặc định level INFO; retention 14 ngày |
| Custom metric có cardinality cao (tag `userId`) | $0.30/metric/tháng × số user | Không gắn ID vào tag metric |
| Bedrock bị spam | Không giới hạn trên | Rate limit + `max-tokens` + budget alarm |
| Elastic IP không gắn vào gì | Vẫn tính phí IPv4 | Không dùng EIP |
| Snapshot Aurora tồn đọng sau nhiều lần destroy | Phí lưu trữ snapshot | Dọn snapshot cũ mỗi tháng (runbook) |

## 4. Kế hoạch dùng credit đến 2027-04-09

| Giai đoạn (ước lượng) | Chế độ mặc định | Dự kiến chi |
|---|---|---|
| Phase 0–2 (26/10 – 20/12/2026): dev local | Chỉ có `JarsAuth` (Cognito) + Bedrock thử nghiệm | ≈ $1–3 |
| Phase 3 (21/12/2026 – 03/01/2027) | RUNNING khi đang làm, TEARDOWN khi nghỉ | ≈ $15–25 |
| Phase 4–6 (01–02/2027) | TEARDOWN mặc định; RUNNING vào ngày làm phần cloud hoặc demo | ≈ $30–50 |
| **Tổng dự kiến cho Jars** | | **≈ $50–80** |

**Quy tắc vận hành:**
1. **Mặc định là TEARDOWN.** Chỉ `cdk deploy JarsApp` khi cần làm phần cloud hoặc demo (khoảng 10–15 phút, phần lớn do CloudFront).
2. Trước khi đi phỏng vấn: deploy trước 1 ngày, kiểm tra luồng demo, sau đó teardown.
3. Mỗi thứ Hai: mở Cost Explorer, lọc tag `Project=jars`, ghi số liệu vào mục 6 bên dưới.

## 5. Guardrail tự động

| Guardrail | Cấu hình | Stack |
|---|---|---|
| Budget riêng cho project | $30/tháng, lọc `Project=jars`; cảnh báo ở 50/80/100% thực chi và 100% dự báo, gửi email | `JarsFoundation` |
| Cost Anomaly Detection | Monitor theo AWS service, email tổng hợp hằng ngày. Dịch vụ không tính phí (chỉ tính phí SNS nếu dùng alert tức thời) | Account baseline (thủ công, Phase 0) |
| Budget tổng tài khoản | Giữ budget $10 hiện có hoặc nâng lên $60 (vì đã có project khác) | Thủ công |
| Alarm Aurora không pause | `ServerlessDatabaseCapacity` > 0 trong 6 giờ liên tục (mỗi giờ lấy giá trị nhỏ nhất) | `JarsApp` (tạo sớm ở Phase 3; chuyển sang `JarsObservability` ở Phase 6) |
| CDK test | Không có NAT Gateway; Fargate dùng ARM64; log retention ≤ 14 ngày | `infra/cdk` tests |
| Tag | `Project=jars` bật làm cost allocation tag | Thủ công, 1 lần (Billing → Cost allocation tags) |

## 6. Nhật ký chi phí thực tế

| Tuần (thứ Hai) | Chế độ chủ yếu | Chi Jars (USD) | Credit còn lại | Ghi chú |
|---|---|---|---|---|
| 2026-10-26 | – | 0.00 | 197.02 | Chưa deploy gì |
