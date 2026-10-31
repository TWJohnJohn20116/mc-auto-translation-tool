"""build.yml 的靜態 matrix 必須是 scripts/ci/build-targets.json 的投影。

`plan` 用這份 JSON 驗證 `-f targets=...` 的名稱，而 `platform-build`／`legacy-build` 用的是
build.yml 裡的靜態 matrix（AGENTS.md 要求 build.yml 維持靜態 matrix）。兩份清單一旦分叉，
會同時出現「名稱打對卻沒有 job」與「名稱被判成不存在」，而且只有 workflow_dispatch 才看得出來。
這裡把分叉變成 PR 上的紅燈：改 matrix 就一起改 JSON，反之亦然。
"""

import json
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "build.yml"
TARGETS = ROOT / "scripts" / "ci" / "build-targets.json"

JOB_HEADER = re.compile(r"^  ([A-Za-z][A-Za-z0-9_-]*):\s*$")
MATRIX_ENTRY = re.compile(r"^          - target:\s*(\S+)\s*$")
MATRIX_KEY = re.compile(r"^            ([A-Za-z][A-Za-z0-9_-]*):\s*(.*?)\s*$")


def workflow_text():
    return WORKFLOW.read_text(encoding="utf-8")


def targets():
    return json.loads(TARGETS.read_text(encoding="utf-8"))


def job_block(text, job):
    lines = text.splitlines()
    start = None
    for index, line in enumerate(lines):
        header = JOB_HEADER.match(line)
        if header and header.group(1) == job:
            start = index
            break
    if start is None:
        raise AssertionError("build.yml 找不到 job：" + job)
    end = len(lines)
    for index in range(start + 1, len(lines)):
        if JOB_HEADER.match(lines[index]):
            end = index
            break
    return lines[start:end]


def matrix_entries(block):
    entries = []
    in_include = False
    for line in block:
        if line.strip() == "include:":
            in_include = True
            continue
        if not in_include:
            continue
        entry = MATRIX_ENTRY.match(line)
        if entry:
            entries.append({"target": entry.group(1)})
            continue
        key = MATRIX_KEY.match(line)
        if key and entries:
            entries[-1][key.group(1)] = key.group(2).strip('"')
            continue
        if entries and not line.startswith("          "):
            break
    return entries


class BuildTargetsTest(unittest.TestCase):
    def test_platform_matrix_matches_json(self):
        expected = [
            {
                "target": entry["target"],
                "java": entry["java"],
                "bundle": "true" if entry.get("bundle") else "false",
            }
            for entry in targets()["platform"]
        ]
        actual = [
            {
                "target": entry["target"],
                "java": entry.get("java"),
                "bundle": "true" if entry.get("bundle") == "true" else "false",
            }
            for entry in matrix_entries(job_block(workflow_text(), "platform-build"))
        ]
        self.assertEqual(
            expected,
            actual,
            "build.yml 的 platform-build matrix 與 scripts/ci/build-targets.json 不一致",
        )

    def test_legacy_matrix_matches_json(self):
        block = job_block(workflow_text(), "legacy-build")
        self.assertEqual(
            [{"target": entry["target"]} for entry in targets()["legacy"]],
            matrix_entries(block),
            "build.yml 的 legacy-build matrix 與 scripts/ci/build-targets.json 不一致",
        )
        # legacy 目標共用一個 JDK（matrix 裡沒有 java 欄位），值寫在 job 的 setup-java 步驟。
        expected_java = {entry["java"] for entry in targets()["legacy"]}
        self.assertEqual(1, len(expected_java), "legacy 目標共用一個 JDK，JSON 裡的值必須一致")
        self.assertIn('java-version: "%s"' % expected_java.pop(), "\n".join(block))

    def test_plan_resolves_names_from_the_json(self):
        block = "\n".join(job_block(workflow_text(), "plan"))
        self.assertIn(
            "scripts/ci/build-targets.json",
            block,
            "plan 的合法目標清單必須讀 scripts/ci/build-targets.json",
        )
        self.assertNotIn(
            'Path(".github/workflows/build.yml").read_text',
            block,
            "plan 不得再用正則解析 build.yml 自己組目標清單",
        )

    def test_release_assets_gate_on_the_release_directory(self):
        block = "\n".join(job_block(workflow_text(), "release-assets"))
        # verifyReleaseJars／prepareReleaseAssets 需要 downloads/<mod_version>，用觸發事件近似
        # 「資產存在」會讓升版後的第一次 push main 必然紅燈（publish-release 對同一狀態是跳過）。
        self.assertIn("needs.plan.outputs.release_verify == 'true'", block)
        self.assertNotIn(
            "needs.plan.outputs.targets == '' && github.event_name != 'pull_request'",
            block,
            "release-assets 不得再用「非 PR 就驗」近似資產存在",
        )


if __name__ == "__main__":
    unittest.main()
