package io.github.oinzc.flamecontractor.execution

import io.github.oinzc.flamecontractor.core.account.WebViewProfileRegistry
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundle
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import java.io.File
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageSendExecutorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val bundle = SelectorBundle.defaults()
    private val sampleTarget = ContractTargetEntity(
        id = "target-img-1",
        accountId = DEFAULT_ACCOUNT_ID,
        nicknameSnapshot = "测试好友",
        platformUserId = "sec_uid_test_1",
        conversationType = "friend",
        templateId = "tpl-img-1",
    )

    @Test
    fun missingOrEmptyImageFileImmediatelyReturnsFailedTerminalWithoutCallbacks() = runTest {
        val nonexistent = File(tempFolder.root, "missing.png")
        val emptyFile = tempFolder.newFile("empty.png")

        for (candidate in listOf<File?>(null, nonexistent, emptyFile)) {
            val transport = ScriptedImageTransport(imagePayload = null)
            var sendingCalls = 0
            var confirmingCalls = 0

            val result = DouyinWebExecutor.executeImageSend(
                bundle = bundle,
                target = sampleTarget,
                imageFile = candidate,
                hasContentUri = true,
                transport = transport,
                onStageSending = { sendingCalls += 1 },
                onStageConfirming = { confirmingCalls += 1 },
            )

            assertEquals(JobStatus.FAILED_TERMINAL, result.jobStatus)
            assertEquals(FailureClass.OTHER, result.failureClass)
            assertEquals("image_upload", result.inputMethodUsed)
            assertEquals(0, sendingCalls)
            assertEquals(0, confirmingCalls)
            assertEquals(0, transport.openCalls)
            assertEquals(0, transport.motionTapCalls)
            assertTrue(transport.evaluatedKinds.isEmpty())
        }
    }

    @Test
    fun missingUploadEntryReturnsDomChangedTerminal() = runTest {
        val imgFile = createValidPngFile()
        val transport = ScriptedImageTransport(
            imagePayload = DouyinWebExecutor.encodeImageFileToPayload(imgFile),
            probeResult = mapOf(
                "triggerFound" to false,
                "triggerCount" to 0,
                "fileInputCount" to 0,
                "hasUploadEntry" to false,
            ),
        )
        var sendingCalls = 0
        var confirmingCalls = 0

        val result = DouyinWebExecutor.executeImageSend(
            bundle = bundle,
            target = sampleTarget,
            imageFile = imgFile,
            hasContentUri = true,
            transport = transport,
            onStageSending = { sendingCalls += 1 },
            onStageConfirming = { confirmingCalls += 1 },
        )

        assertEquals(JobStatus.FAILED_TERMINAL, result.jobStatus)
        assertEquals(FailureClass.DOM_CHANGED, result.failureClass)
        assertTrue(result.coreSkeletonBroken)
        assertEquals(1, sendingCalls)
        assertEquals(0, confirmingCalls)
        assertEquals(0, transport.motionTapCalls)
    }

    @Test
    fun solutionAMotionEventAndFileChooserSucceedsWithSendOrderGuard() = runTest {
        val imgFile = createValidPngFile()
        val stages = mutableListOf<String>()
        var mediaPollSeq = 0
        val transport = ScriptedImageTransport(
            imagePayload = DouyinWebExecutor.encodeImageFileToPayload(imgFile),
            chooserSucceedsOnMotionTap = true,
            onInspectModal = {
                mapOf(
                    "modalVisible" to true,
                    "hasImgPreview" to true,
                    "sendButtonFound" to true,
                    "sendButtonEnabled" to true,
                    "sendButtonText" to "发送（1）",
                )
            },
            onClickModalSend = {
                stages += "click_send"
                mapOf("clicked" to true, "method" to "text_regex", "buttonText" to "发送（1）")
            },
            onSampleMedia = {
                mediaPollSeq += 1
                if (mediaPollSeq == 1) {
                    stages += "sample_before"
                    mapOf("failureMarkerHit" to false, "signatures" to listOf("10:00\u001fimg:old\u001f"))
                } else {
                    mapOf(
                        "failureMarkerHit" to false,
                        "signatures" to listOf("10:00\u001fimg:old\u001f", "10:01\u001fimg:new\u001f"),
                    )
                }
            },
        )

        val result = DouyinWebExecutor.executeImageSend(
            bundle = bundle,
            target = sampleTarget,
            imageFile = imgFile,
            hasContentUri = true,
            transport = transport,
            onStageSending = { stages += "stage_sending" },
            onStageConfirming = { stages += "stage_confirming" },
        )

        assertEquals(JobStatus.SUCCEEDED, result.jobStatus)
        assertEquals("image_chooser", result.inputMethodUsed)
        assertEquals("sec_uid_test_1", result.observedPlatformUserId)
        assertEquals(1, transport.motionTapCalls)
        assertFalse(transport.evaluatedKinds.contains("inject_datatransfer"))
        assertEquals(listOf("stage_sending", "sample_before", "stage_confirming", "click_send"), stages)
    }

    @Test
    fun solutionAFallbackToSolutionBDataTransferWhenChooserNotTriggeredOrModalMissing() = runTest {
        val imgFile = createValidPngFile()

        // 子场景 1：方案 A 未触发 onShowFileChooser -> 关闭残余弹窗并降级方案 B 成功
        val transportNoChooser = ScriptedImageTransport(
            imagePayload = DouyinWebExecutor.encodeImageFileToPayload(imgFile),
            chooserSucceedsOnMotionTap = false,
            onInjectDataTransfer = { mapOf("ok" to true, "injected" to true) },
            onInspectModal = {
                mapOf(
                    "modalVisible" to true,
                    "hasImgPreview" to true,
                    "sendButtonFound" to true,
                    "sendButtonEnabled" to true,
                    "sendButtonText" to "发送（1）",
                )
            },
            onClickModalSend = { mapOf("clicked" to true) },
            onSampleMedia = { pollIdx ->
                if (pollIdx == 1) {
                    mapOf("failureMarkerHit" to false, "signatures" to emptyList<String>())
                } else {
                    mapOf("failureMarkerHit" to false, "signatures" to listOf("10:02\u001fimg:dt\u001f"))
                }
            },
        )
        val res1 = DouyinWebExecutor.executeImageSend(
            bundle = bundle,
            target = sampleTarget,
            imageFile = imgFile,
            hasContentUri = true,
            transport = transportNoChooser,
        )
        assertEquals(JobStatus.SUCCEEDED, res1.jobStatus)
        assertEquals("image_datatransfer", res1.inputMethodUsed)
        assertTrue(transportNoChooser.evaluatedKinds.contains("click_upload_trigger"))
        assertTrue(transportNoChooser.evaluatedKinds.contains("click_file_input"))
        assertTrue(transportNoChooser.evaluatedKinds.contains("dismiss_modal"))
        assertTrue(transportNoChooser.evaluatedKinds.contains("inject_datatransfer"))

        // 子场景 2：方案 A 触发了 onShowFileChooser 但 6s 内未出现弹窗 -> 降级方案 B 后出现弹窗并发送成功
        var dataTransferInjected = false
        val transportChooserModalTimeout = ScriptedImageTransport(
            imagePayload = DouyinWebExecutor.encodeImageFileToPayload(imgFile),
            chooserSucceedsOnMotionTap = true,
            onInjectDataTransfer = {
                dataTransferInjected = true
                mapOf("ok" to true, "injected" to true)
            },
            onInspectModal = {
                if (!dataTransferInjected) {
                    mapOf(
                        "modalVisible" to false,
                        "hasImgPreview" to false,
                        "sendButtonFound" to false,
                        "sendButtonEnabled" to false,
                    )
                } else {
                    mapOf(
                        "modalVisible" to true,
                        "hasImgPreview" to true,
                        "sendButtonFound" to true,
                        "sendButtonEnabled" to true,
                    )
                }
            },
            onClickModalSend = { mapOf("clicked" to true) },
            onSampleMedia = { pollIdx ->
                if (pollIdx == 1) {
                    mapOf("failureMarkerHit" to false, "signatures" to emptyList<String>())
                } else {
                    mapOf("failureMarkerHit" to false, "signatures" to listOf("10:03\u001fimg:dt2\u001f"))
                }
            },
        )
        val res2 = DouyinWebExecutor.executeImageSend(
            bundle = bundle,
            target = sampleTarget,
            imageFile = imgFile,
            hasContentUri = true,
            transport = transportChooserModalTimeout,
        )
        assertEquals(JobStatus.SUCCEEDED, res2.jobStatus)
        assertEquals("image_datatransfer", res2.inputMethodUsed)
        assertTrue(dataTransferInjected)
    }

    @Test
    fun bothUploadPathsFailModalReturnsRetryableWithoutConfirmingOrClickingSend() = runTest {
        val imgFile = createValidPngFile()
        var confirmingCalls = 0
        val transport = ScriptedImageTransport(
            imagePayload = DouyinWebExecutor.encodeImageFileToPayload(imgFile),
            chooserSucceedsOnMotionTap = false,
            onInjectDataTransfer = { mapOf("ok" to true, "injected" to true) },
            onInspectModal = {
                mapOf(
                    "modalVisible" to true,
                    "hasImgPreview" to false,
                    "sendButtonFound" to false,
                    "sendButtonEnabled" to false,
                )
            },
        )

        val result = DouyinWebExecutor.executeImageSend(
            bundle = bundle,
            target = sampleTarget,
            imageFile = imgFile,
            hasContentUri = true,
            transport = transport,
            onStageConfirming = { confirmingCalls += 1 },
        )

        assertEquals(JobStatus.FAILED_RETRYABLE, result.jobStatus)
        assertEquals(FailureClass.OTHER, result.failureClass)
        assertEquals(0, confirmingCalls)
        assertFalse(transport.evaluatedKinds.contains("click_modal_send"))
        assertTrue(transport.evaluatedKinds.contains("dismiss_modal"))
    }

    @Test
    fun abortedModalSendClickReturnsRetryableAndDismissesModal() = runTest {
        val imgFile = createValidPngFile()
        var confirmingCalls = 0
        var sampleMediaCalls = 0
        val transport = ScriptedImageTransport(
            imagePayload = DouyinWebExecutor.encodeImageFileToPayload(imgFile),
            chooserSucceedsOnMotionTap = true,
            onInspectModal = {
                mapOf(
                    "modalVisible" to true,
                    "hasImgPreview" to true,
                    "sendButtonFound" to true,
                    "sendButtonEnabled" to true,
                )
            },
            onClickModalSend = { mapOf("clicked" to false, "reason" to "send_button_disabled") },
            onSampleMedia = {
                sampleMediaCalls += 1
                mapOf("failureMarkerHit" to false, "signatures" to listOf("10:00\u001fimg:old\u001f"))
            },
        )

        val result = DouyinWebExecutor.executeImageSend(
            bundle = bundle,
            target = sampleTarget,
            imageFile = imgFile,
            hasContentUri = true,
            transport = transport,
            onStageConfirming = { confirmingCalls += 1 },
        )

        assertEquals(JobStatus.FAILED_RETRYABLE, result.jobStatus)
        assertEquals(1, confirmingCalls)
        assertEquals(1, sampleMediaCalls)
        assertTrue(transport.evaluatedKinds.contains("dismiss_modal"))
    }

    @Test
    fun confirmTimeoutAfterClickOrNullEvalTransitionsToUnknownRequiresReviewAndNeverResends() = runTest {
        val imgFile = createValidPngFile()

        for (clickResult in listOf(mapOf("clicked" to true), null)) {
            var sendClickCount = 0
            val beforeSigs = listOf("09:59\u001fimg:existing\u001f")
            val transport = ScriptedImageTransport(
                imagePayload = DouyinWebExecutor.encodeImageFileToPayload(imgFile),
                chooserSucceedsOnMotionTap = true,
                onInspectModal = {
                    mapOf(
                        "modalVisible" to true,
                        "hasImgPreview" to true,
                        "sendButtonFound" to true,
                        "sendButtonEnabled" to true,
                    )
                },
                onClickModalSend = {
                    sendClickCount += 1
                    clickResult
                },
                onSampleMedia = {
                    mapOf("failureMarkerHit" to false, "signatures" to beforeSigs)
                },
            )

            val result = DouyinWebExecutor.executeImageSend(
                bundle = bundle,
                target = sampleTarget,
                imageFile = imgFile,
                hasContentUri = true,
                transport = transport,
            )

            assertEquals(JobStatus.UNKNOWN_REQUIRES_REVIEW, result.jobStatus)
            assertEquals(FailureClass.UNKNOWN_OUTCOME, result.failureClass)
            assertEquals("image_chooser", result.inputMethodUsed)
            assertEquals(1, sendClickCount)
        }
    }

    @Test
    fun failureMarkerDuringConfirmPollingReturnsRetryableFailure() = runTest {
        val imgFile = createValidPngFile()
        val transport = ScriptedImageTransport(
            imagePayload = DouyinWebExecutor.encodeImageFileToPayload(imgFile),
            chooserSucceedsOnMotionTap = true,
            onInspectModal = {
                mapOf(
                    "modalVisible" to true,
                    "hasImgPreview" to true,
                    "sendButtonFound" to true,
                    "sendButtonEnabled" to true,
                )
            },
            onClickModalSend = { mapOf("clicked" to true) },
            onSampleMedia = { pollIdx ->
                if (pollIdx == 1) {
                    mapOf("failureMarkerHit" to false, "signatures" to emptyList<String>())
                } else {
                    mapOf("failureMarkerHit" to true, "signatures" to emptyList<String>())
                }
            },
        )

        val result = DouyinWebExecutor.executeImageSend(
            bundle = bundle,
            target = sampleTarget,
            imageFile = imgFile,
            hasContentUri = true,
            transport = transport,
        )

        assertEquals(JobStatus.FAILED_RETRYABLE, result.jobStatus)
        assertEquals(FailureClass.OTHER, result.failureClass)
    }

    @Test
    fun pendingFileChooserSlotDeliversOnceAndAutoCancelsAfterDisarm() = runTest {
        val slot = DouyinWebExecutor.PendingFileChooserSlot<String>()

        // 1) 武装后首次回调收到 URI，二次触发立即返回 null 防挂死
        val req1 = slot.arm("content://app/template_images/a.png")
        var delivered1: Array<String>? = emptyArray()
        assertTrue(slot.handleShowFileChooser { delivered1 = it })
        assertNotNull(delivered1)
        assertArrayEquals(arrayOf("content://app/template_images/a.png"), delivered1)
        assertTrue(req1.triggered.await())

        var deliveredExtra: Array<String>? = emptyArray()
        assertTrue(slot.handleShowFileChooser { deliveredExtra = it })
        assertNull(deliveredExtra)

        // 2) 超时 disarm 后迟到回调必须收到 null，且 triggered 为 false
        val req2 = slot.arm("content://app/template_images/b.png")
        slot.disarm(req2)
        var deliveredLate: Array<String>? = emptyArray()
        assertTrue(slot.handleShowFileChooser { deliveredLate = it })
        assertNull(deliveredLate)
        assertFalse(req2.triggered.await())
    }

    @Test
    fun profileMappingAndWebStorageDeletionGuardIsolateAccounts() {
        val defaultProfile = WebViewProfileRegistry.profileNameForAccount(DEFAULT_ACCOUNT_ID)
        val alphaProfile = WebViewProfileRegistry.profileNameForAccount("acct-alpha")
        val betaProfile = WebViewProfileRegistry.profileNameForAccount("acct-beta")

        assertEquals(WebViewProfileRegistry.DEFAULT_PROFILE_NAME, defaultProfile)
        assertEquals("acct_acct-alpha", alphaProfile)
        assertEquals("acct_acct-beta", betaProfile)
        assertNotEquals(defaultProfile, alphaProfile)
        assertNotEquals(alphaProfile, betaProfile)

        assertTrue(
            LoginWebViewActivity.shouldDeleteGlobalWebStorage(
                multiProfileSupported = false,
                accountCount = 2,
                accountId = "acct-alpha",
            ),
        )
        assertTrue(
            LoginWebViewActivity.shouldDeleteGlobalWebStorage(
                multiProfileSupported = true,
                accountCount = 1,
                accountId = DEFAULT_ACCOUNT_ID,
            ),
        )
        assertFalse(
            LoginWebViewActivity.shouldDeleteGlobalWebStorage(
                multiProfileSupported = true,
                accountCount = 2,
                accountId = DEFAULT_ACCOUNT_ID,
            ),
        )
        assertFalse(
            LoginWebViewActivity.shouldDeleteGlobalWebStorage(
                multiProfileSupported = true,
                accountCount = 1,
                accountId = "acct-alpha",
            ),
        )
        assertFalse(
            LoginWebViewActivity.shouldDeleteGlobalWebStorage(
                multiProfileSupported = true,
                accountCount = 2,
                accountId = "acct-alpha",
            ),
        )
    }

    @Test
    fun encodeImageFileToPayloadDetectsMimeAndSanitizesPrivateFilename() {
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val privateNamedPng = File(tempFolder.root, "私人照片_2026.png").apply {
            writeBytes(pngBytes)
        }
        val payload = DouyinWebExecutor.encodeImageFileToPayload(privateNamedPng)
        assertNotNull(payload)
        assertEquals("image/png", payload!!.mimeType)
        assertEquals("image.png", payload.fileName)
        assertArrayEquals(pngBytes, Base64.getDecoder().decode(payload.base64))

        val gifBytes = "GIF89a\u0001\u0000\u0001\u0000".encodeToByteArray()
        val safeGif = File(tempFolder.root, "uuid-1234.gif").apply { writeBytes(gifBytes) }
        val gifPayload = DouyinWebExecutor.encodeImageFileToPayload(safeGif)
        assertNotNull(gifPayload)
        assertEquals("image/gif", gifPayload!!.mimeType)
        assertEquals("uuid-1234.gif", gifPayload.fileName)
    }

    @Test
    fun blobPreviewDoesNotPrematurelyConfirmUntilRemoteSignatureArrives() = runTest {
        val imgFile = createValidPngFile()
        var pollCount = 0
        val transport = ScriptedImageTransport(
            imagePayload = DouyinWebExecutor.encodeImageFileToPayload(imgFile),
            chooserSucceedsOnMotionTap = true,
            onInspectModal = {
                mapOf(
                    "modalVisible" to true,
                    "hasImgPreview" to true,
                    "sendButtonFound" to true,
                    "sendButtonEnabled" to true,
                    "sendButtonText" to "发送（1）",
                )
            },
            onClickModalSend = { mapOf("clicked" to true, "method" to "text_regex", "buttonText" to "发送（1）") },
            onSampleMedia = { idx ->
                pollCount = idx
                when (idx) {
                    1 -> mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to true,
                        "hasSendingIndicator" to false,
                        "remoteSignatures" to listOf("09:00\u001fimg:https://cdn.example/old\u001f"),
                        "localPreviewSignatures" to emptyList<String>(),
                    )
                    2, 3 -> mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to false,
                        "hasSendingIndicator" to false,
                        "remoteSignatures" to listOf("09:00\u001fimg:https://cdn.example/old\u001f"),
                        "localPreviewSignatures" to listOf("10:36\u001fimg:blob:https://www.douyin.com/temp-1\u001f"),
                    )
                    else -> mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to false,
                        "hasSendingIndicator" to false,
                        "remoteSignatures" to listOf(
                            "09:00\u001fimg:https://cdn.example/old\u001f",
                            "10:36\u001fimg:https://p3-im.douyinpic.com/uploaded\u001f",
                        ),
                        "localPreviewSignatures" to emptyList<String>(),
                    )
                }
            },
        )

        val result = DouyinWebExecutor.executeImageSend(
            bundle = bundle,
            target = sampleTarget,
            imageFile = imgFile,
            hasContentUri = true,
            transport = transport,
        )

        assertEquals(JobStatus.SUCCEEDED, result.jobStatus)
        assertEquals("image_chooser", result.inputMethodUsed)
        assertEquals(4, pollCount)
        assertTrue(transport.nowMs >= 3_500L)
    }

    @Test
    fun blobPreviewOnlyUntilTimeoutTransitionsToUnknownWithoutResending() = runTest {
        val imgFile = createValidPngFile()
        var clickCount = 0
        val transport = ScriptedImageTransport(
            imagePayload = DouyinWebExecutor.encodeImageFileToPayload(imgFile),
            chooserSucceedsOnMotionTap = true,
            onInspectModal = {
                mapOf(
                    "modalVisible" to true,
                    "hasImgPreview" to true,
                    "sendButtonFound" to true,
                    "sendButtonEnabled" to true,
                )
            },
            onClickModalSend = {
                clickCount += 1
                mapOf("clicked" to true)
            },
            onSampleMedia = { idx ->
                if (idx == 1) {
                    mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to true,
                        "signatures" to listOf("09:00\u001fimg:https://cdn.example/old\u001f"),
                    )
                } else {
                    mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to false,
                        "signatures" to listOf(
                            "09:00\u001fimg:https://cdn.example/old\u001f",
                            "10:36\u001fimg:blob:https://www.douyin.com/never-uploaded\u001f",
                        ),
                    )
                }
            },
        )

        val result = DouyinWebExecutor.executeImageSend(
            bundle = bundle,
            target = sampleTarget,
            imageFile = imgFile,
            hasContentUri = true,
            transport = transport,
        )

        assertEquals(JobStatus.UNKNOWN_REQUIRES_REVIEW, result.jobStatus)
        assertEquals(FailureClass.UNKNOWN_OUTCOME, result.failureClass)
        assertEquals(1, clickCount)
        assertTrue(result.summaryDetail.orEmpty().contains("仅观测到本地预览图"))
    }

    @Test
    fun modalStillVisibleBlocksPrematureSuccessEvenWhenSignaturesChange() = runTest {
        val imgFile = createValidPngFile()
        val transport = ScriptedImageTransport(
            imagePayload = DouyinWebExecutor.encodeImageFileToPayload(imgFile),
            chooserSucceedsOnMotionTap = true,
            onInspectModal = {
                mapOf(
                    "modalVisible" to true,
                    "hasImgPreview" to true,
                    "sendButtonFound" to true,
                    "sendButtonEnabled" to true,
                )
            },
            onClickModalSend = { mapOf("clicked" to true) },
            onSampleMedia = { idx ->
                if (idx == 1) {
                    mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to true,
                        "remoteSignatures" to listOf("09:00\u001fimg:https://cdn.example/old\u001f"),
                    )
                } else {
                    mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to true,
                        "remoteSignatures" to listOf(
                            "09:00\u001fimg:https://cdn.example/old\u001f",
                            "00:46\u001fimg:https://cdn.example/lazy-video-card\u001f",
                        ),
                    )
                }
            },
        )

        val result = DouyinWebExecutor.executeImageSend(
            bundle = bundle,
            target = sampleTarget,
            imageFile = imgFile,
            hasContentUri = true,
            transport = transport,
        )

        assertEquals(JobStatus.UNKNOWN_REQUIRES_REVIEW, result.jobStatus)
        assertTrue(result.summaryDetail.orEmpty().contains("弹窗仍未关闭"))
    }

    @Test
    fun sendingIndicatorBlocksPrematureSuccessUntilCleared() = runTest {
        val imgFile = createValidPngFile()
        var pollCount = 0
        val transport = ScriptedImageTransport(
            imagePayload = DouyinWebExecutor.encodeImageFileToPayload(imgFile),
            chooserSucceedsOnMotionTap = true,
            onInspectModal = {
                mapOf(
                    "modalVisible" to true,
                    "hasImgPreview" to true,
                    "sendButtonFound" to true,
                    "sendButtonEnabled" to true,
                )
            },
            onClickModalSend = { mapOf("clicked" to true) },
            onSampleMedia = { idx ->
                pollCount = idx
                when (idx) {
                    1 -> mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to true,
                        "hasSendingIndicator" to false,
                        "remoteSignatures" to emptyList<String>(),
                    )
                    2 -> mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to false,
                        "hasSendingIndicator" to true,
                        "remoteSignatures" to listOf("10:36\u001fimg:https://cdn.example/uploading\u001f"),
                    )
                    else -> mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to false,
                        "hasSendingIndicator" to false,
                        "remoteSignatures" to listOf("10:36\u001fimg:https://cdn.example/uploading\u001f"),
                    )
                }
            },
        )

        val result = DouyinWebExecutor.executeImageSend(
            bundle = bundle,
            target = sampleTarget,
            imageFile = imgFile,
            hasContentUri = true,
            transport = transport,
        )

        assertEquals(JobStatus.SUCCEEDED, result.jobStatus)
        assertEquals(3, pollCount)
    }

    @Test
    fun realDouyinDataUriImageFlowConfirmsAfterTempSwapAndServerAck() = runTest {
        val imgFile = createValidPngFile()
        var pollCount = 0
        val existingPhotoTag = "00:46\u001fMessageItemShareAwemephotosTag:data:image/png\u001f"
        val tempUploadSig = "11:05\u001fMessageItemImageImage MessageItemImagetemp:data:image/jpeg\u001f"
        val settledRealSig = "11:05\u001fcommonMyImageimgReal MessageItemImageImage:data:image/jpeg\u001f"
        val transport = ScriptedImageTransport(
            imagePayload = DouyinWebExecutor.encodeImageFileToPayload(imgFile),
            chooserSucceedsOnMotionTap = true,
            onInspectModal = {
                mapOf(
                    "modalVisible" to true,
                    "hasImgPreview" to true,
                    "sendButtonFound" to true,
                    "sendButtonEnabled" to true,
                    "sendButtonText" to "发送（1）",
                )
            },
            onClickModalSend = { mapOf("clicked" to true, "method" to "text_regex", "buttonText" to "发送（1）") },
            onSampleMedia = { idx ->
                pollCount = idx
                when (idx) {
                    1 -> mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to true,
                        "hasSendingIndicator" to false,
                        "remoteSignatures" to listOf("00:46\u001fimg:https://p3-pc-sign.douyinpic.com/old\u001f"),
                        "localPreviewSignatures" to listOf(existingPhotoTag),
                        "settledLocalSignatures" to listOf(existingPhotoTag),
                        "tempPreviewSignatures" to emptyList<String>(),
                    )
                    2 -> mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to false,
                        "hasSendingIndicator" to true,
                        "remoteSignatures" to listOf("00:46\u001fimg:https://p3-pc-sign.douyinpic.com/old\u001f"),
                        "localPreviewSignatures" to listOf(existingPhotoTag, tempUploadSig),
                        "settledLocalSignatures" to listOf(existingPhotoTag),
                        "tempPreviewSignatures" to listOf(tempUploadSig),
                        "imSendSucceeded" to false,
                        "uploadOrSendInFlight" to true,
                        "uploadOrSendFailed" to false,
                    )
                    3 -> mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to false,
                        "hasSendingIndicator" to true,
                        "remoteSignatures" to listOf("00:46\u001fimg:https://p3-pc-sign.douyinpic.com/old\u001f"),
                        "localPreviewSignatures" to listOf(existingPhotoTag),
                        "settledLocalSignatures" to listOf(existingPhotoTag),
                        "tempPreviewSignatures" to emptyList<String>(),
                        "imSendSucceeded" to true,
                        "uploadOrSendInFlight" to false,
                        "uploadOrSendFailed" to false,
                    )
                    else -> mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to false,
                        "hasSendingIndicator" to false,
                        "remoteSignatures" to listOf("00:46\u001fimg:https://p3-pc-sign.douyinpic.com/old\u001f"),
                        "localPreviewSignatures" to listOf(existingPhotoTag, settledRealSig),
                        "settledLocalSignatures" to listOf(existingPhotoTag, settledRealSig),
                        "tempPreviewSignatures" to emptyList<String>(),
                        "imSendSucceeded" to true,
                        "uploadOrSendInFlight" to false,
                        "uploadOrSendFailed" to false,
                    )
                }
            },
        )

        val result = DouyinWebExecutor.executeImageSend(
            bundle = bundle,
            target = sampleTarget,
            imageFile = imgFile,
            hasContentUri = true,
            transport = transport,
        )

        assertEquals(JobStatus.SUCCEEDED, result.jobStatus)
        assertEquals("image_chooser", result.inputMethodUsed)
        assertEquals(4, pollCount)
        assertTrue(result.summaryDetail.orEmpty().contains("服务端发送回执确认"))
    }

    @Test
    fun tempUploadImageOrFailedNetworkBlocksSettledLocalConfirmation() = runTest {
        val imgFile = createValidPngFile()
        val tempUploadSig = "11:05\u001fMessageItemImagetemp:data:image/jpeg\u001f"
        val settledRealSig = "11:05\u001fcommonMyImageimgReal:data:image/jpeg\u001f"

        // 子场景 1：仍停留在 temp 占位图即使有回执也绝不判成功
        val tempOnlyTransport = ScriptedImageTransport(
            imagePayload = DouyinWebExecutor.encodeImageFileToPayload(imgFile),
            chooserSucceedsOnMotionTap = true,
            onInspectModal = {
                mapOf(
                    "modalVisible" to true,
                    "hasImgPreview" to true,
                    "sendButtonFound" to true,
                    "sendButtonEnabled" to true,
                )
            },
            onClickModalSend = { mapOf("clicked" to true) },
            onSampleMedia = { idx ->
                if (idx == 1) {
                    mapOf("failureMarkerHit" to false, "modalVisible" to true, "signatures" to emptyList<String>())
                } else {
                    mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to false,
                        "hasSendingIndicator" to false,
                        "signatures" to listOf(tempUploadSig),
                        "imSendSucceeded" to true,
                    )
                }
            },
        )
        val res1 = DouyinWebExecutor.executeImageSend(
            bundle = bundle,
            target = sampleTarget,
            imageFile = imgFile,
            hasContentUri = true,
            transport = tempOnlyTransport,
        )
        assertEquals(JobStatus.UNKNOWN_REQUIRES_REVIEW, res1.jobStatus)

        // 子场景 2：出现 uploadOrSendFailed 时即使正式本地图可见也绝不判成功
        val netFailTransport = ScriptedImageTransport(
            imagePayload = DouyinWebExecutor.encodeImageFileToPayload(imgFile),
            chooserSucceedsOnMotionTap = true,
            onInspectModal = {
                mapOf(
                    "modalVisible" to true,
                    "hasImgPreview" to true,
                    "sendButtonFound" to true,
                    "sendButtonEnabled" to true,
                )
            },
            onClickModalSend = { mapOf("clicked" to true) },
            onSampleMedia = { idx ->
                if (idx == 1) {
                    mapOf("failureMarkerHit" to false, "modalVisible" to true, "signatures" to emptyList<String>())
                } else {
                    mapOf(
                        "failureMarkerHit" to false,
                        "modalVisible" to false,
                        "hasSendingIndicator" to false,
                        "settledLocalSignatures" to listOf(settledRealSig),
                        "imSendSucceeded" to true,
                        "uploadOrSendFailed" to true,
                    )
                }
            },
        )
        val res2 = DouyinWebExecutor.executeImageSend(
            bundle = bundle,
            target = sampleTarget,
            imageFile = imgFile,
            hasContentUri = true,
            transport = netFailTransport,
        )
        assertEquals(JobStatus.UNKNOWN_REQUIRES_REVIEW, res2.jobStatus)
    }

    private fun createValidPngFile(): File =
        File(tempFolder.root, "test-image.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        }

    private class ScriptedImageTransport(
        private val imagePayload: DouyinWebExecutor.EncodedImagePayload?,
        private val openResult: DouyinWebExecutor.OpenConversationResult =
            DouyinWebExecutor.OpenConversationResult(
                opened = true,
                exact = true,
                observedPlatformUserId = "sec_uid_test_1",
                observedConversationType = "friend",
            ),
        private val editorReady: Boolean = true,
        private val probeResult: Map<String, Any?>? = mapOf(
            "triggerFound" to true,
            "triggerCount" to 1,
            "centerX" to 420.0,
            "centerY" to 780.0,
            "viewportWidth" to 1440,
            "viewportHeight" to 900,
            "fileInputCount" to 2,
            "hasUploadEntry" to true,
        ),
        private val chooserSucceedsOnMotionTap: Boolean = false,
        private val onInjectDataTransfer: (() -> Map<String, Any?>?) = { mapOf("ok" to false, "injected" to false) },
        private val onInspectModal: (() -> Map<String, Any?>?) = {
            mapOf("modalVisible" to false, "hasImgPreview" to false, "sendButtonFound" to false, "sendButtonEnabled" to false)
        },
        private val onClickModalSend: (() -> Map<String, Any?>?) = { mapOf("clicked" to false) },
        private val onSampleMedia: ((pollIndex: Int) -> Map<String, Any?>?) = {
            mapOf("failureMarkerHit" to false, "signatures" to emptyList<String>())
        },
    ) : DouyinWebExecutor.ImageSendTransport {

        var openCalls = 0
        var motionTapCalls = 0
        var nowMs = 1_000L
        private var sampleMediaCalls = 0
        private var pendingTriggerSuccess = false
        val evaluatedKinds = mutableListOf<String>()

        override suspend fun openTargetConversation(
            bundle: SelectorBundle,
            target: ContractTargetEntity,
        ): DouyinWebExecutor.OpenConversationResult {
            openCalls += 1
            return openResult
        }

        override suspend fun waitForEditorReady(bundle: SelectorBundle, timeoutMs: Long): Boolean = editorReady

        override suspend fun waitForMessageListSettled(bundle: SelectorBundle) = Unit

        override suspend fun dispatchMotionTap(
            cssX: Float,
            cssY: Float,
            viewportWidth: Int,
            viewportHeight: Int,
        ): Boolean {
            motionTapCalls += 1
            if (chooserSucceedsOnMotionTap) {
                pendingTriggerSuccess = true
            }
            return true
        }

        override suspend fun runWithFileChooserArmed(
            triggerActions: suspend (awaitTriggered: suspend (Long) -> Boolean) -> Boolean,
        ): Boolean {
            pendingTriggerSuccess = false
            return triggerActions { timeoutMs ->
                if (pendingTriggerSuccess) {
                    true
                } else {
                    nowMs += timeoutMs
                    false
                }
            }
        }

        override suspend fun evalJsObject(script: String): Map<String, Any?>? = when {
            script.contains("hasUploadEntry") -> {
                evaluatedKinds += "probe_upload_dom"
                probeResult
            }
            script.contains("upload_trigger_not_found") -> {
                evaluatedKinds += "click_upload_trigger"
                mapOf("clicked" to true)
            }
            script.contains("file_input_click_failed") -> {
                evaluatedKinds += "click_file_input"
                mapOf("clicked" to true)
            }
            script.contains("new DataTransfer()") -> {
                evaluatedKinds += "inject_datatransfer"
                onInjectDataTransfer()
            }
            script.contains("hasImgPreview") -> {
                evaluatedKinds += "inspect_modal"
                onInspectModal()
            }
            script.contains("send_button_disabled") -> {
                evaluatedKinds += "click_modal_send"
                onClickModalSend()
            }
            script.contains("modalWasVisible") -> {
                evaluatedKinds += "dismiss_modal"
                mapOf("dismissed" to true, "modalWasVisible" to true, "method" to "cancel_button")
            }
            script.contains("pendingImgCount") -> {
                evaluatedKinds += "sample_media"
                sampleMediaCalls += 1
                onSampleMedia(sampleMediaCalls)
            }
            else -> null
        }

        override fun encodeImageForDataTransfer(imageFile: File): DouyinWebExecutor.EncodedImagePayload? = imagePayload

        override fun clockMs(): Long = nowMs

        override suspend fun delayMs(ms: Long) {
            nowMs += ms
        }
    }
}
