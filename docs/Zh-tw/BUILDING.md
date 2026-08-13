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
會改用 `PATH` 中可執行的 `java`。

## Fabric 26.x 單一 JAR

需要 JDK 25 或更新版本：

```bash
./gradlew :platform-fabric-26.x:build
```

輸出位於 `platforms/fabric/26.x/bundle/build/libs/`。這個單一 JAR 支援目前已有轉接的
Minecraft 26.x 版本：26.1、26.1.1、26.1.2 及 26.2。它內嵌四個精確版本實作，再由 Fabric Loader
選擇相符實作；建置會檢查每個內嵌 JAR，並對全部四個版本執行真實 Loader 解析測試。

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
命名，因此正式發佈必須使用 `platforms/forge/modern/1.20.1/build/libs/` 中的 `-srg.jar`，
不可誤發同一目錄下沒有分類器的開發 JAR。

## 1.19.2 Forge

需要 JDK 17 或更新版本：

```bash
./gradlew :platform-forge-1.19.2:build
```

Forge 43.5.2 從 Forge 官方 Maven 解析。發佈時應使用
`platforms/forge/modern/1.19.2/build/libs/` 中包含執行階段映射與 Mixin refmap 的 `-srg.jar`。

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

發佈時使用 `platforms/forge/modern/1.16.5/build/libs/` 中的 `-srg.jar`。

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

## 核心自我測試

根專案的 `translator-core` 不依賴 Minecraft。測試原始碼位於
`translator-core/src/test/java/`，以相容 JDK 8 的方式編譯後即可執行
`org.universaltranslator.core.CoreSelfTest`。測試涵蓋格式保護、動態範本、快取、
並行去重、失敗回退、端點安全、非阻塞彩現及雜湊持久化。

請勿提交任何本機 `config/universal-translator.properties`、API 金鑰、遊戲日誌、
Gradle 快取或 Minecraft 資源。
