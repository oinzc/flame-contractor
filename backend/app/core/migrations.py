from pathlib import Path
import sys

from alembic import command
from alembic.config import Config


def _backend_root() -> Path:
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        return Path(getattr(sys, "_MEIPASS")).resolve()
    return Path(__file__).resolve().parents[2]


BACKEND_ROOT = _backend_root()


def run_migrations(database_url: str) -> None:
    """升级数据库到最新版本；空库时自动建表。"""
    if database_url.startswith("sqlite"):
        database = database_url.split("///", 1)[-1]
        if database and database != ":memory:":
            Path(database).parent.mkdir(parents=True, exist_ok=True)

    root = _backend_root()
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "alembic"))
    config.set_main_option("sqlalchemy.url", database_url)
    command.upgrade(config, "head")
