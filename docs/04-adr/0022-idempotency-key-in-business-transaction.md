# ADR-0022: Idempotency-Key được ghi trong cùng transaction với nghiệp vụ

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-12
- **Bổ sung cho:** [ADR-0014](0014-money-and-ledger-model.md); thay thiết kế `IN_PROGRESS` trong bản đầu của `api-contract.md §4`.

## Bối cảnh
Các POST làm thay đổi tiền (ghi giao dịch, chuyển hũ) bị bấm lại khi mạng chậm hoặc client retry. Phải đảm bảo cùng một ý định chỉ ghi một lần, kể cả khi hai request giống nhau đến **đồng thời**. Bản thiết kế đầu: chèn dòng `IN_PROGRESS` trước, xử lý, rồi cập nhật kết quả, kèm mã 409 `request-in-progress` cho request đến giữa chừng. Cách này cần trạng thái trung gian, dọn dẹp dòng treo khi tiến trình chết giữa chừng, và client phải tự thử lại.

## Quyết định
Trong **một transaction** với nghiệp vụ (`IdempotencyService.execute`, bắt buộc có transaction đang chạy):

1. `INSERT INTO idempotency_keys (user_id, key, request_hash) … ON CONFLICT DO NOTHING`.
2. Chèn được: chạy nghiệp vụ rồi `UPDATE` dòng đó với `status_code` và `response_body`, rồi commit.
3. Không chèn được (key đã có): lệnh INSERT **đã chờ** khóa unique của transaction đầu tới khi nó commit hoặc rollback, nên dòng đọc ra luôn hoàn chỉnh. Cùng `request_hash` thì phát lại response đã lưu; khác hash thì 422 `idempotency-key-reused`.
4. Nghiệp vụ lỗi thì rollback luôn dòng key, nên client gửi lại cùng key vẫn chạy được.
5. Vòng retry khi thua cuộc đua `@Version` nằm **ngoài** transaction, nên mỗi lần thử có transaction mới (kể cả dòng key mới).

`request_hash` tính trên command đã chuẩn hóa (số tiền `85000` và `85000.00` là một request), không phải trên body thô.

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| Dòng `IN_PROGRESS` + 409 | Trạng thái trung gian, dòng treo khi crash, client phải retry; thêm mã lỗi và quy tắc dọn dẹp |
| Redis `SET NX` | Thêm một hệ thống; khóa và dữ liệu nằm ngoài transaction DB nên vẫn có khe hở giữa "đã đặt khóa" và "đã commit" |
| Unique constraint trên dữ liệu nghiệp vụ | Không có khóa tự nhiên cho "cùng ý định của người dùng" |
| Advisory lock theo key | Chỉ chặn song song, không lưu response để phát lại |

## Hệ quả
- ✅ Không có trạng thái treo: hoặc cả nghiệp vụ lẫn dòng key được commit, hoặc không gì cả.
- ✅ Request trùng đồng thời tự xếp hàng trên unique index và nhận lại đúng response của request đầu (`Idempotency-Replayed: true`), không cần mã 409 riêng.
- ✅ Có test: 8 luồng cùng key chỉ ghi một lần (`IdempotencyServiceTest`, `LedgerPostingTest`).
- ⚠️ Request thứ hai giữ một kết nối DB trong lúc chờ request đầu; có giới hạn bởi thời gian của một giao dịch ngắn nhưng nên theo dõi pool nếu thêm nghiệp vụ chậm vào trong transaction.
- ⚠️ Bảng cần dọn: `purgeExpired()` xóa key quá 24 giờ và được gọi từ job hằng tháng, không dùng `@Scheduled` để Aurora vẫn pause được.

## Xem lại khi
Nghiệp vụ trong transaction trở nên chậm (gọi dịch vụ ngoài), hoặc cần idempotency xuyên nhiều service.
