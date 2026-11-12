# ADR-0004: Java 25 LTS + Spring Boot 4.1

- **Trạng thái:** Accepted. Phần chọn JDK đã bị thay bởi [ADR-0019](0019-java-21-lts.md) (Java 21); các quyết định còn lại vẫn có hiệu lực
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

## Kết quả smoke test (P0.5, 2026-10-23)

Thực hiện bằng một project tạm (không commit) dùng chung parent và BOM với `jars-api`. App chạy thật trên AWS ap-southeast-1 với tài nguyên tạm (SQS queue + secret), xóa ngay sau khi test.

| Kiểm tra | Kết quả |
|---|---|
| Runtime | Boot **4.1.2**, Framework **7.0.10**, Java **25.0.4.1** |
| Spring Cloud AWS **4.2.0** + Boot 4.1.2: `spring.config.import=aws-secretsmanager:` | ✅ property được resolve |
| `SqsTemplate.send` → `@SqsListener` nhận | ✅ round-trip khoảng 20 ms |
| Spring AI **2.0.1** Bedrock Converse | ✅ với `global.anthropic.claude-haiku-4-5-20251001-v1:0` (14 token vào, 4 token ra). ❌ `global.anthropic.claude-haiku-5-5`: `AccessDeniedException ... is not available for this account` (inference profile *liệt kê* được nhưng account không *gọi* được) |
| Spring Modulith **2.1.2** `ApplicationModules.verify()` | ✅ |
| Xung đột dependency | Không có: chỉ có Jackson **3.1.7** (không lẫn Jackson 2); không có `spring-cloud-commons` (nên không có compatibility verifier); 0 dòng WARN khi chạy |

**Phát hiện cần áp dụng vào project:**
1. Credential `aws login` (`login_session` trong `~/.aws/config`) chỉ đọc được bởi AWS SDK Java khi có module **`software.amazon.awssdk:signin`** (scope `runtime`) trên classpath. Thêm module này khi jars-api bắt đầu gọi AWS.
2. Đặt `spring.config.import=aws-secretsmanager:...` trong `application.yaml` chung khiến **mọi test** (kể cả test Modulith) phải gọi AWS. Phải đặt trong `application-aws.yaml` và có tiền tố `optional:`.
3. Listener của Spring Cloud AWS SQS chạy trên **thread pool riêng** (`sqsListenerEndpointContainer#…`, platform thread), **không** dùng virtual thread dù đã bật `spring.threads.virtual.enabled`. Nếu cần thì cấu hình executor riêng cho container ở Phase 3.
4. Git Bash trên Windows tự đổi tham số dạng `/jars/...` thành đường dẫn file → phải dùng `MSYS_NO_PATHCONV=1` khi gọi AWS CLI với tên bắt đầu bằng `/`.
5. *(phát hiện ở P0.7)* `spring-cloud-aws-dependencies` 4.2.0 **tự import** `spring-modulith-bom` **2.1.0** (cùng với BOM của AWS SDK 2.47.4, spring-cloud-commons, spring-cloud-stream). Khi nhiều BOM cùng quản lý một artifact thì **BOM import trước thắng**. Do đó `spring-modulith-bom` phải đứng **trước** `spring-cloud-aws-dependencies` trong `pom.xml`, nếu không Modulith sẽ âm thầm bị hạ xuống 2.1.0 (đã gặp thật, phát hiện bằng `./mvnw dependency:tree -Dincludes=org.springframework.modulith`).

## Xem lại khi
JDK 29 LTS ra mắt (dự kiến 9/2027), hoặc smoke test ở Phase 0 thất bại.
