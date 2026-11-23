package io.github.oinzc.flamecontractor.ui.today

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import io.github.oinzc.flamecontractor.ui.shell.MainTab
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val isWideScreen = maxWidth >= 600.dp
            if (isWideScreen) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.46f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        TodayOverviewSection(
                            state = state,
                            onReloginClick = onReloginClick,
                            onOpenUnknownReview = onOpenUnknownReview,
                            onOpenCooldownDetail = onOpenCooldownDetail,
                            onClearCooldown = onClearCooldown,
                            onTriggerManualBatch = onTriggerManualBatch,
                            onOpenTab = onOpenTab,
                            onOpenSettings = onOpenSettings,
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(0.54f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RecentJobsHeader(count = state.recentJobs.size)
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 16.dp),
                        ) {
                            if (state.recentJobs.isEmpty()) {
                                item(key = "empty_recent") {
                                    EmptyRecentJobsCard(onScanTargets = { onOpenTab(MainTab.TARGETS) })
                                }
                            } else {
                                items(state.recentJobs, key = { it.job.id }) { item ->
                                    RecentJobCard(
                                        item = item,
                                        onToggleExpand = { onToggleJobExpanded(item.job.id) },
                                        onOpenUnknownReview = { onOpenUnknownReview(item.job.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "overview_section") {
                        TodayOverviewSection(
                            state = state,
                            onReloginClick = onReloginClick,
                            onOpenUnknownReview = onOpenUnknownReview,
                            onOpenCooldownDetail = onOpenCooldownDetail,
                            onClearCooldown = onClearCooldown,
                            onTriggerManualBatch = onTriggerManualBatch,
                            onOpenTab = onOpenTab,
                            onOpenSettings = onOpenSettings,
                        )
                    }

                    item(key = "recent_header") {
                        RecentJobsHeader(
                            count = state.recentJobs.size,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    if (state.recentJobs.isEmpty()) {
                        item(key = "empty_recent") {
                            EmptyRecentJobsCard(onScanTargets = { onOpenTab(MainTab.TARGETS) })
                        }
                    } else {
                        items(state.recentJobs, key = { it.job.id }) { item ->
                            RecentJobCard(
                                item = item,
                                onToggleExpand = { onToggleJobExpanded(item.job.id) },
                                onOpenUnknownReview = { onOpenUnknownReview(item.job.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.showReloginChoiceDialog) {
        ReloginChoiceDialog(
            onDismiss = onDismissReloginChoice,
            onPreserveSession = onChoosePreserveSessionLogin,
            onClearAndRelogin = onChooseClearAndRelogin,
        )
    }

    state.activeUnknownReviewJob?.let { reviewItem ->
        UnknownReviewDialog(
            item = reviewItem,
            isResolving = state.isResolvingAction,
            onConfirmSucceeded = { onResolveUnknownAsSucceeded(reviewItem.job.id) },
            onConfirmCancelled = { onResolveUnknownAsCancelled(reviewItem.job.id) },
            onDismiss = onDismissUnknownReview,
        )
    }

    if (state.showCooldownDetailDialog && state.account?.status == AccountStatus.COOLDOWN) {
        CooldownDetailDialog(
            account = state.account,
            stats = state.cooldownFailureStats,
            isResolving = state.isResolvingAction,
            onRecover = onClearCooldown,
            onDismiss = onDismissCooldownDetail,
        )
    }

    if (state.showDomBrokenDialog) {
        AlertDialog(
            onDismissRequest = onDismissDomBrokenDialog,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = null,
                    tint = FlameThemeTokens.semanticColors.danger,
                )
            },
            title = { Text("网页核心结构异常") },
            text = {
                Text("抖音网页聊天页骨架选择器未命中，系统已暂停自动续火以避免误发。请前往「设置」页运行只读网页结构体检或更新定位器覆写。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDismissDomBrokenDialog()
                        onOpenSettings(SettingsSection.DIAGNOSTICS)
                    },
                ) {
                    Text("前往设置体检")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDomBrokenDialog) {
                    Text("稍后处理")
                }
            },
        )
    }

    state.urgentAuthPromptCategory?.let { category ->
        val isVerify = category == UrgentCategory.VERIFICATION_REQUIRED
        AlertDialog(
            onDismissRequest = onDismissUrgentAuthPrompt,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = if (isVerify) {
                        FlameThemeTokens.semanticColors.warning
                    } else {
                        FlameThemeTokens.semanticColors.danger
                    },
                )
            },
            title = {
                Text(if (isVerify) "需要本人完成身份验证" else "抖音登录态已失效")
            },
            text = {
                Text(
                    if (isVerify) {
                        "抖音网页触发了安全验证弹窗，系统已保留当前登录态并暂停后续发信，请打开网页亲自完成验证。"
                    } else {
                        "连续两次登录状态检测未通过，自动续火已暂停，请打开网页重新扫码登录。"
                    },
                )
            },
            confirmButton = {
                Button(onClick = onConfirmUrgentAuthPrompt) {
                    Text(if (isVerify) "前往保留态验证" else "前往扫码登录")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissUrgentAuthPrompt) {
                    Text("暂不处理")
                }
            },
        )
    }

    state.manualBatchFeedback?.let { feedback ->
        ManualBatchFeedbackDialog(
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
                onOpenSettings(SettingsSection.SCHEDULE)
            },
            onOpenTargets = {
                onDismissManualBatchFeedback()
                onOpenTab(MainTab.TARGETS)
            },
        )
    }
}

@Composable
private fun TodayOverviewSection(
    state: TodayUiState,
    onReloginClick: () -> Unit,
    onOpenUnknownReview: (String?) -> Unit,
    onOpenCooldownDetail: () -> Unit,
    onClearCooldown: () -> Unit,
    onTriggerManualBatch: () -> Unit,
    onOpenTab: (MainTab) -> Unit,
    onOpenSettings: (SettingsSection?) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccountHealthCapsuleCard(
            account = state.account,
            onReloginClick = onReloginClick,
        )

        state.otherRunningAccountName?.let { runningAccountName ->
            OtherAccountRunningBanner(
                accountName = runningAccountName,
                taskLabel = state.otherRunningTaskLabel,
            )
        }

        if (state.missingImageEnabledTargetCount > 0 || state.missingImageTemplateCount > 0) {
            MissingImageTemplateBanner(
                missingEnabledTargetCount = state.missingImageEnabledTargetCount,
                missingTemplateCount = state.missingImageTemplateCount,
                onGoTemplates = { onOpenTab(MainTab.TEMPLATES) },
            )
        }

        if (state.unknownReviewJobs.isNotEmpty()) {
            UnknownReviewBanner(
                count = state.unknownReviewJobs.size,
                onReviewClick = { onOpenUnknownReview(state.unknownReviewJobs.first().job.id) },
            )
        }

        val account = state.account
        if (account != null) {
            when (account.status) {
                AccountStatus.COOLDOWN -> {
                    CooldownBanner(
                        failureStreak = account.failureStreak,
                        stats = state.cooldownFailureStats,
                        isResolving = state.isResolvingAction,
                        onBannerClick = onOpenCooldownDetail,
                        onRecoverClick = onClearCooldown,
                    )
                }

                AccountStatus.DOM_BROKEN -> {
                    DomBrokenBanner(
                        onFixClick = { onOpenSettings(SettingsSection.DIAGNOSTICS) },
                    )
                }

                AccountStatus.VERIFICATION_REQUIRED -> {
                    AuthOrVerificationBanner(
                        isVerification = true,
                        onActionClick = onReloginClick,
                    )
                }

                AccountStatus.AUTH_EXPIRED -> {
                    AuthOrVerificationBanner(
                        isVerification = false,
                        onActionClick = onReloginClick,
                    )
                }

                AccountStatus.PENDING_LOGIN -> {
                    PendingLoginBanner(onLoginClick = onReloginClick)
                }

                AccountStatus.HEALTHY,
                AccountStatus.RUNNING,
                AccountStatus.PAUSED,
                -> Unit
            }
        }

        TodaySummaryCard(
            state = state,
            onTriggerManualBatch = onTriggerManualBatch,
            onScanTargets = { onOpenTab(MainTab.TARGETS) },
        )
    }
}

@Composable
private fun AccountHealthCapsuleCard(
    account: AccountEntity?,
    onReloginClick: () -> Unit,
) {
    val status = account?.status ?: AccountStatus.PENDING_LOGIN
    val semantics = FlameThemeTokens.semanticColors
    val (badgeText, badgeFg, badgeBg) = when (status) {
        AccountStatus.HEALTHY -> Triple("健康", semantics.onHealthyContainer, semantics.healthyContainer)
        AccountStatus.RUNNING -> Triple("执行中", MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer)
        AccountStatus.PENDING_LOGIN -> Triple("待扫码", semantics.onWarningContainer, semantics.warningContainer)
        AccountStatus.PAUSED -> Triple("已暂停", semantics.onNeutralContainer, semantics.neutralContainer)
        AccountStatus.AUTH_EXPIRED -> Triple("登录失效", semantics.onDangerContainer, semantics.dangerContainer)
        AccountStatus.VERIFICATION_REQUIRED -> Triple("需验证", semantics.onWarningContainer, semantics.warningContainer)
        AccountStatus.DOM_BROKEN -> Triple("结构异常", semantics.onDangerContainer, semantics.dangerContainer)
        AccountStatus.COOLDOWN -> Triple("已熔断", semantics.onWarningContainer, semantics.warningContainer)
    }

    ElevatedCard(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.LocalFireDepartment,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = account?.displayName ?: "默认抖音号",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusPill(text = badgeText, contentColor = badgeFg, containerColor = badgeBg)
                }
                val nick = account?.douyinNickname?.takeIf { it.isNotBlank() }
                val subText = if (nick != null) {
                    "已绑定抖音号：$nick"
                } else {
                    "单机独立运行 · 数据留存本地"
                }
                Text(
                    text = subText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FilledTonalButton(
                onClick = onReloginClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("扫码/重登", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun OtherAccountRunningBanner(
    accountName: String,
    taskLabel: String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Sync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "正在后台执行：$accountName",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = taskLabel?.takeIf { it.isNotBlank() }?.let {
                        "$it · 多账号任务将按序排队串行运行"
                    } ?: "多账号后台任务按序排队串行运行，互不干扰",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun MissingImageTemplateBanner(
    missingEnabledTargetCount: Int,
    missingTemplateCount: Int,
    onGoTemplates: () -> Unit,
) {
    val semantics = FlameThemeTokens.semanticColors
    val title = if (missingEnabledTargetCount > 0) {
        "有 $missingEnabledTargetCount 位启用好友的图片模板缺少本地图片"
    } else {
        "有 $missingTemplateCount 个图片模板缺少本地图片（待补图）"
    }
    UrgentBannerCard(
        icon = Icons.Filled.Image,
        title = title,
        body = "请前往「模板」页重新选择本地图片，避免发送时因缺少图片文件而失败。",
        actionLabel = "去补图",
        containerColor = semantics.warningContainer,
        contentColor = semantics.onWarningContainer,
        accentColor = semantics.warning,
        onActionClick = onGoTemplates,
        onCardClick = onGoTemplates,
    )
}

@Composable
private fun UnknownReviewBanner(
    count: Int,
    onReviewClick: () -> Unit,
) {
    val semantics = FlameThemeTokens.semanticColors
    UrgentBannerCard(
        icon = Icons.AutoMirrored.Filled.HelpOutline,
        title = "有 $count 条发送结果未明，必须人工核对",
        body = "已触发发送但未在页面确认结果。系统已硬禁止自动与手动补发，点击核对后才可恢复。",
        actionLabel = "去核对",
        containerColor = semantics.dangerContainer,
        contentColor = semantics.onDangerContainer,
        accentColor = semantics.danger,
        onActionClick = onReviewClick,
        onCardClick = onReviewClick,
    )
}

@Composable
private fun CooldownBanner(
    failureStreak: Int,
    stats: List<SanitizedFailureStat>,
    isResolving: Boolean,
    onBannerClick: () -> Unit,
    onRecoverClick: () -> Unit,
) {
    val semantics = FlameThemeTokens.semanticColors
    val summary = if (stats.isEmpty()) {
        "已连续 $failureStreak 轮自动发送全员失败，系统自动熔断停发。排查目标或模板后点击恢复。"
    } else {
        val topBreakdown = stats.take(3).joinToString(" · ") { "${failureClassLabel(it.failureClass)} ×${it.count}" }
        "已连续 $failureStreak 轮失败（近期：$topBreakdown），请排查后恢复运行。"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onBannerClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = semantics.warningContainer,
            contentColor = semantics.onWarningContainer,
        ),
        border = BorderStroke(1.dp, semantics.warning.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = semantics.warning,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "连续失败熔断（$failureStreak 轮）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = onRecoverClick,
                    enabled = !isResolving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = semantics.warning,
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("解除熔断并恢复运行")
                }
            }
        }
    }
}

@Composable
private fun DomBrokenBanner(
    onFixClick: () -> Unit,
) {
    val semantics = FlameThemeTokens.semanticColors
    UrgentBannerCard(
        icon = Icons.Filled.Build,
        title = "抖音网页核心结构异常",
        body = "页面核心骨架定位失败，已暂停自动续火。请前往设置页运行网页结构体检或更新定位器覆写。",
        actionLabel = "前往体检/覆写",
        containerColor = semantics.dangerContainer,
        contentColor = semantics.onDangerContainer,
        accentColor = semantics.danger,
        onActionClick = onFixClick,
        onCardClick = onFixClick,
    )
}

@Composable
private fun AuthOrVerificationBanner(
    isVerification: Boolean,
    onActionClick: () -> Unit,
) {
    val semantics = FlameThemeTokens.semanticColors
    if (isVerification) {
        UrgentBannerCard(
            icon = Icons.Filled.Security,
            title = "触发安全验证，需要本人处理",
            body = "系统已保留登录态并停发本轮任务，请打开抖音网页完成身份验证。",
            actionLabel = "去完成验证",
            containerColor = semantics.warningContainer,
            contentColor = semantics.onWarningContainer,
            accentColor = semantics.warning,
            onActionClick = onActionClick,
            onCardClick = onActionClick,
        )
    } else {
        UrgentBannerCard(
            icon = Icons.Filled.ErrorOutline,
            title = "抖音登录态已失效",
            body = "连续两次登录校验未通过，自动续火已暂停，请重新扫码登录。",
            actionLabel = "去扫码重登",
            containerColor = semantics.dangerContainer,
            contentColor = semantics.onDangerContainer,
            accentColor = semantics.danger,
            onActionClick = onActionClick,
            onCardClick = onActionClick,
        )
    }
}

@Composable
private fun PendingLoginBanner(
    onLoginClick: () -> Unit,
) {
    UrgentBannerCard(
        icon = Icons.Filled.QrCodeScanner,
        title = "尚未扫码登录抖音网页端",
        body = "扫码登录一次即可在本地后台安全自动续火，登录态仅存本机应用沙盒。",
        actionLabel = "立即扫码",
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        accentColor = MaterialTheme.colorScheme.primary,
        onActionClick = onLoginClick,
        onCardClick = onLoginClick,
    )
}

@Composable
private fun UrgentBannerCard(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    containerColor: Color,
    contentColor: Color,
    accentColor: Color,
    onActionClick: () -> Unit,
    onCardClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(26.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FilledTonalButton(
                onClick = onActionClick,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = accentColor,
                    contentColor = Color.White,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun TodaySummaryCard(
    state: TodayUiState,
    onTriggerManualBatch: () -> Unit,
    onScanTargets: () -> Unit,
) {
    val semantics = FlameThemeTokens.semanticColors
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "今日续火总览",
                    style = MaterialTheme.typography.titleLarge,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        text = state.businessDate.ifBlank { "--" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverviewStatTile(
                    label = "今日待续火",
                    value = state.pendingTargetCount.toString(),
                    sublabel = "启用 ${state.enabledTargetCount}",
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                OverviewStatTile(
                    label = "已成功",
                    value = state.succeededTodayCount.toString(),
                    sublabel = "今日确认",
                    accentColor = semantics.healthy,
                    modifier = Modifier.weight(1f),
                )
                OverviewStatTile(
                    label = "异常",
                    value = state.issueTodayCount.toString(),
                    sublabel = if (state.issueTodayCount > 0) "需处理" else "正常",
                    accentColor = if (state.issueTodayCount > 0) semantics.danger else semantics.neutral,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = formatScheduleSummary(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LiveExecutionStrip(executionState = state.executionState)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onTriggerManualBatch,
                    modifier = Modifier.weight(1.2f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("立即执行今日续火")
                }

                OutlinedButton(
                    onClick = onScanTargets,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PersonSearch,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("扫描好友火花")
                }
            }
        }
    }
}

@Composable
private fun OverviewStatTile(
    label: String,
    value: String,
    sublabel: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = accentColor,
            )
            Text(
                text = sublabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LiveExecutionStrip(
    executionState: ExecutionLiveState,
) {
    if (!executionState.running && executionState.lastMessage.isNullOrBlank()) return
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (executionState.progressTotal > 0) {
                        Text(
                            text = "${executionState.progressCurrent}/${executionState.progressTotal}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (executionState.progressTotal > 0) {
                    val progress = (executionState.progressCurrent.toFloat() /
                        executionState.progressTotal.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            executionState.lastMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecentJobsHeader(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "最近发送记录",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "共 $count 条 · 点击展开明细",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyRecentJobsCard(
    onScanTargets: () -> Unit,
) {
    OutlinedCard(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "暂无发送记录",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "先前往「好友」页扫描导入有火花的好友，即可在每日定时或首页手动执行续火。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onScanTargets) {
                Text("前往扫描好友")
            }
        }
    }
}

@Composable
private fun RecentJobCard(
    item: RecentJobItem,
    onToggleExpand: () -> Unit,
    onOpenUnknownReview: () -> Unit,
) {
    val job = item.job
    val semantics = FlameThemeTokens.semanticColors
    val (statusLabel, statusFg, statusBg, statusIcon) = when (job.status) {
        JobStatus.SUCCEEDED -> Quad("已确认发出", semantics.onHealthyContainer, semantics.healthyContainer, Icons.Filled.CheckCircle)
        JobStatus.UNKNOWN_REQUIRES_REVIEW -> Quad("待人工核对", semantics.onDangerContainer, semantics.dangerContainer, Icons.AutoMirrored.Filled.HelpOutline)
        JobStatus.CANCELLED -> Quad("今日已取消", semantics.onNeutralContainer, semantics.neutralContainer, Icons.Filled.Cancel)
        JobStatus.FAILED_RETRYABLE -> Quad("待重试", semantics.onWarningContainer, semantics.warningContainer, Icons.Filled.Refresh)
        JobStatus.FAILED_TERMINAL -> Quad("终态失败", semantics.onDangerContainer, semantics.dangerContainer, Icons.Filled.ErrorOutline)
        JobStatus.LEASED,
        JobStatus.OPENING_CHAT,
        JobStatus.SENDING,
        JobStatus.CONFIRMING,
        -> Quad("执行中", MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer, Icons.Filled.Refresh)
        JobStatus.SCHEDULED -> Quad("已计划", semantics.onNeutralContainer, semantics.neutralContainer, Icons.Filled.Schedule)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusFg,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = item.targetName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    text = if (item.isDailySlot) "每日" else "单发",
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                )
                StatusPill(
                    text = statusLabel,
                    contentColor = statusFg,
                    containerColor = statusBg,
                )
                Icon(
                    imageVector = if (item.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (item.expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.templateLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isImageMissing) {
                            semantics.danger
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.isImageMissing) {
                        StatusPill(
                            text = "待补图",
                            contentColor = semantics.onWarningContainer,
                            containerColor = semantics.warningContainer,
                        )
                    }
                }
                Text(
                    text = formatEpochTimestamp(job.confirmedAt ?: job.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            job.summaryDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (job.status == JobStatus.UNKNOWN_REQUIRES_REVIEW) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    FilledTonalButton(
                        onClick = onOpenUnknownReview,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = semantics.danger,
                            contentColor = Color.White,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("核对发送结果", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            AnimatedVisibility(visible = item.expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (!item.attemptsLoaded) {
                        Text(
                            text = "正在读取尝试明细…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (item.attempts.isEmpty()) {
                        Text(
                            text = "暂无发送尝试明细记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        item.attempts.forEach { attempt ->
                            AttemptDetailRow(attempt = attempt)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttemptDetailRow(attempt: SendAttemptEntity) {
    val semantics = FlameThemeTokens.semanticColors
    val (statusText, statusColor) = when (attempt.status) {
        AttemptStatus.SUCCEEDED -> "成功" to semantics.healthy
        AttemptStatus.FAILED -> "失败" to semantics.danger
        AttemptStatus.RUNNING -> "进行中" to MaterialTheme.colorScheme.primary
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

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "第 ${attempt.attemptNo} 次尝试 · $stageText · $methodText",
                    style = MaterialTheme.typography.labelMedium,
                )
                val failHint = attempt.failureClass?.let { failureClassLabel(it) }
                val timeRange = buildString {
                    append(formatEpochTimeOnly(attempt.startedAt))
                    attempt.finishedAt?.let {
                        append(" - ")
                        append(formatEpochTimeOnly(it))
                    }
                    if (failHint != null) {
                        append(" · ")
                        append(failHint)
                    }
                }
                Text(
                    text = timeRange,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ReloginChoiceDialog(
    onDismiss: () -> Unit,
    onPreserveSession: () -> Unit,
    onClearAndRelogin: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.QrCodeScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("选择扫码 / 重登方式") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "当前已有登录配置，请明确选择保留当前网页会话排查，还是清理 Cookie 后重新扫码换号：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPreserveSession),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "打开当前抖音网页（保留登录态排查）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "不清理 Cookie 与本地缓存，直接打开网页核对聊天列表或页面状态。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClearAndRelogin),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "退出并清理 Cookie 重新扫码（重登或更换抖音号）",
                            style = MaterialTheme.typography.titleSmall,
                            color = FlameThemeTokens.semanticColors.warning,
                        )
                        Text(
                            text = "彻底清除当前 Cookie 与 WebStorage 后展示全新二维码。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun UnknownReviewDialog(
    item: UnknownJobReviewItem,
    isResolving: Boolean,
    onConfirmSucceeded: () -> Unit,
    onConfirmCancelled: () -> Unit,
    onDismiss: () -> Unit,
) {
    val semantics = FlameThemeTokens.semanticColors
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = null,
                tint = semantics.danger,
            )
        },
        title = { Text("人工核对未知发送结果") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "好友：${item.targetName}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "日期：${item.job.businessDate} · ${if (item.isDailySlot) "今日自动/批量续火" else "单发测试"} · ${item.templateLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        item.job.summaryDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                            Text(
                                text = "原因：$detail",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Text(
                    text = "系统已触发过发送动作但未能从页面确认回执，为防重复发消息，严禁自动重发。请先打开手机抖音查看聊天记录，再从以下两项中终裁：",
                    style = MaterialTheme.typography.bodySmall,
                )

                Button(
                    onClick = onConfirmSucceeded,
                    enabled = !isResolving,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = semantics.healthy,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("我在抖音里看到发出了（标为已成功）")
                }

                OutlinedButton(
                    onClick = onConfirmCancelled,
                    enabled = !isResolving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("确认没发出（标为今日取消，不再自动发）")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isResolving) {
                Text("暂不处理")
            }
        },
    )
}

@Composable
private fun CooldownDetailDialog(
    account: AccountEntity,
    stats: List<SanitizedFailureStat>,
    isResolving: Boolean,
    onRecover: () -> Unit,
    onDismiss: () -> Unit,
) {
    val semantics = FlameThemeTokens.semanticColors
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = semantics.warning,
            )
        },
        title = { Text("连续失败熔断详情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "最近已连续 ${account.failureStreak} 轮自动发送全员终态失败，系统为避免持续异常发信已暂停自动调度。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (stats.isNotEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "近期脱敏失败分类统计：",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            stats.forEach { stat ->
                                Text(
                                    text = "• ${failureClassLabel(stat.failureClass)}：${stat.count} 次",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "请核查好友是否同名冲突、绑定模板是否可用或网页是否正常，确认后点击下方按钮解除熔断。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onRecover,
                enabled = !isResolving,
            ) {
                Text("解除熔断并恢复运行")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isResolving) {
                Text("稍后")
            }
        },
    )
}

@Composable
private fun ManualBatchFeedbackDialog(
    feedback: ManualBatchFeedback,
    onDismiss: () -> Unit,
    onOpenUnknownReview: () -> Unit,
    onOpenLogin: () -> Unit,
    onRecoverCooldown: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTargets: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (feedback.reason == ManualBatchBlockReason.UNKNOWN_OR_INFLIGHT) {
                    Icons.Filled.VerifiedUser
                } else {
                    Icons.Filled.WarningAmber
                },
                contentDescription = null,
                tint = if (feedback.reason == ManualBatchBlockReason.UNKNOWN_OR_INFLIGHT) {
                    FlameThemeTokens.semanticColors.danger
                } else {
                    FlameThemeTokens.semanticColors.warning
                },
            )
        },
        title = { Text(feedback.title) },
        text = { Text(feedback.message) },
        confirmButton = {
            when {
                feedback.canJumpToUnknownReview -> Button(onClick = onOpenUnknownReview) {
                    Text("立即去核对")
                }

                feedback.canJumpToLogin -> Button(onClick = onOpenLogin) {
                    Text("前往登录 / 验证")
                }

                feedback.canRecoverCooldown -> Button(onClick = onRecoverCooldown) {
                    Text("解除熔断并恢复")
                }

                feedback.canJumpToSettings -> Button(onClick = onOpenSettings) {
                    Text("前往设置")
                }

                feedback.canJumpToTargets -> Button(onClick = onOpenTargets) {
                    Text("前往好友页")
                }

                else -> Button(onClick = onDismiss) {
                    Text("知道了")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun StatusPill(
    text: String,
    contentColor: Color,
    containerColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private data class Quad<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

private fun formatScheduleSummary(state: TodayUiState): String {
    val account = state.account ?: return "尚未初始化账号配置"
    if (!account.enabled || account.status == AccountStatus.PAUSED) {
        return "每日自动定时已暂停（可在设置页重新开启）"
    }
    if (state.authRecheckAtEpochMs != null && account.authFailCount == 1) {
        return "登录态待复判：预计 ${formatWakeTime(state.authRecheckAtEpochMs, account.timezone)} 自动复检"
    }
    if (account.status != AccountStatus.HEALTHY) {
        return "自动调度已暂挂（当前状态：${accountStatusLabel(account.status)}）"
    }
    val nextMs = state.nextAutoTriggerAtEpochMs
        ?: return "今日暂无后续排班（窗口 ${account.sendWindowStart}–${account.sendWindowEnd}）"
    return "下次自动时间：${formatWakeTime(nextMs, account.timezone)}（已开启每日定时）"
}

private fun formatWakeTime(epochMs: Long, timezone: String): String {
    val zoneId = DailySchedulePlanner.resolveZoneId(timezone)
    val target = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zoneId)
    val today = LocalDate.now(zoneId)
    val timeStr = TIME_FORMATTER.format(target)
    return when (target.toLocalDate()) {
        today -> "今天 $timeStr"
        today.plusDays(1) -> "明天 $timeStr"
        else -> "${DATE_TIME_FORMATTER.format(target)}"
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
