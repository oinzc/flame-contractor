package io.github.oinzc.flamecontractor.core.support

import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackSubmitterTest {

    private fun sampleAttachment(
        name: String = "screenshot_1.jpg",
        mimeType: String = "image/jpeg",
        rawBytes: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
        sizeBytes: Int = rawBytes.size,
        dataBase64: String = Base64.getEncoder().encodeToString(rawBytes),
    ): FeedbackImageAttachment = FeedbackImageAttachment(
        name = name,
        mimeType = mimeType,
        dataBase64 = dataBase64,
        sizeBytes = sizeBytes,
    )

    @Test
    fun `validateInput enforces min and max bounds cleanly`() {
        assertTrue(FeedbackSubmitter.validateInput("", "太短", "") != null)
        assertNull(FeedbackSubmitter.validateInput("小组件问题", "点击刷新圈后在某机型上卡顿", "test@example.com"))
        assertTrue(FeedbackSubmitter.validateInput("a".repeat(101), "正常反馈内容不少于五个字", "") != null)
        assertTrue(FeedbackSubmitter.validateInput("", "a".repeat(2001), "") != null)
        assertTrue(FeedbackSubmitter.validateInput("", "正常反馈内容不少于五个字", "c".repeat(101)) != null)
    }

    @Test
    fun `validateInput enforces image count mime size and base64 boundaries`() {
        val validThree = listOf(
            sampleAttachment(name = "1.jpg", mimeType = "image/jpeg"),
            sampleAttachment(name = "2.png", mimeType = "image/png"),
            sampleAttachment(name = "3.webp", mimeType = "image/webp"),
        )
        assertNull(FeedbackSubmitter.validateInput("带图反馈", "这是一条正常的带截图反馈内容", "", validThree))

        // 超过 3 张
        val fourImages = validThree + sampleAttachment(name = "4.jpg")
        assertNotNull(FeedbackSubmitter.validateInput("带图反馈", "这是一条正常的带截图反馈内容", "", fourImages))

        // 非法 MIME
        val gifImage = listOf(sampleAttachment(name = "a.gif", mimeType = "image/gif"))
        assertNotNull(FeedbackSubmitter.validateImages(gifImage))

        // 恰好 800 KB 边界通过，800 KB + 1 拒绝
        val exactMaxBytes = ByteArray(FeedbackSubmitter.MAX_IMAGE_BYTES) { 1 }
        assertNull(FeedbackSubmitter.validateImages(listOf(sampleAttachment(rawBytes = exactMaxBytes))))

        val overMaxBytes = ByteArray(FeedbackSubmitter.MAX_IMAGE_BYTES + 1) { 1 }
        assertNotNull(FeedbackSubmitter.validateImages(listOf(sampleAttachment(rawBytes = overMaxBytes))))
        // 即使伪造 sizeBytes 较小，解码超限仍拦截
        assertNotNull(
            FeedbackSubmitter.validateImages(
                listOf(sampleAttachment(rawBytes = overMaxBytes, sizeBytes = 100)),
            ),
        )

        // 空数据 / 带 data: 前缀 / 非法 Base64
        assertNotNull(FeedbackSubmitter.validateImages(listOf(sampleAttachment(dataBase64 = "", sizeBytes = 10))))
        assertNotNull(
            FeedbackSubmitter.validateImages(
                listOf(sampleAttachment(dataBase64 = "data:image/jpeg;base64,/9j/4A==", sizeBytes = 4)),
            ),
        )
        assertNotNull(
            FeedbackSubmitter.validateImages(
                listOf(sampleAttachment(dataBase64 = "not-valid-base64!!!", sizeBytes = 10)),
            ),
        )
        assertNotNull(FeedbackSubmitter.validateImages(listOf(sampleAttachment(sizeBytes = 0))))
    }

    @Test
    fun `encodeRequestJson omits images when empty and serializes images cleanly when present`() {
        val noImgReq = FeedbackSubmissionRequest(
            kind = FeedbackKind.BUG,
            title = "无图测试",
            content = "这是一条没有截图的反馈内容",
            contact = "",
            version = "v0.5.0",
            environment = "",
            includeEnv = false,
        )
        val noImgJson = FeedbackSubmitter.encodeRequestJson(noImgReq)
        assertFalse(noImgJson.contains("\"images\""))

        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val withImgReq = noImgReq.copy(
            images = listOf(
                FeedbackImageAttachment(
                    name = "",
                    mimeType = "IMAGE/PNG",
                    dataBase64 = b64,
                    sizeBytes = 4,
                ),
            ),
        )
        val withImgJson = FeedbackSubmitter.encodeRequestJson(withImgReq)
        assertTrue(withImgJson.contains("\"images\":[{"))
        assertTrue(withImgJson.contains("\"name\":\"screenshot_1.png\""))
        assertTrue(withImgJson.contains("\"mimeType\":\"image/png\""))
        assertTrue(withImgJson.contains("\"dataBase64\":\"$b64\""))
    }

    @Test
    fun `buildClipboardFallbackText appends screenshot hint only when images attached`() {
        val baseReq = FeedbackSubmissionRequest(
            kind = FeedbackKind.QUESTION,
            title = "如何设置定时",
            content = "请问如何给单个好友设置固定时间？",
            contact = "user@example.com",
            version = "v0.5.0",
            environment = "Android 15",
            includeEnv = true,
        )
        val withoutImages = FeedbackSubmitter.buildClipboardFallbackText(baseReq)
        assertFalse(withoutImages.contains("本地已选择"))

        val withTwoImages = FeedbackSubmitter.buildClipboardFallbackText(
            baseReq.copy(images = listOf(sampleAttachment(), sampleAttachment(name = "2.jpg"))),
        )
        assertTrue(withTwoImages.contains("（本地已选择 2 张截图，复制文本不含图片，请手动补充截图）"))
    }

    @Test
    fun `timeout is raised to 30 seconds for multi-image uploads`() {
        assertEquals(30L, FeedbackSubmitter.TIMEOUT_SEC)
    }

    @Test
    fun `submit posts JSON payload with images and parses issue receipt and imageCount via MockWebServer`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """{"ok":true,"issueNumber":42,"issueUrl":"https://github.com/oinzc/flame-contractor/issues/42","imageCount":2,"message":"反馈已提交（#42，含 2 张截图），感谢你的帮助！"}""",
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
                    images = listOf(
                        sampleAttachment(name = "shot_1.jpg"),
                        sampleAttachment(name = "shot_2.png", mimeType = "image/png"),
                    ),
                ),
            )

            assertTrue(result is FeedbackSubmitResult.Success)
            val ok = result as FeedbackSubmitResult.Success
            assertEquals(42, ok.issueNumber)
            assertEquals("https://github.com/oinzc/flame-contractor/issues/42", ok.issueUrl)
            assertEquals(2, ok.imageCount)
            assertEquals("反馈已提交（#42，含 2 张截图），感谢你的帮助！", ok.message)

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/v1/feedback", recorded.path)
            val sentBody = recorded.body.readUtf8()
            assertTrue(sentBody.contains("\"kind\":\"feature\""))
            assertTrue(sentBody.contains("\"client\":\"android\""))
            assertTrue(sentBody.contains("\"includeEnv\":true"))
            assertTrue(sentBody.contains("\"website\":\"\""))
            assertTrue(sentBody.contains("\\n方便核对状态"))
            assertTrue(sentBody.contains("\"images\":["))
            assertTrue(sentBody.contains("\"name\":\"shot_1.jpg\""))
            assertTrue(sentBody.contains("\"name\":\"shot_2.png\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `parseRelayResponse defaults imageCount to zero when omitted and formats fallback message`() {
        val noImageCount = FeedbackSubmitter.parseRelayResponse(
            statusCode = 201,
            isSuccessful = true,
            bodyStr = """{"ok":true,"issueNumber":15,"issueUrl":"https://github.com/oinzc/flame-contractor/issues/15"}""",
        ) as FeedbackSubmitResult.Success
        assertEquals(0, noImageCount.imageCount)
        assertEquals(15, noImageCount.issueNumber)
        assertTrue(noImageCount.message.contains("#15"))

        val withImageCountNoMessage = FeedbackSubmitter.parseRelayResponse(
            statusCode = 201,
            isSuccessful = true,
            bodyStr = """{"ok":true,"issueNumber":18,"imageCount":3}""",
        ) as FeedbackSubmitResult.Success
        assertEquals(3, withImageCountNoMessage.imageCount)
        assertTrue(withImageCountNoMessage.message.contains("3 张截图"))
    }

    @Test
    fun `submit surfaces rate limit storage unbound and unconfigured states gracefully`() = runTest {
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
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"ok":false,"error":"当前反馈中转服务尚未绑定图片存储"}"""),
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

            val noStorage = submitter.submit(
                FeedbackSubmissionRequest(
                    kind = FeedbackKind.BUG,
                    title = "",
                    content = "测试未绑定图片存储时的 503 提示",
                    contact = "",
                    version = "v0.5.0",
                    environment = "",
                    includeEnv = false,
                    images = listOf(sampleAttachment()),
                ),
            )
            assertTrue(noStorage is FeedbackSubmitResult.Failure)
            val noStorageFail = noStorage as FeedbackSubmitResult.Failure
            assertEquals("当前反馈中转服务尚未绑定图片存储", noStorageFail.message)
            assertTrue(noStorageFail.retryable)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `diagnosticLog respects 16000 char bound serializes conditionally parses response and updates clipboard fallback`() = runTest {
        assertEquals(16_000, FeedbackSubmitter.MAX_DIAGNOSTIC_LOG_LEN)

        val exactLog = "a".repeat(FeedbackSubmitter.MAX_DIAGNOSTIC_LOG_LEN)
        val overLog = "a".repeat(FeedbackSubmitter.MAX_DIAGNOSTIC_LOG_LEN + 1)
        assertNull(FeedbackSubmitter.validateInput("标题", "这是一条附带诊断日志的反馈内容", "", diagnosticLog = exactLog))
        assertNotNull(FeedbackSubmitter.validateInput("标题", "这是一条附带诊断日志的反馈内容", "", diagnosticLog = overLog))

        val baseReq = FeedbackSubmissionRequest(
            kind = FeedbackKind.BUG,
            title = "会话定位失败",
            content = "点击续火后提示未找到目标会话，附上日志",
            contact = "",
            version = "v0.6.1",
            environment = "Android 15",
            includeEnv = true,
        )
        assertFalse(FeedbackSubmitter.encodeRequestJson(baseReq).contains("\"diagnosticLog\""))
        assertFalse(FeedbackSubmitter.buildClipboardFallbackText(baseReq).contains("--- 脱敏诊断日志 ---"))

        val withLogReq = baseReq.copy(
            diagnosticLog = "2026-11-25T08:15:02Z [WARN] [SEND] target_lookup_miss visibleRows=12",
        )
        val encodedWithLog = FeedbackSubmitter.encodeRequestJson(withLogReq)
        assertTrue(encodedWithLog.contains("\"diagnosticLog\":\"2026-11-25T08:15:02Z [WARN] [SEND] target_lookup_miss visibleRows=12\""))

        val clipboardWithLog = FeedbackSubmitter.buildClipboardFallbackText(withLogReq)
        assertTrue(clipboardWithLog.contains("--- 脱敏诊断日志 ---"))
        assertTrue(clipboardWithLog.contains("target_lookup_miss visibleRows=12"))

        val draft = FeedbackPreFillDraft(title = "异常反馈", content = "描述内容")
        assertEquals(FeedbackKind.BUG, draft.kind)
        assertTrue(draft.attachDiagnosticLog)

        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("""{"ok":true,"issueNumber":99,"issueUrl":"https://github.com/oinzc/flame-contractor/issues/99","imageCount":0,"hasDiagnosticLog":true,"message":"反馈已提交（#99）"}"""),
        )
        server.start()
        try {
            val submitter = FeedbackSubmitter(endpointProvider = { server.url("/v1/feedback").toString() })
            val result = submitter.submit(withLogReq) as FeedbackSubmitResult.Success
            assertEquals(99, result.issueNumber)
            assertTrue(result.hasDiagnosticLog)

            val recordedBody = server.takeRequest().body.readUtf8()
            assertTrue(recordedBody.contains("\"diagnosticLog\""))
        } finally {
            server.shutdown()
        }

        val parsedOmitted = FeedbackSubmitter.parseRelayResponse(
            statusCode = 201,
            isSuccessful = true,
            bodyStr = """{"ok":true,"issueNumber":100}""",
        ) as FeedbackSubmitResult.Success
        assertFalse(parsedOmitted.hasDiagnosticLog)
    }
}
