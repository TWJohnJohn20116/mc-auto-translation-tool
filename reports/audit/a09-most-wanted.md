# a09-most-wanted 審計報告
範圍：全 repo 產品視角（`README.md`、`docs/**`、`CHANGELOG.md`、`translator-core/src/main/java/**`、`platforms/**` 的設定／runtime／mixin 設定、`.github/workflows/**`、`downloads/1.4`）
配額：NF-001 ~ NF-005（5 條，依使用者價值排序）
產出：NF x 5（實際）

> 本報告不是 bug 清單，也不是功能缺口清單（那分別是 a01–a07 與 a08 的工作）。這裡只回答一個問題：
> **做完哪 5 件事，這個模組的體驗會明顯不同一個檔次。**
> 每條的「痛點證據」全部來自文件原文或 `檔案:行號`，行號皆已 `read`／`grep` 逐項核對。
> 本機未執行任何編譯、建置、打包或測試。

---

### NF-001 · 把「首次能翻譯」壓成一次點擊（首跑引導 + 下載中樞）

- **需求**：新使用者第一次啟動後，從「雙擊遊戲圖示」到「聊天視窗出現第一句譯文」不得超過 3 次互動；且預設離線路徑那 ~508 MB 的下載必須可見、可取消、可續傳、失敗時說得出**中文**原因。

- **痛點證據**：

  1. **首次啟動什麼都不會發生，而唯一的引導是「一行講按鍵的聊天訊息」。** `enabled` 預設 `false`（`platforms/fabric/shared/settings/modern/src/main/java/org/universaltranslator/fabric/FabricConfig.java:87` → `properties.getProperty("enabled", "false")`，`defaults()` 亦寫死 `"false"` 於 `:416`；`ForgeConfig` / `legacy/FabricConfig` / `LegacyConfig` 預設值一致）。進入世界約 60 tick 後，玩家只會看到一行提示「按 `U` 開啟設定；按 `F8` 快速切換翻譯」——它說的是**按哪個鍵**，不是**要設定什麼**。

  2. **預設路徑是「無金鑰 + 半 GB 下載」，而且不會詢問。** 預設 provider 是離線（同上 `:130`），`offline-auto-download` 預設 `true`（`:143`），`offline-model` 預設 `lite`（`:144`）。以 Windows x64 計算，第一次要抓引擎 16,906,751 bytes（`translator-core/src/main/java/org/universaltranslator/core/offline/OfflineEngineAsset.java:89`）加上 Lite 模型 491,400,032 bytes（`translator-core/src/main/java/org/universaltranslator/core/OfflineModel.java:12`），合計 **508.3 MB**。官方文件自己也承認這個量級：「離線模式首次使用需要下載約 502 MB」（`docs/Zh-cn/USER_GUIDE.md:348`）。

  3. **「裝了沒反應」是專案公認的第一號使用者問題，而它的答案是「等下載」。** 文件 FAQ 的第一個條目就是「**文字一直没有翻译**」，回答的第一句是「確認總開關已開啟。離線模式首次使用需要下載約 502 MB，可重新按 `U` 查看狀態，並檢查 `config/universal-translator-offline/llama-server.log`」（`docs/Zh-cn/USER_GUIDE.md:346-352`）。使用者被要求去看一份日誌檔。

  4. **首次設定有兩套互相矛盾的說明。** `README.md:40-44` 給 5 步並說「首次使用**建議**選擇離線與僅譯文」；`docs/Zh-cn/USER_GUIDE.md:32-50` 給 11 步、跨 4 個分頁。但 `docs/Zh-cn/USER_GUIDE.md:63-75` 又列出「新安装默认使用以下配置：`provider=offline` / `display-mode=translated-only` / `offline-auto-download=true`」——README 建議的東西本來就是預設值。同一件事在兩份文件裡是「你該做」與「你不用做」。

  5. **失敗時使用者看不到原因。** 選了任一 LLM 但沒填金鑰時，`ProviderSupport.requireCredential` 拋的是**英文原文** `IllegalArgumentException("<X> API key is required; store it only in the local config file")`（`translator-core/src/main/java/org/universaltranslator/core/provider/ProviderSupport.java:9-14`、`OnlineProviderConfig.java:550`），而畫面上只有一行「切換失敗」，完整原因只進 log。

  6. **面對的是 46 個控件、39 個設定欄位。** 共用設定畫面上 `addSettingButton`／`addSettingField` 共 46 個呼叫點（`platforms/fabric/shared/screens-shared/src/client/java/org/universaltranslator/fabric/UniversalTranslatorSettingsScreen.java`），`FabricConfig` 有 39 個 `final` 欄位。沒有任何引導層。

- **使用者價值**：
  - **誰受益**：**100% 的新安裝**——因為預設就是離線，不吃 API Key 的人全部走這條路；而且 `enabled=false` 意味著連「它到底有沒有在跑」都要使用者自己猜。
  - **受益多少**：把「什麼都沒發生 → 猜 → 按 F8 → 等 508 MB → 猜為什麼沒動 → 去翻日誌」壓成「一個畫面、三個選項、一條進度條」。這是從「看不懂」到「不用看懂」的差別。
  - **不做的代價**：FAQ 第一名會一直是「文字一直沒有翻譯」，而真正的答案不是翻譯壞了，是沒人告訴使用者「正在下載 508 MB」。這條漏斗每一版都在漏人，而 `README.md:42`、`CHANGELOG.md:226` 又不斷加重「每次更新要手動刪舊 JAR」的摩擦——等於對同一個人收兩次稅。

- **實作範圍**：**M**（core 部分 S、48 個目標接線 M）
  - `core/offline/OfflineEngineAsset` + `VerifiedDownloader` + `OfflineProcessSupport`：抽出一個 `DownloadProgress` 介面。目前進度只是各平台 runtime 各自更新的欄位，沒有共用型別，也沒有畫面。
  - 新增 `core/FirstRunState`（是否已完成首跑）與 `core/FirstRunPlan`（三個方案：全離線 / 我有 API Key / 我自己跑本機服務）。
  - 設定畫面共用層加一份 `FirstRunScreen`（**必須在 `platforms/shared`，不可複製到 48 個目標**，理由見「風險」）。
  - 四個 config 類（`platforms/fabric/shared/settings/{legacy,modern}/.../FabricConfig.java`、`platforms/forge/shared/common/.../ForgeConfig.java`、`platforms/forge/shared/legacy-common/.../LegacyConfig.java`）各加一個 `first-run-completed` 鍵。

- **風險與取捨**：
  - **最大風險是它在製造第 29 個設定畫面變體。** `CHANGELOG.md:44`「設定畫面在部分版本一開啟就崩潰」、`:394`「首行按鈕與標題分隔線…發生重疊」、`:247`「设置界面在所有平台显示原始翻译键名」——這個專案每一次複製畫面都留下過債。引導畫面只能是**一份**，掛進既有共用層。
  - **`offline-auto-download` 從 `true` 改 `false` 會讓既有使用者升級後以為壞了。** 取捨：只有全新 config 才進引導——`FabricConfig.java:172-178` 已有現成判準（`configVersion(stored) < 6` 與 `!stored.containsKey("config-version")`）。
  - **不要為了「零設定就能用」而取消詢問。** 取捨是「問一次，但預設勾離線」，不是「不問」。
  - **不做「依遊戲 locale 自動選目標語言」。** 那聽起來更聰明，但 `target-language` 現在硬寫 `zh-CN`（`FabricConfig.java:121`、`:434`），而專案的三語文件與 `docs/*/README.md` 全部預設簡中——改動它會讓「讀繁中文件的人拿到簡中譯文」變成新問題。留給使用者自己選，但要**在引導裡明確問**。

- **驗收標準**：
  1. 刪掉整個 `config/` 後啟動，3 次互動內進入下載頁；該頁顯示 508 MB 的百分比、已下載位元組、預計剩餘時間與「取消」鈕。
  2. 下載中按取消，重啟後從 `.part` 續傳，不重下已完成區段。
  3. 金鑰缺失時畫面顯示**中文**可執行訊息（含「去哪個分頁填」），而不是「切換失敗」+ 英文 `IllegalArgumentException`。
  4. 下載失敗時畫面顯示 `docs/Zh-cn/USER_GUIDE.md:386` 列出的六類中文原因之一（權限／檔案缺失／啟動逾時／參數不兼容／CPU 指令不兼容／記憶體壓力）。
  5. `README.md:40-44` 與 `docs/Zh-cn/USER_GUIDE.md:26-50` 的步驟敘述出自同一份來源、步數一致，且不再出現「README 建議 vs 文件說預設」的矛盾。

- **為什麼不是另外那件事**：不是「補一個下載管理器」。單獨做下載管理器只解決 508 MB 的**可見性**，不解決「為什麼是我在不知情的情況下被下載了 508 MB」，也不解決 `enabled=false` 的靜默與英文錯誤訊息。也不是「補 `batch-window-ms` 的 GUI」（`docs/Zh-cn/USER_GUIDE.md:255` 承認它只能在檔案裡改）——那是六個零 UI 鍵的其中一個，屬於 NF-004 的可發現性範疇，吃不掉第一名的份量。

---

### NF-002 · 讓譯名可被鎖定：可編輯術語表 + 一致性檢查

- **需求**：使用者能維護一份「原文 → 指定譯名」的表（例如 `Diamond Sword → 鑽石劍`、`Hypixel → Hypixel`、`Ancient Debris → 遠古遺骸`），且該表在批次、單筆、串流、離線四條路徑上都被**檢查**，而不只是「請模型保持一致」。

- **痛點證據**：

  1. **目前提升一致性的唯一手段是一句提示詞，而且只有 `high` 模式有。** `translator-core/src/main/java/org/universaltranslator/core/TranslationPrompt.java:58-62` 的 `HIGH_STRICTNESS` 是 `" Preserve the tone, register and terminology of the original, and translate repeated game terms identically every time."`；`TranslationQuality.java:21` 的註解自己定義 high 只是「Adds strictness」。沒有結構化資料、沒有事後驗證、沒有使用者可編輯的介面。

  2. **全 repo 沒有術語表概念。** 對 `translator-core/src` 與 `platforms/` 全樹 grep `glossary|terminology|術語`，只命中 2 個主檔（`TranslationPrompt.java:60` 那句字串、`TranslationQuality.java:21` 的註解）與其測試斷言。同時 `TextKind` 有 21 種文字種類（`translator-core/src/main/java/org/universaltranslator/core/TextKind.java:4-25`：`CHAT` 到 `OTHER`），但種類只影響分組與快取鍵，**不影響譯名**。

  3. **唯一的使用者出口被 4096 字元卡住，而且會摧毀內建協定。** `translator-core/src/main/java/org/universaltranslator/core/UiLimits.java:11` 的 `PROMPT_MAX_CHARS = 4096` 是自訂提示詞上限；而 `CHANGELOG.md:20` 明說「非空時**完全取代**內建提示詞」。使用者要塞術語，就得自己重寫整份 `TranslationPrompt` 的 JSON 批次協定與逐行對齊要求。

  4. **這個痛點已經用「繞路」的方式修過一次，證明它是真的。** `CHANGELOG.md:513`（1.1）：「物品名称与 Lore 现在会作为同一组上下文翻译，**减少箱子和物品提示中的错译**」。那是用分組降低不一致的機率，不是消除不一致——同一件裝備仍可能在 chat、tooltip、容器標題、任務書四個地方拿到四個譯名。

- **使用者價值**：
  - **誰受益**：玩整合包與 RPG 伺服器的人（術語密度最高），以及任何需要專有名詞穩定的場景（伺服器名、公會名、自訂附魔、技能名）。
  - **受益多少**：翻譯品質從「隨模型心情」變成「可預期」。這是唯一能讓使用者**自己**把品質拉到滿分的槓桿——換 provider 只是換一種不一致。
  - **不做的代價**：`fast`/`standard`/`high` 三個模式永遠只是「請求模型聽話的程度」三檔；使用者發現某個詞翻錯時，唯一的動作是整段關掉翻譯（`docs/Zh-cn/USER_GUIDE.md:42` 的屏蔽關鍵詞是「不要翻」，不是「這樣翻」）。

- **實作範圍**：**M**
  - 新增 `core/Glossary`（不可變、有界、含大小寫與標點正規化）與 `core/GlossaryStore`（`config/universal-translator-glossary.properties`，沿用 `blocked-keywords` 的逗號／分號／換行解析慣例，`FabricConfig.java:121` 那一帶）。
  - `TranslationPrompt.Settings#signature()` 要把 glossary 內容簽章納入——**這個機制已經為 `custom-system-prompt` 做過同一件事**（`CHANGELOG.md:19`「提示詞非預設時 provider id 會附加簽章，讓快取鍵跟著提示詞走」），照抄即可。
  - `TranslationCoordinator` 的四個入口（`:58`、`:68`、`:78`、`:103` `translateStreaming`）都要套用，否則串流與批次會繞過。
  - `core/TranslationOutputValidator`（125 行）加一條「鎖定詞是否出現在譯文」的檢查，失敗時走既有的「保留原文」路徑，不新增失敗型別。

- **風險與取捨**：
  - **不能在 `ProtectedText` 之前介入。** 佔位符保護（`translator-core/.../ProtectedText.java`，338 行）先還原再替換的順序若顛倒，玩家名與網址會被術語表吃掉。插入點是「保護之後、送出之前」，比對點是「還原之後」。
  - **離線 0.5B 模型不會遵守術語表。** 硬替換會讓大量條目退回原文，使用者感受是「開了術語表反而更糟」。取捨：**只檢查、不硬改**，並在統計面板顯示「術語命中率」，讓使用者看見哪個模型撐得住。
  - **快取鍵變動會讓既有 10,000 筆快取失效。** 上限見 `platforms/fabric/shared/base/src/main/java/org/universaltranslator/fabric/FabricTranslationRuntimeBase.java:137`（`new PersistentTranslationCache(config.cacheFile, 10_000)`）。取捨：只有 glossary 非空時才附加簽章，空的（預設）與 1.4 位元組相同。
  - **刻意不做「社群共享譯文包」。** `README.md:118` 承諾「緩存鍵使用 SHA-256，原文不會以明文形式寫入緩存文件」；要做可共享的翻譯記憶就得改快取格式並撤回這項隱私承諾，工程量 L。glossary 用 M 換到 80% 的品質收益，划得來。

- **驗收標準**：
  1. 設 `Diamond Sword=鑽石劍`，同一段原文在 chat、item tooltip、container title 三處的譯文都是「鑽石劍」。
  2. `translation-quality=fast` 與 `high` 兩種模式下術語都生效（證明它不依賴提示詞）。
  3. 修改術語表後舊譯文不再命中（快取鍵跟著變）；改回原值後能命中舊快取。
  4. 統計面板出現「術語命中／未命中」兩個計數，且 `standard` 模式在 glossary 為空時送出的位元組與 1.4 完全相同。

- **為什麼不是另外那件事**：不是「再加一個 provider」。那只是換模型，術語問題換模型只會換一種不一致。也不是「把 `custom-system-prompt` 上限從 4096 調大」——4096 字元塞得下幾百條術語，卻塞不下**逐條驗證**；少了 validator 那一半，術語表只是更長的許願池。

---

### NF-003 · 費用護欄：用量上限與成本可視化

- **需求**：使用付費 provider 時，使用者能設定「本次遊戲 / 每日 / 總額」三種上限之一；達到上限後依設定「停止翻譯並顯示」或「降級到離線」，成本以金額估算呈現。

- **痛點證據**：

  1. **token 已經在收，但只收不用。** `translator-core/src/main/java/org/universaltranslator/core/TranslationStats.java:184` 的 `recordUsage(String providerId, String responseBody)` 累加 `promptTokens`／`completionTokens`（`:216-220`），`snapshot()`（`:269`）與 `localizedLines()`（`:294`）把它印出來。但全 repo 沒有金額或上限概念：對 `translator-core/src` grep `budget|cost|quota|dailyLimit|spend`，**所有 `budget` 命中都是「完成額度 max_tokens」或解壓縮預算**（`OpenAiChatTranslationProvider`、`ClaudeTranslationProvider`、`GeminiTranslationProvider`、`SafeArchiveExtractor`），沒有一處是「花多少錢」。

  2. **「重複計費」是已發生的歷史事故，而修法只修了成因、沒加上限。** `CHANGELOG.md:182`：「in-flight 去重鍵本來用 `System.identityHashCode`，而玩家名快照每 5 秒換新實例，去重等於失效；**導致同一段文字重複送出、重複計費**（付費 API 重複計費），改用內容簽章。」——去重鍵修好了，但「就算重送也有上限」這道防線從來沒建。

  3. **成本還被設定放大，而使用者改的時候看不到代價。** `translator-core/src/main/java/org/universaltranslator/core/provider/TranslationTuning.java:21-23` 容許 16 個 worker、`:26-28` 容許批次 16 筆；同檔 `:7-11` 的註解明說這些值「deliberately kept out of `TranslationPrompt.Settings#signature()` and the translation cache key」——也就是**改大它不影響快取、直接影響帳單**。而唯一控制節流的 `batch-window-ms` 連 GUI 都沒有（見 NF-004 第 2 條）。

- **使用者價值**：
  - **誰受益**：所有用線上服務的人。`README.md:103-111` 的表格列出 5 類需要或可能需要 API Key 的方式。
  - **受益多少**：把「無限可能的花費」變成「我設了 10 元，到 10 元就停」。對學生與無收入玩家，這是**敢不敢按下「線上」**的分界線——不是體驗優化，是採用門檻。
  - **不做的代價**：統計面板看得到 token 數但換算不出價格（`TranslationStats.Snapshot` 只有 `promptTokens()`／`completionTokens()`，`:686-691`），使用者只能事後對帳單，然後把整個模組關掉。

- **實作範圍**：**S / M**
  - 新增 `core/TranslationBudget`（上限 + 已用量 + 三種動作 `STOP` / `FALLBACK_OFFLINE` / `WARN_ONLY`）。接點就在 `TranslationStats`——它已經是單一寫入點。
  - `TranslationCoordinator` 的四個入口（`:58`、`:68`、`:78`、`:103`）各檢查一次；超限直接回既有的「保留原文」結果，不新增失敗型別。
  - 單價表：每個 provider 一個「每百萬 token 單價」常數，掛在 `core/TranslationProviderCatalog`（87 行）旁邊。未列出的 provider 顯示 token 不顯示金額。
  - `OnlineProviderConfig.applyDefaults`（`:112`）加 3 個鍵；設定畫面加 2 個控件（同時要進 NF-004 的 catalog，否則又是第七個零 UI 鍵）。

- **風險與取捨**：
  - **硬性 `STOP` 會在最重要的時刻斷翻譯**（打王、跟人講話、正在讀任務書）。取捨：預設動作是 `WARN_ONLY`，用既有 HUD 指示器變色提示——`core/HudIndicatorColor.java` 已有 5 種值，不需要新機制。
  - **單價表一定會過期。** 取捨：明寫「估算值」並允許使用者覆寫單價；不假裝精確。
  - **統計每 60 秒才落盤**（`CHANGELOG.md:28`「讀檔一次、背景每 60 秒寫一次」），跨行程上限會有最多 60 秒的溢出視窗。可接受，但驗收要能看到這個數字。

- **驗收標準**：
  1. 設 `budget-daily-tokens=50000`，第 50,001 個 token **不會被送出**（在送請求前擋，不是送出後才擋）。
  2. 超限時 HUD 指示器變色、聊天訊息說明原因、統計面板顯示「今日已用 X / 上限 Y」。
  3. 動作設為 `FALLBACK_OFFLINE` 時，超限後仍能翻譯（離線路徑可用）。
  4. 單價表未列出的 provider 顯示「未提供單價」，不顯示 0 元。

- **為什麼不是另外那件事**：不是「重做統計面板」。統計面板已經存在且夠用（701 行、p50/p95 延遲、7 類失敗原因、per-provider 計數），缺的是**上限**與**價格**，不是更多圖表。也不是「加 provider 並發限制」——`OnlineProviderConfig.java:128` 的 `api-min-request-interval-ms` 預設 60 ms 已經是速率限制，那是**速率**，不是**總量**；跑一整晚的累計成本它完全看不到。

---

### NF-004 · 設定的可發現性：一份可搜尋、可回復、有預設集的設定

- **需求**：使用者能在設定裡用關鍵字搜到欄位（**包含目前完全沒有 UI 的 6 個鍵**）、一鍵套用「我只想看懂英文聊天」／「我要完整在地化」這類預設集、並能把全部設定回復預設值。

- **痛點證據**：

  1. **設定畫面是全專案被修最多次的一塊，跨四個正式版本。** `CHANGELOG.md:44`「**設定畫面在部分版本一開啟就崩潰**：Fabric 1.16–1.20.1 的 HUD 分頁按鈕只被宣告、從未被建立，導致 `NullPointerException`」；`:394`「首行按鈕與標題分隔線、底部按鈕與運行狀態文字發生重疊」；`:402`「低分辨率或大 GUI 缩放下『保存并应用』按钮超出屏幕」；`:247`「设置界面在所有平台显示原始翻译键名（如 `screen.universal_translator.tab.general`）」；`:299` 同一個問題換句話在 1.3.11-rc2 再列一次；`:153`「選 DeepSeek 等 LLM 服務時，畫面不再顯示 LibreTranslate 的 `127.0.0.1:5000/translate` 端點欄位」。散落在 1.3.4 / 1.3.5 / 1.3.11 / 1.4-beta2 四個版本、至少六次。

  2. **六個鍵完全沒有任何 UI，其中一個還是預設開啟的功能。** 對 `platforms/**` 下所有 `*Screen*.java` grep `streamOutgoing|apiConnectTimeout|apiReadTimeout|apiMaxAttempts|apiMinRequestInterval|batchWindow`：**零命中**。這批鍵是 `stream-outgoing`（`FabricConfig.java:95`，預設 `true` 於 `:421`）、`batch-window-ms`（`OnlineProviderConfig.java:134`，`:285` 的註解自承「`batch-window-ms` has no in-game control」）與四個 HTTP 參數（`api-connect-timeout-ms`／`api-read-timeout-ms`／`api-max-attempts`／`api-min-request-interval-ms`，`OnlineProviderConfig.java:125-128`）。另有 `hud-indicator-offset-x/y`（`FabricConfig.java:115`、`:118`）只能靠拖曳，不能輸入數值。對照 `OnlineProviderConfig.KEYS` 陣列（`:33-61`，73 個鍵），**使用者只能透過遊戲內畫面碰到其中一部分**。

  3. **有些欄位只能在檔案裡改，使用者被迫同時學兩套介面。** `docs/Zh-cn/USER_GUIDE.md:255`：「`batch-window-ms` 沒有遊戲內控件，只能在配置文件里修改」。而改了還要「**關閉遊戲後**手動改」（`docs/Zh-cn/USER_GUIDE.md:119-127`、`:146-148` 都這樣寫）。同一個功能，一半在 GUI、一半在文字檔。

  4. **規模本身就是門檻，而且沒有搜尋、沒有預設集、沒有回復預設。** 共用設定畫面有 46 個控件呼叫點與 4 個分頁（`UniversalTranslatorSettingsScreen.java`；`:346-349` 是「混合文本」那一列，`:552-564` 的 `refreshLabels()` 一口氣更新 13 個標籤），`FabricConfig` 有 39 個 `final` 欄位。全樹 grep `reset` 只命中**統計面板**的重設統計鈕（`platforms/fabric/1.0-1.8/shared/common/.../UniversalTranslatorDiagnosticsScreen.java:67`），設定本身沒有重設。

- **使用者價值**：
  - **誰受益**：全部使用者。真正的大多數只想要「聊天看得懂」，要改的其實是 1 個開關（`enabled`），卻要面對 46 個控件；而**六個鍵連控件都沒有**，等於設定有兩層級：看得到的與要自己挖的。
  - **受益多少**：從「翻完文件才敢動」變成「打字搜尋」。
  - **不做的代價**：`CHANGELOG.md:88` 自己列了 7 項「尚待實機驗證」，全部與畫面相關；`CHANGELOG.md:122` 又說「48 個目標逐一開啟設定畫面，確認四個分頁的排版…」——**每次動畫面就要重驗 48 個目標**。這是一筆會持續收租的維護成本，而它現在沒有結構性解法。

- **實作範圍**：**M / L**（core M、48 個目標接線 L）
  - `core/SettingsScreenLayout`（112 行）與 `core/SettingsSelectionList`（99 行）已是共用骨架，加「filter query」是 **S**。
  - 需要**欄位清單的單一來源**：現在標籤鍵散在 `core/UiTextKeys`（20 行）與各平台畫面。抽成 `core/SettingsCatalog`（key / 分頁 / 標籤鍵 / 型別 / 是否必填 / 推薦值）。畫面只負責渲染——這一步順便解掉「28 個設定畫面變體」的長期成本（`CHANGELOG.md:30`、`:134`）。
  - **六個零 UI 鍵必須一起進 catalog 並長出控件**，否則 NF-004 只做了一半。
  - 三個預設集寫在 catalog 裡，套用時走既有的 `FabricConfig.from(...)`（`FabricConfig.java:204` 已有 39 參數建構子，`defaults()` 在 `:415-452`）。
  - 「回復預設」直接呼叫 `defaults()`，並先備份使用者原檔。

- **風險與取捨**：
  - **加搜尋框會重演「小視窗按鈕溢出」**（`CHANGELOG.md:402`）。必須沿用既有 `SettingsScreenLayout` 的列高計算，不可自算座標。
  - **預設集覆蓋使用者既有設定是不可逆的破壞。** 取捨：預設集只改「它自己那幾個鍵」，套用前顯示差異清單。
  - **「回復預設」對調了很久的使用者是陷阱。** 取捨：先寫 `universal-translator.properties.bak`，並在聊天訊息裡給出備份路徑。
  - **catalog 化會撞到四份 config 類**（fabric `legacy`／`modern`、forge `common`／`legacy-common`）。取捨：catalog 只描述「鍵與標籤」，實際讀寫留在各 config 類，不強迫統一。

- **驗收標準**：
  1. 在設定頁輸入 `batch`，1 秒內把 46 個控件過濾到只剩批次相關者，**且包含原本沒有 GUI 的 `batch-window-ms`**（這同時解掉 `USER_GUIDE.md:255` 的摩擦）。
  2. 六個零 UI 鍵（`stream-outgoing`、`batch-window-ms`、四個 HTTP 參數）全部可在遊戲內修改，且 `hud-indicator-offset-x/y` 可直接輸入數值。
  3. 套用「聊天優先」後，`provider`／`target-language`／`display-mode` 變成文件記載的推薦值，其餘 36 個鍵位元組不變。
  4. 「回復預設」後 `universal-translator.properties` 與 `FabricConfig.defaults()`（`FabricConfig.java:415-452`）逐鍵相等，並產生 `.bak`。
  5. 48 個目標的設定畫面在 854×480 下無控件互相重疊（延續 `CHANGELOG.md:402` 的既有驗收），且標籤全部來自 `UiTextKeys` 而非原始鍵名（延續 `CHANGELOG.md:247`）。

- **為什麼不是另外那件事**：不是 NF-001。首跑引導解的是「第一次」，這一條解的是「第 100 次想改一個東西」——兩者不可互替，而且第一名的位置該給漏斗口徑更大的那一條。也不是 a06 的 UI bug 清單：那些是排版與鍵名的**個別缺陷**，這裡要的是**結構**（單一來源 + 搜尋 + 預設集）。不先把欄位收斂成資料，下一次加欄位時同一批 bug 會再長一遍——`CHANGELOG.md:299` 與 `:247` 就是同一個問題修了兩次，而現在已經累積到**六個鍵連控件都沒有**。

---

### NF-005 · 安裝自檢：讓「裝錯 / 裝舊 / 少功能」在遊戲內被指出來

- **需求**：模組啟動時自我檢查「這顆 JAR 是否對應這個 Minecraft 版本與載入器、`mods/` 是否有並存的舊版、這個目標是否缺了某個功能」，並在聊天給出可執行的一句話；同時把「該下載哪一顆」的資訊收斂成單一來源，四份 README 與相容性矩陣不得再互相矛盾。

- **痛點證據**：

  1. **19 顆 NeoForge JAR 發布了，README 只列 4 顆。** `.github/workflows/publish-release.yml:72-119` 的 `expected` 陣列共 **48** 個目標，其中 **19 個 neoforge**（`mc1.20.1`、`1.20.4`、`1.20.6`、`1.21`、`1.21.1`、`1.21.2`、`1.21.3`、`1.21.4`–`1.21.10`、`1.21.11`、`26.1`、`26.1.1`、`26.1.2`、`26.2`）。而 `README.md:87-92` 的 NeoForge 表只有 **4 列**（1.20.1、1.21.1、1.21.3、1.21.11）。**15 顆有檔案、沒有任何文件連結。**（`scripts/prepare_release_assets.py` 只合併 Fabric 與 Forge 家族，NeoForge 一顆不合併，所以這些檔案確實會變成 Release 資產。）

  2. **41 個版本每週都在 CI 建置，卻不在任何發布清單裡。** `settings.gradle:21` 有 `platform-fabric-1.0-1.8.x`（`:167` 列出 26 個支援版本）、`:63` 有 `platform-fabric-1.8-1.12.x`（`:169` 列出 15 個版本），合計 **41 個 Minecraft 版本**。`.github/workflows/legacy-targets.yml:61-62` 每週建置它們，但 `.github/workflows/prepare-release.yml` 對這兩個 bundle 的引用數是 **0**，`publish-release.yml:72-119` 的 48 目標也沒有它們。結果就是 `README.md:63` 那句：「1.0.0–1.12.2（Fabric / Ornithe）尚未隨 v1.4 提供。請不要把 `fabric-all` 用在未列出的遊戲版本上。」——**東西建得出來、測得過，只是沒人拿得到。**

  3. **已發布的 NeoForge 1.20.x（3 顆）缺 HUD 指示器，但設定開關還在。** `platforms/neoforge/shared/metadata/1.20-1.20.4/src/main/resources/universal-translator.neoforge.mixins.json` 與 `.../metadata/1.20.6/...` 以及 `platforms/neoforge/1.20.1/src/main/resources/...` **都沒有** `InGameHudStatusIndicator` 條目（逐檔核對），而 `metadata/1.21/...` 與 `metadata/26.x/...` 有；純 Forge 的六個 mixin 設定（`modern/1.16.5`、`1.18.2`、`1.19.2`、`1.20.1`、`1.21`、`26.x`）**全部都有**。但設定畫面是共用的——`platforms/neoforge/shared.gradle:81-86` 把 `platforms/forge/shared/screens/1.18.2-1.21.11` 掛給所有非 26.x 的 NeoForge 目標，所以 NeoForge 1.20.x 的玩家**看得到指示器開關、開啟後什麼都不會發生**。遊戲內沒有任何訊息說明。

  4. **文件數字互相矛盾且已過期。** `README.md:52` 寫「正式版把 48 個構建產物整理為 **31 個可直接安裝的 JAR**」，但四份文件六處仍停在舊版：`docs/Zh-cn/README.md:50`、`docs/Zh-tw/README.md:52`、`docs/en/README.md:48-49`、`docs/Zh-cn/COMPATIBILITY.md:55` 全部寫「33 個…16 個」。

  5. **「裝錯就載入失敗」是歷史事實，而解法是叫使用者手動做對三件事。** `CHANGELOG.md:66`：「原本版本清單從 1.15.2 直接跳到 1.16.5，**這 5 個版本的玩家裝了會載入失敗**」。`README.md:42`：「先從該實例的 `mods` 文件夾刪除 1.4-beta4 及更早版本，再放入**唯一一個**匹配的 1.4 JAR。Fabric 還需安裝對應版本的 Fabric API。」同一句話在 `CHANGELOG.md:226` 再寫一次。做錯任一件 → 啟動失敗，而遊戲內沒有任何訊息。

- **使用者價值**：
  - **誰受益**：19 顆 NeoForge 目標的玩家（找不到下載連結）、41 個 Fabric 舊版本的玩家（連檔案都沒有）、NeoForge 1.20.x 的玩家（功能少了但沒人說）、以及每次升級的所有玩家（手動刪舊 JAR）。
  - **受益多少**：把「啟動失敗 → 去翻文件 → 發現文件過期 → 去翻 Release 資產清單猜檔名」變成「聊天欄一句話告訴你該抓哪一顆」。對 NeoForge 1.20.x 則是「與其想為什麼指示器不出現，不如直接被告知這個版本不支援」。
  - **不做的代價**：`AGENTS.md` 記錄 2026-10-06 因為版本號寫在兩處而漂掉、`CHANGELOG.md:68` 記 `VerifiedDownloader` 停在 `1.1` 對外宣告三版前的版本號——**這個專案已經用兩次事故證明「同一件事寫兩份就一定會漂」**。而 JAR 清單現在正是兩份（README 表格 vs `publish-release.yml` 的 `expected`），而且已經漂了 15 顆。

- **實作範圍**：**M**
  - 新增 `core/InstallSelfCheck`：讀自身 metadata（`fabric.mod.json` / `mods.toml`，內容已由 `scripts/verify_release_jars.py` 驗證過），比對當前 Minecraft 版本與載入器，結果丟給既有聊天訊息通道（`core/LogMessages` 17 行、`core/UiTextKeys` 20 行已是共用常量）。
  - **目標功能旗標**：在 `core` 放一份「這個目標支援哪些功能」的表，由各平台的 mixin 設定在 build 時填入，`InstallSelfCheck` 用它把「不支援」講出來。這是唯一能防止「開關在、功能無」再發生的結構性做法。
  - 舊版並存檢測：列舉 `mods/` 下所有 `MCAutoTranslationTool-*.jar`，>1 顆就警告並指出該刪哪一顆。這不需要任何 loader API，舊層也能做。
  - 文件單一來源：讓 `publish-release.yml` 的 `expected` 陣列與 README 表格由同一份資料產生；CI 加一條「README 的數字與 `expected` 長度一致」的檢查。
  - **S** 在 self-check，**M** 在「文件由資料產生」那條管線。

- **風險與取捨**：
  - **1.0–1.12 的 Ornithe／legacy 層沒有共用的 metadata API**，而 28 個畫面變體又不能共用。取捨：self-check 只做 1.13+ 與 Forge／NeoForge 全部；舊層用「JAR 檔名比對」這種零 API 依賴的方式，覆蓋「並存舊版」那一半。
  - **把 41 個版本補進發布清單不是這條的工作。** 補它們要讓 `prepare-release.yml` 從 48 變成 89 顆目標、並重寫 `publish-release.yml` 的 `expected` 與數量檢查（AGENTS.md 明說這兩處只能在下一個版本的 release PR 裡一起改）。取捨：NF-005 先做「把話說清楚」，補版本留給獨立決策。
  - **文件由程式產生會讓 README 失去手寫敘事。** 取捨：只讓**表格與數字**由資料產生，敘事段落保留手寫。
  - **CI 檢查太嚴會擋住正常文件 PR。** 取捨：只在 `gradle.properties` 的 `mod_version` 變動時比對，其餘時間只警告不失敗。

- **驗收標準**：
  1. 在 1.21.1 上裝 1.4 的 `mc1.21.11-forge` JAR，進遊戲後聊天欄出現一句可執行指示（含正確檔名），而不是崩潰或靜默無效。
  2. `mods/` 同時存在 1.3.11 與 1.4 的 JAR 時，出現一次警告並指出該刪哪一顆。
  3. 在 NeoForge 1.20.4 上開啟 HUD 指示器，聊天欄出現「此版本不支援 HUD 指示器」而不是靜默不動。
  4. `README.md` 的 NeoForge 表格列數 = `expected` 陣列中 neoforge 的數量（19）。
  5. `README.md`、`docs/{Zh-cn,Zh-tw,en}/README.md`、`docs/Zh-cn/COMPATIBILITY.md` 六處的「48 → 31」全部一致，且 CI 有一條檢查會擋住回退。

- **為什麼不是另外那件事**：不是「補平台缺口」。補 41 個 Fabric 舊版本的發布檔要讓 48 顆變 89 顆（見「風險與取捨」），受眾卻被專案自己判定為接近零（`CHANGELOG.md:79`）；而「已發布的 15 顆 NeoForge JAR 沒有任何文件指向」與「3 顆已發布 JAR 的功能少了而開關還在」是**零額外建置成本**、受眾明確的問題。**先把指路與實話做對，再談補更多版本。**

---

## 考慮過但沒入選的 5 個候選 + 淘汰理由

| # | 候選 | 證據起點 | 淘汰理由 |
| --- | --- | --- | --- |
| 1 | **網站／Release 的一鍵選 JAR 選擇器** | `README.md:52`（31 顆）、`README.md:85`（NeoForge 必須按版本分開）、`publish-release.yml` 的 19 顆 neoforge | 與 NF-005 重疊但**切在錯的層**。網站只能解決「第一次下載」，解決不了「每次升級手動刪舊 JAR」（`README.md:42`）與「進遊戲才發現功能少了」（NeoForge 1.20.x）。NF-005 把同一批證據用在遊戲內自檢，覆蓋面更大，且不需要動 `website/`（那條路徑另有 `pnpm` 檢查成本）。 |
| 2 | **補齊設定 GUI 覆蓋率**（`batch-window-ms`、`stream-outgoing`、四個 HTTP 參數） | `docs/Zh-cn/USER_GUIDE.md:255`、`:119-127`、`OnlineProviderConfig.java:125-128`、`:285` | 這是 **NF-004 的子集**。單獨補六個欄位進不了前五；NF-004 用「欄位收斂成 catalog + 搜尋」一次解掉這一類問題，並把「六個零 UI 鍵」直接寫成驗收標準第 2 條。 |
| 3 | **下載管理器**（暫停／取消／續傳可視化） | `docs/Zh-cn/USER_GUIDE.md:77-81`（引擎 + 模型）、`:348-350`（`.part` 續傳）、`VerifiedDownloader.java:105` | 只解 NF-001 的一半（可見性），解不了 `enabled=false` 的靜默、英文錯誤訊息，也解不了「下載完還要跨 4 分頁設定」。而且 `.part` 續傳**機制已經存在**（`USER_GUIDE.md:349-350` 已在教使用者看 `.part`），缺的是入口不是引擎。 |
| 4 | **翻譯失敗診斷可操作化** | `CHANGELOG.md:27`（六類真實原因）、`:29`（診斷包）、`:30`（一鍵測試連線） | 1.4 已經做掉一大半：非 JSON 轉述、轉發站錯誤轉述、端點自動補 `/chat/completions`、推理模型重試、診斷包匯出、依服務分派的「測試連線」。剩下的缺口（英文例外直出、`切換失敗` 蓋掉原因）已被 NF-001 驗收標準第 3 條吸收。 |
| 5 | **三語文件同步 CI 檢查** | `docs/Zh-cn/README.md:50` / `docs/Zh-tw/README.md:52` / `docs/en/README.md:48-49` / `docs/Zh-cn/COMPATIBILITY.md:55` 四處「33→16」 | 屬 a07 的 CI 範圍，而且它是「防止再漂」而非「改變體驗」。它的核心證據已被 **NF-005 吸收為驗收標準第 5 條**，不必自成一條 NF。 |

---

## 已排查但判定無問題

1. **預設 provider 是離線、不填 API Key**（`FabricConfig.java:130`、`:134` 的 `api-key` 預設空字串）— 這是刻意的產品決策（`README.md:30`「推薦使用內建離線模式，不需要 API Key」），不是缺陷。NF-001 的問題在**引導**與**靜默**，不在預設值本身。
2. **`enabled` 預設 `false`**（`FabricConfig.java:87`、`:416`）— 「裝了不要自動翻譯」是尊重玩家遊戲體驗的正確預設。問題不是這個值，而是**沒有任何東西告訴使用者它現在是關的**；那是 NF-001 的範圍，不是這個預設有錯。
3. **`translate-english-only` 預設 `true`**（`FabricConfig.java:127`）— 這個名字看起來像「只翻英文」，實際語意是 `preserveHanText`（`RenderTranslationSession.java:140`、`:152`），在中文目標下等於「所有字母都是漢字就跳過」（`LanguageHeuristics.java:72`），與 UI 標籤「混合文本」（`UniversalTranslatorSettingsScreen.java:552`）一致。不是命名錯誤，也不是反向邏輯。
4. **`TranslationTuning` 的所有值一律 clamp 而不拒絕**（`TranslationTuning.java:43-48`、`:88-97`）— 手改設定檔打錯字不該讓翻譯停擺，這是正確的容錯方向。
5. **快取鍵刻意不含 throughput 參數**（`TranslationTuning.java:7-11`）— 改併發數不該讓既有譯文失效，設計正確。
6. **`LoopbackContext.TOKENS = 1024`**（`LoopbackContext.java:13`）— 數字偏小但一致：`LlamaCppOfflineProvider` 的 `--ctx-size` 與 `OpenAiChatTranslationProvider` 的完成額度共用同一個常數，兩邊不會再各自漂移（該檔註解 `:6-9` 正是為此而寫）。
7. **`TranslationQuality.STANDARD` 逐位元等於舊提示詞**（`TranslationQuality.java:13-14`、`CHANGELOG.md:19`）— 預設行為零變更是正確的相容策略，也讓 NF-002 的快取鍵設計有現成樣板。
8. **Forge 每個版本一顆、NeoForge 完全不合併**（`README.md:85`、`CHANGELOG.md:78`）— FML 不允許同一 JAR 內嵌多套同 `modId` 實作，這是上游限制，不是設計缺陷。NF-005 的問題是「合併後沒有文件指路」與「少功能沒說」，不是「為什麼不合併」。
9. **`docs/Zh-tw/ARCHITECTURE.md`（133 行）與 `en`（70 行）／`Zh-cn`（62 行）不是同一篇文章** — 已確認是內容不同（tw 版含 mermaid 五層架構），不是翻譯漂移。屬文件一致性範圍，不在 NF 評比內。

---

## 附錄：覆蓋範圍與方法

**主代理（a09）自行讀取並逐項核對行號的檔案**（全部唯讀）：

- `reports/audit/00-規則與號段.md`（142 行）
- `CHANGELOG.md`（**531 行**，全讀；共 28 個版本段落）
- `README.md`（154 行，全讀）
- `gradle.properties`、`settings.gradle`（`platformProjects` `:15-163`、`platformDependencies` `:166-191`）
- `docs/Zh-cn/USER_GUIDE.md`（節錄 1-95、246-259、340-359）
- `docs/Zh-cn/README.md`、`docs/Zh-tw/README.md`、`docs/en/README.md`、`docs/Zh-cn/COMPATIBILITY.md`（數字一致性核對）
- `translator-core/src/main/java/org/universaltranslator/core/`：`TranslationTuning.java`、`TranslationQuality.java`、`TargetLanguage.java`、`LanguageHeuristics.java`、`OfflineModel.java`、`TranslationProviderCatalog.java`、`RenderTranslationSession.java`（1-175）、`TextKind.java`、`TranslationDisplayMode.java`、`MinecraftContentScope.java`、`TranslationPrompt.java`（41-70）、`TranslationStats.java`（API 掃描）、`UiLimits.java`、`OfflineEngineAsset.java`、`provider/LoopbackContext.java`、`provider/OnlineProviderConfig.java`（`KEYS` `:33-61`、`applyDefaults` `:112-206`）
- `platforms/fabric/shared/settings/modern/.../FabricConfig.java`（全預設值、`defaults()` `:415-452`、遷移 `:172-178`）
- `platforms/fabric/shared/base/.../FabricTranslationRuntimeBase.java`（125-165）
- `platforms/fabric/shared/screens-shared/.../UniversalTranslatorSettingsScreen.java`（340-365、545-565；控件呼叫點計數 46）
- `platforms/neoforge/shared.gradle`、`platforms/neoforge/**/*.mixins.json`（12 檔逐一比對 `InGameHudStatusIndicator`）、`platforms/forge/**/*.mixins.json`（6 檔逐一比對）
- `.github/workflows/publish-release.yml`（`expected` `:72-119`、縮減與驗證 `:143-186`）、`legacy-targets.yml`（`:61-62`）、`prepare-release.yml`（目標清單引用數）
- `scripts/prepare_release_assets.py`（函式掃描，確認只有 Fabric／Forge 合併）
- `downloads/1.4`（74 個檔案列舉 = 40 完整 JAR + 33 分片 + 1 SHA256SUMS）

**孫代理分工**（均為唯讀、未編譯／未建置／未執行專案腳本、未寫任何檔案）：

- **孫代理 A（文件痛點挖掘）**：完整 `read` 22 份文件共 3,283 行（含 CHANGELOG 全 531 行、三語 USER_GUIDE／COMPATIBILITY／ONLINE_APIS／ARCHITECTURE／README），另 12 輪 grep 共 405 命中行。產出：16 步首跑流程（每步附 `檔案:行號`）、CHANGELOG 11 個主題的 Top 8 排序（發布數量同步 14 版 → 設定畫面 12 版 → 離線引擎 9 版 → provider 8 版 → 顏色 8 版）、25 條「手動」摩擦與 22 條「必須／僅／不支援」硬限制、4 處文件互相矛盾的實證。並校正三件事：CHANGELOG 是 **531 行**、版本段落叫 `1.4-beta` 而非 `1.4-beta1`、三語 ARCHITECTURE 並非同一篇文章。
- **孫代理 B（首次啟動門檻 + 平台落差）**：讀 `settings.gradle`(248)、四份 config 類、`OnlineProviderConfig`(636)、`ProviderSupport`(25)、`TranslationTuning`、`OfflineEngineAsset`(135)、`VerifiedDownloader`(275)、`LlamaCppOfflineProvider`、`FabricClientBootstrapBase`、`build.yml`(615 全讀)、`legacy-targets.yml`(318 全讀)、`prepare-release.yml`、`publish-release.yml`、`neoforge/shared.gradle`、neoforge/forge 共 11 個 mixin 設定與 4 個 mixin 實作。其核心結論（`enabled=false`、508.3 MB、6 個零 UI 鍵、41 個未發布版本、NeoForge 1.20.x 缺指示器）**已由主代理逐條重新核對行號後採用**；並據此修正了任務書的錯誤前提：本專案**沒有** `TranslatorConfig` 這個類別（實際是四份 config 類）。
- 兩份孫代理產出皆由主代理統一分級、排序與編號，號段 `NF-001`–`NF-005` 未越界。

**本輪未使用**：`build/`、`.gradle/`、`node_modules`、`dist/`、`__pycache__`、`_re/`（依規則排除）；`docs/*/BUILDING.md`（屬 a07 範圍）。
