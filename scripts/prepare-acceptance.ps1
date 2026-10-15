param([ValidateSet('config-only','dismiss','manual','restart','recovery','offline')][string]$Case,
      [ValidatePattern('^[a-z0-9-]+$')][string]$GameName)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$workspace = Split-Path $PSScriptRoot -Parent
if (-not $GameName) { $GameName = 'config-' + $Case }
$game = Join-Path $workspace ('.tools/minecraft/' + $GameName)
$web = Join-Path $workspace '.tools/config-acceptance-web'
if (Test-Path -LiteralPath $game) { throw 'Use a fresh acceptance profile or a new GameName.' }
$source = Join-Path $workspace ('.tools/acceptance-source/' + $GameName)
$mods = Join-Path $source 'mods'; $configs = Join-Path $source 'configs'
$output = Join-Path $web $GameName
[IO.Directory]::CreateDirectory((Join-Path $game 'mods')) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $game 'config/acceptance')) | Out-Null
[IO.Directory]::CreateDirectory($mods) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $configs 'acceptance/nested/empty')) | Out-Null
[IO.File]::WriteAllText((Join-Path $game 'config/acceptance/settings.toml'),'v1')
[IO.File]::WriteAllText((Join-Path $game 'config/acceptance/user-only.txt'),'must be backed up')
[IO.File]::WriteAllText((Join-Path $game 'config/unlisted.toml'),'untouched')
[IO.File]::WriteAllText((Join-Path $configs 'acceptance/settings.toml'),'v2')
[IO.File]::WriteAllText((Join-Path $configs 'acceptance/nested/.hidden'),'nested-content')
[IO.File]::WriteAllText((Join-Path $configs 'root.toml'),'root-content')
Copy-Item -LiteralPath (Join-Path $workspace 'client/build/acceptance/acceptance-probe.jar') -Destination (Join-Path $game 'mods/acceptance-probe.jar')
Copy-Item -LiteralPath (Join-Path $workspace 'client/build/acceptance/acceptance-extra.jar') -Destination (Join-Path $game 'mods/acceptance-extra.jar')
if ($Case -notin @('config-only','offline')) {
    $v2 = Join-Path $mods 'acceptance-extra.jar'
    Copy-Item -LiteralPath (Join-Path $workspace 'client/build/acceptance/acceptance-extra.jar') -Destination $v2
    $zip = [IO.Compression.ZipFile]::Open($v2,[IO.Compression.ZipArchiveMode]::Update)
    try {
        $zip.GetEntry('acceptance-extra-version.txt').Delete(); $entry = $zip.CreateEntry('acceptance-extra-version.txt')
        $writer = [IO.StreamWriter]::new($entry.Open(),[Text.UTF8Encoding]::new($false))
        try { $writer.Write('v2') } finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
}
$base = 'http://127.0.0.1:' + [IO.File]::ReadAllText((Join-Path $web 'port.txt')).Trim() + '/' + $GameName
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $workspace 'make-manifest.ps1') -ModsDir $mods -ConfigsDir $configs -BaseUrl $base -OutDir $output
if ($LASTEXITCODE -ne 0) { throw 'Publisher failed' }
$url = $base + '/server-mods.json'
if ($Case -eq 'offline') { $url = 'http://127.0.0.1:9/unreachable' }
$settings = @{manifestUrl=$url;autoRestart=($Case -ne 'manual')} | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $game 'config/automodupdater.json'),$settings,[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $game 'expected-updater-settings.txt'),$settings,[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $game 'automated-case.txt'),$(if($Case -eq 'recovery'){'restart'}else{$Case}))
if ($Case -eq 'recovery') {
    $jdk = $env:UPDATER_JDK
    if (-not $jdk) { $jdk = Get-ChildItem -LiteralPath (Join-Path $env:ProgramFiles 'Eclipse Adoptium') -Directory -Filter 'jdk-17*' | Select-Object -First 1 -ExpandProperty FullName }
    $classpath = [IO.File]::ReadAllText((Join-Path $workspace '.tools/acceptance-classpath.txt'))
    & (Join-Path $jdk 'bin/java.exe') '-Djava.awt.headless=true' '-cp' $classpath 'com.skymc.autoupdater.bootstrap.AcceptanceSetupFixture' $game
    if ($LASTEXITCODE -ne 0) { throw 'Recovery fixture preparation failed' }
}
Write-Output ('Prepared: ' + $game)
