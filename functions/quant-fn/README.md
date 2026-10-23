# quant-fn (Phase 4)

AWS Lambda **Python 3.13** (container image), được gọi bằng `lambda:Invoke` từ module `market` của jars-api. Không có endpoint public.

| Operation | Input | Output |
|---|---|---|
| `quote` | `{"op":"quote","symbol":"FPT"}` | giá hiện tại, thay đổi trong ngày, thời điểm |
| `history` | `{"op":"history","symbol":"FPT","from":"2026-01-01","to":"2026-10-01"}` | chuỗi OHLCV theo ngày |
| `optimize` | `{"op":"optimize","symbols":["FPT","VCB","HPG"],"riskLevel":"BALANCED","lookbackDays":365}` | trọng số, lợi nhuận và độ biến động kỳ vọng, `method` (`pypfopt` hoặc `heuristic`), cảnh báo |

Nguồn: `vnstock_service.py` và `pypfopt_optimizer.py` của repo `ius_dexter_vnstock_PyPortfolioOpt_intergration`.

> Trạng thái: **chưa có code**. Cần spike trước (P4.1): IP của AWS có bị nguồn dữ liệu chặn không, wheel `cvxpy` trên arm64, license vnstock. Xem [ADR-0017](../../docs/04-adr/0017-python-quant-lambda.md).

Cấu trúc dự kiến: `Dockerfile` (base `public.ecr.aws/lambda/python:3.13`), `app/handler.py`, `app/market.py`, `app/optimizer.py`, `tests/`, `requirements.txt` (pin version).
