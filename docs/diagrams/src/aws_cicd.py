"""CI/CD of Jars Platform as .github/workflows defines it.

ci.yml, web.yml and infra.yml run on pull requests and pushes to main and never touch AWS.
deploy-dev.yml is workflow_dispatch only (run from main): OIDC role, ARM64 image to ECR, cdk deploy JarsApp, smoke test.

Render: see docs/diagrams/README.md
"""
from pathlib import Path

from diagrams import Cluster, Diagram, Edge
from diagrams.aws.compute import ECR, ECS
from diagrams.aws.devtools import CloudDevelopmentKit
from diagrams.aws.management import Cloudformation
from diagrams.aws.network import CloudFront
from diagrams.aws.security import IAMRole
from diagrams.aws.storage import S3
from diagrams.onprem.ci import GithubActions
from diagrams.onprem.vcs import Github

OUT = Path(__file__).resolve().parent.parent / "aws-cicd"

graph_attr = {"fontsize": "20", "pad": "0.5", "nodesep": "0.6", "ranksep": "1.3", "splines": "spline"}

with Diagram("Jars Platform - CI/CD", filename=str(OUT), show=False, direction="LR",
             outformat="png", graph_attr=graph_attr):
    repo = Github("GitHub\njar-platform")

    with Cluster("Pull request + push to main (no AWS access)"):
        ci = GithubActions("ci.yml\njars-api verify (tests,\nTestcontainers, modulith,\nspotless) + gitleaks")
        web_ci = GithubActions("web.yml (apps/web paths)\nlint, test, build,\nAPI types up to date")
        infra_ci = GithubActions("infra.yml (infra/cdk paths)\ncdk tests + cdk-nag,\ncdk synth")

    with Cluster("Manual: workflow_dispatch, run from main"):
        cd = GithubActions("deploy-dev.yml\naction: deploy | destroy\n(ARM64 runner)")
        smoke = GithubActions("smoke test\nscripts/smoke-test.ps1")

    with Cluster("AWS account (ap-southeast-1)"):
        role = IAMRole("GitHub OIDC role\nno long-lived keys")
        ecr = ECR("ECR jars/jars-api\nARM64 image\ntag = git SHA (12 chars)")
        cdk = CloudDevelopmentKit("cdk deploy JarsApp\n--exclusively\n-c imageTag=<sha>")
        cfn = Cloudformation("CloudFormation\nJarsApp stack")
        ecs = ECS("ECS service jars-api\nrolling + circuit breaker")
        web = S3("web bucket\n(SPA build)")
        cdn = CloudFront("CloudFront\ninvalidation")

    repo >> Edge(label="pull request / push") >> ci
    repo >> web_ci
    repo >> infra_ci
    repo >> Edge(label="manual run") >> cd
    cd >> Edge(label="AssumeRoleWithWebIdentity") >> role
    role >> Edge(label="docker buildx --push") >> ecr
    role >> cdk >> cfn
    cfn >> ecs
    cfn >> Edge(label="BucketDeployment") >> web
    web >> cdn
    ecr >> Edge(style="dashed", label="pull") >> ecs
    cd >> Edge(style="dashed") >> smoke
    smoke >> Edge(style="dashed", label="HTTPS") >> cdn
