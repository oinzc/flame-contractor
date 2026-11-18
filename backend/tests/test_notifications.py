"""M5：Bark 通知器——URL/level 解析、device_key 提取、未配置 no-op、sender 注入。"""

import asyncio
import logging

from app.notifications import BarkNotifier, NullNotifier


def test_notifier_disabled_without_url():
    notifier = BarkNotifier(base_url=None)
    assert notifier.enabled is False
    assert asyncio.run(notifier.send("t", "c")) is False


def test_notifier_extracts_device_key():
    assert BarkNotifier._device_key("https://api.day.app/abc123") == "abc123"
    assert BarkNotifier._device_key("https://api.day.app/abc123/push") == "abc123"


def test_resolve_url_appends_level():
    notifier = BarkNotifier(base_url="https://api.day.app/abc123", level="active")
    assert notifier._resolve_url("critical") == "https://api.day.app/abc123?level=critical"
    # 默认 level
    assert notifier._resolve_url(None) == "https://api.day.app/abc123?level=active"


def test_invalid_level_falls_back_to_default():
    notifier = BarkNotifier(base_url="https://api.day.app/abc123", level="active")
    assert notifier._resolve_url("bogus") == "https://api.day.app/abc123?level=active"


def test_invalid_level_logs_warning(caplog):
    """回归：`level="errors"` 这类笔误会静默降级成最低优先级推送（消息照发但不响铃），
    必须留下告警，否则「账号发送出错」这类关键通知会悄悄失去提醒效果。"""
    notifier = BarkNotifier(base_url="https://api.day.app/abc123", level="active")
    with caplog.at_level(logging.WARNING):
        assert notifier._resolve_url("errors") == "https://api.day.app/abc123?level=active"
    assert "errors" in caplog.text


def test_send_uses_injected_sender():
    calls: list[tuple[str, str]] = []

    async def sender(title: str, content: str) -> None:
        calls.append((title, content))

    notifier = BarkNotifier(base_url="https://api.day.app/abc123", sender=sender)
    assert notifier.enabled is True
    result = asyncio.run(notifier.send("标题", "内容", "critical"))
    assert result is True
    assert calls == [("标题", "内容")]
    assert notifier.last_sent == {"title": "标题", "content": "内容", "level": "critical"}


def test_null_notifier_is_noop():
    notifier = NullNotifier()
    assert notifier.enabled is False
    assert asyncio.run(notifier.send("t", "c")) is False


def test_scheduler_exposes_bark_configured_and_test_endpoint_never_leaks_key(settings, monkeypatch, caplog):
    """GET /api/scheduler 暴露布尔 barkConfigured；POST /api/scheduler/test-notification 严禁泄露 device_key。"""
    import httpx
    from fastapi.testclient import TestClient
    from app.main import create_app

    # 1) 未配置 bark_url
    app_unconf = create_app(settings.model_copy(update={"bark_url": None}))
    with TestClient(app_unconf) as client:
        status_body = client.get("/api/scheduler").json()
        assert status_body["barkConfigured"] is False

        probe_resp = client.post("/api/scheduler/test-notification")
        assert probe_resp.status_code == 200, probe_resp.text
        assert probe_resp.json()["ok"] is False

    # 2) 已配置 bark_url：测试成功、HTTPStatusError 与网络超时三种场景均不泄露 secret key
    secret_key = "super-secret-bark-device-key-9999"
    secret_url = f"https://api.day.app/{secret_key}"
    conf_settings = settings.model_copy(update={"bark_url": secret_url})

    mode = {"state": "ok"}

    class FakeAsyncClient:
        def __init__(self, *_args, **_kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return False

        async def post(self, url: str, json: dict):
            req = httpx.Request("POST", url)
            if mode["state"] == "http_400":
                resp = httpx.Response(400, request=req, text=f"bad key {secret_key}")
                resp.raise_for_status()
            if mode["state"] == "timeout":
                raise httpx.ConnectTimeout(f"timeout reaching {url}", request=req)
            return httpx.Response(200, request=req, json={"code": 200})

    monkeypatch.setattr("app.notifications.httpx.AsyncClient", FakeAsyncClient)

    app_conf = create_app(conf_settings)
    with caplog.at_level(logging.WARNING), TestClient(app_conf) as client:
        sched_resp = client.get("/api/scheduler")
        assert sched_resp.status_code == 200
        assert sched_resp.json()["barkConfigured"] is True
        assert secret_key not in sched_resp.text

        ok_resp = client.post("/api/scheduler/test-notification")
        assert ok_resp.status_code == 200
        assert ok_resp.json()["ok"] is True
        assert secret_key not in ok_resp.text

        mode["state"] = "http_400"
        err_400 = client.post("/api/scheduler/test-notification")
        assert err_400.status_code == 200
        assert err_400.json()["ok"] is False
        assert "400" in err_400.json()["detail"]
        assert secret_key not in err_400.text

        mode["state"] = "timeout"
        err_timeout = client.post("/api/scheduler/test-notification")
        assert err_timeout.status_code == 200
        assert err_timeout.json()["ok"] is False
        assert "ConnectTimeout" in err_timeout.json()["detail"]
        assert secret_key not in err_timeout.text

    assert secret_key not in caplog.text


def test_bark_probe_rejects_false_positive_200_missing_key_and_preserves_query_params(monkeypatch):
    """回归：HTTP 200 非 JSON / code!=200 与缺省 device_key 不得假阳性；已有 query 参数不得拼出双问号。"""
    import httpx

    # 1) 已有 query 参数合并 level，不出现双 '?'
    with_query = BarkNotifier(
        base_url="https://api.day.app/abc123?group=flame&sound=minuet",
        level="active",
    )
    resolved = with_query._resolve_url("timeSensitive")
    assert resolved is not None
    assert resolved.count("?") == 1
    assert "group=flame" in resolved
    assert "sound=minuet" in resolved
    assert "level=timeSensitive" in resolved

    # 2) 漏填 device_key 路径段
    missing_key = BarkNotifier(base_url="https://api.day.app/push")
    ok_missing, detail_missing = asyncio.run(missing_key.probe("t", "c"))
    assert ok_missing is False
    assert "Key" in detail_missing

    # 3) 注入 sender 抛异常也必须被 probe 捕获并脱敏
    async def broken_sender(_t: str, _c: str) -> None:
        raise RuntimeError("boom https://api.day.app/secret-xyz")

    broken = BarkNotifier(base_url="https://api.day.app/secret-xyz", sender=broken_sender)
    ok_broken, detail_broken = asyncio.run(broken.probe("t", "c"))
    assert ok_broken is False
    assert "RuntimeError" in detail_broken
    assert "secret-xyz" not in detail_broken

    # 4) HTTP 200 但返回 HTML 或 Bark 业务错误 JSON {"code": 400}
    mode = {"resp": "html"}
    secret = "secret-html-or-json-key-777"

    class FakeClient:
        def __init__(self, *_args, **_kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return False

        async def post(self, url: str, json: dict):
            req = httpx.Request("POST", url)
            if mode["resp"] == "html":
                return httpx.Response(200, request=req, text=f"<html>wrong host {secret}</html>")
            return httpx.Response(
                200,
                request=req,
                json={"code": 400, "message": f"failed token {secret}"},
            )

    monkeypatch.setattr("app.notifications.httpx.AsyncClient", FakeClient)
    notifier = BarkNotifier(base_url=f"https://day.app/{secret}")

    ok_html, detail_html = asyncio.run(notifier.probe("t", "c"))
    assert ok_html is False
    assert "JSON" in detail_html
    assert secret not in detail_html

    mode["resp"] = "biz_400"
    ok_biz, detail_biz = asyncio.run(notifier.probe("t", "c"))
    assert ok_biz is False
    assert "400" in detail_biz
    assert secret not in detail_biz
