[CmdletBinding()]
param(
    [switch]$UseExistingPackages
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$generatedDirectory = Join-Path $repositoryRoot 'target\openapi'
$baselineDirectory = Join-Path $repositoryRoot 'docs\api\openapi-baseline'

& (Join-Path $PSScriptRoot 'generate-openapi.ps1') `
    -OutputDirectory $generatedDirectory `
    -UseExistingPackages:$UseExistingPackages
if ($LASTEXITCODE -ne 0) {
    throw 'Runtime OpenAPI generation failed; baseline was not changed'
}

New-Item -ItemType Directory -Force -Path $baselineDirectory | Out-Null
foreach ($name in @('public', 'user', 'admin')) {
    Copy-Item `
        -LiteralPath (Join-Path $generatedDirectory "$name.json") `
        -Destination (Join-Path $baselineDirectory "$name.json") `
        -Force
}
Write-Output "Updated generated baseline: $baselineDirectory"
