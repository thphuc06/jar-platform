# Vision & Scope

## 1. Vấn đề

Người trẻ Việt Nam (sinh viên, người mới đi làm) có thu nhập không đều. Họ thường không biết tiền đã đi đâu và chỉ phát hiện mình tiêu quá tay vào cuối tháng. Mục tiêu tiết kiệm (mua laptop, quỹ khẩn cấp, du lịch) thì thiếu một kế hoạch cụ thể. Các app ngân hàng có lịch sử giao dịch nhưng **không có lớp "kế hoạch"**: không chia ngân sách, không cảnh báo sớm, không trả lời được câu hỏi "với tốc độ này thì bao giờ tôi đạt mục tiêu?".

## 2. Giải pháp

**Jars Platform** là một web app (PWA) giúp người dùng:
1. **Chia tiền vào hũ** theo phương pháp 6 Jars, hoặc tự định nghĩa hũ và tỷ lệ phần trăm riêng.
2. **Ghi nhận giao dịch** bằng cách nhập tay hoặc import sao kê CSV. Mỗi giao dịch trừ vào một hũ.
3. **Phát hiện chi tiêu bất thường** theo từng hũ (thuật toán CUSUM và MAD) rồi gửi cảnh báo realtime.
4. **Lập mục tiêu tiết kiệm**: kiểm tra tính khả thi, chạy kịch bản what-if, sinh lộ trình theo từng giai đoạn.
5. **Hỏi trợ lý AI** bằng tiếng Việt về tình hình tài chính của chính mình. Mọi con số trong câu trả lời đều do backend tính, LLM chỉ diễn giải.
6. ~~**Tìm hiểu cổ phiếu Việt Nam (giáo dục)**~~: đã bỏ (13/11/2026) vì giấy phép của nguồn dữ liệu vnstock ([ADR-0024](../04-adr/0024-drop-market-and-quant-fn.md)). Trợ lý AI vẫn từ chối khuyến nghị mua/bán.

## 3. Persona

| Persona | Mô tả | Feature quan tâm nhất |
|---|---|---|
| **An**, sinh viên năm 3 | Thu nhập từ bố mẹ và làm thêm, 5–8 triệu/tháng, hay tiêu lố vào ăn uống | Hũ, cảnh báo, dashboard |
| **Bình**, nhân viên mới đi làm | Lương 15–20 triệu, muốn có quỹ khẩn cấp và mua xe máy trong 18 tháng | Mục tiêu, lộ trình, chat AI |
| **Recruiter / interviewer** | Đọc repo để đánh giá năng lực Java backend | README, ADR, code, test, diagram |

> Persona thứ ba là có chủ đích: đây là project portfolio, nên **chất lượng kỹ thuật và tài liệu cũng là một "feature"**.

## 4. Phạm vi

### Trong phạm vi (In scope)

| # | Feature | Phase (theo [roadmap](../06-plan/roadmap.md) hiện tại) |
|---|---|---|
| F1 | Đăng ký, đăng nhập (Cognito); mỗi người chỉ thấy dữ liệu của mình | 1 |
| F2 | Onboarding: bảng hỏi khẩu vị rủi ro → risk profile | 1 |
| F3 | CRUD hũ, cấu hình tỷ lệ phân bổ; tự tạo 6 hũ mặc định khi đăng ký | 1 |
| F4 | Ghi giao dịch thu/chi/chuyển hũ (có idempotency); Money Inbox có lọc và phân trang | 1 |
| F5 | Dashboard: số dư, thu chi trong tháng, chi theo danh mục và theo hũ, xu hướng 6 tháng | 1 |
| F6 | CRUD mục tiêu, tính khả thi, kịch bản what-if, lộ trình theo giai đoạn | 1 |
| F7 | Gợi ý phân bổ thu nhập vào hũ (dựa trên lịch sử chi tiêu) | 1 |
| F8 | Phát hiện bất thường (CUSUM theo hũ, outlier theo giao dịch) → thông báo realtime (SSE) | 1 |
| F9 | Phản hồi cảnh báo: "đúng là bất thường / dự kiến trước / báo nhầm" | 1 |
| F10 | Import sao kê CSV (upload lên S3, xử lý theo batch) | 2 |
| F11 | Khép sổ cuối tháng tự động (tổng kết, chuyển số dư sang tháng mới) | 1 |
| F12 | Chat AI streaming, có tool calling vào dữ liệu thật của user và guardrails | 2 |
| ~~F13~~ | ~~Tra cứu cổ phiếu VN + mô phỏng tối ưu danh mục~~ (đã bỏ, ADR-0024) | – |
| F14 | Audit log: ai làm gì, lúc nào; ghi bằng AOP trong cùng transaction với nghiệp vụ ([ADR-0025](../04-adr/0025-audit-log-aop-same-transaction.md)) (đã làm) | 3+ |
| F15 | Gợi ý sản phẩm từ danh mục **minh họa** của một ngân hàng hư cấu (18 sản phẩm: tiền gửi, quỹ, vay, thẻ, bảo hiểm): trợ lý đọc danh mục qua tool và lý giải theo hồ sơ (khẩu vị rủi ro, độ tuổi, thu nhập, hai câu hỏi onboarding mới) cùng số liệu thật của user; không có engine gợi ý ở backend ([ADR-0027](../04-adr/0027-advisor-product-catalog-and-profile.md)) | 3+ |

Ngoài danh sách feature: **đối soát sổ cái hằng đêm** (kiểm `balance == SUM(entries)` của mọi hũ, chỉ phát hiện và báo, không tự sửa) là biện pháp vận hành, không phải tính năng cho người dùng ([flows.md F6](../02-architecture/flows.md#f6)).

### Ngoài phạm vi (Out of scope) và lý do

| Không làm | Lý do |
|---|---|
| Kết nối tài khoản ngân hàng thật hoặc chuyển tiền thật | Cần giấy phép và đối tác. Ở đây "chuyển tiền" chỉ là ghi sổ giữa các hũ |
| Khuyến nghị đầu tư cá nhân hóa (mua/bán mã cụ thể) | Rủi ro pháp lý; giới hạn ở mức giáo dục, có Guardrails chặn. Trợ lý chỉ giới thiệu sản phẩm của danh mục minh họa (ngân hàng hư cấu) và nói rõ đó là thông tin tham khảo, không phải tư vấn của chuyên gia được cấp phép; cổ phiếu, tiền mã hóa, vàng, ngoại tệ vẫn ngoài phạm vi |
| Đa tiền tệ | MVP chỉ dùng VND. Schema vẫn có cột `currency` để mở rộng sau |
| App mobile native | PWA là đủ |
| Đa tenant hoặc gia đình dùng chung hũ | Tăng độ phức tạp phân quyền, để sau MVP |
| Viết lại UI bằng Java | Frontend giữ React. Trọng tâm portfolio là backend |
| Multi-region, DR, SLA production | Môi trường `dev` duy nhất, tối ưu chi phí |

## 5. Tiêu chí thành công

**Về sản phẩm (demo được):**
- Một user mới đi được trọn luồng: đăng ký → onboarding → 6 hũ được tạo → nhập giao dịch → thấy dashboard → nhận cảnh báo bất thường → hỏi AI "tháng này tôi tiêu lố ở đâu?" và nhận câu trả lời có số liệu đúng.

**Về kỹ thuật (để phỏng vấn):**
- Test coverage lớp domain/service ≥ 80%. Có integration test bằng Testcontainers cho repository và listener.
- Mọi quyết định lớn đều có ADR.
- Có link demo chạy trên AWS. Hạ tầng tái tạo được 100% bằng `cdk deploy`.
- README có số liệu đo được (p95 latency, throughput khi load test).
- Không có secret nào trong git; pipeline CI có gitleaks.

**Về chi phí:**
- Chi phí AWS của project: ≈ **$1/tháng** ở chế độ TEARDOWN (mặc định), ≤ **$25/tháng** khi SLEEP, ≤ **$75/tháng** khi RUNNING đầy đủ (≈ $55 ở Phase 2; thêm `insights-worker` ở backlog Phase 3+ thì ≈ $68). Tổng chi cho tới khi credit hết hạn (04/2027) ≤ **$80**. Xem [cost-and-budget.md](../05-operations/cost-and-budget.md).

## 6. Giả định và ràng buộc

- Có một người phát triển chính, khoảng 15 giờ/tuần (giả định, chưa xác nhận). Trình độ: Java core, OOP, DSA vững; AWS SAA-C03; Spring và hệ phân tán là phần cần học sâu.
- Đây là **repo cá nhân** (`thphuc06/jar-platform`). Những gì có thể đưa lên CV và mốc thời gian: [cv-showcase.md](cv-showcase.md).
- Tài khoản AWS `phuclearning2006` có **$197 credit, dùng chung với project khác, hết hạn khoảng 09/04/2027**.
- Mã nguồn công khai trên GitHub, nên tuyệt đối không commit secret.
