"""M8-3 备份导入/导出：契约（账号/好友/模板含文案池）与运行记录的 JSON 往返。

设计要点：
- 导出为单一 JSON（schema_version=1），含 accounts/templates(+pool_items)/
  targets/schedules/jobs/attempts 全量；不含任何凭据（Profile 登录态属文件系统
  层，不经 API 导出）。
- 导入为**按 id + 自然键无损合并**（先按当前用户归属 id 匹配，再按自然键合并；
  对空库即完整恢复，对已有库是无损合并，跨用户固定 ID 自动隔离重映射）。
- 插入顺序按外键依赖：accounts → templates → pool_items → targets →
  schedules → jobs → attempts。
"""

from datetime import date, datetime, timezone
from typing import Any

from sqlalchemy import Date as _SaDate, DateTime as _SaDateTime
from sqlmodel import Session, select

from .models import (
    Account,
    AccountStatus,
    AttemptStatus,
    ContractTarget,
    FailureClass,
    JobStatus,
    MessageTemplate,
    ScheduleStatus,
    SendAttempt,
    SendJob,
    SendSchedule,
    TemplatePoolItem,
    TemplateType,
    new_uuid,
)
from .schemas import BackupData, BackupImportResult, BackupRead

BACKUP_SCHEMA_VERSION = 1
_TEMPLATE_NAME_MAX_LEN = 60
_PROTECTED_JOB_STATUSES = (
    JobStatus.UNKNOWN_REQUIRES_REVIEW,
    JobStatus.SENDING,
    JobStatus.CONFIRMING,
)
_PROTECTED_SCHEDULE_STATUSES = (
    ScheduleStatus.LEASED,
    ScheduleStatus.SENDING,
    ScheduleStatus.UNKNOWN_REQUIRES_REVIEW,
)

# 导入顺序（外键依赖决定）
_IMPORT_ORDER = [
    ("accounts", Account),
    ("templates", MessageTemplate),
    ("pool_items", TemplatePoolItem),
    ("targets", ContractTarget),
    ("schedules", SendSchedule),
    ("jobs", SendJob),
    ("attempts", SendAttempt),
]


def _user_scoped(session: Session, user_id: str) -> dict[str, set]:
    """按当前用户收集可见数据的外键集合，用于导出/导入隔离。

    accounts/templates 直接按 user_id；targets/schedules/jobs/attempts/pool_items
    通过所属 account/template 的 user_id 派生。返回各表可导出的行 id 集合的映射。
    """
    accounts = session.exec(
        select(Account.id).where(Account.user_id == user_id)
    ).all()
    templates = session.exec(
        select(MessageTemplate.id).where(MessageTemplate.user_id == user_id)
    ).all()
    targets = (
        session.exec(
            select(ContractTarget.id).where(ContractTarget.account_id.in_(accounts))
        ).all()
        if accounts
        else []
    )
    schedules = (
        session.exec(
            select(SendSchedule.id).where(SendSchedule.account_id.in_(accounts))
        ).all()
        if accounts
        else []
    )
    jobs = (
        session.exec(
            select(SendJob.id).where(SendJob.account_id.in_(accounts))
        ).all()
        if accounts
        else []
    )
    attempts = (
        session.exec(
            select(SendAttempt.id).where(SendAttempt.job_id.in_(jobs))
        ).all()
        if jobs
        else []
    )
    pool_items = (
        session.exec(
            select(TemplatePoolItem.id).where(TemplatePoolItem.template_id.in_(templates))
        ).all()
        if templates
        else []
    )
    return {
        "accounts": set(accounts),
        "templates": set(templates),
        "pool_items": set(pool_items),
        "targets": set(targets),
        "schedules": set(schedules),
        "jobs": set(jobs),
        "attempts": set(attempts),
    }


def export_backup(session: Session, user_id: str) -> BackupRead:
    data = {}
    scope = _user_scoped(session, user_id)
    for key, model in _IMPORT_ORDER:
        allowed = scope.get(key, set())
        rows = session.exec(select(model)).all()
        data[key] = [
            row.model_dump(mode="json") for row in rows if row.id in allowed
        ]
    return BackupRead(
        app="flame-contractor",
        schema_version=BACKUP_SCHEMA_VERSION,
        exported_at=datetime.now(timezone.utc),
        data=BackupData(**data),
    )


def _as_utc(dt: datetime | None) -> datetime | None:
    """将 SQLite 读出的 naive datetime 与带时区 datetime 统一规整为 UTC aware。"""
    if dt is None:
        return None
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def _is_newer_dt(incoming: datetime | None, existing: datetime | None) -> bool:
    if incoming is None:
        return False
    if existing is None:
        return True
    return _as_utc(incoming) > _as_utc(existing)  # type: ignore[operator]


def _coerce_enum(enum_cls, value: Any, *, default: Any = None):
    if value is None:
        return default
    if isinstance(value, enum_cls):
        return value
    try:
        return enum_cls(value)
    except ValueError:
        return default


def _coerce_failure_class(row: dict) -> None:
    if "failure_class" in row and row["failure_class"] is not None:
        row["failure_class"] = _coerce_enum(
            FailureClass, row["failure_class"], default=FailureClass.OTHER
        )


def _normalize_conversation_type(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    normalized = value.strip().lower()
    if normalized == "friend":
        return "user"
    if normalized in ("user", "group"):
        return normalized
    return None


def _normalize_optional_str(value: Any) -> str | None:
    if not isinstance(value, str):
        return value
    stripped = value.strip()
    return stripped or None


def _coerce_row(model, row: dict) -> dict:
    """过滤模型未知字段并按 SQLAlchemy 列类型还原日期/时间，避免额外扩展字段导致报错。"""
    table = model.__table__
    cleaned: dict[str, Any] = {}
    for key, value in row.items():
        column = table.c.get(key)
        if column is None:
            continue
        if value is None:
            if not column.nullable and column.default is not None:
                continue
            cleaned[key] = None
            continue
        if isinstance(value, str):
            if isinstance(column.type, _SaDateTime):
                cleaned[key] = datetime.fromisoformat(value)
                continue
            if isinstance(column.type, _SaDate):
                cleaned[key] = (
                    datetime.fromisoformat(value).date()
                    if "T" in value
                    else date.fromisoformat(value)
                )
                continue
        cleaned[key] = value
    return cleaned


def _allocate_local_id(
    session: Session, model, src_id: str | None, used_ids: set[str]
) -> str:
    """若源 id 尚未被其他用户或本批占用则保留，否则分配全新 UUID 实现跨用户隔离。"""
    if (
        isinstance(src_id, str)
        and src_id
        and src_id not in used_ids
        and session.get(model, src_id) is None
    ):
        used_ids.add(src_id)
        return src_id
    local_id = new_uuid()
    used_ids.add(local_id)
    return local_id


def _apply_row_updates(
    existing: Any,
    row: dict,
    *,
    skip_fields: set[str] | frozenset[str] = frozenset({"id"}),
) -> None:
    for field, value in row.items():
        if field in skip_fields:
            continue
        setattr(existing, field, value)


def _remap_owned_id(
    src_id: Any, id_map: dict[str, str], allowed: set[str]
) -> str | None:
    """将源外键 id 重映射到当前用户的本地 id；缺失或非本用户归属时返回 None。"""
    if not isinstance(src_id, str) or not src_id:
        return None
    mapped = id_map.get(src_id, src_id)
    return mapped if mapped in allowed else None


def _update_nk_index(by_nk: dict, old_nk: Any, new_nk: Any, obj: Any) -> None:
    if old_nk != new_nk:
        by_nk.pop(old_nk, None)
    by_nk[new_nk] = obj


def _unique_template_name(base_name: str, taken_names: set[str]) -> str:
    suffix = " (导入)"
    candidate = f"{base_name[: _TEMPLATE_NAME_MAX_LEN - len(suffix)]}{suffix}"
    if candidate not in taken_names:
        return candidate
    idx = 2
    while True:
        suffix_n = f" (导入 {idx})"
        candidate = f"{base_name[: _TEMPLATE_NAME_MAX_LEN - len(suffix_n)]}{suffix_n}"
        if candidate not in taken_names:
            return candidate
        idx += 1


def _import_accounts(
    session: Session,
    rows: list[dict],
    user_id: str,
    scope: dict[str, set],
    inserted: dict[str, int],
    updated: dict[str, int],
) -> dict[str, str]:
    user_accounts = session.exec(
        select(Account).where(Account.user_id == user_id)
    ).all()
    by_id = {acc.id: acc for acc in user_accounts}
    by_name = {acc.display_name: acc for acc in user_accounts}
    used_ids: set[str] = set(by_id)
    id_map: dict[str, str] = {}

    for raw_row in rows:
        row = _coerce_row(Account, raw_row)
        row["user_id"] = user_id
        if "status" in row:
            row["status"] = _coerce_enum(
                AccountStatus, row["status"], default=AccountStatus.PENDING_LOGIN
            )

        src_id = row.get("id")
        display_name = row.get("display_name")
        existing = by_id.get(src_id) if src_id else None
        if existing is None and display_name:
            existing = by_name.get(display_name)

        if existing is not None:
            old_name = existing.display_name
            target_name = display_name or old_name
            # 若改名后与当前用户的另一账号重名，保留原名防止破坏唯一约束
            if (
                target_name != old_name
                and target_name in by_name
                and by_name[target_name].id != existing.id
            ):
                row["display_name"] = old_name

            incoming_status = row.get("status")
            preserve_healthy = (
                existing.status != AccountStatus.PENDING_LOGIN
                and incoming_status == AccountStatus.PENDING_LOGIN
            )
            if preserve_healthy:
                row["status"] = existing.status
                row["last_verified_at"] = existing.last_verified_at
                row["douyin_nickname"] = existing.douyin_nickname

            if not _is_newer_dt(row.get("last_success_at"), existing.last_success_at):
                row["last_success_at"] = existing.last_success_at

            row["failure_streak"] = max(
                existing.failure_streak or 0, row.get("failure_streak") or 0
            )
            _apply_row_updates(existing, row, skip_fields={"id", "user_id"})
            session.add(existing)
            session.flush()

            if old_name != existing.display_name:
                by_name.pop(old_name, None)
            by_name[existing.display_name] = existing
            if src_id:
                id_map[src_id] = existing.id
            updated["accounts"] += 1
            continue

        local_id = _allocate_local_id(session, Account, src_id, used_ids)
        row["id"] = local_id
        account = Account(**row)
        session.add(account)
        session.flush()

        by_id[local_id] = account
        by_name[account.display_name] = account
        scope["accounts"].add(local_id)
        if src_id:
            id_map[src_id] = local_id
        inserted["accounts"] += 1

    return id_map


def _import_templates(
    session: Session,
    rows: list[dict],
    user_id: str,
    scope: dict[str, set],
    inserted: dict[str, int],
    updated: dict[str, int],
) -> dict[str, str]:
    user_templates = session.exec(
        select(MessageTemplate).where(MessageTemplate.user_id == user_id)
    ).all()
    by_id = {tpl.id: tpl for tpl in user_templates}
    by_name = {tpl.name: tpl for tpl in user_templates}
    used_ids: set[str] = set(by_id)
    id_map: dict[str, str] = {}

    for raw_row in rows:
        row = _coerce_row(MessageTemplate, raw_row)
        row["user_id"] = user_id
        if "type" in row:
            row["type"] = _coerce_enum(
                TemplateType, row["type"], default=TemplateType.TEXT
            )
        incoming_type = row.get("type", TemplateType.TEXT)
        incoming_name = row.get("name")
        src_id = row.get("id")

        existing = by_id.get(src_id) if src_id else None
        if existing is not None:
            old_name = existing.name
            if (
                incoming_name
                and incoming_name != old_name
                and incoming_name in by_name
                and by_name[incoming_name].id != existing.id
            ):
                row["name"] = _unique_template_name(incoming_name, set(by_name))
            if (
                existing.type == TemplateType.IMAGE
                and existing.image_path is not None
                and row.get("image_path") is None
            ):
                row["image_path"] = existing.image_path
            _apply_row_updates(existing, row, skip_fields={"id", "user_id"})
            session.add(existing)
            session.flush()
            if old_name != existing.name:
                by_name.pop(old_name, None)
            by_name[existing.name] = existing
            if src_id:
                id_map[src_id] = existing.id
            updated["templates"] += 1
            continue

        same_name = by_name.get(incoming_name) if incoming_name else None
        if same_name is not None and same_name.type == incoming_type:
            if (
                same_name.type == TemplateType.IMAGE
                and same_name.image_path is not None
                and row.get("image_path") is None
            ):
                row["image_path"] = same_name.image_path
            _apply_row_updates(same_name, row, skip_fields={"id", "user_id"})
            session.add(same_name)
            session.flush()
            if src_id:
                id_map[src_id] = same_name.id
            updated["templates"] += 1
            continue

        force_fresh_uuid = False
        if same_name is not None and same_name.type != incoming_type:
            row["name"] = _unique_template_name(incoming_name, set(by_name))
            force_fresh_uuid = True

        local_id = (
            _allocate_local_id(session, MessageTemplate, None, used_ids)
            if force_fresh_uuid
            else _allocate_local_id(session, MessageTemplate, src_id, used_ids)
        )
        row["id"] = local_id
        template = MessageTemplate(**row)
        session.add(template)
        session.flush()

        by_id[local_id] = template
        by_name[template.name] = template
        scope["templates"].add(local_id)
        if src_id:
            id_map[src_id] = local_id
        inserted["templates"] += 1

    return id_map


def _import_pool_items(
    session: Session,
    rows: list[dict],
    template_id_map: dict[str, str],
    scope: dict[str, set],
    inserted: dict[str, int],
    updated: dict[str, int],
) -> None:
    user_items = (
        session.exec(
            select(TemplatePoolItem).where(
                TemplatePoolItem.template_id.in_(scope["templates"])
            )
        ).all()
        if scope["templates"]
        else []
    )
    by_id = {item.id: item for item in user_items}
    by_nk = {(item.template_id, item.content, item.weekday_mask): item for item in user_items}
    used_ids: set[str] = set(by_id)

    for raw_row in rows:
        row = _coerce_row(TemplatePoolItem, raw_row)
        mapped_tpl_id = _remap_owned_id(
            row.get("template_id"), template_id_map, scope["templates"]
        )
        if not mapped_tpl_id:
            continue
        row["template_id"] = mapped_tpl_id

        src_id = row.get("id")
        existing = by_id.get(src_id) if src_id else None
        if existing is None:
            nk = (mapped_tpl_id, row.get("content"), row.get("weekday_mask", 0))
            existing = by_nk.get(nk)

        if existing is not None:
            old_nk = (existing.template_id, existing.content, existing.weekday_mask)
            if not _is_newer_dt(row.get("last_used_at"), existing.last_used_at):
                row["last_used_at"] = existing.last_used_at
            _apply_row_updates(existing, row)
            session.add(existing)
            session.flush()
            new_nk = (existing.template_id, existing.content, existing.weekday_mask)
            _update_nk_index(by_nk, old_nk, new_nk, existing)
            updated["pool_items"] += 1
            continue

        local_id = _allocate_local_id(session, TemplatePoolItem, src_id, used_ids)
        row["id"] = local_id
        item = TemplatePoolItem(**row)
        session.add(item)
        session.flush()
        by_id[local_id] = item
        by_nk[(item.template_id, item.content, item.weekday_mask)] = item
        scope["pool_items"].add(local_id)
        inserted["pool_items"] += 1


def _target_conflicts(
    *,
    account_id: str,
    nickname: str | None,
    platform_user_id: str | None,
    platform_conversation_id: str | None,
    exclude_id: str | None,
    by_nick: dict[tuple[str, str], ContractTarget],
    by_puid: dict[tuple[str, str], ContractTarget],
    by_pcid: dict[tuple[str, str], ContractTarget],
) -> bool:
    if nickname:
        other = by_nick.get((account_id, nickname))
        if other is not None and other.id != exclude_id:
            return True
    if platform_user_id:
        other = by_puid.get((account_id, platform_user_id))
        if other is not None and other.id != exclude_id:
            return True
    if platform_conversation_id:
        other = by_pcid.get((account_id, platform_conversation_id))
        if other is not None and other.id != exclude_id:
            return True
    return False


def _register_target_indexes(
    target: ContractTarget,
    by_nick: dict[tuple[str, str], ContractTarget],
    by_puid: dict[tuple[str, str], ContractTarget],
    by_pcid: dict[tuple[str, str], ContractTarget],
) -> None:
    by_nick[(target.account_id, target.nickname_snapshot)] = target
    if target.platform_user_id:
        by_puid[(target.account_id, target.platform_user_id)] = target
    if target.platform_conversation_id:
        by_pcid[(target.account_id, target.platform_conversation_id)] = target


def _unregister_target_indexes(
    target: ContractTarget,
    by_nick: dict[tuple[str, str], ContractTarget],
    by_puid: dict[tuple[str, str], ContractTarget],
    by_pcid: dict[tuple[str, str], ContractTarget],
) -> None:
    by_nick.pop((target.account_id, target.nickname_snapshot), None)
    if target.platform_user_id:
        by_puid.pop((target.account_id, target.platform_user_id), None)
    if target.platform_conversation_id:
        by_pcid.pop((target.account_id, target.platform_conversation_id), None)


def _merge_existing_target(
    session: Session,
    existing: ContractTarget,
    row: dict,
    mapped_aid: str,
    by_nick: dict[tuple[str, str], ContractTarget],
    by_puid: dict[tuple[str, str], ContractTarget],
    by_pcid: dict[tuple[str, str], ContractTarget],
) -> bool:
    """校验无唯一键冲突后无损合并已有契约目标；发生冲突返回 False。"""
    final_nick = row.get("nickname_snapshot") or existing.nickname_snapshot
    final_puid = (
        existing.platform_user_id
        if row.get("platform_user_id") is None
        else row["platform_user_id"]
    )
    final_pcid = (
        existing.platform_conversation_id
        if row.get("platform_conversation_id") is None
        else row["platform_conversation_id"]
    )
    final_conv_type = (
        existing.conversation_type
        if row.get("conversation_type") is None
        else row["conversation_type"]
    )
    if _target_conflicts(
        account_id=mapped_aid,
        nickname=final_nick,
        platform_user_id=final_puid,
        platform_conversation_id=final_pcid,
        exclude_id=existing.id,
        by_nick=by_nick,
        by_puid=by_puid,
        by_pcid=by_pcid,
    ):
        return False

    _unregister_target_indexes(existing, by_nick, by_puid, by_pcid)

    incoming_deleted = row.get("deleted_at")
    row["nickname_snapshot"] = final_nick
    row["platform_user_id"] = final_puid
    row["platform_conversation_id"] = final_pcid
    row["conversation_type"] = final_conv_type
    row["deleted_at"] = (
        None
        if (existing.deleted_at is None or incoming_deleted is None)
        else incoming_deleted
    )
    row["enabled"] = (
        existing.enabled
        if (existing.deleted_at is None and incoming_deleted is not None)
        else row.get("enabled", existing.enabled)
    )
    row["streak_days"] = max(
        existing.streak_days or 0, row.get("streak_days") or 0
    )
    if row.get("send_time_hhmm") is None:
        row["send_time_hhmm"] = existing.send_time_hhmm
    if not _is_newer_dt(row.get("spark_scanned_at"), existing.spark_scanned_at):
        row["spark_days"] = existing.spark_days
        row["spark_kind"] = existing.spark_kind
        row["spark_label"] = existing.spark_label
        row["spark_scanned_at"] = existing.spark_scanned_at
    if not _is_newer_dt(row.get("last_sent_at"), existing.last_sent_at):
        row["last_sent_at"] = existing.last_sent_at
    if not _is_newer_dt(row.get("last_confirmed_at"), existing.last_confirmed_at):
        row["last_confirmed_at"] = existing.last_confirmed_at

    _apply_row_updates(existing, row)
    session.add(existing)
    session.flush()
    _register_target_indexes(existing, by_nick, by_puid, by_pcid)
    return True


def _import_targets(
    session: Session,
    rows: list[dict],
    account_id_map: dict[str, str],
    template_id_map: dict[str, str],
    scope: dict[str, set],
    inserted: dict[str, int],
    updated: dict[str, int],
) -> dict[str, str]:
    user_targets = (
        session.exec(
            select(ContractTarget).where(
                ContractTarget.account_id.in_(scope["accounts"])
            )
        ).all()
        if scope["accounts"]
        else []
    )
    by_id = {target.id: target for target in user_targets}
    by_nick: dict[tuple[str, str], ContractTarget] = {}
    by_puid: dict[tuple[str, str], ContractTarget] = {}
    by_pcid: dict[tuple[str, str], ContractTarget] = {}
    for target in user_targets:
        _register_target_indexes(target, by_nick, by_puid, by_pcid)

    used_ids: set[str] = set(by_id)
    id_map: dict[str, str] = {}

    for raw_row in rows:
        row = _coerce_row(ContractTarget, raw_row)
        mapped_aid = _remap_owned_id(
            row.get("account_id"), account_id_map, scope["accounts"]
        )
        if not mapped_aid:
            continue
        row["account_id"] = mapped_aid
        row["template_id"] = _remap_owned_id(
            row.get("template_id"), template_id_map, scope["templates"]
        )

        if "conversation_type" in row:
            row["conversation_type"] = _normalize_conversation_type(
                row.get("conversation_type")
            )
        if "platform_user_id" in row:
            row["platform_user_id"] = _normalize_optional_str(
                row.get("platform_user_id")
            )
        if "platform_conversation_id" in row:
            row["platform_conversation_id"] = _normalize_optional_str(
                row.get("platform_conversation_id")
            )

        src_id = row.get("id")
        existing = by_id.get(src_id) if src_id else None
        if existing is None and row.get("platform_user_id"):
            existing = by_puid.get((mapped_aid, row["platform_user_id"]))
        if existing is None and row.get("platform_conversation_id"):
            existing = by_pcid.get((mapped_aid, row["platform_conversation_id"]))
        if existing is None and row.get("nickname_snapshot"):
            existing = by_nick.get((mapped_aid, row["nickname_snapshot"]))

        if existing is not None:
            if _merge_existing_target(
                session, existing, row, mapped_aid, by_nick, by_puid, by_pcid
            ):
                if src_id:
                    id_map[src_id] = existing.id
                updated["targets"] += 1
            continue

        if not row.get("nickname_snapshot"):
            continue
        if _target_conflicts(
            account_id=mapped_aid,
            nickname=row.get("nickname_snapshot"),
            platform_user_id=row.get("platform_user_id"),
            platform_conversation_id=row.get("platform_conversation_id"),
            exclude_id=None,
            by_nick=by_nick,
            by_puid=by_puid,
            by_pcid=by_pcid,
        ):
            continue

        local_id = _allocate_local_id(session, ContractTarget, src_id, used_ids)
        row["id"] = local_id
        target = ContractTarget(**row)
        session.add(target)
        session.flush()
        by_id[local_id] = target
        _register_target_indexes(target, by_nick, by_puid, by_pcid)
        scope["targets"].add(local_id)
        if src_id:
            id_map[src_id] = local_id
        inserted["targets"] += 1

    return id_map


def _is_schedule_protected(existing: SendSchedule, now_utc: datetime) -> bool:
    if existing.status in _PROTECTED_SCHEDULE_STATUSES:
        return True
    if existing.lease_owner and existing.lease_expires_at is not None:
        return _as_utc(existing.lease_expires_at) > now_utc  # type: ignore[operator]
    return False


def _normalize_schedule_status(row: dict) -> None:
    raw = row.get("status")
    raw_str = raw.value if isinstance(raw, ScheduleStatus) else raw
    if raw_str in ("running", "leased"):
        row["status"] = ScheduleStatus.FAILED
        row["lease_owner"] = None
        row["lease_expires_at"] = None
    elif raw_str == "sending":
        row["status"] = ScheduleStatus.UNKNOWN_REQUIRES_REVIEW
        row["lease_owner"] = None
        row["lease_expires_at"] = None
    elif "status" in row:
        row["status"] = _coerce_enum(
            ScheduleStatus, raw, default=ScheduleStatus.PENDING
        )


def _import_schedules(
    session: Session,
    rows: list[dict],
    account_id_map: dict[str, str],
    scope: dict[str, set],
    inserted: dict[str, int],
    updated: dict[str, int],
) -> None:
    user_schedules = (
        session.exec(
            select(SendSchedule).where(SendSchedule.account_id.in_(scope["accounts"]))
        ).all()
        if scope["accounts"]
        else []
    )
    by_id = {sched.id: sched for sched in user_schedules}
    by_nk = {(sched.account_id, sched.business_date): sched for sched in user_schedules}
    used_ids: set[str] = set(by_id)
    now_utc = datetime.now(timezone.utc)

    for raw_row in rows:
        row = _coerce_row(SendSchedule, raw_row)
        mapped_aid = _remap_owned_id(
            row.get("account_id"), account_id_map, scope["accounts"]
        )
        if not mapped_aid:
            continue
        row["account_id"] = mapped_aid
        _normalize_schedule_status(row)

        src_id = row.get("id")
        existing = by_id.get(src_id) if src_id else None
        if existing is None and row.get("business_date") is not None:
            existing = by_nk.get((mapped_aid, row["business_date"]))

        if existing is not None:
            if _is_schedule_protected(existing, now_utc):
                continue
            old_nk = (existing.account_id, existing.business_date)
            new_date = row.get("business_date", existing.business_date)
            new_nk = (mapped_aid, new_date)
            conflict = by_nk.get(new_nk)
            if conflict is not None and conflict.id != existing.id:
                continue
            _apply_row_updates(existing, row)
            session.add(existing)
            session.flush()
            _update_nk_index(by_nk, old_nk, new_nk, existing)
            updated["schedules"] += 1
            continue

        if row.get("business_date") is None:
            continue
        nk = (mapped_aid, row["business_date"])
        if nk in by_nk:
            continue
        local_id = _allocate_local_id(session, SendSchedule, src_id, used_ids)
        row["id"] = local_id
        schedule = SendSchedule(**row)
        session.add(schedule)
        session.flush()
        by_id[local_id] = schedule
        by_nk[nk] = schedule
        scope["schedules"].add(local_id)
        inserted["schedules"] += 1


def _normalize_job_fields(row: dict) -> None:
    _coerce_failure_class(row)
    if "status" not in row:
        return
    raw = row.get("status")
    raw_str = raw.value if isinstance(raw, JobStatus) else raw
    if raw_str in ("sending", "confirming"):
        row["status"] = JobStatus.UNKNOWN_REQUIRES_REVIEW
        row["lease_owner"] = None
        row["lease_expires_at"] = None
    elif raw_str in ("leased", "opening_chat"):
        row["status"] = JobStatus.FAILED_RETRYABLE
        row["lease_owner"] = None
        row["lease_expires_at"] = None
    else:
        row["status"] = _coerce_enum(
            JobStatus, raw, default=JobStatus.FAILED_RETRYABLE
        )


def _import_jobs(
    session: Session,
    rows: list[dict],
    account_id_map: dict[str, str],
    target_id_map: dict[str, str],
    template_id_map: dict[str, str],
    scope: dict[str, set],
    inserted: dict[str, int],
    updated: dict[str, int],
) -> dict[str, str]:
    user_jobs = (
        session.exec(
            select(SendJob).where(SendJob.account_id.in_(scope["accounts"]))
        ).all()
        if scope["accounts"]
        else []
    )
    by_id = {job.id: job for job in user_jobs}
    by_nk = {
        (job.account_id, job.target_id, job.business_date, job.message_slot_id): job
        for job in user_jobs
    }
    used_ids: set[str] = set(by_id)
    id_map: dict[str, str] = {}

    for raw_row in rows:
        row = _coerce_row(SendJob, raw_row)
        mapped_aid = _remap_owned_id(
            row.get("account_id"), account_id_map, scope["accounts"]
        )
        if not mapped_aid:
            continue
        row["account_id"] = mapped_aid

        mapped_target_id = _remap_owned_id(
            row.get("target_id"), target_id_map, scope["targets"]
        )
        if not mapped_target_id:
            continue
        row["target_id"] = mapped_target_id

        row["template_id"] = _remap_owned_id(
            row.get("template_id"), template_id_map, scope["templates"]
        )

        _normalize_job_fields(row)

        src_id = row.get("id")
        existing = by_id.get(src_id) if src_id else None
        if (
            existing is None
            and row.get("business_date") is not None
            and row.get("message_slot_id") is not None
        ):
            existing = by_nk.get(
                (mapped_aid, mapped_target_id, row["business_date"], row["message_slot_id"])
            )

        if existing is not None:
            if src_id:
                id_map[src_id] = existing.id
            if existing.status in _PROTECTED_JOB_STATUSES:
                continue
            old_nk = (
                existing.account_id,
                existing.target_id,
                existing.business_date,
                existing.message_slot_id,
            )
            new_nk = (
                mapped_aid,
                mapped_target_id,
                row.get("business_date", existing.business_date),
                row.get("message_slot_id", existing.message_slot_id),
            )
            conflict = by_nk.get(new_nk)
            if conflict is not None and conflict.id != existing.id:
                continue
            _apply_row_updates(existing, row)
            session.add(existing)
            session.flush()
            _update_nk_index(by_nk, old_nk, new_nk, existing)
            updated["jobs"] += 1
            continue

        if row.get("business_date") is None or not row.get("message_slot_id"):
            continue
        nk = (mapped_aid, mapped_target_id, row["business_date"], row["message_slot_id"])
        if nk in by_nk:
            continue

        local_id = _allocate_local_id(session, SendJob, src_id, used_ids)
        row["id"] = local_id
        job = SendJob(**row)
        session.add(job)
        session.flush()
        by_id[local_id] = job
        by_nk[nk] = job
        scope["jobs"].add(local_id)
        if src_id:
            id_map[src_id] = local_id
        inserted["jobs"] += 1

    return id_map


def _import_attempts(
    session: Session,
    rows: list[dict],
    job_id_map: dict[str, str],
    scope: dict[str, set],
    inserted: dict[str, int],
    updated: dict[str, int],
) -> None:
    user_attempts = (
        session.exec(
            select(SendAttempt).where(SendAttempt.job_id.in_(scope["jobs"]))
        ).all()
        if scope["jobs"]
        else []
    )
    by_id = {att.id: att for att in user_attempts}
    by_nk = {(att.job_id, att.attempt_no): att for att in user_attempts}
    used_ids: set[str] = set(by_id)

    for raw_row in rows:
        row = _coerce_row(SendAttempt, raw_row)
        mapped_jid = _remap_owned_id(
            row.get("job_id"), job_id_map, scope["jobs"]
        )
        if not mapped_jid:
            continue
        row["job_id"] = mapped_jid

        _coerce_failure_class(row)
        if "status" in row:
            raw_status = row.get("status")
            raw_str = raw_status.value if isinstance(raw_status, AttemptStatus) else raw_status
            row["status"] = (
                AttemptStatus.FAILED
                if raw_str == "running"
                else _coerce_enum(
                    AttemptStatus, raw_status, default=AttemptStatus.FAILED
                )
            )

        src_id = row.get("id")
        existing = by_id.get(src_id) if src_id else None
        if existing is None and row.get("attempt_no") is not None:
            existing = by_nk.get((mapped_jid, row["attempt_no"]))

        if existing is not None:
            old_nk = (existing.job_id, existing.attempt_no)
            new_nk = (mapped_jid, row.get("attempt_no", existing.attempt_no))
            conflict = by_nk.get(new_nk)
            if conflict is not None and conflict.id != existing.id:
                continue
            if existing.artifact_ref is not None and row.get("artifact_ref") is None:
                row["artifact_ref"] = existing.artifact_ref
            _apply_row_updates(existing, row)
            session.add(existing)
            session.flush()
            _update_nk_index(by_nk, old_nk, new_nk, existing)
            updated["attempts"] += 1
            continue

        if row.get("attempt_no") is None:
            continue
        nk = (mapped_jid, row["attempt_no"])
        if nk in by_nk:
            continue

        local_id = _allocate_local_id(session, SendAttempt, src_id, used_ids)
        row["id"] = local_id
        attempt = SendAttempt(**row)
        session.add(attempt)
        session.flush()
        by_id[local_id] = attempt
        by_nk[nk] = attempt
        scope["attempts"].add(local_id)
        inserted["attempts"] += 1


def import_backup(
    session: Session,
    backup: BackupRead,
    user_id: str,
    *,
    template_id_map_out: dict[str, str] | None = None,
    commit: bool = True,
) -> BackupImportResult:
    if backup.schema_version != BACKUP_SCHEMA_VERSION:
        raise ValueError(f"不支持的备份版本: {backup.schema_version}")

    payload = backup.data.model_dump()
    scope = _user_scoped(session, user_id)
    inserted = {key: 0 for key, _ in _IMPORT_ORDER}
    updated = {key: 0 for key, _ in _IMPORT_ORDER}

    account_id_map = _import_accounts(
        session, payload.get("accounts") or [], user_id, scope, inserted, updated
    )
    template_id_map = _import_templates(
        session, payload.get("templates") or [], user_id, scope, inserted, updated
    )
    if template_id_map_out is not None:
        template_id_map_out.update(template_id_map)
    _import_pool_items(
        session, payload.get("pool_items") or [], template_id_map, scope, inserted, updated
    )
    target_id_map = _import_targets(
        session,
        payload.get("targets") or [],
        account_id_map,
        template_id_map,
        scope,
        inserted,
        updated,
    )
    _import_schedules(
        session, payload.get("schedules") or [], account_id_map, scope, inserted, updated
    )
    job_id_map = _import_jobs(
        session,
        payload.get("jobs") or [],
        account_id_map,
        target_id_map,
        template_id_map,
        scope,
        inserted,
        updated,
    )
    _import_attempts(
        session, payload.get("attempts") or [], job_id_map, scope, inserted, updated
    )

    if commit:
        session.commit()
    else:
        session.flush()
    return BackupImportResult(inserted=inserted, updated=updated)
