package io.github.oinzc.flamecontractor.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlameMotionTest {

    @Test
    fun `reduced motion scale detects zero and negative safely`() {
        assertTrue(isReducedMotionAnimatorScale(0f))
        assertTrue(isReducedMotionAnimatorScale(-0.5f))
        assertFalse(isReducedMotionAnimatorScale(0.5f))
        assertFalse(isReducedMotionAnimatorScale(1f))
        assertFalse(isReducedMotionAnimatorScale(Float.NaN))
        assertFalse(isReducedMotionAnimatorScale(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `motion timing tokens stay strictly under 300ms and scale never starts at zero`() {
        assertTrue(FlameMotionSpec.TAB_ENTER_MS < 300)
        assertTrue(FlameMotionSpec.TAB_EXIT_MS < 300)
        assertTrue(FlameMotionSpec.SUBPAGE_ENTER_MS < 300)
        assertTrue(FlameMotionSpec.SUBPAGE_EXIT_MS < 300)
        assertTrue(FlameMotionSpec.COLLAPSE_ENTER_MS < 300)
        assertTrue(FlameMotionSpec.COLLAPSE_EXIT_MS < 300)
        assertTrue(FlameMotionSpec.CHEVRON_ROTATE_MS < 300)
        assertTrue(FlameMotionSpec.ICON_SPIN_MS < 300)
        assertTrue(FlameMotionSpec.TAB_INITIAL_SCALE > 0.9f)
        assertTrue(FlameMotionSpec.PRESS_SCALE_DEFAULT > 0.9f)
        assertTrue(FlameMotionSpec.PRESS_SCALE_HERO > 0.9f)
    }
}
