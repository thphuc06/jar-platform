# infra/cdk

AWS CDK v2 viết bằng **Java 21** ([ADR-0012](../../docs/04-adr/0012-iac-aws-cdk-java.md)). Toàn bộ hạ tầng của Jars Platform nằm ở đây.

> Trạng thái: 6 stack đã viết và có test (`JarsFoundation`, `JarsNetwork`, `JarsAuth`, `JarsData`, `JarsMessaging`, `JarsApp`). Đã deploy (`JarsApp` chỉ dựng khi cần kiểm chứng, xong thì destroy). `JarsObservability` đã gộp vào `JarsApp`; `JarsQuant` đã bỏ ([ADR-0024](../../docs/04-adr/0024-drop-market-and-quant-fn.md)). Trước lần deploy đầu đọc [deployment.md, mục "Dọn stack cũ"](../../docs/05-operations/deployment.md#dọn-stack-cũ-trước-lần-deploy-đầu).

## Cấu trúc

```
infra/cdk/
├── cdk.json                  # app = "node scripts/cdk-app.mjs"; context (env, account, region, dbMode...) + feature flags của CDK
├── pom.xml                   # aws-cdk-lib, constructs, cdk-nag, JUnit 6, AssertJ (version cố định)
├── mvnw, mvnw.cmd, .mvn/     # Maven wrapper (cùng bản với apps/jars-api)
├── scripts/cdk-app.mjs       # chạy ./mvnw hoặc mvnw.cmd tùy hệ điều hành (cdk.json không viết được lệnh chạy cả hai)
└── src/
    ├── main/java/io/github/thphuc06/jars/infra/
    │   ├── JarsInfraApp.java          # main(): dựng stack, tag, bật cdk-nag
    │   ├── FoundationStack.java       # ECR, SNS alarm, Budget, GitHub OIDC + deploy role
    │   ├── NetworkStack.java          # VPC, subnet, security group (sở hữu MỌI rule)
    │   ├── AuthStack.java             # Cognito user pool, domain, resource server, client smoke
    │   ├── DataStack.java             # Aurora Serverless v2 (hoặc RDS với dbMode=rds)
    │   ├── MessagingStack.java        # SQS jars-jobs, jars-imports (+ rule statement-uploaded), jars-reconciliation, mỗi queue một DLQ + alarm; Scheduler month-close và ledger-reconciliation
    │   ├── AppStack.java              # ECS, ALB, CloudFront + VPC origin, web bucket, client SPA, quyền của task role
    │   ├── AdvisorResources.java      # Bedrock Guardrail và policy InvokeModel/ApplyGuardrail cho task role
    │   ├── ApiObservability.java      # sidecar ADOT, log group EMF, dashboard, alarm 5xx/p95/đối soát
    │   └── config/EnvConfig.java      # đọc context thành record
    └── test/java/...                  # Template assertions, guardrail, cdk-nag
```

## Lệnh

```bash
cd infra/cdk
./mvnw test                                                  # test + cdk-nag (Windows: mvnw.cmd test)
npx aws-cdk@2 synth --quiet -c imageTag=<sha>                # sinh CloudFormation vào cdk.out/, KHÔNG gọi AWS
npx aws-cdk@2 diff   JarsApp -c imageTag=<sha> --profile jars-dev
npx aws-cdk@2 deploy JarsAuth --profile jars-dev             # Phase 1: chỉ cần Cognito
npx aws-cdk@2 destroy JarsApp --profile jars-dev             # chế độ TEARDOWN
```

JDK phải là 21 (Temurin) và Node phải có trong `PATH` (jsii và CDK CLI cần Node). `synth` không cần credential AWS: AZ ghi cứng (`<region>a`, `<region>b`) nên không có context lookup.

Danh sách stack, vòng đời, thứ tự deploy: [aws-infrastructure.md §1](../../docs/02-architecture/aws-infrastructure.md#1-cdk-stacks) · [deployment.md](../../docs/05-operations/deployment.md).

## Context

Đặt trong `cdk.json` hoặc truyền `-c key=value` (giá trị từ CLI luôn là chuỗi, `EnvConfig` nhận cả hai dạng). Bảng đầy đủ: [deployment.md §3](../../docs/05-operations/deployment.md#3-cấu-hình-theo-môi-trường-infracdkcdkjson--context).

| Key | Mặc định trong `cdk.json` | Ghi chú |
|---|---|---|
| `env`, `account`, `region` | `dev`, `626212071763`, `ap-southeast-1` | Tiền tố tên resource và nơi stack được gắn vào |
| `apiDesiredCount` | `1` | `0` = SLEEP |
| `imageTag` | trống | **Bắt buộc để có `JarsApp`**: trống thì stack này không được synth (synth in cảnh báo, các stack khác vẫn chạy) |
| `dbMode` | `aurora` | `rds` = RDS PostgreSQL 17 `db.t4g.micro` ([ADR-0008](../../docs/04-adr/0008-database-aurora-serverless-v2.md)) |
| `alertEmail` | trống | **Placeholder, không commit email thật.** Truyền `-c alertEmail=<địa chỉ của bạn>` hoặc đặt biến môi trường `JARS_ALERT_EMAIL`. Trống thì không có subscription và synth in cảnh báo |
| `deployWeb` | `false` | `true` = upload `apps/web/dist` (`-c webDistDir=...` để đổi thư mục) |

## CloudFormation outputs

Script smoke test và bước deploy đọc các output này (`aws cloudformation describe-stacks --stack-name <stack>`):

| Stack | Output |
|---|---|
| `JarsFoundation` | `EcrRepositoryUri`, `AlarmTopicArn`, `GitHubDeployRoleArn` |
| `JarsAuth` | `UserPoolId`, `SmokeClientId`, `IssuerUri`, `ManagedLoginBaseUrl` |
| `JarsData` | `DbClusterIdentifier`, `DbEndpoint`, `DbSecretArn`, `StatementsBucketName` |
| `JarsMessaging` | `JobsQueueUrl`, `JobsDeadLetterQueueUrl` |
| `JarsApp` | `CloudFrontDomain`, `SpaClientId`, `DistributionId`, `WebBucketName`, `EcsClusterName`, `EcsServiceName` |
| `JarsNetwork` | `VpcId` |

## Test

`./mvnw test` chạy 118 test trong khoảng 1,5 phút (mỗi biến thể của app được synth một lần rồi dùng chung). Hai dòng `ERROR` về một S3 bucket (AwsSolutions-S1, S10) trong log là của `NagWiringTest`: nó cố ý dựng một stack sai để chứng minh cdk-nag chặn được.

| Test | Bảo vệ điều gì |
|---|---|
| `GuardrailTest` | Không có NAT gateway; task definition ARM64; không security group nào mở inbound `0.0.0.0/0` hay `::/0`; log retention ≤ 14 ngày; Aurora `MinCapacity=0`; ECR tag immutable; trust của OIDC role không có wildcard; S3 chặn public access; ALB internal; DB mã hóa và không public; client Cognito đều public; tag `Project` có trên mọi resource tính phí. Chạy cho cả Aurora và RDS |
| `NagTest` | cdk-nag (gói **AwsSolutions**) trên toàn app, ba biến thể: mặc định, `dbMode=rds`, `deployWeb=true` |
| `NagWiringTest` | Chứng minh cdk-nag thật sự làm synth thất bại khi vi phạm, và acknowledgement thì không |
| `*StackTest` | Cấu hình từng stack theo đúng tài liệu (CIDR, lifecycle ECS, scale-to-zero, cron...) |
| `EnvConfigTest` | Đọc context dạng chuỗi và dạng JSON |

### cdk-nag v3

`cdk-nag` 3.x chạy như **validation plugin** của CDK, không còn là Aspect. Mọi finding (cả mức warning) làm `app.synth()` thất bại, kể cả khi chỉ deploy một stack. Một finding chỉ được bỏ qua bằng `Validations.of(construct).acknowledge(...)` đặt **ngay cạnh construct**, kèm lý do gắn với một deviation đã ghi trong [well-architected-review.md §9](../../docs/02-architecture/well-architected-review.md#9-tổng-hợp-các-điểm-cố-ý-lệch-chuẩn). Không có suppression theo quy tắc chung: mỗi finding IAM5 phải được liệt kê riêng, ví dụ `AwsSolutions-IAM5[Resource::*]`. Khi test thất bại, thông báo liệt kê từng finding và đường dẫn construct.

## Quy ước trong code

- **Security group:** `NetworkStack` sở hữu mọi rule. Stack khác nhận group qua `NetworkStack.immutable(...)`, nên `connections.allowFrom(...)` không thể âm thầm thêm rule ở stack khác. `AppStackTest.thisStackAddsNoSecurityGroupRules` giữ quy ước này.
- **SPA fallback** là CloudFront Function, không phải custom error response ([ADR-0020](../../docs/04-adr/0020-spa-fallback-cloudfront-function.md)).
- **Tên cố định** (`jars-jobs`, `jars-dev-db`, cluster `jars-dev`, service `jars-api`) khớp runbook và biến môi trường của app; đổi tên là thay thế resource.
- Tag `Project=jars`, `Env=dev`, `ManagedBy=cdk`, `Owner=thphuc06` gắn ở cấp app; `propagateTags=SERVICE` đưa tag xuống task để budget theo tag nhìn thấy chi phí Fargate.
