package io.github.oinzc.flamecontractor.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.data.db.ScanRunStatus
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameListRow
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens

private val AUTO_SCAN_PRESET_CHIP_HOURS = listOf(6, 12, 24, 48)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsSchedulePage(
    uiState: SettingsUiState,
    onAccountEnabledChanged: (Boolean) -> Unit,
    onSkipScheduledOfficialLitChanged: (Boolean) -> Unit,
    onSendWindowStartChanged: (String) -> Unit,
    onSendWindowEndChanged: (String) -> Unit,
    onSendTimeHhmmChanged: (String) -> Unit,
    onJitterMaxMinutesChanged: (String) -> Unit,
    onCooldownMinSecChanged: (String) -> Unit,
    onCooldownMaxSecChanged: (String) -> Unit,
    onSaveScheduleConfig: () -> Unit,
    onResetScheduleDraft: () -> Unit,
    onAutoScanEnabledChanged: (Boolean) -> Unit,
    onAutoScanIntervalHoursChanged: (String) -> Unit,
    onSelectAutoScanPresetHours: (Int) -> Unit,
    onSaveAutoScanConfig: () -> Unit,
    onResetAutoScanDraft: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val textFieldColors = settingsTextFieldColors()

    FlameSubpageScaffold(
        title = SettingsSection.SCHEDULE.title,
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        SettingsPageScrollColumn(contentPadding = innerPadding) {
            // 1. 每日自动调度
            FlameGroupedSection(
                title = "每日自动调度与冷却",
                trailingHeader = {
                    FlameMiniTag(
                        text = "时区 ${uiState.accountTimezone}",
                        tone = FlameTone.NEUTRAL,
                    )
                },
            ) {
                FlameListRow(
                    title = "每日自动定时续火",
                    subtitle = "每日严格限制在同一自然日内发送，不支持跨午夜窗口",
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
                    trailing = {
                        Switch(
                            checked = uiState.enabledInput,
                            onCheckedChange = onAccountEnabledChanged,
                            colors = settingsSwitchColors(),
                        )
                    },
                    showDivider = true,
                    onClick = { onAccountEnabledChanged(!uiState.enabledInput) },
                )

                FlameListRow(
                    title = "定时跳过今日抖音已亮火花",
                    subtitle = "默认关闭（按本机今日记录照常发）；开启后到点先只读核对一次官方火花，今日已亮自动跳过、仅发灰火或未续好友",
                    leading = {
                        FlameRowAvatar(
                            tone = if (uiState.skipScheduledOfficialLitInput) FlameTone.EMBER else FlameTone.NEUTRAL,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.LocalFireDepartment,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = {
                        Switch(
                            checked = uiState.skipScheduledOfficialLitInput,
                            onCheckedChange = onSkipScheduledOfficialLitChanged,
                            colors = settingsSwitchColors(),
                        )
                    },
                    showDivider = true,
                    onClick = { onSkipScheduledOfficialLitChanged(!uiState.skipScheduledOfficialLitInput) },
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = uiState.sendWindowStartInput,
                            onValueChange = onSendWindowStartChanged,
                            label = { Text("窗口开始 (HH:mm)") },
                            placeholder = { Text("20:00") },
                            singleLine = true,
                            colors = textFieldColors,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = uiState.sendWindowEndInput,
                            onValueChange = onSendWindowEndChanged,
                            label = { Text("窗口结束 (<=23:55)") },
                            placeholder = { Text("22:00") },
                            singleLine = true,
                            colors = textFieldColors,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = uiState.sendTimeHhmmInput,
                            onValueChange = onSendTimeHhmmChanged,
                            label = { Text("账号固定时刻 (可选)") },
                            placeholder = { Text("留空按窗口") },
                            singleLine = true,
                            colors = textFieldColors,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = uiState.jitterMaxMinutesInput,
                            onValueChange = onJitterMaxMinutesChanged,
                            label = { Text("随机抖动上限 (0–15分)") },
                            placeholder = { Text("15") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = textFieldColors,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = uiState.cooldownMinSecInput,
                            onValueChange = onCooldownMinSecChanged,
                            label = { Text("冷却下限 (秒)") },
                            placeholder = { Text("3") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = textFieldColors,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = uiState.cooldownMaxSecInput,
                            onValueChange = onCooldownMaxSecChanged,
                            label = { Text("冷却上限 (秒)") },
                            placeholder = { Text("8") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = textFieldColors,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // 下次预计唤醒摘要条
                    Surface(
                        color = palette.surfaceSubtle,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, palette.hairline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Alarm,
                                contentDescription = null,
                                tint = palette.inkSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = when {
                                    !uiState.enabledInput -> "下次自动唤醒：已暂停（总开关已关闭）"
                                    !uiState.nextScheduledWakeText.isNullOrBlank() ->
                                        "下次自动唤醒：${uiState.nextScheduledWakeText}"
                                    else -> "下次自动唤醒：保存后按窗口 ${uiState.sendWindowStartInput}–${uiState.sendWindowEndInput} 自动规划"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                            )
                        }
                    }

                    uiState.scheduleError?.let { err ->
                        SettingsFeedbackBanner(message = err, tone = FlameTone.DANGER)
                    }
                    uiState.scheduleMessage?.let { msg ->
                        SettingsFeedbackBanner(message = msg, tone = FlameTone.HEALTHY)
                    }

                    if (uiState.hasUnsavedScheduleChanges) {
                        SettingsFeedbackBanner(
                            message = "检测到未保存的调度配置修改，请点击保存使新计划生效",
                            tone = FlameTone.WARNING,
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsPrimaryButton(
                            text = if (uiState.hasUnsavedScheduleChanges) "保存调度配置 *" else "保存调度配置",
                            loading = uiState.isSavingSchedule,
                            enabled = !uiState.isSavingSchedule,
                            onClick = onSaveScheduleConfig,
                        )
                        if (uiState.hasUnsavedScheduleChanges) {
                            SettingsSecondaryButton(
                                text = "还原",
                                enabled = !uiState.isSavingSchedule,
                                onClick = onResetScheduleDraft,
                            )
                        }
                    }
                }
            }

            // 2. 官方火花自动同步
            FlameGroupedSection(
                title = "官方火花定时扫描",
                trailingHeader = {
                    FlameMiniTag(
                        text = "只读同步",
                        tone = FlameTone.NEUTRAL,
                    )
                },
            ) {
                FlameListRow(
                    title = "启用后台自动同步",
                    subtitle = "后台周期只读同步官方火花并预警倒计时恶化，严格避开发送窗口，绝不自动重发消息",
                    leading = {
                        FlameRowAvatar(
                            tone = if (uiState.autoScanEnabledInput) FlameTone.HEALTHY else FlameTone.NEUTRAL,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = {
                        Switch(
                            checked = uiState.autoScanEnabledInput,
                            onCheckedChange = onAutoScanEnabledChanged,
                            colors = settingsSwitchColors(),
                        )
                    },
                    showDivider = true,
                    onClick = { onAutoScanEnabledChanged(!uiState.autoScanEnabledInput) },
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "快捷同步周期",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.inkSecondary,
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (presetHours in AUTO_SCAN_PRESET_CHIP_HOURS) {
                            PresetHourChip(
                                hours = presetHours,
                                selected = uiState.autoScanIntervalHoursInput.trim() == presetHours.toString(),
                                onClick = { onSelectAutoScanPresetHours(presetHours) },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = uiState.autoScanIntervalHoursInput,
                        onValueChange = onAutoScanIntervalHoursChanged,
                        label = { Text("自定义同步周期 (1–168 小时)") },
                        placeholder = { Text("24") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 最近自动同步与下次计划卡
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
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val latestScan = uiState.latestAutoScanRun
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "最近自动同步",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = palette.ink,
                                )
                                if (latestScan != null) {
                                    val scanTone = when (latestScan.status) {
                                        ScanRunStatus.COMPLETED -> FlameTone.HEALTHY
                                        ScanRunStatus.RUNNING -> FlameTone.EMBER
                                        ScanRunStatus.FAILED,
                                        ScanRunStatus.INTERRUPTED,
                                        -> FlameTone.DANGER
                                        ScanRunStatus.CANCELLED -> FlameTone.NEUTRAL
                                    }
                                    FlameStatusPill(
                                        text = scanRunStatusLabel(latestScan.status),
                                        tone = scanTone,
                                        showDot = false,
                                    )
                                }
                            }

                            if (latestScan == null) {
                                Text(
                                    text = "暂无自动同步记录",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.inkSecondary,
                                )
                            } else {
                                val scanAt = formatSettingsEpochMs(latestScan.finishedAt ?: latestScan.startedAt)
                                val durationText = formatScanDuration(latestScan)
                                Text(
                                    text = buildString {
                                        append("时间：$scanAt")
                                        if (durationText != null) append(" · 耗时 $durationText")
                                        append(" · 会话 ${latestScan.discoveredCount} / 火花 ${latestScan.sparkCount}")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.inkSecondary,
                                )
                                latestScan.alertSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                                    Text(
                                        text = "告警摘要：$summary",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.ember,
                                    )
                                }
                            }

                            HorizontalDivider(thickness = 1.dp, color = palette.hairline)

                            Text(
                                text = when {
                                    !uiState.autoScanEnabledInput -> "下次预计同步：已关闭"
                                    uiState.nextAutoScanTimeText != null -> "下次预计同步：${uiState.nextAutoScanTimeText}"
                                    else -> "下次预计同步：待命中（将在账号健康且避开发送窗后自动安排）"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                            )
                        }
                    }

                    uiState.autoScanError?.let { err ->
                        SettingsFeedbackBanner(message = err, tone = FlameTone.DANGER)
                    }
                    uiState.autoScanMessage?.let { msg ->
                        SettingsFeedbackBanner(message = msg, tone = FlameTone.HEALTHY)
                    }

                    if (uiState.hasUnsavedAutoScanChanges) {
                        SettingsFeedbackBanner(
                            message = "检测到未保存的自动同步修改，请点击保存以更新后台周期",
                            tone = FlameTone.WARNING,
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsPrimaryButton(
                            text = if (uiState.hasUnsavedAutoScanChanges) "保存自动同步 *" else "保存自动同步",
                            loading = uiState.isSavingAutoScan,
                            enabled = !uiState.isSavingAutoScan,
                            onClick = onSaveAutoScanConfig,
                        )
                        if (uiState.hasUnsavedAutoScanChanges) {
                            SettingsSecondaryButton(
                                text = "还原",
                                enabled = !uiState.isSavingAutoScan,
                                onClick = onResetAutoScanDraft,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetHourChip(
    hours: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val containerColor by animateColorAsState(
        targetValue = if (selected) palette.monoCtaContainer else palette.surface,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "scan_preset_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) palette.monoCtaContent else palette.inkSecondary,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "scan_preset_fg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) palette.monoCtaContainer else palette.hairline,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "scan_preset_border",
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = "${hours}h",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}
