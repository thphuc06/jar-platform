"""VPC layout, subnet tiers and security-group rules (dev environment).

Derived from infra/cdk NetworkStack (VPC, subnets, security groups), AppStack (ALB in the lb subnets, ECS task in the
public subnets with a public IP) and DataStack (Aurora in the data subnets). There is no NAT gateway (ADR-0013).

Render: see docs/diagrams/README.md
"""
from pathlib import Path

from diagrams import Cluster, Diagram, Edge
from diagrams.aws.compute import Fargate
from diagrams.aws.database import Aurora
from diagrams.aws.general import InternetAlt1
from diagrams.aws.network import ALB, CloudFront, InternetGateway

OUT = Path(__file__).resolve().parent.parent / "aws-network"

graph_attr = {"fontsize": "20", "pad": "0.5", "nodesep": "0.6", "ranksep": "1.2", "splines": "spline"}

with Diagram("Jars Platform - VPC & security groups (dev)", filename=str(OUT), show=False,
             direction="LR", outformat="png", graph_attr=graph_attr):
    cdn = CloudFront("CloudFront\n(VPC origin to the ALB)")
    aws_public = InternetAlt1("AWS public endpoints\nBedrock, SQS, S3, ECR,\nCloudWatch Logs, X-Ray,\n"
                              "Secrets Manager,\nCognito JWKS")

    with Cluster("VPC 10.20.0.0/16  (2 AZ: ap-southeast-1a / 1b, no NAT gateway)"):
        igw = InternetGateway("Internet Gateway")

        with Cluster("lb subnets (isolated, no route out)\n10.20.10.0/24 (a), 10.20.11.0/24 (b)"):
            alb = ALB("internal ALB\nsg-alb: 80 from the CloudFront\norigin-facing prefix list")
        with Cluster("public subnets  10.20.0.0/24 (a), 10.20.1.0/24 (b)"):
            task = Fargate("jars-api task (desired count 1)\npublic IPv4\n"
                           "sg-app: 8080 from sg-alb only\negress 443 anywhere, 5432 to sg-db")
        with Cluster("data subnets (isolated, no route out)\n10.20.20.0/24 (a), 10.20.21.0/24 (b)"):
            db = Aurora("Aurora PostgreSQL writer\nsg-db: 5432 from sg-app, no egress")

    cdn >> Edge(label="HTTP :80 (AWS network)") >> alb
    alb >> Edge(label=":8080") >> task
    task >> Edge(label=":5432 TLS") >> db
    task >> Edge(label="egress 443") >> igw >> aws_public
