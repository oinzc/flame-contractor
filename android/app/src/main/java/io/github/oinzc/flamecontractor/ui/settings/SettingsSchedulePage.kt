package io.github.oinzc.flamecontractor.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.oinzc.flamecontractor.data.db.ScanRunStatus
import io.github.oinzc.flamecontractor.ui.components.FlameClockTime
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameListRow
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTimeWheelPickerSheet
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.ScheduleTriggerMode
import io.github.oinzc.flamecontractor.ui.components.buildRhythmSummaryText
import io.github.oinzc.flamecontractor.ui.components.buildSchedulePlainExplanation
import io.github.oinzc.flamecontractor.ui.components.flamePressScale
import io.github.oinzc.flamecontractor.ui.components.performFlameLightTick
import io.github.oinzc.flamecontractor.ui.components.rememberReducedMotion
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens

private val AUTO_SCAN_PRESET_CHIP_HOURS = listOf(6, 12, 24, 48)

private enum class ActiveScheduleClockPicker {
    WINDOW_START,
    WINDOW_END,
    FIXED_TIME,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsSchedulePage(
    uiState: SettingsUiState,
    onAccountEnabledChanged: (Boolean) -> Unit,
    onSkipScheduledOfficialLitChanged: (Boolean) -> Unit,
    onScheduleTriggerModeChanged: (ScheduleTriggerMode) -> Unit,
    onWindowStartTimePicked: (FlameClockTime) -> Unit,
    onWindowEndTimePicked: (FlameClockTime) -> Unit,
    onFixedTimePicked: (FlameClockTime) -> Unit,
    onJitterMinutesSelected: (Int) -> Unit,
    onCooldownRangeSelected: (Int, Int) -> Unit,
    onRestoreDefaultRhythm: () -> Unit,
    onSaveScheduleConfig: () -> Unit,
    onResetScheduleDraft: () -> Unit,
    onAutoScanEnabledChanged: (Boolean) -> Unit,
    onAutoScanIntervalHoursChanged: (String) -> Unit,
    onSelectAutoScanPresetHours: (Int) -> Unit,
    onStepAutoScanIntervalHours: (Int) -> Unit,
    onSaveAutoScanConfig: () -> Unit,
    onResetAutoScanDraft: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    embeddedInSplitPane: Boolean = false,
) {
    val palette = FlameThemeTokens.palette
    val haptics = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()
    val textFieldColors = settingsTextFieldColors()

    var activeClockPicker by remember { mutableStateOf<ActiveScheduleClockPicker?>(null) }
    var advancedRhythmExpanded by rememberSaveable { mutableStateOf(false) }

    val startClock = remember(uiState.sendWindowStartInput) {
        FlameClockTime.parseOrDefault(uiState.sendWindowStartInput, FlameClockTime(20, 0))
    }
    val endClock = remember(uiState.sendWindowEndInput) {
        FlameClockTime.parseOrDefault(uiState.sendWindowEndInput, FlameClockTime(22, 0))
    }
    val fixedClock = remember(uiState.fixedTimeDraftInput) {
        FlameClockTime.parseOrDefault(uiState.fixedTimeDraftInput, FlameClockTime(20, 30))
    }
    val jitterInt = uiState.jitterMaxMinutesInput.trim().toIntOrNull()?.coerceIn(0, 15) ?: 15
    val cooldownMinInt = uiState.cooldownMinSecInput.trim().toIntOrNull()?.coerceIn(1, 60) ?: 3
    val cooldownMaxInt = uiState.cooldownMaxSecInput.trim().toIntOrNull()?.coerceIn(cooldownMinInt, 60) ?: 8
    val isDefaultRhythm = jitterInt == 15 && cooldownMinInt == 3 && cooldownMaxInt == 8

    val plainExplanation = remember(
        uiState.enabledInput,
        uiState.scheduleTriggerMode,
        uiState.sendWindowStartInput,
        uiState.sendWindowEndInput,
        uiState.fixedTimeDraftInput,
        uiState.skipScheduledOfficialLitInput,
    ) {
        buildSchedulePlainExplanation(
            enabled = uiState.enabledInput,
            mode = uiState.scheduleTriggerMode,
            windowStart = uiState.sendWindowStartInput,
            windowEnd = uiState.sendWindowEndInput,
            fixedTime = uiState.fixedTimeDraftInput,
            skipOfficialLitToday = uiState.skipScheduledOfficialLitInput,
        )
    }
    val rhythmSummary = remember(jitterInt, cooldownMinInt, cooldownMaxInt) {
        buildRhythmSummaryText(
            jitterMaxMinutes = jitterInt,
            cooldownMinSec = cooldownMinInt,
            cooldownMaxSec = cooldownMaxInt,
        )
    }

    FlameSubpageScaffold(
        title = SettingsSection.SCHEDULE.title,
        onBack = onBack,
        modifier = modifier,
        embeddedInSplitPane = embeddedInSplitPane,
    ) { innerPadding ->
        SettingsPageScrollColumn(
            contentPadding = innerPadding,
            fullWidthInPane = embeddedInSplitPane,
        ) {
            // 1. 每日自动续火
            FlameGroupedSection(
                title = "每日自动续火",
                trailingHeader = {
                    FlameMiniTag(
                        text = "时区 ${uiState.accountTimezone}",
                        tone = FlameTone.NEUTRAL,
                    )
                },
            ) {
                FlameListRow(
                    title = "每日自动定时续火",
                    subtitle = "开启后每天按下面设定的时间自动帮你发消息",
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
                    Text(
                        text = "发送时间方式",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.ink,
                    )

                    ScheduleTriggerModeSelector(
                        mode = uiState.scheduleTriggerMode,
                        onSelectMode = onScheduleTriggerModeChanged,
                    )

                    if (uiState.scheduleTriggerMode == ScheduleTriggerMode.WINDOW) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ClockTimeCard(
                                label = "最早开始",
                                timeText = startClock.toHhmm(),
                                hint = "点此滚轮选时",
                                onClick = { activeClockPicker = ActiveScheduleClockPicker.WINDOW_START },
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = null,
                                tint = palette.inkTertiary,
                                modifier = Modifier.size(18.dp),
                            )
                            ClockTimeCard(
                                label = "最晚结束 (≤ 23:55)",
                                timeText = endClock.toHhmm(),
                                hint = "同日不跨午夜",
                                onClick = { activeClockPicker = ActiveScheduleClockPicker.WINDOW_END },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        ClockTimeCard(
                            label = "每天准点闹钟",
                            timeText = fixedClock.toHhmm(),
                            hint = "点击调节小时与分钟（60 分钟内支持断网补发）",
                            onClick = { activeClockPicker = ActiveScheduleClockPicker.FIXED_TIME },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // 通俗大白话效果解释 + 下次预计唤醒
                    Surface(
                        color = palette.surfaceSubtle,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, palette.hairline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = palette.inkSecondary,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(16.dp),
                                )
                                Text(
                                    text = plainExplanation,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.inkSecondary,
                                )
                            }

                            HorizontalDivider(thickness = 1.dp, color = palette.hairline)

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Alarm,
                                    contentDescription = null,
                                    tint = palette.inkSecondary,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = when {
                                        !uiState.enabledInput -> "下次自动唤醒：已暂停（总开关已关闭）"
                                        !uiState.nextScheduledWakeText.isNullOrBlank() ->
                                            "下次自动唤醒：${uiState.nextScheduledWakeText}"

                                        uiState.scheduleTriggerMode == ScheduleTriggerMode.FIXED_TIME ->
                                            "下次自动唤醒：保存后每天 ${fixedClock.toHhmm()} 左右自动触发"

                                        else ->
                                            "下次自动唤醒：保存后在 ${startClock.toHhmm()}–${endClock.toHhmm()} 之间自动规划"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.ink,
                                )
                            }
                        }
                    }

                    // 高级防风控节奏（默认折叠，人话翻译抖动与冷却）
                    val chevronRotation by animateFloatAsState(
                        targetValue = if (advancedRhythmExpanded) 180f else 0f,
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else 180,
                            easing = FlameMotionSpec.easeOut,
                        ),
                        label = "rhythm_chevron_rotate",
                    )
                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, palette.hairline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button) {
                                        haptics.performFlameLightTick()
                                        advancedRhythmExpanded = !advancedRhythmExpanded
                                    }
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Tune,
                                    contentDescription = null,
                                    tint = palette.inkSecondary,
                                    modifier = Modifier.size(17.dp),
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "高级防风控节奏",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = palette.ink,
                                    )
                                    Text(
                                        text = rhythmSummary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.inkTertiary,
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Outlined.ExpandMore,
                                    contentDescription = if (advancedRhythmExpanded) "收起" else "展开",
                                    tint = palette.inkTertiary,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .rotate(chevronRotation),
                                )
                            }

                            AnimatedVisibility(
                                visible = advancedRhythmExpanded,
                                enter = if (reducedMotion) {
                                    EnterTransition.None
                                } else {
                                    fadeIn(tween(180, easing = FlameMotionSpec.easeOut)) +
                                        expandVertically(tween(200, easing = FlameMotionSpec.easeOut))
                                },
                                exit = if (reducedMotion) {
                                    ExitTransition.None
                                } else {
                                    fadeOut(tween(140, easing = FlameMotionSpec.easeOut)) +
                                        shrinkVertically(tween(170, easing = FlameMotionSpec.easeOut))
                                },
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    HorizontalDivider(thickness = 1.dp, color = palette.hairline)

                                    // 1. 出发随机错峰
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "出发前随机错峰（仅时间段生效）",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = palette.ink,
                                            )
                                            FlameMiniTag(
                                                text = if (jitterInt == 0) "关闭" else "0–${jitterInt} 分",
                                                tone = if (jitterInt == 15) FlameTone.HEALTHY else FlameTone.NEUTRAL,
                                            )
                                        }
                                        Text(
                                            text = "避免每天都卡在同一秒触发发消息。推荐保持 15 分钟，系统会在计划时刻后再随机晚几分钟出发。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = palette.inkSecondary,
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            listOf(
                                                0 to "关闭",
                                                5 to "5 分钟",
                                                10 to "10 分钟",
                                                15 to "15 分钟 (推荐)",
                                            ).forEach { (mins, label) ->
                                                RhythmChoiceChip(
                                                    label = label,
                                                    selected = jitterInt == mins,
                                                    onClick = { onJitterMinutesSelected(mins) },
                                                )
                                            }
                                        }
                                        RhythmStepperRow(
                                            label = "精细调节偏移上限",
                                            valueText = "$jitterInt 分钟",
                                            canDecrement = jitterInt > 0,
                                            canIncrement = jitterInt < 15,
                                            onDecrement = { onJitterMinutesSelected((jitterInt - 1).coerceAtLeast(0)) },
                                            onIncrement = { onJitterMinutesSelected((jitterInt + 1).coerceAtMost(15)) },
                                        )
                                    }

                                    HorizontalDivider(thickness = 1.dp, color = palette.hairline)

                                    // 2. 好友之间发送间隔
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "好友之间发送间隔",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = palette.ink,
                                            )
                                            FlameMiniTag(
                                                text = "${cooldownMinInt}–${cooldownMaxInt} 秒",
                                                tone = if (cooldownMinInt == 3 && cooldownMaxInt == 8) {
                                                    FlameTone.HEALTHY
                                                } else {
                                                    FlameTone.NEUTRAL
                                                },
                                            )
                                        }
                                        Text(
                                            text = "连续给多位好友续火时，每发完一位都会随机停顿几秒再发下一位；调得太短容易触发平台频繁提醒。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = palette.inkSecondary,
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            listOf(
                                                Triple(2, 5, "紧凑 2–5 秒"),
                                                Triple(3, 8, "标准 3–8 秒 (推荐)"),
                                                Triple(5, 15, "稳健 5–15 秒"),
                                            ).forEach { (minS, maxS, label) ->
                                                RhythmChoiceChip(
                                                    label = label,
                                                    selected = cooldownMinInt == minS && cooldownMaxInt == maxS,
                                                    onClick = { onCooldownRangeSelected(minS, maxS) },
                                                )
                                            }
                                        }
                                        RhythmStepperRow(
                                            label = "最短等待",
                                            valueText = "$cooldownMinInt 秒",
                                            canDecrement = cooldownMinInt > 1,
                                            canIncrement = cooldownMinInt < 60,
                                            onDecrement = {
                                                val nextMin = (cooldownMinInt - 1).coerceAtLeast(1)
                                                onCooldownRangeSelected(nextMin, cooldownMaxInt.coerceAtLeast(nextMin))
                                            },
                                            onIncrement = {
                                                val nextMin = (cooldownMinInt + 1).coerceAtMost(60)
                                                onCooldownRangeSelected(nextMin, cooldownMaxInt.coerceAtLeast(nextMin))
                                            },
                                        )
                                        RhythmStepperRow(
                                            label = "最长等待",
                                            valueText = "$cooldownMaxInt 秒",
                                            canDecrement = cooldownMaxInt > cooldownMinInt,
                                            canIncrement = cooldownMaxInt < 60,
                                            onDecrement = {
                                                val nextMax = (cooldownMaxInt - 1).coerceAtLeast(cooldownMinInt)
                                                onCooldownRangeSelected(cooldownMinInt, nextMax)
                                            },
                                            onIncrement = {
                                                val nextMax = (cooldownMaxInt + 1).coerceAtMost(60)
                                                onCooldownRangeSelected(cooldownMinInt, nextMax)
                                            },
                                        )
                                    }

                                    if (!isDefaultRhythm) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                        ) {
                                            TextButton(onClick = onRestoreDefaultRhythm) {
                                                Text("恢复推荐默认节奏 (15分 / 3–8秒)")
                                            }
                                        }
                                    }
                                }
                            }
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
                    subtitle = "每隔一段时间悄悄刷新好友的抖音官方火花天数，若火花快消失会提前提醒你，不会自动发消息",
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = uiState.autoScanIntervalHoursInput,
                            onValueChange = onAutoScanIntervalHoursChanged,
                            label = { Text("自定义间隔 (1–168 小时)") },
                            placeholder = { Text("24") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = textFieldColors,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        )
                        val curScanHours = uiState.autoScanIntervalHoursInput.trim().toIntOrNull() ?: 24
                        RhythmStepIconButton(
                            icon = Icons.Outlined.Remove,
                            contentDescription = "减少同步间隔",
                            enabled = curScanHours > 1,
                            onClick = { onStepAutoScanIntervalHours(-1) },
                        )
                        RhythmStepIconButton(
                            icon = Icons.Outlined.Add,
                            contentDescription = "增加同步间隔",
                            enabled = curScanHours < 168,
                            onClick = { onStepAutoScanIntervalHours(1) },
                        )
                    }

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

    when (activeClockPicker) {
        ActiveScheduleClockPicker.WINDOW_START -> {
            FlameTimeWheelPickerSheet(
                title = "设置最早开始时间",
                subtitle = "每天会从这个时刻之后挑选开始时机",
                initialTime = startClock,
                minTime = FlameClockTime(0, 0),
                maxTime = FlameClockTime(23, 50),
                presetTimes = listOf(
                    FlameClockTime(8, 0),
                    FlameClockTime(12, 0),
                    FlameClockTime(19, 0),
                    FlameClockTime(20, 0),
                    FlameClockTime(21, 0),
                ),
                onConfirm = { picked ->
                    onWindowStartTimePicked(picked)
                    activeClockPicker = null
                },
                onDismiss = { activeClockPicker = null },
            )
        }

        ActiveScheduleClockPicker.WINDOW_END -> {
            FlameTimeWheelPickerSheet(
                title = "设置最晚结束时间",
                subtitle = "为避免跨午夜错乱，同一天最晚可设到 23:55",
                initialTime = endClock,
                minTime = FlameClockTime(0, 5),
                maxTime = FlameClockTime(23, 55),
                presetTimes = listOf(
                    FlameClockTime(9, 0),
                    FlameClockTime(21, 0),
                    FlameClockTime(22, 0),
                    FlameClockTime(23, 0),
                    FlameClockTime(23, 55),
                ),
                onConfirm = { picked ->
                    onWindowEndTimePicked(picked)
                    activeClockPicker = null
                },
                onDismiss = { activeClockPicker = null },
            )
        }

        ActiveScheduleClockPicker.FIXED_TIME -> {
            FlameTimeWheelPickerSheet(
                title = "设置每天固定续火时刻",
                subtitle = "每天准时在这个时间开始续火（支持 60 分钟内补发）",
                initialTime = fixedClock,
                minTime = FlameClockTime(0, 0),
                maxTime = FlameClockTime(23, 59),
                presetTimes = listOf(
                    FlameClockTime(8, 0),
                    FlameClockTime(12, 30),
                    FlameClockTime(20, 0),
                    FlameClockTime(20, 30),
                    FlameClockTime(22, 0),
                ),
                onConfirm = { picked ->
                    onFixedTimePicked(picked)
                    activeClockPicker = null
                },
                onDismiss = { activeClockPicker = null },
            )
        }

        null -> Unit
    }
}

@Composable
private fun ScheduleTriggerModeSelector(
    mode: ScheduleTriggerMode,
    onSelectMode: (ScheduleTriggerMode) -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val haptics = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()
    val animMs = if (reducedMotion) 0 else 180

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = palette.surfaceSubtle,
        border = BorderStroke(1.dp, palette.hairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                ScheduleTriggerMode.WINDOW to "时间段内自动发 (推荐)",
                ScheduleTriggerMode.FIXED_TIME to "每天固定时刻发",
            ).forEach { (option, label) ->
                val isSelected = mode == option
                val interaction = remember(option) { MutableInteractionSource() }
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) palette.monoCtaContainer else Color.Transparent,
                    animationSpec = tween(durationMillis = animMs, easing = FlameMotionSpec.easeOut),
                    label = "sched_mode_bg_${option.name}",
                )
                val fgColor by animateColorAsState(
                    targetValue = if (isSelected) palette.monoCtaContent else palette.inkSecondary,
                    animationSpec = tween(durationMillis = animMs, easing = FlameMotionSpec.easeOut),
                    label = "sched_mode_fg_${option.name}",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .flamePressScale(interactionSource = interaction)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bgColor)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Tab,
                            onClick = {
                                if (!isSelected) {
                                    haptics.performFlameLightTick()
                                    onSelectMode(option)
                                }
                            },
                        )
                        .semantics {
                            role = Role.Tab
                            selected = isSelected
                        }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = fgColor,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClockTimeCard(
    label: String,
    timeText: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }

    Surface(
        onClick = {
            haptics.performFlameLightTick()
            onClick()
        },
        interactionSource = interaction,
        modifier = modifier.flamePressScale(interactionSource = interaction),
        shape = RoundedCornerShape(16.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.hairlineStrong),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = palette.inkSecondary,
                maxLines = 1,
            )
            Text(
                text = timeText,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 28.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
                color = palette.ink,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkTertiary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RhythmChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val haptics = LocalHapticFeedback.current
    val containerColor by animateColorAsState(
        targetValue = if (selected) palette.monoCtaContainer else palette.surfaceSubtle,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "rhythm_chip_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) palette.monoCtaContent else palette.inkSecondary,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "rhythm_chip_fg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) palette.monoCtaContainer else palette.hairline,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "rhythm_chip_border",
    )

    Surface(
        onClick = {
            haptics.performFlameLightTick()
            onClick()
        },
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun RhythmStepperRow(
    label: String,
    valueText: String,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkSecondary,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RhythmStepIconButton(
                icon = Icons.Outlined.Remove,
                contentDescription = "减少$label",
                enabled = canDecrement,
                onClick = onDecrement,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
            )
            RhythmStepIconButton(
                icon = Icons.Outlined.Add,
                contentDescription = "增加$label",
                enabled = canIncrement,
                onClick = onIncrement,
            )
        }
    }
}

@Composable
private fun RhythmStepIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performFlameLightTick()
            onClick()
        },
        enabled = enabled,
        modifier = Modifier.size(34.dp),
        shape = CircleShape,
        color = if (enabled) palette.surfaceSubtle else palette.canvas,
        contentColor = if (enabled) palette.ink else palette.inkTertiary.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, palette.hairline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp),
            )
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
