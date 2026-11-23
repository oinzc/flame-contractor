package io.github.oinzc.flamecontractor.ui.templates

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.core.storage.TemplateImageStoreUtils
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameSheetActionRow
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.FlameTopPillButton
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateEditorScreen(
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
    onApplyStickerPreset: (String) -> Unit,
    onPickImage: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onAddPoolDraft: () -> Unit,
    onRemovePoolDraft: (Int) -> Unit,
    onPoolDraftContentChanged: (Int, String) -> Unit,
    onTogglePoolDraftWeekday: (Int, Int) -> Unit,
    onSetPoolDraftEveryday: (Int) -> Unit,
    onTogglePoolDraftEnabled: (Int, Boolean) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    onRequestDelete: (() -> Unit)? = null,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val canSave = !busy &&
        !editor.isImportingImage &&
        (editor.type != TemplateType.IMAGE || editor.imageAvailable)

    val topTitle = if (editor.isNew) {
        "新建${templateTypeShortLabel(editor.type)}"
    } else {
        "编辑${templateTypeShortLabel(editor.type)}"
    }

    FlameSubpageScaffold(
        title = topTitle,
        onBack = {
            if (!busy) {
                onDismiss()
            }
        },
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        trailingAction = {
            FlameTopPillButton(
                text = if (busy) "保存中…" else "保存",
                onClick = onSave,
                primary = true,
                enabled = canSave,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (editor.errorMessage != null) {
                    Surface(
                        color = semantics.dangerContainer,
                        border = BorderStroke(1.dp, semantics.dangerBorder),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.WarningAmber,
                                contentDescription = null,
                                tint = semantics.danger,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = editor.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = semantics.onDangerContainer,
                            )
                        }
                    }
                }

                // 公共基本信息卡片
                FlameCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(15.dp),
                ) {
                    Text(
                        text = "基本信息",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkTertiary,
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = editor.name,
                        onValueChange = onNameChanged,
                        label = { Text("模板名称") },
                        placeholder = { Text("例如：日常打卡 / 周末问候") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = flameEditorTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (editor.isNew) {
                        Text(
                            text = "模板类型",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkTertiary,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        TemplateTypePickerRow(
                            selected = editor.type,
                            enabled = !busy && !editor.isImportingImage,
                            onSelect = onTypeChanged,
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "模板类型",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.inkSecondary,
                            )
                            FlameMiniTag(
                                text = templateTypeFullLabel(editor.type),
                                tone = templateTypeTone(editor.type),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(thickness = 1.dp, color = palette.hairline)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "启用此模板",
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.ink,
                            )
                            Text(
                                text = "关闭后将暂停绑定此模板好友的自动发送",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = editor.enabled,
                            enabled = !busy,
                            onCheckedChange = onEnabledChanged,
                        )
                    }
                }

                // 按 4 种模板类型呈现专属编辑区
                when (editor.type) {
                    TemplateType.TEXT -> {
                        TextTemplateEditorSection(
                            content = editor.content,
                            previewTarget = previewTarget,
                            onContentChanged = onContentChanged,
                        )
                    }

                    TemplateType.POOL -> {
                        PoolTemplateEditorSection(
                            poolDrafts = editor.poolDrafts,
                            previewTarget = previewTarget,
                            onAddPoolDraft = onAddPoolDraft,
                            onRemovePoolDraft = onRemovePoolDraft,
                            onPoolDraftContentChanged = onPoolDraftContentChanged,
                            onTogglePoolDraftWeekday = onTogglePoolDraftWeekday,
                            onSetPoolDraftEveryday = onSetPoolDraftEveryday,
                            onTogglePoolDraftEnabled = onTogglePoolDraftEnabled,
                        )
                    }

                    TemplateType.STICKER -> {
                        StickerTemplateEditorSection(
                            editor = editor,
                            onStickerNameChanged = onStickerNameChanged,
                            onStickerLabelChanged = onStickerLabelChanged,
                            onStickerCategoryChanged = onStickerCategoryChanged,
                            onStickerFallbackIndexChanged = onStickerFallbackIndexChanged,
                            onApplyStickerPreset = onApplyStickerPreset,
                        )
                    }

                    TemplateType.IMAGE -> {
                        ImageTemplateEditorSection(
                            editor = editor,
                            busy = busy,
                            onPickImage = onPickImage,
                        )
                    }
                }

                if (!editor.isNew && onRequestDelete != null) {
                    FlameSheetActionRow(
                        text = "删除此模板",
                        trailingText = "解绑关联好友",
                        onClick = onRequestDelete,
                        danger = true,
                        enabled = !busy && !editor.isImportingImage,
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun TemplateTypePickerRow(
    selected: TemplateType,
    enabled: Boolean,
    onSelect: (TemplateType) -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val options = remember {
        listOf(
            TemplateType.POOL to "文案池",
            TemplateType.IMAGE to "图片",
            TemplateType.TEXT to "文本",
            TemplateType.STICKER to "表情",
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surfaceSubtle)
            .border(1.dp, palette.hairline, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (type, label) ->
            val isSelected = type == selected
            val bg by animateColorAsState(
                targetValue = if (isSelected) palette.monoCtaContainer else palette.surfaceSubtle,
                animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                label = "tpl_type_bg_${type.name}",
            )
            val fg by animateColorAsState(
                targetValue = if (isSelected) palette.monoCtaContent else palette.inkSecondary,
                animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                label = "tpl_type_fg_${type.name}",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .clickable(enabled = enabled) { onSelect(type) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (enabled) fg else palette.inkTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextTemplateEditorSection(
    content: String,
    previewTarget: ContractTargetEntity?,
    onContentChanged: (String) -> Unit,
) {
    val palette = FlameThemeTokens.palette

    FlameCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(15.dp),
    ) {
        Text(
            text = "固定文本内容",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.inkTertiary,
        )
        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = content,
            onValueChange = onContentChanged,
            label = { Text("发送文案") },
            placeholder = { Text("例如：早安 {{target_name}}，连续续火第 {{streak_days}} 天 🔥") },
            minLines = 3,
            maxLines = 7,
            shape = RoundedCornerShape(14.dp),
            colors = flameEditorTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "一键插入动态变量",
            style = MaterialTheme.typography.labelSmall,
            color = palette.inkTertiary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        VariableInsertChipFlow(
            onInsertToken = { token -> onContentChanged(content + token) },
        )
    }

    // 虚线实时只读预览卡片
    DashedReadOnlyPreviewCard(
        content = content,
        previewTarget = previewTarget,
    )
}

@Composable
private fun DashedReadOnlyPreviewCard(
    content: String,
    previewTarget: ContractTargetEntity?,
) {
    val palette = FlameThemeTokens.palette
    val rendered = remember(content, previewTarget) {
        if (content.isBlank()) {
            ""
        } else {
            TemplatesViewModel.renderTemplatePreview(content, previewTarget)
        }
    }
    val targetLabel = previewTarget?.let {
        "${it.nicknameSnapshot} · ${it.streakDays} 天"
    } ?: "测试好友 · 7 天"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.surfaceSubtle.copy(alpha = 0.65f))
            .drawBehind {
                val strokeWidth = 1.2.dp.toPx()
                val radius = 18.dp.toPx()
                drawRoundRect(
                    color = palette.hairlineStrong,
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(
                        width = strokeWidth,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f),
                    ),
                )
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Visibility,
                    contentDescription = null,
                    tint = palette.inkSecondary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "实时只读预览",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkSecondary,
                )
            }
            FlameMiniTag(text = targetLabel, tone = FlameTone.NEUTRAL)
        }

        if (rendered.isNotBlank()) {
            Text(
                text = rendered,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.ink,
            )
        } else {
            Text(
                text = "输入文案后将在此实时预览变量替换结果",
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }

        Text(
            text = "仅用于只读预览，不写入发送记录或改变轮换顺位",
            style = MaterialTheme.typography.labelSmall,
            color = palette.inkTertiary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PoolTemplateEditorSection(
    poolDrafts: List<PoolDraftItem>,
    previewTarget: ContractTargetEntity?,
    onAddPoolDraft: () -> Unit,
    onRemovePoolDraft: (Int) -> Unit,
    onPoolDraftContentChanged: (Int, String) -> Unit,
    onTogglePoolDraftWeekday: (Int, Int) -> Unit,
    onSetPoolDraftEveryday: (Int) -> Unit,
    onTogglePoolDraftEnabled: (Int, Boolean) -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "轮换文案条目 (${poolDrafts.size})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkTertiary,
            )
            Text(
                text = "支持「每天」或独立多选「一..日」（全选 7 天自动保存为每天）；按最久未用顺序自动轮换。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkSecondary,
            )
        }

        poolDrafts.forEachIndexed { idx, draft ->
            FlameCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "文案 #${idx + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.ink,
                    )
                    FlameMiniTag(
                        text = TemplatesViewModel.formatWeekdayMask(draft.weekdayMask),
                        tone = FlameTone.NEUTRAL,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = { onTogglePoolDraftEnabled(idx, it) },
                    )
                    if (poolDrafts.size > 1) {
                        IconButton(
                            onClick = { onRemovePoolDraft(idx) },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "删除此条轮换文案",
                                tint = semantics.danger,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = draft.content,
                    onValueChange = { onPoolDraftContentChanged(idx, it) },
                    placeholder = { Text("输入候选文案，支持插入 {{target_name}} 等变量") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp),
                    colors = flameEditorTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                VariableInsertChipFlow(
                    onInsertToken = { token ->
                        onPoolDraftContentChanged(idx, draft.content + token)
                    },
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "生效星期",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkTertiary,
                )
                Spacer(modifier = Modifier.height(6.dp))
                WeekdayMaskChipRow(
                    weekdayMask = draft.weekdayMask,
                    onSelectEveryday = { onSetPoolDraftEveryday(idx) },
                    onToggleDay = { dayIndex -> onTogglePoolDraftWeekday(idx, dayIndex) },
                )

                if (draft.content.contains("{{")) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = palette.surfaceSubtle,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "预览：${TemplatesViewModel.renderTemplatePreview(draft.content, previewTarget)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(thickness = 1.dp, color = palette.hairline)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = TemplatesViewModel.formatLastUsedAt(draft.lastUsedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkTertiary,
                    )
                    Text(
                        text = "只读 · 编辑不改顺位",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkTertiary,
                    )
                }
            }
        }

        Surface(
            onClick = onAddPoolDraft,
            shape = RoundedCornerShape(16.dp),
            color = palette.surface,
            contentColor = palette.ink,
            border = BorderStroke(1.dp, palette.hairlineStrong),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "添加一条轮换文案",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StickerTemplateEditorSection(
    editor: TemplateEditorState,
    onStickerNameChanged: (String) -> Unit,
    onStickerLabelChanged: (String) -> Unit,
    onStickerCategoryChanged: (String) -> Unit,
    onStickerFallbackIndexChanged: (String) -> Unit,
    onApplyStickerPreset: (String) -> Unit,
) {
    val palette = FlameThemeTokens.palette

    FlameCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(15.dp),
    ) {
        Text(
            text = "常用表情快捷预设",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.inkTertiary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TemplatesViewModel.PRESET_STICKER_NAMES.forEach { preset ->
                val selected = editor.stickerName.trim() == preset
                Surface(
                    onClick = { onApplyStickerPreset(preset) },
                    shape = RoundedCornerShape(999.dp),
                    color = if (selected) palette.monoCtaContainer else palette.surfaceSubtle,
                    contentColor = if (selected) palette.monoCtaContent else palette.ink,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (selected) palette.monoCtaContainer else palette.hairline,
                    ),
                ) {
                    Text(
                        text = preset,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }

    FlameCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(15.dp),
    ) {
        Text(
            text = "抖音原生表情参数",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.inkTertiary,
        )
        Spacer(modifier = Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = editor.stickerName,
                onValueChange = onStickerNameChanged,
                label = { Text("抖音原生表情名称（必填）") },
                placeholder = { Text("例如：比心 / 早上好 / 续火花") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = flameEditorTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = editor.stickerLabel,
                onValueChange = onStickerLabelChanged,
                label = { Text("展示标签（可选）") },
                placeholder = { Text("留空则沿用表情名称") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = flameEditorTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = editor.stickerCategory,
                onValueChange = onStickerCategoryChanged,
                label = { Text("表情分类（可选）") },
                placeholder = { Text("例如：常用") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = flameEditorTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = editor.stickerFallbackIndexInput,
                onValueChange = onStickerFallbackIndexChanged,
                label = { Text("兜底索引（可选非负整数）") },
                placeholder = { Text("例如：0（名称匹配失败时按位置兜底）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(14.dp),
                colors = flameEditorTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ImageTemplateEditorSection(
    editor: TemplateEditorState,
    busy: Boolean,
    onPickImage: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val isMissingRecovery = !editor.imageAvailable && (!editor.isNew || !editor.imagePath.isNullOrBlank())

    FlameCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(15.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "本地图片",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkTertiary,
            )
            when {
                editor.isImportingImage -> FlameStatusPill(text = "导入处理中", tone = FlameTone.EMBER)
                editor.imageAvailable -> FlameStatusPill(text = "图片就绪", tone = FlameTone.HEALTHY)
                isMissingRecovery -> FlameStatusPill(text = "待补图", tone = FlameTone.DANGER)
                else -> FlameStatusPill(text = "未选择", tone = FlameTone.NEUTRAL)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (editor.isImportingImage) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp)),
                color = palette.ember,
                trackColor = palette.surfaceSubtle,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "正在缩放压缩并剥离 EXIF 隐私元数据…",
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkSecondary,
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        when {
            editor.imageAvailable && !editor.imagePath.isNullOrBlank() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(216.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.surfaceSubtle)
                        .border(1.dp, palette.hairline, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    TemplateImageThumbnail(
                        imagePath = editor.imagePath,
                        maxEdgePx = 768,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    color = palette.surfaceSubtle,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = TemplatesViewModel.formatImageSummary(
                                mimeType = editor.imageMimeType,
                                sizeBytes = editor.imageSizeBytes,
                                widthPx = editor.imageWidthPx,
                                heightPx = editor.imageHeightPx,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.ink,
                        )
                        Text(
                            text = "已剥离 EXIF 隐私元数据并保存在应用私有目录，跨端备份不会上传原图字节。",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                        )
                    }
                }
            }

            isMissingRecovery -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(semantics.dangerContainer)
                        .border(1.dp, semantics.dangerBorder, RoundedCornerShape(18.dp))
                        .padding(horizontal = 18.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BrokenImage,
                        contentDescription = null,
                        tint = semantics.danger,
                        modifier = Modifier.size(34.dp),
                    )
                    Text(
                        text = "缺少本地图片，请重新选择",
                        style = MaterialTheme.typography.titleSmall,
                        color = semantics.onDangerContainer,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "原图片文件丢失或来自脱敏备份迁移。重新选图并保存后即可恢复对应好友的自动发送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = semantics.onDangerContainer.copy(alpha = 0.88f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.surfaceSubtle.copy(alpha = 0.65f))
                        .drawBehind {
                            val radius = 18.dp.toPx()
                            drawRoundRect(
                                color = palette.hairlineStrong,
                                cornerRadius = CornerRadius(radius, radius),
                                style = Stroke(
                                    width = 1.2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f),
                                ),
                            )
                        }
                        .clickable(enabled = !busy && !editor.isImportingImage, onClick = onPickImage)
                        .padding(horizontal = 18.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AddPhotoAlternate,
                            contentDescription = null,
                            tint = palette.inkSecondary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Text(
                        text = "尚未选择本地图片",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.ink,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "支持 JPG / PNG / WebP（≤15 MB，自动缩至 2048px 并剥离 EXIF）及 GIF 动图（≤5 MB）",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = onPickImage,
            enabled = !busy && !editor.isImportingImage,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.monoCtaContainer,
                contentColor = palette.monoCtaContent,
                disabledContainerColor = palette.monoCtaContainer.copy(alpha = 0.42f),
                disabledContentColor = palette.monoCtaContent.copy(alpha = 0.72f),
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            if (editor.isImportingImage) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = palette.monoCtaContent,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "正在导入图片…",
                    style = MaterialTheme.typography.labelLarge,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (editor.imageAvailable) "从相册更换图片" else "从相册选择图片",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariableInsertChipFlow(
    onInsertToken: (String) -> Unit,
) {
    val palette = FlameThemeTokens.palette
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TemplatesViewModel.TEMPLATE_VARIABLES.forEach { (token, desc) ->
            Surface(
                onClick = { onInsertToken(token) },
                shape = RoundedCornerShape(10.dp),
                color = palette.surfaceSubtle,
                contentColor = palette.ink,
                border = BorderStroke(1.dp, palette.hairline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "+ $desc",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.ink,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        text = token,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = palette.inkTertiary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayMaskChipRow(
    weekdayMask: Int,
    onSelectEveryday: () -> Unit,
    onToggleDay: (Int) -> Unit,
) {
    val isEveryday = TemplatesViewModel.isEverydayMask(weekdayMask)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WeekdayCompactChip(
            label = "每天",
            selected = isEveryday,
            onClick = onSelectEveryday,
        )
        TemplatesViewModel.WEEKDAY_SHORT_LABELS.forEachIndexed { dayIndex, shortLabel ->
            val checked = TemplatesViewModel.isSpecificDayChecked(weekdayMask, dayIndex)
            WeekdayCompactChip(
                label = shortLabel,
                selected = checked,
                onClick = { onToggleDay(dayIndex) },
            )
        }
    }
}

@Composable
private fun WeekdayCompactChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val containerColor by animateColorAsState(
        targetValue = if (selected) palette.monoCtaContainer else palette.surfaceSubtle,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "weekday_chip_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) palette.monoCtaContent else palette.inkSecondary,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "weekday_chip_fg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) palette.monoCtaContainer else palette.hairline,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "weekday_chip_border",
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun flameEditorTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = FlameThemeTokens.palette.monoCtaContainer,
    unfocusedBorderColor = FlameThemeTokens.palette.hairlineStrong,
    focusedLabelColor = FlameThemeTokens.palette.ink,
    unfocusedLabelColor = FlameThemeTokens.palette.inkSecondary,
    cursorColor = FlameThemeTokens.palette.monoCtaContainer,
    focusedTextColor = FlameThemeTokens.palette.ink,
    unfocusedTextColor = FlameThemeTokens.palette.ink,
)

@Composable
internal fun TemplateImageThumbnail(
    imagePath: String?,
    maxEdgePx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val palette = FlameThemeTokens.palette
    var bitmap by remember(imagePath, maxEdgePx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(imagePath, maxEdgePx) {
        bitmap = if (imagePath.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                decodeSampledThumbnail(imagePath = imagePath, maxEdgePx = maxEdgePx)
            }
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap,
            contentDescription = "模板图片缩略图",
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(palette.surfaceSubtle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = palette.inkTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun decodeSampledThumbnail(imagePath: String, maxEdgePx: Int): ImageBitmap? {
    return try {
        val file = File(imagePath)
        if (!file.isFile || file.length() <= 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = TemplateImageStoreUtils.computeInSampleSize(
                widthPx = bounds.outWidth,
                heightPx = bounds.outHeight,
                maxEdgePx = maxEdgePx,
            )
        }
        BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

internal fun templateTypeShortLabel(type: TemplateType): String = when (type) {
    TemplateType.POOL -> "文案池"
    TemplateType.IMAGE -> "图片模板"
    TemplateType.TEXT -> "固定文本"
    TemplateType.STICKER -> "原生表情"
}

internal fun templateTypeFullLabel(type: TemplateType): String = when (type) {
    TemplateType.POOL -> "星期轮换文案池"
    TemplateType.IMAGE -> "本地图片模板"
    TemplateType.TEXT -> "固定文本模板"
    TemplateType.STICKER -> "抖音原生表情"
}

internal fun templateTypeTone(type: TemplateType): FlameTone = when (type) {
    TemplateType.POOL -> FlameTone.NEUTRAL
    TemplateType.IMAGE -> FlameTone.HEALTHY
    TemplateType.TEXT -> FlameTone.NEUTRAL
    TemplateType.STICKER -> FlameTone.WARNING
}
