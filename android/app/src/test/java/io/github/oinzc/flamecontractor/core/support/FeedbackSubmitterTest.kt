package io.github.oinzc.flamecontractor.core.support

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackSubmitterTest {

    @Test
    fun `validateInput enforces min and max bounds cleanly`() {
        assertTrue(FeedbackSubmitter.validateInput("", "太短", "") != null)
        assertNull(FeedbackSubmitter.validateInput("小组件问题", "点击刷新圈后在某机型上卡顿", "test@example.com"))
        assertTrue(FeedbackSubmitter.validateInput("a".repeat(101), "正常反馈内容不少于五个字", "") != null)
        assertTrue(FeedbackSubmitter.validateInput("", "a".repeat(2001), "") != null)
        assertTrue(FeedbackSubmitter.validateInput("", "正常反馈内容不少于五个字", "c".repeat(101)) != null)
    }

    @Test
    fun `submit posts JSON payload and parses issue receipt via MockWebServer`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """{"ok":true,"issueNumber":42,"issueUrl":"https://github.com/oinzc/flame-contractor/issues/42","message":"反馈已提交（#42），感谢你的帮助！"}""",
                ),
        )
        server.start()
        try {
            val submitter = FeedbackSubmitter(endpointProvider = { server.url("/v1/feedback").toString() })
            assertTrue(submitter.isConfigured)

            val result = submitter.submit(
                FeedbackSubmissionRequest(
                    kind = FeedbackKind.FEATURE,
                    title = "支持桌面端快捷键",
                    content = "希望增加一个全局快捷键快速呼出窗口\n方便核对状态",
                    contact = "demo@example.com",
                    version = "v0.5.0 (500)",
                    environment = "Android 15 (API 35)",
                    includeEnv = true,
                ),
            )

            assertTrue(result is FeedbackSubmitResult.Success)
            val ok = result as FeedbackSubmitResult.Success
            assertEquals(42, ok.issueNumber)
            assertEquals("https://github.com/oinzc/flame-contractor/issues/42", ok.issueUrl)
            assertEquals("反馈已提交（#42），感谢你的帮助！", ok.message)

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/v1/feedback", recorded.path)
            val sentBody = recorded.body.readUtf8()
            assertTrue(sentBody.contains("\"kind\":\"feature\""))
            assertTrue(sentBody.contains("\"client\":\"android\""))
            assertTrue(sentBody.contains("\"includeEnv\":true"))
            assertTrue(sentBody.contains("\"website\":\"\""))
            assertTrue(sentBody.contains("\\n方便核对状态"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `submit surfaces rate limit and unconfigured states gracefully`() = runTest {
        val unconfigured = FeedbackSubmitter(endpointProvider = { "" })
        assertFalse(unconfigured.isConfigured)
        val unconfResult = unconfigured.submit(
            FeedbackSubmissionRequest(
                kind = FeedbackKind.BUG,
                title = "",
                content = "测试未配置中转地址的提示文案",
                contact = "",
                version = "v0.5.0",
                environment = "",
                includeEnv = false,
            ),
        )
        assertTrue(unconfResult is FeedbackSubmitResult.Failure)
        assertFalse((unconfResult as FeedbackSubmitResult.Failure).retryable)

        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"ok":false,"error":"提交过于频繁，请稍后再试"}"""),
        )
        server.start()
        try {
            val submitter = FeedbackSubmitter(endpointProvider = { server.url("/v1/feedback").toString() })
            val limited = submitter.submit(
                FeedbackSubmissionRequest(
                    kind = FeedbackKind.BUG,
                    title = "",
                    content = "测试限流提示是否准确回传给用户",
                    contact = "",
                    version = "v0.5.0",
                    environment = "",
                    includeEnv = false,
                ),
            )
            assertTrue(limited is FeedbackSubmitResult.Failure)
            val fail = limited as FeedbackSubmitResult.Failure
            assertEquals("提交过于频繁，请稍后再试", fail.message)
            assertTrue(fail.retryable)
        } finally {
            server.shutdown()
        }
    }
}
