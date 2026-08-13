# 构建说明

[简体中文](BUILDING.md) · [繁體中文](../Zh-tw/BUILDING.md) · [English](../en/BUILDING.md) · [返回简中 README](README.md)

项目由一个兼容 Java 8 的通用核心、各版本 Fabric、Forge、NeoForge 模块，以及两个独立旧 Forge 构建组成。
旧 ForgeGradle 使用各自的 Wrapper 与 JDK。

## 选择根构建平台

根构建默认只注册 `translator-core`。使用完整任务路径时，Gradle 会自动注册指定平台及整合包依赖模块：

```bash
./gradlew :platform-forge-1.21.1:build
```

IDE 导入、查看项目或同时处理多个平台时，可明确指定稳定的选择器：

```bash
./gradlew -PtargetPlatform=forge-1.21.1 projects
./gradlew -PtargetPlatform=fabric-1.21.11,neoforge-1.21.1 projects
./gradlew -PtargetPlatform=all projects
```

简写名称省略 `platform-` 前缀；`fabric-1.21.x` 等整合包会自动加入所有精确版本实现。
旧有 `FORGE_TARGET` 仍保留兼容性。

根构建使用 Gradle Java Toolchain：核心至 Minecraft 1.21.x 使用 JDK 21 编译，26.x 自动选择 JDK 25；
现有 `options.release` 仍分别生成 Java 8、17、21 或 25 字节码。`JAVA_HOME` 失效时，两个 Wrapper
会改用 `PATH` 中可执行的 `java`。

## Fabric 26.x 单一 JAR

需要 JDK 25 或更高版本：

```bash
./gradlew :platform-fabric-26.x:build
```

输出位于 `platforms/fabric/26.x/bundle/build/libs/`。这个单一 JAR 支持当前已有适配的
Minecraft 26.x 版本：26.1、26.1.1、26.1.2 与 26.2。它内嵌四个精确版本实现，再由 Fabric Loader
选择匹配实现；构建会检查每个内嵌 JAR，并为全部四个版本执行真实 Loader 解析测试。

## Fabric 1.21.x 单一 JAR

需要 JDK 21 或更高版本：

```bash
./gradlew :platform-fabric-1.21.x:build
```

输出位于 `platforms/fabric/1.21/bundle/build/libs/`。这个单一 JAR 支持从 1.21 到 1.21.11 的全部
十二个正式 1.21 版本；它内嵌十二个精确版本实现，再由 Fabric Loader 选择匹配实现。
构建会检查每个内嵌 JAR，并为全部十二个版本执行真实 Loader 解析测试。

## 1.21.11 Fabric

需要 JDK 21 或更高版本：

```bash
./gradlew :platform-fabric-1.21.11:build
```

输出位于 `platforms/fabric/1.21/versions/1.21.11/build/libs/`。

## 1.21.5 与 1.21.4 Fabric

需要 JDK 21 或更高版本：

```bash
./gradlew :platform-fabric-1.21.5:build :platform-fabric-1.21.4:build
```

输出分别位于对应模块的 `build/libs/`。

## 26.x Forge

使用 JDK 25，并在一次建置中选择一个精确目标：

```powershell
./gradlew.bat -PtargetPlatform=forge-26.2 :platform-forge-26.2:build
```

可将 `26.2` 替换为 `26.1`、`26.1.1` 或 `26.1.2`。输出位于 `platforms/forge/26.x/versions/<版本>/build/libs/`。26.1.2 与 26.2 之间的 Forge API 差异由独立适配器处理，每个 JAR 都声明精确的 Minecraft 版本范围。

## 1.21.x Forge

使用 JDK 21。Forge 发布了 Minecraft 1.21、1.21.1 以及 1.21.3 至 1.21.11；Minecraft 1.21.2 没有 Forge 构建。请选择精确目标，避免一次配置所有 ForgeGradle 映射工作区：

```powershell
./gradlew.bat -PtargetPlatform=forge-1.21.10 :platform-forge-1.21.10:build
```

可将 `1.21.10` 替换为 `1.21`、`1.21.1` 或 `1.21.3` 至 `1.21.11` 中的版本。输出位于 `platforms/forge/1.21/versions/<版本>/build/libs/`，每个 JAR 都声明精确的 Minecraft 版本范围。

## 1.20.1 Fabric

需要 JDK 17 或更高版本：

```bash
./gradlew :platform-fabric-1.20.1:build
```

输出位于 `platforms/fabric/legacy/1.20.1/build/libs/`。

## 1.20.1 Forge

需要 JDK 17 或更高版本：

```bash
./gradlew :platform-forge-1.20.1:build
```

Forge 47.4.10 从官方 Maven 解析。1.20.1 仍需将 Mojmap 开发产物转换为 SRG 运行时
命名，因此正式发布必须使用 `platforms/forge/modern/1.20.1/build/libs/` 中的 `-srg.jar`，
不能误发同目录下的无分类器开发 JAR。

## 1.19.2 Forge

需要 JDK 17 或更高版本：

```bash
./gradlew :platform-forge-1.19.2:build
```

Forge 43.5.2 从 Forge 官方 Maven 解析。发布时应使用
`platforms/forge/modern/1.19.2/build/libs/` 中包含运行时映射与 Mixin refmap 的 `-srg.jar`。

## 1.19.2 Fabric

需要 JDK 17 或更高版本：

```bash
./gradlew :platform-fabric-1.19.2:build
```

## 1.16.5 Forge

该适配输出 Java 8 字节码，并使用 Forge 36.2.42：

```bash
./gradlew :platform-forge-1.16.5:build
```

发布时使用 `platforms/forge/modern/1.16.5/build/libs/` 中的 `-srg.jar`。

## 1.16.5 Fabric

需要 JDK 17 或更高版本运行当前 Gradle/Loom，但产物按 Java 8 编译：

```bash
./gradlew :platform-fabric-1.16.5:build
```

## 1.12.2 Forge

需要完整 JDK 8。进入 `legacy/forge-1.12.2/` 后运行：

```bash
./gradlew build
```

Wrapper 固定 Gradle 4.10.3，ForgeGradle 固定 3.0.197，Forge 固定 14.23.5.2860。

## 1.8.9 Forge

需要完整 JDK 8。进入 `legacy/forge-1.8.9/` 后运行：

```bash
./gradlew build
```

Wrapper 固定 Gradle 2.14.1，ForgeGradle 使用 2.1 系列，Forge 固定
11.15.1.2318-1.8.9。

若 Gradle 本身运行在只有 `java`、没有 `javac` 的旧 JRE 中，可以显式指定另一个
兼容编译器：`./gradlew build -PlegacyJavac=/absolute/path/to/javac`。正常完整 JDK 8
环境不需要这个参数。

## 核心自测

根项目的 `translator-core` 不依赖 Minecraft。测试源码位于
`translator-core/src/test/java/`，可以用 JDK 8 兼容编译后运行
`org.universaltranslator.core.CoreSelfTest`。测试覆盖格式保护、动态模板、缓存、
并发去重、失败回退、端点安全、非阻塞渲染和哈希持久化。

不要提交任何本机 `config/universal-translator.properties`、API 密钥、游戏日志、
Gradle 缓存或 Minecraft 资源。
