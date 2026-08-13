[CmdletBinding()]
param(
    [string]$ProjectName = "",
    [string]$EvidenceDirectory = "",
    [int]$TimeoutSeconds = 360,
    [ValidateSet("", "preflight-only", "fail-after-resource-startup")]
    [string]$OwnershipTestMode = ""
)

$ErrorActionPreference = "Stop"
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
Set-Location $repositoryRoot
. (Join-Path $PSScriptRoot "ci\native_cleanup.ps1")

$suffix = ([Guid]::NewGuid().ToString("N")).Substring(0, 10)
if ([string]::IsNullOrWhiteSpace($ProjectName)) {
    $ProjectName = "hotshop-task16-$suffix"
}
if ($ProjectName -notmatch "^[a-z0-9][a-z0-9_-]{5,62}$") {
    throw "ProjectName must be a lowercase, isolated Compose project name"
}
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
    $EvidenceDirectory = Join-Path $repositoryRoot "target\task16-compose-evidence\$ProjectName"
}
$EvidenceDirectory = [System.IO.Path]::GetFullPath($EvidenceDirectory)
if (Test-Path -LiteralPath $EvidenceDirectory) {
    throw "Evidence directory already exists; refusing to overwrite it: $EvidenceDirectory"
}
New-Item -ItemType Directory -Path $EvidenceDirectory | Out-Null

$keyRoot = [System.IO.Path]::GetFullPath(
    (Join-Path ([System.IO.Path]::GetTempPath()) "hotshop-task16-keys-$suffix")
)
$composeArguments = @("-p", $ProjectName, "--env-file", ".env.example")
$sseCounter = 0
$sensitiveValues = [System.Collections.Generic.List[string]]::new()
$privateKeyMarkers = [System.Collections.Generic.List[string]]::new()
$startedAt = [DateTimeOffset]::UtcNow
$verificationFailure = $null
$ownedImages = @(
    "$ProjectName-portal:verify",
    "$ProjectName-admin:verify",
    "$ProjectName-agent:verify"
)
$resourceOwnership = [ordered]@{
    preflightPassed = $false
    creationAuthorized = $false
    containers = @()
    volumes = @()
    networks = @()
    keyDirectory = $false
    images = @{}
}
foreach ($ownedImage in $ownedImages) {
    $resourceOwnership.images[$ownedImage] = ""
}

$environmentOverrides = [ordered]@{
    MYSQL_ROOT_PASSWORD = "Task16Mysql$suffix"
    MYSQL_PORT = "0"
    REDIS_CACHE_PASSWORD = "Task16RedisCache$suffix"
    REDIS_CACHE_PORT = "0"
    REDIS_SECKILL_PASSWORD = "Task16RedisSeckill$suffix"
    REDIS_SECKILL_PORT = "0"
    PORTAL_PORT = "0"
    ADMIN_PORT = "0"
    AGENT_PORT = "0"
    PORTAL_IMAGE = $ownedImages[0]
    ADMIN_IMAGE = $ownedImages[1]
    AGENT_IMAGE = $ownedImages[2]
    HOTSHOP_KEY_DIR = $keyRoot
    HOTSHOP_SECURE_COOKIES = "false"
    AGENT_MODEL_PROVIDER = "fake"
    # This scenario sends explicit JSON tool calls. Disable natural-language RAG routing so
    # names such as compensate_inventory reach the registry policy asserted below.
    AGENT_RAG_ENABLED = "false"
    AGENT_QWEN_API_KEY = ""
    HOTSHOP_TRACE_SAMPLING_PROBABILITY = "1.0"
}
$originalEnvironment = @{}
foreach ($entry in $environmentOverrides.GetEnumerator()) {
    $originalEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable(
        $entry.Key,
        [EnvironmentVariableTarget]::Process
    )
    [Environment]::SetEnvironmentVariable(
        $entry.Key,
        [string]$entry.Value,
        [EnvironmentVariableTarget]::Process
    )
}

function Restore-Environment {
    foreach ($entry in $script:originalEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable(
            [string]$entry.Key,
            $entry.Value,
            [EnvironmentVariableTarget]::Process
        )
    }
}

function Assert-DockerSucceeded([string]$Step) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE"
    }
}

function Wait-Until {
    param(
        [scriptblock]$Probe,
        [string]$Description
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($script:TimeoutSeconds)
    $delayMilliseconds = 100
    do {
        try {
            $result = & $Probe
            if ($null -ne $result -and $result -ne $false) {
                return $result
            }
        }
        catch {
            # Transient startup and asynchronous-state failures are retried until the deadline.
        }
        if ([DateTimeOffset]::UtcNow -ge $deadline) {
            throw "Timed out waiting for $Description"
        }
        Start-Sleep -Milliseconds $delayMilliseconds
        $delayMilliseconds = [Math]::Min(2000, [int]($delayMilliseconds * 1.6))
    } while ($true)
}

function Get-ServiceBaseUrl([string]$Service, [int]$ContainerPort) {
    $mapping = docker compose @script:composeArguments port $Service $ContainerPort
    Assert-DockerSucceeded "Resolve published port for $Service"
    $match = @($mapping) | Where-Object { $_ -match ":(\d+)\s*$" } | Select-Object -First 1
    if ($null -eq $match -or $match -notmatch ":(\d+)\s*$") {
        throw "Could not parse the published port for $Service"
    }
    return "http://127.0.0.1:$($Matches[1])"
}

function Invoke-Http {
    param(
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers = @{},
        [object]$Body = $null
    )
    $arguments = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        UseBasicParsing = $true
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $arguments.ContentType = "application/json"
        $arguments.Body = $Body | ConvertTo-Json -Compress -Depth 20
    }
    if ((Get-Command Invoke-WebRequest).Parameters.ContainsKey("SkipHttpErrorCheck")) {
        $arguments.SkipHttpErrorCheck = $true
        $result = Invoke-WebRequest @arguments
        Assert-NoPrivateKey (Get-ResponseContentText $result) "HTTP response from $Uri"
        return $result
    }
    try {
        $result = Invoke-WebRequest @arguments
        Assert-NoPrivateKey (Get-ResponseContentText $result) "HTTP response from $Uri"
        return $result
    }
    catch {
        $response = $_.Exception.Response
        if ($null -eq $response) {
            throw
        }
        $content = ""
        if ($null -ne $response.Content) {
            $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
        elseif ($null -ne $response.GetResponseStream()) {
            $reader = [System.IO.StreamReader]::new($response.GetResponseStream())
            try {
                $content = $reader.ReadToEnd()
            }
            finally {
                $reader.Dispose()
            }
        }
        $result = [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Content = $content
            Headers = $response.Headers
        }
        Assert-NoPrivateKey (Get-ResponseContentText $result) "HTTP response from $Uri"
        return $result
    }
}

function Assert-Status([object]$Response, [int]$Expected, [string]$Step) {
    if ([int]$Response.StatusCode -ne $Expected) {
        $code = ""
        try {
            $code = ($Response.Content | ConvertFrom-Json).code
        }
        catch {
            $code = "unavailable"
        }
        throw "$Step returned HTTP $($Response.StatusCode), expected $Expected, code=$code"
    }
    Write-Host "$Step -> HTTP $Expected"
}

function Get-ResponseContentText([object]$Response) {
    if ($null -eq $Response.Content) {
        return ""
    }
    if ($Response.Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString([byte[]]$Response.Content)
    }
    return [string]$Response.Content
}

function Convert-JsonBody([object]$Response, [string]$Step) {
    try {
        return ConvertFrom-Json -InputObject (Get-ResponseContentText $Response)
    }
    catch {
        throw "$Step did not return valid JSON"
    }
}

function New-TraceHeaders([string]$Prefix, [string]$AccessToken = "") {
    $traceId = [Guid]::NewGuid().ToString("N")
    $spanId = ([Guid]::NewGuid().ToString("N")).Substring(0, 16)
    $headers = @{
        "X-Request-ID" = "$Prefix-$([Guid]::NewGuid().ToString('N').Substring(0, 12))"
        traceparent = "00-$traceId-$spanId-01"
        Accept = "application/json"
    }
    if (-not [string]::IsNullOrWhiteSpace($AccessToken)) {
        $headers.Authorization = "Bearer $AccessToken"
    }
    return $headers
}

function Wait-Http([string]$Uri, [string]$Service) {
    [void](Wait-Until -Description "$Service readiness at $Uri" -Probe {
        $containerId = docker compose @script:composeArguments ps -q $Service
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
            return $false
        }
        $running = docker inspect --format "{{.State.Running}}" $containerId
        if ($running -ne "true") {
            return $false
        }
        $response = Invoke-Http GET $Uri
        if ([int]$response.StatusCode -eq 200) {
            return $true
        }
        return $false
    })
}

function Invoke-MySql([string]$Sql) {
    $output = @(
        $Sql | docker compose @script:composeArguments exec -T mysql sh -lc `
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP --host=127.0.0.1 --user=root --database="$MYSQL_DATABASE" --batch --raw --skip-column-names'
    )
    Assert-DockerSucceeded "MySQL statement"
    return ($output -join "`n").Trim()
}

function Get-Scalar([string]$Sql) {
    $value = Invoke-MySql $Sql
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Scalar query returned no value"
    }
    return $value.Trim()
}

function Assert-Equal([object]$Actual, [object]$Expected, [string]$Description) {
    if ([string]$Actual -ne [string]$Expected) {
        throw "$Description mismatch: actual=$Actual expected=$Expected"
    }
}

function Add-SensitiveValue([string]$Value) {
    if (-not [string]::IsNullOrWhiteSpace($Value) -and -not $script:sensitiveValues.Contains($Value)) {
        $script:sensitiveValues.Add($Value)
    }
}

function Assert-NoSensitiveValue([string]$Text, [string]$Description) {
    foreach ($secret in $script:sensitiveValues) {
        if ($Text.IndexOf($secret, [StringComparison]::Ordinal) -ge 0) {
            throw "Sensitive value leaked into $Description"
        }
    }
}

function Assert-NoPrivateKey([string]$Text, [string]$Description) {
    foreach ($marker in $script:privateKeyMarkers) {
        if ($Text.IndexOf($marker, [StringComparison]::Ordinal) -ge 0) {
            throw "Agent private key material leaked into $Description"
        }
    }
}

function Redact-SensitiveValues([string]$Text) {
    $result = $Text
    foreach ($secret in $script:sensitiveValues) {
        $result = $result.Replace($secret, "[REDACTED]")
    }
    return $result
}

function Add-PrivateKeyMaterial([string]$Path) {
    $keyText = [System.IO.File]::ReadAllText($Path)
    $keyLines = @($keyText -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $bodyLines = @($keyLines | Where-Object {
        -not $_.StartsWith("-----", [StringComparison]::Ordinal)
    })
    if ([string]::IsNullOrWhiteSpace($keyText) -or $bodyLines.Count -eq 0) {
        throw "Generated private key is empty or malformed: $Path"
    }
    foreach ($marker in @($keyText) + $keyLines) {
        if (-not $script:privateKeyMarkers.Contains($marker)) {
            $script:privateKeyMarkers.Add($marker)
        }
        Add-SensitiveValue $marker
    }
}

function Parse-Sse([string]$Body) {
    $events = @()
    foreach ($line in ($Body -split "`r?`n")) {
        if ($line.StartsWith("data: ", [StringComparison]::Ordinal)) {
            try {
                $events += $line.Substring(6) | ConvertFrom-Json
            }
            catch {
                throw "SSE contained an invalid JSON data line"
            }
        }
    }
    if ($events.Count -eq 0) {
        throw "SSE contained no data events"
    }
    return $events
}

function New-AgentSession(
    [string]$AgentBase,
    [string]$RoutePrefix,
    [string]$AccessToken,
    [object]$Body
) {
    $response = Invoke-Http POST "$AgentBase$RoutePrefix/sessions" `
        (New-TraceHeaders "task16-session" $AccessToken) $Body
    Assert-Status $response 201 "Create Agent session"
    $session = Convert-JsonBody $response "Create Agent session"
    if ($session.state -ne "ACTIVE" -or [string]::IsNullOrWhiteSpace($session.id)) {
        throw "Agent session response is invalid"
    }
    if ($Body.ContainsKey("scopes")) {
        $requestedScopes = @($Body.scopes | Sort-Object)
        $returnedScopes = @($session.scopes | Sort-Object)
        if (($requestedScopes -join " ") -ne ($returnedScopes -join " ")) {
            throw "Agent session scopes differ from the explicit request"
        }
    }
    return $session
}

function Invoke-AgentTool {
    param(
        [string]$AgentBase,
        [string]$RoutePrefix,
        [string]$SessionId,
        [string]$AccessToken,
        [string]$Tool,
        [hashtable]$Arguments,
        [ValidateSet("SUCCESS", "FAILURE")][string]$ExpectedOutcome = "SUCCESS",
        [string]$ExpectedFailureCode = ""
    )
    $content = @{tool = $Tool; arguments = $Arguments} | ConvertTo-Json -Compress -Depth 20
    $messageResponse = Invoke-Http POST "$AgentBase$RoutePrefix/sessions/$SessionId/messages" `
        (New-TraceHeaders "task16-message" $AccessToken) @{content = $content}
    Assert-Status $messageResponse 201 "Add Agent message for $Tool"
    $message = Convert-JsonBody $messageResponse "Add Agent message for $Tool"

    $runResponse = Invoke-Http POST "$AgentBase$RoutePrefix/sessions/$SessionId/runs" `
        (New-TraceHeaders "task16-run" $AccessToken) @{messageId = $message.id}
    Assert-Status $runResponse 202 "Start Agent run for $Tool"
    $run = Convert-JsonBody $runResponse "Start Agent run for $Tool"

    $eventHeaders = New-TraceHeaders "task16-events" $AccessToken
    $eventHeaders.Accept = "text/event-stream"
    $eventResponse = Invoke-Http GET "$AgentBase$RoutePrefix/runs/$($run.id)/events" $eventHeaders
    Assert-Status $eventResponse 200 "Read Agent SSE for $Tool"
    $cacheControl = @($eventResponse.Headers["Cache-Control"]) -join ","
    if ($cacheControl -notmatch "no-store") {
        throw "Agent SSE for $Tool did not disable caching"
    }
    Assert-NoSensitiveValue $eventResponse.Content "Agent SSE for $Tool"
    $events = @(Parse-Sse $eventResponse.Content)
    if (-not ($events | Where-Object type -eq "done")) {
        throw "Agent SSE for $Tool did not terminate with done"
    }

    if ($ExpectedOutcome -eq "SUCCESS") {
        $terminal = @($events | Where-Object {
            $_.type -eq "tool.completed" -and $_.data.tool -eq $Tool
        })
        if ($terminal.Count -ne 1 -or $terminal[0].data.outcome -ne "SUCCESS") {
            throw "Agent tool $Tool did not complete successfully"
        }
    }
    else {
        $terminal = @($events | Where-Object type -eq "tool.failed")
        if ($terminal.Count -ne 1 -or $terminal[0].data.outcome -ne "FAILURE") {
            throw "Prohibited Agent tool $Tool was not rejected"
        }
        if (-not [string]::IsNullOrWhiteSpace($ExpectedFailureCode) -and
                $terminal[0].data.code -ne $ExpectedFailureCode) {
            throw "Prohibited Agent tool $Tool returned unexpected code $($terminal[0].data.code)"
        }
    }

    $script:sseCounter++
    $safeTool = $Tool -replace "[^A-Za-z0-9_-]", "_"
    $path = Join-Path $script:EvidenceDirectory (
        "sse-{0:d2}-{1}-{2}.txt" -f $script:sseCounter, $ExpectedOutcome.ToLowerInvariant(), $safeTool
    )
    Set-Content -LiteralPath $path -Value $eventResponse.Content -Encoding UTF8
    Write-Host "Agent tool $Tool -> $ExpectedOutcome"
    return [pscustomobject]@{Run = $run; Events = $events; EvidencePath = $path}
}

function Get-BusinessSnapshot {
    return [ordered]@{
        productOneStock = [int](Get-Scalar "SELECT stock FROM catalog_product WHERE product_id=916001;")
        productTwoStock = [int](Get-Scalar "SELECT stock FROM catalog_product WHERE product_id=916002;")
        orderCount = [int](Get-Scalar "SELECT COUNT(*) FROM sales_order;")
        orderItemCount = [int](Get-Scalar "SELECT COUNT(*) FROM sales_order_item;")
        reservationCount = [int](Get-Scalar "SELECT COUNT(*) FROM sale_reservation;")
        purchaseDraftCount = [int](Get-Scalar "SELECT COUNT(*) FROM purchase_draft;")
        confirmationCount = [int](Get-Scalar "SELECT COUNT(*) FROM purchase_confirmation;")
        configurationDraftCount = [int](Get-Scalar "SELECT COUNT(*) FROM agent_configuration_draft;")
        outboxCount = [int](Get-Scalar "SELECT COUNT(*) FROM outbox_event;")
        adminCount = [int](Get-Scalar "SELECT COUNT(*) FROM app_user WHERE role='ROLE_ADMIN';")
    }
}

function Get-ProblemCode([object]$Response) {
    try {
        return (ConvertFrom-Json -InputObject (Get-ResponseContentText $Response)).code
    }
    catch {
        return ""
    }
}

function Get-Sha256([string]$Value) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
        return ([BitConverter]::ToString($algorithm.ComputeHash($bytes)) -replace "-", "").ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
    }
}

function Test-FileContainsByteSequence([string]$Path, [byte[]]$Pattern) {
    if ($Pattern.Length -eq 0) {
        throw "Byte scan pattern must not be empty"
    }
    $prefix = New-Object int[] $Pattern.Length
    $matched = 0
    for ($index = 1; $index -lt $Pattern.Length; $index++) {
        while ($matched -gt 0 -and $Pattern[$matched] -ne $Pattern[$index]) {
            $matched = $prefix[$matched - 1]
        }
        if ($Pattern[$matched] -eq $Pattern[$index]) {
            $matched++
        }
        $prefix[$index] = $matched
    }

    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $buffer = New-Object byte[] (1024 * 1024)
        $matched = 0
        while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            for ($index = 0; $index -lt $read; $index++) {
                while ($matched -gt 0 -and $Pattern[$matched] -ne $buffer[$index]) {
                    $matched = $prefix[$matched - 1]
                }
                if ($Pattern[$matched] -eq $buffer[$index]) {
                    $matched++
                }
                if ($matched -eq $Pattern.Length) {
                    return $true
                }
            }
        }
        return $false
    }
    finally {
        $stream.Dispose()
    }
}

function Assert-ImageHistoryHasNoPrivateKeys(
    [string]$RuntimeImage,
    [string[]]$HostPrivateKeyPaths,
    [string]$Label
) {
    $archivePath = Join-Path $script:EvidenceDirectory "$($Label.ToLowerInvariant())-image-save.tar"
    try {
        docker image save --output $archivePath $RuntimeImage
        Assert-DockerSucceeded "Save $Label runtime image history"
        foreach ($privateKeyPath in $HostPrivateKeyPaths) {
            $privateKeyBytes = [System.IO.File]::ReadAllBytes($privateKeyPath)
            if (Test-FileContainsByteSequence $archivePath $privateKeyBytes) {
                throw "$Label runtime image history contains generated private key bytes"
            }
        }
    }
    finally {
        if (Test-Path -LiteralPath $archivePath -PathType Leaf) {
            Remove-Item -LiteralPath $archivePath -Force
        }
    }
}

function Assert-AgentPrivateKeyDeployment([string]$HostPrivateKeyPath) {
    $targetPath = "/run/hotshop-agent/agent-service-private.pem"
    $containerId = docker compose @script:composeArguments ps -q agent-service
    Assert-DockerSucceeded "Resolve Agent container"
    if ([string]::IsNullOrWhiteSpace($containerId)) {
        throw "Agent container was not found"
    }

    $effectiveUid = @(
        docker compose @script:composeArguments exec -T agent-service sh -lc `
            "awk '/^Uid:/{print `$2}' /proc/1/status"
    ) -join ""
    Assert-DockerSucceeded "Read Agent business PID UID"
    Assert-Equal $effectiveUid "10001" "Agent business PID effective UID"

    docker compose @script:composeArguments exec -T --user 10001 agent-service sh -lc `
        "test -r '$targetPath'"
    Assert-DockerSucceeded "Verify Agent owner can read runtime private key"
    $ownership = @(
        docker compose @script:composeArguments exec -T --user 10001 agent-service sh -lc `
            "stat -c '%u:%g:%a' '$targetPath'"
    ) -join ""
    Assert-DockerSucceeded "Read Agent runtime private key ownership"
    Assert-Equal $ownership "10001:10001:400" "Agent runtime private key ownership"

    docker compose @script:composeArguments exec -T --user 10002 agent-service sh -lc `
        "test ! -r '$targetPath'"
    Assert-DockerSucceeded "Verify unrelated UID cannot read Agent runtime private key"

    $inspectText = @((docker inspect $containerId)) -join "`n"
    Assert-DockerSucceeded "Inspect Agent runtime configuration"
    Assert-NoPrivateKey $inspectText "Agent container inspection"

    Assert-ImageHistoryHasNoPrivateKeys $script:ownedImages[2] @($HostPrivateKeyPath) "Agent"

    return [ordered]@{
        businessPidEffectiveUid = 10001
        ownerCanRead = $true
        runtimeKeyOwnership = "10001:10001:400"
        unrelatedUidCanRead = $false
        privateKeyInContainerInspection = $false
        privateKeyBytesInRuntimeImageHistory = $false
    }
}

function Assert-JavaPrivateKeyDeployment(
    [string]$Service,
    [string[]]$HostPrivateKeyPaths,
    [string[]]$RuntimePrivateKeyPaths,
    [string]$RuntimeImage,
    [string]$Label
) {
    $containerId = docker compose @script:composeArguments ps -q $Service
    Assert-DockerSucceeded "Resolve $Label container"
    if ([string]::IsNullOrWhiteSpace($containerId)) {
        throw "$Label container was not found"
    }

    $effectiveUid = @(
        docker compose @script:composeArguments exec -T $Service sh -lc `
            "awk '/^Uid:/{print `$2}' /proc/1/status"
    ) -join ""
    Assert-DockerSucceeded "Read $Label business PID UID"
    Assert-Equal $effectiveUid "10001" "$Label business PID effective UID"

    foreach ($runtimePrivateKeyPath in $RuntimePrivateKeyPaths) {
        docker compose @script:composeArguments exec -T --user 10001 $Service sh -lc `
            "test -r '$runtimePrivateKeyPath'"
        Assert-DockerSucceeded "Verify $Label owner can read $runtimePrivateKeyPath"
        $ownership = @(
            docker compose @script:composeArguments exec -T --user 10001 $Service sh -lc `
                "stat -c '%u:%g:%a' '$runtimePrivateKeyPath'"
        ) -join ""
        Assert-DockerSucceeded "Read $Label private key ownership for $runtimePrivateKeyPath"
        Assert-Equal $ownership "10001:10001:400" "$Label private key ownership"

        docker compose @script:composeArguments exec -T --user 10002 $Service sh -lc `
            "test ! -r '$runtimePrivateKeyPath'"
        Assert-DockerSucceeded "Verify unrelated UID cannot read $Label $runtimePrivateKeyPath"
    }

    $inspectText = @((docker inspect $containerId)) -join "`n"
    Assert-DockerSucceeded "Inspect $Label runtime configuration"
    Assert-NoPrivateKey $inspectText "$Label container inspection"

    Assert-ImageHistoryHasNoPrivateKeys $RuntimeImage $HostPrivateKeyPaths $Label

    return [ordered]@{
        businessPidEffectiveUid = 10001
        ownerCanRead = $true
        runtimeKeyOwnership = @($RuntimePrivateKeyPaths | ForEach-Object { "$_=10001:10001:400" })
        unrelatedUidCanRead = $false
        privateKeyInContainerInspection = $false
        privateKeyBytesInRuntimeImageHistory = $false
    }
}

function Write-JsonEvidence([string]$Name, [object]$Value) {
    $json = $Value | ConvertTo-Json -Depth 20
    Assert-NoSensitiveValue $json "evidence $Name"
    Set-Content -LiteralPath (Join-Path $script:EvidenceDirectory $Name) `
        -Value $json -Encoding UTF8
}

function Assert-CleanProjectName {
    $conflicts = [System.Collections.Generic.List[string]]::new()
    foreach ($resource in @(
        @{Name = "containers"; Arguments = @("ps", "-aq", "--filter", "label=com.docker.compose.project=$script:ProjectName")},
        @{Name = "volumes"; Arguments = @("volume", "ls", "-q", "--filter", "label=com.docker.compose.project=$script:ProjectName")},
        @{Name = "networks"; Arguments = @("network", "ls", "-q", "--filter", "label=com.docker.compose.project=$script:ProjectName")}
    )) {
        $query = Invoke-HotShopNativeCommand -FilePath "docker" -Arguments $resource.Arguments
        if ($query.ExitCode -ne 0) {
            throw "Preflight lookup for $($resource.Name) failed with exit code $($query.ExitCode)"
        }
        if (@(Get-HotShopNativeOutputLines $query.Stdout).Count -gt 0) {
            [void]$conflicts.Add($resource.Name)
        }
    }
    foreach ($ownedImage in $script:ownedImages) {
        $imageQuery = Invoke-HotShopNativeCommand -FilePath "docker" `
            -Arguments @("image", "inspect", $ownedImage)
        if ($imageQuery.ExitCode -eq 0) {
            [void]$conflicts.Add("image:$ownedImage")
        }
        elseif ($imageQuery.ExitCode -ne 1) {
            throw "Preflight lookup for image $ownedImage failed with exit code $($imageQuery.ExitCode)"
        }
    }
    if ($conflicts.Count -gt 0) {
        throw "Resource ownership conflict; refusing destructive reuse for $script:ProjectName`: $($conflicts -join ', ')"
    }
    if (Test-Path -LiteralPath $script:keyRoot) {
        throw "Temporary key path already exists; refusing reuse: $script:keyRoot"
    }
    $script:resourceOwnership.preflightPassed = $true
}

function Convert-DockerInspectJson([object]$Result, [string]$Step) {
    if ($Result.ExitCode -ne 0) {
        throw "$Step failed with exit code $($Result.ExitCode)"
    }
    try {
        $objects = @($Result.Stdout | ConvertFrom-Json)
    }
    catch {
        throw "$Step returned invalid Docker inspect JSON: $($_.Exception.Message)"
    }
    if ($objects.Count -ne 1) {
        throw "$Step returned $($objects.Count) objects instead of exactly one"
    }
    return $objects[0]
}

function Get-CleanupDockerInspectState(
    [System.Collections.Generic.List[string]]$Failures,
    [string]$Step,
    [ValidateSet("container", "volume", "network", "image")][string]$ResourceType,
    [string]$Identifier
) {
    try {
        $failureCountBefore = $Failures.Count
        $result = Invoke-HotShopCleanupNativeStep -Failures $Failures -Step $Step `
            -FilePath "docker" -Arguments @($ResourceType, "inspect", $Identifier) `
            -AcceptedExitCodes @(0, 1)
        if ($result.ExitCode -eq 0) {
            try {
                $objects = @($result.Stdout | ConvertFrom-Json)
            }
            catch {
                [void]$Failures.Add("$Step returned invalid Docker inspect JSON: $($_.Exception.Message)")
                return [pscustomobject]@{State = "error"; Object = $null}
            }
            if ($objects.Count -ne 1) {
                [void]$Failures.Add("$Step returned $($objects.Count) objects instead of exactly one")
                return [pscustomobject]@{State = "error"; Object = $null}
            }
            return [pscustomobject]@{State = "present"; Object = $objects[0]}
        }
        if ($result.ExitCode -eq 1 -and
                $result.Stderr -match "(?i)(no such (container|volume|image)|network .+ not found)") {
            return [pscustomobject]@{State = "missing"; Object = $null}
        }
        if ($Failures.Count -eq $failureCountBefore) {
            [void]$Failures.Add("$Step failed with exit code $($result.ExitCode)")
        }
        return [pscustomobject]@{State = "error"; Object = $null}
    }
    catch {
        [void]$Failures.Add("$Step raised an isolated cleanup exception: $($_.Exception.Message)")
        return [pscustomobject]@{State = "error"; Object = $null}
    }
}

function Get-DockerInspectProjectLabel([object]$InspectObject, [string]$ResourceType) {
    $labels = if ($ResourceType -ceq "container") {
        $InspectObject.Config.Labels
    }
    else {
        $InspectObject.Labels
    }
    if ($null -eq $labels) {
        return ""
    }
    $property = $labels.PSObject.Properties["com.docker.compose.project"]
    if ($null -eq $property) {
        return ""
    }
    return [string]$property.Value
}

function Record-CreatedProjectResources {
    if (-not $script:resourceOwnership.preflightPassed -or
            -not $script:resourceOwnership.creationAuthorized) {
        throw "Resource creation was not authorized by a clean preflight"
    }

    $recordFailures = [System.Collections.Generic.List[string]]::new()
    $containerQuery = Invoke-HotShopCleanupNativeStep -Failures $recordFailures `
        -Step "Record invocation containers" -FilePath "docker" `
        -Arguments @("ps", "-aq", "--filter", "label=com.docker.compose.project=$script:ProjectName")
    if ($containerQuery.ExitCode -eq 0) {
        $script:resourceOwnership.containers = @(
            Get-HotShopNativeOutputLines $containerQuery.Stdout | Where-Object {
                $_ -match "^[0-9a-f]{12,64}$"
            }
        )
    }

    $volumeQuery = Invoke-HotShopCleanupNativeStep -Failures $recordFailures `
        -Step "Record invocation volumes" -FilePath "docker" `
        -Arguments @("volume", "ls", "-q", "--filter", "label=com.docker.compose.project=$script:ProjectName")
    if ($volumeQuery.ExitCode -eq 0) {
        $script:resourceOwnership.volumes = @(Get-HotShopNativeOutputLines $volumeQuery.Stdout)
    }

    $networkQuery = Invoke-HotShopCleanupNativeStep -Failures $recordFailures `
        -Step "Record invocation networks" -FilePath "docker" `
        -Arguments @("network", "ls", "-q", "--filter", "label=com.docker.compose.project=$script:ProjectName")
    if ($networkQuery.ExitCode -eq 0) {
        $script:resourceOwnership.networks = @(
            Get-HotShopNativeOutputLines $networkQuery.Stdout | Where-Object {
                $_ -match "^[0-9a-f]{12,64}$"
            }
        )
    }

    foreach ($ownedImage in $script:ownedImages) {
        $imageQuery = Invoke-HotShopNativeCommand -FilePath "docker" `
            -Arguments @("image", "inspect", $ownedImage)
        if ($imageQuery.ExitCode -eq 0) {
            $imageObject = Convert-DockerInspectJson $imageQuery "Record invocation image $ownedImage"
            $script:resourceOwnership.images[$ownedImage] = [string]$imageObject.Id
        }
        elseif ($imageQuery.ExitCode -ne 1) {
            [void]$recordFailures.Add(
                "Record invocation image $ownedImage failed with exit code $($imageQuery.ExitCode)"
            )
        }
    }

    if ($recordFailures.Count -gt 0) {
        throw "Could not fully record invocation-owned resources: $($recordFailures -join '; ')"
    }
}

function Invoke-RequiredNativeCommand([string]$Step, [string[]]$Arguments) {
    $result = Invoke-HotShopNativeCommand -FilePath "docker" -Arguments $Arguments
    if ($result.ExitCode -ne 0) {
        throw "$Step failed with exit code $($result.ExitCode)"
    }
    return $result
}

function Start-OwnershipFailureProbe {
    if ([Environment]::GetEnvironmentVariable("HOTSHOP_CI_OWNERSHIP_TEST") -ne "1") {
        throw "Ownership test modes require HOTSHOP_CI_OWNERSHIP_TEST=1"
    }
    $probeImage = "alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce"
    [void](Invoke-RequiredNativeCommand "Pull fixed ownership probe image" @(
        "image", "pull", $probeImage
    ))
    $script:resourceOwnership.keyDirectory = $true
    New-Item -ItemType Directory -Path $script:keyRoot | Out-Null
    $script:resourceOwnership.creationAuthorized = $true

    foreach ($ownedImage in $script:ownedImages) {
        [void](Invoke-RequiredNativeCommand "Create probe image tag $ownedImage" `
            @("image", "tag", $probeImage, $ownedImage))
        $imageId = Invoke-RequiredNativeCommand "Record probe image $ownedImage" `
            @("image", "inspect", $ownedImage)
        $imageObject = Convert-DockerInspectJson $imageId "Record probe image $ownedImage"
        $script:resourceOwnership.images[$ownedImage] = [string]$imageObject.Id
    }

    $networkName = "$($script:ProjectName)_ownership_probe"
    $network = Invoke-RequiredNativeCommand "Create probe network" @(
        "network", "create", "--label",
        "com.docker.compose.project=$($script:ProjectName)", $networkName
    )
    $script:resourceOwnership.networks = @($network.Stdout.Trim())

    $volumeName = "$($script:ProjectName)_ownership_probe"
    [void](Invoke-RequiredNativeCommand "Create probe volume" @(
        "volume", "create", "--label",
        "com.docker.compose.project=$($script:ProjectName)", $volumeName
    ))
    $script:resourceOwnership.volumes = @($volumeName)

    $containerName = "$($script:ProjectName)-ownership-probe"
    $container = Invoke-RequiredNativeCommand "Create probe container" @(
        "container", "create", "--label",
        "com.docker.compose.project=$($script:ProjectName)",
        "--name", $containerName, $probeImage, "sleep", "600"
    )
    $script:resourceOwnership.containers = @($container.Stdout.Trim())
    if ([Environment]::GetEnvironmentVariable(
            "HOTSHOP_CI_INJECT_CLEANUP_PARSE_EXCEPTION"
        ) -eq "1") {
        # `docker container inspect --help` exits zero with non-JSON output. Recording this
        # synthetic identifier before the real ID proves JSON parse failure isolation while
        # the exact invocation-owned container remains available for the following cleanup.
        $script:resourceOwnership.containers = @("--help") +
            @($script:resourceOwnership.containers)
    }
    throw "Synthetic business startup failure after invocation-owned resources were created"
}

try {
    foreach ($secretName in @(
        "MYSQL_ROOT_PASSWORD",
        "REDIS_CACHE_PASSWORD",
        "REDIS_SECKILL_PASSWORD"
    )) {
        Add-SensitiveValue ([Environment]::GetEnvironmentVariable($secretName))
    }
    docker info --format "{{.ServerVersion}}" | Out-Null
    Assert-DockerSucceeded "Docker availability check"
    Assert-CleanProjectName

    if ($OwnershipTestMode -eq "preflight-only") {
        if ([Environment]::GetEnvironmentVariable("HOTSHOP_CI_OWNERSHIP_TEST") -ne "1") {
            throw "Ownership test modes require HOTSHOP_CI_OWNERSHIP_TEST=1"
        }
        Write-Host "Resource ownership preflight passed"
        return
    }
    if ($OwnershipTestMode -eq "fail-after-resource-startup") {
        Start-OwnershipFailureProbe
    }

    # The exact key path was absent during preflight. Authorize only this invocation to
    # remove a partially or fully created directory immediately before key generation.
    $resourceOwnership.keyDirectory = $true
    & "$PSScriptRoot\generate-auth-keys.ps1" -OutputDirectory $keyRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Authentication key generation failed"
    }
    $hostAgentPrivateKey = Join-Path $keyRoot "agent-service-private.pem"
    $hostUserPrivateKey = Join-Path $keyRoot "user-private.pem"
    $hostDelegationPrivateKey = Join-Path $keyRoot "agent-delegation-private.pem"
    $hostAdminPrivateKey = Join-Path $keyRoot "administrator-private.pem"
    foreach ($privateKey in @(
        $hostAgentPrivateKey,
        $hostUserPrivateKey,
        $hostDelegationPrivateKey,
        $hostAdminPrivateKey
    )) {
        Add-PrivateKeyMaterial $privateKey
    }

    docker compose @composeArguments --profile app --profile agent config --quiet
    Assert-DockerSucceeded "Compose configuration validation"

    # All exact project resources and image tags were absent at preflight. From this point,
    # a partial Compose build/start may create any subset, so this invocation owns only
    # those exact names and may clean them in finally.
    $resourceOwnership.creationAuthorized = $true
    $composeUpArguments = @("compose") + @($composeArguments) + @(
        "--profile", "app", "--profile", "agent", "up", "-d", "--build",
        "mysql", "redis-cache", "redis-seckill", "database-migrator",
        "portal-service", "admin-service", "agent-service"
    )
    $composeStartup = Invoke-HotShopNativeCommand -FilePath "docker" -Arguments $composeUpArguments
    Record-CreatedProjectResources
    if ($composeStartup.ExitCode -ne 0) {
        throw "Isolated TASK-16 Compose startup failed with exit code $($composeStartup.ExitCode)"
    }

    $portalBase = Get-ServiceBaseUrl "portal-service" 8080
    $adminBase = Get-ServiceBaseUrl "admin-service" 8088
    $agentBase = Get-ServiceBaseUrl "agent-service" 8090
    Wait-Http "$portalBase/v3/api-docs/public" "portal-service"
    Wait-Http "$adminBase/v3/api-docs/admin" "admin-service"
    Wait-Http "$agentBase/health/ready" "agent-service"
    $privateKeyEvidence = Assert-AgentPrivateKeyDeployment $hostAgentPrivateKey
    Write-JsonEvidence "agent-private-key-deployment.json" $privateKeyEvidence
    $portalPrivateKeyEvidence = Assert-JavaPrivateKeyDeployment `
        "portal-service" @($hostUserPrivateKey, $hostDelegationPrivateKey) `
        @("/run/secrets/hotshop/user-private.pem", "/run/secrets/hotshop/agent-delegation-private.pem") `
        $ownedImages[0] "Portal"
    Write-JsonEvidence "portal-private-key-deployment.json" $portalPrivateKeyEvidence
    $adminPrivateKeyEvidence = Assert-JavaPrivateKeyDeployment `
        "admin-service" @($hostAdminPrivateKey) `
        @("/run/secrets/hotshop/administrator-private.pem") `
        $ownedImages[1] "Admin"
    Write-JsonEvidence "admin-private-key-deployment.json" $adminPrivateKeyEvidence
    Assert-Equal (Get-Scalar "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;") `
        "1.8" "Fresh Compose Flyway version"
    Assert-Equal (Get-Scalar @"
SELECT COUNT(*) FROM information_schema.referential_constraints
WHERE constraint_schema=DATABASE();
"@) "0" "Fresh Compose foreign key count"

    $userName = "task16-user-$suffix"
    $otherUserName = "task16-other-$suffix"
    $adminName = "task16-admin-$suffix"
    $userPassword = "Task16User${suffix}Aa1!"
    $otherPassword = "Task16Other${suffix}Aa1!"
    $adminPassword = "Task16Admin${suffix}Aa1!"
    $promptSentinel = "TASK16_PROMPT_SENTINEL_$suffix"
    Add-SensitiveValue $userPassword
    Add-SensitiveValue $otherPassword
    Add-SensitiveValue $adminPassword
    Add-SensitiveValue $promptSentinel

    foreach ($identity in @(
        @{username = $userName; password = $userPassword; email = "$userName@hotshop.invalid"},
        @{username = $otherUserName; password = $otherPassword; email = "$otherUserName@hotshop.invalid"},
        @{username = $adminName; password = $adminPassword; email = "$adminName@hotshop.invalid"}
    )) {
        $register = Invoke-Http POST "$portalBase/api/v1/auth/register" `
            (New-TraceHeaders "task16-register") $identity
        Assert-Status $register 201 "Register $($identity.username)"
    }

    [void](Invoke-MySql @"
UPDATE app_user SET role='ROLE_ADMIN', updated_at=UTC_TIMESTAMP(6)
WHERE username='$adminName';
INSERT INTO catalog_product (
    product_id, sku, name, price, stock, category, description, status,
    version, created_at, updated_at
) VALUES
    (916001, 'TASK16-A', 'Task16 Product A', 123.45, 12, 'Verification',
     'TASK-16 isolated verification product', 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (916002, 'TASK16-B', 'Task16 Product B', 150.00, 7, 'Verification',
     'TASK-16 isolated comparison product', 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
"@)

    $userId = [long](Get-Scalar "SELECT user_id FROM app_user WHERE username='$userName';")
    $otherUserId = [long](Get-Scalar "SELECT user_id FROM app_user WHERE username='$otherUserName';")
    $adminId = [long](Get-Scalar "SELECT user_id FROM app_user WHERE username='$adminName';")
    $initialSnapshot = Get-BusinessSnapshot
    Write-JsonEvidence "database-initial.json" $initialSnapshot

    $userLogin = Invoke-Http POST "$portalBase/api/v1/auth/login" `
        (New-TraceHeaders "task16-user-login") @{username = $userName; password = $userPassword}
    Assert-Status $userLogin 200 "User login"
    $userAccess = (Convert-JsonBody $userLogin "User login").accessToken
    Add-SensitiveValue $userAccess

    $otherLogin = Invoke-Http POST "$portalBase/api/v1/auth/login" `
        (New-TraceHeaders "task16-other-login") @{username = $otherUserName; password = $otherPassword}
    Assert-Status $otherLogin 200 "Other User login"
    $otherAccess = (Convert-JsonBody $otherLogin "Other User login").accessToken
    Add-SensitiveValue $otherAccess

    $adminLogin = Invoke-Http POST "$adminBase/admin/api/v1/auth/login" `
        (New-TraceHeaders "task16-admin-login") @{username = $adminName; password = $adminPassword}
    Assert-Status $adminLogin 200 "Administrator login"
    $adminAccess = (Convert-JsonBody $adminLogin "Administrator login").accessToken
    Add-SensitiveValue $adminAccess

    $userScopes = @(
        "catalog:read",
        "orders:self:read",
        "reservations:self:read",
        "purchase-drafts:create"
    )
    $userSession = New-AgentSession $agentBase "/api/v1/agent" $userAccess `
        @{scopes = $userScopes}
    [void](Invoke-AgentTool $agentBase "/api/v1/agent" $userSession.id $userAccess `
        "search_products" @{keyword = "Task16"; limit = 10})
    [void](Invoke-AgentTool $agentBase "/api/v1/agent" $userSession.id $userAccess `
        "get_product" @{productId = "916001"})
    [void](Invoke-AgentTool $agentBase "/api/v1/agent" $userSession.id $userAccess `
        "compare_products" @{productIds = @("916001", "916002")})
    $ordersBeforeRun = Invoke-AgentTool $agentBase "/api/v1/agent" $userSession.id $userAccess `
        "list_my_orders" @{limit = 10}
    $ordersBeforeSummary = ($ordersBeforeRun.Events | Where-Object type -eq "tool.completed").data.summary
    if ($ordersBeforeSummary -notmatch "0 item\(s\)") {
        throw "User Agent Order query did not begin empty"
    }
    [void](Invoke-AgentTool $agentBase "/api/v1/agent" $userSession.id $userAccess `
        "list_my_reservations" @{limit = 10})

    $beforeDraft = Get-BusinessSnapshot
    $draftRun = Invoke-AgentTool $agentBase "/api/v1/agent" $userSession.id $userAccess `
        "create_purchase_draft" @{items = @(@{productId = "916001"; quantity = 2})}
    $afterDraft = Get-BusinessSnapshot
    Assert-Equal $afterDraft.productOneStock $beforeDraft.productOneStock "Draft stock"
    Assert-Equal $afterDraft.orderCount $beforeDraft.orderCount "Draft Order count"
    Assert-Equal $afterDraft.reservationCount $beforeDraft.reservationCount "Draft Reservation count"
    Assert-Equal $afterDraft.purchaseDraftCount ($beforeDraft.purchaseDraftCount + 1) "Draft count"

    $draftMessage = @($draftRun.Events | Where-Object type -eq "message.delta" | ForEach-Object {
        [string]$_.data.delta
    }) -join ""
    try {
        $draftSse = $draftMessage | ConvertFrom-Json
    }
    catch {
        throw "FakeModel Purchase Draft message.delta is not valid structured JSON"
    }
    if ($draftSse.tool -ne "create_purchase_draft" -or $draftSse.outcome -ne "SUCCESS" -or
            $null -eq $draftSse.purchaseDraft) {
        throw "FakeModel Purchase Draft SSE summary is incomplete"
    }
    $draftId = [string]$draftSse.purchaseDraft.draftId
    if ($draftId -notmatch "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") {
        throw "FakeModel Purchase Draft SSE did not expose a valid draftId"
    }
    $sseItems = @($draftSse.purchaseDraft.items)
    if ($sseItems.Count -ne 1) {
        throw "FakeModel Purchase Draft SSE item count is invalid"
    }
    Assert-Equal $sseItems[0].productId "916001" "SSE Draft Product"
    Assert-Equal $sseItems[0].quantity "2" "SSE Draft quantity"
    Assert-Equal $sseItems[0].unitPriceSnapshot "123.45" "SSE Draft unit price snapshot"
    Assert-Equal $sseItems[0].lineAmountSnapshot "246.90" "SSE Draft line amount snapshot"
    Assert-Equal $draftSse.purchaseDraft.totalPriceSnapshot "246.90" "SSE Draft total snapshot"
    Assert-Equal $draftSse.purchaseDraft.currency "CNY" "SSE Draft currency"
    Assert-Equal $draftSse.purchaseDraft.confirmationRequired "True" "SSE confirmation requirement"
    if ([DateTimeOffset]::Parse([string]$draftSse.purchaseDraft.validUntil) -le [DateTimeOffset]::UtcNow) {
        throw "FakeModel Purchase Draft SSE validity is not in the future"
    }
    if ([string]::IsNullOrWhiteSpace([string]$draftSse.purchaseDraft.nextStep)) {
        throw "FakeModel Purchase Draft SSE omitted the explicit confirmation next step"
    }
    $draftFacts = (Invoke-MySql @"
SELECT CONCAT_WS('|', d.user_id, d.action_type, d.status,
    DATE_FORMAT(d.valid_until, '%Y-%m-%dT%H:%i:%s.%fZ'),
    i.product_id, i.quantity, i.unit_price_snapshot, i.line_amount_snapshot)
FROM purchase_draft d JOIN purchase_draft_item i ON i.draft_id=d.draft_id
WHERE d.draft_id='$draftId';
"@).Split("|")
    if ($draftFacts.Count -ne 8) {
        throw "Purchase Draft evidence is incomplete"
    }
    Assert-Equal $draftFacts[0] $userId "Draft owner"
    Assert-Equal $draftFacts[1] "CREATE_ORDER" "Draft action"
    Assert-Equal $draftFacts[2] "ACTIVE" "Draft state"
    Assert-Equal $draftFacts[4] "916001" "Draft Product"
    Assert-Equal $draftFacts[5] "2" "Draft quantity"
    Assert-Equal $draftFacts[6] "123.45" "Draft unit price snapshot"
    Assert-Equal $draftFacts[7] "246.90" "Draft line amount snapshot"
    if ([DateTimeOffset]::Parse($draftFacts[3]) -le [DateTimeOffset]::UtcNow) {
        throw "Purchase Draft validity is not in the future"
    }
    $sseValidUntil = [DateTimeOffset]::Parse([string]$draftSse.purchaseDraft.validUntil)
    $databaseValidUntil = [DateTimeOffset]::Parse($draftFacts[3])
    if ([Math]::Abs(($sseValidUntil - $databaseValidUntil).TotalMilliseconds) -gt 1) {
        throw "Purchase Draft SSE and database validity timestamps differ"
    }
    Write-JsonEvidence "purchase-draft.json" ([ordered]@{
        correlationMethod = "SSE_DRAFT_ID_THEN_READ_ONLY_DB_CROSS_CHECK"
        sseReturnedDraftId = $true
        draftId = $draftId
        userId = $userId
        actionType = $draftFacts[1]
        status = $draftFacts[2]
        validUntil = $draftFacts[3]
        productId = [long]$draftFacts[4]
        quantity = [int]$draftFacts[5]
        unitPriceSnapshot = $draftFacts[6]
        lineAmountSnapshot = $draftFacts[7]
        stockBefore = $beforeDraft.productOneStock
        stockAfter = $afterDraft.productOneStock
        orderCountAfter = $afterDraft.orderCount
        reservationCountAfter = $afterDraft.reservationCount
    })

    $issueResponse = Invoke-Http POST `
        "$portalBase/api/v1/orders/purchase-drafts/$draftId/confirmations" `
        (New-TraceHeaders "task16-confirm-issue" $userAccess) @{actionType = "CREATE_ORDER"}
    Assert-Status $issueResponse 200 "Issue Purchase confirmation"
    $issueBody = Convert-JsonBody $issueResponse "Issue Purchase confirmation"
    $confirmationToken = [string]$issueBody.confirmationToken
    Add-SensitiveValue $confirmationToken
    if ($confirmationToken.Length -lt 43) {
        throw "Confirmation token is unexpectedly short"
    }
    $storedTokenHash = Get-Scalar `
        "SELECT token_hash FROM purchase_confirmation WHERE draft_id='$draftId';"
    Assert-Equal $storedTokenHash (Get-Sha256 $confirmationToken) "Opaque confirmation hash"
    if ($storedTokenHash -eq $confirmationToken) {
        throw "Database stored the plaintext confirmation token"
    }

    $consumeBody = [ordered]@{
        confirmationToken = $confirmationToken
        draftId = $draftId
        actionType = "CREATE_ORDER"
        items = @(@{productId = "916001"; quantity = 2})
    }
    $crossUser = Invoke-Http POST "$portalBase/api/v1/orders/purchase-confirmations/consume" `
        (New-TraceHeaders "task16-cross-user" $otherAccess) $consumeBody
    Assert-Status $crossUser 409 "Reject cross-User confirmation"
    Assert-Equal (Get-ProblemCode $crossUser) "PURCHASE_CONFIRMATION_INVALID" `
        "Cross-User confirmation code"

    $tamperedBody = [ordered]@{
        confirmationToken = $confirmationToken
        draftId = $draftId
        actionType = "CREATE_ORDER"
        items = @(@{productId = "916001"; quantity = 3})
    }
    $tampered = Invoke-Http POST "$portalBase/api/v1/orders/purchase-confirmations/consume" `
        (New-TraceHeaders "task16-tampered" $userAccess) $tamperedBody
    Assert-Status $tampered 409 "Reject tampered confirmation parameters"
    Assert-Equal (Get-ProblemCode $tampered) "PURCHASE_CONFIRMATION_MISMATCH" `
        "Tampered confirmation code"

    $wrongActionBody = [ordered]@{
        confirmationToken = $confirmationToken
        draftId = $draftId
        actionType = "REFUND_ORDER"
        items = @(@{productId = "916001"; quantity = 2})
    }
    $wrongAction = Invoke-Http POST "$portalBase/api/v1/orders/purchase-confirmations/consume" `
        (New-TraceHeaders "task16-wrong-action" $userAccess) $wrongActionBody
    Assert-Status $wrongAction 409 "Reject wrong confirmation action"
    Assert-Equal (Get-ProblemCode $wrongAction) "PURCHASE_CONFIRMATION_INVALID" `
        "Wrong action confirmation code"

    Assert-Equal (Get-Scalar "SELECT status FROM purchase_confirmation WHERE draft_id='$draftId';") `
        "ISSUED" "Confirmation state after denied attempts"
    Assert-Equal (Get-Scalar "SELECT COUNT(*) FROM sales_order;") "0" `
        "Order count before valid confirmation"
    Assert-Equal (Get-Scalar "SELECT stock FROM catalog_product WHERE product_id=916001;") `
        "12" "Stock before valid confirmation"

    $consumeResponse = Invoke-Http POST "$portalBase/api/v1/orders/purchase-confirmations/consume" `
        (New-TraceHeaders "task16-consume" $userAccess) $consumeBody
    Assert-Status $consumeResponse 200 "Consume Purchase confirmation"
    $consumeResult = Convert-JsonBody $consumeResponse "Consume Purchase confirmation"
    $orderId = [string]$consumeResult.orderId
    if ([string]::IsNullOrWhiteSpace($orderId)) {
        throw "Confirmation consume response did not contain orderId"
    }
    Assert-Equal (Get-Scalar "SELECT status FROM purchase_confirmation WHERE draft_id='$draftId';") `
        "CONSUMED" "Consumed confirmation state"
    Assert-Equal (Get-Scalar "SELECT COUNT(*) FROM sales_order WHERE order_id='$orderId' AND user_id=$userId;") `
        "1" "Created owned Order"
    Assert-Equal (Get-Scalar "SELECT stock FROM catalog_product WHERE product_id=916001;") `
        "10" "Stock after valid confirmation"

    $replay = Invoke-Http POST "$portalBase/api/v1/orders/purchase-confirmations/consume" `
        (New-TraceHeaders "task16-replay" $userAccess) $consumeBody
    Assert-Status $replay 409 "Reject confirmation replay"
    Assert-Equal (Get-ProblemCode $replay) "PURCHASE_CONFIRMATION_INVALID" `
        "Replay confirmation code"
    Assert-Equal (Get-Scalar "SELECT COUNT(*) FROM sales_order WHERE order_id='$orderId';") `
        "1" "Order count after replay"
    Assert-Equal (Get-Scalar "SELECT stock FROM catalog_product WHERE product_id=916001;") `
        "10" "Stock after replay"

    $ownedOrdersRun = Invoke-AgentTool $agentBase "/api/v1/agent" $userSession.id $userAccess `
        "list_my_orders" @{limit = 10}
    $ownedOrderSummary = ($ownedOrdersRun.Events | Where-Object type -eq "tool.completed").data.summary
    if ($ownedOrderSummary -notmatch "1 item\(s\)") {
        throw "User Agent did not return the owned Order"
    }
    $otherUserSession = New-AgentSession $agentBase "/api/v1/agent" $otherAccess `
        @{scopes = $userScopes}
    $otherOrdersRun = Invoke-AgentTool $agentBase "/api/v1/agent" $otherUserSession.id $otherAccess `
        "list_my_orders" @{limit = 10}
    $otherOrderSummary = ($otherOrdersRun.Events | Where-Object type -eq "tool.completed").data.summary
    if ($otherOrderSummary -notmatch "0 item\(s\)") {
        throw "Other User Agent observed an Order it does not own"
    }

    $adminSession = New-AgentSession $agentBase "/admin/api/v1/agent" $adminAccess @{}
    [void](Invoke-AgentTool $agentBase "/admin/api/v1/agent" $adminSession.id $adminAccess `
        "read_statistics" @{})
    [void](Invoke-AgentTool $agentBase "/admin/api/v1/agent" $adminSession.id $adminAccess `
        "read_anomaly_summary" @{})
    [void](Invoke-AgentTool $agentBase "/admin/api/v1/agent" $adminSession.id $adminAccess `
        "create_configuration_draft" @{
            configurationKey = "AGENT_TOOL_RESULT_LIMIT"
            proposedValue = 10
            reason = "Bound TASK-16 verification model context"
        })
    Assert-Equal (Get-Scalar @"
SELECT COUNT(*) FROM agent_configuration_draft
WHERE administrator_id=$adminId AND configuration_key='AGENT_TOOL_RESULT_LIMIT'
  AND JSON_UNQUOTE(proposed_value)='10' AND risk_level='LOW' AND status='DRAFT';
"@) "1" "Low-risk Administrator configuration Draft"

    $beforeProhibited = Get-BusinessSnapshot
    foreach ($attempt in @(
        @{route = "/api/v1/agent"; session = $userSession.id; token = $userAccess;
          tool = "sql"; arguments = @{query = "SELECT '$promptSentinel'"}},
        @{route = "/api/v1/agent"; session = $userSession.id; token = $userAccess;
          tool = "fetch_url"; arguments = @{url = "https://example.invalid/$promptSentinel"}},
        @{route = "/api/v1/agent"; session = $userSession.id; token = $userAccess;
          tool = "shell"; arguments = @{command = "echo $promptSentinel"}},
        @{route = "/admin/api/v1/agent"; session = $adminSession.id; token = $adminAccess;
          tool = "refund_order"; arguments = @{orderId = $orderId}},
        @{route = "/admin/api/v1/agent"; session = $adminSession.id; token = $adminAccess;
          tool = "compensate_inventory"; arguments = @{orderId = $orderId}},
        @{route = "/admin/api/v1/agent"; session = $adminSession.id; token = $adminAccess;
          tool = "replay_outbox"; arguments = @{eventId = "dynamic"}},
        @{route = "/admin/api/v1/agent"; session = $adminSession.id; token = $adminAccess;
          tool = "change_user_permission"; arguments = @{userId = [string]$userId; role = "ROLE_ADMIN"}},
        @{route = "/admin/api/v1/agent"; session = $adminSession.id; token = $adminAccess;
          tool = "ban_user"; arguments = @{userId = [string]$userId}}
    )) {
        [void](Invoke-AgentTool -AgentBase $agentBase -RoutePrefix $attempt.route `
            -SessionId $attempt.session -AccessToken $attempt.token -Tool $attempt.tool `
            -Arguments $attempt.arguments -ExpectedOutcome FAILURE `
            -ExpectedFailureCode "TOOL_NOT_ALLOWED")
    }
    $afterProhibited = Get-BusinessSnapshot
    Assert-Equal ($afterProhibited | ConvertTo-Json -Compress) `
        ($beforeProhibited | ConvertTo-Json -Compress) "Prohibited tool business facts"

    $userToolAuditCoverage = [ordered]@{}
    foreach ($toolName in @(
        "search_products",
        "get_product",
        "compare_products",
        "list_my_orders",
        "list_my_reservations",
        "create_purchase_draft"
    )) {
        $userToolAuditCoverage[$toolName] = [int](Get-Scalar @"
SELECT COUNT(*) FROM audit_log
WHERE action='AGENT_TOOL_INVOKED' AND source='AGENT_API'
  AND actor_type='AGENT' AND delegated_actor_id='$userId'
  AND JSON_UNQUOTE(JSON_EXTRACT(state_summary, '$.tool'))='$toolName';
"@)
    }
    $adminToolAuditCoverage = [ordered]@{}
    foreach ($toolName in @(
        "read_low_risk_statistics",
        "read_anomaly_summary",
        "create_low_risk_configuration_draft"
    )) {
        $adminToolAuditCoverage[$toolName] = [int](Get-Scalar @"
SELECT COUNT(*) FROM audit_log
WHERE action='AGENT_TOOL_INVOKED' AND source='AGENT_API'
  AND actor_type='ADMIN' AND actor_id='$adminId'
  AND JSON_UNQUOTE(JSON_EXTRACT(state_summary, '$.toolName'))='$toolName';
"@)
    }
    $auditEvidence = [ordered]@{
        agentToolInvocations = [int](Get-Scalar "SELECT COUNT(*) FROM audit_log WHERE action='AGENT_TOOL_INVOKED';")
        userAgentToolInvocations = [int](Get-Scalar "SELECT COUNT(*) FROM audit_log WHERE action='AGENT_TOOL_INVOKED' AND source='AGENT_API' AND actor_type='AGENT' AND delegated_actor_type='USER' AND delegated_actor_id='$userId';")
        adminAgentToolInvocations = [int](Get-Scalar "SELECT COUNT(*) FROM audit_log WHERE action='AGENT_TOOL_INVOKED' AND source='AGENT_API' AND actor_type='ADMIN' AND actor_id='$adminId';")
        delegationIssues = [int](Get-Scalar "SELECT COUNT(*) FROM audit_log WHERE action='AGENT_DELEGATION_ISSUED' AND delegated_actor_id='$userId';")
        confirmationIssued = [int](Get-Scalar "SELECT COUNT(*) FROM audit_log WHERE action='PURCHASE_CONFIRMATION_ISSUED' AND actor_id='$userId' AND result='SUCCESS';")
        confirmationConsumed = [int](Get-Scalar "SELECT COUNT(*) FROM audit_log WHERE action='PURCHASE_CONFIRMATION_CONSUMED' AND actor_id='$userId' AND result='SUCCESS';")
        confirmationDenied = [int](Get-Scalar "SELECT COUNT(*) FROM audit_log WHERE action LIKE 'PURCHASE_CONFIRMATION_%_DENIED' AND result='DENIED';")
        correlatedAgentAudits = [int](Get-Scalar "SELECT COUNT(*) FROM audit_log WHERE action='AGENT_TOOL_INVOKED' AND request_id IS NOT NULL AND trace_id REGEXP '^[0-9a-f]{32}$';")
        correlatedConfirmationIssued = [int](Get-Scalar "SELECT COUNT(*) FROM audit_log WHERE action='PURCHASE_CONFIRMATION_ISSUED' AND result='SUCCESS' AND request_id IS NOT NULL AND request_id<>'' AND trace_id REGEXP '^[0-9a-f]{32}$';")
        correlatedConfirmationDenied = [int](Get-Scalar "SELECT COUNT(*) FROM audit_log WHERE action LIKE 'PURCHASE_CONFIRMATION_%_DENIED' AND result='DENIED' AND request_id IS NOT NULL AND request_id<>'' AND trace_id REGEXP '^[0-9a-f]{32}$';")
        correlatedConfirmationConsumed = [int](Get-Scalar "SELECT COUNT(*) FROM audit_log WHERE action='PURCHASE_CONFIRMATION_CONSUMED' AND result='SUCCESS' AND request_id IS NOT NULL AND request_id<>'' AND trace_id REGEXP '^[0-9a-f]{32}$';")
        userToolCoverage = $userToolAuditCoverage
        administratorToolCoverage = $adminToolAuditCoverage
    }
    if ($auditEvidence.userAgentToolInvocations -lt 7 -or
            $auditEvidence.adminAgentToolInvocations -lt 3 -or
            $auditEvidence.confirmationIssued -ne 1 -or
            $auditEvidence.confirmationConsumed -ne 1 -or
            $auditEvidence.confirmationDenied -lt 4 -or
            $auditEvidence.correlatedAgentAudits -lt 10 -or
            $auditEvidence.correlatedConfirmationIssued -ne $auditEvidence.confirmationIssued -or
            $auditEvidence.correlatedConfirmationDenied -ne $auditEvidence.confirmationDenied -or
            $auditEvidence.correlatedConfirmationConsumed -ne $auditEvidence.confirmationConsumed) {
        throw "Audit evidence does not cover the required Agent and confirmation operations"
    }
    foreach ($entry in $userToolAuditCoverage.GetEnumerator()) {
        if ([int]$entry.Value -lt 1) {
            throw "Missing User Agent tool audit for $($entry.Key)"
        }
    }
    foreach ($entry in $adminToolAuditCoverage.GetEnumerator()) {
        if ([int]$entry.Value -lt 1) {
            throw "Missing Administrator Agent tool audit for $($entry.Key)"
        }
    }
    $unsafeAuditCount = [int](Get-Scalar @"
SELECT COUNT(*) FROM audit_log
WHERE LOWER(CAST(state_summary AS CHAR)) REGEXP
      'confirmation.?token|access.?token|refresh.?token|password|cookie|prompt|chain.?of.?thought';
"@)
    Assert-Equal $unsafeAuditCount 0 "Sensitive audit summary count"
    $exactSecretAuditCount = 0
    $auditSecrets = @(
        $confirmationToken,
        $userAccess,
        $otherAccess,
        $adminAccess,
        $promptSentinel
    ) + @($privateKeyMarkers)
    foreach ($auditSecret in $auditSecrets | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    }) {
        $escapedAuditSecret = ([string]$auditSecret).Replace("'", "''")
        $exactSecretAuditCount += [int](Get-Scalar @"
SELECT COUNT(*) FROM audit_log
WHERE LOCATE('$escapedAuditSecret', CAST(state_summary AS CHAR)) > 0;
"@)
    }
    Assert-Equal $exactSecretAuditCount 0 "Exact sensitive value audit summary count"
    Assert-Equal (Get-Scalar "SELECT COUNT(*) FROM purchase_confirmation WHERE token_hash='$confirmationToken';") `
        "0" "Plaintext confirmation token row count"
    Write-JsonEvidence "audit.json" $auditEvidence

    $finalSnapshot = Get-BusinessSnapshot
    Write-JsonEvidence "database-final.json" $finalSnapshot
    Write-JsonEvidence "summary.json" ([ordered]@{
        projectName = $ProjectName
        startedAt = $startedAt
        finishedAt = [DateTimeOffset]::UtcNow
        provider = "fake"
        userId = $userId
        otherUserId = $otherUserId
        administratorId = $adminId
        draftId = $draftId
        orderId = $orderId
        draftCorrelationMethod = "SSE_DRAFT_ID_THEN_READ_ONLY_DB_CROSS_CHECK"
        sseReturnedDraftId = $true
        legalUserTools = 8
        legalAdministratorTools = 3
        prohibitedToolsRejected = 8
        prohibitedAdministratorCategories = @(
            "refund",
            "compensation",
            "message-replay",
            "permission-change",
            "user-suspension"
        )
        agentPrivateKeyDeployment = $privateKeyEvidence
        portalPrivateKeyDeployment = $portalPrivateKeyEvidence
        adminPrivateKeyDeployment = $adminPrivateKeyEvidence
        productStockBeforeDraft = $beforeDraft.productOneStock
        productStockAfterDraft = $afterDraft.productOneStock
        productStockAfterConsumeAndReplay = $finalSnapshot.productOneStock
        orderCount = $finalSnapshot.orderCount
        audit = $auditEvidence
    })

    $composeLogs = docker compose @composeArguments logs --no-color `
        mysql redis-cache redis-seckill database-migrator portal-service admin-service agent-service
    Assert-DockerSucceeded "Collect Compose logs"
    $composeLogText = @($composeLogs) -join "`n"
    Assert-NoSensitiveValue $composeLogText "Compose logs"
    Set-Content -LiteralPath (Join-Path $EvidenceDirectory "compose-logs.txt") `
        -Value $composeLogText -Encoding UTF8

    $allSse = (Get-ChildItem -LiteralPath $EvidenceDirectory -Filter "sse-*.txt" | ForEach-Object {
        Get-Content -LiteralPath $_.FullName -Raw
    }) -join "`n"
    Assert-NoSensitiveValue $allSse "persisted SSE evidence"
    if ($allSse -match "(?i)confirmationToken|accessToken|refreshToken|<thinking>|<analysis>") {
        throw "SSE evidence contains a forbidden sensitive field or hidden-reasoning marker"
    }

    Write-Host "TASK-16 isolated Compose verification passed"
    Write-Host "Evidence: $EvidenceDirectory"
}
catch {
    $verificationFailure = $_
    try {
        if ($resourceOwnership.containers -or $resourceOwnership.volumes -or $resourceOwnership.networks) {
            $failureLogs = docker compose @composeArguments logs --no-color
            $failureText = Redact-SensitiveValues (@($failureLogs) -join "`n")
            Assert-NoSensitiveValue $failureText "redacted failure logs"
            Assert-NoPrivateKey $failureText "redacted failure logs"
            Set-Content -LiteralPath (Join-Path $EvidenceDirectory "failure-logs-redacted.txt") `
                -Value $failureText -Encoding UTF8
        }
    }
    catch {
        # Evidence collection must not prevent cleanup or hide the original failure.
    }
    throw
}
finally {
    $cleanupFailures = [System.Collections.Generic.List[string]]::new()
    try {
        try {
            foreach ($containerId in @($resourceOwnership.containers)) {
                try {
                    $inspection = Get-CleanupDockerInspectState $cleanupFailures `
                        "Inspect invocation container ownership $containerId" `
                        "container" $containerId
                    if ($inspection.State -ceq "present") {
                        $label = Get-DockerInspectProjectLabel $inspection.Object "container"
                        if ($label -ceq $ProjectName) {
                            [void](Invoke-HotShopCleanupNativeStep -Failures $cleanupFailures `
                                -Step "Remove invocation container $containerId" `
                                -FilePath "docker" `
                                -Arguments @("container", "rm", "--force", $containerId))
                        }
                        else {
                            [void]$cleanupFailures.Add(
                                "Refused container with changed project ownership: $containerId"
                            )
                        }
                    }
                }
                catch {
                    [void]$cleanupFailures.Add(
                        "Container cleanup exception for $containerId`: $($_.Exception.Message)"
                    )
                }
            }
        }
        catch {
            [void]$cleanupFailures.Add("Container cleanup phase failed: $($_.Exception.Message)")
        }

        try {
            foreach ($volumeName in @($resourceOwnership.volumes)) {
                try {
                    $inspection = Get-CleanupDockerInspectState $cleanupFailures `
                        "Inspect invocation volume ownership $volumeName" `
                        "volume" $volumeName
                    if ($inspection.State -ceq "present") {
                        $label = Get-DockerInspectProjectLabel $inspection.Object "volume"
                        if ($label -ceq $ProjectName) {
                            [void](Invoke-HotShopCleanupNativeStep -Failures $cleanupFailures `
                                -Step "Remove invocation volume $volumeName" `
                                -FilePath "docker" `
                                -Arguments @("volume", "rm", "--force", $volumeName))
                        }
                        else {
                            [void]$cleanupFailures.Add(
                                "Refused volume with changed project ownership: $volumeName"
                            )
                        }
                    }
                }
                catch {
                    [void]$cleanupFailures.Add(
                        "Volume cleanup exception for $volumeName`: $($_.Exception.Message)"
                    )
                }
            }
        }
        catch {
            [void]$cleanupFailures.Add("Volume cleanup phase failed: $($_.Exception.Message)")
        }

        try {
            foreach ($networkId in @($resourceOwnership.networks)) {
                try {
                    $inspection = Get-CleanupDockerInspectState $cleanupFailures `
                        "Inspect invocation network ownership $networkId" `
                        "network" $networkId
                    if ($inspection.State -ceq "present") {
                        $label = Get-DockerInspectProjectLabel $inspection.Object "network"
                        if ($label -ceq $ProjectName) {
                            [void](Invoke-HotShopCleanupNativeStep -Failures $cleanupFailures `
                                -Step "Remove invocation network $networkId" `
                                -FilePath "docker" `
                                -Arguments @("network", "rm", $networkId))
                        }
                        else {
                            [void]$cleanupFailures.Add(
                                "Refused network with changed project ownership: $networkId"
                            )
                        }
                    }
                }
                catch {
                    [void]$cleanupFailures.Add(
                        "Network cleanup exception for $networkId`: $($_.Exception.Message)"
                    )
                }
            }
        }
        catch {
            [void]$cleanupFailures.Add("Network cleanup phase failed: $($_.Exception.Message)")
        }

        try {
            if ($resourceOwnership.keyDirectory -and
                    (Test-Path -LiteralPath $keyRoot -PathType Container)) {
                $resolvedKeyRoot = [System.IO.Path]::GetFullPath($keyRoot)
                $resolvedTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
                $expectedKeyDirectory = "hotshop-task16-keys-$suffix"
                if ($resolvedKeyRoot.StartsWith(
                        $resolvedTemp, [StringComparison]::OrdinalIgnoreCase
                    ) -and (Split-Path -Leaf $resolvedKeyRoot) -ceq $expectedKeyDirectory) {
                    Remove-Item -LiteralPath $resolvedKeyRoot -Recurse -Force
                }
                else {
                    [void]$cleanupFailures.Add(
                        "Refused to remove unvalidated private key directory: $resolvedKeyRoot"
                    )
                }
            }
        }
        catch {
            [void]$cleanupFailures.Add(
                "Temporary private key directory removal failed: $($_.Exception.Message)"
            )
        }

        try {
            foreach ($ownedImage in $ownedImages) {
                try {
                    $recordedImageId = [string]$resourceOwnership.images[$ownedImage]
                    if ([string]::IsNullOrWhiteSpace($recordedImageId)) {
                        continue
                    }
                    $inspection = Get-CleanupDockerInspectState $cleanupFailures `
                        "Inspect owned image $ownedImage" "image" $ownedImage
                    if ($inspection.State -ceq "present") {
                        $currentImageId = [string]$inspection.Object.Id
                        if ($currentImageId -ceq $recordedImageId) {
                            [void](Invoke-HotShopCleanupNativeStep -Failures $cleanupFailures `
                                -Step "Remove owned image $ownedImage" -FilePath "docker" `
                                -Arguments @("image", "rm", "--force", $ownedImage))
                        }
                        else {
                            [void]$cleanupFailures.Add(
                                "Refused image tag with changed ownership: $ownedImage"
                            )
                        }
                    }
                }
                catch {
                    [void]$cleanupFailures.Add(
                        "Image cleanup exception for $ownedImage`: $($_.Exception.Message)"
                    )
                }
            }
        }
        catch {
            [void]$cleanupFailures.Add("Image cleanup phase failed: $($_.Exception.Message)")
        }

        try {
            foreach ($ownedResource in @(
                @{Type = "container"; Ids = @($resourceOwnership.containers)},
                @{Type = "volume"; Ids = @($resourceOwnership.volumes)},
                @{Type = "network"; Ids = @($resourceOwnership.networks)}
            )) {
                foreach ($resourceId in $ownedResource.Ids) {
                    try {
                        $verification = Get-CleanupDockerInspectState $cleanupFailures `
                            "Verify invocation $($ownedResource.Type) cleanup $resourceId" `
                            $ownedResource.Type $resourceId
                        if ($verification.State -ceq "present") {
                            [void]$cleanupFailures.Add(
                                "Invocation-owned $($ownedResource.Type) remains after cleanup: $resourceId"
                            )
                        }
                    }
                    catch {
                        [void]$cleanupFailures.Add(
                            "Resource cleanup verification exception for $resourceId`: $($_.Exception.Message)"
                        )
                    }
                }
            }
        }
        catch {
            [void]$cleanupFailures.Add(
                "Resource cleanup verification phase failed: $($_.Exception.Message)"
            )
        }

        try {
            if ($resourceOwnership.keyDirectory -and (Test-Path -LiteralPath $keyRoot)) {
                [void]$cleanupFailures.Add("Temporary private key directory remains after cleanup")
            }
        }
        catch {
            [void]$cleanupFailures.Add(
                "Temporary private key cleanup verification failed: $($_.Exception.Message)"
            )
        }

        try {
            foreach ($ownedImage in $ownedImages) {
                try {
                    if ([string]::IsNullOrWhiteSpace(
                            [string]$resourceOwnership.images[$ownedImage]
                        )) {
                        continue
                    }
                    $inspection = Get-CleanupDockerInspectState $cleanupFailures `
                        "Verify owned image cleanup $ownedImage" "image" $ownedImage
                    if ($inspection.State -ceq "present") {
                        [void]$cleanupFailures.Add("Owned image remains after cleanup: $ownedImage")
                    }
                }
                catch {
                    [void]$cleanupFailures.Add(
                        "Owned image cleanup verification exception for $ownedImage`: $($_.Exception.Message)"
                    )
                }
            }
        }
        catch {
            [void]$cleanupFailures.Add(
                "Owned image cleanup verification phase failed: $($_.Exception.Message)"
            )
        }
    }
    finally {
        try {
            Restore-Environment
        }
        catch {
            [void]$cleanupFailures.Add("Environment restoration failed: $($_.Exception.Message)")
        }
    }
    if ($cleanupFailures.Count -gt 0) {
        $cleanupMessage = "Cleanup verification failed: $($cleanupFailures -join '; ')"
        if ($null -ne $verificationFailure) {
            Write-Error $cleanupMessage -ErrorAction Continue
        }
        else {
            throw $cleanupMessage
        }
    }
}
