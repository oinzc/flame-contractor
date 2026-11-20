"""通知服务：把「必须人工接管」的信号主动推送给用户。

渠道当前仅支持 Bark（官方 API V2：POST JSON 到设备地址）。
- 推送地址来自 `FLAME_BARK_URL`，形如 `https://api.day.app/<device_key>`；
  为空或未配置时本服务为 no-op，不影响调度。
- Bark 官方 JSON 字段：device_key / title / body / badge / sound / group / url / icon。
  `level` 以 query 参数追加（用于 critical / timeSensitive 等提醒级别）。
- 所有 POST 用 httpx，默认短超时，失败只记日志、不抛出（通知失败不影响主流程）。

真实网络发送无法在单元测试中验证，因此提供 `sender` 注入点：测试注入 fake 回调。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Awaitable, Callable, Protocol
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import httpx

logger = logging.getLogger(__name__)

DEFAULT_TIMEOUT = 6.0
VALID_LEVELS = {"active", "timeSensitive", "critical", "passive"}
DEFAULT_LEVEL = "active"
MAX_BARK_URL_LEN = 255

Sender = Callable[[str, str], Awaitable[None]]


def _extract_bark_device_key(base_url: str) -> str:
    """从 base_url 提取 device_key：取路径中最后一个非 push 的非空段。"""
    path = urlsplit(base_url).path
    segments = [seg for seg in path.split("/") if seg]
    for segment in reversed(segments):
        if segment != "push":
            return segment
    return ""


def validate_bark_url(raw_url: str) -> str:
    """校验 Bark 推送地址格式并返回规范化后的字符串；非法则抛 ValueError。"""
    cleaned = (raw_url or "").strip()
    if not cleaned:
        raise ValueError("Bark 推送地址不能为空")
    if len(cleaned) > MAX_BARK_URL_LEN:
        raise ValueError(f"Bark 推送地址长度不能超过 {MAX_BARK_URL_LEN} 个字符")
    parts = urlsplit(cleaned)
    if parts.scheme not in ("http", "https") or not parts.netloc:
        raise ValueError("Bark 推送地址必须以 http:// 或 https:// 开头并包含合法域名")
    if not _extract_bark_device_key(cleaned):
        raise ValueError("Bark 推送地址缺少设备 Key 路径段，请按 https://api.day.app/<device_key> 格式填写")
    return cleaned


def mask_bark_url(raw_url: str | None) -> str | None:
    """将 Bark 推送地址中的 device_key 脱敏，仅保留域名与末尾 4 位，严禁回显完整密钥。"""
    cleaned = (raw_url or "").strip()
    if not cleaned:
        return None
    parts = urlsplit(cleaned)
    if not parts.scheme or not parts.netloc:
        return "****"
    raw_segments = [seg for seg in parts.path.split("/") if seg]
    key_idx: int | None = None
    for idx in range(len(raw_segments) - 1, -1, -1):
        if raw_segments[idx] != "push":
            key_idx = idx
            break
    if key_idx is not None:
        key = raw_segments[key_idx]
        raw_segments[key_idx] = f"****{key[-4:]}" if len(key) > 4 else "****"
    masked_path = "/" + "/".join(raw_segments) if raw_segments else ""
    return urlunsplit((parts.scheme, parts.netloc, masked_path, "", ""))


@dataclass
class BarkNotifier:
    """Bark 推送封装。

    Bark 的 device_key 属于本机私密信息，不入库、不上传，只存于环境变量 / .env。
    """

    base_url: str | None = None
    sender: Sender | None = None
    timeout: float = DEFAULT_TIMEOUT
    level: str = DEFAULT_LEVEL

    # 供测试/调用方观察最近一次通知内容
    last_sent: dict | None = field(default=None, init=False)

    @property
    def enabled(self) -> bool:
        return bool(self.base_url and self.base_url.strip())

    def set_base_url(self, base_url: str | None) -> None:
        cleaned = (base_url or "").strip()
        self.base_url = cleaned or None

    @staticmethod
    def _device_key(base_url: str) -> str:
        """从 base_url 中提取 device_key：取路径中最后一个非空段。
        兼容 `https://api.day.app/<key>` 与 `https://api.day.app/<key>/push`。
        """
        return _extract_bark_device_key(base_url)

    def _resolve_url(self, level: str) -> str | None:
        level = level or self.level
        if level not in VALID_LEVELS:
            # 静默回退会让 `level="errors"` 这类笔误变成「最低优先级推送」而不被发现：
            # 消息照发但手机不响，恰好是最需要立刻知道的那几条。这里显式留痕。
            logger.warning(
                "未知通知级别 %r，已回退到 %r（合法值：%s）",
                level,
                self.level,
                "、".join(sorted(VALID_LEVELS)),
            )
            level = self.level
        base = (self.base_url or "").strip()
        if not base:
            return None
        parts = urlsplit(base)
        query_items = [
            (k, v) for k, v in parse_qsl(parts.query, keep_blank_values=True) if k != "level"
        ]
        query_items.append(("level", level))
        return urlunsplit(
            (parts.scheme, parts.netloc, parts.path, urlencode(query_items), parts.fragment)
        )

    async def send(self, title: str, content: str, level: str | None = None) -> bool:
        """推送一条通知；未配置或失败时静默 no-op。返回是否成功。"""
        url = self._resolve_url(level)
        if not url:
            return False
        effective_level = (level or self.level) if (level or self.level) in VALID_LEVELS else self.level
        self.last_sent = {"title": title, "content": content, "level": effective_level}

        if self.sender is not None:
            await self.sender(title, content)
            return True

        device_key = self._device_key(self.base_url or "")
        if not device_key:
            logger.warning("Bark 通知发送跳过：FLAME_BARK_URL 缺少 device_key 路径段")
            return False

        payload = {
            "device_key": device_key,
            "title": title,
            "body": content,
        }
        try:
            # trust_env=False：忽略本机环境 HTTPS/HTTP 代理，Bark 推送应直连 api.day.app，
            # 避免因系统启用了 SOCKS 代理而要求额外依赖 socksio。
            async with httpx.AsyncClient(timeout=self.timeout, trust_env=False) as client:
                resp = await client.post(url, json=payload)
                resp.raise_for_status()
            return True
        except Exception as exc:  # noqa: BLE001 - 通知失败不影响主流程
            logger.warning("Bark 通知发送失败：%s", type(exc).__name__)
            return False

    async def probe(
        self, title: str, content: str, level: str | None = None
    ) -> tuple[bool, str]:
        """主动探测发送并返回脱敏结果文案（严禁包含 URL 或 device_key）。"""
        url = self._resolve_url(level)
        if not url:
            return False, "尚未配置 Bark 推送地址（可在「系统与调度」页面填写，或在 .env 设置 FLAME_BARK_URL）"
        effective_level = (
            (level or self.level)
            if (level or self.level) in VALID_LEVELS
            else self.level
        )
        self.last_sent = {"title": title, "content": content, "level": effective_level}

        try:
            if self.sender is not None:
                await self.sender(title, content)
                return True, "测试推送已送达 Bark 服务，请检查手机通知"

            device_key = self._device_key(self.base_url or "")
            if not device_key:
                return (
                    False,
                    "FLAME_BARK_URL 缺少设备 Key 路径段，请按 https://api.day.app/<device_key> 格式配置",
                )

            payload = {
                "device_key": device_key,
                "title": title,
                "body": content,
            }
            async with httpx.AsyncClient(timeout=self.timeout, trust_env=False) as client:
                resp = await client.post(url, json=payload)
                resp.raise_for_status()
            try:
                data = resp.json()
            except ValueError:
                logger.warning("Bark 测试推送返回非 JSON 响应")
                return (
                    False,
                    "推送地址未返回合法的 Bark JSON 响应，请检查 FLAME_BARK_URL 是否指向 Bark API 服务",
                )
            if not isinstance(data, dict):
                logger.warning("Bark 测试推送返回非对象 JSON")
                return (
                    False,
                    "推送地址未返回合法的 Bark JSON 响应，请检查 FLAME_BARK_URL 是否指向 Bark API 服务",
                )
            raw_code = data.get("code")
            if raw_code is not None and raw_code != 200:
                safe_code = raw_code if isinstance(raw_code, int) and not isinstance(raw_code, bool) else "异常"
                logger.warning("Bark 测试推送返回业务错误码：%s", safe_code)
                return (
                    False,
                    f"Bark 服务返回业务错误（code {safe_code}），请检查推送地址与设备 Key",
                )
            return True, "测试推送已送达 Bark 服务，请检查手机通知"
        except httpx.HTTPStatusError as exc:
            code = exc.response.status_code
            logger.warning("Bark 测试推送返回 HTTP %s", code)
            return False, f"Bark 服务返回 HTTP {code}，请检查推送地址是否正确"
        except Exception as exc:  # noqa: BLE001
            err_name = type(exc).__name__
            logger.warning("Bark 测试推送失败：%s", err_name)
            return False, f"连接 Bark 服务失败（{err_name}）"


class Notifier(Protocol):
    """统一的通知接口：Scheduler 只依赖该协议，便于测试用 Null/fake 替换。"""

    @property
    def enabled(self) -> bool: ...

    async def send(self, title: str, content: str, level: str | None = None) -> bool: ...


class NullNotifier:
    """未配置通知时的占位实现：始终 no-op，保持接口一致。"""

    @property
    def enabled(self) -> bool:
        return False

    async def send(self, title: str, content: str, level: str | None = None) -> bool:
        return False

    async def probe(
        self, title: str, content: str, level: str | None = None  # noqa: ARG002
    ) -> tuple[bool, str]:
        return False, "尚未配置 FLAME_BARK_URL（请在 .env 中设置后重启服务）"
