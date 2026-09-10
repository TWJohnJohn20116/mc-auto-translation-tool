#!/usr/bin/env python3
"""Write BMCLAPI-backed Minecraft version JSON for Fabric Loom."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from urllib.request import Request, urlopen

BMCL = "https://bmclapi2.bangbang93.com"
SERVE_DIR = Path(r"D:\Gradle\cache\mc-mirror")
VERSIONS = [
    "1.13",
    "1.13.1",
    "1.13.2",
    "1.14",
    "1.14.1",
    "1.14.2",
    "1.14.3",
    "1.14.4",
    "1.15",
    "1.15.1",
    "1.15.2",
]
REPLACEMENTS = (
    ("https://piston-meta.mojang.com", BMCL),
    ("https://piston-data.mojang.com", BMCL),
    ("https://launchermeta.mojang.com", BMCL),
    ("https://launcher.mojang.com", BMCL),
)


def log(message: str) -> None:
    print(message, flush=True)


def fetch(url: str) -> str:
    req = Request(url, headers={"User-Agent": "mc-auto-translation-tool"})
    with urlopen(req, timeout=60) as response:
        return response.read().decode("utf-8")


def rewrite(text: str) -> str:
    for src, dst in REPLACEMENTS:
        text = text.replace(src, dst)
    return text


def main() -> None:
    SERVE_DIR.mkdir(parents=True, exist_ok=True)
    entries = []
    for version in VERSIONS:
        text = rewrite(fetch(f"{BMCL}/version/{version}/json"))
        data = text.encode("utf-8")
        path = SERVE_DIR / f"{version}.json"
        path.write_bytes(data)
        digest = hashlib.sha1(data).hexdigest()
        parsed = json.loads(text)
        client = parsed.get("downloads", {}).get("client", {}).get("url")
        log(f"{version} sha1={digest} client={client}")
        entries.append(
            {
                "id": version,
                "type": "release",
                "url": f"http://127.0.0.1:8765/{version}.json",
                "time": parsed.get("time", "2018-01-01T00:00:00+00:00"),
                "releaseTime": parsed.get(
                    "releaseTime", parsed.get("time", "2018-01-01T00:00:00+00:00")
                ),
                "sha1": digest,
            }
        )
    manifest = {
        "latest": {"release": VERSIONS[-1], "snapshot": VERSIONS[-1]},
        "versions": entries,
    }
    manifest_path = SERVE_DIR / "version_manifest_v2.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    log(f"wrote {manifest_path}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        log(f"ERROR: {error}")
        sys.exit(1)
