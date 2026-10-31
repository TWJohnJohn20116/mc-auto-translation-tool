#!/usr/bin/env python3

import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from prepare_release_assets import (
    FORGE_FAMILIES,
    PreparationError,
    installable_manifest,
    list_installable,
    prepare_release,
    unmerged_forge_groups,
)
from release_layout import PART_SIZE
from verify_release_jars import VerificationError, verify_checksums


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
                # 依賴範圍只列真正有實作的版本（1.21.2 沒有實作、也沒有 JAR），
                # 不是連續區間 [1.21,1.21.6)。
                self.assertIn(
                    'versionRange="'
                    "[1.21,1.21],[1.21.1,1.21.1],[1.21.3,1.21.3],"
                    '[1.21.4,1.21.4],[1.21.5,1.21.5]"',
                    metadata,
                )
                self.assertNotIn("1.21.2", metadata)
                self.assertNotIn("1.21.6", metadata)
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

    def test_manifest_only_matches_prepare_release_names(self) -> None:
        # `--manifest-only`（prepare-release.yml 寫進 downloads/<version>/release-assets.txt 的那一份）
        # 與 `prepare_release`（真正組裝資產的那一份）必須對同一份 downloads/ 給出同一組可安裝檔名：
        # 先前兩邊各有一份「檔名推導」，清單可以被一份比生產端寬的判定寫出來，而清單就是四份 README
        # 與網站的權威——漂移要等到發布那一輪才爆。
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "release"
            release.mkdir()
            self._fixture(release)
            assets = prepare_release(release, root / "assets", "1.2.3")
            self.assertEqual(
                installable_manifest(asset.name for asset in assets),
                installable_manifest(list_installable(release, "1.2.3")),
            )

    def test_manifest_only_rejects_a_layout_the_producer_rejects(self) -> None:
        # 生產端 raise 的佈局，`--manifest-only` 不得回 0 並把清單寫出去。
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "release"
            release.mkdir()
            self._fixture(release)
            wrong = release / "MCAutoTranslationTool-old-mc1.12.2-forge.jar"
            wrong.write_bytes(forge_jar("1.12.2"))
            with self.assertRaisesRegex(PreparationError, "does not match version"):
                prepare_release(release, root / "assets", "1.2.3")
            with self.assertRaisesRegex(PreparationError, "does not match version"):
                list_installable(release, "1.2.3")

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

    def test_rejects_output_directory_equal_to_release_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "release"
            release.mkdir()
            self._fixture(release)
            with self.assertRaisesRegex(PreparationError, "must differ"):
                prepare_release(release, release, "1.2.3")
            self.assertTrue((release / "MCAutoTranslationTool-1.2.3-mc1.20.1-forge.jar").is_file())

    def test_rejects_output_directory_containing_release_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "downloads" / "1.2.3"
            release.mkdir(parents=True)
            self._fixture(release)
            with self.assertRaisesRegex(PreparationError, "must not contain"):
                prepare_release(release, root / "downloads", "1.2.3")
            self.assertTrue((release / "MCAutoTranslationTool-1.2.3-mc1.20.1-forge.jar").is_file())

    def test_rejects_output_directory_inside_release_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "release"
            release.mkdir()
            self._fixture(release)
            with self.assertRaisesRegex(PreparationError, "must not be inside"):
                prepare_release(release, release / "assets", "1.2.3")

    def test_allows_sibling_output_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "release"
            release.mkdir()
            self._fixture(release)
            assets = prepare_release(release, root / "assets", "1.2.3")
            self.assertTrue(assets)
            self.assertTrue((root / "assets" / "SHA256SUMS.txt").is_file())

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


    def test_generated_entries_have_platform_independent_zip_metadata(self) -> None:
        # create_system 預設值依 sys.platform 而異（Windows 0、其餘 3），所以「同一進程跑兩次
        # 相同」抓不到跨平台差異（上面那條 deterministic 測試就是這樣漏掉的）。這裡直接斷言
        # 寫進 ZIP 的欄位本身：create_system 固定為 3，external_attr 只由檔名決定。
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "release"
            release.mkdir()
            self._fixture(release)
            prepare_release(release, root / "assets", "1.2.3")
            with zipfile.ZipFile(
                root / "assets/MCAutoTranslationTool-1.2.3-fabric-all.jar"
            ) as archive:
                infos = archive.infolist()
            self.assertTrue(infos)
            self.assertEqual({3}, {info.create_system for info in infos})
            self.assertEqual(
                {0o100644 << 16},
                {
                    info.external_attr
                    for info in infos
                    if not info.filename.endswith("/")
                },
            )

    def test_forge_family_ranges_list_only_implemented_versions(self) -> None:
        # 每個成員都對應一顆真的存在的 JAR（prepare_release 少一個就失敗），所以「成員」就是
        # 「有實作的版本」；宣告用的 versionRange 由它推導，不得放行中間缺的版本（1.21.2）。
        for family in FORGE_FAMILIES:
            declared = family.minecraft_version_range
            self.assertEqual(len(family.members), declared.count("["), declared)
            for member in family.members:
                self.assertIn(f"[{member},{member}]", declared)
            self.assertNotIn("1.21.2", declared)

    def test_reports_identical_forge_payloads_outside_declared_families(self) -> None:
        jars = {
            "MCAutoTranslationTool-1.2.3-mc1.19.2-forge.jar": (
                Path("a"),
                forge_jar("1.19.2", b"shared"),
            ),
            "MCAutoTranslationTool-1.2.3-mc1.20.1-forge.jar": (
                Path("b"),
                forge_jar("1.20.1", b"shared"),
            ),
            "MCAutoTranslationTool-1.2.3-mc1.16.5-forge.jar": (
                Path("c"),
                forge_jar("1.16.5", b"distinct"),
            ),
        }
        self.assertEqual(
            [("1.19.2", "1.20.1")], unmerged_forge_groups(jars, "1.2.3")
        )

    def test_declared_families_are_not_reported_as_unmerged(self) -> None:
        jars = {
            f"MCAutoTranslationTool-1.2.3-mc{member}-forge.jar": (
                Path(member),
                forge_jar(member),
            )
            for family in FORGE_FAMILIES
            for member in family.members
        }
        self.assertEqual([], unmerged_forge_groups(jars, "1.2.3"))

class SplitPartSizeContractTest(unittest.TestCase):
    """600 KiB 分片規則的「尺寸」那一半。

    test_verify_release_jars.py 既有的 split fixture 只切 ~200 bytes 的 JAR，於是
    verify_release_jars._split_parts 的 `total_size > PART_SIZE` 分支永遠不執行——門檻寫錯
    （例如誤成 61440）時 48 顆 JAR 會碎成上百片，而名稱連續、SHA-256 正確，所有檢查照樣通過。
    這裡用合成資料讓那個分支真的被走到，正向與負向各一條。
    """

    def test_part_size_matches_the_600_kib_contract(self) -> None:
        # AGENTS.md 寫的是 600 KiB；程式碼與文件互相綁定，改一邊就會紅。
        self.assertEqual(600 * 1024, PART_SIZE)

    def _checksum(self, root: Path, data: bytes) -> Path:
        checksum = root / "SHA256SUMS.txt"
        checksum.write_text(
            f"{hashlib.sha256(data).hexdigest()}  big.jar\n", encoding="utf-8"
        )
        return checksum

    def test_oversized_split_jar_accepts_full_size_leading_parts(self) -> None:
        data = b"x" * (PART_SIZE + 10)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "big.jar.part-aa").write_bytes(data[:PART_SIZE])
            (root / "big.jar.part-ab").write_bytes(data[PART_SIZE:])
            self.assertEqual(1, verify_checksums(self._checksum(root, data), True))

    def test_oversized_split_jar_rejects_short_leading_part(self) -> None:
        data = b"y" * (PART_SIZE + 10)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            # 少 1 byte：名稱仍然連續、串接起來的 SHA-256 也仍然正確，只有「每個前置分片都
            # 必須剛好 PART_SIZE」這條會抓到。
            (root / "big.jar.part-aa").write_bytes(data[: PART_SIZE - 1])
            (root / "big.jar.part-ab").write_bytes(data[PART_SIZE - 1 :])
            with self.assertRaisesRegex(VerificationError, "unexpected size"):
                verify_checksums(self._checksum(root, data), True)


if __name__ == "__main__":
    unittest.main()
