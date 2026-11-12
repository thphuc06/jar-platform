<#
.SYNOPSIS
  Builds the jars-api image for ECS (linux/arm64, Graviton) and pushes it to ECR, tagged with the current git commit.

.DESCRIPTION
  The ECR repository is immutable (a tag can never be overwritten), so the tag is the commit sha and the working tree
  must be clean: an image must always be reproducible from a commit.
  Prints the tag at the end; pass it to CDK:  npx aws-cdk@2 deploy JarsApp -c imageTag=<tag>

.EXAMPLE
  pwsh scripts/push-image.ps1
#>
param(
    [string]$Region = "ap-southeast-1",
    [string]$Repository = "jars/jars-api",
    [switch]$AllowDirty
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if (-not $AllowDirty -and (git -C $root status --porcelain)) {
    throw "Working tree has uncommitted changes. Commit first (the image tag is the commit sha), or pass -AllowDirty for a throw-away build."
}
$tag = (git -C $root rev-parse --short=12 HEAD).Trim()
$account = (aws sts get-caller-identity --query Account --output text).Trim()
$registry = "$account.dkr.ecr.$Region.amazonaws.com"
$image = "$registry/${Repository}:$tag"

Write-Host "Logging in to $registry"
aws ecr get-login-password --region $Region | docker login --username AWS --password-stdin $registry
if ($LASTEXITCODE -ne 0) { throw "ECR login failed" }

$existing = aws ecr describe-images --repository-name $Repository --image-ids imageTag=$tag --region $Region --query "imageDetails[0].imageTags" --output text 2>$null
if ($LASTEXITCODE -eq 0 -and $existing) {
    Write-Host "Image $image already exists (immutable tag): nothing to push."
} else {
    Write-Host "Building and pushing $image"
    $env:DOCKER_BUILDKIT = "1"
    docker buildx build --platform linux/arm64 --provenance=false -t $image --push (Join-Path $root "apps/jars-api")
    if ($LASTEXITCODE -ne 0) { throw "docker build/push failed" }
}
Write-Host ""
Write-Host "imageTag=$tag"
$tag
