package io.github.oinzc.flamecontractor.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens

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
    SETTINGS(
        label = "设置",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    ),
}

/**
 * 手机 / 平板自适应导航外壳：
 * - `< 600.dp` 采用底部 5 栏 [NavigationBar]；
 * - `>= 600.dp` 采用左侧常驻 [NavigationRail]。
 */
@Composable
fun AdaptiveNavShell(
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    todayContent: @Composable () -> Unit,
    targetsContent: @Composable () -> Unit,
    templatesContent: @Composable () -> Unit,
    historyContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    todayAlertBadge: Boolean = false,
    currentAccount: AccountEntity? = null,
    totalAccountCount: Int = 1,
    otherAccountAlertBadge: Boolean = false,
    onOpenAccountSwitcher: (() -> Unit)? = null,
) {
    val activeContent: @Composable () -> Unit = {
        when (selectedTab) {
            MainTab.TODAY -> todayContent()
            MainTab.TARGETS -> targetsContent()
            MainTab.TEMPLATES -> templatesContent()
            MainTab.HISTORY -> historyContent()
            MainTab.SETTINGS -> settingsContent()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val useRail = maxWidth >= 600.dp
        if (useRail) {
            val contentInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End,
            )
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    windowInsets = WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Vertical + WindowInsetsSides.Start,
                    ),
                    header = {
                        Box(
                            modifier = Modifier
                                .padding(top = 12.dp, bottom = 8.dp)
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LocalFireDepartment,
                                contentDescription = "抖音续火工",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    },
                ) {
                    MainTab.entries.forEach { tab ->
                        val selected = tab == selectedTab
                        NavigationRailItem(
                            selected = selected,
                            onClick = { onSelectTab(tab) },
                            icon = {
                                TabIconWithBadge(
                                    tab = tab,
                                    selected = selected,
                                    showBadge = tab == MainTab.TODAY && todayAlertBadge,
                                )
                            },
                            label = { Text(text = tab.label, maxLines = 1) },
                            alwaysShowLabel = true,
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(contentInsets)
                            .consumeWindowInsets(contentInsets),
                    ) {
                        if (onOpenAccountSwitcher != null) {
                            CompactAccountSwitcherBar(
                                currentAccount = currentAccount,
                                totalAccountCount = totalAccountCount,
                                otherAccountAlertBadge = otherAccountAlertBadge,
                                onClick = onOpenAccountSwitcher,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 6.dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            activeContent()
                        }
                    }
                }
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        MainTab.entries.forEach { tab ->
                            val selected = tab == selectedTab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { onSelectTab(tab) },
                                icon = {
                                    TabIconWithBadge(
                                        tab = tab,
                                        selected = selected,
                                        showBadge = tab == MainTab.TODAY && todayAlertBadge,
                                    )
                                },
                                label = { Text(text = tab.label, maxLines = 1) },
                                alwaysShowLabel = true,
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                ) {
                    if (onOpenAccountSwitcher != null) {
                        CompactAccountSwitcherBar(
                            currentAccount = currentAccount,
                            totalAccountCount = totalAccountCount,
                            otherAccountAlertBadge = otherAccountAlertBadge,
                            onClick = onOpenAccountSwitcher,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        activeContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactAccountSwitcherBar(
    currentAccount: AccountEntity?,
    totalAccountCount: Int,
    otherAccountAlertBadge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = currentAccount?.status ?: AccountStatus.PENDING_LOGIN
    val style = accountStatusStyle(status)
    val semantics = FlameThemeTokens.semanticColors
    val enabledText = if (currentAccount?.enabled == false) "已停用" else "已启用"

    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = if (otherAccountAlertBadge) {
                semantics.danger.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(style.dotColor),
            )
            Text(
                text = currentAccount?.displayName ?: "默认抖音号",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = style.bg,
                contentColor = style.fg,
            ) {
                Text(
                    text = style.label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            Text(
                text = enabledText,
                style = MaterialTheme.typography.labelSmall,
                color = if (currentAccount?.enabled == false) {
                    semantics.danger
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.weight(1f))
            if (otherAccountAlertBadge) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = semantics.dangerContainer,
                    contentColor = semantics.onDangerContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(semantics.danger),
                        )
                        Text(
                            text = "他号异常",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            } else if (totalAccountCount > 1) {
                Text(
                    text = "共${totalAccountCount}号",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "切换",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "切换或管理账号",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun TabIconWithBadge(
    tab: MainTab,
    selected: Boolean,
    showBadge: Boolean,
) {
    val icon = if (selected) tab.selectedIcon else tab.unselectedIcon
    if (showBadge) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = FlameThemeTokens.semanticColors.danger,
                )
            },
        ) {
            Icon(imageVector = icon, contentDescription = tab.label)
        }
    } else {
        Icon(imageVector = icon, contentDescription = tab.label)
    }
}
