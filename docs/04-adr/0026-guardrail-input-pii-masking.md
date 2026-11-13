# ADR-0026: Guardrail che dữ liệu nhạy cảm ở đầu vào; denied topic chỉ áp dụng cho đầu vào

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-13
- **Bổ sung cho:** [ADR-0023](0023-advisor-guardrail-and-rate-limit.md) (guardrail qua `ApplyGuardrail`). Thay phần "lọc PII" và "denied topic" của ADR-0023.

## Bối cảnh
Lần chạy thật trên AWS (13/11) lộ hai lỗi của guardrail. Cả hai được tái hiện bằng `aws bedrock-runtime apply-guardrail` trên một guardrail tạm tạo từ đúng định nghĩa CDK (bảng đo ở cuối).

1. **PII người dùng gõ không bị xử lý.** Tin nhắn có CCCD, số thẻ và email không bị chặn hay che: `source=INPUT` trả `NONE`, và không có kết quả đánh giá PII nào. `source=OUTPUT` thì che thẻ và CCCD. Nguyên nhân: các rule PII trong CDK chỉ khai `action`, và Bedrock chỉ áp dụng chúng cho **đầu ra**; muốn áp dụng cho đầu vào phải khai `inputEnabled` và `inputAction`. Hậu quả là PII đi thẳng tới model và nằm nguyên văn trong `chat_messages` (đọc lại được qua `GET .../messages`). Email không có rule nào. Phía code, `BedrockGuardrail` coi mọi can thiệp là "bị chặn" và lấy `outputs` làm câu trả lời, nên nếu chỉ bật rule đầu vào thì người dùng sẽ nhận lại chính tin nhắn đã che như một câu trả lời, và model không được gọi.
2. **Câu từ chối vô hại bị chặn ở đầu ra.** Câu trả lời của model "Tôi không thể lặp lại những thông tin nhạy cảm ... quản lý hũ tiền" bị denied topic `SpecificInvestmentAdvice` đánh dấu, và người dùng nhận câu từ chối về chứng khoán, không liên quan gì tới câu hỏi. Guardrail chỉ có **một** thông điệp cho mọi lần chặn đầu ra (`blockedOutputsMessaging`), nên lý do chặn nào cũng ra cùng câu đó.

## Quyết định

**1. Che (anonymize), không chặn, PII ở đầu vào; đi tiếp bằng văn bản đã che.** Mỗi rule PII bật ở cả hai chiều với action `ANONYMIZE` (`inputEnabled/inputAction` và `outputEnabled/outputAction` khai tường minh). `BedrockGuardrail` trả ba kết quả: `PASS`, `MASKED`, `BLOCKED`. Với `MASKED`, `AdvisorService` đi tiếp bằng văn bản đã che (`{CREDIT_DEBIT_CARD_NUMBER}`, `{EMAIL}`, `{VietnameseCitizenId}`...): người dùng vẫn được giúp, và văn bản đã che là thứ **duy nhất** được lưu, gửi cho model và dùng làm nguồn của `Grounding`. Văn bản gốc chỉ tồn tại trong tham số của hàm xử lý lượt chat, không bao giờ chạm bảng, log hay span.
- Văn bản đã che được ghép từ các giá trị `match` mà guardrail báo, không lấy từ `outputs`: khi cùng một tin nhắn vừa bị chặn vì lý do khác vừa có PII, `outputs` là thông điệp chặn, còn `match` của PII vẫn có (đo được, với `action: NONE`). Nhờ vậy cả tin nhắn bị chặn cũng được lưu ở dạng đã che.
- Thứ tự trong `AdvisorService.stream` đổi: kiểm tra guardrail **trước**, rồi mới lưu tin nhắn USER. Nếu gọi guardrail lỗi thì lượt chat lỗi (đóng an toàn) và chưa lưu gì.
- Tiêu đề phiên chat (web gửi 60 ký tự đầu của câu hỏi đầu tiên) cũng là văn bản người dùng: `createSession` cho nó qua guardrail, lưu bản đã che, bị chặn thì dùng tiêu đề mặc định.

**2. Danh sách PII** (so với thứ một app tài chính cá nhân ở Việt Nam có thể nhận): số thẻ (`CREDIT_DEBIT_CARD_NUMBER`), email (`EMAIL`), CCCD (regex 12 chữ số bắt đầu bằng 0), số điện thoại di động VN (regex), số tài khoản ngân hàng **chỉ khi đứng sau từ báo hiệu** ("STK", "số tài khoản", "số TK", "account number") và **chỉ ở đầu vào**. Không che dãy 6–16 chữ số trơ trọi vì sẽ che luôn số tiền (đã ghi ở ADR-0023). Entity `PHONE` có sẵn không dùng: nó không bắt `0912345678`, regex VN bắt cả bốn cách viết đã thử. Không thêm CMND 9 số, mật khẩu, PIN, CVV: CMND trùng với số tiền, và mức này đã đủ cho phạm vi demo.

**3. Denied topic chỉ áp dụng cho đầu vào** (`outputEnabled: false`). Lý do: (a) việc từ chối **câu hỏi** xin khuyến nghị đã nằm ở đầu vào và vẫn giữ nguyên (cổ phiếu, crypto, vàng, tiếng Việt và tiếng Anh); (b) system prompt cấm khuyến nghị mua bán, và mọi con số của người dùng đến từ tool; (c) lần chặn đầu ra duy nhất quan sát được trên AWS là chặn nhầm, chưa có lần nào chặn đúng; (d) bớt một lần đánh giá chủ đề trên mỗi câu trả lời. Định nghĩa và ví dụ của topic cũng được viết lại ngắn hơn (có vàng, có một ví dụ dạng câu trả lời). **Cái mất đi:** nếu model tự đưa ra một khuyến nghị cụ thể trong câu trả lời cho một câu hỏi vô hại, không còn lớp nào chặn nó ngoài system prompt và dòng disclaimer. Đo trên guardrail tạm: ba câu khuyến nghị mẫu ở đầu ra (tiếng Việt, tiếng Anh, crypto + vàng) bị chặn trước thay đổi và đi qua sau thay đổi.

**4. Thông điệp theo lý do chặn.** `BedrockGuardrail` chọn câu trả lời theo kết quả đánh giá: topic bị chặn thì dùng câu về khuyến nghị đầu tư (`TOPIC_REPLY`, cả đầu vào lẫn đầu ra), lý do khác (prompt attack, content filter) thì dùng thông điệp chung của guardrail. `blockedOutputsMessaging` trong CDK đổi thành câu chung, không còn nhắc chứng khoán.

**5. Không có guardrail** (`jars.advisor.guardrail-id` trống: profile local và test): mọi thứ đi qua, tin nhắn lưu nguyên văn như trước. Profile local mặc định không có model nên lượt chat dừng ở 503 trước khi lưu gì; chỉ khi nhà phát triển tự bật Bedrock ở local mà không đặt guardrail thì PII nằm nguyên văn trong DB local của họ.

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| Chặn tin nhắn có PII | Người dùng mất câu hỏi chỉ vì dán kèm số thẻ; tin nhắn bị chặn vẫn phải được lưu hoặc vứt, nên không đơn giản hơn |
| Tự viết bộ phát hiện PII bằng Java | Guardrail đã làm được (regex và entity); hai bộ luật sẽ lệch nhau. Code Java chỉ thay các giá trị mà guardrail đã báo bằng placeholder |
| Lấy `outputs` của guardrail làm văn bản đã che | Không dùng được khi cùng tin nhắn bị chặn vì lý do khác; và văn bản đó đã bị đổi mã hũ (`NEC` → tên hũ) |
| Giữ denied topic ở đầu ra, chỉ viết lại định nghĩa | Không tái hiện được câu chặn nhầm với 35 câu từ chối tương tự nên không đo được hiệu quả; chỉ loại bỏ ở đầu ra mới chắc chắn hết lỗi này |
| Bật topic ở đầu ra với `outputAction: NONE` (chế độ detect) để đo tỷ lệ chặn nhầm | Hợp lý nếu cần số liệu, nhưng thêm mã phân tích và metric; chưa cần ở quy mô demo |
| Bỏ tiêu đề do client gửi, đặt tên phiên theo tin nhắn đầu đã che | Đúng hơn (không cắt giữa dãy số) nhưng đổi hợp đồng API và web |

## Hệ quả
- ✅ PII người dùng gõ không còn nằm nguyên văn trong `chat_messages`, `chat_sessions.title`, prompt gửi model, `Grounding` hay log. Bảng `advisor_usage` không có nội dung; `audit_log` không ghi lượt chat (ADR-0025) và `AuditEntry` từ chối khóa như `message`/`prompt`. Span và log của Spring AI không chứa prompt: `spring.ai.chat.observations.log-prompt`, `...client.observations.log-prompt` và `spring.ai.tools.observations.include-content` đều mặc định `false`; **đừng bật** trên môi trường có người dùng thật.
- ✅ Thông điệp cho người dùng khớp với lý do bị chặn.
- ⚠️ **Còn lọt:** PII không khớp rule (CMND 9 số, số tài khoản không có từ báo hiệu, CCCD viết có dấu cách, tên và địa chỉ nhà, mật khẩu/PIN); phần dư của số bị cắt ngang ở ký tự thứ 60 của tiêu đề; số điện thoại nước ngoài. Dữ liệu cũ đã lưu trước thay đổi này không được che lại.
- ⚠️ Tin nhắn bị chặn vì chủ đề **và** có PII chỉ được che nếu guardrail báo PII trong cùng lần gọi (đã đo là có; nếu Bedrock đổi hành vi thì bản lưu là nguyên văn).
- ⚠️ Mỗi lần tạo phiên có tiêu đề tốn thêm một lần `ApplyGuardrail` (khoảng 0,4 giây, không đáng kể về tiền) và không nằm trong rate limit.
- ⚠️ Câu khuyến nghị cụ thể do model tự nghĩ ra ở đầu ra không còn bị chặn (xem quyết định 3).
- ⚠️ Chỗ mới: một tin nhắn có PII đi tiếp bằng văn bản đã che, trong đó mã hũ (`NEC`...) đã được đổi thành tên hũ (cùng văn bản guardrail thấy).

## Đo trên AWS (13/11, `ap-southeast-1`, guardrail tạm đã xóa)
Kết quả `ApplyGuardrail` trước và sau, dữ liệu thử là số bịa (thẻ `4111 1111 1111 1111`, CCCD `012345678901`, email ở `example.com`). Trước = định nghĩa CDK cũ; Sau = định nghĩa trong repo.

| Câu thử | Nguồn | Trước | Sau |
|---|---|---|---|
| CCCD `012345678901` | INPUT | NONE | masked (`VietnameseCitizenId`) |
| Thẻ `4111 1111 1111 1111` (có và không có dấu cách) | INPUT | NONE | masked |
| Email `...@example.com` | INPUT | NONE | masked |
| SĐT `0912 345 678`, `0912345678`, `+84 912 345 678`, `0912.345.678` | INPUT | NONE | masked (4/4) |
| "Số tài khoản của tôi là 0123456789012", "STK: 1234567890" | INPUT | NONE | masked |
| CCCD + thẻ + email trong một tin nhắn | INPUT | NONE | masked cả ba |
| "Tôi có nên mua cổ phiếu FPT không?", "Should I buy VNM shares now?", Bitcoin, vàng SJC | INPUT | blocked (topic) | blocked (topic) |
| Hỏi mua cổ phiếu FPT + dán số thẻ | INPUT | **NONE** (lọt) | blocked (topic); thẻ báo với action NONE |
| Số tiền, ngày, "1.500.000 ₫", "2500000 đồng", "số tài khoản của tôi đang có 1.500.000 ₫" | INPUT | NONE | NONE |
| Khuyến nghị mua cổ phiếu FPT (VI), "you should buy VNM shares" (EN), mua Bitcoin bán vàng | OUTPUT | blocked (topic) | NONE (chủ ý, quyết định 3) |
| Câu từ chối "Tôi không thể lặp lại những thông tin nhạy cảm ..." (dựng lại, 35 biến thể, mỗi câu 1–3 lần) | OUTPUT | NONE (không tái hiện được lỗi) | NONE |
| Câu trả lời tài chính bình thường: số tiền VND có dấu chấm, không dấu chấm, ngày 05/11/2026, 12,5% | OUTPUT | NONE | NONE |
| Thẻ + CCCD trong câu trả lời | OUTPUT | masked | masked |
| Email trong câu trả lời | OUTPUT | **NONE** | masked |
| SĐT trong câu trả lời | OUTPUT | NONE | masked |

Ghi chú: câu từ chối nguyên văn của lần chạy trên AWS bị cắt ("...") trong báo cáo lỗi nên không có để thử; 35 câu từ chối viết lại đều qua với định nghĩa cũ, nên **nguyên nhân chính xác của lần chặn nhầm đó không xác định được**. Bỏ topic ở đầu ra loại bỏ cả lớp lỗi này mà không cần biết câu cụ thể. Dòng "mua FPT + số thẻ" lọt ở bản cũ chỉ đo một lần; bản mới chặn được, có thể do định nghĩa và ví dụ mới, có thể do dao động của bộ phân loại.

## Xem lại khi
Có số liệu thực về model tự đưa ra khuyến nghị cụ thể (bật topic ở đầu ra với `outputAction: NONE` để đo), cần chặn tiếng Việt đầy đủ (Standard tier, [runbook](../05-operations/runbook.md#sự-cố-đã-gặp)), hoặc client web đổi cách đặt tên phiên.
