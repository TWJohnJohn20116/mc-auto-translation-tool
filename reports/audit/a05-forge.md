# a05-forge 審計報告

範圍：`platforms/forge/**` 全部 126 檔（`shared/common`、`shared/legacy-common`、`shared/local-guard-1.18.2-plus`、`shared/modern-1.18-1.19`、`shared/rendered-1.19.2-plus`、`shared/runtime-1.21.9-plus`、`shared/screens`、`shared/widgets`、`modern/*`、`1.21/shared/*`、`26.x/shared/*`、`legacy/*`）
配額：BUG-S-015 ~ BUG-S-022（8 條）、BUG-M-011 ~ BUG-M-012（有才寫，最多 2 條）
產出：BUG-C 0 / BUG-M 2 / BUG-S 8（實際 10 條）

`UniversalTranslatorScreens.java`(1679)、`LegacyConfigScreen.java`(1409)、`UniversalTranslatorConfigScreenBase.java`(1280) 的版面與互動細節不在本報告（已分配 a06）；本報告只涵蓋這些檔案的非 UI 邏輯（狀態持久化、事件處理、資料流、執行緒）。

---

### BUG-M-011 · 1.19.2+ 多樣式行整行跳過翻譯

- **嚴重度**：中（實質接近嚴重，見「影響」）｜ **信心**：高
- **位置**：
  - `platforms/forge/shared/rendered-1.19.2-plus/src/main/java/org/universaltranslator/forge/RenderedTextBridge.java:69-73`
  - 正確對照：`platforms/forge/modern/1.18.2/src/main/java/org/universaltranslator/forge/RenderedTextBridge.java:72-79` + `:90-137`（`rebuildStyledRuns` / `addRun`）
  - 正確對照：`platforms/forge/modern/1.16.5/src/main/java/org/universaltranslator/forge/RenderedTextBridge.java:67-74` + `:85-132`（同一段，型別為 `IReorderingProcessor`）
- **受影響範圍**：所有使用共用橋接的 Forge 目標 —— 1.19.2、1.20.1、1.21、1.21.1、1.21.3–1.21.11、26.1、26.1.1、26.1.2、26.2（`modern/shared.gradle:124-127`、`1.21/shared.gradle:35-36`、`26.x/shared.gradle:20-22` 三處都掛這一層）。1.16.5 與 1.18.2 不受影響。
- **現象**：帶兩種以上樣式的整行文字完全不翻譯。最明顯的是有隊伍顏色／前綴的伺服器聊天：同一個伺服器裡，單一顏色的系統訊息會翻譯，玩家聊天行（伺服器把 `<前綴><名字><內文>` 併成多個 style）整行原樣不動。記分板側欄與 Tab 玩家列表同理。
- **觸發條件**：任何以 `FormattedCharSequence` 送入 `Font` / `GuiGraphics` 且可見文字橫跨 ≥2 個不同 `Style` 的行。`Font.split(...)` 產生的聊天行即屬此類；只要伺服器為玩家名或訊息加了任何顏色、粗體或 click 事件就會命中。純單一樣式的行仍會翻譯，因此外觀像「時好時壞」。
- **根因**：`rendered-1.19.2-plus` 的 `translate(FormattedCharSequence)` 在偵測到多樣式時只寫 `return text;`：

  ```java
  // RenderedTextBridge.java:69-73
  if (multiStyle[0]) {
      // Flattening would keep only the first segment's colour and drop bold,
      // italic and click events from later segments, so leave the line intact.
      return text;
  }
  ```

  1.16.5／1.18.2 的同一個分支不是放棄，而是重建（`rebuildStyledRuns`：用 `TranslationStyleRuns.mergeAdjacent` 把行切成 style run、逐 run 呼叫 `translateRaw`、再以 `MutableComponent.append` 組回 `getVisualOrderText()`）。三份收斂成共用層時這段能力沒有被帶過去 —— 共用層 240 行裡沒有 `rebuildStyledRuns`、沒有 `addRun`，`TranslationStyleRuns` 只用於 `translateStyledSiblings`（`Component` 路徑）。1.19.2+ 的 `Style` / `MutableComponent` / `FormattedCharSequence` API 與 1.18.2 相同，沒有版本理由。
- **影響**：Forge 1.19.2–26.2 的所有目標在「有樣式的伺服器聊天」這個主場景上失去翻譯，而聊天是這個模組的核心用途。量化：一個 10 人的普通伺服器，玩家訊息 100% 是多樣式（名字顏色），等於玩家之間的對話完全不翻；單人遊戲與無樣式系統訊息仍正常，所以使用者回報時容易誤判成「有時能翻」。`1.20.1` 的 `DrawContextMixin.java:36-41` 對 `FormattedCharSequence` 的注入在多樣式行上等於空轉。
- **修法**：把 1.18.2 的 `rebuildStyledRuns` + `addRun` 原樣搬進共用層（只有 `TextComponent`→`Component.literal` 的型別差異）。

```java
// 取代 shared/rendered-1.19.2-plus/.../RenderedTextBridge.java:69-73
if (multiStyle[0]) {
    // 逐 style run 重建，保留每一段的顏色、粗體與 click 事件。
    FormattedCharSequence rebuilt = rebuildStyledRuns(text);
    if (rebuilt != null) {
        return rebuilt;
    }
}
String translated = translateRaw(original.toString());
// ...（後續不變：單樣式行仍走原本的扁平化路徑）

// rebuildStyledRuns / addRun 照抄 modern/1.18.2/.../RenderedTextBridge.java:90-137，
// 僅將 new TextComponent(...) 改為 Component.literal(...)
```

修完應補一個純函式測試：造一個兩段 style 的 `FormattedCharSequence`，斷言回傳值的可見文字不等於原文、且兩個 run 的 style 都保留。

---

### BUG-M-012 · 品質模式／自訂提示詞／除錯模式永遠無法寫進設定檔

- **嚴重度**：中（實質嚴重：設定永久丟失，且 UI 明確回報成功）｜ **信心**：高
- **位置**：
  - `platforms/forge/shared/common/src/main/java/org/universaltranslator/forge/ForgeConfig.java:457-461`（`toProperties()`：`writeTo` 之後才跑 `preservePromptSettings`，`:462-501` 的 `setProperty` 區塊沒有補寫這三個鍵）
  - `platforms/forge/shared/common/src/main/java/org/universaltranslator/forge/ForgeConfig.java:342-353`（`save()` 以 `toProperties()` 為唯一內容來源）
  - `platforms/forge/shared/common/src/main/java/org/universaltranslator/forge/ForgeConfig.java:300-304`、`:331-335`（`withPromptSettings` / `withDebugLog` 產生的物件本身是正確的）
  - 覆蓋來源：`translator-core/src/main/java/org/universaltranslator/core/provider/OnlineProviderConfig.java:426-445`（`preservePromptSettings`）、`:31-32`（`PROMPT_KEYS = {translation-quality, custom-system-prompt, debug-log}`）
  - 觸發端：`platforms/forge/shared/screens/1.18.2-1.21.11/src/main/java/org/universaltranslator/forge/UniversalTranslatorScreens.java:526-531`（Apply）與 `:634-646`（Save）；`platforms/forge/26.x/shared/common/src/main/java/org/universaltranslator/forge/UniversalTranslatorConfigScreenBase.java:526-543`；`platforms/forge/shared/screens/1.18.2-1.21.11/.../UniversalTranslatorScreens.java:1414-1416`（除錯開關）
- **受影響範圍**：全部 Forge 目標（1.16.5–26.2）。同一順序存在於 `platforms/forge/shared/legacy-common/.../LegacyConfig.java`（`defaults()` 同樣呼叫 `OnlineProviderConfig.applyDefaults`，`:451`）與兩份 `FabricConfig`，屬跨平台共通缺陷。
- **現象**：在「引擎」頁改「翻譯品質」或「自訂系統提示詞」，按 Apply 或 Save 後畫面顯示已儲存，本次遊戲內的翻譯行為也確實改變，但重開遊戲後回到原值。診斷畫面的「除錯模式」開關同樣顯示切換成功、日誌也開始寫，但重開後又變回關閉。設定檔裡 `translation-quality`、`custom-system-prompt`、`debug-log` 三行從頭到尾沒變。
- **觸發條件**：任何一次「修改這三個鍵 → `save()`」。100% 重現，不需要特殊設定或時序。
- **根因**：`ForgeConfig.toProperties()` 的執行順序是

  1. `onlineProviderConfig.writeTo(properties)`（`:459`）—— 寫出物件自己的**新**值；
  2. `OnlineProviderConfig.preservePromptSettings(configFile, properties)`（`:461`）—— 讀磁碟、把 `PROMPT_KEYS` 的**舊**值覆蓋回來（`OnlineProviderConfig.java:440-444`，條件是 `stored.containsKey(key)`）；
  3. `:462-501` 逐鍵 `setProperty` —— 其他 40 餘個鍵都有補寫，**只有這三個鍵沒有**。

  而 `save()`（`:342-353`）唯一的內容來源就是 `toProperties()`，所以寫回檔案的必然是磁碟舊值。`withPromptSettings`／`withDebugLog` 是在 `toProperties()` **之後**才 `applyPromptSettings`／`applyDebugLog`，所以新物件與執行期狀態都是對的（`ForgeTranslationRuntime.initialize(updated)` 因此真的生效），只有落地這一步被覆蓋。

  對照組可證明機制：`translation-workers`／`batch-size` 同樣只由 `writeTo` 提供，但**不在** `PROMPT_KEYS` 內，所以它們存得下來 —— 差別就只在那三行。

  使用者第一次啟動時檔案已含這三個鍵（`ForgeConfig.defaults()` 呼叫 `OnlineProviderConfig.applyDefaults`，其中 `putDefault(properties, "translation-quality", ...)`／`custom-system-prompt`／`debug-log` 都會寫入），所以從第一次存檔起就必然踩到。

- **影響**：三個使用者可見的設定永久無法持久化 —— 翻譯品質直接決定提示詞與 token 成本（`TranslationQuality`）、自訂提示詞是進階使用者的主要客製手段、除錯模式是回報問題的唯一開關。UI 明確回報「已儲存」而實際被靜默丟棄，屬靜默資料遺失。受影響的是 4 個平台的 4 份實作，本報告在 Forge 側立條。
- **修法**：讓 `preservePromptSettings` 只在「呼叫者沒打算改這三個鍵」的路徑上生效。

```java
// ForgeConfig：preserve 變成可選，只有陳舊快照那條路徑才回讀磁碟
private final boolean preservePromptKeys;   // 新增欄位

private ForgeConfig(Properties properties, Path configFile, Path cacheFile,
                    boolean preservePromptKeys) {
    /* ...既有賦值... */
    this.preservePromptKeys = preservePromptKeys;
}

private Properties toProperties() {
    Properties properties = new Properties();
    onlineProviderConfig.writeTo(properties);
    if (preservePromptKeys) {
        // 只有「畫面持有整份陳舊快照」的 withSettings 路徑才需要回讀，
        // 避免它把診斷面板剛改的值寫回去。
        OnlineProviderConfig.preservePromptSettings(configFile, properties);
    }
    properties.setProperty("config-version", "6");
    /* ...其餘不變... */
}

ForgeConfig withPromptSettings(TranslationQuality quality, String customSystemPrompt) {
    Properties properties = toProperties();
    OnlineProviderConfig.applyPromptSettings(properties, quality, customSystemPrompt);
    return new ForgeConfig(properties, configFile, cacheFile, false);  // 本物件擁有這三個鍵
}

ForgeConfig withDebugLog(boolean debugEnabled) {
    Properties properties = toProperties();
    OnlineProviderConfig.applyDebugLog(properties, debugEnabled);
    return new ForgeConfig(properties, configFile, cacheFile, false);
}

// withSettings / withHomeSettings / withEnabled / withTranslationTuning 維持 true
```

修完應補一個往返測試：`load()` → `withPromptSettings(HIGH, "x")` → `save()` → 重新 `load()` → 斷言 `translationQuality()` 仍是 `HIGH`。注意 `ForgeConfig` 是 package-private，測試必須放在 `org.universaltranslator.forge` 套件或改測 `OnlineProviderConfig` 的 `writeTo`+`preserve` 組合順序。

---

### BUG-S-015 · 背景執行緒直接寫非 volatile 畫面欄位

- **嚴重度**：小（實質為競態，見下註）｜ **信心**：中
- **位置**：
  - `platforms/forge/shared/screens/1.18.2-1.21.11/src/main/java/org/universaltranslator/forge/UniversalTranslatorScreens.java:142-146`（`testing` / `fetchingModels` / `modelPage` 非 volatile；`testStatus` / `testStatusIsError` / `fetchedModels` / `modelListOpen` 是 volatile）；worker 寫入在 `:1012`、`:1022`、`:1036`、`:1057`、`:1093`
  - 同檔 `:1568-1573`（`UniversalTranslatorLlmConfigScreenBase`：`fetching` 是 volatile，`modelPage` 不是）；worker 寫入在 `:1660`、`:1670`、`:1680`
  - `platforms/forge/26.x/shared/common/src/main/java/org/universaltranslator/forge/UniversalTranslatorConfigScreenBase.java:143-149`（同一組）、`:1122-1158`
  - `platforms/forge/modern/1.16.5/src/main/java/org/universaltranslator/forge/UniversalTranslatorScreens.java:140-146`、`:1577-1582`
  - `platforms/forge/shared/legacy-common/src/main/java/org/universaltranslator/forge/legacy/LegacyConfigScreen.java:199-205`；`LegacyLlmConfigScreen.java:35-40`
- **受影響範圍**：全部 Forge 目標（1.8.9–26.2）。同一結構在 5 份實作、6 個檔案裡完全一致。
- **現象**：按「取得模型清單」或「檢查」後，偶爾清單不彈出（狀態列卻已清空）、或按鈕從此完全沒反應；模型清單開啟時的頁碼停在上一輪的頁次。
- **觸發條件**：`fetchModels()` / `testConnection()` 的 worker 在 render thread 讀取這些欄位之前完成寫入，且期間經歷一次 JIT 提升或快取行競爭。遠端 endpoint 越慢、TLS 握手越久，交錯窗口越大。
- **根因**：`new Thread(...)`（`UniversalTranslatorScreens.java:1014`、`:1059`、`:1662`）在**非 render thread** 上寫 GUI 狀態，`if (fetchingModels) return;`（`:1007`）與 `if (testing) return;`（`:1050`）在 render thread 上讀同一個非 volatile 欄位，兩者之間沒有 happens-before 邊界（沒有 `synchronized`、沒有 volatile、沒有 `client.execute(...)`）。作者顯然知道這件事 —— 同一組欄位裡 `testStatus`、`fetchedModels`、`modelListOpen` 都標了 `volatile`，`testStatus`/`status` 的 javadoc 也說明它們跨執行緒 —— 但 `testing`、`fetchingModels`、`modelPage` 漏掉，`LegacyLlmConfigScreen` 的 `fetching` 有標而 `modelPage` 沒有。專案內有正確樣板可對照：`1.21/shared/1.21.9-1.21.11/.../UniversalTranslatorForgeClient.java:118-119` 用 `client.execute(() -> sendCompletedMessage(...))` 把結果排回 render thread。
- **影響**：守衛旗標一旦沒被觀察到復位，該畫面實例的「取得模型」與「檢查」就此永久失效（只能關掉畫面重開），使用者只看到按鈕沒反應。`modelPage` 未同步可能讓覆蓋層算出空頁。違反 Minecraft「GUI 只在 render thread 動」的硬規則，在 ARM 或更積極的 JIT 下機率放大。
- **修法**：worker 只做 I/O，結果一律排回 render thread；並把 `modelPage` 補成 volatile（或改成只在 render thread 寫，就不需要 volatile）。

```java
// UniversalTranslatorScreens#fetchModels（:1014 起）
Thread worker = new Thread(() -> {
    OpenAiModelCatalog.Catalog catalog = null;
    String failure = "";
    try {
        catalog = OpenAiModelCatalog.fetchCatalog(provider, endpointValue, keyValue);
    } catch (Exception error) {
        failure = describe(error);
    }
    final OpenAiModelCatalog.Catalog fetched = catalog;
    final String failedWith = failure;
    Minecraft client = this.minecraft;
    if (client == null) {
        return;
    }
    client.execute(() -> {          // 所有 GUI 狀態只在 render thread 寫
        fetchingModels = false;
        if (!failedWith.isEmpty()) {
            setTestStatus(tr("screen.universal_translator.llm.fetch_failed", failedWith), true);
            return;
        }
        if (!fetched.jsonBody()) {
            setTestStatus(tr("screen.universal_translator.engine.test_not_json"), true);
            return;
        }
        if (fetched.models().isEmpty()) {
            setTestStatus(tr("screen.universal_translator.llm.fetch_empty"), true);
            return;
        }
        fetchedModels = fetched.models();
        modelPage = 0;
        modelListOpen = true;
        setTestStatus("", false);
    });
}, "universal-translator-model-list");
```

`testConnection()`（`:1059`）、`UniversalTranslatorLlmConfigScreenBase#fetchModels`（`:1662`）、`26.x` 對應的兩處、`LegacyLlmConfigScreen`（用 `mc.addScheduledTask`）套同一改法。

> 註：依 §4 號段說明「競態務必寫在中BUG」，此條本應佔一個 `BUG-M` 名額；a05 只有 2 個名額，已讓給兩條跨版本功能全滅（BUG-M-011）與跨平台靜默資料遺失（BUG-M-012）。合併時若要恢復其中一條為 `BUG-M`，建議選本條。

---

### BUG-S-016 · HUD 指示器把自己的設定值送去翻譯

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - `platforms/forge/1.21/shared/common/src/main/java/org/universaltranslator/forge/mixin/InGameHudStatusIndicatorMixin.java:98-106`（外送預覽）、`:110-137`（`font.width` + `drawCenteredString`）
  - 同構副本：`platforms/forge/26.x/shared/api/26.2/.../mixin/InGameHudStatusIndicatorMixin.java:104-127`、`platforms/forge/26.x/shared/api/26.1-26.1.2/.../mixin/InGameHudStatusIndicatorMixin.java:105-127`、`platforms/forge/modern/1.20.1/.../mixin/InGameHudStatusIndicatorMixin.java:101-120`、`platforms/forge/modern/1.19.2/.../mixin/InGameHudStatusIndicatorMixin.java:105-124`、`platforms/forge/modern/1.18.2/.../mixin/InGameHudStatusIndicatorMixin.java:126-138`、`platforms/forge/modern/1.16.5/.../mixin/InGameHudStatusIndicatorMixin.java:122-137`
  - 正確對照：`platforms/forge/shared/legacy-common/src/main/java/org/universaltranslator/forge/legacy/LegacyClientEvents.java:304-311`、`:335-346`（`LegacyRenderContext.pushTextInput()` / `popTextInput()` 把整段包住）
- **受影響範圍**：Forge 1.16.5–26.2 全部目標。legacy（1.8.9／1.12.2）已正確隔離。
- **現象**：HUD 指示器的內容設成「目標語言」或「引擎」時，`zh-TW`、`deepseek`、`offline-llama` 這類純 ASCII 標籤會被當成待翻譯文字送出去並被換掉；外送翻譯的即時預覽（本身已是譯文）會被再翻一次。
- **觸發條件**：`hud-indicator=true`（預設 true，`ForgeConfig.java:99`）＋`hud-indicator-content` 設為 `language` / `provider` / `activity`。標籤是 ASCII 時 `LanguageHeuristics.shouldTranslateToTarget("zh-TW", true)` 判定為「需要翻譯」，必然進入翻譯管線；`translate-other` 預設也是 true（`ForgeConfig.java:89`）。設定成 `dot`（預設）時不觸發。
- **根因**：mixin 直接呼叫 `graphics.drawCenteredString(font, text, ...)` 與 `font.width(text)`，而這兩者正是本模組自己 hook 的入口（`DrawContextMixin` 的 `drawString`、`TextRendererMixin` 的 `width`），中間沒有 `TranslationRenderContext.pushTextInput()` 隔離。同一支 mixin 的 legacy 版本明文記載了這個危害並加了保護（`LegacyClientEvents.java:332-335`：「measuring or drawing the label unguarded would feed the indicator's own text back into the translation path」），Forge 這 7 份沒有照做。
- **影響**：① 使用者設定的值在畫面上被誤譯；② 每次切換語言／provider 都送一次無意義的 provider 請求，並把垃圾映射寫進 10 000 筆的 `PersistentTranslationCache`（`ForgeTranslationRuntime.java:116-118`），長期擠掉有用條目；③ `font.width` 也被 hook，譯文回填後標籤寬度突變、位置跳動。
- **修法**：

```java
// InGameHudStatusIndicatorMixin：把預覽與標籤兩段都包進 text-input 隔離
String outgoingPreview = OutgoingTranslationPreview.text();
if (outgoingPreview != null) {
    TranslationRenderContext.pushTextInput();
    try {
        graphics.drawCenteredString(
                Minecraft.getInstance().font,
                Component.literal(outgoingPreview),
                graphics.guiWidth() / 2,
                Math.max(0, graphics.guiHeight() - 60),
                0xFFFFFFFF);
    } finally {
        TranslationRenderContext.popTextInput();
    }
}

HudIndicatorContent content = settings.getContent();
if (content != HudIndicatorContent.DOT) {
    /* ...算出 label... */
    if (label != null && !label.isEmpty()) {
        Font font = Minecraft.getInstance().font;
        Component text = Component.literal(label);
        TranslationRenderContext.pushTextInput();
        try {
            int labelWidth = font.width(text);        // width 也被 hook，必須在隔離內
            graphics.drawCenteredString(font, text, labelCenterX, labelY, 0xFFFFFFFF);
        } finally {
            TranslationRenderContext.popTextInput();
        }
    }
}
```

7 份副本同一改法。

---

### BUG-S-017 · legacy HUD 指示器在每個 HUD 元素通道都重畫一次

- **嚴重度**：小（實質為每次渲染重工，見下註）｜ **信心**：高
- **位置**：`platforms/forge/shared/legacy-common/src/main/java/org/universaltranslator/forge/legacy/LegacyClientEvents.java:228-347`
  - 放棄判斷元素型別的註解：`:230-234`
  - 每次呼叫都新建：`:264`（`new ScaledResolution(client)`）
  - 重畫主體：`:283-298`（8 次 `Gui.drawRect`）、`:302-312`、`:316-346`
- **受影響範圍**：僅 Forge 1.8.9 / 1.12.2
- **現象**：每幀把同一個指示方塊、邊框、語言／引擎標籤與外送翻譯預覽重畫十幾次，而且每次都重做字串量測與 `ScaledResolution` 計算。
- **觸發條件**：`hud-indicator=true`（`LegacyConfig.java` 預設 true）且 HUD 顯示，每一幀。
- **根因**：1.8.9／1.12.2 的 `RenderGameOverlayEvent.Post` 是**逐元素**觸發（`ALL`、`HELMET`、`PORTAL`、`CROSSHAIRS`、`BOSSHEALTH`、`ARMOR`、`HEALTH`、`FOOD`、`AIR`、`HOTBAR`、`EXPERIENCE`、`CHAT`、`PLAYER_LIST`… 各一次）。`onRenderGameOverlay`（`:229`）完全不看 `event.type`／`getType()`，`:230-234` 的註解以「1.8.9 是 public 欄位、1.12.2 是 private + `getType()`，讀它不可移植」為理由放棄判斷，並主張「額外 drawRect 可忽略」—— 但被重複的不只 `drawRect`。
- **影響**：以 60 FPS、約 15 個元素通道估算，每秒約 7200 次即時模式 `drawRect`、約 1800 次 `getStringWidth`（7px 字串）與 1800 次 `drawStringWithShadow`，外加 900 次 `new ScaledResolution` 配置，全部冗餘（結果像素完全相同）。另外每份 `drawStringWithShadow` 都會再走一趟 `LegacyRenderedTextBridge.translate()`，把同一個字串送進翻譯快取查詢。絕對成本不大，但這是整棵 Forge 樹唯一在 HUD 熱路徑做 N 倍重工的地方。
- **修法**：一次性快取反射取得元素型別，只保留 `ALL` 一個通道。

```java
private static java.lang.reflect.Field typeField;
private static java.lang.reflect.Method typeGetter;
private static boolean typeLookupDone;

/** 每幀只畫一次：1.8.9 是 public 欄位、1.12.2 是 private + getType()。 */
private static Object elementType(RenderGameOverlayEvent event) {
    if (!typeLookupDone) {
        typeLookupDone = true;
        try {
            typeGetter = RenderGameOverlayEvent.class.getMethod("getType");
        } catch (NoSuchMethodException pre1_12) {
            try {
                typeField = RenderGameOverlayEvent.class.getField("type");
            } catch (NoSuchFieldException unexpected) {
                typeField = null;
            }
        } catch (RuntimeException unsupported) {
            typeGetter = null;
        }
    }
    try {
        return typeGetter != null ? typeGetter.invoke(event)
                : typeField != null ? typeField.get(event) : null;
    } catch (ReflectiveOperationException | RuntimeException unsupported) {
        return null;   // 取不到就退回舊行為，不要讓 HUD 消失
    }
}

@SubscribeEvent
public void onRenderGameOverlay(RenderGameOverlayEvent.Post event) {
    Object type = elementType(event);
    if (type != null && type != RenderGameOverlayEvent.ElementType.ALL) {
        return;        // 每幀只畫 ALL 這一趟
    }
    /* ...其餘不變... */
}
```

> 註：依 §2「明顯效能劣化（每次渲染都做重活）」可判為中；配額已滿，同 BUG-S-015 的說明。

---

### BUG-S-018 · 1.21.0–1.21.5 的 clientTick 沒有相位判斷，每個 tick 執行兩次

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`platforms/forge/1.21/shared/1.21-1.21.5/src/main/java/org/universaltranslator/forge/UniversalTranslatorForgeClient.java:79-104`（`:80` 收 `TickEvent.ClientTickEvent`，`:81` 直接執行本體）
- **受影響範圍**：僅 Forge 1.21、1.21.1、1.21.3、1.21.4、1.21.5
- **現象**：登入伺服器後的「翻譯已就緒」提示提早一半出現（1.5 秒而非 3 秒）；`TranslationRenderContext.beginFrame()` 每 tick 被呼叫兩次；`ForgeRuntimeStatusNotifier.tick()` 每 tick 執行兩次。
- **觸發條件**：任何時刻。Forge 的 `TickEvent.ClientTickEvent` 在每個客戶端 tick 以 `Phase.START` 與 `Phase.END` 各發一次，而這份 handler 沒有過濾相位。
- **根因**：這是唯一漏掉相位判斷的實作。同專案其他 7 份全部有判斷或有等價保護：`modern/1.16.5:62`、`modern/1.18.2:79`、`modern/1.19.2:81`、`modern/1.20.1:81`（`if (event.phase != TickEvent.Phase.END) return;`）、`shared/legacy-common/.../LegacyClientEvents.java:119-121`（同）、`1.21/shared/1.21.6-1.21.8`、`1.21.9-1.21.11`、`26.x`（改用 `TickEvent.ClientTickEvent.Post`，本身就只發一次）。1.21.0–1.21.5 這一份在 `.github/workflows/build.yml` 的 `forge-1.21.1` 目標裡建置，是唯一把 `ClientTickEvent` 原樣接下的版本。
- **影響**：`joinHintTicks` 由 60 降到 30 tick（提示時機錯誤，使用者可感知）；`beginFrame()` 與 `ForgeRuntimeStatusNotifier.tick()` 執行次數加倍（後者靠 `lastRuntimeStatus` 去重，不產生重複訊息）；`consumeClick()` 迴圈跑兩次但因 `consumeClick` 本身會排空佇列，不會造成重複開畫面。無資料損毀，屬可繞過的行為偏差。
- **修法**：與 1.20.1 對齊。

```java
@SubscribeEvent
public static void clientTick(TickEvent.ClientTickEvent event) {
    if (event.phase != TickEvent.Phase.END) {
        return;
    }
    TranslationRenderContext.beginFrame();
    /* ...其餘不變... */
}
```

---

### BUG-S-019 · `beginFrame()` 只清一半狀態，TextKind 一旦洩漏就永久錯位

- **嚴重度**：小 ｜ **信心**：高（機制）／中（實際觸發需例外或第三方取消）
- **位置**：
  - `platforms/forge/shared/common/src/main/java/org/universaltranslator/forge/TranslationRenderContext.java:62-69`（`beginFrame()` 只有 `TEXT_INPUT_DEPTH.remove()`）
  - 同檔 `:10-16`（`KINDS` 堆疊）、`:27-35`（`pop()`）、`:37-40`（`current()`）
  - 呼叫點：`1.21/shared/1.21-1.21.5/.../UniversalTranslatorForgeClient.java:81`、`1.21/shared/1.21.6-1.21.8/...:81`、`1.21/shared/1.21.9-1.21.11/...:84`、`26.x/shared/api/26.1-26.1.2/...:84`、`26.x/shared/api/26.2/...:84`、`modern/1.16.5/...:65`、`modern/1.18.2/...:82`、`modern/1.19.2/...:84`、`modern/1.20.1/...:84`
  - 唯一的 push/pop 來源：各版本的 `InGameHudContextMixin`、`ChatHudContextMixin`、`BossBarHudContextMixin`、`PlayerListHudContextMixin`（HEAD/RETURN 成對）
- **受影響範圍**：全部 Forge 目標（此檔為 `platforms/forge/shared/common`）
- **現象**：某次 HUD 渲染異常結束後，之後所有文字都被歸類成洩漏的那一種 `TextKind`，換畫面也不會恢復，只能重啟遊戲。若使用者設「不翻譯聊天、只翻譯其他」，此後畫面文字完全不翻譯。
- **觸發條件**：`Gui.render` / `ChatComponent.render` / `BossHealthOverlay.render` 在 `push(kind)` 之後、`pop()` 之前異常返回 —— 自身拋例外，或另一個 HUD 模組用 cancellable 的 `@At("HEAD")` 注入取消同一方法（`@At("RETURN")` 只覆蓋原方法的 return 指令，取消注入產生的 return 走不到我們的 pop）。
- **根因**：`beginFrame()` 的 javadoc（`:62-66`）明說用途是「discard any text-input depth left behind by a widget whose render method threw before its pop runs, so the guard cannot latch permanently」，但實作只處理 `TEXT_INPUT_DEPTH`，**完全沒碰 `KINDS`**。兩套狀態的殘留風險完全相同，防護只做了一半。`current()`（`:37-40`）在堆疊非空時回 `peek()`，殘留值會一直頂在最上層，直到下一次 `push` 才被暫時蓋住，`pop` 之後又露出來。同專案的 Fabric legacy 層有正確樣板：`platforms/fabric/shared/render-context-legacy/.../TranslationRenderContext.java:37-48` 的 `reset()` 同時清 `KINDS` 與 `TEXT_INPUT_DEPTH`。
- **影響**：`RenderKey` 內含 `TextKind`（`RenderTranslationSession`），分類一錯全部快取 miss；`ForgeConfig.allows(kind)`（`ForgeConfig.java:389-396`）的閘門套錯（洩漏成 `CHAT` 後，非聊天文字改受 `translate-chat` 控制）。屬整場 session 的功能失效但重開可繞過。
- **修法**：

```java
public static void beginFrame() {
    TEXT_INPUT_DEPTH.remove();
    // 未配對的 push 與 TEXT_INPUT_DEPTH 一樣會在 render 方法拋例外時殘留，
    // 而 peek() 會讓殘留值一直頂在最上層，因此兩者必須一起清。
    KINDS.remove();
}
```

9 個呼叫點都在 `ClientTickEvent` 開頭（渲染與 tick 不會交錯），直接 `remove()` 不會誤傷進行中的 push。

---

### BUG-S-020 · LegacyConfig 與 ForgeConfig 的四處漂移：設定版本號、越界值語意、預設鍵

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - `platforms/forge/shared/legacy-common/src/main/java/org/universaltranslator/forge/legacy/LegacyConfig.java:182`、`:422`、`:460` 寫入 `config-version=7`；`platforms/forge/shared/common/src/main/java/org/universaltranslator/forge/ForgeConfig.java:179`、`:415`、`:462` 寫 `6`；兩邊的遷移門檻都是 `configVersion(stored) < 6`（`LegacyConfig.java:176` / `ForgeConfig.java:173`）
  - `LegacyConfig.java:510-517`（`parseBoundedInt` 越界→**夾到邊界**，且沒有 null 判斷）vs `ForgeConfig.java:512-522`（越界→**回退預設值**，有 null 判斷）
  - `LegacyConfig.java:420-453`（`defaults()` 缺 `hud-indicator` 等 9 個鍵）vs `ForgeConfig.java:424-432`（有寫）
- **受影響範圍**：Forge 1.8.9／1.12.2，與其他平台共用同一個 `config/universal-translator.properties`（兩邊 `FILE_NAME` 都是 `universal-translator.properties`，`LegacyConfig.java` 與 `ForgeConfig.java:35`）
- **現象**：
  1. 用 1.8.9 存過一次設定後檔案裡是 `config-version=7`；之後用任何 1.16.5–26.2 的 Forge 版開啟並儲存，又被改回 `6`，兩個平台互相改寫。
  2. 手改設定檔成 `hud-indicator-size=99`：1.8.9 顯示最大尺寸，1.16+ 顯示預設 6 —— 同一份檔案、兩個結果。
  3. 1.8.9 首次啟動產生的預設檔沒有 `hud-indicator*` 這 9 行，其他平台產生的有。
- **觸發條件**：在同一個遊戲實例的 `config/` 上交替使用 legacy 與 modern 版（整合包、共用實例、或使用者直接搬舊設定檔），或手改設定檔。
- **根因**：`LegacyConfig` 是 `ForgeConfig` 的手抄副本，`config-version` 的 `7` 是全專案唯一（其餘三份實作 fabric legacy／fabric modern／forge modern 都是 `6`），而且**沒有任何 `>= 7` 的遷移分支** —— 版本號被抬高卻沒有對應的 schema 變化。`parseBoundedInt` 是同一支函式被寫成兩種語意，`defaults()` 也漏抄 9 個 HUD 鍵（功能上看不出來，因為建構子 `:127-146` 的 `getProperty` 預設值會補上，直到檔案被別的平台覆寫）。
- **影響**：目前無資料損毀（兩邊門檻都是 `<6`），但這是跨版本共用檔案的 schema 版本分歧：日後任何一方把門檻提到 `< 7`，讀到 `7` 的一方會靜默跳過遷移，留下 v6 形狀卻標成 v7 的檔案。越界值語意相反則讓同一個手改值在兩個平台得到不同結果。
- **修法**：`config-version` 三處改回 `6`（或真的實作 v6→v7 並同步其餘三份實作），`parseBoundedInt` 與 `defaults()` 對齊 `ForgeConfig`。

```java
// LegacyConfig.java:182 / :422 / :460
properties.setProperty("config-version", "6");

private static int parseBoundedInt(String raw, int fallback, int min, int max) {
    if (raw == null) {
        return fallback;
    }
    try {
        int parsed = Integer.parseInt(raw.trim());
        return parsed < min || parsed > max ? fallback : parsed;   // 與 ForgeConfig:512-522 一致
    } catch (NumberFormatException invalid) {
        return fallback;
    }
}

// defaults(): 補上 ForgeConfig.java:424-432 的 9 個 hud-indicator* 鍵
```

---

### BUG-S-021 · legacy 的 LLM 設定畫面沒有列進翻譯排除清單

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - `platforms/forge/shared/legacy-common/src/main/java/org/universaltranslator/forge/legacy/LegacyTranslationRuntime.java:184-185`（`translate` 只排除 `LegacyConfigScreen`、`LegacyDiagnosticsScreen`）
  - 同檔 `:328-329`（`translateLines` 同樣只有這兩個）
  - 正確對照：`platforms/forge/modern/shared/common/src/main/java/org/universaltranslator/forge/ForgeTranslationRuntime.java:202-206`（三個畫面都排除，含 `UniversalTranslatorLlmConfigScreen`）
- **受影響範圍**：僅 Forge 1.8.9 / 1.12.2
- **現象**：開著「LLM 設定」子畫面時，畫面標題、欄位提示與狀態列文字仍會被送去線上翻譯並被替換；同一玩家的「設定」與「診斷」兩個畫面完全不會。
- **觸發條件**：`enabled=true` 且從設定畫面點「LLM 設定」進入 `LegacyLlmConfigScreen`。
- **根因**：`LegacyLlmConfigScreen` 與另外兩個畫面在同一個 package、同一個 source set，卻沒被加進兩處排除鏈。modern 版的同一段程式碼列了三個畫面。
- **影響**：設定畫面開啟期間會為 UI 文案發出不必要的 provider 請求，且畫面文字在使用者正在輸入 endpoint／model 時被改寫，屬使用者可見的行為不一致。
- **修法**：

```java
// LegacyTranslationRuntime#translate（:184 之後）與 #translateLines（:328 之後）兩處都補
|| minecraft.currentScreen instanceof LegacyLlmConfigScreen
```

---

### BUG-S-022 · 診斷畫面每幀取兩次檔案大小並重建整頁字串

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - `platforms/forge/shared/screens/1.18.2-1.21.11/src/main/java/org/universaltranslator/forge/UniversalTranslatorScreens.java:1443`（`renderDiagnostics`）→ `:1452`（`displayLines()`）→ `:1491`（`ForgeTranslationRuntime.diagnostics()`）
  - `platforms/forge/modern/1.16.5/src/main/java/org/universaltranslator/forge/UniversalTranslatorScreens.java:1500`
  - `platforms/forge/26.x/shared/api/26.1-26.1.2/.../UniversalTranslatorDiagnosticsScreen.java:207`；`platforms/forge/26.x/shared/api/26.2/.../UniversalTranslatorDiagnosticsScreen.java:207`
  - `platforms/forge/shared/legacy-common/src/main/java/org/universaltranslator/forge/legacy/LegacyDiagnosticsScreen.java:217`
  - 熱點本體：`platforms/forge/shared/runtime-1.21.9-plus/.../ForgeTranslationRuntime.java:298-317`（`:305` 解析模型路徑、`:314-315` 兩次 `fileSize()`、`:319-325` 每呼叫兩次 `Files.isRegularFile` + `Files.size`）；`1.21/shared/runtime/1.21-1.21.8/...:294-313`；`modern/shared/common/...:294-313`；`shared/legacy-common/.../LegacyTranslationRuntime.java:283`
- **受影響範圍**：全部 Forge 目標
- **現象**：診斷畫面（尤其統計頁）開著時，翻譯吞吐略降；在有 NFS／雲端同步目錄的 `config/` 上，畫面會明顯卡頓。
- **觸發條件**：開著診斷畫面，並且當時有翻譯流量。
- **根因**：`renderDiagnostics` 每幀呼叫 `displayLines()` → `safeLines()` → `ForgeTranslationRuntime.diagnostics()`，該方法每次執行兩次檔案系統查詢（模型檔與快取檔各一次 `isRegularFile` + `size`），並組出新的 `TranslationDiagnosticsSnapshot`；接著 `snapshot.localizedLines(...)` 每幀重建整頁 `List<String>` 與 N 次 `String.format`。統計頁更重：`TranslationStats.global().localizedLines(...)` 必須先 `synchronized (lock)` 複製整個延遲取樣陣列與 providers map，而這個鎖同時是所有翻譯 worker 每筆計數要拿的鎖。
- **影響**：60 FPS 下每秒 120 次 `stat` 系統呼叫 + 每秒 60 次整頁字串重建（統計頁再加 60 次全量統計快照與 60 次全域鎖競爭，`translationWorkers()` 上限 16 時放大）。畫面關閉即消失，故不判為中。
- **修法**：把 `displayLines()` 的結果節流快取，渲染只讀快取。

```java
private List<String> cachedLines = Collections.emptyList();
private long linesRefreshedAt;

private List<String> displayLines() {
    long now = System.nanoTime();
    if (now - linesRefreshedAt < 250_000_000L && !cachedLines.isEmpty()) {
        return cachedLines;
    }
    linesRefreshedAt = now;
    try {
        cachedLines = statisticsPage
                ? TranslationStats.global().localizedLines(UniversalTranslatorDiagnosticsScreenBase::tr)
                : safeLines();
    } catch (RuntimeException ignored) {
        cachedLines = Collections.singletonList(
                tr("screen.universal_translator.diagnostics.unavailable"));
    }
    return cachedLines;
}
```

並在 `statisticsPage` 切換、`resetStatistics()` 與 `exportLog()` 之後把 `linesRefreshedAt` 歸零。5 處副本同一改法。

---

## 已排查但判定無問題

1. **三份 `ForgeTranslationRuntime` 的漂移只有一處 API 差異，不是邏輯漂移。** `shared/runtime-1.21.9-plus/.../ForgeTranslationRuntime.java:261`（`entry.getProfile().name()`）vs `1.21/shared/runtime/1.21-1.21.8/...:261`（`entry.getProfile().getName()`）—— 1.21.9 把 `GameProfile#getName` 改成 record accessor，屬必要差異。其餘 455 行（`initialize` 的 build-first/swap-last/close-in-background 三段、`SESSION_REAPER` 單執行緒 daemon、`playerNameSnapshot` 的 `AtomicBoolean` 單一刷新窗口與 `MAX_PROTECTED_PLAYER_NAMES` 上限、`applyHomeSettings` 的回滾）三份逐行相同；`modern/shared/common` 的 452 行只差 `isSettingsScreen()` 內聯成 `client.screen instanceof ...`（1.16.5–1.20.1 的 `screen` 是可存取的 public 欄位）。26.x 與 1.21.9+ 共用同一份，`26.x/shared.gradle:30-31` 的掛載正確。
2. **事件總線沒有重複註冊。** `LegacyClientEvents.INSTANCE` 是 `private static final`（`:33`），`initialize(File)` 是 `static synchronized` 且用 `registered` 旗標把 `ClientRegistry.registerKeyBinding` ×2 與 `MinecraftForge.EVENT_BUS.register(INSTANCE)`（`:48-56`）整段包住；唯一呼叫點是 `@Mod.EventHandler` 的 `preInitialize`（`UniversalTranslatorForgeMod.java:24`），FML 保證每個 mod 只呼叫一次。現代樹全部走 `@Mod.EventBusSubscriber` 的 static 巢狀類別（`ModEvents` 標 `bus = Bus.MOD`、`GameEvents` 走預設 bus），沒有 instance 與 static 混用，也沒有重複 `event.register(...)`：1.21.9+ 與 26.x 的 `RegisterKeyMappingsEvent.BUS.addListener` 只在 `registerDirectEvents()` 裡註冊一次，且 `ModEvents` 內已無 `registerKeys`（`1.21/shared/1.21.9-1.21.11/...:62-75`、`26.x/shared/api/26.2/...:62-75`），不會雙重註冊。
3. **專用伺服器不會因 client-only 類別崩潰。** Forge 的兩份 `META-INF/mods.toml`（`shared/common` 與 `modern/shared/common`）都宣告 `clientSideOnly=true`；NeoForge 雖然沒有這個欄位（`platforms/neoforge/**/neoforge.mods.toml` 全部沒有），但 `platforms/neoforge/shared.gradle:154-156` 明確 `java.exclude "**/UniversalTranslatorForgeMod.java"` 與 `"**/UniversalTranslatorForgeClient.java"`，NeoForge 用自己的 entrypoint 層。因此 `26.x/shared/common/.../UniversalTranslatorForgeMod.java:13` 與 `1.21/shared/mod/1.21.9-1.21.11/...:13` 在 `@Mod` 建構子直接呼叫 client-only 的 `registerDirectEvents()` 雖然沒有 `FMLEnvironment.dist` 判斷，在兩條載入路徑上都不會被執行到。legacy 側的 coremod（`LegacyCorePlugin`／`LegacyCorePluginBase`／`FontRendererTransformer`）也不依賴任何 client 型別於方法簽章或 supertype，且有 `@IFMLLoadingPlugin.TransformerExclusions("org.universaltranslator.forge.legacy")` 防載入遞迴。
4. **反射都已快取，沒有每次渲染重建。** `ForgeLocalTextGuard`（`shared/local-guard-1.18.2-plus/...:13-14`、`:32-59`）用 `volatile Field chatField` + `volatile boolean searched` 雙重檢查鎖，且刻意「只在找到時才 latch」，找不到不會污染快取；每次呼叫只剩一次 `Field.get`。`1.16.5` 的同名副本（`:13-14`）與 26.2 的副本結構相同。`FontRendererTransformer` 只在類別載入時執行一次。整棵樹沒有 `Class.forName` 在渲染路徑上。
5. **mixin 設定清單與實際類別一一對應，`MixinConfigs` 也有掛。** `1.21/shared/common` 的 11 個條目、`26.x/shared/common` 的 9 個條目（缺 `ChatHudContextMixin`／`PlayerListHudContextMixin`，與 AGENTS.md 記載的分層一致）、`1.16.5`／`1.18.2`／`1.19.2` 的 10 個條目都對得上各樹內的實際檔案，沒有「寫了但檔案不存在」或「檔案存在但沒註冊」。`platforms/forge/shared.gradle:98` 與 `modern/shared.gradle:183` 的 manifest 都帶 `attributes "MixinConfigs": "universal-translator.forge.mixins.json"`（1.16.5–1.20.1 需要這個屬性才生效），`modern/shared.gradle:145-147` 也設了 `renamer.enableMixinRefmaps`。`@Mod.EventBusSubscriber` 的 `bus` 參數全部正確（`ModEvents` = `Bus.MOD`，`GameEvents` = 預設 bus 收 `TickEvent`／`ClientChatEvent`）。
6. **`ForgeConfig.load()` 被高頻呼叫是安全的。** `openSettings`／`toggle`／`openFromTitle` 每次都會 `ForgeConfig.load(...)`，但 `TranslationStats.attach`（`translator-core/.../TranslationStats.java:107-112`）在 `file != null` 時直接 return、`DebugLog.configure`（`DebugLog.java:100-110`）不持有檔案句柄、每次 append 都開關檔（`:269-288`），所以不會重置統計、不會洩漏 fd、也不會重複寫 header。`save()` 走 `createFile` + `restrictToOwner` + `ATOMIC_MOVE`（`ForgeConfig.java:343-359`），失敗時 `finally` 清掉暫存檔（`:360-366`），不會留下半寫的 `.tmp`。
7. **`mcmod.info` 的中文作者名不是亂碼。** `platforms/forge/legacy/1.8.9` 與 `1.12.2` 的 `mcmod.info` 以 UTF-8 嚴格解碼驗證通過（`authorList: ["我小张7272635"]`），PowerShell 主控台顯示成 `?撘?` 只是終端編碼問題。兩個獨立建置的 `build.gradle` 也都設了 `filteringCharset = "UTF-8"`。
8. **`26.x` 的 `ForgeLocalTextGuard` 獨立副本是刻意的分層，不是漏掛。** `26.x/shared.gradle:25-28` 只在 `api/26.1-26.1.2` 掛共用層，26.2 用自己的副本；兩者逐行 diff 只差 `client.screen`→`client.gui.screen()` 三處（`26.2/.../ForgeLocalTextGuard.java:20,24,28`）。`ForgeTranslationRuntime` 則是三個 26.x 版本共用 `runtime-1.21.9-plus`（`:30-31`），比對過該 runtime 對 `ForgeLocalTextGuard` 的兩個呼叫（`currentScreen`、`isLocalChatInput(Minecraft, String)`）在兩份副本都存在。

---

## 附錄：配額外已確認（不佔號段，供 Lead 決定是否併入其他代理）

以下 7 條已逐一 `read` 驗證、行號對得上，但超出 a05 的 10 條配額，未立正式條目：

| 位置 | 問題 |
| --- | --- |
| `1.21/shared/1.21.9-1.21.11/.../UniversalTranslatorConfigScreen.java:54-71` | LLM 畫面只覆寫 `render` 與 `mouseClicked`，沒有 `keyPressed`／`charTyped`，因此 `shared/screens/.../UniversalTranslatorScreens.java:1776` 的 `handleModelListKey`（該處 javadoc 明說「1.21.9 的簽名也變了，所以由各版本 bridge 呼叫」）與 `:1787` 的 `isModelListOpen` 在 1.21.9–1.21.11 從未被呼叫 → 模型清單開著時按 ESC 會關掉整個畫面並丟棄已輸入的 endpoint／model／key。1.18.2–1.21.8 的 bridge（`modern/1.20.1/...:96-104`、`1.21/shared/runtime/1.21-1.21.8/...:96-104`）四個方法都在。26.x 走第三種寫法（`26.x/shared/common/.../UniversalTranslatorConfigScreenBase.java:839-842` 在 base 內攔 click），但同樣沒有鍵盤處理。 |
| `shared/runtime-1.21.9-plus/.../ForgeTranslationRuntime.java:208-219`（另 `1.21/shared/runtime/1.21-1.21.8/...:208-219`、`modern/shared/common/...:204-215`、`shared/legacy-common/.../LegacyTranslationRuntime.java:193-202`） | `shutdown()` 清了 `session`／`activeProvider`／`protectedPlayerNames`／`RECENT_USER_TEXT`／`outgoingTail`，但**沒有**清 `activeConfig` 與 `cachedHome`，而 `initialize()` 兩者都設。`loadConfig()` 失敗後走 `shutdown()`（`1.21.9-1.21.11/.../UniversalTranslatorForgeClient.java:57-59`），此後 `homeSettings()` 仍回報 `enabled=true`、`diagnostics()` 仍顯示舊 provider 與舊快取大小而不是「尚未载入设置」。 |
| `shared/local-guard-1.18.2-plus/.../ForgeLocalTextGuard.java:61-75`（另 `26.2/...:61-75`、`modern/1.16.5/...:61-75`） | `findTextField` 在 `field.setAccessible(true)` 拋 `RuntimeException` 時直接 `return null`，而不是繼續掃描父類或下一個候選欄位 —— 單一欄位的權限失敗會讓整個「本地輸入保護」失效一整個 session。 |
| `shared/runtime-1.21.9-plus/.../ForgeTranslationRuntime.java:327-336`（另兩份 runtime、`LegacyTranslationRuntime.java:323-334`） | `translateLinesForRender` 只有 2 道閘門（`allows(kind)`、`isTextInput()`），缺少 `translateForRender` 的另外 3 道（settings 畫面、non-vanilla 畫面、`RECENT_USER_TEXT`）。工具提示走這條路徑（`shared/rendered-1.19.2-plus/...:119-120`），所以在模組自己的設定畫面裡仍會翻譯，且 `translate-vanilla=false` 對工具提示無效。 |
| `modern/1.16.5/src/main/resources/universal-translator.forge.mixins.json:19-21`、`modern/1.18.2/...:19-21`、`modern/1.19.2/...:19-21` | `"defaultRequire": 0`，而 `1.20.1` 與 `1.21+` 是 `1`。1.16.5／1.18.2／1.19.2 的注入目標一但對不上就完全靜默（日誌乾淨、翻譯入口消失），而這三版的描述子最複雜。1.16.5 的 `InGameHudContextMixin.java`、`ScreenMixin.java`、`TitleScreenMixin.java` 還各自再寫 `require = 0`，等於雙重放行。 |
| `shared/rendered-1.19.2-plus/.../RenderedTextBridge.java:104-109`、`:137-140`（另 `modern/1.18.2/...:160-165`、`:193-196`）；`shared/legacy-common/.../FontRendererTransformer.java:47-56`、`LegacyRenderedTextBridge.java:44-46`、`:71-74` | 用 `System.out.println` 寫死除錯字串（`Item tooltip producer reached`／`Installed N hook(s)`），不受 `debug-log` 開關控制、沒有等級、沒走 mod logger；`LegacyRenderedTextBridge` 還為了這兩行訊息保留兩個只在此處使用的 `AtomicBoolean`。 |
| `shared/rendered-1.19.2-plus/.../RenderedTextBridge.java:25-32`（另 `modern/1.18.2/...:28-35`、`modern/1.16.5/...:23-30`） | `String translated = translateRaw(text);` 在 `if (text == null || ...)` 之前 —— null 檢查永遠無效。目前不 NPE 只因 `RenderTranslationSession` 內部擋住，橋接層自身的防護是死碼；一旦前置判斷改動就會變成真 NPE。 |
