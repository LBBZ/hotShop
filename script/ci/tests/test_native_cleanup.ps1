[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "..\native_cleanup.ps1")

$currentPowerShell = (Get-Process -Id $PID).Path
if ([string]::IsNullOrWhiteSpace($currentPowerShell)) {
    $currentPowerShell = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
}
if (-not (Test-Path -LiteralPath $currentPowerShell -PathType Leaf)) {
    throw "Cannot resolve the current PowerShell executable"
}

$probePath = Join-Path ([System.IO.Path]::GetTempPath()) (
    "hotshop-native-cleanup-probe-{0}.ps1" -f ([Guid]::NewGuid().ToString("N"))
)
$failureAuditDirectory = Join-Path ([System.IO.Path]::GetTempPath()) (
    "hotshop-native-cleanup-audit-directory-{0}" -f ([Guid]::NewGuid().ToString("N"))
)
$probeSource = @'
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Mode,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$ProbeArguments
)

switch ($Mode) {
    "stderr-success" {
        [Console]::Error.WriteLine("normal native stderr")
        exit 0
    }
    "nonzero" {
        exit 23
    }
    "continued" {
        [Console]::Out.WriteLine("continued")
        exit 0
    }
    "arguments" {
        [Console]::Out.Write(($ProbeArguments | ConvertTo-Json -Compress))
        exit 0
    }
    default {
        [Console]::Error.WriteLine("unknown probe mode")
        exit 64
    }
}
'@

try {
    Set-Content -LiteralPath $probePath -Value $probeSource -Encoding UTF8
    if (-not (Test-Path -LiteralPath $probePath -PathType Leaf)) {
        throw "Native cleanup probe file was not created"
    }

    $failures = [System.Collections.Generic.List[string]]::new()
    $success = Invoke-HotShopCleanupNativeStep -Failures $failures `
        -Step "stderr success probe" -FilePath $currentPowerShell `
        -Arguments @("-NoLogo", "-NoProfile", "-File", $probePath, "stderr-success")
    if ($success.ExitCode -ne 0 -or $failures.Count -ne 0) {
        throw "Native stderr with exit code 0 was incorrectly treated as failure"
    }
    if ($success.Stderr -notmatch "normal native stderr") {
        throw "Native stderr was not captured by the cleanup wrapper"
    }

    $failed = Invoke-HotShopCleanupNativeStep -Failures $failures `
        -Step "nonzero probe" -FilePath $currentPowerShell `
        -Arguments @("-NoLogo", "-NoProfile", "-File", $probePath, "nonzero")
    if ($failed.ExitCode -ne 23 -or $failures.Count -ne 1 -or
            $failures[0] -notmatch "exit code 23") {
        throw "Nonzero native exit code was not recorded accurately"
    }

    $continued = Invoke-HotShopCleanupNativeStep -Failures $failures `
        -Step "continuation probe" -FilePath $currentPowerShell `
        -Arguments @("-NoLogo", "-NoProfile", "-File", $probePath, "continued")
    if ($continued.ExitCode -ne 0 -or $continued.Stdout -notmatch "continued") {
        throw "A previous cleanup failure prevented a later cleanup step"
    }
    if ($failures.Count -ne 1) {
        throw "The successful continuation step changed the cleanup failure summary"
    }

    $expectedArguments = @(
        "plain",
        "contains spaces",
        "label=com.docker.compose.project=hotshop-native-probe",
        "C:\path with spaces\probe.json"
    )
    $argumentResult = Invoke-HotShopCleanupNativeStep -Failures $failures `
        -Step "native argument fidelity probe" -FilePath $currentPowerShell `
        -Arguments (@("-NoLogo", "-NoProfile", "-File", $probePath, "arguments") +
            $expectedArguments)
    if ($argumentResult.ExitCode -ne 0) {
        throw "Native argument fidelity probe failed"
    }
    $parsedArguments = ConvertFrom-Json -InputObject $argumentResult.Stdout
    $actualArguments = [System.Collections.Generic.List[string]]::new()
    foreach ($argument in $parsedArguments) {
        [void]$actualArguments.Add([string]$argument)
    }
    if ($actualArguments.Count -ne $expectedArguments.Count) {
        throw "Native argument count changed across the process boundary"
    }
    for ($index = 0; $index -lt $expectedArguments.Count; $index++) {
        if (-not [string]::Equals(
                [string]$actualArguments[$index], [string]$expectedArguments[$index],
                [StringComparison]::Ordinal
            )) {
            throw "Native argument $index changed across the process boundary"
        }
    }

    New-Item -ItemType Directory -Path $failureAuditDirectory | Out-Null
    [Environment]::SetEnvironmentVariable(
        "HOTSHOP_NATIVE_COMMAND_AUDIT_PATH", $failureAuditDirectory,
        [EnvironmentVariableTarget]::Process
    )
    $failureCountBeforeAudit = $failures.Count
    $auditFailure = Invoke-HotShopCleanupNativeStep -Failures $failures `
        -Step "audit write exception probe" -FilePath $currentPowerShell `
        -Arguments @("-NoLogo", "-NoProfile", "-File", $probePath, "continued")
    if ($auditFailure.ExitCode -ne -1 -or
            $failures.Count -ne ($failureCountBeforeAudit + 1)) {
        throw "Audit write exception was not isolated as a cleanup failure"
    }
    [Environment]::SetEnvironmentVariable(
        "HOTSHOP_NATIVE_COMMAND_AUDIT_PATH", $null,
        [EnvironmentVariableTarget]::Process
    )
    $postException = Invoke-HotShopCleanupNativeStep -Failures $failures `
        -Step "post-exception continuation probe" -FilePath $currentPowerShell `
        -Arguments @("-NoLogo", "-NoProfile", "-File", $probePath, "continued")
    if ($postException.ExitCode -ne 0 -or $postException.Stdout -notmatch "continued") {
        throw "A wrapper exception prevented a later cleanup step"
    }

    Write-Host (
        "Native cleanup probes passed: stderr+exit0, nonzero exit, argument fidelity, " +
        "exception isolation, continuation after failure"
    )
}
finally {
    [Environment]::SetEnvironmentVariable(
        "HOTSHOP_NATIVE_COMMAND_AUDIT_PATH", $null,
        [EnvironmentVariableTarget]::Process
    )
    foreach ($temporaryPath in @($probePath, $failureAuditDirectory)) {
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Recurse -Force
        }
    }
}
