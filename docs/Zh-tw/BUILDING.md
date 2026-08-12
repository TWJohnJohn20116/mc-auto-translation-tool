# 建置說明

[简体中文](../Zh-cn/BUILDING.md) · [繁體中文](BUILDING.md) · [English](../en/BUILDING.md) · [返回繁中 README](README.md)

專案由一個 Java 8 通用核心、十一個 Fabric 模組（包含 1.21.4–1.21.5 共用目標與 1.21.x 整合包）、兩個現代 Forge 模組及兩個獨立舊版 Forge 建置組成。
舊版 ForgeGradle 無法直接在現代 JDK 上執行，因此無法用一條根 Gradle 指令建置所有版本。

## 26.2 Fabric

需要 JDK 25 或更新版本：

```bash
./gradlew :platform-fabric-26.2:build
```

輸出位於 `platform-fabric-26.2/build/libs/`。

## 26.1.2 Fabric

需要 JDK 25 或更新版本：

```bash
./gradlew :platform-fabric-26.1.2:build
```

輸出位於 `platform-fabric-26.1.2/build/libs/`。

## 26.1 Fabric

需要 JDK 25 或更新版本：

```bash
./gradlew :platform-fabric-26.1:build
```

輸出位於 `platform-fabric-26.1/build/libs/`。

## Fabric 1.21.x 單一 JAR

需要 JDK 21 或更新版本：

```bash
./gradlew :platform-fabric-1.21.x:build
```

輸出位於 `platform-fabric-1.21.x/build/libs/`。這個單一 JAR 僅支援已驗證的
Minecraft 1.21.4、1.21.5 與 1.21.11；它內嵌兩個精確相容實作，並由 Fabric Loader
依遊戲版本選擇。建置會自動執行巢狀 JAR 內容檢查及三個版本的 Loader 解析測試。

## 1.21.11 Fabric

需要 JDK 21 或更新版本：

```bash
./gradlew :platform-fabric-1.21.11:build
```

輸出位於 `platform-fabric-1.21.11/build/libs/`。

## 1.21.5 與 1.21.4 Fabric

需要 JDK 21 或更新版本：

```bash
./gradlew :platform-fabric-1.21.5:build :platform-fabric-1.21.4:build
```

## 1.21.11 Forge

需要 JDK 21 或更新版本：

```bash
./gradlew :platform-forge-1.21.11:build
```

Forge 61.2.0 從官方 Maven 解析。此版本使用 Mojmap 執行階段，請發佈
`platform-forge-1.21.11/build/libs/` 中沒有分類器的 JAR。

## 1.20.1 Fabric

需要 JDK 17 或更新版本：

```bash
./gradlew :platform-fabric-1.20.1:build
```

輸出位於 `platform-fabric-1.20.1/build/libs/`。

## 1.20.1 Forge

需要 JDK 17 或更新版本：

```bash
./gradlew :platform-forge-1.20.1:build
```

Forge 47.4.10 從官方 Maven 解析。1.20.1 仍需將 Mojmap 開發產物轉換為 SRG 執行階段
命名，因此正式發佈必須使用 `platform-forge-1.20.1/build/libs/` 中的 `-srg.jar`，
不可誤發同一目錄下沒有分類器的開發 JAR。

## 1.19.2 Fabric

需要 JDK 17 或更新版本：

```bash
./gradlew :platform-fabric-1.19.2:build
```

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
