package io.github.oinzc.flamecontractor.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens

/**
 * 手机 / 平板自适应导航外壳：
 * - `< 600.dp` 采用底部 4 Tab 悬浮圆角胶囊底栏，在进入二级设置或全屏编辑器时自动淡出隐藏；
 * - `>= 600.dp` 采用左侧常驻简洁 [NavigationRail]（顶部账号切换 + 中部 4 Tab + 底部设置齿轮）。
 */
@Composable
fun AdaptiveNavShell(
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    todayAlertBadge: Boolean = false,
    hideNavigationChrome: Boolean = false,
    currentAccount: AccountEntity? = null,
    totalAccountCount: Int = 1,
    otherAccountAlertBadge: Boolean = false,
    onOpenAccountSwitcher: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    todayContent: @Composable () -> Unit,
    targetsContent: @Composable () -> Unit,
    templatesContent: @Composable () -> Unit,
    historyContent: @Composable () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val activeContent: @Composable () -> Unit = {
        when (selectedTab) {
            MainTab.TODAY -> todayContent()
            MainTab.TARGETS -> targetsContent()
            MainTab.TEMPLATES -> templatesContent()
            MainTab.HISTORY -> historyContent()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(palette.canvas),
    ) {
        val useRail = maxWidth >= 600.dp
        if (useRail) {
            val endInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.End)
            Row(modifier = Modifier.fillMaxSize()) {
                if (!hideNavigationChrome) {
                    FlameWideNavigationRail(
                        selectedTab = selectedTab,
                        onSelectTab = onSelectTab,
                        todayAlertBadge = todayAlertBadge,
                        currentAccount = currentAccount,
                        totalAccountCount = totalAccountCount,
                        otherAccountAlertBadge = otherAccountAlertBadge,
                        onOpenAccountSwitcher = onOpenAccountSwitcher,
                        onOpenSettings = onOpenSettings,
                    )
                    VerticalDivider(thickness = 1.dp, color = palette.hairline)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(palette.canvas)
                        .windowInsetsPadding(endInsets),
                ) {
                    activeContent()
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.canvas),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    activeContent()
                }

                AnimatedVisibility(
                    visible = !hideNavigationChrome,
                    enter = fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                        slideInVertically(
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                            initialOffsetY = { it / 2 },
                        ),
                    exit = fadeOut(animationSpec = tween(150, easing = FastOutSlowInEasing)) +
                        slideOutVertically(
                            animationSpec = tween(170, easing = FastOutSlowInEasing),
                            targetOffsetY = { it / 2 },
                        ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
                ) {
                    FlameFloatingPillDock(
                        selectedTab = selectedTab,
                        onSelectTab = onSelectTab,
                        todayAlertBadge = todayAlertBadge,
                    )
                }
            }
        }
    }
}

@Composable
private fun FlameFloatingPillDock(
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    todayAlertBadge: Boolean,
) {
    val palette = FlameThemeTokens.palette
    val isDark = FlameThemeTokens.isDark

    Surface(
        modifier = Modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = palette.dockContainer,
        contentColor = palette.ink,
        border = BorderStroke(1.dp, palette.hairline),
        shadowElevation = if (isDark) 10.dp else 8.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MainTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val itemContainer by animateColorAsState(
                    targetValue = if (selected) palette.dockActiveContainer else Color.Transparent,
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    label = "dock_item_bg_${tab.name}",
                )
                val targetContentColor = when {
                    selected && FlameThemeTokens.isDynamicColorActive -> palette.monoCtaContainer
                    selected -> palette.ink
                    else -> palette.inkTertiary
                }
                val itemTint by animateColorAsState(
                    targetValue = targetContentColor,
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    label = "dock_item_fg_${tab.name}",
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(itemContainer)
                        .clickable(
                            role = Role.Tab,
                            onClick = { onSelectTab(tab) },
                        )
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TabIconWithBadge(
                        tab = tab,
                        selected = selected,
                        tint = itemTint,
                        showBadge = tab == MainTab.TODAY && todayAlertBadge,
                        badgeRingColor = if (selected) palette.dockActiveContainer else palette.dockContainer,
                    )
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = itemTint,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun FlameWideNavigationRail(
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    todayAlertBadge: Boolean,
    currentAccount: AccountEntity?,
    totalAccountCount: Int,
    otherAccountAlertBadge: Boolean,
    onOpenAccountSwitcher: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val statusStyle = accountStatusStyle(currentAccount?.status ?: AccountStatus.PENDING_LOGIN)

    NavigationRail(
        containerColor = palette.surface,
        contentColor = palette.ink,
        windowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Vertical + WindowInsetsSides.Start,
        ),
        header = {
            if (onOpenAccountSwitcher != null) {
                Column(
                    modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box {
                        Surface(
                            onClick = onOpenAccountSwitcher,
                            modifier = Modifier.size(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = palette.surfaceSubtle,
                            contentColor = palette.ink,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (otherAccountAlertBadge) semantics.dangerBorder else palette.hairline,
                            ),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.SwitchAccount,
                                    contentDescription = "切换或管理账号",
                                    modifier = Modifier.size(20.dp),
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(6.dp)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(statusStyle.dotColor),
                                )
                            }
                        }
                        if (otherAccountAlertBadge) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 2.dp, y = (-2).dp)
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(semantics.danger)
                                    .border(1.5.dp, palette.surface, CircleShape),
                            )
                        }
                    }
                    Text(
                        text = currentAccount?.displayName
                            ?.takeIf { it.isNotBlank() }
                            ?: if (totalAccountCount > 1) "共${totalAccountCount}号" else "账号",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 64.dp)
                            .padding(horizontal = 4.dp),
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
            }
        },
    ) {
        MainTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val selectedTint = if (FlameThemeTokens.isDynamicColorActive) {
                palette.monoCtaContainer
            } else {
                palette.ink
            }
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectTab(tab) },
                icon = {
                    TabIconWithBadge(
                        tab = tab,
                        selected = selected,
                        tint = if (selected) selectedTint else palette.inkTertiary,
                        showBadge = tab == MainTab.TODAY && todayAlertBadge,
                        badgeRingColor = if (selected) palette.dockActiveContainer else palette.surface,
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        maxLines = 1,
                        softWrap = false,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = selectedTint,
                    selectedTextColor = selectedTint,
                    indicatorColor = palette.dockActiveContainer,
                    unselectedIconColor = palette.inkTertiary,
                    unselectedTextColor = palette.inkTertiary,
                ),
            )
        }

        if (onOpenSettings != null) {
            Spacer(modifier = Modifier.weight(1f))
            NavigationRailItem(
                selected = false,
                onClick = onOpenSettings,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "设置与维护",
                        modifier = Modifier.size(21.dp),
                    )
                },
                label = {
                    Text(
                        text = "设置",
                        maxLines = 1,
                        softWrap = false,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationRailItemDefaults.colors(
                    unselectedIconColor = palette.inkSecondary,
                    unselectedTextColor = palette.inkSecondary,
                ),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TabIconWithBadge(
    tab: MainTab,
    selected: Boolean,
    tint: Color,
    showBadge: Boolean,
    badgeRingColor: Color,
) {
    val semantics = FlameThemeTokens.semanticColors
    val icon = if (selected) tab.selectedIcon else tab.unselectedIcon
    Box(contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        if (showBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-2).dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(semantics.danger)
                    .border(1.5.dp, badgeRingColor, CircleShape),
            )
        }
    }
}
