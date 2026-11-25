package io.github.oinzc.flamecontractor.execution

import io.github.oinzc.flamecontractor.core.account.WebViewProfileRegistry
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionMutexTest {

    @Test
    fun scopeKeyForAccountIsolatesByProfileWhenMultiProfileSupportedAndDegradesToGlobalOtherwise() {
        assertEquals(
            WebViewProfileRegistry.DEFAULT_PROFILE_NAME,
            ExecutionMutex.scopeKeyForAccount(DEFAULT_ACCOUNT_ID, multiProfileSupported = true),
        )
        assertEquals(
            WebViewProfileRegistry.profileNameForAccount("acct_a"),
            ExecutionMutex.scopeKeyForAccount("acct_a", multiProfileSupported = true),
        )
        assertEquals(
            WebViewProfileRegistry.profileNameForAccount("acct_b"),
            ExecutionMutex.scopeKeyForAccount("acct_b", multiProfileSupported = true),
        )
        assertEquals(
            ExecutionMutex.GLOBAL_SCOPE,
            ExecutionMutex.scopeKeyForAccount("acct_a", multiProfileSupported = false),
        )
        assertEquals(
            ExecutionMutex.GLOBAL_SCOPE,
            ExecutionMutex.scopeKeyForAccount("acct_b", multiProfileSupported = false),
        )
    }

    @Test
    fun differentAccountsAcquireLocksConcurrentlyWithoutBlockingEachOther() = runTest {
        val scopeA = ExecutionMutex.scopeKeyForAccount("acct_a", multiProfileSupported = true)
        val scopeB = ExecutionMutex.scopeKeyForAccount("acct_b", multiProfileSupported = true)
        val scopeC = ExecutionMutex.scopeKeyForAccount("acct_c", multiProfileSupported = true)

        val tokenA = ExecutionMutex.tryAcquireSession(owner = "batch_a", scopeKey = scopeA)
        assertNotNull(tokenA)
        try {
            assertTrue(ExecutionMutex.isLocked())
            assertTrue(ExecutionMutex.isAnyLocked())
            assertTrue(ExecutionMutex.isLocked(scopeA))
            assertFalse(ExecutionMutex.isLocked(scopeB))
            assertFalse(ExecutionMutex.isLocked(scopeC))
            assertEquals("batch_a", ExecutionMutex.currentOwner(scopeA))
            assertNull(ExecutionMutex.currentOwner(scopeB))

            // 账号 A 在后台续火时，账号 B 扫码登录 0 等待立即拿到锁
            val tokenB = ExecutionMutex.tryAcquireSession(owner = "login_b", scopeKey = scopeB)
            assertNotNull(tokenB)
            try {
                assertTrue(ExecutionMutex.isLocked(scopeB))
                assertEquals("login_b", ExecutionMutex.currentOwner(scopeB))

                val ranC = ExecutionMutex.tryWithLock(
                    owner = "scan_c",
                    timeoutMs = 50L,
                    scopeKey = scopeC,
                ) {
                    assertTrue(ExecutionMutex.isLocked(scopeC))
                    "c_ok"
                }
                assertEquals("c_ok", ranC)
                assertFalse(ExecutionMutex.isLocked(scopeC))
            } finally {
                ExecutionMutex.releaseSession(tokenB)
            }
            assertFalse(ExecutionMutex.isLocked(scopeB))
            assertTrue(ExecutionMutex.isLocked(scopeA))
        } finally {
            ExecutionMutex.releaseSession(tokenA)
        }
        assertFalse(ExecutionMutex.isLocked())
        assertFalse(ExecutionMutex.isAnyLocked())
    }

    @Test
    fun sameAccountStrictlyEnforcesMutualExclusionAndWakesWaiterOnRelease() = runTest {
        val scopeA = ExecutionMutex.scopeKeyForAccount("acct_a", multiProfileSupported = true)
        val first = ExecutionMutex.tryAcquireSession(owner = "batch_a", scopeKey = scopeA)
        assertNotNull(first)
        try {
            assertNull(ExecutionMutex.tryAcquireSession(owner = "login_a", scopeKey = scopeA))
            val timedOut = ExecutionMutex.tryWithLock(
                owner = "scan_a",
                timeoutMs = 40L,
                scopeKey = scopeA,
            ) {
                "should_not_run"
            }
            assertNull(timedOut)

            val waiterEntered = CompletableDeferred<Unit>()
            val waiterDone = async {
                ExecutionMutex.withLock(owner = "login_a", scopeKey = scopeA) {
                    waiterEntered.complete(Unit)
                    assertEquals("login_a", ExecutionMutex.currentOwner(scopeA))
                    "acquired_after_release"
                }
            }

            // 释放后等待协程应立即被唤醒并接管同一 scope 锁
            ExecutionMutex.releaseSession(first)
            assertEquals("acquired_after_release", waiterDone.await())
            assertTrue(waiterEntered.isCompleted)
        } finally {
            ExecutionMutex.releaseSession(first)
        }
        assertFalse(ExecutionMutex.isLocked(scopeA))
        assertFalse(ExecutionMutex.isLocked())
    }

    @Test
    fun nonMultiProfileModeDegradesToGlobalMutualExclusion() = runTest {
        val scopeA = ExecutionMutex.scopeKeyForAccount("acct_a", multiProfileSupported = false)
        val scopeB = ExecutionMutex.scopeKeyForAccount("acct_b", multiProfileSupported = false)
        assertEquals(ExecutionMutex.GLOBAL_SCOPE, scopeA)
        assertEquals(ExecutionMutex.GLOBAL_SCOPE, scopeB)

        val tokenA = ExecutionMutex.tryAcquireSession(owner = "batch_a", scopeKey = scopeA)
        assertNotNull(tokenA)
        try {
            assertTrue(ExecutionMutex.isLocked(scopeB))
            assertNull(ExecutionMutex.tryAcquireSession(owner = "login_b", scopeKey = scopeB))
            val blocked = ExecutionMutex.tryWithLock(
                owner = "login_b",
                timeoutMs = 40L,
                scopeKey = scopeB,
            ) {
                "blocked"
            }
            assertNull(blocked)
        } finally {
            ExecutionMutex.releaseSession(tokenA)
        }

        val okB = ExecutionMutex.tryWithLock(
            owner = "login_b",
            timeoutMs = 100L,
            scopeKey = scopeB,
        ) {
            "b_ok"
        }
        assertEquals("b_ok", okB)
        assertFalse(ExecutionMutex.isLocked())
    }

    @Test
    fun globalColdStartLockAndScopedLocksAreMutuallyExclusiveInBothDirections() = runTest {
        val scopeA = ExecutionMutex.scopeKeyForAccount("acct_a", multiProfileSupported = true)
        val scopeB = ExecutionMutex.scopeKeyForAccount("acct_b", multiProfileSupported = true)

        // 方向 1：持有全局冷启动锁期间，任何账号 scope 均不可获取
        val globalToken = ExecutionMutex.tryAcquireGlobalSession("app_cold_start")
        assertNotNull(globalToken)
        try {
            assertTrue(ExecutionMutex.isAnyLocked())
            assertTrue(ExecutionMutex.isLocked(scopeA))
            assertTrue(ExecutionMutex.isLocked(scopeB))
            assertNull(ExecutionMutex.tryAcquireSession(owner = "login_a", scopeKey = scopeA))
            assertNull(ExecutionMutex.tryAcquireSession(owner = "batch_b", scopeKey = scopeB))
        } finally {
            ExecutionMutex.releaseSession(globalToken)
        }
        assertFalse(ExecutionMutex.isAnyLocked())

        // 方向 2：任意账号 scope 活跃期间，全局冷启动锁拒绝获取
        val tokenA = ExecutionMutex.tryAcquireSession(owner = "batch_a", scopeKey = scopeA)
        assertNotNull(tokenA)
        try {
            assertTrue(ExecutionMutex.isAnyLocked())
            assertTrue(ExecutionMutex.isLocked(ExecutionMutex.GLOBAL_SCOPE))
            assertNull(ExecutionMutex.tryAcquireGlobalSession("app_cold_start"))
        } finally {
            ExecutionMutex.releaseSession(tokenA)
        }

        // 所有 scope 释放后，全局冷启动锁可正常获取
        val globalAgain = ExecutionMutex.tryAcquireGlobalSession("app_cold_start")
        assertNotNull(globalAgain)
        ExecutionMutex.releaseSession(globalAgain)
        assertFalse(ExecutionMutex.isAnyLocked())
    }

    @Test
    fun duplicateReleaseAndExceptionInsideBlockNeverLeakOrUnlockAnotherHolder() = runTest {
        val scopeA = ExecutionMutex.scopeKeyForAccount("acct_a", multiProfileSupported = true)

        // 1) 代码块抛异常时 finally 必须释放锁
        var caught = false
        try {
            ExecutionMutex.withLock(owner = "boom", scopeKey = scopeA) {
                assertTrue(ExecutionMutex.isLocked(scopeA))
                throw IllegalStateException("simulated failure")
            }
        } catch (_: IllegalStateException) {
            caught = true
        }
        assertTrue(caught)
        assertFalse(ExecutionMutex.isLocked(scopeA))

        // 2) 重复释放旧 token 绝不能误解锁后继持有者
        val token1 = ExecutionMutex.tryAcquireSession(owner = "holder_1", scopeKey = scopeA)
        assertNotNull(token1)
        ExecutionMutex.releaseSession(token1)
        val token2 = ExecutionMutex.tryAcquireSession(owner = "holder_2", scopeKey = scopeA)
        assertNotNull(token2)
        try {
            ExecutionMutex.releaseSession(token1)
            ExecutionMutex.releaseSession(token1)
            assertTrue(ExecutionMutex.isLocked(scopeA))
            assertEquals("holder_2", ExecutionMutex.currentOwner(scopeA))
        } finally {
            ExecutionMutex.releaseSession(token2)
            ExecutionMutex.releaseSession(token2)
        }
        assertFalse(ExecutionMutex.isLocked(scopeA))

        // 3) 等待中取消协程不泄漏锁
        val holder = ExecutionMutex.tryAcquireSession(owner = "holder_cancel", scopeKey = scopeA)
        assertNotNull(holder)
        val cancelledJob = launch {
            ExecutionMutex.acquireSession(owner = "waiter", timeoutMs = 5_000L, scopeKey = scopeA)
        }
        cancelledJob.cancel()
        cancelledJob.join()
        ExecutionMutex.releaseSession(holder)
        assertFalse(ExecutionMutex.isLocked(scopeA))
    }
}
