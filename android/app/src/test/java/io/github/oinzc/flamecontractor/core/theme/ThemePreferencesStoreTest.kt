package io.github.oinzc.flamecontractor.core.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePreferencesStoreTest {

    @Test
    fun `invalid storage value falls back to SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageValue(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageValue(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageValue("BROKEN_VALUE"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorageValue("DARK"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStorageValue("light"))
    }

    @Test
    fun `updates persist and emit new state`() {
        val persisted = mutableListOf<ThemePreference>()
        val store = ThemePreferencesStore(onPersist = { persisted += it })

        store.setThemeMode(ThemeMode.DARK)
        store.setDynamicColorEnabled(true)

        assertEquals(ThemePreference(ThemeMode.DARK, true), store.preference.value)
        assertEquals(2, persisted.size)
        assertEquals(ThemePreference(ThemeMode.DARK, true), persisted.last())
    }

    @Test
    fun `resolveIsDark respects explicit override and system fallback`() {
        assertTrue(ThemePreferencesStore.resolveIsDark(ThemeMode.DARK, systemDark = false))
        assertFalse(ThemePreferencesStore.resolveIsDark(ThemeMode.LIGHT, systemDark = true))
        assertTrue(ThemePreferencesStore.resolveIsDark(ThemeMode.SYSTEM, systemDark = true))
        assertFalse(ThemePreferencesStore.resolveIsDark(ThemeMode.SYSTEM, systemDark = false))
    }

    @Test
    fun `resolveEffectiveDynamicColor disables below Android 12`() {
        assertFalse(ThemePreferencesStore.resolveEffectiveDynamicColor(enabled = true, sdkInt = 30))
        assertTrue(ThemePreferencesStore.resolveEffectiveDynamicColor(enabled = true, sdkInt = 31))
        assertFalse(ThemePreferencesStore.resolveEffectiveDynamicColor(enabled = false, sdkInt = 35))
    }

    @Test
    fun `fromContext restores saved preference and safely recovers invalid mode`() {
        val fakeContext = FakeSharedPrefsContext()

        val initialStore = ThemePreferencesStore.fromContext(fakeContext)
        assertEquals(ThemePreference(ThemeMode.SYSTEM, false), initialStore.preference.value)

        initialStore.setThemeMode(ThemeMode.LIGHT)
        initialStore.setDynamicColorEnabled(true)

        val reloadedStore = ThemePreferencesStore.fromContext(fakeContext)
        assertEquals(ThemePreference(ThemeMode.LIGHT, true), reloadedStore.preference.value)

        fakeContext.prefs.edit().putString("theme_mode", "UNKNOWN_CORRUPTED").apply()
        val recoveredStore = ThemePreferencesStore.fromContext(fakeContext)
        assertEquals(ThemePreference(ThemeMode.SYSTEM, true), recoveredStore.preference.value)
    }

    private class FakeSharedPrefsContext : ContextWrapper(null) {
        val prefs = InMemorySharedPreferences()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = values?.toSet()
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = null
            }

            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) data.clear()
                for ((k, v) in pending) {
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {}

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {}
    }
}
