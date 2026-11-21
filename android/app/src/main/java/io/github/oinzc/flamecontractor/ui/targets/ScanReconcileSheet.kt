package io.github.oinzc.flamecontractor.ui.targets

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.core.identity.MatchStatus
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.execution.ScannedChatCandidate
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var templateMenuExpanded by remember { mutableStateOf(false) }
    val selectedTemplateName = remember(reconcileState.defaultTemplateId, templates) {
        templates.firstOrNull { it.id == reconcileState.defaultTemplateId }?.name
    }
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val maxSheetContentHeight = (screenHeightDp - 108).coerceAtLeast(280).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 760.dp,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetContentHeight)
                .imePadding(),
        ) {
            val isWideSheet = maxWidth >= 520.dp

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 标题与扫描统计
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "扫描对账与导入",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val timeStr = reconcileState.scannedAtEpochMs?.let(::formatScanTimestamp) ?: "未知时间"
                    Text(
                        text = "共扫描 ${reconcileState.discoveredCount} 条会话 · 含官方火花 ${reconcileState.sparkCount} 条 · $timeStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (reconcileState.errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = reconcileState.errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                // 宽屏（横屏/平板）将搜索框与默认模板选择器并排放置，节省纵向高度让列表与底栏同步可见
                if (isWideSheet) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        ReconcileSearchField(
                            query = reconcileState.searchQuery,
                            onQueryChanged = onSearchChanged,
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
                    ReconcileSearchField(
                        query = reconcileState.searchQuery,
                        onQueryChanged = onSearchChanged,
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
                    OutlinedButton(
                        onClick = onSelectAllImportableSparks,
                        enabled = reconcileState.importableSparkCount > 0 && !reconcileState.importing,
                    ) {
                        Text("全选可导入火花对象 (${reconcileState.importableSparkCount})")
                    }
                    TextButton(
                        onClick = onClearSelection,
                        enabled = reconcileState.selectedCount > 0 && !reconcileState.importing,
                    ) {
                        Text("清空选择")
                    }
                    if (reconcileState.alreadyImportedWithSparkCount > 0) {
                        TextButton(
                            onClick = onSyncExistingSparks,
                            enabled = !reconcileState.importing,
                        ) {
                            Text("同步已有火花 (${reconcileState.alreadyImportedWithSparkCount})")
                        }
                    }
                }

                HorizontalDivider()

                // 候选列表按剩余可用高度自适应伸缩，确保底部按钮栏始终锚定可见
                if (reconcileState.filteredCandidates.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (reconcileState.allCandidates.isEmpty()) {
                                "暂无扫描对账数据，请先点击「手动扫描同步」"
                            } else {
                                "没有匹配当前搜索关键词的会话"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

                HorizontalDivider()

                // 底部提交操作栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !reconcileState.importing,
                    ) {
                        Text("关闭")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onImportSelected,
                        enabled = reconcileState.selectedCount > 0 && !reconcileState.importing,
                    ) {
                        if (reconcileState.importing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导入中…")
                        } else {
                            Text("导入所选 (${reconcileState.selectedCount})")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReconcileSearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier,
        singleLine = true,
        label = { Text("搜索会话") },
        placeholder = { Text("搜索昵称、群名、抖音号或火花天数…") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChanged("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "清空搜索")
                }
            }
        },
    )
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
    val c = item.candidate
    val containerColor = when {
        item.isAmbiguous -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        item.effectivelyAlreadyImported -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        item.selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.medium,
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
                .padding(horizontal = 10.dp, vertical = 10.dp),
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
                    .padding(start = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = c.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = if (c.conversationType == "group") "群聊" else "好友",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (!c.platformHandle.isNullOrBlank()) {
                        Text(
                            text = "@${c.platformHandle}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocalFireDepartment,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = formatCandidateSparkText(c),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }

                    when {
                        item.isAmbiguous -> {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = "同名冲突 · 禁选",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }

                        item.effectivelyAlreadyImported -> {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = "已导入",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }

                        c.matchStatus == MatchStatus.NOT_OBSERVED -> {
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                label = {
                                    Text(
                                        text = "未观察到稳定 ID",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
                }

                Text(
                    text = item.statusNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isAmbiguous) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
