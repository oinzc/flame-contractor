package io.github.oinzc.flamecontractor.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens

@Immutable
data class FlameFilterChipItem(
    val key: String,
    val label: String,
    val count: Int? = null,
)

@Composable
fun FlameStatusPill(
    text: String,
    tone: FlameTone,
    modifier: Modifier = Modifier,
    showDot: Boolean = true,
) {
    val toneColors = resolveFlameToneColors(tone)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = toneColors.container,
        contentColor = toneColors.onContainer,
        border = BorderStroke(1.dp, toneColors.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(toneColors.accent),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun FlameMiniTag(
    text: String,
    tone: FlameTone = FlameTone.NEUTRAL,
    modifier: Modifier = Modifier,
) {
    val toneColors = resolveFlameToneColors(tone)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = toneColors.container,
        contentColor = toneColors.onContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 火花连签徽章：严格锁定 `FlameSemanticColors.sparkGold*` 色彩与等宽数字（tnum），不受动态取色冲淡。
 */
@Composable
fun FlameStreakBadge(
    days: Int,
    modifier: Modifier = Modifier,
    prefixText: String? = null,
) {
    val semantics = FlameThemeTokens.semanticColors
    val labelText = if (prefixText.isNullOrEmpty()) {
        days.toString()
    } else {
        "$prefixText$days"
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = semantics.sparkGoldContainer,
        contentColor = semantics.sparkGold,
        border = BorderStroke(1.dp, semantics.sparkGoldBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = semantics.sparkGold,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = labelText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * 抖音 6 种官方火花类型中文简称：
 * `couple` 情侣 / `family` 家人 / `normal` 普通 / `soulmate` 知己 / `mate` 搭子 / `bosom` 挚友。
 */
fun officialSparkKindShortLabel(sparkKind: String?): String? = when (sparkKind?.trim()?.lowercase()) {
    "couple" -> "情侣"
    "family" -> "家人"
    "normal" -> "普通"
    "soulmate" -> "知己"
    "mate" -> "搭子"
    "bosom" -> "挚友"
    else -> null
}

/**
 * 抖音官方火花带「火花」后缀的完整前缀（未命中已知类型时回退「官方火花」）。
 */
fun officialSparkKindFullLabel(sparkKind: String?): String =
    officialSparkKindShortLabel(sparkKind)?.let { "${it}火花" } ?: "官方火花"

/**
 * 抖音官方火花快照展示徽章（与桌面端 `SparkChip` 口径一致，仅作展示，绝不改写本机 `streak_days`）：
 * - 今日已亮（`litToday == true`）：暖橙亮火徽章（实心亮橙火 + 天数 + 已亮/今日已亮）
 * - 今日未亮灰火（`litToday == false`）：克制中性灰柔和描边徽章（描边灰火 + 天数 + 待续）
 * - 今日亮灰未知（`litToday == null`）：保留经典暖橙火花天数徽章
 * - 有官方火花文本（如 `重燃中 2/3` / `X天后消失`）：包含「后消失」时使用警告态与时钟图标
 * - 明确无火花 / 尚未扫描：柔和中性胶囊「无火花」/「待扫描」
 */
@Composable
fun FlameOfficialSparkBadge(
    sparkDays: Int?,
    sparkKind: String?,
    sparkLabel: String?,
    hasOfficialSpark: Boolean,
    modifier: Modifier = Modifier,
    litToday: Boolean? = null,
    compact: Boolean = false,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val cleanLabel = sparkLabel?.trim()?.takeIf { it.isNotEmpty() }
    val kindFullName = officialSparkKindFullLabel(sparkKind)
    val litContentDesc = when (litToday) {
        true -> "$kindFullName，今日已点亮"
        false -> "$kindFullName，今日未点亮（待续）"
        null -> kindFullName
    }

    when {
        hasOfficialSpark && sparkDays != null && sparkDays > 0 -> {
            if (litToday == false) {
                Surface(
                    modifier = modifier,
                    shape = RoundedCornerShape(999.dp),
                    color = palette.surfaceSubtle,
                    contentColor = palette.inkSecondary,
                    border = BorderStroke(1.dp, palette.hairlineStrong),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LocalFireDepartment,
                            contentDescription = litContentDesc,
                            tint = palette.inkTertiary,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = "${sparkDays}天",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.inkSecondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkTertiary,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Text(
                            text = "待续",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = palette.inkTertiary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            } else {
                Surface(
                    modifier = modifier,
                    shape = RoundedCornerShape(999.dp),
                    color = semantics.sparkGoldContainer,
                    contentColor = semantics.sparkGold,
                    border = BorderStroke(1.dp, semantics.sparkGoldBorder),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocalFireDepartment,
                            contentDescription = litContentDesc,
                            tint = semantics.sparkGold,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = "${sparkDays}天",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                        )
                        if (litToday == true) {
                            Text(
                                text = "·",
                                style = MaterialTheme.typography.labelSmall,
                                color = semantics.sparkGold.copy(alpha = 0.72f),
                                maxLines = 1,
                                softWrap = false,
                            )
                            Text(
                                text = if (compact) "已亮" else "今日已亮",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }

        hasOfficialSpark && cleanLabel != null -> {
            val isExpiring = cleanLabel.contains("后消失")
            val isGrayUnlit = !isExpiring && litToday == false
            val containerColor = when {
                isExpiring -> semantics.warningContainer
                isGrayUnlit -> palette.surfaceSubtle
                else -> semantics.sparkGoldContainer
            }
            val borderColor = when {
                isExpiring -> semantics.warningBorder
                isGrayUnlit -> palette.hairlineStrong
                else -> semantics.sparkGoldBorder
            }
            val fgColor = when {
                isExpiring -> semantics.onWarningContainer
                isGrayUnlit -> palette.inkSecondary
                else -> semantics.sparkGold
            }
            val iconColor = when {
                isExpiring -> semantics.warning
                isGrayUnlit -> palette.inkTertiary
                else -> semantics.sparkGold
            }
            val icon = when {
                isExpiring -> Icons.Outlined.Schedule
                isGrayUnlit -> Icons.Outlined.LocalFireDepartment
                else -> Icons.Filled.LocalFireDepartment
            }
            val displayLabel = when {
                isGrayUnlit && !cleanLabel.contains("待续") && !cleanLabel.contains("重燃") ->
                    "$cleanLabel · 待续"
                !isExpiring && litToday == true ->
                    "$cleanLabel · ${if (compact) "已亮" else "今日已亮"}"
                else -> cleanLabel
            }

            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(999.dp),
                color = containerColor,
                contentColor = fgColor,
                border = BorderStroke(1.dp, borderColor),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = litContentDesc,
                        tint = iconColor,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = displayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        hasOfficialSpark -> {
            val isGrayUnlit = litToday == false
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(999.dp),
                color = if (isGrayUnlit) palette.surfaceSubtle else semantics.sparkGoldContainer,
                contentColor = if (isGrayUnlit) palette.inkSecondary else semantics.sparkGold,
                border = BorderStroke(1.dp, if (isGrayUnlit) palette.hairlineStrong else semantics.sparkGoldBorder),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = if (isGrayUnlit) {
                            Icons.Outlined.LocalFireDepartment
                        } else {
                            Icons.Filled.LocalFireDepartment
                        },
                        contentDescription = litContentDesc,
                        tint = if (isGrayUnlit) palette.inkTertiary else semantics.sparkGold,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = when (litToday) {
                            true -> if (compact) "已亮" else "今日已亮"
                            false -> "待续"
                            null -> "有火花"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        else -> {
            val neutralText = when {
                cleanLabel == "无火花" -> "无火花"
                cleanLabel != null -> cleanLabel
                else -> "待扫描"
            }
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(999.dp),
                color = palette.surfaceSubtle,
                contentColor = palette.inkTertiary,
                border = BorderStroke(1.dp, palette.hairline),
            ) {
                Text(
                    text = neutralText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
fun FlameSearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp),
        shape = RoundedCornerShape(999.dp),
        color = palette.surfaceSubtle,
        border = BorderStroke(1.dp, palette.hairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = palette.inkTertiary,
                modifier = Modifier.size(18.dp),
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.ink),
                    cursorBrush = SolidColor(palette.monoCtaContainer),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.inkTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    },
                )
            }
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn(animationSpec = tween(140, easing = FastOutSlowInEasing)),
                exit = fadeOut(animationSpec = tween(140, easing = FastOutSlowInEasing)),
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(palette.surfacePress)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "清空搜索",
                        tint = palette.inkSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun FlameFilterChipRow(
    items: List<FlameFilterChipItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        items(items = items, key = { it.key }) { item ->
            val selected = item.key == selectedKey
            val containerColor by animateColorAsState(
                targetValue = if (selected) palette.monoCtaContainer else palette.surface,
                animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                label = "filter_chip_bg",
            )
            val contentColor by animateColorAsState(
                targetValue = if (selected) palette.monoCtaContent else palette.inkSecondary,
                animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                label = "filter_chip_fg",
            )
            val borderColor by animateColorAsState(
                targetValue = if (selected) palette.monoCtaContainer else palette.hairline,
                animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                label = "filter_chip_border",
            )
            val text = if (item.count != null) "${item.label} ${item.count}" else item.label

            Surface(
                onClick = { onSelect(item.key) },
                shape = RoundedCornerShape(999.dp),
                color = containerColor,
                contentColor = contentColor,
                border = BorderStroke(1.dp, borderColor),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlameModalSheet(
    onDismissRequest: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    trailingHeader: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val isDark = FlameThemeTokens.isDark
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = palette.surface,
        contentColor = palette.ink,
        scrimColor = Color.Black.copy(alpha = if (isDark) 0.62f else 0.36f),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.hairlineStrong),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 18.dp),
        ) {
            if (!title.isNullOrBlank() || !subtitle.isNullOrBlank() || trailingHeader != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (!title.isNullOrBlank()) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                color = palette.ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    trailingHeader?.invoke()
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(thickness = 1.dp, color = palette.hairline)
                Spacer(modifier = Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun FlameSheetActionRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingText: String? = null,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val containerColor = when {
        !enabled -> palette.surfaceSubtle
        danger && primary -> semantics.danger
        danger -> semantics.dangerContainer
        primary -> palette.monoCtaContainer
        else -> palette.surfaceSubtle
    }
    val contentColor = when {
        !enabled -> palette.inkTertiary
        danger && primary -> MaterialTheme.colorScheme.onError
        danger -> semantics.danger
        primary -> palette.monoCtaContent
        else -> palette.ink
    }
    val borderColor = when {
        !enabled -> palette.hairline
        danger && primary -> semantics.danger
        danger -> semantics.dangerBorder
        primary -> palette.monoCtaContainer
        else -> palette.hairline
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!trailingText.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = trailingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.78f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
fun FlameEmptyStateCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = FlameThemeTokens.palette
    FlameCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 26.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.surfaceSubtle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = palette.inkSecondary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.ink,
                textAlign = TextAlign.Center,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkSecondary,
                textAlign = TextAlign.Center,
            )
            if (!actionLabel.isNullOrBlank() && onAction != null) {
                Spacer(modifier = Modifier.height(6.dp))
                FlameTopPillButton(
                    text = actionLabel,
                    onClick = onAction,
                    primary = true,
                )
            }
        }
    }
}
