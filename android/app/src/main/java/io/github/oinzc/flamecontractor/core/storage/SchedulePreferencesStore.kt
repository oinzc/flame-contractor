package io.github.oinzc.flamecontractor.core.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 定时调度可选策略持久化存储（零 Room schema 变更）：
 * - 按账号 ID 隔离存储「定时跳过今日抖音已亮火花」可选开关，默认全部关闭（`false`），完整保持既有定时发送行为；
 * - 持久化到 `SharedPreferences("flame_schedule_prefs", Context.MODE_PRIVATE)`，支持后台 Alarm/WorkManager 冷启动直读；
 * - 支持纯内存主构造器，便于 JVM 单元测试零 Android 依赖覆盖。
 */
class SchedulePreferencesStore(
    initialSkipOfficialLitAccountIds: Set<String> = emptySet(),
    private val onPersist: ((Set<String>) -> Unit)? = null,
) {

    constructor(context: Context) : this(
        prefs = (context.applicationContext ?: context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private constructor(prefs: SharedPreferences) : this(
        initialSkipOfficialLitAccountIds = prefs.getStringSet(KEY_SKIP_OFFICIAL_LIT_ACCOUNTS, emptySet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet(),
        onPersist = { snapshot ->
            try {
                prefs.edit()
                    .putStringSet(KEY_SKIP_OFFICIAL_LIT_ACCOUNTS, snapshot.toSet())
                    .apply()
            } catch (_: Throwable) {
            }
        },
    )

    private val lock = Any()
    private val _skipOfficialLitAccountIds = MutableStateFlow(
        initialSkipOfficialLitAccountIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet(),
    )
    val skipOfficialLitAccountIds: StateFlow<Set<String>> = _skipOfficialLitAccountIds.asStateFlow()

    fun isSkipOfficialLitToday(accountId: String): Boolean {
        val clean = accountId.trim()
        if (clean.isEmpty()) return false
        return clean in _skipOfficialLitAccountIds.value
    }

    fun setSkipOfficialLitToday(accountId: String, enabled: Boolean) {
        val clean = accountId.trim()
        if (clean.isEmpty()) return
        val snapshot: Set<String>? = synchronized(lock) {
            val current = _skipOfficialLitAccountIds.value
            val already = clean in current
            if (already == enabled) {
                return@synchronized null
            }
            val next = LinkedHashSet(current)
            if (enabled) {
                next.add(clean)
            } else {
                next.remove(clean)
            }
            val immutable = next.toSet()
            _skipOfficialLitAccountIds.value = immutable
            immutable
        }
        if (snapshot != null) {
            onPersist?.invoke(snapshot)
        }
    }

    fun clearForAccount(accountId: String) {
        setSkipOfficialLitToday(accountId, enabled = false)
    }

    companion object {
        const val PREFS_NAME: String = "flame_schedule_prefs"
        private const val KEY_SKIP_OFFICIAL_LIT_ACCOUNTS: String = "skip_official_lit_today_accounts"

        @Volatile
        private var globalStore: SchedulePreferencesStore? = null

        fun attachGlobal(store: SchedulePreferencesStore) {
            globalStore = store
        }

        fun globalOrNull(): SchedulePreferencesStore? = globalStore

        fun resolve(context: Context): SchedulePreferencesStore {
            val existing = globalStore
            if (existing != null) return existing
            val appCtx = context.applicationContext ?: context
            return synchronized(this) {
                globalStore ?: SchedulePreferencesStore(appCtx).also { globalStore = it }
            }
        }

        internal fun detachGlobalForTest() {
            globalStore = null
        }
    }
}
