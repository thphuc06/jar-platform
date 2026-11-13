<#
.SYNOPSIS
  Load test of a deployed Jars Platform through the public CloudFront domain (k6 in Docker).

.DESCRIPTION
  Creates throw-away Cognito users (admin API, no e-mail is sent) and gets their access tokens with the "smoke" app
  client, runs scripts/load-test.js with the grafana/k6 image, then deletes the users. The k6 summary (with p95 per
  request kind) is printed and saved as JSON next to the console output.

  Needs PowerShell 7, the AWS CLI with credentials for the account, and Docker. Costs: a few thousand requests through
  CloudFront and the ALB (fractions of a cent) and some X-Ray traces (within the free 100,000 a month).

.EXAMPLE
  pwsh scripts/load-test.ps1
  pwsh scripts/load-test.ps1 -Users 10 -PeakVus 40
#>
param(
    [string]$Region = "ap-southeast-1",
    [int]$Users = 5,
    [int]$PeakVus = 20,
    [string]$K6Image = "grafana/k6:2.3.0",
    [string]$SummaryFile = (Join-Path ([IO.Path]::GetTempPath()) "jars-load-test-summary.json")
)
$ErrorActionPreference = "Stop"

function Output($stack, $key) {
    (aws cloudformation describe-stacks --stack-name $stack --region $Region `
        --query "Stacks[0].Outputs[?OutputKey=='$key'].OutputValue | [0]" --output text).Trim()
}

$domain = Output "JarsApp" "CloudFrontDomain"
$poolId = Output "JarsAuth" "UserPoolId"
$clientId = Output "JarsAuth" "SmokeClientId"
$suffix = [guid]::NewGuid().ToString("N").Substring(0, 8)
# random throw-away password with every character group the pool policy asks for (no literal for secret scanners)
$password = -join (@(
        ([char[]](65..90) | Get-Random -Count 4)
        ([char[]](97..122) | Get-Random -Count 4)
        ([char[]](48..57) | Get-Random -Count 4)
        ([char[]]'!#*' | Get-Random -Count 2)
    ) | ForEach-Object { $_ } | Get-Random -Count 14)
$names = 1..$Users | ForEach-Object { "load-$_-$suffix@example.com" }

Write-Host "Load test of https://$domain with $Users users, up to $PeakVus virtual users"
try {
    $tokens = foreach ($u in $names) {
        aws cognito-idp admin-create-user --user-pool-id $poolId --username $u --region $Region `
            --user-attributes "Name=email,Value=$u" "Name=email_verified,Value=true" --message-action SUPPRESS | Out-Null
        aws cognito-idp admin-set-user-password --user-pool-id $poolId --username $u --password $password --permanent --region $Region | Out-Null
        (aws cognito-idp admin-initiate-auth --user-pool-id $poolId --client-id $clientId --region $Region `
            --auth-flow ADMIN_USER_PASSWORD_AUTH --auth-parameters "USERNAME=$u,PASSWORD=$password" | ConvertFrom-Json).AuthenticationResult.AccessToken
    }
    $script = Join-Path $PSScriptRoot "load-test.js"
    $out = Split-Path -Parent $SummaryFile
    # the tokens travel in an environment variable of the container, never in a file or on the command line of k6
    $env:TOKENS = $tokens -join ","
    Get-Content $script -Raw | docker run --rm -i -e TOKENS -e "BASE_URL=https://$domain" -e "PEAK_VUS=$PeakVus" `
        -v "${out}:/out" $K6Image run --summary-export "/out/$(Split-Path -Leaf $SummaryFile)" -
    $k6Exit = $LASTEXITCODE
} finally {
    Remove-Item Env:TOKENS -ErrorAction SilentlyContinue
    foreach ($u in $names) {
        aws cognito-idp admin-delete-user --user-pool-id $poolId --username $u --region $Region 2>$null | Out-Null
    }
    Write-Host "Deleted the throw-away users."
}
Write-Host "Summary: $SummaryFile"
exit $k6Exit
