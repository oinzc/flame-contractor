package io.github.oinzc.flamecontractor.widget

import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.domain.SparkDeteriorationDetector
import io.github.oinzc.flamecontractor.execution.ScanReconciliationBuilder
import io.github.oinzc.flamecontractor.scheduler.DailySchedulePlanner
import io.github.oinzc.flamecontractor.ui.shell.MainNavState
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class WidgetStatusTone {
    HEALTHY,
    WARNING,
    DANGER,
    NEUTRAL,
    RUNNING,
}

enum class WidgetDayStatus {
    DONE,
    TODAY_DONE,
    TODAY_IN_PROGRESS,
    ALERT,
    EMPTY,
}

enum class WidgetRefreshPhase {
    IDLE,
    REFRESHING,
    JUST_UPDATED,
}

data class WidgetRefreshState(
    val phase: WidgetRefreshPhase = WidgetRefreshPhase.IDLE,
    val updatedAtHhmm: String = "",
) {
    val isRefreshing: Boolean
        get() = phase == WidgetRefreshPhase.REFRESHING

    val isJustUpdated: Boolean
        get() = phase == WidgetRefreshPhase.JUST_UPDATED

    val statusHintText: String
        get() = when (phase) {
            WidgetRefreshPhase.IDLE -> ""
            WidgetRefreshPhase.REFRESHING -> "同步中…"
            WidgetRefreshPhase.JUST_UPDATED -> {
                val clean = updatedAtHhmm.trim()
                if (clean.isNotEmpty()) "✓ 已刷新 $clean" else "✓ 已刷新"
            }
        }

    val compactStatusHintText: String
        get() = when (phase) {
            WidgetRefreshPhase.IDLE -> ""
            WidgetRefreshPhase.REFRESHING -> "同步中…"
            WidgetRefreshPhase.JUST_UPDATED -> {
                val clean = updatedAtHhmm.trim()
                if (clean.isNotEmpty()) "✓ $clean" else "✓ 已刷新"
            }
        }

    companion object {
        val IDLE = WidgetRefreshState(phase = WidgetRefreshPhase.IDLE)
    }
}

data class WidgetFriendItem(
    val targetId: String,
    val displayName: String,
    val maskedName: String,
    val isSentToday: Boolean,
    val badgeText: String,
    val isSparkBadge: Boolean,
)

data class FlameStatusWidgetSnapshot(
    val accountId: String,
    val accountName: String,
    val statusLabel: String,
    val statusTone: WidgetStatusTone,
    val isAlert: Boolean,
    val succeededTodayCount: Int,
    val enabledTargetCount: Int,
    val unknownReviewCount: Int,
    val progressHeadline: String,
    val footerHint: String,
    val actionLabel: String,
    val actionCategory: String,
    val isCompactWidth: Boolean,
    val showActionPill: Boolean,
    val maxOfficialSparkDays: Int? = null,
    val maxLocalStreakDays: Int = 0,
    val nextScheduleShort: String = "--:--",
    val alertDetailText: String = "",
    val recent7Days: List<WidgetDayStatus> = emptyList(),
    val topFriends: List<WidgetFriendItem> = emptyList(),
) {
    val statusPillText: String
        get() = "● $statusLabel"

    val remainingCount: Int
        get() = (enabledTargetCount - succeededTodayCount).coerceAtLeast(0)

    val isAllDoneToday: Boolean
        get() = enabledTargetCount > 0 && succeededTodayCount >= enabledTargetCount && !isAlert

    val progressFraction: Float
        get() = if (enabledTargetCount <= 0) 0f else (succeededTodayCount.toFloat() / enabledTargetCount.toFloat()).coerceIn(0f, 1f)

    val sparkHighlightText: String
        get() = when {
            maxOfficialSparkDays != null && maxOfficialSparkDays > 0 -> "🔥 ${maxOfficialSparkDays}天"
            maxLocalStreakDays > 0 -> "🔥 本机${maxLocalStreakDays}天"
            else -> "🔥 待扫描"
        }

    val sparkCompactText: String
        get() = when {
            maxOfficialSparkDays != null && maxOfficialSparkDays > 0 -> "🔥${maxOfficialSparkDays}天"
            maxLocalStreakDays > 0 -> "🔥${maxLocalStreakDays}天"
            else -> "🔥待扫"
        }
}

/**
 * 纯 JVM 桌面状态小组件数据模型计算器（零 android.* 依赖），
 * 同时驱动 5 款多尺寸小组件（圆环全景 / 2×2 进度圆环 / 4×1 极简状态条 / 7 日连胜 / 好友火花榜）。
 */
object FlameStatusWidgetCalculator {

    const val COMPACT_WIDTH_THRESHOLD_DP = 200
    const val DEFAULT_ACCOUNT_FALLBACK_NAME = "默认抖音号"
    const val ACTION_LABEL_SCAN_TARGETS = "扫描对账 →"
    const val ACTION_LABEL_HANDLE_NOW = "立即处理 →"
    private const val MAX_TOP_FRIENDS = 3
    private const val DAYS_WINDOW = 7

    private val HHMM_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun isCompactWidth(minWidthDp: Int): Boolean = minWidthDp in 1..COMPACT_WIDTH_THRESHOLD_DP

    fun maskFriendDisplayName(rawName: String, isGroup: Boolean = false): String {
        val clean = rawName.trim()
        if (clean.isEmpty()) return if (isGroup) "群**" else "友**"
        val firstCodePoint = clean.codePointAt(0)
        val firstChar = String(Character.toChars(firstCodePoint))
        return if (isGroup) "${firstChar}**（群）" else "${firstChar}**"
    }

    fun calculate(
        account: AccountEntity?,
        enabledTargets: List<ContractTargetEntity>,
        todayJobs: List<SendJobEntity>,
        unknownReviewCount: Int,
        now: Instant = Instant.now(),
        minWidthDp: Int = 0,
        networkDeferCountToday: Int = 0,
        recentJobs7Days: List<SendJobEntity> = todayJobs,
    ): FlameStatusWidgetSnapshot {
        val cleanAccountId = account?.id?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_ACCOUNT_ID
        val cleanAccountName = account?.displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_ACCOUNT_FALLBACK_NAME

        val activeEnabledTargets = enabledTargets.filter { it.enabled && it.deletedAt == null }
        val enabledCount = activeEnabledTargets.size
        val enabledIds = activeEnabledTargets.mapTo(HashSet()) { it.id }

        val zoneId = DailySchedulePlanner.resolveZoneId(account?.timezone)
        val todayDate = ZonedDateTime.ofInstant(now, zoneId).toLocalDate()
        val todayStr = todayDate.toString()

        val succeededTodayTargetIds = todayJobs
            .filter {
                it.businessDate == todayStr &&
                    it.messageSlotId == SendGuard.DAILY_SLOT_ID &&
                    it.status == JobStatus.SUCCEEDED &&
                    it.targetId in enabledIds
            }
            .mapTo(HashSet()) { it.targetId }
        val succeededCount = succeededTodayTargetIds.size

        val safeUnknownCount = unknownReviewCount.coerceAtLeast(0)

        val (statusLabel, statusTone, isAlert) = when {
            safeUnknownCount > 0 -> Triple(
                "待核对 $safeUnknownCount 条",
                WidgetStatusTone.DANGER,
                true,
            )

            account == null -> Triple(
                "待登录",
                WidgetStatusTone.WARNING,
                false,
            )

            account.status == AccountStatus.AUTH_EXPIRED -> Triple(
                "登录已失效",
                WidgetStatusTone.DANGER,
                true,
            )

            account.status == AccountStatus.VERIFICATION_REQUIRED -> Triple(
                "需身份验证",
                WidgetStatusTone.WARNING,
                true,
            )

            account.status == AccountStatus.DOM_BROKEN -> Triple(
                "页面异常",
                WidgetStatusTone.DANGER,
                true,
            )

            account.status == AccountStatus.COOLDOWN -> Triple(
                "冷却保护",
                WidgetStatusTone.WARNING,
                true,
            )

            !account.enabled || account.status == AccountStatus.PAUSED -> Triple(
                "已停用",
                WidgetStatusTone.NEUTRAL,
                false,
            )

            account.status == AccountStatus.PENDING_LOGIN -> Triple(
                "待登录",
                WidgetStatusTone.WARNING,
                false,
            )

            account.status == AccountStatus.RUNNING -> Triple(
                "执行中",
                WidgetStatusTone.RUNNING,
                false,
            )

            else -> Triple(
                "运行正常",
                WidgetStatusTone.HEALTHY,
                false,
            )
        }

        val progressHeadline = if (enabledCount == 0) {
            "暂无启用好友"
        } else {
            "今日已完成 $succeededCount / $enabledCount 位"
        }

        val evaluation = if (account != null && account.enabled && account.status == AccountStatus.HEALTHY) {
            DailySchedulePlanner.evaluateNow(
                now = now,
                account = account,
                targets = activeEnabledTargets,
                todayJobs = todayJobs,
                zoneId = zoneId,
                networkDeferCountToday = networkDeferCountToday,
            )
        } else {
            null
        }

        val nextWakeAt = evaluation?.let { eval ->
            eval.pendingTargets.minOfOrNull { it.effectiveDueAt }
                ?: eval.dueTargets.minOfOrNull { it.effectiveDueAt }
                ?: eval.nextWakeAt
        }

        val nextWakeFormatted = nextWakeAt?.let {
            val zdt = ZonedDateTime.ofInstant(it, zoneId)
            val hhmm = zdt.format(HHMM_FORMATTER)
            if (zdt.toLocalDate() > todayDate) "明日$hhmm" else hhmm
        }

        val footerHint = when {
            safeUnknownCount > 0 -> "待核对 $safeUnknownCount 条"
            account == null -> "点击打开应用登录"
            account.status == AccountStatus.AUTH_EXPIRED -> "需重新登录抖音"
            account.status == AccountStatus.VERIFICATION_REQUIRED -> "需完成身份验证"
            account.status == AccountStatus.DOM_BROKEN -> "请检查网页定位器"
            account.status == AccountStatus.COOLDOWN -> "连续失败已触发冷却"
            !account.enabled || account.status == AccountStatus.PAUSED -> "自动调度已停用"
            account.status == AccountStatus.PENDING_LOGIN ->
                "发送窗口 ${account.sendWindowStart}-${account.sendWindowEnd}"

            account.status == AccountStatus.RUNNING -> "正在执行今日续火"
            enabledCount > 0 && succeededCount >= enabledCount -> "今日已全部完成"
            nextWakeAt != null -> {
                val hhmm = ZonedDateTime.ofInstant(nextWakeAt, zoneId).format(HHMM_FORMATTER)
                "下次排程 $hhmm"
            }

            else -> "发送窗口 ${account.sendWindowStart}-${account.sendWindowEnd}"
        }

        val nextScheduleShort = when {
            safeUnknownCount > 0 -> "待核对"
            account == null -> "待登录"
            !account.enabled || account.status == AccountStatus.PAUSED -> "已停用"
            account.status == AccountStatus.RUNNING -> "执行中"
            nextWakeFormatted != null -> nextWakeFormatted
            enabledCount > 0 && succeededCount >= enabledCount -> "明日${account.sendWindowStart}"
            else -> account.sendWindowStart
        }

        val alertDetailText = when {
            safeUnknownCount > 0 -> "发送结果未明，系统已拦截自动重发并留存现场截图，请点击人工核对。"
            account?.status == AccountStatus.AUTH_EXPIRED -> "抖音登录态已失效，自动续火已暂停，请点击重新扫码恢复。"
            account?.status == AccountStatus.VERIFICATION_REQUIRED -> "触发抖音安全验证，请点击打开应用完成验证后恢复续火。"
            account?.status == AccountStatus.DOM_BROKEN -> "检测到页面结构变化，请点击前往结构体检核对定位器。"
            account?.status == AccountStatus.COOLDOWN -> "连续失败已触发冷却保护，请点击查看原因并解除冷却。"
            else -> ""
        }

        val maxOfficialSparkDays = activeEnabledTargets
            .mapNotNull { it.sparkDays?.takeIf { days -> days > 0 } }
            .maxOrNull()
        val maxLocalStreakDays = activeEnabledTargets.maxOfOrNull { it.streakDays } ?: 0

        val recent7Days = computeRecent7Days(
            todayDate = todayDate,
            recentJobs = recentJobs7Days,
            enabledCount = enabledCount,
            succeededTodayCount = succeededCount,
            hasAlertToday = isAlert,
        )

        val topFriends = buildTopFriends(
            targets = activeEnabledTargets,
            succeededTodayIds = succeededTodayTargetIds,
        )

        val compact = isCompactWidth(minWidthDp)
        val actionLabel = if (isAlert) ACTION_LABEL_HANDLE_NOW else ACTION_LABEL_SCAN_TARGETS
        val actionCategory = if (isAlert) MainNavState.SHORTCUT_TODAY else MainNavState.SHORTCUT_TARGETS

        return FlameStatusWidgetSnapshot(
            accountId = cleanAccountId,
            accountName = cleanAccountName,
            statusLabel = statusLabel,
            statusTone = statusTone,
            isAlert = isAlert,
            succeededTodayCount = succeededCount,
            enabledTargetCount = enabledCount,
            unknownReviewCount = safeUnknownCount,
            progressHeadline = progressHeadline,
            footerHint = footerHint,
            actionLabel = actionLabel,
            actionCategory = actionCategory,
            isCompactWidth = compact,
            showActionPill = !compact,
            maxOfficialSparkDays = maxOfficialSparkDays,
            maxLocalStreakDays = maxLocalStreakDays,
            nextScheduleShort = nextScheduleShort,
            alertDetailText = alertDetailText,
            recent7Days = recent7Days,
            topFriends = topFriends,
        )
    }

    private fun computeRecent7Days(
        todayDate: LocalDate,
        recentJobs: List<SendJobEntity>,
        enabledCount: Int,
        succeededTodayCount: Int,
        hasAlertToday: Boolean,
    ): List<WidgetDayStatus> {
        val jobsByDate = recentJobs.groupBy { it.businessDate }
        return (DAYS_WINDOW - 1 downTo 0).map { offset ->
            val date = todayDate.minusDays(offset.toLong())
            val dateStr = date.toString()
            val dayJobs = jobsByDate[dateStr].orEmpty()
            val isToday = offset == 0
            val hasAlert = dayJobs.any {
                it.status == JobStatus.UNKNOWN_REQUIRES_REVIEW || it.status == JobStatus.FAILED_TERMINAL
            }
            val hasSuccess = dayJobs.any { it.status == JobStatus.SUCCEEDED }

            when {
                isToday && (hasAlertToday || hasAlert) -> WidgetDayStatus.ALERT
                isToday && enabledCount > 0 && succeededTodayCount >= enabledCount -> WidgetDayStatus.TODAY_DONE
                isToday -> WidgetDayStatus.TODAY_IN_PROGRESS
                hasAlert -> WidgetDayStatus.ALERT
                hasSuccess -> WidgetDayStatus.DONE
                else -> WidgetDayStatus.EMPTY
            }
        }
    }

    private fun buildTopFriends(
        targets: List<ContractTargetEntity>,
        succeededTodayIds: Set<String>,
    ): List<WidgetFriendItem> {
        val sorted = targets.sortedWith(
            compareByDescending<ContractTargetEntity> { it.sparkDays?.takeIf { d -> d > 0 } ?: -1 }
                .thenByDescending {
                    ScanReconciliationBuilder.hasOfficialSpark(
                        sparkDays = it.sparkDays,
                        sparkKind = it.sparkKind,
                        sparkLabel = it.sparkLabel,
                    )
                }
                .thenByDescending { it.streakDays }
                .thenBy { it.nicknameSnapshot },
        )
        return sorted.take(MAX_TOP_FRIENDS).map { target ->
            val sent = target.id in succeededTodayIds
            val isGroup = target.conversationType == "group"
            val cleanLabel = target.sparkLabel?.trim()?.takeIf {
                it.isNotEmpty() && it != SparkDeteriorationDetector.NO_SPARK_LABEL
            }
            val (badgeText, isSpark) = when {
                target.sparkDays != null && target.sparkDays > 0 -> "🔥 ${target.sparkDays}天" to true
                cleanLabel != null -> (if (sent) cleanLabel else "待续 · $cleanLabel") to false
                sent -> "今日已续" to false
                else -> "待发送" to false
            }
            WidgetFriendItem(
                targetId = target.id,
                displayName = if (isGroup) "${target.nicknameSnapshot}（群）" else target.nicknameSnapshot,
                maskedName = maskFriendDisplayName(target.nicknameSnapshot, isGroup),
                isSentToday = sent,
                badgeText = badgeText,
                isSparkBadge = isSpark,
            )
        }
    }
}
