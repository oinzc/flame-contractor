package io.github.oinzc.flamecontractor.core.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class UrgentCategory {
    AUTH_EXPIRED,
    VERIFICATION_REQUIRED,
    DOM_BROKEN,
    COOLDOWN,
    UNKNOWN_REQUIRES_REVIEW,
    SPARK_DETERIORATING,
}

class LocalNotifier(context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    init {
        ensureChannels()
    }

    fun ensureChannels() {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_DAILY_SUMMARY) == null) {
            val dailyChannel = NotificationChannel(
                CHANNEL_DAILY_SUMMARY,
                "每日续火汇总",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "每日自动续火完成后的脱敏状态汇总"
            }
            manager.createNotificationChannel(dailyChannel)
        }
        if (manager.getNotificationChannel(CHANNEL_ALERTS_URGENT) == null) {
            val urgentChannel = NotificationChannel(
                CHANNEL_ALERTS_URGENT,
                "高优异常与待核对告警",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "登录失效、风控验证、页面结构异常、连续失败熔断与未知发送结果待人工核对提醒"
                enableVibration(true)
            }
            manager.createNotificationChannel(urgentChannel)
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyDailySummary(title: String, body: String): Boolean {
        ensureChannels()
        if (!canPostNotifications()) return false
        val builder = NotificationCompat.Builder(appContext, CHANNEL_DAILY_SUMMARY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
        createLaunchPendingIntent(category = null)?.let { builder.setContentIntent(it) }
        return try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID_DAILY_SUMMARY, builder.build())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyUrgentAlert(category: UrgentCategory, title: String, body: String): Boolean {
        ensureChannels()
        if (!canPostNotifications()) return false
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ALERTS_URGENT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
        createLaunchPendingIntent(category = category)?.let { builder.setContentIntent(it) }
        return try {
            NotificationManagerCompat.from(appContext).notify(notificationIdFor(category), builder.build())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    private fun createLaunchPendingIntent(category: UrgentCategory?): PendingIntent? {
        val launchIntent = appContext.packageManager
            ?.getLaunchIntentForPackage(appContext.packageName)
            ?: return null
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        if (category != null) {
            launchIntent.putExtra(EXTRA_URGENT_CATEGORY, category.name)
        }
        val requestCode = if (category == null) {
            NOTIFICATION_ID_DAILY_SUMMARY
        } else {
            notificationIdFor(category)
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_DAILY_SUMMARY = "daily_summary"
        const val CHANNEL_ALERTS_URGENT = "alerts_urgent"
        const val EXTRA_URGENT_CATEGORY = "extra_urgent_category"
        const val NOTIFICATION_ID_DAILY_SUMMARY = 3001

        internal fun notificationIdFor(category: UrgentCategory): Int = when (category) {
            UrgentCategory.AUTH_EXPIRED -> 3101
            UrgentCategory.VERIFICATION_REQUIRED -> 3102
            UrgentCategory.DOM_BROKEN -> 3103
            UrgentCategory.COOLDOWN -> 3104
            UrgentCategory.UNKNOWN_REQUIRES_REVIEW -> 3105
            UrgentCategory.SPARK_DETERIORATING -> 3106
        }
    }
}
