# Compatibility matrix

[简体中文](../Zh-cn/COMPATIBILITY.md) · [繁體中文](../Zh-tw/COMPATIBILITY.md) · [English](COMPATIBILITY.md) · [Back to English README](README.md)

This document records only completed validation. “Builds successfully” is not reported as
“compatible.”

## Version 1.3.1 release validation

| Minecraft | Loader | Java | Build and self-test | Launched to main menu | Full manual in-server regression |
| --- | --- | --- | --- | --- | --- |
| 1.8.9 | Forge 11.15.1.2318 | 8 | Passed | Historical baseline passed | Pending confirmation |
| 1.12.2 | Forge 14.23.5.2860 | 8 | Passed | Historical baseline passed | Pending confirmation |
| All 1.14/1.15 releases (1.14-1.15.2), single JAR | Fabric Loader 0.19.3 + Fabric API 0.28.5 | 8 | Passed; exact Loader selection passed for all eight versions | Pending | Pending |
| All 1.16 releases (1.16-1.16.5), single JAR | Fabric Loader 0.19.3 + Fabric API 0.42.0 | 8 | Passed; exact Loader selection passed for all six versions | Pending | Pending |
| 1.16.5 | Fabric Loader 0.19.3 + Fabric API 0.42.0 | 8 | Passed; metadata and Mixins checked | Pending | Pending |
| All 1.17/1.18 releases (1.17-1.18.2), single JAR | Fabric Loader 0.19.3 | 17 | Passed; exact Loader selection passed for all five versions | Pending | Pending |
| 1.16.5 | Forge 36.2.42 | 8 | Passed; SRG and refmap checked | Pending | Pending |
| 1.19.2 | Fabric Loader 0.19.3 + Fabric API 0.77.0 | 17 | Passed; metadata and Mixins checked | Pending | Pending |
| All 1.19 releases (1.19-1.19.4), single JAR | Fabric Loader 0.19.3 | 17 | Passed; exact Loader selection passed for all five versions | Pending | Pending |
| 1.19.2 | Forge 43.5.2 | 17 | Passed; SRG and refmap checked | Pending | Pending |
| 1.20.1 | Fabric Loader 0.18.1 + Fabric API 0.92.11 | 17 | Passed | Pending | Pending |
| All 1.20 releases (1.20-1.20.6), single JAR | Fabric Loader 0.15.11+ | 17 / 21 | Passed; exact Loader selection passed for all seven versions | Pending | Pending |
| 1.20.1 | Forge 47.4.10 | 17 | Passed; SRG and refmap checked | Pending | Pending |
| 1.20.1 | NeoForge 47.1.106 | 17 | Passed; metadata, Mixins, and refmap checked | Passed; mod initialized | Pending |
| 1.21.1 | NeoForge 21.1.248 | 21 | Passed; metadata and Mixins checked | Pending | Pending |
| 1.21.11 | Fabric Loader 0.18.1 + Fabric API 0.141.4 | 21 | Passed | Historical baseline passed | Pending confirmation |
| All 1.21 releases (1.21–1.21.11), single JAR | Fabric Loader 0.19.3 | 21 | Passed; Loader selection passed for all twelve versions | Pending | Pending |
| 1.21, 1.21.1, 1.21.3–1.21.11 | Forge 51.0.33–61.2.0 | 21 | Compile passed for every published game target; exact metadata ranges | 1.21.11 blocked by headless display | Pending |
| 26.1, 26.1.1, 26.1.2, 26.2 | Forge 62.0.9–65.1.1 | 25 | Compile passed for all four exact targets | Pending | Pending |
| 26.1, 26.1.1, 26.1.2, 26.2, single JAR | Fabric Loader 0.19.3 | 25 | Passed; Loader selection passed for all four versions | 26.1 baseline passed | Pending |

The stable release starts from 27 formally shipped target JARs that share the same translation core
and reduces them to 13 directly installable release JARs. Additional adjacent-version source targets
are build-tested separately and are not counted as stable downloads. Fabric 1.20.1 completed Loom remapping.
The Forge 1.20.1 artifact was renamed into the SRG runtime namespace and contains a refmap for all
nine Mixin classes. Forge 1.21.11 was adapted to the Forge 7 event bus and Mojmap runtime. Its
startup passed ForgeBootstrap and reached GLFW graphics initialization, then stopped because the
automation environment exposes no primary monitor. That is not a mod failure, but it is not
reported as a successful main-menu launch either.

The 1.3.1 provider factory, custom JSON templates, nested response paths, signing digests, endpoint
and header safety, bounded retries, and configuration migration passed core tests without real keys or
external traffic. End-to-end provider calls still require a user's own account, quota, and region, so
this table does not describe local protocol tests as successful calls to paid APIs.

The single 1.21.x JAR embeds twelve exact-version implementations rather than widening one Mixin
build across incompatible game APIs. Each nested implementation declares one Minecraft version,
and Fabric Loader selects the exact match for 1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5,
1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, or 1.21.11.

Every loader metadata file is pinned to the exact Minecraft version that passed build validation.
Pending launch and in-server checks remain explicitly marked and are never used to guess adjacent-version compatibility.

## The principle behind “support every version”

Minecraft generations use different loaders, Java versions, rendering APIs, and text-component
structures. One JAR cannot safely cover every Java Edition version. The project adapts
representative, long-lived versions individually and keeps a separate JAR for each release line.
A target enters the verified table only after build, launch, and in-server interface regression.

Planned candidates, in order:

1. 1.12.2 Cleanroom;
2. 1.7.10 Forge;
3. 1.16.5 Forge;
4. 1.18.2 Forge/Fabric;
5. 1.21.1 Fabric/NeoForge;
6. later releases that retain a substantial player base.

“Nearby version” support is never guessed by widening the version range in `fabric.mod.json` or
`mods.toml`. For example, the 1.20.1 JAR intentionally rejects 1.20.2, and Fabric and Forge JARs
are never interchangeable. Small descriptor or event-ABI changes can otherwise turn into startup
Mixin failures. Adjacent versions may reuse the shared core, but each needs a platform adapter and
the same build, mapping, launch, and in-server checks before being listed as supported.

## In-server regression checklist

- chat, scoreboard, Tab list, Action Bar, titles, and Boss Bar;
- chest/menu titles, item names and Lore, signs, books, entity names, and holographic text;
- mod settings, quest books, recipe screens, and custom modpack title screens before joining a world;
- mod/modpack text rendered through Minecraft's font is captured, while image text and custom renderers remain explicit limitations;
- text entered and sent by the player remains unchanged;
- online player names, the local player name, and server IP/domain/port remain unchanged;
- existing Chinese is not translated again, while English fragments can be translated;
- translated-only mode does not overflow with bilingual text, and color options work;
- the `U` control panel and `F8` master toggle work at different window sizes;
- the game stays responsive and keeps the original text while a model downloads or when a download or offline engine fails.
