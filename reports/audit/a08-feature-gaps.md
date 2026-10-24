# a08-feature-gaps 審計報告

範圍：全 repo（`translator-core`、`platforms/{fabric,forge,neoforge}`、`.github/workflows`、`settings.gradle`、`downloads/`、`README.md` / `docs/`），排除 `build/`、`.gradle/`、`node_modules`、`dist/`、`__pycache__`、`_re/`
配額：FG-001–010（10 條）、BUG-S-051–055（有才寫）
產出：FG 10 / BUG-S 3（實際）

**排序鍵**：使用者感知強度 ÷ 實作成本（每單位工作量換到的體驗改善）。前三條刻意提前，理由是它們都是**文件已經承諾、程式碼沒做到**的落差 —— 這類缺口的可信度損失最大。`FG-010` 雖然是整個模組天花板最高的缺口，但成本是 L，所以排在最後；若只打算投入一件大事，做 `FG-010`。

**全 repo 驗證基準**（本文所有矩陣數字都以此為準，非引用他人）：

- `settings.gradle:15-163` 的 `platformProjects` 共 **142** 條
- `.github/workflows/build.yml` 24 個目標（platform-build 17 + legacy-build 7）
- `.github/workflows/legacy-targets.yml` 的 `plan` JSON 21 個目標
- `.github/workflows/prepare-release.yml` 48 個目標
- `downloads/1.4` 48 顆邏輯 JAR（Fabric 8 / Forge 21 / NeoForge 19）+ `SHA256SUMS.txt`
- `TranslationProviderCatalog.java:5-26` 共 **20** 個 provider id（1 離線 + 19 線上）；審計規則檔寫「約 25 家」，實測為 20，請以本清單為準

---

### FG-001 · 逐面開關失靈：21 個面只有 7 個被分類

- **嚴重度**：功能缺口 ｜ **信心**：高
- **缺口描述**：模組對「哪一種遊戲文字」只有兩個開關（聊天 / 其他），使用者無法只關記分板、只關物品名、只關 Boss 血條。文件承諾的 9 種面在程式碼裡沒有對應的可辨識分類。

- **現狀**：
  - `translator-core/src/main/java/org/universaltranslator/core/TextKind.java:4-26` 宣告 **21** 個面（CHAT、SYSTEM_MESSAGE、SCOREBOARD_TITLE、SCOREBOARD_LINE、PLAYER_LIST_HEADER、PLAYER_LIST_FOOTER、ACTION_BAR、TITLE、SUBTITLE、BOSS_BAR、CONTAINER_TITLE、ITEM_NAME、ITEM_LORE、TOOLTIP、SIGN、BOOK、HOLOGRAM、ENTITY_NAME、DISCONNECT_REASON、TOAST、OTHER）。
  - 實際被 `TranslationRenderContext.push(...)` 產生的只有 **7** 個。全 repo grep `TranslationRenderContext.push(` 得到的實參只有 `TextKind.CHAT`、`BOSS_BAR`、`PLAYER_LIST_HEADER`、`SCOREBOARD_LINE`、`TITLE`、`ACTION_BAR`、`TOOLTIP`。代表位置：
    - `platforms/fabric/1.21/shared/common/src/client/java/org/universaltranslator/fabric/mixin/InGameHudContextMixin.java:18`（CHAT）、`:46`（PLAYER_LIST_HEADER）、`:58`（TITLE）、`:70`（ACTION_BAR）
    - `platforms/fabric/shared/mixin-1.16-1.19/src/client/java/org/universaltranslator/fabric/mixin/ChatHudContextMixin.java:17`（CHAT）
    - `platforms/fabric/shared/mixin-1.16-1.19/src/client/java/org/universaltranslator/fabric/mixin/PlayerListHudContextMixin.java:22`
    - `platforms/fabric/shared/mixin-1.16-1.19/src/client/java/org/universaltranslator/fabric/mixin/BossBarHudContextMixin.java:16`
    - `platforms/fabric/shared/mixin-1.16-1.19/src/client/java/org/universaltranslator/fabric/mixin/InGameHudContextMixin.java:21`（SCOREBOARD_LINE）
    - `platforms/fabric/1.0-1.8/shared/common/src/main/java/org/universaltranslator/fabric/mixin/ScreenMixin.java:29`（TOOLTIP）
  - `HOLOGRAM`、`ENTITY_NAME`、`TOAST` 三者**全 repo（含 docs）零引用**。grep `HOLOGRAM|ENTITY_NAME|\bTOAST\b` 在 `*.java`/`*.json`/`*.md`/`*.toml` 只命中 `TextKind.java:21`、`:22`、`:24` 三行宣告本身。
  - `SCOREBOARD_TITLE`、`PLAYER_LIST_FOOTER`、`SUBTITLE`、`CONTAINER_TITLE`、`ITEM_NAME`、`ITEM_LORE`、`SIGN`、`BOOK`、`DISCONNECT_REASON` 只出現在 `translator-core/src/main/java/org/universaltranslator/core/RenderTranslationSession.java:656-670` 的 `case` 標籤，沒有任何 producer 會送進這些值。
  - `SIGN` 的 mixin 做的事正好相反 —— 它只「抑制」翻譯：`platforms/fabric/shared/mixin-1.20-1.21/src/client/java/org/universaltranslator/fabric/mixin/SignEditScreenMixin.java:11`（`Keeps the local sign editor preview untranslated`）、`:19`（`pushTextInput`）、`:27`（`popTextInput`）。
  - 物品名的情況：`platforms/fabric/shared/mixin-1.20-1.21/src/client/java/org/universaltranslator/fabric/mixin/ScreenMixin.java:19-27` 攔 `getTooltipFromItem` 後呼叫 `RenderedTextBridge.translateItemTooltip`，而該方法最終送出的 kind 是 `TextKind.TOOLTIP`（`platforms/fabric/1.14-1.15/shared/family/1.14-1.15-common/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:61`），所以 `ITEM_NAME` / `ITEM_LORE` 永遠不會出現。
  - 開關只有兩段：`platforms/fabric/shared/settings/modern/src/main/java/org/universaltranslator/fabric/FabricConfig.java:389-396` 的 `allows(TextKind)` 只判 `CHAT || SYSTEM_MESSAGE → translateChat`，其餘全部落 `translateOther`。同構實作在 `platforms/fabric/shared/settings/legacy/src/main/java/org/universaltranslator/fabric/FabricConfig.java:393`、`platforms/forge/shared/common/src/main/java/org/universaltranslator/forge/ForgeConfig.java:393`、`platforms/forge/shared/legacy-common/src/main/java/org/universaltranslator/forge/legacy/LegacyConfig.java:400`。

- **為什麼算缺口**：文件與行為直接衝突。`README.md:28` 寫「全界面翻译｜覆盖聊天、任务/配方界面、模组菜单、记分板、Tab、标题、Boss Bar、容器、物品名与 Lore、书、告示牌、全息文字等」，`docs/en/README.md:23` 同樣列了 holograms/books/signs；但 `README.md:117` 自己又承認「可以分别关闭聊天内容、其他界面」。使用者最常見的需求就是「保留聊天翻譯、不要翻物品名與記分板」—— 現在只能全有或全無。同類模組（TranslationCache、ModTranslater、i18nUpdateMod 的設定頁）都提供逐類勾選。這同時是後續 FG-005（快取分類統計）、FG-007（術語表按面生效）的共同前置。

- **最小可用實作路徑**：
  1. 不動 `TextKind`（21 值留著），改的是「誰送進來」：
     - `RenderedTextBridge` 新增 `translate(Text text, TextKind kind)` 重載，由呼叫端決定 kind。
     - `platforms/*/mixin/` 增加依 `Screen` 型別分流的薄 mixin：`AbstractContainerScreen#render` → `CONTAINER_TITLE`、`BookViewScreen#render` → `BOOK`、`SignEditScreen` 的唯讀渲染 → `SIGN`、`PlayerListHud` 的 footer 呼叫 → `PLAYER_LIST_FOOTER`、`InGameHud#renderTitle` 的副標 → `SUBTITLE`、`BossBarHud` 已存在、`ToastManager` → `TOAST`、`EntityRenderer#renderLabelIfPresent` → `ENTITY_NAME`、`DisconnectedScreen#init` → `DISCONNECT_REASON`、`InGameHud#renderScoreboardSidebar` 的 objective 顯示名 → `SCOREBOARD_TITLE`。
     - 世界空間文字（全息、告示牌世界端）已在 `TextRendererMixin` 內（見 `platforms/fabric/1.21/shared/text-renderer/1.21.11/src/client/java/org/universaltranslator/fabric/mixin/TextRendererMixin.java:11` 的註解），只需在該處加一個「目前無棧頂 kind 就視為 `HOLOGRAM`」的預設。
  2. `TranslationSpec`/`FabricConfig`/`ForgeConfig`/`LegacyConfig` 把 `translate-chat` / `translate-other` 保留為相容別名，新增 `translate-kinds=<逗號分隔>`；`allows(TextKind)` 改成讀一個 `EnumSet<TextKind>`。
  3. 設定畫面第 2 Tab 把兩個按鈕換成可捲動的 21 行勾選清單。改動點是 9 個畫面各一份的同一段（例：`platforms/fabric/shared/screens-shared/src/client/java/org/universaltranslator/fabric/UniversalTranslatorSettingsScreen.java`、`platforms/forge/shared/screens/1.18.2-1.21.11/src/main/java/org/universaltranslator/forge/UniversalTranslatorScreens.java`）。建議先把清單抽進 `translator-core/.../SettingsSelectionList`（已存在）以免 9 份各寫一次。
  4. 同步改 `README.md:28,117` 與三語 docs，別再讓文件超寫。

- **涉及檔案**：`translator-core/src/main/java/org/universaltranslator/core/{TextKind,RenderTranslationSession,TranslationRequest,SettingsSelectionList}.java`；`platforms/fabric/{shared/mixin-1.16-1.19,shared/mixin-1.20-1.21,1.21/shared/common,26.x/shared/common,1.0-1.8/shared/common}/.../mixin/*.java` 與 `.../RenderedTextBridge.java`；`platforms/forge/{shared/modern-1.18-1.19,modern/1.20.1,1.21/shared/common,26.x/shared/common}/.../mixin/*.java`；`platforms/*/shared/settings/**/{FabricConfig,ForgeConfig,LegacyConfig}.java`；9 個設定畫面（含語言檔 `platforms/shared/lang/json/*.json`）
- **預估工作量**：M

---

### FG-002 · 沒有術語表／自訂詞典，專有名詞譯法無法固定

- **嚴重度**：功能缺口 ｜ **信心**：高
- **缺口描述**：使用者無法指定「公會名／插件指令／物品專有名詞必須譯成 X」。`ProtectedText` 只能「不翻」，沒有「指定譯法」的能力。

- **現狀**：
  - 對 `translator-core` 與 `platforms` 的 `*.java` 執行 `grep -ri "glossar|Glossar|術語|术语|termbase|termBase|customDict|詞典|词典|dictionary"` → **0 命中**。
  - 唯一相關的鍵是 `translator-core/src/main/java/org/universaltranslator/core/provider/OnlineProviderConfig.java:44` 的 `"youdao-vocab-id"`，它只被傳給 `translator-core/src/main/java/org/universaltranslator/core/provider/YoudaoTranslationProvider.java:61-63`（`fields.put("vocabId", vocabularyId)`）。那是**有道雲端的詞庫 id**，不是本地可編輯、可分享、跨 provider 生效的詞表；其餘 19 家 provider 沒有任何對應能力。
  - 「品質模式」裡唯一沾到術語的是一句泛泛要求：`translator-core/src/main/java/org/universaltranslator/core/TranslationPrompt.java:59-62` 的 `HIGH_STRICTNESS`（`Preserve the tone, register and terminology of the original, and translate repeated game terms identically every time`）。它要求「同一詞譯法一致」，但**沒有辦法告訴模型那個譯法是什麼**。
  - `translator-core/src/main/java/org/universaltranslator/core/ProtectedText.java`（338 行）只做佔位符保護：把不該翻的東西抽成 token 再還原，沒有「token → 指定譯文」的路徑。

- **為什麼算缺口**：這是同類產品的第一功能差異點。伺服器名稱（`Hypixel` → 常被譯成「超像素」）、插件前綴（`[Auction]`）、自訂物品（`Mythic Sword` → `神話之劍`）、公會名、玩家 ID 的固定譯法，全都只能靠術語表。DeepL、騰訊 TMT、阿里雲 MT、Volcengine 的 API 本來就有 glossary／術語庫參數（本專案一家都沒接），LLM 家族則有提示詞注入點卻沒用。玩家目前的變通辦法是把專有名詞塞進「不翻譯關鍵字」，結果是永遠看到原文 —— 與「翻譯」的期待相反。

- **最小可用實作路徑**：
  1. 新增 `translator-core/src/main/java/org/universaltranslator/core/Glossary.java`：讀 `config/universal-translator-glossary.properties`（`原文=譯文`，一行一條，UTF-8），提供 `Map<String,String> entries()`、`boolean matches(String)`、`String apply(String)`。沿用 `PersistentTranslationCache.java:9-15` 的 `Properties` + `Files.readAllLines` 寫法，避免新增依賴。
  2. LLM 路徑：`TranslationPrompt.build(...)` 在 `GUARDRAIL_SINGLE` / `GUARDRAIL_BATCH` **之前**插入一段 `Terminology (must be used verbatim): 原文 → 譯文; ...`（上限取 `UiLimits` 一個常數，例如 200 條，超過只取與本批文字有交集的條目）。
  3. MT 路徑：在 `TranslationRequest` 送出前把命中的條目換成 `ProtectedText` 風格的不可譯 token，回來後再替換成指定譯文 —— 這樣對 19 家 MT 一次生效，不必等各家 API 接 glossary。
  4. DeepL / 騰訊 TMT / 阿里 / Volcengine 的 glossary 參數當第二階段（`DeepLTranslationProvider.java:70` 的 `fields.put("text", ...)` 旁加 `glossary_id`）。
  5. 設定畫面加術語表分頁：列表 + 新增/刪除 + 匯入/匯出（可直接重用 FG-003 的 profile 檔案選擇器）。
- **涉及檔案**：新增 `translator-core/src/main/java/org/universaltranslator/core/Glossary.java`；改 `TranslationPrompt.java`、`TranslationRequest.java`、`ProtectedText.java`、`provider/OnlineProviderConfig.java`（`KEYS` 陣列 `:33-61` 加 glossary 相關鍵）、`provider/DeepLTranslationProvider.java`；9 個設定畫面 + `platforms/shared/lang/json/*.json`（新增 UI 文案鍵）
- **預估工作量**：M

---

### FG-003 · 沒有設定匯入／匯出，也沒有多設定檔 profile

- **嚴重度**：功能缺口 ｜ **信心**：高
- **缺口描述**：整份設定只能靠手動編輯 `config/universal-translator.properties` 搬移；同一台機器上無法為「中文服 / 英文服 / 單機」各留一套設定。

- **現狀**：
  - 對 `translator-core` 與 `platforms` 的 `*.java` 執行 `grep -ri "exportConfig|importConfig|ExportSettings|ImportSettings"` → **0 命中**。
  - 對 `grep -ri "profile|Profile"` → 命中的全是 Minecraft 的 `GameProfile`（例：`platforms/fabric/shared/runtime-common/src/client/java/org/universaltranslator/fabric/FabricTranslationRuntime.java:128` 的 `entry.getProfile().getName()`）與 `offline/OfflineProcessSupport.java:182` 的 Windows profile，**沒有一個是「設定檔 profile」**。
  - 設定只有單一檔：`platforms/fabric/shared/settings/modern/src/main/java/org/universaltranslator/fabric/FabricConfig.java:188` 附近（`TranslationStats.global().attach(file.resolveSibling(TranslationStats.FILE_NAME))`）可見 `FabricConfig` 就是綁著一個 `universal-translator.properties`；`LocalConfigSecurity.restrictToOwner(configFile)`（`:367`）只處理檔案權限，不處理多檔。
  - 唯一的「輸出」是診斷包，而且只有唯讀文字：`translator-core/src/main/java/org/universaltranslator/core/DiagnosticsBundleExporter.java:141-146` 的 `entryNames()` 只回 `environment.txt`、`diagnostics.txt`、`config.properties`、`debug.log`。

- **為什麼算缺口**：換電腦、多遊戲實例、把設定給朋友，都要重填 20 家 provider 的 endpoint + api key（`OnlineProviderConfig.java:33-61` 的 60 個鍵）。而且這個模組的核心使用情境天然是「多伺服器」：同一個玩家常同時玩中文服與英文服，需要的目標語言與 provider 完全不同，現在只能每次手動改。同類模組普遍有「設定檔」概念。

- **最小可用實作路徑**：
  1. 新增 `translator-core/src/main/java/org/universaltranslator/core/ConfigProfileStore.java`：把 `config/universal-translator.properties` 泛化成 `config/universal-translator/profiles/<name>.properties`，`active-profile` 記在 `config/universal-translator/active.txt`。`FabricConfig.load(Path dir)` / `ForgeConfig.load` / `LegacyConfig.load` 改成 `load(Path dir, String profile)`，其餘不動。
  2. 匯出／匯入就是 `Files.copy`；匯出前用既有的 `OnlineProviderConfig.effectiveSettingsLines(...)`（`DiagnosticsBundleExporter.java:114` 已有呼叫點）判斷哪些鍵是密鑰，提供「遮罩匯出」與「完整匯出」兩個選項。注意 `LocalConfigSecurity.restrictToOwner` 要套在新檔案上。
  3. 設定畫面加 profile 下拉 + 「另存為…」、「匯出…」、「匯入…」三個按鈕。共用邏輯抽到 `translator-core/.../SettingsSelectionList` 旁的新類，避免 9 個畫面各寫一次。
  4. 如果只想要最小版本：先做 export/import（單一 profile），profile 當第二階段。這樣成本降到 S。
- **涉及檔案**：新增 `translator-core/src/main/java/org/universaltranslator/core/ConfigProfileStore.java`；改 `platforms/fabric/shared/settings/{modern,legacy}/.../FabricConfig.java`、`platforms/forge/shared/common/.../ForgeConfig.java`、`platforms/forge/shared/legacy-common/.../LegacyConfig.java`、`translator-core/.../LocalConfigSecurity.java`、9 個設定畫面、`platforms/shared/lang/json/*.json`
- **預估工作量**：S（只做匯出/匯入）／M（含 profile）

---

### FG-004 · NeoForge 1.20.1–1.20.6 沒有 HUD 狀態指示器，設定卻預設開啟

- **嚴重度**：功能缺口 ｜ **信心**：高
- **缺口描述**：同一份 Forge 原始碼在 Forge 1.20.1 有 HUD 指示器，在 NeoForge 1.20.1 / 1.20.4 / 1.20.6 卻完全沒有；但三個版本的設定頁都照樣顯示「HUD 指示器：開」。

- **現狀**：
  - NeoForge 三份 mixin 設定的 `client` 清單都沒有 `InGameHudStatusIndicatorMixin`：
    - `platforms/neoforge/1.20.1/src/main/resources/universal-translator.neoforge.mixins.json`（10 個 mixin，grep `StatusIndicator` → 0 命中）
    - `platforms/neoforge/shared/metadata/1.20-1.20.4/src/main/resources/universal-translator.neoforge.mixins.json:6-17`（10 個，無）
    - `platforms/neoforge/shared/metadata/1.20.6/src/main/resources/universal-translator.neoforge.mixins.json:6-17`（10 個，無）
  - 對照組都有：`platforms/forge/modern/1.20.1/src/main/resources/universal-translator.forge.mixins.json:12`、`platforms/neoforge/shared/metadata/1.21/src/main/resources/universal-translator.neoforge.mixins.json:17`、`platforms/neoforge/shared/metadata/26.x/.../universal-translator.neoforge.mixins.json`。
  - 設定鍵存在且預設開啟：`platforms/forge/shared/common/src/main/java/org/universaltranslator/forge/ForgeConfig.java:99`（`properties.getProperty("hud-indicator", "true")`）、`:424`（預設寫入 `"true"`），而 NeoForge 用的正是 `ForgeConfig`（`platforms/neoforge/shared/entrypoints/common/src/main/java/org/universaltranslator/forge/UniversalTranslatorNeoForgeClient.java:40` 的 `ForgeConfig.load(...)`）。
  - 這三個版本都在已發布的 48 顆資產裡：`downloads/1.4/MCAutoTranslationTool-1.4-mc1.20.1-neoforge.jar`、`...-mc1.20.4-neoforge.jar`、`...-mc1.20.6-neoforge.jar`。

- **為什麼算缺口**：這是「同一能力只在部分載入器有」的教科書案例 —— 不是覆蓋範圍取捨，而是 3 顆已發布 JAR 上的功能缺損，且設定頁對使用者說了謊。成本極低（三行 JSON），是全部 10 條裡單位成本效益最高的。

- **最小可用實作路徑**：
  1. 在三份 JSON 的 `client` 陣列各加一行 `"InGameHudStatusIndicatorMixin"`（位置照 `metadata/1.21` 的第 17 行，放在 `TextRendererMixin` 之後）。
  2. 確認 mixin 類別真的在編譯路徑上：`platforms/neoforge/shared.gradle:124-141` 與 `platforms/neoforge/1.20/versions/1.20.4/build.gradle:15` 已把 `platforms/forge/modern/1.20.1` 掛進 `neoforgeForgeExtraRoots`；`platforms/neoforge/1.20.1/build.gradle:40-58` 是手寫 `java.srcDirs`，需確認其中也含 `platforms/forge/shared/hud-indicator`（或對應層）。若不在，補一行。
  3. 補一個 CI 檢查（可放 `build.gradle` 的 `validateSharedPlatformLayouts` 旁）：每個 neoforge metadata 層的 `client` 清單必須是 `platforms/forge/modern/1.20.1` 那份的超集或明示排除清單，避免下次再漏。
- **涉及檔案**：`platforms/neoforge/1.20.1/src/main/resources/universal-translator.neoforge.mixins.json`、`platforms/neoforge/shared/metadata/1.20-1.20.4/src/main/resources/universal-translator.neoforge.mixins.json`、`platforms/neoforge/shared/metadata/1.20.6/src/main/resources/universal-translator.neoforge.mixins.json`、`platforms/neoforge/1.20.1/build.gradle`、`build.gradle`
- **預估工作量**：S

---

### FG-005 · 持久化快取是黑箱：不能檢視、不能從 UI 清除、不能匯出

- **嚴重度**：功能缺口 ｜ **信心**：高
- **缺口描述**：使用者付費／用本地模型翻出來的譯文存在一個只能整體刪檔的檔案裡；看不到有多少條、不能清掉某一條、也不能匯出重用。

- **現狀**：
  - `translator-core/src/main/java/org/universaltranslator/core/TranslationStore.java` 整個介面只有 `get`、`put`、`clear`、`flush` 四個方法，**沒有 `size()`、沒有列舉、沒有 `remove(key)`**。
  - 對 `platforms` 的 `*.java` grep `store.clear` / `cache.clear` → 命中的全是 UI 自己的 `buttons.clear()` / `buttonList.clear()`（例：`platforms/fabric/1.13/shared/screens/src/main/java/org/universaltranslator/fabric/UniversalTranslatorConfigScreen.java:251`、`platforms/forge/shared/legacy-common/src/main/java/org/universaltranslator/forge/legacy/LegacyConfigScreen.java:256`）。**沒有任何畫面呼叫 `TranslationStore.clear()`**，也就是說快取沒有任何 UI 入口，只能手動刪檔。
  - 持久化鍵是雜湊，不是原文：`translator-core/src/main/java/org/universaltranslator/core/PersistentTranslationCache.java:17-18`（`Small disk cache that hashes source/cache keys before persistence`）、`:68-80`（建構子只收 `Path` 與 `maximumEntries`，內部是 `LinkedHashMap<String,String>` 的 LRU）。因此現有檔案**無法**當翻譯記憶讀出。
  - `PersistentTranslationCache.size()` 存在（`:29-30` 的 javadoc 提到），但 `TranslationStore` 沒宣告、`platforms` 也沒呼叫。
  - 診斷包不含快取：`translator-core/src/main/java/org/universaltranslator/core/DiagnosticsBundleExporter.java:141-146` 的 `entryNames()` 只有 `environment.txt`、`diagnostics.txt`、`config.properties`、`debug.log`。

- **為什麼算缺口**：快取是這個模組省錢的核心機制，卻完全不可觀測。三個實際痛點：(a) 換 provider 或改目標語言後，舊譯文仍持續命中（鍵是雜湊，看不出是哪一句）；(b) 翻錯的句子無法只清那一條，只能全部重來；(c) 累積幾千條譯文無法匯出成資源包或分享（與 FG-010 直接相關）。同類的 TranslationCache 把快取當成第一級資產，有預覽、匯出、匯入。

- **最小可用實作路徑**：
  1. `TranslationStore` 加三個預設方法（保持既有實作可編譯）：`default int size() { return -1; }`、`default Map<String,String> snapshot() { return Collections.emptyMap(); }`、`default void remove(String key) { }`；`PersistentTranslationCache` 覆寫三者。
  2. 讓雜湊鍵可回溯：`PersistentTranslationCache.put(key, value)` 額外寫一份 `universal-translator-cache.tsv`（`原文<TAB>譯文<TAB>kind`），放在主快取檔旁。寫入沿用既有的 `flushLoop` 批次策略（`UNSAVED_ENTRIES_BEFORE_FLUSH = 64`、`FLUSH_INTERVAL_MILLIS = 5000`，`PersistentTranslationCache.java:36-38`），不要另開 thread。
  3. 設定畫面第 5 Tab：`快取 N 條 / 上限 M`、`清除全部`、`匯出 TSV`、`匯入 TSV`。清除要同時清 in-memory 與檔案（`clear()` 已把 `loaded` 設為 true 防止讀回，見 `:44-49`）。
  4. `DiagnosticsBundleExporter.entryNames()` 加 `cache.tsv`，讓回報問題時能附上實際譯文。
- **涉及檔案**：`translator-core/src/main/java/org/universaltranslator/core/{TranslationStore,PersistentTranslationCache,TranslationCache,DiagnosticsBundleExporter}.java`、9 個設定畫面、`platforms/shared/lang/json/*.json`
- **預估工作量**：M

---

### FG-006 · 整個模組沒有任何客戶端指令

- **嚴重度**：功能缺口 ｜ **信心**：高
- **缺口描述**：無法用 `/ut …` 做任何事；所有操作都必須開 GUI 或用實體按鍵。

- **現狀**：
  - 對 `platforms` 的 `*.java` grep `CommandRegistrationCallback|RegisterCommandsEvent|ClientCommandRegistration|CommandDispatcher|registerCommand` → **0 命中**。
  - 對 `platforms` 的 `*.java` grep `brigadier` → **0 命中**。
  - 唯一的輸入面是每個平台各一份的 KeyBinding，且只有 **2 個**：`platforms/neoforge/shared/entrypoints/common/src/main/java/org/universaltranslator/forge/UniversalTranslatorNeoForgeClient.java:21-30`（`key.universal_translator.open_settings` 預設 U、`key.universal_translator.toggle_translation` 預設 F8）；Fabric 對應在 `platforms/fabric/shared/client-1.20-1.21/src/client/java/org/universaltranslator/fabric/UniversalTranslatorFabricClient.java`。

- **為什麼算缺口**：指令是「不打斷遊玩節奏」的唯一手段。實際需要而現在沒有的：`/ut off`（單一伺服器臨時關閉，不必開 GUI 點三層）、`/ut status`（現在用哪家 provider、這分鐘幾次請求、快取命中率 —— 資料都已在 `TranslationStats` 裡）、`/ut retranslate`（快取汙染後重翻畫面）、`/ut test`（不開設定頁驗證金鑰）、`/ut cache clear|export`（FG-005 的 CLI 面）。三人以上共用伺服器時，這也讓玩家能把指令寫進巨集／腳本。同類模組普遍提供 `/transconfig` 這類指令。

- **最小可用實作路徑**：
  1. 新增 `translator-core/src/main/java/org/universaltranslator/core/command/TranslationCommandTree.java`：**不依賴任何 loader API**，輸入是 `String[] args`，輸出是 `CommandOutcome { boolean handled; String message; }`。這樣 20 個平台版本只寫一次分派邏輯（符合專案「translator-core 以 srcDir 共用」的既有模式）。
  2. 各平台入口只做註冊與轉發：
     - Fabric 1.14+：`ClientCommandRegistrationCallback.EVENT.register(...)`
     - Fabric 1.0–1.13.2：`ClientCommandHandler` / `ClientCommandManager`（依版本，放在 `platforms/fabric/shared/runtime-legacy`）
     - Forge / NeoForge 1.16.5+：`RegisterClientCommandsEvent`
     - Forge 1.8.9 / 1.12.2：`ClientCommandHandler.instance.registerCommand`
  3. 子指令第一版只要 5 個：`on`、`off`、`toggle`、`status`、`reload`。`cache` 與 `test` 等 FG-005 完成後再接。
  4. 回應文字沿用 `LogMessages` / `UiTextKeys` 的既有鍵，避免各平台各寫一份字串。
- **涉及檔案**：新增 `translator-core/src/main/java/org/universaltranslator/core/command/{TranslationCommandTree,CommandOutcome}.java`；改各平台 client 入口（Fabric 8 份 `UniversalTranslatorFabricClient.java`、Forge `ForgeClientEvents`/`LegacyClientEvents`、NeoForge 7 份 `UniversalTranslatorNeoForgeClient.java`）、`platforms/shared/lang/json/*.json`
- **預估工作量**：S

---

### FG-007 · Forge／NeoForge 沒有模組清單設定入口（只有 Fabric 有 Mod Menu）

- **嚴重度**：功能缺口 ｜ **信心**：高
- **缺口描述**：Fabric 玩家可以在 Mod Menu 的模組清單裡點齒輪開設定；Forge 與 NeoForge 玩家只能靠標題畫面按鈕或盲按 U。

- **現狀**：
  - Mod Menu 整合只存在於 Fabric：`platforms/fabric/modmenu/integration/mojmap/src/client/java/org/universaltranslator/fabric/ModMenuIntegration.java`、`platforms/fabric/modmenu/integration/yarn/src/client/java/org/universaltranslator/fabric/ModMenuIntegration.java`、`platforms/fabric/modmenu/api/{mojmap,yarn}/src/main/java/com/terraformersmc/modmenu/api/ModMenuApi.java`。
  - `fabric.mod.json` 只有 Fabric 樹宣告 `modmenu` 入口：例 `platforms/fabric/1.21/versions/1.21/src/main/resources/fabric.mod.json:20`、`platforms/fabric/26.x/shared/common/src/main/resources/fabric.mod.json:20`、`platforms/fabric/legacy/shared/common/src/main/resources/fabric.mod.json:19`（`${modmenuEntrypoint}`）。
  - 對 `platforms` 的 `*.java` grep `IConfigScreenFactory|ConfigScreenHandler|registerExtensionPoint|IExtensionPoint` → **0 命中**。Forge／NeoForge 沒有等價的擴充點註冊。
  - `README.md:38` 卻寫「从模组列表打开（若安装 Mod Menu）」，沒有說明那只有 Fabric 有效。

- **為什麼算缺口**：這是三個載入器能力矩陣裡最容易被使用者發現的一格。Forge／NeoForge 玩家裝了 30 個模組，找設定的第一反應就是去模組清單點；找不到就只能猜按鍵（而 U 是很常被佔用的鍵，見 BUG-S-053 的硬編碼問題）。實作成本極低：Forge 1.20+ 與 NeoForge 都有現成的 `IConfigScreenFactory`（`ModListScreen` 會自動顯示齒輪），1.16.5–1.19.2 用 `ConfigScreenHandler.ConfigScreenFactory`。

- **最小可用實作路徑**：
  1. Forge／NeoForge 1.20+：在 `platforms/neoforge/shared/entrypoints/common/src/main/java/org/universaltranslator/forge/UniversalTranslatorNeoForgeClient.java` 與對應的 Forge 入口，於 `FMLClientSetupEvent` 期間 `ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class, () -> (mc, parent) -> new UniversalTranslatorConfigScreen(parent, ForgeConfig.load(FMLPaths.CONFIGDIR.get())))`。
  2. Forge 1.16.5–1.19.2：`ConfigScreenHandler.ConfigScreenFactory`（`platforms/forge/modern/*`、`platforms/forge/shared/modern-1.18-1.19`）。
  3. Forge 1.8.9 / 1.12.2：這一代沒有官方擴充點，跳過並在文件註明。
  4. 這條路徑與 `openFromTitle`（`UniversalTranslatorNeoForgeClient.java:119-130`）共用同一個「開設定」方法，不要另寫一份。
- **涉及檔案**：`platforms/neoforge/shared/entrypoints/{common,1.20.2-1.20.4,1.21.2-1.21.8,1.21.9-1.21.11,26.1-26.1.2,26.2}/src/main/java/org/universaltranslator/forge/UniversalTranslatorNeoForgeClient.java`、`platforms/forge/{modern/1.16.5,modern/1.18.2,modern/1.19.2,shared/modern-1.18-1.19,1.21/shared/common,26.x/shared/common}/.../ForgeClientEvents.java`、`README.md:38` 與三語 docs
- **預估工作量**：S

---

### FG-008 · 批次合併只存在於 OpenAI 家族，其餘 14 家逐條送出（`batch-size` 對它們無效但 UI 仍顯示）

- **嚴重度**：功能缺口 ｜ **信心**：高
- **缺口描述**：一次畫面刷新有 40 行記分板時，OpenAI 家族用 5 個請求翻完，Gemini／Claude／DeepL／LibreTranslate／8 家雲 MT 要 40 個請求。

- **現狀**：
  - 只有 OpenAI 家族有批次路徑：`translator-core/src/main/java/org/universaltranslator/core/provider/OpenAiChatTranslationProvider.java:311`、`:575`（`translateBatched`）、`:702`（`translateBatch`，JSON 按行號回覆）。
  - Gemini 與 Claude 在原始碼裡自承沒有批次路徑：`translator-core/src/main/java/org/universaltranslator/core/provider/GeminiTranslationProvider.java:263`（`Gemini has no batch path yet.`）、`translator-core/src/main/java/org/universaltranslator/core/provider/ClaudeTranslationProvider.java:254`（`Claude has no batch path yet.`）。
  - `translator-core/src/main/java/org/universaltranslator/core/TranslationRequest.java` 沒有 batch 欄位（單筆文字 + 來源/目標語言 + kind + 保護字面量）。
  - 批次上限 16 只在 OpenAI 家族生效：`translator-core/src/main/java/org/universaltranslator/core/provider/TranslationTuning.java:28`。
  - UI 卻對所有引擎顯示批次按鈕：`platforms/fabric/shared/screens-shared/src/client/java/org/universaltranslator/fabric/UniversalTranslatorSettingsScreen.java:681`（`batchSizeButton.visible = isEngine;`，不判 `llm`）。

- **為什麼算缺口**：Gemini 與 Claude 都支援 JSON 模式與 system prompt，缺的只是把 `OpenAiChatTranslationProvider` 已經寫好的「按行號 key 的 JSON 契約」複製一份。不做的代價是 8 倍請求數（40 vs 5），直接反映在延遲（每次往返 300–800ms）與 API 帳單上；而 `batch-size` 這個設定在 Gemini／Claude 上完全是死設定。`TranslationPrompt.java:70-77` 的 `BATCH_JSON_SHAPE` 已經把協議寫成與 provider 無關的形式，重用成本很低。

- **最小可用實作路徑**：
  1. 把 `OpenAiChatTranslationProvider` 的批次協議抽成 `translator-core/src/main/java/org/universaltranslator/core/provider/BatchPrompt.java`：`String build(List<String> lines, target)`（重用 `TranslationPrompt.BATCH_JSON_SHAPE`）+ `Map<Integer,String> parse(String reply, int expected)`（重用既有的 `ProviderJson` / `JsonStrings`）。
  2. `GeminiTranslationProvider` 與 `ClaudeTranslationProvider` 覆寫 `translateBatched`（`TranslationProvider` 目前沒有這方法，可在 `provider/ProviderSupport.java` 加一個 default 轉呼叫逐條）。Gemini 的 `responseMimeType: application/json` 與 Claude 的 `tool_use`／前置 JSON 指令都能直接吃同一份 prompt。
  3. 語音/雲 MT 那 12 家不追（API 本身沒有多筆介面，或像 Volcengine 的 `TextList` 只有半套 —— `translator-core/src/main/java/org/universaltranslator/core/provider/VolcengineMachineTranslationProvider.java:63` 只放一個元素、`:68` 只讀 `TranslationList[0]`）。
  4. `batchSizeButton.visible` 改成 `isEngine && TranslationProviderCatalog.supportsBatch(provider)`，與 FG-009 同一種改法（共用一個 catalog 判定方法）。
- **涉及檔案**：新增 `translator-core/src/main/java/org/universaltranslator/core/provider/BatchPrompt.java`；改 `provider/{OpenAiChatTranslationProvider,GeminiTranslationProvider,ClaudeTranslationProvider,ProviderSupport,TranslationTuning}.java`、`core/TranslationProvider.java`、`core/TranslationProviderCatalog.java`、9 個設定畫面
- **預估工作量**：M

---

### FG-009 · 「品質模式」與「自訂系統提示詞」對 12 家非 LLM provider 完全無效，UI 卻是無條件可見的死控件

- **嚴重度**：功能缺口 ｜ **信心**：高
- **缺口描述**：選百度／騰訊／DeepL／LibreTranslate 的使用者，把品質切到 High、寫一段 500 字自訂提示詞，值存得進設定檔、看起來生效，實際上一個字都沒送出去。

- **現狀**：
  - `promptSettings()` 只被傳給 4 個地方：`translator-core/src/main/java/org/universaltranslator/core/provider/OnlineProviderConfig.java:521`（Azure OpenAI）、`:527`（Gemini）、`:530`（Claude）、`:553`（OpenAI 家族 5 個 id）。
  - 沒有傳的：`:481`（LibreTranslate）、`:486`（Baidu）、`:495`（Youdao）、`:523`（DeepL）；8 家雲 MT（Tencent TMT／Hunyuan、Aliyun、Volcengine MT、iFlytek、Huawei）在建構子裡連 `promptSettings` 參數都沒有。
  - `DeepLTranslationProvider.java:21-23` 的註解直接說明它沒有 system prompt（`...the inherited translateStreaming — one callback carrying the finished...`，同段說明 DeepL 的協議沒有增量形式）。
  - UI 對所有引擎顯示：`platforms/fabric/shared/screens-shared/src/client/java/org/universaltranslator/fabric/UniversalTranslatorSettingsScreen.java:679`（`qualityButton.visible = isEngine;`）、`:682`（`customPrompt.visible = isEngine;`）、`:683`（`applyPromptButton.visible = isEngine;`）。對照同一段的 `:671-673` 有正確判 `llm`（`llmConfigButton`/`modelPickButton`/`fetchModelsButton` 都是 `isEngine && llm`），證明缺少的是同一種判定。
  - 同一段邏輯在 9 個畫面各有一份，例如 `platforms/forge/shared/screens/1.18.2-1.21.11/src/main/java/org/universaltranslator/forge/UniversalTranslatorScreens.java`（與 Fabric 那份同構）。
  - 另有同一家族的第二個死控件：`tuning()` 只在 `OnlineProviderConfig.java:554` 傳給 OpenAI 家族，Azure 在 `:519-521` 沒傳 → Azure 的 `batch-size`／`translation-workers`／`batch-window-ms` 也是無效設定。

- **為什麼算缺口**：這是「設定頁對使用者說謊」的純 UX 缺口，成本極低（9 個畫面各改 3 行的 `visible` 條件 + 一個 catalog 判定），但直接造成「我設了卻沒用」的信任損失。而且它會把使用者推向錯誤的疑難排解方向（以為是 API 壞了）。同類模組要嘛不顯示這些選項，要嘛標「此引擎不支援」。

- **最小可用實作路徑**：
  1. 在 `translator-core/src/main/java/org/universaltranslator/core/TranslationProviderCatalog.java` 加兩個判定，樣式照已有的 `:77-85` 的 `usesLlmEditor`：
     - `public static boolean usesPrompt(String provider)` → 回傳 `usesLlmEditor(provider)`（Azure／DeepL 要注意：DeepL 有 `deepl-model` 鍵但沒有 prompt，所以不能直接沿用 `usesLlmEditor`，需顯式排除 deepL）。
     - `public static boolean usesTuning(String provider)` → 只有 OpenAI 家族（排除 azure-openai，除非同時修 `OnlineProviderConfig.java:519-521` 補上 `tuning()`）。
  2. 9 個設定畫面把 `:679`/`:682`/`:683` 改成 `isEngine && usesPrompt(provider)`，`:680`/`:681` 改成 `isEngine && usesTuning(provider)`。
  3. 若決定不隱藏（保留可點但顯示說明），第二選擇是在按鈕 label 後加灰字 `(此引擎不支援)`；需要 `platforms/shared/lang/json/*.json` 加鍵。
  4. 順手修 Azure：`OnlineProviderConfig.java:519-521` 加上 `tuning()` 參數（這是同一根因的另一面，一起改）。
- **涉及檔案**：`translator-core/src/main/java/org/universaltranslator/core/TranslationProviderCatalog.java`、`translator-core/src/main/java/org/universaltranslator/core/provider/OnlineProviderConfig.java:519-554`；9 個設定畫面（`platforms/fabric/shared/screens-shared/...`、`platforms/fabric/{1.0-1.12,1.13,1.14-1.15,legacy,1.19,1.21,26.x}/...`、`platforms/forge/{shared/screens/1.18.2-1.21.11,modern/1.16.5,26.x/shared/common,shared/legacy-common}/...`）
- **預估工作量**：S

---

### FG-010 · 不攔截 Minecraft 語言管理器（`I18n` / `en_us.json`），也不能匯出翻譯資源包

- **嚴重度**：功能缺口 ｜ **信心**：高
- **缺口描述**：這個模組只在「畫到螢幕上的那一刻」翻譯。所有走 `Language.getInstance()` / `I18n.translate()` 的鍵值文字完全不在管線裡，翻譯結果也無法沉澱成資源包。

- **現狀**：
  - 對 `translator-core` 與 `platforms` 的 `*.java` grep `Language.getInstance|LanguageManager|ClientLanguage` → **0 命中**。沒有任何 mixin 或事件攔截語言管理器。
  - `I18n` 只以「讀自己的 UI key」的形式出現三處，不是攔截：`platforms/fabric/1.0-1.8/shared/common/src/main/java/org/universaltranslator/fabric/mixin/HudIndicatorOverlay.java:118`、`platforms/fabric/1.13/shared/common/src/main/java/org/universaltranslator/fabric/mixin/InGameHudStatusIndicatorMixin.java:140`、`platforms/fabric/1.0-1.8/shared/common/src/main/java/org/universaltranslator/fabric/OrnitheClientAccess.java:42-47`（反射呼叫 `I18n.translate`）。
  - 對 `translator-core` 與 `platforms` 的 `*.java` grep `ResourcePack|resourcepack|ResourceManager` → **0 命中**。整個 repo 沒有產生任何 `assets/*/lang/*.json`。
  - 現有唯一的「輸出」是診斷 zip（`translator-core/src/main/java/org/universaltranslator/core/DiagnosticsBundleExporter.java:141-146`，4 個純文字/屬性檔），與翻譯資產無關。
  - 攔截面完全依賴渲染 hook：`platforms/fabric/shared/draw-context/src/client/java/org/universaltranslator/fabric/mixin/DrawContextMixin.java:15-62`（7 個 `drawText`/`drawTooltip`/`drawCentered*` 的 `ModifyVariable`）與各版本 `TextRendererMixin`（例 `platforms/fabric/1.21/shared/text-renderer/1.21.11/src/client/java/org/universaltranslator/fabric/mixin/TextRendererMixin.java:11`，註解寫 `Captures world-space text such as nameplates, holograms, signs and display entities`）。

- **為什麼算缺口**：這是相對全部同類產品最大的一格。TranslationCache / i18nUpdateMod / ModTranslater 的**核心做法**就是掃 `mods/*.jar` 裡的 `assets/<modid>/lang/en_us.json`、翻譯後產生一個資源包 —— 好處是 (a) 永久生效、不必每幀查快取；(b) 零 API 成本（一次性）；(c) 涵蓋渲染層抓不到的鍵值文字。而這個模組抓不到的具體場景：
    - 其他模組的設定畫面把 `I18n.translate("...")` 的結果塞進 `String[]` 或比較/排序邏輯，不經 `DrawContext`；
    - JEI / REI / EMI 的物品搜尋索引與分類名；
    - 指令自動補全的候選字（`CommandSuggestions`）；
    - 伺服器 MOTD、伺服器清單的 `ping` 描述（在多人選單，走 `ServerInfo` 而非 `DrawContext`）；
    - 資源包／資料包自帶的 `lang` 檔（本模組自家的 `platforms/shared/lang/json/*.json` 就是例子）。
  對玩家來說，表現就是「有些地方的英文永遠翻不到」，而且無法預期哪裡會翻哪裡不會。

- **最小可用實作路徑**（分三階段，每階段都能獨立出貨）：
  1. **攔截層**（成本最低、涵蓋 80% 場景）：新增 `platforms/<loader>/<version-family>/.../mixin/LanguageMixin.java`，`@Mixin(Language.class)` 攔 `getOrDefault(String)`（1.13+），回傳前把結果丟給 `RenderedTextBridge.translate(...)`；1.0–1.12 走 `net.minecraft.locale.Language`／`StatCollector` 的對應方法（`platforms/forge/shared/legacy-common/.../FontRendererTransformer.java` 已有 1.8.9/1.12.2 的 ASM transformer 樣板可沿用）。要小心遞迴：只譯「非本模組命名空間」的 key。
  2. **語言檔掃描**：新增 `translator-core/src/main/java/org/universaltranslator/core/LangFileTranslator.java`：列舉 `mods/*.jar`（沿用 `translator-core/.../offline/VerifiedDownloader.java` 與 `SafeArchiveExtractor.java` 既有的 zip 讀取與路徑校驗），找出 `assets/*/lang/en_us.json`，用既有的 `JsonStrings` 解析、走 `TranslationCoordinator`（重用 `ProtectedText`、`TranslationStore` 快取），輸出到 `config/universal-translator/generated-resources/`。
  3. **資源包匯出**：新增 `translator-core/src/main/java/org/universaltranslator/core/ResourcePackExporter.java`：寫 `pack.mcmeta`（`pack_format` 可由各平台的 `pack.mcmeta` 現值取得）＋ `assets/*/lang/zh_cn.json`，輸出 `resourcepacks/UniversalTranslatorGenerated.zip`。與 FG-005 的 TSV 匯出共用同一份快取快照。
  4. 設定畫面加一個「產生資源包」按鈕（放第 5 Tab，與 FG-005 同頁）。
- **涉及檔案**：新增 `translator-core/src/main/java/org/universaltranslator/core/{LangFileTranslator,ResourcePackExporter}.java`；新增各版本 `.../mixin/LanguageMixin.java`（至少 Fabric 1.13+/1.16+/1.20+/1.21+/26.x、Forge modern/1.21/26.x、NeoForge 借用 Forge 層）；改各 `universal-translator.*.mixins.json` 的 `client` 清單；改 9 個設定畫面、`platforms/shared/lang/json/*.json`
- **預估工作量**：L

---

### BUG-S-051 · 1.0–1.8 bundle 宣告 38 個 Minecraft 版本，只打包 26 個實作

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`platforms/fabric/1.0-1.8/bundle/src/main/resources/fabric.mod.json:134-173` vs `platforms/fabric/1.0-1.8/bundle/build.gradle:23`
- **受影響範圍**：`platform-fabric-1.0-1.8.x` bundle（不在 `downloads/1.4` 的 48 顆發布資產裡，只有自建或 `legacy-targets.yml` 排程會產出）
- **現象**：bundle 的 `depends.minecraft` 陣列列出 38 個版本（`1.0.0`、`1.0.1`、`1.1`、`1.2.1`–`1.2.5`、`1.3.1`、`1.3.2`、`1.4.2`、`1.4.4`–`1.4.7`、`1.5.1`、`1.5.2`、`1.6.1`、`1.6.2`、`1.6.4`、`1.7.2`、`1.7.3`、`1.7.4`、`1.7.5`、`1.7.6`、`1.7.7`、`1.7.8`、`1.7.9`、`1.7.10`、`1.8`–`1.8.8`），但 `jars` 陣列（`:16-131`）與 `build.gradle:23` 的 `supportedVersions` 都只有 26 個。
- **觸發條件**：在 `1.0.0`／`1.0.1`／`1.1`／`1.2.1`–`1.2.5`／`1.6.2`／`1.7.3`／`1.7.5`／`1.7.7` 這 12 個版本上安裝這個 bundle。Fabric Loader 依 `depends.minecraft` 判定相容、載入外層 jar，但沒有任何 nested jar 的版本範圍命中，於是 `depends` 裡的 `"universal_translator": "*"` 永遠無法滿足。
- **根因**：`settings.gradle:16-20` 的註解說明這 12 個版本因上游 mappings 問題被排除，但 `fabric.mod.json` 的 `minecraft` 清單沒有跟著縮，而 `verifyBundle`（`build.gradle:41-50`）只驗 26 個 nested jar 是否存在，不驗「宣告的版本都有對應實作」。這正是 `AGENTS.md` 記過的「靜默略過」家族問題的殘留。
- **影響**：這 12 個版本上會從「模組載入但沒功能」變成「Fabric Loader 直接拒絕啟動並報 missing dependency」，對使用者是硬失敗。因為這顆 bundle 不在正式資產裡，實際受害者僅限自建者，故列為小。
- **修法**：把 `fabric.mod.json:134-173` 的 `minecraft` 陣列縮成與 `build.gradle:23` 完全相同的 26 個；並在 `verifyBundle` 加一條「`minecraft` 清單 == `supportedVersions`」的比對。

```groovy
// platforms/fabric/1.0-1.8/bundle/build.gradle
tasks.register("verifyBundle") {
    doLast {
        def manifest = new groovy.json.JsonSlurper().parse(
                file("src/main/resources/fabric.mod.json").text)
        def declared = manifest.depends.minecraft as List
        if (declared != supportedVersions) {
            throw new GradleException(
                "fabric.mod.json declares ${declared.size()} versions, "
                + "supportedVersions has ${supportedVersions.size()}")
        }
        // ...既有的 nested jar 檢查
    }
}
```

---

### BUG-S-052 · 生產程式碼 26 處直接 `System.out/err.println`，繞過 `DebugLog` 與 `debug-log` 設定

- **嚴重度**：小 ｜ **信心**：高
- **位置**（全部位於生產 source set，非 `src/test`）：
  - `translator-core/src/main/java/org/universaltranslator/core/RenderTranslationSession.java:564`
  - `translator-core/src/main/java/org/universaltranslator/core/TranslationCoordinator.java:404`
  - `platforms/fabric/1.0-1.8/shared/common/src/main/java/org/universaltranslator/fabric/OrnitheClientAccess.java:88`
  - `platforms/fabric/1.14-1.15/shared/family/1.14-1.15-common/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:47,80`
  - `platforms/fabric/1.16/shared/family/1.16-1.16.1/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:76,109`
  - `platforms/fabric/1.20/shared/family/1.20.1/src/client/java/org/universaltranslator/fabric/ChatImageCompatibility.java:37`
  - `platforms/fabric/26.x/shared/common/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:160,193`
  - `platforms/fabric/legacy/shared/rendered/1.16.5/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:160,193`
  - `platforms/fabric/shared/rendered-common/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:159,192`
  - `platforms/forge/modern/1.18.2/src/main/java/org/universaltranslator/forge/RenderedTextBridge.java:162,195`
  - `platforms/forge/shared/rendered-1.19.2-plus/src/main/java/org/universaltranslator/forge/RenderedTextBridge.java:106,139`
  - `platforms/forge/shared/legacy-common/src/main/java/org/universaltranslator/forge/legacy/{LegacyRenderedTextBridge.java:45,73, FontRendererTransformer.java:47,51,55, LegacyClientEvents.java:86,165,179}`
- **受影響範圍**：所有平台；`RenderedTextBridge` 那 6 份是最常被觸發的
- **現象**：遊戲日誌（`latest.log`）會出現 `[MC Auto Translation Tool] Item tooltip producer reached` / `... translation applied` / `provider=... status` 這類沒有級別、沒有時間戳前綴的行；`FontRendererTransformer` 的 transformation 成敗也走 stdout。
- **觸發條件**：第一次 hover 任何有 tooltip 的物品（`AtomicBoolean` 一次性，所以每次啟動各一次）；每次 HUD 狀態字串變更；每次翻譯 worker 未在時限內停止（`TranslationCoordinator.java:404`）；1.8.9/1.12.2 的 FontRenderer transformation。
- **根因**：`translator-core/src/main/java/org/universaltranslator/core/DebugLog.java` 與各平台的 `LOGGER` 已經存在且受 `debug-log` 設定控制（`OnlineProviderConfig.java:37` 的 `debug-log` 鍵），但這 26 處沒走它。`System.out` 不經 SLF4J，所以 `log4j`/`latest.log` 的過濾與 `debug-log=off` 完全管不到。
- **影響**：使用者關不掉；回報問題時這些無級別行會混在崩潰堆疊裡，增加判讀成本；Forge 的 `System.out` 在某些啟動器（HMCL、PCL2 的日誌檢視）會被當成錯誤行高亮。
- **修法**：26 處全部換成各平台的 `DebugLog.get().line(...)` 或對應 `LOGGER.debug(...)`，並把 `RenderedTextBridge` 裡那兩條 println 降級為 `debug`（它們本來就只是開發期的確認訊息，`AtomicBoolean` 已保證只印一次，現在是無條件印）。

```java
// 例：platforms/fabric/shared/rendered-common/.../RenderedTextBridge.java
- System.out.println("[MC Auto Translation Tool] Item tooltip producer reached");
+ DebugLog.get().debug("item-tooltip", "producer reached");
```

---

### BUG-S-053 · Fabric 1.0–1.13.2 的快捷鍵是硬編碼 GLFW 輪詢，無法在按鍵設定改鍵或解綁

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`platforms/fabric/1.13/shared/common/src/main/java/org/universaltranslator/fabric/mixin/MinecraftMixin.java:19-37`（關鍵行 `:26-27`）；同構檔案在 `platforms/fabric/1.13/shared/legacy-1.13.2/src/main/java/org/universaltranslator/fabric/mixin/MinecraftMixin.java`、`platforms/fabric/1.0-1.8/shared/common/.../MinecraftMixin.java`、`platforms/fabric/1.8-1.12/shared/common/.../MinecraftMixin.java`
- **受影響範圍**：Fabric 1.3.1–1.13.2。其中 `1.13.x` 在 `downloads/1.4/MCAutoTranslationTool-1.4-mc1.13.x-fabric.jar.part-aa` 之後有正式發布
- **現象**：`U`（開設定）與 `F8`（切換翻譯）固定綁死。MC 原生「按鍵設定」畫面裡看不到這兩個動作，玩家不能改鍵、不能設第二順位、不能解綁。
- **觸發條件**：任何與 `U` 衝突的模組（`U` 在 1.7–1.13 時代是相當常見的自訂鍵），或使用非 QWERTY 鍵盤／需要 IME 的玩家。
- **根因**：

```java
// platforms/fabric/1.13/shared/common/src/main/java/org/universaltranslator/fabric/mixin/MinecraftMixin.java:26-27
boolean u = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_U) == GLFW.GLFW_PRESS;
boolean f8 = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F8) == GLFW.GLFW_PRESS;
```

  直接讀 GLFW 硬體狀態，完全繞過 `GameOptions.keysAll` / `KeyBinding`。1.14+ 已改用 `KeyBindingHelper.registerKeyBinding`（見 `platforms/fabric/shared/client-1.14-1.16.4/src/client/java/org/universaltranslator/fabric/UniversalTranslatorFabricClient.java:19-32`），所以是純粹的版本落差。
- **影響**：`1.13.x` 使用者無從得知有快捷鍵（`README.md:38` 只寫 `按 U`，等於說明「就是固定 U」）；與其他模組撞鍵時只能卸載其中一個。
- **修法**：1.13.x 的 Fabric API 已提供 `KeyBindingHelper`（`net.fabricmc.fabric.api.client.keybinding.v1`），改用與 1.14+ 相同寫法並在 `ClientTickEvents.END_CLIENT_TICK` 消費 `wasPressed()`；1.0–1.12 沒有 Fabric API 的 keybinding 模組，改用 `GameSettings` 的 `KeyBinding` 陣列註冊（Ornithe mappings 下 `net.minecraft.client.options.KeyBinding`），保留 GLFW 輪詢只作 fallback。

---

## 已排查但判定無問題

1. **用量統計持久化** — 有完整實作且有 UI。`translator-core/src/main/java/org/universaltranslator/core/TranslationStats.java:37`（`FILE_NAME`）、`:41`（`FLUSH_INTERVAL_MILLIS = 60_000`）、`:103-115`（`attach` 讀一次並啟動背景 flusher）、`:184-222`（解析 `usage.prompt_tokens` / `input_tokens` / `completion_tokens` / `output_tokens`）；檢視與重置在 `platforms/fabric/1.21/shared/screens/1.20.1-1.21.11/src/client/java/org/universaltranslator/fabric/UniversalTranslatorDiagnosticsScreen.java:181`（`localizedLines`）與 `:137`（`reset`）。判定非缺口。

2. **A/B（原文 + 譯文）對照顯示** — 已存在。`translator-core/src/main/java/org/universaltranslator/core/TranslationDisplayMode.java:5-28` 提供 `TRANSLATED_ONLY` 與 `ORIGINAL_AND_TRANSLATED`（設定值 `bilingual`）。判定非缺口。

3. **自訂 base URL** — 19 家線上 provider 中 18 家有 `*-endpoint` 鍵（`translator-core/src/main/java/org/universaltranslator/core/provider/OnlineProviderConfig.java:34,41-60`，涵蓋 libreTranslate、baidu、tencent-tmt、aliyun、youdao、volcengine、iflytek、huawei、5 家 LLM、azure、deepl、gemini、claude、custom-http-json）。唯一例外是 `tencent-hunyuan`（`translator-core/src/main/java/org/universaltranslator/core/provider/TencentHunyuanProvider.java:16-17` 的常數 ENDPOINT/HOST，建構子 `:29-38` 不收 endpoint）。個別 provider 的小缺口，不足以立成一條 FG。

4. **HTTP 429 的退避與 `Retry-After`** — 有完整實作，非估算也非缺失。`translator-core/src/main/java/org/universaltranslator/core/provider/ResilientTranslationProvider.java:136-161`（`wait = min(max(backoff, retryAfter), MAX_RETRY_AFTER_MILLIS)`，`backoff = min(2000ms, 200ms << (attempt-1))`）、`:163-171`（只把 `HttpStatusException.isRetryable()` 與 `IOException` 視為可重試）、`translator-core/src/main/java/org/universaltranslator/core/net/HttpStatusException.java:43-45`（408/425/429/5xx）、`translator-core/src/main/java/org/universaltranslator/core/net/HttpJsonClient.java:311`（解析 `Retry-After`）。判定非缺口。（「廠商把限流碼放在 200 body 導致不重試」是另一類問題，屬 provider 個別實作，未達 FG 門檻。）

5. **串流能力矩陣** — 已覆蓋該有的引擎，且刻意排除不支援者。覆寫 `translateStreaming` 的有 `OpenAiChatTranslationProvider.java:380`、`GeminiTranslationProvider.java:115`、`ClaudeTranslationProvider.java:115`、`AzureOpenAiTranslationProvider.java:83`（轉發 delegate）、`ResilientTranslationProvider.java:74`（轉發並注入限流守衛）；`DeepLTranslationProvider.java:22` 的註解明確說明 DeepL 協議沒有增量形式，走介面預設的單次 callback 是正確行為。`LlamaCppOfflineProvider` 沒有串流列在 `FG-010` 的相關敘述而未另立一條。判定串流本身非缺口。

6. **NeoForge 1.20.6 / 1.21 沒有自己的 `UniversalTranslatorNeoForgeClient.java`** — 初看疑似客戶端入口缺失，實際不是。`platforms/neoforge/shared.gradle:144-146` 對 `neoforgeEntryLayer in ["1.20.6", "1.21-1.21.1"]` 額外把 `entrypoints/common/src/main/java` 加入 `java.srcDir`，客戶端入口仍在（`platforms/neoforge/shared/entrypoints/common/src/main/java/org/universaltranslator/forge/UniversalTranslatorNeoForgeClient.java:20-30` 的 keybinding 與 `:67-70` 的 `clientSetup`）。判定非缺口，排除誤報。

7. **`platforms/fabric/1.14-1.15/shared/family/1.14-1.15/src/main/resources/universal-translator.fabric.mixins.json` 是 `"client": []` 的空設定** — 初看疑似會讓 1.14–1.15 全部 mixin 失效，實際是刻意的：只有 `versions/1.14`、`1.14.1`、`1.14.2` 的 `build.gradle:16-18` 會加這個 `srcDir`，而這三個版本依 `README.md:59` 本來就「仅提供引导实现；完整设置页从 1.14.3 起」。1.14.3+ 不加該目錄，走 `1.14-1.15-common` 的 11 個 mixin。判定非缺口，排除誤報。

8. **翻譯歷史** — `translator-core/src/main/java/org/universaltranslator/core/RecentUserText.java:8-9`（128 筆 / 30 分鐘 `RETAIN_MILLIS`）名稱容易誤導，但它記錄的是「玩家自己剛打的字」用來避免自我翻譯（`:6` 的 `Keeps recently typed client messages out of render-time translation`），不是給使用者看的翻譯歷史。真正的「翻譯歷史」缺口已含在 `FG-005` 的快取可觀測性裡，不另立一條以免同根因重複計數。
