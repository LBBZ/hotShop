#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$sandbox = Join-Path ([IO.Path]::GetTempPath()) ('hotshop-lifecycle-' + [Guid]::NewGuid().ToString('N'))
$global:DemoCalls = [Collections.Generic.List[string]]::new()
$global:DemoImages = @{}
$global:DemoMigrationExit = '0'
$global:DemoMigratorMode = 'one'
$global:DemoUnusedImages = @()
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
            @{services=$services} | ConvertTo-Json -Depth 5 -Compress
            return
        }
        if ($args -contains 'build') {
            $service = $args[-1] -replace '-service$','' -replace '^web-demo$','web'
            $global:DemoImages["hotshop-${service}:local"] = 'sha256:' + [Guid]::NewGuid().ToString('N')
        } elseif ($call -like '*ps -a -q database-migrator') {
            switch ($global:DemoMigratorMode) { 'missing' { }; 'ambiguous' { 'one'; 'two' }; default { 'migrator' } }
        }
        elseif ($call -like '*SELECT*COUNT*') { '12' }
        return
    }
    if ($args[0] -eq 'wait') { $global:DemoMigrationExit; return }
    if ($args[0] -eq 'image' -and $args[1] -eq 'rm') { $global:DemoUnusedImages = @(); return }
    if ($args[0] -eq 'ps') { return }
    throw "Unexpected Docker operation: $call"
}
function git { $global:LASTEXITCODE = 0; 'agent/source.py'; 'portal/source.java' }
function Invoke-WebRequest { }
$savedProvider = $env:AGENT_MODEL_PROVIDER
try {
    New-Item -ItemType Directory -Force "$sandbox/script","$sandbox/agent","$sandbox/portal","$sandbox/.local/keys/hotshop" | Out-Null
    Copy-Item -LiteralPath "$root/script/demo.ps1" -Destination "$sandbox/script/demo.ps1"
    '${COMPOSE_PROJECT_NAME} ${AGENT_MODEL_PROVIDER}' | Set-Content "$sandbox/docker-compose.yml"
    '' | Set-Content "$sandbox/docker-compose.demo.yml"
    'agent-v1' | Set-Content "$sandbox/agent/source.py"
    'java-v1' | Set-Content "$sandbox/portal/source.java"
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
