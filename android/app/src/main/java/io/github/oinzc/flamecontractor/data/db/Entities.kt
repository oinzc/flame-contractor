package io.github.oinzc.flamecontractor.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.ZoneId
import java.util.UUID

const val DEFAULT_ACCOUNT_ID = "default"
const val DEFAULT_STICKER_TEMPLATE_ID = "default-sticker-bixin"
const val DEFAULT_STICKER_MORNING_TEMPLATE_ID = "default-sticker-zaoshanghao"
const val DEFAULT_STICKER_SPARK_TEMPLATE_ID = "default-sticker-xuhuohua"
const val DEFAULT_TEXT_TEMPLATE_ID = "default-text-daily"

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey
    val id: String = DEFAULT_ACCOUNT_ID,
    @ColumnInfo(name = "display_name")
    val displayName: String = "默认抖音号",
    val status: AccountStatus = AccountStatus.PENDING_LOGIN,
    val enabled: Boolean = true,
    val timezone: String = ZoneId.systemDefault().id,
    @ColumnInfo(name = "send_window_start")
    val sendWindowStart: String = "20:00",
    @ColumnInfo(name = "send_window_end")
    val sendWindowEnd: String = "22:00",
    @ColumnInfo(name = "send_time_hhmm")
    val sendTimeHhmm: String? = null,
    @ColumnInfo(name = "jitter_max_minutes")
    val jitterMaxMinutes: Int = 15,
    @ColumnInfo(name = "cooldown_min_sec")
    val cooldownMinSec: Int = 3,
    @ColumnInfo(name = "cooldown_max_sec")
    val cooldownMaxSec: Int = 8,
    @ColumnInfo(name = "auth_fail_count")
    val authFailCount: Int = 0,
    @ColumnInfo(name = "failure_streak")
    val failureStreak: Int = 0,
    @ColumnInfo(name = "self_uid")
    val selfUid: String? = null,
    @ColumnInfo(name = "douyin_nickname")
    val douyinNickname: String? = null,
    @ColumnInfo(name = "last_verified_at")
    val lastVerifiedAt: Long? = null,
    @ColumnInfo(name = "last_success_at")
    val lastSuccessAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "auto_scan_enabled")
    val autoScanEnabled: Boolean = true,
    @ColumnInfo(name = "auto_scan_interval_hours")
    val autoScanIntervalHours: Int = 24,
    @ColumnInfo(name = "last_auto_scan_attempt_at")
    val lastAutoScanAttemptAt: Long? = null,
)

@Entity(
    tableName = "contract_targets",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MessageTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["template_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["account_id"]),
        Index(value = ["template_id"]),
        Index(
            value = ["account_id", "nickname_snapshot"],
            unique = true,
            name = "uq_target_account_nickname",
        ),
        Index(
            value = ["account_id", "platform_user_id"],
            unique = true,
            name = "uq_target_account_platform_user",
        ),
        Index(
            value = ["account_id", "platform_conversation_id"],
            unique = true,
            name = "uq_target_account_platform_conversation",
        ),
    ],
)
data class ContractTargetEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "account_id")
    val accountId: String = DEFAULT_ACCOUNT_ID,
    @ColumnInfo(name = "nickname_snapshot")
    val nicknameSnapshot: String,
    @ColumnInfo(name = "platform_user_id")
    val platformUserId: String? = null,
    @ColumnInfo(name = "platform_handle")
    val platformHandle: String? = null,
    @ColumnInfo(name = "platform_conversation_id")
    val platformConversationId: String? = null,
    @ColumnInfo(name = "conversation_type")
    val conversationType: String? = null,
    @ColumnInfo(name = "template_id")
    val templateId: String? = null,
    val enabled: Boolean = true,
    @ColumnInfo(name = "send_time_hhmm")
    val sendTimeHhmm: String? = null,
    @ColumnInfo(name = "spark_days")
    val sparkDays: Int? = null,
    @ColumnInfo(name = "spark_kind")
    val sparkKind: String? = null,
    @ColumnInfo(name = "spark_label")
    val sparkLabel: String? = null,
    @ColumnInfo(name = "spark_scanned_at")
    val sparkScannedAt: Long? = null,
    @ColumnInfo(name = "streak_days")
    val streakDays: Int = 0,
    @ColumnInfo(name = "last_sent_at")
    val lastSentAt: Long? = null,
    @ColumnInfo(name = "last_confirmed_at")
    val lastConfirmedAt: Long? = null,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "message_templates",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["account_id"]),
        Index(
            value = ["account_id", "name"],
            unique = true,
            name = "uq_template_account_name",
        ),
    ],
)
data class MessageTemplateEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "account_id")
    val accountId: String = DEFAULT_ACCOUNT_ID,
    val name: String,
    val type: TemplateType = TemplateType.TEXT,
    val content: String = "",
    @ColumnInfo(name = "sticker_name")
    val stickerName: String? = null,
    @ColumnInfo(name = "sticker_label")
    val stickerLabel: String? = null,
    @ColumnInfo(name = "sticker_category")
    val stickerCategory: String? = null,
    @ColumnInfo(name = "sticker_fallback_index")
    val stickerFallbackIndex: Int? = null,
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "image_path")
    val imagePath: String? = null,
)

@Entity(
    tableName = "template_pool_items",
    foreignKeys = [
        ForeignKey(
            entity = MessageTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["template_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["template_id"]),
    ],
)
data class TemplatePoolItemEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "template_id")
    val templateId: String,
    val content: String,
    @ColumnInfo(name = "weekday_mask")
    val weekdayMask: Int = 0,
    val enabled: Boolean = true,
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "send_jobs",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ContractTargetEntity::class,
            parentColumns = ["id"],
            childColumns = ["target_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MessageTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["template_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["account_id"]),
        Index(value = ["target_id"]),
        Index(value = ["template_id"]),
        Index(value = ["business_date"]),
        Index(value = ["status"]),
        Index(
            value = ["account_id", "target_id", "business_date", "message_slot_id"],
            unique = true,
            name = "uq_send_job_daily_slot",
        ),
    ],
)
data class SendJobEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "account_id")
    val accountId: String = DEFAULT_ACCOUNT_ID,
    @ColumnInfo(name = "target_id")
    val targetId: String,
    @ColumnInfo(name = "template_id")
    val templateId: String? = null,
    @ColumnInfo(name = "business_date")
    val businessDate: String,
    @ColumnInfo(name = "message_slot_id")
    val messageSlotId: String,
    val status: JobStatus = JobStatus.SCHEDULED,
    @ColumnInfo(name = "scheduled_at")
    val scheduledAt: Long? = null,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "failure_class")
    val failureClass: FailureClass? = null,
    @ColumnInfo(name = "summary_detail")
    val summaryDetail: String? = null,
    @ColumnInfo(name = "confirmed_at")
    val confirmedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "send_attempts",
    foreignKeys = [
        ForeignKey(
            entity = SendJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["job_id"]),
        Index(
            value = ["job_id", "attempt_no"],
            unique = true,
            name = "uq_attempt_job_no",
        ),
    ],
)
data class SendAttemptEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "job_id")
    val jobId: String,
    @ColumnInfo(name = "attempt_no")
    val attemptNo: Int,
    val stage: String,
    val status: AttemptStatus = AttemptStatus.RUNNING,
    @ColumnInfo(name = "failure_class")
    val failureClass: FailureClass? = null,
    @ColumnInfo(name = "input_method_used")
    val inputMethodUsed: String? = null,
    @ColumnInfo(name = "error_code")
    val errorCode: String? = null,
    @ColumnInfo(name = "started_at")
    val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "finished_at")
    val finishedAt: Long? = null,
)

@Entity(
    tableName = "scan_runs",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["account_id"]),
        Index(value = ["started_at"]),
    ],
)
data class ScanRunEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "account_id")
    val accountId: String = DEFAULT_ACCOUNT_ID,
    val source: String = "manual",
    val status: ScanRunStatus = ScanRunStatus.RUNNING,
    @ColumnInfo(name = "discovered_count")
    val discoveredCount: Int = 0,
    @ColumnInfo(name = "spark_count")
    val sparkCount: Int = 0,
    @ColumnInfo(name = "reconciliation_json")
    val reconciliationJson: String = "{}",
    @ColumnInfo(name = "started_at")
    val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "finished_at")
    val finishedAt: Long? = null,
    @ColumnInfo(name = "alert_summary")
    val alertSummary: String? = null,
)
