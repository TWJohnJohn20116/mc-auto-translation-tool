# Building

[简体中文](../Zh-cn/BUILDING.md) · [繁體中文](../Zh-tw/BUILDING.md) · [English](BUILDING.md) · [Back to English README](README.md)

The project consists of one shared Java 8-compatible core, versioned Fabric, Forge, and NeoForge
modules, plus two independent legacy Forge builds. Legacy ForgeGradle uses its own wrapper and JDK.

## Selecting root-build platforms

The root build registers only `translator-core` by default. A fully qualified task path automatically
registers the referenced platform and any bundle dependencies:

```bash
./gradlew :platform-forge-1.21.1:build
```

For IDE import, project discovery, or more than one platform, use the stable selector explicitly:

```bash
./gradlew -PtargetPlatform=forge-1.21.1 projects
./gradlew -PtargetPlatform=fabric-1.21.11,neoforge-1.21.1 projects
./gradlew -PtargetPlatform=all projects
```

Short names omit the `platform-` prefix. Bundle selectors such as `fabric-1.21.x` automatically include
their exact-version implementation modules. `FORGE_TARGET` remains accepted for compatibility.

The root build uses Gradle Java Toolchains: JDK 21 compiles the core and Minecraft versions through
1.21.x, while 26.x selects JDK 25. Existing `options.release` values still produce Java 8, 17, 21, or
25 bytecode as required. If `JAVA_HOME` is stale, both wrappers fall back to a working `java` on `PATH`.

## Single Fabric 26.x JAR

JDK 25 or later is required:

```bash
./gradlew :platform-fabric-26.x:build
```

Output is written to `platforms/fabric/26.x/bundle/build/libs/`. This single JAR supports the
currently supported Minecraft 26.x releases: 26.1, 26.1.1, 26.1.2, and 26.2. It embeds four exact-version
implementations, and Fabric Loader selects the matching one. The build verifies every nested JAR
and exercises real Loader resolution for all four versions.

## Single Fabric 1.17/1.18 JAR

JDK 17 is required. Keep the Gradle distribution and dependency cache on the D: drive with
`GRADLE_USER_HOME`:

```powershell
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
.\gradlew.bat :platform-fabric-1.17-1.18.x:build --max-workers=1
```

Output is written to `platforms/fabric/1.17-1.18/bundle/build/libs/`. This bundle embeds exact-version
implementations for 1.17, 1.17.1, 1.18, 1.18.1, and 1.18.2; Fabric Loader selects the matching
implementation and the build verifies all five selections.

## Single Fabric 1.19.x JAR

JDK 17 is required:

```bash
./gradlew :platform-fabric-1.19.x:build --max-workers=1
```

Output is written to `platforms/fabric/1.19/bundle/build/libs/`. This bundle embeds exact-version
implementations for Minecraft 1.19, 1.19.1, 1.19.2, 1.19.3, and 1.19.4; Fabric Loader selects the
matching implementation and the build verifies all five selections.

## Single Fabric 1.20.x JAR

JDK 17 is required for Minecraft 1.20 through 1.20.4; Minecraft 1.20.5 and 1.20.6 require JDK 21.
The bundle can be built with JDK 21 or later:

```bash
./gradlew :platform-fabric-1.20.x:build --max-workers=1
```

Output is written to `platforms/fabric/1.20/bundle/build/libs/`. This single JAR embeds seven
exact-version implementations for 1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4, 1.20.5, and 1.20.6;
Fabric Loader selects the matching implementation and the build verifies all seven selections.

## Single Fabric 1.16.x JAR

JDK 8 is required for the Minecraft 1.16 series. To keep the Gradle cache on the D: drive:

```powershell
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
.\gradlew.bat :platform-fabric-1.16.x:build --max-workers=1
```

Output is written to `platforms/fabric/1.16/bundle/build/libs/`. The bundle embeds exact-version
implementations for 1.16, 1.16.1, 1.16.2, 1.16.3, 1.16.4, and 1.16.5; Fabric Loader selects the
matching implementation and the build verifies all six selections.

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
./gradlew.bat -PtargetPlatform=forge-26.2 :platform-forge-26.2:build
```

Replace `26.2` with `26.1`, `26.1.1`, or `26.1.2`. Outputs are written under `platforms/forge/26.x/versions/<version>/build/libs/`. Forge API changes between 26.1.2 and 26.2 are handled by separate adapters and every JAR declares an exact Minecraft range.

## Forge 1.21.x

Use JDK 21. Forge publishes Minecraft 1.21, 1.21.1, and 1.21.3 through 1.21.11; there is no Forge build for Minecraft 1.21.2. Select the exact target to avoid configuring every ForgeGradle mapping workspace at once:

```bash
./gradlew -PtargetPlatform=forge-1.21.10 :platform-forge-1.21.10:build
```

On PowerShell:

```powershell
./gradlew.bat -PtargetPlatform=forge-1.21.10 :platform-forge-1.21.10:build
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
development output to be renamed to the SRG runtime namespace. The build marks these artifacts as
`-dev.jar` and `-runtime.jar`; publish the normalized JAR from `build/release/`.

## Forge 1.19.2

JDK 17 or later is required:

```bash
./gradlew :platform-forge-1.19.2:build
```

Forge 43.5.2 is resolved from the official Forge Maven. Publish the normalized JAR from
`platforms/forge/modern/1.19.2/build/release/`; it contains the runtime mappings and Mixin refmap.

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

Publish the normalized runtime JAR from `platforms/forge/modern/1.16.5/build/release/`.

## Single Fabric 1.14/1.15 JAR

The 1.14 and 1.15 compatibility modules target Java 8. Keep the Gradle cache on the D: drive:

```powershell
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
.\gradlew.bat :platform-fabric-1.14-1.15.x:build --max-workers=1
```

Output is written to `platforms/fabric/1.14-1.15/bundle/build/libs/`. The bundle embeds exact-version
implementations for 1.14, 1.14.1, 1.14.2, 1.14.3, 1.14.4, 1.15, 1.15.1, and 1.15.2; Fabric Loader
selects the matching implementation and the build verifies all eight selections.

## Legacy Fabric 1.13.2

Legacy Fabric 1.13 support is provided by the 1.13.2 target and uses Java 8. Keep the Gradle cache on the D: drive:

```powershell
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
.\gradlew.bat :platform-fabric-1.13.2:build --max-workers=1
```

Output is written to `platforms/fabric/1.13/versions/1.13.2/build/libs/`.

## Fabric 1.13.0 and 1.13.1 (Ornithe)

Ornithe provides Fabric Loader profiles with Calamus intermediary mappings for these two releases.
The launcher calls Minecraft 1.13.0 simply `1.13`; the generated artifact keeps the `1.13.0` alias
in its file name. Both targets use Java 8 and are built separately:

```powershell
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
.\gradlew.bat :platform-fabric-1.13.0:build :platform-fabric-1.13.1:build --max-workers=1
```

Outputs are written to `platforms/fabric/1.13/versions/1.13.0/build/libs/` and
`platforms/fabric/1.13/versions/1.13.1/build/libs/`.

## Fabric 1.8.9 through 1.12.2 (Ornithe bundle)

Ornithe provides Fabric Loader profiles and Calamus intermediary mappings for all 15 releases in
this range. A single nested-JAR bundle selects the exact implementation for the active Minecraft
version, reducing the installable output to one JAR:

```powershell
$env:GRADLE_USER_HOME = "D:\Gradle\cache"
.\gradlew.bat :platform-fabric-1.8-1.12.x:build --max-workers=1
```

The verified bundle is written to
`platforms/fabric/1.8-1.12/bundle/build/libs/mc-auto-translation-tool-fabric-1.8.9-1.12.2-1.3.0.jar`.

## Fabric 1.16.5

The current Gradle/Loom build requires JDK 17 or later, while the output targets Java 8:

```bash
./gradlew :platform-fabric-1.16.5:build
```

The Fabric and Forge 1.16.5, 1.19.2, and 1.20.1 modules compose stable code from
`platforms/fabric/legacy/shared/` and `platforms/forge/modern/shared/`. Keep API-specific UI and
Mixin adapters in their exact-version directories instead of adding compatibility branches to the
shared runtime.

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

## Release JAR verification

The root check validates every tracked release JAR and its checksum:

```bash
./gradlew check
```

To validate arbitrary build outputs directly, pass them to the standalone verifier:

```bash
python3 scripts/verify_release_jars.py path/to/mod.jar
python3 scripts/verify_release_jars.py \
  --checksum-file downloads/1.3.0/SHA256SUMS.txt \
  --require-complete-checksums downloads/1.3.0/*.jar
```

It checks ZIP structure, expanded metadata, entrypoints, declared Mixin classes and refmaps, nested
Fabric JARs, legacy Forge runtime mappings, SHA-256 values, and checksum-manifest coverage. Legacy
Fabric and Forge `build` tasks run the same verifier against their generated release JARs.

The publish workflow reduces the 27 exact build artifacts to 13 directly installable JARs. It
flattens all 19 Fabric implementations into one Loader-selected bundle, broadens four Forge
families after confirming compatible payloads, and keeps Forge/NeoForge 1.20.1 as separately
verified loader-specific JARs. Different APIs remain separate. Reproduce and validate
the publish set with:

```bash
python3 scripts/prepare_release_assets.py \
  --release-dir downloads/1.3.0 \
  --output-dir build/release-assets \
  --version 1.3.0
python3 scripts/verify_release_jars.py \
  --checksum-file build/release-assets/SHA256SUMS.txt \
  --require-complete-checksums build/release-assets/*.jar
```

## Core self-test

The root `translator-core` project does not depend on Minecraft. Its test sources are under
`translator-core/src/test/java/`. Compile them with Java 8 compatibility and run
`org.universaltranslator.core.CoreSelfTest`. The tests cover formatting protection, dynamic
templates, caching, concurrent request coalescing, failure fallback, endpoint security,
non-blocking rendering, and hashed persistence.

Do not commit local `config/universal-translator.properties` files, API keys, game logs, Gradle
caches, or Minecraft assets.
