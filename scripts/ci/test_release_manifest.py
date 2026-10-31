"""`release-assets.txt` 必須由 prepare_release_assets.py 真的產生出來。

網站測試把 `downloads/<版本>/release-assets.txt` 當成 31 個可安裝檔名的權威清單，但那份檔案一度只
存在於測試註解與 `website/README.md`：腳本從沒寫過它，於是那條比對永遠走「檔案不存在就略過」的
分支，對發布清單是 0 約束（文件、`page.tsx`、`downloads/` 三者可以各自漂移而 CI 全綠）。
這支測試把產生端釘住——格式與排序由一個函式決定，而且 prepare_release 把它列進回傳值。
"""

import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "prepare_release_assets.py"

sys.path.insert(0, str(ROOT / "scripts"))

from prepare_release_assets import MANIFEST_NAME, installable_manifest  # noqa: E402


class ReleaseManifestTest(unittest.TestCase):
    def test_manifest_is_sorted_one_name_per_line(self):
        # 排序是契約的一部分：網站測試把清單與檔案同樣排序後才比對，兩邊的排序方式一旦不同，紅燈
        # 會指向「檔名不一致」這種完全錯誤的方向。
        self.assertEqual("a.jar\nb.jar\n", installable_manifest(["b.jar", "a.jar"]))
        self.assertEqual("", installable_manifest([]))

    def test_manifest_name_matches_the_published_contract(self):
        self.assertEqual("release-assets.txt", MANIFEST_NAME)

    def test_prepare_release_writes_and_returns_the_manifest(self):
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertIn(
            "output_dir / MANIFEST_NAME",
            text,
            "清單必須寫進 output_dir，且檔名只有 MANIFEST_NAME 一份",
        )
        self.assertIn(
            "installable_manifest(",
            text,
            "清單內容必須由 installable_manifest 產生，否則格式沒有單一決策點",
        )
        self.assertIn(
            "return [*assets, checksum, manifest]",
            text,
            "清單檔必須列進回傳值，「它存在」才有東西在管",
        )


if __name__ == "__main__":
    unittest.main()
