# MC Auto Translation Tool

[简体中文](../Zh-cn/README.md) · [繁體中文](../Zh-tw/README.md) · [English](README.md) · [Repository home](../../README.md)

A charity-driven, open-source, client-only full-interface translation mod for Minecraft Java Edition.

[⬇️ Download the latest release](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest) ·
[📚 Language directory](../README.md) · [📖 Installation and usage guide](USER_GUIDE.md)

Original author: [Bilibili creator “我小张7272635”](https://space.bilibili.com/3546631091783712).
Please retain the original author attribution and MIT License copyright notice when redistributing,
republishing, or adapting this project.

The project translates visible text shown by servers, mods, and modpacks, including chat, quest
books, mod settings and menus, scoreboards, the Tab player list, Action Bar messages, titles,
Boss Bars, container titles, item names and lore, signs, books, holograms, and custom entity names.
Player names, numbers, URLs, and Minecraft style codes are preserved by default.

## Downloads

We recommend downloading the latest version from [GitHub Releases](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/latest).
Make sure that the file exactly matches your Minecraft version and mod loader:

| Minecraft | Loader | Download |
| --- | --- | --- |
| 1.8.9 | Forge | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2/MCAutoTranslationTool-1.2-mc1.8.9-forge.jar) |
| 1.12.2 | Forge | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2/MCAutoTranslationTool-1.2-mc1.12.2-forge.jar) |
| 1.20.1 | Fabric | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2/MCAutoTranslationTool-1.2-mc1.20.1-fabric.jar) |
| 1.20.1 | Forge | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2/MCAutoTranslationTool-1.2-mc1.20.1-forge.jar) |
| 1.21.11 | Fabric | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2/MCAutoTranslationTool-1.2-mc1.21.11-fabric.jar) |
| 1.21.11 | Forge | [Download JAR](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2/MCAutoTranslationTool-1.2-mc1.21.11-forge.jar) |

Do not mix JARs across game versions or loaders. Release metadata accepts only the exact Minecraft
versions that passed the build checks; adjacent versions are added only after separate validation.

[View all releases and release notes](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases) ·
[SHA-256 checksum file](https://github.com/wuxiangdan96-byte/mc-auto-translation-tool/releases/download/v1.2/SHA256SUMS.txt)

## Design principles

- The server does not need to install the mod.
- The default provider is an offline model running on the user's computer; no API key or project server is required.
- Offline mode binds only to `127.0.0.1`, so server text does not leave the user's computer.
- LibreTranslate, a Tencent-compatible interface, and OpenAI-compatible LLM APIs are available; API fallback after an offline failure is disabled by default.
- Optional outgoing translation translates normal chat in the background and preserves send order; commands remain unchanged.
- Translation runs in the background. If the service is unavailable, the original text is retained immediately without affecting gameplay.
- Identical text and dynamic text templates use a local cache to reduce latency and cost.
- Mod menus and custom modpack title screens that use Minecraft's font renderer are captured before a world is joined.
- Player names, coordinates, numbers, URLs, and formatting codes are not translated by default.
- Users can prevent private chat or other sensitive content from being sent externally on a per-server basis.

## Version modules

| Minecraft | Loader | Java |
| --- | --- | --- |
| Supported 26.x releases (26.1, 26.1.2, and 26.2; single JAR, development) | Fabric | 25 |
| All 1.21 releases (1.21 through 1.21.11; single JAR, development) | Fabric | 21 |
| 1.21.11 | Forge | 21 |
| 1.20.1 | Forge | 17 |
| 1.20.1 | Fabric | 17 |
| 1.19.2 (development) | Fabric | 17 |
| 1.16.5 (development) | Fabric | 8 |
| 1.12.2 | Forge | 8 |
| 1.8.9 | Forge | 8 |

Fabric 1.21.x and 26.x each provide one bundle JAR. Exact-version implementations remain embedded
inside each bundle and share the same core logic and configuration semantics.

The main branch also contains separate development adapters for Fabric 1.16.5, 1.19.2, 1.21.4,
1.21.5, 26.1, 26.1.2, and 26.2. They are not release downloads until launch and in-server
regression checks are complete.

## Current status

Release 1.2 provides six separate client JARs, adding Fabric and Forge for 1.20.1 and Forge for
1.21.11. All six build lines passed clean builds; modern targets also passed Fabric remapping or
Forge runtime-mapping checks, and the shared core self-tests passed. See the compatibility matrix
for the exact validation level of each target.

On Fabric, press `U` in game to open the settings screen. On Forge 1.20.1/1.21.11, edit
`config/universal-translator.properties` and press `U` to reload it. The mod is disabled by default. New installations
default to the offline provider and translated-only replacement mode, which avoids overflowing
scoreboards and container titles with bilingual text. Press `F8` to toggle translation at any time.
Both shortcuts can be changed in Minecraft's key-binding screen. After the first translatable text
appears, the mod downloads a platform engine of about 10–17 MB and the 491 MB Lite model in the
background. Original text remains visible during the download. Model downloads prefer the
ModelScope mirror for users in China, resume automatically after a failure, and fall back to the
official source. Files are used only after their size and SHA-256 checksum have been verified.
LibreTranslate and the legacy Tencent-compatible mode also remain available.
About three seconds after joining a server, the chat panel displays a local-only `U`/`F8` hint.
It does not send any chat message or packet to the server.

Verified behavior includes:

- Preserving player names, server IP addresses and domains, ports, color codes, numbers, percentages, and URLs.
- Separating protected content locally so it is never sent to the offline model or an online API.
- Normalizing dynamic scoreboard content into reusable templates.
- Caching translations and coalescing identical concurrent requests.
- Skipping network access for text that is already in the target language or contains only numbers.
- Translating only English segments in mixed Chinese-English text while preserving existing Chinese text.
- Applying a separate aqua, green, gold, or other selected color to translations, or retaining the original color.
- Returning the original text when the translation service fails.
- Keeping background translation off the render thread.
- Applying saved settings without restarting the game.
- Allowing external transmission to be disabled independently for chat and other interfaces.
- Keeping outgoing translation disabled by default, with a target language separate from interface translation.
- Translating mod menus, quest/recipe interfaces, and custom modpack title screens without requiring a server connection.
- Installing the offline Lite and Quality models on demand instead of bundling them in the mod JAR.

## Privacy notice

Offline mode does not send server, mod, or modpack text anywhere. Online API mode or “API fallback”
means that selected server text and visible local mod/modpack text may be sent to the translation
service configured by the user. The project
provides a clear master switch, separate switches for chat and other content, and a local cache.
API keys remain in the user's local configuration and must never be committed to the repository.
Remote endpoints must use HTTPS; HTTP is allowed only for exact local loopback addresses.

See the [user guide](USER_GUIDE.md) for detailed installation and usage instructions, and the
[compatibility matrix](COMPATIBILITY.md) for the verified scope and planned version order. The
website source is in `../../website/`.
