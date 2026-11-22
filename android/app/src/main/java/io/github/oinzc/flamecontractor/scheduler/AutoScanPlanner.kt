package io.github.oinzc.flamecontractor.scheduler

import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class AutoScanEvaluation(
    val eligible: Boolean,
    val shouldTriggerNow: Boolean,
    val nextAutoScanAt: Instant?,
    val blockedBySendWindow: Boolean,
)

/**
 * 纯 JVM 自动扫描调度规划器（零 android.* 依赖）：
 * - 门禁：账号启用、开启自动扫描、HEALTHY、authFailCount == 0 且存在活跃启用好友；
 * - 首次无已完成扫描或 AUTH_EXPIRED 重登恢复后触发立即补扫（仍受 60 分钟冷却与避窗约束）；
 * - 严格避开发送窗口 [sendWindowStart - 15m, sendWindowEnd] 及启用好友有效固定时刻
 *   [max(00:00, fixed - 15m), min(23:59, fixed + 60m)] 的合并阻塞区间；顺延晚于 23:55 则跳至次日 00:05 重检。
 */
object AutoScanPlanner {

    const val SEND_WINDOW_GUARD_MINUTES = 15L
    const val AUTO_SCAN_COOLDOWN_MINUTES = 60L
    const val MIN_INTERVAL_HOURS = 1
    const val MAX_INTERVAL_HOURS = 168
    const val DEFAULT_INTERVAL_HOURS = 24

    private val DEFAULT_WINDOW_START: LocalTime = LocalTime.of(20, 0)
    private val DEFAULT_WINDOW_END: LocalTime = LocalTime.of(22, 0)
    private val LATEST_FIXED_CUTOFF: LocalTime = LocalTime.of(23, 59)
    private val ROLLOVER_CUTOFF_TIME: LocalTime = LocalTime.of(23, 55)
    private val NEXT_DAY_RESUME_TIME: LocalTime = LocalTime.of(0, 5)
    private val HHMM_REGEX = Regex("""^\d{2}:\d{2}$""")

    fun evaluate(
        now: Instant,
        account: AccountEntity,
        enabledTargets: List<ContractTargetEntity>,
        latestCompletedScan: ScanRunEntity?,
        latestAnyScan: ScanRunEntity?,
        zoneId: ZoneId = DailySchedulePlanner.resolveZoneId(account.timezone),
    ): AutoScanEvaluation {
        val activeEnabledTargets = enabledTargets.filter { it.enabled && it.deletedAt == null }
        if (!account.enabled ||
            !account.autoScanEnabled ||
            account.status != AccountStatus.HEALTHY ||
            account.authFailCount != 0 ||
            activeEnabledTargets.isEmpty()
        ) {
            return AutoScanEvaluation(
                eligible = false,
                shouldTriggerNow = false,
                nextAutoScanAt = null,
                blockedBySendWindow = false,
            )
        }

        val intervalHours = account.autoScanIntervalHours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)
        val completedScan = latestCompletedScan?.takeIf { it.status == ScanRunStatus.COMPLETED }
        val lastCompletedAt = completedScan?.let { Instant.ofEpochMilli(it.finishedAt ?: it.startedAt) }

        val latestScanRefMs = latestAnyScan?.let { it.finishedAt ?: it.startedAt }
        val lastVerifiedAtMs = account.lastVerifiedAt
        val reloggedInAfterFailedScan = latestAnyScan != null &&
            latestAnyScan.status == ScanRunStatus.FAILED &&
            lastVerifiedAtMs != null &&
            latestScanRefMs != null &&
            lastVerifiedAtMs > latestScanRefMs &&
            (lastCompletedAt == null || !lastCompletedAt.isAfter(Instant.ofEpochMilli(lastVerifiedAtMs)))

        val baseDueAt = if (lastCompletedAt == null || reloggedInAfterFailedScan) {
            now
        } else {
            lastCompletedAt.plus(Duration.ofHours(intervalHours.toLong()))
        }

        val cooldownAt = account.lastAutoScanAttemptAt?.let {
            Instant.ofEpochMilli(it).plus(Duration.ofMinutes(AUTO_SCAN_COOLDOWN_MINUTES))
        }
        val rawDueAt = if (cooldownAt != null && cooldownAt.isAfter(baseDueAt)) {
            cooldownAt
        } else {
            baseDueAt
        }
        val candidateAt = if (rawDueAt.isBefore(now)) now else rawDueAt

        val (adjustedAt, blockedByWindow) = adjustOutsideBlockedWindows(
            candidateAt = candidateAt,
            account = account,
            activeEnabledTargets = activeEnabledTargets,
            zoneId = zoneId,
        )

        return AutoScanEvaluation(
            eligible = true,
            shouldTriggerNow = !adjustedAt.isAfter(now),
            nextAutoScanAt = adjustedAt,
            blockedBySendWindow = blockedByWindow,
        )
    }

    private fun adjustOutsideBlockedWindows(
        candidateAt: Instant,
        account: AccountEntity,
        activeEnabledTargets: List<ContractTargetEntity>,
        zoneId: ZoneId,
    ): Pair<Instant, Boolean> {
        var cursor = candidateAt
        var everBlocked = false

        for (dayAttempt in 0..7) {
            val date = ZonedDateTime.ofInstant(cursor, zoneId).toLocalDate()
            val intervals = buildMergedBlockedIntervals(
                date = date,
                account = account,
                activeEnabledTargets = activeEnabledTargets,
                zoneId = zoneId,
            )
            var blockedThisDay = false
            for ((start, end) in intervals) {
                if (!cursor.isBefore(start) && cursor.isBefore(end)) {
                    cursor = end
                    blockedThisDay = true
                    everBlocked = true
                }
            }
            val rolloverCutoff = ZonedDateTime.of(date, ROLLOVER_CUTOFF_TIME, zoneId).toInstant()
            if (blockedThisDay && cursor.isAfter(rolloverCutoff)) {
                cursor = ZonedDateTime.of(date.plusDays(1), NEXT_DAY_RESUME_TIME, zoneId).toInstant()
                continue
            }
            break
        }

        return cursor to everBlocked
    }

    private fun buildMergedBlockedIntervals(
        date: LocalDate,
        account: AccountEntity,
        activeEnabledTargets: List<ContractTargetEntity>,
        zoneId: ZoneId,
    ): List<Pair<Instant, Instant>> {
        val raw = mutableListOf<Pair<Instant, Instant>>()
        val dayStart = ZonedDateTime.of(date, LocalTime.MIDNIGHT, zoneId).toInstant()
        val dayLatest = ZonedDateTime.of(date, LATEST_FIXED_CUTOFF, zoneId).toInstant()

        val (winStart, winEnd) = resolveClampedWindow(account.sendWindowStart, account.sendWindowEnd)
        val winGuardStart = ZonedDateTime.of(date, winStart, zoneId)
            .minusMinutes(SEND_WINDOW_GUARD_MINUTES)
            .toInstant()
            .let { if (it.isBefore(dayStart)) dayStart else it }
        val winEndInst = ZonedDateTime.of(date, winEnd, zoneId).toInstant()
        if (winGuardStart.isBefore(winEndInst)) {
            raw += winGuardStart to winEndInst
        }

        for (target in activeEnabledTargets) {
            val fixedTime = parseStrictHhmm(target.sendTimeHhmm ?: account.sendTimeHhmm) ?: continue
            val fixedZdt = ZonedDateTime.of(date, fixedTime, zoneId)
            val fixedGuardStart = fixedZdt.minusMinutes(SEND_WINDOW_GUARD_MINUTES)
                .toInstant()
                .let { if (it.isBefore(dayStart)) dayStart else it }
            val fixedGraceEnd = fixedZdt.plusMinutes(DailySchedulePlanner.FIXED_TIME_GRACE_MINUTES)
                .toInstant()
                .let { if (it.isAfter(dayLatest)) dayLatest else it }
            if (fixedGuardStart.isBefore(fixedGraceEnd)) {
                raw += fixedGuardStart to fixedGraceEnd
            }
        }

        if (raw.isEmpty()) return emptyList()

        val sorted = raw.sortedBy { it.first }
        val merged = mutableListOf<Pair<Instant, Instant>>()
        var currentStart = sorted[0].first
        var currentEnd = sorted[0].second

        for (i in 1 until sorted.size) {
            val (nextStart, nextEnd) = sorted[i]
            if (!nextStart.isAfter(currentEnd)) {
                if (nextEnd.isAfter(currentEnd)) {
                    currentEnd = nextEnd
                }
            } else {
                merged += currentStart to currentEnd
                currentStart = nextStart
                currentEnd = nextEnd
            }
        }
        merged += currentStart to currentEnd
        return merged
    }

    private fun resolveClampedWindow(startHhmm: String, endHhmm: String): Pair<LocalTime, LocalTime> {
        val start = parseStrictHhmm(startHhmm)
        val end = parseStrictHhmm(endHhmm)
        if (start != null && end != null) {
            val clampedEnd = if (end.isAfter(DailySchedulePlanner.MAX_WINDOW_END)) {
                DailySchedulePlanner.MAX_WINDOW_END
            } else {
                end
            }
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
}
