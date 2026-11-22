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
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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
    fun notifyDailySummary(
        title: String,
        body: String,
        accountId: String = DEFAULT_ACCOUNT_ID,
    ): Boolean {
        ensureChannels()
        if (!canPostNotifications()) return false
        val normalizedAccountId = normalizeAccountId(accountId)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_DAILY_SUMMARY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
        createLaunchPendingIntent(category = null, accountId = normalizedAccountId)?.let {
            builder.setContentIntent(it)
        }
        return try {
            NotificationManagerCompat.from(appContext).notify(
                dailySummaryNotificationIdFor(normalizedAccountId),
                builder.build(),
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyUrgentAlert(
        category: UrgentCategory,
        title: String,
        body: String,
        accountId: String = DEFAULT_ACCOUNT_ID,
    ): Boolean {
        ensureChannels()
        if (!canPostNotifications()) return false
        val normalizedAccountId = normalizeAccountId(accountId)
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
        createLaunchPendingIntent(category = category, accountId = normalizedAccountId)?.let {
            builder.setContentIntent(it)
        }
        return try {
            NotificationManagerCompat.from(appContext).notify(
                notificationIdFor(category = category, accountId = normalizedAccountId),
                builder.build(),
            )
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

    private fun createLaunchPendingIntent(
        category: UrgentCategory?,
        accountId: String = DEFAULT_ACCOUNT_ID,
    ): PendingIntent? {
        val launchIntent = appContext.packageManager
            ?.getLaunchIntentForPackage(appContext.packageName)
            ?: return null
        val normalizedAccountId = normalizeAccountId(accountId)
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        launchIntent.putExtra(EXTRA_ACCOUNT_ID, normalizedAccountId)
        if (category != null) {
            launchIntent.putExtra(EXTRA_URGENT_CATEGORY, category.name)
        }
        val requestCode = launchRequestCodeFor(category = category, accountId = normalizedAccountId)
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
        const val EXTRA_ACCOUNT_ID = "extra_account_id"
        const val NOTIFICATION_ID_DAILY_SUMMARY = 3001

        private const val ACCOUNT_ID_BUCKET_BASE = 100_000
        private const val ACCOUNT_ID_BUCKET_STRIDE = 16
        private const val ACCOUNT_ID_BUCKET_COUNT = 100_000_000

        internal fun normalizeAccountId(accountId: String?): String =
            accountId?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_ACCOUNT_ID

        internal fun dailySummaryNotificationIdFor(
            accountId: String = DEFAULT_ACCOUNT_ID,
        ): Int {
            val normalized = normalizeAccountId(accountId)
            if (normalized == DEFAULT_ACCOUNT_ID) return NOTIFICATION_ID_DAILY_SUMMARY
            return accountBucketBase(normalized)
        }

        internal fun notificationIdFor(
            category: UrgentCategory,
            accountId: String = DEFAULT_ACCOUNT_ID,
        ): Int {
            val normalized = normalizeAccountId(accountId)
            val offset = urgentSlotOffset(category)
            if (normalized == DEFAULT_ACCOUNT_ID) {
                return 3100 + offset
            }
            return accountBucketBase(normalized) + offset
        }

        internal fun launchRequestCodeFor(
            category: UrgentCategory?,
            accountId: String = DEFAULT_ACCOUNT_ID,
        ): Int = if (category == null) {
            dailySummaryNotificationIdFor(accountId)
        } else {
            notificationIdFor(category = category, accountId = accountId)
        }

        internal fun formatWithAccountPrefix(
            text: String,
            accountDisplayName: String?,
            isMultiAccount: Boolean,
        ): String {
            if (!isMultiAccount) return text
            val label = accountDisplayName?.trim()?.takeIf { it.isNotEmpty() } ?: return text
            val prefix = "[$label]"
            return if (text.startsWith(prefix)) text else "$prefix $text"
        }

        private fun urgentSlotOffset(category: UrgentCategory): Int = when (category) {
            UrgentCategory.AUTH_EXPIRED -> 1
            UrgentCategory.VERIFICATION_REQUIRED -> 2
            UrgentCategory.DOM_BROKEN -> 3
            UrgentCategory.COOLDOWN -> 4
            UrgentCategory.UNKNOWN_REQUIRES_REVIEW -> 5
            UrgentCategory.SPARK_DETERIORATING -> 6
        }

        private fun accountBucketBase(normalizedAccountId: String): Int {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalizedAccountId.toByteArray(StandardCharsets.UTF_8))
            var raw = 0L
            for (i in 0 until 8) {
                raw = (raw shl 8) or (digest[i].toLong() and 0xFFL)
            }
            val positive = raw and Long.MAX_VALUE
            val bucket = (positive % ACCOUNT_ID_BUCKET_COUNT).toInt()
            return ACCOUNT_ID_BUCKET_BASE + bucket * ACCOUNT_ID_BUCKET_STRIDE
        }
    }
}
