@echo off
setlocal
cd /d "%~dp0"
if defined UPDATER_JDK if not exist "%UPDATER_JDK%\bin\javac.exe" set "UPDATER_JDK="
if not defined UPDATER_JDK if exist "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot\bin\javac.exe" set "UPDATER_JDK=C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
if not defined UPDATER_JDK for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*") do set "UPDATER_JDK=%%D"
if not defined UPDATER_JDK for /d %%D in ("%ProgramFiles%\Java\jdk-17*") do set "UPDATER_JDK=%%D"
if not defined UPDATER_JDK (
  echo [HATA] JDK 17 bulunamadi. Temurin JDK 17 kurun veya UPDATER_JDK yolunu ayarlayin.
  pause
  exit /b 1
)
set "JAVA_HOME=%UPDATER_JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call gradlew.bat build --no-daemon --console=plain
if errorlevel 1 (
  echo [HATA] Derleme veya testler basarisiz. build\reports\tests klasorunu kontrol edin.
  pause
  exit /b 1
)
echo Surum numarasina gore olusturulan tam JAR dosyalari: build\libs
echo Mapping: build\libs\mapping-bootstrap.txt ve mapping-client.txt
pause
