[CmdletBinding()]
param([string]$OutputDirectory = '')
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $root 'target/openapi' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$target = [IO.Path]::GetFullPath((Join-Path $root 'target')) + [IO.Path]::DirectorySeparatorChar
if (-not $OutputDirectory.StartsWith($target, [StringComparison]::OrdinalIgnoreCase)) { throw 'Output must remain under target/.' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
foreach ($service in @('portal-service','admin-service','agent-service')) {
    $ids = @(docker ps -q --filter 'label=com.docker.compose.project=hotshop' --filter "label=com.docker.compose.service=$service")
    if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1) { throw "Start the hotshop environment before exporting $service." }
    $container = @(docker inspect $ids[0] | ConvertFrom-Json)[0]
    if ($LASTEXITCODE -ne 0) { throw 'Container inspection failed.' }
    $port = switch ($service) { 'admin-service' { '8088/tcp' }; 'agent-service' { '8090/tcp' }; default { '8080/tcp' } }
    $bindings = @($container.NetworkSettings.Ports.$port)
    if ($bindings.Count -ne 1 -or $bindings[0].HostIp -cne '127.0.0.1') { throw 'Export requires one loopback port binding.' }
    $base = "http://127.0.0.1:$($bindings[0].HostPort)"
    $documents = switch ($service) { 'portal-service' { @('public','user','mock-provider-callback') }; 'admin-service' { @('admin') }; default { @('agent') } }
    foreach ($document in $documents) {
        $path = if ($document -eq 'agent') { '/openapi.json' } else { "/v3/api-docs/$document" }
        $file = Join-Path $OutputDirectory "$document.json"
        Invoke-WebRequest "$base$path" -OutFile $file -TimeoutSec 30
        & python (Join-Path $PSScriptRoot 'canonicalize_openapi.py') $file
        if ($LASTEXITCODE -ne 0) { throw 'OpenAPI canonicalization failed.' }
    }
}
