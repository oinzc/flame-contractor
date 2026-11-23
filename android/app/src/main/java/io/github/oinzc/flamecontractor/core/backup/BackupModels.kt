package io.github.oinzc.flamecontractor.core.backup

import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.data.db.TemplatePoolItemEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType

data class BackupExportSnapshot(
    val account: AccountEntity,
    val templates: List<MessageTemplateEntity>,
    val poolItems: List<TemplatePoolItemEntity>,
    val targets: List<ContractTargetEntity>,
    val jobs: List<SendJobEntity>,
    val attempts: List<SendAttemptEntity>,
)

data class BackupAccountCandidate(
    val sourceId: String,
    val displayName: String,
    val timezone: String,
    val sendWindowStart: String,
    val sendWindowEnd: String,
    val sendTimeHhmm: String?,
    val enabled: Boolean,
    val rawMap: Map<String, Any?> = emptyMap(),
)

data class BackupImportAccountExt(
    val jitterMaxMinutes: Int? = null,
    val cooldownMinSec: Int? = null,
    val cooldownMaxSec: Int? = null,
    val autoScanEnabled: Boolean? = null,
    val autoScanIntervalHours: Int? = null,
)

data class BackupImportAttemptMeta(
    val inputMethodUsed: String? = null,
    val errorCode: String? = null,
)

data class BackupAndroidExtensions(
    val account: BackupImportAccountExt? = null,
    val jobSummaryDetails: Map<String, String> = emptyMap(),
    val jobOriginalSlots: Map<String, String> = emptyMap(),
    val attemptMeta: Map<String, BackupImportAttemptMeta> = emptyMap(),
)

data class BackupParsedTemplate(
    val sourceId: String,
    val name: String,
    val rawType: String,
    val type: TemplateType,
    val isImageTemplate: Boolean = (type == TemplateType.IMAGE),
    val content: String,
    val stickerName: String?,
    val stickerLabel: String?,
    val stickerCategory: String?,
    val stickerFallbackIndex: Int?,
    val enabled: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class BackupParsedPoolItem(
    val sourceId: String,
    val templateId: String,
    val content: String,
    val weekdayMask: Int,
    val enabled: Boolean,
    val lastUsedAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class BackupParsedTarget(
    val sourceId: String,
    val accountId: String,
    val platformUserId: String?,
    val platformHandle: String?,
    val platformConversationId: String?,
    val conversationType: String?,
    val nicknameSnapshot: String,
    val templateId: String?,
    val enabled: Boolean,
    val sendTimeHhmm: String?,
    val sparkDays: Int?,
    val sparkKind: String?,
    val sparkLabel: String?,
    val sparkScannedAtEpochMs: Long?,
    val streakDays: Int,
    val lastSentAtEpochMs: Long?,
    val lastConfirmedAtEpochMs: Long?,
    val deletedAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class BackupParsedJob(
    val sourceId: String,
    val accountId: String,
    val targetId: String,
    val templateId: String?,
    val businessDate: String,
    val messageSlotId: String,
    val status: JobStatus,
    val scheduledAtEpochMs: Long?,
    val attemptCount: Int,
    val failureClass: FailureClass?,
    val summaryDetail: String?,
    val confirmedAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class BackupParsedAttempt(
    val sourceId: String,
    val jobId: String,
    val attemptNo: Int,
    val stage: String,
    val status: AttemptStatus,
    val failureClass: FailureClass?,
    val inputMethodUsed: String?,
    val errorCode: String?,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
)

data class ParsedBackup(
    val app: String,
    val schemaVersion: Int,
    val exportedAtEpochMs: Long,
    val accounts: List<BackupAccountCandidate>,
    val templates: List<BackupParsedTemplate>,
    val poolItems: List<BackupParsedPoolItem>,
    val targets: List<BackupParsedTarget>,
    val jobs: List<BackupParsedJob>,
    val attempts: List<BackupParsedAttempt>,
    val androidAccountExt: BackupImportAccountExt? = null,
    val jobSummaryDetails: Map<String, String> = emptyMap(),
    val jobOriginalSlots: Map<String, String> = emptyMap(),
    val attemptMeta: Map<String, BackupImportAttemptMeta> = emptyMap(),
)

data class BackupInspection(
    val parsed: ParsedBackup,
    val accounts: List<BackupAccountCandidate>,
    val templateCount: Int,
    val missingImageTemplateCount: Int = 0,
    val targetCount: Int,
    val jobCount: Int,
    val attemptCount: Int,
    val isZipArchive: Boolean = false,
    val bundledImageCount: Int = 0,
)

data class BackupImportReport(
    val sourceAccountName: String,
    val templatesInserted: Int = 0,
    val templatesUpdated: Int = 0,
    val poolItemsMerged: Int = 0,
    val targetsInserted: Int = 0,
    val targetsUpdated: Int = 0,
    val targetsSkipped: Int = 0,
    val jobsInserted: Int = 0,
    val jobsUpdated: Int = 0,
    val missingImageTemplateCount: Int = 0,
    val restoredImagesCount: Int = 0,
    val skippedProtectedJobs: Int = 0,
    val warnings: List<String> = emptyList(),
)
