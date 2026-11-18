"""M6-1：失败诊断截图、工件增强（meta/URL/截图路径）与 artifacts 保留期清理。"""

import json
import os
from datetime import datetime, timezone

from fastapi.testclient import TestClient
from pathlib import Path

from app.core.artifacts import (
    prune_old_artifacts,
    resolve_shot_artifact,
    to_artifact_ref,
)
from app.execution.selectors import bundle_digest
from app.scheduler import Scheduler
from tests.fakes import FakePage, make_app


def _page_for_send(**kwargs) -> FakePage:
    params = dict(
        logged_in=True,
        conversation_titles=("好友A",),
        right_title="好友A",
        editor_present=True,
    )
    params.update(kwargs)
    return FakePage(**params)


def _account_with_target(client: TestClient) -> dict:
    account = client.post("/api/accounts", json={"displayName": "测试账号"}).json()
    template = client.post(
        "/api/templates",
        json={"name": "T", "content": "{{target_name}}，第 {{streak_days}} 天。"},
    ).json()
    client.post(
        "/api/targets",
        json={
            "accountId": account["id"],
            "nicknameSnapshot": "好友A",
            "templateId": template["id"],
            "streakDays": 5,
        },
    )
    return account


def test_send_failure_captures_screenshot_and_meta(settings):
    """失败标记 → failed_retryable：结果与工件都带截图路径、页面 URL 与版本 meta。"""
    app = make_app(settings, [_page_for_send(failure_marker=True)])
    with TestClient(app) as client:
        account = _account_with_target(client)
        payload = client.post(f"/api/accounts/{account['id']}/send").json()

        result = payload["results"][0]
        assert result["status"] == "failed_retryable"
        assert result["screenshot"] and result["screenshot"].endswith(".png")
        assert Path(result["screenshot"]).exists()
        assert result["pageUrl"] == "https://www.douyin.com/chat"

        artifact = json.loads(Path(payload["artifactPath"]).read_text(encoding="utf-8"))
        assert artifact["meta"]["app_version"] == settings.version
        assert artifact["meta"]["bundle_digest"] == bundle_digest()
        assert artifact["meta"]["bundle_digest"]


def test_unknown_outcome_captures_screenshot(settings):
    """无失败标记但气泡未确认 → unknown：同样保留现场截图。"""
    app = make_app(
        settings,
        [_page_for_send(sticker_items=("比心",), image_bubble_render=False)],
    )
    with TestClient(app) as client:
        template = client.post(
            "/api/templates",
            json={"name": "S", "type": "sticker", "stickerName": "比心"},
        ).json()
        account = client.post("/api/accounts", json={"displayName": "测试账号"}).json()
        client.post(
            "/api/targets",
            json={
                "accountId": account["id"],
                "nicknameSnapshot": "好友A",
                "templateId": template["id"],
                "streakDays": 1,
            },
        )
        payload = client.post(f"/api/accounts/{account['id']}/send").json()
        result = payload["results"][0]
        # 无气泡 → 不误判成功；失败标记可见与否决定 failed_retryable/unknown，两者都应留截图
        assert result["status"] in ("unknown_requires_review", "failed_retryable"), result
        assert result["screenshot"] and Path(result["screenshot"]).exists()


def test_not_found_captures_screenshot(settings):
    """列表里没有该好友 → not_found（终态）：截图留证。"""
    app = make_app(settings, [_page_for_send(conversation_titles=())])
    with TestClient(app) as client:
        account = _account_with_target(client)
        payload = client.post(f"/api/accounts/{account['id']}/send").json()
        result = payload["results"][0]
        assert result["status"] == "not_found"
        assert result["screenshot"] and Path(result["screenshot"]).exists()


def test_bundle_digest_is_stable_string(settings):
    assert isinstance(bundle_digest(), str) and len(bundle_digest()) == 12
    assert bundle_digest() == bundle_digest()


def test_prune_old_artifacts(settings, tmp_path):
    """保留期内文件保留，超期文件删除；retention_days=0 关闭清理。"""
    old = tmp_path / "old.json"
    new = tmp_path / "new.json"
    old.write_text("{}", encoding="utf-8")
    new.write_text("{}", encoding="utf-8")
    old_time = datetime(2026, 1, 1, tzinfo=timezone.utc).timestamp()
    os.utime(old, (old_time, old_time))
    now = datetime(2026, 8, 29, tzinfo=timezone.utc)

    assert prune_old_artifacts(tmp_path, 30, now=now) == 1
    assert not old.exists() and new.exists()

    assert prune_old_artifacts(tmp_path, 0, now=now) == 0
    assert prune_old_artifacts(tmp_path / "missing", 30, now=now) == 0


def test_artifact_ref_roundtrip_and_traversal_guard(tmp_path):
    """只接受归属当前账号 shots/*.png 的相对路径；越界、跨账号、非 PNG 一律返回 none。"""
    artifacts_dir = tmp_path / "artifacts"
    shot = artifacts_dir / "acct-1" / "shots" / "20261118-150000-unknown_outcome.png"
    shot.parent.mkdir(parents=True, exist_ok=True)
    shot.write_bytes(b"\x89PNG\r\n\x1a\n")

    other_shot = artifacts_dir / "acct-2" / "shots" / "other.png"
    other_shot.parent.mkdir(parents=True, exist_ok=True)
    other_shot.write_bytes(b"\x89PNG\r\n\x1a\n")

    json_file = artifacts_dir / "acct-1" / "send-20261118.json"
    json_file.write_text("{}", encoding="utf-8")

    secret_file = tmp_path / ".env"
    secret_file.write_text("FLAME_SECRET_KEY=secret", encoding="utf-8")

    ref = to_artifact_ref(artifacts_dir, shot)
    assert ref == "acct-1/shots/20261118-150000-unknown_outcome.png"
    assert to_artifact_ref(artifacts_dir, None) is None
    assert to_artifact_ref(artifacts_dir, secret_file) is None

    state, resolved = resolve_shot_artifact(artifacts_dir, "acct-1", ref)
    assert state == "available"
    assert resolved == shot.resolve()

    # 跨账号、路径穿越、非 shots 目录或非 .png、绝对路径、超长字符串全部拒绝为 none
    for bad_ref in (
        None,
        "",
        "../.env",
        "../../.env",
        "acct-1/shots/../../../.env",
        "acct-2/shots/other.png",
        "acct-1/send-20261118.json",
        "acct-1/shots/note.txt",
        str(shot.resolve()),
        "acct-1/shots/" + ("x" * 260) + ".png",
    ):
        bad_state, bad_path = resolve_shot_artifact(artifacts_dir, "acct-1", bad_ref)
        assert (bad_state, bad_path) == ("none", None), f"unexpected result for {bad_ref!r}"

    # 合法引用文件被保留期清理后 → expired
    shot.unlink()
    expired_state, expired_path = resolve_shot_artifact(artifacts_dir, "acct-1", ref)
    assert (expired_state, expired_path) == ("expired", None)

    # 重新落盘后验证恶意 account_id（相对段、大小写别名、嵌套段）同样被拒绝为 none
    shot.write_bytes(b"\x89PNG\r\n\x1a\n")
    for bad_acct_id in ("./acct-1", "acct-1/shots/..", "ACCT-1", "../artifacts/acct-1"):
        assert resolve_shot_artifact(artifacts_dir, bad_acct_id, ref) == ("none", None)


def test_scheduler_prunes_old_artifacts_every_24_hours(settings, tmp_path, monkeypatch):
    """Scheduler.tick 按 24 小时门控清理过期诊断文件，且清理异常不中断调度。"""
    import asyncio
    from datetime import timedelta
    from app.core.database import build_engine
    from app.core.migrations import run_migrations

    artifacts_dir = tmp_path / "artifacts"
    artifacts_dir.mkdir(parents=True, exist_ok=True)
    runtime_settings = settings.model_copy(
        update={
            "artifacts_dir": artifacts_dir,
            "artifact_retention_days": 30,
        }
    )
    run_migrations(runtime_settings.database_url)
    engine = build_engine(runtime_settings.database_url)

    base_now = datetime(2026, 11, 18, 8, 0, 0, tzinfo=timezone.utc)
    old_mtime = (base_now - timedelta(days=45)).timestamp()

    stale_1 = artifacts_dir / "old-1.png"
    stale_1.write_bytes(b"1")
    os.utime(stale_1, (old_mtime, old_mtime))

    sched = Scheduler(engine, runtime_settings)
    asyncio.run(sched.tick(now=base_now))
    assert not stale_1.exists()

    # 12 小时内新增一个超期文件：未满 24h 门控，不重复扫描磁盘
    stale_2 = artifacts_dir / "old-2.png"
    stale_2.write_bytes(b"2")
    os.utime(stale_2, (old_mtime, old_mtime))
    asyncio.run(sched.tick(now=base_now + timedelta(hours=12)))
    assert stale_2.exists()

    # 推进满 24 小时：触发下一轮清理（即使全局暂停自动发送也照常清理磁盘）
    sched.pause()
    asyncio.run(sched.tick(now=base_now + timedelta(hours=24, minutes=1)))
    assert not stale_2.exists()

    # 清理过程抛异常时不应冒泡打断 tick
    def _boom(*_args, **_kwargs):
        raise OSError("disk busy")

    monkeypatch.setattr("app.scheduler.prune_old_artifacts", _boom)
    res = asyncio.run(sched.tick(now=base_now + timedelta(hours=49)))
    assert res == {"dispatched": 0}
    engine.dispose()


def test_send_failures_persist_attempt_artifact_ref_and_expose_detail_api(settings):
    """定位失败、重试失败与待复核均把相对截图路径写入 SendAttempt，并由详情/截图 API 安全暴露。"""
    from sqlmodel import Session, select
    from app.models import JobStatus, SendAttempt, SendJob

    # 1) not_found（opening_chat 阶段）与 2) unknown_requires_review 两个账号分别验证
    app = make_app(
        settings,
        [
            _page_for_send(conversation_titles=()),
            _page_for_send(clear_on_enter=False),
        ],
    )
    with TestClient(app) as client:
        acct1 = _account_with_target(client)
        r1 = client.post(f"/api/accounts/{acct1['id']}/send").json()
        assert r1["results"][0]["status"] == "not_found"

        acct2 = client.post("/api/accounts", json={"displayName": "第二账号"}).json()
        tpl = client.get("/api/templates").json()[0]
        client.post(
            "/api/targets",
            json={
                "accountId": acct2["id"],
                "nicknameSnapshot": "好友A",
                "templateId": tpl["id"],
                "streakDays": 2,
            },
        )
        r2 = client.post(f"/api/accounts/{acct2['id']}/send").json()
        assert r2["results"][0]["status"] == "unknown_requires_review"

        with Session(app.state.engine) as session:
            jobs = session.exec(select(SendJob).order_by(SendJob.created_at)).all()
            assert len(jobs) == 2
            job_not_found, job_unknown = jobs[0], jobs[1]
            att_1 = session.exec(
                select(SendAttempt).where(SendAttempt.job_id == job_not_found.id)
            ).one()
            assert att_1.stage == "opening_chat"
            assert att_1.artifact_ref and att_1.artifact_ref.startswith(f"{acct1['id']}/shots/")

            att_2 = session.exec(
                select(SendAttempt).where(SendAttempt.job_id == job_unknown.id)
            ).one()
            assert att_2.artifact_ref and att_2.artifact_ref.startswith(f"{acct2['id']}/shots/")

        # GET /api/jobs/{id} 详情与截图二进制读取
        detail_resp = client.get(f"/api/jobs/{job_unknown.id}")
        assert detail_resp.status_code == 200, detail_resp.text
        detail = detail_resp.json()
        assert detail["id"] == job_unknown.id
        assert detail["latestScreenshotAttemptNo"] == 1
        assert detail["latestScreenshotState"] == "available"
        assert len(detail["attempts"]) == 1
        assert detail["attempts"][0]["screenshotState"] == "available"
        # 不暴露宿主机文件路径字段
        assert "artifactRef" not in detail["attempts"][0]
        assert "artifact_ref" not in detail["attempts"][0]

        shot_resp = client.get(f"/api/jobs/{job_unknown.id}/attempts/1/screenshot")
        assert shot_resp.status_code == 200
        assert shot_resp.headers["content-type"].startswith("image/png")
        assert "no-store" in shot_resp.headers.get("cache-control", "")
        assert len(shot_resp.content) > 0

        # 人工确认已发出：新增 manual_confirm_sent 尝试（无截图），历史第 1 次尝试截图仍可追溯
        confirm_resp = client.post(f"/api/jobs/{job_unknown.id}/confirm-sent")
        assert confirm_resp.status_code == 200
        after_confirm = client.get(f"/api/jobs/{job_unknown.id}").json()
        assert [a["stage"] for a in after_confirm["attempts"]] == ["confirming", "manual_confirm_sent"]
        assert after_confirm["latestScreenshotAttemptNo"] == 1
        assert after_confirm["latestScreenshotState"] == "available"

        # 模拟文件被保留期清理删除 → 详情返回 expired，截图端点返回 410
        (settings.artifacts_dir / att_2.artifact_ref).unlink()
        expired_detail = client.get(f"/api/jobs/{job_unknown.id}").json()
        assert expired_detail["latestScreenshotState"] == "expired"
        assert expired_detail["attempts"][0]["screenshotState"] == "expired"
        expired_shot = client.get(f"/api/jobs/{job_unknown.id}/attempts/1/screenshot")
        assert expired_shot.status_code == 410


def test_job_screenshot_blocks_cross_user_and_backup_traversal(settings):
    """跨用户访问一律 404；通过备份导入注入恶意 artifact_ref 时判定为 none 并返回 404。"""
    app = make_app(settings, [_page_for_send(clear_on_enter=False)])
    secret = settings.artifacts_dir.parent / ".env"
    secret.write_text("FLAME_SECRET_KEY=top-secret-do-not-leak", encoding="utf-8")

    with TestClient(app) as client:
        acct = _account_with_target(client)
        client.post(f"/api/accounts/{acct['id']}/send")
        job_id = client.get("/api/jobs").json()["items"][0]["id"]

        # 创建另一平台用户 bob，验证跨用户隔离
        client.post(
            "/api/auth/users",
            json={"username": "bob", "password": "bobpassword123"},
        )
        bob_login = client.post(
            "/api/auth/login",
            json={"username": "bob", "password": "bobpassword123"},
        ).json()
        bob_headers = {"Authorization": f"Bearer {bob_login['token']}"}

        assert client.get(f"/api/jobs/{job_id}", headers=bob_headers).status_code == 404
        assert (
            client.get(f"/api/jobs/{job_id}/attempts/1/screenshot", headers=bob_headers).status_code
            == 404
        )

        # 通过备份导出→注入新主键的恶意路径穿越 attempt→导入，验证读取被安全拦截
        backup = client.get("/api/backup").json()
        malicious_attempt = dict(backup["data"]["attempts"][0])
        malicious_attempt["id"] = "00000000-0000-4000-8000-000000000999"
        malicious_attempt["attempt_no"] = 2
        malicious_attempt["artifact_ref"] = "../../.env"
        backup["data"]["attempts"].append(malicious_attempt)
        import_resp = client.post("/api/backup/import", json=backup)
        assert import_resp.status_code == 200, import_resp.text

        tampered_detail = client.get(f"/api/jobs/{job_id}").json()
        att_by_no = {a["attemptNo"]: a for a in tampered_detail["attempts"]}
        assert att_by_no[2]["screenshotState"] == "none"
        assert tampered_detail["latestScreenshotAttemptNo"] == 1
        tampered_shot = client.get(f"/api/jobs/{job_id}/attempts/2/screenshot")
        assert tampered_shot.status_code == 404
        assert b"top-secret" not in tampered_shot.content

        # bob 通过备份导入伪造路径别名账号 id="./<alice_acct>" 企图借壳读取 alice 截图 → 必须被拦截
        real_ref = tampered_detail["id"]
        from sqlmodel import Session, select
        from app.models import SendAttempt

        with Session(app.state.engine) as session:
            alice_ref = session.exec(
                select(SendAttempt.artifact_ref).where(
                    SendAttempt.job_id == real_ref, SendAttempt.attempt_no == 1
                )
            ).one()
        evil_acct_id = f"./{acct['id']}"
        evil_backup = {
            "app": "flame-contractor",
            "schemaVersion": 1,
            "exportedAt": "2026-11-18T08:00:00Z",
            "data": {
                "accounts": [
                    {
                        "id": evil_acct_id,
                        "display_name": "伪造别名号",
                        "status": "healthy",
                        "enabled": True,
                        "timezone": "Asia/Shanghai",
                        "send_window_start": "20:00",
                        "send_window_end": "22:00",
                    }
                ],
                "templates": [],
                "pool_items": [],
                "targets": [
                    {
                        "id": "00000000-0000-4000-8000-00000000e001",
                        "account_id": evil_acct_id,
                        "nickname_snapshot": "伪造目标",
                        "enabled": True,
                        "streak_days": 1,
                    }
                ],
                "schedules": [],
                "jobs": [
                    {
                        "id": "00000000-0000-4000-8000-00000000e002",
                        "account_id": evil_acct_id,
                        "target_id": "00000000-0000-4000-8000-00000000e001",
                        "business_date": "2026-11-18",
                        "message_slot_id": "evil-slot",
                        "status": "unknown_requires_review",
                        "attempt_count": 1,
                    }
                ],
                "attempts": [
                    {
                        "id": "00000000-0000-4000-8000-00000000e003",
                        "job_id": "00000000-0000-4000-8000-00000000e002",
                        "attempt_no": 1,
                        "stage": "confirming",
                        "status": "failed",
                        "artifact_ref": alice_ref,
                    }
                ],
            },
        }
        bob_imp = client.post("/api/backup/import", json=evil_backup, headers=bob_headers)
        assert bob_imp.status_code == 200, bob_imp.text
        bob_detail = client.get(
            "/api/jobs/00000000-0000-4000-8000-00000000e002", headers=bob_headers
        ).json()
        assert bob_detail["latestScreenshotState"] == "none"
        assert (
            client.get(
                "/api/jobs/00000000-0000-4000-8000-00000000e002/attempts/1/screenshot",
                headers=bob_headers,
            ).status_code
            == 404
        )


def test_bubble_confirmed_upgrade_does_not_persist_failure_screenshot(settings):
    """气泡证据将 unknown 升级核销为 succeeded 时，成功任务不保留或绑定现场截图。"""
    from sqlmodel import Session, select
    from app.models import JobStatus, SendAttempt, SendJob

    class BubblePage(FakePage):
        async def wait_for_function(self, _script, arg=None, timeout=None):
            return True

    app = make_app(
        settings,
        [
            BubblePage(
                logged_in=True,
                conversation_titles=("好友A",),
                right_title="好友A",
                clear_on_enter=False,
            )
        ],
    )
    with TestClient(app) as client:
        acct = _account_with_target(client)
        res = client.post(f"/api/accounts/{acct['id']}/send").json()
        assert res["results"][0]["status"] == "succeeded"

        with Session(app.state.engine) as session:
            job = session.exec(select(SendJob)).one()
            assert job.status == JobStatus.SUCCEEDED
            attempt = session.exec(select(SendAttempt).where(SendAttempt.job_id == job.id)).one()
            assert attempt.artifact_ref is None

        detail = client.get(f"/api/jobs/{job.id}").json()
        assert detail["latestScreenshotState"] == "none"
        assert client.get(f"/api/jobs/{job.id}/attempts/1/screenshot").status_code == 404
