# ADR-0021: Phát hiện chi tiêu bất thường theo từng hũ bằng MAD + CUSUM

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-12

## Bối cảnh
Sản phẩm cần cảnh báo "hũ này đang chi bất thường" ngay khi một khoản chi được ghi, và phải giải thích được vì sao có cảnh báo. Bản hackathon dùng CUSUM chuẩn hóa kèm bộ lọc spike z-score > 4 (`cusum_engine.py`) và MAD ở `anomaly.py`, nhưng dính chặt vào Supabase. Ràng buộc: không có GPU, không có dữ liệu huấn luyện sẵn, mỗi người dùng chỉ có vài chục khoản chi mỗi hũ, và kết quả phải xác định để test được.

## Quyết định
`SpendingDetector` (thuần, không phụ thuộc Spring) chạy trên **mỗi khoản chi của một hũ**, trạng thái được lưu theo hũ (`anomaly_detector_state`):

1. **Khởi động:** 5 khoản chi đầu tiên chỉ học baseline (trung bình và độ lệch chuẩn mẫu, cập nhật trực tuyến bằng Welford nên không phải đọc lại lịch sử).
2. **MAD (khoản chi lớn đơn lẻ):** với trung vị m và MAD d của tối đa 30 khoản gần nhất, điểm z hiệu chỉnh = 0.6745 (x − m) / d; lớn hơn 3.5 là spike (HIGH nếu lớn hơn 7). Trung vị và MAD không bị các ngoại lệ trước đó làm lệch. Spike **không** đi vào baseline hay CUSUM.
3. **CUSUM (xu hướng kéo dài):** z = (x − mean) / sigma, S+ = max(0, S+ + z − 0.5), S− = min(0, S− + z + 0.5); S+ > 5 là chi tăng kéo dài, S− < −5 là chi giảm kéo dài (LOW). Reset sau mỗi lần báo.
4. Sàn chống chia cho 0: sigma ≥ 10% của mean, MAD ≥ 5% của trung vị (hũ có lịch sử toàn khoản bằng nhau không coi chênh 1 đồng là vô cùng bất thường).
5. Trạng thái hũ bị khóa bi quan (`SELECT … FOR UPDATE`) khi xử lý, nên các event của một hũ được áp dụng lần lượt; consumer idempotent bằng `processed_messages`.

Vector kiểm thử chuẩn lấy từ các test Python của bản hackathon (baseline 100k, sigma 52k: tăng dần báo ở khoản thứ 10, không đổi 200k báo ở khoản thứ 4).

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| Chỉ z-score theo mean/sigma | Một khoản chi lớn làm lệch chính mean/sigma, nên khoản lớn thứ hai khó bị bắt; không bắt được xu hướng tăng đều |
| Chỉ CUSUM | Phản ứng chậm với một khoản chi cực lớn duy nhất (phải cộng dồn); bản gốc phải thêm bộ lọc spike để bù |
| Mô hình ML (isolation forest, ARIMA, Prophet) | Cần dữ liệu và hạ tầng chạy mô hình; khó giải thích cho người dùng; không xác định nên khó test. Quá mức cho vài chục điểm dữ liệu mỗi hũ |
| Baseline theo **danh mục** thay vì theo hũ | Chính xác hơn (tiền nhà 4,5 triệu không còn là ngoại lệ so với tiền ăn), nhưng mỗi cặp hũ-danh mục có quá ít mẫu; để dành cho sau |

## Hệ quả
- ✅ Giải thích được: "khoản 2.000.000 ₫ cao hơn nhiều so với các khoản thường lệ của hũ này" và mỗi cảnh báo lưu điểm, ngưỡng, bộ phát hiện.
- ✅ Xác định, kiểm thử được, không cần hạ tầng; người dùng phản hồi (xác nhận / đã dự kiến / báo nhầm) được lưu để chỉnh ngưỡng sau.
- ⚠️ **Theo hũ chứ không theo danh mục:** một khoản định kỳ lớn (tiền thuê nhà) nằm cùng hũ với chi tiêu hằng ngày sẽ bị coi là bất thường. Dữ liệu mẫu tránh điều này bằng một hũ "Tiền nhà" riêng; người dùng thật có thể bấm "đã dự kiến". Đây là điểm đã gặp thật khi viết dữ liệu mẫu.
- ⚠️ Spike không vào baseline, nên khi mức chi tăng thật và bền vững, mean/sigma đuổi theo chậm. Cửa sổ trung vị (nhận cả spike) tự thích nghi nên cảnh báo MAD dừng lại, và CUSUM báo một lần rồi reset.
- ⚠️ Thứ tự xử lý event của cùng một hũ chỉ gần đúng thứ tự ghi (khóa theo thứ tự đến, không theo thứ tự commit). Khi tách thành service riêng, SQS FIFO theo `userId` sẽ cho thứ tự chặt.

## Xem lại khi
Có đủ dữ liệu phản hồi để tối ưu ngưỡng theo người dùng, cần baseline theo danh mục, hoặc cần phát hiện theo mùa (cuối tuần, đầu tháng).
