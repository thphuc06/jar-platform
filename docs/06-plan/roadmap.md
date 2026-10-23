# Roadmap

> **Cách dùng:** mỗi bước (`P<phase>.<n>`) là **một mục tiêu nhỏ**, làm xong thì có giải thích theo chế độ "thầy giáo" (đã làm gì → vì sao → kiến thức cốt lõi → phương án khác → lỗi hay gặp → câu hỏi phỏng vấn → bài tập). Tick `[x]` khi xong và ghi ngày.
> Thời gian ước lượng theo giả định **khoảng 15 giờ/tuần** (chưa được xác nhận). Hạn cứng: **credit AWS hết khoảng 2027-04-09**; Tết Nguyên Đán 2027 rơi vào đầu tháng 2, đã chừa buffer.
> **Trình độ đầu vào** (2026-10-23): Java core, OOP, DSA vững về khái niệm; có chứng chỉ **AWS SAA-C03**. Vì vậy phần giải thích bỏ qua kiến thức cơ bản về Java và AWS, tập trung vào **Spring (IoC, AOP proxy, transaction, security filter chain), JPA/Hibernate, hệ phân tán và vận hành thực tế trên AWS** (phần SAA ít đi sâu: CDK, chi phí, chi tiết ECS/ALB).

| Phase | Mục tiêu | Thời gian (ước lượng) | Trạng thái |
|---|---|---|---|
| 0 | Nền móng: bảo mật, tài khoản, repo, skeleton | 26/10 – 01/11/2026 | ⬜ |
| 1 | Monolith lõi + Auth + nối UI | 02/11 – 29/11/2026 | ⬜ |
| 2 | Nghiệp vụ tài chính + event in-process | 30/11 – 20/12/2026 | ⬜ |
| 3 | Deploy AWS MVP | 21/12/2026 – 03/01/2027 | ⬜ |
| ⭐ | **Mốc CV-ready**: có link demo + README | ~03/01/2027 | |
| 4 | AI advisor + quant + cache | 04/01 – 24/01/2027 | ⬜ |
| 5 | Tách service theo event (SNS/SQS FIFO) | 25/01 – 21/02/2027 (có Tết) | ⬜ |
| 6 | Observability, resilience, polish | 22/02 – 14/03/2027 | ⬜ |
| – | Buffer trước khi credit hết hạn | 15/03 – 09/04/2027 | |

---

## Phase 0: Nền móng

**Mục tiêu:** an toàn và có khung để code. **Deliverable:** repo có skeleton chạy được, CI xanh, tài khoản AWS sạch sẽ.

- [ ] **P0.1** Xử lý sự cố bảo mật của repo cũ ([security.md §8](../02-architecture/security.md#8-sự-cố-của-repo-cũ-hackathon-và-việc-cần-làm)): đổi mật khẩu Supabase, untrack `deploy.env`, kiểm tra RLS, archive repo cũ.
- [ ] **P0.2** Vệ sinh tài khoản AWS ([deployment.md §1](../05-operations/deployment.md#1-chuẩn-bị-tài-khoản-làm-1-lần)): MFA root, IAM Identity Center, profile `jars-dev`, cost allocation tag, S3 Block Public Access (account), EBS default encryption, IAM Access Analyzer, Cost Anomaly Detection. Xem lại `sidewalk-labelstudio`.
- [ ] **P0.3** Cài JDK 25, mở Docker Desktop; tạo repo GitHub `jar-platform` (push skeleton tài liệu này), bật secret scanning.
- [ ] **P0.4** Skeleton `apps/jars-api`: Spring Boot 4.1.x (Spring Initializr), Maven Wrapper, package `io.github.thphuc06.jars`, endpoint `/api/v1/health`, bật virtual threads.
- [ ] **P0.5** **Smoke test tương thích** (ghi kết quả vào [ADR-0004](../04-adr/0004-java-25-spring-boot-4.md#kết-quả-smoke-test-điền-ở-phase-0)): Boot 4.1 + Spring Cloud AWS 4.2 + Spring Modulith 2.1 + Spring AI 2.0 cùng khởi động được.
- [ ] **P0.6** `local/compose.yaml` (Postgres 17) + Flyway `V1__baseline.sql` (bảng `users`) + Testcontainers `@ServiceConnection` + test đầu tiên.
- [ ] **P0.7** `ModularityTests` (Modulith `verify()`) + Spotless format.
- [ ] **P0.8** `.github/workflows/ci.yml` (build + test + gitleaks) + PR template.

**Kỹ năng:** Maven, cấu trúc project Spring Boot, Docker Compose, Flyway, Testcontainers, CI.

## Phase 1: Monolith lõi + Auth + nối UI

**Mục tiêu:** UI chạy với Java ở local, mỗi user thấy dữ liệu riêng. **Deliverable:** luồng F1, F2 chạy được; UI không còn gọi Supabase.

- [ ] **P1.1** CDK skeleton `infra/cdk` + stack `JarsAuth` (Cognito user pool + app client localhost) → deploy (miễn phí).
- [ ] **P1.2** Spring Security resource server: validator `token_use` / `client_id`, `GET /me` tạo user lazy (luồng [F1](../02-architecture/flows.md#f1)); test bằng `jwt()`.
- [ ] **P1.3** Global error handling bằng `ProblemDetail` + Bean Validation + `traceId`.
- [ ] **P1.4** Module `ledger`: entity `Jar`, `JarEntry`, `Category` + migration; nghe `UserRegistered` để tạo 6 hũ mặc định.
- [ ] **P1.5** `POST /transactions` với `@Transactional`, `@Version`, retry khi xung đột (luồng [F2](../02-architecture/flows.md#f2)); test đồng thời (2 thread).
- [ ] **P1.6** Idempotency-Key filter + bảng `idempotency_keys`.
- [ ] **P1.7** `GET /transactions` với keyset pagination + `@EntityGraph` (tránh N+1); test số câu query.
- [ ] **P1.8** Module `identity`: risk profile (port từ `risk.py`); module `goals` CRUD.
- [ ] **P1.9** `GET /dashboard` (aggregate SQL / projection).
- [ ] **P1.10** springdoc → `contracts/openapi/jars-api.yaml` → `openapi-typescript`.
- [ ] **P1.11** Đưa UI vào `apps/web`: Amplify Auth (PKCE), thay `useSupabaseData.ts` bằng client mới, Vite proxy `/api`; bỏ mock ở Dashboard, Inbox, Transfer, Jars, Goals.
- [ ] **P1.12** Test IDOR cho mọi endpoint có `{id}`; `SeedDataRunner` cho profile `local`.

**Kỹ năng:** Spring Security / JWT, JPA relationships, transaction và locking, idempotency, pagination, N+1, OpenAPI, test.

## Phase 2: Nghiệp vụ tài chính + event in-process

**Mục tiêu:** toàn bộ logic xác định của bản cũ chạy bằng Java. **Deliverable:** cảnh báo bất thường hiện realtime trên UI; import CSV hoạt động ở local.

- [ ] **P2.1** Spring Modulith event registry (outbox) + `@ApplicationModuleListener`; test bằng `Scenario` của `@ApplicationModuleTest`.
- [ ] **P2.2** Module `insights`: CUSUM (port `cusum_engine`) + MAD outlier; unit test có dữ liệu chuẩn (golden data).
- [ ] **P2.3** Read model `monthly_jar_spend` cập nhật từ event.
- [ ] **P2.4** Module `notification`: lưu + `SseEmitter` + heartbeat + `Last-Event-ID`; UI Alerts nối SSE.
- [ ] **P2.5** `POST /anomalies/{id}/feedback`.
- [ ] **P2.6** `budgeting`: gợi ý phân bổ (port `allocation.py`), `jar_periods`, logic khép sổ tháng (gọi bằng tay hoặc qua test).
- [ ] **P2.7** `goals`: feasibility, scenarios (Monte Carlo có seed), roadmap compiler; nối GoalPlanner (bỏ `mock-roadmap.ts`).
- [ ] **P2.8** `imports`: Spring Batch job đọc CSV (file local hoặc LocalStack S3), chunk + skip + restart.
- [ ] **P2.9** Audit log bằng AOP + MDC `traceId`.

**Kỹ năng:** event-driven trong monolith, outbox, async, SSE, thuật toán thống kê, Spring Batch, AOP, strategy pattern.

## Phase 3: Deploy AWS MVP

**Mục tiêu:** có link demo. **Deliverable:** [Phase 3 diagram](../diagrams/aws-phase3-mvp.png) chạy thật; tạo lại được toàn bộ bằng CDK.

- [ ] **P3.1** Dockerfile multi-stage (layered jar, arm64, non-root user, chạy được với read-only root filesystem + `/tmp`) + chạy thử image ở local; graceful shutdown (`server.shutdown=graceful`).
- [ ] **P3.2** `JarsFoundation` (ECR, Budgets, OIDC role) + `JarsNetwork` (VPC không NAT) + CDK assertions test.
- [ ] **P3.3** `JarsData` (Aurora Serverless v2 min 0) + alarm ACU; kiểm tra auto-pause hoạt động.
- [ ] **P3.4** `JarsApp`: ECS Fargate + internal ALB + CloudFront (S3 OAC + VPC origin) + Cognito app client. Áp dụng [vòng đời task](../02-architecture/aws-infrastructure.md#vòng-đời-task-khởi-động-dừng-draining) (grace period 90 s, deregistration delay 30 s, `stopTimeout` 30 s); S3 `enforceSSL` + lifecycle xóa CSV sau 7 ngày.
- [ ] **P3.5** `JarsMessaging`: S3 → EventBridge → SQS `jars-imports`; Scheduler → `jars-jobs`; `@SqsListener` (luồng [F5](../02-architecture/flows.md#f5), [F6](../02-architecture/flows.md#f6)).
- [ ] **P3.6** `deploy-dev.yml` (OIDC) + checklist sau deploy.
- [ ] **P3.7** Script hoặc lệnh SLEEP / TEARDOWN đã thử thật; ghi nhật ký chi phí tuần đầu.
- [ ] **P3.8** README bản tiếng Anh (cho recruiter) + ảnh chụp màn hình + link demo.
- [ ] **P3.9** Review lần 1 bằng **AWS Well-Architected Tool** (miễn phí); bật GuardDuty dùng thử 30 ngày; cập nhật [well-architected-review.md](../02-architecture/well-architected-review.md).

**Kỹ năng:** Docker, ECS, ALB, CloudFront, Aurora, IAM, CDK, CI/CD, tối ưu chi phí.

## Phase 4: AI advisor + quant + cache

- [ ] **P4.1** Spike `quant-fn`: vnstock gọi từ Lambda có bị chặn IP không? cvxpy chạy trên arm64 được không? (ADR-0017)
- [ ] **P4.2** `quant-fn` (3 operation) + `JarsQuant` + contract test.
- [ ] **P4.3** Module `market`: `LambdaClient` + timeout + circuit breaker + cache Valkey; `enableCache=true`.
- [ ] **P4.4** Module `advisor`: Spring AI `ChatClient` + Bedrock Converse streaming → SSE (luồng [F4](../02-architecture/flows.md#f4)).
- [ ] **P4.5** Tools (read-only, user-scoped) + test chống prompt injection.
- [ ] **P4.6** Bedrock Guardrails (denied topic, PII, **prompt attack filter**; kiểm tra cách Spring AI truyền `guardrailConfig`) + giới hạn số vòng tool-call + log metadata của lời gọi model (không log toàn bộ prompt).
- [ ] **P4.7** Rate limit Bucket4j + Valkey; metric token.
- [ ] **P4.8** Chat memory (JDBC) + viết lại `useChat.ts` theo named events.
- [ ] **P4.9** (Tùy chọn) RAG: Bedrock Knowledge Base + S3 Vectors với `kb/*.md`.

## Phase 5: Tách service theo event

- [ ] **P5.1** `JarsMessaging`: SNS FIFO + SQS FIFO + DLQ + alarm DLQ.
- [ ] **P5.2** `@Externalized` + `spring-cloud-aws-modulith-events-sns`; envelope + JSON Schema trong `contracts/events/`.
- [ ] **P5.3** `apps/insights-worker`: `@SqsListener`, inbox `processed_messages`, schema `insights`.
- [ ] **P5.4** Chuyển `AnomalyDetected` ngược về jars-api qua SQS; fan-out SSE bằng Valkey pub/sub.
- [ ] **P5.5** Truyền `traceparent` qua message attributes; test tích hợp bằng LocalStack.
- [ ] **P5.6** ADR về cách dashboard đọc read model sau khi tách.
- [ ] **P5.7** (Tùy chọn) Lab Kafka local.

## Phase 6: Observability, resilience, polish

- [ ] **P6.1** OpenTelemetry → ADOT sidecar → X-Ray + CloudWatch; log có `traceId`.
- [ ] **P6.2** `JarsObservability`: dashboard + alarm (5xx, p95, DLQ, ACU, queue age).
- [ ] **P6.3** Load test k6 (Dashboard, POST transaction) → ghi p95 và throughput vào README.
- [ ] **P6.4** ECS native blue/green + Lambda hook smoke test.
- [ ] **P6.5** Job đối soát `balance == SUM(entries)` hằng đêm.
- [ ] **P6.6** (Tùy chọn) WAF rate-based rule; (tùy chọn) Helm chart + kind ở local.
- [ ] **P6.7** Game day: tắt task đang chạy, cố ý làm message lỗi để vào DLQ, chặn quant-fn; **thử restore Aurora từ snapshot**; ghi kết quả vào runbook.
- [ ] **P6.8** Review lần 2 bằng WA Tool (+ Generative AI Lens); cập nhật [well-architected-review.md](../02-architecture/well-architected-review.md).
- [ ] **P6.9** Video demo 3 phút + README hoàn chỉnh + dọn dẹp tài liệu + điền số liệu thật vào [cv-showcase.md](../01-product/cv-showcase.md).

---

## Nhật ký tiến độ

| Ngày | Bước | Ghi chú / vướng mắc |
|---|---|---|
| 2026-10-23 | Thiết kế | Hoàn thành phân tích, kiến trúc AWS-native, tài liệu và diagram |
