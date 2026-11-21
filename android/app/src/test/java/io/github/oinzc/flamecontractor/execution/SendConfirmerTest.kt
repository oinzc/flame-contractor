package io.github.oinzc.flamecontractor.execution

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SendConfirmerTest {

    @Test
    fun returnsRetryableWhenFailureMarkerAppears() {
        val tracker = TextConfirmTracker()

        assertEquals(
            ConfirmOutcome.CONFIRMING_PENDING,
            tracker.evaluateTextPoll(
                failureMarkerHit = false,
                outgoingTextMatched = false,
                editorEmpty = true,
                timedOut = false,
            ),
        )
        assertEquals(
            ConfirmOutcome.RETRYABLE_FAILURE,
            tracker.evaluateTextPoll(
                failureMarkerHit = true,
                outgoingTextMatched = true,
                editorEmpty = true,
                timedOut = true,
            ),
        )

        val before = SendConfirmer.toMultiset(listOf("t1\u001fimgA\u001f"))
        val after = SendConfirmer.toMultiset(listOf("t1\u001fimgA\u001f", "t2\u001fimgB\u001f"))
        assertEquals(
            ConfirmOutcome.RETRYABLE_FAILURE,
            SendConfirmer.evaluateStickerPoll(
                failureMarkerHit = true,
                before = before,
                after = after,
                timedOut = false,
            ),
        )
    }

    @Test
    fun requires3ConsecutiveClearedPollsForSucceeded() {
        val tracker = TextConfirmTracker()

        assertEquals(
            ConfirmOutcome.CONFIRMING_PENDING,
            tracker.evaluateTextPoll(
                failureMarkerHit = false,
                outgoingTextMatched = false,
                editorEmpty = true,
                timedOut = false,
            ),
        )
        assertEquals(1, tracker.consecutiveEmptyPolls)

        assertEquals(
            ConfirmOutcome.CONFIRMING_PENDING,
            tracker.evaluateTextPoll(
                failureMarkerHit = false,
                outgoingTextMatched = false,
                editorEmpty = true,
                timedOut = false,
            ),
        )
        assertEquals(2, tracker.consecutiveEmptyPolls)

        // 中途编辑器恢复非空时必须清零计数，重新累计满 3 次才判成功
        assertEquals(
            ConfirmOutcome.CONFIRMING_PENDING,
            tracker.evaluateTextPoll(
                failureMarkerHit = false,
                outgoingTextMatched = false,
                editorEmpty = false,
                timedOut = false,
            ),
        )
        assertEquals(0, tracker.consecutiveEmptyPolls)

        assertEquals(
            ConfirmOutcome.CONFIRMING_PENDING,
            tracker.evaluateTextPoll(
                failureMarkerHit = false,
                outgoingTextMatched = false,
                editorEmpty = true,
                timedOut = false,
            ),
        )
        assertEquals(
            ConfirmOutcome.CONFIRMING_PENDING,
            tracker.evaluateTextPoll(
                failureMarkerHit = false,
                outgoingTextMatched = false,
                editorEmpty = true,
                timedOut = false,
            ),
        )
        assertEquals(
            ConfirmOutcome.SUCCEEDED,
            tracker.evaluateTextPoll(
                failureMarkerHit = false,
                outgoingTextMatched = false,
                editorEmpty = true,
                timedOut = false,
            ),
        )
        assertEquals(SendConfirmer.REQUIRED_CONSECUTIVE_EMPTY_POLLS, tracker.consecutiveEmptyPolls)
    }

    @Test
    fun recognizesOutgoingTextBubbleImmediatelyWithoutWaiting3EmptyPolls() {
        val tracker = TextConfirmTracker()

        val outcome = tracker.evaluateTextPoll(
            failureMarkerHit = false,
            outgoingTextMatched = true,
            editorEmpty = false,
            timedOut = false,
        )

        assertEquals(ConfirmOutcome.SUCCEEDED, outcome)
    }

    @Test
    fun returnsUnknownRequiresReviewOnTimeoutWithoutRetrying() {
        val tracker = TextConfirmTracker()

        assertEquals(
            ConfirmOutcome.CONFIRMING_PENDING,
            tracker.evaluateTextPoll(
                failureMarkerHit = false,
                outgoingTextMatched = false,
                editorEmpty = true,
                timedOut = false,
            ),
        )
        assertEquals(
            ConfirmOutcome.CONFIRMING_PENDING,
            tracker.evaluateTextPoll(
                failureMarkerHit = false,
                outgoingTextMatched = false,
                editorEmpty = true,
                timedOut = false,
            ),
        )
        // 仅两次清空即超时，且没有 outgoing 气泡证据时必须转人工复核
        assertEquals(
            ConfirmOutcome.UNKNOWN_REQUIRES_REVIEW,
            tracker.evaluateTextPoll(
                failureMarkerHit = false,
                outgoingTextMatched = false,
                editorEmpty = false,
                timedOut = true,
            ),
        )

        val before = SendConfirmer.toMultiset(listOf("10:00\u001fsticker:a.png\u001f"))
        val after = SendConfirmer.toMultiset(listOf("10:00\u001fsticker:a.png\u001f"))
        assertEquals(
            ConfirmOutcome.UNKNOWN_REQUIRES_REVIEW,
            SendConfirmer.evaluateStickerPoll(
                failureMarkerHit = false,
                before = before,
                after = after,
                timedOut = true,
            ),
        )
    }

    @Test
    fun detectsNewStickerMediaBubbleViaMultisetCounterDiffEvenWhenNodeCountConstant() {
        // 虚拟列表回收了最旧的节点 sig1，同时底部新插入同签名或新时间节点，总节点数仍保持 3
        val before = SendConfirmer.toMultiset(
            listOf(
                "09:50\u001fsticker:old.png\u001f",
                "10:00\u001fsticker:heart.png\u001f",
                "10:01\u001fsticker:wave.png\u001f",
            ),
        )
        val after = SendConfirmer.toMultiset(
            listOf(
                "10:00\u001fsticker:heart.png\u001f",
                "10:00\u001fsticker:heart.png\u001f",
                "10:01\u001fsticker:wave.png\u001f",
            ),
        )

        assertEquals(1, SendConfirmer.diffMediaMultiset(before, after))
        assertEquals(
            ConfirmOutcome.SUCCEEDED,
            SendConfirmer.evaluateStickerPoll(
                failureMarkerHit = false,
                before = before,
                after = after,
                timedOut = false,
            ),
        )

        // 仅发生旧节点回收但无新增气泡时，差集必须为 0
        val recycledOnly = SendConfirmer.toMultiset(
            listOf(
                "10:00\u001fsticker:heart.png\u001f",
                "10:01\u001fsticker:wave.png\u001f",
            ),
        )
        assertEquals(0, SendConfirmer.diffMediaMultiset(before, recycledOnly))
        assertEquals(
            ConfirmOutcome.CONFIRMING_PENDING,
            SendConfirmer.evaluateStickerPoll(
                failureMarkerHit = false,
                before = before,
                after = recycledOnly,
                timedOut = false,
            ),
        )
    }

    @Test
    fun clickEvalTimeoutNullNeverReturnsRetryableAndTurnsUnknownOnTimeout() = runTest {
        assertEquals(
            ClickDispatchOutcome.PROCEED_TO_CONFIRM,
            SendConfirmer.classifyIrreversibleClickResult(null),
        )
        assertEquals(
            ClickDispatchOutcome.PROCEED_TO_CONFIRM,
            SendConfirmer.classifyIrreversibleClickResult(mapOf("clicked" to true)),
        )
        assertEquals(
            ClickDispatchOutcome.ABORT_RETRYABLE,
            SendConfirmer.classifyIrreversibleClickResult(mapOf("clicked" to false)),
        )

        // 1) 文本发送：clickResult == null（求值超时）且未出现成功证据时必须落 UNKNOWN_REQUIRES_REVIEW，绝不重发
        var fakeNow = 1_000L
        var textPolls = 0
        val textTimeoutOutcome = SendConfirmer.confirmTextAfterClick(
            clickResult = null,
            timeoutMs = 1_500L,
            pollIntervalMs = 500L,
            clockMs = { fakeNow },
            delayFn = { ms -> fakeNow += ms },
        ) {
            textPolls += 1
            TextPollSample(failureMarkerHit = false, outgoingTextMatched = false, editorEmpty = false)
        }
        assertEquals(ConfirmOutcome.UNKNOWN_REQUIRES_REVIEW, textTimeoutOutcome)
        assertEquals(4, textPolls)

        // 2) 文本发送：clickResult == null 但轮询证实已发出气泡时直接判 SUCCEEDED
        fakeNow = 1_000L
        val textSucceededOutcome = SendConfirmer.confirmTextAfterClick(
            clickResult = null,
            timeoutMs = 1_500L,
            pollIntervalMs = 500L,
            clockMs = { fakeNow },
            delayFn = { ms -> fakeNow += ms },
        ) {
            TextPollSample(failureMarkerHit = false, outgoingTextMatched = true, editorEmpty = false)
        }
        assertEquals(ConfirmOutcome.SUCCEEDED, textSucceededOutcome)

        // 3) 文本发送：JS 明确回报 clicked == false 时不轮询直接判 RETRYABLE_FAILURE
        var abortedPolls = 0
        val abortedOutcome = SendConfirmer.confirmTextAfterClick(
            clickResult = mapOf("clicked" to false),
            clockMs = { 0L },
            delayFn = {},
        ) {
            abortedPolls += 1
            TextPollSample(failureMarkerHit = false, outgoingTextMatched = false, editorEmpty = false)
        }
        assertEquals(ConfirmOutcome.RETRYABLE_FAILURE, abortedOutcome)
        assertEquals(0, abortedPolls)

        // 4) 表情发送：clickResult == null 且媒体差集未增长时必须落 UNKNOWN_REQUIRES_REVIEW
        val beforeMedia = SendConfirmer.toMultiset(listOf("10:00\u001fsticker:old.png\u001f"))
        fakeNow = 2_000L
        val stickerTimeoutOutcome = SendConfirmer.confirmStickerAfterClick(
            clickResult = null,
            beforeMultiset = beforeMedia,
            postClickSettleMs = 800L,
            timeoutMs = 1_000L,
            pollIntervalMs = 500L,
            clockMs = { fakeNow },
            delayFn = { ms -> fakeNow += ms },
        ) {
            StickerPollSample(failureMarkerHit = false, afterMultiset = beforeMedia)
        }
        assertEquals(ConfirmOutcome.UNKNOWN_REQUIRES_REVIEW, stickerTimeoutOutcome)
    }
}
