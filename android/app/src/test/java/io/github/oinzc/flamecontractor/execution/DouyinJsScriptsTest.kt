package io.github.oinzc.flamecontractor.execution

import io.github.oinzc.flamecontractor.core.identity.MatchStatus
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundle
import io.github.oinzc.flamecontractor.data.db.AccountDao
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobDao
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.domain.SendGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinJsScriptsTest {

    @Test
    fun indexedDbScriptUsesPerStoreClosureAndEventTargetResultWithoutSharedVar() {
        val script = DouyinJsScripts.buildReadIndexedDbIdentitiesJs()

        assertTrue(script.contains("stores.forEach"))
        assertTrue(script.contains("ev.target.result"))
        assertFalse(script.contains("var allReq"))
        assertFalse(Regex("""for\s*\(\s*var\b""").containsMatchIn(script))
    }

    @Test
    fun textScriptsContainZeroWidthStripRegexAndExecCommand() {
        val bundle = SelectorBundle.defaults()
        val insertScript = DouyinJsScripts.buildInsertTextExecCommandJs(
            bundle = bundle,
            text = "第一行 \"引号\" \\斜杠\n第二行",
        )
        val checkEmptyScript = DouyinJsScripts.buildCheckEditorEmptyJs(bundle)
        val confirmScript = DouyinJsScripts.buildSampleTextConfirmJs(bundle, "第一行\"引号\"\\斜杠第二行")

        assertTrue(DouyinJsScripts.ZERO_WIDTH_REGEX_PATTERN.contains("\\u200B"))
        assertTrue(insertScript.contains("document.execCommand('insertText'"))
        assertTrue(insertScript.contains("""[\s\u200B\u200C\u200D\uFEFF]+"""))
        assertTrue(insertScript.contains("""\"引号\""""))
        assertTrue(insertScript.contains("""\\斜杠"""))
        assertTrue(checkEmptyScript.contains("""[\s\u200B\u200C\u200D\uFEFF]+"""))
        assertTrue(confirmScript.contains("""[\s\u200B\u200C\u200D\uFEFF]+"""))
        assertTrue(confirmScript.contains("isFromMe"))
    }

    @Test
    fun scanReconciliationCodecRoundTripsAndPreservesPreselectedAndAmbiguousFields() {
        val rawCandidates = listOf(
            ScannedChatCandidate(
                displayName = "无火花普通好友",
                conversationType = "friend",
                sparkDays = null,
                sparkKind = null,
                sparkLabel = "无火花",
                matchStatus = MatchStatus.BOUND,
                platformUserId = "uid_plain",
                platformHandle = "handle_plain",
                platformConversationId = null,
                matchDetail = "通过平台昵称唯一匹配",
                alreadyImported = false,
                existingTargetId = null,
                preselected = false,
            ),
            ScannedChatCandidate(
                displayName = "同名冲突火花好友",
                conversationType = "friend",
                sparkDays = 300,
                sparkKind = "normal",
                sparkLabel = null,
                matchStatus = MatchStatus.AMBIGUOUS,
                platformUserId = null,
                platformHandle = null,
                platformConversationId = null,
                matchDetail = "同名对应多个平台用户，已阻止自动绑定",
                alreadyImported = false,
                existingTargetId = null,
                preselected = false,
            ),
            ScannedChatCandidate(
                displayName = "唯一火花群聊",
                conversationType = "group",
                sparkDays = 120,
                sparkKind = "normal",
                sparkLabel = null,
                matchStatus = MatchStatus.BOUND,
                platformUserId = null,
                platformHandle = null,
                platformConversationId = "7310000000000000001",
                matchDetail = "通过抖音会话 protobuf 唯一匹配群聊",
                alreadyImported = false,
                existingTargetId = null,
                preselected = true,
            ),
            ScannedChatCandidate(
                displayName = "已导入高火花",
                conversationType = "friend",
                sparkDays = 500,
                sparkKind = "couple",
                sparkLabel = null,
                matchStatus = MatchStatus.BOUND,
                platformUserId = "uid_imported",
                platformHandle = null,
                platformConversationId = null,
                matchDetail = "通过备注名唯一匹配",
                alreadyImported = true,
                existingTargetId = "target-1",
                preselected = false,
            ),
        )

        val sorted = ScanReconciliationBuilder.sortAndComputePreselection(rawCandidates)
        assertEquals(
            listOf("同名冲突火花好友", "唯一火花群聊", "已导入高火花", "无火花普通好友"),
            sorted.map { it.displayName },
        )
        assertFalse(sorted[0].preselected)
        assertTrue(sorted[1].preselected)
        assertFalse(sorted[2].preselected)
        assertFalse(sorted[3].preselected)

        val snapshot = ScanReconciliationSnapshot(
            scanRunId = "scan-001",
            scannedAtEpochMs = 1_763_760_000_000L,
            discoveredCount = sorted.size,
            sparkCount = 3,
            candidates = sorted,
        )

        val encoded = ScanReconciliationCodec.encode(snapshot)
        val decoded = ScanReconciliationCodec.decode(encoded)

        assertEquals(snapshot, decoded)
        assertEquals(MatchStatus.AMBIGUOUS, decoded.candidates[0].matchStatus)
        assertFalse(decoded.candidates[0].preselected)
        assertTrue(decoded.candidates[1].preselected)
        assertEquals("7310000000000000001", decoded.candidates[1].platformConversationId)
        assertEquals("target-1", decoded.candidates[2].existingTargetId)
        assertNull(ScanReconciliationCodec.decodeOrNull("{}"))
    }

    @Test
    fun executionMutexRejectsContentionWithinTimeout() = runTest {
        val token = ExecutionMutex.tryAcquireSession("login_test")
        assertTrue(token != null)
        try {
            val blockedResult = ExecutionMutex.tryWithLock("scheduled_test", timeoutMs = 50L) {
                "should_not_run"
            }
            assertNull(blockedResult)
        } finally {
            ExecutionMutex.releaseSession(token)
        }
        val ran = ExecutionMutex.tryWithLock("scheduled_test", timeoutMs = 100L) {
            "ok"
        }
        assertEquals("ok", ran)
    }

    @Test
    fun networkDeferThenRetrySendDoesNotCollideAttemptNoUniqueConstraint() = runTest {
        val sendJobDao = InMemoryStrictSendJobDao()
        val target = ContractTargetEntity(
            id = "target-net-1",
            accountId = DEFAULT_ACCOUNT_ID,
            nicknameSnapshot = "测试对象",
            templateId = "tpl-1",
        )

        // 1) 前置断网：记录第 1 条 attempt (attemptNo = 1)，但不增加 job.attemptCount (保持 0)
        val netAttempt = ExecutionServiceStoreOps.recordNetworkDeferAttempt(
            accountId = DEFAULT_ACCOUNT_ID,
            target = target,
            todayStr = "2026-11-22",
            sendJobDao = sendJobDao,
            nowMs = 10_000L,
        )
        assertEquals(1, netAttempt.attemptNo)
        val afterNetJob = sendJobDao.getJobBySlot(
            DEFAULT_ACCOUNT_ID,
            target.id,
            "2026-11-22",
            SendGuard.DAILY_SLOT_ID,
        )!!
        assertEquals(0, afterNetJob.attemptCount)
        assertEquals(JobStatus.FAILED_RETRYABLE, afterNetJob.status)
        assertEquals(FailureClass.NETWORK, afterNetJob.failureClass)

        // 2) 10 分钟后网络恢复：分配真实发送尝试时 attemptNo 必须自增为 2（不触发唯一约束冲突），
        // 且用于 RETRY_MAX_COUNT 的 job.attemptCount 为 1
        val firstSend = ExecutionServiceStoreOps.allocateJobAndAttemptForSend(
            accountId = DEFAULT_ACCOUNT_ID,
            target = target,
            templateId = "tpl-1",
            businessDate = "2026-11-22",
            slotId = SendGuard.DAILY_SLOT_ID,
            sendJobDao = sendJobDao,
            nowMs = 20_000L,
        )
        assertNotNull(firstSend)
        assertEquals(2, firstSend!!.attempt.attemptNo)
        assertEquals(2, firstSend.nextAttemptRowNo)
        assertEquals(1, firstSend.nextRetryAttemptCount)
        assertEquals(1, firstSend.job.attemptCount)

        // 3) 若首次发送仍遇到可重试错误，再次重试时 attemptNo 为 3，job.attemptCount 为 2
        sendJobDao.updateJob(firstSend.job.copy(status = JobStatus.FAILED_RETRYABLE))
        val secondSend = ExecutionServiceStoreOps.allocateJobAndAttemptForSend(
            accountId = DEFAULT_ACCOUNT_ID,
            target = target,
            templateId = "tpl-1",
            businessDate = "2026-11-22",
            slotId = SendGuard.DAILY_SLOT_ID,
            sendJobDao = sendJobDao,
            nowMs = 30_000L,
        )
        assertNotNull(secondSend)
        assertEquals(3, secondSend!!.attempt.attemptNo)
        assertEquals(2, secondSend.nextRetryAttemptCount)
        assertEquals(
            JobStatus.FAILED_RETRYABLE,
            SendGuard.classifyRetryableFailure(secondSend.nextRetryAttemptCount),
        )
    }

    @Test
    fun leftoverRunningAccountAndSendingJobRecoverCleanlyOnStartupAndBatchCancel() = runTest {
        val accountDao = InMemoryAccountDao()
        val sendJobDao = InMemoryStrictSendJobDao()

        // 场景 A：批处理异常/硬超时取消时 finally 必须收口 RUNNING -> HEALTHY
        accountDao.upsert(
            AccountEntity(
                id = DEFAULT_ACCOUNT_ID,
                status = AccountStatus.RUNNING,
                updatedAt = 1_000L,
            ),
        )
        val finalized = ExecutionServiceStoreOps.finalizeBatchAccountIfRunning(
            accountId = DEFAULT_ACCOUNT_ID,
            accountDao = accountDao,
            outcomes = emptyList(),
            nowMs = 2_000L,
        )
        assertNotNull(finalized)
        assertEquals(AccountStatus.HEALTHY, finalized!!.status)
        assertEquals(AccountStatus.HEALTHY, accountDao.getAccount(DEFAULT_ACCOUNT_ID)?.status)

        // 场景 B：进程在 RUNNING + SENDING 状态被系统强杀，重启恢复必须双收敛
        accountDao.upsert(
            AccountEntity(
                id = DEFAULT_ACCOUNT_ID,
                status = AccountStatus.RUNNING,
                updatedAt = 3_000L,
            ),
        )
        val sendingJob = SendJobEntity(
            id = "job-crash-sending",
            accountId = DEFAULT_ACCOUNT_ID,
            targetId = "target-1",
            businessDate = "2026-11-22",
            messageSlotId = SendGuard.DAILY_SLOT_ID,
            status = JobStatus.SENDING,
            attemptCount = 1,
        )
        sendJobDao.insertIgnore(sendingJob)
        sendJobDao.insertAttempt(
            SendAttemptEntity(
                id = "att-crash-1",
                jobId = sendingJob.id,
                attemptNo = 1,
                stage = "sending",
                status = AttemptStatus.RUNNING,
            ),
        )

        // 若互斥锁仍被占用，不抢先改写 RUNNING
        ExecutionServiceStoreOps.recoverInterruptedAccountAndJobs(
            accountId = DEFAULT_ACCOUNT_ID,
            accountDao = accountDao,
            sendJobDao = sendJobDao,
            nowMs = 4_000L,
            isMutexLocked = true,
        )
        assertEquals(AccountStatus.RUNNING, accountDao.getAccount(DEFAULT_ACCOUNT_ID)?.status)
        assertEquals(JobStatus.UNKNOWN_REQUIRES_REVIEW, sendJobDao.getJobById(sendingJob.id)?.status)
        assertEquals(AttemptStatus.FAILED, sendJobDao.listAttemptsForJob(sendingJob.id).first().status)

        // 互斥锁空闲时恢复 RUNNING -> HEALTHY
        ExecutionServiceStoreOps.recoverInterruptedAccountAndJobs(
            accountId = DEFAULT_ACCOUNT_ID,
            accountDao = accountDao,
            sendJobDao = sendJobDao,
            nowMs = 5_000L,
            isMutexLocked = false,
        )
        assertEquals(AccountStatus.HEALTHY, accountDao.getAccount(DEFAULT_ACCOUNT_ID)?.status)
    }

    private class InMemoryAccountDao : AccountDao {
        private val accounts = LinkedHashMap<String, AccountEntity>()

        override fun observeAccount(accountId: String): Flow<AccountEntity?> = emptyFlow()
        override suspend fun getAccount(accountId: String): AccountEntity? = accounts[accountId]
        override suspend fun listAll(): List<AccountEntity> = accounts.values.toList()
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
    }

    private class InMemoryStrictSendJobDao : SendJobDao {
        private val jobs = LinkedHashMap<String, SendJobEntity>()
        private val attempts = mutableListOf<SendAttemptEntity>()

        override fun observeJobsForDate(accountId: String, businessDate: String): Flow<List<SendJobEntity>> = emptyFlow()

        override suspend fun listJobsForDate(accountId: String, businessDate: String): List<SendJobEntity> =
            jobs.values.filter { it.accountId == accountId && it.businessDate == businessDate }

        override suspend fun listJobsForTargetDate(
            accountId: String,
            targetId: String,
            businessDate: String,
        ): List<SendJobEntity> = jobs.values.filter {
            it.accountId == accountId && it.targetId == targetId && it.businessDate == businessDate
        }

        override suspend fun getJobBySlot(
            accountId: String,
            targetId: String,
            businessDate: String,
            slotId: String,
        ): SendJobEntity? = jobs.values.firstOrNull {
            it.accountId == accountId &&
                it.targetId == targetId &&
                it.businessDate == businessDate &&
                it.messageSlotId == slotId
        }

        override suspend fun getJobById(id: String): SendJobEntity? = jobs[id]

        override fun observeJobsByStatus(accountId: String, status: JobStatus): Flow<List<SendJobEntity>> = emptyFlow()

        override suspend fun listJobsByStatuses(
            accountId: String,
            statuses: List<JobStatus>,
        ): List<SendJobEntity> = jobs.values.filter { it.accountId == accountId && it.status in statuses }

        override fun observeRecentJobs(accountId: String, limit: Int): Flow<List<SendJobEntity>> = emptyFlow()

        override suspend fun insertIgnore(job: SendJobEntity): Long {
            val dup = getJobBySlot(job.accountId, job.targetId, job.businessDate, job.messageSlotId)
            if (dup != null || jobs.containsKey(job.id)) return -1L
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
            val toRemove = jobs.values.filter { it.accountId == accountId }.map { it.id }
            toRemove.forEach { id ->
                jobs.remove(id)
                attempts.removeAll { it.jobId == id }
            }
        }

        override suspend fun insertAttempt(attempt: SendAttemptEntity) {
            if (attempts.any { it.jobId == attempt.jobId && it.attemptNo == attempt.attemptNo }) {
                throw IllegalStateException("UNIQUE constraint failed: uq_attempt_job_no(${attempt.jobId}, ${attempt.attemptNo})")
            }
            attempts.add(attempt)
        }

        override suspend fun updateAttempt(attempt: SendAttemptEntity) {
            val idx = attempts.indexOfFirst { it.id == attempt.id }
            if (idx >= 0) {
                attempts[idx] = attempt
            }
        }

        override suspend fun listAttemptsForJob(jobId: String): List<SendAttemptEntity> =
            attempts.filter { it.jobId == jobId }.sortedBy { it.attemptNo }

        override fun observeAttemptsForJob(jobId: String): Flow<List<SendAttemptEntity>> = emptyFlow()
    }
}
