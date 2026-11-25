package io.github.oinzc.flamecontractor.execution

import io.github.oinzc.flamecontractor.core.identity.MatchStatus
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LoginMode {
    PRESERVE_SESSION,
    CLEAR_AND_RELOGIN,
    INSPECT_OR_VERIFY,
}

data class StructureProbeItem(
    val key: String,
    val label: String,
    val hit: Boolean,
    val matchedCount: Int = 0,
    val detail: String? = null,
)

data class StructureProbeReport(
    val checkedAtEpochMs: Long,
    val loggedIn: Boolean,
    val coreSkeletonOk: Boolean,
    val bundleDigest: String,
    val webViewVersion: String,
    val items: List<StructureProbeItem>,
    val summary: String,
)

data class ScannedChatCandidate(
    val displayName: String,
    val conversationType: String,
    val sparkDays: Int?,
    val sparkKind: String?,
    val sparkLabel: String?,
    val matchStatus: MatchStatus,
    val platformUserId: String?,
    val platformHandle: String?,
    val platformConversationId: String?,
    val matchDetail: String?,
    val alreadyImported: Boolean,
    val existingTargetId: String?,
    val preselected: Boolean,
)

data class ScanReconciliationSnapshot(
    val scanRunId: String,
    val scannedAtEpochMs: Long,
    val discoveredCount: Int,
    val sparkCount: Int,
    val candidates: List<ScannedChatCandidate>,
)

object ScanReconciliationBuilder {

    fun hasOfficialSpark(candidate: ScannedChatCandidate): Boolean =
        hasOfficialSpark(candidate.sparkDays, candidate.sparkKind, candidate.sparkLabel)

    fun hasOfficialSpark(
        sparkDays: Int?,
        sparkKind: String?,
        sparkLabel: String?,
    ): Boolean {
        val cleanedLabel = sparkLabel?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanedLabel == "无火花") return false
        if (sparkDays != null && sparkDays > 0) return true
        if (cleanedLabel != null) return true
        if (!sparkKind.isNullOrBlank()) return true
        return false
    }

    fun sortAndComputePreselection(
        candidates: List<ScannedChatCandidate>,
    ): List<ScannedChatCandidate> {
        val normalized = candidates.map { candidate ->
            val hasSpark = hasOfficialSpark(candidate)
            val preselected = hasSpark &&
                !candidate.alreadyImported &&
                candidate.matchStatus == MatchStatus.BOUND
            if (candidate.preselected == preselected) {
                candidate
            } else {
                candidate.copy(preselected = preselected)
            }
        }

        return normalized
            .withIndex()
            .sortedWith { a, b ->
                val itemA = a.value
                val itemB = b.value
                val tierA = tierOf(itemA)
                val tierB = tierOf(itemB)
                if (tierA != tierB) {
                    return@sortedWith tierA.compareTo(tierB)
                }
                if (tierA <= 1) {
                    val daysCmp = compareSparkDaysDesc(itemA.sparkDays, itemB.sparkDays)
                    if (daysCmp != 0) return@sortedWith daysCmp
                }
                a.index.compareTo(b.index)
            }
            .map { it.value }
    }

    private fun tierOf(candidate: ScannedChatCandidate): Int {
        val hasSpark = hasOfficialSpark(candidate)
        return (if (hasSpark) 0 else 2) + (if (candidate.alreadyImported) 1 else 0)
    }

    private fun compareSparkDaysDesc(aDays: Int?, bDays: Int?): Int = when {
        aDays != null && bDays != null -> bDays.compareTo(aDays)
        aDays != null -> -1
        bDays != null -> 1
        else -> 0
    }
}

/**
 * 纯 Kotlin JSON 编解码器（不依赖 Android Stub 返回 null 的 org.json.JSONObject），
 * 保障 ScanReconciliationSnapshot 在纯 JVM 单元测试与真机环境行为完全一致。
 */
object ScanReconciliationCodec {

    fun encode(snapshot: ScanReconciliationSnapshot): String {
        val map = linkedMapOf<String, Any?>(
            "scanRunId" to snapshot.scanRunId,
            "scannedAtEpochMs" to snapshot.scannedAtEpochMs,
            "discoveredCount" to snapshot.discoveredCount,
            "sparkCount" to snapshot.sparkCount,
            "candidates" to snapshot.candidates.map { c ->
                linkedMapOf<String, Any?>(
                    "displayName" to c.displayName,
                    "conversationType" to c.conversationType,
                    "sparkDays" to c.sparkDays,
                    "sparkKind" to c.sparkKind,
                    "sparkLabel" to c.sparkLabel,
                    "matchStatus" to c.matchStatus.name,
                    "platformUserId" to c.platformUserId,
                    "platformHandle" to c.platformHandle,
                    "platformConversationId" to c.platformConversationId,
                    "matchDetail" to c.matchDetail,
                    "alreadyImported" to c.alreadyImported,
                    "existingTargetId" to c.existingTargetId,
                    "preselected" to c.preselected,
                )
            },
        )
        return ExecJson.encode(map)
    }

    fun decode(rawJson: String): ScanReconciliationSnapshot {
        val root = ExecJson.parseObject(rawJson)
        val scanRunId = root.requiredString("scanRunId")
        val scannedAtEpochMs = root.requiredLong("scannedAtEpochMs")
        val discoveredCount = root.requiredInt("discoveredCount")
        val sparkCount = root.requiredInt("sparkCount")
        val rawCandidates = root["candidates"] as? List<*>
            ?: throw IllegalArgumentException("Missing candidates array")

        val candidates = rawCandidates.map { entry ->
            @Suppress("UNCHECKED_CAST")
            val obj = entry as? Map<String, Any?>
                ?: throw IllegalArgumentException("Candidate entry must be a JSON object")
            val statusStr = obj.requiredString("matchStatus")
            val matchStatus = MatchStatus.entries.firstOrNull {
                it.name.equals(statusStr, ignoreCase = true)
            } ?: throw IllegalArgumentException("Unknown MatchStatus: $statusStr")

            ScannedChatCandidate(
                displayName = obj.requiredString("displayName"),
                conversationType = obj.requiredString("conversationType"),
                sparkDays = obj.intOrNull("sparkDays"),
                sparkKind = obj.stringOrNull("sparkKind"),
                sparkLabel = obj.stringOrNull("sparkLabel"),
                matchStatus = matchStatus,
                platformUserId = obj.stringOrNull("platformUserId"),
                platformHandle = obj.stringOrNull("platformHandle"),
                platformConversationId = obj.stringOrNull("platformConversationId"),
                matchDetail = obj.stringOrNull("matchDetail"),
                alreadyImported = obj.boolOrDefault("alreadyImported", false),
                existingTargetId = obj.stringOrNull("existingTargetId"),
                preselected = obj.boolOrDefault("preselected", false),
            )
        }

        return ScanReconciliationSnapshot(
            scanRunId = scanRunId,
            scannedAtEpochMs = scannedAtEpochMs,
            discoveredCount = discoveredCount,
            sparkCount = sparkCount,
            candidates = candidates,
        )
    }

    fun decodeOrNull(rawJson: String?): ScanReconciliationSnapshot? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            decode(rawJson)
        } catch (_: Throwable) {
            null
        }
    }

    private fun Map<String, Any?>.requiredString(key: String): String =
        (this[key] as? String)
            ?: throw IllegalArgumentException("Missing string field: $key")

    private fun Map<String, Any?>.requiredLong(key: String): Long =
        (this[key] as? Number)?.toLong()
            ?: throw IllegalArgumentException("Missing numeric field: $key")

    private fun Map<String, Any?>.requiredInt(key: String): Int =
        (this[key] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Missing int field: $key")
}

data class ExecutionLiveState(
    val running: Boolean = false,
    val activeAccountId: String? = null,
    val currentTask: String? = null,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val scanDiscoveredCount: Int = 0,
    val latestScanSnapshotAccountId: String? = null,
    val latestScanSnapshot: ScanReconciliationSnapshot? = null,
    val latestProbeReport: StructureProbeReport? = null,
    val lastMessage: String? = null,
    val needsLoginChoicePrompt: Boolean = false,
    val lastUpdatedAtEpochMs: Long = 0L,
    val lastSkipSummary: String? = null,
    val lastErrorSummary: String? = null,
    val lastBatchMode: String? = null,
)

object ExecutionRuntimeBus {

    private val _state = MutableStateFlow(ExecutionLiveState())
    val state: StateFlow<ExecutionLiveState> = _state.asStateFlow()

    fun update(transform: (ExecutionLiveState) -> ExecutionLiveState) {
        _state.update(transform)
    }

    fun clearScanSnapshot() {
        _state.update { it.copy(latestScanSnapshot = null) }
    }

    fun clearProbeReport() {
        _state.update { it.copy(latestProbeReport = null) }
    }

    fun clearLoginChoicePrompt() {
        _state.update { it.copy(needsLoginChoicePrompt = false) }
    }
}

internal object ExecJson {

    private const val MAX_DEPTH = 64

    fun parseOrNull(raw: String?): Any? {
        if (raw.isNullOrBlank()) return null
        return try {
            val parser = Parser(raw)
            val result = parser.parseValue(0)
            parser.skipWhitespace()
            if (!parser.isAtEnd()) null else result
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObjectOrNull(raw: String?): Map<String, Any?>? =
        parseOrNull(raw) as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun parseObject(raw: String): Map<String, Any?> {
        val parsed = Parser(raw).let { p ->
            val v = p.parseValue(0)
            p.skipWhitespace()
            if (!p.isAtEnd()) throw IllegalArgumentException("Trailing JSON content")
            v
        }
        return parsed as? Map<String, Any?>
            ?: throw IllegalArgumentException("Expected JSON object")
    }

    fun unwrapEvaluateResult(raw: String?): Any? {
        val parsed = parseOrNull(raw) ?: return null
        if (parsed is String) {
            val trimmed = parsed.trim()
            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"))
            ) {
                return parseOrNull(trimmed) ?: parsed
            }
        }
        return parsed
    }

    fun encode(value: Any?): String {
        val sb = StringBuilder()
        appendValue(sb, value)
        return sb.toString()
    }

    private fun appendValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> sb.append(value.toString())
            is String -> appendString(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, k.toString())
                    sb.append(':')
                    appendValue(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    appendValue(sb, item)
                }
                sb.append(']')
            }
            else -> appendString(sb, value.toString())
        }
    }

    fun appendString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun isAtEnd(): Boolean = pos >= src.length

        fun skipWhitespace() {
            while (pos < src.length && src[pos] in charArrayOf(' ', '\t', '\r', '\n')) {
                pos++
            }
        }

        fun parseValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw IllegalArgumentException("JSON depth exceeded")
            skipWhitespace()
            if (isAtEnd()) throw IllegalArgumentException("Unexpected end of JSON")
            return when (src[pos]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected JSON char at $pos")
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            pos++
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (!isAtEnd() && src[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (isAtEnd() || src[pos] != '"') throw IllegalArgumentException("Expected object key")
                val key = parseString()
                skipWhitespace()
                if (isAtEnd() || src[pos] != ':') throw IllegalArgumentException("Expected colon")
                pos++
                map[key] = parseValue(depth)
                skipWhitespace()
                if (isAtEnd()) throw IllegalArgumentException("Unclosed JSON object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            pos++
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (!isAtEnd() && src[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue(depth))
                skipWhitespace()
                if (isAtEnd()) throw IllegalArgumentException("Unclosed JSON array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            pos++
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        if (pos >= src.length) throw IllegalArgumentException("Truncated escape")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw IllegalArgumentException("Truncated unicode")
                                val code = src.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid unicode")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape: $esc")
                        }
                    }
                    ch.code < 0x20 -> throw IllegalArgumentException("Unescaped control char")
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unclosed string")
        }

        private fun parseLiteral(token: String, value: Any?): Any? {
            if (!src.startsWith(token, pos)) throw IllegalArgumentException("Invalid literal")
            pos += token.length
            return value
        }

        private fun parseNumber(): Number {
            val start = pos
            if (src[pos] == '-') pos++
            if (isAtEnd()) throw IllegalArgumentException("Invalid number")
            if (src[pos] == '0') {
                pos++
            } else if (src[pos] in '1'..'9') {
                while (!isAtEnd() && src[pos] in '0'..'9') pos++
            } else {
                throw IllegalArgumentException("Invalid number")
            }
            var floating = false
            if (!isAtEnd() && src[pos] == '.') {
                floating = true
                pos++
                if (isAtEnd() || src[pos] !in '0'..'9') throw IllegalArgumentException("Invalid fraction")
                while (!isAtEnd() && src[pos] in '0'..'9') pos++
            }
            if (!isAtEnd() && (src[pos] == 'e' || src[pos] == 'E')) {
                floating = true
                pos++
                if (!isAtEnd() && (src[pos] == '+' || src[pos] == '-')) pos++
                if (isAtEnd() || src[pos] !in '0'..'9') throw IllegalArgumentException("Invalid exponent")
                while (!isAtEnd() && src[pos] in '0'..'9') pos++
            }
            val token = src.substring(start, pos)
            return if (floating) {
                token.toDoubleOrNull() ?: throw IllegalArgumentException("Float overflow")
            } else {
                token.toLongOrNull() ?: token.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Number overflow")
            }
        }
    }
}

internal fun Map<String, Any?>.stringOrNull(key: String): String? =
    (this[key] as? String)?.takeIf { it.isNotEmpty() }

internal fun Map<String, Any?>.rawStringOrNull(key: String): String? =
    this[key] as? String

internal fun Map<String, Any?>.intOrNull(key: String): Int? =
    (this[key] as? Number)?.toInt()

internal fun Map<String, Any?>.longOrNull(key: String): Long? =
    (this[key] as? Number)?.toLong()

internal fun Map<String, Any?>.boolOrDefault(key: String, default: Boolean = false): Boolean =
    (this[key] as? Boolean) ?: default

internal fun Map<String, Any?>.stringListOrEmpty(key: String): List<String> =
    (this[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
