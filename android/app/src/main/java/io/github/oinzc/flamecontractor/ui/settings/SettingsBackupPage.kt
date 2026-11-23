package io.github.oinzc.flamecontractor.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.core.backup.BackupImportReport
import io.github.oinzc.flamecontractor.core.backup.BackupInspection
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameModalSheet
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameSheetActionRow
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsBackupPage(
    uiState: SettingsUiState,
    isBackupSafBusy: Boolean,
    onLaunchZipBackupExport: () -> Unit,
    onLaunchJsonBackupExport: () -> Unit,
    onLaunchBackupImport: () -> Unit,
    onSelectBackupAccount: (String) -> Unit,
    onIncludeBackupHistoryChanged: (Boolean) -> Unit,
    onConfirmImportBackup: () -> Unit,
    onDismissBackupPreview: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val busy = isBackupSafBusy ||
        uiState.isExportingBackup ||
        uiState.isInspectingBackup ||
        uiState.isImportingBackup

    FlameSubpageScaffold(
        title = SettingsSection.BACKUP.title,
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        SettingsPageScrollColumn(contentPadding = innerPadding) {
            // 1. 脱敏说明与互迁范围
            FlameCard(contentPadding = PaddingValues(15.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FlameRowAvatar(tone = FlameTone.NEUTRAL) {
                        Icon(
                            imageVector = Icons.Outlined.ImportExport,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "跨端配置与图片模板互迁",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.ink,
                        )
                        Text(
                            text = "支持 Android 与 Windows / Docker 端 .zip（含图）及 .json（schema v1）双向无损互通",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                        )
                    }
                }
                Text(
                    text = "完整备份 (.zip) 会一并打包图片模板本地真图；所有导出均严格脱敏，绝不包含抖音 Cookie、LocalStorage、self_uid、Bark 密钥或本机绝对路径（image_path）。导入时不会覆盖本地登录态与 Bark 密钥。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            // 2. 导出与导入操作
            FlameGroupedSection(title = "SAF 备份文件操作") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (busy) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(999.dp)),
                            color = palette.monoCtaContainer,
                            trackColor = palette.surfaceSubtle,
                        )
                    }

                    uiState.backupError?.let { err ->
                        SettingsFeedbackBanner(message = err, tone = FlameTone.DANGER)
                    }
                    uiState.backupMessage?.let { msg ->
                        SettingsFeedbackBanner(message = msg, tone = FlameTone.HEALTHY)
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsPrimaryButton(
                            text = if (uiState.isExportingBackup || isBackupSafBusy) "正在处理…" else "导出完整备份 (.zip 含图)",
                            leadingIcon = Icons.Outlined.Upload,
                            enabled = !busy,
                            onClick = onLaunchZipBackupExport,
                        )
                        SettingsSecondaryButton(
                            text = "仅导出 JSON",
                            leadingIcon = Icons.Outlined.Upload,
                            enabled = !busy,
                            onClick = onLaunchJsonBackupExport,
                        )
                        SettingsSecondaryButton(
                            text = if (uiState.isInspectingBackup) "解析中…" else "选择备份文件导入",
                            leadingIcon = Icons.Outlined.Download,
                            enabled = !busy,
                            onClick = onLaunchBackupImport,
                        )
                    }
                }
            }

            // 3. 最近一次导入结果报告
            uiState.lastBackupImportReport?.let { report ->
                BackupImportReportSection(report = report)
            }
        }
    }

    val backupInspection = uiState.backupInspection
    if (uiState.showBackupPreviewDialog && backupInspection != null) {
        BackupImportPreviewSheet(
            inspection = backupInspection,
            selectedAccountId = uiState.selectedBackupAccountId,
            includeHistory = uiState.includeBackupHistory,
            isImporting = uiState.isImportingBackup,
            onSelectAccount = onSelectBackupAccount,
            onIncludeHistoryChanged = onIncludeBackupHistoryChanged,
            onConfirm = onConfirmImportBackup,
            onDismiss = onDismissBackupPreview,
        )
    }
}

@Composable
private fun BackupImportReportSection(report: BackupImportReport) {
    val palette = FlameThemeTokens.palette
    FlameGroupedSection(
        title = "最近导入结果",
        trailingHeader = {
            FlameMiniTag(text = "源账号 ${report.sourceAccountName}", tone = FlameTone.HEALTHY)
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "模板：新增 ${report.templatesInserted} / 更新 ${report.templatesUpdated}（文案池 ${report.poolItemsMerged} 项）",
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink,
            )
            Text(
                text = "好友：新增 ${report.targetsInserted} / 更新 ${report.targetsUpdated} / 冲突跳过 ${report.targetsSkipped}",
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink,
            )
            Text(
                text = "历史任务：新增 ${report.jobsInserted} / 更新 ${report.jobsUpdated}",
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink,
            )
            if (report.restoredImagesCount > 0) {
                Text(
                    text = "图片模板：已自动还原 ${report.restoredImagesCount} 张随包图片",
                    style = MaterialTheme.typography.bodySmall,
                    color = FlameThemeTokens.semanticColors.healthy,
                )
            }
            if (report.missingImageTemplateCount > 0) {
                SettingsFeedbackBanner(
                    message = "待补选图片：已保留 ${report.missingImageTemplateCount} 个图片模板及好友绑定，请前往「模板」页重新选择本地图片",
                    tone = FlameTone.WARNING,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (report.skippedProtectedJobs > 0) {
                Text(
                    text = "已保护 ${report.skippedProtectedJobs} 条本地待核对/发送中任务不被覆盖",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                )
            }
            if (report.warnings.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    thickness = 1.dp,
                    color = palette.hairline,
                )
                for (warning in report.warnings.take(5)) {
                    Text(
                        text = "• $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )
                }
                if (report.warnings.size > 5) {
                    Text(
                        text = "…另有 ${report.warnings.size - 5} 条提示",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkTertiary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun BackupImportPreviewSheet(
    inspection: BackupInspection,
    selectedAccountId: String?,
    includeHistory: Boolean,
    isImporting: Boolean,
    onSelectAccount: (String) -> Unit,
    onIncludeHistoryChanged: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    FlameModalSheet(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = "备份导入预览",
        subtitle = "核对源账号与数据明细后合并到当前账号",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsFeedbackBanner(
                message = "导入不会携带或覆盖抖音登录态（Cookie / self_uid）与 Bark 密钥，未登录设备导入后仍需扫码登录。",
                tone = FlameTone.NEUTRAL,
            )

            Surface(
                color = palette.surfaceSubtle,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, palette.hairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FlameMiniTag(
                            text = if (inspection.isZipArchive) "完整备份包 .zip" else "纯配置 .json",
                            tone = if (inspection.isZipArchive && inspection.bundledImageCount > 0) {
                                FlameTone.HEALTHY
                            } else {
                                FlameTone.NEUTRAL
                            },
                        )
                        if (inspection.isZipArchive) {
                            FlameMiniTag(
                                text = "随包图片 ${inspection.bundledImageCount} 张",
                                tone = if (inspection.bundledImageCount > 0) FlameTone.HEALTHY else FlameTone.NEUTRAL,
                            )
                        }
                    }
                    if (inspection.parsed.exportedAtEpochMs > 0L) {
                        Text(
                            text = "导出时间：${formatSettingsEpochMs(inspection.parsed.exportedAtEpochMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                        )
                    }
                    Text(
                        text = "可用模板：${inspection.templateCount} 个 · 好友目标：${inspection.targetCount} 位",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.ink,
                    )
                    Text(
                        text = "历史任务：${inspection.jobCount} 条（尝试日志 ${inspection.attemptCount} 条）",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.ink,
                    )
                    if (inspection.isZipArchive && inspection.bundledImageCount > 0) {
                        Text(
                            text = "导入后将自动还原 ${inspection.bundledImageCount} 张随包图片并绑定至对应图片模板。",
                            style = MaterialTheme.typography.bodySmall,
                            color = FlameThemeTokens.semanticColors.healthy,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (inspection.missingImageTemplateCount > 0) {
                        Text(
                            text = if (inspection.isZipArchive) {
                                "提示：另有 ${inspection.missingImageTemplateCount} 个图片模板未随包附带图片，若本地尚无对应图片文件，导入后需前往「模板」页补选。"
                            } else {
                                "提示：备份含 ${inspection.missingImageTemplateCount} 个图片（image）模板，导入将保留模板定义与好友绑定；若本地尚无对应图片文件，导入后需前往「模板」页补选本地图片。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = FlameThemeTokens.semanticColors.warning,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }

            if (inspection.accounts.size == 1) {
                val onlyAccount = inspection.accounts.first()
                Surface(
                    color = palette.surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "源账号：${onlyAccount.displayName}",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.ink,
                        )
                        Text(
                            text = "窗口 ${onlyAccount.sendWindowStart}–${onlyAccount.sendWindowEnd} · 时区 ${onlyAccount.timezone}",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                        )
                    }
                }
            } else {
                Text(
                    text = "检测到 ${inspection.accounts.size} 个源账号，请选择要导入到当前账号的一组：",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.ink,
                )
                Surface(
                    color = palette.surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        inspection.accounts.forEachIndexed { index, candidate ->
                            val isSelected = selectedAccountId == candidate.sourceId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = isSelected,
                                        onClick = { onSelectAccount(candidate.sourceId) },
                                        role = Role.RadioButton,
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = palette.monoCtaContainer,
                                        unselectedColor = palette.hairlineStrong,
                                    ),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = candidate.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.ink,
                                    )
                                    Text(
                                        text = "窗口 ${candidate.sendWindowStart}–${candidate.sendWindowEnd} · ${candidate.timezone}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.inkSecondary,
                                    )
                                }
                            }
                            if (index < inspection.accounts.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    thickness = 1.dp,
                                    color = palette.hairline,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !isImporting) { onIncludeHistoryChanged(!includeHistory) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = includeHistory,
                    onCheckedChange = if (isImporting) null else onIncludeHistoryChanged,
                    colors = CheckboxDefaults.colors(
                        checkedColor = palette.monoCtaContainer,
                        checkmarkColor = palette.monoCtaContent,
                        uncheckedColor = palette.hairlineStrong,
                    ),
                )
                Text(
                    text = "同时合并历史发送记录与尝试日志",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.ink,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlameSheetActionRow(
                text = if (isImporting) "正在导入…" else "确认导入",
                primary = true,
                enabled = !isImporting && selectedAccountId != null,
                onClick = onConfirm,
            )
            FlameSheetActionRow(
                text = "取消",
                enabled = !isImporting,
                onClick = onDismiss,
            )
        }
    }
}
