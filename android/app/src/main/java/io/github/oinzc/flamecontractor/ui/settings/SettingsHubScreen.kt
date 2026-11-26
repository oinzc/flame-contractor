package io.github.oinzc.flamecontractor.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.BuildConfig
import io.github.oinzc.flamecontractor.core.support.ProjectLinks
import io.github.oinzc.flamecontractor.core.theme.ThemePreference
import io.github.oinzc.flamecontractor.core.theme.UiDisplayMode
import io.github.oinzc.flamecontractor.core.update.AppUpdateStatus
import io.github.oinzc.flamecontractor.core.update.AppUpdateUiState
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.ui.components.FlameAlertStrip
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameListRow
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.performFlameLightTick
import io.github.oinzc.flamecontractor.ui.components.rememberReducedMotion
import io.github.oinzc.flamecontractor.ui.setup.SetupReadinessBuilder
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import io.github.oinzc.flamecontractor.ui.update.formatPrefixedVersion

@Composable
internal fun SettingsHubScreen(
    uiState: SettingsUiState,
    themePreference: ThemePreference,
    onSelectSection: (SettingsSection) -> Unit,
    onStartOnboardingWizard: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    updateUiState: AppUpdateUiState? = null,
    onOpenUpdateDialog: () -> Unit = {},
    onSwitchToStandardMode: () -> Unit = {},
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val palette = FlameThemeTokens.palette
    val reducedMotion = rememberReducedMotion()
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
            AnimatedVisibility(
                visible = themePreference.displayMode == UiDisplayMode.SIMPLE,
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
                SimpleModeQuickReturnCard(
                    onSwitchToStandard = {
                        haptics.performFlameLightTick()
                        onSwitchToStandardMode()
                    },
                )
            }

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

            // 0. 上手与配置状态（集中展示全部权限与业务就绪完成度）
            val readiness = SetupReadinessBuilder.build(
                survivalSnapshot = uiState.survivalSnapshot,
                accountStatus = uiState.accountStatus,
                enabledTargetCount = uiState.enabledTargetCount,
            )
            val readinessTone = when {
                readiness.allCompleted -> FlameTone.HEALTHY
                !readiness.allRequiredCompleted -> FlameTone.WARNING
                else -> FlameTone.NEUTRAL
            }
            val readinessSummary = if (readiness.allCompleted) {
                "${readiness.vendorFamily.displayName} · 全部 ${readiness.totalCount} 项系统权限与账号配置已就绪"
            } else {
                val pendingName = readiness.nextPendingItem?.title ?: "查看未完成项"
                "${readiness.vendorFamily.displayName} · 下一项：$pendingName"
            }

            FlameGroupedSection(title = "上手与配置状态") {
                FlameListRow(
                    title = SettingsSection.SURVIVAL.title,
                    subtitle = readinessSummary,
                    leading = {
                        FlameRowAvatar(tone = readinessTone) {
                            Icon(
                                imageVector = if (readiness.allCompleted) {
                                    Icons.Outlined.CheckCircle
                                } else {
                                    Icons.Outlined.Security
                                },
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
                                text = "${readiness.completedCount}/${readiness.totalCount} 完成",
                                tone = readinessTone,
                                showDot = false,
                            )
                            HubChevron()
                        }
                    },
                    showDivider = true,
                    onClick = { onSelectSection(SettingsSection.SURVIVAL) },
                )

                FlameListRow(
                    title = "新手分步设置引导",
                    subtitle = "逐项说明每项权限的作用并一步步完成设置（全品牌安卓适用）",
                    leading = {
                        FlameRowAvatar(tone = FlameTone.EMBER) {
                            Icon(
                                imageVector = Icons.Outlined.PlayCircleOutline,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = { HubChevron() },
                    showDivider = false,
                    onClick = {
                        haptics.performFlameLightTick()
                        onStartOnboardingWizard()
                    },
                )
            }

            // 快捷醒目入口：问题反馈与建议（紧跟上手清单，首屏可见）
            ProminentFeedbackHubCard(
                onClick = {
                    haptics.performFlameLightTick()
                    onSelectSection(SettingsSection.ABOUT)
                },
            )

            // 1. 个性化
            val appearanceSummary =
                "${themeModeLabel(themePreference.mode)} · ${displayModeLabel(themePreference.displayMode)} · 桌面状态小组件"

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
                    showDivider = false,
                    onClick = { onSelectSection(SettingsSection.NOTIFICATIONS) },
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

            // 4. 关于与开源源码
            val versionTag = ProjectLinks.formatVersionLabel(BuildConfig.VERSION_NAME, versionCode = 0)
            val hasAvailableUpdate = updateUiState?.hasAvailableUpdate == true
            val updateRowSubtitle = when (val updateStatus = updateUiState?.status) {
                is AppUpdateStatus.UpdateAvailable ->
                    "发现新版本 ${formatPrefixedVersion(updateStatus.release.versionName)}"

                is AppUpdateStatus.Downloading -> {
                    val v = formatPrefixedVersion(updateStatus.release.versionName)
                    val pct = updateStatus.progressPercent?.coerceIn(0, 100)
                    if (pct != null) "正在下载 $v · $pct%" else "正在下载 $v…"
                }

                is AppUpdateStatus.Verifying ->
                    "正在校验 ${formatPrefixedVersion(updateStatus.release.versionName)} 安装包…"

                is AppUpdateStatus.ReadyToInstall ->
                    "发现新版本 ${formatPrefixedVersion(updateStatus.release.versionName)} · 已就绪待安装"

                AppUpdateStatus.Checking -> "正在检查更新…"
                AppUpdateStatus.UpToDate -> "当前版本 $versionTag · 已是最新版本"
                else -> "当前版本 $versionTag"
            }
            FlameGroupedSection(title = "版本与开源") {
                FlameListRow(
                    title = SettingsSection.UPDATES.title,
                    subtitle = updateRowSubtitle,
                    leading = {
                        FlameRowAvatar(
                            tone = if (hasAvailableUpdate) FlameTone.EMBER else FlameTone.NEUTRAL,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SystemUpdate,
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
                            if (hasAvailableUpdate) {
                                FlameStatusPill(
                                    text = "可更新",
                                    tone = FlameTone.EMBER,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .clickable {
                                            haptics.performFlameLightTick()
                                            onOpenUpdateDialog()
                                        },
                                )
                            }
                            HubChevron()
                        }
                    },
                    showDivider = true,
                    onClick = {
                        haptics.performFlameLightTick()
                        onSelectSection(SettingsSection.UPDATES)
                    },
                )

                FlameListRow(
                    title = "GitHub 项目源码",
                    subtitle = "${ProjectLinks.REPO_DISPLAY_PATH} · $versionTag",
                    leading = {
                        FlameRowAvatar(tone = FlameTone.NEUTRAL) {
                            Icon(
                                imageVector = Icons.Outlined.Code,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = { ExternalLinkTrailingIcon() },
                    showDivider = false,
                    onClick = {
                        haptics.performFlameLightTick()
                        openExternalUrlOrCopyToast(context, ProjectLinks.REPO_URL)
                    },
                )
            }
        }
    }
}

@Composable
private fun SimpleModeQuickReturnCard(
    onSwitchToStandard: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    FlameCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        containerColor = palette.surface,
        borderColor = palette.emberBorder,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
        onClick = onSwitchToStandard,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlameRowAvatar(tone = FlameTone.EMBER) {
                Icon(
                    imageVector = Icons.Outlined.Dashboard,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "当前处于极简模式",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    FlameMiniTag(text = "单页大圆", tone = FlameTone.EMBER)
                }
                Text(
                    text = "已隐藏底栏、模板库与 30 日记录，点击可一键恢复完整标准模式",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = palette.monoCtaContainer,
                contentColor = palette.monoCtaContent,
            ) {
                Text(
                    text = "切回标准模式",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ProminentFeedbackHubCard(
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    FlameCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        containerColor = palette.surface,
        borderColor = palette.emberBorder,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlameRowAvatar(tone = FlameTone.EMBER) {
                Icon(
                    imageVector = Icons.Outlined.Feedback,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "问题反馈与建议",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    FlameMiniTag(text = "可传截图", tone = FlameTone.EMBER)
                }
                Text(
                    text = "支持传截图 · 无需翻墙 · 遇到问题或有优化建议随时告诉我",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = palette.emberContainer,
                contentColor = palette.ember,
                border = BorderStroke(1.dp, palette.emberBorder),
            ) {
                Text(
                    text = "去反馈",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
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
