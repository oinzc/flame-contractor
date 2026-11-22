package io.github.oinzc.flamecontractor.core.backup

import androidx.room.withTransaction
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.data.db.TemplatePoolItemEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.scheduler.DailySchedulePlanner
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

interface BackupStore {
    suspend fun <T> runInTransaction(block: suspend () -> T): T

    suspend fun getAccount(accountId: String): AccountEntity?
    suspend fun upsertAccount(account: AccountEntity)

    suspend fun listTemplates(accountId: String): List<MessageTemplateEntity>
    suspend fun templateIdExistsGlobally(templateId: String): Boolean = false
    suspend fun upsertTemplate(template: MessageTemplateEntity)

    suspend fun listPoolItems(templateId: String): List<TemplatePoolItemEntity>
    suspend fun poolItemIdExistsGlobally(poolItemId: String): Boolean = false
    suspend fun upsertPoolItem(item: TemplatePoolItemEntity)

    suspend fun listAllTargetsIncludingDeleted(accountId: String): List<ContractTargetEntity>
    suspend fun targetIdExistsGlobally(targetId: String): Boolean = false
    suspend fun upsertTarget(target: ContractTargetEntity)

    suspend fun listAllJobs(accountId: String): List<SendJobEntity>
    suspend fun jobIdExistsGlobally(jobId: String): Boolean = false
    suspend fun upsertJob(job: SendJobEntity)

    suspend fun listAllAttemptsForAccount(accountId: String): List<SendAttemptEntity>
    suspend fun attemptIdExistsGlobally(attemptId: String): Boolean = false
    suspend fun upsertAttempt(attempt: SendAttemptEntity)
}

class RoomBackupStore(private val database: FlameDatabase) : BackupStore {

    override suspend fun <T> runInTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }

    override suspend fun getAccount(accountId: String): AccountEntity? =
        database.accountDao().getAccount(accountId)

    override suspend fun upsertAccount(account: AccountEntity) {
        database.accountDao().upsert(account)
    }

    override suspend fun listTemplates(accountId: String): List<MessageTemplateEntity> =
        database.templateDao().listTemplates(accountId)

    override suspend fun templateIdExistsGlobally(templateId: String): Boolean =
        database.templateDao().getTemplateById(templateId) != null

    override suspend fun upsertTemplate(template: MessageTemplateEntity) {
        database.templateDao().upsertTemplate(template)
    }

    override suspend fun listPoolItems(templateId: String): List<TemplatePoolItemEntity> =
        database.templateDao().listPoolItems(templateId)

    override suspend fun poolItemIdExistsGlobally(poolItemId: String): Boolean =
        database.accountDao().listAll().any { acc ->
            database.templateDao().listTemplates(acc.id).any { tpl ->
                database.templateDao().listPoolItems(tpl.id).any { it.id == poolItemId }
            }
        }

    override suspend fun upsertPoolItem(item: TemplatePoolItemEntity) {
        database.templateDao().upsertPoolItem(item)
    }

    override suspend fun listAllTargetsIncludingDeleted(accountId: String): List<ContractTargetEntity> =
        database.targetDao().listAllTargetsIncludingDeleted(accountId)

    override suspend fun targetIdExistsGlobally(targetId: String): Boolean =
        database.targetDao().getById(targetId) != null

    override suspend fun upsertTarget(target: ContractTargetEntity) {
        database.targetDao().upsert(target)
    }

    override suspend fun listAllJobs(accountId: String): List<SendJobEntity> =
        database.sendJobDao().listAllJobs(accountId)

    override suspend fun jobIdExistsGlobally(jobId: String): Boolean =
        database.sendJobDao().getJobById(jobId) != null

    override suspend fun upsertJob(job: SendJobEntity) {
        database.sendJobDao().upsertJob(job)
    }

    override suspend fun listAllAttemptsForAccount(accountId: String): List<SendAttemptEntity> =
        database.sendJobDao().listAllAttemptsForAccount(accountId)

    override suspend fun attemptIdExistsGlobally(attemptId: String): Boolean =
        database.accountDao().listAll().any { acc ->
            database.sendJobDao().listAllAttemptsForAccount(acc.id).any { it.id == attemptId }
        }

    override suspend fun upsertAttempt(attempt: SendAttemptEntity) {
        database.sendJobDao().upsertAttempt(attempt)
    }
}

/**
 * 事务性跨端无损备份导出与导入管理器。
 */
class BackupManager(
    private val store: BackupStore,
    private val onScheduleChanged: suspend () -> Unit = {},
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val imageExists: (String?) -> Boolean = { path ->
        val cleaned = path?.trim()?.takeIf { it.isNotEmpty() }
        cleaned != null && File(cleaned).let { it.isFile && it.length() > 0L }
    },
) {

    suspend fun exportJson(accountId: String = DEFAULT_ACCOUNT_ID): String {
        val account = store.getAccount(accountId)
            ?: throw IllegalStateException("未找到本地账号：$accountId")
        val templates = store.listTemplates(accountId)
        val poolItems = templates.flatMap { store.listPoolItems(it.id) }
        val targets = store.listAllTargetsIncludingDeleted(accountId)
        val jobs = store.listAllJobs(accountId)
        val attempts = store.listAllAttemptsForAccount(accountId)

        return BackupCodec.encode(
            snapshot = BackupExportSnapshot(
                account = account,
                templates = templates,
                poolItems = poolItems,
                targets = targets,
                jobs = jobs,
                attempts = attempts,
            ),
            exportedAtEpochMs = clockMillis(),
        )
    }

    fun inspectJson(rawJson: String): BackupInspection = BackupCodec.inspect(rawJson)

    suspend fun importBackup(
        parsed: ParsedBackup,
        selectedSourceAccountId: String,
        includeHistory: Boolean = true,
        targetAccountId: String = DEFAULT_ACCOUNT_ID,
    ): BackupImportReport {
        val selectedAccount = parsed.accounts.firstOrNull { it.sourceId == selectedSourceAccountId }
            ?: throw IllegalArgumentException("备份中未找到选定账号：$selectedSourceAccountId")

        val report = store.runInTransaction {
            val nowMs = clockMillis()
            val warnings = mutableListOf<String>()

            mergeAccount(
                targetAccountId = targetAccountId,
                selectedAccount = selectedAccount,
                ext = parsed.androidAccountExt,
                nowMs = nowMs,
                warnings = warnings,
            )

            val templateMerge = mergeTemplates(
                targetAccountId = targetAccountId,
                importedTemplates = parsed.templates,
                nowMs = nowMs,
                warnings = warnings,
            )

            val poolItemsMerged = mergePoolItems(
                importedPoolItems = parsed.poolItems,
                templateMerge = templateMerge,
                nowMs = nowMs,
            )

            val targetMerge = mergeTargets(
                targetAccountId = targetAccountId,
                selectedSourceAccountId = selectedSourceAccountId,
                importedTargets = parsed.targets,
                templateMerge = templateMerge,
                nowMs = nowMs,
                warnings = warnings,
            )

            val historyMerge = if (includeHistory) {
                mergeJobsAndAttempts(
                    targetAccountId = targetAccountId,
                    selectedSourceAccountId = selectedSourceAccountId,
                    parsed = parsed,
                    templateIdRemap = templateMerge.templateIdRemap,
                    targetIdRemap = targetMerge.targetIdRemap,
                    nowMs = nowMs,
                    warnings = warnings,
                )
            } else {
                HistoryMergeResult()
            }

            BackupImportReport(
                sourceAccountName = selectedAccount.displayName,
                templatesInserted = templateMerge.inserted,
                templatesUpdated = templateMerge.updated,
                poolItemsMerged = poolItemsMerged,
                targetsInserted = targetMerge.inserted,
                targetsUpdated = targetMerge.updated,
                targetsSkipped = targetMerge.skipped,
                jobsInserted = historyMerge.jobsInserted,
                jobsUpdated = historyMerge.jobsUpdated,
                missingImageTemplateCount = templateMerge.missingImageCount,
                skippedProtectedJobs = historyMerge.skippedProtectedJobs,
                warnings = warnings,
            )
        }

        onScheduleChanged()
        return report
    }

    private suspend fun mergeAccount(
        targetAccountId: String,
        selectedAccount: BackupAccountCandidate,
        ext: BackupImportAccountExt?,
        nowMs: Long,
        warnings: MutableList<String>,
    ) {
        val local = store.getAccount(targetAccountId)
            ?: throw IllegalStateException("本地目标账号不存在：$targetAccountId")

        val resolvedTimezone = resolveValidTimezone(
            incoming = selectedAccount.timezone,
            fallback = local.timezone,
            warnings = warnings,
        )

        val (resolvedWindowStart, resolvedWindowEnd) = if (
            DailySchedulePlanner.validateWindow(
                selectedAccount.sendWindowStart,
                selectedAccount.sendWindowEnd,
            )
        ) {
            selectedAccount.sendWindowStart.trim() to selectedAccount.sendWindowEnd.trim()
        } else {
            warnings += "备份发送窗口（${selectedAccount.sendWindowStart}–${selectedAccount.sendWindowEnd}）不符合同日窗口约束，已保留本地窗口（${local.sendWindowStart}–${local.sendWindowEnd}）"
            local.sendWindowStart to local.sendWindowEnd
        }

        val resolvedSendTime = resolveOptionalHhmm(
            incoming = selectedAccount.sendTimeHhmm,
            fallback = local.sendTimeHhmm,
            label = "账号发送时刻",
            warnings = warnings,
        )

        val resolvedJitter = (ext?.jitterMaxMinutes ?: local.jitterMaxMinutes).coerceIn(0, 15)
        val resolvedCooldownMin = (ext?.cooldownMinSec ?: local.cooldownMinSec).coerceIn(0, 60)
        val rawCooldownMax = (ext?.cooldownMaxSec ?: local.cooldownMaxSec).coerceIn(0, 60)
        val resolvedCooldownMax = maxOf(resolvedCooldownMin, rawCooldownMax)
        val resolvedAutoScanEnabled = ext?.autoScanEnabled ?: local.autoScanEnabled
        val resolvedAutoScanHours =
            (ext?.autoScanIntervalHours ?: local.autoScanIntervalHours).coerceIn(1, 168)

        val importedLastSuccess = BackupCodec.parseTimestampOrNull(
            (selectedAccount.rawMap["last_success_at"] as? String)
                ?: (selectedAccount.rawMap["lastSuccessAt"] as? String),
        )

        val merged = local.copy(
            displayName = selectedAccount.displayName.trim().ifEmpty { local.displayName },
            // 严禁覆盖登录态、self_uid、douyin_nickname、auth_fail_count、failure_streak、last_verified_at 与自动扫描冷却时刻
            status = local.status,
            enabled = selectedAccount.enabled,
            timezone = resolvedTimezone,
            sendWindowStart = resolvedWindowStart,
            sendWindowEnd = resolvedWindowEnd,
            sendTimeHhmm = resolvedSendTime,
            jitterMaxMinutes = resolvedJitter,
            cooldownMinSec = resolvedCooldownMin,
            cooldownMaxSec = resolvedCooldownMax,
            authFailCount = local.authFailCount,
            failureStreak = local.failureStreak,
            selfUid = local.selfUid,
            douyinNickname = local.douyinNickname,
            lastVerifiedAt = local.lastVerifiedAt,
            lastSuccessAt = maxTimestamp(local.lastSuccessAt, importedLastSuccess),
            updatedAt = nowMs,
            autoScanEnabled = resolvedAutoScanEnabled,
            autoScanIntervalHours = resolvedAutoScanHours,
            lastAutoScanAttemptAt = local.lastAutoScanAttemptAt,
        )
        store.upsertAccount(merged)
    }

    private suspend fun mergeTemplates(
        targetAccountId: String,
        importedTemplates: List<BackupParsedTemplate>,
        nowMs: Long,
        warnings: MutableList<String>,
    ): TemplateMergeResult {
        val existingTemplates = store.listTemplates(targetAccountId)
        val byId = existingTemplates.associateBy { it.id }.toMutableMap()
        val byName = existingTemplates.associateBy { it.name }.toMutableMap()
        val templateIdRemap = linkedMapOf<String, String>()
        val keptPoolTemplateIds = linkedSetOf<String>()
        val allKnownPoolTemplateIds = existingTemplates
            .filter { it.type == TemplateType.POOL }
            .mapTo(linkedSetOf()) { it.id }

        var inserted = 0
        var updated = 0
        var missingImageCount = 0

        for (tpl in importedTemplates) {
            val importedType = tpl.type
            val isImage = importedType == TemplateType.IMAGE
            val matched = byId[tpl.sourceId] ?: byName[tpl.name]

            if (matched != null && matched.type == importedType) {
                val finalName = if (
                    tpl.name != matched.name &&
                    byName[tpl.name]?.let { it.id != matched.id } == true
                ) {
                    val renamed = uniqueTemplateName(tpl.name, byName.keys)
                    warnings += "模板「${tpl.name}」与本地同名模板冲突，已重命名为「$renamed」"
                    renamed
                } else {
                    tpl.name
                }

                if (finalName != matched.name) {
                    byName.remove(matched.name)
                }

                val resolvedImagePath = if (isImage) {
                    val localPath = matched.imagePath?.trim()?.takeIf { it.isNotEmpty() }
                    if (localPath != null && imageExists(localPath)) {
                        localPath
                    } else {
                        missingImageCount++
                        null
                    }
                } else {
                    null
                }

                val next = matched.copy(
                    name = finalName,
                    content = if (isImage) "" else tpl.content,
                    stickerName = if (isImage) null else tpl.stickerName,
                    stickerLabel = if (isImage) null else tpl.stickerLabel,
                    stickerCategory = if (isImage) null else tpl.stickerCategory,
                    stickerFallbackIndex = if (isImage) null else tpl.stickerFallbackIndex,
                    imagePath = resolvedImagePath,
                    enabled = tpl.enabled,
                    updatedAt = nowMs,
                )
                store.upsertTemplate(next)
                byId[next.id] = next
                byName[next.name] = next
                templateIdRemap[tpl.sourceId] = next.id
                if (next.type == TemplateType.POOL) {
                    keptPoolTemplateIds += next.id
                    allKnownPoolTemplateIds += next.id
                }
                updated++
                continue
            }

            val finalName = if (byName.containsKey(tpl.name)) {
                val renamed = uniqueTemplateName(tpl.name, byName.keys)
                warnings += if (matched != null && matched.type != importedType) {
                    "模板「${tpl.name}」（类型 ${importedType.wireValue}）与本地现有模板类型冲突，已另存为「$renamed」"
                } else {
                    "模板「${tpl.name}」与本地同名记录冲突，已另存为「$renamed」"
                }
                renamed
            } else {
                tpl.name
            }

            val newId = if (
                matched == null &&
                tpl.sourceId.isNotBlank() &&
                !byId.containsKey(tpl.sourceId) &&
                !store.templateIdExistsGlobally(tpl.sourceId)
            ) {
                tpl.sourceId
            } else {
                UUID.randomUUID().toString()
            }

            if (isImage) {
                missingImageCount++
            }

            val created = MessageTemplateEntity(
                id = newId,
                accountId = targetAccountId,
                name = finalName,
                type = importedType,
                content = if (isImage) "" else tpl.content,
                stickerName = if (isImage) null else tpl.stickerName,
                stickerLabel = if (isImage) null else tpl.stickerLabel,
                stickerCategory = if (isImage) null else tpl.stickerCategory,
                stickerFallbackIndex = if (isImage) null else tpl.stickerFallbackIndex,
                enabled = tpl.enabled,
                createdAt = tpl.createdAtEpochMs.takeIf { it > 0L } ?: nowMs,
                updatedAt = nowMs,
                imagePath = null,
            )
            store.upsertTemplate(created)
            byId[created.id] = created
            byName[created.name] = created
            templateIdRemap[tpl.sourceId] = created.id
            if (created.type == TemplateType.POOL) {
                keptPoolTemplateIds += created.id
                allKnownPoolTemplateIds += created.id
            }
            inserted++
        }

        if (missingImageCount > 0) {
            warnings += "已保留 $missingImageCount 个图片模板及好友绑定，请前往「模板」页重新选择本地图片"
        }

        return TemplateMergeResult(
            inserted = inserted,
            updated = updated,
            missingImageCount = missingImageCount,
            templateIdRemap = templateIdRemap,
            keptPoolTemplateIds = keptPoolTemplateIds,
            allPoolTemplateIds = allKnownPoolTemplateIds,
        )
    }

    private suspend fun mergePoolItems(
        importedPoolItems: List<BackupParsedPoolItem>,
        templateMerge: TemplateMergeResult,
        nowMs: Long,
    ): Int {
        if (templateMerge.keptPoolTemplateIds.isEmpty()) return 0

        val existingItems = templateMerge.allPoolTemplateIds.flatMap { store.listPoolItems(it) }
        val byId = existingItems.associateBy { it.id }.toMutableMap()
        val byNaturalKey = existingItems
            .associateBy { Triple(it.templateId, it.content, it.weekdayMask) }
            .toMutableMap()

        var mergedCount = 0
        for (item in importedPoolItems) {
            val remappedTplId = templateMerge.templateIdRemap[item.templateId] ?: continue
            if (remappedTplId !in templateMerge.keptPoolTemplateIds) continue

            val nk = Triple(remappedTplId, item.content, item.weekdayMask.coerceIn(0, 127))
            val existing = byId[item.sourceId]?.takeIf { it.templateId == remappedTplId }
                ?: byNaturalKey[nk]

            if (existing != null) {
                val oldNk = Triple(existing.templateId, existing.content, existing.weekdayMask)
                val next = existing.copy(
                    content = item.content,
                    weekdayMask = item.weekdayMask.coerceIn(0, 127),
                    enabled = item.enabled,
                    lastUsedAt = maxTimestamp(existing.lastUsedAt, item.lastUsedAtEpochMs),
                    updatedAt = nowMs,
                )
                store.upsertPoolItem(next)
                byId[next.id] = next
                if (oldNk != nk) {
                    byNaturalKey.remove(oldNk)
                }
                byNaturalKey[nk] = next
                mergedCount++
                continue
            }

            val newId = if (
                item.sourceId.isNotBlank() &&
                !byId.containsKey(item.sourceId) &&
                !store.poolItemIdExistsGlobally(item.sourceId)
            ) {
                item.sourceId
            } else {
                UUID.randomUUID().toString()
            }
            val created = TemplatePoolItemEntity(
                id = newId,
                templateId = remappedTplId,
                content = item.content,
                weekdayMask = item.weekdayMask.coerceIn(0, 127),
                enabled = item.enabled,
                lastUsedAt = item.lastUsedAtEpochMs,
                createdAt = item.createdAtEpochMs.takeIf { it > 0L } ?: nowMs,
                updatedAt = nowMs,
            )
            store.upsertPoolItem(created)
            byId[created.id] = created
            byNaturalKey[nk] = created
            mergedCount++
        }
        return mergedCount
    }

    private suspend fun mergeTargets(
        targetAccountId: String,
        selectedSourceAccountId: String,
        importedTargets: List<BackupParsedTarget>,
        templateMerge: TemplateMergeResult,
        nowMs: Long,
        warnings: MutableList<String>,
    ): TargetMergeResult {
        val existingTargets = store.listAllTargetsIncludingDeleted(targetAccountId)
        val byId = linkedMapOf<String, ContractTargetEntity>()
        val byNick = linkedMapOf<String, ContractTargetEntity>()
        val byPuid = linkedMapOf<String, ContractTargetEntity>()
        val byPcid = linkedMapOf<String, ContractTargetEntity>()

        for (target in existingTargets) {
            byId[target.id] = target
            registerTargetIndexes(target, byNick, byPuid, byPcid)
        }

        val targetIdRemap = linkedMapOf<String, String>()
        var inserted = 0
        var updated = 0
        var skipped = 0

        for (imported in importedTargets) {
            if (imported.accountId != selectedSourceAccountId) continue

            val nick = imported.nicknameSnapshot.trim()
            if (nick.isEmpty()) {
                skipped++
                warnings += "备份中存在缺少昵称的目标记录（${imported.sourceId}），已跳过"
                continue
            }

            val existing = byId[imported.sourceId]
                ?: imported.platformUserId?.let { byPuid[it] }
                ?: imported.platformConversationId?.let { byPcid[it] }
                ?: byNick[nick]

            val rawTplId = imported.templateId
            val remappedTplId = rawTplId?.let { templateMerge.templateIdRemap[it] }

            if (existing != null) {
                val finalPuid = imported.platformUserId ?: existing.platformUserId
                val finalPcid = imported.platformConversationId ?: existing.platformConversationId
                val conflictReason = checkExistingTargetConflict(
                    existing = existing,
                    imported = imported,
                    finalNick = nick,
                    finalPuid = finalPuid,
                    finalPcid = finalPcid,
                    byNick = byNick,
                    byPuid = byPuid,
                    byPcid = byPcid,
                )
                if (conflictReason != null) {
                    skipped++
                    warnings += conflictReason
                    continue
                }

                unregisterTargetIndexes(existing, byNick, byPuid, byPcid)

                val localActive = existing.deletedAt == null
                val importedActive = imported.deletedAtEpochMs == null
                val finalDeletedAt = if (localActive || importedActive) {
                    null
                } else {
                    imported.deletedAtEpochMs
                }
                val finalEnabled = if (localActive && !importedActive) {
                    existing.enabled
                } else {
                    imported.enabled
                }

                val finalTemplateId = resolveTargetTemplateId(
                    nick = nick,
                    rawTplId = rawTplId,
                    remappedTplId = remappedTplId,
                    isExistingTarget = true,
                    warnings = warnings,
                )

                val finalSendTime = resolveOptionalHhmm(
                    incoming = imported.sendTimeHhmm,
                    fallback = existing.sendTimeHhmm,
                    label = "好友「$nick」发送时刻",
                    warnings = warnings,
                )

                val useImportedSpark = isNewerTimestamp(
                    incoming = imported.sparkScannedAtEpochMs,
                    existing = existing.sparkScannedAt,
                )

                val merged = existing.copy(
                    nicknameSnapshot = nick,
                    platformUserId = finalPuid,
                    platformHandle = imported.platformHandle ?: existing.platformHandle,
                    platformConversationId = finalPcid,
                    conversationType = imported.conversationType ?: existing.conversationType,
                    templateId = finalTemplateId,
                    enabled = finalEnabled,
                    sendTimeHhmm = finalSendTime,
                    sparkDays = if (useImportedSpark) imported.sparkDays else existing.sparkDays,
                    sparkKind = if (useImportedSpark) imported.sparkKind else existing.sparkKind,
                    sparkLabel = if (useImportedSpark) imported.sparkLabel else existing.sparkLabel,
                    sparkScannedAt = if (useImportedSpark) {
                        imported.sparkScannedAtEpochMs
                    } else {
                        existing.sparkScannedAt
                    },
                    streakDays = maxOf(existing.streakDays, imported.streakDays),
                    lastSentAt = maxTimestamp(existing.lastSentAt, imported.lastSentAtEpochMs),
                    lastConfirmedAt = maxTimestamp(
                        existing.lastConfirmedAt,
                        imported.lastConfirmedAtEpochMs,
                    ),
                    deletedAt = finalDeletedAt,
                    updatedAt = nowMs,
                )

                store.upsertTarget(merged)
                byId[merged.id] = merged
                registerTargetIndexes(merged, byNick, byPuid, byPcid)
                targetIdRemap[imported.sourceId] = merged.id
                updated++
                continue
            }

            if (hasNewTargetConflict(
                    nick = nick,
                    puid = imported.platformUserId,
                    pcid = imported.platformConversationId,
                    byNick = byNick,
                    byPuid = byPuid,
                    byPcid = byPcid,
                )
            ) {
                skipped++
                warnings += "新目标「$nick」与本地已有目标唯一键冲突，已跳过"
                continue
            }

            val finalTemplateId = resolveTargetTemplateId(
                nick = nick,
                rawTplId = rawTplId,
                remappedTplId = remappedTplId,
                isExistingTarget = false,
                warnings = warnings,
            )

            val finalSendTime = resolveOptionalHhmm(
                incoming = imported.sendTimeHhmm,
                fallback = null,
                label = "新好友「$nick」发送时刻",
                warnings = warnings,
            )

            val newId = if (
                imported.sourceId.isNotBlank() &&
                !byId.containsKey(imported.sourceId) &&
                !store.targetIdExistsGlobally(imported.sourceId)
            ) {
                imported.sourceId
            } else {
                UUID.randomUUID().toString()
            }

            val created = ContractTargetEntity(
                id = newId,
                accountId = targetAccountId,
                nicknameSnapshot = nick,
                platformUserId = imported.platformUserId,
                platformHandle = imported.platformHandle,
                platformConversationId = imported.platformConversationId,
                conversationType = imported.conversationType,
                templateId = finalTemplateId,
                enabled = imported.enabled,
                sendTimeHhmm = finalSendTime,
                sparkDays = imported.sparkDays,
                sparkKind = imported.sparkKind,
                sparkLabel = imported.sparkLabel,
                sparkScannedAt = imported.sparkScannedAtEpochMs,
                streakDays = imported.streakDays,
                lastSentAt = imported.lastSentAtEpochMs,
                lastConfirmedAt = imported.lastConfirmedAtEpochMs,
                deletedAt = imported.deletedAtEpochMs,
                createdAt = imported.createdAtEpochMs.takeIf { it > 0L } ?: nowMs,
                updatedAt = nowMs,
            )

            store.upsertTarget(created)
            byId[created.id] = created
            registerTargetIndexes(created, byNick, byPuid, byPcid)
            targetIdRemap[imported.sourceId] = created.id
            inserted++
        }

        return TargetMergeResult(
            inserted = inserted,
            updated = updated,
            skipped = skipped,
            targetIdRemap = targetIdRemap,
        )
    }

    private suspend fun mergeJobsAndAttempts(
        targetAccountId: String,
        selectedSourceAccountId: String,
        parsed: ParsedBackup,
        templateIdRemap: Map<String, String>,
        targetIdRemap: Map<String, String>,
        nowMs: Long,
        warnings: MutableList<String>,
    ): HistoryMergeResult {
        val candidateJobs = parsed.jobs.mapNotNull { job ->
            if (job.accountId != selectedSourceAccountId) return@mapNotNull null
            val remappedTargetId = targetIdRemap[job.targetId] ?: return@mapNotNull null
            if (!isValidBusinessDate(job.businessDate)) {
                warnings += "历史任务 ${job.sourceId} 业务日格式无效（${job.businessDate}），已跳过"
                return@mapNotNull null
            }
            val initialSlot = BackupCodec.resolveSlotIdFromBackup(
                backupSlot = job.messageSlotId,
                jobId = job.sourceId,
                originalSlotFromExt = parsed.jobOriginalSlots[job.sourceId],
            )
            val normalizedStatus = normalizeImportedJobStatus(job.status)
            NormalizedImportJob(
                source = job,
                remappedTargetId = remappedTargetId,
                remappedTemplateId = job.templateId?.let { templateIdRemap[it] },
                resolvedSlot = initialSlot,
                normalizedStatus = normalizedStatus,
            )
        }

        val resolvedJobs = resolveSameDayDailySlotCollisions(candidateJobs, warnings)

        val existingJobs = store.listAllJobs(targetAccountId)
        val jobById = existingJobs.associateBy { it.id }.toMutableMap()
        val jobBySlot = existingJobs
            .associateBy { JobSlotKey(it.accountId, it.targetId, it.businessDate, it.messageSlotId) }
            .toMutableMap()
        val jobIdRemap = linkedMapOf<String, String>()

        var jobsInserted = 0
        var jobsUpdated = 0
        var skippedProtectedJobs = 0

        for (item in resolvedJobs) {
            val job = item.source
            val slotKey = JobSlotKey(
                accountId = targetAccountId,
                targetId = item.remappedTargetId,
                businessDate = job.businessDate,
                slotId = item.resolvedSlot,
            )
            val slotMatch = jobBySlot[slotKey]
            val idMatch = jobById[job.sourceId]?.takeIf {
                it.targetId == item.remappedTargetId && it.businessDate == job.businessDate
            }
            val existing = slotMatch ?: idMatch

            if (existing != null) {
                if (existing.status in PROTECTED_JOB_STATUSES) {
                    skippedProtectedJobs++
                    warnings += "本地任务 ${job.businessDate}（${existing.status.wireValue}）受安全保护，已跳过外部覆盖"
                    continue
                }

                // 若通过 idMatch 命中了本地 daily 槽位任务，严禁被备份中的非 daily 槽位迁出或解除 daily 锁定
                val preserveLocalDailySlot =
                    existing.messageSlotId == SendGuard.DAILY_SLOT_ID &&
                        item.resolvedSlot != SendGuard.DAILY_SLOT_ID
                val finalSlotId = if (preserveLocalDailySlot) {
                    SendGuard.DAILY_SLOT_ID
                } else {
                    item.resolvedSlot
                }
                val targetSlotKey = if (preserveLocalDailySlot) {
                    JobSlotKey(
                        accountId = targetAccountId,
                        targetId = item.remappedTargetId,
                        businessDate = job.businessDate,
                        slotId = SendGuard.DAILY_SLOT_ID,
                    )
                } else {
                    slotKey
                }
                if (preserveLocalDailySlot) {
                    warnings += "本地 ${job.businessDate} 日常任务已锁定 daily 槽位，拒绝迁移至 ${item.resolvedSlot}"
                }

                val oldSlotKey = JobSlotKey(
                    accountId = existing.accountId,
                    targetId = existing.targetId,
                    businessDate = existing.businessDate,
                    slotId = existing.messageSlotId,
                )
                if (oldSlotKey != targetSlotKey && jobBySlot.containsKey(targetSlotKey)) {
                    continue
                }

                // daily 槽位严格单调防降级：终态与已尝试状态绝不被较早状态覆写而重开当日发送
                val keepLocalDailyState =
                    finalSlotId == SendGuard.DAILY_SLOT_ID &&
                        isDailyStatusDowngrade(existing.status, item.normalizedStatus)

                val updated = if (keepLocalDailyState) {
                    warnings += if (existing.status == JobStatus.SUCCEEDED) {
                        "本地 ${job.businessDate} 日常任务已成功，拒绝被较早状态（${item.normalizedStatus.wireValue}）降级"
                    } else {
                        "本地 ${job.businessDate} 日常任务已处于 ${existing.status.wireValue} 状态，拒绝被较早状态（${item.normalizedStatus.wireValue}）降级"
                    }
                    existing.copy(
                        messageSlotId = finalSlotId,
                        updatedAt = nowMs,
                    )
                } else {
                    val finalStatus = item.normalizedStatus
                    val finalFailureClass = resolveImportedFailureClass(job, finalStatus)
                        ?: if (finalStatus == existing.status) existing.failureClass else null
                    val finalAttemptCount = if (
                        finalSlotId == SendGuard.DAILY_SLOT_ID &&
                        finalStatus == existing.status
                    ) {
                        maxOf(existing.attemptCount, job.attemptCount)
                    } else {
                        job.attemptCount
                    }
                    existing.copy(
                        templateId = item.remappedTemplateId ?: existing.templateId,
                        messageSlotId = finalSlotId,
                        status = finalStatus,
                        scheduledAt = job.scheduledAtEpochMs ?: existing.scheduledAt,
                        attemptCount = finalAttemptCount,
                        failureClass = finalFailureClass,
                        summaryDetail = parsed.jobSummaryDetails[job.sourceId]
                            ?: job.summaryDetail
                            ?: existing.summaryDetail,
                        confirmedAt = job.confirmedAtEpochMs ?: existing.confirmedAt,
                        updatedAt = nowMs,
                    )
                }
                store.upsertJob(updated)
                jobById[updated.id] = updated
                if (oldSlotKey != targetSlotKey) {
                    jobBySlot.remove(oldSlotKey)
                }
                jobBySlot[targetSlotKey] = updated
                jobIdRemap[job.sourceId] = updated.id
                jobsUpdated++
                continue
            }

            if (jobBySlot.containsKey(slotKey)) {
                continue
            }

            val finalFailureClass = resolveImportedFailureClass(job, item.normalizedStatus)

            val newId = if (
                job.sourceId.isNotBlank() &&
                !jobById.containsKey(job.sourceId) &&
                !store.jobIdExistsGlobally(job.sourceId)
            ) {
                job.sourceId
            } else {
                UUID.randomUUID().toString()
            }

            val created = SendJobEntity(
                id = newId,
                accountId = targetAccountId,
                targetId = item.remappedTargetId,
                templateId = item.remappedTemplateId,
                businessDate = job.businessDate,
                messageSlotId = item.resolvedSlot,
                status = item.normalizedStatus,
                scheduledAt = job.scheduledAtEpochMs,
                attemptCount = job.attemptCount,
                failureClass = finalFailureClass,
                summaryDetail = parsed.jobSummaryDetails[job.sourceId] ?: job.summaryDetail,
                confirmedAt = job.confirmedAtEpochMs,
                createdAt = job.createdAtEpochMs.takeIf { it > 0L } ?: nowMs,
                updatedAt = nowMs,
            )
            store.upsertJob(created)
            jobById[created.id] = created
            jobBySlot[slotKey] = created
            jobIdRemap[job.sourceId] = created.id
            jobsInserted++
        }

        if (jobIdRemap.isNotEmpty()) {
            mergeAttempts(
                targetAccountId = targetAccountId,
                parsed = parsed,
                jobIdRemap = jobIdRemap,
                nowMs = nowMs,
            )
        }

        return HistoryMergeResult(
            jobsInserted = jobsInserted,
            jobsUpdated = jobsUpdated,
            skippedProtectedJobs = skippedProtectedJobs,
        )
    }

    private suspend fun mergeAttempts(
        targetAccountId: String,
        parsed: ParsedBackup,
        jobIdRemap: Map<String, String>,
        nowMs: Long,
    ) {
        val existingAttempts = store.listAllAttemptsForAccount(targetAccountId)
        val byId = existingAttempts.associateBy { it.id }.toMutableMap()
        val byJobNo = existingAttempts.associateBy { it.jobId to it.attemptNo }.toMutableMap()

        for (attempt in parsed.attempts) {
            val remappedJobId = jobIdRemap[attempt.jobId] ?: continue
            val normalizedStatus = if (attempt.status == AttemptStatus.RUNNING) {
                AttemptStatus.FAILED
            } else {
                attempt.status
            }
            val meta = parsed.attemptMeta[attempt.sourceId]
            val inputMethod = meta?.inputMethodUsed ?: attempt.inputMethodUsed
            val errorCode = meta?.errorCode ?: attempt.errorCode

            val jobNoKey = remappedJobId to attempt.attemptNo
            val existing = byJobNo[jobNoKey]
                ?: byId[attempt.sourceId]?.takeIf { it.jobId == remappedJobId }

            if (existing != null) {
                val next = existing.copy(
                    stage = attempt.stage,
                    status = normalizedStatus,
                    failureClass = attempt.failureClass,
                    inputMethodUsed = inputMethod ?: existing.inputMethodUsed,
                    errorCode = errorCode ?: existing.errorCode,
                    startedAt = attempt.startedAtEpochMs.takeIf { it > 0L } ?: existing.startedAt,
                    finishedAt = attempt.finishedAtEpochMs ?: existing.finishedAt,
                )
                store.upsertAttempt(next)
                byId[next.id] = next
                byJobNo[jobNoKey] = next
                continue
            }

            val newId = if (
                attempt.sourceId.isNotBlank() &&
                !byId.containsKey(attempt.sourceId) &&
                !store.attemptIdExistsGlobally(attempt.sourceId)
            ) {
                attempt.sourceId
            } else {
                UUID.randomUUID().toString()
            }
            val created = SendAttemptEntity(
                id = newId,
                jobId = remappedJobId,
                attemptNo = attempt.attemptNo,
                stage = attempt.stage,
                status = normalizedStatus,
                failureClass = attempt.failureClass,
                inputMethodUsed = inputMethod,
                errorCode = errorCode,
                startedAt = attempt.startedAtEpochMs.takeIf { it > 0L } ?: nowMs,
                finishedAt = attempt.finishedAtEpochMs,
            )
            store.upsertAttempt(created)
            byId[created.id] = created
            byJobNo[jobNoKey] = created
        }
    }

    private fun resolveSameDayDailySlotCollisions(
        jobs: List<NormalizedImportJob>,
        warnings: MutableList<String>,
    ): List<NormalizedImportJob> {
        val dailyGroups = jobs
            .filter { it.resolvedSlot == SendGuard.DAILY_SLOT_ID }
            .groupBy { it.remappedTargetId to it.source.businessDate }

        val demotedIds = hashSetOf<String>()
        for ((key, group) in dailyGroups) {
            if (group.size <= 1) continue
            val sorted = group.sortedWith(
                compareBy<NormalizedImportJob>(
                    { dailyPriorityRank(it.source.status) },
                    { -it.source.updatedAtEpochMs },
                    { -it.source.createdAtEpochMs },
                    { it.source.sourceId },
                ),
            )
            for (loser in sorted.drop(1)) {
                demotedIds += loser.source.sourceId
            }
            warnings += "目标在 ${key.second} 存在 ${group.size} 条日常模板历史记录，已保留最高安全优先级任务在 daily 槽位，其余转入手动历史槽位"
        }

        return jobs.map { item ->
            if (item.source.sourceId in demotedIds) {
                item.copy(resolvedSlot = "manual:import:${item.source.sourceId.take(48)}")
            } else {
                item
            }
        }
    }

    private fun checkExistingTargetConflict(
        existing: ContractTargetEntity,
        imported: BackupParsedTarget,
        finalNick: String,
        finalPuid: String?,
        finalPcid: String?,
        byNick: Map<String, ContractTargetEntity>,
        byPuid: Map<String, ContractTargetEntity>,
        byPcid: Map<String, ContractTargetEntity>,
    ): String? {
        if (byNick[finalNick]?.let { it.id != existing.id } == true) {
            return "目标「$finalNick」昵称与本地另一目标冲突，已跳过"
        }
        if (finalPuid != null && byPuid[finalPuid]?.let { it.id != existing.id } == true) {
            return "目标「$finalNick」用户身份与本地另一目标冲突，已跳过"
        }
        if (finalPcid != null && byPcid[finalPcid]?.let { it.id != existing.id } == true) {
            return "目标「$finalNick」会话身份与本地另一目标冲突，已跳过"
        }
        if (existing.platformUserId != null &&
            imported.platformUserId != null &&
            existing.platformUserId != imported.platformUserId
        ) {
            return "目标「$finalNick」与本地已有记录的用户身份不一致，已跳过"
        }
        if (existing.platformConversationId != null &&
            imported.platformConversationId != null &&
            existing.platformConversationId != imported.platformConversationId
        ) {
            return "目标「$finalNick」与本地已有记录的会话身份不一致，已跳过"
        }
        if (existing.platformUserId != null &&
            imported.platformConversationId != null &&
            existing.platformConversationId == null
        ) {
            return "目标「$finalNick」与本地单聊身份类型冲突，已跳过"
        }
        if (existing.platformConversationId != null &&
            imported.platformUserId != null &&
            existing.platformUserId == null
        ) {
            return "目标「$finalNick」与本地群聊身份类型冲突，已跳过"
        }
        return null
    }

    private fun hasNewTargetConflict(
        nick: String,
        puid: String?,
        pcid: String?,
        byNick: Map<String, ContractTargetEntity>,
        byPuid: Map<String, ContractTargetEntity>,
        byPcid: Map<String, ContractTargetEntity>,
    ): Boolean =
        byNick.containsKey(nick) ||
            (puid != null && byPuid.containsKey(puid)) ||
            (pcid != null && byPcid.containsKey(pcid))

    private fun registerTargetIndexes(
        target: ContractTargetEntity,
        byNick: MutableMap<String, ContractTargetEntity>,
        byPuid: MutableMap<String, ContractTargetEntity>,
        byPcid: MutableMap<String, ContractTargetEntity>,
    ) {
        byNick[target.nicknameSnapshot] = target
        target.platformUserId?.let { byPuid[it] = target }
        target.platformConversationId?.let { byPcid[it] = target }
    }

    private fun unregisterTargetIndexes(
        target: ContractTargetEntity,
        byNick: MutableMap<String, ContractTargetEntity>,
        byPuid: MutableMap<String, ContractTargetEntity>,
        byPcid: MutableMap<String, ContractTargetEntity>,
    ) {
        byNick.remove(target.nicknameSnapshot)
        target.platformUserId?.let { byPuid.remove(it) }
        target.platformConversationId?.let { byPcid.remove(it) }
    }

    private fun resolveValidTimezone(
        incoming: String,
        fallback: String,
        warnings: MutableList<String>,
    ): String {
        val cleaned = incoming.trim()
        if (cleaned.isEmpty()) return fallback
        return try {
            ZoneId.of(cleaned)
            cleaned
        } catch (_: Exception) {
            warnings += "备份时区「$incoming」无效，已保留本地时区「$fallback」"
            fallback
        }
    }

    private fun resolveOptionalHhmm(
        incoming: String?,
        fallback: String?,
        label: String,
        warnings: MutableList<String>,
    ): String? {
        val cleaned = incoming?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (STRICT_HHMM_REGEX.matches(cleaned)) {
            return cleaned
        }
        warnings += "${label}「$cleaned」格式无效，已保留原设置"
        return fallback
    }

    private fun uniqueTemplateName(baseName: String, takenNames: Set<String>): String {
        val suffix = " (导入)"
        val first = "${baseName.take(TEMPLATE_NAME_MAX_LEN - suffix.length)}$suffix"
        if (first !in takenNames) return first
        var index = 2
        while (true) {
            val numberedSuffix = " (导入 $index)"
            val candidate =
                "${baseName.take(TEMPLATE_NAME_MAX_LEN - numberedSuffix.length)}$numberedSuffix"
            if (candidate !in takenNames) return candidate
            index++
        }
    }

    private fun resolveTargetTemplateId(
        nick: String,
        rawTplId: String?,
        remappedTplId: String?,
        isExistingTarget: Boolean,
        warnings: MutableList<String>,
    ): String? = when {
        rawTplId == null -> null
        remappedTplId != null -> remappedTplId
        else -> {
            warnings += if (isExistingTarget) {
                "好友「$nick」引用的模板在备份中缺失，已置为空模板"
            } else {
                "新导入好友「$nick」引用的模板缺失，已置为空模板"
            }
            null
        }
    }

    private fun resolveImportedFailureClass(
        job: BackupParsedJob,
        finalStatus: JobStatus,
    ): FailureClass? = when {
        finalStatus == JobStatus.UNKNOWN_REQUIRES_REVIEW ->
            job.failureClass ?: FailureClass.UNKNOWN_OUTCOME
        finalStatus == JobStatus.FAILED_RETRYABLE && job.status != JobStatus.FAILED_RETRYABLE ->
            job.failureClass ?: FailureClass.OTHER
        else -> job.failureClass
    }

    private fun normalizeImportedJobStatus(status: JobStatus): JobStatus =
        when (status) {
            JobStatus.SENDING,
            JobStatus.CONFIRMING,
            -> JobStatus.UNKNOWN_REQUIRES_REVIEW

            JobStatus.LEASED,
            JobStatus.OPENING_CHAT,
            -> JobStatus.FAILED_RETRYABLE

            else -> status
        }

    private fun isDailyStatusDowngrade(
        existingStatus: JobStatus,
        incomingNormalizedStatus: JobStatus,
    ): Boolean =
        dailyMonotonicRank(incomingNormalizedStatus) < dailyMonotonicRank(existingStatus)

    private fun dailyMonotonicRank(status: JobStatus): Int =
        when (status) {
            JobStatus.SCHEDULED -> 0
            JobStatus.LEASED,
            JobStatus.OPENING_CHAT,
            -> 1
            JobStatus.FAILED_RETRYABLE -> 2
            JobStatus.CANCELLED -> 3
            JobStatus.FAILED_TERMINAL -> 4
            JobStatus.SUCCEEDED -> 5
            JobStatus.UNKNOWN_REQUIRES_REVIEW,
            JobStatus.SENDING,
            JobStatus.CONFIRMING,
            -> 6
        }

    private fun dailyPriorityRank(status: JobStatus): Int =
        when (status) {
            JobStatus.UNKNOWN_REQUIRES_REVIEW -> 0
            JobStatus.SUCCEEDED -> 1
            JobStatus.FAILED_TERMINAL -> 2
            JobStatus.FAILED_RETRYABLE -> 3
            JobStatus.CANCELLED -> 4
            JobStatus.SCHEDULED -> 5
            JobStatus.LEASED -> 6
            JobStatus.OPENING_CHAT -> 7
            JobStatus.SENDING -> 8
            JobStatus.CONFIRMING -> 9
        }

    private fun isValidBusinessDate(raw: String): Boolean =
        runCatching { LocalDate.parse(raw) }.isSuccess

    private fun maxTimestamp(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun isNewerTimestamp(incoming: Long?, existing: Long?): Boolean {
        if (incoming == null) return false
        if (existing == null) return true
        return incoming > existing
    }

    private data class TemplateMergeResult(
        val inserted: Int,
        val updated: Int,
        val missingImageCount: Int,
        val templateIdRemap: Map<String, String>,
        val keptPoolTemplateIds: Set<String>,
        val allPoolTemplateIds: Set<String>,
    )

    private data class TargetMergeResult(
        val inserted: Int,
        val updated: Int,
        val skipped: Int,
        val targetIdRemap: Map<String, String>,
    )

    private data class HistoryMergeResult(
        val jobsInserted: Int = 0,
        val jobsUpdated: Int = 0,
        val skippedProtectedJobs: Int = 0,
    )

    private data class JobSlotKey(
        val accountId: String,
        val targetId: String,
        val businessDate: String,
        val slotId: String,
    )

    private data class NormalizedImportJob(
        val source: BackupParsedJob,
        val remappedTargetId: String,
        val remappedTemplateId: String?,
        val resolvedSlot: String,
        val normalizedStatus: JobStatus,
    )

    companion object {
        private const val TEMPLATE_NAME_MAX_LEN = 60
        private val STRICT_HHMM_REGEX = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

        private val PROTECTED_JOB_STATUSES: Set<JobStatus> = setOf(
            JobStatus.UNKNOWN_REQUIRES_REVIEW,
            JobStatus.SENDING,
            JobStatus.CONFIRMING,
        )
    }
}
