# Architecture Decision Records

ADR ghi lại **vì sao** một quyết định kiến trúc được đưa ra, tại thời điểm nó được đưa ra. Đọc ADR để hiểu bối cảnh, còn code chỉ cho biết kết quả cuối cùng.

**Quy tắc:**
- ADR đã `Accepted` thì **không sửa nội dung**. Muốn đổi quyết định thì viết ADR mới có dòng `Supersedes: ADR-xxxx`, rồi chuyển ADR cũ sang `Superseded by ADR-yyyy`.
- Mỗi ADR có mục **"Xem lại khi"**: điều kiện nào xảy ra thì nên cân nhắc lại quyết định.
- Template: [0000-template.md](0000-template.md).

| # | Quyết định | Trạng thái | Ngày |
|---|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Ghi quyết định kiến trúc bằng ADR | Accepted | 2026-10-23 |
| [0002](0002-monorepo.md) | Monorepo cho backend, frontend, function, infra, docs | Accepted | 2026-10-23 |
| [0003](0003-modular-monolith-spring-modulith.md) | Modular monolith (Spring Modulith), chỉ tách 1 service ở Phase 5 | Accepted | 2026-10-23 |
| [0004](0004-java-25-spring-boot-4.md) | Java 25 LTS + Spring Boot 4.1 | Accepted | 2026-10-23 |
| [0005](0005-region-ap-southeast-1.md) | Region ap-southeast-1 (Singapore) | Accepted | 2026-10-23 |
| [0006](0006-compute-ecs-fargate.md) | Compute: ECS on Fargate ARM64 (không dùng App Runner/EC2/EKS) | Accepted | 2026-10-23 |
| [0007](0007-edge-cloudfront-vpc-origin.md) | CloudFront là cửa vào duy nhất; S3 OAC + VPC origin tới ALB internal | Accepted | 2026-10-23 |
| [0008](0008-database-aurora-serverless-v2.md) | Aurora PostgreSQL Serverless v2 scale-to-zero | Accepted | 2026-10-23 |
| [0009](0009-messaging-sns-sqs-eventbridge.md) | Outbox → SNS FIFO → SQS FIFO; EventBridge cho platform event; không dùng MSK | Accepted | 2026-10-23 |
| [0010](0010-auth-cognito-pkce.md) | Cognito managed login + PKCE; Spring resource server | Accepted | 2026-10-23 |
| [0011](0011-ai-spring-ai-bedrock.md) | AI bằng Spring AI + Bedrock trong jars-api; LLM không tự tính số | Accepted | 2026-10-23 |
| [0012](0012-iac-aws-cdk-java.md) | IaC bằng AWS CDK (Java) | Accepted | 2026-10-23 |
| [0013](0013-networking-no-nat.md) | Không dùng NAT Gateway ở dev; task ở public subnet, khóa bằng SG | Accepted | 2026-10-23 |
| [0014](0014-money-and-ledger-model.md) | Tiền: BigDecimal/NUMERIC(19,2); sổ cái hũ append-only | Accepted | 2026-10-23 |
| [0015](0015-realtime-sse.md) | Realtime bằng SSE (không dùng WebSocket/AppSync) | Accepted | 2026-10-23 |
| [0016](0016-cicd-github-actions-oidc.md) | CI/CD bằng GitHub Actions + OIDC | Accepted | 2026-10-23 |
| [0017](0017-python-quant-lambda.md) | Python chỉ ở `quant-fn` (Lambda); bỏ Dexter | Accepted | 2026-10-23 |
