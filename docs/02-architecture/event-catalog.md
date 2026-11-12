# Event Catalog

## 1. Hai loại event

| Loại | Ví dụ | Đi qua đâu |
|---|---|---|
| **Domain event**: điều đã xảy ra trong nghiệp vụ | `TransactionPosted`, `AnomalyDetected` | Phase 0–4: **in-process** qua Spring Modulith (outbox trong bảng `event_publication`). Phase 5: externalize ra **SNS FIFO → SQS FIFO** |
| **Platform event**: điều do AWS phát ra | S3 `Object Created`, lịch chạy của EventBridge Scheduler | EventBridge → SQS → `@SqsListener` trong jars-api |

## 2. Envelope (khi đã externalize)

Mọi domain event ra khỏi process đều có cấu trúc chung (JSON, đặt trong message body). Schema nằm ở `contracts/events/*.schema.json`.

```json
{
  "eventId": "01933b6e-7c1a-7f3e-9d2b-8a1f0c4e5d6a",
  "eventType": "TransactionPosted",
  "eventVersion": 1,
  "occurredAt": "2026-11-15T03:21:09.123Z",
  "userId": "01932f00-0000-7000-8000-000000000001",
  "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
  "payload": { }
}
```

- `eventId` (UUIDv7) là **khóa idempotency** phía consumer (bảng `processed_messages`).
- `traceparent` (W3C) giúp nối trace từ request HTTP qua event sang consumer.
- `eventVersion` tăng khi đổi schema **theo cách phá vỡ tương thích**. Thêm field optional thì không cần tăng. Consumer phải bỏ qua field lạ (`FAIL_ON_UNKNOWN_PROPERTIES=false`).

## 3. Danh sách domain event

| Event | Phát bởi | Nghe bởi | Payload chính | Externalize (Phase 5) |
|---|---|---|---|---|
| `UserRegistered` | identity | ledger (tạo 6 hũ mặc định) | `userId`, `locale` | Không. **Nghe đồng bộ trong cùng transaction** với việc tạo user (`@EventListener`, không phải `@ApplicationModuleListener`), để user không bao giờ tồn tại mà thiếu 6 hũ |
| `RiskProfileAssessed` | identity | advisor (ngữ cảnh chat) | `riskLevel`, `score` | Không |
| `TransactionPosted` | ledger | insights (CUSUM/MAD, read model), budgeting (đã chi của kỳ) | `transactionId`, `type`, `amount`, `currency`, `jarId`, `categoryId`, `occurredAt`, `source` | **Có** → `jars-ledger-events.fifo`, group = `userId` |
| `TransactionRecategorized` | ledger | insights | `transactionId`, `oldCategoryId`, `newCategoryId`, `oldJarId`, `newJarId` | **Có** |
| `JarCreated` | ledger | insights (khởi tạo detector state) | `jarId`, `code` | **Có** |
| `MonthClosed` | budgeting | notification, insights | `month`, `summary` (theo từng hũ) | Không |
| `AnomalyDetected` | insights | notification | `anomalyId`, `jarId`, `detector`, `direction`, `score`, `threshold`, `transactionId` | **Có** → `jars-insights-events.fifo` |
| `StatementImportCompleted` | imports | notification | `importId`, `rowsOk`, `rowsFailed` | Không |
| `GoalCreated` | goals | (dự phòng) | `goalId`, `targetAmount`, `targetDate` | Không |

## 4. Platform event

| Nguồn | Rule / Schedule | Đích | Message mà jars-api nhận |
|---|---|---|---|
| S3 `jars-dev-statements`, prefix `uploads/{userId}/{importId}.csv` | Rule `statement-uploaded`: `source=aws.s3`, `detail-type=Object Created` | SQS `jars-imports` | Event EventBridge gốc (`detail.object.key`). jars-api tách `userId` / `importId` từ key **rồi kiểm tra với bảng `statement_imports`** (không tin key) |
| EventBridge Scheduler `month-close` | `cron(5 0 1 * ? *)`, TZ `Asia/Ho_Chi_Minh` | SQS `jars-jobs` | `{"job":"MONTH_CLOSE","month":"2026-10"}` |

## 5. Đảm bảo giao nhận

| Đảm bảo | Cách đạt được |
|---|---|
| **Không mất event khi DB commit** | Outbox: Spring Modulith ghi `event_publication` **trong cùng transaction** với dữ liệu nghiệp vụ. Nếu publish ra SNS lỗi thì bản ghi vẫn ở trạng thái chưa hoàn thành và được gửi lại (khi restart hoặc qua `IncompleteEventPublications`) |
| **Không phát event "ma" khi DB rollback** | Listener dùng `@TransactionalEventListener` (AFTER_COMMIT), nên rollback thì không có gì được phát |
| **At-least-once** | SQS chỉ xóa message sau khi listener xử lý thành công |
| **Idempotent consumer** | Kiểm tra rồi ghi `processed_messages(consumer, eventId)` trong **cùng transaction** với xử lý nghiệp vụ; nếu đã có thì bỏ qua |
| **Thứ tự** | FIFO + message group = `userId`: các giao dịch của một user được xử lý **theo thứ tự**, các user khác nhau chạy song song. CUSUM cần thứ tự này |
| **Poison message** | Sau `maxReceiveCount=5` lần lỗi → DLQ; có alarm khi DLQ > 0; quy trình xử lý ở [runbook.md](../05-operations/runbook.md#xử-lý-message-trong-dlq) |
| **Dedup phía SNS/SQS FIFO** | Content-based deduplication trong cửa sổ 5 phút. Đây chỉ là lớp phụ, **không thay thế** được idempotent consumer |

## 6. Ánh xạ sang khái niệm Kafka (để trả lời phỏng vấn)

JD thường yêu cầu Kafka. Project này dùng SNS/SQS FIFO vì chi phí ([ADR-0009](../04-adr/0009-messaging-sns-sqs-eventbridge.md)), nhưng các khái niệm tương đương nhau:

| Kafka | Trong project này | Khác biệt cần biết |
|---|---|---|
| Topic | SNS FIFO topic | SNS chỉ đẩy (push), không lưu lại để đọc lại; Kafka có retention và replay |
| Partition key | Message group ID (`userId`) | Kafka cố định số partition; FIFO group không giới hạn số lượng |
| Consumer group | Mỗi SQS queue subscribe vào topic | Mỗi queue nhận một bản copy, giống mỗi consumer group đọc topic |
| Offset commit | `DeleteMessage` sau khi xử lý xong | Kafka commit theo offset; SQS xác nhận theo từng message |
| Dead letter topic | SQS DLQ (`redrivePolicy`) | SQS có redrive về queue gốc ngay trên console |
| Replay từ đầu | **Không có sẵn** → muốn rebuild read model phải đọc lại từ DB | Đây là điểm Kafka mạnh hơn |
| Exactly-once (transactions) | Không có → at-least-once + idempotent consumer | Thực tế phần lớn hệ thống Kafka cũng dùng cách này |

**Lab Kafka (tùy chọn, Phase 6):** chạy Kafka (KRaft) bằng docker compose ở local, thêm `spring-modulith-events-kafka` và chuyển externalization sang Kafka bằng profile `kafka`. Như vậy chứng minh được outbox không phụ thuộc broker. **Không** deploy MSK.
