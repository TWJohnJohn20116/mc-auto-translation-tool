# NeoForge 平台層：資源與 mixin 宣告的來源

這份文件記錄 `platforms/neoforge/` 底下**資源**（`assets/`、`pack.mcmeta`）與 **mixin 宣告**
的權威來源，用來人工核對「jar 裡到底有沒有一份語言檔」。它不能取代建置驗證：所有數字都靠
靜態列舉得出，實際 jar 內容一律以 CI 產物為準。

## 1. 語言資源（`assets/universal_translator/lang/*.json`）

唯一一份語言檔在：

```
platforms/shared/lang/json/assets/universal_translator/lang/{en_us,zh_cn,zh_tw}.json
```

它不像 `platforms/forge/<樹>/shared/common/src/main/resources` 那樣「每個平台樹各一份」——那是
2026-10-22 `refactor(lang): 語言檔收斂為共用層` 之後的結果：各樹的 `assets/` 被刪除，改成每條
建置線各自把上面那個目錄掛成 `resources.srcDir`。目前掛上的有 10 處：

| 檔案:行 | 說明 |
| --- | --- |
| `platforms/forge/shared.gradle:66` | Forge 全域層 |
| `platforms/forge/modern/shared.gradle:141` | Forge 舊樹 |
| `platforms/fabric/1.21/shared.gradle:73` | Fabric 1.21 |
| `platforms/fabric/26.x/shared.gradle:64` | Fabric 26.x |
| `platforms/fabric/legacy/shared.gradle:152` | Fabric 舊版 bundle |
| `platforms/fabric/1.13/legacy.gradle:44`、`platforms/fabric/1.13/ornithe.gradle:58` | Fabric 1.13 |
| `platforms/fabric/1.14-1.15/versions/{1.14,1.14.1,1.14.2}/build.gradle:18` | Fabric 1.14–1.15 |

**`platforms/neoforge/shared.gradle` 與 `platforms/neoforge/1.20.1/build.gradle` 不在清單內**，
所以 20 個 NeoForge 目標的 jar 目前沒有 `assets/`（審計條目 C2-153）。這兩個檔案都是 `.gradle`，
由建置腳本負責人（a13）修，需要的變更只有一行：

- `platforms/neoforge/shared.gradle` 的 `sourceSets { main { … } }` 區塊內（例如 `java.srcDir`
  那一串之後）補 `resources.srcDir rootProject.file("platforms/shared/lang/json")`。
  `processResources` 內**不需要**再加 `from(...)`：`sourceSets.main.resources` 的 `srcDir`
  本來就是 `processResources` 的輸入，現有的兩個 `from(...)` 只是額外疊加目錄。
- `platforms/neoforge/1.20.1/build.gradle:59` 有 `resources.setSrcDirs([])`（清空預設值），
  同樣要補一行 `resources.srcDir rootProject.file("platforms/shared/lang/json")`。

在 CI 上核對（不需要在本機建置）：

```bash
unzip -l build/libs/mc-auto-translation-tool-neoforge-<版本>.jar | grep assets/universal_translator/lang
```

應該看到 `en_us.json`、`zh_cn.json`、`zh_tw.json` 三個檔。`verify_release_jars.py` 不檢查
`assets/`，所以 CI 全綠不代表這一層沒漏。

其他資源的來源（現況，皆已核對）：

| 資源 | 來源 |
| --- | --- |
| `META-INF/neoforge.mods.toml` | 各版本自己的 `src/main/resources/META-INF/`（唯一真相；`shared.gradle` 的 `forgeLayers` 迴圈會 `exclude` 掉 Forge 樹的 `META-INF/mods.toml`） |
| `pack.mcmeta` | `forgeLayers` 的 `common` 層（`platforms/forge/{modern,1.21,26.x}/shared/common/src/main/resources`） |
| `universal-translator.neoforge.mixins.json` | `platforms/neoforge/shared/metadata/<層>/src/main/resources` |
| `assets/`（語言檔） | **缺**，見上表 |

## 2. `compatibilityLevel` 為什麼停在 `JAVA_21`

`platforms/neoforge/shared/metadata/26.x/` 的 mixin 設定宣告 `JAVA_21`，但 26.x 的
`options.release` 是 25（class file major 69）。這**不是可以順手改掉的值**：

- `MixinEnvironment.CompatibilityLevel` 這個列舉的最大常數就是 `JAVA_21`
  （Mixin master 與 0.8.7 相同）。宣告更大的值（`JAVA_25` 等）會在
  `MixinConfig.initCompatibilityLevel()` 的 `CompatibilityLevel.valueOf()` 失敗，
  直接丟 `MixinInitialisationError`，整份 mixin 設定被丟棄——比現況更糟。
  根 `build.gradle` 的 `validateMixinConfigs` 也只允許到 `JAVA_21`。
- class file 版本高於宣告層級時，Mixin 走的是 `MixinConfig.checkCompatibilityLevel()`，
  只**印一則 WARN（非 verbose 時為 DEBUG）**，不會拒絕 transform、不會拋例外。
  `"required": true` 影響的是初始化階段（`MixinConfig.prepareMixins`）的例外傳播，管不到
  class 版本。

因此正確結論是：`JAVA_21` 保留，改動只可能在建置層的守門（`validateMixinConfigs` 的
`minimumLevel` / 落差檢查），而那是根 `build.gradle` 的事。細節見
`reports/audit2-fixes/F-a12.md` 的 C2-155 與 Handoff 段。
