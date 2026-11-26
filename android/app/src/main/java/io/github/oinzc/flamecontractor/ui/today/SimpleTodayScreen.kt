package io.github.oinzc.flamecontractor.ui.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.scheduler.DailySchedulePlanner
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.FlameTone
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
    onCopyDiagnosticsLog: (() -> Unit)? = null,
    onFeedbackIssue: ((title: String, content: String) -> Unit)? = null,
    onOpenAccountSwitcher: () -> Unit = {},
    otherAccountAlertBadge: Boolean = false,
    resolveJobScreenshotFile: (String) -> File? = { null },
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val fontScale = LocalDensity.current.fontScale
    val actionState = remember(state) { resolveSimpleActionState(state) }
    val scheduleLine = remember(
        state.account,
        state.nextAutoTriggerAtEpochMs,
        state.authRecheckAtEpochMs,
        state.businessDate,
    ) {
        formatSimpleScheduleLine(state)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(palette.canvas),
    ) {
        val containerMaxWidth = maxWidth
        val circleDiameter: Dp = when {
            fontScale >= 1.3f -> 220.dp.coerceAtMost(containerMaxWidth - 52.dp).coerceAtLeast(188.dp)
            else -> 204.dp.coerceAtMost(containerMaxWidth - 52.dp).coerceAtLeast(180.dp)
        }
        val stackQuickActions = containerMaxWidth < 360.dp && fontScale >= 1.3f

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = palette.canvas,
            contentColor = palette.ink,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                SimpleTodayTopBar(
                    account = state.account,
                    otherAccountAlertBadge = otherAccountAlertBadge,
                    onOpenAccountSwitcher = onOpenAccountSwitcher,
                    onOpenSettings = { onOpenSettings(null) },
                )
            },
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp),
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (!state.isLoading || state.account != null) {
                        SimpleAlertInterceptSection(
                            state = state,
                            onOpenUnknownReview = onOpenUnknownReview,
                            onReloginClick = onReloginClick,
                            onOpenCooldownDetail = onOpenCooldownDetail,
                            onOpenDiagnostics = { onOpenSettings(SettingsSection.DIAGNOSTICS) },
                            onOpenTargets = { onOpenTab(MainTab.TARGETS) },
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    SimpleCentralFlameButton(
                        actionState = actionState,
                        diameter = circleDiameter,
                        onClick = {
                            when (actionState) {
                                is SimpleActionState.Executing -> Unit
                                is SimpleActionState.NeedsUnknownReview ->
                                    onOpenUnknownReview(actionState.firstJobId)

                                is SimpleActionState.Guided -> when (actionState.action) {
                                    SimpleGuidedAction.LOGIN -> onReloginClick()
                                    SimpleGuidedAction.SELECT_TARGETS -> onOpenTab(MainTab.TARGETS)
                                    SimpleGuidedAction.RECOVER_COOLDOWN -> onOpenCooldownDetail()
                                    SimpleGuidedAction.OPEN_DIAGNOSTICS ->
                                        onOpenSettings(SettingsSection.DIAGNOSTICS)

                                    SimpleGuidedAction.OPEN_SCHEDULE ->
                                        onOpenSettings(SettingsSection.SCHEDULE)
                                }

                                is SimpleActionState.ReadyToSend -> onTriggerManualBatch()
                                is SimpleActionState.AllCompletedToday -> onTriggerForceAllBatch()
                            }
                        },
                    )

                    val liveFeedbackText = remember(state.executionState) {
                        when {
                            state.executionState.running ->
                                state.executionState.currentTask?.takeIf { it.isNotBlank() }

                            !state.executionState.lastErrorSummary.isNullOrBlank() ->
                                state.executionState.lastErrorSummary

                            !state.executionState.lastMessage.isNullOrBlank() ->
                                state.executionState.lastMessage

                            else -> null
                        }
                    }
                    if (!liveFeedbackText.isNullOrBlank()) {
                        SimpleLiveStatusNote(
                            text = liveFeedbackText,
                            isError = !state.executionState.running &&
                                !state.executionState.lastErrorSummary.isNullOrBlank(),
                        )
                    }

                    SimpleScheduleStatusCard(
                        text = scheduleLine,
                        isEnabled = state.account?.enabled == true &&
                            state.account.status != AccountStatus.PAUSED,
                        onClick = { onOpenSettings(SettingsSection.SCHEDULE) },
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    SimpleQuickActionsRow(
                        enabledTargetCount = state.enabledTargetCount,
                        stacked = stackQuickActions,
                        onOpenTargets = { onOpenTab(MainTab.TARGETS) },
                        onReloginClick = onReloginClick,
                    )

                    Spacer(modifier = Modifier.height(8.dp))
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
    otherAccountAlertBadge: Boolean,
    onOpenAccountSwitcher: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val haptics = LocalHapticFeedback.current
    val accountInteraction = remember { MutableInteractionSource() }
    val settingsInteraction = remember { MutableInteractionSource() }
    val statusStyle = accountStatusStyle(account?.status ?: AccountStatus.PENDING_LOGIN)
    val displayName = account?.displayName?.takeIf { it.isNotBlank() } ?: "默认抖音号"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.weight(1f, fill = false)) {
            Surface(
                onClick = {
                    haptics.performFlameLightTick()
                    onOpenAccountSwitcher()
                },
                interactionSource = accountInteraction,
                modifier = Modifier
                    .flamePressScale(interactionSource = accountInteraction)
                    .heightIn(min = 46.dp),
                shape = RoundedCornerShape(999.dp),
                color = palette.surface,
                contentColor = palette.ink,
                border = BorderStroke(
                    width = 1.2.dp,
                    color = if (otherAccountAlertBadge) semantics.dangerBorder else palette.hairlineStrong,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
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
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "切换或管理账号",
                        tint = palette.inkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (otherAccountAlertBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-2).dp, y = 2.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(semantics.danger)
                        .border(2.dp, palette.surface, CircleShape),
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Surface(
            onClick = {
                haptics.performFlameLightTick()
                onOpenSettings()
            },
            interactionSource = settingsInteraction,
            modifier = Modifier
                .flamePressScale(interactionSource = settingsInteraction)
                .heightIn(min = 46.dp),
            shape = RoundedCornerShape(999.dp),
            color = palette.surface,
            contentColor = palette.ink,
            border = BorderStroke(1.2.dp, palette.hairlineStrong),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = palette.ink,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun SimpleAlertInterceptSection(
    state: TodayUiState,
    onOpenUnknownReview: (String?) -> Unit,
    onReloginClick: () -> Unit,
    onOpenCooldownDetail: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenTargets: () -> Unit,
) {
    val account = state.account
    val status = account?.status ?: AccountStatus.PENDING_LOGIN
    val isAuthIssue = account == null || status in setOf(
        AccountStatus.PENDING_LOGIN,
        AccountStatus.AUTH_EXPIRED,
        AccountStatus.VERIFICATION_REQUIRED,
    )

    val hasUnknown = state.unknownReviewJobs.isNotEmpty()
    val hasCooldown = status == AccountStatus.COOLDOWN
    val hasDomBroken = status == AccountStatus.DOM_BROKEN
    val hasNoTargets = !isAuthIssue && !hasCooldown && !hasDomBroken && state.enabledTargetCount == 0
    val hasNoBoundTemplates = !isAuthIssue && !hasCooldown && !hasDomBroken &&
        state.enabledTargetCount > 0 && state.boundEnabledTargetCount == 0

    if (!hasUnknown && !isAuthIssue && !hasCooldown && !hasDomBroken && !hasNoTargets && !hasNoBoundTemplates) {
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hasUnknown) {
            SimpleProminentAlertCard(
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                title = "有 ${state.unknownReviewJobs.size} 位好友发送结果待核对",
                message = "系统无法确认消息是否已发出。为防止重复发送，请先在手机抖音里看一眼，再手动确认结果。",
                actionLabel = "立即核对",
                tone = FlameTone.DANGER,
                onAction = { onOpenUnknownReview(state.unknownReviewJobs.firstOrNull()?.job?.id) },
            )
        }

        if (isAuthIssue) {
            val (title, message, tone) = when (status) {
                AccountStatus.AUTH_EXPIRED -> Triple(
                    "抖音登录已失效",
                    "自动续火已暂停，请重新登录抖音账号后再继续续火。",
                    FlameTone.DANGER,
                )

                AccountStatus.VERIFICATION_REQUIRED -> Triple(
                    "抖音需要安全验证",
                    "网页触发了安全验证，请亲自打开页面完成验证以恢复续火。",
                    FlameTone.WARNING,
                )

                else -> Triple(
                    "尚未登录抖音账号",
                    "请先登录抖音账号，登录后即可一键自动给好友续火花。",
                    FlameTone.WARNING,
                )
            }
            SimpleProminentAlertCard(
                icon = if (status == AccountStatus.VERIFICATION_REQUIRED) {
                    Icons.Outlined.Security
                } else {
                    Icons.Outlined.QrCodeScanner
                },
                title = title,
                message = message,
                actionLabel = "去登录抖音",
                tone = tone,
                onAction = onReloginClick,
            )
        }

        if (hasCooldown && account != null) {
            SimpleProminentAlertCard(
                icon = Icons.Outlined.WarningAmber,
                title = "连续失败已暂停续火",
                message = "已连续 ${account.failureStreak} 轮发送失败，系统已暂停保护。点下方查看原因并解除暂停。",
                actionLabel = "查看并恢复",
                tone = FlameTone.WARNING,
                onAction = onOpenCooldownDetail,
            )
        }

        if (hasDomBroken) {
            SimpleProminentAlertCard(
                icon = Icons.Outlined.Build,
                title = "抖音网页结构异常",
                message = "检测到聊天页面结构变化，为防误发已暂停自动续火，请前往诊断检查。",
                actionLabel = "前往诊断",
                tone = FlameTone.DANGER,
                onAction = onOpenDiagnostics,
            )
        }

        if (hasNoTargets) {
            SimpleProminentAlertCard(
                icon = Icons.Outlined.GroupAdd,
                title = "尚未选择要续火的好友",
                message = "先挑选需要每天续火花的好友并启用，之后就能一键完成续火。",
                actionLabel = "去选续火好友",
                tone = FlameTone.WARNING,
                onAction = onOpenTargets,
            )
        }

        if (hasNoBoundTemplates) {
            SimpleProminentAlertCard(
                icon = Icons.Outlined.ErrorOutline,
                title = "启用好友尚未绑定消息模板",
                message = "当前 ${state.enabledTargetCount} 位已启用好友还没有绑定发送内容，请前往好友页设置。",
                actionLabel = "去选续火好友",
                tone = FlameTone.WARNING,
                onAction = onOpenTargets,
            )
        }
    }
}

@Composable
private fun SimpleProminentAlertCard(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String,
    tone: FlameTone,
    onAction: () -> Unit,
) {
    val toneColors = resolveFlameToneColors(tone)
    val semantics = FlameThemeTokens.semanticColors
    val palette = FlameThemeTokens.palette
    val haptics = LocalHapticFeedback.current
    val buttonInteraction = remember { MutableInteractionSource() }

    val ctaContainer = when (tone) {
        FlameTone.DANGER -> Color(0xFFDC2626)
        FlameTone.WARNING -> palette.monoCtaContainer
        else -> palette.monoCtaContainer
    }
    val ctaContent = when (tone) {
        FlameTone.DANGER -> Color.White
        FlameTone.WARNING -> palette.monoCtaContent
        else -> palette.monoCtaContent
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = toneColors.container,
        contentColor = toneColors.onContainer,
        border = BorderStroke(
            width = if (tone == FlameTone.DANGER) 1.8.dp else 1.4.dp,
            color = if (tone == FlameTone.DANGER) semantics.dangerBorder else toneColors.border,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = toneColors.fg,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = toneColors.onContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = toneColors.onContainer.copy(alpha = 0.92f),
            )
            Button(
                onClick = {
                    haptics.performFlameLightTick()
                    onAction()
                },
                interactionSource = buttonInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .flamePressScale(interactionSource = buttonInteraction)
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ctaContainer,
                    contentColor = ctaContent,
                ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SimpleCentralFlameButton(
    actionState: SimpleActionState,
    diameter: Dp,
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val isDark = FlameThemeTokens.isDark
    val reducedMotion = rememberReducedMotion()
    val haptics = LocalHapticFeedback.current
    val fontScale = LocalDensity.current.fontScale
    val interactionSource = remember { MutableInteractionSource() }
    val isClickable = actionState !is SimpleActionState.Executing

    val colorAnimDuration = if (reducedMotion) 0 else 220

    val targetRingColor = when (actionState) {
        is SimpleActionState.Executing -> palette.ember
        is SimpleActionState.NeedsUnknownReview -> Color(0xFFDC2626)
        is SimpleActionState.Guided -> when (actionState.action) {
            SimpleGuidedAction.RECOVER_COOLDOWN,
            SimpleGuidedAction.OPEN_DIAGNOSTICS,
            -> semantics.warning

            else -> palette.monoCtaContainer
        }

        is SimpleActionState.ReadyToSend -> palette.emberBright
        is SimpleActionState.AllCompletedToday -> semantics.healthy
    }

    val ringColor by animateColorAsState(
        targetValue = targetRingColor,
        animationSpec = tween(durationMillis = colorAnimDuration, easing = FlameMotionSpec.easeOut),
        label = "simple_main_ring_color",
    )

    val outerDiameter = diameter + 24.dp
    val iconSize = if (fontScale > 1.25f) 36.dp else 44.dp

    Box(
        modifier = Modifier.size(outerDiameter),
        contentAlignment = Alignment.Center,
    ) {
        // 克制柔和静态外环，提供明确视觉聚焦（无装饰性循环动画）
        Box(
            modifier = Modifier
                .size(outerDiameter)
                .clip(CircleShape)
                .background(ringColor.copy(alpha = if (isDark) 0.16f else 0.11f))
                .border(
                    width = 1.5.dp,
                    color = ringColor.copy(alpha = if (isDark) 0.34f else 0.24f),
                    shape = CircleShape,
                ),
        )

        val circleModifier = Modifier
            .flamePressScale(
                interactionSource = interactionSource,
                pressedScale = FlameMotionSpec.PRESS_SCALE_HERO,
                enabled = isClickable,
            )
            .size(diameter)
            .clip(CircleShape)

        val circleBrush: Brush = when (actionState) {
            is SimpleActionState.Executing ->
                Brush.linearGradient(listOf(palette.emberContainer, palette.emberContainer))

            is SimpleActionState.NeedsUnknownReview ->
                Brush.verticalGradient(listOf(Color(0xFFEF4444), Color(0xFFDC2626)))

            is SimpleActionState.Guided -> when (actionState.action) {
                SimpleGuidedAction.RECOVER_COOLDOWN,
                SimpleGuidedAction.OPEN_DIAGNOSTICS,
                -> Brush.verticalGradient(listOf(Color(0xFFD97706), Color(0xFFB45309)))

                else -> Brush.linearGradient(
                    listOf(palette.monoCtaContainer, palette.monoCtaContainer),
                )
            }

            is SimpleActionState.ReadyToSend -> if (isDark) {
                Brush.verticalGradient(listOf(palette.emberBright, palette.ember))
            } else {
                Brush.verticalGradient(listOf(palette.emberBright, palette.ember))
            }

            is SimpleActionState.AllCompletedToday -> if (isDark) {
                Brush.verticalGradient(listOf(Color(0xFF059669), Color(0xFF047857)))
            } else {
                Brush.verticalGradient(listOf(Color(0xFF10B981), Color(0xFF059669)))
            }
        }

        val rimBrush = when (actionState) {
            is SimpleActionState.Executing ->
                Brush.linearGradient(listOf(palette.emberBorder, palette.emberBorder))

            else -> Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = if (isDark) 0.24f else 0.36f),
                    Color.White.copy(alpha = 0.06f),
                ),
            )
        }

        Box(
            modifier = circleModifier
                .background(circleBrush)
                .border(width = 1.5.dp, brush = rimBrush, shape = CircleShape)
                .clickable(
                    enabled = isClickable,
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = {
                        haptics.performFlameLightTick()
                        onClick()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = actionState,
                transitionSpec = {
                    if (reducedMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (fadeIn(tween(180, easing = FlameMotionSpec.easeOut)) +
                            scaleIn(
                                initialScale = 0.96f,
                                animationSpec = tween(180, easing = FlameMotionSpec.easeOut),
                            )) togetherWith fadeOut(tween(130, easing = FlameMotionSpec.easeOut))
                    }
                },
                label = "simple_circle_button_content",
            ) { current ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (current) {
                        is SimpleActionState.Executing -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(iconSize),
                                strokeWidth = 3.8.dp,
                                color = palette.ember,
                                trackColor = palette.emberBorder.copy(alpha = 0.45f),
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "续火中…",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = palette.ember,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = current.progressText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.ember.copy(alpha = 0.88f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }

                        is SimpleActionState.NeedsUnknownReview -> {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(iconSize),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "先核对结果",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "待人工核对 ${current.unknownCount} 条",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.92f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        is SimpleActionState.Guided -> {
                            val contentColor = when (current.action) {
                                SimpleGuidedAction.RECOVER_COOLDOWN,
                                SimpleGuidedAction.OPEN_DIAGNOSTICS,
                                -> Color.White

                                else -> palette.monoCtaContent
                            }
                            val guidedIcon = when (current.action) {
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
                                modifier = Modifier.size(iconSize),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = current.title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = contentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = current.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = contentColor.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }

                        is SimpleActionState.ReadyToSend -> {
                            Icon(
                                imageVector = Icons.Filled.LocalFireDepartment,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(iconSize),
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "一键续火",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 26.sp,
                                    lineHeight = 32.sp,
                                ),
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = "今日 ${current.succeededCount} / ${current.totalCount}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.94f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        is SimpleActionState.AllCompletedToday -> {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(iconSize),
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (current.allSucceeded) "今日已续完" else "今日已处理",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${current.succeededCount} / ${current.totalCount}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.96f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "再次点击可重发",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.86f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleLiveStatusNote(
    text: String,
    isError: Boolean,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (isError) semantics.dangerContainer else palette.surfaceSubtle,
        border = BorderStroke(
            width = 1.dp,
            color = if (isError) semantics.dangerBorder else palette.hairline,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) semantics.onDangerContainer else palette.inkSecondary,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun SimpleScheduleStatusCard(
    text: String,
    isEnabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        onClick = {
            haptics.performFlameLightTick()
            onClick()
        },
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .flamePressScale(interactionSource = interactionSource)
            .heightIn(min = 52.dp),
        shape = RoundedCornerShape(18.dp),
        color = palette.surface,
        contentColor = palette.ink,
        border = BorderStroke(1.dp, palette.hairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = if (isEnabled) semantics.healthy else palette.inkTertiary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = palette.ink,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "调整定时设置",
                tint = palette.inkTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SimpleQuickActionsRow(
    enabledTargetCount: Int,
    stacked: Boolean,
    onOpenTargets: () -> Unit,
    onReloginClick: () -> Unit,
) {
    if (stacked) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SimpleQuickActionButton(
                icon = Icons.Outlined.Groups,
                label = "续火好友（$enabledTargetCount 位）",
                onClick = onOpenTargets,
                modifier = Modifier.fillMaxWidth(),
            )
            SimpleQuickActionButton(
                icon = Icons.Outlined.QrCodeScanner,
                label = "重登抖音",
                onClick = onReloginClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SimpleQuickActionButton(
                icon = Icons.Outlined.Groups,
                label = "续火好友（$enabledTargetCount 位）",
                onClick = onOpenTargets,
                modifier = Modifier.weight(1f),
            )
            SimpleQuickActionButton(
                icon = Icons.Outlined.QrCodeScanner,
                label = "重登抖音",
                onClick = onReloginClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SimpleQuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        onClick = {
            haptics.performFlameLightTick()
            onClick()
        },
        interactionSource = interactionSource,
        modifier = modifier
            .flamePressScale(interactionSource = interactionSource)
            .heightIn(min = 58.dp),
        shape = RoundedCornerShape(18.dp),
        color = palette.surface,
        contentColor = palette.ink,
        border = BorderStroke(1.4.dp, palette.hairlineStrong),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.ink,
                modifier = Modifier.size(21.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
