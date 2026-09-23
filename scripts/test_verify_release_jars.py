#!/usr/bin/env python3

import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from verify_release_jars import (
    VerificationError,
    _expected_release_version,
    _validate_archive_paths,
    verify_checksums,
    verify_jar,
    verify_jar_bytes,
)


def make_jar(
    entries: dict[str, bytes | str], include_legal_files: bool = True
) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        if include_legal_files:
            archive.writestr("LICENSE_universal_translator", "MIT")
            archive.writestr("THIRD_PARTY_OFFLINE.md", "Third-party notices")
        for name, value in entries.items():
            archive.writestr(name, value)
    return output.getvalue()


def make_jar_with_raw_name(name: str, value: bytes = b"payload") -> bytes:
    """Build a JAR whose entry name survives verbatim.

    `zipfile.writestr` rewrites backslashes to forward slashes on Windows, so the
    hostile name is patched into the serialized archive instead.
    """
    placeholder = "P" * len(name.encode("utf-8"))
    return make_jar({placeholder: value}, include_legal_files=False).replace(
        placeholder.encode("utf-8"), name.encode("utf-8")
    )


class JarVerifierTest(unittest.TestCase):
    def test_valid_fabric_jar(self) -> None:
        metadata = {
            "schemaVersion": 1,
            "id": "universal_translator",
            "version": "1.0",
            "depends": {"minecraft": "1.20.1"},
            "entrypoints": {"client": ["example.Client"]},
            "mixins": ["example.mixins.json"],
        }
        mixins = {"package": "example.mixin", "client": ["ScreenMixin"]}
        result = verify_jar_bytes(
            make_jar(
                {
                    "fabric.mod.json": json.dumps(metadata),
                    "example.mixins.json": json.dumps(mixins),
                    "example/Client.class": b"class",
                    "example/mixin/ScreenMixin.class": b"class",
                }
            ),
            "fabric.jar",
        )
        self.assertEqual("fabric", result.loader)
        self.assertEqual(1, result.mixins)

    def test_missing_mixin_class_is_rejected(self) -> None:
        metadata = {
            "schemaVersion": 1,
            "id": "universal_translator",
            "version": "1.0",
            "depends": {"minecraft": "1.20.1"},
            "mixins": ["example.mixins.json"],
        }
        with self.assertRaisesRegex(VerificationError, "missing Mixin class"):
            verify_jar_bytes(
                make_jar(
                    {
                        "fabric.mod.json": json.dumps(metadata),
                        "example.mixins.json": json.dumps(
                            {"package": "example", "client": ["MissingMixin"]}
                        ),
                    }
                ),
                "broken.jar",
            )

    def test_mojmap_legacy_forge_jar_is_rejected(self) -> None:
        metadata = '''
modLoader="javafml"
loaderVersion="[47,)"
[[mods]]
modId="universal_translator"
version="1.0"
[[dependencies.universal_translator]]
modId="minecraft"
versionRange="[1.20.1,1.20.2)"
'''
        with self.assertRaisesRegex(VerificationError, "Mojmap"):
            verify_jar_bytes(
                make_jar(
                    {
                        "META-INF/mods.toml": metadata,
                        "org/universaltranslator/forge/ForgeTranslationRuntime.class":
                            b"net/minecraft/client/Minecraft\0getInstance",
                    }
                ),
                "forge.jar",
            )

    def test_valid_neoforge_jar(self) -> None:
        metadata = '''
modLoader="javafml"
loaderVersion="[4,)"
[[mods]]
modId="universal_translator"
version="1.0"
[[dependencies.universal_translator]]
modId="neoforge"
versionRange="[21.1,22)"
[[dependencies.universal_translator]]
modId="minecraft"
versionRange="[1.21.1,1.21.2)"
'''
        result = verify_jar_bytes(
            make_jar({"META-INF/neoforge.mods.toml": metadata}),
            "neoforge.jar",
        )
        self.assertEqual("neoforge", result.loader)

    def test_checksum_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / "sample.jar"
            jar.write_bytes(b"sample")
            checksum = root / "SHA256SUMS.txt"
            checksum.write_text(f"{'0' * 64}  {jar.name}\n", encoding="utf-8")
            with self.assertRaisesRegex(VerificationError, "checksum mismatch"):
                verify_checksums(checksum, True)

    def test_complete_checksum_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / "sample.jar"
            jar.write_bytes(b"sample")
            digest = hashlib.sha256(jar.read_bytes()).hexdigest()
            checksum = root / "SHA256SUMS.txt"
            checksum.write_text(f"{digest}  {jar.name}\n", encoding="utf-8")
            self.assertEqual(1, verify_checksums(checksum, True))

    def test_split_jar_is_reassembled_for_checksum_and_structure(self) -> None:
        metadata = {
            "schemaVersion": 1,
            "id": "universal_translator",
            "version": "1.0",
            "depends": {"minecraft": "1.20.1"},
        }
        data = make_jar({"fabric.mod.json": json.dumps(metadata)})
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / "sample.jar"
            midpoint = len(data) // 2
            (root / "sample.jar.part-aa").write_bytes(data[:midpoint])
            (root / "sample.jar.part-ab").write_bytes(data[midpoint:])
            digest = hashlib.sha256(data).hexdigest()
            checksum = root / "SHA256SUMS.txt"
            checksum.write_text(f"{digest}  {jar.name}\n", encoding="utf-8")

            self.assertEqual(1, verify_checksums(checksum, True))
            self.assertEqual("fabric", verify_jar(jar).loader)

    def test_split_jar_gap_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / "sample.jar"
            (root / "sample.jar.part-aa").write_bytes(b"first")
            (root / "sample.jar.part-ac").write_bytes(b"third")
            checksum = root / "SHA256SUMS.txt"
            checksum.write_text(f"{'0' * 64}  {jar.name}\n", encoding="utf-8")
            with self.assertRaisesRegex(VerificationError, "contiguous"):
                verify_checksums(checksum, True)

    def test_checksum_path_escape_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            checksum = root / "SHA256SUMS.txt"
            checksum.write_text(f"{'0' * 64}  ../outside.jar\n", encoding="utf-8")
            with self.assertRaisesRegex(VerificationError, "escapes release directory"):
                verify_checksums(checksum, False)

    def test_unexpanded_metadata_is_rejected(self) -> None:
        metadata = {
            "schemaVersion": 1,
            "id": "universal_translator",
            "version": "${version}",
            "depends": {"minecraft": "1.20.1"},
        }
        with self.assertRaisesRegex(VerificationError, "unexpanded"):
            verify_jar_bytes(
                make_jar({"fabric.mod.json": json.dumps(metadata)}),
                "template.jar",
            )

    def test_release_filename_version_must_match_fabric_metadata(self) -> None:
        metadata = {
            "schemaVersion": 1,
            "id": "universal_translator",
            "version": "1.2.1",
            "depends": {"minecraft": "1.20.1"},
        }
        with self.assertRaisesRegex(VerificationError, "version mismatch"):
            verify_jar_bytes(
                make_jar({"fabric.mod.json": json.dumps(metadata)}),
                "MCAutoTranslationTool-1.3.0-mc1.20.1-fabric.jar",
            )

    def test_release_filename_version_must_match_classic_forge_metadata(self) -> None:
        metadata = [{
            "modid": "universal_translator",
            "version": "1.3.0-test.2",
            "mcversion": "1.8.9",
        }]
        with self.assertRaisesRegex(VerificationError, "version mismatch"):
            verify_jar_bytes(
                make_jar({"mcmod.info": json.dumps(metadata)}),
                "MCAutoTranslationTool-1.3.0-mc1.8.9-forge.jar",
            )

    def test_missing_legal_files_are_rejected(self) -> None:
        metadata = {
            "schemaVersion": 1,
            "id": "universal_translator",
            "version": "1.3.0",
            "depends": {"minecraft": "1.20.1"},
        }
        with self.assertRaisesRegex(VerificationError, "license"):
            verify_jar_bytes(
                make_jar(
                    {"fabric.mod.json": json.dumps(metadata)},
                    include_legal_files=False,
                ),
                "MCAutoTranslationTool-1.3.0-mc1.20.1-fabric.jar",
            )

    def test_windows_drive_absolute_entry_is_rejected(self) -> None:
        with zipfile.ZipFile(io.BytesIO(make_jar_with_raw_name("C:/Windows/Temp/evil.txt"))) as archive:
            with self.assertRaisesRegex(VerificationError, "unsafe ZIP entry"):
                _validate_archive_paths(archive)

    def test_lowercase_drive_absolute_entry_is_rejected(self) -> None:
        with zipfile.ZipFile(io.BytesIO(make_jar_with_raw_name("c:/users/x/evil.exe"))) as archive:
            with self.assertRaisesRegex(VerificationError, "unsafe ZIP entry"):
                _validate_archive_paths(archive)

    def test_backslash_parent_traversal_entry_is_rejected(self) -> None:
        with zipfile.ZipFile(
            io.BytesIO(make_jar_with_raw_name("..\\evil.txt"))
        ) as archive:
            with self.assertRaisesRegex(VerificationError, "unsafe ZIP entry"):
                _validate_archive_paths(archive)

    def test_backslash_entry_is_rejected_independently_of_platform(self) -> None:
        # `zipfile` rewrites `\` to `/` while reading on Windows, which would hide the
        # rule; a duck-typed archive keeps the raw name so the check is exercised on
        # every platform.
        class Archive:
            @staticmethod
            def namelist() -> list[str]:
                return ["org\\universaltranslator\\Example.class"]

        with self.assertRaisesRegex(VerificationError, "unsafe ZIP entry"):
            _validate_archive_paths(Archive())

    def test_posix_parent_traversal_entry_is_rejected(self) -> None:
        with zipfile.ZipFile(io.BytesIO(make_jar_with_raw_name("../evil.txt"))) as archive:
            with self.assertRaisesRegex(VerificationError, "unsafe ZIP entry"):
                _validate_archive_paths(archive)

    def test_absolute_posix_entry_is_rejected(self) -> None:
        with zipfile.ZipFile(io.BytesIO(make_jar_with_raw_name("/abs/evil.txt"))) as archive:
            with self.assertRaisesRegex(VerificationError, "unsafe ZIP entry"):
                _validate_archive_paths(archive)

    def test_normal_relative_entries_are_accepted(self) -> None:
        with zipfile.ZipFile(
            io.BytesIO(
                make_jar(
                    {
                        "META-INF/MANIFEST.MF": "Manifest-Version: 1.0\n",
                        "org/universaltranslator/Example.class": b"class",
                    }
                )
            )
        ) as archive:
            _validate_archive_paths(archive)

    def test_windows_style_label_yields_expected_release_version(self) -> None:
        label = (
            r"C:\build\libs\MCAutoTranslationTool-1.3.11-rc1-mc1.20.1-forge.jar"
        )
        self.assertEqual("1.3.11-rc1", _expected_release_version(label))

    def test_windows_style_label_still_cross_checks_mod_version(self) -> None:
        metadata = '''
modLoader="javafml"
loaderVersion="[47,)"
[[mods]]
modId="universal_translator"
version="1.2.1"
[[dependencies.universal_translator]]
modId="minecraft"
versionRange="[1.20.1,1.20.2)"
'''
        with self.assertRaisesRegex(VerificationError, "version mismatch"):
            verify_jar_bytes(
                make_jar({"META-INF/mods.toml": metadata}),
                r"C:\build\libs\MCAutoTranslationTool-1.3.11-rc1-mc1.20.1-forge.jar",
            )

    def test_empty_required_mixin_config_is_rejected(self) -> None:
        metadata = {
            "schemaVersion": 1,
            "id": "universal_translator",
            "version": "1.0",
            "depends": {"minecraft": "1.20.1"},
            "mixins": ["example.mixins.json"],
        }
        with self.assertRaisesRegex(VerificationError, "declares no mixins"):
            verify_jar_bytes(
                make_jar(
                    {
                        "fabric.mod.json": json.dumps(metadata),
                        "example.mixins.json": json.dumps(
                            {"package": "example.mixin", "client": []}
                        ),
                    }
                ),
                "fabric.jar",
            )

    def test_empty_optional_mixin_config_is_accepted(self) -> None:
        metadata = {
            "schemaVersion": 1,
            "id": "universal_translator",
            "version": "1.0",
            "depends": {"minecraft": "1.20.1"},
            "mixins": ["example.mixins.json"],
        }
        result = verify_jar_bytes(
            make_jar(
                {
                    "fabric.mod.json": json.dumps(metadata),
                    "example.mixins.json": json.dumps(
                        {"package": "example.mixin", "required": False, "client": []}
                    ),
                }
            ),
            "fabric.jar",
        )
        self.assertEqual(0, result.mixins)

    def test_too_new_class_file_is_rejected(self) -> None:
        metadata = {
            "schemaVersion": 1,
            "id": "universal_translator",
            "version": "1.3.0",
            "depends": {"minecraft": "1.20.1"},
        }
        java_22_class = b"\xca\xfe\xba\xbe\x00\x00\x00\x42"
        with self.assertRaisesRegex(VerificationError, "requires major 66"):
            verify_jar_bytes(
                make_jar({
                    "fabric.mod.json": json.dumps(metadata),
                    "example/TooNew.class": java_22_class,
                }),
                "MCAutoTranslationTool-1.3.0-mc1.20.1-fabric.jar",
            )


if __name__ == "__main__":
    unittest.main()
