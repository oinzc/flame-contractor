package io.github.oinzc.flamecontractor.core.storage

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateImageStoreTest {

    @Test
    fun readBoundedBytesReadsExactBytesWithinLimit() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val result = TemplateImageStoreUtils.readBoundedBytes(
            input = ByteArrayInputStream(payload),
            maxBytes = 10,
        )
        assertArrayEquals(payload, result)
    }

    @Test
    fun readBoundedBytesRejectsEmptyOrOversizedStreams() {
        assertThrows(IllegalArgumentException::class.java) {
            TemplateImageStoreUtils.readBoundedBytes(
                input = ByteArrayInputStream(ByteArray(0)),
                maxBytes = 1024,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemplateImageStoreUtils.readBoundedBytes(
                input = ByteArrayInputStream(ByteArray(11)),
                maxBytes = 10,
            )
        }
    }

    @Test
    fun isGifHeaderDetectsGif87aAndGif89aOnly() {
        assertTrue(TemplateImageStoreUtils.isGifHeader("GIF87a...".encodeToByteArray()))
        assertTrue(TemplateImageStoreUtils.isGifHeader("GIF89a...".encodeToByteArray()))
        assertFalse(TemplateImageStoreUtils.isGifHeader("GIF88a...".encodeToByteArray()))
        assertFalse(TemplateImageStoreUtils.isGifHeader("GIF89".encodeToByteArray()))
        assertFalse(TemplateImageStoreUtils.isGifHeader(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }

    @Test
    fun computeInSampleSizeScalesPowersOfTwoRespectingMaxEdge() {
        assertEquals(1, TemplateImageStoreUtils.computeInSampleSize(1024, 768, maxEdgePx = 2048))
        assertEquals(1, TemplateImageStoreUtils.computeInSampleSize(4096, 2048, maxEdgePx = 2048))
        assertEquals(2, TemplateImageStoreUtils.computeInSampleSize(4097, 1000, maxEdgePx = 2048))
        assertEquals(4, TemplateImageStoreUtils.computeInSampleSize(9000, 9000, maxEdgePx = 2048))
        assertEquals(1, TemplateImageStoreUtils.computeInSampleSize(0, -1, maxEdgePx = 2048))
    }
}
