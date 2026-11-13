# Diagrams as code

Sơ đồ hạ tầng AWS được vẽ bằng Python: icon AWS chính thức lấy từ thư viện [`diagrams`](https://diagrams.mingrammer.com/), bố cục theo lưới do [`src/_ortho.py`](src/_ortho.py) tính, Graphviz chỉ dùng để vẽ ra PNG. Mọi mũi tên đều đi theo đoạn ngang/dọc (góc vuông). Các sơ đồ luồng và logic thì viết bằng **Mermaid** ngay trong file Markdown, GitHub tự render.

Cả bốn sơ đồ mô tả **đúng những gì code CDK (`infra/cdk`) và các workflow (`.github/workflows`) đang dựng**, không có thành phần dự kiến. Khi code đổi thì sơ đồ phải được vẽ lại.

| File PNG | Source | Nội dung | Lấy từ |
|---|---|---|---|
| [aws-deployment.png](aws-deployment.png) | [src/aws_deployment.py](src/aws_deployment.py) | Bản tổng quan (dev, ap-southeast-1): browser, CloudFront, S3, Cognito, ALB nội bộ, một task Fargate `jars-api` kèm ADOT sidecar, Aurora, luồng import sao kê, hai schedule, Bedrock, X-Ray và CloudWatch. Sơ đồ đầu tiên của README gốc | `AppStack`, `DataStack`, `AuthStack`, `MessagingStack`, `ApiObservability`, `AdvisorResources` |
| [aws-architecture.png](aws-architecture.png) | [src/aws_architecture.py](src/aws_architecture.py) | Bản chi tiết: subnet theo tầng, từng queue kèm DLQ, EventBridge rule và hai schedule, Bedrock + Guardrail, ECR, Secrets Manager, CloudWatch (log, metric EMF, dashboard, alarm), SNS alarm topic, Budgets, X-Ray | tất cả stack `Jars*` |
| [aws-network.png](aws-network.png) | [src/aws_network.py](src/aws_network.py) | VPC `10.20.0.0/16`, ba tầng subnet (public / lb / data, mỗi tầng hai AZ), quy tắc security group, đường ra internet của task | `NetworkStack`, `AppStack`, `DataStack` |
| [aws-cicd.png](aws-cicd.png) | [src/aws_cicd.py](src/aws_cicd.py) | `ci.yml`, `web.yml`, `infra.yml` (PR và push lên main, không chạm AWS) và `deploy-dev.yml` (chỉ `workflow_dispatch`, chạy từ main): OIDC role, image ARM64 lên ECR, `cdk deploy JarsApp`, smoke test | `.github/workflows/*.yml`, `FoundationStack` |

Những thứ **cố ý không vẽ** để sơ đồ còn đọc được: các Lambda custom resource do CDK sinh (`BucketDeployment`, bật EventBridge cho bucket), CDK bootstrap roles, quyền IAM chi tiết, fallback `-c dbMode=rds` của `DataStack`, quyền đọc/ghi `uploads/` của task trên statements bucket.

**Quy ước cạnh** trong `aws-architecture.png`:
- Xám: request đồng bộ.
- Tím: sự kiện nền tảng (S3 → EventBridge → SQS, Scheduler → SQS, queue → `jars-api`).
- Đỏ chấm: chuyển sang DLQ (redrive của queue, hoặc Scheduler không giao được).
- Nét đứt: browser gọi thẳng dịch vụ AWS (Cognito, presigned PUT), hoặc thứ được nạp khi task khởi động (image từ ECR, secret từ Secrets Manager).

## Cách render lại

Yêu cầu: Python 3.10+ và **Graphviz** (có lệnh `dot` trong `PATH`).

```bash
# Windows: winget install Graphviz.Graphviz   |  macOS: brew install graphviz  |  Ubuntu: apt install graphviz
# Windows, nếu `dot` chưa có trong PATH (Git Bash): export PATH="$PATH:/c/Program Files (x86)/Graphviz/bin"
python -m venv .venv-diagrams   # đặt venv ngoài repo (không có trong .gitignore)
.venv-diagrams/Scripts/python -m pip install diagrams==0.25.1      # macOS/Linux: .venv-diagrams/bin/python
for f in docs/diagrams/src/aws_*.py; do .venv-diagrams/Scripts/python "$f"; done
```

Không muốn cài Graphviz thì render trong container:

```bash
docker run --rm -v "$PWD/docs/diagrams:/d" python:3.13-slim bash -c \
  "apt-get update -qq && apt-get install -y -qq graphviz fonts-dejavu-core && pip install -q diagrams==0.25.1 && cd /d && for f in src/aws_*.py; do python \$f; done"
```

Mỗi script ghi file PNG cùng tên vào `docs/diagrams/` (chạy từ thư mục nào cũng được). Lần render lại gần nhất: 2026-11-13, với `diagrams 0.25.1` + Graphviz 14.1.2.

## Cách sơ đồ được bố trí (`src/_ortho.py`)

`splines=ortho` của Graphviz bỏ qua port và nhãn cạnh, hay đè mũi tên lên nhãn icon, nên không dùng. Thay vào đó mỗi script tự đặt từng node lên lưới (`d.node(id, Icon, "nhãn", cột, hàng)`), hộp lồng nhau (VPC, subnet, nhóm dịch vụ) được tính từ các node bên trong (`d.group`), và mỗi mũi tên là một chuỗi đoạn ngang/dọc đi trong khoảng trống giữa các hộp (`d.edge(src, dst, "rl", lanes=[...])`: hai ký tự là cạnh ra của `src` và cạnh vào của `dst`, `lanes` là toạ độ làn đi giữa hai đầu). Sau đó module ghi file DOT với vị trí cố định và chạy `neato -n2`, nên font, icon, PNG vẫn do Graphviz tạo.

Khi chạy, `_ortho.py` tự kiểm tra và in `LAYOUT WARNING` nếu có mũi tên cắt qua icon, nhãn, nhãn nhóm, hoặc chồng lên một mũi tên khác: sửa tới khi hết cảnh báo rồi mở PNG xem lại. Khi thêm node, chừa chỗ cho nhãn (nhãn nằm dưới icon, hoặc trên icon với `label_top=True` để cạnh dưới icon còn trống cho mũi tên) và chọn làn nằm giữa hai hộp, đừng đi xuyên hộp khác.

Để sơ đồ đọc được, một số quan hệ cùng đích được gộp thành một mũi tên từ hộp nhóm: các queue SQS vào `jars-api` (hộp "SQS queues"), ECR + Secrets Manager nạp lúc khởi động task (hộp "Loaded at task start"), `Converse` + `ApplyGuardrail` sang Bedrock (hộp "Bedrock") trong `aws-architecture.png`; hai queue trong `aws-deployment.png` cũng gộp như vậy.
