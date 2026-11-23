package io.github.oinzc.flamecontractor.widget

import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.scheduler.DailySchedulePlanner
import io.github.oinzc.flamecontractor.ui.shell.MainNavState
import java.time.Instant
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
) {
    val statusPillText: String
        get() = "● $statusLabel"
}

/**
 * 纯 JVM 桌面状态小组件数据模型计算器（零 android.* 依赖），
 * 负责将账号健康状态、今日完成进度、待核对数量、下次排程时间与小组件宽度映射为可渲染快照。
 */
object FlameStatusWidgetCalculator {

    const val COMPACT_WIDTH_THRESHOLD_DP = 200
    const val DEFAULT_ACCOUNT_FALLBACK_NAME = "默认抖音号"
    const val ACTION_LABEL_SCAN_TARGETS = "扫描对账 →"
    const val ACTION_LABEL_HANDLE_NOW = "立即处理 →"

    private val HHMM_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun isCompactWidth(minWidthDp: Int): Boolean = minWidthDp in 1..COMPACT_WIDTH_THRESHOLD_DP

    fun calculate(
        account: AccountEntity?,
        enabledTargets: List<ContractTargetEntity>,
        todayJobs: List<SendJobEntity>,
        unknownReviewCount: Int,
        now: Instant = Instant.now(),
        minWidthDp: Int = 0,
        networkDeferCountToday: Int = 0,
    ): FlameStatusWidgetSnapshot {
        val cleanAccountId = account?.id?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_ACCOUNT_ID
        val cleanAccountName = account?.displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_ACCOUNT_FALLBACK_NAME

        val activeEnabledTargets = enabledTargets.filter { it.enabled && it.deletedAt == null }
        val enabledCount = activeEnabledTargets.size
        val enabledIds = activeEnabledTargets.mapTo(HashSet()) { it.id }

        val zoneId = DailySchedulePlanner.resolveZoneId(account?.timezone)
        val todayStr = ZonedDateTime.ofInstant(now, zoneId).toLocalDate().toString()

        val succeededCount = todayJobs
            .filter {
                it.businessDate == todayStr &&
                    it.messageSlotId == SendGuard.DAILY_SLOT_ID &&
                    it.status == JobStatus.SUCCEEDED &&
                    it.targetId in enabledIds
            }
            .mapTo(HashSet()) { it.targetId }
            .size

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
                val formatted = ZonedDateTime.ofInstant(nextWakeAt, zoneId).format(HHMM_FORMATTER)
                "下次排程 $formatted"
            }

            else -> "发送窗口 ${account.sendWindowStart}-${account.sendWindowEnd}"
        }

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
        )
    }
}
