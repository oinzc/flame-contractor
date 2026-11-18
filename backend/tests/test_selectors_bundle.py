"""selector bundle 热更新：默认加载 / 显式 reload / 惰性 mtime / 运行期取值 / API。

这些测试会构造临时 bundle 文件并替换进程内单例（_reset_for_tests），
每个测试结束后由 autouse fixture 恢复为默认（真实 selectors_bundle.json），
避免污染其它测试与仓库里的 JSON。
"""

import asyncio
import json
import os

import pytest

from app.execution import selectors as sel
from app.execution.douyin_chat import DouyinChat
from app.execution.selectors import _BUNDLE_PATH


@pytest.fixture(autouse=True)
def _restore_bundle():
    yield
    sel._reset_for_tests()  # 恢复为默认（指向真实 selectors_bundle.json）


def _default_dict() -> dict:
    return json.loads(_BUNDLE_PATH.read_text(encoding="utf-8"))


def _write_bundle(path, data: dict, *, advance_mtime: bool = False) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    if advance_mtime:
        st = path.stat()
        os.utime(path, (st.st_atime + 2, st.st_mtime + 2))


# ---------------------------------------------------------------- 默认加载


def test_bundle_loads_defaults():
    current = sel.all_selectors()
    assert current["CHAT_EDITOR"] == 'div[data-slate-editor="true"][contenteditable="true"]'
    assert current["CONVERSATION_LIST"] == ".conversationConversationListwrapper"
    assert current["RIGHT_TITLE"] == ".RightPanelHeadertitleContainer"
    assert current["IMAGE_UPLOAD_TRIGGER"] == ".semi-upload-add"
    assert len(current["SEND_BUTTONS"]) >= 1
    assert len(current["SEARCH_NAME_SELECTORS"]) >= 1
    # 与真实 bundle 文件一致
    assert current == _default_dict()


def test_unknown_selector_raises_attribute_error():
    with pytest.raises(AttributeError):
        sel.current("THIS_IS_NOT_A_SELECTOR")


# ---------------------------------------------------------------- 显式 reload


def test_reload_reads_updated_file(tmp_path):
    target = tmp_path / "bundle.json"
    changed = _default_dict()
    changed["CHAT_EDITOR"] = "div[data-slate-editor='changed'][contenteditable='true']"
    _write_bundle(target, changed)
    sel._reset_for_tests(target)

    assert sel.current("CHAT_EDITOR") == changed["CHAT_EDITOR"]
    assert sel.current("CONVERSATION_LIST") == _default_dict()["CONVERSATION_LIST"]


# ---------------------------------------------------------------- 惰性 mtime


def test_lazy_mtime_reload_without_explicit_call(tmp_path):
    target = tmp_path / "bundle.json"
    base = _default_dict()
    _write_bundle(target, base)
    sel._reset_for_tests(target)

    assert sel.current("CHAT_EDITOR") == base["CHAT_EDITOR"]

    changed = dict(base)
    changed["CHAT_EDITOR"] = ".someNewEditorSelector"
    _write_bundle(target, changed, advance_mtime=True)

    # 未调用 reload_bundle，仅靠下一次访问的 mtime 检查即拿到新值
    assert sel.current("CHAT_EDITOR") == ".someNewEditorSelector"


# ---------------------------------------------------------------- 运行期取值


class _SelectorProbePage:
    """按「期望的 selector 字符串」返回命中与否；仅暴露 query_selector。"""

    def __init__(self, wanted):
        self.wanted = wanted

    async def query_selector(self, selector):
        return object() if selector == self.wanted else None


def test_runtime_accessor_reflects_reload(tmp_path):
    """DouyinChat 用 _s.CONVERSATION_LIST 运行期取值，bundle 改了立即生效。"""
    target = tmp_path / "bundle.json"
    base = _default_dict()
    _write_bundle(target, base)
    sel._reset_for_tests(target)

    new_list = ".myCustomConversationList"
    page = _SelectorProbePage(wanted=new_list)
    chat = DouyinChat(page, sleep=lambda _s: asyncio.sleep(0))

    # 默认 CONVERSATION_LIST 不是 .myCustomConversationList → 未登录
    assert asyncio.run(chat.is_logged_in()) is False

    changed = dict(base)
    changed["CONVERSATION_LIST"] = new_list
    _write_bundle(target, changed, advance_mtime=True)

    # bundle 更新后，同一 chat 实例读到新选择器 → 已登录
    assert asyncio.run(chat.is_logged_in()) is True


# ---------------------------------------------------------------- API


def test_api_read_current_bundle(client):
    response = client.get("/api/selectors")
    assert response.status_code == 200
    body = response.json()
    assert "selectors" in body
    assert "count" in body
    assert "file_path" in body
    assert body["selectors"]["CHAT_EDITOR"] == 'div[data-slate-editor="true"][contenteditable="true"]'
    assert body["count"] == len(body["selectors"])


def test_api_reload_reloads_bundle(client):
    response = client.post("/api/selectors/reload")
    assert response.status_code == 200
    body = response.json()
    assert body["selectors"]["CONVERSATION_LIST"] == ".conversationConversationListwrapper"
    assert body["selectors"]["SEND_BUTTONS"]


def test_override_file_merges_over_base_and_ignores_chat_url(tmp_path):
    base_file = tmp_path / "base_bundle.json"
    override_file = tmp_path / "selectors_override.json"
    base = _default_dict()
    _write_bundle(base_file, base)
    sel._reset_for_tests(base_file, override_path=override_file)

    assert sel.has_overrides() is False
    assert sel.current("CHAT_URL") == "https://www.douyin.com/chat"

    # 手工写入含非法 CHAT_URL 与合法覆盖项的 override 文件：CHAT_URL 必须被忽略
    _write_bundle(
        override_file,
        {
            "CHAT_URL": "http://127.0.0.1:9999/ssrf",
            "CHAT_EDITOR": "div.custom-editor[contenteditable='true']",
        },
        advance_mtime=True,
    )
    assert sel.current("CHAT_URL") == "https://www.douyin.com/chat"
    assert sel.current("CHAT_EDITOR") == "div.custom-editor[contenteditable='true']"
    assert sel.current("CONVERSATION_LIST") == base["CONVERSATION_LIST"]
    assert sel.has_overrides() is True

    # 清空覆盖后删除文件并回到默认基线
    sel.clear_overrides()
    assert not override_file.exists()
    assert sel.has_overrides() is False
    assert sel.current("CHAT_EDITOR") == base["CHAT_EDITOR"]


def test_api_put_and_delete_selectors_enforces_superuser_schema_and_persists_in_data_dir(
    settings,
):
    from fastapi.testclient import TestClient
    from app.main import create_app

    app = create_app(settings)
    expected_override_path = settings.profiles_dir.parent / "selectors_override.json"

    with TestClient(app) as client:
        initial = client.get("/api/selectors").json()
        assert initial["has_overrides"] is False
        assert "CHAT_URL" not in initial["editable_keys"]
        assert "STICKER_BUTTONS" in initial["editable_keys"]
        assert initial["base_selectors"]["CHAT_EDITOR"] == initial["selectors"]["CHAT_EDITOR"]

        # 1) SSRF 防护：尝试修改 CHAT_URL 必须 422
        resp_url = client.put(
            "/api/selectors",
            json={"overrides": {"CHAT_URL": "https://evil.example.com"}},
        )
        assert resp_url.status_code == 422

        # 2) 未知键名必须 422
        resp_unknown = client.put(
            "/api/selectors",
            json={"overrides": {"NOT_EXIST_KEY": ".foo"}},
        )
        assert resp_unknown.status_code == 422

        # 3) 数组型键传入裸字符串（防止逐字符遍历 bug）或字符串键传入数组必须 422
        resp_str_for_list = client.put(
            "/api/selectors",
            json={"overrides": {"SEND_BUTTONS": ".single-string"}},
        )
        assert resp_str_for_list.status_code == 422

        resp_list_for_str = client.put(
            "/api/selectors",
            json={"overrides": {"CHAT_EDITOR": [".arr"]}},
        )
        assert resp_list_for_str.status_code == 422

        # 4) 空字符串或空列表必须 422
        assert (
            client.put("/api/selectors", json={"overrides": {"CHAT_EDITOR": "   "}}).status_code
            == 422
        )
        assert (
            client.put("/api/selectors", json={"overrides": {"SEND_BUTTONS": []}}).status_code
            == 422
        )

        # 5) 合法保存：持久落盘至 data 目录并立即在 DouyinChat 运行期生效
        save_resp = client.put(
            "/api/selectors",
            json={
                "overrides": {
                    "CONVERSATION_LIST": ".customHotList",
                    "STICKER_BUTTONS": ["svg.newStickerBtn", "button[aria-label*='表情']"],
                }
            },
        )
        assert save_resp.status_code == 200, save_resp.text
        saved_body = save_resp.json()
        assert saved_body["has_overrides"] is True
        assert saved_body["overrides"] == {
            "CONVERSATION_LIST": ".customHotList",
            "STICKER_BUTTONS": ["svg.newStickerBtn", "button[aria-label*='表情']"],
        }
        assert expected_override_path.is_file()

        probe_page = _SelectorProbePage(wanted=".customHotList")
        chat = DouyinChat(probe_page, sleep=lambda _s: asyncio.sleep(0))
        assert asyncio.run(chat.is_logged_in()) is True

        # 6) 普通非超管用户：可读 GET，但 PUT 与 DELETE 一律 403
        client.post(
            "/api/auth/users",
            json={"username": "member1", "password": "member-password-1"},
        )
        member_login = client.post(
            "/api/auth/login",
            json={"username": "member1", "password": "member-password-1"},
        ).json()
        member_headers = {"Authorization": f"Bearer {member_login['token']}"}

        assert client.get("/api/selectors", headers=member_headers).status_code == 200
        assert (
            client.put(
                "/api/selectors",
                json={"overrides": {"CHAT_EDITOR": ".hacked"}},
                headers=member_headers,
            ).status_code
            == 403
        )
        assert (
            client.delete("/api/selectors/override", headers=member_headers).status_code
            == 403
        )

        # 7) 管理员一键清空覆盖恢复默认
        del_resp = client.delete("/api/selectors/override")
        assert del_resp.status_code == 200, del_resp.text
        del_body = del_resp.json()
        assert del_body["has_overrides"] is False
        assert del_body["overrides"] == {}
        assert not expected_override_path.exists()
        assert asyncio.run(chat.is_logged_in()) is False
