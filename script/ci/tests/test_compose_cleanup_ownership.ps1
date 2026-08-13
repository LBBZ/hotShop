[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
. (Join-Path $repositoryRoot "script\ci\native_cleanup.ps1")

$currentPowerShell = (Get-Process -Id $PID).Path
if ([string]::IsNullOrWhiteSpace($currentPowerShell)) {
    $currentPowerShell = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
}
if (-not (Test-Path -LiteralPath $currentPowerShell -PathType Leaf)) {
    throw "Cannot resolve the current PowerShell executable"
}

$verificationScript = Join-Path $repositoryRoot "script\verify-task16-compose.ps1"
$verificationSource = [System.IO.File]::ReadAllText($verificationScript)
$finalCleanupMarker = [regex]::Match(
    $verificationSource, '(?m)^finally \{\r?\n    \$cleanupFailures'
)
if (-not $finalCleanupMarker.Success) {
    throw "Cannot locate the TASK-16 final cleanup block"
}
$finalCleanupSource = $verificationSource.Substring($finalCleanupMarker.Index)
if ($finalCleanupSource -match '\bInvoke-HotShopNativeCommand\b') {
    throw "TASK-16 final cleanup must not call the throwing native wrapper directly"
}
if ($finalCleanupSource -match '(?s)\binspect\b.*?--format') {
    throw "TASK-16 final cleanup must read docker inspect JSON without --format"
}
$probeImage = "alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce"
$runId = ([Guid]::NewGuid().ToString("N")).Substring(0, 10)
$sentinelProject = "hotshop-owner-conflict-$runId"
$cleanProject = "hotshop-owner-clean-$runId"
$failureProject = "hotshop-owner-failure-$runId"
$sentinelContainerName = "$sentinelProject-sentinel"
$sentinelVolumeName = "$sentinelProject-sentinel"
$sentinelNetworkName = "$sentinelProject-sentinel"
$sentinelImages = @(
    "$sentinelProject-portal:verify",
    "$sentinelProject-admin:verify",
    "$sentinelProject-agent:verify"
)
$sentinelContainer = ""
$sentinelNetwork = ""
$sentinelVolumeCreated = $false
$createdSentinelImages = [System.Collections.Generic.List[string]]::new()
$evidenceRoot = Join-Path ([System.IO.Path]::GetTempPath()) "hotshop-ownership-evidence-$runId"
$auditPath = Join-Path ([System.IO.Path]::GetTempPath()) "hotshop-native-audit-$runId.jsonl"
$testCleanupFailures = [System.Collections.Generic.List[string]]::new()

function Invoke-RequiredDocker([string]$Step, [string[]]$Arguments) {
    $result = Invoke-HotShopNativeCommand -FilePath "docker" -Arguments $Arguments
    if ($result.ExitCode -ne 0) {
        throw "$Step failed with exit code $($result.ExitCode): $($result.Stderr.Trim())"
    }
    return $result
}

function Get-DockerInspectObject([string]$Step, [string]$ResourceType, [string]$Identifier) {
    $inspection = Invoke-RequiredDocker $Step @($ResourceType, "inspect", $Identifier)
    try {
        $objects = @($inspection.Stdout | ConvertFrom-Json)
    }
    catch {
        throw "$Step returned invalid Docker inspect JSON: $($_.Exception.Message)"
    }
    if ($objects.Count -ne 1) {
        throw "$Step returned $($objects.Count) objects instead of exactly one"
    }
    return $objects[0]
}

function Get-ExactProjectResources([string]$ProjectName) {
    $containers = Invoke-RequiredDocker "Query project containers" @(
        "ps", "-aq", "--filter", "label=com.docker.compose.project=$ProjectName"
    )
    $volumes = Invoke-RequiredDocker "Query project volumes" @(
        "volume", "ls", "-q", "--filter", "label=com.docker.compose.project=$ProjectName"
    )
    $networks = Invoke-RequiredDocker "Query project networks" @(
        "network", "ls", "-q", "--filter", "label=com.docker.compose.project=$ProjectName"
    )
    return [ordered]@{
        containers = @(Get-HotShopNativeOutputLines $containers.Stdout)
        volumes = @(Get-HotShopNativeOutputLines $volumes.Stdout)
        networks = @(Get-HotShopNativeOutputLines $networks.Stdout)
    }
}

function Get-ImageId([string]$Image) {
    return [string](Get-DockerInspectObject "Inspect image $Image" "image" $Image).Id
}

function Get-VolumeFingerprint([string]$Volume) {
    $inspection = Invoke-RequiredDocker "Inspect volume $Volume" @(
        "volume", "inspect", $Volume
    )
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($inspection.Stdout)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace("-", "")
    }
    finally {
        $sha256.Dispose()
    }
}

function Assert-NoProjectResources([string]$ProjectName) {
    $resources = Get-ExactProjectResources $ProjectName
    if ($resources.containers.Count -ne 0 -or
            $resources.volumes.Count -ne 0 -or
            $resources.networks.Count -ne 0) {
        throw "Project resources remain for $ProjectName"
    }
}

function Assert-ImagesAbsent([string[]]$Images) {
    foreach ($image in $Images) {
        $inspection = Invoke-HotShopNativeCommand -FilePath "docker" -Arguments @(
            "image", "inspect", $image
        )
        if ($inspection.ExitCode -eq 0) {
            throw "Owned image remains after cleanup: $image"
        }
        if ($inspection.ExitCode -ne 1) {
            throw "Could not verify image absence for $image`: exit code $($inspection.ExitCode)"
        }
    }
}

function Invoke-Verification([string]$ProjectName, [string]$Mode) {
    $evidencePath = Join-Path $evidenceRoot $ProjectName
    $arguments = @(
        "-NoLogo", "-NoProfile", "-File", $verificationScript,
        "-ProjectName", $ProjectName,
        "-EvidenceDirectory", $evidencePath
    )
    if (-not [string]::IsNullOrWhiteSpace($Mode)) {
        $arguments += @("-OwnershipTestMode", $Mode)
    }
    return Invoke-HotShopNativeCommand -FilePath $currentPowerShell -Arguments $arguments
}

try {
    [void](Invoke-RequiredDocker "Pull fixed ownership probe image" @(
        "image", "pull", $probeImage
    ))

    $sentinelNetwork = (Invoke-RequiredDocker "Create sentinel network" @(
        "network", "create", "--label",
        "com.docker.compose.project=$sentinelProject", $sentinelNetworkName
    )).Stdout.Trim()
    [void](Invoke-RequiredDocker "Create sentinel volume" @(
        "volume", "create", "--label",
        "com.docker.compose.project=$sentinelProject", $sentinelVolumeName
    ))
    $sentinelVolumeCreated = $true
    $sentinelContainer = (Invoke-RequiredDocker "Create sentinel container" @(
        "container", "create", "--label",
        "com.docker.compose.project=$sentinelProject",
        "--name", $sentinelContainerName, $probeImage, "sleep", "600"
    )).Stdout.Trim()
    $sentinelImageIds = [ordered]@{}
    foreach ($image in $sentinelImages) {
        [void](Invoke-RequiredDocker "Create sentinel image tag $image" @(
            "image", "tag", $probeImage, $image
        ))
        [void]$createdSentinelImages.Add($image)
        $sentinelImageIds[$image] = Get-ImageId $image
    }
    $sentinelVolumeFingerprint = Get-VolumeFingerprint $sentinelVolumeName

    [Environment]::SetEnvironmentVariable(
        "HOTSHOP_NATIVE_COMMAND_AUDIT_PATH", $auditPath,
        [EnvironmentVariableTarget]::Process
    )
    $conflictResult = Invoke-Verification $sentinelProject ""
    if ($conflictResult.ExitCode -eq 0 -or
            ($conflictResult.Stdout + $conflictResult.Stderr) -notmatch "Resource ownership conflict") {
        throw "Ownership conflict was not rejected before startup"
    }

    $sentinelResources = Get-ExactProjectResources $sentinelProject
    $currentContainerId = [string](
        Get-DockerInspectObject "Reinspect sentinel container" "container" $sentinelContainerName
    ).Id
    if ($sentinelResources.containers.Count -ne 1 -or
            $currentContainerId -cne $sentinelContainer) {
        throw "Sentinel container identity changed after rejected startup"
    }
    if ($sentinelResources.volumes.Count -ne 1 -or
            $sentinelResources.volumes[0] -cne $sentinelVolumeName -or
            (Get-VolumeFingerprint $sentinelVolumeName) -cne $sentinelVolumeFingerprint) {
        throw "Sentinel volume identity or metadata changed after rejected startup"
    }
    $currentNetworkId = [string](
        Get-DockerInspectObject "Reinspect sentinel network" "network" $sentinelNetworkName
    ).Id
    if ($sentinelResources.networks.Count -ne 1 -or
            $currentNetworkId -cne $sentinelNetwork) {
        throw "Sentinel network identity changed after rejected startup"
    }
    foreach ($image in $sentinelImages) {
        if ((Get-ImageId $image) -cne $sentinelImageIds[$image]) {
            throw "Sentinel image identity changed after rejected startup: $image"
        }
    }

    $auditRecords = @(
        Get-Content -LiteralPath $auditPath | ForEach-Object { $_ | ConvertFrom-Json }
    )
    $conflictInvocations = @(
        $auditRecords | Where-Object {
            @($_.arguments) -contains $verificationScript
        }
    )
    if ($conflictInvocations.Count -ne 1 -or
            @($conflictInvocations[0].arguments) -contains "-OwnershipTestMode") {
        throw "Empty ownership mode was not omitted from the native argument list"
    }
    $destructiveAudit = @(
        $auditRecords |
            Where-Object {
                $command = "$($_.filePath) $($_.arguments -join ' ')"
                $command -match "(?:^|[\\/ ])docker(?:\.exe)?\s+compose\b.*\bdown\b" -or
                $command -match "(?:^|[\\/ ])docker(?:\.exe)?\s+(?:container|volume|network|image)\s+rm\b"
            }
    )
    if ($destructiveAudit.Count -ne 0) {
        throw "Rejected preflight executed a destructive Docker command"
    }

    [Environment]::SetEnvironmentVariable(
        "HOTSHOP_NATIVE_COMMAND_AUDIT_PATH", $null,
        [EnvironmentVariableTarget]::Process
    )
    [Environment]::SetEnvironmentVariable(
        "HOTSHOP_CI_OWNERSHIP_TEST", "1",
        [EnvironmentVariableTarget]::Process
    )
    $cleanResult = Invoke-Verification $cleanProject "preflight-only"
    if ($cleanResult.ExitCode -ne 0 -or
            ($cleanResult.Stdout + $cleanResult.Stderr) -notmatch "Resource ownership preflight passed") {
        throw "Conflict-free ownership preflight did not pass"
    }
    Assert-NoProjectResources $cleanProject
    Assert-ImagesAbsent @(
        "$cleanProject-portal:verify",
        "$cleanProject-admin:verify",
        "$cleanProject-agent:verify"
    )

    $keyDirectoriesBefore = @(
        Get-ChildItem -LiteralPath ([System.IO.Path]::GetTempPath()) -Directory |
            Where-Object { $_.Name -like "hotshop-task16-keys-*" } |
            ForEach-Object { $_.FullName }
    )
    [Environment]::SetEnvironmentVariable(
        "HOTSHOP_CI_INJECT_CLEANUP_PARSE_EXCEPTION", "1",
        [EnvironmentVariableTarget]::Process
    )
    $failureResult = Invoke-Verification $failureProject "fail-after-resource-startup"
    [Environment]::SetEnvironmentVariable(
        "HOTSHOP_CI_INJECT_CLEANUP_PARSE_EXCEPTION", $null,
        [EnvironmentVariableTarget]::Process
    )
    $failureOutput = $failureResult.Stdout + $failureResult.Stderr
    if ($failureResult.ExitCode -eq 0 -or
            $failureOutput -notmatch "Synthetic business startup failure" -or
            $failureOutput -notmatch "Cleanup verification failed") {
        throw "Synthetic post-startup failure did not preserve the business failure"
    }
    Assert-NoProjectResources $failureProject
    Assert-ImagesAbsent @(
        "$failureProject-portal:verify",
        "$failureProject-admin:verify",
        "$failureProject-agent:verify"
    )
    $keyDirectoriesAfter = @(
        Get-ChildItem -LiteralPath ([System.IO.Path]::GetTempPath()) -Directory |
            Where-Object { $_.Name -like "hotshop-task16-keys-*" } |
            ForEach-Object { $_.FullName }
    )
    if (@(Compare-Object $keyDirectoriesBefore $keyDirectoriesAfter).Count -ne 0) {
        throw "Synthetic post-startup failure left a temporary key directory"
    }

    Write-Host (
        "Compose ownership probes passed: conflict preserved container/network/image IDs " +
        "and volume fingerprint; no-conflict passed; post-startup failure cleaned only invocation resources"
    )
}
finally {
    [Environment]::SetEnvironmentVariable(
        "HOTSHOP_NATIVE_COMMAND_AUDIT_PATH", $null,
        [EnvironmentVariableTarget]::Process
    )
    [Environment]::SetEnvironmentVariable(
        "HOTSHOP_CI_OWNERSHIP_TEST", $null,
        [EnvironmentVariableTarget]::Process
    )
    [Environment]::SetEnvironmentVariable(
        "HOTSHOP_CI_INJECT_CLEANUP_PARSE_EXCEPTION", $null,
        [EnvironmentVariableTarget]::Process
    )

    if (-not [string]::IsNullOrWhiteSpace($sentinelContainer)) {
        [void](Invoke-HotShopCleanupNativeStep -Failures $testCleanupFailures `
            -Step "Remove test sentinel container" -FilePath "docker" `
            -Arguments @("container", "rm", "--force", $sentinelContainer))
    }
    if ($sentinelVolumeCreated) {
        [void](Invoke-HotShopCleanupNativeStep -Failures $testCleanupFailures `
            -Step "Remove test sentinel volume" -FilePath "docker" `
            -Arguments @("volume", "rm", "--force", $sentinelVolumeName))
    }
    if (-not [string]::IsNullOrWhiteSpace($sentinelNetwork)) {
        [void](Invoke-HotShopCleanupNativeStep -Failures $testCleanupFailures `
            -Step "Remove test sentinel network" -FilePath "docker" `
            -Arguments @("network", "rm", $sentinelNetwork))
    }
    foreach ($image in $createdSentinelImages) {
        $inspection = Invoke-HotShopNativeCommand -FilePath "docker" -Arguments @(
            "image", "inspect", $image
        )
        if ($inspection.ExitCode -eq 0) {
            [void](Invoke-HotShopCleanupNativeStep -Failures $testCleanupFailures `
                -Step "Remove test sentinel image $image" -FilePath "docker" `
                -Arguments @("image", "rm", "--force", $image))
        }
        elseif ($inspection.ExitCode -ne 1) {
            [void]$testCleanupFailures.Add(
                "Inspect test sentinel image $image failed with exit code $($inspection.ExitCode)"
            )
        }
    }
    foreach ($testPath in @($auditPath, $evidenceRoot)) {
        try {
            if (Test-Path -LiteralPath $testPath) {
                Remove-Item -LiteralPath $testPath -Recurse -Force
            }
        }
        catch {
            [void]$testCleanupFailures.Add(
                "Remove test path $testPath failed: $($_.Exception.Message)"
            )
        }
    }
    if ($testCleanupFailures.Count -gt 0) {
        throw "Ownership probe cleanup failed: $($testCleanupFailures -join '; ')"
    }
}
