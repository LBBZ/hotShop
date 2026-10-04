#requires -Version 7.2
[CmdletBinding()]
param(
    [string]$OutputDirectory,
    [switch]$Resume,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
if ($Resume -and $Force) { throw 'Resume and Force are mutually exclusive.' }
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot '../.local/keys/hotshop'
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$names = @('user', 'administrator', 'agent-delegation', 'agent-service')
$writes = [ordered]@{}
# Validate every existing pair before writing anything. Resume is only for an
# interrupted first initialization; it never replaces an existing private key.
foreach ($name in $names) {
    $private = Join-Path $OutputDirectory "$name-private.pem"
    $public = Join-Path $OutputDirectory "$name-public.pem"
    $hasPrivate = Test-Path -LiteralPath $private
    $hasPublic = Test-Path -LiteralPath $public
    if (($hasPrivate -or $hasPublic) -and -not ($Force -or $Resume)) {
        throw 'Refusing to overwrite existing authentication keys.'
    }
    $rsa = [Security.Cryptography.RSA]::Create(3072)
    try {
        if ($Resume -and $hasPublic -and -not $hasPrivate) { throw "Missing private key for $name; restore the original key." }
        if ($Resume -and $hasPrivate) {
            $rsa.ImportFromPem([IO.File]::ReadAllText($private))
            # Exporting private parameters rejects a public-only file.
            $null = $rsa.ExportParameters($true)
            if ($rsa.KeySize -lt 3072) { throw "Invalid key size for $name." }
        } else {
            $writes[$private] = $rsa.ExportPkcs8PrivateKeyPem() + "`n"
        }
        if ($Resume -and $hasPublic) {
            $verifier = [Security.Cryptography.RSA]::Create()
            try {
                $verifier.ImportFromPem([IO.File]::ReadAllText($public))
                if ([Convert]::ToBase64String($verifier.ExportSubjectPublicKeyInfo()) -cne [Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo())) {
                    throw "Mismatched authentication key pair: $name"
                }
            } finally { $verifier.Dispose() }
        } else {
            $writes[$public] = $rsa.ExportSubjectPublicKeyInfoPem() + "`n"
        }
    } finally { $rsa.Dispose() }
}
foreach ($entry in $writes.GetEnumerator()) {
    $temporary = $entry.Key + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
    try {
        $options = [IO.FileStreamOptions]::new()
        $options.Mode = [IO.FileMode]::CreateNew
        $options.Access = [IO.FileAccess]::Write
        if (-not $IsWindows) { $options.UnixCreateMode = [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite }
        if (-not $IsWindows -and $entry.Key.EndsWith('-public.pem')) {
            $options.UnixCreateMode = $options.UnixCreateMode -bor [IO.UnixFileMode]::GroupRead -bor [IO.UnixFileMode]::OtherRead
        }
        $stream = [IO.File]::Open($temporary, $options)
        try {
            $bytes = [Text.Encoding]::UTF8.GetBytes($entry.Value)
            $stream.Write($bytes)
            $stream.Flush($true)
        } finally { $stream.Dispose() }
        if (-not $IsWindows -and $entry.Key.EndsWith('-public.pem')) {
            [IO.File]::SetUnixFileMode($temporary, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::GroupRead -bor [IO.UnixFileMode]::OtherRead)
        }
        [IO.File]::Move($temporary, $entry.Key, [bool]$Force)
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}
Write-Output "Authentication keys ready under: $OutputDirectory"
