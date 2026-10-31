# 建置說明

[简体中文](../Zh-cn/BUILDING.md) · [繁體中文](BUILDING.md) · [English](../en/BUILDING.md) · [返回繁中 README](README.md)

專案由一個相容 Java 8 的通用核心、各版本 Fabric、Forge、NeoForge 模組，以及兩個獨立舊版 Forge 建置組成。
舊版 ForgeGradle 使用各自的 Wrapper 與 JDK。

## 選擇根建置平台

根建置預設只登錄 `translator-core`。使用完整任務路徑時，Gradle 會自動登錄指定平台及整合包相依模組：

```bash
./gradlew :platform-forge-1.21.1:build
```

IDE 匯入、查看專案或同時處理多個平台時，可明確指定穩定的選擇器：

```bash
./gradlew -PtargetPlatform=forge-1.21.1 projects
./gradlew -PtargetPlatform=fabric-1.21.11,neoforge-1.21.1 projects
./gradlew -PtargetPlatform=all projects
```

簡寫名稱省略 `platform-` 前綴；`fabric-1.21.x` 等整合包會自動加入所有精確版本實作。
舊有 `FORGE_TARGET` 仍保留相容性。

根建置使用 Gradle Java Toolchain：核心至 Minecraft 1.21.x 使用 JDK 21 編譯，26.x 自動選擇 JDK 25；
既有 `options.release` 仍分別產生 Java 8、17、21 或 25 位元碼。`JAVA_HOME` 失效時，兩個 Wrapper
會改用 `PATH` 中可執行的 `java`。僅供本機相容性診斷時，可使用
`-PtoolchainVersion=25` 覆寫根建置的編譯 Toolchain；正式發行仍必須分別以目標要求的
JDK 21／25 完成全部建置，此參數不能取代執行階段驗證。

## Fabric 26.x 單一 JAR

需要 JDK 25 或更新版本：

```bash
./gradlew :platform-fabric-26.x:build
```

輸出位於 `platforms/fabric/26.x/bundle/build/libs/`。這個單一 JAR 支援目前已有轉接的
Minecraft 26.x 版本：26.1、26.1.1、26.1.2、26.2 及 26.3。它內嵌五個精確版本實作，再由 Fabric Loader
選擇相符實作；建置會檢查每個內嵌 JAR，並對全部五個版本執行真實 Loader 解析測試。

## 單一 Fabric 1.17/1.18 JAR

需要 JDK 17。請將 Gradle 發行版與相依套件快取放在 D: 磁碟，並設定 `GRADLE_USER_HOME`：

```powershell
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
.\gradlew.bat :platform-fabric-1.17-1.18.x:build --max-workers=1
```

輸出位於 `platforms/fabric/1.17-1.18/bundle/build/libs/`。該套件內嵌 1.17、1.17.1、1.18、1.18.1
與 1.18.2 的精確版本實作；Fabric Loader 會選擇相符實作，建置會驗證全部五個選擇。

## 單一 Fabric 1.19.x JAR

需要 JDK 17：

```bash
./gradlew :platform-fabric-1.19.x:build --max-workers=1
```

輸出位於 `platforms/fabric/1.19/bundle/build/libs/`。該套件內嵌 Minecraft 1.19、1.19.1、1.19.2、
1.19.3 與 1.19.4 的精確版本實作；Fabric Loader 會選擇相符實作，建置會驗證全部五個選擇。

## 單一 Fabric 1.20.x JAR

Minecraft 1.20 至 1.20.4 需要 JDK 17；1.20.5 與 1.20.6 需要 JDK 21。該套件可使用 JDK 21 或更新版本建置。

```bash
./gradlew :platform-fabric-1.20.x:build --max-workers=1
```

輸出位於 `platforms/fabric/1.20/bundle/build/libs/`。這個單一 JAR 內嵌 1.20、1.20.1、1.20.2、1.20.3、
1.20.4、1.20.5 與 1.20.6 的精確版本實作；Fabric Loader 會選擇相符實作，建置會驗證全部七個選擇。

## 單一 Fabric 1.16.x JAR

Minecraft 1.16 系列需要 JDK 8。請將 Gradle 快取放在 D: 磁碟：

```powershell
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
.\gradlew.bat :platform-fabric-1.16.x:build --max-workers=1
```

輸出位於 `platforms/fabric/1.16/bundle/build/libs/`。該套件內嵌 1.16 至 1.16.5 的精確版本實作；
Fabric Loader 會選擇相符實作，建置會驗證全部六個選擇。

## Fabric 1.21.x 單一 JAR

需要 JDK 21 或更新版本：

```bash
./gradlew :platform-fabric-1.21.x:build
```

輸出位於 `platforms/fabric/1.21/bundle/build/libs/`。這個單一 JAR 支援從 1.21 到 1.21.11 的全部
十二個正式 1.21 版本；它內嵌十二個精確版本實作，再由 Fabric Loader 選擇相符實作。
建置會檢查每個內嵌 JAR，並對全部十二個版本執行真實 Loader 解析測試。

## 1.21.11 Fabric

需要 JDK 21 或更新版本：

```bash
./gradlew :platform-fabric-1.21.11:build
```

輸出位於 `platforms/fabric/1.21/versions/1.21.11/build/libs/`。

## 1.21.5 與 1.21.4 Fabric

需要 JDK 21 或更新版本：

```bash
./gradlew :platform-fabric-1.21.5:build :platform-fabric-1.21.4:build
```

## 26.x Forge

使用 JDK 25，並在一次建置中選擇一個精確目標：

```powershell
./gradlew.bat -PtargetPlatform=forge-26.2 :platform-forge-26.2:build
```

可將 `26.2` 替換為 `26.1`、`26.1.1` 或 `26.1.2`。輸出位於 `platforms/forge/26.x/versions/<版本>/build/libs/`。26.1.2 與 26.2 之間的 Forge API 差異由獨立轉接器處理，每個 JAR 都宣告精確的 Minecraft 版本範圍。

## 1.21.x Forge

使用 JDK 21。Forge 發佈了 Minecraft 1.21、1.21.1 以及 1.21.3 至 1.21.11；Minecraft 1.21.2 沒有 Forge 建置。請選擇精確目標，避免一次設定所有 ForgeGradle 映射工作區：

```powershell
./gradlew.bat -PtargetPlatform=forge-1.21.10 :platform-forge-1.21.10:build
```

可將 `1.21.10` 替換為 `1.21`、`1.21.1` 或 `1.21.3` 至 `1.21.11` 中的版本。輸出位於 `platforms/forge/1.21/versions/<版本>/build/libs/`，每個 JAR 都宣告精確的 Minecraft 版本範圍。

## 1.20.1 Fabric

需要 JDK 17 或更新版本：

```bash
./gradlew :platform-fabric-1.20.1:build
```

輸出位於 `platforms/fabric/legacy/1.20.1/build/libs/`。

## 1.20.1 Forge

需要 JDK 17 或更新版本：

```bash
./gradlew :platform-forge-1.20.1:build
```

Forge 47.4.10 從官方 Maven 解析。1.20.1 仍需將 Mojmap 開發產物轉換為 SRG 執行階段
命名。建置產物會標記為 `-dev.jar` 與 `-runtime.jar`；發佈時使用 `build/release/` 中的標準命名 JAR。

## 1.18.2 Forge

需要 JDK 17 或更新版本：

```bash
./gradlew :platform-forge-1.18.2:build
```

Forge 40.2.21 從 Forge 官方 Maven 解析。發佈時應使用
`platforms/forge/modern/1.18.2/build/release/` 中包含執行階段映射與 Mixin refmap 的標準命名 JAR。
1.18.2 仍使用 `TextComponent`／`TranslatableComponent` 與未簽名的 `LocalPlayer.chat`，
因此不與 1.19.2+ 共用 family 層。

## 1.19.2 Forge

需要 JDK 17 或更新版本：

```bash
./gradlew :platform-forge-1.19.2:build
```

Forge 43.5.2 從 Forge 官方 Maven 解析。發佈時應使用
`platforms/forge/modern/1.19.2/build/release/` 中包含執行階段映射與 Mixin refmap 的標準命名 JAR。

## 1.19.2 Fabric

需要 JDK 17 或更新版本：

```bash
./gradlew :platform-fabric-1.19.2:build
```

## 1.16.5 Forge

此轉接輸出 Java 8 位元碼，並使用 Forge 36.2.42：

```bash
./gradlew :platform-forge-1.16.5:build
```

發佈時使用 `platforms/forge/modern/1.16.5/build/release/` 中的標準命名執行階段 JAR。

## 單一 Fabric 1.14/1.15 JAR

1.14 與 1.15 相容模組使用 Java 8。請將 Gradle 快取放在 D: 磁碟：

```powershell
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
.\gradlew.bat :platform-fabric-1.14-1.15.x:build --max-workers=1
```

輸出位於 `platforms/fabric/1.14-1.15/bundle/build/libs/`。該套件內嵌 1.14 至 1.15.2 的精確版本實作；建置會驗證全部八個選擇。

## 單一 Fabric 1.13.x JAR

1.13、1.13.1（Ornithe）與 1.13.2（Legacy Fabric）使用 Java 8。請將 Gradle 快取放在 D: 磁碟：

```powershell
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
.\gradlew.bat :platform-fabric-1.13.x:build --max-workers=1
```

輸出位於 `platforms/fabric/1.13/bundle/build/libs/`。啟動器把 Minecraft 1.13.0 標記為 `1.13`；
內嵌 JAR 與 Loader 選擇均使用該別名。

## Legacy Fabric 1.13.2

Legacy Fabric 1.13 支援由 1.13.2 目標提供，使用 Java 8。請將 Gradle 快取放在 D: 磁碟：

```powershell
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
.\gradlew.bat :platform-fabric-1.13.2:build --max-workers=1
```

輸出位於 `platforms/fabric/1.13/versions/1.13.2/build/libs/`。

## Fabric 1.13.0 與 1.13.1（Ornithe）

Ornithe 為這兩個版本提供 Fabric Loader 設定與 Calamus intermediary 對映。啟動器將 Minecraft
1.13.0 標示為 `1.13`；產出的檔名保留 `1.13.0` 別名。兩個目標都使用 Java 8，可一次建置：

```powershell
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
.\gradlew.bat :platform-fabric-1.13.0:build :platform-fabric-1.13.1:build --max-workers=1
```

輸出位於 `platforms/fabric/1.13/versions/1.13.0/build/libs/` 與
`platforms/fabric/1.13/versions/1.13.1/build/libs/`。

## Fabric 1.3.1–1.8.8（Ornithe bundle）

Ornithe 目前為這個區間裡的 26 個正式版本提供 Fabric Loader Gen2 設定。bundle 依遊戲版本
選擇精確的內嵌實作，把這批舊版本收成一個可安裝 JAR：

```powershell
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
.\gradlew.bat :platform-fabric-1.0-1.8.x:build --max-workers=1
```

驗證過的 bundle 輸出到
`platforms/fabric/1.0-1.8/bundle/build/libs/mc-auto-translation-tool-fabric-1.3.1-1.8.8-<mod_version>.jar`。

## Fabric 1.8.9–1.12.2（Ornithe bundle）

Ornithe 為這個區間的全部 15 個版本提供 Fabric Loader 設定與 Calamus intermediary 對映。
bundle 用一份 `META-INF/jars/` 巢狀實作依目前遊戲版本選擇，把可安裝產物收成一個 JAR：

```powershell
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
.\gradlew.bat :platform-fabric-1.8-1.12.x:build --max-workers=1
```

驗證過的 bundle 輸出到
`platforms/fabric/1.8-1.12/bundle/build/libs/mc-auto-translation-tool-fabric-1.8.9-1.12.2-<mod_version>.jar`。

## 1.16.5 Fabric

目前 Gradle／Loom 建置需要 JDK 17 或更新版本，但輸出仍以 Java 8 為目標：

```bash
./gradlew :platform-fabric-1.16.5:build
```

## 1.12.2 Forge

需要完整 JDK 8。進入 `legacy/forge-1.12.2/` 後執行：

```bash
./gradlew build
```

Wrapper 固定為 Gradle 4.10.3，ForgeGradle 固定為 3.0.197，Forge 固定為 14.23.5.2860。

## 1.8.9 Forge

需要完整 JDK 8。進入 `legacy/forge-1.8.9/` 後執行：

```bash
./gradlew build
```

Wrapper 固定為 Gradle 2.14.1，ForgeGradle 使用 2.1 系列，Forge 固定為
11.15.1.2318-1.8.9。

若 Gradle 本身執行於只有 `java` 而沒有 `javac` 的舊版 JRE，可以明確指定另一個
相容編譯器：`./gradlew build -PlegacyJavac=/absolute/path/to/javac`。一般完整 JDK 8
環境不需要此參數。

## 發佈 JAR 驗證與縮減

PR 必經的 `./gradlew check` **不驗發佈資產**：它需要 `downloads/<mod_version>`，而那個目錄是發佈後
才存在的，把它掛回 `check` 會讓「改版本號」與「必經檢查」互鎖（C2-163）。發佈資產的驗證入口是三個：
`./gradlew verifyReleaseJars` 驗證任意已建置的 JAR（ZIP/metadata/Mixin/refmap/選版）；
`./gradlew verifyPreparedReleaseAssets` 驗證 `downloads/<版本>` 的 48 顆與縮減後的 31 顆；
`./gradlew verifyPublishedRelease` 是上面兩者的聚合，由 `prepare-release.yml` 與 `build.yml` 的
`release-assets` job 明確呼叫（後者在 `downloads/<mod_version>` 不存在時整步略過）。

也可以直接驗證任意建置產物：

```bash
python3 scripts/verify_release_jars.py path/to/mod.jar
```

驗證器會檢查 ZIP 結構、metadata、entrypoint、Mixin class/refmap、Fabric 內嵌 JAR、
Forge 舊版執行階段映射、版本一致性、checksum 內容及覆蓋範圍。Fabric/Forge 1.16.5、
1.19.2 與 1.20.1 的 `build` 任務也會驗證其產生的 JAR。

驗證與縮減是兩件事：驗證只確認產物結構正確，縮減才改變產物數量。發佈流程會將 48 個發佈建置產物縮減為 31 個可直接安裝的 JAR：51 個 Fabric 實作會
放進一個由 Loader 自動選版的 JAR；四組 Forge 版本在 payload 相容時擴寬範圍；
Forge／NeoForge 1.20.1 保留為分別驗證的載入器專用 JAR。不同 API 仍維持獨立：

```bash
python3 scripts/prepare_release_assets.py \
  --release-dir downloads/1.4 \
  --output-dir build/release-assets \
  --version 1.4
```

發佈目標與 source-only bundle 是兩種不同的東西，混成一類就會寫出與實際產出相反的說明：

| 類別 | 內容 | 是否隨正式版發佈 |
| --- | --- | --- |
| 發佈目標（48 個） | Fabric 1.13–26.3（連續）、Forge 1.8.9／1.12.2／1.16.5／1.18.2／1.19.2／1.20.1／1.21、1.21.1、1.21.3–1.21.11／26.1–26.2（缺口版本由 `mods.toml` 拒絕載入）、NeoForge 1.20.1–26.2（每版一顆） | 是，縮減為 31 個可安裝 JAR |
| source-only bundle（2 個） | `fabric-1.0-1.8.x`（1.3.1–1.8.8 當中的 26 個版本）與 `fabric-1.8-1.12.x`（1.8.9–1.12.2，15 個版本） | 否，只在 `legacy-targets.yml` 建置，從不出現在 `downloads/<版本>/` |

所以 `fabric-all` 只含 1.13–26.3 的 51 個精確實作；1.3.1–1.12.2 的玩家要自行建置這兩個 bundle。
要把覆蓋範圍寫進 CHANGELOG 或 README 時引用這一節，不要另寫一份數字。

## 核心自我測試

根專案的 `translator-core` 不依賴 Minecraft。測試原始碼位於
`translator-core/src/test/java/`，以相容 JDK 8 的方式編譯後即可執行
`org.universaltranslator.core.CoreSelfTest`。測試涵蓋格式保護、動態範本、快取、
並行去重、失敗回退、端點安全、非阻塞彩現及雜湊持久化。

請勿提交任何本機 `config/universal-translator.properties`、API 金鑰、遊戲日誌、
Gradle 快取或 Minecraft 資源。
