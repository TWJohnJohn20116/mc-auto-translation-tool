# Applies the Windows Defender folder exclusions used by the local Gradle cache.
#
# Every path is derived from $PSScriptRoot on purpose: the caller used to interpolate the
# project directory into a -Command string, so a path containing ' or & broke the whole
# command line - and because the caller never checked the exit code, the broken run still
# printed "Successfully added exclusions!".
#
# This file stays ASCII-only: Windows PowerShell 5.1 reads BOM-less scripts as ANSI, so
# non-ASCII text here would be mangled on some machines.
#
# The java.exe *process* exclusion is deliberately absent: it would switch off real-time
# scanning for every Java program on the machine instead of just this project's builds.

$ErrorActionPreference = "Stop"

$projectDir = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
# The fallback has to be Gradle's own rule (GRADLE_USER_HOME, otherwise ~/.gradle), the same one
# scripts/tools/_cache_paths.py uses. The batch caller used to prepare <repo>\build\gradle-user-home
# instead, which usually does not exist - and Add-MpPreference accepts a missing path without an
# error, so the script reported success while the real cache stayed fully scanned.
$gradleHome = $env:GRADLE_USER_HOME
if ([string]::IsNullOrWhiteSpace($gradleHome)) {
    $gradleHome = Join-Path $env:USERPROFILE ".gradle"
}
if (-not (Test-Path -LiteralPath $gradleHome)) {
    Write-Host "ERROR: Gradle cache directory does not exist: $gradleHome" -ForegroundColor Red
    Write-Host "       Set GRADLE_USER_HOME to the real cache, or run Gradle once to create it." -ForegroundColor Red
    exit 1
}

try {
    Add-MpPreference -ExclusionPath $projectDir, $gradleHome -ErrorAction Stop
} catch {
    Write-Host "ERROR: Add-MpPreference failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host "Successfully added exclusions!" -ForegroundColor Green
Write-Host "  - Folder: $projectDir"
Write-Host "  - Folder: $gradleHome"
exit 0
