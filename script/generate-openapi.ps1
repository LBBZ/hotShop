[CmdletBinding()]
param(
    [string]$OutputDirectory,
    [switch]$UseExistingPackages
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:Task19Root = $repositoryRoot
. (Join-Path $PSScriptRoot 'task19-common.ps1')
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot 'target\openapi'
}
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$expectedOutputRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot 'target'))
if (-not $OutputDirectory.StartsWith($expectedOutputRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OpenAPI output must remain under $expectedOutputRoot"
}
$dockerRepositoryRoot = Get-Task19DockerHostPath $repositoryRoot
$dockerOutputDirectory = Get-Task19DockerHostPath $OutputDirectory
$dependencySuffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$dependencyNetwork = "hotshop-openapi-$dependencySuffix"
$mysqlContainerName = "hotshop-openapi-mysql-$dependencySuffix"
$mysqlImage = 'mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b'
$mysqlPassword = [Guid]::NewGuid().ToString('N')
$ownedDependencyNetworkId = ''
$ownedMysqlContainerId = ''
$selfContainerId = ''
$selfNetworkConnected = $false
$cleanupFailures = [System.Collections.Generic.List[string]]::new()

Push-Location $repositoryRoot
try {
    docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Desktop is not available'
    }

    if (-not $UseExistingPackages) {
        # This container only packages the runtime used to export OpenAPI. The Java CI job
        # owns clean verify; skipping tests here avoids duplicating Testcontainers in a
        # nested build container that intentionally has no Docker socket.
        docker run --rm `
            --mount "type=bind,source=$dockerRepositoryRoot,target=/workspace" `
            --mount "type=volume,source=hotshop-task04-m2,target=/root/.m2" `
            --workdir /workspace `
            eclipse-temurin:21-jdk@sha256:efd34b940f2d5a621605c8531c2afb7759c936b6c2ef637a69aa3bf3e1e789d1 `
            sh ./mvnw -B -DskipTests -pl portal,admin -am package
        if ($LASTEXITCODE -ne 0) {
            throw 'Java package build failed; runtime OpenAPI was not generated'
        }
    }

    $networkBefore = Get-Task19ResourceIdentity 'network' $dependencyNetwork
    $mysqlBefore = Get-Task19ResourceIdentity 'container' $mysqlContainerName
    if (-not $networkBefore.Success -or -not $mysqlBefore.Success) {
        throw 'OpenAPI dependency resource preflight query failed'
    }
    if ($networkBefore.Exists -or $mysqlBefore.Exists) {
        throw 'OpenAPI dependency resource name collision'
    }

    $networkCreate = Invoke-Task19Docker -Arguments @(
        'network', 'create', '--label', 'com.hotshop.task=task04-openapi',
        '--label', "hotshop.openapi.invocation=$dependencySuffix", $dependencyNetwork
    ) -AllowFailure
    $networkAfter = Get-Task19ResourceIdentity 'network' $dependencyNetwork
    if ($networkAfter.Success -and $networkAfter.Exists) {
        $ownedDependencyNetworkId = [string]$networkAfter.Identity
    }
    if ($networkCreate.ExitCode -ne 0 -or -not $ownedDependencyNetworkId) {
        throw 'Could not create the isolated OpenAPI dependency network'
    }

    # When this script itself runs in the repository orchestrator container,
    # loopback belongs to that container rather than the Docker host. Join the
    # isolated network temporarily so runtime documents can be fetched by name.
    $selfIdentity = Get-Task19ResourceIdentity 'container' ([Environment]::MachineName)
    if ($selfIdentity.Success -and $selfIdentity.Exists) {
        $selfContainerId = [string]$selfIdentity.Identity
        $connectSelf = Invoke-Task19Docker -Arguments @(
            'network', 'connect', $ownedDependencyNetworkId, $selfContainerId
        ) -AllowFailure
        if ($connectSelf.ExitCode -ne 0) {
            throw 'Could not connect the OpenAPI orchestrator to its isolated network'
        }
        $selfNetworkConnected = $true
    }

    $mysqlStart = Invoke-Task19Docker -Arguments @(
        'run', '-d', '--name', $mysqlContainerName,
        '--label', 'com.hotshop.task=task04-openapi',
        '--label', "hotshop.openapi.invocation=$dependencySuffix",
        '--network', $dependencyNetwork,
        '-e', "MYSQL_ROOT_PASSWORD=$mysqlPassword", '-e', 'MYSQL_DATABASE=hotShop',
        $mysqlImage, '--character-set-server=utf8mb4',
        '--collation-server=utf8mb4_0900_ai_ci', '--default-time-zone=+00:00'
    ) -AllowFailure
    $mysqlAfter = Get-Task19ResourceIdentity 'container' $mysqlContainerName
    if ($mysqlAfter.Success -and $mysqlAfter.Exists) {
        $ownedMysqlContainerId = [string]$mysqlAfter.Identity
    }
    if ($mysqlStart.ExitCode -ne 0 -or -not $ownedMysqlContainerId) {
        throw 'Could not start the isolated OpenAPI MySQL dependency'
    }

    $mysqlDeadline = (Get-Date).AddMinutes(2)
    $mysqlReady = $false
    do {
        $mysqlProbe = Invoke-Task19Docker -Arguments @(
            'exec', '--env', "MYSQL_PWD=$mysqlPassword", $ownedMysqlContainerId,
            'mysql', '--protocol=TCP', '--host=127.0.0.1', '--user=root',
            '--database=hotShop', '--execute=SELECT 1', '--silent'
        ) -AllowFailure
        if ($mysqlProbe.ExitCode -eq 0) { $mysqlReady = $true; break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $mysqlDeadline)
    if (-not $mysqlReady) {
        throw 'Isolated OpenAPI MySQL dependency did not become ready'
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
        $dockerJar = Get-Task19DockerHostPath $application.Jar
        $applicationContainerName = "hotshop-openapi-$($application.Name)-$dependencySuffix"
        $containerId = docker run -d `
            --name $applicationContainerName `
            --label 'com.hotshop.task=task04-openapi' `
            --label "hotshop.openapi.invocation=$dependencySuffix" `
            --network $dependencyNetwork `
            -p '127.0.0.1::8080' `
            -e "SPRING_DATASOURCE_URL=jdbc:mysql://${mysqlContainerName}:3306/hotShop?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" `
            -e 'SPRING_DATASOURCE_USERNAME=root' `
            -e "SPRING_DATASOURCE_PASSWORD=$mysqlPassword" `
            --mount "type=bind,source=$dockerJar,target=/app/app.jar,readonly" `
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
            $probeBaseUrl = if ($selfNetworkConnected) {
                "http://${applicationContainerName}:8080"
            }
            else {
                "http://127.0.0.1:$port"
            }
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
                        -Uri "$probeBaseUrl/v3/api-docs/$probeDocument" `
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
                    -Uri "$probeBaseUrl/v3/api-docs/$document" `
                    -OutFile $destination `
                    -TimeoutSec 30
                $null = Get-Content -Raw -LiteralPath $destination -Encoding UTF8 | ConvertFrom-Json
                $canonicalize = Invoke-Task19Docker -Arguments @(
                    'run', '--rm', '--network', 'none',
                    '--mount', "type=bind,source=$dockerRepositoryRoot,target=/repo,readonly",
                    '--mount', "type=bind,source=$dockerOutputDirectory,target=/output",
                    '--workdir', '/repo',
                    'python:3.12.11-slim-bookworm@sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7',
                    'python', 'script/canonicalize_openapi.py', "/output/$document.json"
                ) -AllowFailure
                if ($canonicalize.ExitCode -ne 0) {
                    throw "Could not canonicalize the $document runtime OpenAPI document"
                }
                Write-Output "Generated runtime OpenAPI: $destination"
            }
        }
        finally {
            docker rm -f $containerId 2>$null | Out-Null
        }
    }

    & (Join-Path $PSScriptRoot 'generate-agent-openapi.ps1') `
        -OutputPath (Join-Path $OutputDirectory 'agent.json') `
        -DockerOutputDirectory $dockerOutputDirectory
    if ($LASTEXITCODE -ne 0) {
        throw 'Agent runtime OpenAPI generation failed'
    }
}
finally {
    if ($ownedMysqlContainerId) {
        try {
            $currentMysql = Get-Task19ResourceIdentity 'container' $ownedMysqlContainerId
            if (-not $currentMysql.Success) {
                [void]$cleanupFailures.Add('OpenAPI MySQL cleanup query failed')
            }
            elseif ($currentMysql.Exists -and $currentMysql.Identity -ceq $ownedMysqlContainerId) {
                $removeMysql = Invoke-Task19Docker -Arguments @('rm', '--force', $ownedMysqlContainerId) -AllowFailure
                if ($removeMysql.ExitCode -ne 0) { [void]$cleanupFailures.Add('OpenAPI MySQL cleanup failed') }
            }
        }
        catch { [void]$cleanupFailures.Add("OpenAPI MySQL cleanup exception: $($_.Exception.Message)") }
    }
    if ($ownedDependencyNetworkId) {
        try {
            if ($selfNetworkConnected -and $selfContainerId) {
                $disconnectSelf = Invoke-Task19Docker -Arguments @(
                    'network', 'disconnect', $ownedDependencyNetworkId, $selfContainerId
                ) -AllowFailure
                if ($disconnectSelf.ExitCode -ne 0) {
                    [void]$cleanupFailures.Add('OpenAPI orchestrator network disconnect failed')
                }
            }
            $currentNetwork = Get-Task19ResourceIdentity 'network' $ownedDependencyNetworkId
            if (-not $currentNetwork.Success) {
                [void]$cleanupFailures.Add('OpenAPI network cleanup query failed')
            }
            elseif ($currentNetwork.Exists -and $currentNetwork.Identity -ceq $ownedDependencyNetworkId) {
                $removeNetwork = Invoke-Task19Docker -Arguments @('network', 'rm', $ownedDependencyNetworkId) -AllowFailure
                if ($removeNetwork.ExitCode -ne 0) { [void]$cleanupFailures.Add('OpenAPI network cleanup failed') }
            }
        }
        catch { [void]$cleanupFailures.Add("OpenAPI network cleanup exception: $($_.Exception.Message)") }
    }
    $mysqlPassword = $null
    Pop-Location
}
if ($cleanupFailures.Count) {
    throw "OpenAPI dependency cleanup failed: $($cleanupFailures -join '; ')"
}
