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

    @Test
    fun authProbeScriptSeparatesAboutBlankFromChromeErrorPage() {
        val script = DouyinJsScripts.buildAuthAndSelfIdentityProbeJs(SelectorBundle.defaults())

        assertTrue(script.contains("var isBlankPage = !href || href === 'about:blank';"))
        assertTrue(script.contains("var isErrorPage = href.indexOf('chrome-error://') === 0;"))
        assertTrue(script.contains("isBlankPage: isBlankPage,"))
    }

    @Test
    fun authPollWaitsThroughInitialAboutBlankAndFailsOnChromeErrorPage() {
        val initialBlankProbe = mapOf<String, Any?>(
            "isDouyinHost" to false,
            "isBlankPage" to true,
            "isErrorPage" to false,
            "loggedIn" to false,
            "verificationRequired" to false,
            "hasLocalStorageUserInfo" to false,
            "hasQrOrLoginSignal" to false,
        )
        assertEquals(
            DouyinWebExecutor.AuthPollDecision.CONTINUE,
            DouyinWebExecutor.evaluateAuthPollDecision(
                probe = initialBlankProbe,
                mainErr = null,
                pageFinished = false,
                elapsedMs = 50L,
                consecutiveVerificationSignals = 0,
                consecutiveLoginSignals = 0,
            ),
        )

        val chromeErrorProbe = mapOf<String, Any?>(
            "isDouyinHost" to false,
            "isBlankPage" to false,
            "isErrorPage" to true,
            "loggedIn" to false,
            "verificationRequired" to false,
            "hasLocalStorageUserInfo" to false,
            "hasQrOrLoginSignal" to false,
        )
        assertEquals(
            DouyinWebExecutor.AuthPollDecision.NETWORK_ERROR,
            DouyinWebExecutor.evaluateAuthPollDecision(
                probe = chromeErrorProbe,
                mainErr = null,
                pageFinished = false,
                elapsedMs = 500L,
                consecutiveVerificationSignals = 0,
                consecutiveLoginSignals = 0,
            ),
        )

        assertEquals(
            DouyinWebExecutor.AuthPollDecision.NETWORK_ERROR,
            DouyinWebExecutor.evaluateAuthPollDecision(
                probe = initialBlankProbe,
                mainErr = -2 to "ERR_INTERNET_DISCONNECTED",
                pageFinished = false,
                elapsedMs = 1_600L,
                consecutiveVerificationSignals = 0,
                consecutiveLoginSignals = 0,
            ),
        )
    }

    @Test
    fun authPollDoesNotFalseFailUnauthenticatedOnSingleTransientLoginFlash() {
        val loginFlashProbe = mapOf<String, Any?>(
            "isDouyinHost" to true,
            "isBlankPage" to false,
            "isErrorPage" to false,
            "loggedIn" to false,
            "verificationRequired" to false,
            "hasLocalStorageUserInfo" to false,
            "hasQrOrLoginSignal" to true,
        )

        assertEquals(
            DouyinWebExecutor.AuthPollDecision.CONTINUE,
            DouyinWebExecutor.evaluateAuthPollDecision(
                probe = loginFlashProbe,
                mainErr = null,
                pageFinished = false,
                elapsedMs = 500L,
                consecutiveVerificationSignals = 0,
                consecutiveLoginSignals = 1,
            ),
        )
        assertEquals(
            DouyinWebExecutor.AuthPollDecision.CONTINUE,
            DouyinWebExecutor.evaluateAuthPollDecision(
                probe = loginFlashProbe,
                mainErr = null,
                pageFinished = true,
                elapsedMs = 1_500L,
                consecutiveVerificationSignals = 0,
                consecutiveLoginSignals = 1,
            ),
        )
        assertEquals(
            DouyinWebExecutor.AuthPollDecision.UNAUTHENTICATED,
            DouyinWebExecutor.evaluateAuthPollDecision(
                probe = loginFlashProbe,
                mainErr = null,
                pageFinished = true,
                elapsedMs = 3_000L,
                consecutiveVerificationSignals = 0,
                consecutiveLoginSignals = 3,
            ),
        )
    }

    @Test
    fun stickerClickScriptResolvesInnerImageTargetAndDispatchesSingleClick() {
        val bundle = SelectorBundle.defaults()
        val script = DouyinJsScripts.buildClickStickerJs(
            bundle = bundle,
            stickerName = "比心",
        )

        assertTrue(script.contains("__flameResolveClickTarget"))
        assertTrue(script.contains("__flameIsVisible(item)"))
        assertTrue(script.contains("targetTag"))
        assertTrue(script.contains("__flameResolveClickTarget(item, true)"))
        assertTrue(script.contains("el.dispatchEvent(new MouseEvent('click', upOpts))"))
        assertTrue(script.contains("if (!clickSent)"))
        assertTrue(
            Regex("""if\s*\(!clickSent\)\s*\{\s*try\s*\{\s*if\s*\(typeof el\.click === 'function'\)\s*el\.click\(\);""")
                .containsMatchIn(script),
        )
        assertFalse(script.contains("__flameDispatchClick(item)"))
    }

    @Test
    fun outgoingMediaMultisetScriptPrefersAttributeSrcAndSkipsEmptyPendingImages() {
        val bundle = SelectorBundle.defaults()
        val script = DouyinJsScripts.buildSampleOutgoingMediaMultisetJs(bundle)

        assertTrue(script.contains("getAttribute('src')"))
        assertTrue(script.contains("pendingImgCount"))
        assertTrue(script.contains("totalBoxes"))
        assertTrue(script.contains("if (!ownContent && String(box.className || '').indexOf('isFromMe') !== -1)"))
    }

    @Test
    fun messageListSettleLogicWaitsForPendingImagesAndStablePolls() {
        val empty = DouyinWebExecutor.MediaSettleSample(
            totalBoxes = 0,
            pendingImgCount = 0,
            signatures = emptyList(),
        )
        val pending = DouyinWebExecutor.MediaSettleSample(
            totalBoxes = 3,
            pendingImgCount = 1,
            signatures = listOf("sig1"),
        )
        val settledV1 = DouyinWebExecutor.MediaSettleSample(
            totalBoxes = 3,
            pendingImgCount = 0,
            signatures = listOf("sig1"),
        )
        val settledV2 = DouyinWebExecutor.MediaSettleSample(
            totalBoxes = 4,
            pendingImgCount = 0,
            signatures = listOf("sig1", "sig2"),
        )

        // 首轮无 previous
        assertFalse(
            DouyinWebExecutor.isMessageListSettled(
                previous = null,
                current = settledV1,
                stablePolls = 1,
                elapsedMs = 900L,
                minWaitMs = 700L,
            ),
        )
        // 尚有未就绪图片 (pendingImgCount > 0)
        assertFalse(
            DouyinWebExecutor.isMessageListSettled(
                previous = pending,
                current = pending,
                stablePolls = 3,
                elapsedMs = 1_200L,
                minWaitMs = 700L,
            ),
        )
        // 签名/气泡总数发生变化
        assertFalse(
            DouyinWebExecutor.isMessageListSettled(
                previous = settledV1,
                current = settledV2,
                stablePolls = 1,
                elapsedMs = 1_200L,
                minWaitMs = 700L,
            ),
        )
        // 连续稳定次数不足 2
        assertFalse(
            DouyinWebExecutor.isMessageListSettled(
                previous = settledV1,
                current = settledV1,
                stablePolls = 1,
                elapsedMs = 1_200L,
                minWaitMs = 700L,
            ),
        )
        // 空列表未满 emptyMinWaitMs 时继续等待懒加载
        assertFalse(
            DouyinWebExecutor.isMessageListSettled(
                previous = empty,
                current = empty,
                stablePolls = 3,
                elapsedMs = 1_000L,
                minWaitMs = 700L,
            ),
        )
        // 空列表达到 emptyMinWaitMs 后放行
        assertTrue(
            DouyinWebExecutor.isMessageListSettled(
                previous = empty,
                current = empty,
                stablePolls = 3,
                elapsedMs = 1_500L,
                minWaitMs = 700L,
            ),
        )
        // 有消息列表连续稳定 2 轮且达到 minWaitMs 后放行
        assertTrue(
            DouyinWebExecutor.isMessageListSettled(
                previous = settledV2,
                current = settledV2,
                stablePolls = 2,
                elapsedMs = 700L,
                minWaitMs = 700L,
            ),
        )
    }

    @Test
    fun imageUploadProbeAndDataTransferScriptsCoverTriggerInputsAndValueTracker() {
        val bundle = SelectorBundle.defaults()
        val probeJs = DouyinJsScripts.buildProbeImageUploadDomJs(bundle)
        assertTrue(probeJs.contains(".semi-upload-add"))
        assertTrue(probeJs.contains("input[type=\\\"file\\\"]"))
        assertTrue(probeJs.contains("centerX"))
        assertTrue(probeJs.contains("centerY"))
        assertTrue(probeJs.contains("fileInputCount"))
        assertTrue(probeJs.contains("hasUploadEntry"))

        val injectJs = DouyinJsScripts.buildInjectImageDataTransferJs(
            bundle = bundle,
            imageBase64 = "AQID",
            fileName = "sample.png",
            mimeType = "image/png",
        )
        assertTrue(injectJs.contains("DataTransfer"))
        assertTrue(injectJs.contains("new File("))
        assertTrue(injectJs.contains("input.files"))
        assertTrue(injectJs.contains("_valueTracker"))
        assertTrue(injectJs.contains("setValue"))
        assertTrue(injectJs.contains("new Event('input'"))
        assertTrue(injectJs.contains("new Event('change'"))
        assertTrue(injectJs.contains("AQID"))
        assertTrue(injectJs.contains("sample.png"))
        assertTrue(injectJs.contains("image/png"))
    }

    @Test
    fun imageConfirmModalScriptsMaskHiddenModalsAndFallbackToSendCountRegex() {
        val bundle = SelectorBundle.defaults()
        val inspectJs = DouyinJsScripts.buildInspectImageConfirmModalJs(bundle)
        val clickJs = DouyinJsScripts.buildClickImageModalSendJs(bundle)
        val dismissJs = DouyinJsScripts.buildDismissImageModalJs()

        for (script in listOf(inspectJs, clickJs)) {
            assertTrue(script.contains(".semi-modal, [role=\"dialog\"]"))
            assertTrue(script.contains("__flameIsModalTrulyVisible"))
            assertTrue(script.contains("aria-hidden"))
            assertTrue(script.contains("st.display === 'none'"))
            assertTrue(script.contains("st.visibility === 'hidden'"))
            assertTrue(script.contains("st.opacity === '0'"))
            assertTrue(script.contains("__flameFindTopVisibleModal"))
            assertTrue(script.contains("cand.z > best.z"))
            assertTrue(script.contains("topModal.contains(c)"))
            assertTrue(script.contains("topModal.querySelectorAll('button, [role=\"button\"]')"))
            assertTrue(script.contains("""/^发送(?:\s*[（(]\d+[)）])?$/"""))
        }

        assertTrue(inspectJs.contains("hasImgPreview"))
        assertTrue(inspectJs.contains("sendButtonFound"))
        assertTrue(inspectJs.contains("sendButtonEnabled"))
        assertTrue(clickJs.contains("__flameDispatchClick(sendHit.el)"))
        assertTrue(clickJs.contains("send_button_disabled"))

        assertTrue(dismissJs.contains("__flameFindTopVisibleModal"))
        assertTrue(dismissJs.contains("'取消'"))
        assertTrue(dismissJs.contains("Escape"))
        assertFalse(dismissJs.contains("IMAGE_DIALOG_SEND"))
        assertFalse(dismissJs.contains("""/^发送"""))

        val sendBtnRegex = Regex("""^发送(?:\s*[（(]\d+[)）])?$""")
        assertTrue(sendBtnRegex.matches("发送"))
        assertTrue(sendBtnRegex.matches("发送（1）"))
        assertTrue(sendBtnRegex.matches("发送(1)"))
        assertTrue(sendBtnRegex.matches("发送 (12)"))
        assertFalse(sendBtnRegex.matches("取消"))
        assertFalse(sendBtnRegex.matches("发送给好友"))
        assertFalse(sendBtnRegex.matches("重新发送"))
    }

    private class InMemoryAccountDao : AccountDao {
        private val accounts = LinkedHashMap<String, AccountEntity>()

        override fun observeAccount(accountId: String): Flow<AccountEntity?> = emptyFlow()
        override fun observeAll(): Flow<List<AccountEntity>> = emptyFlow()
        override suspend fun getAccount(accountId: String): AccountEntity? = accounts[accountId]
        override suspend fun listAll(): List<AccountEntity> = accounts.values.toList()
        override suspend fun upsert(account: AccountEntity) {
            accounts[account.id] = account
        }
        override suspend fun deleteById(accountId: String) {
            accounts.remove(accountId)
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

        override suspend fun updateLastAutoScanAttemptAt(
            accountId: String,
            attemptAt: Long,
            updatedAt: Long,
        ) {
            accounts[accountId]?.let {
                accounts[accountId] = it.copy(
                    lastAutoScanAttemptAt = attemptAt,
                    updatedAt = updatedAt,
                )
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

        override fun observeJobsSince(accountId: String, sinceDate: String): Flow<List<SendJobEntity>> = emptyFlow()

        override suspend fun listAllJobs(accountId: String): List<SendJobEntity> =
            jobs.values
                .filter { it.accountId == accountId }
                .sortedWith(compareBy<SendJobEntity> { it.businessDate }.thenBy { it.createdAt })

        override suspend fun listAllAttemptsForAccount(accountId: String): List<SendAttemptEntity> {
            val accountJobIds = jobs.values.filter { it.accountId == accountId }.mapTo(HashSet()) { it.id }
            return attempts
                .filter { it.jobId in accountJobIds }
                .sortedWith(compareBy<SendAttemptEntity> { it.startedAt }.thenBy { it.attemptNo })
        }

        override suspend fun upsertJob(job: SendJobEntity) {
            jobs[job.id] = job
        }

        override suspend fun upsertAttempt(attempt: SendAttemptEntity) {
            val idx = attempts.indexOfFirst { it.id == attempt.id }
            if (idx >= 0) {
                attempts[idx] = attempt
            } else {
                attempts.add(attempt)
            }
        }

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
