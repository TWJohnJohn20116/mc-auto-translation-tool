"""Gradle 快取／鏡像目錄的定位，供 ``scripts/tools`` 的快取種子腳本共用。

這幾支工具原本各自硬編 ``D:\\Gradle\\cache``，等於把「快取在 D 槽」寫成前提：換一台機器、
或改用預設的 ``~/.gradle`` 就整支失效。這裡集中成一份，優先序是
``GRADLE_USER_HOME`` → ``~/.gradle``（Gradle 自己的規則）。

這些腳本不進 CI（AGENTS.md 也禁止在本機執行發布資產處理），純粹是離線開發的輔助工具。
"""

from __future__ import annotations

import os
import sys
from pathlib import Path


def gradle_user_home() -> Path:
    """Gradle 的根快取目錄：先看 ``GRADLE_USER_HOME``，否則 ``~/.gradle``。"""
    value = os.environ.get("GRADLE_USER_HOME")
    if value:
        return Path(value).expanduser()
    return Path.home() / ".gradle"


def forge_mavenizer_caches() -> Path:
    """ForgeGradle／Minecraft Mavenizer 的 ``caches`` 目錄。"""
    return gradle_user_home() / "caches" / "minecraftforge" / "forgegradle" / "mavenizer" / "caches"


def forge_mojang_maven() -> Path:
    """Mavenizer 快取裡的 Mojang Maven 樹（POM 補齊腳本的 ``rglob`` 起點）。"""
    return forge_mavenizer_caches() / "maven" / "mojang"


def mirror_dir() -> Path:
    """本機 Minecraft meta／library 鏡像（種子腳本寫、Loom 讀）。"""
    return gradle_user_home() / "mc-mirror"


def minecraft_libraries() -> Path | None:
    """使用者 ``.minecraft/libraries``；無法可靠定位時回傳 ``None``。

    原本的寫法是 ``os.environ.get("APPDATA", "")``，``APPDATA`` 缺失時會退化成相對路徑
    ``.minecraft/libraries``，於是下載的 library 被寫進**當前工作目錄**。寧可明確回傳
    ``None`` 讓呼叫端跳過，也不要靜默寫到錯誤的位置。
    """
    if sys.platform == "win32":
        appdata = os.environ.get("APPDATA")
        if not appdata:
            return None
        return Path(appdata) / ".minecraft" / "libraries"
    return Path.home() / ".minecraft" / "libraries"
