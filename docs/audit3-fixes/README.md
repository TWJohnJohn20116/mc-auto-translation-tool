# 第三輪審計修復（666 條）— 修復紀錄

本目錄記錄 `fix/audit3-666` 這個 PR 對應的修復範圍與取捨。完整逐條清單在本機的
`reports/audit3/00-總報告-666.md`（不進版控，因為 `reports/` 被 `.git/info/exclude` 排除）。

## CI 驗證結果

最後一輪 `主要建置` 全綠：30 個 job 全部 success，包含 `CI 總結`（分支保護要求的檢查）。

| 階段 | 結果 | 修正內容 |
| --- | --- | --- |
| 第 1 輪 | 全紅 | `ProviderErrors` 兩個 4-String 方法簽章相撞、`getPlayerList()` 在 1.16 回 `Collection`、`setFocused` 在 1.16–1.20 是 protected、`validateNeoForgeModuleCoordinates` 不認「座標指向 gradle.properties 屬性」、shellcheck SC2016 |
| 第 2 輪 | 核心綠、平台全紅 | `build.gradle` 的 GString 內含 `$` 造成 token recognition error（我自己的修正引入）、SC2016 真正來源是 `sed` 的結尾錨點 |
| 第 3 輪 | 核心／網站／Forge／NeoForge 綠，fabric 與 forge-26.1.1 紅 | 7 個 fabric 畫面的 21 處 `setFocused` 改走 `core/FocusHelper`（快取反射）；26.x mixin 上報需要的類別在 26.1 層不存在 |
| 第 4 輪 | 只剩 8 個 bundle 與 forge-26.1.1 | bundle `verifyBundle` 改 `ZipFile` 後 CI 讀不到 nested JAR（`zipTree` 走 Gradle 檔案樹抽象則看得到）；26.x 的 `ForgeLocalTextGuard` 出現 duplicate class |
| 第 5 輪 | **全綠** | bundle 驗證退回 `zipTree`、 forge-26.1.1 移除重複類別並回退 mixin 上報、目標推導搬到 `scripts/ci/derive-prepare-targets.py` |

## 這個 PR 修什麼

第三輪審計 666 條（嚴重 294 條 = `S1` 240 + `S1↑` 54），按檔案所有權切成 15 個互斥分區：

| 分區 | 範圍 |
| --- | --- |
| P01 | `core/`：TranslationCoordinator、RenderTranslationSession、TranslationCache、WeakIdentitySet、RecentUserText、TranslationBlocklist、OutgoingTranslationPreview |
| P02 | `core/`：TranslationStats、PersistentTranslationCache、診斷快照與匯出、SettingsUiAnimation |
| P03 | `core/`：ProtectedText、TranslationOutputValidator、TranslationPrompt、TargetLanguage、設定版面與清單 |
| P04 | `core/net/`：HttpJsonClient、JsonStrings、ServerSentEvents、EndpointPolicy、簽章 |
| P05 | `core/offline/` 與 `DebugLog`、樣式、語言啟發式、HUD 指示器設定 |
| P06 | `provider/`：OpenAI、llama.cpp、設定、批次、watchdog、失敗策略 |
| P07 | `provider/`：其餘 14 家供應商與共用型別 |
| P08 | `platforms/fabric/shared/**` |
| P09 | `platforms/fabric/{1.0-1.12,1.0-1.8,1.8-1.12,1.13,1.14-1.15,legacy}/**` |
| P10 | `platforms/fabric/{1.16,1.17-1.18,1.19,1.20,1.21,26.x,modmenu,bundles}/**` |
| P11 | `platforms/forge/shared/**`、`platforms/forge/1.21/**` |
| P12 | `platforms/forge/{modern,26.x,legacy}/**`、`platforms/neoforge/**` |
| P13 | 根 `build.gradle`、`settings.gradle`、`gradle.properties`、`platforms/**/*.gradle` |
| P14 | `.github/**`、`scripts/**` |
| P15 | 資源與 metadata（lang、mixins.json、pack.mcmeta、toml、fabric.mod.json）、`website/**`、`docs/**` |

回報統計：修好 557 條、確認已符合 22 條、未修 106 條、跨區站點 251 個（同一條缺陷在多個版本目錄各一份，
由對應分區修，計數會重複）。

## 修復原則（本 PR 遵守）

1. **最小改動**：一條缺陷一個邏輯改動，不做順手重構、不改命名、不動格式。
2. **不動 public API 簽章**：`translator-core` 以 `srcDir` 掛進 48 個平台專案，改簽章會讓某些樹編不過。
3. **Java 8 語法下限**：core 與 legacy 樹不用 `var`／`List.of`／`Map.of`／`switch` 表達式／`record`／`text block`。
4. **新加的狀態必須有清理點**（`clear()`／`shutdown()`／`reset()`），不留新的洩漏。
5. **mixin 注入描述子不動**：`@Inject`／`@At`／`require` 的改動需要逐版本的建置結果才能驗證，屬下一輪。
6. **驗證只有 CI**：本機不編譯、不執行測試（`AGENTS.md` 絕對規則）。

## 未修的 106 條

| 原因 | 代表條目 | 說明 |
| --- | --- | --- |
| 需要產品決策 | #27、#28、#85、#161、#174、#279、#345 | 改動會影響既有使用者（模型快取搬位置等於重新下載 500 MB–1.1 GB）、發布目標清單或 CI 成本結構 |
| 需要 CI 逐目標驗證 mixin | #114、#115、#119、#122、#146、#147、#148、#234、#235、#257、#264、#532、#642 | `@Inject`／`@At`／`require` 的靜態無法確認命中率 |
| 需要重生 lockfile | #283、#350、#606 | 本機禁跑 pnpm，加半套 typecheck／移除 Tailwind 會讓 CI 更糟 |
| 與既有測試或契約衝突 | #207、#457、#661 | 例如 `ProviderSelfTest` 固定「每個 delta 都必須發布」，節流會直接紅 |
| 結構性重構（屎山） | #331、#333、#335、#347、#348、#359、#360、#553、#561、#570 | 合併類別／搬目錄／引入 versions.gradle 會打斷 `srcDir` 掛載與 48 目標建置 |
| 需要一次乾淨 CI 才能定案 | #594 | `-fabric-all` 的 `depends.minecraft` 陣列語意（AND／OR） |
| 本輪退回的兩條 | #165（bundle 一次 ZIP 走訪）、#263（26.x mixin 上報） | 兩者在 CI 上分別出現「讀不到 nested JAR」與「同名類別分層衝突」，退回已知綠的寫法，待能重現時再處理 |

另有 22 條在修復過程中確認「程式碼已符合條目期望」（掃描時是舊狀態），標為已修。

## 本輪新增的檔案

| 檔案 | 用途 |
| --- | --- |
| `translator-core/.../core/FocusHelper.java` | 跨版本清除欄位焦點（1.16–1.20 只有 `setTextFieldFocused` 是 public，1.21+ 才是 `setFocused`） |
| `translator-core/.../core/provider/ProviderErrors.java` | 廠商錯誤碼 → 例外型別（package-private，9 個 provider 共用） |
| `platforms/fabric/1.21/.../mixin/HudIndicatorLabels.java` | HUD 指示器標籤快取，避免往 mixin 加欄位 |
| `platforms/fabric/shared/base/.../HomeButtonPlacement.java` | 三份 `TitleScreenMixin` 共用的首頁按鈕座標 |
| `scripts/ci/read-mod-version.sh` | workflow 共用的版本讀取與字元白名單 |
| `scripts/ci/derive-prepare-targets.py` | 發布目標 → `-PtargetPlatform` 的推導（原本內嵌在 workflow，會觸發 SC2016） |
| `scripts/ci/set-defender-exclusions.ps1` | 取代全域排除 `java.exe` 的 `.bat` |

## 與前兩輪的關係

本 PR 基於第二輪修復（#169，`c5ce0773`）之後的程式碼，行號與證據都以該狀態為準；分支已 rebase 到
`origin/main`，沒有 merge commit。

前兩輪的 3 條錯誤結論已在第三輪報告中更正（`rfc3986Encode` 死碼、`1.20.1` 的 `defaultRequire` 現值、
`GRADLE-1102` 的「8 個目錄不存在」），本 PR 不實作那 3 條的錯誤版本。
