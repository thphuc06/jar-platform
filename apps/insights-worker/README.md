# insights-worker (Phase 3+, backlog chưa lên lịch)

Service Java tách ra từ module `insights` của jars-api.
- Nhận `TransactionPosted` / `TransactionRecategorized` / `JarCreated` từ SQS FIFO `jars-insights-ledger.fifo`.
- Chạy CUSUM + MAD, cập nhật read model, phát `AnomalyDetected` lên SNS FIFO `jars-insights-events.fifo` qua outbox của chính nó.

> Trạng thái: **chưa có code**. Lý do tách và cách tách: [modules.md §5](../../docs/02-architecture/modules.md#5-khi-tách-insights-thành-insights-worker-phase-3-backlog-chưa-lên-lịch), [ADR-0003](../../docs/04-adr/0003-modular-monolith-spring-modulith.md).

Yêu cầu chính:
- Không mở port HTTP (chỉ có health cho ECS).
- Inbox `processed_messages` để xử lý idempotent.
- Schema DB riêng `insights`, DB user riêng.
