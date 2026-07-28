[CmdletBinding()]
param(
    [string]$EnvFile
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot '.env.example'
}
$EnvFile = [System.IO.Path]::GetFullPath($EnvFile)
if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "Environment file not found: $EnvFile"
}

$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$projectName = "hotshop-task04-utc-$suffix"

Push-Location $repositoryRoot
try {
    docker compose --env-file $EnvFile --project-name $projectName up -d --wait mysql
    if ($LASTEXITCODE -ne 0) {
        throw 'Isolated UTC MySQL did not become healthy'
    }

    $sql = 'SELECT @@global.time_zone, @@session.time_zone, ABS(TIMESTAMPDIFF(SECOND, NOW(6), UTC_TIMESTAMP(6)));'
    $result = $sql | docker compose `
        --env-file $EnvFile `
        --project-name $projectName `
        exec -T mysql `
        sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP --host=127.0.0.1 --user=root --database="$MYSQL_DATABASE" --batch --skip-column-names'
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not query the isolated UTC MySQL'
    }

    $columns = ($result.Trim() -split '\s+')
    if ($columns.Count -ne 3) {
        throw "Unexpected UTC probe output: $result"
    }
    if ($columns[0] -ne '+00:00' -or $columns[1] -ne '+00:00' -or [int]$columns[2] -gt 1) {
        throw "UTC verification failed: global=$($columns[0]) session=$($columns[1]) deltaSeconds=$($columns[2])"
    }
    Write-Output "UTC verification passed: project=$projectName global=$($columns[0]) session=$($columns[1]) deltaSeconds=$($columns[2])"
}
finally {
    docker compose `
        --env-file $EnvFile `
        --project-name $projectName `
        down --volumes --remove-orphans | Out-Null
    Pop-Location
}
