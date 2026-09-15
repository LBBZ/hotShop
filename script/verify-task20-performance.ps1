[CmdletBinding()]
param(
    [ValidateSet('smoke', 'baseline', 'target-5k', 'agent-isolation')]
    [string]$Profile = 'smoke',
    [string[]]$Rates = @('100', '250', '500', '1000'),
    [int]$Rate = 0,
    [int]$VUs = 0,
    [string]$Warmup = '2s',
    [string]$Duration = '',
    [long]$DataSeed = 0,
    [int]$UserCount = 0,
    [int]$Inventory = 0,
    [long]$ActivityId = 0,
    [string]$RunId = '',
    [string]$BaseUrl = 'http://portal-service:8080',
    [string]$AgentBaseUrl = 'http://agent-service:8090',
    [string]$PrometheusRemoteWriteUrl = 'http://prometheus:9090/api/v1/write',
    [switch]$KeepStack,
    [switch]$RequirePerformanceTarget,
    [switch]$SkipBuild,
    [switch]$ControlledStartupFailure
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
. (Join-Path $PSScriptRoot 'task20-common.ps1')

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'docker is required' }
if ($Warmup -notmatch '^\d+(ms|s|m)$') { throw 'Warmup must use ms, s, or m' }
if ($Duration -and $Duration -notmatch '^\d+(ms|s|m)$') { throw 'Duration must use ms, s, or m' }
$normalizedRates = [int[]]@(ConvertTo-Task20Rates -Values $Rates)
if ($PrometheusRemoteWriteUrl -notmatch '^https?://') {
    throw 'PrometheusRemoteWriteUrl must be an explicit HTTP(S) URL'
}
if ($BaseUrl -notmatch '^https?://' -or $AgentBaseUrl -notmatch '^https?://') {
    throw 'BaseUrl and AgentBaseUrl must be explicit HTTP(S) URLs reachable by the k6 container'
}

$startedAt = [DateTimeOffset]::UtcNow
$worktreeCleanBeforeRun = -not [bool](& git status --porcelain)
if (-not $RunId) { $RunId = 'run-' + $startedAt.ToString('yyyyMMdd-HHmmss') + '-' + (Get-Random -Minimum 1000 -Maximum 9999) }
$RunId = $RunId.ToLowerInvariant()
if ($RunId -notmatch '^[a-z0-9][a-z0-9-]{5,63}$') { throw 'RunId must match ^[a-z0-9][a-z0-9-]{5,63}$' }
$project = "hotshop-task20-$RunId"
if ($project.Length -gt 63) { throw 'RunId is too long for the isolated Compose project name' }
if ($DataSeed -eq 0) { $DataSeed = [long]$startedAt.ToString('yyyyMMddHHmmss') }
if ($DataSeed -le 0) { throw 'DataSeed must be positive' }
if ($ActivityId -lt 0) { throw 'ActivityId must be zero (automatic) or positive' }

$artifactDir = Join-Path $root "target/task20-performance/$RunId"
if (Test-Path -LiteralPath $artifactDir) {
    $existingEntries = @(Get-ChildItem -LiteralPath $artifactDir -Force)
    if ($existingEntries.Count -gt 0) { throw "RunId '$RunId' already has non-empty evidence; refusing to mix runs" }
} else {
    New-Item -ItemType Directory -Path $artifactDir | Out-Null
}
$commands = [System.Collections.Generic.List[object]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()
$failures = [System.Collections.Generic.List[string]]::new()
$scenarioResults = [System.Collections.Generic.List[object]]::new()
$ports = Get-Task20PortBlock
$runPlan = @(Get-Task20RunPlan -Profile $Profile -Rates $normalizedRates -Rate $Rate -VUs $VUs `
    -Duration $Duration -UserCount $UserCount -Inventory $Inventory)
$envNames = [System.Collections.Generic.List[string]]::new()
$runPassword = 'Task20!' + ([Guid]::NewGuid().ToString('N'))
$adminName = 'task20-admin-' + ($RunId -replace '^run-', '')
if ($adminName.Length -gt 64) { $adminName = $adminName.Substring(0, 64) }
$loadPasswordHash = $null

function Set-RunEnvironment([string]$Name, [string]$Value) {
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
    if (-not $envNames.Contains($Name)) { $envNames.Add($Name) }
}

function Record-Command([string]$Command, [int]$ExitCode) {
    $commands.Add([ordered]@{ command = $Command; exitCode = $ExitCode })
}

function Invoke-Mysql([string]$Sql) {
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = $Sql | & docker compose --project-name $project --env-file .env.example `
            -f docker-compose.yml -f docker-compose.performance.yml exec -T mysql sh -lc `
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot "$MYSQL_DATABASE" --batch --raw --skip-column-names'
        if ($LASTEXITCODE -ne 0) { throw "MySQL command failed with exit code $LASTEXITCODE" }
        return @($output)
    } finally {
        $ErrorActionPreference = $oldPreference
    }
}

function New-Activity([int]$Slot, [int]$Stock, [int]$Users) {
    if ($Users -gt 100000 -or $Stock -gt 100000) { throw 'Data set exceeds the explicit 100000 safety bound' }
    $requestedActivityId = if ($ActivityId -gt 0) { $ActivityId + $Slot - 1 } else { 'NULL' }
    $sql = @"
SET @hotshop_seed=$DataSeed;
SET @hotshop_user_count=$Users;
SET @hotshop_product_count=1;
SET @hotshop_activity_slot=$Slot;
SET @hotshop_activity_stock=$Stock;
SET @hotshop_activity_id=$requestedActivityId;
SOURCE /opt/hotshop/data/load-data.sql;
UPDATE app_user
   SET password_hash='$loadPasswordHash', status='ACTIVE', deleted_at=NULL
 WHERE username LIKE CONCAT('load-', @hotshop_seed, '-user-%');
SELECT activity_id FROM flash_sale_activity WHERE activity_code=CONCAT('LOAD-', @hotshop_seed, '-ACTIVITY-', LPAD(@hotshop_activity_slot, 4, '0'));
"@
    $lines = @(Invoke-Mysql $sql | Where-Object { $_ -match '^\d+$' })
    if ($lines.Count -ne 1) { throw "Could not uniquely locate load-test activity slot $Slot" }
    $createdActivityId = [long]$lines[0]
    if ($ActivityId -gt 0 -and $createdActivityId -ne [long]$requestedActivityId) {
        throw "Requested activity ID $requestedActivityId was not created"
    }

    $adminLogin = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($ports[1])/admin/api/v1/auth/login" `
        -ContentType 'application/json' -Body (@{ username = $adminName; password = $runPassword } | ConvertTo-Json -Compress)
    if (-not $adminLogin.accessToken) { throw 'Administrator login returned no access token' }
    $headers = @{ Authorization = "Bearer $($adminLogin.accessToken)"; 'X-Request-Id' = "task20-load-$Slot" }
    $loaded = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($ports[1])/admin/api/v1/flash-sales/$createdActivityId/load" `
        -Headers $headers -ContentType 'application/json' `
        -Body (@{ reason = "TASK-20 isolated performance run $RunId" } | ConvertTo-Json -Compress)
    $adminLogin = $null
    $headers = $null
    if (-not $loaded.consistent) { throw "Redis activity load was not consistent for slot $Slot" }
    return $createdActivityId
}

function Get-ScenarioFacts([long]$ActivityId, [int]$InitialStock) {
    $factSql = @"
SELECT CONCAT_WS('\t',
  a.total_stock,
  a.available_stock,
  (SELECT COUNT(*) FROM sale_reservation r WHERE r.activity_id=a.activity_id),
  (SELECT COUNT(*) FROM sales_order o JOIN sale_reservation r ON r.reservation_id=o.reservation_id WHERE r.activity_id=a.activity_id),
  (SELECT COALESCE(SUM(r.quantity),0) FROM sale_reservation r WHERE r.activity_id=a.activity_id),
  (SELECT COUNT(*) FROM (SELECT r.reservation_id FROM sale_reservation r LEFT JOIN sales_order o ON o.reservation_id=r.reservation_id WHERE r.activity_id=$ActivityId GROUP BY r.reservation_id HAVING COUNT(o.order_id)>1) d),
  (SELECT COUNT(*) FROM seckill_event_processing p WHERE p.activity_id=a.activity_id AND p.status IN ('RETRYING','COMPENSATING','QUARANTINED','MANUAL_REVIEW')),
  (SELECT COUNT(*) FROM seckill_reconciliation_issue i WHERE i.activity_id=a.activity_id AND i.status='OPEN'),
  (SELECT COUNT(*) FROM outbox_event WHERE status IN ('NEW','PUBLISHING','FAILED')),
  (SELECT COUNT(*) FROM processed_event),
  (SELECT COUNT(*) FROM sales_order o JOIN sale_reservation r ON r.reservation_id=o.reservation_id
    WHERE r.activity_id=a.activity_id AND NOT EXISTS (
      SELECT 1 FROM outbox_event e WHERE e.aggregate_type='ORDER' AND e.aggregate_id=CAST(o.order_id AS CHAR)
        AND e.event_type='ORDER_CREATED' AND e.status='PUBLISHED')),
  (SELECT COUNT(*) FROM sales_order o JOIN sale_reservation r ON r.reservation_id=o.reservation_id
    WHERE r.activity_id=a.activity_id AND NOT EXISTS (
      SELECT 1 FROM outbox_event e WHERE e.aggregate_type='ORDER' AND e.aggregate_id=CAST(o.order_id AS CHAR)
        AND e.event_type='LEGACY_ORDER_TIMEOUT_REQUESTED' AND e.status='PUBLISHED')),
  a.status)
FROM flash_sale_activity a WHERE a.activity_id=$ActivityId;
"@
    $line = @(Invoke-Mysql $factSql | Where-Object { $_ })[-1]
    $parts = $line -split "`t"
    if ($parts.Count -ne 13) { throw 'Fact query returned an unexpected shape' }
    # Braces are required here: in an interpolated PowerShell string,
    # `$ActivityId:stock` is parsed as a scoped variable rather than a value
    # followed by a literal suffix.
    $redisKey = "hotshop:seckill:v1:{hotshop-seckill-v1}:activity:${ActivityId}:stock"
    $streamKey = "hotshop:seckill:v1:{hotshop-seckill-v1}:activity:${ActivityId}:reservations"
    $redisContainer = @(& docker ps -q --filter "label=com.docker.compose.project=$project" --filter 'label=com.docker.compose.service=redis-seckill' | Where-Object { $_ })
    if ($redisContainer.Count -ne 1) { throw 'Could not uniquely resolve the run-owned Redis container' }
    $redisContainerId = $redisContainer[0]
    $redisRaw = @(& docker exec $redisContainerId redis-cli --raw GET $redisKey)
    $redisExit = $LASTEXITCODE
    if ($redisExit -ne 0 -or $redisRaw.Count -ne 1) {
        throw "Could not read Redis stock fact (exit=$redisExit, rows=$($redisRaw.Count))"
    }
    try { $redisStock = [long](([string]$redisRaw[0]).Trim()) }
    catch { throw 'Redis stock fact was not an integer' }
    $streamRows = @(& docker exec $redisContainerId redis-cli --raw XLEN $streamKey)
    $streamExit = $LASTEXITCODE
    if ($streamExit -ne 0 -or $streamRows.Count -ne 1) {
        throw 'Could not read Redis Stream length'
    }
    try { $streamLength = [long](([string]$streamRows[0]).Trim()) }
    catch { throw 'Redis Stream length was not an integer' }
    $pendingRaw = @(& docker exec $redisContainerId redis-cli --raw XPENDING $streamKey hotshop-order-v1 2>$null)
    $pending = 0L
    if ($LASTEXITCODE -eq 0 -and $pendingRaw.Count -gt 0) {
        try { $pending = [long](([string]$pendingRaw[0]).Trim()) } catch { $pending = 0L }
    }
    $latencyLines = @(Invoke-Mysql "SELECT ROUND(TIMESTAMPDIFF(MICROSECOND,r.reserved_at,o.created_at)/1000,3) FROM sale_reservation r JOIN sales_order o ON o.reservation_id=r.reservation_id WHERE r.activity_id=$ActivityId AND r.reserved_at IS NOT NULL ORDER BY o.created_at;")
    [double[]]$latencies = @($latencyLines | Where-Object { $_ -match '^\d+(\.\d+)?$' } | ForEach-Object { [double]$_ })
    $rabbitLines = @(& docker compose --project-name $project --env-file .env.example -f docker-compose.yml -f docker-compose.performance.yml exec -T rabbitmq rabbitmqctl list_queues --quiet name messages_ready messages_unacknowledged)
    $rabbitReady = 0L
    $rabbitUnacknowledged = 0L
    $rabbitExcludedReady = 0L
    $rabbitQueues = [System.Collections.Generic.List[object]]::new()
    $excludedRabbitQueues = [System.Collections.Generic.List[object]]::new()
    $exclusionBoundary = [ordered]@{
        'hotshop.order.created.v1' = [ordered]@{
            eventType = 'ORDER_CREATED'
            reason = 'Integration queue has no consumer in this repository; order creation is already committed and proven by one PUBLISHED outbox row per run order.'
        }
        'hotshop.order.timeout.delay.v1' = [ordered]@{
            eventType = 'LEGACY_ORDER_TIMEOUT_REQUESTED'
            reason = 'Intentional 15-minute TTL delay queue; one PUBLISHED timeout outbox row per run order proves the run-owned delivery boundary.'
        }
    }
    foreach ($rabbitLine in $rabbitLines) {
        $queueParts = $rabbitLine -split "`t"
        if ($queueParts.Count -eq 3 -and $queueParts[1] -match '^\d+$' -and $queueParts[2] -match '^\d+$') {
            $queueName = $queueParts[0]
            $ready = [long]$queueParts[1]
            $unacknowledged = [long]$queueParts[2]
            $rabbitReady += $ready
            $rabbitUnacknowledged += $unacknowledged
            $excluded = $exclusionBoundary.Contains($queueName)
            $rabbitQueues.Add([ordered]@{ name=$queueName; ready=$ready; unacknowledged=$unacknowledged; excludedReady=$excluded })
            if ($excluded) {
                $rabbitExcludedReady += $ready
                $boundary = $exclusionBoundary[$queueName]
                $excludedRabbitQueues.Add([ordered]@{ name=$queueName; eventType=$boundary.eventType; ready=$ready; reason=$boundary.reason })
            }
        }
    }
    $reservedQuantity = [long]$parts[4]
    [ordered]@{
        initialStock = $InitialStock
        databaseInitialStock = [long]$parts[0]
        mysqlRemainingStock = [long]$parts[1]
        redisRemainingStock = [long]$redisStock
        reservations = [long]$parts[2]
        validOrders = [long]$parts[3]
        reservedQuantity = $reservedQuantity
        duplicateValidOrders = [long]$parts[5]
        oversell = [Math]::Max(0, $reservedQuantity - $InitialStock)
        failedOrStuckEvents = [long]$parts[6]
        streamLength = [long]$streamLength
        streamPending = $pending
        outboxPending = [long]$parts[8]
        # processed_event is this system's durable Inbox/idempotency ledger. It has
        # no pending state: rows are inserted atomically only after processing.
        inboxProcessed = [long]$parts[9]
        inboxPending = 0
        rabbitReady = $rabbitReady
        rabbitUnacknowledged = $rabbitUnacknowledged
        rabbitExcludedReady = $rabbitExcludedReady
        rabbitUnexplainedReady = $rabbitReady - $rabbitExcludedReady
        # In-flight deliveries are never excluded, even for a boundary queue.
        rabbitUnexplainedUnacknowledged = $rabbitUnacknowledged
        rabbitQueues = @($rabbitQueues)
        excludedRabbitQueues = @($excludedRabbitQueues)
        orderCreatedPublishProofMissing = [long]$parts[10]
        timeoutPublishProofMissing = [long]$parts[11]
        reconciliationDifferences = [long]$parts[7]
        finalActivityStatus = $parts[12]
        asyncOrderMs = [pscustomobject][ordered]@{
            formula = 'TIMESTAMPDIFF(MICROSECOND,sale_reservation.reserved_at,sales_order.created_at)/1000'
            sampleCount = $latencies.Count
            p50 = Get-Task20Percentile $latencies 0.50
            p95 = Get-Task20Percentile $latencies 0.95
            p99 = Get-Task20Percentile $latencies 0.99
            max = if ($latencies.Count) { [Math]::Round(($latencies | Measure-Object -Maximum).Maximum, 3) } else { $null }
        }
    }
}

function Wait-AsyncDrain([long]$ActivityId, [long]$ExpectedReservations) {
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(3)
    do {
        $line = @(Invoke-Mysql "SELECT CONCAT_WS('\t',(SELECT COUNT(*) FROM sale_reservation WHERE activity_id=$ActivityId),(SELECT COUNT(*) FROM sales_order o JOIN sale_reservation r ON r.reservation_id=o.reservation_id WHERE r.activity_id=$ActivityId),(SELECT COUNT(*) FROM seckill_event_processing WHERE activity_id=$ActivityId AND status IN ('RETRYING','COMPENSATING')),(SELECT COUNT(*) FROM outbox_event e JOIN sales_order o ON e.aggregate_type='ORDER' AND e.aggregate_id=CAST(o.order_id AS CHAR) JOIN sale_reservation r ON r.reservation_id=o.reservation_id WHERE r.activity_id=$ActivityId AND e.status IN ('NEW','PUBLISHING','FAILED')))" | Where-Object { $_ })[-1]
        $p = $line -split "`t"
        if ($p.Count -eq 4 -and [long]$p[0] -eq $ExpectedReservations -and `
            [long]$p[1] -eq $ExpectedReservations -and [long]$p[2] -eq 0 -and [long]$p[3] -eq 0) {
            Start-Sleep -Seconds 2
            return $true
        }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    return $false
}

function Invoke-K6Scenario([string]$Scenario, [int]$ScenarioRate, [int]$ScenarioVUs, [string]$ScenarioDuration, [int]$ScenarioUsers, [int]$ScenarioInventory, [int]$Slot) {
    $activityId = New-Activity -Slot $Slot -Stock $ScenarioInventory -Users $ScenarioUsers
    $scenarioDir = Join-Path $artifactDir ((ConvertTo-Task20SafeFileName "$Slot-$Scenario-$ScenarioRate"))
    New-Item -ItemType Directory -Force -Path $scenarioDir | Out-Null
    # Treat -VUs as an explicit generator resource budget. k6 reports dropped
    # iterations when that budget cannot sustain the requested arrival rate,
    # preserving a complete, honest result instead of exhausting the host.
    $preVus = $ScenarioVUs
    $maxVus = $ScenarioVUs
    Set-RunEnvironment TASK20_ACTIVITY_ID "$activityId"
    Set-RunEnvironment TASK20_USER_COUNT "$ScenarioUsers"
    Set-RunEnvironment TASK20_INVENTORY "$ScenarioInventory"
    Set-RunEnvironment TASK20_RATE "$ScenarioRate"
    Set-RunEnvironment TASK20_VUS "$ScenarioVUs"
    Set-RunEnvironment TASK20_PRE_ALLOCATED_VUS "$preVus"
    Set-RunEnvironment TASK20_MAX_VUS "$maxVus"
    Set-RunEnvironment TASK20_DURATION $ScenarioDuration
    Set-RunEnvironment TASK20_SCENARIO $Scenario
    Set-RunEnvironment TASK20_ARTIFACT_DIR $scenarioDir

    $stdout = Join-Path $scenarioDir 'k6.stdout.log'
    $stderr = Join-Path $scenarioDir 'k6.stderr.log'
    $args = @(
        'compose', '--project-name', $project, '--env-file', '.env.example',
        '-f', 'docker-compose.yml', '-f', 'docker-compose.performance.yml',
        '--profile', 'app', '--profile', 'observability', '--profile', 'agent', '--profile', 'performance',
        'run', '--rm', '--no-deps', 'k6'
    )
    $process = Start-Process -FilePath 'docker' -ArgumentList $args -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -WindowStyle Hidden
    $samples = Join-Path $scenarioDir 'container-resources.jsonl'
    Set-Content -LiteralPath $samples -Value '' -Encoding utf8
    while (-not $process.HasExited) {
        $ids = @(& docker ps -q --filter "label=com.docker.compose.project=$project" | Where-Object { $_ })
        if ($ids.Count -gt 0) {
            # A short-lived k6/one-shot container may disappear between `ps` and `stats`.
            # That sampling race is expected and must not abort the measured run.
            $previousNativePreference = $PSNativeCommandUseErrorActionPreference
            $PSNativeCommandUseErrorActionPreference = $false
            $statRows = @(& docker stats --no-stream --format '{{json .}}' @ids 2>$null)
            $PSNativeCommandUseErrorActionPreference = $previousNativePreference
            $statRows | ForEach-Object {
                if ($_ -and $_.StartsWith('{')) {
                    $sample = $_ | ConvertFrom-Json
                    $sample | Add-Member -NotePropertyName sampledAt -NotePropertyValue ([DateTimeOffset]::UtcNow.ToString('o'))
                    Add-Content -LiteralPath $samples -Value ($sample | ConvertTo-Json -Compress)
                }
            }
        }
        Start-Sleep -Seconds 2
        $process.Refresh()
    }
    $process.WaitForExit()
    $k6Exit = $process.ExitCode
    $resourceSummary = Get-Task20ResourceSummary $samples
    Record-Command "docker compose ... run --rm --no-deps k6 ($Scenario rate=$ScenarioRate)" $k6Exit

    $summaryPath = Join-Path $scenarioDir 'raw-summary.json'
    $k6Summary = if (Test-Path $summaryPath) { Get-Content $summaryPath -Raw | ConvertFrom-Json } else { $null }
    $attempted = [double](Get-Task20MetricValue $k6Summary 'hotshop_new_intents' 'count')
    $acceptedNew = [long](Get-Task20MetricValue $k6Summary 'hotshop_new_accepted' 'count')
    $accepted = [long](Get-Task20MetricValue $k6Summary 'hotshop_business_accepted' 'count')
    $soldOut = [long](Get-Task20MetricValue $k6Summary 'hotshop_business_sold_out' 'count')
    $rejected = [long](Get-Task20MetricValue $k6Summary 'hotshop_business_rejected' 'count')
    $rateLimited = [long](Get-Task20MetricValue $k6Summary 'hotshop_business_rate_limited' 'count')
    $http2xx = [long](Get-Task20MetricValue $k6Summary 'hotshop_http_2xx' 'count')
    $http4xx = [long](Get-Task20MetricValue $k6Summary 'hotshop_http_4xx' 'count')
    $http5xx = [long](Get-Task20MetricValue $k6Summary 'hotshop_http_5xx' 'count')
    $httpOther = [long](Get-Task20MetricValue $k6Summary 'hotshop_http_other' 'count')
    $scenarioIterations = [double](Get-Task20MetricValue $k6Summary 'hotshop_scenario_iterations' 'count')
    $expectedReservations = $acceptedNew
    $drained = if ($null -eq $k6Summary) {
        # Without the k6 summary there is no trustworthy accepted-count target;
        # fail integrity immediately instead of waiting for an unknowable drain.
        $false
    } elseif ($Scenario -eq 'read-baseline') {
        $true
    } else {
        Wait-AsyncDrain $activityId $expectedReservations
    }
    $facts = Get-ScenarioFacts -ActivityId $activityId -InitialStock $ScenarioInventory
    if ($ScenarioDuration -match '^(\d+)s$') { $durationSeconds = [double]$Matches[1] }
    elseif ($ScenarioDuration -match '^(\d+)m$') { $durationSeconds = [double]$Matches[1] * 60 }
    elseif ($ScenarioDuration -match '^(\d+)ms$') { $durationSeconds = [double]$Matches[1] / 1000 }
    else { throw "Unsupported duration: $ScenarioDuration" }
    $actualRps = if ($scenarioIterations -gt 0) { [Math]::Round($scenarioIterations / $durationSeconds, 3) } else { 0 }
    $newIntentRps = if ($attempted -gt 0) { [Math]::Round($attempted / $durationSeconds, 3) } else { 0 }
    $p95 = Get-Task20MetricValue $k6Summary 'hotshop_new_intent_duration_ms' 'p(95)'
    $p99 = Get-Task20MetricValue $k6Summary 'hotshop_new_intent_duration_ms' 'p(99)'
    $dropped = [long](Get-Task20MetricValue $k6Summary 'dropped_iterations' 'count')
    $systemErrors = [long](Get-Task20MetricValue $k6Summary 'hotshop_business_system_errors' 'count')
    $checksFailed = [long](Get-Task20MetricValue $k6Summary 'checks' 'fails')
    $problemMetricNames = [ordered]@{
        IDEMPOTENCY_KEY_CONFLICT='hotshop_problem_idempotency_key_conflict'
        RATE_LIMITED='hotshop_problem_rate_limited'
        FLASH_SALE_ACTIVITY_NOT_FOUND='hotshop_problem_flash_sale_activity_not_found'
        FLASH_SALE_NOT_STARTED='hotshop_problem_flash_sale_not_started'
        FLASH_SALE_ENDED='hotshop_problem_flash_sale_ended'
        FLASH_SALE_NOT_ACTIVE='hotshop_problem_flash_sale_not_active'
        FLASH_SALE_SOLD_OUT='hotshop_problem_flash_sale_sold_out'
        FLASH_SALE_USER_LIMIT_REACHED='hotshop_problem_flash_sale_user_limit_reached'
        FLASH_SALE_INVALID_QUANTITY='hotshop_problem_flash_sale_invalid_quantity'
        TRANSACTION_RATE_LIMIT_UNAVAILABLE='hotshop_problem_transaction_rate_limit_unavailable'
        SECKILL_STATE_INVALID='hotshop_problem_seckill_state_invalid'
        UNCLASSIFIED='hotshop_problem_unclassified'
    }
    $rejectionReasons = [ordered]@{}
    foreach ($entry in $problemMetricNames.GetEnumerator()) {
        $rejectionReasons[$entry.Key] = [long](Get-Task20MetricValue $k6Summary $entry.Value 'count')
    }
    $idempotencyConflicts = [long]$rejectionReasons.IDEMPOTENCY_KEY_CONFLICT
    $unclassifiedProblems = [long]$rejectionReasons.UNCLASSIFIED
    $agentAttemptCount = [long](Get-Task20MetricValue $k6Summary 'hotshop_agent_attempts' 'count')
    $agentSuccessCount = [long](Get-Task20MetricValue $k6Summary 'hotshop_agent_runs' 'count')
    $agentFailureCount = [long](Get-Task20MetricValue $k6Summary 'hotshop_agent_failures' 'count')
    $agentP95 = Get-Task20MetricValue $k6Summary 'hotshop_agent_run_duration_ms' 'p(95)'
    $agentP99 = Get-Task20MetricValue $k6Summary 'hotshop_agent_run_duration_ms' 'p(99)'
    $executionComplete = if ($Scenario -eq 'read-baseline') {
        [long](Get-Task20MetricValue $k6Summary 'http_reqs' 'count') -gt 0
    } elseif ($Scenario -eq 'oversell-boundary') {
        $attempted -gt $ScenarioInventory -and $facts.reservations -eq $ScenarioInventory
    } elseif ($Scenario -in @('smoke','idempotency-replay')) {
        $attempted -eq $ScenarioVUs -and $facts.reservations -eq $ScenarioVUs
    } else {
        $attempted -gt 0 -and $facts.reservations -eq $attempted
    }
    if ($Scenario -eq 'agent-isolation') {
        $executionComplete = $executionComplete -and $agentAttemptCount -gt 0 -and `
            $agentAttemptCount -eq ($agentSuccessCount + $agentFailureCount) -and $agentFailureCount -eq 0
    }
    $correct = Test-Task20BusinessCorrectness -Facts $facts -ExecutionComplete $executionComplete `
        -ChecksFailed $checksFailed -SystemErrors $systemErrors -AsyncDrained $drained `
        -IdempotencyConflicts $idempotencyConflicts -UnclassifiedProblems $unclassifiedProblems
    $targetMet = $Scenario -in @('seckill-new-intent','agent-isolation') -and `
        (Test-Task20PerformanceTarget -Facts $facts -BusinessCorrectnessPassed $correct `
            -ActualNewIntentRps $newIntentRps -RequestedRps $ScenarioRate -DroppedIterations $dropped `
            -SystemErrors $systemErrors -P99 $p99)
    $result = [ordered]@{
        scenario = $Scenario; targetRps = $ScenarioRate; actualRps = $actualRps; newIntentRps = $newIntentRps
        attemptedRequests = [long]$attempted
        acceptedNewRequests = $acceptedNew
        acceptedResponses = $accepted
        soldOutResponses = $soldOut
        businessRejectedResponses = $rejected
        rateLimitedResponses = $rateLimited
        rejectionReasons = $rejectionReasons
        httpStatusClasses = [ordered]@{ http2xx = $http2xx; http4xx = $http4xx; http5xx = $http5xx; other = $httpOther }
        http5xxRatio = if (($http2xx+$http4xx+$http5xx+$httpOther) -gt 0) { [Math]::Round($http5xx / ($http2xx+$http4xx+$http5xx+$httpOther), 6) } else { 0 }
        duration = $ScenarioDuration; warmup = $Warmup; activityId = $activityId
        users = $ScenarioUsers; inventory = $ScenarioInventory; vus = $ScenarioVUs
        identityPreparation = if ($Profile -eq 'target-5k') { 'unique-user-login-per-iteration' } else { 'setup-token-pool' }
        throughputDenominatorSeconds = $durationSeconds
        p95Ms = $p95; p99Ms = $p99; droppedIterations = $dropped
        systemErrors = $systemErrors; checksFailed = $checksFailed; k6ExitCode = $k6Exit
        evidenceComplete = ($null -ne $k6Summary -and (Test-Path $summaryPath) -and $resourceSummary.sampleCount -gt 0)
        resources = $resourceSummary
        asyncDrained = $drained; businessCorrectnessPassed = $correct
        performanceTargetMet = $targetMet; facts = $facts
        agent = if ($Scenario -eq 'agent-isolation') {
            [ordered]@{
                attempts=$agentAttemptCount; successes=$agentSuccessCount; failures=$agentFailureCount
                successRate=if ($agentAttemptCount) { [Math]::Round($agentSuccessCount / $agentAttemptCount, 6) } else { 0 }
                p95Ms=$agentP95; p99Ms=$agentP99
            }
        } else { $null }
        transactionTraffic = if ($Scenario -eq 'agent-isolation') {
            [ordered]@{ attempts=[long]$attempted; actualRps=$newIntentRps; p95Ms=$p95; p99Ms=$p99 }
        } else { $null }
        trafficMix = if ($Scenario -eq 'mixed-e2e') {
            [ordered]@{ publicReadPct = 50; newIntentPct = 30; authenticatedReadPct = 20 }
        } else { $null }
        artifactDirectory = $scenarioDir.Substring($root.Length + 1).Replace('\','/')
    }
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $scenarioDir 'scenario-summary.json') -Encoding utf8
    $scenarioResults.Add($result)
    if ($facts.rabbitUnexplainedReady -gt 0 -or $facts.rabbitUnexplainedUnacknowledged -gt 0) {
        $failures.Add("RabbitMQ retained unexplained backlog after ${Scenario}: ready=$($facts.rabbitUnexplainedReady), unacknowledged=$($facts.rabbitUnexplainedUnacknowledged)")
    }
    if ($idempotencyConflicts -gt 0 -and $Scenario -in @('seckill-new-intent','mixed-e2e')) {
        $failures.Add("IDEMPOTENCY_KEY_CONFLICT occurred in $Scenario")
    }
    if ($k6Exit -ne 0) { $failures.Add("k6 failed for $Scenario at $ScenarioRate RPS") }
    if (-not $correct -and $Scenario -ne 'read-baseline') { $failures.Add("business correctness failed for $Scenario at $ScenarioRate RPS") }
}

$cleanup = $null
$projectOwnershipAcquired = $false
$composeUpSucceeded = $false
try {
    Assert-Task20ProjectAvailable -Project $project
    $projectOwnershipAcquired = $true
    @(& docker ps -a --format '{{json .}}') | Set-Content -LiteralPath (Join-Path $artifactDir 'docker-before.jsonl') -Encoding utf8
    Set-RunEnvironment COMPOSE_PROJECT_NAME $project
    Set-RunEnvironment PORTAL_PORT "$($ports[0])"
    Set-RunEnvironment ADMIN_PORT "$($ports[1])"
    Set-RunEnvironment AGENT_PORT "$($ports[3])"
    Set-RunEnvironment PROMETHEUS_PORT "$($ports[4])"
    Set-RunEnvironment GRAFANA_PORT "$($ports[5])"
    Set-RunEnvironment TASK20_RUN_ID $RunId
    Set-RunEnvironment TASK20_PROFILE $Profile
    Set-RunEnvironment TASK20_DATA_SEED "$DataSeed"
    Set-RunEnvironment TASK20_USER_PASSWORD $runPassword
    Set-RunEnvironment TASK20_WARMUP $Warmup
    Set-RunEnvironment TASK20_PROMETHEUS_RW_URL $PrometheusRemoteWriteUrl
    Set-RunEnvironment TASK20_BASE_URL $BaseUrl
    Set-RunEnvironment TASK20_AGENT_BASE_URL $AgentBaseUrl
    # Required placeholders allow Compose to validate the merged model before a
    # concrete run-owned Activity exists. Invoke-K6Scenario replaces every value.
    Set-RunEnvironment TASK20_ACTIVITY_ID '1'
    Set-RunEnvironment TASK20_USER_COUNT '1'
    Set-RunEnvironment TASK20_INVENTORY '1'
    Set-RunEnvironment TASK20_RATE '1'
    Set-RunEnvironment TASK20_VUS '1'
    Set-RunEnvironment TASK20_PRE_ALLOCATED_VUS '1'
    Set-RunEnvironment TASK20_MAX_VUS '1'
    Set-RunEnvironment TASK20_DURATION '1s'
    Set-RunEnvironment TASK20_SCENARIO 'smoke'
    Set-RunEnvironment TASK20_ARTIFACT_DIR $artifactDir
    Set-RunEnvironment AGENT_MODEL_PROVIDER 'fake'
    Set-RunEnvironment AGENT_EMBEDDING_PROVIDER 'deterministic'
    Set-RunEnvironment AGENT_RAG_ENABLED 'false'
    Set-RunEnvironment HOTSHOP_TRACE_SAMPLING_PROBABILITY '0.01'

    if ($ControlledStartupFailure) {
        $partialCode = Invoke-Task20Compose -Project $project -Arguments @('up','-d','mysql')
        Record-Command 'docker compose ... up -d mysql (controlled partial start)' $partialCode
        $failureCode = Invoke-Task20Compose -Project $project -Arguments @('up','-d','task20-controlled-missing-service') -AllowFailure
        Record-Command 'docker compose ... up -d task20-controlled-missing-service (expected failure)' $failureCode
        if ($failureCode -eq 0) { throw 'Controlled startup failure command unexpectedly succeeded' }
        throw "Controlled startup failure produced expected Compose exit code $failureCode"
    }

    if (-not (Test-Path '.local/keys/hotshop/user-private.pem')) {
        & (Join-Path $root 'script/generate-auth-keys.ps1')
        Record-Command 'pwsh -File script/generate-auth-keys.ps1' $LASTEXITCODE
    }
    $profiles = @('--profile','app','--profile','observability')
    if ($Profile -eq 'agent-isolation') { $profiles += @('--profile','agent') }
    $up = @($profiles + @('up','-d'))
    if (-not $SkipBuild) { $up += '--build' }
    $code = Invoke-Task20Compose -Project $project -Arguments $up
    Record-Command ($(if ($SkipBuild) { 'docker compose ... up -d' } else { 'docker compose ... up -d --build' })) $code
    $composeUpSucceeded = $true
    Wait-Task20HttpReady "http://127.0.0.1:$($ports[0])/actuator/health"
    Wait-Task20HttpReady "http://127.0.0.1:$($ports[1])/actuator/health"
    if ($Profile -eq 'agent-isolation') { Wait-Task20HttpReady "http://127.0.0.1:$($ports[3])/health/ready" }
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($ports[0])/api/v1/auth/register" `
        -ContentType 'application/json' -Body (@{
            username = $adminName
            password = $runPassword
            email = "$adminName@hotshop.invalid"
        } | ConvertTo-Json -Compress) | Out-Null
    $hashRows = @(Invoke-Mysql "UPDATE app_user SET role='ROLE_ADMIN' WHERE username='$adminName'; SELECT password_hash FROM app_user WHERE username='$adminName';" | Where-Object { ([string]$_).Trim().StartsWith('$2') })
    if ($hashRows.Count -ne 1) { throw 'Runtime registration returned an unexpected password-hash row count' }
    $loadPasswordHash = ([string]$hashRows[0]).Trim()
    if ($loadPasswordHash.Length -ne 60 -or -not $loadPasswordHash.StartsWith('$2')) {
        throw 'Runtime registration did not produce a valid BCrypt hash'
    }

    foreach ($stage in $runPlan) {
        Invoke-K6Scenario $stage.scenario $stage.rate $stage.vus $stage.duration $stage.users $stage.inventory $stage.slot
    }

    $queries = @(
        [ordered]@{ name='k6RequestCount'; required=$true; query="sum by (scenario) (increase(k6_http_reqs_total{testid=`"$RunId`"}[30m]))" }
        [ordered]@{ name='k6NewIntentP99'; required=$true; query="max(max_over_time(k6_hotshop_new_intent_duration_ms_p99{testid=`"$RunId`"}[30m]))" }
        [ordered]@{ name='scrapeUp'; required=$true; query='sum by (job)(up)' }
    )
    $prometheusSnapshots = foreach ($querySpec in $queries) {
        try {
            $encoded = [Uri]::EscapeDataString($querySpec.query)
            $response = Invoke-RestMethod "http://127.0.0.1:$($ports[4])/api/v1/query?query=$encoded"
            $sampleCount = @($response.data.result).Count
            if ($querySpec.required -and $sampleCount -eq 0) {
                $failures.Add("Prometheus query '$($querySpec.name)' returned no samples")
            }
            [ordered]@{ name=$querySpec.name; query=$querySpec.query; required=$querySpec.required; sampleCount=$sampleCount; response=$response }
        } catch {
            $failures.Add("Prometheus query '$($querySpec.name)' failed")
            [ordered]@{ name=$querySpec.name; query=$querySpec.query; required=$querySpec.required; sampleCount=0; error='query failed' }
        }
    }
    $prometheusSnapshots | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $artifactDir 'prometheus-snapshots.json') -Encoding utf8

    $infrastructure = [ordered]@{
        mysqlStatus = @(Invoke-Mysql "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Max_used_connections','Slow_queries','Queries');")
        mysqlSlowStatements = @(Invoke-Mysql 'SELECT DIGEST_TEXT,COUNT_STAR,ROUND(SUM_TIMER_WAIT/1000000000000,3) total_seconds FROM performance_schema.events_statements_summary_by_digest WHERE DIGEST_TEXT IS NOT NULL ORDER BY SUM_TIMER_WAIT DESC LIMIT 10;')
        redisCache = @(& docker compose --project-name $project --env-file .env.example -f docker-compose.yml -f docker-compose.performance.yml exec -T redis-cache sh -lc 'redis-cli INFO memory stats commandstats | grep -E "^(used_memory:|used_memory_peak:|total_commands_processed:|instantaneous_ops_per_sec:|cmdstat_)"')
        redisSeckill = @(& docker compose --project-name $project --env-file .env.example -f docker-compose.yml -f docker-compose.performance.yml exec -T redis-seckill sh -lc 'redis-cli INFO memory stats commandstats | grep -E "^(used_memory:|used_memory_peak:|total_commands_processed:|instantaneous_ops_per_sec:|cmdstat_)"')
        redisLatency = @(& docker compose --project-name $project --env-file .env.example -f docker-compose.yml -f docker-compose.performance.yml exec -T redis-seckill redis-cli --raw LATENCY LATEST)
        rabbitQueues = @(& docker compose --project-name $project --env-file .env.example -f docker-compose.yml -f docker-compose.performance.yml exec -T rabbitmq rabbitmqctl list_queues --quiet name messages_ready messages_unacknowledged)
    }
    $infrastructure | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $artifactDir 'infrastructure-snapshot.json') -Encoding utf8
    @($scenarioResults | ForEach-Object {
        [ordered]@{ scenario=$_.scenario; activityId=$_.activityId; users=$_.users; inventory=$_.inventory; seed=$DataSeed }
    }) | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $artifactDir 'dataset.json') -Encoding utf8
    @(& docker compose --project-name $project --env-file .env.example -f docker-compose.yml -f docker-compose.performance.yml images --format json) |
        Set-Content -LiteralPath (Join-Path $artifactDir 'images.jsonl') -Encoding utf8
} catch {
    $failures.Add($_.Exception.Message)
    $_ | Out-String | Set-Content -LiteralPath (Join-Path $artifactDir 'failure.log') -Encoding utf8
} finally {
    $retainSuccessfulStack = $KeepStack -and $composeUpSucceeded
    if ($projectOwnershipAcquired -and -not $retainSuccessfulStack) {
        try {
            $code = Invoke-Task20Compose -Project $project -Arguments @('--profile','app','--profile','agent','--profile','observability','--profile','performance','down','--volumes','--remove-orphans') -AllowFailure
            Record-Command 'docker compose ... down --volumes --remove-orphans' $code
        } catch { $failures.Add('Safe Compose cleanup command failed') }
    } elseif ($retainSuccessfulStack) {
        $warnings.Add('Stack retained by -KeepStack; owned resource count is expected to be non-zero')
    }
    $cleanup = Get-Task20OwnedResources -Project $project
    $cleanup['resourcesZero'] = ($cleanup.containers -eq 0 -and $cleanup.networks -eq 0 -and $cleanup.volumes -eq 0)
    $cleanup | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $artifactDir 'cleanup.json') -Encoding utf8
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
}

$newStages = @($scenarioResults | Where-Object { $_.scenario -in @('seckill-new-intent','agent-isolation') })
$credible = @($newStages | Where-Object { $_.performanceTargetMet } | Sort-Object actualRps -Descending | Select-Object -First 1)
$target5k = @($newStages | Where-Object { $_.targetRps -eq 5000 -and $_.newIntentRps -ge 4950 -and $_.duration -eq '10s' -and $_.performanceTargetMet }).Count -gt 0
$correctness = $failures.Count -eq 0 -and @($scenarioResults | Where-Object { -not $_.businessCorrectnessPassed -and $_.scenario -ne 'read-baseline' }).Count -eq 0
$integrity = $scenarioResults.Count -gt 0 -and $cleanup.resourcesZero -and `
    @($scenarioResults | Where-Object { -not $_.evidenceComplete -or $_.k6ExitCode -ne 0 }).Count -eq 0
$summary = [ordered]@{
    runId = $RunId; startedAt = $startedAt.ToString('o'); endedAt = [DateTimeOffset]::UtcNow.ToString('o')
    gitHead = (& git rev-parse HEAD); worktreeCleanAtStart = $worktreeCleanBeforeRun; profile = $Profile
    dataSeed = $DataSeed; requestedUsers = $UserCount; requestedInventory = $Inventory
    requestedActivityId = $ActivityId
    warmup = $Warmup; scenarios = @($scenarioResults)
    target5000RpsActuallyReached = $target5k
    highestCredibleSustainedRps = if ($credible.Count) { $credible[0].newIntentRps } else { $null }
    performanceTargetMet = $target5k
    businessCorrectnessPassed = $correctness
    runIntegrityPassed = $integrity
    ownedResourcesZero = $cleanup.resourcesZero
    excludedRabbitQueues = @($scenarioResults | ForEach-Object { $_.facts.excludedRabbitQueues } | `
        Group-Object name | ForEach-Object { $_.Group | Select-Object -First 1 })
    warnings = @($warnings); failures = @($failures); commands = @($commands)
}
$agentResult = @($scenarioResults | Where-Object { $null -ne $_.agent } | Select-Object -First 1)
if ($agentResult.Count) {
    $summary['agentIsolation'] = [ordered]@{
        agent=$agentResult[0].agent
        transactionTraffic=$agentResult[0].transactionTraffic
        simultaneousTrafficConclusion=[ordered]@{
            businessCorrectnessPassed=$agentResult[0].businessCorrectnessPassed
            messageBacklogPassed=(Test-Task20MessageBacklog $agentResult[0].facts)
            resourceConclusion=[ordered]@{
                samplesCaptured=($agentResult[0].resources.sampleCount -gt 0)
                sampleCount=$agentResult[0].resources.sampleCount
                maxContainerCpuPercent=$agentResult[0].resources.maxContainerCpuPercent
                maxContainerMemoryBytes=$agentResult[0].resources.maxContainerMemoryBytes
            }
            performanceTargetMet=$agentResult[0].performanceTargetMet
        }
    }
}
$representative = @($newStages | Sort-Object targetRps -Descending | Select-Object -First 1)
if (-not $representative.Count) {
    $representative = @($scenarioResults | Sort-Object targetRps -Descending | Select-Object -First 1)
}
if ($representative.Count) {
    $r = $representative[0]
    $summary['targetRps'] = $r.targetRps
    $summary['actualRps'] = $r.actualRps
    $summary['vus'] = $r.vus
    $summary['duration'] = $r.duration
    $summary['p95Ms'] = $r.p95Ms
    $summary['p99Ms'] = $r.p99Ms
    $summary['droppedIterations'] = $r.droppedIterations
    $summary['http5xxRatio'] = $r.http5xxRatio
    $summary['asyncOrderP95Ms'] = $r.facts.asyncOrderMs.p95
    $summary['asyncOrderP99Ms'] = $r.facts.asyncOrderMs.p99
    $summary['oversell'] = $r.facts.oversell
    $summary['duplicateValidOrders'] = $r.facts.duplicateValidOrders
    $summary['reconciliationDifferences'] = $r.facts.reconciliationDifferences
}
if (-not (Test-Path (Join-Path $artifactDir 'failure.log'))) {
    Set-Content -LiteralPath (Join-Path $artifactDir 'failure.log') -Value '' -Encoding utf8
}
@{ profile=$Profile; runId=$RunId; dataSeed=$DataSeed; rates=$normalizedRates; rate=$Rate; vus=$VUs; warmup=$Warmup; duration=$Duration; keepStack=[bool]$KeepStack; controlledStartupFailure=[bool]$ControlledStartupFailure; plan=$runPlan } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $artifactDir 'parameters.json') -Encoding utf8
@{ gitHead=$summary.gitHead; docker=(& docker version --format '{{.Server.Version}}'); compose=(& docker compose version --short); k6='0.54.0'; k6Digest='sha256:1f40432b1cbe7234e977f96c362c9bc550a2d2b583d014dd8669fe40d3e9e755' } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $artifactDir 'versions.json') -Encoding utf8
$hardware = try {
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1 Name,NumberOfCores,NumberOfLogicalProcessors
    $system = Get-CimInstance Win32_ComputerSystem | Select-Object -First 1 TotalPhysicalMemory
    [ordered]@{ cpu=$cpu; totalPhysicalMemoryBytes=$system.TotalPhysicalMemory; os=[System.Environment]::OSVersion.VersionString }
} catch {
    [ordered]@{ cpu='unavailable'; totalPhysicalMemoryBytes=$null; os=[System.Environment]::OSVersion.VersionString }
}
$hardware | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $artifactDir 'hardware.json') -Encoding utf8
$sensitivePattern = '(?i)(authorization\s*[:=]|bearer\s+[a-z0-9._-]+|accessToken|refreshToken|private.?key|cookie\s*[:=]|password\s*[:=])'
$hits = @(Get-ChildItem $artifactDir -File -Recurse | Select-String -Pattern $sensitivePattern | ForEach-Object { "$($_.Path):$($_.LineNumber)" })
@{ passed=($hits.Count -eq 0); findingLocations=$hits } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $artifactDir 'redaction-check.json') -Encoding utf8
if ($hits.Count) {
    $failures.Add('Artifact redaction check found possible secret-bearing fields')
    $summary.businessCorrectnessPassed = $false
    $summary.runIntegrityPassed = $false
    $summary.failures = @($failures)
}
$summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $artifactDir 'summary.json') -Encoding utf8

Write-Host "TASK-20 report: $artifactDir"
Write-Host "Business correctness: $($summary.businessCorrectnessPassed); run integrity: $($summary.runIntegrityPassed); 5000 RPS target: $target5k"
if ($failures.Count -gt 0 -or -not $summary.businessCorrectnessPassed -or -not $summary.runIntegrityPassed) { exit 1 }
if ($RequirePerformanceTarget -and -not $summary.performanceTargetMet) { exit 2 }
exit 0
