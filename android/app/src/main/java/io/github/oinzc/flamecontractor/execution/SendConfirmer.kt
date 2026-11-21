package io.github.oinzc.flamecontractor.execution

import kotlin.math.max

enum class ConfirmOutcome {
    CONFIRMING_PENDING,
    SUCCEEDED,
    RETRYABLE_FAILURE,
    UNKNOWN_REQUIRES_REVIEW,
}

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
