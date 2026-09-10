#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import time
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen

BMCL = "https://bmclapi2.bangbang93.com"
MIRROR = Path(r"D:\Gradle\cache\mc-mirror")
LOOM = Path(r"D:\Gradle\cache\caches\fabric-loom")


def log(message: str) -> None:
    print(message, flush=True)


def fetch(url: str, attempts: int = 5) -> bytes:
    last: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            log(f"GET {url} ({attempt}/{attempts})")
            req = Request(url, headers={"User-Agent": "mc-auto-translation-tool"})
            with urlopen(req, timeout=180) as response:
                return response.read()
        except Exception as error:  # noqa: BLE001
            last = error
            log(f"retry {url}: {error}")
            time.sleep(2 * attempt)
    raise RuntimeError(f"failed {url}: {last}") from last


def put(path: Path, data: bytes, sha1: str) -> None:
    digest = hashlib.sha1(data).hexdigest()
    if digest != sha1:
        raise RuntimeError(f"sha1 mismatch {path}: {digest} != {sha1}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    log(f"wrote {path} {len(data)}")


def seed(version: str) -> None:
    meta = json.loads((MIRROR / f"{version}.json").read_text(encoding="utf-8"))
    base = LOOM / version
    for kind in ("client", "server"):
        sha1 = meta["downloads"][kind]["sha1"]
        dest = base / f"minecraft-{kind}.jar"
        if dest.is_file() and hashlib.sha1(dest.read_bytes()).hexdigest() == sha1:
            log(f"cached {dest}")
            continue
        put(dest, fetch(f"{BMCL}/v1/objects/{sha1}/{kind}.jar"), sha1)
    for name in ("minecraft-details.json", "ornithe-gen1_minecraft_info.json"):
        path = base / name
        if path.exists():
            text = path.read_text(encoding="utf-8")
            text = text.replace("https://launcher.mojang.com", BMCL)
            text = text.replace("https://launchermeta.mojang.com", BMCL)
            path.write_text(text, encoding="utf-8")
            log(f"patched {path}")


if __name__ == "__main__":
    for version in (
        "1.13",
        "1.13.1",
        "1.13.2",
        "1.14.3",
        "1.14.4",
        "1.15",
        "1.15.1",
        "1.15.2",
    ):
        seed(version)
    log("done")
