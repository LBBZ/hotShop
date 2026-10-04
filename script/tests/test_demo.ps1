#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$sandbox = Join-Path ([IO.Path]::GetTempPath()) ('hotshop-lifecycle-' + [Guid]::NewGuid().ToString('N'))
$global:DemoCalls = [Collections.Generic.List[string]]::new()
$global:DemoImages = @{}
$global:DemoMigrationExit = '0'
$global:DemoMigratorMode = 'one'
$global:DemoUnusedImages = @()
$global:DemoBuildOption = 'v1'
$global:DemoReceipt = '0'
$global:DemoCount = '0'
$global:DemoSeedFails = $false
$global:DemoIndexFails = $false
$global:DemoSeedCalls = 0
function docker {
    $global:LASTEXITCODE = 0
    $call = $args -join ' '
    $global:DemoCalls.Add($call)
    if ($args[0] -eq 'image' -and $args[1] -eq 'ls') {
        if ($args -contains 'dangling=true') { $global:DemoUnusedImages; return }
        $tag = ($args[-1] -replace '^reference=','')
        if ($global:DemoImages.ContainsKey($tag)) { $global:DemoImages[$tag] }
        return
    }
    if ($args[0] -eq 'compose') {
        if ($call -like '*config --format json') {
            $services = @{}
            foreach ($name in @('admin-service','portal-service','task-service','agent-service','rabbitmq','web-demo')) {
                $services[$name] = @{build=@{context=$name}}
            }
            $services['agent-service'].build.args = @{option=$global:DemoBuildOption}
            @{services=$services} | ConvertTo-Json -Depth 5 -Compress
            return
        }
        if ($args -contains 'build') {
            $service = $args[-1] -replace '-service$','' -replace '^web-demo$','web'
            $global:DemoImages["hotshop-${service}:local"] = 'sha256:' + [Guid]::NewGuid().ToString('N')
        } elseif ($call -like '*ps -a -q database-migrator') {
            switch ($global:DemoMigratorMode) { 'missing' { }; 'ambiguous' { 'one'; 'two' }; default { 'migrator' } }
        }
        elseif ($call -like '*CREATE TABLE IF NOT EXISTS local_demo_initialization*') { $global:DemoReceipt }
        elseif ($call -like '*SELECT*COUNT*') { $global:DemoCount }
        elseif ($call -like '*mysql --default-character-set*') {
            $sql = @($input) -join "`n"
            if ([regex]::Matches($sql, '(?im)^START TRANSACTION;').Count -ne 1 -or [regex]::Matches($sql, '(?im)^COMMIT;').Count -ne 1 -or $sql -notmatch 'INSERT INTO local_demo_initialization') { throw 'Seed must have one transaction including its receipt.' }
            $global:DemoSeedCalls++
            if ($global:DemoSeedFails) { $global:LASTEXITCODE = 1 } else { $global:DemoReceipt = '1'; $global:DemoCount = '12' }
        }
        elseif ($call -like '*index_cli rebuild' -and $global:DemoIndexFails) { $global:LASTEXITCODE = 1 }
        return
    }
    if ($args[0] -eq 'wait') { $global:DemoMigrationExit; return }
    if ($args[0] -eq 'image' -and $args[1] -eq 'rm') { $global:DemoUnusedImages = @(); return }
    if ($args[0] -eq 'ps') { return }
    throw "Unexpected Docker operation: $call"
}
function git { $global:LASTEXITCODE = 0; 'agent/source.py'; 'portal/source.java'; 'infrastructure/source.java' }
function Invoke-WebRequest { }
function Invoke-RestMethod { @{accessToken='test-token'} }
$savedProvider = $env:AGENT_MODEL_PROVIDER
try {
    New-Item -ItemType Directory -Force "$sandbox/script","$sandbox/agent","$sandbox/portal","$sandbox/infrastructure","$sandbox/.local/keys/hotshop" | Out-Null
    Copy-Item -LiteralPath "$root/script/demo.ps1" -Destination "$sandbox/script/demo.ps1"
    '${COMPOSE_PROJECT_NAME} ${AGENT_MODEL_PROVIDER}' | Set-Content "$sandbox/docker-compose.yml"
    '' | Set-Content "$sandbox/docker-compose.demo.yml"
    'agent-v1' | Set-Content "$sandbox/agent/source.py"
    'java-v1' | Set-Content "$sandbox/portal/source.java"
    'shared-v1' | Set-Content "$sandbox/infrastructure/source.java"
    @'
COMPOSE_PROJECT_NAME=hotshop
ADMIN_IMAGE=hotshop-admin:local
PORTAL_IMAGE=hotshop-portal:local
TASK_IMAGE=hotshop-task:local
AGENT_IMAGE=hotshop-agent:local
RABBITMQ_PROJECT_IMAGE=hotshop-rabbitmq:local
WEB_DEMO_IMAGE=hotshop-web:local
WEB_DEMO_PORT=18080
AGENT_MODEL_PROVIDER=fake
'@ | Set-Content "$sandbox/.local/keys/hotshop/.env.demo"
    $env:AGENT_MODEL_PROVIDER = 'restore-me'
    & "$sandbox/script/demo.ps1" -Action Start | Out-Null
    if (@($global:DemoCalls | Where-Object { $_ -match ' build ' }).Count -ne 6) { throw 'First Start must build six missing images.' }
    $global:DemoCalls.Clear()
    $global:DemoUnusedImages = @('sha256:unused-child','sha256:unused-parent')
    & "$sandbox/script/demo.ps1" -Action Start | Out-Null
    if (@($global:DemoCalls | Where-Object { $_ -match ' build |--build|seed|index_cli' }).Count) { throw 'Repeated Start rebuilt or seeded existing data.' }
    if ($env:AGENT_MODEL_PROVIDER -cne 'restore-me') { throw 'Shell overrides were not restored.' }
    if (@($global:DemoCalls | Where-Object { $_ -like 'image rm *' }).Count -ne 1) { throw 'Cleanup tried to delete an image already removed with its child.' }
    'agent-v2' | Set-Content "$sandbox/agent/source.py"
    $global:DemoCalls.Clear()
    & "$sandbox/script/demo.ps1" -Action Start | Out-Null
    $builds = @($global:DemoCalls | Where-Object { $_ -match ' build ' })
    if ($builds.Count -ne 1 -or $builds[0] -notlike '*build agent-service') { throw 'Agent input change must build only Agent.' }
    $global:DemoBuildOption = 'v2'
    $global:DemoCalls.Clear()
    & "$sandbox/script/demo.ps1" -Action Start | Out-Null
    $builds = @($global:DemoCalls | Where-Object { $_ -match ' build ' })
    if ($builds.Count -ne 1 -or $builds[0] -notlike '*build agent-service') { throw 'Resolved build argument change must build Agent.' }
    'shared-v2' | Set-Content "$sandbox/infrastructure/source.java"
    $global:DemoCalls.Clear()
    & "$sandbox/script/demo.ps1" -Action Start | Out-Null
    $builds = @($global:DemoCalls | Where-Object { $_ -match ' build ' })
    if ($builds.Count -ne 3 -or @($builds | Where-Object { $_ -match 'build (agent-service|rabbitmq|web-demo)$' }).Count) { throw 'Shared Java inputs must rebuild the three Java services.' }
    $global:DemoMigrationExit = '7'
    $failed = $false
    try { & "$sandbox/script/demo.ps1" -Action Start | Out-Null } catch {
        if ($_.Exception.Message -notlike '*Database migration failed*') { throw }
        $failed = $true
    }
    if (-not $failed) { throw 'Failed migration was accepted.' }
    $global:DemoMigrationExit = '0'
    foreach ($mode in @('missing','ambiguous')) {
        $global:DemoMigratorMode = $mode
        $failed = $false
        try { & "$sandbox/script/demo.ps1" -Action Start | Out-Null } catch {
            if ($_.Exception.Message -notlike '*Expected one migrator container*') { throw }
            $failed = $true
        }
        if (-not $failed) { throw "Migrator $mode accepted." }
    }
    $global:DemoMigratorMode = 'one'
    # Existing empty databases have no bootstrap intent and must remain empty.
    if ($global:DemoSeedCalls -ne 0) { throw 'Existing empty database was seeded.' }
    New-Item -ItemType Directory -Force "$sandbox/web/scripts","$sandbox/database/data" | Out-Null
    Copy-Item "$root/web/scripts/task-13-e2e-seed.sql" "$sandbox/web/scripts/"
    Copy-Item "$root/database/data/demo-catalog.sql" "$sandbox/database/data/"
    $pending = "$sandbox/.local/keys/hotshop/bootstrap-pending"
    'database' | Set-Content $pending
    $global:DemoSeedFails = $true
    try { & "$sandbox/script/demo.ps1" -Action Start | Out-Null; throw 'Seed failure accepted.' }
    catch { if ($_.Exception.Message -notlike '*Demo seed failed*') { throw } }
    if (-not (Test-Path $pending) -or $global:DemoReceipt -ne '0') { throw 'Failed seed lost recovery state.' }
    $global:DemoSeedFails = $false
    $global:DemoIndexFails = $true
    try { & "$sandbox/script/demo.ps1" -Action Start | Out-Null; throw 'Index failure accepted.' }
    catch { if ($_.Exception.Message -notlike '*Docker command failed*') { throw } }
    $seedCalls = $global:DemoSeedCalls
    $global:DemoIndexFails = $false
    & "$sandbox/script/demo.ps1" -Action Start | Out-Null
    if ($global:DemoSeedCalls -ne $seedCalls -or (Test-Path $pending)) { throw 'Post-commit recovery repeated seed or stayed pending.' }
    'database' | Set-Content $pending
    $global:DemoReceipt = '0'
    try { & "$sandbox/script/demo.ps1" -Action Start | Out-Null; throw 'Existing data accepted for bootstrap.' }
    catch { if ($_.Exception.Message -notlike '*refusing to seed*') { throw } }
    Remove-Item $pending
    # A failed first key generation must retry with the same configuration.
    @'
param($OutputDirectory, [switch]$Resume)
if (-not $Resume) { throw 'Bootstrap must resume rather than rotate keys.' }
if ($global:DemoKeysFail) { throw 'Simulated key interruption.' }
'@ | Set-Content "$sandbox/script/generate-auth-keys.ps1"
    $configHash = (Get-FileHash "$sandbox/.local/keys/hotshop/.env.demo").Hash
    'keys' | Set-Content $pending
    $global:DemoKeysFail = $true
    try { & "$sandbox/script/demo.ps1" -Action Start | Out-Null; throw 'Key failure accepted.' }
    catch { if ($_.Exception.Message -notlike '*Simulated key interruption*') { throw } }
    if ((Get-Content -Raw $pending).Trim() -ne 'keys') { throw 'Key failure advanced bootstrap state.' }
    $global:DemoKeysFail = $false
    $global:DemoReceipt = '1'
    & "$sandbox/script/demo.ps1" -Action Start | Out-Null
    if ((Test-Path $pending) -or (Get-FileHash "$sandbox/.local/keys/hotshop/.env.demo").Hash -cne $configHash) { throw 'Key recovery lost progress or rotated configuration secrets.' }
    $lock = [IO.File]::Open((Join-Path $sandbox '.local/demo.lock'), [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        $global:DemoCalls.Clear()
        $failed = $false
        try { & "$sandbox/script/demo.ps1" -Action Start | Out-Null } catch { $failed = $true }
        if (-not $failed -or $global:DemoCalls.Count) { throw 'Concurrent lifecycle operation was not blocked before Docker.' }
    } finally { $lock.Dispose() }
    $savedActions = $env:GITHUB_ACTIONS
    try {
        $env:GITHUB_ACTIONS = $null
        $global:DemoCalls.Clear()
        $failed = $false
        try { & "$root/script/ci/generate-agent-openapi.ps1" | Out-Null } catch {
            if ($_.Exception.Message -notlike '*requires a GitHub-hosted runner*') { throw }
            $failed = $true
        }
        if (-not $failed -or $global:DemoCalls.Count) { throw 'Hosted integration entry point reached local Docker.' }
    } finally { $env:GITHUB_ACTIONS = $savedActions }
    'PASS: missing images built; repeated Start reuses images and data; Agent changes build only Agent; failed/missing/ambiguous migrations rejected; concurrent lifecycle blocked; shell restored.'
} finally {
    $env:AGENT_MODEL_PROVIDER = $savedProvider
    $resolved = [IO.Path]::GetFullPath($sandbox)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) { throw 'Test cleanup escaped temporary directory.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
