package io.github.oinzc.flamecontractor.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID

data class StoredTemplateImage(
    val storedPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val widthPx: Int,
    val heightPx: Int,
)

interface TemplateImageStorage {
    fun resolveExistingFile(storedPath: String?): File?
    fun isImageAvailable(storedPath: String?): Boolean = resolveExistingFile(storedPath) != null
    suspend fun importFromStream(input: InputStream, mimeTypeHint: String?): StoredTemplateImage
    fun deleteIfUnreferenced(storedPath: String?, referencedPaths: Set<String>): Boolean
    fun contentUriFor(file: File): Uri?
}

object TemplateImageStoreUtils {

    const val TEMPLATE_IMAGES_DIR_NAME = "template_images"
    const val MAX_IMPORT_INPUT_BYTES = 15 * 1024 * 1024
    const val MAX_GIF_BYTES = 5 * 1024 * 1024
    const val MAX_OUTPUT_IMAGE_BYTES = 10 * 1024 * 1024
    const val MAX_IMAGE_EDGE_PX = 2048

    fun readBoundedBytes(
        input: InputStream,
        maxBytes: Int = MAX_IMPORT_INPUT_BYTES,
    ): ByteArray {
        val cap = maxBytes.coerceAtLeast(1)
        val buffer = ByteArray(16 * 1024)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > cap) {
                throw IllegalArgumentException(
                    "图片文件超过 ${cap / (1024 * 1024)} MB 上限，请先压缩或换一张较小的图片",
                )
            }
            out.write(buffer, 0, read)
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) {
            throw IllegalArgumentException("所选图片内容为空，请重新选择")
        }
        return bytes
    }

    fun isGifHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        return bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte()
    }

    fun computeInSampleSize(
        widthPx: Int,
        heightPx: Int,
        maxEdgePx: Int = MAX_IMAGE_EDGE_PX,
    ): Int {
        if (widthPx <= 0 || heightPx <= 0) return 1
        val targetMax = maxEdgePx.coerceAtLeast(1)
        var inSampleSize = 1
        var w = widthPx
        var h = heightPx
        while (w > targetMax * 2 || h > targetMax * 2) {
            w /= 2
            h /= 2
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }
}

class AndroidTemplateImageStore(
    context: Context,
) : TemplateImageStorage {

    private val appContext: Context = context.applicationContext ?: context
    private val imagesDir: File = File(appContext.filesDir, TemplateImageStoreUtils.TEMPLATE_IMAGES_DIR_NAME)

    override fun resolveExistingFile(storedPath: String?): File? {
        val clean = storedPath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val candidate = File(clean)
        val canonicalRoot = try {
            imagesDir.canonicalFile
        } catch (_: Throwable) {
            return null
        }
        val canonicalFile = try {
            candidate.canonicalFile
        } catch (_: Throwable) {
            return null
        }
        val rootPath = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        if (!canonicalFile.path.startsWith(rootPath)) {
            return null
        }
        if (!canonicalFile.isFile || canonicalFile.length() <= 0L) {
            return null
        }
        return canonicalFile
    }

    override suspend fun importFromStream(
        input: InputStream,
        mimeTypeHint: String?,
    ): StoredTemplateImage {
        val rawBytes = TemplateImageStoreUtils.readBoundedBytes(
            input = input,
            maxBytes = TemplateImageStoreUtils.MAX_IMPORT_INPUT_BYTES,
        )
        if (!imagesDir.exists()) {
            imagesDir.mkdirs()
        }

        val normalizedMime = mimeTypeHint?.lowercase(Locale.ROOT).orEmpty()
        if (normalizedMime == "image/gif" || TemplateImageStoreUtils.isGifHeader(rawBytes)) {
            if (!TemplateImageStoreUtils.isGifHeader(rawBytes)) {
                throw IllegalArgumentException("无法识别的 GIF 图片格式")
            }
            if (rawBytes.size > TemplateImageStoreUtils.MAX_GIF_BYTES) {
                throw IllegalArgumentException("GIF 动图超过 5 MB 上限，请换一张较小的动图")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            val width = bounds.outWidth.coerceAtLeast(1)
            val height = bounds.outHeight.coerceAtLeast(1)
            val destFile = File(imagesDir, "${UUID.randomUUID()}.gif")
            FileOutputStream(destFile).use { out ->
                out.write(rawBytes)
                out.flush()
            }
            return StoredTemplateImage(
                storedPath = destFile.absolutePath,
                mimeType = "image/gif",
                sizeBytes = destFile.length(),
                widthPx = width,
                heightPx = height,
            )
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("所选文件不是受支持的有效图片格式")
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = TemplateImageStoreUtils.computeInSampleSize(
                widthPx = bounds.outWidth,
                heightPx = bounds.outHeight,
                maxEdgePx = TemplateImageStoreUtils.MAX_IMAGE_EDGE_PX,
            )
        }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
            ?: throw IllegalArgumentException("解码图片失败，请换用 JPEG、PNG 或 WebP 图片")

        val oriented = applyExifOrientationAndRecycle(decoded, rawBytes)
        val scaled = downscaleIfNeeded(oriented, TemplateImageStoreUtils.MAX_IMAGE_EDGE_PX)
        return try {
            val hasAlpha = scaled.hasAlpha()
            val encodedBytes = encodeWithinLimit(scaled, hasAlpha)
            val ext = if (hasAlpha) "png" else "jpg"
            val outMime = if (hasAlpha) "image/png" else "image/jpeg"
            val destFile = File(imagesDir, "${UUID.randomUUID()}.$ext")
            FileOutputStream(destFile).use { out ->
                out.write(encodedBytes)
                out.flush()
            }
            StoredTemplateImage(
                storedPath = destFile.absolutePath,
                mimeType = outMime,
                sizeBytes = destFile.length(),
                widthPx = scaled.width,
                heightPx = scaled.height,
            )
        } finally {
            if (!scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }

    override fun deleteIfUnreferenced(
        storedPath: String?,
        referencedPaths: Set<String>,
    ): Boolean {
        val file = resolveExistingFile(storedPath) ?: return false
        val targetCanonical = file.canonicalPath
        val stillReferenced = referencedPaths.any { ref ->
            val resolvedRef = resolveExistingFile(ref) ?: return@any false
            resolvedRef.canonicalPath == targetCanonical
        }
        if (stillReferenced) return false
        return try {
            file.delete()
        } catch (_: Throwable) {
            false
        }
    }

    override fun contentUriFor(file: File): Uri? = try {
        FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
    } catch (_: Throwable) {
        null
    }

    private fun applyExifOrientationAndRecycle(source: Bitmap, rawBytes: ByteArray): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(rawBytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source && !source.isRecycled) {
            source.recycle()
        }
        return rotated
    }

    private fun downscaleIfNeeded(source: Bitmap, maxEdge: Int): Bitmap {
        val currentMax = maxOf(source.width, source.height)
        if (currentMax <= maxEdge) return source
        val scale = maxEdge.toFloat() / currentMax.toFloat()
        val targetW = (source.width * scale).toInt().coerceAtLeast(1)
        val targetH = (source.height * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(source, targetW, targetH, true)
        if (resized !== source && !source.isRecycled) {
            source.recycle()
        }
        return resized
    }

    private fun encodeWithinLimit(bitmap: Bitmap, hasAlpha: Boolean): ByteArray {
        if (hasAlpha) {
            val pngOut = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngOut)
            val pngBytes = pngOut.toByteArray()
            if (pngBytes.size <= TemplateImageStoreUtils.MAX_OUTPUT_IMAGE_BYTES) {
                return pngBytes
            }
        }
        val qualities = intArrayOf(88, 76, 64)
        for (q in qualities) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, q, out)
            val bytes = out.toByteArray()
            if (bytes.size <= TemplateImageStoreUtils.MAX_OUTPUT_IMAGE_BYTES) {
                return bytes
            }
        }
        throw IllegalArgumentException("图片压缩后仍超过 10 MB 上限，请选择分辨率更小的图片")
    }
}
