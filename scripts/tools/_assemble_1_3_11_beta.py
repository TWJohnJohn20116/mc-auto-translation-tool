#!/usr/bin/env python3
"""把重建好的 JAR 收進 ``downloads/<mod_version>`` 並寫出 ``SHA256SUMS.txt``。

這是 ``AGENTS.md`` 描述的「手動版發布流程」，也是 ``.github/workflows/prepare-release.yml``
的參考實作。它原本是 1.3.11-beta.1 時代的產物，內建 33 個目標（其中 ``mc1.16.5-fabric`` 早已
不存在，真正的發布目標是 bundle ``mc1.16.x-fabric``，而 12 個 NeoForge 目標完全缺席），而且
``main()`` 會先無條件把 ``downloads/<mod_version>`` 清空再寫入——照文件跑一次，已提交的 48
目標佈局（含 8 組 ``.jar.part-*`` 分片）就被換成一個自洽但錯誤的 33 目標版本，
``publish-release.yml`` 的 ``-ne 48`` 之後必定失敗，而錯誤訊息完全看不出真正原因。

因此這支腳本現在遵守三條規則：

1. **清單只有一個來源**：目標、``project`` 與 ``jar_dir`` 全部從
   ``.github/workflows/prepare-release.yml`` 的 ``TARGETS_JSON`` 讀出（那是 48 個發布目標的
   權威清單），並用 ``settings.gradle`` 的 ``platformProjects`` 交叉驗證 ``project`` 名稱。
   分片門檻與後綴公式也只有一份：``scripts/release_layout.py``。
2. **不靜默毀資料**：目標目錄若已存在且不是這次產生的，預設拒絕執行（要覆蓋得明確加
   ``--force``）；被覆蓋的內容只能靠 git 還原，所以寧可停下來。
3. **只寫自己要寫的檔名**：先寫進 ``<version>.staging``，逐檔驗證產出的檔名與數量都與權威
   清單相符，成功後才把舊目錄改名為 ``<version>.bak``、再把 staging 換上去。沒有任何
   「先清空目標目錄再寫」的步驟。

AGENTS.md 禁止在本機執行發布資產處理；這支腳本只作為手動流程的參考實作保留。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

# scripts/tools/ 之下，往上兩層才是 repo 根目錄：不再硬編 D:\Code\...
ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = ROOT / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from release_layout import PART_SIZE, part_suffix  # noqa: E402  (需要先設好 sys.path)

#: 48 個發布目標的權威清單（prepare-release.yml 的 TARGETS_JSON）。
PREPARE_WORKFLOW = ROOT / ".github" / "workflows" / "prepare-release.yml"

#: 平台專案的 include 清單，用來交叉驗證 prepare-release.yml 的 project 名稱。
SETTINGS_GRADLE = ROOT / "settings.gradle"

#: 收集順序與 prepare-release.yml 的「用標準檔名收集」一致：
#: 整合包的 release-assets、Forge 1.16.5-1.20.1 的 release、其餘的 libs。
SOURCE_SUBDIRS = ("build/release-assets", "build/release", "build/libs")

#: 建置目錄裡常見但不是發布產物的 JAR。
SKIP_SUFFIXES = ("-dev.jar", "-sources.jar")

PLATFORM_PROJECT = re.compile(r'^\s*"(platform-[^"]+)"\s*:', re.MULTILINE)


def get_version() -> str:
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("mod_version="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("gradle.properties 讀不到 mod_version")


def published_targets() -> list[dict[str, object]]:
    """讀出 prepare-release.yml 裡那份 heredoc JSON（唯一的權威清單）。"""
    try:
        lines = PREPARE_WORKFLOW.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise SystemExit(f"讀不到權威目標清單 {PREPARE_WORKFLOW}: {error}") from error
    for index, line in enumerate(lines):
        if not line.strip().endswith("<<'JSON'"):
            continue
        body: list[str] = []
        for following in lines[index + 1 :]:
            if following.strip() == "JSON":
                try:
                    targets = json.loads("\n".join(body))
                except json.JSONDecodeError as error:
                    raise SystemExit(
                        f"{PREPARE_WORKFLOW} 的 TARGETS_JSON 不是合法 JSON: {error}"
                    ) from error
                if not isinstance(targets, list) or not targets:
                    raise SystemExit(f"{PREPARE_WORKFLOW} 的 TARGETS_JSON 是空的")
                return targets
            body.append(following)
        break
    raise SystemExit(f"{PREPARE_WORKFLOW} 找不到 TARGETS_JSON heredoc")


def settings_projects() -> set[str]:
    try:
        text = SETTINGS_GRADLE.read_text(encoding="utf-8")
    except OSError as error:
        raise SystemExit(f"讀不到 {SETTINGS_GRADLE}: {error}") from error
    return set(PLATFORM_PROJECT.findall(text))


def find_source(target: dict[str, object], version: str) -> Path:
    jar_dir = ROOT / str(target["jar_dir"])
    for subdir in SOURCE_SUBDIRS:
        directory = jar_dir / subdir
        if not directory.is_dir():
            continue
        candidates = sorted(
            path
            for path in directory.glob(f"*-{version}.jar")
            if path.is_file() and not path.name.endswith(SKIP_SUFFIXES)
        )
        if len(candidates) == 1:
            return candidates[0]
        if len(candidates) > 1:
            raise SystemExit(
                f"{directory} 有多個版本為 {version} 的 JAR，無法決定要用哪一個：\n"
                + "\n".join(str(path) for path in candidates)
            )
    raise FileNotFoundError(
        f"{target['target']}: 在 {jar_dir} 的 build/release-assets|release|libs 底下"
        f"找不到版本 {version} 的 JAR"
    )


def split_if_needed(path: Path, data: bytes) -> None:
    if path.name.endswith("-fabric.jar") and len(data) > PART_SIZE:
        for index in range(0, len(data), PART_SIZE):
            suffix = part_suffix(index // PART_SIZE)
            (path.parent / f"{path.name}.part-{suffix}").write_bytes(
                data[index : index + PART_SIZE]
            )
        return
    path.write_bytes(data)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--force",
        action="store_true",
        help="允許覆蓋已存在且不是這次產生的 downloads/<mod_version>",
    )
    args = parser.parse_args(argv)

    version = get_version()
    destination = ROOT / "downloads" / version
    staging = destination.with_name(destination.name + ".staging")
    backup = destination.with_name(destination.name + ".bak")

    targets = published_targets()
    names = [
        f"MCAutoTranslationTool-{version}-{target['target']}.jar" for target in targets
    ]
    if len(set(names)) != len(names):
        duplicates = sorted({name for name in names if names.count(name) > 1})
        raise SystemExit(f"權威清單有重複的目標：{', '.join(duplicates)}")

    projects = {str(target["project"]) for target in targets if target.get("project")}
    unknown_projects = sorted(projects - settings_projects())
    if unknown_projects:
        raise SystemExit(
            "prepare-release.yml 的 project 不在 settings.gradle 的 platformProjects："
            + ", ".join(unknown_projects)
        )

    # 先把所有來源都找齊，任何一個缺就什麼都不動。
    sources: list[tuple[str, Path]] = []
    missing: list[str] = []
    for name, target in zip(names, targets):
        try:
            sources.append((name, find_source(target, version)))
        except FileNotFoundError as error:
            missing.append(str(error))
    if missing:
        print("缺少建置產物，未動 downloads/：", file=sys.stderr)
        print("\n".join(missing), file=sys.stderr)
        return 1

    if destination.is_dir() and any(destination.iterdir()) and not args.force:
        existing = sum(1 for _ in destination.iterdir())
        print(f"拒絕執行：{destination} 已存在且有 {existing} 個檔案，不是這次產生的。", file=sys.stderr)
        print("這支腳本會整個換掉該目錄，被覆蓋的內容只能靠 git 還原。", file=sys.stderr)
        print(
            "確認 mod_version 與 prepare-release.yml 的清單一致後，再加 --force 覆蓋。",
            file=sys.stderr,
        )
        return 1

    # staging 是本腳本獨佔的名字，殘留只會是自己的上一次中斷。
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    lines: list[str] = []
    for name, source in sources:
        data = source.read_bytes()
        split_if_needed(staging / name, data)
        digest = hashlib.sha256(data).hexdigest()
        lines.append(f"{digest}  {name}")
        print(f"{digest[:12]} {name} {len(data)} from {source.relative_to(ROOT)}")
    (staging / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")

    # 產出的檔名必須與權威清單完全相符：少了就是這支腳本的清單又過期了，
    # 多了就是它寫了清單以外的東西。兩種都在這裡停下，destination 還沒被碰過。
    produced = sorted(path.name for path in staging.iterdir())
    expected = set(names) | {"SHA256SUMS.txt"}
    missing_names = sorted(
        name
        for name in expected
        if name not in produced and f"{name}.part-aa" not in produced
    )
    unexpected = sorted(
        name
        for name in produced
        if name not in expected and not any(name.startswith(f"{item}.part-") for item in names)
    )
    if missing_names or unexpected:
        shutil.rmtree(staging)
        print(f"組出的佈局與權威清單不符（應為 {len(names)} 顆），未動 downloads/：", file=sys.stderr)
        if missing_names:
            print("缺少：" + ", ".join(missing_names), file=sys.stderr)
        if unexpected:
            print("多出：" + ", ".join(unexpected), file=sys.stderr)
        return 1

    if backup.exists():
        shutil.rmtree(backup)
    if destination.exists():
        destination.rename(backup)
    staging.rename(destination)
    if backup.exists():
        shutil.rmtree(backup)

    print(
        f"wrote {len(sources)} logical JARs to {destination.relative_to(ROOT)}"
        f"（權威清單：{PREPARE_WORKFLOW.relative_to(ROOT)}）"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
