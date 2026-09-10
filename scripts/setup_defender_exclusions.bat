@echo off
:: Batch script to add Windows Defender exclusions for Gradle & MCAutoTranslationTool
:: Must be run as Administrator

net session >nul 2>&1
if %errorLevel% neq 0 (
    echo Requesting administrative privileges...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

echo =======================================================
echo Adding Windows Defender Exclusions...
echo =======================================================

powershell -NoProfile -Command "& { Add-MpPreference -ExclusionPath 'D:\Code\mc-auto-translation-tool', 'D:\Gradle\cache'; Add-MpPreference -ExclusionProcess 'java.exe'; Write-Host 'Successfully added exclusions!' -ForegroundColor Green }"

echo.
echo Windows Defender exclusions successfully configured:
echo   - Folder: D:\Code\mc-auto-translation-tool
echo   - Folder: D:\Gradle\cache
echo   - Process: java.exe
echo.
pause
