package io.github.oinzc.flamecontractor.core.storage

import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.execution.ExecutionServiceStoreOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulePreferencesStoreTest {

    @Test
    fun defaultsToFalseAndIsolatesPerAccountWithPersistenceCallback() {
        val persisted = mutableListOf<Set<String>>()
        val store = SchedulePreferencesStore(
            initialSkipOfficialLitAccountIds = emptySet(),
            onPersist = { persisted += it },
        )

        // 默认全账号关闭，完整保留既有定时发送行为
        assertFalse(store.isSkipOfficialLitToday("default"))
        assertFalse(store.isSkipOfficialLitToday("acct-2"))

        store.setSkipOfficialLitToday("default", true)
        assertTrue(store.isSkipOfficialLitToday("default"))
        assertFalse(store.isSkipOfficialLitToday("acct-2"))
        assertEquals(listOf(setOf("default")), persisted)

        // 幂等重复设置不触发冗余落盘
        store.setSkipOfficialLitToday("default", true)
        assertEquals(1, persisted.size)

        store.setSkipOfficialLitToday("acct-2", true)
        assertEquals(setOf("default", "acct-2"), persisted.last())

        store.clearForAccount("default")
        assertFalse(store.isSkipOfficialLitToday("default"))
        assertTrue(store.isSkipOfficialLitToday("acct-2"))
        assertEquals(setOf("acct-2"), persisted.last())
    }

    @Test
    fun filterScheduledTargetsSkipsOnlyOfficiallyLitAndPreservesSafetyGate() {
        val litStore = OfficialSparkLitStore()
        litStore.recordScanResults(
            accountId = "default",
            businessDate = "2026-11-27",
            updates = mapOf(
                "t-lit" to true,
                "t-gray" to false,
                "t-unknown-lit" to true,
                "t-unobserved" to null,
            ),
            scannedAtEpochMs = 1_700_000_000_000L,
        )

        val targets = listOf(
            ContractTargetEntity(id = "t-lit", accountId = "default", nicknameSnapshot = "今日已亮"),
            ContractTargetEntity(id = "t-gray", accountId = "default", nicknameSnapshot = "今日灰火"),
            ContractTargetEntity(id = "t-unobserved", accountId = "default", nicknameSnapshot = "未观测好友"),
            ContractTargetEntity(id = "t-unknown-lit", accountId = "default", nicknameSnapshot = "待复核好友"),
        )
        val jobsByTarget = mapOf(
            "t-unknown-lit" to listOf(
                SendJobEntity(
                    id = "job-unk",
                    accountId = "default",
                    targetId = "t-unknown-lit",
                    businessDate = "2026-11-27",
                    messageSlotId = "daily",
                    status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                ),
            ),
        )

        val plan = ExecutionServiceStoreOps.filterScheduledTargetsByOfficialLit(
            dueTargets = targets,
            jobsByTarget = jobsByTarget,
            businessDate = "2026-11-27",
            officialSparkLitStore = litStore,
        )

        // 灰火与未观测好友均保守发送；今日已亮跳过；待复核好友被安全闸门拦截（既不发也不伪装成“已亮跳过”）
        assertEquals(listOf("t-gray", "t-unobserved"), plan.targetsToRun.map { it.id })
        assertEquals(listOf("t-lit"), plan.officialLitSkippedTargets.map { it.id })
    }
}
