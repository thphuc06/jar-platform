# local

Docker Compose cho môi trường dev local (tạo ở P0.6, bổ sung dần theo phase). Chi tiết: [local-development.md](../docs/05-operations/local-development.md).

Dự kiến: `compose.yaml` (postgres, valkey, localstack, lgtm, kafka [lab]) + `.env.example` (không chứa secret thật; `LOCALSTACK_AUTH_TOKEN` để trống).
