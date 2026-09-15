#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot '../task21-demo.ps1'
$global:Task21TestMode = 'existing'
function docker {
    $global:LASTEXITCODE = 0
    if ($args[0] -eq 'info') { 'test-daemon'; return }
    if ($args[0] -eq 'ps') {
        if ($global:Task21TestMode -eq 'failed-query') { $global:LASTEXITCODE = 42; return }
        'existing-container'; return
    }
    throw "Unexpected Docker operation in safety test: $args"
}
$old = $env:AGENT_MODEL_PROVIDER
try {
    $env:AGENT_MODEL_PROVIDER = 'must-restore'
    foreach ($case in @('existing','failed-query')) {
        $global:Task21TestMode = $case
        $project = 'hotshop-task21-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
        $thrown = $false
        try { & $scriptPath -ProjectName $project | Out-Null }
        catch {
            $thrown = $true
            $expected = if ($case -eq 'existing') { 'existing Docker resources' } else { 'Docker command failed' }
            if ($_.Exception.Message -notlike "*$expected*") { throw }
        }
        if (-not $thrown) { throw "$case was accepted" }
        if ($env:AGENT_MODEL_PROVIDER -ne 'must-restore') { throw 'Inherited shell environment was not restored.' }
        if (Test-Path (Join-Path $PSScriptRoot "../../.local/keys/$project")) { throw 'Preflight created files before ownership was established.' }
    }
    'PASS: existing project refusal; Docker query failure fails closed; shell restored; no files created.'
} finally { $env:AGENT_MODEL_PROVIDER = $old }

# Exercise the actual helper definitions without running Start or touching Docker.
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile([IO.Path]::GetFullPath($scriptPath), [ref]$null, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Demo script has parser errors.' }
foreach ($name in @('Invoke-Docker','Wait-DemoMigrator')) {
    $definition = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true)
    Invoke-Expression $definition.Extent.Text
}
function docker {
    $global:LASTEXITCODE = 0
    if ($args[0] -eq 'compose' -and ($args -join ' ') -eq 'compose ps -a -q database-migrator') {
        switch ($global:Task21TestMode) {
            'missing' { return }
            'ambiguous' { 'container-a'; 'container-b'; return }
            default { 'exited-migrator'; return }
        }
    }
    if ($args[0] -eq 'wait' -and $args[1] -eq 'exited-migrator') {
        if ($global:Task21TestMode -eq 'migration-failed') { '7' } else { '0' }
        return
    }
    throw "Unexpected migrator command: $args"
}
foreach ($case in @('exited-success','migration-failed','missing','ambiguous')) {
    $global:Task21TestMode = $case
    $caught = $false
    try { Wait-DemoMigrator @('compose') }
    catch {
        $caught = $true
        if ($case -eq 'exited-success') { throw }
        if ($_.Exception.Message -notmatch 'Database migration failed|Expected one migrator container') { throw }
    }
    if ($case -ne 'exited-success' -and -not $caught) { throw "Migrator case $case was accepted." }
}
'PASS: exited migrator success; container exit 7 rejected despite docker exit 0; missing and ambiguous IDs rejected.'
