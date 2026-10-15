#requires -Version 7.0
param([ValidatePattern('^[a-z0-9-]+$')][string]$GameName)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
if (-not $GameName) { $GameName = 'self-update-' + [Guid]::NewGuid().ToString('N').Substring(0,8) }
$game = Join-Path $workspace ('.tools/minecraft/' + $GameName)
if (Test-Path -LiteralPath $game) { throw 'Use a new isolated profile.' }
$jdk = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
if ($env:UPDATER_JDK) { $jdk = $env:UPDATER_JDK }
$classpath = [IO.File]::ReadAllText((Join-Path $workspace '.tools/acceptance-classpath.txt'))
$serverStart = [Diagnostics.ProcessStartInfo]::new((Join-Path $jdk 'bin/java.exe'))
$serverStart.ArgumentList.Add('-cp'); $serverStart.ArgumentList.Add($classpath)
$serverStart.ArgumentList.Add('com.skymc.autoupdater.bootstrap.SelfUpdateAcceptanceFixture')
$serverStart.ArgumentList.Add($game); $serverStart.ArgumentList.Add((Join-Path $workspace 'build/libs/AutoModUpdater-1.jar'))
$serverStart.WorkingDirectory = $workspace; $serverStart.UseShellExecute = $false; $serverStart.CreateNoWindow = $true
$serverStart.RedirectStandardOutput = $true; $serverStart.RedirectStandardError = $true
$server = [Diagnostics.Process]::Start($serverStart)
$serverOutput = $server.StandardOutput.ReadToEndAsync(); $serverError = $server.StandardError.ReadToEndAsync()
$process = $null
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    while (-not (Test-Path -LiteralPath (Join-Path $game 'fixture/ready.txt'))) {
        if ($server.HasExited) { throw ('HTTPS fixture exited: ' + $serverError.GetAwaiter().GetResult()) }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'HTTPS fixture did not start.' }
        Start-Sleep -Milliseconds 100
    }
    $runtime = Join-Path $workspace '.tools/minecraft'
    $oldGame = Join-Path $runtime 'game'
    $argv = @(Get-Content -Raw -LiteralPath (Join-Path $workspace '.tools/runtime-command.json') | ConvertFrom-Json | ForEach-Object {
        if ($_ -eq $oldGame) { $game } elseif ($_.StartsWith('-DignoreList=')) { $_ + ',1.20.1.jar' } else { $_ }
    })
    $argv = @(('-javaagent:' + (Join-Path $workspace 'build/ui-driver.jar') + '=' + $game),
        ('-Djavax.net.ssl.trustStore=' + (Join-Path $game 'fixture/test-tls.p12')), '-Djavax.net.ssl.trustStorePassword=test-password') + $argv
    $argFile = Join-Path $runtime ($GameName + '.args')
    $quoted = @($argv | ForEach-Object { '"' + $_.Replace('\','\\').Replace('"','\"') + '"' })
    [IO.File]::WriteAllText($argFile,($quoted -join "`n"),[Text.UTF8Encoding]::new($false))
    $cwd = Join-Path $runtime ($GameName + '-cwd'); [IO.Directory]::CreateDirectory($cwd) | Out-Null
    $start = [Diagnostics.ProcessStartInfo]::new((Join-Path $jdk 'bin/javaw.exe'))
    $start.Arguments = '@"' + $argFile + '"'; $start.WorkingDirectory = $cwd
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $process = [Diagnostics.Process]::Start($start)
    [IO.File]::WriteAllText((Join-Path $game 'initial-pid.txt'),[string]$process.Id)
    Write-Output ('Self-update Minecraft profile: ' + $game + '; PID: ' + $process.Id)
    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    while (-not (Test-Path -LiteralPath (Join-Path $game 'automated-result.txt'))) {
        foreach ($errorFile in @('ui-driver-error.txt','automated-error.txt')) {
            $path = Join-Path $game $errorFile
            if (Test-Path -LiteralPath $path) { throw ([IO.File]::ReadAllText($path)) }
        }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Minecraft self-update acceptance timed out.' }
        Start-Sleep -Milliseconds 250
    }
    if (-not $process.WaitForExit(10000) -or $process.ExitCode -ne 0) { throw 'Initial Minecraft process did not exit cleanly.' }
    $state = Join-Path $game '.automodupdater/packs/mc-mods2-1.20.1'
    $receipt = Get-Content -Raw -LiteralPath (Join-Path $state ('result-' + $process.Id + '.json')) | ConvertFrom-Json
    if (-not $receipt.installed -or -not $receipt.relaunched) { throw 'Helper did not install and relaunch.' }
    if (Test-Path -LiteralPath (Join-Path $game 'mods/AutoModUpdater-1.jar')) { throw 'Old filename left in mods.' }
    if (-not (Test-Path -LiteralPath (Join-Path $game 'mods/AutoModUpdater-2.jar'))) { throw 'New filename missing.' }
    if ((Get-FileHash -LiteralPath (Join-Path $game 'mods/AutoModUpdater-2.jar')).Hash -ne (Get-FileHash -LiteralPath (Join-Path $game 'fixture/future-test-only.jar')).Hash) { throw 'Installed bytes differ.' }
    if (Test-Path -LiteralPath (Join-Path $state 'self-update.json')) { throw 'Self-update journal not completed.' }
    if ([IO.File]::ReadAllText((Join-Path $game 'startup-decision.txt')) -ne 'ui-restart') { throw 'Existing Continue button was not used.' }
    if ([IO.File]::ReadAllText((Join-Path $game 'automated-result.txt')) -ne 'ui-restart-active-no-prompt') { throw 'Relaunched game was not active without a duplicate prompt.' }
    if ([IO.File]::ReadAllText((Join-Path $game 'self-update-loaded-version.txt')) -ne '2') { throw 'Forge did not load updater version 2.' }
    foreach ($crashRoot in @($game,$cwd)) {
        if (@(Get-ChildItem -LiteralPath (Join-Path $crashRoot 'crash-reports') -File -ErrorAction SilentlyContinue).Count -gt 0) { throw 'Minecraft crash report generated.' }
    }
    $settings = Get-Content -Raw -LiteralPath (Join-Path $game 'config/automodupdater.json') | ConvertFrom-Json
    if ($settings.'custom-preserve' -ne 'unchanged') { throw 'User settings changed.' }
    $report = @{profile=$game;fromVersion=1;toVersion=2;forgeLoadedVersion=2;button='existing Continue';initialExitCode=0;installed=$receipt.installed;relaunched=$receipt.relaunched;noDuplicatePrompt=$true;settingsPreserved=$true;crashReports=0;verifiedAt=[DateTimeOffset]::Now.ToString('o')}
    $report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $game 'self-update-acceptance.json') -Encoding UTF8
    Write-Output ('PASS: real Forge 1.20.1 / 47.4.10; existing Continue -> full updater replacement -> new JVM -> active game; ' + $game)
} finally {
    if (Test-Path -LiteralPath (Join-Path $game 'fixture')) { [IO.File]::WriteAllText((Join-Path $game 'fixture/stop'),'stop') }
    if (-not $server.WaitForExit(5000)) { $server.Kill($true) }
    [IO.File]::WriteAllText((Join-Path $workspace ('build/' + $GameName + '-fixture.log')),($serverOutput.GetAwaiter().GetResult() + $serverError.GetAwaiter().GetResult()))
}
