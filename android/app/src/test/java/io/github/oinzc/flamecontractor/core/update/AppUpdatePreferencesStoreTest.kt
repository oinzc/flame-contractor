package io.github.oinzc.flamecontractor.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePreferencesStoreTest {

    @Test
    fun `defaults enable auto check and allow immediate first run`() {
        val store = AppUpdatePreferencesStore()
        val prefs = store.preferences.value
        assertTrue(prefs.autoCheckEnabled)
        assertEquals(0L, prefs.lastAutoCheckAtEpochMs)
        assertEquals(0L, prefs.ignoredVersionCode)
        assertNull(prefs.lastKnownLatestVersionName)
        assertEquals(0L, prefs.lastKnownLatestVersionCode)
        assertTrue(store.shouldRunAutoCheck(nowEpochMs = 1_700_000_000_000L))
    }

    @Test
    fun `shouldRunAutoCheck enforces 6-hour throttle window toggle and clock rollback`() {
        val persisted = mutableListOf<AppUpdatePreferences>()
        val store = AppUpdatePreferencesStore(onPersist = { persisted.add(it) })
        val baseTime = 1_700_000_000_000L
        val sixHours = AppUpdatePreferencesStore.DEFAULT_AUTO_CHECK_INTERVAL_MS
        assertEquals(6L * 60L * 60L * 1000L, sixHours)

        store.recordCheckAttempt(baseTime)
        assertFalse(store.shouldRunAutoCheck(baseTime))
        assertFalse(store.shouldRunAutoCheck(baseTime + sixHours - 1L))
        assertTrue(store.shouldRunAutoCheck(baseTime + sixHours))
        assertTrue(store.shouldRunAutoCheck(baseTime + sixHours + 60_000L))

        // 系统时钟回拨不永久卡死节流
        assertTrue(store.shouldRunAutoCheck(baseTime - 1_000L))

        // 关闭自动检查开关后即使超过 6 小时也不自动检查
        store.setAutoCheckEnabled(false)
        assertFalse(store.shouldRunAutoCheck(baseTime + sixHours * 10))

        // 重新开启后立刻恢复判断
        store.setAutoCheckEnabled(true)
        assertTrue(store.shouldRunAutoCheck(baseTime + sixHours))

        assertTrue(persisted.isNotEmpty())
        assertTrue(persisted.last().autoCheckEnabled)
    }

    @Test
    fun `ignoredVersionCode and knownLatestRelease updates persist cleanly`() {
        var latestSaved: AppUpdatePreferences? = null
        val store = AppUpdatePreferencesStore(onPersist = { latestSaved = it })

        store.setIgnoredVersionCode(601L)
        assertEquals(601L, store.preferences.value.ignoredVersionCode)
        assertEquals(601L, latestSaved?.ignoredVersionCode)

        store.recordKnownLatestRelease("  0.6.1  ", 601L)
        assertEquals("0.6.1", store.preferences.value.lastKnownLatestVersionName)
        assertEquals(601L, store.preferences.value.lastKnownLatestVersionCode)

        store.clearIgnoredVersion()
        assertEquals(0L, store.preferences.value.ignoredVersionCode)
    }
}
