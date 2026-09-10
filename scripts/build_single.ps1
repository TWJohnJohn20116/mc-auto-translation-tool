param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$Target
)

$ErrorActionPreference = "Stop"
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
$manifest = "-Ploom_version_manifests=https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"
$root = "D:\Code\mc-auto-translation-tool"
Set-Location $root

$j25 = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot"
$j21 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$j17 = "D:\Gradle\cache\caches\minecraftforge\forgegradle\mavenizer\caches\microsoft-jdk-17.0.20.1-windows-x64"
$j8 = "C:\Program Files\Eclipse Adoptium\jdk-8.0.502.7-hotspot"

# Normalize target name
$cleanTarget = $Target.Trim().ToLower()
if ($cleanTarget.StartsWith(":")) { $cleanTarget = $cleanTarget.Substring(1) }
if ($cleanTarget.EndsWith(":build")) { $cleanTarget = $cleanTarget.Substring(0, $cleanTarget.Length - 6) }
if ($cleanTarget.StartsWith("platform-")) { $cleanTarget = $cleanTarget.Substring(9) }

# Handle Legacy 1.8.9 / 1.12.2
if ($cleanTarget -eq "1.8.9" -or $cleanTarget -eq "legacy-1.8.9" -or $cleanTarget -eq "forge-1.8.9") {
    $env:JAVA_HOME = $j8
    Write-Host "Building Legacy Forge 1.8.9 using JDK 8..." -ForegroundColor Cyan
    Push-Location "$root\platforms\forge\legacy\1.8.9"
    & ".\gradlew.bat" build
    Pop-Location
    exit $LASTEXITCODE
}
if ($cleanTarget -eq "1.12.2" -or $cleanTarget -eq "legacy-1.12.2" -or $cleanTarget -eq "forge-1.12.2") {
    $env:JAVA_HOME = $j8
    Write-Host "Building Legacy Forge 1.12.2 using JDK 8..." -ForegroundColor Cyan
    Push-Location "$root\platforms\forge\legacy\1.12.2"
    & ".\gradlew.bat" build
    Pop-Location
    exit $LASTEXITCODE
}

# Determine JDK for modern platforms
$selectedJdk = $j21
$jdkName = "JDK 21"

if ($cleanTarget -match "26\.") {
    $selectedJdk = $j25
    $jdkName = "JDK 25"
} elseif ($cleanTarget -match "forge-1\.(16\.5|18\.2|19\.2|20\.1)" -or $cleanTarget -eq "neoforge-1.20.1") {
    $selectedJdk = $j17
    $jdkName = "JDK 17"
}

$platformName = $cleanTarget
$taskName = ":platform-${cleanTarget}:build"

$env:JAVA_HOME = $selectedJdk
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Target platform: $platformName" -ForegroundColor Cyan
Write-Host "Task:            $taskName" -ForegroundColor Cyan
Write-Host "Runtime JDK:     $jdkName ($selectedJdk)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$sw = [Diagnostics.Stopwatch]::StartNew()
$taskArgs = @(
    "--max-workers=8",
    "-PtargetPlatform=$platformName",
    $manifest,
    $taskName
)

& "$root\gradlew.bat" @taskArgs
$code = $LASTEXITCODE
$sw.Stop()

Write-Host "==========================================================" -ForegroundColor Cyan
if ($code -eq 0) {
    Write-Host ("Build SUCCESS in {0:n2}s" -f $sw.Elapsed.TotalSeconds) -ForegroundColor Green
} else {
    Write-Host ("Build FAILED with exit code {0} in {1:n2}s" -f $code, $sw.Elapsed.TotalSeconds) -ForegroundColor Red
}
Write-Host "==========================================================" -ForegroundColor Cyan
exit $code
