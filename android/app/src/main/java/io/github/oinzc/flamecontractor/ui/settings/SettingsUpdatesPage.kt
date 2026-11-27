package io.github.oinzc.flamecontractor.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.BuildConfig
import io.github.oinzc.flamecontractor.core.support.ProjectLinks
import io.github.oinzc.flamecontractor.core.update.AppUpdateCheckResult
import io.github.oinzc.flamecontractor.core.update.AppUpdateManager
import io.github.oinzc.flamecontractor.core.update.AppUpdateStatus
import io.github.oinzc.flamecontractor.core.update.AppUpdateUiState
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameListRow
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.flamePressScale
import io.github.oinzc.flamecontractor.ui.components.performFlameLightTick
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import io.github.oinzc.flamecontractor.ui.update.formatLastCheckedLabel
import io.github.oinzc.flamecontractor.ui.update.formatPrefixedVersion
import io.github.oinzc.flamecontractor.ui.update.formatUpdateStatusSummary
import io.github.oinzc.flamecontractor.ui.update.resolveReleaseFallbackUrl
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsUpdatesPage(
    appUpdateManager: AppUpdateManager?,
    updateUiState: AppUpdateUiState?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    embeddedInSplitPane: Boolean = false,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors

    val versionLabel = remember {
        ProjectLinks.formatVersionLabel(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }

    fun showNotice(message: String) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    fun openUrlWithFeedback(url: String) {
        haptics.performFlameLightTick()
        if (!openExternalProjectUrl(context, url)) {
            copyPlainText(context, label = "project_url", text = url)
            showNotice("未检测到可用浏览器，已复制链接到剪贴板")
        }
    }

    FlameSubpageScaffold(
        title = SettingsSection.UPDATES.title,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        embeddedInSplitPane = embeddedInSplitPane,
    ) { innerPadding ->
        SettingsPageScrollColumn(
            contentPadding = innerPadding,
            fullWidthInPane = embeddedInSplitPane,
        ) {
            // 1. 应用版本与自动更新主卡片
            AppUpdateSectionCard(
                versionLabel = versionLabel,
                updateUiState = updateUiState,
                canManageUpdates = appUpdateManager != null,
                onAutoCheckChanged = { enabled ->
                    haptics.performFlameLightTick()
                    appUpdateManager?.setAutoCheckEnabled(enabled)
                },
                onCheckNow = {
                    val manager = appUpdateManager ?: return@AppUpdateSectionCard
                    haptics.performFlameLightTick()
                    coroutineScope.launch {
                        when (val result = manager.checkForUpdates(manual = true)) {
                            AppUpdateCheckResult.UpToDate ->
                                showNotice("已是最新版本（$versionLabel）")

                            is AppUpdateCheckResult.NotConfigured ->
                                showNotice(result.message)

                            is AppUpdateCheckResult.Error ->
                                showNotice(result.message)

                            is AppUpdateCheckResult.UpdateAvailable ->
                                showNotice("发现新版本 ${formatPrefixedVersion(result.release.versionName)}")

                            AppUpdateCheckResult.Skipped -> Unit
                        }
                    }
                },
                onReopenUpdateDialog = {
                    haptics.performFlameLightTick()
                    appUpdateManager?.reopenUpdateDialog()
                },
                onOpenReleases = {
                    openUrlWithFeedback(resolveReleaseFallbackUrl(updateUiState?.activeRelease))
                },
            )

            // 2. 项目名片与 GitHub 源码直达
            FlameCard(contentPadding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(palette.emberBright, palette.ember),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LocalFireDepartment,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "焰之契约者 · Flame Contractor",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = palette.ink,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FlameMiniTag(text = versionLabel, tone = FlameTone.EMBER)
                                FlameMiniTag(text = "MIT 开源", tone = FlameTone.NEUTRAL)
                            }
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val repoInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = { openUrlWithFeedback(ProjectLinks.REPO_URL) },
                            interactionSource = repoInteraction,
                            modifier = Modifier.flamePressScale(interactionSource = repoInteraction),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.monoCtaContainer,
                                contentColor = palette.monoCtaContent,
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Code,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "打开 GitHub 源码",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }

                        val copyInteraction = remember { MutableInteractionSource() }
                        OutlinedButton(
                            onClick = {
                                haptics.performFlameLightTick()
                                copyPlainText(context, label = "repo_url", text = ProjectLinks.REPO_URL)
                                showNotice("已复制 GitHub 仓库地址")
                            },
                            interactionSource = copyInteraction,
                            modifier = Modifier.flamePressScale(interactionSource = copyInteraction),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, palette.hairlineStrong),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "复制仓库地址",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 5.dp),
                            )
                        }
                    }
                }
            }

            // 3. 发布与开源渠道
            FlameGroupedSection(title = "发布与开源渠道") {
                FlameListRow(
                    title = "GitHub 源码仓库",
                    subtitle = ProjectLinks.REPO_DISPLAY_PATH,
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
                    showDivider = true,
                    onClick = { openUrlWithFeedback(ProjectLinks.REPO_URL) },
                )

                FlameListRow(
                    title = "历史版本发布（Releases）",
                    subtitle = "查看完整更新日志，获取 Android APK 与 Windows 安装包",
                    leading = {
                        FlameRowAvatar(tone = FlameTone.NEUTRAL) {
                            Icon(
                                imageVector = Icons.Outlined.NewReleases,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = { ExternalLinkTrailingIcon() },
                    showDivider = true,
                    onClick = { openUrlWithFeedback(ProjectLinks.RELEASES_URL) },
                )

                FlameListRow(
                    title = "GitHub 社区讨论与 Issue",
                    subtitle = "浏览问题修复进展或参与开源讨论",
                    leading = {
                        FlameRowAvatar(tone = FlameTone.NEUTRAL) {
                            Icon(
                                imageVector = Icons.Outlined.Forum,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = { ExternalLinkTrailingIcon() },
                    showDivider = false,
                    onClick = { openUrlWithFeedback(ProjectLinks.ISSUES_URL) },
                )
            }

            // 4. 升级安全与数据保留说明
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = palette.surfaceSubtle,
                border = BorderStroke(1.dp, palette.hairline),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VerifiedUser,
                        contentDescription = null,
                        tint = semantics.healthy,
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(17.dp),
                    )
                    Text(
                        text = "安全更新说明：正式版更新包均强制 HTTPS 下载与 SHA-256 完整性校验；覆盖升级完整保留所有抖音账号、登录态、好友与模板数据，并自动恢复定时续火调度。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppUpdateSectionCard(
    versionLabel: String,
    updateUiState: AppUpdateUiState?,
    canManageUpdates: Boolean,
    onAutoCheckChanged: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onReopenUpdateDialog: () -> Unit,
    onOpenReleases: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val status = updateUiState?.status ?: AppUpdateStatus.Idle
    val isConfigured = updateUiState?.isConfigured ?: false
    val isChecking = status is AppUpdateStatus.Checking
    val hasAvailableUpdate = updateUiState?.hasAvailableUpdate == true
    val autoCheckEnabled = updateUiState?.autoCheckEnabled ?: true

    val nowEpochMs = remember(updateUiState?.lastCheckedAtEpochMs, status) {
        System.currentTimeMillis()
    }
    val statusSummary = remember(updateUiState, versionLabel) {
        updateUiState?.let(::formatUpdateStatusSummary) ?: "当前版本 $versionLabel"
    }
    val lastCheckedLabel = remember(updateUiState?.lastCheckedAtEpochMs, nowEpochMs) {
        formatLastCheckedLabel(updateUiState?.lastCheckedAtEpochMs, nowEpochMs)
    }

    val avatarTone = when {
        status is AppUpdateStatus.ReadyToInstall || status is AppUpdateStatus.UpToDate -> FlameTone.HEALTHY
        hasAvailableUpdate -> FlameTone.EMBER
        status is AppUpdateStatus.Error && !isConfigured -> FlameTone.NEUTRAL
        status is AppUpdateStatus.Error -> FlameTone.DANGER
        else -> FlameTone.NEUTRAL
    }

    FlameCard(contentPadding = PaddingValues(16.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                FlameRowAvatar(tone = avatarTone) {
                    Icon(
                        imageVector = if (status is AppUpdateStatus.UpToDate) {
                            Icons.Outlined.CheckCircle
                        } else {
                            Icons.Outlined.SystemUpdate
                        },
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
                            text = "应用版本与自动更新",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = palette.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        FlameMiniTag(
                            text = versionLabel,
                            tone = FlameTone.NEUTRAL,
                        )
                    }
                    Text(
                        text = statusSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                when (status) {
                    is AppUpdateStatus.UpdateAvailable -> FlameStatusPill(
                        text = "可更新",
                        tone = FlameTone.EMBER,
                    )

                    is AppUpdateStatus.Downloading -> {
                        val pct = status.progressPercent?.coerceIn(0, 100)
                        FlameStatusPill(
                            text = if (pct != null) "$pct%" else "下载中",
                            tone = FlameTone.EMBER,
                        )
                    }

                    is AppUpdateStatus.Verifying -> FlameStatusPill(
                        text = "校验中",
                        tone = FlameTone.EMBER,
                    )

                    is AppUpdateStatus.ReadyToInstall -> FlameStatusPill(
                        text = "待安装",
                        tone = FlameTone.HEALTHY,
                    )

                    AppUpdateStatus.UpToDate -> FlameStatusPill(
                        text = "已最新",
                        tone = FlameTone.HEALTHY,
                    )

                    AppUpdateStatus.Checking -> FlameStatusPill(
                        text = "检查中",
                        tone = FlameTone.NEUTRAL,
                        showDot = false,
                    )

                    is AppUpdateStatus.Error -> if (updateUiState?.isConfigured == false) {
                        FlameStatusPill(
                            text = "未配置",
                            tone = FlameTone.NEUTRAL,
                            showDot = false,
                        )
                    } else {
                        FlameStatusPill(
                            text = "检查异常",
                            tone = FlameTone.DANGER,
                        )
                    }

                    AppUpdateStatus.Idle -> if (updateUiState != null && !isConfigured) {
                        FlameStatusPill(
                            text = "未配置",
                            tone = FlameTone.NEUTRAL,
                            showDot = false,
                        )
                    }
                }
            }

            if (updateUiState != null && isConfigured) {
                Text(
                    text = "最近检查：$lastCheckedLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkTertiary,
                )
            }

            if (status is AppUpdateStatus.Error && status.release == null) {
                SettingsFeedbackBanner(
                    message = status.message.trim().ifEmpty { "检查更新失败，请稍后重试" },
                    tone = if (isConfigured) FlameTone.DANGER else FlameTone.NEUTRAL,
                )
            }

            HorizontalDivider(thickness = 1.dp, color = palette.hairline)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = canManageUpdates) {
                        onAutoCheckChanged(!autoCheckEnabled)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "启动时自动检查更新",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.ink,
                    )
                    Text(
                        text = "冷启动静默检查新版本（6 小时内不重复打扰，支持手动一键检查）",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )
                }
                Switch(
                    checked = autoCheckEnabled,
                    enabled = canManageUpdates,
                    onCheckedChange = onAutoCheckChanged,
                    colors = settingsSwitchColors(),
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasAvailableUpdate) {
                    val primaryText = when (status) {
                        is AppUpdateStatus.ReadyToInstall -> "立即安装"
                        is AppUpdateStatus.Downloading,
                        is AppUpdateStatus.Verifying,
                        -> "查看下载进度"

                        else -> "查看新版本"
                    }
                    val openDialogInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = onReopenUpdateDialog,
                        enabled = canManageUpdates,
                        interactionSource = openDialogInteraction,
                        modifier = Modifier.flamePressScale(
                            interactionSource = openDialogInteraction,
                            enabled = canManageUpdates,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.monoCtaContainer,
                            contentColor = palette.monoCtaContent,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = primaryText,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }

                    if (status is AppUpdateStatus.UpdateAvailable || status is AppUpdateStatus.Error) {
                        OutlinedButton(
                            onClick = onCheckNow,
                            enabled = canManageUpdates && !isChecking,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, palette.hairlineStrong),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "重新检查",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 5.dp),
                            )
                        }
                    }
                } else {
                    val checkInteraction = remember { MutableInteractionSource() }
                    val canCheck = canManageUpdates && !isChecking
                    Button(
                        onClick = onCheckNow,
                        enabled = canCheck,
                        interactionSource = checkInteraction,
                        modifier = Modifier.flamePressScale(
                            interactionSource = checkInteraction,
                            enabled = canCheck,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.monoCtaContainer,
                            contentColor = palette.monoCtaContent,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        if (isChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = palette.monoCtaContent,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            text = if (isChecking) "正在检查…" else "立即检查更新",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }

                    if ((updateUiState != null && !isConfigured) || status is AppUpdateStatus.Error) {
                        OutlinedButton(
                            onClick = onOpenReleases,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, palette.hairlineStrong),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "前往 GitHub Releases",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 5.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
