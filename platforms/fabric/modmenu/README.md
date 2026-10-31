# Mod Menu compile-only stubs

Mod Menu is an optional integration: the settings screen is also reachable from the keybind, so the
mod ships without a dependency on it and declares the two interfaces it calls
(`ModMenuApi`, `ConfigScreenFactory`) as compile-only stubs under `api/<family>/`. `modmenu.gradle`
excludes `com/terraformersmc/modmenu/**` from `jar`/`remapJar`/`sourcesJar`, so at run time the class
that exists is **Mod Menu's own** — the stub only exists to compile against.

That means the stub's declarations are a promise about a library this build never resolves. A wrong
method name does not fail the build; it fails at link time with `AbstractMethodError` /
`IncompatibleClassChangeError` the first time a player presses the config button in Mod Menu's mod
list.

## What is pinned, and where

| Thing | Pinned in | Value |
| --- | --- | --- |
| `ConfigScreenFactory`'s abstract method | `modmenu.gradle` → `modmenuFactoryStubSignature` | `createScreen(Screen parent);` |
| The call site's override | `modmenu.gradle` → `modmenuFactoryOverride` | `public Screen createScreen(Screen parent)` |
| `ModMenuApi`'s entrypoint method | `modmenu.gradle` → `modmenuApiStubSignature` | `ConfigScreenFactory<?> getModConfigScreenFactory();` |
| The call site's entrypoint override | `modmenu.gradle` → `modmenuApiOverride` | `public ConfigScreenFactory<?> getModConfigScreenFactory()` |

`verifyModMenuContract` (part of `check`) compares **both** stub files and **both** integration classes
against those four constants. The stub itself is not the source of truth: a check that only greps the
stub passes whenever the stub and the call site are renamed to the same wrong name, which is the one
direction `@Override` cannot catch. Keeping the record in `modmenu.gradle` means such a rename has to
edit the guard too. Both contract names need this treatment — pinning only `createScreen` left
`getModConfigScreenFactory()` free to be renamed everywhere at once, and Mod Menu's mod list is the only
thing that would have noticed.

## Checklist when Mod Menu is upgraded (or a new Minecraft line is added)

1. Read `com.terraformersmc.modmenu.api.ConfigScreenFactory` and `ModMenuApi` from the real artifact
   (`https://maven.terraformersmc.com/releases/com/terraformersmc/modmenu/`), not from the stub.
2. Confirm the factory still declares `S createScreen(Screen parent)` and `ModMenuApi` still declares
   `ConfigScreenFactory<?> getModConfigScreenFactory()`. Update `api/<family>/` and the four constants
   above together, in one commit.
3. Confirm the package names are unchanged (`com.terraformersmc.modmenu.api`), since that is what the
   `jar` exclusion and `fabric.mod.json`'s `modmenu` entrypoint rely on.
4. Confirm `net.minecraft.client.gui.screen.Screen` (Yarn families) and
   `net.minecraft.client.gui.screens.Screen` (Mojmap families) are still the `Screen` the entrypoint
   hands over.
5. Nothing else in the repository compares the stub against the library. If a `modCompileOnly`
   `com.terraformersmc:modmenu` dependency is ever added, delete this file's step 1-4 and let the
   compiler bind to the real interface instead.
