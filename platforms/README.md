# Platform Modules Overview

Platform implementations are grouped by Mod Loader (Fabric, Forge, NeoForge) and Minecraft generations so the multi-project workspace remains clean and modular.

For complete architectural details, see [ARCHITECTURE.md](../docs/Zh-tw/ARCHITECTURE.md).

```text
platforms/
├─ fabric/
│  ├─ 1.0-1.8/            # Ornithe Fabric (Minecraft 1.0.0 through 1.8.8, 28 versions)
│  │  ├─ versions/
│  │  └─ bundle/          # Aggregates 1.0.0~1.8.8
│  ├─ 1.8-1.12/           # Ornithe Fabric (Minecraft 1.8.9 through 1.12.2, 12 versions)
│  │  ├─ versions/
│  │  └─ bundle/          # Aggregates 1.8.9~1.12.2
│  ├─ 1.13/               # Ornithe / LegacyFabric (1.13.0, 1.13.1, 1.13.2)
│  │  ├─ versions/
│  │  └─ bundle/          # Aggregates 1.13.x
│  ├─ 1.14-1.15/          # Official Fabric Loom (1.14 ~ 1.15.2, 8 versions)
│  │  ├─ versions/
│  │  └─ bundle/          # Aggregates 1.14-1.15.x
│  ├─ 1.16/               # Fabric 1.16.0 ~ 1.16.5 (6 versions)
│  │  ├─ versions/        # 1.16, 1.16.1, 1.16.2, 1.16.3, 1.16.4, 1.16.5
│  │  └─ bundle/          # Aggregates 1.16.x
│  ├─ 1.17-1.18/          # Fabric 1.17.0 ~ 1.18.2 (5 versions)
│  │  ├─ versions/
│  │  └─ bundle/
│  ├─ 1.19/               # Fabric 1.19.0 ~ 1.19.4 (5 versions)
│  │  ├─ versions/        # 1.19, 1.19.1, 1.19.2, 1.19.3, 1.19.4
│  │  └─ bundle/
│  ├─ 1.20/               # Fabric 1.20.0 ~ 1.20.6 (6 versions)
│  │  ├─ versions/        # 1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4, 1.20.5, 1.20.6
│  │  └─ bundle/
│  ├─ 1.21/               # Fabric 1.21.0 ~ 1.21.11 (12 versions)
│  │  ├─ versions/
│  │  ├─ shared/          # Common 1.21 text, rendering, and HUD layers
│  │  └─ bundle/          # Aggregates 1.21.x
│  ├─ 26.x/               # Fabric 26.1, 26.1.1, 26.1.2, 26.2
│  │  ├─ 26.1, 26.1.1, 26.1.2, 26.2
│  │  ├─ shared/
│  │  └─ bundle/          # Aggregates 26.x
│  ├─ legacy/shared/      # Shared rendering, HUD, and network layers for 1.14~1.20.6
│  └─ modmenu/            # Shared ModMenu API compatibility layer
├─ forge/
│  ├─ legacy/             # Forge 1.8.9 & 1.12.2 (ForgeGradle 2.x, Gradle 4.9 wrapper)
│  │  ├─ 1.8.9/
│  │  └─ 1.12.2/
│  ├─ modern/             # Forge 1.16.5, 1.18.2, 1.19.2, 1.20.1 (ForgeGradle 5.1)
│  ├─ 1.21/               # Forge 1.21.0 through 1.21.11
│  │  ├─ versions/
│  │  └─ shared/          # Shared Forge 1.21 event and client logic
│  ├─ 26.x/versions/      # Forge 26.1, 26.1.1, 26.1.2, 26.2
│  └─ shared/             # Shared Forge modern event listeners & Mixins
└─ neoforge/
   ├─ 1.20.1/             # NeoForge 47.1.106 (shares Forge 1.20.1 source layer)
   ├─ 1.20/versions/      # NeoForge 1.20.2, 1.20.4, 1.20.6
   ├─ 1.21/versions/      # NeoForge 1.21.1, 1.21.3, 1.21.11
   └─ 26.x/versions/      # NeoForge 26.1 ~ 26.2
```

## Target Selection

A fully qualified task path selects its platform automatically (e.g. `:platform-fabric-1.21.4:build`).
For selective builds, use `-PtargetPlatform=<short-name>` (supports comma-separated names, or `all`).
`settings.gradle` expands only the requested projects into the workspace, preventing unrelated Gradle tasks from configuring.
