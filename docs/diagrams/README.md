# Diagrams as code

Sơ đồ hạ tầng AWS được vẽ bằng thư viện Python [`diagrams`](https://diagrams.mingrammer.com/) (dùng icon AWS chính thức, layout do Graphviz tính). Các sơ đồ luồng và logic thì viết bằng **Mermaid** ngay trong file Markdown, GitHub tự render.

| File PNG | Source | Nội dung |
|---|---|---|
| [aws-architecture.png](aws-architecture.png) | [src/aws_architecture.py](src/aws_architecture.py) | Kiến trúc đích (hết Phase 5): edge, VPC, messaging, AI, platform |
| [aws-phase3-mvp.png](aws-phase3-mvp.png) | [src/aws_phase3_mvp.py](src/aws_phase3_mvp.py) | Bản deploy tối thiểu đầu tiên (Phase 3) |
| [aws-network.png](aws-network.png) | [src/aws_network.py](src/aws_network.py) | VPC, subnet theo AZ, quy tắc security group |
| [aws-cicd.png](aws-cicd.png) | [src/aws_cicd.py](src/aws_cicd.py) | GitHub Actions → OIDC → ECR + CDK → ECS/S3/CloudFront |

**Quy ước màu cạnh** trong `aws-architecture.png`:
- Xám: request đồng bộ.
- Xanh lá: domain event đi qua outbox → SNS FIFO → SQS FIFO.
- Tím: platform event (S3 → EventBridge, Scheduler).
- Nét đứt: browser gọi thẳng dịch vụ AWS (Cognito, presigned S3).

## Cách render lại

Yêu cầu: Python 3.10+ và **Graphviz** (có lệnh `dot` trong `PATH`).

```bash
# Windows: winget install Graphviz.Graphviz   |  macOS: brew install graphviz  |  Ubuntu: apt install graphviz
python -m venv .venv-diagrams
.venv-diagrams/Scripts/pip install diagrams==0.25.1      # macOS/Linux: .venv-diagrams/bin/pip
for f in docs/diagrams/src/*.py; do .venv-diagrams/Scripts/python "$f"; done
```

Mỗi script ghi file PNG cùng tên vào `docs/diagrams/`. Đã kiểm tra với `diagrams 0.25.1` + `Graphviz 16.0.0` ngày 2026-10-23.

**Mẹo layout:** dùng `splines="spline"` để nhãn cạnh nằm đúng chỗ (`ortho` hay đặt nhãn sai vị trí). Hạn chế nối dây cho các dịch vụ "dùng chung" (ECR, Secrets, CloudWatch); gom chúng vào một cluster riêng để sơ đồ đỡ rối.
