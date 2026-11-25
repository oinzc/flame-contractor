package io.github.oinzc.flamecontractor.execution

import io.github.oinzc.flamecontractor.core.account.WebViewProfileRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 进程内 WebView 执行互斥锁（按 WebView Profile 隔离，同时保留全局排他能力）：
 * - 当设备 WebView 支持 `MULTI_PROFILE` 时，按 `WebViewProfileRegistry.profileNameForAccount(accountId)` 分锁，
 *   不同账号的扫码登录、续火发送与会话扫描互不阻塞；同一账号内严格互斥；
 * - 当设备不支持 `MULTI_PROFILE` 时，退化为统一 `GLOBAL_SCOPE` 单锁，防止并发抢占共享 CookieManager / WebStorage；
 * - `GLOBAL_SCOPE`（如冷启动崩溃恢复）与所有具体 scope 严格排他：持有全局锁期间阻塞全部 scope，
 *   任意 scope 活跃期间拒绝获取全局锁。
 */
object ExecutionMutex {

    const val GLOBAL_SCOPE: String = "__global__"

    class SessionToken internal constructor(
        val owner: String,
        val scopeKey: String = GLOBAL_SCOPE,
    ) {
        internal var released: Boolean = false
    }

    private val stateLock = Any()
    private var globalToken: SessionToken? = null
    private val scopedTokens = LinkedHashMap<String, SessionToken>()
    private val waiters = ArrayList<CompletableDeferred<Unit>>()

    fun scopeKeyForAccount(
        accountId: String,
        multiProfileSupported: Boolean = WebViewProfileRegistry.isMultiProfileSupported(),
    ): String {
        if (!multiProfileSupported) return GLOBAL_SCOPE
        return WebViewProfileRegistry.profileNameForAccount(accountId)
    }

    fun isAnyLocked(): Boolean = isLocked(null)

    /**
     * 查询锁占用状态：
     * - `scopeKey == null`：是否存在任意活跃锁（全局或任意账号 scope）；
     * - `scopeKey == GLOBAL_SCOPE`：全局锁是否不可立即获取（全局锁或任意 scope 已占用）；
     * - 具体 `scopeKey`：该 scope 是否被全局锁或同 scope 占用。
     */
    fun isLocked(scopeKey: String? = null): Boolean = synchronized(stateLock) {
        if (scopeKey == null) {
            globalToken != null || scopedTokens.isNotEmpty()
        } else {
            val normalized = normalizeScopeKey(scopeKey)
            if (normalized == GLOBAL_SCOPE) {
                globalToken != null || scopedTokens.isNotEmpty()
            } else {
                globalToken != null || scopedTokens.containsKey(normalized)
            }
        }
    }

    fun currentOwner(scopeKey: String? = null): String? = synchronized(stateLock) {
        if (scopeKey == null) {
            globalToken?.owner ?: scopedTokens.values.firstOrNull()?.owner
        } else {
            val normalized = normalizeScopeKey(scopeKey)
            if (normalized == GLOBAL_SCOPE) {
                globalToken?.owner
            } else {
                globalToken?.owner ?: scopedTokens[normalized]?.owner
            }
        }
    }

    fun tryAcquireGlobalSession(owner: String): SessionToken? =
        tryAcquireSession(owner = owner, scopeKey = GLOBAL_SCOPE)

    fun tryAcquireSession(
        owner: String,
        scopeKey: String = GLOBAL_SCOPE,
    ): SessionToken? {
        val normalizedScope = normalizeScopeKey(scopeKey)
        return synchronized(stateLock) {
            if (!canAcquireLocked(normalizedScope)) {
                return@synchronized null
            }
            allocateTokenLocked(owner = owner, normalizedScope = normalizedScope)
        }
    }

    suspend fun acquireSession(
        owner: String,
        timeoutMs: Long = 10_000L,
        scopeKey: String = GLOBAL_SCOPE,
    ): SessionToken? {
        val normalizedScope = normalizeScopeKey(scopeKey)
        tryAcquireSession(owner = owner, scopeKey = normalizedScope)?.let { return it }
        if (timeoutMs <= 0L) return null

        var acquired: SessionToken? = null
        try {
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    var waiter: CompletableDeferred<Unit>? = null
                    val token = synchronized(stateLock) {
                        if (canAcquireLocked(normalizedScope)) {
                            allocateTokenLocked(owner = owner, normalizedScope = normalizedScope)
                        } else {
                            waiter = CompletableDeferred<Unit>().also { waiters.add(it) }
                            null
                        }
                    }
                    if (token != null) {
                        acquired = token
                        return@withTimeoutOrNull
                    }
                    val signal = waiter ?: continue
                    try {
                        signal.await()
                    } finally {
                        synchronized(stateLock) {
                            waiters.remove(signal)
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            acquired?.let {
                releaseSession(it)
                acquired = null
            }
            throw ce
        }
        return acquired
    }

    fun releaseSession(token: SessionToken?) {
        if (token == null) return
        val toNotify: List<CompletableDeferred<Unit>> = synchronized(stateLock) {
            if (token.released) return
            var removed = false
            if (token.scopeKey == GLOBAL_SCOPE) {
                if (globalToken === token) {
                    globalToken = null
                    removed = true
                }
            } else {
                if (scopedTokens[token.scopeKey] === token) {
                    scopedTokens.remove(token.scopeKey)
                    removed = true
                }
            }
            token.released = true
            if (!removed || waiters.isEmpty()) {
                emptyList()
            } else {
                ArrayList(waiters).also { waiters.clear() }
            }
        }
        for (waiter in toNotify) {
            waiter.complete(Unit)
        }
    }

    suspend fun <T> withLock(
        owner: String,
        scopeKey: String = GLOBAL_SCOPE,
        block: suspend () -> T,
    ): T {
        val normalizedScope = normalizeScopeKey(scopeKey)
        val token = acquireIndefinitely(owner = owner, normalizedScope = normalizedScope)
        try {
            return block()
        } finally {
            releaseSession(token)
        }
    }

    suspend fun <T> tryWithLock(
        owner: String,
        timeoutMs: Long,
        scopeKey: String = GLOBAL_SCOPE,
        block: suspend () -> T,
    ): T? {
        val token = acquireSession(
            owner = owner,
            timeoutMs = timeoutMs,
            scopeKey = scopeKey,
        ) ?: return null
        try {
            return block()
        } finally {
            releaseSession(token)
        }
    }

    private suspend fun acquireIndefinitely(
        owner: String,
        normalizedScope: String,
    ): SessionToken {
        while (true) {
            var waiter: CompletableDeferred<Unit>? = null
            val token = synchronized(stateLock) {
                if (canAcquireLocked(normalizedScope)) {
                    allocateTokenLocked(owner = owner, normalizedScope = normalizedScope)
                } else {
                    waiter = CompletableDeferred<Unit>().also { waiters.add(it) }
                    null
                }
            }
            if (token != null) return token
            val signal = waiter ?: continue
            try {
                signal.await()
            } finally {
                synchronized(stateLock) {
                    waiters.remove(signal)
                }
            }
        }
    }

    private fun normalizeScopeKey(scopeKey: String): String =
        scopeKey.trim().ifEmpty { GLOBAL_SCOPE }

    private fun canAcquireLocked(normalizedScope: String): Boolean {
        if (globalToken != null) return false
        if (normalizedScope == GLOBAL_SCOPE) {
            return scopedTokens.isEmpty()
        }
        return !scopedTokens.containsKey(normalizedScope)
    }

    private fun allocateTokenLocked(
        owner: String,
        normalizedScope: String,
    ): SessionToken {
        val token = SessionToken(owner = owner, scopeKey = normalizedScope)
        if (normalizedScope == GLOBAL_SCOPE) {
            globalToken = token
        } else {
            scopedTokens[normalizedScope] = token
        }
        return token
    }
}
