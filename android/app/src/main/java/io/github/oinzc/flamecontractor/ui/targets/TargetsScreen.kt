package io.github.oinzc.flamecontractor.ui.targets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.scheduler.ScheduleCoordinator

@Composable
fun TargetsRoute(
    database: FlameDatabase,
    scheduleCoordinator: ScheduleCoordinator,
    onNavigateToTodayReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: TargetsViewModel = viewModel(
        factory = TargetsViewModel.factory(
            context = context,
            database = database,
            scheduleCoordinator = scheduleCoordinator,
        ),
    )
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

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
        TargetEditorDialog(
            editor = editor,
            templates = uiState.templates,
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
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirm,
            title = { Text("移出续火名单？") },
            text = {
                Text(
                    "确定移除「${target.nicknameSnapshot}」？\n\n" +
                        "系统将保留其历史发送审计与连续天数；日后再次扫码或新建同名对象时可自动复活恢复。",
                )
            },
            confirmButton = {
                Button(onClick = viewModel::confirmDeleteTarget) {
                    Text("确认移除")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteConfirm) {
                    Text("取消")
                }
            },
        )
    }

    when (val dlg = uiState.singleSendDialog) {
        SingleSendDialogState.None -> Unit

        is SingleSendDialogState.Blocked -> {
            AlertDialog(
                onDismissRequest = viewModel::dismissSingleSendDialog,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                title = { Text("已拦截单发测试") },
                text = {
                    Text(
                        "「${dlg.targetName}」今日存在未确认或进行中的发送记录。\n\n${dlg.message}",
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.dismissSingleSendDialog()
                            onNavigateToTodayReview()
                        },
                    ) {
                        Text("去「今日」核对")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissSingleSendDialog) {
                        Text("关闭")
                    }
                },
            )
        }

        is SingleSendDialogState.ConfirmAlreadySucceeded -> {
            val canSend = dlg.overrideText.isNotBlank() || dlg.target.templateId != null
            AlertDialog(
                onDismissRequest = viewModel::dismissSingleSendDialog,
                title = { Text("确认额外补发测试消息？") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "「${dlg.target.nicknameSnapshot}」${dlg.message}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "当前绑定模板：${dlg.templateName ?: "未绑定"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = dlg.overrideText,
                            onValueChange = viewModel::updateSingleSendOverrideText,
                            label = { Text("可选临时测试文本") },
                            placeholder = {
                                Text(
                                    if (dlg.target.templateId != null) {
                                        "留空则按绑定模板发送"
                                    } else {
                                        "未绑定模板，请输入临时测试文本"
                                    },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = viewModel::confirmSingleSend,
                        enabled = canSend,
                    ) {
                        Text("确认补发")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissSingleSendDialog) {
                        Text("取消")
                    }
                },
            )
        }

        is SingleSendDialogState.ConfirmSend -> {
            val canSend = dlg.overrideText.isNotBlank() || dlg.target.templateId != null
            AlertDialog(
                onDismissRequest = viewModel::dismissSingleSendDialog,
                title = { Text("单发测试 · ${dlg.target.nicknameSnapshot}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "将触发离屏 WebView 立即发送 1 条测试消息（走独立手动槽位，写入发送审计）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "当前绑定模板：${dlg.templateName ?: "未绑定"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = dlg.overrideText,
                            onValueChange = viewModel::updateSingleSendOverrideText,
                            label = { Text("可选临时测试文本") },
                            placeholder = {
                                Text(
                                    if (dlg.target.templateId != null) {
                                        "留空则按绑定模板发送"
                                    } else {
                                        "未绑定模板，请输入临时测试文本"
                                    },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = viewModel::confirmSingleSend,
                        enabled = canSend,
                    ) {
                        Text("立即发送")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissSingleSendDialog) {
                        Text("取消")
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onOpenCreateTarget) {
                Icon(Icons.Default.Add, contentDescription = "手工新增好友或群聊")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 顶部标题与「手动扫描同步」入口
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "好友与群聊",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "共 ${uiState.allTargetsCount} 个续火对象",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (uiState.hasAvailableSnapshot) {
                        OutlinedButton(onClick = onOpenReconcileSheet) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("查看最近扫描结果")
                        }
                    }

                    FilledTonalButton(
                        onClick = onStartManualScan,
                        enabled = !uiState.scanRunning,
                    ) {
                        if (uiState.scanRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("扫描中 (${uiState.scanDiscoveredCount})")
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("手动扫描同步")
                        }
                    }
                }
            }

            if (uiState.scanRunning) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "${uiState.scanTaskLabel ?: "正在扫描抖音会话…"} · 已发现 ${uiState.scanDiscoveredCount} 条",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // 搜索框
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索好友昵称、群名、抖音号或绑定模板…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "清空搜索")
                        }
                    }
                },
            )

            // 过滤胶囊
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(TargetFilter.entries) { filter ->
                    val count = uiState.filterCounts[filter] ?: 0
                    FilterChip(
                        selected = uiState.filter == filter,
                        onClick = { onFilterSelected(filter) },
                        label = { Text("${filter.label} ($count)") },
                    )
                }
            }

            // 自适应手机单列 / 平板双列列表
            if (uiState.items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (uiState.allTargetsCount == 0) {
                                "暂无续火对象"
                            } else {
                                "当前筛选条件下没有匹配对象"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (uiState.allTargetsCount == 0) {
                                "可点击右上角「手动扫描同步」导入抖音火花好友，或点击右下角 + 手工录入"
                            } else {
                                "可尝试切换筛选胶囊或清空搜索关键词"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    val isWide = maxWidth >= 600.dp
                    if (isWide) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 330.dp),
                            contentPadding = PaddingValues(bottom = 80.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(
                                items = uiState.items,
                                key = { it.target.id },
                            ) { item ->
                                TargetCard(
                                    item = item,
                                    onToggleEnabled = { enabled -> onToggleEnabled(item.target, enabled) },
                                    onSingleSend = { onSingleSend(item.target) },
                                    onRepairIdentity = { onRepairIdentity(item.target) },
                                    onEdit = { onOpenEditTarget(item.target) },
                                    onDelete = { onRequestDelete(item.target) },
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 80.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(
                                items = uiState.items,
                                key = { it.target.id },
                            ) { item ->
                                TargetCard(
                                    item = item,
                                    onToggleEnabled = { enabled -> onToggleEnabled(item.target, enabled) },
                                    onSingleSend = { onSingleSend(item.target) },
                                    onRepairIdentity = { onRepairIdentity(item.target) },
                                    onEdit = { onOpenEditTarget(item.target) },
                                    onDelete = { onRequestDelete(item.target) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetCard(
    item: TargetItemUi,
    onToggleEnabled: (Boolean) -> Unit,
    onSingleSend: () -> Unit,
    onRepairIdentity: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val target = item.target
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (target.enabled) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 第一行：显示名 + 好友/群聊角标 + 开关 + 快捷菜单
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = target.nicknameSnapshot,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                text = if (target.conversationType == "group") "群聊" else "好友",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (!target.platformHandle.isNullOrBlank()) {
                        Text(
                            text = "@${target.platformHandle}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Switch(
                    checked = target.enabled,
                    onCheckedChange = onToggleEnabled,
                )

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("单发测试") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onSingleSend()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("单独补采身份") },
                            leadingIcon = { Icon(Icons.Default.PersonSearch, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRepairIdentity()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("编辑") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            // 第二行：身份绑定角标 + 独立「抖音官方火花」徽标 + 独立「本机连续天数」
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 1) 身份绑定状态角标
                val (idBg, idFg, idText) = when {
                    item.hasBoundIdentity -> Triple(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                        if (target.conversationType == "group") "已绑群ID" else "已绑稳定身份",
                    )
                    item.isAmbiguousInLatestScan -> Triple(
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer,
                        "同名冲突 · 未绑",
                    )
                    else -> Triple(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        "未绑身份",
                    )
                }
                Surface(color = idBg, shape = MaterialTheme.shapes.small) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (item.hasBoundIdentity) {
                                Icons.Default.VerifiedUser
                            } else {
                                Icons.Default.Badge
                            },
                            contentDescription = null,
                            tint = idFg,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = idText,
                            style = MaterialTheme.typography.labelSmall,
                            color = idFg,
                        )
                    }
                }

                // 2) 独立「抖音官方火花」徽标（绝不与本机连续天数混同）
                val officialBg = if (item.hasOfficialSpark) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
                val officialFg = if (item.hasOfficialSpark) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(color = officialBg, shape = MaterialTheme.shapes.small) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalFireDepartment,
                            contentDescription = null,
                            tint = officialFg,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = formatOfficialSparkBadge(target, item.hasOfficialSpark),
                            style = MaterialTheme.typography.labelSmall,
                            color = officialFg,
                            fontWeight = if (item.hasOfficialSpark) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }

                // 3) 独立「本机连续天数」徽标
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Whatshot,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "本机连续 ${target.streakDays} 天",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                if (item.todayStatusLabel != null) {
                    val statusBg = when {
                        item.hasBlockingTodayJob -> MaterialTheme.colorScheme.errorContainer
                        item.hasSucceededTodayJob -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val statusFg = when {
                        item.hasBlockingTodayJob -> MaterialTheme.colorScheme.onErrorContainer
                        item.hasSucceededTodayJob -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(color = statusBg, shape = MaterialTheme.shapes.small) {
                        Text(
                            text = item.todayStatusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusFg,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            HorizontalDivider()

            // 第三行：绑定模板名 + 固定时刻 + 快捷操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "模板：${item.templateName ?: "未绑定模板"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!target.sendTimeHhmm.isNullOrBlank()) {
                        Text(
                            text = "固定发送时刻：${target.sendTimeHhmm}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!item.hasBoundIdentity) {
                        TextButton(onClick = onRepairIdentity) {
                            Text("补采身份")
                        }
                    }
                    OutlinedButton(onClick = onSingleSend) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("单发测试")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetEditorDialog(
    editor: TargetEditorState,
    templates: List<MessageTemplateEntity>,
    busy: Boolean,
    onNicknameChanged: (String) -> Unit,
    onTypeChanged: (String) -> Unit,
    onTemplateChanged: (String?) -> Unit,
    onSendTimeChanged: (String) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var templateDropdownExpanded by remember { mutableStateOf(false) }
    val selectedTemplateName = remember(editor.templateId, templates) {
        templates.firstOrNull { it.id == editor.templateId }?.name
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (editor.isNew) "手工新增续火对象" else "编辑续火对象")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (editor.errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = editor.errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                OutlinedTextField(
                    value = editor.nickname,
                    onValueChange = onNicknameChanged,
                    label = { Text("显示昵称 / 群名") },
                    placeholder = { Text("须与抖音会话标题严格一致") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "会话类型",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = editor.conversationType != "group",
                            onClick = { onTypeChanged("friend") },
                            enabled = editor.typeLockedReason == null,
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) {
                            Text("好友")
                        }
                        SegmentedButton(
                            selected = editor.conversationType == "group",
                            onClick = { onTypeChanged("group") },
                            enabled = editor.typeLockedReason == null,
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) {
                            Text("群聊")
                        }
                    }
                    if (editor.typeLockedReason != null) {
                        Text(
                            text = editor.typeLockedReason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = templateDropdownExpanded,
                    onExpandedChange = { templateDropdownExpanded = !templateDropdownExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = selectedTemplateName ?: "暂不绑定模板",
                        onValueChange = {},
                        readOnly = true,
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
                            DropdownMenuItem(
                                text = { Text(tpl.name) },
                                onClick = {
                                    onTemplateChanged(tpl.id)
                                    templateDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = editor.sendTimeHhmm,
                    onValueChange = onSendTimeChanged,
                    label = { Text("可选固定时刻（HH:mm）") },
                    placeholder = { Text("留空则沿用账号发送窗口，例如 20:30") },
                    supportingText = { Text("填写后优先于账号全局窗口，享有 60 分钟同日宽限窗") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "启用自动续火",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = editor.enabled,
                        onCheckedChange = onEnabledChanged,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !busy,
            ) {
                Text(if (editor.isNew) "保存新增" else "保存修改")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
            ) {
                Text("取消")
            }
        },
    )
}

private fun formatOfficialSparkBadge(
    target: ContractTargetEntity,
    hasOfficialSpark: Boolean,
): String {
    if (!hasOfficialSpark) {
        return "抖音官方火花: 无"
    }
    val kindPrefix = when (target.sparkKind) {
        "couple" -> "合养火花"
        "family" -> "家庭火花"
        else -> "抖音官方火花"
    }
    val days = target.sparkDays
    val label = target.sparkLabel?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        days != null && days > 0 -> "$kindPrefix ${days}天"
        label != null -> "$kindPrefix · $label"
        else -> kindPrefix
    }
}
