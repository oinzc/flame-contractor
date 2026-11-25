package io.github.oinzc.flamecontractor.core.diagnostics

import android.content.Context
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.ui.settings.SanitizedDiagnosticsBuilder
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 持久化脱敏运行日志环缓冲：
 * - 落盘路径为 `filesDir/diagnostics/runtime_events.log`，最多保留 [MAX_ENTRIES] 条，自动淘汰超过 [RETENTION_DAYS] 天的记录；
 * - 支持纯内存构造（`logFile = null`）以供 JVM 单元测试零 Android 依赖直接验证；
 * - 所有写入字段均强制经 [SanitizedDiagnosticsBuilder.redactSensitiveText]、敏感键值正则与长度钳制，
 *   严禁记录好友昵称、群名、抖音号、`selfUid`、`platformUserId`、`platformConversationId`、Cookie、消息正文或完整 Bark URL。
 */
class DiagnosticLogStore(
    private val logFile: File? = null,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: Context) : this(
        logFile = File(
            (context.applicationContext ?: context).filesDir,
            "$LOG_DIR_NAME/$LOG_FILE_NAME",
        ),
    )

    private data class LogEntry(
        val epochMs: Long,
        val line: String,
    )

    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>(MAX_ENTRIES + 1)
    private var loadedFromDisk = (logFile == null)

    fun record(
        category: String,
        event: String,
        level: String = "INFO",
        detail: String = "",
        accountTag: String? = null,
    ) {
        val nowMs = clockMillis()
        val line = formatSanitizedLine(
            epochMs = nowMs,
            level = level,
            category = category,
            event = event,
            detail = detail,
            accountTag = accountTag,
        )
        synchronized(lock) {
            ensureLoadedLocked(nowMs)
            pruneExpiredLocked(nowMs)
            entries.addLast(LogEntry(epochMs = nowMs, line = line))
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
            persistLocked()
        }
    }

    fun recentLines(limit: Int = DEFAULT_REPORT_LIMIT): List<String> {
        val safeLimit = limit.coerceAtLeast(0)
        if (safeLimit == 0) return emptyList()
        val nowMs = clockMillis()
        synchronized(lock) {
            ensureLoadedLocked(nowMs)
            val pruned = pruneExpiredLocked(nowMs)
            if (pruned) {
                persistLocked()
            }
            if (entries.isEmpty()) return emptyList()
            val skip = (entries.size - safeLimit).coerceAtLeast(0)
            return entries.drop(skip).map { it.line }
        }
    }

    fun formatForReport(
        limit: Int = DEFAULT_REPORT_LIMIT,
        maxChars: Int = DEFAULT_REPORT_MAX_CHARS,
    ): String {
        val safeMax = maxChars.coerceAtLeast(0)
        if (safeMax == 0) return ""
        val lines = recentLines(limit)
        if (lines.isEmpty()) return ""
        return trimLinesFromHeadToMaxChars(lines, safeMax)
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            loadedFromDisk = true
            val file = logFile ?: return
            runCatching {
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    private fun ensureLoadedLocked(nowMs: Long) {
        if (loadedFromDisk) return
        loadedFromDisk = true
        val file = logFile ?: return
        if (!file.isFile || !file.canRead()) return

        val cutoffMs = nowMs - RETENTION_MS
        val loaded = runCatching {
            file.readLines(StandardCharsets.UTF_8)
        }.getOrElse { emptyList() }

        for (rawLine in loaded) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            val tsToken = trimmed.substringBefore(' ')
            val epochMs = runCatching {
                Instant.parse(tsToken).toEpochMilli()
            }.getOrNull() ?: continue
            if (epochMs < cutoffMs) continue
            val sanitizedLine = sanitizePersistedLine(trimmed)
            if (sanitizedLine.isNotEmpty()) {
                entries.addLast(LogEntry(epochMs = epochMs, line = sanitizedLine))
            }
        }
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
        }
    }

    private fun pruneExpiredLocked(nowMs: Long): Boolean {
        val cutoffMs = nowMs - RETENTION_MS
        var removed = false
        while (entries.isNotEmpty() && entries.first().epochMs < cutoffMs) {
            entries.removeFirst()
            removed = true
        }
        return removed
    }

    private fun persistLocked() {
        val file = logFile ?: return
        runCatching {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val payload = if (entries.isEmpty()) {
                ""
            } else {
                entries.joinToString(separator = "\n", postfix = "\n") { it.line }
            }
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            val tmp = File(parent ?: file.absoluteFile.parentFile, "${file.name}.${System.nanoTime()}.tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.flush()
                }
                if (file.exists() && !tmp.renameTo(file)) {
                    file.delete()
                    if (!tmp.renameTo(file)) {
                        FileOutputStream(file).use { out ->
                            out.write(bytes)
                            out.flush()
                        }
                    }
                } else if (!file.exists() && !tmp.renameTo(file)) {
                    FileOutputStream(file).use { out ->
                        out.write(bytes)
                        out.flush()
                    }
                }
            } finally {
                if (tmp.exists()) {
                    runCatching { tmp.delete() }
                }
            }
        }
    }

    companion object {
        const val LOG_DIR_NAME: String = "diagnostics"
        const val LOG_FILE_NAME: String = "runtime_events.log"
        const val MAX_ENTRIES: Int = 300
        const val RETENTION_DAYS: Long = 7L
        const val RETENTION_MS: Long = RETENTION_DAYS * 24L * 60L * 60L * 1000L
        const val DEFAULT_REPORT_LIMIT: Int = 120
        const val DEFAULT_REPORT_MAX_CHARS: Int = 10_000
        const val MAX_DETAIL_CHARS: Int = 240
        private const val MAX_RAW_DETAIL_INPUT_CHARS: Int = 2_048

        private val ALLOWED_LEVELS = setOf("DEBUG", "INFO", "WARN", "ERROR")
        private val SAFE_CATEGORY_REGEX = Regex("""^[A-Z0-9_-]{1,24}$""")
        private val SAFE_EVENT_REGEX = Regex("""^[A-Za-z0-9_.:-]{1,48}$""")
        private val SAFE_ACCOUNT_TAG_REGEX = Regex("""^[A-Za-z0-9_#.-]{1,16}$""")
        private val CONSECUTIVE_DIGITS_10_REGEX = Regex("""\d{10,}""")
        private val WHITESPACE_RUN_REGEX = Regex("""[\r\n\t\s]+""")
        private val PII_KV_REGEX = Regex(
            """\b(nickname|nick_?name|display_?name|remark_?name|group_?name|self_?uid|platform_?user_?id|platform_?conv(?:ersation)?_?id|platform_?handle|douyin_?(?:id|handle|nick(?:name)?)|unique_?id|short_?id|msg_?body|message_?text|raw_?title|bark_?url)(["']?\s*[:=]\s*["']?)([^\s,;}\]"']+)""",
            RegexOption.IGNORE_CASE,
        )

        @Volatile
        private var globalStore: DiagnosticLogStore? = null

        fun attachGlobal(store: DiagnosticLogStore) {
            globalStore = store
        }

        fun globalOrNull(): DiagnosticLogStore? = globalStore

        internal fun detachGlobalForTest() {
            globalStore = null
        }

        fun recordGlobal(
            category: String,
            event: String,
            level: String = "INFO",
            detail: String = "",
            accountTag: String? = null,
        ) {
            runCatching {
                globalStore?.record(
                    category = category,
                    event = event,
                    level = level,
                    detail = detail,
                    accountTag = accountTag,
                )
            }
        }

        /**
         * 将账号 ID 转换为不可逆脱敏短标签，仅用于多账号排查区分，绝不含昵称或 UID。
         */
        fun safeAccountTag(accountId: String?): String? {
            val trimmed = accountId?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            if (trimmed == DEFAULT_ACCOUNT_ID) return DEFAULT_ACCOUNT_ID
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(trimmed.toByteArray(StandardCharsets.UTF_8))
            val hex = buildString(6) {
                for (i in 0 until 3) {
                    append(String.format(Locale.ROOT, "%02x", digest[i].toInt() and 0xFF))
                }
            }
            return "acct_$hex"
        }

        internal fun formatSanitizedLine(
            epochMs: Long,
            level: String,
            category: String,
            event: String,
            detail: String,
            accountTag: String?,
        ): String {
            val ts = Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS).toString()
            val safeLevel = sanitizeLevel(level)
            val safeCategory = sanitizeCategory(category)
            val safeEvent = sanitizeEvent(event)
            val safeTag = sanitizeAccountTag(accountTag)
            val safeDetail = sanitizeDetail(detail)

            return buildString {
                append(ts)
                append(" [").append(safeLevel).append(']')
                append(" [").append(safeCategory).append(']')
                if (safeTag != null) {
                    append(" [").append(safeTag).append(']')
                }
                append(' ').append(safeEvent)
                if (safeDetail.isNotEmpty()) {
                    append(' ').append(safeDetail)
                }
            }
        }

        internal fun sanitizeLevel(raw: String): String {
            val upper = raw.trim().uppercase(Locale.ROOT)
            return if (upper in ALLOWED_LEVELS) upper else "INFO"
        }

        internal fun sanitizeCategory(raw: String): String {
            val cleaned = raw.trim().uppercase(Locale.ROOT).replace(Regex("""[^A-Z0-9_-]"""), "_")
            return if (SAFE_CATEGORY_REGEX.matches(cleaned)) cleaned else cleaned.take(24).ifEmpty { "APP" }
        }

        internal fun sanitizeEvent(raw: String): String {
            val redacted = SanitizedDiagnosticsBuilder.redactSensitiveText(raw.trim())
            val cleaned = redacted.replace(Regex("""[^A-Za-z0-9_.:-]"""), "_").take(48)
            return if (SAFE_EVENT_REGEX.matches(cleaned)) cleaned else "event"
        }

        internal fun sanitizeAccountTag(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val redacted = SanitizedDiagnosticsBuilder.redactSensitiveText(trimmed)
            if (redacted != trimmed) return "acct_redacted"
            if (trimmed.startsWith("MS4wLjAB", ignoreCase = true) ||
                CONSECUTIVE_DIGITS_10_REGEX.containsMatchIn(trimmed)
            ) {
                return "acct_redacted"
            }
            return if (SAFE_ACCOUNT_TAG_REGEX.matches(trimmed)) trimmed else "acct_redacted"
        }

        internal fun sanitizeDetail(raw: String): String {
            if (raw.isBlank()) return ""
            val bounded = raw.take(MAX_RAW_DETAIL_INPUT_CHARS)
            val singleLine = WHITESPACE_RUN_REGEX.replace(bounded, " ").trim()
            if (singleLine.isEmpty()) return ""
            val kvRedacted = PII_KV_REGEX.replace(singleLine) { mr ->
                "${mr.groupValues[1]}${mr.groupValues[2]}[REDACTED]"
            }
            val redacted = SanitizedDiagnosticsBuilder.redactSensitiveText(kvRedacted).trim()
            return if (redacted.length <= MAX_DETAIL_CHARS) {
                redacted
            } else {
                redacted.take(MAX_DETAIL_CHARS - 1).trimEnd() + "…"
            }
        }

        private fun sanitizePersistedLine(rawLine: String): String {
            val singleLine = WHITESPACE_RUN_REGEX.replace(rawLine.take(MAX_RAW_DETAIL_INPUT_CHARS), " ").trim()
            if (singleLine.isEmpty()) return ""
            val kvRedacted = PII_KV_REGEX.replace(singleLine) { mr ->
                "${mr.groupValues[1]}${mr.groupValues[2]}[REDACTED]"
            }
            val redacted = SanitizedDiagnosticsBuilder.redactSensitiveText(kvRedacted).trim()
            val maxLineLen = MAX_DETAIL_CHARS + 120
            return if (redacted.length <= maxLineLen) redacted else redacted.take(maxLineLen)
        }

        internal fun trimLinesFromHeadToMaxChars(
            lines: List<String>,
            maxChars: Int,
        ): String {
            if (lines.isEmpty() || maxChars <= 0) return ""
            val joined = lines.joinToString("\n")
            if (joined.length <= maxChars) return joined

            // 保留最新记录，从头部整行裁剪，单条超长时尾部保底截断
            val keptReversed = ArrayList<String>(lines.size)
            var currentLen = 0
            for (i in lines.lastIndex downTo 0) {
                val line = lines[i]
                val extra = if (keptReversed.isEmpty()) line.length else line.length + 1
                if (currentLen + extra > maxChars) {
                    break
                }
                keptReversed.add(line)
                currentLen += extra
            }
            if (keptReversed.isEmpty()) {
                return lines.last().takeLast(maxChars)
            }
            keptReversed.reverse()
            return keptReversed.joinToString("\n")
        }
    }
}
