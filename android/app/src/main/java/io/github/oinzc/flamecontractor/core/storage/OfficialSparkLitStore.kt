package io.github.oinzc.flamecontractor.core.storage

import android.content.Context
import android.content.SharedPreferences
import io.github.oinzc.flamecontractor.execution.ExecJson
import io.github.oinzc.flamecontractor.execution.longOrNull
import io.github.oinzc.flamecontractor.execution.stringOrNull
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OfficialSparkLitRecord(
    val targetId: String,
    val accountId: String,
    val businessDate: String, // YYYY-MM-DD（按账号时区自然日）
    val litToday: Boolean,    // true=官方已点亮, false=官方灰火
    val scannedAtEpochMs: Long,
)

/**
 * 抖音官方「今日亮/灰火花」只读观察状态存储（零 Room schema 变更）：
 * - 以 `targetId` 为主键持久化到 `SharedPreferences("flame_official_spark_lit_prefs")`；
 * - 严格按账号时区自然日 `businessDate`（`YYYY-MM-DD`）隔离，读取跨日自动失效，写入自动清理过期脏记录；
 * - 仅用于 UI 展示与「补发未发」去重跳过，绝不改写本地 `streak_days` 或绕过 `UNKNOWN_REQUIRES_REVIEW` 安全闸门；
 * - 支持无参纯内存构造，便于纯 JVM 单元测试。
 */
class OfficialSparkLitStore(
    initialRecords: Map<String, OfficialSparkLitRecord> = emptyMap(),
    private val onPersist: ((Map<String, OfficialSparkLitRecord>) -> Unit)? = null,
) {

    constructor(context: Context) : this(
        prefs = (context.applicationContext ?: context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private constructor(prefs: SharedPreferences) : this(
        initialRecords = decodeRecords(prefs.getString(KEY_RECORDS_JSON, null)),
        onPersist = { snapshot ->
            try {
                prefs.edit().putString(KEY_RECORDS_JSON, encodeRecords(snapshot)).apply()
            } catch (_: Throwable) {
            }
        },
    )

    private val lock = Any()
    private val _records = MutableStateFlow(normalizeInitial(initialRecords))
    val records: StateFlow<Map<String, OfficialSparkLitRecord>> = _records.asStateFlow()

    fun recordScanResults(
        accountId: String,
        businessDate: String,
        updates: Map<String, Boolean?>,
        scannedAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        val cleanAccount = accountId.trim()
        val cleanDate = businessDate.trim()
        if (cleanAccount.isEmpty() || cleanDate.isEmpty()) return

        val incomingParsedDate = parseIsoDateOrNull(cleanDate)

        val snapshot: Map<String, OfficialSparkLitRecord> = synchronized(lock) {
            val next = LinkedHashMap<String, OfficialSparkLitRecord>()
            for ((key, record) in _records.value) {
                if (record.accountId == cleanAccount) {
                    // 同账号自动淘汰非今日过期记录
                    if (record.businessDate == cleanDate) {
                        next[key] = record
                    }
                } else {
                    // 跨账号容忍 ±1 天时区差，仅清理明显过期的跨日旧记录
                    if (!isExpiredAcrossAccounts(record.businessDate, incomingParsedDate)) {
                        next[key] = record
                    }
                }
            }

            for ((rawTargetId, litState) in updates) {
                val targetId = rawTargetId.trim()
                if (targetId.isEmpty()) continue
                if (litState == null) {
                    next.remove(targetId)
                } else {
                    next[targetId] = OfficialSparkLitRecord(
                        targetId = targetId,
                        accountId = cleanAccount,
                        businessDate = cleanDate,
                        litToday = litState,
                        scannedAtEpochMs = scannedAtEpochMs,
                    )
                }
            }

            val immutable = next.toMap()
            _records.value = immutable
            immutable
        }
        onPersist?.invoke(snapshot)
    }

    fun isOfficiallyLitToday(targetId: String, businessDate: String): Boolean =
        getTodayLitState(targetId = targetId, businessDate = businessDate) == true

    fun getTodayLitState(targetId: String, businessDate: String): Boolean? {
        val cleanTarget = targetId.trim()
        val cleanDate = businessDate.trim()
        if (cleanTarget.isEmpty() || cleanDate.isEmpty()) return null
        val record = _records.value[cleanTarget] ?: return null
        return if (record.businessDate == cleanDate) record.litToday else null
    }

    fun todayLitTargetIds(accountId: String, businessDate: String): Set<String> {
        val cleanAccount = accountId.trim()
        val cleanDate = businessDate.trim()
        if (cleanAccount.isEmpty() || cleanDate.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>()
        for ((targetId, record) in _records.value) {
            if (record.accountId == cleanAccount &&
                record.businessDate == cleanDate &&
                record.litToday
            ) {
                out.add(targetId)
            }
        }
        return out
    }

    fun clearForTarget(targetId: String) {
        val cleanTarget = targetId.trim()
        if (cleanTarget.isEmpty()) return
        val snapshot: Map<String, OfficialSparkLitRecord>? = synchronized(lock) {
            if (!_records.value.containsKey(cleanTarget)) {
                return@synchronized null
            }
            val next = LinkedHashMap(_records.value)
            next.remove(cleanTarget)
            val immutable = next.toMap()
            _records.value = immutable
            immutable
        }
        if (snapshot != null) {
            onPersist?.invoke(snapshot)
        }
    }

    fun clearForAccount(accountId: String) {
        val cleanAccount = accountId.trim()
        if (cleanAccount.isEmpty()) return
        val snapshot: Map<String, OfficialSparkLitRecord>? = synchronized(lock) {
            val current = _records.value
            if (current.values.none { it.accountId == cleanAccount }) {
                return@synchronized null
            }
            val next = LinkedHashMap<String, OfficialSparkLitRecord>()
            for ((key, record) in current) {
                if (record.accountId != cleanAccount) {
                    next[key] = record
                }
            }
            val immutable = next.toMap()
            _records.value = immutable
            immutable
        }
        if (snapshot != null) {
            onPersist?.invoke(snapshot)
        }
    }

    companion object {
        const val PREFS_NAME: String = "flame_official_spark_lit_prefs"
        private const val KEY_RECORDS_JSON: String = "records_json"

        @Volatile
        private var globalStore: OfficialSparkLitStore? = null

        fun attachGlobal(store: OfficialSparkLitStore) {
            globalStore = store
        }

        fun globalOrNull(): OfficialSparkLitStore? = globalStore

        internal fun detachGlobalForTest() {
            globalStore = null
        }

        private fun normalizeInitial(
            initial: Map<String, OfficialSparkLitRecord>,
        ): Map<String, OfficialSparkLitRecord> {
            if (initial.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, OfficialSparkLitRecord>(initial.size)
            for ((rawKey, record) in initial) {
                val targetId = record.targetId.trim().ifEmpty { rawKey.trim() }
                val accountId = record.accountId.trim()
                val date = record.businessDate.trim()
                if (targetId.isEmpty() || accountId.isEmpty() || date.isEmpty()) continue
                out[targetId] = record.copy(
                    targetId = targetId,
                    accountId = accountId,
                    businessDate = date,
                )
            }
            return out.toMap()
        }

        private fun parseIsoDateOrNull(raw: String): LocalDate? = try {
            LocalDate.parse(raw)
        } catch (_: Throwable) {
            null
        }

        private fun isExpiredAcrossAccounts(
            recordDateStr: String,
            referenceDate: LocalDate?,
        ): Boolean {
            if (referenceDate == null) return false
            val parsed = parseIsoDateOrNull(recordDateStr) ?: return true
            return parsed.isBefore(referenceDate.minusDays(1L))
        }

        internal fun encodeRecords(records: Map<String, OfficialSparkLitRecord>): String {
            val payload = linkedMapOf<String, Any?>(
                "version" to 1,
                "records" to records.values.map { r ->
                    linkedMapOf<String, Any?>(
                        "targetId" to r.targetId,
                        "accountId" to r.accountId,
                        "businessDate" to r.businessDate,
                        "litToday" to r.litToday,
                        "scannedAtEpochMs" to r.scannedAtEpochMs,
                    )
                },
            )
            return ExecJson.encode(payload)
        }

        internal fun decodeRecords(rawJson: String?): Map<String, OfficialSparkLitRecord> {
            if (rawJson.isNullOrBlank()) return emptyMap()
            val root = ExecJson.parseObjectOrNull(rawJson) ?: return emptyMap()
            val rawList = root["records"] as? List<*> ?: return emptyMap()
            val out = LinkedHashMap<String, OfficialSparkLitRecord>()
            for (item in rawList) {
                @Suppress("UNCHECKED_CAST")
                val obj = item as? Map<String, Any?> ?: continue
                val targetId = obj.stringOrNull("targetId")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val accountId = obj.stringOrNull("accountId")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val businessDate = obj.stringOrNull("businessDate")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val litToday = obj["litToday"] as? Boolean ?: continue
                val scannedAt = obj.longOrNull("scannedAtEpochMs") ?: 0L
                out[targetId] = OfficialSparkLitRecord(
                    targetId = targetId,
                    accountId = accountId,
                    businessDate = businessDate,
                    litToday = litToday,
                    scannedAtEpochMs = scannedAt,
                )
            }
            return out.toMap()
        }
    }
}
