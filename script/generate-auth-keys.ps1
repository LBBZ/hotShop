[CmdletBinding()]
param(
    [string]$OutputDirectory,
    [switch]$Force,
    [string]$OpenSslImage = 'alpine/openssl:3.5.4@sha256:42c7389ef077aed0eb4e96d0abbd094083d701bbaff1313073b061c0c9cd8278'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot '.local\keys\hotshop'
}
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$names = @('user', 'administrator', 'agent-delegation', 'agent-service')
$targets = foreach ($name in $names) {
    Join-Path $OutputDirectory "$name-private.pem"
    Join-Path $OutputDirectory "$name-public.pem"
}
$existing = @($targets | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
if ($existing.Count -gt 0 -and -not $Force) {
    throw "Refusing to overwrite existing authentication keys. Re-run with -Force only after explicit confirmation. Existing: $($existing -join ', ')"
}
if ($Force) {
    foreach ($target in $existing) {
        Remove-Item -LiteralPath $target -Force
    }
}

docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Desktop is not available'
}

foreach ($name in $names) {
    docker run --rm `
        --mount "type=bind,source=$OutputDirectory,target=/keys" `
        $OpenSslImage `
        genpkey -quiet -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "/keys/$name-private.pem"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not generate the $name private key"
    }
    docker run --rm `
        --mount "type=bind,source=$OutputDirectory,target=/keys" `
        $OpenSslImage `
        pkey -in "/keys/$name-private.pem" -pubout -out "/keys/$name-public.pem"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not derive the $name public key"
    }
}

foreach ($target in $targets) {
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
        throw "Expected key was not generated: $target"
    }
}

Write-Output "Generated four isolated RSA key pairs under: $OutputDirectory"
Write-Output 'Keep all *-private.pem files outside Git. The Java token-exchange boundary receives only agent-service-public.pem.'
