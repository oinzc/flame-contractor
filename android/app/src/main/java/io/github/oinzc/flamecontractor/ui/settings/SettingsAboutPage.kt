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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.BuildConfig
import io.github.oinzc.flamecontractor.core.support.EncodedFeedbackImage
import io.github.oinzc.flamecontractor.core.support.FeedbackDraftStore
import io.github.oinzc.flamecontractor.core.support.FeedbackImageEncoder
import io.github.oinzc.flamecontractor.core.support.FeedbackImageEncoderUtils
import io.github.oinzc.flamecontractor.core.support.FeedbackKind
import io.github.oinzc.flamecontractor.core.support.FeedbackPreFillDraft
import io.github.oinzc.flamecontractor.core.support.FeedbackSubmissionRequest
import io.github.oinzc.flamecontractor.core.support.FeedbackSubmitResult
import io.github.oinzc.flamecontractor.core.support.FeedbackSubmitter
import io.github.oinzc.flamecontractor.core.support.FeedbackTextDraft
import io.github.oinzc.flamecontractor.core.support.ProjectLinks
import io.github.oinzc.flamecontractor.core.support.mergeFeedbackPreFillDraft
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameListRow
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameModalSheet
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameSheetActionRow
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
    embeddedInSplitPane: Boolean = false,
    preFillDraft: FeedbackPreFillDraft? = null,
    onConsumePreFillDraft: (Long) -> Unit = {},
    onBuildDiagnosticsReportText: (suspend () -> String)? = null,
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
            deviceBrand = Build.BRAND.orEmpty(),
            deviceModel = Build.MODEL.orEmpty(),
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
            deviceBrand = Build.BRAND.orEmpty(),
            deviceModel = Build.MODEL.orEmpty(),
        )
    }

    val draftStore = remember(context) { FeedbackDraftStore.fromContext(context) }
    val initialTextDraft = remember(draftStore) { draftStore.loadTextDraft() }

    var selectedKindName by rememberSaveable { mutableStateOf(initialTextDraft.kind.name) }
    val selectedKind = FeedbackKind.entries.firstOrNull { it.name == selectedKindName } ?: FeedbackKind.BUG
    var titleInput by rememberSaveable { mutableStateOf(initialTextDraft.title) }
    var contentInput by rememberSaveable { mutableStateOf(initialTextDraft.content) }
    var includeEnv by rememberSaveable { mutableStateOf(initialTextDraft.includeEnv) }
    var includeDiagnosticLog by rememberSaveable { mutableStateOf(initialTextDraft.includeDiagnosticLog) }
    var restoredDraftNoticeVisible by rememberSaveable { mutableStateOf(initialTextDraft.hasContent) }
    var lastConsumedDraftNonce by rememberSaveable { mutableStateOf(0L) }
    var diagnosticLogPreviewText by remember { mutableStateOf<String?>(null) }
    var showDiagnosticLogPreview by remember { mutableStateOf(false) }
    var loadingDiagnosticLog by remember { mutableStateOf(false) }
    var attachedImages by remember { mutableStateOf<List<FeedbackImageUiItem>>(emptyList()) }
    var imageMutationVersion by remember { mutableIntStateOf(0) }
    var encodingImages by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var contentValidationError by rememberSaveable { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var submitResult by remember { mutableStateOf<FeedbackSubmitResult?>(null) }
    val contentFocusRequester = remember { FocusRequester() }

    fun persistTextDraft(
        kind: FeedbackKind = selectedKind,
        title: String = titleInput,
        content: String = contentInput,
        env: Boolean = includeEnv,
        diagnosticLog: Boolean = includeDiagnosticLog,
    ) {
        draftStore.saveTextDraft(
            kind = kind,
            title = title,
            content = content,
            includeEnv = env,
            includeDiagnosticLog = diagnosticLog,
        )
    }

    // 异步恢复缓存的反馈截图并解码缩略图（在 IO 线程执行，避免主线程卡顿）
    LaunchedEffect(draftStore) {
        val startVersion = imageMutationVersion
        val restoredImages = withContext(Dispatchers.IO) {
            draftStore.loadImagesAsync().map { encoded ->
                val thumb = FeedbackImageEncoder.decodePreviewThumbnail(
                    previewBytes = encoded.previewBytes,
                    maxEdgePx = 320,
                )?.asImageBitmap()
                FeedbackImageUiItem(encoded = encoded, thumbnail = thumb)
            }
        }
        if (imageMutationVersion == startVersion && restoredImages.isNotEmpty()) {
            attachedImages = restoredImages
            restoredDraftNoticeVisible = true
        }
    }

    // 文字草稿已在首帧前同步恢复，此处按规则保护用户已有草稿不被 preFillDraft 静默覆盖
    LaunchedEffect(preFillDraft?.nonce) {
        val draft = preFillDraft ?: return@LaunchedEffect
        if (draft.nonce == lastConsumedDraftNonce) return@LaunchedEffect
        lastConsumedDraftNonce = draft.nonce
        val current = FeedbackTextDraft(
            kind = selectedKind,
            title = titleInput,
            content = contentInput,
            includeEnv = includeEnv,
            includeDiagnosticLog = includeDiagnosticLog,
        )
        val merged = mergeFeedbackPreFillDraft(current = current, preFill = draft)
        if (merged != current) {
            selectedKindName = merged.kind.name
            titleInput = merged.title
            contentInput = merged.content
            includeEnv = merged.includeEnv
            includeDiagnosticLog = merged.includeDiagnosticLog
            if (merged.content.isNotBlank()) {
                contentValidationError = null
            }
            persistTextDraft(
                kind = merged.kind,
                title = merged.title,
                content = merged.content,
                env = merged.includeEnv,
                diagnosticLog = merged.includeDiagnosticLog,
            )
        }
        onConsumePreFillDraft(draft.nonce)
    }

    fun buildShortContentMessage(trimmedLen: Int): String = if (trimmedLen <= 0) {
        "请先填写至少 ${FeedbackSubmitter.MIN_CONTENT_LEN} 个字的详细描述再提交"
    } else {
        val remaining = (FeedbackSubmitter.MIN_CONTENT_LEN - trimmedLen).coerceAtLeast(1)
        "详细描述还差 $remaining 个字即可提交（至少 ${FeedbackSubmitter.MIN_CONTENT_LEN} 个字）"
    }

    fun buildCurrentRequest(diagnosticLogText: String = ""): FeedbackSubmissionRequest = FeedbackSubmissionRequest(
        kind = selectedKind,
        title = titleInput,
        content = contentInput,
        contact = "",
        version = versionLabel,
        environment = envSummary,
        includeEnv = includeEnv,
        client = "android",
        images = attachedImages.map { it.encoded.attachment },
        diagnosticLog = diagnosticLogText,
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

    suspend fun loadLatestDiagnosticsReportText(): String {
        val raw = onBuildDiagnosticsReportText?.invoke()
            ?: uiState.diagnosticsReport?.textReport
            ?: envSummary
        diagnosticLogPreviewText = raw
        return raw
    }

    fun copyDiagnosticsReportQuick() {
        if (loadingDiagnosticLog) return
        haptics.performFlameLightTick()
        loadingDiagnosticLog = true
        coroutineScope.launch {
            try {
                val reportText = loadLatestDiagnosticsReportText()
                copyPlainText(context, label = "flame_sanitized_diagnostics", text = reportText)
                showNotice("已复制脱敏诊断日志，可直接发给维护者或在反馈中粘贴")
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                showNotice("生成诊断日志失败，请稍后重试")
            } finally {
                loadingDiagnosticLog = false
            }
        }
    }

    fun openDiagnosticsLogPreview() {
        if (loadingDiagnosticLog) return
        haptics.performFlameLightTick()
        loadingDiagnosticLog = true
        coroutineScope.launch {
            try {
                loadLatestDiagnosticsReportText()
                showDiagnosticLogPreview = true
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                showNotice("读取诊断日志失败，请稍后重试")
            } finally {
                loadingDiagnosticLog = false
            }
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
                val updatedImages = (attachedImages + added).take(FeedbackSubmitter.MAX_IMAGE_COUNT)
                imageMutationVersion += 1
                attachedImages = updatedImages
                draftStore.saveImagesAsync(updatedImages.map { it.encoded })
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
            val trimmedLog = if (includeDiagnosticLog) {
                try {
                    trimDiagnosticLogForFeedback(loadLatestDiagnosticsReportText())
                } catch (ce: CancellationException) {
                    submitting = false
                    throw ce
                } catch (_: Throwable) {
                    ""
                }
            } else {
                ""
            }
            val finalReq = buildCurrentRequest(diagnosticLogText = trimmedLog)
            val finalValidationError = FeedbackSubmitter.validateInput(
                title = finalReq.title,
                content = finalReq.content,
                contact = finalReq.contact,
                images = finalReq.images,
                diagnosticLog = finalReq.diagnosticLog,
            )
            if (finalValidationError != null) {
                submitting = false
                submitResult = FeedbackSubmitResult.Failure(finalValidationError, retryable = false)
                showNotice(finalValidationError)
                return@launch
            }
            val result = submitter.submit(finalReq)
            submitting = false
            submitResult = result
            if (result is FeedbackSubmitResult.Success) {
                selectedKindName = FeedbackKind.BUG.name
                titleInput = ""
                contentInput = ""
                includeEnv = true
                includeDiagnosticLog = true
                imageMutationVersion += 1
                attachedImages = emptyList()
                imageError = null
                contentValidationError = null
                restoredDraftNoticeVisible = false
                draftStore.clearDraftAsync()
                showNotice(result.message)
            }
        }
    }

    FlameSubpageScaffold(
        title = SettingsSection.ABOUT.title,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        embeddedInSplitPane = embeddedInSplitPane,
    ) { innerPadding ->
        SettingsPageScrollColumn(
            contentPadding = innerPadding,
            fullWidthInPane = embeddedInSplitPane,
        ) {
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

                    // 温和提示已自动恢复上次未提交草稿
                    val showRestoredDraftNotice = restoredDraftNoticeVisible && (
                        titleInput.isNotBlank() ||
                            contentInput.isNotBlank() ||
                            attachedImages.isNotEmpty()
                        )
                    AnimatedVisibility(
                        visible = showRestoredDraftNotice,
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FlameMiniTag(
                                text = "已自动恢复草稿",
                                tone = FlameTone.NEUTRAL,
                            )
                            Text(
                                text = "已保留上次未提交的文字与截图",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.inkTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                                        persistTextDraft(kind = kind)
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
                                persistTextDraft(title = next)
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
                                persistTextDraft(content = next)
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
                                val updatedImages = attachedImages.filterNot { it.encoded.id == targetId }
                                imageMutationVersion += 1
                                attachedImages = updatedImages
                                draftStore.saveImagesAsync(updatedImages.map { it.encoded })
                                imageError = null
                                if (submitResult is FeedbackSubmitResult.Failure) {
                                    submitResult = null
                                }
                            }
                        },
                    )

                    // 脱敏环境信息开关
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !submitting) {
                                val next = !includeEnv
                                includeEnv = next
                                persistTextDraft(env = next)
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
                                text = "附带脱敏运行环境信息",
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.ink,
                            )
                            Text(
                                text = "仅附版本号、Android 版本、手机型号与定位器摘要，帮助快速定位问题",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                            )
                        }
                        Switch(
                            checked = includeEnv,
                            enabled = !submitting,
                            onCheckedChange = { next ->
                                includeEnv = next
                                persistTextDraft(env = next)
                            },
                            colors = settingsSwitchColors(),
                        )
                    }

                    // 脱敏诊断日志开关 + 预览 / 一键复制
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !submitting) {
                                    val next = !includeDiagnosticLog
                                    includeDiagnosticLog = next
                                    persistTextDraft(diagnosticLog = next)
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
                                    text = "附带近期脱敏诊断日志（强烈推荐）",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.ink,
                                )
                                Text(
                                    text = "含设备与权限状态、跳过/失败统计与近期脱敏运行轨迹，绝不含好友昵称、UID、Cookie 或聊天内容",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.inkSecondary,
                                )
                            }
                            Switch(
                                checked = includeDiagnosticLog,
                                enabled = !submitting,
                                onCheckedChange = { next ->
                                    includeDiagnosticLog = next
                                    persistTextDraft(diagnosticLog = next)
                                },
                                colors = settingsSwitchColors(),
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { openDiagnosticsLogPreview() },
                                enabled = !loadingDiagnosticLog && !submitting,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, palette.hairlineStrong),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink),
                                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                            ) {
                                if (loadingDiagnosticLog) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = palette.ink,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.Visibility,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                                Text(
                                    text = "预览脱敏日志",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(start = 5.dp),
                                )
                            }

                            OutlinedButton(
                                onClick = { copyDiagnosticsReportQuick() },
                                enabled = !loadingDiagnosticLog && !submitting,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, palette.hairlineStrong),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink),
                                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = "一键复制诊断日志",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(start = 5.dp),
                                )
                            }
                        }
                    }

                    // 公开反馈隐私说明（方案 A：不采集联系方式）
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = palette.surfaceSubtle,
                        border = BorderStroke(1.dp, palette.hairline),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VerifiedUser,
                                contentDescription = null,
                                tint = semantics.healthy,
                                modifier = Modifier
                                    .padding(top = 1.dp)
                                    .size(16.dp),
                            )
                            Text(
                                text = "反馈内容会公开显示在 GitHub Issues，请勿填写手机号、微信号或个人隐私；问题修复后会通过应用更新推送。",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                            )
                        }
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
                                    coroutineScope.launch {
                                        val diagLog = if (includeDiagnosticLog) {
                                            runCatching {
                                                trimDiagnosticLogForFeedback(loadLatestDiagnosticsReportText())
                                            }.getOrDefault("")
                                        } else {
                                            ""
                                        }
                                        val text = FeedbackSubmitter.buildClipboardFallbackText(
                                            buildCurrentRequest(diagnosticLogText = diagLog),
                                        )
                                        copyPlainText(context, label = "flame_feedback_draft", text = text)
                                        showNotice("已复制完整脱敏反馈内容，不会丢失")
                                    }
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
                                    coroutineScope.launch {
                                        val diagLog = if (includeDiagnosticLog) {
                                            runCatching {
                                                trimDiagnosticLogForFeedback(loadLatestDiagnosticsReportText())
                                            }.getOrDefault("")
                                        } else {
                                            ""
                                        }
                                        val text = FeedbackSubmitter.buildClipboardFallbackText(
                                            buildCurrentRequest(diagnosticLogText = diagLog),
                                        )
                                        copyPlainText(context, label = "flame_feedback_draft", text = text)
                                        showNotice("已复制完整脱敏反馈内容")
                                    }
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
                            attachedImages.isNotEmpty()
                        if (hasDraft && !submitting && !encodingImages) {
                            TextButton(
                                onClick = {
                                    haptics.performFlameLightTick()
                                    selectedKindName = FeedbackKind.BUG.name
                                    titleInput = ""
                                    contentInput = ""
                                    includeEnv = true
                                    includeDiagnosticLog = true
                                    imageMutationVersion += 1
                                    attachedImages = emptyList()
                                    imageError = null
                                    contentValidationError = null
                                    submitResult = null
                                    restoredDraftNoticeVisible = false
                                    draftStore.clearDraftAsync()
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

            // 2. 脱敏诊断日志与环境信息快捷复制
            FlameGroupedSection(title = "排查与日志工具") {
                FlameListRow(
                    title = "一键复制脱敏诊断日志",
                    subtitle = "含手机型号/ROM、权限状态、跳过与失败统计及近期脱敏运行轨迹，可直接微信/QQ 发给维护者",
                    leading = {
                        FlameRowAvatar(tone = FlameTone.EMBER) {
                            Icon(
                                imageVector = Icons.Outlined.BugReport,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = {
                        Text(
                            text = if (loadingDiagnosticLog) "生成中…" else "复制日志",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkSecondary,
                        )
                    },
                    showDivider = true,
                    onClick = { copyDiagnosticsReportQuick() },
                )

                FlameListRow(
                    title = "复制脱敏环境信息",
                    subtitle = "仅含版本号、Android 系统版本、手机型号与定位器摘要，不含任何个人数据",
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

            // 3. 隐私保护提醒
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
                        text = "隐私提示：反馈与截图会公开显示在 GitHub Issues 便于追踪修复，请勿包含好友昵称、手机号、微信号、Cookie 或 Bark 推送地址；问题修复后会通过应用更新推送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )
                }
            }
        }
    }

    if (showDiagnosticLogPreview) {
        val previewText = trimDiagnosticLogForFeedback(diagnosticLogPreviewText.orEmpty())
        FeedbackDiagnosticLogPreviewSheet(
            logText = previewText,
            onCopy = {
                haptics.performFlameLightTick()
                copyPlainText(
                    context,
                    label = "flame_sanitized_diagnostics",
                    text = diagnosticLogPreviewText.orEmpty().ifBlank { previewText },
                )
                showNotice("已复制脱敏诊断日志，可直接发给维护者或在反馈中粘贴")
            },
            onDismiss = { showDiagnosticLogPreview = false },
        )
    }
}

internal const val MAX_FEEDBACK_DIAGNOSTIC_LOG_CHARS: Int = 15_500

internal fun trimDiagnosticLogForFeedback(rawReport: String): String {
    val trimmed = rawReport.trim()
    if (trimmed.length <= MAX_FEEDBACK_DIAGNOSTIC_LOG_CHARS) return trimmed
    val prefix = "[日志较长，已自动保留最新片段]\n"
    val keepLen = (MAX_FEEDBACK_DIAGNOSTIC_LOG_CHARS - prefix.length).coerceAtLeast(1)
    return prefix + trimmed.takeLast(keepLen)
}

@Composable
private fun FeedbackDiagnosticLogPreviewSheet(
    logText: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = "脱敏诊断日志预览",
        subtitle = "共 ${logText.length} 字 · 已通过白名单与敏感词脱敏，不含任何个人隐私",
    ) {
        Surface(
            color = palette.surfaceSubtle,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, palette.hairline),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(min = 180.dp, max = 380.dp),
        ) {
            SelectionContainer {
                Text(
                    text = logText.ifBlank { "暂无运行轨迹日志" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = palette.ink,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlameSheetActionRow(
                text = "一键复制完整诊断日志",
                trailingText = "可发给维护者",
                primary = true,
                onClick = onCopy,
            )
            FlameSheetActionRow(
                text = "关闭预览",
                onClick = onDismiss,
            )
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (result.hasDiagnosticLog) {
                            FlameMiniTag(
                                text = "已附脱敏诊断日志",
                                tone = FlameTone.HEALTHY,
                            )
                        }
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

internal fun copyPlainText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
