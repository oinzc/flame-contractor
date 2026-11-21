package io.github.oinzc.flamecontractor.domain

import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus

enum class AuthProbeOutcome {
    AUTHENTICATED,
    UNAUTHENTICATED,
    VERIFICATION_REQUIRED,
    NETWORK_ERROR,
}

data class AuthTransitionResult(
    val account: AccountEntity,
    val scheduleRecheckIn10Min: Boolean = false,
    val shouldNotifyUrgent: Boolean = false,
)

data class BatchTargetOutcome(
    val status: JobStatus,
    val failureClass: FailureClass? = null,
    val coreSkeletonBroken: Boolean = false,
)

/**
 * 账号健康状态机：
 * - 连续鉴权容忍 AUTH_CHECK_TOLERANCE = 2；NETWORK_ERROR 不计数不判失效；
 *   VERIFICATION_REQUIRED 跳过容忍立即锁停。
 * - failureStreak 按批次粒度最多 +1（仅全员非网络 FAILED_TERMINAL 且无成功/待复核/鉴权失败），
 *   任意目标 SUCCEEDED 立即清零；>= FAILURE_STREAK_LIMIT (3) 转 COOLDOWN。
 * - 核心页面骨架失效转 DOM_BROKEN；COOLDOWN 与 DOM_BROKEN 均需显式人工恢复。
 */
object AccountHealthReducer {

    const val AUTH_CHECK_TOLERANCE = 2
    const val FAILURE_STREAK_LIMIT = 3

    fun onAuthProbeResult(
        account: AccountEntity,
        outcome: AuthProbeOutcome,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AuthTransitionResult =
        when (outcome) {
            AuthProbeOutcome.NETWORK_ERROR -> AuthTransitionResult(
                account = account,
                scheduleRecheckIn10Min = false,
                shouldNotifyUrgent = false,
            )

            AuthProbeOutcome.VERIFICATION_REQUIRED -> AuthTransitionResult(
                account = account.copy(
                    status = AccountStatus.VERIFICATION_REQUIRED,
                    updatedAt = nowEpochMs,
                ),
                scheduleRecheckIn10Min = false,
                shouldNotifyUrgent = true,
            )

            AuthProbeOutcome.UNAUTHENTICATED -> {
                val nextFailCount = account.authFailCount + 1
                val reachedThreshold = nextFailCount >= AUTH_CHECK_TOLERANCE
                val nextStatus = when {
                    reachedThreshold -> AccountStatus.AUTH_EXPIRED
                    account.status == AccountStatus.RUNNING -> AccountStatus.HEALTHY
                    else -> account.status
                }
                AuthTransitionResult(
                    account = account.copy(
                        status = nextStatus,
                        authFailCount = nextFailCount,
                        updatedAt = nowEpochMs,
                    ),
                    scheduleRecheckIn10Min = !reachedThreshold,
                    shouldNotifyUrgent = reachedThreshold,
                )
            }

            AuthProbeOutcome.AUTHENTICATED -> {
                val nextStatus = when (account.status) {
                    AccountStatus.PENDING_LOGIN,
                    AccountStatus.AUTH_EXPIRED,
                    AccountStatus.VERIFICATION_REQUIRED,
                    AccountStatus.RUNNING,
                    -> AccountStatus.HEALTHY

                    AccountStatus.HEALTHY,
                    AccountStatus.PAUSED,
                    AccountStatus.DOM_BROKEN,
                    AccountStatus.COOLDOWN,
                    -> account.status
                }
                AuthTransitionResult(
                    account = account.copy(
                        status = nextStatus,
                        authFailCount = 0,
                        lastVerifiedAt = nowEpochMs,
                        updatedAt = nowEpochMs,
                    ),
                    scheduleRecheckIn10Min = false,
                    shouldNotifyUrgent = false,
                )
            }
        }

    fun onBatchFinished(
        account: AccountEntity,
        outcomes: List<BatchTargetOutcome>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AccountEntity {
        val baseStatus = if (account.status == AccountStatus.RUNNING) {
            AccountStatus.HEALTHY
        } else {
            account.status
        }
        if (outcomes.isEmpty()) {
            return if (baseStatus != account.status) {
                account.copy(status = baseStatus, updatedAt = nowEpochMs)
            } else {
                account
            }
        }

        val hasSuccess = outcomes.any { it.status == JobStatus.SUCCEEDED }
        val hasCoreDomBroken = outcomes.any {
            it.coreSkeletonBroken || it.failureClass == FailureClass.DOM_CHANGED
        }

        if (hasCoreDomBroken) {
            return account.copy(
                status = AccountStatus.DOM_BROKEN,
                failureStreak = if (hasSuccess) 0 else account.failureStreak,
                lastSuccessAt = if (hasSuccess) nowEpochMs else account.lastSuccessAt,
                updatedAt = nowEpochMs,
            )
        }

        if (hasSuccess) {
            return account.copy(
                status = baseStatus,
                failureStreak = 0,
                lastSuccessAt = nowEpochMs,
                updatedAt = nowEpochMs,
            )
        }

        val accountBlockedByAuth = baseStatus == AccountStatus.AUTH_EXPIRED ||
            baseStatus == AccountStatus.VERIFICATION_REQUIRED
        val hasExcludedOutcome = accountBlockedByAuth || outcomes.any { outcome ->
            outcome.status != JobStatus.FAILED_TERMINAL ||
                outcome.failureClass == FailureClass.NETWORK ||
                outcome.failureClass == FailureClass.AUTH ||
                outcome.failureClass == FailureClass.VERIFICATION ||
                outcome.failureClass == FailureClass.UNKNOWN_OUTCOME
        }

        if (hasExcludedOutcome) {
            return if (baseStatus != account.status) {
                account.copy(status = baseStatus, updatedAt = nowEpochMs)
            } else {
                account
            }
        }

        val nextStreak = account.failureStreak + 1
        val nextStatus = if (nextStreak >= FAILURE_STREAK_LIMIT) {
            AccountStatus.COOLDOWN
        } else {
            baseStatus
        }
        return account.copy(
            status = nextStatus,
            failureStreak = nextStreak,
            updatedAt = nowEpochMs,
        )
    }

    fun onSingleSendSucceeded(
        account: AccountEntity,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AccountEntity = onBatchFinished(
        account = account,
        outcomes = listOf(BatchTargetOutcome(status = JobStatus.SUCCEEDED)),
        nowEpochMs = nowEpochMs,
    )

    fun recoverFromCooldown(
        account: AccountEntity,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AccountEntity {
        if (account.status != AccountStatus.COOLDOWN) return account
        return account.copy(
            status = AccountStatus.HEALTHY,
            failureStreak = 0,
            updatedAt = nowEpochMs,
        )
    }

    fun recoverFromDomBroken(
        account: AccountEntity,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AccountEntity {
        if (account.status != AccountStatus.DOM_BROKEN) return account
        return account.copy(
            status = AccountStatus.HEALTHY,
            updatedAt = nowEpochMs,
        )
    }
}
