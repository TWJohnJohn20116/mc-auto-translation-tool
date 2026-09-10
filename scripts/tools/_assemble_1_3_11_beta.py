#!/usr/bin/env python3
"""Collect rebuilt 1.3.11-beta.1 JARs into downloads/ and write SHA256SUMS."""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path

ROOT = Path(r"D:\Code\mc-auto-translation-tool")


def get_version() -> str:
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("mod_version="):
            return line.split("=", 1)[1].strip()
    raise ValueError("mod_version not found in gradle.properties")


VERSION = get_version()
DST = ROOT / "downloads" / VERSION
PART = 614400

# download name -> candidate source paths (first existing file wins)
CANDIDATES: dict[str, tuple[str, ...]] = {
    f"MCAutoTranslationTool-{VERSION}-mc1.8.9-forge.jar": (
        f"platforms/forge/legacy/1.8.9/build/libs/mc-auto-translation-tool-forge-1.8.9-{VERSION}.jar",
    ),
    f"MCAutoTranslationTool-{VERSION}-mc1.12.2-forge.jar": (
        f"platforms/forge/legacy/1.12.2/build/libs/mc-auto-translation-tool-forge-1.12.2-{VERSION}.jar",
    ),
    f"MCAutoTranslationTool-{VERSION}-mc1.13.x-fabric.jar": (
        f"platforms/fabric/1.13/bundle/build/libs/mc-auto-translation-tool-fabric-1.13.x-{VERSION}.jar",
    ),
    f"MCAutoTranslationTool-{VERSION}-mc1.14-1.15.x-fabric.jar": (
        f"platforms/fabric/1.14-1.15/bundle/build/libs/mc-auto-translation-tool-fabric-1.14-1.15.x-{VERSION}.jar",
    ),
    f"MCAutoTranslationTool-{VERSION}-mc1.16.5-fabric.jar": (
        f"platforms/fabric/1.16/versions/1.16.5/build/libs/mc-auto-translation-tool-fabric-1.16.5-{VERSION}.jar",
    ),
    f"MCAutoTranslationTool-{VERSION}-mc1.16.5-forge.jar": (
        f"platforms/forge/modern/1.16.5/build/release/mc-auto-translation-tool-forge-1.16.5-{VERSION}.jar",
    ),
    f"MCAutoTranslationTool-{VERSION}-mc1.17-1.18.x-fabric.jar": (
        f"platforms/fabric/1.17-1.18/bundle/build/libs/mc-auto-translation-tool-fabric-1.17-1.18.x-{VERSION}.jar",
    ),
    f"MCAutoTranslationTool-{VERSION}-mc1.18.2-forge.jar": (
        f"platforms/forge/modern/1.18.2/build/release/mc-auto-translation-tool-forge-1.18.2-{VERSION}.jar",
    ),
    f"MCAutoTranslationTool-{VERSION}-mc1.19.x-fabric.jar": (
        f"platforms/fabric/1.19/bundle/build/libs/mc-auto-translation-tool-fabric-1.19.x-{VERSION}.jar",
    ),
    f"MCAutoTranslationTool-{VERSION}-mc1.19.2-forge.jar": (
        f"platforms/forge/modern/1.19.2/build/release/mc-auto-translation-tool-forge-1.19.2-{VERSION}.jar",
    ),
    f"MCAutoTranslationTool-{VERSION}-mc1.20.x-fabric.jar": (
        f"platforms/fabric/1.20/bundle/build/libs/mc-auto-translation-tool-fabric-1.20.x-{VERSION}.jar",
    ),
    f"MCAutoTranslationTool-{VERSION}-mc1.20.1-forge.jar": (
        f"platforms/forge/modern/1.20.1/build/release/mc-auto-translation-tool-forge-1.20.1-{VERSION}.jar",
    ),
    f"MCAutoTranslationTool-{VERSION}-mc1.20.1-neoforge.jar": (
        f"platforms/neoforge/1.20.1/build/libs/mc-auto-translation-tool-neoforge-1.20.1-{VERSION}.jar",
    ),
    f"MCAutoTranslationTool-{VERSION}-mc1.21.x-fabric.jar": (
        f"platforms/fabric/1.21/bundle/build/libs/mc-auto-translation-tool-fabric-1.21.x-{VERSION}.jar",
    ),
    f"MCAutoTranslationTool-{VERSION}-mc26.x-fabric.jar": (
        f"platforms/fabric/26.x/bundle/build/libs/mc-auto-translation-tool-fabric-26.x-{VERSION}.jar",
    ),
}


def add_exact_forge(mc: str) -> None:
    CANDIDATES[f"MCAutoTranslationTool-{VERSION}-mc{mc}-forge.jar"] = (
        f"platforms/forge/1.21/versions/{mc}/build/libs/mc-auto-translation-tool-forge-{mc}-{VERSION}.jar",
        f"platforms/forge/26.x/versions/{mc}/build/libs/mc-auto-translation-tool-forge-{mc}-{VERSION}.jar",
        f"platforms/forge/modern/{mc}/build/release/mc-auto-translation-tool-forge-{mc}-{VERSION}.jar",
    )


def add_neoforge(mc: str) -> None:
    CANDIDATES[f"MCAutoTranslationTool-{VERSION}-mc{mc}-neoforge.jar"] = (
        f"platforms/neoforge/1.21/versions/{mc}/build/libs/mc-auto-translation-tool-neoforge-{mc}-{VERSION}.jar",
        f"platforms/neoforge/1.20.1/build/libs/mc-auto-translation-tool-neoforge-{mc}-{VERSION}.jar",
        f"platforms/neoforge/26.x/versions/{mc}/build/libs/mc-auto-translation-tool-neoforge-{mc}-{VERSION}.jar",
    )


for mc in (
    "1.21", "1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.6",
    "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
    "26.1", "26.1.1", "26.1.2", "26.2",
):
    add_exact_forge(mc)

for mc in ("1.21.1", "1.21.3", "1.21.11"):
    add_neoforge(mc)


def find_source(name: str) -> Path:
    for relative in CANDIDATES[name]:
        path = ROOT / relative
        if path.is_file():
            return path
    raise FileNotFoundError(f"missing built JAR for {name}: " + ", ".join(CANDIDATES[name]))


def split_if_needed(path: Path, data: bytes) -> None:
    if path.name.endswith("-fabric.jar") and len(data) > PART:
        for index in range(0, len(data), PART):
            part_index = index // PART
            suffix = chr(ord("a") + part_index // 26) + chr(ord("a") + part_index % 26)
            (path.parent / f"{path.name}.part-{suffix}").write_bytes(data[index : index + PART])
        return
    path.write_bytes(data)


def main() -> int:
    missing = []
    found = {}
    for name in sorted(CANDIDATES):
        try:
            found[name] = find_source(name)
        except FileNotFoundError as error:
            missing.append(str(error))
    if missing:
        print("\n".join(missing), file=sys.stderr)
        return 1

    DST.mkdir(parents=True, exist_ok=True)
    for leftover in DST.iterdir():
        leftover.unlink()

    lines = []
    for name in sorted(found):
        data = found[name].read_bytes()
        split_if_needed(DST / name, data)
        digest = hashlib.sha256(data).hexdigest()
        lines.append(f"{digest}  {name}")
        print(f"{digest[:12]} {name} {len(data)} from {found[name].relative_to(ROOT)}")
    (DST / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {len(found)} logical JARs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
