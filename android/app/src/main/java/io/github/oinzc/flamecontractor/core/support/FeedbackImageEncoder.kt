package io.github.oinzc.flamecontractor.core.support

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

data class EncodedFeedbackImage(
    val id: String,
    val attachment: FeedbackImageAttachment,
    val previewBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedFeedbackImage) return false
        return id == other.id &&
            attachment == other.attachment &&
            previewBytes.contentEquals(other.previewBytes) &&
            widthPx == other.widthPx &&
            heightPx == other.heightPx
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + attachment.hashCode()
        result = 31 * result + previewBytes.contentHashCode()
        result = 31 * result + widthPx
        result = 31 * result + heightPx
        return result
    }
}

data class EncodedImageCandidate(
    val bytes: ByteArray,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedImageCandidate) return false
        return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
}

/**
 * 纯 JVM 可单测的反馈截图缩放、魔数检测、阶梯压缩与命名工具。
 */
object FeedbackImageEncoderUtils {
    const val MAX_INPUT_BYTES: Int = 20 * 1024 * 1024
    const val MAX_EDGE_PX: Int = 1600
    const val MAX_OUTPUT_BYTES: Int = FeedbackSubmitter.MAX_IMAGE_BYTES
    val JPEG_QUALITY_STEPS: IntArray = intArrayOf(86, 76, 66, 56, 46, 36)
    val FALLBACK_EDGE_STEPS: IntArray = intArrayOf(1280, 1024)

    fun readBoundedBytes(
        input: InputStream,
        maxBytes: Int = MAX_INPUT_BYTES,
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
                    "所选图片原图超过 ${cap / (1024 * 1024)} MB 上限，请换一张较小的截图",
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

    /**
     * 按文件头魔数识别反馈协议允许的图片格式（与中转服务魔数校验完全一致）。
     */
    fun detectImageMimeFromMagicBytes(bytes: ByteArray): String? {
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8 &&
            (bytes[2].toInt() and 0xFF) == 0xFF
        ) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 &&
            (bytes[0].toInt() and 0xFF) == 0x89 &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
        ) {
            return "image/png"
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
            return "image/webp"
        }
        return null
    }

    fun isSupportedImageHeader(bytes: ByteArray): Boolean =
        detectImageMimeFromMagicBytes(bytes) in FeedbackSubmitter.ALLOWED_IMAGE_MIMES

    fun computeInSampleSize(
        widthPx: Int,
        heightPx: Int,
        maxEdgePx: Int = MAX_EDGE_PX,
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

    /**
     * 等比计算最长边不超过 [maxEdgePx] 的目标像素宽高。
     */
    fun computeScaledDimensions(
        widthPx: Int,
        heightPx: Int,
        maxEdgePx: Int = MAX_EDGE_PX,
    ): Pair<Int, Int> {
        if (widthPx <= 0 || heightPx <= 0) return 0 to 0
        val cap = maxEdgePx.coerceAtLeast(1)
        val currentMax = maxOf(widthPx, heightPx)
        if (currentMax <= cap) return widthPx to heightPx
        val scale = cap.toDouble() / currentMax.toDouble()
        val targetW = (widthPx * scale).roundToInt().coerceIn(1, cap)
        val targetH = (heightPx * scale).roundToInt().coerceIn(1, cap)
        return targetW to targetH
    }

    /**
     * 带透明通道时优先尝试 PNG，若超出体积上限或无透明通道则按阶梯质量导出 JPEG。
     */
    fun encodeWithQualityLadder(
        hasAlpha: Boolean,
        maxBytes: Int = MAX_OUTPUT_BYTES,
        qualitySteps: IntArray = JPEG_QUALITY_STEPS,
        encodePng: () -> ByteArray,
        encodeJpeg: (quality: Int) -> ByteArray,
    ): EncodedImageCandidate? {
        val cap = maxBytes.coerceAtLeast(1)
        if (hasAlpha) {
            val pngBytes = encodePng()
            if (pngBytes.isNotEmpty() && pngBytes.size <= cap) {
                return EncodedImageCandidate(bytes = pngBytes, mimeType = "image/png")
            }
        }
        for (quality in qualitySteps) {
            val jpegBytes = encodeJpeg(quality)
            if (jpegBytes.isNotEmpty() && jpegBytes.size <= cap) {
                return EncodedImageCandidate(bytes = jpegBytes, mimeType = "image/jpeg")
            }
        }
        return null
    }

    fun encodeBytesToBase64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    fun sanitizeAttachmentName(
        rawName: String?,
        indexHint: Int,
        mimeType: String,
    ): String {
        val ext = when (mimeType.trim().lowercase(Locale.ROOT)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val safeIndex = indexHint.coerceAtLeast(1)
        val cleanedBase = rawName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringBeforeLast('.', missingDelimiterValue = rawName.substringAfterLast('/').substringAfterLast('\\'))
            ?.replace(Regex("[^A-Za-z0-9_\\-\\u4E00-\\u9FA5]+"), "_")
            ?.trim('_', '-', ' ')
            ?.take(40)
            .orEmpty()
        return if (cleanedBase.isNotEmpty()) {
            "$cleanedBase.$ext"
        } else {
            "screenshot_${safeIndex}.$ext"
        }
    }

    fun formatByteSize(sizeBytes: Int): String {
        val safe = sizeBytes.coerceAtLeast(0)
        if (safe < 1024) return "$safe B"
        val kb = ((safe + 512) / 1024).coerceAtLeast(1)
        return "$kb KB"
    }
}

/**
 * Android 端反馈截图安全解码、EXIF 方向校正与隐私剥离编码器：
 * - 通过 `inJustDecodeBounds` + `inSampleSize` 防止大图 OOM；
 * - 校正 EXIF 旋转后重新编码 Bitmap，自动彻底剥离定位/相机/时间等 EXIF 元数据；
 * - 缩放到最长边 <= 1600px，阶梯压缩确保 <= 800 KB。
 */
object FeedbackImageEncoder {

    fun encodeFromUri(
        context: Context,
        uri: Uri,
        indexHint: Int,
    ): EncodedFeedbackImage {
        val displayName = resolveDisplayName(context, uri)
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法读取所选截图，请重新选择")
        return stream.use { input ->
            encodeFromStream(
                input = input,
                nameHint = displayName,
                indexHint = indexHint,
            )
        }
    }

    fun encodeFromStream(
        input: InputStream,
        nameHint: String? = null,
        indexHint: Int = 1,
    ): EncodedFeedbackImage {
        val rawBytes = FeedbackImageEncoderUtils.readBoundedBytes(
            input = input,
            maxBytes = FeedbackImageEncoderUtils.MAX_INPUT_BYTES,
        )

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("所选文件不是有效图片，请选择 JPG、PNG 或 WebP 截图")
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = FeedbackImageEncoderUtils.computeInSampleSize(
                widthPx = bounds.outWidth,
                heightPx = bounds.outHeight,
                maxEdgePx = FeedbackImageEncoderUtils.MAX_EDGE_PX,
            )
        }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
            ?: throw IllegalArgumentException("图片解码失败，请换用 JPG、PNG 或 WebP 截图")

        val oriented = applyExifOrientationAndRecycle(decoded, rawBytes)
        var working = downscaleIfNeeded(oriented, FeedbackImageEncoderUtils.MAX_EDGE_PX)
        try {
            var candidate = encodeBitmapWithinLimit(
                bitmap = working,
                tryPngIfAlpha = working.hasAlpha(),
            )
            if (candidate == null) {
                for (fallbackEdge in FeedbackImageEncoderUtils.FALLBACK_EDGE_STEPS) {
                    if (maxOf(working.width, working.height) <= fallbackEdge) continue
                    working = downscaleIfNeeded(working, fallbackEdge)
                    candidate = encodeBitmapWithinLimit(
                        bitmap = working,
                        tryPngIfAlpha = false,
                    )
                    if (candidate != null) break
                }
            }
            val encoded = candidate
                ?: throw IllegalArgumentException("截图压缩后仍超过 800 KB 上限，请裁剪或换一张分辨率更小的图片")

            val verifiedMime = FeedbackImageEncoderUtils.detectImageMimeFromMagicBytes(encoded.bytes)
                ?: throw IllegalArgumentException("截图编码格式校验失败，请换一张图片重试")

            val base64 = FeedbackImageEncoderUtils.encodeBytesToBase64(encoded.bytes)
            val safeName = FeedbackImageEncoderUtils.sanitizeAttachmentName(
                rawName = nameHint,
                indexHint = indexHint,
                mimeType = verifiedMime,
            )
            return EncodedFeedbackImage(
                id = UUID.randomUUID().toString(),
                attachment = FeedbackImageAttachment(
                    name = safeName,
                    mimeType = verifiedMime,
                    dataBase64 = base64,
                    sizeBytes = encoded.bytes.size,
                ),
                previewBytes = encoded.bytes,
                widthPx = working.width,
                heightPx = working.height,
            )
        } finally {
            if (!working.isRecycled) {
                working.recycle()
            }
        }
    }

    fun decodePreviewThumbnail(
        previewBytes: ByteArray,
        maxEdgePx: Int = 320,
    ): Bitmap? = runCatching {
        if (previewBytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = FeedbackImageEncoderUtils.computeInSampleSize(
                widthPx = bounds.outWidth,
                heightPx = bounds.outHeight,
                maxEdgePx = maxEdgePx,
            )
        }
        BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size, options)
    }.getOrNull()

    private fun encodeBitmapWithinLimit(
        bitmap: Bitmap,
        tryPngIfAlpha: Boolean,
    ): EncodedImageCandidate? {
        var flattenedForJpeg: Bitmap? = null
        return try {
            FeedbackImageEncoderUtils.encodeWithQualityLadder(
                hasAlpha = tryPngIfAlpha,
                maxBytes = FeedbackImageEncoderUtils.MAX_OUTPUT_BYTES,
                qualitySteps = FeedbackImageEncoderUtils.JPEG_QUALITY_STEPS,
                encodePng = {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                },
                encodeJpeg = { quality ->
                    val target = if (bitmap.hasAlpha()) {
                        flattenedForJpeg ?: flattenAlphaOnWhite(bitmap).also { flattenedForJpeg = it }
                    } else {
                        bitmap
                    }
                    val out = ByteArrayOutputStream()
                    target.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    out.toByteArray()
                },
            )
        } finally {
            flattenedForJpeg?.let { flat ->
                if (!flat.isRecycled) flat.recycle()
            }
        }
    }

    private fun flattenAlphaOnWhite(source: Bitmap): Bitmap {
        val opaque = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(opaque)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        return opaque
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
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return source
        }
        val transformed = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        )
        if (transformed !== source && !source.isRecycled) {
            source.recycle()
        }
        return transformed
    }

    private fun downscaleIfNeeded(source: Bitmap, maxEdgePx: Int): Bitmap {
        val (targetW, targetH) = FeedbackImageEncoderUtils.computeScaledDimensions(
            widthPx = source.width,
            heightPx = source.height,
            maxEdgePx = maxEdgePx,
        )
        if (targetW <= 0 || targetH <= 0 || (targetW == source.width && targetH == source.height)) {
            return source
        }
        val resized = Bitmap.createScaledBitmap(source, targetW, targetH, true)
        if (resized !== source && !source.isRecycled) {
            source.recycle()
        }
        return resized
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    }.getOrNull()
}
