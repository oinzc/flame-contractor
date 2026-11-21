package io.github.oinzc.flamecontractor.domain

import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendGuardTest {

    private fun job(
        id: String = "job-1",
        slotId: String = SendGuard.DAILY_SLOT_ID,
        status: JobStatus = JobStatus.SCHEDULED,
        attemptCount: Int = 0,
        templateId: String? = "tpl-1",
        failureClass: FailureClass? = null,
    ): SendJobEntity = SendJobEntity(
        id = id,
        accountId = DEFAULT_ACCOUNT_ID,
        targetId = "target-1",
        templateId = templateId,
        businessDate = "2026-11-21",
        messageSlotId = slotId,
        status = status,
        attemptCount = attemptCount,
        failureClass = failureClass,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Test
    fun hardBlocksBothDailyAndManualWhenUnknownOrInflightExistsToday() {
        val blockingStatuses = listOf(
            JobStatus.UNKNOWN_REQUIRES_REVIEW,
            JobStatus.SENDING,
            JobStatus.CONFIRMING,
        )

        for (blockingStatus in blockingStatuses) {
            // 1. 阻塞发生在 daily 槽位
            val dailyBlocking = listOf(job(id = "j-daily", slotId = SendGuard.DAILY_SLOT_ID, status = blockingStatus))
            assertTrue("daily $blockingStatus must block", SendGuard.hasBlockingUnknownOrInflight(dailyBlocking))
            assertFalse("daily $blockingStatus must disable auto daily", SendGuard.canDispatchDaily(dailyBlocking))
            assertEquals(
                ManualSendDecision.BLOCKED_UNKNOWN_OR_INFLIGHT,
                SendGuard.checkManualSend(dailyBlocking),
            )

            // 2. 阻塞发生在 manual 槽位，且同日之前已有 SUCCEEDED，也必须硬禁止（不得降级为二次确认）
            val crossSlotBlocking = listOf(
                job(id = "j-ok", slotId = SendGuard.DAILY_SLOT_ID, status = JobStatus.SUCCEEDED),
                job(id = "j-manual", slotId = SendGuard.manualSlotId(1700000000000L), status = blockingStatus),
            )
            assertTrue("manual $blockingStatus must block", SendGuard.hasBlockingUnknownOrInflight(crossSlotBlocking))
            assertFalse("manual $blockingStatus must block daily", SendGuard.canDispatchDaily(crossSlotBlocking))
            assertEquals(
                ManualSendDecision.BLOCKED_UNKNOWN_OR_INFLIGHT,
                SendGuard.checkManualSend(crossSlotBlocking),
            )
        }
    }

    @Test
    fun skipsDailyDispatchAfterTemplateChangeWhenSucceededOrCancelled() {
        // 当日 daily 已 SUCCEEDED 后即使用户改了模板，自动/批量 daily 仍跳过；单发测试需二次确认
        val succeededAfterTemplateEdit = listOf(
            job(id = "j-ok", status = JobStatus.SUCCEEDED, templateId = "tpl-new-after-edit"),
        )
        assertFalse(SendGuard.canDispatchDaily(succeededAfterTemplateEdit))
        assertEquals(
            ManualSendDecision.CONFIRM_ALREADY_SUCCEEDED,
            SendGuard.checkManualSend(succeededAfterTemplateEdit),
        )

        // 当日 daily 被人工终裁为 CANCELLED 后，自动/批量 daily 跳过；但卡片单发测试允许
        val cancelledAfterReview = listOf(
            job(id = "j-cancel", status = JobStatus.CANCELLED, templateId = "tpl-new-after-edit"),
        )
        assertFalse(SendGuard.canDispatchDaily(cancelledAfterReview))
        assertEquals(
            ManualSendDecision.ALLOWED,
            SendGuard.checkManualSend(cancelledAfterReview),
        )

        // 当日 daily 已 FAILED_TERMINAL，自动/批量 daily 不再重发；单发测试允许
        val terminalDaily = listOf(
            job(
                id = "j-term",
                status = JobStatus.FAILED_TERMINAL,
                attemptCount = 3,
                failureClass = FailureClass.TARGET_UNCERTAIN,
            ),
        )
        assertFalse(SendGuard.canDispatchDaily(terminalDaily))
        assertEquals(
            ManualSendDecision.ALLOWED,
            SendGuard.checkManualSend(terminalDaily),
        )
    }

    @Test
    fun allowsDailyRetryOnlyUpToMaxCount2() {
        assertEquals(2, SendGuard.RETRY_MAX_COUNT)
        assertEquals("daily", SendGuard.DAILY_SLOT_ID)

        // 无记录或 SCHEDULED 可派发
        assertTrue(SendGuard.canDispatchDaily(emptyList()))
        assertTrue(SendGuard.canDispatchDaily(listOf(job(status = JobStatus.SCHEDULED, attemptCount = 0))))
        assertEquals(ManualSendDecision.ALLOWED, SendGuard.checkManualSend(emptyList()))

        // FAILED_RETRYABLE: attemptCount 0/1/2 (总尝试 < 3) 允许继续重试
        assertTrue(SendGuard.canDispatchDaily(listOf(job(status = JobStatus.FAILED_RETRYABLE, attemptCount = 0))))
        assertTrue(SendGuard.canDispatchDaily(listOf(job(status = JobStatus.FAILED_RETRYABLE, attemptCount = 1))))
        assertTrue(SendGuard.canDispatchDaily(listOf(job(status = JobStatus.FAILED_RETRYABLE, attemptCount = 2))))

        // attemptCount == 3 (1 次首轮 + 2 次重试已耗尽) 必须拒绝自动派发
        assertFalse(SendGuard.canDispatchDaily(listOf(job(status = JobStatus.FAILED_RETRYABLE, attemptCount = 3))))

        // LEASED / OPENING_CHAT 执行中禁止重复派发
        assertFalse(SendGuard.canDispatchDaily(listOf(job(status = JobStatus.LEASED, attemptCount = 1))))
        assertFalse(SendGuard.canDispatchDaily(listOf(job(status = JobStatus.OPENING_CHAT, attemptCount = 1))))
    }

    @Test
    fun manualSucceededPromptsConfirmWithoutBlockingSeparateDailySlot() {
        val manualSuccessOnly = listOf(
            job(id = "j-manual", slotId = SendGuard.manualSlotId(1700000001000L), status = JobStatus.SUCCEEDED),
        )

        assertFalse(SendGuard.hasBlockingUnknownOrInflight(manualSuccessOnly))
        assertTrue(SendGuard.canDispatchDaily(manualSuccessOnly))
        assertEquals(
            ManualSendDecision.CONFIRM_ALREADY_SUCCEEDED,
            SendGuard.checkManualSend(manualSuccessOnly),
        )
    }

    @Test
    fun transitionsCrashedSendingOrConfirmingToUnknownRequiresReview() {
        // 不可逆边界：SENDING / CONFIRMING 无论尝试次数多少，崩溃后必转 UNKNOWN_REQUIRES_REVIEW
        for (attempts in 0..4) {
            assertEquals(
                JobStatus.UNKNOWN_REQUIRES_REVIEW,
                SendGuard.classifyCrashedJob(job(status = JobStatus.SENDING, attemptCount = attempts)),
            )
            assertEquals(
                JobStatus.UNKNOWN_REQUIRES_REVIEW,
                SendGuard.classifyCrashedJob(job(status = JobStatus.CONFIRMING, attemptCount = attempts)),
            )
        }

        // 未进入不可逆边界：LEASED / OPENING_CHAT 未超重试上限转 FAILED_RETRYABLE，超限转 FAILED_TERMINAL
        assertEquals(
            JobStatus.FAILED_RETRYABLE,
            SendGuard.classifyCrashedJob(job(status = JobStatus.LEASED, attemptCount = 1)),
        )
        assertEquals(
            JobStatus.FAILED_RETRYABLE,
            SendGuard.classifyCrashedJob(job(status = JobStatus.OPENING_CHAT, attemptCount = 2)),
        )
        assertEquals(
            JobStatus.FAILED_TERMINAL,
            SendGuard.classifyCrashedJob(job(status = JobStatus.LEASED, attemptCount = 3)),
        )
        assertEquals(
            JobStatus.FAILED_TERMINAL,
            SendGuard.classifyCrashedJob(job(status = JobStatus.OPENING_CHAT, attemptCount = 3)),
        )

        // 非运行态保持原状
        assertEquals(
            JobStatus.SUCCEEDED,
            SendGuard.classifyCrashedJob(job(status = JobStatus.SUCCEEDED)),
        )
        assertEquals(
            JobStatus.CANCELLED,
            SendGuard.classifyCrashedJob(job(status = JobStatus.CANCELLED)),
        )

        // 批量僵尸单收敛仅返回状态发生变化的单据
        val recovered = SendGuard.recoverCrashedJobs(
            jobs = listOf(
                job(id = "j-confirm", status = JobStatus.CONFIRMING, attemptCount = 1),
                job(id = "j-open", status = JobStatus.OPENING_CHAT, attemptCount = 1),
                job(id = "j-done", status = JobStatus.SUCCEEDED, attemptCount = 1),
            ),
            nowEpochMs = 9_999L,
        )
        assertEquals(2, recovered.size)
        assertEquals(JobStatus.UNKNOWN_REQUIRES_REVIEW, recovered[0].status)
        assertEquals(FailureClass.UNKNOWN_OUTCOME, recovered[0].failureClass)
        assertEquals(9_999L, recovered[0].updatedAt)
        assertEquals(JobStatus.FAILED_RETRYABLE, recovered[1].status)
    }

    @Test
    fun classifyRetryableFailureRespectsRetryMaxCount2AndCrossSlotManualFailedDoesNotBlockDailyRetry() {
        assertEquals(JobStatus.FAILED_RETRYABLE, SendGuard.classifyRetryableFailure(0))
        assertEquals(JobStatus.FAILED_RETRYABLE, SendGuard.classifyRetryableFailure(1))
        assertEquals(JobStatus.FAILED_RETRYABLE, SendGuard.classifyRetryableFailure(2))
        assertEquals(JobStatus.FAILED_TERMINAL, SendGuard.classifyRetryableFailure(3))

        val jobsWithManualFailure = listOf(
            job(id = "j-daily", slotId = SendGuard.DAILY_SLOT_ID, status = JobStatus.FAILED_RETRYABLE, attemptCount = 2),
            job(id = "j-manual", slotId = SendGuard.manualSlotId(1700000002000L), status = JobStatus.FAILED_TERMINAL),
        )
        assertTrue(SendGuard.canDispatchDaily(jobsWithManualFailure))
        assertEquals(ManualSendDecision.ALLOWED, SendGuard.checkManualSend(jobsWithManualFailure))
    }
}
