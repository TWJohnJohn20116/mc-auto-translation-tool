from pathlib import Path

root = Path(r"D:\Gradle\cache\caches\minecraftforge\forgegradle\mavenizer\caches\maven\mojang")
written = 0
for jar in root.rglob("*.jar"):
    parts = jar.relative_to(root).parts
    version = parts[-2]
    artifact = parts[-3]
    group = ".".join(parts[:-3])
    pom = jar.parent / f"{artifact}-{version}.pom"
    if pom.exists():
        continue
    pom.write_text(
        "\n".join(
            [
                '<?xml version="1.0" encoding="UTF-8"?>',
                '<project xmlns="http://maven.apache.org/POM/4.0.0">',
                "  <modelVersion>4.0.0</modelVersion>",
                f"  <groupId>{group}</groupId>",
                f"  <artifactId>{artifact}</artifactId>",
                f"  <version>{version}</version>",
                "</project>",
                "",
            ]
        ),
        encoding="utf-8",
    )
    written += 1
print(f"wrote {written} poms")
