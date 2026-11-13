# Engineering Conventions

## 1. Git

| Chủ đề | Quy ước |
|---|---|
| Nhánh | Trunk-based. `main` luôn deploy được; nhánh ngắn ngày `feat/<roadmap-id>-<mô-tả>`, ví dụ `feat/p1.5-post-transaction` |
| Commit | [Conventional Commits](https://www.conventionalcommits.org/): `feat(ledger): post expense with optimistic locking`, `fix`, `docs`, `test`, `refactor`, `chore`, `ci`, `infra` |
| PR | Một PR ứng với một bước roadmap; có checklist (bên dưới); squash merge |
| Không bao giờ | Commit secret, commit file build (`target/`, `dist/`, `*.zip`), force-push lên `main` |

## 2. Java (`apps/jars-api`, `infra/cdk`; `apps/insights-worker` khi được tách ở Phase 3+, backlog)

| Chủ đề | Quy ước |
|---|---|
| Format | Spotless 3.10 + `palantir-java-format` 2.102 (dòng 120 ký tự). `./mvnw spotless:apply` để format; `./mvnw verify` **fail** nếu còn file chưa format |
| Dependency | Version thư viện ngoài Boot quản lý bằng BOM trong `dependencyManagement`. **Thứ tự BOM có ý nghĩa: BOM import trước thắng** (xem ADR-0004, phát hiện #5). Sau khi thêm BOM hoặc dependency thì kiểm tra bằng `./mvnw dependency:tree -Dincludes=<groupId>` |
| Module | Mỗi package con trực tiếp của `io.github.thphuc06.jars` là một module Modulith; `platform` là shared module (`@Modulithic(sharedModules = "platform")`). `ModularityTests` chạy ở mọi build |
| Package | `io.github.thphuc06.jars.<module>`; public API nằm ở root package của module, còn lại ở `internal` ([modules.md](../02-architecture/modules.md)) |
| DTO | `record`; tên `XxxRequest` / `XxxResponse`; không trả entity ra controller |
| Entity | Không dùng Lombok `@Data` cho entity (vì `equals`/`hashCode` có lazy proxy); constructor `protected` cho JPA; method nghiệp vụ nằm trên entity |
| Null | Trả `Optional` cho method tìm kiếm; JSpecify `@Nullable` khi cần (Boot 4 / Framework 7 đã dùng JSpecify) |
| Tiền | Chỉ dùng `Money` / `BigDecimal`; cấm `double` cho tiền (ArchUnit rule) |
| Thời gian | `Instant` trong domain; `Clock` được inject để test |
| Exception | Exception nghiệp vụ kế thừa `DomainException(problemType)` và được map sang `ProblemDetail` |
| Log | SLF4J, format JSON; không log PII hay số tiền ở INFO; có `traceId` trong MDC |
| Config | `@ConfigurationProperties` (record) có validate; không dùng `@Value` rải rác |

## 3. Test

| Lớp test | Công cụ | Ví dụ | Chạy |
|---|---|---|---|
| Unit | JUnit Jupiter + AssertJ + Mockito | CUSUM, Money, allocation | mỗi build |
| Slice | `@WebMvcTest`, `@DataJpaTest` (+ Testcontainers) | Controller trả đúng ProblemDetail; query keyset | mỗi build |
| Module | `@ApplicationModuleTest` + `Scenario` | Ghi giao dịch → event → read model | mỗi build |
| Integration | `@SpringBootTest` + Testcontainers (Postgres, LocalStack) | Luồng F2, F3, SQS listener | mỗi build (CI) |
| Architecture | Modulith `verify()` + ArchUnit | Ranh giới module; cấm `double` cho tiền | mỗi build |
| Infra | CDK assertions + cdk-nag | Không có NAT; SG không mở 0.0.0.0/0 | mỗi build |
| Load | k6 | Dashboard, POST transaction | chạy tay, `scripts/load-test.ps1` (Phase 2 WP2) |

**Mục tiêu coverage:** ≥ 80% line coverage cho `*/internal/domain` và `*/internal/service` (JaCoCo). Không chạy theo coverage cho controller hay config.

**Quy tắc đặt tên test:** `methodOrBehavior_condition_expectedResult`, ví dụ `postExpense_whenVersionConflict_retriesAndSucceeds`.

## 4. Definition of Done (cho mỗi bước roadmap)

- [ ] Code + test xanh ở local và trên CI.
- [ ] Đã cập nhật tài liệu liên quan (API contract, data model, event catalog, flows).
- [ ] Đã viết ADR nếu có quyết định kiến trúc mới.
- [ ] Không có secret; gitleaks xanh.
- [ ] Tick roadmap và ghi nhật ký.
- [ ] Đã viết phần giải thích "thầy giáo" (đã làm gì / vì sao / kiến thức / phương án khác / lỗi hay gặp / câu hỏi phỏng vấn / bài tập) vào mô tả PR.

## 5. PR checklist (template nằm ở `.github/pull_request_template.md`)

Mục tiêu (roadmap ID) · Thay đổi chính · Cách test · Docs updated? · ADR? · Ảnh hưởng chi phí AWS?
