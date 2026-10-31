"""derive-prepare-targets.py 的行為測試。

prepare-release.yml 的 `plan` 用這支腳本把發布資產名稱（`mc1.21.7-forge`）翻譯成
`-PtargetPlatform` 吃的平台名稱（`forge-1.21.7`）；推錯會在 settings 評估階段以看不懂的
"Unknown targetPlatform" 失敗，而且那一次 dispatch 已經很貴。腳本檔名帶連字號、不能直接 import，
所以用 importlib 從路徑載入。stdin 那條路徑（沒有 argv[1] 時）也一併測，避免只有一種呼叫方式被證過。
"""

import importlib.util
import io
import json
import pathlib
import sys
import unittest
from contextlib import redirect_stdout

SCRIPT = pathlib.Path(__file__).resolve().with_name("derive-prepare-targets.py")


def load_module():
    spec = importlib.util.spec_from_file_location("derive_prepare_targets", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class DerivePrepareTargetsTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()

    def derive(self, entries):
        original = sys.argv
        sys.argv = ["derive-prepare-targets.py", json.dumps(entries)]
        try:
            out = io.StringIO()
            with redirect_stdout(out):
                self.assertEqual(0, self.module.main())
            return json.loads(out.getvalue())
        finally:
            sys.argv = original

    def test_standalone_targets_get_empty_platform_and_project(self):
        derived = self.derive([{"target": "mc1.8.9-forge", "standalone": True}])
        self.assertEqual("", derived[0]["platform"])
        self.assertEqual("", derived[0]["project"])

    def test_forge_target_derives_the_platform_name(self):
        derived = self.derive(
            [{"target": "mc1.21-forge", "project": "platform-forge-1.21"}]
        )
        self.assertEqual("forge-1.21", derived[0]["platform"])

    def test_fabric_bundle_target_keeps_the_x_suffix(self):
        derived = self.derive(
            [{"target": "mc1.13.x-fabric", "project": "platform-fabric-1.13.x"}]
        )
        self.assertEqual("fabric-1.13.x", derived[0]["platform"])

    def test_malformed_target_name_exits(self):
        original = sys.argv
        sys.argv = ["derive-prepare-targets.py", json.dumps([{"target": "fabric-1.13.x"}])]
        try:
            with redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit):
                    self.module.main()
        finally:
            sys.argv = original

    def test_reads_the_target_json_from_stdin_without_an_argument(self):
        original_argv = sys.argv
        original_stdin = sys.stdin
        sys.argv = ["derive-prepare-targets.py"]
        sys.stdin = io.StringIO(json.dumps([{"target": "mc26.2-neoforge"}]))
        try:
            out = io.StringIO()
            with redirect_stdout(out):
                self.assertEqual(0, self.module.main())
            self.assertEqual("neoforge-26.2", json.loads(out.getvalue())[0]["platform"])
        finally:
            sys.argv = original_argv
            sys.stdin = original_stdin


if __name__ == "__main__":
    unittest.main()
