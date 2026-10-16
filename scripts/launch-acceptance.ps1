param([ValidatePattern('^[a-z0-9-]+$')][string]$GameName = 'acceptance-game')
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $workspace '.tools/minecraft'
$game = Join-Path $runtime $GameName
$cwd = Join-Path $runtime ($GameName + '-cwd')
[IO.Directory]::CreateDirectory($cwd) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $game 'mods')) | Out-Null
Copy-Item -LiteralPath (Join-Path $workspace 'build/libs/AutoModUpdater-2.0-release.jar') -Destination (Join-Path $game 'mods/AutoModUpdater.jar') -Force
$artifactHash = (Get-FileHash -LiteralPath (Join-Path $game 'mods/AutoModUpdater.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $game 'release-sha256.txt'),$artifactHash)
$argv = Get-Content -Raw -LiteralPath (Join-Path $workspace '.tools/runtime-command.json') | ConvertFrom-Json
$oldGame = Join-Path $runtime 'game'
$argv = @($argv | ForEach-Object {
    if ($_ -eq $oldGame) { $game }
    elseif ($_.StartsWith('-DignoreList=')) { $_ + ',1.20.1.jar' }
    else { $_ }
})
$argFile = Join-Path $runtime ($GameName + '.args')
for ($i = 0; $i -lt $argv.Count - 1; $i++) {
    if ($argv[$i] -eq '--username') { $argv[$i + 1] = 'U' + [Guid]::NewGuid().ToString('N').Substring(0,10) }
}
$quoted = @($argv | ForEach-Object { '"' + $_.Replace('\','\\').Replace('"','\"') + '"' })
[IO.File]::WriteAllText($argFile,($quoted -join "`n"),[Text.UTF8Encoding]::new($false))
$jdk = $env:UPDATER_JDK
if (-not $jdk) {
    $jdk = Get-ChildItem -LiteralPath (Join-Path $env:ProgramFiles 'Eclipse Adoptium') -Directory -Filter 'jdk-17*' |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin/javaw.exe'))) { throw 'Set UPDATER_JDK to a JDK 17 installation.' }
$start = [Diagnostics.ProcessStartInfo]::new((Join-Path $jdk 'bin/javaw.exe'))
$start.Arguments = '@"' + $argFile.Replace('"','\"') + '"'
$start.WorkingDirectory = $cwd
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$process = [Diagnostics.Process]::Start($start)
[IO.File]::WriteAllText((Join-Path $runtime ($GameName + '.pid')), [string]$process.Id)
Write-Output ('Acceptance process: ' + $process.Id)
