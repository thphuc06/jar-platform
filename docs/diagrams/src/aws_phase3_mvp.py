"""Minimal first deployment (built in Phase 1 WP8-WP9; the file name keeps the old phase numbering): only what the monolith needs to serve the UI.

Render: see docs/diagrams/README.md
"""
from pathlib import Path

from diagrams import Cluster, Diagram, Edge
from diagrams.aws.compute import Fargate
from diagrams.aws.database import Aurora
from diagrams.aws.integration import SQS, Eventbridge
from diagrams.aws.network import ALB, CloudFront
from diagrams.aws.security import Cognito
from diagrams.aws.storage import S3
from diagrams.onprem.client import Users

OUT = Path(__file__).resolve().parent.parent / "aws-phase3-mvp"

graph_attr = {"fontsize": "20", "pad": "0.5", "nodesep": "0.6", "ranksep": "1.0", "splines": "spline"}

with Diagram("Jars Platform - Phase 3 MVP deployment", filename=str(OUT), show=False,
             direction="LR", outformat="png", graph_attr=graph_attr):
    users = Users("Browser")
    with Cluster("Edge"):
        cdn = CloudFront("CloudFront")
        web = S3("web bucket")
    with Cluster("ap-southeast-1"):
        idp = Cognito("Cognito")
        with Cluster("VPC"):
            alb = ALB("internal ALB")
            api = Fargate("jars-api x1\n0.5 vCPU / 1 GB ARM64")
            db = Aurora("Aurora Serverless v2\nmin 0 ACU (auto-pause)")
        statements = S3("statements bucket")
        bus = Eventbridge("EventBridge\n+ Scheduler")
        jobs = SQS("SQS jobs")

    users >> cdn >> web
    cdn >> Edge(label="/api/*") >> alb >> api >> db
    users >> Edge(style="dashed") >> idp
    users >> Edge(style="dashed", label="presigned PUT") >> statements >> bus >> jobs >> api
