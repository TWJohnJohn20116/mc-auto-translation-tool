#!/usr/bin/env python3
"""Build a smaller set of directly installable release JARs."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import shutil
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path


PART_PATTERN = re.compile(r"^(?P<jar>.+\.jar)\.part-(?P<suffix>[a-z]{2})$")
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


class PreparationError(RuntimeError):
    pass


@dataclass(frozen=True)
class ForgeFamily:
    members: tuple[str, ...]
    output_range: str
    minecraft_range: str


FORGE_FAMILIES = (
    ForgeFamily(
        ("1.21", "1.21.1", "1.21.3", "1.21.4", "1.21.5"),
        "1.21-1.21.5",
        "[1.21,1.21.6)",
    ),
    ForgeFamily(
        ("1.21.6", "1.21.7", "1.21.8"),
        "1.21.6-1.21.8",
        "[1.21.6,1.21.9)",
    ),
    ForgeFamily(
        ("1.21.9", "1.21.10", "1.21.11"),
        "1.21.9-1.21.11",
        "[1.21.9,1.21.12)",
    ),
    ForgeFamily(
        ("26.1", "26.1.1", "26.1.2"),
        "26.1-26.1.2",
        "[26.1,26.2)",
    ),
)


def _part_suffix(index: int) -> str:
    return chr(ord("a") + index // 26) + chr(ord("a") + index % 26)


def _split_parts(jar: Path) -> list[Path]:
    parts = sorted(jar.parent.glob(f"{jar.name}.part-*"))
    if jar.is_file() and parts:
        raise PreparationError(f"both assembled JAR and split parts are present: {jar}")
    if not parts:
        return []
    if len(parts) < 2:
        raise PreparationError(f"split JAR requires at least two parts: {jar}")
    for index, part in enumerate(parts):
        match = PART_PATTERN.match(part.name)
        if (
            not match
            or match.group("jar") != jar.name
            or match.group("suffix") != _part_suffix(index)
        ):
            raise PreparationError(f"split JAR parts must be contiguous from part-aa: {jar}")
    return parts


def _read_jar(jar: Path) -> bytes:
    parts = _split_parts(jar)
    if jar.is_file():
        return jar.read_bytes()
    if parts:
        return b"".join(part.read_bytes() for part in parts)
    raise PreparationError(f"release JAR is missing: {jar}")


def discover_jars(release_dir: Path) -> dict[str, tuple[Path, bytes]]:
    logical = {path.resolve() for path in release_dir.glob("*.jar")}
    for part in release_dir.glob("*.jar.part-*"):
        match = PART_PATTERN.match(part.name)
        if not match:
            raise PreparationError(f"invalid split JAR part name: {part.name}")
        logical.add((part.parent / match.group("jar")).resolve())
    result = {path.name: (path, _read_jar(path)) for path in logical}
    if not result:
        raise PreparationError(f"no release JARs found in {release_dir}")
    return result


def _zip_entry(archive: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = (0o40755 if name.endswith("/") else 0o100644) << 16
    archive.writestr(info, data, compresslevel=9)


def _minecraft_versions(metadata: dict) -> list[str]:
    depends = metadata.get("depends", {})
    minecraft = depends.get("minecraft") if isinstance(depends, dict) else None
    if isinstance(minecraft, str):
        return [minecraft]
    if isinstance(minecraft, list) and all(isinstance(item, str) for item in minecraft):
        return minecraft
    raise PreparationError("Fabric metadata has no concrete Minecraft dependency")


def _fabric_implementations(
    jars: dict[str, tuple[Path, bytes]]
) -> tuple[dict[str, bytes], set[str]]:
    implementations: dict[str, bytes] = {}
    consumed: set[str] = set()
    for name, (_, data) in jars.items():
        if not name.lower().endswith("-fabric.jar"):
            continue
        consumed.add(name)
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                metadata = json.loads(archive.read("fabric.mod.json"))
                nested = metadata.get("jars", [])
                if nested:
                    for item in nested:
                        nested_name = item.get("file") if isinstance(item, dict) else None
                        if not isinstance(nested_name, str):
                            raise PreparationError(f"invalid nested Fabric JAR in {name}")
                        nested_data = archive.read(nested_name)
                        with zipfile.ZipFile(io.BytesIO(nested_data)) as nested_archive:
                            nested_metadata = json.loads(nested_archive.read("fabric.mod.json"))
                        versions = _minecraft_versions(nested_metadata)
                        if len(versions) != 1:
                            raise PreparationError(f"nested Fabric JAR is not exact-version: {nested_name}")
                        version = versions[0]
                        if version in implementations:
                            raise PreparationError(f"duplicate Fabric implementation for {version}")
                        implementations[version] = nested_data
                else:
                    versions = _minecraft_versions(metadata)
                    if len(versions) != 1:
                        raise PreparationError(f"Fabric JAR is not exact-version: {name}")
                    version = versions[0]
                    if version in implementations:
                        raise PreparationError(f"duplicate Fabric implementation for {version}")
                    implementations[version] = data
        except (KeyError, json.JSONDecodeError, zipfile.BadZipFile) as error:
            raise PreparationError(f"invalid Fabric JAR {name}: {error}") from error
    if not implementations:
        raise PreparationError("release contains no Fabric implementations")
    return implementations, consumed


def _version_key(version: str) -> tuple[int, ...]:
    try:
        return tuple(int(part) for part in version.split("."))
    except ValueError as error:
        raise PreparationError(f"unsupported Minecraft version: {version}") from error


def _build_fabric_all(version: str, implementations: dict[str, bytes]) -> bytes:
    versions = sorted(implementations, key=_version_key)
    metadata = {
        "schemaVersion": 1,
        "id": "universal_translator_all_fabric_bundle",
        "version": version,
        "name": "MC Auto Translation Tool - All Fabric Versions",
        "description": "Selects the exact Universal Translator implementation for this Minecraft version.",
        "authors": ["我小张7272635"],
        "contact": {"sources": "https://github.com/wuxiangdan96-byte/mc-auto-translation-tool"},
        "license": "MIT",
        "environment": "client",
        "jars": [
            {"file": f"META-INF/jars/universal-translator-{item}.jar"}
            for item in versions
        ],
        "depends": {
            "fabricloader": ">=0.19.3",
            "minecraft": versions,
            "java": ">=8",
            "universal_translator": "*",
        },
    }
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        _zip_entry(
            archive,
            "fabric.mod.json",
            (json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode("utf-8"),
        )
        _zip_entry(
            archive,
            "LICENSE_universal_translator_bundle",
            (Path(__file__).resolve().parent.parent / "LICENSE").read_bytes(),
        )
        _zip_entry(
            archive,
            "THIRD_PARTY_OFFLINE.md",
            (
                Path(__file__).resolve().parent.parent
                / "docs/en/THIRD_PARTY_OFFLINE.md"
            ).read_bytes(),
        )
        for item in versions:
            _zip_entry(
                archive,
                f"META-INF/jars/universal-translator-{item}.jar",
                implementations[item],
            )
    return output.getvalue()


def _forge_version_from_name(name: str, release_version: str) -> str | None:
    prefix = f"MCAutoTranslationTool-{release_version}-mc"
    suffix = "-forge.jar"
    if name.startswith(prefix) and name.endswith(suffix):
        return name[len(prefix) : -len(suffix)]
    return None


def _archive_files(data: bytes, exclude: set[str] | None = None) -> dict[str, bytes]:
    exclude = exclude or set()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        return {
            name: archive.read(name)
            for name in archive.namelist()
            if not name.endswith("/") and name not in exclude
        }


def _build_forge_family(
    release_version: str,
    family: ForgeFamily,
    members: list[tuple[str, bytes]],
) -> tuple[str, bytes]:
    payloads = [
        _archive_files(data, {"META-INF/mods.toml"}) for _, data in members
    ]
    if any(payload != payloads[0] for payload in payloads[1:]):
        changed = sorted(
            name
            for name in set().union(*(payload.keys() for payload in payloads))
            if len({payload.get(name) for payload in payloads}) > 1
        )
        raise PreparationError(
            f"Forge family {family.output_range} is not byte-compatible: {changed}"
        )

    representative = members[0][1]
    with zipfile.ZipFile(io.BytesIO(representative)) as source:
        metadata = source.read("META-INF/mods.toml").decode("utf-8")
        dependency = re.compile(
            r'(\[\[dependencies\.universal_translator]](?:(?!\[\[).)*?'
            r'modId="minecraft"(?:(?!\[\[).)*?versionRange=")([^"]+)(")',
            re.DOTALL,
        )
        metadata, count = dependency.subn(
            lambda match: match.group(1) + family.minecraft_range + match.group(3),
            metadata,
            count=1,
        )
        if count != 1:
            raise PreparationError(f"missing Minecraft dependency in Forge {family.output_range}")
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as target:
            for name in source.namelist():
                data = (
                    metadata.encode("utf-8")
                    if name == "META-INF/mods.toml"
                    else source.read(name)
                )
                _zip_entry(target, name, data)
    name = (
        f"MCAutoTranslationTool-{release_version}-mc{family.output_range}-forge.jar"
    )
    return name, output.getvalue()


def prepare_release(
    release_dir: Path, output_dir: Path, version: str
) -> list[Path]:
    release_dir = release_dir.resolve()
    output_dir = output_dir.resolve()
    if not release_dir.is_dir():
        raise PreparationError(f"release directory is missing: {release_dir}")
    if release_dir == output_dir:
        raise PreparationError("output directory must differ from release directory")
    jars = discover_jars(release_dir)
    expected_prefix = f"MCAutoTranslationTool-{version}-"
    mismatched = sorted(name for name in jars if not name.startswith(expected_prefix))
    if mismatched:
        raise PreparationError(
            "release JAR filename does not match version "
            f"{version}: {', '.join(mismatched)}"
        )
    fabric, consumed = _fabric_implementations(jars)

    forge_by_version: dict[str, tuple[str, bytes]] = {}
    for name, (_, data) in jars.items():
        forge_version = _forge_version_from_name(name, version)
        if forge_version:
            forge_by_version[forge_version] = (name, data)

    generated: dict[str, bytes] = {
        f"MCAutoTranslationTool-{version}-fabric-all.jar": _build_fabric_all(version, fabric)
    }

    for family in FORGE_FAMILIES:
        missing = [item for item in family.members if item not in forge_by_version]
        if missing:
            raise PreparationError(
                f"Forge family {family.output_range} is missing: {', '.join(missing)}"
            )
        members = [forge_by_version[item] for item in family.members]
        family_name, family_data = _build_forge_family(version, family, members)
        generated[family_name] = family_data
        consumed.update(name for name, _ in members)

    for name, (_, data) in jars.items():
        if name not in consumed:
            generated[name] = data

    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True)
    assets = []
    for name, data in sorted(generated.items()):
        path = output_dir / name
        path.write_bytes(data)
        assets.append(path)
    checksum = output_dir / "SHA256SUMS.txt"
    checksum.write_text(
        "".join(
            f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
            for path in assets
        ),
        encoding="ascii",
        newline="\n",
    )
    return [*assets, checksum]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--version", required=True)
    args = parser.parse_args(argv)
    try:
        assets = prepare_release(args.release_dir, args.output_dir, args.version)
        for asset in assets:
            print(f"Created {asset}")
        return 0
    except (OSError, PreparationError, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
