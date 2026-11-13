"""Deployed topology of Jars Platform (dev, ap-southeast-1): the overview, one box per thing the CDK stacks build.

Derived from infra/cdk (AppStack, DataStack, AuthStack, MessagingStack, ApiObservability, AdvisorResources).
The detailed view is aws_architecture.py.

Render: see docs/diagrams/README.md
"""
from pathlib import Path

from diagrams import Cluster, Diagram, Edge
from diagrams.aws.compute import Fargate
from diagrams.aws.database import Aurora
from diagrams.aws.devtools import XRay
from diagrams.aws.integration import SQS, Eventbridge, EventbridgeScheduler
from diagrams.aws.management import Cloudwatch
from diagrams.aws.ml import Bedrock
from diagrams.aws.network import ALB, CloudFront
from diagrams.aws.security import Cognito
from diagrams.aws.storage import S3
from diagrams.onprem.client import Users
from diagrams.onprem.container import Docker

OUT = Path(__file__).resolve().parent.parent / "aws-deployment"

graph_attr = {"fontsize": "20", "pad": "0.5", "nodesep": "0.6", "ranksep": "1.2", "splines": "spline"}

with Diagram("Jars Platform - deployed topology (dev, ap-southeast-1)", filename=str(OUT), show=False,
             direction="LR", outformat="png", graph_attr=graph_attr):
    users = Users("Browser")
    with Cluster("Edge"):
        cdn = CloudFront("CloudFront")
        web = S3("web bucket")
    with Cluster("ap-southeast-1"):
        idp = Cognito("Cognito")
        with Cluster("VPC"):
            alb = ALB("internal ALB")
            api = Fargate("jars-api, one task\n0.5 vCPU / 1 GB, ARM64", labelloc="t")
            adot = Docker("ADOT collector\nsidecar in the same task")
            db = Aurora("Aurora Serverless v2\nmin 0 ACU (auto-pause)")
        statements = S3("statements bucket")
        rule = Eventbridge("EventBridge rule\nstatement-uploaded")
        imports = SQS("SQS jars-imports")
        sched = EventbridgeScheduler("EventBridge Scheduler\nmonth-close, nightly")
        jobs = SQS("SQS job queues\njars-jobs, jars-reconciliation")
        llm = Bedrock("Bedrock\nClaude Haiku 4.5 + Guardrail")
        xray = XRay("X-Ray")
        cw = Cloudwatch("CloudWatch")

    users >> cdn >> web
    cdn >> Edge(label="/api/*") >> alb >> api
    api >> Edge(label="JDBC") >> db
    users >> Edge(style="dashed", label="sign in") >> idp
    users >> Edge(style="dashed", label="presigned PUT") >> statements
    statements >> rule >> imports >> api
    sched >> jobs >> api
    api >> llm
    api >> Edge(label="OTLP") >> adot
    adot >> xray
    adot >> cw
