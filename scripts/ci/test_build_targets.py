"""build.yml 的靜態 matrix 必須是 scripts/ci/build-targets.json 的投影。

`plan` 用這份 JSON 驗證 `-f targets=...` 的名稱，而 `platform-build`／`legacy-build` 用的是
build.yml 裡的靜態 matrix（AGENTS.md 要求 build.yml 維持靜態 matrix）。兩份清單一旦分叉，
會同時出現「名稱打對卻沒有 job」與「名稱被判成不存在」，而且只有 workflow_dispatch 才看得出來。
這裡把分叉變成 PR 上的紅燈：改 matrix 就一起改 JSON，反之亦然。

另外兩件事也釘在這裡，因為 `scripts/ci/build-targets.json` 是唯一被文件指名的權威清單：
`legacy-targets.yml` 與 `prepare-release.yml` 那兩份 heredoc JSON 的「目標 → JDK」必須與
`build.gradle` 的 26.x toolchain 前綴一致，否則清單與 matrix 一起寫錯時仍會全綠。
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
TARGET_ENTRY = re.compile(r'"target":\s*"([^"]+)"')

PREPARE_WORKFLOW = ROOT / ".github" / "workflows" / "prepare-release.yml"
LEGACY_WORKFLOW = ROOT / ".github" / "workflows" / "legacy-targets.yml"
SETTINGS = ROOT / "settings.gradle"

# build.gradle 的 subprojects 區塊只對這三個前綴給 Java 25 toolchain，其餘一律 21。三個字串在那裡
# 是逐字寫的，而「哪個目標用哪個 JDK」在四份清單（build.yml matrix、build-targets.json、
# legacy-targets.yml、prepare-release.yml）各講一次：兩份都錯成 21 時既有的 JSON↔matrix 比對仍然
# 全綠，實際建置卻在 setup-java 裝了 21 之後才以 "release version 25 not supported" 失敗
# （AGENTS.md 記載的 2026-10-06 型態）。改 build.gradle:13-15 的前綴時，這裡要跟著改。
TOOLCHAIN_25_PREFIXES = ("platform-fabric-26.", "platform-forge-26.", "platform-neoforge-26.")


def expected_java(project):
    """build.gradle:13-15 的 toolchain 規則：26.x 的三個前綴要 25，其餘 21。"""
    return "25" if project.startswith(TOOLCHAIN_25_PREFIXES) else "21"


def target_entries(path, with_project=False):
    """從 workflow 的 run: 區塊抓出每個目標的 target／java（必要時含 project）。

    不用 YAML 解析器：清單嵌在 heredoc 裡、每筆是一個單行物件，逐行比對就夠，而筆數由呼叫端斷言
    ——形狀被改掉時會變成抓不到，若不鎖筆數就會靜默通過。
    """
    entries = []
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped.startswith('{"target"'):
            continue
        entry = {"target": TARGET_ENTRY.search(stripped).group(1)}
        java = re.search(r'"java":\s*"(\d+)"', stripped)
        entry["java"] = java.group(1) if java else None
        if with_project:
            project = re.search(r'"project":\s*"([^"]*)"', stripped)
            entry["project"] = project.group(1) if project else None
        entries.append(entry)
    return entries


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

    def test_json_groups_are_exactly_the_ones_with_a_matrix(self):
        # `plan` 的「合法名稱」是所有群組的聯集，而「會不會真的建置」由 build.yml 那兩份靜態 matrix
        # 決定。兩者之間先前沒有任何斷言：新增第三個群組（或把 legacy 改名）時，plan 會接受那個目標、
        # 23 個 job 的建置步驟全部被 if 略過、每個 job 仍回報 success，於是 dispatch 得到一次
        # 「零建置的全綠執行」（AGENTS.md 記載的 2026-10-21 事件）。
        groups = targets()
        self.assertEqual(
            {"platform", "legacy"},
            set(groups),
            "build-targets.json 多了一個沒有 matrix 的群組：dispatch 會以假綠燈通過",
        )
        listed = sorted(
            entry["target"] for entries in groups.values() for entry in entries
        )
        declared = sorted(
            entry["target"]
            for job in ("platform-build", "legacy-build")
            for entry in matrix_entries(job_block(workflow_text(), job))
        )
        self.assertEqual(
            listed,
            declared,
            "build-targets.json 有 build.yml 的靜態 matrix 沒有建置的目標（dispatch 會靜默通過）",
        )

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

    def test_platform_java_matches_the_toolchain_prefixes(self):
        # 這一條與上面的 JSON↔matrix 比對互補：那條只證明兩份清單相同，兩份一起寫錯時仍全綠。
        for entry in targets()["platform"]:
            self.assertEqual(
                expected_java("platform-" + entry["target"]),
                entry["java"],
                "scripts/ci/build-targets.json 的 %s 與 build.gradle 的 toolchain 前綴不一致"
                % entry["target"],
            )

    def test_legacy_java_matches_the_toolchain_prefixes(self):
        for entry in targets()["legacy"]:
            self.assertEqual(
                expected_java("platform-" + entry["target"]),
                entry["java"],
                "scripts/ci/build-targets.json 的 legacy 目標 %s 與 build.gradle 的 toolchain 前綴不一致"
                % entry["target"],
            )

    def test_prepare_release_java_matches_the_toolchain_prefixes(self):
        entries = target_entries(PREPARE_WORKFLOW, with_project=True)
        # 48 是 prepare-release 的目標數，也是 publish-release 的 expected 陣列長度；抓不到就代表
        # 清單形狀變了，不能讓下面的斷言在空集合上通過。
        self.assertEqual(48, len(entries), "prepare-release.yml 的目標清單筆數變了")
        self.assertTrue(
            all(entry["java"] for entry in entries),
            "prepare-release.yml 的每一筆目標都必須帶 java",
        )
        # 逐筆比對，不是「哪些是 25」。先前只比對 25 那一半：`java: "17"` 的
        # platform-forge-1.16.5／1.18.2／1.19.2／1.20.1 既不在左邊也不在右邊，於是 toolchain 是 21
        # 而 setup-java 裝 17 這件事永遠不受約束（docstring 卻宣稱「必須與 toolchain 前綴一致」）。
        # 兩個獨立 Gradle 建置（project == ""）不套用根 subprojects 的 toolchain，JDK 8 由各自的
        # setup-java 決定，所以排除。
        for entry in entries:
            if entry["project"] == "":
                continue
            self.assertEqual(
                expected_java(entry["project"]),
                entry["java"],
                "prepare-release.yml 的 %s 與 build.gradle 的 toolchain 前綴不一致"
                % entry["target"],
            )
        # 這兩個是獨立 Gradle 建置（platforms/forge/legacy/*），JDK 8 寫在各自的 setup-java。
        self.assertEqual(
            ["mc1.12.2-forge", "mc1.8.9-forge"],
            sorted(entry["target"] for entry in entries if entry["project"] == ""),
            "prepare-release.yml 只有獨立 Forge 舊版目標可以沒有 project",
        )

    def test_legacy_targets_java_matches_the_toolchain_prefixes(self):
        entries = target_entries(LEGACY_WORKFLOW)
        self.assertEqual(21, len(entries), "legacy-targets.yml 的目標清單筆數變了")
        self.assertEqual(
            ["forge-legacy-1.12.2", "forge-legacy-1.8.9"],
            sorted(entry["target"] for entry in entries if entry["java"] is None),
            "legacy-targets.yml 只有兩個獨立 Forge 舊版目標不帶 java",
        )
        for entry in entries:
            if entry["java"] is None:
                continue
            self.assertEqual(
                expected_java("platform-" + entry["target"]),
                entry["java"],
                "legacy-targets.yml 的 %s 與 build.gradle 的 toolchain 前綴不一致" % entry["target"],
            )

    def test_release_bundle_calls_match_the_settings_table(self):
        # release-assets 的 -PtargetPlatform 與 8 個 verifyPreparedReleaseBundle 是 settings.gradle
        # fabricReleaseBundles 的第三份複本，而且完全手抄：漏抄一個名字那個 bundle 就沒有被 include，
        # 漏抄一個任務則是「任務存在、沒有呼叫點」（A12-002 的形狀：17 個已發布版本沒有任何覆蓋）。
        # 這條不跑 Gradle，只比對兩個文字檔，所以「加一個 bundle 只改 settings.gradle」在 lint 就紅。
        settings = SETTINGS.read_text(encoding="utf-8")
        declared = re.findall(
            r'"(platform-fabric-[^"]+)"',
            settings.split("fabricReleaseBundles = [", 1)[1].split("]", 1)[0],
        )
        self.assertTrue(declared, "settings.gradle 找不到 fabricReleaseBundles 的成員")
        block = "\n".join(job_block(workflow_text(), "release-assets"))
        selected = re.search(r"-PtargetPlatform=(\S+)", block)
        self.assertIsNotNone(selected, "release-assets 沒有 -PtargetPlatform")
        self.assertEqual(
            [name[len("platform-"):] for name in declared],
            selected.group(1).split(","),
            "build.yml 的 release-assets 呼叫面與 settings.gradle 的 fabricReleaseBundles 不一致",
        )
        for name in declared:
            self.assertIn(
                ":%s:verifyPreparedReleaseBundle" % name,
                block,
                "%s 沒有 verifyPreparedReleaseBundle 的呼叫點，那個 bundle 不會被驗證" % name,
            )


if __name__ == "__main__":
    unittest.main()
