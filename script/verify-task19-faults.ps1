[CmdletBinding()]
param(
    [string]$ProjectName = "",
    [string]$EvidenceDirectory = "",
    [int]$TimeoutSeconds = 1200
)

$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$script:Task19Root = $root
. (Join-Path $PSScriptRoot "task19-common.ps1")
$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 12)
if (-not $ProjectName) { $ProjectName = "hotshop-task19-$suffix" }
if (-not $EvidenceDirectory) {
    $EvidenceDirectory = Join-Path $root "target\task19-faults\$ProjectName"
}
$EvidenceDirectory = [System.IO.Path]::GetFullPath($EvidenceDirectory)
$dockerRoot = Get-Task19DockerHostPath $root
if (Test-Path -LiteralPath $EvidenceDirectory) {
    throw "Refusing to overwrite TASK-19 fault evidence"
}
New-Item -ItemType Directory -Force $EvidenceDirectory | Out-Null
$failures = [System.Collections.Generic.List[string]]::new()
$results = [ordered]@{}

function Invoke-Gate([string]$Name, [scriptblock]$Action) {
    $started = [DateTimeOffset]::UtcNow
    try {
        & $Action
        $script:results[$Name] = [ordered]@{
            status = "PASSED"
            startedAt = $started
            finishedAt = [DateTimeOffset]::UtcNow
        }
    }
    catch {
        $script:results[$Name] = [ordered]@{
            status = "FAILED"
            startedAt = $started
            finishedAt = [DateTimeOffset]::UtcNow
            error = $_.Exception.Message
        }
        [void]$script:failures.Add("$Name`: $($_.Exception.Message)")
    }
}

function Get-DockerRuntimeSnapshot {
    $containers = @(& docker container ls --all --quiet 2>$null | Sort-Object)
    if ($LASTEXITCODE -ne 0) { throw "could not snapshot Docker containers" }
    $volumes = @(& docker volume ls --quiet 2>$null | Sort-Object)
    if ($LASTEXITCODE -ne 0) { throw "could not snapshot Docker volumes" }
    $networks = @(& docker network ls --quiet 2>$null | Sort-Object)
    if ($LASTEXITCODE -ne 0) { throw "could not snapshot Docker networks" }
    return [ordered]@{ containers = $containers; volumes = $volumes; networks = $networks }
}

function Test-SameStringSet([object[]]$Expected, [object[]]$Actual) {
    return @(Compare-Object -ReferenceObject @($Expected) -DifferenceObject @($Actual)).Count -eq 0
}

Set-Location $root
Invoke-Gate "compose-browser-faults" {
    & (Join-Path $PSScriptRoot "verify-task19-e2e.ps1") -ProjectName $ProjectName `
        -EvidenceDirectory (Join-Path $EvidenceDirectory "compose") `
        -TimeoutSeconds $TimeoutSeconds
}

Invoke-Gate "precise-commit-boundaries" {
    $reportDirectory = Join-Path $EvidenceDirectory "java"
    New-Item -ItemType Directory -Force $reportDirectory | Out-Null
    $runtimeBefore = Get-DockerRuntimeSnapshot
    $javaFailure = $null
    $arguments = @(
        "run", "--rm",
        "--volume", "${dockerRoot}:/workspace",
        "--volume", "/var/run/docker.sock:/var/run/docker.sock",
        "--workdir", "/workspace",
        "-e", "TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal",
        "eclipse-temurin:21-jdk@sha256:efd34b940f2d5a621605c8531c2afb7759c936b6c2ef637a69aa3bf3e1e789d1",
        "sh", "./mvnw", "-B", "-ntp", "-pl", "portal,task", "-am",
        "-Dtest=SeckillOrderReliabilityContainerTest,ReliableMessagingContainerTest,PaymentTerminalRaceContainerTest,MockPaymentIntegrationTest,SeckillPaymentExpiredDeliveryContainerTest",
        "-Dsurefire.failIfNoSpecifiedTests=false", "test"
    )
    try {
        $attemptEvidence = [System.Collections.Generic.List[string]]::new()
        $exitCode = 1
        foreach ($attempt in 1..2) {
            $output = @(& docker @arguments 2>&1)
            $exitCode = $LASTEXITCODE
            $safe = ($output -join "`n") -replace 'eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}', '[REDACTED_JWT]'
            Set-Content -LiteralPath (Join-Path $reportDirectory "maven-output.attempt-$attempt.txt") `
                -Value $safe -Encoding UTF8
            [void]$attemptEvidence.Add("=== attempt $attempt; exit=$exitCode ===`n$safe")
            if ($exitCode -eq 0) { break }

            # Docker Desktop can rarely terminate the Temurin launcher before Maven
            # executes any goal. Retry only this auditable native-runtime signature;
            # assertion failures and ordinary BUILD FAILURE results are never retried.
            $nativeLauncherCrash = $exitCode -eq 139 -and
                $safe -match '(?i)(stack smashing detected|segmentation fault)' -and
                $safe -notmatch '(?i)BUILD FAILURE|Tests run:'
            if ($attempt -ne 1 -or -not $nativeLauncherCrash) { break }

            [void](Wait-Task19Until -TimeoutSeconds 120 `
                -Description "Testcontainers cleanup before native-launcher retry" -Probe {
                $current = Get-DockerRuntimeSnapshot
                if (
                    (Test-SameStringSet $runtimeBefore.containers $current.containers) -and
                    (Test-SameStringSet $runtimeBefore.volumes $current.volumes) -and
                    (Test-SameStringSet $runtimeBefore.networks $current.networks)
                ) { return $current }
                return $null
            })
        }
        Set-Content -LiteralPath (Join-Path $reportDirectory "maven-output.txt") `
            -Value ($attemptEvidence -join "`n") -Encoding UTF8
        if ($exitCode -ne 0) { throw "Precise Java reliability tests exited $exitCode" }
    }
    catch { $javaFailure = $_ }
    finally {
        $runtimeAfter = $null
        try {
            $runtimeAfter = Wait-Task19Until -TimeoutSeconds 120 `
                -Description "Testcontainers runtime cleanup" -Probe {
                $current = Get-DockerRuntimeSnapshot
                if (
                    (Test-SameStringSet $runtimeBefore.containers $current.containers) -and
                    (Test-SameStringSet $runtimeBefore.volumes $current.volumes) -and
                    (Test-SameStringSet $runtimeBefore.networks $current.networks)
                ) { return $current }
                return $null
            }
            Write-Task19Json (Join-Path $reportDirectory "cleanup.json") ([ordered]@{
                containers = 0; volumes = 0; networks = 0; clean = $true
            })
        }
        catch {
            Write-Task19Json (Join-Path $reportDirectory "cleanup.json") ([ordered]@{
                containers = if ($runtimeAfter) { @($runtimeAfter.containers).Count } else { $null }
                volumes = if ($runtimeAfter) { @($runtimeAfter.volumes).Count } else { $null }
                networks = if ($runtimeAfter) { @($runtimeAfter.networks).Count } else { $null }
                clean = $false
                error = $_.Exception.Message
            })
            if ($null -eq $javaFailure) { $javaFailure = $_ }
        }
    }
    if ($null -ne $javaFailure) { throw $javaFailure }
}

$matrix = @(
    [ordered]@{
        scenario = "redis-seckill-disconnect"
        initialFacts = "active seeded activity; no reservation for the new User intent"
        injection = "pause redis-seckill in the invocation-owned Compose project"
        expectedInvariants = @("sanitized 503", "same intent key", "at most one reservation/order")
        observability = @("browser response status/problem", "MySQL reservation/order", "Redis replay header")
        finalFacts = [ordered]@{
            mysql = "one sale_reservation and at most one sales_order for the intent"
            redis = "reservation replay=true with one stable reservation number after recovery"
            rabbitmq = "no duplicate downstream business effect"
            source = "real browser responses plus MySQL assertions"
        }
        recovery = "unpause; health/HTTP polling"
        evidence = "compose/playwright-output.txt"
    },
    [ordered]@{
        scenario = "stream-consumer-commit-boundaries"
        initialFacts = "accepted Redis Stream entry with no durable Order"
        injection = "deterministic before-commit, after-commit-before-finalize, and after-finalize-before-ACK failpoints"
        expectedInvariants = @("PEL claimable", "one Order", "one stock deduction or compensation", "eventual Redis/MySQL agreement")
        observability = @("XPENDING/XAUTOCLAIM", "seckill_event_processing", "Order/Outbox/audit rows")
        finalFacts = [ordered]@{
            mysql = "one durable Order or one idempotent compensation; processing row terminal"
            redis = "PEL reaches zero after XAUTOCLAIM and reservation fact agrees with MySQL"
            rabbitmq = "one logical order event despite redelivery"
            source = "SeckillOrderReliabilityContainerTest container assertions"
        }
        recovery = "Awaitility-driven new consumer claim/retry/reconciliation"
        evidence = "java/maven-output.txt and task/target/surefire-reports"
    },
    [ordered]@{
        scenario = "rabbitmq-disconnect"
        initialFacts = "Order transaction ready to commit with NEW Outbox"
        injection = "real RabbitMQ outage and broker-confirm crash failpoints"
        expectedInvariants = @("Order and Outbox commit together", "pending publication", "Inbox/unique constraints deduplicate")
        observability = @("outbox status/lease/attempts", "Rabbit queue", "processed_event")
        finalFacts = [ordered]@{
            mysql = "business row and NEW Outbox commit atomically; Outbox becomes PUBLISHED after recovery"
            redis = "not authoritative for this path and remains unchanged"
            rabbitmq = "one queue-visible logical delivery; duplicate is absorbed by Inbox/unique key"
            source = "ReliableMessagingContainerTest database and broker assertions"
        }
        recovery = "broker restart followed by bounded publisher polling"
        evidence = "java/maven-output.txt and task/target/surefire-reports"
    },
    [ordered]@{
        scenario = "mysql-transient-outage"
        initialFacts = "Redis reservation accepted or Rabbit delivery unacknowledged"
        injection = "Testcontainers database proxy/outage before transaction commit"
        expectedInvariants = @("no half Order/inventory/payment/Outbox", "delivery remains retryable", "reconciliation converges")
        observability = @("transaction rows", "PEL/unacked delivery", "reconciliation findings")
        finalFacts = [ordered]@{
            mysql = "no partial Order/inventory/payment/Outbox tuple; retry commits one legal outcome"
            redis = "accepted reservation eventually maps to one Order or compensated inventory"
            rabbitmq = "unacknowledged delivery remains retryable and deduplicated"
            source = "Testcontainers transaction, PEL, and redelivery assertions"
        }
        recovery = "database restoration plus Awaitility fact polling"
        evidence = "java/maven-output.txt"
    },
    [ordered]@{
        scenario = "portal-task-restart"
        initialFacts = "durable timeline cursor and pending asynchronous work"
        injection = "stop/start Portal during live SSE; stop Task after Redis acceptance and start it with pending work"
        expectedInvariants = @("Last-Event-ID resumes", "no second business effect", "consumer/scheduler recover")
        observability = @("new SSE request header", "timeline event count", "pending/consumer facts")
        finalFacts = [ordered]@{
            mysql = "one order for the reservation accepted while Task was stopped"
            redis = "pending reservation is consumed after Task restart without duplicate"
            rabbitmq = "recovered Task connections preserve one logical downstream effect"
            source = "real browser Last-Event-ID, Task restart, timeline, and MySQL assertions"
        }
        recovery = "start owned containers and readiness/event polling"
        evidence = "compose/playwright-output.txt; java/maven-output.txt"
    },
    [ordered]@{
        scenario = "payment-terminal-race"
        initialFacts = "PENDING payment with unique nonce and open Order"
        injection = "concurrent success/failure/timeout plus duplicate and delayed callbacks"
        expectedInvariants = @("one legal Order terminal", "one callback ledger fact", "inventory restored at most once")
        observability = @("payment/order status", "nonce/ledger/audit/Outbox/timeline")
        finalFacts = [ordered]@{
            mysql = "one legal payment/order terminal with consistent nonce, ledger, audit, Outbox, and stock"
            redis = "reserved stock is restored at most once when the winning terminal requires it"
            rabbitmq = "duplicate/late delivery produces no second transition"
            source = "PaymentTerminalRaceContainerTest and real Mock payment journey assertions"
        }
        recovery = "Awaitility waits for callback/timeout terminal facts"
        evidence = "java/maven-output.txt; compose/playwright-output.txt"
    },
    [ordered]@{
        scenario = "agent-qdrant-outage"
        initialFacts = "healthy Agent session and indexed static knowledge"
        injection = "stop Agent, then independently stop Qdrant"
        expectedInvariants = @("transaction core remains usable", "Qdrant failure does not disable dynamic tools", "no provider fallback")
        observability = @("Agent readiness/degraded DOM", "catalog HTTP", "tool.completed and RAG unavailable")
        finalFacts = [ordered]@{
            mysql = "existing order and ORDER_CREATED timeline remain readable during both outages"
            redis = "Agent state outage is isolated from transaction state; recovered session policy is exercised fresh"
            rabbitmq = "transaction messaging remains available and unchanged"
            source = "real browser catalog/order/timeline and dynamic-tool assertions"
        }
        recovery = "restart owned Agent/Qdrant and poll readiness"
        evidence = "compose/playwright-output.txt"
    }
)

$cleanup = [ordered]@{ project = $ProjectName; compose = $null; java = $null; clean = $false }
try {
    $cleanup.compose = Get-Content -LiteralPath (Join-Path $EvidenceDirectory "compose\cleanup.json") -Raw | ConvertFrom-Json
    $cleanup.java = Get-Content -LiteralPath (Join-Path $EvidenceDirectory "java\cleanup.json") -Raw | ConvertFrom-Json
    $cleanup.clean = [bool]$cleanup.compose.clean -and [bool]$cleanup.java.clean
    if (-not $cleanup.clean) { [void]$failures.Add("fault cleanup evidence is not clean") }
}
catch { [void]$failures.Add("fault cleanup evidence is missing or invalid") }
Write-Task19Json (Join-Path $EvidenceDirectory "cleanup.json") $cleanup

[ordered]@{
    schemaVersion = "1.0"
    project = $ProjectName
    provider = "fake"
    embedding = "deterministic"
    gates = $results
    matrix = $matrix
    passed = $failures.Count -eq 0
} | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory "fault-matrix.json") -Encoding UTF8

if ($failures.Count) { throw "TASK-19 fault matrix failed: $($failures -join '; ')" }
Write-Output "TASK-19 fault matrix passed; evidence=$EvidenceDirectory"
