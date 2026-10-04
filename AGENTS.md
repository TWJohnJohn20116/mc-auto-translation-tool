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
| 主要建置 | `.github/workflows/build.yml` | PR、push `main`、`workflow_dispatch` | workflow 靜態檢查（actionlint + shellcheck）、核心檢查、17 個平台目標、7 個舊版目標（各自獨立 job 平行跑）、發布資產驗證，最後由 `CI 總結` 彙總。這是 PR 必經路徑，牆鐘約 8 分 |
| 舊版目標 | `.github/workflows/legacy-targets.yml` | 每週排程（週一 03:17 UTC）、`workflow_dispatch` | 4 個 Ornithe／舊版 Fabric bundle（1.0–1.15）與 2 個獨立 Forge 1.8.9／1.12.2 建置。這些要 remap／decompile 多個舊版 Minecraft，刻意不放在 PR 路徑 |
| 發布下載資產 | `.github/workflows/publish-release.yml` | push `main`（限特定路徑）、`workflow_dispatch` | 驗證 `downloads/<version>` 並建立或更新 GitHub Release |

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
2. 推送並開 PR（PR 會自動觸發主要建置）：`git push -u origin fix/xxx`、`gh pr create --fill`。
3. 或針對分支手動觸發（`workflow_dispatch` 需要該 workflow 已存在於預設分支 `main`）：

   ```bash
   gh workflow run build.yml --ref fix/xxx -f targets=fabric-1.21.x
   ```

4. 觀察結果：`gh run watch <run-id>`；失敗時 `gh run view <run-id> --log-failed`。
5. 需要產物時向 CI 取，不要在本機自己編：

   ```bash
   gh run download <run-id> -n platform-fabric-1.21.x -D build/ci-artifacts
   ```

   - 平台目標 → artifact 名稱 `platform-<target>`（內容 `platforms/**/build/libs/*.jar`）
   - 舊版目標 1.16.5–1.20.1 → artifact 名稱 `legacy-<target>`（同時涵蓋 Fabric 的 `build/libs` 與
     Forge 的 `build/release`）
   - 舊版 Fabric bundle 1.0–1.15 → artifact 名稱 `legacy-fabric-<target>`
   - 獨立 Forge 1.8.9／1.12.2 → artifact 名稱 `forge-legacy-1.8.9`、`forge-legacy-1.12.2`
   - 可直接安裝的正式版 JAR → artifact 名稱 `release-assets`（`build/release-assets/*.jar` 與 `SHA256SUMS.txt`）

6. 確認你要的 job 真的有跑，而不是被略過：`gh run view <run-id> --json jobs` 中該 job 的
   `conclusion` 不能是 `skipped`。分支保護要求的檢查是單一的 **`CI 總結`**（彙總 job）：只有全部
   job 都 `success` 或刻意 `skipped` 才會通過。

### 分支保護（`main`）

`main` 已設定分支保護，唯一必要的檢查是 **`CI 總結`**（彙總 job）。目前設定：

- 不要求 review（單人維護，無法核准自己的 PR）、不要求分支先與 `main` 同步（`strict: false`）。
- `enforce_admins: false`：倉庫擁有者仍可直接推送 `main` 做緊急修正，但其他人的 PR 會被擋下。
- 禁止 force push、禁止刪除 `main`。

要調整時用
`gh api -X PUT repos/<owner>/<repo>/branches/main/protection --input <json 檔>`。在 Windows PowerShell
5.1 產生那個 JSON 檔必須用
`[System.IO.File]::WriteAllText($path, $body, [System.Text.UTF8Encoding]::new($false))`：`Set-Content -Encoding utf8`
會寫入 BOM，GitHub 會回 `400 Problems parsing JSON`。檢查名稱含中文時尤其要用這個寫法，
避免命令列參數被主控台編碼轉壞。

### `workflow_dispatch` 的 `targets` 輸入

`主要建置` 與 `舊版目標` 兩個 workflow 都接受 `targets`：

- 留空 → 執行該 workflow 的全部目標（排程觸發亦然）。
- 逗號分隔的目標名稱 → 只執行符合的 job，例如 `fabric-1.21.x,forge-1.21.11`。
- `主要建置` 額外關鍵字：`legacy`（7 個 1.16.5–1.20.1 舊版目標）、`release`（發布資產驗證）。
- 目標名稱必須與該 workflow 的 matrix 完全一致。名稱打錯時該項目會被略過，而整體仍顯示成功，
  因此一定要用 `gh run view <run-id> --json jobs` 確認 `conclusion` 不是 `skipped`。

### 目標名稱

`build.yml` → `platform-build`（17 個，PR 必經）：

`fabric-1.16.x`、`fabric-1.17-1.18.x`、`fabric-1.19.x`、`fabric-1.20.x`、`fabric-1.21.x`、
`fabric-1.21.4-1.21.5`、`fabric-26.x`、`neoforge-1.20.1`、`forge-1.21.1`、`forge-1.21.7`、
`forge-1.21.9`、`forge-1.21.11`、`forge-26.1.1`、`forge-26.2`、`neoforge-1.21.1`、`neoforge-1.21.3`、
`neoforge-1.21.11`

`build.yml` → `legacy-build`（7 個，各自獨立平行 job；關鍵字 `legacy` 可全選）：

`fabric-1.16.5`、`fabric-1.19.2`、`fabric-1.20.1`、`forge-1.16.5`、`forge-1.18.2`、`forge-1.19.2`、
`forge-1.20.1`

`legacy-targets.yml`（排程／手動，不擋 PR）：

`fabric-1.0-1.8.x`、`fabric-1.8-1.12.x`、`fabric-1.13.x`、`fabric-1.14-1.15.x`、`forge-legacy-1.8.9`、
`forge-legacy-1.12.2`（後兩者位於 `platforms/forge/legacy/<版本>/`，是獨立 Gradle 建置，需要 JDK 8）

## 發布（Release）流程

Release 由 `.github/workflows/publish-release.yml` 自動建立，**不要手動上傳資產或手動開 Release**。

1. 更新 `gradle.properties` 的 `mod_version`。
2. 準備 `downloads/<mod_version>/`：**33 個**目標 JAR 加 `SHA256SUMS.txt`。過大的 JAR 可用
   `.jar.part-aa`、`.jar.part-ab`… 分片，workflow 會自動合併；但同一顆 JAR 同時存在完整檔與分片
   會被視為錯誤。
3. 在 `CHANGELOG.md` 加上 `## <mod_version> - <日期>` 區段，workflow 會把它當成 release notes；
   缺少這個區段會讓發布失敗。
4. 推送到 `origin/main`。workflow 只在這些路徑變動時觸發：`downloads/**`、`gradle.properties`、
   `CHANGELOG.md`、`scripts/prepare_release_assets.py`、`scripts/verify_release_jars.py`、
   `publish-release.yml` 自己。
5. workflow 會驗證 33 顆 JAR 與 checksum、縮減成 **16 顆可直接安裝 JAR**（加 `SHA256SUMS.txt` 共
   17 個資產），再建立或更新 `v<mod_version>` Release；版本號含 `-` 者視為 prerelease。

發布前務必先讓主要建置的 `CI 總結` 變綠，且 `downloads/` 內容必須與 `mod_version` 一致，否則
`verify_release_jars.py` 會失敗。

## 修改 CI 的規則

- 不要降低既有檢查強度：`check`、`verifyBundle`、`verifyLoaderSelection`、`verifyPreparedReleaseAssets`
  與 `scripts/verify_release_jars.py` 的驗證都必須保留。
- 新增或調整平台目標時，必須同步四處：`settings.gradle` 的 `platformProjects`、對應 workflow 的 matrix
  （`build.yml` 或 `legacy-targets.yml`）、`gradle.properties` 的版本變數，以及本檔的目標名稱清單。
- 慢的目標不要放進 PR 必經的 `build.yml`：需要 ForgeGradle decompile 或大量舊版 remap 的目標放
  `legacy-targets.yml`。PR 的牆鐘時間由最慢的 job 決定——曾經有一個 job 序列跑 7 個舊版目標，
  讓整體變成 15.9 分；拆成各自獨立的 matrix job 平行跑之後瓶頸才回到單一目標。
  同類目標一律拆成獨立 job，不要串在同一條 Gradle 指令裡。
- 新增 job 時，必須把它加進 `ci-status` 的 `needs`（`build.yml`），否則它不會被彙總、分支保護也擋不住失敗。
- 顯示名稱一律用繁體中文（workflow 名稱、job 名稱、step 名稱），但 **artifact 名稱維持 ASCII**
  （`platform-<target>`、`release-assets` 等），因為 `gh run download -n` 與腳本會用到。
- 建置一律透過 `./.github/actions/gradle-build` 這個 composite action 執行，不要直接寫 `./gradlew`：
  它負責在疑似網路／依賴解析失敗時自動重試（`neoforge-1.20.1` 曾因暫時性 Maven 故障假紅燈一次），
  並讓所有建置的日誌格式一致。要調整重試條件時只改那個檔案，六個呼叫端不用動。
- `lint` job 用 actionlint 檢查所有 workflow，runner 內建的 shellcheck 會一併檢查每個 `run:` 區塊。
  actionlint 的版本與 sha256 都寫死在 job 裡，升級時要一起改，不要改成「抓最新版」。
- `pull_request` 觸發**不可以**加 `paths` 或 `paths-ignore`：只要有一個 PR 不觸發 workflow，必要的
  `CI 總結` 檢查就會永遠停在 waiting，那個 PR 就無法合併。
- action 版本交給 `.github/dependabot.yml` 每週檢查，並用 `groups` 把所有 action 更新合成一個 PR，
  避免五個 PR 各跑一輪 25 個 job 的建置。Dependabot 開的 PR 同樣要等 `CI 總結` 綠燈。
- Job summary 有兩層：`gradle/actions/setup-gradle` 的英文摘要設為 `add-job-summary: on-failure`
  （它沒有語系參數，無法翻譯，只能在失敗時顯示）；成功時的繁中摘要由每個建置 job 最後的
  「寫入建置摘要」步驟用 `$GITHUB_STEP_SUMMARY` 產生，`ci-status` 另外寫一張彙總表。
  該步驟必須是 `if: always()`，且不得讓 job 因此變紅（取值一律加 `|| true`，缺值時顯示「未知」）。
  那段 bash 在每個建置 job 各有一份（GitHub 運算式不支援共用、YAML 錨點在此不建議使用），
  修改時要一起改。
- runner 固定為 `ubuntu-24.04`，不要改回 `ubuntu-latest`：`ubuntu-latest` 將於 2026-10-19 遷移到
  Ubuntu 26，會讓 23 個建置目標同時面對環境突變。要升級時應一次性改版號並用 CI 驗證。
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
