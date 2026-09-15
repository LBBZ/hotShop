$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
. (Join-Path $PSScriptRoot '../../task19-common.ps1')
$missing = 'hotshop-missing-probe-' + [Guid]::NewGuid().ToString('N')
$result = Invoke-Task19Docker -Arguments @('container', 'inspect', $missing) -AllowFailure
if ($result.ExitCode -eq 0 -or $result.Output -notmatch 'No such') {
    throw 'Expected absent container must return a nonzero diagnostic result'
}
if (-not $PSNativeCommandUseErrorActionPreference) { throw 'Caller preference was changed' }
$rejected = $false
try { Invoke-Task19Docker -Arguments @('container', 'inspect', $missing) | Out-Null }
catch { $rejected = $_.Exception.Message -match 'Docker command failed with exit' }
if (-not $rejected) { throw 'Unexpected native failures must still throw' }
$success = Invoke-Task19Docker -Arguments @('version', '--format', '{{.Client.Version}}')
if ($success.ExitCode -ne 0) { throw 'Successful native command failed' }
Write-Output 'Task19 native preference regression passed'
