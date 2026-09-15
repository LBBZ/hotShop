param(
    [Parameter(Mandatory = $true)][string]$RedisContainer,
    [ValidateRange(1, 1000)][int]$CountHint = 100,
    [ValidateRange(1, 1000)][int]$MaxPages = 10,
    [ValidateRange(1, 60)][int]$MaxSeconds = 10
)
$ErrorActionPreference = "Stop"
$scriptPath = Join-Path $PSScriptRoot "../task/src/main/resources/redis/upgrade-reconciliation-index-v1.lua"
$lua = Get-Content -Raw -LiteralPath $scriptPath
$registry = "hotshop:seckill:v1:{hotshop-seckill-v1}:registry:reservation-streams"
$index = "hotshop:seckill:v1:{hotshop-seckill-v1}:registry:reconciliation-streams"
$checkpoint = "$index`:upgrade"
$timer = [System.Diagnostics.Stopwatch]::StartNew()
for ($page = 0; $page -lt $MaxPages -and $timer.Elapsed.TotalSeconds -lt $MaxSeconds; $page++) {
    # Explicit maintenance only. SSCAN COUNT is advisory, unlike the hard LIMIT
    # used by runBatch; print the actual page size and resume stored cursor.
    $result = @($lua | docker exec -i $RedisContainer redis-cli --raw --eval /dev/stdin $registry $index $checkpoint , $CountHint)
    if ($LASTEXITCODE -ne 0 -or $result.Count -ne 3) {
        throw "Index upgrade failed: $result"
    }
    Write-Output "cursor=$($result[0]) visited=$($result[1]) indexed=$($result[2])"
    if ($result[0] -eq "0") {
        Write-Output "COMPLETE: verify SCARD registry equals ZCARD index before acceptance."
        return
    }
}
Write-Output "IN_PROGRESS: rerun the same command to resume. No old registry entries were removed."
