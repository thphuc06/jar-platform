# Glossary

## Nghiệp vụ

| Thuật ngữ | Giải thích |
|---|---|
| **6 Jars (6 chiếc hũ)** | Phương pháp chia thu nhập vào 6 hũ theo tỷ lệ gợi ý: **NEC** (Necessities, thiết yếu, khoảng 55%), **FFA** (Financial Freedom Account, tự do tài chính, 10%), **LTSS** (Long-Term Saving for Spending, tiết kiệm dài hạn, 10%), **EDU** (giáo dục, 10%), **PLAY** (hưởng thụ, 10%), **GIVE** (cho đi, 5%). Người dùng có thể chỉnh tỷ lệ |
| **Jar entry** | Một bút toán trong sổ cái của hũ (cộng hoặc trừ), chỉ được ghi thêm |
| **Jar period** | Kế hoạch và thực chi của một hũ trong một tháng |
| **Khép sổ tháng (month close)** | Tổng kết tháng cũ, tạo kỳ mới cho mọi hũ |
| **Đối soát sổ cái (ledger reconciliation)** | Job hằng đêm kiểm tra số dư lưu của từng hũ (`jars.balance`) có bằng tổng các bút toán (`SUM(jar_entries.amount)`) không. Chỉ phát hiện và báo (log `Ledger mismatch`, metric `jars.ledger.reconciliation.mismatches`, alarm), không tự sửa ([flows.md F6](02-architecture/flows.md#f6)) |
| **Risk profile** | Khẩu vị rủi ro (CONSERVATIVE / BALANCED / GROWTH) rút ra từ bảng hỏi onboarding |
| **Feasibility** | Mục tiêu có đạt được không với tốc độ tiết kiệm hiện tại; cần bao nhiêu mỗi tháng |
| **What-if scenario** | Mô phỏng khi thay đổi thu nhập hoặc tiết kiệm |
| **Roadmap (lộ trình)** | Kế hoạch theo giai đoạn để đạt mục tiêu (phase, milestone, hành động tiếp theo) |

## Thuật toán

| Thuật ngữ | Giải thích |
|---|---|
| **CUSUM** | Cumulative Sum control chart: cộng dồn độ lệch chuẩn hóa `(x − μ)/σ − k`, báo động khi tổng vượt ngưỡng `h`. Phát hiện **xu hướng tăng hoặc giảm kéo dài** thay vì một điểm lẻ. Tham số mặc định k = 0.5, h = 5 (từ bản cũ, đã cài ở `SpendingDetector`) |
| **MAD** | Median Absolute Deviation: đo độ phân tán, ít bị ảnh hưởng bởi ngoại lệ hơn độ lệch chuẩn. Một giao dịch là outlier nếu `|x − median| / MAD` vượt ngưỡng |
| **Monte Carlo (có seed)** | Mô phỏng ngẫu nhiên nhiều lần; cố định seed để test tái lập được |

## Kỹ thuật

| Thuật ngữ | Giải thích |
|---|---|
| **Modular monolith** | Một artifact deploy, bên trong chia module có ranh giới được kiểm tra |
| **Outbox (transactional outbox)** | Ghi event vào bảng trong **cùng transaction** với dữ liệu nghiệp vụ, rồi mới gửi ra ngoài, nhờ đó không mất event và không có event "ma" |
| **Inbox / idempotent consumer** | Ghi nhận ID message đã xử lý để bỏ qua bản trùng |
| **At-least-once** | Message có thể đến nhiều hơn 1 lần nhưng không mất |
| **Audit log** | Bảng `audit_log` append-only ghi ai làm gì, lúc nào. Aspect `@Audited` ghi một dòng trong cùng transaction với thay đổi nghiệp vụ, nên rollback thì không còn dòng nào ([ADR-0025](04-adr/0025-audit-log-aop-same-transaction.md)) |
| **Idempotency key** | Khóa do client gửi để server không thực hiện cùng một thao tác hai lần |
| **Optimistic locking** | Không khóa khi đọc; kiểm tra cột `version` khi ghi, nếu lệch thì báo xung đột |
| **Lost update** | Hai giao dịch cùng đọc rồi cùng ghi đè, làm mất một cập nhật |
| **IDOR** | Insecure Direct Object Reference: truy cập tài nguyên của người khác bằng cách đổi ID |
| **CQRS-lite** | Tách mô hình đọc (read model) khỏi mô hình ghi, cập nhật read model qua event |
| **SSE** | Server-Sent Events: HTTP response kéo dài, server đẩy từng event dạng text |
| **PKCE** | Proof Key for Code Exchange: bảo vệ flow Authorization Code cho client không giữ được secret (SPA, mobile) |
| **JWKS** | JSON Web Key Set: tập public key để xác thực chữ ký JWT |
| **Virtual threads** | Luồng nhẹ của JVM (Java 21+), cho phép code blocking chạy hàng nghìn request đồng thời |
| **Circuit breaker** | Ngắt lời gọi tới dịch vụ đang lỗi trong một thời gian để tránh lỗi lan rộng (trạng thái CLOSED → OPEN → HALF_OPEN) |

## AWS

| Thuật ngữ | Giải thích |
|---|---|
| **ACU** | Aurora Capacity Unit, khoảng 2 GiB RAM + CPU tương ứng; Serverless v2 tính tiền theo ACU-giờ |
| **Scale-to-zero / auto-pause** | Aurora Serverless v2 với min 0 ACU, tự dừng khi không có kết nối |
| **LCU** | Load Balancer Capacity Unit, đơn vị tính phí theo lưu lượng của ALB |
| **OAC** | Origin Access Control: chỉ CloudFront được đọc S3 bucket |
| **VPC origin** | CloudFront kết nối thẳng vào ALB/NLB/EC2 trong private subnet |
| **Inference profile** | ID model của Bedrock có định tuyến cross-region (`global.`, `us.`, `apac.`...) |
| **Guardrails** | Bộ lọc nội dung của Bedrock (chủ đề bị cấm, PII, nội dung độc hại) |
| **FIFO message group** | Các message cùng group được xử lý theo thứ tự (tương đương partition key của Kafka) |
| **DLQ** | Dead-letter queue, nơi chứa message xử lý lỗi quá số lần cho phép |
| **OIDC (GitHub → AWS)** | GitHub Actions đổi token OIDC lấy credential AWS tạm thời, không cần access key |
| **CDK bootstrap** | Tạo bucket và role mà CDK cần để deploy vào một account/region |
| **Task role vs execution role** | Task role là quyền của code ứng dụng; execution role là quyền của ECS agent (kéo image, đọc secret, ghi log) |
