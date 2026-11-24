package io.github.oinzc.flamecontractor.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.oinzc.flamecontractor.core.theme.ThemeMode
import io.github.oinzc.flamecontractor.core.theme.ThemePreference
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameStreakBadge
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import io.github.oinzc.flamecontractor.widget.FlameStatusWidgetUpdater
import io.github.oinzc.flamecontractor.widget.WidgetPinOutcome

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsAppearancePage(
    themePreference: ThemePreference,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val isDark = FlameThemeTokens.isDark

    var placedWidgetCount by remember(context) {
        mutableIntStateOf(FlameStatusWidgetUpdater.getPlacedWidgetCount(context))
    }
    var widgetPinFeedback by remember { mutableStateOf<Pair<String, FlameTone>?>(null) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                placedWidgetCount = FlameStatusWidgetUpdater.getPlacedWidgetCount(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    FlameSubpageScaffold(
        title = SettingsSection.APPEARANCE.title,
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        SettingsPageScrollColumn(contentPadding = innerPadding) {
            // 当前生效外观预览条
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
                            text = buildString {
                                append(if (isDark) "深色碳黑" else "浅色模式")
                                append(" · 中性单色 + 烈焰橙")
                            },
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

            // 1. 主题模式 3 选 1
            FlameGroupedSection(title = "主题模式") {
                THEME_MODE_OPTIONS.forEachIndexed { index, option ->
                    val selected = themePreference.mode == option.mode
                    val isLast = index == THEME_MODE_OPTIONS.lastIndex
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    onClick = { onSelectThemeMode(option.mode) },
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

            // 2. 桌面状态小组件一键添加与澎湃 OS 指引
            FlameGroupedSection(title = "桌面状态小组件") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FlameRowAvatar(tone = FlameTone.NEUTRAL) {
                            Icon(
                                imageVector = Icons.Outlined.Widgets,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "续火状态小组件（4×2 / 2×2）",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.ink,
                                )
                                if (placedWidgetCount > 0) {
                                    FlameMiniTag(
                                        text = "已添加 $placedWidgetCount 个",
                                        tone = FlameTone.HEALTHY,
                                    )
                                } else {
                                    FlameMiniTag(
                                        text = "未添加",
                                        tone = FlameTone.NEUTRAL,
                                    )
                                }
                            }
                            Text(
                                text = "在桌面直接查看当前账号健康度、今日已完成人数与下次排程时刻",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                            )
                        }
                    }

                    widgetPinFeedback?.let { (msg, tone) ->
                        SettingsFeedbackBanner(message = msg, tone = tone)
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                when (FlameStatusWidgetUpdater.requestPinToHomeScreen(context)) {
                                    WidgetPinOutcome.DISPATCHED -> {
                                        widgetPinFeedback =
                                            "已向桌面发起添加请求。若未弹出系统确认框，请点右侧开启「桌面快捷方式」权限后再试，或按下方步骤手动添加。" to FlameTone.HEALTHY
                                    }

                                    WidgetPinOutcome.UNSUPPORTED -> {
                                        widgetPinFeedback =
                                            "当前桌面不支持应用内直接固定，请按下方步骤在系统小部件列表手动添加。" to FlameTone.WARNING
                                    }

                                    WidgetPinOutcome.FAILED -> {
                                        widgetPinFeedback =
                                            "发起添加请求失败，请开启「桌面快捷方式」权限或按下方步骤手动添加。" to FlameTone.DANGER
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.monoCtaContainer,
                                contentColor = palette.monoCtaContent,
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.AddToHomeScreen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "一键添加到桌面",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }

                        OutlinedButton(
                            onClick = { openLauncherShortcutPermissionSettings(context) },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, palette.hairlineStrong),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = "桌面快捷方式权限",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }

                    HorizontalDivider(thickness = 1.dp, color = palette.hairline)

                    Text(
                        text = "小米澎湃 OS / MIUI 手动查找路径：在桌面双指捏合 → 点底部「添加小部件」→ 点右上角「搜索框（放大镜）」进入搜索页 → 不输文字直接滑到最底部点「支持小部件的应用」（或「安卓小部件」）→ 点「全部」找到「抖音续火工」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )
                }
            }
        }
    }
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
