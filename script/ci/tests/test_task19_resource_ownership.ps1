[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
$script:Task19Root = $root
. (Join-Path $root "script\task19-common.ps1")
$verification = Join-Path $root "script\verify-task19-e2e.ps1"
$agentOpenApi = Join-Path $root "script\generate-agent-openapi.ps1"
$probeImage = "alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce"
$runId = [Guid]::NewGuid().ToString("N").Substring(0, 12)
$evidenceRoot = Join-Path $root "target\task19-ownership-regression\$runId"
$testOriginalLocation = (Get-Location).Path
$testCleanupFailures = [System.Collections.Generic.List[string]]::new()
$sentinels = [System.Collections.Generic.List[object]]::new()
$scenarioResults = [ordered]@{}
$environmentResults = [ordered]@{ absent = $false; empty = $false; nonEmpty = $false }
$cwdRestored = $true
$sentinelIdentityVerified = $false
$createdResourcesCleaned = $true
$testFailure = $null
$environmentKeys = @(
    "COMPOSE_PROJECT_NAME", "HOTSHOP_KEY_DIR", "HOTSHOP_SOURCE_ROOT", "MYSQL_ROOT_PASSWORD",
    "MYSQL_PORT", "REDIS_CACHE_PASSWORD", "REDIS_CACHE_PORT", "REDIS_SECKILL_PASSWORD",
    "REDIS_SECKILL_PORT", "RABBITMQ_DEFAULT_PASS", "RABBITMQ_AMQP_PORT",
    "RABBITMQ_MANAGEMENT_PORT", "PORTAL_PORT", "ADMIN_PORT", "TASK_PORT", "AGENT_PORT",
    "QDRANT_PORT", "PORTAL_IMAGE", "ADMIN_IMAGE", "TASK_IMAGE", "AGENT_IMAGE",
    "RABBITMQ_PROJECT_IMAGE", "HOTSHOP_SECURE_COOKIES", "HOTSHOP_MOCK_PAYMENT_ENABLED",
    "HOTSHOP_MOCK_PAYMENT_SECRET", "HOTSHOP_LEGACY_ORDER_TIMEOUT",
    "HOTSHOP_USER_ACCESS_TTL_SECONDS", "HOTSHOP_JWT_CLOCK_SKEW_SECONDS",
    "HOTSHOP_SECURITY_RATE_LIMIT_AGENT_EXCHANGE_LIMIT", "AGENT_TOOL_TIMEOUT_SECONDS",
    "AGENT_MODEL_PROVIDER", "AGENT_EMBEDDING_PROVIDER", "AGENT_RAG_MINIMUM_SCORE",
    "AGENT_QWEN_API_KEY", "AGENT_DEEPSEEK_API_KEY", "AGENT_BAILIAN_EMBEDDING_API_KEY"
)

function Invoke-Docker([string[]]$Arguments, [bool]$Required = $true) {
    $output = @(& docker @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($Required -and $exitCode -ne 0) { throw "docker $($Arguments -join ' ') exited $exitCode" }
    return [pscustomobject]@{ ExitCode = $exitCode; Output = ($output -join "`n") }
}

function Get-EnvironmentState([string]$Name) {
    $variables = [Environment]::GetEnvironmentVariables("Process")
    $exists = $variables.Contains($Name)
    return [ordered]@{
        exists = [bool]$exists
        value = if ($exists) { [string]$variables[$Name] } else { $null }
    }
}

function Set-EnvironmentState([string]$Name, [System.Collections.IDictionary]$State) {
    if ([bool]$State.exists) { Set-Item -LiteralPath "Env:$Name" -Value ([string]$State.value) }
    else { Remove-Item -LiteralPath "Env:$Name" -ErrorAction SilentlyContinue }
}

function Get-EnvironmentSnapshot {
    $snapshot = [ordered]@{}
    foreach ($key in $environmentKeys) { $snapshot[$key] = Get-EnvironmentState $key }
    return $snapshot
}

function Restore-EnvironmentSnapshot([System.Collections.IDictionary]$Snapshot) {
    foreach ($key in $Snapshot.Keys) { Set-EnvironmentState $key $Snapshot[$key] }
}

function Assert-StateRestored([string]$Location, [System.Collections.IDictionary]$Environment) {
    if ((Get-Location).Path -cne $Location) {
        $script:cwdRestored = $false
        throw "TASK-19 verifier did not restore the original cwd"
    }
    foreach ($key in $environmentKeys) {
        $current = Get-EnvironmentState $key
        $expected = $Environment[$key]
        if ([bool]$current.exists -ne [bool]$expected.exists) {
            throw "TASK-19 verifier changed environment existence for $key"
        }
        if ($current.exists -and $current.value -cne $expected.value) {
            throw "TASK-19 verifier did not restore environment value for $key"
        }
    }
}

function Set-EnvironmentFixture([ValidateSet("absent", "empty", "nonEmpty")][string]$StateName) {
    foreach ($key in $environmentKeys) {
        switch ($StateName) {
            "absent" { Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue }
            "empty" { Set-Item -LiteralPath "Env:$key" -Value "" }
            "nonEmpty" { Set-Item -LiteralPath "Env:$key" -Value "sentinel-$runId-$key" }
        }
    }
}

function Get-GitHead {
    $head = (Get-Content -LiteralPath (Join-Path $root ".git\HEAD") -Raw).Trim()
    if ($head.StartsWith("ref: ")) {
        return (Get-Content -LiteralPath (Join-Path $root ".git\$($head.Substring(5))") -Raw).Trim()
    }
    return $head
}

function Get-ImageId([string]$Tag) {
    $result = Invoke-Docker @("image", "inspect", $Tag) $false
    if ($result.ExitCode -ne 0) { return "" }
    return [string](@($result.Output | ConvertFrom-Json)[0].Id)
}

function Get-ObjectId([string]$Type, [string]$Identifier) {
    $result = Invoke-Docker @($Type, "inspect", $Identifier) $false
    if ($result.ExitCode -ne 0) { return "" }
    $object = @($result.Output | ConvertFrom-Json)[0]
    if ($Type -eq "volume") { return "$($object.Name)|$($object.CreatedAt)|$($object.Mountpoint)" }
    return [string]$object.Id
}

function Get-ProjectImageTags([string]$Project) {
    return @(
        "$Project-portal:verify", "$Project-admin:verify", "$Project-task:verify",
        "$Project-agent:verify", "$Project-web:verify", "$Project-rabbitmq:verify",
        "$Project-web-runtime:verify"
    )
}

function Assert-ProjectAbsent([string]$Project) {
    foreach ($query in @(
        @("ps", "-aq", "--filter", "label=com.docker.compose.project=$Project"),
        @("volume", "ls", "-q", "--filter", "label=com.docker.compose.project=$Project"),
        @("network", "ls", "-q", "--filter", "label=com.docker.compose.project=$Project")
    )) {
        $result = Invoke-Docker $query
        if ($result.Output.Trim()) { $script:createdResourcesCleaned = $false; throw "Project resource remained for $Project" }
    }
    foreach ($tag in Get-ProjectImageTags $Project) {
        if (Get-ImageId $tag) { $script:createdResourcesCleaned = $false; throw "Project image remained: $tag" }
    }
}

function Add-Sentinel([string]$Type, [string]$Identifier, [string]$Identity) {
    [void]$sentinels.Add([pscustomobject]@{ Type = $Type; Identifier = $Identifier; Identity = $Identity })
}

function Assert-SentinelsUnchanged {
    foreach ($sentinel in @($sentinels)) {
        $current = if ($sentinel.Type -eq "image") { Get-ImageId $sentinel.Identifier } else {
            Get-ObjectId $sentinel.Type $sentinel.Identifier
        }
        if ($current -cne $sentinel.Identity) { throw "Sentinel identity changed: $($sentinel.Identifier)" }
    }
    $script:sentinelIdentityVerified = $true
}

function Invoke-Rejected([string]$Name, [string]$Project, [string]$Evidence) {
    $location = (Get-Location).Path
    $environment = Get-EnvironmentSnapshot
    $caught = $null
    try { & $verification -ProjectName $Project -EvidenceDirectory $Evidence }
    catch { $caught = $_ }
    Assert-StateRestored $location $environment
    if ($null -eq $caught) { throw "Pre-existing ownership collision unexpectedly passed" }
    $script:scenarioResults[$Name] = [ordered]@{ status = "passed"; rejected = $true }
}

function Invoke-OwnershipMode(
    [string]$Mode,
    [bool]$ExpectSuccess,
    [bool]$ExpectClean,
    [string]$EnvironmentFixture = ""
) {
    $project = "hotshop-task19-$([Guid]::NewGuid().ToString('N').Substring(0, 10))"
    $evidence = Join-Path $evidenceRoot $project
    $callDirectory = Join-Path $evidenceRoot "cwd-$Mode-$([Guid]::NewGuid().ToString('N').Substring(0, 6))"
    New-Item -ItemType Directory -Force $callDirectory | Out-Null
    $callerLocation = (Get-Location).Path
    $callerEnvironment = Get-EnvironmentSnapshot
    $caught = $null
    try {
        if ($EnvironmentFixture) { Set-EnvironmentFixture $EnvironmentFixture }
        $expectedEnvironment = Get-EnvironmentSnapshot
        Set-Location $callDirectory
        $expectedLocation = (Get-Location).Path
        try { & $verification -ProjectName $project -EvidenceDirectory $evidence -OwnershipTestMode $Mode }
        catch { $caught = $_ }
        Assert-StateRestored $expectedLocation $expectedEnvironment
        if ($EnvironmentFixture) { $script:environmentResults[$EnvironmentFixture] = $true }
        if ($ExpectSuccess -and $null -ne $caught) { throw "$Mode unexpectedly failed: $($caught.Exception.Message)" }
        if (-not $ExpectSuccess -and $null -eq $caught) { throw "$Mode unexpectedly passed" }
        $cleanupPath = Join-Path $evidence "cleanup.json"
        if (-not (Test-Path -LiteralPath $cleanupPath -PathType Leaf)) { throw "$Mode produced no cleanup.json" }
        $cleanup = Get-Content -LiteralPath $cleanupPath -Raw | ConvertFrom-Json
        if ([bool]$cleanup.clean -ne $ExpectClean) { throw "$Mode cleanup.clean was not $ExpectClean" }
        if ([bool]$cleanup.verificationPassed -ne $ExpectSuccess) { throw "$Mode cleanup verificationPassed was inaccurate" }
        if (-not $ExpectClean -and @($cleanup.failures).Count -eq 0) {
            throw "$Mode reported clean=false without a structured failure"
        }
        Assert-ProjectAbsent $project
        $script:scenarioResults[$Mode] = [ordered]@{
            status = "passed"; expectedSuccess = $ExpectSuccess; cleanupClean = [bool]$cleanup.clean
        }
    }
    finally {
        Set-Location $callerLocation
        Restore-EnvironmentSnapshot $callerEnvironment
    }
}

function Invoke-AgentOpenApiMode([string]$Mode, [bool]$ExpectSuccess) {
    $label = if ($Mode) { $Mode } else { "success" }
    $tag = "hotshop-agent:task19-openapi-$([Guid]::NewGuid().ToString('N').Substring(0, 10))"
    $directory = Join-Path $evidenceRoot "openapi-$label-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
    New-Item -ItemType Directory -Force $directory | Out-Null
    $output = Join-Path $directory "agent.json"
    $dockerDirectory = Get-Task19DockerHostPath $directory
    $location = (Get-Location).Path
    $environment = Get-EnvironmentSnapshot
    $caught = $null
    Set-Location $directory
    try {
        try {
            & $agentOpenApi -Image $tag -OutputPath $output -DockerOutputDirectory $dockerDirectory `
                -OwnershipTestMode $Mode
        }
        catch { $caught = $_ }
        Assert-StateRestored $directory $environment
        if ($ExpectSuccess -and $null -ne $caught) { throw "Agent OpenAPI $label unexpectedly failed: $($caught.Exception.Message)" }
        if (-not $ExpectSuccess -and $null -eq $caught) { throw "Agent OpenAPI $label unexpectedly passed" }
        if (Get-ImageId $tag) { $script:createdResourcesCleaned = $false; throw "Agent OpenAPI mode $label left image tag $tag" }
        if ($ExpectSuccess) { $null = Get-Content -LiteralPath $output -Raw | ConvertFrom-Json }
        $script:scenarioResults["openapi-$label"] = [ordered]@{ status = "passed"; imageCleaned = $true }
    }
    finally {
        Set-Location $location
        Restore-EnvironmentSnapshot $environment
    }
}

$originalOwnershipFlag = Get-EnvironmentState "HOTSHOP_CI_OWNERSHIP_TEST"
try {
    [void](Invoke-Docker @("image", "pull", $probeImage))
    New-Item -ItemType Directory $evidenceRoot | Out-Null
    Set-Item -LiteralPath "Env:HOTSHOP_CI_OWNERSHIP_TEST" -Value "1"

    $collisionProject = "hotshop-task19-$([Guid]::NewGuid().ToString('N').Substring(0, 10))"
    $container = "$collisionProject-sentinel-container"
    $volume = "$collisionProject-sentinel-volume"
    $network = "$collisionProject-sentinel-network"
    [void](Invoke-Docker @("network", "create", "--label", "com.docker.compose.project=$collisionProject", $network))
    Add-Sentinel "network" $network (Get-ObjectId "network" $network)
    [void](Invoke-Docker @("volume", "create", "--label", "com.docker.compose.project=$collisionProject", $volume))
    Add-Sentinel "volume" $volume (Get-ObjectId "volume" $volume)
    $containerId = (Invoke-Docker @("container", "create", "--label",
        "com.docker.compose.project=$collisionProject", "--name", $container,
        $probeImage, "sleep", "600")).Output.Trim()
    Add-Sentinel "container" $container $containerId
    $collisionTag = (Get-ProjectImageTags $collisionProject)[0]
    [void](Invoke-Docker @("image", "tag", $probeImage, $collisionTag))
    Add-Sentinel "image" $collisionTag (Get-ImageId $collisionTag)
    $collisionEvidence = Join-Path $evidenceRoot "$collisionProject-existing"
    New-Item -ItemType Directory $collisionEvidence | Out-Null
    Set-Content -LiteralPath (Join-Path $collisionEvidence "cleanup.json") -Value '{"sentinel":true}' -Encoding UTF8
    $evidenceBefore = [System.IO.File]::ReadAllBytes((Join-Path $collisionEvidence "cleanup.json"))
    Invoke-Rejected "project-resource-collision" $collisionProject $collisionEvidence
    Assert-SentinelsUnchanged
    $evidenceAfter = [System.IO.File]::ReadAllBytes((Join-Path $collisionEvidence "cleanup.json"))
    if ([Convert]::ToBase64String($evidenceBefore) -cne [Convert]::ToBase64String($evidenceAfter)) {
        throw "Pre-existing evidence was modified"
    }

    $imageProject = "hotshop-task19-$([Guid]::NewGuid().ToString('N').Substring(0, 10))"
    $imageTag = (Get-ProjectImageTags $imageProject)[0]
    [void](Invoke-Docker @("image", "tag", $probeImage, $imageTag))
    $imageId = Get-ImageId $imageTag
    Add-Sentinel "image" $imageTag $imageId
    Invoke-Rejected "image-tag-collision" $imageProject (Join-Path $evidenceRoot "$imageProject-evidence")
    if ((Get-ImageId $imageTag) -cne $imageId) { throw "Conflicting image tag identity changed" }

    $openApiCollisionTag = "hotshop-agent:task19-openapi-sentinel-$runId"
    [void](Invoke-Docker @("image", "tag", $probeImage, $openApiCollisionTag))
    $openApiCollisionId = Get-ImageId $openApiCollisionTag
    Add-Sentinel "image" $openApiCollisionTag $openApiCollisionId
    $openApiCollisionCaught = $null
    try { & $agentOpenApi -Image $openApiCollisionTag -OutputPath (Join-Path $evidenceRoot "collision-agent.json") }
    catch { $openApiCollisionCaught = $_ }
    if ($null -eq $openApiCollisionCaught -or (Get-ImageId $openApiCollisionTag) -cne $openApiCollisionId) {
        throw "Agent OpenAPI did not preserve an explicitly supplied existing image tag"
    }
    $scenarioResults["openapi-image-collision"] = [ordered]@{ status = "passed"; identityPreserved = $true }

    $evidenceProject = "hotshop-task19-$([Guid]::NewGuid().ToString('N').Substring(0, 10))"
    $existingEvidence = Join-Path $evidenceRoot "$evidenceProject-existing"
    New-Item -ItemType Directory $existingEvidence | Out-Null
    Set-Content -LiteralPath (Join-Path $existingEvidence "marker.txt") -Value "sentinel-$runId" -Encoding UTF8
    $markerBefore = Get-Content -LiteralPath (Join-Path $existingEvidence "marker.txt") -Raw
    Invoke-Rejected "evidence-directory-collision" $evidenceProject $existingEvidence
    if ((Get-Content -LiteralPath (Join-Path $existingEvidence "marker.txt") -Raw) -cne $markerBefore) {
        throw "Pre-existing evidence marker changed"
    }

    Invoke-OwnershipMode "success" $true $true "absent"
    Invoke-OwnershipMode "startup-failure" $false $true "empty"
    Invoke-OwnershipMode "playwright-failure" $false $true "nonEmpty"
    Invoke-OwnershipMode "query-failure" $false $false
    Invoke-OwnershipMode "interrupt" $false $true
    Invoke-AgentOpenApiMode "" $true
    Invoke-AgentOpenApiMode "build-failure" $false
    Invoke-AgentOpenApiMode "export-failure" $false
    Invoke-AgentOpenApiMode "json-failure" $false
    Assert-SentinelsUnchanged
}
catch { $testFailure = $_ }
finally {
    try { Set-EnvironmentState "HOTSHOP_CI_OWNERSHIP_TEST" $originalOwnershipFlag }
    catch { [void]$testCleanupFailures.Add("Ownership flag restore failed: $($_.Exception.Message)") }
    try { Set-Location $testOriginalLocation }
    catch { $script:cwdRestored = $false; [void]$testCleanupFailures.Add("Test cwd restore failed: $($_.Exception.Message)") }

    $sentinelArray = @($sentinels)
    [array]::Reverse($sentinelArray)
    foreach ($sentinel in $sentinelArray) {
        try {
            $current = if ($sentinel.Type -eq "image") { Get-ImageId $sentinel.Identifier } else {
                Get-ObjectId $sentinel.Type $sentinel.Identifier
            }
            if (-not $current) { continue }
            if ($current -cne $sentinel.Identity) {
                [void]$testCleanupFailures.Add("Refused changed test sentinel identity: $($sentinel.Identifier)")
                continue
            }
            $arguments = switch ($sentinel.Type) {
                "container" { @("container", "rm", "--force", $sentinel.Identifier) }
                "volume" { @("volume", "rm", "--force", $sentinel.Identifier) }
                "network" { @("network", "rm", $sentinel.Identifier) }
                "image" { @("image", "rm", "--force", $sentinel.Identifier) }
            }
            $removed = Invoke-Docker $arguments $false
            if ($removed.ExitCode -ne 0) { [void]$testCleanupFailures.Add("Could not remove test sentinel $($sentinel.Identifier)") }
        }
        catch { [void]$testCleanupFailures.Add("Test sentinel cleanup exception: $($_.Exception.Message)") }
    }

    $passed = ($null -eq $testFailure -and $testCleanupFailures.Count -eq 0 -and
        $environmentResults.absent -and $environmentResults.empty -and $environmentResults.nonEmpty -and
        $cwdRestored -and $sentinelIdentityVerified -and $createdResourcesCleaned)
    $summary = [ordered]@{
        schemaVersion = "1.0"
        runId = $runId
        gitHead = Get-GitHead
        scenarios = $scenarioResults
        environmentRestoration = $environmentResults
        cwdRestored = $cwdRestored
        sentinelIdentityVerified = $sentinelIdentityVerified
        currentRunResourcesCleaned = $createdResourcesCleaned
        cleanupFailures = @($testCleanupFailures)
        failure = if ($null -ne $testFailure) { $testFailure.Exception.Message } else { $null }
        passed = $passed
    }
    try {
        New-Item -ItemType Directory -Force $evidenceRoot | Out-Null
        $writeResult = Write-Task19JsonResult (Join-Path $evidenceRoot "summary.json") $summary
        if (-not $writeResult.Success) { [void]$testCleanupFailures.Add($writeResult.Failures -join "; ") }
    }
    catch { [void]$testCleanupFailures.Add("Could not retain ownership summary: $($_.Exception.Message)") }
}

if ($null -ne $testFailure) { throw $testFailure }
if ($testCleanupFailures.Count) { throw "TASK-19 ownership test cleanup failed: $($testCleanupFailures -join '; ')" }
Write-Host "TASK-19 ownership regression passed; summary=$(Join-Path $evidenceRoot 'summary.json')"
