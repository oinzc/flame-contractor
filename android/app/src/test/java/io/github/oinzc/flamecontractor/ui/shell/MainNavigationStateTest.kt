package io.github.oinzc.flamecontractor.ui.shell

import io.github.oinzc.flamecontractor.core.notify.UrgentCategory
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
}
