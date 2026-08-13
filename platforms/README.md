# Platform modules

Platform implementations are grouped by loader and Minecraft generation so the repository root stays readable.

```text
platforms/
├─ fabric/
│  ├─ legacy/              # 1.16.5, 1.19.2, and 1.20.1
│  ├─ 1.21/
│  │  ├─ versions/         # Exact metadata and dependency coordinates
│  │  ├─ shared/           # Common code/resources plus API-specific layers
│  │  ├─ bundle/           # The single downloadable 1.21.x JAR
│  │  └─ shared-1.21.4-1.21.5/
│  └─ 26.x/
│     ├─ 26.1, 26.1.1, 26.1.2, 26.2  # Exact implementations
│     └─ bundle/              # The single downloadable 26.x JAR
├─ forge/
│  ├─ modern/              # 1.16.5, 1.19.2, and 1.20.1
│  ├─ 1.21/
│  │  ├─ versions/         # Exact metadata and build configuration
│  │  └─ shared/           # Common code/resources plus four API families
│  ├─ 26.x/versions/       # Exact 26.x implementations
│  └─ shared/              # Source shared by legacy Forge builds
└─ neoforge/
   ├─ 1.20.1/              # NeoForge 47.1.106; shares the 1.20.1 Forge sources
   └─ 1.21/versions/1.21.1/ # NeoForge 21.1.248
```

The Gradle project names remain unchanged. A fully qualified task path includes its platform
automatically. For IDE import or multiple targets, pass `-PtargetPlatform=<short-name>`; comma-separated
values are supported, and `all` registers every platform. `settings.gradle` expands bundle dependencies
without configuring unrelated ForgeGradle or Loom workspaces.

Fabric 1.21 projects assemble their client sources from `platforms/fabric/1.21/shared`. The runtime,
draw-context, and text-renderer layers isolate the few API boundaries while all other sources and
resources remain common to every supported 1.21 version.
