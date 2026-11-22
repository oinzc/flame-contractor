package io.github.oinzc.flamecontractor.scheduler

import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.ScanRunEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScanPlannerTest {

    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun instantAt(dateTime: String): Instant =
        ZonedDateTime.of(LocalDateTime.parse(dateTime), zoneId).toInstant()

    private fun instantAt(date: LocalDate, time: LocalTime): Instant =
        ZonedDateTime.of(date, time, zoneId).toInstant()

    private fun account(
        enabled: Boolean = true,
        autoScanEnabled: Boolean = true,
        autoScanIntervalHours: Int = 24,
        lastAutoScanAttemptAt: Long? = null,
        status: AccountStatus = AccountStatus.HEALTHY,
        authFailCount: Int = 0,
        lastVerifiedAt: Long? = null,
        sendWindowStart: String = "20:00",
        sendWindowEnd: String = "22:00",
        sendTimeHhmm: String? = null,
    ): AccountEntity = AccountEntity(
        id = DEFAULT_ACCOUNT_ID,
        enabled = enabled,
        autoScanEnabled = autoScanEnabled,
        autoScanIntervalHours = autoScanIntervalHours,
        lastAutoScanAttemptAt = lastAutoScanAttemptAt,
        status = status,
        authFailCount = authFailCount,
        lastVerifiedAt = lastVerifiedAt,
        timezone = zoneId.id,
        sendWindowStart = sendWindowStart,
        sendWindowEnd = sendWindowEnd,
        sendTimeHhmm = sendTimeHhmm,
    )

    private fun target(
        id: String = "t-1",
        enabled: Boolean = true,
        sendTimeHhmm: String? = null,
        deletedAt: Long? = null,
    ): ContractTargetEntity = ContractTargetEntity(
        id = id,
        accountId = DEFAULT_ACCOUNT_ID,
        nicknameSnapshot = "好友-$id",
        enabled = enabled,
        sendTimeHhmm = sendTimeHhmm,
        deletedAt = deletedAt,
    )

    private fun scanRun(
        id: String = "scan-1",
        status: ScanRunStatus = ScanRunStatus.COMPLETED,
        startedAt: Instant,
        finishedAt: Instant? = startedAt,
    ): ScanRunEntity = ScanRunEntity(
        id = id,
        accountId = DEFAULT_ACCOUNT_ID,
        source = "automatic",
        status = status,
        startedAt = startedAt.toEpochMilli(),
        finishedAt = finishedAt?.toEpochMilli(),
    )

    @Test
    fun triggersImmediatelyWhenNeverScannedOutsideSendWindow() {
        val now = instantAt("2026-11-22T14:00:00")
        val evaluation = AutoScanPlanner.evaluate(
            now = now,
            account = account(),
            enabledTargets = listOf(target()),
            latestCompletedScan = null,
            latestAnyScan = null,
            zoneId = zoneId,
        )

        assertTrue(evaluation.eligible)
        assertTrue(evaluation.shouldTriggerNow)
        assertFalse(evaluation.blockedBySendWindow)
        assertEquals(now, evaluation.nextAutoScanAt)
    }

    @Test
    fun triggersCatchUpAfterAuthRecoveryRespectingCooldown() {
        val failedAt = instantAt("2026-11-22T10:00:00")
        val verifiedAt = instantAt("2026-11-22T11:00:00")
        val now = instantAt("2026-11-22T11:05:00")
        val olderCompleted = scanRun(
            id = "scan-old",
            status = ScanRunStatus.COMPLETED,
            startedAt = instantAt("2026-11-21T09:00:00"),
        )
        val latestFailed = scanRun(
            id = "scan-fail",
            status = ScanRunStatus.FAILED,
            startedAt = failedAt,
            finishedAt = failedAt,
        )

        val immediate = AutoScanPlanner.evaluate(
            now = now,
            account = account(
                autoScanIntervalHours = 48,
                lastVerifiedAt = verifiedAt.toEpochMilli(),
                lastAutoScanAttemptAt = null,
            ),
            enabledTargets = listOf(target()),
            latestCompletedScan = olderCompleted,
            latestAnyScan = latestFailed,
            zoneId = zoneId,
        )
        assertTrue(immediate.eligible)
        assertTrue(immediate.shouldTriggerNow)
        assertEquals(now, immediate.nextAutoScanAt)

        val recentAttempt = instantAt("2026-11-22T10:40:00")
        val cooledDown = AutoScanPlanner.evaluate(
            now = now,
            account = account(
                autoScanIntervalHours = 48,
                lastVerifiedAt = verifiedAt.toEpochMilli(),
                lastAutoScanAttemptAt = recentAttempt.toEpochMilli(),
            ),
            enabledTargets = listOf(target()),
            latestCompletedScan = olderCompleted,
            latestAnyScan = latestFailed,
            zoneId = zoneId,
        )
        assertTrue(cooledDown.eligible)
        assertFalse(cooledDown.shouldTriggerNow)
        assertEquals(instantAt("2026-11-22T11:40:00"), cooledDown.nextAutoScanAt)
    }

    @Test
    fun schedulesNextRunAtLastCompletedPlusClampedIntervalHours() {
        val completedAt = instantAt("2026-11-21T10:00:00")
        val now = instantAt("2026-11-22T08:00:00")
        val scan = scanRun(status = ScanRunStatus.COMPLETED, startedAt = completedAt)

        val eval24h = AutoScanPlanner.evaluate(
            now = now,
            account = account(autoScanIntervalHours = 24),
            enabledTargets = listOf(target()),
            latestCompletedScan = scan,
            latestAnyScan = scan,
            zoneId = zoneId,
        )
        assertTrue(eval24h.eligible)
        assertFalse(eval24h.shouldTriggerNow)
        assertFalse(eval24h.blockedBySendWindow)
        assertEquals(instantAt("2026-11-22T10:00:00"), eval24h.nextAutoScanAt)

        // Interval < 1 clamps to 1 hour
        val evalClampedMin = AutoScanPlanner.evaluate(
            now = instantAt("2026-11-21T10:30:00"),
            account = account(autoScanIntervalHours = 0),
            enabledTargets = listOf(target()),
            latestCompletedScan = scan,
            latestAnyScan = scan,
            zoneId = zoneId,
        )
        assertFalse(evalClampedMin.shouldTriggerNow)
        assertEquals(instantAt("2026-11-21T11:00:00"), evalClampedMin.nextAutoScanAt)

        // Interval > 168 clamps to 168 hours (7 days)
        val evalClampedMax = AutoScanPlanner.evaluate(
            now = now,
            account = account(autoScanIntervalHours = 300),
            enabledTargets = listOf(target()),
            latestCompletedScan = scan,
            latestAnyScan = scan,
            zoneId = zoneId,
        )
        assertFalse(evalClampedMax.shouldTriggerNow)
        assertEquals(instantAt("2026-11-28T10:00:00"), evalClampedMax.nextAutoScanAt)
    }

    @Test
    fun enforcesSixtyMinuteAttemptCooldown() {
        val now = instantAt("2026-11-22T14:00:00")
        val lastAttempt = instantAt("2026-11-22T13:40:00")
        val evaluation = AutoScanPlanner.evaluate(
            now = now,
            account = account(lastAutoScanAttemptAt = lastAttempt.toEpochMilli()),
            enabledTargets = listOf(target()),
            latestCompletedScan = null,
            latestAnyScan = null,
            zoneId = zoneId,
        )

        assertTrue(evaluation.eligible)
        assertFalse(evaluation.shouldTriggerNow)
        assertFalse(evaluation.blockedBySendWindow)
        assertEquals(instantAt("2026-11-22T14:40:00"), evaluation.nextAutoScanAt)
    }

    @Test
    fun avoidsMergedSendWindowAndFixedTimesAndJumpsPast2355ToNextDay0005() {
        // Window 20:00-22:00 ([19:45, 22:00)) + enabled target fixed 19:00 ([18:45, 20:00)) -> [18:45, 22:00)
        // Disabled target fixed 17:00 must be ignored
        val nowInsideMerged = instantAt("2026-11-22T19:30:00")
        val blockedEval = AutoScanPlanner.evaluate(
            now = nowInsideMerged,
            account = account(sendWindowStart = "20:00", sendWindowEnd = "22:00"),
            enabledTargets = listOf(
                target(id = "t-fixed", enabled = true, sendTimeHhmm = "19:00"),
                target(id = "t-disabled", enabled = false, sendTimeHhmm = "17:00"),
            ),
            latestCompletedScan = null,
            latestAnyScan = null,
            zoneId = zoneId,
        )
        assertTrue(blockedEval.eligible)
        assertFalse(blockedEval.shouldTriggerNow)
        assertTrue(blockedEval.blockedBySendWindow)
        assertEquals(instantAt("2026-11-22T22:00:00"), blockedEval.nextAutoScanAt)

        // Overdue completed scan at 19:30 must also be blocked by today's window, not past day
        val overdueScan = scanRun(
            status = ScanRunStatus.COMPLETED,
            startedAt = instantAt("2026-11-19T14:00:00"),
        )
        val overdueBlocked = AutoScanPlanner.evaluate(
            now = nowInsideMerged,
            account = account(sendWindowStart = "20:00", sendWindowEnd = "22:00"),
            enabledTargets = listOf(target(id = "t-fixed", enabled = true, sendTimeHhmm = "19:00")),
            latestCompletedScan = overdueScan,
            latestAnyScan = overdueScan,
            zoneId = zoneId,
        )
        assertFalse(overdueBlocked.shouldTriggerNow)
        assertTrue(overdueBlocked.blockedBySendWindow)
        assertEquals(instantAt("2026-11-22T22:00:00"), overdueBlocked.nextAutoScanAt)

        // Fixed time 22:56 -> blocked [22:41, 23:56); 23:00 pushes to 23:56 (> 23:55) -> next day 00:05
        val lateNow = instantAt("2026-11-22T23:00:00")
        val rolledOver = AutoScanPlanner.evaluate(
            now = lateNow,
            account = account(sendWindowStart = "20:00", sendWindowEnd = "22:00"),
            enabledTargets = listOf(target(id = "t-late", enabled = true, sendTimeHhmm = "22:56")),
            latestCompletedScan = null,
            latestAnyScan = null,
            zoneId = zoneId,
        )
        assertTrue(rolledOver.eligible)
        assertFalse(rolledOver.shouldTriggerNow)
        assertTrue(rolledOver.blockedBySendWindow)
        assertEquals(instantAt("2026-11-23T00:05:00"), rolledOver.nextAutoScanAt)

        // If next day 00:05 is ALSO blocked by an early target (00:10 -> [00:00, 01:10)), re-check pushes to 01:10
        val rolledOverWithMorningBlock = AutoScanPlanner.evaluate(
            now = lateNow,
            account = account(sendWindowStart = "20:00", sendWindowEnd = "22:00"),
            enabledTargets = listOf(
                target(id = "t-late", enabled = true, sendTimeHhmm = "22:56"),
                target(id = "t-early", enabled = true, sendTimeHhmm = "00:10"),
            ),
            latestCompletedScan = null,
            latestAnyScan = null,
            zoneId = zoneId,
        )
        assertEquals(instantAt("2026-11-23T01:10:00"), rolledOverWithMorningBlock.nextAutoScanAt)

        // Boundary 23:55 is NOT > 23:55, so 22:30-23:55 pushes 22:45 to 23:55 same day
        val evalAt2355Boundary = AutoScanPlanner.evaluate(
            now = instantAt("2026-11-22T22:45:00"),
            account = account(sendWindowStart = "22:30", sendWindowEnd = "23:55"),
            enabledTargets = listOf(target()),
            latestCompletedScan = null,
            latestAnyScan = null,
            zoneId = zoneId,
        )
        assertEquals(instantAt("2026-11-22T23:55:00"), evalAt2355Boundary.nextAutoScanAt)
    }

    @Test
    fun returnsIneligibleWhenDisabledUnhealthyAuthFailingOrNoActiveEnabledTargets() {
        val now = instantAt("2026-11-22T14:00:00")
        val cases = listOf(
            account(autoScanEnabled = false) to listOf(target()),
            account(enabled = false) to listOf(target()),
            account(status = AccountStatus.AUTH_EXPIRED) to listOf(target()),
            account(status = AccountStatus.PAUSED) to listOf(target()),
            account(authFailCount = 1) to listOf(target()),
            account() to emptyList(),
            account() to listOf(target(enabled = false)),
            account() to listOf(target(enabled = true, deletedAt = 1_000L)),
        )

        for ((acc, targets) in cases) {
            val res = AutoScanPlanner.evaluate(
                now = now,
                account = acc,
                enabledTargets = targets,
                latestCompletedScan = null,
                latestAnyScan = null,
                zoneId = zoneId,
            )
            assertFalse(res.eligible)
            assertFalse(res.shouldTriggerNow)
            assertFalse(res.blockedBySendWindow)
            assertNull(res.nextAutoScanAt)
        }
    }
}
