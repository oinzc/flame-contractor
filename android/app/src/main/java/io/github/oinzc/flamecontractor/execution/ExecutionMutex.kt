package io.github.oinzc.flamecontractor.execution

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/**
 * 进程内 WebView 全局执行互斥锁：
 * 同一时间只允许一个 WebView 任务（扫码登录、全量扫描、页面结构体检、手动发送、定时续火）运行，
 * 防止并发抢占 CookieManager、WebStorage 与网络身份捕获缓存。
 */
object ExecutionMutex {

    class SessionToken internal constructor(
        val owner: String,
    )

    private val mutex = Mutex()

    @Volatile
    private var activeOwner: String? = null

    fun isLocked(): Boolean = mutex.isLocked

    fun currentOwner(): String? = activeOwner

    fun tryAcquireSession(owner: String): SessionToken? {
        val token = SessionToken(owner)
        if (!mutex.tryLock(token)) return null
        activeOwner = owner
        return token
    }

    suspend fun acquireSession(owner: String, timeoutMs: Long = 10_000L): SessionToken? {
        tryAcquireSession(owner)?.let { return it }
        if (timeoutMs <= 0L) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(150L)
            tryAcquireSession(owner)?.let { return it }
        }
        return null
    }

    fun releaseSession(token: SessionToken?) {
        if (token == null) return
        try {
            activeOwner = null
            mutex.unlock(token)
        } catch (_: IllegalStateException) {
        }
    }

    suspend fun <T> withLock(owner: String, block: suspend () -> T): T {
        val token = SessionToken(owner)
        mutex.lock(token)
        activeOwner = owner
        try {
            return block()
        } finally {
            activeOwner = null
            mutex.unlock(token)
        }
    }

    suspend fun <T> tryWithLock(
        owner: String,
        timeoutMs: Long,
        block: suspend () -> T,
    ): T? {
        val token = acquireSession(owner = owner, timeoutMs = timeoutMs) ?: return null
        try {
            return block()
        } finally {
            releaseSession(token)
        }
    }
}
