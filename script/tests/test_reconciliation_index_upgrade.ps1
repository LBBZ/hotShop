$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$name = 'hotshop-review-index-test-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$id = docker run -d --rm --name $name --network none --tmpfs /data redis:8.8.1-alpine
if ($LASTEXITCODE -ne 0 -or -not $id) { throw 'Cannot start isolated Redis' }
try {
    $registry = 'hotshop:seckill:v1:{hotshop-seckill-v1}:registry:reservation-streams'
    $index = 'hotshop:seckill:v1:{hotshop-seckill-v1}:registry:reconciliation-streams'
    $members = 300..459 | ForEach-Object {
        "hotshop:seckill:v1:{hotshop-seckill-v1}:activity:${_}:reservations"
    }
    docker exec $id redis-cli SADD $registry @members | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Seed failed' }
    $upgrade = Join-Path $repositoryRoot 'script/upgrade-reconciliation-index.ps1'
    & $upgrade -RedisContainer $name -CountHint 3 -MaxPages 1
    $cursor = docker exec $id redis-cli --raw HGET "${index}:upgrade" cursor
    if ($LASTEXITCODE -ne 0 -or -not $cursor -or $cursor -eq '0') {
        throw 'Expected a durable partial first step'
    }
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        & $upgrade -RedisContainer $name -CountHint 3 -MaxPages 10
        $state = docker exec $id redis-cli --raw HGET "${index}:upgrade" state
        if ($LASTEXITCODE -ne 0) { throw 'Cannot read upgrade state' }
        if ($state -eq 'COMPLETE') { break }
    }
    $old = docker exec $id redis-cli SCARD $registry
    $new = docker exec $id redis-cli ZCARD $index
    if ($state -ne 'COMPLETE' -or $old -ne '160' -or $new -ne '160') {
        throw "Upgrade mismatch: state=$state old=$old new=$new"
    }
    foreach ($member in $members) {
        $score = docker exec $id redis-cli ZSCORE $index $member
        if ($LASTEXITCODE -ne 0 -or $score -ne '0') { throw "Missing member: $member" }
    }
    Write-Output 'PASS: actual CLI resumed a partial upgrade and retained all 160 legacy members.'
}
finally {
    docker rm -f $id | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Cannot remove owned test container $id" }
}
