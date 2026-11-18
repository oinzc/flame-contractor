"""抖音网页版 DOM 适配层 —— 支持容器持久覆盖热更新的 selector bundle。

选择器概念参考了 MIT 许可的开源项目（unmev/douyin-auto-fire、
dr-190/ScriptCat-Douyin-Fire-Helper、2061360308/DouYinSparkFlow），
按本项目的安全边界重写并集中于此。

【双层热更新设计】
  - 基线层：同目录 `selectors_bundle.json`（随镜像发布，提供内置默认值）；
  - 持久覆盖层：`data/selectors_override.json`（位于容器持久数据卷，重启与升级不丢失）；
  - 访问模块属性（`selectors.CONVERSATION_LIST` 等）时，模块 `__getattr__`
    经 `_Bundle` 惰性检查基线文件与覆盖文件的 mtime，任一变化即重载合并；
  - `CHAT_URL` 被锁定为不可通过覆盖层修改，防止越界导航或 SSRF。
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

_BUNDLE_PATH = Path(__file__).with_name("selectors_bundle.json")
_NON_EDITABLE_KEYS = frozenset({"CHAT_URL"})
_MAX_SELECTOR_LEN = 512
_MAX_LIST_ITEMS = 20

_DEFAULTS: dict[str, object] = {
    "CHAT_URL": "https://www.douyin.com/chat",
    "CONVERSATION_LIST": ".conversationConversationListwrapper",
    "CONVERSATION_ITEM": ".conversationConversationItemwrapper",
    "CONVERSATION_TITLE": ".conversationConversationItemtitle",
    "CONVERSATION_STREAK": '[class*="StreakstreakContainer"]',
    "CONVERSATION_STREAK_DAYS": '[class*="StreaknormalText"]',
    "SEARCH_RESULT_ITEM": (
        'div[class*="SearchPanelitembox"], '
        'div[class*="SearchPanelitem-box"], '
        'div[class*="SearchPanelitem_box"]'
    ),
    "SEARCH_NAME_SELECTORS": [
        '[class*="SearchPanelitemtitle"]',
        '[class*="SearchPanelitemTitle"]',
        '[class*="SearchPanelitem_title"]',
        '[class*="SearchPanelitem-title"]',
        '[class*="SearchPanelitemname"]',
        '[class*="SearchPanelitemName"]',
        '[class*="SearchPanelitem_name"]',
        '[class*="SearchPanelitem-name"]',
    ],
    "SEARCH_NAME_EXACT_SELECTORS": [
        '[class*="SearchPanelitemsearch_highlight"]',
        '[class*="SearchPanelitemsearch-highlight"]',
        '[class*="SearchPanelitemsearchHighlight"]',
    ],
    "SEARCH_CHAT_BTN": 'div[class*="SearchPanelitemchat_btn"]',
    "RIGHT_TITLE": ".RightPanelHeadertitleContainer",
    "SEARCH_INPUT": 'input.semi-input[placeholder="搜索"][type="text"]',
    "SEARCH_INPUTS": [
        'input.semi-input[placeholder="搜索"][type="text"]',
        'input[placeholder="搜索"]',
    ],
    "CHAT_EDITOR": 'div[data-slate-editor="true"][contenteditable="true"]',
    "CHAT_EDITOR_SELECTORS": [
        'div[data-slate-editor="true"][contenteditable="true"]',
        '[class*="messageEditor"] [contenteditable="true"]',
        '.messageEditorinputArea[contenteditable="true"]',
    ],
    "IMAGE_INPUTS": [
        'input[type="file"][accept*="image"]',
        'input[type="file"]',
    ],
    "IMAGE_UPLOAD_TRIGGER": ".semi-upload-add",
    "IMAGE_DIALOG_SEND": [
        '.semi-modal button[class*="primary"]',
        'button[class*="semi-button-primary"]',
        '[class*="confirm"] button[class*="primary"]',
    ],
    "SEND_FAILURE_MARKERS": [
        '[class*="sendFailed"]',
        '[class*="SendFailed"]',
        '[class*="ContentSideSendStatusretry"]',
        '[class*="SendStatusretry"]',
        '[aria-label*="重试"]',
        '[title*="重试"]',
    ],
    "SEND_BUTTONS": [
        '[class*="messageMsgInputpublishBtn"]',
        ".e2e-send-msg-bt",
        'button[aria-label*="发送"]',
        '[role="button"][aria-label*="发送"]',
    ],
    "MEDIA_MESSAGE_BOXES": '[class*="messageMessageBoxmessageBox"]',
    "MEDIA_MESSAGE_CONTENT": '[class*="contentBox"]',
    "MESSAGE_TIME": '[class*="timeLayout"]',
    "STICKER_BUTTONS": [
        "svg.messageMsgInputiconAction",
        'button[aria-label*="表情"]',
        '[role="button"][aria-label*="表情"]',
        '[title*="表情"]',
    ],
    "STICKER_PANELS": [
        ".componentsemojiemojiPanel",
        '[class*="emojiPanel"]',
        '[role="dialog"]',
        '[class*="sticker"]',
    ],
    "STICKER_ITEM": ".emojiEmojiItememojiItem",
    "STICKER_ITEM_NAME": ".emojiEmojiItememojiItemDesc",
}


def _clean_single_selector(key: str, raw: object) -> str:
    if not isinstance(raw, str):
        raise ValueError(f"选择器 {key} 必须为字符串")
    cleaned = raw.strip()
    if not cleaned:
        raise ValueError(f"选择器 {key} 不能为空字符串")
    if len(cleaned) > _MAX_SELECTOR_LEN:
        raise ValueError(f"选择器 {key} 长度不能超过 {_MAX_SELECTOR_LEN} 个字符")
    if any(ch in cleaned for ch in ("\x00", "\r", "\n")):
        raise ValueError(f"选择器 {key} 不能包含换行或控制字符")
    return cleaned


def _validate_override_entry(
    base_values: dict[str, object], key: str, raw_value: object
) -> str | list[str]:
    if not isinstance(key, str) or key in _NON_EDITABLE_KEYS:
        raise ValueError(f"不允许通过在线覆盖修改 {key!r}")
    if key not in base_values:
        raise ValueError(f"未知的选择器键名：{key!r}")

    expected = base_values[key]
    if isinstance(expected, str):
        return _clean_single_selector(key, raw_value)
    if isinstance(expected, (list, tuple)):
        if not isinstance(raw_value, list):
            raise ValueError(f"选择器 {key} 必须为非空字符串数组")
        if len(raw_value) == 0 or len(raw_value) > _MAX_LIST_ITEMS:
            raise ValueError(f"选择器 {key} 数组元素数量须在 1..{_MAX_LIST_ITEMS} 之间")
        return [_clean_single_selector(key, item) for item in raw_value]
    raise ValueError(f"不支持覆盖的选择器类型：{key!r}")


def _copy_value(val: object) -> object:
    if isinstance(val, (list, tuple)):
        return list(val)
    return val


class _Bundle:
    """持有基线 selector 与持久卷 override 合并后的快照，按双文件 mtime 惰性重载。"""

    def __init__(
        self, path: Path | None = None, override_path: Path | None = None
    ) -> None:
        self.path = Path(path) if path is not None else _BUNDLE_PATH
        self.override_path = Path(override_path) if override_path is not None else None
        self._base_values: dict[str, object] = {}
        self._overrides: dict[str, str | list[str]] = {}
        self._values: dict[str, object] = {}
        self._mtime: float | None = None
        self._override_mtime: float | None = None
        self._reload(force=True)

    @staticmethod
    def _stat_mtime(target: Path | None) -> float | None:
        if target is None:
            return None
        try:
            return target.stat().st_mtime
        except OSError:
            return None

    def _reload(self, *, force: bool = False) -> None:
        current_mtime = self._stat_mtime(self.path)
        current_override_mtime = self._stat_mtime(self.override_path)
        if (
            not force
            and current_mtime == self._mtime
            and current_override_mtime == self._override_mtime
        ):
            return

        base_values = {k: _copy_value(v) for k, v in _DEFAULTS.items()}
        if current_mtime is not None:
            try:
                data = json.loads(self.path.read_text(encoding="utf-8"))
                if isinstance(data, dict):
                    for key, value in data.items():
                        if value is None:
                            continue
                        base_values[key] = _copy_value(value)
            except (json.JSONDecodeError, OSError):
                pass
        else:
            try:
                self.path.write_text(
                    json.dumps(base_values, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8",
                )
                current_mtime = self._stat_mtime(self.path)
            except OSError:
                pass

        overrides: dict[str, str | list[str]] = {}
        if self.override_path is not None and current_override_mtime is not None:
            try:
                raw_over = json.loads(self.override_path.read_text(encoding="utf-8"))
                if isinstance(raw_over, dict):
                    for key, value in raw_over.items():
                        try:
                            cleaned = _validate_override_entry(base_values, key, value)
                            if cleaned != _copy_value(base_values[key]):
                                overrides[key] = cleaned
                        except ValueError:
                            # 磁盘手工编辑混入非法项或 CHAT_URL 时安全忽略
                            continue
            except (json.JSONDecodeError, OSError):
                pass

        merged = {k: _copy_value(v) for k, v in base_values.items()}
        for key, val in overrides.items():
            merged[key] = _copy_value(val)

        self._base_values = base_values
        self._overrides = overrides
        self._values = merged
        self._mtime = current_mtime
        self._override_mtime = current_override_mtime

    def configure_override_path(self, override_path: Path | None) -> None:
        next_path = Path(override_path) if override_path is not None else None
        if next_path != self.override_path:
            self.override_path = next_path
            self._reload(force=True)

    def get(self, name: str):
        self._reload()
        if name not in self._values:
            raise AttributeError(name)
        return self._values[name]

    def reload(self, *, force: bool = True) -> None:
        self._reload(force=force)

    def as_dict(self) -> dict[str, object]:
        self._reload()
        return {k: _copy_value(v) for k, v in self._values.items()}

    def base_dict(self) -> dict[str, object]:
        self._reload()
        return {k: _copy_value(v) for k, v in self._base_values.items()}

    def overrides_dict(self) -> dict[str, str | list[str]]:
        self._reload()
        return {k: _copy_value(v) for k, v in self._overrides.items()}  # type: ignore[misc]

    def editable_keys(self) -> list[str]:
        self._reload()
        return [k for k in self._base_values.keys() if k not in _NON_EDITABLE_KEYS]

    def save_overrides(self, raw_overrides: dict[str, object]) -> dict[str, object]:
        if not isinstance(raw_overrides, dict):
            raise ValueError("overrides 必须为 JSON 对象")
        self._reload()
        cleaned_deltas: dict[str, str | list[str]] = {}
        for key, value in raw_overrides.items():
            cleaned = _validate_override_entry(self._base_values, key, value)
            if cleaned != _copy_value(self._base_values[key]):
                cleaned_deltas[key] = cleaned

        if self.override_path is None:
            raise ValueError("当前未配置持久化覆盖路径")

        if not cleaned_deltas:
            self.clear_overrides()
            return self.as_dict()

        self.override_path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = self.override_path.with_suffix(".json.tmp")
        tmp_path.write_text(
            json.dumps(cleaned_deltas, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        os.replace(tmp_path, self.override_path)
        self._reload(force=True)
        return self.as_dict()

    def clear_overrides(self) -> dict[str, object]:
        if self.override_path is not None:
            try:
                self.override_path.unlink()
            except FileNotFoundError:
                pass
        self._reload(force=True)
        return self.as_dict()

    def digest(self) -> str:
        self._reload()
        if not self._overrides:
            try:
                payload = self.path.read_bytes()
                return hashlib.sha256(payload).hexdigest()[:12]
            except OSError:
                pass
        payload = json.dumps(
            self._values, ensure_ascii=False, sort_keys=True
        ).encode("utf-8")
        return hashlib.sha256(payload).hexdigest()[:12]


_bundle = _Bundle()


def configure_override_path(override_path: Path | None) -> None:
    """设置持久数据卷覆盖文件路径（如 data/selectors_override.json）。"""
    _bundle.configure_override_path(override_path)


def current(name: str):
    """取当前 selector 值（公开入口，供 API / 诊断使用）。"""
    return _bundle.get(name)


def all_selectors() -> dict[str, object]:
    """返回当前生效的 bundle 全量快照（基线 + 覆盖层）。"""
    return _bundle.as_dict()


def base_selectors() -> dict[str, object]:
    """返回内置基线 selector 快照（不含覆盖层）。"""
    return _bundle.base_dict()


def current_overrides() -> dict[str, str | list[str]]:
    """返回当前生效的增量覆盖项字典。"""
    return _bundle.overrides_dict()


def has_overrides() -> bool:
    """是否存在任何非默认覆盖项。"""
    return bool(_bundle.overrides_dict())


def editable_keys() -> list[str]:
    """返回允许在线覆盖编辑的键名列表（不含 CHAT_URL）。"""
    return _bundle.editable_keys()


def validate_and_save_overrides(overrides: dict[str, object]) -> dict[str, object]:
    """校验并原子写入覆盖项，立即热重载生效。"""
    return _bundle.save_overrides(overrides)


def clear_overrides() -> dict[str, object]:
    """删除持久覆盖文件并立即恢复内置默认基线。"""
    return _bundle.clear_overrides()


def bundle_digest() -> str:
    """当前生效 selector 集合的 12 位十六进制内容摘要。"""
    return _bundle.digest()


def bundle_metadata() -> dict[str, object]:
    """返回供 API 展示的完整定位器状态元数据。"""
    active = _bundle.as_dict()
    base = _bundle.base_dict()
    overrides = _bundle.overrides_dict()
    mtime = _Bundle._stat_mtime(_bundle.path.resolve())
    override_path = (
        str(_bundle.override_path.resolve())
        if _bundle.override_path is not None
        else None
    )
    return {
        "file_path": str(_bundle.path.resolve()),
        "override_path": override_path,
        "count": len(active),
        "loaded_mtime": mtime,
        "bundle_digest": _bundle.digest(),
        "has_overrides": bool(overrides),
        "editable_keys": _bundle.editable_keys(),
        "overrides": overrides,
        "base_selectors": base,
        "selectors": active,
    }


def reload_bundle(*, force: bool | None = None) -> dict[str, object]:
    """重新加载 bundle（默认强制重读文件），返回当前全量快照。"""
    _bundle.reload(force=True if force is None else force)
    return _bundle.as_dict()


def _reset_for_tests(
    path: Path | None = None, *, override_path: Path | None = None
) -> None:
    """测试专用：重建单例 bundle（可指向临时基线与临时覆盖路径）。"""
    global _bundle
    _bundle = _Bundle(path, override_path=override_path)


def __getattr__(name: str):
    if name.startswith("__"):
        raise AttributeError(name)
    return _bundle.get(name)
