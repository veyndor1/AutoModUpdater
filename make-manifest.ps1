param(
    [string]$ModsDir = 'mods-to-host',
    [string]$ConfigsDir = 'config-to-host',
    [string]$BaseUrl = 'https://raw.githubusercontent.com/veyndor1/mcmods2/main',
    [string]$OutDir = 'publish-mc-mods2',
    [string]$Out = '',
    [string]$UpdaterJar = '',
    [switch]$IncludeUpdater
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
if ($IncludeUpdater -and -not $UpdaterJar) {
    $gradle = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'build.gradle'))
    $match = [regex]::Match($gradle,"(?m)^version = '([0-9.]+)'")
    if (-not $match.Success) { throw 'Cannot find updater version in build.gradle.' }
    $UpdaterJar = Join-Path $PSScriptRoot ('build/libs/AutoModUpdater-' + $match.Groups[1].Value + '.jar')
    if (-not (Test-Path -LiteralPath $UpdaterJar -PathType Leaf)) { throw 'Build the full updater JAR before publishing.' }
}
$BaseUrl = $BaseUrl.TrimEnd('/')
$publication = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir)

function Assert-Name([string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Name) -or $Name -in @('.','..') -or $Name.Length -gt 255 -or
        $Name.EndsWith('.') -or $Name.EndsWith(' ') -or $Name -match '[\\/:*?"<>|\x00-\x1f]' -or
        ($Name.Split('.')[0] -match '^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$')) { throw "Invalid portable name: $Name" }
}
function Assert-Regular($Item) {
    if (($Item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Links/junctions cannot be published: $($Item.FullName)" }
    Assert-Name $Item.Name
}
function Hash([string]$Path, [string]$Algorithm) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $hash = [Security.Cryptography.HashAlgorithm]::Create($Algorithm)
    try { return [BitConverter]::ToString($hash.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
    finally { $hash.Dispose(); $stream.Dispose() }
}
function Url([string]$Relative) {
    return $BaseUrl + '/' + (($Relative.Split('/') | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/')
}
function Copy-Artifact([string]$Source, [string]$Relative) {
    $target = Join-Path $publication $Relative
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
    if ([IO.File]::Exists($target)) { [IO.File]::SetAttributes($target,[IO.FileAttributes]::Normal) }
    [IO.File]::Copy($Source, $target, $true)
    [IO.File]::SetAttributes($target,[IO.FileAttributes]::Normal)
    return $target
}
function Read-Tree([string]$Root) {
    $items = [Collections.Generic.List[object]]::new()
    $queue = [Collections.Generic.Queue[string]]::new(); $queue.Enqueue($Root)
    while ($queue.Count -gt 0) {
        $dir = $queue.Dequeue()
        foreach ($item in Get-ChildItem -LiteralPath $dir -Force) {
            Assert-Regular $item
            $items.Add($item)
            if ($item.PSIsContainer) { $queue.Enqueue($item.FullName) }
        }
    }
    return ,$items.ToArray()
}
function Source-Root([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { [IO.Directory]::CreateDirectory($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)) | Out-Null }
    $root = (Resolve-Path -LiteralPath $Path).Path
    $item = Get-Item -LiteralPath $root -Force; Assert-Regular $item
    if (-not $item.PSIsContainer) { throw "Source is not a directory: $root" }
    if ($publication.Equals($root, [StringComparison]::OrdinalIgnoreCase) -or
        $publication.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Publication directory cannot be inside a source directory.' }
    return $root
}
$modsRoot = Source-Root $ModsDir
$configsRoot = Source-Root $ConfigsDir
if ($Out) {
    $requested = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out)
    foreach ($sourceRoot in @($modsRoot,$configsRoot)) {
        if ($requested.Equals($sourceRoot,[StringComparison]::OrdinalIgnoreCase) -or
            $requested.StartsWith($sourceRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
            throw 'Manifest output cannot overwrite publication inputs.'
        }
    }
}
[IO.Directory]::CreateDirectory($publication) | Out-Null
$mods = @(); $configs = @()
foreach ($item in Get-ChildItem -LiteralPath $modsRoot -Force -File -Filter '*.jar' | Sort-Object Name) {
    Assert-Regular $item
    $temporary = Join-Path $publication ([Guid]::NewGuid().ToString() + '.tmp')
    try {
        [IO.File]::Copy($item.FullName, $temporary)
        $sha1 = Hash $temporary 'SHA1'
        $relative = 'mods/' + $sha1 + '/' + $item.Name
        $artifact = Copy-Artifact $temporary $relative
        $mods += [ordered]@{name=$item.Name;fileName=$item.Name;url=(Url $relative);sha1=$sha1;size=([IO.FileInfo]$artifact).Length}
    } finally { if ([IO.File]::Exists($temporary)) { [IO.File]::SetAttributes($temporary,[IO.FileAttributes]::Normal); [IO.File]::Delete($temporary) } }
}
foreach ($item in Get-ChildItem -LiteralPath $configsRoot -Force | Sort-Object Name) {
    Assert-Regular $item
    if ($item.Name.Equals('automodupdater.json', [StringComparison]::OrdinalIgnoreCase)) {
        Write-Warning 'automodupdater.json is protected and excluded from the publication.'; continue
    }
    $temporary = Join-Path $publication ([Guid]::NewGuid().ToString() + '.tmp')
    try {
        if (-not $item.PSIsContainer) {
            [IO.File]::Copy($item.FullName, $temporary)
            $sha256 = Hash $temporary 'SHA256'; $relative = 'configs/' + $sha256 + '/' + $item.Name
            $artifact = Copy-Artifact $temporary $relative
            $configs += [ordered]@{path=$item.Name;type='file';url=(Url $relative);sha256=$sha256;size=([IO.FileInfo]$artifact).Length}
        } else {
            $tree = Read-Tree $item.FullName
            $lookup = @{}; $names = [Collections.Generic.List[string]]::new()
            foreach ($child in $tree) {
                $name = $child.FullName.Substring($item.FullName.Length + 1).Replace('\','/')
                if ($lookup.ContainsKey($name)) { throw "Case-insensitive config collision: $name" }
                $lookup[$name] = $child; $names.Add($name)
            }
            $sorted = $names.ToArray(); [Array]::Sort($sorted, [StringComparer]::Ordinal)
            $directories = @(); $files = @()
            $stream = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew)
            $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
            try {
                foreach ($name in $sorted) {
                    $child = $lookup[$name]
                    if ($child.PSIsContainer) {
                        $directories += $name; $entry = $zip.CreateEntry($name + '/')
                        $entry.LastWriteTime = [DateTimeOffset]::new(1980,1,1,0,0,0,[TimeSpan]::Zero)
                    } else {
                        $source = [IO.File]::Open($child.FullName,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
                        $hash = [Security.Cryptography.SHA256]::Create()
                        try {
                            $fileHash = [BitConverter]::ToString($hash.ComputeHash($source)).Replace('-','').ToLowerInvariant()
                            $files += [ordered]@{path=$name;sha256=$fileHash;size=$source.Length}
                            $source.Position = 0
                            $entry = $zip.CreateEntry($name, [IO.Compression.CompressionLevel]::Optimal)
                            $entry.LastWriteTime = [DateTimeOffset]::new(1980,1,1,0,0,0,[TimeSpan]::Zero)
                            $output = $entry.Open()
                            try { $source.CopyTo($output) } finally { $output.Dispose() }
                        } finally { $hash.Dispose(); $source.Dispose() }
                    }
                }
            } finally { $zip.Dispose(); $stream.Dispose() }
            $sha256 = Hash $temporary 'SHA256'; $relative = 'configs/' + $sha256 + '/snapshot.zip'
            $artifact = Copy-Artifact $temporary $relative
            $configs += [ordered]@{path=$item.Name;type='directory';url=(Url $relative);sha256=$sha256;size=([IO.FileInfo]$artifact).Length;directories=@($directories);files=@($files)}
        }
    } finally { if ([IO.File]::Exists($temporary)) { [IO.File]::SetAttributes($temporary,[IO.FileAttributes]::Normal); [IO.File]::Delete($temporary) } }
}
$manifest = [ordered]@{schemaVersion=2;minecraftVersion='1.20.1';packId='mc-mods2-1.20.1';mods=@($mods);configs=@($configs)}
if ($UpdaterJar) {
    $updater = Get-Item -LiteralPath $UpdaterJar; Assert-Regular $updater
    if ($updater.PSIsContainer -or $updater.Length -le 0 -or $updater.Length -gt 128MB) { throw 'Invalid updater JAR size.' }
    if (-not $BaseUrl.StartsWith('https://',[StringComparison]::OrdinalIgnoreCase)) { throw 'Updater publication requires an HTTPS base URL.' }
    $archive = [IO.Compression.ZipFile]::OpenRead($updater.FullName)
    try {
        foreach ($required in @('updater/build.properties','embedded/automodupdater-client.jar','com/skymc/autoupdater/bootstrap/UpdaterLocator.class','com/skymc/autoupdater/bootstrap/InstallerHelper.class','META-INF/services/net.minecraftforge.forgespi.locating.IModLocator')) {
            if (-not $archive.GetEntry($required)) { throw "Not a complete updater JAR: $required" }
        }
        $reader = [IO.StreamReader]::new($archive.GetEntry('updater/build.properties').Open())
        try { $build = ConvertFrom-StringData -StringData $reader.ReadToEnd() } finally { $reader.Dispose() }
        $version = $build.version
        if ($version -notmatch '^[0-9]{1,9}(\.[0-9]{1,9}){0,3}$' -or $build.minecraftVersion -ne '1.20.1' -or $build.forgeMajor -ne '47' -or $build.javaMajor -ne '17') { throw 'Invalid updater version/compatibility.' }
    } finally { $archive.Dispose() }
    $name = 'AutoModUpdater-' + $version + '.jar'
    if (@($mods | Where-Object { $_.fileName -eq $name -or $_.fileName -eq $updater.Name }).Count -gt 0) { throw 'Updater must not also appear in mods-to-host.' }
    $sha256 = Hash $updater.FullName 'SHA256'; $relative = 'updater/' + $sha256 + '/' + $name
    $artifact = Copy-Artifact $updater.FullName $relative
    if ((Hash $artifact 'SHA256') -ne $sha256) { throw 'Updater changed while publishing.' }
    $manifest.updater = [ordered]@{version=$version;fileName=$name;minecraftVersion='1.20.1';forgeMajor=47;javaMajor=17;url=(Url $relative);sha256=$sha256;size=([IO.FileInfo]$artifact).Length}
}
foreach ($name in @('README.md','SECURITY.md','LICENSE','banner.svg')) {
    $template = Join-Path $PSScriptRoot ('publish-templates/' + $name)
    if (Test-Path -LiteralPath $template) { [IO.File]::Copy($template,(Join-Path $publication $name),$true) }
}
if (-not $Out) { $Out = Join-Path $publication 'server-mods.json' }
$manifestPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($manifestPath)) | Out-Null
$tempManifest = $manifestPath + '.tmp'
[IO.File]::WriteAllText($tempManifest, ($manifest | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath $tempManifest -Destination $manifestPath -Force
Write-Output "Publication ready: $publication (mods=$($mods.Count), config roots=$($configs.Count))"
Write-Output 'Upload artifacts first and server-mods.json last to veyndor1/mcmods2 main. Only the repository owner should have write access.'
