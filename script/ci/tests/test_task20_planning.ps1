[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\..\task20-common.ps1')

function Assert-Equal($Actual, $Expected, [string]$Message) {
    if ($Actual -ne $Expected) { throw "$Message (expected=$Expected, actual=$Actual)" }
}

$cliRates = @(ConvertTo-Task20Rates -Values @('100,250,500'))
Assert-Equal ($cliRates -join ',') '100,250,500' 'Comma-delimited pwsh -File rates must retain stage boundaries'
$arrayRates = @(ConvertTo-Task20Rates -Values @(100, 250, 500))
Assert-Equal ($arrayRates -join ',') '100,250,500' 'Array rates must retain stage boundaries'
$invalidRatesRejected = $false
try { ConvertTo-Task20Rates -Values @('100,nope') | Out-Null } catch { $invalidRatesRejected = $true }
Assert-Equal $invalidRatesRejected $true 'Invalid rate tokens must be rejected'

$smoke = @(Get-Task20RunPlan -Profile smoke)
Assert-Equal $smoke.Count 1 'Smoke must contain one stage'
Assert-Equal $smoke[0].users 5 'Smoke default user pool'

$baseline = @(Get-Task20RunPlan -Profile baseline)
Assert-Equal $baseline.Count 8 'Baseline default stage count'
$mixed = @($baseline | Where-Object scenario -eq 'mixed-e2e')
Assert-Equal $mixed.Count 1 'Baseline must contain mixed traffic'
Assert-Equal $mixed[0].users 301 'Mixed pool must track 30 percent of 100 RPS for 10 seconds plus boundary identity'
foreach ($stage in @($baseline | Where-Object scenario -eq 'seckill-new-intent')) {
    Assert-Equal $stage.users ([Math]::Ceiling($stage.rate * 10) + 1) "New-intent pool at $($stage.rate) RPS"
}

$target = @(Get-Task20RunPlan -Profile target-5k)
Assert-Equal $target[0].rate 5000 'Target default rate'
Assert-Equal $target[0].users 50001 'Target pool must be rate times duration plus boundary identity'

$agent = @(Get-Task20RunPlan -Profile agent-isolation)
Assert-Equal $agent[0].rate 20 'Agent-isolation transaction rate'
Assert-Equal $agent[0].users 301 'Agent-isolation transaction pool'

$fractional = @(Get-Task20RunPlan -Profile baseline -Rates @(7) -Duration 1500ms)
Assert-Equal (@($fractional | Where-Object scenario -eq 'seckill-new-intent'))[0].users 12 'Fractional duration planning'
Assert-Equal (@($fractional | Where-Object scenario -eq 'mixed-e2e'))[0].users 46 'Fractional mixed planning'

Write-Host 'TASK-20 default configuration planning tests passed.'
