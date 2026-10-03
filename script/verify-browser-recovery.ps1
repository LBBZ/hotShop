#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^hotshop-task21-[a-z0-9]{8,24}$')][string]$DemoProject,
    [string]$DemoUrl = 'http://127.0.0.1:18080',
    [ValidateRange(1024,65535)][int]$AgentPort = 18085,
    [ValidateRange(1024,65535)][int]$WebPort = 18086,
    [ValidatePattern('^[a-z0-9-]{6,32}$')][string]$RunId = ('recovery-' + [Guid]::NewGuid().ToString('N').Substring(0,12))
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$destination = Join-Path $root ".local/verification/$RunId"
if (Test-Path -LiteralPath $destination) { throw 'Use a fresh RunId; existing evidence is never overwritten.' }
$uri = [Uri]$DemoUrl
if (-not $uri.IsLoopback -or $uri.Scheme -notin @('http','https')) { throw 'Recovery tests require a loopback demo URL.' }
$containerName = "hotshop-recovery-$RunId-agent"
$redisName = "hotshop-recovery-$RunId-redis"
$sourceIds = @(docker ps -q --filter "label=com.docker.compose.project=$DemoProject" --filter 'label=com.docker.compose.service=agent-service')
if ($LASTEXITCODE -ne 0 -or $sourceIds.Count -ne 1) { throw 'Expected one running Agent in the selected demo project.' }
$source = @(docker inspect $sourceIds[0] | ConvertFrom-Json)[0]
$networks = @($source.NetworkSettings.Networks.PSObject.Properties.Name)
if ($networks.Count -ne 1) { throw 'Expected one demo network.' }
if (@(docker ps -aq --filter "name=^${containerName}$").Count) { throw 'Refusing an existing recovery container.' }
New-Item -ItemType Directory -Path $destination | Out-Null
$envFile = Join-Path $destination 'agent.env'
$redisEnvFile = Join-Path $destination 'redis.env'
$saved = @{}
$vite = $null
$owned = $false
$redisOwned = $false
$exitCode = 1
try {
    # Borrow read-only key mounts and backend connectivity. The source service is
    # never restarted; the temporary Agent owns a disposable Redis instance.
    $redisIds = @(docker ps -q --filter "label=com.docker.compose.project=$DemoProject" --filter 'label=com.docker.compose.service=redis-cache')
    if ($redisIds.Count -ne 1) { throw 'Expected one demo Redis image source.' }
    $redisImage = docker inspect --format '{{.Image}}' $redisIds[0]
    if (@(docker ps -aq --filter "name=^${redisName}$").Count) { throw 'Refusing an existing recovery Redis container.' }
    $redisSecret = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    "REDISCLI_AUTH=$redisSecret" | Set-Content -LiteralPath $redisEnvFile -Encoding utf8NoBOM
    $redisOwned = $true
    & docker run -d --name $redisName --label "hotshop.recovery.owner=$RunId" --network $networks[0] `
        --env-file $redisEnvFile --memory 64m --cpus 0.25 --entrypoint sh $redisImage `
        -c 'exec redis-server --save "" --appendonly no --maxmemory 32mb --maxmemory-policy noeviction --requirepass "$REDISCLI_AUTH"' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Temporary Redis startup failed.' }
    @($source.Config.Env | Where-Object { $_ -notmatch '^AGENT_(REDIS_URL|RAG_ENABLED|MODEL_PROVIDER)=' }) +
        @("AGENT_REDIS_URL=redis://:${redisSecret}@${redisName}:6379/0", 'AGENT_RAG_ENABLED=false', 'AGENT_MODEL_PROVIDER=fake') |
        Set-Content -LiteralPath $envFile -Encoding utf8NoBOM
    $owned = $true
    & docker run -d --name $containerName --label "hotshop.recovery.owner=$RunId" `
        --network $networks[0] --env-file $envFile --volumes-from "$($sourceIds[0]):ro" `
        --tmpfs '/run/hotshop-agent:rw,noexec,nosuid,nodev,mode=0700' `
        --memory 256m --cpus 1 --publish "127.0.0.1:${AgentPort}:8090" $source.Image | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Temporary Agent startup failed.' }
    $changes = @{
        HOTSHOP_PORTAL_URL=$DemoUrl; HOTSHOP_ADMIN_URL=$DemoUrl
        HOTSHOP_AGENT_URL="http://127.0.0.1:$AgentPort"
        HOTSHOP_DELIVERY_URL="http://127.0.0.1:$WebPort"
        HOTSHOP_RECOVERY_AGENT_CONTAINER=$containerName; HOTSHOP_RECOVERY_RUN_ID=$RunId
    }
    foreach ($name in $changes.Keys) {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
        [Environment]::SetEnvironmentVariable($name,$changes[$name],'Process')
    }
    $vitePath = Join-Path $root 'web/node_modules/vite/bin/vite.js'
    $vite = Start-Process -FilePath (Get-Command node).Source -WorkingDirectory (Join-Path $root 'web') `
        -ArgumentList @(('"' + $vitePath + '"'),'--host','127.0.0.1','--port',"$WebPort",'--strictPort') `
        -RedirectStandardOutput (Join-Path $destination 'vite.log') `
        -RedirectStandardError (Join-Path $destination 'vite-errors.log') -PassThru -WindowStyle Hidden
    foreach ($endpoint in @("http://127.0.0.1:$AgentPort/health/ready", "http://127.0.0.1:$WebPort")) {
        $ready = $false
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds(90)
        do {
            if ($vite.HasExited) { throw 'Recovery web server exited; see the local Vite logs.' }
            $running = docker inspect --format '{{.State.Running}}' $containerName
            if ($running -cne 'true') {
                docker logs $containerName *> (Join-Path $destination 'agent-startup.log')
                throw 'Temporary Agent exited; see the local startup log.'
            }
            try { $ready = (Invoke-WebRequest -Uri $endpoint -TimeoutSec 3).StatusCode -eq 200 } catch { }
            if (-not $ready) { Start-Sleep -Seconds 1 }
        } while (-not $ready -and [DateTimeOffset]::UtcNow -lt $deadline)
        if (-not $ready) { throw "Recovery service did not become ready: $endpoint" }
    }
    Push-Location $root
    try {
        & node web/node_modules/@playwright/test/cli.js test --config web/playwright.recovery.config.ts *> (Join-Path $destination 'browser.log')
        $exitCode = $LASTEXITCODE
    } finally { Pop-Location }
    [ordered]@{ sourceCommit=(& git -C $root rev-parse HEAD); dirty=[bool](& git -C $root status --porcelain); demoProject=$DemoProject; image=$source.Image; modelProvider='fake'; exitCode=$exitCode; backendData='test accounts and orders in the selected demo'; agentState='disposable Redis instance' } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'result.json')
} finally {
    if ($vite -and -not $vite.HasExited) { Stop-Process -Id $vite.Id -Force }
    foreach ($ownedName in @($(if ($owned) { $containerName }), $(if ($redisOwned) { $redisName })) | Where-Object { $_ }) {
        $existing = @(docker ps -aq --filter "name=^${ownedName}$")
        if ($LASTEXITCODE -ne 0) { $exitCode = 1; continue }
        if (-not $existing.Count) { continue }
        $owner = docker inspect --format '{{index .Config.Labels "hotshop.recovery.owner"}}' $ownedName
        if ($owner -ceq $RunId) {
            docker rm -f $ownedName | Out-Null
            if ($LASTEXITCODE -ne 0) { $exitCode = 1 }
        } else { $exitCode = 1 }
    }
    # Literal file deletion stays inside this verified run's workspace directory.
    foreach ($temporaryEnv in @($envFile,$redisEnvFile)) {
        $resolvedEnv = [IO.Path]::GetFullPath($temporaryEnv)
        if (-not $resolvedEnv.StartsWith($destination + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid cleanup path.' }
        if (Test-Path -LiteralPath $resolvedEnv) { Remove-Item -LiteralPath $resolvedEnv }
    }
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
    $remaining = @(docker ps -aq --filter "label=hotshop.recovery.owner=$RunId")
    $cleanupPassed = $LASTEXITCODE -eq 0 -and $remaining.Count -eq 0 -and
        -not (Test-Path -LiteralPath $envFile) -and -not (Test-Path -LiteralPath $redisEnvFile) -and
        (-not $vite -or $vite.HasExited)
    if (-not $cleanupPassed) { $exitCode = 1 }
    [ordered]@{ ownedResourcesZero=$cleanupPassed; exitCode=$exitCode } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'cleanup.json')
}
Write-Host "Recovery evidence: $destination; exit: $exitCode"
exit $exitCode
