package io.github.oinzc.flamecontractor.ui.setup

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameModalSheet
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.FlameSheetActionRow
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.performFlameLightTick
import io.github.oinzc.flamecontractor.ui.components.rememberReducedMotion
import io.github.oinzc.flamecontractor.ui.components.resolveFlameToneColors
import io.github.oinzc.flamecontractor.ui.settings.RomSurvivalChecker
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens

internal enum class OnboardingStep(
    val shortTitle: String,
    val headline: String,
    val icon: ImageVector,
    val isCorePermissionGate: Boolean,
) {
    NOTIFICATIONS(
        shortTitle = "通知",
        headline = "开启系统通知提醒",
        icon = Icons.Outlined.NotificationsActive,
        isCorePermissionGate = true,
    ),
    AUTO_RETURN(
        shortTitle = "自动跳回",
        headline = "抖音点确认后自动跳回",
        icon = Icons.AutoMirrored.Outlined.OpenInNew,
        isCorePermissionGate = true,
    ),
    BATTERY_AND_ALARM(
        shortTitle = "省电与闹钟",
        headline = "放行省电与精确闹钟",
        icon = Icons.Outlined.BatteryChargingFull,
        isCorePermissionGate = true,
    ),
    OEM_BACKGROUND(
        shortTitle = "后台策略",
        headline = "放行本机后台与自启动",
        icon = Icons.Outlined.PhoneAndroid,
        isCorePermissionGate = true,
    ),
    DOUYIN_LOGIN(
        shortTitle = "登录抖音",
        headline = "绑定你的抖音账号",
        icon = Icons.Outlined.QrCodeScanner,
        isCorePermissionGate = false,
    ),
    SELECT_TARGETS(
        shortTitle = "选择好友",
        headline = "扫描并选择续火好友",
        icon = Icons.Outlined.GroupAdd,
        isCorePermissionGate = false,
    ),
}

internal object OnboardingGatePolicy {

    fun isStepCompleted(step: OnboardingStep, readiness: SetupReadinessSnapshot): Boolean = when (step) {
        OnboardingStep.NOTIFICATIONS -> readiness.itemById(SetupItemId.NOTIFICATIONS).completed
        OnboardingStep.AUTO_RETURN -> readiness.itemById(SetupItemId.AUTO_RETURN_OVERLAY).completed
        OnboardingStep.BATTERY_AND_ALARM ->
            readiness.itemById(SetupItemId.BATTERY_OPTIMIZATION).completed &&
                readiness.itemById(SetupItemId.EXACT_ALARM).completed

        OnboardingStep.OEM_BACKGROUND -> readiness.itemById(SetupItemId.OEM_BACKGROUND_AUTOSTART).completed
        OnboardingStep.DOUYIN_LOGIN -> readiness.itemById(SetupItemId.DOUYIN_LOGIN).completed
        OnboardingStep.SELECT_TARGETS -> readiness.itemById(SetupItemId.SELECT_TARGETS).completed
    }

    /**
     * 核心权限分步门禁：返回第一个尚未完成的核心权限步索引；若前 4 步核心权限均已就绪，返回全步骤末索引。
     */
    fun maxUnlockedStepIndex(readiness: SetupReadinessSnapshot): Int {
        val steps = OnboardingStep.entries
        val firstIncompleteCore = steps.indexOfFirst { step ->
            step.isCorePermissionGate && !isStepCompleted(step, readiness)
        }
        return if (firstIncompleteCore >= 0) firstIncompleteCore else steps.lastIndex
    }

    /**
     * 已解锁步骤或已完成步骤（未越过前面未完成的核心门禁）允许点击切换回看；禁止直接跳到未解锁的后续步骤。
     */
    fun canSelectStep(targetIndex: Int, readiness: SetupReadinessSnapshot): Boolean {
        val steps = OnboardingStep.entries
        if (targetIndex !in steps.indices) return false
        return targetIndex <= maxUnlockedStepIndex(readiness)
    }

    fun clampInitialStepIndex(requestedIndex: Int, readiness: SetupReadinessSnapshot): Int {
        val maxUnlocked = maxUnlockedStepIndex(readiness)
        return requestedIndex.coerceIn(0, maxUnlocked)
    }

    fun completedCoreStepCount(readiness: SetupReadinessSnapshot): Int =
        OnboardingStep.entries.count { it.isCorePermissionGate && isStepCompleted(it, readiness) }

    fun totalCoreStepCount(): Int =
        OnboardingStep.entries.count { it.isCorePermissionGate }

    fun missingCorePermissionTitles(readiness: SetupReadinessSnapshot): List<String> = buildList {
        if (!readiness.itemById(SetupItemId.NOTIFICATIONS).completed) add("系统通知权限")
        if (!readiness.itemById(SetupItemId.AUTO_RETURN_OVERLAY).completed) add("悬浮窗自动跳回")
        if (!readiness.itemById(SetupItemId.BATTERY_OPTIMIZATION).completed) add("忽略电池优化")
        if (!readiness.itemById(SetupItemId.EXACT_ALARM).completed) add("精确闹钟调度")
        val oem = readiness.itemById(SetupItemId.OEM_BACKGROUND_AUTOSTART)
        if (oem.needsManualConfirmation && !oem.completed) add("厂商自启动与后台")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingWizardScreen(
    readiness: SetupReadinessSnapshot,
    activeAccountDisplayName: String,
    initialStepIndex: Int = 0,
    onStepChanged: (Int) -> Unit = {},
    onRefreshSurvival: () -> Unit,
    onSetAutostartConfirmed: (Boolean) -> Unit,
    onOpenLogin: () -> Unit,
    onFinishAndOpenTargets: () -> Unit,
    onFinishToToday: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val reducedMotion = rememberReducedMotion()

    val steps = OnboardingStep.entries
    val maxUnlockedIndex = OnboardingGatePolicy.maxUnlockedStepIndex(readiness)
    var currentIndex by rememberSaveable(initialStepIndex) {
        mutableIntStateOf(OnboardingGatePolicy.clampInitialStepIndex(initialStepIndex, readiness))
    }
    var hasOpenedOemSettings by rememberSaveable {
        mutableStateOf(readiness.itemById(SetupItemId.OEM_BACKGROUND_AUTOSTART).completed)
    }
    var showSkipConfirmSheet by rememberSaveable { mutableStateOf(false) }

    // 若用户切到后台撤销了前置核心权限，确保不会停留在尚未解锁的后续步骤
    LaunchedEffect(maxUnlockedIndex) {
        if (currentIndex > maxUnlockedIndex) {
            currentIndex = maxUnlockedIndex
        }
    }

    val currentStep = steps[currentIndex]

    LaunchedEffect(currentIndex) {
        onStepChanged(currentIndex)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshSurvival()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 第 1 步禁用系统返回键直接关闭引导；非第 1 步返回上一步
    BackHandler {
        when {
            showSkipConfirmSheet -> showSkipConfirmSheet = false
            currentIndex > 0 -> {
                haptics.performFlameLightTick()
                currentIndex -= 1
            }

            else -> {
                Toast.makeText(
                    context,
                    "请先完成核心权限配置；如遇特殊机型可点击底部「先仅浏览」",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onRefreshSurvival()
        if (!granted) {
            if (!RomSurvivalChecker.openNotificationSettings(context)) {
                Toast.makeText(context, "未能打开系统通知设置，请在系统设置中开启通知", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val systemSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        onRefreshSurvival()
    }

    val requestNotifications: () -> Unit = {
        haptics.performFlameLightTick()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!RomSurvivalChecker.openNotificationSettings(context)) {
            Toast.makeText(context, "未能打开系统通知设置，请在系统设置中开启通知", Toast.LENGTH_SHORT).show()
        }
    }

    val requestOverlayPermission: () -> Unit = {
        haptics.performFlameLightTick()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        val launched = runCatching {
            systemSettingsLauncher.launch(intent)
            true
        }.getOrDefault(false)
        if (!launched && !RomSurvivalChecker.openOverlayPermissionSettings(context)) {
            Toast.makeText(
                context,
                "未能打开悬浮窗设置页，请在系统应用权限中开启「显示在其他应用上层」",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val requestBatteryOptimization: () -> Unit = {
        haptics.performFlameLightTick()
        val directIntent = buildDirectBatteryOptimizationIntent(context.packageName)
        val launchedDirect = runCatching {
            systemSettingsLauncher.launch(directIntent)
            true
        }.getOrDefault(false)
        if (!launchedDirect && !RomSurvivalChecker.openBatteryOptimizationSettings(context)) {
            Toast.makeText(context, "未能打开系统省电设置", Toast.LENGTH_SHORT).show()
        }
    }

    val requestExactAlarm: () -> Unit = {
        haptics.performFlameLightTick()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}"),
            )
            val launched = runCatching {
                systemSettingsLauncher.launch(intent)
                true
            }.getOrDefault(false)
            if (!launched && !RomSurvivalChecker.openExactAlarmSettings(context)) {
                Toast.makeText(context, "未能打开精确闹钟权限设置", Toast.LENGTH_SHORT).show()
            }
        } else if (!RomSurvivalChecker.openExactAlarmSettings(context)) {
            Toast.makeText(context, "未能打开精确闹钟权限设置", Toast.LENGTH_SHORT).show()
        }
    }

    val openOemAutostartSettings: () -> Unit = {
        haptics.performFlameLightTick()
        hasOpenedOemSettings = true
        if (!RomSurvivalChecker.openOemBackgroundSettings(context)) {
            Toast.makeText(context, "未能打开厂商后台设置页，已为您尝试应用详情页", Toast.LENGTH_SHORT).show()
        }
    }

    val stepCompleted = OnboardingGatePolicy.isStepCompleted(currentStep, readiness)
    val completedCoreCount = OnboardingGatePolicy.completedCoreStepCount(readiness)
    val totalCoreCount = OnboardingGatePolicy.totalCoreStepCount()
    val targetProgress = (currentIndex + 1).toFloat() / steps.size.toFloat()
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress.coerceIn(0f, 1f),
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else 220,
            easing = FlameMotionSpec.easeOut,
        ),
        label = "onboarding_step_progress",
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = palette.canvas,
        contentColor = palette.ink,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部步骤进度条（移除右上角「稍后设置」退出入口，防止误点跳过核心权限）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlameMiniTag(
                        text = "第 ${currentIndex + 1} / ${steps.size} 步",
                        tone = FlameTone.EMBER,
                    )
                    FlameMiniTag(
                        text = readiness.vendorFamily.displayName,
                        tone = FlameTone.NEUTRAL,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    FlameMiniTag(
                        text = "核心权限 $completedCoreCount/$totalCoreCount",
                        tone = if (completedCoreCount >= totalCoreCount) FlameTone.HEALTHY else FlameTone.WARNING,
                    )
                }

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = palette.monoCtaContainer,
                    trackColor = palette.surfaceSubtle,
                    strokeCap = StrokeCap.Round,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    steps.forEachIndexed { idx, step ->
                        val isCurrent = idx == currentIndex
                        val done = OnboardingGatePolicy.isStepCompleted(step, readiness)
                        val unlocked = OnboardingGatePolicy.canSelectStep(idx, readiness)
                        val dotColor = when {
                            isCurrent -> palette.monoCtaContainer
                            done -> semantics.healthy
                            unlocked -> palette.hairlineStrong
                            else -> palette.hairline
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .alpha(if (unlocked || isCurrent) 1f else 0.48f)
                                .clickable {
                                    if (unlocked) {
                                        haptics.performFlameLightTick()
                                        currentIndex = idx
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "请先完成当前核心权限步骤后再继续",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                if (!unlocked && !isCurrent) {
                                    Icon(
                                        imageVector = Icons.Outlined.Lock,
                                        contentDescription = "未解锁步骤",
                                        tint = palette.inkTertiary,
                                        modifier = Modifier.size(10.dp),
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(dotColor),
                                    )
                                }
                                Text(
                                    text = step.shortTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isCurrent) palette.ink else palette.inkTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = palette.hairline)

            // 中部可滚动内容区
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                AnimatedContent(
                    targetState = currentIndex,
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 640.dp),
                    transitionSpec = {
                        when {
                            reducedMotion -> EnterTransition.None togetherWith ExitTransition.None
                            targetState > initialState -> {
                                (fadeIn(tween(190, easing = FlameMotionSpec.easeOut)) +
                                    slideInHorizontally(tween(190, easing = FlameMotionSpec.easeOut)) { it / 14 }) togetherWith
                                    (fadeOut(tween(140, easing = FlameMotionSpec.easeOut)) +
                                        slideOutHorizontally(tween(140, easing = FlameMotionSpec.easeOut)) { -it / 20 })
                            }

                            else -> {
                                (fadeIn(tween(190, easing = FlameMotionSpec.easeOut)) +
                                    slideInHorizontally(tween(190, easing = FlameMotionSpec.easeOut)) { -it / 14 }) togetherWith
                                    (fadeOut(tween(140, easing = FlameMotionSpec.easeOut)) +
                                        slideOutHorizontally(tween(140, easing = FlameMotionSpec.easeOut)) { it / 20 })
                            }
                        }
                    },
                    label = "onboarding_step_body",
                ) { stepIdx ->
                    val step = steps[stepIdx]
                    val scrollState = rememberScrollState()
                    LaunchedEffect(step, hasOpenedOemSettings) {
                        if (step == OnboardingStep.OEM_BACKGROUND && hasOpenedOemSettings) {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 18.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        OnboardingStepHeroHeader(
                            step = step,
                            completed = OnboardingGatePolicy.isStepCompleted(step, readiness),
                            statusText = stepStatusLabel(step, readiness),
                        )

                        when (step) {
                            OnboardingStep.NOTIFICATIONS -> {
                                val item = readiness.itemById(SetupItemId.NOTIFICATIONS)
                                SingleItemStepBody(
                                    item = item,
                                    onPrimaryAction = requestNotifications,
                                    nativePopupHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !item.completed) {
                                        "点击「立即授权」将直接弹出系统通知授权窗口，允许后自动亮绿勾并解锁下一步。"
                                    } else {
                                        null
                                    },
                                )
                            }

                            OnboardingStep.AUTO_RETURN -> {
                                val item = readiness.itemById(SetupItemId.AUTO_RETURN_OVERLAY)
                                SingleItemStepBody(
                                    item = item,
                                    onPrimaryAction = requestOverlayPermission,
                                    onSecondaryAction = item.secondaryActionLabel?.let {
                                        {
                                            haptics.performFlameLightTick()
                                            if (!RomSurvivalChecker.openBackgroundPopupSettings(context)) {
                                                Toast.makeText(
                                                    context,
                                                    "未能打开「后台弹出界面」权限页，请在应用权限管理中开启",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    },
                                )
                            }

                            OnboardingStep.BATTERY_AND_ALARM -> {
                                val batteryItem = readiness.itemById(SetupItemId.BATTERY_OPTIMIZATION)
                                val alarmItem = readiness.itemById(SetupItemId.EXACT_ALARM)
                                OnboardingWhyBox(
                                    whyText = "每天定时自动续火依赖系统在锁屏状态下准点唤醒应用。同时放行「忽略电池优化」和「精确闹钟」，可大幅减少息屏冻结与延迟发送。",
                                )
                                CompactSetupActionCard(
                                    icon = Icons.Outlined.BatteryChargingFull,
                                    item = batteryItem,
                                    onPrimaryAction = requestBatteryOptimization,
                                )
                                CompactSetupActionCard(
                                    icon = Icons.Outlined.Alarm,
                                    item = alarmItem,
                                    onPrimaryAction = requestExactAlarm,
                                )
                            }

                            OnboardingStep.OEM_BACKGROUND -> {
                                val item = readiness.itemById(SetupItemId.OEM_BACKGROUND_AUTOSTART)
                                val confirmCheckboxUnlocked = !item.needsManualConfirmation ||
                                    hasOpenedOemSettings ||
                                    item.completed
                                SingleItemStepBody(
                                    item = item,
                                    onPrimaryAction = openOemAutostartSettings,
                                    onSecondaryAction = item.secondaryActionLabel?.let {
                                        {
                                            haptics.performFlameLightTick()
                                            hasOpenedOemSettings = true
                                            if (!RomSurvivalChecker.openOemPowerSettings(context)) {
                                                Toast.makeText(context, "未能打开省电/神隐设置页", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onConfirmCheckedChange = if (item.needsManualConfirmation) {
                                        onSetAutostartConfirmed
                                    } else {
                                        null
                                    },
                                    confirmCheckboxEnabled = confirmCheckboxUnlocked,
                                    confirmUnlockHint = if (item.needsManualConfirmation && !confirmCheckboxUnlocked) {
                                        "请先点击「${item.primaryActionLabel}」前往系统页开启，返回本页后即可勾选确认并进入下一步。"
                                    } else if (item.needsManualConfirmation && !item.completed) {
                                        "已跳转系统设置，完成开启后请勾选下方确认框即可解锁下一步。"
                                    } else {
                                        null
                                    },
                                )
                            }

                            OnboardingStep.DOUYIN_LOGIN -> {
                                val item = readiness.itemById(SetupItemId.DOUYIN_LOGIN)
                                SingleItemStepBody(
                                    item = item,
                                    extraHighlight = if (activeAccountDisplayName.isNotBlank()) {
                                        "当前配置账号：$activeAccountDisplayName（登录成功后若为默认占位名将自动同步为绑定抖音昵称）"
                                    } else {
                                        null
                                    },
                                    onPrimaryAction = {
                                        haptics.performFlameLightTick()
                                        onOpenLogin()
                                    },
                                )
                            }

                            OnboardingStep.SELECT_TARGETS -> {
                                val item = readiness.itemById(SetupItemId.SELECT_TARGETS)
                                OnboardingWhyBox(whyText = item.whyNeeded)
                                FlameCard(contentPadding = PaddingValues(15.dp)) {
                                    Text(
                                        text = "当前整体就绪度：${readiness.completedCount} / ${readiness.totalCount} 项",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.ink,
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = item.howToHint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.inkSecondary,
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        readiness.items.forEach { entry ->
                                            val rowTone = if (entry.completed) {
                                                FlameTone.HEALTHY
                                            } else if (entry.importance == SetupItemImportance.REQUIRED) {
                                                FlameTone.WARNING
                                            } else {
                                                FlameTone.NEUTRAL
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Icon(
                                                    imageVector = if (entry.completed) {
                                                        Icons.Outlined.CheckCircle
                                                    } else {
                                                        Icons.Outlined.Security
                                                    },
                                                    contentDescription = null,
                                                    tint = resolveFlameToneColors(rowTone).fg,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                                Text(
                                                    text = entry.title,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = palette.ink,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                FlameMiniTag(
                                                    text = entry.statusLabel,
                                                    tone = rowTone,
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "未完成的设置随时可在「设置 → 初始化与权限清单」继续查看和一键补齐。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.inkTertiary,
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            // 底部固定操作栏（核心权限未就绪时聚焦于授权/开启，绝不允许直接点「下一步」敷衍跳过）
            HorizontalDivider(thickness = 1.dp, color = palette.hairline)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = palette.surface,
                contentColor = palette.ink,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (currentIndex > 0) {
                            OutlinedButton(
                                onClick = {
                                    haptics.performFlameLightTick()
                                    currentIndex -= 1
                                },
                                modifier = Modifier.heightIn(min = 46.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, palette.hairlineStrong),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = palette.surface,
                                    contentColor = palette.ink,
                                ),
                            ) {
                                Text(
                                    text = "上一步",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }

                        when {
                            currentStep.isCorePermissionGate && !stepCompleted -> {
                                // 核心权限尚未完成：主按钮直接聚焦「立即授权 / 前往开启」，不提供「下一步」
                                val (ctaText, ctaEnabled, onCtaClick) = when (currentStep) {
                                    OnboardingStep.NOTIFICATIONS -> Triple(
                                        "立即授权通知权限",
                                        true,
                                        requestNotifications,
                                    )

                                    OnboardingStep.AUTO_RETURN -> Triple(
                                        "前往开启悬浮窗权限",
                                        true,
                                        requestOverlayPermission,
                                    )

                                    OnboardingStep.BATTERY_AND_ALARM -> {
                                        val batteryDone = readiness.itemById(SetupItemId.BATTERY_OPTIMIZATION).completed
                                        if (!batteryDone) {
                                            Triple("立即忽略电池优化", true, requestBatteryOptimization)
                                        } else {
                                            Triple("前往开启精确闹钟", true, requestExactAlarm)
                                        }
                                    }

                                    OnboardingStep.OEM_BACKGROUND -> {
                                        if (!hasOpenedOemSettings) {
                                            Triple("前往开启自启动", true, openOemAutostartSettings)
                                        } else {
                                            Triple(
                                                "请先勾选上方已开启确认",
                                                false,
                                                openOemAutostartSettings,
                                            )
                                        }
                                    }

                                    else -> Triple("立即设置", true, onRefreshSurvival)
                                }

                                Button(
                                    onClick = onCtaClick,
                                    enabled = ctaEnabled,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 46.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.monoCtaContainer,
                                        contentColor = palette.monoCtaContent,
                                        disabledContainerColor = palette.surfaceSubtle,
                                        disabledContentColor = palette.inkTertiary,
                                    ),
                                ) {
                                    Text(
                                        text = ctaText,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            currentStep == OnboardingStep.DOUYIN_LOGIN && !stepCompleted -> {
                                OutlinedButton(
                                    onClick = {
                                        haptics.performFlameLightTick()
                                        currentIndex += 1
                                    },
                                    modifier = Modifier.heightIn(min = 46.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, palette.hairlineStrong),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = palette.surface,
                                        contentColor = palette.ink,
                                    ),
                                ) {
                                    Text(
                                        text = "下一步",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                                Button(
                                    onClick = {
                                        haptics.performFlameLightTick()
                                        onOpenLogin()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 46.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.monoCtaContainer,
                                        contentColor = palette.monoCtaContent,
                                    ),
                                ) {
                                    Text(
                                        text = "立即登录抖音",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            currentIndex < steps.lastIndex -> {
                                Button(
                                    onClick = {
                                        haptics.performFlameLightTick()
                                        currentIndex += 1
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 46.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.monoCtaContainer,
                                        contentColor = palette.monoCtaContent,
                                    ),
                                ) {
                                    Text(
                                        text = "已就绪，下一步",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }

                            else -> {
                                val targetsDone = readiness.itemById(SetupItemId.SELECT_TARGETS).completed
                                if (!targetsDone) {
                                    OutlinedButton(
                                        onClick = {
                                            haptics.performFlameLightTick()
                                            onFinishToToday()
                                        },
                                        modifier = Modifier.heightIn(min = 46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, palette.hairlineStrong),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = palette.surface,
                                            contentColor = palette.ink,
                                        ),
                                    ) {
                                        Text(
                                            text = "进入首页",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            haptics.performFlameLightTick()
                                            onFinishAndOpenTargets()
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = palette.monoCtaContainer,
                                            contentColor = palette.monoCtaContent,
                                        ),
                                    ) {
                                        Text(
                                            text = "去扫描选择好友",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            haptics.performFlameLightTick()
                                            onFinishToToday()
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = palette.monoCtaContainer,
                                            contentColor = palette.monoCtaContent,
                                        ),
                                    ) {
                                        Text(
                                            text = "完成并开始使用",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 极低权重防卡死安全阀：防止少数魔改系统无法识别权限状态导致用户永久进不去 App
                    if (currentIndex < steps.lastIndex) {
                        Text(
                            text = "特殊机型无法开启？先仅浏览",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkTertiary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    haptics.performFlameLightTick()
                                    showSkipConfirmSheet = true
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (showSkipConfirmSheet) {
        OnboardingSkipRiskConfirmSheet(
            missingCorePermissions = OnboardingGatePolicy.missingCorePermissionTitles(readiness),
            onContinueSetup = { showSkipConfirmSheet = false },
            onConfirmSkip = {
                showSkipConfirmSheet = false
                onSkip()
            },
        )
    }
}

@Composable
private fun OnboardingSkipRiskConfirmSheet(
    missingCorePermissions: List<String>,
    onContinueSetup: () -> Unit,
    onConfirmSkip: () -> Unit,
) {
    val semantics = FlameThemeTokens.semanticColors

    FlameModalSheet(
        onDismissRequest = onContinueSetup,
        title = "确定先跳过权限引导？",
        subtitle = "仅建议在特殊魔改系统无法识别状态时临时使用",
        trailingHeader = {
            FlameStatusPill(
                text = "存在失效风险",
                tone = FlameTone.WARNING,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = semantics.warningContainer,
                border = BorderStroke(1.dp, semantics.warningBorder),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = semantics.warning,
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(18.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "未开启核心权限会导致后台定时续火失效、确认登录后无法自动跳回，确定先跳过引导进入主界面？",
                            style = MaterialTheme.typography.bodySmall,
                            color = semantics.onWarningContainer,
                        )
                        if (missingCorePermissions.isNotEmpty()) {
                            Text(
                                text = "当前尚未就绪：${missingCorePermissions.joinToString("、")}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = semantics.onWarningContainer,
                            )
                        }
                    }
                }
            }

            FlameSheetActionRow(
                text = "继续完成权限设置（推荐）",
                primary = true,
                onClick = onContinueSetup,
            )

            FlameSheetActionRow(
                text = "已知晓风险，先仅浏览",
                danger = true,
                onClick = onConfirmSkip,
            )
        }
    }
}

@Composable
private fun OnboardingStepHeroHeader(
    step: OnboardingStep,
    completed: Boolean,
    statusText: String,
) {
    val palette = FlameThemeTokens.palette
    val tone = if (completed) FlameTone.HEALTHY else FlameTone.WARNING
    val toneColors = resolveFlameToneColors(tone)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(toneColors.container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (completed) Icons.Outlined.CheckCircle else step.icon,
                contentDescription = null,
                tint = toneColors.fg,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = step.headline,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
            )
            FlameStatusPill(
                text = statusText,
                tone = tone,
            )
        }
    }
}

@Composable
private fun OnboardingWhyBox(whyText: String) {
    val palette = FlameThemeTokens.palette
    FlameCard(
        containerColor = palette.surfaceSubtle,
        borderColor = palette.hairline,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = palette.inkSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "为什么要设置这一项？",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = whyText,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.inkSecondary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SingleItemStepBody(
    item: SetupChecklistItem,
    onPrimaryAction: () -> Unit,
    extraHighlight: String? = null,
    nativePopupHint: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    onConfirmCheckedChange: ((Boolean) -> Unit)? = null,
    confirmCheckboxEnabled: Boolean = true,
    confirmUnlockHint: String? = null,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors

    OnboardingWhyBox(whyText = item.whyNeeded)

    FlameCard(contentPadding = PaddingValues(15.dp)) {
        Text(
            text = "怎么操作",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = palette.ink,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.howToHint,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.inkSecondary,
        )

        if (!nativePopupHint.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = nativePopupHint,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = palette.ink,
            )
        }

        if (!extraHighlight.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = extraHighlight,
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkTertiary,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!item.completed) {
                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier.heightIn(min = 42.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.monoCtaContainer,
                        contentColor = palette.monoCtaContent,
                    ),
                ) {
                    Text(
                        text = item.primaryActionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onPrimaryAction,
                    modifier = Modifier.heightIn(min = 42.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.hairlineStrong),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = palette.surface,
                        contentColor = palette.ink,
                    ),
                ) {
                    Text(
                        text = item.primaryActionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            if (item.secondaryActionLabel != null && onSecondaryAction != null) {
                OutlinedButton(
                    onClick = onSecondaryAction,
                    modifier = Modifier.heightIn(min = 42.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.hairlineStrong),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = palette.surface,
                        contentColor = palette.ink,
                    ),
                ) {
                    Text(
                        text = item.secondaryActionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        if (item.needsManualConfirmation && onConfirmCheckedChange != null && !item.confirmCheckboxLabel.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(thickness = 1.dp, color = palette.hairline)
            Spacer(modifier = Modifier.height(8.dp))
            if (!confirmUnlockHint.isNullOrBlank()) {
                Text(
                    text = confirmUnlockHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (confirmCheckboxEnabled) semantics.warning else palette.inkTertiary,
                    fontWeight = if (confirmCheckboxEnabled) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .alpha(if (confirmCheckboxEnabled) 1f else 0.55f)
                    .clickable(enabled = confirmCheckboxEnabled) {
                        onConfirmCheckedChange(!item.completed)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Checkbox(
                    checked = item.completed,
                    enabled = confirmCheckboxEnabled,
                    onCheckedChange = onConfirmCheckedChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = palette.monoCtaContainer,
                        checkmarkColor = palette.monoCtaContent,
                        uncheckedColor = palette.hairlineStrong,
                        disabledUncheckedColor = palette.hairline,
                    ),
                )
                Text(
                    text = item.confirmCheckboxLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (confirmCheckboxEnabled) palette.ink else palette.inkTertiary,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "授权完成后返回本应用，上方状态会自动亮绿勾并解锁下一步。",
            style = MaterialTheme.typography.labelSmall,
            color = palette.inkTertiary,
        )
    }
}

@Composable
private fun CompactSetupActionCard(
    icon: ImageVector,
    item: SetupChecklistItem,
    onPrimaryAction: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val tone = if (item.completed) FlameTone.HEALTHY else FlameTone.WARNING
    val toneColors = resolveFlameToneColors(tone)

    FlameCard(contentPadding = PaddingValues(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(toneColors.container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (item.completed) Icons.Outlined.CheckCircle else icon,
                    contentDescription = null,
                    tint = toneColors.fg,
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
                tone = tone,
                showDot = false,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.whyNeeded,
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkSecondary,
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (!item.completed) {
            Button(
                onClick = onPrimaryAction,
                modifier = Modifier.heightIn(min = 40.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.monoCtaContainer,
                    contentColor = palette.monoCtaContent,
                ),
            ) {
                Text(
                    text = item.primaryActionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            OutlinedButton(
                onClick = onPrimaryAction,
                modifier = Modifier.heightIn(min = 40.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, palette.hairlineStrong),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = palette.surface,
                    contentColor = palette.ink,
                ),
            ) {
                Text(
                    text = item.primaryActionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun stepStatusLabel(step: OnboardingStep, readiness: SetupReadinessSnapshot): String = when (step) {
    OnboardingStep.NOTIFICATIONS -> readiness.itemById(SetupItemId.NOTIFICATIONS).statusLabel
    OnboardingStep.AUTO_RETURN -> readiness.itemById(SetupItemId.AUTO_RETURN_OVERLAY).statusLabel
    OnboardingStep.BATTERY_AND_ALARM -> {
        val b = readiness.itemById(SetupItemId.BATTERY_OPTIMIZATION).completed
        val a = readiness.itemById(SetupItemId.EXACT_ALARM).completed
        when {
            b && a -> "两项均已就绪"
            b || a -> "已完成 1/2 项"
            else -> "待设置 2 项"
        }
    }

    OnboardingStep.OEM_BACKGROUND -> readiness.itemById(SetupItemId.OEM_BACKGROUND_AUTOSTART).statusLabel
    OnboardingStep.DOUYIN_LOGIN -> readiness.itemById(SetupItemId.DOUYIN_LOGIN).statusLabel
    OnboardingStep.SELECT_TARGETS -> readiness.itemById(SetupItemId.SELECT_TARGETS).statusLabel
}

@SuppressLint("BatteryLife")
private fun buildDirectBatteryOptimizationIntent(packageName: String): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:$packageName")
    }
