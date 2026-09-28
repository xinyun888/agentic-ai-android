@echo off
setlocal

echo ============================================
echo   AI Chat App - Linux APK Build (targetSdk 28)
echo ============================================
echo.

set "WORKBUDDY=%USERPROFILE%\.workbuddy"
set "TOOLS=%WORKBUDDY%\tools"
set "JAVA_HOME=%TOOLS%\jdk-17"
set "ANDROID_HOME=%TOOLS%\android-sdk"
set "GRADLE_HOME=%TOOLS%\gradle-8.6"

if not exist "app\src\main\jniLibs\arm64-v8a\libproot_exec.so" (
    echo Missing Linux runtime.
    echo Run first:
    echo   powershell -ExecutionPolicy Bypass -File fetch-linux-runtime.ps1
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%GRADLE_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"
cd /d "%~dp0"

call gradle :app:assembleRelease -PtargetSdk=28

if %ERRORLEVEL% EQU 0 (
    echo.
    echo BUILD SUCCESS!
    echo APK: app\build\outputs\apk\release\app-release.apk
) else (
    echo.
    echo BUILD FAILED.
)

endlocal