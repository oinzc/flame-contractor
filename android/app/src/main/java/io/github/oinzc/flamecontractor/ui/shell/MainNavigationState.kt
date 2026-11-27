package io.github.oinzc.flamecontractor.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.oinzc.flamecontractor.core.notify.UrgentCategory
import io.github.oinzc.flamecontractor.core.theme.UiDisplayMode
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID

enum class MainTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    TODAY(
        label = "今日",
        selectedIcon = Icons.Filled.LocalFireDepartment,
        unselectedIcon = Icons.Outlined.LocalFireDepartment,
    ),
    TARGETS(
        label = "好友",
        selectedIcon = Icons.Filled.Group,
        unselectedIcon = Icons.Outlined.Group,
    ),
    TEMPLATES(
        label = "模板",
        selectedIcon = Icons.Filled.Forum,
        unselectedIcon = Icons.Outlined.Forum,
    ),
    HISTORY(
        label = "记录",
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History,
    ),
}

enum class SettingsSection(val title: String) {
    APPEARANCE("外观与主题"),
    SCHEDULE("自动调度与定时扫描"),
    NOTIFICATIONS("通知与 Bark 推送"),
    SURVIVAL("初始化与权限清单"),
    BACKUP("备份与跨机迁移"),
    DIAGNOSTICS("网页诊断与定位器维护"),
    UPDATES("应用版本与更新"),
    ABOUT("问题反馈与建议"),
}

data class MainNavState(
    val selectedTab: MainTab = MainTab.TODAY,
    val isSettingsOpen: Boolean = false,
    val activeSettingsSection: SettingsSection? = null,
    val returnToTabOnBack: Boolean = false,
) {
    fun selectTab(
        tab: MainTab,
        displayMode: UiDisplayMode = UiDisplayMode.STANDARD,
    ): MainNavState {
        val resolvedTab = if (displayMode == UiDisplayMode.SIMPLE) {
            when (tab) {
                MainTab.TODAY,
                MainTab.TARGETS,
                -> tab

                MainTab.TEMPLATES,
                MainTab.HISTORY,
                -> MainTab.TODAY
            }
        } else {
            tab
        }
        return copy(
            selectedTab = resolvedTab,
            isSettingsOpen = false,
            activeSettingsSection = null,
            returnToTabOnBack = false,
        )
    }

    fun convergeForSimpleMode(): MainNavState = copy(
        selectedTab = MainTab.TODAY,
    )

    fun openSettings(
        section: SettingsSection? = null,
        returnToTabOnBack: Boolean = false,
    ): MainNavState = copy(
        isSettingsOpen = true,
        activeSettingsSection = section,
        returnToTabOnBack = section != null && returnToTabOnBack,
    )

    fun navigateBack(
        displayMode: UiDisplayMode = UiDisplayMode.STANDARD,
        isSplitSettingsPane: Boolean = false,
    ): Pair<MainNavState, Boolean> = when {
        isSettingsOpen && isSplitSettingsPane ->
            copy(
                isSettingsOpen = false,
                activeSettingsSection = null,
                returnToTabOnBack = false,
            ) to true

        isSettingsOpen && activeSettingsSection != null && returnToTabOnBack ->
            copy(
                isSettingsOpen = false,
                activeSettingsSection = null,
                returnToTabOnBack = false,
            ) to true

        isSettingsOpen && activeSettingsSection != null ->
            copy(activeSettingsSection = null, returnToTabOnBack = false) to true

        isSettingsOpen ->
            copy(
                isSettingsOpen = false,
                activeSettingsSection = null,
                returnToTabOnBack = false,
            ) to true

        displayMode == UiDisplayMode.SIMPLE && selectedTab != MainTab.TODAY ->
            copy(selectedTab = MainTab.TODAY) to true

        else -> this to false
    }

    fun applyLaunchIntent(
        category: String?,
        hasAccountTarget: Boolean,
        displayMode: UiDisplayMode = UiDisplayMode.STANDARD,
    ): MainNavState {
        val cleanCategory = category?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            cleanCategory != null -> {
                val targetTab = when (cleanCategory) {
                    UrgentCategory.SPARK_DETERIORATING.name,
                    SHORTCUT_TARGETS,
                    -> MainTab.TARGETS

                    SHORTCUT_TEMPLATES -> if (displayMode == UiDisplayMode.SIMPLE) {
                        MainTab.TODAY
                    } else {
                        MainTab.TEMPLATES
                    }

                    SHORTCUT_HISTORY -> if (displayMode == UiDisplayMode.SIMPLE) {
                        MainTab.TODAY
                    } else {
                        MainTab.HISTORY
                    }

                    SHORTCUT_TODAY -> MainTab.TODAY
                    else -> MainTab.TODAY
                }
                MainNavState(
                    selectedTab = targetTab,
                    isSettingsOpen = false,
                    activeSettingsSection = null,
                )
            }

            hasAccountTarget -> MainNavState(
                selectedTab = MainTab.TODAY,
                isSettingsOpen = false,
                activeSettingsSection = null,
            )

            else -> this
        }
    }

    companion object {
        const val SHORTCUT_TODAY = "shortcut_today"
        const val SHORTCUT_TARGETS = "shortcut_targets"
        const val SHORTCUT_TEMPLATES = "shortcut_templates"
        const val SHORTCUT_HISTORY = "shortcut_history"
    }
}

val MainNavStateSaver: Saver<MainNavState, Any> = listSaver(
    save = { state ->
        listOf(
            state.selectedTab.name,
            state.isSettingsOpen,
            state.activeSettingsSection?.name.orEmpty(),
            state.returnToTabOnBack,
        )
    },
    restore = { saved ->
        val tabName = saved.getOrNull(0) as? String
        val isOpen = saved.getOrNull(1) as? Boolean ?: false
        val sectionName = (saved.getOrNull(2) as? String)?.takeIf { it.isNotBlank() }
        val section = if (isOpen) {
            SettingsSection.entries.firstOrNull { it.name == sectionName }
        } else {
            null
        }
        val directReturn = (saved.getOrNull(3) as? Boolean ?: false) && section != null
        MainNavState(
            selectedTab = MainTab.entries.firstOrNull { it.name == tabName } ?: MainTab.TODAY,
            isSettingsOpen = isOpen,
            activeSettingsSection = section,
            returnToTabOnBack = directReturn,
        )
    },
)

data class AccountDeleteNotice(
    val canDelete: Boolean,
    val detailMessage: String,
)

fun buildAccountDeleteNotice(
    accountId: String,
    totalAccountCount: Int,
): AccountDeleteNotice {
    if (totalAccountCount <= 1) {
        return AccountDeleteNotice(
            canDelete = false,
            detailMessage = "至少保留 1 个抖音账号；新增账号后可按需删除其他账号。",
        )
    }
    val normalizedId = accountId.trim()
    val isDefaultAccount = normalizedId.isEmpty() || normalizedId == DEFAULT_ACCOUNT_ID
    return if (isDefaultAccount) {
        AccountDeleteNotice(
            canDelete = true,
            detailMessage = "删除后将立即清空该默认账号下的所有好友、模板、发送历史与登录态 Cookie，并回收无引用本地图片；系统默认 WebView Profile（default）会继续保留，不会销毁。此操作不可恢复。",
        )
    } else {
        AccountDeleteNotice(
            canDelete = true,
            detailMessage = "删除后将立即清空该账号下的所有好友、模板、发送历史与登录态 Cookie，并回收无引用本地图片；该账号的独立 WebView Profile 将在下次冷启动彻底销毁。此操作不可恢复。",
        )
    }
}

/**
 * 双栏 Master-Detail 设置模式下，当 `activeSettingsSection == null` 时解析右侧默认展示的设置子页，
 * 避免右窗格出现空白：
 * - `DOM_BROKEN` 时优先直达「网页诊断与定位器维护」；
 * - 核心后台权限未就绪时优先直达「初始化与权限清单」；
 * - 其余常规状态默认展示「外观与主题」。
 */
fun resolveDefaultSplitSettingsSection(
    accountStatus: AccountStatus,
    coreSurvivalReady: Boolean,
): SettingsSection = when {
    accountStatus == AccountStatus.DOM_BROKEN -> SettingsSection.DIAGNOSTICS
    !coreSurvivalReady -> SettingsSection.SURVIVAL
    else -> SettingsSection.APPEARANCE
}
