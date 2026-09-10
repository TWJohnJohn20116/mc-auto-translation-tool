# Builds all Minecraft platforms (Fabric, Forge, NeoForge, Legacy) with multi-worker parallelism and build cache.
$ErrorActionPreference = "Continue"
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
$logDir = "D:\Code\mc-auto-translation-tool\build\release-logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Remove-Item (Join-Path $logDir "summary.txt") -ErrorAction SilentlyContinue
$manifest = "-Ploom_version_manifests=https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"
$root = "D:\Code\mc-auto-translation-tool"
Set-Location $root

$j25 = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot"
$j21 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$j17 = "D:\Gradle\cache\caches\minecraftforge\forgegradle\mavenizer\caches\microsoft-jdk-17.0.20.1-windows-x64"
$j8 = "C:\Program Files\Eclipse Adoptium\jdk-8.0.502.7-hotspot"

function Invoke-TargetBuild {
    param(
        [string]$Name,
        [string]$JavaHome,
        [string]$TargetPlatform,
        [string[]]$Tasks
    )
    $log = Join-Path $logDir "$Name.log"
    $env:JAVA_HOME = $JavaHome
    $taskArgs = @("--max-workers=8", "-x", "test", "-PtargetPlatform=$TargetPlatform", $manifest) + $Tasks
    Write-Host "==== $Name ===="
    $sw = [Diagnostics.Stopwatch]::StartNew()
    & "$root\gradlew.bat" @taskArgs *> $log
    $code = $LASTEXITCODE
    $sw.Stop()
    Add-Content -Path (Join-Path $logDir "summary.txt") -Value ("{0,-28} exit={1} {2:n1}s" -f $Name, $code, $sw.Elapsed.TotalSeconds)
    Write-Host ("{0} exit={1} {2:n1}s" -f $Name, $code, $sw.Elapsed.TotalSeconds)
    return $code
}

$failed = 0

# === JDK 25: 26.x platforms (Fabric + Forge merged) ===
$jdk25Platforms = "fabric-26.x,forge-26.1,forge-26.1.1,forge-26.1.2,forge-26.2"
$jdk25Tasks = @(
    ":platform-fabric-26.x:build",
    ":platform-forge-26.1:build",
    ":platform-forge-26.1.1:build",
    ":platform-forge-26.1.2:build",
    ":platform-forge-26.2:build"
)
$failed += Invoke-TargetBuild "jdk25-combined" $j25 $jdk25Platforms $jdk25Tasks

# === JDK 21: 1.13 through 1.21 platforms (Fabric + Forge + NeoForge merged) ===
$jdk21Platforms = "fabric-1.13.x,fabric-1.14-1.15.x,fabric-1.16.5,fabric-1.17-1.18.x,fabric-1.19.x,fabric-1.20.x,fabric-1.21.x," +
    "forge-1.21,forge-1.21.1,forge-1.21.3,forge-1.21.4,forge-1.21.5,forge-1.21.6,forge-1.21.7,forge-1.21.8,forge-1.21.9,forge-1.21.10,forge-1.21.11," +
    "neoforge-1.21.1,neoforge-1.21.3,neoforge-1.21.11"
$jdk21Tasks = @(
    ":platform-fabric-1.13.x:build", ":platform-fabric-1.14-1.15.x:build", ":platform-fabric-1.16.5:build",
    ":platform-fabric-1.17-1.18.x:build", ":platform-fabric-1.19.x:build", ":platform-fabric-1.20.x:build",
    ":platform-fabric-1.21.x:build",
    ":platform-forge-1.21:build", ":platform-forge-1.21.1:build", ":platform-forge-1.21.3:build",
    ":platform-forge-1.21.4:build", ":platform-forge-1.21.5:build", ":platform-forge-1.21.6:build",
    ":platform-forge-1.21.7:build", ":platform-forge-1.21.8:build", ":platform-forge-1.21.9:build",
    ":platform-forge-1.21.10:build", ":platform-forge-1.21.11:build",
    ":platform-neoforge-1.21.1:build", ":platform-neoforge-1.21.3:build", ":platform-neoforge-1.21.11:build"
)
$failed += Invoke-TargetBuild "jdk21-combined" $j21 $jdk21Platforms $jdk21Tasks

# === JDK 17: Forge Modern & NeoForge 1.20.1 ===
$jdk17Platforms = "forge-1.16.5,forge-1.18.2,forge-1.19.2,forge-1.20.1,neoforge-1.20.1"
$jdk17Tasks = @(
    ":platform-forge-1.16.5:build", ":platform-forge-1.18.2:build",
    ":platform-forge-1.19.2:build", ":platform-forge-1.20.1:build",
    ":platform-neoforge-1.20.1:build"
)
$failed += Invoke-TargetBuild "jdk17-forge-modern" $j17 $jdk17Platforms $jdk17Tasks

# === JDK 8: Legacy 1.8.9 & 1.12.2 (Parallel) ===
Write-Host "==== legacy-parallel (1.8.9 & 1.12.2) ===="
$swLegacy = [Diagnostics.Stopwatch]::StartNew()
$job89 = Start-Job -ScriptBlock {
    param($path, $j8, $logFile)
    $env:JAVA_HOME = $j8
    Push-Location $path
    & ".\gradlew.bat" build *> $logFile
    $c = $LASTEXITCODE
    Pop-Location
    return $c
} -ArgumentList "$root\platforms\forge\legacy\1.8.9", $j8, (Join-Path $logDir "legacy-1.8.9.log")

$job112 = Start-Job -ScriptBlock {
    param($path, $j8, $logFile)
    $env:JAVA_HOME = $j8
    Push-Location $path
    & ".\gradlew.bat" build *> $logFile
    $c = $LASTEXITCODE
    Pop-Location
    return $c
} -ArgumentList "$root\platforms\forge\legacy\1.12.2", $j8, (Join-Path $logDir "legacy-1.12.2.log")

$null = Wait-Job $job89, $job112
$code89 = Receive-Job $job89
$code112 = Receive-Job $job112
Remove-Job $job89, $job112
$swLegacy.Stop()

Add-Content -Path (Join-Path $logDir "summary.txt") -Value ("{0,-28} exit={1} {2:n1}s" -f "legacy-1.8.9", $code89, $swLegacy.Elapsed.TotalSeconds)
Add-Content -Path (Join-Path $logDir "summary.txt") -Value ("{0,-28} exit={1} {2:n1}s" -f "legacy-1.12.2", $code112, $swLegacy.Elapsed.TotalSeconds)
Write-Host ("legacy parallel completed in {0:n1}s (1.8.9 exit={1}, 1.12.2 exit={2})" -f $swLegacy.Elapsed.TotalSeconds, $code89, $code112)

if ($code89 -ne 0) { $failed += 1 }
if ($code112 -ne 0) { $failed += 1 }

Write-Host "total failures sum=$failed"
Get-Content (Join-Path $logDir "summary.txt")
if ($failed -ne 0) { exit 1 }
exit 0
