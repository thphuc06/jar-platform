# ADR-0019: Java 21 LTS thay cho Java 25 (Spring Boot 4.1 giữ nguyên)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-12
- **Supersedes:** phần chọn JDK của [ADR-0004](0004-java-25-spring-boot-4.md). Các quyết định còn lại của ADR-0004 (Spring Boot 4.1, virtual threads, Spring MVC, Maven) vẫn giữ nguyên.

## Bối cảnh
ADR-0004 chọn JDK 25. Khi bắt đầu phase tiếp theo, chủ repo quyết định chuyển sang **Java 21**:
- Máy dev đã có JDK 21 (Temurin 21.0.13) và không cần cài thêm JDK 25.
- Phần lớn ngân hàng và fintech Việt Nam đang chạy Java 17 hoặc 21, nên Java 21 gần với môi trường mà JD tuyển dụng mô tả hơn.
- Spring Boot 4.1 yêu cầu tối thiểu Java 17 nên Java 21 được hỗ trợ đầy đủ. Đã chạy `./mvnw verify` (12 test, Modulith, Spotless, JaCoCo) trên JDK 21.0.13 với Boot 4.1.2.
- Code hiện tại không dùng tính năng ngôn ngữ nào chỉ có từ Java 22 trở lên.

## Quyết định
- **JDK 21 (Eclipse Temurin)**, `java.version=21` trong `pom.xml`, CI dùng `setup-java` Temurin 21.
- Image chạy: `eclipse-temurin:21-jre` (multi-arch, có arm64).
- Thêm `maven-enforcer-plugin` yêu cầu JDK ≥ 21 để lỗi khi chạy nhầm JDK có thông báo rõ ràng.
- Vẫn bật virtual threads (`spring.threads.virtual.enabled=true`).

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| Giữ JDK 25 (ADR-0004) | Là LTS mới hơn, nhưng cần cài thêm JDK và ít khớp với môi trường doanh nghiệp Việt Nam hiện tại |
| JDK 17 | Vẫn phổ biến, nhưng không có virtual threads và record pattern đầy đủ; Boot 4 đã bắt đầu dùng tính năng mới hơn |

## Hệ quả
- ✅ Khớp môi trường mà JD ngân hàng mô tả; build chạy ngay trên máy dev hiện có.
- ✅ Vẫn dùng được record, sealed interface, pattern matching cho `switch`, virtual threads.
- ⚠️ **Virtual thread pinning:** trên Java 21, virtual thread bị "ghim" vào platform thread khi đang chạy trong khối `synchronized` hoặc gọi native. JEP 491 sửa cho `synchronized` ở JDK 24. Với Aurora và JDBC của project, tránh giữ `synchronized` quanh lời gọi blocking, và theo dõi bằng `-Djdk.tracePinnedThreads=full` khi load test.
- ⚠️ Mất một số cải tiến runtime của JDK 22–25 (ví dụ Scoped Values, Compact Object Headers, AOT cache). Không ảnh hưởng đến tính đúng đắn của project.
- ⚠️ Mọi tài liệu từng ghi "Java 25" được cập nhật thành Java 21; ADR-0004 và ADR-0006 giữ nguyên nội dung lịch sử.

## Xem lại khi
Cần tính năng chỉ có ở JDK mới hơn (ví dụ giảm pinning cho `synchronized` ở thư viện bên thứ ba, AOT cache để rút ngắn cold start), hoặc khi JDK 25 trở thành mặc định ở môi trường mục tiêu.
