"""跨平台 Chromium 浏览器内核探测与复用。

桌面版优先复用用户电脑已安装的 Microsoft Edge / Google Chrome，避免把 180MB+
的 Chromium 打进安装包；Linux / Docker 容器优先使用镜像预装的 Playwright Chromium。
"""

from __future__ import annotations

import os
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

BrowserRuntimeKind = Literal[
    "custom",
    "msedge",
    "chrome",
    "playwright-chromium",
    "missing",
]


@dataclass(frozen=True)
class BrowserRuntimeInfo:
    ready: bool
    kind: BrowserRuntimeKind
    label: str
    executable_path: str | None = None


def _system_candidates(platform_name: str) -> list[tuple[BrowserRuntimeKind, str, Path]]:
    candidates: list[tuple[BrowserRuntimeKind, str, Path]] = []
    if platform_name == "win32":
        program_files_x86 = os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")
        program_files = os.environ.get("ProgramFiles", r"C:\Program Files")
        local_app_data = os.environ.get("LOCALAPPDATA", "")
        raw = [
            ("msedge", "Microsoft Edge", Path(program_files_x86) / "Microsoft/Edge/Application/msedge.exe"),
            ("msedge", "Microsoft Edge", Path(program_files) / "Microsoft/Edge/Application/msedge.exe"),
            ("chrome", "Google Chrome", Path(program_files) / "Google/Chrome/Application/chrome.exe"),
            ("chrome", "Google Chrome", Path(program_files_x86) / "Google/Chrome/Application/chrome.exe"),
        ]
        if local_app_data:
            raw.append(
                ("chrome", "Google Chrome", Path(local_app_data) / "Google/Chrome/Application/chrome.exe")
            )
            raw.append(
                ("msedge", "Microsoft Edge", Path(local_app_data) / "Microsoft/Edge/Application/msedge.exe")
            )
        for kind, label, path in raw:
            candidates.append((kind, label, path))  # type: ignore[arg-type]
    elif platform_name == "darwin":
        candidates.extend(
            [
                ("msedge", "Microsoft Edge", Path("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge")),
                ("chrome", "Google Chrome", Path("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")),
            ]
        )
    else:
        for cmd, kind, label in (
            ("google-chrome", "chrome", "Google Chrome"),
            ("google-chrome-stable", "chrome", "Google Chrome"),
            ("chromium", "chrome", "Chromium"),
            ("chromium-browser", "chrome", "Chromium"),
            ("microsoft-edge", "msedge", "Microsoft Edge"),
        ):
            found = shutil.which(cmd)
            if found:
                candidates.append((kind, label, Path(found)))  # type: ignore[arg-type]
    return candidates


def _playwright_chromium_path(platform_name: str) -> Path | None:
    """在不触发 Playwright 进程启动的前提下，只读探测已下载的 Playwright Chromium。"""
    roots: list[Path] = []
    env_path = os.environ.get("PLAYWRIGHT_BROWSERS_PATH")
    if env_path and env_path != "0":
        roots.append(Path(env_path))
    if platform_name == "win32":
        local_app_data = os.environ.get("LOCALAPPDATA")
        if local_app_data:
            roots.append(Path(local_app_data) / "ms-playwright")
    elif platform_name == "darwin":
        roots.append(Path.home() / "Library" / "Caches" / "ms-playwright")
    else:
        roots.append(Path.home() / ".cache" / "ms-playwright")
        roots.append(Path("/ms-playwright"))

    rel_bins = (
        ("chrome-win64/chrome.exe", "chrome-win/chrome.exe")
        if platform_name == "win32"
        else (
            ("chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing",
             "chrome-mac/Chromium.app/Contents/MacOS/Chromium")
            if platform_name == "darwin"
            else ("chrome-linux/chrome", "chrome-linux64/chrome")
        )
    )
    for root in roots:
        if not root.is_dir():
            continue
        try:
            chromium_dirs = sorted(
                (p for p in root.iterdir() if p.is_dir() and p.name.startswith("chromium-")),
                reverse=True,
            )
        except OSError:
            continue
        for cdir in chromium_dirs:
            for rel in rel_bins:
                candidate = cdir / rel
                if candidate.is_file():
                    return candidate
    return None


def detect_browser_runtime(
    *,
    explicit_executable: str | None = None,
    platform_name: str | None = None,
) -> BrowserRuntimeInfo:
    """探测本机可用的 Chromium 系浏览器（只读、零副作用）。"""
    plat = platform_name or sys.platform

    if explicit_executable:
        explicit = Path(explicit_executable).expanduser()
        if explicit.is_file():
            return BrowserRuntimeInfo(
                ready=True,
                kind="custom",
                label=f"自定义浏览器（{explicit.name}）",
                executable_path=str(explicit),
            )

    def _find_system() -> BrowserRuntimeInfo | None:
        for kind, label, path in _system_candidates(plat):
            if path.is_file():
                return BrowserRuntimeInfo(
                    ready=True,
                    kind=kind,
                    label=f"已检测到本机 {label}",
                    executable_path=str(path),
                )
        return None

    def _find_playwright() -> BrowserRuntimeInfo | None:
        pw_path = _playwright_chromium_path(plat)
        if pw_path is not None and pw_path.is_file():
            return BrowserRuntimeInfo(
                ready=True,
                kind="playwright-chromium",
                label="已就绪内置 Playwright Chromium",
                executable_path=str(pw_path),
            )
        return None

    # Docker / Linux 优先使用镜像预装并验证过的 Playwright Chromium；
    # Windows / macOS 桌面端优先复用系统自带 Edge / Chrome，避免额外下载 180MB 内核。
    if plat.startswith("linux"):
        return (
            _find_playwright()
            or _find_system()
            or BrowserRuntimeInfo(
                ready=False,
                kind="missing",
                label="未检测到可用 Chromium 内核，请安装 Chromium 或运行 playwright install chromium",
                executable_path=None,
            )
        )

    return (
        _find_system()
        or _find_playwright()
        or BrowserRuntimeInfo(
            ready=False,
            kind="missing",
            label="未检测到本机 Microsoft Edge / Google Chrome，请安装任一浏览器后重试",
            executable_path=None,
        )
    )
