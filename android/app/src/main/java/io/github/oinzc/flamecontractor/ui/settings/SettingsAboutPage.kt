package io.github.oinzc.flamecontractor.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import io.github.oinzc.flamecontractor.core.support.FeedbackKind
import io.github.oinzc.flamecontractor.core.support.FeedbackSubmissionRequest
import io.github.oinzc.flamecontractor.core.support.FeedbackSubmitResult
import io.github.oinzc.flamecontractor.core.support.FeedbackSubmitter
import io.github.oinzc.flamecontractor.core.support.ProjectLinks
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameListRow
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.flamePressScale
import io.github.oinzc.flamecontractor.ui.components.performFlameLightTick
import io.github.oinzc.flamecontractor.ui.components.rememberReducedMotion
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
    val reducedMotion = rememberReducedMotion()
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors

    val submitter = remember {
        FeedbackSubmitter(endpointProvider = { BuildConfig.FEEDBACK_ENDPOINT })
    }
    val relayConfigured = submitter.isConfigured

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

    var selectedKindName by rememberSaveable { mutableStateOf(FeedbackKind.BUG.name) }
    val selectedKind = FeedbackKind.entries.firstOrNull { it.name == selectedKindName } ?: FeedbackKind.BUG
    var titleInput by rememberSaveable { mutableStateOf("") }
    var contentInput by rememberSaveable { mutableStateOf("") }
    var contactInput by rememberSaveable { mutableStateOf("") }
    var includeEnv by rememberSaveable { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var submitResult by remember { mutableStateOf<FeedbackSubmitResult?>(null) }

    fun buildCurrentRequest(): FeedbackSubmissionRequest = FeedbackSubmissionRequest(
        kind = selectedKind,
        title = titleInput,
        content = contentInput,
        contact = contactInput,
        version = versionLabel,
        environment = envSummary,
        includeEnv = includeEnv,
        client = "android",
    )

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

    fun handleDirectSubmit() {
        if (submitting) return
        val req = buildCurrentRequest()
        val validationError = FeedbackSubmitter.validateInput(
            title = req.title,
            content = req.content,
            contact = req.contact,
        )
        if (validationError != null) {
            submitResult = FeedbackSubmitResult.Failure(validationError, retryable = false)
            return
        }
        haptics.performFlameLightTick()
        submitting = true
        submitResult = null
        coroutineScope.launch {
            val result = submitter.submit(req)
            submitting = false
            submitResult = result
            if (result is FeedbackSubmitResult.Success) {
                titleInput = ""
                contentInput = ""
                showNotice(result.message)
            }
        }
    }

    FlameSubpageScaffold(
        title = SettingsSection.ABOUT.title,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) { innerPadding ->
        SettingsPageScrollColumn(contentPadding = innerPadding) {
            // 1. 应用内直接反馈表单（免魔法、免跳转、自动建 GitHub Issue）
            FlameGroupedSection(title = "应用内直接反馈（免跳转）") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = if (relayConfigured) {
                            "直接写下问题或想法并点击提交，无需代理、无需登录 GitHub，内容将自动汇总给作者跟进改进。"
                        } else {
                            "当前构建尚未绑定直连中转地址（发版配置 FLAME_FEEDBACK_ENDPOINT 后即可一键免魔法直提）；你现在可直接填写并一键复制完整脱敏反馈。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )

                    // 反馈类型分段选择
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FeedbackKind.entries.forEach { kind ->
                            val selected = kind == selectedKind
                            val chipInteraction = remember(kind) { MutableInteractionSource() }
                            val bg by animateColorAsState(
                                targetValue = if (selected) palette.monoCtaContainer else palette.surfaceSubtle,
                                animationSpec = tween(if (reducedMotion) 0 else 160, easing = FlameMotionSpec.easeOut),
                                label = "fb_kind_bg_${kind.name}",
                            )
                            val fg by animateColorAsState(
                                targetValue = if (selected) palette.monoCtaContent else palette.inkSecondary,
                                animationSpec = tween(if (reducedMotion) 0 else 160, easing = FlameMotionSpec.easeOut),
                                label = "fb_kind_fg_${kind.name}",
                            )
                            val border by animateColorAsState(
                                targetValue = if (selected) palette.monoCtaContainer else palette.hairline,
                                animationSpec = tween(if (reducedMotion) 0 else 160, easing = FlameMotionSpec.easeOut),
                                label = "fb_kind_border_${kind.name}",
                            )
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = bg,
                                contentColor = fg,
                                border = BorderStroke(1.dp, border),
                                modifier = Modifier
                                    .flamePressScale(interactionSource = chipInteraction)
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable(
                                        interactionSource = chipInteraction,
                                        indication = LocalIndication.current,
                                    ) {
                                        haptics.performFlameLightTick()
                                        selectedKindName = kind.name
                                        if (submitResult is FeedbackSubmitResult.Failure) {
                                            submitResult = null
                                        }
                                    },
                            ) {
                                Text(
                                    text = kind.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }

                    // 标题（可选）
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { next ->
                            if (next.length <= FeedbackSubmitter.MAX_TITLE_LEN) {
                                titleInput = next
                            }
                        },
                        enabled = !submitting,
                        singleLine = true,
                        label = { Text("简短标题（可选）") },
                        placeholder = { Text("例如：桌面小组件刷新后状态未更新") },
                        colors = settingsTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 正文（必填）
                    OutlinedTextField(
                        value = contentInput,
                        onValueChange = { next ->
                            if (next.length <= FeedbackSubmitter.MAX_CONTENT_LEN) {
                                contentInput = next
                                if (submitResult is FeedbackSubmitResult.Failure) {
                                    submitResult = null
                                }
                            }
                        },
                        enabled = !submitting,
                        minLines = 4,
                        maxLines = 8,
                        label = { Text("详细描述（必填）") },
                        placeholder = { Text(selectedKind.placeholder) },
                        supportingText = {
                            Text(
                                text = "${contentInput.trim().length} / ${FeedbackSubmitter.MAX_CONTENT_LEN}（请勿包含好友昵称、Cookie 或推送密钥）",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.inkTertiary,
                            )
                        },
                        colors = settingsTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 联系方式（可选）
                    OutlinedTextField(
                        value = contactInput,
                        onValueChange = { next ->
                            if (next.length <= FeedbackSubmitter.MAX_CONTACT_LEN) {
                                contactInput = next
                            }
                        },
                        enabled = !submitting,
                        singleLine = true,
                        label = { Text("联系方式（可选）") },
                        placeholder = { Text("邮箱或 GitHub ID；公开可见，介意可留空") },
                        colors = settingsTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 脱敏环境信息开关
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !submitting) { includeEnv = !includeEnv },
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
                                text = "附带脱敏运行环境信息",
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.ink,
                            )
                            Text(
                                text = "仅附版本号、Android 版本、品牌与定位器摘要，帮助快速定位问题",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                            )
                        }
                        Switch(
                            checked = includeEnv,
                            enabled = !submitting,
                            onCheckedChange = { includeEnv = it },
                            colors = settingsSwitchColors(),
                        )
                    }

                    // 提交结果反馈条
                    AnimatedVisibility(
                        visible = submitResult != null,
                        enter = if (reducedMotion) {
                            fadeIn(tween(0))
                        } else {
                            fadeIn(tween(180, easing = FlameMotionSpec.easeOut)) +
                                expandVertically(tween(FlameMotionSpec.COLLAPSE_ENTER_MS, easing = FlameMotionSpec.easeOut))
                        },
                        exit = if (reducedMotion) {
                            fadeOut(tween(0))
                        } else {
                            fadeOut(tween(140, easing = FlameMotionSpec.easeOut)) +
                                shrinkVertically(tween(FlameMotionSpec.COLLAPSE_EXIT_MS, easing = FlameMotionSpec.easeOut))
                        },
                    ) {
                        submitResult?.let { result ->
                            FeedbackResultBanner(
                                result = result,
                                onOpenIssue = { url -> openUrlWithFeedback(url) },
                                onCopyFallback = {
                                    haptics.performFlameLightTick()
                                    val text = FeedbackSubmitter.buildClipboardFallbackText(buildCurrentRequest())
                                    copyPlainText(context, label = "flame_feedback_draft", text = text)
                                    showNotice("已复制完整脱敏反馈内容，不会丢失")
                                },
                            )
                        }
                    }

                    // 提交按钮区
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (relayConfigured) {
                            val submitInteraction = remember { MutableInteractionSource() }
                            val canSubmit = !submitting && contentInput.trim().length >= FeedbackSubmitter.MIN_CONTENT_LEN
                            Button(
                                onClick = { handleDirectSubmit() },
                                enabled = canSubmit,
                                interactionSource = submitInteraction,
                                modifier = Modifier.flamePressScale(
                                    interactionSource = submitInteraction,
                                    enabled = canSubmit,
                                ),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = palette.monoCtaContainer,
                                    contentColor = palette.monoCtaContent,
                                ),
                                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 9.dp),
                            ) {
                                if (submitting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                        color = palette.monoCtaContent,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.Send,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                                Text(
                                    text = if (submitting) "提交中…" else "直接提交反馈",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        } else {
                            val copyFeedbackInteraction = remember { MutableInteractionSource() }
                            Button(
                                onClick = {
                                    haptics.performFlameLightTick()
                                    val text = FeedbackSubmitter.buildClipboardFallbackText(buildCurrentRequest())
                                    copyPlainText(context, label = "flame_feedback_draft", text = text)
                                    showNotice("已复制完整脱敏反馈内容")
                                },
                                enabled = contentInput.trim().isNotEmpty(),
                                interactionSource = copyFeedbackInteraction,
                                modifier = Modifier.flamePressScale(
                                    interactionSource = copyFeedbackInteraction,
                                    enabled = contentInput.trim().isNotEmpty(),
                                ),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = palette.monoCtaContainer,
                                    contentColor = palette.monoCtaContent,
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = "复制完整反馈",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    val targetUrl = if (selectedKind == FeedbackKind.FEATURE) {
                                        ProjectLinks.buildFeatureRequestUrl()
                                    } else {
                                        bugReportUrl
                                    }
                                    openUrlWithFeedback(targetUrl)
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, palette.hairlineStrong),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                            ) {
                                Text(
                                    text = "前往 GitHub 提交",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }

                        if ((titleInput.isNotEmpty() || contentInput.isNotEmpty() || contactInput.isNotEmpty()) && !submitting) {
                            TextButton(
                                onClick = {
                                    haptics.performFlameLightTick()
                                    titleInput = ""
                                    contentInput = ""
                                    contactInput = ""
                                    submitResult = null
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = "清空",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.inkTertiary,
                                )
                            }
                        }
                    }
                }
            }

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

            // 3. 项目源码、版本发布与脱敏环境信息
            FlameGroupedSection(title = "项目源码与更多渠道") {
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
                    title = "版本发布（Releases）",
                    subtitle = "查看更新日志，获取最新 Android APK 与 Windows 安装包",
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
                    subtitle = "浏览已有问题进展或参与公开讨论",
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
                    title = "复制脱敏环境信息",
                    subtitle = "仅含版本号、Android 系统版本与定位器摘要，不含任何个人数据",
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
                        text = "隐私提示：反馈将同步到项目公开 Issue 便于追踪修复，请勿填写好友昵称、抖音号 ID、Cookie 或 Bark 推送地址。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedbackResultBanner(
    result: FeedbackSubmitResult,
    onOpenIssue: (String) -> Unit,
    onCopyFallback: () -> Unit,
) {
    val semantics = FlameThemeTokens.semanticColors
    val isSuccess = result is FeedbackSubmitResult.Success
    val bg = if (isSuccess) semantics.healthyContainer else semantics.dangerContainer
    val border = if (isSuccess) semantics.healthy.copy(alpha = 0.35f) else semantics.dangerBorder
    val fg = if (isSuccess) semantics.onHealthyContainer else semantics.onDangerContainer

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (isSuccess) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = when (result) {
                        is FeedbackSubmitResult.Success -> result.message
                        is FeedbackSubmitResult.Failure -> result.message
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = fg,
                    modifier = Modifier.weight(1f),
                )
            }
            when (result) {
                is FeedbackSubmitResult.Success -> {
                    result.issueUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        TextButton(
                            onClick = { onOpenIssue(url) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "在 GitHub 查看进展 →",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = fg,
                            )
                        }
                    }
                }

                is FeedbackSubmitResult.Failure -> {
                    TextButton(
                        onClick = onCopyFallback,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "复制已填反馈内容",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = fg,
                        )
                    }
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
