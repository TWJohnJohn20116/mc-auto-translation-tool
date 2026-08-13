# Fabric legacy shared sources

Fabric 1.16.5, 1.19.2, and 1.20.1 compose stable code and resources through
`platforms/fabric/legacy/shared.gradle`.

- `common`: configuration, runtime, render context, metadata, and translations.
- `family/1.16.5-1.19.2`: widgets and Mixins whose Yarn APIs are identical.
- `rendered/*`: the two `RenderedTextBridge` API families.

Version directories retain UI, bootstrap, and Mixin adapters that genuinely use
different Minecraft APIs.
