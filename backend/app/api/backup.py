from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException, Request, Response

from ..backup import export_backup, import_backup
from ..backup_archive import (
    MAX_ARCHIVE_COMPRESSED_BYTES,
    export_backup_archive,
    import_backup_archive,
)
from ..core.database import SessionDep
from ..schemas import BackupImportResult, BackupRead
from .deps import CurrentUser

router = APIRouter(prefix="/backup", tags=["backup"])


@router.get("", response_model=BackupRead)
def read_backup(session: SessionDep, user: CurrentUser) -> BackupRead:
    """导出当前平台账号的契约与运行记录 JSON（不含任何凭据；Profile 登录态走文件系统备份）。"""
    return export_backup(session, user.id)


@router.post("/import", response_model=BackupImportResult)
def restore_backup(payload: BackupRead, session: SessionDep, user: CurrentUser) -> BackupImportResult:
    """按 id upsert 导入：空库=完整恢复，已有库=无损合并。导入数据归属当前用户。"""
    try:
        return import_backup(session, payload, user.id)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from None


@router.get("/archive")
def read_backup_archive(session: SessionDep, user: CurrentUser) -> Response:
    """导出包含图片模板的跨端标准 `.zip` 完整备份包（不含凭据与宿主路径）。"""
    archive_bytes = export_backup_archive(session, user.id)
    filename = f"flame-backup-{datetime.now(timezone.utc).strftime('%Y-%m-%d')}.zip"
    return Response(
        content=archive_bytes,
        media_type="application/zip",
        headers={
            "Content-Disposition": f'attachment; filename="{filename}"',
            "Cache-Control": "no-store",
        },
    )


@router.post("/import-archive", response_model=BackupImportResult)
async def restore_backup_archive(
    request: Request,
    session: SessionDep,
    user: CurrentUser,
) -> BackupImportResult:
    """导入 `.zip` 完整备份包或纯 `.json` 备份（流式限额 <= 60 MB，零 python-multipart 依赖）。"""
    content_length = request.headers.get("content-length")
    if content_length is not None:
        try:
            if int(content_length) > MAX_ARCHIVE_COMPRESSED_BYTES:
                raise HTTPException(
                    status_code=413,
                    detail="备份文件超过 60 MB 上限，已拒绝上传",
                )
        except ValueError:
            pass

    buf = bytearray()
    async for chunk in request.stream():
        if not chunk:
            continue
        buf.extend(chunk)
        if len(buf) > MAX_ARCHIVE_COMPRESSED_BYTES:
            raise HTTPException(
                status_code=413,
                detail="备份文件超过 60 MB 上限，已拒绝上传",
            )

    if not buf:
        raise HTTPException(status_code=422, detail="备份文件内容为空")

    settings = request.app.state.settings
    try:
        return import_backup_archive(session, bytes(buf), user.id, settings)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from None
