# a03-fabric-shared 審計報告

範圍：`platforms/fabric/shared/**`（41 個 `.java`）、`platforms/fabric/modmenu/**`、`platforms/fabric/bundles/**`、`platforms/neoforge/**`（entrypoints／metadata／各版本 `build.gradle`／`*.toml`）
配額：BUG-M 3 條（006–008）、BUG-S 6 條（001–006）
產出：BUG-C 0 / BUG-M 3 / BUG-S 6（實際）

> 不負責：`platforms/fabric` 的版本目錄（1.0-1.8 … 26.x、legacy）→ a04；巨型 `*Screen*.java` 的 UI 版面 → a06。
> 本報告只寫跨版本共用的 shared 層，以及 NeoForge 的進入點／metadata／座標層。

---

### BUG-M-006 · 現代樹遺失 reset()，渲染例外後翻譯永久失效

- **嚴重度**：中 ｜ **信心**：高
- **位置**：
  - `platforms/fabric/shared/common/src/client/java/org/universaltranslator/fabric/TranslationRenderContext.java:23-40`（只有 `push`/`pop`/`current`，**沒有** `reset()`）
  - `platforms/fabric/shared/render-context-legacy/src/main/java/org/universaltranslator/fabric/TranslationRenderContext.java:37-48`（legacy 有 `reset()`，且註解明說為什麼需要）
  - 受影響的 push/pop 配對：`platforms/fabric/shared/mixin-1.16-1.19/src/client/java/org/universaltranslator/fabric/mixin/TextFieldWidgetMixin.java:14-28`、同目錄 `SignEditScreenMixin.java:14-28`、`platforms/fabric/shared/mixin-1.20-1.21/src/client/java/org/universaltranslator/fabric/mixin/SignEditScreenMixin.java:14-28`、`platforms/fabric/shared/mixin-1.16-1.19/.../ChatHudContextMixin.java:14-24`、`.../BossBarHudContextMixin.java:14-22`
  - 受影響的判讀點：`platforms/fabric/shared/rendered-common/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:197-203`
- **受影響範圍**：Fabric 1.14–26.x 全部（用 `shared/common` 的那一棵）；legacy 1.0–1.13 不受影響，因為 `render-context-legacy` 提供了 `reset()`，且 `platforms/fabric/1.0-1.8`、`1.8-1.12` 的 `MinecraftMixin` 每 tick 呼叫它。NeoForge 走 Forge 層，同樣沒有這個防護。
- **現象**：玩到一半（開過一次告示牌編輯、搜尋欄、聊天框之後）所有文字突然都不翻了，介面、聊天、物品名全部變回原文，重開設定畫面、按 F8 開關都救不回來，只能重啟遊戲。
- **觸發條件**：任一被 `@Inject(at = HEAD)` push、`@Inject(at = RETURN)` pop 包住的渲染方法在 pop 之前拋出例外（例如 `AbstractSignEditScreen.render` 因其他 mod 的繪製狀態異常而拋、`TextFieldWidget.renderButton` 內的 `getText()` 為 null 的第三方子類）。Mixin 0.8.x 沒有 exception-exit 注入點，`RETURN` 注入不會執行。
- **根因**：`TEXT_INPUT_DEPTH` 是 `ThreadLocal<Integer>` 的計數器（`TranslationRenderContext.java:17-18, 43-60`），push 一次沒 pop 就永久 >0。`RenderedTextBridge.translateRaw` 第一行就是 `if (TranslationRenderContext.isTextInput()) return text;`（`RenderedTextBridge.java:198-200`），於是渲染執行緒之後送出的每一段字串都直接被判為「本地輸入」並原樣返回。`KINDS` 同理，殘留的 `TextKind` 會讓之後所有文字被歸類成 `CHAT`/`BOSS_BAR` 等錯誤種類。
  legacy 版本早就發現這件事，`render-context-legacy/.../TranslationRenderContext.java:37-44` 的註解逐字寫出這個失效模式（「…would otherwise leave a stale TextKind and a text-input depth above zero forever… silently disabling translation for the rest of the session. Called once per client tick」），但 `shared/common` 這份是從它分叉出來的，`reset()` 沒有一起帶過來，也沒有任何 tick hook 呼叫它——`grep -rn "TranslationRenderContext.reset"` 只命中 1.0-1.8 與 1.8-1.12 兩個 legacy 樹。
- **影響**：單一未捕捉的渲染例外 → 整場遊戲的翻譯功能全滅且無法自我恢復（要重啟）。使用者只會覺得「這 mod 自己壞了」。發生機率取決於有沒有其他 mod 在同一幀拋例外，但代價是 100% 功能失效。
- **修法**：在 `shared/common` 的 `TranslationRenderContext` 補上與 legacy 相同的 `reset()`，並在現代樹的 client tick 入口呼叫一次（每 tick 一次即可，成本是兩個 `ThreadLocal.remove`）。

```java
// platforms/fabric/shared/common/src/client/java/.../TranslationRenderContext.java
/**
 * Clears the classification latched on the current thread. Mixin 0.8.x has no exception exit
 * injection point, so a render method that throws before its RETURN injection leaves a stale
 * TextKind and a text-input depth above zero forever, silently disabling translation for the
 * rest of the session. Called once per client tick, before the frame is rendered.
 */
public static void reset() {
    KINDS.remove();
    TEXT_INPUT_DEPTH.remove();
}
```

```java
// FabricClientBootstrapBase.onClientTick()，在 notifyRuntimeStatus(connected) 之前
TranslationRenderContext.reset();
```

  （`platforms/fabric/1.0-1.8`、`1.8-1.12` 的 `MinecraftMixin` 已經是這個寫法，可直接照抄；呼叫點屬 a04 範圍，本條只負責 shared 層的 `reset()`。與 a04 可能重複。）
- **同類風險殘留**：`ChatHudContextMixin`／`BossBarHudContextMixin`／`PlayerListHudContextMixin` 仍是純 HEAD/RETURN 配對，也只能靠同一個 `reset()` 兜住。

---

### BUG-M-007 · NeoForge 1.20.2/1.20.4/1.20.6 未標 client-only，會被當成伺服器端 mod

- **嚴重度**：中 ｜ **信心**：中
- **位置**：
  - `platforms/neoforge/shared/entrypoints/1.20.2-1.20.4/src/main/java/org/universaltranslator/forge/UniversalTranslatorNeoForgeMod.java:8`（`@Mod(UniversalTranslatorNeoForgeMod.MOD_ID)`，無 `dist`）
  - `platforms/neoforge/shared/entrypoints/1.20.6/src/main/java/org/universaltranslator/forge/UniversalTranslatorNeoForgeMod.java:8`（同上）
  - `platforms/neoforge/1.20/versions/1.20.2/src/main/resources/META-INF/mods.toml:1-4`（無 `clientSideOnly`）
  - `platforms/neoforge/1.20/versions/1.20.4/src/main/resources/META-INF/mods.toml:1-4`（無 `clientSideOnly`）
  - `platforms/neoforge/1.20/versions/1.20.6/src/main/resources/META-INF/neoforge.mods.toml:1-4`（無 `clientSideOnly`）
  - 對照組：`platforms/neoforge/1.20.1/src/main/resources/META-INF/mods.toml:4`（`clientSideOnly=true`）、`platforms/neoforge/shared/entrypoints/1.21-1.21.1/src/main/java/...:9`（`@Mod(value = ..., dist = Dist.CLIENT)`）、以及 1.21.2-1.21.8／1.21.9-1.21.11／26.1-26.1.2／26.2 四層全部帶 `dist = Dist.CLIENT`
- **受影響範圍**：NeoForge `1.20.2`、`1.20.4`、`1.20.6` 三個目標（`legacy-targets.yml` 的 neoforge-targets）。
- **現象**：玩家裝了這顆 JAR 之後連不進沒裝這個 mod 的 NeoForge 1.20.2/1.20.4/1.20.6 伺服器，被踢回並提示模組清單不符（missing/incompatible mods）；伺服器端日誌則出現本模組被載入。
- **觸發條件**：用 NeoForge 20.2.x／20.4.x／20.6.x 開的伺服器（不論是否 vanilla 內容），客戶端安裝對應版本的 mod。
- **根因**：NeoForge 判斷「這個 mod 是不是只在客戶端存在」只看兩件事——`@Mod` 的 `dist` 陣列，或 metadata 的 `clientSideOnly`。這三個目標兩者都沒有：`@Mod` 用的是最舊的單參數形式（1.20.2-1.20.4／1.20.6 兩層），三份 toml 也沒有 `clientSideOnly`（只有 1.20.1 那份有，`mods.toml:4`）。於是 NeoForge 認為它是雙端 mod，握手時要求伺服器端也有同一個 modId，而整顆 mod 其實沒有一行伺服器端程式碼。1.21+ 之所以沒事，是因為每一層的 `@Mod` 都帶了 `dist = Dist.CLIENT`——這正是「同步漏改」的典型：`dist` 屬性是在 1.21 那層導入的，回頭沒有補到 1.20 的兩層。
- **影響**：三個 NeoForge 目標的核心使用情境（進別人的伺服器翻譯）直接不可用；本地單機與自己開的伺服器正常，所以只跑 CI 不會發現。
- **修法**：兩層 entrypoint 都改成 client-only，並在三份 toml 補 `clientSideOnly`（兩者做一個即可，做兩個更保險；20.2/20.4/20.6 的 `@Mod` 是否支援 `dist` 取決於該版 FML，toml 那條一定有效）。

```java
// platforms/neoforge/shared/entrypoints/1.20.2-1.20.4/.../UniversalTranslatorNeoForgeMod.java
@Mod(value = UniversalTranslatorNeoForgeMod.MOD_ID, dist = Dist.CLIENT)
public final class UniversalTranslatorNeoForgeMod {
```

```toml
# platforms/neoforge/1.20/versions/1.20.2/src/main/resources/META-INF/mods.toml
modLoader="javafml"
loaderVersion="[1,)"
license="MIT"
clientSideOnly=true
```

  `1.20.4`、`1.20.6` 兩份同樣各補一行。驗證方式：`gh workflow run legacy-targets.yml -f targets=neoforge-1.20.4`，再手動確認 JAR 內 `META-INF/*.toml` 有該欄位。（信心標中的原因：`dist` 屬性在 20.2 是否已存在需以該版 FML API 為準；`clientSideOnly` 這條與 1.20.1 的既有寫法一致，風險最低。）

---

### BUG-M-008 · 物品提示被兩條注入路徑各翻譯一次

- **嚴重度**：中 ｜ **信心**：高
- **位置**：
  - `platforms/fabric/shared/mixin-1.20-1.21/src/client/java/org/universaltranslator/fabric/mixin/ScreenMixin.java:18-27`（攔 `Screen.getTooltipFromItem` 的 RETURN，`cancellable = true`，把整份清單換成譯文）
  - `platforms/fabric/shared/draw-context/src/client/java/org/universaltranslator/fabric/mixin/DrawContextMixin.java:15-20`（再攔 `DrawContext.drawTooltip(...)` 的第一個 `List<Text>` 參數）
  - `platforms/fabric/shared/rendered-common/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:152-195`（`translateTooltip` 對每一行呼叫 `translateLinesForRender`）
  - 兩者同時掛載的清單：`platforms/fabric/1.21/shared/common/src/main/resources/universal-translator.fabric.mixins.json:7-8`、`platforms/fabric/26.x/shared/common/src/main/resources/universal-translator.fabric.mixins.json:7-8`、`platforms/neoforge/shared/metadata/1.21/src/main/resources/universal-translator.neoforge.mixins.json:8,13`、`.../26.x/.../universal-translator.neoforge.mixins.json:8,13`、`.../1.20-1.20.4/...:7,10`、`.../1.20.6/...:7,10`
- **受影響範圍**：Fabric 1.20–1.21 與 26.x、NeoForge 1.20.2–1.20.6 與 1.21/26.x（凡是 mixin 清單同時列出 `DrawContextMixin` 與 `ScreenMixin` 的目標）。Fabric 1.19.x 只列 `ScreenMixin`、沒有 `DrawContextMixin`，不受影響。
- **現象**：滑鼠停在物品上時，tooltip 的翻譯請求被送出兩次；把 `translate-english-only` 關掉（允許翻非英文）之後更明顯——第二趟是對「已經翻好的中文」再翻一次，同一段提示在兩幀之間可能來回改寫，玩家看到文字閃動，provider 帳單與快取條目都翻倍。
- **觸發條件**：任一原版／模組容器介面把滑鼠移到有 tooltip 的物品上；`translate-english-only=false`（設定畫面可關）時必定重複翻譯。`translate-english-only=true`（預設）時第二趟被語言偵測擋掉，所以預設設定下只浪費一次快取查詢。
- **根因**：`Screen.getTooltipFromItem` 是 tooltip 清單的**生產者**，`DrawContext.drawTooltip` 是**消費者**，而兩者都被同一顆 mod 掛了。第一趟把 `List<Text>` 的每個元素換成 `Text.literal(譯文)`（`RenderedTextBridge.java:186-188`），第二趟拿到的 `originals` 已經是譯文（`RenderedTextBridge.java:168-173` 直接 `line.getString()`），於是再次進 `FabricTranslationRuntime.translateLinesForRender`。`translateTooltip(List)` 沒有任何「這份清單已經處理過」的標記，`RenderedTextBridge.java:158-160、190-193` 的兩個 `AtomicBoolean`（`ITEM_TOOLTIP_REACHED`／`ITEM_TOOLTIP_APPLIED`）只印一次診斷訊息，不參與判斷。
- **影響**：每次顯示物品提示多一次 `lookupLines`（含批次佇列與可能的 provider 請求）；關掉英文限制時等於同一段文字被翻兩次，譯文品質也會被第二趟汙染。容器介面滑鼠移動時 tooltip 每幀重建，量級隨 hover 時間線性成長。
- **修法**：讓兩條路徑只留一條。建議保留消費者端（`DrawContext.drawTooltip`），因為它涵蓋所有不走 `Screen.getTooltipFromItem` 的呼叫端；把 `ScreenMixin` 從 1.20/26.x 的清單移除，或把它的注入改成只做 `RenderedTextBridge` 的「已翻譯」標記。

```java
// RenderedTextBridge：用 IdentityHashMap 記錄本幀已處理的清單，讓第二趟直接跳過
private static final java.util.Map<List<Text>, Boolean> HANDLED =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<List<Text>, Boolean>());

public static List<Text> translateTooltip(List<Text> lines) {
    if (lines == null || lines.isEmpty() || HANDLED.containsKey(lines)) {
        return lines;
    }
    List<Text> result = translateTooltip(lines, false);
    if (result != lines) {
        HANDLED.put(result, Boolean.TRUE);
    }
    return result;
}
```

  （更乾淨的做法是移除 `mixin-1.20-1.21/.../ScreenMixin.java` 並同步刪掉 `1.21`、`26.x`、NeoForge 四份 metadata 裡的 `"ScreenMixin"`；那個檔案本身屬本層，清單屬 a04/a07，故標「與 a04 可能重複」。）

---

### BUG-S-001 · 物品提示路徑留下 System.out 除錯輸出

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`platforms/fabric/shared/rendered-common/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:158-160`、`:190-193`
- **受影響範圍**：所有走 `shared/rendered-common` 的 Fabric 樹（1.14–26.x）與重用該檔的 NeoForge 目標。
- **現象**：遊戲日誌（`latest.log`）會出現 `[MC Auto Translation Tool] Item tooltip producer reached` 與 `Item tooltip translation applied` 兩行，不受 `debug-log` 設定控制，也不帶時間戳與級別。
- **觸發條件**：第一次顯示任何物品提示。
- **根因**：`translateItemTooltip` 與 `translateTooltip(..., true)` 各用一個 `AtomicBoolean` 包住 `System.out.println`，是當初為了確認「哪一條注入路徑先到」留下的診斷碼；`RenderedTextBridge` 沒有 logger，於是直接寫 stdout。它與 BUG-M-008 是同一次除錯的殘留。
- **影響**：繞過專案的 `DebugLog`/`Logger` 通道與它的敏感字遮蔽，也讓使用者回報「mod 在對控制台寫東西」。功能無害但屬死碼。
- **修法**：整段刪掉（診斷目的已由 BUG-M-008 的結論取代），或改用平台的 `Logger` 並掛在 `debugLog()` 開關後面。

```java
// 直接刪除
-    if (ITEM_TOOLTIP_REACHED.compareAndSet(false, true)) {
-        System.out.println("[MC Auto Translation Tool] Item tooltip producer reached");
-    }
```

  同時移除 `RenderedTextBridge.java:20-21` 的兩個欄位與 `:190-193` 的第二段輸出。

---

### BUG-S-002 · 出站訊息「序列化」註解與實作不符

- **嚴重度**：小 ｜ **信心**：高
- **位置**：`platforms/fabric/shared/base/src/main/java/org/universaltranslator/fabric/FabricTranslationRuntimeBase.java:385-403`
- **受影響範圍**：所有 Fabric 樹（`translateOutgoing` 由 `FabricClientBootstrapBase.interceptOutgoingMessage:268-282` 呼叫）。
- **現象**：連續快速送兩句聊天時，兩句的翻譯請求是同時發出的，不是排隊；只是**回傳的 future** 依序完成。
- **觸發條件**：`stream-outgoing=true`（預設）且短時間內送出兩句以上聊天。
- **根因**：`active.translateInteractive(...)` 在 `:395-397` 就被呼叫，請求當場送出；`:398-401` 的 `outgoingTail.handle(...).thenCompose(ignored -> translated)` 串的是**已經在跑**的 future，`translated` 不是延遲建立的 supplier。所以 `outgoingTail` 只保證回呼順序，`:392` 的註解「Serializes outgoing requests so rapidly sent chat lines keep their original order」不成立。
- **影響**：功能上仍能保住送出順序（因為呼叫端本身就是依序呼叫），但 provider 的 `translationWorkers`／rate limit 會被同一個玩家的連續發言同時佔用，且註解會誤導後續維護者以為可以依賴這個鎖。
- **修法**：要嘛改成真正排隊，要嘛把註解改成實情。

```java
// 真正排隊（把「送出請求」延到前一個完成之後）
CompletableFuture<TranslationResult> next = outgoingTail.handle((ignored, failure) -> null)
        .thenCompose(ignored -> active.translateInteractive(
                message, TextKind.CHAT, config.outgoingTargetLanguage, false, config.streamOutgoing));
```

---

### BUG-S-003 · require=0 的用法在 legacy 與 modern 兩套 mixin 之間不一致

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - 有標：`platforms/fabric/shared/text-renderer-legacy/src/main/java/org/universaltranslator/fabric/mixin/TextRendererMixin.java:12-31`（每個 `@ModifyVariable` 都 `require = 0`）、`platforms/fabric/shared/mixin-legacy-1.0-1.12/.../TextFieldWidgetMixin.java:12,17`、`platforms/fabric/shared/mixin-legacy-1.0-1.13/.../SignEditScreenMixin.java:12,17`、`.../ChatGuiMixin.java:13,18`
  - 沒標：`platforms/fabric/shared/draw-context/src/client/java/org/universaltranslator/fabric/mixin/DrawContextMixin.java:15-61`（7 個注入點）、`platforms/fabric/shared/text-renderer-1.20-1.21/src/client/java/org/universaltranslator/fabric/mixin/TextRendererMixin.java:14-45`（5 個注入點）、`platforms/fabric/shared/mixin-1.16-1.19/.../TextFieldWidgetMixin.java:14-28`
  - 生效的預設值：`platforms/fabric/1.21/shared/common/src/main/resources/universal-translator.fabric.mixins.json:16-18`（`"defaultRequire": 1`）、`platforms/neoforge/shared/metadata/*/universal-translator.neoforge.mixins.json:18-20`（同）
- **受影響範圍**：Fabric 1.14–26.x、NeoForge 全部 metadata 層。
- **現象**：某一版 Minecraft 只要把 `DrawContext.drawTooltip` 的參數多加一個、或把 `TextRenderer.drawInternal` 的簽名改掉，遊戲在啟動階段就 `MixinApplyError: Critical injection failure` 直接崩潰，而不是「這一版少了 tooltip 翻譯」的降級。
- **觸發條件**：任何目標版本的被注入方法描述子變動（1.21.2 的 `drawTooltip` 去掉 `Optional` 就是這類改動）。
- **根因**：legacy 那一套刻意全部標 `require = 0`（因為 1.8–1.12 的 `draw`/`drawLayer`/`render` 各版本不同），modern 這一套則完全沒標，於是吃 mixin json 的 `defaultRequire: 1`。兩套策略對比之下，modern 的嚴格程度是「簽名一動就崩」，而 legacy 是「找不到就跳過」。
- **影響**：新增版本或載入器時，失敗模式從「功能缺一塊」變成「整個 mod 起不來」，排查成本高（錯誤訊息只指到 mixin 名稱）。
- **修法**：對「跨版本共用、而各版本簽名已知會漂」的注入點統一補 `require = 0`；只有「這個方法一定存在，不存在就是嚴重錯誤」的點才保留 `defaultRequire`。

```java
@ModifyVariable(
        method = "drawTooltip(Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;Ljava/util/Optional;II)V",
        at = @At("HEAD"), argsOnly = true, ordinal = 0,
        require = 0)
private List<Text> universalTranslator$translateTooltipLines(List<Text> lines) {
```

---

### BUG-S-004 · 本地聊天守衛用「子字串」判定，會吞掉伺服器訊息

- **嚴重度**：小 ｜ **信心**：中
- **位置**：`platforms/fabric/shared/legacy-common/src/client/java/org/universaltranslator/fabric/FabricLocalTextGuard.java:77-86`、`platforms/fabric/shared/guard-legacy/src/main/java/org/universaltranslator/fabric/FabricLocalTextGuard.java:77-86`（兩份逐字相同）
- **受影響範圍**：Fabric 1.8–1.13（`guard-legacy`）與 1.14+（`legacy-common`），也就是全部 Fabric 樹。
- **現象**：把聊天框打開、正在打一句較長的話時，畫面上其他伺服器訊息（長度 ≥2 且剛好是已輸入內容的子字串）不會被翻譯。
- **觸發條件**：`currentScreen instanceof ChatScreen` 且輸入框內容含某條待翻譯字串。例如正在打 `hello world`，此時 HUD/聊天裡的 `world`、`hello`、`o w` 全部被跳過。
- **根因**：`:85` `return visible.length() >= 2 && typed.contains(visible);` —— 用「已輸入文字包含待翻譯文字」當成「這就是我正在打的字」。真正的判定只該是「這串字**就是**輸入框內容的（含游標 `_` 的）呈現」。
- **影響**：只在聊天框開著時發生，且只影響正好被涵蓋的子字串，屬偶發、可繞過（關掉聊天框即恢復）。但玩家會覺得「有些字就是不翻」。
- **修法**：拿掉子字串那條，只留全等與游標變體；若真的要支援「輸入中的部分文字」，改用輸入框的選取範圍做前後綴比對。

```java
private static boolean matches(String typed, String rendered) {
    if (typed == null || typed.isEmpty()) {
        return false;
    }
    String visible = TranslationTextStyling.stripLegacyFormatting(rendered);
    return visible.equals(typed) || visible.equals(typed + "_") || visible.equals(typed + "|");
}
```

---

### BUG-S-005 · validateProviderConfiguration 對預設的離線 provider 什麼都沒驗

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - `platforms/fabric/shared/settings/modern/src/main/java/org/universaltranslator/fabric/FabricConfig.java:370-375`（與 `settings/legacy` 同檔同號）
  - `platforms/fabric/shared/settings/*/fabric/FabricConfig.java:398-407`（`createProvider` → `LlamaCppOfflineProvider.forModel`）
  - `translator-core/src/main/java/org/universaltranslator/core/provider/LlamaCppOfflineProvider.java:118-168`（`forModel` 與建構子只做欄位賦值與 `requireSimpleName`，不啟動伺服器、不碰模型檔）
- **受影響範圍**：所有平台的預設設定（`provider=offline`，`FabricConfig.defaults():439`）。同一段程式碼在 Forge/NeoForge 的 `ForgeConfig` 也一樣。
- **現象**：`validateProviderConfiguration()` 回傳成功，使用者以為設定已被驗證，但下一個畫面／下一次 `initialize` 才失敗。
- **觸發條件**：`provider=offline`（預設值）且 `offline-model` 指向一個不存在的目錄／模型檔無法寫入時。
- **根因**：`FabricClientBootstrapBase.toggleFromKey():175-177` 與 `FabricTranslationRuntimeBase.applyHomeSettings():462-464` 都把「先驗證、後換上」當成安全網，但 `createProvider()` 在 offline 分支只呼叫 `LlamaCppOfflineProvider.forModel(...)`，那個方法（`LlamaCppOfflineProvider.java:118-133`）純粹是 `new`，真正的 `startServer(token)` 在 `:229` 的延遲路徑才發生。於是離線路徑的「驗證」永遠成功。
- **影響**：安全網對預設設定形同不存在；`initialize` 仍會拋錯並被 `catch` 回滾，所以不會讓客戶端完全沒有翻譯，但使用者拿到的錯誤訊息會晚一步、也更難懂。
- **修法**：讓驗證對離線 provider 至少檢查目錄可寫與模型檔是否存在（不啟動伺服器），或在 offline 分支明說不驗證並直接在 `initialize` 做。

```java
if ("offline".equalsIgnoreCase(provider)) {
    Path model = offlineDirectory.resolve(offlineModel.modelFile());
    if (!offlineAutoDownload && !Files.isRegularFile(model)) {
        throw new IOException("Offline model is missing: " + model.getFileName());
    }
    return; // 其餘由 initialize 的建構流程負責
}
```

---

### BUG-S-006 · 設定鍵的兩條入口守衛不一致（tick 路徑缺聊天畫面檢查）

- **嚴重度**：小 ｜ **信心**：中
- **位置**：`platforms/fabric/shared/base/src/main/java/org/universaltranslator/fabric/FabricClientBootstrapBase.java:151-159`（tick 路徑）、`:200-215`（`openSettingsFromKey`，有守衛）、`:218-227`（`openSettingsFor`，沒有守衛）
- **受影響範圍**：Fabric 1.14–1.21（走 `onClientTick` 的樹）；Ornithe 1.8–1.13 走 `openSettingsFromKey`，行為正確。
- **現象**：設定畫面會在聊天輸入中、或其他本該被擋下的畫面被打開。
- **觸發條件**：`openSettingsKeyPressed()` 回傳 true 的那一幀，`currentScreen()` 是聊天畫面或正處於文字輸入狀態。
- **根因**：`:157-159` 的 tick 迴圈直接呼叫 `openSettingsFor(currentScreen())`，而 `openSettingsFor`（`:218-221`）只擋 `instanceof UniversalTranslatorConfigScreen`；`isChatScreen(screen)` 與 `TranslationRenderContext.isTextInput()` 這兩個守衛只寫在 `openSettingsFromKey()`（`:209-213`）。`:204-205` 的註解說「The modern generations use openSettingsFor(Object) directly」，等於承認現代樹跳過了那兩個檢查，但沒有說明為什麼可以跳過。`isChatScreen` 的預設實作是 `return false`（`:99-101`），所以即使把檢查搬進 `openSettingsFor` 也必須先讓現代樹覆寫它。
- **影響**：多數版本因為鍵位在畫面開啟時不會被餵給 `KeyMapping`，實際很難踩到；但這是行為上的兩套規則，一旦某版把鍵位讀取搬進 tick（1.20+ 的 `KeyMapping.consumeClick` 在某些 UI 狀態下仍會被餵事件），就會在打字打到一半時跳出設定畫面並丟掉輸入。
- **修法**：把守衛下沉到 `openSettingsFor`，讓兩條入口共用同一套規則。

```java
final void openSettingsFor(Object parent) {
    if (parent instanceof UniversalTranslatorConfigScreen
            || isChatScreen(parent)
            || TranslationRenderContext.isTextInput()) {
        return;
    }
    ...
}
```

  並讓現代樹覆寫 `isChatScreen(Object screen) { return screen instanceof ChatScreen; }`。

---

## 已排查但判定無問題

1. **`settings/modern` 與 `settings/legacy` 兩份 `FabricConfig.java` 沒有漂移**：`Compare-Object` 對兩檔逐行比對結果為 0 差異（皆 529 行），欄位順序、`defaults()` 的鍵與預設值、`toProperties()` 的寫回順序、`load()` 的 `config-version` 遷移邏輯、`withSettings(...)` 的 23 個參數完全一致。唯一差別是 Java 檔頭 import 的排列，屬編譯無關順序。`parseBoundedInt`／`boundedKeywords`／`save()` 的 atomic move 與 `LocalConfigSecurity` 呼叫也逐字相同。
2. **`TranslationStats.global().attach()` 在每次 `FabricConfig.load()` 都被呼叫，但不會重複讀檔**：`TranslationStats.java:103-115` 在 `lock` 內先判斷 `file != null` 就 `return`，只有第一次會 `load()` + `startFlusher()`。設定畫面反覆開關不會造成重複的磁碟 I/O 或第二個 flusher 執行緒。
3. **NeoForge metadata 的 mixin 清單與 `AGENTS.md` 分層表一致**：`1.20-1.20.4`／`1.20.6` 沒有 `InGameHudStatusIndicatorMixin`，`1.21` 有，`26.x` 有 `InGameHudStatusIndicatorMixin` 但沒有 `ChatHudContextMixin`／`PlayerListHudContextMixin`——與表上「1.20.x 沒有 InGameHudStatusIndicatorMixin，26.x 沒有 ChatHudContextMixin 與 PlayerListHudContextMixin」逐項相符。
4. **NeoForge 四份 metadata 列出的每個 mixin 類別都真的存在於被引用的 Forge 層**：`1.20-1.20.4` 的 10 個類別都能在 `platforms/forge/modern/1.20.1/.../mixin/` 找到；`1.21` 的 11 個在 `platforms/forge/1.21/shared/common` + `1.21-1.21.5` + `draw-context/1.21.6-1.21.11`；`26.x` 的 9 個在 `forge/26.x/shared/common` + `api/26.1-26.1.2`（或 `api/26.2`）。沒有「清單寫了但類別不存在」的情形。
5. **Mod Menu stub 的打包策略是對的**：`platforms/fabric/modmenu/modmenu.gradle:22-25` 把 `api/<family>/src/main/java` 與 `integration/<family>/src/client/java` 掛進 client source set，`:27-29` 再把 `com/terraformersmc/modmenu/**` 從 `jar`／`remapJar`／`sourcesJar`／`remapSourcesJar` 排除。`ModMenuIntegration`（`org.universaltranslator.fabric`）留在 JAR 裡，只有 Mod Menu 自己在查 `modmenu` entrypoint 時才會載入它，未安裝 Mod Menu 時不會觸發 `NoClassDefFoundError`。
6. **`1.20.1` 的 NeoForge 模組用 manifest `MixinConfigs` 而非 toml `[[mixins]]` 是正確的**：`platforms/neoforge/1.20.1/build.gradle:91-95` 在 `jar` 的 manifest 寫 `MixinConfigs`，同時 `:67-70` 的 `mixin { }` 區塊產生 refmap；`mods.toml` 因此不需要 `[[mixins]]`。其餘 NeoForge 目標（20.2+）改用 `[[mixins]] config="universal-translator.neoforge.mixins.json"`，兩者各自符合該版 FML 的讀取方式，只是風格不統一，不算缺陷。
7. **`1.20.2`／`1.20.4` 用 `META-INF/mods.toml` 而 `1.20.6` 之後用 `META-INF/neoforge.mods.toml` 不會導致讀不到**：`platforms/neoforge/shared.gradle:194-199` 對兩個檔名都做 `expand "version"`，而 NeoForge 20.2+ 對這兩份檔名都支援（優先讀 `neoforge.mods.toml`，其次相容 `mods.toml`）。所有 toml 都有驗證器要求的 `modLoader="javafml"`。
8. **`text-renderer-legacy` 的 `require = 0` 讓 1.8–1.12 的 `draw`／`drawLayer`／`getWidth` 任一名稱缺失時靜默跳過，不會崩潰**：`platforms/fabric/shared/text-renderer-legacy/.../TextRendererMixin.java:12-31` 的兩個 `@ModifyVariable` 都用 `method = {...}` 陣列形式 + `require = 0`，符合該層跨 1.8.9–1.12.2 多個 mappings 的實際情況。（modern 對應層的相反做法見 BUG-S-003。）
