# Legacy Forge Modules (1.8.9 & 1.12.2)

本目錄存放 Minecraft 1.8.9 與 1.12.2 的 Forge 獨立模組工程。

## 架構說明

Minecraft 1.8.9 與 1.12.2 的官方構建外掛為 **ForgeGradle 2.x / 3.x**，該版本歷史悠久且強制依賴 **Gradle 4.9** 與 **Java 8**。
由於其 Gradle DSL 語法與現代 Gradle 8.x 完全不相容，因此本目錄下的兩個版本各自擁有獨立的 `gradlew` 封裝，不作為根專案的 subproject 註冊。

* `1.8.9/`: Forge 1.8.9-11.15.1.2318 (Java 8)
* `1.12.2/`: Forge 1.12.2-14.23.5.2860 (Java 8)

## 編譯方式

可直接透過全平台構建工具或單模組工具編譯：
```powershell
.\scripts\build_single.ps1 1.8.9
.\scripts\build_single.ps1 1.12.2
```
或進入該目錄手動執行：
```powershell
.\gradlew.bat build
```
