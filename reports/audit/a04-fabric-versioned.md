# a04-fabric-versioned 審計報告

範圍：`platforms/fabric` 的**版本目錄** — `1.0-1.8`、`1.8-1.12`、`1.0-1.12`、`1.13`、`1.14-1.15`、`1.16`、`1.17-1.18`、`1.19`、`1.20`、`1.21`、`26.x`、`legacy`（247 個 `.java` / `fabric.mod.json` / `*.mixins.json` / `*.gradle`）
配額：BUG-M ×2（009–010）、BUG-S ×8（007–014）
產出：BUG-C 0 / BUG-M 2 / BUG-S 8（實際）

**方法**：先把同名類別在各版本目錄裡的份數盤點出來（`RenderedTextBridge` ×4、`FabricTranslationRuntime` ×5、`UniversalTranslatorFabricClient` ×11、`TextRendererMixin` ×10、`InGameHudStatusIndicatorMixin` ×12、`TranslationRenderContext` ×1（另 2 份在 `shared/`，屬 a03）、`FabricLocalTextGuard` ×4、`OrnitheClientAccess` ×4、`fabric.mod.json` ×87、`*.mixins.json` ×24），再逐組 `Compare-Object` 對照，最後用各樹的 Gradle 黏合層（`legacy/shared.gradle`、`1.21/shared.gradle`、`26.x/shared.gradle`、`1.13/{ornithe,legacy}.gradle`、`1.0-1.8/shared.gradle`、`1.8-1.12/shared.gradle`）反推「哪一份會被哪個 MC 版本編譯」，確認差異是「刻意分層」還是「漂移」。
**排除**：`platforms/fabric/shared/**`（a03）、巨型 `*Screen*.java` 的版面與互動（a06）、`bundles/`、`modmenu/`。

---

### BUG-M-009 · 為 1.13–26.x 補回 TranslationRenderContext 的每 tick 復原

- **嚴重度**：中 ｜ **信心**：高
- **位置**：
  - 唯一存在 `reset()` 的副本：`platforms/fabric/shared/render-context-legacy/src/main/java/org/universaltranslator/fabric/TranslationRenderContext.java:37-48`
  - 唯一的兩個呼叫點：`platforms/fabric/1.0-1.8/shared/common/src/main/java/org/universaltranslator/fabric/mixin/MinecraftMixin.java:21-25`、`platforms/fabric/1.8-1.12/shared/common/src/main/java/org/universaltranslator/fabric/mixin/MinecraftMixin.java:20-24`
  - 完全沒有 `reset()` 的兩份副本（1.13–26.3 實際使用）：`platforms/fabric/1.13/shared/shared-both/src/main/java/org/universaltranslator/fabric/TranslationRenderContext.java:9-60`、`platforms/fabric/shared/common/src/client/java/org/universaltranslator/fabric/TranslationRenderContext.java:9-61`
  - 沒有 try/finally 保護的 push/pop 配對（洩漏源）：`platforms/fabric/shared/mixin-1.16-1.19/src/client/java/org/universaltranslator/fabric/mixin/TextFieldWidgetMixin.java:14-28`、`.../mixin/ChatHudContextMixin.java:17`、`platforms/fabric/1.13/shared/common/src/main/java/org/universaltranslator/fabric/mixin/TextFieldWidgetMixin.java:12-20`、`platforms/fabric/1.21/shared/common/src/client/java/org/universaltranslator/fabric/mixin/InGameHudContextMixin.java:18,32,46,58,70`、`platforms/fabric/1.20/shared/chat-hud/1.20-1.20.4/src/client/java/org/universaltranslator/fabric/mixin/ChatHudContextMixin.java:30`、`platforms/fabric/26.x/shared/api/26.1-26.1.2/src/client/java/org/universaltranslator/fabric/mixin/InGameHudContextMixin.java:20,36,52,68,84`
- **受影響範圍**：Fabric **1.13.0、1.13.1、1.13.2、1.14–1.14.4、1.15–1.15.2、1.16–1.16.5、1.17–1.17.1、1.18–1.18.2、1.19–1.19.4、1.20–1.20.6、1.21–1.21.11、26.1、26.1.1、26.1.2、26.2、26.3**（也就是 1.0–1.12 以外的**全部** Fabric 目標）。Forge / NeoForge 的 `TranslationRenderContext`（`platforms/forge/shared/common/...`）同樣沒有 `reset()`，屬 a05 範圍。
- **現象**：翻譯一旦在某一次渲染例外後停擺，就**不會自己恢復**，直到玩家重開遊戲。HUD 狀態指示、聊天、記分板、玩家列表、標題、物品提示全部停在原文；設定畫面顯示翻譯仍為「開」。
- **觸發條件**：任何被注入的 HUD／輸入渲染方法在 `@At("HEAD")` 的 `push` 之後、`@At("RETURN")` 的 `pop` 之前丟出例外。最少見但後果最重的是 `TextFieldWidget.renderButton` / `TextFieldWidget.render(IIF)V`：`platforms/fabric/shared/mixin-1.16-1.19/.../TextFieldWidgetMixin.java:19` 先 `pushTextInput()`，`:27` 才 `popTextInput()`，中間沒有 try/finally。只要該次渲染丟一次例外，`TEXT_INPUT_DEPTH` 就永久 > 0。
- **根因**：`TranslationRenderContext.pop()`（`shared/common/...:27-35`）只在正常 RETURN 路徑被呼叫；Mixin 0.8.x 沒有例外退出注入點，所以 HEAD/RETURN 配對天生會漏。1.0–1.12 的副本用 `reset()` 把兩個 ThreadLocal 整個 `remove()`（`render-context-legacy/...:45-48`，其 javadoc 已明寫「…would silently disable translation for the rest of the session」），並在每次 client tick 的 RETURN 呼叫它。1.13 起三份新副本（`1.13/shared/shared-both`、`shared/common`）是從舊檔重寫的，`reset()` 與 tick 呼叫點**都沒有搬過去**，`MinecraftMixin` 也被各世代的 `FabricClientBootstrapBase.registerTickHook` 取代，於是整條復原路徑消失。
- **影響**：`RenderedTextBridge.translateRaw`（如 `platforms/fabric/26.x/shared/common/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:198-204`）第一行就是 `if (TranslationRenderContext.isTextInput()) return text;`。depth 一旦卡住，這個判斷永遠成立 → 之後**每一次**文字渲染都原封不動回傳原文，是「永久靜默功能全滅」，且沒有任何日誌。`TextKind` 那一半洩漏比較輕（`current()` 之後永遠回報同一個 kind，文字被錯分類但仍在翻）。
- **修法**：把 `reset()` 加進兩份現代副本，並在每個世代已存在的 tick 掛鉤裡呼叫一次。1.16+ 的 tick 掛鉤集中在 `FabricClientBootstrapBase`（`platforms/fabric/shared/base/src/main/java/org/universaltranslator/fabric/FabricClientBootstrapBase.java`），只需改一處；1.13 兩份 `MinecraftMixin` 則照 1.8–1.12 的寫法補。

```java
// platforms/fabric/shared/common/src/client/java/org/universaltranslator/fabric/TranslationRenderContext.java
// （1.13/shared/shared-both 那份加一樣的內容）
/**
 * Clears the classification latched on the current thread. Mixin 0.8.x has no exception exit
 * injection point, so a render method that throws before its RETURN injection leaves a stale
 * {@link TextKind} and a text-input depth above zero forever.
 */
public static void reset() {
    KINDS.remove();
    TEXT_INPUT_DEPTH.remove();
}
```

```java
// platforms/fabric/shared/base/src/main/java/org/universaltranslator/fabric/FabricClientBootstrapBase.java
// tick 回呼（各世代已註冊）開頭：
TranslationRenderContext.reset();
```

```java
// 1.13 兩份 MinecraftMixin 的 tick 注入，照 1.8-1.12 的既有寫法：
@Inject(method = "tick()V", at = @At("RETURN"), require = 0)
private void universalTranslator$pollKeys(CallbackInfo callback) {
    TranslationRenderContext.reset();   // ← 先復原，再處理按鍵
    ...
}
```

並在 `shared/mixin-1.16-1.19/.../TextFieldWidgetMixin.java` 這類配對外層包 try/finally，讓正常路徑不必依賴 tick 補救。

---

### BUG-M-010 · 讓 1.0-1.8 bundle 的 metadata 只宣告實際建置的 26 個版本

- **嚴重度**：中 ｜ **信心**：高
- **位置**：
  - 宣告 38 個巢狀 JAR：`platforms/fabric/1.0-1.8/bundle/src/main/resources/fabric.mod.json:16-131`
  - 宣告 38 個 MC 版本：`platforms/fabric/1.0-1.8/bundle/src/main/resources/fabric.mod.json:134-173`（`depends.minecraft` 陣列；`:175` 另有 `"universal_translator": "*"`）
  - 自稱版本範圍：同檔 `:3`（`id = universal_translator_1_0_0_1_8_8_bundle`）、`:5`、`:6`
  - 實際只建 26 個：`platforms/fabric/1.0-1.8/bundle/build.gradle:11`（`archivesName = ...-1.3.1-1.8.8`）、`:23`（`supportedVersions` 26 筆）、`:34-37`（只把 26 顆放進 `META-INF/jars`）
  - 對照 `settings.gradle:22-47`（只註冊 26 個 `platform-fabric-1.x`）、`settings.gradle:167`（bundle 的 `supportedVersions` 一樣 26 筆）
- **受影響範圍**：`1.0.0`、`1.0.1`、`1.1`、`1.2.1`、`1.2.2`、`1.2.3`、`1.2.4`、`1.2.5`、`1.6.2`、`1.7.3`、`1.7.5`、`1.7.7`（共 12 個被上游 mappings 排除的版本）；bundle 本身宣稱覆蓋 1.0.0–1.8.8（38 個），實際只覆蓋 1.3.1–1.8.8（26 個）
- **現象**：玩家在這 12 個版本上會看到 `Mod Resolution failed`（`universal_translator` 找不到提供者），遊戲起不來；同時 `jars` 陣列裡有 12 筆指向不存在的 `META-INF/jars/universal-translator-1.0.0.jar` 之類。
- **觸發條件**：把 `fabric-1.0-1.8.x` bundle 放進 1.0.0–1.2.5 / 1.6.2 / 1.7.3 / 1.7.5 / 1.7.7 的 `mods/`。`depends.minecraft` 那 38 筆會讓 Loader 認為這顆 bundle「適用」，但 bundle 內沒有對應版本的實作 jar，`depends.universal_translator: "*"` 因此無解。
- **根因**：`supportedVersions` 在 `build.gradle:23` 被縮成 26 筆（AGENTS.md 記錄的「12 個版本上游 mappings 有問題」），但 `fabric.mod.json` 的 `jars` 與 `depends.minecraft` 兩份 38 筆清單是手寫的，沒有跟著改；`id`/`name`/`description` 也還停在 1.0.0–1.8.8。`verifyBundle`（`build.gradle:41-50`）只逐筆檢查 `supportedVersions` 那 26 個，反過來「metadata 多列了 12 個」永遠不會被抓到；`verifyLoaderSelection`（`:52-57`）也只把 26 個版本當參數傳進 `BundleResolutionVerifier`。
- **影響**：12 個版本的 bundle 路徑直接失效；另外 `id` 與 `name` 對外宣稱支援 1.0.0 起，與實際內容不符，玩家無從判斷該退回單版本 jar。
- **修法**：把 `jars`、`depends.minecraft` 換成 `supportedVersions` 那 26 筆，`id`/`name`/`description` 改成 1.3.1–1.8.8；再在 `verifyBundle` 補一條反向檢查，讓 metadata 與 `supportedVersions` 不可能再脫鉤。

```groovy
// platforms/fabric/1.0-1.8/bundle/build.gradle，接在既有 verifyBundle 的 doLast 內
def metadata = new groovy.json.JsonSlurper().parse(
        file("src/main/resources/fabric.mod.json"))
def declaredJars = metadata.jars.collect { it.file.tokenize('/').last()
        .replace('universal-translator-', '').replace('.jar', '') }
def declaredMc = metadata.depends.minecraft
if (declaredJars != supportedVersions) {
    throw new GradleException("fabric.mod.json jars ${declaredJars} != supportedVersions ${supportedVersions}")
}
if (declaredMc != supportedVersions) {
    throw new GradleException("fabric.mod.json depends.minecraft ${declaredMc} != supportedVersions ${supportedVersions}")
}
```

---

### BUG-S-007 · 修正 1.8–1.8.8 掛錯 shared.gradle（1.6-1.8 層的 renderHotbar 分支成死碼）

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - 1.8–1.8.8 掛的是 `1.8-1.12` 的黏合層：`platforms/fabric/1.0-1.8/versions/1.8/build.gradle:10`、`1.8.1/build.gradle:10`、`1.8.2`–`1.8.8/build.gradle:10`（共 9 個）
  - 1.6.1–1.7.10 掛的是自己的層：`platforms/fabric/1.0-1.8/versions/1.7.10/build.gradle:10`（`1.0-1.8/shared.gradle`）
  - 因為掛錯而永遠取不到的分支：`platforms/fabric/1.0-1.8/shared/family/1.6-1.8/src/main/java/org/universaltranslator/fabric/mixin/InGameHudStatusIndicatorMixin.java:25-31`（`renderHotbar(Lnet/minecraft/client/render/Window;F)V`）
  - 被誤導的註解：`.../1.6-1.8/.../InGameHudStatusIndicatorMixin.java:10-17`、`.../1.0-1.8/shared/common/src/main/java/org/universaltranslator/fabric/mixin/HudIndicatorOverlay.java:19-22`、`platforms/fabric/1.0-1.8/shared.gradle:14-17`
- **受影響範圍**：Fabric **1.8、1.8.1、1.8.2、1.8.3、1.8.4、1.8.5、1.8.6、1.8.7、1.8.8**（9 個版本）
- **現象**：`1.0-1.8/shared.gradle:14-17` 的 `playerFamily` 對 `1.8.x` 會算出 `"1.6-1.8"`，但這 9 個版本**從來不套用那份檔案**，所以 `platforms/fabric/1.0-1.8/shared/family/1.6-1.8/` 下的兩個 mixin（`InGameHudStatusIndicatorMixin`、`LocalClientPlayerEntityMixin`）一次都沒被 1.8.x 編進去；`HudIndicatorOverlay` 也不會進 1.8.x 的 jar。
- **觸發條件**：任何針對 1.8.x HUD 狀態指示的修改。改 `1.6-1.8/.../InGameHudStatusIndicatorMixin.java` 對 1.8.x 完全無效，因為 1.8.x 走的是 `1.8-1.12/shared/common` 的 163 行版本。
- **根因**：`1.8` 同時被兩棵樹認領 —— 目錄在 `1.0-1.8/versions/`，`settings.gradle` 也把它放在 `platform-fabric-1.0-1.8.x` bundle 的 `supportedVersions`（`settings.gradle:167`），但 `build.gradle:10` 套的是 `1.8-1.12/shared.gradle`（與 `1.8-1.12/versions/1.8.9/build.gradle:10` 完全相同）。於是 `1.0-1.8/shared.gradle:14-17` 的 `startsWith("1.8")` 分支、`1.6-1.8` 目錄名、以及「1.8 走 `renderHotbar(Window,F)`」的註解三處全部成為死描述；該 `@Inject` 因為 `require = 0` 也不會報錯，完全靜默。
- **影響**：功能上不缺失（`1.8-1.12/shared/common` 的同名 mixin 覆蓋 1.8.x），但這是「改對地方卻沒效果」的高成本陷阱，而且讓 1.0-1.8 樹多了一個從未被編譯的分支，任何回歸測試都看不到。
- **修法**：二選一並寫進註解 —— (a) 承認 1.8.x 屬 1.8-1.12 樹，把 `1.0-1.8/versions/1.8*` 移到 `1.8-1.12/versions/`、`playerFamily` 移除 `1.8` 分支、`1.6-1.8` 目錄改名 `1.6-1.7`、刪掉死掉的 `renderHotbar` 注入；(b) 若真要讓 1.8.x 走 1.0-1.8 樹，把 9 個 `build.gradle:10` 改成 `1.0-1.8/shared.gradle` 並處理 `1.8-1.12` 側的 `ChatGuiMixin` 等層差異。推薦 (a)，改動最小且與現況一致。

---

### BUG-S-008 · 讓 mixins.json 的 compatibilityLevel 跟上 legacyJavaRelease

- **嚴重度**：小 ｜ **信心**：中
- **位置**：
  - `platforms/fabric/1.17-1.18/shared/family/1.17-1.18-common/src/main/resources/universal-translator.fabric.mixins.json:4`（`"JAVA_8"`）
  - 對應的編譯級別：`platforms/fabric/1.17-1.18/versions/1.17/build.gradle:9`（`legacyJavaRelease = 17`）、`1.17.1`、`1.18`、`1.18.1`、`1.18.2` 同
  - `platforms/fabric/1.20/shared/family/1.20.1/src/main/resources/universal-translator.fabric.mixins.json:4`（`"JAVA_17"`）
  - 對應的編譯級別：`platforms/fabric/1.20/versions/1.20.5/build.gradle:9`、`1.20.6/build.gradle:9`（`legacyJavaRelease = 21`）；層指派 `platforms/fabric/legacy/shared.gradle:63-64` 仍讓 1.20.5/1.20.6 收 1.20.1 的 resources
  - 抓不到的原因：`build.gradle:58-65` 的 `validateMixinConfigs` 只驗**上界**
- **受影響範圍**：Fabric **1.17、1.17.1、1.18、1.18.1、1.18.2**（宣告 JAVA_8 / 實際 Java 17）與 **1.20.5、1.20.6**（宣告 JAVA_17 / 實際 Java 21）
- **現象**：mixin 類別的 class 檔版本（major 61 / 65）高於 config 宣告的等級（52 / 61）。
- **觸發條件**：啟動這 7 個版本時載入 mixin config。
- **根因**：`compatibilityLevel` 在整個 repo 的其他 17 份 config 裡都等於該樹的 `options.release`（1.16 = JAVA_8/Java 8、1.19 = JAVA_17/Java 17、1.21 = JAVA_21/Java 21、`platforms/neoforge/shared/metadata/1.20.6` = JAVA_21、`platforms/forge/modern/1.18.2` = JAVA_17），唯獨 1.17-1.18 是從 1.16 那份整份複製過來而沒改，1.20.5/1.20.6 則是因為升 `legacyJavaRelease` 時沒有新增一份 metadata 層。`validateMixinConfigs` 的 `maximumLevel` 由路徑子字串推導（`build.gradle:58-60`），只擋「太高」，所以這兩個「太低」一路綠燈。
- **影響**：若 Mixin 以 compatibilityLevel 當作可接受的 class 版本上限（Repo 自己在 `build.gradle:53` 寫「the bundled Mixin 0.8.7 runtime supports at most JAVA_21」就是以它為上限），這 7 個版本的 mixin 會在 config 載入階段被拒，等同整個 mod 的翻譯 hook 全部失效。**不確定的地方**：本審計不能啟動遊戲，無法斷定 Mixin 對「class 版本 > compatibilityLevel」是硬失敗還是只警告，故信心標中、嚴重度標小。
- **修法**：1.17-1.18 的 `:4` 改 `"JAVA_17"`；1.20.5/1.20.6 在 `platforms/fabric/1.20/shared` 下新增 `family/1.20.5-1.20.6/src/main/resources/universal-translator.fabric.mixins.json`（內容同 1.20.1，`compatibilityLevel` 改 `JAVA_21`），把 `legacy/shared.gradle:63-64` 的 `family/1.20.1` 換成新層。並在 `validateMixinConfigs` 補一條下界檢查（config 的等級不得低於該專案的 `legacyJavaRelease` / `options.release`），把這類漂移變成 CI 紅燈。

```groovy
// build.gradle — 現有的 validateMixinConfigs 是 root task，拿不到子專案的 ext；
// 下界檢查要放在 subprojects 的 afterEvaluate 裡（或把 config 路徑對應到專案名）。
subprojects { subproject ->
    subproject.pluginManager.withPlugin("java") {
        subproject.afterEvaluate {
            def requiredRelease = subproject.ext.has("legacyJavaRelease")
                    ? subproject.ext.legacyJavaRelease
                    : (subproject.tasks.withType(JavaCompile).first().options.release.orNull ?: 21)
            def requiredLevel = "JAVA_" + requiredRelease
            fileTree(subproject.projectDir).matching { include "src/**/*.mixins.json" }.each { cfg ->
                def level = new groovy.json.JsonSlurper().parse(cfg).compatibilityLevel
                if (level != null
                        && compatibilityLevels.indexOf(level) < compatibilityLevels.indexOf(requiredLevel)) {
                    throw new GradleException(
                            "Mixin compatibilityLevel '${level}' is below the compile target " +
                                    "${requiredLevel} for ${subproject.name}/${cfg.name}")
                }
            }
        }
    }
}
```

---

### BUG-S-009 · 移除 RenderedTextBridge 裡殘留的 System.out.println

- **嚴重度**：小 ｜ **信心**：高
- **位置**（同一段除錯輸出在 5 份副本裡各有一對）：
  - `platforms/fabric/1.14-1.15/shared/family/1.14-1.15-common/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:47`、`:80`
  - `platforms/fabric/1.16/shared/family/1.16-1.16.1/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:76`、`:109`
  - `platforms/fabric/legacy/shared/rendered/1.16.5/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:160`、`:193`
  - `platforms/fabric/26.x/shared/common/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:160`、`:193`
  - （**與 a03 可能重複**）`platforms/fabric/shared/rendered-common/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:159`、`:192`
  - 同類殘留：`platforms/fabric/1.20/shared/family/1.20.1/src/client/java/org/universaltranslator/fabric/ChatImageCompatibility.java:37`
- **受影響範圍**：Fabric **1.14–1.15.2、1.16–1.16.5、1.17–1.18.2、1.19–1.19.4、1.20–1.20.6、1.21–1.21.11、26.1–26.3**（所有走 `RenderedTextBridge` 的現代目標）
- **現象**：`latest.log` 裡會出現 `[MC Auto Translation Tool] Item tooltip producer reached` 與 `[MC Auto Translation Tool] Item tooltip translation applied` 兩行，一次性（由 `AtomicBoolean.compareAndSet` 保護）；裝了 ChatImage 時再多一行 `Enabled ChatImage chat-component compatibility`。
- **觸發條件**：第一次打開任一帶 `getTooltipFromItem` 的畫面（背包、容器）。
- **根因**：`ITEM_TOOLTIP_REACHED` / `ITEM_TOOLTIP_APPLIED` 這兩個 `AtomicBoolean` 是當初確認「tooltip 注入點到底有沒有被執行到」的臨時探針，`ChatImageCompatibility.REPORTED` 同理；功能驗證完成後只留了旗標卻沒拔掉輸出。這些類別沒有 Logger，所以直接用了 `System.out`；但專案其他地方的 HUD/Chat 訊息都走 `LOGGER`/`info()`。
- **影響**：三行雜訊進 `latest.log`；`System.out` 繞過 log4j 的等級與 appender，玩家回報問題時貼的 log 會混進看起來像錯誤的訊息。無功能影響。
- **修法**：整段連同 `AtomicBoolean` 一起刪；若要保留可觀測性，改成 `LOGGER.debug(...)`（各 `RenderedTextBridge` 需先引入 logger），或只在 `FabricConfig` 的 debug 開關開啟時輸出。

```java
// before
if (ITEM_TOOLTIP_REACHED.compareAndSet(false, true)) {
    System.out.println("[MC Auto Translation Tool] Item tooltip producer reached");
}
return translateTooltip(lines, true);
// after
return translateTooltip(lines, true);   // 刪掉 ITEM_TOOLTIP_REACHED / ITEM_TOOLTIP_APPLIED 兩個欄位與所有輸出
```

---

### BUG-S-010 · 在 1.14–1.15 的 tooltip 翻譯保留每行樣式

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - `platforms/fabric/1.14-1.15/shared/family/1.14-1.15-common/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:28-39`（`translate(Text)`：`new LiteralText(...)` 沒有 `.setStyle(...)`）
  - 同檔 `:52-83`（`translateTooltip`：`:74-75` 換行時沒有帶 `line.getStyle()`）
  - 對照組（其餘每一棵樹都有帶樣式）：`platforms/fabric/1.16/shared/family/1.16-1.16.1/src/client/java/org/universaltranslator/fabric/RenderedTextBridge.java:50`、`:104`；`platforms/fabric/legacy/shared/rendered/1.16.5/.../RenderedTextBridge.java:52`、`:188`；`platforms/fabric/26.x/shared/common/.../RenderedTextBridge.java:51`、`:188`
- **受影響範圍**：Fabric **1.14、1.14.1、1.14.2、1.14.3、1.14.4、1.15、1.15.1、1.15.2**（8 個版本）
- **現象**：物品提示（`Screen.getTooltipFromItem` → `shared/screen-mixin/.../ScreenMixin.java:24`）被翻譯後變成純白：鑽石劍的名字不再是藍色、附魔書不再依等級變色、藥水效果行不再有顏色。翻譯過的世界文字（名牌、告示牌）同樣丟掉粗體／斜體／點擊事件。
- **觸發條件**：把滑鼠移到任何有稀有度顏色或附魔的 `ItemStack` 上；或伺服器發出帶顏色的事件訊息。
- **根因**：這份副本是唯一一份「重寫前的舊版」。它以 `TranslationTextStyling.applyTranslatedStyle(original, translated, color)`（回傳含 `§` 格式碼的 **String**）重建 `LiteralText`，而沒有走 1.16 之後統一採用的 `translatedStyle(Style)` 路徑 —— 既沒有 `text.getStyle()`/`line.getStyle()`，也沒有 `translateStyledSiblings` / `rebuildStyledRuns` 這兩個保樣式的方法（1.16/1.16.5/26.x 都有）。另外因為 1.14/1.15 的 `TextRendererMixin`（`.../mixin/TextRendererMixin.java:12-30`）四個 hook 全部吃 `String`，`translate(Text)` 這條線在 1.14–1.15 樹裡其實**沒有任何呼叫點**，是純死碼，所以真正被踩到的只有 `translateTooltip`。
- **影響**：樣式／稀有度資訊全失，影響所有 1.14–1.15.2 玩家的物品辨識；同時 1.14–1.15 的 `translate(Text)` / `translateItemTooltip` 的除錯輸出（見 BUG-S-009）也永遠不會印出來，讓「tooltip 到底有沒有走進來」更難判斷。
- **修法**：把 1.16 那套 `translatedStyle(Style)` + `translateStyledSiblings` + `rebuildStyledRuns` 搬進 1.14-1.15-common 的副本（該版本的 API 只有 `LiteralText`/`Text`，`OrderedText` 部分不需要）；`translateTooltip` 至少改成 `new LiteralText(translated).setStyle(translatedStyle(line.getStyle()))`。沒有呼叫點的 `translate(Text)` 連同 `ITEM_TOOLTIP_*` 旗標一併刪。

```java
// platforms/fabric/1.14-1.15/shared/family/1.14-1.15-common/.../RenderedTextBridge.java:74
replacement.set(index,
        new LiteralText(translated).setStyle(translatedStyle(line.getStyle())));
// 並補一個 1.14/1.15 可用的 translatedStyle(Style)：沿用 TranslationStyleRuns.resolveTranslatedColor
```

---

### BUG-S-011 · 讓 1.20.5/1.20.6 重新套用 ChatImage 參數正規化 hook

- **嚴重度**：小 ｜ **信心**：高
- **位置**：
  - 有 hook 的版本：`platforms/fabric/1.20/shared/chat-hud/1.20-1.20.4/src/client/java/org/universaltranslator/fabric/mixin/ChatHudContextMixin.java:15-25`（`@Mixin(value = ChatHud.class, priority = 800)` + `@ModifyVariable` on `addMessage`）
  - 沒有 hook 的版本：`platforms/fabric/1.20/shared/chat-hud/1.20.5-1.20.6/src/client/java/org/universaltranslator/fabric/mixin/ChatHudContextMixin.java:12-27`（只剩兩支 `render` 注入）
  - 因此變成死碼的類別：`platforms/fabric/1.20/shared/family/1.20.1/src/client/java/org/universaltranslator/fabric/ChatImageCompatibility.java:30-40`
  - 層指派：`platforms/fabric/legacy/shared.gradle:63-64`
  - 只在 1.20.1 註冊的自測：`platforms/fabric/1.20/versions/1.20.1/build.gradle:14-32`
- **受影響範圍**：Fabric **1.20.5、1.20.6**
- **現象**：裝了 ChatImage 1.4.7（mod id `chatimage`）時，1.20–1.20.4 會把 `TranslatableTextContent` 的純字串參數正規化成 `Text`，1.20.5/1.20.6 完全不做 —— 也就是說這個相容性修正剛好缺席在 1.20 家族最新的兩個版本上。
- **觸發條件**：1.20.5 或 1.20.6 + ChatImage + 保留大量聊天記錄（`chatimage` 在重建歷史訊息時會碰到非 `Text` 的 `TranslatableTextContent` 參數）。
- **根因**：1.20.5 的 `ChatHud.render` 多了一個 `boolean focused` 參數，於是複製出 `chat-hud/1.20.5-1.20.6` 這一層；複製時只搬了 `render` 的兩支 `TextKind.CHAT` 注入，**漏掉 `addMessage` 的 `ModifyVariable`**，連帶 `@Mixin(..., priority = 800)` 那個「必須排在 ChatImage 之後」的優先序調整也消失了。`ChatImageCompatibility` 本身仍被編進 1.20.5/1.20.6 的 jar（`family/1.20.1` 那一層對所有 1.20.x 都成立），所以沒有任何編譯期提示；自測任務只在 `1.20.1` 註冊，也不會踩到。
- **影響**：`ChatImageCompatibility.java:14-22` 的 javadoc 描述的「restoring a large chat history otherwise throws and logs once per message on the render thread」在 1.20.5/1.20.6 回來；渲染執行緒每訊息一次的例外與日誌，長時間會拖慢聊天視窗。
- **修法**：把 `addMessage` 的 `ModifyVariable` 與 `priority = 800` 複製到 `1.20.5-1.20.6` 那一份（`addMessage` 的簽章在 1.20.5 沒變，仍是 `(Text, MessageSignatureData, MessageIndicator)`），並把 `chatImageCompatibilitySelfTest` 也掛到 `1.20.5`/`1.20.6` 的 `check`，讓三個 1.20 分支都有覆蓋。

```java
// platforms/fabric/1.20/shared/chat-hud/1.20.5-1.20.6/.../ChatHudContextMixin.java
@Mixin(value = ChatHud.class, priority = 800)
abstract class ChatHudContextMixin {
    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"), argsOnly = true)
    private Text universalTranslator$normalizeChatImageArguments(Text message) {
        return ChatImageCompatibility.normalizeArguments(message);
    }
    // …原本兩支 render 注入保留
}
```

---

### BUG-S-012 · 統一 legacy Fabric 對 Fabric API 的 depends 模組 id

- **嚴重度**：小 ｜ **信心**：中
- **位置**：
  - 模板：`platforms/fabric/legacy/shared/common/src/main/resources/fabric.mod.json:28`（`"${fabricDependency}": "*"`）
  - 展開來源：`platforms/fabric/legacy/shared.gradle:347`、`:357`
  - 填 `"fabric"` 的 20 個版本：`platforms/fabric/1.14-1.15/versions/1.14.3/build.gradle:10`、`1.14.4`、`1.15`、`1.15.1`、`1.15.2`；`platforms/fabric/1.16/versions/1.16/build.gradle:10`、`1.16.1`–`1.16.5`；`platforms/fabric/1.17-1.18/versions/1.17/build.gradle:10`、`1.17.1`、`1.18`、`1.18.1`、`1.18.2`；`platforms/fabric/1.19/versions/1.19/build.gradle:10`、`1.19.1`
  - 填 `"fabric-api"` 的 10 個版本：`platforms/fabric/1.19/versions/1.19.2/build.gradle:10`、`1.19.3`、`1.19.4`；`platforms/fabric/1.20/versions/1.20`–`1.20.6` 各自 `:10`
  - 對照：`platforms/fabric/1.21/versions/1.21.11/src/main/resources/fabric.mod.json:34`（`"fabric-api": "*"`）、`platforms/fabric/26.x/shared/common/src/main/resources/fabric.mod.json:34`（同）
- **受影響範圍**：Fabric **1.14.3–1.19.1**（宣告 `fabric`）與 **1.19.2–1.20.6**（宣告 `fabric-api`）；1.19 家族內部就一半一半
- **現象**：同一批 legacy 目標對「Fabric API」的 `depends` 用了兩個不同的模組 id；1.19 與 1.19.1 寫 `fabric`，同一棵 1.19 樹的 1.19.2 起改寫 `fabric-api`。
- **觸發條件**：Fabric Loader 在解析 `depends` 時，未提供的 id 會讓 mod 被標成 `requires ... which is missing`。
- **根因**：`legacyFabricDependency` 是逐版本手寫的 `ext` 屬性，沒有單一來源；1.19.2 起有人把它改成 `fabric-api`，前面的 1.14.3–1.19.1 沒回頭改。Fabric API 的模組 id 是 `fabric-api`（1.21/26.x 的 `fabric.mod.json` 也這樣寫），所以 `fabric` 這 20 個版本至少有一邊是錯的。**不確定的地方**：本審計不能啟動遊戲，無法確認舊 loader 是否對 `fabric` 這個 id 有特殊處理，故信心標中。
- **影響**：若 `fabric` 真的不是有效 id，1.14.3–1.19.1 這 20 個 jar 在缺 Fabric API 時不會給出正確提示（或被誤判成缺 `fabric`）；反之若 `fabric` 有效，那 1.19.2–1.20.6 就是另一邊寫錯。兩種情況都會讓玩家看到誤導性的錯誤訊息。
- **修法**：全部改成 `"fabric-api"`（與 1.21/26.x 一致），並把這個值收斂成 `legacy/shared.gradle` 裡的單一預設值，讓各版本 `build.gradle` 只在真的需要不同 id 時才覆寫。

```groovy
// platforms/fabric/legacy/shared.gradle，requiredProperties 檢查之後
if (!project.ext.has("legacyFabricDependency")) {
    ext.legacyFabricDependency = "fabric-api"
}
```

---

### BUG-S-013 · 統一 1.19–1.19.2 送出翻譯訊息的簽章路徑

- **嚴重度**：小 ｜ **信心**：中
- **位置**：
  - `platforms/fabric/1.19/shared/family/1.19.2/src/client/java/org/universaltranslator/fabric/UniversalTranslatorFabricClient.java:106-112`（`:110` `client.player.sendChatMessage(message, null);`）
  - 對照：`platforms/fabric/1.19/shared/family/1.19.3-1.19.4/src/client/java/org/universaltranslator/fabric/UniversalTranslatorFabricClient.java:106-112`（`:111` `client.getNetworkHandler().sendChatMessage(message);`）
  - 層指派：`platforms/fabric/legacy/shared.gradle:48-53`
- **受影響範圍**：Fabric **1.19、1.19.1、1.19.2**
- **現象**：1.19.1 起 Minecraft 導入聊天簽章。1.19–1.19.2 這條路徑明確把 `MessageSignatureData` 傳成 `null`，等於送出**未簽章**訊息；1.19.3+ 走 `ClientPlayNetworkHandler.sendChatMessage(String)`，由遊戲自己補簽章。
- **觸發條件**：在 `enforce-secure-profile=true` 的伺服器（多數線上模式公開伺服器）上送出一句要被翻譯的聊天；1.19/1.19.1/1.19.2 的訊息會被伺服器以聊天驗證失敗拒收，1.19.3+ 正常。
- **根因**：1.19.2 這份是照 1.18 的 `player.sendChatMessage(...)` 寫法硬套新的兩參數多載，補了一個 `null` 讓它編得過；1.19.3 那份重寫時改走 `getNetworkHandler()`。兩份是同一功能的兩代實作，只有後者跟上游一致。
- **影響**：1.19–1.19.2 的「送出翻譯後的訊息」在嚴格伺服器上直接失效（玩家以為送出了、其實被丟掉），而且沒有任何提示。不影響收到的文字翻譯。
- **修法**：把 1.19.2 那份改成與 1.19.3+ 相同的 `getNetworkHandler().sendChatMessage(message)`（`player != null` 與 `networkHandler != null` 都判斷），並把兩份合併到同一層。

```java
@Override
protected void sendChat(String message) {
    ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
    if (handler != null) {
        handler.sendChatMessage(message);
    }
}
```

---

### BUG-S-014 · 補齊 1.13.2 TextRendererMixin 漏掉的 draw 多載

- **嚴重度**：小 ｜ **信心**：中
- **位置**：
  - Ornithe 版（1.13.0/1.13.1）掛 4 支：`platforms/fabric/1.13/shared/common/src/main/java/org/universaltranslator/fabric/mixin/TextRendererMixin.java:12-24`
  - legacy 版（1.13.2）只掛 3 支：`platforms/fabric/1.13/shared/legacy-1.13.2/src/main/java/org/universaltranslator/fabric/mixin/TextRendererMixin.java:12-23`（缺 `draw(Ljava/lang/String;FFI)I`）
  - 靜默原因：`platforms/fabric/1.13/shared/legacy-1.13.2/src/main/resources/universal-translator.fabric.mixins.json:17`（`"defaultRequire": 0`）
  - 建置路徑：`platforms/fabric/1.13/ornithe.gradle:39-61` vs `platforms/fabric/1.13/legacy.gradle:30-47`
- **受影響範圍**：Fabric **1.13.2**（1.13.0/1.13.1 正常）
- **現象**：1.13.0/1.13.1 攔了 `draw(String,float,float,int)`、`drawWithShadow`、`draw(String,float,float,int,boolean)`、`drawLayer(String,float,float,int,boolean)`；1.13.2 只攔後三支。用四參數 `draw` 繪製的文字在 1.13.2 上不會被翻譯。
- **觸發條件**：任何走 `TextRenderer.draw(String, float, float, int)` 的繪製（原版 UI 部分的標題、除錯文字，以及其他 mod 直接呼叫這個多載）。
- **根因**：1.13.0/1.13.1 走 Ornithe feather mappings（`net.minecraft.client.render.TextRenderer`），1.13.2 走 legacy-yarn（`net.minecraft.client.font.TextRenderer`），兩份是各自手寫的。1.13.2 那份漏了四參數多載，而 `require = 0` + `defaultRequire: 0` 讓漏掛完全無聲。同一個 MC 版本家族的可見多載集合不應該因為 mappings 不同而少一支。
- **影響**：1.13.2 上有一部分文字永遠顯示原文，且不會有任何日誌；排查時只能靠逐支加 hook 比對。功能可用、覆蓋面偏小，故列為小。
- **修法**：把四參數多載補進 1.13.2 那份，並在 `1.13/legacy.gradle` 對 `TextRendererMixin` 加一次啟動自檢（或把該 mixin 的注入改成 `require = 1`，讓 mappings 真的改變時建置期就紅燈）。

```java
// platforms/fabric/1.13/shared/legacy-1.13.2/.../mixin/TextRendererMixin.java
@ModifyVariable(method = "draw(Ljava/lang/String;FFI)I", at = @At("HEAD"), argsOnly = true)
private String universalTranslator$translateDrawnString(String text) {
    return RenderedTextBridge.translate(text);
}
```

---

## 已排查但判定無問題

1. **`FabricTranslationRuntime` 的 5 份副本逐行比對過，差異全部對得上官方 API 變更**：1.21.9+ 用 `getProfile().name()`、26.1–26.1.2 用 `Minecraft.getInstance().screen`（pre-26.2 欄位）、26.2–26.3 用 `gui.screen()`（`platforms/fabric/26.x/shared/api/26.2-26.3/.../FabricTranslationRuntime.java:96-98`）；`serverAddress()` 在 1.21.9+ 走 `getCurrentServerEntry().address`、26.x 走 `getCurrentServer().ip`。沒有漏改的欄位。

2. **`UniversalTranslatorFabricClient` 的 10 份副本，26.x 三份的 API 分派是自洽的（不是先前懷疑的「檔案互換」）**：`api/26.1-26.1.2` 用 `Minecraft.getInstance().screen` + `gui.getChat()`，`api/26.2` 用 `gui.screen()` + `gui.hud.getChat()`，`api/26.3` 與 26.2 同型只換 `InputConstants.KEYBOARD`/`KEY_U`。這與同層的 `FabricTranslationRuntime`（26.1-26.1.2 用 `screen` 欄位、26.2-26.3 用 `gui.screen()`）完全一致；三者的 `Minecraft.gui` 都同時提供 `screen`/`screen()` 與 `setScreen`，因此都能編譯。

3. **13 份 `*.mixins.json` 與實際被 `srcDir` 收進來的 mixin 類別，兩個方向都對得上**：逐版本核對 `1.0-1.8`(9)、`1.8-1.12`(9)、`1.13.0/1.13.1`(9，含 `ChatGuiMixin`)、`1.13.2`(9，含 `ChatHudMixin`)、`1.14/1.15`(11)、`1.16.x`(11)、`1.17–1.18.2`(11)、`1.19–1.19.2`(11)、`1.19.3/1.19.4`(11)、`1.20–1.20.6`(11)、`1.21–1.21.11`(9)、`26.1–26.3`(9)，沒有「列了但檔案不存在」也沒有「有 mixin 類別但沒列」。`1.0-1.8/.../mixin/HudIndicatorOverlay.java` 沒被列是正確的 —— 它沒有 `@Mixin`，是 package-private 的純繪製 helper（`:24`），放在 mixin package 只是為了被同 package 的 `InGameHudStatusIndicatorMixin` 呼叫。

4. **`1.14/1.14.1/1.14.2` 的 `mixins.json` 是空的（`client: []`、`required: false`）且沒有對應 mixin 類別 —— 相符，不是漏檔**：這三個版本用 `platforms/fabric/1.14-1.15/versions/1.14/build.gradle:1-19` 的純 `java` plugin，只編譯 `family/1.14-1.15/src/client/java` 下的 `UniversalTranslatorFabricClient`（6 行 stub），正好是 `fabric.mod.json` 宣告的 entrypoint。

5. **`1.21/shared.gradle` 與 `legacy/shared.gradle` 的 layer 表裡有 7 個字串指向不存在的目錄，但那是「旗標型死標記」不是漏搬檔案**：`runtime/pre-1.21.9`、`draw/1.21-1.21.5`、`text-renderer/1.21-1.21.1`（`1.21/shared.gradle:14-22`）、`family/1.16.0-1.16.4`、`family/1.16.5`、`family/1.17`、`rendered/1.19.2-plus`（`legacy/shared.gradle:30-39`、`:49-64`）。同一字串兼作 `layers.contains(...)` 的條件（`1.21/shared.gradle:99-114`、`legacy/shared.gradle:184-186/201-204/206-208`），真正的類別放在 `platforms/fabric/shared/*`。Gradle 對不存在的 `srcDir` 靜默忽略，目前無功能影響；但清理死路徑時務必連同條件一起處理，否則會把 `rendered-common`/`draw-context`/`runtime-common` 一起拔掉。

6. **`1.19` 的兩個 `UniversalTranslatorScreenBase` / `UniversalTranslatorConfigScreenBase` 差異是刻意的**：1.19.3 給 `ButtonWidget` 加了 static `builder(Text, PressAction)`，子類不能再用同名 static 方法遮蔽，所以 `1.19.3-1.19.4` 那份把工廠改名 `of(...)`（`1.19/shared/screen-base/1.19.3-1.19.4/.../UniversalTranslatorScreenBase.java:28-30`），1.19–1.19.2 仍用 `ButtonWidget.builder(...)`；`1.13.0-1.13.1` / `1.13.2` 的 `drawString` vs `drawWithShadow`、`1.14` / `1.15` 的 `client.window` vs `client.getWindow()` 也都是同一類一對一差異。

7. **`legacy/shared.gradle` 的 11 個關鍵類別覆蓋是「每版本恰好 1 次」**：`FabricTranslationRuntime`、`UniversalTranslatorFabricClient`、`RenderedTextBridge`、`TextRendererMixin`、`InGameHudStatusIndicatorMixin`、`TranslationRenderContext`、`FabricConfig`、`FabricLocalTextGuard`、`ScreenMixin`、`TitleScreenMixin`、`ClientPlayerEntityMixin` 在 1.14–1.20.6 的 30 個版本、1.21 的代表版本、26.x 的 3 個版本都只被一個 `srcDir` 提供，沒有同名類別衝突。`ButtonWidget`（1.19/1.19.2 走 `shared/button-widget-1.16-1.19`、1.19.3/1.19.4 走 `family/1.19.3-1.19.4`）與 `RenderedTextBridge`（1.19+ 走 `shared/rendered-common`、1.14–1.18 走各自的 `rendered/1.16.5` 或 `1.16-1.16.1`）各只命中一次。

8. **`processResources` 的 `expand` 佔位符與 `fabric.mod.json` 完全對得上**：`legacy/shared.gradle:341-362` 提供 7 個（`version`、`minecraftVersion`、`loaderVersion`、`javaVersion`、`fabricDependency`、`modmenuEntrypoint`、`modmenuSuggests`），對應 `legacy/shared/common/src/main/resources/fabric.mod.json` 的 7 個 `${...}`；`26.x/shared.gradle:94-107` 提供 3 個、`1.21/shared.gradle:135-140` 提供 1 個。所有 `fabric.mod.json` 沒有多餘的 `$` 或反斜線，不會 `MissingPropertyException`。
