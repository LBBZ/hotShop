[CmdletBinding()]
param(
    [string]$ProjectName = "",
    [string]$EvidenceDirectory = "",
    [int]$TimeoutSeconds = 900,
    [switch]$RunZapBaseline,
    [ValidateSet("", "success", "startup-failure", "playwright-failure", "query-failure", "interrupt")]
    [string]$OwnershipTestMode = ""
)

$ErrorActionPreference = "Stop"
$script:Task19Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
. (Join-Path $PSScriptRoot "task19-common.ps1")
$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 12)
if (-not $ProjectName) { $ProjectName = "hotshop-task19-$suffix" }
if (-not $EvidenceDirectory) {
    $EvidenceDirectory = Join-Path $Task19Root "target\task19-e2e\$ProjectName"
}
$EvidenceDirectory = [System.IO.Path]::GetFullPath($EvidenceDirectory)
$temporaryDirectory = Join-Path $Task19Root "target\task19-temp\$ProjectName"
$keyDirectory = Join-Path $temporaryDirectory "keys"
$dockerRoot = Get-Task19DockerHostPath $Task19Root
$dockerEvidenceDirectory = Get-Task19DockerHostPath $EvidenceDirectory
$dockerKeyDirectory = Get-Task19DockerHostPath $keyDirectory
$imageTags = @(
    "$ProjectName-portal:verify",
    "$ProjectName-admin:verify",
    "$ProjectName-task:verify",
    "$ProjectName-agent:verify",
    "$ProjectName-web:verify",
    "$ProjectName-rabbitmq:verify",
    "$ProjectName-web-runtime:verify"
)
$beforeImages = [ordered]@{}
foreach ($tag in $imageTags) { $beforeImages[$tag] = "" }
$beforeResources = New-Task19ResourceSet
$ownedImages = [ordered]@{}
$owned = [ordered]@{
    containers = [ordered]@{}
    volumes = [ordered]@{}
    networks = [ordered]@{}
}
$failure = $null
$cleanupFailures = @()
$cleanupResult = $null
$evidenceDirectoryOwned = $false
$temporaryDirectoryOwned = $false
$verificationPassed = $false
$originalLocation = (Get-Location).Path
$environment = [ordered]@{
    COMPOSE_PROJECT_NAME = $ProjectName
    HOTSHOP_KEY_DIR = $dockerKeyDirectory
    HOTSHOP_SOURCE_ROOT = $dockerRoot
    MYSQL_ROOT_PASSWORD = "Task19Mysql$suffix"
    MYSQL_PORT = "0"
    REDIS_CACHE_PASSWORD = "Task19RedisCache$suffix"
    REDIS_CACHE_PORT = "0"
    REDIS_SECKILL_PASSWORD = "Task19RedisSeckill$suffix"
    REDIS_SECKILL_PORT = "0"
    RABBITMQ_DEFAULT_PASS = "Task19Rabbit$suffix"
    RABBITMQ_AMQP_PORT = "0"
    RABBITMQ_MANAGEMENT_PORT = "0"
    PORTAL_PORT = "0"
    ADMIN_PORT = "0"
    TASK_PORT = "0"
    AGENT_PORT = "0"
    QDRANT_PORT = "0"
    PORTAL_IMAGE = $imageTags[0]
    ADMIN_IMAGE = $imageTags[1]
    TASK_IMAGE = $imageTags[2]
    AGENT_IMAGE = $imageTags[3]
    RABBITMQ_PROJECT_IMAGE = $imageTags[5]
    HOTSHOP_SECURE_COOKIES = "false"
    HOTSHOP_MOCK_PAYMENT_ENABLED = "true"
    HOTSHOP_MOCK_PAYMENT_SECRET = "Task19MockPaymentSecret$suffix-padding"
    HOTSHOP_LEGACY_ORDER_TIMEOUT = "10s"
    HOTSHOP_USER_ACCESS_TTL_SECONDS = "60"
    HOTSHOP_JWT_CLOCK_SKEW_SECONDS = "0"
    # Both browser projects intentionally exercise many short-lived Agent
    # sessions from one isolated runner address. Production keeps the default 20.
    HOTSHOP_SECURITY_RATE_LIMIT_AGENT_EXCHANGE_LIMIT = "200"
    # Five aggregate queries plus an audit write can exceed the production
    # three-second tool budget on constrained CI workers. Keep a finite bound
    # while avoiding a false backend-unavailable result in the browser proof.
    AGENT_TOOL_TIMEOUT_SECONDS = "10"
    # The observability stack is intentionally outside this isolated E2E
    # profile. Do not queue exporters for a collector that is not running.
    HOTSHOP_TRACE_SAMPLING_PROBABILITY = "0"
    AGENT_MODEL_PROVIDER = "fake"
    AGENT_EMBEDDING_PROVIDER = "deterministic"
    AGENT_RAG_MINIMUM_SCORE = "-1"
    AGENT_QWEN_API_KEY = ""
    AGENT_DEEPSEEK_API_KEY = ""
    AGENT_BAILIAN_EMBEDDING_API_KEY = ""
}
$originalEnvironment = [ordered]@{}
$processEnvironment = [Environment]::GetEnvironmentVariables("Process")
foreach ($entry in $environment.GetEnumerator()) {
    $exists = $processEnvironment.Contains($entry.Key)
    $originalEnvironment[$entry.Key] = [ordered]@{
        exists = [bool]$exists
        value = if ($exists) { [string]$processEnvironment[$entry.Key] } else { $null }
    }
}

function Register-CurrentResources {
    $result = Register-Task19CreatedResources -ProjectName $script:ProjectName `
        -Before $script:beforeResources -Owned $script:owned
    if (-not $result.Success) { throw "TASK-19 ownership registration failed: $($result.Failures -join '; ')" }
}

function Register-CurrentImages([string[]]$Tags) {
    foreach ($tag in $Tags) {
        $result = Register-Task19CreatedImage -Tag $tag -BeforeImages $script:beforeImages `
            -OwnedImages $script:ownedImages
        if (-not $result.Success) { throw "TASK-19 image ownership registration failed: $($result.Failures -join '; ')" }
    }
}

function Invoke-OwnershipProbeResources {
    $probeImage = "alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce"
    $networkName = "$script:ProjectName-ownership-network"
    $volumeName = "$script:ProjectName-ownership-volume"
    $containerName = "$script:ProjectName-ownership-container"
    foreach ($operation in @(
        @("network", "create", "--label", "com.docker.compose.project=$script:ProjectName", $networkName),
        @("volume", "create", "--label", "com.docker.compose.project=$script:ProjectName", $volumeName),
        @("container", "create", "--label", "com.docker.compose.project=$script:ProjectName",
            "--name", $containerName, $probeImage, "sleep", "600")
    )) {
        $result = Invoke-Task19Docker -Arguments $operation -AllowFailure
        Register-CurrentResources
        if ($result.ExitCode -ne 0) { throw "Synthetic ownership resource creation failed" }
    }
    $tagResult = Invoke-Task19Docker -Arguments @("image", "tag", $probeImage, $script:imageTags[0]) -AllowFailure
    Register-CurrentImages @($script:imageTags[0])
    if ($tagResult.ExitCode -ne 0) { throw "Synthetic ownership image creation failed" }
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $dockerArguments = @(
        "compose", "-p", $script:ProjectName, "--env-file",
        (Join-Path $script:Task19Root ".env.example")
    ) + $Arguments
    $result = Invoke-Task19Docker -Arguments $dockerArguments -AllowFailure
    if ($result.ExitCode -ne 0) { throw "Compose command failed" }
    return $result.Output
}

function Invoke-WebImageBuild(
    [string]$Target,
    [string]$Tag,
    [string]$LogName
) {
    $attemptOutput = [System.Collections.Generic.List[string]]::new()
    $result = $null
    foreach ($attempt in 1..2) {
        $result = Invoke-Task19Docker -Arguments @(
            "build", "--target", $Target, "--tag", $Tag,
            "--file", (Join-Path $script:Task19Root "web\Dockerfile"),
            (Join-Path $script:Task19Root "web")
        ) -AllowFailure
        Register-CurrentImages @($Tag)
        [void]$attemptOutput.Add("=== attempt $attempt exit $($result.ExitCode) ===`n$($result.Output)")
        if ($result.ExitCode -eq 0) { break }
        if ($result.Output -notmatch '(?i)segmentation fault|exit code:\s*139|unexpected EOF|file has already been closed') {
            break
        }
    }
    Set-Content -LiteralPath (Join-Path $script:EvidenceDirectory $LogName) `
        -Value ($attemptOutput -join "`n") -Encoding UTF8
    return $result
}

try {
    Set-Location $Task19Root
    if ($OwnershipTestMode -and
            [Environment]::GetEnvironmentVariable("HOTSHOP_CI_OWNERSHIP_TEST", "Process") -cne "1") {
        throw "TASK-19 ownership test mode is restricted to CI regression probes"
    }
    $beforeResources = Assert-Task19Preflight $ProjectName $imageTags $temporaryDirectory $EvidenceDirectory
    New-Item -ItemType Directory -Force $EvidenceDirectory | Out-Null
    $evidenceDirectoryOwned = $true
    New-Item -ItemType Directory -Force $keyDirectory | Out-Null
    $temporaryDirectoryOwned = $true
    foreach ($entry in $environment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, "Process")
    }

    if ($OwnershipTestMode) {
        Invoke-OwnershipProbeResources
        switch ($OwnershipTestMode) {
            "success" {
                Write-Task19Json (Join-Path $EvidenceDirectory "summary.json") ([ordered]@{
                    schemaVersion = "1.0"; project = $ProjectName; ownershipProbe = $true; passed = $true
                })
                $verificationPassed = $true
            }
            "query-failure" {
                $script:Task19InjectDockerQueryFailure = $true
                throw "Synthetic Docker query failure"
            }
            "interrupt" { throw [System.OperationCanceledException]::new("Synthetic Ctrl-C/interrupt") }
            "startup-failure" { throw "Synthetic startup failure" }
            "playwright-failure" { throw "Synthetic Playwright failure" }
        }
    }

    if (-not $OwnershipTestMode) {
    & (Join-Path $PSScriptRoot "generate-auth-keys.ps1") -OutputDirectory $keyDirectory `
        -DockerOutputDirectory $dockerKeyDirectory
    if ($LASTEXITCODE -ne 0) { throw "TASK-19 key generation failed" }
    [void](Invoke-Compose --profile app --profile agent config --quiet)
    $startup = Invoke-Task19Docker -Arguments @(
        "compose", "-p", $ProjectName, "--env-file", (Join-Path $Task19Root ".env.example"),
        "--profile", "app", "--profile", "agent", "up", "-d", "--build"
    ) -AllowFailure
    Register-CurrentResources
    Register-CurrentImages $imageTags
    Set-Content -LiteralPath (Join-Path $EvidenceDirectory "compose-startup.txt") `
        -Value ($startup.Output -replace 'eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}', '[REDACTED_JWT]') `
        -Encoding UTF8
    if ($startup.ExitCode -ne 0) { throw "TASK-19 isolated Compose startup failed" }

    [void](Wait-Task19Until -TimeoutSeconds $TimeoutSeconds -Description "all application readiness" -Probe {
        [void](Invoke-Compose exec -T agent-service python -c @'
import urllib.request
for url in (
    "http://portal-service:8080/v3/api-docs/public",
    "http://admin-service:8088/v3/api-docs/admin",
    "http://task-service:8888/actuator/health/readiness",
    "http://agent-service:8090/health/ready",
    "http://qdrant:6333/readyz",
):
    urllib.request.urlopen(url, timeout=2).read()
'@)
        return $true
    })

    Get-Content -LiteralPath (Join-Path $Task19Root "web\scripts\task-13-e2e-seed.sql") -Raw -Encoding UTF8 |
        & docker compose -p $ProjectName --env-file (Join-Path $Task19Root ".env.example") exec -T mysql sh -lc `
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 --protocol=TCP --host=127.0.0.1 --user=root --database="$MYSQL_DATABASE"'
    if ($LASTEXITCODE -ne 0) { throw "TASK-19 deterministic seed failed" }

    [void](Invoke-Compose exec -T agent-service python -m hotshop_agent.index_cli rebuild)

    $webBuild = Invoke-WebImageBuild "test" $imageTags[4] "web-runner-build.txt"
    if ($webBuild.ExitCode -ne 0) { throw "TASK-19 Web runner build failed" }
    $webRuntimeBuild = Invoke-WebImageBuild "runtime" $imageTags[6] "web-runtime-build.txt"
    if ($webRuntimeBuild.ExitCode -ne 0) { throw "TASK-19 Web runtime build failed" }
    $webIntegrity = Invoke-Task19Docker -Arguments @(
        "run", "--rm", "--network", "none", $imageTags[4],
        "sh", "-lc", "test -s package.json && test -s src/main.tsx"
    ) -AllowFailure
    if ($webIntegrity.ExitCode -ne 0) { throw "TASK-19 Web runner integrity check failed" }

    $network = "${ProjectName}_hotShop-network"
    $runtimeName = "$ProjectName-web-runtime"
    $runtimeStart = Invoke-Task19Docker -Arguments @(
        "run", "-d", "--name", $runtimeName,
        "--label", "com.docker.compose.project=$ProjectName",
        "--network", $network, $imageTags[6]
    ) -AllowFailure
    Register-CurrentResources
    if ($runtimeStart.ExitCode -ne 0) { throw "TASK-19 Web runtime failed to start" }
    $runtimeEvidence = Wait-Task19Until -TimeoutSeconds 120 -Description "Web runtime and explicit proxy readiness" -Probe {
        $probe = Invoke-Task19Docker -Arguments @(
            "exec", $runtimeName, "sh", "-lc", @'
set -eu
headers="$(curl -sS -D - -o /dev/null http://127.0.0.1:8080/)"
printf '%s\n' "$headers" | grep -qi '^Content-Security-Policy:'
printf '%s\n' "$headers" | grep -qi '^X-Content-Type-Options: nosniff'
test "$(curl -sS -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/v1/products)" = 200
test "$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8080/agent-api/api/v1/agent/sessions)" = 401
printf 'static=200 portal_proxy=200 agent_proxy=401 csp=present nosniff=present\n'
'@
        ) -AllowFailure
        if ($probe.ExitCode -ne 0) { throw "Web runtime proxy is not ready" }
        return $probe.Output
    }
    Set-Content -LiteralPath (Join-Path $EvidenceDirectory "web-runtime-smoke.txt") `
        -Value $runtimeEvidence -Encoding UTF8

    function Invoke-PlaywrightRun(
        [string]$Name,
        [string[]]$Specifications,
        [string[]]$Projects
    ) {
        $arguments = @(
            "run", "--rm", "--name", "$ProjectName-playwright-$Name",
            "--label", "com.docker.compose.project=$ProjectName",
            "--user", "0:0",
            "--network", $network,
            "--volume", "/var/run/docker.sock:/var/run/docker.sock",
            "--mount", "type=bind,source=$dockerEvidenceDirectory,target=/workspace/web/task19-evidence",
            "-e", "CI=true",
            "-e", "HOTSHOP_REAL_COMPOSE=1",
            "-e", "HOTSHOP_E2E_COMPOSE_PROJECT=$ProjectName",
            "-e", "HOTSHOP_E2E_COMPOSE_FILE=e2e/compose-control.yml",
            "-e", "HOTSHOP_E2E_ADMIN_USERNAME=task13-admin",
            "-e", "HOTSHOP_E2E_ADMIN_PASSWORD=Task13Admin!2026",
            "-e", "HOTSHOP_E2E_SHORT_TIMEOUT=1",
            "-e", "HOTSHOP_E2E_SHORT_ACCESS=1",
            "-e", "HOTSHOP_PORTAL_URL=http://portal-service:8080",
            "-e", "HOTSHOP_ADMIN_URL=http://admin-service:8088",
            "-e", "HOTSHOP_AGENT_URL=http://agent-service:8090",
            "-e", "PLAYWRIGHT_JUNIT_OUTPUT_NAME=task19-evidence/results-$Name.xml"
        ) + @(
            $imageTags[4], "pnpm", "exec", "playwright", "test"
        )
        $arguments += $Specifications + @($Projects | ForEach-Object { "--project=$_" }) + @(
            "--retries=0",
            "--reporter=line,junit"
        )
        return Invoke-Task19Docker -Arguments $arguments -AllowFailure
    }

    # Run the complete real suite in one Playwright process. This gives the serial
    # scenarios one global setup and one Vite lifecycle, and avoids a second Node
    # startup becoming an unrelated failure point on constrained Docker hosts.
    # The mobile project is restricted in playwright.config.ts to the TASK-19
    # Agent/security spec, while Chromium covers all three specifications.
    $realRunner = Invoke-PlaywrightRun "real" @(
        "e2e/user-transaction-real.spec.ts",
        "e2e/admin-operations-real.spec.ts",
        "e2e/task-19-agent-security-real.spec.ts"
    ) @("chromium", "mobile-chromium")
    Register-CurrentResources
    $safeOutput = @(
        "=== real user/admin/Agent/security journeys (Chromium + mobile Chromium) ===",
        $realRunner.Output
    ) -join "`n"
    $safeOutput = $safeOutput -replace 'eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}', '[REDACTED_JWT]'
    Set-Content -LiteralPath (Join-Path $EvidenceDirectory "playwright-output.txt") `
        -Value $safeOutput -Encoding UTF8
    if ($realRunner.ExitCode -ne 0) {
        throw "TASK-19 real Playwright suite failed"
    }

    $zap = [ordered]@{ executed = $false; exitCode = $null; warningsAllowed = $true }
    if ($RunZapBaseline) {
        $zapImage = "zaproxy/zap-stable@sha256:7840969c7c9fead565bf9734b12f49f6886db90b1d35b1f74d79710bbd081dab"
        $zapRun = Invoke-Task19Docker -Arguments @(
            "run", "--rm", "--user", "0:0", "--network", $network,
            "--mount", "type=bind,source=$dockerEvidenceDirectory,target=/zap/wrk",
            $zapImage, "zap-baseline.py", "-t", "http://$runtimeName`:8080",
            "-J", "zap.json", "-r", "zap.html", "-m", "2"
        ) -AllowFailure
        Set-Content -LiteralPath (Join-Path $EvidenceDirectory "zap-output.txt") `
            -Value $zapRun.Output -Encoding UTF8
        $zap.executed = $true
        $zap.exitCode = $zapRun.ExitCode
        if ($zapRun.ExitCode -notin @(0, 2)) {
            throw "ZAP baseline reported a FAIL or scanner error (exit $($zapRun.ExitCode))"
        }
    }

    $summary = [ordered]@{
        schemaVersion = "1.0"
        project = $ProjectName
        provider = "fake"
        embedding = "deterministic"
        specifications = @(
            "user-transaction-real.spec.ts",
            "admin-operations-real.spec.ts",
            "task-19-agent-security-real.spec.ts"
        )
        browsers = @("chromium", "mobile-chromium")
        fixedSleep = $false
        routeMock = $false
        directProviderCallback = $false
        traceArtifact = $false
        zap = $zap
        passed = $true
    }
    Write-Task19Json (Join-Path $EvidenceDirectory "summary.json") $summary
    $verificationPassed = $true
    }
}
catch {
    $failure = $_
    try {
        if (-not $evidenceDirectoryOwned) { throw "Evidence directory is not invocation-owned" }
        $logs = Invoke-Task19Docker -Arguments @(
            "compose", "-p", $ProjectName, "--env-file", (Join-Path $Task19Root ".env.example"),
            "logs", "--no-color"
        ) -AllowFailure
        $safeLogs = $logs.Output -replace 'eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}', '[REDACTED_JWT]'
        Set-Content -LiteralPath (Join-Path $EvidenceDirectory "failure-logs-redacted.txt") -Value $safeLogs -Encoding UTF8
    }
    catch { }
}
finally {
    try {
        $cleanupResult = Remove-Task19Resources -ProjectName $ProjectName -Owned $owned `
            -OwnedImages $ownedImages -TemporaryDirectory $temporaryDirectory `
            -TemporaryDirectoryOwned $temporaryDirectoryOwned -EvidenceDirectory $EvidenceDirectory `
            -EvidenceDirectoryOwned $evidenceDirectoryOwned -VerificationPassed $verificationPassed
        $cleanupFailures = @($cleanupResult.Failures)
    }
    catch {
        $cleanupFailures += "Unexpected cleanup exception: $($_.Exception.Message)"
    }
    finally {
        $script:Task19InjectDockerQueryFailure = $false
        foreach ($entry in $originalEnvironment.GetEnumerator()) {
            try {
                if ([bool]$entry.Value.exists) {
                    # PowerShell 7 preserves an explicitly empty value through the
                    # environment provider, unlike APIs that conflate empty/null.
                    Set-Item -LiteralPath "Env:$($entry.Key)" -Value ([string]$entry.Value.value)
                }
                else {
                    Remove-Item -LiteralPath "Env:$($entry.Key)" -ErrorAction SilentlyContinue
                }
            }
            catch { $cleanupFailures += "Environment restore failed for $($entry.Key): $($_.Exception.Message)" }
        }
        try { Set-Location $originalLocation }
        catch { $cleanupFailures += "Working directory restore failed: $($_.Exception.Message)" }
    }
    if ($cleanupFailures.Count -and $evidenceDirectoryOwned -and $null -ne $cleanupResult) {
        $cleanupResult.Report.failures = @($cleanupFailures)
        $cleanupResult.Report.clean = $false
        $rewrite = Write-Task19JsonResult (Join-Path $EvidenceDirectory "cleanup.json") $cleanupResult.Report
        if (-not $rewrite.Success) { $cleanupFailures += @($rewrite.Failures) }
    }
}

if ($cleanupFailures.Count) {
    $cleanupMessage = "TASK-19 cleanup failed: $($cleanupFailures -join '; ')"
    if ($null -ne $failure) {
        throw "$($failure.Exception.Message); $cleanupMessage"
    }
    throw $cleanupMessage
}
if ($null -ne $failure) { throw $failure }
Write-Output "TASK-19 real E2E passed; evidence=$EvidenceDirectory"
