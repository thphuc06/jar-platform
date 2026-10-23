# ADR-0012: Infrastructure as Code bằng AWS CDK v2 (Java)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
Bản cũ dùng Terraform + script PowerShell + CodeBuild JSON, với một phần cấu hình được *sinh ra* rồi commit (`generated_config.tf`). Yêu cầu mới là "AWS-native". Tài khoản đã từng dùng CDK (stack `CDKToolkit`, `FairTable*` ở us-east-1).

## Quyết định
- Toàn bộ hạ tầng viết bằng **AWS CDK v2, ngôn ngữ Java**, nằm trong `infra/cdk` (Maven project riêng).
- Chia stack theo vòng đời (Foundation, Network, Data, Auth, Messaging, Quant, App, Observability), xem [aws-infrastructure.md §1](../02-architecture/aws-infrastructure.md#1-cdk-stacks).
- Cấu hình theo môi trường đặt trong `cdk.json` context (`env=dev`, `dbMode=aurora|rds`, `apiDesiredCount`, `imageTag`).
- Chạy CDK CLI qua `npx aws-cdk@2` (không cài global).
- Test hạ tầng bằng `aws-cdk-lib.assertions` (`Template.fromStack(...).hasResourceProperties(...)`) + `cdk-nag` (AwsSolutions pack) để bắt lỗi cấu hình bảo mật.

## Các phương án đã cân nhắc

| Phương án | Ưu | Nhược |
|---|---|---|
| Terraform | Phổ biến nhất trong JD DevOps; dùng được đa cloud | Thêm ngôn ngữ HCL; phải tự lo state backend (S3 + lock); không phải "AWS-native" |
| CloudFormation YAML thuần | Native nhất | Dài dòng, không có abstraction, không viết test được bằng ngôn ngữ thật |
| CDK TypeScript | Tài liệu và ví dụ nhiều nhất | Thêm một ngôn ngữ; mục tiêu là luyện **Java** |
| **CDK Java** ✅ | Cùng ngôn ngữ với backend; có L2 construct mạnh (VPC, ECS, Aurora...); test bằng JUnit | Ví dụ ít hơn TS; CDK CLI vẫn cần Node.js |

## Hệ quả
- ✅ Một ngôn ngữ cho backend lẫn hạ tầng; test hạ tầng bằng JUnit.
- ✅ Kiến thức chuyển sang CloudFormation (CDK synth ra CFN) và Terraform đều dễ, vì khái niệm giống nhau.
- ⚠️ Phải `cdk bootstrap aws://626212071763/ap-southeast-1` (hiện mới bootstrap us-east-1).
- ⚠️ JD hay ghi Terraform → ghi chú trong skills matrix: "khái niệm IaC giống nhau; đã từng dùng Terraform ở bản hackathon".

## Xem lại khi
Cần quản lý tài nguyên ngoài AWS (GitHub, Cloudflare, Datadog) trong cùng một tool.
