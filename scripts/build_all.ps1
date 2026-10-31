# Builds all Minecraft platforms (Fabric, Forge, NeoForge, Legacy) with multi-worker parallelism and build cache.
#
# 介面說明用；AGENTS.md 禁止在本機編譯／建置／測試，唯一可信的建置途徑是 GitHub Actions。
#
# 目標清單的權威來源是 .github/workflows/prepare-release.yml 的 TARGETS_JSON（48 個發布目標）。
# 下面的分組只是把那 48 個目標依 JDK 分群後的結果：
#   JDK 25 -> 26.x（fabric-26.x bundle + 4 forge + 4 neoforge）
#   JDK 21 -> 8 個 fabric bundle／1.13–1.21、其餘 forge／neoforge，以及 forge modern 1.16.5–1.20.1
#             與 neoforge-1.20.1（根 build.gradle 的 subprojects 對它們要求的 toolchain 也是 21）
#   JDK 8  -> platforms/forge/legacy 的 1.8.9、1.12.2（各自獨立 Gradle 建置）
# 改動任一份清單時，prepare-release.yml、settings.gradle 與這裡必須一起改。
$ErrorActionPreference = "Continue"

# 專案根目錄與快取目錄由腳本自身位置推導（本來硬編 D:\Code\... 與 D:\Gradle\cache，
# 換一台機器就失效）。
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $root "gradlew.bat"))) {
    throw "找不到 $root\gradlew.bat；請確認這支腳本位於 <repo>\scripts\ 之下。"
}
if (-not $env:GRADLE_USER_HOME) {
    $env:GRADLE_USER_HOME = Join-Path $root "build\gradle-user-home"
}
$logDir = Join-Path $root "build\release-logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Remove-Item (Join-Path $logDir "summary.txt") -ErrorAction SilentlyContinue
$manifest = "-Ploom_version_manifests=https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
Set-Location $root

# JDK 也改成「先看 JAVA_HOME_<major>_X64，再從 Adoptium／ForgeGradle 的 Mavenizer 快取找」，
# 不再寫死帶 build 號的目錄名。解析延遲到每個分組之前才做，缺 JDK 只會影響需要它的那一組。
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
    # $LASTEXITCODE 只反映上一個「原生」命令，不會被 PowerShell 重置；先設成 -1，
    # 這樣 gradlew 根本沒被執行時（路徑錯、被執行原則擋下）就不會沿用上一次成功建置的 0。
    $global:LASTEXITCODE = -1
    & "$root\gradlew.bat" @taskArgs *> $log
    $code = $LASTEXITCODE
    if ($null -eq $code -or $code -lt 0) {
        throw "gradlew 沒有被執行（$Name），看不到離開碼；詳細輸出見 $log"
    }
    $sw.Stop()
    Add-Content -Path (Join-Path $logDir "summary.txt") -Value ("{0,-28} exit={1} {2:n1}s" -f $Name, $code, $sw.Elapsed.TotalSeconds)
    Write-Host ("{0} exit={1} {2:n1}s" -f $Name, $code, $sw.Elapsed.TotalSeconds)
    return $code
}

$failed = 0

# === JDK 25: 26.x platforms (Fabric + Forge + NeoForge) ===
$j25 = Resolve-Jdk 25
$jdk25Platforms = "fabric-26.x,forge-26.1,forge-26.1.1,forge-26.1.2,forge-26.2," +
    "neoforge-26.1,neoforge-26.1.1,neoforge-26.1.2,neoforge-26.2"
$jdk25Tasks = @(
    ":platform-fabric-26.x:build",
    ":platform-forge-26.1:build",
    ":platform-forge-26.1.1:build",
    ":platform-forge-26.1.2:build",
    ":platform-forge-26.2:build",
    ":platform-neoforge-26.1:build",
    ":platform-neoforge-26.1.1:build",
    ":platform-neoforge-26.1.2:build",
    ":platform-neoforge-26.2:build"
)
$failed += Invoke-TargetBuild "jdk25-combined" $j25 $jdk25Platforms $jdk25Tasks

# === JDK 21: 1.13 through 1.21 platforms (Fabric + Forge + NeoForge merged) ===
$j21 = Resolve-Jdk 21
# fabric-1.16.x is the bundle project, which is what prepare-release.yml ships; building
# platform-fabric-1.16.5 produced a single-version JAR that _assemble_* then treated as the bundle.
$jdk21Platforms = "fabric-1.13.x,fabric-1.14-1.15.x,fabric-1.16.x,fabric-1.17-1.18.x,fabric-1.19.x,fabric-1.20.x,fabric-1.21.x," +
    "forge-1.21,forge-1.21.1,forge-1.21.3,forge-1.21.4,forge-1.21.5,forge-1.21.6,forge-1.21.7,forge-1.21.8,forge-1.21.9,forge-1.21.10,forge-1.21.11," +
    "neoforge-1.20.4,neoforge-1.20.6,neoforge-1.21,neoforge-1.21.1,neoforge-1.21.2,neoforge-1.21.3," +
    "neoforge-1.21.4,neoforge-1.21.5,neoforge-1.21.6,neoforge-1.21.7,neoforge-1.21.8,neoforge-1.21.9," +
    "neoforge-1.21.10,neoforge-1.21.11"
$jdk21Tasks = @(
    ":platform-fabric-1.13.x:build", ":platform-fabric-1.14-1.15.x:build", ":platform-fabric-1.16.x:build",
    ":platform-fabric-1.17-1.18.x:build", ":platform-fabric-1.19.x:build", ":platform-fabric-1.20.x:build",
    ":platform-fabric-1.21.x:build",
    ":platform-forge-1.21:build", ":platform-forge-1.21.1:build", ":platform-forge-1.21.3:build",
    ":platform-forge-1.21.4:build", ":platform-forge-1.21.5:build", ":platform-forge-1.21.6:build",
    ":platform-forge-1.21.7:build", ":platform-forge-1.21.8:build", ":platform-forge-1.21.9:build",
    ":platform-forge-1.21.10:build", ":platform-forge-1.21.11:build",
    ":platform-neoforge-1.20.4:build", ":platform-neoforge-1.20.6:build", ":platform-neoforge-1.21:build",
    ":platform-neoforge-1.21.1:build", ":platform-neoforge-1.21.2:build", ":platform-neoforge-1.21.3:build",
    ":platform-neoforge-1.21.4:build", ":platform-neoforge-1.21.5:build", ":platform-neoforge-1.21.6:build",
    ":platform-neoforge-1.21.7:build", ":platform-neoforge-1.21.8:build", ":platform-neoforge-1.21.9:build",
    ":platform-neoforge-1.21.10:build", ":platform-neoforge-1.21.11:build"
)
$failed += Invoke-TargetBuild "jdk21-combined" $j21 $jdk21Platforms $jdk21Tasks

# === JDK 21: Forge Modern & NeoForge 1.20.1 ===
# 這一組先前用 JDK 17，但根 build.gradle 的 subprojects 對 platform-forge-1.16.5／1.18.2／1.19.2／
# 1.20.1 與 platform-neoforge-1.20.1 要求的 toolchain 是 21（只有 26.x 的前綴是 25），
# options.release 才負責 8／17 的位元碼目標。用 17 等於讓 Gradle 去找一個 21 的工具鏈。
$j21Modern = Resolve-Jdk 21
$jdk21ModernPlatforms = "forge-1.16.5,forge-1.18.2,forge-1.19.2,forge-1.20.1,neoforge-1.20.1"
$jdk21ModernTasks = @(
    ":platform-forge-1.16.5:build", ":platform-forge-1.18.2:build",
    ":platform-forge-1.19.2:build", ":platform-forge-1.20.1:build",
    ":platform-neoforge-1.20.1:build"
)
$failed += Invoke-TargetBuild "jdk21-forge-modern" $j21Modern $jdk21ModernPlatforms $jdk21ModernTasks

# === JDK 8: Legacy 1.8.9 & 1.12.2 (Parallel) ===
$j8 = Resolve-Jdk 8
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
} -ArgumentList (Join-Path $root "platforms\forge\legacy\1.8.9"), $j8, (Join-Path $logDir "legacy-1.8.9.log")

$job112 = Start-Job -ScriptBlock {
    param($path, $j8, $logFile)
    $env:JAVA_HOME = $j8
    Push-Location $path
    & ".\gradlew.bat" build *> $logFile
    $c = $LASTEXITCODE
    Pop-Location
    return $c
} -ArgumentList (Join-Path $root "platforms\forge\legacy\1.12.2"), $j8, (Join-Path $logDir "legacy-1.12.2.log")

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
