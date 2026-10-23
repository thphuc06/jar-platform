# Jars Platform

[![ci](https://github.com/thphuc06/jar-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/thphuc06/jar-platform/actions/workflows/ci.yml)

Ứng dụng quản lý tài chính cá nhân theo **phương pháp 6 chiếc hũ (6 Jars)**: chia thu nhập vào hũ, theo dõi chi tiêu, phát hiện chi tiêu bất thường, lập lộ trình tiết kiệm cho mục tiêu, và có trợ lý AI tư vấn. Kèm theo đó là phần phân tích cổ phiếu Việt Nam dành cho mục đích giáo dục.

Đây là **repo cá nhân**, viết lại **từ đầu** project hackathon *Jars/IUS* (org `swinhackathon`). Mục tiêu là làm một **portfolio Java Backend + AWS** (khi hoàn thành sẽ demo và đưa lên CV được những gì: [cv-showcase.md](docs/01-product/cv-showcase.md)):
- Backend viết bằng **Java 25 + Spring Boot 4.1**.
- Hạ tầng **AWS-native**: ECS Fargate, Aurora Serverless v2, Cognito, SNS/SQS, EventBridge, Bedrock, Lambda; tất cả dựng bằng **AWS CDK (Java)**.
- Python chỉ dùng ở chỗ nó thật sự có lợi thế: `quant-fn` (vnstock + PyPortfolioOpt).

> **Trạng thái (2026-10-23):** Đã xong phần thiết kế và tài liệu. Chưa có code ứng dụng. Bước tiếp theo là Phase 0, xem [docs/06-plan/roadmap.md](docs/06-plan/roadmap.md).

![AWS target architecture](docs/diagrams/aws-architecture.png)

## Tech stack (tóm tắt)

| Lớp | Công nghệ |
|---|---|
| Backend | Java 25 LTS, Spring Boot 4.1, Spring Modulith 2.1, Spring Security (OAuth2 Resource Server), Spring Data JPA, Flyway, Spring Batch, Spring AI 2.0, Spring Cloud AWS 4.2 |
| Database | Amazon Aurora PostgreSQL Serverless v2 (scale-to-zero) |
| Messaging | Transactional outbox (Spring Modulith) → SNS FIFO → SQS FIFO; EventBridge (S3 events) và EventBridge Scheduler |
| AI | Amazon Bedrock (Claude Haiku, cross-region inference profile) + Bedrock Guardrails |
| Quant | AWS Lambda (Python 3.13, container image): vnstock + PyPortfolioOpt |
| Frontend | React + Vite + shadcn/ui (PWA), host trên S3 + CloudFront |
| Auth | Amazon Cognito User Pool (managed login, Authorization Code + PKCE) |
| Compute | Amazon ECS on AWS Fargate (ARM64 / Graviton) |
| IaC / CI-CD | AWS CDK v2 (Java), GitHub Actions + OIDC |
| Observability | Micrometer + OpenTelemetry → CloudWatch / X-Ray |

Chi tiết và lý do của từng lựa chọn nằm ở [docs/02-architecture/overview.md](docs/02-architecture/overview.md) và các [ADR](docs/04-adr/README.md).

## Cấu trúc repo

```
jar-platform/
├── apps/
│   ├── jars-api/          # Java 25 · Spring Boot 4.1 · modular monolith (Maven)
│   ├── insights-worker/   # Java · service tách ra ở Phase 5 (consumer SQS)
│   └── web/               # React + Vite PWA (chuyển từ ius_financial_ui)
├── functions/
│   └── quant-fn/          # Python 3.13 Lambda: vnstock + PyPortfolioOpt
├── infra/
│   └── cdk/               # AWS CDK v2 viết bằng Java: toàn bộ hạ tầng
├── contracts/
│   ├── openapi/           # OpenAPI spec do jars-api sinh ra (commit vào repo)
│   └── events/            # JSON Schema cho domain events
├── local/                 # docker compose cho môi trường dev local
├── docs/                  # toàn bộ tài liệu (bắt đầu từ docs/README.md)
└── .github/workflows/     # CI (mỗi PR) và CD (deploy dev)
```

## Đọc tài liệu theo thứ tự nào?

1. [docs/README.md](docs/README.md): bản đồ tài liệu và quy tắc cập nhật.
2. [Vision & scope](docs/01-product/vision-and-scope.md): sản phẩm làm gì, không làm gì.
3. [Architecture overview](docs/02-architecture/overview.md), rồi [AWS infrastructure](docs/02-architecture/aws-infrastructure.md).
4. [Roadmap](docs/06-plan/roadmap.md): đang ở phase nào, bước tiếp theo là gì.

## Quick start

Chưa có, sẽ được bổ sung ở Phase 0. Hướng dẫn chuẩn bị máy: [docs/05-operations/local-development.md](docs/05-operations/local-development.md).
