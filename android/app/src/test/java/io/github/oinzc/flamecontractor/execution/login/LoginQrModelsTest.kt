package io.github.oinzc.flamecontractor.execution.login

import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.execution.LoginMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginQrModelsTest {

    @Test
    fun validateQrIndexUrlAllowsOfficialHttpsHostsAndRejectsSpoofedOrHttp() {
        val valid = "https://api.amemv.com/ucenter_web/app/aweme/scan_login/index/douyin_scan_code_login/cn/app/index.html?token=abc"
        assertEquals(valid, LoginQrParser.validateQrIndexUrl(valid))
        assertNotNull(LoginQrParser.validateQrIndexUrl("https://sso.douyin.com/qr_code_login?token=1"))
        assertNotNull(LoginQrParser.validateQrIndexUrl("https://aweme.snssdk.com/ucenter_web/app?token=2"))
        assertNotNull(LoginQrParser.validateQrIndexUrl("https://auth.zijieapi.com/ucenter_web/app?token=3"))

        assertNull(LoginQrParser.validateQrIndexUrl(null))
        assertNull(LoginQrParser.validateQrIndexUrl("   "))
        assertNull(LoginQrParser.validateQrIndexUrl("http://api.amemv.com/scan_login"))
        assertNull(LoginQrParser.validateQrIndexUrl("https://douyin.com.evil.example/scan_login"))
        assertNull(LoginQrParser.validateQrIndexUrl("https://notamemv.com/scan_login"))
        assertNull(LoginQrParser.validateQrIndexUrl("https://user@api.amemv.com/scan_login"))
        assertNull(LoginQrParser.validateQrIndexUrl("javascript:alert(1)"))
    }

    @Test
    fun stripBase64ImagePrefixHandlesRawAndDataUrlAndRejectsInvalid() {
        assertEquals(
            "iVBORw0KGgoAAAANSUhEUg==",
            LoginQrParser.stripBase64ImagePrefix("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="),
        )
        assertEquals(
            "iVBORw0KGgoAAAANSUhEUg==",
            LoginQrParser.stripBase64ImagePrefix("  iVBORw0KGgoAAAANSUhEUg== \n"),
        )
        assertNull(LoginQrParser.stripBase64ImagePrefix(null))
        assertNull(LoginQrParser.stripBase64ImagePrefix("   "))
        assertNull(LoginQrParser.stripBase64ImagePrefix("data:image/jpeg;base64,iVBORw0KGgo="))
        assertNull(LoginQrParser.stripBase64ImagePrefix("data:text/html;base64,PHNjcmlwdD4="))
        assertNull(LoginQrParser.stripBase64ImagePrefix("https://example.com/a.png"))
    }

    @Test
    fun parseGetQrCodeJsonConvertsEpochSecondsAndFallsBackWhenMissingOrSpoofedUrl() {
        val nowMs = 1_795_527_700_000L
        val raw = """
            {
              "data": {
                "qrcode": "iVBORw0KGgo=",
                "qrcode_index_url": "https://api.amemv.com/scan?token=1",
                "expire_time": 1795527820
              },
              "error_code": 0,
              "message": "success"
            }
        """.trimIndent()
        val parsed = LoginQrParser.parseGetQrCodeJson(raw, nowMs)
        assertNotNull(parsed)
        assertEquals("iVBORw0KGgo=", parsed!!.base64Png)
        assertEquals("https://api.amemv.com/scan?token=1", parsed.validatedIndexUrl)
        assertEquals(1_795_527_820_000L, parsed.expireAtEpochMs)

        val untrustedUrlJson = """
            {
              "data": {
                "qrcode": "data:image/png;base64,iVBORw0KGgo=",
                "qrcode_index_url": "https://evil.example/phish",
                "expire_time": 0
              }
            }
        """.trimIndent()
        val fallback = LoginQrParser.parseGetQrCodeJson(untrustedUrlJson, nowMs)
        assertNotNull(fallback)
        assertEquals("iVBORw0KGgo=", fallback!!.base64Png)
        assertNull(fallback.validatedIndexUrl)
        assertEquals(nowMs + LoginQrParser.DEFAULT_QR_TTL_MS, fallback.expireAtEpochMs)

        val alreadyPastJson = """{"data":{"qrcode":"iVBORw0KGgo=","expire_time":1700000000}}"""
        val pastFallback = LoginQrParser.parseGetQrCodeJson(alreadyPastJson, nowMs)
        assertNotNull(pastFallback)
        assertEquals(nowMs + LoginQrParser.DEFAULT_QR_TTL_MS, pastFallback!!.expireAtEpochMs)

        assertNull(LoginQrParser.parseGetQrCodeJson("""{"data":{}}""", nowMs))
        assertNull(LoginQrParser.parseGetQrCodeJson("not-json", nowMs))
    }

    @Test
    fun parseQrConnectJsonMapsKnownStatesSafely() {
        assertEquals(
            QrConnectStatus.NEW,
            LoginQrParser.parseQrConnectJson("""{"data":{"status":"new"},"message":"success"}"""),
        )
        assertEquals(
            QrConnectStatus.SCANNED,
            LoginQrParser.parseQrConnectJson("""{"data":{"status":"scanned"}}"""),
        )
        assertEquals(
            QrConnectStatus.SCANNED,
            LoginQrParser.parseQrConnectJson("""{"data":{"status":"scan"}}"""),
        )
        assertEquals(
            QrConnectStatus.CONFIRMED,
            LoginQrParser.parseQrConnectJson("""{"data":{"status":"confirmed"}}"""),
        )
        assertEquals(
            QrConnectStatus.CONFIRMED,
            LoginQrParser.parseQrConnectJson("""{"data":{"status":"confirm"}}"""),
        )
        assertEquals(
            QrConnectStatus.EXPIRED,
            LoginQrParser.parseQrConnectJson("""{"data":{"status":"expired"}}"""),
        )
        assertEquals(
            QrConnectStatus.UNKNOWN,
            LoginQrParser.parseQrConnectJson("""{"data":{"status":"something_else"}}"""),
        )
        assertEquals(QrConnectStatus.UNKNOWN, LoginQrParser.parseQrConnectJson(null))
    }

    @Test
    fun douyinVariantPriorityAndDeepLinksEncodeCleanly() {
        assertEquals(
            DouyinClientVariant.DOUYIN_MAIN,
            DouyinSchemeBuilder.resolvePreferredDouyinClient(
                setOf("com.ss.android.ugc.aweme.lite", "com.ss.android.ugc.aweme"),
            ),
        )
        assertEquals(
            DouyinClientVariant.DOUYIN_LITE,
            DouyinSchemeBuilder.resolvePreferredDouyinClient(
                setOf("com.ss.android.ugc.live", "com.ss.android.ugc.aweme.lite"),
            ),
        )
        assertEquals(
            DouyinClientVariant.DOUYIN_VOLCANO,
            DouyinSchemeBuilder.resolvePreferredDouyinClient(setOf("com.ss.android.ugc.live")),
        )
        assertNull(DouyinSchemeBuilder.resolvePreferredDouyinClient(emptySet()))

        val indexUrl = "https://api.amemv.com/scan_login/index.html?_pia_=1&token=abc 123"
        val deepLink = DouyinSchemeBuilder.buildConfirmLoginDeepLink(
            DouyinClientVariant.DOUYIN_LITE,
            indexUrl,
        )
        assertEquals(
            "snssdk2329://webview?url=https%3A%2F%2Fapi.amemv.com%2Fscan_login%2Findex.html%3F_pia_%3D1%26token%3Dabc%20123",
            deepLink,
        )
        assertEquals(
            "snssdk1128://aweme/scan",
            DouyinSchemeBuilder.buildScanCameraDeepLink(DouyinClientVariant.DOUYIN_MAIN),
        )
    }

    @Test
    fun initialDisplayModeAndAutoFinishResidencyFollowSpec() {
        assertEquals(
            LoginDisplayMode.NATIVE_QR,
            LoginSessionReducer.initialDisplayMode(LoginMode.PRESERVE_SESSION),
        )
        assertEquals(
            LoginDisplayMode.NATIVE_QR,
            LoginSessionReducer.initialDisplayMode(LoginMode.CLEAR_AND_RELOGIN),
        )
        assertEquals(
            LoginDisplayMode.WEB_FALLBACK,
            LoginSessionReducer.initialDisplayMode(LoginMode.INSPECT_OR_VERIFY),
        )

        assertFalse(
            LoginSessionReducer.shouldAutoFinishOnAuthenticated(
                LoginMode.INSPECT_OR_VERIFY,
                AccountStatus.HEALTHY,
                userInitiated = false,
            ),
        )
        assertFalse(
            LoginSessionReducer.shouldAutoFinishOnAuthenticated(
                LoginMode.INSPECT_OR_VERIFY,
                AccountStatus.PAUSED,
                userInitiated = false,
            ),
        )
        assertFalse(
            LoginSessionReducer.shouldAutoFinishOnAuthenticated(
                LoginMode.INSPECT_OR_VERIFY,
                AccountStatus.COOLDOWN,
                userInitiated = false,
            ),
        )
        assertFalse(
            LoginSessionReducer.shouldAutoFinishOnAuthenticated(
                LoginMode.INSPECT_OR_VERIFY,
                AccountStatus.DOM_BROKEN,
                userInitiated = false,
            ),
        )
        assertTrue(
            LoginSessionReducer.shouldAutoFinishOnAuthenticated(
                LoginMode.INSPECT_OR_VERIFY,
                AccountStatus.VERIFICATION_REQUIRED,
                userInitiated = false,
            ),
        )
        assertTrue(
            LoginSessionReducer.shouldAutoFinishOnAuthenticated(
                LoginMode.INSPECT_OR_VERIFY,
                AccountStatus.HEALTHY,
                userInitiated = true,
            ),
        )
        assertTrue(
            LoginSessionReducer.shouldAutoFinishOnAuthenticated(
                LoginMode.PRESERVE_SESSION,
                AccountStatus.HEALTHY,
                userInitiated = false,
            ),
        )
        assertTrue(
            LoginSessionReducer.shouldAutoFinishOnAuthenticated(
                LoginMode.CLEAR_AND_RELOGIN,
                AccountStatus.HEALTHY,
                userInitiated = false,
            ),
        )
    }

    @Test
    fun resolveUpdatedIndexUrlNeverCarriesStaleTokenAcrossRefreshedOrRejectedPayloads() {
        val oldUrl = "https://api.amemv.com/scan?token=old"
        val newUrl = "https://api.amemv.com/scan?token=new"

        // Authoritative get_qrcode with null/rejected URL MUST clear previous URL
        assertNull(
            LoginSessionReducer.resolveUpdatedIndexUrl(
                incomingUrl = null,
                previousUrl = oldUrl,
                fromAuthoritativeNetwork = true,
                imageChanged = true,
            ),
        )
        assertEquals(
            newUrl,
            LoginSessionReducer.resolveUpdatedIndexUrl(
                incomingUrl = newUrl,
                previousUrl = oldUrl,
                fromAuthoritativeNetwork = true,
                imageChanged = true,
            ),
        )
        // DOM fallback keeps URL only when QR image has not changed
        assertEquals(
            oldUrl,
            LoginSessionReducer.resolveUpdatedIndexUrl(
                incomingUrl = null,
                previousUrl = oldUrl,
                fromAuthoritativeNetwork = false,
                imageChanged = false,
            ),
        )
        assertNull(
            LoginSessionReducer.resolveUpdatedIndexUrl(
                incomingUrl = null,
                previousUrl = oldUrl,
                fromAuthoritativeNetwork = false,
                imageChanged = true,
            ),
        )
    }

    @Test
    fun qrLoadTimeoutAndDomExpiryGuardAgainstWebFallbackAndConfirmedOverwrite() {
        val t0 = 1_000_000L
        assertTrue(
            LoginSessionReducer.shouldTriggerQrLoadTimeout(
                displayMode = LoginDisplayMode.NATIVE_QR,
                stage = QrVisualStage.GENERATING_QR,
                hasQrBitmap = false,
                verificationRequired = false,
                inspectedHealthyOnce = false,
                loadStartedAtEpochMs = t0,
                nowEpochMs = t0 + 20_500L,
            ),
        )
        // Must NOT misfire in WEB_FALLBACK (e.g. INSPECT_OR_VERIFY or SMS tab)
        assertFalse(
            LoginSessionReducer.shouldTriggerQrLoadTimeout(
                displayMode = LoginDisplayMode.WEB_FALLBACK,
                stage = QrVisualStage.CONNECTING,
                hasQrBitmap = false,
                verificationRequired = false,
                inspectedHealthyOnce = false,
                loadStartedAtEpochMs = t0,
                nowEpochMs = t0 + 25_000L,
            ),
        )
        assertFalse(
            LoginSessionReducer.shouldTriggerQrLoadTimeout(
                displayMode = LoginDisplayMode.NATIVE_QR,
                stage = QrVisualStage.CONNECTING,
                hasQrBitmap = false,
                verificationRequired = true,
                inspectedHealthyOnce = false,
                loadStartedAtEpochMs = t0,
                nowEpochMs = t0 + 25_000L,
            ),
        )
        assertFalse(
            LoginSessionReducer.shouldTriggerQrLoadTimeout(
                displayMode = LoginDisplayMode.NATIVE_QR,
                stage = QrVisualStage.CONNECTING,
                hasQrBitmap = false,
                verificationRequired = false,
                inspectedHealthyOnce = true,
                loadStartedAtEpochMs = t0,
                nowEpochMs = t0 + 25_000L,
            ),
        )

        assertTrue(LoginSessionReducer.shouldMarkDomExpired(true, true, QrVisualStage.READY))
        assertTrue(LoginSessionReducer.shouldMarkDomExpired(true, true, QrVisualStage.SCANNED))
        assertFalse(LoginSessionReducer.shouldMarkDomExpired(true, true, QrVisualStage.CONFIRMED))
    }

    @Test
    fun backgroundReturnDecisionTriggersOnlyWhenActivityIsNotResumedAndProvidesClearCopy() {
        assertTrue(LoginSessionReducer.shouldReturnToForegroundFromBackground(isActivityResumed = false))
        assertFalse(LoginSessionReducer.shouldReturnToForegroundFromBackground(isActivityResumed = true))

        assertEquals(
            LoginMode.PRESERVE_SESSION,
            LoginSessionReducer.safeReturnLoginMode(LoginMode.CLEAR_AND_RELOGIN),
        )
        assertEquals(
            LoginMode.PRESERVE_SESSION,
            LoginSessionReducer.safeReturnLoginMode(LoginMode.PRESERVE_SESSION),
        )
        assertEquals(
            LoginMode.INSPECT_OR_VERIFY,
            LoginSessionReducer.safeReturnLoginMode(LoginMode.INSPECT_OR_VERIFY),
        )

        assertTrue(LoginReturnEvent.QR_CONFIRMED.title.contains("已确认登录"))
        assertTrue(LoginReturnEvent.QR_CONFIRMED.body.contains("返回"))
        assertTrue(LoginReturnEvent.LOGIN_SUCCEEDED.title.contains("登录成功"))
        assertTrue(LoginReturnEvent.LOGIN_SUCCEEDED.body.contains("返回"))
        assertTrue(LoginReturnEvent.VERIFICATION_REQUIRED.title.contains("安全验证"))
        assertTrue(LoginReturnEvent.ACCOUNT_SWITCH_CONFIRM.title.contains("更换"))
    }

    @Test
    fun keepAliveFgsCoordinatorDefersStopUntilStartForegroundCompletesToPreventCrash() {
        val guard = KeepAliveFgsCoordinator()

        // 1. Stop when IDLE is a no-op
        assertEquals(KeepAliveStopAction.NO_OP, guard.onRequestStop())

        // 2. Repro of permission-dialog onResume race: start() then stop() BEFORE onStartCommand()
        assertTrue(guard.onRequestStart())
        assertEquals(KeepAliveStopAction.DEFER_UNTIL_STARTED, guard.onRequestStop())
        // Once onStartCommand runs startForeground(ok=true), it must immediately stopSelf safely
        assertEquals(
            KeepAliveStartResult.STOP_SELF_AFTER_FOREGROUND,
            guard.onStartCommandEntered(startForegroundSucceeded = true),
        )
        assertEquals(KeepAliveStopAction.NO_OP, guard.onRequestStop())

        // 3. Normal lifecycle: start() -> onStartCommand(true) -> stop() -> STOP_SERVICE_NOW
        assertTrue(guard.onRequestStart())
        assertEquals(
            KeepAliveStartResult.KEEP_RUNNING,
            guard.onStartCommandEntered(startForegroundSucceeded = true),
        )
        assertEquals(KeepAliveStopAction.STOP_SERVICE_NOW, guard.onRequestStop())
        assertEquals(KeepAliveStopAction.NO_OP, guard.onRequestStop())

        // 4. Re-start cancels a pending deferred stop
        assertTrue(guard.onRequestStart())
        assertEquals(KeepAliveStopAction.DEFER_UNTIL_STARTED, guard.onRequestStop())
        assertTrue(guard.onRequestStart())
        assertEquals(
            KeepAliveStartResult.KEEP_RUNNING,
            guard.onStartCommandEntered(startForegroundSucceeded = true),
        )
        guard.onServiceDestroyed()
        assertEquals(KeepAliveStopAction.NO_OP, guard.onRequestStop())

        // 5. Failed startForegroundService dispatch rolls back state cleanly
        assertTrue(guard.onRequestStart())
        guard.onStartDispatchFailed()
        assertEquals(KeepAliveStopAction.NO_OP, guard.onRequestStop())
    }

    @Test
    fun loginResourceFilterSkipsOnlyHeavyMediaAndFontsWhileProtectingAuthAndCaptcha() {
        // 1. 推荐视频流接口返回空列表占位，防止后台播放推荐视频与音频解码
        assertEquals(
            LoginSubresourceAction.STUB_EMPTY_FEED,
            LoginResourceFilter.classifySubresource(
                "https://www.douyin.com/aweme/v1/web/tab/feed/?device_platform=webapp",
                isForMainFrame = false,
            ),
        )
        assertEquals(
            LoginSubresourceAction.STUB_EMPTY_FEED,
            LoginResourceFilter.classifySubresource(
                "https://www.douyin.com/aweme/v1/web/channel/feed/?count=10",
                isForMainFrame = false,
            ),
        )

        // 2. 跳过重型音视频流、Range 媒体分片、音频工作线程、特效 CDN、封面图与字体
        assertEquals(
            LoginSubresourceAction.BLOCK_EMPTY,
            LoginResourceFilter.classifySubresource("https://v3-web.douyinvod.com/abc/video.mp4", isForMainFrame = false),
        )
        assertEquals(
            LoginSubresourceAction.BLOCK_EMPTY,
            LoginResourceFilter.classifySubresource("https://lf6-effectcdn-tos.byteeffecttos.com/obj/ies.fe.effect/a.mp4", isForMainFrame = false),
        )
        assertEquals(
            LoginSubresourceAction.BLOCK_EMPTY,
            LoginResourceFilter.classifySubresource("https://lf-douyin-pc-web.douyinstatic.com/obj/douyin-pc-web/mammon-worklet-processor.min.js", isForMainFrame = false),
        )
        assertEquals(
            LoginSubresourceAction.BLOCK_EMPTY,
            LoginResourceFilter.classifySubresource("https://v26-web.example.com/video/tos/cn/stream?mime_type=video_mp4", isForMainFrame = false),
        )
        assertEquals(
            LoginSubresourceAction.BLOCK_EMPTY,
            LoginResourceFilter.classifySubresource("https://cdn.example.com/stream/opaque", isForMainFrame = false, hasRangeHeader = true),
        )
        assertTrue(LoginResourceFilter.shouldSkipDuringNativeQr("https://cdn.example.com/assets/font.woff2?v=1", isForMainFrame = false))
        assertTrue(LoginResourceFilter.shouldSkipDuringNativeQr("https://p3-pc.douyinpic.com/img/avatar.webp", isForMainFrame = false))

        // 3. 绝不拦截主文档、核心脚本(含 byteimg 上的 bdms)、样式、登录接口、IM、风控与验证码资源
        assertEquals(
            LoginSubresourceAction.ALLOW,
            LoginResourceFilter.classifySubresource("https://www.douyin.com/chat", isForMainFrame = true),
        )
        assertEquals(
            LoginSubresourceAction.ALLOW,
            LoginResourceFilter.classifySubresource("https://p-pc-weboff.byteimg.com/tos-cn-i-9r5gewecjs/bdms_1.0.1.19_fix.js", isForMainFrame = false),
        )
        assertFalse(LoginResourceFilter.shouldSkipDuringNativeQr("https://sso.douyin.com/passport/web/get_qrcode/", isForMainFrame = false))
        assertFalse(LoginResourceFilter.shouldSkipDuringNativeQr("https://sso.douyin.com/passport/web/check_qrconnect/", isForMainFrame = false))
        assertFalse(LoginResourceFilter.shouldSkipDuringNativeQr("https://verify.snssdk.com/captcha/font.woff2", isForMainFrame = false))
        assertFalse(LoginResourceFilter.shouldSkipDuringNativeQr("https://rmc.bytedance.com/bdturing/challenge.mp4", isForMainFrame = false))
        assertFalse(LoginResourceFilter.shouldSkipDuringNativeQr("https://www.douyin.com/aweme/v1/web/im/user/info/", isForMainFrame = false))
        assertFalse(LoginResourceFilter.shouldSkipDuringNativeQr("https://lf-cdn-tos.bytescm.com/obj/static/app.js", isForMainFrame = false))
        assertFalse(LoginResourceFilter.shouldSkipDuringNativeQr("https://lf-cdn-tos.bytescm.com/obj/static/style.css", isForMainFrame = false))
    }
}
