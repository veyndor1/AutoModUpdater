$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$fixture = Join-Path $workspace ('.tools/manifest-config-test-' + [Guid]::NewGuid().ToString('N'))
$mods = Join-Path $fixture 'mods'; $configs = Join-Path $fixture 'configs'; $out = Join-Path $fixture 'publication'
$folder = ([string][char]0x00D6) + 'mer space'
[IO.Directory]::CreateDirectory($mods) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $configs ($folder + '/nested/empty'))) | Out-Null
[IO.File]::WriteAllText((Join-Path $mods 'sample.jar'),'mod fixture')
[IO.File]::WriteAllText((Join-Path $configs ($folder + '/nested/.hidden')),'hidden fixture')
[IO.File]::SetAttributes((Join-Path $configs ($folder + '/nested/.hidden')),[IO.FileAttributes]::Hidden)
[IO.File]::WriteAllText((Join-Path $configs 'root.toml'),'value=1')
[IO.File]::WriteAllText((Join-Path $configs 'automodupdater.json'),'protected')
function Run-Generator {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $workspace 'make-manifest.ps1') -ModsDir $mods -ConfigsDir $configs -BaseUrl 'https://example.com/pack' -OutDir $out
    if ($LASTEXITCODE -ne 0) { throw 'Manifest generator failed' }
}
function Hash([string]$Path,[string]$Algorithm) {
    $h = [Security.Cryptography.HashAlgorithm]::Create($Algorithm); $s = [IO.File]::OpenRead($Path)
    try { [BitConverter]::ToString($h.ComputeHash($s)).Replace('-','').ToLowerInvariant() }
    finally { $h.Dispose(); $s.Dispose() }
}
Run-Generator
$manifestPath = Join-Path $out 'server-mods.json'
$manifest = Get-Content -Raw -LiteralPath $manifestPath -Encoding UTF8 | ConvertFrom-Json
if ($manifest.schemaVersion -ne 2 -or $manifest.minecraftVersion -ne '1.20.1' -or $manifest.packId -ne 'mc-mods2-1.20.1') { throw 'Wrong publication identity' }
if (@($manifest.mods).Count -ne 1 -or $manifest.mods[0].fileName -ne 'sample.jar') { throw 'Wrong flat mod inventory' }
if (@($manifest.configs).Count -ne 2 -or @($manifest.configs | Where-Object { $_.path -eq 'automodupdater.json' }).Count -ne 0) { throw 'Updater config was not excluded' }
$directory = $manifest.configs | Where-Object { $_.type -eq 'directory' }
if ($directory.path -ne $folder -or $directory.files[0].path -ne 'nested/.hidden' -or $directory.directories -notcontains 'nested/empty') { throw 'Unicode/hidden/empty tree not preserved' }
if ($directory.files[0].sha256 -ne (Hash (Join-Path $configs ($folder + '/nested/.hidden')) 'SHA256')) { throw 'File inventory hash mismatch' }
$zip = Join-Path $out ('configs/' + $directory.sha256 + '/snapshot.zip')
if ((Hash $zip 'SHA256') -ne $directory.sha256) { throw 'Package hash mismatch' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($zip)
try { if (-not $archive.GetEntry('nested/.hidden') -or -not $archive.GetEntry('nested/empty/')) { throw 'ZIP tree mismatch' } }
finally { $archive.Dispose() }
$before = [IO.File]::ReadAllText($manifestPath)
Run-Generator
if ([IO.File]::ReadAllText($manifestPath) -ne $before) { throw 'Publication is not deterministic' }
$bytes = [IO.File]::ReadAllBytes($manifestPath)
if ($bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) { throw 'Manifest must be BOM-free UTF8' }
$manifest = Get-Content -Raw -LiteralPath $manifestPath -Encoding UTF8 | ConvertFrom-Json
$manifest.removeMods = @(@{fileName='old.jar';name='Old mod';reason='Incompatible'})
[IO.File]::WriteAllText($manifestPath,($manifest | ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
Run-Generator
$manifest = Get-Content -Raw -LiteralPath $manifestPath -Encoding UTF8 | ConvertFrom-Json
if (@($manifest.removeMods).Count -ne 1 -or $manifest.removeMods[0].fileName -ne 'old.jar' -or $manifest.removeMods[0].reason -ne 'Incompatible') { throw 'Manual removeMods was lost during regeneration' }
Write-Output 'Manifest producer: v2 identity, mods, root config, Unicode/hidden/empty directories, protected updater settings, deterministic ZIP/SHA256 and BOM-free UTF8 passed.'
