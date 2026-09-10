#!/usr/bin/env python3
"""Seed Forge Mavenizer caches so 1.18.2 can build when Mojang meta is blocked.

Mavenizer listLibraries only accepts library URLs under libraries.minecraft.net,
then downloads into caches/maven/mojang. Keep those URLs in version.json and
pre-fill the cache (and .minecraft/libraries) from BMCLAPI instead.
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.request import Request, urlopen

BMCL = "https://bmclapi2.bangbang93.com"
META_REPLACEMENTS = (
    ("https://piston-meta.mojang.com", BMCL),
    ("https://piston-data.mojang.com", BMCL),
    ("https://launchermeta.mojang.com", BMCL),
    ("https://launcher.mojang.com", BMCL),
)
CACHE_ROOT = Path(r"D:\Gradle\cache\caches\minecraftforge\forgegradle\mavenizer\caches")
MOJANG_MAVEN = CACHE_ROOT / "maven" / "mojang"
SERVE_DIR = Path(r"D:\Gradle\cache\mc-mirror")
MC_LIBS = Path(os.environ.get("APPDATA", "")) / ".minecraft" / "libraries"


def log(message: str) -> None:
    print(message, flush=True)


def fetch(url: str) -> bytes:
    req = Request(url, headers={"User-Agent": "mc-auto-translation-tool"})
    with urlopen(req, timeout=60) as response:
        return response.read()


def rewrite_meta(text: str) -> str:
    for src, dst in META_REPLACEMENTS:
        text = text.replace(src, dst)
    return text


def library_downloads(version: dict) -> list[dict]:
    artifacts: list[dict] = []
    seen: set[str] = set()
    for library in version.get("libraries", []):
        downloads = library.get("downloads") or {}
        candidates = []
        artifact = downloads.get("artifact")
        if isinstance(artifact, dict):
            candidates.append(artifact)
        classifiers = downloads.get("classifiers") or {}
        if isinstance(classifiers, dict):
            candidates.extend(
                item for item in classifiers.values() if isinstance(item, dict)
            )
        for item in candidates:
            path = item.get("path")
            if not isinstance(path, str) or path in seen:
                continue
            seen.add(path)
            artifacts.append(item)
    return artifacts


def bmcl_maven_url(path: str) -> str:
    return f"{BMCL}/maven/{path}"


def seed_file(item: dict) -> str:
    path = item["path"]
    sha1 = item.get("sha1")
    targets = [MOJANG_MAVEN / path]
    if MC_LIBS.drive or MC_LIBS.parts:
        targets.append(MC_LIBS / path)
    data = None
    for target in targets:
        if target.is_file() and (not sha1 or hashlib.sha1(target.read_bytes()).hexdigest() == sha1):
            continue
        if data is None:
            data = fetch(bmcl_maven_url(path))
            digest = hashlib.sha1(data).hexdigest()
            if sha1 and digest != sha1:
                raise RuntimeError(f"sha1 mismatch for {path}: {digest} != {sha1}")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        (target.parent / f"{target.name}.sha1").write_text(sha1 or hashlib.sha1(data).hexdigest(), encoding="utf-8")
    return path


def main() -> None:
    CACHE_ROOT.mkdir(parents=True, exist_ok=True)
    MOJANG_MAVEN.mkdir(parents=True, exist_ok=True)
    SERVE_DIR.mkdir(parents=True, exist_ok=True)

    version_json = rewrite_meta(fetch(f"{BMCL}/version/1.18.2/json").decode("utf-8"))
    if "https://bmclapi2.bangbang93.com/maven/" in version_json:
        raise RuntimeError("version json still rewrites libraries.minecraft.net")
    if "https://libraries.minecraft.net/" not in version_json:
        raise RuntimeError("version json lost Mojang library URLs")
    version_path = SERVE_DIR / "1.18.2.json"
    version_path.write_text(version_json, encoding="utf-8")
    digest = hashlib.sha1(version_path.read_bytes()).hexdigest()
    parsed = json.loads(version_json)
    artifacts = library_downloads(parsed)
    log(f"client url {parsed['downloads']['client']['url']}")
    log(f"seeding {len(artifacts)} Mojang libraries")

    failed: list[str] = []
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = {pool.submit(seed_file, item): item["path"] for item in artifacts}
        for future in as_completed(futures):
            path = futures[future]
            try:
                future.result()
                log(f"ok {path}")
            except Exception as error:
                failed.append(f"{path}: {error}")
                log(f"FAIL {path}: {error}")
    if failed:
        raise RuntimeError("library seed failed:\n" + "\n".join(failed))

    manifest = {
        "latest": {"release": "1.18.2", "snapshot": "1.18.2"},
        "versions": [
            {
                "id": "1.18.2",
                "type": "release",
                "url": "http://127.0.0.1:8765/1.18.2.json",
                "time": "2022-02-28T10:42:45+00:00",
                "releaseTime": "2022-02-28T10:42:45+00:00",
                "sha1": digest,
            }
        ],
    }
    manifest_path = CACHE_ROOT / "launcher_manifest.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    log(f"seeded {manifest_path}")
    log(f"serving rewritten 1.18.2.json sha1={digest}")

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(SERVE_DIR), **kwargs)

        def log_message(self, format, *args):
            log("[mirror] " + (args[0] if args else format))

    httpd = ThreadingHTTPServer(("127.0.0.1", 8765), Handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    log("READY")
    thread.join()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        log(f"ERROR: {error}")
        sys.exit(1)
