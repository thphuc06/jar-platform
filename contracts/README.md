# contracts

Các hợp đồng giao tiếp giữa các thành phần. Đây là nguồn sự thật để sinh code và chạy contract test.

| Thư mục | Nội dung | Ai sinh / ai dùng |
|---|---|---|
| `openapi/jars-api.yaml` | OpenAPI 3.1 của jars-api | jars-api sinh (springdoc, bước P1.10) → web sinh TypeScript client |
| `events/*.schema.json` | JSON Schema của envelope + payload từng domain event ([event-catalog](../docs/02-architecture/event-catalog.md)) | jars-api, insights-worker (Phase 5) |
| `quant/*.schema.json` | Input/output của `quant-fn` | jars-api `market` ↔ `functions/quant-fn` (Phase 4) |

Quy tắc: đổi contract theo kiểu phá vỡ tương thích thì phải tăng version (`/v2`, `eventVersion`) và ghi chú trong PR.
