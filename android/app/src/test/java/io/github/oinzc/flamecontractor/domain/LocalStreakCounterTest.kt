package io.github.oinzc.flamecontractor.domain

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.github.oinzc.flamecontractor.data.db.AccountDao
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunDao
import io.github.oinzc.flamecontractor.data.db.ScanRunEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunStatus
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobDao
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.data.db.TargetDao
import io.github.oinzc.flamecontractor.data.db.TargetSucceededJobCounts
import io.github.oinzc.flamecontractor.data.db.TemplateDao
import io.github.oinzc.flamecontractor.data.db.TemplatePoolItemEntity
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStreakCounterTest {

    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `shouldIncrementStreakForConfirmation allows once per local calendar day`() {
        val day1Morning = Instant.parse("2026-11-22T17:10:00Z").toEpochMilli() // 2026-11-23 01:10 +08:00
        val day1Evening = Instant.parse("2026-11-23T14:55:00Z").toEpochMilli() // 2026-11-23 22:55 +08:00
        val day2Morning = Instant.parse("2026-11-23T16:05:00Z").toEpochMilli() // 2026-11-24 00:05 +08:00

        assertTrue(
            LocalStreakCounter.shouldIncrementStreakForConfirmation(
                previousConfirmedAtEpochMs = null,
                newConfirmedAtEpochMs = day1Morning,
                zoneId = shanghaiZone,
            ),
        )
        assertFalse(
            LocalStreakCounter.shouldIncrementStreakForConfirmation(
                previousConfirmedAtEpochMs = day1Morning,
                newConfirmedAtEpochMs = day1Evening,
                zoneId = shanghaiZone,
            ),
        )
        assertTrue(
            LocalStreakCounter.shouldIncrementStreakForConfirmation(
                previousConfirmedAtEpochMs = day1Evening,
                newConfirmedAtEpochMs = day2Morning,
                zoneId = shanghaiZone,
            ),
        )
    }

    @Test
    fun `reconcileInflatedStreakDays heals same-day duplicates while preserving backup baseline`() {
        // Freshly imported target sent 4 times on 1 day: 4 -> 1
        assertEquals(
            1,
            LocalStreakCounter.reconcileInflatedStreakDays(
                currentStreakDays = 4,
                totalSucceededJobs = 4,
                distinctSucceededBusinessDates = 1,
            ),
        )

        // Backup baseline 20 + 4 sends on 1 day: 24 -> 21
        assertEquals(
            21,
            LocalStreakCounter.reconcileInflatedStreakDays(
                currentStreakDays = 24,
                totalSucceededJobs = 4,
                distinctSucceededBusinessDates = 1,
            ),
        )

        // Never drops below distinct succeeded business dates
        assertEquals(
            3,
            LocalStreakCounter.reconcileInflatedStreakDays(
                currentStreakDays = 2,
                totalSucceededJobs = 6,
                distinctSucceededBusinessDates = 3,
            ),
        )

        // Normal target with 1 send per day stays untouched
        assertEquals(
            15,
            LocalStreakCounter.reconcileInflatedStreakDays(
                currentStreakDays = 15,
                totalSucceededJobs = 5,
                distinctSucceededBusinessDates = 5,
            ),
        )
    }

    @Test
    fun `recordSendConfirmedOncePerDay increments once across four same-day sends and again next day`() = runTest {
        val db = FakeStreakFlameDatabase(
            initialAccounts = listOf(AccountEntity(id = DEFAULT_ACCOUNT_ID, timezone = shanghaiZone.id)),
            initialTargets = listOf(
                ContractTargetEntity(
                    id = "t-1",
                    accountId = DEFAULT_ACCOUNT_ID,
                    nicknameSnapshot = "测试好友甲",
                    streakDays = 0,
                ),
            ),
        )
        val dao = db.targetDao()
        val sameDayTimes = listOf(
            Instant.parse("2026-11-23T01:00:00Z").toEpochMilli(),
            Instant.parse("2026-11-23T04:00:00Z").toEpochMilli(),
            Instant.parse("2026-11-23T08:00:00Z").toEpochMilli(),
            Instant.parse("2026-11-23T13:30:00Z").toEpochMilli(),
        )

        val increments = sameDayTimes.map { ts ->
            dao.recordSendConfirmedOncePerDay("t-1", ts, shanghaiZone)
        }
        assertEquals(listOf(true, false, false, false), increments)

        val afterDay1 = dao.getById("t-1")!!
        assertEquals(1, afterDay1.streakDays)
        assertEquals(sameDayTimes.last(), afterDay1.lastConfirmedAt)

        val nextDayTs = Instant.parse("2026-11-24T01:00:00Z").toEpochMilli()
        assertTrue(dao.recordSendConfirmedOncePerDay("t-1", nextDayTs, shanghaiZone))
        val afterDay2 = dao.getById("t-1")!!
        assertEquals(2, afterDay2.streakDays)
        assertEquals(nextDayTs, afterDay2.lastConfirmedAt)
    }

    @Test
    fun `startup reconciliation heals inflated streaks once and stays idempotent on subsequent launches`() = runTest {
        val db = FakeStreakFlameDatabase(
            initialAccounts = listOf(AccountEntity(id = DEFAULT_ACCOUNT_ID, timezone = shanghaiZone.id)),
            initialTargets = listOf(
                ContractTargetEntity(
                    id = "t-fresh-inflated",
                    accountId = DEFAULT_ACCOUNT_ID,
                    nicknameSnapshot = "测试好友一",
                    streakDays = 4,
                ),
                ContractTargetEntity(
                    id = "t-backup-inflated",
                    accountId = DEFAULT_ACCOUNT_ID,
                    nicknameSnapshot = "测试好友二",
                    streakDays = 24,
                ),
            ),
            initialJobs = listOf(
                succeededJob("j-1", "t-fresh-inflated", "2026-11-23", "daily"),
                succeededJob("j-2", "t-fresh-inflated", "2026-11-23", "manual:1"),
                succeededJob("j-3", "t-fresh-inflated", "2026-11-23", "manual:2"),
                succeededJob("j-4", "t-fresh-inflated", "2026-11-23", "manual:3"),
                succeededJob("j-5", "t-backup-inflated", "2026-11-23", "daily"),
                succeededJob("j-6", "t-backup-inflated", "2026-11-23", "manual:1"),
                succeededJob("j-7", "t-backup-inflated", "2026-11-23", "manual:2"),
                succeededJob("j-8", "t-backup-inflated", "2026-11-23", "manual:3"),
            ),
        )
        val context = FakePrefsContext()

        val firstRunHealed = LocalStreakCounter.runStartupSameDayStreakReconciliationOnce(
            context = context,
            database = db,
            nowEpochMs = 9_000L,
        )
        assertEquals(2, firstRunHealed)
        assertEquals(1, db.targetDao().getById("t-fresh-inflated")?.streakDays)
        assertEquals(21, db.targetDao().getById("t-backup-inflated")?.streakDays)

        // Second launch must NOT subtract duplicate sends again on backup baseline target
        val secondRunHealed = LocalStreakCounter.runStartupSameDayStreakReconciliationOnce(
            context = context,
            database = db,
            nowEpochMs = 10_000L,
        )
        assertEquals(0, secondRunHealed)
        assertEquals(1, db.targetDao().getById("t-fresh-inflated")?.streakDays)
        assertEquals(21, db.targetDao().getById("t-backup-inflated")?.streakDays)
    }

    private fun succeededJob(
        id: String,
        targetId: String,
        businessDate: String,
        slotId: String,
    ): SendJobEntity = SendJobEntity(
        id = id,
        accountId = DEFAULT_ACCOUNT_ID,
        targetId = targetId,
        businessDate = businessDate,
        messageSlotId = slotId,
        status = JobStatus.SUCCEEDED,
    )

    private class FakePrefsContext : ContextWrapper(null) {
        private val prefs = InMemoryPrefs()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class InMemoryPrefs : SharedPreferences {
        private val map = linkedMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearAll = false
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = values?.toSet()
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = null
            }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) map.clear()
                for ((k, v) in pending) {
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeStreakFlameDatabase(
        initialAccounts: List<AccountEntity> = emptyList(),
        initialTargets: List<ContractTargetEntity> = emptyList(),
        initialJobs: List<SendJobEntity> = emptyList(),
    ) : FlameDatabase() {
        private val accountDaoImpl = FakeAccountDao(initialAccounts)
        private val targetDaoImpl = FakeTargetDao(initialTargets)
        private val sendJobDaoImpl = FakeSendJobDao(initialJobs)
        private var txDepth = 0

        override fun accountDao(): AccountDao = accountDaoImpl
        override fun targetDao(): TargetDao = targetDaoImpl
        override fun templateDao(): TemplateDao = EmptyTemplateDao
        override fun sendJobDao(): SendJobDao = sendJobDaoImpl
        override fun scanRunDao(): ScanRunDao = EmptyScanRunDao

        override fun createInvalidationTracker(): InvalidationTracker =
            InvalidationTracker(this, emptyMap(), emptyMap(), "accounts")

        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper =
            throw UnsupportedOperationException("Not used in unit test")

        override fun clearAllTables() {}
        override val transactionExecutor: Executor = Executor { it.run() }

        @Suppress("DEPRECATION")
        override fun beginTransaction() { txDepth += 1 }

        @Suppress("DEPRECATION")
        override fun setTransactionSuccessful() {}

        @Suppress("DEPRECATION")
        override fun endTransaction() { if (txDepth > 0) txDepth -= 1 }

        override fun inTransaction(): Boolean = txDepth > 0
    }

    private class FakeAccountDao(initial: List<AccountEntity>) : AccountDao {
        private val state = MutableStateFlow(initial)
        override fun observeAccount(accountId: String): Flow<AccountEntity?> =
            state.map { list -> list.firstOrNull { it.id == accountId } }
        override suspend fun getAccount(accountId: String): AccountEntity? =
            state.value.firstOrNull { it.id == accountId }
        override fun observeAll(): Flow<List<AccountEntity>> = state
        override suspend fun listAll(): List<AccountEntity> = state.value
        override suspend fun deleteById(accountId: String) {}
        override suspend fun upsert(account: AccountEntity) {}
        override suspend fun update(account: AccountEntity) {}
        override suspend fun updateHealthState(accountId: String, status: AccountStatus, authFailCount: Int, failureStreak: Int, updatedAt: Long) {}
        override suspend fun updateLoginIdentity(accountId: String, selfUid: String?, douyinNickname: String?, verifiedAt: Long) {}
        override suspend fun updateLastSuccessAt(accountId: String, successAt: Long) {}
        override suspend fun updateLastAutoScanAttemptAt(accountId: String, attemptAt: Long, updatedAt: Long) {}
    }

    private class FakeTargetDao(initial: List<ContractTargetEntity>) : TargetDao {
        private val items = LinkedHashMap<String, ContractTargetEntity>().apply {
            initial.forEach { put(it.id, it) }
        }
        override fun observeActiveTargets(accountId: String): Flow<List<ContractTargetEntity>> = emptyFlow()
        override suspend fun listActiveTargets(accountId: String): List<ContractTargetEntity> =
            items.values.filter { it.accountId == accountId && it.deletedAt == null }
        override suspend fun listAllTargetsIncludingDeleted(accountId: String): List<ContractTargetEntity> =
            items.values.filter { it.accountId == accountId }
        override suspend fun listEnabledTargets(accountId: String): List<ContractTargetEntity> =
            items.values.filter { it.accountId == accountId && it.enabled && it.deletedAt == null }
        override suspend fun getById(id: String): ContractTargetEntity? = items[id]
        override suspend fun findByNicknameIncludingDeleted(accountId: String, nickname: String): ContractTargetEntity? = null
        override suspend fun findByPlatformUserIdIncludingDeleted(accountId: String, platformUserId: String): ContractTargetEntity? = null
        override suspend fun findByPlatformConversationIdIncludingDeleted(accountId: String, platformConversationId: String): ContractTargetEntity? = null
        override suspend fun countActiveTargets(accountId: String): Int = 0
        override suspend fun countActiveByTemplate(templateId: String): Int = 0
        override suspend fun upsert(target: ContractTargetEntity) { items[target.id] = target }
        override suspend fun insert(target: ContractTargetEntity) { items[target.id] = target }
        override suspend fun update(target: ContractTargetEntity) { items[target.id] = target }
        override suspend fun softDelete(id: String, deletedAt: Long) {}
        override suspend fun reviveTarget(id: String, nicknameSnapshot: String, platformUserId: String?, platformHandle: String?, platformConversationId: String?, conversationType: String?, enabled: Boolean, updatedAt: Long) {}
        override suspend fun updateOfficialSparkSnapshot(id: String, sparkDays: Int?, sparkKind: String?, sparkLabel: String?, scannedAt: Long) {}
        override suspend fun recordSendConfirmed(id: String, confirmedAt: Long) {
            items[id]?.let { current ->
                items[id] = current.copy(
                    streakDays = current.streakDays + 1,
                    lastSentAt = confirmedAt,
                    lastConfirmedAt = confirmedAt,
                    updatedAt = confirmedAt,
                )
            }
        }
        override suspend fun touchSendConfirmedTimestampsOnly(id: String, confirmedAt: Long) {
            items[id]?.let { current ->
                items[id] = current.copy(
                    lastSentAt = confirmedAt,
                    lastConfirmedAt = confirmedAt,
                    updatedAt = confirmedAt,
                )
            }
        }
        override suspend fun updateStreakDays(id: String, streakDays: Int, updatedAt: Long) {
            items[id]?.let { current ->
                items[id] = current.copy(streakDays = streakDays, updatedAt = updatedAt)
            }
        }
        override suspend fun unbindIdentitiesAndDisableAll(accountId: String, updatedAt: Long) {}
        override suspend fun deleteAllByAccount(accountId: String) {}
    }

    private class FakeSendJobDao(initial: List<SendJobEntity>) : SendJobDao {
        private val jobs = initial.toMutableList()
        override fun observeJobsForDate(accountId: String, businessDate: String): Flow<List<SendJobEntity>> = emptyFlow()
        override suspend fun listJobsForDate(accountId: String, businessDate: String): List<SendJobEntity> = emptyList()
        override suspend fun listJobsForTargetDate(accountId: String, targetId: String, businessDate: String): List<SendJobEntity> = emptyList()
        override suspend fun getJobBySlot(accountId: String, targetId: String, businessDate: String, slotId: String): SendJobEntity? = null
        override suspend fun getJobById(id: String): SendJobEntity? = null
        override fun observeJobsByStatus(accountId: String, status: JobStatus): Flow<List<SendJobEntity>> = emptyFlow()
        override suspend fun listJobsByStatuses(accountId: String, statuses: List<JobStatus>): List<SendJobEntity> = emptyList()
        override fun observeRecentJobs(accountId: String, limit: Int): Flow<List<SendJobEntity>> = emptyFlow()
        override fun observeJobsSince(accountId: String, sinceDate: String): Flow<List<SendJobEntity>> = emptyFlow()
        override suspend fun listAllJobs(accountId: String): List<SendJobEntity> = jobs
        override suspend fun listAllAttemptsForAccount(accountId: String): List<SendAttemptEntity> = emptyList()
        override suspend fun upsertJob(job: SendJobEntity) {}
        override suspend fun upsertAttempt(attempt: SendAttemptEntity) {}
        override suspend fun insertIgnore(job: SendJobEntity): Long = 1L
        override suspend fun updateJob(job: SendJobEntity) {}
        override suspend fun updateJobOutcome(id: String, status: JobStatus, failureClass: FailureClass?, summaryDetail: String?, confirmedAt: Long?, updatedAt: Long) {}
        override suspend fun deleteAllByAccount(accountId: String) {}
        override suspend fun insertAttempt(attempt: SendAttemptEntity) {}
        override suspend fun updateAttempt(attempt: SendAttemptEntity) {}
        override suspend fun listAttemptsForJob(jobId: String): List<SendAttemptEntity> = emptyList()
        override fun observeAttemptsForJob(jobId: String): Flow<List<SendAttemptEntity>> = emptyFlow()
        override suspend fun listSucceededJobCountsByTarget(status: JobStatus): List<TargetSucceededJobCounts> =
            jobs.filter { it.status == status }
                .groupBy { it.targetId }
                .map { (tid, list) ->
                    TargetSucceededJobCounts(
                        targetId = tid,
                        totalSucceeded = list.size,
                        distinctBusinessDates = list.map { it.businessDate }.distinct().size,
                    )
                }
    }

    private object EmptyTemplateDao : TemplateDao {
        override fun observeTemplates(accountId: String): Flow<List<MessageTemplateEntity>> = emptyFlow()
        override suspend fun listTemplates(accountId: String): List<MessageTemplateEntity> = emptyList()
        override suspend fun getTemplateById(id: String): MessageTemplateEntity? = null
        override suspend fun countTemplates(accountId: String): Int = 0
        override suspend fun upsertTemplate(template: MessageTemplateEntity) {}
        override suspend fun updateTemplate(template: MessageTemplateEntity) {}
        override suspend fun deleteTemplateById(id: String) {}
        override suspend fun deleteAllByAccount(accountId: String) {}
        override suspend fun listAllReferencedImagePaths(): List<String> = emptyList()
        override fun observePoolItems(templateId: String): Flow<List<TemplatePoolItemEntity>> = emptyFlow()
        override suspend fun listPoolItems(templateId: String): List<TemplatePoolItemEntity> = emptyList()
        override suspend fun listEnabledPoolItems(templateId: String): List<TemplatePoolItemEntity> = emptyList()
        override suspend fun upsertPoolItem(item: TemplatePoolItemEntity) {}
        override suspend fun updatePoolItem(item: TemplatePoolItemEntity) {}
        override suspend fun deletePoolItemById(id: String) {}
        override suspend fun markPoolItemUsed(id: String, nowEpochMs: Long) {}
    }

    private object EmptyScanRunDao : ScanRunDao {
        override fun observeLatestScanRun(accountId: String): Flow<ScanRunEntity?> = emptyFlow()
        override suspend fun getLatestScanRun(accountId: String): ScanRunEntity? = null
        override suspend fun getLatestCompletedScanRun(accountId: String): ScanRunEntity? = null
        override suspend fun getLatestAutoScanRun(accountId: String): ScanRunEntity? = null
        override fun observeLatestAutoScanRun(accountId: String): Flow<ScanRunEntity?> = emptyFlow()
        override suspend fun getById(id: String): ScanRunEntity? = null
        override suspend fun listRecent(accountId: String, limit: Int): List<ScanRunEntity> = emptyList()
        override suspend fun insert(scanRun: ScanRunEntity) {}
        override suspend fun update(scanRun: ScanRunEntity) {}
        override suspend fun deleteAllByAccount(accountId: String) {}
    }
}
