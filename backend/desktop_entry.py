"""Flame Contractor Windows/macOS 桌面端 Sidecar 启动入口。

由 Tauri 桌面壳以子进程拉起，强制绑定 127.0.0.1；数据目录、端口与当次随机握手
令牌通过环境变量注入，运行日志按轮转写入 <FLAME_DATA_DIR>/logs/backend.log。
"""

from __future__ import annotations

import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import sys

import uvicorn

from app.core.config import Settings
from app.main import create_app


def _ensure_std_streams() -> None:
    """Windows --noconsole 模式下 stdout/stderr 为 None，避免 alembic/logging 写流崩溃。"""
    if sys.stdout is None:
        sys.stdout = open(os.devnull, "w", encoding="utf-8")  # noqa: SIM115
    if sys.stderr is None:
        sys.stderr = open(os.devnull, "w", encoding="utf-8")  # noqa: SIM115


def _configure_logging(data_dir: Path | None) -> None:
    _ensure_std_streams()
    handlers: list[logging.Handler] = []
    if data_dir is not None:
        log_dir = data_dir / "logs"
        log_dir.mkdir(parents=True, exist_ok=True)
        handlers.append(
            RotatingFileHandler(
                log_dir / "backend.log",
                maxBytes=5 * 1024 * 1024,
                backupCount=3,
                encoding="utf-8",
            )
        )
    if sys.stdout is not None and sys.stdout.name != os.devnull:
        handlers.append(logging.StreamHandler(sys.stdout))
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        handlers=handlers or None,
        force=True,
    )


def main() -> None:
    port_raw = os.environ.get("FLAME_PORT", "8000").strip()
    try:
        port = int(port_raw)
        if not (1 <= port <= 65535):
            raise ValueError
    except ValueError:
        port = 8000

    # 桌面端完全由环境变量与 FLAME_DATA_DIR 驱动，不读取当前工作目录的零散 .env 文件。
    settings = Settings(_env_file=None)
    _configure_logging(settings.data_dir)
    logging.getLogger("flame.desktop").info(
        "启动 Flame Contractor 桌面后端（127.0.0.1:%s，desktop_mode=%s）",
        port,
        settings.desktop_mode,
    )
    app = create_app(settings=settings)
    # --noconsole 模式下 sys.stdout/stderr 可能为 None，禁用 uvicorn 默认 tty 色彩配置。
    uvicorn.run(
        app,
        host="127.0.0.1",
        port=port,
        log_config=None,
        access_log=False,
    )


if __name__ == "__main__":
    main()
