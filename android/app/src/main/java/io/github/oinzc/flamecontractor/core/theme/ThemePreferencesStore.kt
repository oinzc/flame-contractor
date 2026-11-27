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

enum class UiDisplayMode {
    STANDARD,
    SIMPLE,
    ;

    companion object {
        fun fromStorageValue(raw: String?): UiDisplayMode {
            val normalized = raw?.trim()?.uppercase()
            return entries.firstOrNull { it.name == normalized } ?: SIMPLE
        }
    }
}

data class ThemePreference(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val displayMode: UiDisplayMode = UiDisplayMode.SIMPLE,
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

    fun setDisplayMode(mode: UiDisplayMode) {
        val next = _preference.value.copy(displayMode = mode)
        if (_preference.value != next) {
            _preference.value = next
        }
        onPersist(next)
    }

    companion object {
        private const val PREFS_NAME = "flame_theme_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_UI_DISPLAY_MODE = "ui_display_mode"
        internal const val KEY_SIMPLE_DEFAULT_MIGRATION_V1 = "ui_display_mode_simple_default_v1_migrated"
        private const val LEGACY_KEY_DYNAMIC_COLOR_ENABLED = "dynamic_color_enabled"

        internal fun resolveInitialDisplayMode(
            rawStoredMode: String?,
            migratedV1: Boolean,
        ): Pair<UiDisplayMode, Boolean> {
            if (!migratedV1) {
                return UiDisplayMode.SIMPLE to true
            }
            return UiDisplayMode.fromStorageValue(rawStoredMode) to false
        }

        fun fromContext(context: Context): ThemePreferencesStore {
            val prefs = (context.applicationContext ?: context)
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(LEGACY_KEY_DYNAMIC_COLOR_ENABLED)) {
                prefs.edit().remove(LEGACY_KEY_DYNAMIC_COLOR_ENABLED).apply()
            }
            val savedMode = ThemeMode.fromStorageValue(prefs.getString(KEY_THEME_MODE, null))
            val migratedV1 = prefs.getBoolean(KEY_SIMPLE_DEFAULT_MIGRATION_V1, false)
            val (initialDisplayMode, needsMigrationPersist) = resolveInitialDisplayMode(
                rawStoredMode = prefs.getString(KEY_UI_DISPLAY_MODE, null),
                migratedV1 = migratedV1,
            )
            if (needsMigrationPersist) {
                prefs.edit()
                    .putString(KEY_UI_DISPLAY_MODE, initialDisplayMode.name)
                    .putBoolean(KEY_SIMPLE_DEFAULT_MIGRATION_V1, true)
                    .apply()
            }
            return ThemePreferencesStore(
                initialPreference = ThemePreference(
                    mode = savedMode,
                    displayMode = initialDisplayMode,
                ),
                onPersist = { pref ->
                    prefs.edit()
                        .putString(KEY_THEME_MODE, pref.mode.name)
                        .putString(KEY_UI_DISPLAY_MODE, pref.displayMode.name)
                        .putBoolean(KEY_SIMPLE_DEFAULT_MIGRATION_V1, true)
                        .apply()
                },
            )
        }

        fun resolveIsDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    }
}
