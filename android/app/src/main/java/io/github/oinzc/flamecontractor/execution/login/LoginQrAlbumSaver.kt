package io.github.oinzc.flamecontractor.execution.login

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class QuietZonePlacement(
    val canvasSizePx: Int,
    val qrLeftPx: Int,
    val qrTopPx: Int,
    val qrSizePx: Int,
)

data class QrSaveOutcome(
    val savedToAlbum: Boolean,
    val shareUri: Uri? = null,
    val errorMessage: String? = null,
)

internal class TempQrCleanupTracker<T> {
    private val lock = Any()
    private val items = LinkedHashSet<T>()
    private var disposed = false

    fun record(item: T) {
        synchronized(lock) {
            items += item
        }
    }

    fun recordOrCleanupIfDisposed(item: T, deleter: (T) -> Unit) {
        val cleanupImmediately = synchronized(lock) {
            if (disposed) {
                true
            } else {
                items += item
                false
            }
        }
        if (cleanupImmediately) {
            try {
                deleter(item)
            } catch (_: Throwable) {
            }
        }
    }

    fun drainAndCleanup(deleter: (T) -> Unit) {
        val snapshot: List<T> = synchronized(lock) {
            if (items.isEmpty()) return
            val list = items.toList()
            items.clear()
            list
        }
        for (item in snapshot) {
            try {
                deleter(item)
            } catch (_: Throwable) {
            }
        }
    }

    fun markDisposedAndDrain(deleter: (T) -> Unit) {
        val snapshot: List<T> = synchronized(lock) {
            disposed = true
            if (items.isEmpty()) return
            val list = items.toList()
            items.clear()
            list
        }
        for (item in snapshot) {
            try {
                deleter(item)
            } catch (_: Throwable) {
            }
        }
    }
}

class LoginQrAlbumSaver(context: Context) {

    private val appContext: Context = context.applicationContext ?: context
    private val uriTracker = TempQrCleanupTracker<Uri>()

    fun hasLegacyWritePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun decodeBitmapForPreview(rawBase64OrDataUrl: String?): Bitmap? =
        withContext(Dispatchers.Default) {
            val bytes = decodePngQrBytesOrNull(rawBase64OrDataUrl) ?: return@withContext null
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Throwable) {
                null
            }
        }

    suspend fun savePaddedQrForScan(base64Png: String): QrSaveOutcome =
        withContext(Dispatchers.IO) {
            val rawBytes = decodePngQrBytesOrNull(base64Png)
                ?: return@withContext QrSaveOutcome(
                    savedToAlbum = false,
                    errorMessage = "二维码图片数据无效，请刷新后重试",
                )

            val paddedBytes = buildPaddedPngBytes(rawBytes)
                ?: return@withContext QrSaveOutcome(
                    savedToAlbum = false,
                    errorMessage = "生成二维码图片失败，请刷新后重试",
                )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || hasLegacyWritePermission()) {
                val albumUri = insertIntoMediaStore(paddedBytes)
                if (albumUri != null) {
                    uriTracker.recordOrCleanupIfDisposed(albumUri) { uri ->
                        appContext.contentResolver.delete(uri, null, null)
                    }
                    return@withContext QrSaveOutcome(savedToAlbum = true)
                }
            }

            val shareUri = saveToShareCache(paddedBytes)
            if (shareUri != null) {
                return@withContext QrSaveOutcome(
                    savedToAlbum = false,
                    shareUri = shareUri,
                )
            }

            QrSaveOutcome(
                savedToAlbum = false,
                errorMessage = "无法保存二维码图片，请改用网页或短信验证码登录",
            )
        }

    suspend fun cleanupAll(markDisposed: Boolean = false) {
        withContext(Dispatchers.IO) {
            if (markDisposed) {
                uriTracker.markDisposedAndDrain { uri ->
                    appContext.contentResolver.delete(uri, null, null)
                }
            } else {
                uriTracker.drainAndCleanup { uri ->
                    appContext.contentResolver.delete(uri, null, null)
                }
            }
            runCatching {
                val cacheDir = File(appContext.cacheDir, SHARE_CACHE_DIR_NAME)
                cacheDir.listFiles()?.forEach { file ->
                    runCatching { file.delete() }
                }
            }
        }
    }

    private fun buildPaddedPngBytes(rawPngBytes: ByteArray): ByteArray? {
        var sourceBitmap: Bitmap? = null
        var canvasBitmap: Bitmap? = null
        return try {
            sourceBitmap = BitmapFactory.decodeByteArray(rawPngBytes, 0, rawPngBytes.size)
                ?: return null
            val placement = computeQuietZonePlacement()
            canvasBitmap = Bitmap.createBitmap(
                placement.canvasSizePx,
                placement.canvasSizePx,
                Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(canvasBitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply {
                isFilterBitmap = false
                isAntiAlias = false
                isDither = false
            }
            val dstRect = Rect(
                placement.qrLeftPx,
                placement.qrTopPx,
                placement.qrLeftPx + placement.qrSizePx,
                placement.qrTopPx + placement.qrSizePx,
            )
            canvas.drawBitmap(sourceBitmap, null, dstRect, paint)

            val out = ByteArrayOutputStream()
            if (!canvasBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                return null
            }
            out.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching {
                if (sourceBitmap != null && !sourceBitmap.isRecycled) sourceBitmap.recycle()
            }
            runCatching {
                if (canvasBitmap != null && !canvasBitmap.isRecycled) canvasBitmap.recycle()
            }
        }
    }

    private fun insertIntoMediaStore(pngBytes: ByteArray): Uri? {
        val resolver = appContext.contentResolver
        val fileName = "flame_login_qr_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/FlameContractor",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        var insertedUri: Uri? = null
        return try {
            insertedUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(insertedUri)?.use { os ->
                os.write(pngBytes)
                os.flush()
            } ?: run {
                runCatching { resolver.delete(insertedUri, null, null) }
                return null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publish = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(insertedUri, publish, null, null)
            }
            insertedUri
        } catch (_: Throwable) {
            if (insertedUri != null) {
                runCatching { resolver.delete(insertedUri, null, null) }
            }
            null
        }
    }

    private fun saveToShareCache(pngBytes: ByteArray): Uri? = runCatching {
        val dir = File(appContext.cacheDir, SHARE_CACHE_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, "flame_login_qr_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { fos ->
            fos.write(pngBytes)
            fos.flush()
        }
        FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
    }.getOrNull()

    companion object {
        private const val SHARE_CACHE_DIR_NAME = "login_qr_share"
        private const val MAX_QR_IMAGE_BYTES = 2 * 1024 * 1024
        private val PNG_MAGIC = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )

        internal fun computeQuietZonePlacement(
            canvasSizePx: Int = 640,
            quietZonePx: Int = 100,
        ): QuietZonePlacement {
            val safeCanvas = canvasSizePx.coerceAtLeast(240)
            val maxMargin = (safeCanvas - 120) / 2
            val margin = quietZonePx.coerceIn(16, maxMargin)
            val qrSize = safeCanvas - margin * 2
            return QuietZonePlacement(
                canvasSizePx = safeCanvas,
                qrLeftPx = margin,
                qrTopPx = margin,
                qrSizePx = qrSize,
            )
        }

        internal fun decodePngQrBytesOrNull(rawBase64OrDataUrl: String?): ByteArray? {
            val cleanBase64 = LoginQrParser.stripBase64ImagePrefix(rawBase64OrDataUrl)
                ?: return null
            if (cleanBase64.length > MAX_QR_IMAGE_BYTES * 2) return null
            val decoded = try {
                Base64.getDecoder().decode(cleanBase64)
            } catch (_: IllegalArgumentException) {
                try {
                    Base64.getUrlDecoder().decode(cleanBase64)
                } catch (_: Throwable) {
                    return null
                }
            }
            if (decoded.size < PNG_MAGIC.size || decoded.size > MAX_QR_IMAGE_BYTES) return null
            for (i in PNG_MAGIC.indices) {
                if (decoded[i] != PNG_MAGIC[i]) return null
            }
            return decoded
        }
    }
}
