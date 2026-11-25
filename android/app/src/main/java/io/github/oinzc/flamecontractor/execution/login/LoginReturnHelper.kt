package io.github.oinzc.flamecontractor.execution.login

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.execution.LoginMode
import io.github.oinzc.flamecontractor.execution.LoginWebViewActivity
import io.github.oinzc.flamecontractor.ui.shell.MainActivity

data class LoginReturnDispatchResult(
    val activityStartAttempted: Boolean,
    val notificationPosted: Boolean,
)

/**
 * 抖音端完成授权后的双层回跳协助器：
 * 1. 先尝试主动将本应用拉回前台（Android 8–9 或已授予「后台弹出界面 / 悬浮窗」权限的设备可全自动跳回）；
 * 2. 同步发出 `IMPORTANCE_HIGH` 顶部悬浮横幅通知（点一下即刻返回）；若第 1 层自动回跳成功，
 *    目标 Activity `onResume()` 会立即撤销该横幅，不留冗余通知。
 */
class LoginReturnHelper(context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun bringAppToFrontAndNotify(
        event: LoginReturnEvent,
        accountId: String,
        loginMode: LoginMode,
    ): LoginReturnDispatchResult {
        val targetIntent = buildReturnIntent(
            event = event,
            accountId = accountId,
            loginMode = loginMode,
        )

        // 第一层：尽力直接把本应用拉回前台（支持后台弹出权限或 Android 8-9 时无缝自动跳回）
        val activityAttempted = runCatching {
            appContext.startActivity(targetIntent)
            true
        }.getOrDefault(false)

        // 第二层：同步推送高优顶部悬浮横幅兜底（未开后台弹出权限时，在抖音顶部滑出横幅一点即回）
        ensureChannel()
        val notifManager = NotificationManagerCompat.from(appContext)
        if (!hasNotificationPermission() || !notifManager.areNotificationsEnabled()) {
            Log.i(TAG, "RETURN_DISPATCH event=${event.name} activityAttempted=$activityAttempted notifPosted=false")
            return LoginReturnDispatchResult(
                activityStartAttempted = activityAttempted,
                notificationPosted = false,
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            appContext,
            REQUEST_CODE_LOGIN_RETURN,
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_LOGIN_RETURN)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(event.title)
            .setContentText(event.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setTimeoutAfter(BANNER_TIMEOUT_MS)
            .setContentIntent(pendingIntent)
            .addAction(0, event.actionLabel, pendingIntent)
            .build()

        val notifPosted = runCatching {
            notifManager.notify(
                NOTIFICATION_ID_LOGIN_RETURN,
                notification,
            )
            true
        }.getOrDefault(false)

        Log.i(TAG, "RETURN_DISPATCH event=${event.name} activityAttempted=$activityAttempted notifPosted=$notifPosted")
        return LoginReturnDispatchResult(
            activityStartAttempted = activityAttempted,
            notificationPosted = notifPosted,
        )
    }

    private fun buildReturnIntent(
        event: LoginReturnEvent,
        accountId: String,
        loginMode: LoginMode,
    ): Intent = when (event) {
        LoginReturnEvent.LOGIN_SUCCEEDED -> Intent(appContext, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(LocalNotifier.EXTRA_ACCOUNT_ID, accountId)
        }

        LoginReturnEvent.QR_CONFIRMED,
        LoginReturnEvent.VERIFICATION_REQUIRED,
        LoginReturnEvent.ACCOUNT_SWITCH_CONFIRM,
        -> LoginWebViewActivity.createIntent(
            context = appContext,
            mode = LoginSessionReducer.safeReturnLoginMode(loginMode),
            accountId = accountId,
        ).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
    }

    fun ensureChannel() {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_LOGIN_RETURN) != null) return
        val channel = NotificationChannel(
            CHANNEL_LOGIN_RETURN,
            "登录结果与返回提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "在抖音完成授权、触发安全验证或换号确认时弹出顶部横幅，方便一键返回应用"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "FlameLogin"
        private const val CHANNEL_LOGIN_RETURN = "login_return_result"
        private const val NOTIFICATION_ID_LOGIN_RETURN = 4111
        private const val REQUEST_CODE_LOGIN_RETURN = 4112
        private const val BANNER_TIMEOUT_MS = 45_000L

        fun cancelReturnNotification(context: Context) {
            runCatching {
                val appCtx = context.applicationContext ?: context
                NotificationManagerCompat.from(appCtx).cancel(NOTIFICATION_ID_LOGIN_RETURN)
            }
        }
    }
}
