# MC Auto Translation Tool installation and usage guide

[简体中文](../Zh-cn/USER_GUIDE.md) · [繁體中文](../Zh-tw/USER_GUIDE.md) · [English](USER_GUIDE.md) · [Back to English README](README.md)

## Choose the correct file

Each Minecraft version requires its matching JAR. Do not mix them:

| Minecraft | Mod loader | Java | Also required |
| --- | --- | --- | --- |
| 26.1, 26.1.1, 26.1.2, 26.2 (single JAR) | Fabric | 25 | Matching Fabric API |
| 1.21–1.21.11 (single JAR) | Fabric | 21 | Matching Fabric API |
| 1.21.11 | Forge 61.2.0+ | 21 | No additional mod |
| 1.21.11 | Fabric | 21 | Fabric API |
| 1.20.1 | Forge 47.4.10+ | 17 | No additional mod |
| 1.20.1 | Fabric | 17 | Fabric API |
| 1.19.2 (no released JAR yet) | Fabric | 17 | Fabric API |
| 1.16.5 (no released JAR yet) | Fabric | 8 | Fabric API |
| 1.12.2 | Forge 14.23.5.x | 8 | No additional mod |
| 1.8.9 | Forge 11.15.1.x | 8 | No additional mod |

Place the correct JAR in the game instance's `mods` folder. The server does not need this mod.
The 1.20.1 and 1.21.11 JARs accept only the exact game version in the table. Do not use them on
adjacent versions or interchange Fabric and Forge files.

## First-time setup

The graphical steps below apply to Fabric, Forge, and NeoForge. Open the same
settings screen from the “Translation settings” button at the top-right of
Minecraft's title screen, by pressing `U` in-game, or from Mod Menu's mods list
when that mod is installed.
The newly redesigned interface features a clean, tabbed layout organized into four categories (🌐 General, 🎯 Scopes, ⚡ Engine, 💬 Outgoing):

1. **🌐 General Tab**:
   - Master toggle for automatic translation.
   - Select “Target language” (Traditional Chinese, Simplified Chinese, English, Japanese, etc.) directly from the in-place picker.
   - Select “Display mode” (Translated only, Original + translated, etc.).
   - Configure “Translation color” (Aqua, Green, Yellow, etc., or keep original color).
   - Toggle “Local cache” and “UI style” (Animated / Classic).
   - Access full-width “Translation Diagnostics” button to verify status and engine connectivity.
2. **🎯 Scopes Tab**:
   - Granular translation scopes: Chat messages, other mod interfaces, vanilla UI, and player names.
   - Enable “Translate only English in mixed text” to avoid duplicate translations.
   - Configure “Ignored keywords” (comma-separated list skipped during translation).
3. **⚡ Engine Tab**:
   - Switch “Translation service”: Offline local model (llama.cpp), Google, Microsoft, Tencent, LLM (OpenAI/Ollama), or custom API.
   - In Offline mode, select model tier (Lite / Quality), auto-download, and online fallback.
   - In Online / Custom API / LLM modes, configure endpoints and advanced options.
4. **💬 Outgoing Tab**:
   - Enable or disable “Outgoing translation” (disabled by default).
   - Select outgoing target language (automatically disabled/greyed out when outgoing translation is off).
5. Click “Save and apply” at the bottom to commit your changes.

“Outgoing translation” is disabled by default. Enable it separately to translate messages you send
into the server's language, and choose the outgoing target from the in-place list. Commands are never
translated. If translation fails or exceeds that Minecraft version's chat length limit, the original
message is sent and a local warning is shown.

`F8` is the master translation toggle. Press it once to enable or disable translation without opening
the settings page. Both `U` and `F8` can be reassigned under “Options → Controls → Key Binds →
MC Auto Translation Tool.” About three seconds after joining a server, the chat area also displays
the key reminder once. The client inserts this reminder directly into the local chat interface; it
is not sent to the server as a player message.

## Offline translation (recommended)

Offline mode needs no API key and requires no service from the project or Minecraft server. New
installations use the following defaults:

```properties
provider=offline
display-mode=translated-only
translate-english-only=true
translated-text-color=original
offline-auto-download=true
offline-model=lite
api-fallback=false
animated-ui=true
```

When translatable text first appears, the mod downloads the llama.cpp engine for the current
platform (about 10–17 MB) and the Lite model (491,400,032 bytes) in the background. The game keeps
showing the original English text during the download and does not block rendering. If the network
disconnects, the `.part` file remains and the next request resumes it. A complete file is used only
after its size and SHA-256 both match. Model downloads prefer the official Qwen repository on
ModelScope for mainland China and fall back to the official Hugging Face source. Engine downloads
use an acceleration source and fall back to the official GitHub release asset.

The default `lite` model is suitable for initial testing. If the computer has at least about 4 GB
of available memory and you want better translation quality, press `U`, change “Offline model” to
`Quality`, and save. The translation service reinitializes immediately and downloads that model on
demand the next time translation is needed. You can also close the game and edit the setting:

```properties
offline-model=quality
```

The Quality model is 1,117,320,736 bytes and is downloaded automatically when needed. Both are
multilingual models, so common languages other than English do not require a separate JAR for each
language. The models and engine are stored in:

```text
config/universal-translator-offline/
```

The mod starts a local child process that listens only on `127.0.0.1`. It stops the process when the
game exits or the configuration is reapplied. Its runtime log is
`config/universal-translator-offline/llama-server.log`.

“Translation diagnostics” on the settings screen shows the current service, target language,
offline model, model-file integrity, automatic-download setting, disk cache, and latest runtime
state. The diagnostics page never displays API endpoints, keys, or server text, and it updates
download and startup status while open. “Export log” writes a sanitized text report to
`config/universal-translator-diagnostics/`; chat text, translations, endpoints, and keys are
deliberately excluded, so that report can be attached to an issue.

To try an existing LibreTranslate API when offline installation or startup fails, enable “API
fallback.” Text may then be sent to the API; this option is disabled by default. Configure the
fallback provider with:

```properties
api-fallback=true
api-fallback-provider=libretranslate
```

### Tencent Hunyuan translation (legacy compatibility interface)

1. Complete identity verification in Tencent Cloud and activate Tencent Hunyuan.
2. Create a sub-account API key that can call only Hunyuan translation. Do not use a root-account key long term.
3. Keep the game closed and open `config/universal-translator.properties` from the game instance in a plain-text editor.
4. Change the following three lines. Enter your own values after the equals signs and do not share the configuration file or keys:

```properties
provider=tencent-hunyuan
tencent-secret-id=YOUR_SECRET_ID
tencent-secret-key=YOUR_SECRET_KEY
```

The default model is `hunyuan-translation-lite`. To use the full model, also set:

```properties
tencent-model=hunyuan-translation
```

Save the file, start the game, press `U`, confirm that the translation service is Tencent Hunyuan,
and enable automatic translation. Check current quota, billing status, and spending limits in the
Tencent Cloud console.

This option remains only for compatibility with 0.1.x configurations. The legacy Hunyuan platform
has announced shutdown on 2026-09-30. Creating long-lived keys for new installations is not
recommended; prefer Offline or LibreTranslate mode.

### LibreTranslate

The default address, `http://127.0.0.1:5000/translate`, refers only to a local service on the user's
computer. The project does not start a service on that port. If no translation service is
available, the game keeps showing the original text and remains responsive.

Remote services must use `https://`. If a service needs a key, close the game and set `api-key=`
locally in the game instance's `config/universal-translator.properties`. Do not share this file or
expose its key in chat, issues, or screenshots.

### Local or online LLM API

Select DeepSeek, Qwen, Volcengine Ark, Zhipu, or “LLM API” in the control panel, then open
“LLM API settings…” to enter that provider's endpoint, model, and API key in-game. The five
providers keep independent settings, so switching providers does not overwrite another provider's
credentials. “LLM API” accepts the OpenAI `/v1/chat/completions` format and can connect to an online
service or a user-run local compatible service such as llama.cpp or Ollama. Its default endpoint is:

```text
http://127.0.0.1:8080/v1/chat/completions
```

A local service may omit the API key; an online service must use `https://`. The settings screen
does not reveal a saved key: leave the field blank to keep it or enter a single `-` to clear it.
The endpoint, model name, and key are stored only in the current game instance's
`config/universal-translator.properties`. The mod does not install or start third-party LLM services.

The settings screen's “Fetch model list” button queries the configured endpoint's `/models` catalog
with the current address and key (the endpoint may be either `/v1` or `/v1/chat/completions`), then
lists the model names the server actually serves; click one to fill in the model field. The request
runs in the background, so the screen never freezes, and a failure reports its reason.

### Other online services and custom APIs

Version 1.4-beta3 includes adapters for Baidu, Tencent Cloud TMT, Alibaba Cloud MT, Youdao,
Volcengine MT, iFlytek, Huawei Cloud, DeepSeek, Qwen, Volcengine Ark, and Zhipu. Custom HTTPS or
loopback HTTP JSON request templates, headers, and response paths are supported as well. See the
[online API configuration guide](ONLINE_APIS.md) for provider IDs, properties, examples, and safety limits.

## Translation quality mode and custom system prompt

### Translation quality mode

`translation-quality` controls how long and how strict the system prompt sent to an LLM is. It
**changes the prompt only**: the model, the `temperature`, the output ceiling, the reasoning-budget
escalation and the batching protocol are untouched.

```properties
translation-quality=standard
```

| Value | Meaning |
| --- | --- |
| `fast` | Shortest prompt, fewest instructions, lowest latency. |
| `standard` | The default; character-for-character identical to the prompt used before this setting existed. |
| `high` | Adds strictness to the standard prompt: preserve tone and register, translate repeated game terms identically, never add, drop or reorder meaning, and keep line alignment. |

The in-game "diagnostics" screen has a quality-mode cycle button that switches and applies the
mode immediately. The setting can also be edited in the configuration file with the game closed.
Each mode uses its own cache key, so switching modes never returns a translation produced under
different instructions. `fast` and `high` only apply to LLM / OpenAI-compatible services;
LibreTranslate, Baidu and the other non-LLM services take no system prompt at all.

### Custom system prompt

A non-empty `custom-system-prompt` **replaces** the built-in prompt entirely, for every quality
mode:

```properties
custom-system-prompt=You are a Minecraft localization editor. Translate to {target}.
```

Supported placeholders:

| Placeholder | Expands to |
| --- | --- |
| `{target}` | Target language, e.g. `繁體中文 (zh-TW)` |
| `{source}` | Source language; `auto` expands to `auto-detect` |
| `{kind}` | Text kind, e.g. `CHAT`, `SCOREBOARD_LINE` |
| `{mode}` | Active quality mode, e.g. `high` |

Any other `{name}` is left untouched. A multi-line prompt is written with `\n` in
`.properties`, which `Properties.load` restores to real line breaks; the in-game diagnostics
field accepts `\n` as well.

**Guardrail and warning**: whatever the custom text says, a fixed requirement is appended to it —
"reply with only the translation" and "one output line per input line, in the same order, without
merging or omitting". A custom prompt therefore cannot switch off the restoration of player names,
URLs, numbers and `§` formatting codes, and cannot switch off line alignment. If your prompt asks
for something like "explain first, then translate" or "answer in JSON", the appended requirement
still applies and the translation will usually be worse; write it on the assumption that only the
translation and one line per input line may come back.

## Translation statistics

The mod accumulates translation counters across sessions and stores them in:

```text
config/universal-translator-stats.properties
```

| Counter | Meaning |
| --- | --- |
| requests / success / failure | Provider requests actually issued, and how many succeeded |
| failure reasons | auth, rate limit, server, request, timeout, network, invalid output, other |
| cache | hits, misses and the resulting hit rate |
| latency | average and p95 over the most recent 512 requests |
| tokens | prompt and completion tokens the service reported (non-streamed responses only) |
| per provider | requests, successes, failures and average latency for each provider |

The file is read when the game starts, written every 60 seconds while it runs, and written once
more when the game exits. A failed write — a read-only configuration directory, for instance — is
ignored and never affects a translation, and the translation path performs no extra disk I/O.

On Fabric 1.20.1 and 1.21 the "diagnostics" screen switches between "Show statistics" and
"Show diagnostics", and "Reset statistics" clears the totals. Other platforms still record and
persist the counters, and they are appended to the file produced by "Export diagnostics"
(`config/universal-translator-diagnostics/`).

## What can be translated

The mod works at the final text-rendering layer. Even before a world is joined it covers mod settings,
quest books, recipe/item interfaces, and custom modpack title screens that use Minecraft's font
renderer. In a world it also covers chat, scoreboards, the Tab list, Action Bar, titles, Boss Bar,
menu/container titles, item names and Lore, tooltips, books, signs, holographic text, and entity
names. Player names, the current server IP/domain and port,
URLs, numbers, percentages, time values, and Minecraft `§` formatting codes are separated locally.
Player names are protected by default; enabling “Player names” allows them to be translated, while
addresses, numbers, and formatting values remain protected from the translation model or API.

“Blocked keywords” accepts comma-, semicolon-, or line-separated entries and matches without regard
to case. If the source contains any entry, the entire text stays unchanged and is never sent to the
translation service. For example, `hello,advertisement` skips chat, UI text, and outgoing translation
that contains either keyword.

For mixed text such as “Welcome 欢迎,” only `Welcome` is sent to the translation service by
default. The existing Chinese text is reassembled locally without changes. This behavior can be
disabled in the settings. Translations are aqua by default; green, gold, light purple, yellow,
white, and original-color options are also available.

Text cannot be captured automatically when a server or modpack embeds it in an image or a third-party mod
bypasses the vanilla font renderer. Complex multicolored Text components may currently preserve
only the first style segment in the translation, but original click and hover data is never written
back or sent to the server.

## Cache and privacy

- Source text is not written to cache files in plaintext; cache keys are SHA-256 hashes.
- Player names do not enter model requests by default; they participate only after “Player names” is enabled.
- Text matching a blocked keyword never enters a model request.
- Server addresses, numbers, and existing Chinese do not enter model requests and are reassembled locally.
- Translations are stored locally to reduce repeated requests and scoreboard refresh latency.
- The disk cache can be disabled in settings; a runtime-only memory cache still exists until restart.
- Disabling “Chat content” prevents chat HUD text from reaching the service; disabling “Other interfaces” blocks server interfaces, mod menus, and modpack content.
- “Outgoing translation” independently controls normal chat sent by the player; it is disabled by default and commands are never translated.
- No new translation request is created after the master switch is turned off.

## Troubleshooting

**Text is never translated**

Confirm that the master switch is enabled. First-time offline use downloads about 502 MB. Press `U`
again to inspect the status and check
`config/universal-translator-offline/llama-server.log`. A `.part` file means the download is not
complete; stay online and join the server again to resume it. In API mode, also confirm that the
endpoint is reachable and that remote addresses use HTTPS. A new sentence first appears in its
original form and is replaced on later frames after background processing finishes.

**The server is lagging. Does the project need access to it for testing?**

No. The mod runs entirely on the client, and development and most tests do not depend on a server.
Final compatibility testing can use a normal test server. A laggy server is useful only for a later,
dedicated high-latency scenario test.

**Does outgoing translation affect chat signatures or require server-side installation?**

The server does not need the mod. When outgoing translation is disabled, outgoing messages are not
modified. When enabled, normal chat is translated locally in the background and sent through the
vanilla chat path so the game signs it normally. Commands remain unchanged. Servers with additional
chat verification may impose their own restrictions, which is why the feature is disabled by default.

## Current version

This guide covers the `1.4-beta3` release. Forty-eight release build targets are packaged into 31
installable JARs. Every target must pass clean builds, shared-core tests, and release-structure checks;
the Fabric bundles also run real Loader selection checks. Back up the configuration before updating.
When reporting untranslated
interface text, include the Minecraft version, loader version, interface location, and `latest.log`,
but omit API keys and private chat content.
