package io.github.oinzc.flamecontractor.domain

import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountHealthReducerTest {

    private fun account(
        status: AccountStatus = AccountStatus.HEALTHY,
        authFailCount: Int = 0,
        failureStreak: Int = 0,
    ): AccountEntity = AccountEntity(
        id = DEFAULT_ACCOUNT_ID,
        displayName = "测试账号",
        status = status,
        authFailCount = authFailCount,
        failureStreak = failureStreak,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Test
    fun ignoresNetworkErrorsInAuthAndFailureStreak() {
        val initial = account(status = AccountStatus.HEALTHY, authFailCount = 1, failureStreak = 2)

        val authResult = AccountHealthReducer.onAuthProbeResult(
            account = initial,
            outcome = AuthProbeOutcome.NETWORK_ERROR,
            nowEpochMs = 2_000L,
        )
        assertEquals(AccountStatus.HEALTHY, authResult.account.status)
        assertEquals(1, authResult.account.authFailCount)
        assertEquals(2, authResult.account.failureStreak)
        assertFalse(authResult.scheduleRecheckIn10Min)
        assertFalse(authResult.shouldNotifyUrgent)

        val afterNetworkBatch = AccountHealthReducer.onBatchFinished(
            account = initial,
            outcomes = listOf(
                BatchTargetOutcome(JobStatus.FAILED_TERMINAL, FailureClass.NETWORK),
                BatchTargetOutcome(JobStatus.FAILED_TERMINAL, FailureClass.TARGET_UNCERTAIN),
            ),
            nowEpochMs = 3_000L,
        )
        assertEquals(AccountStatus.HEALTHY, afterNetworkBatch.status)
        assertEquals(2, afterNetworkBatch.failureStreak)
    }

    @Test
    fun requiresTwoConsecutiveAuthFailuresForAuthExpired() {
        assertEquals(2, AccountHealthReducer.AUTH_CHECK_TOLERANCE)

        val initial = account(status = AccountStatus.HEALTHY, authFailCount = 0)

        // 第 1 次未登录：容忍保持 HEALTHY，并预约 10 分钟后复判
        val firstFail = AccountHealthReducer.onAuthProbeResult(
            account = initial,
            outcome = AuthProbeOutcome.UNAUTHENTICATED,
            nowEpochMs = 2_000L,
        )
        assertEquals(AccountStatus.HEALTHY, firstFail.account.status)
        assertEquals(1, firstFail.account.authFailCount)
        assertTrue(firstFail.scheduleRecheckIn10Min)
        assertFalse(firstFail.shouldNotifyUrgent)

        // 若期间探测通过，则清零计数
        val recoveredInMiddle = AccountHealthReducer.onAuthProbeResult(
            account = firstFail.account,
            outcome = AuthProbeOutcome.AUTHENTICATED,
            nowEpochMs = 2_500L,
        )
        assertEquals(AccountStatus.HEALTHY, recoveredInMiddle.account.status)
        assertEquals(0, recoveredInMiddle.account.authFailCount)
        assertEquals(2_500L, recoveredInMiddle.account.lastVerifiedAt)

        // 连续第 2 次未登录：正式转 AUTH_EXPIRED 并紧急告警
        val secondFail = AccountHealthReducer.onAuthProbeResult(
            account = firstFail.account,
            outcome = AuthProbeOutcome.UNAUTHENTICATED,
            nowEpochMs = 3_000L,
        )
        assertEquals(AccountStatus.AUTH_EXPIRED, secondFail.account.status)
        assertEquals(2, secondFail.account.authFailCount)
        assertFalse(secondFail.scheduleRecheckIn10Min)
        assertTrue(secondFail.shouldNotifyUrgent)

        // 重新扫码成功后清零 authFailCount 并恢复 HEALTHY
        val reloggedIn = AccountHealthReducer.onAuthProbeResult(
            account = secondFail.account,
            outcome = AuthProbeOutcome.AUTHENTICATED,
            nowEpochMs = 4_000L,
        )
        assertEquals(AccountStatus.HEALTHY, reloggedIn.account.status)
        assertEquals(0, reloggedIn.account.authFailCount)
    }

    @Test
    fun triggersVerificationRequiredImmediatelyWithoutTolerance() {
        val initial = account(status = AccountStatus.HEALTHY, authFailCount = 0)

        val verifyResult = AccountHealthReducer.onAuthProbeResult(
            account = initial,
            outcome = AuthProbeOutcome.VERIFICATION_REQUIRED,
            nowEpochMs = 2_000L,
        )
        assertEquals(AccountStatus.VERIFICATION_REQUIRED, verifyResult.account.status)
        assertFalse(verifyResult.scheduleRecheckIn10Min)
        assertTrue(verifyResult.shouldNotifyUrgent)

        // 完成风控验证后自动恢复 HEALTHY 并清零 authFailCount
        val verified = AccountHealthReducer.onAuthProbeResult(
            account = verifyResult.account.copy(authFailCount = 1),
            outcome = AuthProbeOutcome.AUTHENTICATED,
            nowEpochMs = 3_000L,
        )
        assertEquals(AccountStatus.HEALTHY, verified.account.status)
        assertEquals(0, verified.account.authFailCount)
    }

    @Test
    fun incrementsFailureStreakAtMostOncePerBatchAndCooldownsAt3() {
        assertEquals(3, AccountHealthReducer.FAILURE_STREAK_LIMIT)

        // 单批 4 人全部非网络 FAILED_TERMINAL：整批只 +1，且单个/多个 TARGET_UNCERTAIN 不触发 DOM_BROKEN
        val batch1 = AccountHealthReducer.onBatchFinished(
            account = account(status = AccountStatus.RUNNING, failureStreak = 0),
            outcomes = listOf(
                BatchTargetOutcome(JobStatus.FAILED_TERMINAL, FailureClass.TARGET_UNCERTAIN),
                BatchTargetOutcome(JobStatus.FAILED_TERMINAL, FailureClass.TARGET_UNCERTAIN),
                BatchTargetOutcome(JobStatus.FAILED_TERMINAL, FailureClass.OTHER),
                BatchTargetOutcome(JobStatus.FAILED_TERMINAL, FailureClass.OTHER),
            ),
            nowEpochMs = 10_000L,
        )
        assertEquals(1, batch1.failureStreak)
        assertEquals(AccountStatus.HEALTHY, batch1.status)

        // 含 UNKNOWN_REQUIRES_REVIEW 或 AUTH 失败的批次不计入 failureStreak
        val batchWithUnknown = AccountHealthReducer.onBatchFinished(
            account = batch1,
            outcomes = listOf(
                BatchTargetOutcome(JobStatus.UNKNOWN_REQUIRES_REVIEW, FailureClass.UNKNOWN_OUTCOME),
                BatchTargetOutcome(JobStatus.FAILED_TERMINAL, FailureClass.OTHER),
            ),
            nowEpochMs = 11_000L,
        )
        assertEquals(1, batchWithUnknown.failureStreak)
        assertEquals(AccountStatus.HEALTHY, batchWithUnknown.status)

        val batchWithAuth = AccountHealthReducer.onBatchFinished(
            account = batch1,
            outcomes = listOf(
                BatchTargetOutcome(JobStatus.FAILED_TERMINAL, FailureClass.AUTH),
            ),
            nowEpochMs = 12_000L,
        )
        assertEquals(1, batchWithAuth.failureStreak)

        // 第 2 批全员非网络终态失败 -> streak = 2，仍为 HEALTHY
        val batch2 = AccountHealthReducer.onBatchFinished(
            account = batch1,
            outcomes = listOf(BatchTargetOutcome(JobStatus.FAILED_TERMINAL, FailureClass.TARGET_UNCERTAIN)),
            nowEpochMs = 20_000L,
        )
        assertEquals(2, batch2.failureStreak)
        assertEquals(AccountStatus.HEALTHY, batch2.status)

        // 第 3 批全员非网络终态失败 -> streak = 3，触发 COOLDOWN 熔断
        val batch3 = AccountHealthReducer.onBatchFinished(
            account = batch2,
            outcomes = listOf(BatchTargetOutcome(JobStatus.FAILED_TERMINAL, FailureClass.OTHER)),
            nowEpochMs = 30_000L,
        )
        assertEquals(3, batch3.failureStreak)
        assertEquals(AccountStatus.COOLDOWN, batch3.status)
    }

    @Test
    fun resetsStreakOnAnySuccessAndSupportsExplicitRecovery() {
        // 只要同批内有任意 1 个 SUCCEEDED，即清零 failureStreak
        val withPartialSuccess = AccountHealthReducer.onBatchFinished(
            account = account(status = AccountStatus.RUNNING, failureStreak = 2),
            outcomes = listOf(
                BatchTargetOutcome(JobStatus.FAILED_TERMINAL, FailureClass.TARGET_UNCERTAIN),
                BatchTargetOutcome(JobStatus.SUCCEEDED, null),
            ),
            nowEpochMs = 5_000L,
        )
        assertEquals(0, withPartialSuccess.failureStreak)
        assertEquals(AccountStatus.HEALTHY, withPartialSuccess.status)
        assertEquals(5_000L, withPartialSuccess.lastSuccessAt)

        // COOLDOWN 需通过 recoverFromCooldown 显式恢复，普通 AUTHENTICATED 探活不会绕过 COOLDOWN
        val cooledDown = account(status = AccountStatus.COOLDOWN, failureStreak = 3)
        val probeWhileCooldown = AccountHealthReducer.onAuthProbeResult(
            account = cooledDown,
            outcome = AuthProbeOutcome.AUTHENTICATED,
            nowEpochMs = 6_000L,
        )
        assertEquals(AccountStatus.COOLDOWN, probeWhileCooldown.account.status)
        assertEquals(3, probeWhileCooldown.account.failureStreak)

        val recoveredCooldown = AccountHealthReducer.recoverFromCooldown(cooledDown, nowEpochMs = 7_000L)
        assertEquals(AccountStatus.HEALTHY, recoveredCooldown.status)
        assertEquals(0, recoveredCooldown.failureStreak)

        // 核心骨架损坏触发 DOM_BROKEN，需通过 recoverFromDomBroken 显式恢复
        val domBroken = AccountHealthReducer.onBatchFinished(
            account = account(status = AccountStatus.RUNNING, failureStreak = 1),
            outcomes = listOf(
                BatchTargetOutcome(
                    status = JobStatus.FAILED_TERMINAL,
                    failureClass = FailureClass.DOM_CHANGED,
                    coreSkeletonBroken = true,
                ),
            ),
            nowEpochMs = 8_000L,
        )
        assertEquals(AccountStatus.DOM_BROKEN, domBroken.status)

        val probeWhileDomBroken = AccountHealthReducer.onAuthProbeResult(
            account = domBroken,
            outcome = AuthProbeOutcome.AUTHENTICATED,
            nowEpochMs = 8_500L,
        )
        assertEquals(AccountStatus.DOM_BROKEN, probeWhileDomBroken.account.status)

        val recoveredDom = AccountHealthReducer.recoverFromDomBroken(domBroken, nowEpochMs = 9_000L)
        assertEquals(AccountStatus.HEALTHY, recoveredDom.status)
    }

    @Test
    fun guardsCrossStateRecoveryAndHandlesPendingLoginAndRunningEdgeCases() {
        // PENDING_LOGIN 首次扫码通过后恢复 HEALTHY
        val firstLogin = AccountHealthReducer.onAuthProbeResult(
            account = account(status = AccountStatus.PENDING_LOGIN, authFailCount = 0),
            outcome = AuthProbeOutcome.AUTHENTICATED,
            nowEpochMs = 1_500L,
        )
        assertEquals(AccountStatus.HEALTHY, firstLogin.account.status)
        assertEquals(0, firstLogin.account.authFailCount)

        // RUNNING 收到第 1 次 UNAUTHENTICATED 回到 HEALTHY 并预约 10 分钟复判
        val runningFirstFail = AccountHealthReducer.onAuthProbeResult(
            account = account(status = AccountStatus.RUNNING, authFailCount = 0),
            outcome = AuthProbeOutcome.UNAUTHENTICATED,
            nowEpochMs = 2_000L,
        )
        assertEquals(AccountStatus.HEALTHY, runningFirstFail.account.status)
        assertEquals(1, runningFirstFail.account.authFailCount)
        assertTrue(runningFirstFail.scheduleRecheckIn10Min)

        // 显式恢复函数不得误解锁其他异常态
        val authExpired = account(status = AccountStatus.AUTH_EXPIRED, authFailCount = 2, failureStreak = 2)
        assertEquals(AccountStatus.AUTH_EXPIRED, AccountHealthReducer.recoverFromCooldown(authExpired).status)
        assertEquals(AccountStatus.AUTH_EXPIRED, AccountHealthReducer.recoverFromDomBroken(authExpired).status)

        // 空批次或仅 FAILED_RETRYABLE 均不递增 failureStreak，且 RUNNING 回到 HEALTHY
        val emptyBatch = AccountHealthReducer.onBatchFinished(
            account = account(status = AccountStatus.RUNNING, failureStreak = 2),
            outcomes = emptyList(),
            nowEpochMs = 3_000L,
        )
        assertEquals(AccountStatus.HEALTHY, emptyBatch.status)
        assertEquals(2, emptyBatch.failureStreak)

        val retryableBatch = AccountHealthReducer.onBatchFinished(
            account = account(status = AccountStatus.RUNNING, failureStreak = 2),
            outcomes = listOf(BatchTargetOutcome(JobStatus.FAILED_RETRYABLE, FailureClass.OTHER)),
            nowEpochMs = 4_000L,
        )
        assertEquals(AccountStatus.HEALTHY, retryableBatch.status)
        assertEquals(2, retryableBatch.failureStreak)

        // 同批内既有 SUCCEEDED 又有 coreSkeletonBroken：清零 failureStreak 同时锁定 DOM_BROKEN
        val successThenDomBroken = AccountHealthReducer.onBatchFinished(
            account = account(status = AccountStatus.RUNNING, failureStreak = 2),
            outcomes = listOf(
                BatchTargetOutcome(JobStatus.SUCCEEDED, null),
                BatchTargetOutcome(JobStatus.FAILED_TERMINAL, FailureClass.DOM_CHANGED, coreSkeletonBroken = true),
            ),
            nowEpochMs = 5_000L,
        )
        assertEquals(AccountStatus.DOM_BROKEN, successThenDomBroken.status)
        assertEquals(0, successThenDomBroken.failureStreak)
        assertEquals(5_000L, successThenDomBroken.lastSuccessAt)
    }
}
