# MC Auto Translation Tool

**MC Auto Translation Tool** is a charity-driven, open-source, client-only full-interface translation mod
for Minecraft Java Edition. The server does not need the mod; it translates player-visible text such as
chat, quest books, mod menus, scoreboards, item descriptions, and signs.

[简体中文](../Zh-cn/README.md) · [繁體中文](../Zh-tw/README.md) · [English](README.md) · [Repository home](../../README.md)

[⬇️ Download the latest release](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest) ·
[📖 Install and use](USER_GUIDE.md) ·
[✅ Compatibility matrix](COMPATIBILITY.md) ·
[📝 Changelog](../../CHANGELOG.md)

> See [GitHub Releases](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest) for the
> current stable version and publication date. Use only the JAR matching your Minecraft version and mod loader.

## Why use it

| Feature | Description |
| --- | --- |
| Full-interface translation | Covers chat, quest and recipe screens, mod menus, scoreboards, Tab, titles, boss bars, containers, item names and lore, books, signs, holograms, and more |
| Client-only | Minecraft servers do not need the mod and receive no extra mod network packets |
| Local offline mode | The recommended built-in mode requires no API key; its model and translation content remain on the user's computer |
| Multiple providers | Built-in Chinese MT and LLM services, plus LibreTranslate, OpenAI-compatible, and custom HTTP JSON APIs |
| Responsive and resilient | Translation runs in the background; unfinished requests and service failures keep the original text without blocking rendering |
| Privacy protection | Player names, server addresses, URLs, numbers, and formatting codes are separated locally before a translation request |
| Local cache | Coalesces identical requests and caches translations to reduce latency and remote-service costs |
| Optional outgoing translation | Can translate normal chat and send it in order; this is off by default and commands always remain unchanged |

## Quick start

1. Check the **Minecraft version, mod loader, and Java version** used by the game instance.
2. Open the [latest release](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest) and select a JAR from the table below.
3. Put the JAR in that instance's `mods` folder. Fabric also requires the matching version of Fabric API.
4. Start the game. Press `F8` to toggle automatic translation; press `U` to open settings or reload the configuration.
5. For a first run, choose **Offline** and **Translated only**. See the [installation and usage guide](USER_GUIDE.md) for details.

The settings entry point differs slightly between Forge versions. When manual configuration is required,
edit `config/universal-translator.properties` in the game instance, save it, and press `U` to reload.

## Downloads and version selection

The stable release turns 27 exact-version build artifacts into **13 directly installable JARs**. Fabric uses
one all-version JAR whose embedded implementation is selected by Loader. Forge shares a JAR only between
adjacent versions with verified compatibility.

The table links directly to every JAR in the current stable release. These links are updated with the
README whenever a new stable version is published:

| Minecraft | Loader | Java | Download |
| --- | --- | ---: | --- |
| 1.16.5, 1.19.2, 1.20.1, 1.21–1.21.11, 26.1–26.2 | Fabric | 8 / 17 / 21 / 25 | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.1/MCAutoTranslationTool-1.3.1-fabric-all.jar) |
| 1.8.9 | Forge | 8 | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.1/MCAutoTranslationTool-1.3.1-mc1.8.9-forge.jar) |
| 1.12.2 | Forge | 8 | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.1/MCAutoTranslationTool-1.3.1-mc1.12.2-forge.jar) |
| 1.16.5 | Forge | 8 | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.1/MCAutoTranslationTool-1.3.1-mc1.16.5-forge.jar) |
| 1.19.2 | Forge | 17 | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.1/MCAutoTranslationTool-1.3.1-mc1.19.2-forge.jar) |
| 1.20.1 | Forge | 17 | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.1/MCAutoTranslationTool-1.3.1-mc1.20.1-forge.jar) |
| 1.21, 1.21.1, 1.21.3–1.21.5 | Forge | 21 | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.1/MCAutoTranslationTool-1.3.1-mc1.21-1.21.5-forge.jar) |
| 1.21.6–1.21.8 | Forge | 21 | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.1/MCAutoTranslationTool-1.3.1-mc1.21.6-1.21.8-forge.jar) |
| 1.21.9–1.21.11 | Forge | 21 | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.1/MCAutoTranslationTool-1.3.1-mc1.21.9-1.21.11-forge.jar) |
| 26.1–26.1.2 | Forge | 25 | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.1/MCAutoTranslationTool-1.3.1-mc26.1-26.1.2-forge.jar) |
| 26.2 | Forge | 25 | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.1/MCAutoTranslationTool-1.3.1-mc26.2-forge.jar) |
| 1.20.1 | NeoForge 47.1.106–47.1.x | 17 | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.1/MCAutoTranslationTool-1.3.1-mc1.20.1-neoforge.jar) |
| 1.21.1 | NeoForge 21.1.248 | 21 | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.3.1/MCAutoTranslationTool-1.3.1-mc1.21.1-neoforge.jar) |

> **Do not mix loaders or use a JAR outside the range in its filename.** Adjacent Minecraft releases may
> change rendering APIs and Mixin targets. Release metadata includes only versions that completed validation.

[View the latest release](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest) ·
[View all releases](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases) ·
[View actual validation coverage](COMPATIBILITY.md)

## Translation methods and privacy

| Method | API key required | Text leaves the computer | Best for |
| --- | --- | --- | --- |
| Offline Lite / Quality | No | No | Recommended; downloads the platform engine and selected model on first use |
| Local LibreTranslate | Depends on service | No | Users already running LibreTranslate locally |
| Remote LibreTranslate | Depends on service | Yes | A trusted HTTPS translation service |
| Local OpenAI-compatible API | Depends on service | No | A user-run compatible service such as llama.cpp or Ollama |
| Online OpenAI-compatible API | Usually | Yes | A user-selected HTTPS LLM service |
| Dedicated MT / LLM APIs | Usually | Yes | Baidu, Tencent Cloud, Alibaba Cloud, Youdao, Volcengine, iFlytek, Huawei Cloud, DeepSeek, Qwen, Zhipu, and others |
| Custom HTTP JSON API | Depends on service | Depends on endpoint | User-defined safe request templates, authentication headers, and nested response paths |
| Tencent-compatible interface | Yes | Yes | Legacy configuration compatibility only |

- Offline mode runs only on a loopback address; server, mod, and modpack text is not sent to the project.
- With a remote API, visible text allowed for translation is sent to the user-configured service. The project does not hold API keys.
- API keys are stored only in the current game instance's configuration file. Remote endpoints must use HTTPS.
- Chat content, other interfaces, and player-initiated outgoing translation can be disabled separately. Outgoing translation is off by default.
- Cache keys use SHA-256; original text is not written to the cache file in plaintext.

See [online API configuration](ONLINE_APIS.md) for built-in services and custom requests, and the
[installation and usage guide](USER_GUIDE.md) for other settings, model downloads, and troubleshooting.

## Current validation status

Every target in the current stable release passes a clean build, shared-core self-tests, Mixin/refmap checks,
runtime mapping checks, release-structure validation, and SHA-256 verification. The all-version Fabric JAR
contains 19 exact implementations and passes real Loader selection tests for every target. NeoForge 1.20.1
has also completed a real client launch and mod initialization.

“Builds successfully” does not mean “completed an in-server manual regression.” Build, launch, and manual
validation levels are recorded separately in the [compatibility matrix](COMPATIBILITY.md).

## Documentation

| Document | Contents |
| --- | --- |
| [Installation and usage guide](USER_GUIDE.md) | Installation, first-run setup, offline models, API configuration, and troubleshooting |
| [Compatibility matrix](COMPATIBILITY.md) | Actual validation level for each Minecraft and loader target |
| [Building guide](BUILDING.md) | Development environment, Gradle tasks, and release JAR validation |
| [Online API configuration](ONLINE_APIS.md) | Built-in services, custom HTTP JSON, timeouts, retries, and security constraints |
| [Architecture](ARCHITECTURE.md) | Shared core, platform adapters, translation flow, and caching |
| [Third-party offline services](THIRD_PARTY_OFFLINE.md) | Connecting a local translation service run by the user |
| [Changelog](../../CHANGELOG.md) | Stable and test release changes |
| [Multilingual documentation index](../README.md) | Simplified Chinese, Traditional Chinese, and English documentation |

## Community, authorship, and license

- QQ community group: `1054795488`
- Afdian: [Support “我小张7272635”](https://afdian.com/a/XiaoZhangGG)
- Original author: [Bilibili creator “我小张7272635”](https://space.bilibili.com/3546631091783712)
- License: [MIT License](../../LICENSE)

Sponsorship is entirely optional. The project will remain charity-driven, open source, and free to download.
Please retain the original author attribution and MIT License copyright notice when redistributing,
republishing, or adapting this project.
