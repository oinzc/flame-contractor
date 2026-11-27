package io.github.oinzc.flamecontractor.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlameAdaptiveLayoutTest {

    private data class BoundaryCase(
        val widthDp: Int,
        val heightDp: Int,
        val formFactor: FlameFormFactor,
        val navChromeKind: FlameNavChromeKind,
        val globalActionsInNavChrome: Boolean,
        val useCenterDialogForSheets: Boolean,
        val hasBottomFloatingDock: Boolean,
        val isShortLandscape: Boolean,
        val supportsEmbeddedSettingsPane: Boolean,
        val horizontalGutterDp: Int,
    )

    @Test
    fun `resolveFlameWindowLayout covers all 11 spec boundary sizes accurately`() {
        val cases = listOf(
            // 手机竖屏与窄分屏 (width < 600)
            BoundaryCase(
                widthDp = 360,
                heightDp = 800,
                formFactor = FlameFormFactor.COMPACT_PORTRAIT,
                navChromeKind = FlameNavChromeKind.BOTTOM_DOCK,
                globalActionsInNavChrome = false,
                useCenterDialogForSheets = false,
                hasBottomFloatingDock = true,
                isShortLandscape = false,
                supportsEmbeddedSettingsPane = false,
                horizontalGutterDp = 16,
            ),
            BoundaryCase(
                widthDp = 599,
                heightDp = 900,
                formFactor = FlameFormFactor.COMPACT_PORTRAIT,
                navChromeKind = FlameNavChromeKind.BOTTOM_DOCK,
                globalActionsInNavChrome = false,
                useCenterDialogForSheets = false,
                hasBottomFloatingDock = true,
                isShortLandscape = false,
                supportsEmbeddedSettingsPane = false,
                horizontalGutterDp = 16,
            ),
            // 手机横屏与矮小窗 (width >= 600 && height < 480)
            BoundaryCase(
                widthDp = 600,
                heightDp = 390,
                formFactor = FlameFormFactor.COMPACT_HEIGHT_LANDSCAPE,
                navChromeKind = FlameNavChromeKind.COMPACT_RAIL,
                globalActionsInNavChrome = true,
                useCenterDialogForSheets = true,
                hasBottomFloatingDock = false,
                isShortLandscape = true,
                supportsEmbeddedSettingsPane = true,
                horizontalGutterDp = 16,
            ),
            BoundaryCase(
                widthDp = 873,
                heightDp = 393,
                formFactor = FlameFormFactor.COMPACT_HEIGHT_LANDSCAPE,
                navChromeKind = FlameNavChromeKind.COMPACT_RAIL,
                globalActionsInNavChrome = true,
                useCenterDialogForSheets = true,
                hasBottomFloatingDock = false,
                isShortLandscape = true,
                supportsEmbeddedSettingsPane = true,
                horizontalGutterDp = 16,
            ),
            BoundaryCase(
                widthDp = 960,
                heightDp = 479,
                formFactor = FlameFormFactor.COMPACT_HEIGHT_LANDSCAPE,
                navChromeKind = FlameNavChromeKind.COMPACT_RAIL,
                globalActionsInNavChrome = true,
                useCenterDialogForSheets = true,
                hasBottomFloatingDock = false,
                isShortLandscape = true,
                supportsEmbeddedSettingsPane = true,
                horizontalGutterDp = 16,
            ),
            // 平板竖屏 / 中等分屏 (600 <= width < 960 && height >= 480)
            BoundaryCase(
                widthDp = 600,
                heightDp = 960,
                formFactor = FlameFormFactor.TABLET_PORTRAIT,
                navChromeKind = FlameNavChromeKind.NAVIGATION_RAIL,
                globalActionsInNavChrome = true,
                useCenterDialogForSheets = true,
                hasBottomFloatingDock = false,
                isShortLandscape = false,
                supportsEmbeddedSettingsPane = false,
                horizontalGutterDp = 20,
            ),
            BoundaryCase(
                widthDp = 711,
                heightDp = 1138,
                formFactor = FlameFormFactor.TABLET_PORTRAIT,
                navChromeKind = FlameNavChromeKind.NAVIGATION_RAIL,
                globalActionsInNavChrome = true,
                useCenterDialogForSheets = true,
                hasBottomFloatingDock = false,
                isShortLandscape = false,
                supportsEmbeddedSettingsPane = false,
                horizontalGutterDp = 20,
            ),
            BoundaryCase(
                widthDp = 959,
                heightDp = 1138,
                formFactor = FlameFormFactor.TABLET_PORTRAIT,
                navChromeKind = FlameNavChromeKind.NAVIGATION_RAIL,
                globalActionsInNavChrome = true,
                useCenterDialogForSheets = true,
                hasBottomFloatingDock = false,
                isShortLandscape = false,
                supportsEmbeddedSettingsPane = false,
                horizontalGutterDp = 20,
            ),
            // 平板横屏 / 桌面宽窗 (width >= 960 && height >= 480)
            BoundaryCase(
                widthDp = 960,
                heightDp = 480,
                formFactor = FlameFormFactor.TABLET_LANDSCAPE,
                navChromeKind = FlameNavChromeKind.PERMANENT_SIDEBAR,
                globalActionsInNavChrome = true,
                useCenterDialogForSheets = true,
                hasBottomFloatingDock = false,
                isShortLandscape = false,
                supportsEmbeddedSettingsPane = true,
                horizontalGutterDp = 20,
            ),
            BoundaryCase(
                widthDp = 1138,
                heightDp = 711,
                formFactor = FlameFormFactor.TABLET_LANDSCAPE,
                navChromeKind = FlameNavChromeKind.PERMANENT_SIDEBAR,
                globalActionsInNavChrome = true,
                useCenterDialogForSheets = true,
                hasBottomFloatingDock = false,
                isShortLandscape = false,
                supportsEmbeddedSettingsPane = true,
                horizontalGutterDp = 20,
            ),
            BoundaryCase(
                widthDp = 1280,
                heightDp = 800,
                formFactor = FlameFormFactor.TABLET_LANDSCAPE,
                navChromeKind = FlameNavChromeKind.PERMANENT_SIDEBAR,
                globalActionsInNavChrome = true,
                useCenterDialogForSheets = true,
                hasBottomFloatingDock = false,
                isShortLandscape = false,
                supportsEmbeddedSettingsPane = true,
                horizontalGutterDp = 20,
            ),
        )

        assertEquals(11, cases.size)
        cases.forEach { expected ->
            val actual = resolveFlameWindowLayout(expected.widthDp, expected.heightDp)
            val label = "${expected.widthDp}x${expected.heightDp}"
            assertEquals("windowWidthDp for $label", expected.widthDp, actual.windowWidthDp)
            assertEquals("windowHeightDp for $label", expected.heightDp, actual.windowHeightDp)
            assertEquals("formFactor for $label", expected.formFactor, actual.formFactor)
            assertEquals("navChromeKind for $label", expected.navChromeKind, actual.navChromeKind)
            assertEquals(
                "globalActionsInNavChrome for $label",
                expected.globalActionsInNavChrome,
                actual.globalActionsInNavChrome,
            )
            assertEquals(
                "useCenterDialogForSheets for $label",
                expected.useCenterDialogForSheets,
                actual.useCenterDialogForSheets,
            )
            assertEquals(
                "hasBottomFloatingDock for $label",
                expected.hasBottomFloatingDock,
                actual.hasBottomFloatingDock,
            )
            assertEquals(
                "isShortLandscape for $label",
                expected.isShortLandscape,
                actual.isShortLandscape,
            )
            assertEquals(
                "supportsEmbeddedSettingsPane for $label",
                expected.supportsEmbeddedSettingsPane,
                actual.supportsEmbeddedSettingsPane,
            )
            assertEquals(
                "horizontalGutterDp for $label",
                expected.horizontalGutterDp,
                actual.horizontalGutterDp,
            )
        }
    }

    @Test
    fun `usesSplitSettingsPane respects form factor and 680dp content width gate`() {
        val phonePortrait = resolveFlameWindowLayout(360, 800)
        assertFalse(phonePortrait.usesSplitSettingsPane(hasOuterNavChrome = false))

        val tabletPortrait = resolveFlameWindowLayout(711, 1138)
        assertFalse(tabletPortrait.usesSplitSettingsPane(hasOuterNavChrome = false))
        assertFalse(tabletPortrait.usesSplitSettingsPane(hasOuterNavChrome = true))

        val narrowPhoneLandscape = resolveFlameWindowLayout(600, 390)
        assertFalse(narrowPhoneLandscape.usesSplitSettingsPane(hasOuterNavChrome = true))
        assertFalse(narrowPhoneLandscape.usesSplitSettingsPane(hasOuterNavChrome = false))

        val standardPhoneLandscape = resolveFlameWindowLayout(873, 393)
        assertTrue(standardPhoneLandscape.usesSplitSettingsPane(hasOuterNavChrome = true))
        assertTrue(standardPhoneLandscape.usesSplitSettingsPane(hasOuterNavChrome = false))

        val tabletLandscapeMin = resolveFlameWindowLayout(960, 480)
        assertTrue(tabletLandscapeMin.usesSplitSettingsPane(hasOuterNavChrome = true))
        assertTrue(tabletLandscapeMin.usesSplitSettingsPane(hasOuterNavChrome = false))
    }

    @Test
    fun `resolveFlameWindowLayout clamps negative dimensions safely`() {
        val clamped = resolveFlameWindowLayout(-100, -50)
        assertEquals(0, clamped.windowWidthDp)
        assertEquals(0, clamped.windowHeightDp)
        assertEquals(FlameFormFactor.COMPACT_PORTRAIT, clamped.formFactor)
        assertEquals(FlameNavChromeKind.BOTTOM_DOCK, clamped.navChromeKind)
    }
}
