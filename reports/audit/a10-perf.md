# a10-perf 審計報告

範圍：全 repo 效能熱路徑（每幀渲染鏈 / 每則訊息翻譯鏈 / 每次存檔與週期性鏈 / UI 重繪鏈）
配額：PERF-001 ~ PERF-005（恰好 5 條）、BUG-S-056 ~ BUG-S-060（有才寫）
產出：PERF × 5 / BUG-S × 5

> **方法聲明**：本報告完全來自靜態閱讀（`read` / `grep` / `glob`）。未編譯、未建置、未打包、
> 未執行任何專案腳本、未做任何 profiling 或 benchmark。所有 µs / ms 數字都是「操作數 × JIT 後
> 單一操作成本」的推估，**每條都標明假設**；量級可信，絕對值誤差約 ±50%。
> 所有行號均取自實際 `read` 過的內容。

## 排序依據

依「消除後收益 ÷ 重構成本」排序，同時以括號標出絕對影響量級。第 1 名是**吞吐上限**問題
（決定功能能不能用），第 2–5 名是**每幀成本**問題（決定掉不掉幀）。

| 編號 | 一句話 | 絕對影響 | 重構成本 | 觸發條件 |
| --- | --- | --- | --- | --- |
| PERF-001 | 批次層的 `batchServing` 把 provider 併發釘死成 1，`translation-workers` 形同虛設 | 吞吐 1.5–4 倍落差 | 低（移動 3 行） | 預設配置，所有 OpenAI 相容 provider |
| PERF-002 | 渲染鉤子每幀重建每個可見文字，且同一 draw 被 2–4 個 hook 各跑一次完整 lookup | 25–54 ms/s | 中～高 | 常態 |
| PERF-003 | 渲染查找把配置重的守衛（鎖／反射／全字串複製）排在任何 O(1) 快取探測之前 | 13.5–135 ms/s | 低 | 常態；開聊天框時最重 |
| PERF-004 | 診斷／統計畫面每幀 4 次檔案 stat + 5 次 `Pattern.compile` + 512 筆裝箱排序，且持有統計全域鎖 | 100–300 µs/幀 | 低 | 診斷畫面開啟時 |
| PERF-005 | `DebugLog` 在渲染／翻譯路徑上同步逐行做 6 次 syscall + 8 次正則編譯 | 34–56 ms/s | 中 | `debug-log=true`（預設 false） |

---

### PERF-001 · 批次層持有「服務者」角色跨過整個網路往返，provider 併發被釘死成 1

- **嚴重度**：結構性效能上限 ｜ **信心**：高
- **熱點**：`translator-core/src/main/java/org/universaltranslator/core/provider/OpenAiChatTranslationProvider.java:575-602`
  `translateBatched(...)`，以及 `:604-639` `serveBatch(...)`
- **受影響範圍**：所有走 `OpenAiChatTranslationProvider` 的線上 provider，即 `OnlineProviderConfig.java:552`
  建立的 OpenAI 相容家族（openai / deepseek / qwen / moonshot / zhipu / groq / together /
  openrouter / siliconflow …）與 `OnlineProviderConfig.java:519` 的 `AzureOpenAiTranslationProvider`。
  離線 loopback（`:311`）與 `ClaudeTranslationProvider` / `GeminiTranslationProvider`（各自獨立實作，
  全域 grep `batchServing` 僅 1 個檔案命中）不受影響。

#### 呼叫頻率 × 資料量估算

呼叫鏈（每次需要真正打 provider 時）：

```
TranslationCoordinator.java:354-356  worker 呼叫 provider.translate(request)
  → OpenAiChatTranslationProvider.java:308 translate(request)
  → :314 translateBatched(request)          ← batchSize=8 > 1，預設走這裡
  → :577-579 synchronized(batchLock) { pendingBatch.add(entry) }
  → :582-593 搶「服務者」角色：batchServing=true，否則 batchLock.wait(1000)
  → :594-596 serveBatch(entry)
      → :609-611 Thread.sleep(30)            ← batchWindowMillis，預設 30
      → :612-621 收集 group（只收這 30 ms 內到達且 sameJob 的 entry）
      → :623 translateGroup(group) → :656 translateBatch(...)  ← 真正的 HTTP 往返
      → :631-637 finally { batchServing = false; notifyAll(); }   ← 角色在此才釋放
```

**結構缺陷**：`batchServing`（`:124`，provider 實例欄位，非 per-job）在 `:587` 取得後，要到
`:635` 的 `finally` 才釋放，而 `:623` 的 `translateGroup` → `:656` `translateBatch` 的整個 HTTP
往返都在這個區間內。因此**任何時刻最多只有一個批次在飛**，其餘 worker 全部卡在 `:590`
的 `batchLock.wait(1000L)`。

估算（假設：`translation-workers=4`（`TranslationTuning.java:21`）、`batch-size=8`（`:26`）、
`batch-window-ms=30`（`:31`）、單次 chat-completion 往返 RTT ≈ 700 ms、矩陣的翻譯需求率 R 條/秒）：

| 情境 | 每批裝載（1 + R×0.030） | 每批週期（30 + 700 ms） | 實際吞吐 | 純 4 併發可達（4 / 0.70） | 落差 |
| --- | --- | --- | --- | --- | --- |
| 閒置 R=1/s | 1.03 條 | 730 ms | **1.4 條/s** | 5.7 條/s | 4.0× |
| 大廳 R=20/s | 1.6 條 | 730 ms | **2.2 條/s** | 5.7 條/s | 2.6× |
| 尖峰 R=60/s | 2.8 條 | 730 ms | **3.8 條/s** | 5.7 條/s | 1.5× |

關鍵比例：**合批窗口 30 ms 只有它要攤平的 RTT（700 ms）的 4.3%（1/23）**，所以每批幾乎裝不滿
（實際 1–3 條，而非設計的 8 條），合批只換到約 1.5 倍裝載，卻把管線深度從 4 壓成 1，淨損
1.5–4 倍。

#### 根因（結構性）

1. `OpenAiChatTranslationProvider.java:124` `private boolean batchServing;` —— 併發上限被寫成一個
   **布林**，而它應該是「在飛批次数 ≤ worker 數」的計數器。
2. `:587` 取得角色 → `:623` 網路呼叫 → `:635` 釋放。角色的生命週期跨越了整個 RTT，而角色唯一
   需要的區間只有 `:612-621` 的「收集 group」。
3. `:610` 的窗口是絕對毫秒常數，與 RTT 和需求率無關，所以無法隨負載自適應。
4. 兩個旋鈕從未對帳：`translation-workers` 經 `OnlineProviderConfig.java:249-253` →
   `FabricTranslationRuntimeBase.java:141-144` → `TranslationCoordinator.java:48-55` 決定
   `ThreadPoolExecutor` 大小；`batch-*` 則經 `OnlineProviderConfig.java:276-278` →
   provider 的 `tuning` 欄位。而 `TranslationTuning.workers()`（`:51`）**在生產碼從未被呼叫**
   （全域 grep 只命中 `ProviderSelfTest.java:1367`、`:1370`），是死碼。UI 上那個
   「翻譯執行緒數」旋鈕因此只影響了被批次層抵銷掉的池大小。

#### 量化影響

- 吞吐上限從「4 併發 ≈ 5.7 條/s」降到「1 批次 ≈ 1.4–3.8 條/s」。
- 後果鏈：`RenderTranslationSession.java:19` `MAX_PENDING_TRANSLATIONS = 128` 會先滿
  → `:220-228` 直接回原文 → `TranslationCoordinator.java:48-55` 的
  `ArrayBlockingQueue(128)` + `AbortPolicy` 在 128 個排隊請求後開始拒絕
  → `:199-202` 回 `"Translation queue is busy; retrying later"`
  → `RenderTranslationSession.java:310-323` 把它記成 failure，進入
  `FAILURE_RETRY_MILLIS = 30_000`（`:18`）的退避。
- 使用者可見症狀是**「大廳裡一半以上的字永遠維持原文」**，而不是「變慢」。以 R=30 條/s、服務
  2.2 條/s 計算，128 槽的 pending map 約 **4.5 秒**就飽和並維持飽和。
- 付費 API 面向：因為幾乎每批只裝 1–3 條，帳單上的請求數逼近「一條一請求」，等於**付了合批
  的延遲代價卻沒拿到合批的請求數折扣**。

#### 重構方向

把「服務者」角色的生命週期縮到只包住 group 的收集，讓最多 `workers` 個批次同時在飛：

```java
// serveBatch(...) —— 收集完就釋放角色，不等網路
List<BatchEntry> group = new ArrayList<>(maximumBatchSize);
try {
    if (batchWindowMillis > 0L) Thread.sleep(batchWindowMillis);
    synchronized (batchLock) {
        Iterator<BatchEntry> it = pendingBatch.iterator();
        while (it.hasNext() && group.size() < maximumBatchSize) {
            BatchEntry c = it.next();
            if (sameJob(c.request, leader.request)) { it.remove(); group.add(c); }
        }
        // 角色只保護「收集」，不保護「發送」：喚醒下一個等待者去收下一批
        batchServing = false;
        batchLock.notifyAll();
    }
    if (!group.isEmpty()) translateGroup(group);   // 網路在角色外，可與其他批次並行
} catch (...) { ... } finally {
    synchronized (batchLock) {
        for (BatchEntry e : group) e.settled = true;
        // 只在尚未釋放時釋放，避免 double-release
        if (batchServing) { batchServing = false; }
        batchLock.notifyAll();
    }
}
```

同時三件配套：
1. `batchServing` 由 `boolean` 改為 `int inFlightBatches`，上限 `tuning.workers()`，讓
   `translation-workers` 真正生效（並修掉 `TranslationTuning.workers()` 的死碼狀態）。
2. 窗口自適應：`window = clamp(batchWindowMillis, 0, min(200, rttEwma / 4))`；或「按需求率決定」——
   只有當前一秒的需求率 × 窗口 ≥ 1 時才等待。
3. `sameJob`（`:642-647`）目前不比較 `preserveHanText`/`protectedLiterals`，把批次鍵補齊以免
   不同保護集合的條目被併批（`TranslationCoordinator.java:149-151` 的 in-flight 鍵已含這些，
   批次層沒有）。

#### 預估收益

- 吞吐 1.4–3.8 條/s → **5.7 條/s 以上**（理想可到 `workers × batchFill / RTT`）。這是把主要功能
  從「半殘」救回「可用」的量級，不是百分比最佳化。
- 連帶消除 `"Translation queue is busy"` 失敗風暴與 30 秒退避，`TranslationStats` 的 failure
  計數會顯著下降。
- 付費 API 請求數在同樣的翻譯量下可降 1.5–3 倍。

#### 風險

低～中。移動 `batchServing = false` 的位置會讓多個批次並行，因此：
- `translateGroup` → `translateBatch` 必須確認對 provider 實例是無狀態的（`translateBatch` 只讀
  `tuning`/`endpoint`/憑證，不寫 provider 欄位；`reasoningModel`、`streamingUnsupported` 是
  `:548`、`:388` 的觀測旗標，並行寫入只會提早收斂，不影響正確性）。
- `pendingBatch.contains(entry)`（`:586`）在並行下的語意仍是「我的 entry 還在佇列裡」，成立。
- 建議加一個 `ProviderSelfTest` 斷言：4 個執行緒併發送不同文字時，**至少 2 個 HTTP 請求重疊**
  （用一個會 sleep 的假 endpoint 計數）。CI 走 GitHub Actions 驗證。

---

### PERF-002 · 渲染鉤子每幀重建每一個可見文字，且同一次 draw 被 2–4 個 hook 各跑一次完整查找

- **嚴重度**：結構性效能屎山 ｜ **信心**：高
- **熱點**：
  - `platforms/fabric/shared/rendered-common/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:54-84`
    `translate(OrderedText)`（逐 code point 攤平）
  - 同檔 `:87-118` `rebuildStyledRuns(...)`（第二次完整走訪 + 重建整棵樹）
  - 同檔 `:211-236` `translateStyledSiblings(Text)`、`:247-271` `collectStyledLeaves(...)`
  - `translator-core/src/main/java/org/universaltranslator/core/TranslationStyleRuns.java:62-80`
    `mergeAdjacent(...)`、`:83-98` `distinctStyleCount(...)`
- **受影響範圍**：所有平台。同一邏輯以近似複本存在於 9 個位置：
  `platforms/fabric/shared/rendered-common`（主）、`platforms/fabric/26.x/shared/common`、
  `platforms/fabric/1.14-1.15/shared/family/1.14-1.15-common`、
  `platforms/fabric/1.16/shared/family/1.16-1.16.1`、
  `platforms/fabric/shared/rendered-legacy`、
  `platforms/forge/shared/rendered-1.19.2-plus`、
  `platforms/forge/modern/1.16.5`、`platforms/forge/modern/1.18.2`、
  `platforms/forge/shared/legacy-common/.../LegacyRenderedTextBridge.java`

#### 呼叫頻率 × 資料量估算

呼叫鏈（同一行文字在**一幀內被完整查找 2–4 次**）：

```
GameRenderer.render → InGameHud.render （每幀）
  → ChatHud.render / PlayerListHud.render / InGameHud.renderScoreboardSidebar
     └─ 分類 push/pop：fabric/shared/mixin-1.16-1.19/.../ChatHudContextMixin.java:14-24
        （每幀一次，設計正確）
  → DrawContext.drawText(...)        ← hook #1
     platforms/fabric/shared/draw-context/.../DrawContextMixin.java:22-62（6 個 @ModifyVariable）
  → TextRenderer.draw → drawInternal(OrderedText,…)   ← hook #2
     platforms/fabric/shared/text-renderer-1.20-1.21/.../TextRendererMixin.java:21-26
  → 對齊／置中／tooltip 量測 → getWidth(...)          ← hook #3
     同檔 :28-31、:33-38、:40-45
  → RenderedTextBridge.translate(OrderedText):54
      → :58 new StringBuilder
      → :59 new AtomicReference<Style>
      → :60 new boolean[1]
      → :61-69 text.accept(逐 code point：appendCodePoint + style.equals(firstStyle.get()))
      → :70-77 多樣式時 rebuildStyledRuns(text) → :87-118 再走一次 accept
      → :78 translateRaw(original.toString())      ← 第 1 次全文複製
      → :79 original.toString().equals(translated) ← 第 2 次全文複製
  → translateRaw(:197-203) → FabricTranslationRuntime.java:33-35
  → FabricTranslationRuntimeBase.java:220-232 translateForRender(...)
  → RenderTranslationSession.java:162 lookup → :173 lookupInternal
      → :180 isCompletedOutput（見 PERF-003）
      → :184 new RenderKey(original, kind)   ← 鍵永遠是內容字串
      → :190 translated.get(key)             ← 新鮮 String，hashCode 未快取
  → 回到 :113-116 每個 style run 再各呼叫一次 translateRaw(run.text())  ← N 次額外完整查找
  → :117 rebuilt.asOrderedText() → Minecraft Language.reorder 整棵樹重新 tokenise
```

**為什麼是 2–4 次**：可見元件（聊天 10–20 行、計分板 15 行、玩家列表 60–80 行、名牌、物品數量）
約 90–110 條，但實際 `lookup` 次數是 250 次/幀量級 —— 多出來的約 150 次全是同一行的重複
（`DrawContext` hook + `drawInternal` hook + `getWidth` hook 各自跑一次）。程式自己的
`:140-142` 註解也承認「a GUI text can pass through both a high-level draw hook and TextRenderer」。

**逐項成本估算**（假設 60 FPS、每條文字 40 字元、`Style.equals` 在樣式相同時走 `this == o`
早退約 2 ns、不同時約 20–30 ns、`StringBuilder.appendCodePoint` ≈ 5 ns、一次 `String` 複製
40 字元 ≈ 20 ns、一次 TLAB 配置 ≈ 12 ns、`Language.reorder` 40 字元 ≈ 20 ns/字元）：

| 步驟 | 每次呼叫成本 |
| --- | --- |
| `:58-60` 3 個配置 + `:61` 捕獲式 lambda 1 個配置 | 4 × 12 ns = 0.05 µs |
| `:61-69` 40 字元 × (lambda + appendCodePoint + AtomicReference.get) | 40 × 12 ns = 0.48 µs |
| `:78` + `:79` 兩次 `toString()` | 2 × 40 × 0.5 ns + 2 配置 = 0.06 µs |
| `translateRaw` → 守衛鏈 + 快取探測（見 PERF-003） | 0.3–0.9 µs |
| 多樣式時 `:87-118`：第二次 accept + 3 個 ArrayList + `mergeAdjacent` + `distinctStyleCount` + N×`translateRaw` + N×`Style.withColor` + `asOrderedText` | 1.5–3.0 µs |
| **合計（單一樣式）** | **≈ 1.0 µs** |
| **合計（多樣式，聊天／計分板／玩家列表的常態）** | **≈ 2.5–4.5 µs** |

`:238-245` 的註解自承 team prefix/suffix 幾乎都是嵌套樹，所以多樣式是**常態而非邊角**。

#### 根因（結構性）

1. **重建結果不進任何快取**。`RenderTranslationSession.translated`（`:58`）只快取
   `String → String`；每 run 的 Style 結構、`sourceHasColor` 掃描（`:219-222`）、
   `distinctStyleCount`（`TranslationStyleRuns.java:83-98`）每幀從零重算。輸入 `Text` /
   `OrderedText` 在靜態計分板與玩家列表上每幀是**同一批物件實例**，這份身分資訊完全沒被利用
   —— `RenderKey`（`:727-752`）的鍵是 `text.equals`，把身分抹掉了。
2. **hook 掛在三個抽象層卻無「本幀已處理」去重**。`isCompletedOutput`（`:180`）只防
   自我翻譯，不防同一行被查三次。全域共 11 個 `@ModifyVariable` + 至少 142 個
   `RenderedTextBridge.translate` 呼叫點。
3. **`TranslationStyleRuns.mergeAdjacent:74` 是 O(n²) 字串拼接**：
   `new Run(previous.text + text, …)` 對連續 k 個同樣式 run 做 1+2+…+k 次複製。
4. **`translationStyledSiblings` 的 `getString()` 是 O(n × 樹深)**：
   `:254` `node.getString()` 回傳整個子樹的串接字串（O(子樹大小)），`:257` 再對每個 child
   各呼叫一次 `child.getString()`（O(各 child 子樹大小)），加總 O(n × 深度)，且每次都配置
   StringBuilder + String。`:260` 的 `full.length()` 又來一次。
5. **`distinctStyleCount:90` 是 O(k²) `Style.equals`**，`:87` 還配置一個 `ArrayList<Object>`。

#### 量化影響

- 大廳滿載（tab 全開 + 計分板 + 聊天）：140 條可見文字 × 3.5 µs ≈ **490 µs/幀**；
  加上約 150 次冗餘查找 × 0.8 µs ≈ 120 µs/幀 → **≈ 610 µs/幀 = 3.7% 幀預算 ≈ 37 ms/s**。
- 一般遊玩（只有聊天 + 物品欄，約 60 條）：60 × 3.5 µs ≈ 210 µs/幀 ≈ **13 ms/s**。
- 配置速率：每條文字約 10–12 個物件（含 `RenderKey`、`Run`、`Text.literal`、`Style`、
  `TranslationEntry`）→ 140 × 12 = 1,680 物件/幀 = **100,800 物件/s ≈ 2.5–3.5 MB/s** 的
  純垃圾，直接推高 young GC 頻率。
- 幀時間波動（jitter）比平均值更傷：`:117` 的 `Language.reorder` 在單一複雜元件（伺服器
  建的多層 team prefix）上會突然吃掉數十 µs。

#### 重構方向

1. **加一層有界的 identity 快取（最高收益、最低風險）**：在每個 bridge 內放
   `WeakHashMap<Object,Object>` 或固定 512 槽的 `IdentityHashMap` 環形表，key 用傳入的
   `Text` / `OrderedText` **實例**，value 用已經組好的 `Text` / `OrderedText`。命中時**直接回傳
   同一個物件**，`Language.reorder`、攤平、重建全部跳過。失效條件保守取「清空時機 =
   `RenderTranslationSession.clearRenderedTranslations()`」，由既有的 session 重建驅動。
2. **把 hook 收斂到兩處**：`TextRenderer.drawInternal(OrderedText|String)` +
   `TextRenderer.getWidth(OrderedText|StringVisitable|String)`。`DrawContextMixin` 只保留
   tooltip 與 `drawCenteredText` 這種「不經過 `drawInternal`」的路徑。1.14/1.15 的
   `drawWithShadow` hook 直接移除（它內部就呼叫 `draw`，同字串被翻 2–3 次）。
3. **`mergeAdjacent` 改 StringBuilder**：`TranslationStyleRuns.java:67-79` 用一個
   `StringBuilder` 累積當前 run，遇到樣式變化才 `toString()`。
4. **`collectStyledLeaves` 改單趟**：遞迴時直接回傳自己子樹的 `length()`，不要靠
   `getString()` 反推前綴（`:254`→`:261` 的 `substring` 也可省掉）。
5. **`distinctStyleCount` 改 `HashSet`**：`Style.hashCode()` 是值雜湊，可直接放進
   `HashSet<Object>`，把 O(k²) 降成 O(k)。

#### 預估收益

- 直接消除 **25–40 ms/s** 的渲染執行緒成本，以及 **2.5–3.5 MB/s** 的配置壓力。
- 第 2 項（收斂 hook）單獨就能砍掉約 120 µs/幀的**完全冗餘**成本，且是唯一能一次解決
  「9 份複本」的結構機會。

#### 風險

中～高。
- 快取住 `Text` / `OrderedText` 會延長生命週期，必須有界或 weak；且要處理「同一個 `Text`
  實例內容被就地修改」（`MutableText.append` 之後再畫）的情形 —— 保守做法是只在
  `Text` 的 `getSiblings()` 未被改動的前提下命中，或把快取壽命壓到單幀（一個
  `frameCounter` 遞增就清空），單幀快取已足以吃掉「同一行 3 個 hook」的冗餘。
- 收斂 hook 必須逐版本確認實際呼叫路徑（1.14 的 `drawWithShadow`、26.x 的 `prepare()`、
  1.8.9/1.12.2 的 ASM 注入點 `FontRendererTransformer.java:222-241`）。刪錯會漏翻，
  建議一次只收斂一個世代並用 GitHub Actions 驗證。

---

### PERF-003 · 渲染查找把 `synchronized`／反射／全字串複製排在 O(1) 快取探測之前

- **嚴重度**：結構性效能屎山 ｜ **信心**：高
- **熱點**：
  - `platforms/fabric/shared/base/src/main/java/org/universaltranslator/fabric/FabricTranslationRuntimeBase.java:220-232`
    `translateForRender(...)`
  - `translator-core/src/main/java/org/universaltranslator/core/RecentUserText.java:41-59`
    `shouldPreserve(...)`（`synchronized`）
  - `platforms/fabric/shared/guard-legacy/src/main/java/org/universaltranslator/fabric/FabricLocalTextGuard.java:23-30`
    `isLocalChatInput(...)`、`:32-59` `findChatField(...)`（反射）
  - `translator-core/src/main/java/org/universaltranslator/core/RenderTranslationSession.java:180`、
    `:508-514` `isCompletedOutput(...)`
- **受影響範圍**：`translateForRender` 的同一形狀在 4 個 runtime 各有一份：
  `FabricTranslationRuntimeBase.java:220`、`platforms/forge/modern/shared/common/.../ForgeTranslationRuntime.java:198`、
  `platforms/forge/1.21/shared/runtime/1.21-1.21.8/.../ForgeTranslationRuntime.java:196`、
  `platforms/forge/shared/runtime-1.21.9-plus/.../ForgeTranslationRuntime.java:196`、
  `platforms/forge/shared/legacy-common/.../LegacyTranslationRuntime.java:187`

#### 呼叫頻率 × 資料量估算

呼叫鏈（**每一次 render hook 都先付完整守衛鏈，才輪到快取**）：

```
TextRendererMixin.java:28-31  getWidth(String)          ← 每幀每條文字
  → RenderedTextBridge.java:26-33 translate(String)
  → :27 translateRaw → :198 TranslationRenderContext.isTextInput()   （ThreadLocal.get）
  → :201 FabricTranslationRuntime.translateForRender(text, :202 TranslationRenderContext.current())
                                                          （第 2 次 ThreadLocal.get）
  → FabricTranslationRuntimeBase.java:220 translateForRender
      :221-222 volatile 讀 session / activeConfig
      :223 config.allows(kind)                            （便宜）
      :225 currentScreen()                                ← 每幀 1–2 次
      :226 currentScreen()                                ← 同一個值再讀一次
      :226 isTranslatorScreen(...)                         （3 次 instanceof）
      :227 isLocalChatInput(original)  → FabricLocalTextGuard.java:23-30
             :24 client.screen instanceof ChatScreen
             :28 findChatField(screen)
                :54 known.get(screen)        ← ★ 反射欄位讀取，每次 lookup
             :29 matches(field.getText(), rendered)
                :81 stripLegacyFormatting(rendered)  → StringBuilder + 全文複製
                :82 visible.equals(typed) / visible.equals(typed + "_")  ← 後者還會配置
                :85 typed.contains(visible)  ← O(n·m) 樸素搜尋
      :228 recentUserText.shouldPreserve(original)  → RecentUserText.java:41 ★ synchronized
             :44 entries.isEmpty()           ← 空佇列早退「在鎖內」
             :47 normalize(renderedText)     （非空時：strip + trim = 2 次配置）
             :53-58 線性掃描 entries，每條 7 次 endsWith（:103-110）
  → 只有到這裡才 :231 active.lookup(...)
  → RenderTranslationSession.java:162 lookup → :173 lookupInternal
      :180 isCompletedOutput(original) → :508-514
             :509 translatedOutputs.containsKey(text)        （CHM 探測）
             :512 stripLegacyFormatting(text) → TranslationTextStyling.java:49-63
                     :53 new StringBuilder(text.length()) + 全文逐字元複製 + :62 toString
             :513 translatedOutputs.containsKey(unformatted) （第 2 次 CHM 探測）
      :184 new RenderKey(original, kind)      ← 每次 lookup 1 個配置
      :190 translated.get(key)                （第 3 次 CHM 探測）
      :194 rejected.containsKey(key)          （第 4 次 CHM 探測）
      :212 retryAfter.get(key)                （第 5 次 CHM 探測）
```

**頻率**：250 次 lookup/幀 × 60 FPS = **15,000 次/秒**（大廳）；一般遊玩約 60 條 × 2.5 hook
= 150 次/幀 = 9,000 次/秒。**每一次**都完整走完上面這條鏈，包括已經快取命中的那 99%。

#### 根因（結構性）

1. **順序錯了**：`FabricTranslationRuntimeBase.java:227-228` 的兩個守衛是「情境判斷」
   （聊天框、最近輸入），它們的結果**每幀只可能變一次**，卻被放在
   `:231 active.lookup` 之前、而且是在**每一次**文字查找上重算。正確順序是
   「先問快取 → 只有 miss 才問情境守衛」。
2. **`synchronized` 的空路徑早退寫在鎖內**（`RecentUserText.java:41` 的 `synchronized` +
   `:44` 的 `entries.isEmpty()`）。一般玩家只要在這一局打過一句話，`entries`
   在 30 分鐘內（`:9` `RETAIN_MILLIS`）恆為非空，於是**每一幀的每一次 lookup 都會**：
   取 monitor → `stripLegacyFormatting` + `trim` 配置 → 掃 10 條 × 7 次 `endsWith`。
   `synchronized` 方法本身還會讓 JIT 無法內聯整條 `translateForRender` 鏈。
3. **用反射讀聊天輸入框**：`FabricLocalTextGuard.java:54` 的 `known.get(screen)` 在
   ChatScreen 開啟期間對**每一次** lookup 執行一次反射欄位讀取。JIT 無法內聯
   `Field.get`，且它會阻斷整個 `translateForRender` 的方法內聯。
4. **`isCompletedOutput` 用內容比對辨識自己的輸出**（`RenderTranslationSession.java:508-514`）：
   每幀無條件對含 `§` 的字串做一次 `stripLegacyFormatting` 全文複製 + 再比對一次。
   正確判據應該是**字串實例身分**（`:342` 存進去的和 `:190` 取出來的是同一個 `String`
   實例），一次 `==` 就夠。
5. **同一個 `currentScreen()` 在 `:225` 與 `:226` 各讀一次**，`:226` 內的
   `MinecraftContentScope.isVanillaScreen(...)` 還對 `screen.getClass().getName()` 做
   `startsWith`。

#### 量化影響

估算假設：一次 `synchronized` 進出（未競爭、JDK 21 輕量鎖）≈ 12 ns；一次 CHM `get` ≈ 20 ns；
一次 TLAB 配置 ≈ 12 ns；`stripLegacyFormatting` 對 40 字元字串（含 4 個 `§`）
≈ 4 個配置 + 40 次 append ≈ 60 ns；反射 `Field.get`（已 inflate）≈ 25 ns；
`trim` + `equals` + `contains` ≈ 100 ns；`ThreadLocal.get` ≈ 3 ns。

| 情境 | 逐項 | 合計 |
| --- | --- | --- |
| **常態**（每次 lookup，15,000 次/s） | 2× ThreadLocal 6 ns + `new RenderKey` 12 ns + 5× CHM 100 ns + `synchronized` 12 ns + `stripLegacyFormatting`（`isCompletedOutput`，約 50% 的字串含 `§`）~30 ns 均值 + 4 次比較 ~20 ns | **≈ 0.18 µs/次 → 2.7 ms/s** |
| **`entries` 非空**（打過一句話之後的常態） | 再加 `shouldPreserve`：normalize 2 次配置 + 10 條 × 7 次 `endsWith` ≈ 0.9 µs | **≈ 1.1 µs/次 → 16 ms/s** |
| **`entries` 滿**（128 條，`:8`） | 128 × 7 = 896 次 `endsWith` ≈ 9 µs | **≈ 9 µs/次 → 135 ms/s** |
| **ChatScreen 開啟時** | 再加反射 `Field.get` 25 ns + `matches` 的 strip/trim/equals/contains ≈ 250 ns | **≈ 1.4 µs/次 → 21 ms/s** |
| **ChatScreen 開啟 + `entries` 滿** | 兩者疊加 | **≈ 10 µs/次 → 150 ms/s** |

另外，`isCompletedOutput` 的 `stripLegacyFormatting`（`:512`）在 50% 命中率下每幀配置
250×0.5×(StringBuilder + String) ≈ **125 個物件/幀 = 7,500/s ≈ 1 MB/s** 的純垃圾。
`shouldPreserve` 的 `normalize` 再加一層。

**這條特別惡劣的地方**：`RecentUserText.shouldPreserve` 擋掉的字串，本來在
`RenderTranslationSession.java:190` 之後的快取裡就會被 O(1) 接住 —— 也就是說
**它攔下來的東西幾乎都是白攔，純 overhead**。

#### 重構方向

1. **重排順序（改 3 行，收益最大）**：把 `isLocalChatInput` 與 `shouldPreserve` 移到
   `active.lookup(...)` **之後**，或者更精確地 —— 移進
   `RenderTranslationSession.lookupInternal` 的 `translated.get(key)` 之後。

```java
// FabricTranslationRuntimeBase.java:220 起
String translateForRender(String original, TextKind kind) {
    RenderTranslationSession active = session;
    FabricConfig config = activeConfig;
    if (active == null || config == null || !config.allows(kind)) {
        return original;
    }
    // 先問快取：命中就不可能再需要情境守衛（命中的字串已通過所有守衛才被存進來）
    String ready = active.cachedLookup(original, kind);   // 只做 translated.get + rejected
    if (ready != null) {
        return ready;
    }
    if (isTranslatorScreen(currentScreen())
            || isLocalChatInput(original)
            || recentUserText.shouldPreserve(original)) {
        return original;
    }
    return active.lookup(original, kind);
}
```

2. **`RecentUserText` 改 copy-on-write**：`remember` 時把 7 個 prefix 形式與原文一起放進一個
   `volatile` 的不可變 `HashSet<String>` 快照；`shouldPreserve` 只做
   `snapshot.isEmpty()` + 一次 `strip` + `contains`（O(1)）。移除 `synchronized`。
   且**只在 `kind == TextKind.CHAT` 時才呼叫**（它只可能影響聊天行的渲染）。
3. **`FabricLocalTextGuard` 改 `ClassValue<Field>`**：每個 screen class 只解析一次並永久
   鎖存；`:54` 的反射讀取降到只能靠 `MethodHandle` 或「每幀一次快取」（見第 4 項）。
4. **把情境守衛升到「每幀一次」**：在 `InGameHud.render` 或 `MinecraftClient.tick` 的 HEAD
   算一次 `localChatInputSnapshot`（一個 `String`），render 路徑只做一次
   `rendered.equals(snapshot)`，反射與 monitor 全部從熱路徑消失。
5. **`isCompletedOutput` 改身分比對**：`translatedOutputs` 由
   `ConcurrentHashMap<String,Boolean>` 改為固定 512 槽的環形 `String[]`，用 `==` 比對
   （`RenderTranslationSession.java:342` 存入的實例就是 `:190` 會取出的同一個實例）。
   只在 miss 時才退回 `stripLegacyFormatting` 的內容比對。
6. `:225`/`:226` 的 `currentScreen()` 提出成一個 local。

#### 預估收益

- 常態情境：**2.7 → 0.2 ms/s**；打過一句話之後：**16 → 0.2 ms/s**。
- `entries` 滿的大廳：**135 ms/s → 0.2 ms/s**（這一項單獨就是 0.8% 的核）。
- ChatScreen 開啟時：**21–150 ms/s → ≈ 0**。
- 附帶：`translateForRender` 恢復可內聯，`RenderTranslationSession.lookup` 的
  `DebugLog.global()` + `isEnabled()`（均為 volatile 讀）才有機會被 JIT 摺疊。

#### 風險

低。純粹是「順序」與「資料結構」調整，語意等價：
- 需要確認「命中快取的字串一定已經通過所有守衛」這個不變式。目前
  `RenderTranslationSession.java:306` 在 `completeLookup` 裡會用**當前**的
  `blockedKeywords` 再檢查一次，且 `:534` 的 `setBlockedKeywords` 會
  `clearRenderedTranslations()` 整表清空，所以不變式成立。但 `isLocalChatInput` /
  `shouldPreserve` 沒有對應的「清表」機制 —— 這也是它們不能純粹移到快取後面的原因，
  所以**必須**配合第 4 項的「每幀快照」：用一個
  `volatile` 的 `localChatInput` 每幀更新，快取命中時再比對一次（一次 `equals`）。
- 快取命中率統計（`TranslationStats`）不受影響，因為 `shouldPreserve` 攔下的字串從來
  沒進過 `translated`。

---

### PERF-004 · 診斷／統計畫面每幀重做檔案 stat、正則編譯與 512 筆裝箱排序，且持有統計全域鎖

- **嚴重度**：結構性效能屎山（單幀成本）｜ **信心**：高
- **熱點**：
  - `platforms/forge/shared/screens/1.18.2-1.21.11/src/main/java/org/universaltranslator/forge/UniversalTranslatorScreens.java:1443-1474`
    `renderDiagnostics(...)` → `:1452` `displayLines()`
  - `platforms/forge/modern/shared/common/src/main/java/org/universaltranslator/forge/ForgeTranslationRuntime.java:294-313`
    `diagnostics()` → `:310-311` `fileSize(...)` → `:315-321`（`Files.isRegularFile` + `Files.size`）
  - `translator-core/src/main/java/org/universaltranslator/core/TranslationDiagnosticsSnapshot.java:41`
    `sanitizeStatus(...)` → `:214-218` → `DiagnosticsLogExporter.java:76-102` 的 **5 次 `replaceAll`**
  - `translator-core/src/main/java/org/universaltranslator/core/TranslationStats.java:269-286`
    `snapshot()`、`:294-322` `localizedLines(...)`、`:709-718`、`:730-742`
- **受影響範圍**：`displayLines()` 每幀呼叫的同一 pattern 複製在 **13 個檔案**：
  `forge/shared/screens/1.18.2-1.21.11/.../UniversalTranslatorScreens.java:1452`、
  `forge/modern/1.16.5/.../UniversalTranslatorScreens.java:1461`、
  `forge/26.x/shared/api/26.1-26.1.2/...:164`、`forge/26.x/shared/api/26.2/...:164`、
  `forge/shared/legacy-common/.../LegacyDiagnosticsScreen.java:174`、
  `fabric/1.21/shared/screens/1.20.1-1.21.11/...:150`、`fabric/1.19/shared/screens/...:148`、
  `fabric/1.14-1.15/...:155`、`fabric/1.13/shared/screens/...:170`、
  `fabric/1.0-1.8/...:172`、`fabric/1.8-1.12/...:173`、
  `fabric/legacy/shared/screens/1.16-1.18/...:150`、`fabric/26.x/shared/api/26.1-26.3/...:149`

#### 呼叫頻率 × 資料量估算

呼叫鏈：

```
（畫面開啟中）GameRenderer.render → Screen.render → renderDiagnostics（每幀）
  → UniversalTranslatorScreens.java:1452 List<String> lines = displayLines()
      → :1477-1487 displayLines()
          → 統計頁：TranslationStats.java:294 localizedLines(tr)
          → 一般頁：:1489 safeLines() → ForgeTranslationRuntime.java:294 diagnostics()
                :310 fileSize(modelFile)   → Files.isRegularFile + Files.size   ← 2 syscall
                :311 fileSize(cacheFile)   → Files.isRegularFile + Files.size   ← 2 syscall
                :302 new TranslationDiagnosticsSnapshot(...)
                    → TranslationDiagnosticsSnapshot.java:41 sanitizeStatus(status())
                        → DiagnosticsLogExporter.java:82  trim
                        → :83 replaceAll(  Pattern.compile #1
                        → :86 replaceAll(  Pattern.compile #2
                        → :91 replaceAll(  Pattern.compile #3
                        → :94 replaceAll(  Pattern.compile #4
                        → :99 replaceAll(  Pattern.compile #5
          → TranslationStats.localizedLines
                :296 snapshot()  → :270 synchronized(lock)     ★ 與翻譯 worker 同一把鎖
                       :271 new ArrayList<Long>(latencyCount)  ← 最多 512 筆
                       :272-274 samples.add(Long.valueOf(...))  ← 最多 512 個裝箱
                       :275-281 new ProviderLine × provider 數
                       :284 new LinkedHashMap<>(failuresByReason)
                :304-306 同一份 snapshot 連呼叫 averageLatencyMillis()（:709-718，
                         512 次 for (Long …) 拆箱相加）與 percentileLatencyMillis(95)
                         （:730-742，new long[512] + 512 次拆箱 + Arrays.sort）
  → :1458-1465 逐行 graphics.text(literal(line), …)   ← 每行 new Text
```

`:1543-1545` 的 `isPauseScreen()` 回傳 `false`，所以畫面開啟時**世界照跑、60 FPS 全速重繪**，
這條路徑不會因為「遊戲暫停」而變便宜。

#### 根因（結構性）

1. **把「每幀都會變的顯示文字」直接綁在渲染幀上**，中間沒有任何快取或節流。診斷資料
   （檔案大小、統計計數）的變化頻率是**秒級**，取樣頻率卻是 60 Hz —— 兩個數量級的錯配。
2. **`String.replaceAll` 每次都 `Pattern.compile`**。`DiagnosticsLogExporter.java` 的
   5 個正規表示式全寫在方法體內（`:83`、`:86`、`:91`、`:94`、`:99`），**不是 `static final`**。
   同 repo 的 `DebugLog.java:42`、`:53-55`、`:61-63` 已經正確預編譯了 3 個 Pattern ——
   同一份紅化邏輯兩種寫法並存。
3. **檔案 metadata 每幀查一次**：`ForgeTranslationRuntime.java:315-321` 的 `fileSize`
   對兩個路徑各做 `isRegularFile` + `size`，共 **4 次 stat syscall/幀 = 240 次/秒**。
4. **`TranslationStats.snapshot()` 的複製與排序在 `synchronized (lock)` 內**（`:270`），
   而 `lock`（`:58`）同時被翻譯路徑的 `recordCacheHit`（`:118-122`）、`recordCacheMiss`
   （`:125-129`）、`recordSuccess`（`:137-148`）、`recordFailure`（`:157-171`）持有
   （由 `TranslationCoordinator.java:341`、`:362`、`:367`、`:370` 每則翻譯呼叫）。
   渲染執行緒因此每幀與 N 個 worker 搶同一把 monitor。
5. **`Snapshot` 用裝箱的 `List<Long>` 保存延遲樣本**（`:271-274`、`:640`），而底層本來就是
   `long[512]`（`:61`）—— 資料在邊界上被迫裝箱再拆箱，兩趟（`:709-718`、`:735-738`）。

#### 量化影響

估算假設：`stat` 在本機 SSD 上 1–10 µs、在 OneDrive／網路磁碟上 100–1000 µs；
`Pattern.compile` 10–40 µs（`:94` 那個含 `SECRET_NAMES` + `TOKEN_RUN` 展開後是最貴的）；
`Long.valueOf` 裝箱 15 ns（延遲值 > 127 ms 時不進快取，必然新配置）；
`Arrays.sort(long[512])` ≈ 4,600 次比較 ≈ 18 µs；`graphics.text` 與 `int translate` ≈ 1–2 µs/行。

| 項目 | 每幀 | 每秒（60 FPS） |
| --- | --- | --- |
| 4 次 stat（SSD） | 4–40 µs | 0.24–2.4 ms |
| 4 次 stat（OneDrive／網路磁碟） | 0.4–4 ms | 24–240 ms |
| 5 次 `Pattern.compile` | 50–200 µs | 3–12 ms |
| `snapshot()` 512 筆裝箱 + map 複製 | 10–15 µs | 0.6–0.9 ms |
| `averageLatencyMillis` 512 次拆箱 | 1.5 µs | 0.09 ms |
| `percentileLatencyMillis` `long[512]` + sort | 20 µs | 1.2 ms |
| 9–20 行 `literal(...)` + `translator.translate` | 15–40 µs | 0.9–2.4 ms |
| **一般頁合計** | **70–280 µs** | **4–17 ms/s** |
| **統計頁合計** | **100–320 µs** | **6–19 ms/s** |
| **`config/` 在同步磁碟時** | **0.5–4.3 ms/幀 = 3–26% 幀預算** | — |

另外：`snapshot()` 每幀配置約 512 個 `Long` + N 個 `ProviderLine` + 2 個集合
≈ **31,000 物件/秒 ≈ 0.7 MB/s**；並每幀與翻譯 worker 競爭 `TranslationStats.lock` 一次。

#### 重構方向

1. **給 `TranslationStats` 加快取層（最高收益）**：加一個 `volatile long revision`
   （每次 `recordXxx` 遞增）與 `volatile Snapshot cached`；`snapshot()` 改成
   `if (cachedRevision == revision && cached != null) return cached;` 並在鎖外重算。
   面板以 4 Hz 取樣就足夠。
2. **`Snapshot` 改存 `long[]`**：`snapshot():271-274` 直接
   `long[] samples = Arrays.copyOf(latencySamples, latencyCount);`，
   `percentileLatencyMillis` 與 `averageLatencyMillis` 收 `long[]` —— 一次裝箱都不做。
3. **把 5 個正規表示式提成 `static final Pattern`**：`DiagnosticsLogExporter.java:83`、
   `:86`、`:91`、`:94`、`:99` 改成 `private static final Pattern HTTP_URL = Pattern.compile(...)`
   等，`replaceAll` 改 `pattern.matcher(clean).replaceAll(...)`。
   （順帶把 `DebugLog.java:210-218` 一併改成同一種風格，讓兩處不再分叉。）
4. **`fileSize` 加 TTL 快取**：在 runtime 內存 `(path, size, expireAt)`，TTL 2 秒；或把檔案大小
   改成「使用者按下重新整理才更新」。
5. **面板加節流**：在 `renderDiagnostics` 內用
   `if (frameCounter % 15 != 0) 用上次的 lines;` —— 4 Hz 對一個純文字面板完全夠。
6. **合併 13 份複本**：`displayLines()` + 節流 + 快照可以抽成 `TranslationStatsPanel`
   放進 `translator-core`，13 個畫面只提供 `tr(...)` 與 `safeLines()` 兩個 seam。

#### 預估收益

- 一般頁 **4–17 ms/s → < 1 ms/s**；統計頁 **6–19 ms/s → < 1 ms/s**。
- `config/` 在 OneDrive／網路磁碟的災難情境：**3–26% 幀預算 → 接近 0**。
- 順帶消除 `TranslationStats.lock` 上每幀一次跨執行緒競爭。

#### 風險

低。純快取與預編譯，語意不變。唯一要注意：`snapshot()` 的 revision 快取必須在
`reset()`（`:225-240`）與 `load()`（`:449-501`）之後也更新 revision，否則面板會顯示舊值。
`fileSize` 的 TTL 快取要保證「模型下載完成後 2 秒內會更新」，否則使用者會看到舊大小。

---

### PERF-005 · `DebugLog` 在渲染／翻譯路徑上同步逐行做 6 次 syscall 與 8 次正則編譯；bundle 匯出把整檔讀進渲染執行緒

- **嚴重度**：結構性效能屎山（啟用時）｜ **信心**：高
- **熱點**：
  - `translator-core/src/main/java/org/universaltranslator/core/DebugLog.java:118-133` `log(...)`
  - 同檔 `:269-288` `appendLocked(...)` —— `:275 createDirectories`、`:279 Files.exists`、
    `:279 Files.size`、`:283 Files.write(CREATE+APPEND)` ＝ open/write/close
  - 同檔 `:210-218` `redact(...)` —— `:214 DiagnosticsLogExporter.sanitize`（5 次 `replaceAll`）
    + `:215`、`:216`、`:217` 三次 `replaceAll` = **8 次 Pattern.compile/值**
  - `translator-core/src/main/java/org/universaltranslator/core/DiagnosticsBundleExporter.java:79`
    `Files.readAllLines`（整份 debug.log 進記憶體）
- **受影響範圍**：所有平台；只在 `debug-log=true` 時生效（`OnlineProviderConfig.java:124`
  預設 `"false"`）。

#### 呼叫頻率 × 資料量估算

呼叫鏈（三個來源疊加）：

```
(A) 渲染探針（僅 debug 開啟時）：RenderTranslationSession.java:163-170 lookup(...)
      → :624 probeLog.log("render-path", …)   ← 每 1,024 次 lookup 一行（:28 PROBE_REPORT_INTERVAL）
      15,000 lookups/s ÷ 1024 ≈ 15 行/s（大廳）

(B) 每次翻譯片段：TranslationCoordinator.java:342 logCache(false,…)（miss）
                  同檔 :364 logFailure(…)（失敗）
                  同檔 :371 logCache(true,…)（hit）       ← 每個 segment 各一行

(C) 每個 SSE delta：OpenAiChatTranslationProvider.java:490 logStreamEvent(provider,"delta",…)
      一次 800 token 的串流回覆 = 數百行

共通路徑：
  DebugLog.java:118 log(event, fields...)
      :119 if (!enabled || file == null) return;      ← 關閉時成本為 0（設計正確）
      :122 new StringBuilder(192)
      :123 TIMESTAMP.format(LocalDateTime.now())      ← 每次配置 new String
      :127 redact(fields[i+1])
          :214 DiagnosticsLogExporter.sanitize(…)     → :82-101 五次 replaceAll = 5 次 compile
          :215 NAMED_SECRET.replaceAll(…)             → 1 次（已預編譯 Pattern，但仍配置 Matcher）
          :216 NAMED_SECRET_SPACED.replaceAll(…)      → 1 次
          :217 LONG_BASE64.replaceAll(…)              → 1 次
      :130 synchronized (lock)                        ★ process 全域鎖（:70）
          :269 appendLocked(line)
              :275 Files.createDirectories(directory)          ← syscall 群
              :279 Files.exists(target)                        ← syscall
              :279 Files.size(target)                          ← syscall
              :283 Files.write(target, …, CREATE, APPEND)      ← open + write + close
```

#### 根因（結構性）

1. **沒有任何緩衝或佇列**：每筆事件都是一次「打開檔案 → 寫 → 關閉」，而且**寫入前先做兩次
   metadata 查詢**（`Files.exists` + `Files.size`）只為了判斷是否要輪替。`:33` 的
   `MAXIMUM_BYTES` 邊界是對的（有界、有輪替），但**檢查方式**讓每行都付 6 次 syscall。
2. **紅化在寫入路徑上做，而且用 `String.replaceAll`**。注意 `DiagnosticsLogExporter.java:83`、
   `:86`、`:91`、`:94`、`:99` 的正規表示式**沒有預編譯** —— 每次呼叫都 `Pattern.compile`。
   `DebugLog` 自己那 3 個（`:42`、`:53-55`、`:61-63`）是 `static final`，所以是
   **8 次編譯 = 5 次浪費**。
3. **`synchronized (lock)` 包住整段 IO**（`:130`）。`lock` 是 process 全域的（`:70`），
   所以渲染執行緒的探針行、翻譯 worker 的 cache 行、串流執行緒的 delta 行
   **全部序列化在同一把鎖後面**，而且每個持有者都在做檔案 IO。
4. **bundle 匯出在呼叫者的執行緒上做整檔處理**：`DiagnosticsBundleExporter.java:79` 的
   `Files.readAllLines` 把最多 2 MiB（`:33`）整份載進記憶體，然後 `:80` 逐行
   `DebugLog.redact`（8 次 compile/行）。這條由
   `UniversalTranslatorScreens.java:1343-1346` 的按鈕 callback 直接呼叫，即**渲染執行緒**。

#### 量化影響

估算假設：`Files.createDirectories` 30 µs（已存在路徑仍要 stat）、`Files.exists` 5 µs、
`Files.size` 5 µs、`Files.write`（open+write+close）60 µs → `appendLocked` ≈ **105 µs/行**；
`redact` 3 次 `replaceAll` + `sanitize` 5 次 `replaceAll` 於 200 字元行 ≈ 8 × 15 µs = **120 µs**；
`TIMESTAMP.format` ≈ 1 µs。

| 來源 | 行/秒 | 成本 |
| --- | --- | --- |
| (A) 渲染探針 | ~15 | 15 × 226 µs = 3.4 ms/s |
| (B) 每 segment 2–3 行（16 請求/s × 2 段 × 2 行） | ~64 | 64 × 226 µs = 14.5 ms/s |
| (C) 串流 delta（一次回覆數百行，突發） | 突發 ~200/s | 45 ms/s 峰值 |
| **合計（debug 開啟、有在翻譯）** | **~80–280** | **18–63 ms/s**，且**鎖內** |

因為全部被 `:130` 的 `synchronized (lock)` 序列化，這個成本是**串行的**：即使有 4 個 worker，
它們也只能一個一個寫日誌。實際表現是「打開 debug 之後整個遊戲明顯變鈍、翻譯變慢」。

**bundle 匯出**：2 MiB / 約 150 bytes/行 ≈ **14,000 行**；14,000 × 8 次 compile ≈
**112,000 次 `Pattern.compile`** ≈ 1.1–4.5 秒的純正則編譯，加上 `readAllLines` 的
List + 逐行 StringBuilder + `getBytes` 峰值約 **9–11 MB**。全部在**渲染執行緒**上，單次
按鈕點擊會凍結 **1–5 秒**且沒有進度提示。

#### 重構方向

1. **單一寫入執行緒 + 有界佇列**（照抄同 repo 已有的成功範例）：
   `PersistentTranslationCache.java:105-129` 的「只有一個寫入者 + dirty 旗標」模式可以
   直接搬過來。`log(...)` 只做 `redact` + 字串組裝，然後 `queue.offer(line)`（
   `ArrayBlockingQueue(2048)`，滿了就丟），由一個 daemon 執行緒持有 `BufferedWriter`。

```java
private final BlockingQueue<String> pending = new ArrayBlockingQueue<String>(2048);
private volatile Writer writer;   // 由 writer 執行緒持有，lazy 開啟

public void log(String event, String... fields) {
    if (!enabled || file == null) return;
    StringBuilder line = new StringBuilder(192);
    line.append(TIMESTAMP.format(LocalDateTime.now())).append(' ').append(event);
    if (fields != null) {
        for (int i = 0; i + 1 < fields.length; i += 2) {
            line.append(' ').append(fields[i]).append('=').append(redact(fields[i + 1]));
        }
    }
    pending.offer(line.toString());   // 非阻塞；滿了自然丟棄
}
// writer 執行緒：take() → writer.write(line + LS)；每 256 行檢查一次 size() 決定是否輪替
```

2. **輪替檢查從「每行」改成「每 N 行或每 5 秒」**：把 `Files.exists` + `Files.size` 從
   `:279` 移到 writer 迴圈的計數器上。
3. **`DiagnosticsLogExporter` 的 5 個正規表示式提成 `static final Pattern`**（與 PERF-004
   第 3 項同一件事，一併修）。
4. **bundle 匯出丟到背景執行緒**：`exportBundle()` 改成
   `CompletableFuture.runAsync(..., SESSION_REAPER)`，按鈕先顯示「匯出中…」，
   完成時在 tick 上把結果寫進 `exportStatus`。這同時修掉「渲染執行緒上做 1–5 秒 IO」。

#### 預估收益

- debug 開啟時：**18–63 ms/s → < 1 ms/s**，且鎖競爭消失，翻譯 worker 吞吐恢復。
- bundle 匯出：**1–5 秒凍結 → 0**（背景執行），正則編譯 112,000 次 → 8 次。
- 關閉 debug（預設）時本來就是 0，所以這一項**不影響預設使用者** —— 這也是它排在第 5 位
  的原因：絕對收益大，但有觸發條件，且需要新增一個執行緒。

#### 風險

中。
- 新增一個 daemon 寫入執行緒，必須在 JVM 關閉時 flush（`Runtime.addShutdownHook`，可照抄
  `TranslationStats.java:441-446` 的寫法），否則最後幾行會丟。
- 非同步日誌會讓「日誌行出現的順序」與事件順序在跨執行緒時可能交錯 —— 對支援用途可接受，
  但要在類別 javadoc 寫明。
- 丟棄策略（佇列滿就丟）必須在日誌裡留一行 `dropped=N`，否則使用者會以為沒有事件。

---

### BUG-S-056 · `FabricLocalTextGuard` 的反射快取在失敗時永不上鎖存，導致每幀重跑整類別欄位掃描

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`platforms/fabric/shared/guard-legacy/src/main/java/org/universaltranslator/fabric/FabricLocalTextGuard.java:32-49`、`:61-75`
- **受影響範圍**：所有 Fabric 世代（`platforms/fabric/shared/guard-legacy`、`platforms/fabric/1.13/shared/common`、`legacy-1.13.2`、`platforms/fabric/26.x/shared/api/26.1-26.1.2`、`26.2-26.3` 各有一份）
- **現象**：`findChatField` 只有在 `findTextField` **成功**時才把 `searched = true` 鎖存
  （`:40-43`）。`:56` 的 `known.get(screen)` 失敗、或 `:68-70` 的 `setAccessible(true)` 拋
  `InaccessibleObjectException`／`SecurityException`（`:68-70` 直接 `return null`）時，
  `searched` 永遠是 `false`。
- **觸發條件**：`findTextField` 第一次被呼叫時回傳 `null` —— 例如 ChatScreen 的子類別把
  `TextFieldWidget` 欄位換成其他 widget 型別、或 `setAccessible` 被拒。
- **根因**：`:38-40` 的註解明說「Only latch the cache once the lookup actually succeeded,
  otherwise a transient failure would disable the local-chat guard for the session」——這個
  選擇把一次性的失敗轉成**永久性**的每幀重試。
- **影響**：路徑變成「每次 render lookup → 進 `synchronized (FabricLocalTextGuard.class)`（`:35`）
  → `getDeclaredFields()`（`:63`，配置 `Field[]` 並可能觸發類別載入／驗證，5–20 µs）
  → `setAccessible` 例外」。以 250 次 lookup/幀 × 60 FPS 計算，這是 **75–300 ms/s** 加上
  每幀 250 次全域類別鎖。
- **修法**：改用 `ClassValue<Optional<Field>>` 做**每個 class 一次**的解析，並把 `null`
  也鎖存（用哨兵值），只在 `screen.getClass()` 改變時重試：

```java
private static final ClassValue<Optional<Field>> CHAT_FIELD = new ClassValue<Optional<Field>>() {
    @Override protected Optional<Field> computeValue(Class<?> type) {
        return Optional.ofNullable(findTextField(type));   // null 也被鎖存
    }
};
// findChatField: return CHAT_FIELD.get(screen.getClass()).orElse(null);
```

---

### BUG-S-057 · `TranslationCoordinator` 用會重新正規化語系的 `shouldTranslate`，對同一段文字重複掃描並違反自身不變式

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/TranslationCoordinator.java:138`、
  `translator-core/src/main/java/org/universaltranslator/core/LanguageHeuristics.java:28-30`、`:106-112`
- **受影響範圍**：所有平台（每次送出翻譯請求）
- **現象**：`RenderTranslationSession.java:207` 已經用
  `shouldTranslateToTarget(original, chineseTarget)`（預先解析好的布林）掃過一次，
  送出後 `TranslationCoordinator.java:138` 又用 `shouldTranslate(text, targetLanguage)`
  對**同一段文字**再掃一次。
- **根因**：`LanguageHeuristics.java:28-30` 的 `shouldTranslate` 每次呼叫都會走
  `isChineseTarget(targetLanguage)`，而 `:110` 是
  `language.toLowerCase().replace('_', '-')` —— **每次 2 次 String 配置**。
  `LanguageHeuristics.java:32-40` 的 javadoc 自己寫著「the render path calls this for every
  visible string on every frame, so it must not re-normalize the language identifier per call;
  the identifier is fixed for the lifetime of a session, which makes the boolean a per-session
  constant」—— `TranslationCoordinator:138` 正好違反了這條不變式。
- **影響**：每次請求多 2 次字串配置 + 一次 `shouldTranslateToTarget` 的全程式掃描
  （`Character.isLetter` 迴圈 + 4 次 `containsSelfName` + `containsInternalArtifact`）。
  在渲染執行緒上，於 `RenderTranslationSession.java:239` 的 `SubmissionWindow` 限制下最多
  16 次/秒，量級不大；但**每次請求的 `requestKey`（`:149-151`）也在此執行緒上組**，兩者
  疊加違反 `:145` 自己的註解「Keep all regex construction, cache I/O and provider work off
  the render thread」。
- **修法**：讓 `TranslationCoordinator` 接受已解析的 `chineseTarget`（或直接接受呼叫端
  「已通過啟發式」的事實，跳過 `:138` 的重複檢查 —— 因為
  `RenderTranslationSession:207` 與 `translateInteractive` 的呼叫端都已經檢查過）：

```java
// TranslationCoordinator: constructor 預先解析一次
this.chineseTarget = LanguageHeuristics.isChineseTarget(targetLanguage);
// :138
if (!LanguageHeuristics.shouldTranslateToTarget(text, chineseTarget)) { ... }
```

---

### BUG-S-058 · `lookupLines` 每幀重新 join 全部 tooltip 行，並用 `split("\\n")` 每次都編譯 Pattern

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/RenderTranslationSession.java:405-444`
  （重點 `:421-432` join、`:437` split）
- **受影響範圍**：所有平台的物品 tooltip 路徑
- **呼叫鏈**：`platforms/fabric/shared/screen-mixin/.../ScreenMixin.java:17-25`（`@Inject` at
  RETURN on `getTooltipFromItem`）→ `RenderedTextBridge.java:157 translateItemTooltip` →
  `:164 translateTooltip` → `:172 FabricTranslationRuntime.translateLinesForRender` →
  `FabricTranslationRuntimeBase.java:359-368` → `RenderTranslationSession.java:405 lookupLines`。
  `Screen.getTooltipFromItem` 由 `HandledScreen.render` 每幀呼叫，所以這條是**每幀**路徑。
- **現象**：
  1. `:421-432` 每幀用 `StringBuilder` 把所有行重新 join 成一個**全新的 String**
     （20 行 × 50 字元 = 1,000 字元）。因為是新的實例，`RenderKey.hashCode()`
     （`:750` 的 `31 * text.hashCode() + kind.hashCode()`）會對它做一次 O(n) 的完整
     雜湊計算，`equals`（`:745`）也是完整的字元比較。
  2. `:437` `translatedText.split("\\n", -1)`：`"\\n"` 是**2 字元**的 regex（反斜線 + n），
     不滿足 JDK `String.split` 的兩條快路徑（單字元非元字元／兩字元且第二個不是 ASCII
     字母或數字），因此**每次呼叫都執行 `Pattern.compile("\\n")`**。
- **影響**：`Pattern.compile` 5–20 µs/次 × 60 FPS = **0.3–1.2 ms/s**（hover tooltip 期間），
  加上每幀 1,000 字元的 join 與雜湊 ≈ 0.05 ms/s 與約 2 KB/幀 垃圾。量級不大，但這是
  **每次 hover 都在編譯正則**，屬於典型可完全避免的成本。
- **修法**：把 join 結果按 `List` 的內容做 1 層快取（或用 `String.join` + 固定
  `"\n".equals` 判斷），並把 split 改成手寫掃描：

```java
// 取代 :437
List<String> translatedLines = new ArrayList<String>(originals.size());
int from = 0;
for (int i = 0; i <= translatedText.length(); i++) {
    if (i == translatedText.length() || translatedText.charAt(i) == '\n') {
        translatedLines.add(translatedText.substring(from, i));
        from = i + 1;
    }
}
// 注意：.split("\\n", -1) 會保留尾端空字串，上面的寫法在 i == length 時補一個，語意等價
```

並在 `lookupLines` 開頭加一個「上次的 `List` 實例 → 上次的 join 結果」單槽快取
（`List` identity 相同就重用），把 join 與雜湊從熱路徑移掉。

---

### BUG-S-059 · `TranslationTuning.workers()` 在生產碼是死碼，與 `translation-workers` 各自為政

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/provider/TranslationTuning.java:51-53`、
  `translator-core/src/main/java/org/universaltranslator/core/provider/OnlineProviderConfig.java:249-253`、`:276-278`
- **受影響範圍**：所有平台
- **現象**：`TranslationTuning` 有三個欄位 `workers` / `batchSize` / `batchWindowMillis`
  （`:39-41`），其中 `batchSize()`（`:56-58`）與 `batchWindowMillis()`（`:61-62`）被 provider
  使用，但 `workers()`（`:51`）**在全 repo 只被測試呼叫**
  （`ProviderSelfTest.java:1367`、`:1370`）。
- **根因**：worker 數走的是另一條路徑 —— `OnlineProviderConfig.java:249-253`
  `translationWorkers()` → `FabricTranslationRuntimeBase.java:141-144` →
  `TranslationCoordinator.java:48-55` 的 `ThreadPoolExecutor`。`OnlineProviderConfig.java:276-278`
  的 `tuning()` 又把 `translationWorkers()` 塞進 `TranslationTuning`，於是同一個設定值有兩份
  副本，而 provider 拿到的 `workers` 從頭到尾沒被讀。
- **影響**：設定驗證（`:325-327` 的 `settingLine("Translation workers", …)`）會印出一個
  「有效值」，但該值對 provider 的批次併發沒有任何作用（PERF-001 的併發上限由
  `batchServing` 布林決定，與此無關）。使用者在 UI 上調高 worker 數看不到任何吞吐變化。
- **修法**：PERF-001 一起修 —— 讓 `batchServing` 改成 `inFlightBatches` 並以
  `tuning.workers()` 為上限，這一欄就活了。若短期不修 PERF-001，至少把
  `TranslationTuning.workers()` 標 `@Deprecated` 並在 `toString()`（`:101-102`）裡移除，
  避免誤導。

---

### BUG-S-060 · `completeLookup` 是 `synchronized` 卻在鎖內同步寫 stderr

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/RenderTranslationSession.java:290-350`
  （`:290` `private synchronized boolean completeLookup`、`:322 reportFailureIfChanged`、
  `:557-565` → `:564 System.err.println`）
- **受影響範圍**：所有平台
- **現象**：`completeLookup` 持有 `RenderTranslationSession` 的實例 monitor，而
  `:322` → `:564` 的 `System.err.println(...)` 是一次**同步的 PrintStream 寫入**。同一個
  monitor 被 `:636 clearRenderedTranslations()`、`:718 close()`、`:532-535 setBlockedKeywords()`
  使用。
- **根因**：`reportFailureIfChanged` 只在狀態**變化**時寫（`:558-560` 的 `equals` 早退），
  所以頻率不高；但一次 stderr 寫入可能因 `latest.log` 的 flush 或主控台 pipe 滿而阻塞
  數毫秒到數十毫秒，期間整個 session 的 monitor 被佔住。
- **影響**：provider 故障（例如 API key 失效）時，第一次失敗會寫一行；之後同狀態不再寫，
  所以不是每則都寫。真正的風險是「provider 在兩種失敗狀態間閃爍」（例如 429 與 timeout
  交替）時每次完成都寫一行，而 `initialize()`（設定變更、F8 切換）會排在該 monitor 後
  一起卡住 —— 註解 `:120-123` 已明確記錄「避免在 client tick 執行緒上阻塞」是這個類別的設計目標。
- **修法**：把 stderr 寫入移出 monitor。最乾淨的做法是 `completeLookup` 改回非
  `synchronized`，只把真正需要互斥的區段（`:300` 的 `pending.remove` 已是 CHM 原子操作，
  `:328-344` 的 map 寫入也是 CHM）留在裡面；`reportFailureIfChanged` 改成
  `AtomicReference<String>` 的 `compareAndSet`，並在鎖外印出：

```java
private final AtomicReference<String> lastReported = new AtomicReference<String>("");
private void reportFailureIfChanged(String status) {
    String previous = lastReported.getAndSet(status);
    if (status.equals(previous)) return;
    System.err.println("[MC Auto Translation Tool] provider=" + providerCategory + " " + status);
}
```

---

## 已排查但判定不構成屎山的候選（7 條）

| # | 候選 | 判定與理由 |
| --- | --- | --- |
| 1 | `Pattern.compile` 在熱路徑反覆執行 | **不成立（已正確處理）**。全域 9 處 `compile` 中 8 處是 `static final`（`DebugLog.java:42`、`:53`、`:61`、`ProtectedText.java:83`、`:85`、`:86`、`CustomHttpJsonTranslationProvider.java:18`、`TranslationOutputValidator.java:11`）；第 9 處 `ProtectedText.java:175` 只在一個 4 槽的內容鍵快取內（`:189-206`），踢掉才編譯。`TranslationPrompt.java` 全檔無正則。`platforms/` 零命中。真正的例外只有 `DiagnosticsLogExporter`（已列入 PERF-004/005）與 `RenderTranslationSession.java:437`（BUG-S-058）。 |
| 2 | 每次請求重建 `HttpClient` / `SSLContext` / `ExecutorService` | **不成立（物件不存在）**。`HttpJsonClient.java:67`、`:197` 直接用 `HttpURLConnection`，全檔沒有 `java.net.http.HttpClient`、`SSLContext` 或 `ExecutorService`；keep-alive 取捨在 `:68-70`、`:116-127` 是正確的（讀完並關閉才允許池化，其他路徑 `disconnect()`），timeout 由建構子注入（`:25-31`）。沒有 per-request 執行緒或 selector 洩漏。 |
| 3 | `JsonStrings` 是 O(n²) 或無深度限制 | **不成立（線性且有限）**。`:250-420` 是單游標線性掃描，所有字串走 `StringBuilder`（`:349-381`），沒有逐字元 `String +=`；`MAX_DEPTH = 64`（`:251`）在 `:269-271` 強制。`readStringField`（`:138-167`）是 O(n·m) 而非 O(n²)。可議處僅「同一 body 被完整 parse 多次」（provider 取值 + `TranslationStats.recordUsage` + `parseJsonBatch`），2–4 KB / 10–50 µs，量級不值得動。 |
| 4 | `PersistentTranslationCache` 每次 `put` 整檔重寫 | **不成立（已合流）**。`:117-120` 合批（64 筆或 5 秒），`:105-110` 在 flush 進行中只設 `dirty`；IO 在 monitor 之外（`:128` 離開 `synchronized` 才進 `:129` → `persistBestEffort`，後者用獨立的 `diskLock:259`）。殘留小成本：`:288-290` 的 `snapshot()` 在 monitor 內複製整個 map（10,000 筆 ≈ 0.5 ms 排隊）＋ `:270` 的 `putAll` 二次複製，只在 flush 邊界發生。`:73-75` 的註解已明確記錄刻意選 insertion-order 以避免 `get()` 變成結構修改而序列化所有 worker。 |
| 5 | 設定畫面每幀重建 widget 清單／重算版面 | **不成立（量級小兩三個數量級）**。widget 只在 `init()` 建立一次（`UniversalTranslatorScreens.java:203`、`:1326`、`:1587`）；`updateTabVisibility()`（`:539-602`）只從點擊 callback 呼叫（`:219`、`:224`、`:229`、`:234`、`:239`、`:783`）。`layout()`（`:1250-1255`）每幀被呼叫約 8 次、每次 2 個物件 ≈ 16 物件/幀 ≈ 40 KB/s，在純量逃逸分析範圍內，比 PERF-004 的單一項小兩個數量級。`SettingsSelectionList.values()` 只 clone 20/10 個字串。 |
| 6 | `TranslationStats` 累積結構無限成長 / 每則翻譯都重建 snapshot | **部分成立但已分離**。三個容器都有固定上界：`latencySamples` 是 `long[512]` 環形（`:39`、`:61`、`:413-419`）；`failuresByReason` 的 key 只來自 `reasonOf()` 的 8 個常數（`:47-54`、`:374-401`）；`providers` 的 key 是 provider 實例的 `id()`（建構時固定）。翻譯路徑只做 `long++`，`snapshot()` **只由 UI 與匯出呼叫** —— 所以「每則翻譯重建 snapshot」不成立，但「每幀重建 snapshot」成立，已列為 PERF-004。殘留小瑕疵：`MAXIMUM_STORED_PROVIDERS`（`:43`）只約束 `load()`（`:473-474`），不約束執行期 `counters()`（`:403-411`），但只隨使用者改設定線性成長，幾十筆量級。 |
| 7 | 分類用的 Context Mixin（`ChatHudContextMixin` / `InGameHudContextMixin` / `PlayerListHudContextMixin` / `BossBarHudContextMixin`）在熱路徑 | **不成立（設計正確）**。全部注入在 render 的 `HEAD`/`RETURN`，是**每幀一次**的 `push`/`pop`，不是每字串一次（`platforms/fabric/shared/mixin-1.16-1.19/.../ChatHudContextMixin.java:14-24`、`InGameHudContextMixin.java:16-30`、`PlayerListHudContextMixin.java:16-32`）。`TranslationRenderContext.pop():27-35` 在堆疊空時 `KINDS.remove()`，每幀約 1–3 次，可忽略。`blockedKeywords.matches`（`RenderTranslationSession.java:201`）也在兩道快取（`:190`、`:194`）之後，只有從未見過的字串會走到，且 `TranslationBlocklist` 無正則、關鍵字上限 100。`LanguageHeuristics.shouldTranslateToTarget`（`:41-76`）只在快取 miss 時跑、全程零配置（`containsSelfName:83-90` 用 `contains`、`isHanIdeograph:99-103` 用碼位比較），是本鏈做得最對的一段，可作為其他重構的範本。 |

---

## 附註：本次審計的覆蓋範圍

逐條追過的熱路徑（不含已排除的 `build/`、`.gradle/`、`node_modules`、`dist/`、`__pycache__`、`_re/`）：

- **每幀／每 tick 級**：`RenderTranslationSession`(753 行)、`TranslationStyleRuns`(154)、
  `TranslationTextStyling`(81)、`TranslationTextColor`(52)、`LanguageHeuristics`(113)、
  `MinecraftContentScope`(15)、`RecentUserText`(112)、`TranslationRenderContext`(61)、
  `RenderedTextBridge`（rendered-common 293 行、rendered-legacy、26.x、1.14-1.15、1.16）、
  `LegacyRenderedTextBridge`(77)、`FontRendererTransformer`(372)、`FabricTranslationRuntimeBase`(488)、
  `FabricLocalTextGuard`(87)、`TranslationTextRendererMixin` / `DrawContextMixin` / 各 Context Mixin
- **每則訊息／每次翻譯**：`TranslationCoordinator`(458)、`JsonStrings`(421)、
  `HttpJsonClient`(339)、`TranslationDiagnosticsSnapshot`(223)、`TranslationStats`(757)、
  `OpenAiChatTranslationProvider`(1156)、`OnlineProviderConfig`(594)、`TranslationTuning`、
  `DiagnosticsLogExporter`(113)
- **每次存檔／週期性**：`PersistentTranslationCache`(296)、`DebugLog`(289)、
  `DiagnosticsBundleExporter`(149)、`TranslationActivity`、`TranslationStore`、`TranslationCache`
- **UI 重繪**：`UniversalTranslatorScreens`(1818 行，forge 1.18.2-1.21.11)、
  `UniversalTranslatorScreens`(forge modern 1.16.5)、`UniversalTranslatorDiagnosticsScreen`（fabric 8 份）、
  `LegacyDiagnosticsScreen`、`SettingsScreenLayout`、`SettingsSelectionList`、`SettingsUiAnimation`

**未執行**：任何編譯、建置、打包、測試、profiling、benchmark，以及任何專案腳本
（`scripts/*.ps1`、`scripts/*.py`）。所有結論皆可由上列的 `檔案:行號` 靜態複核。
