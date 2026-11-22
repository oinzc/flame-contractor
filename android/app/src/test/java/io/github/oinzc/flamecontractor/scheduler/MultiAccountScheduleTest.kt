package io.github.oinzc.flamecontractor.scheduler

import io.github.oinzc.flamecontractor.app.FlameApplication
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.core.notify.UrgentCategory
import io.github.oinzc.flamecontractor.data.db.AccountDao
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.ScanRunDao
import io.github.oinzc.flamecontractor.data.db.ScanRunEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunStatus
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobDao
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.data.db.TargetDao
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.execution.DeduplicatingCommandQueue
import io.github.oinzc.flamecontractor.execution.ExecutionForegroundService
import io.github.oinzc.flamecontractor.execution.ExecutionServiceStoreOps
import io.github.oinzc.flamecontractor.execution.PendingCommandKey
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiAccountScheduleTest {

    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun instantAt(dateTime: String): Instant =
        ZonedDateTime.of(LocalDateTime.parse(dateTime), zoneId).toInstant()

    private fun account(
        id: String,
        displayName: String = "账号-$id",
        enabled: Boolean = true,
        status: AccountStatus = AccountStatus.HEALTHY,
        authFailCount: Int = 0,
        updatedAt: Long = 1_000L,
        autoScanEnabled: Boolean = true,
        autoScanIntervalHours: Int = 24,
        lastAutoScanAttemptAt: Long? = null,
        sendTimeHhmm: String? = null,
    ): AccountEntity = AccountEntity(
        id = id,
        displayName = displayName,
        status = status,
        enabled = enabled,
        timezone = zoneId.id,
        sendWindowStart = "20:00",
        sendWindowEnd = "22:00",
        sendTimeHhmm = sendTimeHhmm,
        authFailCount = authFailCount,
        autoScanEnabled = autoScanEnabled,
        autoScanIntervalHours = autoScanIntervalHours,
        lastAutoScanAttemptAt = lastAutoScanAttemptAt,
        createdAt = 1_000L,
        updatedAt = updatedAt,
    )

    private fun target(
        id: String,
        accountId: String,
        sendTimeHhmm: String? = null,
        enabled: Boolean = true,
    ): ContractTargetEntity = ContractTargetEntity(
        id = id,
        accountId = accountId,
        nicknameSnapshot = "测试好友-$id",
        sendTimeHhmm = sendTimeHhmm,
        enabled = enabled,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun scanRun(
        id: String,
        accountId: String,
        finishedAt: Instant,
        status: ScanRunStatus = ScanRunStatus.COMPLETED,
    ): ScanRunEntity = ScanRunEntity(
        id = id,
        accountId = accountId,
        source = "automatic",
        status = status,
        startedAt = finishedAt.toEpochMilli() - 10_000L,
        finishedAt = finishedAt.toEpochMilli(),
    )

    @Test
    fun aggregatesEarliestNextWakeAndEarliestAutoScanAcrossMultipleAccounts() = runTest {
        val now = instantAt("2026-11-23T12:00:00")
        val accountDao = FakeAccountDao()
        val targetDao = FakeTargetDao()
        val sendJobDao = FakeSendJobDao()
        val scanRunDao = FakeScanRunDao()

        // default: 发送在 21:00, 下次自动扫描在 14:00 (2026-11-22T14:00 + 24h)
        accountDao.upsert(account(id = DEFAULT_ACCOUNT_ID, displayName = "默认号", autoScanIntervalHours = 24))
        targetDao.upsert(target(id = "t-def", accountId = DEFAULT_ACCOUNT_ID, sendTimeHhmm = "21:00"))
        scanRunDao.insert(scanRun(id = "scan-def", accountId = DEFAULT_ACCOUNT_ID, finishedAt = instantAt("2026-11-22T14:00:00")))

        // acct-b: 发送更早 (20:15), 下次自动扫描更晚 (16:00)
        accountDao.upsert(account(id = "acct-b", displayName = "副号B", autoScanIntervalHours = 24))
        targetDao.upsert(target(id = "t-b", accountId = "acct-b", sendTimeHhmm = "20:15"))
        scanRunDao.insert(scanRun(id = "scan-b", accountId = "acct-b", finishedAt = instantAt("2026-11-22T16:00:00")))

        // acct-paused: 发送更早 (19:00) 但处于 PAUSED，不得影响聚合结果
        accountDao.upsert(account(id = "acct-paused", status = AccountStatus.PAUSED))
        targetDao.upsert(target(id = "t-p", accountId = "acct-paused", sendTimeHhmm = "19:00"))

        val aggregation = MultiAccountSchedulePlanner.evaluateAllAccounts(
            accountDao = accountDao,
            targetDao = targetDao,
            sendJobDao = sendJobDao,
            scanRunDao = scanRunDao,
            now = now,
        )

        assertTrue(aggregation.hasAnySchedulableAccount)
        assertEquals(3, aggregation.snapshots.size)
        assertEquals(2, aggregation.schedulableSnapshots.size)
        assertEquals(instantAt("2026-11-23T20:15:00"), aggregation.earliestNextWakeAt)
        assertEquals(instantAt("2026-11-23T14:00:00"), aggregation.earliestNextAutoScanAt)
        assertTrue(aggregation.dispatchPlans.isEmpty())
    }

    @Test
    fun keepsHealthyAccountAlarmsWhenDefaultAccountDisabledOrPaused() = runTest {
        val now = instantAt("2026-11-23T12:00:00")
        val accountDao = FakeAccountDao()
        val targetDao = FakeTargetDao()
        val sendJobDao = FakeSendJobDao()
        val scanRunDao = FakeScanRunDao()

        accountDao.upsert(account(id = DEFAULT_ACCOUNT_ID, enabled = false))
        targetDao.upsert(target(id = "t-def", accountId = DEFAULT_ACCOUNT_ID, sendTimeHhmm = "20:00"))

        accountDao.upsert(account(id = "acct-b", enabled = true, status = AccountStatus.HEALTHY))
        targetDao.upsert(target(id = "t-b", accountId = "acct-b", sendTimeHhmm = "20:30"))
        scanRunDao.insert(scanRun(id = "scan-b", accountId = "acct-b", finishedAt = instantAt("2026-11-22T15:00:00")))

        val partialAggregation = MultiAccountSchedulePlanner.evaluateAllAccounts(
            accountDao = accountDao,
            targetDao = targetDao,
            sendJobDao = sendJobDao,
            scanRunDao = scanRunDao,
            now = now,
        )
        assertTrue(partialAggregation.hasAnySchedulableAccount)
        assertEquals(instantAt("2026-11-23T20:30:00"), partialAggregation.earliestNextWakeAt)
        assertEquals(instantAt("2026-11-23T15:00:00"), partialAggregation.earliestNextAutoScanAt)

        // 当全部账号都停用或异常时，hasAnySchedulableAccount 才转为 false 取消全部闹钟
        accountDao.upsert(account(id = "acct-b", enabled = true, status = AccountStatus.AUTH_EXPIRED))
        val noneSchedulable = MultiAccountSchedulePlanner.evaluateAllAccounts(
            accountDao = accountDao,
            targetDao = targetDao,
            sendJobDao = sendJobDao,
            scanRunDao = scanRunDao,
            now = now,
        )
        assertFalse(noneSchedulable.hasAnySchedulableAccount)
        assertNull(noneSchedulable.earliestNextWakeAt)
        assertNull(noneSchedulable.earliestNextAutoScanAt)
    }

    @Test
    fun dispatchesDueSendAutoScanAndAuthRecheckPerAccountIndependently() = runTest {
        val now = instantAt("2026-11-23T20:10:00")
        val accountDao = FakeAccountDao()
        val targetDao = FakeTargetDao()
        val sendJobDao = FakeSendJobDao()
        val scanRunDao = FakeScanRunDao()

        // 1) default 到期发送
        accountDao.upsert(account(id = DEFAULT_ACCOUNT_ID, displayName = "主号"))
        targetDao.upsert(target(id = "t-def", accountId = DEFAULT_ACCOUNT_ID, sendTimeHhmm = "20:05"))
        scanRunDao.insert(scanRun(id = "scan-def", accountId = DEFAULT_ACCOUNT_ID, finishedAt = now.minusSeconds(3600)))

        // 2) acct-send 同样到期发送（验证同类型任务多账号同时派发不互相吞掉）
        accountDao.upsert(account(id = "acct-send", displayName = "副号发"))
        targetDao.upsert(target(id = "t-send", accountId = "acct-send", sendTimeHhmm = "20:08"))
        scanRunDao.insert(scanRun(id = "scan-s", accountId = "acct-send", finishedAt = now.minusSeconds(3600)))

        // 3) acct-recheck 达到 10 分钟鉴权复判时间
        accountDao.upsert(
            account(
                id = "acct-recheck",
                displayName = "副号复判",
                authFailCount = 1,
                updatedAt = instantAt("2026-11-23T20:00:00").toEpochMilli(),
            ),
        )
        targetDao.upsert(target(id = "t-rec", accountId = "acct-recheck", sendTimeHhmm = "20:00"))

        // 4) acct-scan 昨日已发送完成无到期发送，但从未自动扫描且不在其避让发送窗口 (23:00-23:30)
        accountDao.upsert(
            account(
                id = "acct-scan",
                displayName = "副号扫",
                autoScanEnabled = true,
            ).copy(sendWindowStart = "23:00", sendWindowEnd = "23:30"),
        )
        targetDao.upsert(target(id = "t-scan", accountId = "acct-scan", sendTimeHhmm = "23:10"))

        // 5) acct-disabled 停用不得派发
        accountDao.upsert(account(id = "acct-disabled", enabled = false))
        targetDao.upsert(target(id = "t-dis", accountId = "acct-disabled", sendTimeHhmm = "20:00"))

        val aggregation = MultiAccountSchedulePlanner.evaluateAllAccounts(
            accountDao = accountDao,
            targetDao = targetDao,
            sendJobDao = sendJobDao,
            scanRunDao = scanRunDao,
            now = now,
        )

        val actionsByAccount = aggregation.dispatchPlans.associate {
            it.snapshot.accountId to it.action
        }
        assertEquals(4, actionsByAccount.size)
        assertEquals(ExecutionDispatchContract.ACTION_RUN_SCHEDULED, actionsByAccount[DEFAULT_ACCOUNT_ID])
        assertEquals(ExecutionDispatchContract.ACTION_RUN_SCHEDULED, actionsByAccount["acct-send"])
        assertEquals(ExecutionDispatchContract.ACTION_AUTH_RECHECK, actionsByAccount["acct-recheck"])
        assertEquals(ExecutionDispatchContract.ACTION_RUN_AUTO_SCAN, actionsByAccount["acct-scan"])
        assertFalse(actionsByAccount.containsKey("acct-disabled"))
    }

    @Test
    fun inspectNextAutoScanAtTargetsRequestedAccountIdOnly() = runTest {
        val now = instantAt("2026-11-23T12:00:00")
        val accountDao = FakeAccountDao()
        val targetDao = FakeTargetDao()
        val sendJobDao = FakeSendJobDao()
        val scanRunDao = FakeScanRunDao()

        accountDao.upsert(account(id = DEFAULT_ACCOUNT_ID, autoScanIntervalHours = 24))
        targetDao.upsert(target(id = "t-def", accountId = DEFAULT_ACCOUNT_ID))
        scanRunDao.insert(scanRun(id = "s-def", accountId = DEFAULT_ACCOUNT_ID, finishedAt = instantAt("2026-11-23T10:00:00")))

        accountDao.upsert(account(id = "acct-b", autoScanIntervalHours = 6))
        targetDao.upsert(target(id = "t-b", accountId = "acct-b"))
        scanRunDao.insert(scanRun(id = "s-b", accountId = "acct-b", finishedAt = instantAt("2026-11-23T08:00:00")))

        accountDao.upsert(account(id = "acct-off", autoScanEnabled = false))
        targetDao.upsert(target(id = "t-off", accountId = "acct-off"))

        val nextDefault = MultiAccountSchedulePlanner.inspectNextAutoScanAt(
            accountDao = accountDao,
            targetDao = targetDao,
            sendJobDao = sendJobDao,
            scanRunDao = scanRunDao,
            now = now,
            accountId = DEFAULT_ACCOUNT_ID,
        )
        val nextB = MultiAccountSchedulePlanner.inspectNextAutoScanAt(
            accountDao = accountDao,
            targetDao = targetDao,
            sendJobDao = sendJobDao,
            scanRunDao = scanRunDao,
            now = now,
            accountId = "acct-b",
        )
        val nextOff = MultiAccountSchedulePlanner.inspectNextAutoScanAt(
            accountDao = accountDao,
            targetDao = targetDao,
            sendJobDao = sendJobDao,
            scanRunDao = scanRunDao,
            now = now,
            accountId = "acct-off",
        )
        val nextMissing = MultiAccountSchedulePlanner.inspectNextAutoScanAt(
            accountDao = accountDao,
            targetDao = targetDao,
            sendJobDao = sendJobDao,
            scanRunDao = scanRunDao,
            now = now,
            accountId = "non-existent",
        )

        assertEquals(instantAt("2026-11-24T10:00:00"), nextDefault)
        assertEquals(instantAt("2026-11-23T14:00:00"), nextB)
        assertNull(nextOff)
        assertNull(nextMissing)
    }

    @Test
    fun isolatesNotificationIdsAndRequestCodesWhilePreservingDefaultAccountIds() {
        // 默认账号保持既有固定常量（3001 与 3101..3106）
        assertEquals(3001, LocalNotifier.dailySummaryNotificationIdFor(DEFAULT_ACCOUNT_ID))
        assertEquals(3001, LocalNotifier.dailySummaryNotificationIdFor("  "))
        assertEquals(3001, LocalNotifier.launchRequestCodeFor(null, DEFAULT_ACCOUNT_ID))
        assertEquals(3101, LocalNotifier.notificationIdFor(UrgentCategory.AUTH_EXPIRED, DEFAULT_ACCOUNT_ID))
        assertEquals(3102, LocalNotifier.notificationIdFor(UrgentCategory.VERIFICATION_REQUIRED, DEFAULT_ACCOUNT_ID))
        assertEquals(3103, LocalNotifier.notificationIdFor(UrgentCategory.DOM_BROKEN, DEFAULT_ACCOUNT_ID))
        assertEquals(3104, LocalNotifier.notificationIdFor(UrgentCategory.COOLDOWN, DEFAULT_ACCOUNT_ID))
        assertEquals(3105, LocalNotifier.notificationIdFor(UrgentCategory.UNKNOWN_REQUIRES_REVIEW, DEFAULT_ACCOUNT_ID))
        assertEquals(3106, LocalNotifier.notificationIdFor(UrgentCategory.SPARK_DETERIORATING, DEFAULT_ACCOUNT_ID))

        val accounts = listOf(DEFAULT_ACCOUNT_ID, "acct-alpha", "acct-beta")
        val allCategories: List<UrgentCategory?> = listOf(null) + UrgentCategory.entries

        val notifIds = mutableListOf<Int>()
        val reqCodes = mutableListOf<Int>()
        for (accId in accounts) {
            for (cat in allCategories) {
                val nId = if (cat == null) {
                    LocalNotifier.dailySummaryNotificationIdFor(accId)
                } else {
                    LocalNotifier.notificationIdFor(cat, accId)
                }
                val rCode = LocalNotifier.launchRequestCodeFor(cat, accId)
                notifIds += nId
                reqCodes += rCode
                // 不与前台服务常驻通知 2002 或闹钟 requestCode 4101..4104 碰撞
                assertNotEquals(2002, nId)
                assertFalse(rCode in 4101..4104)
                assertTrue(nId > 0)
                assertTrue(rCode > 0)
            }
        }

        assertEquals(accounts.size * allCategories.size, notifIds.toSet().size)
        assertEquals(accounts.size * allCategories.size, reqCodes.toSet().size)

        // 确定性：同输入多次调用完全一致
        assertEquals(
            LocalNotifier.notificationIdFor(UrgentCategory.AUTH_EXPIRED, "acct-alpha"),
            LocalNotifier.notificationIdFor(UrgentCategory.AUTH_EXPIRED, "acct-alpha"),
        )
        assertEquals(
            LocalNotifier.launchRequestCodeFor(null, "acct-alpha"),
            LocalNotifier.launchRequestCodeFor(null, "acct-alpha"),
        )
    }

    @Test
    fun formatsMultiAccountNotificationPrefixOnlyWhenMultiAccountOrNonDefault() {
        assertFalse(ExecutionServiceStoreOps.isMultiAccountMode(DEFAULT_ACCOUNT_ID, totalAccounts = 1))
        assertTrue(ExecutionServiceStoreOps.isMultiAccountMode(DEFAULT_ACCOUNT_ID, totalAccounts = 2))
        assertTrue(ExecutionServiceStoreOps.isMultiAccountMode("acct-2", totalAccounts = 1))

        assertEquals(
            "发现未确认的发送任务",
            LocalNotifier.formatWithAccountPrefix(
                text = "发现未确认的发送任务",
                accountDisplayName = "默认抖音号",
                isMultiAccount = false,
            ),
        )
        val prefixed = LocalNotifier.formatWithAccountPrefix(
            text = "发现未确认的发送任务",
            accountDisplayName = "副号测试",
            isMultiAccount = true,
        )
        assertEquals("[副号测试] 发现未确认的发送任务", prefixed)
        // 幂等不重复加前缀
        assertEquals(
            prefixed,
            LocalNotifier.formatWithAccountPrefix(
                text = prefixed,
                accountDisplayName = "副号测试",
                isMultiAccount = true,
            ),
        )
    }

    @Test
    fun deduplicatesPendingCommandsByTripleActionAccountIdAndTargetId() {
        data class FakeCommand(
            val action: String,
            val accountId: String,
            val targetId: String = "",
            val seq: Int,
        )

        val queue = DeduplicatingCommandQueue<FakeCommand> { cmd ->
            PendingCommandKey(
                action = cmd.action,
                accountId = cmd.accountId,
                targetId = cmd.targetId,
            )
        }

        // 同三元组排队中重复入队被去重
        assertTrue(queue.enqueue(FakeCommand(ExecutionDispatchContract.ACTION_RUN_SCHEDULED, DEFAULT_ACCOUNT_ID, "", 1)))
        assertFalse(queue.enqueue(FakeCommand(ExecutionDispatchContract.ACTION_RUN_SCHEDULED, DEFAULT_ACCOUNT_ID, "", 2)))

        // 不同账号同类任务不互相吞掉，保持 FIFO
        assertTrue(queue.enqueue(FakeCommand(ExecutionDispatchContract.ACTION_RUN_SCHEDULED, "acct-2", "", 3)))

        // 同账号同单发动作不同 targetId 不互相吞掉
        assertTrue(queue.enqueue(FakeCommand(ExecutionForegroundService.ACTION_RUN_SINGLE_SEND, DEFAULT_ACCOUNT_ID, "t-1", 4)))
        assertTrue(queue.enqueue(FakeCommand(ExecutionForegroundService.ACTION_RUN_SINGLE_SEND, DEFAULT_ACCOUNT_ID, "t-2", 5)))
        assertFalse(queue.enqueue(FakeCommand(ExecutionForegroundService.ACTION_RUN_SINGLE_SEND, DEFAULT_ACCOUNT_ID, "t-1", 6)))

        // 同账号不同 action 不互相吞掉
        assertTrue(queue.enqueue(FakeCommand(ExecutionForegroundService.ACTION_RUN_MANUAL_SCAN, DEFAULT_ACCOUNT_ID, "", 7)))

        assertEquals(5, queue.size())
        assertEquals(1, queue.dequeue()?.seq)
        assertEquals(3, queue.dequeue()?.seq)

        // 已出队执行后，同三元组允许重新入队
        assertTrue(queue.enqueue(FakeCommand(ExecutionDispatchContract.ACTION_RUN_SCHEDULED, DEFAULT_ACCOUNT_ID, "", 8)))
        assertEquals(4, queue.dequeue()?.seq)
        assertEquals(5, queue.dequeue()?.seq)
        assertEquals(7, queue.dequeue()?.seq)
        assertEquals(8, queue.dequeue()?.seq)
        assertNull(queue.dequeue())
        assertTrue(queue.isEmpty())
    }

    @Test
    fun coldStartRecoveryTracksPerAccountUnknownAlertsAndResetsRunningStatus() = runTest {
        val accountDao = FakeAccountDao()
        val sendJobDao = FakeSendJobDao()
        val nowMs = 2_000_000L

        accountDao.upsert(account(id = DEFAULT_ACCOUNT_ID, displayName = "主号", status = AccountStatus.HEALTHY))
        accountDao.upsert(account(id = "acct-b", displayName = "副号B", status = AccountStatus.RUNNING))
        accountDao.upsert(account(id = "acct-c", displayName = "副号C", status = AccountStatus.HEALTHY))

        // 仅 acct-b 存在进入 CONFIRMING 的崩溃任务，acct-c 为 OPENING_CHAT（可重试，不应发待核对告警）
        val confirmingJob = SendJobEntity(
            id = "job-b",
            accountId = "acct-b",
            targetId = "t-b",
            businessDate = "2026-11-23",
            messageSlotId = SendGuard.DAILY_SLOT_ID,
            status = JobStatus.CONFIRMING,
            attemptCount = 1,
            createdAt = 1_000L,
            updatedAt = 1_000L,
        )
        sendJobDao.upsertJob(confirmingJob)
        sendJobDao.insertAttempt(
            SendAttemptEntity(
                id = "att-b",
                jobId = "job-b",
                attemptNo = 1,
                stage = "confirming",
                status = AttemptStatus.RUNNING,
                startedAt = 1_000L,
            ),
        )

        val openingJob = SendJobEntity(
            id = "job-c",
            accountId = "acct-c",
            targetId = "t-c",
            businessDate = "2026-11-23",
            messageSlotId = SendGuard.DAILY_SLOT_ID,
            status = JobStatus.OPENING_CHAT,
            attemptCount = 1,
            createdAt = 1_000L,
            updatedAt = 1_000L,
        )
        sendJobDao.upsertJob(openingJob)

        val report = FlameApplication.performColdStartRecovery(
            accountDao = accountDao,
            sendJobDao = sendJobDao,
            nowMs = nowMs,
        )

        assertTrue(report.hasUnknown)
        assertEquals(3, report.totalAccountCount)
        assertEquals(1, report.unknownAlerts.size)
        assertEquals("acct-b", report.unknownAlerts.single().accountId)
        assertEquals("副号B", report.unknownAlerts.single().displayName)
        assertEquals(JobStatus.UNKNOWN_REQUIRES_REVIEW, sendJobDao.getJobById("job-b")?.status)
        assertEquals(JobStatus.FAILED_RETRYABLE, sendJobDao.getJobById("job-c")?.status)
        assertEquals(AccountStatus.HEALTHY, accountDao.getAccount("acct-b")?.status)

        val missingImgRes = ExecutionServiceStoreOps.missingTemplateImageResult()
        assertEquals(JobStatus.FAILED_TERMINAL, missingImgRes.jobStatus)
        assertEquals(FailureClass.OTHER, missingImgRes.failureClass)
        assertEquals("image_upload", missingImgRes.inputMethodUsed)
        assertTrue(missingImgRes.summaryDetail?.contains("模板") == true)
    }

    private class FakeAccountDao : AccountDao {
        private val accounts = LinkedHashMap<String, AccountEntity>()

        override fun observeAccount(accountId: String): Flow<AccountEntity?> = emptyFlow()
        override suspend fun getAccount(accountId: String): AccountEntity? = accounts[accountId]
        override fun observeAll(): Flow<List<AccountEntity>> = emptyFlow()
        override suspend fun listAll(): List<AccountEntity> = accounts.values.sortedBy { it.createdAt }
        override suspend fun deleteById(accountId: String) {
            accounts.remove(accountId)
        }
        override suspend fun upsert(account: AccountEntity) {
            accounts[account.id] = account
        }
        override suspend fun update(account: AccountEntity) {
            accounts[account.id] = account
        }
        override suspend fun updateHealthState(
            accountId: String,
            status: AccountStatus,
            authFailCount: Int,
            failureStreak: Int,
            updatedAt: Long,
        ) {
            accounts[accountId]?.let {
                accounts[accountId] = it.copy(
                    status = status,
                    authFailCount = authFailCount,
                    failureStreak = failureStreak,
                    updatedAt = updatedAt,
                )
            }
        }
        override suspend fun updateLoginIdentity(
            accountId: String,
            selfUid: String?,
            douyinNickname: String?,
            verifiedAt: Long,
        ) {
            accounts[accountId]?.let {
                accounts[accountId] = it.copy(
                    selfUid = selfUid,
                    douyinNickname = douyinNickname,
                    lastVerifiedAt = verifiedAt,
                    updatedAt = verifiedAt,
                )
            }
        }
        override suspend fun updateLastSuccessAt(accountId: String, successAt: Long) {
            accounts[accountId]?.let {
                accounts[accountId] = it.copy(lastSuccessAt = successAt, updatedAt = successAt)
            }
        }
        override suspend fun updateLastAutoScanAttemptAt(accountId: String, attemptAt: Long, updatedAt: Long) {
            accounts[accountId]?.let {
                accounts[accountId] = it.copy(lastAutoScanAttemptAt = attemptAt, updatedAt = updatedAt)
            }
        }
    }

    private class FakeTargetDao : TargetDao {
        private val targets = LinkedHashMap<String, ContractTargetEntity>()

        override fun observeActiveTargets(accountId: String): Flow<List<ContractTargetEntity>> = emptyFlow()
        override suspend fun listActiveTargets(accountId: String): List<ContractTargetEntity> =
            targets.values.filter { it.accountId == accountId && it.deletedAt == null }
        override suspend fun listAllTargetsIncludingDeleted(accountId: String): List<ContractTargetEntity> =
            targets.values.filter { it.accountId == accountId }
        override suspend fun listEnabledTargets(accountId: String): List<ContractTargetEntity> =
            targets.values.filter { it.accountId == accountId && it.enabled && it.deletedAt == null }
        override suspend fun getById(id: String): ContractTargetEntity? = targets[id]
        override suspend fun findByNicknameIncludingDeleted(accountId: String, nickname: String): ContractTargetEntity? =
            targets.values.firstOrNull { it.accountId == accountId && it.nicknameSnapshot == nickname }
        override suspend fun findByPlatformUserIdIncludingDeleted(accountId: String, platformUserId: String): ContractTargetEntity? =
            targets.values.firstOrNull { it.accountId == accountId && it.platformUserId == platformUserId }
        override suspend fun findByPlatformConversationIdIncludingDeleted(accountId: String, platformConversationId: String): ContractTargetEntity? =
            targets.values.firstOrNull { it.accountId == accountId && it.platformConversationId == platformConversationId }
        override suspend fun countActiveTargets(accountId: String): Int = listActiveTargets(accountId).size
        override suspend fun countActiveByTemplate(templateId: String): Int =
            targets.values.count { it.templateId == templateId && it.deletedAt == null }
        override suspend fun upsert(target: ContractTargetEntity) {
            targets[target.id] = target
        }
        override suspend fun insert(target: ContractTargetEntity) {
            targets[target.id] = target
        }
        override suspend fun update(target: ContractTargetEntity) {
            targets[target.id] = target
        }
        override suspend fun softDelete(id: String, deletedAt: Long) {
            targets[id]?.let { targets[id] = it.copy(deletedAt = deletedAt, enabled = false, updatedAt = deletedAt) }
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
        ) = Unit
        override suspend fun updateOfficialSparkSnapshot(
            id: String,
            sparkDays: Int?,
            sparkKind: String?,
            sparkLabel: String?,
            scannedAt: Long,
        ) = Unit
        override suspend fun recordSendConfirmed(id: String, confirmedAt: Long) = Unit
        override suspend fun unbindIdentitiesAndDisableAll(accountId: String, updatedAt: Long) = Unit
        override suspend fun deleteAllByAccount(accountId: String) {
            targets.values.removeAll { it.accountId == accountId }
        }
    }

    private class FakeSendJobDao : SendJobDao {
        private val jobs = LinkedHashMap<String, SendJobEntity>()
        private val attempts = mutableListOf<SendAttemptEntity>()

        override fun observeJobsForDate(accountId: String, businessDate: String): Flow<List<SendJobEntity>> = emptyFlow()
        override suspend fun listJobsForDate(accountId: String, businessDate: String): List<SendJobEntity> =
            jobs.values.filter { it.accountId == accountId && it.businessDate == businessDate }
        override suspend fun listJobsForTargetDate(accountId: String, targetId: String, businessDate: String): List<SendJobEntity> =
            jobs.values.filter { it.accountId == accountId && it.targetId == targetId && it.businessDate == businessDate }
        override suspend fun getJobBySlot(accountId: String, targetId: String, businessDate: String, slotId: String): SendJobEntity? =
            jobs.values.firstOrNull {
                it.accountId == accountId && it.targetId == targetId && it.businessDate == businessDate && it.messageSlotId == slotId
            }
        override suspend fun getJobById(id: String): SendJobEntity? = jobs[id]
        override fun observeJobsByStatus(accountId: String, status: JobStatus): Flow<List<SendJobEntity>> = emptyFlow()
        override suspend fun listJobsByStatuses(accountId: String, statuses: List<JobStatus>): List<SendJobEntity> =
            jobs.values.filter { it.accountId == accountId && it.status in statuses }
        override fun observeRecentJobs(accountId: String, limit: Int): Flow<List<SendJobEntity>> = emptyFlow()
        override fun observeJobsSince(accountId: String, sinceDate: String): Flow<List<SendJobEntity>> = emptyFlow()
        override suspend fun listAllJobs(accountId: String): List<SendJobEntity> =
            jobs.values.filter { it.accountId == accountId }
        override suspend fun listAllAttemptsForAccount(accountId: String): List<SendAttemptEntity> {
            val ids = jobs.values.filter { it.accountId == accountId }.mapTo(HashSet()) { it.id }
            return attempts.filter { it.jobId in ids }
        }
        override suspend fun upsertJob(job: SendJobEntity) {
            jobs[job.id] = job
        }
        override suspend fun upsertAttempt(attempt: SendAttemptEntity) {
            val idx = attempts.indexOfFirst { it.id == attempt.id }
            if (idx >= 0) attempts[idx] = attempt else attempts.add(attempt)
        }
        override suspend fun insertIgnore(job: SendJobEntity): Long {
            if (jobs.containsKey(job.id)) return -1L
            jobs[job.id] = job
            return 1L
        }
        override suspend fun updateJob(job: SendJobEntity) {
            jobs[job.id] = job
        }
        override suspend fun updateJobOutcome(
            id: String,
            status: JobStatus,
            failureClass: FailureClass?,
            summaryDetail: String?,
            confirmedAt: Long?,
            updatedAt: Long,
        ) {
            jobs[id]?.let {
                jobs[id] = it.copy(
                    status = status,
                    failureClass = failureClass,
                    summaryDetail = summaryDetail,
                    confirmedAt = confirmedAt,
                    updatedAt = updatedAt,
                )
            }
        }
        override suspend fun deleteAllByAccount(accountId: String) {
            val ids = jobs.values.filter { it.accountId == accountId }.map { it.id }
            ids.forEach { id ->
                jobs.remove(id)
                attempts.removeAll { it.jobId == id }
            }
        }
        override suspend fun insertAttempt(attempt: SendAttemptEntity) {
            attempts.add(attempt)
        }
        override suspend fun updateAttempt(attempt: SendAttemptEntity) {
            val idx = attempts.indexOfFirst { it.id == attempt.id }
            if (idx >= 0) attempts[idx] = attempt
        }
        override suspend fun listAttemptsForJob(jobId: String): List<SendAttemptEntity> =
            attempts.filter { it.jobId == jobId }.sortedBy { it.attemptNo }
        override fun observeAttemptsForJob(jobId: String): Flow<List<SendAttemptEntity>> = emptyFlow()
    }

    private class FakeScanRunDao : ScanRunDao {
        private val runs = LinkedHashMap<String, ScanRunEntity>()

        override fun observeLatestScanRun(accountId: String): Flow<ScanRunEntity?> = emptyFlow()
        override suspend fun getLatestScanRun(accountId: String): ScanRunEntity? =
            runs.values.filter { it.accountId == accountId }.maxByOrNull { it.startedAt }
        override suspend fun getLatestCompletedScanRun(accountId: String): ScanRunEntity? =
            runs.values
                .filter { it.accountId == accountId && it.status == ScanRunStatus.COMPLETED }
                .maxByOrNull { it.finishedAt ?: it.startedAt }
        override suspend fun getLatestAutoScanRun(accountId: String): ScanRunEntity? =
            runs.values
                .filter { it.accountId == accountId && it.source == "automatic" }
                .maxByOrNull { it.startedAt }
        override fun observeLatestAutoScanRun(accountId: String): Flow<ScanRunEntity?> = emptyFlow()
        override suspend fun getById(id: String): ScanRunEntity? = runs[id]
        override suspend fun listRecent(accountId: String, limit: Int): List<ScanRunEntity> =
            runs.values.filter { it.accountId == accountId }.sortedByDescending { it.startedAt }.take(limit)
        override suspend fun insert(scanRun: ScanRunEntity) {
            runs[scanRun.id] = scanRun
        }
        override suspend fun update(scanRun: ScanRunEntity) {
            runs[scanRun.id] = scanRun
        }
        override suspend fun deleteAllByAccount(accountId: String) {
            runs.values.removeAll { it.accountId == accountId }
        }
    }
}
