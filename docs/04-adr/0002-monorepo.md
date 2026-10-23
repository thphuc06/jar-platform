# ADR-0002: Monorepo cho backend, frontend, function, infra, docs

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
Bản hackathon chia thành 4 repo (backend, UI, stock agent, demo). Các thay đổi liên quan nhau (đổi API → sửa UI → sửa infra) phải làm ở nhiều repo, khó đồng bộ version, và lịch sử commit lẫn nhiều phần không phải của tác giả (Lovable bot, fork Dexter).

## Quyết định
Tạo **một repo mới `jar-platform`** chứa `apps/` (jars-api, insights-worker, web), `functions/` (quant-fn), `infra/cdk`, `contracts/`, `local/`, `docs/`. Mỗi app tự quản lý build của nó (Maven, npm, pip). CI dùng **path filter** để chỉ build phần bị thay đổi.

Các repo cũ: sau khi xử lý xong secret thì **archive** (read-only) và ghi link sang repo mới.

## Các phương án đã cân nhắc

| Phương án | Ưu | Nhược |
|---|---|---|
| Tiếp tục dùng 4 repo cũ | Giữ lịch sử | Lịch sử có secret, phụ thuộc chéo khó quản lý, khó thể hiện phần do mình làm |
| Polyrepo mới (mỗi app một repo) | Tách quyền, tách CI rõ ràng | Thừa với 1 người phát triển; thay đổi xuyên suốt phải mở nhiều PR |
| Monorepo + công cụ build chung (Nx, Bazel) | Có cache build, tối ưu | Quá phức tạp so với quy mô project |

## Hệ quả
- ✅ Một PR có thể chứa trọn một feature (API + UI + infra + docs). Contract (OpenAPI, event schema) nằm cạnh code dùng nó.
- ✅ Lịch sử sạch, 100% là commit của tác giả.
- ⚠️ CI phải có path filter để không build lại mọi thứ.
- ⚠️ UI lấy từ `ius_financial_ui` (do Lovable sinh). Ghi rõ nguồn gốc trong `apps/web/README.md`.

## Xem lại khi
Có nhiều team độc lập cùng làm, hoặc thời gian CI vượt 15 phút dù đã có path filter.
