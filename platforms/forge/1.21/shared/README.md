# Forge 1.21 shared sources

Every exact Forge 1.21 project declares only its Minecraft, Forge, loader, and
compatibility coordinates. `platforms/forge/1.21/shared.gradle` composes Java and
stable resources from:

- `common/`: loader integration, configuration, render context, stable Mixins,
  translations, pack metadata, and the Mixin manifest shared by every target.
- `1.21-1.21.5/`, `1.21.6-1.21.8/`, `1.21.9-1.21.10/`, and `1.21.11/`: source
  families that follow Minecraft and Forge API boundaries.
- `mod/`: the bootstrap split at 1.21.9, where direct game-event registration
  became necessary.

Global build, manifest, and `mods.toml` processing is provided by
`platforms/forge/shared.gradle`. When an API changes, split the smallest source
family instead of copying the complete implementation into each version.
