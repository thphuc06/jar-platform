# ADR-0004: Java 25 LTS + Spring Boot 4.1

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-23

## Bối cảnh
JD ở ngân hàng và fintech Việt Nam yêu cầu Java 17+ và Spring Boot. Ngày 2026-10-23:
- JDK 25 là bản LTS mới nhất (9/2025); JDK 27 (9/2026) không phải LTS.
- Spring Boot 4.0 ra 11/2025, 4.1 ra 06/2026, bản mới nhất là `v4.1.2`. Boot 4 vẫn yêu cầu tối thiểu Java 17 và hỗ trợ Java 25.
- Spring Modulith 2.1.2, Spring AI 2.0.1 (cần Boot 4) và Spring Cloud AWS 4.2.0 đều đã có bản stable.

## Quyết định
- **JDK 25 (Eclipse Temurin)**, bật **virtual threads** (`spring.threads.virtual.enabled=true`). Dùng Spring MVC (blocking) thay vì WebFlux.
- **Spring Boot 4.1.x**, build bằng **Maven** (có Maven Wrapper), dùng BOM của Boot, Spring AI và Spring Cloud AWS.
- **Bước bắt buộc ở Phase 0:** smoke test Boot 4.1 + Spring Cloud AWS 4.2 (bảng tương thích chính thức của Spring Cloud AWS 4.x mới ghi Boot 4.0.x). Nếu không tương thích thì hạ xuống **Boot 4.0.x** và ghi lại kết quả ở mục dưới.

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| Java 21 + Boot 3.5 | Đang là bản phổ biến nhất trong doanh nghiệp, nhưng Spring AI 2.0 yêu cầu Boot 4. Boot 3.5 sẽ sớm hết OSS support. Code Boot 4 vẫn dễ chuyển ngữ cảnh sang Boot 3 khi phỏng vấn |
| JDK 27 | Không phải LTS; hết support sau 6 tháng |
| WebFlux (reactive) | Virtual threads cho throughput tương đương với code blocking dễ đọc, dễ debug. JPA/JDBC là blocking. Chỉ phần streaming của Spring AI dùng `Flux` |
| Gradle | Tốt, nhưng Maven phổ biến hơn trong JD ngân hàng Việt Nam và dễ đọc với người mới |

## Hệ quả
- ✅ Dùng được record, pattern matching, sealed interface (cho event, result type), virtual threads.
- ✅ Phù hợp xu hướng 2026–2027; tránh phải nâng cấp Boot 3 → 4 sau này.
- ⚠️ Một số thư viện bên thứ ba có thể chưa hỗ trợ Boot 4 / Jackson 3 (springdoc, Resilience4j starter). Kiểm tra từng thư viện trước khi thêm vào.
- ⚠️ Máy dev hiện có **JDK 21**, cần cài thêm JDK 25.

## Kết quả smoke test (điền ở Phase 0)
- [ ] Boot 4.1.x + Spring Cloud AWS 4.2.0: SQS listener + Secrets Manager import: ☐ OK / ☐ Lỗi → hướng xử lý: …
- [ ] Spring AI 2.0.x Bedrock Converse với `global.anthropic.claude-haiku-5-5`: ☐ OK

## Xem lại khi
JDK 29 LTS ra mắt (dự kiến 9/2027), hoặc smoke test ở Phase 0 thất bại.
