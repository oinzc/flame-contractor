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
    SURVIVAL("后台保活自检"),
    BACKUP("备份与跨机迁移"),
    DIAGNOSTICS("网页诊断与定位器维护"),
}

data class MainNavState(
    val selectedTab: MainTab = MainTab.TODAY,
    val isSettingsOpen: Boolean = false,
    val activeSettingsSection: SettingsSection? = null,
) {
    fun selectTab(tab: MainTab): MainNavState = copy(
        selectedTab = tab,
        isSettingsOpen = false,
        activeSettingsSection = null,
    )

    fun openSettings(section: SettingsSection? = null): MainNavState = copy(
        isSettingsOpen = true,
        activeSettingsSection = section,
    )

    fun navigateBack(): Pair<MainNavState, Boolean> = when {
        isSettingsOpen && activeSettingsSection != null ->
            copy(activeSettingsSection = null) to true

        isSettingsOpen ->
            copy(isSettingsOpen = false, activeSettingsSection = null) to true

        else -> this to false
    }

    fun applyLaunchIntent(
        category: String?,
        hasAccountTarget: Boolean,
    ): MainNavState {
        val cleanCategory = category?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            cleanCategory != null -> {
                val targetTab = when (cleanCategory) {
                    UrgentCategory.SPARK_DETERIORATING.name,
                    SHORTCUT_TARGETS,
                    -> MainTab.TARGETS

                    SHORTCUT_TEMPLATES -> MainTab.TEMPLATES
                    SHORTCUT_HISTORY -> MainTab.HISTORY
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
        )
    },
    restore = { saved ->
        val tabName = saved.getOrNull(0) as? String
        val isOpen = saved.getOrNull(1) as? Boolean ?: false
        val sectionName = (saved.getOrNull(2) as? String)?.takeIf { it.isNotBlank() }
        MainNavState(
            selectedTab = MainTab.entries.firstOrNull { it.name == tabName } ?: MainTab.TODAY,
            isSettingsOpen = isOpen,
            activeSettingsSection = if (isOpen) {
                SettingsSection.entries.firstOrNull { it.name == sectionName }
            } else {
                null
            },
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
