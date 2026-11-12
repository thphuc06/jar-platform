# jars-api

Modular monolith **Java 21 · Spring Boot 4.1 · Spring Modulith 2.1**. Đây là backend chính của Jars Platform.

> Trạng thái (2026-11-12): module `identity`, `ledger`, `insights`, `notification` đã chạy (xem [roadmap](../../docs/06-plan/roadmap.md)); ranh giới module kiểm tra bằng Spring Modulith, format bằng Spotless, coverage domain/service ≥ 80% bằng JaCoCo; Spring Boot **4.1.2** / Java 21; PostgreSQL 17 + Flyway (schema `jars`), OpenAPI commit ở `contracts/openapi`, Dockerfile arm64.

## Chạy nhanh

```bash
# cần JDK 21 (nếu máy có nhiều JDK thì trỏ JAVA_HOME tới bản 21; enforcer sẽ báo lỗi nếu dùng JDK thấp hơn)
./mvnw verify                 # build + test (cần Docker) + Modulith verify + Spotless check
./mvnw spotless:apply         # format code trước khi commit
./mvnw spring-boot:run        # profile local: tự chạy ../../local/compose.yaml rồi Flyway migrate; http://localhost:8080
curl -X POST localhost:8080/dev/token -H 'Content-Type: application/json' -d '{"sub":"demo"}'   # token local, không cần Cognito
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--jars.seed.enabled=true"   # + dữ liệu mẫu cho subject demo
./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true   # sinh lại contracts/openapi/jars-api.yaml sau khi đổi API
curl localhost:8080/api/v1/health              # {"status":"UP","service":"jars-api","version":"0.0.1-SNAPSHOT"}
curl localhost:8080/actuator/health/readiness  # {"status":"UP"} (probe cho ALB/ECS)
```

| Endpoint | Dùng cho | Ghi chú |
|---|---|---|
| `GET /api/v1/health` | Client, smoke test qua CloudFront | Public, trả version lấy từ `build-info` |
| `GET /actuator/health/readiness` | ALB target group | Không được chạm DB (xem aws-infrastructure §4) |
| `GET /actuator/health/liveness` | ECS container health | |
| `GET /actuator/info` | Debug | Chỉ expose `health`, `info` |

## Cấu trúc dự kiến

```
apps/jars-api/
├── mvnw, .mvn/                     # Maven Wrapper
├── pom.xml                         # BOM: spring-boot 4.1.x, spring-ai 2.0.x, spring-cloud-aws 4.2.x, spring-modulith 2.1.x
├── Dockerfile                      # multi-stage, eclipse-temurin:21-jre, arm64, non-root
└── src/
    ├── main/java/io/github/thphuc06/jars/
    │   ├── JarsApplication.java
    │   ├── platform/               # security, error handling, audit, observability config
    │   ├── shared/                 # Money, UserId, ProblemTypes
    │   ├── identity/ ledger/ budgeting/ goals/ insights/
    │   └── notification/ advisor/ market/ imports/
    ├── main/resources/
    │   ├── application.yaml        # chung
    │   ├── application-local.yaml
    │   ├── application-aws.yaml
    │   └── db/migration/           # Flyway V<n>__*.sql
    └── test/java/...               # unit, slice, module, integration, ModularityTests
```

## Dependencies chính

| Nhóm | Artifact |
|---|---|
| Web | `spring-boot-starter-webmvc`, `spring-boot-starter-validation` (Boot 4 tách starter theo module: `web` cũ nay là `webmvc`) |
| Security | `spring-boot-starter-oauth2-resource-server` |
| Data | `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`, `org.postgresql:postgresql`, `flyway-database-postgresql` |
| Modulith | `spring-modulith-starter-core`, `spring-modulith-starter-jpa`, `spring-modulith-starter-test` |
| AWS | `spring-cloud-aws-starter-sqs`, `-sns`, `-s3`, `-secrets-manager`, `spring-cloud-aws-modulith-events-sns` (P5), `software.amazon.awssdk:lambda` |
| AI | `spring-ai-starter-model-bedrock-converse` |
| Batch | `spring-boot-starter-batch` |
| Ops | `spring-boot-starter-actuator`, `spring-boot-starter-opentelemetry` |
| API docs | `springdoc-openapi-starter-webmvc-ui` (3.x, kiểm tra hỗ trợ Boot 4) |
| Test | các starter `*-test` theo module (`spring-boot-starter-webmvc-test`, `-jdbc-test`, `-flyway-test`...), `spring-security-test`, `spring-boot-testcontainers`, `testcontainers` (postgresql, localstack), `archunit-junit5` |

Tài liệu: [modules](../../docs/02-architecture/modules.md) · [API contract](../../docs/03-api/api-contract.md) · [data model](../../docs/02-architecture/data-model.md).
