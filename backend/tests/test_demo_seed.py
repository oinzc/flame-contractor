"""演示数据生成脚本的冒烟测试：能在空库上跑通，且数据全部是虚构的演示内容。"""

import importlib.util
from datetime import datetime
from pathlib import Path

from sqlmodel import Session, select

from app.core.database import build_engine
from app.models import Account, ContractTarget, ScanTask, SendJob, User

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "demo_server.py"
SCREENSHOTS_SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "demo_screenshots.py"


def _load_demo_module():
    spec = importlib.util.spec_from_file_location("demo_server", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _load_screenshots_module():
    spec = importlib.util.spec_from_file_location("demo_screenshots", SCREENSHOTS_SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_seed_builds_fictional_demo_dataset(tmp_path):
    demo = _load_demo_module()
    url = f"sqlite:///{(tmp_path / 'demo.db').as_posix()}"
    demo.seed(url, datetime(2026, 11, 7, 9, 0, 0))

    engine = build_engine(url)
    with Session(engine) as session:
        users = session.exec(select(User)).all()
        assert [u.username for u in users] == [demo.DEMO_USERNAME]
        assert len(session.exec(select(Account)).all()) == 3
        targets = session.exec(select(ContractTarget)).all()
        assert len(targets) == 13
        assert all(
            (t.platform_user_id or t.platform_conversation_id or "").startswith("demo-")
            for t in targets
        )
        assert session.exec(select(SendJob)).first() is not None
        assert len(session.exec(select(ScanTask)).all()) == 4
    engine.dispose()


def test_demo_screenshots_plan_covers_scheme_a_targets():
    shots = _load_screenshots_module()
    source = SCREENSHOTS_SCRIPT.read_text(encoding="utf-8")

    assert [(item["name"], item["theme"]) for item in shots.SHOT_PLAN] == [
        ("dashboard", "light"),
        ("send-confirm", "light"),
        ("targets", "light"),
        ("scan-reconciliation", "light"),
        ("jobs", "light"),
        ("accounts", "dark"),
    ]
    for legacy_token in ("#scan-tab", "输入用户名", "输入密码", "立即发送"):
        assert legacy_token not in source
    assert "#auth-username" in source
    assert "#auth-password" in source
