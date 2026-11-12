<#
.SYNOPSIS
  End-to-end smoke test of a deployed Jars Platform, through the public CloudFront domain.

.DESCRIPTION
  Creates two throw-away Cognito users (admin API, no e-mail is sent), gets real access tokens for them with the
  "smoke" app client, then checks the things the project promises:
    - the SPA and the public health endpoint are served; the API rejects calls without a token (401)
    - first request creates the user with the six default jars
    - an Idempotency-Key makes a retried POST post exactly once
    - one user cannot see or change another user's data (404, not 403)
    - an unusual expense raises an anomaly, a notification, and a live Server-Sent Event through CloudFront
    - the ALB is internal (not reachable from the internet)
  Deletes the two users at the end. Needs the AWS CLI (credentials for the account) and curl.exe.

.EXAMPLE
  pwsh scripts/smoke-test.ps1
#>
param(
    [string]$Region = "ap-southeast-1",
    [string]$AppStack = "JarsApp",
    [string]$AuthStack = "JarsAuth",
    [int]$AsyncTimeoutSeconds = 60
)
$ErrorActionPreference = "Stop"
$results = [System.Collections.Generic.List[object]]::new()

function Output($stack, $key) {
    (aws cloudformation describe-stacks --stack-name $stack --region $Region `
        --query "Stacks[0].Outputs[?OutputKey=='$key'].OutputValue | [0]" --output text).Trim()
}
function Check($name, [scriptblock]$test) {
    try {
        $detail = & $test
        $results.Add([pscustomobject]@{ Check = $name; Result = "PASS"; Detail = "$detail" })
        Write-Host ("PASS  {0}  {1}" -f $name, $detail) -ForegroundColor Green
    } catch {
        $results.Add([pscustomobject]@{ Check = $name; Result = "FAIL"; Detail = $_.Exception.Message })
        Write-Host ("FAIL  {0}  {1}" -f $name, $_.Exception.Message) -ForegroundColor Red
    }
}
function Expect($condition, $message) { if (-not $condition) { throw $message } }

function Call($method, $path, $token, $body = $null, $key = $null) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    if ($key) { $headers["Idempotency-Key"] = $key }
    $params = @{ Uri = "https://$script:Domain$path"; Method = $method; Headers = $headers; SkipHttpErrorCheck = $true; UseBasicParsing = $true }
    if ($body) { $params.Body = ($body | ConvertTo-Json -Depth 6 -Compress); $params.ContentType = "application/json" }
    $r = Invoke-WebRequest @params
    $text = if ($r.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($r.Content) } else { [string]$r.Content }
    [pscustomobject]@{ Status = [int]$r.StatusCode; Text = $text; Headers = $r.Headers
        Json = $(if ($text -and $text.TrimStart().StartsWith("{") -or $text.TrimStart().StartsWith("[")) { $text | ConvertFrom-Json } else { $null }) }
}

$script:Domain = Output $AppStack "CloudFrontDomain"
$poolId = Output $AuthStack "UserPoolId"
$clientId = Output $AuthStack "SmokeClientId"
Write-Host "Testing https://$script:Domain (user pool $poolId)"

$suffix = [guid]::NewGuid().ToString("N").Substring(0, 8)
$password = "Sm0ke!" + [guid]::NewGuid().ToString("N").Substring(0, 12)
$users = @("smoke-a-$suffix@example.com", "smoke-b-$suffix@example.com")
$tokens = @{}
try {
    foreach ($u in $users) {
        aws cognito-idp admin-create-user --user-pool-id $poolId --username $u --region $Region `
            --user-attributes "Name=email,Value=$u" "Name=email_verified,Value=true" --message-action SUPPRESS | Out-Null
        # (the quotes matter: unquoted, PowerShell reads the comma as its array operator and splits each attribute in two)
        aws cognito-idp admin-set-user-password --user-pool-id $poolId --username $u --password $password --permanent --region $Region | Out-Null
        $auth = aws cognito-idp admin-initiate-auth --user-pool-id $poolId --client-id $clientId --region $Region `
            --auth-flow ADMIN_USER_PASSWORD_AUTH --auth-parameters "USERNAME=$u,PASSWORD=$password" | ConvertFrom-Json
        $tokens[$u] = $auth.AuthenticationResult.AccessToken
    }
    $alice = $tokens[$users[0]]; $bob = $tokens[$users[1]]

    Check "SPA is served from CloudFront" {
        $r = Call GET "/" $null
        Expect ($r.Status -eq 200 -and $r.Text -match "<html|<!doctype") "status $($r.Status)"
        "200 html"
    }
    Check "SPA deep link falls back to index.html" {
        $r = Call GET "/budget/jars" $null
        Expect ($r.Status -eq 200 -and $r.Text -match "<html|<!doctype") "status $($r.Status)"
        "200 html"
    }
    Check "public health endpoint" {
        $r = Call GET "/api/v1/health" $null
        Expect ($r.Status -eq 200 -and $r.Json.status -eq "UP") "status $($r.Status) $($r.Text)"
        "version $($r.Json.version)"
    }
    Check "API without a token is 401 problem+json" {
        $r = Call GET "/api/v1/me" $null
        Expect ($r.Status -eq 401 -and $r.Json.type -like "*unauthenticated") "status $($r.Status)"
        "401 traceId=$($r.Json.traceId)"
    }
    Check "a real Cognito access token is accepted, user is created lazily" {
        $r = Call GET "/api/v1/me" $alice
        Expect ($r.Status -eq 200 -and $r.Json.id) "status $($r.Status) $($r.Text)"
        "user $($r.Json.id)"
    }
    $jars = $null
    Check "new user has the six default jars" {
        $r = Call GET "/api/v1/jars" $alice
        Expect ($r.Status -eq 200 -and @($r.Json).Count -eq 6) "status $($r.Status) jars=$(@($r.Json).Count)"
        $script:jars = @($r.Json)
        ($script:jars.code -join ",")
    }
    $nec = ($script:jars | Where-Object code -eq "NEC").id
    $bobNec = $null
    Check "money posting is idempotent (same key twice = one posting)" {
        $key = [guid]::NewGuid().ToString()
        $body = @{ type = "EXPENSE"; amount = 120000; jarId = $nec }
        $first = Call POST "/api/v1/transactions" $alice $body $key
        $second = Call POST "/api/v1/transactions" $alice $body $key
        Expect ($first.Status -eq 201 -and $second.Status -eq 201) "statuses $($first.Status)/$($second.Status) $($first.Text)"
        Expect ($second.Headers["Idempotency-Replayed"] -contains "true") "second call was not a replay"
        $balance = (Call GET "/api/v1/jars" $alice).Json | Where-Object id -eq $nec
        Expect ($balance.balance.amount -eq "-120000.00") "balance is $($balance.balance.amount), expected -120000.00"
        "debited once"
    }
    Check "another user gets 404 (not 403) for my jar and cannot post to it" {
        $bobJars = (Call GET "/api/v1/jars" $bob).Json
        $script:bobNec = ($bobJars | Where-Object code -eq "NEC").id
        $patch = Call PATCH "/api/v1/jars/$nec" $bob @{ name = "hacked" }
        $post = Call POST "/api/v1/transactions" $bob @{ type = "EXPENSE"; amount = 1000; jarId = $nec } ([guid]::NewGuid().ToString())
        Expect ($patch.Status -eq 404 -and $post.Status -eq 404) "statuses $($patch.Status)/$($post.Status)"
        "404/404"
    }
    Check "an unusual expense raises an anomaly, a notification and a live SSE event through CloudFront" {
        $sseFile = Join-Path ([IO.Path]::GetTempPath()) "sse-$suffix.txt"
        $curl = Start-Process -FilePath "curl.exe" -PassThru -NoNewWindow -RedirectStandardOutput $sseFile `
            -ArgumentList @("-sN", "--max-time", [string]($AsyncTimeoutSeconds + 40), "-H", ('"Authorization: Bearer {0}"' -f $alice), "https://$script:Domain/api/v1/notifications/stream")
        # (Start-Process joins the list with spaces and does not quote for us: without the inner quotes curl sees three arguments)
        Start-Sleep -Seconds 3
        $steady = 95000, 105000, 98000, 102000, 100000, 97000, 103000, 99000, 101000, 100000, 96000, 104000
        foreach ($a in $steady) {
            $r = Call POST "/api/v1/transactions" $alice @{ type = "EXPENSE"; amount = $a; jarId = $nec } ([guid]::NewGuid().ToString())
            Expect ($r.Status -eq 201) "posting failed: $($r.Status) $($r.Text)"
        }
        # the detector runs asynchronously after commit: wait until all twelve (and the first 120000) are counted
        $deadline = (Get-Date).AddSeconds($AsyncTimeoutSeconds)
        do {
            Start-Sleep -Seconds 2
            $dash = (Call GET "/api/v1/dashboard" $alice).Json
            $count = ($dash.spendByJar | Where-Object jarId -eq $nec).txnCount
        } while ($count -lt 13 -and (Get-Date) -lt $deadline)
        Expect ($count -ge 13) "read model caught up only to $count of 13 postings"
        $spike = Call POST "/api/v1/transactions" $alice @{ type = "EXPENSE"; amount = 2000000; jarId = $nec } ([guid]::NewGuid().ToString())
        Expect ($spike.Status -eq 201) "spike posting failed: $($spike.Status)"
        do {
            Start-Sleep -Seconds 2
            $notes = (Call GET "/api/v1/notifications" $alice).Json
        } while (@($notes.items).Count -lt 1 -and (Get-Date) -lt $deadline)
        Expect (@($notes.items).Count -ge 1) "no notification within $AsyncTimeoutSeconds s"
        $anomalies = (Call GET "/api/v1/anomalies" $alice).Json
        Expect (@($anomalies.items).Count -ge 1 -and $anomalies.items[0].detector -eq "MAD") "no MAD anomaly"
        Start-Sleep -Seconds 3
        if (-not $curl.HasExited) { $curl.Kill() }
        $stream = Get-Content $sseFile -Raw
        Expect ($stream -match "event:\s*notification" -and $stream -match "data:") "SSE stream got no notification event: [$stream]"
        "anomaly $($anomalies.items[0].severity), notification '$($notes.items[0].title)', SSE event received"
    }
    Check "the dashboard reflects the postings" {
        $d = (Call GET "/api/v1/dashboard" $alice).Json
        Expect ($d.expense.amount -match "^\d+\.\d\d$") "expense $($d.expense.amount)"
        "expense $($d.expense.amount) $($d.expense.currency), $(@($d.spendByCategory).Count) categories"
    }
    Check "the ALB is internal (no public address)" {
        $scheme = aws elbv2 describe-load-balancers --region $Region --query "LoadBalancers[?contains(LoadBalancerName, 'jars') || contains(LoadBalancerArn, 'jars')].Scheme | [0]" --output text
        Expect ($scheme -eq "internal") "scheme is '$scheme'"
        "scheme=internal"
    }
} finally {
    foreach ($u in $users) {
        aws cognito-idp admin-delete-user --user-pool-id $poolId --username $u --region $Region 2>$null | Out-Null
    }
    Write-Host "Deleted the throw-away users."
}

Write-Host ""
$results | Format-Table -AutoSize -Wrap
$failed = @($results | Where-Object Result -eq "FAIL").Count
Write-Host ("{0} passed, {1} failed" -f ($results.Count - $failed), $failed)
exit $(if ($failed -gt 0) { 1 } else { 0 })
