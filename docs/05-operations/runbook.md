# Runbook

Các thao tác vận hành thường gặp. Mọi lệnh giả định đã có `AWS_PROFILE=jars-dev` và `AWS_REGION=ap-southeast-1`.

## Chuyển chế độ chi phí

| Muốn | Lệnh | Mất bao lâu |
|---|---|---|
| RUNNING → SLEEP | `cdk deploy JarsApp -c apiDesiredCount=0 -c imageTag=<sha>` | khoảng 2 phút |
| SLEEP → RUNNING | `cdk deploy JarsApp -c apiDesiredCount=1 -c imageTag=<sha>` | khoảng 3 phút (+ Aurora resume ở request đầu) |
| → TEARDOWN | `cdk destroy JarsApp` | khoảng 10–15 phút (CloudFront) |
| TEARDOWN → RUNNING | `cdk deploy JarsApp -c imageTag=<sha>` | khoảng 10–15 phút |

Lấy SHA của image mới nhất: `aws ecr describe-images --repository-name jars/jars-api --query 'sort_by(imageDetails,&imagePushedAt)[-1].imageTags[0]' --output text`.

## Kiểm tra Aurora có đang pause không

```bash
aws cloudwatch get-metric-statistics --namespace AWS/RDS --metric-name ServerlessDatabaseCapacity \
  --dimensions Name=DBClusterIdentifier,Value=jars-dev-db --statistics Maximum --period 300 \
  --start-time $(date -u -d '-3 hours' +%FT%TZ) --end-time $(date -u +%FT%TZ)
```

Nếu > 0 liên tục mà không có ai dùng thì tìm thứ đang giữ kết nối:

```sql
SELECT pid, usename, application_name, client_addr, state, backend_start
FROM pg_stat_activity WHERE datname = 'jars';
```

## Xem log

```bash
aws logs tail /jars/dev/api --follow --since 15m
aws logs tail /jars/dev/api --since 1h --filter-pattern '"level":"ERROR"'
```

Tìm theo trace: `--filter-pattern '"traceId":"<id>"'`.

## Từ một lỗi tới trace trong X-Ray

Mọi response lỗi có `traceId` (body RFC 9457) và mọi response có header `traceparent`. Trace id đó chính là id trong X-Ray (`TraceIdFilter` lấy từ span hiện tại):

```bash
aws xray batch-get-traces --region ap-southeast-1 --trace-ids "1-<8 ký tự đầu>-<24 ký tự còn lại>"
```

X-Ray hiển thị trace id dạng `1-xxxxxxxx-yyyyyyyyyyyyyyyyyyyyyyyy`: 8 ký tự hex đầu của id W3C, rồi 24 ký tự còn lại. Trên console: CloudWatch → Traces → tìm theo trace id. Collector ghi log của chính nó vào `/jars/dev/api` với prefix `otel/`; không có trace thì xem ở đó trước (thường là thiếu quyền X-Ray hoặc sai cấu hình).

## Dashboard, alarm và load test

- Dashboard `jars-dev` (CloudWatch → Dashboards): request, 5xx, p50/p95/p99 từ ALB, CPU/memory ECS, ACU Aurora, sự kiện nghiệp vụ, độ sâu queue.
- Alarm: `jars-dev-api-5xx-rate`, `jars-dev-api-p95-latency`, `jars-dev-aurora-not-paused`, `jars-dev-ledger-reconciliation-mismatches` (trong code, chưa deploy), alarm DLQ (gồm `jars-dev-reconciliation-dlq-not-empty`, chưa deploy). Gửi về SNS `jars-dev-alarms`; topic chỉ có người nhận khi deploy `JarsFoundation` kèm `-c alertEmail=<địa chỉ>`.
- Load test: `pwsh scripts/load-test.ps1` (mặc định 5 user, tối đa 20 virtual user, 2 phút ổn định). Cần Docker. Kết quả p95 in ra cuối và lưu JSON ở thư mục tạm. Chạy thử ngắn: thêm `-e STEADY=20s` vào lệnh `docker run` trong script, hoặc sửa tham số `-PeakVus`.

## Xử lý message trong DLQ

1. Xem số lượng: `aws sqs get-queue-attributes --queue-url <dlq-url> --attribute-names ApproximateNumberOfMessages`.
2. Đọc thử 1 message (không xóa): `aws sqs receive-message --queue-url <dlq-url> --visibility-timeout 0 --max-number-of-messages 1`.
3. Tìm nguyên nhân trong log (dùng `eventId` hoặc `traceparent`), sửa code rồi deploy.
4. Redrive về queue gốc: `aws sqs start-message-move-task --source-arn <dlq-arn>` (consumer idempotent nên chạy lại an toàn).
5. Ghi lại sự cố vào mục "Sự cố đã gặp" bên dưới.

`jars-reconciliation-dlq` (alarm `jars-dev-reconciliation-dlq-not-empty`, trong code, chưa deploy) có nghĩa là đối soát đêm đó **thất bại 3 lần liên tiếp** (không đọc được DB), tức số dư chưa được kiểm tra. Xem log lỗi trong `/jars/dev/api`; message giữ trong DLQ 14 ngày. Không cần redrive: message gốc chỉ sống 1 giờ và đêm sau lịch tự gửi lại; chỉ cần sửa nguyên nhân (thường là Aurora không resume kịp hoặc mất kết nối) rồi chờ lần chạy tiếp theo, hoặc gửi tay `{"job":"LEDGER_RECONCILIATION"}` vào `jars-reconciliation`. Xóa message khỏi DLQ để alarm về OK.

## Alarm đối soát sổ cái (`ledger-reconciliation-mismatches`)

Job `ReconciliationService` chạy 02:30 mỗi đêm (giờ VN, khi `JarsApp` đang chạy) và so `jars.balance` với `SUM(jar_entries.amount)` của từng hũ ([flows.md F6](../02-architecture/flows.md#f6)). Alarm kêu nghĩa là có ít nhất một hũ lệch. **Không có gì được tự sửa.**

1. Tìm chi tiết trong log group của API: `aws logs filter-log-events --log-group-name /jars/dev/api --filter-pattern '"Ledger mismatch"' --start-time <epoch-ms>`. Mỗi dòng có `jar`, `stored`, `computed`, `difference` (tối đa 50 dòng mỗi lần chạy, lệch nhiều nhất trước; dòng cuối cho biết còn bao nhiêu hũ không liệt kê).
2. Kiểm tra lại bằng tay trên DB (chỉ đọc):
   ```sql
   SELECT j.id, j.balance AS stored, COALESCE(SUM(e.amount), 0) AS computed
   FROM jars.jars j LEFT JOIN jars.jar_entries e ON e.jar_id = j.id
   GROUP BY j.id HAVING j.balance <> COALESCE(SUM(e.amount), 0);
   ```
3. Quyết định bên nào sai (số dư cache hay thiếu bút toán) bằng cách xem `jar_entries` và `audit_log` của hũ đó; sửa bằng một bút toán điều chỉnh hoặc cập nhật `balance` có kiểm soát, rồi ghi vào "Sự cố đã gặp".
4. Lần chạy sạch kế tiếp báo `mismatches = 0` và alarm tự về OK. Nếu `JarsApp` bị destroy hoặc task bị thay thế, metric biến mất và alarm cũng về OK dù hũ vẫn lệch; lần chạy đêm sau sẽ báo lại.

## Import sao kê bị kẹt hoặc thất bại

| Triệu chứng | Kiểm tra | Xử lý |
|---|---|---|
| Import ở `AWAITING_UPLOAD` mãi | Tệp có lên S3 không: `aws s3 ls s3://jars-dev-statements-<account>/uploads/<userId>/`; message có tới `jars-imports` không (metric `NumberOfMessagesReceived`) | Không có tệp: lỗi CORS hoặc URL hết hạn (10 phút), user tải lại. Có tệp mà không có message: kiểm tra rule `statement-uploaded` và việc bật EventBridge trên bucket |
| `FAILED` với lý do của tệp (thiếu cột, không phải UTF-8, quá 5 MB, đã nhập) | `GET /api/v1/imports/{id}` → `failureReason` | Lỗi của tệp, chạy lại không đổi được kết quả; user sửa tệp rồi tạo phiên nhập mới |
| `FAILED` với "lỗi hệ thống" | Log theo `traceId`; `SELECT status, exit_message FROM batch_job_execution ...` | Sửa nguyên nhân, rồi redrive message từ `jars-imports-dlq` (hoặc user tải lại đúng tệp): **cùng job instance** chạy tiếp từ chunk cuối đã commit, idempotency key chặn ghi trùng |
| `PROCESSING` mãi sau khi task bị dừng giữa chừng | `batch_job_execution.status = 'STARTED'` mà không có task nào chạy | Spring Batch không tự restart execution còn `STARTED`: đặt `status='FAILED', end_time=now()` cho execution đó rồi redrive message |

## Đổi secret DB

Secret do CDK tạo (`Credentials.fromGeneratedSecret`) và lưu trong Secrets Manager. Ở dev **chưa bật rotation tự động**: Lambda rotation chạy trong VPC cần đường tới Secrets Manager (NAT hoặc interface endpoint), mà kiến trúc hiện tại không có (ADR-0013). Cách rotate thủ công:
1. `aws secretsmanager put-secret-value` với password mới, rồi `ALTER USER ... PASSWORD` trên DB (hoặc `aws rds modify-db-cluster --master-user-password`).
2. `aws ecs update-service --cluster jars-dev --service jars-api --force-new-deployment` để task đọc lại secret.

## Dọn dẹp hằng tháng

- [ ] Snapshot Aurora cũ: `aws rds describe-db-cluster-snapshots --snapshot-type manual`.
- [ ] Image ECR thừa (lifecycle policy giữ 10 image, kiểm tra lại).
- [ ] Log group không còn dùng.
- [ ] Ghi số liệu vào [nhật ký chi phí](cost-and-budget.md#6-nhật-ký-chi-phí-thực-tế).

## Sự cố đã gặp

| Ngày | Triệu chứng | Nguyên nhân | Cách xử lý | Bài học |
|---|---|---|---|---|
| 2026-11-12 | Container thoát ngay khi khởi động với `SPRING_PROFILES_ACTIVE=aws` (chạy từ IDE và test thì bình thường) | `TokenController` của `/dev/token` là class lồng nhau, bị component scan nhặt dù không có điều kiện `@Profile("local")`; ở profile `aws` nó thiếu bean khóa dev | Gắn cùng điều kiện `@Profile` và `@ConditionalOnProperty` lên class lồng; thêm `AwsProfileTest` khởi động đúng profile production | Chỉ chạy image thật (read-only FS, giới hạn Fargate) mới lộ lỗi này. Test không chạy đúng profile production thì chưa chứng minh được gì |
| 2026-11-12 | Container báo thiếu region ngay lúc khởi động (SQS auto-config) | Trên máy dev có `~/.aws/config` nên region được suy ra, trong container thì không | Ghim `spring.cloud.aws.region.static=${AWS_REGION:ap-southeast-1}`; `spring.cloud.aws.sqs.enabled` chỉ bật ở profile `aws` | Đừng để cấu hình cloud phụ thuộc vào file nằm trên máy dev |
| 2026-11-12 | `cdk deploy JarsApp` mất 18 phút | CloudFront VPC origin mất 9 phút để tạo, distribution thêm 3 phút, ECS service ổn định sau 3,4 phút | Không có cách rút gọn; deploy lần hai (chỉ thêm web) mất khoảng 4 phút | Dựng `JarsApp` trước khi cần demo ít nhất 20 phút. Lần đầu phải deploy hai lượt vì web cần `SpaClientId` do chính `JarsApp` tạo ra (xem [deployment §2](deployment.md#2-thứ-tự-deploy)) |
| 2026-11-12 | `cdk deploy` in cảnh báo `current credentials could not be used to assume ...deploy-role... Proceeding anyway` | CLI đang dùng credential root, không đóng được role `cdk-...-deploy-role` | Vẫn deploy được vì root có đủ quyền; không tạo access key | Đây là hệ quả của việc hoãn [ADR-0018](../04-adr/0018-human-access-iam-user-aws-login.md). Làm lại khi có IAM user hoặc OIDC cho CI |
| 2026-11-12 | Smoke test: `AdminCreateUser` trả `Invalid email address format`, sau đó mọi call đều 401 | PowerShell coi dấu phẩy ngoài dấu nháy là toán tử mảng, nên `Name=email,Value=$u` bị tách thành hai đối số | Bọc từng thuộc tính trong dấu nháy: `"Name=email,Value=$u"` | Lỗi nằm ở script chứ không ở hệ thống; nhìn thông báo lỗi của lệnh đầu tiên trước khi đọc các lỗi 401 phía sau |
| 2026-11-12 | Smoke test: bước SSE nhận `401` dù token đúng (anomaly và notification vẫn đạt) | `Start-Process -ArgumentList` ghép mảng bằng dấu cách mà không tự thêm dấu nháy, nên header `Authorization: Bearer ...` thành ba đối số của curl | Truyền header đã kèm dấu nháy: `'"Authorization: Bearer {0}"' -f $token` | Khi một tính năng đạt ở bước trước mà hỏng ở bước đọc kết quả, nghi ngờ công cụ đo trước khi nghi ngờ hệ thống |
| 2026-11-12 | CI đỏ ở PR đầu tiên: `OpenApiContractTest` báo `jars-api.yaml is out of date`, trong khi chạy trên máy dev thì xanh | springdoc liệt kê property của class được reflect (`java.util.Currency` trong schema `Money`) theo thứ tự JVM trả về method, thứ tự này khác giữa Windows và Linux, nên cùng code sinh ra file khác nhau | Tái hiện bằng cách chạy `./mvnw verify` trong container `eclipse-temurin:21-jdk` (clone sạch, `TZ=UTC`, 2 CPU như runner); bật `springdoc.writer-with-order-by-keys=true` cho test để key luôn theo bảng chữ cái; sinh lại file ở Linux và kiểm Windows chấp nhận cùng file | Test so sánh file sinh ra với file đã commit phải xác định, không phụ thuộc hệ điều hành. Kiểm tra trước khi push bằng một lần chạy trong container Linux |
| 2026-11-12 | GitGuardian báo `Generic Password` ở `scripts/smoke-test.ps1` | Dòng `$password = "..." + <chuỗi ngẫu nhiên>`: chuỗi cố định đủ để máy quét coi là mật khẩu, dù mật khẩu thật sinh ngẫu nhiên lúc chạy và user Cognito bị xóa ngay sau test | Sinh mật khẩu hoàn toàn từ ký tự ngẫu nhiên, không còn literal trong mã nguồn. Commit cũ vẫn nằm trong lịch sử PR nên incident cần được đánh dấu false positive trong dashboard GitGuardian (hoặc viết lại lịch sử) | Secret scanner quét **từng commit** của PR: sửa ở commit sau không xóa được phát hiện ở commit trước |
| 2026-11-13 | Task đầu tiên sau khi deploy chết vì Flyway không kết nối được Aurora, ECS phải chạy task thứ hai | Aurora đang pause ở 0 ACU, cần khoảng 15 giây để resume; Flyway mặc định không thử lại | `spring.flyway.connect-retries: 6`, `connect-retries-interval: 10s` trong `application-aws.yaml` | Scale-to-zero là một trạng thái mà app phải chịu được ngay từ connection đầu tiên |
| 2026-11-13 | Smoke test chat: request tiếp theo trên cùng connection keep-alive nhận một body `401` thừa | Khi `SseEmitter` hoàn tất, Spring chạy một async dispatch. Bearer token filter không chạy trên dispatch đó, nhưng `anyRequest().authenticated()` vẫn áp dụng, nên một body `401` bị ghi nối vào cuối stream đã xong | `dispatcherTypeMatchers(ASYNC, ERROR).permitAll()` trong `SecurityConfig` (request gốc đã được xác thực); `ContinuedDispatchTest` | Dùng `MockMvc` thì không thấy lỗi này, chỉ thấy khi đi qua connection HTTP thật. SSE cần một bước smoke test qua đúng CloudFront và ALB |
| 2026-11-13 | Guardrail chặn câu "Số dư hũ NEC của tôi là bao nhiêu?" như một yêu cầu tư vấn đầu tư | Denied topic về mua bán chứng khoán coi mã hũ (`NEC`, `FFA`, ...) là mã cổ phiếu | `BedrockGuardrail` thay mã hũ bằng tên hũ trước khi gửi `ApplyGuardrail`; model và người dùng vẫn thấy văn bản gốc | Phải thử guardrail bằng câu hỏi hợp lệ của chính domain chứ không chỉ bằng câu tấn công |
| 2026-11-13 | Guardrail chặn prompt attack bằng tiếng Anh nhưng **không** chặn câu cùng nghĩa bằng tiếng Việt | Tier mặc định (Classic) của content filter và prompt attack chủ yếu hỗ trợ tiếng Anh | **Chưa xử lý**, smoke test ghi nhận kết quả thay vì fail. System prompt và tool chỉ đọc vẫn giới hạn được thiệt hại | Muốn chặn tiếng Việt thì cân nhắc Standard tier (cần cross-region inference cho guardrail, giá cao hơn) |
| 2026-11-13 | `deploy-dev.yml` lần đầu: `configure-aws-credentials` báo `Not authorized to perform sts:AssumeRoleWithWebIdentity` | Repo dùng định dạng subject OIDC bất biến của GitHub (`repo:owner@<id>/repo@<id>:ref:...`), còn trust policy so với định dạng cũ chỉ có tên | Đọc định dạng thật bằng `gh api repos/<owner>/<repo>/actions/oidc/customization/sub`; đổi `FoundationStack.GITHUB_SUBJECT` và deploy `JarsFoundation` | Điều kiện `sub` phải lấy từ cấu hình thật của repo, không chép theo tài liệu mẫu. Định dạng bất biến còn chặn được repo tạo lại cùng tên |
