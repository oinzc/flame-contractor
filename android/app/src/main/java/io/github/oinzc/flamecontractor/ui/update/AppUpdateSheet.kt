package io.github.oinzc.flamecontractor.ui.update

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.oinzc.flamecontractor.core.support.ProjectLinks
import io.github.oinzc.flamecontractor.core.update.AppUpdateInstallOutcome
import io.github.oinzc.flamecontractor.core.update.AppUpdateManager
import io.github.oinzc.flamecontractor.core.update.AppUpdateRelease
import io.github.oinzc.flamecontractor.core.update.AppUpdateStatus
import io.github.oinzc.flamecontractor.core.update.AppUpdateUiState
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameModalSheet
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.flamePressScale
import io.github.oinzc.flamecontractor.ui.components.performFlameLightTick
import io.github.oinzc.flamecontractor.ui.components.rememberReducedMotion
import io.github.oinzc.flamecontractor.ui.components.resolveFlameToneColors
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private const val BYTES_PER_KB: Long = 1024L
private const val BYTES_PER_MB: Long = 1024L * 1024L
private const val BYTES_PER_GB: Long = 1024L * 1024L * 1024L
private const val MILLIS_PER_MINUTE: Long = 60_000L
private const val MILLIS_PER_HOUR: Long = 60L * MILLIS_PER_MINUTE
private const val MILLIS_PER_DAY: Long = 24L * MILLIS_PER_HOUR
private val ISO_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

/**
 * 格式化 APK 安装包字节体积为可读字符串（如 `18.4 MB`、`512.0 KB`、`0 B`）。
 */
fun formatApkFileSize(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe < BYTES_PER_KB -> "$safe B"
        safe < BYTES_PER_MB -> String.format(Locale.US, "%.1f KB", safe / BYTES_PER_KB.toDouble())
        safe < BYTES_PER_GB -> String.format(Locale.US, "%.1f MB", safe / BYTES_PER_MB.toDouble())
        else -> String.format(Locale.US, "%.2f GB", safe / BYTES_PER_GB.toDouble())
    }
}

/**
 * 将远端清单中的 ISO-8601 发布时间（支持 `Z`、时区偏移、`yyyy-MM-dd` 等）格式化为 `yyyy-MM-dd`；
 * 空字符串或无效格式返回空串。
 */
fun formatUpdatePublishedDate(iso: String): String {
    val trimmed = iso.trim()
    if (trimmed.isEmpty()) return ""
    val localDate = runCatching { OffsetDateTime.parse(trimmed).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(trimmed) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(trimmed).toLocalDate() }.getOrNull()
        ?: return ""
    return localDate.format(ISO_DATE_FORMATTER)
}

/**
 * 格式化最近一次检查更新的相对时间文案（纯函数，无系统时钟依赖）。
 */
fun formatLastCheckedLabel(epochMs: Long?, nowEpochMs: Long): String {
    if (epochMs == null || epochMs <= 0L) return "尚未检查"
    val diffMs = nowEpochMs - epochMs
    if (diffMs < MILLIS_PER_MINUTE) return "刚刚检查"
    if (diffMs < MILLIS_PER_HOUR) {
        val minutes = diffMs / MILLIS_PER_MINUTE
        return "$minutes 分钟前检查"
    }
    if (diffMs < MILLIS_PER_DAY) {
        val hours = diffMs / MILLIS_PER_HOUR
        return "$hours 小时前检查"
    }
    val days = diffMs / MILLIS_PER_DAY
    return "$days 天前检查"
}

internal fun formatPrefixedVersion(rawVersion: String): String {
    val trimmed = rawVersion.trim()
    if (trimmed.isEmpty()) return "v?"
    return if (trimmed.startsWith("v", ignoreCase = true) && trimmed.length > 1 && trimmed[1].isDigit()) {
        "v${trimmed.substring(1)}"
    } else if (trimmed.startsWith("v", ignoreCase = true)) {
        trimmed
    } else {
        "v$trimmed"
    }
}

internal fun formatVersionTransitionTag(
    currentVersionName: String,
    targetVersionName: String,
): String = "${formatPrefixedVersion(currentVersionName)} -> ${formatPrefixedVersion(targetVersionName)}"

internal fun formatUpdateDownloadProgressSummary(
    downloadedBytes: Long,
    totalBytes: Long,
    progressPercent: Int?,
): String {
    val downloadedLabel = formatApkFileSize(downloadedBytes)
    return when {
        totalBytes > 0L && progressPercent != null -> {
            val pct = progressPercent.coerceIn(0, 100)
            "已下载 $downloadedLabel / ${formatApkFileSize(totalBytes)} · $pct%"
        }

        totalBytes > 0L -> "已下载 $downloadedLabel / ${formatApkFileSize(totalBytes)}"
        else -> "已下载 $downloadedLabel"
    }
}

internal fun formatUpdateStatusSummary(uiState: AppUpdateUiState): String {
    val status = uiState.status
    if (!uiState.isConfigured && uiState.activeRelease == null && status !is AppUpdateStatus.Checking) {
        return "当前构建未配置自动更新源"
    }
    return when (status) {
        AppUpdateStatus.Idle -> "当前版本 ${formatPrefixedVersion(uiState.currentVersionName)}"
        AppUpdateStatus.Checking -> "正在检查更新…"
        AppUpdateStatus.UpToDate -> "已是最新版本"
        is AppUpdateStatus.UpdateAvailable ->
            "发现新版本 ${formatPrefixedVersion(status.release.versionName)}"

        is AppUpdateStatus.Downloading -> {
            val versionTag = formatPrefixedVersion(status.release.versionName)
            val pct = status.progressPercent?.coerceIn(0, 100)
            if (pct != null) "正在下载 $versionTag（$pct%）" else "正在下载 $versionTag…"
        }

        is AppUpdateStatus.Verifying ->
            "正在校验 ${formatPrefixedVersion(status.release.versionName)} 安装包完整性…"

        is AppUpdateStatus.ReadyToInstall ->
            "新版本 ${formatPrefixedVersion(status.release.versionName)} 已下载就绪，可立即安装"

        is AppUpdateStatus.Error ->
            status.message.trim().ifEmpty { "检查更新失败，请稍后重试" }
    }
}

internal fun resolveReleaseFallbackUrl(release: AppUpdateRelease?): String =
    release?.releasePageUrl?.trim()?.takeIf { it.isNotEmpty() } ?: ProjectLinks.RELEASES_URL

internal fun canDismissUpdateSheetByScrim(uiState: AppUpdateUiState): Boolean {
    if (uiState.isMandatoryUpdate) return false
    return when (uiState.status) {
        is AppUpdateStatus.Downloading,
        is AppUpdateStatus.Verifying,
        -> false

        AppUpdateStatus.Idle,
        AppUpdateStatus.Checking,
        AppUpdateStatus.UpToDate,
        is AppUpdateStatus.UpdateAvailable,
        is AppUpdateStatus.ReadyToInstall,
        is AppUpdateStatus.Error,
        -> true
    }
}

/**
 * 全局应用内自动检查更新弹窗（基于现有 [FlameModalSheet]）。
 * 支持更新提示、流式下载进度、SHA-256 完整性校验、未知来源安装权限引导及错误降级外链。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppUpdateSheet(
    uiState: AppUpdateUiState,
    appUpdateManager: AppUpdateManager,
) {
    val status = uiState.status
    val release = uiState.activeRelease
    if (release == null && status !is AppUpdateStatus.Error) {
        return
    }

    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()
    val palette = FlameThemeTokens.palette

    val isMandatory = uiState.isMandatoryUpdate
    val canDismissByScrim = canDismissUpdateSheetByScrim(uiState)
    var showPermissionGuide by remember(release?.versionCode) {
        mutableStateOf(uiState.pendingInstallAfterPermissionGrant)
    }
    var blockedDismissNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(uiState.pendingInstallAfterPermissionGrant, status) {
        if (uiState.pendingInstallAfterPermissionGrant) {
            showPermissionGuide = true
        } else if (status !is AppUpdateStatus.ReadyToInstall) {
            showPermissionGuide = false
        }
    }

    val sheetTitle = when (status) {
        is AppUpdateStatus.Downloading -> "正在下载更新"
        is AppUpdateStatus.Verifying -> "正在校验安装包"
        is AppUpdateStatus.ReadyToInstall -> "新版本已就绪"
        is AppUpdateStatus.Error -> if (release != null) "更新下载异常" else "检查更新失败"
        else -> "发现新版本"
    }

    val sheetSubtitle = when {
        release != null && isMandatory -> "当前版本低于最低兼容要求，请更新后继续使用"
        release != null -> "已通过 HTTPS 更新源获取最新安装包信息"
        else -> "请稍后重试或前往 GitHub Releases 手动下载"
    }

    val fallbackReleasesUrl = remember(release) { resolveReleaseFallbackUrl(release) }

    key(blockedDismissNonce) {
        FlameModalSheet(
            onDismissRequest = {
                if (canDismissByScrim) {
                    appUpdateManager.dismissDialog()
                } else {
                    blockedDismissNonce += 1
                }
            },
            title = sheetTitle,
            subtitle = sheetSubtitle,
            dismissOnClickOutside = canDismissByScrim,
            dismissOnBackPress = canDismissByScrim,
            showCloseButton = canDismissByScrim,
            trailingHeader = {
                when {
                    isMandatory -> FlameStatusPill(
                        text = "重要兼容更新",
                        tone = FlameTone.WARNING,
                    )

                    status is AppUpdateStatus.ReadyToInstall -> FlameStatusPill(
                        text = "待安装",
                        tone = FlameTone.HEALTHY,
                    )

                    status is AppUpdateStatus.Error -> FlameStatusPill(
                        text = "异常",
                        tone = FlameTone.DANGER,
                    )

                    else -> FlameStatusPill(
                        text = "新版本",
                        tone = FlameTone.EMBER,
                    )
                }
            },
        ) {
            BackHandler(enabled = !canDismissByScrim) {
                // 强制更新与下载/校验进行中禁止返回键误关闭
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (release != null) {
                    UpdateReleaseMetaRow(
                        currentVersionName = uiState.currentVersionName,
                        release = release,
                        isMandatory = isMandatory,
                    )
                }

                if (uiState.isDebugVariant) {
                    UpdateInfoBanner(
                        message = "当前为调试版（.debug），安装正式版将并存为独立应用，不会覆盖调试版",
                        tone = FlameTone.NEUTRAL,
                        icon = Icons.Outlined.Info,
                    )
                }

                if (release != null) {
                    UpdateChangelogBox(changelog = release.changelog)
                }

                when (status) {
                    is AppUpdateStatus.UpdateAvailable -> {
                        UpdatePrimaryActionButton(
                            text = "立即下载更新",
                            leadingIcon = Icons.Outlined.Download,
                            onClick = {
                                haptics.performFlameLightTick()
                                appUpdateManager.startDownload()
                            },
                        )
                        if (!isMandatory) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                UpdateSecondaryActionButton(
                                    text = "暂不更新",
                                    onClick = {
                                        haptics.performFlameLightTick()
                                        appUpdateManager.dismissDialog()
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                UpdateSecondaryActionButton(
                                    text = "忽略此版本",
                                    onClick = {
                                        haptics.performFlameLightTick()
                                        appUpdateManager.ignoreCurrentUpdate()
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = {
                                        haptics.performFlameLightTick()
                                        openUpdateExternalUrl(context, fallbackReleasesUrl)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = "前往 GitHub Releases",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = palette.inkSecondary,
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                        contentDescription = null,
                                        tint = palette.inkSecondary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }

                    is AppUpdateStatus.Downloading -> {
                        UpdateDownloadingCard(
                            status = status,
                            reducedMotion = reducedMotion,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            UpdateSecondaryActionButton(
                                text = "取消下载",
                                onClick = {
                                    haptics.performFlameLightTick()
                                    appUpdateManager.cancelDownload()
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (!isMandatory) {
                                UpdatePrimaryActionButton(
                                    text = "收起",
                                    onClick = {
                                        haptics.performFlameLightTick()
                                        appUpdateManager.dismissDialog(allowDuringDownload = true)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (!isMandatory) {
                            Text(
                                text = "收起后将在后台继续下载，可随时在「设置 → 关于与反馈」重新打开。",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.inkTertiary,
                            )
                        }
                    }

                    is AppUpdateStatus.Verifying -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = palette.surfaceSubtle,
                            border = BorderStroke(1.dp, palette.hairline),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.2.dp,
                                    color = palette.ember,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "正在校验安装包 SHA-256 完整性…",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = palette.ink,
                                    )
                                    Text(
                                        text = "校验通过后将开放一键安装，请稍候",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.inkSecondary,
                                    )
                                }
                            }
                        }
                        UpdatePrimaryActionButton(
                            text = "完整性校验中…",
                            enabled = false,
                            onClick = {},
                        )
                    }

                    is AppUpdateStatus.ReadyToInstall -> {
                        UpdateInfoBanner(
                            message = "已通过 SHA-256 完整性校验",
                            tone = FlameTone.HEALTHY,
                            icon = Icons.Outlined.VerifiedUser,
                        )

                        AnimatedVisibility(
                            visible = showPermissionGuide,
                            enter = if (reducedMotion) {
                                fadeIn(tween(0))
                            } else {
                                fadeIn(tween(160, easing = FlameMotionSpec.easeOut)) +
                                    expandVertically(tween(FlameMotionSpec.COLLAPSE_ENTER_MS, easing = FlameMotionSpec.easeOut))
                            },
                            exit = if (reducedMotion) {
                                fadeOut(tween(0))
                            } else {
                                fadeOut(tween(130, easing = FlameMotionSpec.easeOut)) +
                                    shrinkVertically(tween(FlameMotionSpec.COLLAPSE_EXIT_MS, easing = FlameMotionSpec.easeOut))
                            },
                        ) {
                            UpdateInfoBanner(
                                message = "请在系统设置中开启「允许安装未知应用」权限，授权完成后返回本应用将自动继续安装。",
                                tone = FlameTone.WARNING,
                                icon = Icons.Outlined.WarningAmber,
                            )
                        }

                        UpdatePrimaryActionButton(
                            text = "立即安装更新",
                            leadingIcon = Icons.Outlined.SystemUpdate,
                            onClick = {
                                haptics.performFlameLightTick()
                                when (val outcome = appUpdateManager.installReadyApk(context)) {
                                    AppUpdateInstallOutcome.Launched -> {
                                        showPermissionGuide = false
                                    }

                                    AppUpdateInstallOutcome.NeedsUnknownSourcesPermission -> {
                                        showPermissionGuide = true
                                        openUnknownSourcesSettingsSafely(context, appUpdateManager)
                                    }

                                    is AppUpdateInstallOutcome.Failed -> {
                                        Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                        )

                        if (showPermissionGuide) {
                            UpdateSecondaryActionButton(
                                text = "前往开启「允许安装未知应用」权限",
                                onClick = {
                                    haptics.performFlameLightTick()
                                    openUnknownSourcesSettingsSafely(context, appUpdateManager)
                                },
                            )
                        }

                        if (!isMandatory) {
                            UpdateSecondaryActionButton(
                                text = "稍后安装",
                                onClick = {
                                    haptics.performFlameLightTick()
                                    appUpdateManager.dismissDialog()
                                },
                            )
                        }
                    }

                    is AppUpdateStatus.Error -> {
                        UpdateInfoBanner(
                            message = status.message.trim().ifEmpty { "更新过程出现异常，请稍后重试" },
                            tone = FlameTone.DANGER,
                            icon = Icons.Outlined.ErrorOutline,
                        )

                        if (status.retryable) {
                            UpdatePrimaryActionButton(
                                text = "重试",
                                leadingIcon = Icons.Outlined.Refresh,
                                onClick = {
                                    haptics.performFlameLightTick()
                                    if (status.release != null) {
                                        appUpdateManager.startDownload()
                                    } else {
                                        coroutineScope.launch {
                                            appUpdateManager.checkForUpdates(manual = true)
                                        }
                                    }
                                },
                            )
                        }

                        UpdateSecondaryActionButton(
                            text = "前往 GitHub Releases",
                            leadingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
                            onClick = {
                                haptics.performFlameLightTick()
                                openUpdateExternalUrl(context, fallbackReleasesUrl)
                            },
                        )

                        if (!isMandatory) {
                            TextButton(
                                onClick = {
                                    haptics.performFlameLightTick()
                                    appUpdateManager.dismissDialog()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "关闭",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = palette.inkSecondary,
                                )
                            }
                        }
                    }

                    AppUpdateStatus.Idle,
                    AppUpdateStatus.Checking,
                    AppUpdateStatus.UpToDate,
                    -> Unit
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateReleaseMetaRow(
    currentVersionName: String,
    release: AppUpdateRelease,
    isMandatory: Boolean,
) {
    val versionTag = remember(currentVersionName, release.versionName) {
        formatVersionTransitionTag(currentVersionName, release.versionName)
    }
    val sizeLabel = remember(release.fileSize) {
        if (release.fileSize > 0L) formatApkFileSize(release.fileSize) else ""
    }
    val dateLabel = remember(release.publishedAt) {
        formatUpdatePublishedDate(release.publishedAt.orEmpty())
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FlameMiniTag(
            text = versionTag,
            tone = FlameTone.EMBER,
        )
        if (isMandatory) {
            FlameMiniTag(
                text = "重要兼容更新",
                tone = FlameTone.WARNING,
            )
        }
        if (sizeLabel.isNotEmpty()) {
            FlameMiniTag(
                text = sizeLabel,
                tone = FlameTone.NEUTRAL,
            )
        }
        if (dateLabel.isNotEmpty()) {
            FlameMiniTag(
                text = dateLabel,
                tone = FlameTone.NEUTRAL,
            )
        }
    }
}

internal enum class UpdateChangelogTone {
    FEATURE,
    FIX,
    NOTICE,
    GENERAL,
}

internal data class UpdateChangelogItem(
    val lead: String?,
    val body: String,
    val isSubItem: Boolean = false,
)

internal data class UpdateChangelogSection(
    val title: String?,
    val tone: UpdateChangelogTone,
    val items: List<UpdateChangelogItem>,
)

private const val DEFAULT_UPDATE_CHANGELOG_FALLBACK = "本次更新包含稳定性改进与体验优化。"
private val CHANGELOG_MD_LINK_REGEX = Regex("\\[([^\\]]+)\\]\\((https?://[^)]+)\\)")
private val CHANGELOG_PR_META_REGEX = Regex(
    "(?:\\s+by\\s+@[^\\s]+\\s+in\\s+https?://\\S+|\\s*\\(#\\d+\\))\\s*$",
    RegexOption.IGNORE_CASE,
)
private val CHANGELOG_HASH_HEADER_REGEX = Regex("^#{1,6}\\s*(.+)$")
private val CHANGELOG_BRACKET_HEADER_REGEX = Regex("^【(.+)】$")
private val CHANGELOG_NOISE_PATTERNS = listOf(
    "dependabot[bot]",
    "chore(ci):",
    "chore(deps):",
    "chore(deps-dev):",
    "bump actions/",
    "bump gradle/",
)

private fun isChangelogNoiseLine(lowerLine: String): Boolean {
    if (lowerLine.startsWith("**full changelog**") || lowerLine.startsWith("full changelog:")) {
        return true
    }
    return CHANGELOG_NOISE_PATTERNS.any { lowerLine.contains(it) }
}

private fun cleanChangelogInlineText(raw: String): String {
    var text = CHANGELOG_MD_LINK_REGEX.replace(raw) { match -> match.groupValues[1] }
    text = text.replace("`", "")
    text = CHANGELOG_PR_META_REGEX.replace(text, "")
    return text.trim().replace(Regex("\\s{2,}"), " ")
}

private fun classifyChangelogSection(rawTitle: String): Pair<String?, UpdateChangelogTone> {
    val cleanTitle = cleanChangelogInlineText(rawTitle)
        .replace("**", "")
        .replace("__", "")
        .trim('【', '】', ' ')
    val lower = cleanTitle.lowercase(Locale.ROOT)
    if (cleanTitle.isEmpty()) return null to UpdateChangelogTone.GENERAL
    if (lower in setOf("what's changed", "whats changed", "changes")) {
        return null to UpdateChangelogTone.GENERAL
    }
    if (cleanTitle.contains("新增") || cleanTitle.contains("新特性") ||
        lower.contains("added") || lower.contains("feature")
    ) {
        return "新增功能" to UpdateChangelogTone.FEATURE
    }
    if (cleanTitle.contains("修复") || cleanTitle.contains("优化") || cleanTitle.contains("改进") ||
        lower.contains("fixed") || lower.contains("fix") || lower.contains("improved")
    ) {
        return "修复与优化" to UpdateChangelogTone.FIX
    }
    if (cleanTitle.contains("变更") || cleanTitle.contains("注意") || lower.contains("breaking")) {
        return "重要变更" to UpdateChangelogTone.NOTICE
    }
    return cleanTitle to UpdateChangelogTone.GENERAL
}

private fun parseChangelogItemText(
    rawBody: String,
    isSubItem: Boolean,
): UpdateChangelogItem? {
    val cleaned = cleanChangelogInlineText(rawBody)
    if (cleaned.isEmpty() || isChangelogNoiseLine(cleaned.lowercase(Locale.ROOT))) {
        return null
    }

    val boldLeadMatch = Regex("^\\*\\*([^*]+)\\*\\*[：:](.*)$").matchEntire(cleaned)
    if (boldLeadMatch != null) {
        val lead = boldLeadMatch.groupValues[1].trim()
        val rest = boldLeadMatch.groupValues[2].replace("**", "").replace("__", "").trim()
        if (lead.isNotEmpty() && rest.isNotEmpty()) {
            return UpdateChangelogItem(lead = lead, body = rest, isSubItem = isSubItem)
        }
    }

    val stripped = cleaned.replace("**", "").replace("__", "").trim()
    if (stripped.isEmpty()) return null

    if (!isSubItem) {
        val colonIdx = stripped.indexOfFirst { it == '：' || it == ':' }
        if (colonIdx in 1..28) {
            val candidateLead = stripped.substring(0, colonIdx).trim()
            val candidateBody = stripped.substring(colonIdx + 1).trim()
            if (candidateBody.isNotEmpty() && !candidateLead.contains("http", ignoreCase = true)) {
                return UpdateChangelogItem(
                    lead = candidateLead,
                    body = candidateBody,
                    isSubItem = false,
                )
            }
        }
    }

    return UpdateChangelogItem(lead = null, body = stripped, isSubItem = isSubItem)
}

internal fun parseUpdateChangelog(raw: String): List<UpdateChangelogSection> {
    val normalized = raw
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    val sections = mutableListOf<UpdateChangelogSection>()
    var currentTitle: String? = null
    var currentTone = UpdateChangelogTone.GENERAL
    val currentItems = mutableListOf<UpdateChangelogItem>()
    var skipContributorsSection = false

    fun flushSection() {
        if (currentItems.isNotEmpty()) {
            sections.add(
                UpdateChangelogSection(
                    title = currentTitle,
                    tone = currentTone,
                    items = currentItems.toList(),
                ),
            )
        }
        currentTitle = null
        currentTone = UpdateChangelogTone.GENERAL
        currentItems.clear()
    }

    for (rawLine in normalized.split('\n')) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) continue
        val lower = trimmed.lowercase(Locale.ROOT)

        val hashHeader = CHANGELOG_MD_LINK_REGEX.let {
            CHANGELOG_HASH_HEADER_REGEX.matchEntire(trimmed)
        }
        if (hashHeader != null) {
            val headerContent = hashHeader.groupValues[1].trim()
            if (headerContent.lowercase(Locale.ROOT).contains("new contributors")) {
                flushSection()
                skipContributorsSection = true
                continue
            }
            skipContributorsSection = false
            val (mappedTitle, mappedTone) = classifyChangelogSection(headerContent)
            if (mappedTitle != null) {
                flushSection()
                currentTitle = mappedTitle
                currentTone = mappedTone
            }
            continue
        }

        if (skipContributorsSection || isChangelogNoiseLine(lower)) {
            continue
        }

        val bracketHeader = CHANGELOG_BRACKET_HEADER_REGEX.matchEntire(trimmed)
        if (bracketHeader != null && trimmed.length <= 26) {
            flushSection()
            val (mappedTitle, mappedTone) = classifyChangelogSection(bracketHeader.groupValues[1])
            currentTitle = mappedTitle ?: bracketHeader.groupValues[1].trim()
            currentTone = mappedTone
            continue
        }

        val leadingSpaces = rawLine.length - rawLine.trimStart(' ').length
        val isSubBullet = (leadingSpaces >= 2 && (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ "))) ||
            trimmed.startsWith("· ")
        val bulletBody = when {
            trimmed.startsWith("• ") -> trimmed.removePrefix("• ")
            trimmed.startsWith("· ") -> trimmed.removePrefix("· ")
            trimmed.startsWith("- ") -> trimmed.removePrefix("- ")
            trimmed.startsWith("* ") -> trimmed.removePrefix("* ")
            trimmed.startsWith("+ ") -> trimmed.removePrefix("+ ")
            else -> trimmed
        }
        parseChangelogItemText(bulletBody, isSubItem = isSubBullet)?.let { currentItems.add(it) }
    }

    flushSection()
    if (sections.isEmpty()) {
        return listOf(
            UpdateChangelogSection(
                title = null,
                tone = UpdateChangelogTone.GENERAL,
                items = listOf(UpdateChangelogItem(lead = null, body = DEFAULT_UPDATE_CHANGELOG_FALLBACK)),
            ),
        )
    }
    return sections
}

@Composable
private fun UpdateChangelogBox(
    changelog: String,
) {
    val palette = FlameThemeTokens.palette
    val sections = remember(changelog) { parseUpdateChangelog(changelog) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp),
        shape = RoundedCornerShape(14.dp),
        color = palette.surfaceSubtle,
        border = BorderStroke(1.dp, palette.hairline),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "更新内容",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkTertiary,
            )

            sections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    section.title?.let { sectionTitle ->
                        val tagTone = when (section.tone) {
                            UpdateChangelogTone.FEATURE -> FlameTone.EMBER
                            UpdateChangelogTone.FIX -> FlameTone.HEALTHY
                            UpdateChangelogTone.NOTICE -> FlameTone.WARNING
                            UpdateChangelogTone.GENERAL -> FlameTone.NEUTRAL
                        }
                        FlameMiniTag(text = sectionTitle, tone = tagTone)
                    }

                    section.items.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = if (item.isSubItem) 12.dp else 0.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = if (item.isSubItem) "·" else "•",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (item.isSubItem) palette.inkTertiary else palette.inkSecondary,
                            )
                            Text(
                                text = buildAnnotatedString {
                                    if (!item.lead.isNullOrBlank()) {
                                        withStyle(
                                            SpanStyle(
                                                fontWeight = FontWeight.SemiBold,
                                                color = palette.ink,
                                            ),
                                        ) {
                                            append(item.lead)
                                            append("：")
                                        }
                                    }
                                    withStyle(SpanStyle(color = palette.inkSecondary)) {
                                        append(item.body)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 19.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateDownloadingCard(
    status: AppUpdateStatus.Downloading,
    reducedMotion: Boolean,
) {
    val palette = FlameThemeTokens.palette
    val progressPercent = status.progressPercent?.coerceIn(0, 100)
    val summaryText = formatUpdateDownloadProgressSummary(
        downloadedBytes = status.downloadedBytes,
        totalBytes = status.totalBytes,
        progressPercent = progressPercent,
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = palette.surfaceSubtle,
        border = BorderStroke(1.dp, palette.hairline),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "正在下载安装包",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.ink,
                )
                Text(
                    text = if (progressPercent != null) "$progressPercent%" else "下载中…",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ember,
                )
            }

            if (progressPercent != null) {
                val targetProgress = (progressPercent / 100f).coerceIn(0f, 1f)
                val animatedProgress by animateFloatAsState(
                    targetValue = targetProgress,
                    animationSpec = tween(
                        durationMillis = if (reducedMotion) 0 else 160,
                        easing = FlameMotionSpec.easeOut,
                    ),
                    label = "app_update_download_progress",
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    color = palette.ember,
                    trackColor = palette.hairlineStrong,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
            } else {
                LinearProgressIndicator(
                    color = palette.ember,
                    trackColor = palette.hairlineStrong,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
            }

            Text(
                text = summaryText,
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun UpdateInfoBanner(
    message: String,
    tone: FlameTone,
    icon: ImageVector,
) {
    val toneColors = resolveFlameToneColors(tone)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = toneColors.container,
        contentColor = toneColors.onContainer,
        border = BorderStroke(1.dp, toneColors.border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = toneColors.fg,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = toneColors.onContainer,
            )
        }
    }
}

@Composable
private fun UpdatePrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val palette = FlameThemeTokens.palette
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .flamePressScale(interactionSource = interactionSource, enabled = enabled)
            .heightIn(min = 46.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.monoCtaContainer,
            contentColor = palette.monoCtaContent,
            disabledContainerColor = palette.monoCtaContainer.copy(alpha = 0.35f),
            disabledContentColor = palette.monoCtaContent.copy(alpha = 0.72f),
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UpdateSecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val palette = FlameThemeTokens.palette
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (enabled) palette.hairlineStrong else palette.hairline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = palette.surface,
            contentColor = palette.ink,
            disabledContainerColor = palette.surfaceSubtle,
            disabledContentColor = palette.inkTertiary,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun openUnknownSourcesSettingsSafely(
    context: Context,
    appUpdateManager: AppUpdateManager,
) {
    val intent = appUpdateManager.createUnknownSourcesSettingsIntent(context)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "未能打开系统「安装未知应用」设置页，请在系统设置中手动允许本应用安装应用",
            Toast.LENGTH_LONG,
        ).show()
    } catch (_: SecurityException) {
        Toast.makeText(
            context,
            "系统限制直接跳转安装权限设置页，请在系统设置中手动开启",
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun openUpdateExternalUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        copyUpdateUrlToClipboard(context, url)
        Toast.makeText(context, "未检测到可用浏览器，已复制发布页链接到剪贴板", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        copyUpdateUrlToClipboard(context, url)
        Toast.makeText(context, "无法直接打开外部浏览器，已复制发布页链接到剪贴板", Toast.LENGTH_SHORT).show()
    }
}

private fun copyUpdateUrlToClipboard(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("flame_update_release_url", url))
}
