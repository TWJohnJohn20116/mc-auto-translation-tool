# 工具腳本索引 (Scripts Overview)

此目錄包含專案的建置、測試、發布與環境調校腳本。

**這些指令只供 GitHub Actions 與已授權的環境使用；本機不得執行建置或測試（見 `AGENTS.md`）。**
下表的範例是介面說明，版本號與路徑請按當下的 `gradle.properties` 與 `downloads/` 目錄替換。

## 常用工具

| 腳本名稱 | 語言 | 用途說明 | 使用範例 |
| :--- | :--- | :--- | :--- |
| **`build_all.ps1`** | PowerShell | 全平台全量構建（利用 8 Worker + 常駐 Daemon + Build Cache 並行編譯）。目標分組對齊 `prepare-release.yml` 的 48 個發布目標；根目錄與 Gradle 快取由腳本位置與 `GRADLE_USER_HOME` 推導 | `.\scripts\build_all.ps1` |
| **`build_single.ps1`** | PowerShell | 單一模組靶向編譯（自動切換對應 JDK）。實際耗時依目標與快取狀態而定，沒有固定秒數 | `.\scripts\build_single.ps1 fabric-1.21.4` |
| **`prepare_release_assets.py`** | Python 3 | 將各平台產物組裝為發布用 JAR（含超壓縮 `fabric-all.jar` 與 SHA256 驗證碼） | `python scripts\prepare_release_assets.py --release-dir downloads\1.4 --output-dir build\release-assets --version 1.4` |
| **`verify_release_jars.py`** | Python 3 | 檢驗發布資產的位元碼完整性與 Fabric Loader 解壓正確性 | `python scripts\verify_release_jars.py --checksum-file downloads\1.4\SHA256SUMS.txt --require-complete-checksums downloads\1.4\*.jar` |
| **`setup_defender_exclusions.bat`**| Batch / PS | 一鍵將專案目錄、Gradle 快取與 `java.exe` 加入 Windows Defender 排除名單。專案目錄由腳本位置推導 | 右鍵 ->「以系統管理員身分執行」 |

## 內部工具 (`tools/`)

`scripts/tools/` 存放歷史版本發布組裝腳本與離線開發時的快取種子工具（如 Mojang POMs 與 Loom 鏡像快取）。平時開發無需手動調用。

- `_assemble_1_3_11_beta.py`：手動版發布組裝流程的參考實作。目標清單從
  `prepare-release.yml` 的 `TARGETS_JSON` 讀出（48 個，並用 `settings.gradle` 交叉驗證），
  先寫進 `downloads/<version>.staging` 驗證無誤後才替換；`downloads/<version>` 已存在且不是這次
  產生的會**拒絕執行**，要覆蓋得明確加 `--force`。
- `_build_1_3_11_beta.ps1`：已收斂為 `build_all.ps1` 的包裝，不再維護自己的（過期的 33 目標）清單。
- `_seed_forge_1182_cache.py`、`_seed_loom_jars.py`、`_seed_loom_versions.py`、`_gen_mojang_poms.py`：
  離線快取種子。快取位置集中在 `_cache_paths.py`（`GRADLE_USER_HOME` → `~/.gradle`），不再硬編 `D:\`；
  `APPDATA` 無法定位時只寫 Gradle 快取，不會把 library 寫進當前工作目錄。`_gen_mojang_poms.py`
  缺目錄或一個 POM 都沒補到都以非零碼結束（原本會靜默回報「wrote 0 poms」而成功）。

## CI 共用腳本 (`ci/`)

- `ci/write-build-summary.sh`：`build.yml` 與 `legacy-targets.yml` 共 7 個建置 job 的繁中摘要，
  只留這一份（參數走 `TARGET`／`OUTCOME`／`ARTIFACT` 三個 env），`lint` job 會用 `bash -n` 檢查語法。
