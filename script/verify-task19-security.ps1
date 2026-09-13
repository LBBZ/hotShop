[CmdletBinding()]
param(
    [string]$EvidenceDirectory = "",
    [int]$TimeoutSeconds = 1200
)

$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$script:Task19Root = $root
. (Join-Path $PSScriptRoot "task19-common.ps1")
$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 12)
$project = "hotshop-task19-$suffix"
if (-not $EvidenceDirectory) {
    $EvidenceDirectory = Join-Path $root "target\task19-security\$project"
}
$EvidenceDirectory = [System.IO.Path]::GetFullPath($EvidenceDirectory)
$dockerRoot = Get-Task19DockerHostPath $root
$dockerEvidenceDirectory = Get-Task19DockerHostPath $EvidenceDirectory
$securityTemporaryDirectory = Join-Path $root "target\task19-temp\$project-security"
$sourceSnapshotDirectory = Join-Path $securityTemporaryDirectory "source"
$dockerSourceSnapshotDirectory = Get-Task19DockerHostPath $sourceSnapshotDirectory
$securityTemporaryDirectoryOwned = $false
if (Test-Path -LiteralPath $EvidenceDirectory) {
    throw "Refusing to overwrite TASK-19 security evidence"
}
New-Item -ItemType Directory -Force $EvidenceDirectory | Out-Null
$failures = [System.Collections.Generic.List[string]]::new()
$results = [ordered]@{}
$images = [ordered]@{
    portal = "$project-portal:scan"
    admin = "$project-admin:scan"
    task = "$project-task:scan"
    agent = "$project-agent:scan"
    agentTest = "$project-agent-test:scan"
    agentSecurityTest = "$project-agent-security-test:scan"
    web = "$project-web:scan"
    webTest = "$project-web-test:scan"
    rabbitmq = "$project-rabbitmq:scan"
}
$imageIds = [ordered]@{}
$trivyCacheVolume = "$project-trivy-cache"
$trivyCacheCreated = $false

$scannerImages = [ordered]@{
    gitleaks = "zricethezav/gitleaks@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854"
    osv = "ghcr.io/google/osv-scanner@sha256:fd62daecbaaed24442031574540e8af6dbaf0ab0a3e28c626bec0dd235a28346"
    trivy = "aquasec/trivy@sha256:086971aaf400beebd94e8300fd8ea623774419597169156cec56eec5b00dfb1e"
    semgrep = "semgrep/semgrep@sha256:cda1b566fafbf6010a02a3ea1d265b1c8eba4380e489a13891a102243d81ca6f"
    zap = "zaproxy/zap-stable@sha256:7840969c7c9fead565bf9734b12f49f6886db90b1d35b1f74d79710bbd081dab"
}

function Invoke-SecurityGate {
    param(
        [string]$Name,
        [scriptblock]$Action,
        [string]$RequiredReport = ""
    )
    $started = [DateTimeOffset]::UtcNow
    try {
        & $Action
        if ($RequiredReport) {
            if (-not (Test-Path -LiteralPath $RequiredReport -PathType Leaf) -or
                    (Get-Item -LiteralPath $RequiredReport).Length -eq 0) {
                throw "scanner did not create a nonempty machine-readable report"
            }
            try { Get-Content -LiteralPath $RequiredReport -Raw | ConvertFrom-Json | Out-Null }
            catch { throw "scanner report is not valid JSON" }
        }
        $script:results[$Name] = [ordered]@{
            status = "PASSED"
            startedAt = $started
            finishedAt = [DateTimeOffset]::UtcNow
            report = if ($RequiredReport) { [System.IO.Path]::GetFileName($RequiredReport) } else { $null }
        }
    }
    catch {
        $script:results[$Name] = [ordered]@{
            status = "FAILED"
            startedAt = $started
            finishedAt = [DateTimeOffset]::UtcNow
            error = $_.Exception.Message
            report = if ($RequiredReport -and (Test-Path -LiteralPath $RequiredReport)) {
                [System.IO.Path]::GetFileName($RequiredReport)
            } else { $null }
        }
        [void]$script:failures.Add("$Name`: $($_.Exception.Message)")
    }
}

function Invoke-ContainerCommand([string[]]$Arguments, [string]$OutputPath) {
    $attemptOutput = [System.Collections.Generic.List[string]]::new()
    $exitCode = 1
    foreach ($attempt in 1..2) {
        $output = @(& docker @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
        [void]$attemptOutput.Add("=== attempt $attempt exit $exitCode ===`n$($output -join "`n")")
        if ($exitCode -ne 139) { break }
    }
    $safe = ($attemptOutput -join "`n") `
        -replace 'eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}', '[REDACTED_JWT]' `
        -replace '-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----[\s\S]*?-----END (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----', '[REDACTED_PRIVATE_KEY]'
    Set-Content -LiteralPath $OutputPath -Value $safe -Encoding UTF8
    if ($exitCode -ne 0) { throw "container command exited $exitCode" }
}

function Invoke-ScannerCommand([string[]]$Arguments, [string]$OutputPath) {
    $output = @(& docker @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $safe = ($output -join "`n") `
        -replace 'eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}', '[REDACTED_JWT]' `
        -replace '-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----[\s\S]*?-----END (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----', '[REDACTED_PRIVATE_KEY]'
    Set-Content -LiteralPath $OutputPath -Value $safe -Encoding UTF8
    if ($exitCode -notin @(0, 1)) { throw "scanner infrastructure exited $exitCode" }
}

function Assert-NoSensitiveEvidence([string]$Directory) {
    $patterns = @(
        'eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}',
        '-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----',
        '(?i)Authorization\s*:\s*Bearer\s+[A-Za-z0-9_-]',
        '(?i)(?:Cookie|Set-Cookie)\s*:\s*\S+',
        'Task19Browser!2026',
        'Task13Admin!2026'
    )
    $leaks = [System.Collections.Generic.List[string]]::new()
    foreach ($file in Get-ChildItem -LiteralPath $Directory -Recurse -File | Where-Object {
        $_.Extension -in @('.json', '.txt', '.xml', '.html', '.log')
    }) {
        $content = Get-Content -LiteralPath $file.FullName -Raw -ErrorAction Stop
        foreach ($pattern in $patterns) {
            if ($content -match $pattern) {
                [void]$leaks.Add([System.IO.Path]::GetRelativePath($Directory, $file.FullName))
                break
            }
        }
    }
    [ordered]@{
        schemaVersion = "1.0"
        scannedDirectory = [System.IO.Path]::GetFileName($Directory)
        leakingFiles = @($leaks)
        passed = $leaks.Count -eq 0
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath `
        (Join-Path $Directory "sensitive-evidence-policy.json") -Encoding UTF8
    if ($leaks.Count) { throw "dynamic evidence contains sensitive-value patterns" }
}

function Assert-OsvCriticalHighPolicy([string]$ReportPath, [string]$PolicyPath) {
    $report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
    $findings = @(
        foreach ($result in @($report.results)) {
            foreach ($package in @($result.packages)) {
                foreach ($vulnerability in @($package.vulnerabilities)) {
                    $severity = [string]$vulnerability.database_specific.severity
                    if ($severity -in @("HIGH", "CRITICAL")) {
                        [ordered]@{
                            source = [string]$result.source.path
                            package = [string]$package.package.name
                            version = [string]$package.package.version
                            id = [string]$vulnerability.id
                            severity = $severity
                        }
                    }
                }
            }
        }
    )
    [ordered]@{
        schemaVersion = "1.0"
        criticalHighFindings = $findings
        passed = $findings.Count -eq 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $PolicyPath -Encoding UTF8
    if ($findings.Count) { throw "OSV reported $($findings.Count) Critical/High dependency findings" }
}

function Get-TrivyCriticalHighFindings([object]$Report) {
    @(
        foreach ($result in @($Report.Results)) {
            $vulnerabilities = if ($null -ne $result.PSObject.Properties["Vulnerabilities"]) {
                @($result.Vulnerabilities)
            } else { @() }
            $secrets = if ($null -ne $result.PSObject.Properties["Secrets"]) {
                @($result.Secrets)
            } else { @() }
            foreach ($finding in @($vulnerabilities) + @($secrets)) {
                if ([string]$finding.Severity -in @("HIGH", "CRITICAL")) {
                    [ordered]@{
                        target = [string]$result.Target
                        id = if ($finding.VulnerabilityID) { [string]$finding.VulnerabilityID } else { [string]$finding.RuleID }
                        severity = [string]$finding.Severity
                    }
                }
            }
        }
    )
}

function Assert-TrivyCriticalHighPolicy([string]$ReportPath, [string]$PolicyPath) {
    $report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
    $findings = @(Get-TrivyCriticalHighFindings $report)
    [ordered]@{
        schemaVersion = "1.0"
        criticalHighFindings = $findings
        passed = $findings.Count -eq 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $PolicyPath -Encoding UTF8
    if ($findings.Count) { throw "Trivy reported $($findings.Count) Critical/High vulnerability or secret findings" }
}

function Assert-TrivyMisconfigurationPolicy([string]$ReportPath, [string]$PolicyPath) {
    $report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
    $waiverDocument = Get-Content -LiteralPath (Join-Path $script:Task19Root "docs\security\task19-scan-waivers.json") -Raw | ConvertFrom-Json
    $today = [DateOnly]::FromDateTime([DateTime]::UtcNow)
    $findings = @(
        foreach ($result in @($report.Results)) {
            $misconfigurations = if ($null -ne $result.PSObject.Properties["Misconfigurations"]) {
                @($result.Misconfigurations)
            } else { @() }
            foreach ($finding in $misconfigurations) {
                if ([string]$finding.Severity -in @("HIGH", "CRITICAL")) {
                    [ordered]@{
                        target = ([string]$result.Target).Replace("\", "/")
                        id = [string]$finding.ID
                        severity = [string]$finding.Severity
                    }
                }
            }
        }
    )
    $unhandled = [System.Collections.Generic.List[object]]::new()
    $usedWaivers = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($finding in $findings) {
        $matching = @($waiverDocument.waivers | Where-Object {
            [string]$_.scanner -ceq "trivy" -and [string]$_.id -ceq $finding.id -and
            ([string]$_.target).Replace("\", "/") -ceq $finding.target -and
            [string]$_.severity -ceq $finding.severity
        })
        if ($matching.Count -ne 1) {
            [void]$unhandled.Add($finding)
            continue
        }
        $expiry = [DateOnly]::ParseExact([string]$matching[0].expiresOn, "yyyy-MM-dd")
        if ($expiry -lt $today -or -not $matching[0].owner -or -not $matching[0].reason -or -not $matching[0].remediation) {
            [void]$unhandled.Add($finding)
            continue
        }
        [void]$usedWaivers.Add("$($finding.id)|$($finding.target)|$($finding.severity)")
    }
    $staleWaivers = @($waiverDocument.waivers | Where-Object {
        -not $usedWaivers.Contains("$($_.id)|$(([string]$_.target).Replace('\', '/'))|$($_.severity)")
    })
    [ordered]@{
        schemaVersion = "1.0"
        criticalHighFindings = $findings
        handledByExactUnexpiredWaiver = $findings.Count - $unhandled.Count
        unhandledFindings = @($unhandled)
        staleWaivers = $staleWaivers
        passed = $unhandled.Count -eq 0 -and $staleWaivers.Count -eq 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $PolicyPath -Encoding UTF8
    if ($unhandled.Count) { throw "Trivy reported $($unhandled.Count) unhandled Critical/High misconfigurations" }
    if ($staleWaivers.Count) { throw "Trivy waiver file contains $($staleWaivers.Count) unmatched entries" }
}

function Build-Image([string]$Name, [string[]]$Arguments) {
    & docker image inspect $script:images[$Name] 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        throw "Refusing to overwrite security image $($script:images[$Name])"
    }
    if ($LASTEXITCODE -ne 1) { throw "image preflight failed for $Name" }
    $output = @(& docker build @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        Set-Content -LiteralPath (Join-Path $script:EvidenceDirectory "build-$Name.txt") `
            -Value ($output -join "`n") -Encoding UTF8
        $partial = @(& docker image inspect $script:images[$Name] 2>$null | ConvertFrom-Json)
        if ($LASTEXITCODE -eq 0 -and $partial.Count -eq 1) {
            $script:imageIds[$script:images[$Name]] = [string]$partial[0].Id
        }
        throw "image build failed for $Name"
    }
    $object = @(& docker image inspect $script:images[$Name] | ConvertFrom-Json)
    if ($object.Count -ne 1) { throw "built image identity unavailable for $Name" }
    $script:imageIds[$script:images[$Name]] = [string]$object[0].Id
}

Set-Location $root
try {
    Assert-Task19Preflight $project @($images.Values) $securityTemporaryDirectory
    New-Item -ItemType Directory $sourceSnapshotDirectory | Out-Null
    $securityTemporaryDirectoryOwned = $true
    & docker volume inspect $trivyCacheVolume 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { throw "Refusing pre-existing Trivy cache volume $trivyCacheVolume" }
    if ($LASTEXITCODE -ne 1) { throw "Trivy cache volume preflight failed" }
    & docker volume create `
        --label "com.hotshop.task19.project=$project" `
        --label "com.hotshop.task19.purpose=trivy-cache" `
        $trivyCacheVolume | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not create owned Trivy cache volume" }
    $trivyCacheCreated = $true
    Invoke-SecurityGate "gitleaks-history" {
        Invoke-ContainerCommand @(
            "run", "--rm", "--volume", "${dockerRoot}:/repo:ro",
            "--mount", "type=bind,source=$dockerEvidenceDirectory,target=/reports",
            "--workdir", "/repo", $scannerImages.gitleaks,
            "git", "--redact", "--config", ".gitleaks.toml", "--log-opts=HEAD", "--no-banner",
            "--report-format=json", "--report-path=/reports/gitleaks-history.json"
        ) (Join-Path $EvidenceDirectory "gitleaks-history-output.txt")
    } (Join-Path $EvidenceDirectory "gitleaks-history.json")

    Invoke-SecurityGate "gitleaks-working-tree" {
        Invoke-ContainerCommand @(
            "run", "--rm", "--volume", "${dockerRoot}:/repo:ro",
            "--mount", "type=bind,source=$dockerSourceSnapshotDirectory,target=/scan",
            "--workdir", "/repo", "--entrypoint", "sh", $scannerImages.gitleaks, "-c", @'
set -eu
git -c safe.directory=/repo ls-files --cached --others --exclude-standard |
    tar -T - -cf - |
    tar -C /scan -xf -
'@
        ) (Join-Path $EvidenceDirectory "source-snapshot-output.txt")
        Invoke-ContainerCommand @(
            "run", "--rm", "--volume", "${dockerRoot}:/repo:ro",
            "--mount", "type=bind,source=$dockerSourceSnapshotDirectory,target=/scan,readonly",
            "--mount", "type=bind,source=$dockerEvidenceDirectory,target=/reports",
            "--workdir", "/repo", $scannerImages.gitleaks,
            "dir", "--redact", "--config", "/repo/.gitleaks.toml", "--no-banner",
            "--report-format=json", "--report-path=/reports/gitleaks-working-tree.json", "/scan"
        ) (Join-Path $EvidenceDirectory "gitleaks-working-tree-output.txt")
    } (Join-Path $EvidenceDirectory "gitleaks-working-tree.json")

    Invoke-SecurityGate "osv-java-python-pnpm" {
        Invoke-ScannerCommand @(
            "run", "--rm", "--mount", "type=bind,source=$dockerSourceSnapshotDirectory,target=/repo,readonly",
            "--mount", "type=bind,source=$dockerEvidenceDirectory,target=/reports",
            "--workdir", "/repo", $scannerImages.osv,
            "scan", "source", "--recursive", "--format=json", "--output=/reports/osv.json", "."
        ) (Join-Path $EvidenceDirectory "osv-output.txt")
        Assert-OsvCriticalHighPolicy (Join-Path $EvidenceDirectory "osv.json") `
            (Join-Path $EvidenceDirectory "osv-policy.json")
    } (Join-Path $EvidenceDirectory "osv.json")

    Invoke-SecurityGate "trivy-filesystem-high-critical" {
        foreach ($attempt in 1..2) {
            $attemptOutput = Join-Path $EvidenceDirectory "trivy-filesystem-attempt-$attempt-output.txt"
            Invoke-ScannerCommand @(
                "run", "--rm", "--mount", "type=bind,source=$dockerSourceSnapshotDirectory,target=/repo,readonly",
                "--volume", "${trivyCacheVolume}:/root/.cache/trivy",
                "--mount", "type=bind,source=$dockerEvidenceDirectory,target=/reports",
                $scannerImages.trivy, "filesystem", "--scanners", "vuln,secret",
                "--severity", "HIGH,CRITICAL", "--exit-code", "1", "--format", "json",
                "--output", "/reports/trivy-filesystem.json", "/repo"
            ) $attemptOutput
            if (Test-Path -LiteralPath (Join-Path $EvidenceDirectory "trivy-filesystem.json") -PathType Leaf) { break }
            $detail = Get-Content -LiteralPath $attemptOutput -Raw
            if ($attempt -eq 2 -or $detail -notmatch '(?i)context deadline exceeded|failed to download|invalid tar header') { break }
        }
        Assert-TrivyCriticalHighPolicy (Join-Path $EvidenceDirectory "trivy-filesystem.json") `
            (Join-Path $EvidenceDirectory "trivy-filesystem-policy.json")
    } (Join-Path $EvidenceDirectory "trivy-filesystem.json")

    Invoke-SecurityGate "trivy-filesystem-misconfiguration-policy" {
        foreach ($attempt in 1..2) {
            $attemptOutput = Join-Path $EvidenceDirectory "trivy-filesystem-misconfig-attempt-$attempt-output.txt"
            Invoke-ScannerCommand @(
                "run", "--rm", "--mount", "type=bind,source=$dockerSourceSnapshotDirectory,target=/repo,readonly",
                "--volume", "${trivyCacheVolume}:/root/.cache/trivy",
                "--mount", "type=bind,source=$dockerEvidenceDirectory,target=/reports",
                $scannerImages.trivy, "filesystem", "--scanners", "misconfig",
                "--severity", "HIGH,CRITICAL", "--exit-code", "1", "--format", "json",
                "--output", "/reports/trivy-filesystem-misconfig.json", "/repo"
            ) $attemptOutput
            if (Test-Path -LiteralPath (Join-Path $EvidenceDirectory "trivy-filesystem-misconfig.json") -PathType Leaf) { break }
            $detail = Get-Content -LiteralPath $attemptOutput -Raw
            if ($attempt -eq 2 -or $detail -notmatch '(?i)context deadline exceeded|failed to download|invalid tar header') { break }
        }
        Assert-TrivyMisconfigurationPolicy (Join-Path $EvidenceDirectory "trivy-filesystem-misconfig.json") `
            (Join-Path $EvidenceDirectory "trivy-filesystem-misconfig-policy.json")
    } (Join-Path $EvidenceDirectory "trivy-filesystem-misconfig.json")

    Invoke-SecurityGate "semgrep-local-security-rules" {
        Invoke-ContainerCommand @(
            "run", "--rm", "--network", "none",
            "--mount", "type=bind,source=$dockerSourceSnapshotDirectory,target=/src,readonly",
            "--mount", "type=bind,source=$dockerEvidenceDirectory,target=/reports",
            "--workdir", "/src", $scannerImages.semgrep,
            "semgrep", "scan", "--config", ".semgrep/task19.yml", "--error",
            "--json", "--output", "/reports/semgrep.json"
        ) (Join-Path $EvidenceDirectory "semgrep-output.txt")
    } (Join-Path $EvidenceDirectory "semgrep.json")

    Invoke-SecurityGate "deterministic-security-regression" {
        Invoke-ContainerCommand @(
            "run", "--rm", "--volume", "${dockerRoot}:/workspace",
            "--volume", "/var/run/docker.sock:/var/run/docker.sock", "--workdir", "/workspace",
            "-e", "TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal",
            "eclipse-temurin:21-jdk@sha256:efd34b940f2d5a621605c8531c2afb7759c936b6c2ef637a69aa3bf3e1e789d1",
            "sh", "./mvnw", "-B", "-ntp", "-pl", "portal,security", "-am",
            "-Dtest=IdentitySecurityTest,RefreshCookieServiceTest,AuditSensitiveDataSanitizerTest,AgentToolsAndPurchaseConfirmationIntegrationTest",
            "-Dsurefire.failIfNoSpecifiedTests=false", "test"
        ) (Join-Path $EvidenceDirectory "security-regression-java-portal.txt")
        Invoke-ContainerCommand @(
            "run", "--rm", "--volume", "${dockerRoot}:/workspace",
            "--volume", "/var/run/docker.sock:/var/run/docker.sock", "--workdir", "/workspace",
            "-e", "TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal",
            "eclipse-temurin:21-jdk@sha256:efd34b940f2d5a621605c8531c2afb7759c936b6c2ef637a69aa3bf3e1e789d1",
            "sh", "./mvnw", "-B", "-ntp", "-pl", "admin", "-am",
            "-Dtest=AdminIdentitySecurityTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test"
        ) (Join-Path $EvidenceDirectory "security-regression-java-admin.txt")
    }

    Invoke-SecurityGate "build-final-images" {
        Build-Image "portal" @("--tag", $images.portal, "--build-arg", "MODULE=portal", "--file", "Dockerfile", ".")
        Build-Image "admin" @("--tag", $images.admin, "--build-arg", "MODULE=admin", "--file", "Dockerfile", ".")
        Build-Image "task" @("--tag", $images.task, "--build-arg", "MODULE=task", "--file", "Dockerfile", ".")
        Build-Image "agent" @("--tag", $images.agent, "--file", "agent/Dockerfile", "agent")
        Build-Image "agentTest" @("--tag", $images.agentTest, "--target", "test", "--file", "agent/Dockerfile", "agent")
        Build-Image "agentSecurityTest" @("--tag", $images.agentSecurityTest, "--target", "security-test", "--file", "agent/Dockerfile", "agent")
        Build-Image "web" @("--tag", $images.web, "--target", "runtime", "--file", "web/Dockerfile", "web")
        Build-Image "webTest" @("--tag", $images.webTest, "--target", "test", "--file", "web/Dockerfile", "web")
        Build-Image "rabbitmq" @("--tag", $images.rabbitmq, "--file", "docker/rabbitmq/Dockerfile", ".")
    }

    Invoke-SecurityGate "agent-security-pytest" {
        Invoke-ContainerCommand @(
            "run", "--rm", "--network", "none", "-e", "PYTEST_DISABLE_PLUGIN_AUTOLOAD=1",
            "-e", "AGENT_MODEL_PROVIDER=fake", "-e", "AGENT_EMBEDDING_PROVIDER=deterministic",
            "--entrypoint", "python", $images.agentTest, "-m", "pytest",
            "tests/test_security.py", "tests/test_registry.py", "tests/test_reliability.py",
            "tests/test_cancellation.py",
            "-p", "pytest_asyncio.plugin", "-p", "no:cacheprovider"
        ) (Join-Path $EvidenceDirectory "security-regression-agent-core.txt")
        Invoke-ContainerCommand @(
            "run", "--rm", "--network", "none", "-e", "PYTEST_DISABLE_PLUGIN_AUTOLOAD=1",
            "-e", "AGENT_MODEL_PROVIDER=fake", "-e", "AGENT_EMBEDDING_PROVIDER=deterministic",
            "--entrypoint", "python", $images.agentTest, "-m", "pytest",
            "tests/test_streaming_sanitizer.py",
            "-p", "pytest_asyncio.plugin", "-p", "no:cacheprovider"
        ) (Join-Path $EvidenceDirectory "security-regression-agent-streaming.txt")
    }

    Invoke-SecurityGate "agent-runtime-container-security" {
        Invoke-ContainerCommand @(
            "run", "--rm", "--network", "none", "--user", "0:0",
            "--volume", "/var/run/docker.sock:/var/run/docker.sock",
            "-e", "PYTEST_DISABLE_PLUGIN_AUTOLOAD=1",
            "-e", "AGENT_CONTAINER_IMAGE=$($images.agent)",
            "-e", "TASK19_PROJECT=$project",
            "--entrypoint", "python", $images.agentSecurityTest, "-m", "pytest",
            "tests/test_container_security.py", "-p", "pytest_asyncio.plugin",
            "-p", "no:cacheprovider"
        ) (Join-Path $EvidenceDirectory "security-regression-agent-container.txt")
    }

    Invoke-SecurityGate "web-security-tests" {
        Invoke-ContainerCommand @(
            "run", "--rm", "--network", "none", "--entrypoint", "pnpm", $images.webTest,
            "test", "--", "src/auth/no-persistence.test.ts", "src/features/agent/agent-stream.test.ts",
            "src/auth/auth-domain.test.ts", "src/api/core/problem.test.ts"
        ) (Join-Path $EvidenceDirectory "security-regression-web.txt")
    }

    foreach ($entry in $images.GetEnumerator() | Where-Object { $_.Key -notin @("agentTest", "agentSecurityTest", "webTest") }) {
        $report = Join-Path $EvidenceDirectory "trivy-image-$($entry.Key).json"
        Invoke-SecurityGate "trivy-image-$($entry.Key)-high-critical" {
            foreach ($attempt in 1..2) {
                $attemptOutput = Join-Path $EvidenceDirectory "trivy-image-$($entry.Key)-attempt-$attempt-output.txt"
                Invoke-ScannerCommand @(
                    "run", "--rm", "--volume", "/var/run/docker.sock:/var/run/docker.sock",
                    "--volume", "${trivyCacheVolume}:/root/.cache/trivy",
                    "--mount", "type=bind,source=$dockerEvidenceDirectory,target=/reports",
                    $scannerImages.trivy, "image", "--severity", "HIGH,CRITICAL", "--exit-code", "1",
                    "--format", "json", "--output", "/reports/$([System.IO.Path]::GetFileName($report))",
                    [string]$entry.Value
                ) $attemptOutput
                if (Test-Path -LiteralPath $report -PathType Leaf) { break }
                $detail = Get-Content -LiteralPath $attemptOutput -Raw
                if ($attempt -eq 2 -or $detail -notmatch '(?i)Java DB update failed|invalid tar header|failed to download artifact') {
                    break
                }
            }
            Assert-TrivyCriticalHighPolicy $report `
                (Join-Path $EvidenceDirectory "trivy-image-$($entry.Key)-policy.json")
        } $report
    }

    Invoke-SecurityGate "zap-passive-baseline" {
        & (Join-Path $PSScriptRoot "verify-task19-e2e.ps1") `
            -ProjectName $project -EvidenceDirectory (Join-Path $EvidenceDirectory "zap-stack") `
            -TimeoutSeconds $TimeoutSeconds -RunZapBaseline
    } (Join-Path $EvidenceDirectory "zap-stack\zap.json")

    Invoke-SecurityGate "artifact-sensitive-data-regression" {
        Assert-NoSensitiveEvidence $EvidenceDirectory
    } (Join-Path $EvidenceDirectory "sensitive-evidence-policy.json")
}
finally {
    $ownedProjectResources = [ordered]@{containers = @(); volumes = @(); networks = @()}
    try {
        $projectQuery = Get-Task19ProjectResources $project
        if ($projectQuery.Success) { $ownedProjectResources = $projectQuery.Resources }
        else { [void]$failures.Add("project resource discovery failed before cleanup: $($projectQuery.Failures -join '; ')") }
    }
    catch { [void]$failures.Add("project resource discovery failed before cleanup") }
    foreach ($entry in @(
        @{Type = "container"; Items = @($ownedProjectResources.containers); Remove = @("container", "rm", "--force")},
        @{Type = "volume"; Items = @($ownedProjectResources.volumes); Remove = @("volume", "rm", "--force")},
        @{Type = "network"; Items = @($ownedProjectResources.networks); Remove = @("network", "rm")}
    )) {
        foreach ($identifier in $entry.Items) {
            try {
                $label = Get-Task19ResourceProjectLabel $entry.Type $identifier
                if ($null -eq $label) { continue }
                if ($label -cne $project) {
                    [void]$failures.Add("refused $($entry.Type) with changed project identity")
                    continue
                }
                & docker @($entry.Remove + $identifier) 2>$null | Out-Null
                if ($LASTEXITCODE -ne 0) {
                    [void]$failures.Add("could not remove owned $($entry.Type)")
                }
            }
            catch { [void]$failures.Add("$($entry.Type) cleanup exception") }
        }
    }
    foreach ($tag in @($imageIds.Keys)) {
        try {
            $current = @(& docker image inspect $tag 2>$null | ConvertFrom-Json)
            if ($LASTEXITCODE -eq 0 -and $current.Count -eq 1 -and
                    [string]$current[0].Id -ceq [string]$imageIds[$tag]) {
                & docker image rm --force $tag 2>$null | Out-Null
                if ($LASTEXITCODE -ne 0) { [void]$failures.Add("could not remove owned image $tag") }
            }
            elseif ($LASTEXITCODE -eq 0) {
                [void]$failures.Add("refused changed image identity $tag")
            }
        }
        catch { [void]$failures.Add("image cleanup exception for $tag") }
    }
    if ($trivyCacheCreated) {
        try {
            $volume = @(& docker volume inspect $trivyCacheVolume 2>$null | ConvertFrom-Json)
            if ($LASTEXITCODE -eq 0 -and $volume.Count -eq 1 -and
                    [string]$volume[0].Labels."com.hotshop.task19.project" -ceq $project -and
                    [string]$volume[0].Labels."com.hotshop.task19.purpose" -ceq "trivy-cache") {
                & docker volume rm $trivyCacheVolume 2>$null | Out-Null
                if ($LASTEXITCODE -ne 0) { [void]$failures.Add("could not remove owned Trivy cache volume") }
            }
            elseif ($LASTEXITCODE -eq 0) {
                [void]$failures.Add("refused Trivy cache volume with changed identity")
            }
        }
        catch { [void]$failures.Add("Trivy cache volume cleanup exception") }
    }
    if ($securityTemporaryDirectoryOwned) {
        try {
            $expectedTemporaryRoot = [System.IO.Path]::GetFullPath((Join-Path $root "target\task19-temp"))
            $resolvedTemporary = [System.IO.Path]::GetFullPath($securityTemporaryDirectory)
            if (-not $resolvedTemporary.StartsWith($expectedTemporaryRoot + [System.IO.Path]::DirectorySeparatorChar,
                    [StringComparison]::OrdinalIgnoreCase)) {
                [void]$failures.Add("refused security source snapshot outside task19 temp root")
            }
            elseif (Test-Path -LiteralPath $resolvedTemporary) {
                Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force
            }
        }
        catch { [void]$failures.Add("security source snapshot cleanup exception: $($_.Exception.Message)") }
    }
}

$securityTemporaryRemaining = Test-Path -LiteralPath $securityTemporaryDirectory
if ($securityTemporaryRemaining) { [void]$failures.Add("security source snapshot remains") }
$remainingImages = @($imageIds.Keys | Where-Object {
    & docker image inspect $_ 2>$null | Out-Null
    $LASTEXITCODE -eq 0
})
& docker volume inspect $trivyCacheVolume 2>$null | Out-Null
$trivyCacheRemaining = $LASTEXITCODE -eq 0
$remainingProjectResources = [ordered]@{containers = @("query-failed"); volumes = @(); networks = @()}
try {
    $remainingQuery = Get-Task19ProjectResources $project
    if ($remainingQuery.Success) { $remainingProjectResources = $remainingQuery.Resources }
    else { [void]$failures.Add("project resource verification failed after cleanup: $($remainingQuery.Failures -join '; ')") }
}
catch { [void]$failures.Add("project resource verification failed after cleanup") }
[ordered]@{
    schemaVersion = "1.0"
    project = $project
    scanners = [ordered]@{
        gitleaks = "8.28.0"
        osvScanner = "2.2.2"
        trivy = "0.66.0"
        semgrep = "1.136.0"
        zap = "2.16.1"
    }
    gates = $results
    criticalHighPolicy = "zero unhandled findings; no broad allowlist"
    ownedImagesRemaining = $remainingImages.Count
    ownedVolumesRemaining = if ($trivyCacheRemaining) { 1 } else { 0 }
    ownedContainersRemaining = @($remainingProjectResources.containers).Count
    ownedComposeVolumesRemaining = @($remainingProjectResources.volumes).Count
    ownedNetworksRemaining = @($remainingProjectResources.networks).Count
    ownedTemporaryDirectoriesRemaining = if ($securityTemporaryRemaining) { 1 } else { 0 }
    passed = $failures.Count -eq 0 -and $remainingImages.Count -eq 0 -and
        -not $trivyCacheRemaining -and @($remainingProjectResources.containers).Count -eq 0 -and
        @($remainingProjectResources.volumes).Count -eq 0 -and
        @($remainingProjectResources.networks).Count -eq 0 -and -not $securityTemporaryRemaining
} | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory "summary.json") -Encoding UTF8

[ordered]@{
    project = $project
    containers = @($remainingProjectResources.containers).Count
    volumes = @($remainingProjectResources.volumes).Count + $(if ($trivyCacheRemaining) { 1 } else { 0 })
    networks = @($remainingProjectResources.networks).Count
    images = $remainingImages.Count
    temporaryKeyDirectories = if ($securityTemporaryRemaining) { 1 } else { 0 }
    clean = $remainingImages.Count -eq 0 -and -not $trivyCacheRemaining -and
        @($remainingProjectResources.containers).Count -eq 0 -and
        @($remainingProjectResources.volumes).Count -eq 0 -and
        @($remainingProjectResources.networks).Count -eq 0 -and -not $securityTemporaryRemaining
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory "cleanup.json") -Encoding UTF8

if ($remainingImages.Count) { [void]$failures.Add("owned security images remain") }
if ($trivyCacheRemaining) { [void]$failures.Add("owned Trivy cache volume remains") }
if (@($remainingProjectResources.containers).Count -or @($remainingProjectResources.volumes).Count -or
        @($remainingProjectResources.networks).Count) {
    [void]$failures.Add("owned security project resources remain")
}
if ($failures.Count) { throw "TASK-19 security verification failed: $($failures -join '; ')" }
Write-Output "TASK-19 security verification passed; evidence=$EvidenceDirectory"
