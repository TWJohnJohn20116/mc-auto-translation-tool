param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$Target
)

# 介面說明用；AGENTS.md 禁止在本機編譯／建置／測試，唯一可信的建置途徑是 GitHub Actions。
$ErrorActionPreference = "Stop"

# 專案根目錄與快取目錄由腳本自身位置推導（原本硬編 D:\Code\... 與 D:\Gradle\cache）。
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $root "gradlew.bat"))) {
    throw "找不到 $root\gradlew.bat；請確認這支腳本位於 <repo>\scripts\ 之下。"
}
if (-not $env:GRADLE_USER_HOME) {
    $env:GRADLE_USER_HOME = Join-Path $root "build\gradle-user-home"
}
$manifest = "-Ploom_version_manifests=https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
Set-Location $root

# JDK 先看 JAVA_HOME_<major>_X64，再從 Adoptium／ForgeGradle 的 Mavenizer 快取找；
# 不再寫死帶 build 號的目錄名。
# 解析改成延遲：只解析這個目標真的要用來跑 Gradle 的那一個 JDK。先前在建置任何東西之前就
# 先解析 25／21／17／8 四個，缺任何一個無關的 JDK 就直接 throw，於是「在一台只裝 JDK 21 的
# 機器上驗證單一目標」在設計上就不可能。
$jdkSearchRoots = @(
    (Join-Path $env:ProgramFiles "Eclipse Adoptium"),
    (Join-Path $env:GRADLE_USER_HOME "caches\minecraftforge\forgegradle\mavenizer\caches")
)
function Resolve-Jdk {
    param([int]$Major)
    $candidates = @()
    $fromEnv = [Environment]::GetEnvironmentVariable("JAVA_HOME_${Major}_X64")
    if ($fromEnv) { $candidates += $fromEnv }
    foreach ($searchRoot in $jdkSearchRoots) {
        if ($searchRoot -and (Test-Path $searchRoot)) {
            $candidates += @(Get-ChildItem -Path $searchRoot -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match "(^|-)jdk-?$Major[._]" } |
                Sort-Object Name -Descending |
                ForEach-Object { $_.FullName })
        }
    }
    $resolved = $candidates |
        Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\java.exe")) } |
        Select-Object -First 1
    if (-not $resolved) {
        throw "找不到 JDK $Major；請設定 JAVA_HOME_${Major}_X64，或把它裝在 $($jdkSearchRoots -join ' / ')。"
    }
    return $resolved
}

# Normalize target name
$cleanTarget = $Target.Trim().ToLower()
if ($cleanTarget.StartsWith(":")) { $cleanTarget = $cleanTarget.Substring(1) }
if ($cleanTarget.EndsWith(":build")) { $cleanTarget = $cleanTarget.Substring(0, $cleanTarget.Length - 6) }
if ($cleanTarget.StartsWith("platform-")) { $cleanTarget = $cleanTarget.Substring(9) }

# Handle Legacy 1.8.9 / 1.12.2
# The published target names are `forge-legacy-1.8.9` / `forge-legacy-1.12.2` (see AGENTS.md and
# legacy-targets.yml); passing those used to fall through to the modern branch below and ask Gradle
# for a project that does not exist, because these two are standalone builds.
if ($cleanTarget -match '^(forge-)?legacy-1\.8\.9$' -or $cleanTarget -eq "1.8.9" -or $cleanTarget -eq "forge-1.8.9") {
    $env:JAVA_HOME = Resolve-Jdk 8
    Write-Host "Building Legacy Forge 1.8.9 using JDK 8..." -ForegroundColor Cyan
    Push-Location (Join-Path $root "platforms\forge\legacy\1.8.9")
    & ".\gradlew.bat" build
    Pop-Location
    exit $LASTEXITCODE
}
if ($cleanTarget -match '^(forge-)?legacy-1\.12\.2$' -or $cleanTarget -eq "1.12.2" -or $cleanTarget -eq "forge-1.12.2") {
    $env:JAVA_HOME = Resolve-Jdk 8
    Write-Host "Building Legacy Forge 1.12.2 using JDK 8..." -ForegroundColor Cyan
    Push-Location (Join-Path $root "platforms\forge\legacy\1.12.2")
    & ".\gradlew.bat" build
    Pop-Location
    exit $LASTEXITCODE
}

# Determine JDK for modern platforms
$jdkMajor = 21
$jdkName = "JDK 21"

if ($cleanTarget -match "26\.") {
    $jdkMajor = 25
    $jdkName = "JDK 25"
} elseif ($cleanTarget -match "forge-1\.(16\.5|18\.2|19\.2|20\.1)" -or $cleanTarget -eq "neoforge-1.20.1") {
    $jdkMajor = 17
    $jdkName = "JDK 17"
}

$selectedJdk = Resolve-Jdk $jdkMajor

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
