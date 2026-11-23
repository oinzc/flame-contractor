"""本地火花连续天数（streak_days）的自然日去重判定。

抖音同日多次手动强制重发、定时重发或人工核销不应重复累加 streak_days：
每个目标在同一账号本地自然日内至多自增一次。
"""

from datetime import datetime, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


def _resolve_timezone(timezone_name: str | None):
    if not timezone_name:
        return timezone.utc
    cleaned = timezone_name.strip()
    if not cleaned:
        return timezone.utc
    try:
        return ZoneInfo(cleaned)
    except (ZoneInfoNotFoundError, ValueError, KeyError):
        return timezone.utc


def _as_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def should_increment_streak_for_confirmation(
    previous_confirmed_at: datetime | None,
    new_confirmed_at: datetime,
    timezone_name: str | None,
) -> bool:
    """判断本次发送确认是否应当让目标 streak_days + 1。

    - 首次确认（previous_confirmed_at is None）返回 True；
    - 否则按账号时区（非法或空时区安全回退 UTC）比较本地自然日，仅跨日时返回 True。
    """
    if previous_confirmed_at is None:
        return True
    tz = _resolve_timezone(timezone_name)
    prev_local_date = _as_utc(previous_confirmed_at).astimezone(tz).date()
    new_local_date = _as_utc(new_confirmed_at).astimezone(tz).date()
    return prev_local_date != new_local_date
