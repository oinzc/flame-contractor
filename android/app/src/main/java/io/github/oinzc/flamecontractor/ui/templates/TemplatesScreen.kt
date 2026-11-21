package io.github.oinzc.flamecontractor.ui.templates

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.TemplatePoolItemEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType

@Composable
fun TemplatesRoute(
    database: FlameDatabase,
    modifier: Modifier = Modifier,
) {
    val viewModel: TemplatesViewModel = viewModel(
        factory = TemplatesViewModel.factory(database),
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

    TemplatesScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onSelectPreviewTarget = viewModel::selectPreviewTarget,
        onOpenCreate = viewModel::openCreateTemplate,
        onOpenEdit = viewModel::openEditTemplate,
        onToggleTemplateEnabled = viewModel::toggleTemplateEnabled,
        onRequestDelete = viewModel::requestDeleteTemplate,
        onTogglePoolExpanded = viewModel::togglePoolExpanded,
        onInlineInputChanged = viewModel::updateInlinePoolInput,
        onToggleInlineWeekday = viewModel::toggleInlinePoolWeekday,
        onSetInlineEveryday = viewModel::setInlinePoolEveryday,
        onAddInlinePoolItem = viewModel::addInlinePoolItem,
        onOpenEditPoolItem = viewModel::openEditPoolItemDialog,
        onTogglePoolItemEnabled = viewModel::togglePoolItemEnabled,
        onTogglePoolItemWeekday = viewModel::togglePoolItemWeekday,
        onSetPoolItemEveryday = viewModel::setPoolItemEveryday,
        onDeletePoolItem = viewModel::deletePoolItem,
        modifier = modifier,
    )

    uiState.editorState?.let { editor ->
        TemplateEditorDialog(
            editor = editor,
            previewTarget = uiState.sampleTargets.firstOrNull { it.id == uiState.selectedPreviewTargetId }
                ?: uiState.sampleTargets.firstOrNull(),
            busy = uiState.isBusy,
            onNameChanged = viewModel::updateEditorName,
            onTypeChanged = viewModel::updateEditorType,
            onContentChanged = viewModel::updateEditorContent,
            onStickerNameChanged = viewModel::updateEditorStickerName,
            onStickerLabelChanged = viewModel::updateEditorStickerLabel,
            onStickerCategoryChanged = viewModel::updateEditorStickerCategory,
            onStickerFallbackIndexChanged = viewModel::updateEditorStickerFallbackIndex,
            onEnabledChanged = viewModel::updateEditorEnabled,
            onAddPoolDraft = viewModel::addEditorPoolDraftRow,
            onRemovePoolDraft = viewModel::removeEditorPoolDraftRow,
            onPoolDraftContentChanged = viewModel::updateEditorPoolDraftContent,
            onTogglePoolDraftWeekday = viewModel::toggleEditorPoolDraftWeekday,
            onSetPoolDraftEveryday = viewModel::setEditorPoolDraftEveryday,
            onTogglePoolDraftEnabled = viewModel::toggleEditorPoolDraftEnabled,
            onSave = viewModel::saveTemplateEditor,
            onDismiss = viewModel::dismissTemplateEditor,
        )
    }

    uiState.poolItemEditDialog?.let { dlg ->
        PoolItemEditDialog(
            dialogState = dlg,
            onContentChanged = viewModel::updatePoolItemEditContent,
            onToggleWeekday = viewModel::togglePoolItemEditWeekday,
            onSetEveryday = viewModel::setPoolItemEditEveryday,
            onEnabledChanged = viewModel::updatePoolItemEditEnabled,
            onSave = viewModel::savePoolItemEditDialog,
            onDismiss = viewModel::dismissPoolItemEditDialog,
        )
    }

    uiState.deleteConfirmState?.let { confirm ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirm,
            title = { Text("删除模板「${confirm.template.name}」？") },
            text = {
                Text(
                    if (confirm.activeReferenceCount > 0) {
                        "当前有 ${confirm.activeReferenceCount} 位活跃好友正在使用此模板。\n\n" +
                            "删除后这些好友将转为「未绑定模板」状态（需重新绑定后才可自动发送），确定删除吗？"
                    } else {
                        "删除后不可恢复，确定要删除此模板吗？"
                    },
                )
            },
            confirmButton = {
                Button(onClick = viewModel::confirmDeleteTemplate) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteConfirm) {
                    Text("取消")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TemplatesScreen(
    uiState: TemplatesUiState,
    snackbarHostState: SnackbarHostState,
    onSelectPreviewTarget: (String?) -> Unit,
    onOpenCreate: (TemplateType) -> Unit,
    onOpenEdit: (MessageTemplateEntity) -> Unit,
    onToggleTemplateEnabled: (MessageTemplateEntity, Boolean) -> Unit,
    onRequestDelete: (MessageTemplateEntity) -> Unit,
    onTogglePoolExpanded: (String) -> Unit,
    onInlineInputChanged: (String, String) -> Unit,
    onToggleInlineWeekday: (String, Int) -> Unit,
    onSetInlineEveryday: (String) -> Unit,
    onAddInlinePoolItem: (String) -> Unit,
    onOpenEditPoolItem: (TemplatePoolItemEntity) -> Unit,
    onTogglePoolItemEnabled: (String, String, Boolean) -> Unit,
    onTogglePoolItemWeekday: (String, String, Int) -> Unit,
    onSetPoolItemEveryday: (String, String) -> Unit,
    onDeletePoolItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewTarget = remember(uiState.selectedPreviewTargetId, uiState.sampleTargets) {
        uiState.sampleTargets.firstOrNull { it.id == uiState.selectedPreviewTargetId }
            ?: uiState.sampleTargets.firstOrNull()
    }
    var previewMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenCreate(TemplateType.TEXT) }) {
                Icon(Icons.Default.Add, contentDescription = "新建消息模板")
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "模板与文案池",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "固定文本 · 星期轮换文案池 · 抖音原生表情",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(onClick = { onOpenCreate(TemplateType.POOL) }) {
                        Text("新建文案池")
                    }
                    FilledTonalButton(onClick = { onOpenCreate(TemplateType.STICKER) }) {
                        Text("新建表情")
                    }
                }
            }

            if (uiState.sampleTargets.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = previewMenuExpanded,
                    onExpandedChange = { previewMenuExpanded = !previewMenuExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = previewTarget?.let {
                            "${it.nicknameSnapshot}（本机连续 ${it.streakDays} 天）"
                        } ?: "默认测试好友",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("变量预览代入好友（仅只读预览，不改变轮换顺位）") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = previewMenuExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = previewMenuExpanded,
                        onDismissRequest = { previewMenuExpanded = false },
                    ) {
                        uiState.sampleTargets.forEach { t ->
                            DropdownMenuItem(
                                text = { Text("${t.nicknameSnapshot}（${t.streakDays}天）") },
                                onClick = {
                                    onSelectPreviewTarget(t.id)
                                    previewMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 84.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 分组 1：TEXT
                item(key = "section-text") {
                    SectionHeader(
                        icon = Icons.Default.TextFields,
                        title = "固定文本模板 (${uiState.textTemplates.size})",
                        onAdd = { onOpenCreate(TemplateType.TEXT) },
                    )
                }
                if (uiState.textTemplates.isEmpty()) {
                    item(key = "empty-text") {
                        EmptyGroupHint("暂无固定文本模板")
                    }
                } else {
                    items(
                        items = uiState.textTemplates,
                        key = { it.template.id },
                    ) { card ->
                        TextTemplateCard(
                            card = card,
                            previewTarget = previewTarget,
                            onToggleEnabled = { onToggleTemplateEnabled(card.template, it) },
                            onEdit = { onOpenEdit(card.template) },
                            onDelete = { onRequestDelete(card.template) },
                        )
                    }
                }

                // 分组 2：POOL
                item(key = "section-pool") {
                    SectionHeader(
                        icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                        title = "星期轮换文案池 (${uiState.poolTemplates.size})",
                        onAdd = { onOpenCreate(TemplateType.POOL) },
                    )
                }
                if (uiState.poolTemplates.isEmpty()) {
                    item(key = "empty-pool") {
                        EmptyGroupHint("暂无文案池模板，可点击「新建文案池」按星期与 LRU 轮换发送")
                    }
                } else {
                    items(
                        items = uiState.poolTemplates,
                        key = { it.template.id },
                    ) { card ->
                        PoolTemplateCard(
                            card = card,
                            onToggleExpanded = { onTogglePoolExpanded(card.template.id) },
                            onToggleEnabled = { onToggleTemplateEnabled(card.template, it) },
                            onEditTemplate = { onOpenEdit(card.template) },
                            onDeleteTemplate = { onRequestDelete(card.template) },
                            onInlineInputChanged = { onInlineInputChanged(card.template.id, it) },
                            onToggleInlineWeekday = { onToggleInlineWeekday(card.template.id, it) },
                            onSetInlineEveryday = { onSetInlineEveryday(card.template.id) },
                            onAddInlinePoolItem = { onAddInlinePoolItem(card.template.id) },
                            onEditPoolItem = onOpenEditPoolItem,
                            onTogglePoolItemEnabled = { itemId, enabled ->
                                onTogglePoolItemEnabled(card.template.id, itemId, enabled)
                            },
                            onTogglePoolItemWeekday = { itemId, dayIdx ->
                                onTogglePoolItemWeekday(card.template.id, itemId, dayIdx)
                            },
                            onSetPoolItemEveryday = { itemId ->
                                onSetPoolItemEveryday(card.template.id, itemId)
                            },
                            onDeletePoolItem = onDeletePoolItem,
                        )
                    }
                }

                // 分组 3：STICKER
                item(key = "section-sticker") {
                    SectionHeader(
                        icon = Icons.Default.EmojiEmotions,
                        title = "抖音原生表情模板 (${uiState.stickerTemplates.size})",
                        onAdd = { onOpenCreate(TemplateType.STICKER) },
                    )
                }
                if (uiState.stickerTemplates.isEmpty()) {
                    item(key = "empty-sticker") {
                        EmptyGroupHint("暂无原生表情模板")
                    }
                } else {
                    items(
                        items = uiState.stickerTemplates,
                        key = { it.template.id },
                    ) { card ->
                        StickerTemplateCard(
                            card = card,
                            onToggleEnabled = { onToggleTemplateEnabled(card.template, it) },
                            onEdit = { onOpenEdit(card.template) },
                            onDelete = { onRequestDelete(card.template) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        TextButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("添加")
        }
    }
}

@Composable
private fun EmptyGroupHint(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun TextTemplateCard(
    card: TemplateCardUi,
    previewTarget: ContractTargetEntity?,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val tpl = card.template
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TemplateCardHeader(
                name = tpl.name,
                badgeText = "TEXT · 已绑 ${card.activeReferenceCount} 人",
                enabled = tpl.enabled,
                onToggleEnabled = onToggleEnabled,
                onEdit = onEdit,
                onDelete = onDelete,
            )
            Text(
                text = tpl.content,
                style = MaterialTheme.typography.bodyMedium,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "预览：${TemplatesViewModel.renderTemplatePreview(tpl.content, previewTarget)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StickerTemplateCard(
    card: TemplateCardUi,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val tpl = card.template
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TemplateCardHeader(
                name = tpl.name,
                badgeText = "STICKER · 已绑 ${card.activeReferenceCount} 人",
                enabled = tpl.enabled,
                onToggleEnabled = onToggleEnabled,
                onEdit = onEdit,
                onDelete = onDelete,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "表情名：${tpl.stickerName ?: TemplatesViewModel.DEFAULT_STICKER_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                if (!tpl.stickerLabel.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "展示标签：${tpl.stickerLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                if (!tpl.stickerCategory.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "分类：${tpl.stickerCategory}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                if (tpl.stickerFallbackIndex != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "兜底索引：#${tpl.stickerFallbackIndex}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PoolTemplateCard(
    card: TemplateCardUi,
    onToggleExpanded: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEditTemplate: () -> Unit,
    onDeleteTemplate: () -> Unit,
    onInlineInputChanged: (String) -> Unit,
    onToggleInlineWeekday: (Int) -> Unit,
    onSetInlineEveryday: () -> Unit,
    onAddInlinePoolItem: () -> Unit,
    onEditPoolItem: (TemplatePoolItemEntity) -> Unit,
    onTogglePoolItemEnabled: (String, Boolean) -> Unit,
    onTogglePoolItemWeekday: (String, Int) -> Unit,
    onSetPoolItemEveryday: (String) -> Unit,
    onDeletePoolItem: (String) -> Unit,
) {
    val tpl = card.template
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tpl.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "POOL · ${card.poolItems.size} 条候选 · 已绑 ${card.activeReferenceCount} 人",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = tpl.enabled,
                    onCheckedChange = onToggleEnabled,
                )
                IconButton(onClick = onEditTemplate) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑文案池模板")
                }
                IconButton(onClick = onDeleteTemplate) {
                    Icon(Icons.Default.Delete, contentDescription = "删除文案池模板")
                }
                IconButton(onClick = onToggleExpanded) {
                    Icon(
                        imageVector = if (card.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (card.expanded) "收起文案池" else "展开文案池",
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (card.nextPoolPreviewText != null) {
                        "今日下一句轮换预览：${card.nextPoolPreviewText}"
                    } else {
                        "今日暂无可用的启用候选句子"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            if (card.expanded) {
                HorizontalDivider()

                if (card.poolItems.isEmpty()) {
                    Text(
                        text = "该文案池暂无候选句子，请在下方直接添加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        card.poolItems.forEach { poolUi ->
                            PoolItemCardRow(
                                poolUi = poolUi,
                                onEdit = { onEditPoolItem(poolUi.item) },
                                onToggleEnabled = { onTogglePoolItemEnabled(poolUi.item.id, it) },
                                onToggleWeekday = { onTogglePoolItemWeekday(poolUi.item.id, it) },
                                onSetEveryday = { onSetPoolItemEveryday(poolUi.item.id) },
                                onDelete = { onDeletePoolItem(poolUi.item.id) },
                            )
                        }
                    }
                }

                HorizontalDivider()

                // 展开后直接内联添加新句子
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "快速新增候选句子",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (card.inlineInput.errorMessage != null) {
                        Text(
                            text = card.inlineInput.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedTextField(
                        value = card.inlineInput.content,
                        onValueChange = onInlineInputChanged,
                        placeholder = { Text("输入候选文本（支持 {{target_name}}、{{streak_days}} 等变量）") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TemplatesViewModel.TEMPLATE_VARIABLES.forEach { (token, desc) ->
                            AssistChip(
                                onClick = {
                                    onInlineInputChanged(card.inlineInput.content + token)
                                },
                                label = { Text("$desc $token", style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    WeekdayMaskSelector(
                        weekdayMask = card.inlineInput.weekdayMask,
                        onSelectEveryday = onSetInlineEveryday,
                        onToggleDay = onToggleInlineWeekday,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        FilledTonalButton(onClick = onAddInlinePoolItem) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("添加至文案池")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PoolItemCardRow(
    poolUi: PoolItemUi,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleWeekday: (Int) -> Unit,
    onSetEveryday: () -> Unit,
    onDelete: () -> Unit,
) {
    val item = poolUi.item
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = item.enabled,
                    onCheckedChange = onToggleEnabled,
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑候选句子")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除候选句子")
                }
            }

            WeekdayMaskSelector(
                weekdayMask = item.weekdayMask,
                onSelectEveryday = onSetEveryday,
                onToggleDay = onToggleWeekday,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "生效：${poolUi.weekdaySummary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = poolUi.lastUsedSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TemplateCardHeader(
    name: String,
    badgeText: String,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggleEnabled,
        )
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "编辑模板")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "删除模板")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayMaskSelector(
    weekdayMask: Int,
    onSelectEveryday: () -> Unit,
    onToggleDay: (Int) -> Unit,
) {
    val isEveryday = TemplatesViewModel.isEverydayMask(weekdayMask)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = isEveryday,
            onClick = onSelectEveryday,
            label = { Text("每天", style = MaterialTheme.typography.labelSmall) },
        )
        TemplatesViewModel.WEEKDAY_SHORT_LABELS.forEachIndexed { dayIndex, shortLabel ->
            val checked = TemplatesViewModel.isSpecificDayChecked(weekdayMask, dayIndex)
            FilterChip(
                selected = checked,
                onClick = { onToggleDay(dayIndex) },
                label = { Text(shortLabel, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateEditorDialog(
    editor: TemplateEditorState,
    previewTarget: ContractTargetEntity?,
    busy: Boolean,
    onNameChanged: (String) -> Unit,
    onTypeChanged: (TemplateType) -> Unit,
    onContentChanged: (String) -> Unit,
    onStickerNameChanged: (String) -> Unit,
    onStickerLabelChanged: (String) -> Unit,
    onStickerCategoryChanged: (String) -> Unit,
    onStickerFallbackIndexChanged: (String) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onAddPoolDraft: () -> Unit,
    onRemovePoolDraft: (Int) -> Unit,
    onPoolDraftContentChanged: (Int, String) -> Unit,
    onTogglePoolDraftWeekday: (Int, Int) -> Unit,
    onSetPoolDraftEveryday: (Int) -> Unit,
    onTogglePoolDraftEnabled: (Int, Boolean) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (editor.isNew) "新建消息模板" else "编辑模板 · ${editor.name}")
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
                        value = editor.name,
                        onValueChange = onNameChanged,
                        label = { Text("模板名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "模板类型",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val types = listOf(
                            TemplateType.TEXT to "固定文本",
                            TemplateType.POOL to "文案池",
                            TemplateType.STICKER to "原生表情",
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            types.forEachIndexed { idx, (type, label) ->
                                SegmentedButton(
                                    selected = editor.type == type,
                                    onClick = { onTypeChanged(type) },
                                    enabled = editor.isNew,
                                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = types.size),
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }

                    when (editor.type) {
                        TemplateType.TEXT -> {
                            OutlinedTextField(
                                value = editor.content,
                                onValueChange = onContentChanged,
                                label = { Text("固定文本内容") },
                                minLines = 2,
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                TemplatesViewModel.TEMPLATE_VARIABLES.forEach { (token, desc) ->
                                    AssistChip(
                                        onClick = { onContentChanged(editor.content + token) },
                                        label = { Text("$desc $token", style = MaterialTheme.typography.labelSmall) },
                                    )
                                }
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "实时预览：${TemplatesViewModel.renderTemplatePreview(editor.content, previewTarget)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }

                        TemplateType.STICKER -> {
                            OutlinedTextField(
                                value = editor.stickerName,
                                onValueChange = onStickerNameChanged,
                                label = { Text("抖音原生表情名称") },
                                placeholder = { Text("默认：比心") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = editor.stickerLabel,
                                onValueChange = onStickerLabelChanged,
                                label = { Text("展示标签（stickerLabel，可选）") },
                                placeholder = { Text("留空则沿用表情名称") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = editor.stickerCategory,
                                onValueChange = onStickerCategoryChanged,
                                label = { Text("表情分类（stickerCategory，可选）") },
                                placeholder = { Text("例如：常用") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = editor.stickerFallbackIndexInput,
                                onValueChange = onStickerFallbackIndexChanged,
                                label = { Text("兜底索引（stickerFallbackIndex，可选非负整数）") },
                                placeholder = { Text("例如：0") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        TemplateType.POOL -> {
                            Text(
                                text = "候选句子（支持「每天」或独立多选「周一..周日」；全选 7 天自动保存为每天）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            editor.poolDrafts.forEachIndexed { idx, draft ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            OutlinedTextField(
                                                value = draft.content,
                                                onValueChange = { onPoolDraftContentChanged(idx, it) },
                                                label = { Text("候选 ${idx + 1}") },
                                                modifier = Modifier.weight(1f),
                                                maxLines = 3,
                                            )
                                            if (editor.poolDrafts.size > 1) {
                                                IconButton(onClick = { onRemovePoolDraft(idx) }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "删除候选")
                                                }
                                            }
                                        }
                                        WeekdayMaskSelector(
                                            weekdayMask = draft.weekdayMask,
                                            onSelectEveryday = { onSetPoolDraftEveryday(idx) },
                                            onToggleDay = { day -> onTogglePoolDraftWeekday(idx, day) },
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = TemplatesViewModel.formatLastUsedAt(draft.lastUsedAt),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Switch(
                                                checked = draft.enabled,
                                                onCheckedChange = { onTogglePoolDraftEnabled(idx, it) },
                                            )
                                        }
                                    }
                                }
                            }
                            OutlinedButton(onClick = onAddPoolDraft) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("添加一条候选句子")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("启用此模板", style = MaterialTheme.typography.bodyMedium)
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
                Text(if (editor.isNew) "创建模板" else "保存修改")
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

@Composable
private fun PoolItemEditDialog(
    dialogState: PoolItemEditDialogState,
    onContentChanged: (String) -> Unit,
    onToggleWeekday: (Int) -> Unit,
    onSetEveryday: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑候选句子") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (dialogState.errorMessage != null) {
                    Text(
                        text = dialogState.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = dialogState.content,
                    onValueChange = onContentChanged,
                    label = { Text("句子内容") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                WeekdayMaskSelector(
                    weekdayMask = dialogState.weekdayMask,
                    onSelectEveryday = onSetEveryday,
                    onToggleDay = onToggleWeekday,
                )
                Text(
                    text = "${TemplatesViewModel.formatLastUsedAt(dialogState.lastUsedAt)}（编辑不改变轮换顺位）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("启用此句子", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = dialogState.enabled,
                        onCheckedChange = onEnabledChanged,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
