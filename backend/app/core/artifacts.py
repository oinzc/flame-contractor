"""artifacts 诊断产物的维护与安全路径解析工具。

诊断产物（失败截图、发送/定位工件 JSON）属于核心排障能力，需满足两条硬约束：
1. 保留期自动回收：启动时执行一次，并由后台调度器按 24 小时门控巡检清理，防止长跑容器膨胀；
2. 严格路径边界：`SendAttempt.artifact_ref` 仅记录相对 `artifacts_dir` 的 POSIX 子路径，
   读取截图时必须锚定在 `artifacts_dir/<account_id>/shots/*.png` 之内，杜绝跨账号与路径穿越。
"""

from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
import re
from typing import Literal

SECONDS_PER_DAY = 86400
MAX_ARTIFACT_REF_LEN = 256
_SAFE_ACCOUNT_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
ScreenshotState = Literal["none", "available", "expired"]


def prune_old_artifacts(
    artifacts_dir: Path,
    retention_days: int,
    *,
    now: datetime | None = None,
) -> int:
    """删除 artifacts_dir 下 mtime 早于保留期的文件，返回删除数量。

    retention_days <= 0 表示关闭清理；目录不存在时视为无可清理。
    """
    if retention_days <= 0:
        return 0
    if not artifacts_dir.exists():
        return 0
    cutoff = (now or datetime.now(timezone.utc)).timestamp() - retention_days * SECONDS_PER_DAY
    removed = 0
    for path in artifacts_dir.rglob("*"):
        try:
            if path.is_file() and path.stat().st_mtime < cutoff:
                path.unlink()
                removed += 1
        except OSError:
            continue
    return removed


def to_artifact_ref(artifacts_dir: Path, raw_path: str | Path | None) -> str | None:
    """将现场截图路径归一化为相对于 artifacts_dir 的 POSIX 相对路径。

    路径为空、不在 artifacts_dir 之内或超出列长度上限时保守返回 None，绝不阻塞发送落库。
    """
    if not raw_path:
        return None
    try:
        root = artifacts_dir.resolve()
        resolved = Path(raw_path).resolve()
        if not resolved.is_relative_to(root) or resolved == root:
            return None
        rel = resolved.relative_to(root).as_posix()
        if not rel or len(rel) > MAX_ARTIFACT_REF_LEN:
            return None
        return rel
    except (OSError, ValueError, RuntimeError):
        return None


def resolve_shot_artifact(
    artifacts_dir: Path,
    account_id: str,
    artifact_ref: str | None,
) -> tuple[ScreenshotState, Path | None]:
    """安全解析某账号的现场截图路径，返回 ('none' | 'available' | 'expired', path)。

    任何空值、越界段、绝对路径、跨账号引用、非 shots/*.png 或符号链接跳出目录均返回 ('none', None)。
    """
    if not artifact_ref or not isinstance(artifact_ref, str):
        return ("none", None)
    if len(artifact_ref) > MAX_ARTIFACT_REF_LEN:
        return ("none", None)
    if not isinstance(account_id, str) or not _SAFE_ACCOUNT_ID.fullmatch(account_id):
        return ("none", None)
    if artifact_ref.startswith(("/", "\\")) or ":" in artifact_ref:
        return ("none", None)

    posix = PurePosixPath(artifact_ref.replace("\\", "/"))
    parts = posix.parts
    if len(parts) != 3 or any(part in ("", ".", "..") for part in parts):
        return ("none", None)
    if parts[0] != account_id or parts[1] != "shots":
        return ("none", None)
    if not parts[2].lower().endswith(".png"):
        return ("none", None)

    try:
        root = artifacts_dir.resolve()
        shots_root = (root / account_id / "shots").resolve()
        candidate = (root / Path(*parts)).resolve()
        if not candidate.is_relative_to(shots_root) or candidate.parent != shots_root:
            return ("none", None)
        if shots_root.parent.name != account_id or shots_root.parent.parent != root:
            return ("none", None)
        if root.is_dir():
            exact_dirs = {entry.name for entry in root.iterdir() if entry.is_dir()}
            if (root / account_id).exists() and account_id not in exact_dirs:
                return ("none", None)
        if candidate.is_file():
            return ("available", candidate)
        return ("expired", None)
    except (OSError, ValueError, RuntimeError):
        return ("none", None)
