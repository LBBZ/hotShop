[CmdletBinding()]
param(
    [string]$OutputDirectory,
    [switch]$UseExistingPackages
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot 'target\openapi'
}
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$expectedOutputRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot 'target'))
if (-not $OutputDirectory.StartsWith($expectedOutputRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OpenAPI output must remain under $expectedOutputRoot"
}

Push-Location $repositoryRoot
try {
    docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Desktop is not available'
    }

    if (-not $UseExistingPackages) {
        docker run --rm `
            --mount "type=bind,source=$repositoryRoot,target=/workspace" `
            --mount "type=volume,source=hotshop-task04-m2,target=/root/.m2" `
            --workdir /workspace `
            eclipse-temurin:21-jdk@sha256:efd34b940f2d5a621605c8531c2afb7759c936b6c2ef637a69aa3bf3e1e789d1 `
            ./mvnw -B -pl portal,admin -am package
        if ($LASTEXITCODE -ne 0) {
            throw 'Java package build failed; runtime OpenAPI was not generated'
        }
    }

    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $applications = @(
        @{
            Name = 'portal'
            Jar = Join-Path $repositoryRoot 'portal\target\portal-0.0.1-SNAPSHOT.jar'
            Documents = @('public', 'user', 'mock-provider-callback')
        },
        @{
            Name = 'admin'
            Jar = Join-Path $repositoryRoot 'admin\target\admin-0.0.1-SNAPSHOT.jar'
            Documents = @('admin')
        }
    )

    foreach ($application in $applications) {
        if (-not (Test-Path -LiteralPath $application.Jar -PathType Leaf)) {
            throw "Packaged application not found: $($application.Jar)"
        }
        $containerId = docker run -d `
            --label 'com.hotshop.task=task04-openapi' `
            -p '127.0.0.1::8080' `
            --mount "type=bind,source=$($application.Jar),target=/app/app.jar,readonly" `
            eclipse-temurin:21-jdk@sha256:efd34b940f2d5a621605c8531c2afb7759c936b6c2ef637a69aa3bf3e1e789d1 `
            java -jar /app/app.jar `
            --server.port=8080 `
            --springdoc.swagger-ui.enabled=false `
            --logging.level.root=WARN `
            --logging.level.com.real=WARN `
            --logging.level.org.springframework.security=WARN
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
            throw "Could not start the $($application.Name) runtime"
        }

        try {
            $portOutput = docker port $containerId '8080/tcp'
            if ($LASTEXITCODE -ne 0 -or $portOutput -notmatch ':(\d+)\s*$') {
                throw "Could not resolve the $($application.Name) runtime port"
            }
            $port = $Matches[1]
            $probeDocument = $application.Documents[0]
            $deadline = (Get-Date).AddMinutes(2)
            $lastError = $null
            do {
                $running = docker inspect --format '{{.State.Running}}' $containerId
                if ($LASTEXITCODE -ne 0 -or $running -ne 'true') {
                    docker logs $containerId
                    throw "$($application.Name) runtime exited before OpenAPI was available"
                }
                try {
                    Invoke-WebRequest `
                        -UseBasicParsing `
                        -Uri "http://127.0.0.1:$port/v3/api-docs/$probeDocument" `
                        -TimeoutSec 3 | Out-Null
                    $lastError = $null
                    break
                }
                catch {
                    $lastError = $_
                    Start-Sleep -Seconds 2
                }
            } while ((Get-Date) -lt $deadline)
            if ($null -ne $lastError) {
                docker logs $containerId
                throw "$($application.Name) did not become ready: $($lastError.Exception.Message)"
            }

            foreach ($document in $application.Documents) {
                $destination = Join-Path $OutputDirectory "$document.json"
                Invoke-WebRequest `
                    -UseBasicParsing `
                    -Uri "http://127.0.0.1:$port/v3/api-docs/$document" `
                    -OutFile $destination `
                    -TimeoutSec 30
                $null = Get-Content -Raw -LiteralPath $destination -Encoding UTF8 | ConvertFrom-Json
                python (Join-Path $PSScriptRoot 'canonicalize_openapi.py') $destination
                if ($LASTEXITCODE -ne 0) {
                    throw "Could not canonicalize the $document runtime OpenAPI document"
                }
                Write-Output "Generated runtime OpenAPI: $destination"
            }
        }
        finally {
            docker rm -f $containerId 2>$null | Out-Null
        }
    }
}
finally {
    Pop-Location
}
