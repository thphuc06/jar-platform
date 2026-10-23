# ADR-0013: Không dùng NAT Gateway ở môi trường dev; ECS task ở public subnet, khóa bằng security group

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
ECS task cần gọi ra ngoài: ECR (pull image), CloudWatch Logs, Secrets Manager, SQS/SNS, Bedrock, Lambda và JWKS của Cognito. Mô hình "chuẩn" là đặt task ở private subnet và đi ra qua NAT Gateway. Giá ở Singapore (Pricing API, 2026-10-23):

| Phương án đi ra internet | Đơn giá | Ước tính / tháng |
|---|---|---|
| NAT Gateway (1 AZ) | $0.059/giờ + phí xử lý dữ liệu | **≈ $43** + data |
| VPC interface endpoints (khoảng 7 endpoint × 2 AZ) | $0.013/giờ mỗi endpoint mỗi AZ | **≈ $133** (và vẫn không gọi được JWKS hay Internet) |
| fck-nat trên EC2 t4g.nano | $0.0053/giờ + IPv4 $0.005/giờ | **≈ $7.5** (phải tự vận hành) |
| Task có IP public (không NAT) | $0.005/giờ mỗi IPv4 | **≈ $3.65** mỗi task |

## Quyết định
Ở `dev`, ECS task chạy trong **public subnet với `assignPublicIp=ENABLED`**. Inbound của task **chỉ cho phép từ security group của ALB**. ALB là **internal** (không có IP public). DB và cache nằm ở subnet isolated. **Không có NAT Gateway.**

## Các phương án đã cân nhắc
Xem bảng trên. NAT Gateway đúng chuẩn nhưng chiếm khoảng 1/4 credit mỗi tháng. Interface endpoint còn đắt hơn. fck-nat rẻ nhưng thêm một thứ phải vận hành (EC2, AMI, ASG, route table), có thể làm bài tập về EC2 sau.

## Hệ quả
- ✅ Tiết kiệm khoảng $40/tháng.
- ✅ Security group vẫn chặn mọi inbound từ internet vào task. IP public **không** có nghĩa là truy cập được.
- ⚠️ Không đạt tiêu chuẩn "defense in depth" của ngân hàng: nếu SG cấu hình sai thì task bị lộ. Giảm thiểu: CDK test + `cdk-nag` kiểm tra rule SG; không bao giờ mở `0.0.0.0/0` inbound.
- ⚠️ Mỗi task tốn thêm $3.65/tháng tiền IPv4.
- 📌 **Đường nâng cấp** (ghi lại để kể khi phỏng vấn): prod sẽ dùng private subnet + NAT Gateway mỗi AZ (HA) + gateway endpoint cho S3 (miễn phí) + interface endpoint cho các dịch vụ có lưu lượng lớn.

## Xem lại khi
Có môi trường `prod`, hoặc có yêu cầu compliance, hoặc có ngân sách > $100/tháng.
