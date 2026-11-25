package io.github.oinzc.flamecontractor.core.account

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.github.oinzc.flamecontractor.core.storage.StoredTemplateImage
import io.github.oinzc.flamecontractor.core.storage.TemplateImageStorage
import io.github.oinzc.flamecontractor.data.db.AccountDao
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.DEFAULT_TEXT_TEMPLATE_ID
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
import io.github.oinzc.flamecontractor.data.db.TemplateType
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountManagerTest {

    @Test
    fun createAccountSeedsDefaultTemplatesSelectsActiveAccountAndReschedules() = runTest {
        val db = FakeFlameDatabase(
            initialAccounts = listOf(
                AccountEntity(id = DEFAULT_ACCOUNT_ID, displayName = "默认号", createdAt = 100L, updatedAt = 100L),
            ),
        )
        val activeStore = ActiveAccountStore(initialAccountId = DEFAULT_ACCOUNT_ID)
        val cleanupStore = PendingProfileCleanupStore(FakeSharedPrefsContext())
        val imageStore = FakeTemplateImageStorage()
        var scheduleCalls = 0
        var now = 2_000L

        val manager = AccountManager(
            database = db,
            activeAccountStore = activeStore,
            imageStore = imageStore,
            pendingProfileCleanupStore = cleanupStore,
            isMultiProfileSupported = { true },
            onClearAccountCookies = {},
            onScheduleChanged = { scheduleCalls += 1 },
            clockMillis = { now },
        )

        val created = manager.createAccount("  工作号  ")

        assertEquals("工作号", created.displayName)
        assertEquals(created.id, activeStore.currentAccountId())
        assertEquals(1, scheduleCalls)
        assertTrue(db.transactionCompletedCount >= 1)

        val allAccounts = db.fakeAccountDao.listAll()
        assertEquals(2, allAccounts.size)
        val seededTemplates = db.fakeTemplateDao.listTemplates(created.id)
        assertEquals(4, seededTemplates.size)
        assertEquals(3, seededTemplates.count { it.type == TemplateType.STICKER })
        assertEquals(1, seededTemplates.count { it.type == TemplateType.TEXT })
        assertTrue(seededTemplates.any { it.id == "${created.id}:$DEFAULT_TEXT_TEMPLATE_ID" })
    }

    @Test
    fun createAccountRejectsBlankDuplicateAndUnsupportedMultiProfile() = runTest {
        val db = FakeFlameDatabase(
            initialAccounts = listOf(
                AccountEntity(id = DEFAULT_ACCOUNT_ID, displayName = "主账号"),
            ),
        )
        val activeStore = ActiveAccountStore(DEFAULT_ACCOUNT_ID)
        val cleanupStore = PendingProfileCleanupStore(FakeSharedPrefsContext())
        var multiProfileSupported = true

        val manager = AccountManager(
            database = db,
            activeAccountStore = activeStore,
            imageStore = FakeTemplateImageStorage(),
            pendingProfileCleanupStore = cleanupStore,
            isMultiProfileSupported = { multiProfileSupported },
            onClearAccountCookies = {},
        )

        assertFailsWithMessage("不能为空") {
            manager.createAccount("   ")
        }
        assertFailsWithMessage("已存在同名账号") {
            manager.createAccount(" 主账号 ")
        }

        multiProfileSupported = false
        assertFailsWithMessage("不支持多环境隔离") {
            manager.createAccount("小号")
        }
        assertEquals(1, db.fakeAccountDao.listAll().size)
    }

    @Test
    fun renameAndToggleEnabledPersistUpdatesAndValidateDuplicates() = runTest {
        val db = FakeFlameDatabase(
            initialAccounts = listOf(
                AccountEntity(id = DEFAULT_ACCOUNT_ID, displayName = "主号", enabled = true),
                AccountEntity(id = "acct-2", displayName = "副号", enabled = true),
            ),
        )
        var scheduleCalls = 0
        var now = 5_000L
        val manager = AccountManager(
            database = db,
            activeAccountStore = ActiveAccountStore(DEFAULT_ACCOUNT_ID),
            imageStore = FakeTemplateImageStorage(),
            pendingProfileCleanupStore = PendingProfileCleanupStore(FakeSharedPrefsContext()),
            isMultiProfileSupported = { true },
            onClearAccountCookies = {},
            onScheduleChanged = { scheduleCalls += 1 },
            clockMillis = { now },
        )

        assertFailsWithMessage("已存在同名账号") {
            manager.renameAccount("acct-2", "主号")
        }

        val renamed = manager.renameAccount("acct-2", "工作副号")
        assertEquals("工作副号", renamed.displayName)
        assertEquals("工作副号", db.fakeAccountDao.getAccount("acct-2")?.displayName)
        assertEquals(0, scheduleCalls)

        now = 6_000L
        val disabled = manager.setAccountEnabled("acct-2", false)
        assertFalse(disabled.enabled)
        assertEquals(false, db.fakeAccountDao.getAccount("acct-2")?.enabled)
        assertEquals(1, scheduleCalls)
    }

    @Test
    fun deleteAccountRejectsSingleRemainingAccountOrBusyAccount() = runTest {
        val db = FakeFlameDatabase(
            initialAccounts = listOf(
                AccountEntity(id = DEFAULT_ACCOUNT_ID, displayName = "唯一账号"),
            ),
        )
        var busyAccountId: String? = null
        val manager = AccountManager(
            database = db,
            activeAccountStore = ActiveAccountStore(DEFAULT_ACCOUNT_ID),
            imageStore = FakeTemplateImageStorage(),
            pendingProfileCleanupStore = PendingProfileCleanupStore(FakeSharedPrefsContext()),
            isMultiProfileSupported = { true },
            onClearAccountCookies = {},
            isAccountBusy = { it == busyAccountId },
        )

        assertFailsWithMessage("至少保留 1 个") {
            manager.deleteAccount(DEFAULT_ACCOUNT_ID)
        }

        db.fakeAccountDao.upsert(AccountEntity(id = "acct-2", displayName = "第二账号"))
        busyAccountId = "acct-2"
        assertFailsWithMessage("正在执行后台任务") {
            manager.deleteAccount("acct-2")
        }
        assertNotNull(db.fakeAccountDao.getAccount("acct-2"))
        assertFalse(manager.deleteAccount("missing-id"))
    }

    @Test
    fun deleteAccountCleansAccountDataOrphanImagesCookiesPendingProfileActiveAccountAndReschedules() = runTest {
        val sharedImg = "images/shared.jpg"
        val orphanImg = "images/orphan.png"
        val imageStore = FakeTemplateImageStorage(
            existingPaths = mutableSetOf(sharedImg, orphanImg),
        )
        val db = FakeFlameDatabase(
            initialAccounts = listOf(
                AccountEntity(id = DEFAULT_ACCOUNT_ID, displayName = "默认号", createdAt = 100L),
                AccountEntity(id = "acct-2", displayName = "待删号", createdAt = 200L),
            ),
            initialTemplates = listOf(
                MessageTemplateEntity(
                    id = "tpl-default-shared",
                    accountId = DEFAULT_ACCOUNT_ID,
                    name = "保留图",
                    type = TemplateType.IMAGE,
                    imagePath = sharedImg,
                ),
                MessageTemplateEntity(
                    id = "tpl-2-shared",
                    accountId = "acct-2",
                    name = "复用图",
                    type = TemplateType.IMAGE,
                    imagePath = sharedImg,
                ),
                MessageTemplateEntity(
                    id = "tpl-2-orphan",
                    accountId = "acct-2",
                    name = "独享图",
                    type = TemplateType.IMAGE,
                    imagePath = orphanImg,
                ),
            ),
            initialTargets = listOf(
                ContractTargetEntity(id = "t-default", accountId = DEFAULT_ACCOUNT_ID, nicknameSnapshot = "甲"),
                ContractTargetEntity(id = "t-2", accountId = "acct-2", nicknameSnapshot = "乙"),
            ),
            initialJobs = listOf(
                SendJobEntity(
                    id = "job-default",
                    accountId = DEFAULT_ACCOUNT_ID,
                    targetId = "t-default",
                    businessDate = "2026-11-23",
                    messageSlotId = "slot-1",
                ),
                SendJobEntity(
                    id = "job-2",
                    accountId = "acct-2",
                    targetId = "t-2",
                    businessDate = "2026-11-23",
                    messageSlotId = "slot-1",
                ),
            ),
            initialScanRuns = listOf(
                ScanRunEntity(id = "scan-default", accountId = DEFAULT_ACCOUNT_ID),
                ScanRunEntity(id = "scan-2", accountId = "acct-2"),
            ),
        )

        val activeStore = ActiveAccountStore(initialAccountId = "acct-2")
        val cleanupStore = PendingProfileCleanupStore(FakeSharedPrefsContext())
        val clearedCookies = mutableListOf<String>()
        var scheduleCalls = 0

        val manager = AccountManager(
            database = db,
            activeAccountStore = activeStore,
            imageStore = imageStore,
            pendingProfileCleanupStore = cleanupStore,
            isMultiProfileSupported = { true },
            onClearAccountCookies = { clearedCookies += it },
            onScheduleChanged = { scheduleCalls += 1 },
        )

        val deleted = manager.deleteAccount("acct-2")

        assertTrue(deleted)
        assertNull(db.fakeAccountDao.getAccount("acct-2"))
        assertTrue(db.fakeTemplateDao.listTemplates("acct-2").isEmpty())
        assertTrue(db.fakeTargetDao.listAllTargetsIncludingDeleted("acct-2").isEmpty())
        assertTrue(db.fakeSendJobDao.listAllJobs("acct-2").isEmpty())
        assertTrue(db.fakeScanRunDao.listRecent("acct-2").isEmpty())

        // Default account data preserved
        assertNotNull(db.fakeAccountDao.getAccount(DEFAULT_ACCOUNT_ID))
        assertEquals(1, db.fakeTemplateDao.listTemplates(DEFAULT_ACCOUNT_ID).size)
        assertEquals(1, db.fakeTargetDao.listAllTargetsIncludingDeleted(DEFAULT_ACCOUNT_ID).size)
        assertEquals(1, db.fakeSendJobDao.listAllJobs(DEFAULT_ACCOUNT_ID).size)
        assertEquals(1, db.fakeScanRunDao.listRecent(DEFAULT_ACCOUNT_ID).size)

        // Shared image kept, orphan image deleted
        assertTrue(imageStore.existingPaths.contains(sharedImg))
        assertFalse(imageStore.existingPaths.contains(orphanImg))

        // Cookie clear, pending WebView profile cleanup, active account fallback, and schedule recalculation
        assertEquals(listOf("acct-2"), clearedCookies)
        assertEquals(
            setOf(WebViewProfileRegistry.profileNameForAccount("acct-2")),
            cleanupStore.listPending(),
        )
        assertEquals(DEFAULT_ACCOUNT_ID, activeStore.currentAccountId())
        assertEquals(1, scheduleCalls)
    }

    @Test
    fun deleteDefaultAccountDoesNotEnqueueDefaultProfileForCleanup() = runTest {
        val db = FakeFlameDatabase(
            initialAccounts = listOf(
                AccountEntity(id = DEFAULT_ACCOUNT_ID, displayName = "默认号", createdAt = 100L),
                AccountEntity(id = "acct-2", displayName = "保留号", createdAt = 200L),
            ),
        )
        val activeStore = ActiveAccountStore(initialAccountId = DEFAULT_ACCOUNT_ID)
        val cleanupStore = PendingProfileCleanupStore(FakeSharedPrefsContext())

        val manager = AccountManager(
            database = db,
            activeAccountStore = activeStore,
            imageStore = FakeTemplateImageStorage(),
            pendingProfileCleanupStore = cleanupStore,
            isMultiProfileSupported = { true },
            onClearAccountCookies = {},
        )

        assertTrue(manager.deleteAccount(DEFAULT_ACCOUNT_ID))
        assertEquals("acct-2", activeStore.currentAccountId())
        assertTrue(cleanupStore.listPending().isEmpty())
    }

    @Test
    fun suggestNextDefaultAccountNameAvoidsCollisionsAfterDeletesAndRenames() {
        assertEquals("抖音号 2", AccountManager.suggestNextDefaultAccountName(listOf("默认抖音号")))
        assertEquals("抖音号 3", AccountManager.suggestNextDefaultAccountName(listOf("默认抖音号", "抖音号 2")))
        // 删掉默认号后仅剩「抖音号 2」时，绝对不得再次生成「抖音号 2」
        assertEquals("抖音号 3", AccountManager.suggestNextDefaultAccountName(listOf("抖音号 2")))
        assertEquals(
            "抖音号 3",
            AccountManager.suggestNextDefaultAccountName(listOf("默认抖音号", "抖音号 2", "抖音号 4")),
        )
        assertEquals(
            "抖音号 4",
            AccountManager.suggestNextDefaultAccountName(listOf("抖音号 2", "抖音号 3", "小号")),
        )
    }

    @Test
    fun resolveSyncedDisplayNameFollowsOptionAAndDeduplicatesAcrossAccounts() {
        assertTrue(AccountManager.isDefaultPlaceholderName("默认抖音号"))
        assertTrue(AccountManager.isDefaultPlaceholderName("默认账号"))
        assertTrue(AccountManager.isDefaultPlaceholderName("抖音号 2"))
        assertTrue(AccountManager.isDefaultPlaceholderName("抖音号12"))
        assertFalse(AccountManager.isDefaultPlaceholderName("工作号"))

        // 1. 默认占位名登录后自动改为绑定的抖音昵称
        assertEquals(
            "阿明日常",
            AccountManager.resolveSyncedDisplayNameAfterLogin(
                currentDisplayName = "抖音号 2",
                newDouyinNickname = " 阿明日常 ",
                isSwitchedDouyinAccount = false,
                otherAccountDisplayNames = listOf("默认抖音号"),
            ),
        )

        // 2. 同号重登且用户手动改过备注时，保留自定义备注（策略 A）
        assertEquals(
            "工作副号",
            AccountManager.resolveSyncedDisplayNameAfterLogin(
                currentDisplayName = "工作副号",
                newDouyinNickname = "阿明日常",
                isSwitchedDouyinAccount = false,
                otherAccountDisplayNames = listOf("主号"),
            ),
        )

        // 3. 更换绑定另一个抖音号时，即使原名是自定义备注也同步为新抖音昵称
        assertEquals(
            "新号小橘",
            AccountManager.resolveSyncedDisplayNameAfterLogin(
                currentDisplayName = "工作副号",
                newDouyinNickname = "新号小橘",
                isSwitchedDouyinAccount = true,
                otherAccountDisplayNames = listOf("主号"),
            ),
        )

        // 4. 与其他账号重名时自动追加 (2)/(3) 后缀防撞名
        assertEquals(
            "阿明日常 (2)",
            AccountManager.resolveSyncedDisplayNameAfterLogin(
                currentDisplayName = "抖音号 3",
                newDouyinNickname = "阿明日常",
                isSwitchedDouyinAccount = false,
                otherAccountDisplayNames = listOf("阿明日常"),
            ),
        )
        assertEquals(
            "阿明日常 (3)",
            AccountManager.resolveSyncedDisplayNameAfterLogin(
                currentDisplayName = "抖音号 4",
                newDouyinNickname = "阿明日常",
                isSwitchedDouyinAccount = false,
                otherAccountDisplayNames = listOf("阿明日常", "阿明日常 (2)"),
            ),
        )

        // 5. 未抓到昵称时保留原名
        assertEquals(
            "抖音号 2",
            AccountManager.resolveSyncedDisplayNameAfterLogin(
                currentDisplayName = "抖音号 2",
                newDouyinNickname = "   ",
                isSwitchedDouyinAccount = false,
                otherAccountDisplayNames = emptyList(),
            ),
        )
    }

    private suspend fun assertFailsWithMessage(expectedSubstring: String, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected failure containing '$expectedSubstring', but succeeded")
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            assertTrue("Expected '$expectedSubstring' in '$msg'", msg.contains(expectedSubstring))
        }
    }

    private class FakeTemplateImageStorage(
        val existingPaths: MutableSet<String> = mutableSetOf(),
    ) : TemplateImageStorage {
        override fun resolveExistingFile(storedPath: String?): File? =
            storedPath?.takeIf { it in existingPaths }?.let { File(it) }

        override suspend fun importFromStream(input: InputStream, mimeTypeHint: String?): StoredTemplateImage {
            val path = "images/imported_${existingPaths.size + 1}.jpg"
            existingPaths += path
            return StoredTemplateImage(path, mimeTypeHint ?: "image/jpeg", 128L, 100, 100)
        }

        override fun deleteIfUnreferenced(storedPath: String?, referencedPaths: Set<String>): Boolean {
            val clean = storedPath?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            if (clean in referencedPaths) return false
            return existingPaths.remove(clean)
        }

        override fun contentUriFor(file: File): Uri? = null
    }

    private class FakeSharedPrefsContext : ContextWrapper(null) {
        private val prefs = InMemorySharedPreferences()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)

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
                if (clearAll) data.clear()
                for ((k, v) in pending) {
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeFlameDatabase(
        initialAccounts: List<AccountEntity> = emptyList(),
        initialTemplates: List<MessageTemplateEntity> = emptyList(),
        initialTargets: List<ContractTargetEntity> = emptyList(),
        initialJobs: List<SendJobEntity> = emptyList(),
        initialScanRuns: List<ScanRunEntity> = emptyList(),
    ) : FlameDatabase() {
        val fakeAccountDao = InMemoryAccountDao(initialAccounts)
        val fakeTemplateDao = InMemoryTemplateDao(initialTemplates)
        val fakeTargetDao = InMemoryTargetDao(initialTargets)
        val fakeSendJobDao = InMemorySendJobDao(initialJobs)
        val fakeScanRunDao = InMemoryScanRunDao(initialScanRuns)

        private var txDepth = 0
        var transactionCompletedCount = 0

        override fun accountDao(): AccountDao = fakeAccountDao
        override fun targetDao(): TargetDao = fakeTargetDao
        override fun templateDao(): TemplateDao = fakeTemplateDao
        override fun sendJobDao(): SendJobDao = fakeSendJobDao
        override fun scanRunDao(): ScanRunDao = fakeScanRunDao

        override fun createInvalidationTracker(): InvalidationTracker =
            InvalidationTracker(this, emptyMap(), emptyMap(), "accounts")

        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper =
            throw UnsupportedOperationException("Not used in unit test")

        override fun clearAllTables() {
            fakeScanRunDao.items.value = emptyList()
            fakeSendJobDao.jobs.value = emptyList()
            fakeSendJobDao.attempts.value = emptyList()
            fakeTargetDao.items.value = emptyList()
            fakeTemplateDao.templates.value = emptyList()
            fakeTemplateDao.poolItems.value = emptyList()
            fakeAccountDao.items.value = emptyList()
        }

        override val transactionExecutor: Executor = Executor { it.run() }

        @Suppress("DEPRECATION")
        override fun beginTransaction() { txDepth += 1 }

        @Suppress("DEPRECATION")
        override fun setTransactionSuccessful() {}

        @Suppress("DEPRECATION")
        override fun endTransaction() {
            if (txDepth > 0) txDepth -= 1
            if (txDepth == 0) transactionCompletedCount += 1
        }

        override fun inTransaction(): Boolean = txDepth > 0
    }

    private class InMemoryAccountDao(initial: List<AccountEntity>) : AccountDao {
        val items = MutableStateFlow(initial)
        override fun observeAccount(accountId: String): Flow<AccountEntity?> =
            items.map { list -> list.firstOrNull { it.id == accountId } }
        override suspend fun getAccount(accountId: String): AccountEntity? =
            items.value.firstOrNull { it.id == accountId }
        override fun observeAll(): Flow<List<AccountEntity>> =
            items.map { it.sortedBy(AccountEntity::createdAt) }
        override suspend fun listAll(): List<AccountEntity> =
            items.value.sortedBy(AccountEntity::createdAt)
        override suspend fun deleteById(accountId: String) {
            items.value = items.value.filterNot { it.id == accountId }
        }
        override suspend fun upsert(account: AccountEntity) {
            items.value = items.value.filterNot { it.id == account.id } + account
        }
        override suspend fun update(account: AccountEntity) = upsert(account)
        override suspend fun updateHealthState(accountId: String, status: AccountStatus, authFailCount: Int, failureStreak: Int, updatedAt: Long) {
            items.value = items.value.map {
                if (it.id == accountId) it.copy(status = status, authFailCount = authFailCount, failureStreak = failureStreak, updatedAt = updatedAt) else it
            }
        }
        override suspend fun updateLoginIdentity(accountId: String, selfUid: String?, douyinNickname: String?, verifiedAt: Long) {
            items.value = items.value.map {
                if (it.id == accountId) it.copy(selfUid = selfUid, douyinNickname = douyinNickname, lastVerifiedAt = verifiedAt, updatedAt = verifiedAt) else it
            }
        }
        override suspend fun updateLastSuccessAt(accountId: String, successAt: Long) {
            items.value = items.value.map {
                if (it.id == accountId) it.copy(lastSuccessAt = successAt, updatedAt = successAt) else it
            }
        }
        override suspend fun updateLastAutoScanAttemptAt(accountId: String, attemptAt: Long, updatedAt: Long) {
            items.value = items.value.map {
                if (it.id == accountId) it.copy(lastAutoScanAttemptAt = attemptAt, updatedAt = updatedAt) else it
            }
        }
    }

    private class InMemoryTargetDao(initial: List<ContractTargetEntity>) : TargetDao {
        val items = MutableStateFlow(initial)
        override fun observeActiveTargets(accountId: String): Flow<List<ContractTargetEntity>> =
            items.map { list -> list.filter { it.accountId == accountId && it.deletedAt == null }.sortedBy(ContractTargetEntity::createdAt) }
        override suspend fun listActiveTargets(accountId: String): List<ContractTargetEntity> =
            items.value.filter { it.accountId == accountId && it.deletedAt == null }.sortedBy(ContractTargetEntity::createdAt)
        override suspend fun listAllTargetsIncludingDeleted(accountId: String): List<ContractTargetEntity> =
            items.value.filter { it.accountId == accountId }.sortedBy(ContractTargetEntity::createdAt)
        override suspend fun listEnabledTargets(accountId: String): List<ContractTargetEntity> =
            items.value.filter { it.accountId == accountId && it.enabled && it.deletedAt == null }.sortedBy(ContractTargetEntity::createdAt)
        override suspend fun getById(id: String): ContractTargetEntity? = items.value.firstOrNull { it.id == id }
        override suspend fun findByNicknameIncludingDeleted(accountId: String, nickname: String): ContractTargetEntity? =
            items.value.firstOrNull { it.accountId == accountId && it.nicknameSnapshot == nickname }
        override suspend fun findByPlatformUserIdIncludingDeleted(accountId: String, platformUserId: String): ContractTargetEntity? =
            items.value.firstOrNull { it.accountId == accountId && it.platformUserId == platformUserId }
        override suspend fun findByPlatformConversationIdIncludingDeleted(accountId: String, platformConversationId: String): ContractTargetEntity? =
            items.value.firstOrNull { it.accountId == accountId && it.platformConversationId == platformConversationId }
        override suspend fun countActiveTargets(accountId: String): Int = listActiveTargets(accountId).size
        override suspend fun countActiveByTemplate(templateId: String): Int =
            items.value.count { it.templateId == templateId && it.deletedAt == null }
        override suspend fun upsert(target: ContractTargetEntity) {
            items.value = items.value.filterNot { it.id == target.id } + target
        }
        override suspend fun insert(target: ContractTargetEntity) = upsert(target)
        override suspend fun update(target: ContractTargetEntity) = upsert(target)
        override suspend fun softDelete(id: String, deletedAt: Long) {
            items.value = items.value.map { if (it.id == id) it.copy(deletedAt = deletedAt, enabled = false, updatedAt = deletedAt) else it }
        }
        override suspend fun reviveTarget(
            id: String,
            nicknameSnapshot: String,
            platformUserId: String?,
            platformHandle: String?,
            platformConversationId: String?,
            conversationType: String?,
            enabled: Boolean,
            updatedAt: Long,
        ) {
            items.value = items.value.map {
                if (it.id == id) {
                    it.copy(
                        deletedAt = null,
                        enabled = enabled,
                        nicknameSnapshot = nicknameSnapshot,
                        platformUserId = platformUserId,
                        platformHandle = platformHandle,
                        platformConversationId = platformConversationId,
                        conversationType = conversationType,
                        updatedAt = updatedAt,
                    )
                } else it
            }
        }
        override suspend fun updateOfficialSparkSnapshot(id: String, sparkDays: Int?, sparkKind: String?, sparkLabel: String?, scannedAt: Long) {}
        override suspend fun recordSendConfirmed(id: String, confirmedAt: Long) {}
        override suspend fun touchSendConfirmedTimestampsOnly(id: String, confirmedAt: Long) {}
        override suspend fun updateStreakDays(id: String, streakDays: Int, updatedAt: Long) {}
        override suspend fun unbindIdentitiesAndDisableAll(accountId: String, updatedAt: Long) {}
        override suspend fun deleteAllByAccount(accountId: String) {
            items.value = items.value.filterNot { it.accountId == accountId }
        }
    }

    private class InMemoryTemplateDao(initial: List<MessageTemplateEntity>) : TemplateDao {
        val templates = MutableStateFlow(initial)
        val poolItems = MutableStateFlow<List<TemplatePoolItemEntity>>(emptyList())
        override fun observeTemplates(accountId: String): Flow<List<MessageTemplateEntity>> =
            templates.map { list -> list.filter { it.accountId == accountId }.sortedBy(MessageTemplateEntity::createdAt) }
        override suspend fun listTemplates(accountId: String): List<MessageTemplateEntity> =
            templates.value.filter { it.accountId == accountId }.sortedBy(MessageTemplateEntity::createdAt)
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
        override suspend fun deleteAllByAccount(accountId: String) {
            templates.value = templates.value.filterNot { it.accountId == accountId }
        }
        override suspend fun listAllReferencedImagePaths(): List<String> =
            templates.value.mapNotNull { it.imagePath?.takeIf(String::isNotBlank) }
        override fun observePoolItems(templateId: String): Flow<List<TemplatePoolItemEntity>> =
            poolItems.map { list -> list.filter { it.templateId == templateId } }
        override suspend fun listPoolItems(templateId: String): List<TemplatePoolItemEntity> =
            poolItems.value.filter { it.templateId == templateId }
        override suspend fun listEnabledPoolItems(templateId: String): List<TemplatePoolItemEntity> =
            poolItems.value.filter { it.templateId == templateId && it.enabled }
        override suspend fun upsertPoolItem(item: TemplatePoolItemEntity) {
            poolItems.value = poolItems.value.filterNot { it.id == item.id } + item
        }
        override suspend fun updatePoolItem(item: TemplatePoolItemEntity) = upsertPoolItem(item)
        override suspend fun deletePoolItemById(id: String) {
            poolItems.value = poolItems.value.filterNot { it.id == id }
        }
        override suspend fun markPoolItemUsed(id: String, nowEpochMs: Long) {}
    }

    private class InMemorySendJobDao(initialJobs: List<SendJobEntity>) : SendJobDao {
        val jobs = MutableStateFlow(initialJobs)
        val attempts = MutableStateFlow<List<SendAttemptEntity>>(emptyList())
        override fun observeJobsForDate(accountId: String, businessDate: String): Flow<List<SendJobEntity>> =
            jobs.map { list -> list.filter { it.accountId == accountId && it.businessDate == businessDate } }
        override suspend fun listJobsForDate(accountId: String, businessDate: String): List<SendJobEntity> =
            jobs.value.filter { it.accountId == accountId && it.businessDate == businessDate }
        override suspend fun listJobsForTargetDate(accountId: String, targetId: String, businessDate: String): List<SendJobEntity> =
            jobs.value.filter { it.accountId == accountId && it.targetId == targetId && it.businessDate == businessDate }
        override suspend fun getJobBySlot(accountId: String, targetId: String, businessDate: String, slotId: String): SendJobEntity? =
            jobs.value.firstOrNull { it.accountId == accountId && it.targetId == targetId && it.businessDate == businessDate && it.messageSlotId == slotId }
        override suspend fun getJobById(id: String): SendJobEntity? = jobs.value.firstOrNull { it.id == id }
        override fun observeJobsByStatus(accountId: String, status: JobStatus): Flow<List<SendJobEntity>> =
            jobs.map { list -> list.filter { it.accountId == accountId && it.status == status } }
        override suspend fun listJobsByStatuses(accountId: String, statuses: List<JobStatus>): List<SendJobEntity> =
            jobs.value.filter { it.accountId == accountId && it.status in statuses }
        override fun observeRecentJobs(accountId: String, limit: Int): Flow<List<SendJobEntity>> =
            jobs.map { list -> list.filter { it.accountId == accountId }.take(limit) }
        override fun observeJobsSince(accountId: String, sinceDate: String): Flow<List<SendJobEntity>> =
            jobs.map { list -> list.filter { it.accountId == accountId && it.businessDate >= sinceDate } }
        override suspend fun listAllJobs(accountId: String): List<SendJobEntity> =
            jobs.value.filter { it.accountId == accountId }
        override suspend fun listAllAttemptsForAccount(accountId: String): List<SendAttemptEntity> {
            val jobIds = jobs.value.filter { it.accountId == accountId }.map { it.id }.toSet()
            return attempts.value.filter { it.jobId in jobIds }
        }
        override suspend fun upsertJob(job: SendJobEntity) {
            jobs.value = jobs.value.filterNot { it.id == job.id } + job
        }
        override suspend fun upsertAttempt(attempt: SendAttemptEntity) {
            attempts.value = attempts.value.filterNot { it.id == attempt.id } + attempt
        }
        override suspend fun insertIgnore(job: SendJobEntity): Long = 1L
        override suspend fun updateJob(job: SendJobEntity) = upsertJob(job)
        override suspend fun updateJobOutcome(id: String, status: JobStatus, failureClass: FailureClass?, summaryDetail: String?, confirmedAt: Long?, updatedAt: Long) {}
        override suspend fun deleteAllByAccount(accountId: String) {
            val removedIds = jobs.value.filter { it.accountId == accountId }.map { it.id }.toSet()
            jobs.value = jobs.value.filterNot { it.accountId == accountId }
            attempts.value = attempts.value.filterNot { it.jobId in removedIds }
        }
        override suspend fun insertAttempt(attempt: SendAttemptEntity) = upsertAttempt(attempt)
        override suspend fun updateAttempt(attempt: SendAttemptEntity) = upsertAttempt(attempt)
        override suspend fun listAttemptsForJob(jobId: String): List<SendAttemptEntity> =
            attempts.value.filter { it.jobId == jobId }
        override fun observeAttemptsForJob(jobId: String): Flow<List<SendAttemptEntity>> =
            attempts.map { list -> list.filter { it.jobId == jobId } }
        override suspend fun listSucceededJobCountsByTarget(status: JobStatus): List<TargetSucceededJobCounts> =
            jobs.value.filter { it.status == status }
                .groupBy { it.targetId }
                .map { (tid, list) ->
                    TargetSucceededJobCounts(
                        targetId = tid,
                        totalSucceeded = list.size,
                        distinctBusinessDates = list.map { it.businessDate }.distinct().size,
                    )
                }
    }

    private class InMemoryScanRunDao(initial: List<ScanRunEntity>) : ScanRunDao {
        val items = MutableStateFlow(initial)
        override fun observeLatestScanRun(accountId: String): Flow<ScanRunEntity?> =
            items.map { list -> list.filter { it.accountId == accountId }.maxByOrNull(ScanRunEntity::startedAt) }
        override suspend fun getLatestScanRun(accountId: String): ScanRunEntity? =
            items.value.filter { it.accountId == accountId }.maxByOrNull(ScanRunEntity::startedAt)
        override suspend fun getLatestCompletedScanRun(accountId: String): ScanRunEntity? =
            items.value.filter { it.accountId == accountId && it.status == ScanRunStatus.COMPLETED }.maxByOrNull { it.finishedAt ?: it.startedAt }
        override suspend fun getLatestAutoScanRun(accountId: String): ScanRunEntity? =
            items.value.filter { it.accountId == accountId && it.source == "automatic" }.maxByOrNull(ScanRunEntity::startedAt)
        override fun observeLatestAutoScanRun(accountId: String): Flow<ScanRunEntity?> =
            items.map { list -> list.filter { it.accountId == accountId && it.source == "automatic" }.maxByOrNull(ScanRunEntity::startedAt) }
        override suspend fun getById(id: String): ScanRunEntity? = items.value.firstOrNull { it.id == id }
        override suspend fun listRecent(accountId: String, limit: Int): List<ScanRunEntity> =
            items.value.filter { it.accountId == accountId }.sortedByDescending(ScanRunEntity::startedAt).take(limit)
        override suspend fun insert(scanRun: ScanRunEntity) {
            items.value = items.value + scanRun
        }
        override suspend fun update(scanRun: ScanRunEntity) {
            items.value = items.value.filterNot { it.id == scanRun.id } + scanRun
        }
        override suspend fun deleteAllByAccount(accountId: String) {
            items.value = items.value.filterNot { it.accountId == accountId }
        }
    }
}
