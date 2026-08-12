# Building

[简体中文](../Zh-cn/BUILDING.md) · [繁體中文](../Zh-tw/BUILDING.md) · [English](BUILDING.md) · [Back to English README](README.md)

The project consists of one shared Java 8 core, twenty-two Fabric modules (including the shared 1.21.4–1.21.5 target plus the 1.21.x and 26.x bundles), two modern Forge modules,
and two independent legacy Forge builds. Legacy ForgeGradle cannot run directly on modern JDKs, so one root Gradle
command cannot build every version.

## Single Fabric 26.x JAR

JDK 25 or later is required:

```bash
./gradlew :platform-fabric-26.x:build
```

Output is written to `platforms/fabric/26.x/bundle/build/libs/`. This single JAR supports the
currently supported Minecraft 26.x releases: 26.1, 26.1.1, 26.1.2, and 26.2. It embeds four exact-version
implementations, and Fabric Loader selects the matching one. The build verifies every nested JAR
and exercises real Loader resolution for all four versions.

## Single Fabric 1.21.x JAR

JDK 21 or later is required:

```bash
./gradlew :platform-fabric-1.21.x:build
```

Output is written to `platforms/fabric/1.21/bundle/build/libs/`. This single JAR supports every published
Minecraft 1.21 release from 1.21 through 1.21.11. It embeds twelve exact-version implementations,
and Fabric Loader selects the matching one. The build verifies every nested JAR and exercises real
Loader resolution for all twelve versions.

## Fabric 1.21.11

JDK 21 or later is required:

```bash
./gradlew :platform-fabric-1.21.11:build
```

Output is written to `platforms/fabric/1.21/versions/1.21.11/build/libs/`.

## Fabric 1.21.5 and 1.21.4

JDK 21 or later is required:

```bash
./gradlew :platform-fabric-1.21.5:build :platform-fabric-1.21.4:build
```

## Forge 26.x

Use JDK 25 and select one exact target at a time:

```powershell
$env:FORGE_TARGET = "26.2"
./gradlew.bat :platform-forge-26.2:build
```

Replace `26.2` with `26.1`, `26.1.1`, or `26.1.2`. Outputs are written under `platforms/forge/26.x/versions/<version>/build/libs/`. Forge API changes between 26.1.2 and 26.2 are handled by separate adapters and every JAR declares an exact Minecraft range.

## Forge 1.21.x

Use JDK 21. Forge publishes Minecraft 1.21, 1.21.1, and 1.21.3 through 1.21.11; there is no Forge build for Minecraft 1.21.2. Select the exact target to avoid configuring every ForgeGradle mapping workspace at once:

```bash
FORGE_TARGET=1.21.10 ./gradlew :platform-forge-1.21.10:build
```

On PowerShell:

```powershell
$env:FORGE_TARGET = "1.21.10"
./gradlew.bat :platform-forge-1.21.10:build
```

Replace `1.21.10` with `1.21`, `1.21.1`, or any version from `1.21.3` through `1.21.11`. Outputs are written under `platforms/forge/1.21/versions/<version>/build/libs/`. Each JAR deliberately accepts only its exact Minecraft version.

## Fabric 1.20.1

JDK 17 or later is required:

```bash
./gradlew :platform-fabric-1.20.1:build
```

Output is written to `platforms/fabric/legacy/1.20.1/build/libs/`.

## Forge 1.20.1

JDK 17 or later is required:

```bash
./gradlew :platform-forge-1.20.1:build
```

Forge 47.4.10 is resolved from the official Maven. Minecraft 1.20.1 still requires the Mojmap
development output to be renamed to the SRG runtime namespace. Release the `-srg.jar` from
`platforms/forge/modern/1.20.1/build/libs/`, not the unclassified development JAR beside it.

## Forge 1.19.2

JDK 17 or later is required:

```bash
./gradlew :platform-forge-1.19.2:build
```

Forge 43.5.2 is resolved from the official Forge Maven. Publish the `-srg.jar` from
`platforms/forge/modern/1.19.2/build/libs/`; it contains the runtime mappings and Mixin refmap.

## Fabric 1.19.2

JDK 17 or later is required:

```bash
./gradlew :platform-fabric-1.19.2:build
```

## Forge 1.16.5

The adapter targets Java 8 bytecode and Forge 36.2.42:

```bash
./gradlew :platform-forge-1.16.5:build
```

Publish the `-srg.jar` from `platforms/forge/modern/1.16.5/build/libs/`.

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
