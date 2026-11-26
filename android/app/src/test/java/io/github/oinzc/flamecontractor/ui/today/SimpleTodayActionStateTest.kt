package io.github.oinzc.flamecontractor.ui.today

import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.execution.ExecutionLiveState
import java.time.Instant
import java.time.ZonedDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleTodayActionStateTest {

    @Test
    fun `resolves Executing state when execution is running or account status is RUNNING`() {
        val runningState = baseHealthyState().copy(
            executionState = ExecutionLiveState(
                running = true,
                currentTask = "正在给小红续火",
                progressCurrent = 2,
                progressTotal = 5,
            ),
        )
        val result = resolveSimpleActionState(runningState)
        assertEquals(
            SimpleActionState.Executing(
                progressCurrent = 2,
                progressTotal = 5,
                progressText = "2 / 5",
            ),
            result,
        )

        val accountRunningFallback = baseHealthyState().copy(
            account = baseAccount(status = AccountStatus.RUNNING),
            executionState = ExecutionLiveState(running = false, currentTask = "准备打开会话"),
        )
        assertEquals(
            SimpleActionState.Executing(
                progressCurrent = 0,
                progressTotal = 0,
                progressText = "准备打开会话",
            ),
            resolveSimpleActionState(accountRunningFallback),
        )

        val inflightBlockingState = baseHealthyState().copy(
            hasBlockingTodayJob = true,
            unknownReviewJobs = emptyList(),
        )
        val inflightResult = resolveSimpleActionState(inflightBlockingState)
        assertTrue(inflightResult is SimpleActionState.Executing)
    }

    @Test
    fun `resolves NeedsUnknownReview before any send or guided action to preserve safety invariant`() {
        val unknownJob = UnknownJobReviewItem(
            job = SendJobEntity(
                id = "job_unknown_1",
                accountId = "default",
                targetId = "t1",
                businessDate = "2026-04-12",
                messageSlotId = SendGuard.DAILY_SLOT_ID,
                templateId = "tpl_1",
                status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                attemptCount = 1,
                failureClass = null,
                summaryDetail = "未观测到明确回执",
                confirmedAt = null,
                createdAt = 1000L,
                updatedAt = 2000L,
            ),
            targetName = "测试好友",
            templateLabel = "续火花（表情）",
            isDailySlot = true,
        )
        val state = baseHealthyState().copy(
            unknownReviewJobs = listOf(unknownJob),
            pendingTargetCount = 3,
            enabledTargetCount = 5,
            boundEnabledTargetCount = 5,
        )

        val result = resolveSimpleActionState(state)
        assertEquals(
            SimpleActionState.NeedsUnknownReview(
                unknownCount = 1,
                firstJobId = "job_unknown_1",
            ),
            result,
        )
    }

    @Test
    fun `resolves Guided actions for login, verification, cooldown, dom broken, paused, and missing targets`() {
        assertEquals(
            SimpleGuidedAction.LOGIN,
            (resolveSimpleActionState(TodayUiState(isLoading = false, account = null)) as SimpleActionState.Guided).action,
        )
        assertEquals(
            SimpleGuidedAction.LOGIN,
            (resolveSimpleActionState(baseHealthyState(AccountStatus.PENDING_LOGIN)) as SimpleActionState.Guided).action,
        )
        assertEquals(
            SimpleGuidedAction.LOGIN,
            (resolveSimpleActionState(baseHealthyState(AccountStatus.AUTH_EXPIRED)) as SimpleActionState.Guided).action,
        )
        assertEquals(
            SimpleGuidedAction.LOGIN,
            (resolveSimpleActionState(baseHealthyState(AccountStatus.VERIFICATION_REQUIRED)) as SimpleActionState.Guided).action,
        )
        assertEquals(
            SimpleGuidedAction.RECOVER_COOLDOWN,
            (resolveSimpleActionState(baseHealthyState(AccountStatus.COOLDOWN)) as SimpleActionState.Guided).action,
        )
        assertEquals(
            SimpleGuidedAction.OPEN_DIAGNOSTICS,
            (resolveSimpleActionState(baseHealthyState(AccountStatus.DOM_BROKEN)) as SimpleActionState.Guided).action,
        )
        assertEquals(
            SimpleGuidedAction.OPEN_SCHEDULE,
            (resolveSimpleActionState(baseHealthyState(AccountStatus.PAUSED)) as SimpleActionState.Guided).action,
        )
        assertEquals(
            SimpleGuidedAction.OPEN_SCHEDULE,
            (
                resolveSimpleActionState(
                    baseHealthyState().copy(account = baseAccount(enabled = false)),
                ) as SimpleActionState.Guided
                ).action,
        )
        assertEquals(
            SimpleGuidedAction.SELECT_TARGETS,
            (
                resolveSimpleActionState(
                    baseHealthyState().copy(
                        enabledTargetCount = 0,
                        boundEnabledTargetCount = 0,
                        pendingTargetCount = 0,
                    ),
                ) as SimpleActionState.Guided
                ).action,
        )
        assertEquals(
            SimpleGuidedAction.SELECT_TARGETS,
            (
                resolveSimpleActionState(
                    baseHealthyState().copy(
                        enabledTargetCount = 3,
                        boundEnabledTargetCount = 0,
                        pendingTargetCount = 3,
                    ),
                ) as SimpleActionState.Guided
                ).action,
        )
    }

    @Test
    fun `resolves ReadyToSend when healthy account has pending targets today`() {
        val state = baseHealthyState().copy(
            enabledTargetCount = 6,
            boundEnabledTargetCount = 6,
            succeededTodayCount = 2,
            pendingTargetCount = 4,
        )
        assertEquals(
            SimpleActionState.ReadyToSend(
                succeededCount = 2,
                totalCount = 6,
                pendingCount = 4,
            ),
            resolveSimpleActionState(state),
        )
    }

    @Test
    fun `resolves AllCompletedToday when no pending targets remain today`() {
        val allLitState = baseHealthyState().copy(
            enabledTargetCount = 4,
            boundEnabledTargetCount = 4,
            localSucceededTodayCount = 2,
            officialLitOnlyTodayCount = 2,
            succeededTodayCount = 4,
            pendingTargetCount = 0,
        )
        val allLitResult = resolveSimpleActionState(allLitState) as SimpleActionState.AllCompletedToday
        assertEquals(4, allLitResult.succeededCount)
        assertEquals(4, allLitResult.totalCount)
        assertTrue(allLitResult.allSucceeded)

        val someCancelledState = baseHealthyState().copy(
            enabledTargetCount = 4,
            boundEnabledTargetCount = 4,
            succeededTodayCount = 3,
            pendingTargetCount = 0,
        )
        val cancelledResult = resolveSimpleActionState(someCancelledState) as SimpleActionState.AllCompletedToday
        assertEquals(3, cancelledResult.succeededCount)
        assertEquals(4, cancelledResult.totalCount)
        assertFalse(cancelledResult.allSucceeded)
    }

    @Test
    fun `formatSimpleScheduleLine renders clear schedule text for evening, daytime, tomorrow, and paused`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now: Instant = ZonedDateTime.of(2026, 4, 12, 10, 0, 0, 0, zone).toInstant()
        val tonightMs = ZonedDateTime.of(2026, 4, 12, 20, 32, 0, 0, zone).toInstant().toEpochMilli()
        val todayNoonMs = ZonedDateTime.of(2026, 4, 12, 14, 15, 0, 0, zone).toInstant().toEpochMilli()
        val tomorrowMorningMs = ZonedDateTime.of(2026, 4, 13, 8, 5, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals(
            "今晚 20:32 自动续火 · 已开启",
            formatSimpleScheduleLine(baseHealthyState().copy(nextAutoTriggerAtEpochMs = tonightMs), now),
        )
        assertEquals(
            "今日 14:15 自动续火 · 已开启",
            formatSimpleScheduleLine(baseHealthyState().copy(nextAutoTriggerAtEpochMs = todayNoonMs), now),
        )
        assertEquals(
            "明日 08:05 自动续火 · 已开启",
            formatSimpleScheduleLine(baseHealthyState().copy(nextAutoTriggerAtEpochMs = tomorrowMorningMs), now),
        )
        assertEquals(
            "自动续火已暂停 · 点此开启",
            formatSimpleScheduleLine(baseHealthyState().copy(account = baseAccount(enabled = false)), now),
        )
        assertEquals(
            "自动续火已暂停 · 请先登录抖音",
            formatSimpleScheduleLine(baseHealthyState(AccountStatus.AUTH_EXPIRED), now),
        )
    }

    @Test
    fun `buildSimpleProgressDots maps ember, healthy, danger, pending and overflow cleanly`() {
        val empty = buildSimpleProgressDots(totalCount = 0, succeededCount = 0, unknownCount = 0, allCompleted = false)
        assertTrue(empty.dots.isEmpty())
        assertEquals(0, empty.overflowCount)

        val inProgress = buildSimpleProgressDots(totalCount = 5, succeededCount = 3, unknownCount = 0, allCompleted = false)
        assertEquals(
            listOf(
                SimpleProgressDotKind.DONE_EMBER,
                SimpleProgressDotKind.DONE_EMBER,
                SimpleProgressDotKind.DONE_EMBER,
                SimpleProgressDotKind.PENDING,
                SimpleProgressDotKind.PENDING,
            ),
            inProgress.dots,
        )
        assertEquals(0, inProgress.overflowCount)

        val doneAll = buildSimpleProgressDots(totalCount = 3, succeededCount = 3, unknownCount = 0, allCompleted = true)
        assertEquals(
            listOf(
                SimpleProgressDotKind.DONE_HEALTHY,
                SimpleProgressDotKind.DONE_HEALTHY,
                SimpleProgressDotKind.DONE_HEALTHY,
            ),
            doneAll.dots,
        )

        val withUnknown = buildSimpleProgressDots(totalCount = 4, succeededCount = 2, unknownCount = 1, allCompleted = false)
        assertEquals(
            listOf(
                SimpleProgressDotKind.DONE_EMBER,
                SimpleProgressDotKind.DONE_EMBER,
                SimpleProgressDotKind.PENDING,
                SimpleProgressDotKind.UNKNOWN_DANGER,
            ),
            withUnknown.dots,
        )

        val overflowed = buildSimpleProgressDots(totalCount = 30, succeededCount = 10, unknownCount = 0, allCompleted = false)
        assertEquals(SIMPLE_MAX_VISIBLE_DOTS, overflowed.dots.size)
        assertEquals(30 - SIMPLE_MAX_VISIBLE_DOTS, overflowed.overflowCount)
        assertTrue(overflowed.dots.any { it == SimpleProgressDotKind.DONE_EMBER })
        assertTrue(overflowed.dots.any { it == SimpleProgressDotKind.PENDING })
    }

    @Test
    fun `layoutSimpleProgressDotRows balances multi-row counts without orphan dots`() {
        fun dotsOf(n: Int) = List(n) { SimpleProgressDotKind.DONE_HEALTHY }

        assertTrue(layoutSimpleProgressDotRows(emptyList()).isEmpty())
        assertEquals(listOf(1), layoutSimpleProgressDotRows(dotsOf(1)).map { it.size })
        assertEquals(listOf(12), layoutSimpleProgressDotRows(dotsOf(12)).map { it.size })
        assertEquals(listOf(7, 6), layoutSimpleProgressDotRows(dotsOf(13)).map { it.size })
        assertEquals(listOf(8, 7), layoutSimpleProgressDotRows(dotsOf(15)).map { it.size })
        assertEquals(listOf(8, 8), layoutSimpleProgressDotRows(dotsOf(16)).map { it.size })
        assertEquals(listOf(9, 9), layoutSimpleProgressDotRows(dotsOf(18)).map { it.size })
        assertEquals(listOf(12, 12), layoutSimpleProgressDotRows(dotsOf(24)).map { it.size })

        // 1..24 任意数量都不得出现前后行相差超过 1 个点的孤行
        for (n in 1..SIMPLE_MAX_VISIBLE_DOTS) {
            val rows = layoutSimpleProgressDotRows(dotsOf(n))
            assertEquals(n, rows.sumOf { it.size })
            assertTrue(rows.all { it.size <= SIMPLE_MAX_SINGLE_ROW_DOTS })
            assertTrue((rows.maxOf { it.size } - rows.minOf { it.size }) <= 1)
        }
    }

    @Test
    fun `resolveSimplePrimaryCtaLabel matches every state clearly without jargon`() {
        val readyState = baseHealthyState().copy(enabledTargetCount = 15, boundEnabledTargetCount = 15, succeededTodayCount = 12, pendingTargetCount = 3)
        assertEquals(
            "立即续火（还差 3 位）",
            resolveSimplePrimaryCtaLabel(resolveSimpleActionState(readyState), readyState),
        )

        val doneState = baseHealthyState().copy(enabledTargetCount = 15, boundEnabledTargetCount = 15, succeededTodayCount = 15, pendingTargetCount = 0)
        assertEquals(
            "今日已续完 · 点此重发",
            resolveSimplePrimaryCtaLabel(resolveSimpleActionState(doneState), doneState),
        )

        val expiredState = baseHealthyState(AccountStatus.AUTH_EXPIRED)
        assertEquals(
            "重新登录抖音",
            resolveSimplePrimaryCtaLabel(resolveSimpleActionState(expiredState), expiredState),
        )

        val emptyTargets = baseHealthyState().copy(enabledTargetCount = 0, boundEnabledTargetCount = 0, pendingTargetCount = 0)
        assertEquals(
            "去扫描并挑选好友",
            resolveSimplePrimaryCtaLabel(resolveSimpleActionState(emptyTargets), emptyTargets),
        )
    }

    private fun baseAccount(
        status: AccountStatus = AccountStatus.HEALTHY,
        enabled: Boolean = true,
    ): AccountEntity = AccountEntity(
        id = "default",
        displayName = "主力号",
        status = status,
        enabled = enabled,
        timezone = "Asia/Shanghai",
        sendWindowStart = "08:00",
        sendWindowEnd = "22:00",
        updatedAt = 1000L,
    )

    private fun baseHealthyState(status: AccountStatus = AccountStatus.HEALTHY): TodayUiState =
        TodayUiState(
            isLoading = false,
            businessDate = "2026-04-12",
            account = baseAccount(status = status),
            enabledTargetCount = 3,
            boundEnabledTargetCount = 3,
            pendingTargetCount = 2,
            succeededTodayCount = 1,
        )
}
