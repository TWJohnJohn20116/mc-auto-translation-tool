# 构建说明

[简体中文](BUILDING.md) · [繁體中文](../Zh-tw/BUILDING.md) · [English](../en/BUILDING.md) · [返回简中 README](README.md)

项目由一个 Java 8 通用核心、四个现代 Fabric 模块和两个独立旧 Forge 构建组成。
旧 ForgeGradle 不能在现代 JDK 上直接运行，因此不能用一条根 Gradle 命令构建全部版本。

## 26.2 Fabric

需要 JDK 25 或更高版本：

```bash
./gradlew :platform-fabric-26.2:build
```

输出位于 `platform-fabric-26.2/build/libs/`。

## 26.1.2 Fabric

需要 JDK 25 或更高版本：

```bash
./gradlew :platform-fabric-26.1.2:build
```

输出位于 `platform-fabric-26.1.2/build/libs/`。

## 26.1 Fabric

需要 JDK 25 或更高版本：

```bash
./gradlew :platform-fabric-26.1:build
```

输出位于 `platform-fabric-26.1/build/libs/`。

## 1.21.11 Fabric

需要 JDK 21 或更高版本：

```bash
./gradlew :platform-fabric-1.21.11:build
```

输出位于 `platform-fabric-1.21.11/build/libs/`。

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
