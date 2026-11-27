package io.github.oinzc.flamecontractor.ui.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.oinzc.flamecontractor.core.theme.UiDisplayMode
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.scheduler.DailySchedulePlanner
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameDisplayModeToggle
import io.github.oinzc.flamecontractor.ui.components.FlameFeedbackTopButton
import io.github.oinzc.flamecontractor.ui.components.FlameFormFactor
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.LocalFlameWindowLayout
import io.github.oinzc.flamecontractor.ui.components.flamePressScale
import io.github.oinzc.flamecontractor.ui.components.performFlameLightTick
import io.github.oinzc.flamecontractor.ui.components.rememberReducedMotion
import io.github.oinzc.flamecontractor.ui.components.resolveFlameToneColors
import io.github.oinzc.flamecontractor.ui.shell.MainTab
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.shell.accountStatusStyle
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal enum class SimpleGuidedAction {
    LOGIN,
    SELECT_TARGETS,
    RECOVER_COOLDOWN,
    OPEN_DIAGNOSTICS,
    OPEN_SCHEDULE,
}

internal sealed interface SimpleActionState {
    data class Executing(
        val progressCurrent: Int,
        val progressTotal: Int,
        val progressText: String,
    ) : SimpleActionState

    data class NeedsUnknownReview(
        val unknownCount: Int,
        val firstJobId: String?,
    ) : SimpleActionState

    data class Guided(
        val action: SimpleGuidedAction,
        val title: String,
        val subtitle: String,
    ) : SimpleActionState

    data class ReadyToSend(
        val succeededCount: Int,
        val totalCount: Int,
        val pendingCount: Int,
    ) : SimpleActionState

    data class AllCompletedToday(
        val succeededCount: Int,
        val totalCount: Int,
        val allSucceeded: Boolean,
    ) : SimpleActionState
}

internal enum class SimpleProgressDotKind {
    DONE_EMBER,
    DONE_HEALTHY,
    UNKNOWN_DANGER,
    PENDING,
}

internal data class SimpleProgressDotStripModel(
    val dots: List<SimpleProgressDotKind>,
    val overflowCount: Int,
)

internal const val SIMPLE_MAX_SINGLE_ROW_DOTS = 12
internal const val SIMPLE_MAX_VISIBLE_DOTS = 24

internal fun layoutSimpleProgressDotRows(
    dots: List<SimpleProgressDotKind>,
    maxPerRow: Int = SIMPLE_MAX_SINGLE_ROW_DOTS,
): List<List<SimpleProgressDotKind>> {
    if (dots.isEmpty() || maxPerRow <= 0) return emptyList()
    if (dots.size <= maxPerRow) return listOf(dots)
    val rowCount = (dots.size + maxPerRow - 1) / maxPerRow
    val baseSize = dots.size / rowCount
    val remainder = dots.size % rowCount
    val rows = ArrayList<List<SimpleProgressDotKind>>(rowCount)
    var cursor = 0
    for (rowIdx in 0 until rowCount) {
        val count = baseSize + if (rowIdx < remainder) 1 else 0
        rows.add(dots.subList(cursor, cursor + count))
        cursor += count
    }
    return rows
}

internal fun buildSimpleProgressDots(
    totalCount: Int,
    succeededCount: Int,
    unknownCount: Int,
    allCompleted: Boolean,
    maxVisibleDots: Int = SIMPLE_MAX_VISIBLE_DOTS,
): SimpleProgressDotStripModel {
    val safeTotal = totalCount.coerceAtLeast(0)
    if (safeTotal == 0 || maxVisibleDots <= 0) {
        return SimpleProgressDotStripModel(dots = emptyList(), overflowCount = 0)
    }
    val safeUnknown = unknownCount.coerceIn(0, safeTotal)
    val safeSucceeded = succeededCount.coerceIn(0, (safeTotal - safeUnknown).coerceAtLeast(0))
    val overflow = (safeTotal - maxVisibleDots).coerceAtLeast(0)
    val visibleCount = safeTotal.coerceAtMost(maxVisibleDots)

    val visibleUnknown = safeUnknown.coerceAtMost(visibleCount)
    val remainingSlots = (visibleCount - visibleUnknown).coerceAtLeast(0)
    val visibleSucceeded = if (overflow == 0) {
        safeSucceeded.coerceAtMost(remainingSlots)
    } else {
        ((safeSucceeded.toFloat() / safeTotal.toFloat()) * remainingSlots)
            .toInt()
            .coerceIn(if (safeSucceeded > 0 && remainingSlots > 0) 1 else 0, remainingSlots)
    }
    val visiblePending = (visibleCount - visibleUnknown - visibleSucceeded).coerceAtLeast(0)

    val doneKind = if (allCompleted && safeUnknown == 0) {
        SimpleProgressDotKind.DONE_HEALTHY
    } else {
        SimpleProgressDotKind.DONE_EMBER
    }

    val dots = buildList(visibleCount) {
        repeat(visibleSucceeded) { add(doneKind) }
        repeat(visiblePending) { add(SimpleProgressDotKind.PENDING) }
        repeat(visibleUnknown) { add(SimpleProgressDotKind.UNKNOWN_DANGER) }
    }
    return SimpleProgressDotStripModel(dots = dots, overflowCount = overflow)
}

internal fun resolveSimpleActionState(state: TodayUiState): SimpleActionState {
    val account = state.account
    val status = account?.status ?: AccountStatus.PENDING_LOGIN
    val isExecuting = state.executionState.running || status == AccountStatus.RUNNING

    if (isExecuting) {
        val progressText = when {
            state.executionState.progressTotal > 0 ->
                "${state.executionState.progressCurrent} / ${state.executionState.progressTotal}"

            !state.executionState.currentTask.isNullOrBlank() ->
                state.executionState.currentTask

            else -> "请稍候"
        }
        return SimpleActionState.Executing(
            progressCurrent = state.executionState.progressCurrent,
            progressTotal = state.executionState.progressTotal,
            progressText = progressText,
        )
    }

    if (state.unknownReviewJobs.isNotEmpty()) {
        return SimpleActionState.NeedsUnknownReview(
            unknownCount = state.unknownReviewJobs.size,
            firstJobId = state.unknownReviewJobs.firstOrNull()?.job?.id,
        )
    }

    if (state.hasBlockingTodayJob) {
        return SimpleActionState.Executing(
            progressCurrent = state.succeededTodayCount,
            progressTotal = state.enabledTargetCount,
            progressText = "等待发送确认…",
        )
    }

    if (account == null || status == AccountStatus.PENDING_LOGIN) {
        return SimpleActionState.Guided(
            action = SimpleGuidedAction.LOGIN,
            title = "登录抖音",
            subtitle = "先绑定你的抖音号",
        )
    }

    when (status) {
        AccountStatus.AUTH_EXPIRED -> {
            return SimpleActionState.Guided(
                action = SimpleGuidedAction.LOGIN,
                title = "登录抖音",
                subtitle = "登录态已失效",
            )
        }

        AccountStatus.VERIFICATION_REQUIRED -> {
            return SimpleActionState.Guided(
                action = SimpleGuidedAction.LOGIN,
                title = "登录抖音",
                subtitle = "需本人安全验证",
            )
        }

        AccountStatus.COOLDOWN -> {
            return SimpleActionState.Guided(
                action = SimpleGuidedAction.RECOVER_COOLDOWN,
                title = "处理熔断",
                subtitle = "连续失败已暂停",
            )
        }

        AccountStatus.DOM_BROKEN -> {
            return SimpleActionState.Guided(
                action = SimpleGuidedAction.OPEN_DIAGNOSTICS,
                title = "页面诊断",
                subtitle = "网页结构有变动",
            )
        }

        AccountStatus.PAUSED -> {
            return SimpleActionState.Guided(
                action = SimpleGuidedAction.OPEN_SCHEDULE,
                title = "开启续火",
                subtitle = "自动续火已暂停",
            )
        }

        AccountStatus.HEALTHY,
        AccountStatus.RUNNING,
        AccountStatus.PENDING_LOGIN,
        -> Unit
    }

    if (!account.enabled) {
        return SimpleActionState.Guided(
            action = SimpleGuidedAction.OPEN_SCHEDULE,
            title = "开启续火",
            subtitle = "续火总开关已关",
        )
    }

    if (state.enabledTargetCount == 0) {
        return SimpleActionState.Guided(
            action = SimpleGuidedAction.SELECT_TARGETS,
            title = "去选好友",
            subtitle = "尚未选择续火好友",
        )
    }

    if (state.boundEnabledTargetCount == 0) {
        return SimpleActionState.Guided(
            action = SimpleGuidedAction.SELECT_TARGETS,
            title = "去选好友",
            subtitle = "请先给好友绑模板",
        )
    }

    if (state.pendingTargetCount > 0) {
        return SimpleActionState.ReadyToSend(
            succeededCount = state.succeededTodayCount,
            totalCount = state.enabledTargetCount,
            pendingCount = state.pendingTargetCount,
        )
    }

    return SimpleActionState.AllCompletedToday(
        succeededCount = state.succeededTodayCount,
        totalCount = state.enabledTargetCount,
        allSucceeded = state.succeededTodayCount >= state.enabledTargetCount,
    )
}

internal fun resolveSimplePrimaryCtaLabel(
    actionState: SimpleActionState,
    state: TodayUiState,
): String = when (actionState) {
    is SimpleActionState.Executing -> "正在续火（${actionState.progressText}）"
    is SimpleActionState.NeedsUnknownReview -> "去核对结果（${actionState.unknownCount} 位）"
    is SimpleActionState.ReadyToSend -> "立即续火（还差 ${actionState.pendingCount} 位）"
    is SimpleActionState.AllCompletedToday -> if (actionState.allSucceeded) {
        "今日已续完 · 点此重发"
    } else {
        "今日已处理 · 点此重发"
    }

    is SimpleActionState.Guided -> when (actionState.action) {
        SimpleGuidedAction.LOGIN -> when (state.account?.status) {
            AccountStatus.AUTH_EXPIRED -> "重新登录抖音"
            AccountStatus.VERIFICATION_REQUIRED -> "去完成安全验证"
            else -> "登录抖音账号"
        }

        SimpleGuidedAction.SELECT_TARGETS -> if (state.enabledTargetCount == 0) {
            "去扫描并挑选好友"
        } else {
            "去给好友绑定模板"
        }

        SimpleGuidedAction.RECOVER_COOLDOWN -> "查看原因并恢复"
        SimpleGuidedAction.OPEN_DIAGNOSTICS -> "前往页面诊断"
        SimpleGuidedAction.OPEN_SCHEDULE -> "开启自动续火"
    }
}

internal fun formatSimpleScheduleLine(
    state: TodayUiState,
    now: Instant = Instant.now(),
): String {
    val account = state.account ?: return "尚未绑定抖音账号 · 请先登录"
    if (!account.enabled || account.status == AccountStatus.PAUSED) {
        return "自动续火已暂停 · 点此开启"
    }
    if (state.authRecheckAtEpochMs != null && account.authFailCount == 1) {
        val recheckTime = formatSimpleWakeTime(state.authRecheckAtEpochMs, account.timezone, now)
        return "登录态待复检 · 预计 ${recheckTime}复检"
    }
    return when (account.status) {
        AccountStatus.PENDING_LOGIN,
        AccountStatus.AUTH_EXPIRED,
        AccountStatus.VERIFICATION_REQUIRED,
        -> "自动续火已暂停 · 请先登录抖音"

        AccountStatus.COOLDOWN -> "自动续火已暂停 · 连续失败熔断中"
        AccountStatus.DOM_BROKEN -> "自动续火已暂停 · 页面结构异常"
        AccountStatus.PAUSED -> "自动续火已暂停 · 点此开启"
        AccountStatus.HEALTHY,
        AccountStatus.RUNNING,
        -> {
            val nextMs = state.nextAutoTriggerAtEpochMs
            if (nextMs != null) {
                val wakeLabel = formatSimpleWakeTime(nextMs, account.timezone, now)
                "${wakeLabel}自动续火 · 已开启"
            } else {
                "每日 ${account.sendWindowStart}–${account.sendWindowEnd} 自动续火 · 已开启"
            }
        }
    }
}

private fun formatSimpleWakeTime(
    epochMs: Long,
    timezone: String,
    now: Instant,
): String {
    val zoneId = DailySchedulePlanner.resolveZoneId(timezone)
    val target = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zoneId)
    val today = ZonedDateTime.ofInstant(now, zoneId).toLocalDate()
    val timeStr = SIMPLE_TIME_FORMATTER.format(target)
    val isEvening = target.hour >= 18
    return when (target.toLocalDate()) {
        today -> if (isEvening) "今晚 $timeStr " else "今日 $timeStr "
        today.plusDays(1) -> if (isEvening) "明晚 $timeStr " else "明日 $timeStr "
        else -> "${SIMPLE_DATE_TIME_FORMATTER.format(target)} "
    }
}

private val SIMPLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
private val SIMPLE_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")

@Immutable
internal data class SimpleLayoutMetrics(
    val isTwoColumn: Boolean,
    val containerMaxWidthDp: Int,
    val heroNumberSp: Int,
    val heroDenomSp: Int,
    val singleRowDotSizeDp: Int,
    val multiRowDotSizeDp: Int,
    val dotGapDp: Int,
    val primaryButtonMinHeightDp: Int,
    val primaryButtonMaxWidthDp: Int?,
)

internal fun resolveSimpleLayoutMetrics(
    formFactor: FlameFormFactor,
    fontScale: Float = 1f,
): SimpleLayoutMetrics {
    val largeFont = fontScale > 1.25f
    return when (formFactor) {
        FlameFormFactor.COMPACT_PORTRAIT -> SimpleLayoutMetrics(
            isTwoColumn = false,
            containerMaxWidthDp = 460,
            heroNumberSp = if (largeFont) 54 else 68,
            heroDenomSp = if (largeFont) 24 else 28,
            singleRowDotSizeDp = 9,
            multiRowDotSizeDp = 8,
            dotGapDp = 7,
            primaryButtonMinHeightDp = 56,
            primaryButtonMaxWidthDp = null,
        )

        FlameFormFactor.TABLET_PORTRAIT -> SimpleLayoutMetrics(
            isTwoColumn = false,
            containerMaxWidthDp = 560,
            heroNumberSp = if (largeFont) 64 else 84,
            heroDenomSp = if (largeFont) 28 else 34,
            singleRowDotSizeDp = 11,
            multiRowDotSizeDp = 10,
            dotGapDp = 9,
            primaryButtonMinHeightDp = 58,
            primaryButtonMaxWidthDp = 400,
        )

        FlameFormFactor.TABLET_LANDSCAPE -> SimpleLayoutMetrics(
            isTwoColumn = true,
            containerMaxWidthDp = 960,
            heroNumberSp = if (largeFont) 70 else 92,
            heroDenomSp = if (largeFont) 28 else 36,
            singleRowDotSizeDp = 12,
            multiRowDotSizeDp = 11,
            dotGapDp = 9,
            primaryButtonMinHeightDp = 58,
            primaryButtonMaxWidthDp = null,
        )

        FlameFormFactor.COMPACT_HEIGHT_LANDSCAPE -> SimpleLayoutMetrics(
            isTwoColumn = true,
            containerMaxWidthDp = 780,
            heroNumberSp = if (largeFont) 46 else 56,
            heroDenomSp = if (largeFont) 20 else 24,
            singleRowDotSizeDp = 8,
            multiRowDotSizeDp = 8,
            dotGapDp = 6,
            primaryButtonMinHeightDp = 50,
            primaryButtonMaxWidthDp = null,
        )
    }
}

@Composable
fun SimpleTodayScreen(
    state: TodayUiState,
    snackbarHostState: SnackbarHostState,
    onReloginClick: () -> Unit,
    onDismissReloginChoice: () -> Unit,
    onChoosePreserveSessionLogin: () -> Unit,
    onChooseClearAndRelogin: () -> Unit,
    onOpenUnknownReview: (String?) -> Unit,
    onDismissUnknownReview: () -> Unit,
    onResolveUnknownAsSucceeded: (String) -> Unit,
    onResolveUnknownAsCancelled: (String) -> Unit,
    onOpenCooldownDetail: () -> Unit,
    onDismissCooldownDetail: () -> Unit,
    onClearCooldown: () -> Unit,
    onDismissDomBrokenDialog: () -> Unit,
    onDismissUrgentAuthPrompt: () -> Unit,
    onConfirmUrgentAuthPrompt: () -> Unit,
    onTriggerForceAllBatch: () -> Unit = {},
    onConfirmForceAllBatch: () -> Unit = {},
    onDismissForceAllConfirm: () -> Unit = {},
    onTriggerManualBatch: () -> Unit,
    onDismissManualBatchFeedback: () -> Unit,
    onOpenTab: (MainTab) -> Unit,
    onOpenSettings: (SettingsSection?) -> Unit = {},
    onOpenFeedback: () -> Unit = { onOpenSettings(SettingsSection.ABOUT) },
    onCopyDiagnosticsLog: (() -> Unit)? = null,
    onFeedbackIssue: ((title: String, content: String) -> Unit)? = null,
    onOpenAccountSwitcher: () -> Unit = {},
    onSwitchToStandardMode: () -> Unit = { onOpenSettings(SettingsSection.APPEARANCE) },
    otherAccountAlertBadge: Boolean = false,
    resolveJobScreenshotFile: (String) -> File? = { null },
    showTopBar: Boolean = true,
    showSnackbarHost: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val windowLayout = LocalFlameWindowLayout.current
    val formFactor = windowLayout.formFactor
    val fontScale = LocalDensity.current.fontScale
    val metrics = remember(formFactor, fontScale) {
        resolveSimpleLayoutMetrics(formFactor = formFactor, fontScale = fontScale)
    }
    val actionState = remember(state) { resolveSimpleActionState(state) }
    val scheduleLine = remember(
        state.account,
        state.nextAutoTriggerAtEpochMs,
        state.authRecheckAtEpochMs,
        state.businessDate,
    ) {
        formatSimpleScheduleLine(state)
    }
    val isInitialLoading = state.isLoading && state.account == null
    val showReloginHint = state.account?.status in setOf(
        AccountStatus.AUTH_EXPIRED,
        AccountStatus.VERIFICATION_REQUIRED,
    )

    val handlePrimaryClick: () -> Unit = {
        when (actionState) {
            is SimpleActionState.Executing -> Unit
            is SimpleActionState.NeedsUnknownReview -> onOpenUnknownReview(actionState.firstJobId)
            is SimpleActionState.Guided -> when (actionState.action) {
                SimpleGuidedAction.LOGIN -> onReloginClick()
                SimpleGuidedAction.SELECT_TARGETS -> onOpenTab(MainTab.TARGETS)
                SimpleGuidedAction.RECOVER_COOLDOWN -> onOpenCooldownDetail()
                SimpleGuidedAction.OPEN_DIAGNOSTICS -> onOpenSettings(SettingsSection.DIAGNOSTICS)
                SimpleGuidedAction.OPEN_SCHEDULE -> onOpenSettings(SettingsSection.SCHEDULE)
            }

            is SimpleActionState.ReadyToSend -> onTriggerManualBatch()
            is SimpleActionState.AllCompletedToday -> onTriggerForceAllBatch()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(palette.canvas),
        containerColor = palette.canvas,
        contentColor = palette.ink,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (showTopBar) {
                SimpleTodayTopBar(
                    account = state.account,
                    formFactor = formFactor,
                    otherAccountAlertBadge = otherAccountAlertBadge,
                    onOpenAccountSwitcher = onOpenAccountSwitcher,
                    onSwitchToStandardMode = onSwitchToStandardMode,
                    onOpenFeedback = onOpenFeedback,
                    onOpenSettings = { onOpenSettings(null) },
                )
            }
        },
        snackbarHost = {
            if (showSnackbarHost) {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp),
                )
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.TopCenter,
        ) {
            val availableHeight = maxHeight
            val isShortLandscape = formFactor == FlameFormFactor.COMPACT_HEIGHT_LANDSCAPE
            val isTabletPortrait = formFactor == FlameFormFactor.TABLET_PORTRAIT

            if (metrics.isTwoColumn && !isInitialLoading) {
                val horizontalPadding = if (isShortLandscape) 20.dp else 32.dp
                val verticalPadding = if (isShortLandscape) 6.dp else 18.dp
                val paneSpacing = if (isShortLandscape) 20.dp else 40.dp
                val leftWeight = if (isShortLandscape) 1.05f else 1.12f
                val rightWeight = if (isShortLandscape) 0.95f else 0.88f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = metrics.containerMaxWidthDp.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(paneSpacing),
                    ) {
                        Column(
                            modifier = Modifier.weight(leftWeight),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            SimpleHeroEditorialSection(
                                state = state,
                                actionState = actionState,
                                scheduleLine = scheduleLine,
                                isInitialLoading = false,
                                metrics = metrics,
                                leftAligned = true,
                                showInlineNoteInHero = false,
                                compactVertical = isShortLandscape,
                                onOpenTargets = { onOpenTab(MainTab.TARGETS) },
                                onOpenSchedule = { onOpenSettings(SettingsSection.SCHEDULE) },
                            )
                        }

                        SimpleConsoleActionCard(
                            state = state,
                            actionState = actionState,
                            metrics = metrics,
                            compactVertical = isShortLandscape,
                            showReloginHint = showReloginHint,
                            onPrimaryClick = handlePrimaryClick,
                            onOpenTargets = { onOpenTab(MainTab.TARGETS) },
                            onOpenTemplates = { onOpenTab(MainTab.TEMPLATES) },
                            onReloginClick = onReloginClick,
                            modifier = Modifier.weight(rightWeight),
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .widthIn(max = metrics.containerMaxWidthDp.dp)
                        .fillMaxWidth()
                        .heightIn(min = availableHeight)
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(
                            horizontal = if (isTabletPortrait) 28.dp else 24.dp,
                            vertical = if (isTabletPortrait) 18.dp else 12.dp,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    // 上半留白，让数字视觉中心自然落在光学黄金偏上位置
                    Spacer(modifier = Modifier.height(if (isTabletPortrait) 18.dp else 12.dp))

                    // 视觉主角区：大字重等宽数字 + 柔和圆点进度 + 单行状态/排班说明
                    SimpleHeroEditorialSection(
                        state = state,
                        actionState = actionState,
                        scheduleLine = scheduleLine,
                        isInitialLoading = isInitialLoading,
                        metrics = metrics,
                        leftAligned = false,
                        showInlineNoteInHero = true,
                        compactVertical = false,
                        onOpenTargets = { onOpenTab(MainTab.TARGETS) },
                        onOpenSchedule = { onOpenSettings(SettingsSection.SCHEDULE) },
                    )

                    // 底部拇指操作区：高对比单色主按钮 + 极轻文字次入口
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (isTabletPortrait) 32.dp else 28.dp, bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(if (isTabletPortrait) 16.dp else 14.dp),
                    ) {
                        if (!isInitialLoading) {
                            val buttonModifier = if (metrics.primaryButtonMaxWidthDp != null) {
                                Modifier
                                    .widthIn(max = metrics.primaryButtonMaxWidthDp.dp)
                                    .fillMaxWidth()
                            } else {
                                Modifier.fillMaxWidth()
                            }
                            SimpleBottomPrimaryButton(
                                actionState = actionState,
                                state = state,
                                minHeightDp = metrics.primaryButtonMinHeightDp,
                                onClick = handlePrimaryClick,
                                modifier = buttonModifier,
                            )
                        }

                        SimpleQuietFooterLinks(
                            enabledTargetCount = state.enabledTargetCount,
                            showReloginHint = showReloginHint,
                            largerTouchTargets = isTabletPortrait,
                            onOpenTargets = { onOpenTab(MainTab.TARGETS) },
                            onOpenTemplates = { onOpenTab(MainTab.TEMPLATES) },
                            onReloginClick = onReloginClick,
                        )
                    }
                }
            }
        }
    }

    if (state.showForceAllConfirmDialog) {
        ForceAllConfirmSheet(
            succeededTodayCount = state.succeededTodayCount,
            localSucceededTodayCount = state.localSucceededTodayCount,
            officialLitOnlyTodayCount = state.officialLitOnlyTodayCount,
            enabledTargetCount = state.enabledTargetCount,
            unboundEnabledTargetCount = state.unboundEnabledTargetCount,
            onConfirm = onConfirmForceAllBatch,
            onDismiss = onDismissForceAllConfirm,
        )
    }

    if (state.showReloginChoiceDialog) {
        ReloginChoiceSheet(
            onDismiss = onDismissReloginChoice,
            onPreserveSession = onChoosePreserveSessionLogin,
            onClearAndRelogin = onChooseClearAndRelogin,
        )
    }

    state.activeUnknownReviewJob?.let { reviewItem ->
        UnknownReviewSheet(
            item = reviewItem,
            screenshotFile = remember(reviewItem.job.id, reviewItem.job.updatedAt) {
                resolveJobScreenshotFile(reviewItem.job.id)
            },
            isResolving = state.isResolvingAction,
            onConfirmSucceeded = { onResolveUnknownAsSucceeded(reviewItem.job.id) },
            onConfirmCancelled = { onResolveUnknownAsCancelled(reviewItem.job.id) },
            onCopyDiagnosticsLog = onCopyDiagnosticsLog,
            onFeedbackIssue = onFeedbackIssue?.let { handler ->
                {
                    onDismissUnknownReview()
                    val title = "发送结果未明需人工核对"
                    val content = "在 ${reviewItem.job.businessDate} 执行续火任务时出现「发送结果未明」（尝试 ${reviewItem.job.attemptCount} 次）。已自动附带脱敏诊断日志，请协助排查原因。"
                    handler(title, content)
                }
            },
            onDismiss = onDismissUnknownReview,
        )
    }

    if (state.showCooldownDetailDialog && state.account?.status == AccountStatus.COOLDOWN) {
        CooldownDetailSheet(
            account = state.account,
            stats = state.cooldownFailureStats,
            isResolving = state.isResolvingAction,
            onRecover = onClearCooldown,
            onDismiss = onDismissCooldownDetail,
        )
    }

    if (state.showDomBrokenDialog) {
        DomBrokenSheet(
            onOpenDiagnostics = {
                onDismissDomBrokenDialog()
                onOpenSettings(SettingsSection.DIAGNOSTICS)
            },
            onDismiss = onDismissDomBrokenDialog,
        )
    }

    state.urgentAuthPromptCategory?.let { category ->
        UrgentAuthPromptSheet(
            category = category,
            onConfirm = onConfirmUrgentAuthPrompt,
            onDismiss = onDismissUrgentAuthPrompt,
        )
    }

    state.manualBatchFeedback?.let { feedback ->
        val settingsTargetSection = when {
            state.account?.status == AccountStatus.DOM_BROKEN -> SettingsSection.DIAGNOSTICS
            feedback.reason == ManualBatchBlockReason.SERVICE_START_FAILED -> SettingsSection.SURVIVAL
            else -> SettingsSection.SCHEDULE
        }
        ManualBatchFeedbackSheet(
            feedback = feedback,
            onDismiss = onDismissManualBatchFeedback,
            onOpenUnknownReview = {
                onDismissManualBatchFeedback()
                onOpenUnknownReview(null)
            },
            onOpenLogin = {
                onDismissManualBatchFeedback()
                onReloginClick()
            },
            onRecoverCooldown = {
                onDismissManualBatchFeedback()
                onClearCooldown()
            },
            onOpenSettings = {
                onDismissManualBatchFeedback()
                onOpenSettings(settingsTargetSection)
            },
            onOpenTargets = {
                onDismissManualBatchFeedback()
                onOpenTab(MainTab.TARGETS)
            },
        )
    }
}

@Composable
private fun SimpleTodayTopBar(
    account: AccountEntity?,
    formFactor: FlameFormFactor,
    otherAccountAlertBadge: Boolean,
    onOpenAccountSwitcher: () -> Unit,
    onSwitchToStandardMode: () -> Unit,
    onOpenFeedback: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val haptics = LocalHapticFeedback.current
    val accountInteraction = remember { MutableInteractionSource() }
    val settingsInteraction = remember { MutableInteractionSource() }
    val status = account?.status ?: AccountStatus.PENDING_LOGIN
    val statusStyle = accountStatusStyle(status)
    val displayName = account?.displayName?.takeIf { it.isNotBlank() } ?: "默认抖音号"
    val isCompactPortrait = formFactor == FlameFormFactor.COMPACT_PORTRAIT
    val isShortLandscape = formFactor == FlameFormFactor.COMPACT_HEIGHT_LANDSCAPE
    val horizontalPadding = when (formFactor) {
        FlameFormFactor.COMPACT_PORTRAIT -> 20.dp
        FlameFormFactor.COMPACT_HEIGHT_LANDSCAPE -> 16.dp
        FlameFormFactor.TABLET_PORTRAIT,
        FlameFormFactor.TABLET_LANDSCAPE,
        -> 28.dp
    }
    val verticalPadding = when (formFactor) {
        FlameFormFactor.COMPACT_PORTRAIT -> 12.dp
        FlameFormFactor.COMPACT_HEIGHT_LANDSCAPE -> 6.dp
        FlameFormFactor.TABLET_PORTRAIT -> 16.dp
        FlameFormFactor.TABLET_LANDSCAPE -> 14.dp
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isShortLandscape) 8.dp else 12.dp),
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier
                    .flamePressScale(interactionSource = accountInteraction)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(
                        interactionSource = accountInteraction,
                        indication = null,
                        role = Role.Button,
                        onClick = {
                            haptics.performFlameLightTick()
                            onOpenAccountSwitcher()
                        },
                    )
                    .heightIn(min = if (isShortLandscape) 36.dp else 42.dp)
                    .padding(horizontal = 6.dp, vertical = if (isShortLandscape) 5.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusStyle.dotColor),
                )
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!isCompactPortrait) {
                    Text(
                        text = "· ${statusStyle.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.inkTertiary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "切换或管理账号",
                    tint = palette.inkTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (otherAccountAlertBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-2).dp, y = 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(semantics.danger)
                        .border(1.5.dp, palette.canvas, CircleShape),
                )
            }
        }

        FlameDisplayModeToggle(
            mode = UiDisplayMode.SIMPLE,
            compact = isCompactPortrait || isShortLandscape,
            onSelectMode = { next ->
                if (next == UiDisplayMode.STANDARD) {
                    onSwitchToStandardMode()
                }
            },
        )

        if (!isCompactPortrait) {
            FlameFeedbackTopButton(
                onClick = onOpenFeedback,
                compact = true,
            )
        }

        Surface(
            onClick = {
                haptics.performFlameLightTick()
                onOpenSettings()
            },
            interactionSource = settingsInteraction,
            modifier = Modifier
                .flamePressScale(interactionSource = settingsInteraction)
                .size(if (isShortLandscape) 38.dp else 40.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = "设置"
                },
            shape = CircleShape,
            color = palette.surfaceSubtle,
            contentColor = palette.inkSecondary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun SimpleConsoleActionCard(
    state: TodayUiState,
    actionState: SimpleActionState,
    metrics: SimpleLayoutMetrics,
    compactVertical: Boolean,
    showReloginHint: Boolean,
    onPrimaryClick: () -> Unit,
    onOpenTargets: () -> Unit,
    onOpenTemplates: () -> Unit,
    onReloginClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val isDark = FlameThemeTokens.isDark
    val emberGlowColor = palette.emberBright
    val inlineAlert = resolveSimpleInlineAlert(actionState = actionState, state = state)
    val liveNote = remember(state.executionState) {
        resolveSimpleLiveNote(state)
    }

    val statusHintText = when (actionState) {
        is SimpleActionState.Executing -> "正在逐位发送并确认回执，完成后将自动更新今日进度。"
        is SimpleActionState.NeedsUnknownReview -> null
        is SimpleActionState.Guided -> actionState.subtitle
        is SimpleActionState.ReadyToSend ->
            "今日还有 ${actionState.pendingCount} 位好友待续火，点下方按钮立即发送。"

        is SimpleActionState.AllCompletedToday ->
            if (actionState.allSucceeded) {
                "今日 ${actionState.totalCount} 位启用好友已全部安全点亮。"
            } else {
                "今日好友均已处理完毕，如需再发一次可点下方按钮。"
            }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = palette.surface,
        contentColor = palette.ink,
        border = BorderStroke(1.dp, palette.hairline),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    if (isDark) {
                        val glowRadius = size.maxDimension * 0.78f
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    emberGlowColor.copy(alpha = 0.12f),
                                    emberGlowColor.copy(alpha = 0.03f),
                                    Color.Transparent,
                                ),
                                center = Offset(size.width * 0.85f, size.height * 0.18f),
                                radius = glowRadius,
                            ),
                        )
                    }
                }
                .padding(
                    horizontal = if (compactVertical) 16.dp else 22.dp,
                    vertical = if (compactVertical) 14.dp else 22.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (compactVertical) 10.dp else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (inlineAlert != null) {
                SimpleInlineCallout(
                    text = inlineAlert.message,
                    tone = inlineAlert.tone,
                    maxLines = if (compactVertical) 2 else 4,
                )
            } else if (liveNote != null) {
                SimpleInlineCallout(
                    text = liveNote.first,
                    tone = liveNote.second,
                    maxLines = if (compactVertical) 2 else 4,
                )
            } else if (!statusHintText.isNullOrBlank()) {
                Text(
                    text = statusHintText,
                    style = if (compactVertical) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = palette.inkSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            SimpleBottomPrimaryButton(
                actionState = actionState,
                state = state,
                minHeightDp = metrics.primaryButtonMinHeightDp,
                onClick = onPrimaryClick,
            )

            SimpleConsoleQuickActionsRow(
                enabledTargetCount = state.enabledTargetCount,
                showReloginHint = showReloginHint,
                compact = compactVertical,
                onOpenTargets = onOpenTargets,
                onOpenTemplates = onOpenTemplates,
                onReloginClick = onReloginClick,
            )
        }
    }
}

@Composable
private fun SimpleConsoleQuickActionsRow(
    enabledTargetCount: Int,
    showReloginHint: Boolean,
    compact: Boolean,
    onOpenTargets: () -> Unit,
    onOpenTemplates: () -> Unit,
    onReloginClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val haptics = LocalHapticFeedback.current
    val targetsInteraction = remember { MutableInteractionSource() }
    val templatesInteraction = remember { MutableInteractionSource() }
    val reloginInteraction = remember { MutableInteractionSource() }
    val pillMinHeight = if (compact) 36.dp else 42.dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                onClick = {
                    haptics.performFlameLightTick()
                    onOpenTargets()
                },
                interactionSource = targetsInteraction,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = pillMinHeight)
                    .flamePressScale(interactionSource = targetsInteraction),
                shape = RoundedCornerShape(14.dp),
                color = palette.surfaceSubtle,
                contentColor = palette.inkSecondary,
                border = BorderStroke(1.dp, palette.hairline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Groups,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (enabledTargetCount > 0) "续火好友 ($enabledTargetCount)" else "续火好友",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Surface(
                onClick = {
                    haptics.performFlameLightTick()
                    onOpenTemplates()
                },
                interactionSource = templatesInteraction,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = pillMinHeight)
                    .flamePressScale(interactionSource = templatesInteraction),
                shape = RoundedCornerShape(14.dp),
                color = palette.surfaceSubtle,
                contentColor = palette.inkSecondary,
                border = BorderStroke(1.dp, palette.hairline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Forum,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "续火模板",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (showReloginHint) {
            Surface(
                onClick = {
                    haptics.performFlameLightTick()
                    onReloginClick()
                },
                interactionSource = reloginInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .flamePressScale(interactionSource = reloginInteraction),
                shape = RoundedCornerShape(12.dp),
                color = palette.surfaceSubtle,
                contentColor = palette.inkSecondary,
                border = BorderStroke(1.dp, palette.hairline),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "重新登录抖音账号",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun resolveSimpleLiveNote(state: TodayUiState): Pair<String, FlameTone>? = when {
    state.executionState.running -> null
    !state.executionState.lastErrorSummary.isNullOrBlank() ->
        state.executionState.lastErrorSummary to FlameTone.DANGER

    !state.executionState.lastMessage.isNullOrBlank() -> {
        val tone = resolveHeroLiveStripTone(state.executionState)
        val displayTone = if (tone == FlameTone.HEALTHY) FlameTone.NEUTRAL else tone
        state.executionState.lastMessage to displayTone
    }

    else -> null
}

@Composable
private fun SimpleHeroEditorialSection(
    state: TodayUiState,
    actionState: SimpleActionState,
    scheduleLine: String,
    isInitialLoading: Boolean,
    metrics: SimpleLayoutMetrics,
    leftAligned: Boolean,
    showInlineNoteInHero: Boolean,
    compactVertical: Boolean,
    onOpenTargets: () -> Unit,
    onOpenSchedule: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val reducedMotion = rememberReducedMotion()
    val fontScale = LocalDensity.current.fontScale
    val horizontalAlign = if (leftAligned) Alignment.Start else Alignment.CenterHorizontally
    val textAlign = if (leftAligned) TextAlign.Start else TextAlign.Center

    if (isInitialLoading) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.2.dp,
                color = palette.inkSecondary,
                trackColor = palette.hairline,
            )
            Text(
                text = "正在读取今日续火状态…",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.inkTertiary,
            )
        }
        return
    }

    val eyebrowSpec = resolveSimpleEyebrowSpec(actionState = actionState, state = state)
    val heroNumberSize = metrics.heroNumberSp.sp
    val heroDenomSize = metrics.heroDenomSp.sp

    val showNumericHero = state.enabledTargetCount > 0 && when (actionState) {
        is SimpleActionState.ReadyToSend,
        is SimpleActionState.AllCompletedToday,
        is SimpleActionState.Executing,
        is SimpleActionState.NeedsUnknownReview,
        -> true

        is SimpleActionState.Guided -> actionState.action !in setOf(
            SimpleGuidedAction.LOGIN,
            SimpleGuidedAction.SELECT_TARGETS,
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = horizontalAlign,
    ) {
        // 顶部柔和语义眉标（无重框）
        SimpleStatusEyebrowPill(
            text = eyebrowSpec.text,
            icon = eyebrowSpec.icon,
            tone = eyebrowSpec.tone,
            showSpinner = actionState is SimpleActionState.Executing,
        )

        Spacer(modifier = Modifier.height(if (compactVertical) 10.dp else 18.dp))

        // 视觉主角：大号等宽数字（或引导态标题）
        AnimatedContent(
            targetState = Pair(showNumericHero, actionState),
            transitionSpec = {
                if (reducedMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    fadeIn(tween(FlameMotionSpec.TAB_ENTER_MS, easing = FlameMotionSpec.easeOut)) togetherWith
                        fadeOut(tween(FlameMotionSpec.TAB_EXIT_MS, easing = FlameMotionSpec.easeOut))
                }
            },
            label = "simple_hero_editorial",
        ) { (numeric, current) ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = horizontalAlign,
            ) {
                if (numeric) {
                    val numerator = when (current) {
                        is SimpleActionState.Executing -> if (current.progressTotal > 0) {
                            current.progressCurrent
                        } else {
                            state.succeededTodayCount
                        }

                        is SimpleActionState.ReadyToSend -> current.succeededCount
                        is SimpleActionState.AllCompletedToday -> current.succeededCount
                        else -> state.succeededTodayCount
                    }
                    val denominator = when (current) {
                        is SimpleActionState.Executing -> if (current.progressTotal > 0) {
                            current.progressTotal
                        } else {
                            state.enabledTargetCount
                        }

                        is SimpleActionState.ReadyToSend -> current.totalCount
                        is SimpleActionState.AllCompletedToday -> current.totalCount
                        else -> state.enabledTargetCount
                    }

                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = if (leftAligned) Arrangement.Start else Arrangement.Center,
                    ) {
                        Text(
                            text = numerator.toString(),
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontSize = heroNumberSize,
                                lineHeight = heroNumberSize * 1.04f,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-2).sp,
                                fontFeatureSettings = "tnum",
                            ),
                            color = palette.ink,
                            modifier = Modifier.alignByBaseline(),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "/ $denominator",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = heroDenomSize,
                                lineHeight = heroDenomSize * 1.1f,
                                fontWeight = FontWeight.Medium,
                                fontFeatureSettings = "tnum",
                            ),
                            color = palette.inkTertiary,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }

                    Spacer(modifier = Modifier.height(if (compactVertical) 4.dp else 10.dp))

                    val subtitleText = buildString {
                        append(
                            when (current) {
                                is SimpleActionState.AllCompletedToday ->
                                    if (current.allSucceeded) "今日已全部安全点亮" else "今日已全部处理"

                                is SimpleActionState.Executing ->
                                    state.executionState.currentTask?.takeIf { it.isNotBlank() }
                                        ?: "正在逐位确认送达…"

                                is SimpleActionState.NeedsUnknownReview ->
                                    "已暂停自动重发，请先人工核对"

                                else -> "今日已点亮火花好友"
                            },
                        )
                        val maxSpark = state.maxOfficialSparkDays
                        if (maxSpark != null && maxSpark > 0 && current !is SimpleActionState.Executing) {
                            append(" · 最长 ")
                            append(maxSpark)
                            append(" 天")
                        }
                    }

                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkTertiary,
                        textAlign = textAlign,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    val (headline, description) = resolveGuidedHeroCopy(actionState = current, state = state)
                    val guidedTitleSp = when {
                        compactVertical -> if (fontScale > 1.25f) 24.sp else 26.sp
                        leftAligned -> if (fontScale > 1.25f) 32.sp else 38.sp
                        else -> if (fontScale > 1.25f) 28.sp else 32.sp
                    }
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = guidedTitleSp,
                            lineHeight = guidedTitleSp * 1.22f,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                        ),
                        color = palette.ink,
                        textAlign = textAlign,
                    )
                    Spacer(modifier = Modifier.height(if (compactVertical) 6.dp else 10.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkSecondary,
                        textAlign = textAlign,
                        modifier = if (leftAligned) {
                            Modifier.widthIn(max = 360.dp)
                        } else {
                            Modifier.widthIn(max = 320.dp)
                        },
                    )
                }
            }
        }

        // 极简圆点进度条（可点击直接进入续火好友列表）
        if (state.enabledTargetCount > 0) {
            val dotModel = remember(
                state.enabledTargetCount,
                state.succeededTodayCount,
                state.unknownReviewJobs.size,
                actionState,
            ) {
                buildSimpleProgressDots(
                    totalCount = state.enabledTargetCount,
                    succeededCount = state.succeededTodayCount,
                    unknownCount = state.unknownReviewJobs.size,
                    allCompleted = actionState is SimpleActionState.AllCompletedToday,
                )
            }
            Spacer(modifier = Modifier.height(if (compactVertical) 10.dp else 22.dp))
            SimpleProgressDotStrip(
                model = dotModel,
                singleRowDotSizeDp = metrics.singleRowDotSizeDp,
                multiRowDotSizeDp = metrics.multiRowDotSizeDp,
                dotGapDp = metrics.dotGapDp,
                leftAligned = leftAligned,
                onClick = onOpenTargets,
            )
        }

        if (showInlineNoteInHero) {
            val inlineAlert = resolveSimpleInlineAlert(actionState = actionState, state = state)
            if (inlineAlert != null) {
                Spacer(modifier = Modifier.height(20.dp))
                SimpleInlineCallout(
                    text = inlineAlert.message,
                    tone = inlineAlert.tone,
                )
            } else {
                val liveNote = remember(state.executionState) {
                    resolveSimpleLiveNote(state)
                }
                if (liveNote != null) {
                    Spacer(modifier = Modifier.height(18.dp))
                    SimpleInlineCallout(
                        text = liveNote.first,
                        tone = liveNote.second,
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))
                SimpleQuietScheduleRow(
                    text = scheduleLine,
                    isActive = state.account?.enabled == true &&
                        state.account.status in setOf(AccountStatus.HEALTHY, AccountStatus.RUNNING),
                    onClick = onOpenSchedule,
                )
            }
        } else {
            Spacer(modifier = Modifier.height(if (compactVertical) 8.dp else 18.dp))
            SimpleQuietScheduleRow(
                text = scheduleLine,
                isActive = state.account?.enabled == true &&
                    state.account.status in setOf(AccountStatus.HEALTHY, AccountStatus.RUNNING),
                onClick = onOpenSchedule,
            )
        }
    }
}

private data class SimpleEyebrowSpec(
    val text: String,
    val icon: ImageVector,
    val tone: FlameTone,
)

private fun resolveSimpleEyebrowSpec(
    actionState: SimpleActionState,
    state: TodayUiState,
): SimpleEyebrowSpec = when (actionState) {
    is SimpleActionState.Executing -> SimpleEyebrowSpec(
        text = "正在续火 · ${actionState.progressText}",
        icon = Icons.Filled.LocalFireDepartment,
        tone = FlameTone.EMBER,
    )

    is SimpleActionState.NeedsUnknownReview -> SimpleEyebrowSpec(
        text = "${actionState.unknownCount} 位发送结果待核对",
        icon = Icons.AutoMirrored.Outlined.HelpOutline,
        tone = FlameTone.DANGER,
    )

    is SimpleActionState.ReadyToSend -> SimpleEyebrowSpec(
        text = "今日待续 ${actionState.pendingCount} 位",
        icon = Icons.Filled.LocalFireDepartment,
        tone = FlameTone.NEUTRAL,
    )

    is SimpleActionState.AllCompletedToday -> SimpleEyebrowSpec(
        text = if (actionState.allSucceeded) "今日已全部点亮" else "今日已全部处理",
        icon = Icons.Outlined.CheckCircle,
        tone = FlameTone.HEALTHY,
    )

    is SimpleActionState.Guided -> when (actionState.action) {
        SimpleGuidedAction.LOGIN -> when (state.account?.status) {
            AccountStatus.AUTH_EXPIRED -> SimpleEyebrowSpec(
                text = "登录态已失效",
                icon = Icons.Outlined.QrCodeScanner,
                tone = FlameTone.DANGER,
            )

            AccountStatus.VERIFICATION_REQUIRED -> SimpleEyebrowSpec(
                text = "需本人安全验证",
                icon = Icons.Outlined.Security,
                tone = FlameTone.WARNING,
            )

            else -> SimpleEyebrowSpec(
                text = "尚未绑定抖音号",
                icon = Icons.Outlined.QrCodeScanner,
                tone = FlameTone.NEUTRAL,
            )
        }

        SimpleGuidedAction.SELECT_TARGETS -> SimpleEyebrowSpec(
            text = if (state.enabledTargetCount == 0) "尚未挑选续火好友" else "好友未绑定模板",
            icon = Icons.Outlined.GroupAdd,
            tone = FlameTone.NEUTRAL,
        )

        SimpleGuidedAction.RECOVER_COOLDOWN -> SimpleEyebrowSpec(
            text = "连续失败已暂停保护",
            icon = Icons.Outlined.WarningAmber,
            tone = FlameTone.WARNING,
        )

        SimpleGuidedAction.OPEN_DIAGNOSTICS -> SimpleEyebrowSpec(
            text = "网页结构异常",
            icon = Icons.Outlined.Build,
            tone = FlameTone.DANGER,
        )

        SimpleGuidedAction.OPEN_SCHEDULE -> SimpleEyebrowSpec(
            text = "自动续火已暂停",
            icon = Icons.Outlined.Schedule,
            tone = FlameTone.NEUTRAL,
        )
    }
}

private fun resolveGuidedHeroCopy(
    actionState: SimpleActionState,
    state: TodayUiState,
): Pair<String, String> = when (actionState) {
    is SimpleActionState.Guided -> when (actionState.action) {
        SimpleGuidedAction.LOGIN -> when (state.account?.status) {
            AccountStatus.AUTH_EXPIRED -> "登录已失效" to "重新登录抖音账号后，即可恢复一键与自动续火"
            AccountStatus.VERIFICATION_REQUIRED -> "需要安全验证" to "网页触发了安全验证，请亲自打开页面完成验证"
            else -> "先登录抖音" to "绑定你的抖音账号后，每天一键即可给好友续火花"
        }

        SimpleGuidedAction.SELECT_TARGETS -> if (state.enabledTargetCount == 0) {
            "准备开始" to "先扫描抖音会话挑选续火好友，之后每天一键即可完成"
        } else {
            "绑定发送模板" to "已启用 ${state.enabledTargetCount} 位好友，先给他们选择默认续火内容"
        }

        SimpleGuidedAction.RECOVER_COOLDOWN -> "续火已暂停" to "连续失败触发保护，查看原因并恢复后再继续发送"
        SimpleGuidedAction.OPEN_DIAGNOSTICS -> "页面结构变动" to "检测到抖音聊天页改版，为防误发已暂停自动发送"
        SimpleGuidedAction.OPEN_SCHEDULE -> "自动续火已关" to "开启排班开关后，每天会在设定时段自动帮你续火"
    }

    else -> "今日续火" to "一切就绪"
}

private data class SimpleInlineAlert(
    val message: String,
    val tone: FlameTone,
)

private fun resolveSimpleInlineAlert(
    actionState: SimpleActionState,
    state: TodayUiState,
): SimpleInlineAlert? = when (actionState) {
    is SimpleActionState.NeedsUnknownReview -> SimpleInlineAlert(
        message = "系统无法确认 ${actionState.unknownCount} 位好友是否已收到消息。为防重复发送，请先在手机抖音里看一眼再手动确认。",
        tone = FlameTone.DANGER,
    )

    is SimpleActionState.Guided -> when (actionState.action) {
        SimpleGuidedAction.RECOVER_COOLDOWN -> SimpleInlineAlert(
            message = "已连续 ${state.account?.failureStreak ?: 0} 轮发送失败，自动续火已暂停保护。点击下方按钮查看原因并恢复。",
            tone = FlameTone.WARNING,
        )

        SimpleGuidedAction.OPEN_DIAGNOSTICS -> SimpleInlineAlert(
            message = "检测到抖音网页结构变化，为防误发已暂停自动续火，请前往诊断页核查。",
            tone = FlameTone.DANGER,
        )

        else -> null
    }

    else -> null
}

@Composable
private fun SimpleStatusEyebrowPill(
    text: String,
    icon: ImageVector,
    tone: FlameTone,
    showSpinner: Boolean,
) {
    val palette = FlameThemeTokens.palette
    val toneColors = resolveFlameToneColors(tone)
    val bgColor = if (tone == FlameTone.NEUTRAL) palette.surfaceSubtle else toneColors.container
    val fgColor = if (tone == FlameTone.NEUTRAL) palette.inkSecondary else toneColors.fg

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = bgColor,
        contentColor = fgColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 2.dp,
                    color = fgColor,
                    trackColor = fgColor.copy(alpha = 0.2f),
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fgColor,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = fgColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SimpleProgressDotStrip(
    model: SimpleProgressDotStripModel,
    singleRowDotSizeDp: Int,
    multiRowDotSizeDp: Int,
    dotGapDp: Int,
    leftAligned: Boolean,
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val rows = remember(model.dots) { layoutSimpleProgressDotRows(model.dots) }
    val isMultiRow = rows.size > 1
    val dotSize = if (isMultiRow) multiRowDotSizeDp.dp else singleRowDotSizeDp.dp
    val dotGap = dotGapDp.dp
    val horizontalAlign = if (leftAligned) Alignment.Start else Alignment.CenterHorizontally

    Column(
        modifier = Modifier
            .flamePressScale(interactionSource = interaction)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = {
                    haptics.performFlameLightTick()
                    onClick()
                },
            )
            .semantics { contentDescription = "查看续火好友列表" }
            .padding(
                start = if (leftAligned) 0.dp else 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
        horizontalAlignment = horizontalAlign,
        verticalArrangement = Arrangement.spacedBy(dotGap),
    ) {
        rows.forEach { rowDots ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(dotGap, horizontalAlign),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowDots.forEach { kind ->
                    val dotColor = when (kind) {
                        SimpleProgressDotKind.DONE_EMBER -> palette.ember
                        SimpleProgressDotKind.DONE_HEALTHY -> semantics.healthy
                        SimpleProgressDotKind.UNKNOWN_DANGER -> semantics.danger
                        SimpleProgressDotKind.PENDING -> palette.hairlineStrong
                    }
                    Box(
                        modifier = Modifier
                            .size(dotSize)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
            }
        }
        if (model.overflowCount > 0) {
            Text(
                text = "+${model.overflowCount} 位好友",
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = palette.inkTertiary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SimpleInlineCallout(
    text: String,
    tone: FlameTone,
    maxLines: Int = 4,
) {
    val palette = FlameThemeTokens.palette
    val toneColors = resolveFlameToneColors(tone)
    val bgColor = if (tone == FlameTone.NEUTRAL) palette.surfaceSubtle else toneColors.container
    val borderColor = if (tone == FlameTone.NEUTRAL) palette.hairline else toneColors.border
    val textColor = if (tone == FlameTone.NEUTRAL) palette.inkSecondary else toneColors.onContainer

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SimpleQuietScheduleRow(
    text: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .flamePressScale(interactionSource = interaction)
            .clip(RoundedCornerShape(999.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = {
                    haptics.performFlameLightTick()
                    onClick()
                },
            )
            .heightIn(min = 38.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            tint = if (isActive) semantics.healthy else palette.inkTertiary,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = palette.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "调整定时设置",
            tint = palette.inkTertiary,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun SimpleBottomPrimaryButton(
    actionState: SimpleActionState,
    state: TodayUiState,
    minHeightDp: Int = 56,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val reducedMotion = rememberReducedMotion()
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val enabled = actionState !is SimpleActionState.Executing
    val animDuration = if (reducedMotion) 0 else 200

    val targetContainer = when (actionState) {
        is SimpleActionState.Executing -> palette.emberContainer
        is SimpleActionState.NeedsUnknownReview -> Color(0xFFDC2626)
        is SimpleActionState.AllCompletedToday -> palette.surface
        is SimpleActionState.ReadyToSend -> palette.monoCtaContainer
        is SimpleActionState.Guided -> when (actionState.action) {
            SimpleGuidedAction.OPEN_DIAGNOSTICS -> Color(0xFFDC2626)
            else -> palette.monoCtaContainer
        }
    }
    val targetContent = when (actionState) {
        is SimpleActionState.Executing -> palette.ember
        is SimpleActionState.NeedsUnknownReview -> Color.White
        is SimpleActionState.AllCompletedToday -> palette.ink
        is SimpleActionState.ReadyToSend -> palette.monoCtaContent
        is SimpleActionState.Guided -> when (actionState.action) {
            SimpleGuidedAction.OPEN_DIAGNOSTICS -> Color.White
            else -> palette.monoCtaContent
        }
    }
    val targetBorder = when (actionState) {
        is SimpleActionState.Executing -> palette.emberBorder
        is SimpleActionState.AllCompletedToday -> palette.hairlineStrong
        else -> Color.Transparent
    }

    val containerColor by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = tween(durationMillis = animDuration, easing = FlameMotionSpec.easeOut),
        label = "simple_cta_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = targetContent,
        animationSpec = tween(durationMillis = animDuration, easing = FlameMotionSpec.easeOut),
        label = "simple_cta_fg",
    )
    val borderColor by animateColorAsState(
        targetValue = targetBorder,
        animationSpec = tween(durationMillis = animDuration, easing = FlameMotionSpec.easeOut),
        label = "simple_cta_border",
    )

    val label = remember(actionState, state.account?.status, state.enabledTargetCount) {
        resolveSimplePrimaryCtaLabel(actionState = actionState, state = state)
    }

    Surface(
        onClick = {
            haptics.performFlameLightTick()
            onClick()
        },
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .flamePressScale(
                interactionSource = interaction,
                pressedScale = FlameMotionSpec.PRESS_SCALE_DEFAULT,
                enabled = enabled,
            )
            .heightIn(min = minHeightDp.dp),
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
        border = if (borderColor == Color.Transparent) null else BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = if (minHeightDp <= 50) 11.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            when (actionState) {
                is SimpleActionState.Executing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.2.dp,
                        color = contentColor,
                        trackColor = contentColor.copy(alpha = 0.22f),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }

                is SimpleActionState.ReadyToSend -> {
                    Icon(
                        imageVector = Icons.Filled.LocalFireDepartment,
                        contentDescription = null,
                        tint = palette.emberBright,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                is SimpleActionState.AllCompletedToday -> {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = semantics.healthy,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                is SimpleActionState.NeedsUnknownReview -> {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                is SimpleActionState.Guided -> {
                    val guidedIcon = when (actionState.action) {
                        SimpleGuidedAction.LOGIN -> Icons.Outlined.QrCodeScanner
                        SimpleGuidedAction.SELECT_TARGETS -> Icons.Outlined.GroupAdd
                        SimpleGuidedAction.RECOVER_COOLDOWN -> Icons.Outlined.WarningAmber
                        SimpleGuidedAction.OPEN_DIAGNOSTICS -> Icons.Outlined.Build
                        SimpleGuidedAction.OPEN_SCHEDULE -> Icons.Outlined.Schedule
                    }
                    Icon(
                        imageVector = guidedIcon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }

            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SimpleQuietFooterLinks(
    enabledTargetCount: Int,
    showReloginHint: Boolean,
    largerTouchTargets: Boolean = false,
    onOpenTargets: () -> Unit,
    onOpenTemplates: () -> Unit,
    onReloginClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val haptics = LocalHapticFeedback.current
    val targetsInteraction = remember { MutableInteractionSource() }
    val templatesInteraction = remember { MutableInteractionSource() }
    val reloginInteraction = remember { MutableInteractionSource() }
    val textStyle = if (largerTouchTargets) {
        MaterialTheme.typography.bodyMedium
    } else {
        MaterialTheme.typography.bodySmall
    }
    val linkMinHeight = if (largerTouchTargets) 44.dp else 40.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .flamePressScale(interactionSource = targetsInteraction)
                .clip(RoundedCornerShape(999.dp))
                .clickable(
                    interactionSource = targetsInteraction,
                    indication = null,
                    role = Role.Button,
                    onClick = {
                        haptics.performFlameLightTick()
                        onOpenTargets()
                    },
                )
                .heightIn(min = linkMinHeight)
                .padding(horizontal = if (largerTouchTargets) 12.dp else 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Groups,
                contentDescription = null,
                tint = palette.inkSecondary,
                modifier = Modifier.size(if (largerTouchTargets) 16.dp else 15.dp),
            )
            Text(
                text = if (enabledTargetCount > 0) "续火好友 ($enabledTargetCount)" else "续火好友",
                style = textStyle,
                fontWeight = FontWeight.Medium,
                color = palette.inkSecondary,
                maxLines = 1,
            )
        }

        Text(
            text = "·",
            style = textStyle,
            color = palette.hairlineStrong,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        if (showReloginHint) {
            Box(
                modifier = Modifier
                    .flamePressScale(interactionSource = reloginInteraction)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(
                        interactionSource = reloginInteraction,
                        indication = null,
                        role = Role.Button,
                        onClick = {
                            haptics.performFlameLightTick()
                            onReloginClick()
                        },
                    )
                    .heightIn(min = linkMinHeight)
                    .padding(horizontal = if (largerTouchTargets) 12.dp else 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "重登抖音",
                    style = textStyle,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkSecondary,
                    maxLines = 1,
                )
            }
            Text(
                text = "·",
                style = textStyle,
                color = palette.hairlineStrong,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Row(
            modifier = Modifier
                .flamePressScale(interactionSource = templatesInteraction)
                .clip(RoundedCornerShape(999.dp))
                .clickable(
                    interactionSource = templatesInteraction,
                    indication = null,
                    role = Role.Button,
                    onClick = {
                        haptics.performFlameLightTick()
                        onOpenTemplates()
                    },
                )
                .heightIn(min = linkMinHeight)
                .padding(horizontal = if (largerTouchTargets) 12.dp else 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Forum,
                contentDescription = null,
                tint = palette.inkSecondary,
                modifier = Modifier.size(if (largerTouchTargets) 16.dp else 15.dp),
            )
            Text(
                text = "续火模板",
                style = textStyle,
                fontWeight = FontWeight.Medium,
                color = palette.inkSecondary,
                maxLines = 1,
            )
        }
    }
}
