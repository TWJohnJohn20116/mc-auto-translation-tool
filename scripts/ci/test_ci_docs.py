"""scripts/README.md 必須與它索引的腳本行為一致。

兩句話曾經與程式碼相反，而且方向剛好一正一反：README 仍寫「`java.exe` 加入 Defender 排除名單」
（程式碼早就不做，因為那會關掉整台機器的即時掃描），又寫「`wrote 0 poms` 視為失敗」（程式碼在
POM 全部已存在時回 0，那是最常見的健康狀態）。README 是這批工具唯一的入口文件，所以任一邊被
改動時，這裡要讓它在 PR 上就紅，而不是等維護者照文件去查一個不存在的設定。
"""

import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
README = ROOT / "scripts" / "README.md"
DEFENDER = ROOT / "scripts" / "ci" / "set-defender-exclusions.ps1"
POMS = ROOT / "scripts" / "tools" / "_gen_mojang_poms.py"

DEFENDER_CONTRACT = "java.exe *process* exclusion is deliberately absent"


class ScriptsReadmeTest(unittest.TestCase):
    def test_defender_script_still_omits_the_java_process_exclusion(self):
        self.assertIn(DEFENDER_CONTRACT, DEFENDER.read_text(encoding="utf-8"))

    def test_poms_generator_treats_written_zero_as_success(self):
        lines = POMS.read_text(encoding="utf-8").splitlines()
        start = None
        for index, line in enumerate(lines):
            if line.strip() == "if written == 0:":
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


if __name__ == "__main__":
    unittest.main()
