# ADR-0011: AI advisor bằng Spring AI + Amazon Bedrock trong jars-api; LLM không tự tính số

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
Bản cũ có 3 runtime cho AI: Bedrock AgentCore orchestrator (Strands, Python) → AgentCore Gateway → MCP specialist server (planner/service/stock). Thêm vào đó là Cedar policy, encoding gate, OpenSearch retrieval và một agent TypeScript riêng (Dexter) cho cổ phiếu. Phần lớn "AI" thật ra là **toán xác định** viết bằng Python. Bài toán thực chất chỉ là: hiểu câu hỏi → gọi vài tool lấy số liệu → diễn giải bằng tiếng Việt → stream về UI.

Spring AI 2.0 (GA 12/06/2026) có Bedrock Converse, tool calling, streaming và MCP.

## Quyết định
- Module `advisor` trong jars-api dùng **Spring AI 2.0** `ChatClient` với `spring-ai-starter-model-bedrock-converse`.
- Model mặc định **`global.anthropic.claude-haiku-5-5`**; cho phép cấu hình `global.anthropic.claude-sonnet-4-6`.
- **Tool = method Java** gọi API của các module khác (`getJarSummary`, `getSpending`, `getGoalFeasibility`, `getRoadmap`, `getQuote`...). Tool **chỉ đọc**, và `userId` luôn lấy từ security context.
- **Mọi con số do Java tính.** System prompt yêu cầu chỉ dùng số liệu từ tool, và test kiểm tra câu trả lời không chứa số không có trong kết quả tool (eval nhẹ).
- **Bedrock Guardrails:** chặn khuyến nghị mua/bán cụ thể, che PII. Phase 4 cần kiểm tra Spring AI có hỗ trợ `guardrailConfig` không; nếu không thì gọi `ApplyGuardrail` bằng AWS SDK.
- Rate limit theo user (Bucket4j + Valkey); giới hạn `max-tokens`; đo token in/out qua Micrometer.
- **IAM với global inference profile:** cần quyền `bedrock:InvokeModel*` trên ARN của inference profile **và** ARN của foundation model ở các region đích. Kiểm tra lại theo tài liệu Bedrock khi viết CDK.
- RAG (tùy chọn): Bedrock Knowledge Base + S3 Vectors với nội dung `kb/*.md` lấy từ project cũ.

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| Giữ AgentCore + Strands (Python) | 3 runtime và nhiều chi phí cố định; trọng tâm không phải Java; khó debug |
| LangChain4j | Tốt, nhưng Spring AI tích hợp sâu hơn với Boot (auto-config, observability) và là hướng chính thức của hệ Spring |
| Gọi trực tiếp Bedrock SDK | Phải tự viết vòng lặp tool-calling, streaming, memory; Spring AI đã làm sẵn |
| Agent tự trị nhiều bước (ReAct, nhiều agent) | Quá mức cần thiết; khó kiểm soát chi phí và độ đúng |

## Hệ quả
- ✅ Bỏ được khoảng 3 runtime; AI nằm ngay trong Java, đúng điểm cộng "AI" mà JD (MB, NAB) nhắc tới.
- ✅ Nguyên tắc "LLM diễn giải, Java tính" là câu chuyện tốt về độ tin cậy trong fintech.
- ⚠️ Chi phí Bedrock phụ thuộc lượng dùng → rate limit + budget alarm.
- ⚠️ Global inference profile: dữ liệu prompt có thể được xử lý ngoài Singapore (xem ADR-0005).

## Xem lại khi
Cần workflow AI dài nhiều bước có trạng thái (khi đó cân nhắc Bedrock Agents hoặc AgentCore), hoặc cần model chạy tại chỗ.
