[CmdletBinding()]
param(
    [switch]$SkipStartup,
    [string]$UserName = "",
    [string]$AdminUserName = "dev-admin",
    [string]$AdminPassword = "password",
    [string]$UserPassword = "Task11!Local123",
    [string]$ProjectName = "hotshop-task11-verify",
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot
$composeArguments = @("-p", $ProjectName, "--env-file", ".env.example")

$sentinel = "TASK11_SENTINEL_SECRET_7f63a2f9"
$env:HOTSHOP_MOCK_PAYMENT_ENABLED = "true"
$env:HOTSHOP_MOCK_PAYMENT_SECRET = "$sentinel-padding-for-local-demo"
$env:HOTSHOP_SECURE_COOKIES = "false"
$env:HOTSHOP_TRACE_SAMPLING_PROBABILITY = "1.0"

function Wait-Until {
    param([scriptblock]$Probe, [string]$Description)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $delay = 100
    do {
        try {
            $value = & $Probe
            if ($null -ne $value -and $value -ne $false) { return $value }
        } catch { }
        if ([DateTimeOffset]::UtcNow -ge $deadline) {
            throw "Timed out waiting for $Description"
        }
        Start-Sleep -Milliseconds $delay
        $delay = [Math]::Min(2000, [int]($delay * 1.6))
    } while ($true)
}

function Wait-Http([string]$Url) {
    Wait-Until -Description $Url -Probe {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 3
        if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) { $response }
    }
}

function Invoke-Json {
    param([string]$Method, [string]$Uri, [hashtable]$Headers, [object]$Body)
    $arguments = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        UseBasicParsing = $true
        TimeoutSec = 15
    }
    if ($null -ne $Body) {
        $arguments.ContentType = "application/json"
        $arguments.Body = $Body | ConvertTo-Json -Compress -Depth 10
    }
    $response = Invoke-WebRequest @arguments
    [pscustomobject]@{
        Headers = $response.Headers
        Body = if ($response.Content) { $response.Content | ConvertFrom-Json } else { $null }
    }
}

function New-RandomHex([int]$ByteCount) {
    $bytes = New-Object byte[] $ByteCount
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return ([BitConverter]::ToString($bytes) -replace '-', '').ToLowerInvariant()
}

if (-not $SkipStartup) {
    $keyDirectory = Join-Path $projectRoot ".local/keys/hotshop"
    $expectedKeys = @("user", "administrator", "agent-delegation", "agent-service") |
        ForEach-Object { Join-Path $keyDirectory "$_-private.pem"; Join-Path $keyDirectory "$_-public.pem" }
    $presentKeys = @($expectedKeys | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
    if ($presentKeys.Count -eq 0) {
        & "$PSScriptRoot/generate-auth-keys.ps1"
        if ($LASTEXITCODE -ne 0) { throw "Authentication key generation failed" }
    } elseif ($presentKeys.Count -ne $expectedKeys.Count) {
        throw "Authentication key directory is incomplete; refusing to overwrite it"
    }
    docker compose @composeArguments --profile app --profile agent --profile observability up -d --build
    if ($LASTEXITCODE -ne 0) { throw "Compose startup failed" }
}

@(
    "http://localhost:8080/v3/api-docs",
    "http://localhost:8088/v3/api-docs",
    "http://localhost:8888/actuator/health/readiness",
    "http://localhost:8090/health/ready",
    "http://localhost:9090/-/ready",
    "http://localhost:3100/ready",
    "http://localhost:3200/ready",
    "http://localhost:12345/-/ready",
    "http://localhost:3000/api/health"
) | ForEach-Object { [void](Wait-Http $_) }

$seedSql = Get-Content -LiteralPath "database/data/dev-data.sql" -Raw -Encoding UTF8
$seedSql | docker compose @composeArguments exec -T mysql sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot "$MYSQL_DATABASE"'
if ($LASTEXITCODE -ne 0) { throw "Development data load failed" }
$adminPasswordHash = '$2a$10$TMZmcGpxe.3oTQlg1ikW1O7ioPlkqJDRMUiQ9MQ9QN6K7Z9m0msyi'
$activateSql = @"
UPDATE app_user
SET password_hash='$adminPasswordHash', status='ACTIVE', deleted_at=NULL,
    updated_at=UTC_TIMESTAMP(6)
WHERE username='dev-admin';
UPDATE flash_sale_activity
SET status='ACTIVE', starts_at=UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE,
    ends_at=UTC_TIMESTAMP(6)+INTERVAL 30 MINUTE, available_stock=total_stock,
    version=version+1, updated_at=UTC_TIMESTAMP(6)
WHERE activity_id=900001;
"@
$activateSql | docker compose @composeArguments exec -T mysql sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot "$MYSQL_DATABASE"'
if ($LASTEXITCODE -ne 0) { throw "Activity activation failed" }

$requestId = "task11-" + [Guid]::NewGuid().ToString("N")
$traceId = New-RandomHex 16
$parentSpanId = New-RandomHex 8
$traceParent = "00-$traceId-$parentSpanId-01"
$baseHeaders = @{"X-Request-ID"=$requestId; traceparent=$traceParent; tracestate="hotshop=task11"}

$adminLogin = Invoke-Json POST "http://localhost:8088/admin/api/v1/auth/login" $baseHeaders @{
    username=$AdminUserName; password=$AdminPassword
}
$adminHeaders = $baseHeaders.Clone()
$adminHeaders.Authorization = "Bearer $($adminLogin.Body.accessToken)"
$activityPrefix = "hotshop:seckill:v1:{hotshop-seckill-v1}:activity:900001"
$streamRegistry = "hotshop:seckill:v1:{hotshop-seckill-v1}:registry:reservation-streams"
$activityStream = "$activityPrefix`:reservations"
docker compose @composeArguments exec -T redis-seckill redis-cli DEL `
    "$activityPrefix`:meta" "$activityPrefix`:stock" $activityStream | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Could not reset the local demonstration activity" }
docker compose @composeArguments exec -T redis-seckill redis-cli SREM `
    $streamRegistry $activityStream | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Could not reset the local Stream registry" }
[void](Invoke-Json POST "http://localhost:8088/admin/api/v1/flash-sales/900001/load" $adminHeaders $null)

if (-not $UserName) {
    $UserName = "task11-" + [Guid]::NewGuid().ToString("N")
    [void](Invoke-Json POST "http://localhost:8080/api/v1/auth/register" $baseHeaders @{
        username=$UserName
        password=$UserPassword
        email="$UserName@hotshop.invalid"
    })
}

$userLogin = Invoke-Json POST "http://localhost:8080/api/v1/auth/login" $baseHeaders @{
    username=$UserName; password=$UserPassword
}
$userHeaders = $baseHeaders.Clone()
$userHeaders.Authorization = "Bearer $($userLogin.Body.accessToken)"
$userHeaders["Idempotency-Key"] = "task11-" + [Guid]::NewGuid().ToString("N")
$reservation = Invoke-Json POST "http://localhost:8080/api/v1/flash-sales/900001/reservations" $userHeaders @{quantity=1}
$reservationNo = $reservation.Body.reservationNo
if (-not $reservationNo) { throw "Reservation response did not contain reservationNo" }

$reservationStatus = Wait-Until -Description "asynchronous MySQL Order" -Probe {
    $current = Invoke-Json GET "http://localhost:8080/api/v1/flash-sales/900001/reservations/$reservationNo" $userHeaders $null
    if ($current.Body.status -eq "ORDER_CREATED" -and $current.Body.orderId) { $current.Body }
}
$payment = Invoke-Json POST "http://localhost:8080/api/v1/orders/$($reservationStatus.orderId)/payments" $userHeaders $null
[void](Invoke-Json POST "http://localhost:8080/api/v1/payments/$($payment.Body.paymentNo)/mock-actions" $userHeaders @{
    outcome="SUCCEEDED"; delay="PT0S"; duplicateCount=2
})
[void](Wait-Until -Description "Mock Payment callback and final state" -Probe {
    $current = Invoke-Json GET "http://localhost:8080/api/v1/payments/$($payment.Body.paymentNo)" $userHeaders $null
    if ($current.Body.status -eq "SUCCEEDED") { $current.Body }
})
[void](Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:8090/health/live" -Headers $baseHeaders)

$metricQueries = @(
    "http_server_requests_seconds_count",
    "hotshop_redis_lua_calls_total",
    "hotshop_seckill_order_consumed_total",
    "hotshop_outbox_publish_total",
    "hotshop_rabbitmq_deliveries_total",
    "hotshop_payment_callbacks_total",
    "hotshop_agent_active_runs"
)
foreach ($metric in $metricQueries) {
    [void](Wait-Until -Description "Prometheus metric $metric" -Probe {
        $query = [Uri]::EscapeDataString($metric)
        $result = Invoke-RestMethod "http://localhost:9090/api/v1/query?query=$query"
        if ($result.status -eq "success" -and $result.data.result.Count -gt 0) { $result }
    })
}

$lokiQuery = [Uri]::EscapeDataString(('{{compose_service="portal-service"}} |= "{0}"' -f $requestId))
$loki = Wait-Until -Description "Loki requestId log" -Probe {
    $result = Invoke-RestMethod "http://localhost:3100/loki/api/v1/query_range?query=$lokiQuery&limit=100"
    if ($result.data.result.Count -gt 0) { $result }
}
$tempo = Wait-Until -Description "Tempo trace $traceId" -Probe {
    $result = Invoke-WebRequest -UseBasicParsing "http://localhost:3200/api/traces/$traceId"
    if ($result.Content -match 'service.name' -and $result.Content -match 'portal' -and
        $result.Content -match 'task' -and $result.Content -match 'agent') { $result }
}

@("hotshop-transaction-overview", "hotshop-messaging-reliability", "hotshop-agent", "hotshop-k6") |
    ForEach-Object { [void](Wait-Http "http://localhost:3000/api/dashboards/uid/$_") }

docker compose @composeArguments --profile observability restart prometheus loki tempo alloy grafana
if ($LASTEXITCODE -ne 0) { throw "Observability restart failed" }
@("http://localhost:9090/-/ready", "http://localhost:3100/ready", "http://localhost:3200/ready",
  "http://localhost:12345/-/ready", "http://localhost:3000/api/health") |
    ForEach-Object { [void](Wait-Http $_) }
@("hotshop-transaction-overview", "hotshop-messaging-reliability", "hotshop-agent", "hotshop-k6") |
    ForEach-Object { [void](Wait-Http "http://localhost:3000/api/dashboards/uid/$_") }

$dockerLogs = docker compose @composeArguments logs --no-color
$seriesSelector = [Uri]::EscapeDataString('{__name__=~".+"}')
$prometheusSeries = (Invoke-WebRequest -UseBasicParsing "http://localhost:9090/api/v1/series?match[]=$seriesSelector").Content
$lokiSentinelQuery = [Uri]::EscapeDataString(('{{compose_project=~".+"}} |= "{0}"' -f $sentinel))
$lokiSentinel = (Invoke-WebRequest -UseBasicParsing "http://localhost:3100/loki/api/v1/query_range?query=$lokiSentinelQuery&limit=20").Content
$evidence = @($dockerLogs, $prometheusSeries, $lokiSentinel, $tempo.Content) -join "`n"
if ($evidence.Contains($sentinel)) { throw "Sentinel secret leaked into observability output" }

[pscustomobject]@{
    requestId = $requestId
    traceId = $traceId
    reservationNo = $reservationNo
    orderId = $reservationStatus.orderId
    paymentNo = $payment.Body.paymentNo
    metrics = $metricQueries.Count
    lokiStreams = $loki.data.result.Count
    dashboards = 4
    restartVerified = $true
    sentinelLeak = $false
} | ConvertTo-Json -Depth 4
