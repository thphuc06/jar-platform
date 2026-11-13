"""Deployed topology of Jars Platform (dev, ap-southeast-1): the overview, one box per thing the CDK stacks build.

Derived from infra/cdk (AppStack, DataStack, AuthStack, MessagingStack, ApiObservability, AdvisorResources).
The detailed view is aws_architecture.py.

Render: see docs/diagrams/README.md
"""
from pathlib import Path

from _ortho import Canvas
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

d = Canvas("Jars Platform - deployed topology (dev, ap-southeast-1)", OUT, cw=215, rh=190)

# Grid: columns 0-6, rows 0-3. Left band = outside the region (browser, edge); the region box holds the rest.
# Row 1 is the request path: Browser > CloudFront > ALB > jars-api > Aurora.
d.node("users", Users, "Browser", 0, 1)
d.node("cdn", CloudFront, "CloudFront", 1, 1)
d.node("web", S3, "web bucket", 1, 2)

d.node("idp", Cognito, "Cognito", 2, 0)
d.node("alb", ALB, "internal ALB", 3, 1)
d.node("api", Fargate, "jars-api, one task\n0.5 vCPU / 1 GB, ARM64", 4, 1, label_top=True)
d.node("db", Aurora, "Aurora Serverless v2\nmin 0 ACU (auto-pause)", 5, 1)
d.node("adot", Docker, "ADOT collector\nsidecar in the same task", 5, 0)

d.node("statements", S3, "statements bucket", 2, 3)
d.node("rule", Eventbridge, "EventBridge rule\nstatement-uploaded", 3, 3)
d.node("imports", SQS, "SQS jars-imports", 4, 3)
d.node("sched", EventbridgeScheduler, "EventBridge Scheduler\nmonth-close, nightly", 3, 2)
d.node("jobs", SQS, "SQS job queues\njars-jobs,\njars-reconciliation", 4, 2)

d.node("xray", XRay, "X-Ray", 6.2, 0)
d.node("cw", Cloudwatch, "CloudWatch", 6.2, 1)
d.node("llm", Bedrock, "Bedrock\nClaude Haiku 4.5\n+ Guardrail", 6.2, 2)

d.group("edge", "Edge", ["cdn", "web"], "plain")
d.group("vpc", "VPC", ["alb", "api", "db", "adot"], "vpc")
d.group("queues", "SQS queues", ["jobs", "imports"], "plain", pad=(14, 12, 14, 10))
d.group("services", "Regional services", ["xray", "cw", "llm"], "plain")
d.group("region", "ap-southeast-1", ["idp", "vpc", "statements", "rule", "sched", "queues", "services"], "region",
        pad=(18, 14, 18, 16))

# request path
d.edge("users", "cdn", "rl")
d.edge("cdn", "web", "bt")
d.edge("cdn", "alb", "rl", label="/api/*")
d.edge("alb", "api", "rl")
d.edge("api", "db", "rl", label="JDBC", lt=0.66)

# browser talks to AWS directly (dashed)
d.edge("users", "idp", "tl", style="dashed", label="sign in")
d.edge("users", "statements", "bl", style="dashed", label="presigned PUT")

# statement import and scheduled jobs; both queues feed jars-api, so one arrow from the queue box
d.edge("statements", "rule", "rl")
d.edge("rule", "imports", "rl")
d.edge("sched", "jobs", "rl")
d.edge("queues", "api", "tb")

# AI advisor and telemetry
d.edge("api", "llm", "rl", off=(20, 0), lanes=[4.5])
d.edge("api", "adot", "rl", off=(-20, 0), lanes=[4.5], label="OTLP", lseg=2)
d.edge("adot", "xray", "rl")
d.edge("adot", "cw", "rl", off=(20, 0), lanes=[5.66])

d.render()
