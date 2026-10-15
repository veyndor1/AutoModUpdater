@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0make-manifest.ps1" -IncludeUpdater
if errorlevel 1 (
  echo Publication failed. The previous manifest has not been replaced.
  pause
  exit /b 1
)
pause
exit /b 0
