"""Layout of the files under ``downloads/<version>``, shared by producer and verifier.

The split-JAR convention used to live in four places: the ``prepare-release.yml`` inline python and
``scripts/tools/_assemble_1_3_11_beta.py`` held the 614400-byte threshold, and
``prepare_release_assets.py`` plus ``verify_release_jars.py`` each carried their own copy of the
two-letter suffix formula. ``verify_release_jars.py`` only ever checked the *names* of the parts, so
the "600 KiB" half of the contract — the size written down in ``AGENTS.md`` — was unguarded: a typo
in the threshold produced hundreds of parts that still passed every check.

Keep this module dependency-free; it is imported by scripts that run from ``scripts/`` directly and
by the workflow's inline python.
"""

from __future__ import annotations

import re

#: Largest size of one split part, in bytes (600 KiB). See AGENTS.md.
PART_SIZE = 614400

#: ``<name>.jar.part-aa`` .. ``<name>.jar.part-zz``, 676 parts maximum.
PART_PATTERN = re.compile(r"^(?P<jar>.+\.jar)\.part-(?P<suffix>[a-z]{2})$")

#: Literal copy of the ``mod_version`` whitelist in ``scripts/ci/read-mod-version.sh``. A value that
#: passes there is spliced into ``find -name`` patterns and ``rm -rf`` targets, so the local producer
#: (``scripts/tools/_assemble_1_3_11_beta.py``) has to reject the same values: it used to accept
#: anything after ``mod_version=`` as long as it stripped clean. ``scripts/ci/test_ci_docs.py``
#: compares this string with the shell script so the two cannot drift.
VERSION_PATTERN = re.compile(r"^[0-9]+([.][0-9]+)*([_-][0-9A-Za-z.-]+)?$")


def part_suffix(index: int) -> str:
    """The suffix of part ``index`` (0 -> ``aa``, 25 -> ``az``, 26 -> ``ba``)."""
    return chr(ord("a") + index // 26) + chr(ord("a") + index % 26)
