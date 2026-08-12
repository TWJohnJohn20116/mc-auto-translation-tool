# MC 自動翻譯工具（MC Auto Translation Tool）

[简体中文](../Zh-cn/README.md) · [繁體中文](README.md) · [English](../en/README.md) · [儲存庫首頁](../../README.md)

一個面向 Minecraft Java 版的公益、開源、純用戶端全介面翻譯模組。

[⬇️ 下載最新版](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest) ·
[📚 語言目錄](../README.md) · [📖 安裝及使用說明](USER_GUIDE.md)

原作者：[Bilibili「我小张7272635」](https://space.bilibili.com/3546631091783712)。
轉載、再次發佈或改作時，請保留原作者署名及 MIT License 版權聲明。

本專案旨在翻譯伺服器、模組及整合包顯示給玩家的可見文字，包括聊天、任務書、
模組設定與選單、記分板、Tab 清單、Action Bar、標題、Boss Bar、容器標題、
物品名稱與 Lore、告示牌、書本、全像文字和實體自訂名稱。玩家名稱、數字、網址及
Minecraft 樣式代碼預設保持不變。

## 下載

建議從 [GitHub Releases 下載最新版](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest)。
請務必選擇與你的 Minecraft 版本及模組載入器完全相符的檔案：

| Minecraft | 載入器 | 下載 |
| --- | --- | --- |
| 1.8.9 | Forge | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2.1/MCAutoTranslationTool-1.2.1-mc1.8.9-forge.jar) |
| 1.12.2 | Forge | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2.1/MCAutoTranslationTool-1.2.1-mc1.12.2-forge.jar) |
| 1.20.1 | Fabric | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2.1/MCAutoTranslationTool-1.2.1-mc1.20.1-fabric.jar) |
| 1.20.1 | Forge | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2.1/MCAutoTranslationTool-1.2.1-mc1.20.1-forge.jar) |
| 1.21–1.21.11 | Fabric | [下載單一 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2.1/MCAutoTranslationTool-1.2.1-mc1.21.x-fabric.jar) |
| 1.21.11 | Fabric | [下載精確版本 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2.1/MCAutoTranslationTool-1.2.1-mc1.21.11-fabric.jar) |
| 1.21.11 | Forge | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2.1/MCAutoTranslationTool-1.2.1-mc1.21.11-forge.jar) |
| 26.1–26.2 | Fabric | [下載單一 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2.1/MCAutoTranslationTool-1.2.1-mc26.x-fabric.jar) |

請勿跨版本或載入器混用 JAR。發行中只允許已完成建置驗證的精確 Minecraft 版本；
鄰近版本會在個別驗證後再加入支援範圍。

[查看所有版本與更新說明](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases) ·
[SHA-256 校驗檔案](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2.1/SHA256SUMS.txt)

## 設計原則

- 伺服器無須安裝模組。
- 預設使用玩家電腦上的離線模型，不需要 API 密鑰或專案伺服器。
- 離線模式僅繫結至 `127.0.0.1`，伺服器文字不會離開玩家的電腦。
- 可使用 LibreTranslate、騰訊相容介面或 OpenAI 相容 LLM API；離線失敗時的 API 備援預設關閉。
- 可另行開啟「傳送翻譯」；一般聊天會在背景翻譯後依原順序傳送，命令維持原樣。
- 翻譯在背景執行；服務無回應時立即保留原文，不影響遊戲。
- 相同文字及動態文字範本使用本機快取，以降低延遲及費用。
- 尚未進入世界時，也會擷取使用 Minecraft 原版字型彩現器的模組選單及整合包標題畫面。
- 預設不翻譯玩家名稱、座標、數字、網址及格式代碼。
- 玩家可依伺服器關閉私訊或其他敏感內容的外傳。

## 版本模組

| Minecraft | 載入器 | Java |
| --- | --- | --- |
| 26.1、26.1.1、26.1.2、26.2（單一 JAR） | Fabric | 25 |
| 1.21–1.21.11（單一 JAR） | Fabric | 21 |
| 1.21.11 | Forge | 21 |
| 1.20.1 | Forge | 17 |
| 1.20.1 | Fabric | 17 |
| 1.19.2（開發中） | Fabric | 17 |
| 1.16.5（開發中） | Fabric | 8 |
| 1.12.2 | Forge | 8 |
| 1.8.9 | Forge | 8 |

Fabric 1.21.x 與 26.x 各提供一個整合 JAR；整合包內仍保留精確版本實作，並共用相同的核心邏輯及設定語意。

主分支另包含 Fabric 1.16.5 與 1.19.2 獨立開發轉接；完成實機及伺服器內人工
迴歸前不提供正式版下載。

## 目前狀態

1.2.1 正式版提供八個用戶端 JAR：保留 1.2 的六個精確版本下載，並新增涵蓋
1.21–1.21.11 與 26.1／26.1.1／26.1.2／26.2 的兩個 Fabric 單一 JAR。
全部建置線均通過乾淨建置；兩個整合包通過真實 Fabric Loader 自動選版測試，
共用核心自我測試通過。各目標的精確驗證層級請見相容性矩陣。

Fabric 版本按下 `U` 開啟設定。Forge 1.20.1／1.21.11 修改
`config/universal-translator.properties` 後按下 `U` 重新載入。模組預設關閉；新安裝預設選擇「離線」，並使用
「僅顯示譯文」替換方式，避免記分板及容器文字因雙語串接而溢出。按下 `F8` 可隨時
開啟或關閉翻譯，兩個快速鍵都能在 Minecraft 按鍵綁定介面中修改。第一次遇到待翻譯
文字後，模組會在背景下載約 10–17 MB 的平台引擎及 491 MB 的 Lite 模型；下載期間
繼續顯示原文。模型預設優先使用 ModelScope 中國來源，失敗後會自動續傳並切換至官方
來源。所有檔案都必須通過大小及 SHA-256 校驗後才會執行。也可以繼續選擇
LibreTranslate 或舊版騰訊相容模式。
進入伺服器約三秒後，聊天欄會顯示一則只存在本機的 `U`／`F8` 操作提示，不會傳送
任何聊天訊息或資料封包給伺服器。

已驗證的行為：

- 保護玩家名稱、伺服器 IP／網域名稱、連接埠、顏色代碼、數字、百分比及網址。
- 受保護的內容會在本機分段，不會傳送給離線模型或線上 API。
- 將動態記分板內容正規化為可重複使用的範本。
- 快取譯文，並合併同時發生的相同請求。
- 對已經是目標語言或只包含數字的內容略過連線。
- 中英文混合文字只翻譯英文片段，已有中文保持不變。
- 譯文可使用青色、綠色、金色等獨立顏色，也可保留原始顏色。
- 翻譯服務異常時傳回原文。
- 背景翻譯不會阻塞彩現執行緒。
- 儲存設定後無須重新啟動遊戲即可套用。
- 聊天及其他介面可分別禁止外傳。
- 玩家傳送翻譯預設關閉，其目標語言與介面翻譯目標語言分開設定。
- 模組選單、任務／配方介面及整合包自訂標題畫面不需連線伺服器即可翻譯。
- 離線 Lite 及 Quality 模型會按需安裝，不包含在模組 JAR 中。

## 隱私權提示

離線模式不會傳送伺服器、模組或整合包文字。線上 API 模式或「API 備援」表示
所選的伺服器文字及本機可見的模組／整合包文字可能會傳送至玩家設定的翻譯服務。
專案提供明確的總開關、聊天／其他內容開關及
本機快取。密鑰只儲存在玩家本機，禁止提交至程式碼儲存庫。遠端端點必須使用
HTTPS；只有精確的本機回環位址可使用 HTTP。

詳細安裝及使用方式請參閱 [使用指南](USER_GUIDE.md)；實際驗證範圍及後續版本順序
請參閱 [相容性矩陣](COMPATIBILITY.md)。官方網站原始碼位於 `../../website/`。
