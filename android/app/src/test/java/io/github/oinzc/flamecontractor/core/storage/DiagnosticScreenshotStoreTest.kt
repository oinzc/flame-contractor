package io.github.oinzc.flamecontractor.core.storage

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticScreenshotStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val minimalPngHeader = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    )

    @Test
    fun sanitizeIdPreventsTraversalAndEnforcesSafeCharset() {
        assertEquals("attempt-abc_123", DiagnosticScreenshotStore.sanitizeId("attempt-abc_123"))
        assertEquals("etc_passwd", DiagnosticScreenshotStore.sanitizeId("../../etc/passwd"))
        assertEquals("a_b_c_d", DiagnosticScreenshotStore.sanitizeId("a\\b:c/d"))
        assertEquals("unknown", DiagnosticScreenshotStore.sanitizeId("../../../"))
        assertEquals("unknown", DiagnosticScreenshotStore.sanitizeId("   "))
        assertEquals("unknown", DiagnosticScreenshotStore.sanitizeId(""))

        val overlong = "a".repeat(120)
        val sanitized = DiagnosticScreenshotStore.sanitizeId(overlong)
        assertEquals(80, sanitized.length)

        val root = tempFolder.newFolder("diag_sanitize")
        val store = DiagnosticScreenshotStore(screenshotsDir = root)
        val written = store.writePngBytes(
            jobId = "../../outside_job",
            attemptId = "..\\..\\outside_attempt",
            pngBytes = minimalPngHeader,
        )
        assertNotNull(written)
        val rootCanonical = root.canonicalPath + File.separator
        assertTrue(written!!.canonicalPath.startsWith(rootCanonical))
        assertFalse(written.name.contains(".."))
        assertFalse(written.name.contains("/"))
        assertFalse(written.name.contains("\\"))
    }

    @Test
    fun computeScaledDimensionsScales1440x900To960x600WithoutUpscaling() {
        assertEquals(960 to 600, DiagnosticScreenshotStore.computeScaledDimensions(1440, 900))
        assertEquals(800 to 500, DiagnosticScreenshotStore.computeScaledDimensions(800, 500))
        assertEquals(960 to 540, DiagnosticScreenshotStore.computeScaledDimensions(1920, 1080))
        assertEquals(1 to 1, DiagnosticScreenshotStore.computeScaledDimensions(0, -10))
    }

    @Test
    fun writePngBytesPersistsAttemptAndJobLatestAndSupportsFallbackLookup() {
        val root = tempFolder.newFolder("diag_write")
        var now = 1_764_000_000_000L
        val store = DiagnosticScreenshotStore(screenshotsDir = root, clockMillis = { now })

        val png1 = minimalPngHeader + byteArrayOf(1, 2, 3)
        val png2 = minimalPngHeader + byteArrayOf(4, 5, 6, 7)

        val file1 = store.writePngBytes(jobId = "job-1", attemptId = "att-1", pngBytes = png1)
        assertNotNull(file1)
        assertEquals("attempt_att-1.png", file1!!.name)
        assertArrayEquals(png1, store.resolveAttemptFile("att-1")!!.readBytes())
        assertArrayEquals(png1, store.resolveJobLatestFile("job-1")!!.readBytes())

        now += 5_000L
        val file2 = store.writePngBytes(jobId = "job-1", attemptId = "att-2", pngBytes = png2)
        assertNotNull(file2)
        // 旧尝试截图保留，同时 job_<id>_latest.png 刷新为最新一次尝试画面
        assertArrayEquals(png1, store.resolveAttemptFile("att-1")!!.readBytes())
        assertArrayEquals(png2, store.resolveAttemptFile("att-2")!!.readBytes())
        assertArrayEquals(png2, store.resolveJobLatestFile("job-1")!!.readBytes())

        // 优先命中 attempt 文件，缺失时安全回退 job latest
        assertEquals(
            store.resolveAttemptFile("att-1")!!.canonicalPath,
            store.resolveForAttemptOrJob("att-1", "job-1")!!.canonicalPath,
        )
        assertEquals(
            store.resolveJobLatestFile("job-1")!!.canonicalPath,
            store.resolveForAttemptOrJob("att-missing", "job-1")!!.canonicalPath,
        )

        // 空 ID 或空内容不落盘
        assertNull(store.writePngBytes(jobId = "", attemptId = "att-3", pngBytes = png1))
        assertNull(store.writePngBytes(jobId = "job-2", attemptId = "att-3", pngBytes = ByteArray(0)))
        assertNull(store.resolveJobLatestFile("job-2"))
    }

    @Test
    fun pruneExpiredAndExcessRemovesFilesOlderThanSevenDays() {
        val root = tempFolder.newFolder("diag_retention_days")
        val baseTime = 1_764_000_000_000L
        var now = baseTime
        val store = DiagnosticScreenshotStore(screenshotsDir = root, clockMillis = { now })

        assertNotNull(store.writePngBytes("job-old", "att-old", minimalPngHeader))

        // 推进 6 天：仍在 7 天保留窗口内
        now = baseTime + 6L * 24L * 60L * 60L * 1000L
        assertEquals(0, store.pruneExpiredAndExcess())
        assertNotNull(store.resolveAttemptFile("att-old"))
        assertNotNull(store.resolveJobLatestFile("job-old"))

        // 推进至 7 天 + 1 秒后：写入新截图并自动淘汰过期文件
        now = baseTime + DiagnosticScreenshotStore.RETENTION_MS + 1_000L
        assertNotNull(store.writePngBytes("job-fresh", "att-fresh", minimalPngHeader))

        assertNull(store.resolveAttemptFile("att-old"))
        assertNull(store.resolveJobLatestFile("job-old"))
        assertNotNull(store.resolveAttemptFile("att-fresh"))
        assertNotNull(store.resolveJobLatestFile("job-fresh"))
    }

    @Test
    fun pruneExpiredAndExcessCapsDirectoryAtSixtyFilesEvictingOldestFirst() {
        val root = tempFolder.newFolder("diag_retention_cap")
        var now = 1_764_000_000_000L
        val store = DiagnosticScreenshotStore(screenshotsDir = root, clockMillis = { now })

        // 写入 35 组独立 job + attempt，共 70 张截图；上限 60 张应淘汰最旧 5 组（10 张）
        for (i in 1..35) {
            now += 1_000L
            assertNotNull(
                store.writePngBytes(
                    jobId = "job-%02d".format(i),
                    attemptId = "att-%02d".format(i),
                    pngBytes = minimalPngHeader + byteArrayOf(i.toByte()),
                ),
            )
        }

        val remainingPngs = root.listFiles()?.filter { it.isFile && it.name.endsWith(".png") }.orEmpty()
        assertEquals(DiagnosticScreenshotStore.MAX_FILES, remainingPngs.size)

        // 最旧的 1..5 组已被清理
        for (i in 1..5) {
            assertNull("Expected old attempt att-%02d pruned".format(i), store.resolveAttemptFile("att-%02d".format(i)))
            assertNull("Expected old job job-%02d pruned".format(i), store.resolveJobLatestFile("job-%02d".format(i)))
        }
        // 最新的 6..35 组完整保留
        for (i in 6..35) {
            assertNotNull("Expected fresh attempt att-%02d kept".format(i), store.resolveAttemptFile("att-%02d".format(i)))
            assertNotNull("Expected fresh job job-%02d kept".format(i), store.resolveJobLatestFile("job-%02d".format(i)))
        }
    }
}
