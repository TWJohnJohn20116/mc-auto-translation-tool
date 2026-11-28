"""守住 PR-Agent → CodeRabbit 遷移的版本控管契約。

這些測試只讀本機檔案；GitHub App 與 main 的分支保護不在 checkout 內。
"""

import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
CONFIG = ROOT / ".coderabbit.yaml"
WORKFLOWS = ROOT / ".github" / "workflows"
RETIRED_FILES = (
    ".github/workflows/ai-review.yml",
    ".pr_agent.toml",
    ".github/pr-agent/zh-tw.toml",
    "scripts/ci/apply-pr-agent-zh-tw.sh",
)


def config_entry(lines, key):
    """取單一同層的設定及其子區塊；沿用 CI 不安裝 YAML 套件的做法。"""
    lines = [line for line in lines if line.strip() and not line.lstrip().startswith("#")]
    indent = min((len(line) - len(line.lstrip()) for line in lines), default=0)
    prefix = " " * indent + key + ":"
    matches = [index for index, line in enumerate(lines) if line.startswith(prefix)]
    if len(matches) != 1:
        raise AssertionError(f".coderabbit.yaml: {key} 應出現一次，實際 {len(matches)} 次")
    start = matches[0]
    end = next(
        (index for index in range(start + 1, len(lines)) if not lines[index].startswith(" " * (indent + 1))),
        len(lines),
    )
    value = re.split(r"\s+#", lines[start][len(prefix):], maxsplit=1)[0].strip()
    return value, lines[start + 1:end]


def config_list(lines):
    """讀取設定使用的逐行純文字清單，支援加引號的 glob。"""
    items = []
    for line in lines:
        match = re.fullmatch(r"""\s*-\s+(?:"([^"]+)"|'([^']+)'|([^\s#!][^\s#]*))\s*(?:#.*)?""", line)
        if not match:
            raise AssertionError(f".coderabbit.yaml: 無法讀取清單項目：{line}")
        items.append(next(value for value in match.groups() if value is not None))
    return items


class AiReviewMigrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = CONFIG.read_text(encoding="utf-8").splitlines()
        _, cls.reviews = config_entry(cls.config, "reviews")

    def test_reviews_use_traditional_chinese(self):
        language, _ = config_entry(self.config, "language")
        self.assertEqual("zh-TW", language.strip("\"'"))

    def test_automatic_reviews_include_updates_to_non_draft_main_prs(self):
        _, auto_review = config_entry(self.reviews, "auto_review")
        for key, expected in (
            ("enabled", "true"),
            ("auto_incremental_review", "true"),
            ("drafts", "false"),
        ):
            with self.subTest(key=key):
                self.assertEqual(expected, config_entry(auto_review, key)[0])

        _, branches = config_entry(auto_review, "base_branches")
        # base_branches 是正規表示式：未錨定的 main 也會匹配 release/main。
        self.assertEqual(["^main$"], config_list(branches))

    def test_reviews_request_changes_until_resolved(self):
        # AGENTS.md 的硬規則：CodeRabbit 意見未解決時不得合併。
        self.assertEqual("true", config_entry(self.reviews, "request_changes_workflow")[0])

    def test_generated_paths_are_excluded_without_a_source_allowlist(self):
        _, filters = config_entry(self.reviews, "path_filters")
        rules = config_list(filters)
        self.assertTrue(all(rule.startswith("!") for rule in rules), rules)
        self.assertTrue(
            {
                "!downloads/**",
                "!**/build/**",
                "!**/.gradle/**",
                "!website/pnpm-lock.yaml",
                "!**/*.jar",
                "!**/*.part-*",
            }.issubset(rules),
            f"審查排除清單缺少產物規則：{rules}",
        )

    def test_workflow_shell_and_yaml_checks_are_enabled(self):
        _, tools = config_entry(self.reviews, "tools")
        for name in ("actionlint", "shellcheck", "yamllint"):
            with self.subTest(tool=name):
                _, tool = config_entry(tools, name)
                self.assertEqual("true", config_entry(tool, "enabled")[0])

    def test_review_chat_replies_are_enabled(self):
        _, chat = config_entry(self.config, "chat")
        self.assertEqual("true", config_entry(chat, "auto_reply")[0])

    def test_legacy_pr_agent_assets_are_gone(self):
        for relative_path in RETIRED_FILES:
            with self.subTest(path=relative_path):
                path = ROOT / relative_path
                self.assertFalse(path.exists() or path.is_symlink(), relative_path)

    def test_no_workflow_reintroduces_the_legacy_merge_gate(self):
        for path in sorted(WORKFLOWS.iterdir()):
            if path.suffix not in {".yaml", ".yml"}:
                continue
            with self.subTest(workflow=path.name):
                for line in path.read_text(encoding="utf-8").splitlines():
                    if line.lstrip().startswith("#"):
                        continue
                    self.assertNotRegex(
                        line,
                        r"^\s*name:\s*[\"']?開 PR 時自動審查[\"']?(?:\s*#.*)?\s*$",
                    )
                    self.assertNotIn("ai-review/pass", line)
                    for retired_file in RETIRED_FILES:
                        self.assertNotIn(retired_file, line)


if __name__ == "__main__":
    unittest.main()
