package io.github.oinzc.flamecontractor.execution.login

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.execution.LoginMode
import io.github.oinzc.flamecontractor.execution.LoginWebViewActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class KeepAliveStopAction {
    NO_OP,
    DEFER_UNTIL_STARTED,
    STOP_SERVICE_NOW,
}

internal enum class KeepAliveStartResult {
    KEEP_RUNNING,
    STOP_SELF_AFTER_FOREGROUND,
}

/**
 * 协调 `startForegroundService()` 与 `stopService()` 的状态机：
 * Android 8+ 若在 `Service.startForeground()` 尚未执行前调用 `stopService()`，系统会直接抛出
 * `ForegroundServiceDidNotStartInTimeException`。该协调器将过早到达的停止请求延迟到
 * `startForeground()` 完成后立即 `stopSelf()`，彻底消除权限弹窗回调与 `onResume()` 竞态闪退。
 */
internal class KeepAliveFgsCoordinator {

    private enum class State {
        IDLE,
        STARTING,
        RUNNING,
    }

    private var state: State = State.IDLE
    private var pendingStop: Boolean = false

    @Synchronized
    fun onRequestStart(): Boolean {
        pendingStop = false
        if (state == State.IDLE) {
            state = State.STARTING
        }
        return true
    }

    @Synchronized
    fun onStartDispatchFailed() {
        if (state == State.STARTING) {
            state = State.IDLE
            pendingStop = false
        }
    }

    @Synchronized
    fun onStartCommandEntered(startForegroundSucceeded: Boolean): KeepAliveStartResult {
        if (!startForegroundSucceeded) {
            state = State.IDLE
            pendingStop = false
            return KeepAliveStartResult.STOP_SELF_AFTER_FOREGROUND
        }
        if (pendingStop) {
            state = State.IDLE
            pendingStop = false
            return KeepAliveStartResult.STOP_SELF_AFTER_FOREGROUND
        }
        state = State.RUNNING
        return KeepAliveStartResult.KEEP_RUNNING
    }

    @Synchronized
    fun onRequestStop(): KeepAliveStopAction = when (state) {
        State.IDLE -> KeepAliveStopAction.NO_OP
        State.STARTING -> {
            pendingStop = true
            KeepAliveStopAction.DEFER_UNTIL_STARTED
        }
        State.RUNNING -> {
            state = State.IDLE
            pendingStop = false
            KeepAliveStopAction.STOP_SERVICE_NOW
        }
    }

    @Synchronized
    fun onServiceDestroyed() {
        state = State.IDLE
        pendingStop = false
    }
}

/**
 * 单手机跳往抖音 App 确认授权期间的轻量前台保活服务：
 * - 完全独立于 `ExecutionMutex`（登录页本身已持有互斥锁，避免自死锁）；
 * - 挂常驻低打扰状态条方便用户点「确认登录」后一键切回；
 * - 最长存活 3 分钟自动销毁，登录成功或页面销毁时立即停止。
 */
class LoginKeepAliveService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private var timeoutJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ACCOUNT_ID
        val modeName = intent?.getStringExtra(EXTRA_LOGIN_MODE)
            ?: LoginMode.PRESERVE_SESSION.name

        val notification = buildNotification(accountId = accountId, modeName = modeName)
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.getOrDefault(false)

        if (fgsCoordinator.onStartCommandEntered(startForegroundSucceeded = started) ==
            KeepAliveStartResult.STOP_SELF_AFTER_FOREGROUND
        ) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        armAutoStopTimeout()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        fgsCoordinator.onServiceDestroyed()
        timeoutJob?.cancel()
        timeoutJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun armAutoStopTimeout() {
        timeoutJob?.cancel()
        timeoutJob = serviceScope.launch {
            delay(KEEP_ALIVE_TIMEOUT_MS)
            stopSelfSafely()
        }
    }

    private fun stopSelfSafely() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "登录等待保活",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "跳转抖音 App 确认登录期间保持会话连接，并提供一键返回入口"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(accountId: String, modeName: String): Notification {
        val mode = LoginMode.entries.firstOrNull { it.name == modeName }
            ?: LoginMode.PRESERVE_SESSION
        val returnIntent = LoginWebViewActivity.createIntent(
            context = this,
            mode = mode,
            accountId = accountId,
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_RETURN,
            returnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("正在等待抖音授权登录")
            .setContentText("在抖音点击「确认登录」后，点此返回完成绑定")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "login_keep_alive"
        private const val NOTIFICATION_ID = 4109
        private const val REQUEST_CODE_RETURN = 4110
        private const val KEEP_ALIVE_TIMEOUT_MS = 180_000L

        private const val ACTION_START = "io.github.oinzc.flamecontractor.login.ACTION_KEEP_ALIVE_START"
        private const val EXTRA_ACCOUNT_ID = "extra_account_id"
        private const val EXTRA_LOGIN_MODE = "extra_login_mode"

        private val fgsCoordinator = KeepAliveFgsCoordinator()

        fun start(context: Context, accountId: String, modeName: String) {
            fgsCoordinator.onRequestStart()
            val dispatched = runCatching {
                val appCtx = context.applicationContext ?: context
                val intent = Intent(appCtx, LoginKeepAliveService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_ACCOUNT_ID, accountId)
                    putExtra(EXTRA_LOGIN_MODE, modeName)
                }
                ContextCompat.startForegroundService(appCtx, intent)
                true
            }.getOrDefault(false)
            if (!dispatched) {
                fgsCoordinator.onStartDispatchFailed()
            }
        }

        fun stop(context: Context) {
            if (fgsCoordinator.onRequestStop() != KeepAliveStopAction.STOP_SERVICE_NOW) return
            runCatching {
                val appCtx = context.applicationContext ?: context
                appCtx.stopService(Intent(appCtx, LoginKeepAliveService::class.java))
            }
        }
    }
}
