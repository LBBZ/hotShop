#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Start','Status','Stop','Restart')][string]$Action = 'Start',
    [string]$ProjectName = '',
    [ValidateRange(1024,65535)][int]$WebPort = 18080,
    [ValidateRange(30,1800)][int]$TimeoutSeconds = 300
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$demoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $ProjectName) {
    if ($Action -ne 'Start') { throw 'Specify the project printed by Start.' }
    $ProjectName = 'hotshop-task21-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
}
if ($ProjectName -notmatch '^hotshop-task21-[a-z0-9]{8,24}$') { throw 'Project must match hotshop-task21-<8 to 24 lowercase letters/digits>.' }
$demoDir = Join-Path $demoRoot ".local/keys/$ProjectName"
$demoEnv = Join-Path $demoDir '.env.demo'
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
function New-Secret { return [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant() }
$compose = @('compose','-p',$ProjectName,'--env-file',$demoEnv,'-f',(Join-Path $demoRoot 'docker-compose.yml'),'-f',(Join-Path $demoRoot 'docker-compose.demo.yml'),'--profile','app','--profile','agent')
$previous = @{}
try {
    # Prevent undisclosed shell overrides (including COMPOSE_PROFILES and image tags).
    $template = (Get-Content (Join-Path $demoRoot 'docker-compose.yml') -Raw) + (Get-Content (Join-Path $demoRoot 'docker-compose.demo.yml') -Raw)
    $names = @([regex]::Matches($template, '\$\{([A-Z][A-Z0-9_]*)') | ForEach-Object { $_.Groups[1].Value }) + @('COMPOSE_PROFILES','COMPOSE_FILE')
    foreach ($name in $names | Select-Object -Unique) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
        [Environment]::SetEnvironmentVariable($name,$null,'Process')
    }
    if ($Action -eq 'Start') {
        if (Test-Path -LiteralPath $demoDir) { throw 'Refusing existing demo directory. Use a fresh project; Restart preserves existing data.' }
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
            ADMIN_IMAGE="$ProjectName-admin:local"; PORTAL_IMAGE="$ProjectName-portal:local"; TASK_IMAGE="$ProjectName-task:local"
            AGENT_IMAGE="$ProjectName-agent:local"; RABBITMQ_PROJECT_IMAGE="$ProjectName-rabbitmq:local"
            WEB_DEMO_IMAGE="$ProjectName-web:local"; WEB_DEMO_PORT="$WebPort"
        }
        foreach ($key in @('ADMIN_IMAGE','PORTAL_IMAGE','TASK_IMAGE','AGENT_IMAGE','RABBITMQ_PROJECT_IMAGE','WEB_DEMO_IMAGE')) {
            $found = @(Invoke-Docker @('image','ls','-q','--filter',"reference=$($values[$key])"))
            if ($found.Count) { throw "Refusing existing image tag $($values[$key])" }
        }
        New-Item -ItemType Directory -Path $demoDir | Out-Null
        $values.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" } | Set-Content -LiteralPath $demoEnv -Encoding utf8
        & (Join-Path $PSScriptRoot 'generate-auth-keys.ps1') -OutputDirectory $demoDir
    }
    if (-not (Test-Path -LiteralPath $demoEnv)) { throw 'No owned demo configuration found.' }
    # Explicit env file values win over inherited shell values; restore shell after execution.
    foreach ($line in Get-Content -LiteralPath $demoEnv) {
        $name,$value = $line -split '=',2
        if (-not $previous.ContainsKey($name)) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
        [Environment]::SetEnvironmentVariable($name,$value,'Process')
    }
    Write-Host "Demo project: $ProjectName"
    if ($Action -eq 'Status') { Invoke-Docker ($compose + @('ps','-a')); return }
    if ($Action -eq 'Stop') { Invoke-Docker ($compose + @('stop')); return }
    if ($Action -eq 'Restart') { Invoke-Docker ($compose + @('start')); return }
    Invoke-Docker ($compose + @('config','--quiet'))
    Invoke-Docker ($compose + @('up','-d','--build'))
    Wait-DemoMigrator $compose
    $demoUrl = "http://127.0.0.1:$WebPort"
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
    # Only on first initialization in a verified empty project; never re-seed Restart.
    $seed = Get-Content (Join-Path $demoRoot 'web/scripts/task-13-e2e-seed.sql') -Raw -Encoding utf8
    # Keep the live demonstration window open for one day from first seed.
    $seed = $seed.Replace('INTERVAL 30 MINUTE','INTERVAL 1 DAY')
    $seed | & docker @compose exec -T mysql sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 --protocol=TCP --host=127.0.0.1 --user=root --database="$MYSQL_DATABASE"'
    if ($LASTEXITCODE -ne 0) { throw 'Demo seed failed; resources retained.' }
    Invoke-Docker ($compose + @('exec','-T','agent-service','python','-m','hotshop_agent.index_cli','rebuild'))
    $admin = Invoke-RestMethod "$demoUrl/admin/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body (@{username='task13-admin';password='Task13Admin!2026'} | ConvertTo-Json)
    foreach ($activity in @(913001,913002,913003)) {
        Invoke-RestMethod "$demoUrl/admin/api/v1/flash-sales/$activity/load" -Method Post -ContentType 'application/json' -Headers @{Authorization="Bearer $($admin.accessToken)"} -Body '{"reason":"TASK21 isolated demo initialization"}' | Out-Null
    }
    Write-Host "Ready: $demoUrl"
    Write-Host 'Admin: task13-admin / Task13Admin!2026 (public local demo account). Create your User account via the registration screen.'
    Write-Host "Credentials and keys (ignored): $demoDir"
    Write-Host "Stop without deleting data: pwsh -File ./script/task21-demo.ps1 -Action Stop -ProjectName $ProjectName"
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
