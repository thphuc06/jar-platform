# Roadmap

> **Cách dùng:** mỗi bước (`P<phase>.<n>`) là **một mục tiêu nhỏ**, làm xong thì có giải thích theo chế độ "thầy giáo" (đã làm gì → vì sao → kiến thức cốt lõi → phương án khác → lỗi hay gặp → câu hỏi phỏng vấn → bài tập). Tick `[x]` khi xong và ghi ngày.
> Thời gian ước lượng theo giả định **khoảng 15 giờ/tuần** (chưa được xác nhận). Hạn cứng: **credit AWS hết khoảng 2027-04-09**; Tết Nguyên Đán 2027 rơi vào đầu tháng 2, đã chừa buffer.
> **Trình độ đầu vào** (2026-10-23): Java core, OOP, DSA vững về khái niệm; có chứng chỉ **AWS SAA-C03**. Vì vậy phần giải thích bỏ qua kiến thức cơ bản về Java và AWS, tập trung vào **Spring (IoC, AOP proxy, transaction, security filter chain), JPA/Hibernate, hệ phân tán và vận hành thực tế trên AWS** (phần SAA ít đi sâu: CDK, chi phí, chi tiết ECS/ALB).

| Phase | Mục tiêu | Thời gian (ước lượng) | Trạng thái |
|---|---|---|---|
| 0 | Nền móng: bảo mật, tài khoản, repo, skeleton | 26/10 – 01/11/2026 → **xong 23/10** | ✅ |
| 1 | Monolith lõi + Auth + nối UI | 02/11 – 29/11/2026 | ⬜ |
| 2 | Nghiệp vụ tài chính + event in-process | 30/11 – 20/12/2026 | ⬜ |
| 3 | Deploy AWS MVP | 21/12/2026 – 03/01/2027 | ⬜ |
| ⭐ | **Mốc CV-ready**: có link demo + README | ~03/01/2027 | |
| 4 | AI advisor + quant + cache | 04/01 – 24/01/2027 | ⬜ |
| 5 | Tách service theo event (SNS/SQS FIFO) | 25/01 – 21/02/2027 (có Tết) | ⬜ |
| 6 | Observability, resilience, polish | 22/02 – 14/03/2027 | ⬜ |
| – | Buffer trước khi credit hết hạn | 15/03 – 09/04/2027 | |

## Chuẩn bị đầu mỗi phase (research + đọc source cũ)

**Quy trình kickoff** (khoảng 1–2 giờ, trước bước đầu tiên của mỗi phase):
1. Đọc tài liệu chính thức của các công nghệ trong phase. Kiểm tra version và API có thay đổi so với lúc thiết kế (10/2026) không.
2. Đọc các file nguồn của repo cũ liệt kê bên dưới. Tách phần **logic** (port sang Java) khỏi phần **glue** (bỏ đi), ghi ra các quy tắc nghiệp vụ, hằng số và test case có thể tái dùng.
3. Nếu khác với thiết kế thì cập nhật tài liệu hoặc viết ADR **trước khi** code.
4. Chốt danh sách bước `P<n>.x` của phase (có thể thêm, bớt hoặc tách nhỏ).

Repo cũ: `be` = `ius_backend_agent_mcp`, `ui` = `ius_financial_ui`, `stock` = `ius_dexter_vnstock_PyPortfolioOpt_intergration`. Đường dẫn `be` dưới đây tính từ `src/aws-specialist-agent-mcp-server/` nếu bắt đầu bằng `planner_agent/` hoặc `service_agent/`.

| Phase | Tài liệu cần research | Source cũ cần đọc để port | Tái dùng được gì |
|---|---|---|---|
| 0 | Spring Boot 4.1 release notes; Spring Modulith 2.1 reference; bảng tương thích Spring Cloud AWS; Testcontainers + `@ServiceConnection`; Flyway | – | – |
| 1 | Spring Security OAuth2 Resource Server; claim của Cognito access token; JPA locking (`@Version`), `@EntityGraph`; springdoc cho Boot 4; Amplify Auth (`signInWithRedirect`) | `be/schema.sql`, `be/backend/seed/seed.json`, `be/backend/seed/sparkov_category_mapping.json`, `planner_agent/finance/risk.py`, `planner_agent/finance/taxonomy.py`; `ui/src/hooks/useSupabaseData.ts`, `ui/src/lib/auth.ts`, `ui/src/pages/*.tsx`, `ui/src/pages/Onboarding.tsx` | Danh mục chi tiêu và mapping; câu hỏi onboarding; công thức chấm điểm rủi ro; query mà mỗi màn hình cần |
| 2 | Spring Modulith events + `Scenario`; `SseEmitter`; Spring Batch 6 (chunk, skip, restart); Spring AOP | `be/infrastructure/lambda/cusum_worker/package/cusum_engine.py`, `cusum_state_manager.py`, `income_engine.py`; `planner_agent/finance/anomaly.py`, `allocation.py`, `goal.py`, `scenario.py`, `spend.py`, `recurring.py`, `legacy_tools.py` (`ingest_statement_vn`, `normalize_and_categorize_txn`); `service_agent/projection_engine.py`, `milestone_engine.py`, `roadmap_compiler.py`, `candidate_ranker.py`; test: `be/tests/test_cusum_sliding_window_local.py`, `test_income_engine.py`, `test_service_agent_mvp.py`; `ui/src/types/roadmap.ts` | Công thức CUSUM (k, h), MAD; quy tắc phân bổ hũ; mô phỏng mục tiêu; cấu trúc roadmap; **dùng test cũ làm golden data** cho test Java |
| 3 | CDK v2 (ECS patterns, `DatabaseCluster` Serverless v2, CloudFront `VpcOrigin`); ECS draining và health check; GitHub OIDC cho AWS | `be/iac/terraform/ecs-services/*.tf`, `be/iac/terraform/account-bootstrap/*.tf`, `be/DEPLOY.md` | Tham số ECS/ALB/Cognito đã chạy được; những lỗi từng gặp khi deploy |
| 4 | Spring AI 2.0 (ChatClient, tool calling, chat memory, streaming); Bedrock Guardrails + Converse; Lambda Python container (arm64); vnstock; PyPortfolioOpt | `stock/vnstock/vnstock_service.py`, `stock/dexter/src/investment_advisor/python/pypfopt_optimizer.py`, `optimizer.ts` (heuristic dự phòng), `risk-profile-resolver.ts`, `recommendation-engine.ts`; `be/agent/kb/*.md`, `be/kb/disclaimers.md`, `planner_agent/finance/suitability.py`, `be/agent/router/policy.py`, `be/agent/response/*`; `ui/src/hooks/useChat.ts` | Endpoint vnstock đã dùng; cách gọi optimizer và heuristic khi optimizer lỗi; disclaimer; quy tắc suitability → guardrail và system prompt; nội dung RAG |
| 5 | Spring Cloud AWS SQS (`@SqsListener`, acknowledgement); SNS/SQS FIFO; `spring-cloud-aws-modulith-events-sns`; W3C trace context | `be/workers/tier1/*` (queue_client, state_store, alert_processor), `be/workers/trigger_worker.py` | Ý tưởng tách alert processor và state store (chỉ tham khảo thiết kế) |
| 6 | ADOT + X-Ray cho Java; ECS native blue/green + lifecycle hooks; k6; AWS Well-Architected Tool | `be/agent/observability/*` (audit logger, tracing) | Danh sách field trace/audit đáng ghi |

---

## Phase 0: Nền móng

**Mục tiêu:** an toàn và có khung để code. **Deliverable:** repo có skeleton chạy được, CI xanh, tài khoản AWS sạch sẽ.

**Kickoff 2026-10-23 (đã research):**
- Version chốt: Spring Boot **4.1.2** (mặc định trên Initializr, ra 22/10/2026); Initializr đã có Java 25. Spring Modulith **2.1.2**; Spring AI **2.0.1** (build trên Boot 4.1.1, nên tương thích); Spring Cloud AWS **4.2.0** (bảng tương thích chính thức mới ghi Boot 4.0.x, phải kiểm ở P0.5); Testcontainers 2.0.5; Flyway 13.x (Boot quản lý version); JDK **Temurin 25.0.4.1**.
- Repo cũ (kiểm ngày 23/10): `current_env.json`, `deploy.env` và 2 file zip **vẫn còn ở HEAD** của `ius_backend_agent_mcp` (public, 0 fork). Commit gây lộ là `ab10edb` của một thành viên khác (22/03/2026). Repo có 3 maintainer khác.
- Thay đổi thiết kế: các thiết lập bảo mật cấp account **không đặt trong CDK** (xem [deployment.md §1](../05-operations/deployment.md#1-chuẩn-bị-tài-khoản-làm-1-lần)).

Ký hiệu: 👤 bạn tự làm (console, quyết định) · 🤖 làm cùng trong session · ✋ cần bạn đồng ý trước khi thực hiện.

- [x] **P0.1** ~~Xử lý sự cố bảo mật của repo cũ~~: **bỏ qua theo quyết định (2026-10-23)**. Repo cũ thuộc org, nằm ngoài phạm vi; repo mới không dùng credential nào của repo cũ ([security.md §8](../02-architecture/security.md#8-sự-cố-của-repo-cũ-hackathon-và-việc-cần-làm)).
- [x] **P0.2** Vệ sinh tài khoản AWS (23/10): root đã có MFA và không có access key ✅; **vô hiệu hóa access key dài hạn của `phuc-account`** ✅ (xóa hẳn sau 30/10 nếu không có gì hỏng); `sidewalk-labelstudio` đã stop ✅; Cost Anomaly Detection có sẵn ✅. **Bỏ qua theo quyết định:** dùng IAM user thay root (ADR-0018 hoãn), S3 Block Public Access cấp account, EBS default encryption, Access Analyzer. Cost allocation tag `Project` bật ở P3.2 khi đã có resource gắn tag.
- [x] **P0.3** (23/10) JDK Temurin 25.0.4 cài qua winget; Docker Desktop chạy (29.5.3); secret scanning + push protection của `thphuc06/jar-platform` đã bật mặc định.
- [x] **P0.4** (23/10) Skeleton `apps/jars-api`: Spring Boot 4.1.2 (Initializr, starter tách module của Boot 4: `spring-boot-starter-webmvc` + các `-test` starter), Maven Wrapper 3.3.4 (only-script, Maven 3.10.0), package `io.github.thphuc06.jars`, `GET /api/v1/health` (version từ `build-info`), readiness/liveness probe, virtual threads. 4 test (slice + full context) xanh; đã chạy JAR thật và gọi bằng curl.
- [x] **P0.5** (23/10) Smoke test tương thích: Boot 4.1.2 + Spring Cloud AWS 4.2.0 (Secrets import, SQS round-trip) + Spring AI 2.0.1 (Bedrock Converse) + Modulith 2.1.2 (`verify()`): ✅ cùng chạy được, chỉ có Jackson 3.1.7. Phát hiện: cần module `awssdk:signin` để đọc credential `aws login`; Haiku 5.5 bị AccessDenied nên đổi mặc định sang **Haiku 4.5**; SQS listener chạy trên platform thread. Kết quả trong [ADR-0004](../04-adr/0004-java-25-spring-boot-4.md#kết-quả-smoke-test-p05-2026-10-23); 3 BOM đã được ghim version trong `jars-api/pom.xml`.
- [x] **P0.6** (23/10) `local/compose.yaml` (`postgres:17-alpine`, Spring Boot tự khởi động qua `spring-boot-docker-compose`, chế độ `start-only`); profile mặc định `local`; Flyway `V1__baseline_users.sql` vào schema `jars`; Testcontainers 2 + `@ServiceConnection`; `BaselineMigrationTest` (`@JdbcTest`: V1 đã áp dụng, unique `cognito_sub`, email unique không phân biệt hoa/thường); test readiness **không** có `db`. 9/9 test xanh; đã chạy `spring-boot:run` thật.
- [x] **P0.7** (23/10) `ModularityTests`: `ApplicationModules.verify()` + sinh tài liệu module (`Documenter` → `target/spring-modulith-docs`) + **test của test** (fixture cố ý vi phạm ranh giới phải bị phát hiện); `@Modulithic(sharedModules = "platform")`. Spotless 3.10.4 + palantir-java-format 2.102.0, `check` gắn vào `verify`. Phát hiện: BOM của Spring Cloud AWS hạ Modulith xuống 2.1.0, đã sửa bằng cách đổi thứ tự BOM. 12/12 test xanh.
- [x] **P0.8** (23/10) `.github/workflows/ci.yml`: job `jars-api` (Temurin 25, cache Maven, `mvnw verify` với Testcontainers + Modulith + Spotless) và job `gitleaks` (toàn bộ lịch sử, action pin SHA); `permissions: contents: read`, `concurrency`, timeout; `dependabot.yml` (Maven + Actions hằng tuần). Đã kiểm tra ở local: actionlint 0 lỗi (đã thử với workflow lỗi để chắc actionlint bắt được), gitleaks 0 leak trên 13 commit. PR template đã có từ trước. **Lần chạy đầu tiên trên GitHub cần push** để xác nhận.

**Kỹ năng:** Maven, cấu trúc project Spring Boot, Docker Compose, Flyway, Testcontainers, CI.

## Phase 1: Monolith lõi + Auth + nối UI

**Mục tiêu:** UI chạy với Java ở local, mỗi user thấy dữ liệu riêng. **Deliverable:** luồng F1, F2 chạy được; UI không còn gọi Supabase.

**Kickoff 2026-10-23 (đã research + đọc source cũ):**
- Version: starter Boot 4 đổi tên thành **`spring-boot-starter-security-oauth2-resource-server`** (+ `-test`); `spring-boot-starter-data-jpa` (+ `-test`); springdoc **3.1.1** (build trên Boot 4.1.0); `aws-cdk-lib` **2.273.0**, `constructs` 10.8.1, `cdk-nag` 3.0.2.
- Cognito access token: có `token_use=access`, `client_id`, `scope`, `sub`, `username`; **`aud` chỉ có khi dùng resource binding** → validate `iss` + `token_use` + `client_id`. **Không validate định dạng của `sub`** (AWS khuyên vậy).
- Hibernate 7: `@UuidGenerator(style = VERSION_7)` sinh UUIDv7 phía app (khớp data-model).
- Cognito managed login trong CDK: `UserPoolDomain(managedLoginVersion = NEWER_MANAGED_LOGIN)` + `CfnManagedLoginBranding` (L2 chưa hỗ trợ style).
- Từ source cũ → [business-rules.md](../01-product/business-rules.md): 6 hũ + hũ ẩn **`POOL`** (tiền chưa phân bổ, khớp màn Budget Jars cũ); màn **Transfer = EXPENSE từ một hũ** (`counterparty` = tài khoản nhận); danh mục mặc định có hũ gợi ý; **cách chấm bảng hỏi rủi ro là thiết kế mới** (code cũ không chấm). `risk.py` là sức khỏe tài chính từ giao dịch, nên chuyển sang `insights` ở Phase 2.
- Thay đổi thứ tự: event registry của Modulith (JPA) cần **từ P1.4** (ledger nghe `UserRegistered`), không đợi tới P2.1.

- [ ] **P1.1** CDK skeleton `infra/cdk` (Maven, CDK 2.273.0, test assertions + cdk-nag) + `cdk bootstrap` ap-southeast-1 + stack `JarsAuth` (user pool Essentials, managed login, resource server `jars-api` có scope read/write, app client public + PKCE cho `localhost:5173`) → deploy (miễn phí).
- [ ] **P1.2** Spring Security resource server (validator `token_use` / `client_id`, stateless, 401/403 dạng ProblemDetail) + JPA + entity `User` (UUIDv7) + `GET /api/v1/me` tạo user lazy, xử lý race bằng `ON CONFLICT` (luồng [F1](../02-architecture/flows.md#f1)); test bằng `jwt()`.
- [ ] **P1.3** Global error handling bằng `ProblemDetail` + Bean Validation + `traceId`.
- [ ] **P1.4** Module `ledger`: entity `Jar` (gồm `POOL`), `JarEntry`, `Category` (+ `default_jar_code`) + migration + seed danh mục mặc định; Modulith event registry (JPA) + nghe `UserRegistered` để tạo hũ theo BR-1.
- [ ] **P1.5** `POST /transactions` (INCOME/EXPENSE) + `POST /jars/transfers` (phân bổ từ POOL / chuyển giữa hũ) với `@Transactional`, `@Version`, retry khi xung đột (luồng [F2](../02-architecture/flows.md#f2)); test đồng thời (2 thread); quy tắc BR-2 và BR-5.
- [ ] **P1.6** Idempotency-Key + bảng `idempotency_keys`.
- [ ] **P1.7** `GET /transactions` với keyset pagination + `@EntityGraph` (tránh N+1); test số câu query; `PATCH /transactions/{id}` (đổi danh mục hoặc hũ).
- [ ] **P1.8** Module `identity`: bảng hỏi rủi ro theo BR-4 (`PUT/GET /me/risk-profile`); module `goals` CRUD.
- [ ] **P1.9** `GET /dashboard` (aggregate SQL / projection) + `GET /balances/daily`.
- [ ] **P1.10** springdoc 3.1.1 → `contracts/openapi/jars-api.yaml` → `openapi-typescript`.
- [ ] **P1.11** Đưa UI vào `apps/web`: auth PKCE (research ở bước này: Amplify Auth hay `oidc-client-ts`), thay `useSupabaseData.ts` bằng client mới, Vite proxy `/api`; bỏ mock ở Dashboard, Inbox, Transfer, Jars, Goals.
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
| 2026-10-23 | **Phase 0 ✅** | Xong 8/8 bước (P0.1 bỏ qua theo quyết định). Build sạch từ clone mới: 12/12 test, Spotless, Modulith; gitleaks 0 leak; actionlint 0 lỗi. **Lệch thiết kế phát hiện được:** Identity Center không dùng được (ADR-0018, đang hoãn); account baseline để ngoài CDK; Haiku 5.5 bị AccessDenied nên đổi sang Haiku 4.5; cần module `awssdk:signin`; thứ tự BOM làm Modulith bị hạ version; biến `JAVA_HOME` cấp User trỏ JDK 21 (đã xóa). **Còn mở:** push và xem CI chạy lần đầu trên GitHub; xóa access key `phuc-account` sau 30/10; chọn mức agentic (trước P4); số giờ/tuần. |
