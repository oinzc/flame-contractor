package io.github.oinzc.flamecontractor.execution.login

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginQrAlbumSaverTest {

    @Test
    fun computeQuietZonePlacementCentersQrWithSymmetricWhiteMargin() {
        val placement = LoginQrAlbumSaver.computeQuietZonePlacement(
            canvasSizePx = 640,
            quietZonePx = 100,
        )
        assertEquals(640, placement.canvasSizePx)
        assertEquals(100, placement.qrLeftPx)
        assertEquals(100, placement.qrTopPx)
        assertEquals(440, placement.qrSizePx)
    }

    @Test
    fun decodePngQrBytesOrNullAcceptsValidPngAndRejectsCorruptOrNonPng() {
        val validPngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D,
        )
        val b64 = Base64.getEncoder().encodeToString(validPngHeader)

        val fromRaw = LoginQrAlbumSaver.decodePngQrBytesOrNull(b64)
        assertNotNull(fromRaw)
        assertArrayEquals(validPngHeader, fromRaw)

        val fromDataUrl = LoginQrAlbumSaver.decodePngQrBytesOrNull("data:image/png;base64,$b64")
        assertNotNull(fromDataUrl)
        assertArrayEquals(validPngHeader, fromDataUrl)

        val nonPngB64 = Base64.getEncoder().encodeToString("hello world".toByteArray())
        assertNull(LoginQrAlbumSaver.decodePngQrBytesOrNull(nonPngB64))
        assertNull(LoginQrAlbumSaver.decodePngQrBytesOrNull("%%%not-base64%%%"))
        assertNull(LoginQrAlbumSaver.decodePngQrBytesOrNull(""))
        assertNull(LoginQrAlbumSaver.decodePngQrBytesOrNull(null))
    }

    @Test
    fun cleanupTrackerDeletesEachTrackedTokenOnceAndSurvivesPartialFailure() {
        val tracker = TempQrCleanupTracker<String>()
        tracker.record("content://media/external/images/media/101")
        tracker.record("content://media/external/images/media/102")
        tracker.record("content://media/external/images/media/101")

        val deleted = mutableListOf<String>()
        tracker.drainAndCleanup { token ->
            deleted += token
            if (token.endsWith("101")) {
                throw RuntimeException("simulated delete failure")
            }
        }

        assertEquals(
            listOf(
                "content://media/external/images/media/101",
                "content://media/external/images/media/102",
            ),
            deleted,
        )

        // Idempotent second call deletes nothing
        val secondPass = mutableListOf<String>()
        tracker.drainAndCleanup { secondPass += it }
        assertTrue(secondPass.isEmpty())
    }

    @Test
    fun disposedTrackerImmediatelyCleansLateRecordedItems() {
        val tracker = TempQrCleanupTracker<String>()
        tracker.record("content://media/external/images/media/201")
        val cleanedOnDispose = mutableListOf<String>()
        tracker.markDisposedAndDrain { cleanedOnDispose += it }
        assertEquals(listOf("content://media/external/images/media/201"), cleanedOnDispose)

        val cleanedLate = mutableListOf<String>()
        tracker.recordOrCleanupIfDisposed("content://media/external/images/media/202") {
            cleanedLate += it
        }
        assertEquals(listOf("content://media/external/images/media/202"), cleanedLate)
    }
}
