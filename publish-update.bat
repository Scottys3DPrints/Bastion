@echo off
setlocal enabledelayedexpansion
title Bastion - publish an update

REM ---------------------------------------------------------------------------
REM  Builds a release and produces everything the phone needs to update itself
REM  over the air, in dist\:
REM
REM      Bastion-<version>.apk    the signed build
REM      bastion-update.json      the manifest the app polls
REM
REM  Upload BOTH to wherever you host them (a GitHub release works well and is
REM  free), then put the URL of bastion-update.json into Bastion's
REM  Settings > Updates field once. After that the phone updates itself.
REM ---------------------------------------------------------------------------

cd /d "%~dp0"
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

echo.
echo  == Bastion - publish ==
echo.

if not exist "keystore.properties" (
    echo  [X] keystore.properties is missing - the build would be unsigned and
    echo      could never update an existing install. Restore your backup.
    pause
    exit /b 1
)

REM Read the version straight out of the build file so it can never drift.
for /f "tokens=2 delims==" %%v in ('findstr /r /c:"^ *versionCode *=" app\build.gradle.kts') do set "VCODE=%%v"
for /f "tokens=2 delims==" %%v in ('findstr /r /c:"^ *versionName *=" app\build.gradle.kts') do set "VNAME=%%v"
set "VCODE=%VCODE: =%"
set "VNAME=%VNAME: =%"
set "VNAME=%VNAME:"=%"

echo  Version %VNAME% ^(code %VCODE%^)
echo.

if "%VCODE%"=="" (
    echo  [X] Could not read versionCode from app\build.gradle.kts
    pause
    exit /b 1
)

echo  Running tests...
call gradlew.bat :app:testDebugUnitTest --quiet
if errorlevel 1 (
    echo.
    echo  [X] Tests failed - not publishing. This is the guard that stops a
    echo      broken database migration reaching a phone that cannot recover.
    pause
    exit /b 1
)

echo  Building release...
call gradlew.bat :app:assembleRelease --quiet
if errorlevel 1 (
    echo  [X] Build failed.
    pause
    exit /b 1
)

if not exist "dist" mkdir dist
set "APK=dist\Bastion-%VNAME%.apk"
copy /y "app\build\outputs\apk\release\app-release.apk" "%APK%" >nul

for /f %%h in ('powershell -NoProfile -Command "(Get-FileHash '%APK%' -Algorithm SHA256).Hash.ToLower()"') do set "SHA=%%h"

REM ---- Set this once to wherever the APK will actually be downloadable from ----
set "BASE_URL=https://example.com/bastion"

>dist\bastion-update.json (
    echo {
    echo   "versionCode": %VCODE%,
    echo   "versionName": "%VNAME%",
    echo   "apkUrl": "%BASE_URL%/Bastion-%VNAME%.apk",
    echo   "sha256": "%SHA%",
    echo   "notes": "",
    echo   "releasedAt": "%DATE%"
    echo }
)

echo.
echo  [OK] Ready in dist\
echo.
echo      %APK%
echo      dist\bastion-update.json
echo.
echo      sha256  %SHA%
echo.
echo  Next:
echo    1. Edit BASE_URL near the bottom of this script to your real host
echo       ^(then re-run^), or fix the apkUrl inside bastion-update.json by hand.
echo    2. Upload both files.
echo    3. First time only: paste the bastion-update.json URL into
echo       Bastion ^> Settings ^> Updates on your phone.
echo.
pause
