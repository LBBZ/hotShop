#requires -Version 7.2
[CmdletBinding()]
param(
    [ValidateSet('Start','Status','Stop','Restart','Build','Logs','Config')][string]$Action = 'Start',
    [ValidateSet('admin-service','portal-service','task-service','agent-service','rabbitmq','web-demo')][string]$Service = '',
    [ValidateRange(1024,65535)][int]$WebPort = 18080,
    [ValidateRange(30,1800)][int]$TimeoutSeconds = 300
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$demoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$ProjectName = 'hotshop'
$demoDir = Join-Path $demoRoot ".local/keys/$ProjectName"
$demoEnv = Join-Path $demoDir '.env.demo'
$bootstrapFile = Join-Path $demoDir 'bootstrap-pending'
function Invoke-Docker([string[]]$Arguments) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed ($LASTEXITCODE). Resources retained; project: $ProjectName" }
}
function Wait-DemoMigrator([string[]]$ComposeArguments) {
    # Compose wait may omit exited one-shot services; identify it with ps -a.
    $ids = @(Invoke-Docker ($ComposeArguments + @('ps','-a','-q','database-migrator')) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($ids.Count -ne 1) { throw "Expected one migrator container, found $($ids.Count)." }
    $exitCodes = @(Invoke-Docker @('wait', $ids[0]))
    # docker wait itself exits 0 even when the container process failed.
    if ($exitCodes.Count -ne 1 -or $exitCodes[0].Trim() -cne '0') {
        throw 'Database migration failed or returned an ambiguous exit status; resources retained.'
    }
}
function Get-BuildFingerprint([string]$ServiceName, [string[]]$Paths, [string]$BuildDefinition) {
    $selected = @($Paths | Where-Object {
        $path = $_
        switch ($ServiceName) {
            'agent-service' { $path -like 'agent/*' }
            'web-demo' { $path -like 'web/*' }
            'rabbitmq' { $path -like 'docker/rabbitmq/*' }
            default { $path -match '^(common|infrastructure|domain|database|security|data|portal|admin|task|\.mvn)/|(^|/)pom\.xml$|^mvnw|^Dockerfile$' }
        }
    }) + @('.dockerignore','docker-compose.yml','docker-compose.demo.yml')
    $parts = foreach ($path in $selected | Sort-Object -Unique) {
        $absolute = Join-Path $demoRoot $path
        if (Test-Path -LiteralPath $absolute -PathType Leaf) { "$path`:$((Get-FileHash -LiteralPath $absolute -Algorithm SHA256).Hash)" }
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($parts -join "`n") + "`n$BuildDefinition")
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
}
function New-Secret { return [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant() }
$compose = @('compose','-p',$ProjectName,'--env-file',$demoEnv,'-f',(Join-Path $demoRoot 'docker-compose.yml'),'-f',(Join-Path $demoRoot 'docker-compose.demo.yml'),'--profile','app','--profile','agent')
$previous = @{}
$lifecycleLock = $null
try {
    $lockDir = Join-Path $demoRoot '.local'
    New-Item -ItemType Directory -Force -Path $lockDir | Out-Null
    $lifecycleLock = [IO.File]::Open((Join-Path $lockDir 'demo.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    # Prevent undisclosed shell overrides (including COMPOSE_PROFILES and image tags).
    $template = (Get-Content (Join-Path $demoRoot 'docker-compose.yml') -Raw) + (Get-Content (Join-Path $demoRoot 'docker-compose.demo.yml') -Raw)
    $names = @([regex]::Matches($template, '\$\{([A-Z][A-Z0-9_]*)') | ForEach-Object { $_.Groups[1].Value }) + @('COMPOSE_PROFILES','COMPOSE_FILE')
    foreach ($name in $names | Select-Object -Unique) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
        [Environment]::SetEnvironmentVariable($name,$null,'Process')
    }
    if ($Action -eq 'Start' -and -not (Test-Path -LiteralPath $demoEnv)) {
        if ((Test-Path -LiteralPath $demoDir) -and @(Get-ChildItem -LiteralPath $demoDir -Force).Count) {
            throw 'Demo configuration is missing but local state remains. Restore .env.demo before starting; credentials retained.'
        }
        Invoke-Docker @('info','--format','{{.ServerVersion}}')
        foreach ($query in @(
            @('ps','-aq','--filter',"label=com.docker.compose.project=$ProjectName"),
            @('volume','ls','-q','--filter',"label=com.docker.compose.project=$ProjectName"),
            @('network','ls','-q','--filter',"label=com.docker.compose.project=$ProjectName")
        )) {
            $existing = @(Invoke-Docker $query)
            if ($existing.Count) { throw 'Refusing a project with existing Docker resources.' }
        }
        $values = [ordered]@{
            COMPOSE_PROJECT_NAME=$ProjectName; HOTSHOP_SOURCE_ROOT=$demoRoot.Replace('\','/'); HOTSHOP_KEY_DIR=$demoDir.Replace('\','/')
            MYSQL_DATABASE='hotShop'; MYSQL_ROOT_PASSWORD=(New-Secret); REDIS_CACHE_PASSWORD=(New-Secret)
            REDIS_SECKILL_PASSWORD=(New-Secret); RABBITMQ_DEFAULT_USER='hotshop'; RABBITMQ_DEFAULT_PASS=(New-Secret)
            RABBITMQ_DEFAULT_VHOST='/'; TZ='UTC'; MYSQL_TIME_ZONE='+00:00'; SPRING_PROFILES_ACTIVE=''
            HOTSHOP_SECURE_COOKIES='false'; HOTSHOP_MOCK_PAYMENT_ENABLED='true'; HOTSHOP_MOCK_PAYMENT_SECRET=(New-Secret)
            HOTSHOP_MOCK_PAYMENT_CALLBACK_URL='http://portal-service:8080/provider-callbacks/v1/mock-payment'
            AGENT_MODEL_PROVIDER='fake'; AGENT_EMBEDDING_PROVIDER='deterministic'; AGENT_RAG_ENABLED='true'
            AGENT_QWEN_API_KEY=''; AGENT_DEEPSEEK_API_KEY=''; AGENT_BAILIAN_EMBEDDING_API_KEY=''
            ADMIN_IMAGE="hotshop-admin:local"; PORTAL_IMAGE="hotshop-portal:local"; TASK_IMAGE="hotshop-task:local"
            AGENT_IMAGE="hotshop-agent:local"; RABBITMQ_PROJECT_IMAGE="hotshop-rabbitmq:local"
            WEB_DEMO_IMAGE="hotshop-web:local"; WEB_DEMO_PORT="$WebPort"
        }
        New-Item -ItemType Directory -Force -Path $demoDir | Out-Null
        Set-Content -LiteralPath $bootstrapFile -Value 'keys' -Encoding utf8
        $values.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" } | Set-Content -LiteralPath "$demoEnv.tmp" -Encoding utf8
        [IO.File]::Move("$demoEnv.tmp", $demoEnv, $false)
    }
    if (-not (Test-Path -LiteralPath $demoEnv)) { throw 'No owned demo configuration found.' }
    # Explicit env file values win over inherited shell values; restore shell after execution.
    foreach ($line in Get-Content -LiteralPath $demoEnv | Where-Object { $_ -match '^[A-Z][A-Z0-9_]*=' }) {
        $name,$value = $line -split '=',2
        if (-not $previous.ContainsKey($name)) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
        [Environment]::SetEnvironmentVariable($name,$value,'Process')
    }
    Write-Host "Demo project: $ProjectName"
    if ($Action -eq 'Status') { Invoke-Docker ($compose + @('ps','-a')); return }
    if ($Action -eq 'Stop') { Invoke-Docker ($compose + @('stop')); return }
    if ($Action -eq 'Logs') { Invoke-Docker ($compose + @('logs','--tail','100')); return }
    Invoke-Docker ($compose + @('config','--quiet'))
    if ($Action -eq 'Config') { return }
    if ((Test-Path -LiteralPath $bootstrapFile) -and (Get-Content -Raw $bootstrapFile).Trim() -notin @('keys','database')) {
        throw 'Invalid bootstrap state; resources retained.'
    }
    if ($Action -ne 'Start' -and (Test-Path -LiteralPath $bootstrapFile) -and (Get-Content -Raw $bootstrapFile).Trim() -eq 'keys') {
        throw 'Incomplete bootstrap keys; run Start to resume.'
    }
    if ($Action -eq 'Start' -and (Test-Path -LiteralPath $bootstrapFile) -and (Get-Content -Raw $bootstrapFile).Trim() -eq 'keys') {
        & (Join-Path $PSScriptRoot 'generate-auth-keys.ps1') -OutputDirectory $demoDir -Resume
        Set-Content -LiteralPath "$bootstrapFile.tmp" -Value 'database' -Encoding utf8
        [IO.File]::Move("$bootstrapFile.tmp", $bootstrapFile, $true)
    }
    if ($env:COMPOSE_PROJECT_NAME -cne 'hotshop') { throw 'Local project must be hotshop.' }
    $images = [ordered]@{
        'admin-service'='hotshop-admin:local'; 'portal-service'='hotshop-portal:local'
        'task-service'='hotshop-task:local'; 'agent-service'='hotshop-agent:local'
        'rabbitmq'='hotshop-rabbitmq:local'; 'web-demo'='hotshop-web:local'
    }
    $imageVars = @('ADMIN_IMAGE','PORTAL_IMAGE','TASK_IMAGE','AGENT_IMAGE','RABBITMQ_PROJECT_IMAGE','WEB_DEMO_IMAGE')
    foreach ($name in $imageVars) {
        $expected = switch ($name) {
            'WEB_DEMO_IMAGE' { 'hotshop-web:local' }
            'RABBITMQ_PROJECT_IMAGE' { 'hotshop-rabbitmq:local' }
            default { 'hotshop-' + $name.Replace('_IMAGE','').ToLowerInvariant() + ':local' }
        }
        if ([Environment]::GetEnvironmentVariable($name,'Process') -cne $expected) { throw "Use the fixed local image tag $expected." }
    }
    if ($Action -eq 'Restart') {
        foreach ($entry in $images.GetEnumerator()) {
            $imageIds = @(Invoke-Docker @('image','ls','-q','--no-trunc','--filter',"reference=$($entry.Value)"))
            if ($imageIds.Count -ne 1) { throw "Restart requires $($entry.Value). Run Start to build missing images; running services retained." }
        }
    } else {
        $stateFile = Join-Path $demoDir 'build-state.json'
        $state = if (Test-Path -LiteralPath $stateFile) { Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json -AsHashtable } else { @{} }
        $tracked = @(& git -C $demoRoot ls-files --cached --others --exclude-standard)
        if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate build inputs.' }
        $configuration = (Invoke-Docker ($compose + @('config','--format','json')) | Out-String) | ConvertFrom-Json -AsHashtable
        foreach ($entry in $images.GetEnumerator()) {
            $buildDefinition = $configuration.services[$entry.Key].build | ConvertTo-Json -Depth 30 -Compress
            $fingerprint = Get-BuildFingerprint $entry.Key $tracked $buildDefinition
            $oldId = @(Invoke-Docker @('image','ls','-q','--no-trunc','--filter',"reference=$($entry.Value)"))
            $record = $state[$entry.Key]
            $changed = $oldId.Count -eq 0 -or -not $record -or $record.fingerprint -cne $fingerprint -or $record.image -cne $oldId[0]
            if (($Action -eq 'Build' -and (-not $Service -or $Service -eq $entry.Key)) -or ($Action -eq 'Start' -and $changed)) {
                Invoke-Docker ($compose + @('build',$entry.Key))
                $newId = @(Invoke-Docker @('image','ls','-q','--no-trunc','--filter',"reference=$($entry.Value)"))
                if ($newId.Count -ne 1) { throw 'Expected one built image.' }
                $state[$entry.Key] = @{fingerprint=$fingerprint; image=$newId[0]}
                $state | ConvertTo-Json | Set-Content -LiteralPath "$stateFile.tmp" -Encoding utf8
                [IO.File]::Move("$stateFile.tmp", $stateFile, $true)
            }
        }
    }
    if ($Action -eq 'Build') { return }
    if ($Action -eq 'Restart') { Invoke-Docker ($compose + @('stop')) }
    Invoke-Docker ($compose + @('up','-d','--no-build','--remove-orphans'))
    $unused = @(Invoke-Docker @('image','ls','-q','--no-trunc','--filter','dangling=true','--filter','label=com.hotshop.project=hotshop'))
    foreach ($imageId in $unused | Select-Object -Unique) {
        $remaining = @(Invoke-Docker @('image','ls','-q','--no-trunc','--filter','dangling=true','--filter','label=com.hotshop.project=hotshop'))
        if ($imageId -notin $remaining) { continue }
        if (-not @(Invoke-Docker @('ps','-aq','--filter',"ancestor=$imageId")).Count) {
            Invoke-Docker @('image','rm',$imageId)
        }
    }
    Wait-DemoMigrator $compose
    $demoUrl = "http://127.0.0.1:$($env:WEB_DEMO_PORT)"
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $ready = $false
    do {
        try {
            foreach ($path in @('/','/api/v1/products','/agent-api/health/ready')) {
                Invoke-WebRequest "$demoUrl$path" -TimeoutSec 3 | Out-Null
            }
            $ready = $true
        } catch { Start-Sleep -Seconds 2 }
    } while (-not $ready -and [DateTime]::UtcNow -lt $deadline)
    if (-not $ready) { throw "Readiness timeout. Use Status and docker compose logs with project $ProjectName." }
    if (Test-Path -LiteralPath $bootstrapFile) {
        if ((Get-Content -Raw $bootstrapFile).Trim() -ne 'database') { throw 'Incomplete bootstrap keys; run Start to resume.' }
        # The receipt commits with the seed. A crash after COMMIT must never seed again.
        $receipt = @(Invoke-Docker ($compose + @('exec','-T','mysql','sh','-lc',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch --skip-column-names --protocol=TCP --host=127.0.0.1 --user=root --database="$MYSQL_DATABASE" --execute="CREATE TABLE IF NOT EXISTS local_demo_initialization (id INT PRIMARY KEY) ENGINE=InnoDB; SELECT COUNT(*) FROM local_demo_initialization WHERE id=1"')))
        if ($receipt.Count -ne 1 -or $receipt[0] -notmatch '^[01]$') { throw 'Cannot determine bootstrap receipt.' }
        if ($receipt[0] -eq '0') {
            $counts = @(Invoke-Docker ($compose + @('exec','-T','mysql','sh','-lc',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch --skip-column-names --protocol=TCP --host=127.0.0.1 --user=root --database="$MYSQL_DATABASE" --execute="SELECT (SELECT COUNT(*) FROM app_user)+(SELECT COUNT(*) FROM catalog_product)+(SELECT COUNT(*) FROM sales_order)"')))
            if ($counts.Count -ne 1 -or $counts[0] -notmatch '^\d+$') { throw 'Cannot determine database initialization state.' }
            if ([long]$counts[0] -ne 0) { throw 'Bootstrap found existing business data; refusing to seed.' }
            $seed = Get-Content (Join-Path $demoRoot 'web/scripts/task-13-e2e-seed.sql') -Raw -Encoding utf8
            # Keep the live demonstration window open for one day from first seed.
            $seed = $seed.Replace('INTERVAL 30 MINUTE','INTERVAL 1 DAY')
            $seed += "`n" + (Get-Content (Join-Path $demoRoot 'database/data/demo-catalog.sql') -Raw -Encoding utf8)
            $seed = "START TRANSACTION;`n$seed`nINSERT INTO local_demo_initialization (id) VALUES (1);`nCOMMIT;"
            $seed | & docker @compose exec -T mysql sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 --protocol=TCP --host=127.0.0.1 --user=root --database="$MYSQL_DATABASE"'
            if ($LASTEXITCODE -ne 0) { throw 'Demo seed failed; resources retained.' }
        }
        Invoke-Docker ($compose + @('exec','-T','agent-service','python','-m','hotshop_agent.index_cli','rebuild'))
        $admin = Invoke-RestMethod "$demoUrl/admin/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body (@{username='task13-admin';password='Task13Admin!2026'} | ConvertTo-Json)
        foreach ($activity in @(913001,913002,913003)) {
            Invoke-RestMethod "$demoUrl/admin/api/v1/flash-sales/$activity/load" -Method Post -ContentType 'application/json' -Headers @{Authorization="Bearer $($admin.accessToken)"} -Body '{"reason":"Local demo initialization"}' | Out-Null
        }
        Remove-Item -LiteralPath $bootstrapFile
    }
    Write-Host "Ready: $demoUrl"
    Write-Host 'Admin: task13-admin / Task13Admin!2026 (public local demo account). Create your User account via the registration screen.'
    Write-Host "Credentials and keys (ignored): $demoDir"
    Write-Host "Stop without deleting data: pwsh -File ./script/demo.ps1 -Action Stop"
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    if ($null -ne $lifecycleLock) { $lifecycleLock.Dispose() }
}
