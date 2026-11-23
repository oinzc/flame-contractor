package io.github.oinzc.flamecontractor.ui.today

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oinzc.flamecontractor.app.AppContainer
import io.github.oinzc.flamecontractor.core.notify.UrgentCategory
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.execution.ExecutionLiveState
import io.github.oinzc.flamecontractor.execution.LoginWebViewActivity
import io.github.oinzc.flamecontractor.scheduler.DailySchedulePlanner
import io.github.oinzc.flamecontractor.ui.components.FlameAlertStrip
import io.github.oinzc.flamecontractor.ui.components.FlameBentoCard
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameEmptyStateCard
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameIconButton
import io.github.oinzc.flamecontractor.ui.components.FlameListRow
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameModalSheet
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameSheetActionRow
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.FlameTopBar
import io.github.oinzc.flamecontractor.ui.components.flameDockBottomPadding
import io.github.oinzc.flamecontractor.ui.components.resolveFlameToneColors
import io.github.oinzc.flamecontractor.ui.shell.MainTab
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.shell.accountStatusStyle
import io.github.oinzc.flamecontractor.ui.shell.accountStatusTone
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TodayRoute(
    container: AppContainer,
    onOpenTab: (MainTab) -> Unit,
    onOpenSettings: (SettingsSection?) -> Unit = {},
    onOpenAccountSwitcher: () -> Unit = {},
    otherAccountAlertBadge: Boolean = false,
    initialUrgentCategory: String? = null,
    modifier: Modifier = Modifier,
    urgentSignalNonce: Long = 0L,
    accountId: String = DEFAULT_ACCOUNT_ID,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: TodayViewModel = viewModel(
        key = "today_$accountId",
        factory = TodayViewModel.factory(container = container, accountId = accountId),
    )
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val loginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshBusinessDate()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBusinessDate()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(initialUrgentCategory, urgentSignalNonce) {
        if (!initialUrgentCategory.isNullOrBlank()) {
            viewModel.handleUrgentCategory(initialUrgentCategory, urgentSignalNonce)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TodayUiEffect.OpenLogin -> {
                    val intent = LoginWebViewActivity.createIntent(
                        context = context,
                        mode = effect.mode,
                        accountId = effect.accountId,
                    )
                    loginLauncher.launch(intent)
                }

                is TodayUiEffect.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
            }
        }
    }

    TodayScreen(
        state = uiState,
        snackbarHostState = snackbarHostState,
        onReloginClick = viewModel::onReloginClick,
        onDismissReloginChoice = viewModel::dismissReloginChoiceDialog,
        onChoosePreserveSessionLogin = viewModel::chooseReloginPreserveSession,
        onChooseClearAndRelogin = viewModel::chooseReloginClearAndRelogin,
        onOpenUnknownReview = { jobId -> viewModel.openUnknownReview(jobId) },
        onDismissUnknownReview = viewModel::dismissUnknownReview,
        onResolveUnknownAsSucceeded = viewModel::resolveUnknownAsSucceeded,
        onResolveUnknownAsCancelled = viewModel::resolveUnknownAsCancelled,
        onOpenCooldownDetail = viewModel::openCooldownDetail,
        onDismissCooldownDetail = viewModel::dismissCooldownDetail,
        onClearCooldown = viewModel::clearCooldown,
        onDismissDomBrokenDialog = viewModel::dismissDomBrokenDialog,
        onDismissUrgentAuthPrompt = viewModel::dismissUrgentAuthPrompt,
        onConfirmUrgentAuthPrompt = viewModel::confirmUrgentAuthPrompt,
        onTriggerManualBatch = viewModel::onTriggerManualBatchClick,
        onDismissManualBatchFeedback = viewModel::dismissManualBatchFeedback,
        onToggleJobExpanded = viewModel::toggleJobExpanded,
        onOpenTab = onOpenTab,
        onOpenSettings = onOpenSettings,
        onOpenAccountSwitcher = onOpenAccountSwitcher,
        otherAccountAlertBadge = otherAccountAlertBadge,
        modifier = modifier,
    )
}

@Composable
fun TodayScreen(
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
    onTriggerManualBatch: () -> Unit,
    onDismissManualBatchFeedback: () -> Unit,
    onToggleJobExpanded: (String) -> Unit,
    onOpenTab: (MainTab) -> Unit,
    onOpenSettings: (SettingsSection?) -> Unit = {},
    onOpenAccountSwitcher: () -> Unit = {},
    otherAccountAlertBadge: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val dockBottomClearance = flameDockBottomPadding()
    var timelineJobId by remember { mutableStateOf<String?>(null) }

    val openJobTimeline: (RecentJobItem) -> Unit = { item ->
        timelineJobId = item.job.id
        if (!item.expanded) {
            onToggleJobExpanded(item.job.id)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(palette.canvas),
    ) {
        val isWideScreen = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = palette.canvas,
            contentColor = palette.ink,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                FlameTopBar(title = "今日") {
                    TodayAccountSwitcherPill(
                        account = state.account,
                        otherAccountAlertBadge = otherAccountAlertBadge,
                        onClick = onOpenAccountSwitcher,
                    )
                    FlameIconButton(
                        icon = Icons.Outlined.Settings,
                        contentDescription = "设置与维护",
                        onClick = { onOpenSettings(null) },
                    )
                }
            },
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.padding(
                        bottom = if (isWideScreen) 16.dp else dockBottomClearance - 12.dp,
                    ),
                )
            },
        ) { innerPadding ->
            if (isWideScreen) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 20.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.46f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TodayOverviewColumn(
                            state = state,
                            onReloginClick = onReloginClick,
                            onOpenAccountSwitcher = onOpenAccountSwitcher,
                            onOpenUnknownReview = onOpenUnknownReview,
                            onOpenCooldownDetail = onOpenCooldownDetail,
                            onTriggerManualBatch = onTriggerManualBatch,
                            onOpenTab = onOpenTab,
                            onOpenSettings = onOpenSettings,
                        )
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(0.54f)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "recent_jobs_section") {
                            TodayRecentJobsSection(
                                recentJobs = state.recentJobs,
                                onOpenJobTimeline = openJobTimeline,
                                onOpenUnknownReview = onOpenUnknownReview,
                                onScanTargets = { onOpenTab(MainTab.TARGETS) },
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = dockBottomClearance,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "overview_section") {
                        TodayOverviewColumn(
                            state = state,
                            onReloginClick = onReloginClick,
                            onOpenAccountSwitcher = onOpenAccountSwitcher,
                            onOpenUnknownReview = onOpenUnknownReview,
                            onOpenCooldownDetail = onOpenCooldownDetail,
                            onTriggerManualBatch = onTriggerManualBatch,
                            onOpenTab = onOpenTab,
                            onOpenSettings = onOpenSettings,
                        )
                    }

                    item(key = "recent_jobs_section") {
                        TodayRecentJobsSection(
                            recentJobs = state.recentJobs,
                            onOpenJobTimeline = openJobTimeline,
                            onOpenUnknownReview = onOpenUnknownReview,
                            onScanTargets = { onOpenTab(MainTab.TARGETS) },
                        )
                    }
                }
            }
        }
    }

    val activeTimelineItem = timelineJobId?.let { id ->
        state.recentJobs.firstOrNull { it.job.id == id }
    }
    if (activeTimelineItem != null) {
        TaskTimelineSheet(
            item = activeTimelineItem,
            onDismiss = {
                if (activeTimelineItem.expanded) {
                    onToggleJobExpanded(activeTimelineItem.job.id)
                }
                timelineJobId = null
            },
            onOpenUnknownReview = {
                if (activeTimelineItem.expanded) {
                    onToggleJobExpanded(activeTimelineItem.job.id)
                }
                timelineJobId = null
                onOpenUnknownReview(activeTimelineItem.job.id)
            },
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
            isResolving = state.isResolvingAction,
            onConfirmSucceeded = { onResolveUnknownAsSucceeded(reviewItem.job.id) },
            onConfirmCancelled = { onResolveUnknownAsCancelled(reviewItem.job.id) },
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
private fun TodayAccountSwitcherPill(
    account: AccountEntity?,
    otherAccountAlertBadge: Boolean,
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val statusStyle = accountStatusStyle(account?.status ?: AccountStatus.PENDING_LOGIN)
    val displayName = account?.displayName?.takeIf { it.isNotBlank() } ?: "默认抖音号"

    Box {
        Surface(
            onClick = onClick,
            modifier = Modifier.heightIn(min = 38.dp),
            shape = RoundedCornerShape(999.dp),
            color = palette.surface,
            contentColor = palette.ink,
            border = BorderStroke(
                width = 1.dp,
                color = if (otherAccountAlertBadge) semantics.dangerBorder else palette.hairline,
            ),
        ) {
            Row(
                modifier = Modifier.padding(start = 11.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusStyle.dotColor),
                )
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 116.dp),
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "切换抖音账号",
                    tint = palette.inkTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (otherAccountAlertBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-1).dp, y = 1.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(semantics.danger)
                    .border(1.5.dp, palette.surface, CircleShape),
            )
        }
    }
}

@Composable
private fun TodayOverviewColumn(
    state: TodayUiState,
    onReloginClick: () -> Unit,
    onOpenAccountSwitcher: () -> Unit,
    onOpenUnknownReview: (String?) -> Unit,
    onOpenCooldownDetail: () -> Unit,
    onTriggerManualBatch: () -> Unit,
    onOpenTab: (MainTab) -> Unit,
    onOpenSettings: (SettingsSection?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TodayHeroStatusCard(
            state = state,
            onTriggerManualBatch = onTriggerManualBatch,
            onReloginClick = onReloginClick,
        )

        TodayConditionalAlertStrips(
            state = state,
            onOpenAccountSwitcher = onOpenAccountSwitcher,
            onOpenUnknownReview = onOpenUnknownReview,
            onOpenCooldownDetail = onOpenCooldownDetail,
            onReloginClick = onReloginClick,
            onOpenTab = onOpenTab,
            onOpenSettings = onOpenSettings,
        )

        TodayBentoMetricsRow(
            state = state,
            onOpenTargets = { onOpenTab(MainTab.TARGETS) },
        )
    }
}

@Composable
private fun TodayHeroStatusCard(
    state: TodayUiState,
    onTriggerManualBatch: () -> Unit,
    onReloginClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val isDark = FlameThemeTokens.isDark
    val account = state.account
    val status = account?.status ?: AccountStatus.PENDING_LOGIN
    val statusStyle = accountStatusStyle(status)
    val statusTone = accountStatusTone(status)
    val isRunning = state.executionState.running || status == AccountStatus.RUNNING
    val emberGlowColor = palette.emberBright

    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                        val glowRadius = size.maxDimension * 0.72f
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    emberGlowColor.copy(alpha = 0.14f),
                                    emberGlowColor.copy(alpha = 0.04f),
                                    Color.Transparent,
                                ),
                                center = Offset(size.width * 0.88f, size.height * 0.22f),
                                radius = glowRadius,
                            ),
                        )
                    }
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FlameStatusPill(
                            text = statusStyle.label,
                            tone = statusTone,
                        )
                        if (state.businessDate.isNotBlank()) {
                            Text(
                                text = state.businessDate,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.inkTertiary,
                                maxLines = 1,
                            )
                        }
                    }

                    Text(
                        text = account?.displayName?.takeIf { it.isNotBlank() } ?: "默认抖音号",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    val boundNick = account?.douyinNickname?.takeIf { it.isNotBlank() }
                    Text(
                        text = if (boundNick != null) {
                            "已绑抖音：$boundNick"
                        } else {
                            "单机本地运行 · 登录态仅存应用沙盒"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = palette.inkTertiary,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = formatScheduleSummary(state),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                HeroFlameCtaButton(
                    isRunning = isRunning,
                    onClick = onTriggerManualBatch,
                )
            }

            AnimatedVisibility(
                visible = state.executionState.running || !state.executionState.lastMessage.isNullOrBlank(),
                enter = fadeIn(tween(180)) + expandVertically(tween(200)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(180)),
            ) {
                HeroLiveProgressStrip(
                    executionState = state.executionState,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }

            HorizontalDivider(thickness = 1.dp, color = palette.hairline)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onReloginClick)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val verificationText = when {
                    account?.lastVerifiedAt != null ->
                        "登录态校验于 ${formatEpochTimestamp(account.lastVerifiedAt)}"

                    account?.lastSuccessAt != null ->
                        "最近确认发火 ${formatEpochTimestamp(account.lastSuccessAt)}"

                    else -> "本地会话待扫码或校验"
                }
                Text(
                    text = verificationText,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "重登 / 换号",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = palette.inkTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroFlameCtaButton(
    isRunning: Boolean,
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val shape = RoundedCornerShape(18.dp)

    if (isRunning) {
        Surface(
            modifier = Modifier.size(60.dp),
            shape = shape,
            color = palette.emberContainer,
            contentColor = palette.ember,
            border = BorderStroke(1.5.dp, palette.emberBorder),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = palette.ember,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "执行中",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ember,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    } else {
        val emberBrush = Brush.linearGradient(
            colors = listOf(palette.emberBright, Color(0xFFEA580C)),
        )
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(shape)
                .background(emberBrush)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.LocalFireDepartment,
                    contentDescription = "立即执行今日续火",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "续火",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun HeroLiveProgressStrip(
    executionState: ExecutionLiveState,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = palette.surfaceSubtle,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (executionState.running) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = executionState.currentTask ?: "正在执行后台任务…",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.ember,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (executionState.progressTotal > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${executionState.progressCurrent}/${executionState.progressTotal}",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.inkSecondary,
                        )
                    }
                }
                if (executionState.progressTotal > 0) {
                    val targetProgress = (executionState.progressCurrent.toFloat() /
                        executionState.progressTotal.toFloat()).coerceIn(0f, 1f)
                    val animatedProgress by animateFloatAsState(
                        targetValue = targetProgress,
                        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                        label = "hero_live_progress",
                    )
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        color = palette.ember,
                        trackColor = palette.hairline,
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        color = palette.ember,
                        trackColor = palette.hairline,
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp),
                    )
                }
            }
            executionState.lastMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TodayConditionalAlertStrips(
    state: TodayUiState,
    onOpenAccountSwitcher: () -> Unit,
    onOpenUnknownReview: (String?) -> Unit,
    onOpenCooldownDetail: () -> Unit,
    onReloginClick: () -> Unit,
    onOpenTab: (MainTab) -> Unit,
    onOpenSettings: (SettingsSection?) -> Unit,
) {
    val account = state.account
    val hasAnyAlert = state.otherRunningAccountName != null ||
        state.unknownReviewJobs.isNotEmpty() ||
        account?.status in setOf(
            AccountStatus.COOLDOWN,
            AccountStatus.DOM_BROKEN,
            AccountStatus.VERIFICATION_REQUIRED,
            AccountStatus.AUTH_EXPIRED,
            AccountStatus.PENDING_LOGIN,
        ) ||
        state.missingImageEnabledTargetCount > 0 ||
        state.missingImageTemplateCount > 0

    if (!hasAnyAlert) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.otherRunningAccountName?.let { runningAccountName ->
            val taskHint = state.otherRunningTaskLabel?.takeIf { it.isNotBlank() }?.let {
                "$it · 多账号任务将按序排队串行运行"
            } ?: "多账号后台任务按序排队串行运行，互不干扰"
            FlameAlertStrip(
                title = "正在后台执行：$runningAccountName",
                subtitle = taskHint,
                tone = FlameTone.NEUTRAL,
                actionLabel = "切号",
                icon = Icons.Outlined.Sync,
                onClick = onOpenAccountSwitcher,
            )
        }

        if (state.unknownReviewJobs.isNotEmpty()) {
            FlameAlertStrip(
                title = "有 ${state.unknownReviewJobs.size} 条发送结果未明，必须人工核对",
                subtitle = "系统已硬禁止自动与手动补发，点击核对确认后方可恢复。",
                tone = FlameTone.DANGER,
                actionLabel = "去核对",
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                onClick = { onOpenUnknownReview(state.unknownReviewJobs.first().job.id) },
            )
        }

        if (account != null) {
            when (account.status) {
                AccountStatus.COOLDOWN -> {
                    val cooldownSummary = if (state.cooldownFailureStats.isEmpty()) {
                        "已连续 ${account.failureStreak} 轮全员失败并熔断停发，点击查看详情或恢复。"
                    } else {
                        val breakdown = state.cooldownFailureStats.take(2)
                            .joinToString(" · ") { "${failureClassLabel(it.failureClass)} ×${it.count}" }
                        "连续 ${account.failureStreak} 轮失败（$breakdown），排查后可解除熔断。"
                    }
                    FlameAlertStrip(
                        title = "连续失败熔断（${account.failureStreak} 轮）",
                        subtitle = cooldownSummary,
                        tone = FlameTone.WARNING,
                        actionLabel = "详情/恢复",
                        icon = Icons.Outlined.WarningAmber,
                        onClick = onOpenCooldownDetail,
                    )
                }

                AccountStatus.DOM_BROKEN -> {
                    FlameAlertStrip(
                        title = "抖音网页核心结构异常",
                        subtitle = "页面核心骨架定位失败，已暂停自动续火，请前往诊断页体检或覆写。",
                        tone = FlameTone.DANGER,
                        actionLabel = "去诊断",
                        icon = Icons.Outlined.Build,
                        onClick = { onOpenSettings(SettingsSection.DIAGNOSTICS) },
                    )
                }

                AccountStatus.VERIFICATION_REQUIRED -> {
                    FlameAlertStrip(
                        title = "触发安全验证，需要本人处理",
                        subtitle = "已保留登录态并停发本轮任务，请打开网页亲自完成验证。",
                        tone = FlameTone.WARNING,
                        actionLabel = "去验证",
                        icon = Icons.Outlined.Security,
                        onClick = onReloginClick,
                    )
                }

                AccountStatus.AUTH_EXPIRED -> {
                    FlameAlertStrip(
                        title = "抖音登录态已失效",
                        subtitle = "连续两次登录校验未通过，自动续火已暂停，请重新扫码登录。",
                        tone = FlameTone.DANGER,
                        actionLabel = "去重登",
                        icon = Icons.Outlined.ErrorOutline,
                        onClick = onReloginClick,
                    )
                }

                AccountStatus.PENDING_LOGIN -> {
                    FlameAlertStrip(
                        title = "尚未扫码登录抖音网页端",
                        subtitle = "扫码登录一次即可在本地安全自动续火，登录态仅存本机沙盒。",
                        tone = FlameTone.EMBER,
                        actionLabel = "立即扫码",
                        icon = Icons.Outlined.QrCodeScanner,
                        onClick = onReloginClick,
                    )
                }

                AccountStatus.HEALTHY,
                AccountStatus.RUNNING,
                AccountStatus.PAUSED,
                -> Unit
            }
        }

        if (state.missingImageEnabledTargetCount > 0 || state.missingImageTemplateCount > 0) {
            val missingTitle = if (state.missingImageEnabledTargetCount > 0) {
                "有 ${state.missingImageEnabledTargetCount} 位启用好友缺少模板图片"
            } else {
                "有 ${state.missingImageTemplateCount} 个图片模板待补图"
            }
            FlameAlertStrip(
                title = missingTitle,
                subtitle = "请前往「模板」页重新选择本地图片，避免发送时因缺少图片而失败。",
                tone = FlameTone.WARNING,
                actionLabel = "去补图",
                icon = Icons.Outlined.Image,
                onClick = { onOpenTab(MainTab.TEMPLATES) },
            )
        }
    }
}

@Composable
private fun TodayBentoMetricsRow(
    state: TodayUiState,
    onOpenTargets: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val completionRatio = if (state.enabledTargetCount > 0) {
        (state.succeededTodayCount.toFloat() / state.enabledTargetCount.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedCompletion by animateFloatAsState(
        targetValue = completionRatio,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "today_bento_completion",
    )
    val allCompleted = state.enabledTargetCount > 0 && state.succeededTodayCount >= state.enabledTargetCount

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlameBentoCard(
            label = "今日已完成",
            value = state.succeededTodayCount.toString(),
            valueSuffix = "/ ${state.enabledTargetCount}",
            valueColor = if (allCompleted) semantics.healthy else palette.ink,
            onClick = onOpenTargets,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            footer = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(palette.surfaceSubtle),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animatedCompletion)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (allCompleted) semantics.healthy else palette.ember),
                        )
                    }
                    val detailText = when {
                        state.issueTodayCount > 0 ->
                            "待发 ${state.pendingTargetCount} · 异常 ${state.issueTodayCount}"

                        state.enabledTargetCount == 0 ->
                            "尚未启用续火好友"

                        state.pendingTargetCount == 0 ->
                            "今日启用对象已全部处理"

                        else ->
                            "今日待发 ${state.pendingTargetCount} 位"
                    }
                    Text(
                        text = detailText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.issueTodayCount > 0) semantics.danger else palette.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        )

        FlameBentoCard(
            label = "最长本地连签",
            value = state.maxLocalStreakDays.toString(),
            valueSuffix = "天",
            valueColor = palette.ember,
            onClick = onOpenTargets,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            footer = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = "启用 ${state.enabledTargetCount} 位 · 暂停 ${state.pausedTargetCount} 位",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        )
    }
}

@Composable
private fun TodayRecentJobsSection(
    recentJobs: List<RecentJobItem>,
    onOpenJobTimeline: (RecentJobItem) -> Unit,
    onOpenUnknownReview: (String?) -> Unit,
    onScanTargets: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    if (recentJobs.isEmpty()) {
        FlameEmptyStateCard(
            icon = Icons.Outlined.LocalFireDepartment,
            title = "暂无发送记录",
            subtitle = "先前往「好友」页扫描导入有火花的好友，即可在每日定时或首页手动执行续火。",
            actionLabel = "前往扫描好友",
            onAction = onScanTargets,
        )
        return
    }

    FlameGroupedSection(
        title = "今日执行动态",
        trailingHeader = {
            Text(
                text = "共 ${recentJobs.size} 条 · 点击看时间线",
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkTertiary,
            )
        },
    ) {
        recentJobs.forEachIndexed { index, item ->
            val job = item.job
            val meta = resolveJobVisualMeta(job.status)
            val subtitleText = buildString {
                append(item.templateLabel)
                append(" · ")
                append(formatEpochTimestamp(job.confirmedAt ?: job.updatedAt))
                val detail = job.summaryDetail?.trim().orEmpty()
                if (detail.isNotEmpty()) {
                    append("\n")
                    append(detail)
                }
            }

            FlameListRow(
                title = item.targetName,
                subtitle = subtitleText,
                titleTag = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FlameMiniTag(
                            text = if (item.isDailySlot) "每日" else "单发",
                            tone = FlameTone.NEUTRAL,
                        )
                        if (item.isImageMissing) {
                            FlameMiniTag(text = "待补图", tone = FlameTone.WARNING)
                        }
                    }
                },
                leading = {
                    FlameRowAvatar(tone = meta.tone) {
                        Icon(
                            imageVector = meta.icon,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                },
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (job.status == JobStatus.UNKNOWN_REQUIRES_REVIEW) {
                            val dangerTone = resolveFlameToneColors(FlameTone.DANGER)
                            Surface(
                                onClick = { onOpenUnknownReview(job.id) },
                                shape = RoundedCornerShape(999.dp),
                                color = dangerTone.container,
                                contentColor = dangerTone.fg,
                                border = BorderStroke(1.dp, dangerTone.border),
                            ) {
                                Text(
                                    text = "去核对",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                )
                            }
                        } else {
                            FlameStatusPill(
                                text = meta.label,
                                tone = meta.tone,
                                showDot = false,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = "查看任务时间线",
                            tint = palette.inkTertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
                showDivider = index < recentJobs.lastIndex,
                dividerIndent = 60.dp,
                onClick = { onOpenJobTimeline(item) },
            )
        }
    }
}

@Composable
private fun TaskTimelineSheet(
    item: RecentJobItem,
    onDismiss: () -> Unit,
    onOpenUnknownReview: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val job = item.job
    val meta = resolveJobVisualMeta(job.status)

    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = item.targetName,
        subtitle = "${job.businessDate} · ${if (item.isDailySlot) "今日自动/批量续火" else "单发测试"} · ${item.templateLabel}",
        trailingHeader = {
            FlameStatusPill(
                text = meta.label,
                tone = meta.tone,
            )
        },
    ) {
        if (!job.summaryDetail.isNullOrBlank() || job.failureClass != null || item.isImageMissing) {
            FlameCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                cornerRadius = 16.dp,
                containerColor = palette.surfaceSubtle,
                contentPadding = PaddingValues(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "执行摘要 · ${formatEpochTimestamp(job.confirmedAt ?: job.updatedAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.inkTertiary,
                    )
                    job.summaryDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.ink,
                        )
                    }
                    job.failureClass?.let { fc ->
                        Text(
                            text = "失败分类：${failureClassLabel(fc)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = semantics.danger,
                        )
                    }
                    if (item.isImageMissing) {
                        Text(
                            text = "关联图片模板缺少本地图片（待补图）",
                            style = MaterialTheme.typography.labelSmall,
                            color = semantics.warning,
                        )
                    }
                }
            }
        }

        Text(
            text = "执行尝试时间线",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.inkTertiary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (!item.attemptsLoaded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(
                    color = palette.monoCtaContainer,
                    trackColor = palette.surfaceSubtle,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "正在读取发送尝试明细…",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                )
            }
        } else if (item.attempts.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = palette.surfaceSubtle,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "暂无发送尝试明细记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                    modifier = Modifier.padding(14.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(items = item.attempts, key = { it.id }) { attempt ->
                    AttemptTimelineRow(attempt = attempt)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (job.status == JobStatus.UNKNOWN_REQUIRES_REVIEW) {
            FlameSheetActionRow(
                text = "立即人工核对发送结果",
                primary = true,
                danger = true,
                onClick = onOpenUnknownReview,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        FlameSheetActionRow(
            text = "关闭",
            onClick = onDismiss,
        )
    }
}

@Composable
private fun AttemptTimelineRow(attempt: SendAttemptEntity) {
    val palette = FlameThemeTokens.palette
    val (statusText, tone) = when (attempt.status) {
        AttemptStatus.SUCCEEDED -> "成功" to FlameTone.HEALTHY
        AttemptStatus.FAILED -> "失败" to FlameTone.DANGER
        AttemptStatus.RUNNING -> "进行中" to FlameTone.EMBER
    }
    val stageText = when (attempt.stage) {
        "opening_chat" -> "打开会话"
        "sending" -> "写入发送"
        "confirming" -> "等待确认"
        "auth_probe" -> "登录探测"
        else -> attempt.stage
    }
    val methodText = when (attempt.inputMethodUsed) {
        "js_execCommand" -> "JS 写入"
        "android_ime" -> "IME 输入"
        "sticker_click" -> "表情点击"
        "image_chooser" -> "图片选择上传"
        "image_datatransfer" -> "图片注入上传"
        "image_upload" -> "图片上传"
        null -> "--"
        else -> attempt.inputMethodUsed
    }

    val durationLabel = attempt.finishedAt?.let { end ->
        val elapsedMs = (end - attempt.startedAt).coerceAtLeast(0L)
        if (elapsedMs < 1000L) {
            "${elapsedMs}ms"
        } else {
            String.format(Locale.US, "%.1fs", elapsedMs / 1000.0)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = palette.surfaceSubtle,
        border = BorderStroke(1.dp, palette.hairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "第 ${attempt.attemptNo} 次 · $stageText · $methodText",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.ink,
                )
                val failHint = attempt.failureClass?.let(::failureClassLabel)
                val metaLine = buildString {
                    append(formatEpochTimeOnly(attempt.startedAt))
                    attempt.finishedAt?.let {
                        append(" – ")
                        append(formatEpochTimeOnly(it))
                    }
                    if (durationLabel != null) {
                        append(" (")
                        append(durationLabel)
                        append(")")
                    }
                    if (failHint != null) {
                        append(" · ")
                        append(failHint)
                    }
                }
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                )
            }
            FlameStatusPill(
                text = statusText,
                tone = tone,
                showDot = false,
            )
        }
    }
}

@Composable
private fun ReloginChoiceSheet(
    onDismiss: () -> Unit,
    onPreserveSession: () -> Unit,
    onClearAndRelogin: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors

    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = "选择扫码 / 重登方式",
        subtitle = "当前已有登录配置，请明确选择保留当前会话或清理后换号",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlameCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                containerColor = palette.surfaceSubtle,
                contentPadding = PaddingValues(14.dp),
                onClick = onPreserveSession,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FlameRowAvatar(tone = FlameTone.HEALTHY) {
                        Icon(
                            imageVector = Icons.Outlined.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "打开当前抖音网页（保留登录态排查）",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.ink,
                        )
                        Text(
                            text = "不清理 Cookie 与本地缓存，直接打开网页核对聊天列表或页面状态。",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = palette.inkTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            FlameCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                containerColor = semantics.warningContainer,
                borderColor = semantics.warningBorder,
                contentPadding = PaddingValues(14.dp),
                onClick = onClearAndRelogin,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FlameRowAvatar(tone = FlameTone.WARNING) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "退出并清理 Cookie 重新扫码",
                            style = MaterialTheme.typography.titleSmall,
                            color = semantics.onWarningContainer,
                        )
                        Text(
                            text = "彻底清除当前 Cookie 与 WebStorage 后展示全新二维码，用于重登或更换抖音号。",
                            style = MaterialTheme.typography.bodySmall,
                            color = semantics.onWarningContainer.copy(alpha = 0.85f),
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = semantics.warning,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            FlameSheetActionRow(
                text = "取消",
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun UnknownReviewSheet(
    item: UnknownJobReviewItem,
    isResolving: Boolean,
    onConfirmSucceeded: () -> Unit,
    onConfirmCancelled: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val isDark = FlameThemeTokens.isDark

    FlameModalSheet(
        onDismissRequest = { if (!isResolving) onDismiss() },
        title = "人工核对未知发送结果",
        subtitle = "严禁自动重发 · 请先在手机抖音核对聊天记录",
        trailingHeader = {
            FlameStatusPill(text = "待人工核对", tone = FlameTone.DANGER)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlameCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                containerColor = palette.surfaceSubtle,
                contentPadding = PaddingValues(13.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "好友：${item.targetName}",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.ink,
                    )
                    Text(
                        text = "日期：${item.job.businessDate} · ${if (item.isDailySlot) "今日自动/批量续火" else "单发测试"} · ${item.templateLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )
                    item.job.summaryDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                        Text(
                            text = "原因：$detail",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = semantics.dangerContainer,
                border = BorderStroke(1.dp, semantics.dangerBorder),
            ) {
                Text(
                    text = "系统已触发过发送动作但未能从页面确认回执，为防重复发消息，严禁自动重发。请先打开手机抖音查看聊天记录，再从以下两项中终裁：",
                    style = MaterialTheme.typography.bodySmall,
                    color = semantics.onDangerContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }

            Surface(
                onClick = onConfirmSucceeded,
                enabled = !isResolving,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (isResolving) palette.surfaceSubtle else semantics.healthy,
                contentColor = when {
                    isResolving -> palette.inkTertiary
                    isDark -> Color(0xFF06281E)
                    else -> Color.White
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "我在抖音里看到发出了（标为已成功）",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            FlameSheetActionRow(
                text = "确认没发出（标为今日取消，不再自动发）",
                enabled = !isResolving,
                onClick = onConfirmCancelled,
            )

            FlameSheetActionRow(
                text = "暂不处理",
                enabled = !isResolving,
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun CooldownDetailSheet(
    account: AccountEntity,
    stats: List<SanitizedFailureStat>,
    isResolving: Boolean,
    onRecover: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors

    FlameModalSheet(
        onDismissRequest = { if (!isResolving) onDismiss() },
        title = "连续失败熔断详情",
        subtitle = "已连续 ${account.failureStreak} 轮自动发送全员终态失败",
        trailingHeader = {
            FlameStatusPill(text = "已熔断", tone = FlameTone.WARNING)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = semantics.warningContainer,
                border = BorderStroke(1.dp, semantics.warningBorder),
            ) {
                Text(
                    text = "系统为避免持续异常发信已暂停自动调度。请核查好友是否同名冲突、绑定模板是否可用或网页是否正常，确认后解除熔断。",
                    style = MaterialTheme.typography.bodySmall,
                    color = semantics.onWarningContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }

            if (stats.isNotEmpty()) {
                FlameGroupedSection(title = "近期脱敏失败分类统计") {
                    stats.forEachIndexed { index, stat ->
                        FlameListRow(
                            title = failureClassLabel(stat.failureClass),
                            trailing = {
                                Text(
                                    text = "${stat.count} 次",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = palette.inkSecondary,
                                )
                            },
                            showDivider = index < stats.lastIndex,
                        )
                    }
                }
            }

            FlameSheetActionRow(
                text = "解除熔断并恢复运行",
                primary = true,
                enabled = !isResolving,
                onClick = onRecover,
            )

            FlameSheetActionRow(
                text = "稍后处理",
                enabled = !isResolving,
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun DomBrokenSheet(
    onOpenDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    val semantics = FlameThemeTokens.semanticColors

    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = "网页核心结构异常",
        subtitle = "已暂停自动续火以避免误发",
        trailingHeader = {
            FlameStatusPill(text = "结构异常", tone = FlameTone.DANGER)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = semantics.dangerContainer,
                border = BorderStroke(1.dp, semantics.dangerBorder),
            ) {
                Text(
                    text = "抖音网页聊天页骨架选择器未命中，系统已暂停自动续火以避免误发。请前往「网页诊断与定位器维护」运行只读网页结构体检或更新定位器覆写。",
                    style = MaterialTheme.typography.bodySmall,
                    color = semantics.onDangerContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }

            FlameSheetActionRow(
                text = "前往诊断页体检 / 覆写",
                primary = true,
                onClick = onOpenDiagnostics,
            )

            FlameSheetActionRow(
                text = "稍后处理",
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun UrgentAuthPromptSheet(
    category: UrgentCategory,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isVerify = category == UrgentCategory.VERIFICATION_REQUIRED
    val tone = if (isVerify) FlameTone.WARNING else FlameTone.DANGER
    val toneColors = resolveFlameToneColors(tone)

    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = if (isVerify) "需要本人完成身份验证" else "抖音登录态已失效",
        subtitle = if (isVerify) "已保留当前登录态并暂停后续发信" else "连续两次登录校验未通过，自动续火已暂停",
        trailingHeader = {
            FlameStatusPill(
                text = if (isVerify) "需验证" else "登录失效",
                tone = tone,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = toneColors.container,
                border = BorderStroke(1.dp, toneColors.border),
            ) {
                Text(
                    text = if (isVerify) {
                        "抖音网页触发了安全验证弹窗，系统已保留当前登录态并暂停后续发信，请打开网页亲自完成验证。"
                    } else {
                        "连续两次登录状态检测未通过，自动续火已暂停，请打开网页重新扫码登录。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = toneColors.onContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }

            FlameSheetActionRow(
                text = if (isVerify) "前往保留态验证" else "前往扫码登录",
                primary = true,
                danger = !isVerify,
                onClick = onConfirm,
            )

            FlameSheetActionRow(
                text = "暂不处理",
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun ManualBatchFeedbackSheet(
    feedback: ManualBatchFeedback,
    onDismiss: () -> Unit,
    onOpenUnknownReview: () -> Unit,
    onOpenLogin: () -> Unit,
    onRecoverCooldown: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTargets: () -> Unit,
) {
    val isHardBlock = feedback.reason == ManualBatchBlockReason.UNKNOWN_OR_INFLIGHT
    val tone = if (isHardBlock) FlameTone.DANGER else FlameTone.WARNING
    val toneColors = resolveFlameToneColors(tone)

    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = feedback.title,
        trailingHeader = {
            FlameStatusPill(
                text = if (isHardBlock) "安全拦截" else "提示",
                tone = tone,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = toneColors.container,
                border = BorderStroke(1.dp, toneColors.border),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = if (isHardBlock) {
                            Icons.Outlined.VerifiedUser
                        } else {
                            Icons.Outlined.WarningAmber
                        },
                        contentDescription = null,
                        tint = toneColors.fg,
                        modifier = Modifier.size(19.dp),
                    )
                    Text(
                        text = feedback.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = toneColors.onContainer,
                    )
                }
            }

            when {
                feedback.canJumpToUnknownReview -> FlameSheetActionRow(
                    text = "立即去核对",
                    primary = true,
                    danger = true,
                    onClick = onOpenUnknownReview,
                )

                feedback.canJumpToLogin -> FlameSheetActionRow(
                    text = "前往登录 / 验证",
                    primary = true,
                    onClick = onOpenLogin,
                )

                feedback.canRecoverCooldown -> FlameSheetActionRow(
                    text = "解除熔断并恢复",
                    primary = true,
                    onClick = onRecoverCooldown,
                )

                feedback.canJumpToSettings -> FlameSheetActionRow(
                    text = "前往设置处理",
                    primary = true,
                    onClick = onOpenSettings,
                )

                feedback.canJumpToTargets -> FlameSheetActionRow(
                    text = "前往好友页",
                    primary = true,
                    onClick = onOpenTargets,
                )
            }

            FlameSheetActionRow(
                text = "关闭",
                onClick = onDismiss,
            )
        }
    }
}

private data class JobVisualMeta(
    val label: String,
    val tone: FlameTone,
    val icon: ImageVector,
)

private fun resolveJobVisualMeta(status: JobStatus): JobVisualMeta = when (status) {
    JobStatus.SUCCEEDED -> JobVisualMeta("已确认", FlameTone.HEALTHY, Icons.Outlined.CheckCircle)
    JobStatus.UNKNOWN_REQUIRES_REVIEW -> JobVisualMeta("待核对", FlameTone.DANGER, Icons.AutoMirrored.Outlined.HelpOutline)
    JobStatus.CANCELLED -> JobVisualMeta("已取消", FlameTone.NEUTRAL, Icons.Outlined.Cancel)
    JobStatus.FAILED_RETRYABLE -> JobVisualMeta("待重试", FlameTone.WARNING, Icons.Outlined.Refresh)
    JobStatus.FAILED_TERMINAL -> JobVisualMeta("终态失败", FlameTone.DANGER, Icons.Outlined.ErrorOutline)
    JobStatus.LEASED,
    JobStatus.OPENING_CHAT,
    JobStatus.SENDING,
    JobStatus.CONFIRMING,
    -> JobVisualMeta("执行中", FlameTone.EMBER, Icons.Outlined.Sync)
    JobStatus.SCHEDULED -> JobVisualMeta("已计划", FlameTone.NEUTRAL, Icons.Outlined.Schedule)
}

private fun formatScheduleSummary(state: TodayUiState): String {
    val account = state.account ?: return "尚未初始化账号配置"
    if (!account.enabled || account.status == AccountStatus.PAUSED) {
        return "每日自动定时已暂停（可在设置页开启）"
    }
    if (state.authRecheckAtEpochMs != null && account.authFailCount == 1) {
        return "登录态待复判 · 预计 ${formatWakeTime(state.authRecheckAtEpochMs, account.timezone)} 复检"
    }
    if (account.status != AccountStatus.HEALTHY) {
        return "自动调度已暂挂（当前：${accountStatusLabel(account.status)}）"
    }
    val nextMs = state.nextAutoTriggerAtEpochMs
        ?: return "今日暂无后续排班 · 窗口 ${account.sendWindowStart}–${account.sendWindowEnd}"
    return "下次自动：${formatWakeTime(nextMs, account.timezone)} · 窗口 ${account.sendWindowStart}–${account.sendWindowEnd}"
}

private fun formatWakeTime(epochMs: Long, timezone: String): String {
    val zoneId = DailySchedulePlanner.resolveZoneId(timezone)
    val target = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zoneId)
    val today = LocalDate.now(zoneId)
    val timeStr = TIME_FORMATTER.format(target)
    return when (target.toLocalDate()) {
        today -> "今天 $timeStr"
        today.plusDays(1) -> "明天 $timeStr"
        else -> DATE_TIME_FORMATTER.format(target)
    }
}

private fun formatEpochTimestamp(epochMs: Long): String {
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
    return DATE_TIME_FORMATTER.format(zdt)
}

private fun formatEpochTimeOnly(epochMs: Long): String {
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
    return TIME_SEC_FORMATTER.format(zdt)
}

private fun failureClassLabel(failureClass: FailureClass): String = when (failureClass) {
    FailureClass.NETWORK -> "网络异常"
    FailureClass.AUTH -> "登录失效"
    FailureClass.VERIFICATION -> "风控验证"
    FailureClass.TARGET_UNCERTAIN -> "目标/身份不确定"
    FailureClass.DOM_CHANGED -> "页面结构变化"
    FailureClass.UNKNOWN_OUTCOME -> "发送结果未明"
    FailureClass.OTHER -> "其他错误"
}

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
private val TIME_SEC_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")
