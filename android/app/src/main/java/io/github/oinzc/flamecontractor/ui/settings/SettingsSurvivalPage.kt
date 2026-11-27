package io.github.oinzc.flamecontractor.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.FlameTopPillButton
import io.github.oinzc.flamecontractor.ui.components.rememberReducedMotion
import io.github.oinzc.flamecontractor.ui.components.resolveFlameToneColors
import io.github.oinzc.flamecontractor.ui.setup.SetupChecklistItem
import io.github.oinzc.flamecontractor.ui.setup.SetupItemId
import io.github.oinzc.flamecontractor.ui.setup.SetupItemImportance
import io.github.oinzc.flamecontractor.ui.setup.SetupReadinessBuilder
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsSurvivalPage(
    uiState: SettingsUiState,
    onOpenSurvivalAction: (SurvivalCheckId) -> Unit,
    onOpenBackgroundPopupSettings: () -> Unit,
    onOpenOemPowerSettings: () -> Unit,
    onAutostartConfirmedChanged: (Boolean) -> Unit,
    onRefreshSurvivalChecks: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenTargets: () -> Unit,
    onStartOnboardingWizard: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    embeddedInSplitPane: Boolean = false,
) {
    val palette = FlameThemeTokens.palette
    val reducedMotion = rememberReducedMotion()
    val readiness = SetupReadinessBuilder.build(
        survivalSnapshot = uiState.survivalSnapshot,
        accountStatus = uiState.accountStatus,
        enabledTargetCount = uiState.enabledTargetCount,
    )

    val summaryTone = when {
        readiness.allCompleted -> FlameTone.HEALTHY
        !readiness.allRequiredCompleted -> FlameTone.WARNING
        else -> FlameTone.NEUTRAL
    }
    val summaryToneColors = resolveFlameToneColors(summaryTone)
    val targetProgress = if (readiness.totalCount > 0) {
        readiness.completedCount.toFloat() / readiness.totalCount.toFloat()
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress.coerceIn(0f, 1f),
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else 200,
            easing = FlameMotionSpec.easeOut,
        ),
        label = "setup_readiness_progress",
    )

    val systemItems = readiness.systemItems
    val workflowItems = readiness.workflowItems
    val systemCompleted = systemItems.count { it.completed }
    val workflowCompleted = workflowItems.count { it.completed }

    val needsBgPopupOnly = readiness.overlayAutoReturnAllowed && !readiness.backgroundPopupAllowed

    val triggerPrimaryAction: (SetupItemId) -> Unit = { id ->
        when (id) {
            SetupItemId.NOTIFICATIONS -> onOpenSurvivalAction(SurvivalCheckId.NOTIFICATIONS)
            SetupItemId.AUTO_RETURN_OVERLAY -> if (needsBgPopupOnly) {
                onOpenBackgroundPopupSettings()
            } else {
                onOpenSurvivalAction(SurvivalCheckId.OVERLAY_AUTO_RETURN)
            }
            SetupItemId.BATTERY_OPTIMIZATION -> onOpenSurvivalAction(SurvivalCheckId.BATTERY_OPTIMIZATION)
            SetupItemId.EXACT_ALARM -> onOpenSurvivalAction(SurvivalCheckId.EXACT_ALARM)
            SetupItemId.OEM_BACKGROUND_AUTOSTART -> onOpenSurvivalAction(SurvivalCheckId.OEM_BACKGROUND_AUTOSTART)
            SetupItemId.DOUYIN_LOGIN -> onOpenLogin()
            SetupItemId.SELECT_TARGETS -> onOpenTargets()
        }
    }

    val triggerSecondaryAction: (SetupItemId) -> (() -> Unit)? = { id ->
        when (id) {
            SetupItemId.AUTO_RETURN_OVERLAY -> if (needsBgPopupOnly) {
                { onOpenSurvivalAction(SurvivalCheckId.OVERLAY_AUTO_RETURN) }
            } else {
                onOpenBackgroundPopupSettings
            }
            SetupItemId.OEM_BACKGROUND_AUTOSTART -> onOpenOemPowerSettings
            else -> null
        }
    }

    FlameSubpageScaffold(
        title = SettingsSection.SURVIVAL.title,
        onBack = onBack,
        modifier = modifier,
        embeddedInSplitPane = embeddedInSplitPane,
        trailingAction = {
            FlameTopPillButton(
                text = "刷新",
                leadingIcon = Icons.Outlined.Refresh,
                onClick = onRefreshSurvivalChecks,
            )
        },
    ) { innerPadding ->
        SettingsPageScrollColumn(
            contentPadding = innerPadding,
            fullWidthInPane = embeddedInSplitPane,
        ) {
            // 1. 顶部整体完成度摘要卡
            FlameCard(contentPadding = PaddingValues(15.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "配置完成总览",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.ink,
                            )
                            FlameMiniTag(
                                text = readiness.vendorFamily.displayName,
                                tone = FlameTone.NEUTRAL,
                            )
                        }
                        Text(
                            text = if (readiness.allCompleted) {
                                "当前设备（${readiness.manufacturer}）全部 ${readiness.totalCount} 项系统权限与续火准备已就绪。"
                            } else {
                                val remaining = readiness.totalCount - readiness.completedCount
                                val nextTitle = readiness.nextPendingItem?.title
                                if (nextTitle != null) {
                                    "当前设备：${readiness.manufacturer} · 还有 $remaining 项未完成，建议优先处理「$nextTitle」"
                                } else {
                                    "当前设备：${readiness.manufacturer} · 还有 $remaining 项待核对"
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                        )
                    }
                    FlameStatusPill(
                        text = "${readiness.completedCount}/${readiness.totalCount} 完成",
                        tone = summaryTone,
                    )
                }

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(7.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = summaryToneColors.fg,
                    trackColor = palette.surfaceSubtle,
                    strokeCap = StrokeCap.Round,
                )

                Spacer(modifier = Modifier.height(12.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (readiness.allCompleted) {
                        SettingsSecondaryButton(
                            text = "按步骤重新引导",
                            leadingIcon = Icons.Outlined.PlayCircleOutline,
                            onClick = onStartOnboardingWizard,
                        )
                    } else {
                        SettingsPrimaryButton(
                            text = "按步骤重新引导",
                            leadingIcon = Icons.Outlined.PlayCircleOutline,
                            onClick = onStartOnboardingWizard,
                        )
                    }
                }

                if (readiness.checkedAtEpochMs > 0L) {
                    Text(
                        text = "最近检测：${formatSettingsEpochMs(readiness.checkedAtEpochMs)}（返回前台自动刷新）",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkTertiary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            // 2. 系统权限与自动回跳清单（5 项）
            FlameGroupedSection(
                title = "系统权限与自动回跳（$systemCompleted/${systemItems.size}）",
            ) {
                systemItems.forEachIndexed { index, item ->
                    SetupChecklistItemRow(
                        item = item,
                        showDivider = index < systemItems.lastIndex,
                        onPrimaryAction = { triggerPrimaryAction(item.id) },
                        onSecondaryAction = item.secondaryActionLabel?.let { triggerSecondaryAction(item.id) },
                        onManualConfirmChanged = if (item.needsManualConfirmation) {
                            onAutostartConfirmedChanged
                        } else {
                            null
                        },
                    )
                }
            }

            // 3. 账号与续火业务就绪清单（2 项）
            FlameGroupedSection(
                title = "账号与续火就绪（$workflowCompleted/${workflowItems.size}）",
            ) {
                workflowItems.forEachIndexed { index, item ->
                    SetupChecklistItemRow(
                        item = item,
                        showDivider = index < workflowItems.lastIndex,
                        onPrimaryAction = { triggerPrimaryAction(item.id) },
                        onSecondaryAction = null,
                        onManualConfirmChanged = null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupChecklistItemRow(
    item: SetupChecklistItem,
    showDivider: Boolean,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: (() -> Unit)?,
    onManualConfirmChanged: ((Boolean) -> Unit)?,
) {
    val palette = FlameThemeTokens.palette
    val itemTone = when {
        item.completed -> FlameTone.HEALTHY
        item.importance == SetupItemImportance.REQUIRED -> FlameTone.WARNING
        else -> FlameTone.NEUTRAL
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlameRowAvatar(tone = itemTone) {
                Icon(
                    imageVector = if (item.completed) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.WarningAmber
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlameMiniTag(
                        text = if (item.importance == SetupItemImportance.REQUIRED) "必选" else "推荐",
                        tone = if (item.importance == SetupItemImportance.REQUIRED && !item.completed) {
                            FlameTone.WARNING
                        } else {
                            FlameTone.NEUTRAL
                        },
                    )
                }
            }
            FlameStatusPill(
                text = item.statusLabel,
                tone = itemTone,
                showDot = false,
            )
        }

        Text(
            text = "为什么需要：${item.whyNeeded}",
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkSecondary,
        )

        if (!item.completed) {
            Text(
                text = item.howToHint,
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!item.completed) {
                SettingsPrimaryButton(
                    text = item.primaryActionLabel,
                    onClick = onPrimaryAction,
                )
            } else {
                SettingsSecondaryButton(
                    text = item.primaryActionLabel,
                    onClick = onPrimaryAction,
                )
            }

            if (item.secondaryActionLabel != null && onSecondaryAction != null) {
                SettingsSecondaryButton(
                    text = item.secondaryActionLabel,
                    onClick = onSecondaryAction,
                )
            }
        }

        if (item.needsManualConfirmation && onManualConfirmChanged != null && !item.confirmCheckboxLabel.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onManualConfirmChanged(!item.completed) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Checkbox(
                    checked = item.completed,
                    onCheckedChange = onManualConfirmChanged,
                    colors = CheckboxDefaults.colors(
                        checkedColor = palette.monoCtaContainer,
                        checkmarkColor = palette.monoCtaContent,
                        uncheckedColor = palette.hairlineStrong,
                    ),
                )
                Text(
                    text = item.confirmCheckboxLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.ink,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showDivider) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 14.dp),
            thickness = 1.dp,
            color = palette.hairline,
        )
    }
}
