Set-StrictMode -Version Latest

function Invoke-Task20Compose {
    param(
        [Parameter(Mandatory)][string]$Project,
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    $base = @(
        'compose', '--project-name', $Project, '--env-file', '.env.example',
        '-f', 'docker-compose.yml', '-f', 'docker-compose.performance.yml'
    )
    & docker @base @Arguments | Out-Host
    $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "docker compose command failed with exit code $code"
    }
    return $code
}

function Get-Task20OwnedResources {
    param([Parameter(Mandatory)][string]$Project)
    $filter = "label=com.docker.compose.project=$Project"
    $containers = @(& docker ps -aq --filter $filter | Where-Object { $_ })
    $networks = @(& docker network ls -q --filter $filter | Where-Object { $_ })
    $volumes = @(& docker volume ls -q --filter $filter | Where-Object { $_ })
    [ordered]@{
        project = $Project
        containers = $containers.Count
        networks = $networks.Count
        volumes = $volumes.Count
    }
}

function Assert-Task20ProjectAvailable {
    param([Parameter(Mandatory)][string]$Project)
    $owned = Get-Task20OwnedResources -Project $Project
    if ($owned.containers -ne 0 -or $owned.networks -ne 0 -or $owned.volumes -ne 0) {
        throw "Compose project '$Project' already owns resources; refusing to reuse it"
    }
}

function Get-Task20Percentile {
    param([double[]]$Values, [double]$Percentile)
    if ($null -eq $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($Percentile * $sorted.Count) - 1)
    return [Math]::Round([double]$sorted[$index], 3)
}

function ConvertFrom-Task20DockerSizeBytes {
    param([string]$Value)
    if (-not $Value -or $Value.Trim() -notmatch '^([0-9.]+)\s*(B|kB|KiB|MB|MiB|GB|GiB)$') { return 0L }
    $multipliers = @{ B=1; kB=1000; KiB=1024; MB=1000000; MiB=1048576; GB=1000000000; GiB=1073741824 }
    return [long]([double]$Matches[1] * [long]$multipliers[$Matches[2]])
}

function Get-Task20ResourceSummary {
    param([Parameter(Mandatory)][string]$Path)
    $rows = @(Get-Content -LiteralPath $Path -ErrorAction SilentlyContinue | Where-Object { $_.Trim().StartsWith('{') } | ForEach-Object { $_ | ConvertFrom-Json })
    $byContainer = @($rows | Group-Object Name | ForEach-Object {
        $cpu = @($_.Group | ForEach-Object { [double](([string]$_.CPUPerc).TrimEnd('%')) })
        $memory = @($_.Group | ForEach-Object { ConvertFrom-Task20DockerSizeBytes (([string]$_.MemUsage -split '/')[0].Trim()) })
        [ordered]@{
            name=$_.Name; samples=$_.Count
            maxCpuPercent=if ($cpu.Count) { [Math]::Round(($cpu | Measure-Object -Maximum).Maximum, 3) } else { 0 }
            maxMemoryBytes=if ($memory.Count) { [long](($memory | Measure-Object -Maximum).Maximum) } else { 0 }
        }
    })
    [ordered]@{
        sampleCount=$rows.Count
        containersObserved=$byContainer.Count
        maxContainerCpuPercent=if ($byContainer.Count) { ($byContainer.maxCpuPercent | Measure-Object -Maximum).Maximum } else { 0 }
        maxContainerMemoryBytes=if ($byContainer.Count) { [long](($byContainer.maxMemoryBytes | Measure-Object -Maximum).Maximum) } else { 0 }
        byContainer=$byContainer
    }
}

function Test-Task20TcpPort {
    param([Parameter(Mandatory)][int]$Port)
    $listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback, $Port
    )
    try {
        $listener.Start()
        return $true
    } catch {
        return $false
    } finally {
        try { $listener.Stop() } catch { }
    }
}

function Get-Task20PortBlock {
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        $start = Get-Random -Minimum 20000 -Maximum 50000
        $ports = $start..($start + 11)
        if (@($ports | Where-Object { -not (Test-Task20TcpPort -Port $_) }).Count -eq 0) {
            return $ports
        }
    }
    throw 'Could not reserve an available local port block'
}

function Wait-Task20HttpReady {
    param([Parameter(Mandatory)][string]$Uri, [int]$TimeoutSeconds = 180)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Uri -TimeoutSec 3 -UseBasicParsing
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Service did not become ready: $Uri"
}

function Get-Task20MetricValue {
    param($Summary, [string]$Metric, [string]$Value)
    if ($null -eq $Summary -or $null -eq $Summary.metrics) { return $null }
    $property = $Summary.metrics.PSObject.Properties[$Metric]
    if ($null -eq $property) { return $null }
    $valueProperty = $property.Value.values.PSObject.Properties[$Value]
    if ($null -eq $valueProperty) { return $null }
    return $valueProperty.Value
}

function ConvertTo-Task20SafeFileName {
    param([Parameter(Mandatory)][string]$Value)
    return ($Value -replace '[^a-zA-Z0-9_.-]', '-')
}

function ConvertFrom-Task20DurationSeconds {
    param([Parameter(Mandatory)][string]$Duration)
    if ($Duration -notmatch '^(\d+)(ms|s|m)$') { throw "Unsupported duration: $Duration" }
    $amount = [double]$Matches[1]
    switch ($Matches[2]) {
        'ms' { return $amount / 1000 }
        's' { return $amount }
        'm' { return $amount * 60 }
    }
}

function ConvertTo-Task20Rates {
    param([Parameter(Mandatory)][object[]]$Values)
    $normalized = [System.Collections.Generic.List[int]]::new()
    foreach ($value in $Values) {
        foreach ($token in ([string]$value -split ',')) {
            $candidate = $token.Trim()
            [int]$parsed = 0
            if ($candidate -notmatch '^[1-9]\d*$' -or -not [int]::TryParse($candidate, [ref]$parsed)) {
                throw "Rates must contain positive 32-bit integers; invalid value: '$candidate'"
            }
            $normalized.Add($parsed)
        }
    }
    if ($normalized.Count -eq 0) { throw 'Rates must contain at least one positive integer' }
    return @($normalized)
}

function Get-Task20RunPlan {
    param(
        [Parameter(Mandatory)][ValidateSet('smoke','baseline','target-5k','agent-isolation')][string]$Profile,
        [int[]]$Rates = @(100,250,500,1000), [int]$Rate = 0, [int]$VUs = 0,
        [string]$Duration = '', [int]$UserCount = 0, [int]$Inventory = 0
    )
    $durationValue = if ($Duration) { $Duration } elseif ($Profile -eq 'agent-isolation') { '15s' } else { '10s' }
    $seconds = ConvertFrom-Task20DurationSeconds $durationValue
    $plan = [System.Collections.Generic.List[object]]::new()
    function Add-Stage([int]$Slot,[string]$Scenario,[int]$StageRate,[int]$StageVUs,[string]$StageDuration,[int]$DefaultUsers,[int]$DefaultInventory) {
        $users = if ($UserCount) { $UserCount } else { $DefaultUsers }
        $stock = if ($Inventory) { $Inventory } else { $DefaultInventory }
        $plan.Add([pscustomobject][ordered]@{ slot=$Slot; scenario=$Scenario; rate=$StageRate; vus=$StageVUs; duration=$StageDuration; users=$users; inventory=$stock })
    }
    switch ($Profile) {
        'smoke' {
            $smokeVus = if ($VUs) { $VUs } else { 5 }
            Add-Stage 1 'smoke' 5 $smokeVus $durationValue $smokeVus $smokeVus
        }
        'baseline' {
            Add-Stage 1 'read-baseline' 500 100 $durationValue 1 1
            $slot = 2
            foreach ($stageRate in $Rates) {
                $required = [int][Math]::Ceiling($stageRate * $seconds) + 1
                Add-Stage $slot 'seckill-new-intent' $stageRate ([Math]::Min(1000,[Math]::Max(50,$stageRate))) $durationValue $required $required
                $slot++
            }
            Add-Stage $slot 'idempotency-replay' 1 10 '1s' 10 10; $slot++
            Add-Stage $slot 'oversell-boundary' 50 100 '2s' 101 25; $slot++
            $mixedRequired = [int][Math]::Ceiling(100 * $seconds * 0.30) + 1
            Add-Stage $slot 'mixed-e2e' 100 100 $durationValue $mixedRequired $mixedRequired
        }
        'target-5k' {
            $targetRate = if ($Rate) { $Rate } else { 5000 }
            $required = [int][Math]::Ceiling($targetRate * $seconds) + 1
            Add-Stage 1 'seckill-new-intent' $targetRate $(if ($VUs) {$VUs} else {1000}) $durationValue $required $required
        }
        'agent-isolation' {
            $transactionRate = if ($Rate) { $Rate } else { 20 }
            $required = [int][Math]::Ceiling($transactionRate * $seconds) + 1
            Add-Stage 1 'agent-isolation' $transactionRate $(if ($VUs) {$VUs} else {100}) $durationValue $required $required
        }
    }
    return @($plan)
}

function Test-Task20MessageBacklog {
    param([Parameter(Mandatory)]$Facts)
    return $Facts.outboxPending -eq 0 -and $Facts.inboxPending -eq 0 -and `
        $Facts.streamPending -eq 0 -and $Facts.failedOrStuckEvents -eq 0 -and `
        $Facts.rabbitUnexplainedReady -eq 0 -and $Facts.rabbitUnexplainedUnacknowledged -eq 0 -and `
        $Facts.orderCreatedPublishProofMissing -eq 0 -and $Facts.timeoutPublishProofMissing -eq 0
}

function Test-Task20BusinessCorrectness {
    param(
        [Parameter(Mandatory)]$Facts, [bool]$ExecutionComplete, [long]$ChecksFailed,
        [long]$SystemErrors, [bool]$AsyncDrained, [long]$IdempotencyConflicts,
        [long]$UnclassifiedProblems
    )
    return $ExecutionComplete -and $ChecksFailed -eq 0 -and $SystemErrors -eq 0 -and $AsyncDrained -and `
        $IdempotencyConflicts -eq 0 -and $UnclassifiedProblems -eq 0 -and `
        (Test-Task20MessageBacklog $Facts) -and $Facts.oversell -eq 0 -and `
        $Facts.duplicateValidOrders -eq 0 -and $Facts.reconciliationDifferences -eq 0 -and `
        $Facts.redisRemainingStock -eq $Facts.mysqlRemainingStock -and `
        $Facts.databaseInitialStock -eq $Facts.initialStock -and `
        $Facts.reservations -eq $Facts.validOrders -and `
        ($Facts.initialStock - $Facts.redisRemainingStock) -eq $Facts.reservedQuantity
}

function Test-Task20PerformanceTarget {
    param(
        [Parameter(Mandatory)]$Facts, [bool]$BusinessCorrectnessPassed,
        [double]$ActualNewIntentRps, [double]$RequestedRps, [long]$DroppedIterations,
        [long]$SystemErrors, $P99
    )
    return $BusinessCorrectnessPassed -and (Test-Task20MessageBacklog $Facts) -and `
        $ActualNewIntentRps -ge ($RequestedRps * 0.99) -and $DroppedIterations -eq 0 -and `
        $SystemErrors -eq 0 -and $null -ne $P99 -and [double]$P99 -le 200 -and `
        $Facts.asyncOrderMs.sampleCount -gt 0 -and [double]$Facts.asyncOrderMs.p99 -le 3000
}
