package io.github.oinzc.flamecontractor.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Flame Contractor 统一微动效节奏与触感规格：
 * - 所有 UI 过渡严格 < 300ms，采用响应迅速的 ease-out 或可打断 spring；
 * - 仅驱动 transform (graphicsLayer) 与 opacity；
 * - 全链路响应系统「移除动画 / 减弱动效」(ANIMATOR_DURATION_SCALE == 0)。
 */
object FlameMotionSpec {
    const val TAB_ENTER_MS: Int = 180
    const val TAB_EXIT_MS: Int = 130
    const val TAB_INITIAL_SCALE: Float = 0.985f

    const val SUBPAGE_ENTER_MS: Int = 220
    const val SUBPAGE_EXIT_MS: Int = 180
    const val SUBPAGE_SLIDE_FRACTION: Int = 12 // ~8.3% 宽度轻位移，清晰表达父子层级而不拖沓

    const val COLLAPSE_ENTER_MS: Int = 200
    const val COLLAPSE_EXIT_MS: Int = 170
    const val CHEVRON_ROTATE_MS: Int = 200
    const val ICON_SPIN_MS: Int = 280

    const val PRESS_SCALE_DEFAULT: Float = 0.96f
    const val PRESS_SCALE_HERO: Float = 0.94f
    const val TAB_SELECTED_ICON_SCALE: Float = 1.06f

    val easeOut: Easing = LinearOutSlowInEasing
    val standardEasing: Easing = FastOutSlowInEasing

    fun pressSpringSpec() = spring<Float>(
        dampingRatio = 0.72f,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun tabIconSpringSpec() = spring<Float>(
        dampingRatio = 0.92f,
        stiffness = Spring.StiffnessMedium,
    )
}

val LocalFlameReducedMotion = staticCompositionLocalOf<Boolean?> { null }

internal fun isReducedMotionAnimatorScale(scale: Float): Boolean =
    scale.isFinite() && scale <= 0f

fun readSystemReducedMotion(context: Context): Boolean = runCatching {
    val scale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    isReducedMotionAnimatorScale(scale)
}.getOrDefault(false)

/**
 * 读取系统是否关闭动画（开发者选项或无障碍「移除动画」），并在页面恢复前台时同步最新值。
 */
@Composable
fun rememberReducedMotion(): Boolean {
    LocalFlameReducedMotion.current?.let { return it }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var reduced by remember(context) { mutableStateOf(readSystemReducedMotion(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reduced = readSystemReducedMotion(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return reduced
}

/**
 * 可打断的轻量按压缩放反馈（仅操作 GPU graphicsLayer，零布局重排），减弱动效时自动退化为无缩放。
 */
@Composable
fun Modifier.flamePressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = FlameMotionSpec.PRESS_SCALE_DEFAULT,
    enabled: Boolean = true,
): Modifier {
    val reducedMotion = rememberReducedMotion()
    if (reducedMotion) return this

    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && isPressed) pressedScale else 1f,
        animationSpec = FlameMotionSpec.pressSpringSpec(),
        label = "flame_press_scale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 轻触震动反馈（按钮、Tab 切换、展开收起、刷新操作），自动遵循系统触控反馈总开关。
 */
fun HapticFeedback.performFlameLightTick() {
    performHapticFeedback(HapticFeedbackType.TextHandleMove)
}
