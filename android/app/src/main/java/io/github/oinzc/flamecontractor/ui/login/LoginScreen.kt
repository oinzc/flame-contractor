package io.github.oinzc.flamecontractor.ui.login

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.oinzc.flamecontractor.execution.LoginMode
import io.github.oinzc.flamecontractor.execution.login.DouyinClientVariant
import io.github.oinzc.flamecontractor.execution.login.LoginDisplayMode
import io.github.oinzc.flamecontractor.execution.login.QrVisualStage
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.FlameTopPillButton
import io.github.oinzc.flamecontractor.ui.components.flamePressScale
import io.github.oinzc.flamecontractor.ui.components.performFlameLightTick
import io.github.oinzc.flamecontractor.ui.components.rememberReducedMotion
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import java.util.Locale

@Immutable
data class AccountSwitchPromptState(
    val newUid: String?,
    val newNick: String?,
)

@Immutable
data class LoginScreenState(
    val accountDisplayName: String = "默认账号",
    val loginMode: LoginMode = LoginMode.PRESERVE_SESSION,
    val displayMode: LoginDisplayMode = LoginDisplayMode.NATIVE_QR,
    val stage: QrVisualStage = QrVisualStage.CONNECTING,
    val qrBitmap: ImageBitmap? = null,
    val validatedIndexUrl: String? = null,
    val remainingSeconds: Int = 0,
    val loadProgressPercent: Int = 0,
    val installedVariant: DouyinClientVariant? = null,
    val isRefreshingQr: Boolean = false,
    val refreshSpinCount: Int = 0,
    val isSavingQr: Boolean = false,
    val jumpedToDouyin: Boolean = false,
    val verificationRequired: Boolean = false,
    val statusHint: String = "正在准备安全登录环境…",
    val accountSwitchPrompt: AccountSwitchPromptState? = null,
    val isApplyingSwitchDecision: Boolean = false,
)

@SuppressLint("ClickableViewAccessibility")
@Composable
fun LoginScreen(
    state: LoginScreenState,
    webView: WebView,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onToggleDisplayMode: () -> Unit,
    onRefreshQr: () -> Unit,
    onLaunchDouyinConfirm: () -> Unit,
    onSaveQrAndScan: () -> Unit,
    onOpenSmsWebLogin: () -> Unit,
    onProbeLoginNow: () -> Unit,
    onClearCacheAndReload: () -> Unit,
    onConfirmAccountSwitch: (wipeAndRescan: Boolean) -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val reducedMotion = rememberReducedMotion()

    BackHandler(enabled = state.accountSwitchPrompt == null) {
        if (state.displayMode == LoginDisplayMode.WEB_FALLBACK &&
            state.loginMode != LoginMode.INSPECT_OR_VERIFY &&
            !state.verificationRequired
        ) {
            onToggleDisplayMode()
        } else {
            onBack()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = palette.canvas,
        contentColor = palette.ink,
        contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            LoginTopBar(
                state = state,
                onBack = onBack,
                onToggleDisplayMode = onToggleDisplayMode,
                onProbeLoginNow = onProbeLoginNow,
                onClearCacheAndReload = onClearCacheAndReload,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 底层常驻全尺寸 WebView：避免 View.GONE 或 0×0 触发 Chromium 冻结布局和定时器
            Column(modifier = Modifier.fillMaxSize()) {
                if (state.displayMode == LoginDisplayMode.WEB_FALLBACK) {
                    WebModeStatusBanner(
                        verificationRequired = state.verificationRequired,
                        statusHint = state.statusHint,
                        onSwitchNativeQr = onToggleDisplayMode,
                        onRefreshWeb = onRefreshQr,
                    )
                }

                AndroidView(
                    factory = {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView
                    },
                    update = { view ->
                        val hiddenUnderNative = state.displayMode == LoginDisplayMode.NATIVE_QR
                        view.importantForAccessibility = if (hiddenUnderNative) {
                            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        } else {
                            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        }
                        view.setOnTouchListener(
                            if (hiddenUnderNative) {
                                View.OnTouchListener { _, _ -> true }
                            } else {
                                null
                            },
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }

            // 顶层不透明原生登录工作台：默认遮蔽电脑版网页，切换网页/风控时零重载平滑露出
            AnimatedVisibility(
                visible = state.displayMode == LoginDisplayMode.NATIVE_QR,
                enter = if (reducedMotion) {
                    EnterTransition.None
                } else {
                    fadeIn(tween(durationMillis = 180, easing = FlameMotionSpec.easeOut))
                },
                exit = if (reducedMotion) {
                    ExitTransition.None
                } else {
                    fadeOut(tween(durationMillis = 140, easing = FlameMotionSpec.easeOut))
                },
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = palette.canvas,
                    contentColor = palette.ink,
                ) {
                    NativeQrWorkspace(
                        state = state,
                        onRefreshQr = onRefreshQr,
                        onLaunchDouyinConfirm = onLaunchDouyinConfirm,
                        onSaveQrAndScan = onSaveQrAndScan,
                        onOpenSmsWebLogin = onOpenSmsWebLogin,
                        onSwitchWebMode = onToggleDisplayMode,
                    )
                }
            }
        }
    }

    if (state.accountSwitchPrompt != null) {
        AccountSwitchConfirmDialog(
            prompt = state.accountSwitchPrompt,
            busy = state.isApplyingSwitchDecision,
            dangerColor = semantics.warning,
            onConfirm = onConfirmAccountSwitch,
        )
    }
}

@Composable
private fun LoginTopBar(
    state: LoginScreenState,
    onBack: () -> Unit,
    onToggleDisplayMode: () -> Unit,
    onProbeLoginNow: () -> Unit,
    onClearCacheAndReload: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = palette.ink,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = if (state.loginMode == LoginMode.INSPECT_OR_VERIFY &&
                        state.displayMode == LoginDisplayMode.WEB_FALLBACK &&
                        !state.verificationRequired
                    ) {
                        "网页状态排查"
                    } else {
                        "登录抖音账号"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${state.accountDisplayName} · 登录态仅保存在本机沙盒",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (state.displayMode == LoginDisplayMode.NATIVE_QR) {
                FlameTopPillButton(
                    text = "网页/短信",
                    leadingIcon = Icons.Outlined.Language,
                    onClick = onToggleDisplayMode,
                )
            } else {
                FlameTopPillButton(
                    text = "确认登录态",
                    primary = true,
                    onClick = onProbeLoginNow,
                )
                if (state.loginMode != LoginMode.INSPECT_OR_VERIFY) {
                    IconButton(
                        onClick = onClearCacheAndReload,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CleaningServices,
                            contentDescription = "清理缓存并刷新二维码",
                            tint = palette.inkSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = palette.hairline)
    }
}

@Composable
private fun WebModeStatusBanner(
    verificationRequired: Boolean,
    statusHint: String,
    onSwitchNativeQr: () -> Unit,
    onRefreshWeb: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val bgColor = if (verificationRequired) semantics.warningContainer else palette.surfaceSubtle
    val fgColor = if (verificationRequired) semantics.onWarningContainer else palette.inkSecondary

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (verificationRequired) {
                    Icons.Outlined.Security
                } else {
                    Icons.Outlined.Language
                },
                contentDescription = null,
                tint = if (verificationRequired) semantics.warning else palette.inkSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = if (verificationRequired) {
                    "抖音要求新设备安全验证，请在下方网页完成验证后自动返回"
                } else {
                    statusHint
                },
                style = MaterialTheme.typography.bodySmall,
                color = fgColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!verificationRequired) {
                Text(
                    text = "扫码页",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onSwitchNativeQr)
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                )
            }
            Text(
                text = "刷新",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRefreshWeb)
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
        }
        HorizontalDivider(thickness = 1.dp, color = palette.hairline)
    }
}

@Composable
private fun NativeQrWorkspace(
    state: LoginScreenState,
    onRefreshQr: () -> Unit,
    onLaunchDouyinConfirm: () -> Unit,
    onSaveQrAndScan: () -> Unit,
    onOpenSmsWebLogin: () -> Unit,
    onSwitchWebMode: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val reducedMotion = rememberReducedMotion()
    val scrollState = rememberScrollState()

    val spinAngle by animateFloatAsState(
        targetValue = if (reducedMotion) 0f else state.refreshSpinCount * 360f,
        animationSpec = tween(
            durationMillis = FlameMotionSpec.ICON_SPIN_MS,
            easing = FlameMotionSpec.easeOut,
        ),
        label = "qr_refresh_spin",
    )

    val variant = state.installedVariant
    val qrReadyForUse = state.qrBitmap != null &&
        state.stage in setOf(QrVisualStage.READY, QrVisualStage.SCANNED)
    val canDirectJump = variant != null &&
        !state.validatedIndexUrl.isNullOrBlank() &&
        qrReadyForUse

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.jumpedToDouyin && state.stage != QrVisualStage.EXPIRED) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = semantics.healthyContainer,
                border = BorderStroke(1.dp, semantics.healthy.copy(alpha = 0.28f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = semantics.healthy,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "已在后台保持连接：在${variant?.displayName ?: "抖音"}点完「确认登录」后，直接切回本页面即可自动完成绑定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = semantics.onHealthyContainer,
                    )
                }
            }
        }

        FlameCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 24.dp,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val (pillLabel, pillTone) = when (state.stage) {
                        QrVisualStage.CONNECTING -> "连接安全通行证" to FlameTone.NEUTRAL
                        QrVisualStage.GENERATING_QR -> "正在生成授权码" to FlameTone.EMBER
                        QrVisualStage.READY -> "等待授权确认" to FlameTone.HEALTHY
                        QrVisualStage.SCANNED -> "已扫码 · 待确认" to FlameTone.EMBER
                        QrVisualStage.CONFIRMED -> "已确认 · 同步中" to FlameTone.HEALTHY
                        QrVisualStage.EXPIRED -> "二维码已过期" to FlameTone.WARNING
                        QrVisualStage.LOAD_TIMEOUT -> "加载超时" to FlameTone.DANGER
                    }
                    FlameStatusPill(
                        text = pillLabel,
                        tone = pillTone,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.qrBitmap != null &&
                            state.remainingSeconds > 0 &&
                            state.stage == QrVisualStage.READY
                        ) {
                            Text(
                                text = formatCountdown(state.remainingSeconds),
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.inkTertiary,
                            )
                        }

                        Surface(
                            onClick = onRefreshQr,
                            enabled = !state.isRefreshingQr,
                            shape = RoundedCornerShape(999.dp),
                            color = palette.surfaceSubtle,
                            contentColor = palette.inkSecondary,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = "刷新二维码",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .graphicsLayer { rotationZ = spinAngle },
                                )
                                Text(
                                    text = if (state.isRefreshingQr) "刷新中" else "刷新",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                QrCodeStageBox(
                    state = state,
                    onRefreshQr = onRefreshQr,
                    onSwitchWebMode = onSwitchWebMode,
                )

                Text(
                    text = when {
                        variant != null && canDirectJump ->
                            "单部手机推荐直接点下方按钮拉起${variant.displayName}确认；备用机或平板可直接用主力手机扫码"

                        variant == null ->
                            "当前设备未检测到抖音客户端，请用手机抖音扫上方二维码，或切换短信验证码登录"

                        else -> state.statusHint
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }

        // 单手机操作区：主按钮直跳抖音授权，次按钮宽白边存码跳扫一扫，底部网页短信兜底
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (variant != null && (canDirectJump || state.qrBitmap == null)) {
                LoginActionButton(
                    text = if (qrReadyForUse) {
                        "打开${variant.displayName}一键确认登录"
                    } else {
                        "授权码生成后可一键打开${variant.displayName}"
                    },
                    leadingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
                    primary = true,
                    enabled = canDirectJump,
                    onClick = onLaunchDouyinConfirm,
                )
            }

            if (state.qrBitmap != null || variant != null) {
                val promoteAlbumToPrimary = state.qrBitmap != null && !canDirectJump
                val albumLabel = when {
                    state.isSavingQr -> "正在生成高清二维码并准备跳转…"
                    variant != null && canDirectJump ->
                        "打不开确认页？存码去${variant.displayName}扫一扫"

                    variant != null ->
                        "保存二维码并打开${variant.displayName}扫一扫"

                    else -> "保存高清二维码到相册"
                }
                LoginActionButton(
                    text = albumLabel,
                    leadingIcon = Icons.Outlined.PhotoLibrary,
                    primary = promoteAlbumToPrimary,
                    enabled = qrReadyForUse && !state.isSavingQr,
                    onClick = onSaveQrAndScan,
                )
            }

            TextButton(
                onClick = onOpenSmsWebLogin,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Sms,
                    contentDescription = null,
                    tint = palette.inkSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "不想跳转？改用手机号验证码登录",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.inkSecondary,
                )
            }
        }
    }
}

@Composable
private fun QrCodeStageBox(
    state: LoginScreenState,
    onRefreshQr: () -> Unit,
    onSwitchWebMode: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val outerShape = RoundedCornerShape(20.dp)

    Box(
        modifier = Modifier
            .size(224.dp)
            .clip(outerShape)
            .background(
                if (state.qrBitmap != null) Color.White else palette.surfaceSubtle,
            )
            .border(1.dp, palette.hairline, outerShape),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = state.qrBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "抖音登录二维码",
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            )

            when (state.stage) {
                QrVisualStage.SCANNED -> {
                    QrOverlayMask(
                        backgroundColor = Color(0xD9111318),
                        onClick = null,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "已在抖音扫码",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "请在手机抖音点击「确认登录」",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE5E7EB),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                QrVisualStage.CONFIRMED -> {
                    QrOverlayMask(
                        backgroundColor = Color(0xDF111318),
                        onClick = null,
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.5.dp,
                            color = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "登录成功，正在同步会话…",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                QrVisualStage.EXPIRED -> {
                    QrOverlayMask(
                        backgroundColor = Color(0xDE111318),
                        onClick = onRefreshQr,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "二维码已过期",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "轻点此处立即刷新",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE5E7EB),
                        )
                    }
                }

                else -> Unit
            }
        } else if (state.stage == QrVisualStage.LOAD_TIMEOUT) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = semantics.danger,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "二维码生成较慢或网络受阻",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlameTopPillButton(
                        text = "重试",
                        primary = true,
                        onClick = onRefreshQr,
                    )
                    FlameTopPillButton(
                        text = "网页查看",
                        onClick = onSwitchWebMode,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.5.dp,
                    color = palette.ink,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = if (state.stage == QrVisualStage.CONNECTING) {
                        if (state.loadProgressPercent in 1..99) {
                            "正在连接抖音安全通行证… ${state.loadProgressPercent}%"
                        } else {
                            "正在连接抖音安全通行证…"
                        }
                    } else {
                        "正在生成专属授权码…"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                    textAlign = TextAlign.Center,
                )
                if (state.loadProgressPercent in 1..99 && state.stage == QrVisualStage.CONNECTING) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (state.loadProgressPercent / 100f).coerceIn(0.05f, 1f) },
                        modifier = Modifier
                            .width(128.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = palette.monoCtaContainer,
                        trackColor = palette.hairline,
                        strokeCap = StrokeCap.Round,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "首次建立独立沙盒环境约需 3–6 秒，请勿重复刷新",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun QrOverlayMask(
    backgroundColor: Color,
    onClick: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val modifier = if (onClick != null) {
        Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(16.dp)
    } else {
        Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp)
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

@Composable
private fun LoginActionButton(
    text: String,
    leadingIcon: ImageVector,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    val containerColor = when {
        !enabled && primary -> palette.monoCtaContainer.copy(alpha = 0.36f)
        !enabled -> palette.surfaceSubtle
        primary -> palette.monoCtaContainer
        else -> palette.surface
    }
    val contentColor = when {
        !enabled && primary -> palette.monoCtaContent.copy(alpha = 0.72f)
        !enabled -> palette.inkTertiary
        primary -> palette.monoCtaContent
        else -> palette.ink
    }
    val border = if (primary) null else BorderStroke(1.dp, palette.hairlineStrong)

    Surface(
        onClick = {
            haptics.performFlameLightTick()
            onClick()
        },
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .flamePressScale(interactionSource = interactionSource, enabled = enabled)
            .heightIn(min = if (primary) 52.dp else 46.dp),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = contentColor,
        border = border,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AccountSwitchConfirmDialog(
    prompt: AccountSwitchPromptState,
    busy: Boolean,
    dangerColor: Color,
    onConfirm: (wipeAndRescan: Boolean) -> Unit,
) {
    val palette = FlameThemeTokens.palette
    var selectedIdx by remember { mutableIntStateOf(0) }
    val options = listOf(
        "清空旧号好友与待发任务，重新扫描当前账号（推荐）",
        "保留现有好友名单，但解绑全部稳定 ID 并暂停自动发送（逐个核对后再启用）",
    )

    AlertDialog(
        onDismissRequest = { /* 强制二选一，不可点击外部关闭 */ },
        title = {
            Text(
                text = "检测到当前登录抖音号已变更",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val nickHint = prompt.newNick?.takeIf { it.isNotBlank() }?.let { "（新登录：$it）" }.orEmpty()
                Text(
                    text = "当前账号已有绑定的续火好友$nickHint。为防止把消息错发给不同账号的好友，请选择处理方式：",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                )
                options.forEachIndexed { idx, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !busy) { selectedIdx = idx }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedIdx == idx,
                            onClick = if (!busy) ({ selectedIdx = idx }) else null,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.ink,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedIdx == 0) dangerColor else palette.monoCtaContainer,
                    contentColor = if (selectedIdx == 0) Color.White else palette.monoCtaContent,
                ),
                onClick = { onConfirm(selectedIdx == 0) },
            ) {
                Text(if (busy) "处理中…" else "确认执行")
            }
        },
    )
}

private fun formatCountdown(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val mins = safe / 60
    val secs = safe % 60
    return String.format(Locale.ROOT, "%02d:%02d", mins, secs)
}
