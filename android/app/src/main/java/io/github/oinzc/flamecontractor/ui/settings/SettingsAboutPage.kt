package io.github.oinzc.flamecontractor.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.BuildConfig
import io.github.oinzc.flamecontractor.core.support.ProjectLinks
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameListRow
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.flamePressScale
import io.github.oinzc.flamecontractor.ui.components.performFlameLightTick
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsAboutPage(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
    val bugReportUrl = remember(uiState.selectorDigest) {
        ProjectLinks.buildAndroidBugReportUrl(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT,
            selectorDigest = uiState.selectorDigest,
        )
    }
    val envSummary = remember(uiState.selectorDigest, uiState.survivalSnapshot) {
        ProjectLinks.buildDesensitizedEnvSummary(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = uiState.survivalSnapshot.manufacturer,
            selectorDigest = uiState.selectorDigest,
            survivalPassed = uiState.survivalSnapshot.passedCount,
            survivalTotal = uiState.survivalSnapshot.totalCount,
        )
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
        title = SettingsSection.ABOUT.title,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) { innerPadding ->
        SettingsPageScrollColumn(contentPadding = innerPadding) {
            // 1. 项目名片与直达按钮
            FlameCard(contentPadding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(14.dp))
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
                                modifier = Modifier.size(25.dp),
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

                    Text(
                        text = "本机优先的抖音续火助手：所有账号、好友、模板与登录态仅保留在你自己的设备上。欢迎通过 GitHub 提交问题反馈或改进建议，或直接查看开源源码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )

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

            // 2. 问题反馈与改进建议
            FlameGroupedSection(title = "问题反馈与建议") {
                FlameListRow(
                    title = "提交 Bug 报告",
                    subtitle = "自动预填当前版本与 Android 系统环境，直达 GitHub 问题表单",
                    leading = {
                        FlameRowAvatar(tone = FlameTone.DANGER) {
                            Icon(
                                imageVector = Icons.Outlined.BugReport,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = { ExternalLinkTrailingIcon() },
                    showDivider = true,
                    onClick = { openUrlWithFeedback(bugReportUrl) },
                )

                FlameListRow(
                    title = "提交功能建议",
                    subtitle = "有好点子、交互优化或新需求？直接告诉作者持续改进",
                    leading = {
                        FlameRowAvatar(tone = FlameTone.EMBER) {
                            Icon(
                                imageVector = Icons.Outlined.Lightbulb,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = { ExternalLinkTrailingIcon() },
                    showDivider = true,
                    onClick = { openUrlWithFeedback(ProjectLinks.buildFeatureRequestUrl()) },
                )

                FlameListRow(
                    title = "参与社区讨论",
                    subtitle = "前往 GitHub Discussions 交流使用技巧与部署问题",
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
                    showDivider = true,
                    onClick = { openUrlWithFeedback(ProjectLinks.DISCUSSIONS_URL) },
                )

                FlameListRow(
                    title = "复制脱敏环境信息",
                    subtitle = "一键复制版本号、Android 版本与定位器摘要，方便粘贴至反馈",
                    leading = {
                        FlameRowAvatar(tone = FlameTone.HEALTHY) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = {
                        Text(
                            text = "复制",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkSecondary,
                        )
                    },
                    showDivider = false,
                    onClick = {
                        haptics.performFlameLightTick()
                        copyPlainText(context, label = "flame_env_summary", text = envSummary)
                        showNotice("已复制脱敏环境信息（不含好友与账号隐私）")
                    },
                )
            }

            // 3. 项目源码与版本发布
            FlameGroupedSection(title = "项目源码与发布") {
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
                    title = "查看全部 Issue 与进展",
                    subtitle = "浏览已知问题、修复进度与后续开发计划",
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
                    showDivider = true,
                    onClick = { openUrlWithFeedback(ProjectLinks.ISSUES_URL) },
                )

                FlameListRow(
                    title = "版本发布（Releases）",
                    subtitle = "查看更新日志，获取最新 Android APK 与 Windows 桌面安装包",
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
                    showDivider = false,
                    onClick = { openUrlWithFeedback(ProjectLinks.RELEASES_URL) },
                )
            }

            // 4. 隐私保护提醒
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
                        text = "隐私提示：公开提交 Issue 时，请勿附带真实好友昵称、抖音号 ID、Cookie、Bark 推送地址或包含敏感信息的截图。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ExternalLinkTrailingIcon() {
    Icon(
        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
        contentDescription = "在浏览器打开",
        tint = FlameThemeTokens.palette.inkTertiary,
        modifier = Modifier.size(17.dp),
    )
}

internal fun openExternalProjectUrl(context: Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

internal fun openExternalUrlOrCopyToast(context: Context, url: String) {
    if (!openExternalProjectUrl(context, url)) {
        copyPlainText(context, label = "project_url", text = url)
        Toast.makeText(context, "未检测到可用浏览器，已复制链接到剪贴板", Toast.LENGTH_SHORT).show()
    }
}

private fun copyPlainText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
