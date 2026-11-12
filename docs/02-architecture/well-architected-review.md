# Well-Architected Review (tự đánh giá)

> Đối chiếu thiết kế với **AWS Well-Architected Framework** (6 trụ cột) và hai lens sát với project nhất: **Generative AI Lens** và **Financial Services Industry (FSI) Lens** (FSI Lens đã được cập nhật cho generative AI và agentic AI).
> Mục tiêu: biết rõ **mình đang làm đúng chỗ nào, cố ý lệch chuẩn chỗ nào và vì sao**. Đây là kiểu câu hỏi interviewer thường hỏi người có chứng chỉ SAA.
> Ngày lập: 2026-10-23. Chạy lại bằng **AWS Well-Architected Tool** (miễn phí) ở cuối Phase 3 và cuối Phase 6, sau đó cập nhật cột "Trạng thái".

Ký hiệu: ✅ có trong thiết kế · 🔧 bổ sung sau review này · ⚠️ cố ý lệch chuẩn (có lý do) · ⏳ để phase sau

## 1. Operational Excellence

| Best practice | Áp dụng trong project | Trạng thái | Phase |
|---|---|---|---|
| Hạ tầng là code, thay đổi nhỏ và đảo ngược được | CDK Java; deploy theo git SHA; rollback = deploy SHA cũ | ✅ | 3 |
| CI/CD tự động, có kiểm thử | GitHub Actions: test, cdk-nag, gitleaks; CD qua OIDC | ✅ | 0, 3 |
| Observability: log có cấu trúc, metric, trace | JSON log + `traceId`; OTel → X-Ray/CloudWatch | ✅ | 3, 6 |
| Runbook cho thao tác lặp lại | [runbook.md](../05-operations/runbook.md) | ✅ | – |
| Học từ sự cố | Bảng "Sự cố đã gặp" trong runbook; postmortem ngắn | ✅ | – |
| **Game day / diễn tập lỗi** | Tắt task đang chạy, đẩy message lỗi vào DLQ, chặn quant-fn; đo thời gian phát hiện và phục hồi. (AWS FIS tính phí theo action-phút, có thể dùng thử) | 🔧 | 6 |
| Well-Architected review định kỳ | WA Tool (miễn phí) ở cuối Phase 3 và Phase 6 | 🔧 | 3, 6 |

## 2. Security

| Best practice | Áp dụng | Trạng thái | Phase |
|---|---|---|---|
| Bảo vệ root: MFA, không access key, không dùng hằng ngày | Root đã có MFA và không có access key ✅; ⚠️ **CLI vẫn đang dùng root** (ADR-0018 hoãn, ghi nhận 2026-11-12); phải chuyển sang IAM user trước khi bật CI/CD deploy | ⚠️ | 0 |
| Danh tính tập trung, credential tạm thời | ⚠️ **Không dùng Identity Center** (account standalone; Organizations làm mất credit). Thay bằng IAM user có MFA + `aws login` (credential tạm thời) cho người; OIDC (CI); task role (app) ([ADR-0018](../04-adr/0018-human-access-iam-user-aws-login.md)) | ⚠️ / 🔧 | 0 |
| Least privilege | Role tách riêng, resource ARN cụ thể ([security.md §3](security.md#3-iam)); cdk-nag | ✅ | 3 |
| **Phát hiện truy cập ngoài ý muốn** | **IAM Access Analyzer – external access analyzer** (miễn phí): báo resource bị chia sẻ public hoặc cross-account | 🔧 | 0 |
| **Chặn S3 public ở cấp account** | Bật **S3 Block Public Access (account-level)** | 🔧 | 0 |
| Mã hóa at rest | Aurora (KMS mặc định), S3 SSE-S3, SQS SSE, EBS default encryption (cho EC2 nếu có) | ✅ / 🔧 | 0, 3 |
| Mã hóa in transit | CloudFront HTTPS; `rds.force_ssl=1`; Valkey TLS; S3 bucket policy **chặn non-TLS** (`enforceSSL`) | ✅ / 🔧 | 3 |
| Bảo vệ hạ tầng nhiều lớp | ALB internal + VPC origin; SG theo chuỗi ALB → app → DB | ✅ | 3 |
| Bảo mật container | Image non-root; `readonlyRootFilesystem=true` (mount volume `/tmp` cho Tomcat); ECR **immutable tags** + scan on push; không đưa secret vào image | 🔧 | 3 |
| Phát hiện mối đe dọa | CloudTrail event history (90 ngày, miễn phí). **GuardDuty: dùng thử 30 ngày** (mỗi account mỗi region) ở Phase 3, rồi tắt nếu không đủ ngân sách. Security Hub / Config: không bật (tốn phí) | ⚠️ | 3 |
| Bảo vệ dữ liệu theo vòng đời | File CSV sao kê là dữ liệu nhạy cảm → **S3 lifecycle xóa `uploads/` sau 7 ngày** (data minimization) | 🔧 | 3 |
| Chuẩn bị ứng phó sự cố | Runbook đổi secret; có sẵn quy trình xử lý repo cũ bị lộ secret ([security.md §8](security.md#8-sự-cố-của-repo-cũ-hackathon-và-việc-cần-làm)) | ✅ | 0 |

## 3. Reliability

| Best practice | Áp dụng | Trạng thái | Phase |
|---|---|---|---|
| Multi-AZ | VPC 2 AZ; ALB 2 AZ; Aurora subnet group 2 AZ. **Chỉ 1 task, 1 writer** ở dev | ⚠️ (chi phí) | 3 |
| Tự phục hồi | ECS thay task lỗi; deployment circuit breaker rollback | ✅ | 3 |
| **Graceful shutdown + connection draining** | Spring `server.shutdown=graceful`; ECS `stopTimeout` ≥ thời gian shutdown của app; target group **deregistration delay khoảng 30 s** (mặc định 300 s là quá lâu); client SSE tự reconnect với `Last-Event-ID` vì ALB cắt kết nối dài khi hết thời gian draining | 🔧 | 3 |
| **Health check hợp với JVM** | `healthCheckGracePeriod` khoảng 60–90 s cho JVM khởi động; readiness tách khỏi liveness; không check DB (giữ cho Aurora pause được) | 🔧 | 3 |
| Timeout, retry có jitter, circuit breaker | AWS SDK retry mặc định có jitter; Resilience4j cho quant-fn; timeout Bedrock | ✅ | 4 |
| Tách rời bằng queue, có DLQ | SQS + DLQ + alarm; consumer idempotent | ✅ | 3, 5 |
| Visibility timeout > thời gian xử lý | `jars-imports` 15 phút (batch), `jars-jobs` 5 phút | ✅ | 3 |
| Backup & khôi phục | Aurora automated backup + snapshot khi xóa stack; **thử restore 1 lần** để chắc chắn backup dùng được | 🔧 | 6 |
| Giới hạn tài nguyên (quota) | Kiểm tra quota Fargate vCPU, Bedrock TPM ở Singapore trước demo | 🔧 | 4 |

## 4. Performance Efficiency

| Best practice | Áp dụng | Trạng thái |
|---|---|---|
| Chọn compute phù hợp | Fargate **Graviton (ARM64)**; Lambda cho quant (tải không đều) | ✅ |
| JVM trong container | `MaxRAMPercentage=75`; virtual threads; (tùy chọn) CDS/AOT cache để khởi động nhanh hơn | ✅ / ⏳ |
| Cache gần người dùng | CloudFront cho static; Valkey cho dữ liệu đọc nhiều | ✅ |
| Đo trước khi tối ưu | Load test k6, ghi p95 vào README | ⏳ 6 |
| Đúng cấu trúc dữ liệu | Index cho keyset pagination; read model cho dashboard | ✅ |

## 5. Cost Optimization

| Best practice | Áp dụng | Trạng thái |
|---|---|---|
| Đo lường và phân bổ chi phí | Tag `Project=jars` + cost allocation tag; nhật ký chi phí hằng tuần | ✅ |
| Budget + cảnh báo | Budget theo tag ($30) | ✅ |
| **Phát hiện chi phí bất thường** | **AWS Cost Anomaly Detection** (không tính phí; email alert hằng ngày) | 🔧 0 |
| Trả theo mức dùng | Aurora scale-to-zero, Lambda, SQS; chế độ TEARDOWN mặc định | ✅ |
| Kiến trúc tránh phí cố định | Không NAT, không MSK, không EKS ([ADR-0013](../04-adr/0013-networking-no-nat.md), [ADR-0009](../04-adr/0009-messaging-sns-sqs-eventbridge.md)) | ✅ |
| Log retention có hạn | 14 ngày | ✅ |
| Kiểm tra tài nguyên bỏ quên | Dọn hằng tháng (runbook); **Trusted Advisor core checks** (miễn phí) | ✅ |

## 6. Sustainability

| Best practice | Áp dụng |
|---|---|
| Region gần người dùng | ap-southeast-1 ✅ |
| Phần cứng hiệu quả năng lượng | Graviton ✅ |
| Không chạy tài nguyên nhàn rỗi | Scale-to-zero, TEARDOWN ✅ |
| Giảm dữ liệu thừa | Lifecycle xóa CSV, giữ 10 image ECR, log 14 ngày ✅ |

## 7. Generative AI Lens (áp dụng cho `advisor`)

| Best practice | Áp dụng | Trạng thái |
|---|---|---|
| Phòng thủ prompt injection nhiều lớp | Guardrails **bật prompt attack filter** + system prompt cố định + tool read-only scope theo user + kiểm tra input ở app | 🔧 4 |
| Guardrails cho mọi lời gọi model | Một guardrail dùng chung cho mọi request Converse | ✅ 4 |
| Kiểm soát chi phí inference | Rate limit, `max-tokens`, model nhỏ mặc định (Haiku), giới hạn số vòng tool-call (điều kiện dừng của agent), (tùy chọn) prompt caching | ✅ / 🔧 4 |
| Ghi log và giám sát lời gọi model | Ghi metadata (model, token, latency, tool đã gọi, guardrail action) qua Micrometer/log. **Không bật Bedrock model invocation logging toàn bộ prompt** vì prompt chứa dữ liệu tài chính cá nhân; nếu bật để debug thì chỉ ở dev, bucket có lifecycle ngắn | 🔧 4 |
| Đánh giá chất lượng | Bộ eval nhỏ (20–30 câu hỏi mẫu): đúng tool, không bịa số, có disclaimer; chạy trong CI ở chế độ thủ công | ⏳ 4 |
| Minh bạch với người dùng | Disclaimer "tham khảo, không phải khuyến nghị đầu tư" ở mọi câu trả lời liên quan đầu tư | ✅ 4 |

## 8. Financial Services Industry Lens (những điểm áp dụng được)

| Chủ đề FSI | Áp dụng thực tế trong project |
|---|---|
| Tính đúng đắn và truy vết giao dịch | Ledger append-only, `audit_log`, `traceId` xuyên suốt, job đối soát số dư |
| Bảo vệ dữ liệu khách hàng | Ownership check (chống IDOR), mask PII trong log, xóa sao kê sau 7 ngày, mã hóa at rest và in transit |
| Nội địa hóa / lưu trú dữ liệu | Dữ liệu nghiệp vụ ở Singapore; **ghi nhận rủi ro**: global inference profile của Bedrock có thể xử lý prompt ngoài region ([ADR-0005](../04-adr/0005-region-ap-southeast-1.md)) |
| Khả năng phục hồi (operational resilience) | ⚠️ Dev single-AZ task. Ghi rõ "prod sẽ cần ≥ 2 task, Aurora reader ở AZ khác, NAT mỗi AZ" |
| Quản trị AI | Guardrails, giới hạn phạm vi (không tư vấn đầu tư cá nhân hóa), log metadata để kiểm tra lại |

## 9. Tổng hợp các điểm cố ý lệch chuẩn

| Lệch chuẩn | Chuẩn khuyến nghị | Lý do | Sẽ làm gì nếu là production |
|---|---|---|---|
| Một AWS account, chung với project khác | Multi-account (AWS Organizations: dev / prod / security / log) | **Tham gia Organizations làm credit Free Tier hết hạn ngay**; ngân sách $197 | Organizations + Control Tower, account riêng cho prod |
| IAM user + MFA + `aws login` cho người | IAM Identity Center | Account instance của Identity Center không hỗ trợ permission set; organization instance thì cần Organizations ([ADR-0018](../04-adr/0018-human-access-iam-user-aws-login.md)) | Identity Center (organization instance), không có IAM user nào |
| Không NAT, task có IP public | Task ở private subnet | Tiết kiệm khoảng $43/tháng | NAT mỗi AZ + VPC endpoint |
| 1 task, 1 Aurora writer | ≥ 2 task, có reader ở AZ khác | Chi phí | Auto scaling ≥ 2, Aurora multi-AZ |
| Không có Security Hub / Config / GuardDuty lâu dài | Bật toàn bộ, tổng hợp findings | Chi phí | Bật ở mọi account qua delegated admin |
| CloudFront → ALB đi HTTP (trong mạng AWS) | TLS end-to-end | Không có domain riêng | Domain + ACM trên ALB |
| Chưa bật rotation tự động cho secret DB | Rotation tự động | Lambda rotation cần NAT hoặc endpoint | Bật rotation (hoặc IAM DB auth) |
| Bedrock global inference profile | Profile theo vùng / in-region | Model mới ở Singapore chỉ có qua global | Đánh giá theo quy định dữ liệu; chọn profile theo vùng nếu có |
