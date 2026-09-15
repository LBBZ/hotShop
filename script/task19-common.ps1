Set-StrictMode -Version Latest

if (-not (Get-Variable -Name Task19InjectDockerQueryFailure -Scope Script -ErrorAction SilentlyContinue)) {
    $script:Task19InjectDockerQueryFailure = $false
}

function Assert-Task19ProjectName([string]$ProjectName) {
    if ($ProjectName -notmatch "^hotshop-task19-[a-z0-9]{8,24}$") {
        throw "TASK-19 project name must be unique and match hotshop-task19-<random>"
    }
}

function Invoke-Task19Docker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments, [switch]$AllowFailure)
    # Function-local: expected nonzero probes must reach our explicit exit-code
    # policy even when the caller enables native error promotion (GitHub CI).
    $PSNativeCommandUseErrorActionPreference = $false
    $output = @(& docker @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) { throw "Docker command failed with exit $exitCode" }
    return [pscustomobject]@{ ExitCode = $exitCode; Output = ($output -join "`n") }
}

function Get-Task19DockerHostPath([string]$LocalPath) {
    $resolved = [System.IO.Path]::GetFullPath($LocalPath)
    $inspection = Invoke-Task19Docker -Arguments @("container", "inspect", [Environment]::MachineName) -AllowFailure
    if ($inspection.ExitCode -ne 0) { return $resolved }
    try {
        $objects = @($inspection.Output | ConvertFrom-Json)
        if ($objects.Count -ne 1) { return $resolved }
        $mount = @($objects[0].Mounts | Where-Object {
            [string]::Equals([System.IO.Path]::GetFullPath([string]$_.Destination),
                [System.IO.Path]::GetFullPath($script:Task19Root), [StringComparison]::Ordinal)
        }) | Select-Object -First 1
        if ($null -eq $mount) { return $resolved }
        $relative = [System.IO.Path]::GetRelativePath([System.IO.Path]::GetFullPath($script:Task19Root), $resolved)
        if ($relative -eq ".") { return [string]$mount.Source }
        return (([string]$mount.Source).TrimEnd("/") + "/" + $relative.Replace("\", "/"))
    }
    catch { return $resolved }
}

function Get-Task19Lines([string]$Text) {
    return @($Text -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function New-Task19ResourceSet { return [ordered]@{ containers = @(); volumes = @(); networks = @() } }

function Get-Task19ProjectResources([string]$ProjectName) {
    $failures = [System.Collections.Generic.List[string]]::new()
    $resources = New-Task19ResourceSet
    if ($script:Task19InjectDockerQueryFailure) {
        [void]$failures.Add("Injected Docker project resource query failure")
        return [pscustomobject]@{ Success = $false; Resources = $resources; Failures = @($failures) }
    }
    foreach ($query in @(
        @{ Kind = "containers"; Arguments = @("ps", "-aq", "--filter", "label=com.docker.compose.project=$ProjectName") },
        @{ Kind = "volumes"; Arguments = @("volume", "ls", "-q", "--filter", "label=com.docker.compose.project=$ProjectName") },
        @{ Kind = "networks"; Arguments = @("network", "ls", "-q", "--filter", "label=com.docker.compose.project=$ProjectName") }
    )) {
        try {
            $result = Invoke-Task19Docker -Arguments $query.Arguments -AllowFailure
            if ($result.ExitCode -ne 0) { [void]$failures.Add("Docker $($query.Kind) query exited $($result.ExitCode)") }
            else { $resources[$query.Kind] = @(Get-Task19Lines $result.Output) }
        }
        catch { [void]$failures.Add("Docker $($query.Kind) query exception: $($_.Exception.Message)") }
    }
    return [pscustomobject]@{ Success = $failures.Count -eq 0; Resources = $resources; Failures = @($failures) }
}

function Get-Task19ImageIdentity([string]$Tag) {
    $failures = [System.Collections.Generic.List[string]]::new()
    try {
        $result = Invoke-Task19Docker -Arguments @("image", "inspect", $Tag) -AllowFailure
        if ($result.ExitCode -eq 1) { return [pscustomobject]@{ Success = $true; Exists = $false; Id = ""; Failures = @() } }
        if ($result.ExitCode -ne 0) { throw "inspection exited $($result.ExitCode)" }
        $objects = @($result.Output | ConvertFrom-Json)
        if ($objects.Count -ne 1) { throw "inspection returned $($objects.Count) objects" }
        return [pscustomobject]@{ Success = $true; Exists = $true; Id = [string]$objects[0].Id; Failures = @() }
    }
    catch { [void]$failures.Add("Image identity exception for $Tag`: $($_.Exception.Message)") }
    return [pscustomobject]@{ Success = $false; Exists = $false; Id = ""; Failures = @($failures) }
}

function Get-Task19ImageId([string]$Tag) {
    $identity = Get-Task19ImageIdentity $Tag
    if (-not $identity.Success) { throw ($identity.Failures -join "; ") }
    return [string]$identity.Id
}

function Get-Task19ResourceIdentity([string]$Type, [string]$Identifier) {
    $failures = [System.Collections.Generic.List[string]]::new()
    try {
        $result = Invoke-Task19Docker -Arguments @($Type, "inspect", $Identifier) -AllowFailure
        if ($result.ExitCode -eq 1) {
            return [pscustomobject]@{ Success = $true; Exists = $false; Identity = ""; Project = ""; Failures = @() }
        }
        if ($result.ExitCode -ne 0) { throw "inspection exited $($result.ExitCode)" }
        $objects = @($result.Output | ConvertFrom-Json)
        if ($objects.Count -ne 1) { throw "inspection returned $($objects.Count) objects" }
        $object = $objects[0]
        $labels = if ($Type -eq "container") { $object.Config.Labels } else { $object.Labels }
        $project = ""
        if ($null -ne $labels) {
            $property = $labels.PSObject.Properties["com.docker.compose.project"]
            if ($null -ne $property) { $project = [string]$property.Value }
        }
        if ($Type -eq "volume") {
            $stable = [ordered]@{
                Name = [string]$object.Name; Driver = [string]$object.Driver
                Mountpoint = [string]$object.Mountpoint; CreatedAt = [string]$object.CreatedAt
                Scope = [string]$object.Scope; Labels = $object.Labels; Options = $object.Options
            } | ConvertTo-Json -Compress -Depth 10
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try {
                $identity = ([BitConverter]::ToString($sha.ComputeHash(
                    [System.Text.Encoding]::UTF8.GetBytes($stable)))).Replace("-", "")
            }
            finally { $sha.Dispose() }
        }
        else { $identity = [string]$object.Id }
        return [pscustomobject]@{
            Success = $true; Exists = $true; Identity = $identity; Project = $project; Failures = @()
        }
    }
    catch { [void]$failures.Add("$Type identity exception for $Identifier`: $($_.Exception.Message)") }
    return [pscustomobject]@{ Success = $false; Exists = $false; Identity = ""; Project = ""; Failures = @($failures) }
}

function Get-Task19ResourceProjectLabel([string]$Type, [string]$Identifier) {
    $identity = Get-Task19ResourceIdentity $Type $Identifier
    if (-not $identity.Success) { throw ($identity.Failures -join "; ") }
    if (-not $identity.Exists) { return $null }
    return [string]$identity.Project
}

function Assert-Task19Preflight(
    [string]$ProjectName, [string[]]$ImageTags, [string]$TemporaryDirectory,
    [string]$EvidenceDirectory = ""
) {
    Assert-Task19ProjectName $ProjectName
    $query = Get-Task19ProjectResources $ProjectName
    if (-not $query.Success) { throw "TASK-19 project preflight query failed: $($query.Failures -join '; ')" }
    $resources = $query.Resources
    if ($resources.containers.Count -or $resources.volumes.Count -or $resources.networks.Count) {
        throw "TASK-19 refuses a Compose project with existing resources"
    }
    foreach ($tag in $ImageTags) {
        $inspection = Get-Task19ImageIdentity $tag
        if (-not $inspection.Success) { throw "TASK-19 image preflight failed for $tag" }
        if ($inspection.Exists) { throw "TASK-19 refuses to overwrite existing image tag: $tag" }
    }
    if (Test-Path -LiteralPath $TemporaryDirectory) { throw "TASK-19 refuses to reuse its temporary directory" }
    if ($EvidenceDirectory -and (Test-Path -LiteralPath $EvidenceDirectory)) {
        throw "TASK-19 refuses to overwrite existing evidence"
    }
    return $resources
}

function Register-Task19CreatedResources {
    param([string]$ProjectName, [System.Collections.IDictionary]$Before,
        [System.Collections.IDictionary]$Owned)
    $failures = [System.Collections.Generic.List[string]]::new()
    $query = Get-Task19ProjectResources $ProjectName
    if (-not $query.Success) { return [pscustomobject]@{ Success = $false; Failures = @($query.Failures) } }
    foreach ($kind in @("containers", "volumes", "networks")) {
        $type = $kind.TrimEnd("s")
        foreach ($identifier in @($query.Resources[$kind])) {
            if (@($Before[$kind]) -contains $identifier -or $Owned[$kind].Contains($identifier)) { continue }
            $identity = Get-Task19ResourceIdentity $type $identifier
            if (-not $identity.Success) {
                foreach ($item in $identity.Failures) { [void]$failures.Add($item) }
            }
            elseif (-not $identity.Exists -or $identity.Project -cne $ProjectName) {
                [void]$failures.Add("Refused to register ambiguous $type identity: $identifier")
            }
            else { $Owned[$kind][$identifier] = [string]$identity.Identity }
        }
    }
    return [pscustomobject]@{ Success = $failures.Count -eq 0; Failures = @($failures) }
}

function Register-Task19CreatedImage {
    param([string]$Tag, [System.Collections.IDictionary]$BeforeImages,
        [System.Collections.IDictionary]$OwnedImages)
    if ($BeforeImages.Contains($Tag) -and [string]$BeforeImages[$Tag]) {
        return [pscustomobject]@{ Success = $false; Failures = @("Refused pre-existing image tag: $Tag") }
    }
    $identity = Get-Task19ImageIdentity $Tag
    if (-not $identity.Success) { return [pscustomobject]@{ Success = $false; Failures = @($identity.Failures) } }
    if ($identity.Exists) { $OwnedImages[$Tag] = [string]$identity.Id }
    return [pscustomobject]@{ Success = $true; Failures = @() }
}

function Write-Task19JsonResult([string]$Path, [object]$Value) {
    try {
        New-Item -ItemType Directory -Force (Split-Path -Parent $Path) | Out-Null
        $Value | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Path -Encoding UTF8
        return [pscustomobject]@{ Success = $true; Path = $Path; Failures = @() }
    }
    catch {
        return [pscustomobject]@{
            Success = $false; Path = $Path
            Failures = @("Evidence write failed for $Path`: $($_.Exception.Message)")
        }
    }
}

function Remove-Task19Resources {
    param(
        [string]$ProjectName, [System.Collections.IDictionary]$Owned,
        [System.Collections.IDictionary]$OwnedImages, [string]$TemporaryDirectory,
        [bool]$TemporaryDirectoryOwned, [string]$EvidenceDirectory,
        [bool]$EvidenceDirectoryOwned, [bool]$VerificationPassed
    )
    $failures = [System.Collections.Generic.List[string]]::new()
    foreach ($entry in @(
        @{Type = "container"; Kind = "containers"; Remove = @("container", "rm", "--force")},
        @{Type = "volume"; Kind = "volumes"; Remove = @("volume", "rm", "--force")},
        @{Type = "network"; Kind = "networks"; Remove = @("network", "rm")}
    )) {
        foreach ($identifier in @($Owned[$entry.Kind].Keys)) {
            try {
                $identity = Get-Task19ResourceIdentity $entry.Type $identifier
                if (-not $identity.Success) {
                    foreach ($item in $identity.Failures) { [void]$failures.Add($item) }
                    continue
                }
                if (-not $identity.Exists) { continue }
                if ($identity.Project -cne $ProjectName -or
                        $identity.Identity -cne [string]$Owned[$entry.Kind][$identifier]) {
                    [void]$failures.Add("Refused $($entry.Type) with changed identity: $identifier")
                    continue
                }
                $remove = Invoke-Task19Docker -Arguments @($entry.Remove + $identifier) -AllowFailure
                if ($remove.ExitCode -ne 0) { [void]$failures.Add("Failed to remove $($entry.Type): $identifier") }
            }
            catch { [void]$failures.Add("$($entry.Type) cleanup exception: $($_.Exception.Message)") }
        }
    }
    foreach ($tag in @($OwnedImages.Keys)) {
        try {
            $current = Get-Task19ImageIdentity $tag
            if (-not $current.Success) {
                foreach ($item in $current.Failures) { [void]$failures.Add($item) }
                continue
            }
            if (-not $current.Exists) { continue }
            if ($current.Id -cne [string]$OwnedImages[$tag]) {
                [void]$failures.Add("Refused image with changed identity: $tag")
                continue
            }
            $remove = Invoke-Task19Docker -Arguments @("image", "rm", "--force", $tag) -AllowFailure
            if ($remove.ExitCode -ne 0) { [void]$failures.Add("Failed to remove image: $tag") }
        }
        catch { [void]$failures.Add("Image cleanup exception for $tag`: $($_.Exception.Message)") }
    }
    if ($TemporaryDirectoryOwned) {
        try {
            if (Test-Path -LiteralPath $TemporaryDirectory) {
                $resolved = [System.IO.Path]::GetFullPath($TemporaryDirectory)
                $expectedParent = [System.IO.Path]::GetFullPath((Join-Path $script:Task19Root "target\task19-temp"))
                if (-not $resolved.StartsWith($expectedParent, [StringComparison]::OrdinalIgnoreCase) -or
                        (Split-Path -Leaf $resolved) -cne $ProjectName) {
                    [void]$failures.Add("Refused unvalidated TASK-19 temporary path")
                }
                else { Remove-Item -LiteralPath $resolved -Recurse -Force }
            }
        }
        catch { [void]$failures.Add("Temporary directory cleanup exception: $($_.Exception.Message)") }
    }

    $remainingOwned = [ordered]@{ containers = 0; volumes = 0; networks = 0; images = 0 }
    foreach ($entry in @(
        @{Type = "container"; Kind = "containers"}, @{Type = "volume"; Kind = "volumes"},
        @{Type = "network"; Kind = "networks"}
    )) {
        foreach ($identifier in @($Owned[$entry.Kind].Keys)) {
            $identity = Get-Task19ResourceIdentity $entry.Type $identifier
            if (-not $identity.Success) {
                foreach ($item in $identity.Failures) { [void]$failures.Add("Residual query: $item") }
            }
            elseif ($identity.Exists) { $remainingOwned[$entry.Kind]++ }
        }
    }
    foreach ($tag in @($OwnedImages.Keys)) {
        $identity = Get-Task19ImageIdentity $tag
        if (-not $identity.Success) {
            foreach ($item in $identity.Failures) { [void]$failures.Add("Residual query: $item") }
        }
        elseif ($identity.Exists) { $remainingOwned.images++ }
    }
    $projectQuery = Get-Task19ProjectResources $ProjectName
    $untracked = New-Task19ResourceSet
    if (-not $projectQuery.Success) {
        foreach ($item in $projectQuery.Failures) { [void]$failures.Add("Residual project query: $item") }
    }
    else {
        foreach ($kind in @("containers", "volumes", "networks")) {
            $untracked[$kind] = @($projectQuery.Resources[$kind] | Where-Object { -not $Owned[$kind].Contains($_) })
            if ($untracked[$kind].Count) { [void]$failures.Add("Untracked project $kind remain") }
        }
    }
    $temporaryRemaining = $TemporaryDirectoryOwned -and (Test-Path -LiteralPath $TemporaryDirectory)
    if ($temporaryRemaining) { [void]$failures.Add("Temporary key directory remains") }
    $sum = ($remainingOwned.Values | Measure-Object -Sum).Sum
    $clean = $failures.Count -eq 0 -and $sum -eq 0 -and -not $temporaryRemaining
    $report = [ordered]@{
        schemaVersion = "2.0"; project = $ProjectName; verificationPassed = $VerificationPassed
        containers = $remainingOwned.containers; volumes = $remainingOwned.volumes
        networks = $remainingOwned.networks; images = $remainingOwned.images
        untrackedProjectResources = $untracked
        temporaryKeyDirectories = if ($temporaryRemaining) { 1 } else { 0 }
        failures = @($failures); clean = $clean
    }
    $evidenceResult = [pscustomobject]@{ Success = $true; Path = ""; Failures = @() }
    if ($EvidenceDirectoryOwned) {
        $evidenceResult = Write-Task19JsonResult (Join-Path $EvidenceDirectory "cleanup.json") $report
        if (-not $evidenceResult.Success) {
            foreach ($item in $evidenceResult.Failures) { [void]$failures.Add($item) }
            $report.clean = $false
        }
    }
    return [pscustomobject]@{
        Success = $failures.Count -eq 0; Failures = @($failures); Report = $report
        EvidenceWritten = $evidenceResult.Success -and $EvidenceDirectoryOwned
    }
}

function Wait-Task19Until {
    param([scriptblock]$Probe, [string]$Description, [int]$TimeoutSeconds = 300)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $delay = 100
    $lastError = "no observation"
    do {
        try {
            $result = & $Probe
            if ($null -ne $result -and $result -ne $false) { return $result }
            $lastError = "probe returned false"
        }
        catch { $lastError = $_.Exception.Message }
        if ([DateTimeOffset]::UtcNow -ge $deadline) {
            throw "Timed out waiting for $Description; last observation: $lastError"
        }
        Start-Sleep -Milliseconds $delay
        $delay = [Math]::Min(2000, [int]($delay * 1.6))
    } while ($true)
}

function Write-Task19Json([string]$Path, [object]$Value) {
    $result = Write-Task19JsonResult $Path $Value
    if (-not $result.Success) { throw ($result.Failures -join "; ") }
}
