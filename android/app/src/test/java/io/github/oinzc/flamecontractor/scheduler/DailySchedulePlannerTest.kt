package io.github.oinzc.flamecontractor.scheduler

import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.domain.SendGuard
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailySchedulePlannerTest {

    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun instantAt(date: LocalDate, time: LocalTime): Instant =
        ZonedDateTime.of(date, time, zoneId).toInstant()

    private fun instantAt(dateTime: String): Instant =
        ZonedDateTime.of(LocalDateTime.parse(dateTime), zoneId).toInstant()

    private fun account(
        status: AccountStatus = AccountStatus.HEALTHY,
        enabled: Boolean = true,
        windowStart: String = "20:00",
        windowEnd: String = "22:00",
        sendTimeHhmm: String? = null,
        jitterMaxMinutes: Int = 15,
        authFailCount: Int = 0,
        updatedAt: Long = 1_000L,
    ): AccountEntity = AccountEntity(
        id = DEFAULT_ACCOUNT_ID,
        displayName = "测试账号",
        status = status,
        enabled = enabled,
        timezone = zoneId.id,
        sendWindowStart = windowStart,
        sendWindowEnd = windowEnd,
        sendTimeHhmm = sendTimeHhmm,
        jitterMaxMinutes = jitterMaxMinutes,
        authFailCount = authFailCount,
        createdAt = 1_000L,
        updatedAt = updatedAt,
    )

    private fun target(
        id: String = "target-1",
        enabled: Boolean = true,
        sendTimeHhmm: String? = null,
        deletedAt: Long? = null,
    ): ContractTargetEntity = ContractTargetEntity(
        id = id,
        accountId = DEFAULT_ACCOUNT_ID,
        nicknameSnapshot = "测试对象-$id",
        enabled = enabled,
        sendTimeHhmm = sendTimeHhmm,
        deletedAt = deletedAt,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun job(
        id: String = "job-1",
        targetId: String = "target-1",
        businessDate: String = "2026-11-21",
        slotId: String = SendGuard.DAILY_SLOT_ID,
        status: JobStatus = JobStatus.SCHEDULED,
        attemptCount: Int = 0,
        failureClass: FailureClass? = null,
        updatedAt: Long = 1_000L,
    ): SendJobEntity = SendJobEntity(
        id = id,
        accountId = DEFAULT_ACCOUNT_ID,
        targetId = targetId,
        templateId = "tpl-1",
        businessDate = businessDate,
        messageSlotId = slotId,
        status = status,
        attemptCount = attemptCount,
        failureClass = failureClass,
        createdAt = 1_000L,
        updatedAt = updatedAt,
    )

    @Test
    fun rejectsCrossMidnightWindowInV1() {
        // 合法同日窗口
        assertTrue(DailySchedulePlanner.validateWindow("00:00", "23:55"))
        assertTrue(DailySchedulePlanner.validateWindow("20:00", "22:00"))
        assertTrue(DailySchedulePlanner.validateWindow("23:54", "23:55"))

        // 跨午夜、相等、超 23:55 与格式非法均必须拒绝
        assertFalse(DailySchedulePlanner.validateWindow("23:00", "01:00"))
        assertFalse(DailySchedulePlanner.validateWindow("22:00", "00:00"))
        assertFalse(DailySchedulePlanner.validateWindow("20:00", "20:00"))
        assertFalse(DailySchedulePlanner.validateWindow("20:00", "23:56"))
        assertFalse(DailySchedulePlanner.validateWindow("20:00", "23:59"))
        assertFalse(DailySchedulePlanner.validateWindow("8:00", "10:00"))
        assertFalse(DailySchedulePlanner.validateWindow("24:00", "23:55"))
        assertFalse(DailySchedulePlanner.validateWindow("20:60", "21:00"))
        assertFalse(DailySchedulePlanner.validateWindow("invalid", "21:00"))

        // 即使落库窗口跨午夜，computeTargetDueTime 也必须回退到同日合法窗口内
        val day = LocalDate.of(2026, 11, 21)
        val crossMidnightAcc = account(windowStart = "23:00", windowEnd = "02:00")
        val schedule = DailySchedulePlanner.computeTargetDueTime(
            businessDate = day,
            account = crossMidnightAcc,
            target = target(),
            zoneId = zoneId,
        )
        assertEquals(day, ZonedDateTime.ofInstant(schedule.dueAt, zoneId).toLocalDate())
        assertEquals(day, ZonedDateTime.ofInstant(schedule.cutoffAt, zoneId).toLocalDate())
        assertTrue(schedule.dueAt.isBefore(schedule.cutoffAt))
    }

    @Test
    fun clampsWindowJitterBeforeWindowEndMinus2Minutes() {
        val day = LocalDate.of(2026, 11, 21)
        val narrowAccount = account(
            windowStart = "21:45",
            windowEnd = "22:00",
            jitterMaxMinutes = 15,
        )
        val windowStart = instantAt(day, LocalTime.of(21, 45))
        val windowEnd = instantAt(day, LocalTime.of(22, 0))
        val maxAllowedDue = windowEnd.minus(Duration.ofMinutes(DailySchedulePlanner.WINDOW_END_SAFETY_MARGIN_MINUTES))

        // 覆盖 50 个不同目标，全部抖动后都必须严格 <= windowEnd - 2 分钟
        for (i in 1..50) {
            val schedule = DailySchedulePlanner.computeTargetDueTime(
                businessDate = day,
                account = narrowAccount,
                target = target(id = "target-$i"),
                zoneId = zoneId,
            )
            assertFalse("dueAt must not be before windowStart", schedule.dueAt.isBefore(windowStart))
            assertFalse("dueAt must be <= windowEnd - 2m", schedule.dueAt.isAfter(maxAllowedDue))
            assertEquals(windowEnd, schedule.cutoffAt)
        }

        // 超短窗口 (< 2 分钟，例如 21:59..22:00)：钳制至不早于 start 且 < cutoffAt
        val tinyAccount = account(windowStart = "21:59", windowEnd = "22:00", jitterMaxMinutes = 15)
        val tinySchedule = DailySchedulePlanner.computeTargetDueTime(
            businessDate = day,
            account = tinyAccount,
            target = target(id = "tiny-target"),
            zoneId = zoneId,
        )
        assertEquals(instantAt(day, LocalTime.of(21, 59)), tinySchedule.dueAt)
        assertEquals(windowEnd, tinySchedule.cutoffAt)
    }

    @Test
    fun grants60MinuteGraceForFixedSendTimeAndSkipsAfterGrace() {
        val day = LocalDate.of(2026, 11, 21)
        val acc = account(sendTimeHhmm = "20:00")
        val targetWithOverride = target(id = "t-override", sendTimeHhmm = "20:30")
        val targetInheritsAccount = target(id = "t-account", sendTimeHhmm = null)

        val overrideSched = DailySchedulePlanner.computeTargetDueTime(day, acc, targetWithOverride, zoneId)
        assertTrue(overrideSched.isFixedTime)
        assertEquals(instantAt(day, LocalTime.of(20, 30)), overrideSched.dueAt)
        assertEquals(instantAt(day, LocalTime.of(21, 30)), overrideSched.cutoffAt)

        // 在 20:59:59 评估：账号固定 20:00 尚在 60m 宽限内，目标 20:30 也已到期
        val withinGrace = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T20:59:59"),
            account = acc,
            targets = listOf(targetWithOverride, targetInheritsAccount),
            todayJobs = emptyList(),
            zoneId = zoneId,
        )
        assertEquals(setOf("t-override", "t-account"), withinGrace.dueTargets.map { it.target.id }.toSet())
        assertTrue(withinGrace.expiredTargets.isEmpty())

        // 到 21:00:00 整：账号 20:00 目标宽限已过，仅剩 20:30 目标（宽限至 21:30）
        val atAccountCutoff = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T21:00:00"),
            account = acc,
            targets = listOf(targetWithOverride, targetInheritsAccount),
            todayJobs = emptyList(),
            zoneId = zoneId,
        )
        assertEquals(listOf("t-override"), atAccountCutoff.dueTargets.map { it.target.id })
        assertEquals(listOf("t-account"), atAccountCutoff.expiredTargets.map { it.target.id })

        // 到 21:30:00 整：两个固定目标均已过 60 分钟宽限，全部跳过并安排次日唤醒
        val afterAllGrace = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T21:30:00"),
            account = acc,
            targets = listOf(targetWithOverride, targetInheritsAccount),
            todayJobs = emptyList(),
            zoneId = zoneId,
        )
        assertTrue(afterAllGrace.dueTargets.isEmpty())
        assertEquals(setOf("t-override", "t-account"), afterAllGrace.expiredTargets.map { it.target.id }.toSet())
        assertEquals(instantAt(day.plusDays(1), LocalTime.of(20, 0)), afterAllGrace.nextWakeAt)

        // 晚间固定时刻 23:30 的宽限上限截断到当日 23:59
        val lateTarget = target(id = "t-late", sendTimeHhmm = "23:30")
        val lateSched = DailySchedulePlanner.computeTargetDueTime(day, acc, lateTarget, zoneId)
        assertEquals(instantAt(day, LocalTime.of(23, 59)), lateSched.cutoffAt)
    }

    @Test
    fun neverDispatchesPreviousBusinessDateAfterMidnight() {
        val yesterday = "2026-11-20"
        val today = LocalDate.of(2026, 11, 21)
        val acc = account(windowStart = "20:00", windowEnd = "22:00")
        val t1 = target(id = "t-1")
        val t2 = target(id = "t-2")

        // 昨日遗留 SCHEDULED、FAILED_RETRYABLE 与 UNKNOWN_REQUIRES_REVIEW 不得触发今日凌晨补发或阻塞今日新任务
        val yesterdayJobs = listOf(
            job(
                id = "old-sched",
                targetId = "t-1",
                businessDate = yesterday,
                status = JobStatus.SCHEDULED,
                updatedAt = instantAt("2026-11-20T21:55:00").toEpochMilli(),
            ),
            job(
                id = "old-retry",
                targetId = "t-2",
                businessDate = yesterday,
                status = JobStatus.FAILED_RETRYABLE,
                attemptCount = 1,
                updatedAt = instantAt("2026-11-20T23:58:00").toEpochMilli(),
            ),
        )

        val afterMidnightEval = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T00:05:00"),
            account = acc,
            targets = listOf(t1, t2),
            todayJobs = yesterdayJobs,
            zoneId = zoneId,
        )
        assertEquals(today, afterMidnightEval.businessDate)
        assertTrue(afterMidnightEval.dueTargets.isEmpty())
        assertTrue(afterMidnightEval.expiredTargets.isEmpty())
        assertEquals(2, afterMidnightEval.pendingTargets.size)
        val todayWake = afterMidnightEval.nextWakeAt
        assertNotNull(todayWake)
        assertEquals(today, ZonedDateTime.ofInstant(todayWake, zoneId).toLocalDate())
        assertFalse(todayWake!!.isBefore(instantAt(today, LocalTime.of(20, 0))))
    }

    @Test
    fun computesDeterministicWindowTimeAndBatchesNearbyTargets() {
        val day = LocalDate.of(2026, 11, 21)
        val acc = account(windowStart = "20:00", windowEnd = "22:00", jitterMaxMinutes = 15)
        val wTarget = target(id = "window-deterministic")

        val s1 = DailySchedulePlanner.computeTargetDueTime(day, acc, wTarget, zoneId)
        val s2 = DailySchedulePlanner.computeTargetDueTime(day, acc, wTarget, zoneId)
        assertEquals(s1.baseDueAt, s2.baseDueAt)
        assertEquals(s1.dueAt, s2.dueAt)
        assertEquals(s1.cutoffAt, s2.cutoffAt)

        // 使用确定性固定时刻验证 5 分钟邻近合批行为
        val tDueNow = target(id = "t-due-now", sendTimeHhmm = "20:00")
        val tWithin5Min = target(id = "t-within-5m", sendTimeHhmm = "20:04")
        val tExact5Min = target(id = "t-exact-5m", sendTimeHhmm = "20:05")
        val tOutside5Min = target(id = "t-outside-5m", sendTimeHhmm = "20:10")
        val allTargets = listOf(tDueNow, tWithin5Min, tExact5Min, tOutside5Min)

        // 19:58 时无任何到期目标，绝不提前把 20:00 合并进来
        val beforeFirstDue = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T19:58:00"),
            account = acc,
            targets = allTargets,
            todayJobs = emptyList(),
            zoneId = zoneId,
        )
        assertTrue(beforeFirstDue.dueTargets.isEmpty())
        assertEquals(instantAt(day, LocalTime.of(20, 0)), beforeFirstDue.nextWakeAt)

        // 20:00 时 t-due-now 到期，将 <= 20:05 的 t-within-5m 与 t-exact-5m 一并合批，t-outside-5m 留在 pending 并预约 20:10
        val atFirstDue = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T20:00:00"),
            account = acc,
            targets = allTargets,
            todayJobs = emptyList(),
            zoneId = zoneId,
        )
        assertEquals(
            listOf("t-due-now", "t-within-5m", "t-exact-5m"),
            atFirstDue.dueTargets.map { it.target.id },
        )
        assertEquals(listOf("t-outside-5m"), atFirstDue.pendingTargets.map { it.target.id })
        assertEquals(instantAt(day, LocalTime.of(20, 10)), atFirstDue.nextWakeAt)
    }

    @Test
    fun backsOff5mThen15mAndStopsAtRetryMax2() {
        assertEquals(listOf(5L, 15L), DailySchedulePlanner.RETRY_BACKOFF_MINUTES)

        val day = LocalDate.of(2026, 11, 21)
        val acc = account(windowStart = "20:00", windowEnd = "22:00")
        val t1 = target(id = "t-retry", sendTimeHhmm = "20:00") // cutoff = 21:00

        // 第 1 次可重试失败 (attemptCount = 1, updatedAt = 20:02) -> 退避 5 分钟到 20:07
        val attempt1FailTime = instantAt("2026-11-21T20:02:00")
        val jobAfterFail1 = job(
            targetId = "t-retry",
            businessDate = day.toString(),
            status = JobStatus.FAILED_RETRYABLE,
            attemptCount = 1,
            failureClass = FailureClass.OTHER,
            updatedAt = attempt1FailTime.toEpochMilli(),
        )
        val wait1 = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T20:06:59"),
            account = acc,
            targets = listOf(t1),
            todayJobs = listOf(jobAfterFail1),
            zoneId = zoneId,
        )
        assertTrue(wait1.dueTargets.isEmpty())
        assertEquals(instantAt("2026-11-21T20:07:00"), wait1.nextWakeAt)

        val ready1 = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T20:07:00"),
            account = acc,
            targets = listOf(t1),
            todayJobs = listOf(jobAfterFail1),
            zoneId = zoneId,
        )
        assertEquals(listOf("t-retry"), ready1.dueTargets.map { it.target.id })
        assertTrue(ready1.dueTargets.single().isRetry)

        // 第 2 次可重试失败 (attemptCount = 2, updatedAt = 20:08) -> 退避 15 分钟到 20:23
        val attempt2FailTime = instantAt("2026-11-21T20:08:00")
        val jobAfterFail2 = jobAfterFail1.copy(
            attemptCount = 2,
            updatedAt = attempt2FailTime.toEpochMilli(),
        )
        val wait2 = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T20:22:59"),
            account = acc,
            targets = listOf(t1),
            todayJobs = listOf(jobAfterFail2),
            zoneId = zoneId,
        )
        assertTrue(wait2.dueTargets.isEmpty())
        assertEquals(instantAt("2026-11-21T20:23:00"), wait2.nextWakeAt)

        val ready2 = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T20:23:00"),
            account = acc,
            targets = listOf(t1),
            todayJobs = listOf(jobAfterFail2),
            zoneId = zoneId,
        )
        assertEquals(listOf("t-retry"), ready2.dueTargets.map { it.target.id })

        // 第 3 次耗尽 (attemptCount = 3 > RETRY_MAX_COUNT = 2) -> 今日不再排班，转次日唤醒
        val exhausted = jobAfterFail2.copy(
            attemptCount = 3,
            updatedAt = instantAt("2026-11-21T20:24:00").toEpochMilli(),
        )
        val afterExhausted = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T20:45:00"),
            account = acc,
            targets = listOf(t1),
            todayJobs = listOf(exhausted),
            zoneId = zoneId,
        )
        assertTrue(afterExhausted.dueTargets.isEmpty())
        assertTrue(afterExhausted.pendingTargets.isEmpty())
        assertEquals(instantAt(day.plusDays(1), LocalTime.of(20, 0)), afterExhausted.nextWakeAt)

        // 若退避后超过 cutoffAt（20:50 + 15m = 21:05 >= 21:00）：不再预约今日无效退避闹钟
        val pastCutoffRetry = jobAfterFail1.copy(
            attemptCount = 2,
            updatedAt = instantAt("2026-11-21T20:50:00").toEpochMilli(),
        )
        val afterCutoffRetry = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T20:55:00"),
            account = acc,
            targets = listOf(t1),
            todayJobs = listOf(pastCutoffRetry),
            zoneId = zoneId,
        )
        assertTrue(afterCutoffRetry.dueTargets.isEmpty())
        assertTrue(afterCutoffRetry.pendingTargets.isEmpty())
        assertEquals(listOf("t-retry"), afterCutoffRetry.expiredTargets.map { it.target.id })
        assertEquals(instantAt(day.plusDays(1), LocalTime.of(20, 0)), afterCutoffRetry.nextWakeAt)
    }

    @Test
    fun schedules10MinuteAuthRecheckWhenFailCountIs1() {
        val failAt = instantAt("2026-11-21T20:00:00")
        val acc = account(
            status = AccountStatus.HEALTHY,
            authFailCount = 1,
            updatedAt = failAt.toEpochMilli(),
        )
        val t1 = target(id = "t-normal", sendTimeHhmm = "20:00")

        // 5 分钟后：尚未到 10 分钟复判时间，暂停普通派发且 nextWakeAt 指向 20:10
        val beforeRecheck = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T20:05:00"),
            account = acc,
            targets = listOf(t1),
            todayJobs = emptyList(),
            zoneId = zoneId,
        )
        assertFalse(beforeRecheck.shouldTriggerAuthRecheck)
        assertEquals(instantAt("2026-11-21T20:10:00"), beforeRecheck.authRecheckAt)
        assertEquals(instantAt("2026-11-21T20:10:00"), beforeRecheck.nextWakeAt)
        assertTrue(beforeRecheck.dueTargets.isEmpty())
        assertTrue(beforeRecheck.pendingTargets.isEmpty())

        // 10 分钟整：立即触发 AUTH_RECHECK，且不与普通派发混发
        val atRecheck = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T20:10:00"),
            account = acc,
            targets = listOf(t1),
            todayJobs = emptyList(),
            zoneId = zoneId,
        )
        assertTrue(atRecheck.shouldTriggerAuthRecheck)
        assertEquals(instantAt("2026-11-21T20:10:00"), atRecheck.authRecheckAt)
        assertTrue(atRecheck.dueTargets.isEmpty())
    }

    @Test
    fun stopsNetworkDeferAfterMaxCount5() {
        assertEquals(10L, DailySchedulePlanner.NETWORK_DEFER_INTERVAL_MINUTES)
        assertEquals(5, DailySchedulePlanner.NETWORK_DEFER_MAX_COUNT)

        val day = LocalDate.of(2026, 11, 21)
        val acc = account(windowStart = "20:00", windowEnd = "22:00")
        val t1 = target(id = "t-net", sendTimeHhmm = "20:00")
        val networkJob = job(
            targetId = "t-net",
            businessDate = day.toString(),
            status = JobStatus.FAILED_RETRYABLE,
            attemptCount = 1,
            failureClass = FailureClass.NETWORK,
            updatedAt = instantAt("2026-11-21T20:00:00").toEpochMilli(),
        )

        // networkDeferCountToday = 4 (< 5)：+10m 后可再次检查
        val waiting = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T20:09:00"),
            account = acc,
            targets = listOf(t1),
            todayJobs = listOf(networkJob),
            zoneId = zoneId,
            networkDeferCountToday = 4,
        )
        assertTrue(waiting.dueTargets.isEmpty())
        assertEquals(instantAt("2026-11-21T20:10:00"), waiting.nextWakeAt)

        val ready = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T20:10:00"),
            account = acc,
            targets = listOf(t1),
            todayJobs = listOf(networkJob),
            zoneId = zoneId,
            networkDeferCountToday = 4,
        )
        assertEquals(listOf("t-net"), ready.dueTargets.map { it.target.id })
        assertTrue(ready.dueTargets.single().isNetworkRetry)

        // networkDeferCountToday = 5 (>= 5)：当日停摆不排班
        val maxedOut = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T20:15:00"),
            account = acc,
            targets = listOf(t1),
            todayJobs = listOf(networkJob),
            zoneId = zoneId,
            networkDeferCountToday = 5,
        )
        assertTrue(maxedOut.dueTargets.isEmpty())
        assertTrue(maxedOut.pendingTargets.isEmpty())
        assertEquals(instantAt(day.plusDays(1), LocalTime.of(20, 0)), maxedOut.nextWakeAt)
    }

    @Test
    fun skipsWindowTargetOncePastWindowEndAndPlansNextDayWake() {
        val day = LocalDate.of(2026, 11, 21)
        val acc = account(windowStart = "20:00", windowEnd = "22:00", jitterMaxMinutes = 15)
        val t1 = target(id = "t-win")

        // 22:00:00 到达 windowEnd，过窗跳过并计划次日唤醒
        val atEnd = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T22:00:00"),
            account = acc,
            targets = listOf(t1),
            todayJobs = emptyList(),
            zoneId = zoneId,
        )
        assertTrue(atEnd.dueTargets.isEmpty())
        assertTrue(atEnd.pendingTargets.isEmpty())
        assertEquals(listOf("t-win"), atEnd.expiredTargets.map { it.target.id })

        val nextDaySchedule = DailySchedulePlanner.computeTargetDueTime(day.plusDays(1), acc, t1, zoneId)
        assertEquals(nextDaySchedule.dueAt, atEnd.nextWakeAt)
        assertEquals(day.plusDays(1), ZonedDateTime.ofInstant(atEnd.nextWakeAt, zoneId).toLocalDate())

        // 账号未启用或非 HEALTHY 状态均不得排班或泄露次日闹钟
        val disabledEval = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T20:00:00"),
            account = account(enabled = false),
            targets = listOf(t1),
            todayJobs = emptyList(),
            zoneId = zoneId,
        )
        assertTrue(disabledEval.dueTargets.isEmpty())
        assertNull(disabledEval.nextWakeAt)

        for (nonHealthy in AccountStatus.entries.filter { it != AccountStatus.HEALTHY }) {
            val nonHealthyEval = DailySchedulePlanner.evaluateNow(
                now = instantAt("2026-11-21T20:00:00"),
                account = account(status = nonHealthy),
                targets = listOf(t1),
                todayJobs = emptyList(),
                zoneId = zoneId,
            )
            assertTrue("status=$nonHealthy must not dispatch", nonHealthyEval.dueTargets.isEmpty())
            assertNull("status=$nonHealthy must not schedule nextWakeAt", nonHealthyEval.nextWakeAt)
        }
    }

    @Test
    fun ignoresDeletedOrDisabledTargetsAndSucceededOrBlockedTodayJobs() {
        val day = LocalDate.of(2026, 11, 21)
        val acc = account(windowStart = "20:00", windowEnd = "22:00")
        val tDeleted = target(id = "t-del", enabled = true, sendTimeHhmm = "20:00", deletedAt = 5_000L)
        val tDisabled = target(id = "t-dis", enabled = false, sendTimeHhmm = "20:00")
        val tUnknown = target(id = "t-unk", enabled = true, sendTimeHhmm = "20:00")
        val tSucceeded = target(id = "t-ok", enabled = true, sendTimeHhmm = "20:00")
        val tActivePending = target(id = "t-pending", enabled = true, sendTimeHhmm = "21:00")

        val jobs = listOf(
            job(
                id = "j-unk",
                targetId = "t-unk",
                businessDate = day.toString(),
                slotId = SendGuard.manualSlotId(1700000000000L),
                status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
            ),
            job(
                id = "j-ok",
                targetId = "t-ok",
                businessDate = day.toString(),
                slotId = SendGuard.DAILY_SLOT_ID,
                status = JobStatus.SUCCEEDED,
            ),
        )

        val eval = DailySchedulePlanner.evaluateNow(
            now = instantAt("2026-11-21T20:00:00"),
            account = acc,
            targets = listOf(tDeleted, tDisabled, tUnknown, tSucceeded, tActivePending),
            todayJobs = jobs,
            zoneId = zoneId,
        )
        assertTrue(eval.dueTargets.isEmpty())
        assertEquals(listOf("t-pending"), eval.pendingTargets.map { it.target.id })
        assertEquals(instantAt("2026-11-21T21:00:00"), eval.nextWakeAt)
    }
}
