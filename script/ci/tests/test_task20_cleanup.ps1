[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
Set-Location $root
. (Join-Path $root 'script/task20-common.ps1')

$suffix = ([Guid]::NewGuid().ToString('N')).Substring(0,8)
$runId = "run-cleanup-$suffix"
$project = "hotshop-task20-$runId"
$sentinelProject = "task20-other-$suffix"
$sentinelVolume = "task20-other-volume-$suffix"

try {
    & docker volume create --label "com.docker.compose.project=$sentinelProject" $sentinelVolume | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create ownership sentinel volume' }

    $oldNativePreference = $PSNativeCommandUseErrorActionPreference
    $PSNativeCommandUseErrorActionPreference = $false
    & pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile smoke -RunId $runId -SkipBuild -ControlledStartupFailure
    $failureExit = $LASTEXITCODE
    $PSNativeCommandUseErrorActionPreference = $oldNativePreference
    if ($failureExit -eq 0) { throw 'Controlled partial startup did not exit nonzero' }

    $owned = Get-Task20OwnedResources $project
    if ($owned.containers -ne 0 -or $owned.networks -ne 0 -or $owned.volumes -ne 0) {
        throw "Controlled failure leaked run resources: $($owned | ConvertTo-Json -Compress)"
    }
    $sentinel = @(& docker volume ls -q --filter "name=^${sentinelVolume}$")
    if ($sentinel.Count -ne 1) { throw 'Cleanup removed another Docker project resource' }

    $PSNativeCommandUseErrorActionPreference = $false
    & pwsh -NoProfile -File .\script\verify-task20-performance.ps1 -Profile smoke -RunId $runId -SkipBuild -ControlledStartupFailure
    $reuseExit = $LASTEXITCODE
    $PSNativeCommandUseErrorActionPreference = $oldNativePreference
    if ($reuseExit -eq 0) { throw 'Non-empty RunId evidence directory was reused' }
    Write-Host "TASK-20 controlled startup cleanup passed (harness exit=$failureExit, resources=0/0/0, reuse exit=$reuseExit)."
} finally {
    $PSNativeCommandUseErrorActionPreference = $false
    & docker volume rm $sentinelVolume 2>$null | Out-Null
    $PSNativeCommandUseErrorActionPreference = $true
}
