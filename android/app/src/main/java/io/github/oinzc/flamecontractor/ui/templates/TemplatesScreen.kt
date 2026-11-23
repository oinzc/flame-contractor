package io.github.oinzc.flamecontractor.ui.templates

import android.content.ActivityNotFoundException
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import io.github.oinzc.flamecontractor.ui.components.FlameAlertStrip
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameEmptyStateCard
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameIconButton
import io.github.oinzc.flamecontractor.ui.components.FlameListRow
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameModalSheet
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameSheetActionRow
import io.github.oinzc.flamecontractor.ui.components.FlameStreakBadge
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.FlameTopBar
import io.github.oinzc.flamecontractor.ui.components.FlameTopPillButton
import io.github.oinzc.flamecontractor.ui.components.flameDockBottomPadding
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import kotlinx.coroutines.launch

@Composable
fun TemplatesRoute(
    database: FlameDatabase,
    onOpenSettings: (SettingsSection?) -> Unit = {},
    onEditorVisibleChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    accountId: String = DEFAULT_ACCOUNT_ID,
    imageStore: TemplateImageStorage? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val resolvedImageStore = imageStore ?: remember(context) { AndroidTemplateImageStore(context) }
    val viewModel: TemplatesViewModel = viewModel(
        key = "templates_$accountId",
        factory = TemplatesViewModel.factory(
            database = database,
            imageStore = resolvedImageStore,
            accountId = accountId,
        ),
    )
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val latestOnEditorVisibleChanged by rememberUpdatedState(onEditorVisibleChanged)
    val isEditorVisible = uiState.editorState != null

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val mimeHint = context.contentResolver.getType(uri)
            viewModel.importEditorImage(mimeTypeHint = mimeHint) {
                context.contentResolver.openInputStream(uri)
            }
        }
    }

    LaunchedEffect(isEditorVisible) {
        latestOnEditorVisibleChanged(isEditorVisible)
    }

    DisposableEffect(Unit) {
        onDispose {
            latestOnEditorVisibleChanged(false)
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshImageStates()
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

    val previewTarget = remember(uiState.selectedPreviewTargetId, uiState.sampleTargets) {
        uiState.sampleTargets.firstOrNull { it.id == uiState.selectedPreviewTargetId }
            ?: uiState.sampleTargets.firstOrNull()
    }

    val activeEditor = uiState.editorState
    if (activeEditor != null) {
        val editingTemplateEntity = remember(activeEditor.editingTemplateId, uiState) {
            val editId = activeEditor.editingTemplateId ?: return@remember null
            (uiState.poolTemplates + uiState.imageTemplates + uiState.textTemplates + uiState.stickerTemplates)
                .firstOrNull { it.template.id == editId }
                ?.template
        }

        TemplateEditorScreen(
            editor = activeEditor,
            previewTarget = previewTarget,
            busy = uiState.isBusy,
            onNameChanged = viewModel::updateEditorName,
            onTypeChanged = viewModel::updateEditorType,
            onContentChanged = viewModel::updateEditorContent,
            onStickerNameChanged = viewModel::updateEditorStickerName,
            onStickerLabelChanged = viewModel::updateEditorStickerLabel,
            onStickerCategoryChanged = viewModel::updateEditorStickerCategory,
            onStickerFallbackIndexChanged = viewModel::updateEditorStickerFallbackIndex,
            onApplyStickerPreset = viewModel::applyStickerPreset,
            onPickImage = {
                try {
                    pickImageLauncher.launch(arrayOf("image/*"))
                } catch (_: ActivityNotFoundException) {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("系统未提供图片选择器，无法选择本地图片")
                    }
                }
            },
            onEnabledChanged = viewModel::updateEditorEnabled,
            onAddPoolDraft = viewModel::addEditorPoolDraftRow,
            onRemovePoolDraft = viewModel::removeEditorPoolDraftRow,
            onPoolDraftContentChanged = viewModel::updateEditorPoolDraftContent,
            onTogglePoolDraftWeekday = viewModel::toggleEditorPoolDraftWeekday,
            onSetPoolDraftEveryday = viewModel::setEditorPoolDraftEveryday,
            onTogglePoolDraftEnabled = viewModel::toggleEditorPoolDraftEnabled,
            onSave = viewModel::saveTemplateEditor,
            onDismiss = viewModel::dismissTemplateEditor,
            modifier = modifier,
            snackbarHostState = snackbarHostState,
            onRequestDelete = editingTemplateEntity?.let { tpl ->
                { viewModel.requestDeleteTemplate(tpl) }
            },
        )
    } else {
        TemplatesScreen(
            uiState = uiState,
            snackbarHostState = snackbarHostState,
            listState = listState,
            onSelectPreviewTarget = viewModel::selectPreviewTarget,
            onOpenCreate = viewModel::openCreateTemplate,
            onOpenEdit = viewModel::openEditTemplate,
            onToggleTemplateEnabled = viewModel::toggleTemplateEnabled,
            onRequestDelete = viewModel::requestDeleteTemplate,
            onOpenSettings = { onOpenSettings(null) },
            modifier = modifier,
        )
    }

    uiState.deleteConfirmState?.let { confirm ->
        DeleteTemplateConfirmSheet(
            confirm = confirm,
            busy = uiState.isBusy,
            onConfirm = {
                val editingId = uiState.editorState?.editingTemplateId
                viewModel.confirmDeleteTemplate()
                if (editingId == confirm.template.id) {
                    viewModel.dismissTemplateEditor()
                }
            },
            onDismiss = viewModel::dismissDeleteConfirm,
        )
    }
}

@Composable
fun TemplatesScreen(
    uiState: TemplatesUiState,
    snackbarHostState: SnackbarHostState,
    onSelectPreviewTarget: (String?) -> Unit,
    onOpenCreate: (TemplateType) -> Unit,
    onOpenEdit: (MessageTemplateEntity) -> Unit,
    onToggleTemplateEnabled: (MessageTemplateEntity, Boolean) -> Unit,
    onRequestDelete: (MessageTemplateEntity) -> Unit,
    onOpenSettings: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val previewTarget = remember(uiState.selectedPreviewTargetId, uiState.sampleTargets) {
        uiState.sampleTargets.firstOrNull { it.id == uiState.selectedPreviewTargetId }
            ?: uiState.sampleTargets.firstOrNull()
    }
    var showCreateTypeSheet by remember { mutableStateOf(false) }
    var showPreviewTargetSheet by remember { mutableStateOf(false) }

    val missingImageCards = remember(uiState.imageTemplates) {
        uiState.imageTemplates.filter { !it.imageAvailable }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = palette.canvas,
        contentColor = palette.ink,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 86.dp),
            )
        },
        topBar = {
            FlameTopBar(
                title = "模板",
                actions = {
                    FlameTopPillButton(
                        text = "新建",
                        leadingIcon = Icons.Default.Add,
                        primary = true,
                        onClick = { showCreateTypeSheet = true },
                    )
                    FlameIconButton(
                        icon = Icons.Outlined.Settings,
                        contentDescription = "设置与维护",
                        onClick = onOpenSettings,
                    )
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = flameDockBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 单行只读预览代入卡
                item(key = "preview_target_bar") {
                    ReadOnlyPreviewTargetCard(
                        previewTarget = previewTarget,
                        canSelect = uiState.sampleTargets.isNotEmpty(),
                        onClick = { showPreviewTargetSheet = true },
                    )
                }

                // 缺图恢复高优提示条（仅存在待补图图片模板时显示）
                if (missingImageCards.isNotEmpty()) {
                    item(key = "missing_image_alert") {
                        val firstMissing = missingImageCards.first().template
                        FlameAlertStrip(
                            title = "有 ${missingImageCards.size} 个图片模板待补图",
                            subtitle = "本地图片文件缺失或来自跨机迁移，重新选图后可恢复自动续火",
                            tone = FlameTone.DANGER,
                            actionLabel = "去补图",
                            icon = Icons.Outlined.BrokenImage,
                            onClick = { onOpenEdit(firstMissing) },
                        )
                    }
                }

                if (uiState.totalCount == 0) {
                    item(key = "empty_all") {
                        FlameEmptyStateCard(
                            icon = Icons.AutoMirrored.Outlined.Notes,
                            title = "暂无消息模板",
                            subtitle = "支持星期轮换文案池、本地图片、固定文本与抖音原生表情，创建后可绑定给好友自动续火。",
                            actionLabel = "新建模板",
                            onAction = { showCreateTypeSheet = true },
                        )
                    }
                } else {
                    // 分组 1：星期轮换文案池
                    item(key = "group_pool") {
                        TemplateGroupSection(
                            title = "星期轮换文案池",
                            type = TemplateType.POOL,
                            cards = uiState.poolTemplates,
                            emptyHint = "按星期与最久未用顺序自动轮换发送",
                            previewTarget = previewTarget,
                            onAddClick = { onOpenCreate(TemplateType.POOL) },
                            onOpenEdit = onOpenEdit,
                            onToggleEnabled = onToggleTemplateEnabled,
                            onRequestDelete = onRequestDelete,
                        )
                    }

                    // 分组 2：本地图片模板
                    item(key = "group_image") {
                        TemplateGroupSection(
                            title = "本地图片模板",
                            type = TemplateType.IMAGE,
                            cards = uiState.imageTemplates,
                            emptyHint = "从相册选图并自动剥离 EXIF 隐私信息",
                            previewTarget = previewTarget,
                            onAddClick = { onOpenCreate(TemplateType.IMAGE) },
                            onOpenEdit = onOpenEdit,
                            onToggleEnabled = onToggleTemplateEnabled,
                            onRequestDelete = onRequestDelete,
                        )
                    }

                    // 分组 3：固定文本模板
                    item(key = "group_text") {
                        TemplateGroupSection(
                            title = "固定文本模板",
                            type = TemplateType.TEXT,
                            cards = uiState.textTemplates,
                            emptyHint = "发送固定问候文案，支持好友名与连签天数变量",
                            previewTarget = previewTarget,
                            onAddClick = { onOpenCreate(TemplateType.TEXT) },
                            onOpenEdit = onOpenEdit,
                            onToggleEnabled = onToggleTemplateEnabled,
                            onRequestDelete = onRequestDelete,
                        )
                    }

                    // 分组 4：抖音原生表情
                    item(key = "group_sticker") {
                        TemplateGroupSection(
                            title = "抖音原生表情",
                            type = TemplateType.STICKER,
                            cards = uiState.stickerTemplates,
                            emptyHint = "匹配抖音聊天面板原生表情自动发送",
                            previewTarget = previewTarget,
                            onAddClick = { onOpenCreate(TemplateType.STICKER) },
                            onOpenEdit = onOpenEdit,
                            onToggleEnabled = onToggleTemplateEnabled,
                            onRequestDelete = onRequestDelete,
                        )
                    }
                }
            }
        }
    }

    if (showCreateTypeSheet) {
        CreateTemplateTypeSheet(
            onSelectType = { type ->
                showCreateTypeSheet = false
                onOpenCreate(type)
            },
            onDismiss = { showCreateTypeSheet = false },
        )
    }

    if (showPreviewTargetSheet) {
        PreviewTargetPickerSheet(
            targets = uiState.sampleTargets,
            selectedTargetId = previewTarget?.id,
            onSelect = { targetId ->
                showPreviewTargetSheet = false
                onSelectPreviewTarget(targetId)
            },
            onDismiss = { showPreviewTargetSheet = false },
        )
    }
}

@Composable
private fun ReadOnlyPreviewTargetCard(
    previewTarget: ContractTargetEntity?,
    canSelect: Boolean,
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    FlameCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
        onClick = if (canSelect) onClick else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.surfaceSubtle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Visibility,
                    contentDescription = null,
                    tint = palette.inkSecondary,
                    modifier = Modifier.size(17.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = previewTarget?.let { "预览代入：${it.nicknameSnapshot}" } ?: "预览代入：测试好友",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    FlameMiniTag(
                        text = "本机 ${previewTarget?.streakDays ?: 7} 天",
                        tone = FlameTone.NEUTRAL,
                    )
                }
                Text(
                    text = "仅只读预览，不改变轮换顺位",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canSelect) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = "切换预览代入好友",
                    tint = palette.inkTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun TemplateGroupSection(
    title: String,
    type: TemplateType,
    cards: List<TemplateCardUi>,
    emptyHint: String,
    previewTarget: ContractTargetEntity?,
    onAddClick: () -> Unit,
    onOpenEdit: (MessageTemplateEntity) -> Unit,
    onToggleEnabled: (MessageTemplateEntity, Boolean) -> Unit,
    onRequestDelete: (MessageTemplateEntity) -> Unit,
) {
    val palette = FlameThemeTokens.palette
    FlameGroupedSection(
        title = "$title (${cards.size})",
        trailingHeader = {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onAddClick)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = palette.inkSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "添加",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = palette.inkSecondary,
                )
            }
        },
    ) {
        if (cards.isEmpty()) {
            FlameListRow(
                title = "暂无$title",
                subtitle = emptyHint,
                trailing = {
                    Text(
                        text = "新建 ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.inkTertiary,
                    )
                },
                showDivider = false,
                onClick = onAddClick,
            )
        } else {
            cards.forEachIndexed { index, card ->
                TemplateListItemRow(
                    card = card,
                    type = type,
                    previewTarget = previewTarget,
                    showDivider = index < cards.lastIndex,
                    onClick = { onOpenEdit(card.template) },
                    onToggleEnabled = { onToggleEnabled(card.template, it) },
                    onDelete = { onRequestDelete(card.template) },
                )
            }
        }
    }
}

@Composable
private fun TemplateListItemRow(
    card: TemplateCardUi,
    type: TemplateType,
    previewTarget: ContractTargetEntity?,
    showDivider: Boolean,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val tpl = card.template
    val isMissingImage = type == TemplateType.IMAGE && !card.imageAvailable

    val subtitleText = remember(card, type, previewTarget) {
        buildTemplateRowSubtitle(
            card = card,
            type = type,
            previewTarget = previewTarget,
        )
    }

    FlameListRow(
        title = tpl.name,
        subtitle = subtitleText,
        showDivider = showDivider,
        dividerIndent = if (type == TemplateType.IMAGE) 70.dp else 60.dp,
        onClick = onClick,
        leading = {
            when (type) {
                TemplateType.IMAGE -> {
                    if (card.imageAvailable && card.imageFile != null) {
                        TemplateImageThumbnail(
                            imagePath = card.imageFile.absolutePath,
                            maxEdgePx = 176,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, palette.hairline, RoundedCornerShape(12.dp)),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(semantics.dangerContainer)
                                .border(1.dp, semantics.dangerBorder, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.BrokenImage,
                                contentDescription = "缺少本地图片",
                                tint = semantics.danger,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                TemplateType.POOL -> {
                    FlameRowAvatar(tone = FlameTone.NEUTRAL) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.FormatListBulleted,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                TemplateType.TEXT -> {
                    FlameRowAvatar(tone = FlameTone.NEUTRAL) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Notes,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                TemplateType.STICKER -> {
                    FlameRowAvatar(tone = if (tpl.enabled) FlameTone.WARNING else FlameTone.NEUTRAL) {
                        Icon(
                            imageVector = Icons.Outlined.EmojiEmotions,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
        titleTag = {
            when {
                isMissingImage -> {
                    FlameMiniTag(text = "待补图", tone = FlameTone.DANGER)
                }

                type == TemplateType.POOL -> {
                    val enabledItems = card.poolItems.count { it.item.enabled }
                    FlameMiniTag(
                        text = "$enabledItems/${card.poolItems.size} 条",
                        tone = FlameTone.NEUTRAL,
                    )
                }

                type == TemplateType.STICKER -> {
                    FlameMiniTag(
                        text = tpl.stickerName?.takeIf { it.isNotBlank() }
                            ?: TemplatesViewModel.DEFAULT_STICKER_NAME,
                        tone = FlameTone.WARNING,
                    )
                }

                !tpl.enabled -> {
                    FlameMiniTag(text = "已停用", tone = FlameTone.NEUTRAL)
                }
            }
        },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Switch(
                    checked = tpl.enabled,
                    onCheckedChange = onToggleEnabled,
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "删除模板",
                        tint = palette.inkTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    )
}

private fun buildTemplateRowSubtitle(
    card: TemplateCardUi,
    type: TemplateType,
    previewTarget: ContractTargetEntity?,
): String {
    val tpl = card.template
    val refPart = "已绑 ${card.activeReferenceCount} 人"
    val disabledSuffix = if (!tpl.enabled) " · 已停用" else ""

    return when (type) {
        TemplateType.POOL -> {
            val cleanPreview = card.nextPoolPreviewText
                ?.replace('\n', ' ')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (cleanPreview != null) {
                "$refPart$disabledSuffix · 下句：$cleanPreview"
            } else {
                "$refPart$disabledSuffix · 今日暂无可用启用文案"
            }
        }

        TemplateType.IMAGE -> {
            if (!card.imageAvailable) {
                "$refPart · 缺少本地图片，点击重新选图"
            } else {
                val summary = card.imageSummary ?: "本地图片就绪"
                "$refPart$disabledSuffix · $summary"
            }
        }

        TemplateType.TEXT -> {
            val cleanPreview = TemplatesViewModel.renderTemplatePreview(tpl.content, previewTarget)
                .replace('\n', ' ')
                .trim()
            "$refPart$disabledSuffix · 预览：$cleanPreview"
        }

        TemplateType.STICKER -> {
            val meta = buildList {
                add(refPart + disabledSuffix)
                tpl.stickerLabel?.takeIf { it.isNotBlank() && it != tpl.stickerName }?.let { add("标签 $it") }
                tpl.stickerCategory?.takeIf { it.isNotBlank() }?.let { add(it) }
                tpl.stickerFallbackIndex?.let { add("兜底 #$it") }
            }
            meta.joinToString(" · ")
        }
    }
}

private data class CreateTypeOption(
    val type: TemplateType,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tone: FlameTone,
)

@Composable
private fun CreateTemplateTypeSheet(
    onSelectType: (TemplateType) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val options = remember {
        listOf(
            CreateTypeOption(
                type = TemplateType.POOL,
                title = "星期轮换文案池",
                subtitle = "多条候选按星期掩码与最久未用顺序自动轮换",
                icon = Icons.AutoMirrored.Outlined.FormatListBulleted,
                tone = FlameTone.NEUTRAL,
            ),
            CreateTypeOption(
                type = TemplateType.IMAGE,
                title = "本地图片模板",
                subtitle = "相册选图，自动缩图去 EXIF 并私密保存",
                icon = Icons.Outlined.Image,
                tone = FlameTone.HEALTHY,
            ),
            CreateTypeOption(
                type = TemplateType.TEXT,
                title = "固定文本模板",
                subtitle = "单条固定文案，支持好友名与连签天数变量",
                icon = Icons.AutoMirrored.Outlined.Notes,
                tone = FlameTone.NEUTRAL,
            ),
            CreateTypeOption(
                type = TemplateType.STICKER,
                title = "抖音原生表情",
                subtitle = "匹配抖音聊天面板原生表情自动发送",
                icon = Icons.Outlined.EmojiEmotions,
                tone = FlameTone.WARNING,
            ),
        )
    }

    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = "新建消息模板",
        subtitle = "选择要创建的续火消息类型",
    ) {
        FlameGroupedSection {
            options.forEachIndexed { idx, opt ->
                FlameListRow(
                    title = opt.title,
                    subtitle = opt.subtitle,
                    showDivider = idx < options.lastIndex,
                    dividerIndent = 60.dp,
                    onClick = { onSelectType(opt.type) },
                    leading = {
                        FlameRowAvatar(tone = opt.tone) {
                            Icon(
                                imageVector = opt.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = {
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = palette.inkTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun PreviewTargetPickerSheet(
    targets: List<ContractTargetEntity>,
    selectedTargetId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = "选择预览代入好友",
        subtitle = "仅用于只读预览变量替换效果，绝不改写文案池轮换顺位",
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            items(items = targets, key = { it.id }) { target ->
                val isSelected = target.id == selectedTargetId
                FlameCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    containerColor = if (isSelected) palette.surfaceSubtle else palette.surface,
                    borderColor = if (isSelected) palette.monoCtaContainer.copy(alpha = 0.45f) else palette.hairline,
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
                    onClick = { onSelect(target.id) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = if (isSelected) {
                                Icons.Filled.CheckCircle
                            } else {
                                Icons.Outlined.RadioButtonUnchecked
                            },
                            contentDescription = null,
                            tint = if (isSelected) palette.monoCtaContainer else palette.hairlineStrong,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = target.nicknameSnapshot,
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        FlameStreakBadge(days = target.streakDays)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteTemplateConfirmSheet(
    confirm: DeleteTemplateConfirmState,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val hasRefs = confirm.activeReferenceCount > 0

    FlameModalSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        title = "删除模板「${confirm.template.name}」？",
        subtitle = if (hasRefs) {
            "当前有 ${confirm.activeReferenceCount} 位活跃好友正在使用此模板"
        } else {
            "删除后不可恢复"
        },
    ) {
        if (hasRefs) {
            Surface(
                color = semantics.warningContainer,
                border = BorderStroke(1.dp, semantics.warningBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = semantics.warning,
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(18.dp),
                    )
                    Text(
                        text = "当前有 ${confirm.activeReferenceCount} 位活跃好友正在使用此模板。删除后这些好友将转为「未绑定模板」状态，需重新绑定后才可自动发送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = semantics.onWarningContainer,
                    )
                }
            }
        } else {
            Text(
                text = "删除后不可恢复，确定要删除此模板吗？",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.inkSecondary,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlameSheetActionRow(
                text = "确认删除",
                trailingText = if (hasRefs) "解绑 ${confirm.activeReferenceCount} 位好友" else null,
                onClick = onConfirm,
                primary = true,
                danger = true,
                enabled = !busy,
            )
            FlameSheetActionRow(
                text = "取消",
                onClick = onDismiss,
                enabled = !busy,
            )
        }
    }
}
