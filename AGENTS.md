# 專案代理規則（DSH / AGENTS.md）

本檔是本倉庫的代理工作指示，DSH 會在每次請求時自動載入。與本檔衝突的既有文件、腳本註解或習慣，
一律以本檔為準。

## 絕對規則：本機不得編譯、建置或執行測試

**唯一允許的編譯途徑是 GitHub Actions。** 在使用者這台 Windows 電腦上，不得執行任何會編譯、
建置、打包或測試本專案的指令。這條規則沒有「順手跑一下」的例外。

| 禁止類別 | 具體例子 |
| --- | --- |
| 任何 Gradle 呼叫 | `./gradlew build`、`gradlew.bat :platform-fabric-1.21.x:build`、`./gradlew check`、`./gradlew test`、`./gradlew --dry-run`、`./gradlew projects`、`./gradlew tasks` |
| 專案建置腳本 | `scripts/build_all.ps1`、`scripts/build_single.ps1`、`scripts/tools/_build_1_3_11_beta.ps1` |
| 直接呼叫編譯器／執行環境 | `javac`、`java -jar`、任何 JDK 工具鏈指令（含 `C:\Program Files\Eclipse Adoptium\...` 下的 `java.exe`） |
| 本機測試 | `python scripts/test_verify_release_jars.py`、`python scripts/test_prepare_release_assets.py`、`python -m unittest`、`pytest` |
| 發布資產處理 | `python scripts/prepare_release_assets.py`、`python scripts/verify_release_jars.py` |
| 環境調校與其他 | `scripts/setup_defender_exclusions.bat`、手動維護 `.gradle` 快取、建立新的本機建置腳本 |

原因（不要嘗試繞過）：

- 本機 Gradle 建置會吃滿記憶體與 CPU，過去已造成 JVM 崩潰（倉庫根目錄的 `hs_err_pid*.log` 即為證據）。
- 專案有 33 個以上的平台目標與多套 JDK（8／17／21／25），本機環境不保證與 CI 一致。
- GitHub Actions 是唯一可信的建置與驗證來源；本機能編過不代表 CI 會過，反之亦然。

### 本機允許做的事

- 讀取、搜尋、理解程式碼與文件（`read`、`grep`、`glob`）。
- 編輯任何檔案，包括 Gradle 設定、Java 原始碼、文件與 `.github/workflows/`。
- Git 操作：`git status`、`git diff`、`git log`、`git switch`、`git add`、`git commit`、`git push`、`git fetch`。
- 以 `gh` 查詢、觸發與觀察 GitHub Actions：`gh workflow run`、`gh run list`、`gh run view --log-failed`、`gh run download`。
- 不執行專案程式碼的靜態檢查，例如用 Python 讀取 workflow YAML 驗證語法、檢查 JSON 格式。

### 沒有例外

若認為某項變更非得在本機編譯才能確認，**停下來詢問使用者**，不要自行執行。
使用者若明確、逐次地授權某一次本機建置，該次授權僅適用於那一次，不會改變本規則。

## 唯一建置途徑：GitHub Actions

| Workflow | 檔案 | 觸發條件 | 用途 |
| --- | --- | --- | --- |
| Build | `.github/workflows/build.yml` | PR、push `main`、`workflow_dispatch` | 核心檢查、17 個平台目標、舊版 Fabric／Forge、發布資產驗證 |
| Publish release downloads | `.github/workflows/publish-release.yml` | push `main`（限特定路徑）、`workflow_dispatch` | 驗證 `downloads/<version>` 並建立或更新 GitHub Release |

Git 遠端：

- `origin` = `TWJohnJohn20116/mc-auto-translation-tool`（本機推送目標）
- `upstream` = `wuxiangdan96-byte/mc-auto-translation-tool`（原作者，**唯讀，永不推送**）

**推送安全（務必遵守）**：本機 `main` 的 fetch／pull 來源是 `upstream/main`（原作者），但
`branch.main.pushremote=origin`，所以 push 會到 `origin`。儘管如此，一律明確指定遠端：
`git push origin <branch>` 或 `git push -u origin <branch>`；永遠不要 `git push upstream`、
不要對 upstream 開 PR、不要 force push。

若 push 出現 `could not read Username for 'https://github.com'`，代表 git 沒有可用的憑證 helper。
本機全域設定指向 Git Credential Manager，它在這裡會先被呼叫並崩潰，讓 git 直接中止（stderr 會出現
一串 `*.dll` 位址）。用單次命令把 helper 換成已登入的 `gh`，不要改動全域 git 設定：

```bash
git -c 'credential.helper=' -c 'credential.helper=!gh auth git-credential' push -u origin <branch>
```

不要用 `git config --local credential.helper ""` 這種寫法：PowerShell 會把空字串參數吃掉，指令
實際上只會讀取設定而不會寫入。

`gh` 目前解析到的倉庫是 `TWJohnJohn20116/mc-auto-translation-tool`，但這個解析不是保證。為了避免
誤操作原作者倉庫，`gh` 指令一律加上 `-R TWJohnJohn20116/mc-auto-translation-tool`，或先執行一次
`gh repo set-default TWJohnJohn20116/mc-auto-translation-tool` 再繼續。

### 驗證一個變更的標準流程

1. 建立分支並提交：`git switch -c fix/xxx`、`git add -A`、`git commit`。
2. 推送並開 PR（PR 會自動觸發 Build）：`git push -u origin fix/xxx`、`gh pr create --fill`。
3. 或針對分支手動觸發（`workflow_dispatch` 需要該 workflow 已存在於預設分支 `main`）：

   ```bash
   gh workflow run build.yml --ref fix/xxx -f targets=fabric-1.21.x
   ```

4. 觀察結果：`gh run watch <run-id>`；失敗時 `gh run view <run-id> --log-failed`。
5. 需要產物時向 CI 取，不要在本機自己編：

   ```bash
   gh run download <run-id> -n platform-fabric-1.21.x -D build/ci-artifacts
   ```

   - 平台目標 → artifact 名稱 `platform-<target>`，內容為 `platforms/**/build/libs/*.jar`
   - 舊版目標 → artifact 名稱 `legacy-platforms`
   - 可直接安裝的正式版 JAR → artifact 名稱 `release-assets`（`build/release-assets/*.jar` 與 `SHA256SUMS.txt`）

6. 確認你要的 job 真的有跑，而不是被略過：`gh run view <run-id> --json jobs` 中該 job 的
   `conclusion` 不能是 `skipped`。

### `workflow_dispatch` 的 `targets` 輸入

- 留空 → 執行完整矩陣，等同 push `main`。
- 逗號分隔的平台目標 → 只執行符合的 `platform-build` 項目，例如 `fabric-1.21.x,forge-1.21.11`。
- 額外關鍵字：`legacy`（舊版 Fabric／Forge 建置）、`release`（發布資產驗證）。
- 目標名稱必須與 `build.yml` 的 matrix 完全一致。名稱打錯時該項目會被略過，而整體仍顯示成功，
  因此一定要用上一步的 `--json jobs` 確認。

### 平台目標名稱（`build.yml` matrix 的值）

`fabric-1.16.x`、`fabric-1.17-1.18.x`、`fabric-1.19.x`、`fabric-1.20.x`、`fabric-1.21.x`、
`fabric-1.21.4-1.21.5`、`fabric-26.x`、`neoforge-1.20.1`、`forge-1.21.1`、`forge-1.21.7`、
`forge-1.21.9`、`forge-1.21.11`、`forge-26.1.1`、`forge-26.2`、`neoforge-1.21.1`、`neoforge-1.21.3`、
`neoforge-1.21.11`

`legacy-build` 另外涵蓋：`fabric-1.16.5`、`fabric-1.19.2`、`fabric-1.20.1`、`forge-1.16.5`、
`forge-1.18.2`、`forge-1.19.2`、`forge-1.20.1`。

## 修改 CI 的規則

- 不要降低既有檢查強度：`check`、`verifyBundle`、`verifyLoaderSelection`、`verifyPreparedReleaseAssets`
  與 `scripts/verify_release_jars.py` 的驗證都必須保留。
- 新增或調整平台目標時，必須同步三處：`settings.gradle` 的 `platformProjects`、`build.yml` 的 matrix、
  `gradle.properties` 的版本變數。
- 不要為了讓 CI 變綠而刪除測試或放寬驗證；要修的是程式碼。
- GitHub 運算式**沒有** `replace`、`trim` 這類字串函式；可用的只有 `contains`、`startsWith`、
  `endsWith`、`format`、`join`、`toJSON`、`fromJSON`、`hashFiles` 與狀態函式。需要字串正規化時，
  放到 `run:` 步驟用 bash 做（例如 `plan` job 用 `tr -d '[:space:]'` 剝除空白）。用到不存在的函式會讓
  整份 workflow 解析失敗：run 名稱會變成檔案路徑 `.github/workflows/build.yml`、`jobs` 數為 0。
  遇到這種症狀時，去 run 頁面的 HTML 找 `Invalid workflow file` 的訊息，裡面有行號與原因。
- 修改 workflow 後，仍需以 GitHub Actions 的實際執行結果為準，不得以「看起來沒問題」結案。

## 其他專案慣例

- 版本號來源是 `gradle.properties` 的 `mod_version`；發布目錄為 `downloads/<mod_version>`。
- 文件為三語：`docs/Zh-cn`（主）、`docs/Zh-tw`、`docs/en`；改一份時要考慮同步其餘兩份。
- 不要提交 `build/` 產物或 `hs_err_pid*.log`；也不要為了方便而修改 `.gitignore` 讓產物進版控。
- 回報時附上對應的 GitHub Actions run 連結。尚未推送、尚未經 CI 驗證的變更，必須明確說明
  「尚未經 CI 驗證」，不得描述成「已通過」。
