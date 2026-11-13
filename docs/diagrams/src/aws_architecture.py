"""AWS architecture of Jars Platform as the CDK code builds it today (dev, ap-southeast-1).

Derived from infra/cdk: FoundationStack (ECR, alarm topic, budget), NetworkStack (subnet tiers), AuthStack (Cognito),
DataStack (Aurora, statements bucket), MessagingStack (queues, schedules, rule), AppStack (CloudFront, ALB, Fargate
service), ApiObservability (ADOT sidecar, EMF, dashboard, alarms) and AdvisorResources (Bedrock guardrail).
Nothing planned is drawn: no cache, no worker service, no SNS/SQS FIFO chain.

Render: see docs/diagrams/README.md
"""
from pathlib import Path

from _ortho import Canvas
from diagrams.aws.compute import ECR, Fargate
from diagrams.aws.cost import Budgets
from diagrams.aws.database import Aurora
from diagrams.aws.devtools import XRay
from diagrams.aws.integration import SNS, SQS, EventbridgeRule, EventbridgeScheduler
from diagrams.aws.management import Cloudwatch, CloudwatchAlarm, CloudwatchLogs
from diagrams.aws.ml import Bedrock
from diagrams.aws.network import ALB, CloudFront
from diagrams.aws.security import Cognito, SecretsManager
from diagrams.aws.storage import S3
from diagrams.onprem.client import Users
from diagrams.onprem.container import Docker

OUT = Path(__file__).resolve().parent.parent / "aws-architecture"

d = Canvas("Jars Platform - AWS architecture (dev, ap-southeast-1)", OUT, cw=230, rh=165)

R = 3.5      # main row: request path
M1, M2, M3 = 0.0, 0.78, 1.56   # the three messaging rows
OBS = 5.05   # observability row

# ---- outside the region ----------------------------------------------------------------------------------------
d.node("users", Users, "Browser\nReact PWA", 0, R)
d.node("cdn", CloudFront, "CloudFront\n+ SPA-rewrite\nfunction", 1, R)
d.node("web", S3, "web bucket\nprivate", 1, R + 1.2)

# ---- region: messaging band at the top -------------------------------------------------------------------------
d.node("statements", S3, "statements bucket\nuploads/ expire after 7 days", 2, M1)
d.node("rule", EventbridgeRule, "rule statement-uploaded\nS3 Object Created, uploads/", 3, M1)
d.node("sched_close", EventbridgeScheduler, "schedule month-close\n00:05 on the 1st", 3, M2)
d.node("sched_recon", EventbridgeScheduler, "schedule ledger-reconciliation\n02:30 nightly", 3, M3)
d.node("dlq_sched", SQS, "jars-jobs-scheduler-dlq", 2, (M2 + M3) / 2)
d.node("q_imports", SQS, "jars-imports", 4, M1)
d.node("q_jobs", SQS, "jars-jobs", 4, M2)
d.node("q_recon", SQS, "jars-reconciliation", 4, M3)
d.node("dlq_imports", SQS, "jars-imports-dlq", 5, M1)
d.node("dlq_jobs", SQS, "jars-jobs-dlq", 5, M2)
d.node("dlq_recon", SQS, "jars-reconciliation-dlq", 5, M3)
d.node("ecr", ECR, "ECR jars/jars-api", 6.4, 0.2)
d.node("secret", SecretsManager, "Secrets Manager\nDB credentials", 6.4, 1.1)

d.node("idp", Cognito, "Cognito user pool\nmanaged login, PKCE", 2, 2.35)

# ---- region: VPC ----------------------------------------------------------------------------------------------
d.node("alb", ALB, "internal ALB\nsg-alb", 2.85, R)
d.node("api", Fargate, "jars-api\nFargate x1, 0.5 vCPU\n/ 1 GB ARM64\nsg-app", 4, R)
d.node("adot", Docker, "ADOT collector\nsidecar\n(same task)", 4, OBS)
d.node("db", Aurora, "Aurora PostgreSQL\nServerless v2, 0-2 ACU\nauto-pause 5 min\nsg-db", 5, R)

# ---- region: regional services ---------------------------------------------------------------------------------
d.node("llm", Bedrock, "Bedrock\nClaude Haiku 4.5", 7.55, 2.15)
d.node("guard", Bedrock, "Bedrock Guardrail", 7.55, 2.95)
d.node("logs", CloudwatchLogs, "CloudWatch Logs\napp log + EMF log group", 7.55, 4.4)
d.node("xray", XRay, "X-Ray", 5.95, 6.3)
d.node("metrics", Cloudwatch, "CloudWatch metrics\nJars/Api (EMF)\n+ dashboard", 5.95, OBS)
d.node("alarms", CloudwatchAlarm, "8 alarms\n5xx, p95, 4 DLQs,\nledger, Aurora\nnot paused", 7.1, OBS)
d.node("topic", SNS, "SNS alarm topic", 8.3, OBS)
d.node("budget", Budgets, "AWS Budgets", 8.3, 3.55)

d.group("edge", "Global edge", ["cdn", "web"], "plain", pad=(40, 16, 16, 14))
d.group("queues", "SQS queues", ["q_imports", "q_jobs", "q_recon"], "plain", pad=(14, 8, 14, 8))
d.group("start", "Loaded at task start", ["ecr", "secret"], "plain")
d.group("bedrock", "Bedrock", ["llm", "guard"], "plain")
d.group("lb", "lb subnets (isolated)\n10.20.10.0/24\n10.20.11.0/24", ["alb"], "private")
d.group("pub", "public subnets,\npublic IPv4\n10.20.0.0/24\n10.20.1.0/24", ["api", "adot"], "public",
        label_bottom=True)
d.group("data", "data subnets (isolated)\n10.20.20.0/24\n10.20.21.0/24", ["db"], "private")
d.group("vpc", "VPC 10.20.0.0/16\n(2 AZ, no NAT)", ["lb", "pub", "data"], "vpc")
d.group("region", "Region ap-southeast-1",
        ["statements", "rule", "sched_close", "sched_recon", "dlq_sched", "queues", "dlq_imports", "dlq_jobs",
         "dlq_recon", "start", "idp", "vpc", "bedrock", "logs", "xray", "metrics", "alarms", "topic", "budget"],
        "region", pad=(18, 14, 18, 16))

# request path
d.edge("users", "cdn", "rl", label="HTTPS")
d.edge("cdn", "web", "bt", label="/*  (OAC)", lside="l")
d.edge("cdn", "alb", "rl", label="/api/*  (VPC origin, :80)")
d.edge("alb", "api", "rl", label=":8080")
d.edge("api", "db", "rl", label="JDBC\n:5432", lt=0.76, lside="a")

# browser talks to AWS directly
d.edge("users", "idp", "tl", off=(12, 0), style="dashed", label="sign in")
d.edge("users", "statements", "tl", off=(-12, 0), style="dashed", label="presigned PUT")

# statement import and scheduled jobs
d.edge("statements", "rule", "rl", color="purple")
d.edge("rule", "q_imports", "rl", color="purple")
d.edge("sched_close", "q_jobs", "rl", color="purple")
d.edge("sched_recon", "q_recon", "rl", color="purple")
d.edge("queues", "api", "bt", off=("align", -14), color="purple")
for src, dlq in (("q_imports", "dlq_imports"), ("q_jobs", "dlq_jobs"), ("q_recon", "dlq_recon")):
    d.edge(src, dlq, "rl", style="dotted", color="red")
d.edge("sched_close", "dlq_sched", "lr", off=(0, -14), lanes=[2.5], style="dotted", color="red")
d.edge("sched_recon", "dlq_sched", "lr", off=(0, 14), lanes=[2.5], style="dotted", color="red")

# loaded at task start
d.edge("start", "api", "bt", off=(0, 14), lanes=[2.02], style="dashed", label="image pull, secrets injected at start", lside="b", lt=0.3)

# AI advisor and telemetry
d.edge("api", "bedrock", "rl", off=(-20, ("row", 2.3)), lanes=[4.5], label="Converse, ApplyGuardrail")
d.edge("api", "logs", "rl", off=(20, 0), lanes=[4.5], label="stdout")
d.edge("api", "adot", "bt", label="OTLP")
d.edge("adot", "metrics", "rl", label="EMF")
d.edge("adot", "xray", "rl", off=(20, 0), lanes=[4.8], label="traces")
d.edge("metrics", "alarms", "rl")
d.edge("alarms", "topic", "rl", label="publish")
d.edge("budget", "topic", "bt", label="publish")

d.render()
