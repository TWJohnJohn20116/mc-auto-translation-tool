# 1.13 mixin 設定檔為什麼有兩份（audit S2-096）

`universal-translator.fabric.mixins.json` 在這個目錄樹下有**兩個**同名檔案：

| 檔案 | 由誰掛入 | 內容 |
| --- | --- | --- |
| `shared/common/src/main/resources/universal-translator.fabric.mixins.json` | `ornithe.gradle:56`（1.13.0／1.13.1） | 9 個 mixin，聊天 HUD 用 `ChatGuiMixin` |
| `shared/legacy-1.13.2/src/main/resources/universal-translator.fabric.mixins.json` | `legacy.gradle:42`（1.13.2） | 9 個 mixin，聊天 HUD 用 `ChatHudMixin` |

兩者**不會同時進入同一個 classpath**：`ornithe.gradle` 只掛 `shared/common` 的 resources，
`legacy.gradle` 只掛 `shared/legacy-1.13.2` 的 resources。同名而不同內容是刻意的，因為兩個 mapping
家族對同一個 mixin 類別給的名字不同（`ChatGuiMixin` 對 `ChatHudMixin`），其餘 8 個成員必須完全一致。

## 規則

1. **不要在 `versions/<版本>/src/main/resources/` 再放一份同名 mixin 設定**。曾經有這種第三份
   （1.13 的版本目錄各一份、1.8–1.12 的 15 個版本目錄各一份空的 `client: []` + `required:false`），
   它和 shared 層同路徑，誰生效取決於 `processResources` 的拷貝順序；一旦順序改變就會出現
   「HUD 指示器消失」或「一個 mixin 都沒載入」而建置仍然成功。這些檔案已移除，別再加回來。
2. 改動其中一份時，另一份要同步；兩份的唯一合法差異是上表「內容」欄註明的類別名稱。
3. 新增 mixin 時，兩個檔案都要加，並確認該類別在兩條建置路徑上都編得到。
4. `injectors.defaultRequire` 是 0：mapping 找不到注入點時靜默跳過而不崩潰，這對跨多個 mapping
   的舊版本是必要的取捨，代價是「漏翻譯不會有日誌」。要驗證注入是否命中，請在對應 mixin 內
   臨時加 `require = 1` 觀察啟動日誌。
