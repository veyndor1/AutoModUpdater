param([string]$Prefix='config-')
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$release = Join-Path $workspace 'build/libs/AutoModUpdater-2.0-release.jar'
$expectedRelease = (Get-FileHash -LiteralPath $release -Algorithm SHA256).Hash.ToLowerInvariant()
$cases = @{'config-only'='config-only-no-restart';'dismiss'='dismiss-and-normal-exit';'manual'='manual-exit';'restart'='restart-mod-active-and-server-joined';'recovery'='restart-mod-active-and-server-joined';'offline'='offline-error-no-restart'}
$results = @()
foreach ($case in $cases.Keys) {
    $name = $Prefix + $case; $game = Join-Path $workspace ('.tools/minecraft/' + $name)
    if ([IO.File]::ReadAllText((Join-Path $game 'release-sha256.txt')) -ne $expectedRelease) { throw "Tested artifact differs from final release: $name" }
    if (Test-Path (Join-Path $game 'automated-error.txt')) { throw ([IO.File]::ReadAllText((Join-Path $game 'automated-error.txt'))) }
    $actual = [IO.File]::ReadAllText((Join-Path $game 'automated-result.txt'))
    if ($actual -ne $cases[$case]) { throw "Unexpected result: $name" }
    $probe = [IO.File]::ReadAllText((Join-Path $game 'probe-loaded.json')) | ConvertFrom-Json
    if (-not $probe.earlyConfig -or -not $probe.accessTransformer -or $probe.library -ne 'dependency-loaded') { throw "Probe failed: $name" }
    if ($case -notin @('manual','offline') -and -not (Test-Path (Join-Path $game 'probe-mixin.txt'))) { throw "Mixin failed: $name" }
    $version = [IO.File]::ReadAllText((Join-Path $game 'config/acceptance/settings.toml'))
    if ($version -ne $(if($case -eq 'offline'){'v1'}else{'v2'})) { throw "Wrong installed config: $name" }
    if ([IO.File]::ReadAllText((Join-Path $game 'config/unlisted.toml')) -ne 'untouched') { throw "Unpublished config changed: $name" }
    if ([IO.File]::ReadAllText((Join-Path $game 'config/automodupdater.json')) -ne [IO.File]::ReadAllText((Join-Path $game 'expected-updater-settings.txt'))) { throw "Updater settings changed: $name" }
    $state = Join-Path $game '.automodupdater/packs/mc-mods2-1.20.1'
    $helpers = @(Get-ChildItem -LiteralPath $state -Filter 'result-*.json' | ForEach-Object { [IO.File]::ReadAllText($_.FullName) | ConvertFrom-Json })
    if ($case -notin @('config-only','offline')) {
        if ($helpers.Count -eq 0 -or @($helpers | Where-Object { -not $_.installed -or -not $_.configsInstalled }).Count -gt 0) { throw "Helper installation failed: $name" }
        $relaunch = $case -in @('restart','recovery')
        if (@($helpers | Where-Object {$_.relaunched -eq $relaunch}).Count -eq 0) { throw "Wrong relaunch: $name" }
        $zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $game 'mods/acceptance-extra.jar'))
        try { $reader=[IO.StreamReader]::new($zip.GetEntry('acceptance-extra-version.txt').Open()); try { if($reader.ReadToEnd().Trim() -ne 'v2'){throw "Wrong mod: $name"} } finally {$reader.Dispose()} } finally {$zip.Dispose()}
    }
    if ($case -ne 'offline') {
        if (Test-Path (Join-Path $game 'config/acceptance/user-only.txt')) { throw "Extra file was not removed: $name" }
        $backed = @(Get-ChildItem -LiteralPath (Join-Path $state 'config-backups') -Recurse -File | Where-Object {$_.Name -eq 'user-only.txt'})
        if ($backed.Count -ne 1 -or [IO.File]::ReadAllText($backed[0].FullName) -ne 'must be backed up') { throw "Backup failed: $name" }
        if (-not (Test-Path (Join-Path $game 'config/acceptance/nested/empty'))) { throw "Empty directory lost: $name" }
    }
    if (Test-Path (Join-Path $state 'config-transaction.json')) { throw "Unfinished config transaction: $name" }
    if ($case -in @('restart','recovery') -and -not (Test-Path (Join-Path $game 'server-joined.txt'))) { throw "Server join missing: $name" }
    if ($case -in @('restart','manual') -and [IO.File]::ReadAllText((Join-Path $game 'first-session-prompt.txt')) -ne 'new-mod-inactive:v1;config:v1') { throw 'First-session pairing failed' }
    if ($case -eq 'recovery') {
        $sessions=@(Get-ChildItem -LiteralPath $state -Filter 'session-*.json' | ForEach-Object {[IO.File]::ReadAllText($_.FullName)|ConvertFrom-Json})
        if (@($sessions | Where-Object {$_.recovery}).Count -eq 0) { throw 'Recovery not exercised' }
    }
    $results += @{profile=$name;result=$actual;releaseSha256=$expectedRelease;earlyConfig=$probe.earlyConfig;configAtStartup=$probe.configVersion;helpers=$helpers}
}
$release = Join-Path $workspace 'build/libs/AutoModUpdater-2.0-release.jar'
$sha = [Security.Cryptography.SHA256]::Create(); $stream = [IO.File]::OpenRead($release)
try {$digest=[BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant()} finally {$stream.Dispose();$sha.Dispose()}
$report=@{minecraft='1.20.1';forge='47.4.10';java='17';releaseSha256=$digest;cases=$results;verifiedAt=[DateTimeOffset]::Now.ToString('o')}
$output=Join-Path $workspace 'build/config-acceptance-results.json'
[IO.File]::WriteAllText($output,($report|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
Write-Output ('All config Java acceptance cases passed: ' + $output)
