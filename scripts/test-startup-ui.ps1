param([ValidateSet('config-only','restart','exit','offline','manual','current','removal')][string]$Case,
      [ValidatePattern('^[a-z0-9-]+$')][string]$GameName)
$ErrorActionPreference='Stop'
$workspace=Split-Path $PSScriptRoot -Parent
$requestedCase=$Case
if ($Case -eq 'removal') { $Case='restart' }
if (-not $GameName) { $GameName='ui-'+$Case+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6) }
$prepareCase=if($Case -eq 'exit' -or $Case -eq 'current'){'restart'}else{$Case}
& (Join-Path $PSScriptRoot 'prepare-acceptance.ps1') -Case $prepareCase -GameName $GameName
$game=Join-Path $workspace ('.tools/minecraft/'+$GameName)
if ($requestedCase -eq 'removal') {
    $forbidden=Join-Path $game 'mods/forbidden-copy.jar'
    Copy-Item -LiteralPath (Join-Path $workspace 'client/build/acceptance/acceptance-extra.jar') -Destination $forbidden
    $forbiddenHash=(Get-FileHash -LiteralPath $forbidden -Algorithm SHA256).Hash
    $manifestPath=Join-Path $workspace ('.tools/config-acceptance-web/'+$GameName+'/server-mods.json')
    $manifest=Get-Content -Raw -LiteralPath $manifestPath -Encoding UTF8 | ConvertFrom-Json
    $manifest.removeMods=@(@{fileName='forbidden-copy.jar';name='Uyumsuz eski mod';reason='Paket ile cakisan eski kopya.'})
    [IO.File]::WriteAllText($manifestPath,($manifest | ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))
}
[IO.File]::WriteAllText((Join-Path $game 'automated-case.txt'),'ui-'+$Case)
if($Case -eq 'current') {
    Copy-Item -LiteralPath (Join-Path $workspace ('.tools/acceptance-source/'+$GameName+'/mods/acceptance-extra.jar')) -Destination (Join-Path $game 'mods/acceptance-extra.jar') -Force
    Copy-Item -LiteralPath (Join-Path $workspace ('.tools/acceptance-source/'+$GameName+'/configs/acceptance/settings.toml')) -Destination (Join-Path $game 'config/acceptance/settings.toml') -Force
    Remove-Item -LiteralPath (Join-Path $game 'config/acceptance/user-only.txt')
    Copy-Item -LiteralPath (Join-Path $workspace ('.tools/acceptance-source/'+$GameName+'/configs/acceptance/nested')) -Destination (Join-Path $game 'config/acceptance/nested') -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $workspace ('.tools/acceptance-source/'+$GameName+'/configs/root.toml')) -Destination (Join-Path $game 'config/root.toml')
}
$runtime=Join-Path $workspace '.tools/minecraft'
$argv=Get-Content -Raw -LiteralPath (Join-Path $workspace '.tools/runtime-command.json') | ConvertFrom-Json
$oldGame=Join-Path $runtime 'game'
$argv=@($argv | ForEach-Object { if($_ -eq $oldGame){$game}elseif($_.StartsWith('-DignoreList=')){$_+',1.20.1.jar'}else{$_} })
$argv=@('-javaagent:'+(Join-Path $workspace 'build/ui-driver.jar')+'='+$game)+$argv
$argFile=Join-Path $runtime ($GameName+'.args')
$quoted=@($argv | ForEach-Object { '"'+$_.Replace('\','\\').Replace('"','\"')+'"' })
[IO.File]::WriteAllText($argFile,($quoted -join "`n"),[Text.UTF8Encoding]::new($false))
$release=Join-Path $workspace 'build/libs/AutoModUpdater-2.0-release.jar'
Copy-Item -LiteralPath $release -Destination (Join-Path $game 'mods/AutoModUpdater.jar') -Force
$cwd=Join-Path $runtime ($GameName+'-cwd'); [IO.Directory]::CreateDirectory($cwd) | Out-Null
$start=[Diagnostics.ProcessStartInfo]::new((Get-Command java).Source.Replace('java.exe','javaw.exe'))
$start.Arguments='@"'+$argFile+'"'; $start.WorkingDirectory=$cwd; $start.UseShellExecute=$false; $start.CreateNoWindow=$true
$process=[Diagnostics.Process]::Start($start)
Write-Output ('Profile: '+$GameName+'; PID: '+$process.Id)
if(-not $process.WaitForExit(120000)) { throw 'Acceptance process did not exit; inspect this isolated profile.' }
if($process.ExitCode -ne 0) { throw ('Minecraft exited with '+$process.ExitCode) }
$state=Join-Path $game '.automodupdater/packs/mc-mods2-1.20.1'
$until=[DateTime]::UtcNow.AddSeconds(100)
while([DateTime]::UtcNow -lt $until) {
    if(Test-Path (Join-Path $game 'automated-error.txt')) { throw ([IO.File]::ReadAllText((Join-Path $game 'automated-error.txt'))) }
    if(Test-Path (Join-Path $game 'ui-driver-error.txt')) { throw ([IO.File]::ReadAllText((Join-Path $game 'ui-driver-error.txt'))) }
    $results=@(Get-ChildItem -LiteralPath $state -Filter 'result-*.json' -ErrorAction SilentlyContinue | ForEach-Object { Get-Content -Raw $_.FullName | ConvertFrom-Json })
    $ready=if($Case -in @('exit','manual')){$results.Count -gt 0}else{($Case -eq 'offline') -or (Test-Path (Join-Path $game 'automated-result.txt'))}
    if($ready) { break }; Start-Sleep -Milliseconds 250
}
if(-not $ready) { throw 'Final acceptance evidence missing.' }
foreach($result in $results) {
    if(-not $result.installed -or -not $result.configsInstalled) { throw 'Helper did not finish the complete release.' }
    if($result.relaunched -ne ($Case -eq 'restart')) { throw 'Wrong relaunch decision.' }
}
if($Case -in @('exit','manual','offline') -and (Test-Path (Join-Path $game 'probe-loaded.json'))) { throw 'Game loaded after exit decision.' }
if($Case -in @('config-only','restart','current')) {
    if([IO.File]::ReadAllText((Join-Path $game 'automated-result.txt')) -ne ('ui-'+$Case+'-active-no-prompt')) { throw 'Wrong in-game outcome.' }
    if($Case -eq 'current' -and (Test-Path (Join-Path $game 'startup-decision.txt'))) { throw 'Unchanged package asked for confirmation.' }
}
if(@(Get-ChildItem -LiteralPath (Join-Path $game 'crash-reports') -File -ErrorAction SilentlyContinue).Count -gt 0) { throw 'Crash report generated.' }
if ($requestedCase -eq 'removal') {
    if (Test-Path -LiteralPath $forbidden) { throw 'Mandatory mod was left in mods.' }
    $backups=@(Get-ChildItem -LiteralPath (Join-Path $state 'removed-mods') -Filter 'forbidden-copy.jar' -Recurse -File)
    if ($backups.Count -ne 1 -or (Get-FileHash -LiteralPath $backups[0].FullName -Algorithm SHA256).Hash -ne $forbiddenHash) { throw 'Removed mod backup differs.' }
}
$report=@{case=$requestedCase;profile=$GameName;initialExitCode=$process.ExitCode;helpers=$results;crashReports=0;releaseSha256=(Get-FileHash -LiteralPath $release -Algorithm SHA256).Hash;verifiedAt=[DateTimeOffset]::Now.ToString('o')}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $game 'ui-acceptance.json') -Encoding utf8
Write-Output ('PASS: '+$requestedCase+'; exit=0; no crash report; '+$game)
