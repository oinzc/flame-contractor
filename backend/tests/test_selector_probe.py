"""抖音页面结构与「比心」面板零副作用体检测试（含热更新联动、空会话降级与四重互斥锁）。"""

from __future__ import annotations

from datetime import datetime, timezone

from fastapi.testclient import TestClient
from sqlmodel import Session, select

from app.execution.browser_manager import (
    IdentityHydrationProgress,
    LoginFlow,
    ScanProgress,
    SendProgress,
)
from app.models import SendJob
from tests.fakes import FakePage, RealApiPage, create_account, make_app


def test_probe_selectors_verifies_list_composer_and_heart_sticker_without_sending(
    settings,
):
    page = RealApiPage(
        logged_in=True,
        conversation_titles=("好友小满", "阿哲"),
        right_title="好友小满",
        editor_present=True,
        sticker_items=["大笑", "比心", "玫瑰"],
        sticker_button_available=True,
        sticker_panel_available=True,
    )
    app = make_app(settings, [page])
    with TestClient(app) as client:
        account = create_account(client)
        client.post(
            "/api/targets",
            json={"accountId": account["id"], "nicknameSnapshot": "好友小满"},
        )

        resp = client.post(f"/api/accounts/{account['id']}/probe-selectors")
        assert resp.status_code == 200, resp.text
        body = resp.json()

        assert body["accountId"] == account["id"]
        assert body["overall"] == "ok"
        assert body["sampleConversation"] == "好友小满"
        assert body["heartStickerFound"] is True
        assert body["hasOverrides"] is False
        assert len(body["bundleDigest"]) == 12

        by_key = {item["key"]: item for item in body["items"]}
        assert by_key["CONVERSATION_LIST"]["status"] == "ok"
        assert by_key["CONVERSATION_ITEM"]["status"] == "ok"
        assert by_key["CONVERSATION_ITEM"]["count"] == 2
        assert by_key["SEARCH_INPUTS"]["status"] == "ok"
        assert by_key["RIGHT_TITLE"]["status"] == "ok"
        assert by_key["CHAT_EDITOR_SELECTORS"]["status"] == "ok"
        # FakePage 空输入下未渲染发送按钮，应归为 optional_when_empty 且不拖垮 overall
        assert by_key["SEND_BUTTONS"]["status"] == "optional_when_empty"
        assert by_key["STICKER_BUTTONS"]["status"] == "ok"
        assert by_key["STICKER_PANELS"]["status"] == "ok"
        assert by_key["STICKER_ITEM"]["status"] == "ok"
        assert by_key["STICKER_ITEM"]["count"] == 3

        # 红线断言：零输入文字、零点击表情项、零新增 SendJob、已按 Escape 恢复输入框并关闭浏览器
        assert page.sticker_clicked is None
        assert page.typed == []
        assert page.editor_text == ""
        assert "Escape" in page.keys
        assert page.closed is True
        with Session(app.state.engine) as session:
            assert session.exec(select(SendJob)).all() == []


def test_probe_selectors_reports_degraded_when_sticker_button_breaks_and_recovers_after_hot_override(
    settings,
):
    """模拟抖音改版把表情按钮类名换成 .dy-new-emoji-trigger：先体检定界 degraded，在线热修后立即恢复 ok。"""

    class RebrandedStickerPage(RealApiPage):
        def locator(self, selector: str):
            loc = super().locator(selector)
            if selector == ".dy-new-emoji-trigger":
                # 仅新类名能匹配表情按钮，旧类名全部失效
                orig_count = loc.count

                async def _new_count() -> int:
                    return 1

                async def _visible() -> bool:
                    return True

                loc.count = _new_count  # type: ignore[method-assign]
                loc.is_visible = _visible  # type: ignore[method-assign]
            return loc

    # 第 1 页：旧选择器找不到表情按钮（sticker_button_available=False）
    page_before = RebrandedStickerPage(
        logged_in=True,
        conversation_titles=("好友A",),
        right_title="好友A",
        sticker_items=["比心"],
        sticker_button_available=False,
        sticker_panel_available=True,
    )
    # 第 2 页：同样只有 .dy-new-emoji-trigger 可见
    page_after = RebrandedStickerPage(
        logged_in=True,
        conversation_titles=("好友A",),
        right_title="好友A",
        sticker_items=["比心"],
        sticker_button_available=False,
        sticker_panel_available=True,
    )

    app = make_app(settings, [page_before, page_after])
    with TestClient(app) as client:
        account = create_account(client)
        r1 = client.post(f"/api/accounts/{account['id']}/probe-selectors")
        assert r1.status_code == 200
        b1 = r1.json()
        assert b1["overall"] == "degraded"
        by_key_1 = {item["key"]: item for item in b1["items"]}
        assert by_key_1["STICKER_BUTTONS"]["status"] == "missing"
        assert by_key_1["STICKER_PANELS"]["status"] == "skipped"

        # 在线持久化热修 STICKER_BUTTONS，无需重启进程
        put_resp = client.put(
            "/api/selectors",
            json={"overrides": {"STICKER_BUTTONS": [".dy-new-emoji-trigger"]}},
        )
        assert put_resp.status_code == 200

        r2 = client.post(f"/api/accounts/{account['id']}/probe-selectors")
        assert r2.status_code == 200
        b2 = r2.json()
        assert b2["overall"] == "ok"
        assert b2["hasOverrides"] is True
        assert b2["heartStickerFound"] is True
        by_key_2 = {item["key"]: item for item in b2["items"]}
        assert by_key_2["STICKER_BUTTONS"]["status"] == "ok"
        assert by_key_2["STICKER_BUTTONS"]["matchedSelector"] == ".dy-new-emoji-trigger"


def test_probe_selectors_handles_empty_chat_list_mutex_409_and_other_user_404(
    settings,
):
    empty_page = FakePage(
        logged_in=True,
        conversation_titles=(),
        right_title=None,
    )
    app = make_app(settings, [empty_page])
    with TestClient(app) as client:
        account = create_account(client)
        acc_id = account["id"]

        # 1) 无任何会话时左侧列表正常，右侧输入区优雅标为 skipped 而非报错 degraded
        empty_resp = client.post(f"/api/accounts/{acc_id}/probe-selectors")
        assert empty_resp.status_code == 200
        empty_body = empty_resp.json()
        assert empty_body["overall"] == "ok"
        assert empty_body["sampleConversation"] is None
        assert empty_body["heartStickerFound"] is None
        by_key = {item["key"]: item for item in empty_body["items"]}
        assert by_key["CONVERSATION_LIST"]["status"] == "ok"
        assert by_key["RIGHT_TITLE"]["status"] == "skipped"
        assert by_key["CHAT_EDITOR_SELECTORS"]["status"] == "skipped"
        assert by_key["STICKER_BUTTONS"]["status"] == "skipped"

        # 2) 四重账号互斥锁：登录中 / 扫描中 / 发送中 / 身份补采中 一律 409
        manager = app.state.browser_manager
        now = datetime.now(timezone.utc)

        manager._flows[acc_id] = LoginFlow(account_id=acc_id, state="waiting_scan")
        assert client.post(f"/api/accounts/{acc_id}/probe-selectors").status_code == 409
        manager._flows.clear()

        manager._scan_progress["t1"] = ScanProgress(
            task_id="t1", account_id=acc_id, status="running", started_at=now
        )
        assert client.post(f"/api/accounts/{acc_id}/probe-selectors").status_code == 409
        manager._scan_progress.clear()

        manager._send_progress[acc_id] = SendProgress(
            account_id=acc_id, status="running", total=1, completed=0, started_at=now
        )
        assert client.post(f"/api/accounts/{acc_id}/probe-selectors").status_code == 409
        manager._send_progress.clear()

        manager._identity_hydration_progress["h1"] = IdentityHydrationProgress(
            task_id="h1",
            account_id=acc_id,
            target_ids=["tid-1"],
            status="running",
            total=1,
            started_at=now,
        )
        assert client.post(f"/api/accounts/{acc_id}/probe-selectors").status_code == 409
        manager._identity_hydration_progress.clear()

        # 3) 跨用户隔离：另一用户请求此 account_id 返回 404
        client.post(
            "/api/auth/users",
            json={"username": "other_u", "password": "other-password-9"},
        )
        other_token = client.post(
            "/api/auth/login",
            json={"username": "other_u", "password": "other-password-9"},
        ).json()["token"]
        other_resp = client.post(
            f"/api/accounts/{acc_id}/probe-selectors",
            headers={"Authorization": f"Bearer {other_token}"},
        )
        assert other_resp.status_code == 404


def test_probe_selectors_diagnoses_broken_conversation_list_and_bad_syntax_without_locking_account(
    settings,
):
    """回归 Critical #1 & Important #2：CONVERSATION_LIST 失效或选择器语法错误时不得把健康账号标为 auth_expired 自锁，且不得丢失体检明细。"""
    from app.models import Account, AccountStatus

    class SyntaxCheckingPage(RealApiPage):
        def locator(self, selector: str):
            if selector.startswith("[unclosed"):
                raise RuntimeError("SyntaxError: invalid selector")
            return super().locator(selector)

    pages = [
        SyntaxCheckingPage(
            logged_in=True,
            conversation_titles=("好友小满",),
            right_title="好友小满",
            sticker_items=["比心"],
            sticker_button_available=True,
            sticker_panel_available=True,
        )
        for _ in range(3)
    ]
    app = make_app(settings, pages)
    with TestClient(app) as client:
        account = create_account(client)
        acc_id = account["id"]
        with Session(app.state.engine) as session:
            acc_row = session.get(Account, acc_id)
            assert acc_row is not None
            acc_row.status = AccountStatus.HEALTHY
            session.add(acc_row)
            session.commit()

        # 热修 CONVERSATION_LIST 为不匹配类名，且 STICKER_ITEM 含语法错误选择器
        put_r = client.put(
            "/api/selectors",
            json={
                "overrides": {
                    "CONVERSATION_LIST": ".douyinRenamedListWrapper",
                    "STICKER_ITEM": "[unclosed_css",
                }
            },
        )
        assert put_r.status_code == 200

        # 连续执行 3 次（超过 auth_check_tolerance=2）：每次都应返回 degraded + 明细，且账号保持 healthy
        for _ in range(3):
            probe_r = client.post(f"/api/accounts/{acc_id}/probe-selectors")
            assert probe_r.status_code == 200
            data = probe_r.json()
            assert data["overall"] == "degraded", data
            by_key = {item["key"]: item for item in data["items"]}
            assert by_key["CONVERSATION_LIST"]["status"] == "missing"
            assert by_key["SEARCH_INPUTS"]["status"] == "ok"
            assert by_key["STICKER_ITEM"]["status"] == "missing"

        with Session(app.state.engine) as session:
            acc_after = session.get(Account, acc_id)
            assert acc_after is not None
            assert acc_after.status == AccountStatus.HEALTHY


def test_probe_selectors_holds_account_check_mutex_against_concurrent_operations(
    settings,
):
    """回归 Important #1：体检运行期间登记账号级占用锁，防止并发发送/扫描/登录撞死 Chromium SingletonLock。"""
    observed_during_open: list[str | None] = []

    class ObservingPage(RealApiPage):
        pass

    page = ObservingPage(
        logged_in=True,
        conversation_titles=("好友A",),
        right_title="好友A",
        sticker_items=["比心"],
    )
    app = make_app(settings, [page])

    with TestClient(app) as client:
        manager = app.state.browser_manager
        orig_factory = manager._browser_factory

        async def recording_factory(profile_path, headless):
            # 浏览器打开瞬间，账号必须处于 _active_check 占用保护之下
            for aid in list(manager._active_checks.keys()):
                observed_during_open.append(manager._active_check(aid))
            return await orig_factory(profile_path, headless)

        manager._browser_factory = recording_factory

        account = create_account(client)
        acc_id = account["id"]
        client.post(
            "/api/targets",
            json={"accountId": acc_id, "nicknameSnapshot": "好友A"},
        )

        r = client.post(f"/api/accounts/{acc_id}/probe-selectors")
        assert r.status_code == 200
        assert observed_during_open == ["页面结构体检"]
        # 结束后锁必须已释放
        assert manager._active_check(acc_id) is None

        # 当账号处于体检占用中，所有会打开该账号浏览器的入口一律返回 409
        manager._active_checks[acc_id] = "页面结构体检"
        try:
            assert client.post(f"/api/accounts/{acc_id}/probe-selectors").status_code == 409
            assert client.post(f"/api/accounts/{acc_id}/dry-run").status_code == 409
            assert client.post(f"/api/accounts/{acc_id}/send").status_code == 409
            assert client.post(f"/api/accounts/{acc_id}/scan-chats/start").status_code == 409
            assert client.post(f"/api/accounts/{acc_id}/login").status_code == 409
            assert client.post(f"/api/accounts/{acc_id}/login/qr").status_code == 409
        finally:
            manager._active_checks.clear()
