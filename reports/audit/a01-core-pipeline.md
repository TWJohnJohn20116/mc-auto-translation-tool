# a01-core-pipeline 審計報告

範圍：`translator-core/src/main/java/org/universaltranslator/core/`（**排除** `net/`、`offline/`、`provider/`），共 49 個 `.java`。深讀順序：`RenderTranslationSession`(753)、`TranslationStats`(757)、`TranslationCoordinator`(458)、`ProtectedText`(365)、`PersistentTranslationCache`(296)、`DebugLog`(289)、`TranslationPrompt`(240)、`TranslationDiagnosticsSnapshot`、`DiagnosticsBundleExporter`、`DiagnosticsLogExporter`、`OutgoingTranslationPreview`、`TranslationStyleRuns`、`TranslationOutputValidator`、`LanguageHeuristics`、`TranslationCache`、`TranslationStore`、`RecentUserText`、`LocalConfigSecurity`、`TranslationTextStyling`、`TranslationTextColor`、`TranslationDisplayMode`、`TranslationBlocklist`、`TargetLanguage`、`OfflineModel`、`TranslationQuality`、`TranslationActivity`、`UserAgent`、`HudIndicator*`(5)、`SettingsScreenLayout`、`SettingsSelectionList`、`SettingsUiAnimation`、`HomeQuickSettingsState`、`UiLimits`、`UiTextKeys`、`LogMessages`、`TextKind`、`MinecraftContentScope`、`TranslationProviderCatalog`、`TranslationProviderStatus`、`TranslationResult`、`TranslationRequest`、`TranslationStreamListener`、`TranslationProvider`、`UiTranslator`、`TranslationStatusLocalizer`。
配額：BUG-C ×3、BUG-M ×2、BUG-S 041–045（有才寫，最多 5）
產出：BUG-C 3 / BUG-M 2 / BUG-S 5（實際）

> 跨包子包（`net/`、`offline/`、`provider/`）的問題不在本報告；凡根因落在子包者，已於條目內註明「交由 a02」。
> 依賴端（`platforms/**`）只作為量級與影響的證據引用，問題本身不歸本報告。

---

### BUG-C-001 · 未知的 cosmetic 設定值讓翻譯永久全滅且設定畫面開不了

- **嚴重度**：嚴重 ｜ **信心**：高
- **位置**：
  - `translator-core/src/main/java/org/universaltranslator/core/TranslationTextColor.java:40-51`（拋出點 `:50`）
  - `translator-core/src/main/java/org/universaltranslator/core/TranslationDisplayMode.java:8-21`（拋出點 `:20`）
  - 呼叫端（證據）：`platforms/fabric/shared/settings/modern/src/main/java/org/universaltranslator/fabric/FabricConfig.java:124-129`、`platforms/fabric/shared/base/src/main/java/org/universaltranslator/fabric/FabricClientBootstrapBase.java:113-119`、`:223-226`
- **受影響範圍**：所有平台（Fabric modern／legacy、Forge modern／legacy、NeoForge 皆共用這兩個列舉與同一種 `load()` 寫法）；只要 `universal-translator.properties` 的 `display-mode` 或 `translated-text-color` 出現不在表內的值
- **現象**：
  1. 進入世界後**一句都不翻**，HUD 沒有翻譯中指示。
  2. **按設定鍵完全沒反應**（畫面不開、聊天只偶爾出現一行失敗訊息，且受 60 秒冷卻），使用者沒有任何介面可以改回來。
  3. 問題在每次啟動時重現，手動編輯的檔案也不會被自動修正。
- **觸發條件**：`display-mode` 或 `translated-text-color` 的值不屬於列舉的 `configName`。實務來源有三個，都不需要使用者「亂填」：
  - 降版：新版的設定鍵被舊版讀到（或反之）；
  - 使用者手改／從他人的設定檔複製整段；
  - 設定檔被工具改寫成中文值（`translated-text-color=青色`）。
  例：`display-mode=both`、`translated-text-color=red`、`translated-text-color=cyan`。
- **根因**：兩個 `fromConfig` 是全 repo 唯一對「未知設定值」拋例外的列舉解析器：
  ```java
  // TranslationDisplayMode.java:20
  throw new IllegalArgumentException("Unsupported display mode: " + value);
  // TranslationTextColor.java:50
  throw new IllegalArgumentException("Unsupported translated text color: " + value);
  ```
  這兩個方法在 `FabricConfig.load()` 的建構子裡被直接呼叫（`:124-125`、`:128-129`），例外一路穿出 `load()`：
  - `onInitializeClient()` 於 `FabricClientBootstrapBase.java:116-119` 接住後只做 `error(...)` + `shutdown()`，註解明寫 "translation remains disabled"；
  - 設定鍵路徑 `openSettingsFor()` 於 `:222-226` 也一樣：`FabricConfig.load(...)` 在 `try` 內就拋，`openSettings(...)` 永遠不會被呼叫。而 `save()` 從未被執行，壞值永遠留在檔案裡。
  同一個 `load()` 內的姊妹列舉全部刻意 fallback（`HudIndicatorCorner`、`HudIndicatorColor`、`HudIndicatorContent`、`HudIndicatorVisibility`、`OfflineModel`、`TranslationQuality` 都是 clamp/預設值），`HudIndicatorCorner` 的 javadoc 甚至寫著「must never be able to fail configuration loading」。這兩個列舉違反了自己專案的契約。
- **影響**：一次寫錯值 = **功能全滅且無法自救**（唯一的復原方式是使用者知道去遊戲目錄手改 `.properties`）。嚴重度高於一般崩潰：崩潰至少會當場看到 stacktrace，這裡只看到「翻譯壞了」。而且 `enable`、`provider`、`api-key` 等設定全都讀不到，等於整個模組報廢。
- **修法**：改成 fallback 並留一行痕跡；未知值不該讓設定載入失敗。

```java
// TranslationTextColor.java:40-51
public static TranslationTextColor fromConfig(String value) {
    if (value == null || value.trim().isEmpty()) {
        return AQUA;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    for (TranslationTextColor color : values()) {
        if (color.configName.equals(normalized)) {
            return color;
        }
    }
    // 未知值不該讓整個模組停擺：退回預設並在 debug log 留一行。
    DebugLog.global().log("config-fallback", "field", "translated-text-color",
            "value", value, "fallback", AQUA.configName);
    return AQUA;
}
```

```java
// TranslationDisplayMode.java:8-21
    if ("bilingual".equals(normalized) || "original-and-translated".equals(normalized)
            || "original_and_translated".equals(normalized)) {
        return ORIGINAL_AND_TRANSLATED;
    }
    DebugLog.global().log("config-fallback", "field", "display-mode",
            "value", value, "fallback", "translated-only");
    return TRANSLATED_ONLY;
```

補強（交由 a03／a04）：`*Config.load()` 對每個 `fromConfig` 包一層 `try/catch (RuntimeException)` → 該欄位用預設值，其餘照常載入，讓「新增列舉忘了 fallback」不再有全滅的後果。

---

### BUG-C-002 · 未通過顯示安全校驗的譯文已先寫進持久化快取，該行終身翻不出來

- **嚴重度**：嚴重 ｜ **信心**：高
- **位置**：
  - `translator-core/src/main/java/org/universaltranslator/core/TranslationCoordinator.java:332-339`（讀快取 + `requireValid`）、`:354-368`（provider 呼叫 → `requireValid` → `stats.recordSuccess` → `cache.put`）
  - 對照 `translator-core/src/main/java/org/universaltranslator/core/TranslationCoordinator.java:181-187`（`requireDisplaySafe` 在**更外層**，`cache.put` 之後才跑）
  - 觸發點 `translator-core/src/main/java/org/universaltranslator/core/TranslationOutputValidator.java:81-83`
  - 還原點 `translator-core/src/main/java/org/universaltranslator/core/ProtectedText.java:287-302`
- **受影響範圍**：所有平台、所有 provider、**開著 `disk-cache`（預設 `true`，`FabricConfig:148`）的所有使用者**；尤其多人伺服器
- **現象**：某些行永遠顯示原文。debug log 每 30 秒出現一次同一條 `failure ... reason=invalid-output`，`TranslationStats` 的 `invalid-output` 計數穩定上升；重開遊戲後完全沒有改善（因為壞值已在磁碟快取裡）。
- **觸發條件**（兩個都成立就永久失效，且第一個可被第三方遠端觸發）：
  1. 一段文字含有 `__UT_<數字>__`。Minecraft 使用者名稱合法字元正好是 `[A-Za-z0-9_]`、長度 3–16，**`__UT_0__` 是完全合法的玩家 ID**；任何伺服器上的玩家只要改名成這個字串，聊天、玩家列表、進出訊息、計分板中提到他的每一行都會永久失效。伺服器 MOTD、告示牌、書本內容同理。
  2. 譯文命中 `INSTRUCTION_FRAGMENTS`（`TranslationOutputValidator.java:12-39`）的任一片段，例如把英文 `translation engine` 譯成 `翻译引擎`、`output translated text only` 譯成 `只返回译文`，就會被判為「回顯指令」。
- **根因**：兩層校驗的**順序**錯了，且第二層失敗不會撤銷第一層的副作用。
  ```java
  // TranslationCoordinator.java:360-368（worker 內）
  translated = TranslationOutputValidator.requireValid(core, translated);   // 第一層：過
  ...
  stats.recordSuccess(providerId, elapsedMillis(startedAt));
  cache.put(cacheKey, translated);        // ← 副作用已提交（記憶體＋磁碟）
  ...
  // TranslationCoordinator.java:181-185（更外層）
  String restored = TranslationOutputValidator.requireDisplaySafe(       // 第二層：可能拋
          text, translateSegments(protectedText, ...));
  ```
  `cache.put` 在 `requireDisplaySafe` 之前，而 `requireDisplaySafe` 拋例外時 `cache.remove` 從未被呼叫，所以**一個被判定不可顯示的值從此永久佔住這個 key**。下一輪（30 秒後、重開遊戲後）走 `cache.get` → `requireValid` 過關（它只比對 token 序列與長度）→ 回到 `requireDisplaySafe` → 再拋一次。形成 `FAILURE_RETRY_MILLIS`（`RenderTranslationSession.java:18`）驅動的永久失敗迴圈。
  第二個觸發條件之所以能命中，是因為 `ProtectedText.getSegments()` 對受保護值回傳的是**原始字串**（`ProtectedText.java:296-302`），還原後 `__UT_0__` 原樣出現在輸出裡，而 `TranslationOutputValidator.java:81-83` 對「還原後」的字串做無條件 token 檢查：
  ```java
  if (TOKEN.matcher(output).find()) {
      throw new IllegalArgumentException("Restored translation leaked an internal placeholder");
  }
  ```
  但 segment 路徑**根本不會把 token 送給 provider**（`TranslationCoordinator.java:283-303` 只送非保護段），所以「洩漏 token」這個失敗模式在現行架構下不可能發生 —— 這個檢查只會誤殺合法內容。`ProtectedText.restore()` 自己的註解（`:313-314`）也明說「a value that happens to contain a token literal (e.g. `"__UT_1__"`) is never re-scanned」，兩份程式碼互相矛盾。
- **影響**：受影響的行**終身**不翻譯（非暫時、非可重試恢復）。每個受影響的行每 30 秒送出一次無效重試：`RenderTranslationSession` 的 `retryAfter` 上限 1024 條，被這種 key 佔滿後（例如 1024 個含該玩家名的計分板行）`retryOrder` 會開始淘汰其它真正失敗的 key，退化重試節流。遠端可觸發 ⇒ 一個玩家改名就能讓同一伺服器上所有使用本模組的客戶端永久少翻一部分內容，屬於**跨使用者、無需本機權限**的可用性破壞。
- **修法**：副作用必須與最終校驗同進退。最乾淨的做法是把 `cache.put` 移到 `requireDisplaySafe` 之後，或至少在校驗失敗時撤銷。

```java
// TranslationCoordinator.java：把 cache.put 從 translateSegment 移出來
private String translateSegment(..., boolean commit) { ... }

// worker（:181 之後）
String restored;
try {
    restored = TranslationOutputValidator.requireDisplaySafe(
            text, translateSegments(protectedText, effectiveSource, targetLanguage, effectiveKind, listener));
} catch (RuntimeException notDisplaySafe) {
    // 只撤銷本次請求真的寫入的 key；translateSegment 需要把寫入的 key 收集起來回傳
    for (String written : protectedText.writtenCacheKeys()) {
        cache.remove(written);   // TranslationStore 需新增 remove(String)
    }
    throw notDisplaySafe;
}
```

```java
// TranslationOutputValidator.java:81-83：token 洩漏檢查必須對「受保護值本身」讓路
// 由呼叫端傳入本次請求真正存在的 token 集合；segment 路徑傳空集合 ⇒ 不做此檢查
public static String requireDisplaySafe(String source, String restored, Set<String> liveTokens) {
    ...
    for (String token : liveTokens) {
        if (output.contains(token)) {
            throw new IllegalArgumentException("Restored translation leaked an internal placeholder");
        }
    }
    ...
}
```

補強（低風險但在同一條路徑上）：`parseWithPattern` 的 token 改成每次 parse 遞增的 nonce（`__UT{nonce}_{index}__`），讓玩家無法偽造可定址的 token；`tokenIndex` 已會把不符格式者當字面文字處理（`ProtectedText.java:340-346`），改動安全。

---

### BUG-C-003 · 快取檔沒有任何跨實例保護：讀取失敗被靜默吞掉後首次 flush 用殘缺 map 覆寫整檔

- **嚴重度**：嚴重 ｜ **信心**：中高（程式鏈路確定；「暫態讀取失敗」的實際發生率未在本機量測）
- **位置**：
  - `translator-core/src/main/java/org/universaltranslator/core/PersistentTranslationCache.java:222-234`（`ensureLoadedLocked` 空 catch）、`:236-252`（`load`）
  - `translator-core/src/main/java/org/universaltranslator/core/PersistentTranslationCache.java:116-129`（`put` 的 flush 觸發）、`:258-285`（`persistBestEffort`）
  - `translator-core/src/main/java/org/universaltranslator/core/PersistentTranslationCache.java:40`（`diskLock` 是 **實例** 欄位）、`:268`（tmp 名稱只由檔名決定）
  - 共犯（證據）：`platforms/fabric/shared/base/src/main/java/org/universaltranslator/fabric/FabricTranslationRuntimeBase.java:136-137`（每次重建都 `new PersistentTranslationCache(config.cacheFile, 10_000)`）、`:175`（舊 session 交給背景 reaper 關閉 → 舊實例在關閉前仍持有同一個檔案）
- **受影響範圍**：所有開啟 `disk-cache`（預設開）的使用者；Windows 尤其（防毒掃描會短暫持有剛寫出的檔案）
- **現象**：某一次啟動後「所有看過的字串都要重新翻一遍」，設定沒改、快取檔也還在；此後每次開遊戲都從零開始。debug log 與 `latest.log` 裡**沒有任何一行**提到快取載入失敗。同時可能觀察到設定目錄裡殘留 `universal-translator-cache.properties.tmp`。
- **觸發條件**：
  1. `Files.newBufferedReader(file, UTF_8)`（`:241`）拋任何 `IOException`（`AccessDeniedException`／`FileSystemException`／被防毒或另一個實例暫時持有）；
  2. 或 `properties.load` 拋 `IllegalArgumentException`（截斷的 `\uXXXX`）。
  任一發生後，本次 session 的第一次 `put` 因為 `lastFlushMillis` 初值為 `0L`（`:59`），`now - 0 >= FLUSH_INTERVAL_MILLIS` 必然成立（`:117-118`），於是立刻把只有新譯文的 map 寫成 tmp 再 `REPLACE_EXISTING` 覆蓋原檔。
- **根因**：三個獨立缺陷疊在同一個檔案上：
  1. **讀取失敗被降級成「空快取」且不再重試**：
     ```java
     private void ensureLoadedLocked() {
         if (loaded) return;
         loaded = true;                 // ← 先設旗標
         try { load(); }
         catch (IOException unreadableCache) {
             // 註解只說 "degrade to an empty cache"，沒有記錄、沒有重試、沒有禁止覆寫
         }
     }
     ```
     「檔案不存在」與「暫時讀不到」共用同一條降級路徑（`:236-239` 直接 `return`），差別只在後者會把整個既有內容丟掉。
  2. **降級後沒有「不要覆寫既有檔案」的保護**：`persistBestEffort` 直接 tmp + `Files.move(..., REPLACE_EXISTING)`（`:274-279`），沒有 `.bak`、沒有「本次 session 未成功載入」的閘門。
  3. **互斥的粒度是實例，不是檔案**：`diskLock` 是 `private final Object`（`:40`），tmp 名稱固定為 `<file>.tmp`（`:268`）。而 `FabricTranslationRuntimeBase.initialize()` 在**舊 session 還活著**的時候就建了指向同一路徑的新實例（`:136-137`），舊 session 只交給單執行緒 reaper 稍後關閉（`:175`）；舊實例的 `TranslationCoordinator.close()` 必然呼叫 `cache.flush()`（`TranslationCoordinator.java:410-411`），於是一次換設定就會有**兩個實例同時對同一個 `.tmp` 與同一個目標檔寫入**。`Files.newBufferedWriter` 預設 `CREATE + TRUNCATE_EXISTING`，第二個寫入者會截斷第一個寫入者正在寫的 inode，兩條流的 file position 各自獨立 → 位元組交錯；先 `move` 的一方贏，另一方的 `move` 拋 `NoSuchFileException` 被 `catch (IOException ignored)`（`:281-283`）無聲吞掉。
- **影響**：
  - **量化**：上限 10 000 條、每條 ≈ 64（SHA-256 鍵 hex）+ 1 + 約 60（10 個中文字的 `\uXXXX` 轉義）≈ 125 bytes ⇒ 檔案約 **1.25 MB**。一次暫態讀取失敗就會讓這 1.25 MB（相當於使用者花費的 provider 查詢成本）**永久消失且無備份**，並在之後每次啟動都重來。
  - 交錯寫入的結果是兩份 `Properties` 流的混合：可解析時混進大量不可命中的垃圾鍵，吃掉 10 000 條額度並排擠真條目；截斷在多位元組 UTF-8 上時 `MalformedInputException` 被 `:229` 吞掉 → 整份歸零。
  - 舊實例的 flush 以「換設定前的完整快照」覆蓋新檔，新 session 剛寫入的譯文靜默消失（下一次 flush 才補回，但若此時關遊戲就真的沒了）。
  - 整個過程**零日誌**，使用者與維護者都無從得知。
- **修法**：三件事一起做（三者缺一都還有殘留風險）。

```java
// 1) 讀取失敗要留痕，並禁止本次 session 覆寫既有檔案
private boolean loaded;
private boolean loadFailed;      // 新增

private void ensureLoadedLocked() {
    if (loaded) {
        return;
    }
    loaded = true;
    try {
        load();
    } catch (IOException | IllegalArgumentException unreadable) {
        loadFailed = true;                                  // 不再重試，但也不准寫
        DebugLog.global().logFailure("cache-load", unreadable);
    }
}

private void persistBestEffort(Map<String, String> snapshot, long snapshotRevision) {
    synchronized (diskLock) {
        if (loadFailed) {                                   // 寧可整個 session 不持久化
            return;
        }
        ...
    }
}
```

```java
// 2) tmp 名稱加上行程與實例唯一標記，並在結束時清掉
private final String tmpSuffix = "-" + ProcessHandle.current().pid()
        + "-" + Long.toHexString(System.nanoTime());

Path temporary = file.resolveSibling(file.getFileName().toString() + tmpSuffix + ".tmp");
try {
    ...寫入 + move...
} finally {
    try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
}
```

```java
// 3) 互斥對象改成「檔案路徑」而不是「實例」
private static final java.util.concurrent.ConcurrentHashMap<String, Object> PATH_LOCKS =
        new java.util.concurrent.ConcurrentHashMap<String, Object>();
private final Object diskLock = PATH_LOCKS.computeIfAbsent(
        file.toAbsolutePath().normalize().toString(), new java.util.function.Function<String, Object>() {
            @Override public Object apply(String key) { return new Object(); }
        });
```

更上游（交由 a03／a04／a05）：`initialize()` 在 `config.cacheFile` 與舊 session 相同時直接沿用舊的 `TranslationStore`，不要建第二個實例 —— 這一條同時解掉「舊快照覆蓋新資料」與「兩個實例搶同一個 tmp」。

---

### BUG-M-001 · 有玩家名字面量時漏了 `Pattern.MULTILINE`，多人遊戲行首項目符號保護整條失效

- **嚴重度**：中 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/ProtectedText.java:175-176`（字面量版本的 `Pattern.compile`）；對照 `:83`、`:84-85`（無字面量版本有 `Pattern.MULTILINE`）、`:161-170`（把 `PROTECTED_SOURCE` 內嵌進來）、`:68`（`BULLET_CORE` 靠 `^` 錨定）
- **受影響範圍**：所有平台；**只要 `protectedLiterals` 非空就命中**——多人連線必然成立（`FabricTranslationRuntimeBase` 會把伺服器位址與全部玩家名加進字面量表）。單人遊戲（字面量表為空）反而不受影響。
- **現象**：計分板／HUD／聊天中的 `• `、`» `、`◆ ` 等行首裝飾在多人遊戲被送去翻譯；模型回傳自己挑的字形，render bridge 依 text run 重新貼樣式，整行的顏色跟著位移。同一個模組在單人正常、在多人異常。
- **觸發條件**：`protectedLiterals` 至少 1 個（多人連線必然）**且**文字是多行、第 2 行以後以符號／標點開頭。例：`"• Wins: 0\n• Losses: 1"`；計分板與物品 lore 大量採用這個形式。
- **根因**：`compilePattern` 把 `PROTECTED_SOURCE` 內嵌成 alternation 的一支（`:170`），但編譯時只帶 `CASE_INSENSITIVE | UNICODE_CASE`：
  ```java
  // ProtectedText.java:175-176
  return Pattern.compile(
          source.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  ```
  而 `BULLET_CORE = "^[\\p{S}\\p{P}]+[ \\u3000]*"`（`:68`）靠 `^` 錨定；兩個靜態 pattern 都明確帶 `Pattern.MULTILINE`（`:83`、`:84-85`），只有字面量版本漏掉。`:81-82` 的註解「MULTILINE so the leading-bullet pattern anchors at the start of every line」正好說明這是必要條件。
- **影響**：多人環境下（本模組主場景）計分板每一行開頭的裝飾字元都被送給 provider。除字形被替換、整行顏色位移外，每個 bullet 還會多吃一次 `MAX_PENDING_TRANSLATIONS`（128）配額 —— 一張 20 行的計分板就有 20 個無謂請求排掉真文字的額度。同一份設定在單人與多人行為不同，違反類別註解 `:58-67` 自述的保證。
- **修法**：

```java
// ProtectedText.java:175-176
return Pattern.compile(
        source.toString(),
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE);
```

```java
// CoreSelfTest 追加：帶字面量時，第二行以後的 bullet 仍必須是受保護段
ProtectedText parsed = ProtectedText.parse("• a\n• Wins: 0", java.util.Arrays.asList("Steve"));
assertTrue(parsed.getSegments().get(2).isProtectedValue());
```

---

### BUG-M-002 · 每幀對每個文字元件做 O(n²) 的 `Style.equals` 掃描，而該條件在合併後恆為真

- **嚴重度**：中 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/TranslationStyleRuns.java:83-98`（`distinctStyleCount`，雙層迴圈）、`:105-107`（`shouldRebuildRuns(List)`）、`:123-133`（`shouldRebuildRuns(List, Object)`）
- **受影響範圍**：所有平台的 render bridge。實際呼叫點：`platforms/fabric/shared/rendered-common/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:102-104`、`:223-225`；`platforms/fabric/26.x/.../RenderedTextBridge.java:103-104`、`:224-225`；`platforms/forge/modern/1.18.2/.../RenderedTextBridge.java:105-106`、`:226-227`；`platforms/forge/modern/1.16.5/.../RenderedTextBridge.java:100-101`、`:196-197`；`platforms/forge/shared/rendered-1.19.2-plus/.../RenderedTextBridge.java:170-171`；`platforms/fabric/legacy/shared/rendered/1.16.5/.../RenderedTextBridge.java:103-104`、`:224-225`；`platforms/fabric/1.16/.../RenderedTextBridge.java:140-141`
- **現象**：在聊天列、玩家列表、計分板大量重繪時 client tick 時間上升；`translated-view` 打開時尤其明顯。屬於「每次渲染都做重活」而非單行 micro-opt。
- **觸發條件**：任一走 `mergeAdjacent` 的 render bridge 繪製一個有 `n ≥ 2` 個 style run 的元件，且每幀都繪製（聊天歷史、玩家列表、計分板側欄都是每幀重畫）。
- **根因**：`shouldRebuildRuns` 呼叫 `distinctStyleCount`，而 `distinctStyleCount` 對每一個 run 都回頭線性掃描已收集的 style 清單（`:87-96`），每次比較都是 `Object.equals`——傳進來的正是 Minecraft 的 `Style` 實例，其 `equals` 是逐欄位值比較（顏色、bold、italic、underline、strikethrough、obfuscated、insertion、clickEvent、hoverEvent、font）。
  ```java
  // TranslationStyleRuns.java:105-107
  public static boolean shouldRebuildRuns(List<Run> runs) {
      return runs != null && runs.size() > 1 && distinctStyleCount(runs) > 1;
  }
  ```
  但 `mergeAdjacent`（`:62-80`）的產出不變量是「相鄰 run 的 styleKey 一定不同」（`:72-77` 不同才 `add`，相同就合併）。所以 `runs.size() > 1` **已經蘊涵** `distinctStyleCount(runs) > 1`，`distinctStyleCount` 這兩處呼叫都是**純浪費**，而且是最貴的那一段。`:123-133` 的第二個重載同理（`:127-129` 的 `distinctStyleCount(runs) > 1` 恆為真）。
- **影響**：以 `n` 個 run 計，每次判定要做 `O(n²)` 次 `Style.equals`。實際量級：計分板側欄常有一個元件帶 15–40 個 sibling（每行一個 team prefix + name + suffix），玩家列表一頁 20 名玩家各有 3–5 個 run。
  - 15 個 run → 約 105 次 `Style.equals`；
  - 40 個 run → 約 780 次 `Style.equals`；
  - 60 fps × 一趟 20 名玩家的玩家列表（每位 4 個 run）→ 20 × 6 × 60 ≈ **7 200 次 `Style.equals`/秒**；若側欄 40 個 run 同時可見，再乘上 ~780 → 每秒數萬次多欄位物件比較。
  這些全部發生在 render thread 上，而且 `mergeAdjacent` 另外每幀配置一個 `ArrayList` 與多個 `Run`。在低階機器上直接反映為 FPS 掉格。
- **修法**：拿掉恆真的判斷，並讓 `distinctStyleCount` 只真的做「有多少種不同 style」時才跑（它目前唯一的必要性在 `shouldRebuildRuns`，而那裡不需要）。

```java
// TranslationStyleRuns.java:105-107：合併後相鄰 run 必不同 style，size > 1 就是多 style
public static boolean shouldRebuildRuns(List<Run> runs) {
    return runs != null && runs.size() > 1;
}

// TranslationStyleRuns.java:123-133
public static boolean shouldRebuildRuns(List<Run> runs, Object rootStyleKey) {
    if (runs == null || runs.isEmpty()) {
        return false;
    }
    if (runs.size() > 1) {
        return true;
    }
    // 相鄰 run 必定不同 style ⇒ 只剩一個 run，直接比它與 root
    return !equalKeys(runs.get(0).styleKey(), rootStyleKey);
}
```

`distinctStyleCount` 若仍要保留給外部診斷用，就在 javadoc 明寫「不得在 render path 呼叫」。另外 `mergeAdjacent` 的 `previous.text + text`（`:74`）在 40 個 sibling 的情境是 O(n²) 字元複製，可改成 `StringBuilder` 累積。

---

### BUG-S-041 · 診斷包的隱私聲明寫著「已排除譯文」，同一 zip 內的 `debug.log` 就是譯文原文

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/DiagnosticsBundleExporter.java:96`（聲明）對照 `:67-68`（把 debug log 原樣收進包）；內容來源 `translator-core/src/main/java/org/universaltranslator/core/DebugLog.java:146-148`、`:152-158`
- **受影響範圍**：所有開啟 debug log 後匯出診斷包並上傳到 issue／Discord 的使用者
- **現象**：`environment.txt` 寫著 `Privacy: API keys, endpoints and translated text are excluded.`，但同一個 zip 的 `debug.log` 內含逐筆 `preview=` —— `logRequest` 存的是**待翻譯原文**前 120 字，`logResponse` 存的是**譯文**前 120 字。使用者以為上傳的是「已去識別化的診斷」，實際把聊天內容與譯文一起公開了。
- **觸發條件**：設定開啟 `debug-log` → 玩一段時間 → 診斷畫面按「匯出診斷包」→ 把 zip 貼到公開 issue。
- **根因**：`DebugLog.redact`（`DebugLog.java:210-218`）只處理憑證（`DiagnosticsLogExporter.sanitize` + 三個 secret pattern + `LONG_BASE64`），對「文字內容」完全不動；而 `redactedFile(debugLogFile, ...)`（`DiagnosticsBundleExporter.java:65-68`）只做這一層就放進包。對照 `DiagnosticsLogExporter.java:49` 有同一句聲明，但那份報告**不含** debug log，所以那裡的聲明是成立的——證明這是 bundle 特有的一致性缺陷。
- **影響**：隱私承諾失效，且失效方向是「往外送」。使用者無法從任何地方得知 `preview` 欄位存在。屬於文案與行為不一致造成的實質外洩（不是 API key 等級，但聊天內容同樣是使用者資料）。
- **修法**：二選一，並讓兩邊永遠一致。

```java
// 方案 A（推薦）：聲明改成事實
content.append("Privacy: API keys and endpoints are excluded. The debug log inside this "
        + "archive may contain up to 120 characters of source and translated text per request.\n");

// 方案 B：包裡不放 debug.log 的內容，只放統計
put(zip, "debug.log", "# Debug log contents are excluded from the bundle; "
        + "reproduce with debug logging on and attach it manually if the maintainer asks.\n");
```

若採用方案 A，順帶把 `DebugLog.preview` 的輸出換成「長度 + 語言判定」而非原文會更徹底，但那會顯著降低 debug log 的可用性，不建議。

---

### BUG-S-042 · 自訂提示詞的 `\n` 逸出不是互逆轉換，設定畫面存一次檔就被靜默吃掉一層反斜線

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/TranslationPrompt.java:209-211`（`fromEditorText`）、`:220-222`（`toEditorText`）、javadoc `:213-214`
- **受影響範圍**：使用 `custom-system-prompt` 的使用者；設定畫面每次「開啟 → 儲存」一輪
- **現象**：使用者若想讓 prompt 裡留下**字面的**反斜線（例如要求模型輸出 JSON 轉義、或寫含 `\d` 的正則），第一次存檔後它變成真正的換行或消失；之後再也無法輸入字面 `\n`。
- **觸發條件**：`customSystemPrompt` 含 `\` 字元；開啟設定畫面後按儲存（即使沒改內容）。
- **根因**：javadoc（`:213`）宣稱 `toEditorText` 是 `fromEditorText` 的反函式，但實作是**單向**替換，不轉義反斜線本身：
  ```java
  public static String fromEditorText(String value) {   // :209-211
      return value == null ? "" : value.replace("\\n", "\n");
  }
  public static String toEditorText(String value) {     // :220-222
      return value == null ? "" : value.replace("\r\n", "\n").replace("\n", "\\n");
  }
  ```
  `toEditorText` 沒有把既有的 `\` 轉義成 `\\`，於是「字面 `\n`」與「換行」在編輯器裡是同一串字元；回來時一律被當成換行。`\\n`（兩個反斜線 + n）更是被降級成一個換行。
- **影響**：使用者設定被靜默改寫（資料遺失），且無法表達字面反斜線；錯誤只有在實際送出的 system prompt 與預期不同、導致譯文品質改變時才會被察覺，沒有任何提示。
- **修法**：先轉義反斜線再處理換行；`fromEditorText` 改成單趟掃描以支援 `\\`。注意這是**儲存格式變更**，要保留舊檔相容（舊值不含 `\\`，行為不變）。

```java
public static String toEditorText(String value) {
    if (value == null) {
        return "";
    }
    return value.replace("\\", "\\\\").replace("\r\n", "\n").replace("\n", "\\n");
}

public static String fromEditorText(String value) {
    if (value == null) {
        return "";
    }
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        if (c == '\\' && i + 1 < value.length()) {
            char next = value.charAt(i + 1);
            if (next == 'n') { out.append('\n'); i++; continue; }
            if (next == '\\') { out.append('\\'); i++; continue; }
        }
        out.append(c);
    }
    return out.toString();
}
```

---

### BUG-S-043 · 用 `char` 掃字母，非 BMP 漢字被判為「沒有文字」因此永不翻譯，也不受 `preserveHanText` 保護

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/LanguageHeuristics.java:49-62`（`char` 掃描 + `letters == 0` 早退）、`:72-74`（`han == letters`）、`:99-103`（`isHanIdeograph(char)` 只列三個 BMP 區塊）；連帶 `translator-core/src/main/java/org/universaltranslator/core/ProtectedText.java:79-80`（`HAN_SOURCE` 同樣只涵蓋 BMP）
- **受影響範圍**：所有平台；文字含 U+20000–U+2FA1F（CJK 擴充 B/F）等增補平面字元時
- **現象**：整行是生僻漢字（人名、幫派名、日文幽靈漢字、護照罕用字）時完全不被翻譯；混在標籤裡的非 BMP 漢字則未被 `preserveHanText` 保護而被送去翻譯、被模型改寫或掉落。
- **觸發條件**：`text` 只由增補平面字元組成（例 `"\uD840\uDC00"`），或 `preserveHanText = true` 且文字混有非 BMP 漢字。
- **根因**：`Character.isLetter(char)` 對代理對的**單個** `char` 一律回 `false`，所以純非 BMP 文字得到 `letters == 0`：
  ```java
  for (int i = 0; i < text.length(); i++) {
      char value = text.charAt(i);
      if (Character.isLetter(value)) {
          letters++;
          if (isHanIdeograph(value)) { han++; }
      }
  }
  if (letters == 0) {
      return false;      // 非 BMP 文字在這裡被整段丟掉
  }
  ```
  而 `isHanIdeograph(char)`（`:99-103`）的 javadoc 自承只覆蓋三個 BMP 區塊，所以 `han == letters`（`:72`）在混合 BMP／非 BMP 漢字時也永遠不成立。`ProtectedText.HAN_SOURCE`（`:79-80`）範圍與它一致地漏掉擴充平面。
- **影響**：特定文字永久不翻譯（使用者無從得知原因），且非 BMP 漢字會被送進 provider 造成字形被替換。屬於「罕見路徑未處理」，因此列為小。
- **修法**：以 code point 掃描，兩處一起補範圍。

```java
for (int i = 0; i < text.length(); ) {
    int codePoint = text.codePointAt(i);
    i += Character.charCount(codePoint);
    if (Character.isLetter(codePoint)) {
        letters++;
        if (isHanIdeograph(codePoint)) { han++; }
    }
}

private static boolean isHanIdeograph(int codePoint) {
    return (codePoint >= 0x3400 && codePoint <= 0x4dbf)
            || (codePoint >= 0x4e00 && codePoint <= 0x9fff)
            || (codePoint >= 0xf900 && codePoint <= 0xfaff)
            || (codePoint >= 0x20000 && codePoint <= 0x2fa1f)
            || (codePoint >= 0x30000 && codePoint <= 0x323af);
}
```

```java
// ProtectedText.java:79-80，Java regex 的 \x{...} 以 code point 比對
private static final String HAN_SOURCE =
        "(?:[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\x{20000}-\\x{2FA1F}]+)";
```

---

### BUG-S-044 · 預覽截斷切斷代理對，且 `finish()` 的 CAS 可能清不掉剛 publish 的狀態 → HUD 殘留 60 秒

- **嚴重度**：小 ｜ **信心**：高（截斷）／中（競態）
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/OutgoingTranslationPreview.java:155-158`（`substring` 截斷）、`:113-121`（`onPartialText`）、`:124-129`（`finish`）
- **受影響範圍**：所有使用「送出前翻譯」聊天路徑的平台；HUD 預覽行
- **現象**：
  1. 譯文含 emoji 或增補平面字元、且在第 45–48 個 UTF-16 單位處被截斷時，HUD 顯示 `?` 或半個字形。
  2. 送出聊天後，已完成的譯文在 HUD 狀態列旁殘留到 `DEFAULT_STALE_MILLIS`（60 秒）才消失，即使該請求早已 settle。
- **觸發條件**：
  1. `sanitize` 的 `value.substring(0, MAXIMUM_PREVIEW_CHARS - TRUNCATION_SUFFIX.length())`（=45）剛好落在代理對中間。
  2. `onPartialText` 已經通過 `finished` 檢查、但還沒 `publish(next)` 時 `finish()` 執行；或反向。
- **根因**：
  ```java
  // :155-158
  if (value.length() <= MAXIMUM_PREVIEW_CHARS) { return value; }
  return value.substring(0, MAXIMUM_PREVIEW_CHARS - TRUNCATION_SUFFIX.length()) + TRUNCATION_SUFFIX;

  // :114-121
  if (finished) { return; }
  State next = new State(sequence, sanitize(partialText), System.currentTimeMillis());
  state = next;
  publish(next);              // ← finished 的讀取與這次 publish 之間沒有任何同步

  // :124-129
  public void finish() {
      finished = true;
      CURRENT.compareAndSet(state, null);    // ← 若 state 已被換成尚未 publish 的 next，CAS 目標不符
  }
  ```
  `finish()` 用「handle 自己記得的 state」當 CAS 的預期值，但 `state` 欄位在 `publish` **之前**就被更新（`:119` → `:120`）。若 `finish()` 讀到的 `state` 是還沒進 `CURRENT` 的新值，CAS 必然失敗、什麼都沒清；接著 `publish(next)` 看到 `CURRENT == null` 就把非空預覽寫回去。整個窗口包含 `sanitize()` 的字元掃描，不是奈秒級。
- **影響**：殘留的是一行 48 字的譯文，60 秒後才被 staleness 清掉；玩家會以為翻譯還在進行。截斷問題影響單一字元顯示。
- **修法**：

```java
// 1) 截斷不要切開代理對
int end = MAXIMUM_PREVIEW_CHARS - TRUNCATION_SUFFIX.length();
if (Character.isHighSurrogate(value.charAt(end - 1))) {
    end--;
}
return value.substring(0, end) + TRUNCATION_SUFFIX;
```

```java
// 2) finish 以「目前擁有者」為預期值，並在 publish 之後再比對一次
private volatile State published;   // 只記錄真正進入 CURRENT 的狀態

public void finish() {
    finished = true;
    State current = published;
    if (current != null) {
        CURRENT.compareAndSet(current, null);
    }
    // publish 與 finish 交錯時，讓 publish 自己讓路
}

// publish 內
while (true) {
    State current = CURRENT.get();
    if (finished) { return; }               // 已結束就不再寫回
    if (current != null && current.sequence > next.sequence) { return; }
    if (CURRENT.compareAndSet(current, next)) { published = next; return; }
}
```

---

### BUG-S-045 · 憑證檔權限限制三種例外全吞、無驗證、無痕跡，非 POSIX 掛載點上等於沒做

- **嚴重度**：小 ｜ **信心**：中（機制確定；實際暴露程度取決於使用者環境的 ACL／umask）
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/LocalConfigSecurity.java:15-28`
- **受影響範圍**：設定檔含 `*-api-key`、`*-secret-key`、`llm-api-key` 等憑證的所有平台
- **現象**：在 chmod 被拒絕的環境（SMB／NFS／9p 掛載、部分容器 volume、`Program Files` 下的可攜式啟動器）`universal-translator.properties` 仍是 umask 預設的 `0644`，同機其他帳號可讀。診斷包與 debug log 都不會顯示「權限限制失敗」。
- **觸發條件**：`Files.setPosixFilePermissions` 拋 `IOException`／`UnsupportedOperationException`／`SecurityException` 任一。
- **根因**：整支類別是 best-effort，三個 catch 都是空塊，方法回傳 `void`，沒有事後驗證，也沒有任何記錄：
  ```java
  try {
      Files.setPosixFilePermissions(file, permissions);
  } catch (IOException ignored) {
  } catch (UnsupportedOperationException ignored) {
  } catch (SecurityException ignored) {
  }
  ```
  寫入順序本身是正確的（呼叫端在寫入憑證前先限制 `temporary`），所以問題只在「限制失敗時完全無聲」——連「有沒有生效過」都無法事後判斷。
- **影響**：憑證檔權限降級不會被任何人發現；在多使用者主機或共享掛載上屬於實際的機密暴露面。Windows 上 `UnsupportedOperationException` 是常態（`%APPDATA%` 本身有 per-user ACL，可接受），但那也意味著「這個方法在 Windows 上永遠是 no-op」這件事沒有任何地方說明。
- **修法**：回傳 boolean、讀回驗證、失敗留痕。

```java
public static boolean restrictToOwner(Path file) {
    Set<PosixFilePermission> ownerOnly = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    try {
        Files.setPosixFilePermissions(file, ownerOnly);
        return ownerOnly.equals(Files.getPosixFilePermissions(file));   // 讀回確認
    } catch (IOException | UnsupportedOperationException | SecurityException denied) {
        // 至少讓維護者在診斷包裡看得到，而不是靜默降級
        System.err.println("[MC Auto Translation Tool] owner-only permissions unavailable for "
                + file.getFileName() + ": " + denied.getClass().getSimpleName());
        return false;
    }
}
```

---

## 已排查但判定無問題

1. **`ProtectedText.java:257-268`、`:332-346`（`appendReplacement` 群組參照與 token 前綴衝突）** — 使用 `Matcher.quoteReplacement(token(index))`，`$1`、`\` 不會被當成群組參照或跳脫；`INTERNAL_TOKEN` 是 `__UT_\d+__`（`\d+` 貪婪，`__UT_10__` 不會先命中 `__UT_1`），`tokenIndex` 用 `"__UT_".length()` 常數而非魔術數字 5，超界以 `-1` 回退成字面文字。任務提示的 `$1` 與 `\u0001`/`\u00010` 前綴風險實際不存在。
2. **`TranslationCoordinator.java:152-157`、`:195-197`（`ConcurrentHashMap` 兩段式誤用）** — 用的是 `get` → `putIfAbsent` → 輸家沿用贏家的 future，在 `finally` 以 `inFlight.remove(requestKey, created)` 做有條件的移除，`close()` 另 `clear()`。沒有 `containsKey` + `put` 的競態。
3. **`RenderTranslationSession` 的所有容器都有硬上限（無無限增長）** — `translated` 4096（`:20`）、`translatedOutputs` 8192（`:23`）、`rejected` 8192（`:26`）、`retryAfter` 由 `retryOrder.size()` 1024 封頂（`:29`、`:313-317`）、`pending` 128（`:19`）並由 `reclaimExpiredPending` 回收未完成的 future（`:356-364`）。四個 `ConcurrentLinkedQueue` 都與對應 map 同步 poll/remove，沒有只長不消的路徑。
4. **`TranslationStats` 的樣本與 provider 表都有上限，落盤在鎖外且原子** — 延遲樣本是固定 512 的環形緩衝（`:39`、`:413-419`），從檔案讀回的 provider 數上限 64（`:43`、`:473-474`），`flush()` 先取快照再離開 `lock` 才寫 tmp + `ATOMIC_MOVE`（`:249-265`）。翻譯熱路徑上沒有磁碟 I/O。
5. **`TranslationCache.java:14-30`（`accessOrder = true` 的 `LinkedHashMap`）** — `get` 引發的結構性重排與 `put`、`clear`、`size` 全部在同一個 `synchronized` 方法內，不會 `ConcurrentModificationException`，`removeEldestEntry` 提供硬上限。`PersistentTranslationCache` 刻意改用插入序（`:73-81`）避免讀取時重排，是正確的取捨。
6. **`LanguageHeuristics.shouldTranslateToTarget` 在渲染路徑確實 allocation-free** — `letters == 0` 早退在 `containsSelfName` 與 `containsInternalArtifact` 之前（`:60-69`），`containsSelfName` 只做 4 次 `String.contains`（`:83-90`），`isHanIdeograph` 用三段範圍比較而非 `Character.UnicodeBlock.of` 的二元搜尋（`:99-103`）。除了本報告 BUG-S-043 的非 BMP 覆蓋缺口外，熱路徑成本符合註解承諾。
7. **`OutgoingTranslationPreview` 的靜態持有者不會跨 session 洩漏舊預覽** — 單一 `AtomicReference<State>`、序列號防舊蓋新（`:76-87`）、`DEFAULT_STALE_MILLIS = 60_000` 的 staleness 由 `text()` 讀取時就地清除（`:59-69`），且 `Handle` 只能釋放自己仍擁有的那一個 state。除 BUG-S-044 的競態窗口外，生命週期正確。
8. **`DebugLog` 的機密過濾與檔案上限在 `logRequest` / `logResponse` 路徑上覆蓋完整** — endpoint 只留 host（`hostOnly`，`:230-251`），值一律過 `redact`（`DiagnosticsLogExporter.sanitize` + `NAMED_SECRET` + `NAMED_SECRET_SPACED` + `LONG_BASE64`，`:210-218`）；`MAXIMUM_BYTES` 2 MB + 單一輪替檔（`:33`、`:279-282`），關閉時所有入口在 `enabled` 檢查就返回。追蹤後無憑證可從這條路徑離開機器（與 BUG-S-041 的「文字內容」是兩件事）。
