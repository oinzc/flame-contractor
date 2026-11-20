"""构建 Flame Contractor 桌面端 Python Sidecar（PyInstaller --onedir）。

用法：
    backend/.venv/Scripts/python.exe scripts/build_desktop_backend.py

产物输出到 `desktop/src-tauri/resources/backend/`，供 Tauri v2 打包进 Windows 安装包。
"""

from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import sys

REPO_ROOT = Path(__file__).resolve().parents[1]
BACKEND_DIR = REPO_ROOT / "backend"
DESKTOP_DIR = REPO_ROOT / "desktop"
OUTPUT_DIR = DESKTOP_DIR / "src-tauri" / "resources" / "backend"
WORK_DIR = DESKTOP_DIR / ".build-pyinstaller"


def main() -> None:
    sep = ";" if os.name == "nt" else ":"
    dist_parent = WORK_DIR / "dist"
    build_dir = WORK_DIR / "build"
    spec_dir = WORK_DIR / "spec"

    if WORK_DIR.exists():
        shutil.rmtree(WORK_DIR)
    dist_parent.mkdir(parents=True, exist_ok=True)
    build_dir.mkdir(parents=True, exist_ok=True)
    spec_dir.mkdir(parents=True, exist_ok=True)

    hidden_imports = [
        "uvicorn.logging",
        "uvicorn.loops",
        "uvicorn.loops.auto",
        "uvicorn.protocols",
        "uvicorn.protocols.http",
        "uvicorn.protocols.http.auto",
        "uvicorn.protocols.http.h11_impl",
        "uvicorn.protocols.http.httptools_impl",
        "uvicorn.protocols.websockets",
        "uvicorn.protocols.websockets.auto",
        "uvicorn.lifespan",
        "uvicorn.lifespan.on",
        "sqlalchemy.dialects.sqlite",
        "sqlalchemy.sql.default_comparator",
        "sqlmodel",
        "alembic",
        "pydantic_settings",
        "app.models",
        "app.execution.selectors",
    ]

    add_data = [
        f"{BACKEND_DIR / 'alembic.ini'}{sep}.",
        f"{BACKEND_DIR / 'alembic'}{sep}alembic",
        f"{BACKEND_DIR / 'app' / 'execution' / 'selectors_bundle.json'}{sep}app/execution",
    ]

    cmd = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--noconfirm",
        "--clean",
        "--onedir",
        "--name",
        "flame-backend",
        "--paths",
        str(BACKEND_DIR),
        "--distpath",
        str(dist_parent),
        "--workpath",
        str(build_dir),
        "--specpath",
        str(spec_dir),
        "--collect-all",
        "playwright",
        "--collect-data",
        "tzdata",
        "--collect-data",
        "certifi",
    ]
    if os.name == "nt":
        cmd.append("--noconsole")

    for mod in hidden_imports:
        cmd.extend(["--hidden-import", mod])
    for item in add_data:
        cmd.extend(["--add-data", item])

    cmd.append(str(BACKEND_DIR / "desktop_entry.py"))

    print("Running PyInstaller:", " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=str(REPO_ROOT), check=True)

    built_dir = dist_parent / "flame-backend"
    if not built_dir.is_dir():
        raise RuntimeError(f"PyInstaller 未产出目录: {built_dir}")

    if OUTPUT_DIR.exists():
        shutil.rmtree(OUTPUT_DIR)
    OUTPUT_DIR.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(built_dir, OUTPUT_DIR)

    exe_name = "flame-backend.exe" if os.name == "nt" else "flame-backend"
    exe_path = OUTPUT_DIR / exe_name
    if not exe_path.is_file():
        raise RuntimeError(f"缺少后端可执行文件: {exe_path}")

    total_bytes = sum(p.stat().st_size for p in OUTPUT_DIR.rglob("*") if p.is_file())
    print(
        f"Desktop backend built: {exe_path} (total {total_bytes / (1024 * 1024):.1f} MB)",
        flush=True,
    )


if __name__ == "__main__":
    main()
