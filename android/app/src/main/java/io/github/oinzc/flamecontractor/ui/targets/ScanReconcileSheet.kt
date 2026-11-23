package io.github.oinzc.flamecontractor.ui.targets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.core.identity.MatchStatus
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.execution.ScannedChatCandidate
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameSearchPill
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.FlameTopPillButton
import io.github.oinzc.flamecontractor.ui.components.resolveFlameToneColors
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScanReconcileSheet(
    reconcileState: ScanReconcileState,
    templates: List<MessageTemplateEntity>,
    onSearchChanged: (String) -> Unit,
    onDefaultTemplateChanged: (String?) -> Unit,
    onToggleCandidate: (ScannedChatCandidate) -> Unit,
    onSelectAllImportableSparks: () -> Unit,
    onClearSelection: () -> Unit,
    onImportSelected: () -> Unit,
    onSyncExistingSparks: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val isDark = FlameThemeTokens.isDark
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var templateMenuExpanded by remember { mutableStateOf(false) }
    val selectedTemplateName = remember(reconcileState.defaultTemplateId, templates) {
        templates.firstOrNull { it.id == reconcileState.defaultTemplateId }?.name
    }
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val maxSheetContentHeight = (screenHeightDp * 0.88f).coerceAtLeast(280f).dp

    ModalBottomSheet(
        onDismissRequest = { if (!reconcileState.importing) onDismiss() },
        sheetState = sheetState,
        sheetMaxWidth = 760.dp,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = palette.surface,
        contentColor = palette.ink,
        scrimColor = Color.Black.copy(alpha = if (isDark) 0.62f else 0.36f),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.hairlineStrong),
                )
            }
        },
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetContentHeight)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            val isWideSheet = maxWidth >= 520.dp

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 标题与扫描统计
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "扫描对账与导入",
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.ink,
                    )
                    val timeStr = reconcileState.scannedAtEpochMs?.let(::formatScanTimestamp) ?: "未知时间"
                    Text(
                        text = "共扫描 ${reconcileState.discoveredCount} 条会话 · 含官方火花 ${reconcileState.sparkCount} 条 · $timeStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )
                }

                if (reconcileState.errorMessage != null) {
                    Surface(
                        color = semantics.dangerContainer,
                        border = BorderStroke(1.dp, semantics.dangerBorder),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = reconcileState.errorMessage,
                            color = semantics.onDangerContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                // 宽屏（横屏/平板）将搜索栏与默认模板选择器并排放置，节省纵向高度让列表与底栏同步可见
                if (isWideSheet) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FlameSearchPill(
                            query = reconcileState.searchQuery,
                            onQueryChange = onSearchChanged,
                            placeholder = "搜索昵称、群名、抖音号或火花天数…",
                            modifier = Modifier.weight(1f),
                        )
                        ReconcileTemplateSelector(
                            selectedTemplateName = selectedTemplateName,
                            templates = templates,
                            expanded = templateMenuExpanded,
                            onExpandedChange = { templateMenuExpanded = it },
                            onDefaultTemplateChanged = onDefaultTemplateChanged,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    FlameSearchPill(
                        query = reconcileState.searchQuery,
                        onQueryChange = onSearchChanged,
                        placeholder = "搜索昵称、群名、抖音号或火花天数…",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ReconcileTemplateSelector(
                        selectedTemplateName = selectedTemplateName,
                        templates = templates,
                        expanded = templateMenuExpanded,
                        onExpandedChange = { templateMenuExpanded = it },
                        onDefaultTemplateChanged = onDefaultTemplateChanged,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FlameTopPillButton(
                        text = "全选可导入火花 (${reconcileState.importableSparkCount})",
                        primary = false,
                        enabled = reconcileState.importableSparkCount > 0 && !reconcileState.importing,
                        onClick = onSelectAllImportableSparks,
                    )
                    FlameTopPillButton(
                        text = "清空选择",
                        primary = false,
                        enabled = reconcileState.selectedCount > 0 && !reconcileState.importing,
                        onClick = onClearSelection,
                    )
                    if (reconcileState.alreadyImportedWithSparkCount > 0) {
                        FlameTopPillButton(
                            text = "同步已有火花 (${reconcileState.alreadyImportedWithSparkCount})",
                            primary = false,
                            enabled = !reconcileState.importing,
                            onClick = onSyncExistingSparks,
                        )
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = palette.hairline)

                // 候选列表按剩余可用高度自适应伸缩，确保底部按钮栏始终锚定可见
                if (reconcileState.filteredCandidates.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(vertical = 22.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (reconcileState.allCandidates.isEmpty()) {
                                "暂无扫描对账数据，请先点击「扫描对账」重新扫描"
                            } else {
                                "没有匹配当前搜索关键词的会话"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkSecondary,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = reconcileState.filteredCandidates,
                            key = { it.key },
                        ) { item ->
                            ScanCandidateRow(
                                item = item,
                                busy = reconcileState.importing,
                                onToggle = { onToggleCandidate(item.candidate) },
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = palette.hairline)

                // 底部提交操作栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = onDismiss,
                        enabled = !reconcileState.importing,
                        modifier = Modifier.heightIn(min = 44.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = palette.surfaceSubtle,
                        contentColor = if (reconcileState.importing) palette.inkTertiary else palette.ink,
                        border = BorderStroke(1.dp, palette.hairline),
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "关闭",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }

                    val importEnabled = reconcileState.selectedCount > 0 && !reconcileState.importing
                    Surface(
                        onClick = onImportSelected,
                        enabled = importEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = if (importEnabled) {
                            palette.monoCtaContainer
                        } else {
                            palette.surfaceSubtle
                        },
                        contentColor = if (importEnabled) {
                            palette.monoCtaContent
                        } else {
                            palette.inkTertiary
                        },
                        border = BorderStroke(
                            1.dp,
                            if (importEnabled) palette.monoCtaContainer else palette.hairline,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (reconcileState.importing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp,
                                    color = palette.inkTertiary,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "导入中…",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            } else {
                                Text(
                                    text = "导入所选 (${reconcileState.selectedCount})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReconcileTemplateSelector(
    selectedTemplateName: String?,
    templates: List<MessageTemplateEntity>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDefaultTemplateChanged: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { onExpandedChange(!expanded) },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedTemplateName ?: "导入后暂不绑定模板",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            label = { Text("导入时默认绑定模板") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            DropdownMenuItem(
                text = { Text("暂不绑定模板") },
                onClick = {
                    onDefaultTemplateChanged(null)
                    onExpandedChange(false)
                },
            )
            templates.forEach { tpl ->
                DropdownMenuItem(
                    text = { Text(tpl.name) },
                    onClick = {
                        onDefaultTemplateChanged(tpl.id)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanCandidateRow(
    item: ScanCandidateUi,
    busy: Boolean,
    onToggle: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val c = item.candidate

    val containerColor = when {
        item.isAmbiguous -> semantics.dangerContainer.copy(alpha = 0.65f)
        item.effectivelyAlreadyImported -> palette.surfaceSubtle.copy(alpha = 0.72f)
        item.selected -> palette.emberContainer.copy(alpha = 0.52f)
        else -> palette.surfaceSubtle
    }
    val borderColor = when {
        item.isAmbiguous -> semantics.dangerBorder
        item.selected -> palette.emberBorder
        else -> palette.hairline
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (item.selectable && !busy) {
                    Modifier.clickable(onClick = onToggle)
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = item.selected,
                onCheckedChange = if (item.selectable && !busy) {
                    { onToggle() }
                } else {
                    null
                },
                enabled = item.selectable && !busy,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = c.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    FlameMiniTag(
                        text = if (c.conversationType == "group") "群聊" else "好友",
                        tone = FlameTone.NEUTRAL,
                    )
                    if (!c.platformHandle.isNullOrBlank()) {
                        Text(
                            text = "@${c.platformHandle}",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (item.hasOfficialSpark) {
                        val emberTone = resolveFlameToneColors(FlameTone.EMBER)
                        Surface(
                            color = emberTone.container,
                            border = BorderStroke(1.dp, emberTone.border),
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.LocalFireDepartment,
                                    contentDescription = null,
                                    tint = emberTone.fg,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = formatCandidateSparkText(c),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = emberTone.fg,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    when {
                        item.isAmbiguous -> {
                            val dangerTone = resolveFlameToneColors(FlameTone.DANGER)
                            Surface(
                                color = dangerTone.container,
                                border = BorderStroke(1.dp, dangerTone.border),
                                shape = RoundedCornerShape(999.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.WarningAmber,
                                        contentDescription = null,
                                        tint = dangerTone.fg,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = "同名冲突 · 禁选",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = dangerTone.onContainer,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }

                        item.effectivelyAlreadyImported -> {
                            val healthyTone = resolveFlameToneColors(FlameTone.HEALTHY)
                            Surface(
                                color = healthyTone.container,
                                border = BorderStroke(1.dp, healthyTone.border),
                                shape = RoundedCornerShape(999.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = healthyTone.fg,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = "已导入",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = healthyTone.onContainer,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }

                        c.matchStatus == MatchStatus.NOT_OBSERVED -> {
                            FlameMiniTag(
                                text = "未观察到稳定 ID",
                                tone = FlameTone.WARNING,
                            )
                        }
                    }
                }

                Text(
                    text = item.statusNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isAmbiguous) semantics.danger else palette.inkSecondary,
                )
            }
        }
    }
}

private fun formatCandidateSparkText(candidate: ScannedChatCandidate): String {
    val kindPrefix = when (candidate.sparkKind) {
        "couple" -> "合养火花"
        "family" -> "家庭火花"
        else -> "官方火花"
    }
    val days = candidate.sparkDays
    val label = candidate.sparkLabel?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        days != null && days > 0 -> "$kindPrefix ${days}天"
        label != null -> "$kindPrefix · $label"
        else -> kindPrefix
    }
}

private val SCAN_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatScanTimestamp(epochMs: Long): String {
    val zdt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return SCAN_TIME_FORMATTER.format(zdt)
}
