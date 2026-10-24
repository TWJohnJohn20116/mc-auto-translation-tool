# 嚴重 BUG 修復獨立 Review（f9-review）

- 分支：`fix/critical-bugs-1.4`（改動全在工作區，未 commit）
- Review 範圍：`git diff --stat` 的 16 個主程式檔（`src/test/**` 未審，由 f8 負責）
- 方法：逐檔 `git diff <path>` 讀完整 patch + 讀原檔上下文 + 手動推演呼叫鏈
- **未執行任何編譯／建置／測試**（依 AGENTS.md，唯一建置途徑是 GitHub Actions）
- 本 review 未修改任何檔案，唯一寫入是本報告

## 判定總表

| BUG | 判定 | 一句理由 |
| --- | --- | --- |
| BUG-C-001 列舉 fallback + 四份 config 防護 | **PASS** | 兩個 `fromConfig` 未知值皆回預設且不拋例外，四份 config 的 7 個列舉解析全部改成逐欄位 `parseField` 包覆，`IOException` 路徑完全沒被動到。 |
| BUG-C-002 cache.put 移到顯示校驗之後 | **存疑** | 新增的寫入確實全部走 `PendingCommit` 且 rollback 正確，但 `requireDisplaySafe` 兩參數版已被改成**完全不做 placeholder 檢查**，coordinator 自己 javadoc 描述的觸發情境已不可能發生，且既有 cache 命中值不進 journal，舊壞值仍會無限循環。 |
| BUG-C-003 快取讀取失敗保護／tmp 唯一化／跨實例鎖 | **PASS** | `remove` 是抽象方法且兩個實作都實作；失敗旗標只在失敗時設、缺檔仍算首跑；early return 確實在取 path lock 之前；tmp 名含 UUID 且 `finally` 必定刪除；`this` 與 path lock 從不同時持有，無反向鎖序。 |
| BUG-C-004 批次 leader 中斷自旋 | **PASS** | 中斷時先把 leader 移出 `pendingBatch` 並設 `settled = true`，才重設中斷旗標；回到迴圈頂端第一件事就是 `entry.settled` → break，已無法被重選為 leader；所有出口逐條驗證後 `settled` 終會為 `true`。 |
| BUG-C-005 串流完整度／keep-alive／停止原因／離線門檻 | **存疑** | OpenAI 的 `[DONE]` 判定與 `isRelayError` 都正確，Gemini/Claude 的 `finishReason`/`stop_reason` 兩條路徑也都檢查了；但 Gemini/Claude 在**串流被中途斷線、完全沒有停止原因**時仍會把半截翻譯當成完成品（＝本 bug 的原始症狀），且沒有 `[DONE]` 的 relay 從此每行都要打兩次請求。 |
| BUG-C-006 進階設定被 toProperties 覆蓋 | **PASS** | 手動推演 `withSettings → withPromptSettings → withTranslationTuning → save()` 全鏈，新 prompt 與新 tuning 都保留到檔案；四份 config 結構完全同構；兩參數版 `preservePromptSettings` 行為未變。 |

**必須修 3 條、建議修 6 條。**

---

## 必須修

> **處理狀態（2026-10，分支 `fix/critical-bugs-1.4`）：M-1、M-2、M-3 全部已修，尚未經 CI 驗證。**
>
> - **M-1**：Gemini 加 `sawEnd`（等到 `finishReason` 才算完整）、Claude 以 `message_stop` 為終止證明；兩者沒看到結束標記就判定「目前累積只是前綴」，退回純文字路徑重取一次完整答案，不再把半截句子寫進快取。
> - **M-2**：走的路是「留下雙參數重載、把因果鏈寫清楚」——它的 javadoc 現在明確說明這條路徑從未把 token 送給 provider，掃描只會誤殺玩家真的叫 `__UT_0__` 的合法內容，並指向三參數重載給真正會送 token 的呼叫端。`CoreSelfTest` 同時斷言「還原值被接受」與「真的送出去的 token 被拒絕」。
> - **M-3**：`CoreSelfTest` 的匿名 `TranslationStore` 補上 `remove(String)`。

### M-1　Gemini／Claude 串流斷線仍會被當成完成品（C-005 未修完）　✅ 已修
- `translator-core/src/main/java/org/universaltranslator/core/provider/GeminiTranslationProvider.java:179`
- `translator-core/src/main/java/org/universaltranslator/core/provider/ClaudeTranslationProvider.java:176`

`requireCompleteAnswer(stopReason[0] / finishReason[0], budget)` 在參數為 `null` 時直接 `return`（視為「端點沒報告 ＝ 正常」）。可是「連線在收到 `finishReason` 之前就斷了」與「端點不支援這個欄位」在程式裡無法區分，兩者都得到 `null`：

- Gemini：`finishReason` 只出現在**最後一個** chunk。連線在第 3 個 chunk 被 relay／行動網路切斷 → `finishReason[0] == null` → 直接放行 → `requireValid(text, accumulated)`（半截句子長度合法）→ 回傳半截翻譯 → coordinator 寫入持久化快取 → 之後永遠重播。
- Claude：`stop_reason` 只出現在 `message_delta`。`ServerSentEvents.read` 在 EOF 時正常回傳（它不拋例外），所以沒收到 `message_stop` 也會一路走到第 176 行；`stopReason[0] == null` → 同樣放行。

這正是 BUG-C-005 描述的原始症狀（「串流中途死亡的前綴長得跟完整翻譯一樣」），只是換了 provider。OpenAI 那條已用 `[DONE]` 修好，這兩條沒有等價的終止判定。

**改法**：把「是否看到終止標記」也記成 `boolean[] sawEnd`：
- Claude：在 `:143` 命中 `message_stop` 時先 `sawEnd[0] = true;` 再 `return false;`，然後 `:176` 改成 `requireStreamEnded(sawEnd[0], stopReason[0], budget)`——`sawEnd == false` 一律當截斷，走既有的「丟 `IllegalStateException` → coordinator 記 failure → 下輪重試」路徑（**不要** fallback 到 plain path，否則又變 2 倍請求）。
- Gemini：Gemini 串流的正常結束沒有專屬 sentinel（`streamGenerateContent` 就是陣列結束），所以改用「最後一個帶 `finishReason` 的 chunk 必須是 `STOP`」為準；仍收不到任何 `finishReason` 時，至少要求 `accumulated` 與 plain path 的結果一致，或直接 `throw new IllegalStateException("Gemini stream ended without finishReason")` 讓上層重試一次。二者選一，但**不能像現在這樣靜默放行**。

### M-2　`requireDisplaySafe` 兩參數版變成「完全不檢查 placeholder」，與 coordinator 的註解互相矛盾（C-002 的因果鏈斷了）　✅ 已修
- `translator-core/src/main/java/org/universaltranslator/core/TranslationOutputValidator.java:90-93`
- `translator-core/src/main/java/org/universaltranslator/core/TranslationCoordinator.java:468-474`

`:93` 讓兩參數版委派 `Collections.<String>emptySet()`，於是 `:120-128` 的迴圈永遠不執行——**placeholder 洩漏檢查在整條生產路徑上已經不存在**。全 repo 唯一呼叫點就是 `TranslationCoordinator.java:191`（兩參數版），三參數 overload 目前**沒有任何呼叫端**，是死碼。

而 `PendingCommit` 的 javadoc（`:468-474`）仍然寫著「a player is allowed to be named `__UT_0__` … 只有 `requireDisplaySafe` 會擋下，journalling 讓它自我撤銷」。這段描述的行為**已經不會發生**：`__UT_0__` 現在會被原樣顯示。兩個改動各自合理，合起來卻讓「為什麼需要 rollback」的說法全部失效。

**改法（二選一，不要留現狀）**：
1. 真的把 token 集合接起來：`ProtectedText` 提供本次送出的 token 集合（segment 路徑其實是空集合，但 batch／未來路徑不是），`TranslationCoordinator.java:191` 改呼叫三參數版並傳入它。這樣 rollback 的觸發條件與 javadoc 一致。
2. 承認 token 檢查對 segment 路徑無意義，把 `TranslationCoordinator.java:468-474` 的 `__UT_0__` 段落刪掉，改成實際仍會觸發的三個條件（`:113` 的長度上限、`:117` 的多行、`:129` 的 instruction artifact），並在 `TranslationOutputValidator.java:83-89` 註明「本路徑不做 token 檢查，保留三參數版給會送 token 的呼叫端」。

否則下一個維護者會照著一段不存在的機制去 debug。

### M-3　測試原始碼會編不過（`TranslationStore` 新增抽象方法）　✅ 已修
- `translator-core/src/test/java/org/universaltranslator/core/CoreSelfTest.java:145`（`TranslationStore store = new TranslationStore() { … }`）
- `translator-core/src/test/java/org/universaltranslator/core/CoreSelfTest.java:1194 / 1199 / 1205`（`requireDisplaySafe` 相關斷言）

`TranslationStore.java:18` 新增的是**抽象**方法（判定正確，見「已確認正確」），但 `CoreSelfTest` 有一個匿名實作沒有 `remove`，`check`／`test` 的 testCompile 會直接紅。同時 `:1205` 的 `assertThrows(() -> requireDisplaySafe(...))` 依 M-2 的行為變更很可能已經不再拋例外。

**這是 f8 的職責範圍**（本 review 依指示未審 `src/test/**`），列出純粹是提醒：合併前務必確認 f8 已補上匿名類別的 `remove`，並重新確認這三處 `requireDisplaySafe` 斷言的方向。

---

## 建議修

### S-1　沒有 `data: [DONE]` 的 relay，從此每行都要打兩次付費請求（C-005 副作用）
`translator-core/src/main/java/org/universaltranslator/core/provider/OpenAiChatTranslationProvider.java:480`

`if (attempt.complete && accumulated.length() > 0)` 讓「沒收到 `[DONE]`」等價於「失敗」，於是 `:417-423` 落到 `super.translateStreaming` → 再打一次**完整且付費**的 plain 請求。改動前這類端點是一次請求就拿到答案。因為 `streamingUnsupported` 沒被設，這個 2 倍成本**每一行都會重複**（overlay 聊天是每則訊息一次），而且早期的 partial preview 也一併失效。

`ServerSentEvents.read` 已確認會把 `[DONE]` 原樣交給 handler（`ServerSentEvents.java:82`），所以標準 relay 不受影響；受影響的是省略 `[DONE]` 的代理。

**建議**：額外接受「最後一個 chunk 帶 `choices[0].finish_reason`」為完成訊號（Azure 與部分 relay 就是這種形式），在 `readEvent` 裡加 `if (JsonStrings.readStringPath(payload, "choices[0].finish_reason") != null) attempt.complete = true;`（注意要在 `delta.content` 判斷**之後**，不要影響累積）。若不做，至少要在 `attempt.content == null` 的 fallback 分支記一次 `DebugLog.global().logStreamEvent(provider, "incomplete", 0)`，否則這個 2 倍成本在全靜默的狀態下發生。

### S-2　既有快取裡的壞值不會被 rollback 清掉，仍會無限重試
`translator-core/src/main/java/org/universaltranslator/core/TranslationCoordinator.java:399-402`

`PendingCommit.recordKey` 只在 `:397`（provider 剛回傳）時呼叫。cache **命中**分支（`:399-402`）不記錄任何 key，所以：
- 舊版本寫進 `cache.properties` 的壞值（本次修復之前產生的）→ 每次都命中 → `requireValid(core, …)` 通過 → 整體 `requireDisplaySafe` 在 `:191` 失敗 → `rollback` 無 key 可刪 → **下一輪完全一樣**。
- 同一請求內「A 段命中舊值、B 段新翻譯」時，rollback 只刪 B，A 永遠留著。

`:195-197` 的註解寫「the next request for the same line has to reach the provider again」，對命中值並不成立。

**建議**：命中時也 `pendingCommit.recordKey(cacheKey)`（`remove` 對不存在的 key 是 no-op，成本可忽略），或至少在被 reject 時把本請求所有**讀到過**的 key 一併移除。這才是把「一個 key 永久壞掉一行」徹底關掉。

### S-3　`withEnabled` / `withHomeSettings` 把 `ownsPromptKeys` 硬寫成 `false`
`platforms/forge/shared/common/.../ForgeConfig.java:291`、`:303`；其餘三份同位置（`FabricConfig` modern/legacy `:291`、`:303`，`LegacyConfig` `:296`、`:308` 附近）

目前所有呼叫端都是在「剛從磁碟載入」的實例上呼叫（`previous` / `current`），所以 `false` 與 `this.ownsPromptKeys` 等價、行為正確。但這是靠**呼叫順序巧合**成立的：一旦有人寫 `config.withPromptSettings(q, p).withEnabled(true).save()`，prompt 又會被 `toProperties()` 從磁碟還原，C-006 就原地復活，而且完全沒有編譯或測試會提醒。

**建議**：這兩個方法改成傳 `this.ownsPromptKeys`。對現有呼叫端是 no-op，對未來的鏈式呼叫才是安全的。

### S-4　`withTranslationTuning` 宣稱擁有它沒有寫的 key
`ForgeConfig.java:354`、`FabricConfig.java:354`（兩份）、`LegacyConfig.java:358`

`applyTranslationTuning` 只寫 worker／batch，不動 `translation-quality` / `custom-system-prompt` / `debug-log`，但它回傳 `true`，等於整個 prompt 三鍵都放棄「從磁碟重讀」的保護。目前唯一的呼叫端（五個設定畫面）都接在 `withPromptSettings` 之後，所以正確；一旦有人單獨用 `current.withTranslationTuning(w, b).save()`，就會把診斷面板剛改的 prompt 蓋回舊值。

**建議**：`preservePromptSettings` 的 `ownedKeys` 改成「本次真正寫入的 key」——`withPromptSettings` 傳 `translation-quality`+`custom-system-prompt`，`withDebugLog` 傳 `debug-log`，`withTranslationTuning` 傳空集合但**繼承**前一個實例的擁有權（即欄位改成 `Set<String>` 累加而非 `boolean`）。這是 C-006 的正確終局，現在的 `boolean` 只是夠用。

### S-5　`requireContent` 會把純 emoji／純符號的回答判成失敗
`translator-core/src/main/java/org/universaltranslator/core/TranslationOutputValidator.java:152-156`、`:170-181`

`countContentCharacters` 只數 `Character.isLetterOrDigit`。補充平面字元（emoji）是 symbol，回傳 `false`，所以「`lol` → `😂😂😂`」這種回答在 `requireValid` 會丟 `IllegalArgumentException`，被 `TranslationCoordinator.java:388-393` 當成 provider 失敗計數並重試。改動前這種回答會被接受並快取。

聊天場景的純 emoji 回覆不算罕見。**建議**：`countContentCharacters` 除了 letter/digit 也接受 `Character.isSurrogate` 開頭的 code point，或只在「來源本身含 letter/digit」時才要求輸出含 letter/digit。

### S-6　`requireCompleteAnswer` 排在空內容判斷之前，診斷訊息變差
`GeminiTranslationProvider.java:115`、`ClaudeTranslationProvider.java:103`

被 safety／recitation 擋掉的回應沒有 `finishReason`，走不到這裡（安全）；但 `finishReason: "SAFETY"` 且無 content 的回應，現在吐的是「Gemini stopped early (finishReason: SAFETY)」而非原本資訊量更高的 `ProviderJson.describeMissingContent(...)`（含 body 摘要）。**建議**：把 `requireCompleteAnswer` 移到 `describeMissingContent` 之後。

### S-7　重複常數 128 與 64
- `translator-core/.../provider/OpenAiChatTranslationProvider.java:29`（`OFFLINE_PROMPT_OVERHEAD_TOKENS = 128`）、`:36`（`MINIMUM_OFFLINE_COMPLETION_TOKENS = 64`）
- `translator-core/.../provider/LlamaCppOfflineProvider.java:61`（`PROMPT_OVERHEAD_TOKENS = 128`）、`:66`（`MINIMUM_ANSWER_TOKENS = 64`）

數值一致、公式一致（都經 `OpenAiChatTranslationProvider.estimatePromptTokens`，皆為 `LoopbackContext.TOKENS − 128 − promptTokens`），**目前沒有不一致風險**，因為 `LlamaCppOfflineProvider:750` 直接呼叫對方 package-private 的 `estimatePromptTokens`。但兩個 128 和兩個 64 是四份字面量，改一邊忘另一邊就會出現「先擋後放」或「先放後擋」的分歧。

依專案慣例（`translator-core` 已有 `provider/LoopbackContext`、`UiLimits` 這類共用常數類），**建議**把 128 與 64 搬進 `LoopbackContext`（例如 `PROMPT_OVERHEAD_TOKENS`、`MINIMUM_COMPLETION_TOKENS`），兩個 provider 一起引用。（依指示未動手。）

---

## 已確認正確（逐項驗證過）

### BUG-C-001
- `TranslationTextColor.fromConfig`（`:54-72`）：`null`／空白 → `AQUA`，與修改前一致；未知值 → 記 log 後回 `AQUA`，不再拋 `IllegalArgumentException`。
- `TranslationDisplayMode.fromConfig`：`null`／空白 → `TRANSLATED_ONLY` 未變；`translated-only` / `translated_only` / `bilingual` / `original-and-translated` / `original_and_translated` 五種寫法都仍被接受；未知值 → `TRANSLATED_ONLY`。
- 四份 config（ForgeConfig、FabricConfig modern、FabricConfig legacy、LegacyConfig）各有 **7 個**列舉解析，全部改走 `parseField(...)`：`hud-indicator-corner`、`hud-indicator-color`、`hud-indicator-content`、`hud-indicator-visibility`、`display-mode`、`translated-text-color`、`offline-model`。逐一核對 fallback 常數存在且正確：`HudIndicatorCorner.TOP_LEFT`、`HudIndicatorColor.GREEN`、`HudIndicatorContent.DOT`、`HudIndicatorVisibility.ALWAYS`、`TranslationDisplayMode.TRANSLATED_ONLY`、`TranslationTextColor.ORIGINAL`、`OfflineModel.LITE`。
- **只包單一欄位**：`parseField` 在每個賦值右側各自 try/catch，`load()` / constructor 本身沒有被整個包住；`IOException` 的 catch 路徑（`load()` 讀檔）完全沒被改動，也沒有被吞。
- `DebugLog.global().log(String, String...)` 簽名存在（`DebugLog.java:118`）；兩個列舉與 `parseField` 的呼叫參數個數都**是偶數**（各 6 個 key/value），且 `redact(null)` 有 null 防護（`DebugLog.java:211`），所以 `"value", raw` 帶 `null` 不會 NPE。
- `configVersion()`（`ForgeConfig.java:546-552`）本來就有 `catch (NumberFormatException)`；`parseBoundedInt` 的 `null` 判斷已移進 try 且改成 `catch (RuntimeException)`，`null`／非數字都不會逃出 constructor。
- `TranslationQuality.fromConfig` 本身就會 fallback，且是在 getter 惰性解析，不在 constructor。

### BUG-C-002
- `cache.put` 全檔只剩 **1 處**：`TranslationCoordinator.java:398`，且**先** `recordKey`（`:397`）再 `put`，所以「store 接受了但持久化失敗」也能被移除。
- `PendingCommit` 在 `:188` 於 executor 的 lambda 內建立 → **每次請求獨立**，不是 coordinator 欄位（`PendingCommit` 是 `private static final class`，`:481`）。
- `commit()` 只有一個呼叫點（`:202`），且在 `requireDisplaySafe` 回傳成功之後；`rollback()` 只在 catch（`:199`）——`commit` 不可能跑兩次，也不會重複計數。
- `rollback()` 對空清單是 no-op（增強 for 空集合不執行），不會拋例外；`store.remove` 對不存在的 key 也是 no-op。
- `stats.recordSuccess` 確實移到成功之後：provider 成功時只 `recordSuccess(providerId, elapsedMillis)` 進 journal（`:394`），實際計數在 `commit()`（`:496-501`）。`recordCacheHit` / `recordCacheMiss` / `recordFailure` 留在原本位置，語意未變。
- 驗證器：三參數版（`:107-133`）只對 `liveTokens` 逐個 `output.contains(token)`；兩參數版（`:90-93`）委派 `emptySet()`；`liveTokens == null` 有 `if (liveTokens != null)` 保護（`:120`），不會 NPE。
- 失敗時對**本次請求所有**寫入 key 都呼叫 `remove`（`:504-508` 迴圈），且 `writtenKeys` 是 `PendingCommit` 的實例欄位。
- `created.complete(...)` 在 `commit()` 之後才呼叫，因此 caller 看到的成功一定對應已計數的成功。
- `restored` 的 definite assignment 成立（catch 分支 `throw rejected`），不影響編譯。

### BUG-C-003
- `TranslationStore.remove` 在 `TranslationStore.java:18` 是**抽象**方法（`flush()` 才是 `default`），兩個實作都真的實作：`TranslationCache.java:33-35`（`synchronized`）、`PersistentTranslationCache.java:160-193`。
- 載入失敗旗標 `loadFailed`（`:68`）：只在 `markLoadFailed`（`:316-319`）設；`load()` 成功不會設；`Files.exists(file) == false` 時 `load()` 直接 `return`（`:322-324`）→ **不設旗標**，首跑仍可正常建新檔。
- `persistBestEffort` 的 early return（`:342-344`）確實在 `synchronized (lockForPath())`（`:345`）**之前**，因此不會形成「持 path lock 再取 `this`」的反向鎖序。
- `persistenceUnavailable()` 是 `synchronized` 方法，呼叫結束即釋放 `this` → **`this` 與 path lock 從不同時持有**；`flushLoop`（`:254-278`）也只在 `persistBestEffort` 之外才進 `synchronized (this)`。`put` / `remove` / `clear` / `flush` 四條路徑都是「先 `this`、釋放後才 path lock」，全局一致，不會死鎖。
- `PATH_LOCKS.computeIfAbsent` 的 lambda 是 `key -> new Object()`（`:378` 附近），**沒有**呼叫任何會回頭取 `this` 鎖的方法；`pathKey` 用 `toAbsolutePath().normalize()`（`:100`），兩個實例指向同一路徑會拿到同一個 monitor。
- tmp 名唯一：`file.getName() + "." + UUID.randomUUID() + ".tmp"`（`:358` 附近）；`finally` 在 `temporary != null` 時一定 `Files.deleteIfExists`，且刪除已 move 的名字是 no-op。
- `moveIntoPlace`（`:390` 附近）：內層只接 `AtomicMoveNotSupportedException` → 改 plain `REPLACE_EXISTING`；外層 `catch (NoSuchFileException)` 用 `Files.exists(temporary)` 分辨「來源已消失 → `return false`（不推進 `persistedRevision`）」與「目的地側出問題 → 原樣 rethrow」。`NoSuchFileException` 與 `AtomicMoveNotSupportedException` 互不繼承，catch 順序無誤。
- `persistedRevision` 只在 `moveIntoPlace` 回 `true` 時推進，所以失敗的寫入不會讓後續較新的快照被誤判成過期。
- `remove` 的節流與 `put` **完全一致**（同樣的 `flushing` → `dirty` 折疊、同樣的 `UNSAVED_ENTRIES_BEFORE_FLUSH` / `FLUSH_INTERVAL_MILLIS` 雙界、同樣把 `lastFlushMillis` 前移），且 `remove` 會 `++revision`，所以之後的 `flush()` 一定會把移除帶進檔案。
- `properties.load` 的 `IllegalArgumentException` 從 `load()` 內層搬到 `ensureLoadedLocked` 的 catch（`:304-308`），語意等價且會同時設 `loadFailed`。

### BUG-C-004
- 非 leader 的 `batchLock.wait(1000L)`（`:646-658`）：catch 內 `pendingBatch.remove(entry)`、`entry.settled = true`、`entry.failure = interrupted`，然後 `Thread.currentThread().interrupt()` 再 `throw`。**清理了 `pendingBatch`**。
- leader 的 `catch (InterruptedException)`（`:693-705`）：**先** `synchronized (batchLock) { pendingBatch.remove(leader); leader.settled = true; }`（`:699-702`）**再** `Thread.currentThread().interrupt()`（`:704`）。順序正確——這正是原 bug 的關鍵。
- 回到 `while (true)`（`:636-665`）後：頂端第一件事是 `if (entry.settled) break;`（`:639-641`），`settled` 已為 `true` → 直接 break → 不再被 `!batchServing && pendingBatch.contains(entry)` 選為 leader（`contains` 也已為 false，雙重保險）。**不會自旋。**
- 中斷旗標**保留**給上層：`serveBatch` 之後沒有任何 `Thread.interrupted()` / 清旗標的程式碼；`translateBatched` 只做 `throw entry.failure`（`:666-668`）向外傳播。
- 所有回到迴圈頂端的出口逐條檢查：(a) `settled` 已 true → break；(b) 等待逾時 → `continue`（已睡過 1 秒，非忙等）；(c) 本執行緒成為 leader → `serveBatch` 必在 `finally`（`:708-716`）釋放 `batchServing` 並 `notifyAll`，leader 本身在中斷分支被單獨 `settled`，非中斷分支必被收進 `group`（`sameJob(leader.request, leader.request)` 恆真，且 `group` 空時 `sameJob` 檢查先於容量檢查）→ `finally` 統一 `settled`。沒有留下「unsettled 且已離隊」的狀態。
- `failGroup`（`:762-768`）對空 `group` 只是不進迴圈，安全；且只覆寫尚未有 `translated` / `failure` 的 entry。
- 額外：leader 若因 `group` 已被更早的同 job entry 填滿而沒進 `group`，`leader.failure = interrupted`（`:703`）與 `settled`（`:701`）仍是被顯式設定的，不會卡住。

### BUG-C-005
- `StreamAttempt.complete` 只由 `[DONE]` 設定（`:499-506`），其他地方沒有寫入；接受條件 `if (attempt.complete && accumulated.length() > 0)`（`:480`）確實要求 `complete`。
- 未完成時 `attempt.content` 保持 `null`（欄位初始值），`translateStreaming:417-423` 走既有的 `super.translateStreaming`（＝plain path，plain path 自己會在空回應時丟 `IllegalStateException`）。**不是 NPE，也不會靜默回空字串。**
- `ServerSentEvents.read` 已確認會把 `data: [DONE]` 的 payload 交給 handler（`:82`），並在 handler 回 `false` 時立刻停止（`:83`）；沒有結尾換行的最後一行也會被送出（`:88-93`），所以 `[DONE]` 不會被漏掉。
- `isRelayError`（`:531-555`）覆蓋三種錯誤形狀：`{"type":"error",…}`、`{"error":"…"}`、`{"error":{"message"/"type":…}}`。`readStringField` 只比對**key 位置**且要求值是字串（`JsonStrings.java:118-140` + `isKeyPosition`），所以正常 chunk 裡 `"content":"error"` 這種值不會被誤判——**keep-alive 誤判（`{"type":"ping","message":"keep-alive"}` 被當錯誤）已修掉**。
  - 反向 false negative（會被漏的 payload）：沒有 `type` 也沒有 `error` 鍵的扁平錯誤物件，例如 `{"code":429,"message":"rate limited"}`、`{"detail":"Not Found"}`、`{"object":"error",…}`。實際影響有限：這類 payload 不含 `choices[0].delta.content`，累積仍為空，且不會有 `[DONE]` → `complete == false` → 仍然走 plain path fallback，只是晚一步（不是正確性問題）。
- Gemini `finishReason`（欄位名 `finishReason`）與 Claude `stop_reason` 在**非串流**（`Gemini:115`、`Claude:103`）與**串流**（`Gemini:158-161`／`:179`、`Claude:146-149`／`:176`）兩條路徑都檢查了。Claude 的 `message_stop` 早退（`:143-145`）發生在 `stop_reason` 之前，但 Anthropic 的 `message_delta`（帶 `stop_reason`）一定先於 `message_stop`，順序無誤。
- 正常 `STOP` / `end_turn` **不會**被誤判成截斷：兩處都是 `if (reported != null && !X_STOP.equals(reported))` 才記錄，`requireCompleteAnswer` 又對 `null` 與 `end_turn`/`STOP` 直接放行。
- 離線門檻：`OpenAiChatTranslationProvider.java:592` 與 `LlamaCppOfflineProvider.java:751` 兩處都是 `LoopbackContext.TOKENS(1024) − 128 − estimatePromptTokens(text) < 64` → 拒絕，**數值與公式一致**；且共用同一個 `estimatePromptTokens`（package-private，同 package 可存取，`:1215`）。
- 合法短句**不會**被誤擋：3 個中文字的短句 `estimatePromptTokens = (0+3)/4 + 3 + 1 = 4`，剩 `1024 − 128 − 4 = 892`，遠大於 64；連 256 字的純中文行（257 tokens）也還有 639。實務上不可能誤擋一般聊天行，門檻只在 >800 tokens 的長文才觸發。

### BUG-C-006
手動推演 `UniversalTranslatorSettingsScreen` 的存檔鏈（`:744-770`，`withSettings → withPromptSettings → withTranslationTuning → save`）：

| 步驟 | 呼叫 | `toProperties()` 落在誰身上 | `ownsPromptKeys` | prompt 值 | tuning 值 |
| --- | --- | --- | --- | --- | --- |
| 1 | `original.withSettings(...)` | `original`（`:502` 重讀磁碟） | false → 重讀 | 舊（但下一步覆蓋） | 舊 |
| 2 | `.withPromptSettings(q, p)` | 步驟 1 的新實例 | false → 重讀，之後 `applyPromptSettings` 覆寫 | **新** ✔ | 舊 |
| 3 | `.withTranslationTuning(w, b)` | 步驟 2 的實例（`:354` 回 true） | **true → 跳過重讀** | **新** ✔ | **新** ✔ |
| 4 | `updated.save()` → `toProperties()`（`ForgeConfig.java:391`） | 步驟 3 的實例 | **true → 跳過重讀** | **新** ✔ 寫進檔 | **新** ✔ |

- `withTranslationTuning`（`:354`）與 `save()`→`toProperties()`（`:502`）這兩個「最後一棒」都保留了新值 → **本 bug 已真的修好**，不是表面修復。
- `applyPromptSettings()` 單獨呼叫（`UniversalTranslatorSettingsScreen.java:626-631`：`original.withPromptSettings(...)` → `save()`）：步驟 2 即 `true`，`save()` 不再重讀 → 新 prompt 進檔 ✔。
- `withDebugLog`（診斷面板 `:1423`：`live.withDebugLog(enabled)` → `save()`）：同理 ✔。
- 四份 config **完全同構**：ctor 3 參數版預設 `false`；`withSettings`/`withEnabled`/`withHomeSettings` → `false`；`withPromptSettings`/`withTranslationTuning`/`withDebugLog` → `true`。逐一比對 ForgeConfig `:285/291/303/338/354/374`、FabricConfig(modern) `:285/291/303/338/354/374`、FabricConfig(legacy) 同位置、LegacyConfig `:278/290/296/308/343/359/379` 全部一致。
- `promptKeys()`（`OnlineProviderConfig.java:437`）回傳 `Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(PROMPT_KEYS)))` → **不可變**，且 `PROMPT_KEYS` 陣列本身仍是 private，呼叫端改不到；目前唯一使用者是四份 config 的 `toProperties()`（只讀）。
- `preservePromptSettings(Path, Properties)` 兩參數版（`:454-456`）只是委派 `emptySet()`，而 `:488-495` 的迴圈在 `ownedKeys` 為空集合時等價於原本的無條件覆寫 → 對其他呼叫端**行為完全沒變**（`IOException` / `RuntimeException` 的兩條 return 也維持原樣）。

### 跨檔
- **Java 8 相容性**：16 個改動檔逐一 grep `\bvar `、`List.of`、`Set.of`、`Map.of`、`ProcessHandle`、`.repeat(`、`Stream.of`、`Optional.isEmpty`、`isBlank(`、`strip(` → **0 命中**。用到的 `computeIfAbsent(pathKey, key -> new Object())`、`Multi-catch`、方法引用 `HudIndicatorCorner::fromConfig` 都是 Java 8 API。
- **未使用 import / 欄位**：`TranslationOutputValidator` 的 `ArrayList`（`:189`）、`Collections`（`:93`）、`Set`（`:108`）都在用；`TranslationCoordinator` 新增的 `ArrayList`（`:483`、`:485`）在用；`PersistentTranslationCache` 新增的 `NoSuchFileException`、`UUID`、`ConcurrentHashMap` 都在用；`OnlineProviderConfig` 新增的 `Arrays`、`LinkedHashSet`、`Set` 都在用；四份 config 新增的 `Collections` 都在用。新欄位 `ownsPromptKeys`、`loadFailed`、`pathKey`、`StreamAttempt.complete` 全部有讀寫。
- **公開 API 相容**：`withXxx` / `save()` / `load()` / 各 getter 簽名全部未變；既有的 `TranslationStore` 呼叫端（`FabicTranslationRuntimeBase`、四處 `ForgeTranslationRuntime`、`LegacyTranslationRuntime`）只用到 `get`/`put`/`clear`/`flush`/`size`，新增的抽象方法不影響（它們不實作介面，只持有 `TranslationStore` 參考）。`preservePromptSettings(Path, Properties)` 保留為 overload，`requireDisplaySafe(String, String)`、`requireValid(String, String)` 簽名未變。**唯一破壞**是新增抽象方法 → 見 M-3（測試端）。
- **`LlamaCppOfflineProvider` 呼叫 `OpenAiChatTranslationProvider.estimatePromptTokens`**：該方法在 `:1215` 是 `static`（package-private），兩者同在 `org.universaltranslator.core.provider`（`LlamaCppOfflineProvider` 無 package 宣告差異）→ 可存取，不會編譯失敗。`LoopbackContext` 也是同 package 的 package-private 類。
- **註解語言**：新增／改寫的註解與 javadoc 全部是英文，符合專案風格。
