package io.github.oinzc.flamecontractor.core.diagnostics

import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun tearDown() {
        DiagnosticLogStore.detachGlobalForTest()
    }

    @Test
    fun ringBufferCapsAt300EntriesAndPrunesEntriesOlderThan7Days() {
        var nowMs = Instant.parse("2026-11-25T08:00:00Z").toEpochMilli()
        val store = DiagnosticLogStore(logFile = null, clockMillis = { nowMs })

        for (i in 1..350) {
            nowMs += 1_000L
            store.record(
                category = "SEND",
                event = "step_$i",
                level = "INFO",
                detail = "seq=$i",
            )
        }

        val all = store.recentLines(limit = 400)
        assertEquals(DiagnosticLogStore.MAX_ENTRIES, all.size)
        assertTrue(all.first().contains("step_51 seq=51"))
        assertTrue(all.last().contains("step_350 seq=350"))

        val latestFive = store.recentLines(limit = 5)
        assertEquals(5, latestFive.size)
        assertTrue(latestFive.first().contains("step_346"))
        assertTrue(latestFive.last().contains("step_350"))

        // 前进超过 7 天后写入一条新日志，旧日志必须全部淘汰
        nowMs += DiagnosticLogStore.RETENTION_MS + 60_000L
        store.record(category = "AUTH", event = "fresh_check", level = "INFO", detail = "ok=true")
        val afterExpiry = store.recentLines(limit = 100)
        assertEquals(1, afterExpiry.size)
        assertTrue(afterExpiry.single().contains("[INFO] [AUTH] fresh_check ok=true"))
    }

    @Test
    fun persistsToDiskReloadsValidLinesPrunesExpiredAndClearsCleanly() {
        var nowMs = Instant.parse("2026-11-25T08:15:02Z").toEpochMilli()
        val logFile = File(tempFolder.root, "diagnostics/runtime_events.log")

        val writer = DiagnosticLogStore(logFile = logFile, clockMillis = { nowMs })
        writer.record(
            category = "SEND",
            event = "target_lookup_miss",
            level = "WARN",
            detail = "visibleRows=12 scrollSteps=6 searchHit=false",
        )
        nowMs += 5_000L
        writer.record(
            category = "SCAN",
            event = "scan_done",
            level = "INFO",
            detail = "rounds=3 discovered=18 sparks=7 iconVariants=normal/light.png",
            accountTag = DiagnosticLogStore.safeAccountTag("acc-work-2"),
        )

        assertTrue(logFile.isFile)
        val rawDiskText = logFile.readText(StandardCharsets.UTF_8)
        assertTrue(rawDiskText.contains("2026-11-25T08:15:02Z [WARN] [SEND] target_lookup_miss visibleRows=12 scrollSteps=6 searchHit=false"))

        // 重新构造实例，验证从磁盘完整恢复
        val reloaded = DiagnosticLogStore(logFile = logFile, clockMillis = { nowMs })
        val lines = reloaded.recentLines(limit = 120)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("target_lookup_miss visibleRows=12 scrollSteps=6 searchHit=false"))
        assertTrue(lines[1].contains("iconVariants=normal/light.png"))

        // 超过 7 天后重新加载，过期条目应被清除
        val futureStore = DiagnosticLogStore(
            logFile = logFile,
            clockMillis = { nowMs + DiagnosticLogStore.RETENTION_MS + 10_000L },
        )
        assertTrue(futureStore.recentLines().isEmpty())

        // clear() 清空内存与磁盘
        reloaded.clear()
        assertTrue(reloaded.recentLines().isEmpty())
        assertFalse(logFile.exists())
    }

    @Test
    fun formatForReportPreservesLatestRecordsWithinMaxChars() {
        var nowMs = Instant.parse("2026-11-25T08:00:00Z").toEpochMilli()
        val store = DiagnosticLogStore(logFile = null, clockMillis = { nowMs })

        for (i in 1..40) {
            nowMs += 1_000L
            store.record(
                category = "BATCH",
                event = "evt_$i",
                detail = "count=$i payload=" + "x".repeat(40),
            )
        }

        val capped = store.formatForReport(limit = 40, maxChars = 300)
        assertTrue("Expected formatted length <= 300, got ${capped.length}", capped.length <= 300)
        assertTrue(capped.contains("evt_40"))
        assertFalse(capped.contains("evt_1 "))
    }

    @Test
    fun redactsAllSensitivePiiCredentialsBarkUrlsAndPathsAcrossMemoryAndDisk() {
        val secretNickname = "张三丰密友昵称"
        val secretGroupName = "核心私密群聊"
        val secretSecUid = "MS4wLjABAAAA_SuperSecretUid_998877"
        val secretConvId = "7398765432109876543"
        val secretCookie = "sessionid=abc987xyz654321; ttwid=1%7CsecretTtwidValue999"
        val secretBarkKey = "SuperSecretBarkDeviceKey9942"
        val secretBarkUrl = "https://api.day.app/$secretBarkKey/hello"
        val secretImagePath = "template_images/private_photo_123.jpg"
        val secretMsgText = "早安宝子今天一起续火呀"

        val logFile = File(tempFolder.root, "diagnostics/runtime_events.log")
        val store = DiagnosticLogStore(
            logFile = logFile,
            clockMillis = { Instant.parse("2026-11-25T09:00:00Z").toEpochMilli() },
        )

        store.record(
            category = "SEND",
            event = "leak_attempt",
            level = "WARN",
            detail = "nickname=$secretNickname groupName=$secretGroupName selfUid=$secretSecUid " +
                "platformConversationId=$secretConvId Cookie: $secretCookie " +
                "barkUrl=$secretBarkUrl path=$secretImagePath msgBody=$secretMsgText\ninjected_line",
            accountTag = secretNickname,
        )
        store.record(
            category = "AUTH",
            event = "uid_tag_attempt",
            detail = "sec=$secretSecUid conv=$secretConvId device_key=$secretBarkKey",
            accountTag = secretSecUid,
        )

        val memJoined = store.formatForReport()
        val diskText = logFile.readText(StandardCharsets.UTF_8)

        val forbidden = listOf(
            secretNickname,
            secretGroupName,
            secretSecUid,
            "SuperSecretUid",
            secretConvId,
            "abc987xyz654321",
            "secretTtwidValue999",
            secretBarkKey,
            secretBarkUrl,
            "private_photo_123.jpg",
            "template_images",
            secretMsgText,
        )

        for (output in listOf(memJoined, diskText)) {
            for (secret in forbidden) {
                assertFalse("Diagnostic log leaked sensitive string [$secret] in:\n$output", output.contains(secret))
            }
            // 不得出现换行注入导致多出伪造日志行
            assertEquals(2, output.trim().lines().size)
        }
    }

    @Test
    fun globalAttachAndSafeAccountTagWorkCleanlyAndThreadSafely() {
        assertNull(DiagnosticLogStore.globalOrNull())
        assertEquals(DEFAULT_ACCOUNT_ID, DiagnosticLogStore.safeAccountTag(DEFAULT_ACCOUNT_ID))
        assertNull(DiagnosticLogStore.safeAccountTag("   "))

        val workTag = DiagnosticLogStore.safeAccountTag("user-real-account-uuid-123456")!!
        assertTrue(workTag.startsWith("acct_"))
        assertEquals(11, workTag.length)
        assertFalse(workTag.contains("123456"))
        assertNotEquals(workTag, DiagnosticLogStore.safeAccountTag("another-account-uuid-999999"))

        val store = DiagnosticLogStore(logFile = null)
        DiagnosticLogStore.attachGlobal(store)
        assertSame(store, DiagnosticLogStore.globalOrNull())

        val pool = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(40)
        try {
            for (i in 0 until 40) {
                pool.execute {
                    try {
                        DiagnosticLogStore.recordGlobal(
                            category = "SEND",
                            event = "concurrent_$i",
                            level = "INFO",
                            detail = "idx=$i",
                            accountTag = workTag,
                        )
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(40, store.recentLines(limit = 100).size)
    }
}
