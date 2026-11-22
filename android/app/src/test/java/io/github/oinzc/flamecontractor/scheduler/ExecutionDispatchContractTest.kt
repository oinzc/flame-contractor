package io.github.oinzc.flamecontractor.scheduler

import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.core.notify.UrgentCategory
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.domain.SparkDeteriorationAlert
import io.github.oinzc.flamecontractor.execution.ExecutionServiceStoreOps
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionDispatchContractTest {

    private val today = LocalDate.of(2026, 11, 22)
    private val now = Instant.parse("2026-11-22T12:00:00Z")

    private fun scheduledTarget(id: String = "t-1"): ScheduledTarget {
        val target = ContractTargetEntity(
            id = id,
            accountId = DEFAULT_ACCOUNT_ID,
            nicknameSnapshot = "好友-$id",
        )
        return ScheduledTarget(
            target = target,
            schedule = TargetDueSchedule(
                targetId = id,
                businessDate = today,
                isFixedTime = false,
                baseDueAt = now,
                dueAt = now,
                cutoffAt = now.plusSeconds(3600),
            ),
            effectiveDueAt = now,
            cutoffAt = now.plusSeconds(3600),
        )
    }

    @Test
    fun sendTakesPriorityOverAutoScanWhenBothDue() {
        val sendEvaluation = ScheduleEvaluation(
            businessDate = today,
            evaluatedAt = now,
            shouldTriggerAuthRecheck = false,
            dueTargets = listOf(scheduledTarget()),
        )
        val autoScanEvaluation = AutoScanEvaluation(
            eligible = true,
            shouldTriggerNow = true,
            nextAutoScanAt = now,
            blockedBySendWindow = false,
        )

        val action = ExecutionDispatchContract.decideAction(sendEvaluation, autoScanEvaluation)
        assertEquals(ExecutionDispatchContract.ACTION_RUN_SCHEDULED, action)
    }

    @Test
    fun authRecheckTakesPriorityOverScheduledAndAutoScan() {
        val sendEvaluation = ScheduleEvaluation(
            businessDate = today,
            evaluatedAt = now,
            shouldTriggerAuthRecheck = true,
            dueTargets = listOf(scheduledTarget()),
        )
        val autoScanEvaluation = AutoScanEvaluation(
            eligible = true,
            shouldTriggerNow = true,
            nextAutoScanAt = now,
            blockedBySendWindow = false,
        )

        val action = ExecutionDispatchContract.decideAction(sendEvaluation, autoScanEvaluation)
        assertEquals(ExecutionDispatchContract.ACTION_AUTH_RECHECK, action)
    }

    @Test
    fun dispatchesAutoScanOnlyWhenNoSendOrAuthRecheckDue() {
        val idleEvaluation = ScheduleEvaluation(
            businessDate = today,
            evaluatedAt = now,
            shouldTriggerAuthRecheck = false,
            dueTargets = emptyList(),
        )
        val autoScanEvaluation = AutoScanEvaluation(
            eligible = true,
            shouldTriggerNow = true,
            nextAutoScanAt = now,
            blockedBySendWindow = false,
        )

        val action = ExecutionDispatchContract.decideAction(idleEvaluation, autoScanEvaluation)
        assertEquals(ExecutionDispatchContract.ACTION_RUN_AUTO_SCAN, action)
    }

    @Test
    fun returnsNullWhenNothingIsDueAndExposesDedicatedNotificationAndAlarmIds() {
        val idleEvaluation = ScheduleEvaluation(
            businessDate = today,
            evaluatedAt = now,
            shouldTriggerAuthRecheck = false,
            dueTargets = emptyList(),
        )
        val waitingAutoScan = AutoScanEvaluation(
            eligible = true,
            shouldTriggerNow = false,
            nextAutoScanAt = now.plusSeconds(3600),
            blockedBySendWindow = false,
        )

        assertNull(ExecutionDispatchContract.decideAction(idleEvaluation, null))
        assertNull(ExecutionDispatchContract.decideAction(idleEvaluation, waitingAutoScan))
        assertEquals(3106, LocalNotifier.notificationIdFor(UrgentCategory.SPARK_DETERIORATING))
        assertEquals(4104, ScheduleCoordinator.REQUEST_CODE_AUTO_SCAN)
        assertEquals("auto_scan", ScheduleCoordinator.KIND_AUTO_SCAN)
    }

    @Test
    fun formatsSparkDeteriorationAlertBodyWithMaxThreeNamesAndSanitizedSummary() {
        assertNull(ExecutionServiceStoreOps.formatSparkDeteriorationAlertSummary(emptyList()))

        val alerts = listOf(
            SparkDeteriorationAlert("t-1", "好友A", "12天 → 3天后消失"),
            SparkDeteriorationAlert("t-2", "好友B", "2天后消失 → 重燃中 1/3"),
            SparkDeteriorationAlert("t-3", "好友C", "8天 → 无火花"),
            SparkDeteriorationAlert("t-4", "敏感昵称D", "5天 → 1天后消失"),
        )
        val summary = ExecutionServiceStoreOps.formatSparkDeteriorationAlertSummary(alerts)
        assertEquals("发现 4 位好友火花告急", summary)
        assertFalse(summary!!.contains("好友A"))
        assertFalse(summary.contains("敏感昵称D"))

        val body = ExecutionServiceStoreOps.formatSparkDeteriorationAlertBody(alerts)
        assertTrue(body.contains("好友A（12天 → 3天后消失）"))
        assertTrue(body.contains("好友B（2天后消失 → 重燃中 1/3）"))
        assertTrue(body.contains("好友C（8天 → 无火花）"))
        assertFalse(body.contains("敏感昵称D"))
        assertTrue(body.contains("等 4 位好友"))
        assertTrue(body.contains("仅只读预警，不会自动重发"))
    }
}
