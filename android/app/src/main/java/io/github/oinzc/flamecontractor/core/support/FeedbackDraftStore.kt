package io.github.oinzc.flamecontractor.core.support

import android.content.Context
import android.content.SharedPreferences
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 反馈表单文字与开关草稿（按方案 A 不持久化任何联系方式字段）。
 */
data class FeedbackTextDraft(
    val kind: FeedbackKind = FeedbackKind.BUG,
    val title: String = "",
    val content: String = "",
    val includeEnv: Boolean = true,
    val includeDiagnosticLog: Boolean = true,
) {
    val hasContent: Boolean
        get() = title.isNotBlank() || content.isNotBlank()
}

/**
 * 反馈表单完整持久化草稿快照（含文字、开关与已编码去 EXIF 的截图附件）。
 */
data class FeedbackDraftSnapshot(
    val kind: FeedbackKind = FeedbackKind.BUG,
    val title: String = "",
    val content: String = "",
    val includeEnv: Boolean = true,
    val includeDiagnosticLog: Boolean = true,
    val images: List<EncodedFeedbackImage> = emptyList(),
) {
    val textDraft: FeedbackTextDraft
        get() = FeedbackTextDraft(
            kind = kind,
            title = title,
            content = content,
            includeEnv = includeEnv,
            includeDiagnosticLog = includeDiagnosticLog,
        )

    val hasRestorableContent: Boolean
        get() = title.isNotBlank() || content.isNotBlank() || images.isNotEmpty()
}

/**
 * 合并外部预填草稿（如异常卡片「一键反馈」）与用户已有草稿：
 * 若用户已有手写标题或正文，绝不静默覆盖用户的标题、正文或反馈分类。
 */
fun mergeFeedbackPreFillDraft(
    current: FeedbackTextDraft,
    preFill: FeedbackPreFillDraft,
): FeedbackTextDraft {
    if (current.title.isNotBlank() || current.content.isNotBlank()) {
        return current
    }
    return current.copy(
        kind = preFill.kind,
        title = preFill.title.trim().take(FeedbackSubmitter.MAX_TITLE_LEN),
        content = preFill.content.trim().take(FeedbackSubmitter.MAX_CONTENT_LEN),
        includeDiagnosticLog = if (preFill.attachDiagnosticLog) true else current.includeDiagnosticLog,
    )
}

/**
 * 轻量键值存储抽象，便于生产环境对接 SharedPreferences、单测环境注入纯内存实现。
 */
interface FeedbackDraftStorage {
    fun getString(key: String): String?
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String)
        fun putBoolean(key: String, value: Boolean)
        fun remove(key: String)
        fun clear()
    }
}

class SharedPreferencesFeedbackDraftStorage(
    private val prefs: SharedPreferences,
) : FeedbackDraftStorage {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun edit(block: FeedbackDraftStorage.Editor.() -> Unit) {
        val editor = prefs.edit()
        val adapter = object : FeedbackDraftStorage.Editor {
            override fun putString(key: String, value: String) {
                editor.putString(key, value)
            }

            override fun putBoolean(key: String, value: Boolean) {
                editor.putBoolean(key, value)
            }

            override fun remove(key: String) {
                editor.remove(key)
            }

            override fun clear() {
                editor.clear()
            }
        }
        adapter.block()
        editor.apply()
    }
}

class InMemoryFeedbackDraftStorage(
    initial: Map<String, Any> = emptyMap(),
) : FeedbackDraftStorage {
    private val lock = Any()
    private val entries = LinkedHashMap<String, Any>(initial)

    override fun getString(key: String): String? = synchronized(lock) {
        entries[key] as? String
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = synchronized(lock) {
        (entries[key] as? Boolean) ?: defaultValue
    }

    override fun contains(key: String): Boolean = synchronized(lock) {
        entries.containsKey(key)
    }

    override fun edit(block: FeedbackDraftStorage.Editor.() -> Unit) {
        synchronized(lock) {
            val adapter = object : FeedbackDraftStorage.Editor {
                override fun putString(key: String, value: String) {
                    entries[key] = value
                }

                override fun putBoolean(key: String, value: Boolean) {
                    entries[key] = value
                }

                override fun remove(key: String) {
                    entries.remove(key)
                }

                override fun clear() {
                    entries.clear()
                }
            }
            adapter.block()
        }
    }

    fun snapshotKeys(): Set<String> = synchronized(lock) { entries.keys.toSet() }
}

/**
 * Android 端反馈表单草稿自动持久化仓库：
 * - 文字与开关通过 SharedPreferences（`flame_feedback_draft_prefs`）即时保存；
 * - 已编码去 EXIF 的截图缓存至应用私有目录 `cacheDir/feedback_draft_images/`（元信息 JSON + 字节文件），
 *   严格校验路径防穿越、数量与体积上限；
 * - 按方案 A 不保存、不读取任何联系方式字段，遇到旧版残留键自动清理；
 * - 全部 JSON 编解码纯 Kotlin 实现，不依赖 Android Stub 返回 null 的 `org.json`，纯 JVM 可完整单测。
 */
class FeedbackDraftStore(
    private val storage: FeedbackDraftStorage,
    private val imagesDir: File,
) {
    private val ioLock = Any()

    fun loadTextDraft(): FeedbackTextDraft = synchronized(ioLock) {
        purgeLegacyContactKeysLocked()
        val kind = parseFeedbackKind(storage.getString(KEY_KIND))
        val title = storage.getString(KEY_TITLE).orEmpty().take(FeedbackSubmitter.MAX_TITLE_LEN)
        val content = storage.getString(KEY_CONTENT).orEmpty().take(FeedbackSubmitter.MAX_CONTENT_LEN)
        val includeEnv = storage.getBoolean(KEY_INCLUDE_ENV, true)
        val includeDiagnosticLog = storage.getBoolean(KEY_INCLUDE_DIAGNOSTIC_LOG, true)
        FeedbackTextDraft(
            kind = kind,
            title = title,
            content = content,
            includeEnv = includeEnv,
            includeDiagnosticLog = includeDiagnosticLog,
        )
    }

    fun loadImages(): List<EncodedFeedbackImage> = synchronized(ioLock) {
        readImagesLocked()
    }

    fun loadDraft(): FeedbackDraftSnapshot = synchronized(ioLock) {
        val text = loadTextDraft()
        val images = readImagesLocked()
        FeedbackDraftSnapshot(
            kind = text.kind,
            title = text.title,
            content = text.content,
            includeEnv = text.includeEnv,
            includeDiagnosticLog = text.includeDiagnosticLog,
            images = images,
        )
    }

    fun saveTextDraft(
        kind: FeedbackKind,
        title: String,
        content: String,
        includeEnv: Boolean,
        includeDiagnosticLog: Boolean,
    ) {
        synchronized(ioLock) {
            storage.edit {
                putString(KEY_KIND, kind.name)
                putString(KEY_TITLE, title.take(FeedbackSubmitter.MAX_TITLE_LEN))
                putString(KEY_CONTENT, content.take(FeedbackSubmitter.MAX_CONTENT_LEN))
                putBoolean(KEY_INCLUDE_ENV, includeEnv)
                putBoolean(KEY_INCLUDE_DIAGNOSTIC_LOG, includeDiagnosticLog)
                LEGACY_CONTACT_KEYS.forEach { remove(it) }
            }
        }
    }

    fun saveTextDraft(draft: FeedbackTextDraft) {
        saveTextDraft(
            kind = draft.kind,
            title = draft.title,
            content = draft.content,
            includeEnv = draft.includeEnv,
            includeDiagnosticLog = draft.includeDiagnosticLog,
        )
    }

    fun saveImages(images: List<EncodedFeedbackImage>) {
        synchronized(ioLock) {
            writeImagesLocked(images)
        }
    }

    fun clearDraft() {
        synchronized(ioLock) {
            storage.edit { clear() }
            clearImagesDirLocked()
        }
    }

    /**
     * 在全局串行 IO 调度器上加载缓存图片，确保排在任意尚未落盘的 `saveImagesAsync` / `clearDraftAsync` 之后。
     */
    suspend fun loadImagesAsync(): List<EncodedFeedbackImage> = withContext(serialIoDispatcher) {
        loadImages()
    }

    /**
     * 在独立串行作用域持久化截图，避免用户添加/删除截图后立即返回导致页面协程被取消而丢图。
     */
    fun saveImagesAsync(images: List<EncodedFeedbackImage>) {
        val snapshot = images.take(FeedbackSubmitter.MAX_IMAGE_COUNT).toList()
        serialIoScope.launch {
            runCatching { saveImages(snapshot) }
        }
    }

    /**
     * 立即清空文字偏好，并在串行 IO 作用域安全清理缓存截图目录（不受页面退出取消影响）。
     */
    fun clearDraftAsync() {
        synchronized(ioLock) {
            storage.edit { clear() }
        }
        serialIoScope.launch {
            runCatching {
                synchronized(ioLock) {
                    clearImagesDirLocked()
                }
            }
        }
    }

    private fun purgeLegacyContactKeysLocked() {
        val presentLegacyKeys = LEGACY_CONTACT_KEYS.filter { storage.contains(it) }
        if (presentLegacyKeys.isNotEmpty()) {
            storage.edit {
                presentLegacyKeys.forEach { remove(it) }
            }
        }
    }

    private fun writeImagesLocked(images: List<EncodedFeedbackImage>) {
        if (images.isEmpty()) {
            clearImagesDirLocked()
            return
        }

        val bounded = images.take(FeedbackSubmitter.MAX_IMAGE_COUNT)
        val validItems = mutableListOf<PreparedImageRecord>()
        val usedFileNames = mutableSetOf<String>()
        val usedIds = mutableSetOf<String>()

        bounded.forEachIndexed { index, item ->
            val rawBytes = resolveValidImageBytes(item) ?: return@forEachIndexed
            val verifiedMime = FeedbackImageEncoderUtils.detectImageMimeFromMagicBytes(rawBytes)
                ?: return@forEachIndexed
            if (verifiedMime !in FeedbackSubmitter.ALLOWED_IMAGE_MIMES) return@forEachIndexed

            val ext = extensionForMime(verifiedMime)
            val rawId = item.id.trim().take(64).ifEmpty { UUID.randomUUID().toString() }
            val uniqueId = if (usedIds.add(rawId)) rawId else "${rawId.take(48)}_${index + 1}"
            val safeToken = uniqueId
                .replace(Regex("[^A-Za-z0-9_-]"), "")
                .take(24)
                .ifEmpty { "slot_${index + 1}" }
            var candidateFileName = "img_${index}_${safeToken}.$ext"
            var suffix = 1
            while (!usedFileNames.add(candidateFileName)) {
                candidateFileName = "img_${index}_${safeToken}_${suffix++}.$ext"
            }

            val safeAttachmentName = FeedbackImageEncoderUtils.sanitizeAttachmentName(
                rawName = item.attachment.name,
                indexHint = index + 1,
                mimeType = verifiedMime,
            )
            validItems += PreparedImageRecord(
                id = uniqueId,
                name = safeAttachmentName,
                mimeType = verifiedMime,
                fileName = candidateFileName,
                bytes = rawBytes,
                widthPx = item.widthPx.coerceAtLeast(0),
                heightPx = item.heightPx.coerceAtLeast(0),
            )
        }

        if (validItems.isEmpty()) {
            clearImagesDirLocked()
            return
        }

        if (!imagesDir.exists() && !imagesDir.mkdirs()) {
            return
        }
        if (!imagesDir.isDirectory) {
            return
        }

        val writtenRecords = mutableListOf<DraftImageMetaEntry>()
        val activeFiles = mutableSetOf(METADATA_FILE_NAME)

        for (record in validItems) {
            val targetFile = resolveSafeCacheFile(imagesDir, record.fileName) ?: continue
            val wrote = writeBytesAtomically(imagesDir, targetFile, record.bytes)
            if (wrote) {
                activeFiles += record.fileName
                writtenRecords += DraftImageMetaEntry(
                    id = record.id,
                    name = record.name,
                    mimeType = record.mimeType,
                    fileName = record.fileName,
                    sizeBytes = record.bytes.size,
                    widthPx = record.widthPx,
                    heightPx = record.heightPx,
                )
            }
        }

        if (writtenRecords.isEmpty()) {
            clearImagesDirLocked()
            return
        }

        val metaFile = File(imagesDir, METADATA_FILE_NAME)
        val metaJson = FeedbackDraftMetaJson.encode(writtenRecords)
        val metaWritten = writeBytesAtomically(imagesDir, metaFile, metaJson.toByteArray(Charsets.UTF_8))
        if (!metaWritten) {
            return
        }

        // 清理已移除或旧版本遗留的缓存文件
        imagesDir.listFiles()?.forEach { child ->
            if (child.name !in activeFiles) {
                runCatching { child.deleteRecursively() }
            }
        }
    }

    private fun readImagesLocked(): List<EncodedFeedbackImage> {
        if (!imagesDir.isDirectory) return emptyList()
        val metaFile = File(imagesDir, METADATA_FILE_NAME)
        if (!metaFile.isFile || isSymbolicLinkSafe(metaFile)) return emptyList()
        val metaLen = metaFile.length()
        if (metaLen <= 0L || metaLen > MAX_METADATA_BYTES) return emptyList()

        val rawJson = runCatching {
            FileInputStream(metaFile).use { input ->
                FeedbackImageEncoderUtils.readBoundedBytes(input, MAX_METADATA_BYTES)
                    .toString(Charsets.UTF_8)
            }
        }.getOrNull() ?: return emptyList()

        val entries = FeedbackDraftMetaJson.decodeOrEmpty(rawJson)
        if (entries.isEmpty()) return emptyList()

        val restored = mutableListOf<EncodedFeedbackImage>()
        val seenIds = mutableSetOf<String>()
        val seenFileNames = mutableSetOf<String>()

        for (entry in entries) {
            if (restored.size >= FeedbackSubmitter.MAX_IMAGE_COUNT) break
            if (!seenFileNames.add(entry.fileName)) continue

            val file = resolveSafeCacheFile(imagesDir, entry.fileName) ?: continue
            if (!file.isFile || isSymbolicLinkSafe(file)) continue
            val fileLen = file.length()
            if (fileLen <= 0L || fileLen > FeedbackSubmitter.MAX_IMAGE_BYTES) continue

            val bytes = runCatching {
                FileInputStream(file).use { input ->
                    FeedbackImageEncoderUtils.readBoundedBytes(
                        input = input,
                        maxBytes = FeedbackSubmitter.MAX_IMAGE_BYTES,
                    )
                }
            }.getOrNull() ?: continue

            val verifiedMime = FeedbackImageEncoderUtils.detectImageMimeFromMagicBytes(bytes) ?: continue
            if (verifiedMime !in FeedbackSubmitter.ALLOWED_IMAGE_MIMES) continue

            val candidateId = entry.id.trim().take(64).ifEmpty { UUID.randomUUID().toString() }
            val uniqueId = if (seenIds.add(candidateId)) {
                candidateId
            } else {
                var fallback = "${candidateId.take(48)}_${restored.size + 1}"
                while (!seenIds.add(fallback)) {
                    fallback = UUID.randomUUID().toString()
                }
                fallback
            }

            val safeName = FeedbackImageEncoderUtils.sanitizeAttachmentName(
                rawName = entry.name,
                indexHint = restored.size + 1,
                mimeType = verifiedMime,
            )
            val base64 = FeedbackImageEncoderUtils.encodeBytesToBase64(bytes)

            restored += EncodedFeedbackImage(
                id = uniqueId,
                attachment = FeedbackImageAttachment(
                    name = safeName,
                    mimeType = verifiedMime,
                    dataBase64 = base64,
                    sizeBytes = bytes.size,
                ),
                previewBytes = bytes,
                widthPx = entry.widthPx.coerceAtLeast(0),
                heightPx = entry.heightPx.coerceAtLeast(0),
            )
        }
        return restored
    }

    private fun clearImagesDirLocked() {
        if (!imagesDir.exists()) return
        runCatching {
            imagesDir.listFiles()?.forEach { child ->
                child.deleteRecursively()
            }
            imagesDir.delete()
        }
    }

    private fun resolveValidImageBytes(item: EncodedFeedbackImage): ByteArray? {
        val direct = item.previewBytes
        val bytes = when {
            direct.isNotEmpty() -> direct
            item.attachment.dataBase64.isNotBlank() -> runCatching {
                Base64.getDecoder().decode(item.attachment.dataBase64.trim())
            }.getOrNull()
            else -> null
        } ?: return null
        if (bytes.isEmpty() || bytes.size > FeedbackSubmitter.MAX_IMAGE_BYTES) return null
        return runCatching {
            FeedbackImageEncoderUtils.readBoundedBytes(
                input = ByteArrayInputStream(bytes),
                maxBytes = FeedbackSubmitter.MAX_IMAGE_BYTES,
            )
        }.getOrNull()
    }

    private data class PreparedImageRecord(
        val id: String,
        val name: String,
        val mimeType: String,
        val fileName: String,
        val bytes: ByteArray,
        val widthPx: Int,
        val heightPx: Int,
    )

    companion object {
        const val PREFS_NAME: String = "flame_feedback_draft_prefs"
        const val IMAGES_DIR_NAME: String = "feedback_draft_images"
        internal const val METADATA_FILE_NAME: String = "images_meta.json"
        internal const val MAX_METADATA_BYTES: Int = 64 * 1024

        internal const val KEY_KIND: String = "kind"
        internal const val KEY_TITLE: String = "title"
        internal const val KEY_CONTENT: String = "content"
        internal const val KEY_INCLUDE_ENV: String = "include_env"
        internal const val KEY_INCLUDE_DIAGNOSTIC_LOG: String = "include_diagnostic_log"
        internal val LEGACY_CONTACT_KEYS: Set<String> = setOf("contact", "contact_input")

        private val SAFE_CACHE_FILENAME_REGEX = Regex("^[A-Za-z0-9._-]{1,120}$")

        private val serialIoDispatcher = Dispatchers.IO.limitedParallelism(1)
        private val serialIoScope = CoroutineScope(SupervisorJob() + serialIoDispatcher)

        fun fromContext(context: Context): FeedbackDraftStore {
            val appContext = context.applicationContext ?: context
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val imagesDir = File(appContext.cacheDir, IMAGES_DIR_NAME)
            return FeedbackDraftStore(
                storage = SharedPreferencesFeedbackDraftStorage(prefs),
                imagesDir = imagesDir,
            )
        }

        internal fun parseFeedbackKind(raw: String?): FeedbackKind {
            val normalized = raw?.trim().orEmpty()
            if (normalized.isEmpty()) return FeedbackKind.BUG
            return FeedbackKind.entries.firstOrNull { kind ->
                kind.name.equals(normalized, ignoreCase = true) ||
                    kind.wireValue.equals(normalized, ignoreCase = true)
            } ?: FeedbackKind.BUG
        }

        internal fun isSafeCacheFileName(fileName: String?): Boolean {
            if (fileName.isNullOrBlank()) return false
            if (fileName == "." || fileName == ".." || fileName == METADATA_FILE_NAME) return false
            if (fileName.contains("..") || fileName.contains('/') || fileName.contains('\\')) return false
            return SAFE_CACHE_FILENAME_REGEX.matches(fileName)
        }

        internal fun resolveSafeCacheFile(baseDir: File, fileName: String?): File? {
            if (!isSafeCacheFileName(fileName)) return null
            val safeName = fileName!!
            return runCatching {
                val canonicalBase = baseDir.canonicalFile
                val candidate = File(canonicalBase, safeName)
                val canonicalCandidate = candidate.canonicalFile
                val basePrefix = canonicalBase.path.let { path ->
                    if (path.endsWith(File.separator)) path else path + File.separator
                }
                if (canonicalCandidate.path.startsWith(basePrefix) &&
                    canonicalCandidate.parentFile == canonicalBase
                ) {
                    canonicalCandidate
                } else {
                    null
                }
            }.getOrNull()
        }

        private fun isSymbolicLinkSafe(file: File): Boolean = runCatching {
            Files.isSymbolicLink(file.toPath())
        }.getOrDefault(false)

        private fun writeBytesAtomically(dir: File, targetFile: File, bytes: ByteArray): Boolean {
            val tmpFile = File(dir, ".tmp_${targetFile.name}_${UUID.randomUUID().toString().take(8)}")
            return try {
                tmpFile.writeBytes(bytes)
                val moved = runCatching {
                    Files.move(
                        tmpFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    true
                }.getOrDefault(false)
                if (!moved) {
                    targetFile.writeBytes(bytes)
                    tmpFile.delete()
                }
                true
            } catch (_: Throwable) {
                runCatching { tmpFile.delete() }
                false
            }
        }

        private fun extensionForMime(mimeType: String): String = when (mimeType.trim().lowercase(Locale.ROOT)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
    }
}

internal data class DraftImageMetaEntry(
    val id: String,
    val name: String,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Int,
    val widthPx: Int,
    val heightPx: Int,
)

/**
 * 纯 Kotlin 轻量 JSON 编解码器，专用于反馈草稿截图元信息 `images_meta.json`。
 */
internal object FeedbackDraftMetaJson {

    private const val SCHEMA_VERSION: Int = 1
    private const val MAX_DEPTH: Int = 16

    fun encode(entries: List<DraftImageMetaEntry>): String = buildString {
        append("{\"version\":")
        append(SCHEMA_VERSION)
        append(",\"images\":[")
        entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append('{')
            append("\"id\":"); appendJsonString(entry.id); append(',')
            append("\"name\":"); appendJsonString(entry.name); append(',')
            append("\"mimeType\":"); appendJsonString(entry.mimeType); append(',')
            append("\"fileName\":"); appendJsonString(entry.fileName); append(',')
            append("\"sizeBytes\":"); append(entry.sizeBytes.coerceAtLeast(0)); append(',')
            append("\"widthPx\":"); append(entry.widthPx.coerceAtLeast(0)); append(',')
            append("\"heightPx\":"); append(entry.heightPx.coerceAtLeast(0))
            append('}')
        }
        append("]}")
    }

    fun decodeOrEmpty(rawJson: String?): List<DraftImageMetaEntry> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return runCatching {
            val parser = JsonParser(rawJson)
            val parsed = parser.parseValue(depth = 0)
            parser.skipWhitespace()
            if (!parser.isAtEnd()) return emptyList()
            @Suppress("UNCHECKED_CAST")
            val root = parsed as? Map<String, Any?> ?: return emptyList()
            val imagesRaw = root["images"] as? List<*> ?: return emptyList()
            buildList {
                for (item in imagesRaw) {
                    @Suppress("UNCHECKED_CAST")
                    val obj = item as? Map<String, Any?> ?: continue
                    val fileName = obj["fileName"] as? String ?: continue
                    val id = (obj["id"] as? String).orEmpty()
                    val name = (obj["name"] as? String).orEmpty()
                    val mimeType = (obj["mimeType"] as? String).orEmpty()
                    val sizeBytes = (obj["sizeBytes"] as? Number)?.toInt() ?: 0
                    val widthPx = (obj["widthPx"] as? Number)?.toInt() ?: 0
                    val heightPx = (obj["heightPx"] as? Number)?.toInt() ?: 0
                    add(
                        DraftImageMetaEntry(
                            id = id,
                            name = name,
                            mimeType = mimeType,
                            fileName = fileName,
                            sizeBytes = sizeBytes,
                            widthPx = widthPx,
                            heightPx = heightPx,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

    private class JsonParser(private val src: String) {
        private var pos: Int = 0

        fun isAtEnd(): Boolean = pos >= src.length

        fun skipWhitespace() {
            while (pos < src.length) {
                val ch = src[pos]
                if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                    pos++
                } else {
                    break
                }
            }
        }

        fun parseValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw IllegalArgumentException("JSON depth exceeded")
            skipWhitespace()
            if (isAtEnd()) throw IllegalArgumentException("Unexpected end of JSON")
            return when (val ch = src[pos]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> expectLiteral("true", true)
                'f' -> expectLiteral("false", false)
                'n' -> expectLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected char: $ch")
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            expectChar('{')
            skipWhitespace()
            val result = LinkedHashMap<String, Any?>()
            if (!isAtEnd() && src[pos] == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expectChar(':')
                val value = parseValue(depth)
                result[key] = value
                skipWhitespace()
                if (isAtEnd()) throw IllegalArgumentException("Unterminated object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            expectChar('[')
            skipWhitespace()
            val result = ArrayList<Any?>()
            if (!isAtEnd() && src[pos] == ']') {
                pos++
                return result
            }
            while (true) {
                result.add(parseValue(depth))
                skipWhitespace()
                if (isAtEnd()) throw IllegalArgumentException("Unterminated array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            expectChar('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        if (pos >= src.length) throw IllegalArgumentException("Unterminated escape")
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
                                if (pos + 4 > src.length) throw IllegalArgumentException("Truncated unicode escape")
                                val hex = src.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid unicode escape: $hex")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape: \\$esc")
                        }
                    }
                    ch.code < 0x20 -> throw IllegalArgumentException("Unescaped control char")
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (src[pos] == '-') pos++
            if (isAtEnd() || src[pos] !in '0'..'9') throw IllegalArgumentException("Invalid number")
            if (src[pos] == '0') {
                pos++
            } else {
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            var isFloating = false
            if (pos < src.length && src[pos] == '.') {
                isFloating = true
                pos++
                if (isAtEnd() || src[pos] !in '0'..'9') throw IllegalArgumentException("Invalid decimal")
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloating = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                if (isAtEnd() || src[pos] !in '0'..'9') throw IllegalArgumentException("Invalid exponent")
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            val token = src.substring(start, pos)
            return if (isFloating) {
                token.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid double: $token")
            } else {
                token.toLongOrNull() ?: token.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid number: $token")
            }
        }

        private fun expectLiteral(literal: String, value: Any?): Any? {
            if (!src.startsWith(literal, startIndex = pos)) {
                throw IllegalArgumentException("Expected $literal")
            }
            pos += literal.length
            return value
        }

        private fun expectChar(expected: Char) {
            if (isAtEnd() || src[pos] != expected) {
                throw IllegalArgumentException("Expected '$expected'")
            }
            pos++
        }
    }
}
