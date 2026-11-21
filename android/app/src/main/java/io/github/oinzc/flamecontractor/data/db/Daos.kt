package io.github.oinzc.flamecontractor.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts WHERE id = :accountId LIMIT 1")
    fun observeAccount(accountId: String = DEFAULT_ACCOUNT_ID): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts WHERE id = :accountId LIMIT 1")
    suspend fun getAccount(accountId: String = DEFAULT_ACCOUNT_ID): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY created_at ASC")
    suspend fun listAll(): List<AccountEntity>

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Update
    suspend fun update(account: AccountEntity)

    @Query(
        """
        UPDATE accounts
        SET status = :status,
            auth_fail_count = :authFailCount,
            failure_streak = :failureStreak,
            updated_at = :updatedAt
        WHERE id = :accountId
        """,
    )
    suspend fun updateHealthState(
        accountId: String,
        status: AccountStatus,
        authFailCount: Int,
        failureStreak: Int,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE accounts
        SET self_uid = :selfUid,
            douyin_nickname = :douyinNickname,
            last_verified_at = :verifiedAt,
            updated_at = :verifiedAt
        WHERE id = :accountId
        """,
    )
    suspend fun updateLoginIdentity(
        accountId: String,
        selfUid: String?,
        douyinNickname: String?,
        verifiedAt: Long,
    )

    @Query("UPDATE accounts SET last_success_at = :successAt, updated_at = :successAt WHERE id = :accountId")
    suspend fun updateLastSuccessAt(accountId: String, successAt: Long)
}

@Dao
interface TargetDao {

    @Query(
        """
        SELECT * FROM contract_targets
        WHERE account_id = :accountId AND deleted_at IS NULL
        ORDER BY created_at ASC
        """,
    )
    fun observeActiveTargets(accountId: String = DEFAULT_ACCOUNT_ID): Flow<List<ContractTargetEntity>>

    @Query(
        """
        SELECT * FROM contract_targets
        WHERE account_id = :accountId AND deleted_at IS NULL
        ORDER BY created_at ASC
        """,
    )
    suspend fun listActiveTargets(accountId: String = DEFAULT_ACCOUNT_ID): List<ContractTargetEntity>

    @Query(
        """
        SELECT * FROM contract_targets
        WHERE account_id = :accountId AND enabled = 1 AND deleted_at IS NULL
        ORDER BY created_at ASC
        """,
    )
    suspend fun listEnabledTargets(accountId: String = DEFAULT_ACCOUNT_ID): List<ContractTargetEntity>

    @Query("SELECT * FROM contract_targets WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ContractTargetEntity?

    @Query(
        """
        SELECT * FROM contract_targets
        WHERE account_id = :accountId AND nickname_snapshot = :nickname
        LIMIT 1
        """,
    )
    suspend fun findByNicknameIncludingDeleted(
        accountId: String,
        nickname: String,
    ): ContractTargetEntity?

    @Query(
        """
        SELECT * FROM contract_targets
        WHERE account_id = :accountId AND platform_user_id = :platformUserId
        LIMIT 1
        """,
    )
    suspend fun findByPlatformUserIdIncludingDeleted(
        accountId: String,
        platformUserId: String,
    ): ContractTargetEntity?

    @Query(
        """
        SELECT * FROM contract_targets
        WHERE account_id = :accountId AND platform_conversation_id = :platformConversationId
        LIMIT 1
        """,
    )
    suspend fun findByPlatformConversationIdIncludingDeleted(
        accountId: String,
        platformConversationId: String,
    ): ContractTargetEntity?

    @Query("SELECT COUNT(*) FROM contract_targets WHERE account_id = :accountId AND deleted_at IS NULL")
    suspend fun countActiveTargets(accountId: String = DEFAULT_ACCOUNT_ID): Int

    @Query("SELECT COUNT(*) FROM contract_targets WHERE template_id = :templateId AND deleted_at IS NULL")
    suspend fun countActiveByTemplate(templateId: String): Int

    @Upsert
    suspend fun upsert(target: ContractTargetEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(target: ContractTargetEntity)

    @Update
    suspend fun update(target: ContractTargetEntity)

    @Query(
        """
        UPDATE contract_targets
        SET deleted_at = :deletedAt,
            enabled = 0,
            updated_at = :deletedAt
        WHERE id = :id
        """,
    )
    suspend fun softDelete(id: String, deletedAt: Long)

    @Query(
        """
        UPDATE contract_targets
        SET deleted_at = NULL,
            enabled = :enabled,
            nickname_snapshot = :nicknameSnapshot,
            platform_user_id = :platformUserId,
            platform_handle = :platformHandle,
            platform_conversation_id = :platformConversationId,
            conversation_type = :conversationType,
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun reviveTarget(
        id: String,
        nicknameSnapshot: String,
        platformUserId: String?,
        platformHandle: String?,
        platformConversationId: String?,
        conversationType: String?,
        enabled: Boolean,
        updatedAt: Long,
    )

    /**
     * 仅更新抖音官方火花快照，严禁修改本机维护的 streak_days。
     */
    @Query(
        """
        UPDATE contract_targets
        SET spark_days = :sparkDays,
            spark_kind = :sparkKind,
            spark_label = :sparkLabel,
            spark_scanned_at = :scannedAt,
            updated_at = :scannedAt
        WHERE id = :id
        """,
    )
    suspend fun updateOfficialSparkSnapshot(
        id: String,
        sparkDays: Int?,
        sparkKind: String?,
        sparkLabel: String?,
        scannedAt: Long,
    )

    @Query(
        """
        UPDATE contract_targets
        SET streak_days = streak_days + 1,
            last_sent_at = :confirmedAt,
            last_confirmed_at = :confirmedAt,
            updated_at = :confirmedAt
        WHERE id = :id
        """,
    )
    suspend fun recordSendConfirmed(id: String, confirmedAt: Long)

    @Query(
        """
        UPDATE contract_targets
        SET platform_user_id = NULL,
            platform_handle = NULL,
            platform_conversation_id = NULL,
            enabled = 0,
            updated_at = :updatedAt
        WHERE account_id = :accountId AND deleted_at IS NULL
        """,
    )
    suspend fun unbindIdentitiesAndDisableAll(accountId: String, updatedAt: Long)

    @Query("DELETE FROM contract_targets WHERE account_id = :accountId")
    suspend fun deleteAllByAccount(accountId: String)
}

@Dao
interface TemplateDao {

    @Query("SELECT * FROM message_templates WHERE account_id = :accountId ORDER BY created_at ASC")
    fun observeTemplates(accountId: String = DEFAULT_ACCOUNT_ID): Flow<List<MessageTemplateEntity>>

    @Query("SELECT * FROM message_templates WHERE account_id = :accountId ORDER BY created_at ASC")
    suspend fun listTemplates(accountId: String = DEFAULT_ACCOUNT_ID): List<MessageTemplateEntity>

    @Query("SELECT * FROM message_templates WHERE id = :id LIMIT 1")
    suspend fun getTemplateById(id: String): MessageTemplateEntity?

    @Query("SELECT COUNT(*) FROM message_templates WHERE account_id = :accountId")
    suspend fun countTemplates(accountId: String = DEFAULT_ACCOUNT_ID): Int

    @Upsert
    suspend fun upsertTemplate(template: MessageTemplateEntity)

    @Update
    suspend fun updateTemplate(template: MessageTemplateEntity)

    @Query("DELETE FROM message_templates WHERE id = :id")
    suspend fun deleteTemplateById(id: String)

    @Query("SELECT * FROM template_pool_items WHERE template_id = :templateId ORDER BY created_at ASC, id ASC")
    fun observePoolItems(templateId: String): Flow<List<TemplatePoolItemEntity>>

    @Query("SELECT * FROM template_pool_items WHERE template_id = :templateId ORDER BY created_at ASC, id ASC")
    suspend fun listPoolItems(templateId: String): List<TemplatePoolItemEntity>

    @Query("SELECT * FROM template_pool_items WHERE template_id = :templateId AND enabled = 1")
    suspend fun listEnabledPoolItems(templateId: String): List<TemplatePoolItemEntity>

    @Upsert
    suspend fun upsertPoolItem(item: TemplatePoolItemEntity)

    @Update
    suspend fun updatePoolItem(item: TemplatePoolItemEntity)

    @Query("DELETE FROM template_pool_items WHERE id = :id")
    suspend fun deletePoolItemById(id: String)

    @Query("UPDATE template_pool_items SET last_used_at = :nowEpochMs, updated_at = :nowEpochMs WHERE id = :id")
    suspend fun markPoolItemUsed(id: String, nowEpochMs: Long)
}

@Dao
interface SendJobDao {

    @Query(
        """
        SELECT * FROM send_jobs
        WHERE account_id = :accountId AND business_date = :businessDate
        ORDER BY created_at DESC
        """,
    )
    fun observeJobsForDate(
        accountId: String,
        businessDate: String,
    ): Flow<List<SendJobEntity>>

    @Query(
        """
        SELECT * FROM send_jobs
        WHERE account_id = :accountId AND business_date = :businessDate
        ORDER BY created_at ASC
        """,
    )
    suspend fun listJobsForDate(
        accountId: String,
        businessDate: String,
    ): List<SendJobEntity>

    @Query(
        """
        SELECT * FROM send_jobs
        WHERE account_id = :accountId
          AND target_id = :targetId
          AND business_date = :businessDate
        ORDER BY created_at ASC
        """,
    )
    suspend fun listJobsForTargetDate(
        accountId: String,
        targetId: String,
        businessDate: String,
    ): List<SendJobEntity>

    @Query(
        """
        SELECT * FROM send_jobs
        WHERE account_id = :accountId
          AND target_id = :targetId
          AND business_date = :businessDate
          AND message_slot_id = :slotId
        LIMIT 1
        """,
    )
    suspend fun getJobBySlot(
        accountId: String,
        targetId: String,
        businessDate: String,
        slotId: String,
    ): SendJobEntity?

    @Query("SELECT * FROM send_jobs WHERE id = :id LIMIT 1")
    suspend fun getJobById(id: String): SendJobEntity?

    @Query(
        """
        SELECT * FROM send_jobs
        WHERE account_id = :accountId AND status = :status
        ORDER BY updated_at DESC
        """,
    )
    fun observeJobsByStatus(
        accountId: String,
        status: JobStatus,
    ): Flow<List<SendJobEntity>>

    @Query(
        """
        SELECT * FROM send_jobs
        WHERE account_id = :accountId AND status IN (:statuses)
        ORDER BY updated_at ASC
        """,
    )
    suspend fun listJobsByStatuses(
        accountId: String,
        statuses: List<JobStatus>,
    ): List<SendJobEntity>

    @Query("SELECT * FROM send_jobs WHERE account_id = :accountId ORDER BY created_at DESC LIMIT :limit")
    fun observeRecentJobs(
        accountId: String = DEFAULT_ACCOUNT_ID,
        limit: Int = 50,
    ): Flow<List<SendJobEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(job: SendJobEntity): Long

    @Update
    suspend fun updateJob(job: SendJobEntity)

    @Query(
        """
        UPDATE send_jobs
        SET status = :status,
            failure_class = :failureClass,
            summary_detail = :summaryDetail,
            confirmed_at = :confirmedAt,
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateJobOutcome(
        id: String,
        status: JobStatus,
        failureClass: FailureClass?,
        summaryDetail: String?,
        confirmedAt: Long?,
        updatedAt: Long,
    )

    @Query("DELETE FROM send_jobs WHERE account_id = :accountId")
    suspend fun deleteAllByAccount(accountId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(attempt: SendAttemptEntity)

    @Update
    suspend fun updateAttempt(attempt: SendAttemptEntity)

    @Query("SELECT * FROM send_attempts WHERE job_id = :jobId ORDER BY attempt_no ASC")
    suspend fun listAttemptsForJob(jobId: String): List<SendAttemptEntity>

    @Query("SELECT * FROM send_attempts WHERE job_id = :jobId ORDER BY attempt_no ASC")
    fun observeAttemptsForJob(jobId: String): Flow<List<SendAttemptEntity>>
}

@Dao
interface ScanRunDao {

    @Query("SELECT * FROM scan_runs WHERE account_id = :accountId ORDER BY started_at DESC LIMIT 1")
    fun observeLatestScanRun(accountId: String = DEFAULT_ACCOUNT_ID): Flow<ScanRunEntity?>

    @Query("SELECT * FROM scan_runs WHERE account_id = :accountId ORDER BY started_at DESC LIMIT 1")
    suspend fun getLatestScanRun(accountId: String = DEFAULT_ACCOUNT_ID): ScanRunEntity?

    @Query("SELECT * FROM scan_runs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ScanRunEntity?

    @Query("SELECT * FROM scan_runs WHERE account_id = :accountId ORDER BY started_at DESC LIMIT :limit")
    suspend fun listRecent(
        accountId: String = DEFAULT_ACCOUNT_ID,
        limit: Int = 20,
    ): List<ScanRunEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(scanRun: ScanRunEntity)

    @Update
    suspend fun update(scanRun: ScanRunEntity)
}
