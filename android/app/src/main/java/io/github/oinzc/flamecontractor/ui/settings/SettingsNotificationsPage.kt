package io.github.oinzc.flamecontractor.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameListRow
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsNotificationsPage(
    uiState: SettingsUiState,
    onBarkInputChanged: (String) -> Unit,
    onSaveBarkConfig: () -> Unit,
    onClearBarkConfig: () -> Unit,
    onSendBarkTestProbe: () -> Unit,
    onRequestOrOpenNotifications: () -> Unit,
    onOpenChannelSettings: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    embeddedInSplitPane: Boolean = false,
) {
    val palette = FlameThemeTokens.palette
    val survival = uiState.survivalSnapshot
    val textFieldColors = settingsTextFieldColors()

    FlameSubpageScaffold(
        title = SettingsSection.NOTIFICATIONS.title,
        onBack = onBack,
        modifier = modifier,
        embeddedInSplitPane = embeddedInSplitPane,
    ) { innerPadding ->
        SettingsPageScrollColumn(
            contentPadding = innerPadding,
            fullWidthInPane = embeddedInSplitPane,
        ) {
            // 1. 本机通知总权限与严格既有的 2 个通知通道（紧急告警 + 每日汇总）
            FlameGroupedSection(title = "本机通知权限与分类通道") {
                FlameListRow(
                    title = "系统通知总权限",
                    subtitle = if (survival.notificationsGranted) {
                        "已授权，可正常推送每日汇总与异常告警"
                    } else {
                        "未授权，掉登录或待人工核对时将无法弹出提醒"
                    },
                    leading = {
                        FlameRowAvatar(
                            tone = if (survival.notificationsGranted) FlameTone.HEALTHY else FlameTone.DANGER,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.NotificationsActive,
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
                                text = if (survival.notificationsGranted) "已授权" else "去开启",
                                tone = if (survival.notificationsGranted) FlameTone.HEALTHY else FlameTone.DANGER,
                                showDot = false,
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                tint = palette.inkTertiary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    showDivider = true,
                    onClick = onRequestOrOpenNotifications,
                )

                // 通道 1：紧急告警（LocalNotifier.CHANNEL_ALERTS_URGENT）
                FlameListRow(
                    title = "紧急告警通道",
                    subtitle = "登录失效、风控验证、结构异常、熔断与待人工核对告警",
                    leading = {
                        FlameRowAvatar(
                            tone = if (survival.urgentAlertsChannelEnabled) FlameTone.WARNING else FlameTone.DANGER,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.WarningAmber,
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
                                text = if (survival.urgentAlertsChannelEnabled) "已开启" else "已关闭",
                                tone = if (survival.urgentAlertsChannelEnabled) FlameTone.HEALTHY else FlameTone.DANGER,
                                showDot = false,
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                tint = palette.inkTertiary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    showDivider = true,
                    onClick = { onOpenChannelSettings(LocalNotifier.CHANNEL_ALERTS_URGENT) },
                )

                // 通道 2：每日汇总（LocalNotifier.CHANNEL_DAILY_SUMMARY）
                FlameListRow(
                    title = "每日汇总通道",
                    subtitle = "每日自动续火完成后的脱敏状态汇总通知",
                    leading = {
                        FlameRowAvatar(
                            tone = if (survival.dailySummaryChannelEnabled) FlameTone.HEALTHY else FlameTone.NEUTRAL,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.EventNote,
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
                                text = if (survival.dailySummaryChannelEnabled) "已开启" else "已关闭",
                                tone = if (survival.dailySummaryChannelEnabled) FlameTone.HEALTHY else FlameTone.WARNING,
                                showDot = false,
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                tint = palette.inkTertiary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    showDivider = false,
                    onClick = { onOpenChannelSettings(LocalNotifier.CHANNEL_DAILY_SUMMARY) },
                )
            }

            // 2. Bark 远程推送
            FlameGroupedSection(
                title = "Bark 远程推送",
                trailingHeader = {
                    FlameStatusPill(
                        text = if (uiState.barkConfigured) "已加密保存" else "未配置",
                        tone = if (uiState.barkConfigured) FlameTone.HEALTHY else FlameTone.NEUTRAL,
                    )
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Bark 密钥经 Android Keystore AES-GCM 加密落盘，界面仅回显脱敏摘要。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )

                    uiState.maskedBarkUrl?.let { masked ->
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
                                    text = "当前已生效地址（脱敏）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.inkTertiary,
                                )
                                Text(
                                    text = masked,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = palette.ink,
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = uiState.barkInputText,
                        onValueChange = onBarkInputChanged,
                        label = { Text("Bark 地址或整段 curl 命令") },
                        placeholder = { Text("https://api.day.app/<key> 或直接粘贴 Bark App 复制的 curl") },
                        minLines = 2,
                        maxLines = 5,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    uiState.barkError?.let { err ->
                        SettingsFeedbackBanner(message = err, tone = FlameTone.DANGER)
                    }
                    uiState.barkMessage?.let { msg ->
                        SettingsFeedbackBanner(message = msg, tone = FlameTone.HEALTHY)
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsPrimaryButton(
                            text = "保存并加密",
                            loading = uiState.isSavingBark,
                            enabled = !uiState.isSavingBark && !uiState.isProbingBark,
                            onClick = onSaveBarkConfig,
                        )
                        SettingsSecondaryButton(
                            text = if (uiState.isProbingBark) "发送测试中…" else "发送测试推送",
                            loading = uiState.isProbingBark,
                            enabled = uiState.barkConfigured && !uiState.isSavingBark && !uiState.isProbingBark,
                            onClick = onSendBarkTestProbe,
                        )
                        if (uiState.barkConfigured || uiState.barkInputText.isNotEmpty()) {
                            SettingsDangerTextButton(
                                text = "清空 Bark",
                                enabled = !uiState.isSavingBark && !uiState.isProbingBark,
                                onClick = onClearBarkConfig,
                            )
                        }
                    }
                }
            }
        }
    }
}
