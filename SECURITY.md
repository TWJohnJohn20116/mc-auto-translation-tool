# 安全政策

## 回報安全漏洞

請**不要**用公開 issue 回報安全問題（本倉庫的 issue 也不作為支援管道）。

請使用 GitHub 的私有漏洞回報，內容只有維護者看得到：

<https://github.com/TWJohnJohn20116/mc-auto-translation-tool/security/advisories/new>

回報時如果能附上：受影響的目標（`mc<版本>-<載入器>`）、重現步驟、以及你認為的影響範圍，會快很多。
修復發布後會在 `CHANGELOG.md` 註明；在修復發布前請勿公開細節。

## 適用範圍

- **模組本體**（`translator-core` 與各平台適配）：玩家名／伺服器位址／IP／URL 被送去翻譯、
  明文 HTTP 放行、任意程式碼執行、崩潰或資源耗盡（記憶體、執行緒、檔案）。
- **網站**（`website/`）：登入與重定向、越權存取、注入、個資或對話內容外洩。
- **發布流程**（`.github/workflows/`、`scripts/`）：發布資產校驗繞過、供應鏈（JAR 與 checksum）。

## English

Please report security issues through GitHub private vulnerability reporting
(<https://github.com/TWJohnJohn20116/mc-auto-translation-tool/security/advisories/new>) rather than
public issues. Include the affected target (`mc<version>-<loader>`), reproduction steps, and impact
if you can. Please do not disclose details publicly before a fix is released.
