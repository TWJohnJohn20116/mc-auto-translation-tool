"""scripts/README.md 必須與它索引的腳本行為一致。

兩句話曾經與程式碼相反，而且方向剛好一正一反：README 仍寫「`java.exe` 加入 Defender 排除名單」
（程式碼早就不做，因為那會關掉整台機器的即時掃描），又寫「`wrote 0 poms` 視為失敗」（程式碼在
POM 全部已存在時回 0，那是最常見的健康狀態）。README 是這批工具唯一的入口文件，所以任一邊被
改動時，這裡要讓它在 PR 上就紅，而不是等維護者照文件去查一個不存在的設定。
"""

import pathlib
import re
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
README = ROOT / "scripts" / "README.md"
DEFENDER = ROOT / "scripts" / "ci" / "set-defender-exclusions.ps1"
POMS = ROOT / "scripts" / "tools" / "_gen_mojang_poms.py"
VERSION_SCRIPT = ROOT / "scripts" / "ci" / "read-mod-version.sh"

sys.path.insert(0, str(ROOT / "scripts"))

from release_layout import VERSION_PATTERN  # noqa: E402  (需要先設好 sys.path)

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


if __name__ == "__main__":
    unittest.main()
