# infra/cdk

AWS CDK v2 viết bằng **Java** ([ADR-0012](../../docs/04-adr/0012-iac-aws-cdk-java.md)). Toàn bộ hạ tầng của Jars Platform nằm ở đây.

> Trạng thái: **chưa có code**. Stack đầu tiên (`JarsAuth`) được tạo ở bước P1.1.

## Cấu trúc dự kiến

```
infra/cdk/
├── cdk.json                 # "app": "./mvnw -q compile exec:java", context: env, account, region, dbMode...
├── pom.xml                  # aws-cdk-lib, constructs, cdk-nag, junit
└── src/
    ├── main/java/io/github/thphuc06/jars/infra/
    │   ├── JarsInfraApp.java
    │   ├── FoundationStack.java   NetworkStack.java   DataStack.java     AuthStack.java
    │   ├── MessagingStack.java    QuantStack.java     AppStack.java      ObservabilityStack.java
    │   └── config/EnvConfig.java  # đọc context -> record
    └── test/java/...              # Template assertions + cdk-nag
```

## Lệnh

```bash
cd infra/cdk
npx aws-cdk@2 synth                                  # sinh CloudFormation
npx aws-cdk@2 diff  JarsApp -c imageTag=<sha>        # xem thay đổi trước khi deploy
npx aws-cdk@2 deploy JarsAuth --profile jars-dev
npx aws-cdk@2 destroy JarsApp --profile jars-dev     # chế độ TEARDOWN
```

Danh sách stack, vòng đời, thứ tự deploy: [aws-infrastructure.md §1](../../docs/02-architecture/aws-infrastructure.md#1-cdk-stacks) · [deployment.md](../../docs/05-operations/deployment.md).

**Test bắt buộc** (chống "cháy" tiền và lỗi bảo mật):
- Không có `AWS::EC2::NatGateway`.
- ECS task definition dùng `ARM64`.
- Không security group nào mở inbound `0.0.0.0/0`.
- Log group có retention ≤ 14 ngày.
- Aurora `MinCapacity = 0`.
