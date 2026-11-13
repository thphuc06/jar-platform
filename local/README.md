# local

Docker Compose cho môi trường dev local. Chi tiết: [local-development.md](../docs/05-operations/local-development.md).

| File | Nội dung | Từ bước |
|---|---|---|
| [compose.yaml](compose.yaml) | `postgres:17-alpine` (db/user `jars`, password chỉ dùng local), volume `pgdata`, healthcheck | P0.6 |

`./mvnw spring-boot:run` (trong `apps/jars-api`, profile `local`) **tự khởi động** file này. Chạy tay thì dùng `docker compose -f local/compose.yaml up -d`.

Hiện compose chỉ có `postgres`. Valkey, LocalStack, `lgtm` và Kafka chưa được thêm (và Valkey không còn kế hoạch dùng: [ADR-0023](../docs/04-adr/0023-advisor-guardrail-and-rate-limit.md)); nếu thêm LocalStack thì cần `LOCALSTACK_AUTH_TOKEN` trong `local/.env` (đã gitignore).
