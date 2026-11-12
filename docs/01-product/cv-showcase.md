# CV Showcase: hoàn thành thì project làm được gì, và đưa lên CV ra sao

> **Nguyên tắc trung thực:** chỉ đưa lên CV những gì **đã chạy thật**. Mọi con số (`[p95]`, `[số test]`...) phải là **số đo được** (k6, JaCoCo, Cost Explorer). Không ghi số ước lượng. UI xuất phát từ bản hackathon (do Lovable sinh), nên **không nhận là tự viết frontend**. Phần của bạn là backend, hạ tầng và phần nối UI.

## 1. Khi code xong (hết Phase 6), hệ thống làm được gì

### Góc nhìn người dùng: kịch bản demo 3 phút

| # | Người dùng làm | Hệ thống phản hồi | Thứ chứng minh được phía sau |
|---|---|---|---|
| 1 | Mở link demo, đăng ký, đăng nhập | Trang đăng nhập Cognito; vào app thì 6 hũ đã được tạo sẵn | OAuth2/PKCE, resource server, event `UserRegistered` |
| 2 | Trả lời 5 câu onboarding | Hiện risk profile | Validation, xử lý lỗi chuẩn |
| 3 | Nhập lương 15 triệu, bấm "Gợi ý phân bổ" | Gợi ý chia tiền vào 6 hũ, áp dụng bằng một lần bấm | Logic tài chính bằng Java, ghi sổ cái hũ |
| 4 | Ghi vài khoản chi; bấm "Lưu" 2 lần liên tiếp | Chỉ ghi **một** giao dịch; số dư hũ đúng | Idempotency key, optimistic locking |
| 5 | Upload file CSV sao kê | "Đã import 120 giao dịch, 3 dòng lỗi" hiện realtime | S3 presigned → EventBridge → SQS → Spring Batch |
| 6 | Chi đột biến ở hũ PLAY | Cảnh báo "chi tiêu bất thường" **hiện ngay**, không cần tải lại trang | Outbox → SNS/SQS FIFO → insights-worker → SSE |
| 7 | Tạo mục tiêu "Laptop 25 triệu trong 8 tháng" | Biết có khả thi không, mỗi tháng cần để dành bao nhiêu, kịch bản what-if, lộ trình theo giai đoạn | Mô phỏng (Monte Carlo có seed), strategy pattern |
| 8 | Hỏi AI: "Tháng này tôi tiêu lố ở đâu, có kịp mua laptop không?" | Câu trả lời tiếng Việt chạy dần ra (streaming), có số liệu đúng và hiện các tool đã gọi | Spring AI + Bedrock, tool calling, Guardrails |
| 9 | Hỏi "nên mua FPT không?" | Từ chối khuyến nghị và giải thích; kèm dữ liệu giá mang tính giáo dục | Guardrails denied topic, Lambda Python |
| 10 | (Người xem kỹ thuật) mở dashboard CloudWatch / X-Ray | Thấy một trace đi xuyên HTTP → DB → SNS → SQS → worker | OpenTelemetry, observability |

### Góc nhìn kỹ thuật: năng lực hệ thống

- **Modular monolith** Java 21 / Spring Boot 4.1 với 9 module, ranh giới được kiểm tra tự động trong CI, cộng **1 microservice** tách ra theo event và **1 Lambda Python**.
- **Tiền đúng tuyệt đối**: ledger append-only, optimistic locking, idempotency, job đối soát.
- **Event-driven**: transactional outbox, SNS/SQS FIFO có thứ tự theo user, DLQ, idempotent consumer.
- **AI có kiểm soát**: LLM chỉ diễn giải, số liệu do Java tính; guardrails; rate limit; streaming.
- **Hạ tầng 100% bằng code** (CDK Java), CI/CD không dùng access key (OIDC), dựng lại toàn bộ trong khoảng 15 phút.
- **Tối ưu chi phí có chủ đích**: khoảng $1/tháng khi teardown, scale-to-zero, có số liệu chi phí thật.
- **Tài liệu như một team thật**: 17+ ADR, Well-Architected review, runbook, threat model.

## 2. Những điểm "đắt giá" nhất cho CV (xếp theo mức nhà tuyển dụng ngân hàng/fintech VN quan tâm)

| Hạng | Điểm nổi bật | Vì sao đắt | JD liên quan |
|---|---|---|---|
| 1 | Xử lý cập nhật số dư đồng thời (optimistic lock + idempotency + ledger) | Câu hỏi kinh điển của fintech; đa số portfolio sinh viên không có | VPBank, MB, MoMo |
| 2 | Event-driven với outbox + SQS/SNS FIFO + idempotent consumer + DLQ | "Kafka/MQ, xử lý bất đồng bộ" là yêu cầu bắt buộc ở MB | MB, VPBank, Lotte |
| 3 | Spring Security OAuth2 + Cognito + chống IDOR có test | Bảo mật là ưu tiên của ngân hàng | MB (OAuth2/JWT) |
| 4 | Deploy AWS thật bằng CDK + CI/CD OIDC + cost engineering | Có SAA-C03 **cộng** chứng minh làm được thật | VPBank ("AWS là lợi thế"), TPBank |
| 5 | AI advisor bằng Spring AI + Bedrock có guardrails | Điểm cộng AI | MB ("ưu tiên AI"), NAB |
| 6 | Biết khi nào **không** dùng microservices (modular monolith → tách 1 service có lý do) | Thể hiện tư duy kiến trúc, hơn một danh sách buzzword | Middle-level |
| 7 | Observability (trace xuyên queue) + load test có số liệu | Có số đo cụ thể thay vì lời nói | VPBank (ELK/Prometheus/Grafana) |
| 8 | Spring Batch import CSV | Batch là việc hằng ngày ở ngân hàng (đối soát, sao kê) | Ngân hàng nói chung |

## 3. Mẫu mục "Projects" trên CV (tiếng Anh)

> Các ô `[...]` chỉ điền **sau khi đo**. Chọn 4–6 bullet tùy độ dài CV. Thứ tự bullet ưu tiên theo JD đang apply.

**Jars Platform — Personal finance app (6-Jars budgeting, anomaly alerts, AI advisor)** · *Personal project* · `github.com/thphuc06/jar-platform` · `[demo link]`
*Java 21, Spring Boot 4.1, Spring Modulith, Spring Security, JPA/PostgreSQL, Spring AI, AWS (ECS Fargate, Aurora Serverless v2, SNS/SQS, EventBridge, Cognito, Bedrock, Lambda, CloudFront), AWS CDK, GitHub Actions*

- Designed and built a **modular monolith** (9 modules, boundaries verified in CI with Spring Modulith/ArchUnit) and later extracted an **event-driven microservice** over **SNS/SQS FIFO** using the **transactional outbox** pattern, with idempotent consumers and DLQs.
- Implemented a **money ledger** with append-only entries, **optimistic locking** and **idempotency keys**, preventing lost updates and duplicate postings under concurrent requests (covered by concurrency tests).
- Secured the API with **Spring Security OAuth2 Resource Server + Amazon Cognito (PKCE)**, enforced per-user data ownership, and added automated **IDOR tests** for every resource endpoint.
- Built a **streaming AI financial advisor** with **Spring AI + Amazon Bedrock** using tool calling into domain services, so the LLM explains figures computed in Java and never invents numbers. Added Bedrock Guardrails and per-user rate limiting.
- Provisioned all infrastructure with **AWS CDK (Java)**: private ALB behind CloudFront VPC origin, Fargate on Graviton, Aurora Serverless v2 scale-to-zero. **CI/CD via GitHub Actions + OIDC** (no long-lived keys). Kept idle cost at **~$[x]/month**.
- Achieved **p95 [y] ms at [z] req/s** on core endpoints (k6) and **[n]% coverage** on domain/services with JUnit 5, Mockito and Testcontainers. End-to-end traces across HTTP → DB → queue with **OpenTelemetry/X-Ray**.

**Phiên bản 3 dòng** (CV một trang): giữ bullet 1, 2 và một bullet gộp 4 + 5.

## 4. Câu chuyện phỏng vấn (STAR) nên chuẩn bị

| Chủ đề | Situation / Task | Action | Result (điền sau) |
|---|---|---|---|
| Lost update | Hai request cùng trừ một hũ thì số dư sai | `@Version` + retry; test 2 thread; so sánh với `SELECT FOR UPDATE` | Test đồng thời luôn đúng |
| Hóa đơn Aurora | Aurora không chịu pause, chi phí tăng | Tìm ra Hikari giữ kết nối và health check gọi DB; sửa `minimum-idle=0`, readiness không check DB | ACU về 0 sau 5 phút; tiết kiệm `[$]` |
| Mất event | Ghi DB xong thì publish lỗi, gây lệch dữ liệu | Outbox + idempotent consumer | Không mất event khi giả lập lỗi |
| Không dùng Kafka | JD yêu cầu Kafka nhưng ngân sách thấp | SNS/SQS FIFO + bảng ánh xạ khái niệm + lab Kafka local | Hiểu cả hai, giải thích được trade-off |
| LLM bịa số | Model tự tính sai tiền | Tool calling, mọi số do Java tính; eval nhỏ | `[x/y]` câu eval đúng |
| Repo cũ lộ secret | Mật khẩu DB nằm trong repo public | Rotate, purge, gitleaks trong CI, push protection | Không tái diễn |

## 5. Mốc có thể đưa lên CV

| Sau phase | Có thể ghi gì | Tạm chưa ghi |
|---|---|---|
| **3 (~01/2027)** | Bullet 1 (phần monolith), 2, 3, 5 (phần CDK / CI/CD) + link demo | AI, microservice, số p95 |
| **4** | + bullet AI | |
| **5** | + phần microservice / SNS/SQS của bullet 1 | |
| **6** | + số liệu p95, coverage, trace | |

## 6. Trên GitHub cần có gì để recruiter xem trong 60 giây

- README tiếng Anh: 1 câu mô tả, ảnh diagram kiến trúc, GIF demo, link demo, bảng tech stack, mục "Key engineering decisions" trỏ tới ADR.
- Badge CI xanh, badge coverage.
- Pin repo lên profile GitHub; commit thường xuyên với message rõ ràng (Conventional Commits).
- Commit phải đứng tên chính email đã gắn với tài khoản GitHub của bạn (để hiện trên contribution graph).
