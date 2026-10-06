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
- 專案有 48 個以上的平台目標與多套 JDK（8／17／21／25），本機環境不保證與 CI 一致。
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
| 主要建置 | `.github/workflows/build.yml` | PR、push `main`、`workflow_dispatch` | workflow 靜態檢查（actionlint + shellcheck）、核心檢查、網站檢查（只在 PR 動到 `website/` 時才真的跑）、17 個平台目標、7 個舊版目標（各自獨立 job 平行跑）、發布資產驗證，最後由 `CI 總結` 彙總。這是 PR 必經路徑，牆鐘約 5～6 分（由最慢的 job 決定） |
| 舊版目標 | `.github/workflows/legacy-targets.yml` | 每週排程（週一 03:17 UTC）、`workflow_dispatch` | 4 個 Ornithe／舊版 Fabric bundle（1.3.1–1.15）、2 個獨立 Forge 1.8.9／1.12.2 建置，以及 15 個 NeoForge 目標（1.20.4～26.2）。這些要 remap／decompile 或跑 Minecraft Mavenizer，刻意不放在 PR 路徑 |
| 準備發布資產 | `.github/workflows/prepare-release.yml` | 只有 `workflow_dispatch` | 一鍵準備 `downloads/<mod_version>`：建置 48 個目標、用標準檔名收集、產生 `SHA256SUMS.txt`、跑 `verify_release_jars.py` 驗證，再開 PR。手動觸發，不在 PR 路徑上（一次約 10～20 分鐘牆鐘時間） |
| 發布下載資產 | `.github/workflows/publish-release.yml` | push `main`（限特定路徑）、`workflow_dispatch` | 驗證 `downloads/<version>` 並建立或更新 GitHub Release |

Git 遠端：

- `origin` = `TWJohnJohn20116/mc-auto-translation-tool`（推送目標）
- `upstream` = `wuxiangdan96-byte/mc-auto-translation-tool`：**這不是另一個 repo**，而是同一個 repo
  的舊名稱（GitHub 會把舊 URL 轉址到新名稱）。用
  `git ls-remote origin refs/heads/main` 與 `git ls-remote upstream refs/heads/main` 比對即可驗證：
  兩者回報同一個 commit。所以**不要**用它做任何同步判斷——`git rev-list --count origin/main
  ^upstream/main` 這種算法沒有意義，只會反映本機那個 remote-tracking ref 有多舊。

**推送安全**：一律明確指定遠端 `git push origin <branch>`；不要 force push、不要刪除 `main`。
本機 `branch.main.remote` 指向 `upstream`，但因為兩者是同一個 repo，`git pull` 結果相同；
為了讓指令一看就懂，一律寫 `git pull --ff-only origin main`。

若 push 出現 `could not read Username for 'https://github.com'`，代表 git 沒有可用的憑證 helper。
本機全域設定指向 Git Credential Manager，它在這裡會先被呼叫並崩潰，讓 git 直接中止（stderr 會出現
一串 `*.dll` 位址）。用單次命令把 helper 換成已登入的 `gh`，不要改動全域 git 設定：

```bash
git -c 'credential.helper=' -c 'credential.helper=!gh auth git-credential' push -u origin <branch>
```

不要用 `git config --local credential.helper ""` 這種寫法：PowerShell 會把空字串參數吃掉，指令
實際上只會讀取設定而不會寫入。

`gh` 目前解析到的倉庫是 `TWJohnJohn20116/mc-auto-translation-tool`，但這個解析不是保證。`gh` 指令
一律加上 `-R TWJohnJohn20116/mc-auto-translation-tool`，或先執行一次
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

   **PR 的執行沒有產物**（見「修改 CI 的規則」）：要取產物請用 `workflow_dispatch` 或 push `main`
   的執行。產物保留 **3 天**，過期就取不到。

   - 平台目標 → artifact 名稱 `platform-<target>`（內容 `platforms/**/build/libs/*.jar`）
   - 舊版目標 1.16.5–1.20.1 → artifact 名稱 `legacy-<target>`（同時涵蓋 Fabric 的 `build/libs` 與
     Forge 的 `build/release`）
   - 舊版 Fabric bundle 1.3.1–1.15 → artifact 名稱 `legacy-fabric-<target>`
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

`legacy-targets.yml` → `neoforge-targets`（15 個，排程／手動，不擋 PR）：

`neoforge-1.20.4`、`neoforge-1.20.6`、`neoforge-1.21`、`neoforge-1.21.2`、
`neoforge-1.21.4`、`neoforge-1.21.5`、`neoforge-1.21.6`、`neoforge-1.21.7`、`neoforge-1.21.8`、
`neoforge-1.21.9`、`neoforge-1.21.10`、`neoforge-26.1`、`neoforge-26.1.1`、`neoforge-26.1.2`、
`neoforge-26.2`（1.20.x 與 1.21.x 用 JDK 21，26.x 用 JDK 25。這些目標原本只存在於 `settings.gradle`，
從來沒有被任何 workflow 建置、也沒有發布過）

`neoforge-1.20.2` **不在**清單裡：它的 `net.neoforged:neoforge:20.2.93` 在
`neoFormRuntimeDependenciesNeoForgeClasses` 這個 configuration 解析不到。POM 與 `-universal.jar`
都存在（實測 HTTP 200），所以是 plugin／layout 層的問題，不是版本打錯。

## 發布（Release）流程

Release 由 `.github/workflows/publish-release.yml` 自動建立，**不要手動上傳資產或手動開 Release**。

1. 更新 `gradle.properties` 的 `mod_version`。
2. 準備 `downloads/<mod_version>/`：**48 個**目標 JAR 加 `SHA256SUMS.txt`。這一步現在由
   `.github/workflows/prepare-release.yml` 自動完成：

   ```bash
   gh workflow run prepare-release.yml -R TWJohnJohn20116/mc-auto-translation-tool
   ```

   它會建置 48 個目標、用標準檔名 `MCAutoTranslationTool-<version>-mc<版本>-<載入器>.jar` 收集、
   產生 `SHA256SUMS.txt`、跑 `verify_release_jars.py`，然後開一個 `release/prepare-<version>` PR。
   因為用 `GITHUB_TOKEN` 開的 PR 不會觸發其他 workflow，它會順便手動觸發該分支的建置，讓分支保護
   要求的 `CI 總結` 能產生。只想煙霧測試時用 `-f targets=mc1.21.7-forge,mc1.13.x-fabric`：只指定
   部分目標時不會開 PR、也不會檢查數量是否為 48。

   `downloads/<version>` 會**整個重新產生**（舊檔案與舊分片都不留），而且 fabric 的 JAR 超過
   614400 bytes（600 KiB）會切成 `.jar.part-aa`、`.jar.part-ab`…；`SHA256SUMS.txt` 記的是
   **完整 JAR** 的雜湊，不是分片的雜湊。這段邏輯的參考實作是 `scripts/tools/_assemble_*.py`
   （它同時是手動版的流程）。同一顆 JAR 同時存在完整檔與分片會被驗證器視為錯誤。
3. 在 `CHANGELOG.md` 加上 `## <mod_version> - <日期>` 區段，workflow 會把它當成 release notes；
   缺少這個區段會讓發布失敗。
4. 推送到 `origin/main`。workflow 只在這些路徑變動時觸發：`downloads/**`、`gradle.properties`、
   `CHANGELOG.md`、`scripts/prepare_release_assets.py`、`scripts/verify_release_jars.py`、
   `publish-release.yml` 自己。
5. workflow 會驗證 48 顆 JAR 與 checksum、縮減成 **31 顆可直接安裝 JAR**（加 `SHA256SUMS.txt` 共
   32 個資產），再建立或更新 `v<mod_version>` Release；版本號含 `-` 者視為 prerelease。
   NeoForge **不做**版本範圍合併（同一顆 JAR 塞多個同 `modId` 實作會載入失敗），每個版本各一顆。
6. 更新文件的下載表格：`README.md` 與 `docs/Zh-cn|Zh-tw|en/README.md` 裡的連結是**寫死版本號**的
   （`releases/download/v<版本>/...`），新增或移除目標時四個檔案都要一起改，否則會指向不存在的檔案。
   這一步沒有自動化，漏掉只會在事後被使用者發現。

發布前務必先讓主要建置的 `CI 總結` 變綠，且 `downloads/` 內容必須與 `mod_version` 一致，否則
`verify_release_jars.py` 會失敗。

## 修改 CI 的規則

- `prepare-release.yml` 的 48 個目標必須與 `publish-release.yml` 的 `expected` 陣列完全一致，
  新增或移除目標時兩邊要一起改；每個目標的 `project` 與 `jar_dir` 也要對得上 `settings.gradle`
  （`jar_dir` 是專案目錄，JAR 會在它的 `build/release-assets`、`build/release` 或 `build/libs` 下）。
  收集時是用**版本號精確比對** `*-<version>.jar`，不要改成「目錄裡唯一的 JAR」：本機與快取目錄
  常留有舊版本的 JAR。
- `prepare-release.yml` 用動態 matrix（`fromJSON`）：48 個目標的清單在 `plan` job 裡，只有被要求的
  目標才會開 runner，打錯名稱會直接以 `::error::` 失敗（`build.yml` 的靜態 matrix 遇到打錯的名稱
  只會靜默略過，所以那邊仍要用 `gh run view --json jobs` 確認）。`build.yml` 維持靜態 matrix：它是
  PR 必經路徑、目標也少，不值得為偶爾的手動篩選去改動那條路徑。
- **沒有**對 PR 開啟 Gradle 快取寫入（`cache-read-only: false`），這是刻意的：GitHub 每個倉庫的快取
  上限是 10 GB，讓每個 PR 各寫一份快取會把 `main` 的快取擠掉（LRU），反而讓每次合併後的建置變慢。
  單一 PR 內重複推送所省下的時間不值得這個代價。若確定要開，就在 `setup-gradle` 加
  `cache-read-only: false`，並先確認快取用量沒有逼近上限。
- 不要降低既有檢查強度：`check`、`verifyBundle`、`verifyLoaderSelection`、`verifyPreparedReleaseAssets`
  與 `scripts/verify_release_jars.py` 的驗證都必須保留。
- 新增或調整平台目標時，必須同步四處：`settings.gradle` 的 `platformProjects`、對應 workflow 的 matrix
  （`build.yml` 或 `legacy-targets.yml`）、`gradle.properties` 的版本變數，以及本檔的目標名稱清單。
- **新增 26.x 平台時要同步 `build.gradle` 的 toolchain 清單**：`subprojects` 區塊只對
  `platform-fabric-26.`、`platform-forge-26.`、`platform-neoforge-26.` 開頭的專案給 Java 25
  toolchain，其餘一律 21。漏掉一個前綴，該平台的 `options.release = 25` 就會以
  `release version 25 not supported` 失敗（2026-10-06 就是漏了 `platform-neoforge-26.`，讓 4 個
  NeoForge 26.x 目標從來沒有建置成功過）。
- **新增 NeoForge 目標時要檢查 `neoforge.mods.toml` 的必填欄位**：驗證器對 `mods.toml` 與
  `neoforge.mods.toml` 共用同一條 Forge 驗證路徑，要求 `modLoader="javafml"`。漏掉時建置會成功，
  但在 `prepare-release.yml` 的「用專案的驗證器檢查」那一步以 `missing Forge metadata field` 失敗
  （2026-10-06 的 4 個 26.x toml 就是這樣）。
- 慢的目標不要放進 PR 必經的 `build.yml`：需要 ForgeGradle decompile 或大量舊版 remap 的目標放
  `legacy-targets.yml`。PR 的牆鐘時間由最慢的 job 決定——曾經有一個 job 序列跑 7 個舊版目標，
  讓整體變成 15.9 分；拆成各自獨立的 matrix job 平行跑之後瓶頸才回到單一目標。
  同類目標一律拆成獨立 job，不要串在同一條 Gradle 指令裡。
- **Forge／NeoForge 的建置在 PR 上條件化**：`plan` job 用 `git diff` 判斷這個 PR 有沒有動到
  Forge 相關路徑，結果放在 `forge_changed` 輸出。只有「所有變更都落在 `docs/`、`website/`、
  `downloads/`、`platforms/fabric/`、`*.md`、`LICENSE`、`.gitignore`」時，才會略過那些目標的
  **建置步驟**。這是保守策略：清單以外一律建置。
  - **不要改成 job 層級的 `if:`**：job 被略過會讓 `CI 總結` 的 `needs` 缺一項結果而卡在
    waiting。略過的必須是步驟，job 本身仍要執行並回報成功。
  - 非 PR 觸發（push `main`、排程、`workflow_dispatch`）一律建置全部目標，所以每次合併仍會
    完整驗證一輪；`workflow_dispatch` 明確指定 `targets` 時也一律照做，不受 `forge_changed` 影響。
  - 目的：Forge 家族的 Mavenizer 與 decompile 單一目標就要 3～5 分鐘，PR 只動文件、網站或
    Fabric 時是白白消耗 runner。
- **產物只在非 PR 的執行上傳**：所有 `upload-artifact` 步驟的 `if:` 都帶
  `github.event_name != 'pull_request'`，`retention-days` 一律 **3 天**。PR 是最頻繁的觸發來源，
  產物對審查沒有幫助卻會佔用儲存與頻寬（2026-10-05 之前是 7～30 天）。要取產物就手動觸發，
  或看 push `main` 的執行。
- `downloads/` 只保留目前 `mod_version` 的目錄：`prepare-release.yml` 的 `assemble` job 會在開 PR 前
  移除其他版本目錄，並用 `git add -A downloads` 把刪除一起納入。舊版本早已隨 Release 發布，
  目錄留在倉庫裡只會累積體積（2026-10-05 清理前有 22 個殘留目錄、235 MB）。
- 不要為了量測而反覆觸發 workflow：每次觸發都是 20 個以上 job 的完整執行。改動請批次處理，
  一次執行驗證多項變更，不要一項一項推上去各跑一輪。
- 新增 job 時，必須把它加進 `ci-status` 的 `needs`（`build.yml`），否則它不會被彙總、分支保護也擋不住失敗。
- `website/` 由 `website` job 檢查（`pnpm install --frozen-lockfile`、`pnpm run lint`、`pnpm test`）：
  它用 `git diff` 判斷這個 PR 有沒有動到 `website/`，沒動就整條略過，所以一般 PR 幾乎零成本，
  而 Dependabot 的 npm 更新 PR 會被真的驗證。非 PR 觸發（push `main`、手動）一律執行。
  `website/tests/rendered-html.test.mjs` 的版本號與標題是**從 `app/page.tsx` 原始碼取**的
  （`releaseVersion` 與 `metadata.title`），不要改回寫死版本號——寫死的話每次發布都會讓它變紅
  （2026-10-04 就發生過：測試還停在 1.3.10，網站已經是 1.3.11）。
- `fabric-1.0-1.8.x` 這個 bundle 覆蓋 **1.3.1～1.8.8 當中的 26 個版本**，排除 12 個上游 mappings
  有問題的版本：1.0.0～1.2.5 上游只發佈帶側別後綴的 feather（`1.0.0-client+build.3`），而
  `ploceus.featherMappings` 只會組出一般命名（`1.0.0+build.31`）；1.6.2／1.7.3／1.7.5／1.7.7 雖然
  有部分 build 的成品，但 ploceus 的 version manifest 對不上（`unable to read version details`，
  來自 `manifest/VersionDetails.java` 的解析）。目錄仍在 `platforms/fabric/1.0-1.8/versions/`，
  要恢復就把版本加回 `settings.gradle` 的 `platformProjects`、`platformDependencies` 與 bundle 的
  `supportedVersions` 這三處。這個 bundle 不在 48 顆發布資產裡，所以它紅燈時對使用者沒有影響，
  但排程紅燈會掩蓋其他目標真正的失敗。
- 判斷「某個版本的 mappings 能不能用」**不能只看 artifact 有沒有回 200**：`1.6.2+build.5` 的 `.pom`
  與 `.jar` 都存在，ploceus 仍然建不起來。反過來說，**`maven-metadata.xml` 是過期不完整的**
  （它沒有列出 `1.3.1+build.31`，但那個檔案確實存在），用它判斷會得到完全錯誤的結論
  （2026-10-04 就因此誤判成「整個平台都建不起來」，實際上只有 12 個版本有問題）。
  唯一可靠的判斷方式是實際跑 CI。
- 顯示名稱一律用繁體中文（workflow 名稱、job 名稱、step 名稱），但 **artifact 名稱維持 ASCII**
  （`platform-<target>`、`release-assets` 等），因為 `gh run download -n` 與腳本會用到。
- 建置一律透過 `./.github/actions/gradle-build` 這個 composite action 執行，不要直接寫 `./gradlew`：
  它負責在疑似網路／依賴解析失敗時自動重試（`neoforge-1.20.1` 曾因暫時性 Maven 故障假紅燈一次），
  並讓所有建置的日誌格式一致。要調整重試條件時只改那個檔案，六個呼叫端不用動。
  這個 action 內必須有 `set +e`：GitHub 是用 **`bash -e`** 執行 `run:`（log 裡的 shell 就是
  `/usr/bin/bash -e`），errexit 預設開著，失敗的 `./gradlew | tee` 會直接結束整個 script，
  讓底下的重試邏輯變成死碼——上線後真的發生過一次，log 裡只看得到
  `Process completed with exit code 1`、沒有任何重試訊息。驗證這類 script 要用 `bash -e script.sh`
  模擬 runner，用 `bash script.sh` 測會測不出來。
  重試的 grep 必須用 `-i`：Gradle 的訊息大小寫不一致，ForgeGradle 外掛解析失敗吐的是小寫的
  `could not resolve plugin artifact`，區分大小寫會漏掉而直接放棄（2026-10-04 三個 job 同時中）。
- `-PtargetPlatform` 吃的是**平台名稱**（`forge-1.21.7`、`fabric-1.13.x`、`neoforge-1.20.1`），
  不是發布資產名稱（`mc1.21.7-forge`）。傳錯會在 settings 評估階段立刻失敗
  （`Unknown targetPlatform 'platform-...'`，並列出所有可用值）。`prepare-release.yml` 的 `plan`
  job 會從目標名稱推導平台名稱，不要改成直接用 `matrix.target`。
- `lint` job 用 actionlint 檢查所有 workflow，runner 內建的 shellcheck 會一併檢查每個 `run:` 區塊。
  actionlint 的版本與 sha256 都寫死在 job 裡，升級時要一起改，不要改成「抓最新版」。
- `pull_request` 觸發**不可以**加 `paths` 或 `paths-ignore`：只要有一個 PR 不觸發 workflow，必要的
  `CI 總結` 檢查就會永遠停在 waiting，那個 PR 就無法合併。
- action 版本交給 `.github/dependabot.yml` 每週檢查，並用 `groups` 把所有 action 更新合成一個 PR，
  避免五個 PR 各跑一輪 25 個 job 的建置。Dependabot 開的 PR 同樣要等 `CI 總結` 綠燈。
  npm（`website/`）那組只群組 **minor／patch**，major 會個別開 PR：major 會改變行為、
  需要單獨審查，跟二十個小更新混在一起會讓整個 PR 無法合併（#69 就是這樣，網站檢查直接紅燈）。
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
- `website/` 用 pnpm 11（CI 以 `corepack enable` 讀 `package.json` 的 `packageManager` 決定版本）。
  **pnpm 11 不再讀 `package.json` 的 `pnpm` 欄位**：`overrides`、`allowBuilds` 這類設定要放
  `website/pnpm-workspace.yaml`，放進 `package.json` 只會被靜默忽略（僅吐一行 WARN）。
  2026-10-06 就因此白做了一次無效的 `overrides`。要重新產生 lockfile 時用專案指定的版本，
  不要用系統上剛好裝的那個（版本不同會產生大量無關的 lockfile 變動）。
- 文件為三語：`docs/Zh-cn`（主）、`docs/Zh-tw`、`docs/en`；改一份時要考慮同步其餘兩份。
- 不要提交 `build/` 產物或 `hs_err_pid*.log`；也不要為了方便而修改 `.gitignore` 讓產物進版控。
- 回報時附上對應的 GitHub Actions run 連結。尚未推送、尚未經 CI 驗證的變更，必須明確說明
  「尚未經 CI 驗證」，不得描述成「已通過」。
