package io.github.oinzc.flamecontractor.domain

import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendJobEntity

enum class ManualSendDecision {
    BLOCKED_UNKNOWN_OR_INFLIGHT,
    CONFIRM_ALREADY_SUCCEEDED,
    ALLOWED,
}

/**
 * 安全发送闸门与崩溃僵尸单收敛规则。
 *
 * 硬守则：
 * 1. 当日任意槽位存在 UNKNOWN_REQUIRES_REVIEW / SENDING / CONFIRMING 时，
 *    自动调度、首页一键续火与好友卡片单发测试全路径硬禁止。
 * 2. 当日 `daily` 槽位一旦 SUCCEEDED / CANCELLED / FAILED_TERMINAL，即使改模板也绝不自动重发。
 * 3. FAILED_RETRYABLE 每日每单最多自动重试 RETRY_MAX_COUNT = 2 次（总尝试 < 3）。
 * 4. 崩溃遗留的 SENDING / CONFIRMING 必转 UNKNOWN_REQUIRES_REVIEW，绝不自动补发。
 */
object SendGuard {

    const val DAILY_SLOT_ID = "daily"
    const val MANUAL_SLOT_PREFIX = "manual:"
    const val RETRY_MAX_COUNT = 2

    private val BLOCKING_STATUSES: Set<JobStatus> = setOf(
        JobStatus.UNKNOWN_REQUIRES_REVIEW,
        JobStatus.SENDING,
        JobStatus.CONFIRMING,
    )

    val CRASH_IN_PROGRESS_STATUSES: Set<JobStatus> = setOf(
        JobStatus.LEASED,
        JobStatus.OPENING_CHAT,
        JobStatus.SENDING,
        JobStatus.CONFIRMING,
    )

    fun manualSlotId(epochMs: Long): String = "$MANUAL_SLOT_PREFIX$epochMs"

    fun hasBlockingUnknownOrInflight(todayJobs: List<SendJobEntity>): Boolean =
        todayJobs.any { it.status in BLOCKING_STATUSES }

    fun canDispatchDaily(todayJobs: List<SendJobEntity>): Boolean {
        if (hasBlockingUnknownOrInflight(todayJobs)) return false
        val dailyJobs = todayJobs.filter { it.messageSlotId == DAILY_SLOT_ID }
        if (dailyJobs.isEmpty()) return true
        return dailyJobs.all { job ->
            when (job.status) {
                JobStatus.SCHEDULED -> true
                JobStatus.FAILED_RETRYABLE -> job.attemptCount <= RETRY_MAX_COUNT
                JobStatus.LEASED,
                JobStatus.OPENING_CHAT,
                JobStatus.SENDING,
                JobStatus.CONFIRMING,
                JobStatus.SUCCEEDED,
                JobStatus.FAILED_TERMINAL,
                JobStatus.UNKNOWN_REQUIRES_REVIEW,
                JobStatus.CANCELLED,
                -> false
            }
        }
    }

    fun checkManualSend(todayJobs: List<SendJobEntity>): ManualSendDecision {
        if (hasBlockingUnknownOrInflight(todayJobs)) {
            return ManualSendDecision.BLOCKED_UNKNOWN_OR_INFLIGHT
        }
        if (todayJobs.any { it.status == JobStatus.SUCCEEDED }) {
            return ManualSendDecision.CONFIRM_ALREADY_SUCCEEDED
        }
        return ManualSendDecision.ALLOWED
    }

    fun classifyRetryableFailure(attemptCount: Int): JobStatus =
        if (attemptCount <= RETRY_MAX_COUNT) {
            JobStatus.FAILED_RETRYABLE
        } else {
            JobStatus.FAILED_TERMINAL
        }

    fun classifyCrashedJob(job: SendJobEntity): JobStatus =
        when (job.status) {
            JobStatus.SENDING,
            JobStatus.CONFIRMING,
            -> JobStatus.UNKNOWN_REQUIRES_REVIEW

            JobStatus.LEASED,
            JobStatus.OPENING_CHAT,
            -> classifyRetryableFailure(job.attemptCount)

            JobStatus.SCHEDULED,
            JobStatus.SUCCEEDED,
            JobStatus.FAILED_RETRYABLE,
            JobStatus.FAILED_TERMINAL,
            JobStatus.UNKNOWN_REQUIRES_REVIEW,
            JobStatus.CANCELLED,
            -> job.status
        }

    fun recoverCrashedJobs(
        jobs: List<SendJobEntity>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<SendJobEntity> =
        jobs.mapNotNull { job ->
            val nextStatus = classifyCrashedJob(job)
            if (nextStatus == job.status) {
                null
            } else {
                val nextFailureClass = when (nextStatus) {
                    JobStatus.UNKNOWN_REQUIRES_REVIEW -> FailureClass.UNKNOWN_OUTCOME
                    JobStatus.FAILED_RETRYABLE,
                    JobStatus.FAILED_TERMINAL,
                    -> job.failureClass ?: FailureClass.OTHER

                    else -> job.failureClass
                }
                val detail = when (nextStatus) {
                    JobStatus.UNKNOWN_REQUIRES_REVIEW -> "进程中断时已进入发送或确认阶段，请核对发送结果"
                    JobStatus.FAILED_RETRYABLE -> "进程中断，已回收到可重试状态"
                    JobStatus.FAILED_TERMINAL -> "进程中断且已达当日重试上限"
                    else -> job.summaryDetail
                }
                job.copy(
                    status = nextStatus,
                    failureClass = nextFailureClass,
                    summaryDetail = detail,
                    updatedAt = nowEpochMs,
                )
            }
        }
}
