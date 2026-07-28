[CmdletBinding()]
param(
    [switch]$UseExistingPackages
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

& (Join-Path $PSScriptRoot 'generate-openapi.ps1') -UseExistingPackages:$UseExistingPackages
if ($LASTEXITCODE -ne 0) {
    throw 'Runtime OpenAPI generation failed'
}

Push-Location $repositoryRoot
try {
    docker run --rm `
        --mount "type=bind,source=$repositoryRoot,target=/workspace" `
        --mount "type=volume,source=hotshop-task04-m2,target=/root/.m2" `
        --workdir /workspace `
        eclipse-temurin:21-jdk `
        ./mvnw -B -N -Pgenerate-api-client generate-sources
    if ($LASTEXITCODE -ne 0) {
        throw 'TypeScript API client generation failed'
    }
    Write-Output "Generated clients: $(Join-Path $repositoryRoot 'target\generated-sources\typescript')"
}
finally {
    Pop-Location
}
