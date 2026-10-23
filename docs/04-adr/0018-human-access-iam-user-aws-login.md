# ADR-0018: Truy cập của người dùng bằng IAM user + MFA + `aws login` (không dùng IAM Identity Center)

- **Trạng thái:** Accepted (**hoãn thực hiện**: ngày 2026-10-23 chủ repo quyết định tạm dùng root, đã có MFA, qua `aws login` trong giai đoạn dev)
- **Ngày:** 2026-10-23
- **Điều kiện bắt buộc phải thực hiện:** trước khi bật CI/CD deploy (P3.6), hoặc khi có người thứ hai cần truy cập account, hoặc khi phát hiện bất thường trong CloudTrail. Trong lúc hoãn, vẫn **vô hiệu hóa access key dài hạn** của `phuc-account` (không có MFA) vì đó là rủi ro lớn nhất hiện tại.
- **Liên quan:** thay đổi bước P0.2 trong roadmap và [deployment.md §1](../05-operations/deployment.md#1-chuẩn-bị-tài-khoản-làm-1-lần)

## Bối cảnh
Thiết kế ban đầu định thay root bằng **IAM Identity Center**. Khi bắt đầu Phase 0 thì phát hiện:
- Tài khoản `626212071763` là **standalone** (không thuộc AWS Organizations). Theo tài liệu AWS, *"Account instances do not support permission sets and therefore do not support access to AWS accounts"*. Muốn dùng Identity Center để vào AWS account thì cần **organization instance**, nghĩa là phải có AWS Organizations.
- Theo FAQ của AWS Free Tier, tham gia Organizations hoặc Control Tower làm **credit hết hạn ngay**. Vậy là mất $197.
- Trạng thái kiểm tra ngày 2026-10-23: root đã bật MFA và không có access key. IAM user `phuc-account` có `AdministratorAccess` và đăng nhập console được, nhưng **chưa bật MFA** và có **1 access key dài hạn đang Active** (lần dùng cuối 2026-05-15, gọi Bedrock).
- AWS CLI ≥ 2.32 có lệnh **`aws login`** (11/2025): dùng chính phiên đăng nhập console (root, IAM user hoặc federated) để lấy **credential tạm thời** cho CLI/SDK, tự refresh tối đa 12 giờ. Máy dev đang có CLI 2.35.5.

## Quyết định
- Dùng **IAM user `phuc-account`** cho công việc hằng ngày, **bắt buộc bật MFA**.
- CLI/SDK lấy credential bằng **`aws login --profile jars-dev`**, không dùng access key dài hạn. Profile `jars-dev` đặt region `ap-southeast-1`.
- **Vô hiệu hóa** access key cũ của `phuc-account`, theo dõi 7 ngày, rồi xóa.
- Root: giữ MFA, không tạo access key, chỉ dùng cho tác vụ bắt buộc phải là root (billing, đóng account...).
- Giai đoạn dev vẫn dùng `AdministratorAccess`. Thu hẹp quyền khi có môi trường khác.

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| IAM Identity Center (organization instance) | Cần AWS Organizations, làm mất credit |
| IAM Identity Center (account instance) | Không hỗ trợ permission set, nên không vào được AWS account |
| Tiếp tục dùng root qua `aws login` | Vi phạm best practice; root có quyền không giới hạn và không thể hạn chế bằng policy |
| IAM user + access key dài hạn trong `~/.aws/credentials` | Key sống lâu và có thể lộ (đúng kiểu sự cố của repo cũ) |

## Hệ quả
- ✅ Không còn credential dài hạn nào cho con người; có MFA; không mất credit.
- ✅ Câu chuyện phỏng vấn tốt: "best practice là Identity Center, nhưng bị ràng buộc bởi X nên tôi chọn Y và giảm thiểu rủi ro bằng Z".
- ⚠️ Không có SSO đa account. Chấp nhận được vì chỉ có 1 account.
- ⚠️ Mỗi 12 giờ phải chạy lại `aws login`.
- 📌 *(Phát hiện ở P1.1)* Khi deploy bằng root, CDK báo không assume được các role bootstrap (`cdk-*-deploy-role`...) vì **root không thể assume IAM role**, nên phải dùng thẳng quyền root. Mô hình bootstrap chuẩn (deploy qua role có giới hạn) chỉ hoạt động khi dùng IAM user hoặc role. Thêm một lý do để thực hiện ADR này trước P3.6.

## Xem lại khi
Project chuyển sang account mới thuộc Organizations, hoặc credit đã hết hạn (khi đó không còn lý do tránh Organizations).
