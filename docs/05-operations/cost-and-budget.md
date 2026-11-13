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
| ElastiCache Serverless Valkey (ước tính; **không dùng**, rate limit của advisor nằm trong PostgreSQL, [ADR-0023](../04-adr/0023-advisor-guardrail-and-rate-limit.md)) | tối thiểu 100 MB được tính phí | khoảng $6/tháng (nguồn bên thứ ba) |
| CloudWatch custom metric (EMF) | metric-tháng, 10.000 metric đầu (tính theo giờ tồn tại) | 0.30 |
| CloudWatch alarm (standard) | mỗi metric trong alarm-tháng | 0.10 |
| CloudWatch dashboard | dashboard-tháng, **3 dashboard đầu miễn phí** (account chưa có dashboard nào, kiểm ngày 2026-11-12) | 3.00 |
| X-Ray trace lưu trữ | trace, sau **100.000 trace miễn phí** mỗi tháng | 0.000005 |

> 4 dòng trên lấy từ AWS Pricing API ngày **2026-11-12** (Phase 2 WP2), trừ phần free tier của dashboard và X-Ray (theo trang giá CloudWatch/X-Ray).
> **Observability khi `JarsApp` chạy** (WP2): khoảng 6 custom metric nghiệp vụ × 0,30 = 1,8 USD/tháng nếu chạy liên tục, nhưng tính theo giờ tồn tại nên một phiên 1 giờ chỉ khoảng 0,0025 USD; 3 alarm có 4 metric = 0,40 USD/tháng (alarm 5xx dùng 2 metric); dashboard và trace nằm trong free tier. Chọn lọc metric ở collector là cố ý: cấu hình ECS mặc định của ADOT đẩy mọi metric Micrometer (vài trăm metric, khoảng 60–100 USD/tháng nếu chạy liên tục).
| Bedrock Claude | token | Xem [Bedrock pricing](https://aws.amazon.com/bedrock/pricing/); phụ thuộc lượng dùng |

## 3. Ba chế độ vận hành

| Chế độ | Mô tả | Phase 1 (đã deploy) | + advisor, observability (Phase 2) | + insights-worker (Phase 3+, backlog) |
|---|---|---|---|---|
| **RUNNING** | jars-api chạy 24/7; Aurora dùng khoảng 3 giờ/ngày | **≈ $51** | **≈ $55** | **≈ $68** |
| **SLEEP** | `desiredCount=0`; giữ ALB, CloudFront; Aurora pause | ≈ $19 | ≈ $19 | ≈ $19 |
| **TEARDOWN** | `cdk destroy JarsApp`; chỉ còn Data/Auth/Network/Messaging/Foundation | **≈ $1** | ≈ $1 | ≈ $1 |

### Chi tiết RUNNING (Phase 1)

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

Phase 2 cộng thêm: Bedrock khoảng $3–5 (rate limit nằm trong PostgreSQL nên không có Valkey, [ADR-0023](../04-adr/0023-advisor-guardrail-and-rate-limit.md)) và observability (xem ghi chú ở §2; sidecar ADOT dùng chung task nên không tính thêm Fargate đáng kể). Backlog Phase 3+, nếu tách insights-worker: 0.25 vCPU + 0.5 GB ≈ $9.00 + IPv4 $3.65. Các cột sau Phase 1 là ước tính theo đơn giá, chưa đo.

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
| Phase 0–2 (đã xong 13/11/2026): dev local + hai phiên cloud ngắn (12/11 và 13/11, ước lượng ≈ $0,4 tổng cộng) | Chỉ có `JarsAuth` (Cognito) + Bedrock thử nghiệm | ≈ $1–3 |
| Các phiên cloud tiếp theo (kế hoạch ban đầu của "Phase 3", phần deploy đã làm ở Phase 1 WP9) | RUNNING khi đang làm, TEARDOWN khi nghỉ | ≈ $15–25 |
| Phase 3+ (backlog, chưa lên lịch; dự kiến 01–02/2027) | TEARDOWN mặc định; RUNNING vào ngày làm phần cloud hoặc demo | ≈ $30–50 |
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
| Alarm Aurora không pause | `ServerlessDatabaseCapacity` > 0 trong 6 giờ liên tục (mỗi giờ lấy giá trị nhỏ nhất) | `JarsApp` (cùng với dashboard `jars-dev` và alarm 5xx/p95 ở Phase 2 WP2; không có stack `JarsObservability`, [aws-infrastructure §11](../02-architecture/aws-infrastructure.md#11-observability)) |
| CDK test | Không có NAT Gateway; Fargate dùng ARM64; log retention ≤ 14 ngày | `infra/cdk` tests |
| Tag | `Project=jars` bật làm cost allocation tag | Thủ công, 1 lần (Billing → Cost allocation tags) |

## 6. Nhật ký chi phí thực tế

| Tuần (thứ Hai) | Chế độ chủ yếu | Chi Jars (USD) | Credit còn lại | Ghi chú |
|---|---|---|---|---|
| 2026-10-26 | – | 0.00 | 197.02 | Chưa deploy gì |
| 2026-11-09 | TEARDOWN | *(chưa có số Cost Explorer)* | *(chưa có)* | Tuần có lần deploy đầu (12/11, xem dưới). Điền số thật vào đây sau khi Cost Explorer có dữ liệu ngày 12–13/11 |

### Lần chạy `JarsApp` đầu tiên (2026-11-12): ước lượng theo đơn giá niêm yết

`JarsApp` tồn tại khoảng một giờ (tạo 18:32, bắt đầu xóa lúc 19:08 giờ VN, xóa xong mất thêm vài phút); chỉ có một task API chạy từ 18:47. **Đây là ước lượng từ bảng giá ở §2 và số giờ thật, không phải số trên hóa đơn.**

| Thành phần | Cách tính | USD |
|---|---|---|
| ALB internal | 0,0252 × khoảng 0,6 giờ (từ lúc tạo đến lúc bắt đầu xóa) | 0,02 |
| Fargate 0,5 vCPU + 1 GB | (0,5 × 0,04045 + 1 × 0,00442) × khoảng 0,35 giờ | 0,01 |
| IPv4 public của task | 0,005 × khoảng 0,35 giờ | 0,00 |
| Aurora Serverless v2 | khoảng 0,27 ACU-giờ (đọc từ metric `ServerlessDatabaseCapacity`: tối đa 2 ACU lúc app kết nối lần đầu, 0,5 ACU trong 10 phút trước khi pause) × 0,20 | 0,05 |
| CloudFront, S3, Cognito, SQS, EventBridge | free tier / gần 0 | 0,00 |
| **Tổng ước lượng** | | **≈ 0,1** |

Hai điều rút ra: (1) **đã đo:** Aurora tự pause khoảng 6 phút sau connection cuối, nên không tạo ra phần chi phí "ẩn" nào ([bẫy ở §3](#bẫy-chi-phí-ẩn)); (2) **theo bảng giá, chưa đo:** phần đắt nhất của `RUNNING` là ALB và Fargate chạy liên tục (§3), nên `cdk destroy JarsApp` ngay sau khi kiểm xong là cách tiết kiệm đúng chỗ. Các stack nền (`JarsFoundation`, `JarsNetwork`, `JarsAuth`, `JarsData`, `JarsMessaging`) vẫn chạy; chi phí cố định của chúng là Secrets Manager 0,40 USD/tháng, lưu trữ Aurora và ECR vài cent.

### Phiên kiểm chứng Phase 2 (2026-11-13, WP6)

`JarsApp` tồn tại khoảng 1,3 giờ (tạo lúc 05:36 giờ VN ngày 13/11, bắt đầu xóa lúc 06:45), gồm task API với sidecar ADOT, ALB, CloudFront và guardrail Bedrock. Trong phiên đã chạy hai lần smoke test, khoảng 10 lượt chat với Bedrock (Haiku 4.5, khoảng 3.700 token vào mỗi lượt) và một lần k6 với 7.840 request.

| Mốc | Chi Jars theo tag `Project=jars`, tháng 11 tới ngày đó (USD) |
|---|---|
| Trước phiên | 0,18 |
| Sau phiên (đọc lúc 07:00 ngày 13/11 giờ VN) | 0,18: Cost Explorer trễ khoảng một ngày, ngày 13/11 (UTC) chưa có dữ liệu. Điền số thật vào dòng tuần 2026-11-09 ở bảng trên khi có |

Ước lượng theo đơn giá: ALB 0,03 + Fargate 0,03 + Aurora (tối đa 2 ACU trong k6) khoảng 0,1 + Bedrock khoảng 0,1 (khoảng 25 lần gọi model, mỗi lần khoảng 4.000 token vào, Haiku 4.5 giá 1 USD/1 triệu token vào) + guardrail khoảng 0,01 ≈ **0,3 USD**. Chi phí Bedrock không mang tag `Project=jars` vì gọi qua inference profile dùng chung, nên số trong Cost Explorer theo tag thấp hơn chi phí thật một chút.
