# Platform modules

Platform implementations are grouped by loader and Minecraft generation so the repository root stays readable.

```text
platforms/
├─ fabric/
│  ├─ legacy/              # 1.16.5, 1.19.2, and 1.20.1
│  ├─ 1.21/
│  │  ├─ versions/         # Exact 1.21 through 1.21.11 implementations
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
   └─ 1.21/versions/1.21.1/
```

The Gradle project names remain unchanged. A fully qualified task path includes its platform
automatically. For IDE import or multiple targets, pass `-PtargetPlatform=<short-name>`; comma-separated
values are supported, and `all` registers every platform. `settings.gradle` expands bundle dependencies
without configuring unrelated ForgeGradle or Loom workspaces.
