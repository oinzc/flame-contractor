package io.github.oinzc.flamecontractor.execution

import kotlin.math.max
import kotlinx.coroutines.delay

enum class ConfirmOutcome {
    CONFIRMING_PENDING,
    SUCCEEDED,
    RETRYABLE_FAILURE,
    UNKNOWN_REQUIRES_REVIEW,
}

enum class ClickDispatchOutcome {
    PROCEED_TO_CONFIRM,
    ABORT_RETRYABLE,
}

data class TextPollSample(
    val failureMarkerHit: Boolean,
    val outgoingTextMatched: Boolean,
    val editorEmpty: Boolean,
)

data class StickerPollSample(
    val failureMarkerHit: Boolean,
    val afterMultiset: Map<String, Int>,
)

/**
 * 文本发送终态确认纯状态机（对齐桌面版 `send_confirmer.py` 与设计规范 §6.4）：
 * - 命中 `SEND_FAILURE_MARKERS` 立即返回 `RETRYABLE_FAILURE`；
 * - 优先级 A：检测到含规范化目标文本的新增 outgoing 气泡 → 立即 `SUCCEEDED`；
 * - 优先级 B：连续 3 次轮询编辑器清空且无失败图标 → `SUCCEEDED`（中途非空清零计数）；
 * - 超时仍未证实 → `UNKNOWN_REQUIRES_REVIEW`（绝不自动重试）。
 */
class TextConfirmTracker(
    private val requiredConsecutiveEmptyPolls: Int = SendConfirmer.REQUIRED_CONSECUTIVE_EMPTY_POLLS,
) {
    var consecutiveEmptyPolls: Int = 0
        private set

    fun reset() {
        consecutiveEmptyPolls = 0
    }

    fun evaluateTextPoll(
        failureMarkerHit: Boolean,
        outgoingTextMatched: Boolean,
        editorEmpty: Boolean,
        timedOut: Boolean,
    ): ConfirmOutcome {
        if (failureMarkerHit) {
            consecutiveEmptyPolls = 0
            return ConfirmOutcome.RETRYABLE_FAILURE
        }
        if (outgoingTextMatched) {
            return ConfirmOutcome.SUCCEEDED
        }
        if (editorEmpty) {
            consecutiveEmptyPolls += 1
            if (consecutiveEmptyPolls >= requiredConsecutiveEmptyPolls) {
                return ConfirmOutcome.SUCCEEDED
            }
        } else {
            consecutiveEmptyPolls = 0
        }
        return if (timedOut) {
            ConfirmOutcome.UNKNOWN_REQUIRES_REVIEW
        } else {
            ConfirmOutcome.CONFIRMING_PENDING
        }
    }
}

object SendConfirmer {

    const val REQUIRED_CONSECUTIVE_EMPTY_POLLS = 3
    const val DEFAULT_CONFIRM_TIMEOUT_MS = 15_000L
    const val DEFAULT_POLL_INTERVAL_MS = 500L

    /**
     * 不可逆点击求值结果判定：
     * - 仅当 JS 明确回报 `clicked == false`（尚未触发点击）时才允许回退为可重试；
     * - 若求值超时或异常返回 `null`（点击结果未知），绝不退回可重试，必须进入确认轮询并在未证实时落 `UNKNOWN_REQUIRES_REVIEW`。
     */
    fun classifyIrreversibleClickResult(clickResult: Map<String, Any?>?): ClickDispatchOutcome {
        if (clickResult != null && !clickResult.boolOrDefault("clicked", false)) {
            return ClickDispatchOutcome.ABORT_RETRYABLE
        }
        return ClickDispatchOutcome.PROCEED_TO_CONFIRM
    }

    suspend fun confirmTextAfterClick(
        clickResult: Map<String, Any?>?,
        timeoutMs: Long = DEFAULT_CONFIRM_TIMEOUT_MS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        clockMs: () -> Long = { System.currentTimeMillis() },
        delayFn: (suspend (Long) -> Unit)? = null,
        pollSample: suspend () -> TextPollSample,
    ): ConfirmOutcome {
        if (classifyIrreversibleClickResult(clickResult) == ClickDispatchOutcome.ABORT_RETRYABLE) {
            return ConfirmOutcome.RETRYABLE_FAILURE
        }
        val tracker = TextConfirmTracker()
        val deadline = clockMs() + timeoutMs
        while (true) {
            val timedOut = clockMs() >= deadline
            val sample = pollSample()
            val outcome = tracker.evaluateTextPoll(
                failureMarkerHit = sample.failureMarkerHit,
                outgoingTextMatched = sample.outgoingTextMatched,
                editorEmpty = sample.editorEmpty,
                timedOut = timedOut,
            )
            if (outcome != ConfirmOutcome.CONFIRMING_PENDING) {
                return outcome
            }
            if (delayFn != null) delayFn(pollIntervalMs) else delay(pollIntervalMs)
        }
    }

    suspend fun confirmStickerAfterClick(
        clickResult: Map<String, Any?>?,
        beforeMultiset: Map<String, Int>,
        postClickSettleMs: Long = 0L,
        timeoutMs: Long = DEFAULT_CONFIRM_TIMEOUT_MS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        clockMs: () -> Long = { System.currentTimeMillis() },
        delayFn: (suspend (Long) -> Unit)? = null,
        pollSample: suspend () -> StickerPollSample,
    ): ConfirmOutcome {
        if (classifyIrreversibleClickResult(clickResult) == ClickDispatchOutcome.ABORT_RETRYABLE) {
            return ConfirmOutcome.RETRYABLE_FAILURE
        }
        if (postClickSettleMs > 0L) {
            if (delayFn != null) delayFn(postClickSettleMs) else delay(postClickSettleMs)
        }
        val deadline = clockMs() + timeoutMs
        while (true) {
            val timedOut = clockMs() >= deadline
            val sample = pollSample()
            val outcome = evaluateStickerPoll(
                failureMarkerHit = sample.failureMarkerHit,
                before = beforeMultiset,
                after = sample.afterMultiset,
                timedOut = timedOut,
            )
            if (outcome != ConfirmOutcome.CONFIRMING_PENDING) {
                return outcome
            }
            if (delayFn != null) delayFn(pollIntervalMs) else delay(pollIntervalMs)
        }
    }

    fun toMultiset(signatures: Iterable<String>): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (sig in signatures) {
            counts[sig] = (counts[sig] ?: 0) + 1
        }
        return counts
    }

    /**
     * 计算发送前后 `isFromMe` 非头像媒体签名多重集的新增条数差集，
     * 即使虚拟列表回收旧节点导致图片总数不变也能精准识别新增气泡。
     */
    fun diffMediaMultiset(
        before: Map<String, Int>,
        after: Map<String, Int>,
    ): Int {
        var added = 0
        for ((signature, afterCount) in after) {
            val beforeCount = before[signature] ?: 0
            added += max(0, afterCount - beforeCount)
        }
        return added
    }

    fun evaluateStickerPoll(
        failureMarkerHit: Boolean,
        before: Map<String, Int>,
        after: Map<String, Int>,
        timedOut: Boolean,
    ): ConfirmOutcome {
        if (failureMarkerHit) {
            return ConfirmOutcome.RETRYABLE_FAILURE
        }
        if (diffMediaMultiset(before, after) > 0) {
            return ConfirmOutcome.SUCCEEDED
        }
        return if (timedOut) {
            ConfirmOutcome.UNKNOWN_REQUIRES_REVIEW
        } else {
            ConfirmOutcome.CONFIRMING_PENDING
        }
    }
}
