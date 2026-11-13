"""AWS architecture of Jars Platform as the CDK code builds it today (dev, ap-southeast-1).

Derived from infra/cdk: FoundationStack (ECR, alarm topic, budget), NetworkStack (subnet tiers), AuthStack (Cognito),
DataStack (Aurora, statements bucket), MessagingStack (queues, schedules, rule), AppStack (CloudFront, ALB, Fargate
service), ApiObservability (ADOT sidecar, EMF, dashboard, alarms) and AdvisorResources (Bedrock guardrail).
Nothing planned is drawn: no cache, no worker service, no SNS/SQS FIFO chain.

Render: see docs/diagrams/README.md
"""
from pathlib import Path

from diagrams import Cluster, Diagram, Edge
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

graph_attr = {"fontsize": "22", "pad": "0.5", "nodesep": "0.8", "ranksep": "0.9", "splines": "spline"}

grey = Edge(color="gray40")

with Diagram("Jars Platform - AWS architecture (dev, ap-southeast-1)",
             filename=str(OUT), show=False, direction="TB", outformat="png", graph_attr=graph_attr):
    users = Users("Browser\nReact PWA")

    with Cluster("Global edge"):
        cdn = CloudFront("CloudFront\n+ SPA-rewrite function")
        web = S3("web bucket\nprivate")

    with Cluster("Region ap-southeast-1"):
        idp = Cognito("Cognito user pool\nmanaged login, PKCE")
        statements = S3("statements bucket\nuploads/ expire after 7 days")

        with Cluster("VPC 10.20.0.0/16  (2 AZ, no NAT)"):
            with Cluster("lb subnets (isolated)\n10.20.10.0/24, 10.20.11.0/24"):
                alb = ALB("internal ALB\nsg-alb")
            with Cluster("public subnets, public IPv4\n10.20.0.0/24, 10.20.1.0/24"):
                api = Fargate("jars-api\nFargate x1, 0.5 vCPU / 1 GB ARM64\nsg-app")
                adot = Docker("ADOT collector\nsidecar (same task)")
            with Cluster("data subnets (isolated)\n10.20.20.0/24, 10.20.21.0/24"):
                db = Aurora("Aurora PostgreSQL\nServerless v2, 0-2 ACU\nauto-pause 5 min, sg-db")

        with Cluster("Messaging"):
            rule = EventbridgeRule("rule statement-uploaded\nS3 Object Created, uploads/")
            q_imports = SQS("jars-imports")
            dlq_imports = SQS("jars-imports-dlq")
            sched_close = EventbridgeScheduler("schedule\nmonth-close\n00:05 on the 1st")
            sched_recon = EventbridgeScheduler("schedule\nledger-reconciliation\n02:30 nightly")
            q_jobs = SQS("jars-jobs")
            dlq_jobs = SQS("jars-jobs-dlq")
            q_recon = SQS("jars-reconciliation")
            dlq_recon = SQS("jars-reconciliation-dlq")
            dlq_sched = SQS("jars-jobs-scheduler-dlq")

        llm = Bedrock("Bedrock\nClaude Haiku 4.5")
        guard = Bedrock("Bedrock Guardrail")

        ecr = ECR("ECR jars/jars-api")
        secret = SecretsManager("Secrets Manager\nDB credentials")
        logs = CloudwatchLogs("CloudWatch Logs\napp log + EMF log group")
        metrics = Cloudwatch("CloudWatch metrics\nJars/Api (EMF) + dashboard")
        alarms = CloudwatchAlarm("8 alarms\n5xx, p95, 4 DLQs,\nledger, Aurora not paused")
        topic = SNS("SNS alarm topic")
        budget = Budgets("AWS Budgets")
        xray = XRay("X-Ray")

    # request path
    users >> Edge(label="HTTPS") >> cdn
    cdn >> Edge(label="/*  (OAC)") >> web
    cdn >> Edge(label="/api/*  (VPC origin, :80)") >> alb
    alb >> Edge(label=":8080") >> api
    api >> Edge(label="JDBC :5432") >> db
    users >> Edge(style="dashed", label="sign in") >> idp
    users >> Edge(style="dashed", label="presigned PUT") >> statements

    # statement import
    statements >> Edge(color="purple") >> rule
    rule >> Edge(color="purple") >> q_imports
    q_imports >> Edge(color="purple") >> api

    # scheduled jobs
    sched_close >> Edge(color="purple") >> q_jobs
    sched_recon >> Edge(color="purple") >> q_recon
    q_jobs >> Edge(color="purple") >> api
    q_recon >> Edge(color="purple") >> api
    for src, dlq in ((q_imports, dlq_imports), (q_jobs, dlq_jobs), (q_recon, dlq_recon)):
        src >> Edge(style="dotted", color="firebrick") >> dlq
    sched_close >> Edge(style="dotted", color="firebrick") >> dlq_sched
    sched_recon >> Edge(style="dotted", color="firebrick") >> dlq_sched

    # AI advisor
    api >> Edge(label="Converse") >> llm
    api >> Edge(label="ApplyGuardrail") >> guard

    # platform
    ecr >> Edge(style="dashed", label="image pull") >> api
    secret >> Edge(style="dashed", label="injected at start") >> api
    api >> Edge(label="stdout") >> logs
    api >> Edge(label="OTLP") >> adot
    adot >> Edge(label="traces") >> xray
    adot >> Edge(label="EMF") >> metrics
    metrics >> alarms >> Edge(label="publish") >> topic
    budget >> Edge(label="publish") >> topic
