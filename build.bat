@echo off
setlocal enabledelayedexpansion

echo ============================================
echo   AI Chat App - Build Script
echo ============================================
echo.

set "WORKBUDDY=%USERPROFILE%\.workbuddy"
set "TOOLS=%WORKBUDDY%\tools"
set "JAVA_HOME=%TOOLS%\jdk-17"
set "ANDROID_HOME=%TOOLS%\android-sdk"
set "GRADLE_HOME=%TOOLS%\gradle-8.6"

:: ---- Step 1: JDK 17 ----
echo [1/4] Checking JDK 17...
if not exist "%JAVA_HOME%\bin\java.exe" (
    if not exist "%TOOLS%\jdk17.zip" (
        echo ERROR: jdk17.zip not found in %TOOLS%
        echo Please download JDK 17 from:
        echo   https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse
        echo Save as %TOOLS%\jdk17.zip
        exit /b 1
    )

    echo Extracting JDK 17...
    powershell -Command "Expand-Archive -Path '%TOOLS%\jdk17.zip' -DestinationPath '%TOOLS%\jdk-temp' -Force"

    :: Find the extracted JDK directory
    for /d %%d in ("%TOOLS%\jdk-temp\*") do (
        if exist "%%d\bin\java.exe" (
            move "%%d" "%JAVA_HOME%" >nul 2>&1
        )
    )
    rmdir /s /q "%TOOLS%\jdk-temp" 2>nul
    echo JDK 17 extracted to %JAVA_HOME%
) else (
    echo JDK 17 already installed.
)

:: ---- Step 2: Android SDK ----
echo [2/4] Checking Android SDK...
if not exist "%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat" (
    if not exist "%TOOLS%\android-cmdline.zip" (
        echo ERROR: android-cmdline.zip not found
        echo Download from: https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip
        echo Save as %TOOLS%\android-cmdline.zip
        exit /b 1
    )

    echo Extracting Android SDK command-line tools...
    powershell -Command "Expand-Archive -Path '%TOOLS%\android-cmdline.zip' -DestinationPath '%ANDROID_HOME%\cmdline-tools\latest' -Force"
    
    :: Fix directory structure if needed
    if exist "%ANDROID_HOME%\cmdline-tools\latest\cmdline-tools" (
        move "%ANDROID_HOME%\cmdline-tools\latest\cmdline-tools\*" "%ANDROID_HOME%\cmdline-tools\latest\" >nul 2>&1
        rmdir /s /q "%ANDROID_HOME%\cmdline-tools\latest\cmdline-tools" 2>nul
    )
    echo Android SDK tools extracted.
) else (
    echo Android SDK tools already installed.
)

:: Install SDK components
echo Installing SDK components...
set "PATH=%JAVA_HOME%\bin;%PATH%"
call "%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="%ANDROID_HOME%" "platform-tools" "build-tools;34.0.0" "platforms;android-34"

:: ---- Step 3: Gradle ----
echo [3/4] Checking Gradle 8.6...
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
    if not exist "%TOOLS%\gradle-8.6-bin.zip" (
        echo Downloading Gradle 8.6...
        powershell -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.6-bin.zip' -OutFile '%TOOLS%\gradle-8.6-bin.zip' -UseBasicParsing"
    )

    echo Extracting Gradle...
    powershell -Command "Expand-Archive -Path '%TOOLS%\gradle-8.6-bin.zip' -DestinationPath '%TOOLS%' -Force"
    echo Gradle 8.6 installed.
) else (
    echo Gradle 8.6 already installed.
)

:: ---- Step 4: Build ----
echo [4/4] Building APK...
set "PATH=%JAVA_HOME%\bin;%GRADLE_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"

:: Write local.properties
(
    echo sdk.dir=%ANDROID_HOME:\=/%
) > "%~dp0local.properties"

cd /d "%~dp0"
call gradle assembleDebug

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ============================================
    echo   BUILD SUCCESS!
    echo   APK: app\build\outputs\apk\debug\app-debug.apk
    echo ============================================
) else (
    echo.
    echo BUILD FAILED. Check errors above.
)

endlocal
