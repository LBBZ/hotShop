[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 10)
$project = "hotshop-task17-$suffix"
$runtimeImage = "hotshop-agent:task17-$suffix"
$testImage = "hotshop-agent:task17-test-$suffix"
$fixture = "$project-portal-fixture"
$evidence = Join-Path $root "target\task17-compose-evidence"
$temporary = Join-Path $root "target\task17-compose-temp-$suffix"
$keys = Join-Path $temporary "keys"
$agentPort = Get-Random -Minimum 18090 -Maximum 18990
$qdrantPort = Get-Random -Minimum 16333 -Maximum 16990
$redisPort = Get-Random -Minimum 17379 -Maximum 17990

New-Item -ItemType Directory -Path $evidence -Force | Out-Null
New-Item -ItemType Directory -Path $keys -Force | Out-Null

$oldValues = @{}
foreach ($name in @("HOTSHOP_KEY_DIR", "AGENT_IMAGE", "AGENT_PORT", "QDRANT_PORT", "REDIS_CACHE_PORT")) {
    $oldValues[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & docker compose -p $project --env-file (Join-Path $root ".env.example") @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
}

function Invoke-IndexCommand {
    param([Parameter(Mandatory = $true)][string]$Command)
    $output = & docker run --rm --network "${project}_hotShop-network" `
        -e PYTHONDEVMODE=1 `
        -e AGENT_QDRANT_URL=http://qdrant:6333 `
        --entrypoint python $testImage -m hotshop_agent.index_cli $Command
    if ($LASTEXITCODE -ne 0) { throw "Index command failed: $Command" }
    return $output
}

function Invoke-AgentJourney {
    param([string]$Question)
    $headers = @{ Authorization = "Bearer $script:userToken"; "Content-Type" = "application/json" }
    $session = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$agentPort/api/v1/agent/sessions" -Headers $headers -Body "{}"
    $messageBody = @{ content = $Question } | ConvertTo-Json -Compress
    $message = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$agentPort/api/v1/agent/sessions/$($session.id)/messages" -Headers $headers -Body $messageBody
    $runBody = @{ messageId = $message.id } | ConvertTo-Json -Compress
    $run = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$agentPort/api/v1/agent/sessions/$($session.id)/runs" -Headers $headers -Body $runBody
    return (Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$agentPort/api/v1/agent/runs/$($run.id)/events" -Headers @{ Authorization = "Bearer $script:userToken" }).Content
}

try {
    Set-Location $root
    docker build --target test -t $testImage -f agent/Dockerfile agent
    if ($LASTEXITCODE -ne 0) { throw "Agent test image build failed" }
    docker build -t $runtimeImage -f agent/Dockerfile agent
    if ($LASTEXITCODE -ne 0) { throw "Agent runtime image build failed" }

    docker run --rm --user 0:0 -e PYTHONDEVMODE=1 --entrypoint python -v "${keys}:/out" $testImage tests/compose_support.py generate-keys --directory /out
    if ($LASTEXITCODE -ne 0) { throw "Key generation failed" }

    $env:HOTSHOP_KEY_DIR = $keys
    $env:AGENT_IMAGE = $runtimeImage
    $env:AGENT_PORT = "$agentPort"
    $env:QDRANT_PORT = "$qdrantPort"
    $env:REDIS_CACHE_PORT = "$redisPort"
    Invoke-Compose --profile agent up -d --wait redis-cache qdrant

    docker run -d --name $fixture --network "${project}_hotShop-network" --network-alias portal-service --network-alias admin-service --user 10001:10001 -e PYTHONDEVMODE=1 --entrypoint python -v "${keys}:/keys:ro" $testImage tests/compose_support.py serve --directory /keys | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Backend fixture failed to start" }
    Invoke-Compose --profile agent up -d --wait agent-service

    $script:userToken = docker run --rm --user 10001:10001 -e PYTHONDEVMODE=1 --entrypoint python -v "${keys}:/keys:ro" $testImage tests/compose_support.py issue-user --directory /keys
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($script:userToken)) { throw "User token issue failed" }

    $validate = Invoke-IndexCommand validate
    $rebuildOne = Invoke-IndexCommand rebuild
    $rebuildTwo = Invoke-IndexCommand rebuild
    if ($rebuildOne -ne $rebuildTwo) { throw "Rebuild is not idempotent" }
    $static = Invoke-AgentJourney "How does the after-sales return policy work?"
    if ($static -notmatch 'rag.completed' -or $static -notmatch 'after-sales-general') { throw "Static RAG journey failed" }

    $beforeRestart = Invoke-IndexCommand status
    Invoke-Compose --profile agent restart qdrant
    Invoke-Compose --profile agent up -d --wait qdrant
    $afterRestart = Invoke-IndexCommand status
    if ($beforeRestart -ne $afterRestart) { throw "Qdrant persistence check failed" }

    Invoke-Compose --profile agent stop qdrant
    $dynamic = Invoke-AgentJourney "What is the current price and inventory for product 101?"
    if ($dynamic -notmatch 'tool.completed' -or $dynamic -notmatch 'get_product' -or $dynamic -notmatch '88.00') { throw "Dynamic outage journey failed" }
    $degraded = Invoke-AgentJourney "How does the after-sales return policy work?"
    if ($degraded -notmatch 'unavailable') { throw "Static outage degradation failed" }

    @{
        schemaVersion = "1.0"
        project = $project
        validate = ($validate | ConvertFrom-Json)
        rebuild = ($rebuildOne | ConvertFrom-Json)
        persistence = (($beforeRestart | ConvertFrom-Json).collection -eq ($afterRestart | ConvertFrom-Json).collection)
        staticCitation = ($static -match 'after-sales-general')
        dynamicDuringOutage = ($dynamic -match '88.00')
        staticDegradedDuringOutage = ($degraded -match 'unavailable')
        passed = $true
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $evidence "result.json")
    Write-Output "TASK-17 Compose verification passed; evidence: $evidence"
}
finally {
    docker rm -f $fixture 2>$null | Out-Null
    $cleanupErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & docker compose -p $project --env-file (Join-Path $root ".env.example") --profile agent down -v --remove-orphans 2>&1 | Out-Null
    $ErrorActionPreference = $cleanupErrorPreference
    foreach ($name in $oldValues.Keys) {
        [Environment]::SetEnvironmentVariable($name, $oldValues[$name], "Process")
    }
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Recurse -Force }
    Set-Location $root
}
