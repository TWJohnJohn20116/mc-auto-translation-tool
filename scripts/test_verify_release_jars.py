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
    verify_checksums,
    verify_jar,
    verify_jar_bytes,
)


def make_jar(entries: dict[str, bytes | str]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        for name, value in entries.items():
            archive.writestr(name, value)
    return output.getvalue()


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


if __name__ == "__main__":
    unittest.main()
