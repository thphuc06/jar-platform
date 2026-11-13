"""VPC layout, subnet tiers and security-group rules (dev environment).

Derived from infra/cdk NetworkStack (VPC, subnets, security groups), AppStack (ALB in the lb subnets, ECS task in the
public subnets with a public IP) and DataStack (Aurora in the data subnets). There is no NAT gateway (ADR-0013).

Render: see docs/diagrams/README.md
"""
from pathlib import Path

from _ortho import Canvas
from diagrams.aws.compute import Fargate
from diagrams.aws.database import Aurora
from diagrams.aws.general import InternetAlt1
from diagrams.aws.network import ALB, CloudFront, InternetGateway

OUT = Path(__file__).resolve().parent.parent / "aws-network"

d = Canvas("Jars Platform - VPC & security groups (dev)", OUT, cw=300, rh=230)

# one tier per row (lb, public, data); the request path runs down the middle column
d.node("cdn", CloudFront, "CloudFront\n(VPC origin to the ALB)", 0, 0)
d.node("alb", ALB, "internal ALB\nsg-alb: 80 from the CloudFront\norigin-facing prefix list", 1.5, 0)
d.node("task", Fargate, "jars-api task (desired count 1)\npublic IPv4\nsg-app: 8080 from sg-alb only\n"
       "egress 443 anywhere, 5432 to sg-db", 1.5, 1)
d.node("db", Aurora, "Aurora PostgreSQL writer\nsg-db: 5432 from sg-app, no egress", 1.5, 2)
d.node("igw", InternetGateway, "Internet Gateway", 2.7, 1)
d.node("aws", InternetAlt1, "AWS public endpoints\nBedrock, SQS, S3, ECR,\nCloudWatch Logs, X-Ray,\n"
       "Secrets Manager,\nCognito JWKS", 3.9, 1)

d.group("lb", "lb subnets (isolated, no route out)\n10.20.10.0/24 (a), 10.20.11.0/24 (b)", ["alb"], "private",
        include=[(0.5, 0, 2.1, 0)])
d.group("public", "public subnets\n10.20.0.0/24 (a), 10.20.1.0/24 (b)", ["task"], "public",
        include=[(0.5, 1, 2.1, 1)])
d.group("data", "data subnets (isolated, no route out)\n10.20.20.0/24 (a), 10.20.21.0/24 (b)", ["db"], "private",
        include=[(0.5, 2, 2.1, 2)])
d.group("vpc", "VPC 10.20.0.0/16  (2 AZ: ap-southeast-1a / 1b, no NAT gateway)", ["lb", "public", "data", "igw"],
        "vpc", pad=(20, 14, 20, 16))

d.edge("cdn", "alb", "rl", label="HTTP :80 (AWS network)")
d.edge("alb", "task", "bt", label=":8080")
d.edge("task", "db", "bt", label=":5432 TLS")
d.edge("task", "igw", "rl", label="egress 443")
d.edge("igw", "aws", "rl")

d.render()
