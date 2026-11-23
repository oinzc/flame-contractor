package io.github.oinzc.flamecontractor.ui.settings

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.core.theme.ThemeMode
import io.github.oinzc.flamecontractor.core.theme.ThemePreference
import io.github.oinzc.flamecontractor.core.theme.ThemePreferencesStore
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameListRow
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameStreakBadge
import io.github.oinzc.flamecontractor.ui.components.FlameSubpageScaffold
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens

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

@Composable
internal fun SettingsAppearancePage(
    themePreference: ThemePreference,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColorEnabled: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val isDark = FlameThemeTokens.isDark
    val dynamicActive = FlameThemeTokens.isDynamicColorActive
    val supportsDynamicColor = Build.VERSION.SDK_INT >= ThemePreferencesStore.MIN_DYNAMIC_COLOR_SDK

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
                                append(" · ")
                                append(if (dynamicActive) "Material You 动态取色" else "中性单色 + 烈焰橙")
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
                    SwatchPill(label = "火花", color = palette.ember)
                    SwatchPill(label = "健康", color = semantics.healthy)
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
                            FlameRowAvatar(
                                tone = if (selected) FlameTone.EMBER else FlameTone.NEUTRAL,
                            ) {
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

            // 2. Android 12+ Material You 壁纸动态取色
            FlameGroupedSection(title = "动态取色") {
                FlameListRow(
                    title = "Material You 壁纸取色",
                    subtitle = if (supportsDynamicColor) {
                        "开启后画布容器与常规主操作色将跟随系统壁纸主题色；烈焰橙续火按钮、火花徽章与安全告警色保持锁定"
                    } else {
                        "需要 Android 12（API 31）及以上系统版本，当前设备暂不支持壁纸动态取色"
                    },
                    titleTag = if (!supportsDynamicColor) {
                        { FlameMiniTag(text = "需 Android 12+", tone = FlameTone.NEUTRAL) }
                    } else if (themePreference.dynamicColorEnabled) {
                        { FlameMiniTag(text = "已开启", tone = FlameTone.HEALTHY) }
                    } else {
                        null
                    },
                    leading = {
                        FlameRowAvatar(
                            tone = if (supportsDynamicColor && themePreference.dynamicColorEnabled) {
                                FlameTone.EMBER
                            } else {
                                FlameTone.NEUTRAL
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ColorLens,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    trailing = {
                        Switch(
                            checked = supportsDynamicColor && themePreference.dynamicColorEnabled,
                            onCheckedChange = { enabled ->
                                if (supportsDynamicColor) {
                                    onSetDynamicColorEnabled(enabled)
                                }
                            },
                            enabled = supportsDynamicColor,
                            colors = settingsSwitchColors(),
                        )
                    },
                    showDivider = false,
                    onClick = if (supportsDynamicColor) {
                        { onSetDynamicColorEnabled(!themePreference.dynamicColorEnabled) }
                    } else {
                        null
                    },
                )
            }
        }
    }
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
