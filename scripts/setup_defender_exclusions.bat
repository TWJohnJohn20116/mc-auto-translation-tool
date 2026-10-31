@echo off
:: Batch script to add Windows Defender exclusions for Gradle & MCAutoTranslationTool
:: Must be run as Administrator
::
:: 專案目錄由腳本自身位置推導（原本硬編 D:\Code\mc-auto-translation-tool）。
:: Gradle 快取的 fallback 只由 ci\set-defender-exclusions.ps1 決定（GRADLE_USER_HOME，否則
:: %USERPROFILE%\.gradle）。批次檔先前也有一份 fallback，值是 <repo>\build\gradle-user-home，
:: 與 .ps1 不同而且通常不存在——Add-MpPreference 對不存在的路徑不報錯，於是腳本回報成功，
:: 真正的快取卻完全沒被排除。

setlocal
set "PROJECT_DIR=%~dp0.."
for %%I in ("%PROJECT_DIR%") do set "PROJECT_DIR=%%~fI"

net session >nul 2>&1
if %errorLevel% neq 0 (
    echo Requesting administrative privileges...
    rem 路徑走環境變數：把 %~f0 插進 -Command 的字串內容時，含 ' 或 & 的路徑會讓整條命令提早
    rem 結束、提權靜默失敗（下一行就 exit /b，離開碼看不出來）。-FilePath 吃的是變數本身。
    set "BATCH_SELF=%~f0"
    powershell -NoProfile -Command "Start-Process -FilePath $env:BATCH_SELF -Verb RunAs"
    exit /b
)

echo =======================================================
echo Adding Windows Defender Exclusions...
echo =======================================================

echo WARNING: these exclusions lower this machine's malware protection. Use them only on a
echo          dedicated build machine. Only two folders are excluded - never the java.exe process,
echo          which would switch off real-time scanning for every Java program on this machine.
echo.

:: 目錄路徑由 .ps1 自己用 $PSScriptRoot 推導，批次檔不把路徑插進 PowerShell 字串，
:: 否則路徑含 ' 或 & 時整條 -Command 會解析失敗（且失敗不會被偵測）。
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0ci\set-defender-exclusions.ps1"
if errorlevel 1 (
    echo.
    echo FAILED: Windows Defender exclusions were NOT applied.
    echo         Run this script from an elevated prompt on a machine whose Defender policy
    echo         (or Tamper Protection) allows Add-MpPreference.
    exit /b 1
)

echo.
echo Windows Defender exclusions successfully configured. The applied folders are listed above.
echo.
pause
