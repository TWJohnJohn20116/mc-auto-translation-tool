# Fabric 1.21 shared sources

Fabric 1.21 platform projects keep only their version-specific `fabric.mod.json` and dependency
coordinates. Java and common resources are assembled by `../shared.gradle` from these layers:

- `common`: classes and resources shared by every supported 1.21 version.
- `runtime`: client bootstrap and translation runtime code split at the 1.21.9 API change.
- `draw`: draw-context mixins split at the 1.21.6 rendering API change.
- `text-renderer`: text-renderer mixins for the four compatible API ranges.

When a Minecraft update changes one API surface, add or adjust only its layer and register the
version-to-layer selection in `../shared.gradle`.
