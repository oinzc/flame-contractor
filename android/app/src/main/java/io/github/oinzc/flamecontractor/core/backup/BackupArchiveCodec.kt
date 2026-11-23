package io.github.oinzc.flamecontractor.core.backup

import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.execution.ExecJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.time.LocalDate
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BundledTemplateImage(
    val bytes: ByteArray,
    val mimeType: String,
    val extension: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BundledTemplateImage) return false
        return mimeType == other.mimeType &&
            extension == other.extension &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + extension.hashCode()
        return result
    }
}

data class BackupArchivePayload(
    val rawJson: String,
    val bundledImagesByTemplateId: Map<String, BundledTemplateImage> = emptyMap(),
    val isZipArchive: Boolean = false,
)

data class BackupZipExportResult(
    val bundledImageCount: Int = 0,
    val skippedImageCount: Int = 0,
)

enum class DetectedImageFormat(
    val extension: String,
    val mimeType: String,
    val maxBytes: Int,
) {
    JPEG(extension = "jpg", mimeType = "image/jpeg", maxBytes = BackupArchiveCodec.MAX_IMAGE_BYTES),
    PNG(extension = "png", mimeType = "image/png", maxBytes = BackupArchiveCodec.MAX_IMAGE_BYTES),
    WEBP(extension = "webp", mimeType = "image/webp", maxBytes = BackupArchiveCodec.MAX_IMAGE_BYTES),
    GIF(extension = "gif", mimeType = "image/gif", maxBytes = BackupArchiveCodec.MAX_GIF_BYTES),
}

/**
 * 跨端 `.zip` 完整备份归档编解码器（Python 后端 ↔ Web 前端 ↔ Android 100% 互通）：
 * - 标准无密码 PKZIP `DEFLATED` 归档，包含 `backup.json`、`manifest.json` 与 `template_images/<templateId>.<ext>`；
 * - 导出前通过真实文件魔数校验与体积上限，严禁打包非图片文件，单图缺失仅跳过不中断导出；
 * - 导入时自动识别 `PK\x03\x04` 归档与纯 `.json`，严格执行 ZipSlip 白名单与流式解压硬上限防 ZipBomb。
 */
object BackupArchiveCodec {

    const val ARCHIVE_FORMAT_VERSION = 1
    const val BACKUP_JSON_ENTRY = "backup.json"
    const val MANIFEST_JSON_ENTRY = "manifest.json"
    const val TEMPLATE_IMAGES_DIR_ENTRY = "template_images/"

    const val MAX_ARCHIVE_INPUT_BYTES = 60 * 1024 * 1024
    const val MAX_JSON_BYTES = BackupCodec.MAX_BACKUP_BYTES
    const val MAX_MANIFEST_BYTES = 1 * 1024 * 1024
    const val MAX_IMAGE_BYTES = 15 * 1024 * 1024
    const val MAX_GIF_BYTES = 5 * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 80L * 1024L * 1024L
    const val MAX_ZIP_ENTRIES = 200

    val SAFE_TEMPLATE_ID_REGEX = Regex("""^[A-Za-z0-9_-]{1,80}$""")
    val TEMPLATE_IMAGE_ENTRY_REGEX = Regex("""^template_images/([A-Za-z0-9_-]{1,80})\.(jpg|jpeg|png|webp|gif)$""")

    private const val IO_BUFFER_SIZE = 16 * 1024

    fun defaultZipBackupFileName(date: LocalDate = LocalDate.now()): String =
        "flame-contractor-backup-$date.zip"

    fun detectImageFormat(bytes: ByteArray): DetectedImageFormat? {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
        ) {
            return DetectedImageFormat.PNG
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return DetectedImageFormat.JPEG
        }
        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte()
        ) {
            return DetectedImageFormat.GIF
        }
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return DetectedImageFormat.WEBP
        }
        return null
    }

    fun exportZip(
        outputStream: OutputStream,
        jsonContent: String,
        templates: List<MessageTemplateEntity>,
        resolveImageFile: (String?) -> File?,
    ): BackupZipExportResult {
        val bundledEntries = linkedMapOf<String, Pair<String, ByteArray>>()
        var skippedImageCount = 0

        for (tpl in templates) {
            if (tpl.type != TemplateType.IMAGE) continue
            if (!SAFE_TEMPLATE_ID_REGEX.matches(tpl.id)) {
                skippedImageCount++
                continue
            }
            val file = try {
                resolveImageFile(tpl.imagePath)
            } catch (_: Throwable) {
                null
            }
            if (file == null || !file.isFile) {
                skippedImageCount++
                continue
            }
            val fileLen = file.length()
            if (fileLen <= 0L || fileLen > MAX_IMAGE_BYTES.toLong()) {
                skippedImageCount++
                continue
            }
            val bytes = try {
                FileInputStream(file).use { input ->
                    readStreamExactBounded(
                        input = input,
                        maxBytes = MAX_IMAGE_BYTES,
                        errorMessage = "图片文件超过 15 MB 上限",
                    )
                }
            } catch (_: Throwable) {
                skippedImageCount++
                continue
            }
            val detected = detectImageFormat(bytes)
            if (detected == null || bytes.size > detected.maxBytes) {
                skippedImageCount++
                continue
            }
            val entryPath = "template_images/${tpl.id}.${detected.extension}"
            bundledEntries[tpl.id] = entryPath to bytes
        }

        val imagesManifest = linkedMapOf<String, String>()
        for ((templateId, entryPair) in bundledEntries) {
            imagesManifest[templateId] = entryPair.first
        }
        val manifestJson = ExecJson.encode(
            linkedMapOf<String, Any?>(
                "format_version" to ARCHIVE_FORMAT_VERSION,
                "app" to BackupCodec.BACKUP_APP_ID,
                "images" to imagesManifest,
            ),
        )

        val zos = ZipOutputStream(outputStream, Charsets.UTF_8)
        zos.setMethod(ZipOutputStream.DEFLATED)

        writeZipEntry(zos, BACKUP_JSON_ENTRY, jsonContent.toByteArray(Charsets.UTF_8))
        writeZipEntry(zos, MANIFEST_JSON_ENTRY, manifestJson.toByteArray(Charsets.UTF_8))
        for ((_, entryPair) in bundledEntries) {
            writeZipEntry(zos, entryPair.first, entryPair.second)
        }

        zos.finish()
        zos.flush()

        return BackupZipExportResult(
            bundledImageCount = bundledEntries.size,
            skippedImageCount = skippedImageCount,
        )
    }

    fun readArchiveOrJson(inputStream: InputStream): BackupArchivePayload {
        val boundedInput = BoundedInputStream(
            delegate = inputStream,
            maxBytes = MAX_ARCHIVE_INPUT_BYTES.toLong(),
            errorMessage = "备份文件超过 60 MB 上限，已停止读取以防止内存溢出",
        )
        val header = ByteArray(4)
        var headerLen = 0
        while (headerLen < header.size) {
            val read = boundedInput.read(header, headerLen, header.size - headerLen)
            if (read < 0) break
            if (read == 0) continue
            headerLen += read
        }
        if (headerLen == 0) {
            throw IllegalArgumentException("备份文件内容为空")
        }

        if (isZipEmptyArchiveHeader(header, headerLen)) {
            throw IllegalArgumentException("备份压缩包为空，缺少 backup.json")
        }

        val combined = SequenceInputStream(
            ByteArrayInputStream(header, 0, headerLen),
            boundedInput,
        )

        if (!isZipLocalFileHeader(header, headerLen)) {
            val rawBytes = readStreamExactBounded(
                input = combined,
                maxBytes = MAX_JSON_BYTES,
                errorMessage = "备份 JSON 文件超过 20 MB 上限，已停止读取以防止内存溢出",
            )
            return BackupArchivePayload(
                rawJson = rawBytes.toString(Charsets.UTF_8),
                bundledImagesByTemplateId = emptyMap(),
                isZipArchive = false,
            )
        }

        return parseZipArchive(combined = combined, boundedInput = boundedInput)
    }

    private fun parseZipArchive(
        combined: InputStream,
        boundedInput: BoundedInputStream,
    ): BackupArchivePayload {
        var entryCount = 0
        var totalUncompressedBytes = 0L
        var backupJsonText: String? = null
        var manifestJsonText: String? = null
        val seenNames = HashSet<String>()
        val rawImagesByPath = LinkedHashMap<String, BundledTemplateImage>()

        try {
            val zis = ZipInputStream(combined, Charsets.UTF_8)
            while (true) {
                val entry = zis.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw IllegalArgumentException("备份压缩包条目数超过 $MAX_ZIP_ENTRIES 上限，已拒绝解析")
                }

                val name = entry.name
                validateEntryNameSafety(name)

                if (entry.isDirectory) {
                    if (name != TEMPLATE_IMAGES_DIR_ENTRY) {
                        throw IllegalArgumentException("备份压缩包包含未授权目录条目：$name")
                    }
                    zis.closeEntry()
                    continue
                }

                if (!seenNames.add(name)) {
                    throw IllegalArgumentException("备份压缩包包含重复条目：$name")
                }

                when {
                    name == BACKUP_JSON_ENTRY -> {
                        val (bytes, nextTotal) = readZipEntryBounded(
                            zis = zis,
                            entryMaxBytes = MAX_JSON_BYTES,
                            currentTotalBytes = totalUncompressedBytes,
                            entrySizeError = "压缩包内 backup.json 超过 20 MB 上限，已拒绝解析",
                        )
                        totalUncompressedBytes = nextTotal
                        backupJsonText = bytes.toString(Charsets.UTF_8)
                    }

                    name == MANIFEST_JSON_ENTRY -> {
                        val (bytes, nextTotal) = readZipEntryBounded(
                            zis = zis,
                            entryMaxBytes = MAX_MANIFEST_BYTES,
                            currentTotalBytes = totalUncompressedBytes,
                            entrySizeError = "压缩包内 manifest.json 超过 1 MB 上限，已拒绝解析",
                        )
                        totalUncompressedBytes = nextTotal
                        manifestJsonText = bytes.toString(Charsets.UTF_8)
                    }

                    else -> {
                        val match = TEMPLATE_IMAGE_ENTRY_REGEX.matchEntire(name)
                            ?: throw IllegalArgumentException("备份压缩包包含未授权条目：$name")
                        val ext = match.groupValues[2].lowercase(Locale.ROOT)
                        val isGifExt = ext == "gif"
                        val entryCap = if (isGifExt) MAX_GIF_BYTES else MAX_IMAGE_BYTES
                        val (bytes, nextTotal) = readZipEntryBounded(
                            zis = zis,
                            entryMaxBytes = entryCap,
                            currentTotalBytes = totalUncompressedBytes,
                            entrySizeError = if (isGifExt) {
                                "压缩包内 GIF 图片「$name」超过 5 MB 上限，已拒绝解析"
                            } else {
                                "压缩包内图片「$name」超过 15 MB 上限，已拒绝解析"
                            },
                        )
                        totalUncompressedBytes = nextTotal
                        if (bytes.isNotEmpty()) {
                            val detected = detectImageFormat(bytes)
                            if (detected != null) {
                                if (bytes.size > detected.maxBytes) {
                                    throw IllegalArgumentException(
                                        "压缩包内图片「$name」超过格式体积上限（GIF <= 5 MB，静态图 <= 15 MB）",
                                    )
                                }
                                rawImagesByPath[name] = BundledTemplateImage(
                                    bytes = bytes,
                                    mimeType = detected.mimeType,
                                    extension = detected.extension,
                                )
                            }
                        }
                    }
                }

                zis.closeEntry()
            }

            // 排空底层流中剩余字节（如中央目录及尾部数据），确保整个压缩包原始体积 <= 60 MB
            val discard = ByteArray(IO_BUFFER_SIZE)
            while (boundedInput.read(discard) >= 0) {
                // BoundedInputStream 会在超过 60 MB 时立即抛出 IllegalArgumentException
            }
        } catch (iae: IllegalArgumentException) {
            throw iae
        } catch (ioe: IOException) {
            throw IllegalArgumentException(
                "备份压缩包已损坏或无法解压：${ioe.message ?: ioe.javaClass.simpleName}",
                ioe,
            )
        }

        val rawJson = backupJsonText
            ?: throw IllegalArgumentException("备份压缩包缺少 backup.json")

        val bundledByTemplateId = resolveBundledImagesMap(
            manifestJsonText = manifestJsonText,
            rawImagesByPath = rawImagesByPath,
        )

        return BackupArchivePayload(
            rawJson = rawJson,
            bundledImagesByTemplateId = bundledByTemplateId,
            isZipArchive = true,
        )
    }

    private fun resolveBundledImagesMap(
        manifestJsonText: String?,
        rawImagesByPath: Map<String, BundledTemplateImage>,
    ): Map<String, BundledTemplateImage> {
        val resolved = LinkedHashMap<String, BundledTemplateImage>()
        val mappedPaths = HashSet<String>()

        if (!manifestJsonText.isNullOrBlank()) {
            val manifestObj = try {
                ExecJson.parseObject(manifestJsonText)
            } catch (exc: Exception) {
                throw IllegalArgumentException("备份压缩包 manifest.json 格式无效", exc)
            }
            val rawFormatVer = manifestObj["format_version"] ?: manifestObj["formatVersion"]
            if (rawFormatVer != null) {
                val version = when (rawFormatVer) {
                    is Int -> rawFormatVer
                    is Long -> rawFormatVer.toInt()
                    else -> throw IllegalArgumentException("不支持的备份归档格式版本：$rawFormatVer")
                }
                if (version != ARCHIVE_FORMAT_VERSION) {
                    throw IllegalArgumentException("不支持的备份归档格式版本：$version")
                }
            }
            @Suppress("UNCHECKED_CAST")
            val manifestImages = manifestObj["images"] as? Map<String, Any?>
            if (manifestImages != null) {
                for ((templateId, rawPath) in manifestImages) {
                    val path = (rawPath as? String)?.trim() ?: continue
                    if (!TEMPLATE_IMAGE_ENTRY_REGEX.matches(path)) continue
                    val image = rawImagesByPath[path] ?: continue
                    resolved[templateId] = image
                    mappedPaths.add(path)
                }
            }
        }

        for ((path, image) in rawImagesByPath) {
            if (path in mappedPaths) continue
            val stem = TEMPLATE_IMAGE_ENTRY_REGEX.matchEntire(path)?.groupValues?.getOrNull(1) ?: continue
            resolved.putIfAbsent(stem, image)
        }

        return resolved
    }

    private fun validateEntryNameSafety(name: String) {
        if (name.isEmpty()) {
            throw IllegalArgumentException("备份压缩包包含空条目名")
        }
        if (name.startsWith('/') || name.startsWith('\\') || name.contains('\\') || name.contains(':')) {
            throw IllegalArgumentException("备份压缩包包含非法路径条目：$name")
        }
        if (name.contains("..")) {
            throw IllegalArgumentException("备份压缩包检测到路径穿越风险条目：$name")
        }
        for (ch in name) {
            if (ch.code < 0x20 || ch.code == 0x7F) {
                throw IllegalArgumentException("备份压缩包条目名包含非法控制字符")
            }
        }
    }

    private fun readZipEntryBounded(
        zis: ZipInputStream,
        entryMaxBytes: Int,
        currentTotalBytes: Long,
        entrySizeError: String,
    ): Pair<ByteArray, Long> {
        val buffer = ByteArray(IO_BUFFER_SIZE)
        val out = ByteArrayOutputStream()
        var entryRead = 0L
        var totalRead = currentTotalBytes
        val entryLimit = entryMaxBytes.toLong()

        while (true) {
            val read = zis.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            entryRead += read
            totalRead += read
            if (entryRead > entryLimit) {
                throw IllegalArgumentException(entrySizeError)
            }
            if (totalRead > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw IllegalArgumentException("备份压缩包解压后总大小超过 80 MB 上限，已拒绝解析")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray() to totalRead
    }

    private fun readStreamExactBounded(
        input: InputStream,
        maxBytes: Int,
        errorMessage: String,
    ): ByteArray {
        val buffer = ByteArray(IO_BUFFER_SIZE)
        val out = ByteArrayOutputStream()
        var total = 0L
        val limit = maxBytes.toLong()
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > limit) {
                throw IllegalArgumentException(errorMessage)
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun writeZipEntry(
        zos: ZipOutputStream,
        entryName: String,
        bytes: ByteArray,
    ) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun isZipLocalFileHeader(header: ByteArray, length: Int): Boolean =
        length >= 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() &&
            header[3] == 0x04.toByte()

    private fun isZipEmptyArchiveHeader(header: ByteArray, length: Int): Boolean =
        length >= 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] == 0x05.toByte() &&
            header[3] == 0x06.toByte()

    private class BoundedInputStream(
        delegate: InputStream,
        private val maxBytes: Long,
        private val errorMessage: String,
    ) : FilterInputStream(delegate) {
        private var bytesRead = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) {
                bytesRead++
                if (bytesRead > maxBytes) {
                    throw IllegalArgumentException(errorMessage)
                }
            }
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) {
                bytesRead += count
                if (bytesRead > maxBytes) {
                    throw IllegalArgumentException(errorMessage)
                }
            }
            return count
        }
    }
}
