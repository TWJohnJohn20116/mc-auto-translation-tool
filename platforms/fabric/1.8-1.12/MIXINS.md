# 1.8–1.12 mixin 設定檔只有一份（audit S2-096）

`shared/common/src/main/resources/universal-translator.fabric.mixins.json` 是這棵樹唯一的 mixin
設定，由 `shared.gradle:56` 掛入。它列出的 9 個類別分別來自：

| mixin | 來源目錄 |
| --- | --- |
| `InGameHudStatusIndicatorMixin`、`ScreenMixin`、`MinecraftMixin`、`LocalClientPlayerEntityMixin` | `1.8-1.12/shared/common` |
| `TextRendererMixin` | `platforms/fabric/shared/text-renderer-legacy` |
| `ChatGuiMixin`、`SignEditScreenMixin` | `platforms/fabric/shared/mixin-legacy-1.0-1.13` |
| `TitleScreenMixin`、`TextFieldWidgetMixin` | `platforms/fabric/shared/mixin-legacy-1.0-1.12` |

## 規則

1. **不要在 `versions/<版本>/src/main/resources/` 放同名 mixin 設定**。這裡曾經有 15 份
   `{"required":false, "client":[], ...}` 的空殼，和 shared 層同路徑；它們一旦因為
   `processResources` 順序改變而勝出，整個模組就一個 mixin 都不載入，而建置與啟動都不報錯。
2. `injectors.defaultRequire` 是 0：1.8–1.12 各版本的 `draw`／`drawLayer`／`getWidth` 名稱不同，
   找不到時必須靜默跳過而不是崩潰；代價是漏翻譯沒有日誌。
3. 新增 mixin 時確認它在上表其中一個來源目錄，否則 mixin 設定會因找不到類別而在啟動時失敗。
