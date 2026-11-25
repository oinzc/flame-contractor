package io.github.oinzc.flamecontractor.ui.targets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import io.github.oinzc.flamecontractor.core.storage.AndroidTemplateImageStore
import io.github.oinzc.flamecontractor.core.storage.TemplateImageStorage
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.scheduler.ScheduleCoordinator
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameEmptyStateCard
import io.github.oinzc.flamecontractor.ui.components.FlameFeedbackTopButton
import io.github.oinzc.flamecontractor.ui.components.FlameFilterChipItem
import io.github.oinzc.flamecontractor.ui.components.FlameFilterChipRow
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameIconButton
import io.github.oinzc.flamecontractor.ui.components.FlameListRow
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameModalSheet
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.FlameOfficialSparkBadge
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameSearchPill
import io.github.oinzc.flamecontractor.ui.components.FlameSheetActionRow
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.FlameTopBar
import io.github.oinzc.flamecontractor.ui.components.FlameTopPillButton
import io.github.oinzc.flamecontractor.ui.components.flameDockBottomPadding
import io.github.oinzc.flamecontractor.ui.components.officialSparkKindFullLabel
import io.github.oinzc.flamecontractor.ui.components.officialSparkKindShortLabel
import io.github.oinzc.flamecontractor.ui.components.rememberReducedMotion
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TargetsRoute(
    database: FlameDatabase,
    scheduleCoordinator: ScheduleCoordinator,
    onNavigateToTodayReview: () -> Unit,
    onOpenSettings: (SettingsSection?) -> Unit = {},
    onOpenFeedback: () -> Unit = { onOpenSettings(SettingsSection.ABOUT) },
    modifier: Modifier = Modifier,
    accountId: String = DEFAULT_ACCOUNT_ID,
    imageStore: TemplateImageStorage? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val resolvedImageStore = imageStore ?: remember(context) { AndroidTemplateImageStore(context) }
    val viewModel: TargetsViewModel = viewModel(
        key = "targets_$accountId",
        factory = TargetsViewModel.factory(
            context = context,
            database = database,
            scheduleCoordinator = scheduleCoordinator,
            accountId = accountId,
            imageStore = resolvedImageStore,
        ),
    )
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshTemplateStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.feedbackMessage) {
        val message = uiState.feedbackMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearFeedbackMessage()
        }
    }

    TargetsScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onFilterSelected = viewModel::onFilterSelected,
        onStartManualScan = viewModel::startManualScan,
        onOpenReconcileSheet = viewModel::openReconcileSheet,
        onOpenCreateTarget = viewModel::openCreateTargetDialog,
        onOpenEditTarget = viewModel::openEditTargetDialog,
        onToggleEnabled = viewModel::toggleTargetEnabled,
        onSingleSend = viewModel::requestSingleSend,
        onRepairIdentity = viewModel::requestIdentityRepair,
        onRequestDelete = viewModel::requestDeleteTarget,
        onOpenSettings = onOpenSettings,
        onOpenFeedback = onOpenFeedback,
        modifier = modifier,
    )

    if (uiState.isReconcileSheetOpen) {
        ScanReconcileSheet(
            reconcileState = uiState.reconcileState,
            templates = uiState.templates,
            onSearchChanged = viewModel::onReconcileSearchChanged,
            onDefaultTemplateChanged = viewModel::onReconcileDefaultTemplateChanged,
            onToggleCandidate = viewModel::toggleCandidateSelection,
            onSelectAllImportableSparks = viewModel::selectAllImportableSparks,
            onClearSelection = viewModel::clearCandidateSelection,
            onImportSelected = viewModel::importSelectedCandidates,
            onSyncExistingSparks = viewModel::syncExistingOfficialSparks,
            onDismiss = viewModel::dismissReconcileSheet,
        )
    }

    uiState.editorState?.let { editor ->
        TargetEditorSheet(
            editor = editor,
            templates = uiState.templates,
            missingImageTemplateIds = uiState.missingImageTemplateIds,
            busy = uiState.isBusy,
            onNicknameChanged = viewModel::updateEditorNickname,
            onTypeChanged = viewModel::updateEditorType,
            onTemplateChanged = viewModel::updateEditorTemplate,
            onSendTimeChanged = viewModel::updateEditorSendTime,
            onEnabledChanged = viewModel::updateEditorEnabled,
            onSave = viewModel::saveTargetEditor,
            onDismiss = viewModel::dismissTargetEditor,
        )
    }

    uiState.deleteConfirmTarget?.let { target ->
        DeleteTargetConfirmSheet(
            target = target,
            busy = uiState.isBusy,
            onConfirm = viewModel::confirmDeleteTarget,
            onDismiss = viewModel::dismissDeleteConfirm,
        )
    }

    when (val dlg = uiState.singleSendDialog) {
        SingleSendDialogState.None -> Unit

        is SingleSendDialogState.Blocked -> {
            SingleSendBlockedSheet(
                state = dlg,
                onNavigateToTodayReview = {
                    viewModel.dismissSingleSendDialog()
                    onNavigateToTodayReview()
                },
                onDismiss = viewModel::dismissSingleSendDialog,
            )
        }

        is SingleSendDialogState.ConfirmAlreadySucceeded -> {
            SingleSendConfirmSheet(
                target = dlg.target,
                templateName = dlg.templateName,
                templateType = dlg.templateType,
                isTemplateMissingImage = dlg.isTemplateMissingImage,
                overrideText = dlg.overrideText,
                isAlreadySucceeded = true,
                leadMessage = "「${dlg.target.nicknameSnapshot}」${dlg.message}",
                onOverrideTextChanged = viewModel::updateSingleSendOverrideText,
                onConfirm = viewModel::confirmSingleSend,
                onDismiss = viewModel::dismissSingleSendDialog,
            )
        }

        is SingleSendDialogState.ConfirmSend -> {
            SingleSendConfirmSheet(
                target = dlg.target,
                templateName = dlg.templateName,
                templateType = dlg.templateType,
                isTemplateMissingImage = dlg.isTemplateMissingImage,
                overrideText = dlg.overrideText,
                isAlreadySucceeded = false,
                leadMessage = "将触发离屏 WebView 立即发送 1 条测试消息（走独立手动槽位，写入发送审计）。",
                onOverrideTextChanged = viewModel::updateSingleSendOverrideText,
                onConfirm = viewModel::confirmSingleSend,
                onDismiss = viewModel::dismissSingleSendDialog,
            )
        }
    }
}

@Composable
fun TargetsScreen(
    uiState: TargetsUiState,
    snackbarHostState: SnackbarHostState,
    onSearchQueryChanged: (String) -> Unit,
    onFilterSelected: (TargetFilter) -> Unit,
    onStartManualScan: () -> Unit,
    onOpenReconcileSheet: () -> Unit,
    onOpenCreateTarget: () -> Unit,
    onOpenEditTarget: (ContractTargetEntity) -> Unit,
    onToggleEnabled: (ContractTargetEntity, Boolean) -> Unit,
    onSingleSend: (ContractTargetEntity) -> Unit,
    onRepairIdentity: (ContractTargetEntity) -> Unit,
    onRequestDelete: (ContractTargetEntity) -> Unit,
    onOpenSettings: (SettingsSection?) -> Unit = {},
    onOpenFeedback: () -> Unit = { onOpenSettings(SettingsSection.ABOUT) },
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val reducedMotion = rememberReducedMotion()
    val dockBottomClearance = flameDockBottomPadding()
    var selectedTargetId by remember { mutableStateOf<String?>(null) }
    var showScanActionSheet by remember { mutableStateOf(false) }

    val filterItems = remember(uiState.filterCounts) {
        TargetFilter.entries.map { filter ->
            FlameFilterChipItem(
                key = filter.name,
                label = filter.label,
                count = uiState.filterCounts[filter] ?: 0,
            )
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(palette.canvas),
    ) {
        val isWideScreen = maxWidth >= 600.dp
        val compactFeedbackButton = maxWidth < 430.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = palette.canvas,
            contentColor = palette.ink,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                FlameTopBar(title = "好友") {
                    FlameTopPillButton(
                        text = if (uiState.scanRunning) {
                            "扫描(${uiState.scanDiscoveredCount})"
                        } else {
                            "扫描对账"
                        },
                        leadingIcon = Icons.Outlined.Sync,
                        primary = false,
                        enabled = !uiState.scanRunning,
                        onClick = {
                            if (uiState.hasAvailableSnapshot) {
                                showScanActionSheet = true
                            } else {
                                onStartManualScan()
                            }
                        },
                    )
                    FlameTopPillButton(
                        text = "新增",
                        leadingIcon = Icons.Default.Add,
                        primary = true,
                        onClick = onOpenCreateTarget,
                    )
                    FlameFeedbackTopButton(
                        onClick = onOpenFeedback,
                        compact = compactFeedbackButton,
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = if (isWideScreen) 20.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FlameSearchPill(
                    query = uiState.searchQuery,
                    onQueryChange = onSearchQueryChanged,
                    placeholder = "搜索好友昵称、群名、抖音号或绑定模板…",
                )

                FlameFilterChipRow(
                    items = filterItems,
                    selectedKey = uiState.filter.name,
                    onSelect = { key ->
                        TargetFilter.entries.firstOrNull { it.name == key }?.let(onFilterSelected)
                    },
                )

                AnimatedVisibility(
                    visible = uiState.scanRunning,
                    enter = if (reducedMotion) {
                        EnterTransition.None
                    } else {
                        fadeIn(tween(180, easing = FlameMotionSpec.easeOut)) +
                            expandVertically(tween(200, easing = FlameMotionSpec.easeOut))
                    },
                    exit = if (reducedMotion) {
                        ExitTransition.None
                    } else {
                        fadeOut(tween(150, easing = FlameMotionSpec.easeOut)) +
                            shrinkVertically(tween(180, easing = FlameMotionSpec.easeOut))
                    },
                ) {
                    ActiveScanProgressBanner(
                        taskLabel = uiState.scanTaskLabel,
                        discoveredCount = uiState.scanDiscoveredCount,
                    )
                }

                AnimatedVisibility(
                    visible = uiState.hasAvailableSnapshot && !uiState.scanRunning,
                    enter = if (reducedMotion) {
                        EnterTransition.None
                    } else {
                        fadeIn(tween(180, easing = FlameMotionSpec.easeOut)) +
                            expandVertically(tween(200, easing = FlameMotionSpec.easeOut))
                    },
                    exit = if (reducedMotion) {
                        ExitTransition.None
                    } else {
                        fadeOut(tween(150, easing = FlameMotionSpec.easeOut)) +
                            shrinkVertically(tween(180, easing = FlameMotionSpec.easeOut))
                    },
                ) {
                    RecentScanReconcileShortcut(
                        reconcileState = uiState.reconcileState,
                        onOpenReconcile = onOpenReconcileSheet,
                    )
                }

                if (uiState.items.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(
                                top = 12.dp,
                                bottom = if (isWideScreen) 24.dp else dockBottomClearance,
                            ),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        if (uiState.allTargetsCount == 0) {
                            FlameEmptyStateCard(
                                icon = Icons.Outlined.PersonSearch,
                                title = "暂无续火对象",
                                subtitle = "点击右上角「扫描对账」导入抖音火花好友，或点击「新增」手工录入。",
                                actionLabel = "立即扫描导入",
                                onAction = onStartManualScan,
                            )
                        } else {
                            FlameEmptyStateCard(
                                icon = Icons.Outlined.PersonSearch,
                                title = "当前筛选条件下没有匹配对象",
                                subtitle = "可尝试切换上方筛选胶囊或清空搜索关键词。",
                                actionLabel = "重置筛选",
                                onAction = {
                                    onSearchQueryChanged("")
                                    onFilterSelected(TargetFilter.ALL)
                                },
                            )
                        }
                    }
                } else if (isWideScreen) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .navigationBarsPadding(),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = uiState.items,
                            key = { it.target.id },
                        ) { item ->
                            FlameCard(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 20.dp,
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                TargetGritListRow(
                                    item = item,
                                    showDivider = false,
                                    onClick = { selectedTargetId = item.target.id },
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(top = 2.dp, bottom = dockBottomClearance),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "targets_grouped_list") {
                            FlameGroupedSection(
                                title = "续火名单",
                                trailingHeader = {
                                    Text(
                                        text = if (uiState.items.size == uiState.allTargetsCount) {
                                            "共 ${uiState.allTargetsCount} 位 · 点行操作"
                                        } else {
                                            "显示 ${uiState.items.size} / ${uiState.allTargetsCount} 位"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.inkTertiary,
                                    )
                                },
                            ) {
                                uiState.items.forEachIndexed { index, item ->
                                    TargetGritListRow(
                                        item = item,
                                        showDivider = index < uiState.items.lastIndex,
                                        onClick = { selectedTargetId = item.target.id },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showScanActionSheet) {
        ScanActionChoiceSheet(
            reconcileState = uiState.reconcileState,
            onRescanNow = {
                showScanActionSheet = false
                onStartManualScan()
            },
            onOpenLastReconcile = {
                showScanActionSheet = false
                onOpenReconcileSheet()
            },
            onDismiss = { showScanActionSheet = false },
        )
    }

    val selectedItem = selectedTargetId?.let { id ->
        uiState.items.firstOrNull { it.target.id == id }
    }
    if (selectedItem != null) {
        TargetActionDetailSheet(
            item = selectedItem,
            onToggleEnabled = { enabled -> onToggleEnabled(selectedItem.target, enabled) },
            onSingleSend = {
                selectedTargetId = null
                onSingleSend(selectedItem.target)
            },
            onEdit = {
                selectedTargetId = null
                onOpenEditTarget(selectedItem.target)
            },
            onRepairIdentity = {
                selectedTargetId = null
                onRepairIdentity(selectedItem.target)
            },
            onDelete = {
                selectedTargetId = null
                onRequestDelete(selectedItem.target)
            },
            onDismiss = { selectedTargetId = null },
        )
    }
}

@Composable
private fun ActiveScanProgressBanner(
    taskLabel: String?,
    discoveredCount: Int,
) {
    val palette = FlameThemeTokens.palette
    FlameCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        containerColor = palette.surfaceSubtle,
        borderColor = palette.hairline,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = taskLabel?.takeIf { it.isNotBlank() } ?: "正在扫描抖音会话…",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "已发现 $discoveredCount 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.inkSecondary,
                )
            }
            LinearProgressIndicator(
                color = palette.monoCtaContainer,
                trackColor = palette.hairline,
                strokeCap = StrokeCap.Round,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }
    }
}

@Composable
private fun RecentScanReconcileShortcut(
    reconcileState: ScanReconcileState,
    onOpenReconcile: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val timeLabel = reconcileState.scannedAtEpochMs?.let(::formatTargetTimestamp)
    val metaText = buildString {
        append("发现 ${reconcileState.discoveredCount} 条 · 含火花 ${reconcileState.sparkCount} 条")
        if (timeLabel != null) {
            append(" · ")
            append(timeLabel)
        }
    }

    Surface(
        onClick = onOpenReconcile,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = palette.surface,
        contentColor = palette.ink,
        border = BorderStroke(1.dp, palette.hairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlameRowAvatar(tone = FlameTone.NEUTRAL) {
                Icon(
                    imageVector = Icons.Outlined.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "最近扫描结果可对账",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "查看",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkSecondary,
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

@Composable
private fun TargetGritListRow(
    item: TargetItemUi,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val target = item.target
    val avatarMeta = resolveTargetAvatarMeta(item)

    val subtitleLine1 = buildString {
        append(item.templateName?.let { name ->
            val typeTag = item.templateType?.let { "·${targetTemplateTypeLabel(it)}" }.orEmpty()
            "模板：$name$typeTag"
        } ?: "未绑定模板")
        if (!target.sendTimeHhmm.isNullOrBlank()) {
            append(" · 定时 ")
            append(target.sendTimeHhmm)
        }
    }
    val subtitleLine2 = formatTargetSecondaryLocalSummary(target, item.hasOfficialSpark)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlameRowAvatar(tone = avatarMeta.tone) {
                Icon(
                    imageVector = avatarMeta.icon,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = target.nicknameSnapshot,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (target.enabled) palette.ink else palette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (target.conversationType == "group") {
                        FlameMiniTag(text = "群聊", tone = FlameTone.NEUTRAL)
                    }
                    if (item.isAmbiguousInLatestScan) {
                        FlameMiniTag(text = "同名冲突", tone = FlameTone.DANGER)
                    } else if (!item.hasBoundIdentity) {
                        FlameMiniTag(text = "未绑身份", tone = FlameTone.WARNING)
                    }
                    if (item.isTemplateMissingImage) {
                        FlameMiniTag(text = "待补图", tone = FlameTone.DANGER)
                    }
                }

                Text(
                    text = subtitleLine1,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isTemplateMissingImage) semantics.danger else palette.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = subtitleLine2,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FlameOfficialSparkBadge(
                    sparkDays = target.sparkDays,
                    sparkKind = target.sparkKind,
                    sparkLabel = target.sparkLabel,
                    hasOfficialSpark = item.hasOfficialSpark,
                    litToday = item.officialSparkLitToday,
                    compact = true,
                )
                item.todayStatusLabel?.let { statusText ->
                    val statusColor = when {
                        item.hasBlockingTodayJob -> semantics.danger
                        item.hasSucceededTodayJob || item.officialSparkLitToday == true -> semantics.healthy
                        !target.enabled -> palette.inkTertiary
                        else -> palette.inkSecondary
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 60.dp, end = 14.dp),
                thickness = 1.dp,
                color = palette.hairline,
            )
        }
    }
}

@Composable
private fun ScanActionChoiceSheet(
    reconcileState: ScanReconcileState,
    onRescanNow: () -> Unit,
    onOpenLastReconcile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val timeLabel = reconcileState.scannedAtEpochMs?.let(::formatTargetTimestamp) ?: "最近一次"

    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = "扫描对账",
        subtitle = "已有 $timeLabel 的扫描结果（发现 ${reconcileState.discoveredCount} 条 · 火花 ${reconcileState.sparkCount} 条）",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlameCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                containerColor = palette.surfaceSubtle,
                contentPadding = PaddingValues(14.dp),
                onClick = onOpenLastReconcile,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FlameRowAvatar(tone = FlameTone.NEUTRAL) {
                        Icon(
                            imageVector = Icons.Outlined.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "查看上次扫描对账结果",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.ink,
                        )
                        Text(
                            text = "直接打开已有快照导入新好友或同步官方火花，无需等待重扫。",
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
                containerColor = palette.surfaceSubtle,
                contentPadding = PaddingValues(14.dp),
                onClick = onRescanNow,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FlameRowAvatar(tone = FlameTone.NEUTRAL) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "立即重新扫描同步",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.ink,
                        )
                        Text(
                            text = "离屏打开抖音聊天页重新拉取最新好友列表、身份 ID 与官方火花。",
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

            FlameSheetActionRow(
                text = "取消",
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun TargetActionDetailSheet(
    item: TargetItemUi,
    onToggleEnabled: (Boolean) -> Unit,
    onSingleSend: () -> Unit,
    onEdit: () -> Unit,
    onRepairIdentity: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val target = item.target

    val identitySummary = when {
        item.hasBoundIdentity && target.conversationType == "group" -> "已绑群会话 ID"
        item.hasBoundIdentity -> "已绑稳定身份"
        item.isAmbiguousInLatestScan -> "同名冲突 · 尚未绑定"
        else -> "未绑稳定身份"
    }
    val headerSubtitle = buildString {
        append(if (target.conversationType == "group") "群聊" else "好友")
        if (!target.platformHandle.isNullOrBlank()) {
            append(" · @")
            append(target.platformHandle)
        }
        append(" · ")
        append(identitySummary)
    }

    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = target.nicknameSnapshot,
        subtitle = headerSubtitle,
        trailingHeader = {
            FlameOfficialSparkBadge(
                sparkDays = target.sparkDays,
                sparkKind = target.sparkKind,
                sparkLabel = target.sparkLabel,
                hasOfficialSpark = item.hasOfficialSpark,
                litToday = item.officialSparkLitToday,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 详情快照卡（官方火花快照在先、本机续火记录在后，严格分列展示）
            FlameCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                containerColor = palette.surfaceSubtle,
                contentPadding = PaddingValues(13.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    TargetDetailKeyValueRow(
                        label = "官方火花快照",
                        value = formatOfficialSparkRowSummary(
                            target = target,
                            hasOfficialSpark = item.hasOfficialSpark,
                            officialSparkLitToday = item.officialSparkLitToday,
                        ),
                        valueColor = when {
                            !item.hasOfficialSpark -> palette.ink
                            item.officialSparkLitToday == false -> palette.inkSecondary
                            else -> semantics.sparkGold
                        },
                    )
                    TargetDetailKeyValueRow(
                        label = "本机续火记录",
                        value = buildString {
                            if (target.streakDays > 0) {
                                append("已续 ${target.streakDays} 天")
                            } else {
                                append("暂未发送（0 天）")
                            }
                            target.lastConfirmedAt?.let {
                                append(" · 最近确认 ")
                                append(formatTargetTimestamp(it))
                            }
                        },
                    )
                    val tplDisplay = item.templateName?.let { name ->
                        val typeTag = item.templateType?.let { "（${targetTemplateTypeLabel(it)}）" }.orEmpty()
                        val missingTag = if (item.isTemplateMissingImage) " · 待补图" else ""
                        "$name$typeTag$missingTag"
                    } ?: "未绑定模板"
                    TargetDetailKeyValueRow(
                        label = "绑定模板",
                        value = tplDisplay,
                        valueColor = if (item.isTemplateMissingImage) semantics.danger else palette.ink,
                    )
                    TargetDetailKeyValueRow(
                        label = "发送时刻",
                        value = target.sendTimeHhmm?.takeIf { it.isNotBlank() } ?: "沿用账号全局发送窗口",
                    )
                    TargetDetailKeyValueRow(
                        label = "今日状态",
                        value = item.todayStatusLabel ?: when {
                            !target.enabled -> "已暂停"
                            item.officialSparkLitToday == false -> "灰火待续 · 待自动或手动发送"
                            else -> "待自动或手动发送"
                        },
                        valueColor = when {
                            item.hasBlockingTodayJob -> semantics.danger
                            item.hasSucceededTodayJob || item.officialSparkLitToday == true -> semantics.healthy
                            else -> palette.ink
                        },
                    )
                    Text(
                        text = "官方火花快照仅作参考展示，绝不覆盖本机连续天数。",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkTertiary,
                    )
                }
            }

            // 自动续火总开关
            FlameGroupedSection {
                FlameListRow(
                    title = "自动续火开关",
                    subtitle = if (target.enabled) {
                        "已纳入每日自动与批量续火队列"
                    } else {
                        "已暂停自动续火，保留历史审计与连签天数"
                    },
                    trailing = {
                        Switch(
                            checked = target.enabled,
                            onCheckedChange = onToggleEnabled,
                        )
                    },
                    showDivider = false,
                )
            }

            // 主行动：立即单发测试
            FlameSheetActionRow(
                text = "立即单发测试",
                trailingText = if (item.hasSucceededTodayJob) "今日已发" else "独立手动槽位",
                primary = true,
                onClick = onSingleSend,
            )

            // 次级操作分组
            FlameGroupedSection {
                FlameListRow(
                    title = "编辑好友与模板",
                    subtitle = "修改显示名称、绑定模板与固定发送时刻",
                    leading = {
                        FlameRowAvatar(tone = FlameTone.NEUTRAL) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    },
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = palette.inkTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    dividerIndent = 60.dp,
                    onClick = onEdit,
                )
                FlameListRow(
                    title = "单点身份修复",
                    subtitle = if (item.hasBoundIdentity) {
                        "离屏重新核验平台稳定身份 ID"
                    } else {
                        "离屏补采稳定身份 ID，防止好友改名失联"
                    },
                    leading = {
                        FlameRowAvatar(
                            tone = if (item.hasBoundIdentity) FlameTone.NEUTRAL else FlameTone.WARNING,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PersonSearch,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    },
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = palette.inkTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    dividerIndent = 60.dp,
                    onClick = onRepairIdentity,
                )
                FlameListRow(
                    title = "移出续火列表",
                    subtitle = "软删除保留审计记录，再次扫码可自动复活",
                    leading = {
                        FlameRowAvatar(tone = FlameTone.DANGER) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    },
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = semantics.danger,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    showDivider = false,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun TargetDetailKeyValueRow(
    label: String,
    value: String,
    valueColor: Color = FlameThemeTokens.palette.ink,
) {
    val palette = FlameThemeTokens.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = palette.inkTertiary,
            modifier = Modifier.width(86.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetEditorSheet(
    editor: TargetEditorState,
    templates: List<MessageTemplateEntity>,
    missingImageTemplateIds: Set<String> = emptySet(),
    busy: Boolean,
    onNicknameChanged: (String) -> Unit,
    onTypeChanged: (String) -> Unit,
    onTemplateChanged: (String?) -> Unit,
    onSendTimeChanged: (String) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    var templateDropdownExpanded by remember { mutableStateOf(false) }

    val selectedTemplate = remember(editor.templateId, templates) {
        templates.firstOrNull { it.id == editor.templateId }
    }
    val selectedTemplateLabel = remember(selectedTemplate, missingImageTemplateIds) {
        selectedTemplate?.let { tpl ->
            val missingSuffix = if (tpl.id in missingImageTemplateIds) " · 待补图" else ""
            "${tpl.name}（${targetTemplateTypeLabel(tpl.type)}$missingSuffix）"
        }
    }

    FlameModalSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        title = if (editor.isNew) "手工新增续火对象" else "编辑续火对象",
        subtitle = "昵称或群名须与抖音聊天页标题严格一致",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (editor.errorMessage != null) {
                Surface(
                    color = semantics.dangerContainer,
                    border = BorderStroke(1.dp, semantics.dangerBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = editor.errorMessage,
                        color = semantics.onDangerContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            OutlinedTextField(
                value = editor.nickname,
                onValueChange = onNicknameChanged,
                label = { Text("显示昵称 / 群名") },
                placeholder = { Text("须与抖音会话标题严格一致") },
                singleLine = true,
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "会话类型",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.inkTertiary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val isFriend = editor.conversationType != "group"
                    val isGroup = editor.conversationType == "group"
                    val typeEnabled = editor.typeLockedReason == null && !busy
                    EditorSegmentOption(
                        label = "好友",
                        selected = isFriend,
                        enabled = typeEnabled,
                        onClick = { onTypeChanged("friend") },
                        modifier = Modifier.weight(1f),
                    )
                    EditorSegmentOption(
                        label = "群聊",
                        selected = isGroup,
                        enabled = typeEnabled,
                        onClick = { onTypeChanged("group") },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (editor.typeLockedReason != null) {
                    Text(
                        text = editor.typeLockedReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkTertiary,
                    )
                }
            }

            ExposedDropdownMenuBox(
                expanded = templateDropdownExpanded,
                onExpandedChange = { if (!busy) templateDropdownExpanded = !templateDropdownExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = selectedTemplateLabel ?: "暂不绑定模板",
                    onValueChange = {},
                    readOnly = true,
                    enabled = !busy,
                    shape = RoundedCornerShape(14.dp),
                    label = { Text("绑定发送模板") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = templateDropdownExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = templateDropdownExpanded,
                    onDismissRequest = { templateDropdownExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("暂不绑定模板") },
                        onClick = {
                            onTemplateChanged(null)
                            templateDropdownExpanded = false
                        },
                    )
                    templates.forEach { tpl ->
                        val isMissing = tpl.id in missingImageTemplateIds
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${tpl.name} · ${targetTemplateTypeLabel(tpl.type)}" +
                                        if (isMissing) "（待补图）" else "",
                                    color = if (isMissing) semantics.danger else palette.ink,
                                )
                            },
                            onClick = {
                                onTemplateChanged(tpl.id)
                                templateDropdownExpanded = false
                            },
                        )
                    }
                }
            }

            if (selectedTemplate != null && selectedTemplate.id in missingImageTemplateIds) {
                Surface(
                    color = semantics.dangerContainer,
                    border = BorderStroke(1.dp, semantics.dangerBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "当前所选图片模板缺少本地图片（待补图），请先前往「模板」页重新选择本地图片。",
                        style = MaterialTheme.typography.bodySmall,
                        color = semantics.onDangerContainer,
                        modifier = Modifier.padding(11.dp),
                    )
                }
            }

            OutlinedTextField(
                value = editor.sendTimeHhmm,
                onValueChange = onSendTimeChanged,
                label = { Text("可选固定时刻（HH:mm）") },
                placeholder = { Text("留空则沿用账号发送窗口，例如 20:30") },
                supportingText = { Text("填写后优先于账号全局窗口，享有 60 分钟同日宽限窗") },
                singleLine = true,
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            FlameGroupedSection {
                FlameListRow(
                    title = "启用自动续火",
                    subtitle = "开启后自动参与每日定时与首页续火",
                    trailing = {
                        Switch(
                            checked = editor.enabled,
                            enabled = !busy,
                            onCheckedChange = onEnabledChanged,
                        )
                    },
                    showDivider = false,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlameSheetActionRow(
                text = if (editor.isNew) "保存新增" else "保存修改",
                primary = true,
                enabled = !busy,
                onClick = onSave,
            )
            FlameSheetActionRow(
                text = "取消",
                enabled = !busy,
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun EditorSegmentOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val container = when {
        !enabled && selected -> palette.monoCtaContainer.copy(alpha = 0.4f)
        !enabled -> palette.surfaceSubtle
        selected -> palette.monoCtaContainer
        else -> palette.surfaceSubtle
    }
    val content = when {
        !enabled && selected -> palette.monoCtaContent.copy(alpha = 0.72f)
        !enabled -> palette.inkTertiary
        selected -> palette.monoCtaContent
        else -> palette.inkSecondary
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, if (selected) container else palette.hairline),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun DeleteTargetConfirmSheet(
    target: ContractTargetEntity,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val semantics = FlameThemeTokens.semanticColors
    FlameModalSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        title = "移出续火名单？",
        subtitle = "「${target.nicknameSnapshot}」",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = semantics.dangerContainer,
                border = BorderStroke(1.dp, semantics.dangerBorder),
            ) {
                Text(
                    text = "确定移除「${target.nicknameSnapshot}」？系统将保留其历史发送审计与连续天数；日后再次扫码或新建同名对象时可自动复活恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = semantics.onDangerContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
            FlameSheetActionRow(
                text = "确认移除",
                primary = true,
                danger = true,
                enabled = !busy,
                onClick = onConfirm,
            )
            FlameSheetActionRow(
                text = "取消",
                enabled = !busy,
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun SingleSendBlockedSheet(
    state: SingleSendDialogState.Blocked,
    onNavigateToTodayReview: () -> Unit,
    onDismiss: () -> Unit,
) {
    val semantics = FlameThemeTokens.semanticColors
    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = "已拦截单发测试",
        subtitle = "「${state.targetName}」今日存在未确认或进行中的记录",
        trailingHeader = {
            FlameStatusPill(text = "安全拦截", tone = FlameTone.DANGER)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = semantics.dangerContainer,
                border = BorderStroke(1.dp, semantics.dangerBorder),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = semantics.danger,
                        modifier = Modifier.size(19.dp),
                    )
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = semantics.onDangerContainer,
                    )
                }
            }
            FlameSheetActionRow(
                text = "去「今日」核对",
                primary = true,
                danger = true,
                onClick = onNavigateToTodayReview,
            )
            FlameSheetActionRow(
                text = "关闭",
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun SingleSendConfirmSheet(
    target: ContractTargetEntity,
    templateName: String?,
    templateType: TemplateType?,
    isTemplateMissingImage: Boolean,
    overrideText: String,
    isAlreadySucceeded: Boolean,
    leadMessage: String,
    onOverrideTextChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val missingImageBlocked = isTemplateMissingImage && overrideText.isBlank()
    val canSend = overrideText.isNotBlank() || (target.templateId != null && !isTemplateMissingImage)
    val tplLabel = templateName?.let { name ->
        val typeTag = templateType?.let { "（${targetTemplateTypeLabel(it)}）" }.orEmpty()
        "$name$typeTag"
    } ?: "未绑定模板"

    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = if (isAlreadySucceeded) "确认额外补发测试消息？" else "单发测试 · ${target.nicknameSnapshot}",
        subtitle = "当前绑定模板：$tplLabel",
        trailingHeader = if (isAlreadySucceeded) {
            { FlameStatusPill(text = "今日已发", tone = FlameTone.WARNING) }
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = if (isAlreadySucceeded) semantics.warningContainer else palette.surfaceSubtle,
                border = BorderStroke(
                    1.dp,
                    if (isAlreadySucceeded) semantics.warningBorder else palette.hairline,
                ),
            ) {
                Text(
                    text = leadMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isAlreadySucceeded) semantics.onWarningContainer else palette.inkSecondary,
                    modifier = Modifier.padding(12.dp),
                )
            }

            if (missingImageBlocked) {
                Surface(
                    color = semantics.dangerContainer,
                    border = BorderStroke(1.dp, semantics.dangerBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "该图片模板缺少本地图片（待补图），请先前往「模板」页重新选图，或填写下方临时文本后发送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = semantics.onDangerContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            OutlinedTextField(
                value = overrideText,
                onValueChange = onOverrideTextChanged,
                label = { Text("可选临时测试文本") },
                placeholder = {
                    Text(
                        if (target.templateId != null && !isTemplateMissingImage) {
                            "留空则按绑定模板发送"
                        } else {
                            "请输入临时测试文本以发送"
                        },
                    )
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlameSheetActionRow(
                text = if (isAlreadySucceeded) "确认补发" else "立即发送",
                primary = true,
                enabled = canSend,
                onClick = onConfirm,
            )
            FlameSheetActionRow(
                text = "取消",
                onClick = onDismiss,
            )
        }
    }
}

private data class TargetAvatarMeta(
    val tone: FlameTone,
    val icon: ImageVector,
)

private fun resolveTargetAvatarMeta(item: TargetItemUi): TargetAvatarMeta {
    val target = item.target
    return when {
        item.isTemplateMissingImage && target.enabled ->
            TargetAvatarMeta(FlameTone.DANGER, Icons.Outlined.Image)

        item.isAmbiguousInLatestScan ->
            TargetAvatarMeta(FlameTone.DANGER, Icons.Outlined.WarningAmber)

        !target.enabled ->
            TargetAvatarMeta(FlameTone.NEUTRAL, Icons.Outlined.PauseCircleOutline)

        !item.hasBoundIdentity ->
            TargetAvatarMeta(FlameTone.WARNING, Icons.Outlined.PersonSearch)

        target.conversationType == "group" ->
            TargetAvatarMeta(
                FlameTone.NEUTRAL,
                Icons.Outlined.Groups,
            )

        else ->
            TargetAvatarMeta(
                FlameTone.NEUTRAL,
                Icons.Outlined.Person,
            )
    }
}

private fun targetTemplateTypeLabel(type: TemplateType): String = when (type) {
    TemplateType.TEXT -> "文本"
    TemplateType.POOL -> "文案池"
    TemplateType.STICKER -> "表情"
    TemplateType.IMAGE -> "图片"
}

private fun formatTargetSecondaryLocalSummary(
    target: ContractTargetEntity,
    hasOfficialSpark: Boolean,
): String {
    val kindPrefix = if (hasOfficialSpark) {
        officialSparkKindShortLabel(target.sparkKind)?.let { "${it}火花 · " }.orEmpty()
    } else {
        ""
    }
    val localPart = if (target.streakDays > 0) {
        "本机已续 ${target.streakDays} 天"
    } else {
        "本机暂未发送"
    }
    val scanPart = target.sparkScannedAt?.let { " · ${formatTargetTimestamp(it)} 扫描" }.orEmpty()
    return "$kindPrefix$localPart$scanPart"
}

private fun formatOfficialSparkRowSummary(
    target: ContractTargetEntity,
    hasOfficialSpark: Boolean,
    officialSparkLitToday: Boolean? = null,
): String {
    val sparkPart = if (!hasOfficialSpark) {
        val raw = target.sparkLabel?.trim()?.takeIf { it.isNotEmpty() }
        if (raw != null) "官方快照：$raw" else "官方火花：暂无"
    } else {
        val kindPrefix = officialSparkKindFullLabel(target.sparkKind)
        val days = target.sparkDays
        val label = target.sparkLabel?.trim()?.takeIf { it.isNotEmpty() }
        val base = when {
            days != null && days > 0 -> "$kindPrefix ${days}天"
            label != null -> "$kindPrefix · $label"
            else -> kindPrefix
        }
        val litSuffix = when (officialSparkLitToday) {
            true -> "（今日已点亮）"
            false -> "（灰火 · 今日待续）"
            null -> ""
        }
        "$base$litSuffix"
    }
    val scannedAt = target.sparkScannedAt
    return if (scannedAt != null) {
        "$sparkPart · ${formatTargetTimestamp(scannedAt)} 扫描"
    } else {
        sparkPart
    }
}

private val TARGET_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatTargetTimestamp(epochMs: Long): String {
    val zdt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return TARGET_TIME_FORMATTER.format(zdt)
}
