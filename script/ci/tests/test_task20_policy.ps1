[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\..\task20-common.ps1')

function New-CleanFacts {
    [pscustomobject]@{
        outboxPending=0L; inboxPending=0L; streamPending=0L; failedOrStuckEvents=0L
        rabbitUnexplainedReady=0L; rabbitUnexplainedUnacknowledged=0L
        orderCreatedPublishProofMissing=0L; timeoutPublishProofMissing=0L
        oversell=0L; duplicateValidOrders=0L; reconciliationDifferences=0L
        redisRemainingStock=90L; mysqlRemainingStock=90L; databaseInitialStock=100L
        initialStock=100L; reservations=10L; validOrders=10L; reservedQuantity=10L
        asyncOrderMs=[pscustomobject]@{ sampleCount=10; p99=100 }
    }
}

$facts = New-CleanFacts
if (-not (Test-Task20MessageBacklog $facts)) { throw 'Clean backlog was rejected' }
if (-not (Test-Task20BusinessCorrectness $facts $true 0 0 $true 0 0)) { throw 'Clean correctness facts were rejected' }
if (-not (Test-Task20PerformanceTarget $facts $true 100 100 0 0 100)) { throw 'Clean performance facts were rejected' }

foreach ($field in @('outboxPending','inboxPending','streamPending','failedOrStuckEvents',
        'rabbitUnexplainedReady','rabbitUnexplainedUnacknowledged','orderCreatedPublishProofMissing','timeoutPublishProofMissing')) {
    $dirty = New-CleanFacts
    $dirty.$field = 1L
    if (Test-Task20MessageBacklog $dirty) { throw "$field backlog was incorrectly accepted" }
    if (Test-Task20BusinessCorrectness $dirty $true 0 0 $true 0 0) { throw "$field did not fail business correctness" }
    if (Test-Task20PerformanceTarget $dirty $true 100 100 0 0 100) { throw "$field did not fail the performance target" }
}

if (Test-Task20BusinessCorrectness (New-CleanFacts) $true 0 0 $true 1 0) {
    throw 'Idempotency conflict did not fail correctness'
}
Write-Host 'TASK-20 backlog and correctness gate tests passed.'
