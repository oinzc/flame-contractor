package io.github.oinzc.flamecontractor.ui.shell

import io.github.oinzc.flamecontractor.core.notify.UrgentCategory
import io.github.oinzc.flamecontractor.core.theme.UiDisplayMode
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationStateTest {

    @Test
    fun `main tab exposes exactly four primary tabs`() {
        assertEquals(
            listOf(MainTab.TODAY, MainTab.TARGETS, MainTab.TEMPLATES, MainTab.HISTORY),
            MainTab.entries,
        )
    }

    @Test
    fun `back unwinds settings detail to hub then to previous main tab`() {
        val inDetail = MainNavState(
            selectedTab = MainTab.HISTORY,
            isSettingsOpen = true,
            activeSettingsSection = SettingsSection.DIAGNOSTICS,
        )
        val (atHub, consumed1) = inDetail.navigateBack()
        assertTrue(consumed1)
        assertEquals(
            MainNavState(MainTab.HISTORY, isSettingsOpen = true, activeSettingsSection = null),
            atHub,
        )

        val (atTab, consumed2) = atHub.navigateBack()
        assertTrue(consumed2)
        assertEquals(
            MainNavState(MainTab.HISTORY, isSettingsOpen = false, activeSettingsSection = null),
            atTab,
        )

        val (unchanged, consumed3) = atTab.navigateBack()
        assertFalse(consumed3)
        assertEquals(atTab, unchanged)
    }

    @Test
    fun `direct feedback entry returns straight to originating main tab on back`() {
        val directFeedback = MainNavState(MainTab.TARGETS)
            .openSettings(section = SettingsSection.ABOUT, returnToTabOnBack = true)
        assertTrue(directFeedback.isSettingsOpen)
        assertEquals(SettingsSection.ABOUT, directFeedback.activeSettingsSection)
        assertTrue(directFeedback.returnToTabOnBack)

        val (returned, consumed) = directFeedback.navigateBack()
        assertTrue(consumed)
        assertEquals(
            MainNavState(
                selectedTab = MainTab.TARGETS,
                isSettingsOpen = false,
                activeSettingsSection = null,
                returnToTabOnBack = false,
            ),
            returned,
        )
    }

    @Test
    fun `updates section is independent from feedback section and unwinds to settings hub`() {
        val inUpdates = MainNavState(MainTab.TODAY)
            .openSettings(section = SettingsSection.UPDATES)
        assertTrue(inUpdates.isSettingsOpen)
        assertEquals(SettingsSection.UPDATES, inUpdates.activeSettingsSection)
        assertFalse(inUpdates.returnToTabOnBack)

        val (backToHub, consumed) = inUpdates.navigateBack()
        assertTrue(consumed)
        assertEquals(
            MainNavState(MainTab.TODAY, isSettingsOpen = true, activeSettingsSection = null),
            backToHub,
        )
    }

    @Test
    fun `reopening settings starts at hub unless section specified`() {
        val opened = MainNavState(MainTab.TARGETS)
            .openSettings(SettingsSection.SCHEDULE)
        assertTrue(opened.isSettingsOpen)
        assertEquals(SettingsSection.SCHEDULE, opened.activeSettingsSection)

        val afterTabSwitch = opened.selectTab(MainTab.TEMPLATES)
        assertFalse(afterTabSwitch.isSettingsOpen)
        assertNull(afterTabSwitch.activeSettingsSection)
        assertEquals(MainTab.TEMPLATES, afterTabSwitch.selectedTab)

        val reopenedHub = afterTabSwitch.openSettings()
        assertTrue(reopenedHub.isSettingsOpen)
        assertNull(reopenedHub.activeSettingsSection)
        assertEquals(MainTab.TEMPLATES, reopenedHub.selectedTab)
    }

    @Test
    fun `urgent launch intent closes nested settings overlay and routes correctly`() {
        val nested = MainNavState(
            selectedTab = MainTab.TEMPLATES,
            isSettingsOpen = true,
            activeSettingsSection = SettingsSection.BACKUP,
        )

        val spark = nested.applyLaunchIntent(
            category = UrgentCategory.SPARK_DETERIORATING.name,
            hasAccountTarget = true,
        )
        assertEquals(
            MainNavState(MainTab.TARGETS, isSettingsOpen = false, activeSettingsSection = null),
            spark,
        )

        val auth = nested.applyLaunchIntent(
            category = UrgentCategory.AUTH_EXPIRED.name,
            hasAccountTarget = true,
        )
        assertEquals(
            MainNavState(MainTab.TODAY, isSettingsOpen = false, activeSettingsSection = null),
            auth,
        )

        val accountOnly = nested.applyLaunchIntent(
            category = null,
            hasAccountTarget = true,
        )
        assertEquals(
            MainNavState(MainTab.TODAY, isSettingsOpen = false, activeSettingsSection = null),
            accountOnly,
        )

        val empty = nested.applyLaunchIntent(category = null, hasAccountTarget = false)
        assertEquals(nested, empty)
    }

    @Test
    fun `shortcut launch categories route to matching tabs and dismiss settings overlay`() {
        val nested = MainNavState(
            selectedTab = MainTab.TODAY,
            isSettingsOpen = true,
            activeSettingsSection = SettingsSection.SCHEDULE,
        )

        assertEquals(
            MainNavState(MainTab.TODAY, isSettingsOpen = false, activeSettingsSection = null),
            nested.applyLaunchIntent(category = MainNavState.SHORTCUT_TODAY, hasAccountTarget = false),
        )
        assertEquals(
            MainNavState(MainTab.TARGETS, isSettingsOpen = false, activeSettingsSection = null),
            nested.applyLaunchIntent(category = MainNavState.SHORTCUT_TARGETS, hasAccountTarget = false),
        )
        assertEquals(
            MainNavState(MainTab.TEMPLATES, isSettingsOpen = false, activeSettingsSection = null),
            nested.applyLaunchIntent(category = MainNavState.SHORTCUT_TEMPLATES, hasAccountTarget = false),
        )
        assertEquals(
            MainNavState(MainTab.HISTORY, isSettingsOpen = false, activeSettingsSection = null),
            nested.applyLaunchIntent(category = MainNavState.SHORTCUT_HISTORY, hasAccountTarget = false),
        )
        assertEquals(
            MainNavState(MainTab.TARGETS, isSettingsOpen = false, activeSettingsSection = null),
            nested.applyLaunchIntent(category = "  shortcut_targets  ", hasAccountTarget = true),
        )
    }

    @Test
    fun `account delete notice distinguishes single account default account and extra profile`() {
        assertFalse(buildAccountDeleteNotice(DEFAULT_ACCOUNT_ID, totalAccountCount = 1).canDelete)
        assertFalse(buildAccountDeleteNotice("acc_2", totalAccountCount = 1).canDelete)

        val defaultNotice = buildAccountDeleteNotice(DEFAULT_ACCOUNT_ID, totalAccountCount = 2)
        assertTrue(defaultNotice.canDelete)
        assertTrue(
            defaultNotice.detailMessage.contains("默认") ||
                defaultNotice.detailMessage.contains("Cookie"),
        )
        assertFalse(defaultNotice.detailMessage.contains("冷启动彻底销毁"))

        val secondaryNotice = buildAccountDeleteNotice("acc_2", totalAccountCount = 2)
        assertTrue(secondaryNotice.canDelete)
        assertTrue(secondaryNotice.detailMessage.contains("冷启动"))
    }

    @Test
    fun `simple mode back navigation unwinds settings then returns from targets to today`() {
        val inSettingsFromTargets = MainNavState(
            selectedTab = MainTab.TARGETS,
            isSettingsOpen = true,
            activeSettingsSection = SettingsSection.APPEARANCE,
        )

        val (atHub, consumed1) = inSettingsFromTargets.navigateBack(UiDisplayMode.SIMPLE)
        assertTrue(consumed1)
        assertEquals(MainNavState(MainTab.TARGETS, isSettingsOpen = true, activeSettingsSection = null), atHub)

        val (atTargets, consumed2) = atHub.navigateBack(UiDisplayMode.SIMPLE)
        assertTrue(consumed2)
        assertEquals(MainNavState(MainTab.TARGETS, isSettingsOpen = false, activeSettingsSection = null), atTargets)

        val (atToday, consumed3) = atTargets.navigateBack(UiDisplayMode.SIMPLE)
        assertTrue(consumed3)
        assertEquals(MainNavState(MainTab.TODAY, isSettingsOpen = false, activeSettingsSection = null), atToday)

        val (unchanged, consumed4) = atToday.navigateBack(UiDisplayMode.SIMPLE)
        assertFalse(consumed4)
        assertEquals(atToday, unchanged)
    }

    @Test
    fun `simple mode converges hidden tabs and launch intents safely to today or targets`() {
        val switchedInSettings = MainNavState(
            selectedTab = MainTab.HISTORY,
            isSettingsOpen = true,
            activeSettingsSection = SettingsSection.APPEARANCE,
        ).convergeForSimpleMode()
        assertEquals(MainTab.TODAY, switchedInSettings.selectedTab)
        assertTrue(switchedInSettings.isSettingsOpen)
        assertEquals(SettingsSection.APPEARANCE, switchedInSettings.activeSettingsSection)

        val base = MainNavState(MainTab.TODAY)
        assertEquals(MainTab.TARGETS, base.selectTab(MainTab.TARGETS, UiDisplayMode.SIMPLE).selectedTab)
        assertEquals(MainTab.TODAY, base.selectTab(MainTab.TEMPLATES, UiDisplayMode.SIMPLE).selectedTab)
        assertEquals(MainTab.TODAY, base.selectTab(MainTab.HISTORY, UiDisplayMode.SIMPLE).selectedTab)

        assertEquals(
            MainNavState(MainTab.TARGETS),
            base.applyLaunchIntent(MainNavState.SHORTCUT_TARGETS, false, UiDisplayMode.SIMPLE),
        )
        assertEquals(
            MainNavState(MainTab.TARGETS),
            base.applyLaunchIntent(UrgentCategory.SPARK_DETERIORATING.name, true, UiDisplayMode.SIMPLE),
        )
        assertEquals(
            MainNavState(MainTab.TODAY),
            base.applyLaunchIntent(MainNavState.SHORTCUT_TEMPLATES, false, UiDisplayMode.SIMPLE),
        )
        assertEquals(
            MainNavState(MainTab.TODAY),
            base.applyLaunchIntent(MainNavState.SHORTCUT_HISTORY, false, UiDisplayMode.SIMPLE),
        )
    }
}
