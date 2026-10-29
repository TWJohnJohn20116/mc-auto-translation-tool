#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import time
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen

from _cache_paths import gradle_user_home, mirror_dir

BMCL = "https://bmclapi2.bangbang93.com"
# 原本硬編 D:\Gradle\cache；改成跟 Gradle 自己一樣的優先序（GRADLE_USER_HOME → ~/.gradle）。
MIRROR = mirror_dir()
LOOM = gradle_user_home() / "caches" / "fabric-loom"


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
        if not path.exists():
            continue
        original = path.read_text(encoding="utf-8")
        if BMCL in original:
            log(f"already patched {path}")
            continue
        patched = original.replace("https://launcher.mojang.com", BMCL)
        patched = patched.replace("https://launchermeta.mojang.com", BMCL)
        # 這是 Gradle 的共用快取，改寫是不可逆的：先留一份 .orig 備份，改完再讀回來比對，
        # 否則一旦 Loom 之後要修復或別的專案要驗證來源，就沒有原檔可以還原。
        backup = path.with_suffix(path.suffix + ".orig")
        if not backup.exists():
            backup.write_text(original, encoding="utf-8")
        path.write_text(patched, encoding="utf-8")
        if hashlib.sha256(path.read_text(encoding="utf-8").encode("utf-8")).hexdigest() != (
            hashlib.sha256(patched.encode("utf-8")).hexdigest()
        ):
            raise RuntimeError(f"patch did not land as written: {path}")
        log(f"patched {path} (original kept at {backup.name})")


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
