# Forge 1.21 shared sources

Every exact Forge 1.21 project keeps its own `build.gradle` and `META-INF/mods.toml`, because Forge,
Minecraft, mappings, and accepted version ranges remain exact-version concerns.

Java and stable resources are composed from:

- `common/`: loader integration, configuration, render context, stable Mixins, translations, pack
  metadata, and the Mixin manifest shared by every Forge 1.21 target.
- `1.21-1.21.5/`, `1.21.6-1.21.8/`, `1.21.9-1.21.10/`, and `1.21.11/`: the four source families
  that follow actual Minecraft/Forge API boundaries.
- `mod/`: the bootstrap split at 1.21.9, where direct game-event registration became necessary.

`../shared.gradle` maps each exact project to one family. When an API change affects only part of a
family, split that family at the new boundary instead of copying the complete implementation back
into every version directory.
