package io.github.oinzc.flamecontractor.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.ui.components.FlameFilterChipItem
import io.github.oinzc.flamecontractor.ui.components.FlameFilterChipRow
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameModalSheet
import io.github.oinzc.flamecontractor.ui.components.FlameSheetActionRow
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsDiagnosticsPage(
    uiState: SettingsUiState,
    onTriggerStructureProbe: () -> Unit,
    onOpenProbeReportDialog: () -> Unit,
    onDismissProbeDialog: () -> Unit,
    onConfirmRecoverDomBroken: () -> Unit,
    onOpenOverrideEditor: () -> Unit,
    onDismissOverrideDialog: () -> Unit,
    onOverrideJsonInputChanged: (String) -> Unit,
    onSaveSelectorOverrides: () -> Unit,
    onClearSelectorOverrides: () -> Unit,
    onGenerateDiagnosticsReport: () -> Unit,
    onDismissDiagnosticsDialog: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
    onShareDiagnostics: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette

    FlameSubpageScaffold(
        title = SettingsSection.DIAGNOSTICS.title,
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        SettingsPageScrollColumn(contentPadding = innerPadding) {
            if (uiState.accountStatus == AccountStatus.DOM_BROKEN) {
                SettingsFeedbackBanner(
                    message = "当前账号处于页面结构异常（DOM_BROKEN）。请运行「网页结构体检」或保存有效「定位器覆写」以恢复运行。",
                    tone = FlameTone.DANGER,
                )
            }
            uiState.domRecoveryMessage?.let { msg ->
                SettingsFeedbackBanner(message = msg, tone = FlameTone.HEALTHY)
            }

            // 1. 只读网页结构体检
            val latestProbe = uiState.latestProbeReport
            FlameGroupedSection(
                title = "网页结构体检（只读）",
                trailingHeader = if (latestProbe != null) {
                    {
                        FlameStatusPill(
                            text = if (latestProbe.coreSkeletonOk) "核心骨架正常" else "骨架异常",
                            tone = if (latestProbe.coreSkeletonOk) FlameTone.HEALTHY else FlameTone.DANGER,
                            showDot = false,
                        )
                    }
                } else {
                    null
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "所有诊断严格只读，仅在离屏 WebView 核对页面骨架定位器，绝不写入聊天字符或触发发送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )

                    if (uiState.isProbeRunning) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(999.dp)),
                            color = palette.monoCtaContainer,
                            trackColor = palette.surfaceSubtle,
                        )
                    }

                    uiState.probeRuntimeMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                        )
                    }

                    if (latestProbe != null) {
                        val hitCount = latestProbe.items.count { it.hit }
                        Surface(
                            color = palette.surfaceSubtle,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, palette.hairline),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = latestProbe.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.ink,
                                )
                                Text(
                                    text = "命中 $hitCount/${latestProbe.items.size} · 登录态 ${if (latestProbe.loggedIn) "已登录" else "未登录"} · ${formatSettingsEpochMs(latestProbe.checkedAtEpochMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.inkTertiary,
                                )
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsPrimaryButton(
                            text = if (uiState.isProbeRunning) "正在体检…" else "运行网页结构体检",
                            leadingIcon = Icons.Outlined.VerifiedUser,
                            loading = uiState.isProbeRunning,
                            enabled = !uiState.isProbeRunning,
                            onClick = onTriggerStructureProbe,
                        )
                        if (latestProbe != null) {
                            SettingsSecondaryButton(
                                text = "查看体检报告",
                                onClick = onOpenProbeReportDialog,
                            )
                        }
                    }
                }
            }

            // 2. Selector 定位器摘要与热覆写
            FlameGroupedSection(
                title = "Selector 定位器基线与覆写",
                trailingHeader = {
                    FlameStatusPill(
                        text = if (uiState.hasSelectorOverrides) "已启用本地覆写" else "默认基线",
                        tone = if (uiState.hasSelectorOverrides) FlameTone.NEUTRAL else FlameTone.HEALTHY,
                        showDot = false,
                    )
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        color = palette.surfaceSubtle,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, palette.hairline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "当前定位器 12 位摘要",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.inkTertiary,
                            )
                            Text(
                                text = uiState.selectorDigest.ifEmpty { "加载中" },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = palette.ink,
                            )
                        }
                    }

                    uiState.selectorMessage?.let { msg ->
                        SettingsFeedbackBanner(message = msg, tone = FlameTone.HEALTHY)
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsSecondaryButton(
                            text = "编辑覆写 JSON",
                            leadingIcon = Icons.Outlined.Code,
                            enabled = !uiState.isSavingOverrides,
                            onClick = onOpenOverrideEditor,
                        )
                        if (uiState.hasSelectorOverrides) {
                            SettingsDangerTextButton(
                                text = "清空覆写并恢复默认",
                                enabled = !uiState.isSavingOverrides,
                                onClick = onClearSelectorOverrides,
                            )
                        }
                    }
                }
            }

            // 3. 脱敏诊断包
            FlameGroupedSection(title = "脱敏诊断包") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "仅导出应用与 WebView 版本、定位器摘要、账号状态枚举、保活状态及尝试计数；绝不包含昵称、UID、Cookie、消息正文与完整 Bark Key。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )

                    uiState.diagnosticsError?.let { err ->
                        SettingsFeedbackBanner(message = err, tone = FlameTone.DANGER)
                    }

                    SettingsPrimaryButton(
                        text = if (uiState.isBuildingDiagnostics) "正在生成…" else "导出脱敏诊断报告",
                        leadingIcon = Icons.Outlined.BugReport,
                        loading = uiState.isBuildingDiagnostics,
                        enabled = !uiState.isBuildingDiagnostics,
                        onClick = onGenerateDiagnosticsReport,
                    )
                }
            }
        }
    }

    if (uiState.showProbeDialog) {
        StructureProbeReportSheet(
            uiState = uiState,
            onDismiss = onDismissProbeDialog,
            onRetrigger = onTriggerStructureProbe,
            onConfirmRecoverDomBroken = onConfirmRecoverDomBroken,
        )
    }

    if (uiState.showOverrideDialog) {
        SelectorOverrideEditorSheet(
            uiState = uiState,
            onDismiss = onDismissOverrideDialog,
            onJsonChanged = onOverrideJsonInputChanged,
            onSave = onSaveSelectorOverrides,
            onClearOverrides = {
                onClearSelectorOverrides()
                onDismissOverrideDialog()
            },
        )
    }

    val diagnostics = uiState.diagnosticsReport
    if (uiState.showDiagnosticsDialog && diagnostics != null) {
        SanitizedDiagnosticsSheet(
            report = diagnostics,
            onDismiss = onDismissDiagnosticsDialog,
            onCopy = onCopyDiagnostics,
            onShare = onShareDiagnostics,
        )
    }
}

@Composable
internal fun StructureProbeReportSheet(
    uiState: SettingsUiState,
    onDismiss: () -> Unit,
    onRetrigger: () -> Unit,
    onConfirmRecoverDomBroken: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val report = uiState.latestProbeReport
    val canManualRecoverDomBroken = report != null &&
        report.coreSkeletonOk &&
        uiState.accountStatus == AccountStatus.DOM_BROKEN

    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = "网页只读结构体检",
        subtitle = "严格只读探测页面元素，绝不写入字符或点击发送",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (uiState.isProbeRunning && report == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 14.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = palette.monoCtaContainer,
                    )
                    Text(
                        text = uiState.probeRuntimeMessage ?: "正在后台离屏 WebView 核对页面结构…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.ink,
                    )
                }
            } else if (report == null) {
                Text(
                    text = uiState.probeRuntimeMessage ?: "暂未收到体检报告。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkSecondary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                SettingsFeedbackBanner(
                    message = report.summary,
                    tone = if (report.coreSkeletonOk) FlameTone.HEALTHY else FlameTone.DANGER,
                )

                Surface(
                    color = palette.surfaceSubtle,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "登录态：${if (report.loggedIn) "已登录" else "未登录"} | " +
                                "核心骨架：${if (report.coreSkeletonOk) "正常" else "异常"} | " +
                                "摘要：${report.bundleDigest}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = palette.ink,
                        )
                        if (report.webViewVersion.isNotBlank()) {
                            Text(
                                text = "WebView：${report.webViewVersion} · 时间：${formatSettingsEpochMs(report.checkedAtEpochMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = palette.hairline)

                for (item in report.items) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = if (item.hit) {
                                Icons.Outlined.CheckCircle
                            } else {
                                Icons.Outlined.ErrorOutline
                            },
                            contentDescription = null,
                            tint = if (item.hit) semantics.healthy else semantics.danger,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(17.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${item.label} (${item.key})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = palette.ink,
                            )
                            Text(
                                text = buildString {
                                    append(if (item.hit) "命中 (${item.matchedCount})" else "未命中")
                                    item.detail?.takeIf { it.isNotBlank() }?.let { d ->
                                        append(" · ").append(d)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canManualRecoverDomBroken) {
                FlameSheetActionRow(
                    text = "确认恢复运行",
                    primary = true,
                    onClick = onConfirmRecoverDomBroken,
                )
            }
            if (!uiState.isProbeRunning) {
                FlameSheetActionRow(
                    text = "重新体检",
                    primary = !canManualRecoverDomBroken,
                    onClick = onRetrigger,
                )
            }
            FlameSheetActionRow(
                text = "关闭",
                onClick = onDismiss,
            )
        }
    }
}

@Composable
internal fun SelectorOverrideEditorSheet(
    uiState: SettingsUiState,
    onDismiss: () -> Unit,
    onJsonChanged: (String) -> Unit,
    onSave: () -> Unit,
    onClearOverrides: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val textFieldColors = settingsTextFieldColors()
    var showReference by remember { mutableStateOf(false) }

    FlameModalSheet(
        onDismissRequest = { if (!uiState.isSavingOverrides) onDismiss() },
        title = "编辑定位器覆写 JSON",
        subtitle = "CHAT_URL 锁定不可改，仅允许已知定位器键名",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "锁定规则：CHAT_URL 不可修改；仅允许已知定位器键名，禁止换行/控制字符，单串不超 512 字符，数组 1–20 项。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkSecondary,
            )

            OutlinedTextField(
                value = uiState.overrideJsonInput,
                onValueChange = onJsonChanged,
                label = { Text("覆写 JSON 对象") },
                minLines = 6,
                maxLines = 12,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                colors = textFieldColors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            uiState.overrideError?.let { err ->
                SettingsFeedbackBanner(message = err, tone = FlameTone.DANGER)
            }

            TextButton(onClick = { showReference = !showReference }) {
                Text(
                    text = if (showReference) "收起内置基线参考" else "查看内置基线键名与默认值",
                    color = palette.ink,
                )
            }

            if (showReference && uiState.overrideBaselineReferenceJson.isNotBlank()) {
                Surface(
                    color = palette.surfaceSubtle,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Text(
                            text = uiState.overrideBaselineReferenceJson,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = palette.ink,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlameSheetActionRow(
                text = if (uiState.isSavingOverrides) "校验保存中…" else "校验并保存",
                primary = true,
                enabled = !uiState.isSavingOverrides,
                onClick = onSave,
            )
            if (uiState.hasSelectorOverrides) {
                FlameSheetActionRow(
                    text = "清空本地覆写并恢复默认",
                    danger = true,
                    enabled = !uiState.isSavingOverrides,
                    onClick = onClearOverrides,
                )
            }
            FlameSheetActionRow(
                text = "取消",
                enabled = !uiState.isSavingOverrides,
                onClick = onDismiss,
            )
        }
    }
}

@Composable
internal fun SanitizedDiagnosticsSheet(
    report: SanitizedDiagnosticsReport,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    val palette = FlameThemeTokens.palette
    var selectedFormat by remember { mutableStateOf("text") }
    val activePayload = if (selectedFormat == "json") report.jsonReport else report.textReport

    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = "脱敏诊断报告",
        subtitle = "已通过白名单与敏感词脱敏，不包含隐私与密钥",
    ) {
        FlameFilterChipRow(
            items = listOf(
                FlameFilterChipItem(key = "text", label = "可读摘要"),
                FlameFilterChipItem(key = "json", label = "JSON"),
            ),
            selectedKey = selectedFormat,
            onSelect = { selectedFormat = it },
        )

        Spacer(modifier = Modifier.height(10.dp))

        Surface(
            color = palette.surfaceSubtle,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, palette.hairline),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(min = 180.dp, max = 380.dp),
        ) {
            SelectionContainer {
                Text(
                    text = activePayload,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = palette.ink,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsSecondaryButton(
                text = "复制",
                leadingIcon = Icons.Outlined.ContentCopy,
                onClick = { onCopy(activePayload) },
                modifier = Modifier.weight(1f),
            )
            SettingsPrimaryButton(
                text = "分享",
                leadingIcon = Icons.Outlined.Share,
                onClick = { onShare(activePayload) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        FlameSheetActionRow(
            text = "关闭",
            onClick = onDismiss,
        )
    }
}
