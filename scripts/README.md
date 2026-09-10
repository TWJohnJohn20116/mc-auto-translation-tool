# 工具腳本索引 (Scripts Overview)

此目錄包含專案的建置、測試、發布與環境調校腳本。

## 常用工具

| 腳本名稱 | 語言 | 用途說明 | 使用範例 |
| :--- | :--- | :--- | :--- |
| **`build_all.ps1`** | PowerShell | 全平台全量構建（利用 8 Worker + 常駐 Daemon + Build Cache 並行編譯） | `.\scripts\build_all.ps1` |
| **`build_single.ps1`** | PowerShell | 單一模組快速靶向編譯（自動切換對應 JDK，8 秒完成） | `.\scripts\build_single.ps1 fabric-1.21.4` |
| **`prepare_release_assets.py`** | Python 3 | 將各平台產物組裝為發布用 JAR（含超壓縮 `fabric-all.jar` 與 SHA256 驗證碼） | `python scripts\prepare_release_assets.py --release-dir downloads\1.3.11-beta.2 --output-dir build\release-assets --version 1.3.11-beta.2` |
| **`verify_release_jars.py`** | Python 3 | 檢驗發布資產的位元碼完整性與 Fabric Loader 解壓正確性 | `python scripts\verify_release_jars.py` |
| **`setup_defender_exclusions.bat`**| Batch / PS | 一鍵將專案目錄、Gradle 快取與 `java.exe` 加入 Windows Defender 排除名單 | 右鍵 ->「以系統管理員身分執行」 |

## 內部工具 (`tools/`)

`scripts/tools/` 存放歷史版本發布組裝腳本與離線開發時的快取種子工具（如 Mojang POMs 與 Loom 鏡像快取）。平時開發無需手動調用。
