# Bản đồ tài liệu

Tài liệu được viết theo kiểu **docs-as-code**: nằm cùng repo với code, review cùng PR và cập nhật cùng commit. Ngôn ngữ chính là tiếng Việt, thuật ngữ kỹ thuật giữ nguyên tiếng Anh.

## Mục lục

| Thư mục / file | Trả lời câu hỏi | Khi nào cập nhật |
|---|---|---|
| [01-product/vision-and-scope.md](01-product/vision-and-scope.md) | Sản phẩm giải quyết vấn đề gì? Cho ai? Trong phạm vi và ngoài phạm vi là gì? | Khi thêm hoặc bỏ feature |
| [01-product/cv-showcase.md](01-product/cv-showcase.md) | Hoàn thành thì demo được gì? Viết mục project trên CV thế nào? Chuẩn bị câu chuyện phỏng vấn nào? | Cuối mỗi phase (điền số liệu thật) |
| [02-architecture/overview.md](02-architecture/overview.md) | Hệ thống gồm những phần nào và nói chuyện với nhau ra sao (C4)? Dùng stack nào? | Khi thêm hoặc bỏ thành phần lớn |
| [02-architecture/aws-infrastructure.md](02-architecture/aws-infrastructure.md) | Từng dịch vụ AWS dùng để làm gì, cấu hình ra sao, thuộc CDK stack nào, tốn bao nhiêu? | Khi đổi hạ tầng |
| [02-architecture/modules.md](02-architecture/modules.md) | jars-api chia module thế nào? Module nào được gọi module nào? | Khi thêm module hoặc đổi ranh giới |
| [02-architecture/data-model.md](02-architecture/data-model.md) | Bảng, quan hệ, quy ước dữ liệu (tiền, thời gian, ID) | Mỗi Flyway migration |
| [02-architecture/event-catalog.md](02-architecture/event-catalog.md) | Event nào, ai phát, ai nghe, đảm bảo giao nhận ra sao? | Mỗi khi thêm hoặc đổi event |
| [02-architecture/flows.md](02-architecture/flows.md) | Các luồng chính chạy từng bước như thế nào (sequence diagram)? | Khi đổi luồng |
| [02-architecture/security.md](02-architecture/security.md) | Xác thực, phân quyền, IAM, secret, threat model | Khi đổi auth, IAM hoặc dữ liệu nhạy cảm |
| [02-architecture/well-architected-review.md](02-architecture/well-architected-review.md) | Thiết kế đúng best practice AWS ở đâu, cố ý lệch chuẩn ở đâu và vì sao (6 trụ cột + GenAI Lens + FSI Lens) | Cuối Phase 2 và sau backlog Phase 3+ (sau khi chạy WA Tool) |
| [03-api/api-contract.md](03-api/api-contract.md) | Endpoint REST/SSE, format lỗi, idempotency, mapping với màn hình UI | Mỗi khi đổi API |
| [04-adr/](04-adr/README.md) | **Vì sao** chọn A mà không chọn B? | Mỗi quyết định kiến trúc mới |
| [05-operations/cost-and-budget.md](05-operations/cost-and-budget.md) | Tốn bao nhiêu tiền, làm sao để không bị "cháy" credit? | Khi thêm dịch vụ có tính phí |
| [05-operations/local-development.md](05-operations/local-development.md) | Chạy project trên máy local thế nào? | Khi đổi cách chạy local |
| [05-operations/deployment.md](05-operations/deployment.md) | Deploy lên AWS lần đầu và các lần sau ra sao? CI/CD hoạt động thế nào? | Khi đổi pipeline hoặc stack |
| [05-operations/runbook.md](05-operations/runbook.md) | Các thao tác vận hành: sleep/wake, xem log, xử lý DLQ, rollback | Khi có thao tác vận hành mới |
| [06-plan/roadmap.md](06-plan/roadmap.md) | Đang ở đâu, việc tiếp theo là gì, khi nào xong? | **Mỗi tuần** |
| [06-plan/skills-matrix.md](06-plan/skills-matrix.md) | Feature nào rèn kỹ năng gì, phỏng vấn sẽ hỏi gì? | Khi thêm feature |
| [07-legacy/hackathon-migration.md](07-legacy/hackathon-migration.md) | Project cũ có gì, cái gì được giữ, cái gì bị bỏ, vì sao? | Hiếm khi |
| [engineering/conventions.md](engineering/conventions.md) | Quy ước git, code style, test, Definition of Done | Khi team thống nhất quy ước mới |
| [glossary.md](glossary.md) | Thuật ngữ: Jar, CUSUM, ACU, outbox, PKCE... | Khi gặp thuật ngữ mới |
| [diagrams/](diagrams/README.md) | Sơ đồ AWS vẽ bằng code (Python `diagrams`) | Khi đổi hạ tầng |
| [learning/](learning/README.md) | Ghi chú học tập theo từng bước của bản cài đặt đầu tiên (P0.x–P3.x) | Không còn viết thêm từ Phase 2 |

## Quy tắc giữ tài liệu luôn đúng

1. **Đổi code thì đổi doc trong cùng PR.** Checklist trong PR template có mục "Docs updated?".
2. **ADR không sửa lại nội dung.** Khi đổi quyết định, viết ADR mới với trạng thái `Supersedes ADR-xxxx`, còn ADR cũ chuyển sang `Superseded`.
3. **Diagram là code.** Sửa file `.py` trong `diagrams/src/`, render lại PNG rồi commit cả hai.
4. **Mermaid dùng cho luồng và sơ đồ logic** (GitHub tự render), còn `diagrams` dùng cho sơ đồ hạ tầng AWS (cần icon chính thức).
5. **Roadmap cập nhật hằng tuần:** tick checkbox, ghi ngày, ghi lại vướng mắc.
6. **Mỗi con số chi phí đều ghi nguồn và ngày lấy giá.** Giá AWS thay đổi theo thời gian.
7. **Không bao giờ ghi secret vào tài liệu:** không ghi password, token hay connection string có mật khẩu.
