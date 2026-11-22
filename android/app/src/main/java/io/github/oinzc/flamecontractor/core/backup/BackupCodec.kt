package io.github.oinzc.flamecontractor.core.backup

import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.execution.ExecJson
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * 跨端全双工 JSON 备份编解码器（纯 Kotlin + ExecJson，零 org.json / android.* 依赖）。
 */
object BackupCodec {

    const val MAX_BACKUP_BYTES = 20 * 1024 * 1024
    const val BACKUP_APP_ID = "flame-contractor"
    const val BACKUP_SCHEMA_VERSION = 1

    private const val MAX_SLOT_ID_LENGTH = 64
    private const val IMPORT_SLOT_ID_PREFIX_MAX_LEN = 48
    private const val RAW_DAILY_SLOT_ID = "raw:daily"
    private val TEMPLATE_VERSION_SLOT_REGEX = Regex("""^.+:v\d+$""")

    fun defaultBackupFileName(date: LocalDate = LocalDate.now()): String =
        "flame-contractor-backup-$date.json"

    fun encode(
        snapshot: BackupExportSnapshot,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
    ): String {
        val account = snapshot.account
        // 严禁导出登录态、self_uid、auth_fail_count、failure_streak、douyin_nickname 等鉴权/隐私字段
        val accountMap = linkedMapOf<String, Any?>(
            "id" to account.id,
            "display_name" to account.displayName,
            "status" to AccountStatus.PENDING_LOGIN.wireValue,
            "enabled" to account.enabled,
            "timezone" to account.timezone,
            "send_window_start" to account.sendWindowStart,
            "send_window_end" to account.sendWindowEnd,
            "send_time_hhmm" to account.sendTimeHhmm,
            "last_success_at" to formatIsoOrNull(account.lastSuccessAt),
            "created_at" to formatIso(account.createdAt),
            "updated_at" to formatIso(account.updatedAt),
        )

        val templatesList = snapshot.templates.map { tpl ->
            val isImage = tpl.type == TemplateType.IMAGE
            // 刻意省略 "image_path" 键（不输出本地路径，也不写入 null），防止回导桌面端时覆写原路径
            linkedMapOf<String, Any?>(
                "id" to tpl.id,
                "name" to tpl.name,
                "type" to tpl.type.wireValue,
                "content" to if (isImage) "" else tpl.content,
                "sticker_name" to if (isImage) null else tpl.stickerName,
                "sticker_label" to if (isImage) null else tpl.stickerLabel,
                "sticker_category" to if (isImage) null else tpl.stickerCategory,
                "sticker_fallback_index" to if (isImage) null else tpl.stickerFallbackIndex,
                "version" to 1,
                "enabled" to tpl.enabled,
                "created_at" to formatIso(tpl.createdAt),
                "updated_at" to formatIso(tpl.updatedAt),
            )
        }

        val poolItemsList = snapshot.poolItems.map { item ->
            linkedMapOf<String, Any?>(
                "id" to item.id,
                "template_id" to item.templateId,
                "content" to item.content,
                "weekday_mask" to item.weekdayMask,
                "enabled" to item.enabled,
                "last_used_at" to formatIsoOrNull(item.lastUsedAt),
                "created_at" to formatIso(item.createdAt),
                "updated_at" to formatIso(item.updatedAt),
            )
        }

        val targetsList = snapshot.targets.map { target ->
            linkedMapOf<String, Any?>(
                "id" to target.id,
                "account_id" to target.accountId,
                "platform_user_id" to target.platformUserId,
                "platform_handle" to target.platformHandle,
                "platform_conversation_id" to target.platformConversationId,
                "conversation_type" to mapConversationTypeToBackup(target.conversationType),
                "nickname_snapshot" to target.nicknameSnapshot,
                "template_id" to target.templateId,
                "enabled" to target.enabled,
                "send_time_hhmm" to target.sendTimeHhmm,
                "spark_days" to target.sparkDays,
                "spark_kind" to target.sparkKind,
                "spark_label" to target.sparkLabel,
                "spark_scanned_at" to formatIsoOrNull(target.sparkScannedAt),
                "streak_days" to target.streakDays,
                "last_sent_at" to formatIsoOrNull(target.lastSentAt),
                "last_confirmed_at" to formatIsoOrNull(target.lastConfirmedAt),
                "deleted_at" to formatIsoOrNull(target.deletedAt),
                "created_at" to formatIso(target.createdAt),
                "updated_at" to formatIso(target.updatedAt),
            )
        }

        val jobsList = snapshot.jobs.map { job ->
            linkedMapOf<String, Any?>(
                "id" to job.id,
                "account_id" to job.accountId,
                "target_id" to job.targetId,
                "template_id" to job.templateId,
                "business_date" to job.businessDate,
                "message_slot_id" to mapSlotIdToBackup(job.messageSlotId, job.templateId),
                "status" to job.status.wireValue,
                "scheduled_at" to formatIsoOrNull(job.scheduledAt),
                "attempt_count" to job.attemptCount,
                "failure_class" to job.failureClass?.wireValue,
                "confirmed_at" to formatIsoOrNull(job.confirmedAt),
                "created_at" to formatIso(job.createdAt),
                "updated_at" to formatIso(job.updatedAt),
            )
        }

        val attemptsList = snapshot.attempts.map { attempt ->
            linkedMapOf<String, Any?>(
                "id" to attempt.id,
                "job_id" to attempt.jobId,
                "attempt_no" to attempt.attemptNo,
                "stage" to attempt.stage,
                "status" to attempt.status.wireValue,
                "failure_class" to attempt.failureClass?.wireValue,
                "started_at" to formatIso(attempt.startedAt),
                "finished_at" to formatIsoOrNull(attempt.finishedAt),
            )
        }

        val jobSummaryDetails = linkedMapOf<String, Any?>()
        val jobOriginalSlots = linkedMapOf<String, Any?>()
        for (job in snapshot.jobs) {
            if (!job.summaryDetail.isNullOrBlank()) {
                jobSummaryDetails[job.id] = job.summaryDetail
            }
            jobOriginalSlots[job.id] = job.messageSlotId
        }

        val attemptMeta = linkedMapOf<String, Any?>()
        for (attempt in snapshot.attempts) {
            val meta = linkedMapOf<String, Any?>()
            if (!attempt.inputMethodUsed.isNullOrBlank()) {
                meta["inputMethodUsed"] = attempt.inputMethodUsed
            }
            if (!attempt.errorCode.isNullOrBlank()) {
                meta["errorCode"] = attempt.errorCode
            }
            if (meta.isNotEmpty()) {
                attemptMeta[attempt.id] = meta
            }
        }

        val androidExtensions = linkedMapOf<String, Any?>(
            "account" to linkedMapOf<String, Any?>(
                "jitterMaxMinutes" to account.jitterMaxMinutes,
                "cooldownMinSec" to account.cooldownMinSec,
                "cooldownMaxSec" to account.cooldownMaxSec,
                "autoScanEnabled" to account.autoScanEnabled,
                "autoScanIntervalHours" to account.autoScanIntervalHours,
            ),
            "jobSummaryDetails" to jobSummaryDetails,
            "jobOriginalSlots" to jobOriginalSlots,
            "attemptMeta" to attemptMeta,
        )

        val root = linkedMapOf<String, Any?>(
            "app" to BACKUP_APP_ID,
            "schemaVersion" to BACKUP_SCHEMA_VERSION,
            "exportedAt" to formatIso(exportedAtEpochMs),
            "data" to linkedMapOf<String, Any?>(
                "accounts" to listOf(accountMap),
                "templates" to templatesList,
                "poolItems" to poolItemsList,
                "targets" to targetsList,
                "schedules" to emptyList<Any?>(),
                "jobs" to jobsList,
                "attempts" to attemptsList,
            ),
            "androidExtensions" to androidExtensions,
        )
        return ExecJson.encode(root)
    }

    fun inspect(rawJson: String): BackupInspection {
        val byteCount = rawJson.toByteArray(Charsets.UTF_8).size
        if (byteCount > MAX_BACKUP_BYTES) {
            val actualMb = String.format(Locale.US, "%.2f", byteCount / (1024.0 * 1024.0))
            throw IllegalArgumentException(
                "备份文件超过 20 MB 上限（实际 $actualMb MB，$byteCount 字节），已拒绝解析",
            )
        }
        if (rawJson.isBlank()) {
            throw IllegalArgumentException("备份文件内容为空")
        }

        val root = try {
            ExecJson.parseObject(rawJson)
        } catch (e: Exception) {
            throw IllegalArgumentException("备份文件不是合法的 JSON 格式：${e.message ?: "语法错误"}", e)
        }

        val app = root.stringAlias("app")
            ?: throw IllegalArgumentException("备份文件缺少顶层 app 标识")
        if (app != BACKUP_APP_ID) {
            throw IllegalArgumentException("不支持的备份应用标识：$app")
        }

        val rawSchemaVersion = root.valueAlias("schemaVersion", "schema_version")
            ?: throw IllegalArgumentException("备份文件缺少 schemaVersion 版本号")
        val schemaVersion = parseExactInt(rawSchemaVersion)
            ?: throw IllegalArgumentException("备份版本号格式无效：$rawSchemaVersion")
        if (schemaVersion != BACKUP_SCHEMA_VERSION) {
            throw IllegalArgumentException("不支持的备份版本：$schemaVersion（当前仅支持版本 $BACKUP_SCHEMA_VERSION）")
        }

        val rawExportedAt = root.valueAlias("exportedAt", "exported_at")
        val exportedAtEpochMs = when (rawExportedAt) {
            null -> 0L
            is String -> parseTimestampOrNull(rawExportedAt)
                ?: throw IllegalArgumentException("备份导出时间 exportedAt 格式无效：$rawExportedAt")
            else -> throw IllegalArgumentException("备份导出时间 exportedAt 必须为 ISO-8601 字符串")
        }

        val dataObj = root.objectAlias("data")
            ?: throw IllegalArgumentException("备份文件缺少 data 数据块")

        val extObj = root.objectAlias("androidExtensions", "android_extensions")
        val androidAccountExt = extObj?.objectAlias("account")?.let { parseAccountExt(it) }
        val jobSummaryDetails = extObj?.objectAlias("jobSummaryDetails", "job_summary_details")
            ?.entries
            ?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
            ?.toMap()
            ?: emptyMap()
        val jobOriginalSlots = extObj?.objectAlias("jobOriginalSlots", "job_original_slots")
            ?.entries
            ?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
            ?.toMap()
            ?: emptyMap()
        val attemptMeta = parseAttemptMetaMap(extObj?.objectAlias("attemptMeta", "attempt_meta"))

        val accounts = parseAccounts(dataObj.objectListAlias("accounts", required = true))
        if (accounts.isEmpty()) {
            throw IllegalArgumentException("备份中不包含任何账号数据")
        }

        val templates = parseTemplates(
            rows = dataObj.objectListAlias("templates"),
            fallbackTimestampMs = exportedAtEpochMs,
        )
        val poolItems = parsePoolItems(
            rows = dataObj.objectListAlias("poolItems", "pool_items"),
            fallbackTimestampMs = exportedAtEpochMs,
        )
        val targets = parseTargets(
            rows = dataObj.objectListAlias("targets"),
            fallbackTimestampMs = exportedAtEpochMs,
        )
        val jobs = parseJobs(
            rows = dataObj.objectListAlias("jobs"),
            jobSummaryDetails = jobSummaryDetails,
            fallbackTimestampMs = exportedAtEpochMs,
        )
        val attempts = parseAttempts(
            rows = dataObj.objectListAlias("attempts"),
            attemptMeta = attemptMeta,
            fallbackTimestampMs = exportedAtEpochMs,
        )

        val parsed = ParsedBackup(
            app = app,
            schemaVersion = schemaVersion,
            exportedAtEpochMs = exportedAtEpochMs,
            accounts = accounts,
            templates = templates,
            poolItems = poolItems,
            targets = targets,
            jobs = jobs,
            attempts = attempts,
            androidAccountExt = androidAccountExt,
            jobSummaryDetails = jobSummaryDetails,
            jobOriginalSlots = jobOriginalSlots,
            attemptMeta = attemptMeta,
        )

        val missingImageTemplateCount = templates.count { it.isImageTemplate }
        return BackupInspection(
            parsed = parsed,
            accounts = accounts,
            templateCount = templates.size,
            missingImageTemplateCount = missingImageTemplateCount,
            targetCount = targets.size,
            jobCount = jobs.size,
            attemptCount = attempts.size,
        )
    }

    fun parseTimestampOrNull(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(text).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun mapConversationTypeToBackup(localType: String?): String? =
        when (localType?.trim()?.lowercase(Locale.ROOT)) {
            "friend", "user" -> "user"
            "group" -> "group"
            else -> null
        }

    fun mapConversationTypeFromBackup(backupType: String?): String? =
        when (backupType?.trim()?.lowercase(Locale.ROOT)) {
            "user", "friend" -> "friend"
            "group" -> "group"
            else -> null
        }

    fun mapSlotIdToBackup(localSlot: String, templateId: String?): String {
        val trimmedSlot = localSlot.trim()
        if (trimmedSlot == SendGuard.DAILY_SLOT_ID) {
            val cleanedTemplateId = templateId?.trim()?.takeIf { it.isNotEmpty() }
            if (cleanedTemplateId == null) return RAW_DAILY_SLOT_ID
            val candidate = "$cleanedTemplateId:v1"
            return if (candidate.length <= MAX_SLOT_ID_LENGTH) {
                candidate
            } else {
                "${sha1Hex12(cleanedTemplateId)}:v1"
            }
        }
        if (trimmedSlot.isNotEmpty() && trimmedSlot.length <= MAX_SLOT_ID_LENGTH) {
            return trimmedSlot
        }
        return "raw:m:${sha1Hex12(trimmedSlot)}"
    }

    fun resolveSlotIdFromBackup(
        backupSlot: String,
        jobId: String,
        originalSlotFromExt: String?,
    ): String {
        val ext = originalSlotFromExt?.trim()
        if (ext == SendGuard.DAILY_SLOT_ID ||
            (ext != null && ext.startsWith(SendGuard.MANUAL_SLOT_PREFIX) && ext.length > SendGuard.MANUAL_SLOT_PREFIX.length)
        ) {
            return ext
        }
        val trimmed = backupSlot.trim()
        if (trimmed == SendGuard.DAILY_SLOT_ID ||
            trimmed == RAW_DAILY_SLOT_ID ||
            (TEMPLATE_VERSION_SLOT_REGEX.matches(trimmed) && !trimmed.contains(":force:"))
        ) {
            return SendGuard.DAILY_SLOT_ID
        }
        return "manual:import:${jobId.take(IMPORT_SLOT_ID_PREFIX_MAX_LEN)}"
    }

    private fun parseAccountExt(map: Map<String, Any?>): BackupImportAccountExt =
        BackupImportAccountExt(
            jitterMaxMinutes = map.intAlias("jitterMaxMinutes", "jitter_max_minutes"),
            cooldownMinSec = map.intAlias("cooldownMinSec", "cooldown_min_sec"),
            cooldownMaxSec = map.intAlias("cooldownMaxSec", "cooldown_max_sec"),
            autoScanEnabled = map.boolOrNullAlias("autoScanEnabled", "auto_scan_enabled"),
            autoScanIntervalHours = map.intAlias("autoScanIntervalHours", "auto_scan_interval_hours"),
        )

    private fun parseAttemptMetaMap(
        rawMap: Map<String, Any?>?,
    ): Map<String, BackupImportAttemptMeta> {
        if (rawMap == null) return emptyMap()
        val result = linkedMapOf<String, BackupImportAttemptMeta>()
        for ((attemptId, rawEntry) in rawMap) {
            @Suppress("UNCHECKED_CAST")
            val entry = rawEntry as? Map<String, Any?> ?: continue
            result[attemptId] = BackupImportAttemptMeta(
                inputMethodUsed = entry.stringAlias("inputMethodUsed", "input_method_used"),
                errorCode = entry.stringAlias("errorCode", "error_code"),
            )
        }
        return result
    }

    private fun parseAccounts(rows: List<Map<String, Any?>>): List<BackupAccountCandidate> =
        rows.mapIndexed { index, row ->
            val id = row.stringAlias("id")
                ?: throw IllegalArgumentException("第 ${index + 1} 条账号缺少 id")
            val displayName = row.stringAlias("display_name", "displayName")
                ?: throw IllegalArgumentException("账号 $id 缺少 display_name")
            BackupAccountCandidate(
                sourceId = id,
                displayName = displayName,
                timezone = row.stringAlias("timezone") ?: "Asia/Shanghai",
                sendWindowStart = row.stringAlias("send_window_start", "sendWindowStart") ?: "20:00",
                sendWindowEnd = row.stringAlias("send_window_end", "sendWindowEnd") ?: "22:00",
                sendTimeHhmm = row.stringAlias("send_time_hhmm", "sendTimeHhmm"),
                enabled = row.boolAlias("enabled", default = true),
                rawMap = row,
            )
        }

    private fun parseTemplates(
        rows: List<Map<String, Any?>>,
        fallbackTimestampMs: Long,
    ): List<BackupParsedTemplate> =
        rows.mapIndexed { index, row ->
            val id = row.stringAlias("id")
                ?: throw IllegalArgumentException("第 ${index + 1} 条模板缺少 id")
            val name = row.stringAlias("name")
                ?: throw IllegalArgumentException("模板 $id 缺少 name")
            val rawType = (row.stringAlias("type") ?: TemplateType.TEXT.wireValue)
                .lowercase(Locale.ROOT)
            val parsedType = try {
                TemplateType.fromWireValue(rawType)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("模板「$name」类型不受支持：$rawType", e)
            }
            val isImage = parsedType == TemplateType.IMAGE
            val createdAt = row.timestampAlias(fallbackTimestampMs, "created_at", "createdAt")
            val updatedAt = row.timestampAlias(createdAt, "updated_at", "updatedAt")
            BackupParsedTemplate(
                sourceId = id,
                name = name,
                rawType = rawType,
                type = parsedType,
                isImageTemplate = isImage,
                content = if (isImage) "" else (row.rawStringAlias("content") ?: ""),
                stickerName = if (isImage) null else row.stringAlias("sticker_name", "stickerName"),
                stickerLabel = if (isImage) null else row.stringAlias("sticker_label", "stickerLabel"),
                stickerCategory = if (isImage) null else row.stringAlias("sticker_category", "stickerCategory"),
                stickerFallbackIndex = if (isImage) null else row.intAlias("sticker_fallback_index", "stickerFallbackIndex"),
                enabled = row.boolAlias("enabled", default = true),
                createdAtEpochMs = createdAt,
                updatedAtEpochMs = updatedAt,
            )
        }

    private fun parsePoolItems(
        rows: List<Map<String, Any?>>,
        fallbackTimestampMs: Long,
    ): List<BackupParsedPoolItem> =
        rows.mapIndexed { index, row ->
            val id = row.stringAlias("id")
                ?: throw IllegalArgumentException("第 ${index + 1} 条文案池项缺少 id")
            val templateId = row.stringAlias("template_id", "templateId")
                ?: throw IllegalArgumentException("文案池项 $id 缺少 template_id")
            val content = row.rawStringAlias("content")
                ?: throw IllegalArgumentException("文案池项 $id 缺少 content")
            val createdAt = row.timestampAlias(fallbackTimestampMs, "created_at", "createdAt")
            val updatedAt = row.timestampAlias(createdAt, "updated_at", "updatedAt")
            BackupParsedPoolItem(
                sourceId = id,
                templateId = templateId,
                content = content,
                weekdayMask = row.intAlias("weekday_mask", "weekdayMask") ?: 0,
                enabled = row.boolAlias("enabled", default = true),
                lastUsedAtEpochMs = row.timestampOrNullAlias("last_used_at", "lastUsedAt"),
                createdAtEpochMs = createdAt,
                updatedAtEpochMs = updatedAt,
            )
        }

    private fun parseTargets(
        rows: List<Map<String, Any?>>,
        fallbackTimestampMs: Long,
    ): List<BackupParsedTarget> =
        rows.mapIndexed { index, row ->
            val id = row.stringAlias("id")
                ?: throw IllegalArgumentException("第 ${index + 1} 条目标缺少 id")
            val accountId = row.stringAlias("account_id", "accountId")
                ?: throw IllegalArgumentException("目标 $id 缺少 account_id")
            val nickname = row.stringAlias("nickname_snapshot", "nicknameSnapshot")
                ?: throw IllegalArgumentException("目标 $id 缺少 nickname_snapshot")
            val createdAt = row.timestampAlias(fallbackTimestampMs, "created_at", "createdAt")
            val updatedAt = row.timestampAlias(createdAt, "updated_at", "updatedAt")
            BackupParsedTarget(
                sourceId = id,
                accountId = accountId,
                platformUserId = row.stringAlias("platform_user_id", "platformUserId"),
                platformHandle = row.stringAlias("platform_handle", "platformHandle"),
                platformConversationId = row.stringAlias(
                    "platform_conversation_id",
                    "platformConversationId",
                ),
                conversationType = mapConversationTypeFromBackup(
                    row.stringAlias("conversation_type", "conversationType"),
                ),
                nicknameSnapshot = nickname,
                templateId = row.stringAlias("template_id", "templateId"),
                enabled = row.boolAlias("enabled", default = true),
                sendTimeHhmm = row.stringAlias("send_time_hhmm", "sendTimeHhmm"),
                sparkDays = row.intAlias("spark_days", "sparkDays")?.coerceAtLeast(0),
                sparkKind = row.stringAlias("spark_kind", "sparkKind"),
                sparkLabel = row.stringAlias("spark_label", "sparkLabel"),
                sparkScannedAtEpochMs = row.timestampOrNullAlias(
                    "spark_scanned_at",
                    "sparkScannedAt",
                ),
                streakDays = (row.intAlias("streak_days", "streakDays") ?: 0).coerceAtLeast(0),
                lastSentAtEpochMs = row.timestampOrNullAlias("last_sent_at", "lastSentAt"),
                lastConfirmedAtEpochMs = row.timestampOrNullAlias(
                    "last_confirmed_at",
                    "lastConfirmedAt",
                ),
                deletedAtEpochMs = row.timestampOrNullAlias("deleted_at", "deletedAt"),
                createdAtEpochMs = createdAt,
                updatedAtEpochMs = updatedAt,
            )
        }

    private fun parseJobs(
        rows: List<Map<String, Any?>>,
        jobSummaryDetails: Map<String, String>,
        fallbackTimestampMs: Long,
    ): List<BackupParsedJob> =
        rows.mapIndexed { index, row ->
            val id = row.stringAlias("id")
                ?: throw IllegalArgumentException("第 ${index + 1} 条任务缺少 id")
            val accountId = row.stringAlias("account_id", "accountId")
                ?: throw IllegalArgumentException("任务 $id 缺少 account_id")
            val targetId = row.stringAlias("target_id", "targetId")
                ?: throw IllegalArgumentException("任务 $id 缺少 target_id")
            val businessDate = row.stringAlias("business_date", "businessDate")
                ?: throw IllegalArgumentException("任务 $id 缺少 business_date")
            val messageSlotId = row.stringAlias("message_slot_id", "messageSlotId")
                ?: throw IllegalArgumentException("任务 $id 缺少 message_slot_id")
            val rawStatus = row.stringAlias("status") ?: JobStatus.FAILED_RETRYABLE.wireValue
            val status = try {
                JobStatus.fromWireValue(rawStatus)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("任务 $id 状态无效：$rawStatus", e)
            }
            val createdAt = row.timestampAlias(fallbackTimestampMs, "created_at", "createdAt")
            val updatedAt = row.timestampAlias(createdAt, "updated_at", "updatedAt")
            BackupParsedJob(
                sourceId = id,
                accountId = accountId,
                targetId = targetId,
                templateId = row.stringAlias("template_id", "templateId"),
                businessDate = businessDate,
                messageSlotId = messageSlotId,
                status = status,
                scheduledAtEpochMs = row.timestampOrNullAlias("scheduled_at", "scheduledAt"),
                attemptCount = (row.intAlias("attempt_count", "attemptCount") ?: 0).coerceAtLeast(0),
                failureClass = coerceFailureClass(row.stringAlias("failure_class", "failureClass")),
                summaryDetail = jobSummaryDetails[id]
                    ?: row.stringAlias("summary_detail", "summaryDetail"),
                confirmedAtEpochMs = row.timestampOrNullAlias("confirmed_at", "confirmedAt"),
                createdAtEpochMs = createdAt,
                updatedAtEpochMs = updatedAt,
            )
        }

    private fun parseAttempts(
        rows: List<Map<String, Any?>>,
        attemptMeta: Map<String, BackupImportAttemptMeta>,
        fallbackTimestampMs: Long,
    ): List<BackupParsedAttempt> =
        rows.mapIndexed { index, row ->
            val id = row.stringAlias("id")
                ?: throw IllegalArgumentException("第 ${index + 1} 条尝试记录缺少 id")
            val jobId = row.stringAlias("job_id", "jobId")
                ?: throw IllegalArgumentException("尝试记录 $id 缺少 job_id")
            val rawStatus = row.stringAlias("status") ?: AttemptStatus.FAILED.wireValue
            val status = try {
                AttemptStatus.fromWireValue(rawStatus)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("尝试记录 $id 状态无效：$rawStatus", e)
            }
            val meta = attemptMeta[id]
            val startedAt = row.timestampAlias(fallbackTimestampMs, "started_at", "startedAt")
            BackupParsedAttempt(
                sourceId = id,
                jobId = jobId,
                attemptNo = (row.intAlias("attempt_no", "attemptNo") ?: 1).coerceAtLeast(1),
                stage = row.stringAlias("stage") ?: "sending",
                status = status,
                failureClass = coerceFailureClass(row.stringAlias("failure_class", "failureClass")),
                inputMethodUsed = meta?.inputMethodUsed
                    ?: row.stringAlias("input_method_used", "inputMethodUsed"),
                errorCode = meta?.errorCode ?: row.stringAlias("error_code", "errorCode"),
                startedAtEpochMs = startedAt,
                finishedAtEpochMs = row.timestampOrNullAlias("finished_at", "finishedAt"),
            )
        }

    private fun coerceFailureClass(raw: String?): FailureClass? {
        if (raw.isNullOrBlank()) return null
        return try {
            FailureClass.fromWireValue(raw)
        } catch (_: IllegalArgumentException) {
            FailureClass.OTHER
        }
    }

    private fun formatIso(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    private fun formatIsoOrNull(epochMs: Long?): String? =
        epochMs?.let { Instant.ofEpochMilli(it).toString() }

    private fun sha1Hex12(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }.take(12)
    }

    private fun parseExactInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.toInt().takeIf { value in Int.MIN_VALUE..Int.MAX_VALUE }
        else -> null
    }

    private fun Map<String, Any?>.valueAlias(vararg keys: String): Any? {
        for (key in keys) {
            this[key]?.let { return it }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.objectAlias(vararg keys: String): Map<String, Any?>? {
        val raw = valueAlias(*keys) ?: return null
        return raw as? Map<String, Any?>
            ?: throw IllegalArgumentException("字段 ${keys.first()} 必须为 JSON 对象")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.objectListAlias(
        vararg keys: String,
        required: Boolean = false,
    ): List<Map<String, Any?>> {
        val raw = valueAlias(*keys)
        if (raw == null) {
            if (required) {
                throw IllegalArgumentException("备份缺少 ${keys.first()} 数组")
            }
            return emptyList()
        }
        val list = raw as? List<*>
            ?: throw IllegalArgumentException("字段 ${keys.first()} 必须为 JSON 数组")
        return list.mapIndexed { idx, entry ->
            entry as? Map<String, Any?>
                ?: throw IllegalArgumentException("${keys.first()}[$idx] 必须为 JSON 对象")
        }
    }

    private fun Map<String, Any?>.stringAlias(vararg keys: String): String? =
        (valueAlias(*keys) as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, Any?>.rawStringAlias(vararg keys: String): String? =
        valueAlias(*keys) as? String

    private fun Map<String, Any?>.intAlias(vararg keys: String): Int? =
        (valueAlias(*keys) as? Number)?.toInt()

    private fun Map<String, Any?>.boolAlias(vararg keys: String, default: Boolean): Boolean =
        (valueAlias(*keys) as? Boolean) ?: default

    private fun Map<String, Any?>.boolOrNullAlias(vararg keys: String): Boolean? =
        valueAlias(*keys) as? Boolean

    private fun Map<String, Any?>.timestampOrNullAlias(vararg keys: String): Long? =
        parseTimestampOrNull(valueAlias(*keys) as? String)

    private fun Map<String, Any?>.timestampAlias(fallbackMs: Long, vararg keys: String): Long =
        timestampOrNullAlias(*keys) ?: fallbackMs
}
