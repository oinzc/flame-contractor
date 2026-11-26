package io.github.oinzc.flamecontractor.execution

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import io.github.oinzc.flamecontractor.app.FlameApplication
import io.github.oinzc.flamecontractor.core.account.AccountManager
import io.github.oinzc.flamecontractor.core.account.WebViewProfileRegistry
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundleStore
import io.github.oinzc.flamecontractor.core.storage.OfficialSparkLitStore
import io.github.oinzc.flamecontractor.core.theme.ThemePreference
import io.github.oinzc.flamecontractor.core.theme.ThemePreferencesStore
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.domain.AccountHealthReducer
import io.github.oinzc.flamecontractor.domain.AuthProbeOutcome
import io.github.oinzc.flamecontractor.execution.login.DouyinAppLauncher
import io.github.oinzc.flamecontractor.execution.login.LoginDisplayMode
import io.github.oinzc.flamecontractor.execution.login.LoginKeepAliveService
import io.github.oinzc.flamecontractor.execution.login.LoginQrAlbumSaver
import io.github.oinzc.flamecontractor.execution.login.LoginQrParser
import io.github.oinzc.flamecontractor.execution.login.LoginResourceFilter
import io.github.oinzc.flamecontractor.execution.login.LoginReturnEvent
import io.github.oinzc.flamecontractor.execution.login.LoginReturnHelper
import io.github.oinzc.flamecontractor.execution.login.LoginSessionReducer
import io.github.oinzc.flamecontractor.execution.login.LoginSubresourceAction
import io.github.oinzc.flamecontractor.execution.login.QrConnectStatus
import io.github.oinzc.flamecontractor.execution.login.QrVisualStage
import io.github.oinzc.flamecontractor.execution.webview.DesktopWebViewFactory
import io.github.oinzc.flamecontractor.scheduler.ScheduleCoordinator
import io.github.oinzc.flamecontractor.ui.login.AccountSwitchPromptState
import io.github.oinzc.flamecontractor.ui.login.LoginScreen
import io.github.oinzc.flamecontractor.ui.login.LoginScreenState
import io.github.oinzc.flamecontractor.ui.theme.FlameTheme
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 跳往抖音 App 确认授权期间阻止 Chromium 进入后台隐藏休眠的定制 WebView：
 * - 当 `keepAliveInBackground == true`（仅在跳往抖音后的最长 3 分钟内开启）时，
 *   拦截 `onWindowVisibilityChanged` 与 `onVisibilityChanged` 的不可见事件并维持 `View.VISIBLE`，
 *   确保抖音网页端持续轮询 `/passport/web/check_qrconnect/` 并在确认后立刻完成会话加载；
 * - 返回前台或超时/销毁后立即恢复真实窗口可见性，不造成常驻后台耗电。
 */
internal class KeepAliveLoginWebView(context: Context) : WebView(context) {

    private var keepAliveInBackground: Boolean = false
    private var lastRealWindowVisibility: Int = View.VISIBLE
    private var lastRealWindowFocus: Boolean = true

    fun setKeepAliveInBackground(enabled: Boolean) {
        if (keepAliveInBackground == enabled) return
        keepAliveInBackground = enabled
        runCatching {
            setRendererPriorityPolicy(
                RENDERER_PRIORITY_IMPORTANT,
                /* waivedWhenNotVisible = */ !enabled,
            )
        }
        if (!enabled) {
            if (lastRealWindowVisibility != View.VISIBLE) {
                super.onWindowVisibilityChanged(lastRealWindowVisibility)
            }
            if (!lastRealWindowFocus) {
                super.onWindowFocusChanged(false)
            }
        }
    }

    override fun getWindowVisibility(): Int =
        if (keepAliveInBackground) View.VISIBLE else super.getWindowVisibility()

    override fun onWindowVisibilityChanged(visibility: Int) {
        lastRealWindowVisibility = visibility
        if (keepAliveInBackground && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
            return
        }
        super.onWindowVisibilityChanged(visibility)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        lastRealWindowFocus = hasWindowFocus
        if (keepAliveInBackground && !hasWindowFocus) {
            super.onWindowFocusChanged(true)
            return
        }
        super.onWindowFocusChanged(hasWindowFocus)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (keepAliveInBackground && visibility != View.VISIBLE) {
            super.onVisibilityChanged(changedView, View.VISIBLE)
            return
        }
        super.onVisibilityChanged(changedView, visibility)
    }
}

/**
 * 原生 Compose 登录工作台 + 底层全尺寸遮蔽桌面模式 WebView 宿主：
 * - 默认以 `FlameTheme` 原生卡片展示加载阶段、高清二维码与「打开抖音一键确认登录」；
 * - 底层 `WebView` 保持真实布局尺寸不设 `GONE`，通过 `DOCUMENT_START` 钩子 + DOM 探针采集二维码与授权态；
 * - 跳往抖音期间启动独立 `LoginKeepAliveService` 保活，切回 `onResume` 触发 800ms 极速回检；
 * - 严格保留 `ExecutionMutex`、`WebViewProfileRegistry`、`AccountSwitchGuard` 与临时相册二维码自清理。
 */
class LoginWebViewActivity : AppCompatActivity() {

    private lateinit var webView: KeepAliveLoginWebView
    private lateinit var loginMode: LoginMode
    private lateinit var accountId: String
    private lateinit var profileName: String
    private lateinit var database: FlameDatabase
    private lateinit var selectorStore: SelectorBundleStore
    private lateinit var douyinLauncher: DouyinAppLauncher
    private lateinit var albumSaver: LoginQrAlbumSaver
    private lateinit var returnHelper: LoginReturnHelper

    private val snackbarHostState = SnackbarHostState()
    private var screenState by mutableStateOf(LoginScreenState())

    private var mutexSession: ExecutionMutex.SessionToken? = null
    private var pollJob: Job? = null
    private var tickerJob: Job? = null
    private var refreshWatchdogJob: Job? = null
    private var backgroundKeepAliveJob: Job? = null
    private var pendingFallbackNotifyJob: Job? = null

    private var initialAccountStatus: AccountStatus = AccountStatus.PENDING_LOGIN
    private var handlingLoginSuccess = false
    private var identityHydrationRetries = 0
    private var inspectedHealthyOnce = false

    @Volatile
    private var allowSubresourceFilter: Boolean = true
    private val blockedSubresourceCount = AtomicInteger(0)
    private val stubbedFeedCount = AtomicInteger(0)

    private var loadStartedAtEpochMs: Long = 0L
    private var qrExpireAtEpochMs: Long = 0L
    private var currentQrBase64: String? = null
    private var fastPollUntilEpochMs: Long = 0L
    private var pendingSaveAfterPermission: Boolean = false
    private var pendingSaveReadyForResume: Boolean = false
    private var isActivityResumed: Boolean = false
    private var notifiedBackgroundQrConfirmed: Boolean = false
    private var notifiedBackgroundVerification: Boolean = false
    private var askedNotificationPermissionOnce: Boolean = false
    private var pendingExternalLaunchAction: (() -> Unit)? = null
    private var pendingExternalLaunchReadyForResume: Boolean = false

    private val writeStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        if (pendingSaveAfterPermission) {
            pendingSaveAfterPermission = false
            pendingSaveReadyForResume = true
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        if (pendingExternalLaunchAction != null) {
            pendingExternalLaunchReadyForResume = true
        }
    }

    @SuppressLint("JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        loginMode = parseLoginMode(intent?.getStringExtra(EXTRA_LOGIN_MODE))
        accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ACCOUNT_ID
        profileName = WebViewProfileRegistry.profileNameForAccount(accountId)

        val appCtx = applicationContext ?: this
        database = ServiceDatabaseHolder.getInstance(appCtx)
        selectorStore = SelectorBundleStore(appCtx)
        douyinLauncher = DouyinAppLauncher(appCtx)
        albumSaver = LoginQrAlbumSaver(appCtx)
        returnHelper = LoginReturnHelper(appCtx).also { it.ensureChannel() }

        val initialDisplay = LoginSessionReducer.initialDisplayMode(loginMode)
        allowSubresourceFilter = initialDisplay == LoginDisplayMode.NATIVE_QR
        screenState = LoginScreenState(
            loginMode = loginMode,
            displayMode = initialDisplay,
            installedVariant = douyinLauncher.detectInstalledVariant(),
            statusHint = modeHintText(loginMode),
        )

        webView = KeepAliveLoginWebView(this)
        DesktopWebViewFactory.configureDesktopWebView(
            webView = webView,
            blockDeepLinks = true,
            onPageFinished = {
                val elapsed = if (loadStartedAtEpochMs > 0L) {
                    System.currentTimeMillis() - loadStartedAtEpochMs
                } else {
                    -1L
                }
                Log.i(TAG, "PAGE_FINISHED elapsedMs=$elapsed hasQr=${screenState.qrBitmap != null}")
                if (screenState.qrBitmap == null &&
                    screenState.stage == QrVisualStage.CONNECTING
                ) {
                    screenState = screenState.copy(stage = QrVisualStage.GENERATING_QR)
                }
                if (!handlingLoginSuccess && screenState.accountSwitchPrompt == null) {
                    triggerSingleLoginProbe(userInitiated = false)
                }
            },
            onMainFrameError = { _, _ ->
                if (screenState.qrBitmap == null) {
                    screenState = screenState.copy(
                        stage = QrVisualStage.LOAD_TIMEOUT,
                        statusHint = "网络连接异常，请检查网络后重试",
                    )
                }
            },
            documentStartScript = DouyinJsScripts.buildLoginCaptureDocumentStartJs(),
            profileName = profileName,
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    if (screenState.qrBitmap == null && newProgress != screenState.loadProgressPercent) {
                        screenState = screenState.copy(loadProgressPercent = newProgress.coerceIn(0, 100))
                    }
                }
            },
            interceptSubresource = ::interceptNativeQrSubresource,
        )
        webView.settings.blockNetworkImage = allowSubresourceFilter
        webView.addJavascriptInterface(FlameLoginJsBridge(), DouyinJsScripts.DEFAULT_BRIDGE_NAME)

        val themeStore = (application as? FlameApplication)?.container?.themePreferencesStore
        setContent {
            val preference by (themeStore?.preference ?: flowOf(ThemePreference()))
                .collectAsState(initial = ThemePreference())
            val isDark = ThemePreferencesStore.resolveIsDark(
                mode = preference.mode,
                systemDark = isSystemInDarkTheme(),
            )
            DisposableEffect(isDark) {
                val barStyle = if (isDark) {
                    SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                } else {
                    SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                onDispose {}
            }

            FlameTheme(preference = preference) {
                LoginScreen(
                    state = screenState,
                    webView = webView,
                    snackbarHostState = snackbarHostState,
                    onBack = { finish() },
                    onToggleDisplayMode = ::toggleDisplayMode,
                    onRefreshQr = ::handleRefreshQrClick,
                    onLaunchDouyinConfirm = ::handleLaunchDouyinConfirm,
                    onSaveQrAndScan = ::handleSaveQrAndScanClick,
                    onOpenSmsWebLogin = ::handleOpenSmsWebLogin,
                    onProbeLoginNow = { triggerSingleLoginProbe(userInitiated = true) },
                    onClearCacheAndReload = ::handleClearCacheAndReload,
                    onConfirmAccountSwitch = ::applyAccountSwitchDecision,
                )
            }
        }

        startCountdownTicker()
        bootstrapLoginSession()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        pendingFallbackNotifyJob?.cancel()
        pendingFallbackNotifyJob = null
        notifiedBackgroundQrConfirmed = false
        notifiedBackgroundVerification = false
        disarmBackgroundKeepAlive()
        LoginKeepAliveService.stop(this)
        LoginReturnHelper.cancelReturnNotification(this)
        if (::webView.isInitialized) {
            runCatching {
                webView.onResume()
                webView.resumeTimers()
            }
        }
        if (::douyinLauncher.isInitialized) {
            val resetJump = screenState.stage != QrVisualStage.CONFIRMED
            screenState = screenState.copy(
                installedVariant = douyinLauncher.detectInstalledVariant(),
                jumpedToDouyin = if (resetJump) false else screenState.jumpedToDouyin,
            )
        }
        if (mutexSession != null && !handlingLoginSuccess && screenState.accountSwitchPrompt == null) {
            fastPollUntilEpochMs = System.currentTimeMillis() + FAST_RESUME_WINDOW_MS
            triggerSingleLoginProbe(userInitiated = false)
        }
        dispatchDeferredActionsAfterResume()
    }

    private fun dispatchDeferredActionsAfterResume() {
        val pendingLaunch = if (pendingExternalLaunchReadyForResume) {
            pendingExternalLaunchReadyForResume = false
            val action = pendingExternalLaunchAction
            pendingExternalLaunchAction = null
            action
        } else {
            null
        }
        val shouldSave = if (pendingSaveReadyForResume) {
            pendingSaveReadyForResume = false
            true
        } else {
            false
        }
        if (pendingLaunch == null && !shouldSave) return

        // 等当前 onResume 与系统权限窗关闭完全落稳后再拉起外部应用和前台保活服务，
        // 避免 ActivityResult 回调早于 onResume() 触发 FGS 启停竞态或被 MIUI 拦截。
        window.decorView.post {
            if (isFinishing || isDestroyed || !isActivityResumed) return@post
            if (pendingLaunch != null) {
                pendingLaunch.invoke()
            } else if (shouldSave) {
                performSaveQrAndScan()
            }
        }
    }

    private fun armBackgroundKeepAlive() {
        backgroundKeepAliveJob?.cancel()
        fastPollUntilEpochMs = System.currentTimeMillis() + BG_KEEP_ALIVE_TIMEOUT_MS
        if (::webView.isInitialized) {
            webView.setKeepAliveInBackground(true)
            runCatching {
                webView.evaluateJavascript("window.__flameKeepAliveActive=true;", null)
            }
        }
        Log.i(TAG, "BG_KEEP_ALIVE_ARMED")
        backgroundKeepAliveJob = lifecycleScope.launch {
            delay(BG_KEEP_ALIVE_TIMEOUT_MS)
            if (::webView.isInitialized && !isActivityResumed) {
                webView.setKeepAliveInBackground(false)
                runCatching {
                    webView.evaluateJavascript("window.__flameKeepAliveActive=false;", null)
                }
                Log.i(TAG, "BG_KEEP_ALIVE_TIMEOUT_DISARMED")
            }
        }
    }

    private fun disarmBackgroundKeepAlive() {
        backgroundKeepAliveJob?.cancel()
        backgroundKeepAliveJob = null
        if (::webView.isInitialized) {
            webView.setKeepAliveInBackground(false)
            runCatching {
                webView.evaluateJavascript("window.__flameKeepAliveActive=false;", null)
            }
        }
    }

    override fun onPause() {
        isActivityResumed = false
        try {
            WebViewProfileRegistry.cookieManagerForProfileName(profileName).flush()
        } catch (_: Throwable) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        pollJob = null
        tickerJob?.cancel()
        tickerJob = null
        refreshWatchdogJob?.cancel()
        refreshWatchdogJob = null
        pendingFallbackNotifyJob?.cancel()
        pendingFallbackNotifyJob = null
        disarmBackgroundKeepAlive()

        LoginKeepAliveService.stop(this)
        if (::albumSaver.isInitialized) {
            val saver = albumSaver
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO + NonCancellable) {
                saver.cleanupAll(markDisposed = true)
            }
        }

        ExecutionMutex.releaseSession(mutexSession)
        mutexSession = null
        if (::webView.isInitialized) {
            try {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.stopLoading()
                webView.destroy()
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }

    private fun bootstrapLoginSession() {
        // 并发读取账号基础信息，不阻塞 WebView 立刻发起首帧网络加载
        lifecycleScope.launch {
            val acc = withContext(Dispatchers.IO) {
                database.accountDao().getAccount(accountId)
            }
            if (!isActive || isFinishing || isDestroyed) return@launch
            initialAccountStatus = acc?.status ?: AccountStatus.PENDING_LOGIN
            if (acc != null && acc.displayName.isNotBlank()) {
                screenState = screenState.copy(accountDisplayName = acc.displayName)
            }
        }

        lifecycleScope.launch {
            val scopeKey = ExecutionMutex.scopeKeyForAccount(accountId)
            var token: ExecutionMutex.SessionToken? = ExecutionMutex.tryAcquireSession(
                owner = MUTEX_OWNER,
                scopeKey = scopeKey,
            )
            while (token == null && isActive) {
                screenState = screenState.copy(statusHint = "当前账号正在后台续火或扫描，等待完成后自动加载…")
                token = ExecutionMutex.acquireSession(
                    owner = MUTEX_OWNER,
                    timeoutMs = 2_000L,
                    scopeKey = scopeKey,
                )
            }
            if (!isActive || isFinishing || isDestroyed) {
                ExecutionMutex.releaseSession(token)
                return@launch
            }
            mutexSession = token

            val chatUrl = selectorStore.current().chatUrl
            if (loginMode == LoginMode.CLEAR_AND_RELOGIN) {
                screenState = screenState.copy(statusHint = "正在清理旧登录态并请求新授权码…")
                // 保留静态 JS/CSS 磁盘缓存，仅清理 Cookie 与站点存储，避免每次重登重新拉取数 MB 脚本
                clearCookiesAndWebStorage(clearDiskCache = false) {
                    if (!isFinishing && !isDestroyed) {
                        beginChatLoad(chatUrl)
                        startPollingLoop()
                    }
                }
            } else {
                beginChatLoad(chatUrl)
                startPollingLoop()
            }
        }
    }

    private fun beginChatLoad(chatUrl: String) {
        val nowMs = System.currentTimeMillis()
        loadStartedAtEpochMs = nowMs
        qrExpireAtEpochMs = 0L
        currentQrBase64 = null
        notifiedBackgroundQrConfirmed = false
        blockedSubresourceCount.set(0)
        stubbedFeedCount.set(0)
        syncSubresourceFilterFlag(screenState.displayMode, screenState.verificationRequired)
        Log.i(TAG, "LOAD_BEGIN mode=$loginMode filter=$allowSubresourceFilter")
        screenState = screenState.copy(
            stage = QrVisualStage.CONNECTING,
            qrBitmap = null,
            validatedIndexUrl = null,
            remainingSeconds = 0,
            loadProgressPercent = 5,
            isRefreshingQr = false,
            jumpedToDouyin = false,
            statusHint = modeHintText(loginMode),
        )
        webView.loadUrl(chatUrl)
    }

    private fun syncSubresourceFilterFlag(
        displayMode: LoginDisplayMode,
        verificationRequired: Boolean,
    ) {
        val enabled = displayMode == LoginDisplayMode.NATIVE_QR && !verificationRequired
        allowSubresourceFilter = enabled
        if (::webView.isInitialized) {
            runCatching {
                webView.settings.blockNetworkImage = enabled
            }
        }
    }

    private fun interceptNativeQrSubresource(request: WebResourceRequest): WebResourceResponse? {
        if (!allowSubresourceFilter) return null
        val headers = request.requestHeaders
        val hasRange = headers?.keys?.any { it.equals("Range", ignoreCase = true) } == true
        val action = LoginResourceFilter.classifySubresource(
            rawUrl = request.url?.toString(),
            isForMainFrame = request.isForMainFrame,
            hasRangeHeader = hasRange,
        )
        if (action == LoginSubresourceAction.ALLOW) return null

        val origin = headers?.entries
            ?.firstOrNull { it.key.equals("Origin", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
        val responseHeaders = buildMap {
            put("Access-Control-Allow-Origin", origin ?: "*")
            if (origin != null) {
                put("Access-Control-Allow-Credentials", "true")
            }
            put("Cache-Control", "no-store")
        }

        return when (action) {
            LoginSubresourceAction.STUB_EMPTY_FEED -> {
                stubbedFeedCount.incrementAndGet()
                val body = """{"status_code":0,"aweme_list":[],"has_more":0}""".toByteArray(Charsets.UTF_8)
                WebResourceResponse(
                    "application/json",
                    "utf-8",
                    200,
                    "OK",
                    responseHeaders,
                    ByteArrayInputStream(body),
                )
            }

            LoginSubresourceAction.BLOCK_EMPTY -> {
                blockedSubresourceCount.incrementAndGet()
                WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    204,
                    "No Content",
                    responseHeaders,
                    ByteArrayInputStream(ByteArray(0)),
                )
            }

            LoginSubresourceAction.ALLOW -> null
        }
    }

    private fun startCountdownTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            while (isActive && !isFinishing) {
                delay(1_000L)
                val nowMs = System.currentTimeMillis()
                val cur = screenState
                if (cur.qrBitmap != null && qrExpireAtEpochMs > 0L) {
                    val rem = ((qrExpireAtEpochMs - nowMs) / 1_000L).coerceAtLeast(0L).toInt()
                    val nextStage = if (rem == 0 && cur.stage in setOf(QrVisualStage.READY, QrVisualStage.SCANNED)) {
                        QrVisualStage.EXPIRED
                    } else {
                        cur.stage
                    }
                    if (rem != cur.remainingSeconds || nextStage != cur.stage) {
                        screenState = cur.copy(
                            remainingSeconds = rem,
                            stage = nextStage,
                        )
                    }
                } else if (
                    LoginSessionReducer.shouldTriggerQrLoadTimeout(
                        displayMode = cur.displayMode,
                        stage = cur.stage,
                        hasQrBitmap = cur.qrBitmap != null,
                        verificationRequired = cur.verificationRequired,
                        inspectedHealthyOnce = inspectedHealthyOnce,
                        loadStartedAtEpochMs = loadStartedAtEpochMs,
                        nowEpochMs = nowMs,
                    )
                ) {
                    screenState = cur.copy(
                        stage = QrVisualStage.LOAD_TIMEOUT,
                        isRefreshingQr = false,
                        statusHint = "授权码加载超时，可点击重试或切换网页模式查看",
                    )
                }
            }
        }
    }

    private fun startPollingLoop() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && !isFinishing) {
                val interval = if (System.currentTimeMillis() < fastPollUntilEpochMs) {
                    FAST_POLL_INTERVAL_MS
                } else {
                    POLL_INTERVAL_MS
                }
                delay(interval)
                if (!handlingLoginSuccess && screenState.accountSwitchPrompt == null && mutexSession != null) {
                    evaluateLoginProbe(userInitiated = false)
                }
            }
        }
    }

    private fun triggerSingleLoginProbe(userInitiated: Boolean) {
        lifecycleScope.launch {
            if (!handlingLoginSuccess && screenState.accountSwitchPrompt == null && mutexSession != null) {
                evaluateLoginProbe(userInitiated = userInitiated)
            }
        }
    }

    private fun toggleDisplayMode() {
        val next = if (screenState.displayMode == LoginDisplayMode.NATIVE_QR) {
            LoginDisplayMode.WEB_FALLBACK
        } else {
            LoginDisplayMode.NATIVE_QR
        }
        syncSubresourceFilterFlag(next, screenState.verificationRequired)
        screenState = screenState.copy(displayMode = next)
    }

    private fun handleOpenSmsWebLogin() {
        syncSubresourceFilterFlag(LoginDisplayMode.WEB_FALLBACK, screenState.verificationRequired)
        screenState = screenState.copy(
            displayMode = LoginDisplayMode.WEB_FALLBACK,
            statusHint = "请在下方弹窗选择「验证码登录」输入手机号完成登录",
        )
        lifecycleScope.launch {
            evaluateJsOnMain(DouyinJsScripts.buildSwitchToSmsLoginTabJs())
        }
    }

    private fun handleRefreshQrClick() {
        if (screenState.accountSwitchPrompt != null) return
        val nextSpin = screenState.refreshSpinCount + 1
        val chatUrl = selectorStore.current().chatUrl

        if (screenState.displayMode == LoginDisplayMode.WEB_FALLBACK || screenState.qrBitmap == null) {
            screenState = screenState.copy(refreshSpinCount = nextSpin)
            beginChatLoad(chatUrl)
            return
        }

        val previousB64 = currentQrBase64
        screenState = screenState.copy(
            isRefreshingQr = true,
            validatedIndexUrl = null,
            jumpedToDouyin = false,
            refreshSpinCount = nextSpin,
        )
        refreshWatchdogJob?.cancel()
        refreshWatchdogJob = lifecycleScope.launch {
            val raw = evaluateJsOnMain(DouyinJsScripts.buildRefreshLoginQrCodeJs())
            @Suppress("UNCHECKED_CAST")
            val res = ExecJson.unwrapEvaluateResult(raw) as? Map<String, Any?>
            val clicked = res?.boolOrDefault("clicked", false) == true
            if (!clicked) {
                beginChatLoad(chatUrl)
                return@launch
            }
            delay(FAST_REFRESH_WAIT_MS)
            if (currentQrBase64 == previousB64 && screenState.isRefreshingQr) {
                beginChatLoad(chatUrl)
            } else {
                screenState = screenState.copy(isRefreshingQr = false)
            }
        }
    }

    private fun handleClearCacheAndReload() {
        if (screenState.accountSwitchPrompt != null || loginMode == LoginMode.INSPECT_OR_VERIFY) return
        screenState = screenState.copy(statusHint = "正在清理缓存并重新获取授权码…")
        clearCookiesAndWebStorage(clearDiskCache = true) {
            if (!isFinishing && !isDestroyed) {
                beginChatLoad(selectorStore.current().chatUrl)
            }
        }
    }

    private fun runWithOptionalNotificationPrompt(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !returnHelper.hasNotificationPermission() &&
            !askedNotificationPermissionOnce
        ) {
            askedNotificationPermissionOnce = true
            pendingExternalLaunchAction = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        action()
    }

    private fun handleLaunchDouyinConfirm() {
        val variant = screenState.installedVariant ?: douyinLauncher.detectInstalledVariant()
        val indexUrl = screenState.validatedIndexUrl
        if (variant == null || indexUrl.isNullOrBlank()) {
            showSnackbar("当前无法直接唤起抖音确认页，请尝试「存码去扫一扫」")
            return
        }
        runWithOptionalNotificationPrompt {
            armBackgroundKeepAlive()
            val launched = douyinLauncher.launchConfirmLogin(variant, indexUrl)
            if (launched) {
                LoginKeepAliveService.start(this, accountId = accountId, modeName = loginMode.name)
                screenState = screenState.copy(jumpedToDouyin = true)
            } else {
                disarmBackgroundKeepAlive()
                screenState = screenState.copy(validatedIndexUrl = null)
                showSnackbar("未能拉起${variant.displayName}确认页，请点击「保存二维码并打开扫一扫」")
            }
        }
    }

    private fun handleSaveQrAndScanClick() {
        if (screenState.isSavingQr || currentQrBase64.isNullOrBlank()) return
        runWithOptionalNotificationPrompt {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !albumSaver.hasLegacyWritePermission()) {
                pendingSaveAfterPermission = true
                writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return@runWithOptionalNotificationPrompt
            }
            performSaveQrAndScan()
        }
    }

    private fun performSaveQrAndScan() {
        val b64 = currentQrBase64 ?: return
        val variant = screenState.installedVariant ?: douyinLauncher.detectInstalledVariant()
        screenState = screenState.copy(isSavingQr = true)
        lifecycleScope.launch {
            val outcome = albumSaver.savePaddedQrForScan(b64)
            screenState = screenState.copy(isSavingQr = false)

            if (outcome.savedToAlbum) {
                if (variant != null) {
                    armBackgroundKeepAlive()
                    val opened = douyinLauncher.launchScanCamera(variant)
                    if (opened) {
                        LoginKeepAliveService.start(
                            this@LoginWebViewActivity,
                            accountId = accountId,
                            modeName = loginMode.name,
                        )
                        screenState = screenState.copy(jumpedToDouyin = true)
                        showSnackbar("已存入相册首张，请在${variant.displayName}扫一扫右下角点「相册」选取")
                    } else {
                        disarmBackgroundKeepAlive()
                        showSnackbar("二维码已存入相册，请打开抖音「扫一扫 → 相册」选取第一张图")
                    }
                } else {
                    showSnackbar("高清二维码已保存到系统相册（登录退出后将自动清理）")
                }
                return@launch
            }

            if (outcome.shareUri != null) {
                openSystemShareForQr(outcome.shareUri)
                return@launch
            }

            showSnackbar(outcome.errorMessage ?: "保存二维码失败，请改用手机号验证码登录")
        }
    }

    private fun openSystemShareForQr(shareUri: Uri) {
        runCatching {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, "发送到抖音或保存二维码"))
        }.onFailure {
            showSnackbar("无法拉起系统分享，请改用网页或短信验证码登录")
        }
    }

    private fun showSnackbar(message: String) {
        lifecycleScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    private suspend fun applyCapturedQrPayload(
        base64Png: String,
        validatedIndexUrl: String?,
        expireAtEpochMs: Long,
        fromAuthoritativeNetwork: Boolean,
    ) {
        val imageChanged = currentQrBase64 != base64Png || screenState.qrBitmap == null
        val nextIndexUrl = LoginSessionReducer.resolveUpdatedIndexUrl(
            incomingUrl = validatedIndexUrl,
            previousUrl = screenState.validatedIndexUrl,
            fromAuthoritativeNetwork = fromAuthoritativeNetwork,
            imageChanged = imageChanged,
        )
        if (!imageChanged) {
            if (nextIndexUrl != screenState.validatedIndexUrl) {
                screenState = screenState.copy(validatedIndexUrl = nextIndexUrl)
            }
            return
        }

        val bitmap = albumSaver.decodeBitmapForPreview(base64Png) ?: return
        currentQrBase64 = base64Png
        notifiedBackgroundQrConfirmed = false
        qrExpireAtEpochMs = expireAtEpochMs
        val remSec = ((expireAtEpochMs - System.currentTimeMillis()) / 1_000L)
            .coerceAtLeast(0L)
            .toInt()
        val elapsedMs = if (loadStartedAtEpochMs > 0L) {
            System.currentTimeMillis() - loadStartedAtEpochMs
        } else {
            -1L
        }
        Log.i(
            TAG,
            "QR_READY source=${if (fromAuthoritativeNetwork) "net" else "dom"} elapsedMs=$elapsedMs hasIndexUrl=${nextIndexUrl != null} blocked=${blockedSubresourceCount.get()} stubbedFeeds=${stubbedFeedCount.get()}",
        )
        refreshWatchdogJob?.cancel()
        screenState = screenState.copy(
            stage = QrVisualStage.READY,
            qrBitmap = bitmap.asImageBitmap(),
            validatedIndexUrl = nextIndexUrl,
            remainingSeconds = remSec,
            isRefreshingQr = false,
            jumpedToDouyin = false,
            installedVariant = douyinLauncher.detectInstalledVariant(),
        )
    }

    private suspend fun evaluateLoginProbe(userInitiated: Boolean) {
        val bundle = selectorStore.current()
        val raw = evaluateJsOnMain(DouyinJsScripts.buildAuthAndSelfIdentityProbeJs(bundle)) ?: return
        @Suppress("UNCHECKED_CAST")
        val probe = ExecJson.unwrapEvaluateResult(raw) as? Map<String, Any?> ?: return

        val verificationRequired = probe.boolOrDefault("verificationRequired", false)
        if (verificationRequired) {
            if (!screenState.verificationRequired || screenState.displayMode != LoginDisplayMode.WEB_FALLBACK) {
                syncSubresourceFilterFlag(LoginDisplayMode.WEB_FALLBACK, verificationRequired = true)
                screenState = screenState.copy(
                    verificationRequired = true,
                    displayMode = LoginDisplayMode.WEB_FALLBACK,
                    statusHint = "检测到安全验证弹窗，请在下方网页完成身份验证",
                )
            }
            if (LoginSessionReducer.shouldReturnToForegroundFromBackground(isActivityResumed) &&
                !notifiedBackgroundVerification
            ) {
                notifiedBackgroundVerification = true
                returnHelper.bringAppToFrontAndNotify(
                    event = LoginReturnEvent.VERIFICATION_REQUIRED,
                    accountId = accountId,
                    loginMode = loginMode,
                )
            }
            return
        } else if (screenState.verificationRequired) {
            notifiedBackgroundVerification = false
            syncSubresourceFilterFlag(screenState.displayMode, verificationRequired = false)
            screenState = screenState.copy(verificationRequired = false)
        }

        val loggedIn = probe.boolOrDefault("loggedIn", false)
        if (!loggedIn) {
            val domDataUrl = probe.stringOrNull("qrImageDataUrl")
            val domB64 = LoginQrParser.stripBase64ImagePrefix(domDataUrl)
            val domExpired = probe.boolOrDefault("qrDomExpired", false)
            val hasSignal = probe.boolOrDefault("hasQrOrLoginSignal", false)

            if (domB64 != null && screenState.qrBitmap == null) {
                applyCapturedQrPayload(
                    base64Png = domB64,
                    validatedIndexUrl = null,
                    expireAtEpochMs = System.currentTimeMillis() + LoginQrParser.DEFAULT_QR_TTL_MS,
                    fromAuthoritativeNetwork = false,
                )
            } else if (hasSignal &&
                screenState.qrBitmap == null &&
                screenState.stage == QrVisualStage.CONNECTING
            ) {
                screenState = screenState.copy(stage = QrVisualStage.GENERATING_QR)
            }

            if (LoginSessionReducer.shouldMarkDomExpired(
                    domExpired = domExpired,
                    hasQrBitmap = screenState.qrBitmap != null,
                    currentStage = screenState.stage,
                )
            ) {
                screenState = screenState.copy(stage = QrVisualStage.EXPIRED, remainingSeconds = 0)
            }

            if (userInitiated) {
                showSnackbar("尚未检测到已登录会话列表，请完成授权或短信验证后再试")
            }
            return
        }

        val newUid = probe.stringOrNull("selfUid")
        val newNick = probe.stringOrNull("douyinNickname")
        if (newUid == null && newNick == null && identityHydrationRetries < 2) {
            identityHydrationRetries += 1
            return
        }

        handlingLoginSuccess = true
        Log.i(TAG, "LOGIN_DETECTED resumed=$isActivityResumed mode=$loginMode")
        try {
            handleLoggedInDetected(
                newUid = newUid,
                newNick = newNick,
                userInitiated = userInitiated,
            )
        } finally {
            if (!isFinishing && screenState.accountSwitchPrompt == null) {
                handlingLoginSuccess = false
            }
        }
    }

    private suspend fun handleLoggedInDetected(
        newUid: String?,
        newNick: String?,
        userInitiated: Boolean,
    ) {
        val nowMs = System.currentTimeMillis()
        val (account, hasTargets) = withContext(Dispatchers.IO) {
            val acc = database.accountDao().getAccount(accountId)
            val count = database.targetDao().countActiveTargets(accountId)
            acc to (count > 0)
        }
        if (account == null) {
            cleanupBeforeFinish()
            setResult(RESULT_OK)
            finish()
            return
        }

        val switchDecision = AccountSwitchGuard.checkLoginIdentity(
            prevUid = account.selfUid,
            newUid = newUid,
            prevNick = account.douyinNickname,
            newNick = newNick,
            hasTargets = hasTargets,
        )

        if (switchDecision == SwitchDecision.REQUIRE_USER_CHOICE) {
            withContext(Dispatchers.IO) {
                database.accountDao().update(
                    account.copy(
                        status = AccountStatus.PAUSED,
                        updatedAt = nowMs,
                    ),
                )
                ScheduleCoordinator(this@LoginWebViewActivity, database).cancelAllAlarms()
            }
            ExecutionRuntimeBus.update {
                it.copy(
                    needsLoginChoicePrompt = true,
                    lastMessage = "检测到更换了抖音账号，已暂停调度等待用户选择处理方式",
                    lastUpdatedAtEpochMs = nowMs,
                )
            }
            screenState = screenState.copy(
                accountSwitchPrompt = AccountSwitchPromptState(newUid = newUid, newNick = newNick),
            )
            if (LoginSessionReducer.shouldReturnToForegroundFromBackground(isActivityResumed)) {
                returnHelper.bringAppToFrontAndNotify(
                    event = LoginReturnEvent.ACCOUNT_SWITCH_CONFIRM,
                    accountId = accountId,
                    loginMode = loginMode,
                )
            }
            return
        }

        val shouldAutoFinish = LoginSessionReducer.shouldAutoFinishOnAuthenticated(
            loginMode = loginMode,
            initialStatus = initialAccountStatus,
            userInitiated = userInitiated,
        )
        if (!shouldAutoFinish) {
            loadStartedAtEpochMs = 0L
            if (!inspectedHealthyOnce) {
                inspectedHealthyOnce = true
                val syncedName = withContext(Dispatchers.IO) {
                    val otherNames = database.accountDao().listAll()
                        .filter { it.id != accountId }
                        .map { it.displayName }
                    val nextDisplayName = AccountManager.resolveSyncedDisplayNameAfterLogin(
                        currentDisplayName = account.displayName,
                        newDouyinNickname = newNick,
                        isSwitchedDouyinAccount = false,
                        otherAccountDisplayNames = otherNames,
                    )
                    database.accountDao().update(
                        account.copy(
                            displayName = nextDisplayName,
                            selfUid = newUid ?: account.selfUid,
                            douyinNickname = newNick ?: account.douyinNickname,
                            lastVerifiedAt = nowMs,
                            updatedAt = nowMs,
                        ),
                    )
                    nextDisplayName
                }
                screenState = screenState.copy(accountDisplayName = syncedName)
            }
            screenState = screenState.copy(
                statusHint = "排查模式：已保持登录态，可浏览核对聊天列表，或点右上角「确认登录态」退出",
            )
            return
        }

        screenState = screenState.copy(stage = QrVisualStage.CONFIRMED)
        val isSwitchedAccount = AccountSwitchGuard.checkLoginIdentity(
            prevUid = account.selfUid,
            newUid = newUid,
            prevNick = account.douyinNickname,
            newNick = newNick,
            hasTargets = true,
        ) == SwitchDecision.REQUIRE_USER_CHOICE
        withContext(Dispatchers.IO) {
            val otherNames = database.accountDao().listAll()
                .filter { it.id != accountId }
                .map { it.displayName }
            val nextDisplayName = AccountManager.resolveSyncedDisplayNameAfterLogin(
                currentDisplayName = account.displayName,
                newDouyinNickname = newNick,
                isSwitchedDouyinAccount = isSwitchedAccount,
                otherAccountDisplayNames = otherNames,
            )
            val updatedIdentity = account.copy(
                displayName = nextDisplayName,
                selfUid = newUid ?: account.selfUid,
                douyinNickname = newNick ?: account.douyinNickname,
                lastVerifiedAt = nowMs,
            )
            val transition = AccountHealthReducer.onAuthProbeResult(
                account = updatedIdentity,
                outcome = AuthProbeOutcome.AUTHENTICATED,
                nowEpochMs = nowMs,
            )
            database.accountDao().update(transition.account)
            ScheduleCoordinator(this@LoginWebViewActivity, database).rescheduleAll()
        }

        try {
            WebViewProfileRegistry.cookieManagerForProfileName(profileName).flush()
        } catch (_: Throwable) {
        }
        ExecutionRuntimeBus.update {
            it.copy(
                needsLoginChoicePrompt = false,
                lastMessage = "登录状态已确认有效，自动调度已就绪",
                lastUpdatedAtEpochMs = nowMs,
            )
        }
        if (LoginSessionReducer.shouldReturnToForegroundFromBackground(isActivityResumed)) {
            returnHelper.bringAppToFrontAndNotify(
                event = LoginReturnEvent.LOGIN_SUCCEEDED,
                accountId = accountId,
                loginMode = loginMode,
            )
        }
        cleanupBeforeFinish()
        setResult(RESULT_OK)
        finish()
    }

    private fun applyAccountSwitchDecision(wipeAndRescan: Boolean) {
        val prompt = screenState.accountSwitchPrompt ?: return
        if (screenState.isApplyingSwitchDecision) return
        screenState = screenState.copy(isApplyingSwitchDecision = true)

        lifecycleScope.launch {
            try {
                val nowMs = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    database.withTransaction {
                        val allAccounts = database.accountDao().listAll()
                        val acc = allAccounts.firstOrNull { it.id == accountId } ?: return@withTransaction
                        val otherNames = allAccounts
                            .filter { it.id != accountId }
                            .map { it.displayName }
                        val syncedDisplayName = AccountManager.resolveSyncedDisplayNameAfterLogin(
                            currentDisplayName = acc.displayName,
                            newDouyinNickname = prompt.newNick,
                            isSwitchedDouyinAccount = true,
                            otherAccountDisplayNames = otherNames,
                        )
                        if (wipeAndRescan) {
                            database.sendJobDao().deleteAllByAccount(accountId)
                            database.targetDao().deleteAllByAccount(accountId)
                            val resetAcc = acc.copy(
                                displayName = syncedDisplayName,
                                status = AccountStatus.HEALTHY,
                                authFailCount = 0,
                                failureStreak = 0,
                                selfUid = prompt.newUid,
                                douyinNickname = prompt.newNick,
                                lastVerifiedAt = nowMs,
                                updatedAt = nowMs,
                            )
                            database.accountDao().update(resetAcc)
                        } else {
                            database.targetDao().unbindIdentitiesAndDisableAll(accountId, nowMs)
                            val pausedAcc = acc.copy(
                                displayName = syncedDisplayName,
                                status = AccountStatus.PAUSED,
                                authFailCount = 0,
                                selfUid = prompt.newUid,
                                douyinNickname = prompt.newNick,
                                lastVerifiedAt = nowMs,
                                updatedAt = nowMs,
                            )
                            database.accountDao().update(pausedAcc)
                        }
                    }

                    val coordinator = ScheduleCoordinator(this@LoginWebViewActivity, database)
                    if (wipeAndRescan) {
                        coordinator.rescheduleAll()
                    } else {
                        coordinator.cancelAllAlarms()
                    }
                }

                try {
                    OfficialSparkLitStore.globalOrNull()?.clearForAccount(accountId)
                } catch (_: Throwable) {
                }
                try {
                    WebViewProfileRegistry.cookieManagerForProfileName(profileName).flush()
                } catch (_: Throwable) {
                }
                ExecutionRuntimeBus.update {
                    it.copy(
                        needsLoginChoicePrompt = false,
                        latestScanSnapshot = if (wipeAndRescan) null else it.latestScanSnapshot,
                        lastMessage = if (wipeAndRescan) {
                            "已清空旧号名单并切换为新账号，正在启动会话扫描"
                        } else {
                            "已保留好友名单并解绑全部 ID，账号已暂停等待人工核对"
                        },
                        lastUpdatedAtEpochMs = nowMs,
                    )
                }

                cleanupBeforeFinish()
                ExecutionMutex.releaseSession(mutexSession)
                mutexSession = null
                if (wipeAndRescan) {
                    ExecutionForegroundService.enqueueManualScan(this@LoginWebViewActivity, accountId)
                }

                setResult(RESULT_OK)
                finish()
            } finally {
                if (!isFinishing && !isDestroyed) {
                    screenState = screenState.copy(isApplyingSwitchDecision = false)
                }
            }
        }
    }

    private suspend fun cleanupBeforeFinish() {
        disarmBackgroundKeepAlive()
        LoginKeepAliveService.stop(this)
        albumSaver.cleanupAll()
    }

    private fun clearCookiesAndWebStorage(
        clearDiskCache: Boolean,
        onCleared: () -> Unit,
    ) {
        lifecycleScope.launch {
            val accountCount = withContext(Dispatchers.IO) {
                try {
                    database.accountDao().listAll().size
                } catch (_: Throwable) {
                    Int.MAX_VALUE
                }
            }
            if (isFinishing || isDestroyed) return@launch
            val allowDeleteWebStorage = shouldDeleteGlobalWebStorage(
                multiProfileSupported = WebViewProfileRegistry.isMultiProfileSupported(),
                accountCount = accountCount,
                accountId = accountId,
            )
            WebViewProfileRegistry.clearAccountCookies(accountId) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (allowDeleteWebStorage) {
                        try {
                            WebStorage.getInstance().deleteAllData()
                        } catch (_: Throwable) {
                        }
                    }
                    try {
                        if (clearDiskCache) {
                            webView.clearCache(true)
                        }
                        webView.clearFormData()
                        webView.clearHistory()
                    } catch (_: Throwable) {
                    }
                    onCleared()
                }
            }
        }
    }

    private suspend fun evaluateJsOnMain(script: String): String? =
        withTimeoutOrNull(EVALUATE_JS_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    try {
                        webView.evaluateJavascript(script) { value ->
                            if (cont.isActive) cont.resume(value)
                        }
                    } catch (_: Throwable) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }

    private fun modeHintText(mode: LoginMode): String = when (mode) {
        LoginMode.CLEAR_AND_RELOGIN -> "已清理旧登录态，请授权登录新抖音账号"
        LoginMode.PRESERVE_SESSION -> "支持一键跳转本机抖音确认登录，或使用另一台设备扫码"
        LoginMode.INSPECT_OR_VERIFY -> "保留登录态排查 / 安全验证模式"
    }

    private fun parseLoginMode(raw: String?): LoginMode =
        LoginMode.entries.firstOrNull { it.name == raw } ?: LoginMode.PRESERVE_SESSION

    private inner class FlameLoginJsBridge {
        @JavascriptInterface
        fun onQrCodePayload(rawJson: String?) {
            val nowMs = System.currentTimeMillis()
            val payload = LoginQrParser.parseGetQrCodeJson(rawJson, nowMs) ?: return
            lifecycleScope.launch(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    applyCapturedQrPayload(
                        base64Png = payload.base64Png,
                        validatedIndexUrl = payload.validatedIndexUrl,
                        expireAtEpochMs = payload.expireAtEpochMs,
                        fromAuthoritativeNetwork = true,
                    )
                }
            }
        }

        @JavascriptInterface
        fun onQrStatusPayload(rawJson: String?) {
            val status = LoginQrParser.parseQrConnectJson(rawJson)
            if (status == QrConnectStatus.UNKNOWN) return
            lifecycleScope.launch(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@launch
                when (status) {
                    QrConnectStatus.NEW -> Unit
                    QrConnectStatus.SCANNED -> {
                        Log.i(TAG, "QR_STATUS_SCANNED resumed=$isActivityResumed")
                        if (screenState.qrBitmap != null && screenState.stage != QrVisualStage.CONFIRMED) {
                            screenState = screenState.copy(stage = QrVisualStage.SCANNED)
                        }
                    }

                    QrConnectStatus.CONFIRMED -> {
                        Log.i(TAG, "QR_STATUS_CONFIRMED resumed=$isActivityResumed")
                        screenState = screenState.copy(stage = QrVisualStage.CONFIRMED)
                        fastPollUntilEpochMs = System.currentTimeMillis() + CONFIRM_POLL_WINDOW_MS
                        if (LoginSessionReducer.shouldReturnToForegroundFromBackground(isActivityResumed) &&
                            !notifiedBackgroundQrConfirmed
                        ) {
                            notifiedBackgroundQrConfirmed = true
                            // 立刻直拉前台；仅当 550ms 后仍未回到前台（未授予悬浮窗或被厂商拦截）时再发横幅兜底，
                            // 避免自动回跳成功时出现多余震动与横幅闪烁。
                            returnHelper.bringAppToFront(
                                event = LoginReturnEvent.QR_CONFIRMED,
                                accountId = accountId,
                                loginMode = loginMode,
                            )
                            pendingFallbackNotifyJob?.cancel()
                            pendingFallbackNotifyJob = lifecycleScope.launch {
                                delay(FALLBACK_NOTIFY_GRACE_MS)
                                if (!isActivityResumed && !isFinishing && !isDestroyed) {
                                    returnHelper.postReturnNotification(
                                        event = LoginReturnEvent.QR_CONFIRMED,
                                        accountId = accountId,
                                        loginMode = loginMode,
                                    )
                                }
                            }
                        }
                        triggerSingleLoginProbe(userInitiated = false)
                    }

                    QrConnectStatus.EXPIRED -> {
                        if (screenState.qrBitmap != null) {
                            screenState = screenState.copy(
                                stage = QrVisualStage.EXPIRED,
                                remainingSeconds = 0,
                            )
                        }
                    }

                    QrConnectStatus.UNKNOWN -> Unit
                }
            }
        }

        @JavascriptInterface
        fun onIdentityRecords(@Suppress("UNUSED_PARAMETER") rawJson: String?) {
            // 登录页不需要收集会话好友身份列表，保留空实现兼容共享钩子
        }

        @JavascriptInterface
        fun onGroupRecords(@Suppress("UNUSED_PARAMETER") rawJson: String?) {
            // 登录页不需要收集群聊身份列表，保留空实现兼容共享钩子
        }

        @JavascriptInterface
        fun onInitProtoBase64(@Suppress("UNUSED_PARAMETER") base64: String?) {
            // 登录页不需要解析初始化 protobuf，保留空实现兼容共享钩子
        }

        @JavascriptInterface
        fun onInitProtoChunk(
            @Suppress("UNUSED_PARAMETER") txId: String?,
            @Suppress("UNUSED_PARAMETER") index: Int,
            @Suppress("UNUSED_PARAMETER") total: Int,
            @Suppress("UNUSED_PARAMETER") chunk: String?,
        ) {
            // 登录页不需要解析分片 protobuf，保留空实现兼容共享钩子
        }
    }

    companion object {
        private const val TAG = "FlameLogin"
        const val EXTRA_LOGIN_MODE = "extra_login_mode"
        const val EXTRA_ACCOUNT_ID = "extra_account_id"
        private const val MUTEX_OWNER = "login_webview"
        private const val POLL_INTERVAL_MS = 1_500L
        private const val FAST_POLL_INTERVAL_MS = 500L
        private const val FAST_RESUME_WINDOW_MS = 6_000L
        private const val CONFIRM_POLL_WINDOW_MS = 12_000L
        private const val FALLBACK_NOTIFY_GRACE_MS = 550L
        private const val FAST_REFRESH_WAIT_MS = 3_500L
        private const val BG_KEEP_ALIVE_TIMEOUT_MS = 180_000L
        private const val EVALUATE_JS_TIMEOUT_MS = 4_000L

        internal fun shouldDeleteGlobalWebStorage(
            multiProfileSupported: Boolean,
            accountCount: Int,
            accountId: String,
        ): Boolean = !multiProfileSupported || (accountCount <= 1 && accountId == DEFAULT_ACCOUNT_ID)

        fun createIntent(
            context: Context,
            mode: LoginMode,
            accountId: String = DEFAULT_ACCOUNT_ID,
        ): Intent = Intent(context, LoginWebViewActivity::class.java).apply {
            putExtra(EXTRA_LOGIN_MODE, mode.name)
            putExtra(EXTRA_ACCOUNT_ID, accountId)
        }
    }
}
