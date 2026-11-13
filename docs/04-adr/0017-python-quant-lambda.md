# ADR-0017: Python chỉ dùng ở `quant-fn` (AWS Lambda); bỏ agent Dexter (TypeScript)

- **Trạng thái:** Superseded bởi [ADR-0024](0024-drop-market-and-quant-fn.md) (2026-11-13)
- **Ngày:** 2026-10-23

## Bối cảnh
Repo `ius_dexter_vnstock_PyPortfolioOpt_intergration` gồm 3 phần:
- **Dexter**: agent ReAct viết bằng TypeScript/Bun, fork từ một dự án mã nguồn mở.
- **vnstock_service**: FastAPI bọc thư viện `vnstock` để lấy dữ liệu chứng khoán Việt Nam.
- **pypfopt_optimizer**: tối ưu Markowitz bằng PyPortfolioOpt (cvxpy), được Dexter gọi bằng cách spawn child process.

Cả ba chạy chung trong 1 container App Runner bằng supervisord. Endpoint `/ask` không có auth.

## Quyết định
- **Bỏ Dexter.** Phần hội thoại về cổ phiếu do module `advisor` (Spring AI) đảm nhận, thông qua tool `getQuote` / `getHistory` / `optimizePortfolio`.
- Gộp `vnstock_service` và `pypfopt_optimizer` thành **một Lambda Python 3.13 (container image)** tên `quant-fn` với 3 operation: `quote`, `history`, `optimize`.
- jars-api gọi bằng `lambda:Invoke` (IAM), có timeout 10 s, circuit breaker, và cache kết quả trong Valkey (quote 60 s, history 1 giờ).
- Contract JSON (input/output) lưu ở `contracts/quant/` và có contract test ở cả hai phía.

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| Viết lại bằng Java | Không có thư viện Java tương đương vnstock (scrape VCI/TCBS...). Tự viết Markowitz + solver QP thì lạc đề |
| Giữ FastAPI chạy trên ECS | Thêm một service chạy 24/7 (khoảng $18+/tháng) cho tính năng ít dùng |
| Lambda Function URL public | Lộ endpoint; phải tự xác thực. Gọi bằng IAM Invoke đơn giản và an toàn hơn |
| Giữ Dexter | Không phải code của tác giả, không phải Java, trùng chức năng với advisor |

## Hệ quả
- ✅ Chi phí gần $0 (Lambda free tier 1M request/tháng); không có endpoint public.
- ✅ Câu chuyện "polyglot có chủ đích": dùng Python đúng chỗ, giao tiếp qua contract.
- ⚠️ Cold start của Lambda Python có numpy/cvxpy: khoảng 1–3 s. Chấp nhận được vì có cache.
- ⚠️ **Rủi ro cần spike sớm ở Phase 4:**
  - (1) Nguồn dữ liệu của vnstock có chặn IP của AWS không.
  - (2) Wheel `cvxpy` trên arm64.
  - (3) License của vnstock (dùng phi thương mại).
  - Nếu (1) thất bại thì phương án B là dùng dữ liệu mẫu tĩnh (snapshot lưu trên S3) cho mục đích demo giáo dục.

## Xem lại khi
Cần dữ liệu realtime liên tục (streaming giá) hoặc tính toán nặng hơn giới hạn của Lambda.
