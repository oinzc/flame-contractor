package io.github.oinzc.flamecontractor.scheduler

import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.domain.SendGuard
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class TargetDueSchedule(
    val targetId: String,
    val businessDate: LocalDate,
    val isFixedTime: Boolean,
    val baseDueAt: Instant,
    val dueAt: Instant,
    val cutoffAt: Instant,
)

data class ScheduledTarget(
    val target: ContractTargetEntity,
    val schedule: TargetDueSchedule,
    val effectiveDueAt: Instant,
    val cutoffAt: Instant,
    val isRetry: Boolean = false,
    val isNetworkRetry: Boolean = false,
    val attemptCount: Int = 0,
)

data class ScheduleEvaluation(
    val businessDate: LocalDate,
    val evaluatedAt: Instant,
    val shouldTriggerAuthRecheck: Boolean = false,
    val authRecheckAt: Instant? = null,
    val dueTargets: List<ScheduledTarget> = emptyList(),
    val pendingTargets: List<ScheduledTarget> = emptyList(),
    val expiredTargets: List<ScheduledTarget> = emptyList(),
    val nextWakeAt: Instant? = null,
)

/**
 * 纯 JVM 每日同日防过窗调度规划器（零 android.* 依赖）。
 *
 * 规则：
 * 1. 禁止跨午夜窗口，严格约束 00:00 <= start < end <= 23:55；
 * 2. 窗口目标确定性取点 + 0..15m 抖动并钳制 <= windowEnd - 2m；
 * 3. 固定时刻享有 60 分钟宽限窗（受当日 23:59 截断），过窗即跳过不补发；
 * 4. 仅处理当日 businessDate，跨午夜昨日遗留单据直接失效；
 * 5. FAILED_RETRYABLE 退避 5m / 15m 且严格受 RETRY_MAX_COUNT = 2 与 cutoffAt 限制；
 *    NETWORK 顺延 10m 最多 5 次；authFailCount == 1 暂停普通派发并预约 10 分钟复判。
 */
object DailySchedulePlanner {

    const val FIXED_TIME_GRACE_MINUTES = 60L
    const val WINDOW_END_SAFETY_MARGIN_MINUTES = 2L
    val MAX_WINDOW_END: LocalTime = LocalTime.of(23, 55)
    val RETRY_BACKOFF_MINUTES: List<Long> = listOf(5L, 15L)
    const val NETWORK_DEFER_INTERVAL_MINUTES = 10L
    const val NETWORK_DEFER_MAX_COUNT = 5
    const val AUTH_RECHECK_DELAY_MINUTES = 10L
    const val BATCH_COALESCE_WINDOW_MINUTES = 5L

    private const val MAX_JITTER_MINUTES = 15
    private val DEFAULT_WINDOW_START: LocalTime = LocalTime.of(20, 0)
    private val DEFAULT_WINDOW_END: LocalTime = LocalTime.of(22, 0)
    private val LATEST_FIXED_CUTOFF: LocalTime = LocalTime.of(23, 59)
    private val HHMM_REGEX = Regex("^\\d{2}:\\d{2}$")

    fun validateWindow(startHhmm: String, endHhmm: String): Boolean {
        val start = parseStrictHhmm(startHhmm) ?: return false
        val end = parseStrictHhmm(endHhmm) ?: return false
        return start.isBefore(end) && !end.isAfter(MAX_WINDOW_END)
    }

    fun resolveZoneId(timezone: String?): ZoneId {
        val cleaned = timezone?.trim().orEmpty()
        if (cleaned.isEmpty()) return ZoneId.systemDefault()
        return try {
            ZoneId.of(cleaned)
        } catch (_: Exception) {
            ZoneId.systemDefault()
        }
    }

    fun computeTargetDueTime(
        businessDate: LocalDate,
        account: AccountEntity,
        target: ContractTargetEntity,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): TargetDueSchedule {
        val fixedTime = parseStrictHhmm(target.sendTimeHhmm ?: account.sendTimeHhmm)
        if (fixedTime != null) {
            val fixedInstant = ZonedDateTime.of(businessDate, fixedTime, zoneId).toInstant()
            val graceLimit = fixedInstant.plus(Duration.ofMinutes(FIXED_TIME_GRACE_MINUTES))
            val dayLatest = ZonedDateTime.of(businessDate, LATEST_FIXED_CUTOFF, zoneId).toInstant()
            val cutoffAt = if (graceLimit.isBefore(dayLatest)) graceLimit else dayLatest
            return TargetDueSchedule(
                targetId = target.id,
                businessDate = businessDate,
                isFixedTime = true,
                baseDueAt = fixedInstant,
                dueAt = fixedInstant,
                cutoffAt = cutoffAt,
            )
        }

        val (startTime, endTime) = resolveClampedWindow(account.sendWindowStart, account.sendWindowEnd)
        val startInstant = ZonedDateTime.of(businessDate, startTime, zoneId).toInstant()
        val endInstant = ZonedDateTime.of(businessDate, endTime, zoneId).toInstant()
        val windowSeconds = Duration.between(startInstant, endInstant).seconds.coerceAtLeast(1L)

        val digest = sha256("$businessDate:${account.id}:${target.id}")
        val baseBits = bytesToPositiveLong(digest, 0)
        val jitterBits = bytesToPositiveLong(digest, 8)

        val baseOffsetSec = baseBits % windowSeconds
        val baseDueAt = startInstant.plusSeconds(baseOffsetSec)

        val jitterMaxSec = account.jitterMaxMinutes.coerceIn(0, MAX_JITTER_MINUTES) * 60L
        val jitterSec = if (jitterMaxSec > 0L) jitterBits % (jitterMaxSec + 1L) else 0L

        val safeMarginCeiling = endInstant.minus(Duration.ofMinutes(WINDOW_END_SAFETY_MARGIN_MINUTES))
            .let { if (it.isBefore(startInstant)) startInstant else it }

        val rawWithJitter = baseDueAt.plusSeconds(jitterSec)
        val clampedDueAt = when {
            rawWithJitter.isBefore(startInstant) -> startInstant
            rawWithJitter.isAfter(safeMarginCeiling) -> safeMarginCeiling
            else -> rawWithJitter
        }

        return TargetDueSchedule(
            targetId = target.id,
            businessDate = businessDate,
            isFixedTime = false,
            baseDueAt = baseDueAt,
            dueAt = clampedDueAt,
            cutoffAt = endInstant,
        )
    }

    fun evaluateNow(
        now: Instant,
        account: AccountEntity,
        targets: List<ContractTargetEntity>,
        todayJobs: List<SendJobEntity>,
        zoneId: ZoneId = ZoneId.systemDefault(),
        networkDeferCountToday: Int = 0,
    ): ScheduleEvaluation {
        val today = ZonedDateTime.ofInstant(now, zoneId).toLocalDate()
        val todayStr = today.toString()

        if (!account.enabled || account.status != AccountStatus.HEALTHY) {
            return ScheduleEvaluation(
                businessDate = today,
                evaluatedAt = now,
                nextWakeAt = null,
            )
        }

        if (account.authFailCount == 1) {
            val authRecheckAt = Instant.ofEpochMilli(account.updatedAt)
                .plus(Duration.ofMinutes(AUTH_RECHECK_DELAY_MINUTES))
            return if (now.isBefore(authRecheckAt)) {
                ScheduleEvaluation(
                    businessDate = today,
                    evaluatedAt = now,
                    shouldTriggerAuthRecheck = false,
                    authRecheckAt = authRecheckAt,
                    nextWakeAt = authRecheckAt,
                )
            } else {
                ScheduleEvaluation(
                    businessDate = today,
                    evaluatedAt = now,
                    shouldTriggerAuthRecheck = true,
                    authRecheckAt = authRecheckAt,
                    nextWakeAt = null,
                )
            }
        }

        if (account.authFailCount > 1) {
            return ScheduleEvaluation(
                businessDate = today,
                evaluatedAt = now,
                nextWakeAt = null,
            )
        }

        val jobsForToday = todayJobs.filter { it.businessDate == todayStr }
        val jobsByTarget = jobsForToday.groupBy { it.targetId }
        val activeTargets = targets.filter { it.enabled && it.deletedAt == null }

        val candidates = mutableListOf<ScheduledTarget>()
        val expiredTargets = mutableListOf<ScheduledTarget>()

        for (target in activeTargets) {
            val targetJobsToday = jobsByTarget[target.id].orEmpty()
            if (!SendGuard.canDispatchDaily(targetJobsToday)) {
                continue
            }

            val baseSchedule = computeTargetDueTime(today, account, target, zoneId)
            val cutoffAt = baseSchedule.cutoffAt
            val dailyJob = targetJobsToday.firstOrNull { it.messageSlotId == SendGuard.DAILY_SLOT_ID }

            val scheduledEntry = buildCandidateEntry(
                target = target,
                baseSchedule = baseSchedule,
                dailyJob = dailyJob,
                networkDeferCountToday = networkDeferCountToday,
            ) ?: continue

            if (!now.isBefore(cutoffAt) || !scheduledEntry.effectiveDueAt.isBefore(cutoffAt)) {
                expiredTargets += scheduledEntry
                continue
            }

            candidates += scheduledEntry
        }

        val sortedCandidates = candidates.sortedWith(
            compareBy<ScheduledTarget>(
                { it.effectiveDueAt },
                { it.target.createdAt },
                { it.target.id },
            ),
        )

        val hasAnyDueNow = sortedCandidates.any { !it.effectiveDueAt.isAfter(now) }
        val dueTargets: List<ScheduledTarget>
        val pendingTargets: List<ScheduledTarget>

        if (hasAnyDueNow) {
            val coalesceLimit = now.plus(Duration.ofMinutes(BATCH_COALESCE_WINDOW_MINUTES))
            dueTargets = sortedCandidates.filter { !it.effectiveDueAt.isAfter(coalesceLimit) }
            pendingTargets = sortedCandidates.filter { it.effectiveDueAt.isAfter(coalesceLimit) }
        } else {
            dueTargets = emptyList()
            pendingTargets = sortedCandidates
        }

        val nextWakeAt = if (pendingTargets.isNotEmpty()) {
            pendingTargets.minOf { it.effectiveDueAt }
        } else {
            computeNextDayWakeAt(
                tomorrow = today.plusDays(1),
                account = account,
                activeTargets = activeTargets,
                zoneId = zoneId,
            )
        }

        return ScheduleEvaluation(
            businessDate = today,
            evaluatedAt = now,
            shouldTriggerAuthRecheck = false,
            authRecheckAt = null,
            dueTargets = dueTargets,
            pendingTargets = pendingTargets,
            expiredTargets = expiredTargets,
            nextWakeAt = nextWakeAt,
        )
    }

    private fun buildCandidateEntry(
        target: ContractTargetEntity,
        baseSchedule: TargetDueSchedule,
        dailyJob: SendJobEntity?,
        networkDeferCountToday: Int,
    ): ScheduledTarget? {
        if (dailyJob != null && dailyJob.status == JobStatus.FAILED_RETRYABLE) {
            if (dailyJob.failureClass == FailureClass.NETWORK) {
                if (networkDeferCountToday >= NETWORK_DEFER_MAX_COUNT) {
                    return null
                }
                val retryAt = Instant.ofEpochMilli(dailyJob.updatedAt)
                    .plus(Duration.ofMinutes(NETWORK_DEFER_INTERVAL_MINUTES))
                return ScheduledTarget(
                    target = target,
                    schedule = baseSchedule,
                    effectiveDueAt = retryAt,
                    cutoffAt = baseSchedule.cutoffAt,
                    isRetry = true,
                    isNetworkRetry = true,
                    attemptCount = dailyJob.attemptCount,
                )
            }

            if (dailyJob.attemptCount > SendGuard.RETRY_MAX_COUNT) {
                return null
            }
            val backoffIndex = (dailyJob.attemptCount - 1).coerceIn(0, RETRY_BACKOFF_MINUTES.lastIndex)
            val backoffMinutes = RETRY_BACKOFF_MINUTES[backoffIndex]
            val retryAt = Instant.ofEpochMilli(dailyJob.updatedAt)
                .plus(Duration.ofMinutes(backoffMinutes))
            return ScheduledTarget(
                target = target,
                schedule = baseSchedule,
                effectiveDueAt = retryAt,
                cutoffAt = baseSchedule.cutoffAt,
                isRetry = true,
                isNetworkRetry = false,
                attemptCount = dailyJob.attemptCount,
            )
        }

        return ScheduledTarget(
            target = target,
            schedule = baseSchedule,
            effectiveDueAt = baseSchedule.dueAt,
            cutoffAt = baseSchedule.cutoffAt,
            isRetry = false,
            isNetworkRetry = false,
            attemptCount = dailyJob?.attemptCount ?: 0,
        )
    }

    private fun computeNextDayWakeAt(
        tomorrow: LocalDate,
        account: AccountEntity,
        activeTargets: List<ContractTargetEntity>,
        zoneId: ZoneId,
    ): Instant? {
        if (activeTargets.isEmpty()) return null
        return activeTargets
            .map { computeTargetDueTime(tomorrow, account, it, zoneId) }
            .filter { it.dueAt.isBefore(it.cutoffAt) }
            .minOfOrNull { it.dueAt }
    }

    private fun resolveClampedWindow(startHhmm: String, endHhmm: String): Pair<LocalTime, LocalTime> {
        val start = parseStrictHhmm(startHhmm)
        val end = parseStrictHhmm(endHhmm)
        if (start != null && end != null) {
            val clampedEnd = if (end.isAfter(MAX_WINDOW_END)) MAX_WINDOW_END else end
            if (start.isBefore(clampedEnd)) {
                return start to clampedEnd
            }
        }
        return DEFAULT_WINDOW_START to DEFAULT_WINDOW_END
    }

    private fun parseStrictHhmm(raw: String?): LocalTime? {
        val text = raw?.trim() ?: return null
        if (!HHMM_REGEX.matches(text)) return null
        val hour = text.substring(0, 2).toIntOrNull() ?: return null
        val minute = text.substring(3, 5).toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    private fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))

    private fun bytesToPositiveLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFFL)
        }
        return value and Long.MAX_VALUE
    }
}
