package io.github.oinzc.flamecontractor.ui.today

import io.github.oinzc.flamecontractor.execution.ExecutionLiveState
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import org.junit.Assert.assertEquals
import org.junit.Test

class TodayHeroStripToneTest {

    @Test
    fun `running state always resolves to EMBER`() {
        val state = ExecutionLiveState(
            running = true,
            lastMessage = "发送失败 2",
            lastErrorSummary = "上次存在错误",
        )
        assertEquals(FlameTone.EMBER, resolveHeroLiveStripTone(state))
    }

    @Test
    fun `completed summary with zero skipped and zero failed resolves to HEALTHY`() {
        val state = ExecutionLiveState(
            running = false,
            lastMessage = "今日续火完成：成功 15 / 跳过 0 / 失败 0",
        )
        assertEquals(FlameTone.HEALTHY, resolveHeroLiveStripTone(state))
    }

    @Test
    fun `completed summary with non-zero skipped and zero failed resolves to WARNING`() {
        val state = ExecutionLiveState(
            running = false,
            lastMessage = "今日续火完成：成功 14 / 跳过 1 / 失败 0",
        )
        assertEquals(FlameTone.WARNING, resolveHeroLiveStripTone(state))
    }

    @Test
    fun `completed summary with non-zero failed resolves to DANGER`() {
        val failOnly = ExecutionLiveState(
            running = false,
            lastMessage = "今日续火完成：成功 13 / 跳过 0 / 失败 2",
        )
        assertEquals(FlameTone.DANGER, resolveHeroLiveStripTone(failOnly))

        val bothSkipAndFail = ExecutionLiveState(
            running = false,
            lastMessage = "今日续火完成：成功 10 / 跳过 2 / 失败 1",
        )
        assertEquals(FlameTone.DANGER, resolveHeroLiveStripTone(bothSkipAndFail))
    }

    @Test
    fun `explicit error keywords and error summary resolve to DANGER`() {
        listOf(
            "发送失败，请检查网络",
            "页面结构异常导致中止",
            "存在结果未明任务",
            "等待发送超时",
            "执行被系统中断",
            "安全风控拦截",
        ).forEach { msg ->
            assertEquals(
                "Expected DANGER for: $msg",
                FlameTone.DANGER,
                resolveHeroLiveStripTone(ExecutionLiveState(running = false, lastMessage = msg)),
            )
        }

        assertEquals(
            FlameTone.DANGER,
            resolveHeroLiveStripTone(
                ExecutionLiveState(
                    running = false,
                    lastMessage = "今日续火完成：成功 15 / 跳过 0 / 失败 0",
                    lastErrorSummary = "1 位好友发送超时",
                ),
            ),
        )
    }

    @Test
    fun `skip keywords and skip summary resolve to WARNING`() {
        assertEquals(
            FlameTone.WARNING,
            resolveHeroLiveStripTone(
                ExecutionLiveState(
                    running = false,
                    lastMessage = "部分好友未绑定模板",
                ),
            ),
        )
        assertEquals(
            FlameTone.WARNING,
            resolveHeroLiveStripTone(
                ExecutionLiveState(
                    running = false,
                    lastMessage = "已跳过未配置对象",
                ),
            ),
        )
        assertEquals(
            FlameTone.WARNING,
            resolveHeroLiveStripTone(
                ExecutionLiveState(
                    running = false,
                    lastMessage = "今日续火完成：成功 12 / 跳过 0 / 失败 0",
                    lastSkipSummary = "2 位未绑定模板",
                ),
            ),
        )
    }
}
