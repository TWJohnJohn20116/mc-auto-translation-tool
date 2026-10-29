# 1.3.11-beta.1 時代的手動建置入口，現在只是 build_all.ps1 的包裝。
#
# 它原本自帶一份目標清單，而且已經和 build_all.ps1 分叉：fabric 用的是單一版本的
# fabric-1.16.5（真正的發布目標是 bundle fabric-1.16.x）、NeoForge 只有 3 個而不是 14 個、
# manifest 走 bmclapi 鏡像而不是 piston-meta。照它建出來的產物會被 _assemble_*.py 當成
# 完整的發布資產，於是「本機全綠」與 CI 的目標集合不同（33 對 48）。
#
# 現在目標清單只剩一個來源：scripts\build_all.ps1（對齊 .github/workflows/prepare-release.yml
# 的 48 個目標）。AGENTS.md 禁止在本機執行建置腳本，這支保留給需要重現舊流程的人參考。
$ErrorActionPreference = "Stop"

$buildAll = Join-Path (Split-Path -Parent $PSScriptRoot) "build_all.ps1"
if (-not (Test-Path $buildAll)) {
    throw "找不到 $buildAll；這支腳本應位於 <repo>\scripts\tools\ 之下。"
}

Write-Host "此腳本已收斂為 build_all.ps1 的包裝（48 個目標），不再維護自己的清單。" -ForegroundColor Yellow
& $buildAll @args

# build_all.ps1 以 exit 0/1 結束；被 & 呼叫時不保證 $LASTEXITCODE 一定有值，
# 因此退回 $?（上一個指令是否成功）。
$code = $LASTEXITCODE
if ($null -eq $code) {
    $code = if ($?) { 0 } else { 1 }
}
exit $code
