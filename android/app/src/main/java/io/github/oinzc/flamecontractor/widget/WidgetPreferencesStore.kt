package io.github.oinzc.flamecontractor.widget

import android.content.Context
import io.github.oinzc.flamecontractor.core.theme.ThemeMode
import io.github.oinzc.flamecontractor.core.theme.ThemePreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WidgetAppearanceStyle(val label: String) {
    FOLLOW_APP("跟随 App"),
    DARK_CARBON("深色碳黑"),
    LIGHT_PURE("浅色纯白"),
    GLASS_DARK("半透明深色玻璃"),
    ;

    companion object {
        fun fromStorageValue(raw: String?): WidgetAppearanceStyle {
            val normalized = raw?.trim()?.uppercase()
            return entries.firstOrNull { it.name == normalized } ?: FOLLOW_APP
        }
    }
}

enum class ResolvedWidgetSurface {
    LIGHT,
    DARK_CARBON,
    GLASS_DARK,
    ;

    val isDark: Boolean get() = this != LIGHT
}

data class WidgetPreferences(
    val appearanceStyle: WidgetAppearanceStyle = WidgetAppearanceStyle.FOLLOW_APP,
    val hideFriendNames: Boolean = false,
)

class WidgetPreferencesStore(
    initial: WidgetPreferences = WidgetPreferences(),
    private val onPersist: (WidgetPreferences) -> Unit = {},
) {
    private val _preferences = MutableStateFlow(initial)
    val preferences: StateFlow<WidgetPreferences> = _preferences.asStateFlow()

    fun setAppearanceStyle(style: WidgetAppearanceStyle) {
        val next = _preferences.value.copy(appearanceStyle = style)
        if (_preferences.value != next) {
            _preferences.value = next
        }
        onPersist(next)
    }

    fun setHideFriendNames(hide: Boolean) {
        val next = _preferences.value.copy(hideFriendNames = hide)
        if (_preferences.value != next) {
            _preferences.value = next
        }
        onPersist(next)
    }

    companion object {
        private const val PREFS_NAME = "flame_widget_prefs"
        private const val KEY_APPEARANCE_STYLE = "appearance_style"
        private const val KEY_HIDE_FRIEND_NAMES = "hide_friend_names"

        fun fromContext(context: Context): WidgetPreferencesStore {
            val prefs = (context.applicationContext ?: context)
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val style = WidgetAppearanceStyle.fromStorageValue(
                prefs.getString(KEY_APPEARANCE_STYLE, null),
            )
            val hide = prefs.getBoolean(KEY_HIDE_FRIEND_NAMES, false)
            return WidgetPreferencesStore(
                initial = WidgetPreferences(
                    appearanceStyle = style,
                    hideFriendNames = hide,
                ),
                onPersist = { next ->
                    prefs.edit()
                        .putString(KEY_APPEARANCE_STYLE, next.appearanceStyle.name)
                        .putBoolean(KEY_HIDE_FRIEND_NAMES, next.hideFriendNames)
                        .apply()
                },
            )
        }

        fun resolveSurface(
            style: WidgetAppearanceStyle,
            appThemeMode: ThemeMode,
            systemDark: Boolean,
        ): ResolvedWidgetSurface = when (style) {
            WidgetAppearanceStyle.LIGHT_PURE -> ResolvedWidgetSurface.LIGHT
            WidgetAppearanceStyle.DARK_CARBON -> ResolvedWidgetSurface.DARK_CARBON
            WidgetAppearanceStyle.GLASS_DARK -> ResolvedWidgetSurface.GLASS_DARK
            WidgetAppearanceStyle.FOLLOW_APP -> {
                if (ThemePreferencesStore.resolveIsDark(appThemeMode, systemDark)) {
                    ResolvedWidgetSurface.DARK_CARBON
                } else {
                    ResolvedWidgetSurface.LIGHT
                }
            }
        }
    }
}
