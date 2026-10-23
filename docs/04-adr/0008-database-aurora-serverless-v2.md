# ADR-0008: Aurora PostgreSQL Serverless v2 với scale-to-zero

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
Cần một RDBMS (JD yêu cầu SQL, JPA, transaction). Mẫu sử dụng của project: **phần lớn thời gian không có ai dùng**, chỉ có vài giờ mỗi ngày khi dev hoặc demo. Tài khoản tạo ngày 2026-04-09 nên **không** có free tier 750 giờ RDS kiểu cũ (chỉ áp dụng cho tài khoản tạo trước 15/07/2025). Giá Singapore lấy từ Pricing API ngày 2026-10-23:

| Lựa chọn | Đơn giá | Ước tính / tháng |
|---|---|---|
| RDS PostgreSQL db.t4g.micro Single-AZ | $0.025/giờ | khoảng **$18.3** + storage, chạy 24/7 |
| Aurora Serverless v2, 0.5 ACU chạy 24/7 | $0.20/ACU-giờ | khoảng **$73**: rất đắt nếu không pause được |
| Aurora Serverless v2, min 0 ACU, dùng khoảng 3 giờ/ngày | $0.20/ACU-giờ | khoảng **$9** + storage $0.11/GB-tháng |

## Quyết định
Dùng **Aurora PostgreSQL Serverless v2**, `minCapacity=0`, `maxCapacity=2`, `SecondsUntilAutoPause=300`, 1 writer. App được cấu hình để **không giữ kết nối nhàn rỗi** (Hikari `minimum-idle=0`, `connection-fetch=lazy` của Boot 4.1; health check không chạm DB).

**Guardrail chi phí:** alarm khi `ServerlessDatabaseCapacity > 0` liên tục 6 giờ; budget alarm. CDK có context `-c dbMode=rds` để chuyển sang RDS t4g.micro nếu pause không hoạt động như mong đợi.

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| RDS t4g.micro | Đơn giản, chi phí cố định khoảng $18. **Là phương án dự phòng.** Không được chọn làm mặc định vì đắt hơn khi ít dùng, và bỏ lỡ cơ hội học scale-to-zero |
| DynamoDB | Free tier tốt, nhưng không phải SQL; JD ngân hàng cần RDBMS, JPA, transaction nhiều bảng |
| Postgres tự chạy trên EC2 | Rẻ, nhưng phải tự lo backup, patch, HA; không AWS-native |
| Giữ Supabase | Không AWS-native; dữ liệu nằm ngoài tầm kiểm soát của IAM/VPC |

## Hệ quả
- ✅ Chi phí gần $0 khi không dùng; vẫn là PostgreSQL thật (window function, JSONB, pgvector).
- ✅ Bài học thực tế: connection pool và health check ảnh hưởng trực tiếp tới hóa đơn.
- ⚠️ **Request đầu tiên sau khi pause chậm** (resume khoảng vài giây đến khoảng 15 s). Chấp nhận được ở dev; UI hiển thị "đang khởi động".
- ⚠️ Nếu có thứ gì giữ kết nối thì Aurora không bao giờ pause → khoảng $73/tháng. Có alarm để phát hiện.
- ⚠️ Yêu cầu Aurora PostgreSQL ≥ 16.3 (chọn 17.x).

## Xem lại khi
Có traffic đều cả ngày (khi đó RDS hoặc Aurora provisioned rẻ hơn), hoặc độ trễ resume không chấp nhận được khi demo.
