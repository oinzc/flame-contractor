package io.github.oinzc.flamecontractor.core.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun fromStorageValue(raw: String?): ThemeMode {
            val normalized = raw?.trim()?.uppercase()
            return entries.firstOrNull { it.name == normalized } ?: SYSTEM
        }
    }
}

data class ThemePreference(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColorEnabled: Boolean = false,
)

class ThemePreferencesStore(
    initialPreference: ThemePreference = ThemePreference(),
    private val onPersist: (ThemePreference) -> Unit = {},
) {
    private val _preference = MutableStateFlow(initialPreference)
    val preference: StateFlow<ThemePreference> = _preference.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        val next = _preference.value.copy(mode = mode)
        if (_preference.value != next) {
            _preference.value = next
        }
        onPersist(next)
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        val next = _preference.value.copy(dynamicColorEnabled = enabled)
        if (_preference.value != next) {
            _preference.value = next
        }
        onPersist(next)
    }

    companion object {
        private const val PREFS_NAME = "flame_theme_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR_ENABLED = "dynamic_color_enabled"
        const val MIN_DYNAMIC_COLOR_SDK = 31

        fun fromContext(context: Context): ThemePreferencesStore {
            val prefs = (context.applicationContext ?: context)
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedMode = ThemeMode.fromStorageValue(prefs.getString(KEY_THEME_MODE, null))
            val savedDynamic = prefs.getBoolean(KEY_DYNAMIC_COLOR_ENABLED, false)
            return ThemePreferencesStore(
                initialPreference = ThemePreference(
                    mode = savedMode,
                    dynamicColorEnabled = savedDynamic,
                ),
                onPersist = { pref ->
                    prefs.edit()
                        .putString(KEY_THEME_MODE, pref.mode.name)
                        .putBoolean(KEY_DYNAMIC_COLOR_ENABLED, pref.dynamicColorEnabled)
                        .apply()
                },
            )
        }

        fun resolveIsDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }

        fun resolveEffectiveDynamicColor(enabled: Boolean, sdkInt: Int): Boolean =
            enabled && sdkInt >= MIN_DYNAMIC_COLOR_SDK
    }
}
