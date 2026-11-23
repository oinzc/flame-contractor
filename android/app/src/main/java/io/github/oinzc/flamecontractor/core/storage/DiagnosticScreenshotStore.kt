package io.github.oinzc.flamecontractor.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.webkit.WebView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 离屏执行失败 / `UNKNOWN_REQUIRES_REVIEW` 现场诊断截图沙盒存储：
 * - 严格存放于应用私域 `filesDir/diagnostic_screenshots/`，不进入系统相册（MediaStore）、
 *   不纳入脱敏诊断导出包，且受 `allowBackup="false"` 保护；
 * - 文件命名确定且做路径穿越消毒：`attempt_<id>.png` 与 `job_<id>_latest.png`；
 * - 自动执行 7 天 + 最多 60 张双维度淘汰清理，全程防御性兜底，绝不影响任务落库。
 */
class DiagnosticScreenshotStore(
    private val screenshotsDir: File,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: Context) : this(
        screenshotsDir = File(
            (context.applicationContext ?: context).filesDir,
            SCREENSHOTS_DIR_NAME,
        ),
    )

    fun resolveAttemptFile(attemptId: String): File? {
        if (attemptId.isBlank()) return null
        return resolveExistingInRoot(attemptFileName(attemptId))
    }

    fun resolveJobLatestFile(jobId: String): File? {
        if (jobId.isBlank()) return null
        return resolveExistingInRoot(jobLatestFileName(jobId))
    }

    fun resolveForAttemptOrJob(attemptId: String?, jobId: String?): File? {
        val attemptFile = attemptId?.takeIf { it.isNotBlank() }?.let(::resolveAttemptFile)
        if (attemptFile != null) return attemptFile
        return jobId?.takeIf { it.isNotBlank() }?.let(::resolveJobLatestFile)
    }

    suspend fun captureFromWebView(
        webView: WebView,
        jobId: String,
        attemptId: String,
    ): File? {
        if (jobId.isBlank() || attemptId.isBlank()) return null
        var capturedBitmap: Bitmap? = null
        return try {
            withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                capturedBitmap = withContext(Dispatchers.Main) {
                    val srcWidth = webView.width
                    val srcHeight = webView.height
                    if (srcWidth <= 0 || srcHeight <= 0) {
                        return@withContext null
                    }
                    val (targetWidth, targetHeight) = computeScaledDimensions(
                        srcWidth = srcWidth,
                        srcHeight = srcHeight,
                    )
                    val bitmap = Bitmap.createBitmap(
                        targetWidth,
                        targetHeight,
                        Bitmap.Config.ARGB_8888,
                    )
                    try {
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        canvas.scale(
                            targetWidth.toFloat() / srcWidth.toFloat(),
                            targetHeight.toFloat() / srcHeight.toFloat(),
                        )
                        webView.draw(canvas)
                        bitmap
                    } catch (exc: Throwable) {
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                        throw exc
                    }
                }

                val bitmap = capturedBitmap ?: return@withTimeoutOrNull null
                withContext(Dispatchers.IO) {
                    val out = ByteArrayOutputStream()
                    val compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    if (!compressed) {
                        return@withContext null
                    }
                    writePngBytes(
                        jobId = jobId,
                        attemptId = attemptId,
                        pngBytes = out.toByteArray(),
                    )
                }
            }
        } catch (ce: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw ce
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            capturedBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
        }
    }

    fun writePngBytes(
        jobId: String,
        attemptId: String,
        pngBytes: ByteArray,
    ): File? {
        if (jobId.isBlank() || attemptId.isBlank() || pngBytes.isEmpty()) return null
        return try {
            if (!screenshotsDir.exists() && !screenshotsDir.mkdirs() && !screenshotsDir.isDirectory) {
                return null
            }
            val nowMs = clockMillis()
            val attemptDest = resolveTargetInRoot(attemptFileName(attemptId)) ?: return null
            val jobLatestDest = resolveTargetInRoot(jobLatestFileName(jobId)) ?: return null

            writeAtomic(attemptDest, pngBytes, nowMs)
            writeAtomic(jobLatestDest, pngBytes, nowMs)
            pruneExpiredAndExcess(nowMs)

            if (attemptDest.isFile && attemptDest.length() > 0L) attemptDest else null
        } catch (_: Throwable) {
            null
        }
    }

    fun pruneExpiredAndExcess(nowMs: Long = clockMillis()): Int {
        return try {
            if (!screenshotsDir.isDirectory) return 0
            val files = screenshotsDir.listFiles() ?: return 0
            val cutoffMs = nowMs - RETENTION_MS
            var deletedCount = 0
            val survivingPngs = ArrayList<File>(files.size)

            for (file in files) {
                if (!file.isFile) continue
                val name = file.name
                if (name.endsWith(TMP_SUFFIX)) {
                    if (nowMs - file.lastModified() >= STALE_TMP_MS && file.delete()) {
                        deletedCount++
                    }
                    continue
                }
                if (!name.endsWith(PNG_SUFFIX)) continue

                if (file.lastModified() < cutoffMs) {
                    if (file.delete()) {
                        deletedCount++
                    }
                } else {
                    survivingPngs.add(file)
                }
            }

            if (survivingPngs.size > MAX_FILES) {
                survivingPngs.sortWith(
                    compareBy<File> { it.lastModified() }.thenBy { it.name },
                )
                val excessCount = survivingPngs.size - MAX_FILES
                for (i in 0 until excessCount) {
                    if (survivingPngs[i].delete()) {
                        deletedCount++
                    }
                }
            }
            deletedCount
        } catch (_: Throwable) {
            0
        }
    }

    private fun writeAtomic(
        dest: File,
        bytes: ByteArray,
        timestampMs: Long,
    ) {
        val tmpFile = File(screenshotsDir, "${dest.name}.${System.nanoTime()}$TMP_SUFFIX")
        try {
            FileOutputStream(tmpFile).use { out ->
                out.write(bytes)
                out.flush()
            }
            runCatching { tmpFile.setLastModified(timestampMs) }
            if (dest.exists() && !tmpFile.renameTo(dest)) {
                // Windows / 部分文件系统 renameTo 覆盖已存在文件可能返回 false，安全回退为先删后移或直接覆写
                dest.delete()
                if (!tmpFile.renameTo(dest)) {
                    FileOutputStream(dest).use { out ->
                        out.write(bytes)
                        out.flush()
                    }
                }
            } else if (!dest.exists() && !tmpFile.renameTo(dest)) {
                FileOutputStream(dest).use { out ->
                    out.write(bytes)
                    out.flush()
                }
            }
            runCatching { dest.setLastModified(timestampMs) }
        } finally {
            if (tmpFile.exists()) {
                runCatching { tmpFile.delete() }
            }
        }
    }

    private fun resolveTargetInRoot(fileName: String): File? {
        val canonicalRoot = try {
            screenshotsDir.canonicalFile
        } catch (_: Throwable) {
            return null
        }
        val candidate = File(canonicalRoot, fileName)
        val canonicalCandidate = try {
            candidate.canonicalFile
        } catch (_: Throwable) {
            return null
        }
        val rootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        if (!canonicalCandidate.path.startsWith(rootPrefix)) {
            return null
        }
        return canonicalCandidate
    }

    private fun resolveExistingInRoot(fileName: String): File? {
        val target = resolveTargetInRoot(fileName) ?: return null
        if (!target.isFile || target.length() <= 0L) return null
        return target
    }

    companion object {
        const val SCREENSHOTS_DIR_NAME = "diagnostic_screenshots"
        const val RETENTION_MS: Long = 7L * 24L * 60L * 60L * 1000L
        const val MAX_FILES: Int = 60
        const val TARGET_WIDTH_PX: Int = 960
        const val TARGET_HEIGHT_PX: Int = 600

        private const val CAPTURE_TIMEOUT_MS: Long = 3_000L
        private const val STALE_TMP_MS: Long = 60_000L
        private const val MAX_ID_LENGTH: Int = 80
        private const val PNG_SUFFIX = ".png"
        private const val TMP_SUFFIX = ".tmp"

        fun sanitizeId(raw: String): String {
            val trimmed = raw.trim()
            val cleaned = buildString(trimmed.length.coerceAtMost(MAX_ID_LENGTH)) {
                for (ch in trimmed) {
                    if (length >= MAX_ID_LENGTH) break
                    if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '-') {
                        append(ch)
                    } else {
                        append('_')
                    }
                }
            }.trim('_')
            return cleaned.ifEmpty { "unknown" }
        }

        fun attemptFileName(attemptId: String): String =
            "attempt_${sanitizeId(attemptId)}$PNG_SUFFIX"

        fun jobLatestFileName(jobId: String): String =
            "job_${sanitizeId(jobId)}_latest$PNG_SUFFIX"

        fun computeScaledDimensions(
            srcWidth: Int,
            srcHeight: Int,
            maxWidth: Int = TARGET_WIDTH_PX,
            maxHeight: Int = TARGET_HEIGHT_PX,
        ): Pair<Int, Int> {
            val safeW = srcWidth.coerceAtLeast(1)
            val safeH = srcHeight.coerceAtLeast(1)
            val limitW = maxWidth.coerceAtLeast(1)
            val limitH = maxHeight.coerceAtLeast(1)
            val scale = minOf(
                1f,
                limitW.toFloat() / safeW.toFloat(),
                limitH.toFloat() / safeH.toFloat(),
            )
            val targetW = (safeW * scale).roundToInt().coerceIn(1, limitW)
            val targetH = (safeH * scale).roundToInt().coerceIn(1, limitH)
            return targetW to targetH
        }
    }
}
