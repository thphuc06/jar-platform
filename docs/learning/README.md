# Learning Notes

Ghi chú học tập viết **trong lúc làm** từng bước của [roadmap](../06-plan/roadmap.md): mỗi bước một file, cùng commit với code. Roadmap ghi *đã làm gì và kết quả*; các file ở đây giải thích *vì sao*, *kiến thức nền* và *lỗi đã gặp thật*, để ôn lại và chuẩn bị phỏng vấn.

## Cấu trúc mỗi file (7 mục)

| Mục | Nội dung |
|---|---|
| 1. Đã làm gì | Thay đổi chính và **bằng chứng** (test, số đo, kiểm tra thật trên AWS) |
| 2. Tại sao | Vấn đề mà bước này giải quyết |
| 3. Kiến thức cốt lõi | Cơ chế bên dưới (Spring, JPA, PostgreSQL, AWS) |
| 4. Cách khác và trade-off | Phương án đã cân nhắc và lý do không chọn |
| 5. Lỗi hay gặp | Gồm cả lỗi đã gặp thật trong bước đó |
| 6. Câu hỏi phỏng vấn | Câu hỏi hay gặp ở vị trí Java Backend |
| 7. Bài tập nhỏ | Thí nghiệm để tự kiểm chứng; lời giải ghi lại khi một bước sau giải quyết nó |

## Mục lục

| Bước | Chủ đề | Từ khóa phỏng vấn |
|---|---|---|
| [P0.2](P0.2-aws-account-access.md) | Truy cập AWS an toàn trên tài khoản standalone | IAM user vs role, MFA, credential tạm thời, `aws login` |
| [P0.4](P0.4-spring-boot-skeleton.md) | Skeleton Spring Boot 4 | Auto-configuration, liveness vs readiness, virtual threads, test slice |
| [P0.5](P0.5-compatibility-smoke-test.md) | Smoke test tương thích thư viện | BOM, `dependencyManagement`, nearest-wins, credential provider chain |
| [P0.6](P0.6-postgres-flyway-testcontainers.md) | PostgreSQL + Flyway + Testcontainers | Migration bất biến, checksum, Testcontainers vs H2, expression index |
| [P0.7](P0.7-modulith-spotless.md) | Ranh giới module | Modular monolith, Spring Modulith, ArchUnit, thứ tự BOM |
| [P0.8](P0.8-ci-github-actions.md) | CI với GitHub Actions | Pin SHA, least privilege `GITHUB_TOKEN`, secret scanning |
| [P1.1](P1.1-cdk-cognito.md) | CDK Java + Cognito | Authorization Code + PKCE, `redirect_uri`, IaC testing, cdk-nag |
| [P1.2](P1.2-security-resource-server.md) | Spring Security resource server | JWT, access vs ID token, `@Transactional` self-invocation, `ON CONFLICT` |
| [P1.3](P1.3-problem-details-trace-id.md) | Xử lý lỗi toàn cục | RFC 9457, `@RestControllerAdvice`, sealed class, MDC, 400 vs 422 |
| [P1.4](P1.4-ledger-outbox-events.md) | Module ledger + outbox | Transactional outbox, `@TransactionalEventListener`, at-least-once, idempotent consumer |
| [P1.5](P1.5-optimistic-locking-retry.md) | Ghi tiền khi có đồng thời | Lost update, `@Version`, retry ngoài transaction, deadlock, `BigDecimal` |
| [P1.6](P1.6-idempotency-key.md) | Idempotency-Key | Idempotent POST, giữ chỗ trong cùng transaction, request hash, exactly-once |
| [P1.7](P1.7-keyset-pagination-n-plus-1.md) | Money Inbox + sửa giao dịch | Keyset vs offset, N+1, entity graph, merge-patch, bút toán đảo |

P0.1 (repo cũ) và P0.3 (cài JDK, Docker) không có ghi chú riêng.
