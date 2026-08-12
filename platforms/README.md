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
│  └─ 26.x/                # 26.1, 26.1.2, and 26.2
└─ forge/
   ├─ modern/              # 1.20.1 and 1.21.11
   └─ shared/              # Source shared by legacy Forge builds
```

The Gradle project names remain unchanged. Existing commands such as
`./gradlew :platform-fabric-1.21.x:build` continue to work; `settings.gradle` maps those stable names to the organized directories above.
