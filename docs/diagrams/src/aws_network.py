"""VPC layout, subnets and security-group rules (dev environment).

Render: see docs/diagrams/README.md
"""
from pathlib import Path

from diagrams import Cluster, Diagram, Edge
from diagrams.aws.compute import Fargate
from diagrams.aws.database import Aurora, ElastiCache
from diagrams.aws.general import InternetAlt1
from diagrams.aws.network import ALB, CloudFront, InternetGateway

OUT = Path(__file__).resolve().parent.parent / "aws-network"

graph_attr = {"fontsize": "20", "pad": "0.5", "nodesep": "0.6", "ranksep": "1.0", "splines": "spline"}

with Diagram("Jars Platform - VPC & security groups (dev)", filename=str(OUT), show=False,
             direction="LR", outformat="png", graph_attr=graph_attr):
    cdn = CloudFront("CloudFront\nVPC origin ENI")
    aws_public = InternetAlt1("AWS public endpoints\nBedrock, SQS, SNS, ECR,\nLogs, Secrets, Cognito JWKS")

    with Cluster("VPC 10.20.0.0/16"):
        igw = InternetGateway("Internet Gateway")

        with Cluster("AZ ap-southeast-1a"):
            with Cluster("private 10.20.10.0/24"):
                alb_a = ALB("internal ALB\n(node a)\nsg-alb: 80 from CF prefix list")
            with Cluster("public 10.20.0.0/24"):
                task_a = Fargate("jars-api task\npublic IPv4\nsg-app: 8080 from sg-alb only")
            with Cluster("isolated 10.20.20.0/24"):
                db_a = Aurora("Aurora writer\nsg-db: 5432 from sg-app")

        with Cluster("AZ ap-southeast-1b"):
            with Cluster("private 10.20.11.0/24"):
                alb_b = ALB("internal ALB\n(node b)")
            with Cluster("public 10.20.1.0/24"):
                task_b = Fargate("jars-api task\n(when desired=2)")
            with Cluster("isolated 10.20.21.0/24"):
                cache = ElastiCache("Valkey endpoints (not built)\nsg-cache: 6379 from sg-app")

    cdn >> Edge(label="HTTP :80 (AWS network)") >> alb_a
    cdn >> Edge(style="dashed") >> alb_b
    alb_a >> Edge(label=":8080") >> task_a
    alb_b >> Edge(style="dashed") >> task_b
    task_a >> Edge(label=":5432 TLS") >> db_a
    task_a >> Edge(label=":6379 TLS") >> cache
    task_a >> Edge(label="egress 443") >> igw >> aws_public
    task_b >> Edge(style="dashed") >> igw
