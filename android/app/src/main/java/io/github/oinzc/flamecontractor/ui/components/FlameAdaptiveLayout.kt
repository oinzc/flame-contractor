package io.github.oinzc.flamecontractor.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 标准内容区双栏门禁常量（以页面内 `BoxWithConstraints` 实际可用 `maxWidth` 为准）：
 * - 今日 / 好友 / 记录：`>= 700.dp`
 * - 模板画廊 + 实时预览：`>= 760.dp`
 * - 设置中心 Master-Detail：`>= 680.dp`
 */
val FLAME_TWO_PANE_CONTENT_MIN_WIDTH: Dp = 700.dp
val FLAME_TEMPLATES_SPLIT_MIN_WIDTH: Dp = 760.dp
val FLAME_SETTINGS_SPLIT_MIN_WIDTH: Dp = 680.dp

const val FLAME_COMPACT_RAIL_WIDTH_DP: Int = 62
const val FLAME_NAVIGATION_RAIL_WIDTH_DP: Int = 76
const val FLAME_PERMANENT_SIDEBAR_WIDTH_DP: Int = 216

private const val COMPACT_WIDTH_BREAKPOINT_DP: Int = 600
private const val SHORT_HEIGHT_BREAKPOINT_DP: Int = 480
private const val TABLET_LANDSCAPE_WIDTH_BREAKPOINT_DP: Int = 960
private const val SETTINGS_SPLIT_MIN_WIDTH_DP: Int = 680

enum class FlameFormFactor {
    /** 手机竖屏或平板极窄分屏：`width < 600.dp` */
    COMPACT_PORTRAIT,

    /** 手机横屏或极矮小窗：`width >= 600.dp && height < 480.dp` */
    COMPACT_HEIGHT_LANDSCAPE,

    /** 平板竖屏 / 展开折叠屏 / 中等分屏：`600.dp <= width < 960.dp && height >= 480.dp` */
    TABLET_PORTRAIT,

    /** 平板横屏 / 桌面宽窗：`width >= 960.dp && height >= 480.dp` */
    TABLET_LANDSCAPE,
}

enum class FlameNavChromeKind {
    /** `COMPACT_PORTRAIT`：底部悬浮圆角胶囊底栏 */
    BOTTOM_DOCK,

    /** `COMPACT_HEIGHT_LANDSCAPE`：62.dp 可垂直滚动纯图标 Rail */
    COMPACT_RAIL,

    /** `TABLET_PORTRAIT`：76.dp 精致 Rail */
    NAVIGATION_RAIL,

    /** `TABLET_LANDSCAPE`：216.dp 常驻展开侧边栏 */
    PERMANENT_SIDEBAR,
}

@Immutable
data class FlameWindowLayout(
    val windowWidthDp: Int,
    val windowHeightDp: Int,
    val formFactor: FlameFormFactor,
    val navChromeKind: FlameNavChromeKind,
    val globalActionsInNavChrome: Boolean,
    val useCenterDialogForSheets: Boolean,
    val horizontalGutterDp: Int,
) {
    /** 是否使用底部悬浮胶囊底栏（用于消除平板与横屏下的假底栏留白） */
    val hasBottomFloatingDock: Boolean
        get() = navChromeKind == FlameNavChromeKind.BOTTOM_DOCK

    /** 是否为手机横屏/矮小窗形态 */
    val isShortLandscape: Boolean
        get() = formFactor == FlameFormFactor.COMPACT_HEIGHT_LANDSCAPE

    /** 标准模式下打开设置时是否保留左侧导航壳层、直接在右侧主内容区嵌入设置页 */
    val supportsEmbeddedSettingsPane: Boolean
        get() = navChromeKind == FlameNavChromeKind.PERMANENT_SIDEBAR ||
            navChromeKind == FlameNavChromeKind.COMPACT_RAIL

    val horizontalGutter: Dp
        get() = horizontalGutterDp.dp

    val navChromeWidthDp: Int
        get() = when (navChromeKind) {
            FlameNavChromeKind.BOTTOM_DOCK -> 0
            FlameNavChromeKind.COMPACT_RAIL -> FLAME_COMPACT_RAIL_WIDTH_DP
            FlameNavChromeKind.NAVIGATION_RAIL -> FLAME_NAVIGATION_RAIL_WIDTH_DP
            FlameNavChromeKind.PERMANENT_SIDEBAR -> FLAME_PERMANENT_SIDEBAR_WIDTH_DP
        }

    /**
     * 判断当前窗口下设置页是否处于 Master-Detail 双栏模式：
     * - 仅在横屏形态（[supportsEmbeddedSettingsPane] 为 true）且设置内容区实际宽度 `>= 680.dp` 时启用双栏；
     * - 手机竖屏、平板竖屏及极窄手机横屏（扣除 62dp Rail 后不足 680dp）统一回退单栏两级导航。
     */
    fun usesSplitSettingsPane(hasOuterNavChrome: Boolean): Boolean {
        if (!supportsEmbeddedSettingsPane) return false
        val chromeReservation = if (hasOuterNavChrome && navChromeWidthDp > 0) {
            navChromeWidthDp + 1
        } else {
            0
        }
        val availableWidthDp = (windowWidthDp - chromeReservation).coerceAtLeast(0)
        return availableWidthDp >= SETTINGS_SPLIT_MIN_WIDTH_DP
    }
}

/**
 * 纯函数：根据实际可用窗口宽高（dp）计算四档自适应布局信息。
 */
fun resolveFlameWindowLayout(
    widthDp: Int,
    heightDp: Int,
): FlameWindowLayout {
    val safeWidth = widthDp.coerceAtLeast(0)
    val safeHeight = heightDp.coerceAtLeast(0)
    return when {
        safeWidth < COMPACT_WIDTH_BREAKPOINT_DP -> FlameWindowLayout(
            windowWidthDp = safeWidth,
            windowHeightDp = safeHeight,
            formFactor = FlameFormFactor.COMPACT_PORTRAIT,
            navChromeKind = FlameNavChromeKind.BOTTOM_DOCK,
            globalActionsInNavChrome = false,
            useCenterDialogForSheets = false,
            horizontalGutterDp = 16,
        )

        safeHeight < SHORT_HEIGHT_BREAKPOINT_DP -> FlameWindowLayout(
            windowWidthDp = safeWidth,
            windowHeightDp = safeHeight,
            formFactor = FlameFormFactor.COMPACT_HEIGHT_LANDSCAPE,
            navChromeKind = FlameNavChromeKind.COMPACT_RAIL,
            globalActionsInNavChrome = true,
            useCenterDialogForSheets = true,
            horizontalGutterDp = 16,
        )

        safeWidth < TABLET_LANDSCAPE_WIDTH_BREAKPOINT_DP -> FlameWindowLayout(
            windowWidthDp = safeWidth,
            windowHeightDp = safeHeight,
            formFactor = FlameFormFactor.TABLET_PORTRAIT,
            navChromeKind = FlameNavChromeKind.NAVIGATION_RAIL,
            globalActionsInNavChrome = true,
            useCenterDialogForSheets = true,
            horizontalGutterDp = 20,
        )

        else -> FlameWindowLayout(
            windowWidthDp = safeWidth,
            windowHeightDp = safeHeight,
            formFactor = FlameFormFactor.TABLET_LANDSCAPE,
            navChromeKind = FlameNavChromeKind.PERMANENT_SIDEBAR,
            globalActionsInNavChrome = true,
            useCenterDialogForSheets = true,
            horizontalGutterDp = 20,
        )
    }
}

/**
 * 当前窗口的自适应布局形态，默认回退至标准手机竖屏（411×915 dp）。
 */
val LocalFlameWindowLayout = compositionLocalOf {
    resolveFlameWindowLayout(widthDp = 411, heightDp = 915)
}

/**
 * 指示外层导航壳层（侧边栏 / Rail）是否已承接全局入口（账号切换、问题反馈、设置）。
 * 为 `true` 时，业务页面顶栏应隐藏重复的全局按钮，仅保留标题与本页专属操作。
 */
val LocalFlameChromeHandlesGlobalNav = compositionLocalOf { false }

/**
 * 在顶层容器使用 [BoxWithConstraints] 实时测量窗口实际可用宽高并向下注入 [LocalFlameWindowLayout]，
 * 自动适配屏幕旋转、平板分屏与自由多窗口。
 */
@Composable
fun ProvideFlameWindowLayout(
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    BoxWithConstraints(modifier = modifier) {
        val widthDp = if (constraints.hasBoundedWidth) {
            maxWidth.value.roundToInt().coerceAtLeast(0)
        } else {
            configuration.screenWidthDp.coerceAtLeast(0)
        }
        val heightDp = if (constraints.hasBoundedHeight) {
            maxHeight.value.roundToInt().coerceAtLeast(0)
        } else {
            configuration.screenHeightDp.coerceAtLeast(0)
        }
        val layout = remember(widthDp, heightDp) {
            resolveFlameWindowLayout(widthDp = widthDp, heightDp = heightDp)
        }
        CompositionLocalProvider(LocalFlameWindowLayout provides layout) {
            content()
        }
    }
}

/**
 * 统一的页面底部内容安全留白：
 * - 有底部悬浮胶囊底栏时返回 [flameDockBottomPadding]；
 * - 无底栏（平板与手机横屏）时仅保留系统导航条高度 + `16.dp`，避免多余空白。
 */
@Composable
fun flameContentBottomPadding(
    hasBottomDock: Boolean = LocalFlameWindowLayout.current.hasBottomFloatingDock,
): Dp = if (hasBottomDock) {
    flameDockBottomPadding()
} else {
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
}
