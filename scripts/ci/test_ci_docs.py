"""scripts/README.md 必須與它索引的腳本行為一致。

兩句話曾經與程式碼相反，而且方向剛好一正一反：README 仍寫「`java.exe` 加入 Defender 排除名單」
（程式碼早就不做，因為那會關掉整台機器的即時掃描），又寫「`wrote 0 poms` 視為失敗」（程式碼在
POM 全部已存在時回 0，那是最常見的健康狀態）。README 是這批工具唯一的入口文件，所以任一邊被
改動時，這裡要讓它在 PR 上就紅，而不是等維護者照文件去查一個不存在的設定。
"""

import json
import pathlib
import re
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
README = ROOT / "scripts" / "README.md"
DEFENDER = ROOT / "scripts" / "ci" / "set-defender-exclusions.ps1"
POMS = ROOT / "scripts" / "tools" / "_gen_mojang_poms.py"
VERSION_SCRIPT = ROOT / "scripts" / "ci" / "read-mod-version.sh"
TARGETS = ROOT / "scripts" / "ci" / "build-targets.json"
#: `downloads/<version>` 的手動生產者：它寫的檔名清單決定清單守衛會不會退化成略過。
ASSEMBLE = ROOT / "scripts" / "tools" / "_assemble_1_3_11_beta.py"
#: 四份 README 是「31 個可安裝檔名」的手抄副本（`publish-release.yml` 只在資產變動時才會比對）。
READMES = (
    ROOT / "README.md",
    ROOT / "docs" / "Zh-cn" / "README.md",
    ROOT / "docs" / "Zh-tw" / "README.md",
    ROOT / "docs" / "en" / "README.md",
)

GRADLE_PROPERTIES = ROOT / "gradle.properties"
#: `build-targets.json` 的群組 → README 用的顯示名（`scripts/README.md` 那句「平台 16 筆、舊版 7 筆」）。
TARGET_LABELS = {"platform": "平台", "legacy": "舊版"}

sys.path.insert(0, str(ROOT / "scripts"))

from prepare_release_assets import (  # noqa: E402  (需要先設好 sys.path)
    MANIFEST_NAME,
    installable_manifest,
    list_installable,
)
from release_layout import PART_SIZE, VERSION_PATTERN  # noqa: E402  (同上)


def mod_version() -> str:
    """`gradle.properties` 的 `mod_version`，規則與 `read-mod-version.sh` 相同（取第一行＋白名單）。"""
    for line in GRADLE_PROPERTIES.read_text(encoding="utf-8").splitlines():
        if not line.startswith("mod_version="):
            continue
        value = line[len("mod_version="):]
        if not VERSION_PATTERN.match(value):
            raise AssertionError("gradle.properties 的 mod_version 不合法：%r" % value)
        return value
    raise AssertionError("gradle.properties 沒有 mod_version")


# `if written == 0:` 的等價寫法（`if not written:`）必須定位到同一個分支：先前是字面字串比對，
# 把條件改寫成等價形式就會以「沒有 written == 0 的分支」紅燈，而行為完全沒變。
POMS_SUCCESS_BRANCH = re.compile(r"^\s*if (written == 0|not written):")


class ScriptsReadmeTest(unittest.TestCase):
    def test_defender_script_still_omits_the_java_process_exclusion(self):
        # 斷言對象是「腳本有沒有呼叫排除程序」，不是原始碼裡那段散文：先前綁在
        # "java.exe *process* exclusion is deliberately absent" 這句話上，於是真正的回歸
        # （加回 -ExclusionProcess）照樣全綠，而改一句英文註解就讓 PR 必經的 lint 紅燈。
        text = DEFENDER.read_text(encoding="utf-8")
        self.assertIn("-ExclusionPath", text)
        self.assertNotIn("-ExclusionProcess", text)

    def test_poms_generator_treats_written_zero_as_success(self):
        lines = POMS.read_text(encoding="utf-8").splitlines()
        start = None
        for index, line in enumerate(lines):
            if POMS_SUCCESS_BRANCH.match(line):
                start = index
                break
        self.assertIsNotNone(start, "_gen_mojang_poms.py 沒有 written == 0 的分支")

        indent = len(lines[start]) - len(lines[start].lstrip())
        block = []
        for line in lines[start + 1:]:
            if line.strip() and (len(line) - len(line.lstrip())) <= indent:
                break
            block.append(line)
        self.assertNotIn(
            "return 1",
            "\n".join(block),
            "POM 全部已存在（健康狀態）時必須回 0，不能當成失敗",
        )

    def test_readme_states_both_contracts(self):
        text = README.read_text(encoding="utf-8")
        self.assertIn("java.exe", text, "README 必須寫明刻意不排除 java.exe 程序")
        self.assertIn("POM 全部已存在", text, "README 必須寫明 POM 全部已存在時回 0")

    def test_readme_indexes_every_ci_helper(self):
        text = README.read_text(encoding="utf-8")
        for name in (
            "write-build-summary.sh",
            "read-mod-version.sh",
            "derive-prepare-targets.py",
            "set-defender-exclusions.ps1",
            "build-targets.json",
        ):
            self.assertIn(name, text, "scripts/README.md 的 ci/ 索引缺少 " + name)

    def test_version_whitelist_matches_the_python_producer(self):
        # 版本號白名單有兩份實作（bash 的 read-mod-version.sh、python 的 release_layout）：bash 讀不到
        # python 常數，做不成「真正一份」，所以讓兩邊的字面規則必須逐字相同——值會被拼進
        # `find -name` 與 `rm -rf`，兩邊判定不同就是第三輪那類事件的復發。
        shell = VERSION_SCRIPT.read_text(encoding="utf-8")
        self.assertIn(
            VERSION_PATTERN.pattern,
            shell,
            "read-mod-version.sh 的白名單與 release_layout.VERSION_PATTERN 不一致",
        )

    def test_readme_states_the_target_count(self):
        # `build-targets.json` 是「權威清單有幾筆」的事實，而 README 是唯一說明筆數的地方：B07-004
        # 刪掉 fabric-1.21.4-1.21.5 之後那行仍寫著平台 17 筆，讀者會照著去找一個不存在的目標。
        counts = {
            key: len(value)
            for key, value in json.loads(TARGETS.read_text(encoding="utf-8")).items()
        }
        text = README.read_text(encoding="utf-8")
        # 逐群組斷言，不只兩個具名 key：新增第三個群組時它的筆數沒有任何文件在講，
        # 而 README 那兩行也照樣通過（build.yml 沒有任何 matrix 會建置它）。
        self.assertEqual({"platform", "legacy"}, set(counts), "build-targets.json 的群組集合變了")
        for key, count in sorted(counts.items()):
            self.assertIn("%s %d 筆" % (TARGET_LABELS[key], count), text)

    def test_committed_split_parts_match_the_part_size(self):
        # PART_SIZE 與 part_suffix 只有一份實作（scripts/release_layout.py），生產端與驗證端都 import
        # 同一份：門檻被改壞時兩邊一起漂移、彼此一致，唯一會露出來的就是已經提交在 downloads/ 的分片。
        groups = {}
        for part in sorted((ROOT / "downloads").glob("*/*.jar.part-*")):
            groups.setdefault(part.name.rsplit(".part-", 1)[0], []).append(part)
        self.assertTrue(groups, "downloads/ 至少要有一組分片，否則這條斷言沒有樣本")
        for base, parts in sorted(groups.items()):
            for part in parts[:-1]:
                self.assertEqual(
                    PART_SIZE,
                    part.stat().st_size,
                    "%s 不是用 PART_SIZE 切的：改門檻要連 downloads/ 一起重新產生" % part.name,
                )
            # 最後一片只受「不超過門檻」約束：完整 JAR 很少剛好是門檻的整數倍。
            self.assertLessEqual(parts[-1].stat().st_size, PART_SIZE, base)
            self.assertGreater(parts[-1].stat().st_size, 0, base)

    def test_readmes_list_the_committed_installable_assets(self):
        # 這條比對原本只存在於 publish-release.yml，而那一版需要「剛重建的資產」在場，於是任何只動
        # 文件的 push 都會重解 48 顆 JAR、再用 --clobber 把 31 顆先刪後傳（位元組完全沒變）。清單
        # 本身是已提交的（prepare-release 產生），所以在 PR 上用純文字比對就夠，而且提早一輪變紅。
        manifests = sorted((ROOT / "downloads").glob("*/" + MANIFEST_NAME))
        if not manifests:
            self.skipTest("downloads/ 還沒有 %s（下一次 prepare-release 才會產生）" % MANIFEST_NAME)
        self.assertEqual(1, len(manifests), "downloads/ 只能有一個 %s" % MANIFEST_NAME)
        manifest = manifests[0]
        expected = [
            line for line in manifest.read_text(encoding="utf-8").splitlines() if line.strip()
        ]
        names = re.compile(r"MCAutoTranslationTool-[A-Za-z0-9.-]*\.jar")
        for readme in READMES:
            found = sorted(set(names.findall(readme.read_text(encoding="utf-8"))))
            self.assertTrue(found, "%s 沒有任何 MCAutoTranslationTool-*.jar 檔名" % readme)
            self.assertEqual(expected, found, "%s 的 JAR 檔名與 %s 不一致" % (readme, manifest))

    def test_committed_manifest_is_derivable_from_downloads(self):
        # 上面那條證明「文件照著清單寫」，不證明「清單照著 downloads/ 算」。清單只由
        # prepare-release.yml 產生（workflow_dispatch，一次 10～20 分鐘），而它進入版控之後就沒有
        # 任何東西會重算它：清單與 downloads/ 一旦漂移，四份 README 與網站會一起指著不存在的資產，
        # 真正的比對卻要等合併後的發布 run 才跑。list_installable 只讀檔名（不建置、不解壓）。
        version = mod_version()
        release_dir = ROOT / "downloads" / version
        manifest = release_dir / MANIFEST_NAME
        if not manifest.is_file():
            self.skipTest("downloads/%s 還沒有 %s（下一次 prepare-release 才會產生）" % (version, MANIFEST_NAME))
        self.assertEqual(
            installable_manifest(list_installable(release_dir, version)),
            manifest.read_text(encoding="utf-8"),
            "downloads/%s/%s 與 downloads/ 推導出的可安裝清單不一致" % (version, MANIFEST_NAME),
        )

    def test_manual_assembly_writes_the_installable_manifest(self):
        # `downloads/<version>` 有兩個生產者：prepare-release.yml 寫清單，手動的 `_assemble_*.py` 只寫
        # JAR 與 SHA256SUMS.txt。後者換目錄的方式是「舊目錄改名、staging 換上去、刪備份」，所以沒寫的
        # 檔案不是被覆蓋而是消失——已提交的清單一消失，上面兩條清單守衛就走 skipTest，D12-405 補上的
        # 「文件 ↔ 真實資產」比對整條失靈。這裡釘住產生端（不跑那支腳本：它會刪目錄，且在本機禁令內）。
        text = ASSEMBLE.read_text(encoding="utf-8")
        self.assertIn(
            "from prepare_release_assets import",
            text,
            "_assemble 必須用 prepare_release_assets 的清單實作，不能自己再寫一份名稱規則",
        )
        expected = [line for line in text.splitlines() if "expected = set(names)" in line]
        self.assertEqual(1, len(expected), "_assemble 找不到 'expected = set(names)' 那一行")
        self.assertIn(
            "MANIFEST_NAME",
            expected[0],
            "_assemble 的 expected 沒有 " + MANIFEST_NAME + "：缺清單的佈局不會被擋下",
        )


if __name__ == "__main__":
    unittest.main()
