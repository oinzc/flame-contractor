package io.github.oinzc.flamecontractor.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.oinzc.flamecontractor.core.theme.ThemeMode
import io.github.oinzc.flamecontractor.core.theme.ThemePreference
import io.github.oinzc.flamecontractor.core.theme.UiDisplayMode
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameStreakBadge
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.flamePressScale
import io.github.oinzc.flamecontractor.ui.components.performFlameLightTick
import io.github.oinzc.flamecontractor.ui.components.rememberReducedMotion
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import io.github.oinzc.flamecontractor.widget.FlameStatusWidgetUpdater
import io.github.oinzc.flamecontractor.widget.FlameWidgetKind
import io.github.oinzc.flamecontractor.widget.WidgetAppearanceStyle
import io.github.oinzc.flamecontractor.widget.WidgetPinEventBus
import io.github.oinzc.flamecontractor.widget.WidgetPinOutcome
import io.github.oinzc.flamecontractor.widget.WidgetPreferencesStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class ThemeModeOption(
    val mode: ThemeMode,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private val THEME_MODE_OPTIONS = listOf(
    ThemeModeOption(
        mode = ThemeMode.SYSTEM,
        title = "跟随系统",
        subtitle = "自动同步系统当前的浅色或深色外观",
        icon = Icons.Outlined.BrightnessAuto,
    ),
    ThemeModeOption(
        mode = ThemeMode.LIGHT,
        title = "浅色模式",
        subtitle = "冷灰白画布与纯白分组卡片，通透明快",
        icon = Icons.Outlined.LightMode,
    ),
    ThemeModeOption(
        mode = ThemeMode.DARK,
        title = "深色碳黑",
        subtitle = "中性碳黑低眩光表面，夜间查看更舒适",
        icon = Icons.Outlined.DarkMode,
    ),
)

private data class WidgetCenterFeedback(
    val message: String,
    val tone: FlameTone,
    val showGoHomeAction: Boolean = false,
)

private const val PIN_DEBOUNCE_MS = 1_500L
private const val INLINE_REFRESH_RECEIPT_MS = 1_600L

private data class DisplayModeOption(
    val mode: UiDisplayMode,
    val title: String,
    val badge: String,
    val subtitle: String,
    val icon: ImageVector,
)

private val DISPLAY_MODE_OPTIONS = listOf(
    DisplayModeOption(
        mode = UiDisplayMode.STANDARD,
        title = "标准模式",
        badge = "完整工作台",
        subtitle = "保留底部 4 栏导航、执行动态时间线、消息模板库与 30 日发送记录",
        icon = Icons.Outlined.Dashboard,
    ),
    DisplayModeOption(
        mode = UiDisplayMode.SIMPLE,
        title = "极简模式",
        badge = "单页大圆",
        subtitle = "隐藏底栏与复杂图表，保留超大一键续火圆钮、醒目异常拦截与好友快捷入口",
        icon = Icons.Outlined.LocalFireDepartment,
    ),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsAppearancePage(
    themePreference: ThemePreference,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    embeddedInSplitPane: Boolean = false,
    onSelectDisplayMode: (UiDisplayMode) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val isDark = FlameThemeTokens.isDark

    val widgetPrefsStore = remember(context) { WidgetPreferencesStore.fromContext(context) }
    val widgetPrefs by widgetPrefsStore.preferences.collectAsState()

    var placedCounts by remember(context) {
        mutableStateOf(FlameStatusWidgetUpdater.getPlacedCountsByKind(context))
    }
    val busyKinds = remember { mutableStateMapOf<FlameWidgetKind, Boolean>() }
    val justRefreshedKinds = remember { mutableStateMapOf<FlameWidgetKind, Boolean>() }
    val refreshNonces = remember { mutableStateMapOf<FlameWidgetKind, Int>() }
    var feedback by remember { mutableStateOf<WidgetCenterFeedback?>(null) }

    fun showActionSnackbar(message: String, showGoHome: Boolean = false) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (showGoHome) "去桌面看" else null,
                withDismissAction = !showGoHome,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && showGoHome) {
                openLauncherHomeScreen(context)
            }
        }
    }

    LaunchedEffect(context) {
        WidgetPinEventBus.events.collect { event ->
            placedCounts = FlameStatusWidgetUpdater.getPlacedCountsByKind(context)
            feedback = WidgetCenterFeedback(
                message = "「${event.kind.title}」已添加到桌面",
                tone = FlameTone.HEALTHY,
                showGoHomeAction = true,
            )
            showActionSnackbar("「${event.kind.title}」已添加到桌面", showGoHome = true)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                placedCounts = FlameStatusWidgetUpdater.getPlacedCountsByKind(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun triggerRefreshWidget(kind: FlameWidgetKind) {
        FlameStatusWidgetUpdater.requestInteractiveRefreshAll(context)
        placedCounts = FlameStatusWidgetUpdater.getPlacedCountsByKind(context)
        val nextNonce = (refreshNonces[kind] ?: 0) + 1
        refreshNonces[kind] = nextNonce
        justRefreshedKinds[kind] = true
        feedback = WidgetCenterFeedback(
            message = "已触发桌面上所有小组件同步刷新（「${kind.title}」最新状态已推送）",
            tone = FlameTone.HEALTHY,
            showGoHomeAction = true,
        )
        showActionSnackbar("已刷新桌面「${kind.title}」最新状态", showGoHome = true)
        coroutineScope.launch {
            delay(INLINE_REFRESH_RECEIPT_MS)
            if (refreshNonces[kind] == nextNonce) {
                justRefreshedKinds[kind] = false
            }
        }
    }

    fun triggerPinWidget(kind: FlameWidgetKind) {
        if (busyKinds[kind] == true) return
        val beforeCount = FlameStatusWidgetUpdater.getPlacedCountsByKind(context)[kind] ?: 0
        busyKinds[kind] = true

        when (FlameStatusWidgetUpdater.requestPinWidget(context, kind)) {
            WidgetPinOutcome.DISPATCHED -> {
                coroutineScope.launch {
                    delay(350L)
                    val midCounts = FlameStatusWidgetUpdater.getPlacedCountsByKind(context)
                    placedCounts = midCounts
                    val midAfter = midCounts[kind] ?: 0
                    if (midAfter > beforeCount) {
                        feedback = WidgetCenterFeedback(
                            message = "「${kind.title}」已添加到桌面（共 $midAfter 个）",
                            tone = FlameTone.HEALTHY,
                            showGoHomeAction = true,
                        )
                        showActionSnackbar("「${kind.title}」已添加到桌面", showGoHome = true)
                    } else {
                        delay(650L)
                        val finalCounts = FlameStatusWidgetUpdater.getPlacedCountsByKind(context)
                        placedCounts = finalCounts
                        val finalAfter = finalCounts[kind] ?: 0
                        feedback = if (finalAfter > beforeCount) {
                            showActionSnackbar("「${kind.title}」已添加到桌面", showGoHome = true)
                            WidgetCenterFeedback(
                                message = "「${kind.title}」已添加到桌面（共 $finalAfter 个）",
                                tone = FlameTone.HEALTHY,
                                showGoHomeAction = true,
                            )
                        } else {
                            WidgetCenterFeedback(
                                message = "已发送添加请求。若桌面未出现新卡片，请先点下方「权限设置」允许「桌面快捷方式」，或从系统小部件列表手动添加。",
                                tone = FlameTone.WARNING,
                                showGoHomeAction = false,
                            )
                        }
                    }
                    delay(PIN_DEBOUNCE_MS - 350L)
                    busyKinds[kind] = false
                }
            }

            WidgetPinOutcome.UNSUPPORTED -> {
                busyKinds[kind] = false
                feedback = WidgetCenterFeedback(
                    message = "当前桌面不支持应用内直接固定，请按底部指引从系统小部件列表添加。",
                    tone = FlameTone.WARNING,
                )
            }

            WidgetPinOutcome.FAILED -> {
                busyKinds[kind] = false
                feedback = WidgetCenterFeedback(
                    message = "发起添加请求失败，请点下方「权限设置」开启「桌面快捷方式」权限后重试。",
                    tone = FlameTone.DANGER,
                )
            }
        }
    }

    FlameSubpageScaffold(
        title = SettingsSection.APPEARANCE.title,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        embeddedInSplitPane = embeddedInSplitPane,
    ) { innerPadding ->
        SettingsPageScrollColumn(
            contentPadding = innerPadding,
            fullWidthInPane = embeddedInSplitPane,
        ) {
            // 1. 当前生效外观预览条
            FlameCard(contentPadding = PaddingValues(15.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "当前生效外观",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.ink,
                        )
                        Text(
                            text = "${if (isDark) "深色碳黑" else "浅色模式"} · ${displayModeLabel(themePreference.displayMode)} · 烈焰橙",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                        )
                    }
                    FlameStreakBadge(days = 15)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SwatchPill(label = "画布", color = palette.canvas)
                    SwatchPill(label = "主操作", color = palette.monoCtaContainer)
                    SwatchPill(label = "续火主钮", color = palette.ember)
                    SwatchPill(label = "火花", color = semantics.sparkGold)
                }
            }

            // 2. 界面模式（标准模式 vs 极简模式，即时生效）
            FlameGroupedSection(title = "界面模式") {
                val modeHaptics = LocalHapticFeedback.current
                val modeReducedMotion = rememberReducedMotion()
                val modeAnimMs = if (modeReducedMotion) 0 else 160
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DISPLAY_MODE_OPTIONS.forEach { option ->
                        val selected = themePreference.displayMode == option.mode
                        val cardInteraction = remember(option.mode) { MutableInteractionSource() }
                        val cardBorder by animateColorAsState(
                            targetValue = if (selected) {
                                if (option.mode == UiDisplayMode.SIMPLE) palette.ember else palette.monoCtaContainer
                            } else {
                                palette.hairline
                            },
                            animationSpec = tween(modeAnimMs, easing = FlameMotionSpec.easeOut),
                            label = "display_mode_border_${option.mode.name}",
                        )
                        val cardBg by animateColorAsState(
                            targetValue = if (selected) {
                                if (option.mode == UiDisplayMode.SIMPLE) {
                                    palette.emberContainer.copy(alpha = if (isDark) 0.55f else 0.72f)
                                } else {
                                    palette.surfaceSubtle
                                }
                            } else {
                                palette.surface
                            },
                            animationSpec = tween(modeAnimMs, easing = FlameMotionSpec.easeOut),
                            label = "display_mode_bg_${option.mode.name}",
                        )

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .flamePressScale(interactionSource = cardInteraction)
                                .clip(RoundedCornerShape(16.dp))
                                .selectable(
                                    selected = selected,
                                    interactionSource = cardInteraction,
                                    indication = LocalIndication.current,
                                    role = Role.RadioButton,
                                    onClick = {
                                        if (!selected) {
                                            modeHaptics.performFlameLightTick()
                                            onSelectDisplayMode(option.mode)
                                        }
                                    },
                                ),
                            shape = RoundedCornerShape(16.dp),
                            color = cardBg,
                            contentColor = palette.ink,
                            border = BorderStroke(
                                width = if (selected) 1.8.dp else 1.dp,
                                color = cardBorder,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                FlameRowAvatar(
                                    tone = if (option.mode == UiDisplayMode.SIMPLE) {
                                        FlameTone.EMBER
                                    } else {
                                        FlameTone.NEUTRAL
                                    },
                                ) {
                                    Icon(
                                        imageVector = option.icon,
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
                                            text = option.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = palette.ink,
                                        )
                                        FlameMiniTag(
                                            text = if (selected) "当前使用" else option.badge,
                                            tone = if (selected) {
                                                if (option.mode == UiDisplayMode.SIMPLE) FlameTone.EMBER else FlameTone.HEALTHY
                                            } else {
                                                FlameTone.NEUTRAL
                                            },
                                        )
                                    }
                                    Text(
                                        text = option.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.inkSecondary,
                                    )
                                }
                                RadioButton(
                                    selected = selected,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = if (option.mode == UiDisplayMode.SIMPLE) {
                                            palette.ember
                                        } else {
                                            palette.monoCtaContainer
                                        },
                                        unselectedColor = palette.hairlineStrong,
                                    ),
                                )
                            }
                        }
                    }

                    Text(
                        text = "切换即时生效且不影响已有好友、模板与续火记录；在极简模式下随时可通过右上角「设置」切回标准模式。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkTertiary,
                    )
                }
            }

            // 3. App 主题模式 3 选 1
            FlameGroupedSection(title = "应用主题模式") {
                THEME_MODE_OPTIONS.forEachIndexed { index, option ->
                    val selected = themePreference.mode == option.mode
                    val isLast = index == THEME_MODE_OPTIONS.lastIndex
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    onClick = {
                                        onSelectThemeMode(option.mode)
                                        FlameStatusWidgetUpdater.requestUpdateAll(context)
                                    },
                                    role = Role.RadioButton,
                                )
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            FlameRowAvatar(tone = FlameTone.NEUTRAL) {
                                Icon(
                                    imageVector = option.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = option.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.ink,
                                )
                                Text(
                                    text = option.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.inkSecondary,
                                )
                            }
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = palette.monoCtaContainer,
                                    unselectedColor = palette.hairlineStrong,
                                ),
                            )
                        }
                        if (!isLast) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 60.dp, end = 14.dp),
                                thickness = 1.dp,
                                color = palette.hairline,
                            )
                        }
                    }
                }
            }

            // 3. 桌面小组件独立外观与隐私
            FlameGroupedSection(title = "桌面小组件外观与隐私") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "小组件风格（立即联动桌面）",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.ink,
                        )
                        val styleHaptics = LocalHapticFeedback.current
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            WidgetAppearanceStyle.entries.forEach { style ->
                                val selected = widgetPrefs.appearanceStyle == style
                                val chipInteraction = remember(style) { MutableInteractionSource() }
                                val chipBg by animateColorAsState(
                                    targetValue = if (selected) palette.monoCtaContainer else palette.surfaceSubtle,
                                    animationSpec = tween(160, easing = FlameMotionSpec.standardEasing),
                                    label = "widget_style_bg_${style.name}",
                                )
                                val chipFg by animateColorAsState(
                                    targetValue = if (selected) palette.monoCtaContent else palette.inkSecondary,
                                    animationSpec = tween(160, easing = FlameMotionSpec.standardEasing),
                                    label = "widget_style_fg_${style.name}",
                                )
                                val chipBorder by animateColorAsState(
                                    targetValue = if (selected) palette.monoCtaContainer else palette.hairline,
                                    animationSpec = tween(160, easing = FlameMotionSpec.standardEasing),
                                    label = "widget_style_border_${style.name}",
                                )
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = chipBg,
                                    contentColor = chipFg,
                                    border = BorderStroke(1.dp, chipBorder),
                                    modifier = Modifier
                                        .flamePressScale(interactionSource = chipInteraction)
                                        .clip(RoundedCornerShape(999.dp))
                                        .clickable(
                                            interactionSource = chipInteraction,
                                            indication = LocalIndication.current,
                                        ) {
                                            styleHaptics.performFlameLightTick()
                                            widgetPrefsStore.setAppearanceStyle(style)
                                            FlameStatusWidgetUpdater.requestUpdateAll(context)
                                        },
                                ) {
                                    Text(
                                        text = style.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(thickness = 1.dp, color = palette.hairline)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                widgetPrefsStore.setHideFriendNames(!widgetPrefs.hideFriendNames)
                                FlameStatusWidgetUpdater.requestUpdateAll(context)
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
                                text = "好友火花榜隐藏全名",
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.ink,
                            )
                            Text(
                                text = "开启后桌面仅显示首字脱敏（如 林**），防止旁人瞥见备注",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                            )
                        }
                        Switch(
                            checked = widgetPrefs.hideFriendNames,
                            onCheckedChange = { hide ->
                                widgetPrefsStore.setHideFriendNames(hide)
                                FlameStatusWidgetUpdater.requestUpdateAll(context)
                            },
                            colors = settingsSwitchColors(),
                        )
                    }
                }
            }

            // 4. 5 款多尺寸桌面小组件中心
            FlameGroupedSection(title = "挑选并添加小组件（共 ${FlameWidgetKind.entries.size} 款）") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val reducedMotion = rememberReducedMotion()
                    var lastFeedback by remember { mutableStateOf<WidgetCenterFeedback?>(null) }
                    if (feedback != null) {
                        lastFeedback = feedback
                    }
                    AnimatedVisibility(
                        visible = feedback != null,
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
                        lastFeedback?.let { fb ->
                            WidgetPinFeedbackBanner(
                                feedback = fb,
                                onGoHome = { openLauncherHomeScreen(context) },
                            )
                        }
                    }

                    FlameWidgetKind.entries.forEach { kind ->
                        val count = placedCounts[kind] ?: 0
                        val isBusy = busyKinds[kind] == true
                        val justRefreshed = justRefreshedKinds[kind] == true
                        WidgetOptionCard(
                            kind = kind,
                            placedCount = count,
                            isBusy = isBusy,
                            justRefreshed = justRefreshed,
                            onAddClick = { triggerPinWidget(kind) },
                            onRefreshClick = { triggerRefreshWidget(kind) },
                            onGoHomeClick = { openLauncherHomeScreen(context) },
                        )
                    }

                    HorizontalDivider(thickness = 1.dp, color = palette.hairline)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "澎湃 OS 若拦截添加，请先开启「桌面快捷方式」权限",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                        )
                        TextButton(
                            onClick = { openLauncherShortcutPermissionSettings(context) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "权限设置 ›",
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.ink,
                            )
                        }
                    }

                    Text(
                        text = "手动添加路径：桌面双指捏合 → 点底部「添加小部件」→ 点右上角搜索框进入搜索页 → 不输文字直接滑到最底部点「支持小部件的应用 / 安卓小部件」→ 抖音续火工。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkTertiary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetOptionCard(
    kind: FlameWidgetKind,
    placedCount: Int,
    isBusy: Boolean,
    justRefreshed: Boolean,
    onAddClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onGoHomeClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val haptics = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()
    var refreshSpinTurns by remember(kind) { mutableIntStateOf(0) }
    val refreshIconRotation by animateFloatAsState(
        targetValue = if (reducedMotion) 0f else refreshSpinTurns * 360f,
        animationSpec = tween(
            durationMillis = FlameMotionSpec.ICON_SPIN_MS,
            easing = FlameMotionSpec.easeOut,
        ),
        label = "widget_card_refresh_spin_${kind.name}",
    )
    val refreshContainerColor by animateColorAsState(
        targetValue = if (justRefreshed) semantics.healthy else palette.monoCtaContainer,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else 180,
            easing = FlameMotionSpec.easeOut,
        ),
        label = "widget_card_refresh_bg_${kind.name}",
    )
    val refreshContentColor by animateColorAsState(
        targetValue = if (justRefreshed) Color.White else palette.monoCtaContent,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else 180,
            easing = FlameMotionSpec.easeOut,
        ),
        label = "widget_card_refresh_fg_${kind.name}",
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = kind.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.ink,
                )
                FlameMiniTag(text = kind.sizeBadge, tone = FlameTone.NEUTRAL)
                if (placedCount > 0) {
                    FlameMiniTag(text = "桌面已有 $placedCount 个", tone = FlameTone.HEALTHY)
                } else {
                    FlameMiniTag(text = "未添加", tone = FlameTone.NEUTRAL)
                }
            }

            Text(
                text = kind.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkSecondary,
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (placedCount <= 0) {
                    val addInteraction = remember(kind) { MutableInteractionSource() }
                    Button(
                        onClick = {
                            haptics.performFlameLightTick()
                            onAddClick()
                        },
                        enabled = !isBusy,
                        interactionSource = addInteraction,
                        modifier = Modifier.flamePressScale(
                            interactionSource = addInteraction,
                            enabled = !isBusy,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.monoCtaContainer,
                            contentColor = palette.monoCtaContent,
                        ),
                        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.AddToHomeScreen,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = if (isBusy) "添加中…" else "添加到桌面",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                } else {
                    val refreshInteraction = remember(kind) { MutableInteractionSource() }
                    Button(
                        onClick = {
                            haptics.performFlameLightTick()
                            refreshSpinTurns += 1
                            onRefreshClick()
                        },
                        interactionSource = refreshInteraction,
                        modifier = Modifier.flamePressScale(interactionSource = refreshInteraction),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = refreshContainerColor,
                            contentColor = refreshContentColor,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Icon(
                            imageVector = if (justRefreshed) Icons.Outlined.CheckCircle else Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier
                                .size(15.dp)
                                .graphicsLayer {
                                    rotationZ = if (justRefreshed) 0f else refreshIconRotation
                                },
                        )
                        Text(
                            text = if (justRefreshed) "已刷新" else "刷新组件",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }

                    val goHomeInteraction = remember(kind) { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = {
                            haptics.performFlameLightTick()
                            onGoHomeClick()
                        },
                        interactionSource = goHomeInteraction,
                        modifier = Modifier.flamePressScale(interactionSource = goHomeInteraction),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, palette.hairlineStrong),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Home,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = "去桌面查看",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }

                    TextButton(
                        onClick = {
                            haptics.performFlameLightTick()
                            onAddClick()
                        },
                        enabled = !isBusy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (isBusy) "添加中…" else "再添一个",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.inkTertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetPinFeedbackBanner(
    feedback: WidgetCenterFeedback,
    onGoHome: () -> Unit,
) {
    val semantics = FlameThemeTokens.semanticColors
    val (bg, border, fg) = when (feedback.tone) {
        FlameTone.HEALTHY -> Triple(semantics.healthyContainer, semantics.healthy.copy(alpha = 0.35f), semantics.onHealthyContainer)
        FlameTone.WARNING -> Triple(semantics.warningContainer, semantics.warningBorder, semantics.onWarningContainer)
        FlameTone.DANGER -> Triple(semantics.dangerContainer, semantics.dangerBorder, semantics.onDangerContainer)
        else -> Triple(semantics.neutralContainer, semantics.neutral.copy(alpha = 0.25f), semantics.onNeutralContainer)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = feedback.message,
                style = MaterialTheme.typography.bodySmall,
                color = fg,
                modifier = Modifier.weight(1f),
            )
            if (feedback.showGoHomeAction) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = semantics.healthy,
                    contentColor = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onGoHome),
                ) {
                    Text(
                        text = "去桌面看 →",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

private fun openLauncherHomeScreen(context: Context) {
    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(homeIntent) }
}

private fun openLauncherShortcutPermissionSettings(context: Context) {
    val miuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
        putExtra("extra_pkgname", context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val startedMiui = runCatching {
        context.startActivity(miuiIntent)
        true
    }.getOrDefault(false)
    if (startedMiui) return

    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(fallback) }
}

@Composable
private fun SwatchPill(
    label: String,
    color: Color,
) {
    val palette = FlameThemeTokens.palette
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = palette.surfaceSubtle,
        border = BorderStroke(1.dp, palette.hairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkSecondary,
            )
        }
    }
}
