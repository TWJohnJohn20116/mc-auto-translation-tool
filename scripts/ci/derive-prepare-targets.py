#!/usr/bin/env python3
"""Adds `platform`/`project` to every prepare-release target, for -PtargetPlatform.

`-PtargetPlatform` takes the platform name (forge-1.21.7, fabric-1.13.x), not the release-asset
name (mc1.21.7-forge), so the target list has to be translated before Gradle is invoked; getting it
wrong fails at settings-evaluation time with the opaque "Unknown targetPlatform".

The standalone targets (the two Forge legacy builds) have no platform/project pair; they only ran
because `matrix.standalone && ...` short-circuited to an empty string, so they are given explicit
empty values here instead of relying on that.

Reads the target JSON on argv[1] (or stdin) and writes the derived JSON to stdout.
"""

import json
import re
import sys

TARGET = re.compile(r"mc(.+)-(forge|neoforge|fabric)\Z")


def main() -> int:
    raw = sys.argv[1] if len(sys.argv) > 1 else sys.stdin.read()
    targets = json.loads(raw)
    for entry in targets:
        if entry.get("standalone"):
            entry.setdefault("platform", "")
            entry.setdefault("project", "")
            continue
        name = entry["target"]
        match = TARGET.match(name)
        if match is None:
            raise SystemExit("目標名稱不符 mc<game>-<loader>：" + name)
        game, loader = match.groups()
        entry["platform"] = f"{loader}-{game}"
    json.dump(targets, sys.stdout)
    return 0


if __name__ == "__main__":
    sys.exit(main())
