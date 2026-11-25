package io.github.oinzc.flamecontractor.core.support

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackImageEncoderTest {

    @Test
    fun `computeScaledDimensions preserves small images and scales longest edge to 1600`() {
        assertEquals(1200 to 800, FeedbackImageEncoderUtils.computeScaledDimensions(1200, 800))
        assertEquals(1600 to 1600, FeedbackImageEncoderUtils.computeScaledDimensions(1600, 1600))
        assertEquals(1600 to 800, FeedbackImageEncoderUtils.computeScaledDimensions(3200, 1600))
        assertEquals(1200 to 1600, FeedbackImageEncoderUtils.computeScaledDimensions(3000, 4000))
        assertEquals(1600 to 1600, FeedbackImageEncoderUtils.computeScaledDimensions(2400, 2400))
        assertEquals(1600 to 1, FeedbackImageEncoderUtils.computeScaledDimensions(8000, 2))
        assertEquals(0 to 0, FeedbackImageEncoderUtils.computeScaledDimensions(0, 500))
        assertEquals(0 to 0, FeedbackImageEncoderUtils.computeScaledDimensions(500, -10))
    }

    @Test
    fun `computeInSampleSize scales powers of two against 1600 max edge`() {
        assertEquals(1, FeedbackImageEncoderUtils.computeInSampleSize(1600, 900))
        assertEquals(1, FeedbackImageEncoderUtils.computeInSampleSize(3200, 1600))
        assertEquals(2, FeedbackImageEncoderUtils.computeInSampleSize(3201, 1600))
        assertEquals(4, FeedbackImageEncoderUtils.computeInSampleSize(7000, 7000))
        assertEquals(1, FeedbackImageEncoderUtils.computeInSampleSize(0, 0))
    }

    @Test
    fun `detectImageMimeFromMagicBytes recognizes JPEG PNG WebP and rejects others`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.ISO_8859_1)
        val wav = "RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray(Charsets.ISO_8859_1)
        val gif = "GIF89a......".toByteArray(Charsets.ISO_8859_1)

        assertEquals("image/jpeg", FeedbackImageEncoderUtils.detectImageMimeFromMagicBytes(jpeg))
        assertEquals("image/png", FeedbackImageEncoderUtils.detectImageMimeFromMagicBytes(png))
        assertEquals("image/webp", FeedbackImageEncoderUtils.detectImageMimeFromMagicBytes(webp))
        assertTrue(FeedbackImageEncoderUtils.isSupportedImageHeader(jpeg))
        assertTrue(FeedbackImageEncoderUtils.isSupportedImageHeader(png))
        assertTrue(FeedbackImageEncoderUtils.isSupportedImageHeader(webp))

        assertNull(FeedbackImageEncoderUtils.detectImageMimeFromMagicBytes(wav))
        assertNull(FeedbackImageEncoderUtils.detectImageMimeFromMagicBytes(gif))
        assertNull(FeedbackImageEncoderUtils.detectImageMimeFromMagicBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertNull(FeedbackImageEncoderUtils.detectImageMimeFromMagicBytes(ByteArray(0)))
        assertFalse(FeedbackImageEncoderUtils.isSupportedImageHeader(gif))
    }

    @Test
    fun `readBoundedBytes enforces non-empty and max cap`() {
        val data = byteArrayOf(10, 20, 30)
        assertArrayEquals(
            data,
            FeedbackImageEncoderUtils.readBoundedBytes(ByteArrayInputStream(data), maxBytes = 3),
        )
        assertThrows(IllegalArgumentException::class.java) {
            FeedbackImageEncoderUtils.readBoundedBytes(ByteArrayInputStream(ByteArray(0)), maxBytes = 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FeedbackImageEncoderUtils.readBoundedBytes(ByteArrayInputStream(ByteArray(11)), maxBytes = 10)
        }
    }

    @Test
    fun `encodeWithQualityLadder keeps PNG for small alpha falls back to JPEG and steps down quality`() {
        val smallPng = ByteArray(120) { 1 }
        val alphaKept = FeedbackImageEncoderUtils.encodeWithQualityLadder(
            hasAlpha = true,
            maxBytes = 500,
            encodePng = { smallPng },
            encodeJpeg = { error("JPEG fallback should not be invoked when PNG fits") },
        )
        assertNotNull(alphaKept)
        assertEquals("image/png", alphaKept!!.mimeType)
        assertEquals(120, alphaKept.bytes.size)

        val triedQualities = mutableListOf<Int>()
        val fallbackJpeg = FeedbackImageEncoderUtils.encodeWithQualityLadder(
            hasAlpha = true,
            maxBytes = 500,
            qualitySteps = intArrayOf(86, 76, 66, 56),
            encodePng = { ByteArray(900) { 2 } },
            encodeJpeg = { q ->
                triedQualities += q
                if (q <= 66) ByteArray(420) { 3 } else ByteArray(700) { 3 }
            },
        )
        assertNotNull(fallbackJpeg)
        assertEquals("image/jpeg", fallbackJpeg!!.mimeType)
        assertEquals(420, fallbackJpeg.bytes.size)
        assertEquals(listOf(86, 76, 66), triedQualities)

        val impossible = FeedbackImageEncoderUtils.encodeWithQualityLadder(
            hasAlpha = false,
            maxBytes = 500,
            qualitySteps = intArrayOf(86, 66, 36),
            encodePng = { ByteArray(1000) },
            encodeJpeg = { ByteArray(800) },
        )
        assertNull(impossible)
    }

    @Test
    fun `sanitizeAttachmentName formatByteSize and encodeBytesToBase64 format cleanly`() {
        assertEquals(
            "screenshot_2.jpg",
            FeedbackImageEncoderUtils.sanitizeAttachmentName(null, indexHint = 2, mimeType = "image/jpeg"),
        )
        assertEquals(
            "IMG_2026.png",
            FeedbackImageEncoderUtils.sanitizeAttachmentName(
                rawName = "/storage/emulated/0/DCIM/IMG_2026.heic",
                indexHint = 1,
                mimeType = "image/png",
            ),
        )
        assertEquals(
            "screenshot_1.webp",
            FeedbackImageEncoderUtils.sanitizeAttachmentName(
                rawName = "///...",
                indexHint = 1,
                mimeType = "image/webp",
            ),
        )

        assertEquals("512 B", FeedbackImageEncoderUtils.formatByteSize(512))
        assertEquals("1 KB", FeedbackImageEncoderUtils.formatByteSize(1024))
        assertEquals("186 KB", FeedbackImageEncoderUtils.formatByteSize(186 * 1024))

        val b64 = FeedbackImageEncoderUtils.encodeBytesToBase64(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        assertEquals("/9j/", b64)
        assertFalse(b64.startsWith("data:"))
        assertFalse(b64.contains('\n'))
    }
}
