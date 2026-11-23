package io.github.oinzc.flamecontractor.ui.today

import android.net.Uri
import io.github.oinzc.flamecontractor.core.notify.UrgentCategory
import io.github.oinzc.flamecontractor.core.storage.StoredTemplateImage
import io.github.oinzc.flamecontractor.core.storage.TemplateImageStorage
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.domain.AccountHealthReducer
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.execution.ExecutionLiveState
import io.github.oinzc.flamecontractor.execution.LoginMode
import io.github.oinzc.flamecontractor.scheduler.DailySchedulePlanner
import java.io.File
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {

    private val testZone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedInstant: Instant = Instant.parse("2026-11-22T12:00:00Z") // 20:00 +08:00
    private val fixedClock: Clock = Clock.fixed(fixedInstant, testZone)
    private val todayDate: String = "2026-11-22"

    private val createdScopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        createdScopes.forEach { it.cancel() }
        createdScopes.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun resolvingUnknownAsSucceededIncrementsStreakOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = FakeTodayRepository(
            initialAccount = sampleAccount(status = AccountStatus.HEALTHY, failureStreak = 2),
            initialTargets = listOf(
                sampleTarget(id = "t-1", nickname = "好友甲", streakDays = 9),
            ),
            initialJobs = listOf(
                sampleJob(
                    id = "job-unknown",
                    targetId = "t-1",
                    slotId = SendGuard.DAILY_SLOT_ID,
                    status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                    failureClass = FailureClass.UNKNOWN_OUTCOME,
                ),
            ),
        )
        val scheduler = FakeTodayScheduleGateway()
        val launcher = FakeTodayExecutionLauncher()
        val vm = createViewModel(repo, scheduler, launcher, dispatcher)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.unknownReviewJobs.size)

        vm.resolveUnknownAsSucceeded("job-unknown")
        advanceUntilIdle()

        // 幂等性校验：重复触发同一笔已终裁记录，streakDays 绝不可二次 +1
        vm.resolveUnknownAsSucceeded("job-unknown")
        advanceUntilIdle()

        val updatedTarget = repo.targets.value.single { it.id == "t-1" }
        val updatedJob = repo.jobs.value.single { it.id == "job-unknown" }
        val updatedAccount = repo.account.value!!

        assertEquals(10, updatedTarget.streakDays)
        assertNotNull(updatedTarget.lastConfirmedAt)
        assertEquals(JobStatus.SUCCEEDED, updatedJob.status)
        assertNull(updatedJob.failureClass)
        assertNotNull(updatedJob.confirmedAt)
        assertEquals(0, updatedAccount.failureStreak)
        assertEquals(1, scheduler.rescheduleCount)
        assertTrue(vm.uiState.value.unknownReviewJobs.isEmpty())
    }

    @Test
    fun resolvingUnknownAsCancelledPreventsTodayReautoSend() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val target = sampleTarget(id = "t-1", nickname = "好友乙", streakDays = 5)
        val repo = FakeTodayRepository(
            initialAccount = sampleAccount(
                status = AccountStatus.HEALTHY,
                sendWindowStart = "19:00",
                sendWindowEnd = "23:00",
            ),
            initialTargets = listOf(target),
            initialJobs = listOf(
                sampleJob(
                    id = "job-unknown",
                    targetId = "t-1",
                    slotId = SendGuard.DAILY_SLOT_ID,
                    status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                    failureClass = FailureClass.UNKNOWN_OUTCOME,
                ),
            ),
        )
        val scheduler = FakeTodayScheduleGateway()
        val launcher = FakeTodayExecutionLauncher()
        val vm = createViewModel(repo, scheduler, launcher, dispatcher)
        advanceUntilIdle()

        vm.resolveUnknownAsCancelled("job-unknown")
        advanceUntilIdle()

        val updatedTarget = repo.targets.value.single { it.id == "t-1" }
        val updatedJob = repo.jobs.value.single { it.id == "job-unknown" }
        val todayJobs = repo.jobs.value.filter { it.businessDate == todayDate }

        assertEquals(JobStatus.CANCELLED, updatedJob.status)
        assertEquals(5, updatedTarget.streakDays)
        assertFalse(SendGuard.canDispatchDaily(todayJobs))

        val eval = DailySchedulePlanner.evaluateNow(
            now = fixedInstant,
            account = repo.account.value!!,
            targets = repo.targets.value,
            todayJobs = todayJobs,
            zoneId = testZone,
        )
        assertTrue(eval.dueTargets.isEmpty())
        assertTrue(eval.pendingTargets.none { it.target.id == "t-1" })
        assertEquals(1, scheduler.rescheduleCount)

        // 首页再点击「立即执行今日续火」也不得把已取消的 daily 单重新拉起
        vm.onTriggerManualBatchClick()
        advanceUntilIdle()
        assertEquals(0, launcher.manualBatchCalls)
        assertEquals(
            ManualBatchBlockReason.ALL_DONE_OR_SKIPPED_TODAY,
            vm.uiState.value.manualBatchFeedback?.reason,
        )
    }

    @Test
    fun clearingCooldownResetsFailureStreakToZero() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = FakeTodayRepository(
            initialAccount = sampleAccount(
                status = AccountStatus.COOLDOWN,
                failureStreak = 3,
            ),
            initialTargets = listOf(sampleTarget(id = "t-1", nickname = "好友丙")),
            initialJobs = emptyList(),
        )
        val scheduler = FakeTodayScheduleGateway()
        val launcher = FakeTodayExecutionLauncher()
        val vm = createViewModel(repo, scheduler, launcher, dispatcher)
        advanceUntilIdle()

        assertEquals(AccountStatus.COOLDOWN, vm.uiState.value.account?.status)
        assertEquals(3, vm.uiState.value.account?.failureStreak)

        vm.clearCooldown()
        advanceUntilIdle()

        val recovered = repo.account.value!!
        assertEquals(AccountStatus.HEALTHY, recovered.status)
        assertEquals(0, recovered.failureStreak)
        assertEquals(AccountStatus.HEALTHY, vm.uiState.value.account?.status)
        assertEquals(0, vm.uiState.value.account?.failureStreak)
        assertEquals(1, scheduler.rescheduleCount)
        assertFalse(vm.uiState.value.showCooldownDetailDialog)
    }

    @Test
    fun reloginFromHealthyShowsKeepOrClearChoice() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = FakeTodayRepository(
            initialAccount = sampleAccount(status = AccountStatus.HEALTHY),
        )
        val scheduler = FakeTodayScheduleGateway()
        val launcher = FakeTodayExecutionLauncher()
        val vm = createViewModel(repo, scheduler, launcher, dispatcher)
        val effects = mutableListOf<TodayUiEffect>()
        val collectJob = launch(dispatcher) {
            vm.effects.collect { effects += it }
        }
        advanceUntilIdle()

        // 健康状态点击 [扫码/重登] 时必须先弹二选一，不得直接启动登录或清 Cookie
        vm.onReloginClick()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.showReloginChoiceDialog)
        assertTrue(effects.isEmpty())

        // 选择「保留登录态排查」
        vm.chooseReloginPreserveSession()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showReloginChoiceDialog)
        assertEquals(
            TodayUiEffect.OpenLogin(LoginMode.PRESERVE_SESSION, DEFAULT_ACCOUNT_ID),
            effects.last(),
        )

        // 再次点击后选择「退出并清理 Cookie 重新扫码」
        vm.onReloginClick()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showReloginChoiceDialog)

        vm.chooseReloginClearAndRelogin()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showReloginChoiceDialog)
        assertEquals(
            TodayUiEffect.OpenLogin(LoginMode.CLEAR_AND_RELOGIN, DEFAULT_ACCOUNT_ID),
            effects.last(),
        )
        collectJob.cancel()
    }

    @Test
    fun verificationRequiredReloginUsesInspectOrVerifyWithoutClearingCookies() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = FakeTodayRepository(
            initialAccount = sampleAccount(status = AccountStatus.VERIFICATION_REQUIRED),
        )
        val vm = createViewModel(repo, FakeTodayScheduleGateway(), FakeTodayExecutionLauncher(), dispatcher)
        val effects = mutableListOf<TodayUiEffect>()
        val collectJob = launch(dispatcher) {
            vm.effects.collect { effects += it }
        }
        advanceUntilIdle()

        vm.onReloginClick()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showReloginChoiceDialog)
        assertEquals(
            TodayUiEffect.OpenLogin(LoginMode.INSPECT_OR_VERIFY, DEFAULT_ACCOUNT_ID),
            effects.single(),
        )
        collectJob.cancel()
    }

    @Test
    fun authExpiredAndPendingLoginReloginUsePreserveSession() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = FakeTodayRepository(
            initialAccount = sampleAccount(status = AccountStatus.AUTH_EXPIRED),
        )
        val vm = createViewModel(repo, FakeTodayScheduleGateway(), FakeTodayExecutionLauncher(), dispatcher)
        val effects = mutableListOf<TodayUiEffect>()
        val collectJob = launch(dispatcher) {
            vm.effects.collect { effects += it }
        }
        advanceUntilIdle()

        vm.onReloginClick()
        advanceUntilIdle()
        assertEquals(
            TodayUiEffect.OpenLogin(LoginMode.PRESERVE_SESSION, DEFAULT_ACCOUNT_ID),
            effects.last(),
        )

        repo.account.value = sampleAccount(status = AccountStatus.PENDING_LOGIN)
        advanceUntilIdle()

        vm.onReloginClick()
        advanceUntilIdle()
        assertEquals(
            TodayUiEffect.OpenLogin(LoginMode.PRESERVE_SESSION, DEFAULT_ACCOUNT_ID),
            effects.last(),
        )
        assertFalse(vm.uiState.value.showReloginChoiceDialog)
        collectJob.cancel()
    }

    @Test
    fun manualBatchHardBlocksWhenUnknownOrInflightExistsToday() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = FakeTodayRepository(
            initialAccount = sampleAccount(status = AccountStatus.HEALTHY),
            initialTargets = listOf(
                sampleTarget(id = "t-1", nickname = "好友一"),
                sampleTarget(id = "t-2", nickname = "好友二"),
            ),
            initialJobs = listOf(
                sampleJob(
                    id = "job-unknown",
                    targetId = "t-1",
                    slotId = SendGuard.manualSlotId(1_700_000_000_000L),
                    status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                    failureClass = FailureClass.UNKNOWN_OUTCOME,
                ),
            ),
        )
        val launcher = FakeTodayExecutionLauncher()
        val vm = createViewModel(repo, FakeTodayScheduleGateway(), launcher, dispatcher)
        advanceUntilIdle()

        vm.onTriggerManualBatchClick()
        advanceUntilIdle()

        assertEquals(0, launcher.manualBatchCalls)
        val feedback = vm.uiState.value.manualBatchFeedback
        assertNotNull(feedback)
        assertEquals(ManualBatchBlockReason.UNKNOWN_OR_INFLIGHT, feedback?.reason)
        assertTrue(feedback?.canJumpToUnknownReview == true)
    }

    @Test
    fun urgentCategoryNotificationOpensUnknownReviewDialogOnceDataLoaded() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = FakeTodayRepository(
            initialAccount = sampleAccount(status = AccountStatus.HEALTHY),
            initialTargets = listOf(sampleTarget(id = "t-1", nickname = "好友丁")),
            initialJobs = listOf(
                sampleJob(
                    id = "job-unknown",
                    targetId = "t-1",
                    slotId = SendGuard.DAILY_SLOT_ID,
                    status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                    failureClass = FailureClass.UNKNOWN_OUTCOME,
                ),
            ),
            initialAttempts = listOf(
                SendAttemptEntity(
                    id = "att-1",
                    jobId = "job-unknown",
                    attemptNo = 1,
                    stage = "confirming",
                    status = AttemptStatus.FAILED,
                    failureClass = FailureClass.UNKNOWN_OUTCOME,
                    inputMethodUsed = "js_execCommand",
                    startedAt = 100L,
                    finishedAt = 200L,
                ),
            ),
        )
        val vm = createViewModel(repo, FakeTodayScheduleGateway(), FakeTodayExecutionLauncher(), dispatcher)

        vm.handleUrgentCategory(UrgentCategory.UNKNOWN_REQUIRES_REVIEW.name)
        advanceUntilIdle()

        assertEquals("job-unknown", vm.uiState.value.activeUnknownReviewJob?.job?.id)

        vm.toggleJobExpanded("job-unknown")
        advanceUntilIdle()

        val recent = vm.uiState.value.recentJobs.single { it.job.id == "job-unknown" }
        assertTrue(recent.expanded)
        assertTrue(recent.attemptsLoaded)
        assertEquals(1, recent.attempts.size)
        assertEquals("js_execCommand", recent.attempts.single().inputMethodUsed)

        // 关闭弹窗后，同一 signalNonce 切换 Tab 回来不再重复弹出；新 nonce 可再次触发
        vm.handleUrgentCategory(UrgentCategory.UNKNOWN_REQUIRES_REVIEW.name, signalNonce = 1L)
        advanceUntilIdle()
        assertEquals("job-unknown", vm.uiState.value.activeUnknownReviewJob?.job?.id)

        vm.dismissUnknownReview()
        advanceUntilIdle()
        assertNull(vm.uiState.value.activeUnknownReviewJob)

        vm.handleUrgentCategory(UrgentCategory.UNKNOWN_REQUIRES_REVIEW.name, signalNonce = 1L)
        advanceUntilIdle()
        assertNull(vm.uiState.value.activeUnknownReviewJob)

        vm.handleUrgentCategory(UrgentCategory.UNKNOWN_REQUIRES_REVIEW.name, signalNonce = 2L)
        advanceUntilIdle()
        assertEquals("job-unknown", vm.uiState.value.activeUnknownReviewJob?.job?.id)
    }

    @Test
    fun manualBatchEnqueuesWhenHealthyWithPendingTargetsAndNoBlockingJobs() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = FakeTodayRepository(
            initialAccount = sampleAccount(status = AccountStatus.HEALTHY),
            initialTargets = listOf(sampleTarget(id = "t-1", nickname = "好友戊")),
            initialJobs = emptyList(),
        )
        val launcher = FakeTodayExecutionLauncher()
        val vm = createViewModel(repo, FakeTodayScheduleGateway(), launcher, dispatcher)
        advanceUntilIdle()

        vm.onTriggerManualBatchClick()
        advanceUntilIdle()

        assertEquals(1, launcher.manualBatchCalls)
        assertNull(vm.uiState.value.manualBatchFeedback)
    }

    @Test
    fun otherRunningAccountShowsGentleBannerAndHidesForeignExecutionProgress() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val liveState = MutableStateFlow(
            ExecutionLiveState(
                running = true,
                activeAccountId = "acct-2",
                currentTask = "批量发火",
                lastMessage = "小号进度消息",
            ),
        )
        val repo = FakeTodayRepository(
            initialAccount = sampleAccount(),
            extraAccounts = listOf(
                AccountEntity(
                    id = "acct-2",
                    displayName = "小号二",
                    status = AccountStatus.HEALTHY,
                    timezone = testZone.id,
                ),
            ),
        )
        val vm = createViewModel(
            repo = repo,
            scheduler = FakeTodayScheduleGateway(),
            launcher = FakeTodayExecutionLauncher(),
            dispatcher = dispatcher,
            liveState = liveState,
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("小号二", state.otherRunningAccountName)
        assertEquals("批量发火", state.otherRunningTaskLabel)
        assertFalse(state.executionState.running)
        assertNull(state.executionState.lastMessage)
    }

    @Test
    fun missingImageTemplateAndMultiAccountIsolationAreTrackedInTodayState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val imageStore = FakeTodayImageStorage(existingPaths = mutableSetOf("images/ok.jpg"))
        val acct1 = sampleAccount()
        val acct2 = AccountEntity(
            id = "acct-2",
            displayName = "工作抖音号",
            status = AccountStatus.HEALTHY,
            timezone = testZone.id,
        )
        val missingTemplate = MessageTemplateEntity(
            id = "tpl-img-missing",
            accountId = "acct-2",
            name = "早安图",
            type = TemplateType.IMAGE,
            imagePath = "images/missing.jpg",
        )
        val repo = FakeTodayRepository(
            initialAccount = acct1,
            extraAccounts = listOf(acct2),
            initialTargets = listOf(
                sampleTarget(id = "t-1", nickname = "默认好友", templateId = "tpl-1"),
                ContractTargetEntity(
                    id = "t-2",
                    accountId = "acct-2",
                    nicknameSnapshot = "二号好友",
                    platformUserId = "sec_2",
                    conversationType = "friend",
                    templateId = missingTemplate.id,
                    enabled = true,
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                ),
            ),
            initialTemplates = listOf(
                MessageTemplateEntity(
                    id = "tpl-1",
                    accountId = DEFAULT_ACCOUNT_ID,
                    name = "比心",
                    type = TemplateType.STICKER,
                ),
                missingTemplate,
            ),
            initialJobs = listOf(
                sampleJob(id = "j-1", targetId = "t-1", slotId = SendGuard.DAILY_SLOT_ID, status = JobStatus.SUCCEEDED),
                SendJobEntity(
                    id = "j-2",
                    accountId = "acct-2",
                    targetId = "t-2",
                    templateId = missingTemplate.id,
                    businessDate = todayDate,
                    messageSlotId = SendGuard.DAILY_SLOT_ID,
                    status = JobStatus.FAILED_TERMINAL,
                    failureClass = FailureClass.DOM_CHANGED,
                    createdAt = 2_000L,
                    updatedAt = 2_000L,
                ),
            ),
        )
        val vm = createViewModel(
            repo = repo,
            scheduler = FakeTodayScheduleGateway(),
            launcher = FakeTodayExecutionLauncher(),
            dispatcher = dispatcher,
            accountId = "acct-2",
            imageStore = imageStore,
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("acct-2", state.account?.id)
        assertEquals(1, state.enabledTargetCount)
        assertEquals(1, state.missingImageTemplateCount)
        assertEquals(1, state.missingImageEnabledTargetCount)
        assertEquals(1, state.recentJobs.size)
        assertEquals("j-2", state.recentJobs.first().job.id)
        assertTrue(state.recentJobs.first().isImageMissing)
        assertTrue(state.recentJobs.first().templateLabel.contains("待补图"))
    }

    @Test
    fun emptyTargetsProducesZeroMaxLocalStreakAndPausedCount() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeTodayRepository(
            initialAccount = sampleAccount(status = AccountStatus.HEALTHY),
            initialTargets = emptyList(),
        )
        val vm = createViewModel(
            repo = repo,
            scheduler = FakeTodayScheduleGateway(),
            launcher = FakeTodayExecutionLauncher(),
            dispatcher = dispatcher,
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(0, state.enabledTargetCount)
        assertEquals(0, state.pausedTargetCount)
        assertEquals(0, state.maxLocalStreakDays)
    }

    @Test
    fun maxLocalStreakIncludesPausedTargetsAndExcludesSoftDeleted() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = FakeTodayRepository(
            initialAccount = sampleAccount(status = AccountStatus.HEALTHY),
            initialTargets = listOf(
                sampleTarget(id = "t-enabled", nickname = "启用好友", streakDays = 19, enabled = true),
                sampleTarget(id = "t-paused", nickname = "暂停好友", streakDays = 42, enabled = false),
                sampleTarget(
                    id = "t-deleted",
                    nickname = "已移除好友",
                    streakDays = 99,
                    enabled = false,
                    deletedAt = 5_000L,
                ),
            ),
        )
        val vm = createViewModel(
            repo = repo,
            scheduler = FakeTodayScheduleGateway(),
            launcher = FakeTodayExecutionLauncher(),
            dispatcher = dispatcher,
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, state.enabledTargetCount)
        assertEquals(1, state.pausedTargetCount)
        assertEquals(42, state.maxLocalStreakDays)
    }

    private fun createViewModel(
        repo: FakeTodayRepository,
        scheduler: FakeTodayScheduleGateway,
        launcher: FakeTodayExecutionLauncher,
        dispatcher: CoroutineDispatcher,
        liveState: MutableStateFlow<ExecutionLiveState> = MutableStateFlow(ExecutionLiveState()),
        accountId: String = DEFAULT_ACCOUNT_ID,
        imageStore: TemplateImageStorage? = null,
    ): TodayViewModel {
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        createdScopes += vmScope
        return TodayViewModel(
            repository = repo,
            scheduleGateway = scheduler,
            executionLauncher = launcher,
            executionStateFlow = liveState,
            accountId = accountId,
            imageStore = imageStore,
            clock = fixedClock,
            ioDispatcher = dispatcher,
            customScope = vmScope,
        )
    }

    private fun sampleAccount(
        status: AccountStatus = AccountStatus.HEALTHY,
        failureStreak: Int = 0,
        sendWindowStart: String = "20:00",
        sendWindowEnd: String = "22:00",
    ): AccountEntity = AccountEntity(
        id = DEFAULT_ACCOUNT_ID,
        displayName = "默认抖音号",
        status = status,
        enabled = true,
        timezone = testZone.id,
        sendWindowStart = sendWindowStart,
        sendWindowEnd = sendWindowEnd,
        failureStreak = failureStreak,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun sampleTarget(
        id: String,
        nickname: String,
        streakDays: Int = 0,
        templateId: String = "tpl-1",
        enabled: Boolean = true,
        deletedAt: Long? = null,
    ): ContractTargetEntity = ContractTargetEntity(
        id = id,
        accountId = DEFAULT_ACCOUNT_ID,
        nicknameSnapshot = nickname,
        platformUserId = "sec_$id",
        conversationType = "friend",
        templateId = templateId,
        enabled = enabled,
        streakDays = streakDays,
        deletedAt = deletedAt,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun sampleJob(
        id: String,
        targetId: String,
        slotId: String,
        status: JobStatus,
        failureClass: FailureClass? = null,
    ): SendJobEntity = SendJobEntity(
        id = id,
        accountId = DEFAULT_ACCOUNT_ID,
        targetId = targetId,
        templateId = "tpl-1",
        businessDate = todayDate,
        messageSlotId = slotId,
        status = status,
        attemptCount = 1,
        failureClass = failureClass,
        createdAt = 2_000L,
        updatedAt = 2_000L,
    )

    private class FakeTodayScheduleGateway : TodayScheduleGateway {
        var rescheduleCount = 0
        override suspend fun rescheduleAll(now: Instant): Instant? {
            rescheduleCount += 1
            return null
        }
    }

    private class FakeTodayExecutionLauncher : TodayExecutionLauncher {
        var manualBatchCalls = 0
        var returnValue = true
        override fun enqueueManualBatch(accountId: String): Boolean {
            manualBatchCalls += 1
            return returnValue
        }
    }

    private class FakeTodayImageStorage(
        private val existingPaths: MutableSet<String> = mutableSetOf(),
    ) : TemplateImageStorage {
        override fun resolveExistingFile(storedPath: String?): File? =
            storedPath?.takeIf { it in existingPaths }?.let { File(it) }

        override suspend fun importFromStream(input: InputStream, mimeTypeHint: String?): StoredTemplateImage =
            StoredTemplateImage("images/fake.jpg", mimeTypeHint ?: "image/jpeg", 100L, 100, 100)

        override fun deleteIfUnreferenced(storedPath: String?, referencedPaths: Set<String>): Boolean = false

        override fun contentUriFor(file: File): Uri? = null
    }

    private class FakeTodayRepository(
        initialAccount: AccountEntity? = null,
        extraAccounts: List<AccountEntity> = emptyList(),
        initialTargets: List<ContractTargetEntity> = emptyList(),
        initialJobs: List<SendJobEntity> = emptyList(),
        initialAttempts: List<SendAttemptEntity> = emptyList(),
        initialTemplates: List<MessageTemplateEntity> = listOf(
            MessageTemplateEntity(
                id = "tpl-1",
                accountId = DEFAULT_ACCOUNT_ID,
                name = "比心",
                type = TemplateType.STICKER,
                stickerName = "比心",
                stickerLabel = "比心",
            ),
        ),
    ) : TodayRepository {
        val account = MutableStateFlow(initialAccount)
        val extraAccountsState = MutableStateFlow(extraAccounts)
        val targets = MutableStateFlow(initialTargets)
        val jobs = MutableStateFlow(initialJobs)
        val attempts = MutableStateFlow(initialAttempts)
        val templates = MutableStateFlow(initialTemplates)

        override fun observeAccount(accountId: String): Flow<AccountEntity?> =
            combine(account, extraAccountsState) { primary, extras ->
                if (primary?.id == accountId) primary else extras.firstOrNull { it.id == accountId }
            }

        override fun observeAllAccounts(): Flow<List<AccountEntity>> =
            combine(account, extraAccountsState) { primary, extras ->
                listOfNotNull(primary) + extras.filter { it.id != primary?.id }
            }

        override fun observeActiveTargets(accountId: String): Flow<List<ContractTargetEntity>> =
            targets.map { list -> list.filter { it.accountId == accountId && it.deletedAt == null } }

        override fun observeJobsForDate(accountId: String, businessDate: String): Flow<List<SendJobEntity>> =
            jobs.map { list -> list.filter { it.accountId == accountId && it.businessDate == businessDate } }

        override fun observeJobsByStatus(accountId: String, status: JobStatus): Flow<List<SendJobEntity>> =
            jobs.map { list -> list.filter { it.accountId == accountId && it.status == status } }

        override fun observeRecentJobs(accountId: String, limit: Int): Flow<List<SendJobEntity>> =
            jobs.map { list ->
                list.filter { it.accountId == accountId }
                    .sortedByDescending { it.createdAt }
                    .take(limit)
            }

        override fun observeTemplates(accountId: String): Flow<List<MessageTemplateEntity>> =
            templates.map { list -> list.filter { it.accountId == accountId } }

        override suspend fun listAttemptsForJob(jobId: String): List<SendAttemptEntity> =
            attempts.value.filter { it.jobId == jobId }.sortedBy { it.attemptNo }

        override suspend fun resolveUnknownJobAsSucceeded(jobId: String, nowEpochMs: Long): Boolean {
            val currentJobs = jobs.value
            val job = currentJobs.firstOrNull { it.id == jobId } ?: return false
            if (job.status != JobStatus.UNKNOWN_REQUIRES_REVIEW) return false

            jobs.value = currentJobs.map {
                if (it.id == jobId) {
                    it.copy(
                        status = JobStatus.SUCCEEDED,
                        failureClass = null,
                        summaryDetail = "人工核对确认已在抖音发出",
                        confirmedAt = nowEpochMs,
                        updatedAt = nowEpochMs,
                    )
                } else {
                    it
                }
            }
            targets.value = targets.value.map {
                if (it.id == job.targetId) {
                    it.copy(
                        streakDays = it.streakDays + 1,
                        lastSentAt = nowEpochMs,
                        lastConfirmedAt = nowEpochMs,
                        updatedAt = nowEpochMs,
                    )
                } else {
                    it
                }
            }
            account.value?.let { acc ->
                account.value = AccountHealthReducer.onSingleSendSucceeded(acc, nowEpochMs)
            }
            return true
        }

        override suspend fun resolveUnknownJobAsCancelled(jobId: String, nowEpochMs: Long): Boolean {
            val currentJobs = jobs.value
            val job = currentJobs.firstOrNull { it.id == jobId } ?: return false
            if (job.status != JobStatus.UNKNOWN_REQUIRES_REVIEW) return false

            jobs.value = currentJobs.map {
                if (it.id == jobId) {
                    it.copy(
                        status = JobStatus.CANCELLED,
                        failureClass = null,
                        summaryDetail = "人工核对确认未发出，今日已取消不再自动发送",
                        confirmedAt = null,
                        updatedAt = nowEpochMs,
                    )
                } else {
                    it
                }
            }
            return true
        }

        override suspend fun clearAccountCooldown(accountId: String, nowEpochMs: Long): AccountEntity? {
            val current = account.value ?: return null
            if (current.id != accountId) return null
            val recovered = AccountHealthReducer.recoverFromCooldown(current, nowEpochMs)
            account.value = recovered
            return recovered
        }
    }
}
