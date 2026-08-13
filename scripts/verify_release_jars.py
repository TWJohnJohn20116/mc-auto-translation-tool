#!/usr/bin/env python3
"""Validate Universal Translator release JAR structure and checksums."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import sys
import zipfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Iterable


PLACEHOLDER = re.compile(r"\$\{[^}]+}")
SHA256_LINE = re.compile(r"^([0-9a-fA-F]{64})\s+[* ]?(.+?)\s*$")
SPLIT_JAR_PART = re.compile(r"^(?P<jar>.+\.jar)\.part-(?P<suffix>[a-z]{2})$")


class VerificationError(RuntimeError):
    pass


@dataclass
class JarResult:
    label: str
    loader: str
    mixins: int
    nested: int


def _read_json(archive: zipfile.ZipFile, name: str) -> object:
    try:
        return json.loads(archive.read(name).decode("utf-8"))
    except (KeyError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationError(f"invalid JSON {name}: {error}") from error


def _reject_placeholders(value: object, label: str) -> None:
    if PLACEHOLDER.search(json.dumps(value, ensure_ascii=False)):
        raise VerificationError(f"unexpanded template placeholder in {label}")


def _validate_archive_paths(archive: zipfile.ZipFile) -> None:
    names = archive.namelist()
    duplicates = sorted(name for name, count in Counter(names).items() if count > 1)
    if duplicates:
        raise VerificationError(f"duplicate ZIP entries: {', '.join(duplicates)}")
    for name in names:
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts:
            raise VerificationError(f"unsafe ZIP entry: {name}")


def _manifest_attributes(archive: zipfile.ZipFile) -> dict[str, str]:
    if "META-INF/MANIFEST.MF" not in archive.namelist():
        return {}
    text = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
    unfolded = text.replace("\r\n ", "").replace("\n ", "")
    attributes: dict[str, str] = {}
    for line in unfolded.splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            attributes[key.strip().lower()] = value.strip()
    return attributes


def _mixin_configs(archive: zipfile.ZipFile, explicit: Iterable[str]) -> list[str]:
    configs = {name for name in explicit if name}
    manifest = _manifest_attributes(archive)
    for name in manifest.get("mixinconfigs", "").split(","):
        if name.strip():
            configs.add(name.strip())
    configs.update(
        name for name in archive.namelist()
        if name.endswith(".mixins.json") and "/" not in name.rstrip("/")
    )
    return sorted(configs)


def _validate_mixins(archive: zipfile.ZipFile, configs: Iterable[str]) -> int:
    count = 0
    for config_name in configs:
        if config_name not in archive.namelist():
            raise VerificationError(f"missing Mixin config: {config_name}")
        config = _read_json(archive, config_name)
        if not isinstance(config, dict):
            raise VerificationError(f"Mixin config is not an object: {config_name}")
        _reject_placeholders(config, config_name)
        package = str(config.get("package", ""))
        refmap = config.get("refmap")
        if refmap and str(refmap) not in archive.namelist():
            raise VerificationError(f"missing refmap {refmap} declared by {config_name}")
        for section in ("mixins", "client", "server"):
            entries = config.get(section, [])
            if not isinstance(entries, list):
                raise VerificationError(f"{config_name} field {section} is not a list")
            for mixin in entries:
                class_name = str(mixin)
                if "." not in class_name and package:
                    class_name = f"{package}.{class_name}"
                class_entry = class_name.replace(".", "/") + ".class"
                if class_entry not in archive.namelist():
                    raise VerificationError(
                        f"missing Mixin class {class_entry} declared by {config_name}"
                    )
                count += 1
    return count


def _fabric_entrypoints(metadata: dict[str, object]) -> list[str]:
    result: list[str] = []
    entrypoints = metadata.get("entrypoints", {})
    if not isinstance(entrypoints, dict):
        return result
    for entries in entrypoints.values():
        if not isinstance(entries, list):
            continue
        for entry in entries:
            value = entry.get("value") if isinstance(entry, dict) else entry
            if isinstance(value, str):
                result.append(value.split("::", 1)[0])
    return result


def _validate_fabric(archive: zipfile.ZipFile, label: str) -> JarResult:
    metadata = _read_json(archive, "fabric.mod.json")
    if not isinstance(metadata, dict):
        raise VerificationError("fabric.mod.json is not an object")
    _reject_placeholders(metadata, "fabric.mod.json")
    if metadata.get("schemaVersion") != 1 or not metadata.get("id"):
        raise VerificationError("invalid Fabric schemaVersion or mod id")
    dependencies = metadata.get("depends")
    if not isinstance(dependencies, dict) or not dependencies.get("minecraft"):
        raise VerificationError("missing Fabric Minecraft dependency")

    mixin_names: list[str] = []
    for item in metadata.get("mixins", []):
        name = item.get("config") if isinstance(item, dict) else item
        if isinstance(name, str):
            mixin_names.append(name)
    mixin_count = _validate_mixins(archive, _mixin_configs(archive, mixin_names))

    for entrypoint in _fabric_entrypoints(metadata):
        class_entry = entrypoint.replace(".", "/") + ".class"
        if class_entry not in archive.namelist():
            raise VerificationError(f"missing Fabric entrypoint class: {class_entry}")

    nested_count = 0
    for nested in metadata.get("jars", []):
        nested_name = nested.get("file") if isinstance(nested, dict) else None
        if not isinstance(nested_name, str) or nested_name not in archive.namelist():
            raise VerificationError(f"missing nested Fabric JAR: {nested_name}")
        verify_jar_bytes(archive.read(nested_name), f"{label}!/{nested_name}")
        nested_count += 1
    return JarResult(label, "fabric", mixin_count, nested_count)


def _forge_minecraft_range(metadata: str) -> str | None:
    match = re.search(
        r'\[\[dependencies\.universal_translator]](?:(?!\[\[).)*?'
        r'modId="minecraft"(?:(?!\[\[).)*?versionRange="([^"]+)"',
        metadata,
        re.DOTALL,
    )
    return match.group(1) if match else None


def _validate_legacy_forge_mapping(
    archive: zipfile.ZipFile, metadata: str, loader: str
) -> None:
    if loader != "forge":
        return
    loader = re.search(r'^loaderVersion="\[(\d+),', metadata, re.MULTILINE)
    if not loader or int(loader.group(1)) > 47:
        return
    class_name = "org/universaltranslator/forge/ForgeTranslationRuntime.class"
    if class_name not in archive.namelist():
        return
    bytecode = archive.read(class_name)
    if b"getInstance" in bytecode:
        raise VerificationError(
            "legacy Forge runtime still contains Mojmap Minecraft.getInstance"
        )


def _validate_forge(
    archive: zipfile.ZipFile, label: str, metadata_path: str
) -> JarResult:
    metadata = archive.read(metadata_path).decode("utf-8")
    if PLACEHOLDER.search(metadata):
        raise VerificationError(f"unexpanded template placeholder in {metadata_path}")
    for required in ('modLoader="javafml"', 'modId="universal_translator"'):
        if required not in metadata:
            raise VerificationError(f"missing Forge metadata field: {required}")
    minecraft_range = _forge_minecraft_range(metadata)
    if not minecraft_range:
        raise VerificationError(f"missing exact Minecraft dependency range in {metadata_path}")
    if not re.fullmatch(r"[\[(][^,]+,[^\])]+[\])]", minecraft_range):
        raise VerificationError(
            f"Forge Minecraft dependency must be a bounded range: {minecraft_range}"
        )
    loader = (
        "neoforge"
        if metadata_path.endswith("neoforge.mods.toml")
        or re.search(r'modId\s*=\s*"neoforge"', metadata)
        else "forge"
    )
    mixin_count = _validate_mixins(archive, _mixin_configs(archive, []))
    _validate_legacy_forge_mapping(archive, metadata, loader)
    return JarResult(label, loader, mixin_count, 0)


def _validate_classic_forge(archive: zipfile.ZipFile, label: str) -> JarResult:
    metadata = _read_json(archive, "mcmod.info")
    _reject_placeholders(metadata, "mcmod.info")
    mixin_count = _validate_mixins(archive, _mixin_configs(archive, []))
    return JarResult(label, "forge-classic", mixin_count, 0)


def verify_jar_bytes(data: bytes, label: str) -> JarResult:
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            _validate_archive_paths(archive)
            names = set(archive.namelist())
            if "fabric.mod.json" in names:
                return _validate_fabric(archive, label)
            if "META-INF/mods.toml" in names:
                return _validate_forge(archive, label, "META-INF/mods.toml")
            if "META-INF/neoforge.mods.toml" in names:
                return _validate_forge(archive, label, "META-INF/neoforge.mods.toml")
            if "mcmod.info" in names:
                return _validate_classic_forge(archive, label)
            raise VerificationError("unrecognized mod metadata")
    except zipfile.BadZipFile as error:
        raise VerificationError(f"invalid ZIP/JAR: {error}") from error


def _part_suffix(index: int) -> str:
    return chr(ord("a") + index // 26) + chr(ord("a") + index % 26)


def _split_parts(path: Path) -> list[Path]:
    parts = sorted(path.parent.glob(f"{path.name}.part-*"))
    if path.is_file() and parts:
        raise VerificationError(f"both assembled JAR and split parts are present: {path}")
    if not parts:
        return []
    if path.suffix.lower() != ".jar":
        raise VerificationError(f"split parts are only supported for JARs: {path}")
    if len(parts) < 2:
        raise VerificationError(f"split JAR requires at least two parts: {path}")
    for index, part in enumerate(parts):
        match = SPLIT_JAR_PART.match(part.name)
        expected = _part_suffix(index)
        if not match or match.group("jar") != path.name or match.group("suffix") != expected:
            raise VerificationError(
                f"split JAR parts must be contiguous from part-aa: {path}"
            )
        if not part.is_file():
            raise VerificationError(f"split JAR part is not a file: {part}")
    return parts


def _read_file_or_split_jar(path: Path) -> bytes:
    parts = _split_parts(path)
    if path.is_file():
        return path.read_bytes()
    if parts:
        return b"".join(part.read_bytes() for part in parts)
    raise VerificationError(f"checksum target is missing: {path}")


def verify_jar(path: Path) -> JarResult:
    try:
        return verify_jar_bytes(_read_file_or_split_jar(path), str(path))
    except OSError as error:
        raise VerificationError(f"could not read JAR: {error}") from error


def verify_checksums(checksum_file: Path, require_complete: bool) -> int:
    expected: dict[Path, str] = {}
    checksum_root = checksum_file.parent.resolve()
    for number, raw_line in enumerate(
        checksum_file.read_text(encoding="utf-8-sig").splitlines(), 1
    ):
        if not raw_line.strip():
            continue
        match = SHA256_LINE.match(raw_line)
        if not match:
            raise VerificationError(f"invalid checksum line {number}: {raw_line}")
        path = (checksum_file.parent / match.group(2)).resolve()
        if path.parent != checksum_root:
            raise VerificationError(f"checksum target escapes release directory: {path}")
        if path in expected:
            raise VerificationError(f"duplicate checksum target: {path.name}")
        expected[path] = match.group(1).lower()
    for path, digest in expected.items():
        actual = hashlib.sha256(_read_file_or_split_jar(path)).hexdigest()
        if actual != digest:
            raise VerificationError(f"checksum mismatch for {path.name}: {actual} != {digest}")
    if require_complete:
        jars = {path.resolve() for path in checksum_file.parent.glob("*.jar")}
        for part in checksum_file.parent.glob("*.jar.part-*"):
            match = SPLIT_JAR_PART.match(part.name)
            if not match:
                raise VerificationError(f"invalid split JAR part name: {part.name}")
            jars.add((part.parent / match.group("jar")).resolve())
        for jar in jars:
            _split_parts(jar)
        checked_jars = {path for path in expected if path.suffix.lower() == ".jar"}
        if jars != checked_jars:
            missing = sorted(path.name for path in jars - checked_jars)
            extra = sorted(path.name for path in checked_jars - jars)
            raise VerificationError(
                f"checksum manifest coverage differs; missing={missing}, extra={extra}"
            )
    return len(expected)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jars", nargs="*", type=Path)
    parser.add_argument("--checksum-file", type=Path)
    parser.add_argument("--require-complete-checksums", action="store_true")
    args = parser.parse_args(argv)
    if not args.jars and not args.checksum_file:
        parser.error("provide at least one JAR or --checksum-file")

    try:
        checksum_count = 0
        if args.checksum_file:
            checksum_count = verify_checksums(
                args.checksum_file, args.require_complete_checksums
            )
        for jar in args.jars:
            result = verify_jar(jar)
            print(
                f"OK {result.loader:13} mixins={result.mixins:2} "
                f"nested={result.nested:2} {result.label}"
            )
        print(f"Verified {len(args.jars)} JARs and {checksum_count} checksums")
        return 0
    except (OSError, VerificationError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
