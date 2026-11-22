package io.github.oinzc.flamecontractor.domain

import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import java.time.LocalDate

data class DailyJobBucket(
    val date: LocalDate,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val unknown: Int,
    val cancelled: Int,
)

data class HistoryStatsSummary(
    val windowDays: Int,
    val daily: List<DailyJobBucket>,
    val currentSuccessStreak: Int,
    val bestSuccessStreak: Int,
    val totalJobs: Int,
    val succeededJobs: Int,
    val failedJobs: Int,
    val unknownJobs: Int,
    val cancelledJobs: Int,
    val successRate: Double?,
)

/**
 * 纯 JVM 续火执行统计计算器（零 android.* 依赖，1:1 对齐后端 `/api/stats/summary` 口径）。
 */
object HistoryStatsCalculator {

    private class MutableBucket(val date: LocalDate) {
        var succeeded: Int = 0
        var failed: Int = 0
        var unknown: Int = 0
        var cancelled: Int = 0

        fun toImmutable(): DailyJobBucket = DailyJobBucket(
            date = date,
            total = succeeded + failed + unknown + cancelled,
            succeeded = succeeded,
            failed = failed,
            unknown = unknown,
            cancelled = cancelled,
        )
    }

    fun summarize(
        jobs: List<SendJobEntity>,
        today: LocalDate,
        windowDays: Int = 30,
    ): HistoryStatsSummary {
        val effectiveWindowDays = windowDays.coerceIn(1, 365)
        val startDate = today.minusDays((effectiveWindowDays - 1).toLong())
        val bucketByDate = LinkedHashMap<LocalDate, MutableBucket>(effectiveWindowDays)
        for (offset in 0L until effectiveWindowDays.toLong()) {
            val date = startDate.plusDays(offset)
            bucketByDate[date] = MutableBucket(date)
        }

        for (job in jobs) {
            val businessDate = runCatching { LocalDate.parse(job.businessDate) }.getOrNull() ?: continue
            if (businessDate.isBefore(startDate) || businessDate.isAfter(today)) continue
            val bucket = bucketByDate[businessDate] ?: continue
            when (job.status) {
                JobStatus.SUCCEEDED -> bucket.succeeded += 1
                JobStatus.FAILED_RETRYABLE,
                JobStatus.FAILED_TERMINAL,
                -> bucket.failed += 1
                JobStatus.UNKNOWN_REQUIRES_REVIEW -> bucket.unknown += 1
                JobStatus.CANCELLED -> bucket.cancelled += 1
                JobStatus.SCHEDULED,
                JobStatus.LEASED,
                JobStatus.OPENING_CHAT,
                JobStatus.SENDING,
                JobStatus.CONFIRMING,
                -> Unit
            }
        }

        val daily = bucketByDate.values.map { it.toImmutable() }
        var currentSuccessStreak = 0
        var bestSuccessStreak = 0
        for (bucket in daily) {
            // 仅跳过尾部尚未产生任何终态记录的 today，避免当日夜间窗口未跑前把连胜归零
            if (bucket.date == today && bucket.total == 0) {
                continue
            }
            val isSuccessDay = bucket.succeeded >= 1 && bucket.failed == 0 && bucket.unknown == 0
            if (isSuccessDay) {
                currentSuccessStreak += 1
                if (currentSuccessStreak > bestSuccessStreak) {
                    bestSuccessStreak = currentSuccessStreak
                }
            } else {
                currentSuccessStreak = 0
            }
        }

        val totalJobs = daily.sumOf { it.total }
        val succeededJobs = daily.sumOf { it.succeeded }
        val failedJobs = daily.sumOf { it.failed }
        val unknownJobs = daily.sumOf { it.unknown }
        val cancelledJobs = daily.sumOf { it.cancelled }
        val successRate = if (totalJobs > 0) {
            succeededJobs.toDouble() / totalJobs.toDouble()
        } else {
            null
        }

        return HistoryStatsSummary(
            windowDays = effectiveWindowDays,
            daily = daily,
            currentSuccessStreak = currentSuccessStreak,
            bestSuccessStreak = bestSuccessStreak,
            totalJobs = totalJobs,
            succeededJobs = succeededJobs,
            failedJobs = failedJobs,
            unknownJobs = unknownJobs,
            cancelledJobs = cancelledJobs,
            successRate = successRate,
        )
    }
}
