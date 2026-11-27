package io.github.oinzc.flamecontractor.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.ui.components.FLAME_COMPACT_RAIL_WIDTH_DP
import io.github.oinzc.flamecontractor.ui.components.FLAME_NAVIGATION_RAIL_WIDTH_DP
import io.github.oinzc.flamecontractor.ui.components.FLAME_PERMANENT_SIDEBAR_WIDTH_DP
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.FlameNavChromeKind
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.LocalFlameChromeHandlesGlobalNav
import io.github.oinzc.flamecontractor.ui.components.LocalFlameWindowLayout
import io.github.oinzc.flamecontractor.ui.components.flamePressScale
import io.github.oinzc.flamecontractor.ui.components.performFlameLightTick
import io.github.oinzc.flamecontractor.ui.components.rememberReducedMotion
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens

private sealed interface ShellContentKey {
    data class Tab(val tab: MainTab) : ShellContentKey
    data object EmbeddedSettings : ShellContentKey
}

/**
 * 四档宽×高自适应导航外壳：
 * - [FlameNavChromeKind.BOTTOM_DOCK]：手机竖屏底部 4 Tab 悬浮圆角胶囊底栏；
 * - [FlameNavChromeKind.COMPACT_RAIL]：手机横屏 `62.dp` 可垂直滚动纯图标 Rail（避让 `safeDrawing`）；
 * - [FlameNavChromeKind.NAVIGATION_RAIL]：平板竖屏 `76.dp` 精致 Rail（含极简入口 / 反馈 / 设置）；
 * - [FlameNavChromeKind.PERMANENT_SIDEBAR]：平板横屏 `216.dp` 常驻展开侧边栏（账号切换卡 + 4 Tab 徽标/计数 + 底部工具区）。
 *
 * 在横屏标准模式下，当 [isSettingsSelected] 且提供了 [settingsContent] 时，保留左侧导航壳层并直接在右侧主画布渲染设置页。
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
    enabledTargetCount: Int? = null,
    isSettingsSelected: Boolean = false,
    appVersionLabel: String? = null,
    onOpenAccountSwitcher: (() -> Unit)? = null,
    onSwitchToSimpleMode: (() -> Unit)? = null,
    onOpenFeedback: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    settingsContent: (@Composable () -> Unit)? = null,
    todayContent: @Composable () -> Unit,
    targetsContent: @Composable () -> Unit,
    templatesContent: @Composable () -> Unit,
    historyContent: @Composable () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val reducedMotion = rememberReducedMotion()
    val windowLayout = LocalFlameWindowLayout.current
    val navChromeKind = windowLayout.navChromeKind
    val chromeHandlesGlobalNav = !hideNavigationChrome && navChromeKind != FlameNavChromeKind.BOTTOM_DOCK
    val showEmbeddedSettings = !hideNavigationChrome && isSettingsSelected && settingsContent != null
    val contentKey: ShellContentKey = if (showEmbeddedSettings) {
        ShellContentKey.EmbeddedSettings
    } else {
        ShellContentKey.Tab(selectedTab)
    }

    val activeContent: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalFlameChromeHandlesGlobalNav provides chromeHandlesGlobalNav,
        ) {
            AnimatedContent(
                targetState = contentKey,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (reducedMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (fadeIn(
                            animationSpec = tween(
                                durationMillis = FlameMotionSpec.TAB_ENTER_MS,
                                easing = FlameMotionSpec.easeOut,
                            ),
                        ) + scaleIn(
                            initialScale = FlameMotionSpec.TAB_INITIAL_SCALE,
                            animationSpec = tween(
                                durationMillis = FlameMotionSpec.TAB_ENTER_MS,
                                easing = FlameMotionSpec.easeOut,
                            ),
                        )) togetherWith fadeOut(
                            animationSpec = tween(
                                durationMillis = FlameMotionSpec.TAB_EXIT_MS,
                                easing = FlameMotionSpec.easeOut,
                            ),
                        )
                    }
                },
                label = "main_shell_content_transition",
            ) { key ->
                when (key) {
                    ShellContentKey.EmbeddedSettings -> settingsContent?.invoke()
                    is ShellContentKey.Tab -> when (key.tab) {
                        MainTab.TODAY -> todayContent()
                        MainTab.TARGETS -> targetsContent()
                        MainTab.TEMPLATES -> templatesContent()
                        MainTab.HISTORY -> historyContent()
                    }
                }
            }
        }
    }

    if (navChromeKind != FlameNavChromeKind.BOTTOM_DOCK) {
        val endInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.End)
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(palette.canvas),
        ) {
            AnimatedVisibility(
                visible = !hideNavigationChrome,
                enter = if (reducedMotion) {
                    EnterTransition.None
                } else {
                    expandHorizontally(
                        animationSpec = tween(220, easing = FlameMotionSpec.easeOut),
                        expandFrom = Alignment.Start,
                    ) + fadeIn(animationSpec = tween(180, easing = FlameMotionSpec.easeOut))
                },
                exit = if (reducedMotion) {
                    ExitTransition.None
                } else {
                    shrinkHorizontally(
                        animationSpec = tween(190, easing = FlameMotionSpec.easeOut),
                        shrinkTowards = Alignment.Start,
                    ) + fadeOut(animationSpec = tween(130, easing = FlameMotionSpec.easeOut))
                },
                label = "side_nav_visibility",
            ) {
                Row(modifier = Modifier.fillMaxHeight()) {
                    when (navChromeKind) {
                        FlameNavChromeKind.COMPACT_RAIL -> FlameCompactIconRail(
                            selectedTab = selectedTab,
                            isSettingsSelected = isSettingsSelected,
                            onSelectTab = onSelectTab,
                            todayAlertBadge = todayAlertBadge,
                            currentAccount = currentAccount,
                            otherAccountAlertBadge = otherAccountAlertBadge,
                            onOpenAccountSwitcher = onOpenAccountSwitcher,
                            onSwitchToSimpleMode = onSwitchToSimpleMode,
                            onOpenFeedback = onOpenFeedback,
                            onOpenSettings = onOpenSettings,
                        )

                        FlameNavChromeKind.NAVIGATION_RAIL -> FlameTabletNavigationRail(
                            selectedTab = selectedTab,
                            isSettingsSelected = isSettingsSelected,
                            onSelectTab = onSelectTab,
                            todayAlertBadge = todayAlertBadge,
                            currentAccount = currentAccount,
                            totalAccountCount = totalAccountCount,
                            otherAccountAlertBadge = otherAccountAlertBadge,
                            onOpenAccountSwitcher = onOpenAccountSwitcher,
                            onSwitchToSimpleMode = onSwitchToSimpleMode,
                            onOpenFeedback = onOpenFeedback,
                            onOpenSettings = onOpenSettings,
                        )

                        FlameNavChromeKind.PERMANENT_SIDEBAR -> FlamePermanentSidebar(
                            selectedTab = selectedTab,
                            isSettingsSelected = isSettingsSelected,
                            onSelectTab = onSelectTab,
                            todayAlertBadge = todayAlertBadge,
                            enabledTargetCount = enabledTargetCount,
                            currentAccount = currentAccount,
                            totalAccountCount = totalAccountCount,
                            otherAccountAlertBadge = otherAccountAlertBadge,
                            appVersionLabel = appVersionLabel,
                            onOpenAccountSwitcher = onOpenAccountSwitcher,
                            onSwitchToSimpleMode = onSwitchToSimpleMode,
                            onOpenFeedback = onOpenFeedback,
                            onOpenSettings = onOpenSettings,
                        )

                        FlameNavChromeKind.BOTTOM_DOCK -> Unit
                    }
                    VerticalDivider(thickness = 1.dp, color = palette.hairline)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(palette.canvas)
                    .then(
                        if (!hideNavigationChrome) {
                            Modifier.windowInsetsPadding(endInsets)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                activeContent()
            }
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(palette.canvas),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                activeContent()
            }

            AnimatedVisibility(
                visible = !hideNavigationChrome,
                enter = if (reducedMotion) {
                    EnterTransition.None
                } else {
                    fadeIn(animationSpec = tween(220, easing = FlameMotionSpec.easeOut)) +
                        slideInVertically(
                            animationSpec = tween(220, easing = FlameMotionSpec.easeOut),
                            initialOffsetY = { it / 3 },
                        )
                },
                exit = if (reducedMotion) {
                    ExitTransition.None
                } else {
                    fadeOut(animationSpec = tween(150, easing = FlameMotionSpec.easeOut)) +
                        slideOutVertically(
                            animationSpec = tween(150, easing = FlameMotionSpec.easeOut),
                            targetOffsetY = { it / 3 },
                        )
                },
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

@Composable
private fun FlameFloatingPillDock(
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    todayAlertBadge: Boolean,
) {
    val palette = FlameThemeTokens.palette
    val isDark = FlameThemeTokens.isDark
    val haptics = LocalHapticFeedback.current

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
                val tabInteraction = remember(tab) { MutableInteractionSource() }
                val itemContainer by animateColorAsState(
                    targetValue = if (selected) palette.dockActiveContainer else Color.Transparent,
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    label = "dock_item_bg_${tab.name}",
                )
                val targetContentColor = if (selected) palette.ink else palette.inkTertiary
                val itemTint by animateColorAsState(
                    targetValue = targetContentColor,
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    label = "dock_item_fg_${tab.name}",
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .flamePressScale(
                            interactionSource = tabInteraction,
                            pressedScale = FlameMotionSpec.PRESS_SCALE_HERO,
                        )
                        .clip(RoundedCornerShape(20.dp))
                        .background(itemContainer)
                        .clickable(
                            interactionSource = tabInteraction,
                            indication = LocalIndication.current,
                            role = Role.Tab,
                            onClick = {
                                if (!selected) {
                                    haptics.performFlameLightTick()
                                }
                                onSelectTab(tab)
                            },
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

/**
 * 手机横屏 / 矮小窗（`COMPACT_HEIGHT_LANDSCAPE`）专用 `62.dp` 可垂直滚动纯图标 Rail。
 */
@Composable
private fun FlameCompactIconRail(
    selectedTab: MainTab,
    isSettingsSelected: Boolean,
    onSelectTab: (MainTab) -> Unit,
    todayAlertBadge: Boolean,
    currentAccount: AccountEntity?,
    otherAccountAlertBadge: Boolean,
    onOpenAccountSwitcher: (() -> Unit)?,
    onSwitchToSimpleMode: (() -> Unit)?,
    onOpenFeedback: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val haptics = LocalHapticFeedback.current
    val statusStyle = accountStatusStyle(currentAccount?.status ?: AccountStatus.PENDING_LOGIN)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxHeight()
            .background(palette.surface)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
            )
            .width(FLAME_COMPACT_RAIL_WIDTH_DP.dp),
    ) {
        val minRailHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minRailHeight)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onOpenAccountSwitcher != null) {
                    CompactAccountAvatarButton(
                        dotColor = statusStyle.dotColor,
                        otherAccountAlertBadge = otherAccountAlertBadge,
                        sizeDp = 38,
                        onClick = {
                            haptics.performFlameLightTick()
                            onOpenAccountSwitcher()
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier
                            .width(26.dp)
                            .padding(vertical = 4.dp),
                        thickness = 1.dp,
                        color = palette.hairline,
                    )
                }

                MainTab.entries.forEach { tab ->
                    val isSelected = !isSettingsSelected && tab == selectedTab
                    val interaction = remember(tab) { MutableInteractionSource() }
                    val bgColor by animateColorAsState(
                        targetValue = if (isSelected) palette.dockActiveContainer else Color.Transparent,
                        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                        label = "compact_rail_bg_${tab.name}",
                    )
                    val tint by animateColorAsState(
                        targetValue = if (isSelected) palette.ink else palette.inkTertiary,
                        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                        label = "compact_rail_fg_${tab.name}",
                    )

                    Surface(
                        onClick = {
                            if (!isSelected) {
                                haptics.performFlameLightTick()
                            }
                            onSelectTab(tab)
                        },
                        interactionSource = interaction,
                        modifier = Modifier
                            .flamePressScale(interactionSource = interaction)
                            .size(width = 44.dp, height = 38.dp)
                            .semantics {
                                contentDescription = tab.label
                                selected = isSelected
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = bgColor,
                        border = if (isSelected) BorderStroke(1.dp, palette.hairline) else null,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            TabIconWithBadge(
                                tab = tab,
                                selected = isSelected,
                                tint = tint,
                                showBadge = tab == MainTab.TODAY && todayAlertBadge,
                                badgeRingColor = if (isSelected) palette.dockActiveContainer else palette.surface,
                            )
                        }
                    }
                }
            }

            if (onSwitchToSimpleMode != null || onOpenFeedback != null || onOpenSettings != null) {
                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    HorizontalDivider(
                        modifier = Modifier
                            .width(26.dp)
                            .padding(bottom = 2.dp),
                        thickness = 1.dp,
                        color = palette.hairline,
                    )
                    if (onSwitchToSimpleMode != null) {
                        CompactRailActionIcon(
                            icon = Icons.Outlined.CenterFocusWeak,
                            contentDescription = "切换极简模式",
                            tint = palette.inkSecondary,
                            containerColor = Color.Transparent,
                            borderColor = null,
                            selected = false,
                            onClick = {
                                haptics.performFlameLightTick()
                                onSwitchToSimpleMode()
                            },
                        )
                    }
                    if (onOpenFeedback != null) {
                        CompactRailActionIcon(
                            icon = Icons.Outlined.Feedback,
                            contentDescription = "问题反馈",
                            tint = palette.ember,
                            containerColor = palette.emberContainer,
                            borderColor = palette.emberBorder,
                            selected = false,
                            onClick = {
                                haptics.performFlameLightTick()
                                onOpenFeedback()
                            },
                        )
                    }
                    if (onOpenSettings != null) {
                        CompactRailActionIcon(
                            icon = Icons.Outlined.Settings,
                            contentDescription = "设置与维护",
                            tint = if (isSettingsSelected) palette.ink else palette.inkSecondary,
                            containerColor = if (isSettingsSelected) palette.dockActiveContainer else Color.Transparent,
                            borderColor = if (isSettingsSelected) palette.hairline else null,
                            selected = isSettingsSelected,
                            onClick = {
                                haptics.performFlameLightTick()
                                onOpenSettings()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 平板竖屏（`TABLET_PORTRAIT`）专用 `76.dp` 精致 Rail。
 */
@Composable
private fun FlameTabletNavigationRail(
    selectedTab: MainTab,
    isSettingsSelected: Boolean,
    onSelectTab: (MainTab) -> Unit,
    todayAlertBadge: Boolean,
    currentAccount: AccountEntity?,
    totalAccountCount: Int,
    otherAccountAlertBadge: Boolean,
    onOpenAccountSwitcher: (() -> Unit)?,
    onSwitchToSimpleMode: (() -> Unit)?,
    onOpenFeedback: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
) {
    val palette = FlameThemeTokens.palette
    val haptics = LocalHapticFeedback.current
    val statusStyle = accountStatusStyle(currentAccount?.status ?: AccountStatus.PENDING_LOGIN)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxHeight()
            .background(palette.surface)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
            )
            .width(FLAME_NAVIGATION_RAIL_WIDTH_DP.dp),
    ) {
        val minRailHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minRailHeight)
                .padding(horizontal = 6.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (onOpenAccountSwitcher != null) {
                    Column(
                        modifier = Modifier.padding(bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        CompactAccountAvatarButton(
                            dotColor = statusStyle.dotColor,
                            otherAccountAlertBadge = otherAccountAlertBadge,
                            sizeDp = 44,
                            onClick = {
                                haptics.performFlameLightTick()
                                onOpenAccountSwitcher()
                            },
                        )
                        Text(
                            text = currentAccount?.displayName
                                ?.takeIf { it.isNotBlank() }
                                ?: if (totalAccountCount > 1) "共${totalAccountCount}号" else "账号",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier
                            .width(36.dp)
                            .padding(bottom = 4.dp),
                        thickness = 1.dp,
                        color = palette.hairline,
                    )
                }

                MainTab.entries.forEach { tab ->
                    val isSelected = !isSettingsSelected && tab == selectedTab
                    val interaction = remember(tab) { MutableInteractionSource() }
                    val bgColor by animateColorAsState(
                        targetValue = if (isSelected) palette.dockActiveContainer else Color.Transparent,
                        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                        label = "tablet_rail_bg_${tab.name}",
                    )
                    val tint by animateColorAsState(
                        targetValue = if (isSelected) palette.ink else palette.inkTertiary,
                        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                        label = "tablet_rail_fg_${tab.name}",
                    )

                    Surface(
                        onClick = {
                            if (!isSelected) {
                                haptics.performFlameLightTick()
                            }
                            onSelectTab(tab)
                        },
                        interactionSource = interaction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .flamePressScale(interactionSource = interaction)
                            .semantics {
                                selected = isSelected
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = bgColor,
                        border = if (isSelected) BorderStroke(1.dp, palette.hairline) else null,
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            TabIconWithBadge(
                                tab = tab,
                                selected = isSelected,
                                tint = tint,
                                showBadge = tab == MainTab.TODAY && todayAlertBadge,
                                badgeRingColor = if (isSelected) palette.dockActiveContainer else palette.surface,
                            )
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = tint,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }

            if (onSwitchToSimpleMode != null || onOpenFeedback != null || onOpenSettings != null) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HorizontalDivider(
                        modifier = Modifier
                            .width(36.dp)
                            .padding(bottom = 2.dp),
                        thickness = 1.dp,
                        color = palette.hairline,
                    )
                    if (onSwitchToSimpleMode != null) {
                        TabletRailToolItem(
                            icon = Icons.Outlined.CenterFocusWeak,
                            label = "极简",
                            contentDescription = "切换极简模式",
                            iconTint = palette.inkSecondary,
                            labelColor = palette.inkSecondary,
                            containerColor = Color.Transparent,
                            borderColor = null,
                            selected = false,
                            onClick = {
                                haptics.performFlameLightTick()
                                onSwitchToSimpleMode()
                            },
                        )
                    }
                    if (onOpenFeedback != null) {
                        TabletRailToolItem(
                            icon = Icons.Outlined.Feedback,
                            label = "反馈",
                            contentDescription = "问题反馈",
                            iconTint = palette.ember,
                            labelColor = palette.ember,
                            containerColor = palette.emberContainer,
                            borderColor = palette.emberBorder,
                            selected = false,
                            onClick = {
                                haptics.performFlameLightTick()
                                onOpenFeedback()
                            },
                        )
                    }
                    if (onOpenSettings != null) {
                        TabletRailToolItem(
                            icon = Icons.Outlined.Settings,
                            label = "设置",
                            contentDescription = "设置与维护",
                            iconTint = if (isSettingsSelected) palette.ink else palette.inkSecondary,
                            labelColor = if (isSettingsSelected) palette.ink else palette.inkSecondary,
                            containerColor = if (isSettingsSelected) palette.dockActiveContainer else Color.Transparent,
                            borderColor = if (isSettingsSelected) palette.hairline else null,
                            selected = isSettingsSelected,
                            onClick = {
                                haptics.performFlameLightTick()
                                onOpenSettings()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 平板横屏 / 桌面宽窗（`TABLET_LANDSCAPE`）专用 `216.dp` 常驻展开侧边栏。
 */
@Composable
private fun FlamePermanentSidebar(
    selectedTab: MainTab,
    isSettingsSelected: Boolean,
    onSelectTab: (MainTab) -> Unit,
    todayAlertBadge: Boolean,
    enabledTargetCount: Int?,
    currentAccount: AccountEntity?,
    totalAccountCount: Int,
    otherAccountAlertBadge: Boolean,
    appVersionLabel: String?,
    onOpenAccountSwitcher: (() -> Unit)?,
    onSwitchToSimpleMode: (() -> Unit)?,
    onOpenFeedback: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val haptics = LocalHapticFeedback.current
    val accountStatus = currentAccount?.status ?: AccountStatus.PENDING_LOGIN
    val statusStyle = accountStatusStyle(accountStatus)
    val statusTone = accountStatusTone(accountStatus)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxHeight()
            .background(palette.surface)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
            )
            .width(FLAME_PERMANENT_SIDEBAR_WIDTH_DP.dp),
    ) {
        val minSidebarHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minSidebarHeight)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 顶部账号状态切换卡
                if (onOpenAccountSwitcher != null) {
                    val accountInteraction = remember { MutableInteractionSource() }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            onClick = {
                                haptics.performFlameLightTick()
                                onOpenAccountSwitcher()
                            },
                            interactionSource = accountInteraction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .flamePressScale(interactionSource = accountInteraction),
                            shape = RoundedCornerShape(16.dp),
                            color = palette.surfaceSubtle,
                            contentColor = palette.ink,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (otherAccountAlertBadge) semantics.dangerBorder else palette.hairline,
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 11.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                                ) {
                                    Surface(
                                        modifier = Modifier.size(34.dp),
                                        shape = RoundedCornerShape(11.dp),
                                        color = palette.surface,
                                        contentColor = palette.ink,
                                        border = BorderStroke(1.dp, palette.hairline),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Outlined.SwitchAccount,
                                                contentDescription = "切换或管理账号",
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(4.dp)
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(statusStyle.dotColor),
                                            )
                                        }
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(1.dp),
                                    ) {
                                        Text(
                                            text = currentAccount?.displayName
                                                ?.takeIf { it.isNotBlank() }
                                                ?: "默认账号",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = palette.ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val secondaryInfo = currentAccount?.douyinNickname
                                            ?.takeIf { it.isNotBlank() }
                                            ?: if (totalAccountCount > 1) {
                                                "共 $totalAccountCount 个账号"
                                            } else {
                                                "点击切换或管理"
                                            }
                                        Text(
                                            text = secondaryInfo,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.inkSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Outlined.UnfoldMore,
                                        contentDescription = null,
                                        tint = palette.inkTertiary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    FlameStatusPill(
                                        text = statusStyle.label,
                                        tone = statusTone,
                                    )
                                    if (totalAccountCount > 1) {
                                        FlameMiniTag(
                                            text = "共${totalAccountCount}号",
                                            tone = FlameTone.NEUTRAL,
                                        )
                                    }
                                }
                            }
                        }

                        if (otherAccountAlertBadge) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-6).dp, y = 6.dp)
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(semantics.danger)
                                    .border(1.5.dp, palette.surfaceSubtle, CircleShape),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // 中部主导航分组
                Text(
                    text = "工作区",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkTertiary,
                    modifier = Modifier.padding(start = 10.dp, bottom = 6.dp),
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MainTab.entries.forEach { tab ->
                        val isSelected = !isSettingsSelected && tab == selectedTab
                        val interaction = remember(tab) { MutableInteractionSource() }
                        val containerColor by animateColorAsState(
                            targetValue = if (isSelected) palette.surfaceSubtle else Color.Transparent,
                            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                            label = "sidebar_tab_bg_${tab.name}",
                        )
                        val contentTint by animateColorAsState(
                            targetValue = if (isSelected) palette.ink else palette.inkSecondary,
                            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                            label = "sidebar_tab_fg_${tab.name}",
                        )
                        val iconTint by animateColorAsState(
                            targetValue = if (isSelected) palette.ink else palette.inkTertiary,
                            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                            label = "sidebar_tab_icon_${tab.name}",
                        )

                        Surface(
                            onClick = {
                                if (!isSelected) {
                                    haptics.performFlameLightTick()
                                }
                                onSelectTab(tab)
                            },
                            interactionSource = interaction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .flamePressScale(interactionSource = interaction)
                                .semantics {
                                    selected = isSelected
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = containerColor,
                            contentColor = contentTint,
                            border = if (isSelected) BorderStroke(1.dp, palette.hairline) else null,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                TabIconWithBadge(
                                    tab = tab,
                                    selected = isSelected,
                                    tint = iconTint,
                                    showBadge = tab == MainTab.TODAY && todayAlertBadge,
                                    badgeRingColor = if (isSelected) palette.surfaceSubtle else palette.surface,
                                )
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = contentTint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                when (tab) {
                                    MainTab.TODAY -> if (todayAlertBadge) {
                                        FlameMiniTag(
                                            text = "待处理",
                                            tone = FlameTone.DANGER,
                                        )
                                    }

                                    MainTab.TARGETS -> if (enabledTargetCount != null && enabledTargetCount >= 0) {
                                        Surface(
                                            shape = RoundedCornerShape(999.dp),
                                            color = if (isSelected) palette.surface else palette.surfaceSubtle,
                                            contentColor = palette.inkSecondary,
                                            border = BorderStroke(1.dp, palette.hairline),
                                        ) {
                                            Text(
                                                text = enabledTargetCount.toString(),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                            )
                                        }
                                    }

                                    MainTab.TEMPLATES,
                                    MainTab.HISTORY,
                                    -> Unit
                                }
                            }
                        }
                    }
                }
            }

            // 底部工具区
            if (onSwitchToSimpleMode != null || onOpenFeedback != null || onOpenSettings != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(bottom = 4.dp),
                        thickness = 1.dp,
                        color = palette.hairline,
                    )

                    if (onSwitchToSimpleMode != null) {
                        SidebarUtilityRow(
                            icon = Icons.Outlined.CenterFocusWeak,
                            label = "切换极简模式",
                            iconTint = palette.inkSecondary,
                            labelColor = palette.inkSecondary,
                            containerColor = Color.Transparent,
                            borderColor = null,
                            selected = false,
                            onClick = {
                                haptics.performFlameLightTick()
                                onSwitchToSimpleMode()
                            },
                        )
                    }

                    if (onOpenFeedback != null) {
                        SidebarUtilityRow(
                            icon = Icons.Outlined.Feedback,
                            label = "反馈与建议",
                            iconTint = palette.ember,
                            labelColor = palette.ember,
                            containerColor = palette.emberContainer,
                            borderColor = palette.emberBorder,
                            selected = false,
                            onClick = {
                                haptics.performFlameLightTick()
                                onOpenFeedback()
                            },
                        )
                    }

                    if (onOpenSettings != null) {
                        SidebarUtilityRow(
                            icon = Icons.Outlined.Settings,
                            label = "设置与维护",
                            trailingText = appVersionLabel?.takeIf { it.isNotBlank() },
                            iconTint = if (isSettingsSelected) palette.ink else palette.inkSecondary,
                            labelColor = if (isSettingsSelected) palette.ink else palette.inkSecondary,
                            containerColor = if (isSettingsSelected) palette.surfaceSubtle else Color.Transparent,
                            borderColor = if (isSettingsSelected) palette.hairline else null,
                            selected = isSettingsSelected,
                            onClick = {
                                haptics.performFlameLightTick()
                                onOpenSettings()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactAccountAvatarButton(
    dotColor: Color,
    otherAccountAlertBadge: Boolean,
    sizeDp: Int,
    onClick: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val interaction = remember { MutableInteractionSource() }
    Box {
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            modifier = Modifier
                .flamePressScale(interactionSource = interaction)
                .size(sizeDp.dp),
            shape = RoundedCornerShape(if (sizeDp >= 44) 14.dp else 12.dp),
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
                    modifier = Modifier.size(if (sizeDp >= 44) 20.dp else 18.dp),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(if (sizeDp >= 44) 6.dp else 5.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor),
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
}

@Composable
private fun CompactRailActionIcon(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    containerColor: Color,
    borderColor: Color?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier
            .flamePressScale(interactionSource = interaction)
            .size(width = 44.dp, height = 36.dp)
            .semantics {
                this.contentDescription = contentDescription
                this.selected = selected
            },
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = tint,
        border = borderColor?.let { BorderStroke(1.dp, it) },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun TabletRailToolItem(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    iconTint: Color,
    labelColor: Color,
    containerColor: Color,
    borderColor: Color?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .flamePressScale(interactionSource = interaction)
            .semantics {
                this.contentDescription = contentDescription
                this.selected = selected
            },
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = borderColor?.let { BorderStroke(1.dp, it) },
    ) {
        Column(
            modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = labelColor,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun SidebarUtilityRow(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    labelColor: Color,
    containerColor: Color,
    borderColor: Color?,
    selected: Boolean,
    onClick: () -> Unit,
    trailingText: String? = null,
) {
    val palette = FlameThemeTokens.palette
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .flamePressScale(interactionSource = interaction)
            .semantics {
                this.selected = selected
            },
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = labelColor,
        border = borderColor?.let { BorderStroke(1.dp, it) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected || borderColor != null) FontWeight.SemiBold else FontWeight.Medium,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!trailingText.isNullOrBlank()) {
                Text(
                    text = trailingText,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkTertiary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    val reducedMotion = rememberReducedMotion()
    val iconScale by animateFloatAsState(
        targetValue = if (!reducedMotion && selected) FlameMotionSpec.TAB_SELECTED_ICON_SCALE else 1f,
        animationSpec = FlameMotionSpec.tabIconSpringSpec(),
        label = "tab_icon_scale_${tab.name}",
    )
    val icon = if (selected) tab.selectedIcon else tab.unselectedIcon
    Box(
        contentAlignment = Alignment.Center,
        modifier = if (reducedMotion) {
            Modifier
        } else {
            Modifier.graphicsLayer {
                scaleX = iconScale
                scaleY = iconScale
            }
        },
    ) {
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
