# 更新记录

## 1.4 - 2026-10-21

**1.4 把「能翻」變成「翻得可控、看得見、修得動」**：新增 HUD 狀態指示器、JSON 批次協定、SSE 串流顯示、四個原生服務（DeepL／Gemini／Claude／Azure OpenAI）、統計面板與除錯模式；翻譯失敗時會說出真正原因；翻譯行為第一次可以自己調（併發數、批次大小、品質模式、自訂提示詞）；同時把 48 個發布目標的設定畫面收斂成共用層，淨刪 38,169 行。**本版取代 1.3.11 成為建議版本。**

### 一、翻譯更快、更省

| 改動 | 效果 |
| --- | --- |
| **JSON 批次協定** | 同一批的單行文字合併成一次請求，`{"1":"…","2":"…"}` 逐鍵驗證：缺鍵只補那一筆、多餘鍵忽略、值不是字串只留一個洞；模型不吐 JSON 時退回編號清單（允許缺號）。譯文以數字開頭、含換行的情況不再錯位。 |
| **併發與批次可設定** | `translation-workers`（1–16，預設 4）、`batch-size`（1–16，預設 8）、`batch-window-ms`（0–500，預設 30）。超範圍或無法解析一律夾到範圍內，診斷包會寫出實際生效值與被夾到的原因。**併發數在儲存設定時即生效，不需要重開遊戲。** |
| **SSE 串流顯示** | `stream-outgoing`（預設開）：翻譯中在 HUD 旁顯示累積譯文，請求結束即清除；部分文字永不進快取，只有通過驗證的最終結果才寫入。非串流端點自動退回既有路徑，每個端點只多付一次探測成本。 |
| **推理模型額度 16,384** | 原本 2048 常被思考吃光、還沒寫出譯文就被截斷。現在第一次遇到「只有 reasoning、沒有譯文」就直接以 16,384 起算；端點拒絕時記住可接受上限，不再每行重試。 |
| **託管服務併發 2 → 4** | 速率限制是「預約時段」，worker 數才是真上限；對動輒數秒的推理模型，2 個就是硬天花板。離線維持 1。 |

### 二、品質可以自己定

- **品質模式 `fast` / `standard` / `high`**（`translation-quality`，預設 `standard`）：只改系統提示詞，`temperature`、額度計算、批次協定、串流行為全部不變。`standard` 逐字元等於舊版送出的提示詞，**預設設定的位元組與 1.3.11 完全一致**，既有磁碟快取不失效。**提示詞非預設時 provider id 會附加簽章，讓快取鍵跟著提示詞走**，不會吃到舊模式的譯文。
- **自訂系統提示詞**（`custom-system-prompt`）：非空時完全取代內建提示詞，三種模式都適用；支援 `{target}`、`{source}`、`{kind}`、`{mode}`，未列出的 `{name}` 原樣保留。結尾一律附加固定要求（只輸出譯文、每個輸入行對應一個輸出行），因此無法藉此關閉受保護文字的還原或逐行對齊。
- **新增四個原生服務**：DeepL（表單 POST + `DeepL-Auth-Key`，目標語言轉 `ZH-HANT`／`ZH-HANS`）、Gemini（`systemInstruction`、`:streamGenerateContent?alt=sse`）、Claude（Messages + prefill）、Azure OpenAI（`{endpoint}/openai/deployments/{deployment}/chat/completions?api-version=…`）。既有 16 個服務的建構、請求與快取鍵完全未動。

### 三、看得見：HUD、統計、除錯、失敗原因

- **HUD 翻譯狀態指示器**：開關、四角定位、大小與邊距、顏色（開為實心方塊、關為紅色空心框，形狀與顏色雙重區分）、顯示內容（圓點／目標語言／服務商／翻譯中）、顯示時機（始終／僅翻譯中／僅停用）、可直接拖曳並附迷你螢幕預覽。**覆蓋全部 8 個平台家族（48 個發布目標，另含未隨版發布的 Fabric 1.0–1.12 兩個 bundle）**，含 Fabric 1.13（含 1.13.2）、Forge 1.8.9／1.12.2 這些原本沒有 HUD 掛勾的版本。
- **Forge／NeoForge 的執行期狀態與離線模型下載進度**：行為比照 Fabric——狀態進 Action Bar，失敗進聊天並 60 秒去重；離線模型下載有進度回報。
- **翻譯失敗會說出真正的原因**：不再是一句籠統訊息。非 JSON 回應會附上開頭 60 字元（`Invalid JSON value (body starts with: "<!doctype html>…")`）；轉發站以 HTTP 200 回傳自己的錯誤物件時直接轉述（額度不足、令牌無效、模型不存在…）；填 `https://host/v1` 這種基底網址會自動補上 `/chat/completions`；模型只回推理內容時自動以最大額度重試一次，仍失敗才回報並說明原因。
- **統計面板**：`config/universal-translator-stats.properties`，記請求數、成功／失敗分類、快取命中率、平均與 p95 延遲、輸入／輸出 Token，以及每個服務各自的計數。讀檔一次、背景每 60 秒寫一次，讀寫失敗一律吞掉，絕不影響翻譯。
- **除錯模式與診斷包**：`debug-log` 記錄重試、退避、串流事件、快取決策與失敗原因；金鑰與端點先清洗（含 `x-goog-api-key`、`aliyun-access-key-id`、`DeepL-Auth-Key` 這類名稱與裸的長 base64），端點只留主機名稱，檔案達 2 MB 自動輪替。另有「匯出診斷包」，在 `config/` 產生含環境、診斷、清洗後設定與除錯記錄的 zip。
- **引擎分頁全面重做**：服務類型決定顯示哪些欄位（不再出現與所選服務無關的輸入框）、**LLM 摘要一目了然**（直接看到端點與金鑰是否已設定）、一鍵抓取模型清單、可實際打一次 chat 端點的「測試連線」，控件在分頁列與儲存列之間**垂直置中**。連同品質模式、自訂提示詞、統計與除錯控制，**28 個設定畫面全部到位**。

### 四、修掉的真實缺陷

- **Fabric 1.13.2 補上 HUD 指示器**：這是 8 個平台家族裡唯一沒有指示器的版本線——它走 legacy yarn，HUD 類名（`InGameHud` 對 `GameGui`）、`TextRenderer` 套件，甚至「有沒有 `Window` 類」都與 1.13.0／1.13.1 的 Ornithe mappings 不同。指示器改為從 GLFW framebuffer 與 GUI scale 推導縮放尺寸（即 vanilla `Window#getScaledWidth` 的算法），行為與其餘家族一致。
- **列表項目符號被改寫、顏色跟著位移**：行首裝飾符號（`•` `◆` `▪` `▸` `»`）連同其後的空白一律保護，不再送給模型，也就不會因字元寬度不同讓顏色看起來跑到別的字上。
- **計分板／Tab 行翻譯後變白**：顏色在子元件上時，bridge 原本退回「整行攤平成一個 literal」而丟掉樣式。10 份 `RenderedTextBridge` 改為遞迴收集整棵元件樹的可視葉節點與各自樣式。
- **同一段文字重複送出、重複計費**：in-flight 去重鍵原本用 `System.identityHashCode`，而玩家名快照每 5 秒換新實例，去重等於失效；改用內容簽章。
- **多色 Text 元件只保留第一段樣式**：改為逐段重建，顏色、粗體與點擊事件都保留。
- **模組自有訊息被再翻譯**：原本只認帶方括號的字面值，繁中「MC 自動翻譯工具：…」等漏網；改為語言無關的名稱比對。
- **Forge 1.21.x／NeoForge 1.21.x 的自有設定與診斷畫面被機翻**：畫面文字被當成待譯文本送去翻譯；已把 26.x 的判定移植到三個 family，涵蓋 Forge 11 個、NeoForge 12 個版本。
- **Fabric 1.20–1.20.6 拖曳中縮放視窗會把 HUD 把手黏在指標上**：`init()` 在視窗縮放時重跑，1.20.x 少了 `hudDragging = false`；共用畫面後一併修正。
- **翻譯文字顏色預設 `aqua` → `original`**：顏色不在字串本身、來自隊伍或元件的行（例如計分板標籤）原本會被整片塗成青色。13 個設定檔同步，玩家自己選過的顏色不受影響。
- **Fabric 1.14–1.15.2 完全沒有介面語言檔**：8 個發布目標的畫面標籤原本顯示原始鍵名，已補 `en_us`／`zh_cn`／`zh_tw`。
- **設定畫面在部分版本一開啟就崩潰**：Fabric 1.16–1.20.1 的 HUD 分頁按鈕只被宣告、從未被建立，導致 `NullPointerException`。

### 五、效能（只搬成本，沒有放寬任何行為語意）

| 項目 | 原本 | 現在 |
| --- | --- | --- |
| `RecentUserText.shouldPreserve` | 每幀約 896 個臨時字串 | 0 個 |
| 玩家名快照去重（18 個 runtime） | `List.contains`，n=1000 約 5×10⁵ 次 `equals` | `LinkedHashSet`，O(n) |
| HTTP 連線 | 每個請求結束 `disconnect()` | 成功路徑回連線池，省下每次 TCP+TLS 握手 |
| `ProtectedText` 正則 | 每次 `parse` 重編最多 1000 條 alternative | 4 槽有界快取，一次快照只編一次 |
| 磁碟快取落盤 | 每次 `put` 複製整個 map（上限 10,000 筆） | 單一寫者 + dirty 旗標 + revision 守衛 |
| 離線模型驗證 | 每次啟動重算數百 MB～GB 的全檔 SHA-256 | `.verified` sidecar，不符才回退全檔雜湊 |
| 渲染快取淘汰 | 滿 4096 筆整張 `clear()`，整個畫面同時退回原文 | 一次淘汰 512 筆 |
| pending 期限 | 180 秒（短於最壞重試 35 分） | 40 分鐘 |
| F8／儲存設定 | 先 `shutdown()` 再建新的，會等 worker 做完 | 先建 → 再換 → 背景關舊，切換無空窗 |
| 429 `Retry-After` | 讀了標頭但沒用 | `min(max(backoff, retryAfter), 30 秒)` |

**三項行為取捨（你可能察覺）**：快取檔損毀或不可讀時改為**靜默退化成空快取**（原本會拋 `IOException` 讓遊戲起不來，代價是重新翻譯）；`.verified` sidecar 可被同時偽造大小與 mtime 繞過（能寫入該目錄者本來就能替換 JAR）；磁碟快取 flush 進行中進來的 `put` 只存在記憶體，硬殺或斷電會遺失一次視窗內的數筆（硬上限 10,000 筆，正常關閉不遺失）。

### 六、平台與發布

- **發布目標 33 → 48**：新增 15 個從未出貨的 NeoForge 目標（1.20.4、1.20.6、1.21、1.21.2、1.21.4–1.21.10、26.1、26.1.1、26.1.2、26.2）。可安裝 JAR 16 → **31 顆**，Release 資產 **32 個**。
- **Fabric 1.16 補回 1.16.0–1.16.4**：原本版本清單從 1.15.2 直接跳到 1.16.5，這 5 個版本的玩家裝了會載入失敗。
- **覆蓋範圍**：Fabric 1.13–26.3（51 個精確實作，另含未隨版發布的 1.3.1–1.12.2 兩個 source-only bundle）、Forge 1.8.9／1.12.2／1.16.5／1.18.2／1.19.2／1.20.1／1.21、1.21.1、1.21.3–1.21.11／26.1–26.2（缺口版本由 `mods.toml` 拒絕載入）、NeoForge 1.20.1–26.2（每版一顆）。
- **對外版本號收斂為單一來源**：`VerifiedDownloader` 原本停在 `1.1`，每次下載模型都對外宣告一個三版前的版本號；現在統一由 `UserAgent.VALUE` 提供。
- **內部整理**（不影響行為）：三個設定畫面類別由 **106 份降到 56 份**，`platforms/` 的 `.java` 由 490 檔降到 406 檔，淨刪 **38,169 行**；NeoForge 改為座標驅動（`.java` 38 → 14，版本目錄只留 `build.gradle` 與 `META-INF/*.toml`）；`validateSharedPlatformLayouts` 新增 9 條 include，並新增 `validateNeoForgeModuleCoordinates`，違規在設定階段直接失敗。收斂過程中也修掉五類世代錯配（1.19.3 的靜態方法遮蔽、Forge 1.18.2／1.19.2 的 `TextComponent` 與 `GuiGraphics`、NeoForge 的 16 個 `srcDir`、1.21 線兩層遺失、26.x 誤吃 1.18.2–1.21.11 的共用畫面）。

### 七、安裝

先刪掉舊版 JAR，每個遊戲實例只留一顆與你的 Minecraft 版本、載入器完全對應的 1.4 JAR；Fabric 另需對應版本的 Fabric API。除了 **Fabric 1.13.2 補上指示器**之外，`1.4` 與 `1.4-beta5` 的 JAR 只差版本號字串——**1.13.2 的玩家請更新**，其餘已在 beta5 的不必急著換。

### 八、已知限制

- **NeoForge 1.20.2 不發布**：`net.neoforged:neoforge:20.2.93` 的 `-userdev.jar` 在 Maven 上確實存在，但它是 ForgeGradle 風格的過渡期產物，`moddev_version=2.0.144` 的 `neoFormRuntimeDependenciesNeoForgeClasses` 期待的是 1.20.5+ 的 NeoForm runtime 結構，因此解析不到。要補這一版得改成獨立建置。
- **Forge／NeoForge 不做版本範圍合併**：FML 不允許同一 JAR 內嵌多套同 `modId` 實作；JiJ 也不會按 Minecraft 版本篩選子 mod，跳過之前就可能因類引用失敗而崩。每個版本各一顆是官方推薦做法。
- **`fabric-1.0-1.8` 不含 1.0.0–1.2.5、1.6.2、1.7.3、1.7.5、1.7.7**：上游 mappings 只發佈帶側別後綴的 feather，或 ploceus 的 version manifest 對不上。這 12 個版本的玩家基數接近 0，補上需要繞開 ploceus 的版本推導。

### 九、驗證

- 48 個目標的發布資產由 `prepare-release` 建置、以 `verify_release_jars.py` 校驗：[run 39593357729](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/39593357729)，50/50
- 合併後 `main` 的完整建置（17 平台 + 7 舊版）：[run 39595869553](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/39595869553)，30/30、0 skipped
- 舊版目標全跑（4 個 Fabric bundle、`forge-legacy-1.8.9`／`1.12.2`、15 個 NeoForge）：[run 39577304249](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/39577304249)，22/22
- Fabric 1.13.2 的指示器：[run 39599742982](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/39599742982)（建置步驟 success，產物含 `InGameHudStatusIndicatorMixin.class` 與 mixins.json 條目）
- 本機未執行任何編譯或測試——本專案唯一的建置途徑是 GitHub Actions。
- **尚待實機驗證**：HUD 指示器的遮擋與拖曳手感（含 1.13.2）、SSE 串流、統計與除錯、四個新服務的端到端、離線模型第二次啟動、1.20–1.20.6 拖曳中縮放視窗、NeoForge 26.x 進世界。

## 1.4-beta5 - 2026-10-21

- **設定畫面收斂成共用層，同一份原始碼服務 48 個發布目標**：全倉的 `UniversalTranslatorConfigScreen`、`UniversalTranslatorDiagnosticsScreen`、`UniversalTranslatorLlmConfigScreen` 三個畫面原本有 **106 份定義**，逐對比對後只剩 56 份；`platforms/` 底下的 `.java` 由 490 檔降到 406 檔，本次 264 個檔案 `+1,715 / −39,884`（淨刪 38,169 行）。做法一律是「把世代差異抽成基底類別或轉接器、畫面本體只留一份」，沒有放寬任何行為。逐家族結果：
  - **Fabric 1.0–1.12**：兩個 Ornithe bundle 的設定畫面合成 `platforms/fabric/1.0-1.12/shared/screens`，三處 API 分歧（`TextFieldWidget` 的 `int id` 參數、`mouseDragged` 要到 1.7.10 才出現、翻譯入口 `OrnitheClientAccess.tr` 對 `I18n.translate`）留在各 bundle 自己的 `UniversalTranslatorConfigScreenBase`。順手刪掉 1.0-1.8 那份裡沒有任何呼叫點的 `private ButtonWidget button(int id)`——它依賴 1.8 之後就沒有的 `ButtonWidget.id` 欄位，本來也不可能進共用檔。
  - **Fabric 1.13**：Ornithe（1.13.0／1.13.1）與 legacy（1.13.2）兩條建置路徑先共用 10 個逐位元相同的檔案（`FabricConfig`、`TitleScreenMixin`、`RenderedTextBridge`、`TranslationRenderContext` 與 6 個語言檔，−1,940 行），三個畫面再合成 `1.13/shared/screens`；`TextRenderer` 的套件路徑與 `drawString`／`drawWithShadow` 的方法名差異收進兩個 19／18 行的基底（淨刪約 2,000 行）。
  - **Fabric 1.14／1.15**：兩棵 family 各 22 個檔案，其中 21 個逐位元相同，搬到 `family/1.14-1.15-common`，兩邊各只留真正不同的 `InGameHudStatusIndicatorMixin`（−3,660 行）。
  - **Fabric 1.16–1.18.2**：11 個版本原本帶 5 份、9,775 行的畫面副本，現在是 `legacy/shared/screens/1.16-1.18` 一份加五個世代基底；三類差異（`addDrawableChild` 的泛型界、`openScreen` 對 `setScreen`、`onClose`＋`isPauseScreen` 對 `close`＋`shouldPause`）由基底承接，共用畫面只透過 `openScreen`／`onBack`／`pausesGame` 動作（淨刪 7,648 行）。同時刪掉 `family/1.17-1.18` 這個**死層**：`sourceLayers` 沒有任何版本引用它，全倉也搜不到那個字串，6 個檔案 1,892 行純浪費。
  - **Fabric 1.19**：1.19～1.19.4 五個版本的三個畫面收成一份（`+103 / −1,968`），世代差異放在 `screen-base/1.19-1.19.2` 與 `screen-base/1.19.3-1.19.4` 兩個基底。1.19.3 把 `ButtonWidget` 建構子的 `TooltipSupplier` 換成 `NarrationSupplier`、六參數版本消失，所以另寫一份 45 行 shim，`super` 走七參數並傳 `DEFAULT_NARRATION_SUPPLIER`。
  - **Fabric 1.20.x／1.21**：1.20 到 1.20.6 與 1.21 到 1.21.11 共用 `1.21/shared/screens/1.20.1-1.21.11`，沿用 1.21 已抽好的輸入 hook（`input/pre-1.21.9` 與 `input/1.21.9-plus`）。重複的三個畫面由 9 份降到 8 份。
  - **Fabric 26.x**：26.2 與 26.3 先共用 6 個逐位元相同的檔案（−2,488 行），再把 26.1 併進來共用三個畫面；`setScreen` 在 26.2 被移到 `Minecraft#gui`，用 19 行的 `FabricScreenOpener`（兩個世代各一份）承接 5 個呼叫點。26.x 樹由 6,008 行降到 4,157 行。
  - **Forge 1.18.2–1.21.11**：`UniversalTranslatorScreens`（1,817 行）合成一份，兩個接縫各自按世代實作——`ButtonSpec`（1.18.2／1.19.2 走 `new Button`，1.20.1 以後走 `Button.builder`）與 `ForgeScreenText`（1.18.2 走 `TextComponent`、1.19.2 走 `Component.literal`、1.20.1 以後再加上 `setHint`）。Forge 側的 `UniversalTranslatorScreens` 由 5 份 9,073 行降到 2 份。
  - **Forge 1.21**：相鄰 family 之間抽出 `runtime/1.21-1.21.8`（`ForgeTranslationRuntime` 加設定畫面，545 行）與 `draw-context/1.21.6-1.21.11`（`DrawContextMixin`，54 行），三個 family 依 `family` 條件吃不同層（−599 行）。
  - **Forge 1.16.5 維持自己一份**：`ITextComponent`／`MatrixStack`／`TextFieldWidget` 與 1.18 以後的 `Component`／`PoseStack`／`EditBox` 是兩個世代，加上 `net.minecraft.client.gui.widget` 對 `gui.components` 的套件搬移，Java 沒有型別別名可以抹平。
- **NeoForge 改為座標驅動，19 個版本目錄只剩座標與 `META-INF/*.toml`**：`platforms/neoforge` 的 `.java` 由 38 檔降到 14 檔，`sourceSets`、資源與編譯設定集中在單一 `platforms/neoforge/shared.gradle`；`entrypoints/` 依真實 API 差異分 7 層（`1.20.2-1.20.4`、`1.20.6`、`1.21-1.21.1`、`1.21.2-1.21.8`、`1.21.9-1.21.11`、`26.1-26.1.2`、`26.2`），`metadata/` 只有 4 層 mixin 設定（`1.20-1.20.4`、`1.20.6`、`1.21`、`26.x`）。common API、版本 family、語言檔與 `pack.mcmeta` 直接指向對應的 `platforms/forge/` 目錄，不再保留鏡像副本（−3,199 行）。19×2 個 entrypoint 與 19 份 mixin 設定逐位元組核對過，`archivesName`、`options.release`、`packFormat`／`resourcePackFormat`、`jar_dir` 全部不變。
- **建置期驗證器加固**：`validateSharedPlatformLayouts` 新增 9 條 include，NeoForge 版本目錄裡再出現 `.java`、`*.mixins.json` 或 `assets/` 會在設定階段直接失敗；新增 `validateNeoForgeModuleCoordinates`（掛進 `check`），檢查每個座標都解析得到目錄、版本號與目錄一致，並拿 Forge 樹自己的版本對應表交叉驗證宣告的 Forge 層（`forge/1.21` 的 `versionFamilies`、`forge/26.x` 與 `forge/modern` 的 `sourceLayers` 三種格式都解析；1.21.2 不在 Forge 表內，用版本區間回退）。
- **收斂過程中修掉的五類世代錯配**（都是 CI 才暴露的編譯失敗，beta4 的產物沒有這些問題）：
  - **1.19.3 的靜態方法遮蔽**：1.19.3 起原版 `ButtonWidget` 有 `static builder(Text, PressAction)`，子類別宣告同名同參數、卻回傳自家 `Builder` 的靜態方法構成非法隱藏。兩種 shim 一律改名為 `of(...)`，共用畫面的 50 處呼叫跟著改；兩個 `TitleScreenMixin` 不受影響（顯式 import 優先）。
  - **Forge 1.18.2／1.19.2 的兩個世代錯配**：`TextComponent` 只到 1.18.2（1.19 起是 `Component.literal`／`Component.translatable`），`GuiGraphics` 是 1.20 以後（1.18.2／1.19.2 的 `render` 參數是 `PoseStack`）。共用基底裡那個 `render(GuiGraphics, ...)` 覆寫改成 `protected final renderModelListOverlay(int mouseX, int mouseY)`，由四個具體畫面在 `super.render` 之後自己呼叫，繪製位置不變。
  - **NeoForge 找不到 Forge 的新層**：畫面檔案搬走後，NeoForge 1.21 的 12 個版本、1.20.2／1.20.4／1.20.6 與 1.20.1 共 16 個 `build.gradle` 少了 `srcDir`，PR 上有 4 個 job 紅燈（`cannot find symbol`）；另外 1.21 線的 `runtime` 與 `draw-context` 兩層在解衝突時掉了，`neoforge-1.21.1`／`1.21.3` 回報 34 個 `cannot find symbol`。現在 `platforms/neoforge/shared.gradle` 照抄 `forge/1.21/shared.gradle` 的 family 條件，並加上與其他層一致的存在性檢查（目錄不在就讓設定階段直接失敗）。
  - **NeoForge 26.x 誤吃 1.18.2–1.21.11 的共用畫面**：`platforms/neoforge/shared.gradle` 原本對**每個** NeoForge 模組都掛上 `forge/shared/screens/1.18.2-1.21.11`，但 26.x 的 Forge 樹自己就有三個畫面（`forge/26.x/shared/api/26.1-26.1.2`、`26.2`），而且 Minecraft 26.x 把 `setScreen` 從 `Minecraft` 移到 `Minecraft#gui`——1.21.11 的副本在 26.x 上編不過。4 個 26.x NeoForge 目標全部失敗：`cannot find symbol: setScreen`（4 處）與 `UniversalTranslatorConfigScreenBase cannot be converted to UniversalTranslatorConfigScreen`。這個錯誤在 PR 路徑上抓不到（`build.yml` 不含 26.x NeoForge），而先前那次 `legacy-targets` 因為只指定了部分 `targets`，26.x 的建置步驟被條件略過、job 仍回報 success；是 `prepare-release` 第一次真正編譯全部 48 個目標時才暴露（[run 39589563963](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/39589563963)）。現在只對 1.20／1.21 樹掛那兩個畫面層，26.x 用自己 `api/` 底下的那份；修正後以 `-f targets=neoforge-26.1,neoforge-26.1.1,neoforge-26.1.2,neoforge-26.2` 重跑，4 個目標的建置步驟都是 success（[run 39590910832](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/39590910832)）。
- **唯一的使用者可見變化：Fabric 1.20–1.20.6 拖曳中縮放視窗不再卡住把手**：1.21 的 `init()` 尾端有 `hudDragging = false;`，1.20.x 沒有，而 `init()` 在視窗縮放時會重跑。共用畫面之後 1.20 到 1.20.6 一併得到這個修正——這是本次唯一刻意保留的行為差異；其餘 48 個目標的畫面行為、譯文、顏色與座標都逐項對照過，與 beta4 相同。
- **文件**：`docs/Zh-tw/ARCHITECTURE.md` 補上 NeoForge 座標層的說明（版本目錄只放座標、`shared/` 只放 NeoForge 真正獨有的東西、驗證任務名）；`AGENTS.md` 記下 `main.refmap.json` 的 key 順序不穩定（ForgeGradle 用 `HashMap` 迭代產生，長度、行數、內容都一樣但位元組不同），所以整顆 JAR 的位元組比對在有 refmap 時不能當唯一回歸判準。

**驗證**

- 收斂分兩條分支、各自以 PR 經 GitHub Actions 驗證——這是唯一允許的建置途徑，本機未執行任何編譯或測試：
  - Fabric／Forge 世代收斂（分支 `refactor/fabric-26x-shared-layers`，−13,051 行，12 個產物位元組相同）：[run 39570433222](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/39570433222)，30/30、0 skipped
  - 該分支合併進 `main`：[run 39573339371](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/39573339371)
  - NeoForge 座標化（分支 `refactor/neoforge-shared-layers`）：[run 39575115709](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/39575115709)，30/30；前一次 [run 39573994322](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/39573994322) 紅在 `neoforge-1.21.1`／`neoforge-1.21.3`，補回兩層後轉綠
  - 合併後 `main` 的完整驗證（17 個平台目標 + 7 個舊版目標，`cd66d3e2`）：[run 39576043476](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/39576043476)，30/30
  - 舊版目標全跑（4 個 Fabric bundle、`forge-legacy-1.8.9`／`1.12.2`、15 個 NeoForge）：[run 39577304249](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/39577304249)，22/22
- **發布**：`gradle.properties` 的 `mod_version`、`core/UserAgent.java`（對外請求版本號的唯一來源）、四份 README 與三語文件、`website/app/page.tsx` 以及 `publish-release.yml` 的 `expected` 陣列都在同一次變更裡升到 `1.4-beta5`。`prepare-release` 產生的 `downloads/1.4-beta5`（48 顆目標 JAR 加 `SHA256SUMS.txt`）由 `release/prepare-1.4-beta5` 帶進 `main`，合併後 `publish-release` 建立 `v1.4-beta5`；版本號含 `-`，因此標記為 prerelease，並縮減成 31 顆可直接安裝 JAR（加 checksum 共 32 個資產）。
- **需要遊戲內實測**（CI 無法覆蓋）：48 個目標逐一開啟設定畫面，確認四個分頁的排版、拖曳與選單、LLM 模型清單的點擊與輸入在收斂後仍正常；Fabric 1.20–1.20.6 在拖曳中縮放視窗的行為。

## 1.4-beta4 - 2026-10-19

- **批次翻譯改用 JSON 協定（以輸入行號為鍵），並保留編號清單後備**：原本的「編號清單」協定有兩個結構性漏洞——譯文本身以數字開頭（例如 `3 apples`）會被當成下一筆，譯文裡有換行也會被當成新的一筆。改成 `{"1": "...", "2": "..."}` 之後兩個問題同時消失，多行文字也第一次能進批次。回覆逐鍵驗證：缺鍵、空值或值不是字串只留一個洞，其餘照收，多餘的鍵忽略；少一筆只補那一筆（走單筆路徑），不再整批重送。模型不吐 JSON 或服務不支援輸出格式時，退回編號清單解析（現在允許缺號，套用同一套逐鍵後備）。OpenAI／Azure 用 `response_format`、Gemini 宣告 `responseMimeType`、Claude 宣告 prefill；端點以 400 拒絕 `response_format` 時重試一次不帶該欄位，之後整個工作階段不再送。
- **推理模型的完成額度提高到 16384 並記住端點上限**：原本只有 2048，模型把額度全花在思考上、還沒寫出譯文就被截斷，而且每一行都要先試小額度再重試一次。現在第一次遇到「只有 reasoning 內容、沒有譯文」就直接以 16384 起算；端點以 4xx 拒絕該額度時記下已知可接受的上限（2048）並如實回報，不會讓整行失敗，也不會每一行重複被拒。`max_tokens` 是上限而非預留量，模型提早停止沒有額外成本。
- **併發數與批次現在可以自己設定**：新增 `translation-workers`（1–16，預設 4）、`batch-size`（1–16，預設 8；1 = 不批次）、`batch-window-ms`（0–500，預設 30）。超範圍或無法解析一律夾到範圍內，不讓翻譯失敗；診斷包會輸出實際生效值與被夾到的原因。離線 llama.cpp 永遠 1 個 worker、`batch-size` 1，不受設定影響。引擎分頁新增「併發數」「批次大小」兩個循環按鈕，覆蓋 28 個設定畫面；併發數在儲存設定時重建翻譯工作階段即生效，不需要重開遊戲。
- **傳送中的翻譯邊生成邊顯示（SSE 串流）**：新增 `stream-outgoing` 設定（預設開啟）。翻譯進行中在 HUD 指示器旁顯示累積譯文，請求結束即清除；部分文字永不寫入快取，只有通過驗證的最終結果才進快取。非串流端點自動退回既有路徑（回應不是 `text/event-stream`，或端點以不可重試的 4xx 拒絕 `stream`），每個端點只多付一次探測成本。
- **翻譯品質模式 `fast` / `standard` / `high`**：新增 `translation-quality` 設定（預設 `standard`），只改系統提示詞——`temperature`、`max_tokens` 計算、推理額度升級、批次協定與串流行為全部不變。`standard` 逐字元等同原本送出的提示詞，因此預設設定送出的位元組與改動前完全一致。提示詞非預設時 provider id 會附加簽章，讓快取鍵跟著提示詞走；預設設定不附加，既有磁碟快取不失效。
- **自訂系統提示詞**：新增 `custom-system-prompt`（預設空字串）。值非空時完全取代內建提示詞，三種品質模式都適用；支援 `{target}`、`{source}`、`{kind}`、`{mode}` 佔位符，未列出的 `{name}` 原樣保留。無論自訂內容為何，結尾一律附加固定要求（只輸出譯文、每個輸入行對應一個輸出行且順序一致），因此無法藉此關閉受保護文字的還原或逐行對齊。
- **新增原生 DeepL／Gemini／Claude／Azure OpenAI 服務**：既有 16 個服務的建構、請求和快取鍵都未變動。DeepL 用表單 POST 與 `DeepL-Auth-Key`，目標語言轉為大寫區域碼（`ZH-HANT`／`ZH-HANS`），因沒有系統提示詞所以品質模式對它無效；Gemini 用 `x-goog-api-key` 與 `systemInstruction`，支援 `:streamGenerateContent?alt=sse`；Claude 用 Messages 介面與 `anthropic-version`，系統提示詞放在頂層 `system`；Azure OpenAI 組合 `{endpoint}/openai/deployments/{deployment}/chat/completions?api-version=...`，憑證走 `api-key` 標頭。設定頁的「測試連線」改為依服務分派，不再一律送出 OpenAI 聊天請求；Gemini 與 Claude 可抓取模型清單。
- **翻譯統計面板與除錯模式**：新增 `config/universal-translator-stats.properties`（請求、成功、失敗原因分類、快取命中率、平均與 p95 延遲、輸入／輸出 Token，以及每個服務各自的計數），讀檔一次、背景每 60 秒寫一次、結束時再寫一次，讀寫失敗一律吞掉，絕不影響翻譯。新增 `debug-log` 設定（預設關閉）與 `config/universal-translator-debug.log`，記錄重試、退避、串流事件、快取決策與失敗原因，金鑰與端點先清洗（含 `x-goog-api-key`、`aliyun-access-key-id`、`DeepL-Auth-Key` 這類名稱與裸的長 base64），端點只留主機名稱，檔案達 2 MB 自動輪替。另新增「匯出診斷包」，在 `config/` 產生含環境、診斷、清洗後設定與除錯記錄的 zip。
- **診斷畫面與引擎分頁在全部 28 個設定畫面完整覆蓋**：品質模式、自訂提示詞、統計頁切換與重設、除錯模式開關、診斷包匯出全部到位，各變體沿用自己既有的 widget 與繪製 API。品質模式與自訂提示詞位於引擎分頁，統計與除錯控制留在診斷畫面。
- **修正列表項目符號被改寫與隨之的樣式位移**：行首的裝飾符號（Unicode 類別 `\p{S}`／`\p{P}`，例如 `•` `◆` `▪` `▸` `»`）連同其後的空白一律保護，不再送給模型；模型不會再把 `•` 換成 `◆`，也就不會因為字元寬度不同而讓顏色看起來跑到別的字上。兩個 `Pattern.compile` 加上 `Pattern.MULTILINE`，讓行首錨點在每一行都生效。
- **修正計分板／Tab 行的顏色在翻譯後變白**：顏色不在元件根節點、而在子元件上時（隊伍前綴／後綴，以及伺服器以巢狀結構送來的元件），bridge 原本會退回「整行攤平成一個 literal」的路徑，而那個 literal 只帶根節點的樣式，顏色因此遺失；未經翻譯的行直接回傳原元件，所以顏色看起來完全正常。10 份 `RenderedTextBridge` 改為遞迴收集整棵元件樹的可視葉節點與各自的樣式（新增 `collectStyledLeaves`），並新增 `TranslationStyleRuns.shouldRebuildRuns(List<Run>, Object)`：單一 run 的樣式與根節點不同時也必須逐 run 重建。單一 run 仍是單一翻譯單位，送給模型的請求數不變。
- **翻譯文字顏色的預設值由 `aqua` 改為 `original`**：`translated-text-color` 的預設原本是 `aqua`，而顏色不在字串本身、來自隊伍或元件的行（例如計分板標籤）會被整片塗成青色。13 個設定檔（Fabric 9、Forge 4）的讀取預設與寫入預設都改成 `original`，三語系 USER_GUIDE 的範例設定同步更新；玩家自己選過的顏色不受影響。
- **補上 Fabric 1.14–1.15.2 的介面語言檔**：這個家族（8 個發佈目標）原本完全沒有 `assets/universal_translator/lang/`，所有畫面標籤——包含品質模式、併發數、批次大小、統計與除錯模式等新控制項——在遊戲內都顯示原始鍵名。現在補上 `en_us`／`zh_cn`／`zh_tw` 三份，鍵集合與該家族程式碼實際引用的鍵一一對應。

## 1.4-beta3 - 2026-10-09

- **批量翻译：单行文字合并成一次请求**：同一批渲染帧内到达的单行文字会先收集 30 毫秒，最多 8 句合并成一次请求送出，请求数从「每行一次」降为「每批一次」。回复必须每个编号各出现一次才采用；编号重复、缺漏、多出未编号的行，或任何一行解析失败，就整批退回逐行请求，不会把译文接到错误的行上。批次以「目标语言＋文本种类＋来源语言」分组。多行文本（本身含换行）与离线 llama.cpp 路径维持单笔请求——离线服务器的 context 只有 1024 tokens，一次只服务一个请求。
- **推理模型的完成额度提高到 16384 并记住上限**：原本只有 2048，模型把额度全花在思考上、还没写出译文就被截断，而且每一行都要先试小额再重试一次。现在第一次遇到「只有 reasoning 内容、没有译文」就直接以 16384 起算；端点以 4xx 拒绝该额度时记下已知可接受的上限（2048）并如实回报，不会让整行失败，也不会每一行重复被拒。`max_tokens` 是上限而非保留量，模型提早停止没有额外成本。
- **托管服务的并行数 2 → 4**：离线维持 1。速率限制是「预约时段」（每 60 毫秒一个 slot、睡眠在锁外），worker 数才是真正的并发上限，2 个对动辄数秒的推理模型就是硬性天花板。
- **修正列表项目符号被改写与随之的样式位移**：行首的装饰符号（Unicode 类别 `\p{S}`／`\p{P}`，例如 `•` `◆` `▪` `▸` `»`）连同其后的空白一律保护，不再送给模型；模型不会再把 `•` 换成 `◆`，也就不会因为字符宽度不同而让颜色看起来跑到别的字上。两个 `Pattern.compile` 加上 `Pattern.MULTILINE`，让行首锚点在每一行都生效。
- **修正计分板／Tab 行的颜色在翻译后变白**：颜色不在组件根节点、而在子组件上时（队伍前缀／后缀，以及服务器以嵌套结构送来的组件），bridge 原本会退回「整行摊平成一个 literal」的路径，而那个 literal 只带根节点的样式，颜色因此遗失；未经翻译的行直接返回原组件，所以颜色看起来完全正常。10 份 `RenderedTextBridge` 改为递归收集整棵组件树的可视叶节点与各自的样式（新增 `collectStyledLeaves`），并新增 `TranslationStyleRuns.shouldRebuildRuns(List<Run>, Object)`：单一 run 的样式与根节点不同时也必须逐 run 重建。单一 run 仍是单一翻译单位，送给模型的请求数不变。
- **翻译文字颜色的默认值由 `aqua` 改为 `original`**：`translated-text-color` 的预设原本是 `aqua`，而颜色不在字符串本身、来自队伍或组件的行（例如计分板标签）会被整片涂成青色。13 个配置文件（Fabric 9、Forge 4）的读取预设与写入预设都改成 `original`，三语系 USER_GUIDE 的示例设置同步更新；玩家自己选过的颜色不受影响。


## 1.4-beta2 - 2026-10-08

- **設定畫面的「引擎」分頁全面重做，涵蓋所有 48 個平台目標**：服務類型決定顯示哪些欄位，不再出現與所選服務無關的輸入框。
  - **修正誤導欄位**：選 DeepSeek 等 LLM 服務時，畫面不再顯示 LibreTranslate 的 `127.0.0.1:5000/translate` 端點欄位；該欄位只在選 LibreTranslate 時出現。
  - **LLM 摘要一目了然**：直接顯示目前端點與金鑰是否已設定，不必進入子畫面確認。
  - **模型列與「獲取模型列表」**：主畫面就能看到目前模型，並可一鍵抓取服務端提供的模型清單、點選套用。
  - **新增「測試連線」**：LLM 會實際打一次 chat 端點（與翻譯相同的請求格式）並要求回應是帶 `choices` 的 JSON；LibreTranslate 檢查 `/languages`；其餘服務檢查簽名欄位是否齊全。測試在背景執行緒進行，不會卡住畫面。
  - **版面置中**：引擎分頁的控件在分頁列與儲存列之間垂直置中，不再擠在上半部。

- **翻譯失敗的訊息會說出真正的原因**：過去所有失敗都只顯示一句籠統訊息，現在會轉述服務端實際回覆的內容。
  - **非 JSON 回應**：附上回應開頭 60 字元，例如 `Invalid JSON value (body starts with: "<!doctype html>…")`。
  - **服務端自己的錯誤**：轉發站以 HTTP 200 回傳自己的錯誤物件時，直接顯示它說的內容（額度不足、令牌無效、模型不存在…）。
  - **端點網址自動補齊**：填寫轉發站公告的 `https://host/v1` 這類基底網址時，會自動補上 `/chat/completions`；帶自訂路徑（如 Azure 的 api-version）則維持原樣。
  - **推理模型可用**：模型只回傳推理內容而沒有譯文時，會自動以最大額度重試一次；仍失敗才回報並說明原因。

- **修正設定畫面在部分版本一開啟就崩潰**：Fabric 1.16–1.20.1 的 HUD 分頁按鈕只被宣告與使用、從未被建立，導致開啟設定畫面時發生 `NullPointerException`。現在補上按鈕，HUD 分頁在這些版本也能正常進入。


## 1.4-beta - 2026-10-07

- **新增 HUD 翻译状态指示器，7 项全部可自定义**：常驻显示翻译是否开启，并在设置界面新增独立的 🌐 HUD 分页调整全部选项。
  - **开关与位置**：指示器总开关，四角定位（左上／右上／左下／右下）。
  - **大小与边距**：尺寸与边距各自独立可调，配置档写入时会被夹在允许区间内。
  - **颜色**：翻译开启时为可配置的实心方块；翻译关闭时为红色空心框——形状与颜色双重区分，色盲玩家也能分辨。
  - **显示内容**：圆点、目标语言、当前服务商、翻译中提示，四选一（纯圆点模式不绘制文字）。
  - **显示时机**：始终显示、仅翻译中显示、仅停用翻译时显示。
  - **拖拽定位**：在 HUD 分页内直接拖动指示器，偏移量写入配置；旁边有迷你屏幕预览，位置在所有平台上一致。
  - **覆盖全部 8 个平台家族**：Fabric 1.0–1.8、1.8–1.12、1.13（Ornithe 1.13／1.13.1）、1.14–1.15、1.16–1.20；Forge 1.16.5–1.20.1、1.8.9／1.12.2；以及共用 Forge 源码的 NeoForge 目标。其中 Fabric 1.0–1.8、1.8–1.12、1.13 与 Forge 1.8.9／1.12.2 原本没有任何 HUD 挂勾，本次逐一新建；Forge 1.8.9／1.12.2 改用 `RenderGameOverlayEvent`，不需要 mixin。
- **新增 Forge／NeoForge 执行期状态与离线模型下载进度通知**：行为比照 Fabric——状态进 Action Bar，失败进聊天并 60 秒去重。
- **修复四项翻译缺陷**：
  - Forge 1.21.x／NeoForge 1.21.x 的自有设置与诊断画面被机翻（把 26.x 的判定移植到三个 family，涵盖 Forge 11 个、NeoForge 12 个版本）；
  - 模组自有消息被再翻译——原本只认带方括号的字面值，繁中「MC 自动翻译工具：…」等漏网，改为语言无关的名称比对；
  - in-flight 去重键用 `System.identityHashCode`，而玩家名快照每 5 秒换新实例，导致去重失效、同一段文字重复送出请求（付费 API 重复计费），改用内容签名；
  - 多色 Text 元件翻译后只保留第一段样式，颜色、粗体与点击事件丢失，改为逐段重建。
- **性能优化**：以下按类别列出。所有改动都只是成本搬移或型别替换，没有放宽任何行为语义。

  **一、热路径（每帧或每次渲染都会跑到）**

  | 项目 | 原本 | 改后 |
  | --- | --- | --- |
  | `RecentUserText.shouldPreserve` | 每帧产生约 896 个临时字符串（逐次串接前缀比对） | 在 `remember()` 预存 7 种「前缀＋文字」形式，比对时不再配置字符串，临时字符串降为 0。刻意保留独立的 `equals` 检查、不折进后缀数组，否则 `"Server says hello world"` 会被误判命中 |
  | 玩家名快照去重（18 个平台 runtime） | `List.contains`，每 5 秒重建一次，n=1000 时约 5×10⁵ 次 `equals`（O(n²)），大型服务器会出现周期性卡顿 | 改用 `LinkedHashSet`（O(n)）。插入顺序、`MAX_PROTECTED_PLAYER_NAMES` 上限、`length() <= 255` 守门与 `synchronized` 范围全部不变 |
  | `LanguageHeuristics.shouldTranslate` | 每个字符串都要 `toLowerCase` ＋ 26 个片段 ＋ 3 个标记 ＋ regex，并以 `Character.UnicodeBlock.of` 判断汉字 | 先数字母，`letters == 0` 直接返回（`"123 / 456"` 不再付出上述成本）；汉字判断改为精确码点区间（已对 JDK 21 `src.zip` 的 `blockStarts` 核对 3400／4E00／F900） |
  | `CustomHttpJsonTranslationProvider.id()` | render thread 每个 draw 都对最多 64 KiB 的样版重算 SHA-256 | 改在建构子算一次。`id` 字符串逐字不变，既有磁盘快取不失效 |
  | `RenderTranslationSession.lookup` | 先跑语言启发式，再查 session 内快取 | 快取探测移到启发式之前。两者等价的理由：该快取唯一的写入点在启发式通过之后，`targetLanguage` 是 session 的 `final` 栏位，且 `shouldTranslate` 是 `(text, targetLanguage)` 的纯函数，所以启发式会拒绝的字符串不可能有快取条目 |

  **二、网络与磁盘**

  | 项目 | 原本 | 改后 |
  | --- | --- | --- |
  | HTTP 连线 | 每个请求结束后 `disconnect()` | 成功路径不再 `disconnect()`，socket 回到连线池，省下每个请求一次 TCP＋TLS 握手。3xx 刻意保留 `disconnect()`：`followRedirects=false` 时 JDK 的 `getErrorStream()` 对 3xx 回 `null`，body 未被消费，不断线无法保证还池 |
  | `ProtectedText` 正则 | 每次 `parse` 重新编译一条最多 1000 条 alternative 的正则，而来源是每 5 秒才变一次的玩家名快照 | 以 `(内容签章, preserveHanText, literals)` 为键的 4 槽有界快取。签章只用于快速排除，正确性由 `List.equals` 保证——签章碰撞不可能回传用不同 literals 编出的 Pattern。编译在锁内进行，一次快照变更只编一次，不是每个 worker 各编一次 |
  | 磁盘快取落盘 | 每一次 `put` 都复制整个 map（上限 10,000 笔）并重写整份文件 | 单一写者＋dirty 旗标（事件驱动，非计时去抖动）。第一个 `put` 仍同步写完才返回；另加 revision 守卫，避免 `clear()` 期间的 in-flight flush 把已删条目写回去，例外时复位旗标避免写入永久停止 |
  | 磁盘快取读取 | 整档同步读取发生在客户端执行绪 | 延迟载入：第一次 `get`／`put`／`size` 才读，且发生在 worker 上 |
  | 离线模型验证 | 每次启动都对数百 MB～GB 的模型重算全档 SHA-256 | 把「已通过全档 SHA-256」的结论写成模型旁的 `.verified` sidecar（大小＋mtime＋期望摘要＋版本化 header）。任何一项不符、读不到、格式错或截断，一律回退全档杂凑；`VerifiedDownloader.sha256` 仍是唯一真实来源。写入是 best-effort，写不进去只会让下次重算 |
  | 429 `Retry-After` | 读了标头但没有使用 | 真正采用：`wait = min(max(backoff, retryAfter), 30 秒)`（只支援 delta-seconds，HTTP-date 视为未知） |

  **三、渲染与切换**

  | 项目 | 原本 | 改后 |
  | --- | --- | --- |
  | 渲染快取淘汰 | 满 4096 笔时整张 `clear()`，导致整个画面同时退回原文、接下来数十帧全部重新翻译 | 沿用同档既有的 `renderedOutputOrder` ＋ `evictOldest`，一次淘汰 512 笔（`MAX/8`），且只在首次插入时入列 |
  | pending 期限 | 180 秒 | 40 分钟。原本的 180 秒短于 provider 可配置的最坏总耗时（`5 × (60s connect + 300s read + 60s 限流) + 3s 退避 ≈ 35 分`），仍在正常重试中的请求会被误判为废弃而回收 |
  | 按 F8／保存设置 | 在客户端 tick 执行绪上先 `shutdown()` 再建新的，而 `shutdown()` → `close()` → `awaitTermination(5 秒)` 会等 worker 做完当下的翻译 | 改为「先建 → 再换 → 背景关旧」：旧 session 一路服务到新的建好；建构失败时旧 session 完好无损；`awaitTermination` 移到背景 daemon。切换期间没有翻译空窗，连续切换最多只有一条 reaper 执行绪 |
  | 建置失败清理 | 建置阶段抛例外时，已建立的 provider 被丢弃（泄漏，含 `llama-server` 子行程） | 建置阶段包上清理：失败时用既有的 `instanceof AutoCloseable` 惯例 `close()` 已建好的 provider，并用 `addSuppressed` 保留清理失败，最后仍抛出原始例外 |
  | 速率限制睡眠 | 睡在 monitor 上（`ResilientTranslationProvider`、`TencentHunyuanProvider`）——另一条 worker 会卡在不可中断的等待，使 `close()` 的 5 秒 `awaitTermination` 逾时后仍关闭 provider | 改为「锁内原子预约＋锁外睡眠＋醒后重检」。速率上限与 slot 序列不变 |

  **三项行为取舍（使用者可能察觉）**

  - **快取档损毁或不可读时改为静默退化成空快取**（原本建构子会抛 `IOException`，让平台回滚到上一份设定并把错误往上报）。取舍理由是不让游戏起不来，代价是重新翻译；若每次 `cache.get` 都重试读档，等于把磁盘 I/O 放回翻译热路径。
  - **`.verified` 快取可被同时伪造大小与 mtime（`touch -r`）绕过**。这是所有 mtime 型快取的固有取舍；能写入该目录者本来就能替换被执行的引擎与 JAR。
  - **磁盘快取在 flush 进行中时进来的 `put` 只存在记忆体**，硬杀或断电会遗失「一次 flush 视窗内的那些」（实务上数笔到数十笔，硬上限 10,000 笔）；正常关闭不遗失。
- **对外请求的版本号收敛为单一来源**：原本有两份，其中 `VerifiedDownloader` 停在 `1.1`，也就是每次下载模型都对外宣告一个三版前的版本号。现在统一由 `UserAgent.VALUE` 提供，本版为 `MCAutoTranslationTool/1.4`。
- **发布目标由 33 个增至 48 个**：新增 15 个从未出货的 NeoForge 目标（1.20.4、1.20.6、1.21、1.21.2、1.21.4～1.21.10、26.1、26.1.1、26.1.2、26.2），包含 NeoForge 26.x 全线。NeoForge 1.20.2 因为 `neoFormRuntimeDependenciesNeoForgeClasses` 解析不到依赖而不发布。可安装 JAR 由 16 个增至 **31 个**，Release 资产由 17 个增至 **32 个**。过程中修好三个只有实际建置才会暴露的问题：26.x 的 toolchain 清单漏了 `platform-neoforge-26.`（`release version 25 not supported`）、26.x 的 `neoforge.mods.toml` 少了 `modLoader` 与 `loaderVersion`（建置成功但验证器挡下）。
- **Fabric 1.16 补回 1.16.0～1.16.4**：原本发布目标指向单一版本子专案，导致已发布的 `fabric-all.jar` 版本清单从 `1.15.2` 直接跳到 `1.16.5`，**这五个版本的玩家装了会载入失败**。已改用涵盖 1.16～1.16.5 的 bundle。
- **仓库与文件链接改用目前的帐号名称** `TWJohnJohn20116`（原本的旧名称仍会转址，但下载链接统一后较不易混淆）。
- **安装说明**：本版所有 31 个安装包版本号均为 1.4。安装前请删除 1.3.11 及更早的 JAR，每个游戏实例只保留一个匹配的 JAR。

**已知限制**

- **Fabric 1.13.2 没有指示器**：该版本线使用 yarn 1.13.2+build.604，缺少 `net.minecraft.client.Window`、`MinecraftClient.getWindow()` 与 `TextRenderer.getWidth(String)`，无法建立 HUD 挂勾。设置层、语言键与设置画面在该版本可正常使用，只是看不到指示器本身。Fabric 1.13／1.13.1（Ornithe）正常。

**验证**

- 本次工作阶段（HUD 指示器与旧平台支援）的每一波都以独立 PR 经 GitHub Actions 验证——这是唯一允许的建置途径，本机未执行任何编译或测试。代表结果：
  - fabric 1.13.x 两层的设置层与指示器：[run 37650905054](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/37650905054)、[run 37653345782](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/37653345782)
  - forge legacy 1.8.9／1.12.2 的指示器：[run 37659127435](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/37659127435)
  - forge legacy 1.8.9／1.12.2 的设置画面：[run 37661731368](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/37661731368)
  - 最后一次 `main` 推送（30/30 通过）：[run 37662932290](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/actions/runs/37662932290)
- 更早的 1.4 批次（运行时通知、四项翻译修正、性能优化、NeoForge 目标扩充）由各自的 PR 验证；本次工作阶段未重新核对它们的执行结果。
- **尚未执行**：`prepare-release` 尚未运行，因此 48 个目标的发布资产与 `verify_release_jars.py` 校验、`SHA256SUMS.txt` 都还没产生。发布前必须先跑这一步。
- **需要游戏内实测**（CI 无法覆盖）：指示器是否遮到原版 HUD 元素、拖拽手感、四个分页在小视窗下的排版；大型服务器下每 5 秒的周期性卡顿是否消失；F8 切换与设置保存是否不再冻结、切换期间译文是否不退回原文；离线翻译第二次启动是否不再卡顿；真实 429 带 `Retry-After` 的等待体感。
## 1.3.11 - 2026-09-23

- **新增支持 Minecraft 26.3（Fabric）**：适配 26.3 全新底层架构（SDL3 / InputConstants 按键系统），移除过往 GLFW 依赖。26.3 已纳入 `fabric-all` 的 26.x 族群，同一个 JAR 自动匹配 26.1、26.1.1、26.1.2、26.2 与 26.3。
- **全版本 4-Tab 现代化设置界面转正**：原本壅塞的 18 按钮双列网格重构为 🌐 常规、🎯 范围、⚡ 引擎、💬 发送四个分页。零外部依赖、纯原生组件渲染，像素级对齐 Minecraft 1.0 至 26.3。离线模式下自适应显示模型切换与下载状态；在线／LLM 模式下动态展开自定义端点；发送翻译未开启时自动禁用目标语言选项。
- **修复全平台共 65 项缺陷**，其中影响最直接的有：
  - 设置界面在所有平台显示原始翻译键名（如 `screen.universal_translator.tab.general`）而非中文标签；
  - Forge 1.21–1.21.5 开启发送翻译后每条聊天重复发送两次；
  - NeoForge 26.2 进入世界即崩溃，NeoForge 1.20.2／1.20.4 完全无法加载；
  - 关闭离线翻译引擎时界面冻结数分钟，关机时可能阻止游戏退出；
  - 渲染异常后翻译永久停止、worker 崩溃后翻译静默停止并持续占用内存；
  - 玩家名、服务器地址、域名与 IP 在部分路径下未被保护即送往翻译服务；
  - 登录后的开放重定向，以及私有网段误判导致 `fda.gov` 等公网域名被允许明文 HTTP。
- **安装包内嵌实现已去重压缩**：`fabric-all.jar` 内嵌子模组改以未压缩格式存储、由外层统一 DEFLATE，跨版本重复的 class 与资源深度去重，体积由 9.22 MB 缩减至约 7.06 MB。
- 更新全部 33 个精确发布目标与 16 个最终安装包的版本号为 1.3.11；更新前请删除 1.3.10 及更早文件，每个游戏实例只保留一个匹配的 JAR。

**验证**

- `translator-core`：编译、单元测试、核心自测、提供商自测全部通过。
- Fabric：1.0–1.8、1.8–1.12、1.13.0/1.13.1/1.13.2、26.1.2/26.2/26.3 编译通过。
- 网站：类型检查通过，7/7 测试通过。
- 发布脚本：36/36 单元测试通过。
- 全部 33 个发布 JAR 通过 metadata、版本一致性、Mixin/refmap 与 SHA-256 校验；16 个最终安装包与 `SHA256SUMS.txt` 校验通过。

## 1.3.11-rc2 - 2026-09-23

修复全平台共 65 项缺陷。以下按严重程度列出。

**严重（导致功能失效或数据错误）**

- **Forge 1.21–1.21.5 聊天消息重复发送**：`outgoingChat` 以 `boolean` 返回值取消事件，但该版本使用的 eventbus 6 会丢弃监听器返回值（仅 eventbus 7 支持）。开启 `translate-outgoing` 后每条聊天会发送两次。已改为 `void` + `event.setCanceled(true)`，与 1.16.5/1.18.2/1.19.2/1.20.1 一致。
- **NeoForge 26.2 无法运行**：客户端代码仍调用 26.1 的 `Gui.getChat()`、`setOverlayMessage()`、`Minecraft.screen`，进入世界后抛出 `NoSuchMethodError`。已移植至 26.2 的 `Gui.hud` API。
- **NeoForge 1.20.2 / 1.20.4 无法加载**：mod 描述档误名为 `neoforge.mods.toml`，但对应的 FML 1.0.16 / 2.0.17 只读取 `META-INF/mods.toml`，导致 mod 完全不被加载。已更名为 `mods.toml`。

**高（安全、崩溃、静默失效）**

- **明文 HTTP 被误放行**：私有网段判断对任何主机做字符串前缀比对，`fda.gov`、`fdroid.org`、`fd00.example.com` 被误判为内网而允许明文传输。已改为真正的 IPv6 字面值解析（`fc00::/7`、`fe80::/10` 位掩码），并修正 `::1` 因括号未剥离而失效的判断。
- **离线翻译引擎关闭时界面冻结数分钟**：`ensureRunning` 以 `synchronized` 持有实例监视器跨越模型下载与 90 秒健康检查轮询，渲染线程调用 `close()` 会长时间卡住，关机挂钩亦可能阻止 JVM 退出。已改为专用锁（持锁期间绝不阻塞等待）。
- **翻译静默停止（内存泄漏）**：worker 抛出 `Error` 时 future 永不完成，待处理条目泄漏，对照表卡在上限 128 后翻译完全停止。已加入超时回收机制。
- **翻译静默停止（样式守卫锁死）**：Forge 与 Fabric 的渲染上下文仅在正常路径弹出，渲染方法抛异常后深度永久滞留，所有 GUI 文本停止翻译。已改为每帧开头防御性重置。
- **聊天格式丢失**：Forge 将带样式的文本扁平化并只套用第一段样式，后续段落的颜色、粗体、点击事件全部丢失。已改为检测多样式时不扁平化。
- **登录后开放重定向**：网站返回路径清洗未在规范化后复查，`/a/..//evil.com` 会变成 `//evil.com`（浏览器视为跨站）。已加入规范化后复查、反斜线拒绝与反复百分号解码检查。
- **发布校验可被绕过**：JAR 路径检查未拦截 Windows 磁盘机前缀与反斜线，`C:/Windows/Temp/evil.txt` 可通过校验。已修复（对 525 个历史压缩包普查，零合法条目受影响）。
- **macOS 离线模型安装失败**：归档符号链接链（`libggml.dylib` → `libggml.0.dylib` → 实际文件）在 Windows 上解压失败。已支持多级链接链解析。

**中（功能异常、配置损坏）**

- 华为云繁中翻译全部失败（语言码大小写不符）。
- 有道翻译多行结果仅保留第一行。
- OpenAI 兼容端点 `max_tokens` 上限过低导致长文被截断。
- 受保护文本（域名、IP）在 Minecraft 格式代码后仍会泄露给翻译服务。
- 翻译输出为空字符串时仍通过校验并污染磁盘缓存。
- 非 LLM 提供商会覆盖其他提供商的 API 设置。
- 诊断日志脱敏不完整，部分形式的密钥可能写入日志。
- 繁体中文地区变体（`zh-Hant-TW` 等）无法正确识别。
- CJK 扩展 A 区文字未被识别为中文。
- 小窗口下设置面板按钮溢出画面。
- 主翻译中断后仍会调用备用服务，可能在关闭后继续外发请求。
- **所有平台的设置界面显示原始翻译键名**：标签使用了从未存在过的语言键，游戏内直接显示 `category.universal_translator.general` 这类字符串。已改用实际存在的键。
- Fabric 1.13 切换提供商时输入框与按钮重叠。
- 发布脚本可能误删整个发布目录（含全部历史版本）。
- 本机发布校验因 Windows 路径问题静默跳过版本比对。
- 构建脚本中 Legacy Forge 路径在目录搬迁后失效。
- 网站社交预览图指向 `localhost`，所有链接预览失效。
- 网站笔记 API 对非字符串输入返回 500，且错误信息泄露内部细节。
- Fabric 1.13 孤立源码树（26 个文件）未接入任何构建路径，其中包含唯一的空指针守卫；已移植守卫后移除死代码。
- Legacy Forge 版本号硬编码为 1.3.8，与构建配置不符。
- Fabric 本地输入守卫在查询失败后永久失效，导致玩家自己的输入被发送翻译。

**低（边界条件与一致性问题）**

- 反射失败被静默吞掉，快捷键切换无任何反馈。
- 服务器地址在特定配置下不再受保护。
- 窗口对象未做空指针检查。
- 归档解压路径异常未拦截，泄露暂存目录信息。
- 网站 worker 中一段永不生效的图片优化分支每次请求抛异常。
- 网站宣称支持 1.16.0–1.16.4，但实际发布包不含这些版本。
- JSON 解析接受非标准空白字符与带符号的转义序列。
- 翻译失败状态判断未去除空白。
- 重入守卫在缓存溢出时被一并清除；重试表清空造成惊群。
- 发布校验对空 mixin 配置形同虚设。

**验证**

- `translator-core`：编译、单元测试、核心自测、提供商自测全部通过。
- Fabric：1.0–1.8、1.8–1.12、1.13.0/1.13.1/1.13.2、26.1.2/26.2/26.3 编译通过。
- 网站：类型检查通过，7/7 测试通过（新增开放重定向测试）。
- 发布脚本：36/36 单元测试通过。

## 1.3.11-rc1 - 2026-09-16

- **新增支援 Minecraft 26.3（Fabric）**：
  - 適配 26.3 全新底層架構（SDL3 / InputConstants 按鍵系統），移除過往 GLFW 依賴。
  - 將 26.3 完整納入 `fabric-26.x` Bundle，提供單一 JAR 自動匹配 26.1、26.1.1、26.1.2、26.2、26.3 五大版本。
- **全版本 4-Tab 現代化設定介面（UI）正式穩定版候選**：
  - 提供 🌐 常規、🎯 範圍、⚡ 引擎、💬 發送 4 分頁清爽排版。
  - 零外部依賴、純原生元件渲染，像素級對齊所有版本（Minecraft 1.0 至 26.3）。
- **更新全部發布目標與安裝包版本號為 1.3.11-rc1**。

## 1.3.11-beta.3 - 2026-09-10

- **全版本游戏内设置界面（UI）彻底重构**：将原本壅塞的 18 按钮双列网格重构为现代化的 4 分页（🌐 常规、🎯 范围、⚡ 引擎、💬 发送）架构。零外部依赖，100% 依托 Minecraft 原生 GUI 组件，带来极致清爽与直观的配置体验。
- **动态情境适配**：离线模式下自适应显示离线模型切换与下载状态；在线/LLM 模式下动态展开专用自定义端点与进阶设置；发送翻译开关未开启时自动连动禁用目标语言选项。
- **项目架构与工程深度整洁化**：
  - 彻底清理孤立的旧版目录与散落脚本，将 Legacy Forge 与 Fabric 微版本全数收归对齐的标准模块路径。
  - 新增 VS Code / Cursor 项目智能折叠与暂存目录过滤，开发体验更纯净。
- **跨平台与版本兼容性增强**：完美兼容 Minecraft 1.0.0 至 26.2 快照（全数 60+ 个版本），针对 26.2 的屏幕调度系统（`minecraft.gui.setScreen`）与 Fabric 1.13 多映射渲染进行专门适配。
- 此为测试版，不取代 1.3.10 正式版。请从 GitHub Pre-release 下载对应 JAR，并先删除该实例中的旧版文件。


- 优化 `fabric-all.jar` 的 Jar-in-Jar 压缩策略：内嵌 45 个子模组改以未压缩格式存储，由外层统一进行 DEFLATE 压缩，使跨版本重复的 class 与资源能够深度去重，安装包体积由 9.22 MB 缩减至约 7.06 MB（缩减约 23%）。
- 继承 1.3.11-beta.1 改进：主界面右上角改为单一「翻译设置」入口，点击后打开完整设置页。
- 此为测试版，不取代 1.3.10 正式版。请从 GitHub Pre-release 下载对应 JAR，并先删除该实例中的旧版文件。

## 1.3.11-beta.1 - 2026-09-09

- 主界面右上角改为单一「翻译设置」入口，点击后打开完整设置页；不再在主界面直接显示翻译总开关、原版界面开关和目标语言三个按钮。
- 此为测试版，不取代 1.3.10 正式版。请从 GitHub Pre-release 下载对应 JAR，并先删除该实例中的旧版文件。

## 1.3.10 - 2026-09-08

- 将 Fabric 1.13（Ornithe 1.13／1.13.1 与 Legacy Fabric 1.13.2）和 1.14–1.15 打入 `fabric-all`；Loader 按游戏版本选择内嵌实现。1.14–1.14.2 仍为无法 remap 的引导实现，完整设置页从 1.14.3 起提供。
- 新增 Forge 1.18.2（Forge 40.2.21、Java 17）正式发布目标。
- 更新全部 33 个精确发布目标与 16 个最终安装包的版本号为 1.3.10；更新前请删除 1.3.9 及更早文件。

## 1.3.9 - 2026-09-08

- 下载页与 README 按加载器分组：Fabric 一个 JAR，Forge／NeoForge 按已验证兼容族群选择，避免一张总表过长。
- 说明 NeoForge／FML 无法像 Fabric Loader 那样在同一 JAR 内嵌多套同 `modId` 实现；Forge 仍只在 payload 兼容的相邻版本间共用 JAR。
- 发布准备脚本会提示尚未合并、但内容相同的 Forge JAR。
- 更新全部 30 个精确发布目标与 15 个最终安装包的版本号为 1.3.9；更新前请删除 1.3.8 及更早文件。

## 1.3.8 - 2026-08-21

- 修复 Forge 与 NeoForge 开启“发送消息翻译”后，已翻译消息再次触发发送事件、形成重复翻译并导致消息无法正常发出的递归问题；内部重发阶段现在会绕过自身的翻译拦截，并在异常后可靠恢复状态。
- 新增全部现代 Forge／NeoForge 发送翻译入口的重入守卫检查，并重新构建、校验全部 30 个精确发布目标及 15 个最终安装包。

## 1.3.7 - 2026-08-18

- 修复 Fabric 1.20.x 与 ChatImage 等聊天增强模组共同使用时，历史聊天消息恢复阶段反复触发不兼容的 `String`／`Text` 类型转换、导致界面长时间卡住的问题；兼容层会先规范化聊天上下文，再交给第三方 Mixin。
- 新增聊天上下文兼容性自测，并重新构建、校验全部 30 个精确发布目标及 15 个最终安装包。

## 1.3.6 - 2026-08-16

- 修复离线引擎兼容重试仍重复携带新版 `-fit`／`--no-direct-io` 参数的问题；如果启动器缓存了旧版 llama.cpp，首次启动失败后会改用仅含长期兼容 `--no-mmap` 的最小参数重新启动。
- 离线引擎缺少执行权限、文件缺失、启动超时、参数不兼容、CPU 指令不兼容、系统因内存压力终止进程时，游戏内会显示对应的中文原因；超时诊断会附上本次 `llama-server.log` 的安全摘要。
- 明确 1.21.x Forge／NeoForge 应使用发布表指定的 Java 21；崩溃包中的 1.3.3 在 Java 25 下出现 LWJGL JNI 不兼容警告，而 Java 21 记录已正常进入世界，更新时必须先移除旧版 JAR，避免多个版本并存。
- 1.3.6 完整继承 1.3.5 的设置页防重叠、翻译诊断可见性、1.21.x 聊天 HUD、NeoForge 1.21.1、旧版 Forge API 设置与离线断点下载修复，并重新构建全部正式发布目标。

## 1.3.5 - 2026-08-16

- 修复 Forge 1.8.9 等版本只为通用 OpenAI 兼容服务显示 API 设置、DeepSeek／通义千问／火山方舟／智谱无法在游戏内填写密钥的问题；五种 LLM 服务现在都可独立编辑并保存端点、模型和密钥，切换服务不会互相覆盖凭据。
- 修复离线模型或引擎已完整写入 `.part`、但游戏在最终重命名前退出后，下次启动可能因从文件末尾继续请求而反复收到 HTTP 416 的问题；完整且校验通过的分片会直接转为正式文件，校验失败则安全重下。
- 修复经典与动态设置界面在低分辨率或较大 GUI 缩放下，首行按钮与标题分隔线、底部按钮与运行状态文字发生重叠的问题；字段名称只保留在输入框内，界面背景继续保持原有透明效果。
- 修复“翻译诊断”界面因六位 RGB 颜色被新版渲染器视为全透明而显示空白的问题；全部自绘诊断文字统一改为显式不透明 ARGB。
- 修复新版 Minecraft 将六位 RGB 自绘文字颜色解释为透明色的问题；翻译服务选择弹层的标题、候选服务与当前选中项现在会以显式不透明 ARGB 颜色显示，服务可正常辨认和切换。
- 同步修正全部已发布 Fabric、Forge 与 NeoForge 设置选择界面的自绘提示、状态及成功文字颜色，并新增构建守卫，防止六位透明颜色再次进入选择界面。
- 发布流程在刷新已有正式版时会显式更新 GitHub Latest 标记，确保回退和后续恢复发布都指向实际推荐版本。

## 1.3.4 - 2026-08-15

- 修复低分辨率或大 GUI 缩放下“保存并应用”按钮超出屏幕、下拉列表打开时第一次保存点击被遮罩吞掉的问题；设置页会整体自适应上移，列表外点击可直接继续触发原按钮。
- 停止或重新配置离线模型时改为后台回收 llama.cpp 进程，不再让 Minecraft 主线程同步等待最多四秒。
- 修复 Fabric 1.19.3／1.19.4 的 `TextRenderer` Mixin 严格注入崩溃，以及 1.20.5／1.20.6 聊天 HUD 方法签名变化导致的启动崩溃。
- 修正 Fabric 1.16／1.16.1 的 API 版本范围，并修正 1.17–1.19.1 实际提供 `fabric`、项目却错误依赖 `fabric-api` 的加载失败；1.16–1.20.6 的已实现版本现并入 Fabric 全版本发布 JAR。
- 离线引擎压缩包解压新增条目数量与总展开大小上限，继续保留路径穿越和符号链接校验。
- Fabric 已有图形设置的版本新增可选 Mod Menu 入口；Forge 1.16.5、1.19.2、1.20.1 与 26.x 补齐同功能图形设置。
- 正式发布新增 NeoForge 1.21.3 与 1.21.11 下载，并保留 NeoForge 1.20.1、1.21.1 支持。

## 1.3.3 - 2026-08-14

- 新增翻译关键词屏蔽：可在设置中用逗号、分号或换行配置关键词；原文只要包含任一关键词，就会保持原样且不会发送给离线模型或在线 API。
- 新增“玩家名称翻译”开关。为保持既有隐私行为，该开关默认关闭；开启后玩家名可参与翻译，服务器地址、网址、数字与格式代码仍会原样保护。
- Fabric 各正式支持版本与 Forge 1.8.9／1.12.2 设置界面可直接编辑屏蔽词和玩家名开关；现代 Forge 与 NeoForge 可使用同名配置项并按 `U` 重新载入。
- 配置格式升级到版本 6，并同步接入 Fabric、Forge 与 NeoForge 的全部发布运行时；旧配置会保留原有设置并自动补充安全默认值。
- 修复不同文字类型或不同玩家名保护快照可能错误共用缓存／进行中请求的问题，并限制动态保护项的数量与长度，避免异常配置导致过大的正则表达式。

## 1.3.2 - 2026-08-14

- 新增独立的“原版界面翻译”开关，可单独控制 Minecraft 首页、原版菜单等内容；关闭它不会影响聊天、物品提示、模组与整合包界面翻译。
- 在 Minecraft 首页右上角直接显示翻译总开关、原版界面开关和目标语言按钮，修改后立即保存并应用，无需离开首页或编辑配置文件。
- 目标语言改为可直接选择的十种预设：简体中文、繁體中文、English、日本語、한국어、Français、Deutsch、Español、Português 与 Русский；正式发布范围内的设置界面不再要求手动输入语言简称。
- 修复部分 Windows 启动器目录中离线 llama.cpp 引擎读取模型后以退出码 1 结束的问题：默认关闭自动内存适配与直接文件 I/O，首次失败后自动使用无内存映射兼容模式重试；错误提示会保留真正的模型读取、内存或 CPU 后端错误，不再只显示通用清理日志。
- 修复本地 LibreTranslate、OpenAI 兼容接口或 API 回退服务未启动时只显示 `Connection refused: getsockopt` 且重复重试的问题；现在会显示不含密钥和请求路径的中文处理提示，立即停止无效重试，并在日志中记录安全的提供商标识以便排查。

## 1.3.1 - 2026-08-13

- 新增百度翻译、腾讯云机器翻译 TMT、阿里云机器翻译、有道智云、火山引擎机器翻译、讯飞机器翻译与华为云机器翻译专用适配，并保留 LibreTranslate 与腾讯混元旧兼容接口。
- 新增 DeepSeek、阿里云百炼／通义千问、火山方舟、智谱 GLM 预设，以及通用 OpenAI Chat Completions 兼容服务。
- 新增可配置的 HTTP JSON 翻译服务：支持安全的请求模板、鉴权／额外请求头及嵌套响应路径，不执行脚本；远程端点强制 HTTPS，并禁止危险请求头和换行注入。
- 所有在线服务共用可配置连接／读取超时、1–5 次有限重试与请求间隔；仅网络故障、超时、限流和服务器端错误会重试，鉴权与请求格式错误立即返回原文。
- 各 Fabric、Forge 与 NeoForge 配置加载器统一迁移到配置版本 4；新增服务在正式发布的 27 个精确构建目标中使用同一工厂、语言代码映射与密钥校验。
- 合并远端新增的 Fabric 1.0–1.20 邻近版本源码，并将其中具备完整翻译运行时与设置界面的版本同步到 1.3.1 提供商目录；仅含旧版加载引导的目标不会冒充完整翻译支持，新增源码目标也不会在未经相同发布验证前冒充正式下载支持。
- 新增依赖零外网和真实密钥的提供方自测，覆盖嵌套 JSON、签名摘要、自定义模板、请求头安全、服务目录轮换和配置实例化。

## 1.3.0 - 2026-08-13

- 将 27 个精确版本构建产物整理为 13 个可直接安装的发布 JAR，覆盖 Forge 1.8.9、1.12.2、1.16.5、1.19.2、1.20.1、1.21／1.21.1／1.21.3–1.21.11 与 26.1–26.2，Fabric 1.16.5、1.19.2、1.20.1、1.21–1.21.11 与 26.1–26.2，以及 NeoForge 1.20.1 和 1.21.1；1.20.1 Forge／NeoForge 保留为分别验证的加载器专用文件。
- Fabric 统一为内含 19 个精确实现、由 Loader 自动选版的单一 JAR；Forge 相邻版本在 payload 兼容时合并为四个兼容族群；Forge／NeoForge 1.20.1 保留加载器专用 JAR，其余不同 API 版本继续独立发布。
- 合并 Fabric/Forge 1.16.5、1.19.2 与 1.20.1 的稳定源码和构建逻辑；新增通用发布 JAR 验证器，检查 metadata、版本一致性、Mixin/refmap、Fabric 内嵌 JAR、Forge 运行时映射与 SHA-256；并修正 Forge 26.1.1 发布 JAR 缺少 Mixin class 的问题。
- 修正 Forge 1.8.9 与 1.12.2 的 `mcmod.info` 仍写入旧测试版号的问题，并加入发行文件名与 JAR metadata 版本一致性测试。
- 修正分片 Fabric 1.21.x JAR 在 GitHub Actions 重组后仍保留分片、导致发布校验失败的问题；所有 Python 校验任务统一使用跨平台可用的 `python3`。
- 全部目标完成干净构建、核心自测、Mixin/refmap 与发行结构校验；Fabric 两个整合 JAR 通过全部内嵌版本的真实 Loader 选版测试，NeoForge 1.20.1 使用 Java 17 实际启动并完成模组初始化。

## 1.3.0-beta.3 - 2026-08-13

- 合并 Fabric 26.x 与 Forge 26.x 的重复源码，并将 Forge 1.21/26.x 的公共构建逻辑及版本 metadata 改为共享模板；各版本目录仅保留依赖与兼容性参数。
- 修正 1.3.0-test.2 的 Forge 1.16.5、1.19.2 与 1.20.1 下载误用 Mojmap 开发 JAR，避免启动时因 `Minecraft.getInstance()` 未转换为 SRG 名称而崩溃；构建与发布流程新增运行时映射验证。
- 根 Gradle 构建新增 `targetPlatform` 选择器，完整平台任务可自动载入所需模块，整合 JAR 会展开其版本依赖；默认只配置共用核心，避免单一目标触发所有 ForgeGradle 与 Loom 工作区。
- 根构建加入 Java Toolchain，并让 Gradle Wrapper 在 `JAVA_HOME` 失效时回退至 `PATH` 中的 Java。
- Forge 1.21 系列改用共用核心、共用资源与四个 API family，移除各精确版本间的大量重复 Java、语言及 Mixin 文件；NeoForge 1.21.1 同步重用这些来源。
- 新增 Minecraft 1.20.1 NeoForge 独立构建，目标为 NeoForge `1.20.1-47.1.106`
  与 Java 17；复用已验证的 1.20.1 翻译实现，但使用独立加载器依赖和下载文件名。
- NeoForge 依赖范围固定为 `[47.1.106,47.2)`，避免误装 Forge 47.4.x 或其他不兼容
  的 1.20.1 加载器实现。

## 1.3.0-test.2 - 2026-08-12

- 合并 1.2.1 正式版以及之后已进入 `main` 的 Forge 平台适配。
- 玩家发送翻译继续保持可选、异步和有序，并使用单独选择的发送目标语言；命令保持原样。
- 修正 Fabric 1.16.5 对旧版聚合 Fabric API 的运行时依赖标识。
- Forge 1.16.5 的 Mixin 兼容级别恢复为 Java 8，避免在该版本的原生 Java 8 环境中拒绝启动。
- Forge 1.16.5 与 1.19.2 的辅助上下文钩子改为容错注入，Forge 1.20.1 的物品提示框生产者钩子也保持可选；核心字体绘制翻译继续生效，避免重映射后的正式 JAR 因上游目标方法变化而启动崩溃。
- Forge 1.21.9 及以上改用新版直接事件总线注册按键，不再把该事件错误注册到 Mod 总线。
- Forge 与 Fabric 26.x 的 Mixin 兼容级别降至运行时可识别的 Java 21，并新增构建守卫，避免 Java 25 配置导致启动期失败。
- 为现代 Forge 的 macOS 开发启动配置补充首线程 JVM 参数，确保可执行实际客户端启动验证。
- 测试版下载扩展为 25 个 JAR：覆盖 Fabric 1.16.5、1.19.2、1.20.1、1.21–1.21.11、26.1–26.2，以及对应的 Forge 已验证目标；带后缀版本只发布为预发布，不会替代 Latest 正式版。

## 1.2.1 - 2026-08-12

- 新增 Fabric 1.21.x 单一下载 JAR，精确支持 1.21 至 1.21.11 的十二个正式版本；
  每个版本保留独立实现，由 Fabric Loader 自动选择匹配的内嵌 JAR。
- 新增 Fabric 26.x 单一下载 JAR，精确支持 26.1、26.1.1、26.1.2 与 26.2；
  其中 26.1.1 使用 Fabric API 0.145.4+26.1.1。
- 两个整合 JAR 都加入真实 Fabric Loader 解析测试，发布建置会验证每个内嵌版本均存在，
  并确认 Loader 不会载入错误版本的实现。
- 保留 v1.2 的旧平台下载，并以 1.2.1 重新构建；本次 Release 共提供八个客户端 JAR
  与统一的 SHA-256 校验文件。

- 扩展 Fabric 1.21.x 单一下载 JAR：精确支持从 1.21 到 1.21.11 的全部十二个正式版本，并由 Fabric Loader 选择匹配的内嵌实现。
  通过嵌套 JAR 保留版本专属 Mixin，并加入 Fabric Loader 实际解析测试。
- 集成 Fabric 1.16.5、1.19.2、1.21.4、1.21.5 与 26.1.2 独立源码适配，并将
  1.20.1 适配的关键 Mixin 固定到完整方法描述符；所有平台继续使用精确游戏版本约束。
- 翻译渲染管线不再要求已经进入世界或连接服务器，因此使用 Minecraft 原版字体
  渲染器的模组菜单、任务书、配方/物品界面和整合包自定义标题屏也能自动翻译。
- 本项目自己的设置、LLM 设置、诊断页面以及玩家正在编辑的文字仍保持原样，避免
  配置输入被翻译；图片文字和完全绕过原版字体渲染器的第三方界面仍无法捕获。

## 1.2 - 2026-08-11

- 新增 Minecraft 1.20.1 Fabric 正式支持，使用 Java 17、Fabric Loader 0.18.1 与
  Fabric API 0.92.11。
- 新增 Minecraft 1.20.1 Forge 正式支持，使用 Java 17 与 Forge 47.4.10；发布 JAR
  已按 Forge 1.20.1 运行时命名重新映射，并包含完整 Mixin refmap。
- 新增 Minecraft 1.21.11 Forge 正式支持，使用 Java 21 与 Forge 61.2.0，并适配
  新版事件总线、聊天取消语义、键位分类及 Mojmap 运行时。
- v1.2 同时重新构建原有 Minecraft 1.8.9 Forge、1.12.2 Forge 与 1.21.11 Fabric
  版本，共提供六个独立客户端 JAR 和统一的 SHA-256 校验文件。
- 每个 JAR 只声明其完成构建验证的 Minecraft 版本和加载器；相邻版本因渲染 API、
  Mixin 目标和加载器 ABI 可能变化，不会在未经验证时冒险放宽版本范围。

- 设置画面新增目标语言快捷按钮，可在简体中文、繁體中文和 English 之间切换；
  `zh-TW` 会为离线模型明确要求繁体字，并映射到腾讯与 LibreTranslate 的繁中代码。
- 新增 Minecraft 26.2 Fabric 开发适配，迁移 GUI/HUD API，并完成干净构建、重混淆和共享核心自测。
- 设置、诊断、按键与运行提示会跟随 Minecraft 语言，现已完整支持简体中文、繁體中文及 English。
- 翻译已有颜色的服务器文字时保留原始颜色，不再被默认译文颜色覆盖成单色。
- 设置画面可以直接切换 Lite 与 Quality 离线模型；未知模型配置会回退 Lite。
- 新增不显示端点、密钥或服务器文字的翻译诊断页，集中显示服务、模型文件、缓存与运行状态。
- 新增 Minecraft 26.1 Fabric 客户端适配；已通过干净构建、Mixin 注入与主菜单启动检查，尚未发布下载。

## 1.1 - 2026-08-11

- 物品名称与 Lore 现在会作为同一组上下文翻译，减少箱子和物品提示中的错译；译文行数异常时自动保留原文。
- 离线引擎和模型下载会显示百分比、已下载大小与校验完成状态。
- 离线模型、在线 API 或回退服务失败时会显示明确错误，并始终保留原文，避免翻译故障影响游戏。
- API 回退成功后会显示实际使用状态，不再错误地持续显示主翻译服务失败。
- 三个目标版本共用上述修复：Minecraft 1.8.9 Forge、1.12.2 Forge 和 1.21.11 Fabric。

## 1.0

MC 自动翻译工具首个正式版本，完全公益、开源并永久免费下载。

- 支持 Minecraft Java 版 1.8.9 Forge、1.12.2 Forge 和 1.21.11 Fabric。
- 翻译聊天、记分板、Tab 列表、Action Bar、标题、Boss Bar、容器、物品名称与 Lore、告示牌及其他服务器界面文字。
- 默认使用本机离线模型，优先从适合中国网络环境的镜像下载，并保留可选 API 模式。
- 默认以译文替换原文，支持仅译文或原文加译文显示，以及独立译文颜色。
- 中英文混合内容只翻译英文部分，保护已有中文、玩家名、服务器地址、数字、网址和格式代码。
- 玩家输入、聊天搜索框、设置输入框和告示牌编辑内容保持原样，不参与翻译。
- 提供 `U` 控制面板和可自由改键的 `F8` 一键翻译开关。
- 翻译在后台执行并使用本地缓存；模型或 API 不可用时保留原文，不阻塞游戏。
- 离线引擎仅监听 `127.0.0.1`，退出或重新配置时自动关闭，不公开本机服务端口。
