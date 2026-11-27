"""会话列表扫描（好友批量导入候选）与好友换绑模板的测试。"""

import asyncio
import time

from fastapi.testclient import TestClient

from tests.fakes import FakePage, make_app
from tests.test_send import _account_with_targets


def test_scan_chats_collects_all_titles(settings):
    """登录态有效：滚动揭示懒加载标题并去重收集；账号 last_verified 更新。"""
    app = make_app(
        settings,
        [
            FakePage(
                logged_in=True,
                conversation_titles=("好友A", "好友B"),
                titles_revealed_by_scroll=("好友C", "好友A"),
            )
        ],
    )
    with TestClient(app) as client:
        account = _account_with_targets(client)

        result = client.post(f"/api/accounts/{account['id']}/scan-chats")
        assert result.status_code == 200, result.text
        payload = result.json()
        assert payload["overall"] == "ok"
        # FakeItem 的 inner_text 只有名字单行：title 收齐，preview 为空
        assert [item["title"] for item in payload["entries"]] == ["好友A", "好友B", "好友C"]
        assert all(item["preview"] == "" for item in payload["entries"])

        updated = client.get("/api/accounts").json()[0]
        assert updated["lastVerifiedAt"] is not None


def test_scan_chats_requires_login(settings):
    """登录失效：overall=auth_expired；连续两次判定失败后账号状态才转 auth_expired。

    单次判定抖动不得锁死账号——调度器会跳过所有非 HEALTHY 账号的扫描与发送，
    一次误判就需要人工重新扫码才能恢复（2026-10-10 实测发生过），因此第一次
    只计数、不改状态。
    """
    app = make_app(settings, [FakePage(logged_in=False), FakePage(logged_in=False)])
    with TestClient(app) as client:
        account = _account_with_targets(client)

        first = client.post(f"/api/accounts/{account['id']}/scan-chats")
        assert first.status_code == 200, first.text
        payload = first.json()
        assert payload["overall"] == "auth_expired"
        assert payload["entries"] == []
        assert client.get("/api/accounts").json()[0]["status"] != "auth_expired"

        client.post(f"/api/accounts/{account['id']}/scan-chats")
        updated = client.get("/api/accounts").json()[0]
        assert updated["status"] == "auth_expired"


def test_scan_chats_background_progress_reaches_completion(settings):
    """后台扫描返回任务号，完成后进度快照包含真实结果与 100% 终态。"""
    app = make_app(
        settings,
        [
            FakePage(
                logged_in=True,
                conversation_titles=("好友A", "好友B"),
                titles_revealed_by_scroll=("好友C",),
            )
        ],
    )
    with TestClient(app) as client:
        account = _account_with_targets(client)

        started = client.post(f"/api/accounts/{account['id']}/scan-chats/start")
        assert started.status_code == 202, started.text
        initial = started.json()
        assert initial["taskId"]
        assert initial["accountId"] == account["id"]
        assert initial["status"] == "running"

        deadline = time.monotonic() + 5
        snapshot = initial
        while snapshot["status"] == "running" and time.monotonic() < deadline:
            time.sleep(0.01)
            response = client.get(
                f"/api/accounts/{account['id']}/scan-chats/progress",
                params={"taskId": initial["taskId"]},
            )
            assert response.status_code == 200, response.text
            snapshot = response.json()

        assert snapshot["status"] == "completed", snapshot
        assert snapshot["overall"] == "ok"
        assert snapshot["total"] == 3
        assert snapshot["completed"] == 3
        assert snapshot["entries"][-1]["title"] == "好友C"


def test_auto_scan_refreshes_existing_targets_without_importing_new_chats(settings):
    """自动扫描回写已有对象的官方快照，但不会静默新增扫描到的会话。"""
    app = make_app(
        settings,
        [
            FakePage(
                logged_in=True,
                conversation_titles=("好友A", "新会话"),
            )
        ],
    )
    with TestClient(app) as client:
        account = _account_with_targets(client)
        manager = app.state.browser_manager

        result = asyncio.run(manager.run_auto_scan(account["id"]))

        assert result["overall"] == "ok"
        assert result["sync"]["refreshed"] == ["好友A"]
        assert result["sync"]["created"] == []
        targets = client.get(
            "/api/targets", params={"accountId": account["id"]}
        ).json()
        assert len(targets) == 1
        assert targets[0]["nicknameSnapshot"] == "好友A"
        assert targets[0]["sparkLabel"] == "无火花"
        assert targets[0]["sparkScannedAt"] is not None


def test_scan_snapshot_label_does_not_confuse_missing_icon_with_no_spark(settings):
    app = make_app(settings, [FakePage(logged_in=True)])
    with TestClient(app):
        manager = app.state.browser_manager

        assert manager._spark_label_for_scan_entry(
            {"streak_days": 515, "streak_kind": None, "streak_label": None, "preview": ""}
        ) is None
        assert manager._spark_label_for_scan_entry(
            {"streak_days": None, "streak_kind": "soulmate", "streak_label": None, "preview": ""}
        ) is None
        assert manager._spark_label_for_scan_entry(
            {"streak_days": None, "streak_kind": "normal", "streak_label": "重燃中 1/3", "preview": ""}
        ) == "重燃中 1/3"
        assert manager._spark_label_for_scan_entry(
            {"streak_days": None, "streak_kind": "mate", "streak_label": "点亮中 1/3", "preview": ""}
        ) == "点亮中 1/3"
        assert manager._spark_label_for_scan_entry(
            {"streak_days": None, "streak_kind": "normal", "streak_label": "2 天后消失", "preview": ""}
        ) == "2 天后消失"
        # 严格边界：无官方火花容器时，即使 preview 含「火花」或「[续火花]」也绝不误判为官方火花
        assert manager._spark_label_for_scan_entry(
            {"streak_days": None, "streak_kind": None, "streak_label": None, "preview": "已读 · [续火花]"}
        ) == "无火花"
        assert manager._spark_label_for_scan_entry(
            {"streak_days": None, "streak_kind": None, "streak_label": None, "preview": "2 天后火花将消失"}
        ) == "无火花"
        assert manager._spark_label_for_scan_entry(
            {"streak_days": 0, "streak_kind": None, "streak_label": None, "preview": "已读 · [续火花]"}
        ) == "无火花"
        assert manager._spark_label_for_scan_entry(
            {"streak_days": None, "streak_kind": None, "streak_label": None, "preview": "[比心]"}
        ) == "无火花"


def test_scan_all_chats_extracts_all_flame_kinds_and_never_infers_spark_from_preview():
    from app.execution.douyin_chat import DouyinChat, parse_flame_icon_kind
    from app.execution.selectors import (
        CONVERSATION_ITEM,
        CONVERSATION_STREAK,
        CONVERSATION_STREAK_DAYS,
        CONVERSATION_TITLE,
    )

    class _IconEl:
        def __init__(self, src: str | None):
            self._src = src

        async def get_attribute(self, name: str):
            return self._src if name == "src" else None

    class _TextEl:
        def __init__(self, text: str):
            self._text = text

        async def inner_text(self) -> str:
            return self._text

    class _StreakEl:
        def __init__(
            self,
            *,
            days_text: str | None = None,
            container_text: str = "",
            icon_src: str | None = None,
            has_icon: bool = False,
        ):
            self._days_text = days_text
            self._container_text = container_text
            self._icon_src = icon_src
            self._has_icon = has_icon or icon_src is not None

        async def query_selector(self, selector: str):
            if selector == CONVERSATION_STREAK_DAYS:
                return _TextEl(self._days_text) if self._days_text is not None else None
            if selector == "img[src*='flame_icon']":
                return _IconEl(self._icon_src) if self._has_icon else None
            return None

        async def inner_text(self) -> str:
            return self._container_text

    class _RichItem:
        def __init__(self, title: str, full_text: str, streak_el: _StreakEl | None = None):
            self._title = title
            self._full_text = full_text
            self._streak_el = streak_el

        async def query_selector(self, selector: str):
            if selector == CONVERSATION_TITLE:
                return _TextEl(self._title)
            if selector == CONVERSATION_STREAK:
                return self._streak_el
            return None

        async def inner_text(self) -> str:
            return self._full_text

    class _RichScanPage(FakePage):
        def __init__(self, items):
            super().__init__(logged_in=True, conversation_titles=())
            self._rich_items = items

        async def query_selector_all(self, selector: str):
            if selector == CONVERSATION_ITEM:
                return list(self._rich_items)
            return []

        async def evaluate(self, expression: str, target=None, *args, **kwargs):
            if "indexedDB" in expression:
                return []
            if "atBottom" in expression:
                return {"scrollTop": 0, "clientHeight": 500, "scrollHeight": 500, "atBottom": True}
            return None

    # 覆盖全部 6 类官方图标目录、大小写/查询串及未知 flame_icon 兜底
    assert parse_flame_icon_kind("https://cdn.example/flame_icon/couple/level_1.png?a=1") == "couple"
    assert parse_flame_icon_kind("https://cdn.example/flame_icon/family/gray_1.png") == "family"
    assert parse_flame_icon_kind("https://cdn.example/flame_icon/SOULMATE/level_2.png") == "soulmate"
    assert parse_flame_icon_kind("https://cdn.example/flame_icon/mate/level_1.png") == "mate"
    assert parse_flame_icon_kind("https://cdn.example/flame_icon/bosom/level_3.png") == "bosom"
    assert parse_flame_icon_kind("https://cdn.example/flame_icon/normal/level_1.png") == "normal"
    assert parse_flame_icon_kind("https://cdn.example/flame_icon/new_type/level_1.png") == "normal"
    assert parse_flame_icon_kind("https://cdn.example/avatar/couple/1.png") is None
    assert parse_flame_icon_kind(None) is None

    items = [
        _RichItem("预览误导好友", "预览误导好友\n昨天\n已读 · [续火花]", streak_el=None),
        _RichItem(
            "密友仅图标",
            "密友仅图标\n今天\n早安",
            streak_el=_StreakEl(icon_src="https://cdn.example/flame_icon/soulmate/level_1.png"),
        ),
        _RichItem(
            "搭子点亮中",
            "搭子点亮中\n点亮中 1/3\n今天\n你好",
            streak_el=_StreakEl(
                days_text=None,
                container_text="点亮中\n1/3",
                icon_src="https://cdn.example/flame_icon/mate/level_1.png",
            ),
        ),
        _RichItem(
            "挚友重燃中",
            "挚友重燃中\n重燃中 1/3\n今天\n续一下",
            streak_el=_StreakEl(
                days_text="重燃中 1/3",
                icon_src="https://cdn.example/flame_icon/bosom/gray_1.png",
            ),
        ),
        _RichItem(
            "情侣倒计时",
            "情侣倒计时\n2 天后消失\n昨天\n晚安",
            streak_el=_StreakEl(
                days_text="2 天后消失",
                icon_src="https://cdn.example/flame_icon/couple/gray_2.png",
            ),
        ),
        _RichItem(
            "家人数字火花",
            "家人数字火花\n88\n今天\n吃了没",
            streak_el=_StreakEl(
                days_text="88",
                icon_src="https://cdn.example/flame_icon/family/level_2.png",
            ),
        ),
        _RichItem(
            "未知目录图标兜底",
            "未知目录图标兜底\n今天\n在吗",
            streak_el=_StreakEl(
                icon_src="https://cdn.example/flame_icon/future_badge/v1.png",
            ),
        ),
    ]

    async def scenario():
        chat = DouyinChat(_RichScanPage(items), sleep=lambda _: asyncio.sleep(0))
        return await chat.scan_all_chats()

    by_title = {row["title"]: row for row in asyncio.run(scenario())}

    assert by_title["预览误导好友"]["preview"] == "已读 · [续火花]"
    assert by_title["预览误导好友"]["streak_days"] is None
    assert by_title["预览误导好友"]["streak_kind"] is None
    assert by_title["预览误导好友"]["streak_label"] is None

    assert by_title["密友仅图标"]["streak_days"] is None
    assert by_title["密友仅图标"]["streak_kind"] == "soulmate"
    assert by_title["密友仅图标"]["streak_label"] is None

    assert by_title["搭子点亮中"]["streak_days"] is None
    assert by_title["搭子点亮中"]["streak_kind"] == "mate"
    assert by_title["搭子点亮中"]["streak_label"] == "点亮中 1/3"

    assert by_title["挚友重燃中"]["streak_days"] is None
    assert by_title["挚友重燃中"]["streak_kind"] == "bosom"
    assert by_title["挚友重燃中"]["streak_label"] == "重燃中 1/3"

    assert by_title["情侣倒计时"]["streak_days"] is None
    assert by_title["情侣倒计时"]["streak_kind"] == "couple"
    assert by_title["情侣倒计时"]["streak_label"] == "2 天后消失"

    assert by_title["家人数字火花"]["streak_days"] == 88
    assert by_title["家人数字火花"]["streak_kind"] == "family"
    assert by_title["家人数字火花"]["streak_label"] is None

    assert by_title["未知目录图标兜底"]["streak_days"] is None
    assert by_title["未知目录图标兜底"]["streak_kind"] == "normal"
    assert by_title["未知目录图标兜底"]["streak_label"] is None


def test_auto_scan_with_spark_preview_marks_no_spark_and_preserves_local_streak_days(settings):
    """无官方容器但 preview 含「已读 · [续火花]」：自动同步记为无火花且绝不覆写本地 streakDays。"""
    app = make_app(settings, [FakePage(logged_in=True)])
    with TestClient(app) as client:
        account = _account_with_targets(client)
        manager = app.state.browser_manager

        async def fake_scan(_account_id, progress=None):
            return {
                "overall": "ok",
                "detail": None,
                "entries": [
                    {
                        "title": "好友A",
                        "preview": "已读 · [续火花]",
                        "streak_days": None,
                        "streak_kind": None,
                        "streak_label": None,
                    }
                ],
            }

        manager._scan_chats_impl = fake_scan
        result = asyncio.run(manager.run_auto_scan(account["id"]))

        assert result["overall"] == "ok"
        assert result["reconciliation"]["counts"]["no_spark"] == 1
        assert result["reconciliation"]["entries"][0]["current"] == {
            "days": None,
            "kind": None,
            "label": "无火花",
            "scannedAt": None,
        }
        target = client.get("/api/targets", params={"accountId": account["id"]}).json()[0]
        assert target["sparkDays"] is None
        assert target["sparkKind"] is None
        assert target["sparkLabel"] == "无火花"
        assert target["streakDays"] == 5


def test_cancel_scan_preserves_discovered_entries(settings):
    """取消后台扫描只结束任务，不丢失取消前已经持久化的候选会话。"""
    app = make_app(settings, [FakePage(logged_in=True)])
    with TestClient(app) as client:
        account = _account_with_targets(client)
        manager = app.state.browser_manager

        async def blocked_scan(account_id, progress=None):
            assert progress is not None
            manager._update_scan_progress(
                progress.task_id,
                stage="滚动读取会话",
                completed=1,
                current_item="好友A",
                round=2,
                entries=[
                    {
                        "title": "好友A",
                        "preview": "",
                        "streak_days": 3,
                        "streak_kind": "normal",
                        "streak_label": None,
                        "platform_user_id": "uid-a",
                    }
                ],
            )
            await asyncio.Event().wait()

        manager._scan_chats_impl = blocked_scan
        started = client.post(f"/api/accounts/{account['id']}/scan-chats/start")
        assert started.status_code == 202, started.text
        task_id = started.json()["taskId"]
        time.sleep(0.03)

        cancelled = client.post(
            f"/api/accounts/{account['id']}/scan-chats/cancel",
            params={"taskId": task_id},
        )
        assert cancelled.status_code == 200, cancelled.text
        payload = cancelled.json()
        assert payload["status"] == "cancelled"
        assert payload["completed"] == 1
        assert payload["entries"][0]["title"] == "好友A"
        assert "已保留已发现结果" in payload["detail"]

        latest = client.get(f"/api/accounts/{account['id']}/scan-chats/latest")
        assert latest.status_code == 200, latest.text
        assert latest.json()["status"] == "cancelled"
        assert latest.json()["entries"][0]["platformUserId"] == "uid-a"


def test_scan_tasks_are_isolated_by_account(settings):
    """不同账号可并行扫描，但同一账号仍保持单任务互斥。"""
    app = make_app(settings, [FakePage(logged_in=True), FakePage(logged_in=True)])
    with TestClient(app) as client:
        first_account = _account_with_targets(client)
        second_account = client.post(
            "/api/accounts", json={"displayName": "第二账号"}
        ).json()
        manager = app.state.browser_manager

        async def blocked_scan(account_id, progress=None):
            assert progress is not None
            manager._update_scan_progress(
                progress.task_id,
                stage="滚动读取会话",
                current_item=account_id,
            )
            await asyncio.Event().wait()

        manager._scan_chats_impl = blocked_scan
        first = client.post(f"/api/accounts/{first_account['id']}/scan-chats/start")
        second = client.post(f"/api/accounts/{second_account['id']}/scan-chats/start")
        assert first.status_code == 202, first.text
        assert second.status_code == 202, second.text

        same_account = client.post(f"/api/accounts/{first_account['id']}/scan-chats/start")
        assert same_account.status_code == 409, same_account.text
        send_conflict = client.post(f"/api/accounts/{first_account['id']}/send")
        assert send_conflict.status_code == 409, send_conflict.text
        assert "扫描" in send_conflict.json()["detail"]

        first_cancel = client.post(
            f"/api/accounts/{first_account['id']}/scan-chats/cancel",
            params={"taskId": first.json()["taskId"]},
        )
        second_cancel = client.post(
            f"/api/accounts/{second_account['id']}/scan-chats/cancel",
            params={"taskId": second.json()["taskId"]},
        )
        assert first_cancel.json()["status"] == "cancelled"
        assert second_cancel.json()["status"] == "cancelled"


def test_scan_is_blocked_while_account_is_sending(settings):
    """同账号已有发送进度时，不允许再启动会话扫描。"""
    app = make_app(settings, [FakePage(logged_in=True)])
    with TestClient(app) as client:
        account = _account_with_targets(client)
        app.state.browser_manager._start_send_progress(account["id"], 1)

        conflict = client.post(f"/api/accounts/{account['id']}/scan-chats/start")
        assert conflict.status_code == 409, conflict.text
        assert "发送" in conflict.json()["detail"]


def test_patch_target_template_rebind(client):
    """换绑模板：合法 id 生效；不存在 404；null 解绑。"""
    account = client.post("/api/accounts", json={"displayName": "主账号"}).json()
    t1 = client.post("/api/templates", json={"name": "T1", "content": "文案一"}).json()
    t2 = client.post("/api/templates", json={"name": "T2", "content": "文案二"}).json()
    target = client.post(
        "/api/targets",
        json={"accountId": account["id"], "nicknameSnapshot": "好友A", "templateId": t1["id"]},
    ).json()

    rebound = client.patch(f"/api/targets/{target['id']}", json={"templateId": t2["id"]})
    assert rebound.status_code == 200, rebound.text
    assert rebound.json()["templateId"] == t2["id"]

    unbound = client.patch(f"/api/targets/{target['id']}", json={"templateId": None})
    assert unbound.status_code == 200, unbound.text
    assert unbound.json()["templateId"] is None

    ghost = client.patch(f"/api/targets/{target['id']}", json={"templateId": "ghost"})
    assert ghost.status_code == 404


def test_parse_flame_lit_today_and_scheduled_skip_official_lit_pipeline(settings):
    """核验 parse_flame_lit_today 保守判定、/api/scheduler/settings 开关读写与同会话预检跳过逻辑。"""
    from app.execution.douyin_chat import parse_flame_lit_today
    from app.models import JobStatus, SendJob
    from datetime import datetime
    from sqlmodel import Session
    from zoneinfo import ZoneInfo

    assert (
        parse_flame_lit_today(
            "https://lf3-static.bytednsdoc.com/obj/eden-cn/flame_icon/normal/normal_2.png",
            15,
            None,
        )
        is True
    )
    assert (
        parse_flame_lit_today(
            "https://lf3-static.bytednsdoc.com/obj/eden-cn/flame_icon/couple/gray_couple_2.png",
            15,
            None,
        )
        is False
    )
    assert (
        parse_flame_lit_today(
            "https://lf3-static.bytednsdoc.com/obj/eden-cn/flame_icon/normal/normal_1.png",
            None,
            "重燃中 1/3",
        )
        is None
    )
    assert (
        parse_flame_lit_today(
            "https://lf3-static.bytednsdoc.com/obj/eden-cn/flame_icon/normal/normal_1.png",
            5,
            "2 天后消失",
        )
        is None
    )
    assert parse_flame_lit_today("https://example.com/avatar.png", 10, None) is None

    app = make_app(settings, [FakePage(logged_in=True), FakePage(logged_in=True)])
    with TestClient(app) as client:
        # 1) API 读写：默认关闭，可 PATCH 开关持久化
        status_default = client.get("/api/scheduler")
        assert status_default.status_code == 200, status_default.text
        assert status_default.json()["scheduledSkipOfficialLitToday"] is False

        patched = client.patch(
            "/api/scheduler/settings",
            json={"scheduledSkipOfficialLitToday": True},
        )
        assert patched.status_code == 200, patched.text
        assert patched.json()["scheduledSkipOfficialLitToday"] is True

        # 2) 同会话预检跳过：已亮跳过（不调用 _send_one）、灰火与未观测正常发、待复核不被跳过掩盖
        account = client.post("/api/accounts", json={"displayName": "主账号"}).json()
        tpl = client.post("/api/templates", json={"name": "续火", "content": "早安"}).json()
        t_lit = client.post(
            "/api/targets",
            json={
                "accountId": account["id"],
                "nicknameSnapshot": "已亮好友",
                "platformUserId": "uid-lit",
                "templateId": tpl["id"],
            },
        ).json()
        t_gray = client.post(
            "/api/targets",
            json={
                "accountId": account["id"],
                "nicknameSnapshot": "灰火好友",
                "platformUserId": "uid-gray",
                "templateId": tpl["id"],
            },
        ).json()
        t_unknown = client.post(
            "/api/targets",
            json={
                "accountId": account["id"],
                "nicknameSnapshot": "待复核好友",
                "platformUserId": "uid-unk",
                "templateId": tpl["id"],
            },
        ).json()

        with Session(app.state.engine) as session:
            today = datetime.now(ZoneInfo("Asia/Shanghai")).date()
            session.add(
                SendJob(
                    account_id=account["id"],
                    target_id=t_unknown["id"],
                    business_date=today,
                    message_slot_id=f"{tpl['id']}:v1",
                    idempotency_key=f"{account['id']}:{t_unknown['id']}:{today}:v1",
                    status=JobStatus.UNKNOWN_REQUIRES_REVIEW,
                )
            )
            session.commit()

        manager = app.state.browser_manager
        sent_targets: list[str] = []

        async def fake_send_one(chat, acc, target, business_date, **kwargs):
            sent_targets.append(target.nickname_snapshot)
            if target.id == t_unknown["id"]:
                return {
                    "target": target.nickname_snapshot,
                    "status": "duplicate_unknown",
                    "detail": "今日已有结果未确认，强制发送仍跳过以防重复",
                    "failure_class": None,
                    "message": None,
                }
            return {
                "target": target.nickname_snapshot,
                "status": "succeeded",
                "detail": None,
                "failure_class": None,
                "message": "早安",
            }

        async def fake_confirm_session(_chat, _account_id):
            return "ok"

        class _PreScanChat:
            def __init__(self, fail_scan: bool = False):
                self._fail_scan = fail_scan

            async def open_chat_page(self):
                return None

            async def scan_all_chats(self):
                if self._fail_scan:
                    raise RuntimeError("pre-scan boom")
                return [
                    {
                        "title": "已亮好友",
                        "platform_user_id": "uid-lit",
                        "spark_lit_today": True,
                    },
                    {
                        "title": "灰火好友",
                        "platform_user_id": "uid-gray",
                        "spark_lit_today": False,
                    },
                    {
                        "title": "待复核好友",
                        "platform_user_id": "uid-unk",
                        "spark_lit_today": True,
                    },
                ]

        manager._confirm_session = fake_confirm_session
        manager._send_one = fake_send_one
        manager._make_chat = lambda _page: _PreScanChat(fail_scan=False)

        res = asyncio.run(manager.run_send(account["id"], skip_official_lit_today=True))
        by_name = {item["target"]: item for item in res["results"]}
        assert by_name["已亮好友"]["status"] == "skipped"
        assert by_name["已亮好友"]["skip_code"] == "official_lit_today"
        assert by_name["灰火好友"]["status"] == "succeeded"
        assert by_name["待复核好友"]["status"] == "duplicate_unknown"
        assert sent_targets == ["灰火好友", "待复核好友"]

        # 3) 预检抛异常时保守回退：全部照常进入发送路径，绝不误跳过
        sent_targets.clear()
        manager._make_chat = lambda _page: _PreScanChat(fail_scan=True)
        fallback = asyncio.run(manager.run_send(account["id"], skip_official_lit_today=True))
        assert fallback["overall"] in ("ok", "partial")
        assert sent_targets == ["已亮好友", "灰火好友", "待复核好友"]
