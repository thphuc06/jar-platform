# jars-api

Modular monolith **Java 25 · Spring Boot 4.1 · Spring Modulith 2.1**. Đây là backend chính của Jars Platform.

> Trạng thái (P0.4, 2026-10-23): skeleton Spring Boot **4.1.2** / Java 25, có `GET /api/v1/health` và các actuator probe.

## Chạy nhanh

```bash
# cần JDK 25 (nếu máy có nhiều JDK thì trỏ JAVA_HOME tới bản 25)
./mvnw verify                 # build + chạy test
./mvnw spring-boot:run        # http://localhost:8080
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
├── Dockerfile                      # multi-stage, eclipse-temurin:25-jre, arm64, non-root (P3.1)
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
| Web | `spring-boot-starter-web`, `spring-boot-starter-validation` |
| Security | `spring-boot-starter-oauth2-resource-server` |
| Data | `spring-boot-starter-data-jpa`, `org.postgresql:postgresql`, `flyway-core`, `flyway-database-postgresql` |
| Modulith | `spring-modulith-starter-core`, `spring-modulith-starter-jpa`, `spring-modulith-starter-test` |
| AWS | `spring-cloud-aws-starter-sqs`, `-sns`, `-s3`, `-secrets-manager`, `spring-cloud-aws-modulith-events-sns` (P5), `software.amazon.awssdk:lambda` |
| AI | `spring-ai-starter-model-bedrock-converse` |
| Batch | `spring-boot-starter-batch` |
| Ops | `spring-boot-starter-actuator`, `spring-boot-starter-opentelemetry` |
| API docs | `springdoc-openapi-starter-webmvc-ui` (3.x, kiểm tra hỗ trợ Boot 4) |
| Test | `spring-boot-starter-test`, `spring-security-test`, `spring-boot-testcontainers`, `testcontainers` (postgresql, localstack), `archunit-junit5` |

Tài liệu: [modules](../../docs/02-architecture/modules.md) · [API contract](../../docs/03-api/api-contract.md) · [data model](../../docs/02-architecture/data-model.md).
