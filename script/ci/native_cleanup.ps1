function Invoke-HotShopNativeCommand {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @()
    )

    $stdoutPath = [System.IO.Path]::GetTempFileName()
    $stderrPath = [System.IO.Path]::GetTempFileName()
    $exitCode = -1
    $invocationError = $null
    try {
        $auditPath = [Environment]::GetEnvironmentVariable(
            "HOTSHOP_NATIVE_COMMAND_AUDIT_PATH",
            [EnvironmentVariableTarget]::Process
        )
        if (-not [string]::IsNullOrWhiteSpace($auditPath)) {
            $auditRecord = [ordered]@{
                filePath = $FilePath
                arguments = @($Arguments)
            } | ConvertTo-Json -Compress
            Add-Content -LiteralPath $auditPath -Value $auditRecord -Encoding UTF8
        }
        # Windows PowerShell turns native stderr into ErrorRecord instances. Redirect both
        # streams to files and temporarily disable native-command error promotion so the
        # process exit code, not harmless stderr text, determines success.
        $previousErrorActionPreference = $ErrorActionPreference
        $nativePreferenceExists = Test-Path Variable:PSNativeCommandUseErrorActionPreference
        if ($nativePreferenceExists) {
            $previousNativePreference = $PSNativeCommandUseErrorActionPreference
        }
        try {
            $ErrorActionPreference = "Continue"
            if ($nativePreferenceExists) {
                $PSNativeCommandUseErrorActionPreference = $false
            }
            & $FilePath @Arguments 1> $stdoutPath 2> $stderrPath
            $exitCode = $LASTEXITCODE
        }
        catch {
            $invocationError = $_.Exception.Message
            $exitCode = -1
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
            if ($nativePreferenceExists) {
                $PSNativeCommandUseErrorActionPreference = $previousNativePreference
            }
        }

        return [pscustomobject]@{
            ExitCode = [int]$exitCode
            Stdout = [System.IO.File]::ReadAllText($stdoutPath)
            Stderr = [System.IO.File]::ReadAllText($stderrPath)
            InvocationError = $invocationError
        }
    }
    finally {
        foreach ($temporaryPath in @($stdoutPath, $stderrPath)) {
            if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
                Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

function Invoke-HotShopCleanupNativeStep {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()]
        [System.Collections.Generic.List[string]]$Failures,
        [Parameter(Mandatory = $true)][string]$Step,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [int[]]$AcceptedExitCodes = @(0)
    )

    try {
        $result = Invoke-HotShopNativeCommand -FilePath $FilePath -Arguments $Arguments
        if ($AcceptedExitCodes -notcontains $result.ExitCode) {
            $detail = if ([string]::IsNullOrWhiteSpace($result.InvocationError)) {
                "exit code $($result.ExitCode)"
            }
            else {
                "invocation error: $($result.InvocationError)"
            }
            [void]$Failures.Add("$Step failed with $detail")
        }
        return $result
    }
    catch {
        [void]$Failures.Add("$Step failed before an exit code was available: $($_.Exception.Message)")
        return [pscustomobject]@{
            ExitCode = -1
            Stdout = ""
            Stderr = ""
            InvocationError = $_.Exception.Message
        }
    }
}

function Get-HotShopNativeOutputLines {
    [CmdletBinding()]
    param([AllowEmptyString()][string]$Text = "")

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return @()
    }
    return @($Text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}
