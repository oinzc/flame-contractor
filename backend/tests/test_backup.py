"""M8-3：备份导出/导入——全量往返、空库恢复、幂等 upsert。"""

from datetime import date, datetime, timedelta, timezone

from fastapi.testclient import TestClient
from sqlmodel import Session, select

from app.models import (
    Account,
    AccountStatus,
    AttemptStatus,
    ContractTarget,
    FailureClass,
    JobStatus,
    MessageTemplate,
    ScheduleStatus,
    SendAttempt,
    SendJob,
    SendSchedule,
    TemplatePoolItem,
    TemplateType,
)
from tests.fakes import FakePage, create_account, make_app


def _seed_and_export(client: TestClient) -> dict:
    account = create_account(client)
    template = client.post(
        "/api/templates",
        json={
            "name": "池",
            "type": "pool",
            "poolItems": [{"content": "候选 {{target_name}}"}],
        },
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
    client.post(f"/api/accounts/{account['id']}/send")
    return client.get("/api/backup").json()


def test_backup_roundtrip_to_fresh_db(settings):
    """导出 → 空库导入 = 完整恢复（含文案池候选与任务记录）。"""
    app = make_app(
        settings,
        [FakePage(logged_in=True, conversation_titles=("好友A",), right_title="好友A")],
    )
    with TestClient(app) as client:
        exported = _seed_and_export(client)

    assert exported["schemaVersion"] == 1
    data = exported["data"]
    assert len(data["accounts"]) == 1
    assert len(data["targets"]) == 1
    assert len(data["poolItems"]) == 1
    assert len(data["jobs"]) == 1
    assert data["jobs"][0]["status"] == "succeeded"

    # 空库导入 = 完整恢复
    fresh_settings = settings.model_copy(
        update={"database_url": settings.database_url.replace("test.db", "fresh.db")}
    )
    app2 = make_app(fresh_settings, [])
    with TestClient(app2) as client2:
        result = client2.post("/api/backup/import", json=exported).json()
        assert result["inserted"]["accounts"] == 1
        assert result["inserted"]["pool_items"] == 1
        assert result["inserted"]["jobs"] == 1

        with Session(app2.state.engine) as session:
            assert len(session.exec(select(Account)).all()) == 1
            assert len(session.exec(select(TemplatePoolItem)).all()) == 1
            assert len(session.exec(select(SendJob)).all()) == 1
            job = session.exec(select(SendJob)).one()
            assert job.status.value == "succeeded"
            target = session.exec(select(ContractTarget)).one()
            # 种子阶段真实发送成功，火花已 5→6；导入应完整还原导出值
            assert target.streak_days == data["targets"][0]["streak_days"] == 6


def test_backup_import_is_idempotent_upsert(settings):
    """同一份备份导入两次：第二次全部走更新（updated），数据不重复。"""
    app = make_app(settings, [])
    with TestClient(app) as client:
        create_account(client)
        exported = client.get("/api/backup").json()

        first = client.post("/api/backup/import", json=exported).json()
        second = client.post("/api/backup/import", json=exported).json()
        # 同库导入：已存在的行全部走更新（upsert），绝不产生重复
        assert first["inserted"]["accounts"] == 0
        assert first["updated"]["accounts"] == 1
        assert second["inserted"]["accounts"] == 0
        assert second["updated"]["accounts"] == 1

        with Session(app.state.engine) as session:
            assert len(session.exec(select(Account)).all()) == 1


def test_backup_rejects_unknown_schema_version(settings):
    app = make_app(settings, [])
    with TestClient(app) as client:
        create_account(client)
        exported = client.get("/api/backup").json()
        exported["schemaVersion"] = 999
        response = client.post("/api/backup/import", json=exported)
        assert response.status_code == 422


def test_backup_import_accepts_and_ignores_android_extensions(settings):
    """BackupRead 接受并忽略顶层 androidExtensions，同时保持其余未知字段 extra=forbid 拒绝。"""
    app = make_app(settings, [])
    with TestClient(app) as client:
        create_account(client)
        exported = client.get("/api/backup").json()

        fresh_settings = settings.model_copy(
            update={"database_url": settings.database_url.replace("test.db", "ext.db")}
        )
        app2 = make_app(fresh_settings, [])
        with TestClient(app2) as client2:
            payload = dict(exported)
            payload["androidExtensions"] = {
                "account": {"autoScanEnabled": True, "autoScanIntervalHours": 24}
            }
            resp = client2.post("/api/backup/import", json=payload)
            assert resp.status_code == 200, resp.text
            assert resp.json()["inserted"]["accounts"] == 1

            bad_payload = dict(exported)
            bad_payload["unknownField"] = 1
            bad_resp = client2.post("/api/backup/import", json=bad_payload)
            assert bad_resp.status_code == 422


def test_backup_import_updates_existing_child_rows_without_skipping(settings):
    """子表（pool_items/targets/jobs）已有行再次导入应按归属校验更新，不能因无 user_id 而静默跳过。"""
    app = make_app(
        settings,
        [
            FakePage(
                logged_in=True,
                conversation_titles=("好友A",),
                right_title="好友A",
                failure_marker=True,
            )
        ],
    )
    with TestClient(app) as client:
        exported = _seed_and_export(client)
        assert exported["data"]["jobs"][0]["status"] == "failed_retryable"

        exported["data"]["poolItems"][0]["content"] = "更新后的候选 {{target_name}}"
        exported["data"]["targets"][0]["nickname_snapshot"] = "好友A-已更新"
        exported["data"]["jobs"][0]["status"] = "succeeded"

        resp = client.post("/api/backup/import", json=exported)
        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["updated"]["pool_items"] == 1
        assert body["updated"]["targets"] == 1
        assert body["updated"]["jobs"] == 1

        with Session(app.state.engine) as session:
            pool_item = session.exec(select(TemplatePoolItem)).one()
            target = session.exec(select(ContractTarget)).one()
            job = session.exec(select(SendJob)).one()
            assert pool_item.content == "更新后的候选 {{target_name}}"
            assert target.nickname_snapshot == "好友A-已更新"
            assert job.status == JobStatus.SUCCEEDED


def test_backup_import_isolates_fixed_android_ids_across_users(settings):
    """多用户分别导入含固定 ID（default / default-sticker-bixin）的 Android 备份时互不串主、不崩溃。"""
    app = make_app(settings, [])
    app.state.disable_test_auto_auth = True
    android_backup = {
        "app": "flame-contractor",
        "schemaVersion": 1,
        "exportedAt": "2026-11-20T10:00:00Z",
        "androidExtensions": {"account": {"autoScanEnabled": True}},
        "data": {
            "accounts": [
                {
                    "id": "default",
                    "display_name": "默认抖音号",
                    "status": "pending_login",
                    "enabled": True,
                    "timezone": "Asia/Shanghai",
                    "send_window_start": "20:00",
                    "send_window_end": "22:00",
                }
            ],
            "templates": [
                {
                    "id": "default-sticker-bixin",
                    "name": "比心表情",
                    "type": "sticker",
                    "content": "",
                    "sticker_name": "比心",
                    "enabled": True,
                }
            ],
            "poolItems": [],
            "targets": [
                {
                    "id": "android-target-1",
                    "account_id": "default",
                    "nickname_snapshot": "好友A",
                    "conversation_type": "user",
                    "template_id": "default-sticker-bixin",
                    "enabled": True,
                    "streak_days": 7,
                }
            ],
            "schedules": [],
            "jobs": [
                {
                    "id": "android-job-1",
                    "account_id": "default",
                    "target_id": "android-target-1",
                    "template_id": "default-sticker-bixin",
                    "business_date": "2026-11-20",
                    "message_slot_id": "daily",
                    "status": "succeeded",
                    "attempt_count": 1,
                }
            ],
            "attempts": [],
        },
    }

    with TestClient(app) as client:
        reg_a = client.post(
            "/api/auth/register",
            json={"username": "alice", "password": "alicepassword123"},
        )
        assert reg_a.status_code == 201, reg_a.text
        headers_a = {"Authorization": f"Bearer {reg_a.json()['token']}"}
        alice_id = reg_a.json()["user"]["id"]

        create_b = client.post(
            "/api/auth/users",
            headers=headers_a,
            json={"username": "bob", "password": "bobpassword123", "isSuperuser": False},
        )
        assert create_b.status_code == 201, create_b.text
        login_b = client.post(
            "/api/auth/login",
            json={"username": "bob", "password": "bobpassword123"},
        )
        assert login_b.status_code == 200, login_b.text
        headers_b = {"Authorization": f"Bearer {login_b.json()['token']}"}
        bob_id = login_b.json()["user"]["id"]

        imp_a = client.post("/api/backup/import", json=android_backup, headers=headers_a)
        assert imp_a.status_code == 200, imp_a.text
        imp_b = client.post("/api/backup/import", json=android_backup, headers=headers_b)
        assert imp_b.status_code == 200, imp_b.text

        with Session(app.state.engine) as session:
            accounts = session.exec(select(Account)).all()
            templates = session.exec(select(MessageTemplate)).all()
            targets = session.exec(select(ContractTarget)).all()
            jobs = session.exec(select(SendJob)).all()
            assert len(accounts) == 2
            assert len(templates) == 2
            assert len(targets) == 2
            assert len(jobs) == 2

            acc_by_user = {a.user_id: a for a in accounts}
            tpl_by_user = {t.user_id: t for t in templates}
            assert set(acc_by_user.keys()) == {alice_id, bob_id}
            assert set(tpl_by_user.keys()) == {alice_id, bob_id}
            assert acc_by_user[alice_id].id != acc_by_user[bob_id].id
            assert tpl_by_user[alice_id].id != tpl_by_user[bob_id].id

            tgt_by_acct = {t.account_id: t for t in targets}
            job_by_acct = {j.account_id: j for j in jobs}
            assert tgt_by_acct[acc_by_user[alice_id].id].template_id == tpl_by_user[alice_id].id
            assert tgt_by_acct[acc_by_user[bob_id].id].template_id == tpl_by_user[bob_id].id
            assert (
                job_by_acct[acc_by_user[alice_id].id].target_id
                == tgt_by_acct[acc_by_user[alice_id].id].id
            )
            assert (
                job_by_acct[acc_by_user[bob_id].id].target_id
                == tgt_by_acct[acc_by_user[bob_id].id].id
            )


def test_backup_import_merges_android_export_into_nonempty_desktop_db(settings):
    """非空桌面库导入 Android 备份：自然键合并、类型冲突改名、friend 归一化、瞬态收敛与状态保护。"""
    app = make_app(settings, [])
    with TestClient(app) as client:
        acct = create_account(client, "默认抖音号")
        pool_tpl = client.post(
            "/api/templates",
            json={
                "name": "每日续火",
                "type": "pool",
                "poolItems": [{"content": "桌面文案池 {{target_name}}"}],
            },
        ).json()
        target = client.post(
            "/api/targets",
            json={
                "accountId": acct["id"],
                "nicknameSnapshot": "好友A",
                "templateId": pool_tpl["id"],
                "streakDays": 5,
            },
        ).json()

        with Session(app.state.engine) as session:
            db_acct = session.get(Account, acct["id"])
            assert db_acct is not None
            db_acct.status = AccountStatus.HEALTHY
            db_acct.douyin_nickname = "桌面登录昵称"
            db_acct.last_verified_at = datetime(2026, 11, 20, 8, 0, tzinfo=timezone.utc)
            session.add(db_acct)

            protected_job = SendJob(
                account_id=acct["id"],
                target_id=target["id"],
                template_id=pool_tpl["id"],
                business_date=date(2026, 11, 19),
                message_slot_id="daily",
                status=JobStatus.UNKNOWN_REQUIRES_REVIEW,
                attempt_count=3,
                failure_class=FailureClass.UNKNOWN_OUTCOME,
            )
            session.add(protected_job)
            session.commit()
            protected_job_id = protected_job.id

        android_backup = {
            "app": "flame-contractor",
            "schemaVersion": 1,
            "exportedAt": "2026-11-20T12:00:00Z",
            "androidExtensions": {"account": {"autoScanEnabled": True}},
            "data": {
                "accounts": [
                    {
                        "id": "default",
                        "display_name": "默认抖音号",
                        "status": "pending_login",
                        "enabled": True,
                        "timezone": "Asia/Shanghai",
                        "send_window_start": "20:00",
                        "send_window_end": "22:00",
                        "failure_streak": 0,
                    }
                ],
                "templates": [
                    {
                        "id": "default-text-daily",
                        "name": "每日续火",
                        "type": "text",
                        "content": "早安续火",
                        "enabled": True,
                    }
                ],
                "poolItems": [],
                "targets": [
                    {
                        "id": "android-target-a",
                        "account_id": "default",
                        "nickname_snapshot": "好友A",
                        "conversation_type": "friend",
                        "template_id": "default-text-daily",
                        "enabled": True,
                        "streak_days": 12,
                    }
                ],
                "schedules": [],
                "jobs": [
                    {
                        "id": "android-job-sending",
                        "account_id": "default",
                        "target_id": "android-target-a",
                        "template_id": "default-text-daily",
                        "business_date": "2026-11-20",
                        "message_slot_id": "daily",
                        "status": "sending",
                        "attempt_count": 1,
                    },
                    {
                        "id": "android-job-review",
                        "account_id": "default",
                        "target_id": "android-target-a",
                        "template_id": "default-text-daily",
                        "business_date": "2026-11-19",
                        "message_slot_id": "daily",
                        "status": "unknown_requires_review",
                        "attempt_count": 1,
                    },
                ],
                "attempts": [],
            },
        }

        resp = client.post("/api/backup/import", json=android_backup)
        assert resp.status_code == 200, resp.text

        targets_resp = client.get("/api/targets")
        assert targets_resp.status_code == 200, targets_resp.text
        targets_json = targets_resp.json()
        assert len(targets_json) == 1
        assert targets_json[0]["conversationType"] == "user"
        assert targets_json[0]["streakDays"] == 12

        with Session(app.state.engine) as session:
            db_acct = session.get(Account, acct["id"])
            assert db_acct is not None
            assert db_acct.status == AccountStatus.HEALTHY
            assert db_acct.douyin_nickname == "桌面登录昵称"

            templates = session.exec(select(MessageTemplate)).all()
            tpl_by_name = {t.name: t for t in templates}
            assert "每日续火" in tpl_by_name
            assert tpl_by_name["每日续火"].type == TemplateType.POOL
            assert "每日续火 (导入)" in tpl_by_name
            assert tpl_by_name["每日续火 (导入)"].type == TemplateType.TEXT

            db_target = session.get(ContractTarget, target["id"])
            assert db_target is not None
            assert db_target.conversation_type == "user"
            assert db_target.streak_days == 12

            jobs = session.exec(select(SendJob)).all()
            assert len(jobs) == 2
            jobs_by_date = {j.business_date: j for j in jobs}
            assert jobs_by_date[date(2026, 11, 20)].status == JobStatus.UNKNOWN_REQUIRES_REVIEW
            kept_job = jobs_by_date[date(2026, 11, 19)]
            assert kept_job.id == protected_job_id
            assert kept_job.attempt_count == 3
            assert kept_job.failure_class == FailureClass.UNKNOWN_OUTCOME


def test_backup_import_edge_cases_and_forward_compatibility(settings):
    """覆盖前向兼容扩展字段、缺失模板置空、软删复活、火花时间戳比较、调度租约保护与瞬态收敛。"""
    app = make_app(settings, [])
    with TestClient(app) as client:
        acct = create_account(client, "默认抖音号")
        with Session(app.state.engine) as session:
            t_revive = ContractTarget(
                account_id=acct["id"],
                nickname_snapshot="待复活好友",
                platform_user_id="uid-revive",
                deleted_at=datetime(2026, 11, 10, 8, 0, tzinfo=timezone.utc),
                spark_days=9,
                spark_kind="fire",
                spark_label="火花9天",
                spark_scanned_at=datetime(2026, 11, 18, 8, 0, tzinfo=timezone.utc),
            )
            t_conflict = ContractTarget(
                account_id=acct["id"],
                nickname_snapshot="冲突目标",
                platform_user_id="uid-conflict",
            )
            active_sched = SendSchedule(
                account_id=acct["id"],
                business_date=date(2026, 11, 19),
                status=ScheduleStatus.PENDING,
                lease_owner="worker-1",
                lease_expires_at=datetime.now(timezone.utc) + timedelta(minutes=10),
            )
            session.add(t_revive)
            session.add(t_conflict)
            session.add(active_sched)
            session.commit()
            t_revive_id = t_revive.id
            active_sched_id = active_sched.id

        payload = {
            "app": "flame-contractor",
            "schemaVersion": 1,
            "exportedAt": "2026-11-20T12:00:00Z",
            "data": {
                "accounts": [
                    {
                        "id": "default",
                        "display_name": "默认抖音号",
                        "status": "pending_login",
                        "futureField": "ignored",
                    }
                ],
                "templates": [],
                "poolItems": [],
                "targets": [
                    {
                        "id": "ext-target-revive",
                        "account_id": "default",
                        "platform_user_id": "uid-revive",
                        "nickname_snapshot": "待复活好友",
                        "template_id": "missing-image-template",
                        "spark_days": 1,
                        "spark_kind": "old",
                        "spark_label": "旧快照",
                        "spark_scanned_at": "2026-11-15T08:00:00Z",
                        "deleted_at": None,
                        "extraColumn": 123,
                    },
                    {
                        "id": "ext-target-bad-conflict",
                        "account_id": "default",
                        "platform_user_id": "uid-revive",
                        "nickname_snapshot": "冲突目标",
                    },
                ],
                "schedules": [
                    {
                        "id": "ext-sched-active",
                        "account_id": "default",
                        "business_date": "2026-11-19",
                        "status": "failed",
                    },
                    {
                        "id": "ext-sched-running",
                        "account_id": "default",
                        "business_date": "2026-11-20",
                        "status": "running",
                    },
                ],
                "jobs": [
                    {
                        "id": "ext-job-leased",
                        "account_id": "default",
                        "target_id": "ext-target-revive",
                        "template_id": "missing-image-template",
                        "business_date": "2026-11-20",
                        "message_slot_id": "daily",
                        "status": "leased",
                        "attempt_count": 1,
                        "futureJobField": True,
                    }
                ],
                "attempts": [
                    {
                        "id": "ext-attempt-running",
                        "job_id": "ext-job-leased",
                        "attempt_no": 1,
                        "stage": "sending",
                        "status": "running",
                    }
                ],
            },
        }

        resp = client.post("/api/backup/import", json=payload)
        assert resp.status_code == 200, resp.text
        assert "androidExtensions" not in client.get("/api/backup").json()

        with Session(app.state.engine) as session:
            revived = session.get(ContractTarget, t_revive_id)
            assert revived is not None
            assert revived.deleted_at is None
            assert revived.template_id is None
            assert revived.spark_days == 9
            assert revived.spark_label == "火花9天"

            kept_sched = session.get(SendSchedule, active_sched_id)
            assert kept_sched is not None
            assert kept_sched.status == ScheduleStatus.PENDING
            assert kept_sched.lease_owner == "worker-1"

            sched_20 = session.exec(
                select(SendSchedule).where(SendSchedule.business_date == date(2026, 11, 20))
            ).one()
            assert sched_20.status == ScheduleStatus.FAILED

            job = session.exec(select(SendJob)).one()
            assert job.target_id == t_revive_id
            assert job.template_id is None
            assert job.status == JobStatus.FAILED_RETRYABLE

            attempt = session.exec(select(SendAttempt)).one()
            assert attempt.job_id == job.id
            assert attempt.status == AttemptStatus.FAILED
