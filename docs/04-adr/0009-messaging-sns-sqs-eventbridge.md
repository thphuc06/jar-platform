# ADR-0009: Outbox → SNS FIFO → SQS FIFO cho domain event; EventBridge cho platform event; không dùng MSK

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
- Cần giao tiếp bất đồng bộ: giao dịch → phát hiện bất thường → thông báo; S3 upload → import; lịch khép sổ tháng.
- JD hay ghi **Kafka**. Nhưng Amazon MSK Serverless khoảng $0.75/giờ cluster (≈ $550/tháng), còn MSK provisioned nhỏ nhất cũng hàng chục USD/tháng cho ≥ 2 broker. Cả hai đều vượt hoặc ăn gần hết $197 credit.
- Spring Modulith có outbox (event publication registry). **Spring Cloud AWS 4.2** có module `spring-cloud-aws-modulith-events-sns` / `-sqs` để externalize event sang SNS/SQS. Routing key được dùng làm **message group ID**, với điều kiện topic/queue là **FIFO**.

## Quyết định
1. **Domain event** ghi qua outbox của Spring Modulith (bảng `event_publication`, cùng transaction).
2. Phase 0–4: tiêu thụ **in-process** (`@ApplicationModuleListener`).
3. Phase 5: event cần ra khỏi process được đánh `@Externalized("<topic>::#{#this.userId()}")` → **SNS FIFO** → các **SQS FIFO** subscribe (mỗi consumer một queue, có DLQ).
4. **Platform event** (S3 Object Created, lịch chạy) dùng **EventBridge** (rule + Scheduler) → SQS standard → `@SqsListener`.
5. Consumer luôn **idempotent** (bảng `processed_messages`).
6. Kafka chỉ xuất hiện ở **lab local tùy chọn** (`spring-modulith-events-kafka`, Kafka KRaft trong docker compose) để chứng minh outbox không phụ thuộc broker.

## Các phương án đã cân nhắc

| Phương án | Ưu | Nhược | Kết luận |
|---|---|---|---|
| Amazon MSK (Serverless / provisioned) | Đúng keyword "Kafka"; replay được | Chi phí vượt ngân sách; vận hành nặng | Loại |
| Kafka tự chạy trên EC2 | Rẻ hơn MSK | Tự vận hành, không AWS-native, 1 broker không có HA | Loại |
| Kinesis Data Streams | AWS-native, có thứ tự theo shard, replay | Tính phí theo shard-giờ (on-demand có phí cố định); quá mức cần thiết cho event nghiệp vụ ít ỏi | Loại |
| Chỉ EventBridge (custom bus) cho domain event | Lọc rule mạnh, schema registry | **Không đảm bảo thứ tự**; CUSUM cần xử lý giao dịch theo thứ tự của từng user | Dùng cho platform event, không dùng cho domain event |
| Chỉ SQS (không có SNS) | Đơn giản | Một event có nhiều consumer thì producer phải gửi nhiều queue | Loại |
| **SNS FIFO → SQS FIFO** ✅ | Fan-out; có thứ tự theo group; dedup; DLQ; gần $0 ở quy mô này; có tích hợp sẵn với Spring Modulith | Không replay; throughput FIFO có giới hạn (dư sức ở quy mô này) | **Chọn** |

## Hệ quả
- ✅ Chi phí gần như bằng 0; thứ tự theo user; DLQ có sẵn.
- ✅ Học được outbox, idempotency, DLQ, eventual consistency, đều áp dụng được khi chuyển sang Kafka.
- ⚠️ Không có replay: muốn rebuild read model `monthly_jar_spend` thì phải chạy job đọc lại từ bảng `transactions`.
- ⚠️ Phỏng vấn hỏi Kafka thì cần nắm bảng ánh xạ khái niệm trong [event-catalog.md §6](../02-architecture/event-catalog.md#6-ánh-xạ-sang-khái-niệm-kafka-để-trả-lời-phỏng-vấn) và làm lab local.

## Ghi chú triển khai
- **P1.4 (2026-10-26):** registry dùng bản lưu **JDBC** (`spring-modulith-starter-jdbc`), không dùng bản JPA. Lý do: module JDBC có sẵn file DDL chính thức cho từng database và từng version schema (`schemas/v2/schema-postgresql.sql`), nên Flyway V2 chép nguyên và quản lý được; còn bản JPA là một entity mà Hibernate phải khớp với bảng tự viết khi `ddl-auto=validate`. Cả hai đều ghi trong **cùng transaction** với JPA (`JpaTransactionManager` dùng chung connection với JDBC). `completion-mode=delete`: outbox là hàng đợi việc, không phải lịch sử.

## Xem lại khi
Cần replay hoặc stream processing (windowing, join); hoặc throughput vượt giới hạn của FIFO; hoặc có ngân sách cho MSK.
