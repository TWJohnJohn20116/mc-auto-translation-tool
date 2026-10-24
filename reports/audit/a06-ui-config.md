# a06-ui-config 審計報告

範圍：所有手寫 UI 與設定畫面（無框架）

- 巨型設定畫面：`platforms/forge/shared/screens/1.18.2-1.21.11/`（1818 行）、`platforms/forge/modern/1.16.5/`（1816 行）、`platforms/forge/shared/legacy-common/LegacyConfigScreen`（1518 行）、`platforms/forge/26.x/shared/common/UniversalTranslatorConfigScreenBase`（1410 行）、`platforms/fabric/1.13/shared/screens/`（1510 行）、`platforms/fabric/1.0-1.12/shared/screens/`（1492 行）、`platforms/fabric/shared/screens-shared/`（1441 行）、`platforms/fabric/26.x/shared/api/26.1-26.3/`（1382 行）
- LLM 設定與診斷畫面：各版本 `UniversalTranslatorLlmConfigScreen`、`UniversalTranslatorDiagnosticsScreen`、`LegacyDiagnosticsScreen`
- 共用 UI 元件與設定模型（`translator-core`）：`SettingsScreenLayout`、`SettingsSelectionList`、`SettingsUiAnimation`、`UiLimits`、`UiTextKeys`、`UiTranslator`、`HudIndicator*`、`HomeQuickSettingsState`、`TranslationStatusLocalizer`、`TranslationBlocklist`、`TranslationProviderCatalog`
- 語言檔：`platforms/shared/lang/json/assets/universal_translator/lang/{en_us,zh_cn,zh_tw}.json`

配額：`BUG-M-013` ~ `BUG-M-015`（有才寫，最多 3 條）、`BUG-S-023` ~ `BUG-S-030`（小 BUG 8 條）

產出：**BUG-C 1（號段外，見下）** / BUG-M 3 / BUG-S 8

> **號段說明**：依 `00-規則與號段.md` §4，a06 沒有 BUG-C 配額（該欄為 `—`）。本報告第一條判定為「嚴重」，但依規則不得自佔 C 號，故以 `BUG-C-〔待 Lead 配號〕` 標記，請 Lead 統一配號並與 a01（`OnlineProviderConfig` 擁有者）、a03／a04（Fabric `settings` 層）、a05（Forge `ForgeConfig`／`LegacyConfig`）去重——根因橫跨這些範圍，合併時極可能重複命中。
>
> 任務書給的行數（1679/1678/1409/1399/1383/1302/1255）與實際不符，本報告全部使用實際行號。

---

## BUG-C

### BUG-C-〔待 Lead 配號〕 · 修正存檔鏈覆蓋，讓提示詞／品質／除錯設定真的寫進檔案

- **嚴重度**：嚴重 ｜ **信心**：高
- **位置**：
  - 覆蓋來源：`translator-core/src/main/java/org/universaltranslator/core/provider/OnlineProviderConfig.java:31-32`（`PROMPT_KEYS = {"translation-quality", "custom-system-prompt", "debug-log"}`）、`:426-445`（`preservePromptSettings` 從磁碟把三鍵蓋回 `target`）
  - 觸發點 1（`toProperties()` 每次都呼叫覆蓋）：`platforms/forge/shared/common/src/main/java/org/universaltranslator/forge/ForgeConfig.java:457-461`、`platforms/fabric/shared/settings/modern/src/main/java/org/universaltranslator/fabric/FabricConfig.java:457-461`、`platforms/fabric/shared/settings/legacy/src/main/java/org/universaltranslator/fabric/FabricConfig.java`（同構）、`platforms/forge/shared/legacy-common/src/main/java/org/universaltranslator/forge/legacy/LegacyConfig.java:455-459`
  - 觸發點 2（`save()` 又跑一次）：`ForgeConfig.java:342-352`（`:351 toProperties().store(writer, …)`）、`FabricConfig.java:342`（同構）
  - 觸發點 3（畫面 Save 鏈在存檔前就把新值洗掉）：`UniversalTranslatorScreens.java:621-646`（1.18.2–1.21.11，`.withPromptSettings(...)` 在 `:634-635`、`.withTranslationTuning(...)` 在 `:636`、`updated.save()` 在 `:646`）、`UniversalTranslatorScreens.java:611-653`（1.16.5，對應 `:641-643`／`:653`）、`LegacyConfigScreen.java:947-987`（`:975-977` 為 prompt 鏈、`:987` 為 save）
  - Apply／Debug 路徑：`UniversalTranslatorScreens.java:520-537`（`Apply prompt`，`:526` + `:531`）、`:1414-1416`（debug 開關）、`LegacyConfigScreen.java:752-769`、`:1376-1384`
- **受影響範圍**：**全部平台全部版本**（Forge 1.8.9–26.x、Fabric 1.0–26.x、NeoForge 經 Forge 層）。三項設定：`custom-system-prompt`（自訂提示詞）、`translation-quality`（品質模式）、`debug-log`（除錯開關）。
- **現象**：
  1. ENGINE 分頁輸入自訂提示詞 → 按 `Apply prompt` → 畫面顯示「Settings saved」，當下這次遊戲確實生效；**重開遊戲後提示詞回到原值**（若原本是空的就是整個消失）。
  2. 切換 `Quality: Fast/Standard/High` → 按 Save → 畫面關閉、其他設定都存了，**品質模式沒存**，下次開遊戲仍是舊值。
  3. 診斷畫面切換 `Debug mode: On` → 訊息顯示「Debug mode: On」→ **重開遊戲回到 Off**，`debug-log` 從未寫入檔案。
  4. 直接按 Save（不按 Apply）時，ENGINE 分頁上的提示詞與品質連「當下生效」都不完整：`updated.save()` 寫出的檔案裡是舊值，而 `ForgeTranslationRuntime.initialize(updated)` 用的是新值——**執行期與檔案的內容不一致**，直到下次啟動才發現。
- **觸發條件**：任何一次透過 GUI 修改這三項並存檔。無需特殊時序或版本，**100% 可重現**；唯一「有效」的寫入方式是手動編輯 `universal-translator.properties`。
- **根因**：`toProperties()` 的兩行順序決定了這件事——先 `onlineProviderConfig.writeTo(properties)`（寫出**當前記憶體**值），再 `OnlineProviderConfig.preservePromptSettings(configFile, properties)`（從**磁碟**把 `PROMPT_KEYS` 三鍵**覆蓋**回舊值）。設計意圖是「保護診斷畫面在設定畫面開著時做的修改不被舊快照蓋掉」，但它沒有區分「我這次就是要寫這三鍵」與「我沒碰這三鍵」。

  實際執行的鏈（以 Forge 1.18.2 的 Save 為例）：

  ```
  original.withSettings(...)                  → toProperties(): writeTo(舊值) + preserve(磁碟舊值)   → 舊值
          .withPromptSettings(quality, 新prompt) → toProperties(): writeTo(舊值) + preserve(磁碟舊值)
                                                 → applyPromptSettings(...)                        → 新值 ✅
          .withTranslationTuning(workers, batch) → toProperties(): writeTo(新值) + preserve(磁碟舊值)  → 舊值 ❌
  updated.save()                              → toProperties(): writeTo(舊值) + preserve(磁碟舊值)   → 舊值 ❌ → 寫檔為舊值
  ```

  關鍵在 `:636` 的 `.withTranslationTuning(...)` 與 `:646` 的 `save()`：**兩者各自重建一次 `Properties`，各自呼叫一次 `preservePromptSettings`**，把 `withPromptSettings` 剛剛放進去的新值洗掉。`ForgeConfig.withPromptSettings` 自己的註解（`:296-299`「round-trip through `toProperties()`」）說明了作者認為這裡是安全的往返，但沒意識到往返的終點是磁碟舊值。

  `Apply prompt`（`:520-537`）走的是同一條路：`original.withPromptSettings(...)` → `initialize(updated)`（記憶體新值，畫面正確）→ `updated.save()`（`:531`，寫回舊值）。`withDebugLog`（`:331-335`）亦同。
- **影響**：三項設定**永久無法透過 GUI 保存**，而且是靜默的——UI 回報成功、當下行為也正確，只有重啟才暴露。`custom-system-prompt` 是這個模組最有價值的進階功能之一（自訂翻譯風格／術語表），使用者會反覆調整十幾次才發現每次都白做。屬於 §2「資料遺失」級別：使用者明確輸入的內容被丟棄且無任何提示，也沒有 UI 端的 workaround。
- **修法**：讓「這次要寫 prompt 鍵」的需求可以被表達，而不是靠 `toProperties()` 的副作用。

```java
// OnlineProviderConfig：把覆蓋改成「只補沒被本次擁有者提供的鍵」
public static void preservePromptSettings(Path configFile, Properties target) {
    preservePromptSettings(configFile, target, Collections.<String>emptySet());
}

/**
 * @param ownedKeys 本次寫入由呼叫端負責的鍵，不得被磁碟舊值覆蓋
 */
public static void preservePromptSettings(
        Path configFile, Properties target, Set<String> ownedKeys) {
    if (configFile == null || target == null) {
        return;
    }
    Properties stored = new Properties();
    try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
        stored.load(reader);
    } catch (IOException | RuntimeException unreadable) {
        return;
    }
    for (String key : PROMPT_KEYS) {
        if (ownedKeys.contains(key)) {
            continue;                       // 本次由呼叫端提供，保留它的值
        }
        if (stored.containsKey(key)) {
            target.setProperty(key, stored.getProperty(key, ""));
        }
    }
}

// ForgeConfig / FabricConfig / LegacyConfig：物件記住「這三鍵歸我管」
private final boolean ownsPromptKeys;

private ForgeConfig(Properties properties, Path configFile, Path cacheFile) {
    this(properties, configFile, cacheFile, false);
}

private Properties toProperties() {
    Properties properties = new Properties();
    onlineProviderConfig.writeTo(properties);
    OnlineProviderConfig.preservePromptSettings(configFile, properties,
            ownsPromptKeys ? PROMPT_KEYS_SET : Collections.<String>emptySet());
    …
}

// withPromptSettings / withDebugLog / withTranslationTuning 產生的物件一律 ownsPromptKeys = true
ForgeConfig withPromptSettings(TranslationQuality quality, String customSystemPrompt) {
    Properties properties = toProperties();
    OnlineProviderConfig.applyPromptSettings(properties, quality, customSystemPrompt);
    return new ForgeConfig(properties, configFile, cacheFile, /* ownsPromptKeys */ true);
}
```

同一個修正要套到 `FabricConfig`（modern／legacy 兩份）與 `LegacyConfig`，四份 `toProperties()` 一起改。**回歸驗證方式**：改完之後 `applyPromptSettings()` → 重新 `load()` → `customSystemPrompt()` 必須等於剛剛輸入的值（建議在 `ProviderSelfTest` 補一條「write → load → 值保留」的往返測試，現有測試 `ProviderSelfTest.java:1958/2094/2436` 只測 `applyPromptSettings` 本身，沒有測經過 `save()`）。

---

## BUG-M

### BUG-M-013 · 讓選取清單吃掉整個點擊與 ESC，別再誤觸 Save／Cancel

- **嚴重度**：中 ｜ **信心**：高
- **位置**（`selectFromList` 在 panel 外回 `false` → 呼叫端 `||` 串到 `super.mouseClicked`）：
  - Forge：`platforms/forge/shared/screens/1.18.2-1.21.11/src/main/java/org/universaltranslator/forge/UniversalTranslatorScreens.java:758-790`（`:785-788` 回 `false`）；呼叫端 `platforms/forge/modern/shared/screens/1.18.2-1.19.2/…/UniversalTranslatorConfigScreen.java:25`、`platforms/forge/modern/1.16.5/…/UniversalTranslatorConfigScreen.java:26`、`platforms/forge/modern/1.20.1/…:24`、`platforms/forge/1.21/shared/runtime/1.21-1.21.8/…:24`、`platforms/forge/1.21/shared/1.21.9-1.21.11/…:25`；`platforms/forge/modern/1.16.5/…/UniversalTranslatorScreens.java:764`（同構副本）
  - Fabric 1.14–1.21.11：`platforms/fabric/shared/screens-shared/src/client/java/org/universaltranslator/fabric/UniversalTranslatorSettingsScreen.java:865-874`（`:872-873`）＋`:1058-1061`（`:1060` 回 `false`）；呼叫端 `1.14-1.15/…/UniversalTranslatorConfigScreenBase.java:44`、`1.19/shared/screen-base/1.19-1.19.2/…:37`、`1.19.3-1.19.4/…:38`、`1.21/shared/input/pre-1.21.9/…:34`、`1.21/shared/input/1.21.9-plus/…:35`、`legacy/shared/screens/1.16-1.18/…:45`
  - Fabric 26.x：`platforms/fabric/26.x/shared/api/26.1-26.3/src/client/java/org/universaltranslator/fabric/UniversalTranslatorConfigScreen.java:827-840`（`:835-836` 回 `false` 才走到 `:839`）、`:1018-1041`
  - Fabric 1.0–1.12：`platforms/fabric/1.0-1.12/shared/screens/src/main/java/org/universaltranslator/fabric/UniversalTranslatorConfigScreen.java`（`mouseClicked` 內 `if (openSelection != NONE && selectFromList(...)) return;`，`selectFromList` 在 `:1198`）
  - **正確對照組（唯一寫對的）**：`platforms/fabric/1.13/shared/screens/src/main/java/org/universaltranslator/fabric/UniversalTranslatorConfigScreen.java:1062-1065`（`selectFromList(mouseX, mouseY); return true;`）
  - **鍵盤同源問題**：`1.13/…:1025-1039`、`1.0-1.12/…:1029-1043` 的 `keyPressed` 只轉發給三個文字框就 `super.keyPressed(...)`；`screens-shared` 與 `26.x` 兩份**完全沒有 `keyPressed` 覆寫**
- **受影響範圍**：**除 Fabric 1.13 以外全部版本**（Forge 1.16.5–26.x、Fabric 1.0–1.12、1.14–1.21.11、26.x）。
- **現象**：
  1. 語言清單／服務清單開著時，點清單外的暗色遮罩想「取消」，清單關閉的**同時**底下那顆按鈕也被按下：點到 Save → 直接存檔並離開；點到 Cancel → 這次所有未存的改動全被丟掉；點到某個切換按鈕 → 順手改掉一個沒打算改的設定。
  2. 清單開著時按 ESC → 關掉的是**整個設定畫面**（連帶丟棄全部未存變更），而不是關清單；清單明明畫了專屬的 `Back` 熱區（`1.13/…:1432`、`26.x/…:1275`、`screens-shared/…:1251`），鍵盤卻沒有對應行為。
- **觸發條件**：320×240 邏輯解析度（Minecraft 常見最小值）下 General 分頁開 `Target: …`：10 個語言 preset 讓 `SettingsSelectionList.layout` 算出的 panel 只到 `panelBottom ≈ 154`（`SettingsSelectionList.java:44-52`），而 Save/Cancel 在 `y = 216..236` → **任何 `y > 154` 的點擊都會穿透**到 Save/Cancel。服務清單（20 筆）panel 只到約 204，右下角仍漏出 Save 列。ESC 路徑在任何解析度都會踩到，只要清單是開著的。
- **根因**：覆蓋層只有「被 `mouseClicked` 查詢」這一種存在形式，不具模態性。`selectFromList` 把「panel 外 → 關閉清單」實作成 `return false`（`screens-shared:1058-1061`、`26.x:1038-1041`、`forge F1:785-788`），呼叫端又寫成 `if (openSelection != NONE && selectFromList(...)) return true;`（`26.x:835-838`、`1.0-1.12`）或 `… || super.mouseClicked(…)`（Forge 與 screens-shared 的 6 個 base），於是「已消費的點擊」被回報成「未消費」，事件繼續往下派送。`keyPressed` 則完全不知道覆蓋層存在。1.13 那份把回傳值丟掉、無條件 `return true`（`:1062-1065`），所以行為正確——同一份 UI 出現兩種行為，正是「手抄多份」的典型代價。
- **影響**：一次點擊做兩件事。最惡劣的是 Cancel：使用者在設定頁改了十幾項，想按空白處收起清單，結果整批改動被無提示丟棄；ESC 那條同理。這不是「罕見路徑」，而是清單開著時最自然的兩個操作（點空白處、按 ESC）都會中。
- **修法**：覆蓋層開著時一律吞掉點擊與 ESC；關閉清單只發生在覆蓋層自己的處理裡。

```java
// screens-shared:865-874
protected boolean handleSelectionClick(double mouseX, double mouseY) {
    if (activeTab == Tab.ENGINE && modelListOpen) {
        handleModelListClick(mouseX, mouseY);
        return true;
    }
    if (openSelection == SettingsSelectionList.Kind.NONE) {
        return false;
    }
    selectFromList(mouseX, mouseY);   // 只負責選取／關閉，不再把點擊往外傳
    return true;
}

// 26.x:835-838 與 1.0-1.12 的 mouseClicked 同義改寫
if (openSelection != SettingsSelectionList.Kind.NONE) {
    selectFromList(event.x(), event.y());
    return true;
}

// 新增 keyPressed（screens-shared 與 26.x 目前完全沒有）
@Override
public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (keyCode == 256) {                                  // GLFW_KEY_ESCAPE
        if (modelListOpen) {
            modelListOpen = false;
            return true;
        }
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            openSelection = SettingsSelectionList.Kind.NONE;
            return true;
        }
    }
    return super.keyPressed(keyCode, scanCode, modifiers);
}
```

Forge 那份（`handleSelectionClick` 回 `false`）與 `1.16.5` 的同構副本同步改成「清單開著就回 `true`」；8 個 base 的 `|| super.mouseClicked(…)` 鏈不必動。

---

### BUG-M-014 · 讓「檢查設定」驗證畫面當前選的服務，不是開畫面時的舊服務

- **嚴重度**：中 ｜ **信心**：高
- **位置**：`platforms/forge/shared/screens/1.18.2-1.21.11/src/main/java/org/universaltranslator/forge/UniversalTranslatorScreens.java:1084-1088`（`original.validateProviderConfiguration()` 在 `:1085`）、`:1060-1094`（整個 `testEndpoint` 執行緒）；同構副本 `platforms/forge/modern/1.16.5/…/UniversalTranslatorScreens.java:921-925`、`platforms/forge/shared/legacy-common/…/LegacyConfigScreen.java:1382-1386`、`platforms/forge/26.x/shared/common/…/UniversalTranslatorConfigScreenBase.java`（同段）、Fabric 各份 `setTestStatus` 對應區塊（`screens-shared`／`26.x`／`1.13`／`1.0-1.12`）
- **受影響範圍**：全部有「檢查設定／測試連線」按鈕的設定畫面，全部平台。
- **現象**：在 ENGINE 分頁把 `Service` 從 A 切到 B，還沒存檔就按「檢查設定」：
  - 若 B 需要 API Key／端點（走 `else` 分支），驗的是 **A** 的設定，卻回報成 B 的結果——A 沒填金鑰會被報成「B 設定有問題」，A 填好了則被報成「B 可以使用」。
  - 只有 LibreTranslate（`isLibreTranslate()`，用畫面上的 `endpoint`）與 LLM 家族（用畫面上的 `llmEndpoint`）兩個分支是用畫面現值；其餘 20 家走 `original.validateProviderConfiguration()`。
- **觸發條件**：開 ENGINE 分頁 → 切換 `Service` 到任一「非 LibreTranslate、非 LLM editor」的服務（例如 Baidu / Tencent TMT / Youdao / DeepL / Gemini / Claude）→ 直接按「檢查設定」，不先存檔。或在切換前該服務已填好金鑰、切換後想確認新服務，結果拿到舊服務的結論。
- **根因**：`:1085` 呼叫的是 `original.validateProviderConfiguration()`，而 `original` 是畫面開啟時載入的唯讀快照；畫面上使用者改的是 `provider` / `tencentSecretId` 等欄位，尚未進入任何 `Config` 物件（`:1084` 的 `else` 分支位於 `if (isLibreTranslate()) {…} else {…}` 內，`else` 沒有像另外兩個分支那樣改用畫面值）。`ForgeConfig` 雖然有 `withSettings(...)` 可以組出「當前畫面值」的物件，但這裡沒用它。
- **影響**：這個按鈕存在的唯一目的就是「存檔前告訴我這樣設定行不行」，而它在最需要它的情況（剛切換服務、還沒存）給出的是**另一個服務**的答案。使用者可能因此把一組可用的設定改壞，或誤以為新服務可用而存檔後在遊戲中失敗。
- **修法**：先用畫面現值組出暫存 `Config` 再驗（不需要存檔）。

```java
// :1084-1088
} else {
    // 驗證畫面當前這一組值，而不是開畫面時的舊快照。
    original.withSettings(enabled, translateChat, translateOther, translateVanilla,
                    translateOutgoing, translatePlayerNames, blockedKeywords.getValue(),
                    targetLanguage, outgoingTargetLanguage, displayMode, translateEnglishOnly,
                    translatedTextColor, provider, endpoint.getValue(), llmEndpoint, llmApiKey,
                    llmModel, offlineAutoDownload, offlineModel, apiFallback, diskCache, animatedUi,
                    new HudIndicatorSettings(hudIndicator, hudIndicatorCorner, hudIndicatorSize,
                            hudIndicatorMargin, hudIndicatorColor, hudIndicatorContent,
                            hudIndicatorVisibility, hudIndicatorOffsetX, hudIndicatorOffsetY))
            .validateProviderConfiguration();
    message = tr("screen.universal_translator.engine.test_ok_config");
    error = false;
}
```

若不想複製那 20 幾個參數，可在 `ForgeConfig` 加一個 `withProvider(String provider)`（沿用 `toProperties()` + `setProperty("provider", …)`），`withProvider(provider).validateProviderConfiguration()` 即可，也順便讓這個 seam 給 `1.16.5`／`LegacyConfigScreen` 共用。

---

### BUG-M-015 · 修正 HUD 拖曳預覽的垂直縮放，讓角落標記對齊實際位置

- **嚴重度**：中 ｜ **信心**：高
- **位置**：
  - `platforms/forge/shared/screens/1.18.2-1.21.11/src/main/java/org/universaltranslator/forge/UniversalTranslatorScreens.java:151-152`（`HUD_PREVIEW_SCREEN_WIDTH=320` / `HUD_PREVIEW_SCREEN_HEIGHT=240`）、`:866-874`（`hudPreviewH()` 上限 48）、`:892-912`（`hudScaleX/hudScaleY`、`hudHandleX/hudHandleY`）、`:914-922`（`insideHudDragHandle`）、`:930-946`（`renderHudDragPreview`）
  - `platforms/forge/modern/1.16.5/…/UniversalTranslatorScreens.java:151-152`、`:1099-1104`、`:1135-1152`、`:1161-1177`
  - `platforms/forge/26.x/shared/common/…/UniversalTranslatorConfigScreenBase.java:154-158`、`:935-940`、`:947-965`、`:971-988`、`:997-1013`
  - `platforms/forge/shared/legacy-common/…/LegacyConfigScreen.java:90-93`、`:800-805`、`:836-843`、`:852-853`、`:897-912`
  - `platforms/fabric/1.13/…/UniversalTranslatorConfigScreen.java:86-89`、`:810-815`、`:846-853`、`:862-863`、`:915-922`
  - `platforms/fabric/1.0-1.12/…/UniversalTranslatorConfigScreen.java:88-91`、`:815-820`、`:851-858`、`:867-868`、`:920-927`
  - `platforms/fabric/shared/screens-shared/…/UniversalTranslatorSettingsScreen.java:40-44`、`:942-947`、`:960-986`、`:988-996`、`:998-1013`
  - `platforms/fabric/26.x/shared/api/26.1-26.3/…/UniversalTranslatorConfigScreen.java:40-44`、`:920-925`、`:932-964`、`:966-975`、`:984-991`
- **受影響範圍**：全部 7 份設定畫面的 HUD 分頁；四個角位以 `Bottom left` / `Bottom right` 最嚴重。
- **現象**：
  1. 選 `HUD position: Bottom left` 或 `Bottom right` 時，**剛開啟畫面、偏移為 0** 的狀態下方塊就被畫在面板下緣，不是指示器真正的角落；切換按鈕文字變了，方塊卻不動。
  2. 開始拖曳後方塊滑到面板邊緣就**停住不再跟隨滑鼠**，但 `hudIndicatorOffsetX/Y` 仍持續累加；使用者必須往回拖一大段「空轉距離」指示器才會再動。
  3. 面板實際是 `totalWidth×48`（最多 310×48），代表 320×240 的虛擬畫面，水平縮放約 0.97、垂直縮放固定 0.2，兩軸不等比。
- **觸發條件**：（a）HUD 分頁把 `HUD position` 切到 bottom 兩項；（b）在預覽面板內按住方塊往任一方向拖曳超過面板能表示的範圍（預設縮放下約 40 px 指標位移即觸及）。GUI 縮放比例越小、面板越寬，誤差越大。
- **根因**：
  1. `hudPreviewH()` 把面板高度硬壓到 `HUD_DRAG_PREVIEW_HEIGHT = 48`（`:866-874`），`hudScaleY()` = `hudPreviewH() / 240`（`:896-898`）因此恆為 0.2，與水平縮放差 4.8 倍。
  2. `hudHandleY()` 夾的是**繪製座標**（`:907-912` `Math.max(py, Math.min(py + ph - HUD_DRAG_HANDLE_SIZE, y))`），而不是 `hudIndicatorOffsetY`，於是「真實偏移」與「畫出的方塊」可以脫鉤。
  3. bottom 角位時 `hudAnchorY()` = `240 - margin - size`（預設 `240-4-6 = 230`，`:886-890`），映射後是 `py+46`，而面板可畫範圍只到 `py+40`（`48-8`）——**一開啟就已經被夾掉 6 px**，正好是 `:1007` 註解「corner options 要在預覽裡看得到」的反面。
  4. 同一段 60 行在 7 份畫面逐字複製，屬同一根因的多處重複（依 §3.3 合併為一條）。
- **影響**：HUD 拖曳在 bottom 角位是「看起來壞掉」的：起始位置錯、拖曳會空轉。使用者無法用預覽判斷落點，只能存檔進遊戲確認，預覽失去存在意義。每次開 HUD 分頁都會遇到。
- **修法**：改成等比縮放 + 置中留邊（letterbox），並把 offset 夾在面板能表示的範圍，畫的時候不再夾繪製座標。

```java
// 1) 兩軸共用一個倍率，面板內置中（letterbox）
private double hudScale() {
    return Math.min(hudPreviewW() / (double) HUD_PREVIEW_SCREEN_WIDTH,
                    hudPreviewH() / (double) HUD_PREVIEW_SCREEN_HEIGHT);
}

private int hudContentX() {
    return hudPreviewX() + (hudPreviewW()
            - (int) Math.round(HUD_PREVIEW_SCREEN_WIDTH * hudScale())) / 2;
}

private int hudContentY() {
    return hudPreviewY() + (hudPreviewH()
            - (int) Math.round(HUD_PREVIEW_SCREEN_HEIGHT * hudScale())) / 2;
}

// 2) 把 offset 夾在面板能表示的範圍（不是夾繪製座標）
private int clampOffsetToPreview(int value, int anchor, int extent) {
    int span = (int) Math.floor(extent - anchor - HUD_DRAG_HANDLE_SIZE / hudScale());
    int min = (int) Math.floor(-anchor);
    int bounded = Math.max(HudIndicatorSettings.MIN_OFFSET,
            Math.min(HudIndicatorSettings.MAX_OFFSET, value));
    return Math.max(min, Math.min(span, bounded));
}

// 3) 繪製不再夾座標
private int hudHandleX() {
    return hudContentX() + (int) Math.round((hudAnchorX() + hudIndicatorOffsetX) * hudScale());
}
```

`handleDragMove` 的 `/ hudScaleX()` 與 `/ hudScaleY()` 一併改成除以 `hudScale()`，兩軸一致後空轉消失。7 份同步修改，或把這 60 行抽到 `translator-core` 新增的 `HudPreviewGeometry`，只留 `layout()` 供料。

---

## BUG-S

### BUG-S-023 · 把封鎖關鍵字上限收進 UiLimits，並在超出時提示

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - 上限定義：`translator-core/src/main/java/org/universaltranslator/core/TranslationBlocklist.java:14-16`（`MAX_CONFIG_LENGTH=4096`、`MAX_KEYWORDS=100`、`MAX_KEYWORD_LENGTH=128`）、`:46-52`（靜默截斷）
  - 輸入框上限（8 處寫死 `4096`，同檔的 prompt 都用 `UiLimits.PROMPT_MAX_CHARS`）：
    - `platforms/fabric/1.0-1.12/shared/screens/…/UniversalTranslatorConfigScreen.java:306`
    - `platforms/fabric/1.13/shared/screens/…/UniversalTranslatorConfigScreen.java:303`
    - `platforms/fabric/26.x/shared/api/26.1-26.3/…/UniversalTranslatorConfigScreen.java:305`
    - `platforms/fabric/shared/screens-shared/…/UniversalTranslatorSettingsScreen.java:353`
    - `platforms/forge/26.x/shared/common/…/UniversalTranslatorConfigScreenBase.java:314`
    - `platforms/forge/modern/1.16.5/…/UniversalTranslatorScreens.java:275`
    - `platforms/forge/shared/screens/1.18.2-1.21.11/…/UniversalTranslatorScreens.java:279`
    - `platforms/forge/shared/legacy-common/…/LegacyConfigScreen.java:311`
- **受影響範圍**：全部平台與版本的 SCOPES 分頁「封鎖關鍵字」輸入框。
- **現象**：貼上超過 100 個關鍵字（或單一關鍵字超過 128 字）後按 Save，畫面回報「Settings saved」，但**只有前 100 個生效**，第 101 個之後完全被忽略；超過 128 字的那筆被砍成前 128 字而匹配不到原文。使用者以為已封鎖，實際仍在送往翻譯服務。畫面沒有任何字數／筆數回饋。
- **觸發條件**：欄位輸入 ≥101 筆以 `,` `;` `，` `；` 換行分隔的關鍵字，或單筆 >128 字。輸入框上限是 4096 字，101 個短詞（約 400 字）**完全塞得進去**，不會被 `setMaxLength` 擋下。
- **根因**：`TranslationBlocklist.parse()` 在 `:46-52` 用 `substring` 截斷、用 `unique.size() >= MAX_KEYWORDS` 直接 `break`，過程中沒有任何回報管道；UI 端只複製了 `MAX_CONFIG_LENGTH` 這個數字（8 份各抄一次，`UiLimits` 沒有對應常數），對真正生效的 `MAX_KEYWORDS=100` / `MAX_KEYWORD_LENGTH=128` 完全沒有暴露。
- **影響**：靜默功能失效，方向是「該封鎖的沒封鎖」。使用者把文字加入封鎖清單通常是為了避免特定內容被送到第三方服務，靜默丟棄直接違背該期待。且 4096 這個數字在 8 個檔案各一份，日後改 `MAX_CONFIG_LENGTH` 必漏改。
- **修法**：

```java
// UiLimits.java
/** 封鎖關鍵字輸入框的字元上限，等於 {@link TranslationBlocklist#MAX_CONFIG_LENGTH}。 */
public static final int KEYWORDS_MAX_CHARS = TranslationBlocklist.MAX_CONFIG_LENGTH;
/** 生效的關鍵字筆數上限，畫面必須提示，否則超出部分會被靜默丟棄。 */
public static final int KEYWORDS_MAX_ENTRIES = TranslationBlocklist.MAX_KEYWORDS;

// 8 處
blockedKeywords.setMaxLength(UiLimits.KEYWORDS_MAX_CHARS);

// TranslationBlocklist 增加帶回報的解析
public static final class Parsed {
    public final TranslationBlocklist blocklist;
    public final int dropped;    // 因 MAX_KEYWORDS 被丟棄的筆數
    public final int truncated;  // 因 MAX_KEYWORD_LENGTH 被截斷的筆數
}
```

`saveAndApply()` 先用 `parseWithReport(...)` 檢查，`dropped > 0 || truncated > 0` 就走 `setTestStatus(tr("status.universal_translator.blocked_keywords_truncated", dropped, truncated), true)` 並且不關閉畫面；同時在 `blocked_keywords_hint` 補上「最多 100 筆、每筆 128 字」。

---

### BUG-S-024 · 清掉 13 個從未被引用的語言鍵，或把 HUD 拖曳提示接上

- **嚴重度**：小 ｜ **信心**：高
- **位置**（三個語言檔行號完全相同）：
  - `platforms/shared/lang/json/assets/universal_translator/lang/en_us.json:10`、`:12`、`:13`、`:15`、`:37`、`:38`、`:39`、`:107`、`:108`、`:134`、`:147`、`:148`、`:149`
  - 同位置：`zh_cn.json` 與 `zh_tw.json` 的 `10,12,13,15,37,38,39,107,108,134,147,148,149`
- **受影響範圍**：三個語言檔、全部 24 個發布目標；其中 `hud_drag.*` 與 `option.hud_drag_hint` 影響 HUD 分頁的可發現性。
- **現象**：語言檔各 212 鍵，其中 13 鍵在 `platforms/**` 與 `translator-core/**` 的 Java 程式碼裡**引用數為 0**（已用全庫字串比對，含 `"..." + suffix` 的動態拼接形式）：`screen.universal_translator.target_language`、`outgoing_target_language`、`target_language_hint`、`outgoing_target_hint`、`error.universal_translator.target_required`、`error.universal_translator.outgoing_target_required`、`message.universal_translator.settings_reloaded`、`screen.universal_translator.home.enabled`、`home.vanilla`、`home.target`、`option.hud_drag_hint`、`value.universal_translator.hud_drag.ready`、`hud_drag.dragging`。
  最直接的後果：`option.hud_drag_hint`（「Drag the square to move the indicator」）與 `hud_drag.ready` / `hud_drag.dragging` 是為 HUD 拖曳方塊準備的說明，程式碼從未畫出來——使用者在 HUD 分頁只看到一顆沒有標籤的 8×8 綠色方塊，不知道它可以拖。
- **觸發條件**：開任一設定畫面的 HUD 分頁，觀察那顆綠色方塊，畫面上不會出現任何文字說明（hover 也沒有 tooltip）。
- **根因**：`renderHudDragPreview`（`UniversalTranslatorScreens.java:930-946` 等 7 處）只畫面板底色與方塊，沒有畫 `option.hud_drag_hint`；`value.universal_translator.hud_drag.ready/dragging` 對應的 `hudDragging` 狀態只用來切換填充色（`:945` `0xFFFFAA00` / `0xFF00AA00`），沒有文字。`home.*` 三鍵則是被 `screen.universal_translator.home.open`（`TitleScreenMixin.java:24`，各平台皆是）取代後的遺留。
- **影響**：可用性與維護成本。使用者不知道方塊可拖；13/212（6.1%）的鍵是死資料，未來改文案的人會以為改了有效。
- **修法**：二擇一。(a) 在 `renderHudDragPreview` 的面板下方補畫 `tr("screen.universal_translator.option.hud_drag_hint")`，並用 `hudDragging ? "…hud_drag.dragging" : "…hud_drag.ready"` 當方塊 tooltip；(b) 確認不做就從三個語言檔刪掉這 13 鍵，並在 `HomeQuickSettingsState` 的 javadoc 註明 `home.*` 已由 `home.open` 取代。

---

### BUG-S-025 · 讓 ENGINE 分頁的解說與隱私提示不要壓到 Save 列

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - `platforms/fabric/shared/screens-shared/src/client/java/org/universaltranslator/fabric/UniversalTranslatorSettingsScreen.java:1082-1087`（`engineBlockTop`）、`:1089-1091`（`engineRow`）、`:1094-1114`（`renderEngineInfo`，`y = engineRow(5) + 24` 在 `:1095`、隱私提示 `y + 18` 在 `:1113`）
  - 同構：`platforms/fabric/1.13/…/UniversalTranslatorConfigScreen.java:770-775`、`:1259`、`:1274`；`platforms/fabric/1.0-1.12/…:775-780`、`:1244`、`:1259`；`platforms/fabric/26.x/…:1062-1067`、`:1075`、`:1092`
  - Forge 同構：`platforms/forge/shared/screens/1.18.2-1.21.11/…/UniversalTranslatorScreens.java:969-1003`、`platforms/forge/modern/1.16.5/…:801-840`、`platforms/forge/shared/legacy-common/…/LegacyConfigScreen.java:1262-1301`
- **受影響範圍**：全部 7 份設定畫面（排版計算同一份），`height ≲ 240` 起。
- **現象**：320×240 下 ENGINE 分頁那句隱私提示（`info.api`「API 模式會把伺服器／模組文字送到翻譯服務」／`info.offline`）被畫在 Save/Cancel 那一列上。Fabric 1.13 與 1.0–1.12 是文字**蓋在**按鈕上（`super.render` 在文字之前）；`screens-shared` 與 26.x 因為 chrome 在 `super` 之前，變成文字被按鈕吃掉。使用者看不到這句唯一的隱私告知。
- **觸發條件**：`height = 240`（Minecraft 最小邏輯高度）時實測：`saveY = 216`、`contentTop = 46`、`contentRowStep = 26`、`blockHeight = 26×5 + 20 + 46 = 196`，`available = 216 - 6 - 46 = 164`，`(164-196)/2 = -16` 被 `Math.max(0, …)` 夾成 0 → `engineBlockTop = 46`、`engineRow(5) = 176`、摘要行 `y = 200`、**隱私提示 `y = 218`**，落在 Save(216..236) 內。`height ≤ 220` 時 Workers/Batch（`176..196`）也會與 Save 列重疊。
- **根因**：`engineBlockTop()` 在 `available < blockHeight` 時只把差值夾成 0，沒有把「46 px 的解說保留區」與 `contentRowStep` 一起縮；`engineRow(5) + 24` 與 `+18` 是固定偏移，不檢查 `saveY`。`engineBlockTop` 的 javadoc（`:1077-1081`）明說「The shared geometry is left alone because Save/Cancel and the HUD preview depend on it」，也就是把衝突推給了呼叫端，但呼叫端沒有做檢查。
- **影響**：小視窗（很常見：1280×720 開 GUI scale 3 → 320×180 更糟）下隱私提示完全看不到；≤220 px 時按鈕與 Save 搶同一塊點擊區。
- **修法**：

```java
private int engineBlockTop() {
    Layout layout = layout();
    int infoHeight = 46;                                     // 摘要 + 隱私提示的保留區
    int available = layout.saveY - 6 - layout.contentTop - infoHeight;
    int step = Math.max(12, Math.min(layout.contentRowStep, available / 5));
    int blockHeight = step * 5 + 20 + infoHeight;
    int spare = Math.max(0, (layout.saveY - 6 - layout.contentTop) - blockHeight);
    return layout.contentTop + spare / 2;
}

// renderEngineInfo：放不下就不畫，而不是畫到 Save 上
int y = engineRow(5) + 24;
boolean roomForHint = y + 18 + 9 <= layout().saveY;
```

`engineRow(index)` 要改用同一個 `step`（目前讀 `layout().contentRowStep`），否則壓縮後的按鈕與文字會錯開。

---

### BUG-S-026 · 抽出共用的模型清單分頁模組，順手修掉頁碼越界與版面常數重複

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - 同一檔案內兩份實作 ＋ 兩組重複常數：`platforms/forge/shared/screens/1.18.2-1.21.11/…/UniversalTranslatorScreens.java:157,159,161`（`MODEL_ROW_HEIGHT=12` / `MODEL_LIST_TOP=46` / `MODEL_LIST_FOOTER_HEIGHT=62`）、`:1129-1183`（第一份）；`:1554,1556,1558`（`LIST_ROW_HEIGHT` / `LIST_TOP` / `LIST_FOOTER_HEIGHT`，值完全相同）、`:1710-1797`（第二份）
  - 跨檔重複：`platforms/fabric/1.13/…/UniversalTranslatorConfigScreen.java:91-95`、`:1390-1456` ↔ `platforms/fabric/1.13/…/UniversalTranslatorLlmConfigScreen.java:15-19`、`:250-268`；同型在 `1.0-1.12`、`1.16-1.18`、`1.19`、`1.21`、`26.x`、`legacy-common` 各一組
  - 頁碼越界（`modelPage` 只在抓取成功時歸零，之後從不夾取）：`1.18.2-1.21.11/…:146`（欄位）、`:1036`（唯一歸零點）、`:1133-1143`（`first = modelPage * rows`）、`:1150-1175`；第二份 `:1573`、`:1680`、`:1714-1723`、`:1739-1768`；Fabric `screens-shared/…:205`、`:1146-1147`、`:1246`、`:1254-1256`、`:1268-1291`；`26.x/…:156`、`:1125-1126`、`:1225`、`:1233-1234`、`:1244-1275`；`1.13/…:200`、`:1307-1308`、`:1407`、`:1414-1416`、`:1425-1448`；`1.0-1.12/…:203`、`:1293`、`:1392`、`:1399-1400`、`:1410-1433`；`LegacyConfigScreen.java:1432`、`:1439-1440`、`:1450-1453`
- **受影響範圍**：全部 7 份設定畫面 ＋ 各版本獨立 `UniversalTranslatorLlmConfigScreen`（共 12 個入口）。
- **現象**：
  1. 模型目錄覆蓋層開著時把視窗拉高（或把 GUI 縮放從 4 調到 1），`rows` 變多 → 總頁數變少，但 `modelPage` 仍是舊值 → 覆蓋層變成**一整片空白**，底部頁碼顯示「4/2」「6/2」這種不存在的組合；要點一次「上一頁」才恢復。
  2. 同一個「抓到的模型目錄」覆蓋層在同一個 Java 檔裡有兩份幾乎逐字相同的實作（`renderModelList` / `handleModelListClick` / `rowsPerPage` ↔ `modelRowsPerPage` / `pageCount` ↔ `modelPageCount`），連 `46` / `12` / `62` 三個版面常數都各寫一份、只是改名 `LIST_*`。任何一次只改一份的修正，兩個入口（設定畫面 ENGINE 分頁「取得模型」vs 獨立 LLM 畫面「取得模型」）就會長得不一樣。
- **觸發條件**：（1）開「取得模型」→ 翻到第 2 頁以上（需要目錄夠長，20 個模型在 `height=240` 時 `rows=11`、共 2 頁）→ 覆蓋層仍開著時把視窗拉高到 400（`rows=24`、`pages=1`）→ `first = 1×24 = 24 > 20`，一行都不畫。（2）不需要觸發，屬結構性風險。
- **根因**：`modelPage` 是唯一的捲動狀態，但 `renderModelList` 與 `handleModelListClick` 都直接 `modelPage * rows`，沒有任何 `modelPage = min(modelPage, pages - 1)`；`modelRowsPerPage()` 又完全由 `height` 決定，所以高度一變就可能越界。而 `init()` / resize 也不會重設，因為 `modelListOpen` 會跨 resize 存活。重複的常數則是因為 `AGENTS.md` 要求的「先找 `UiLimits`」在這一段沒被遵守。
- **影響**：覆蓋層短暫空白且頁碼自相矛盾（無「此頁無資料」說明，容易誤以為抓模型失敗）；12 處手抄需要手動同步修正，屬尚未爆的債。
- **修法**：抽一個 `translator-core` 的 `ModelListPaging`，同時解決兩件事。

```java
// translator-core：UiLimits 增加三個版面常數
public static final int MODEL_LIST_TOP = 46;
public static final int MODEL_ROW_HEIGHT = 12;
public static final int MODEL_LIST_FOOTER_HEIGHT = 62;

public final class ModelListPaging {
    public static int rows(int screenHeight) {
        return Math.max(3, (screenHeight - UiLimits.MODEL_LIST_TOP
                - UiLimits.MODEL_LIST_FOOTER_HEIGHT) / UiLimits.MODEL_ROW_HEIGHT);
    }
    public static int pageCount(int size, int rows) {
        return Math.max(1, (size + rows - 1) / rows);
    }
    /** 夾取頁碼；resize 後頁數變少時必須先呼叫，否則會畫出空白頁。 */
    public static int clampPage(int page, int size, int rows) {
        return Math.max(0, Math.min(page, pageCount(size, rows) - 1));
    }
    public static int rowAt(int mouseY) {
        return (mouseY - UiLimits.MODEL_LIST_TOP) / UiLimits.MODEL_ROW_HEIGHT;
    }
    public static int index(int page, int rows, int row) { return page * rows + row; }
}
```

12 個入口一律改呼叫這一組，`MODEL_*` / `LIST_*` 兩組常數刪除。

---

### BUG-S-027 · 修正極小縮放寬度下第五個分頁被推出畫面

- **嚴重度**：小 ｜ **信心**：中
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/SettingsScreenLayout.java:24-29`（`totalWidth` / `left`）、`:56-59`（`tabY`、`tabGap = 3`、`tabWidth = Math.max(20, …)`）、`:115`（`tabX(int index)`）
- **受影響範圍**：全部 7 份設定畫面（都呼叫 `calculate(width, height, 5)`）。只在縮放後邏輯寬度約 < 116 px 時發生。
- **現象**：GUI 縮放比例 4 搭配較小的實體視窗（或行動啟動器／嵌入式視窗）時，五個分頁按鈕被 `Math.max(20, …)` 的下限撐住，整排總寬超出面板與畫面，最右邊的 `HUD` 分頁被推出畫面，只剩 0–4 px 可點，使用者無法切到 HUD 分頁。
- **觸發條件**：`screenWidth ≤ 116`（例如實體 460×270 + scale 4，或 232×135 + scale 2）。走 `:24-26` 的 `safeWidth < 200` 分支：`totalWidth = min(310, screenWidth-8)`、`left = (screenWidth-totalWidth)/2 = 4`、`tabWidth = max(20, (totalWidth-12)/5)` 被下限拉到 20。代 `screenWidth=112` → `totalWidth=104`、`tabX(4)=4+4×23=96`、右緣 `116 > 112`；代 `screenWidth=100` → 右緣 `116 > 100`，超出 16 px。
- **根因**：`tabWidth` 的 20 px 下限（給可讀性用）讓 `tabWidth × tabCount + tabGap × (tabCount-1)` 不再受 `totalWidth` 約束，而 `totalWidth` 走 `safeWidth - 8`，沒有替分頁列預留寬度。這是加入第 5 個（HUD）分頁時才出現的回歸：4 個分頁只需 `4×20+9 = 89`，在 `safeWidth ≥ 97` 時都還塞得下。
- **影響**：極窄畫面下 HUD 分頁不可達（連帶指示器、顏色、內容、可見度、拖曳位置全部無法設定）。這正是 `:43-45` 註解所說「mobile launchers、custom GUI scales、embedded windows 可能更小」要照顧的情境。
- **修法**：分頁寬度上限受畫面約束，並讓整排居中；`screenWidth` 太小時改用「當前分頁 + 左右箭頭」。

```java
int tabGap = 3;
int tabAvailable = Math.max(40, Math.min(totalWidth, safeWidth - 2 * left));
int tabWidth = Math.max(14, (tabAvailable - tabGap * (safeTabCount - 1)) / safeTabCount);
int tabRowWidth = tabWidth * safeTabCount + tabGap * (safeTabCount - 1);
int tabLeft = Math.max(0, (safeWidth - tabRowWidth) / 2);

// Geometry.tabX(int index)
public int tabX(int index) { return tabLeft + index * (tabWidth + tabGap); }
```

---

### BUG-S-028 · 讓服務名稱走語言鍵，不要永遠是英文

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`translator-core/src/main/java/org/universaltranslator/core/TranslationProviderCatalog.java:44-67`（`displayName` 全部回傳英文硬編字面，21 個分支）、`translator-core/src/main/java/org/universaltranslator/core/SettingsSelectionList.java:25-29`（直接轉發）；呼叫端 `platforms/fabric/1.13/…:702`、`:1212`；`1.0-1.12/…:707`、`:1192`；`screens-shared/…:540`、`:1034`；`26.x/…:643`、`:1012`；Forge 各份 `refreshLabels()` 與選取清單繪製
- **受影響範圍**：全部平台、全部語言檔（含 `zh_cn` / `zh_tw`）。
- **現象**：`Service: …` 按鈕與服務清單永遠顯示 "Tencent Hunyuan"、"Alibaba Cloud MT"、"Anthropic Claude"、"DeepL" 等英文，繁體中文介面裡只有這一塊是英文。
- **觸發條件**：任何語系下開 ENGINE 分頁或服務清單。
- **根因**：`displayName()` 是一串 `if (...) return "…";` 的英文字面（`:44-67`），`SettingsSelectionList.displayName()`（`:25-29`）直接轉發，四份畫面都只用它、沒有經過 `tr()`。`en_us.json` 裡雖然有 `value.universal_translator.provider_offline` / `provider_llm` / `provider_tencent`，但那是 HUD 用的另一組鍵（`HudIndicatorContent.PROVIDER` 顯示用），與服務清單無關。26.x 那份更繞一層：把英文字面當成鍵丟給 `Component.translatable(...)`，查不到才回退成原文，等於每次顯示都白查一次。
- **影響**：在地化不完整；繁中／簡中使用者看到 20 個英文服務名。也讓 `SettingsSelectionList.displayName` 無法在不改 20 個呼叫端的前提下本地化。
- **修法**：`displayName` 改回傳鍵，畫面端用 `tr(...)`；或保留 `displayName` 但改走 `UiTranslator`。

```java
// TranslationProviderCatalog
/** 語言鍵；實際顯示文字由 UiTranslator 解析，查不到時回退成人類可讀的英文名。 */
public static String displayNameKey(String provider) {
    return "provider.universal_translator." + (provider == null ? "unknown" : provider.trim());
}

// SettingsSelectionList：多載一個帶 translator 的版本
public static String displayName(Kind kind, String value, UiTranslator translator) {
    if (kind != Kind.PROVIDER) {
        return TargetLanguage.displayName(value);
    }
    String key = TranslationProviderCatalog.displayNameKey(value);
    String localized = translator.translate(key);
    return localized.equals(key) ? TranslationProviderCatalog.displayName(value) : localized;
}
```

三個語言檔各補 21 個 `provider.universal_translator.<id>` 鍵（`offline`、`libretranslate`、`baidu`、`tencent-tmt`、`tencent-hunyuan`、`aliyun-mt`、`youdao`、`volcengine-mt`、`iflytek-niutrans`、`huawei-cloud-mt`、`deepseek`、`dashscope`、`volcengine-ark`、`zhipu`、`openai-compatible`、`azure-openai`、`deepl`、`gemini`、`claude`、`custom-http-json`、`unknown`）。

---

### BUG-S-029 · 讓離線引擎的失敗原因走語言鍵，別把中文原文透到其他語系

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - `translator-core/src/main/java/org/universaltranslator/core/provider/LlamaCppOfflineProvider.java:191`（`status = "离线翻译失败：" + safeMessage(error)`）、`:206`（`throw new IOException("离线模型已停止")`）、`:212`（`throw new IOException("离线模型正在启动中")`）、`:751`（`status = "离线模型已停止"`）
  - `translator-core/src/main/java/org/universaltranslator/core/TranslationStatusLocalizer.java:50-53`（只翻譯前綴，尾段原樣帶出）、`:71`（比對失敗時 `return value;` 直接回原文）
- **受影響範圍**：所有使用離線 llama.cpp 引擎的平台與版本；非中文語系（`en_us` 及未來其他語言）使用者。
- **現象**：離線引擎在使用者按下停止後又有請求進來，或啟動中的請求撞上另一次啟動，HUD／設定畫面的狀態列會出現 `Offline translation failed: 离线模型已停止` 這種中英夾雜的一行。
- **觸發條件**：`ensureRunning()` 在 `stopRequested` 為真時拋 `IOException("离线模型已停止")`（`:206`），`translate()` 的 catch 接成 `"离线翻译失败：" + safeMessage(error)`（`:191`）；`TranslationStatusLocalizer` 命中 `startsWith("离线翻译失败：")`（`:50`）後把尾段當參數塞進 `status.universal_translator.offline_failed`（英文是 `Offline translation failed: %s`），中文因此原樣輸出。`:212` 的「离线模型正在启动中」同理。
- **根因**：`TranslationStatusLocalizer` 用**硬寫的中文字面量**當內部狀態與語言鍵之間的對照表（整個 `localize()` 是 30 多個 `equals`/`startsWith`），且只翻譯前綴、不翻譯尾段參數；同時 `LlamaCppOfflineProvider` 把使用者可見的例外訊息也寫成中文。任一端改一個字，該狀態在**所有**語系都會退回中文原文（`:71`），編譯器不會有任何提示。
- **影響**：非中文語系狀態列出現中文；對照表的脆弱設計讓「改文案」變成跨語系的靜默回退風險。
- **修法**：分兩步。(1) 立即修：`:206`、`:212`、`:751` 改成內部碼（例如 `"offline-model-stopped"`），並在 `TranslationStatusLocalizer` 加對照；(2) 結構性修：讓 provider 直接回傳語言鍵，`localize()` 只負責「鍵 + 參數」組字串。

```java
// LlamaCppOfflineProvider
private static final String KEY_STOPPED = "status.universal_translator.offline_stopped";
throw new IOException(KEY_STOPPED);              // 訊息即語言鍵

// TranslationStatusLocalizer：先認鍵，再退回舊的中文字面比對
if (value.startsWith("status.universal_translator.")) {
    return translator.translate(value);
}
```

---

### BUG-S-030 · 讓 LLM 設定畫面在 resize 後保留未存的輸入

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - `platforms/forge/shared/screens/1.18.2-1.21.11/src/main/java/org/universaltranslator/forge/UniversalTranslatorScreens.java:1591-1601`（`init()` 內 `endpoint.setValue(initialEndpoint)` 在 `:1594`、`model.setValue(initialModel)` 在 `:1598`、`apiKey` 在 `:1599-1601` 完全沒有回填）
  - 同構：`platforms/forge/modern/1.16.5/…/UniversalTranslatorScreens.java:1600-1610`、`platforms/fabric/1.13/…/UniversalTranslatorLlmConfigScreen.java:55-69`、`1.0-1.12/…/UniversalTranslatorLlmConfigScreen.java:56-70`、`1.14-1.15/…:55-70`、`1.16-1.18/…:52-67`、`1.19/…:50-65`、`1.21/…:49-64`、`26.x/…:51-66`、`legacy-common/…/LegacyLlmConfigScreen.java:52-72`
  - 對照（同一專案裡寫對的版本）：設定畫面 `1.18.2-1.21.11/…/UniversalTranslatorScreens.java:210-212`、`screens-shared/…:250-252`（`endpoint == null ? original.endpoint : endpoint.getText()` — 先讀回既有輸入再重建）
- **受影響範圍**：全部版本的獨立 LLM 設定畫面（`UniversalTranslatorLlmConfigScreen` 家族，Fabric 6 份 ＋ Forge 3 份）。
- **現象**：在 LLM 設定畫面（Endpoint / Model / API Key 三個輸入框）打了一半，視窗大小改變或 GUI 縮放比例一改（Minecraft 會走 `resize` → `init()`），**三個輸入框全部回到開啟畫面時的初始值**，剛剛打的字消失；API Key 更是每次都變空白（連初始值都沒有回填）。
- **觸發條件**：開 LLM 設定畫面 → 在任一輸入框打字 → 拖動視窗邊框改變大小，或按 F11 進全螢幕，或改 GUI 縮放比例 → 輸入內容消失。不需要按任何按鈕。
- **根因**：`init()` 用建構子時的 `initialEndpoint` / `initialModel` 快照覆蓋欄位（`:1594`、`:1598`），而這兩個欄位在 `save()` 之前不會被更新；`apiKey` 連 `setValue` 都沒有（`:1599-1601`），新的 `EditBox` 天生空白。同一個專案的設定畫面已經有正確寫法（先讀回 widget 現值，`:210-212`），LLM 畫面這 9 份沒有跟上。
- **影響**：使用者白打一次 API Key（很長、是機密、通常從別處貼上）；打字打到一半調視窗就重來。因為 API Key 需要貼上，這個丟失特別惱人。
- **修法**：沿用設定畫面的「先讀回現值」寫法。

```java
// init() 開頭先把現值取回
String endpointValue = endpoint == null ? initialEndpoint : endpoint.getValue();
String modelValue = model == null ? initialModel : model.getValue();
String apiKeyValue = apiKey == null ? initialApiKey : apiKey.getValue();

endpoint.setMaxLength(512);
endpoint.setValue(endpointValue);
model.setMaxLength(128);
model.setValue(modelValue);
apiKey.setMaxLength(512);
apiKey.setValue(apiKeyValue);        // 目前完全沒有這一行
```

9 份 LLM 畫面與 2 份 Forge 內嵌副本同步修改。

---

## 配額外候選（未編號，供 Lead 分配或轉交）

以下已用 `read` 核對且成立，但 a06 的 8 個 S 名額已滿，先記錄不編號，避免虛報或越界：

1. `platforms/forge/shared/legacy-common/…/LegacyDiagnosticsScreen.java:194-197` 的匯出狀態列畫在 `height-82`、按鈕列起於 `height-76`（`:62-66`），字型高 9 → 恆重疊 2–3 px，且 `super.drawScreen` 在 `:198` 才畫按鈕，狀態文字下緣被蓋掉。修法：兩者由同一組常數推算並留出字型高度。
2. `translator-core/…/HudIndicatorSettings.java:116-121` 的 `clamp` 在越界時回 `fallback`（`DEFAULT_SIZE`/`DEFAULT_MARGIN`），手改 `hud-indicator-size=20` 會跳回 6 而不是夾到 16。修法：`Math.max(min, Math.min(max, value))`，「無法解析」的情形留在 `*Config` 的解析端處理。
3. `platforms/fabric/1.13/…/UniversalTranslatorConfigScreen.java:1257-1258`、`1.0-1.12/…:1242-1243` 的 prompt 提示文字用 `engineRow(4) - 11`，與 Quality 按鈕（`engineRow(3)..+20`）恆重疊 3–9 px。修法：先檢查空間再畫，或改用 1.14+ 的 `setSuggestion`。
4. `platforms/fabric/1.0-1.12/…/UniversalTranslatorConfigScreen.java:1210` 在選完服務後呼叫 `init()`，1.13／screens-shared／26.x 都是 `updateTabVisibility()`——同一操作在三棵樹反應不同，且 `init()` 會換掉所有 widget 物件（焦點與游標回到預設）。
5. `platforms/fabric/shared/screens-shared/…/UniversalTranslatorSettingsScreen.java:771-772` 存檔成功後 `status = …saved` 立刻 `onBack()`，該賦值永遠不會被 `renderChrome`（`:818`）畫出來，且 `status` 一律以 `0xFFFF5555`（紅）繪製，語意也錯。

---

## 已排查但判定無問題

1. **三份語言檔完全一致**：`en_us` / `zh_cn` / `zh_tw` 各 212 鍵；鍵集合做過三向差集（無單側缺鍵），raw regex 檢查無重複鍵，`%s`/`%d`/`%d%%` 佔位符數量三檔全部一致。`TranslationStats.localizedLines()`（`TranslationStats.java:301-320`）的參數個數與 `stats.totals/cache/latency/tokens/reasons` 語言鍵相符。
2. **程式碼沒有「用到但語言檔沒有」的鍵**：全庫抽取 187 個被引用的鍵與語言檔做差集，只剩 6 個**字首**（`stats.reason.`、`color.`、`hud_content.`、`hud_corner.`、`hud_visibility.`、`quality.`，實際鍵由 `configName()` 拼接）；逐項驗證拼接結果都存在（`HudIndicatorColor.LIGHT_PURPLE.configName()` 是 `light-purple`，各畫面以 `configName().replace('-','_')` 組成 `…color.light_purple`，與語言鍵一致，不會顯示原始鍵名）。
3. **分頁數與共用幾何一致**：8 個 `Tab` 列舉全部是 `GENERAL/SCOPES/ENGINE/OUTGOING/HUD` 五項，所有畫面都呼叫 `SettingsScreenLayout.calculate(width, height, 5)`（`1.18.2-1.21.11/…:1251`、`1.16.5/…:1261`、`LegacyConfigScreen.java:258/772/1072`、`UniversalTranslatorConfigScreenBase.java:1369`、`fabric/1.0-1.12/…:256/767/1081`、`fabric/1.13/…:253/762/1101`、`screens-shared/…:1380`、`fabric/26.x/…:1341`），沒有「有 HUD 分頁卻傳 4」的錯配。`layout.tabX(activeTab.ordinal())` 的順序也與列舉一致，底線不會滑到隔壁分頁。
4. **on/off 文字單一來源**：全庫找不到寫死的 `"value.universal_translator.on"` / `"value.universal_translator.off"` 字面量（只有 `UiTextKeys.java:12,15` 的宣告），所有畫面與 `TranslationDiagnosticsSnapshot.java:144` 都走 `UiTextKeys.onOff(boolean)`。
5. **`customPrompt` 上限一致**：8 處 `setMaxLength`／`setMaxStringLength` 全部引用 `UiLimits.PROMPT_MAX_CHARS`（4096），與 `OnlineProviderConfig` 的 `custom-system-prompt` 及 `TranslationBlocklist.MAX_CONFIG_LENGTH` 三方相符。
6. **手改設定檔不會讓 UI 拋例外**：`HudIndicatorCorner/Color/Content/Visibility.fromConfig()` 四個都有 fallback，`HomeQuickSettingsState` 建構子對 `hudIndicator` / `provider` 做了非 null 保證（`HomeQuickSettingsState.java:31-33`）；`HUD 拖曳的除零路徑不可達`（`insideHudDragHandle` 先擋 `hudPreviewH() <= 0`，且 `init()` 會清 `hudDragging`）。
7. **選取清單的繪製區與點擊區一致**：`renderSelection` 與 `optionAt` 共用同一個 `SettingsSelectionList.layout(width, height, values.length)` 與同一組 `x/y/buttonWidth/buttonHeight`（`SettingsSelectionList.java:79-105`），沒有 optionCount 不一致或 off-by-one；`panelBottom` 由實際繪製的最後一列推導（`:50-52`），窄畫面不會出現畫在面板外卻可點的情形。
8. **`withSettings(...)` 23 個位置參數沒有錯位**：四份的引數順序（`blocked → target → outgoingTarget → display → englishOnly → color → provider → endpoint → llmEndpoint → llmApiKey → llmModel → …`）與 `FabricConfig.java:193-217` / `ForgeConfig.java:193-217` 的簽名一致；`HudIndicatorSettings` 用物件傳遞（而非 9 個連續 int），避免了 size/margin/offset 交換順序這種「能編譯但看起來錯」的風險。
