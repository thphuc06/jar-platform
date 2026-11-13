# ADR-0003: Modular monolith với Spring Modulith; chỉ tách một service ở Phase 5

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
JD Java ở Việt Nam hay ghi "microservices". Nhưng project có 1 người phát triển, ngân sách AWS khoảng $197, và nghiệp vụ chính (ghi giao dịch, trừ số dư hũ) cần **nhất quán mạnh**. Tách sớm sẽ gây ra distributed transaction, chi phí mỗi service (mỗi task Fargate khoảng $18/tháng + IP), và tốn thời gian vào hạ tầng thay vì nghiệp vụ.

## Quyết định
- `jars-api` là **modular monolith**: các module `identity`, `ledger`, `budgeting`, `goals`, `insights`, `notification`, `advisor`, `imports` (`market` đã bỏ theo ADR-0024), cùng `shared` ([modules.md](../02-architecture/modules.md)).
- **Spring Modulith 2.1** kiểm tra ranh giới module trong test, cung cấp **event publication registry** (outbox) và `@ApplicationModuleTest`.
- **Phase 5** tách đúng một service: `insights-worker`. Lý do chọn insights: nó vốn bất đồng bộ, chịu được trễ, tính toán độc lập, chỉ cần event `TransactionPosted`. Đây là đường cắt "tự nhiên" nhất.

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| Microservices ngay từ đầu (identity, ledger, budget, goals, ai... mỗi cái một service) | Distributed monolith; saga cho mọi thao tác ghi tiền; chi phí × N; debug khó |
| Monolith không chia module | Nhanh lúc đầu nhưng thành "big ball of mud"; không tách được về sau; không thể hiện được tư duy thiết kế |
| Tách nhiều service ở Phase 5 | Mỗi service thêm vào cần lý do; 1 service đủ để học và thể hiện event-driven, idempotency, DLQ |

## Hệ quả
- ✅ Một transaction ACID cho phần tiền; deploy đơn giản; chi phí thấp.
- ✅ Câu chuyện phỏng vấn tốt: "biết khi nào **không** nên dùng microservices, và tách khi có lý do".
- ⚠️ Kỷ luật ranh giới phụ thuộc vào test của Modulith. Bắt buộc có `ModularityTests` trong CI.
- ⚠️ Một module chậm có thể ảnh hưởng cả process. Giảm thiểu: listener async, bulkhead cho lời gọi ra ngoài.

## Xem lại khi
Một module cần scale khác hẳn phần còn lại (ví dụ advisor ngốn CPU hoặc bộ nhớ), hoặc cần release cycle riêng.
