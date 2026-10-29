#!/usr/bin/env bash
# 建置摘要（繁中）的唯一一份實作。
#
# 這段 bash 原本在 build.yml 的 4 個 job 與 legacy-targets.yml 的 3 個 job 各複製一份，
# 只有 TARGET／OUTCOME／ARTIFACT 三個 env 不同，於是任何一次格式調整都要同步 7 處，
# 漏改就會在同一個 run 裡出現兩種表格（AGENTS.md 的「修改 CI 的規則」把這件事寫成了規則，
# 但差異其實只在參數，本文可以共用）。
#
# 由 job 以 env 傳入：
#   TARGET   要顯示的目標名稱（matrix.target 或固定字串）
#   OUTCOME  對應建置步驟的 outcome（success／failure／skipped，未執行時為空）
#   ARTIFACT artifact 名稱（沒有就留空）
#   GRADLE_WRAPPER_PROPERTIES  選填，預設 gradle/wrapper/gradle-wrapper.properties
#
# 這個步驟在每個 job 都掛 `if: always()` 且不得讓 job 變紅：因此刻意不開 errexit，
# 取值一律容錯（缺檔、缺 env 都只顯示「未知」）。
set -uo pipefail

target="${TARGET:-}"
outcome="${OUTCOME:-}"
artifact="${ARTIFACT:-}"
properties="${GRADLE_WRAPPER_PROPERTIES:-gradle/wrapper/gradle-wrapper.properties}"

case "$outcome" in
  success) result="成功" ;;
  failure) result="失敗" ;;
  skipped) result="略過（本次未要求）" ;;
  *) result="未知（${outcome:-未執行}）" ;;
esac

version=""
if [[ -f "$properties" ]]; then
  version="$(sed -n 's|.*gradle-\([0-9][0-9.]*\)-.*|\1|p' "$properties" | head -n 1 || true)"
fi

{
  echo "## 建置摘要"
  echo
  echo "| 項目 | 內容 |"
  echo "| --- | --- |"
  echo "| 目標 | ${target:-未指定} |"
  echo "| Gradle 版本 | ${version:-未知} |"
  echo "| 結果 | ${result} |"
  echo "| artifact | ${artifact:-無} |"
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
