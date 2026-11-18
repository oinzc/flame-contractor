"""selector bundle 热更新与持久卷覆盖层管理 API。"""

from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict

from ..execution import selectors as _selectors
from .deps import CurrentSuperUser, CurrentUser

router = APIRouter(prefix="/selectors", tags=["selectors"])


class SelectorOverridesPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    overrides: dict[str, object]


@router.get("")
def read_selectors(user: CurrentUser) -> dict:  # noqa: ARG001
    """返回当前生效 selector 快照、内置基线与覆盖项元数据（只读，不含任何凭据）。"""
    return _selectors.bundle_metadata()


@router.post("/reload")
def reload_selectors(user: CurrentUser) -> dict:  # noqa: ARG001
    """强制重新加载基线与覆盖文件，返回最新元数据。"""
    _selectors.reload_bundle(force=True)
    return _selectors.bundle_metadata()


@router.put("")
def update_selector_overrides(
    payload: SelectorOverridesPayload,
    user: CurrentSuperUser,  # noqa: ARG001
) -> dict:
    """校验并将自定义选择器覆盖项原子写入 data/selectors_override.json（仅平台管理员）。"""
    try:
        _selectors.validate_and_save_overrides(payload.overrides)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return _selectors.bundle_metadata()


@router.delete("/override")
def reset_selector_overrides(user: CurrentSuperUser) -> dict:  # noqa: ARG001
    """删除持久覆盖文件，立即恢复内置默认选择器（仅平台管理员）。"""
    _selectors.clear_overrides()
    return _selectors.bundle_metadata()
