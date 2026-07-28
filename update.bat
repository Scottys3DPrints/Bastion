@echo off
setlocal enabledelayedexpansion
title Bastion - build and update phone

REM ---------------------------------------------------------------------------
REM  Rebuilds Bastion and installs it OVER the copy already on your phone.
REM
REM  This is an in-place upgrade, not a reinstall: "adb install -r" keeps the
REM  app's data, so the covenant, the signature, the streak, the rank and every
REM  log survive untouched. Bastion is never uninstalled.
REM ---------------------------------------------------------------------------

cd /d "%~dp0"

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

echo.
echo  == Bastion ==
echo.

if not exist "keystore.properties" (
    echo  [X] keystore.properties is missing.
    echo.
    echo      Without the original signing key Android will refuse to update
    echo      the installed app, and the only way forward would be uninstalling
    echo      it - which destroys your journey. Restore your backup of
    echo      keystore.properties and bastion-release.jks before continuing.
    echo.
    pause
    exit /b 1
)

echo  Building release...
call gradlew.bat :app:assembleRelease --quiet
if errorlevel 1 (
    echo.
    echo  [X] Build failed. Nothing was sent to the phone.
    pause
    exit /b 1
)

set "APK=app\build\outputs\apk\release\app-release.apk"

echo.
echo  Looking for your phone...
"%ADB%" start-server >nul 2>&1
for /f "skip=1 tokens=1,2" %%a in ('"%ADB%" devices') do (
    if "%%b"=="device" set "DEVICE=%%a"
)

if not defined DEVICE (
    echo.
    echo  [!] No phone detected.
    echo.
    echo      Plug it in and make sure USB debugging is on
    echo      ^(Settings ^> Developer options ^> USB debugging^).
    echo.
    echo      The APK is still built and ready here:
    echo      %CD%\%APK%
    echo.
    pause
    exit /b 1
)

echo  Found !DEVICE!. Installing over the existing app...
echo.
"%ADB%" install -r "%APK%"

if errorlevel 1 (
    echo.
    echo  [X] Install failed.
    echo.
    echo      If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE, this APK was signed
    echo      with a different key than the one already on the phone. Do NOT
    echo      uninstall to work around it - that erases everything. Find the
    echo      original bastion-release.jks instead.
    echo.
    pause
    exit /b 1
)

echo.
echo  [OK] Updated in place. Your data was not touched.
echo.
pause
