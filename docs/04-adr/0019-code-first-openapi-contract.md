# ADR-0019: OpenAPI sinh từ code, commit vào repo và được test giữ đúng

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-27

## Bối cảnh

`web` (TypeScript) cần một client có kiểu cho `jars-api`. Đến P1.9, API đã có 18 operation. Mỗi operation đều được test bằng integration test, nhưng mô tả của chúng nằm rải rác trong [api-contract.md](../03-api/api-contract.md). Nếu không có một nguồn sự thật mà máy đọc được, UI sẽ phải viết tay type cho từng response, và mỗi lần API đổi là UI có thể sai mà không ai biết.

Ràng buộc:

- Chỉ có một người phát triển, nên không đủ sức giữ đồng bộ hai bản mô tả (YAML viết tay và code).
- Những quy tắc quan trọng nhất của API nằm trong code, không nằm trong annotation: tiền là string, field nào có thể `null`, scope nào cho method nào. Spec sinh tự động mà bỏ sót các quy tắc này thì có cũng như không.
- Repo là public. Tài liệu API không được lộ ra ở môi trường đã deploy nếu không cần.

## Quyết định

1. **Code-first:** springdoc **3.1.1** sinh OpenAPI **3.1** từ controller. File sinh ra được **commit** ở `contracts/openapi/jars-api.yaml`. `web` sinh type từ file này bằng `openapi-typescript`.
2. **Quy tắc chung đặt ở một chỗ** (`platform.web`):
   - `DtoSchemaConverter` đọc JSpecify `@Nullable` thành nullable, và `@VndAmount` thành string có `pattern`.
   - `ApiContractRules` làm 4 việc:
     - gắn security cho từng operation theo đúng quy tắc của `SecurityConfig`;
     - khai báo `401`, `403` và `default` (Problem RFC 9457);
     - thêm header `Location` và `Idempotent-Replayed`;
     - đánh dấu **mọi** property của response là `required`.
   - Controller không phải chép lại những điều này bằng annotation.
3. **`OpenApiContractTest` giữ cho file nói thật:**
   - file đã commit phải khớp **từng byte** với spec mà app đang sinh;
   - security ghi trong spec phải đúng với filter chain (gọi từng operation không có token, rồi với scope sai);
   - response thật của **mọi** operation phải khớp schema của nó.
   - Muốn đổi API thì chạy `-Dopenapi.update=true`, rồi review diff của YAML trong cùng commit.
4. `operationId` là tên handler method, nên tên method phải duy nhất trong toàn API.
5. `/v3/api-docs` và Swagger UI **chỉ bật ở profile `local`**. Ở mọi môi trường khác, các path này trả 404.

## Các phương án đã cân nhắc

| Phương án | Ưu | Nhược | Vì sao không chọn |
|---|---|---|---|
| Design-first (viết YAML trước, sinh interface Java bằng openapi-generator) | Thống nhất contract trước khi code; hợp khi nhiều team | Code sinh ra khó đọc; validation và quy tắc nghiệp vụ vẫn phải viết tay; hai nguồn cần khớp nhau | Một người làm, API đã có 18 operation chạy thật; chi phí chuyển đổi lớn mà không được thêm gì |
| Code-first nhưng **không commit** file (client đọc `/v3/api-docs` lúc build) | Không có file để quên cập nhật | Build của `web` cần backend đang chạy; không review được thay đổi API trong diff; không có lịch sử | Mất khả năng nhìn thấy thay đổi API trong PR, đây là lợi ích chính |
| `springdoc-openapi-maven-plugin` sinh file ở phase `integration-test` | Không cần viết test | Phải chạy app thật (cần DB) trong Maven; chỉ sinh file chứ không kiểm tra spec có nói thật hay không | Test bằng MockMvc nhanh hơn và kiểm tra được nhiều hơn (security, response thật) |
| Annotation `@Schema` / `@Operation` / `@ApiResponse` trên từng controller | Cách phổ biến, dễ tra | 18 operation × nhiều annotation lặp lại; quên một chỗ là spec sai mà không ai biết | Quy tắc chung nên viết một lần và được test chứng minh |

## Hệ quả

- ✅ TypeScript client có kiểu chính xác: response không còn `?` thừa, field có thể null là `T | null`, tiền là `string`, header `Idempotency-Key` bắt buộc ngay lúc compile.
- ✅ Mọi thay đổi API hiện ra dưới dạng diff YAML trong commit; build fail nếu quên cập nhật file.
- ✅ Ba loại sai được test bắt: thiếu `@Nullable`, ghi sai security, Jackson bỏ field `null`. Đã kiểm chứng bằng 6 mutant ở P1.10.
- ⚠️ Gánh nặng mới:
  - Mỗi operation mới phải được gọi thật trong `everyOperation_realResponseFitsItsSchema`; test fail nếu thiếu.
  - Field nào có thể null mà quên `@Nullable` thì chỉ bị bắt khi có một response thật trả `null` ở field đó.
  - Cách giảm thiểu: DTO chép nullability từ view record của domain, vốn đã có JSpecify.
- ⚠️ Tên method trở thành một phần của contract: đổi tên method là đổi `operationId`. Contract test sẽ báo diff, nên không đổi một cách vô tình được.
- ⚠️ springdoc phụ thuộc chặt vào version Spring Boot. Cách giảm thiểu: chỉ nâng cùng dòng Boot (3.1.x ↔ Boot 4.1.x), và contract test sẽ cho thấy output có đổi hay không.

## Xem lại khi

- Có team hoặc client thứ hai (ví dụ app mobile) cần thống nhất API **trước** khi có code. Khi đó chuyển sang design-first.
- springdoc không theo kịp một bản Boot mới. Khi đó cân nhắc sinh spec bằng thư viện khác, hoặc viết tay một phần.
