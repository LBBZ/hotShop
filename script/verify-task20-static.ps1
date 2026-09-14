[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root
$staticPassword = [Guid]::NewGuid().ToString('N')

$values = [ordered]@{
    TASK20_ACTIVITY_ID='1'; TASK20_DATA_SEED='42'; TASK20_USER_COUNT='5'
    TASK20_INVENTORY='5'; TASK20_RATE='5'; TASK20_VUS='5'
    TASK20_PRE_ALLOCATED_VUS='5'; TASK20_MAX_VUS='10'; TASK20_WARMUP='1s'
    TASK20_DURATION='1s'; TASK20_RUN_ID='run-static-1234'; TASK20_PROFILE='smoke'
    TASK20_SCENARIO='smoke'; TASK20_USER_PASSWORD=$staticPassword
    TASK20_ARTIFACT_DIR=(Join-Path $root 'target/task20-performance/static')
}
foreach ($entry in $values.GetEnumerator()) {
    [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
}
try {
    New-Item -ItemType Directory -Force $values.TASK20_ARTIFACT_DIR | Out-Null
    & docker compose --env-file .env.example -f docker-compose.yml -f docker-compose.performance.yml `
        --profile app --profile observability --profile performance config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'TASK-20 merged Compose validation failed' }

    $image = 'grafana/k6:0.54.0@sha256:1f40432b1cbe7234e977f96c362c9bc550a2d2b583d014dd8669fe40d3e9e755'
    $k6Values = [ordered]@{
        ACTIVITY_ID='1'; DATA_SEED='42'; USER_COUNT='5'; INVENTORY='5'; RATE='5'
        VUS='5'; PRE_ALLOCATED_VUS='5'; MAX_VUS='10'; WARMUP='1s'; DURATION='1s'
        RUN_ID='run-static-1234'; PROFILE='smoke'; SCENARIO='smoke'
        USER_PASSWORD=$staticPassword; BASE_URL='http://portal-service:8080'
        AGENT_BASE_URL='http://agent-service:8090'
    }
    $k6EnvArgs = foreach ($entry in $k6Values.GetEnumerator()) { '-e'; "$($entry.Key)=$($entry.Value)" }
    & docker run --rm `
        --mount "type=bind,source=$root/load/k6,target=/work,readonly" `
        $image inspect @k6EnvArgs /work/scenarios/task20.js | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'TASK-20 k6 script inspection failed' }

    $tokens = $null
    $parseErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile(
        (Join-Path $root 'script/verify-task20-performance.ps1'), [ref]$tokens, [ref]$parseErrors
    ) | Out-Null
    if ($parseErrors.Count -ne 0) { throw 'TASK-20 PowerShell parser validation failed' }
    Write-Host 'TASK-20 Compose, k6, and PowerShell static validation passed.'
} finally {
    foreach ($entry in $values.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $null, 'Process')
    }
}
