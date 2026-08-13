#!/usr/bin/env python3

import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from prepare_release_assets import FORGE_FAMILIES, PreparationError, prepare_release


def jar(entries: dict[str, bytes | str]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return output.getvalue()


def fabric_jar(version: str) -> bytes:
    metadata = {
        "schemaVersion": 1,
        "id": "universal_translator",
        "version": "test",
        "depends": {"minecraft": version},
    }
    return jar({"fabric.mod.json": json.dumps(metadata), "example/Class.class": version})


def forge_jar(version: str, payload: bytes = b"same") -> bytes:
    metadata = f'''modLoader="javafml"
loaderVersion="[51,)"
[[mods]]
modId="universal_translator"
[[dependencies.universal_translator]]
modId="forge"
versionRange="[51,)"
[[dependencies.universal_translator]]
modId="minecraft"
versionRange="[{version},{version}.1)"
'''
    return jar({"META-INF/mods.toml": metadata, "example/Class.class": payload})


class PrepareReleaseAssetsTest(unittest.TestCase):
    def _fixture(self, release: Path) -> None:
        for version in ("1.16.5", "1.19.2", "1.20.1"):
            (release / f"MCAutoTranslationTool-1.2.3-mc{version}-fabric.jar").write_bytes(
                fabric_jar(version)
            )
        for family in FORGE_FAMILIES:
            for version in family.members:
                (release / f"MCAutoTranslationTool-1.2.3-mc{version}-forge.jar").write_bytes(
                    forge_jar(version)
                )
        (release / "MCAutoTranslationTool-1.2.3-mc1.20.1-neoforge.jar").write_bytes(b"neo")

    def test_creates_installable_fabric_bundle_and_forge_families(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "release"
            release.mkdir()
            self._fixture(release)
            assets = prepare_release(release, root / "assets", "1.2.3")
            names = {asset.name for asset in assets}
            self.assertIn("MCAutoTranslationTool-1.2.3-fabric-all.jar", names)
            self.assertIn("MCAutoTranslationTool-1.2.3-mc1.21-1.21.5-forge.jar", names)
            self.assertIn("MCAutoTranslationTool-1.2.3-mc1.20.1-neoforge.jar", names)
            self.assertNotIn("MCAutoTranslationTool-1.2.3-mc1.21.5-forge.jar", names)

            with zipfile.ZipFile(root / "assets/MCAutoTranslationTool-1.2.3-fabric-all.jar") as archive:
                metadata = json.loads(archive.read("fabric.mod.json"))
                self.assertEqual(["1.16.5", "1.19.2", "1.20.1"], metadata["depends"]["minecraft"])
                self.assertEqual(3, len(metadata["jars"]))
            with zipfile.ZipFile(root / "assets/MCAutoTranslationTool-1.2.3-mc1.21-1.21.5-forge.jar") as archive:
                metadata = archive.read("META-INF/mods.toml").decode()
                self.assertIn('versionRange="[1.21,1.21.6)"', metadata)

    def test_output_and_checksums_are_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "release"
            release.mkdir()
            self._fixture(release)
            first = prepare_release(release, root / "assets", "1.2.3")
            digests = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in first}
            second = prepare_release(release, root / "assets", "1.2.3")
            self.assertEqual(
                digests,
                {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in second},
            )

    def test_rejects_forge_family_with_different_payloads(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "release"
            release.mkdir()
            self._fixture(release)
            broken = release / "MCAutoTranslationTool-1.2.3-mc1.21.5-forge.jar"
            broken.write_bytes(forge_jar("1.21.5", b"different"))
            with self.assertRaisesRegex(PreparationError, "not byte-compatible"):
                prepare_release(release, root / "assets", "1.2.3")


if __name__ == "__main__":
    unittest.main()
