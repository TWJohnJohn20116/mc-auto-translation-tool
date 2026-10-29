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
$gradleHome = $env:GRADLE_USER_HOME
if ([string]::IsNullOrWhiteSpace($gradleHome)) {
    $gradleHome = Join-Path $projectDir "build\gradle-user-home"
}

try {
    Add-MpPreference -ExclusionPath $projectDir, $gradleHome -ErrorAction Stop
} catch {
    Write-Host "ERROR: Add-MpPreference failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host "Successfully added exclusions!" -ForegroundColor Green
exit 0
