package io.github.oinzc.flamecontractor.data.db

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlameDatabaseMigrationTest {

    @Test
    fun entityDefaultsMatchVersion3Schema() {
        val account = AccountEntity()
        assertTrue(account.autoScanEnabled)
        assertEquals(24, account.autoScanIntervalHours)
        assertNull(account.lastAutoScanAttemptAt)

        val scanRun = ScanRunEntity()
        assertNull(scanRun.alertSummary)
    }

    @Test
    fun migration2To3StatementsAndMetadataAreExact() {
        assertEquals(
            listOf(
                "ALTER TABLE accounts ADD COLUMN auto_scan_enabled INTEGER NOT NULL DEFAULT 1",
                "ALTER TABLE accounts ADD COLUMN auto_scan_interval_hours INTEGER NOT NULL DEFAULT 24",
                "ALTER TABLE accounts ADD COLUMN last_auto_scan_attempt_at INTEGER",
                "ALTER TABLE scan_runs ADD COLUMN alert_summary TEXT",
            ),
            FlameDatabase.MIGRATION_2_3_STATEMENTS,
        )
        assertEquals(2, FlameDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, FlameDatabase.MIGRATION_2_3.endVersion)
    }

    @Test
    fun migration2To3ExecutesAllAlterStatementsInOrder() {
        val recorder = RecordingSqliteDb()
        FlameDatabase.MIGRATION_2_3.migrate(recorder.db)

        assertEquals(4, recorder.calls.size)
        assertEquals(
            FlameDatabase.MIGRATION_2_3_STATEMENTS,
            recorder.calls.map { it.sql },
        )
        recorder.calls.forEach { call ->
            assertNull(call.bindArgs)
        }
    }

    @Test
    fun seedInitialDataBindsAll22AccountColumns() {
        val recorder = RecordingSqliteDb()
        val fixedNow = 1_700_000_000_000L
        FlameDatabase.seedInitialData(
            db = recorder.db,
            nowEpochMs = fixedNow,
            defaultTimezone = "Asia/Shanghai",
        )

        val accountInsert = recorder.calls.firstOrNull {
            it.sql.contains("INSERT OR IGNORE INTO accounts")
        } ?: error("Expected INSERT OR IGNORE INTO accounts statement")

        val columnsSection = accountInsert.sql
            .substringAfter("INSERT OR IGNORE INTO accounts (")
            .substringBefore(") VALUES (")
        val columns = columnsSection.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val placeholderCount = accountInsert.sql.substringAfter("VALUES (").count { it == '?' }
        val bindings = accountInsert.bindArgs ?: error("Expected bind arguments for account insert")

        assertEquals(22, columns.size)
        assertEquals(22, placeholderCount)
        assertEquals(22, bindings.size)

        val colToValue = columns.zip(bindings).toMap()
        assertEquals(1, colToValue["auto_scan_enabled"])
        assertEquals(24, colToValue["auto_scan_interval_hours"])
        assertNull(colToValue["last_auto_scan_attempt_at"])
    }

    @Test
    fun templateTypeImageAndMigration3To4AreExact() {
        assertEquals(TemplateType.IMAGE, TemplateType.fromWireValue("image"))
        val imgTemplate = MessageTemplateEntity(
            name = "早安图",
            type = TemplateType.IMAGE,
        )
        assertNull(imgTemplate.imagePath)

        assertEquals(
            listOf("ALTER TABLE message_templates ADD COLUMN image_path TEXT"),
            FlameDatabase.MIGRATION_3_4_STATEMENTS,
        )
        assertEquals(3, FlameDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, FlameDatabase.MIGRATION_3_4.endVersion)

        val recorder = RecordingSqliteDb()
        FlameDatabase.MIGRATION_3_4.migrate(recorder.db)
        assertEquals(1, recorder.calls.size)
        assertEquals(
            FlameDatabase.MIGRATION_3_4_STATEMENTS,
            recorder.calls.map { it.sql },
        )
        assertNull(recorder.calls.single().bindArgs)
    }

    @Test
    fun seedInitialDataSeedsSparkFirstWithoutLegacyDailyTextTemplate() {
        val recorder = RecordingSqliteDb()
        FlameDatabase.seedInitialData(
            db = recorder.db,
            nowEpochMs = 1_700_000_000_000L,
            defaultTimezone = "Asia/Shanghai",
        )

        val templateCalls = recorder.calls.filter {
            it.sql.contains("INSERT OR IGNORE INTO message_templates")
        }
        assertEquals(3, templateCalls.size)
        assertEquals(
            listOf(
                DEFAULT_STICKER_SPARK_TEMPLATE_ID,
                DEFAULT_STICKER_TEMPLATE_ID,
                DEFAULT_STICKER_MORNING_TEMPLATE_ID,
            ),
            templateCalls.map { it.bindArgs?.get(0) },
        )
        assertEquals(listOf("续火花", "比心", "早上好"), templateCalls.map { it.bindArgs?.get(2) })
        assertTrue(templateCalls.none { it.bindArgs?.get(0) == DEFAULT_TEXT_TEMPLATE_ID })
    }

    @Test
    fun migrateLegacyDefaultTextTemplateOnceMigratesUntouchedLegacyAndPreservesCustomized() = runTest {
        val db = FakeMigrationFlameDatabase(
            initialAccounts = listOf(
                AccountEntity(id = DEFAULT_ACCOUNT_ID, displayName = "主号"),
                AccountEntity(id = "acct-2", displayName = "副号未改"),
                AccountEntity(id = "acct-3", displayName = "副号已改文案"),
                AccountEntity(id = "acct-4", displayName = "副号同名自定义"),
            ),
            initialTemplates = listOf(
                // 1) 主号：旧版未改动默认文本模板，且缺少「续火花」表情模板（应自动补种并迁移好友）
                MessageTemplateEntity(
                    id = DEFAULT_TEXT_TEMPLATE_ID,
                    accountId = DEFAULT_ACCOUNT_ID,
                    name = "每日续火",
                    type = TemplateType.TEXT,
                    content = "每日续火打卡 🔥",
                ),
                MessageTemplateEntity(
                    id = DEFAULT_STICKER_TEMPLATE_ID,
                    accountId = DEFAULT_ACCOUNT_ID,
                    name = "比心",
                    type = TemplateType.STICKER,
                    stickerName = "比心",
                    stickerLabel = "比心",
                ),
                // 2) acct-2：已有续火花模板 + 旧版未改动默认文本模板
                MessageTemplateEntity(
                    id = "acct-2:$DEFAULT_STICKER_SPARK_TEMPLATE_ID",
                    accountId = "acct-2",
                    name = "续火花",
                    type = TemplateType.STICKER,
                    stickerName = "续火花",
                    stickerLabel = "续火花",
                ),
                MessageTemplateEntity(
                    id = "acct-2:$DEFAULT_TEXT_TEMPLATE_ID",
                    accountId = "acct-2",
                    name = "每日续火",
                    type = TemplateType.TEXT,
                    content = "  每日续火打卡 🔥  ",
                ),
                // 3) acct-3：用户改过默认文本文案 -> 绝不可误删
                MessageTemplateEntity(
                    id = "acct-3:$DEFAULT_TEXT_TEMPLATE_ID",
                    accountId = "acct-3",
                    name = "每日续火",
                    type = TemplateType.TEXT,
                    content = "早安呀宝子，今天继续续火！",
                ),
                // 4) acct-4：用户手动自建同名模板（非默认 ID）-> 绝不可误删
                MessageTemplateEntity(
                    id = "custom-uuid-text-1",
                    accountId = "acct-4",
                    name = "每日续火",
                    type = TemplateType.TEXT,
                    content = "每日续火打卡 🔥",
                ),
            ),
            initialTargets = listOf(
                ContractTargetEntity(
                    id = "t-default-1",
                    accountId = DEFAULT_ACCOUNT_ID,
                    nicknameSnapshot = "主号好友A",
                    templateId = DEFAULT_TEXT_TEMPLATE_ID,
                ),
                ContractTargetEntity(
                    id = "t-default-other",
                    accountId = DEFAULT_ACCOUNT_ID,
                    nicknameSnapshot = "主号好友B",
                    templateId = DEFAULT_STICKER_TEMPLATE_ID,
                ),
                ContractTargetEntity(
                    id = "t-acct2-1",
                    accountId = "acct-2",
                    nicknameSnapshot = "副号好友A",
                    templateId = "acct-2:$DEFAULT_TEXT_TEMPLATE_ID",
                ),
                ContractTargetEntity(
                    id = "t-acct3-1",
                    accountId = "acct-3",
                    nicknameSnapshot = "自定义文案好友",
                    templateId = "acct-3:$DEFAULT_TEXT_TEMPLATE_ID",
                ),
                ContractTargetEntity(
                    id = "t-acct4-1",
                    accountId = "acct-4",
                    nicknameSnapshot = "同名自定义好友",
                    templateId = "custom-uuid-text-1",
                ),
            ),
        )
        val context = FakePrefsContext()

        val firstMigrated = FlameDatabase.migrateLegacyDefaultTextTemplateOnce(
            context = context,
            database = db,
            nowEpochMs = 99_000L,
        )
        assertEquals(2, firstMigrated)

        // 主号：旧文本模板被删，续火花表情自动补种，指向旧模板的好友迁移至续火花，其他好友不动
        assertNull(db.templateDao().getTemplateById(DEFAULT_TEXT_TEMPLATE_ID))
        val seededSpark = db.templateDao().getTemplateById(DEFAULT_STICKER_SPARK_TEMPLATE_ID)
        assertNotNull(seededSpark)
        assertEquals(TemplateType.STICKER, seededSpark?.type)
        assertEquals("续火花", seededSpark?.stickerName)
        assertEquals(DEFAULT_STICKER_SPARK_TEMPLATE_ID, db.targetDao().getById("t-default-1")?.templateId)
        assertEquals(DEFAULT_STICKER_TEMPLATE_ID, db.targetDao().getById("t-default-other")?.templateId)

        // acct-2：复用已有续火花模板并删旧默认文本模板
        assertNull(db.templateDao().getTemplateById("acct-2:$DEFAULT_TEXT_TEMPLATE_ID"))
        assertEquals("acct-2:$DEFAULT_STICKER_SPARK_TEMPLATE_ID", db.targetDao().getById("t-acct2-1")?.templateId)

        // acct-3 与 acct-4：用户改动或自建模板必须原样保留
        assertNotNull(db.templateDao().getTemplateById("acct-3:$DEFAULT_TEXT_TEMPLATE_ID"))
        assertEquals("acct-3:$DEFAULT_TEXT_TEMPLATE_ID", db.targetDao().getById("t-acct3-1")?.templateId)
        assertNotNull(db.templateDao().getTemplateById("custom-uuid-text-1"))
        assertEquals("custom-uuid-text-1", db.targetDao().getById("t-acct4-1")?.templateId)

        // 二次调用必须幂等跳过（即使后续手动插回一条也不再重复执行）
        val secondMigrated = FlameDatabase.migrateLegacyDefaultTextTemplateOnce(
            context = context,
            database = db,
            nowEpochMs = 100_000L,
        )
        assertEquals(0, secondMigrated)
    }

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
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }
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

    private class FakeMigrationFlameDatabase(
        initialAccounts: List<AccountEntity>,
        initialTemplates: List<MessageTemplateEntity>,
        initialTargets: List<ContractTargetEntity>,
    ) : FlameDatabase() {
        private val accounts = MutableStateFlow(initialAccounts)
        private val templates = MutableStateFlow(initialTemplates)
        private val targets = MutableStateFlow(initialTargets)
        private var txDepth = 0

        private val accountDaoImpl = object : AccountDao {
            override fun observeAccount(accountId: String): Flow<AccountEntity?> = emptyFlow()
            override suspend fun getAccount(accountId: String): AccountEntity? = accounts.value.firstOrNull { it.id == accountId }
            override fun observeAll(): Flow<List<AccountEntity>> = accounts
            override suspend fun listAll(): List<AccountEntity> = accounts.value
            override suspend fun deleteById(accountId: String) {}
            override suspend fun upsert(account: AccountEntity) {}
            override suspend fun update(account: AccountEntity) {}
            override suspend fun updateHealthState(accountId: String, status: AccountStatus, authFailCount: Int, failureStreak: Int, updatedAt: Long) {}
            override suspend fun updateLoginIdentity(accountId: String, selfUid: String?, douyinNickname: String?, verifiedAt: Long) {}
            override suspend fun updateLastSuccessAt(accountId: String, successAt: Long) {}
            override suspend fun updateLastAutoScanAttemptAt(accountId: String, attemptAt: Long, updatedAt: Long) {}
        }

        private val targetDaoImpl = object : TargetDao {
            override fun observeActiveTargets(accountId: String): Flow<List<ContractTargetEntity>> = emptyFlow()
            override suspend fun listActiveTargets(accountId: String): List<ContractTargetEntity> =
                targets.value.filter { it.accountId == accountId && it.deletedAt == null }
            override suspend fun listAllTargetsIncludingDeleted(accountId: String): List<ContractTargetEntity> =
                targets.value.filter { it.accountId == accountId }
            override suspend fun listEnabledTargets(accountId: String): List<ContractTargetEntity> =
                targets.value.filter { it.accountId == accountId && it.enabled && it.deletedAt == null }
            override suspend fun getById(id: String): ContractTargetEntity? = targets.value.firstOrNull { it.id == id }
            override suspend fun findByNicknameIncludingDeleted(accountId: String, nickname: String): ContractTargetEntity? = null
            override suspend fun findByPlatformUserIdIncludingDeleted(accountId: String, platformUserId: String): ContractTargetEntity? = null
            override suspend fun findByPlatformConversationIdIncludingDeleted(accountId: String, platformConversationId: String): ContractTargetEntity? = null
            override suspend fun countActiveTargets(accountId: String): Int = 0
            override suspend fun countActiveByTemplate(templateId: String): Int = 0
            override suspend fun upsert(target: ContractTargetEntity) {
                targets.value = targets.value.filterNot { it.id == target.id } + target
            }
            override suspend fun insert(target: ContractTargetEntity) = upsert(target)
            override suspend fun update(target: ContractTargetEntity) = upsert(target)
            override suspend fun softDelete(id: String, deletedAt: Long) {}
            override suspend fun reviveTarget(id: String, nicknameSnapshot: String, platformUserId: String?, platformHandle: String?, platformConversationId: String?, conversationType: String?, enabled: Boolean, updatedAt: Long) {}
            override suspend fun updateOfficialSparkSnapshot(id: String, sparkDays: Int?, sparkKind: String?, sparkLabel: String?, scannedAt: Long) {}
            override suspend fun recordSendConfirmed(id: String, confirmedAt: Long) {}
            override suspend fun touchSendConfirmedTimestampsOnly(id: String, confirmedAt: Long) {}
            override suspend fun updateStreakDays(id: String, streakDays: Int, updatedAt: Long) {}
            override suspend fun unbindIdentitiesAndDisableAll(accountId: String, updatedAt: Long) {}
            override suspend fun deleteAllByAccount(accountId: String) {}
        }

        private val templateDaoImpl = object : TemplateDao {
            override fun observeTemplates(accountId: String): Flow<List<MessageTemplateEntity>> =
                templates.map { list -> list.filter { it.accountId == accountId } }
            override suspend fun listTemplates(accountId: String): List<MessageTemplateEntity> =
                templates.value.filter { it.accountId == accountId }
            override suspend fun getTemplateById(id: String): MessageTemplateEntity? =
                templates.value.firstOrNull { it.id == id }
            override suspend fun countTemplates(accountId: String): Int = listTemplates(accountId).size
            override suspend fun upsertTemplate(template: MessageTemplateEntity) {
                templates.value = templates.value.filterNot { it.id == template.id } + template
            }
            override suspend fun updateTemplate(template: MessageTemplateEntity) = upsertTemplate(template)
            override suspend fun deleteTemplateById(id: String) {
                templates.value = templates.value.filterNot { it.id == id }
            }
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

        private val emptySendJobDao = object : SendJobDao {
            override fun observeJobsForDate(accountId: String, businessDate: String): Flow<List<SendJobEntity>> = emptyFlow()
            override suspend fun listJobsForDate(accountId: String, businessDate: String): List<SendJobEntity> = emptyList()
            override suspend fun listJobsForTargetDate(accountId: String, targetId: String, businessDate: String): List<SendJobEntity> = emptyList()
            override suspend fun getJobBySlot(accountId: String, targetId: String, businessDate: String, slotId: String): SendJobEntity? = null
            override suspend fun getJobById(id: String): SendJobEntity? = null
            override fun observeJobsByStatus(accountId: String, status: JobStatus): Flow<List<SendJobEntity>> = emptyFlow()
            override suspend fun listJobsByStatuses(accountId: String, statuses: List<JobStatus>): List<SendJobEntity> = emptyList()
            override fun observeRecentJobs(accountId: String, limit: Int): Flow<List<SendJobEntity>> = emptyFlow()
            override fun observeJobsSince(accountId: String, sinceDate: String): Flow<List<SendJobEntity>> = emptyFlow()
            override suspend fun listAllJobs(accountId: String): List<SendJobEntity> = emptyList()
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
            override suspend fun listSucceededJobCountsByTarget(status: JobStatus): List<TargetSucceededJobCounts> = emptyList()
        }

        private val emptyScanRunDao = object : ScanRunDao {
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

        override fun accountDao(): AccountDao = accountDaoImpl
        override fun targetDao(): TargetDao = targetDaoImpl
        override fun templateDao(): TemplateDao = templateDaoImpl
        override fun sendJobDao(): SendJobDao = emptySendJobDao
        override fun scanRunDao(): ScanRunDao = emptyScanRunDao
        override fun createInvalidationTracker(): InvalidationTracker = InvalidationTracker(this, emptyMap(), emptyMap(), "accounts")
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

    private class RecordingSqliteDb {
        data class ExecCall(
            val sql: String,
            val bindArgs: List<Any?>? = null,
        )

        val calls = mutableListOf<ExecCall>()

        val db: SupportSQLiteDatabase = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    val sql = args[0] as String
                    val bindArgs = if (args.size >= 2 && args[1] is Array<*>) {
                        (args[1] as Array<*>).toList()
                    } else {
                        null
                    }
                    calls += ExecCall(sql = sql, bindArgs = bindArgs)
                    Unit
                }
                "toString" -> "RecordingSqliteDb"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> null
            }
        } as SupportSQLiteDatabase
    }
}
