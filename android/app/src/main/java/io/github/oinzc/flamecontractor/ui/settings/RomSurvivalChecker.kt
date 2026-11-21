package io.github.oinzc.flamecontractor.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import java.util.Locale

enum class SurvivalCheckId {
    NOTIFICATIONS,
    BATTERY_OPTIMIZATION,
    EXACT_ALARM,
    MIUI_AUTOSTART_AND_BACKGROUND,
}

data class SurvivalCheckItem(
    val id: SurvivalCheckId,
    val title: String,
    val passed: Boolean,
    val advisoryOnly: Boolean = false,
    val statusLabel: String,
    val detail: String,
    val actionLabel: String,
)

data class NotificationChannelStatus(
    val channelId: String,
    val title: String,
    val enabled: Boolean,
)

data class RomSurvivalSnapshot(
    val checkedAtEpochMs: Long,
    val isXiaomiOrHyperOs: Boolean,
    val manufacturer: String,
    val notificationsGranted: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val exactAlarmAllowed: Boolean,
    val autostartGuidanceConfirmed: Boolean,
    val dailySummaryChannelEnabled: Boolean = true,
    val urgentAlertsChannelEnabled: Boolean = true,
    val items: List<SurvivalCheckItem>,
) {
    val passedCount: Int
        get() = items.count { it.passed }

    val totalCount: Int
        get() = items.size

    val coreAllPassed: Boolean
        get() = notificationsGranted && batteryOptimizationIgnored && exactAlarmAllowed
}

/**
 * 小米 HyperOS / 国产 ROM 后台稳定性体检与安全跳转器。
 *
 * 所有厂商私有活动（如 MIUI/HyperOS AutoStartManagementActivity / PowerHideModeActivity）
 * 均使用 try/catch ActivityNotFoundException / SecurityException 安全回退到标准系统设置或应用详情页。
 */
object RomSurvivalChecker {

    private const val PREFS_NAME = "flame_rom_survival_prefs"
    private const val KEY_AUTOSTART_CONFIRMED = "autostart_confirmed"

    private val XIAOMI_BRANDS = setOf("xiaomi", "redmi", "poco", "blackshark")

    fun isXiaomiOrHyperOs(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        brand: String = Build.BRAND.orEmpty(),
    ): Boolean {
        val m = manufacturer.trim().lowercase(Locale.ROOT)
        val b = brand.trim().lowercase(Locale.ROOT)
        return XIAOMI_BRANDS.any { token -> m.contains(token) || b.contains(token) }
    }

    fun isAutostartGuidanceConfirmed(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOSTART_CONFIRMED, false)
    }

    fun setAutostartGuidanceConfirmed(context: Context, confirmed: Boolean) {
        val appContext = context.applicationContext ?: context
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTOSTART_CONFIRMED, confirmed)
            .apply()
    }

    fun inspect(
        context: Context,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): RomSurvivalSnapshot {
        val appContext = context.applicationContext ?: context
        // 确保通道已初始化，避免首次打开设置页时无法读取通道状态
        try {
            LocalNotifier(appContext).ensureChannels()
        } catch (_: Throwable) {
        }

        val notificationsGranted = hasNotificationPermission(appContext)
        val batteryIgnored = isIgnoringBatteryOptimizations(appContext)
        val exactAlarmAllowed = canScheduleExactAlarms(appContext)
        val xiaomi = isXiaomiOrHyperOs()
        val manufacturerLabel = Build.MANUFACTURER?.takeIf { it.isNotBlank() } ?: "Android"
        val userConfirmedAutostart = isAutostartGuidanceConfirmed(appContext)
        val autostartEffective = if (xiaomi) userConfirmedAutostart else true

        val dailyEnabled = isNotificationChannelEnabled(appContext, LocalNotifier.CHANNEL_DAILY_SUMMARY)
        val urgentEnabled = isNotificationChannelEnabled(appContext, LocalNotifier.CHANNEL_ALERTS_URGENT)

        return buildSnapshot(
            checkedAtEpochMs = nowEpochMs,
            isXiaomiOrHyperOs = xiaomi,
            manufacturer = manufacturerLabel,
            notificationsGranted = notificationsGranted,
            batteryOptimizationIgnored = batteryIgnored,
            exactAlarmAllowed = exactAlarmAllowed,
            autostartGuidanceConfirmed = autostartEffective,
            userConfirmedFlag = userConfirmedAutostart,
            dailySummaryChannelEnabled = dailyEnabled,
            urgentAlertsChannelEnabled = urgentEnabled,
        )
    }

    internal fun buildSnapshot(
        checkedAtEpochMs: Long,
        isXiaomiOrHyperOs: Boolean,
        manufacturer: String,
        notificationsGranted: Boolean,
        batteryOptimizationIgnored: Boolean,
        exactAlarmAllowed: Boolean,
        autostartGuidanceConfirmed: Boolean,
        userConfirmedFlag: Boolean = autostartGuidanceConfirmed,
        dailySummaryChannelEnabled: Boolean = true,
        urgentAlertsChannelEnabled: Boolean = true,
    ): RomSurvivalSnapshot {
        val items = listOf(
            SurvivalCheckItem(
                id = SurvivalCheckId.NOTIFICATIONS,
                title = "系统通知权限",
                passed = notificationsGranted,
                statusLabel = if (notificationsGranted) "已允许" else "未授权",
                detail = if (notificationsGranted) {
                    "已授权发送每日汇总与高优异常（AUTH_EXPIRED / UNKNOWN）提醒"
                } else {
                    "未开启通知权限，掉登录或待人工核对时将无法弹出系统告警"
                },
                actionLabel = if (notificationsGranted) "通知设置" else "去授权",
            ),
            SurvivalCheckItem(
                id = SurvivalCheckId.BATTERY_OPTIMIZATION,
                title = "电池优化白名单 / 无限制省电",
                passed = batteryOptimizationIgnored,
                statusLabel = if (batteryOptimizationIgnored) "已加入白名单" else "受省电限制",
                detail = if (batteryOptimizationIgnored) {
                    "系统已放行电池优化，息屏后调度与前台服务不易被杀"
                } else {
                    "建议设为「无限制」或忽略电池优化，避免后台闹钟与 WebView 被限制网络"
                },
                actionLabel = if (batteryOptimizationIgnored) "省电设置" else "设为无限制",
            ),
            SurvivalCheckItem(
                id = SurvivalCheckId.EXACT_ALARM,
                title = "精确闹钟调度权限",
                passed = exactAlarmAllowed,
                statusLabel = if (exactAlarmAllowed) "已允许" else "受限（已降级）",
                detail = if (exactAlarmAllowed) {
                    "可按设定窗口精准注册 AlarmManager 唤醒闹钟"
                } else {
                    "Android 12+ 未授予精确闹钟权限，当前降级为非精确唤醒，可能延后触发"
                },
                actionLabel = if (exactAlarmAllowed) "查看权限" else "去授权",
            ),
            SurvivalCheckItem(
                id = SurvivalCheckId.MIUI_AUTOSTART_AND_BACKGROUND,
                title = if (isXiaomiOrHyperOs) "小米 / HyperOS 自启动与神隐模式" else "国产 ROM 自启动与后台策略",
                passed = autostartGuidanceConfirmed,
                advisoryOnly = !isXiaomiOrHyperOs,
                statusLabel = when {
                    isXiaomiOrHyperOs && userConfirmedFlag -> "已确认开启"
                    isXiaomiOrHyperOs -> "待前往设置"
                    userConfirmedFlag -> "已确认"
                    else -> "按需核对（$manufacturer）"
                },
                detail = if (isXiaomiOrHyperOs) {
                    "HyperOS / MIUI 系统未提供自启动读取接口。请在系统页面开启「允许自启动」并在「省电策略」选择「无限制」（关闭神隐后台冻结），完成后勾选下方确认。"
                } else {
                    "当前机型（$manufacturer）未识别为 MIUI/HyperOS，若系统存在「应用自启动/后台冻结」开关，可点击前往应用详情放行。"
                },
                actionLabel = if (isXiaomiOrHyperOs) "去设置自启动" else "应用后台设置",
            ),
        )

        return RomSurvivalSnapshot(
            checkedAtEpochMs = checkedAtEpochMs,
            isXiaomiOrHyperOs = isXiaomiOrHyperOs,
            manufacturer = manufacturer,
            notificationsGranted = notificationsGranted,
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            exactAlarmAllowed = exactAlarmAllowed,
            autostartGuidanceConfirmed = autostartGuidanceConfirmed,
            dailySummaryChannelEnabled = dailySummaryChannelEnabled,
            urgentAlertsChannelEnabled = urgentAlertsChannelEnabled,
            items = items,
        )
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun isNotificationChannelEnabled(context: Context, channelId: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        val channel = manager.getNotificationChannel(channelId) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return try {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Throwable) {
            false
        }
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return try {
            alarmManager.canScheduleExactAlarms()
        } catch (_: Throwable) {
            false
        }
    }

    fun openNotificationSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        return launchWithAppDetailsFallback(context, listOf(intent))
    }

    fun openNotificationChannelSettings(context: Context, channelId: String): Boolean {
        try {
            LocalNotifier(context).ensureChannels()
        } catch (_: Throwable) {
        }
        val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        }
        val appNotifyIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        return launchWithAppDetailsFallback(context, listOf(channelIntent, appNotifyIntent))
    }

    /**
     * 本应用为开源直装 APK（非 Google Play 分发），需在设置体检中直接引导用户放行电池优化白名单。
     */
    @SuppressLint("BatteryLife")
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        val requestDirect = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        val listSettings = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return launchWithAppDetailsFallback(context, listOf(requestDirect, listSettings))
    }

    fun openExactAlarmSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val withPackage = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            val withoutPackage = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            return launchWithAppDetailsFallback(context, listOf(withPackage, withoutPackage))
        }
        return openAppDetailsSettings(context)
    }

    fun openMiuiAutostartSettings(context: Context): Boolean {
        val pkg = context.packageName
        val appLabel = resolveAppLabel(context)
        val candidates = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )
                putExtra("extra_pkgname", pkg)
                putExtra("package_name", pkg)
            },
            Intent("miui.intent.action.OP_AUTO_START").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                putExtra("package_name", pkg)
            },
            Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                )
                putExtra("package_name", pkg)
                putExtra("package_label", appLabel)
            },
            Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.PowerHideModeActivity",
                )
                putExtra("package_name", pkg)
            },
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                )
                putExtra("extra_pkgname", pkg)
            },
        )
        return launchWithAppDetailsFallback(context, candidates)
    }

    fun openMiuiPowerHideSettings(context: Context): Boolean {
        val pkg = context.packageName
        val appLabel = resolveAppLabel(context)
        val candidates = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                )
                putExtra("package_name", pkg)
                putExtra("package_label", appLabel)
            },
            Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.PowerHideModeActivity",
                )
                putExtra("package_name", pkg)
            },
            Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").apply {
                putExtra("package_name", pkg)
            },
        )
        return launchWithAppDetailsFallback(context, candidates)
    }

    fun openAppDetailsSettings(context: Context): Boolean {
        val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        return launchSafely(context, detailsIntent)
    }

    internal fun launchWithAppDetailsFallback(
        context: Context,
        candidates: List<Intent>,
    ): Boolean {
        for (intent in candidates) {
            if (launchSafely(context, intent)) {
                return true
            }
        }
        return openAppDetailsSettings(context)
    }

    internal fun launchSafely(context: Context, intent: Intent): Boolean {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun resolveAppLabel(context: Context): String = try {
        val pm = context.packageManager
        val appInfo = context.applicationInfo
        pm.getApplicationLabel(appInfo).toString()
    } catch (_: Throwable) {
        "抖音续火工"
    }
}
