"""Target AWS architecture of Jars Platform (end of Phase 5).

Render: see docs/diagrams/README.md
"""
from pathlib import Path

from diagrams import Cluster, Diagram, Edge
from diagrams.aws.compute import ECR, Fargate, Lambda
from diagrams.aws.database import Aurora, ElastiCache
from diagrams.aws.devtools import XRay
from diagrams.aws.integration import SNS, SQS, Eventbridge, EventbridgeScheduler
from diagrams.aws.management import Cloudwatch
from diagrams.aws.ml import Bedrock
from diagrams.aws.network import ALB, CloudFront
from diagrams.aws.security import Cognito, SecretsManager
from diagrams.aws.storage import S3
from diagrams.onprem.client import Users

OUT = Path(__file__).resolve().parent.parent / "aws-architecture"

graph_attr = {"fontsize": "22", "pad": "0.5", "nodesep": "0.7", "ranksep": "1.1", "splines": "spline"}

with Diagram("Jars Platform - AWS target architecture (ap-southeast-1, end of Phase 5)",
             filename=str(OUT), show=False, direction="LR", outformat="png", graph_attr=graph_attr):
    users = Users("Browser\nReact PWA")

    with Cluster("Global edge"):
        cdn = CloudFront("CloudFront\n/*  -> S3\n/api/*  -> VPC origin")
        web = S3("web bucket\nprivate + OAC")

    with Cluster("Region ap-southeast-1"):
        idp = Cognito("Cognito User Pool\nmanaged login + PKCE")
        statements = S3("statements bucket\nCSV uploads")

        with Cluster("VPC 10.20.0.0/16  (2 AZ, no NAT)"):
            with Cluster("private subnets"):
                alb = ALB("internal ALB")
            with Cluster("public subnets  (SG: inbound only from ALB)"):
                api = Fargate("jars-api\nJava 25 / Spring Boot 4.1\nmodular monolith")
                worker = Fargate("insights-worker\nJava (Phase 5)")
            with Cluster("isolated subnets"):
                db = Aurora("Aurora PostgreSQL\nServerless v2  0-2 ACU")
                cache = ElastiCache("ElastiCache Serverless\nValkey")

        with Cluster("Messaging"):
            topic = SNS("SNS FIFO topics\nledger / insights events")
            q_worker = SQS("SQS FIFO\ninsights-ledger (+DLQ)")
            q_api = SQS("SQS FIFO\napi-notifications (+DLQ)")
            bus = Eventbridge("EventBridge\ndefault bus")
            sched = EventbridgeScheduler("EventBridge Scheduler\nmonth-close cron")
            q_jobs = SQS("SQS\nimports / jobs (+DLQ)")

        with Cluster("AI & quant"):
            llm = Bedrock("Bedrock\nClaude Haiku + Guardrails")
            quant = Lambda("quant-fn  Python\nvnstock + PyPortfolioOpt")

        with Cluster("Platform (used by every service)"):
            ECR("ECR")
            SecretsManager("Secrets Manager")
            Cloudwatch("CloudWatch\nlogs / metrics / alarms")
            XRay("X-Ray (OTel traces)")

    # request path
    users >> Edge(label="HTTPS") >> cdn
    cdn >> web
    cdn >> Edge(label="VPC origin") >> alb
    alb >> Edge(label="REST + SSE") >> api
    users >> Edge(style="dashed", label="sign in") >> idp
    users >> Edge(style="dashed", label="presigned PUT") >> statements

    # data + dependencies of jars-api
    api >> Edge(label="JDBC") >> db
    api >> Edge(label="cache / rate limit") >> cache
    api >> Edge(label="ConverseStream") >> llm
    api >> Edge(label="lambda:Invoke") >> quant

    # async: outbox -> SNS FIFO -> SQS FIFO
    api >> Edge(color="darkgreen", label="outbox relay") >> topic
    topic >> Edge(color="darkgreen") >> q_worker
    q_worker >> Edge(color="darkgreen") >> worker
    worker >> Edge(color="darkgreen", label="AnomalyDetected") >> topic
    topic >> Edge(color="darkgreen") >> q_api
    q_api >> Edge(color="darkgreen") >> api
    worker >> Edge(label="own schema") >> db

    # platform events
    statements >> Edge(color="purple", label="Object Created") >> bus
    bus >> Edge(color="purple") >> q_jobs
    sched >> Edge(color="purple") >> q_jobs
    q_jobs >> Edge(color="purple") >> api
