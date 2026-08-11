# MC Auto Translation Tool installation and usage guide

[简体中文](../Zh-cn/USER_GUIDE.md) · [繁體中文](../Zh-tw/USER_GUIDE.md) · [English](USER_GUIDE.md) · [Back to English README](README.md)

## Choose the correct file

Each Minecraft version requires its matching JAR. Do not mix them:

| Minecraft | Mod loader | Java | Also required |
| --- | --- | --- | --- |
| 26.2 (no released JAR yet) | Fabric | 25 | Fabric API |
| 26.1 (no released JAR yet) | Fabric | 25 | Fabric API |
| 1.21.4 (no released JAR yet) | Fabric | 21 | Fabric API |
| 1.21.11 | Fabric | 21 | Fabric API |
| 1.12.2 | Forge 14.23.5.x | 8 | No additional mod |
| 1.8.9 | Forge 11.15.1.x | 8 | No additional mod |

Place the correct JAR in the game instance's `mods` folder. The server does not need this mod.

## First-time setup

1. Enter any world or server and press `U` to open the settings.
2. Enter the target language, such as `zh-CN` for Simplified Chinese or `zh-TW` for Traditional
   Chinese. The shortcut button cycles through Simplified Chinese, Traditional Chinese, and English.
3. Prefer “Offline.” You may also continue using “Libre,” “Tencent,” or “LLM API.”
4. Choose whether chat content and other interface text may be sent, and whether to use the local cache.
5. The default display option is “Translated only.” Replacing the source directly prevents bilingual text from overflowing scoreboards and chest interfaces; switch to “Original + translation” when needed.
6. “Translate only English in mixed text” is enabled by default, so existing Chinese is not translated again.
7. Choose a translation color. Select “Keep original color” to disable color differentiation.
8. Read the privacy notice in the interface, enable automatic translation, and save.

“Outgoing translation” is disabled by default. Enable it separately to translate messages you send
into the server's language, and enter an outgoing target such as `en` or `ja`. Commands are never
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
translated-text-color=aqua
offline-auto-download=true
offline-model=lite
api-fallback=false
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
download and startup status while open.

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

Select “LLM API” in the control panel, then open “LLM API settings…”. This mode accepts the OpenAI
`/v1/chat/completions` format and can connect to an online service or a user-run local compatible
service such as llama.cpp or Ollama. The default endpoint is:

```text
http://127.0.0.1:8080/v1/chat/completions
```

A local service may omit the API key; an online service must use `https://`. The settings screen
does not reveal a saved key: leave the field blank to keep it or enter a single `-` to clear it.
The endpoint, model name, and key are stored only in the current game instance's
`config/universal-translator.properties`. The mod does not install or start third-party LLM services.

## What can be translated

The mod works at the final text-rendering layer, so it normally covers chat, scoreboards, the Tab
list, Action Bar, titles, Boss Bar, menu/container titles, item names and Lore, tooltips, books,
signs, holographic text, and entity names. Player names, the current server IP/domain and port,
URLs, numbers, percentages, time values, and Minecraft `§` formatting codes are separated locally
and preserved exactly. They are not sent to the translation model or API.

For mixed text such as “Welcome 欢迎,” only `Welcome` is sent to the translation service by
default. The existing Chinese text is reassembled locally without changes. This behavior can be
disabled in the settings. Translations are aqua by default; green, gold, light purple, yellow,
white, and original-color options are also available.

Text cannot be captured automatically when a server embeds it in an image or a third-party mod
bypasses the vanilla font renderer. Complex multicolored Text components may currently preserve
only the first style segment in the translation, but original click and hover data is never written
back or sent to the server.

## Cache and privacy

- Source text is not written to cache files in plaintext; cache keys are SHA-256 hashes.
- Player names, server addresses, numbers, and existing Chinese do not enter model requests and are reassembled locally.
- Translations are stored locally to reduce repeated requests and scoreboard refresh latency.
- The disk cache can be disabled in settings; a runtime-only memory cache still exists until restart.
- Disabling “Chat content” prevents chat HUD text from reaching the service; disabling “Other interfaces” blocks the remaining content.
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

This guide covers the `1.1` release. All three target versions completed clean builds, remapping,
and the shared-core self-test. Hands-on launch validation completed before 1.0 remains the
compatibility baseline. Fabric 26.1 passed build and main-menu launch checks. Fabric 26.2 has source
adaptation and build checks complete, with an actual launch still pending. Neither has a release JAR
or completed in-server regression. Back up the configuration before updating. When reporting untranslated
interface text, include the Minecraft version, loader version, interface location, and `latest.log`,
but omit API keys and private chat content.
