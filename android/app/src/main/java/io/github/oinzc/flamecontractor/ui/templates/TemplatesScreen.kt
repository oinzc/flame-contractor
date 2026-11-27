package io.github.oinzc.flamecontractor.ui.templates

import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import io.github.oinzc.flamecontractor.ui.components.FLAME_TEMPLATES_SPLIT_MIN_WIDTH
import io.github.oinzc.flamecontractor.ui.components.FlameAlertStrip
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameEmptyStateCard
import io.github.oinzc.flamecontractor.ui.components.FlameFeedbackTopButton
import io.github.oinzc.flamecontractor.ui.components.FlameFormFactor
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameIconButton
import io.github.oinzc.flamecontractor.ui.components.FlameListRow
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameModalSheet
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameSheetActionRow
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameStreakBadge
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.FlameTopBar
import io.github.oinzc.flamecontractor.ui.components.FlameTopPillButton
import io.github.oinzc.flamecontractor.ui.components.LocalFlameChromeHandlesGlobalNav
import io.github.oinzc.flamecontractor.ui.components.LocalFlameWindowLayout
import io.github.oinzc.flamecontractor.ui.components.flameContentBottomPadding
import io.github.oinzc.flamecontractor.ui.components.flameDockBottomPadding
import io.github.oinzc.flamecontractor.ui.components.performFlameLightTick
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import kotlinx.coroutines.launch

@Composable
fun TemplatesRoute(
    database: FlameDatabase,
    onOpenSettings: (SettingsSection?) -> Unit = {},
    onOpenFeedback: () -> Unit = { onOpenSettings(SettingsSection.ABOUT) },
    onEditorVisibleChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    accountId: String = DEFAULT_ACCOUNT_ID,
    imageStore: TemplateImageStorage? = null,
    onBack: (() -> Unit)? = null,
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
            onOpenFeedback = onOpenFeedback,
            onBack = onBack,
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
    onOpenFeedback: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val layout = LocalFlameWindowLayout.current
    val chromeHandlesGlobalNav = LocalFlameChromeHandlesGlobalNav.current
    val hideGlobalTopActions = onBack == null && chromeHandlesGlobalNav
    val previewTarget = remember(uiState.selectedPreviewTargetId, uiState.sampleTargets) {
        uiState.sampleTargets.firstOrNull { it.id == uiState.selectedPreviewTargetId }
            ?: uiState.sampleTargets.firstOrNull()
    }
    var showCreateTypeSheet by remember { mutableStateOf(false) }
    var showPreviewTargetSheet by remember { mutableStateOf(false) }
    var selectedTemplateId by rememberSaveable { mutableStateOf<String?>(null) }

    val missingImageCards = remember(uiState.imageTemplates) {
        uiState.imageTemplates.filter { !it.imageAvailable }
    }
    val allTypedCards = remember(
        uiState.poolTemplates,
        uiState.imageTemplates,
        uiState.textTemplates,
        uiState.stickerTemplates,
    ) {
        buildList {
            uiState.poolTemplates.forEach { add(it to TemplateType.POOL) }
            uiState.imageTemplates.forEach { add(it to TemplateType.IMAGE) }
            uiState.textTemplates.forEach { add(it to TemplateType.TEXT) }
            uiState.stickerTemplates.forEach { add(it to TemplateType.STICKER) }
        }
    }
    val selectedPreviewPair = remember(selectedTemplateId, allTypedCards) {
        allTypedCards.firstOrNull { it.first.template.id == selectedTemplateId }
            ?: allTypedCards.firstOrNull()
    }
    val dockBottomClearance = flameDockBottomPadding()
    val hasBottomDock = onBack == null && layout.hasBottomFloatingDock
    val navBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentBottomPadding = flameContentBottomPadding(hasBottomDock)

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
                    .padding(
                        bottom = if (hasBottomDock) dockBottomClearance - 12.dp else navBarBottomPadding + 16.dp,
                    ),
            )
        },
        topBar = {
            if (onBack != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(
                            horizontal = layout.horizontalGutter,
                            vertical = if (layout.isShortLandscape) 6.dp else 12.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlameIconButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回极简主页",
                        onClick = onBack,
                    )
                    Text(
                        text = "续火模板",
                        style = if (layout.isShortLandscape) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.headlineSmall
                        },
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
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
                }
            } else {
                FlameTopBar(
                    title = "模板",
                    subtitle = if (hideGlobalTopActions && uiState.totalCount > 0) {
                        "共 ${uiState.totalCount} 个消息模板"
                    } else {
                        null
                    },
                    actions = {
                        FlameTopPillButton(
                            text = "新建",
                            leadingIcon = Icons.Default.Add,
                            primary = true,
                            onClick = { showCreateTypeSheet = true },
                        )
                        if (!hideGlobalTopActions) {
                            FlameFeedbackTopButton(
                                onClick = onOpenFeedback,
                            )
                            FlameIconButton(
                                icon = Icons.Outlined.Settings,
                                contentDescription = "设置与维护",
                                onClick = onOpenSettings,
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .then(
                    if (onBack != null) {
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            val useSplitPreview = maxWidth >= FLAME_TEMPLATES_SPLIT_MIN_WIDTH
            val isCompactPortrait = layout.formFactor == FlameFormFactor.COMPACT_PORTRAIT

            when {
                useSplitPreview -> {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = layout.horizontalGutter)
                            .navigationBarsPadding(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            val twoColGallery = maxWidth >= 480.dp
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                if (missingImageCards.isNotEmpty()) {
                                    item(key = "split_missing_image_alert") {
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
                                    item(key = "split_empty_all") {
                                        FlameEmptyStateCard(
                                            icon = Icons.AutoMirrored.Outlined.Notes,
                                            title = "暂无消息模板",
                                            subtitle = "支持星期轮换文案池、本地图片、固定文本与抖音原生表情，创建后可绑定给好友自动续火。",
                                            actionLabel = "新建模板",
                                            onAction = { showCreateTypeSheet = true },
                                        )
                                    }
                                } else {
                                    item(key = "split_group_pool") {
                                        TemplateGalleryGroupSection(
                                            title = "星期轮换文案池",
                                            type = TemplateType.POOL,
                                            cards = uiState.poolTemplates,
                                            emptyHint = "按星期与最久未用顺序自动轮换发送",
                                            previewTarget = previewTarget,
                                            twoColumn = twoColGallery,
                                            selectedTemplateId = selectedPreviewPair?.first?.template?.id,
                                            onAddClick = { onOpenCreate(TemplateType.POOL) },
                                            onCardClick = { selectedTemplateId = it.template.id },
                                            onEditClick = { onOpenEdit(it.template) },
                                            onToggleEnabled = onToggleTemplateEnabled,
                                            onRequestDelete = onRequestDelete,
                                        )
                                    }
                                    item(key = "split_group_image") {
                                        TemplateGalleryGroupSection(
                                            title = "本地图片模板",
                                            type = TemplateType.IMAGE,
                                            cards = uiState.imageTemplates,
                                            emptyHint = "从相册选图并自动剥离 EXIF 隐私信息",
                                            previewTarget = previewTarget,
                                            twoColumn = twoColGallery,
                                            selectedTemplateId = selectedPreviewPair?.first?.template?.id,
                                            onAddClick = { onOpenCreate(TemplateType.IMAGE) },
                                            onCardClick = { selectedTemplateId = it.template.id },
                                            onEditClick = { onOpenEdit(it.template) },
                                            onToggleEnabled = onToggleTemplateEnabled,
                                            onRequestDelete = onRequestDelete,
                                        )
                                    }
                                    item(key = "split_group_text") {
                                        TemplateGalleryGroupSection(
                                            title = "固定文本模板",
                                            type = TemplateType.TEXT,
                                            cards = uiState.textTemplates,
                                            emptyHint = "发送固定问候文案，支持好友名与连签天数变量",
                                            previewTarget = previewTarget,
                                            twoColumn = twoColGallery,
                                            selectedTemplateId = selectedPreviewPair?.first?.template?.id,
                                            onAddClick = { onOpenCreate(TemplateType.TEXT) },
                                            onCardClick = { selectedTemplateId = it.template.id },
                                            onEditClick = { onOpenEdit(it.template) },
                                            onToggleEnabled = onToggleTemplateEnabled,
                                            onRequestDelete = onRequestDelete,
                                        )
                                    }
                                    item(key = "split_group_sticker") {
                                        TemplateGalleryGroupSection(
                                            title = "抖音原生表情",
                                            type = TemplateType.STICKER,
                                            cards = uiState.stickerTemplates,
                                            emptyHint = "匹配抖音聊天面板原生表情自动发送",
                                            previewTarget = previewTarget,
                                            twoColumn = twoColGallery,
                                            selectedTemplateId = selectedPreviewPair?.first?.template?.id,
                                            onAddClick = { onOpenCreate(TemplateType.STICKER) },
                                            onCardClick = { selectedTemplateId = it.template.id },
                                            onEditClick = { onOpenEdit(it.template) },
                                            onToggleEnabled = onToggleTemplateEnabled,
                                            onRequestDelete = onRequestDelete,
                                        )
                                    }
                                }
                            }
                        }

                        TemplateLivePreviewPanel(
                            selectedPair = selectedPreviewPair,
                            previewTarget = previewTarget,
                            canSelectPreviewTarget = uiState.sampleTargets.isNotEmpty(),
                            onOpenPreviewTargetPicker = { showPreviewTargetSheet = true },
                            onOpenCreate = { showCreateTypeSheet = true },
                            onEdit = { onOpenEdit(it) },
                            onToggleEnabled = onToggleTemplateEnabled,
                            onDelete = onRequestDelete,
                            modifier = Modifier
                                .width(332.dp)
                                .fillMaxHeight()
                                .padding(top = 4.dp, bottom = 16.dp),
                        )
                    }
                }

                !isCompactPortrait -> {
                    val useTwoColCards = maxWidth >= 540.dp
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = layout.horizontalGutter),
                        verticalArrangement = Arrangement.spacedBy(if (layout.isShortLandscape) 8.dp else 12.dp),
                    ) {
                        ReadOnlyPreviewTargetCard(
                            previewTarget = previewTarget,
                            canSelect = uiState.sampleTargets.isNotEmpty(),
                            onClick = { showPreviewTargetSheet = true },
                            modifier = Modifier.padding(top = 4.dp),
                        )

                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentPadding = PaddingValues(top = 2.dp, bottom = contentBottomPadding),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            if (missingImageCards.isNotEmpty()) {
                                item(key = "tablet_missing_image_alert") {
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
                                item(key = "tablet_empty_all") {
                                    FlameEmptyStateCard(
                                        icon = Icons.AutoMirrored.Outlined.Notes,
                                        title = "暂无消息模板",
                                        subtitle = "支持星期轮换文案池、本地图片、固定文本与抖音原生表情，创建后可绑定给好友自动续火。",
                                        actionLabel = "新建模板",
                                        onAction = { showCreateTypeSheet = true },
                                    )
                                }
                            } else if (useTwoColCards) {
                                item(key = "tablet_group_pool") {
                                    TemplateGalleryGroupSection(
                                        title = "星期轮换文案池",
                                        type = TemplateType.POOL,
                                        cards = uiState.poolTemplates,
                                        emptyHint = "按星期与最久未用顺序自动轮换发送",
                                        previewTarget = previewTarget,
                                        twoColumn = true,
                                        selectedTemplateId = null,
                                        onAddClick = { onOpenCreate(TemplateType.POOL) },
                                        onCardClick = { onOpenEdit(it.template) },
                                        onEditClick = { onOpenEdit(it.template) },
                                        onToggleEnabled = onToggleTemplateEnabled,
                                        onRequestDelete = onRequestDelete,
                                    )
                                }
                                item(key = "tablet_group_image") {
                                    TemplateGalleryGroupSection(
                                        title = "本地图片模板",
                                        type = TemplateType.IMAGE,
                                        cards = uiState.imageTemplates,
                                        emptyHint = "从相册选图并自动剥离 EXIF 隐私信息",
                                        previewTarget = previewTarget,
                                        twoColumn = true,
                                        selectedTemplateId = null,
                                        onAddClick = { onOpenCreate(TemplateType.IMAGE) },
                                        onCardClick = { onOpenEdit(it.template) },
                                        onEditClick = { onOpenEdit(it.template) },
                                        onToggleEnabled = onToggleTemplateEnabled,
                                        onRequestDelete = onRequestDelete,
                                    )
                                }
                                item(key = "tablet_group_text") {
                                    TemplateGalleryGroupSection(
                                        title = "固定文本模板",
                                        type = TemplateType.TEXT,
                                        cards = uiState.textTemplates,
                                        emptyHint = "发送固定问候文案，支持好友名与连签天数变量",
                                        previewTarget = previewTarget,
                                        twoColumn = true,
                                        selectedTemplateId = null,
                                        onAddClick = { onOpenCreate(TemplateType.TEXT) },
                                        onCardClick = { onOpenEdit(it.template) },
                                        onEditClick = { onOpenEdit(it.template) },
                                        onToggleEnabled = onToggleTemplateEnabled,
                                        onRequestDelete = onRequestDelete,
                                    )
                                }
                                item(key = "tablet_group_sticker") {
                                    TemplateGalleryGroupSection(
                                        title = "抖音原生表情",
                                        type = TemplateType.STICKER,
                                        cards = uiState.stickerTemplates,
                                        emptyHint = "匹配抖音聊天面板原生表情自动发送",
                                        previewTarget = previewTarget,
                                        twoColumn = true,
                                        selectedTemplateId = null,
                                        onAddClick = { onOpenCreate(TemplateType.STICKER) },
                                        onCardClick = { onOpenEdit(it.template) },
                                        onEditClick = { onOpenEdit(it.template) },
                                        onToggleEnabled = onToggleTemplateEnabled,
                                        onRequestDelete = onRequestDelete,
                                    )
                                }
                            } else {
                                item(key = "tablet_single_group_pool") {
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
                                item(key = "tablet_single_group_image") {
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
                                item(key = "tablet_single_group_text") {
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
                                item(key = "tablet_single_group_sticker") {
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

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .widthIn(max = 840.dp)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 4.dp,
                            bottom = contentBottomPadding,
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
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    FlameCard(
        modifier = modifier.fillMaxWidth(),
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
private fun TemplateGalleryGroupSection(
    title: String,
    type: TemplateType,
    cards: List<TemplateCardUi>,
    emptyHint: String,
    previewTarget: ContractTargetEntity?,
    twoColumn: Boolean,
    selectedTemplateId: String?,
    onAddClick: () -> Unit,
    onCardClick: (TemplateCardUi) -> Unit,
    onEditClick: (TemplateCardUi) -> Unit,
    onToggleEnabled: (MessageTemplateEntity, Boolean) -> Unit,
    onRequestDelete: (MessageTemplateEntity) -> Unit,
) {
    val palette = FlameThemeTokens.palette
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$title (${cards.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkSecondary,
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onAddClick)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
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
        }

        if (cards.isEmpty()) {
            FlameCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                onClick = onAddClick,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FlameRowAvatar(tone = FlameTone.NEUTRAL) {
                        Icon(
                            imageVector = when (type) {
                                TemplateType.POOL -> Icons.AutoMirrored.Outlined.FormatListBulleted
                                TemplateType.IMAGE -> Icons.Outlined.Image
                                TemplateType.TEXT -> Icons.AutoMirrored.Outlined.Notes
                                TemplateType.STICKER -> Icons.Outlined.EmojiEmotions
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "暂无$title",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = palette.ink,
                        )
                        Text(
                            text = emptyHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FlameTopPillButton(
                        text = "新建",
                        leadingIcon = Icons.Default.Add,
                        onClick = onAddClick,
                    )
                }
            }
        } else if (twoColumn) {
            val rows = cards.chunked(2)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        pair.forEach { card ->
                            TemplateGalleryCard(
                                card = card,
                                type = type,
                                previewTarget = previewTarget,
                                selected = card.template.id == selectedTemplateId,
                                onClick = { onCardClick(card) },
                                onEditClick = { onEditClick(card) },
                                onToggleEnabled = { enabled -> onToggleEnabled(card.template, enabled) },
                                onRequestDelete = { onRequestDelete(card.template) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                cards.forEach { card ->
                    TemplateGalleryCard(
                        card = card,
                        type = type,
                        previewTarget = previewTarget,
                        selected = card.template.id == selectedTemplateId,
                        onClick = { onCardClick(card) },
                        onEditClick = { onEditClick(card) },
                        onToggleEnabled = { enabled -> onToggleEnabled(card.template, enabled) },
                        onRequestDelete = { onRequestDelete(card.template) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateGalleryCard(
    card: TemplateCardUi,
    type: TemplateType,
    previewTarget: ContractTargetEntity?,
    selected: Boolean,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val haptics = LocalHapticFeedback.current
    val tpl = card.template
    val isMissingImage = type == TemplateType.IMAGE && !card.imageAvailable

    FlameCard(
        modifier = modifier,
        cornerRadius = 18.dp,
        containerColor = if (selected) palette.surfaceSubtle else palette.surface,
        borderColor = when {
            selected -> palette.emberBorder
            isMissingImage -> semantics.dangerBorder
            else -> palette.hairline
        },
        contentPadding = PaddingValues(13.dp),
        onClick = {
            haptics.performFlameLightTick()
            onClick()
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    if (type == TemplateType.IMAGE && card.imageAvailable && tpl.imagePath != null) {
                        TemplateImageThumbnail(
                            imagePath = tpl.imagePath,
                            maxEdgePx = 128,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, palette.hairline, RoundedCornerShape(10.dp)),
                        )
                    } else {
                        val avatarTone = when {
                            isMissingImage -> FlameTone.DANGER
                            !tpl.enabled -> FlameTone.NEUTRAL
                            type == TemplateType.POOL -> FlameTone.EMBER
                            else -> FlameTone.HEALTHY
                        }
                        FlameRowAvatar(
                            tone = avatarTone,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = when (type) {
                                    TemplateType.POOL -> Icons.AutoMirrored.Outlined.FormatListBulleted
                                    TemplateType.IMAGE -> if (card.imageAvailable) {
                                        Icons.Outlined.Image
                                    } else {
                                        Icons.Outlined.BrokenImage
                                    }
                                    TemplateType.TEXT -> Icons.AutoMirrored.Outlined.Notes
                                    TemplateType.STICKER -> Icons.Outlined.EmojiEmotions
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = tpl.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            FlameMiniTag(
                                text = "已绑 ${card.activeReferenceCount} 人",
                                tone = FlameTone.NEUTRAL,
                            )
                            if (type == TemplateType.POOL) {
                                FlameMiniTag(
                                    text = "${card.poolItems.size} 句",
                                    tone = FlameTone.EMBER,
                                )
                            } else if (!tpl.enabled) {
                                FlameMiniTag(
                                    text = "已停用",
                                    tone = FlameTone.WARNING,
                                )
                            }
                        }
                    }
                }

                Text(
                    text = buildTemplateGallerySnippet(card, type, previewTarget),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(thickness = 1.dp, color = palette.hairline)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (isMissingImage) "点击去补图" else "编辑模板",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isMissingImage) semantics.danger else palette.inkSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onEditClick)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Switch(
                            checked = tpl.enabled,
                            onCheckedChange = onToggleEnabled,
                        )
                        IconButton(
                            onClick = onRequestDelete,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "删除模板",
                                tint = palette.inkTertiary,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateLivePreviewPanel(
    selectedPair: Pair<TemplateCardUi, TemplateType>?,
    previewTarget: ContractTargetEntity?,
    canSelectPreviewTarget: Boolean,
    onOpenPreviewTargetPicker: () -> Unit,
    onOpenCreate: () -> Unit,
    onEdit: (MessageTemplateEntity) -> Unit,
    onToggleEnabled: (MessageTemplateEntity, Boolean) -> Unit,
    onDelete: (MessageTemplateEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val haptics = LocalHapticFeedback.current

    FlameCard(
        modifier = modifier,
        cornerRadius = 22.dp,
        contentPadding = PaddingValues(15.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 固定头部 + 只读代入好友选择条
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "实时发送预览",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                )
                FlameMiniTag(
                    text = "只读 · 不改顺位",
                    tone = FlameTone.NEUTRAL,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            ReadOnlyPreviewTargetCard(
                previewTarget = previewTarget,
                canSelect = canSelectPreviewTarget,
                onClick = onOpenPreviewTargetPicker,
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(thickness = 1.dp, color = palette.hairline)

            if (selectedPair == null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(12.dp),
                    ) {
                        FlameRowAvatar(
                            tone = FlameTone.NEUTRAL,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Text(
                            text = "点选左侧任意模板",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.ink,
                        )
                        Text(
                            text = "这里将实时预览发送给当前好友的真实消息效果",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                            textAlign = TextAlign.Center,
                        )
                        FlameTopPillButton(
                            text = "新建模板",
                            leadingIcon = Icons.Default.Add,
                            onClick = onOpenCreate,
                        )
                    }
                }
            } else {
                val (card, type) = selectedPair
                val tpl = card.template

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = tpl.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${templateTypeLabel(type)} · 已绑 ${card.activeReferenceCount} 位好友",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.inkTertiary,
                            )
                        }
                        FlameStatusPill(
                            text = if (tpl.enabled) "启用中" else "已停用",
                            tone = if (tpl.enabled) FlameTone.HEALTHY else FlameTone.WARNING,
                        )
                    }

                    // 模拟抖音私信聊天气泡效果
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = palette.surfaceSubtle,
                        border = BorderStroke(1.dp, palette.hairline),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "发往 ${previewTarget?.nicknameSnapshot ?: "示例好友"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.inkTertiary,
                                )
                                FlameMiniTag(
                                    text = "本机 ${previewTarget?.streakDays ?: 7} 天",
                                    tone = FlameTone.EMBER,
                                )
                            }

                            when (type) {
                                TemplateType.TEXT -> {
                                    val rendered = TemplatesViewModel.renderTemplatePreview(tpl.content, previewTarget)
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(
                                                topStart = 16.dp,
                                                topEnd = 16.dp,
                                                bottomStart = 16.dp,
                                                bottomEnd = 4.dp,
                                            ),
                                            color = palette.monoCtaContainer,
                                            contentColor = palette.monoCtaContent,
                                        ) {
                                            Text(
                                                text = rendered.ifBlank { "（空文本）" },
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                                            )
                                        }
                                    }
                                }

                                TemplateType.POOL -> {
                                    val nextText = card.nextPoolPreviewText
                                    if (!nextText.isNullOrBlank()) {
                                        val rendered = TemplatesViewModel.renderTemplatePreview(nextText, previewTarget)
                                        Text(
                                            text = "下一次轮换命中文案：",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.inkSecondary,
                                        )
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.CenterEnd,
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(
                                                    topStart = 16.dp,
                                                    topEnd = 16.dp,
                                                    bottomStart = 16.dp,
                                                    bottomEnd = 4.dp,
                                                ),
                                                color = palette.monoCtaContainer,
                                                contentColor = palette.monoCtaContent,
                                            ) {
                                                Text(
                                                    text = rendered,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                                                )
                                            }
                                        }
                                    } else {
                                        Text(
                                            text = "当前星期无可用启用文案，请在编辑器中添加或放开星期掩码。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = semantics.warning,
                                        )
                                    }
                                }

                                TemplateType.IMAGE -> {
                                    if (card.imageAvailable && tpl.imagePath != null) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.CenterEnd,
                                        ) {
                                            TemplateImageThumbnail(
                                                imagePath = tpl.imagePath,
                                                maxEdgePx = 420,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(width = 168.dp, height = 168.dp)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .border(1.dp, palette.hairlineStrong, RoundedCornerShape(14.dp)),
                                            )
                                        }
                                        card.imageSummary?.let { summary ->
                                            Text(
                                                text = "$summary · 已剥离 EXIF 隐私元数据",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = palette.inkTertiary,
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "本地图片缺失或来自跨机迁移，重新选图后可恢复发送。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = semantics.danger,
                                        )
                                    }
                                }

                                TemplateType.STICKER -> {
                                    val stickerTitle = tpl.stickerName?.takeIf { it.isNotBlank() } ?: tpl.name
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = palette.surface,
                                            border = BorderStroke(1.dp, palette.emberBorder),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.EmojiEmotions,
                                                    contentDescription = null,
                                                    tint = palette.ember,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                Text(
                                                    text = "[$stickerTitle]",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = palette.ink,
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = buildString {
                                            append("原生表情匹配：$stickerTitle")
                                            tpl.stickerCategory?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                                            tpl.stickerFallbackIndex?.let { append(" · 兜底 #$it") }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.inkTertiary,
                                    )
                                }
                            }
                        }
                    }

                    if (type == TemplateType.POOL && card.poolItems.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "文案池候选（${card.poolItems.size}）",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.inkSecondary,
                            )
                            card.poolItems.take(5).forEach { poolUi ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = palette.surfaceSubtle.copy(alpha = 0.65f),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = poolUi.item.content,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (poolUi.item.enabled) palette.ink else palette.inkTertiary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${poolUi.weekdaySummary} · ${poolUi.lastUsedSummary}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.inkTertiary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (card.poolItems.size > 5) {
                                Text(
                                    text = "还有 ${card.poolItems.size - 5} 条，点下方「编辑模板」查看全部",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.inkTertiary,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = palette.hairline)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        onClick = {
                            haptics.performFlameLightTick()
                            onEdit(tpl)
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = palette.monoCtaContainer,
                        contentColor = palette.monoCtaContent,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (type == TemplateType.IMAGE && !card.imageAvailable) "重新选图" else "编辑模板",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }

                    Switch(
                        checked = tpl.enabled,
                        onCheckedChange = { enabled -> onToggleEnabled(tpl, enabled) },
                    )

                    IconButton(
                        onClick = { onDelete(tpl) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "删除模板",
                            tint = palette.inkTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun buildTemplateGallerySnippet(
    card: TemplateCardUi,
    type: TemplateType,
    previewTarget: ContractTargetEntity?,
): String {
    val tpl = card.template
    return when (type) {
        TemplateType.POOL -> {
            val cleanPreview = card.nextPoolPreviewText
                ?.replace('\n', ' ')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (cleanPreview != null) {
                "下句：$cleanPreview"
            } else {
                "今日暂无可用启用文案"
            }
        }

        TemplateType.IMAGE -> {
            if (!card.imageAvailable) {
                "缺少本地图片，点击重新选图"
            } else {
                card.imageSummary ?: "本地图片就绪"
            }
        }

        TemplateType.TEXT -> {
            val cleanPreview = TemplatesViewModel.renderTemplatePreview(tpl.content, previewTarget)
                .replace('\n', ' ')
                .trim()
            "预览：$cleanPreview"
        }

        TemplateType.STICKER -> {
            val meta = buildList {
                tpl.stickerLabel?.takeIf { it.isNotBlank() && it != tpl.stickerName }?.let { add("标签 $it") }
                tpl.stickerCategory?.takeIf { it.isNotBlank() }?.let { add(it) }
                tpl.stickerFallbackIndex?.let { add("兜底 #$it") }
            }
            if (meta.isEmpty()) "抖音聊天原生表情 · 点击实时预览" else meta.joinToString(" · ")
        }
    }
}

private fun templateTypeLabel(type: TemplateType): String = when (type) {
    TemplateType.POOL -> "星期轮换文案池"
    TemplateType.IMAGE -> "本地图片模板"
    TemplateType.TEXT -> "固定文本模板"
    TemplateType.STICKER -> "抖音原生表情"
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
