# Building

[简体中文](../Zh-cn/BUILDING.md) · [繁體中文](../Zh-tw/BUILDING.md) · [English](BUILDING.md) · [Back to English README](README.md)

The project consists of one shared Java 8 core, nine Fabric modules, two modern Forge modules,
and two independent legacy Forge builds. Legacy ForgeGradle cannot run directly on modern JDKs, so one root Gradle
command cannot build every version.

## Fabric 26.2

JDK 25 or later is required:

```bash
./gradlew :platform-fabric-26.2:build
```

Output is written to `platform-fabric-26.2/build/libs/`.

## Fabric 26.1.2

JDK 25 or later is required:

```bash
./gradlew :platform-fabric-26.1.2:build
```

Output is written to `platform-fabric-26.1.2/build/libs/`.

## Fabric 26.1

JDK 25 or later is required:

```bash
./gradlew :platform-fabric-26.1:build
```

Output is written to `platform-fabric-26.1/build/libs/`.

## Fabric 1.21.11

JDK 21 or later is required:

```bash
./gradlew :platform-fabric-1.21.11:build
```

Output is written to `platform-fabric-1.21.11/build/libs/`.

## Fabric 1.21.5 and 1.21.4

JDK 21 or later is required:

```bash
./gradlew :platform-fabric-1.21.5:build :platform-fabric-1.21.4:build
```

## Forge 1.21.11

JDK 21 or later is required:

```bash
./gradlew :platform-forge-1.21.11:build
```

Forge 61.2.0 is resolved from the official Maven. This target uses the Mojmap runtime; publish the
unclassified JAR from `platform-forge-1.21.11/build/libs/`.

## Fabric 1.20.1

JDK 17 or later is required:

```bash
./gradlew :platform-fabric-1.20.1:build
```

Output is written to `platform-fabric-1.20.1/build/libs/`.

## Forge 1.20.1

JDK 17 or later is required:

```bash
./gradlew :platform-forge-1.20.1:build
```

Forge 47.4.10 is resolved from the official Maven. Minecraft 1.20.1 still requires the Mojmap
development output to be renamed to the SRG runtime namespace. Release the `-srg.jar` from
`platform-forge-1.20.1/build/libs/`, not the unclassified development JAR beside it.

## Fabric 1.19.2

JDK 17 or later is required:

```bash
./gradlew :platform-fabric-1.19.2:build
```

## Fabric 1.16.5

The current Gradle/Loom build requires JDK 17 or later, while the output targets Java 8:

```bash
./gradlew :platform-fabric-1.16.5:build
```

## Forge 1.12.2

A full JDK 8 installation is required. Enter `legacy/forge-1.12.2/` and run:

```bash
./gradlew build
```

The wrapper is pinned to Gradle 4.10.3, ForgeGradle to 3.0.197, and Forge to 14.23.5.2860.

## Forge 1.8.9

A full JDK 8 installation is required. Enter `legacy/forge-1.8.9/` and run:

```bash
./gradlew build
```

The wrapper is pinned to Gradle 2.14.1, ForgeGradle uses the 2.1 series, and Forge is pinned to
11.15.1.2318-1.8.9.

If Gradle itself is running under a legacy JRE that has `java` but no `javac`, specify another
compatible compiler explicitly with
`./gradlew build -PlegacyJavac=/absolute/path/to/javac`. A normal full JDK 8 installation does not
need this argument.

## Core self-test

The root `translator-core` project does not depend on Minecraft. Its test sources are under
`translator-core/src/test/java/`. Compile them with Java 8 compatibility and run
`org.universaltranslator.core.CoreSelfTest`. The tests cover formatting protection, dynamic
templates, caching, concurrent request coalescing, failure fallback, endpoint security,
non-blocking rendering, and hashed persistence.

Do not commit local `config/universal-translator.properties` files, API keys, game logs, Gradle
caches, or Minecraft assets.
