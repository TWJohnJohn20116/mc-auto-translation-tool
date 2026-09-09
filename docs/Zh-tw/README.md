# MC 自動翻譯工具

**模組權限已轉給瓜瓜，有事不要問我（小張）了**

**模組原作者：https://space.bilibili.com/3546631091783712**

**現維護者：https://space.bilibili.com/3546554256328745**

**MC Auto Translation Tool** 是一個面向 Minecraft Java 版的公益、開源、純用戶端全介面翻譯模組。
伺服器不必安裝本模組；它可以翻譯聊天、任務書、模組選單、計分板、物品說明、告示牌等玩家可見文字。

[简体中文](../Zh-cn/README.md) · [繁體中文](README.md) · [English](../en/README.md) · [儲存庫首頁](../../README.md)

[⬇️ 下載最新版](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest) ·
[📖 安裝與使用](USER_GUIDE.md) ·
[✅ 相容性矩陣](COMPATIBILITY.md) ·
[📝 更新記錄](../../CHANGELOG.md)

> 目前正式版與發佈日期以 [GitHub Releases](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest)
> 為準。請只使用與你的 Minecraft 版本及模組載入器相符的 JAR。
>
> 測試版 **1.3.11-beta.1**（主畫面改為單一「翻譯設定」入口）見
> [GitHub Pre-release](https://github.com/TWJohnJohn20116/mc-auto-translation-tool/releases/tag/v1.3.11-beta.1)，不取代 1.3.10 正式版。

## 為什麼使用它

| 功能 | 說明 |
| --- | --- |
| 全介面翻譯 | 涵蓋聊天、任務／配方介面、模組選單、計分板、Tab、標題、Boss Bar、容器、物品名稱與 Lore、書、告示牌、全息文字等 |
| 純用戶端 | Minecraft 伺服器不必安裝模組，也不會收到額外的模組資料封包 |
| 本機離線 | 建議使用內建離線模式，不需要 API Key；模型與翻譯內容保留在使用者電腦上 |
| 多種服務 | 內建多種中國大陸機器翻譯與 LLM 服務，也支援 LibreTranslate、OpenAI 相容及自訂 HTTP JSON API |
| 流暢與穩定 | 翻譯在背景執行；尚未完成或服務異常時繼續顯示原文，不阻塞渲染執行緒 |
| 隱私保護 | 玩家名稱預設在本機分離，也可主動開啟翻譯；伺服器位址、網址、數字與格式碼仍會保持保護 |
| 屏蔽關鍵詞 | 自訂不翻譯關鍵詞；符合的整段文字保持原樣，不會進入離線模型或線上 API |
| 本機快取 | 合併相同請求並快取譯文，降低重複翻譯的延遲與線上服務費用 |
| 可選傳送翻譯 | 可將一般聊天翻譯後依序傳送；此功能預設關閉，指令永遠保持原樣 |

## 快速開始

1. 確認遊戲使用的 **Minecraft 版本、模組載入器與 Java 版本**。
2. 開啟[最新 Release](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest)，依下表選擇 JAR。
3. 先從該實例的 `mods` 資料夾移除 1.3.9 及更早版本，再放入唯一一個相符的 1.3.10 JAR。Fabric 還需安裝對應版本的 Fabric API。
4. 啟動遊戲。按 `F8` 開啟或關閉自動翻譯；主畫面右上角「翻譯設定」或按 `U` 開啟設定。若已安裝 Mod Menu，也可在模組清單開啟同一設定頁。
5. 首次使用建議選擇「離線」與「僅譯文」。詳細步驟請見[安裝與使用指南](USER_GUIDE.md)。

Fabric、Forge 與 NeoForge 均可按 `U` 開啟圖形設定，直接編輯屏蔽詞、玩家名稱翻譯等選項。
已安裝 Mod Menu 的 Fabric 實例也可從模組清單進入同一設定頁。
騰訊雲金鑰等進階選項仍可寫在遊戲實例的 `config/universal-translator.properties`。

## 下載與版本選擇

先確認載入器，再下載對應 JAR。正式版將 33 個建置產物整理為 **16 個可直接安裝的 JAR**；
發佈新版本時，這些連結會隨 README 一併更新。

原始碼中另有 1.0.0–1.12.2 的 Ornithe／早期 Fabric 目標，未打入目前 GitHub 正式版。

### Fabric：一個 JAR 即可

涵蓋 **1.13–1.21.11** 與 **26.1–26.2**。JAR 內含各版本精確實作，由 Fabric Loader 依目前遊戲版本自動選擇。Java 隨遊戲為 8、17、21 或 25。1.14–1.14.2 因 Yarn 無法 remap，僅提供引導實作；完整設定頁從 1.14.3 起。

[下載 Fabric JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-fabric-all.jar)

1.0.0–1.12.2（Fabric / Ornithe）尚未隨 v1.3.10 提供。請不要把 `fabric-all` 用在未列出的遊戲版本上。

### Forge：依 Minecraft 版本選擇

Forge 只在已驗證相容的相鄰版本間共用 JAR，沒有 Fabric 那種多實作巢狀選版。

| Minecraft | Java | 下載 |
| --- | ---: | --- |
| 1.8.9 | 8 | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-mc1.8.9-forge.jar) |
| 1.12.2 | 8 | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-mc1.12.2-forge.jar) |
| 1.16.5 | 8 | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-mc1.16.5-forge.jar) |
| 1.18.2 | 17 | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-mc1.18.2-forge.jar) |
| 1.19.2 | 17 | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-mc1.19.2-forge.jar) |
| 1.20.1 | 17 | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-mc1.20.1-forge.jar) |
| 1.21、1.21.1、1.21.3–1.21.5 | 21 | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-mc1.21-1.21.5-forge.jar) |
| 1.21.6–1.21.8 | 21 | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-mc1.21.6-1.21.8-forge.jar) |
| 1.21.9–1.21.11 | 21 | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-mc1.21.9-1.21.11-forge.jar) |
| 26.1–26.1.2 | 25 | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-mc26.1-26.1.2-forge.jar) |
| 26.2 | 25 | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-mc26.2-forge.jar) |

### NeoForge：必須依版本分開

NeoForge / FML **不能**像 Fabric Loader 那樣，在同一個 JAR 裡巢狀多套同 `modId` 實作再依遊戲版本只啟用一套：重複 modId 會無法載入，Jar-in-Jar 只用來嵌入相依函式庫。1.20.1（Java 17、NeoForge 47）與 1.21.1 / 1.21.3 / 1.21.11（Java 21、NeoForge 21.1 / 21.3 / 21.11）API 也不相容，所以正式版仍是四個獨立 JAR。

| Minecraft | NeoForge | Java | 下載 |
| --- | --- | ---: | --- |
| 1.20.1 | 47.1.106–47.1.x | 17 | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-mc1.20.1-neoforge.jar) |
| 1.21.1 | 21.1.248 | 21 | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-mc1.21.1-neoforge.jar) |
| 1.21.3 | 21.3.97 | 21 | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-mc1.21.3-neoforge.jar) |
| 1.21.11 | 21.11.45 | 21 | [下載 JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.10/MCAutoTranslationTool-1.3.10-mc1.21.11-neoforge.jar) |

> **不要跨載入器或超出檔名範圍混用 JAR。** 相鄰 Minecraft 版本的渲染 API 與 Mixin
> 目標可能不同；只有完成驗證的版本才會寫入發佈 metadata。

[查看最新 Release](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest) ·
[查看所有版本](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases) ·
[查看實際驗證範圍](COMPATIBILITY.md)

## 翻譯方式與隱私

| 方式 | 需要 API Key | 文字是否離開本機 | 適合情境 |
| --- | --- | --- | --- |
| 離線 Lite / Quality | 否 | 否 | 建議；首次使用會下載平台引擎與所選模型 |
| 本機 LibreTranslate | 視服務設定 | 否 | 已在本機執行 LibreTranslate 的使用者 |
| 遠端 LibreTranslate | 視服務設定 | 是 | 使用可信任的 HTTPS 翻譯服務 |
| 本機 OpenAI 相容 API | 視服務設定 | 否 | 自行執行 llama.cpp、Ollama 等相容服務 |
| 線上 OpenAI 相容 API | 通常需要 | 是 | 使用者自行選擇的 HTTPS LLM 服務 |
| 專用機器翻譯／LLM API | 通常需要 | 是 | 百度、騰訊雲、阿里雲、有道、火山引擎、訊飛、華為雲、DeepSeek、通義千問、智譜等 |
| 自訂 HTTP JSON API | 視服務設定 | 視端點而定 | 自行設定安全請求範本、鑑權標頭及巢狀回應路徑 |
| 騰訊相容介面 | 是 | 是 | 僅用於相容舊設定 |

- 離線模式只在本機回環位址上執行；伺服器、模組與整合包文字不會傳送到專案方。
- 使用遠端 API 時，獲准翻譯的可見文字會傳送到使用者設定的服務；專案不會代管金鑰。
- API Key 只儲存在目前遊戲實例的設定檔中。遠端端點必須使用 HTTPS。
- 可以分別關閉聊天內容、其他介面與玩家主動傳送翻譯；傳送翻譯預設關閉。
- 快取鍵使用 SHA-256，原文不會以明文形式寫入快取檔案。
- 命中屏蔽關鍵詞的文字會留在本機，不會傳送到翻譯服務。

完整線上服務及自訂請求設定請見[線上翻譯 API 設定](ONLINE_APIS.md)；
其餘設定、模型下載說明與常見問題請見[安裝與使用指南](USER_GUIDE.md)。

## 目前驗證狀態

目前正式版的所有目標均通過乾淨建置、共用核心自我測試、Mixin/refmap、執行階段映射、發佈結構與
SHA-256 驗證。Fabric 全版本 JAR 內含 39 個精確實作，並已通過各目標版本的真實 Loader
選擇測試；NeoForge 1.20.1 已完成實際用戶端啟動與模組初始化。

「可以編譯」不等於「已完成伺服器內人工回歸」。各版本的建置、啟動與人工驗證層級會分別記錄，
詳見[相容性矩陣](COMPATIBILITY.md)。

## 文件

| 文件 | 內容 |
| --- | --- |
| [安裝與使用指南](USER_GUIDE.md) | 安裝、首次設定、離線模型、API 設定、常見問題 |
| [相容性矩陣](COMPATIBILITY.md) | 各 Minecraft／載入器目標的實際驗證層級 |
| [建置指南](BUILDING.md) | 開發環境、Gradle 任務、發佈 JAR 驗證 |
| [線上翻譯 API 設定](ONLINE_APIS.md) | 內建服務、自訂 HTTP JSON、逾時、重試及安全限制 |
| [架構說明](ARCHITECTURE.md) | 共用核心、平台適配、翻譯與快取流程 |
| [第三方離線服務](THIRD_PARTY_OFFLINE.md) | 連接使用者自行執行的本機翻譯服務 |
| [更新記錄](../../CHANGELOG.md) | 正式版與測試版變更 |
| [多語言文件目錄](../README.md) | 简体中文、繁體中文與 English 文件入口 |

## 交流、作者與授權

- QQ 交流群：`1054795488`
- 愛發電：[支持「我小张7272635」](https://afdian.com/a/XiaoZhangGG)
- 原作者：[Bilibili「我小张7272635」](https://space.bilibili.com/3546631091783712)
- 授權條款：[MIT License](../../LICENSE)

贊助完全自願；本專案會繼續維持公益、開源與免費下載。轉載、再次發佈或改作時，請保留
原作者署名與 MIT License 版權聲明。
