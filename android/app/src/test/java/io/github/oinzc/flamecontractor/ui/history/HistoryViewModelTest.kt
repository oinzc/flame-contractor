package io.github.oinzc.flamecontractor.ui.history

import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.domain.SendGuard
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
class HistoryViewModelTest {

    private val testZone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedInstant: Instant = Instant.parse("2026-11-22T12:00:00Z") // 2026-11-22 20:00 +08:00
    private val fixedClock: Clock = Clock.fixed(fixedInstant, testZone)
    private val today: LocalDate = LocalDate.of(2026, 11, 22)

    private val createdScopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        createdScopes.forEach { it.cancel() }
        createdScopes.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun initialDefaultWindowIs30DaysAndComputesKpiAnd30DailyBuckets() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = FakeHistoryRepository(
            initialTargets = listOf(sampleTarget(id = "t-1", nickname = "好友甲")),
            initialJobs = listOf(
                sampleJob(id = "j-2", targetId = "t-1", businessDate = today.minusDays(2), status = JobStatus.SUCCEEDED),
                sampleJob(id = "j-1", targetId = "t-1", businessDate = today.minusDays(1), status = JobStatus.SUCCEEDED),
                sampleJob(
                    id = "j-0",
                    targetId = "t-1",
                    businessDate = today,
                    status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                    failureClass = FailureClass.UNKNOWN_OUTCOME,
                ),
            ),
        )

        val vm = createViewModel(repo, dispatcher)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(30, state.windowDays)
        assertEquals(30, state.summary.daily.size)
        assertEquals(listOf(today.minusDays(29).toString()), repo.observedSinceDates)
        assertEquals(3, state.summary.totalJobs)
        assertEquals(2, state.summary.succeededJobs)
        assertEquals(1, state.summary.unknownJobs)
        assertEquals(0, state.summary.currentSuccessStreak)
        assertEquals(2, state.summary.bestSuccessStreak)
        assertEquals(3, state.filteredJobs.size)
    }

    @Test
    fun setWindowDays7SwitchesWindowAndRequeriesRepository() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = FakeHistoryRepository(
            initialTargets = listOf(sampleTarget(id = "t-1", nickname = "好友甲")),
            initialJobs = listOf(
                sampleJob(id = "j-old", targetId = "t-1", businessDate = today.minusDays(15), status = JobStatus.SUCCEEDED),
                sampleJob(id = "j-recent", targetId = "t-1", businessDate = today.minusDays(2), status = JobStatus.SUCCEEDED),
            ),
        )

        val vm = createViewModel(repo, dispatcher)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.filteredJobs.size)

        // 先在 30 日模式下选中第 15 天前日期，再切 7 天应自动清除越界选中日
        vm.toggleSelectedDate(today.minusDays(15))
        advanceUntilIdle()
        assertEquals(today.minusDays(15), vm.uiState.value.selectedDate)

        vm.setWindowDays(7)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(7, state.windowDays)
        assertEquals(7, state.summary.daily.size)
        assertNull(state.selectedDate)
        assertEquals(
            listOf(today.minusDays(29).toString(), today.minusDays(6).toString()),
            repo.observedSinceDates,
        )
        assertEquals(1, state.summary.totalJobs)
        assertEquals(listOf("j-recent"), state.filteredJobs.map { it.job.id })
    }

    @Test
    fun statusFilterAndToggleSelectedDateFilterJobListAccurately() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val dayA = today.minusDays(1)
        val dayB = today
        val repo = FakeHistoryRepository(
            initialTargets = listOf(sampleTarget(id = "t-1", nickname = "好友乙")),
            initialJobs = listOf(
                sampleJob(id = "j-ok", targetId = "t-1", businessDate = dayA, status = JobStatus.SUCCEEDED),
                sampleJob(
                    id = "j-fail-retry",
                    targetId = "t-1",
                    businessDate = dayA,
                    status = JobStatus.FAILED_RETRYABLE,
                    failureClass = FailureClass.NETWORK,
                ),
                sampleJob(
                    id = "j-fail-term",
                    targetId = "t-1",
                    businessDate = dayB,
                    status = JobStatus.FAILED_TERMINAL,
                    failureClass = FailureClass.DOM_CHANGED,
                ),
                sampleJob(
                    id = "j-unk",
                    targetId = "t-1",
                    businessDate = dayB,
                    status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                    failureClass = FailureClass.UNKNOWN_OUTCOME,
                ),
                sampleJob(
                    id = "j-cancel",
                    targetId = "t-1",
                    businessDate = dayB,
                    status = JobStatus.CANCELLED,
                ),
            ),
        )

        val vm = createViewModel(repo, dispatcher)
        advanceUntilIdle()

        vm.setStatusFilter(HistoryStatusFilter.FAILED)
        advanceUntilIdle()
        assertEquals(setOf("j-fail-retry", "j-fail-term"), vm.uiState.value.filteredJobs.map { it.job.id }.toSet())

        vm.setStatusFilter(HistoryStatusFilter.UNKNOWN)
        advanceUntilIdle()
        assertEquals(listOf("j-unk"), vm.uiState.value.filteredJobs.map { it.job.id })

        vm.setStatusFilter(HistoryStatusFilter.CANCELLED)
        advanceUntilIdle()
        assertEquals(listOf("j-cancel"), vm.uiState.value.filteredJobs.map { it.job.id })

        vm.setStatusFilter(HistoryStatusFilter.ALL)
        vm.toggleSelectedDate(dayA)
        advanceUntilIdle()
        assertEquals(dayA, vm.uiState.value.selectedDate)
        assertEquals(setOf("j-ok", "j-fail-retry"), vm.uiState.value.filteredJobs.map { it.job.id }.toSet())

        // 再次点选同一天，应取消日期筛选
        vm.toggleSelectedDate(dayA)
        advanceUntilIdle()
        assertNull(vm.uiState.value.selectedDate)
        assertEquals(5, vm.uiState.value.filteredJobs.size)
    }

    @Test
    fun softDeletedTargetStillDisplaysNicknameSnapshotWithDeletedFlag() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = FakeHistoryRepository(
            initialTargets = listOf(
                sampleTarget(id = "t-active", nickname = "活跃好友", deletedAt = null),
                sampleTarget(id = "t-deleted", nickname = "已删除好友", deletedAt = 1_700_000_000_000L),
            ),
            initialJobs = listOf(
                sampleJob(
                    id = "j-active",
                    targetId = "t-active",
                    businessDate = today,
                    slotId = SendGuard.DAILY_SLOT_ID,
                    status = JobStatus.SUCCEEDED,
                ),
                sampleJob(
                    id = "j-deleted",
                    targetId = "t-deleted",
                    businessDate = today.minusDays(1),
                    slotId = SendGuard.manualSlotId(1_700_000_000_123L),
                    status = JobStatus.SUCCEEDED,
                ),
            ),
        )

        val vm = createViewModel(repo, dispatcher)
        advanceUntilIdle()

        val itemsById = vm.uiState.value.filteredJobs.associateBy { it.job.id }
        val activeItem = itemsById.getValue("j-active")
        val deletedItem = itemsById.getValue("j-deleted")

        assertEquals("活跃好友", activeItem.targetName)
        assertFalse(activeItem.targetDeleted)
        assertTrue(activeItem.isDailySlot)

        assertEquals("已删除好友", deletedItem.targetName)
        assertTrue(deletedItem.targetDeleted)
        assertFalse(deletedItem.isDailySlot)
    }

    @Test
    fun selectJobLoadsAttemptsTimelineDismissClearsAndBuildExportCsvIncludesAllJobs() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = FakeHistoryRepository(
            initialTargets = listOf(sampleTarget(id = "t-1", nickname = "敏感昵称不应导出")),
            initialJobs = listOf(
                sampleJob(
                    id = "j-ancient",
                    targetId = "t-1",
                    businessDate = today.minusDays(90),
                    status = JobStatus.SUCCEEDED,
                    summaryDetail = "包含敏感摘要不应导出",
                ),
                sampleJob(
                    id = "j-today",
                    targetId = "t-1",
                    businessDate = today,
                    status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                    attemptCount = 2,
                    failureClass = FailureClass.UNKNOWN_OUTCOME,
                    summaryDetail = "发信后未确认回执",
                ),
            ),
            initialAttempts = listOf(
                SendAttemptEntity(
                    id = "att-2",
                    jobId = "j-today",
                    attemptNo = 2,
                    stage = "confirming",
                    status = AttemptStatus.FAILED,
                    failureClass = FailureClass.UNKNOWN_OUTCOME,
                    inputMethodUsed = "sticker_click",
                    errorCode = "CONFIRM_TIMEOUT",
                    startedAt = 20_000L,
                    finishedAt = 24_000L,
                ),
                SendAttemptEntity(
                    id = "att-1",
                    jobId = "j-today",
                    attemptNo = 1,
                    stage = "sending",
                    status = AttemptStatus.FAILED,
                    failureClass = FailureClass.NETWORK,
                    inputMethodUsed = "js_execCommand",
                    errorCode = "NET_RESET",
                    startedAt = 10_000L,
                    finishedAt = 12_000L,
                ),
            ),
        )

        val vm = createViewModel(repo, dispatcher)
        advanceUntilIdle()

        // 30 日视图仅展示 j-today，但 buildExportCsv 应导出全量历史（含 90 天前 j-ancient）
        assertEquals(listOf("j-today"), vm.uiState.value.filteredJobs.map { it.job.id })

        vm.selectJob("j-today")
        advanceUntilIdle()

        val detail = vm.uiState.value.selectedJobDetail
        assertNotNull(detail)
        assertFalse(detail!!.isLoadingAttempts)
        assertEquals(listOf(1, 2), detail.attempts.map { it.attemptNo })
        assertEquals("NET_RESET", detail.attempts.first().errorCode)

        vm.dismissJobDetail()
        advanceUntilIdle()
        assertNull(vm.uiState.value.selectedJobDetail)

        val csv = vm.buildExportCsv()
        assertTrue(csv.startsWith("business_date,account_id,target_id,status,failure_class,attempt_count,scheduled_at,confirmed_at,message_slot_id"))
        assertTrue(csv.contains(today.minusDays(90).toString()))
        assertTrue(csv.contains(today.toString()))
        assertFalse(csv.contains("敏感昵称不应导出"))
        assertFalse(csv.contains("包含敏感摘要不应导出"))
    }

    @Test
    fun filtersJobsTargetsAndExportCsvStrictlyByBoundAccountId() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val acct2 = "acct-2"
        val repo = FakeHistoryRepository(
            initialTargets = listOf(
                sampleTarget(id = "t-default", nickname = "默认好友"),
                sampleTarget(id = "t-acct2", nickname = "二号好友").copy(accountId = acct2),
            ),
            initialJobs = listOf(
                sampleJob(id = "job-default", targetId = "t-default", businessDate = today, status = JobStatus.SUCCEEDED),
                sampleJob(id = "job-acct2", targetId = "t-acct2", businessDate = today, status = JobStatus.SUCCEEDED)
                    .copy(accountId = acct2),
            ),
        )
        val vm = createViewModel(repo = repo, dispatcher = dispatcher, accountId = acct2)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, state.totalJobsInWindow)
        assertEquals("job-acct2", state.filteredJobs.single().job.id)
        assertEquals("二号好友", state.filteredJobs.single().targetName)

        val csv = vm.buildExportCsv()
        assertTrue(csv.contains(",$acct2,t-acct2,"))
        assertFalse(csv.contains(",$DEFAULT_ACCOUNT_ID,t-default,"))
    }

    private fun createViewModel(
        repo: FakeHistoryRepository,
        dispatcher: CoroutineDispatcher,
        accountId: String = DEFAULT_ACCOUNT_ID,
    ): HistoryViewModel {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        createdScopes += scope
        return HistoryViewModel(
            repository = repo,
            accountId = accountId,
            clock = fixedClock,
            ioDispatcher = dispatcher,
            customScope = scope,
        )
    }

    private fun sampleTarget(
        id: String,
        nickname: String,
        deletedAt: Long? = null,
    ): ContractTargetEntity = ContractTargetEntity(
        id = id,
        accountId = DEFAULT_ACCOUNT_ID,
        nicknameSnapshot = nickname,
        deletedAt = deletedAt,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun sampleJob(
        id: String,
        targetId: String,
        businessDate: LocalDate,
        status: JobStatus,
        slotId: String = SendGuard.DAILY_SLOT_ID,
        attemptCount: Int = 1,
        failureClass: FailureClass? = null,
        summaryDetail: String? = null,
    ): SendJobEntity = SendJobEntity(
        id = id,
        accountId = DEFAULT_ACCOUNT_ID,
        targetId = targetId,
        templateId = "tpl-1",
        businessDate = businessDate.toString(),
        messageSlotId = slotId,
        status = status,
        attemptCount = attemptCount,
        failureClass = failureClass,
        summaryDetail = summaryDetail,
        createdAt = 2_000L,
        updatedAt = 2_000L,
    )

    private class FakeHistoryRepository(
        initialTargets: List<ContractTargetEntity> = emptyList(),
        initialJobs: List<SendJobEntity> = emptyList(),
        initialAttempts: List<SendAttemptEntity> = emptyList(),
    ) : HistoryRepository {
        val targets = MutableStateFlow(initialTargets)
        val jobs = MutableStateFlow(initialJobs)
        val attempts = MutableStateFlow(initialAttempts)
        val observedSinceDates = mutableListOf<String>()

        override fun observeJobsSince(accountId: String, sinceDate: String): Flow<List<SendJobEntity>> {
            observedSinceDates += sinceDate
            return jobs.map { list ->
                list.filter { it.accountId == accountId && it.businessDate >= sinceDate }
                    .sortedWith(compareByDescending<SendJobEntity> { it.businessDate }.thenByDescending { it.createdAt })
            }
        }

        override suspend fun listAllTargetsIncludingDeleted(accountId: String): List<ContractTargetEntity> =
            targets.value.filter { it.accountId == accountId }

        override suspend fun listAllJobs(accountId: String): List<SendJobEntity> =
            jobs.value.filter { it.accountId == accountId }
                .sortedWith(compareBy<SendJobEntity> { it.businessDate }.thenBy { it.createdAt })

        override suspend fun listAttemptsForJob(jobId: String): List<SendAttemptEntity> =
            attempts.value.filter { it.jobId == jobId }.sortedBy { it.attemptNo }
    }
}
