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

## Xử lý message trong DLQ

1. Xem số lượng: `aws sqs get-queue-attributes --queue-url <dlq-url> --attribute-names ApproximateNumberOfMessages`.
2. Đọc thử 1 message (không xóa): `aws sqs receive-message --queue-url <dlq-url> --visibility-timeout 0 --max-number-of-messages 1`.
3. Tìm nguyên nhân trong log (dùng `eventId` hoặc `traceparent`), sửa code rồi deploy.
4. Redrive về queue gốc: `aws sqs start-message-move-task --source-arn <dlq-arn>` (consumer idempotent nên chạy lại an toàn).
5. Ghi lại sự cố vào mục "Sự cố đã gặp" bên dưới.

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
| – | | | | |
