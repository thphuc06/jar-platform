# Roadmap

> **Cách dùng:** mỗi bước (`P<phase>.<n>` ở Phase 0, `WP<n>` ở Phase 1 gộp) là **một mục tiêu nhỏ**, làm xong thì có giải thích theo chế độ "thầy giáo" (đã làm gì → vì sao → kiến thức cốt lõi → phương án khác → lỗi hay gặp → câu hỏi phỏng vấn → bài tập). Tick `[x]` khi xong và ghi ngày.
> Thời gian ước lượng theo giả định **khoảng 15 giờ/tuần** (chưa được xác nhận). Hạn cứng: **credit AWS hết khoảng 2027-04-09**; Tết Nguyên Đán 2027 rơi vào đầu tháng 2, đã chừa buffer.
> **Trình độ đầu vào** (2026-10-23): Java core, OOP, DSA vững về khái niệm; có chứng chỉ **AWS SAA-C03**. Vì vậy phần giải thích bỏ qua kiến thức cơ bản về Java và AWS, tập trung vào **Spring (IoC, AOP proxy, transaction, security filter chain), JPA/Hibernate, hệ phân tán và vận hành thực tế trên AWS** (phần SAA ít đi sâu: CDK, chi phí, chi tiết ECS/ALB).

| Phase | Mục tiêu | Thời gian (ước lượng) | Trạng thái |
|---|---|---|---|
| 0 | Nền móng: bảo mật, tài khoản, repo, skeleton | 26/10 – 01/11/2026 → **xong 23/10** | ✅ |
| 1 (gộp) | **Core → Cloud**: backend lõi + auth + ledger + event/cảnh báo + goals/budgeting + UI + CDK + deploy AWS + smoke test (gộp các Phase 1, 2 và 3 cũ, gồm 11 work package WP0–WP10) | bắt đầu 12/11/2026 | 🟨 đang làm (WP0) |
| ⭐ | **Mốc CV-ready**: có link demo + README tiếng Anh | cuối Phase 1 | |
| 2+ | Backlog: import CSV, AI advisor + quant, tách insights-worker, observability (xem mục "Phase 2+") | chưa lên lịch | ⬜ |
| – | Hạn cứng: credit AWS hết hạn | ~09/04/2027 | |

> Các bảng "Chuẩn bị đầu mỗi phase" bên dưới giữ nguyên cách đánh số phase cũ (0–6) để tra cứu repo nguồn cần port. Phase 1 gộp dùng các hàng 1, 2 và 3; các hàng 4, 5, 6 thuộc backlog Phase 2+.

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
- Version chốt: Spring Boot **4.1.2** (mặc định trên Initializr, ra 22/10/2026); Initializr đã có Java 25. Spring Modulith **2.1.2**; Spring AI **2.0.1** (build trên Boot 4.1.1, nên tương thích); Spring Cloud AWS **4.2.0** (bảng tương thích chính thức mới ghi Boot 4.0.x, phải kiểm ở P0.5); Testcontainers 2.0.5; Flyway 13.x (Boot quản lý version); JDK **Temurin 25.0.4.1** (sau đó chuyển sang Java 21 theo [ADR-0019](../04-adr/0019-java-21-lts.md), 12/11/2026).
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

## Phase 1 (gộp): Core → Cloud

> **Quyết định 2026-11-12:** gộp Phase 1, phần lõi của Phase 2 và phần deploy của Phase 3 thành **một phase lớn**, kết thúc bằng một bản chạy thật trên AWS đã được smoke test. Đổi JDK sang **Java 21** ([ADR-0019](../04-adr/0019-java-21-lts.md)). Bỏ khỏi phase này: import CSV, AI advisor, quant-fn, tách insights-worker, observability nâng cao (xem "Phase 2+" bên dưới).
> **Mục tiêu:** UI chạy với backend Java ở local và trên AWS, mỗi user thấy dữ liệu riêng, cảnh báo bất thường hiện realtime. **Deliverable:** link demo CloudFront (dựng khi cần, teardown khi xong), `./mvnw verify` xanh với coverage đo thật, hạ tầng tái tạo 100% bằng CDK.
> Làm trên nhánh `feat/phase-1-core-to-cloud`, mỗi work package (WP) là một hoặc vài commit. Thứ tự đảm bảo dừng ở đâu cũng có sản phẩm chạy được: WP0–4 là lõi, WP5–7 mở rộng, WP8–9 lên cloud.

- [x] **WP0** (12/11) Java 21, enforcer + JaCoCo, nâng BOM (Spring AI 2.0.2, Spring Cloud AWS 4.2.1, Spotless 3.11.0), ADR-0019, sửa mâu thuẫn tài liệu, viết lại roadmap. `./mvnw verify` xanh trên JDK 21.0.13 (12 test).
- [x] **WP1** (12/11) Platform + shared: security resource server (validator `token_use`/`client_id`, decoder lazy), `ProblemDetail` + `traceId`/`traceparent`, `Money`, `Clock`, `IdempotencyService` (một transaction, test đồng thời 8 luồng), ArchUnit cấm `double` cho tiền. 58 test xanh.
- [x] **WP2** (12/11) Identity: `GET/PATCH /me` tạo user lazy (`ON CONFLICT`, test 12 luồng), bỏ unique email, risk profile bằng bảng điểm riêng (`risk.py` cũ tính rủi ro từ giao dịch chứ không phải bảng hỏi onboarding nên không port), UUIDv7. 88 test xanh.
- [x] **WP3** (12/11) Ledger: hũ, bút toán chỉ ghi thêm, danh mục, `POST /transactions` và chuyển hũ với `@Version` + retry ngoài transaction + idempotency; keyset pagination; số dư theo ngày; test đồng thời (cùng key, cùng hũ, chuyển hai chiều), IDOR, bất biến `balance == SUM(entries)`. Phát hiện và sửa deadlock chuyển hũ hai chiều. 140 test xanh.
- [x] **WP4** (12/11) Event + insights + notification: outbox Modulith (`event_publication`), MAD + CUSUM (vector chuẩn từ test Python cũ), read model theo tháng, `GET /dashboard`, anomaly feedback, thông báo, SSE (heartbeat, `Last-Event-ID`) kiểm bằng HTTP thật, consumer idempotent qua `processed_messages`, test `Scenario` của Modulith. 170+ test xanh.
- [ ] **WP5** Budgeting + goals + khép sổ tháng: gợi ý phân bổ, kỳ ngân sách, khả thi, what-if (Monte Carlo có seed), roadmap; khép sổ idempotent, trigger bằng `@SqsListener` ở profile `aws`.
- [ ] **WP6** Đóng gói backend: OpenAPI commit vào `contracts/`, `SeedDataRunner`, `application-aws.yaml`, Dockerfile (JRE 21, arm64, non-root, read-only FS).
- [ ] **WP7** UI React: đưa `ius_financial_ui` vào `apps/web`, Amplify Auth PKCE, client sinh từ OpenAPI, bỏ Supabase và mock, ẩn màn AI Chat, SSE cho Alerts.
- [ ] **WP8** CDK Java: `JarsFoundation`, `JarsNetwork`, `JarsAuth`, `JarsData`, `JarsMessaging`, `JarsApp` + assertion test + cdk-nag; `deploy-dev.yml` (chưa chạy thử, cần ADR-0018).
- [ ] **WP9** Deploy thật: xóa 3 stack cũ (`jars-dev-auth`, `jars-dev-foundation`, `jars-dev-network`, deploy 23–27/10, không còn code trong repo), deploy stack mới, smoke test bằng script, ghi chi phí thật, rồi `cdk destroy JarsApp`.
- [ ] **WP10** README tiếng Anh (bản tiếng Việt thành `README.vi.md`), cập nhật `cv-showcase.md` bằng số liệu đo thật.

**Kỹ năng:** Spring Security / JWT, JPA + locking, idempotency, pagination, N+1, event-driven trong monolith, outbox, SSE, thuật toán thống kê, Docker, ECS, ALB, CloudFront, Aurora, IAM, CDK, CI/CD, tối ưu chi phí.

## Phase 2+: backlog (chưa lên lịch)

Các hạng mục dưới đây giữ nguyên thiết kế trong tài liệu, sẽ làm sau Phase 1 nếu còn thời gian trước khi credit hết hạn (khoảng 2027-04-09).

- **Import CSV:** module `imports`, S3 presigned, EventBridge → SQS, Spring Batch (chunk, skip, restart).
- **AI advisor + market:** Spring AI + Bedrock Converse streaming, tool calling read-only theo user, Guardrails (prompt attack filter), rate limit Bucket4j + Valkey; `quant-fn` Lambda Python (cần spike P4.1: IP AWS có bị nguồn dữ liệu chặn không, wheel `cvxpy` arm64, license vnstock).
- **Tách `insights-worker`:** SNS FIFO → SQS FIFO, `@Externalized`, inbox `processed_messages`, schema `insights` riêng, `traceparent` qua message attributes.
- **Observability và resilience:** OpenTelemetry → ADOT → X-Ray, `JarsObservability`, k6 (ghi p95 vào README), ECS native blue/green, job đối soát `balance == SUM(entries)` hằng đêm, game day, thử restore Aurora, Well-Architected Tool lần 1 và 2.
- **Khác:** audit log bằng AOP, lab Kafka local, WAF rate-based rule, chuyển sang IAM user (ADR-0018) trước khi bật CI/CD deploy.

---

## Nhật ký tiến độ

| Ngày | Bước | Ghi chú / vướng mắc |
|---|---|---|
| 2026-10-23 | Thiết kế | Hoàn thành phân tích, kiến trúc AWS-native, tài liệu và diagram |
| 2026-11-12 | Gộp phase, WP0 | Gộp Phase 1–3 cũ thành Phase 1 "Core → Cloud"; chuyển sang Java 21 (ADR-0019). **Phát hiện:** account đang có 3 stack CDK `jars-dev-auth` (23/10), `jars-dev-foundation` và `jars-dev-network` (27/10) mà code CDK không còn trong repo (local lẫn GitHub); quyết định viết lại CDK và thay các stack này ở WP9. Sửa mâu thuẫn tài liệu: idempotency chuyển về `platform` và dùng một transaction duy nhất, retry optimistic lock nằm ngoài transaction, bỏ phân quyền theo scope ở MVP. `aws login` hiện vẫn là root (ADR-0018 hoãn). |
| 2026-10-23 | **Phase 0 ✅** | Xong 8/8 bước (P0.1 bỏ qua theo quyết định). Build sạch từ clone mới: 12/12 test, Spotless, Modulith; gitleaks 0 leak; actionlint 0 lỗi. **Lệch thiết kế phát hiện được:** Identity Center không dùng được (ADR-0018, đang hoãn); account baseline để ngoài CDK; Haiku 5.5 bị AccessDenied nên đổi sang Haiku 4.5; cần module `awssdk:signin`; thứ tự BOM làm Modulith bị hạ version; biến `JAVA_HOME` cấp User trỏ JDK 21 (đã xóa). **Còn mở:** push và xem CI chạy lần đầu trên GitHub; xóa access key `phuc-account` sau 30/10; chọn mức agentic (trước P4); số giờ/tuần. |
