# CV Showcase: hoàn thành thì project làm được gì, và đưa lên CV ra sao

> **Nguyên tắc trung thực:** chỉ đưa lên CV những gì **đã chạy thật**. Mọi con số (`[p95]`, `[số test]`...) phải là **số đo được** (k6, JaCoCo, Cost Explorer). Không ghi số ước lượng. UI xuất phát từ bản hackathon (do Lovable sinh), nên **không nhận là tự viết frontend**. Phần của bạn là backend, hạ tầng và phần nối UI.

## 0. Trạng thái thật sau Phase 1 (2026-11-12): đã đo, đã chạy, ghi được lên CV ngay

Mọi số dưới đây lấy từ một lần chạy thật, không phải ước lượng. Phần 1 trở xuống là **đích đến theo thiết kế ban đầu** (hết Phase 6 cũ), không phải hiện trạng: phần đã làm xong nằm ở §0 và mục "Thêm sau Phase 2"; tách `insights-worker` (SNS/SQS FIFO) là backlog Phase 3+ và phần cổ phiếu đã bỏ ([ADR-0024](../04-adr/0024-drop-market-and-quant-fn.md)).

| Hạng mục | Số đo | Lấy từ đâu |
|---|---|---|
| Test backend | **738 test, 0 lỗi** (đo lại 13/11/2026) | `./mvnw verify`, surefire |
| Coverage dòng (JaCoCo) | **94,8%** toàn backend; build ép tối thiểu 80% trên `internal/domain` và `internal/service` | JaCoCo |
| Test hạ tầng | **118 test** (không NAT Gateway, task ARM64, không ingress `0.0.0.0/0`, log retention, Aurora `min = 0`) + cdk-nag | `infra/cdk`, `./mvnw test` |
| Test web | **134 test** trong 19 file | `npm test` (Vitest) |
| Đồng thời | 12 writer cùng ghi một hũ: mọi khoản chi được nhận đều có trong số dư, `balance == SUM(entries)`; 8 request trùng `Idempotency-Key` chỉ ghi một lần. Tìm ra và sửa một **deadlock** của chuyển hũ hai chiều | `LedgerPostingTest` |
| Smoke test trên AWS | **11/11 đạt** qua CloudFront, token Cognito thật: idempotency, user khác nhận 404, anomaly → notification → SSE, ALB internal | `scripts/smoke-test.ps1` |
| Khởi động trên Fargate | `Started JarsApplication in 53,3 s` (0,5 vCPU, Aurora vừa resume từ paused) | CloudWatch Logs |
| Dựng cả hệ thống | `JarsApp` lần đầu 18 phút (CloudFront VPC origin 9 phút là phần chậm nhất) + 4 phút đẩy web | CloudFormation events |
| Aurora Serverless v2 | scale-to-zero: 0 ACU khi không dùng, lên tối đa 2 ACU khi app kết nối lần đầu | CloudWatch `ServerlessDatabaseCapacity` |
| Tài liệu | 25 ADR, trong đó có những quyết định phải đổi vì thực tế khác thiết kế (Java 21 thay 25, không dùng Identity Center, CloudFront Function cho SPA fallback) | `docs/04-adr` |

**Bullet CV dùng được ngay** (tiếng Anh, chỉ gồm điều đã chạy thật):

- Built a **modular monolith** (8 modules, boundaries verified on every build with Spring Modulith and ArchUnit) on Java 21 / Spring Boot 4 for a 6-jars personal-finance app: append-only money ledger, budgeting with a once-only month close, savings goals with seeded Monte Carlo what-ifs.
- Made money postings safe under concurrency: **optimistic locking** with a retry loop outside the transaction, **idempotency keys** inserted inside the business transaction, and a reproduced-then-fixed PostgreSQL **deadlock** on two-way jar transfers. 738 tests (94.8% line coverage), including race tests with 8–12 parallel writers.
- Implemented per-jar **spending anomaly detection** (robust MAD outlier test plus CUSUM drift, checked against golden vectors from the original Python prototype), delivered through the **Modulith transactional outbox** to idempotent consumers and pushed to the browser over **Server-Sent Events**.
- Secured the API as a stateless **OAuth2 resource server for Amazon Cognito** (access tokens from our own app clients only, fail-closed validators), with every query scoped to the caller and IDOR tests for each resource endpoint.
- Provisioned everything with **AWS CDK (Java)**, six stacks, 118 guardrail tests and cdk-nag: Fargate on Graviton, internal ALB behind a CloudFront VPC origin, Aurora Serverless v2 scaling to zero, no NAT gateway. Verified a real deployment end to end with a smoke test (11/11 checks) and tore the compute stack down afterwards.

**Chưa được ghi (ghi chú ở thời điểm Phase 1, đã được mục "Thêm sau Phase 2" bên dưới thay thế: AI advisor, import CSV với Spring Batch, k6/p95 và `deploy-dev.yml` đều đã có):** microservice / SNS-SQS FIFO, chi phí thật theo Cost Explorer (số hiện có là ước lượng theo đơn giá niêm yết), link demo công khai (`JarsApp` đã bị `cdk destroy`; dựng lại khoảng 25 phút), đăng nhập bằng trình duyệt qua managed login (mới kiểm bằng token lấy từ admin API, chưa thử tay).

### Thêm sau Phase 2 (2026-11-13): đã đo trên AWS

| Hạng mục | Số đo | Lấy từ đâu |
|---|---|---|
| Test | **738 test backend** (coverage dòng 94,8%), **118 test CDK**, **134 test web** | `./mvnw verify`, JaCoCo, Vitest |
| Smoke test trên AWS | **14/14 đạt** qua CloudFront. Thêm 3 bước so với Phase 1: import sao kê (presigned PUT → S3 → EventBridge → SQS → Spring Batch, `COMPLETED`), chat có gọi tool stream qua SSE, guardrail chặn prompt attack | `scripts/smoke-test.ps1` |
| Tải (k6, 20 VU, 2 phút 45 giây, qua CloudFront) | **p95 đọc 117 ms, p95 ghi 200 ms, p99 toàn bộ 202 ms, 0% lỗi** trên 7.840 request (47 req/s) với 1 task Fargate 0,5 vCPU | `scripts/load-test.ps1` |
| AI advisor (Bedrock Haiku 4.5) | Byte đầu tiên sau 1,3–2,1 giây; trả lời bằng số lấy từ tool; guardrail từ chối "nên mua FPT không?" trong 0,4 giây, trước khi gọi model | `probe-advisor`, smoke test |
| Observability | Trace trong X-Ray có các node `jars-api`, SQS và `spring_ai chat_client`; 4 metric nghiệp vụ (`jars.transactions.posted`, `jars.anomalies.detected`, `jars.advisor.messages`, `jars.advisor.tokens`) trong namespace `Jars/Api` qua EMF | X-Ray service graph, CloudWatch |
| CD không dùng access key | `deploy-dev.yml` qua GitHub OIDC: build image ARM64 → ECR → `cdk deploy JarsApp` → smoke 14/14, 28 phút từ đầu tới cuối; `destroy` một nút bấm | GitHub Actions run |
| Đăng nhập trình duyệt | Cognito managed login, Authorization Code + PKCE `S256`, kiểm bằng Playwright | `login.mjs` (scratch) |
| Lỗi chỉ lộ ra trên AWS | 3 lỗi: SSE ghi thêm một body `401` vào cuối stream, guardrail coi mã hũ `NEC` là mã cổ phiếu, Flyway không chờ Aurora resume. Đã sửa, có test cho cả 3 | [runbook](../05-operations/runbook.md#sự-cố-đã-gặp) |

**Bullet CV thêm** (tiếng Anh):

- Added an **AI financial advisor** on Amazon Bedrock (Spring AI, Claude Haiku 4.5) that answers only from **read-only tools** scoped to the caller's JWT, streams tokens over SSE, checks every input and answer with a **Bedrock Guardrail** (denied topic for buy/sell advice, PII, prompt attack) and limits each user's messages per hour.
- Built **CSV bank-statement import** with **Spring Batch** (chunked, skip-with-reason, restartable, idempotent per row): presigned S3 upload → EventBridge → SQS → batch job → ledger, with a live notification when the import finishes.
- Instrumented the service with **OpenTelemetry** (ADOT sidecar → X-Ray and CloudWatch EMF), a dashboard and alarms; load-tested through CloudFront with k6 at **p95 117 ms (reads) / 200 ms (writes), 0% errors** on a single 0.5 vCPU Fargate task.

**Vẫn chưa được ghi:** tách microservice, chi phí theo hóa đơn tháng (Cost Explorer chưa có đủ dữ liệu).

## 1. Đích đến theo thiết kế ban đầu (hết Phase 6 cũ), hệ thống làm được gì

### Góc nhìn người dùng: kịch bản demo 3 phút

| # | Người dùng làm | Hệ thống phản hồi | Thứ chứng minh được phía sau |
|---|---|---|---|
| 1 | Mở link demo, đăng ký, đăng nhập | Trang đăng nhập Cognito; vào app thì 6 hũ đã được tạo sẵn | OAuth2/PKCE, resource server, event `UserRegistered` |
| 2 | Trả lời 5 câu onboarding | Hiện risk profile | Validation, xử lý lỗi chuẩn |
| 3 | Nhập lương 15 triệu, bấm "Gợi ý phân bổ" | Gợi ý chia tiền vào 6 hũ, áp dụng bằng một lần bấm | Logic tài chính bằng Java, ghi sổ cái hũ |
| 4 | Ghi vài khoản chi; bấm "Lưu" 2 lần liên tiếp | Chỉ ghi **một** giao dịch; số dư hũ đúng | Idempotency key, optimistic locking |
| 5 | Upload file CSV sao kê | "Đã import 120 giao dịch, 3 dòng lỗi" hiện realtime | S3 presigned → EventBridge → SQS → Spring Batch |
| 6 | Chi đột biến ở hũ PLAY | Cảnh báo "chi tiêu bất thường" **hiện ngay**, không cần tải lại trang | Outbox Modulith (in-process) → module `insights` → `notification` → SSE. Qua SNS/SQS FIFO + `insights-worker` là backlog Phase 3+, chưa làm |
| 7 | Tạo mục tiêu "Laptop 25 triệu trong 8 tháng" | Biết có khả thi không, mỗi tháng cần để dành bao nhiêu, kịch bản what-if, lộ trình theo giai đoạn | Mô phỏng (Monte Carlo có seed), strategy pattern |
| 8 | Hỏi AI: "Tháng này tôi tiêu lố ở đâu, có kịp mua laptop không?" | Câu trả lời tiếng Việt chạy dần ra (streaming), có số liệu đúng và hiện các tool đã gọi | Spring AI + Bedrock, tool calling, Guardrails |
| 9 | Hỏi "nên mua FPT không?" | Từ chối khuyến nghị và giải thích, gợi ý hỏi về hũ và mục tiêu | Guardrails denied topic (đã chạy thật, xem §0) |
| 10 | (Người xem kỹ thuật) mở dashboard CloudWatch / X-Ray | Thấy trace `jars-api` (HTTP → DB, listener SQS, lời gọi Spring AI) trong X-Ray; trace xuyên request → SQS → consumer chưa làm (Phase 3+) | OpenTelemetry, observability |

### Góc nhìn kỹ thuật: năng lực hệ thống

- **Modular monolith** Java 21 / Spring Boot 4.1 với 8 module nghiệp vụ, ranh giới được kiểm tra tự động trong CI. Tách **1 microservice** (`insights-worker`) theo event là backlog Phase 3+, chưa làm.
- **Tiền đúng tuyệt đối**: ledger append-only, optimistic locking, idempotency, job đối soát.
- **Event-driven**: transactional outbox, idempotent consumer, SQS + DLQ (SNS/SQS FIFO có thứ tự theo user: backlog Phase 3+).
- **AI có kiểm soát**: LLM chỉ diễn giải, số liệu do Java tính; guardrails; rate limit; streaming.
- **Hạ tầng 100% bằng code** (CDK Java), CI/CD không dùng access key (OIDC), dựng lại toàn bộ trong khoảng 15 phút.
- **Tối ưu chi phí có chủ đích**: khoảng $1/tháng khi teardown, scale-to-zero, có số liệu chi phí thật.
- **Tài liệu như một team thật**: 25 ADR, Well-Architected review, runbook, threat model.

## 2. Những điểm "đắt giá" nhất cho CV (xếp theo mức nhà tuyển dụng ngân hàng/fintech VN quan tâm)

| Hạng | Điểm nổi bật | Vì sao đắt | JD liên quan |
|---|---|---|---|
| 1 | Xử lý cập nhật số dư đồng thời (optimistic lock + idempotency + ledger) | Câu hỏi kinh điển của fintech; đa số portfolio sinh viên không có | VPBank, MB, MoMo |
| 2 | Event-driven với outbox + idempotent consumer + SQS + DLQ (SNS/SQS FIFO: backlog Phase 3+) | "Kafka/MQ, xử lý bất đồng bộ" là yêu cầu bắt buộc ở MB | MB, VPBank, Lotte |
| 3 | Spring Security OAuth2 + Cognito + chống IDOR có test | Bảo mật là ưu tiên của ngân hàng | MB (OAuth2/JWT) |
| 4 | Deploy AWS thật bằng CDK + CI/CD OIDC + cost engineering | Có SAA-C03 **cộng** chứng minh làm được thật | VPBank ("AWS là lợi thế"), TPBank |
| 5 | AI advisor bằng Spring AI + Bedrock có guardrails | Điểm cộng AI | MB ("ưu tiên AI"), NAB |
| 6 | Biết khi nào **không** dùng microservices (modular monolith, ranh giới kiểm tra được; tách 1 service khi có lý do: backlog Phase 3+) | Thể hiện tư duy kiến trúc, hơn một danh sách buzzword | Middle-level |
| 7 | Observability (OpenTelemetry → X-Ray; trace xuyên queue là việc chưa làm) + load test có số liệu | Có số đo cụ thể thay vì lời nói | VPBank (ELK/Prometheus/Grafana) |
| 8 | Spring Batch import CSV | Batch là việc hằng ngày ở ngân hàng (đối soát, sao kê) | Ngân hàng nói chung |

## 3. Mẫu mục "Projects" trên CV (tiếng Anh)

> Mẫu này viết cho đích đến ban đầu: **bỏ** bullet và từ khóa về microservice / SNS-SQS FIFO (backlog Phase 3+) và Lambda (`quant-fn` đã bỏ) cho tới khi chúng thật sự có. Các ô `[...]` chỉ điền **sau khi đo**. Chọn 4–6 bullet tùy độ dài CV. Thứ tự bullet ưu tiên theo JD đang apply.

**Jars Platform — Personal finance app (6-Jars budgeting, anomaly alerts, AI advisor)** · *Personal project* · `github.com/thphuc06/jar-platform` · `[demo link]`
*Java 21, Spring Boot 4.1, Spring Modulith, Spring Security, JPA/PostgreSQL, Spring AI, AWS (ECS Fargate, Aurora Serverless v2, SQS, EventBridge, Cognito, Bedrock, CloudFront), AWS CDK, GitHub Actions*

- Designed and built a **modular monolith** (8 modules, boundaries verified in CI with Spring Modulith/ArchUnit) with events delivered through the **transactional outbox** pattern to idempotent consumers, plus SQS queues with DLQs for imports and scheduled jobs. (Extracting an event-driven microservice over SNS/SQS FIFO is backlog, not done.)
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
| Không dùng Kafka | JD yêu cầu Kafka nhưng ngân sách thấp | SQS + EventBridge (SNS/SQS FIFO ở backlog Phase 3+) + bảng ánh xạ khái niệm + lab Kafka local (tùy chọn) | Hiểu cả hai, giải thích được trade-off |
| LLM bịa số | Model tự tính sai tiền | Tool calling, mọi số do Java tính; eval nhỏ | `[x/y]` câu eval đúng |
| Repo cũ lộ secret | Mật khẩu DB nằm trong repo public | Rotate, purge, gitleaks trong CI, push protection | Không tái diễn |

## 5. Mốc có thể đưa lên CV

| Sau phase | Có thể ghi gì | Tạm chưa ghi |
|---|---|---|
| **1 (xong 12/11/2026)** | Bullet 1 (phần monolith), 2, 3, 5 (phần CDK) + smoke test trên AWS thật | AI, import CSV, số p95 |
| **2 (xong 13/11/2026)** | + bullet AI advisor, Spring Batch, observability, CD qua OIDC, số p95 và coverage đo thật (xem §0) | Microservice, chi phí theo hóa đơn |
| **3+ (backlog, chưa lên lịch)** | + phần microservice / SNS/SQS của bullet 1, trace xuyên queue, game day, nếu được làm | |

## 6. Trên GitHub cần có gì để recruiter xem trong 60 giây

- README tiếng Anh: 1 câu mô tả, ảnh diagram kiến trúc, GIF demo, link demo, bảng tech stack, mục "Key engineering decisions" trỏ tới ADR.
- Badge CI xanh, badge coverage.
- Pin repo lên profile GitHub; commit thường xuyên với message rõ ràng (Conventional Commits).
- Commit phải đứng tên chính email đã gắn với tài khoản GitHub của bạn (để hiện trên contribution graph).
