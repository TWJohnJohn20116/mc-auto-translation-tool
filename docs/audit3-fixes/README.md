# 第三輪審計修復（666 條）— 修復紀錄

本目錄記錄 `fix/audit3-666` 這個 PR 對應的修復範圍與取捨。完整逐條清單在本機的
`reports/audit3/00-總報告-666.md`（不進版控，因為 `reports/` 被 `.git/info/exclude` 排除）。

## 這個 PR 修什麼

第三輪全倉庫審計共 666 條（嚴重 294 條 = `S1` 240 + `S1↑` 54），分 15 個分區、由 15 個子代理平行修復。
分區與檔案所有權嚴格互斥，避免同一檔案被兩個代理同時改。

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

## 修復原則（本 PR 遵守）

1. **最小改動**：一條缺陷一個邏輯改動，不做順手重構、不改命名、不動格式。
2. **不動 public API 簽章**：`translator-core` 以 `srcDir` 掛進 48 個平台專案，改簽章會讓某些樹編不過。
3. **Java 8 語法下限**：core 與 legacy 樹不用 `var`／`List.of`／`Map.of`／`switch` 表達式／`record`／`text block`。
4. **新加的狀態必須有清理點**（`clear()`／`shutdown()`／`reset()`），不留新的洩漏。
5. **驗證只有 CI**：本機不編譯、不執行測試（`AGENTS.md` 絕對規則）。

## 未修 / 延後的條目

見本文末「未修清單」。原則：需要產品決策（改重試上限、改存放路徑、改 provider `id()` 契約）、
需要 CI 逐目標驗證（mixin `@At` 描述子）、或與另一條修法互斥者，一律不在本 PR 內強改，
以免把「可編譯的修復」換成「不可診斷的紅燈」。

## 與前兩輪的關係

本 PR 基於 `fix/audit2-167`（第二輪 167 條修復）之後的程式碼，行號與證據都以該狀態為準。
前兩輪的 3 條錯誤結論已在第三輪報告中更正（`rfc3986Encode` 死碼、`1.20.1` 的 `defaultRequire`、
`GRADLE-1102` 的「8 個目錄不存在」），本 PR 不實作那 3 條的錯誤版本。
