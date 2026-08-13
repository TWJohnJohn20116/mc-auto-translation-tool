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
    mixin = {
        "required": True,
        "package": "example.mixin",
        "compatibilityLevel": "JAVA_17",
        "client": ["ExampleMixin"],
    }
    return jar({
        "META-INF/MANIFEST.MF": (
            "Manifest-Version: 1.0\n"
            "MixinConfigs: universal-translator.forge.mixins.json\n"
        ),
        "META-INF/mods.toml": metadata,
        "universal-translator.forge.mixins.json": json.dumps(mixin),
        "example/Class.class": payload,
    })


def neoforge_1201_jar(payload: bytes = b"same") -> bytes:
    metadata = '''modLoader="javafml"
loaderVersion="[47,)"
[[mods]]
modId="universal_translator"
[[dependencies.universal_translator]]
modId="forge"
versionRange="[47.1.106,47.2)"
[[dependencies.universal_translator]]
modId="minecraft"
versionRange="[1.20.1,1.20.2)"
'''
    mixin = {
        "required": True,
        "minVersion": "0.8.5",
        "package": "example.mixin",
        "compatibilityLevel": "JAVA_17",
        "client": ["ExampleMixin"],
    }
    return jar({
        "META-INF/MANIFEST.MF": (
            "Manifest-Version: 1.0\n"
            "MixinConfigs: universal-translator.neoforge.mixins.json\n"
        ),
        "META-INF/mods.toml": metadata,
        "universal-translator.neoforge.mixins.json": json.dumps(mixin),
        "example/Class.class": payload,
    })


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
        (release / "MCAutoTranslationTool-1.2.3-mc1.20.1-forge.jar").write_bytes(
            forge_jar("1.20.1")
        )
        (release / "MCAutoTranslationTool-1.2.3-mc1.20.1-neoforge.jar").write_bytes(
            neoforge_1201_jar()
        )

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
            self.assertIn("MCAutoTranslationTool-1.2.3-mc1.20.1-forge.jar", names)
            self.assertIn("MCAutoTranslationTool-1.2.3-mc1.20.1-neoforge.jar", names)
            self.assertIn("MCAutoTranslationTool-1.2.3-mc1.21.9-1.21.11-forge.jar", names)
            self.assertNotIn("MCAutoTranslationTool-1.2.3-mc1.21.5-forge.jar", names)

            with zipfile.ZipFile(root / "assets/MCAutoTranslationTool-1.2.3-fabric-all.jar") as archive:
                metadata = json.loads(archive.read("fabric.mod.json"))
                self.assertEqual(["1.16.5", "1.19.2", "1.20.1"], metadata["depends"]["minecraft"])
                self.assertEqual(3, len(metadata["jars"]))
                self.assertIn("LICENSE_universal_translator_bundle", archive.namelist())
                self.assertIn("THIRD_PARTY_OFFLINE.md", archive.namelist())
            with zipfile.ZipFile(root / "assets/MCAutoTranslationTool-1.2.3-mc1.21-1.21.5-forge.jar") as archive:
                metadata = archive.read("META-INF/mods.toml").decode()
                self.assertIn('versionRange="[1.21,1.21.6)"', metadata)
            with zipfile.ZipFile(root / "assets/MCAutoTranslationTool-1.2.3-mc1.20.1-forge.jar") as archive:
                self.assertIn("universal-translator.forge.mixins.json", archive.namelist())
                self.assertNotIn("universal-translator.neoforge.mixins.json", archive.namelist())
            with zipfile.ZipFile(root / "assets/MCAutoTranslationTool-1.2.3-mc1.20.1-neoforge.jar") as archive:
                self.assertIn("universal-translator.neoforge.mixins.json", archive.namelist())
                self.assertNotIn("universal-translator.forge.mixins.json", archive.namelist())

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

    def test_rejects_release_filename_with_wrong_version(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "release"
            release.mkdir()
            self._fixture(release)
            wrong = release / "MCAutoTranslationTool-old-mc1.12.2-forge.jar"
            wrong.write_bytes(forge_jar("1.12.2"))
            with self.assertRaisesRegex(PreparationError, "does not match version"):
                prepare_release(release, root / "assets", "1.2.3")

    def test_preserves_loader_specific_1201_payloads(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "release"
            release.mkdir()
            self._fixture(release)
            neoforge = release / "MCAutoTranslationTool-1.2.3-mc1.20.1-neoforge.jar"
            neoforge.write_bytes(neoforge_1201_jar(b"loader-specific"))
            prepare_release(release, root / "assets", "1.2.3")
            with zipfile.ZipFile(root / "assets" / neoforge.name) as archive:
                self.assertEqual(b"loader-specific", archive.read("example/Class.class"))


if __name__ == "__main__":
    unittest.main()
