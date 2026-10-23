"""CI/CD pipeline: GitHub Actions -> OIDC -> ECR + CDK (CloudFormation) -> ECS / S3 / CloudFront.

Render: see docs/diagrams/README.md
"""
from pathlib import Path

from diagrams import Cluster, Diagram, Edge
from diagrams.aws.compute import ECR, ECS, Lambda
from diagrams.aws.devtools import CloudDevelopmentKit
from diagrams.aws.management import Cloudformation
from diagrams.aws.network import CloudFront
from diagrams.aws.security import IAMRole
from diagrams.aws.storage import S3
from diagrams.onprem.ci import GithubActions
from diagrams.onprem.vcs import Github

OUT = Path(__file__).resolve().parent.parent / "aws-cicd"

graph_attr = {"fontsize": "20", "pad": "0.5", "nodesep": "0.6", "ranksep": "1.0", "splines": "spline"}

with Diagram("Jars Platform - CI/CD", filename=str(OUT), show=False, direction="LR",
             outformat="png", graph_attr=graph_attr):
    repo = Github("GitHub\njar-platform")

    with Cluster("GitHub Actions"):
        ci = GithubActions("ci.yml (every PR)\nunit + Testcontainers\nweb build, cdk synth,\ngitleaks")
        cd = GithubActions("deploy-dev.yml\n(main / manual)")

    with Cluster("AWS account (ap-southeast-1)"):
        role = IAMRole("GitHub OIDC role\nno long-lived keys")
        ecr = ECR("ECR\nimage tag = git SHA")
        cdk = CloudDevelopmentKit("cdk deploy\n-c imageTag=<sha>")
        cfn = Cloudformation("CloudFormation\nJars* stacks")
        ecs = ECS("ECS service\nrolling + circuit breaker")
        fn = Lambda("quant-fn image")
        web = S3("web bucket")
        cdn = CloudFront("CloudFront\ninvalidation")

    repo >> Edge(label="pull request") >> ci
    repo >> Edge(label="merge to main") >> cd
    cd >> Edge(label="AssumeRoleWithWebIdentity") >> role
    role >> Edge(label="docker push") >> ecr
    role >> cdk >> cfn
    cfn >> ecs
    cfn >> fn
    cfn >> Edge(label="BucketDeployment") >> web
    web >> cdn
    ecr >> Edge(style="dashed", label="pull") >> ecs
