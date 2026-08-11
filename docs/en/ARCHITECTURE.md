# Architecture

[简体中文](../Zh-cn/ARCHITECTURE.md) · [繁體中文](../Zh-tw/ARCHITECTURE.md) · [English](ARCHITECTURE.md) · [Back to English README](README.md)

## Why the project does not need a server

The mod only captures, normalizes, and displays text on the player's client. Translation providers
are injected through one interface. A provider can be an online API selected by the player or a
local service running on the player's computer. The project does not proxy chat content, retain
player data, or incur the cost of a central server.

## Data flow

1. A version adapter extracts visible text when a text component is received or rendered.
2. Privacy rules decide whether that content may be sent to a translator.
3. `ProtectedText` locally separates player names, server addresses, numbers, existing Chinese text, and formatting codes.
4. `LanguageHeuristics` skips empty content, numeric-only content, and content already in the target language.
5. `TranslationCoordinator` checks the cache and coalesces identical requests that are already in progress.
6. A provider translates only eligible fragments on a background thread; protected content never enters the request.
7. Translated fragments and protected content are reassembled locally in their original order and displayed on a later frame or render pass.
8. If any step fails, the original server text remains visible.

Optional outgoing translation uses a separate path. The platform cancels the original normal chat
message, the core translates it through a background queue while protecting literal player names
and addresses, and the Minecraft main thread then sends it through the vanilla chat entry point.
Messages retain input order even if concurrent requests finish out of order. Commands, failed
requests, and overlong translations are never silently rewritten.

## Multi-version boundary

`translator-core` remains compatible with Java 8 and does not reference Minecraft classes. Each
loader module is responsible only for:

- converting between Minecraft text components and plain strings;
- capturing text from interfaces and the world;
- safely updating the display cache on the game thread;
- loading the settings screen and key bindings for that version.

This lets legacy Forge and modern Fabric versions share the same algorithms while producing
separate compatible JARs.

## Capture strategy

- Fabric 1.21.11, 26.1, 26.1.2, and 26.2 replace display copies at the corresponding final
  `DrawContext`/`GuiGraphicsExtractor` and `TextRenderer`/`Font` render entry points, while recording
  the content type during chat, scoreboard, Tab list, title, Action Bar, and Boss Bar rendering.
- Forge 1.8.9/1.12.2 use one LaunchWrapper ASM core plugin to replace strings at the
  `FontRenderer` draw and width-calculation entry points. The chat HUD adds a lightweight context
  used to enforce the chat privacy toggle.

Display translation does not modify original network packets, chat signatures, Text components, or
server state. Only when the user explicitly enables outgoing translation is ordinary outgoing chat
replaced with a translation and re-signed through the vanilla send path. Text is outside the current
capture scope if a server prerenders it into an image or a third-party mod bypasses Minecraft's font
renderer entirely.

## Security boundary

- Network translation is disabled by default.
- Non-loopback HTTP addresses are rejected; remote services must use HTTPS.
- Online APIs do not follow HTTP redirects. Connections and reads have timeouts, and responses are limited to 1 MiB.
- Offline component downloads accept only HTTPS and validate resume ranges, type, fixed size, and SHA-256; a failed mirror falls back to the official source.
- Source-text cache keys use SHA-256. The local cache stores only hashed keys and translations, not plaintext source text.
- API keys are not written to logs or packaged in release JARs.
