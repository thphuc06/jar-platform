# Data Model

> Schema được quản lý bằng **Flyway** (`apps/jars-api/src/main/resources/db/migration/V<n>__<mô_tả>.sql`). Tài liệu này mô tả **thiết kế đích**, còn migration là nguồn sự thật. Sau mỗi migration phải cập nhật lại tài liệu này.
> Thiết kế mới **không** dùng lại `schema.sql` của bản hackathon. Lý do và các điểm thay đổi xem mục 5.

**Migration đã có** (schema PostgreSQL `jars`, do Flyway tạo; app kết nối với `hikari.schema=jars`):

| Version | File | Nội dung | Bước |
|---|---|---|---|
| V1 | `V1__baseline_users.sql` | Bảng `users`: `id` UUID (app sinh), `cognito_sub` UNIQUE, `email` UNIQUE **không phân biệt hoa/thường** (unique index trên `lower(email)`), `locale` mặc định `vi-VN`, `created_at`/`updated_at` | P0.6 |

## 1. Quy ước

| Chủ đề | Quy ước | Lý do |
|---|---|---|
| Khóa chính | `UUID` sinh ở app theo **UUIDv7** (có thứ tự theo thời gian) | Không lộ số lượng bản ghi, index B-tree không bị phân mảnh như UUIDv4 |
| Tiền | `NUMERIC(19,2)` + cột `currency CHAR(3)`; trong Java dùng `BigDecimal` bọc trong record `Money` | Không bao giờ dùng `double` hay `float`. VND không có đơn vị lẻ nhưng vẫn để scale 2 để sau này mở rộng đa tiền tệ ([ADR-0014](../04-adr/0014-money-and-ledger-model.md)) |
| Dấu của số tiền | `transactions.amount` luôn **> 0**, hướng dòng tiền thể hiện qua cột `type`. `jar_entries.amount` **có dấu** (+ vào hũ, − ra khỏi hũ) | Dữ liệu người dùng nhập thì dễ hiểu, còn sổ cái thì dễ cộng dồn |
| Thời gian | `TIMESTAMPTZ`, lưu UTC; trong Java dùng `Instant` | Quy đổi sang giờ `Asia/Ho_Chi_Minh` chỉ khi tính "tháng" hoặc khi hiển thị |
| Tháng ngân sách | `DATE` = ngày đầu tháng theo giờ VN (ví dụ `2026-11-01`); trong Java dùng `YearMonth` | Tránh lệch tháng do múi giờ |
| Enum | `TEXT` + `CHECK` constraint; trong Java dùng `enum` + `@Enumerated(STRING)` | Dễ migrate hơn kiểu enum của PostgreSQL |
| Concurrency | Bảng có cập nhật đồng thời có cột `version BIGINT` (`@Version`) | Optimistic locking |
| Audit cột | `created_at`, `updated_at` (`@CreatedDate` / `@LastModifiedDate`) | |
| Xóa | Xóa mềm (`archived_at`) với `jars` và `goals`; **giao dịch không bao giờ xóa**, muốn sửa thì ghi bút toán điều chỉnh | Giữ toàn vẹn lịch sử tài chính |
| Phân quyền dữ liệu | Mọi bảng nghiệp vụ có `user_id`; mọi query đều lọc theo `user_id` | Chống IDOR |

## 2. ERD

```mermaid
erDiagram
    users ||--o{ risk_assessments : has
    users ||--o{ jars : owns
    users ||--o{ categories : defines
    users ||--o{ transactions : records
    users ||--o{ goals : sets
    users ||--o{ notifications : receives
    users ||--o{ chat_sessions : opens
    users ||--o{ statement_imports : uploads
    jars ||--o{ jar_entries : "ledger of"
    jars ||--o{ jar_periods : "budget per month"
    transactions ||--o{ jar_entries : "produces 1..2"
    categories ||--o{ transactions : classifies
    categories ||--o{ categories : parent
    jars ||--o| anomaly_detector_state : "CUSUM state"
    jars ||--o{ anomalies : flags
    transactions ||--o{ anomalies : "may trigger"
    chat_sessions ||--o{ chat_messages : contains
    statement_imports ||--o{ transactions : "imported into"

    users {
        uuid id PK
        text cognito_sub UK "JWT sub"
        text email UK
        text display_name
        text locale "vi-VN"
        timestamptz created_at
    }
    risk_assessments {
        uuid id PK
        uuid user_id FK
        jsonb answers
        int score
        text risk_level "CONSERVATIVE|BALANCED|GROWTH"
        timestamptz assessed_at
    }
    jars {
        uuid id PK
        uuid user_id FK
        text code "NEC|FFA|LTSS|EDU|PLAY|GIVE|CUSTOM"
        text name
        numeric target_pct "0..100"
        numeric balance "cached = SUM(jar_entries)"
        char currency
        bigint version "optimistic lock"
        timestamptz archived_at
    }
    jar_entries {
        uuid id PK
        uuid jar_id FK
        uuid transaction_id FK "nullable"
        text entry_type "ALLOCATE|SPEND|TRANSFER_IN|TRANSFER_OUT|ADJUST"
        numeric amount "signed"
        numeric balance_after
        timestamptz created_at
    }
    jar_periods {
        uuid id PK
        uuid jar_id FK
        date period_month
        numeric planned_amount
        numeric spent_amount
        timestamptz closed_at
    }
    categories {
        uuid id PK
        uuid user_id FK "null = system default"
        uuid parent_id FK
        text name
        text kind "EXPENSE|INCOME"
    }
    transactions {
        uuid id PK
        uuid user_id FK
        text type "INCOME|EXPENSE|JAR_TRANSFER"
        numeric amount ">0"
        char currency
        uuid jar_id FK "nullable for INCOME"
        uuid category_id FK
        text counterparty
        text note
        timestamptz occurred_at
        text source "MANUAL|IMPORT"
        uuid import_id FK
        timestamptz created_at
    }
    goals {
        uuid id PK
        uuid user_id FK
        text name
        numeric target_amount
        date target_date
        uuid funding_jar_id FK
        text status "ACTIVE|ACHIEVED|ARCHIVED"
    }
    anomaly_detector_state {
        uuid jar_id PK
        numeric cusum_pos
        numeric cusum_neg
        numeric ref_mean
        numeric ref_sigma
        numeric k_param
        numeric h_param
        int sample_count
        bigint version
    }
    anomalies {
        uuid id PK
        uuid user_id FK
        uuid jar_id FK
        uuid transaction_id FK
        text detector "CUSUM|MAD"
        text direction "UP|DOWN"
        numeric score
        numeric threshold
        text status "OPEN|CONFIRMED|EXPECTED|FALSE_POSITIVE"
        timestamptz detected_at
    }
    notifications {
        uuid id PK
        uuid user_id FK
        text type
        text severity "LOW|MEDIUM|HIGH"
        text title
        text body
        text ref_type
        uuid ref_id
        timestamptz read_at
    }
    chat_sessions {
        uuid id PK
        uuid user_id FK
        text title
    }
    chat_messages {
        uuid id PK
        uuid session_id FK
        text role "USER|ASSISTANT|TOOL"
        text content
        jsonb tool_calls
        int tokens_in
        int tokens_out
    }
    statement_imports {
        uuid id PK
        uuid user_id FK
        text s3_key
        text status "PENDING|RUNNING|COMPLETED|FAILED"
        int rows_total
        int rows_ok
        int rows_failed
    }
```

## 3. Bảng kỹ thuật (không có trong ERD)

| Bảng | Module | Mục đích |
|---|---|---|
| `idempotency_keys` (`user_id`, `key`) PK, `request_hash`, `status_code`, `response_body JSONB`, `expires_at` | ledger (dùng chung qua filter) | Header `Idempotency-Key` cho các POST làm thay đổi tiền ([api-contract.md](../03-api/api-contract.md#4-idempotency)) |
| `processed_messages` (`consumer`, `message_id`) PK, `processed_at` | platform | **Inbox**: bỏ qua message SQS trùng (at-least-once) |
| `event_publication` (+ `_archive`) | Spring Modulith tự quản lý | **Outbox**: event được ghi cùng transaction nghiệp vụ |
| `monthly_jar_spend` (`user_id`, `jar_id`, `month`) PK, `spent`, `txn_count` | insights | **Read model** cho dashboard (CQRS-lite), cập nhật từ event |
| `allocation_decisions` | budgeting | Lưu user chấp nhận, sửa hay bỏ qua gợi ý phân bổ, dùng để cải thiện gợi ý |
| `audit_log` | platform | `actor`, `action`, `entity_type`, `entity_id`, `trace_id`, `payload JSONB` |
| `BATCH_*` | Spring Batch tự quản lý | Metadata của job import |
| `SPRING_AI_CHAT_MEMORY` (tùy chọn) | advisor | Nếu dùng JDBC chat memory repository của Spring AI |

## 4. Index chính

| Index | Phục vụ |
|---|---|
| `transactions (user_id, occurred_at DESC, id DESC)` | Money Inbox, keyset pagination |
| `transactions (user_id, jar_id, occurred_at)` | Chi tiêu theo hũ trong tháng |
| `jar_entries (jar_id, created_at)` | Đối soát số dư hũ |
| `notifications (user_id, read_at NULLS FIRST, created_at DESC)` | Danh sách thông báo chưa đọc |
| `anomalies (user_id, status, detected_at DESC)` | Màn hình Alerts |
| `users (cognito_sub)` UNIQUE | Map JWT → user |

## 5. Khác gì so với `schema.sql` của bản hackathon?

| Bản cũ | Bản mới | Vì sao |
|---|---|---|
| `jars.allocated_amount`, `used_amount` bị cập nhật đè, `remaining_amount` là cột tính | `jar_entries` chỉ ghi thêm + `jars.balance` cache + `version` | Cập nhật đè gây **lost update** khi có 2 request đồng thời và không còn lịch sử để đối soát |
| `transactions.jar_id`, `category_id` NOT NULL cho mọi loại | `jar_id` có thể null với INCOME; có thêm `type` | Thu nhập chưa vào hũ nào; chuyển hũ là một loại giao dịch riêng |
| `direction debit/credit` + `affects_jar_balance` | `type` + bút toán `jar_entries` | Bỏ cờ boolean khó hiểu |
| Bảng `tier1_*` riêng cho CUSUM | `anomaly_detector_state`, `anomalies` thuộc module insights | Đặt tên theo nghiệp vụ, không theo "tier" kỹ thuật |
| `*_log` nhiều bảng JSONB cho trace của agent | `audit_log` + trace qua OpenTelemetry | Không lưu payload của LLM vào DB nghiệp vụ |
| Supabase anon key truy cập trực tiếp | Chỉ `jars-api` truy cập DB | Kiểm soát quyền tập trung |
