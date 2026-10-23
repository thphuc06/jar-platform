# Skills Matrix: Feature × Kỹ năng × AWS × Phỏng vấn

> Mục đích: mỗi feature trong sản phẩm đều phải "rèn" được kỹ năng mà JD Java Backend (Junior → Middle) tại Việt Nam yêu cầu. Nguồn JD và phân tầng kỹ năng được tổng hợp ngày 2026-10-23 (MB Bank, VPBank, Techcombank, Shinhan DS, NAB, MoMo, FPT, Lotte; báo cáo ITviec/TopDev).

## 1. Phân tầng kỹ năng từ JD

| Tầng | Kỹ năng |
|---|---|
| **Bắt buộc** | Java core + OOP + Collections/Stream; Spring Boot, Data JPA/Hibernate, Security; SQL (Oracle/PostgreSQL/MySQL) + tối ưu query; REST API; Git; JUnit |
| **Thường gặp** | Message queue (Kafka/RabbitMQ), xử lý bất đồng bộ; Redis; microservices; Docker; CI/CD; design pattern/SOLID |
| **Điểm cộng** | AWS / Kubernetes; observability (ELK, Prometheus, Grafana); OAuth2/JWT, API gateway; DDD/CQRS; kỹ năng AI/LLM; tiếng Anh |

## 2. Bảng Feature × Skill

| # | Feature | Java làm gì | Kỹ năng | AWS | Câu hỏi phỏng vấn |
|---|---|---|---|---|---|
| 1 | Đăng nhập; dữ liệu riêng từng user | Resource server, tạo user lazy, kiểm tra ownership | Spring Security filter chain, JWT/JWKS, PKCE, `@PreAuthorize`, test IDOR | Cognito | JWT khác session thế nào? Revoke JWT bằng cách nào? IDOR là gì? |
| 2 | Onboarding risk profile | Chấm điểm bảng hỏi | Bean Validation, record DTO, `ProblemDetail`, `@RestControllerAdvice` | – | `@Valid` vs `@Validated`? Xử lý lỗi toàn cục ra sao? |
| 3 | Money Inbox | Lọc + phân trang | JPA, **N+1** (`@EntityGraph`, fetch join), keyset pagination, composite index, `EXPLAIN ANALYZE` | Aurora | Vì sao offset chậm ở trang sâu? Thứ tự cột trong composite index có quan trọng không? |
| 4 | Ghi chi tiêu / chuyển hũ | Cập nhật tiền nhất quán | `@Transactional` (proxy, self-invocation, rollback rules), isolation, `@Version`, retry, idempotency key, `BigDecimal` | Aurora | Vì sao gọi `@Transactional` nội bộ không có tác dụng? Optimistic vs pessimistic? Lost update? Vì sao không dùng `double`? |
| 5 | Hũ + gợi ý phân bổ | Domain logic (port `allocation.py`) | Rich domain model, value object `Money`, `@ParameterizedTest` | – | Anemic vs rich domain? Value object là gì? |
| 6 | Dashboard | Aggregate + read model | GROUP BY, window function, CQRS-lite, cache-aside | Valkey (P4) | CQRS là gì, khi nào nên dùng? Cache invalidation thế nào? |
| 7 | Import CSV | Batch | Spring Batch (chunk, skip, retry, restart), streaming file | S3 presigned, EventBridge, SQS | Xử lý file 1 triệu dòng mà không OOM? Job lỗi giữa chừng thì sao? |
| 8 | Cảnh báo bất thường | CUSUM/MAD, consumer | **Outbox**, `@ApplicationModuleListener`, idempotent consumer, thứ tự xử lý | SNS FIFO, SQS FIFO, DLQ (P5) | Dual-write problem? At-least-once vs exactly-once? Đảm bảo thứ tự thế nào? |
| 9 | Thông báo realtime | Đẩy SSE | `SseEmitter`, heartbeat, reconnect, pub/sub fan-out | ALB/CloudFront timeout, Valkey | SSE vs WebSocket vs polling? Scale SSE khi có nhiều instance? |
| 10 | Mục tiêu + what-if + lộ trình | Mô phỏng tài chính | Strategy pattern, Monte Carlo có seed, test tính toán | – | Test code có ngẫu nhiên thế nào? |
| 11 | Khép sổ tháng | Job định kỳ, idempotent | Timezone (`ZoneId`, `YearMonth`), batch theo trang, idempotency | EventBridge Scheduler → SQS | Cron chạy trên 2 instance thì sao (ShedLock vs scheduler bên ngoài)? |
| 12 | Chat AI | Spring AI tool calling + streaming | `ChatClient`, tool, `Flux` → SSE, rate limit token bucket, chat memory | Bedrock, Guardrails | Làm sao để LLM không bịa số? Chống prompt injection? Token bucket hoạt động thế nào? |
| 13 | Cổ phiếu + tối ưu danh mục | Gateway gọi Python | Circuit breaker, timeout, retry + backoff, bulkhead, `CompletableFuture` + virtual threads, contract test | Lambda (IAM invoke) | Các trạng thái của circuit breaker? Retry storm là gì? Virtual thread khác platform thread thế nào? |
| 14 | Audit log | Ghi lại thao tác | Spring AOP (JDK proxy vs CGLIB), MDC | CloudWatch Logs | AOP hoạt động thế nào? MDC có bị mất khi chạy `@Async` không? |
| 15 | Vận hành | Health, metric, trace | Actuator, Micrometer, OpenTelemetry | CloudWatch, X-Ray, ADOT | 3 trụ cột observability? Trace đi xuyên qua message queue thế nào? |
| 16 | Build & deploy | Container, pipeline | Multi-stage Docker, layered jar, JVM trong container (`MaxRAMPercentage`) | ECR, ECS Fargate, CDK, OIDC | Tối ưu image Java thế nào? Rolling vs blue/green? Giữ secret khi deploy ra sao? |
| 17 | Tách insights-worker | Microservice đầu tiên | Database-per-service (schema), eventual consistency, versioning event | SNS/SQS FIFO | Khi nào nên tách service? Distributed monolith là gì? |
| 18 | Hạ tầng như code | Viết CDK bằng Java | CDK construct, test hạ tầng | CloudFormation | IaC lợi gì? CDK vs Terraform? |

## 3. Kiểm tra độ phủ

| Kỹ năng từ JD | Phủ bởi | Ghi chú |
|---|---|---|
| Java core, OOP, Collections, Stream | #4, #5, #8, #10 | Thêm: record, sealed interface (event), pattern matching |
| Spring Boot / JPA / Security | #1–#4, #6 | ✅ |
| SQL + tối ưu | #3, #6 | ✅. Oracle PL/SQL: ❌ không phủ (PostgreSQL thay thế, khái niệm chuyển được) |
| REST API | toàn bộ | ✅ OpenAPI |
| Message queue / async | #7, #8, #11, #17 | ✅ dùng SNS/SQS. **Kafka:** bảng ánh xạ khái niệm + lab local (tùy chọn) |
| Redis | #6, #9, #12, #13 | ✅ Valkey (tương thích giao thức Redis) |
| Microservices | #17 | ✅ có chủ đích giới hạn ở 2 service + 1 Lambda |
| Docker, CI/CD | #16 | ✅ |
| Design pattern / SOLID | #5, #10, #13 | Strategy, Adapter, Template, Outbox |
| AWS | #1, #7, #8, #11–#18 | ✅ phủ rộng |
| Kubernetes | ❌ | Tùy chọn P6.6 (Helm + kind ở local), không deploy EKS vì chi phí |
| Observability | #15 | ✅ CloudWatch/X-Ray; Prometheus/Grafana chỉ dùng ở local (LGTM) |
| OAuth2/JWT | #1 | ✅ |
| API Gateway (Spring Cloud Gateway / Kong) | ❌ | Có chủ đích: CloudFront + ALB là đủ; giải thích được vì sao không cần |
| DDD / CQRS | #5, #6, module boundaries | DDD-lite, CQRS-lite |
| AI | #12 | ✅ Spring AI + Bedrock |
| Tiếng Anh | README tiếng Anh (P3.8), ADR | ⚠️ Tài liệu chính viết tiếng Việt; bản tóm tắt tiếng Anh cho recruiter |
| Terraform | ❌ | Dùng CDK; từng dùng Terraform ở bản hackathon |
