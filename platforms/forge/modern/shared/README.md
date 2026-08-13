# Forge legacy shared sources

Forge 1.16.5, 1.19.2, and 1.20.1 use
`platforms/forge/modern/shared.gradle` for common ForgeGradle, Renamer, release,
and metadata configuration.

- `common`: stable configuration, runtime, render context, metadata, and translations.
- `family/1.19.2-plus`: client bootstrap and rendered-text APIs shared by 1.19.2 and 1.20.1.

Version directories retain the Mixin and client adapters whose Mojmap APIs differ.
