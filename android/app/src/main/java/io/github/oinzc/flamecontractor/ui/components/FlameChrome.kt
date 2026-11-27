package io.github.oinzc.flamecontractor.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.core.theme.UiDisplayMode
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens

/**
 * 手机端悬浮圆角胶囊底栏的统一列表底部预留高度，确保末行内容与按钮永远不被遮挡。
 */
val FLAME_DOCK_BOTTOM_CLEARANCE: Dp = 104.dp

@Composable
fun flameDockBottomPadding(): Dp =
    FLAME_DOCK_BOTTOM_CLEARANCE + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

enum class FlameTone {
    NEUTRAL,
    HEALTHY,
    WARNING,
    DANGER,
    EMBER,
}

@Immutable
data class FlameToneColors(
    val fg: Color,
    val onContainer: Color,
    val container: Color,
    val border: Color,
    val accent: Color,
)

@Composable
fun resolveFlameToneColors(tone: FlameTone): FlameToneColors {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    return when (tone) {
        FlameTone.NEUTRAL -> FlameToneColors(
            fg = palette.inkSecondary,
            onContainer = semantics.onNeutralContainer,
            container = semantics.neutralContainer,
            border = palette.hairline,
            accent = palette.inkSecondary,
        )

        FlameTone.HEALTHY -> FlameToneColors(
            fg = semantics.healthy,
            onContainer = semantics.onHealthyContainer,
            container = semantics.healthyContainer,
            border = semantics.healthy.copy(alpha = 0.28f),
            accent = semantics.healthy,
        )

        FlameTone.WARNING -> FlameToneColors(
            fg = semantics.warning,
            onContainer = semantics.onWarningContainer,
            container = semantics.warningContainer,
            border = semantics.warningBorder,
            accent = semantics.warning,
        )

        FlameTone.DANGER -> FlameToneColors(
            fg = semantics.danger,
            onContainer = semantics.onDangerContainer,
            container = semantics.dangerContainer,
            border = semantics.dangerBorder,
            accent = semantics.danger,
        )

        FlameTone.EMBER -> FlameToneColors(
            fg = palette.ember,
            onContainer = palette.ember,
            container = palette.emberContainer,
            border = palette.emberBorder,
            accent = palette.ember,
        )
    }
}

@Composable
fun FlameTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = FlameThemeTokens.palette
    val layout = LocalFlameWindowLayout.current
    val horizontalPadding = layout.horizontalGutterDp.dp
    val verticalPadding = if (layout.isShortLandscape) 6.dp else 12.dp
    val titleStyle = if (layout.isShortLandscape) {
        MaterialTheme.typography.titleLarge
    } else {
        MaterialTheme.typography.headlineMedium
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (subtitle.isNullOrBlank()) {
            Text(
                text = title,
                style = titleStyle,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (layout.isShortLandscape) 1.dp else 2.dp),
            ) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = if (layout.isShortLandscape) {
                        MaterialTheme.typography.labelSmall
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = palette.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

@Composable
fun FlameTopPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val palette = FlameThemeTokens.palette
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val targetContainer = when {
        !enabled && primary -> palette.monoCtaContainer.copy(alpha = 0.38f)
        !enabled -> palette.surfaceSubtle
        primary -> palette.monoCtaContainer
        else -> palette.surface
    }
    val targetContent = when {
        !enabled && primary -> palette.monoCtaContent.copy(alpha = 0.72f)
        !enabled -> palette.inkTertiary
        primary -> palette.monoCtaContent
        else -> palette.ink
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "flame_top_pill_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = targetContent,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "flame_top_pill_fg",
    )
    val borderStroke = if (primary) null else BorderStroke(1.dp, palette.hairline)

    Surface(
        onClick = {
            haptics.performFlameLightTick()
            onClick()
        },
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier
            .flamePressScale(interactionSource = interactionSource, enabled = enabled)
            .heightIn(min = 36.dp),
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
        border = borderStroke,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun FlameIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showBadge: Boolean = false,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(modifier = modifier) {
        Surface(
            onClick = {
                haptics.performFlameLightTick()
                onClick()
            },
            interactionSource = interactionSource,
            modifier = Modifier
                .flamePressScale(interactionSource = interactionSource)
                .size(38.dp),
            shape = CircleShape,
            color = palette.surface,
            contentColor = palette.ink,
            border = BorderStroke(1.dp, palette.hairline),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        if (showBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 2.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(semantics.danger)
                    .border(1.5.dp, palette.surface, CircleShape),
            )
        }
    }
}

/**
 * 顶部栏全局 1 步直达「问题反馈」快捷入口：
 * - 常规态采用克制的轻量胶囊（微暖火光描边 + 图标 +「反馈」标签），与设置齿轮清晰区分又不抢主操作风头；
 * - 窄屏多按钮场景支持 [compact] 圆形图标态，避免挤压页面标题与主操作胶囊。
 */
@Composable
fun FlameFeedbackTopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val palette = FlameThemeTokens.palette
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    if (compact) {
        Surface(
            onClick = {
                haptics.performFlameLightTick()
                onClick()
            },
            interactionSource = interactionSource,
            modifier = modifier
                .flamePressScale(interactionSource = interactionSource)
                .size(38.dp),
            shape = CircleShape,
            color = palette.emberContainer,
            contentColor = palette.ember,
            border = BorderStroke(1.dp, palette.emberBorder),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Feedback,
                    contentDescription = "问题反馈",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    } else {
        Surface(
            onClick = {
                haptics.performFlameLightTick()
                onClick()
            },
            interactionSource = interactionSource,
            modifier = modifier
                .flamePressScale(interactionSource = interactionSource)
                .heightIn(min = 38.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "问题反馈"
                },
            shape = RoundedCornerShape(999.dp),
            color = palette.emberContainer,
            contentColor = palette.ember,
            border = BorderStroke(1.dp, palette.emberBorder),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Feedback,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "反馈",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
fun FlameSubpageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailingAction: @Composable RowScope.() -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    embeddedInSplitPane: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (!embeddedInSplitPane) {
        BackHandler(onBack = onBack)
    }

    val palette = FlameThemeTokens.palette
    val layout = LocalFlameWindowLayout.current
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = palette.canvas,
        contentColor = palette.ink,
        contentWindowInsets = WindowInsets.navigationBars,
        snackbarHost = {
            if (snackbarHostState != null) {
                SnackbarHost(hostState = snackbarHostState)
            }
        },
        topBar = {
            if (embeddedInSplitPane) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surface),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = layout.horizontalGutterDp.dp,
                                vertical = if (layout.isShortLandscape) 10.dp else 13.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = title,
                            style = if (layout.isShortLandscape) {
                                MaterialTheme.typography.titleMedium
                            } else {
                                MaterialTheme.typography.titleLarge
                            },
                            color = palette.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = trailingAction,
                        )
                    }
                    HorizontalDivider(thickness = 1.dp, color = palette.hairline)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surface)
                        .statusBarsPadding(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
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
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = palette.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = trailingAction,
                        )
                    }
                    HorizontalDivider(thickness = 1.dp, color = palette.hairline)
                }
            }
        },
        content = content,
    )
}

/**
 * 首页顶栏「极简 | 标准」双段滑块胶囊，固定同一坐标系位置实现单点零跳转直切。
 */
@Composable
fun FlameDisplayModeToggle(
    mode: UiDisplayMode,
    onSelectMode: (UiDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val palette = FlameThemeTokens.palette
    val isDark = FlameThemeTokens.isDark
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val reducedMotion = rememberReducedMotion()
    val segWidth = if (compact) 48.dp else 56.dp
    val segHeight = if (compact) 28.dp else 30.dp
    val gapWidth = 2.dp
    val textAnimMs = if (reducedMotion) 0 else 160
    val thumbFraction by animateFloatAsState(
        targetValue = if (mode == UiDisplayMode.SIMPLE) 0f else 1f,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            spring(dampingRatio = 0.86f, stiffness = 520f)
        },
        label = "display_mode_thumb_fraction",
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = palette.surfaceSubtle,
        border = BorderStroke(1.dp, palette.hairline),
    ) {
        Box(modifier = Modifier.padding(3.dp)) {
            Box(
                modifier = Modifier
                    .width(segWidth)
                    .height(segHeight)
                    .graphicsLayer {
                        translationX = thumbFraction * with(density) { (segWidth + gapWidth).toPx() }
                    }
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.surface)
                    .then(
                        if (isDark) {
                            Modifier.border(1.dp, palette.hairlineStrong, RoundedCornerShape(999.dp))
                        } else {
                            Modifier.border(0.5.dp, palette.hairline, RoundedCornerShape(999.dp))
                        },
                    ),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gapWidth),
            ) {
                listOf(
                    UiDisplayMode.SIMPLE to "极简",
                    UiDisplayMode.STANDARD to "标准",
                ).forEach { (option, label) ->
                    val isSelected = mode == option
                    val interaction = remember(option) { MutableInteractionSource() }
                    val fgColor by animateColorAsState(
                        targetValue = if (isSelected) palette.ink else palette.inkTertiary,
                        animationSpec = tween(durationMillis = textAnimMs, easing = FlameMotionSpec.easeOut),
                        label = "display_mode_seg_fg_${option.name}",
                    )

                    Box(
                        modifier = Modifier
                            .width(segWidth)
                            .height(segHeight)
                            .flamePressScale(interactionSource = interaction)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                role = Role.Tab,
                                onClick = {
                                    if (!isSelected) {
                                        haptics.performFlameLightTick()
                                        onSelectMode(option)
                                    }
                                },
                            )
                            .semantics {
                                role = Role.Tab
                                selected = isSelected
                                contentDescription = "切换至${label}模式"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = fgColor,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}
