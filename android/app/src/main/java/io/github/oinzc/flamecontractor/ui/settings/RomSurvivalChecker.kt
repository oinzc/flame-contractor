package io.github.oinzc.flamecontractor.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import java.util.Locale

enum class OemVendorFamily(
    val displayName: String,
    val hasExtraBackgroundPopupSwitch: Boolean,
    val requiresManualAutostartConfirmation: Boolean,
) {
    XIAOMI(
        displayName = "小米 / HyperOS",
        hasExtraBackgroundPopupSwitch = true,
        requiresManualAutostartConfirmation = true,
    ),
    HUAWEI_HONOR(
        displayName = "华为 / 荣耀",
        hasExtraBackgroundPopupSwitch = false,
        requiresManualAutostartConfirmation = true,
    ),
    OPPO_ONEPLUS_REALME(
        displayName = "OPPO / 一加 / 真我",
        hasExtraBackgroundPopupSwitch = false,
        requiresManualAutostartConfirmation = true,
    ),
    VIVO_IQOO(
        displayName = "vivo / iQOO",
        hasExtraBackgroundPopupSwitch = true,
        requiresManualAutostartConfirmation = true,
    ),
    SAMSUNG(
        displayName = "三星 One UI",
        hasExtraBackgroundPopupSwitch = false,
        requiresManualAutostartConfirmation = true,
    ),
    MEIZU(
        displayName = "魅族 Flyme",
        hasExtraBackgroundPopupSwitch = false,
        requiresManualAutostartConfirmation = true,
    ),
    GENERIC_ANDROID(
        displayName = "通用 Android",
        hasExtraBackgroundPopupSwitch = false,
        requiresManualAutostartConfirmation = false,
    ),
}

enum class SurvivalCheckId {
    NOTIFICATIONS,
    OVERLAY_AUTO_RETURN,
    BATTERY_OPTIMIZATION,
    EXACT_ALARM,
    OEM_BACKGROUND_AUTOSTART,
}

data class SurvivalCheckItem(
    val id: SurvivalCheckId,
    val title: String,
    val passed: Boolean,
    val advisoryOnly: Boolean = false,
    val statusLabel: String,
    val detail: String,
    val actionLabel: String,
    val secondaryActionLabel: String? = null,
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
    val overlayAutoReturnAllowed: Boolean = false,
    val backgroundPopupAllowed: Boolean = true,
    val autostartDetectedBySystem: Boolean? = null,
    val vendorFamily: OemVendorFamily = if (isXiaomiOrHyperOs) {
        OemVendorFamily.XIAOMI
    } else {
        OemVendorFamily.GENERIC_ANDROID
    },
    val dailySummaryChannelEnabled: Boolean = true,
    val urgentAlertsChannelEnabled: Boolean = true,
    val items: List<SurvivalCheckItem>,
) {
    val autoReturnFullyAllowed: Boolean
        get() = overlayAutoReturnAllowed && backgroundPopupAllowed

    val isAutostartSystemDetected: Boolean
        get() = autostartDetectedBySystem != null

    val passedCount: Int
        get() = items.count { it.passed }

    val totalCount: Int
        get() = items.size

    val coreAllPassed: Boolean
        get() = notificationsGranted && batteryOptimizationIgnored && exactAlarmAllowed
}

/**
 * 全品牌 Android（小米 / HyperOS、华为 / 荣耀、OPPO / 一加 / 真我、vivo / iQOO、三星、魅族及通用原生 Android）
 * 权限检测、自动回跳与后台稳定性安全跳转器。
 *
 * 所有厂商私有页面均使用 `try/catch (ActivityNotFoundException / SecurityException)` 安全回退至标准系统设置或应用详情页。
 */
object RomSurvivalChecker {

    private const val PREFS_NAME = "flame_rom_survival_prefs"
    private const val KEY_AUTOSTART_CONFIRMED = "autostart_confirmed"
    private const val MIUI_OP_AUTO_START = 10008
    private const val MIUI_OP_BACKGROUND_START_ACTIVITY = 10021

    private val XIAOMI_TOKENS = setOf("xiaomi", "redmi", "poco", "blackshark")
    private val HUAWEI_HONOR_TOKENS = setOf("huawei", "honor")
    private val OPPO_FAMILY_TOKENS = setOf("oppo", "oneplus", "realme")
    private val VIVO_FAMILY_TOKENS = setOf("vivo", "iqoo")
    private val SAMSUNG_TOKENS = setOf("samsung")
    private val MEIZU_TOKENS = setOf("meizu")

    fun detectVendorFamily(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        brand: String = Build.BRAND.orEmpty(),
    ): OemVendorFamily {
        val m = manufacturer.trim().lowercase(Locale.ROOT)
        val b = brand.trim().lowercase(Locale.ROOT)
        fun matches(tokens: Set<String>): Boolean = tokens.any { token ->
            m.contains(token) || b.contains(token)
        }
        return when {
            matches(XIAOMI_TOKENS) -> OemVendorFamily.XIAOMI
            matches(HUAWEI_HONOR_TOKENS) -> OemVendorFamily.HUAWEI_HONOR
            matches(OPPO_FAMILY_TOKENS) -> OemVendorFamily.OPPO_ONEPLUS_REALME
            matches(VIVO_FAMILY_TOKENS) -> OemVendorFamily.VIVO_IQOO
            matches(SAMSUNG_TOKENS) -> OemVendorFamily.SAMSUNG
            matches(MEIZU_TOKENS) -> OemVendorFamily.MEIZU
            else -> OemVendorFamily.GENERIC_ANDROID
        }
    }

    fun isXiaomiOrHyperOs(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        brand: String = Build.BRAND.orEmpty(),
    ): Boolean = detectVendorFamily(manufacturer = manufacturer, brand = brand) == OemVendorFamily.XIAOMI

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
        try {
            LocalNotifier(appContext).ensureChannels()
        } catch (_: Throwable) {
        }

        val notificationsGranted = hasNotificationPermission(appContext)
        val overlayAllowed = canDrawOverlays(appContext)
        val batteryIgnored = isIgnoringBatteryOptimizations(appContext)
        val exactAlarmAllowed = canScheduleExactAlarms(appContext)
        val vendor = detectVendorFamily()
        val backgroundPopupAllowed = if (vendor == OemVendorFamily.XIAOMI) {
            isXiaomiBackgroundPopupAllowed(appContext) ?: true
        } else {
            true
        }
        val detectedAutoStart = if (vendor == OemVendorFamily.XIAOMI) {
            isXiaomiAutoStartAllowed(appContext)
        } else {
            null
        }
        val manufacturerLabel = Build.MANUFACTURER?.takeIf { it.isNotBlank() } ?: "Android"
        val userConfirmedAutostart = isAutostartGuidanceConfirmed(appContext)
        val autostartEffective = when {
            detectedAutoStart != null -> detectedAutoStart
            vendor.requiresManualAutostartConfirmation -> userConfirmedAutostart
            else -> true
        }

        val dailyEnabled = isNotificationChannelEnabled(appContext, LocalNotifier.CHANNEL_DAILY_SUMMARY)
        val urgentEnabled = isNotificationChannelEnabled(appContext, LocalNotifier.CHANNEL_ALERTS_URGENT)

        return buildSnapshot(
            checkedAtEpochMs = nowEpochMs,
            isXiaomiOrHyperOs = vendor == OemVendorFamily.XIAOMI,
            manufacturer = manufacturerLabel,
            notificationsGranted = notificationsGranted,
            batteryOptimizationIgnored = batteryIgnored,
            exactAlarmAllowed = exactAlarmAllowed,
            autostartGuidanceConfirmed = autostartEffective,
            userConfirmedFlag = userConfirmedAutostart,
            dailySummaryChannelEnabled = dailyEnabled,
            urgentAlertsChannelEnabled = urgentEnabled,
            overlayAutoReturnAllowed = overlayAllowed,
            backgroundPopupAllowed = backgroundPopupAllowed,
            autostartDetectedBySystem = detectedAutoStart,
            vendorFamily = vendor,
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
        overlayAutoReturnAllowed: Boolean = false,
        backgroundPopupAllowed: Boolean = true,
        autostartDetectedBySystem: Boolean? = null,
        vendorFamily: OemVendorFamily = if (isXiaomiOrHyperOs) {
            OemVendorFamily.XIAOMI
        } else {
            detectVendorFamily(manufacturer = manufacturer, brand = manufacturer)
        },
    ): RomSurvivalSnapshot {
        val isSystemVerifiedAutostart = autostartDetectedBySystem != null
        val effectiveAutostartPassed = autostartDetectedBySystem ?: autostartGuidanceConfirmed
        val oemMeta = resolveOemBackgroundGuidance(
            vendorFamily = vendorFamily,
            manufacturer = manufacturer,
            isSystemVerifiedAutostart = isSystemVerifiedAutostart,
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            autostartAllowed = effectiveAutostartPassed,
        )
        val autoReturnReady = overlayAutoReturnAllowed && backgroundPopupAllowed
        val overlayDetail = when {
            autoReturnReady && vendorFamily.hasExtraBackgroundPopupSwitch ->
                "已同时允许「显示在其他应用上层」与「后台弹出界面」，在抖音点击「确认登录」后即可自动跳回本应用。"

            overlayAutoReturnAllowed && !backgroundPopupAllowed ->
                "悬浮窗已开启，但${vendorFamily.displayName}尚未放行「后台弹出界面」，确认登录后仍会被系统拦截。请立即点击开启「后台弹出界面」。"

            overlayAutoReturnAllowed ->
                "已允许「显示在其他应用上层」，在抖音点击「确认登录」后可自动跳回本应用，无需手动切换。"

            vendorFamily.hasExtraBackgroundPopupSwitch ->
                "Android 10+ 限制后台自动切回前台。请先开启「显示在其他应用上层」，再在${vendorFamily.displayName}「其他权限」中允许「后台弹出界面」，两项全开后抖音确认登录即可自动跳回。"

            else ->
                "Android 10+ 默认阻止后台应用自动回到前台。开启「显示在其他应用上层」后，在抖音点完「确认登录」即可自动跳回本应用。"
        }

        val items = listOf(
            SurvivalCheckItem(
                id = SurvivalCheckId.NOTIFICATIONS,
                title = "系统通知权限",
                passed = notificationsGranted,
                statusLabel = if (notificationsGranted) "已允许" else "未授权",
                detail = if (notificationsGranted) {
                    "已授权发送登录返回提醒、每日汇总与掉登录/待人工核对高优告警。"
                } else {
                    "用于掉登录、安全验证或待核对消息时第一时间提醒；未开悬浮窗时也可在确认登录后弹出返回通知。"
                },
                actionLabel = if (notificationsGranted) "通知设置" else "去授权",
            ),
            SurvivalCheckItem(
                id = SurvivalCheckId.OVERLAY_AUTO_RETURN,
                title = "显示在其他应用上层（自动跳回）",
                passed = autoReturnReady,
                advisoryOnly = true,
                statusLabel = when {
                    autoReturnReady -> "已开启"
                    overlayAutoReturnAllowed && !backgroundPopupAllowed -> "还差后台弹出"
                    else -> "待开启"
                },
                detail = overlayDetail,
                actionLabel = when {
                    !overlayAutoReturnAllowed -> "开启悬浮窗权限"
                    !backgroundPopupAllowed -> "开启后台弹出界面"
                    else -> "查看权限"
                },
                secondaryActionLabel = if (vendorFamily.hasExtraBackgroundPopupSwitch) {
                    if (overlayAutoReturnAllowed && !backgroundPopupAllowed) "查看悬浮窗" else "后台弹出界面"
                } else {
                    null
                },
            ),
            SurvivalCheckItem(
                id = SurvivalCheckId.BATTERY_OPTIMIZATION,
                title = "电池优化白名单 / 无限制省电",
                passed = batteryOptimizationIgnored,
                statusLabel = if (batteryOptimizationIgnored) "已加入白名单" else "受省电限制",
                detail = if (batteryOptimizationIgnored) {
                    "系统已放行电池优化，锁屏后定时唤醒与网络请求不易被省电策略中断。"
                } else {
                    "防止手机息屏省电后冻结后台服务或断网，导致到点不发火或发送中途失败。"
                },
                actionLabel = if (batteryOptimizationIgnored) "省电设置" else "设为无限制",
            ),
            SurvivalCheckItem(
                id = SurvivalCheckId.EXACT_ALARM,
                title = "精确闹钟调度权限",
                passed = exactAlarmAllowed,
                statusLabel = if (exactAlarmAllowed) "已允许" else "受限（已降级）",
                detail = if (exactAlarmAllowed) {
                    "已允许按设定的发火窗口精准注册闹钟唤醒，准点执行自动续火。"
                } else {
                    "Android 12+ 未授予精确闹钟时系统会推迟定时任务，可能延后几十分钟甚至错过当天窗口。"
                },
                actionLabel = if (exactAlarmAllowed) "查看权限" else "去授权",
            ),
            SurvivalCheckItem(
                id = SurvivalCheckId.OEM_BACKGROUND_AUTOSTART,
                title = oemMeta.title,
                passed = effectiveAutostartPassed,
                advisoryOnly = !vendorFamily.requiresManualAutostartConfirmation,
                statusLabel = when {
                    isSystemVerifiedAutostart && effectiveAutostartPassed -> "已开启"
                    isSystemVerifiedAutostart -> "未开启"
                    vendorFamily.requiresManualAutostartConfirmation && userConfirmedFlag -> "已确认开启"
                    vendorFamily.requiresManualAutostartConfirmation -> "待前往设置"
                    userConfirmedFlag -> "已核对"
                    else -> "按需核对（$manufacturer）"
                },
                detail = oemMeta.detail,
                actionLabel = oemMeta.primaryActionLabel,
                secondaryActionLabel = oemMeta.secondaryActionLabel,
            ),
        )

        return RomSurvivalSnapshot(
            checkedAtEpochMs = checkedAtEpochMs,
            isXiaomiOrHyperOs = vendorFamily == OemVendorFamily.XIAOMI,
            manufacturer = manufacturer,
            notificationsGranted = notificationsGranted,
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            exactAlarmAllowed = exactAlarmAllowed,
            autostartGuidanceConfirmed = effectiveAutostartPassed,
            overlayAutoReturnAllowed = overlayAutoReturnAllowed,
            backgroundPopupAllowed = backgroundPopupAllowed,
            autostartDetectedBySystem = autostartDetectedBySystem,
            vendorFamily = vendorFamily,
            dailySummaryChannelEnabled = dailySummaryChannelEnabled,
            urgentAlertsChannelEnabled = urgentAlertsChannelEnabled,
            items = items,
        )
    }

    internal data class OemBackgroundGuidance(
        val title: String,
        val detail: String,
        val confirmCheckboxLabel: String,
        val primaryActionLabel: String,
        val secondaryActionLabel: String?,
    )

    internal fun resolveOemBackgroundGuidance(
        vendorFamily: OemVendorFamily,
        manufacturer: String,
        isSystemVerifiedAutostart: Boolean = false,
        batteryOptimizationIgnored: Boolean = false,
        autostartAllowed: Boolean = false,
    ): OemBackgroundGuidance = when (vendorFamily) {
        OemVendorFamily.XIAOMI -> if (isSystemVerifiedAutostart) {
            OemBackgroundGuidance(
                title = "小米 / HyperOS 允许自启动",
                detail = if (autostartAllowed) {
                    if (batteryOptimizationIgnored) {
                        "系统已实时检测到「允许自启动」已开启，且电池「无限制」已放行，定时续火可在锁屏与重启后稳定唤醒。"
                    } else {
                        "系统已实时检测到「允许自启动」已开启；建议同时在电池设置保持「无限制」以防锁屏断网。"
                    }
                } else if (batteryOptimizationIgnored) {
                    "请点击下方前往开启「允许自启动」，返回本应用后将自动实时亮绿勾（上一步电池「无限制」已自动生效，无需重复设置省电策略）。"
                } else {
                    "请点击下方前往开启「允许自启动」，返回本应用后将自动实时亮绿勾；若电池优化尚未设为「无限制」，可点右侧补充放行。"
                },
                confirmCheckboxLabel = "我已在系统设置开启「允许自启动」",
                primaryActionLabel = if (autostartAllowed) "查看自启动" else "去开自启动",
                secondaryActionLabel = if (batteryOptimizationIgnored) null else "省电 / 神隐策略",
            )
        } else {
            OemBackgroundGuidance(
                title = "小米 / HyperOS 允许自启动",
                detail = if (batteryOptimizationIgnored) {
                    "当前系统未开放自启动状态读取，请开启「允许自启动」后勾选确认（上一步电池「无限制」已放行，无需重复设置省电策略）。"
                } else {
                    "请开启「允许自启动」并在「省电策略」保持「无限制」（勿选智能限制后台运行），防止手机重启或夜间被清理后无法自动发火。"
                },
                confirmCheckboxLabel = if (batteryOptimizationIgnored) {
                    "我已在系统设置开启「允许自启动」"
                } else {
                    "我已在系统设置开启「自启动」并设为省电「无限制」"
                },
                primaryActionLabel = "去开自启动",
                secondaryActionLabel = if (batteryOptimizationIgnored) null else "省电 / 神隐策略",
            )
        }

        OemVendorFamily.HUAWEI_HONOR -> OemBackgroundGuidance(
            title = "华为 / 荣耀应用启动管理",
            detail = "请在「应用启动管理」中关闭本应用的「自动管理」，手动打开「允许自启动」「允许关联启动」「允许后台活动」，防止锁屏清理导致定时续火中断。",
            confirmCheckboxLabel = "我已在应用启动管理中改为手动并允许后台活动",
            primaryActionLabel = "应用启动管理",
            secondaryActionLabel = "应用详情",
        )

        OemVendorFamily.OPPO_ONEPLUS_REALME -> OemBackgroundGuidance(
            title = "OPPO / 一加 / 真我自启动与后台运行",
            detail = "请在应用耗电与权限设置中开启「允许自启动」和「允许完全后台行为 / 允许后台运行」，避免 ColorOS 夜间冻结定时任务。",
            confirmCheckboxLabel = "我已开启「允许自启动」并放行后台运行",
            primaryActionLabel = "自启动与耗电",
            secondaryActionLabel = "应用详情",
        )

        OemVendorFamily.VIVO_IQOO -> OemBackgroundGuidance(
            title = "vivo / iQOO 自启动与后台高耗电",
            detail = "请在系统设置中开启「允许自启动」与「允许后台高耗电」，同时建议允许「后台弹出界面」，确保锁屏准点发火及抖音登录后自动跳回。",
            confirmCheckboxLabel = "我已开启自启动与「允许后台高耗电」",
            primaryActionLabel = "自启动与后台设置",
            secondaryActionLabel = "应用详情",
        )

        OemVendorFamily.SAMSUNG -> OemBackgroundGuidance(
            title = "三星 One UI 电池不受限制与从未休眠",
            detail = "请进入应用电池设置选择「不受限制」，并确认本应用未被加入「休眠 / 深度休眠应用程序」列表，避免定时闹钟失效。",
            confirmCheckboxLabel = "我已将电池设为「不受限制」且未加入休眠列表",
            primaryActionLabel = "电池与后台设置",
            secondaryActionLabel = "应用详情",
        )

        OemVendorFamily.MEIZU -> OemBackgroundGuidance(
            title = "魅族 Flyme 后台运行权限",
            detail = "请在手机管家的应用后台管理中将本应用设为「允许后台运行」，避免切出应用或锁屏后任务被终止。",
            confirmCheckboxLabel = "我已在后台管理中设为「允许后台运行」",
            primaryActionLabel = "后台管理设置",
            secondaryActionLabel = "应用详情",
        )

        OemVendorFamily.GENERIC_ANDROID -> OemBackgroundGuidance(
            title = "系统后台运行策略（$manufacturer）",
            detail = "当前机型（$manufacturer）完成上方「电池优化白名单」与「精确闹钟」后通常已足够；如系统额外提供「后台运行 / 自启动」开关，可点下方前往应用详情核对。",
            confirmCheckboxLabel = "我已核对系统后台运行未被限制",
            primaryActionLabel = "应用后台设置",
            secondaryActionLabel = null,
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

    fun canDrawOverlays(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return try {
            Settings.canDrawOverlays(context)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 读取小米 / HyperOS 私有权限 `OP_AUTO_START (10008)`（「允许自启动」）。
     * 在不支持该 Op 码的机型上返回 `null`，调用方回退至手动确认勾选。
     */
    fun isXiaomiAutoStartAllowed(context: Context): Boolean? =
        checkMiuiAppOp(context, MIUI_OP_AUTO_START)

    /**
     * 读取小米 / HyperOS 私有权限 `OP_BACKGROUND_START_ACTIVITY (10021)`（「后台弹出界面」）。
     * 在不支持该 Op 码的机型上返回 `null`，调用方保守放行不阻塞非小米设备。
     */
    fun isXiaomiBackgroundPopupAllowed(context: Context): Boolean? =
        checkMiuiAppOp(context, MIUI_OP_BACKGROUND_START_ACTIVITY)

    private fun checkMiuiAppOp(context: Context, opCode: Int): Boolean? {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return null
        return runCatching {
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            val mode = method.invoke(
                appOps,
                opCode,
                Process.myUid(),
                context.packageName,
            ) as? Int ?: return null
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrNull()
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

    fun openOverlayPermissionSettings(context: Context): Boolean {
        val withPackage = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        val generic = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        return launchWithAppDetailsFallback(context, listOf(withPackage, generic))
    }

    fun openBackgroundPopupSettings(context: Context): Boolean {
        val pkg = context.packageName
        val vendor = detectVendorFamily()
        val candidates = when (vendor) {
            OemVendorFamily.XIAOMI -> listOf(
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity",
                    )
                    putExtra("extra_pkgname", pkg)
                },
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    putExtra("extra_pkgname", pkg)
                },
            )

            OemVendorFamily.VIVO_IQOO -> listOf(
                Intent().apply {
                    component = ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity",
                    )
                    putExtra("packagename", pkg)
                },
                Intent().apply {
                    component = ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.safeguard.SoftPermissionDetailActivity",
                    )
                    putExtra("packagename", pkg)
                },
            )

            else -> listOf(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$pkg"),
                ),
            )
        }
        return launchWithAppDetailsFallback(context, candidates)
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

    fun openOemBackgroundSettings(context: Context): Boolean {
        val pkg = context.packageName
        val appLabel = resolveAppLabel(context)
        val candidates = when (detectVendorFamily()) {
            OemVendorFamily.XIAOMI -> listOf(
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
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity",
                    )
                    putExtra("extra_pkgname", pkg)
                },
            )

            OemVendorFamily.HUAWEI_HONOR -> listOf(
                Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    )
                },
                Intent().apply {
                    component = ComponentName(
                        "com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    )
                },
                Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity",
                    )
                },
            )

            OemVendorFamily.OPPO_ONEPLUS_REALME -> listOf(
                Intent().apply {
                    component = ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    )
                },
                Intent().apply {
                    component = ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity",
                    )
                },
                Intent().apply {
                    component = ComponentName(
                        "com.oplus.battery",
                        "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
                    )
                },
                Intent().apply {
                    component = ComponentName(
                        "com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity",
                    )
                },
            )

            OemVendorFamily.VIVO_IQOO -> listOf(
                Intent().apply {
                    component = ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    )
                },
                Intent().apply {
                    component = ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                    )
                },
                Intent().apply {
                    component = ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                    )
                },
            )

            OemVendorFamily.SAMSUNG -> listOf(
                Intent().apply {
                    component = ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.battery.ui.BatteryActivity",
                    )
                },
                Intent().apply {
                    component = ComponentName(
                        "com.samsung.android.sm_cn",
                        "com.samsung.android.sm.ui.battery.BatteryActivity",
                    )
                },
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )

            OemVendorFamily.MEIZU -> listOf(
                Intent("com.meizu.safe.security.SHOW_APPSEC").apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    putExtra("packageName", pkg)
                },
            )

            OemVendorFamily.GENERIC_ANDROID -> emptyList()
        }
        return launchWithAppDetailsFallback(context, candidates)
    }

    fun openMiuiAutostartSettings(context: Context): Boolean = openOemBackgroundSettings(context)

    fun openOemPowerSettings(context: Context): Boolean {
        val pkg = context.packageName
        val appLabel = resolveAppLabel(context)
        val candidates = when (detectVendorFamily()) {
            OemVendorFamily.XIAOMI -> listOf(
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

            else -> emptyList()
        }
        return launchWithAppDetailsFallback(context, candidates)
    }

    fun openMiuiPowerHideSettings(context: Context): Boolean = openOemPowerSettings(context)

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
