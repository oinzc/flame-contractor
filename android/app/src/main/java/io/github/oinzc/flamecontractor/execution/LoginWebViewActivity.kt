package io.github.oinzc.flamecontractor.execution

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import io.github.oinzc.flamecontractor.core.account.WebViewProfileRegistry
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundleStore
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.domain.AccountHealthReducer
import io.github.oinzc.flamecontractor.domain.AuthProbeOutcome
import io.github.oinzc.flamecontractor.execution.webview.DesktopWebViewFactory
import io.github.oinzc.flamecontractor.scheduler.ScheduleCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 桌面模式可见扫码 / 风控验证 / 保留态排查 Activity（设计规范 §6.5）：
 * - CLEAR_AND_RELOGIN 进入前先彻底清理 Cookie 与 WebStorage，避免旧态跳过二维码；
 * - PRESERVE_SESSION 与 INSPECT_OR_VERIFY 严禁清理 Cookie；
 * - 仅在非 INSPECT_OR_VERIFY 模式显示「清理缓存刷新二维码」按钮；
 * - 检出 CONVERSATION_LIST 且读取 localStorage.user_info 时经 AccountSwitchGuard 核对换号风险，
 *   不一致时立即暂停账号、取消闹钟并弹出不可取消二选一确认框。
 */
class LoginWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusLabel: TextView
    private lateinit var clearCacheBtn: Button

    private lateinit var loginMode: LoginMode
    private lateinit var accountId: String
    private lateinit var profileName: String
    private lateinit var database: FlameDatabase
    private lateinit var selectorStore: SelectorBundleStore

    private var mutexSession: ExecutionMutex.SessionToken? = null
    private var pollJob: Job? = null
    private var initialAccountStatus: AccountStatus = AccountStatus.PENDING_LOGIN
    private var handlingLoginSuccess = false
    private var dialogShowing = false
    private var identityHydrationRetries = 0
    private var inspectedHealthyOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loginMode = parseLoginMode(intent?.getStringExtra(EXTRA_LOGIN_MODE))
        accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ACCOUNT_ID
        profileName = WebViewProfileRegistry.profileNameForAccount(accountId)

        val appCtx = applicationContext ?: this
        database = ServiceDatabaseHolder.getInstance(appCtx)
        selectorStore = SelectorBundleStore(appCtx)

        buildLayout()

        DesktopWebViewFactory.configureDesktopWebView(
            webView = webView,
            blockDeepLinks = true,
            onPageFinished = {
                if (!handlingLoginSuccess && !dialogShowing) {
                    triggerSingleLoginProbe(userInitiated = false)
                }
            },
            documentStartScript = DouyinJsScripts.buildIdentityCaptureDocumentStartJs(),
            profileName = profileName,
        )

        lifecycleScope.launch {
            initialAccountStatus = withContext(Dispatchers.IO) {
                database.accountDao().getAccount(accountId)?.status ?: AccountStatus.PENDING_LOGIN
            }
            statusLabel.text = "正在准备桌面模式登录环境…"
            var token: ExecutionMutex.SessionToken? = ExecutionMutex.tryAcquireSession(MUTEX_OWNER)
            while (token == null && isActive) {
                statusLabel.text = "等待后台 WebView 任务结束…"
                token = ExecutionMutex.acquireSession(MUTEX_OWNER, timeoutMs = 2_000L)
            }
            mutexSession = token
            if (!isActive || isFinishing) return@launch

            val chatUrl = selectorStore.current().chatUrl
            if (loginMode == LoginMode.CLEAR_AND_RELOGIN) {
                statusLabel.text = "正在清理旧登录态并加载二维码…"
                clearCookiesAndWebStorage {
                    if (!isFinishing && !isDestroyed) {
                        statusLabel.text = modeHintText()
                        webView.loadUrl(chatUrl)
                        startPollingLoop()
                    }
                }
            } else {
                statusLabel.text = modeHintText()
                webView.loadUrl(chatUrl)
                startPollingLoop()
            }
        }
    }

    override fun onPause() {
        try {
            WebViewProfileRegistry.cookieManagerForProfileName(profileName).flush()
        } catch (_: Throwable) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        pollJob = null
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

    private fun buildLayout() {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#18181B"))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        statusLabel = TextView(this).apply {
            text = modeHintText()
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
        }
        topBar.addView(
            statusLabel,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        fun addBarButton(label: String, onClick: () -> Unit): Button {
            val btn = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#27272A"))
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener { onClick() }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) }
            topBar.addView(btn, lp)
            return btn
        }

        addBarButton("刷新") {
            if (!dialogShowing) {
                webView.loadUrl(selectorStore.current().chatUrl)
            }
        }

        addBarButton("检查登录态") {
            if (!dialogShowing) {
                triggerSingleLoginProbe(userInitiated = true)
            }
        }

        clearCacheBtn = addBarButton("清理缓存刷新二维码") {
            if (!dialogShowing && loginMode != LoginMode.INSPECT_OR_VERIFY) {
                statusLabel.text = "正在清理缓存并刷新二维码…"
                clearCookiesAndWebStorage {
                    if (!isFinishing && !isDestroyed) {
                        statusLabel.text = "请使用抖音 App 扫码登录"
                        webView.loadUrl(selectorStore.current().chatUrl)
                    }
                }
            }
        }
        clearCacheBtn.visibility = if (loginMode == LoginMode.INSPECT_OR_VERIFY) {
            View.GONE
        } else {
            View.VISIBLE
        }

        root.addView(
            topBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        webView = WebView(this)
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        setContentView(root)
    }

    private fun startPollingLoop() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && !isFinishing) {
                delay(POLL_INTERVAL_MS)
                if (!handlingLoginSuccess && !dialogShowing && mutexSession != null) {
                    evaluateLoginProbe(userInitiated = false)
                }
            }
        }
    }

    private fun triggerSingleLoginProbe(userInitiated: Boolean) {
        lifecycleScope.launch {
            if (!handlingLoginSuccess && !dialogShowing) {
                evaluateLoginProbe(userInitiated = userInitiated)
            }
        }
    }

    private suspend fun evaluateLoginProbe(userInitiated: Boolean) {
        val bundle = selectorStore.current()
        val raw = evaluateJsOnMain(DouyinJsScripts.buildAuthAndSelfIdentityProbeJs(bundle)) ?: return
        @Suppress("UNCHECKED_CAST")
        val probe = ExecJson.unwrapEvaluateResult(raw) as? Map<String, Any?> ?: return

        val verificationRequired = probe.boolOrDefault("verificationRequired", false)
        if (verificationRequired) {
            statusLabel.text = "检测到安全验证弹窗，请在网页内完成身份验证"
            return
        }

        val loggedIn = probe.boolOrDefault("loggedIn", false)
        if (!loggedIn) {
            if (userInitiated) {
                statusLabel.text = "尚未检测到已登录会话列表，请完成扫码或验证后再试"
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
        try {
            handleLoggedInDetected(
                newUid = newUid,
                newNick = newNick,
                userInitiated = userInitiated,
            )
        } finally {
            if (!isFinishing && !dialogShowing) {
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
            showAccountSwitchChoiceDialog(newUid = newUid, newNick = newNick)
            return
        }

        if (loginMode == LoginMode.INSPECT_OR_VERIFY &&
            initialAccountStatus == AccountStatus.HEALTHY &&
            !userInitiated
        ) {
            if (!inspectedHealthyOnce) {
                inspectedHealthyOnce = true
                withContext(Dispatchers.IO) {
                    database.accountDao().updateLoginIdentity(
                        accountId = accountId,
                        selfUid = newUid ?: account.selfUid,
                        douyinNickname = newNick ?: account.douyinNickname,
                        verifiedAt = nowMs,
                    )
                }
            }
            statusLabel.text = "排查模式：已保持登录态，可浏览网页或点「检查登录态」退出"
            return
        }

        withContext(Dispatchers.IO) {
            val updatedIdentity = account.copy(
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
        statusLabel.text = "已登录并验证通过，正在返回…"
        setResult(RESULT_OK)
        finish()
    }

    private fun showAccountSwitchChoiceDialog(
        newUid: String?,
        newNick: String?,
    ) {
        if (isFinishing || isDestroyed || dialogShowing) return
        dialogShowing = true

        val choices = arrayOf(
            "清空旧号好友与待发任务，重新扫描当前账号（推荐）",
            "保留现有好友名单，但解绑全部稳定 ID 并暂停自动发送（逐个核对后再启用）",
        )

        var selectedIdx = 0
        AlertDialog.Builder(this)
            .setTitle("检测到当前登录抖音号已变更")
            .setCancelable(false)
            .setSingleChoiceItems(choices, 0) { _, which ->
                selectedIdx = which
            }
            .setPositiveButton("确认执行") { dialog, _ ->
                dialog.dismiss()
                applyAccountSwitchDecision(
                    wipeAndRescan = selectedIdx == 0,
                    newUid = newUid,
                    newNick = newNick,
                )
            }
            .show()
    }

    private fun applyAccountSwitchDecision(
        wipeAndRescan: Boolean,
        newUid: String?,
        newNick: String?,
    ) {
        lifecycleScope.launch {
            val nowMs = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                database.withTransaction {
                    val acc = database.accountDao().getAccount(accountId) ?: return@withTransaction
                    if (wipeAndRescan) {
                        database.sendJobDao().deleteAllByAccount(accountId)
                        database.targetDao().deleteAllByAccount(accountId)
                        val resetAcc = acc.copy(
                            status = AccountStatus.HEALTHY,
                            authFailCount = 0,
                            failureStreak = 0,
                            selfUid = newUid,
                            douyinNickname = newNick,
                            lastVerifiedAt = nowMs,
                            updatedAt = nowMs,
                        )
                        database.accountDao().update(resetAcc)
                    } else {
                        database.targetDao().unbindIdentitiesAndDisableAll(accountId, nowMs)
                        val pausedAcc = acc.copy(
                            status = AccountStatus.PAUSED,
                            authFailCount = 0,
                            selfUid = newUid,
                            douyinNickname = newNick,
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

            // 先释放互斥锁再入队扫描，避免扫描等待自身 Activity 释放
            ExecutionMutex.releaseSession(mutexSession)
            mutexSession = null
            if (wipeAndRescan) {
                ExecutionForegroundService.enqueueManualScan(this@LoginWebViewActivity, accountId)
            }

            setResult(RESULT_OK)
            finish()
        }
    }

    private fun clearCookiesAndWebStorage(onCleared: () -> Unit) {
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
                        webView.clearCache(true)
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

    private fun modeHintText(): String = when (loginMode) {
        LoginMode.CLEAR_AND_RELOGIN -> "已清理旧登录态，请扫码登录新账号"
        LoginMode.PRESERVE_SESSION -> "请扫码登录或完成风控身份验证"
        LoginMode.INSPECT_OR_VERIFY -> "保留登录态排查 / 验证模式"
    }

    private fun parseLoginMode(raw: String?): LoginMode =
        LoginMode.entries.firstOrNull { it.name == raw } ?: LoginMode.PRESERVE_SESSION

    companion object {
        const val EXTRA_LOGIN_MODE = "extra_login_mode"
        const val EXTRA_ACCOUNT_ID = "extra_account_id"
        private const val MUTEX_OWNER = "login_webview"
        private const val POLL_INTERVAL_MS = 1_500L

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
