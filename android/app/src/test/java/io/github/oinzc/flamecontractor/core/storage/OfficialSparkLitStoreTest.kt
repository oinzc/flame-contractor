package io.github.oinzc.flamecontractor.core.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialSparkLitStoreTest {

    @After
    fun tearDown() {
        OfficialSparkLitStore.detachGlobalForTest()
    }

    @Test
    fun recordsLitAndGrayStatesAndRemovesDirtyRecordsOnNullUpdate() {
        val persistedSnapshots = mutableListOf<Map<String, OfficialSparkLitRecord>>()
        val store = OfficialSparkLitStore(onPersist = { persistedSnapshots += it })

        store.recordScanResults(
            accountId = "acct_1",
            businessDate = "2026-11-25",
            updates = mapOf(
                "t1" to true,
                "t2" to false,
                "t3" to null,
            ),
            scannedAtEpochMs = 1_000L,
        )

        assertTrue(store.isOfficiallyLitToday("t1", "2026-11-25"))
        assertEquals(true, store.getTodayLitState("t1", "2026-11-25"))
        assertFalse(store.isOfficiallyLitToday("t2", "2026-11-25"))
        assertEquals(false, store.getTodayLitState("t2", "2026-11-25"))
        assertNull(store.getTodayLitState("t3", "2026-11-25"))
        assertEquals(setOf("t1"), store.todayLitTargetIds("acct_1", "2026-11-25"))
        assertEquals(2, store.records.value.size)
        assertEquals(1, persistedSnapshots.size)

        // 当 t1 再次扫描变为 null（未知/无图标）时必须清除今日记录避免脏数据
        store.recordScanResults(
            accountId = "acct_1",
            businessDate = "2026-11-25",
            updates = mapOf(
                "t1" to null,
                "t2" to true,
            ),
            scannedAtEpochMs = 2_000L,
        )

        assertFalse(store.isOfficiallyLitToday("t1", "2026-11-25"))
        assertNull(store.getTodayLitState("t1", "2026-11-25"))
        assertTrue(store.isOfficiallyLitToday("t2", "2026-11-25"))
        assertEquals(setOf("t2"), store.todayLitTargetIds("acct_1", "2026-11-25"))
        assertEquals(1, store.records.value.size)
    }

    @Test
    fun crossDayRolloverInvalidatesOldRecordsAndAutoPurgesSameAccountStaleEntries() {
        val store = OfficialSparkLitStore()
        store.recordScanResults(
            accountId = "default",
            businessDate = "2026-11-25",
            updates = mapOf("t1" to true, "t2" to false),
            scannedAtEpochMs = 1_000L,
        )

        // 跨入次日自然日：读取立即失效
        assertFalse(store.isOfficiallyLitToday("t1", "2026-11-26"))
        assertNull(store.getTodayLitState("t1", "2026-11-26"))
        assertNull(store.getTodayLitState("t2", "2026-11-26"))
        assertEquals(emptySet<String>(), store.todayLitTargetIds("default", "2026-11-26"))

        // 次日写入新扫描结果时自动清理同账号非今日旧记录
        store.recordScanResults(
            accountId = "default",
            businessDate = "2026-11-26",
            updates = mapOf("t3" to true),
            scannedAtEpochMs = 2_000L,
        )
        assertEquals(setOf("t3"), store.records.value.keys)
        assertTrue(store.isOfficiallyLitToday("t3", "2026-11-26"))
        assertNull(store.records.value["t1"])
        assertNull(store.records.value["t2"])
    }

    @Test
    fun multiAccountTimezoneIsolationPreservesAdjacentDayOtherAccountAndPurgesAncientRecords() {
        val store = OfficialSparkLitStore()
        // 账号 B（美洲时区）仍处于 2026-11-24，账号 C 有陈旧的 2026-11-20 记录
        store.recordScanResults(
            accountId = "acct_c",
            businessDate = "2026-11-20",
            updates = mapOf("tc_old" to true),
            scannedAtEpochMs = 500L,
        )
        store.recordScanResults(
            accountId = "acct_b",
            businessDate = "2026-11-24",
            updates = mapOf("tb1" to true),
            scannedAtEpochMs = 1_000L,
        )
        // 账号 A（亚洲时区）处于 2026-11-25
        store.recordScanResults(
            accountId = "acct_a",
            businessDate = "2026-11-25",
            updates = mapOf("ta1" to true),
            scannedAtEpochMs = 2_000L,
        )

        assertTrue(store.isOfficiallyLitToday("ta1", "2026-11-25"))
        assertTrue(store.isOfficiallyLitToday("tb1", "2026-11-24"))
        assertEquals(setOf("ta1"), store.todayLitTargetIds("acct_a", "2026-11-25"))
        assertEquals(setOf("tb1"), store.todayLitTargetIds("acct_b", "2026-11-24"))
        // 超过 1 天跨度的陈旧记录已自动清理
        assertNull(store.records.value["tc_old"])

        store.clearForTarget("ta1")
        assertFalse(store.isOfficiallyLitToday("ta1", "2026-11-25"))
        assertTrue(store.isOfficiallyLitToday("tb1", "2026-11-24"))

        store.clearForAccount("acct_b")
        assertTrue(store.records.value.isEmpty())
    }

    @Test
    fun jsonCodecRoundTripsAndGlobalHolderAttachesCleanly() {
        val original = mapOf(
            "t1" to OfficialSparkLitRecord(
                targetId = "t1",
                accountId = "default",
                businessDate = "2026-11-25",
                litToday = true,
                scannedAtEpochMs = 1_764_000_000_000L,
            ),
            "t2" to OfficialSparkLitRecord(
                targetId = "t2",
                accountId = "acct_2",
                businessDate = "2026-11-25",
                litToday = false,
                scannedAtEpochMs = 1_764_000_001_000L,
            ),
        )
        val encoded = OfficialSparkLitStore.encodeRecords(original)
        val decoded = OfficialSparkLitStore.decodeRecords(encoded)
        assertEquals(original, decoded)
        assertEquals(emptyMap<String, OfficialSparkLitRecord>(), OfficialSparkLitStore.decodeRecords(null))
        assertEquals(emptyMap<String, OfficialSparkLitRecord>(), OfficialSparkLitStore.decodeRecords("not-json"))

        val store = OfficialSparkLitStore(initialRecords = decoded)
        assertNull(OfficialSparkLitStore.globalOrNull())
        OfficialSparkLitStore.attachGlobal(store)
        assertTrue(OfficialSparkLitStore.globalOrNull() === store)
    }
}
