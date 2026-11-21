package io.github.oinzc.flamecontractor.execution

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.oinzc.flamecontractor.execution.webview.DesktopWebViewFactory
import org.json.JSONObject

class OffscreenProbeService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var probeMode: String = MODE_PROBE
    private var domProbeTriggered: Boolean = false
    private var stopped: Boolean = false

    private val settleRunnable = Runnable {
        if (!stopped && !domProbeTriggered) {
            triggerDomProbe()
        }
    }

    private val hardTimeoutRunnable = Runnable {
        if (!stopped) {
            Log.w(TAG, "OFFSCREEN_HARD_TIMEOUT")
            finishAndStop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    @SuppressLint("JavascriptInterface")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("火焰契约 M1 离屏验证中")
            .setContentText("正在后台验证无窗口桌面模式 WebView 能力")
            .setOngoing(true)
            .setSilent(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)

        if (webView != null && !stopped) {
            Log.i(TAG, "OFFSCREEN_PROBE_ALREADY_RUNNING")
            return START_NOT_STICKY
        }

        probeMode = intent?.getStringExtra("mode")?.trim().takeUnless { it.isNullOrEmpty() } ?: MODE_PROBE
        stopped = false
        domProbeTriggered = false
        acquireWakeLock()

        val (createdWebView, config) = DesktopWebViewFactory.createOffscreenWebView(
            context = applicationContext,
            widthPx = 1440,
            heightPx = 900,
            onPageFinished = { originPath ->
                Log.i(TAG, "OFFSCREEN_PAGE_FINISHED originPath=$originPath")
                if (!stopped && !domProbeTriggered) {
                    mainHandler.removeCallbacks(settleRunnable)
                    mainHandler.postDelayed(settleRunnable, SETTLE_DELAY_MS)
                }
            },
            onMainFrameError = { code, desc ->
                Log.e(TAG, "OFFSCREEN_MAIN_FRAME_ERROR code=$code desc=$desc")
            },
        )

        webView = createdWebView
        createdWebView.addJavascriptInterface(M1Bridge(), "FlameM1")
        Log.i(
            TAG,
            "OFFSCREEN_SERVICE_STARTED mode=$probeMode docStart=${config.documentStartSupported}",
        )

        mainHandler.postDelayed(hardTimeoutRunnable, HARD_TIMEOUT_MS)
        createdWebView.loadUrl(DesktopWebViewFactory.CHAT_URL)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanupResources()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun triggerDomProbe() {
        if (stopped || domProbeTriggered) return
        domProbeTriggered = true
        val view = webView ?: run {
            finishAndStop()
            return
        }
        view.evaluateJavascript(DesktopWebViewFactory.OFFSCREEN_DOM_PROBE_JS) { }
    }

    private fun runSlateNonSendingProbe() {
        val view = webView ?: run {
            finishAndStop()
            return
        }
        view.evaluateJavascript(DesktopWebViewFactory.OPEN_FIRST_CONVERSATION_JS) { clickRaw ->
            Log.i(TAG, "OPEN_CHAT_CLICK $clickRaw")
            pollEditorReady(attempt = 1, lastJson = "{}")
        }
    }

    private fun pollEditorReady(attempt: Int, lastJson: String) {
        if (stopped) return
        if (attempt > EDITOR_MAX_POLLS) {
            Log.w(TAG, "OFFSCREEN_EDITOR_TIMEOUT attempts=$EDITOR_MAX_POLLS last=$lastJson")
            finishAndStop()
            return
        }
        mainHandler.postDelayed(
            {
                if (stopped) return@postDelayed
                val view = webView ?: run {
                    finishAndStop()
                    return@postDelayed
                }
                view.evaluateJavascript(DesktopWebViewFactory.EDITOR_READY_CHECK_JS) { checkRaw ->
                    if (stopped) return@evaluateJavascript
                    val parsed = try {
                        JSONObject(checkRaw ?: "{}")
                    } catch (_: Throwable) {
                        JSONObject()
                    }
                    val visible = parsed.optBoolean("editorVisible", false)
                    val found = parsed.optBoolean("editorFound", false)
                    if (visible || (attempt == EDITOR_MAX_POLLS && found)) {
                        view.evaluateJavascript(DesktopWebViewFactory.SLATE_NON_SENDING_EXEC_COMMAND_JS) { }
                    } else {
                        pollEditorReady(attempt + 1, checkRaw ?: "{}")
                    }
                }
            },
            EDITOR_POLL_INTERVAL_MS,
        )
    }

    private fun finishAndStop() {
        if (stopped) return
        stopped = true
        cleanupResources()
        Log.i(TAG, "OFFSCREEN_SERVICE_STOPPED")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupResources() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {
        }
        webView?.let { wv ->
            try {
                wv.stopLoading()
                wv.destroy()
            } catch (_: Throwable) {
            }
        }
        webView = null
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Throwable) {
        } finally {
            wakeLock = null
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlameContractor::M1Offscreen")
        lock.setReferenceCounted(false)
        lock.acquire(HARD_TIMEOUT_MS)
        wakeLock = lock
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "火焰契约 M1 离屏验证",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    inner class M1Bridge {
        @JavascriptInterface
        fun onProbe(json: String) {
            mainHandler.post {
                if (stopped) return@post
                Log.i(TAG, "OFFSCREEN_PROBE $json")
                val loggedIn = try {
                    JSONObject(json).optBoolean("loggedIn", false)
                } catch (_: Throwable) {
                    false
                }
                if (probeMode == MODE_PROBE_SLATE && loggedIn) {
                    runSlateNonSendingProbe()
                } else {
                    finishAndStop()
                }
            }
        }

        @JavascriptInterface
        fun onSlateResult(json: String) {
            mainHandler.post {
                if (stopped) return@post
                Log.i(TAG, "OFFSCREEN_SLATE $json")
                finishAndStop()
            }
        }
    }

    companion object {
        private const val TAG = "FlameM1"
        private const val CHANNEL_ID = "flame_m1_offscreen"
        private const val NOTIFICATION_ID = 2001
        private const val MODE_PROBE = "probe"
        private const val MODE_PROBE_SLATE = "probe_slate"
        private const val SETTLE_DELAY_MS = 4500L
        private const val EDITOR_POLL_INTERVAL_MS = 500L
        private const val EDITOR_MAX_POLLS = 12
        private const val HARD_TIMEOUT_MS = 35_000L
    }
}
