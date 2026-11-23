package io.github.oinzc.flamecontractor.ui.settings

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.FlameTopPillButton
import io.github.oinzc.flamecontractor.ui.components.resolveFlameToneColors
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsSurvivalPage(
    uiState: SettingsUiState,
    onOpenSurvivalAction: (SurvivalCheckId) -> Unit,
    onOpenMiuiPowerHideSettings: () -> Unit,
    onAutostartConfirmedChanged: (Boolean) -> Unit,
    onRefreshSurvivalChecks: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val snapshot = uiState.survivalSnapshot
    val summaryTone = when {
        snapshot.passedCount == snapshot.totalCount -> FlameTone.HEALTHY
        !snapshot.coreAllPassed -> FlameTone.DANGER
        else -> FlameTone.WARNING
    }
    val summaryToneColors = resolveFlameToneColors(summaryTone)
    val targetProgress = if (snapshot.totalCount > 0) {
        snapshot.passedCount.toFloat() / snapshot.totalCount.toFloat()
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "survival_pass_progress",
    )

    FlameSubpageScaffold(
        title = SettingsSection.SURVIVAL.title,
        onBack = onBack,
        modifier = modifier,
        trailingAction = {
            FlameTopPillButton(
                text = "刷新",
                leadingIcon = Icons.Outlined.Refresh,
                onClick = onRefreshSurvivalChecks,
            )
        },
    ) { innerPadding ->
        SettingsPageScrollColumn(contentPadding = innerPadding) {
            // 1. 顶部通过率摘要卡
            FlameCard(contentPadding = PaddingValues(15.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "保活策略通过率",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.ink,
                        )
                        Text(
                            text = if (snapshot.isXiaomiOrHyperOs) {
                                "检测到 ${snapshot.manufacturer} 设备（MIUI / HyperOS），已启用专项自启动与神隐模式引导"
                            } else {
                                "当前机型：${snapshot.manufacturer} · 核对 ${snapshot.totalCount} 项后台定时与通知保活策略"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                        )
                    }
                    FlameStatusPill(
                        text = "${snapshot.passedCount}/${snapshot.totalCount} 通过",
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
                )

                if (snapshot.checkedAtEpochMs > 0L) {
                    Text(
                        text = "最近检测：${formatSettingsEpochMs(snapshot.checkedAtEpochMs)}（返回前台时自动刷新）",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkTertiary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            // 2. 逐项检查列表
            FlameGroupedSection(title = "逐项保活检查") {
                snapshot.items.forEachIndexed { index, item ->
                    val isLast = index == snapshot.items.lastIndex
                    val itemTone = when {
                        item.passed -> FlameTone.HEALTHY
                        item.advisoryOnly -> FlameTone.WARNING
                        else -> FlameTone.DANGER
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            FlameRowAvatar(tone = itemTone) {
                                Icon(
                                    imageVector = if (item.passed) {
                                        Icons.Outlined.CheckCircle
                                    } else {
                                        Icons.Outlined.WarningAmber
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.ink,
                                modifier = Modifier.weight(1f),
                            )
                            FlameStatusPill(
                                text = item.statusLabel,
                                tone = itemTone,
                                showDot = false,
                            )
                        }

                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!item.passed && !item.advisoryOnly) {
                                SettingsPrimaryButton(
                                    text = item.actionLabel,
                                    onClick = { onOpenSurvivalAction(item.id) },
                                )
                            } else {
                                SettingsSecondaryButton(
                                    text = item.actionLabel,
                                    onClick = { onOpenSurvivalAction(item.id) },
                                )
                            }

                            if (item.id == SurvivalCheckId.MIUI_AUTOSTART_AND_BACKGROUND && snapshot.isXiaomiOrHyperOs) {
                                SettingsSecondaryButton(
                                    text = "神隐 / 省电策略",
                                    onClick = onOpenMiuiPowerHideSettings,
                                )
                            }
                        }

                        if (item.id == SurvivalCheckId.MIUI_AUTOSTART_AND_BACKGROUND && snapshot.isXiaomiOrHyperOs) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onAutostartConfirmedChanged(!item.passed) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Checkbox(
                                    checked = item.passed,
                                    onCheckedChange = onAutostartConfirmedChanged,
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = palette.monoCtaContainer,
                                        checkmarkColor = palette.monoCtaContent,
                                        uncheckedColor = palette.hairlineStrong,
                                    ),
                                )
                                Text(
                                    text = "我已在系统设置开启「自启动」并设为省电「无限制」",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.ink,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    if (!isLast) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            thickness = 1.dp,
                            color = palette.hairline,
                        )
                    }
                }
            }
        }
    }
}
