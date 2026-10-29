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

from release_layout import PART_PATTERN, part_suffix


ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)

#: ``ZipInfo.create_system`` 固定寫成 3（Unix）。``zipfile.ZipInfo.__init__`` 會依
#: ``sys.platform`` 給值：Windows 給 0、其餘平台給 3，而這個值會進每個 local header 與
#: central directory，所以同一批輸入在本機（Windows）與 CI（ubuntu-24.04）會產生不同位元組、
#: 不同 SHA-256，讓「發布資產可重現」無法驗證。選 3 而不是 0 是因為驗證與發布都在 Linux 上
#: 進行（既有 downloads/<version> 的產物就是 create_system=3 的結果），這條設定只是把
#: 「跑在哪台機器」從輸出裡拿掉，不會改變 CI 產生的位元組。
ZIP_CREATE_SYSTEM = 3

#: 檔案與目錄的 Unix 權限位，寫進 ``external_attr`` 的高 16 位。
ZIP_FILE_ATTR = 0o100644 << 16
ZIP_DIR_ATTR = 0o40755 << 16


def _entry_attr(name: str) -> int:
    return ZIP_DIR_ATTR if name.endswith("/") else ZIP_FILE_ATTR


class PreparationError(RuntimeError):
    pass


@dataclass(frozen=True)
class ForgeFamily:
    """一個合併後的 Forge 家族：成員各一顆 JAR，共用同一份 payload。

    `members` 同時是「這個家族真的有實作的 Minecraft 版本」——`prepare_release` 要求每個成員
    都提供一顆 JAR，缺一個就失敗。因此宣告給 Forge 的依賴範圍直接由它推導（見
    `minecraft_version_range`），不再另存一份手寫的 `versionRange` 字串：那第二份真相就是
    「宣告放行、實際沒有實作」的來源。
    """

    members: tuple[str, ...]
    output_range: str

    @property
    def minecraft_version_range(self) -> str:
        """只宣告真正有實作的版本：每個版本一個精確 restriction 的聯集。

        為什麼不用連續區間（原本的 `[1.21,1.21.6)`）：`platforms/forge/1.21/versions/` 沒有
        1.21.2 的目錄，那個版本也沒有 JAR。連續區間會讓 1.21.2 通過 Forge 的依賴檢查，
        玩家裝上去之後才在載入 mixin／位元組碼時失敗——錯誤被延後到最難診斷的地方。
        聯集語法（逗號分隔多個 restriction）是 Maven `VersionRange` 的標準寫法，
        驗證端 `verify_release_jars.py` 會逐段要求「上下界都在」，所以不會退化成無界宣告。
        """
        return _minecraft_dependency_range(self.members)


def _minecraft_dependency_range(versions: tuple[str, ...]) -> str:
    ordered = sorted(versions, key=_version_key)
    return ",".join(f"[{version},{version}]" for version in ordered)


FORGE_FAMILIES = (
    # 檔名（output_range）沿用歷史標籤，因為它已經是已發布資產的名稱；依賴範圍則逐版本列出。
    ForgeFamily(("1.21", "1.21.1", "1.21.3", "1.21.4", "1.21.5"), "1.21-1.21.5"),
    ForgeFamily(("1.21.6", "1.21.7", "1.21.8"), "1.21.6-1.21.8"),
    ForgeFamily(("1.21.9", "1.21.10", "1.21.11"), "1.21.9-1.21.11"),
    ForgeFamily(("26.1", "26.1.1", "26.1.2"), "26.1-26.1.2"),
)


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
            or match.group("suffix") != part_suffix(index)
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
    # 用排序後的順序而不是 set 的迭代序：Path 的雜湊取決於字串雜湊（每個進程隨機），於是
    # _fabric_implementations 遇到重複版本時丟哪一顆、unmerged_forge_groups 印 NOTE 的順序、
    # forge_by_version 被同名覆蓋時保留哪一顆都會隨 PYTHONHASHSEED 變動。
    result = {path.name: (path, _read_jar(path)) for path in sorted(logical)}
    if not result:
        raise PreparationError(f"no release JARs found in {release_dir}")
    return result


def _zip_entry(archive: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
    info.create_system = ZIP_CREATE_SYSTEM
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = _entry_attr(name)
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


def _stored_jar(data: bytes) -> bytes:
    with zipfile.ZipFile(io.BytesIO(data)) as src:
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as dst:
            for item in src.infolist():
                info = zipfile.ZipInfo(item.filename, ZIP_TIMESTAMP)
                # 這裡刻意不複製 item.external_attr：來源 JAR 若是在 Windows 上建置的，
                # 那 16 位就是 0，同一份 payload 會因此得到不同的位元組。改成依檔名推導固定
                # 的 Unix 權限位，嵌套 JAR 的 entry 屬性就只取決於它的名字。
                info.create_system = ZIP_CREATE_SYSTEM
                info.compress_type = zipfile.ZIP_STORED
                info.external_attr = _entry_attr(item.filename)
                dst.writestr(info, src.read(item.filename))
        return output.getvalue()


def _build_fabric_all(version: str, implementations: dict[str, bytes]) -> bytes:
    versions = sorted(implementations, key=_version_key)
    metadata = {
        "schemaVersion": 1,
        "id": "universal_translator_all_fabric_bundle",
        "version": version,
        "name": "MC Auto Translation Tool - All Fabric Versions",
        "description": "Selects the exact Universal Translator implementation for this Minecraft version.",
        "authors": ["我小张7272635"],
        "contact": {"sources": "https://github.com/TWJohnJohn20116/mc-auto-translation-tool"},
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
                _stored_jar(implementations[item]),
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
            lambda match: match.group(1) + family.minecraft_version_range + match.group(3),
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


def unmerged_forge_groups(
    jars: dict[str, tuple[Path, bytes]], release_version: str
) -> list[tuple[str, ...]]:
    """Report Forge versions with identical payloads that no family merges together.

    Byte-identical payloads are the precondition `_build_forge_family` enforces, so any
    such group outside `FORGE_FAMILIES` is a release JAR that could have been merged and
    was not. Declared families are excluded because they are already collapsed.
    """
    declared = {member for family in FORGE_FAMILIES for member in family.members}
    payloads: dict[str, bytes] = {}
    for name, (_, data) in jars.items():
        forge_version = _forge_version_from_name(name, release_version)
        if forge_version is None or forge_version in declared:
            continue
        entries = _archive_files(data, {"META-INF/mods.toml"})
        payloads[forge_version] = repr(sorted(entries.items())).encode("utf-8")
    grouped: dict[bytes, list[str]] = {}
    for forge_version, digest in payloads.items():
        grouped.setdefault(digest, []).append(forge_version)
    return [
        tuple(sorted(group, key=_version_key))
        for group in grouped.values()
        if len(group) > 1
    ]


def _is_within(path: Path, parent: Path) -> bool:
    """Return True when `path` is `parent` itself or nested inside it.

    Uses `Path.parents` rather than `Path.is_relative_to` so the guard also holds on
    interpreters older than 3.9. Both operands are resolved by the caller, so a relative
    `..` cannot escape the comparison. A path on another drive (or another root) shares no
    parents with `parent` and therefore compares as "not inside": this function alone
    cannot prove a directory is safe, which is why `_validate_output_dir` also requires an
    explicitly allowed location.
    """
    return path == parent or parent in path.parents


def _validate_output_dir(output_dir: Path) -> None:
    """Refuse an output directory that a mistyped argument could point at repository content.

    The three release-directory guards below only describe how `output_dir` relates to
    `downloads/<version>`, so `--output-dir scripts`, `--output-dir docs`, `--output-dir .`
    and `--output-dir ..` all passed them and were then deleted by `shutil.rmtree` and
    refilled with the generated JARs.
    """
    if _is_within(output_dir, Path.cwd().resolve() / "build"):
        return
    # build/ 之外只接受「還不存在的新目錄」（CI 傳 $RUNNER_TEMP/release-assets）或上一次執行
    # 留下的資產集——後者以 SHA256SUMS.txt 認身分，那是這個工具唯一的產物簽名。
    if output_dir.exists() and not (output_dir / "SHA256SUMS.txt").is_file():
        raise PreparationError(
            "output directory exists and is not a previous asset set "
            f"(no SHA256SUMS.txt): {output_dir}"
        )


def prepare_release(
    release_dir: Path, output_dir: Path, version: str
) -> list[Path]:
    release_dir = release_dir.resolve()
    output_dir = output_dir.resolve()
    if not release_dir.is_dir():
        raise PreparationError(f"release directory is missing: {release_dir}")
    if release_dir == output_dir:
        raise PreparationError("output directory must differ from release directory")
    if _is_within(release_dir, output_dir):
        raise PreparationError(
            "output directory must not contain the release directory: "
            f"{output_dir} contains {release_dir}"
        )
    if _is_within(output_dir, release_dir):
        raise PreparationError(
            "output directory must not be inside the release directory: "
            f"{output_dir} is inside {release_dir}"
        )
    _validate_output_dir(output_dir)
    jars = discover_jars(release_dir)
    expected_prefix = f"MCAutoTranslationTool-{version}-"
    mismatched = sorted(name for name in jars if not name.startswith(expected_prefix))
    if mismatched:
        raise PreparationError(
            "release JAR filename does not match version "
            f"{version}: {', '.join(mismatched)}"
        )
    fabric, consumed = _fabric_implementations(jars)

    for group in unmerged_forge_groups(jars, version):
        print(
            "NOTE: Forge "
            + ", ".join(group)
            + " have identical payloads and could share one JAR",
            file=sys.stderr,
        )

    forge_by_version: dict[str, tuple[str, bytes]] = {}
    for name, (_, data) in jars.items():
        forge_version = _forge_version_from_name(name, version)
        if forge_version:
            if forge_version in forge_by_version:
                # 同名覆蓋會讓「這一版的 Forge JAR 是哪一顆」取決於迭代序；兩個檔名指向同一個
                # Minecraft 版本必須是顯性錯誤（例如同時存在單版本與 bundle 產物）。
                raise PreparationError(
                    f"duplicate Forge JAR for {forge_version}: "
                    f"{forge_by_version[forge_version][0]} and {name}"
                )
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
