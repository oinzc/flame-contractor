package io.github.oinzc.flamecontractor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.core.theme.ThemePreference
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.ui.components.FlameAlertStrip
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameListRow
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens

@Composable
internal fun SettingsHubScreen(
    uiState: SettingsUiState,
    themePreference: ThemePreference,
    onSelectSection: (SettingsSection) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val statusTone = settingsAccountStatusTone(uiState.accountStatus)
    val statusLabel = settingsAccountStatusLabel(uiState.accountStatus)

    FlameSubpageScaffold(
        title = "设置与维护",
        onBack = onBack,
        modifier = modifier,
        trailingAction = {
            if (uiState.accountDisplayName.isNotBlank()) {
                Text(
                    text = uiState.accountDisplayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 88.dp),
                )
            }
            FlameStatusPill(
                text = statusLabel,
                tone = statusTone,
            )
        },
    ) { innerPadding ->
        SettingsPageScrollColumn(contentPadding = innerPadding) {
            if (uiState.accountStatus == AccountStatus.DOM_BROKEN) {
                FlameAlertStrip(
                    title = "页面结构异常（DOM_BROKEN）",
                    subtitle = "自动续火已暂停，建议运行只读结构体检或保存定位器覆写",
                    tone = FlameTone.DANGER,
                    actionLabel = "去诊断",
                    icon = Icons.Outlined.WarningAmber,
                    onClick = { onSelectSection(SettingsSection.DIAGNOSTICS) },
                )
            }

            // 1. 个性化
            val appearanceSummary = "${themeModeLabel(themePreference.mode)} · 桌面状态小组件"

            FlameGroupedSection(title = "个性化") {
                FlameListRow(
                    title = SettingsSection.APPEARANCE.title,
                    subtitle = appearanceSummary,
                    leading = {
                        FlameRowAvatar(tone = FlameTone.NEUTRAL) {
                            Icon(
                                imageVector = Icons.Outlined.Palette,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = { HubChevron() },
                    showDivider = false,
                    onClick = { onSelectSection(SettingsSection.APPEARANCE) },
                )
            }

            // 2. 自动执行
            val scheduleSummary = buildString {
                if (!uiState.enabledInput) {
                    append("每日调度已暂停")
                } else {
                    val fixed = uiState.sendTimeHhmmInput.trim()
                    if (fixed.isNotEmpty()) {
                        append("每日 $fixed 固定发送")
                    } else {
                        append("每日 ${uiState.sendWindowStartInput}–${uiState.sendWindowEndInput}")
                    }
                }
                append(" · ")
                if (uiState.autoScanEnabledInput) {
                    val hours = uiState.autoScanIntervalHoursInput.trim().ifEmpty { "24" }
                    append("每 ${hours}h 扫描")
                } else {
                    append("自动扫描已关")
                }
            }
            val hasScheduleDraft = uiState.hasUnsavedScheduleChanges || uiState.hasUnsavedAutoScanChanges

            val survival = uiState.survivalSnapshot
            val notifSummary = buildString {
                append(if (survival.notificationsGranted) "本机通知已授权" else "本机通知未授权")
                append(" · ")
                append(if (uiState.barkConfigured) "Bark 已配置" else "Bark 未配置")
            }
            val notifTone = when {
                !survival.notificationsGranted -> FlameTone.WARNING
                uiState.barkConfigured -> FlameTone.HEALTHY
                else -> FlameTone.NEUTRAL
            }

            val survivalTone = when {
                survival.passedCount == survival.totalCount -> FlameTone.HEALTHY
                !survival.coreAllPassed -> FlameTone.DANGER
                else -> FlameTone.WARNING
            }
            val survivalSummary = buildString {
                append(survival.manufacturer)
                append(" · ")
                append(if (survival.coreAllPassed) "核心保活策略已就绪" else "存在受限项，建议前往放行")
            }

            FlameGroupedSection(title = "自动执行") {
                FlameListRow(
                    title = SettingsSection.SCHEDULE.title,
                    subtitle = scheduleSummary,
                    titleTag = if (hasScheduleDraft) {
                        { FlameMiniTag(text = "未保存", tone = FlameTone.WARNING) }
                    } else {
                        null
                    },
                    leading = {
                        FlameRowAvatar(
                            tone = if (uiState.enabledInput) FlameTone.HEALTHY else FlameTone.NEUTRAL,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = { HubChevron() },
                    showDivider = true,
                    onClick = { onSelectSection(SettingsSection.SCHEDULE) },
                )

                FlameListRow(
                    title = SettingsSection.NOTIFICATIONS.title,
                    subtitle = notifSummary,
                    leading = {
                        FlameRowAvatar(tone = notifTone) {
                            Icon(
                                imageVector = Icons.Outlined.NotificationsActive,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = { HubChevron() },
                    showDivider = true,
                    onClick = { onSelectSection(SettingsSection.NOTIFICATIONS) },
                )

                FlameListRow(
                    title = SettingsSection.SURVIVAL.title,
                    subtitle = survivalSummary,
                    leading = {
                        FlameRowAvatar(tone = survivalTone) {
                            Icon(
                                imageVector = Icons.Outlined.Security,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FlameStatusPill(
                                text = "${survival.passedCount}/${survival.totalCount} 通过",
                                tone = survivalTone,
                                showDot = false,
                            )
                            HubChevron()
                        }
                    },
                    showDivider = false,
                    onClick = { onSelectSection(SettingsSection.SURVIVAL) },
                )
            }

            // 3. 数据与高级维护
            val backupSummary = uiState.lastBackupImportReport?.let { report ->
                "最近已导入 ${report.sourceAccountName} · JSON 脱敏互迁"
            } ?: "JSON 脱敏导出与跨端无损迁移"

            val probe = uiState.latestProbeReport
            val diagnosticsTone = when {
                uiState.accountStatus == AccountStatus.DOM_BROKEN -> FlameTone.DANGER
                probe != null && !probe.coreSkeletonOk -> FlameTone.WARNING
                else -> FlameTone.NEUTRAL
            }
            val diagnosticsSummary = buildString {
                append("摘要 ")
                append(uiState.selectorDigest.ifEmpty { "加载中" })
                append(" · ")
                append(if (uiState.hasSelectorOverrides) "已覆写" else "默认基线")
                append(" · ")
                append(
                    when {
                        uiState.isProbeRunning -> "体检中"
                        probe == null -> "未体检"
                        probe.coreSkeletonOk -> "体检正常"
                        else -> "体检异常"
                    },
                )
            }

            FlameGroupedSection(title = "数据与高级维护") {
                FlameListRow(
                    title = SettingsSection.BACKUP.title,
                    subtitle = backupSummary,
                    leading = {
                        FlameRowAvatar(tone = FlameTone.NEUTRAL) {
                            Icon(
                                imageVector = Icons.Outlined.ImportExport,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = { HubChevron() },
                    showDivider = true,
                    onClick = { onSelectSection(SettingsSection.BACKUP) },
                )

                FlameListRow(
                    title = SettingsSection.DIAGNOSTICS.title,
                    subtitle = diagnosticsSummary,
                    leading = {
                        FlameRowAvatar(tone = diagnosticsTone) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = { HubChevron() },
                    showDivider = false,
                    onClick = { onSelectSection(SettingsSection.DIAGNOSTICS) },
                )
            }
        }
    }
}

@Composable
private fun HubChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = FlameThemeTokens.palette.inkTertiary,
        modifier = Modifier.size(20.dp),
    )
}
