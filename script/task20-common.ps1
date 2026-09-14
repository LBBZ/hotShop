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
