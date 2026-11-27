from datetime import datetime
from zoneinfo import ZoneInfo

from fastapi import APIRouter, HTTPException, Request, status
from sqlmodel import select

from ..core.database import SessionDep
from ..execution.browser_runtime import detect_browser_runtime
from ..models import Account, SendSchedule
from ..schemas import (
    BrowserRuntimeRead,
    NotificationTestRead,
    SchedulerSettingsPatch,
    SchedulerStatusRead,
    SendScheduleRead,
)
from .deps import CurrentUser

router = APIRouter(prefix="/scheduler", tags=["scheduler"])


def _today_schedules(
    session: SessionDep, scheduler, user_id: str
) -> list[SendScheduleRead]:
    """聚合当前用户各账号今日的调度计划（按账号本地时区的 business_date）。"""
    records: list[SendScheduleRead] = []
    accounts = session.exec(
        select(Account).where(Account.user_id == user_id)
    ).all()
    for account in accounts:
        business_date = datetime.now(ZoneInfo(account.timezone)).date()
        schedule = session.exec(
            select(SendSchedule).where(
                SendSchedule.account_id == account.id,
                SendSchedule.business_date == business_date,
            )
        ).first()
        if schedule is not None:
            records.append(SendScheduleRead.model_validate(schedule))
    records.sort(key=lambda r: (r.account_id, r.business_date))
    return records


def _scheduler_read(
    request: Request, session: SessionDep, user_id: str
) -> SchedulerStatusRead:
    scheduler = request.app.state.scheduler
    settings = request.app.state.settings
    auto_scan_status = getattr(scheduler, "auto_scan_status", None)
    runtime = detect_browser_runtime(explicit_executable=settings.browser_executable)
    return SchedulerStatusRead(
        enabled=scheduler.enabled,
        paused=scheduler.paused,
        running_accounts=sorted(scheduler.running_accounts),
        today_schedules=_today_schedules(session, scheduler, user_id),
        auto_scan_interval_hours=getattr(scheduler, "auto_scan_interval_hours", 24),
        scheduled_always_send=getattr(scheduler, "scheduled_always_send", False),
        scheduled_skip_official_lit_today=getattr(
            scheduler, "scheduled_skip_official_lit_today", False
        ),
        fixed_time_grace_minutes=getattr(scheduler, "fixed_time_grace_minutes", 30),
        bark_configured=bool(getattr(scheduler, "notifier_enabled", False)),
        bark_masked_url=getattr(scheduler, "bark_masked_url", None),
        bark_source=getattr(scheduler, "bark_source", "none"),
        browser_runtime=BrowserRuntimeRead(
            ready=runtime.ready,
            kind=runtime.kind,
            label=runtime.label,
        ),
        auto_scans=auto_scan_status() if callable(auto_scan_status) else [],
    )


@router.get("", response_model=SchedulerStatusRead)
def read_scheduler_status(
    request: Request, session: SessionDep, user: CurrentUser
) -> SchedulerStatusRead:
    return _scheduler_read(request, session, user.id)


@router.patch("/settings", response_model=SchedulerStatusRead)
def update_scheduler_settings(
    request: Request,
    session: SessionDep,
    payload: SchedulerSettingsPatch,
    user: CurrentUser,
) -> SchedulerStatusRead:
    """按需更新调度与通知设置：只应用请求里显式给出的字段。"""
    scheduler = request.app.state.scheduler
    provided = payload.model_fields_set
    if "bark_url" in provided:
        if not user.is_superuser:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="仅平台管理员可修改全局 Bark 推送地址",
            )
        try:
            scheduler.update_bark_url(payload.bark_url)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
    if "auto_scan_interval_hours" in provided:
        scheduler.update_auto_scan_interval(payload.auto_scan_interval_hours)
    if "scheduled_always_send" in provided:
        scheduler.update_scheduled_always_send(payload.scheduled_always_send)
    if "scheduled_skip_official_lit_today" in provided:
        scheduler.update_scheduled_skip_official_lit_today(
            payload.scheduled_skip_official_lit_today
        )
    if "fixed_time_grace_minutes" in provided:
        scheduler.update_fixed_time_grace_minutes(payload.fixed_time_grace_minutes)
    return _scheduler_read(request, session, user.id)


@router.post("/pause", response_model=SchedulerStatusRead)
def pause_scheduler(
    request: Request, session: SessionDep, user: CurrentUser
) -> SchedulerStatusRead:
    scheduler = request.app.state.scheduler
    scheduler.pause()
    return _scheduler_read(request, session, user.id)


@router.post("/resume", response_model=SchedulerStatusRead)
def resume_scheduler(
    request: Request, session: SessionDep, user: CurrentUser
) -> SchedulerStatusRead:
    scheduler = request.app.state.scheduler
    scheduler.resume()
    return _scheduler_read(request, session, user.id)


@router.post("/test-notification", response_model=NotificationTestRead)
async def test_scheduler_notification(
    request: Request, user: CurrentUser  # noqa: ARG001
) -> NotificationTestRead:
    """发送一条 Bark 连通性测试推送，返回脱敏诊断结果（不暴露 device_key）。"""
    scheduler = request.app.state.scheduler
    ok, detail = await scheduler.send_test_notification()
    return NotificationTestRead(ok=ok, detail=detail)
