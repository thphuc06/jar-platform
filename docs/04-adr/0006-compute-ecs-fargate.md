# ADR-0006: Compute bằng Amazon ECS on Fargate (ARM64)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
Bản hackathon deploy bằng **App Runner** (stock agent) và ECS Fargate (backend). App Runner **ngừng nhận khách hàng mới từ 30/04/2026** và AWS khuyên chuyển sang ECS Express Mode. jars-api là một Spring Boot app chạy liên tục, có kết nối SSE dài, có SQS listener chạy nền và cần nối vào VPC để vào Aurora.

## Quyết định
Chạy `jars-api` (và `insights-worker` ở Phase 5) trên **ECS with Fargate**, kiến trúc **ARM64 (Graviton)**. Định nghĩa bằng CDK (`FargateService` + ALB internal tự cấu hình), **không dùng** ECS Express Mode.

## Các phương án đã cân nhắc

| Phương án | Ưu | Nhược | Kết luận |
|---|---|---|---|
| **App Runner** | Đơn giản nhất | **Không nhận khách hàng mới** từ 30/04/2026; không có tính năng mới | Loại |
| **EC2** (tự chạy Docker hoặc JAR) | Học được EC2, ASG, AMI; t4g.small rẻ ngang Fargate | Tự patch OS, tự cấu hình deploy, tự lo health/replace; deploy không có rolling sẵn | Loại cho app chính. EC2 vẫn là phương án dự phòng cho NAT (fck-nat), xem ADR-0013 |
| **ECS on EC2** (capacity provider) | Rẻ hơn khi tải lớn; học ASG | Vẫn phải quản lý instance; với 1 task thì không rẻ hơn | Ghi lại để thử sau |
| **ECS Express Mode** (11/2025) | Một lệnh có ngay Fargate service + ALB có TLS + scaling + log; hỗ trợ cả service public lẫn private; dùng chung ALB cho tối đa 25 service | Tự động hóa che mất các mảnh mà interviewer hay hỏi (target group, listener, SG, task role); chưa rõ cách ghép với CloudFront VPC origin theo thiết kế của mình | Loại cho bản chính, **tự dựng bằng CDK** để hiểu từng mảnh. Có thể thử Express Mode như bài tập so sánh |
| **EKS** | Kubernetes là điểm cộng trong JD | Control plane khoảng $0.10/giờ ≈ $73/tháng; vận hành phức tạp | Loại; học K8s ở local bằng kind (tùy chọn) |
| **Lambda + SnapStart** cho toàn bộ API | Gần $0 khi ít traffic | SSE/streaming dài và SQS listener chạy nền không hợp; cold start; giới hạn 15 phút | Loại cho API; **dùng cho `quant-fn`** |
| **ECS Fargate** ✅ | Không quản lý server; rolling deploy + circuit breaker; native blue/green (từ 7/2025); IAM theo task; ARM rẻ hơn x86 khoảng 20% | Không có free tier; khoảng $18/tháng cho 0.5 vCPU / 1 GB ở Singapore | **Chọn** |

## Hệ quả
- ✅ Kỹ năng ECS, task definition, IAM task role và ALB đúng với JD ("deploy trên AWS / K8s").
- ✅ Scale về 0 khi không dùng (`desiredCount=0`).
- ⚠️ JVM cần tối thiểu khoảng 1 GB RAM khi có Spring AI + Batch. Phải đặt `MaxRAMPercentage` và theo dõi OOM (exit code 137).
- ⚠️ Image phải build multi-arch hoặc arm64 (base `eclipse-temurin:21-jre` có arm64; JDK đổi từ 25 sang 21 theo [ADR-0019](0019-java-21-lts.md)).

## Xem lại khi
Cần nhiều service (≥ 5) và muốn dùng chung tool K8s; hoặc chi phí Fargate chiếm > 50% ngân sách.
