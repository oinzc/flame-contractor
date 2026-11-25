package io.github.oinzc.flamecontractor.ui.setup

import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.ui.settings.OemVendorFamily
import io.github.oinzc.flamecontractor.ui.settings.RomSurvivalChecker
import io.github.oinzc.flamecontractor.ui.settings.RomSurvivalSnapshot

enum class SetupItemCategory {
    SYSTEM_PERMISSION,
    ACCOUNT_WORKFLOW,
}

enum class SetupItemImportance {
    REQUIRED,
    RECOMMENDED,
}

enum class SetupItemId {
    NOTIFICATIONS,
    AUTO_RETURN_OVERLAY,
    BATTERY_OPTIMIZATION,
    EXACT_ALARM,
    OEM_BACKGROUND_AUTOSTART,
    DOUYIN_LOGIN,
    SELECT_TARGETS,
}

data class SetupChecklistItem(
    val id: SetupItemId,
    val category: SetupItemCategory,
    val importance: SetupItemImportance,
    val title: String,
    val whyNeeded: String,
    val howToHint: String,
    val completed: Boolean,
    val statusLabel: String,
    val primaryActionLabel: String,
    val secondaryActionLabel: String? = null,
    val needsManualConfirmation: Boolean = false,
    val confirmCheckboxLabel: String? = null,
)

data class SetupReadinessSnapshot(
    val items: List<SetupChecklistItem>,
    val vendorFamily: OemVendorFamily,
    val manufacturer: String,
    val checkedAtEpochMs: Long,
) {
    val totalCount: Int
        get() = items.size

    val completedCount: Int
        get() = items.count { it.completed }

    val requiredTotalCount: Int
        get() = items.count { it.importance == SetupItemImportance.REQUIRED }

    val requiredCompletedCount: Int
        get() = items.count { it.importance == SetupItemImportance.REQUIRED && it.completed }

    val allCompleted: Boolean
        get() = totalCount > 0 && completedCount == totalCount

    val allRequiredCompleted: Boolean
        get() = requiredTotalCount > 0 && requiredCompletedCount == requiredTotalCount

    val systemItems: List<SetupChecklistItem>
        get() = items.filter { it.category == SetupItemCategory.SYSTEM_PERMISSION }

    val workflowItems: List<SetupChecklistItem>
        get() = items.filter { it.category == SetupItemCategory.ACCOUNT_WORKFLOW }

    val nextPendingItem: SetupChecklistItem?
        get() = items.firstOrNull { !it.completed && it.importance == SetupItemImportance.REQUIRED }
            ?: items.firstOrNull { !it.completed }

    fun itemById(id: SetupItemId): SetupChecklistItem =
        items.first { it.id == id }
}

/**
 * 「新手分步引导」与「初始化与权限清单」共享的单一事实源构建器。
 * 统一汇总 5 项全品牌系统权限与 2 项账号业务就绪状态，保证两处状态完全一致。
 */
object SetupReadinessBuilder {

    fun isAccountLoggedIn(status: AccountStatus): Boolean = status !in setOf(
        AccountStatus.PENDING_LOGIN,
        AccountStatus.AUTH_EXPIRED,
        AccountStatus.VERIFICATION_REQUIRED,
    )

    fun build(
        survivalSnapshot: RomSurvivalSnapshot,
        accountStatus: AccountStatus,
        enabledTargetCount: Int,
    ): SetupReadinessSnapshot {
        val vendor = survivalSnapshot.vendorFamily
        val manufacturer = survivalSnapshot.manufacturer.ifBlank { "Android" }
        val oemMeta = RomSurvivalChecker.resolveOemBackgroundGuidance(vendor, manufacturer)
        val loggedIn = isAccountLoggedIn(accountStatus)
        val targetsReady = enabledTargetCount > 0

        val overlayHowTo = if (vendor.hasExtraBackgroundPopupSwitch) {
            "先点击「开启悬浮窗权限」允许「显示在其他应用上层」，再点击「后台弹出界面」确认已开启（${vendor.displayName}需两项都放行才能稳定自动跳回）。"
        } else {
            "点击「开启悬浮窗权限」，在系统列表中找到「抖音续火工」并打开「允许显示在其他应用上层」。"
        }

        val oemWhyNeeded = when (vendor) {
            OemVendorFamily.XIAOMI ->
                "小米 / HyperOS 默认拦截应用自启动并在锁屏后触发神隐后台冻结，未放行时夜间定时续火极易被系统终止。"

            OemVendorFamily.HUAWEI_HONOR ->
                "华为 / 荣耀的自动启动管理会在锁屏后回收后台任务，改为手动并放行自启动与后台活动才能准点发火。"

            OemVendorFamily.OPPO_ONEPLUS_REALME ->
                "ColorOS / Realme UI 默认禁止应用自启动并限制后台行为，开启自启动与完全后台运行可避免定时失效。"

            OemVendorFamily.VIVO_IQOO ->
                "OriginOS / FuntouchOS 会拦截后台高耗电和自启动，开启这两项后才能保证每日自动续火不断档。"

            OemVendorFamily.SAMSUNG ->
                "三星 One UI 会自动将低频打开的应用移入休眠/深度休眠列表，导致闹钟与后台调度被暂停。"

            OemVendorFamily.MEIZU ->
                "魅族 Flyme 默认在切出应用后快速清理后台进程，需设为「允许后台运行」以保障准点续火。"

            OemVendorFamily.GENERIC_ANDROID ->
                "部分 Android 定制系统会额外提供后台自启动开关；若前面已放行电池与精确闹钟，本项仅需按需核对。"
        }

        val loginStatusLabel = when (accountStatus) {
            AccountStatus.PENDING_LOGIN -> "待登录"
            AccountStatus.AUTH_EXPIRED -> "登录已失效"
            AccountStatus.VERIFICATION_REQUIRED -> "需安全验证"
            AccountStatus.PAUSED -> "已登录（已暂停）"
            AccountStatus.COOLDOWN -> "已登录（熔断中）"
            AccountStatus.DOM_BROKEN -> "已登录（待体检）"
            AccountStatus.HEALTHY,
            AccountStatus.RUNNING,
            -> "已登录"
        }

        val loginActionLabel = when (accountStatus) {
            AccountStatus.PENDING_LOGIN -> "去登录抖音"
            AccountStatus.AUTH_EXPIRED -> "重新登录抖音"
            AccountStatus.VERIFICATION_REQUIRED -> "去完成验证"
            else -> "重新校验 / 换号"
        }

        val items = listOf(
            SetupChecklistItem(
                id = SetupItemId.NOTIFICATIONS,
                category = SetupItemCategory.SYSTEM_PERMISSION,
                importance = SetupItemImportance.REQUIRED,
                title = "系统通知权限",
                whyNeeded = "抖音掉登录、触发安全验证或发火结果未明时第一时间告警提醒；未开悬浮窗时也可在确认登录后通过横幅快速返回本应用。",
                howToHint = "点击下方按钮允许通知，并确认系统未屏蔽本应用的通知横幅与告警通道。",
                completed = survivalSnapshot.notificationsGranted,
                statusLabel = if (survivalSnapshot.notificationsGranted) "已允许" else "未授权",
                primaryActionLabel = if (survivalSnapshot.notificationsGranted) "通知设置" else "去开启通知",
            ),
            SetupChecklistItem(
                id = SetupItemId.AUTO_RETURN_OVERLAY,
                category = SetupItemCategory.SYSTEM_PERMISSION,
                importance = SetupItemImportance.RECOMMENDED,
                title = "抖音确认后自动跳回（悬浮窗权限）",
                whyNeeded = "Android 10 及以上系统默认阻止后台应用切回前台。允许「显示在其他应用上层」后，在抖音点击「确认登录」即可自动跳回本应用，无需手动切屏。",
                howToHint = overlayHowTo,
                completed = survivalSnapshot.overlayAutoReturnAllowed,
                statusLabel = if (survivalSnapshot.overlayAutoReturnAllowed) "已开启" else "待开启",
                primaryActionLabel = if (survivalSnapshot.overlayAutoReturnAllowed) "查看悬浮窗权限" else "开启悬浮窗权限",
                secondaryActionLabel = if (vendor.hasExtraBackgroundPopupSwitch) "后台弹出界面" else null,
            ),
            SetupChecklistItem(
                id = SetupItemId.BATTERY_OPTIMIZATION,
                category = SetupItemCategory.SYSTEM_PERMISSION,
                importance = SetupItemImportance.REQUIRED,
                title = "电池优化白名单 / 无限制",
                whyNeeded = "手机息屏或长时间待机时，系统省电策略会冻结后台进程与网络，容易导致到点不发火或发火中途超时失败。",
                howToHint = "点击下方按钮，在系统弹窗选择「允许」，或在电池设置中将本应用改为「无限制 / 不优化」。",
                completed = survivalSnapshot.batteryOptimizationIgnored,
                statusLabel = if (survivalSnapshot.batteryOptimizationIgnored) "已放行" else "受省电限制",
                primaryActionLabel = if (survivalSnapshot.batteryOptimizationIgnored) "查看省电设置" else "设为无限制",
            ),
            SetupChecklistItem(
                id = SetupItemId.EXACT_ALARM,
                category = SetupItemCategory.SYSTEM_PERMISSION,
                importance = SetupItemImportance.REQUIRED,
                title = "精确闹钟调度权限",
                whyNeeded = "Android 12+ 未授予精确闹钟时，系统会合并并推迟定时任务，可能延迟数十分钟甚至错过当天续火窗口。",
                howToHint = "点击下方按钮，在系统设置中打开「允许设置闹钟和提醒」。",
                completed = survivalSnapshot.exactAlarmAllowed,
                statusLabel = if (survivalSnapshot.exactAlarmAllowed) "已允许" else "受限（已降级）",
                primaryActionLabel = if (survivalSnapshot.exactAlarmAllowed) "查看闹钟权限" else "去开启精确闹钟",
            ),
            SetupChecklistItem(
                id = SetupItemId.OEM_BACKGROUND_AUTOSTART,
                category = SetupItemCategory.SYSTEM_PERMISSION,
                importance = if (vendor.requiresManualAutostartConfirmation) {
                    SetupItemImportance.REQUIRED
                } else {
                    SetupItemImportance.RECOMMENDED
                },
                title = oemMeta.title,
                whyNeeded = oemWhyNeeded,
                howToHint = oemMeta.detail,
                completed = survivalSnapshot.autostartGuidanceConfirmed,
                statusLabel = when {
                    vendor.requiresManualAutostartConfirmation && survivalSnapshot.autostartGuidanceConfirmed -> "已确认开启"
                    vendor.requiresManualAutostartConfirmation -> "待手动确认"
                    else -> "按需核对"
                },
                primaryActionLabel = oemMeta.primaryActionLabel,
                secondaryActionLabel = oemMeta.secondaryActionLabel,
                needsManualConfirmation = vendor.requiresManualAutostartConfirmation,
                confirmCheckboxLabel = oemMeta.confirmCheckboxLabel,
            ),
            SetupChecklistItem(
                id = SetupItemId.DOUYIN_LOGIN,
                category = SetupItemCategory.ACCOUNT_WORKFLOW,
                importance = SetupItemImportance.REQUIRED,
                title = "绑定抖音账号",
                whyNeeded = "所有消息均由本机内置浏览器沙盒直接发送，不经任何第三方服务器。绑定一次抖音会话后，即可自动同步账号昵称并开始每日续火。",
                howToHint = "点击「去登录抖音」，支持「一键跳转本机抖音确认」或「另一台设备扫码」；登录成功后会自动将默认占位名更新为绑定的抖音昵称。",
                completed = loggedIn,
                statusLabel = loginStatusLabel,
                primaryActionLabel = loginActionLabel,
            ),
            SetupChecklistItem(
                id = SetupItemId.SELECT_TARGETS,
                category = SetupItemCategory.ACCOUNT_WORKFLOW,
                importance = SetupItemImportance.REQUIRED,
                title = "扫描并启用续火好友",
                whyNeeded = "默认遵循「宁可不发不错发」安全原则，不会自动给任何人发消息；只有你主动勾选并启用的好友或群聊才会纳入每日自动续火。",
                howToHint = "前往「好友」页点击扫描会话列表，勾选需要续火的联系人并开启开关即可。",
                completed = targetsReady,
                statusLabel = if (targetsReady) "已启用 $enabledTargetCount 位" else "未选择好友",
                primaryActionLabel = if (targetsReady) "管理续火好友" else "去扫描选择好友",
            ),
        )

        return SetupReadinessSnapshot(
            items = items,
            vendorFamily = vendor,
            manufacturer = manufacturer,
            checkedAtEpochMs = survivalSnapshot.checkedAtEpochMs,
        )
    }
}
