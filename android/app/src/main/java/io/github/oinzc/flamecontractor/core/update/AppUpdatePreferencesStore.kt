package io.github.oinzc.flamecontractor.core.update

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * 应用内更新偏好与节流持久化状态。
 */
data class AppUpdatePreferences(
    val autoCheckEnabled: Boolean = true,
    val lastAutoCheckAtEpochMs: Long = 0L,
    val ignoredVersionCode: Long = 0L,
    val lastKnownLatestVersionName: String? = null,
    val lastKnownLatestVersionCode: Long = 0L,
)

/**
 * 轻量更新偏好存储：
 * - 生产环境通过 [fromContext] 持久化到 `SharedPreferences("flame_app_update_prefs", Context.MODE_PRIVATE)`；
 * - 支持纯内存直接构造（零 Android 依赖），便于 JVM 单元测试覆盖节流与忽略状态。
 */
class AppUpdatePreferencesStore(
    initialPreferences: AppUpdatePreferences = AppUpdatePreferences(),
    private val onPersist: (AppUpdatePreferences) -> Unit = {},
) {
    private val _preferences = MutableStateFlow(initialPreferences)
    val preferences: StateFlow<AppUpdatePreferences> = _preferences.asStateFlow()

    fun setAutoCheckEnabled(enabled: Boolean) {
        update { it.copy(autoCheckEnabled = enabled) }
    }

    fun recordCheckAttempt(epochMs: Long) {
        val safeEpoch = epochMs.coerceAtLeast(0L)
        update { it.copy(lastAutoCheckAtEpochMs = safeEpoch) }
    }

    fun setIgnoredVersionCode(versionCode: Long) {
        val safeCode = versionCode.coerceAtLeast(0L)
        update { it.copy(ignoredVersionCode = safeCode) }
    }

    fun clearIgnoredVersion() {
        update { it.copy(ignoredVersionCode = 0L) }
    }

    fun recordKnownLatestRelease(
        versionName: String?,
        versionCode: Long,
    ) {
        val cleanName = versionName?.trim()?.takeIf { it.isNotEmpty() }
        val safeCode = versionCode.coerceAtLeast(0L)
        update {
            it.copy(
                lastKnownLatestVersionName = cleanName,
                lastKnownLatestVersionCode = safeCode,
            )
        }
    }

    /**
     * 仅在 `autoCheckEnabled == true` 且距上次检查超过 [minIntervalMs]（默认 6 小时）时返回 `true`。
     */
    fun shouldRunAutoCheck(
        nowEpochMs: Long,
        minIntervalMs: Long = DEFAULT_AUTO_CHECK_INTERVAL_MS,
    ): Boolean {
        val current = _preferences.value
        if (!current.autoCheckEnabled) return false
        val last = current.lastAutoCheckAtEpochMs
        if (last <= 0L) return true
        val safeInterval = minIntervalMs.coerceAtLeast(0L)
        val elapsed = nowEpochMs - last
        // 系统时间发生回拨时放行检查，避免长期卡死节流窗口
        if (elapsed < 0L) return true
        return elapsed >= safeInterval
    }

    private fun update(transform: (AppUpdatePreferences) -> AppUpdatePreferences) {
        val next = _preferences.updateAndGet(transform)
        onPersist(next)
    }

    companion object {
        const val PREFS_NAME: String = "flame_app_update_prefs"
        const val DEFAULT_AUTO_CHECK_INTERVAL_MS: Long = 6L * 60L * 60L * 1000L

        private const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
        private const val KEY_LAST_AUTO_CHECK_AT = "last_auto_check_at_epoch_ms"
        private const val KEY_IGNORED_VERSION_CODE = "ignored_version_code"
        private const val KEY_LAST_KNOWN_VERSION_NAME = "last_known_latest_version_name"
        private const val KEY_LAST_KNOWN_VERSION_CODE = "last_known_latest_version_code"

        fun fromContext(context: Context): AppUpdatePreferencesStore {
            val prefs = (context.applicationContext ?: context)
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return fromSharedPreferences(prefs)
        }

        fun fromSharedPreferences(prefs: SharedPreferences): AppUpdatePreferencesStore {
            val initial = AppUpdatePreferences(
                autoCheckEnabled = prefs.getBoolean(KEY_AUTO_CHECK_ENABLED, true),
                lastAutoCheckAtEpochMs = prefs.getLong(KEY_LAST_AUTO_CHECK_AT, 0L).coerceAtLeast(0L),
                ignoredVersionCode = prefs.getLong(KEY_IGNORED_VERSION_CODE, 0L).coerceAtLeast(0L),
                lastKnownLatestVersionName = prefs.getString(KEY_LAST_KNOWN_VERSION_NAME, null)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
                lastKnownLatestVersionCode = prefs.getLong(KEY_LAST_KNOWN_VERSION_CODE, 0L).coerceAtLeast(0L),
            )
            return AppUpdatePreferencesStore(
                initialPreferences = initial,
                onPersist = { state ->
                    val editor = prefs.edit()
                        .putBoolean(KEY_AUTO_CHECK_ENABLED, state.autoCheckEnabled)
                        .putLong(KEY_LAST_AUTO_CHECK_AT, state.lastAutoCheckAtEpochMs)
                        .putLong(KEY_IGNORED_VERSION_CODE, state.ignoredVersionCode)
                        .putLong(KEY_LAST_KNOWN_VERSION_CODE, state.lastKnownLatestVersionCode)
                    if (state.lastKnownLatestVersionName != null) {
                        editor.putString(KEY_LAST_KNOWN_VERSION_NAME, state.lastKnownLatestVersionName)
                    } else {
                        editor.remove(KEY_LAST_KNOWN_VERSION_NAME)
                    }
                    editor.apply()
                },
            )
        }
    }
}
