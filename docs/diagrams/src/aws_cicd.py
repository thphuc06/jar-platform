"""CI/CD of Jars Platform as .github/workflows defines it.

ci.yml, web.yml and infra.yml run on pull requests and pushes to main and never touch AWS.
deploy-dev.yml is workflow_dispatch only (run from main): OIDC role, ARM64 image to ECR, cdk deploy JarsApp, smoke test.

Render: see docs/diagrams/README.md
"""
from pathlib import Path

from _ortho import Canvas
from diagrams.aws.compute import ECR, ECS
from diagrams.aws.devtools import CloudDevelopmentKit
from diagrams.aws.management import Cloudformation
from diagrams.aws.network import CloudFront
from diagrams.aws.security import IAMRole
from diagrams.aws.storage import S3
from diagrams.onprem.ci import GithubActions
from diagrams.onprem.vcs import Github

OUT = Path(__file__).resolve().parent.parent / "aws-cicd"

d = Canvas("Jars Platform - CI/CD", OUT, cw=250, rh=190)

# Row 0: the repository and the three workflows that never touch AWS.
# Rows 1.25-3.25: the manual deploy workflow (left) and what it does in the AWS account (right).
d.node("repo", Github, "GitHub\njar-platform", 0, 0)
d.node("ci", GithubActions, "ci.yml\njars-api verify (tests,\nTestcontainers, modulith,\nspotless) + gitleaks", 1, 0)
d.node("web_ci", GithubActions, "web.yml (apps/web paths)\nlint, test, build,\nAPI types up to date", 2, 0)
d.node("infra_ci", GithubActions, "infra.yml (infra/cdk paths)\ncdk tests + cdk-nag,\ncdk synth", 3, 0)

d.node("cd", GithubActions, "deploy-dev.yml\naction: deploy | destroy\n(ARM64 runner)", 0, 2.25)
d.node("smoke", GithubActions, "smoke test\nscripts/smoke-test.ps1", 0, 3.25)

d.node("role", IAMRole, "GitHub OIDC role\nAssumeRoleWithWebIdentity,\nno long-lived keys", 1, 2.25)
d.node("ecr", ECR, "ECR jars/jars-api\nARM64 image\ntag = git SHA (12 chars)", 2, 1.25)
d.node("cdk", CloudDevelopmentKit, "cdk deploy JarsApp\n--exclusively\n-c imageTag=<sha>", 2, 2.25)
d.node("cfn", Cloudformation, "CloudFormation\nJarsApp stack", 3, 2.25)
d.node("ecs", ECS, "ECS service jars-api\nrolling + circuit breaker", 4, 1.25)
d.node("web", S3, "web bucket\n(SPA build)", 4, 2.25)
d.node("cdn", CloudFront, "CloudFront\ninvalidation", 5, 2.25, label_top=True)

d.group("ci_box", "Pull request + push to main (no AWS access)", ["ci", "web_ci", "infra_ci"], "github")
d.group("manual", "Manual:\nworkflow_dispatch,\nrun from main", ["cd", "smoke"], "github", label_bottom=True)
d.group("aws", "AWS account (ap-southeast-1)", ["role", "ecr", "cdk", "cfn", "ecs", "web", "cdn"], "aws")

d.edge("repo", "ci_box", "rl", off=(0, "align"), label="pull request\n/ push")
d.edge("repo", "cd", "bt", label="manual run")
d.edge("cd", "role", "rl")
d.edge("role", "ecr", "tl", label="docker buildx --push")
d.edge("role", "cdk", "rl")
d.edge("cdk", "cfn", "rl")
d.edge("cfn", "web", "rl", label="BucketDeployment")
d.edge("web", "cdn", "rl")
d.edge("cfn", "ecs", "tl", off=(0, 14))
d.edge("ecr", "ecs", "rl", off=(-14, -14), style="dashed", label="pull")
d.edge("cd", "smoke", "bt", style="dashed")
d.edge("smoke", "cdn", "rb", style="dashed", label="HTTPS")

d.render()
