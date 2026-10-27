# contracts

Các hợp đồng giao tiếp giữa các thành phần. Đây là nguồn sự thật để sinh code và chạy contract test.

| Thư mục | Nội dung | Ai sinh / ai dùng |
|---|---|---|
| `openapi/jars-api.yaml` | OpenAPI 3.1 của jars-api (✅ P1.10, [ADR-0019](../docs/04-adr/0019-code-first-openapi-contract.md)) | jars-api sinh bằng springdoc; **không sửa tay**. Sau khi đổi API: `./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true` (trong `apps/jars-api`), review diff rồi commit cùng code. Build fail nếu file lệch với API. web sinh type: `npx openapi-typescript ../../contracts/openapi/jars-api.yaml -o src/api/schema.d.ts` (script `gen:api` ở P1.11) |
| `events/*.schema.json` | JSON Schema của envelope + payload từng domain event ([event-catalog](../docs/02-architecture/event-catalog.md)) | jars-api, insights-worker (Phase 5) |
| `quant/*.schema.json` | Input/output của `quant-fn` | jars-api `market` ↔ `functions/quant-fn` (Phase 4) |

Quy tắc: đổi contract theo kiểu phá vỡ tương thích thì phải tăng version (`/v2`, `eventVersion`) và ghi chú trong PR.
