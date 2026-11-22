package io.github.oinzc.flamecontractor.domain

import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStatsCalculatorTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 22)

    @Test
    fun summarizeEmptyJobsFills30DailyBucketsWithNullSuccessRate() {
        val summary = HistoryStatsCalculator.summarize(
            jobs = emptyList(),
            today = today,
            windowDays = 30,
        )

        assertEquals(30, summary.windowDays)
        assertEquals(30, summary.daily.size)
        assertEquals(today.minusDays(29), summary.daily.first().date)
        assertEquals(today, summary.daily.last().date)
        assertTrue(summary.daily.all { it.total == 0 && it.succeeded == 0 && it.failed == 0 && it.unknown == 0 && it.cancelled == 0 })
        assertEquals(0, summary.totalJobs)
        assertEquals(0, summary.succeededJobs)
        assertEquals(0, summary.failedJobs)
        assertEquals(0, summary.unknownJobs)
        assertEquals(0, summary.cancelledJobs)
        assertNull(summary.successRate)
        assertEquals(0, summary.currentSuccessStreak)
        assertEquals(0, summary.bestSuccessStreak)
    }

    @Test
    fun summarizeWindow7DaysFiltersOutOutsideWindowDates() {
        val jobs = listOf(
            job(id = "outside-old", businessDate = today.minusDays(7), status = JobStatus.SUCCEEDED),
            job(id = "inside-start", businessDate = today.minusDays(6), status = JobStatus.SUCCEEDED),
            job(id = "inside-today", businessDate = today, status = JobStatus.FAILED_RETRYABLE, failureClass = FailureClass.NETWORK),
            job(id = "outside-future", businessDate = today.plusDays(1), status = JobStatus.SUCCEEDED),
        )

        val summary = HistoryStatsCalculator.summarize(
            jobs = jobs,
            today = today,
            windowDays = 7,
        )

        assertEquals(7, summary.windowDays)
        assertEquals(7, summary.daily.size)
        assertEquals(today.minusDays(6), summary.daily.first().date)
        assertEquals(today, summary.daily.last().date)
        assertEquals(2, summary.totalJobs)
        assertEquals(1, summary.succeededJobs)
        assertEquals(1, summary.failedJobs)
        assertEquals(0, summary.unknownJobs)
        assertEquals(0, summary.cancelledJobs)
        assertEquals(0.5, summary.successRate ?: -1.0, 1e-6)
    }

    @Test
    fun summarizeSkipsTrailingEmptyTodayWithoutBreakingOngoingStreak() {
        // 前3天连续成功，今天尚未执行（today 为全 0 桶）
        val jobs = listOf(
            job(id = "d3", businessDate = today.minusDays(3), status = JobStatus.SUCCEEDED),
            job(id = "d2", businessDate = today.minusDays(2), status = JobStatus.SUCCEEDED),
            job(id = "d1", businessDate = today.minusDays(1), status = JobStatus.SUCCEEDED),
            // today 只有尚未执行的 SCHEDULED 任务，不应计为失败或打断当前连续天数
            job(id = "d0-sched", businessDate = today, status = JobStatus.SCHEDULED),
        )

        val summary = HistoryStatsCalculator.summarize(
            jobs = jobs,
            today = today,
            windowDays = 7,
        )

        assertEquals(3, summary.currentSuccessStreak)
        assertEquals(3, summary.bestSuccessStreak)
        assertEquals(3, summary.totalJobs)
        assertEquals(3, summary.succeededJobs)
        assertEquals(0, summary.daily.last().total)
    }

    @Test
    fun summarizeBreaksCurrentStreakOnEmptyYesterdayOrFailureOrUnknownWhilePreservingBest() {
        // Case A: 昨天空白断档（即使今天空着），current 归 0，best 保留历史连续 3 天
        val gapYesterdayJobs = listOf(
            job(id = "h5", businessDate = today.minusDays(4), status = JobStatus.SUCCEEDED),
            job(id = "h4", businessDate = today.minusDays(3), status = JobStatus.SUCCEEDED),
            job(id = "h3", businessDate = today.minusDays(2), status = JobStatus.SUCCEEDED),
            // today.minusDays(1) 无记录，today 无记录
        )
        val gapSummary = HistoryStatsCalculator.summarize(gapYesterdayJobs, today, windowDays = 7)
        assertEquals(0, gapSummary.currentSuccessStreak)
        assertEquals(3, gapSummary.bestSuccessStreak)

        // Case B: 今天已有失败或待核对，即使昨天以前连续 2 天也立即打断 current
        val mixedTodayJobs = listOf(
            job(id = "m2", businessDate = today.minusDays(2), status = JobStatus.SUCCEEDED),
            job(id = "m1", businessDate = today.minusDays(1), status = JobStatus.SUCCEEDED),
            job(id = "m0-ok", businessDate = today, status = JobStatus.SUCCEEDED),
            job(id = "m0-unk", businessDate = today, status = JobStatus.UNKNOWN_REQUIRES_REVIEW, failureClass = FailureClass.UNKNOWN_OUTCOME),
        )
        val mixedSummary = HistoryStatsCalculator.summarize(mixedTodayJobs, today, windowDays = 7)
        assertEquals(0, mixedSummary.currentSuccessStreak)
        assertEquals(2, mixedSummary.bestSuccessStreak)
        assertEquals(4, mixedSummary.totalJobs)
        assertEquals(3, mixedSummary.succeededJobs)
        assertEquals(1, mixedSummary.unknownJobs)

        // Case C: 中间某天同时有 SUCCEEDED 和 CANCELLED（无失败与未知），当日仍计为成功日
        val cancelledPlusSuccessJobs = listOf(
            job(id = "c2-ok", businessDate = today.minusDays(2), status = JobStatus.SUCCEEDED),
            job(id = "c1-ok", businessDate = today.minusDays(1), status = JobStatus.SUCCEEDED),
            job(id = "c1-cancel", businessDate = today.minusDays(1), status = JobStatus.CANCELLED),
            job(id = "c0-fail", businessDate = today, status = JobStatus.FAILED_TERMINAL, failureClass = FailureClass.TARGET_UNCERTAIN),
        )
        val cancelledSummary = HistoryStatsCalculator.summarize(cancelledPlusSuccessJobs, today, windowDays = 7)
        assertEquals(0, cancelledSummary.currentSuccessStreak)
        assertEquals(2, cancelledSummary.bestSuccessStreak)
        assertEquals(1, cancelledSummary.cancelledJobs)
        assertEquals(1, cancelledSummary.failedJobs)
    }

    @Test
    fun csvExporterEncodesExactHeaderEscapesRfc4180SortsAndExcludesSensitiveText() {
        val t1 = Instant.parse("2026-11-21T12:00:00Z").toEpochMilli()
        val t2 = Instant.parse("2026-11-21T12:00:15Z").toEpochMilli()
        val t3 = Instant.parse("2026-11-22T12:05:00Z").toEpochMilli()

        val unsortedJobs = listOf(
            job(
                id = "j-late",
                targetId = "target-2",
                businessDate = today,
                slotId = "manual:1700,\"special\"\nslot",
                status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                attemptCount = 2,
                failureClass = FailureClass.UNKNOWN_OUTCOME,
                scheduledAt = t3,
                confirmedAt = null,
                summaryDetail = "SENSITIVE_NICKNAME_DO_NOT_EXPORT",
                createdAt = 3_000L,
            ),
            job(
                id = "j-early-2",
                targetId = "target-1",
                businessDate = today.minusDays(1),
                slotId = "daily",
                status = JobStatus.SUCCEEDED,
                attemptCount = 1,
                failureClass = null,
                scheduledAt = t1,
                confirmedAt = t2,
                summaryDetail = "SECRET_CHAT_CONTENT_DO_NOT_EXPORT",
                createdAt = 2_000L,
            ),
            job(
                id = "j-early-1",
                targetId = "target-3",
                businessDate = today.minusDays(1),
                slotId = "daily",
                status = JobStatus.FAILED_RETRYABLE,
                attemptCount = 1,
                failureClass = FailureClass.NETWORK,
                scheduledAt = null,
                confirmedAt = null,
                summaryDetail = "ANOTHER_SECRET_DETAIL",
                createdAt = 1_000L,
            ),
        )

        val csv = HistoryCsvExporter.encode(unsortedJobs)

        assertFalse(csv.contains("SENSITIVE_NICKNAME_DO_NOT_EXPORT"))
        assertFalse(csv.contains("SECRET_CHAT_CONTENT_DO_NOT_EXPORT"))
        assertFalse(csv.contains("ANOTHER_SECRET_DETAIL"))

        val headerLine = csv.lineSequence().first()
        assertEquals(
            "business_date,account_id,target_id,status,failure_class,attempt_count,scheduled_at,confirmed_at,message_slot_id",
            headerLine,
        )

        // 验证排序顺序：2026-11-21 createdAt=1000 -> 2026-11-21 createdAt=2000 -> 2026-11-22 createdAt=3000
        val idx1 = csv.indexOf("target-3")
        val idx2 = csv.indexOf("target-1")
        val idx3 = csv.indexOf("target-2")
        assertTrue(idx1 in 1 until idx2)
        assertTrue(idx2 < idx3)

        assertTrue(csv.contains("2026-11-21,$DEFAULT_ACCOUNT_ID,target-3,failed_retryable,network,1,,,daily"))
        assertTrue(
            csv.contains(
                "2026-11-21,$DEFAULT_ACCOUNT_ID,target-1,succeeded,,1,2026-11-21T12:00:00Z,2026-11-21T12:00:15Z,daily",
            ),
        )
        // 验证 RFC 4180 转义（含逗号、双引号与换行）
        assertTrue(
            csv.contains(
                "2026-11-22,$DEFAULT_ACCOUNT_ID,target-2,unknown_requires_review,unknown_outcome,2,2026-11-22T12:05:00Z,,\"manual:1700,\"\"special\"\"\nslot\"",
            ),
        )
    }

    private fun job(
        id: String,
        businessDate: LocalDate,
        status: JobStatus,
        targetId: String = "target-1",
        slotId: String = SendGuard.DAILY_SLOT_ID,
        attemptCount: Int = 1,
        failureClass: FailureClass? = null,
        scheduledAt: Long? = null,
        confirmedAt: Long? = null,
        summaryDetail: String? = null,
        createdAt: Long = 1_000L,
    ): SendJobEntity = SendJobEntity(
        id = id,
        accountId = DEFAULT_ACCOUNT_ID,
        targetId = targetId,
        templateId = "tpl-1",
        businessDate = businessDate.toString(),
        messageSlotId = slotId,
        status = status,
        scheduledAt = scheduledAt,
        attemptCount = attemptCount,
        failureClass = failureClass,
        summaryDetail = summaryDetail,
        confirmedAt = confirmedAt,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
