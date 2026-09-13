[CmdletBinding()]
param(
    [string]$OutputPath = "",
    [string]$DockerOutputDirectory = "",
    [string]$Image = "",
    [ValidateSet("", "build-failure", "export-failure", "json-failure")]
    [string]$OwnershipTestMode = ""
)

$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$script:Task19Root = $root
. (Join-Path $PSScriptRoot "task19-common.ps1")
if ($OwnershipTestMode -and
        [Environment]::GetEnvironmentVariable("HOTSHOP_CI_OWNERSHIP_TEST", "Process") -cne "1") {
    throw "Agent OpenAPI ownership test mode is restricted to CI regression probes"
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $root "target\openapi\agent.json"
}
$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $root "target"))
if (-not $OutputPath.StartsWith($targetRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Agent OpenAPI output must remain below target/"
}

$explicitImage = -not [string]::IsNullOrWhiteSpace($Image)
if (-not $explicitImage) {
    $Image = "hotshop-agent:task19-openapi-$([Guid]::NewGuid().ToString('N').Substring(0, 12))"
}
$before = Get-Task19ImageIdentity $Image
if (-not $before.Success) { throw "Agent OpenAPI image preflight failed: $($before.Failures -join '; ')" }
if ($before.Exists) {
    throw "Agent OpenAPI refuses to overwrite existing image tag $Image (image ID $($before.Id))"
}

$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force $outputDirectory | Out-Null
if ([string]::IsNullOrWhiteSpace($DockerOutputDirectory)) { $DockerOutputDirectory = $outputDirectory }
$ownedImageId = ""
$ownedExportContainerId = ""
$exportContainerName = "hotshop-agent-openapi-export-$([Guid]::NewGuid().ToString('N').Substring(0, 12))"
$failure = $null
$cleanupFailures = [System.Collections.Generic.List[string]]::new()

try {
    $build = Invoke-Task19Docker -Arguments @(
        "build", "--target", "test", "--tag", $Image,
        "--file", (Join-Path $root "agent\Dockerfile"), (Join-Path $root "agent")
    ) -AllowFailure
    $afterBuild = Get-Task19ImageIdentity $Image
    if (-not $afterBuild.Success) { throw "Agent OpenAPI image identity could not be recorded" }
    if ($afterBuild.Exists) { $ownedImageId = [string]$afterBuild.Id }
    if ($build.ExitCode -ne 0) { throw "Agent OpenAPI image build failed" }
    if (-not $ownedImageId) { throw "Agent OpenAPI build created no inspectable image" }
    if ($OwnershipTestMode -eq "build-failure") { throw "Synthetic Agent OpenAPI build failure" }
    if ($OwnershipTestMode -eq "export-failure") { throw "Synthetic Agent OpenAPI export failure" }

    $containerOutput = "/output/$([System.IO.Path]::GetFileName($OutputPath))"
    $exportBefore = Get-Task19ResourceIdentity "container" $exportContainerName
    if (-not $exportBefore.Success -or $exportBefore.Exists) {
        throw "Agent OpenAPI export container preflight failed"
    }
    $exportCreate = Invoke-Task19Docker -Arguments @(
        "create", "--name", $exportContainerName, "--user", "0:0", "--network", "none",
        "--label", "hotshop.task19.owner=agent-openapi",
        "--mount", "type=bind,source=$DockerOutputDirectory,target=/output",
        "-e", "HOTSHOP_AGENT_OPENAPI_OUTPUT=$containerOutput",
        "--entrypoint", "python", $Image, "-c", @'
import json
import os
from pathlib import Path
from hotshop_agent.api import create_app

os.environ["AGENT_MODEL_PROVIDER"] = "fake"
os.environ["AGENT_EMBEDDING_PROVIDER"] = "deterministic"
document = create_app().openapi()
Path(os.environ["HOTSHOP_AGENT_OPENAPI_OUTPUT"]).write_text(
    json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
    encoding="utf-8",
)
'@
    ) -AllowFailure
    $exportAfter = Get-Task19ResourceIdentity "container" $exportContainerName
    if ($exportAfter.Success -and $exportAfter.Exists) {
        $ownedExportContainerId = [string]$exportAfter.Identity
    }
    if ($exportCreate.ExitCode -ne 0 -or -not $ownedExportContainerId) {
        throw "Agent runtime OpenAPI export container creation failed"
    }
    $exportStart = Invoke-Task19Docker -Arguments @("start", $ownedExportContainerId) -AllowFailure
    if ($exportStart.ExitCode -ne 0) { throw "Agent runtime OpenAPI export start failed" }

    $exportDeadline = (Get-Date).AddSeconds(60)
    $exportTimedOut = $true
    do {
        $exportState = Invoke-Task19Docker -Arguments @(
            "container", "inspect", "--format", "{{.State.Running}}", $ownedExportContainerId
        ) -AllowFailure
        if ($exportState.ExitCode -ne 0) { throw "Agent runtime OpenAPI export state query failed" }
        if ($exportState.Output.Trim() -ceq "false") { $exportTimedOut = $false; break }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $exportDeadline)
    if ($exportTimedOut) { throw "Agent runtime OpenAPI export timed out" }

    $exportExit = Invoke-Task19Docker -Arguments @(
        "container", "inspect", "--format", "{{.State.ExitCode}}", $ownedExportContainerId
    ) -AllowFailure
    $exportLogs = Invoke-Task19Docker -Arguments @("logs", $ownedExportContainerId) -AllowFailure
    if ($exportExit.ExitCode -ne 0 -or $exportExit.Output.Trim() -cne "0") {
        $safeDetail = ($exportLogs.Output -replace 'eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}', '[REDACTED_JWT]')
        throw "Agent runtime OpenAPI export failed: $safeDetail"
    }
    if ($OwnershipTestMode -eq "json-failure") {
        Set-Content -LiteralPath $OutputPath -Value "not-json" -Encoding UTF8
    }
    $null = Get-Content -Raw -LiteralPath $OutputPath -Encoding UTF8 | ConvertFrom-Json
    Write-Output "Generated runtime Agent OpenAPI: $OutputPath"
}
catch { $failure = $_ }
finally {
    if ($ownedExportContainerId) {
        try {
            $currentExport = Get-Task19ResourceIdentity "container" $ownedExportContainerId
            if (-not $currentExport.Success) {
                [void]$cleanupFailures.Add("Agent OpenAPI export container cleanup query failed")
            }
            elseif ($currentExport.Exists -and $currentExport.Identity -ceq $ownedExportContainerId) {
                $removeExport = Invoke-Task19Docker -Arguments @("rm", "--force", $ownedExportContainerId) -AllowFailure
                if ($removeExport.ExitCode -ne 0) {
                    [void]$cleanupFailures.Add("Agent OpenAPI export container cleanup failed")
                }
            }
        }
        catch { [void]$cleanupFailures.Add("Agent OpenAPI export container cleanup exception: $($_.Exception.Message)") }
    }
    if ($ownedImageId) {
        try {
            $current = Get-Task19ImageIdentity $Image
            if (-not $current.Success) {
                [void]$cleanupFailures.Add("Agent OpenAPI image cleanup query failed")
            }
            elseif ($current.Exists -and $current.Id -cne $ownedImageId) {
                [void]$cleanupFailures.Add("Agent OpenAPI refused changed image identity: $Image")
            }
            elseif ($current.Exists) {
                $remove = Invoke-Task19Docker -Arguments @("image", "rm", "--force", $Image) -AllowFailure
                if ($remove.ExitCode -ne 0) { [void]$cleanupFailures.Add("Agent OpenAPI image cleanup failed") }
            }
        }
        catch { [void]$cleanupFailures.Add("Agent OpenAPI image cleanup exception: $($_.Exception.Message)") }
    }
}

if ($cleanupFailures.Count) {
    $message = "Agent OpenAPI cleanup failed: $($cleanupFailures -join '; ')"
    if ($null -ne $failure) { Write-Error $message -ErrorAction Continue } else { throw $message }
}
if ($null -ne $failure) { throw $failure }
