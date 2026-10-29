@echo off
:: Batch script to add Windows Defender exclusions for Gradle & MCAutoTranslationTool
:: Must be run as Administrator
::
:: 專案目錄由腳本自身位置推導（原本硬編 D:\Code\mc-auto-translation-tool），
:: Gradle 快取沿用既有的 GRADLE_USER_HOME，未設定時才退到 <repo>\build\gradle-user-home。

setlocal
set "PROJECT_DIR=%~dp0.."
for %%I in ("%PROJECT_DIR%") do set "PROJECT_DIR=%%~fI"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%PROJECT_DIR%\build\gradle-user-home"

net session >nul 2>&1
if %errorLevel% neq 0 (
    echo Requesting administrative privileges...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

echo =======================================================
echo Adding Windows Defender Exclusions...
echo =======================================================

powershell -NoProfile -Command "& { Add-MpPreference -ExclusionPath '%PROJECT_DIR%', '%GRADLE_USER_HOME%'; Add-MpPreference -ExclusionProcess 'java.exe'; Write-Host 'Successfully added exclusions!' -ForegroundColor Green }"

echo.
echo Windows Defender exclusions successfully configured:
echo   - Folder: %PROJECT_DIR%
echo   - Folder: %GRADLE_USER_HOME%
echo   - Process: java.exe
echo.
pause
