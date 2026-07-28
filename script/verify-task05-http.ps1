[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$suffix = ([guid]::NewGuid().ToString('N')).Substring(0, 10)
$network = "hotshop-task05-http-$suffix"
$mysql = "hotshop-task05-mysql-$suffix"
$redis = "hotshop-task05-redis-$suffix"
$portal = "hotshop-task05-portal-$suffix"
$admin = "hotshop-task05-admin-$suffix"
$containers = @($portal, $admin, $mysql, $redis)
$keys = Join-Path $repositoryRoot '.local\keys\hotshop'
$portalJar = Join-Path $repositoryRoot 'portal\target\portal-0.0.1-SNAPSHOT.jar'
$adminJar = Join-Path $repositoryRoot 'admin\target\admin-0.0.1-SNAPSHOT.jar'
$migrations = Join-Path $repositoryRoot 'database\src\main\resources\db\migration'
$password = 'Task05Http!1'

function Assert-File([string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required verification input is missing: $path"
    }
}

function Get-PublishedUri([string]$containerName, [int]$containerPort) {
    $mapping = docker port $containerName "$containerPort/tcp"
    if ($LASTEXITCODE -ne 0 -or $mapping -notmatch ':(\d+)\s*$') {
        throw "Cannot resolve published port for $containerName"
    }
    return "http://127.0.0.1:$($Matches[1])"
}

function Wait-Http([string]$uri, [string]$containerName) {
    $deadline = (Get-Date).AddMinutes(2)
    $lastProbe = 'no response'
    do {
        if ((docker inspect --format '{{.State.Running}}' $containerName) -ne 'true') {
            docker logs $containerName
            throw "$containerName exited before becoming ready"
        }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 3
            $lastProbe = "HTTP $($response.StatusCode)"
            if ($response.StatusCode -eq 200) {
                return
            }
        }
        catch {
            $lastProbe = $_.Exception.Message
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)
    docker logs $containerName
    throw "$containerName did not become ready: $lastProbe"
}

function Invoke-Json(
    [string]$method,
    [string]$uri,
    [object]$body,
    [hashtable]$headers = @{},
    [Microsoft.PowerShell.Commands.WebRequestSession]$session = $null
) {
    $arguments = @{
        Method = $method
        Uri = $uri
        Headers = $headers
        ContentType = 'application/json'
        UseBasicParsing = $true
    }
    if ($null -ne $body) {
        $arguments.Body = $body | ConvertTo-Json -Compress
    }
    if ($null -ne $session) {
        $arguments.WebSession = $session
    }
    try {
        return Invoke-WebRequest @arguments
    }
    catch {
        $failureResponse = $_.Exception.Response
        if ($null -eq $failureResponse) {
            throw
        }
        $reader = [System.IO.StreamReader]::new($failureResponse.GetResponseStream())
        try {
            $failureContent = $reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }
        return [pscustomobject]@{
            StatusCode = [int]$failureResponse.StatusCode
            Content = $failureContent
            Headers = $failureResponse.Headers
        }
    }
}

function Assert-Status([object]$response, [int]$expected, [string]$step) {
    if ($response.StatusCode -ne $expected) {
        throw "$step returned HTTP $($response.StatusCode), expected $expected"
    }
    Write-Output "$step -> HTTP $expected"
}

function Get-SessionCookie(
    [Microsoft.PowerShell.Commands.WebRequestSession]$session,
    [uri]$uri,
    [string]$name
) {
    $cookie = $session.Cookies.GetCookies($uri) | Where-Object Name -eq $name | Select-Object -First 1
    if ($null -eq $cookie -or [string]::IsNullOrWhiteSpace($cookie.Value)) {
        throw "Expected cookie was not issued: $name"
    }
    return $cookie.Value
}

function New-UserRefreshSession([uri]$uri, [string]$refreshToken, [string]$csrfToken) {
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $session.Cookies.Add(
        $uri,
        [System.Net.Cookie]::new('hotshop_user_refresh', $refreshToken, '/api/v1/auth')
    )
    $session.Cookies.Add(
        $uri,
        [System.Net.Cookie]::new('hotshop_user_csrf', $csrfToken, '/api/v1/auth')
    )
    return $session
}

function ConvertTo-Base64Url([string]$value) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($value))
    return $encoded.TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

foreach ($path in @(
    $portalJar,
    $adminJar,
    (Join-Path $keys 'user-private.pem'),
    (Join-Path $keys 'user-public.pem'),
    (Join-Path $keys 'administrator-private.pem'),
    (Join-Path $keys 'administrator-public.pem'),
    (Join-Path $keys 'agent-delegation-private.pem'),
    (Join-Path $keys 'agent-delegation-public.pem'),
    (Join-Path $keys 'agent-service-public.pem')
)) {
    Assert-File $path
}

try {
    docker network create $network | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not create isolated verification network'
    }

    docker run -d --name $mysql --network $network `
        -e MYSQL_ROOT_PASSWORD=task05-root `
        -e MYSQL_DATABASE=hotShop `
        mysql:8.0.46 | Out-Null
    docker run -d --name $redis --network $network redis:8.8.1-alpine | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not start isolated data dependencies'
    }

    $mysqlDeadline = (Get-Date).AddMinutes(2)
    $consecutiveMysqlProbes = 0
    do {
        $probePreference = $ErrorActionPreference
        $ErrorActionPreference = 'SilentlyContinue'
        docker exec -e MYSQL_PWD=task05-root $mysql mysql -uroot -Nse 'SELECT 1' 2>$null | Out-Null
        $probeExitCode = $LASTEXITCODE
        $ErrorActionPreference = $probePreference
        if ($probeExitCode -eq 0) {
            $consecutiveMysqlProbes++
            if ($consecutiveMysqlProbes -ge 3) {
                break
            }
        }
        else {
            $consecutiveMysqlProbes = 0
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $mysqlDeadline)
    if ($consecutiveMysqlProbes -lt 3) {
        throw 'Isolated MySQL did not become ready'
    }

    docker run --rm --network $network `
        --mount "type=bind,source=$migrations,target=/flyway/sql,readonly" `
        flyway/flyway:11.20.3-alpine `
        -url="jdbc:mysql://${mysql}:3306/hotShop?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" `
        -user=root -password=task05-root `
        -locations=filesystem:/flyway/sql `
        -validateMigrationNaming=true `
        -cleanDisabled=true migrate | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Flyway migration failed'
    }

    $portalArguments = @(
        'run', '-d', '--name', $portal, '--network', $network, '-p', '127.0.0.1::8080',
        '--mount', "type=bind,source=$portalJar,target=/app/app.jar,readonly",
        '--mount', "type=bind,source=$(Join-Path $keys 'user-private.pem'),target=/keys/user-private.pem,readonly",
        '--mount', "type=bind,source=$(Join-Path $keys 'user-public.pem'),target=/keys/user-public.pem,readonly",
        '--mount', "type=bind,source=$(Join-Path $keys 'agent-delegation-private.pem'),target=/keys/agent-delegation-private.pem,readonly",
        '--mount', "type=bind,source=$(Join-Path $keys 'agent-delegation-public.pem'),target=/keys/agent-delegation-public.pem,readonly",
        '--mount', "type=bind,source=$(Join-Path $keys 'agent-service-public.pem'),target=/keys/agent-service-public.pem,readonly",
        '-e', "SPRING_DATASOURCE_URL=jdbc:mysql://${mysql}:3306/hotShop?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        '-e', 'SPRING_DATASOURCE_USERNAME=root',
        '-e', 'SPRING_DATASOURCE_PASSWORD=task05-root',
        '-e', "SPRING_REDIS_HOST=$redis",
        '-e', 'SPRING_REDIS_PORT=6379',
        '-e', 'HOTSHOP_SECURE_COOKIES=false',
        '-e', 'HOTSHOP_JWT_CLOCK_SKEW_SECONDS=0',
        '-e', 'HOTSHOP_USER_ACCESS_TTL_SECONDS=60',
        '-e', 'HOTSHOP_USER_PRIVATE_KEY_PATH=/keys/user-private.pem',
        '-e', 'HOTSHOP_USER_PUBLIC_KEY_PATH=/keys/user-public.pem',
        '-e', 'HOTSHOP_AGENT_DELEGATION_PRIVATE_KEY_PATH=/keys/agent-delegation-private.pem',
        '-e', 'HOTSHOP_AGENT_DELEGATION_PUBLIC_KEY_PATH=/keys/agent-delegation-public.pem',
        '-e', 'HOTSHOP_AGENT_SERVICE_PUBLIC_KEY_PATH=/keys/agent-service-public.pem',
        'eclipse-temurin:21-jdk', 'java', '-jar', '/app/app.jar',
        '--server.port=8080', '--logging.level.root=WARN'
    )
    docker @portalArguments | Out-Null

    $adminArguments = @(
        'run', '-d', '--name', $admin, '--network', $network, '-p', '127.0.0.1::8088',
        '--mount', "type=bind,source=$adminJar,target=/app/app.jar,readonly",
        '--mount', "type=bind,source=$(Join-Path $keys 'administrator-private.pem'),target=/keys/administrator-private.pem,readonly",
        '--mount', "type=bind,source=$(Join-Path $keys 'administrator-public.pem'),target=/keys/administrator-public.pem,readonly",
        '-e', "SPRING_DATASOURCE_URL=jdbc:mysql://${mysql}:3306/hotShop?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        '-e', 'SPRING_DATASOURCE_USERNAME=root',
        '-e', 'SPRING_DATASOURCE_PASSWORD=task05-root',
        '-e', "SPRING_REDIS_HOST=$redis",
        '-e', 'SPRING_REDIS_PORT=6379',
        '-e', 'HOTSHOP_SECURE_COOKIES=false',
        '-e', 'HOTSHOP_ADMIN_PRIVATE_KEY_PATH=/keys/administrator-private.pem',
        '-e', 'HOTSHOP_ADMIN_PUBLIC_KEY_PATH=/keys/administrator-public.pem',
        'eclipse-temurin:21-jdk', 'java', '-jar', '/app/app.jar',
        '--server.port=8088', '--logging.level.root=WARN'
    )
    docker @adminArguments | Out-Null

    $portalBase = Get-PublishedUri $portal 8080
    $adminBase = Get-PublishedUri $admin 8088
    Wait-Http "$portalBase/v3/api-docs/public" $portal
    Wait-Http "$adminBase/v3/api-docs/admin" $admin

    $userName = "http-user-$suffix"
    $adminName = "http-admin-$suffix"
    Assert-Status (Invoke-Json POST "$portalBase/api/v1/auth/register" @{
        username = $userName
        password = $password
        email = "$userName@example.test"
    }) 201 'register User'
    Assert-Status (Invoke-Json POST "$portalBase/api/v1/auth/register" @{
        username = $adminName
        password = $password
        email = "$adminName@example.test"
    }) 201 'register Administrator seed'

    docker exec -e MYSQL_PWD=task05-root $mysql mysql -uroot hotShop `
        -e "UPDATE app_user SET role='ROLE_ADMIN' WHERE username='$adminName'" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not promote isolated Administrator seed'
    }

    $userSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $login = Invoke-Json POST "$portalBase/api/v1/auth/login" @{
        username = $userName
        password = $password
    } @{} $userSession
    Assert-Status $login 200 'User login'
    if ($login.Headers['Cache-Control'] -notcontains 'no-store') {
        throw 'User login did not return Cache-Control: no-store'
    }
    $userBody = $login.Content | ConvertFrom-Json
    $userAccess = $userBody.accessToken
    $cookieUri = [uri]"$portalBase/api/v1/auth/"
    $oldRefresh = Get-SessionCookie $userSession $cookieUri 'hotshop_user_refresh'
    $oldCsrf = Get-SessionCookie $userSession $cookieUri 'hotshop_user_csrf'
    Assert-Status (Invoke-Json GET "$portalBase/api/v1/users/me" $null @{
        Authorization = "Bearer $userAccess"
    }) 200 'User Access -> Portal'

    $refresh = Invoke-Json POST "$portalBase/api/v1/auth/refresh" $null @{
        'X-CSRF-Token' = $oldCsrf
    } $userSession
    Assert-Status $refresh 200 'refresh rotation'
    $successorRefresh = Get-SessionCookie $userSession $cookieUri 'hotshop_user_refresh'
    $successorCsrf = Get-SessionCookie $userSession $cookieUri 'hotshop_user_csrf'
    if ($successorRefresh -eq $oldRefresh) {
        throw 'Refresh rotation did not replace the opaque token'
    }

    $oldSession = New-UserRefreshSession $cookieUri $oldRefresh $oldCsrf
    $reuse = Invoke-Json POST "$portalBase/api/v1/auth/refresh" $null @{
        'X-CSRF-Token' = $oldCsrf
    } $oldSession
    Assert-Status $reuse 401 'old Refresh reuse'
    $successorSession = New-UserRefreshSession $cookieUri $successorRefresh $successorCsrf
    $familyRevoked = Invoke-Json POST "$portalBase/api/v1/auth/refresh" $null @{
        'X-CSRF-Token' = $successorCsrf
    } $successorSession
    Assert-Status $familyRevoked 401 'successor after family revocation'

    $userSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $login = Invoke-Json POST "$portalBase/api/v1/auth/login" @{
        username = $userName
        password = $password
    } @{} $userSession
    Assert-Status $login 200 'User re-login'
    $userAccess = ($login.Content | ConvertFrom-Json).accessToken
    $csrf = Get-SessionCookie $userSession $cookieUri 'hotshop_user_csrf'

    $adminSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $adminLogin = Invoke-Json POST "$adminBase/admin/api/v1/auth/login" @{
        username = $adminName
        password = $password
    } @{} $adminSession
    Assert-Status $adminLogin 200 'Administrator login'
    $adminAccess = ($adminLogin.Content | ConvertFrom-Json).accessToken
    Assert-Status (Invoke-Json GET "$adminBase/admin/api/v1/users" $null @{
        Authorization = "Bearer $userAccess"
    }) 401 'User Access -> Admin boundary'
    Assert-Status (Invoke-Json GET "$portalBase/api/v1/users/me" $null @{
        Authorization = "Bearer $adminAccess"
    }) 401 'Administrator Access -> Portal boundary'
    Assert-Status (Invoke-Json GET "$portalBase/agent/api/v1/not-a-tool" $null @{
        Authorization = "Bearer $adminAccess"
    }) 401 'Administrator Access -> Agent boundary'

    $jwtParts = $userAccess.Split('.')
    $wrongAlgorithm = "$(ConvertTo-Base64Url '{"alg":"none","kid":"user-local-1","typ":"user-access+jwt"}').$($jwtParts[1]).$($jwtParts[2])"
    Assert-Status (Invoke-Json GET "$portalBase/api/v1/users/me" $null @{
        Authorization = "Bearer $wrongAlgorithm"
    }) 401 'wrong JWT algorithm'

    Write-Output 'waiting for the 60-second network Access Token to expire'
    Start-Sleep -Seconds 61
    Assert-Status (Invoke-Json GET "$portalBase/api/v1/users/me" $null @{
        Authorization = "Bearer $userAccess"
    }) 401 'expired User Access'

    $freshLogin = Invoke-Json POST "$portalBase/api/v1/auth/login" @{
        username = $userName
        password = $password
    } @{} $userSession
    Assert-Status $freshLogin 200 'User login before logout'
    $freshAccess = ($freshLogin.Content | ConvertFrom-Json).accessToken
    $freshCsrf = Get-SessionCookie $userSession $cookieUri 'hotshop_user_csrf'
    Assert-Status (Invoke-Json POST "$portalBase/api/v1/auth/logout" $null @{
        Authorization = "Bearer $freshAccess"
        'X-CSRF-Token' = $freshCsrf
    } $userSession) 200 'User logout'
    Assert-Status (Invoke-Json GET "$portalBase/api/v1/users/me" $null @{
        Authorization = "Bearer $freshAccess"
    }) 401 'denylisted Access after logout'

    Write-Output 'TASK-05 isolated HTTP/Cookie verification passed'
}
finally {
    $cleanupPreference = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    foreach ($container in $containers) {
        docker rm -f $container 2>$null | Out-Null
    }
    docker network rm $network 2>$null | Out-Null
    $ErrorActionPreference = $cleanupPreference
}
