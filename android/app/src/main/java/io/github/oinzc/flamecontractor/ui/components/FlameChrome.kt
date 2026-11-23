package io.github.oinzc.flamecontractor.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
            onContainer = if (FlameThemeTokens.isDynamicColorActive) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                palette.ember
            },
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
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = FlameThemeTokens.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 36.dp),
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
    Box(modifier = modifier) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(38.dp),
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

@Composable
fun FlameSubpageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailingAction: @Composable RowScope.() -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    BackHandler(onBack = onBack)

    val palette = FlameThemeTokens.palette
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
        },
        content = content,
    )
}
