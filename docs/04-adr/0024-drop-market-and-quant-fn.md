# ADR-0024: Bỏ module `market` và Lambda `quant-fn` (vnstock, PyPortfolioOpt)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-13
- **Thay thế:** [ADR-0017](0017-python-quant-lambda.md)

## Bối cảnh
ADR-0017 giữ lại phần Python của prototype hackathon dưới dạng một Lambda `quant-fn`. Lambda này lấy giá cổ phiếu Việt Nam bằng vnstock và tối ưu danh mục bằng PyPortfolioOpt. Module `market` của `jars-api` gọi Lambda này, và advisor có thêm tool `getQuote`. ADR-0017 đặt điều kiện phải spike trước. Kết quả spike (Phase 2 WP5, 13/11/2026):

- **vnstock không dùng được cho sản phẩm này.**
  - Gói không còn trên PyPI.
  - Từ thế hệ 5, vnstock là gói dựng sẵn theo giấy phép riêng. Các phiên bản trước thế hệ 6 ngừng hoạt động từ 10/11/2026, nên code của prototype không còn chạy.
  - Bản Community chỉ cho mục đích cá nhân, học tập và nghiên cứu. Muốn cho bên thứ ba truy xuất dữ liệu thì cần thoả thuận bằng văn bản.
  - Giấy phép cấm phân phối lại gói, nên không được đóng gói vào image.
- **Không có vnstock thì PyPortfolioOpt không còn giá trị.** Tối ưu Markowitz trên dữ liệu giá giả hoặc giá đóng băng chỉ là phần trình diễn, không phục vụ người dùng.
- Phần lõi của sản phẩm là các hũ, sổ cái, cảnh báo, ngân sách, mục tiêu, import và advisor. Phần lõi không phụ thuộc vào giá cổ phiếu.

## Quyết định
- Bỏ khỏi thiết kế:
  - Module `market`.
  - Lambda `quant-fn` và thư mục `functions/quant-fn`.
  - Stack `JarsQuant` và repository ECR `jars/quant-fn`.
  - Contract `contracts/quant/`.
  - Ba endpoint `/market/*`.
  - Tool `getQuote` của advisor.
- Advisor tiếp tục từ chối khuyến nghị mua bán chứng khoán bằng denied topic của guardrail ([ADR-0023](0023-advisor-guardrail-and-rate-limit.md)). Nó trả lời dựa trên dữ liệu tài chính của chính người dùng.
- `jars-api` là thành phần duy nhất của hệ thống. Không còn Python trong runtime.

## Hệ quả
- Kiến trúc gọn hơn: không có Lambda gọi đồng bộ, không cần circuit breaker cho nó, và không có Python trong pipeline CI.
- Mất phần trình diễn "tích hợp Lambda Python". Nếu cần ví dụ về Lambda, có thể làm trên chính nghiệp vụ của project. Một ví dụ là xử lý sao kê bằng Lambda, nhưng hiện chưa có lý do để làm.
- Nếu sau này có nguồn dữ liệu giá có giấy phép phù hợp, thì viết một ADR mới. Không khôi phục ADR-0017, vì giả định về vnstock trong đó đã sai.
