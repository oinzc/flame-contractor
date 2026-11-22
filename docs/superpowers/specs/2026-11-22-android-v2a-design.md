# Flame Contractor 独立 Android 版 v2A 设计规范

- **日期**：2026-11-22
- **状态**：已完成独立构建审查与修订，待最终批准
- **范围**：`android/`（跨端兼容 JSON 备份/迁移、后台定时自动扫描与官方火花告急提醒、30 日历史记录与统计第 5 主 Tab）+ `backend/app/schemas.py` & `backend/app/backup.py`（跨端备份全双工兼容修复）
- **基线版本**：`v0.4.0`（Room DB `version = 2`；4 主导航 Tab）

---

## 1. 背景与目标（Intent & Why）

独立 Android 版 v1（`v0.4.0`）已完成核心续火闭环，但在长期日常使用、换机及跨端协同中仍有三项刚需缺口：

1. 出于隐私与安全边界，Android 版禁用了系统云备份（`android:allowBackup="false"`）；从 `.debug` 迁到正式签名包、换机、重装或与 Windows/Docker 端互迁配置与续火数据时，用户需要人工重新配置。
2. 官方火花状态仅能在「好友」页手动触发扫描更新，无法在后台按周期自动同步，也无法在官方火花进入消失倒计时或断火时提前发出告警。
3. 主界面仅有「今日」总览视图，缺少近 7/30 日续火成功率、连续成功天数、每日趋势与历史尝试时间线的回溯与导出入口。

---

## 2. 目标、非目标与反目标

### 2.1 目标
1. **跨端全双工 JSON 备份与迁移**：双向兼容后端 `schema_version = 1` 备份协议（`GET /api/backup` / `POST /api/backup/import`），Android 侧使用原生系统存储访问框架（SAF）文件选择器完成零权限导出/导入；同步小幅修复后端 `schemas.py` 与 `backup.py`，支持忽略 `androidExtensions`、按所属用户作用域安全更新子表、以及自然键无损合并，实现 **Android ↔ Android** 与 **Android ↔ Windows/Docker** 全双工无损互迁。
2. **后台定时自动扫描与官方火花告急提醒**：独立周期后台只读自动扫描（`auto_scan_enabled` 默认开启、`auto_scan_interval_hours` 默认 `24` 小时，可配 `1–168` 小时），严格避开发送窗口及前置 15 分钟缓冲，1:1 移植后端 `collect_spark_deteriorations` 判定规则，发现已启用好友火花进入倒计时、倒计时缩短或断火时，合并发送 1 条高优本机通知（`UrgentCategory.SPARK_DETERIORATING`）与 Bark `timeSensitive` 提醒。
3. **30 日历史执行记录与统计页**：新增第 5 个主导航 Tab「记录」（`今日 / 好友 / 模板 / 记录 / 设置`），提供 7/30 日窗口切换、与后端完全对齐的成功率与连续成功天数计算、纯 Compose 每日趋势柱状条、状态/日期筛选、单任务 `send_attempts` 尝试时间线详情，以及脱敏 CSV 导出。
4. **平滑数据库迁移**：提供 Room `MIGRATION_2_3`（DB `version 2 -> 3`），保障存量数据升级零丢失。

### 2.2 非目标（留待第 2B / 第 3 阶段）
- 本地图片模板与图片消息发送（`image`）——留待第 2B 阶段；
- 多账号切换 UI 与 `WebView ProfileStore` 多账号隔离——留待第 2B 阶段；
- 非 Bark 通知渠道（通用 Webhook / 飞书 / 钉钉 / 企业微信 / 邮件）——留待第 3 阶段；
- 备份「清空重建」覆盖式导入模式、云端自动同步、跨午夜发送窗口。

### 2.3 反目标（绝对禁止）
- 导出 JSON 或 CSV 时严禁包含 Cookie、LocalStorage、`self_uid` 或完整 Bark URL/Device Key；
- 导入备份时严禁把未登录设备的账号状态置为 `HEALTHY`（未登录设备必须维持 `PENDING_LOGIN`；已登录设备保留本地登录态、`self_uid`、`auth_fail_count` 与 `failure_streak`）；
- 导入备份时严禁覆盖或降级本地已存在的 `UNKNOWN_REQUIRES_REVIEW`、`SENDING`、`CONFIRMING` 任务，且绝不因跨端 `message_slot_id` 格式差异导致同日重复发送；
- 自动扫描严禁静默导入新会话、严禁自动重发消息、严禁改写本机维护的 `streak_days`；
- 同一好友已断火落库为 `"无火花"` 或倒计时标签未继续恶化时，后续周期扫描严禁重复刷屏告警；
- Logcat 与导出的 CSV 严禁输出好友昵称、抖音号、UID 或聊天正文。

---

## 3. 方案决策记录（Approaches）

1. **后台自动扫描触发方式**：对比「方案 1：独立周期自动扫描（`AlarmManager` 独立槽位 + `SafetyNetWorker` 兜底，严格避开发送窗）」与「方案 2：仅在每日续火结束后顺带扫一次」——选定 **方案 1**（与发送链路解耦、无待发任务日也能按期同步并提前预警、Doze 下 `AlarmManager` 唤醒前台服务比纯周期任务更可靠）。
2. **历史统计页面入口**：对比「选项 A：在 `AdaptiveNavShell` 新增第 5 个主导航 Tab『记录』」与「选项 B：保持 4 主 Tab，在『今日』页放入二级入口」——选定 **选项 A**（手机底栏与平板左侧栏均可直达，且与桌面控制台工作流一致）。
3. **跨端备份兼容边界**：对比「选项 1：同步小幅修复 Python 后端 `schemas.py` 与 `backup.py`，实现含 `androidExtensions` 与自然键合并的全双工互导」与「选项 2：只改 `android/` 导出纯净格式，暂不修后端既有子表更新与同名冲突问题」——选定 **选项 1**（一次性彻底打通双向无报错互迁）。

---

## 4. Room 数据库迁移（`version 2 -> 3`）

1. `accounts` 表新增 3 列：
   - `auto_scan_enabled INTEGER NOT NULL DEFAULT 1`（`Boolean`，默认开启）
   - `auto_scan_interval_hours INTEGER NOT NULL DEFAULT 24`（`Int`，允许范围 `1..168`，默认 `24`）
   - `last_auto_scan_attempt_at INTEGER`（可空 `Long` 毫秒时间戳，跨进程重启持久化最近自动扫描尝试时刻以维持冷却退避）
2. `scan_runs` 表新增 1 列：
   - `alert_summary TEXT`（可空 `String`，记录本轮发现火花告急的可读摘要，仅存本地隐私库，不写入 Logcat 与脱敏诊断）
3. `FlameDatabase` 将 `@Database(version = 3)` 升级至版本 `3`，并在 `create()` 与 `createInMemory()` 同时注册 `MIGRATION_1_2` 与 `MIGRATION_2_3`。

---

## 5. 模块 1：跨端全双工 JSON 备份与迁移

### 5.1 统一协议与 Android 导出格式（`core/backup/BackupCodec.kt`）
- 复用纯 Kotlin `ExecJson` 进行解析与序列化（避免 Android `org.json` 在纯 JVM 单元测试返回 stub `null`）。
- **顶层结构**（对齐 FastAPI `APIBase` camelCase 序列化格式）：
  - `app`: `"flame-contractor"`
  - `schemaVersion`: `1`
  - `exportedAt`: `Instant.ofEpochMilli(now).toString()`（ISO-8601 UTC `Z` 字符串）
  - `data`: `{ accounts, templates, poolItems, targets, schedules: [], jobs, attempts }`
  - `androidExtensions`（可选扩展段，Android 端还原全部调度参数、后端自动忽略）：
    - `account`: `{ jitterMaxMinutes, cooldownMinSec, cooldownMaxSec, autoScanEnabled, autoScanIntervalHours }`
    - `jobSummaryDetails`: `{ <jobId>: summaryDetail }`
    - `jobOriginalSlots`: `{ <jobId>: originalSlotId }`
    - `attemptMeta`: `{ <attemptId>: { inputMethodUsed, errorCode } }`
- **`data.*` 行级字段**（全部采用后端 SQLModel snake_case 键名、ISO-8601 UTC 时间戳与 `YYYY-MM-DD` 业务日）：
  - `data.accounts`：仅导出主账号，**`status` 固定导出为 `"pending_login"`**，**严禁导出 `self_uid`、`auth_fail_count`、`failure_streak`**。
  - `data.templates`：导出 `id, name, type, content, image_path(null), sticker_name, sticker_label, sticker_category, sticker_fallback_index, version(1), enabled, created_at, updated_at`。
  - `data.poolItems`：导出 `id, template_id, content, weekday_mask, enabled, last_used_at, created_at, updated_at`。
  - `data.targets`：全量导出（含软删除行以维持历史 `jobs` 外键完整），字段含 `id, account_id, platform_user_id, platform_handle, platform_conversation_id, conversation_type, nickname_snapshot, template_id, enabled, send_time_hhmm, spark_days, spark_kind, spark_label, spark_scanned_at, streak_days, last_sent_at, last_confirmed_at, deleted_at, created_at, updated_at`。
    - **【工程裁决 1】`conversation_type` 边界双向映射**：Android 本地与执行器继续保持 `"friend"` / `"group"` 不变（零执行器与扫描匹配回归风险）；导出至 `data.targets` 时将 `"friend"` 映射为后端标准值 `"user"`（`"group"` 保持 `"group"`，其他非 `"user"` / `"group"` 值按安全规则置 `null`）；从备份导入回 Android 时将 `"user"` 映射回 `"friend"`。
  - `data.jobs` 与 `data.attempts`：全量导出关联记录；`data.schedules` 固定为 `[]`。
    - **【工程裁决 2】`message_slot_id` 边界双向映射（防跨端同日重复发送）**：
      - **Android → 备份导出**：
        - `"daily"` 映射为 `if (!job.templateId.isNullOrBlank()) "${job.templateId}:v1" else "raw:daily"`；
        - `"manual:<rest>"` 保持或规范截断为 `<= 64` 字符（超长时转为 `"raw:m:${sha1_12(slot)}"`），并在 `androidExtensions.jobOriginalSlots[job.id]` 保留原始 Android 槽位名。
      - **备份导入 → Android**：
        - 若 `androidExtensions.jobOriginalSlots` 中存在该 `jobId` 的原始 Android 槽位（且符合 `"daily"` 或 `"manual:*"` 约定），优先还原原槽位；
        - 否则：匹配后端普通模板槽位（正则 `^.+:v\d+$` 且不含 `:force:`）规范映射为 `"daily"`；匹配 `raw:*`、`:force:` 或其他未知命名空间的槽位规范映射为 `"manual:import:${job.id.take(48)}"`；
        - **同日同好友多模板槽位碰撞处理**：若用户在桌面端同一天同一好友因切换模板产生了多条可映射为 `"daily"` 的记录，先按安全优先级 `UNKNOWN_REQUIRES_REVIEW > SUCCEEDED > FAILED_TERMINAL > FAILED_RETRYABLE > CANCELLED > SCHEDULED > LEASED > OPENING_CHAT > SENDING > CONFIRMING` 排序选出最高优先级一条占入 `"daily"` 槽位（锁定 `SendGuard` 防同日重发与待核对拦截），其余同日记录自动降入 `"manual:import:${job.id.take(48)}"` 保留完整历史审计与统计数据，绝不因唯一索引冲突报错。
- **SAF 导出默认文件名**：`flame-contractor-backup-YYYY-MM-DD.json`。

### 5.2 Android 导入预览与校验（`BackupCodec.inspect`）
- **读取防护**：最大允许读取文件体积 `20 MB`，超出或非合法 JSON 立即返回可读错误提示，防止 OOM。
- **格式与版本校验**：要求顶层 `app == "flame-contractor"` 且 `schemaVersion == 1`；兼容读取顶层与 `data` 下的 camelCase（`schemaVersion`, `exportedAt`, `poolItems`）与 snake_case（`schema_version`, `exported_at`, `pool_items`）键名。
- **时间戳兼容**：解析 `Instant` 时兼容带 `Z`、带时区偏移（如 `+08:00`）以及后端可能输出的无偏移 naive UTC ISO-8601 字符串（默认按 UTC 处理）。
- **多账号处理**：若 `data.accounts` 为空，拒绝导入并提示缺少账号；若 `data.accounts.size > 1`（例如来自多账号 PC/Docker 备份），在预览弹窗展示账号单选列表由用户择一导入。
- **预览界面要素**：显示源账号名、可用模板数（及将被跳过的 `image` 模板数）、好友数、历史任务数；提供勾选框「同时合并历史发送记录与尝试日志」（默认勾选）；并醒目提示「导入不会携带或覆盖抖音登录态与 Bark 密钥」。

### 5.3 Android 事务性无损合并规则（`BackupManager.importBackup`，单 Room `withTransaction`）
1. **账号合并**：更新本地 `DEFAULT_ACCOUNT_ID` 的 `display_name`、`timezone`（经 `ZoneId.of` 校验合法性，非法保留本地并记警告）、`send_window_start`、`send_window_end`（若来源存在跨午夜窗口导致 `DailySchedulePlanner.validateWindow` 不通过，保留本地有效窗口并写入 `warnings`）、`send_time_hhmm`、`enabled`，以及可选 `androidExtensions.account` 中的 `jitterMaxMinutes`、`cooldownMinSec`、`cooldownMaxSec`、`autoScanEnabled`、`autoScanIntervalHours`（逐项钳制到合法范围）。**绝不覆盖**本地 `status`、`self_uid`、`douyin_nickname`、`auth_fail_count`、`failure_streak`、`last_verified_at`。
2. **模板合并与类型冲突处理**：
   - 跳过所有 `type == "image"` 模板（计入 `skippedImageTemplates` 并记录 `skippedTemplateIds`）。
   - 其余合法模板（`text` / `pool` / `sticker`）按 `id` → 否则 `(DEFAULT_ACCOUNT_ID, name)` 查找本地现有模板：
     - **【工程裁决 3a】同名模板类型冲突裁决**：若按名称命中且 `local.type == imported.type`，原地更新内容/表情元数据并复用本地 `id`（`templateIdRemap[srcId] = local.id`）；若按名称命中但 `local.type != imported.type`（例如预置 `"每日续火"` 为 `TEXT`，桌面端备份同名 `"每日续火"` 为 `POOL`），**绝不覆盖改变现有模板类型**，而是自动生成不重名的新名称 `"<name> (导入)"`（若仍重名则递增 `" (导入 2)"`…）作为新模板插入，将 `templateIdRemap[srcId]` 指向新 ID 并在结果 `warnings` 中说明。
3. **文案池候选项**：仅处理归属已保留 `POOL` 模板的项，将 `template_id` 经 `templateIdRemap` 重映射；按 `id` → 否则 `(remappedTemplateId, content, weekdayMask)` 去重 upsert，`last_used_at` 取较新值。
4. **好友目标合并与冲突裁决**：
   - 仅筛选 `account_id == selectedSourceAccountId` 的目标；按 `id` → `platform_user_id` → `platform_conversation_id` → `nickname_snapshot` 优先级匹配本地目标（含软删除记录）。
   - **唯一性预检**：在写入目标前检查合并后的 `nickname_snapshot`、`platform_user_id`、`platform_conversation_id` 是否与本地**另一条**不相同记录冲突；若跨键碰撞（例如同昵称对应不同 `platform_user_id`），跳过该目标（不写入 `targetIdRemap`）并记入 `warnings`，杜绝 `SQLiteConstraintException` 中断事务或张冠李戴。
   - **【工程裁决 3b】命中已有好友时的字段裁决**：
     1. **软删除冲突**：活跃态优先于软删除——本地活跃 + 备份已软删除时，保持本地活跃（不把本地好友删掉）；本地已软删除 + 备份活跃时，自动复活并清空 `deleted_at`；仅当双方均为软删除态时维持软删除；
     2. **非统计字段**：`enabled`、`send_time_hhmm`、`nickname_snapshot`（无唯一冲突时）与 `template_id` 以备份为准更新；**例外保护**：当备份目标原绑定的模板是因 `type == "image"` 而被跳过的模板时，**保留本地已有好友原来的 `template_id`（不强制清空为 `null`）**，并在 `warnings` 中提示；仅对新建好友才因跳过 `image` 模板而置 `template_id = null`；
     3. **统计与官方火花快照**：`streak_days = max(local.streakDays, imported.streakDays)`；`last_sent_at` / `last_confirmed_at` 取较新者；仅当导入 `spark_scanned_at` 晚于本地时才刷新 `spark_days / spark_kind / spark_label / spark_scanned_at`。
5. **历史任务与尝试记录（`includeHistory == true` 时）**：
   - 筛选 `account_id == selectedSourceAccountId` 且 `target_id` 已进入 `targetIdRemap` 的记录；
   - 按【工程裁决 2】映射 `message_slot_id` 并重映射 `target_id`、`template_id`（若原模板被跳过则置 `null`）；
   - 按 `(DEFAULT_ACCOUNT_ID, remappedTargetId, businessDate, mappedSlotId)` 或 `id` 查找本地既有记录：
     - **安全保护**：若本地已存在对应任务且状态属于 `UNKNOWN_REQUIRES_REVIEW` / `SENDING` / `CONFIRMING`，**硬跳过保护**（`skippedProtectedJobs++`），绝不被外部导入覆写或降级；
     - **瞬时态规范化**：导入记录自身若为 `SENDING` 或 `CONFIRMING`，导入时强制收敛为 `UNKNOWN_REQUIRES_REVIEW`；若为 `LEASED` 或 `OPENING_CHAT`，强制收敛为 `FAILED_RETRYABLE`；
   - 对应 `attempts` 仅导入归属已入库任务的记录，按 `(remappedJobId, attemptNo)` 去重合并，瞬时 `RUNNING` 状态统一收敛为 `FAILED`；若存在 `androidExtensions` 元数据则一并恢复 `summaryDetail`、`inputMethodUsed` 与 `errorCode`。
6. 事务提交后立即调用 `scheduleCoordinator.rescheduleAll()` 刷新精确闹钟与兜底任务。

### 5.4 Python 后端同步修复（`backend/app/schemas.py` & `backend/app/backup.py`）
1. **`schemas.py` 扩展字段放行**：在 `BackupRead` 中显式声明 `android_extensions: dict[str, Any] | None = Field(default=None, exclude=True)`（继承 `APIBase(extra="forbid")` 下仅 `BackupRead` 顶层接受并自动忽略 `androidExtensions`，其余所有 API 请求仍严格维持 `extra="forbid"`）。
2. **`backup.py` 子表归属修复、自然键合并与多用户固定 ID 隔离**：
   - **修复既有子表更新 Bug**：改用 `_user_scoped(session, user_id)` 提供的归属集合而非 `getattr(existing, "user_id", None) != user_id`（解决 `pool_items / targets / schedules / jobs / attempts` 因无 `user_id` 列而更新被静默跳过的问题）。
   - **固定 ID 跨用户隔离重映射**：在一次导入内维护 `account_id_map`、`template_id_map`、`target_id_map`、`job_id_map`：
     - 对 `Account` / `MessageTemplate`：若传入 `id` 在库中存在且归属**当前用户** → 原地安全更新；若归属**其他用户**（典型场景：不同用户先后导入 Android 固定 ID `"default"`、`"default-sticker-bixin"` 等），**绝不跨用户修改或借用对方记录**，而是按自然键 `(user_id, display_name)` / `(user_id, name)` 查找当前用户的对应记录（无则分配新 UUID 创建），并将映射写入 `*_id_map` 透传给后续子表；
     - 对 `Account` 按 `(user_id, display_name)` 匹配已存在但 UUID 不同的本地账号时复用其 ID；更新 `Account` 时，若库中已非 `pending_login` 而导入为 `pending_login`，保留本地已激活状态；
     - 对 `MessageTemplate` 按 `(user_id, name)` 匹配到异型模板（如本地 `pool` 对导入 `text`）时，同样以 `"<name> (导入)"` 另存新 UUID 避免违反唯一索引或破坏原模板；
     - 对 `ContractTarget`：按 `account_id` + `(id → platform_user_id → platform_conversation_id → nickname_snapshot)` 匹配（要求归属当前用户的账号），防御性规范化 `conversation_type`（`"friend" -> "user"`），遵循活跃态优先、`streak_days = max(...)` 与唯一键冲突跳过保护；
     - 对 `SendSchedule`（按 `(account_id, business_date)`）与 `SendJob`（按 `(account_id, target_id, business_date, message_slot_id)`）：已有记录属 `unknown_requires_review / sending / confirming` 时跳过保护；导入自身属 `sending / confirming` 收敛为 `unknown_requires_review`，`leased / opening_chat` 收敛为 `failed_retryable`；
     - 对 `SendAttempt`：按 `id`（确认归属本批/本用户 `job_id`）或 `(job_id, attempt_no)` 去重合并。
3. **跨端测试补充**：在 `backend/tests/test_backup.py` 增补单测覆盖：带 `androidExtensions` 的 Android 导出 JSON 在 `POST /api/backup/import` 接受并忽略扩展段；同库二次导入时子表正常更新计数；多用户各自导入 Android 固定 `"default"` ID 彻底隔离不串主；Android 风格数据（含 `"friend"` 转换、预置模板名撞桌面端同名）导入非空桌面库零 `IntegrityError`。

### 5.5 设置页入口（`SettingsScreen`）
在「诊断与版本」卡片上方新增「数据备份与跨端迁移」卡片，提供：
- 「导出 JSON 备份」按钮（触发 `ActivityResultContracts.CreateDocument("application/json")`）；
- 「导入 JSON 备份」按钮（触发 `ActivityResultContracts.OpenDocument()`）；
- 脱敏与登录态说明（明确列出不含 Cookie / 密钥）；
- 导入预览弹窗（多账号单选、是否合并历史发送记录复选框）与导入结果摘要。

---

## 6. 模块 2：后台定时自动扫描与官方火花告急提醒

### 6.1 火花恶化纯函数检测器（`domain/SparkDeteriorationDetector.kt`）
- 1:1 移植 `backend/app/scan_reconciliation.py` 语义：
  - `DISAPPEAR_DAYS_REGEX = Regex("""(\d+)\s*天后(?:火花(?:将)?)?消失""")`
  - `REKINDLE_PROGRESS_REGEX = Regex("""重燃中\s*\(?(\d+)\s*/\s*(\d+)\)?""")`
- 匹配策略：遍历本次扫描返回的 `candidates`，仅处理 `matchStatus != MatchStatus.AMBIGUOUS` 且能精确对应到本地唯一活跃目标（按 `platformUserId` → `platformConversationId` → `nicknameSnapshot`，且无身份字段冲突）的对象。**未在本次会话列表中观察到的既有目标，不更新快照也不告警**。
- 观察快照规范化：
  - 若 `ScanReconciliationBuilder.hasOfficialSpark(candidate)` 为 `true`，写入观察到的 `(sparkDays, sparkKind, sparkLabel)`；
  - 若为 `false`（确认在本轮会话列表中观察到但无任何火花标记），规范化写入 `(sparkDays = null, sparkKind = null, sparkLabel = "无火花")`，确保断火落库后下周期因 `prevLabel == "无火花"` 不再重复推送。
- 恶化跃迁判定（**仅针对 `target.enabled == true`**，已暂停好友更新快照但不推送告警）：
  - **断火**：本轮观察为无火花，且此前有火花（`prevDays > 0` 或 `isDangerLabel(prevLabel)` 或 `prevKind != null && prevLabel != "无火花"`）→ 判定恶化（`"{prevDesc} → 无火花"`）；
  - **倒计时 / 重燃出现或回退**：`currDays == null && isDangerLabel(currLabel)` 时，若此前为正常数字天数（`prevDays > 0`）、或此前无标签仅靠图标/类型标记有火花（`prevKind != null && prevLabel == null`）、或标签发生回退（`天后消失` 天数变小、`重燃中` 进度分子变小、或从 `天后消失` 跌入 `重燃中`）→ 判定恶化。
- 回写方式：仅调用 `targetDao.updateOfficialSparkSnapshot(...)`，**严禁**改写 `streak_days` 且**严禁**创建或导入新目标。

### 6.2 自动扫描调度器（`scheduler/AutoScanPlanner.kt` + `ScheduleCoordinator` + `SafetyNetWorker`）
- **资格门禁**：仅当 `account.enabled && account.autoScanEnabled && account.status == AccountStatus.HEALTHY && account.authFailCount == 0 && enabledTargetCount > 0` 时参与调度。
- **周期基准与冷却约束**：
  - 最近完成基准 `lastCompletedAt` = 最近一条 `ScanRunEntity(status == COMPLETED)`（不区分 `manual` / `automatic`）的 `finishedAt ?: startedAt`。
  - **【工程裁决 4】首次无扫描历史与 `AUTH_EXPIRED` 重登后补扫**：完全对齐后端 `scheduler.py:1421-1427`——
    1. 若 `lastCompletedAt == null`（新安装直接导入备份、或从 `v0.4.0` 升级且从未扫描），视为基础到期时刻等于当前 `now`（仍须遵守下方发送窗口避让与 60 分钟冷却，避免与发信撞车或失败狂重试）；
    2. 若最近一次扫描记录为失败/未授权（`latestAnyScan` 为 `FAILED` 且发生在 `account.lastVerifiedAt` 之前），而账号已在 `lastVerifiedAt` 完成重登恢复为 `HEALTHY`，同样触发一次立即补扫（基础到期时刻为 `now`，受发送窗口避让与 60 分钟冷却约束）。
  - 正常周期：`baseDueAt = lastCompletedAt + autoScanIntervalHours.coerceIn(1, 168) hours`。
  - 失败/互斥冷却：`cooldownAt = lastAutoScanAttemptAt?.plus(60 minutes)`；最终 `rawDueAt = max(baseDueAt, cooldownAt)`。
- **避开发送窗口与固定时刻**：
  - 收集今日阻塞区间：`[account.sendWindowStart - 15m, account.sendWindowEnd]`，以及账号级与所有启用目标级固定时刻 `[fixedTime - 15m, min(fixedTime + 60m, 23:59)]`；合并重叠区间。
  - 若计算出的扫描时刻落入任一阻塞区间，顺延至该区间结束点；若顺延后晚于 `23:55`，顺延至次日 `00:05`（再校验次日区间）。
- **发送严格优先**：同一评估周期内，若 `DailySchedulePlanner` 判定存在 `dueTargets` 或 `shouldTriggerAuthRecheck == true`，优先派发发送/鉴权复判，自动扫描不在本轮并发抢锁（由调度器预约下一次独立唤醒或等待下轮触发）。
- **独立闹钟与契约**：
  - `ScheduleCoordinator` 新增 `REQUEST_CODE_AUTO_SCAN = 4104`、`KIND_AUTO_SCAN = "auto_scan"`；`rescheduleAll()` 同时协同维护「主发送/复判/重试闹钟」与「自动扫描闹钟」（两者独立注册互不覆盖；若账号停用或非 `HEALTHY` 则一并取消）。
  - `ExecutionDispatchContract` 新增 `ACTION_RUN_AUTO_SCAN`；`AlarmWakeReceiver` 按 `intent.getStringExtra(EXTRA_ALARM_KIND)` / 统一 `evaluateAndDispatchIfNeeded()` 调度，保证闹钟与 `SafetyNetWorker` 30 分钟兜底均一致处理发送与自动扫描。

### 6.3 离屏执行与双通道合并通知（`ExecutionForegroundService.runAutoScanCommand`）
- 获取 `ExecutionMutex.tryWithLock("auto_scan", SCHEDULED_LOCK_WAIT_MS)`；锁被占用时不弹窗打扰，仅更新 `account.lastAutoScanAttemptAt = nowMs` 触发 60 分钟冷却后静默退出。
- 获取锁后立即写入 `lastAutoScanAttemptAt = startedAt` 并创建 `ScanRunEntity(source = "automatic", status = RUNNING)`。
- 复用 `executor.scanAllChats(...)`：
  - **`Success`**：运行 `SparkDeteriorationDetector` 批量刷新已有目标快照；更新 `ScanRunEntity(status = COMPLETED, alertSummary = ...)` 与账号 `lastVerifiedAt`；若 `alerts.isNotEmpty()` 合并发出 1 条双通道高优提醒：
    - 本机通知：`LocalNotifier.notifyUrgentAlert(UrgentCategory.SPARK_DETERIORATING, "好友火花告急", body)`（`notificationId = 3106`、`VISIBILITY_PRIVATE`，点击通知直达 `MainTab.TARGETS`）；
    - Bark 推送：`NotifyLevel.TIME_SENSITIVE`，正文展示最多 3 位好友及跃迁摘要（例：`好友A（12天 → 3天后消失）`），并附注 `"可能对方尚未回复消息，请留意提醒（仅只读预警，不会自动重发）。"`。
  - **`AuthOrGateFailure`**：交由 `AccountHealthReducer.onAuthProbeResult` 处理，严格遵守 `AUTH_CHECK_TOLERANCE = 2`（单次未通过不改状态、不告警并预约 10 分钟复判闹钟；连续 2 次确认失效转 `AUTH_EXPIRED` 并高优告警；遇到风控验证立即转 `VERIFICATION_REQUIRED` 并高优告警）。
  - **`Error`**：落库 `ScanRunEntity(status = FAILED)`，不弹干扰通知，等 60 分钟冷却后按调度重试。

### 6.4 手动同步对齐与设置/诊断集成
- **手动同步行为对齐**：修正 `TargetsViewModel.syncExistingTargetsFromSnapshot`——对本次手动扫描中真实匹配且观察到无火花的好友，同样调用 `updateOfficialSparkSnapshot(..., sparkDays = null, sparkKind = null, sparkLabel = "无火花")`，保持手动与自动扫描的状态基线一致，避免手动刷新后旧数据残留导致后续自动扫描误报或漏报。
- **设置页配置卡片**：在 `SettingsScreen` 调度卡片下方新增「官方火花自动同步」配置区域：开启开关、同步周期输入框（`1–168` 小时 + `12h / 24h / 48h` 快捷芯片）、最近自动同步状态/耗时/摘要与下次预计自动扫描时间。
- **脱敏诊断扩展**：`SanitizedDiagnosticsBuilder` 补充 `autoScanEnabled`、`autoScanIntervalHours`、最近自动扫描状态与脱敏时间差（零 PII）。

---

## 7. 模块 3：30 日历史执行记录与统计页（`ui/history/`）

### 7.1 导航与通知跳转（`AdaptiveNavShell.kt` & `MainActivity.kt`）
- `MainTab` 扩展为 5 项：`TODAY("今日")`, `TARGETS("好友")`, `TEMPLATES("模板")`, `HISTORY("记录")`, `SETTINGS("设置")`（手机使用 `NavigationBar`，平板 `>= 600dp` 使用 `NavigationRail`）。
- `MainActivity` 接入 `HistoryRoute`；点击 `UrgentCategory.SPARK_DETERIORATING` 通知时切至 `MainTab.TARGETS`（其他紧急通知保持跳往 `MainTab.TODAY` 并弹窗）。

### 7.2 统计计算器与数据库查询（`domain/HistoryStatsCalculator.kt` & `Daos.kt`）
- **`HistoryStatsCalculator`**（纯 JVM，1:1 对齐 `backend/app/api/stats.py`）：
  - 支持窗口 `7` 天与 `30` 天，生成 `[today - (windowDays - 1), today]` 闭区间每日桶（补齐无任务空白天用于趋势条对齐渲染）；
  - 状态口径：`succeeded`（`SUCCEEDED`）、`failed`（`FAILED_RETRYABLE` + `FAILED_TERMINAL`）、`unknown`（`UNKNOWN_REQUIRES_REVIEW`）、`cancelled`（`CANCELLED`），忽略仍在途/未运行态或计入总览说明；
  - `successRate = if (total > 0) succeeded.toDouble() / total else null`；
  - 成功日判定：`succeeded >= 1 && failed == 0 && unknown == 0`；
  - 连续成功天数：`currentSuccessStreak`（若窗口内最近任务日断档或最后一天失败/待核对则归 0）与 `bestSuccessStreak`。
- **DAO 新增方法**：
  - `SendJobDao.observeJobsSince(accountId, sinceDate)`（按 `business_date DESC, created_at DESC`）
  - `SendJobDao.listAllJobs(accountId)`、`SendJobDao.listAllAttemptsForAccount(accountId)`
  - `TargetDao.listAllTargetsIncludingDeleted(accountId)`（供备份导出及历史记录展示已软删除好友的昵称快照）
  - `ScanRunDao.getLatestCompletedScanRun(accountId)`、`ScanRunDao.getLatestScanRun(accountId)`、`ScanRunDao.observeLatestAutoScanRun(accountId)`

### 7.3 历史与统计界面（`HistoryScreen.kt` & `HistoryViewModel.kt`）
- **顶栏**：标题「历史与统计」、`7天 / 30天` 分段切换按钮、「导出 CSV」按钮（调用 SAF `CreateDocument("text/csv")`，默认文件名 `flame-jobs-YYYY-MM-DD.csv`，列头 `business_date,account_id,target_id,status,failure_class,attempt_count,scheduled_at,confirmed_at,message_slot_id`，与后端 `/api/stats/export.csv` 完全对齐，严格不含好友昵称或文案）。
- **KPI 概览卡片**：手机 2×2、平板 4 列展示：成功率、当前/最长连续成功天数、成功/总任务数、待核对与失败数（存在待核对时提供一键前往「今日」处理的快捷入口）。
- **纯 Compose 每日趋势条**：展示窗口内每日执行结果堆叠柱（绿 = 成功、琥珀 = 待核对、红 = 失败、淡灰底线 = 无任务）；点击某一日柱子可过滤/取消过滤该日明细列表。
- **筛选与明细列表**：状态筛选芯片（`全部 / 成功 / 失败 / 待核对 / 已取消`）；每条任务展示业务日、好友名（软删除目标附加「已删除」徽标）、槽位标签（`每日` / `测试`）、状态徽章、尝试次数、确认时间、脱敏详情摘要；点击任一任务弹出详情弹窗加载完整 `send_attempts` 时间线（`attempt_no`、`stage`、`status`、`input_method_used`、`failure_class`、`error_code`、起止耗时），如状态为 `UNKNOWN_REQUIRES_REVIEW` 额外提供「前往『今日』人工核对」按钮。

---

## 8. 留给实施者的边界（What's Left to the Builder）

- Compose 卡片圆角、间距与色阶沿用现有 [Theme.kt](../../../android/app/src/main/java/io/github/oinzc/flamecontractor/ui/theme/Theme.kt)；
- 备份多账号单选弹窗、导入预览弹窗、尝试时间线详情弹窗与趋势条在手机竖屏/平板横屏下的具体排布微调（确保底部操作按钮不被软键盘或横屏视口裁切）；
- 单元测试组织方式：Android 沿用 `android/app/src/test` 零 Robolectric 假仓库风格，并覆盖 Room `MIGRATION_2_3` 结构迁移验证与纯 JVM 逻辑单测；后端在 `backend/tests/test_backup.py` 补充跨端往返与回归单测。
