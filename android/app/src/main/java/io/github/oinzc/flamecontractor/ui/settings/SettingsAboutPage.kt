package io.github.oinzc.flamecontractor.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.BuildConfig
import io.github.oinzc.flamecontractor.core.support.EncodedFeedbackImage
import io.github.oinzc.flamecontractor.core.support.FeedbackImageEncoder
import io.github.oinzc.flamecontractor.core.support.FeedbackImageEncoderUtils
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class FeedbackImageUiItem(
    val encoded: EncodedFeedbackImage,
    val thumbnail: ImageBitmap?,
)

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
    var attachedImages by remember { mutableStateOf<List<FeedbackImageUiItem>>(emptyList()) }
    var encodingImages by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var contentValidationError by rememberSaveable { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var submitResult by remember { mutableStateOf<FeedbackSubmitResult?>(null) }
    val contentFocusRequester = remember { FocusRequester() }

    fun buildShortContentMessage(trimmedLen: Int): String = if (trimmedLen <= 0) {
        "请先填写至少 ${FeedbackSubmitter.MIN_CONTENT_LEN} 个字的详细描述再提交"
    } else {
        val remaining = (FeedbackSubmitter.MIN_CONTENT_LEN - trimmedLen).coerceAtLeast(1)
        "详细描述还差 $remaining 个字即可提交（至少 ${FeedbackSubmitter.MIN_CONTENT_LEN} 个字）"
    }

    fun buildCurrentRequest(): FeedbackSubmissionRequest = FeedbackSubmissionRequest(
        kind = selectedKind,
        title = titleInput,
        content = contentInput,
        contact = contactInput,
        version = versionLabel,
        environment = envSummary,
        includeEnv = includeEnv,
        client = "android",
        images = attachedImages.map { it.encoded.attachment },
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

    fun handlePickedUris(uris: List<Uri>) {
        if (uris.isEmpty() || submitting || encodingImages) return
        val remainingSlots = (FeedbackSubmitter.MAX_IMAGE_COUNT - attachedImages.size).coerceAtLeast(0)
        if (remainingSlots <= 0) {
            imageError = "最多只能上传 ${FeedbackSubmitter.MAX_IMAGE_COUNT} 张截图"
            return
        }
        val overflowCount = (uris.size - remainingSlots).coerceAtLeast(0)
        val toProcess = uris.take(remainingSlots)
        val startIndex = attachedImages.size

        encodingImages = true
        imageError = null
        coroutineScope.launch {
            val added = mutableListOf<FeedbackImageUiItem>()
            val failures = mutableListOf<String>()
            try {
                toProcess.forEachIndexed { offset, uri ->
                    try {
                        val uiItem = withContext(Dispatchers.IO) {
                            val encoded = FeedbackImageEncoder.encodeFromUri(
                                context = context,
                                uri = uri,
                                indexHint = startIndex + added.size + 1,
                            )
                            val thumb = FeedbackImageEncoder.decodePreviewThumbnail(
                                previewBytes = encoded.previewBytes,
                                maxEdgePx = 320,
                            )?.asImageBitmap()
                            FeedbackImageUiItem(encoded = encoded, thumbnail = thumb)
                        }
                        added += uiItem
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (err: Throwable) {
                        val reason = err.message?.takeIf { it.isNotBlank() } ?: "图片处理失败，请换一张重试"
                        failures += if (toProcess.size == 1) reason else "第 ${offset + 1} 张：$reason"
                    }
                }
            } finally {
                encodingImages = false
            }

            if (added.isNotEmpty()) {
                attachedImages = (attachedImages + added).take(FeedbackSubmitter.MAX_IMAGE_COUNT)
                if (submitResult is FeedbackSubmitResult.Failure) {
                    submitResult = null
                }
            }
            imageError = when {
                failures.isNotEmpty() -> failures.joinToString("；")
                overflowCount > 0 -> "最多支持 ${FeedbackSubmitter.MAX_IMAGE_COUNT} 张截图，已保留前 ${toProcess.size} 张"
                else -> null
            }
        }
    }

    val pickMultiple3Launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(FeedbackSubmitter.MAX_IMAGE_COUNT),
    ) { uris ->
        handlePickedUris(uris)
    }

    val pickMultiple2Launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(2),
    ) { uris ->
        handlePickedUris(uris)
    }

    val pickSingleVisualMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            handlePickedUris(listOf(uri))
        }
    }

    val fallbackMultiContentsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        handlePickedUris(uris)
    }

    fun launchImagePicker() {
        if (submitting || encodingImages) return
        val remaining = FeedbackSubmitter.MAX_IMAGE_COUNT - attachedImages.size
        if (remaining <= 0) {
            imageError = "最多只能上传 ${FeedbackSubmitter.MAX_IMAGE_COUNT} 张截图"
            return
        }
        haptics.performFlameLightTick()
        imageError = null
        val photoPickerAvailable = runCatching {
            ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)
        }.getOrDefault(false)
        val visualMediaRequest = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        val launchedPhotoPicker = if (photoPickerAvailable) {
            runCatching {
                when {
                    remaining >= FeedbackSubmitter.MAX_IMAGE_COUNT ->
                        pickMultiple3Launcher.launch(visualMediaRequest)

                    remaining == 2 ->
                        pickMultiple2Launcher.launch(visualMediaRequest)

                    else ->
                        pickSingleVisualMediaLauncher.launch(visualMediaRequest)
                }
                true
            }.getOrDefault(false)
        } else {
            false
        }
        if (!launchedPhotoPicker) {
            try {
                fallbackMultiContentsLauncher.launch("image/*")
            } catch (_: ActivityNotFoundException) {
                imageError = "当前系统未找到可用的图片选择器"
            }
        }
    }

    fun handleDirectSubmit() {
        if (submitting || encodingImages) return
        val req = buildCurrentRequest()
        val trimmedLen = req.content.trim().length
        if (trimmedLen < FeedbackSubmitter.MIN_CONTENT_LEN) {
            val shortMessage = buildShortContentMessage(trimmedLen)
            haptics.performFlameLightTick()
            contentValidationError = shortMessage
            submitResult = FeedbackSubmitResult.Failure(shortMessage, retryable = false)
            showNotice(shortMessage)
            runCatching { contentFocusRequester.requestFocus() }
            return
        }
        val validationError = FeedbackSubmitter.validateInput(
            title = req.title,
            content = req.content,
            contact = req.contact,
            images = req.images,
        )
        if (validationError != null) {
            haptics.performFlameLightTick()
            submitResult = FeedbackSubmitResult.Failure(validationError, retryable = false)
            showNotice(validationError)
            return
        }
        haptics.performFlameLightTick()
        contentValidationError = null
        submitting = true
        submitResult = null
        coroutineScope.launch {
            val result = submitter.submit(req)
            submitting = false
            submitResult = result
            if (result is FeedbackSubmitResult.Success) {
                titleInput = ""
                contentInput = ""
                attachedImages = emptyList()
                imageError = null
                contentValidationError = null
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
            // 1. 问题反馈与建议（应用内直提 + 截图上传）
            FlameCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                containerColor = palette.surface,
                borderColor = palette.hairline,
                contentPadding = PaddingValues(15.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 顶部直观引导头
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
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
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                FlameMiniTag(
                                    text = if (relayConfigured) "支持截图 · 免登录" else "脱敏复制",
                                    tone = FlameTone.EMBER,
                                )
                            }
                            Text(
                                text = if (relayConfigured) {
                                    "遇到问题或有好点子？附上截图直接告诉我，无需代理、无需登录 GitHub。"
                                } else {
                                    "遇到问题或有好点子？可直接填写并附上截图，一键复制完整脱敏反馈前往 GitHub 提交。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                            )
                        }
                    }

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
                    val trimmedContentLen = contentInput.trim().length
                    val missingContentChars = (FeedbackSubmitter.MIN_CONTENT_LEN - trimmedContentLen).coerceAtLeast(0)
                    OutlinedTextField(
                        value = contentInput,
                        onValueChange = { next ->
                            if (next.length <= FeedbackSubmitter.MAX_CONTENT_LEN) {
                                contentInput = next
                                val nextTrimmedLen = next.trim().length
                                if (nextTrimmedLen >= FeedbackSubmitter.MIN_CONTENT_LEN) {
                                    contentValidationError = null
                                } else if (contentValidationError != null) {
                                    contentValidationError = buildShortContentMessage(nextTrimmedLen)
                                }
                                if (submitResult is FeedbackSubmitResult.Failure) {
                                    submitResult = null
                                }
                            }
                        },
                        enabled = !submitting,
                        isError = contentValidationError != null,
                        minLines = 4,
                        maxLines = 8,
                        label = { Text("详细描述（必填，至少 ${FeedbackSubmitter.MIN_CONTENT_LEN} 字）") },
                        placeholder = { Text(selectedKind.placeholder) },
                        supportingText = {
                            val (supportText, supportColor, supportWeight) = when {
                                contentValidationError != null -> Triple(
                                    contentValidationError.orEmpty(),
                                    semantics.danger,
                                    FontWeight.SemiBold,
                                )

                                trimmedContentLen in 1 until FeedbackSubmitter.MIN_CONTENT_LEN -> Triple(
                                    "还差 $missingContentChars 个字即可提交 · $trimmedContentLen / ${FeedbackSubmitter.MAX_CONTENT_LEN}",
                                    semantics.warning,
                                    FontWeight.Medium,
                                )

                                trimmedContentLen == 0 -> Triple(
                                    "至少输入 ${FeedbackSubmitter.MIN_CONTENT_LEN} 个字（请勿包含好友昵称、Cookie 或推送密钥）",
                                    palette.inkTertiary,
                                    FontWeight.Normal,
                                )

                                else -> Triple(
                                    "$trimmedContentLen / ${FeedbackSubmitter.MAX_CONTENT_LEN}（请勿包含好友昵称、Cookie 或推送密钥）",
                                    palette.inkTertiary,
                                    FontWeight.Normal,
                                )
                            }
                            Text(
                                text = supportText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = supportWeight,
                                color = supportColor,
                            )
                        },
                        colors = settingsTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(contentFocusRequester),
                    )

                    // 补充截图（可选，最多 3 张）
                    FeedbackScreenshotPickerSection(
                        images = attachedImages,
                        isEncoding = encodingImages,
                        enabled = !submitting,
                        errorMessage = imageError,
                        reducedMotion = reducedMotion,
                        onAddClick = { launchImagePicker() },
                        onRemoveImage = { targetId ->
                            if (!submitting) {
                                haptics.performFlameLightTick()
                                attachedImages = attachedImages.filterNot { it.encoded.id == targetId }
                                imageError = null
                                if (submitResult is FeedbackSubmitResult.Failure) {
                                    submitResult = null
                                }
                            }
                        },
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
                                showCopyFallback = titleInput.isNotBlank() || contentInput.isNotBlank(),
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
                            val canSubmit = !submitting && !encodingImages
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
                                    text = when {
                                        submitting && attachedImages.isNotEmpty() -> "上传并提交中…"
                                        submitting -> "提交中…"
                                        attachedImages.isNotEmpty() -> "直接提交（含 ${attachedImages.size} 图）"
                                        else -> "直接提交反馈"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        } else {
                            val copyFeedbackInteraction = remember { MutableInteractionSource() }
                            val canCopy = !encodingImages
                            Button(
                                onClick = {
                                    haptics.performFlameLightTick()
                                    if (contentInput.trim().isEmpty()) {
                                        val emptyMsg = "请先填写反馈内容后再复制"
                                        contentValidationError = emptyMsg
                                        showNotice(emptyMsg)
                                        runCatching { contentFocusRequester.requestFocus() }
                                        return@Button
                                    }
                                    val text = FeedbackSubmitter.buildClipboardFallbackText(buildCurrentRequest())
                                    copyPlainText(context, label = "flame_feedback_draft", text = text)
                                    showNotice("已复制完整脱敏反馈内容")
                                },
                                enabled = canCopy,
                                interactionSource = copyFeedbackInteraction,
                                modifier = Modifier.flamePressScale(
                                    interactionSource = copyFeedbackInteraction,
                                    enabled = canCopy,
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

                        val hasDraft = titleInput.isNotEmpty() ||
                            contentInput.isNotEmpty() ||
                            contactInput.isNotEmpty() ||
                            attachedImages.isNotEmpty()
                        if (hasDraft && !submitting && !encodingImages) {
                            TextButton(
                                onClick = {
                                    haptics.performFlameLightTick()
                                    titleInput = ""
                                    contentInput = ""
                                    contactInput = ""
                                    attachedImages = emptyList()
                                    imageError = null
                                    contentValidationError = null
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
                        text = "隐私提示：反馈与截图将同步到项目公开 Issue 便于追踪修复，请勿包含好友昵称、手机号、微信号、Cookie 或 Bark 推送地址。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedbackScreenshotPickerSection(
    images: List<FeedbackImageUiItem>,
    isEncoding: Boolean,
    enabled: Boolean,
    errorMessage: String?,
    reducedMotion: Boolean,
    onAddClick: () -> Unit,
    onRemoveImage: (String) -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val remaining = (FeedbackSubmitter.MAX_IMAGE_COUNT - images.size).coerceAtLeast(0)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "补充截图（可选，最多 ${FeedbackSubmitter.MAX_IMAGE_COUNT} 张）",
                style = MaterialTheme.typography.titleSmall,
                color = palette.ink,
            )
            Text(
                text = "${images.size} / ${FeedbackSubmitter.MAX_IMAGE_COUNT}",
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkTertiary,
            )
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(
                items = images,
                key = { _, item -> item.encoded.id },
            ) { index, item ->
                FeedbackScreenshotThumbCard(
                    item = item,
                    index = index,
                    canRemove = enabled && !isEncoding,
                    onRemove = { onRemoveImage(item.encoded.id) },
                )
            }

            if (remaining > 0) {
                item(key = "add_screenshot_tile") {
                    FeedbackAddScreenshotTile(
                        remaining = remaining,
                        isEncoding = isEncoding,
                        enabled = enabled && !isEncoding,
                        onClick = onAddClick,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !errorMessage.isNullOrBlank(),
            enter = if (reducedMotion) {
                fadeIn(tween(0))
            } else {
                fadeIn(tween(160, easing = FlameMotionSpec.easeOut)) +
                    expandVertically(tween(180, easing = FlameMotionSpec.easeOut))
            },
            exit = if (reducedMotion) {
                fadeOut(tween(0))
            } else {
                fadeOut(tween(130, easing = FlameMotionSpec.easeOut)) +
                    shrinkVertically(tween(160, easing = FlameMotionSpec.easeOut))
            },
        ) {
            errorMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = semantics.dangerContainer,
                    border = BorderStroke(1.dp, semantics.dangerBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = semantics.onDangerContainer,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = semantics.onDangerContainer,
                        )
                    }
                }
            }
        }

        Text(
            text = "截图将随公开 GitHub Issue 展示（自动剥离 EXIF 定位信息），请先遮挡手机号、微信号或聊天隐私。",
            style = MaterialTheme.typography.labelSmall,
            color = palette.inkTertiary,
        )
    }
}

@Composable
private fun FeedbackScreenshotThumbCard(
    item: FeedbackImageUiItem,
    index: Int,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val sizeLabel = remember(item.encoded.attachment.sizeBytes) {
        FeedbackImageEncoderUtils.formatByteSize(item.encoded.attachment.sizeBytes)
    }
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(shape)
            .background(palette.surfaceSubtle)
            .border(1.dp, palette.hairlineStrong, shape),
    ) {
        val thumb = item.thumbnail
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = "已选反馈截图 ${index + 1}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = "已选反馈截图 ${index + 1}",
                    tint = palette.inkTertiary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // 左下角压缩后体积标签
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.66f))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        ) {
            Text(
                text = sizeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
            )
        }

        // 右上角删除按钮
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.7f))
                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                .clickable(
                    enabled = canRemove,
                    role = Role.Button,
                    onClick = onRemove,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "移除第 ${index + 1} 张截图",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun FeedbackAddScreenshotTile(
    remaining: Int,
    isEncoding: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val interactionSource = remember { MutableInteractionSource() }
    val dashColor = palette.hairlineStrong
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = Modifier
            .size(96.dp)
            .flamePressScale(interactionSource = interactionSource, enabled = enabled)
            .clip(shape)
            .background(palette.surfaceSubtle)
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                val radiusPx = 14.dp.toPx()
                drawRoundRect(
                    color = dashColor,
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    style = Stroke(
                        width = strokeWidth,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                    ),
                )
            }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = if (isEncoding) "正在压缩处理截图" else "添加截图，还可添加 $remaining 张"
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isEncoding) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = palette.inkSecondary,
                )
                Text(
                    text = "压缩中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkSecondary,
                    maxLines = 1,
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = palette.inkSecondary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = "添加截图",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkSecondary,
                    maxLines = 1,
                )
                Text(
                    text = "剩 $remaining 张",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FeedbackResultBanner(
    result: FeedbackSubmitResult,
    showCopyFallback: Boolean,
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
                    if (showCopyFallback) {
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
