#!/usr/bin/env python3
"""補齊 Forge Mavenizer 快取裡缺失的 Mojang POM。

原本把整個 ``D:\\Gradle\\cache\\...\\maven\\mojang`` 硬編成 ``rglob`` 的起點：目錄不存在時
``rglob`` 只是產生空結果，腳本照樣印出「wrote 0 poms」並以 0 結束，於是「POM 已補齊」是
假的。這裡改成缺目錄或一個 POM 都沒補到就以非零碼結束。
"""

from __future__ import annotations

import sys

from _cache_paths import forge_mojang_maven


def main() -> int:
    root = forge_mojang_maven()
    if not root.is_dir():
        print(
            f"ERROR: 找不到 Mojang Maven 目錄 {root}；"
            "請先設定 GRADLE_USER_HOME，或先跑一次 ForgeGradle 讓它把相依下載下來。",
            file=sys.stderr,
        )
        return 1

    written = 0
    for jar in sorted(root.rglob("*.jar")):
        parts = jar.relative_to(root).parts
        if len(parts) < 3:
            # 目錄結構不是 group/artifact/version/file，算不出座標，跳過而不是爆掉。
            print(f"SKIP: {jar}（路徑層級不足，推不出 groupId/artifactId/version）", file=sys.stderr)
            continue
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

    print(f"wrote {written} poms under {root}")
    if written == 0:
        print(
            "ERROR: 一個 POM 都沒補到——快取可能不完整（沒有 *.jar），"
            "或 POM 本來就齊全。請確認上面列出的目錄內容。",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
