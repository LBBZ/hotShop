#requires -Version 7.2
$ErrorActionPreference = 'Stop'
$generator = Join-Path $PSScriptRoot '../generate-auth-keys.ps1'
$directory = Join-Path ([IO.Path]::GetTempPath()) ('hotshop-key-test-' + [Guid]::NewGuid().ToString('N'))
function docker { throw 'Key generation must not use Docker.' }
function Hashes { return (Get-ChildItem $directory -Filter '*.pem' | Sort-Object Name | Get-FileHash).Hash -join ',' }
try {
    & $generator -OutputDirectory $directory | Out-Null
    if (@(Get-ChildItem $directory -Filter '*.pem').Count -ne 8) { throw 'Expected four complete key pairs.' }
    if (-not $IsWindows) {
        foreach ($file in Get-ChildItem $directory -Filter '*.pem') {
            $expectedMode = if ($file.Name.EndsWith('-private.pem')) { 384 } else { 420 }
            if ([int][IO.File]::GetUnixFileMode($file.FullName) -ne $expectedMode) { throw "Unexpected permissions: $($file.Name)" }
        }
    }
    $original = Hashes
    & $generator -OutputDirectory $directory -Resume | Out-Null
    if ((Hashes) -cne $original) { throw 'Resume changed existing keys.' }
    try { & $generator -OutputDirectory $directory | Out-Null; throw 'Overwrite accepted.' }
    catch { if ($_.Exception.Message -notlike '*Refusing to overwrite*') { throw } }
    Remove-Item (Join-Path $directory 'user-public.pem')
    & $generator -OutputDirectory $directory -Resume | Out-Null
    if ((Hashes) -cne $original) { throw 'Public key recovery changed the key pair.' }
    $private = [Security.Cryptography.RSA]::Create()
    $public = [Security.Cryptography.RSA]::Create()
    try {
        $private.ImportFromPem([IO.File]::ReadAllText((Join-Path $directory 'user-private.pem')))
        $public.ImportFromPem([IO.File]::ReadAllText((Join-Path $directory 'user-public.pem')))
        $data = [Text.Encoding]::UTF8.GetBytes('bootstrap recovery')
        $signature = $private.SignData($data, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)
        if (-not $public.VerifyData($data, $signature, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)) { throw 'Generated pair failed RS256 verification.' }
    } finally { $private.Dispose(); $public.Dispose() }
    Copy-Item (Join-Path $directory 'user-public.pem') (Join-Path $directory 'administrator-public.pem') -Force
    $before = Hashes
    try { & $generator -OutputDirectory $directory -Resume | Out-Null; throw 'Mismatch accepted.' }
    catch { if ($_.Exception.Message -notlike '*Mismatched*') { throw } }
    if ((Hashes) -cne $before) { throw 'Validation failure mutated keys.' }
    Remove-Item (Join-Path $directory 'user-private.pem')
    $before = Hashes
    try { & $generator -OutputDirectory $directory -Resume | Out-Null; throw 'Orphan public key accepted.' }
    catch { if ($_.Exception.Message -notlike '*Missing private key*') { throw } }
    if ((Hashes) -cne $before) { throw 'Missing private key was silently replaced.' }
    & $generator -OutputDirectory $directory -Force | Out-Null
    if ((Hashes) -ceq $original -or @(Get-ChildItem $directory -Filter '*.pem').Count -ne 8) { throw 'Explicit rotation did not replace all pairs.' }
    'PASS: native RSA generation, preservation, public recovery, mismatch and orphan rejection.'
} finally {
    $resolved = [IO.Path]::GetFullPath($directory)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) { throw 'Cleanup escaped temporary directory.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
