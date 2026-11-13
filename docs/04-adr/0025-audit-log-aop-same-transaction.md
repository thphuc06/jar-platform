# ADR-0025: Audit log ghi bằng AOP trong cùng transaction với nghiệp vụ

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-13
- **Bổ sung cho:** [ADR-0022](0022-idempotency-key-in-business-transaction.md) (cùng nguyên tắc: bản ghi phụ nằm trong transaction nghiệp vụ) và [ADR-0014](0014-money-and-ledger-model.md) (sổ cái append-only).

## Bối cảnh
Mối đe dọa **Repudiation** trong [security.md](../02-architecture/security.md) là "Tôi không tạo giao dịch này". Để trả lời được, mỗi thao tác làm thay đổi dữ liệu phải để lại dấu vết: ai, làm gì, lên đối tượng nào, lúc nào, thuộc request nào (`traceId`). Đây là feature F14 và là chỗ luyện **Spring AOP (JDK proxy và CGLIB) và MDC** trong [skills-matrix](../06-plan/skills-matrix.md).

Khó khăn nằm ở cấu trúc có sẵn của lệnh ghi tiền: vòng retry khi thua cuộc đua `@Version` nằm **ngoài** transaction (ADR-0014), transaction mở bằng `TransactionTemplate` trong service, và `IdempotencyService` chạy bên trong transaction đó (ADR-0022). Một dòng audit đặt sai chỗ sẽ sai theo ba cách: còn lại sau khi nghiệp vụ rollback (một lần thử thua cuộc đua), ghi nhiều dòng cho một lần ghi tiền (mỗi lần retry một dòng), hoặc ghi thêm dòng cho request phát lại mà không thay đổi gì.

## Quyết định

**1. Cơ chế.** Annotation `@Audited(AuditAction.X)` và một `@Aspect` trong `platform.audit`. Với mỗi lần gọi trả về bình thường, aspect ghi **một** dòng vào `audit_log` rồi để transaction commit. Thân method nói điều gì đã xảy ra bằng `Audit.entity(id).with("amount", money)...` (id của đối tượng cùng vài dữ kiện có kiểu), hoặc `Audit.unchanged()` khi lần gọi hóa ra không đổi gì. Không dùng SpEL và không bắt kết quả trả về phải implement interface: id và dữ kiện là Java có kiểu nằm cạnh đoạn code biết chúng. Thiếu `Audit.entity` thì aspect ném lỗi, nghiệp vụ rollback: không có thay đổi nào mà không có dòng audit.

**2. Cùng transaction, nhờ thứ tự advisor.** Chuỗi gọi của một method vừa `@Transactional` vừa `@Audited` là `TransactionInterceptor → AuditAspect → method`. Boot bật transaction management với `order = LOWEST_PRECEDENCE`, trùng với order của aspect, và khi hai advisor bằng order thì Spring không cam kết cái nào ở ngoài. `AuditConfig` khai báo lại `@EnableTransactionManagement(proxyTargetClass = true, order = LOWEST_PRECEDENCE - 1)` (Boot tự lùi ra, chỉ khác mỗi order), còn aspect giữ `LOWEST_PRECEDENCE`. Dòng audit được chèn **trước** commit trên cùng connection, nên rollback (kể cả conflict `@Version` chỉ lộ ra lúc commit) mang theo cả dòng audit, và nếu chèn audit lỗi thì nghiệp vụ cũng rollback. Hai lớp bảo vệ nếu có người làm sai: aspect từ chối chạy khi không có transaction đọc-ghi đang hoạt động, và `AuditStore.append` là `@Transactional(MANDATORY)`.

**3. Vị trí annotation so với retry và idempotency.** Annotation nằm ở method chạy **trong** vòng retry, **trong** transaction và **trong** `action` của `IdempotencyService.execute`, không nằm ở method public bọc bên ngoài. Hệ quả: mỗi lần thử có transaction riêng và dòng audit riêng, lần thua rollback cả hai, nên 3 lần thử chỉ để lại 1 dòng; request phát lại không bao giờ chạm tới `action` nên không thêm dòng.
- `ledger`: `LedgerCommandService` giữ retry, transaction và idempotency; phần nghiệp vụ (kiểm tra luật, bút toán, event) chuyển sang bean riêng `LedgerOperations` có các method `@Audited`. Tách bean vì `@Audited` chỉ hoạt động qua proxy: gọi method cùng class bằng `this` đi vòng qua proxy, và đặt annotation ở method public ngoài cùng thì nằm ngoài cả retry lẫn transaction.
- `goals`, `identity`, `imports`, `insights`: annotation đặt ngay trên method `@Transactional` của service.
- `budgeting`: quyết định phân bổ nằm ở `AllocationDecisionStore.insertIfAbsent` (chỉ ghi dòng audit khi lần gọi này thật sự chèn được; bản lặp trả `false` và nói `Audit.unchanged()`), khép sổ tháng ở `JarPeriodStore.closeAll` (chỉ khi thật sự đóng được dòng nào). Đặt cạnh câu `INSERT`/`UPDATE` là chỗ chắc chắn nhất về "dòng audit có khi và chỉ khi có thay đổi".

**4. Ghi gì.** `actor` là user id nội bộ (JWT `sub` qua `UserDirectory`, một nơi duy nhất là `CurrentUser`, dùng chung với argument resolver của controller); không có người gọi (job khép sổ, seed dev) thì `system`. `trace_id` là giá trị MDC mà log và body lỗi đang mang (hoặc span hiện tại nếu không có request). `occurred_at` lấy từ bean `Clock`. `payload` JSONB nhỏ, chỉ dữ kiện không nhạy cảm: id, số tiền dạng chuỗi, enum, cờ. API của `AuditEntry` có kiểu cho từng loại (không có overload `String` tự do; chuỗi phải giống một mã ngắn) và từ chối các khóa gọi tên dữ liệu nhạy cảm (`email`, `token`, `note`, `prompt`, `message`, `counterparty`...). **Không ghi**: ghi chú, tên hũ, tên mục tiêu, tên tệp, tên hiển thị, nơi nhận/nguồn thu, email, token, prompt hay nội dung LLM ("không lưu payload của LLM vào DB nghiệp vụ", [data-model.md](../02-architecture/data-model.md)).

**5. Danh sách thao tác** (`AuditAction`, tên được lưu nên là hợp đồng, có test khóa): ghi giao dịch, chuyển hũ, chia thu nhập, đổi danh mục/hũ của giao dịch; tạo, sửa, archive hũ; quyết định phân bổ ngân sách; khép sổ tháng; tạo, sửa, archive mục tiêu; sửa hồ sơ, nộp bảng hỏi rủi ro; tạo phiên import sao kê (một dòng cho cả sao kê, không phải mỗi dòng CSV); phản hồi cảnh báo bất thường. **Không audit**: mọi thao tác đọc; từng dòng của import (do batch ghi, đã có `source = IMPORT` trên giao dịch); việc batch bắt đầu chạy (không có danh tính người dùng, chỉ có sự kiện S3); chat với trợ lý AI (xem Hệ quả); tạo user lần đầu và sáu hũ mặc định (chưa có actor).

**6. Append-only.** Trong code không có đường update hay delete (`AuditStore` chỉ có `append` và `page`). Trong DB, migration V11 có trigger từ chối `UPDATE`, `DELETE` (mức dòng) và `TRUNCATE` (mức câu lệnh), kèm test. Index `(actor, occurred_at DESC, id DESC)` phục vụ endpoint đọc.

**7. Đọc.** `GET /api/v1/audit-log?action&entityType&limit&cursor`, chỉ trả dòng của chính người gọi (`actor` lấy từ token, không có tham số nào đổi được), keyset `(occurred_at, id)` giống danh sách giao dịch, lỗi là problem+json. Dòng của `system` không thuộc về ai nên không hiện.

**8. MDC.** `CurrentUser` đặt `userId` (id nội bộ, không phải email) vào MDC ngay khi xác định được người gọi; `TraceIdFilter` xóa cả `traceId` lẫn `userId` trong cùng khối `finally`, nên thread (kể cả virtual thread) được dùng lại không mang dư lượng sang request sau. Dòng log và dòng audit khớp nhau qua `userId` và `traceId`.

## Các phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| Ghi audit sau commit (`@TransactionalEventListener(AFTER_COMMIT)`, hoặc outbox) | Giữa commit và ghi audit có khe hở: crash đúng lúc đó thì có thay đổi mà không có dấu vết, đúng thứ audit phải chặn. Outbox (event của Modulith) đóng được khe hở nhưng thêm độ trễ và một bước bất đồng bộ cho một thứ cần đúng tuyệt đối; và bản ghi audit lại phải đi qua hạ tầng event chỉ để vào bảng cùng DB |
| `BEFORE_COMMIT` listener | Chạy trong transaction nên đúng, nhưng chỉ nhận event; muốn dùng thì mọi thao tác phải publish thêm một event audit, thêm một bản sao của dữ kiện, và vẫn phải tự lo phát lại idempotent |
| Hibernate Envers | Ghi theo thay đổi của entity (bảng `_aud`), không theo ý định ("chuyển 300.000 từ NEC sang EDU"). Không phủ được phần ghi bằng SQL thuần (`JdbcClient`: idempotency, quyết định phân bổ, khép sổ), không biết `traceId` hay actor mà không cấu hình thêm, và nhân đôi mỗi bảng |
| Chỉ trigger trong DB | Không biết user của ứng dụng (kết nối dùng chung một DB user), không biết `traceId`, không biết ý định; phù hợp làm lớp chặn sửa (như V11) chứ không làm nguồn audit |
| Chỉ log (CloudWatch Logs) | Không truy vấn được theo user để trả cho người dùng, retention và chi phí theo log group, log bị mất hoặc lọc được; không có bảo đảm "mỗi thay đổi một dòng" |
| Annotation ở method public ngoài cùng (trước vòng retry) | Cách ngây thơ nhất và sai: aspect nằm ngoài transaction, ghi sau commit hoặc phải mở transaction riêng; mở transaction ngoài cùng thì vòng retry nằm **trong** transaction đã hỏng và không bao giờ thành công |
| SpEL trong annotation hoặc interface trên kết quả để lấy `entityId` | SpEL là chuỗi không kiểm tra lúc biên dịch và dựa vào reflection; interface bắt DTO của API biết về audit và không xử lý được `void` hay "không đổi gì" |

## Hệ quả
- ✅ Không thay đổi nào thiếu dấu vết và không dòng nào mồ côi: dòng audit và thay đổi nghiệp vụ cùng commit hoặc cùng rollback. Có test cho từng trường hợp: commit, exception, commit bị từ chối, retry trong vòng ngoài (3 lần thử, 1 dòng), 12 request đồng thời trên một hũ (số dòng bằng số giao dịch được chấp nhận), cùng `Idempotency-Key` tuần tự và đồng thời (1 dòng), thứ tự advisor.
- ✅ Annotation đặt sai chỗ thì build đỏ thay vì im lặng: luật ArchUnit (public, không `final`/`static`, trên Spring bean, không gọi bằng `this`) có fixture chứng minh luật bắt được lỗi; lúc chạy, `Audit.entity` ném lỗi khi gọi ngoài một lần gọi `@Audited`, nên self-invocation hoặc bean ngoài Spring hỏng ngay ở test đầu tiên đi qua nó.
- ⚠️ Audit lỗi thì thao tác của người dùng lỗi theo (fail-closed). Chủ ý: tốt hơn một thay đổi tiền không dấu vết. Mỗi thao tác tốn thêm một câu INSERT nhỏ trong transaction.
- ⚠️ Trigger chỉ chặn lỗi của code và thao tác tay nhầm: user DB của ứng dụng là chủ bảng nên vẫn có thể `DROP TRIGGER`. Chống giả mạo thật sự cần role riêng chỉ có quyền INSERT, hoặc xuất dần sang S3 Object Lock. Chưa làm, vì hiện ứng dụng chỉ dùng một DB user.
- ⚠️ Một lần `POST /budget/allocations` để lại hai dòng (`INCOME_ALLOCATED` của ledger và `ALLOCATION_DECIDED` của budgeting) vì hai bước nằm ở hai transaction khác nhau (xem `AllocationService`); mỗi dòng cùng transaction với thay đổi của nó.
- ⚠️ Request phát lại của `Idempotency-Key` không để lại dòng nào, nên "ai gửi lại" không được ghi; chỉ lần đầu là thay đổi trạng thái.
- ⚠️ Chat với trợ lý AI **không** được audit: lượt chat chạy trên virtual thread của SSE, không có `SecurityContext` hay MDC (MDC là `ThreadLocal`, không tự sang thread khác, cũng như với `@Async` listener), không đổi dữ liệu tiền, và lịch sử chat cùng bộ đếm `advisor_usage` đã ghi user và thời điểm. Muốn audit thì phải mang actor sang thread đó và chọn transaction của `ChatStore.append`.
- ⚠️ Con trỏ keyset dựa trên `occurred_at` gán trước lúc commit: hai transaction đồng thời của cùng một user có thể commit ngược thứ tự và một dòng cũ đến muộn sau khi client đã lật qua. Với dòng của một người dùng (vài request cùng lúc) chấp nhận được.
- ⚠️ Bảng chỉ lớn lên (khoảng một dòng nhỏ cho mỗi thay đổi). Chưa có chính sách giữ dữ liệu và chưa chia partition; khi cần, chia partition theo tháng của `occurred_at` rồi `DROP` partition cũ hoặc đưa sang S3 trước (`DELETE` bị trigger chặn, `DROP PARTITION` thì không).
- ⚠️ Spring AOP chỉ thấy lời gọi đi qua proxy giữa các bean trong cùng JVM. Nếu một module được tách thành service riêng (backlog `insights-worker`), phần audit của nó phải làm lại ở service đó.

## Xem lại khi
Có hơn một service ghi dữ liệu, cần chống giả mạo (role INSERT-only hoặc S3 Object Lock), bảng đủ lớn để cần partition và retention, cần cho người dùng thấy cả hành động của hệ thống trên dữ liệu của họ, hoặc muốn audit chat với trợ lý AI.
