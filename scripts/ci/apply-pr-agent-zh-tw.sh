#!/usr/bin/env bash
# 把 PR-Agent 的固定文字換成繁體中文。
#
# PR-Agent 0.50.0 只內建 en／es 兩份語系表（pr_agent/settings/i18n/），固定標籤
# （預估審查工作量、沒有相關測試、未發現安全性疑慮、建議優先審查的範圍…）沒有
# 語系表就不會翻，所以把版控裡的 zh-tw.toml 複製進套件目錄。缺鍵或 {placeholder}
# 對不上時 PR-Agent 會自行回退英文，不會讓留言壞掉。
#
# 模型產生的文字由 .pr_agent.toml 的 config.response_language 控制；留言標題則由
# pr_reviewer.review_heading 與 pr_code_suggestions.suggestions_heading 控制
# （pr_agent/algo/comment_identity.py 的 format_*_header 會讀這兩個鍵，
#  PRReviewHeader 那個列舉只是留言身分標記，不是可見標題）。
#
# 這支腳本只留一份：ai-review.yml 的三個 job 都呼叫它。AGENTS.md 記錄過
# 「七個 job 各貼一份摘要腳本、改一處漏六處」的事故，不要在 workflow 內重複貼。
set -euo pipefail

settings_dir="$(python -c 'import os, pr_agent; print(os.path.join(os.path.dirname(pr_agent.__file__), "settings"))')"

if [ ! -f .github/pr-agent/zh-tw.toml ]; then
  echo "::warning::找不到 .github/pr-agent/zh-tw.toml，這次留言的固定文字維持英文"
  exit 0
fi

cp .github/pr-agent/zh-tw.toml "$settings_dir/i18n/zh-tw.toml"
echo "已套用 $settings_dir/i18n/zh-tw.toml"

# 光複製還不夠：檔名要能命中 locale_chain('zh-TW')，否則固定文字會靜默維持英文。
python - <<'PY'
import sys

from pr_agent.algo.i18n import locale_chain

chain = locale_chain("zh-TW")
print("locale_chain('zh-TW') =", chain)
if "zh-tw" not in chain:
    print(
        "::error::PR-Agent 認不到 zh-tw 語系表，固定文字會維持英文；"
        "請確認檔名與 locale_chain 的候選一致",
        file=sys.stderr,
    )
    raise SystemExit(1)
PY
