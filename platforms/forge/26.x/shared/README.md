# Forge 26.x shared sources

Forge 26.x modules compose their sources from this directory through
`platforms/forge/26.x/shared.gradle`.

- `common`: code and resources shared by every 26.x build.
- `api/26.1-26.1.2`: Minecraft client API calls used through 26.1.2.
- `api/26.2`: Minecraft client API calls introduced by 26.2.

The global `platforms/forge/shared.gradle` script owns common Forge build,
manifest, and metadata processing. Version directories only declare coordinates
and compatibility values.
