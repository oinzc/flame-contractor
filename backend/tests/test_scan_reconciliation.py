"""M11 扫描对账：纯分类、持久化快照与历史查询。"""

import time
from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient
from sqlmodel import Session

from app.models import Account, ContractTarget, ScanTask, ScanTaskStatus, User
from app.scan_reconciliation import build_reconciliation
from tests.fakes import FakePage, make_app
from tests.test_send import _account_with_targets


def _previous(days: int | None, label: str | None = None) -> dict:
    return {
        "days": days,
        "kind": "normal" if days is not None else None,
        "label": label,
        "scannedAt": "2026-09-10T10:00:00+00:00" if days is not None else None,
    }


def _target(
    target_id: str,
    title: str,
    *,
    days: int | None,
    user_id: str | None = None,
    conversation_id: str | None = None,
    conversation_type: str | None = "user",
) -> dict:
    return {
        "targetId": target_id,
        "title": title,
        "conversationType": conversation_type,
        "platformUserId": user_id,
        "platformConversationId": conversation_id,
        "previous": _previous(days),
    }


def _entry(
    title: str,
    *,
    days: int | None,
    user_id: str | None = None,
    conversation_id: str | None = None,
    conversation_type: str | None = "user",
    preview: str = "",
) -> dict:
    return {
        "title": title,
        "platform_user_id": user_id,
        "platform_conversation_id": conversation_id,
        "conversation_type": conversation_type,
        "streak_days": days,
        "streak_kind": "normal" if days is not None else None,
        "streak_label": None,
        "preview": preview,
    }


def test_build_reconciliation_covers_five_categories_and_strict_matching():
    targets = [
        _target("changed-id", "状态变化", days=5),
        _target("no-spark-id", "熄火好友", days=8),
        _target("same-id", "无变化", days=3),
        _target("missing-id", "未观察好友", days=2),
        _target("group-id", "群聊", days=10, user_id=None, conversation_id="conv-1", conversation_type="group"),
    ]
    entries = [
        _entry("状态变化", days=6),
        _entry("熄火好友", days=None),
        _entry("无变化", days=3),
        _entry("群聊", days=11, conversation_id="conv-1", conversation_type="group"),
        _entry("新会话", days=4),
    ]

    result = build_reconciliation(targets, entries)

    assert result["counts"] == {
        "changed": 2,
        "no_spark": 1,
        "new": 1,
        "not_observed": 1,
        "unchanged": 1,
    }
    by_category = {item["category"]: item for item in result["entries"]}
    changed = [item for item in result["entries"] if item["category"] == "changed"]
    assert {item["previous"]["days"] for item in changed} == {5, 10}
    assert {item["current"]["days"] for item in changed} == {6, 11}
    assert by_category["no_spark"]["current"]["label"] == "无火花"
    assert by_category["new"]["targetId"] is None
    assert by_category["not_observed"]["current"]["days"] is None


def test_reconciliation_prefers_stable_user_and_group_ids_over_nickname():
    targets = [
        _target("user-target", "重名用户", days=1, user_id="uid-a"),
        _target(
            "group-target",
            "同名群",
            days=1,
            conversation_id="conv-a",
            conversation_type="group",
        ),
    ]
    result = build_reconciliation(
        targets,
        [
            _entry("重名用户", days=2, user_id="uid-a"),
            # Same title but a different stable user is not the saved target.
            _entry("重名用户", days=9, user_id="uid-b"),
            _entry("同名群", days=2, conversation_id="conv-a", conversation_type="group"),
            # Different group ID cannot fall back to the same title.
            _entry("同名群", days=9, conversation_id="conv-b", conversation_type="group"),
        ],
    )
    assert result["counts"]["changed"] == 2
    assert result["counts"]["new"] == 2
    assert result["counts"]["not_observed"] == 0
    new_titles = [item["title"] for item in result["entries"] if item["category"] == "new"]
    assert new_titles == ["重名用户", "同名群"]


def test_reconciliation_is_persisted_before_manual_sync_and_exposed_in_progress(settings):
    app = make_app(settings, [FakePage(logged_in=True)])
    with TestClient(app) as client:
        account = _account_with_targets(client)
        manager = app.state.browser_manager

        async def fake_scan(account_id, progress=None):
            return {
                "overall": "ok",
                "detail": None,
                "entries": [_entry("好友A", days=6), _entry("新会话", days=2)],
            }

        manager._scan_chats_impl = fake_scan
        started = client.post(f"/api/accounts/{account['id']}/scan-chats/start")
        assert started.status_code == 202, started.text
        task_id = started.json()["taskId"]
        for _ in range(100):
            snapshot = client.get(
                f"/api/accounts/{account['id']}/scan-chats/progress",
                params={"taskId": task_id},
            ).json()
            if snapshot["status"] != "running":
                break
            time.sleep(0.01)
        assert snapshot["status"] == "completed", snapshot
        assert snapshot["reconciliation"]["counts"]["changed"] == 1
        assert snapshot["reconciliation"]["counts"]["new"] == 1
        assert snapshot["reconciliation"]["counts"]["notObserved"] == 0

        # The current task has not performed the frontend's manual status sync
        # yet, so the target still carries its previous official snapshot.
        target = client.get("/api/targets", params={"accountId": account["id"]}).json()[0]
        assert target["sparkDays"] is None


def test_failed_scan_has_no_not_observed_reconciliation(settings):
    app = make_app(settings, [FakePage(logged_in=True)])
    with TestClient(app) as client:
        account = _account_with_targets(client)
        manager = app.state.browser_manager

        async def failed_scan(_account_id, progress=None):
            return {"overall": "error", "detail": "network", "entries": []}

        manager._scan_chats_impl = failed_scan
        started = client.post(f"/api/accounts/{account['id']}/scan-chats/start").json()
        for _ in range(100):
            snapshot = client.get(
                f"/api/accounts/{account['id']}/scan-chats/progress",
                params={"taskId": started["taskId"]},
            ).json()
            if snapshot["status"] != "running":
                break
            time.sleep(0.01)
        assert snapshot["status"] == "failed"
        assert snapshot["reconciliation"]["counts"]["notObserved"] == 0
        assert snapshot["reconciliation"]["entries"] == []


def test_scan_history_is_lightweight_sorted_limited_and_user_isolated(settings):
    app = make_app(settings, [])
    with TestClient(app) as client:
        account = _account_with_targets(client)
        # Create three persisted tasks directly, including an old task with the
        # migration's empty default to prove it is not backfilled dynamically.
        with Session(app.state.engine) as session:
            now = datetime.now(timezone.utc)
            for index in range(3):
                session.add(
                    ScanTask(
                        account_id=account["id"],
                        source="manual" if index != 1 else "automatic",
                        status=ScanTaskStatus.COMPLETED,
                        total=1,
                        completed=1,
                        overall="ok",
                        entries_json='[{"title":"不应返回"}]',
                        reconciliation_json=(
                            '{"counts":{"changed":1,"no_spark":0,"new":0,"not_observed":0,"unchanged":0},'
                            '"entries":[]}'
                            if index == 0
                            else '{"counts":{"changed":0,"no_spark":0,"new":0,"not_observed":0,"unchanged":0},"entries":[]}'
                        ),
                        started_at=now - timedelta(hours=index),
                        finished_at=now - timedelta(hours=index),
                        updated_at=now - timedelta(hours=index),
                    )
                )
            session.commit()

        history = client.get(
            f"/api/accounts/{account['id']}/scan-chats/history",
            params={"limit": 2},
        )
        assert history.status_code == 200, history.text
        rows = history.json()
        assert len(rows) == 2
        assert rows[0]["reconciliation"]["counts"]["changed"] == 1
        assert "entries" not in rows[0]  # no full entries_json leakage
        assert rows[1]["reconciliation"]["counts"]["changed"] == 0

        assert client.get(
            f"/api/accounts/{account['id']}/scan-chats/history",
            params={"limit": 0},
        ).status_code == 422
        assert client.get(
            f"/api/accounts/{account['id']}/scan-chats/history",
            params={"limit": 21},
        ).status_code == 422

        # A second platform user cannot read this account's scan history.
        client.post(
            "/api/auth/users",
            json={"username": "other", "password": "other1234"},
        ).json()
        login = client.post(
            "/api/auth/login",
            json={"username": "other", "password": "other1234"},
        ).json()
        response = client.get(
            f"/api/accounts/{account['id']}/scan-chats/history",
            headers={"Authorization": f"Bearer {login['token']}"},
        )
        assert response.status_code == 404


def test_collect_spark_deteriorations_filters_true_regressions_only():
    from app.scan_reconciliation import collect_spark_deteriorations

    reconciliation = {
        "entries": [
            # 1) 正常数字火花 -> 消失倒计时（告警）
            {
                "targetId": "t-warn",
                "title": "小满",
                "category": "changed",
                "previous": {"days": 100, "kind": "normal", "label": None},
                "current": {"days": None, "kind": "normal", "label": "2 天后消失"},
            },
            # 2) 消失倒计时进一步缩短（告警）
            {
                "targetId": "t-shorter",
                "title": "阿哲",
                "category": "changed",
                "previous": {"days": None, "kind": "normal", "label": "2 天后消失"},
                "current": {"days": None, "kind": "normal", "label": "1 天后消失"},
            },
            # 3) 消失倒计时跌入重燃（告警）
            {
                "targetId": "t-rekindle",
                "title": "林间",
                "category": "changed",
                "previous": {"days": None, "kind": "normal", "label": "1 天后消失"},
                "current": {"days": None, "kind": "normal", "label": "重燃中 1/3"},
            },
            # 4) 原有火花 -> 观察到无火花（告警）
            {
                "targetId": "t-lost",
                "title": "豆豆",
                "category": "no_spark",
                "previous": {"days": 63, "kind": "normal", "label": None},
                "current": {"days": None, "kind": None, "label": "无火花"},
            },
            # 5) 首次观测即处于濒危文字（告警）
            {
                "targetId": "t-first-danger",
                "title": "橘子",
                "category": "changed",
                "previous": {"days": None, "kind": None, "label": None},
                "current": {"days": None, "kind": "normal", "label": "1 天后消失"},
            },
            # --- 以下全部不应告警 ---
            # 正常 +1
            {
                "targetId": "t-up",
                "title": "正常增长",
                "category": "changed",
                "previous": {"days": 267, "kind": "normal", "label": None},
                "current": {"days": 268, "kind": "normal", "label": None},
            },
            # 重燃正向恢复 1/3 -> 2/3
            {
                "targetId": "t-recovering",
                "title": "正向重燃",
                "category": "changed",
                "previous": {"days": None, "kind": "normal", "label": "重燃中 1/3"},
                "current": {"days": None, "kind": "normal", "label": "重燃中 2/3"},
            },
            # 从消失倒计时救回数字火花
            {
                "targetId": "t-saved",
                "title": "已救回",
                "category": "changed",
                "previous": {"days": None, "kind": "normal", "label": "1 天后消失"},
                "current": {"days": 97, "kind": "normal", "label": None},
            },
            # 原先就没火花 -> 依然无火花
            {
                "targetId": "t-never",
                "title": "本无火花",
                "category": "no_spark",
                "previous": {"days": None, "kind": None, "label": None},
                "current": {"days": None, "kind": None, "label": "无火花"},
            },
            # 上次已报过 1 天后消失，本次未变化 unchanged（防重复刷屏）
            {
                "targetId": "t-same",
                "title": "同态不刷屏",
                "category": "unchanged",
                "previous": {"days": None, "kind": "normal", "label": "1 天后消失"},
                "current": {"days": None, "kind": "normal", "label": "1 天后消失"},
            },
            # 未滚动观察到 not_observed
            {
                "targetId": "t-unseen",
                "title": "未滚到",
                "category": "not_observed",
                "previous": {"days": 50, "kind": "normal", "label": None},
                "current": {"days": None, "kind": None, "label": None},
            },
            # 已停用好友（不在 enabled_target_ids 中）
            {
                "targetId": "t-disabled",
                "title": "已停用好友",
                "category": "no_spark",
                "previous": {"days": 88, "kind": "normal", "label": None},
                "current": {"days": None, "kind": None, "label": "无火花"},
            },
        ]
    }
    enabled = {
        "t-warn",
        "t-shorter",
        "t-rekindle",
        "t-lost",
        "t-first-danger",
        "t-up",
        "t-recovering",
        "t-saved",
        "t-never",
        "t-same",
        "t-unseen",
    }
    alerts = collect_spark_deteriorations(reconciliation, enabled_target_ids=enabled)
    assert [item["targetId"] for item in alerts] == [
        "t-warn",
        "t-shorter",
        "t-rekindle",
        "t-lost",
        "t-first-danger",
    ]
    assert "100天" in alerts[0]["summary"] and "2 天后消失" in alerts[0]["summary"]
    assert "63天" in alerts[3]["summary"] and "无火花" in alerts[3]["summary"]


def test_collect_spark_deteriorations_avoids_false_positives_on_chat_previews_and_rekindle_from_zero():
    """回归：原无火花进入重燃、普通聊天预览含“火花”字样或消失倒计时长文案均不得误判。"""
    from app.scan_reconciliation import collect_spark_deteriorations

    reconciliation = {
        "entries": [
            # 1) 消失倒计时同义长文案缩短：2 天后火花将消失 -> 1 天后火花将消失（应告警）
            {
                "targetId": "t-long-shorter",
                "title": "长文案缩短",
                "category": "changed",
                "previous": {"days": None, "kind": "normal", "label": "2 天后火花将消失"},
                "current": {"days": None, "kind": "normal", "label": "1 天后火花将消失"},
            },
            # 2) 消失倒计时同义长文案延长/恢复：1 天后火花将消失 -> 2 天后火花将消失（不应告警）
            {
                "targetId": "t-long-longer",
                "title": "长文案恢复",
                "category": "changed",
                "previous": {"days": None, "kind": "normal", "label": "1 天后火花将消失"},
                "current": {"days": None, "kind": "normal", "label": "2 天后火花将消失"},
            },
            # 3) 原先已落库为「无火花」的好友开始互动进入「重燃中 1/3」（正向新建/恢复，不应告警）
            {
                "targetId": "t-zero-to-rekindle",
                "title": "从零重燃",
                "category": "changed",
                "previous": {"days": None, "kind": None, "label": "无火花"},
                "current": {"days": None, "kind": "normal", "label": "重燃中 1/3"},
            },
            # 4) 原无火花好友发了一条含“火花”字样的普通聊天消息（不应告警）
            {
                "targetId": "t-chat-preview-in",
                "title": "普通聊天提火花",
                "category": "changed",
                "previous": {"days": None, "kind": None, "label": "无火花"},
                "current": {"days": None, "kind": None, "label": "今天一起养火花吧"},
            },
            # 5) 首次扫描无徽标但最新消息预览含“火花”（不应告警）
            {
                "targetId": "t-first-chat-preview",
                "title": "首次普通消息含火花",
                "category": "changed",
                "previous": {"days": None, "kind": None, "label": None},
                "current": {"days": None, "kind": None, "label": "聊聊火花"},
            },
            # 6) 上一轮仅是含“火花”的普通聊天预览（kind=None），本轮发了 [比心] 回落为 no_spark（不应误报断火）
            {
                "targetId": "t-chat-preview-out",
                "title": "普通消息切回比心",
                "category": "no_spark",
                "previous": {"days": None, "kind": None, "label": "聊聊火花"},
                "current": {"days": None, "kind": None, "label": "无火花"},
            },
            # 7) 原先无火花进入「点亮中 1/3」（正向新建火花，不应告警）
            {
                "targetId": "t-igniting",
                "title": "新建点亮中",
                "category": "changed",
                "previous": {"days": None, "kind": None, "label": "无火花"},
                "current": {"days": None, "kind": "mate", "label": "点亮中 1/3"},
            },
            # 8) 仅图标官方火花熄灭 -> 应告警并展示种类摘要
            {
                "targetId": "t-icon-only-lost",
                "title": "情侣仅图标断火",
                "category": "no_spark",
                "previous": {"days": None, "kind": "couple", "label": None},
                "current": {"days": None, "kind": None, "label": "无火花"},
            },
        ]
    }
    alerts = collect_spark_deteriorations(reconciliation)
    assert [item["targetId"] for item in alerts] == ["t-long-shorter", "t-icon-only-lost"]
    assert alerts[1]["summary"] == "情侣火花 → 无火花"


def test_reconciliation_rejects_spark_preview_false_positive_and_classifies_all_official_states():
    targets = [
        _target("t-preview-only", "预览含续火花好友", days=None),
        _target("t-rekindle", "重燃好友", days=20),
        _target("t-ignite", "点亮好友", days=None),
        _target("t-disappear", "消失倒计时好友", days=30),
        _target("t-icon-only", "仅图标密友", days=None),
    ]
    entries = [
        # 无官方火花容器，仅最后一条消息预览含 [续火花] -> 必须归类为 no_spark / 无火花
        {
            "title": "预览含续火花好友",
            "streak_days": None,
            "streak_kind": None,
            "streak_label": None,
            "preview": "已读 · [续火花]",
        },
        # 新会话同理：preview 含 [续火花] 绝不带官方火花状态
        {
            "title": "新会话预览续火花",
            "streak_days": None,
            "streak_kind": None,
            "streak_label": None,
            "preview": "已读 · [续火花]",
        },
        {
            "title": "重燃好友",
            "streak_days": None,
            "streak_kind": "normal",
            "streak_label": "重燃中 1/3",
            "preview": "早安",
        },
        {
            "title": "点亮好友",
            "streak_days": None,
            "streak_kind": "mate",
            "streak_label": "点亮中 1/3",
            "preview": "你好",
        },
        {
            "title": "消失倒计时好友",
            "streak_days": None,
            "streak_kind": "couple",
            "streak_label": "3 天后消失",
            "preview": "晚安",
        },
        {
            "title": "仅图标密友",
            "streak_days": None,
            "streak_kind": "soulmate",
            "streak_label": None,
            "preview": "",
        },
        {
            "title": "新会话挚友仅图标",
            "streak_days": None,
            "streak_kind": "bosom",
            "streak_label": None,
            "preview": "",
        },
    ]

    result = build_reconciliation(targets, entries)
    by_title = {item["title"]: item for item in result["entries"]}

    assert result["counts"] == {
        "changed": 4,
        "no_spark": 1,
        "new": 2,
        "not_observed": 0,
        "unchanged": 0,
    }
    assert by_title["预览含续火花好友"]["category"] == "no_spark"
    assert by_title["预览含续火花好友"]["current"] == {
        "days": None,
        "kind": None,
        "label": "无火花",
        "scannedAt": None,
    }
    assert by_title["新会话预览续火花"]["category"] == "new"
    assert by_title["新会话预览续火花"]["current"] == {
        "days": None,
        "kind": None,
        "label": "无火花",
        "scannedAt": None,
    }
    assert by_title["重燃好友"]["category"] == "changed"
    assert by_title["重燃好友"]["current"]["label"] == "重燃中 1/3"
    assert by_title["点亮好友"]["category"] == "changed"
    assert by_title["点亮好友"]["current"]["label"] == "点亮中 1/3"
    assert by_title["消失倒计时好友"]["category"] == "changed"
    assert by_title["消失倒计时好友"]["current"]["label"] == "3 天后消失"
    assert by_title["仅图标密友"]["category"] == "changed"
    assert by_title["仅图标密友"]["current"] == {
        "days": None,
        "kind": "soulmate",
        "label": None,
        "scannedAt": None,
    }
    assert by_title["新会话挚友仅图标"]["category"] == "new"
    assert by_title["新会话挚友仅图标"]["current"] == {
        "days": None,
        "kind": "bosom",
        "label": None,
        "scannedAt": None,
    }
