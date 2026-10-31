#!/usr/bin/env bash
# 讀取並驗證 gradle.properties 的 mod_version，驗證過的值輸出到 stdout。
#
# publish-release.yml 早就有一條字元白名單，prepare-release.yml 卻只檢查「非空」。於是含空白、
# `*`、`[` 或 CRLF 的 mod_version 會被拼進 `find -name` 與 `rm -rf`：`VERSION="1.4\r"` 時
# `! -name "$VERSION"` 對真正的 `downloads/1.4` 不匹配，當前版本的目錄反而被刪掉。兩支 workflow
# 共用這一份，白名單就只有一個地方可以漂。
set -euo pipefail

# 這條白名單的字面規則與 scripts/release_layout.py 的 VERSION_PATTERN 必須逐字相同：本機的
# scripts/tools/_assemble_1_3_11_beta.py 用那一份驗同一個值（bash 讀不到 python 常數，所以只能
# 兩份互相比對，scripts/ci/test_ci_docs.py 會斷言兩者一致）。
version="$(sed -n 's/^mod_version=//p' gradle.properties | head -n 1)"
if [[ -z "$version" || ! "$version" =~ ^[0-9]+([.][0-9]+)*([_-][0-9A-Za-z.-]+)?$ ]]; then
  echo "::error::gradle.properties 的 mod_version 是空值或含不允許的字元：'${version}'" >&2
  exit 1
fi
printf '%s\n' "$version"
