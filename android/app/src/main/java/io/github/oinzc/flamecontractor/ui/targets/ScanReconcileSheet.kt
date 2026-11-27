package io.github.oinzc.flamecontractor.ui.targets

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.oinzc.flamecontractor.R
import io.github.oinzc.flamecontractor.core.identity.MatchStatus
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.execution.ScannedChatCandidate
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.FlameSearchPill
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.FlameTopPillButton
import io.github.oinzc.flamecontractor.ui.components.LocalFlameWindowLayout
import io.github.oinzc.flamecontractor.ui.components.officialSparkKindFullLabel
import io.github.oinzc.flamecontractor.ui.components.rememberReducedMotion
import io.github.oinzc.flamecontractor.ui.components.resolveFlameToneColors
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
private fun EdgeToEdgeSheetDialog(
    isDark: Boolean,
    dismissOnBackPress: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val hostView = LocalView.current
    val compositionContext = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val currentDismissOnBack by rememberUpdatedState(dismissOnBackPress)

    val dialog = remember(hostView) {
        val dlg = ComponentDialog(
            hostView.context,
            R.style.Theme_FlameContractor_EdgeToEdgeBottomSheetDialog,
        )
        dlg.setCanceledOnTouchOutside(false)
        dlg.setCancelable(false)
        dlg.window?.let { window ->
            window.requestFeature(Window.FEATURE_NO_TITLE)
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.setDimAmount(0f)
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            @Suppress("DEPRECATION")
            val softInputMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            window.setSoftInputMode(softInputMode)
        }

        val composeView = ComposeView(hostView.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setParentCompositionContext(compositionContext)
            setViewTreeLifecycleOwner(hostView.findViewTreeLifecycleOwner())
            setViewTreeViewModelStoreOwner(hostView.findViewTreeViewModelStoreOwner())
            setViewTreeSavedStateRegistryOwner(hostView.findViewTreeSavedStateRegistryOwner())
            setContent {
                currentContent()
            }
        }
        dlg.setContentView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        dlg.onBackPressedDispatcher.addCallback(
            dlg,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentDismissOnBack) {
                        currentOnDismiss()
                    }
                }
            },
        )
        dlg
    }

    SideEffect {
        dialog.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }

    DisposableEffect(dialog) {
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        onDispose {
            dialog.dismiss()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScanReconcileSheet(
    reconcileState: ScanReconcileState,
    templates: List<MessageTemplateEntity>,
    onSearchChanged: (String) -> Unit,
    onDefaultTemplateChanged: (String?) -> Unit,
    onToggleCandidate: (ScannedChatCandidate) -> Unit,
    onSelectAllImportableSparks: () -> Unit,
    onClearSelection: () -> Unit,
    onImportSelected: () -> Unit,
    onSyncExistingSparks: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val isDark = FlameThemeTokens.isDark
    val layout = LocalFlameWindowLayout.current
    val useCenterDialog = layout.useCenterDialogForSheets
    val reducedMotion = rememberReducedMotion()
    val density = LocalDensity.current
    val sheetScope = rememberCoroutineScope()
    val importingState by rememberUpdatedState(reconcileState.importing)
    val onDismissState by rememberUpdatedState(onDismiss)

    var templateMenuExpanded by remember { mutableStateOf(false) }
    val selectedTemplateName = remember(reconcileState.defaultTemplateId, templates) {
        templates.firstOrNull { it.id == reconcileState.defaultTemplateId }?.name
    }
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val maxSheetHeight = if (useCenterDialog) {
        (screenHeightDp * 0.86f).coerceAtLeast(300f).dp
    } else {
        (screenHeightDp * 0.88f).coerceAtLeast(320f).dp
    }

    var sheetHeightPx by remember { mutableFloatStateOf(0f) }
    var dismissing by remember { mutableStateOf(false) }
    val entryProgress = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val dragOffsetY = remember { Animatable(0f) }

    val entrySlidePx = with(density) { if (useCenterDialog) 20.dp.toPx() else 44.dp.toPx() }
    val dismissMinPx = with(density) { 96.dp.toPx() }
    val dismissMaxPx = with(density) { 180.dp.toPx() }
    val flingVelocityThresholdPx = with(density) { 750.dp.toPx() }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            entryProgress.snapTo(1f)
        } else if (entryProgress.value < 1f) {
            entryProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = FlameMotionSpec.SUBPAGE_ENTER_MS,
                    easing = FlameMotionSpec.easeOut,
                ),
            )
        }
    }

    fun animateAndDismiss() {
        if (importingState || dismissing) return
        dismissing = true
        if (reducedMotion) {
            onDismissState()
            return
        }
        if (useCenterDialog) {
            sheetScope.launch {
                entryProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = FlameMotionSpec.SUBPAGE_EXIT_MS,
                        easing = FlameMotionSpec.easeOut,
                    ),
                )
                onDismissState()
            }
            return
        }
        sheetScope.launch {
            val targetY = if (sheetHeightPx > 0f) sheetHeightPx else dismissMaxPx * 3f
            dragOffsetY.animateTo(
                targetValue = targetY,
                animationSpec = tween(
                    durationMillis = FlameMotionSpec.SUBPAGE_EXIT_MS,
                    easing = FlameMotionSpec.easeOut,
                ),
            )
            onDismissState()
        }
    }

    val dismissingState by rememberUpdatedState(dismissing)

    fun settleHandleDrag(velocityY: Float) {
        val threshold = if (sheetHeightPx > 0f) {
            (sheetHeightPx * 0.18f).coerceIn(dismissMinPx, dismissMaxPx)
        } else {
            dismissMinPx
        }
        if (!importingState &&
            (dragOffsetY.value >= threshold || velocityY >= flingVelocityThresholdPx)
        ) {
            animateAndDismiss()
        } else {
            sheetScope.launch {
                if (reducedMotion) {
                    dragOffsetY.snapTo(0f)
                } else {
                    dragOffsetY.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = FlameMotionSpec.COLLAPSE_ENTER_MS,
                            easing = FlameMotionSpec.easeOut,
                        ),
                    )
                }
            }
        }
    }

    EdgeToEdgeSheetDialog(
        isDark = isDark,
        dismissOnBackPress = !reconcileState.importing,
        onDismissRequest = { animateAndDismiss() },
    ) {
        val dismissDenom = if (sheetHeightPx > 0f) sheetHeightPx else dismissMaxPx * 2f
        val dragFraction = (dragOffsetY.value / dismissDenom).coerceIn(0f, 1f)
        val baseScrimAlpha = if (isDark) 0.62f else 0.36f
        val effectiveScrimAlpha = baseScrimAlpha * entryProgress.value * (1f - dragFraction)
        val dialogHPadding = if (layout.isShortLandscape) 20.dp else 24.dp
        val dialogVPadding = if (layout.isShortLandscape) 12.dp else 20.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = effectiveScrimAlpha)),
            contentAlignment = if (useCenterDialog) Alignment.Center else Alignment.BottomCenter,
        ) {
            // 手机竖屏保留底部弹窗 + 仅顶部横条可下拉关闭的防误滑逻辑；
            // 平板横/竖屏与手机横屏切换为居中圆角大浮层，无拖拽手势，仅通过标题关闭钮/取消按钮/返回键关闭。
            Surface(
                shape = if (useCenterDialog) {
                    RoundedCornerShape(24.dp)
                } else {
                    RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
                },
                color = palette.surface,
                contentColor = palette.ink,
                border = if (useCenterDialog) BorderStroke(1.dp, palette.hairlineStrong) else null,
                shadowElevation = if (useCenterDialog) {
                    if (isDark) 18.dp else 14.dp
                } else {
                    6.dp
                },
                modifier = Modifier
                    .then(
                        if (useCenterDialog) {
                            Modifier
                                .imePadding()
                                .padding(horizontal = dialogHPadding, vertical = dialogVPadding)
                                .widthIn(max = 780.dp)
                                .fillMaxWidth()
                        } else {
                            Modifier
                                .widthIn(max = 760.dp)
                                .fillMaxWidth()
                        },
                    )
                    .heightIn(max = maxSheetHeight)
                    .onSizeChanged { sheetHeightPx = it.height.toFloat() }
                    .graphicsLayer {
                        if (useCenterDialog) {
                            translationY = (1f - entryProgress.value) * entrySlidePx
                            alpha = if (reducedMotion) 1f else entryProgress.value
                        } else {
                            translationY = dragOffsetY.value + (1f - entryProgress.value) * entrySlidePx
                            alpha = if (reducedMotion) 1f else (0.65f + 0.35f * entryProgress.value)
                        }
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (useCenterDialog) {
                                Modifier
                            } else {
                                Modifier
                                    .imePadding()
                                    .navigationBarsPadding()
                            },
                        ),
                ) {
                    if (!useCenterDialog) {
                        // 仅此顶部横条严格自身边界内支持下拉关闭（不使用会扩展 48dp 隐形热区的 draggable，
                        // 避免向下渗入「扫描对账与导入」标题区域）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "向下拖动顶部横条关闭扫描对账" }
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        if (importingState || dismissingState) return@awaitEachGesture
                                        if (down.position.y < 0f || down.position.y > size.height.toFloat()) {
                                            return@awaitEachGesture
                                        }
                                        val tracker = VelocityTracker()
                                        tracker.addPosition(down.uptimeMillis, down.position)
                                        var started = false
                                        val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                                            change.consume()
                                            started = true
                                            tracker.addPosition(change.uptimeMillis, change.position)
                                            val next = (dragOffsetY.value + over).coerceAtLeast(0f)
                                            sheetScope.launch { dragOffsetY.snapTo(next) }
                                        }
                                        if (drag != null) {
                                            verticalDrag(drag.id) { change ->
                                                val dy = change.positionChange().y
                                                if (dy != 0f) {
                                                    change.consume()
                                                    tracker.addPosition(change.uptimeMillis, change.position)
                                                    val next = (dragOffsetY.value + dy).coerceAtLeast(0f)
                                                    sheetScope.launch { dragOffsetY.snapTo(next) }
                                                }
                                            }
                                            settleHandleDrag(tracker.calculateVelocity().y)
                                        } else if (started) {
                                            settleHandleDrag(0f)
                                        }
                                    }
                                }
                                .padding(top = 14.dp, bottom = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 40.dp, height = 4.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(palette.hairlineStrong),
                            )
                        }
                    }

                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val isWideSheet = maxWidth >= 520.dp

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = if (useCenterDialog) 22.dp else 18.dp,
                                    end = if (useCenterDialog) 22.dp else 18.dp,
                                    top = if (useCenterDialog) 18.dp else 2.dp,
                                    bottom = if (useCenterDialog) 18.dp else 16.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // 标题与扫描统计
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = "扫描对账与导入",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = palette.ink,
                                    )
                                    val timeStr = reconcileState.scannedAtEpochMs?.let(::formatScanTimestamp) ?: "未知时间"
                                    val litCount = reconcileState.allCandidates.count { it.candidate.sparkLitToday == true }
                                    val grayCount = reconcileState.allCandidates.count { it.candidate.sparkLitToday == false }
                                    val litBreakdown = if (litCount > 0 || grayCount > 0) {
                                        "（今日已亮 $litCount / 待续 $grayCount）"
                                    } else {
                                        ""
                                    }
                                    Text(
                                        text = "共扫描 ${reconcileState.discoveredCount} 条会话 · 含官方火花 ${reconcileState.sparkCount} 条$litBreakdown · $timeStr",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.inkSecondary,
                                    )
                                }
                                if (useCenterDialog) {
                                    Surface(
                                        onClick = { animateAndDismiss() },
                                        enabled = !reconcileState.importing && !dismissing,
                                        modifier = Modifier.size(34.dp),
                                        shape = CircleShape,
                                        color = palette.surfaceSubtle,
                                        contentColor = if (reconcileState.importing) palette.inkTertiary else palette.inkSecondary,
                                        border = BorderStroke(1.dp, palette.hairline),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Outlined.Close,
                                                contentDescription = "关闭",
                                                modifier = Modifier.size(17.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            if (reconcileState.errorMessage != null) {
                                Surface(
                                    color = semantics.dangerContainer,
                                    border = BorderStroke(1.dp, semantics.dangerBorder),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = reconcileState.errorMessage,
                                        color = semantics.onDangerContainer,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }
                            }

                            // 宽屏（横屏/平板）将搜索栏与默认模板选择器并排放置，节省纵向高度让列表与底栏同步可见
                            if (isWideSheet) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FlameSearchPill(
                                        query = reconcileState.searchQuery,
                                        onQueryChange = onSearchChanged,
                                        placeholder = "搜索昵称、群名、抖音号或火花天数…",
                                        modifier = Modifier.weight(1f),
                                    )
                                    ReconcileTemplateSelector(
                                        selectedTemplateName = selectedTemplateName,
                                        templates = templates,
                                        expanded = templateMenuExpanded,
                                        onExpandedChange = { templateMenuExpanded = it },
                                        onDefaultTemplateChanged = onDefaultTemplateChanged,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            } else {
                                FlameSearchPill(
                                    query = reconcileState.searchQuery,
                                    onQueryChange = onSearchChanged,
                                    placeholder = "搜索昵称、群名、抖音号或火花天数…",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ReconcileTemplateSelector(
                                    selectedTemplateName = selectedTemplateName,
                                    templates = templates,
                                    expanded = templateMenuExpanded,
                                    onExpandedChange = { templateMenuExpanded = it },
                                    onDefaultTemplateChanged = onDefaultTemplateChanged,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FlameTopPillButton(
                                    text = "全选可导入火花 (${reconcileState.importableSparkCount})",
                                    primary = false,
                                    enabled = reconcileState.importableSparkCount > 0 && !reconcileState.importing,
                                    onClick = onSelectAllImportableSparks,
                                )
                                FlameTopPillButton(
                                    text = "清空选择",
                                    primary = false,
                                    enabled = reconcileState.selectedCount > 0 && !reconcileState.importing,
                                    onClick = onClearSelection,
                                )
                                if (reconcileState.alreadyImportedWithSparkCount > 0) {
                                    FlameTopPillButton(
                                        text = "同步已有火花 (${reconcileState.alreadyImportedWithSparkCount})",
                                        primary = false,
                                        enabled = !reconcileState.importing,
                                        onClick = onSyncExistingSparks,
                                    )
                                }
                            }

                            HorizontalDivider(thickness = 1.dp, color = palette.hairline)

                            // 候选列表按剩余可用高度自适应伸缩，确保底部按钮栏始终锚定可见
                            if (reconcileState.filteredCandidates.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = false)
                                        .padding(vertical = 22.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = if (reconcileState.allCandidates.isEmpty()) {
                                            "暂无扫描对账数据，请先点击「扫描对账」重新扫描"
                                        } else {
                                            "没有匹配当前搜索关键词的会话"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.inkSecondary,
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = false),
                                    contentPadding = PaddingValues(vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(
                                        items = reconcileState.filteredCandidates,
                                        key = { it.key },
                                    ) { item ->
                                        ScanCandidateRow(
                                            item = item,
                                            busy = reconcileState.importing,
                                            onToggle = { onToggleCandidate(item.candidate) },
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(thickness = 1.dp, color = palette.hairline)

                            // 底部提交操作栏
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    onClick = { animateAndDismiss() },
                                    enabled = !reconcileState.importing && !dismissing,
                                    modifier = Modifier.heightIn(min = 44.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = palette.surfaceSubtle,
                                    contentColor = if (reconcileState.importing) palette.inkTertiary else palette.ink,
                                    border = BorderStroke(1.dp, palette.hairline),
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "关闭",
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                }

                                val importEnabled = reconcileState.selectedCount > 0 && !reconcileState.importing && !dismissing
                                Surface(
                                    onClick = onImportSelected,
                                    enabled = importEnabled,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 44.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (importEnabled) {
                                        palette.monoCtaContainer
                                    } else {
                                        palette.surfaceSubtle
                                    },
                                    contentColor = if (importEnabled) {
                                        palette.monoCtaContent
                                    } else {
                                        palette.inkTertiary
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (importEnabled) palette.monoCtaContainer else palette.hairline,
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        if (reconcileState.importing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(15.dp),
                                                strokeWidth = 2.dp,
                                                color = palette.inkTertiary,
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "导入中…",
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                        } else {
                                            Text(
                                                text = "导入所选 (${reconcileState.selectedCount})",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReconcileTemplateSelector(
    selectedTemplateName: String?,
    templates: List<MessageTemplateEntity>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDefaultTemplateChanged: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { onExpandedChange(!expanded) },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedTemplateName ?: "导入后暂不绑定模板",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            label = { Text("导入时默认绑定模板") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            DropdownMenuItem(
                text = { Text("暂不绑定模板") },
                onClick = {
                    onDefaultTemplateChanged(null)
                    onExpandedChange(false)
                },
            )
            templates.forEach { tpl ->
                DropdownMenuItem(
                    text = { Text(tpl.name) },
                    onClick = {
                        onDefaultTemplateChanged(tpl.id)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanCandidateRow(
    item: ScanCandidateUi,
    busy: Boolean,
    onToggle: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val c = item.candidate

    val containerColor = when {
        item.isAmbiguous -> semantics.dangerContainer.copy(alpha = 0.65f)
        item.effectivelyAlreadyImported -> palette.surfaceSubtle.copy(alpha = 0.72f)
        item.selected -> palette.surface
        else -> palette.surfaceSubtle
    }
    val borderColor = when {
        item.isAmbiguous -> semantics.dangerBorder
        item.selected -> palette.monoCtaContainer.copy(alpha = 0.45f)
        else -> palette.hairline
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (item.selectable && !busy) {
                    Modifier.clickable(onClick = onToggle)
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = item.selected,
                onCheckedChange = if (item.selectable && !busy) {
                    { onToggle() }
                } else {
                    null
                },
                enabled = item.selectable && !busy,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = c.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    FlameMiniTag(
                        text = when (c.conversationType) {
                            "group" -> "群聊"
                            "friend" -> "好友"
                            else -> "会话"
                        },
                        tone = FlameTone.NEUTRAL,
                    )
                    if (!c.platformHandle.isNullOrBlank()) {
                        Text(
                            text = "@${c.platformHandle}",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (item.hasOfficialSpark) {
                        val isExpiring = c.sparkLabel?.contains("后消失") == true
                        val isGrayUnlit = !isExpiring && c.sparkLitToday == false
                        val chipBg = when {
                            isExpiring -> semantics.warningContainer
                            isGrayUnlit -> palette.surfaceSubtle
                            else -> semantics.sparkGoldContainer
                        }
                        val chipBorder = when {
                            isExpiring -> semantics.warningBorder
                            isGrayUnlit -> palette.hairlineStrong
                            else -> semantics.sparkGoldBorder
                        }
                        val chipIcon = when {
                            isExpiring -> Icons.Outlined.Schedule
                            isGrayUnlit -> Icons.Outlined.LocalFireDepartment
                            else -> Icons.Filled.LocalFireDepartment
                        }
                        val chipIconTint = when {
                            isExpiring -> semantics.warning
                            isGrayUnlit -> palette.inkTertiary
                            else -> semantics.sparkGold
                        }
                        val chipTextColor = when {
                            isExpiring -> semantics.onWarningContainer
                            isGrayUnlit -> palette.inkSecondary
                            else -> semantics.sparkGold
                        }
                        val sparkBaseText = formatCandidateSparkText(c)
                        val chipText = when {
                            isExpiring -> sparkBaseText
                            c.sparkLitToday == true -> "$sparkBaseText · 今日已亮"
                            c.sparkLitToday == false -> "$sparkBaseText · 待续"
                            else -> sparkBaseText
                        }

                        Surface(
                            color = chipBg,
                            border = BorderStroke(1.dp, chipBorder),
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    imageVector = chipIcon,
                                    contentDescription = null,
                                    tint = chipIconTint,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = chipText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = chipTextColor,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    when {
                        item.isAmbiguous -> {
                            val dangerTone = resolveFlameToneColors(FlameTone.DANGER)
                            Surface(
                                color = dangerTone.container,
                                border = BorderStroke(1.dp, dangerTone.border),
                                shape = RoundedCornerShape(999.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.WarningAmber,
                                        contentDescription = null,
                                        tint = dangerTone.fg,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = "同名冲突 · 禁选",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = dangerTone.onContainer,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }

                        item.effectivelyAlreadyImported -> {
                            val healthyTone = resolveFlameToneColors(FlameTone.HEALTHY)
                            Surface(
                                color = healthyTone.container,
                                border = BorderStroke(1.dp, healthyTone.border),
                                shape = RoundedCornerShape(999.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = healthyTone.fg,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = "已导入",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = healthyTone.onContainer,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }

                        c.matchStatus == MatchStatus.NOT_OBSERVED -> {
                            FlameMiniTag(
                                text = "未观察到稳定 ID",
                                tone = FlameTone.WARNING,
                            )
                        }
                    }
                }

                Text(
                    text = item.statusNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isAmbiguous) semantics.danger else palette.inkSecondary,
                )
            }
        }
    }
}

private fun formatCandidateSparkText(candidate: ScannedChatCandidate): String {
    val kindPrefix = officialSparkKindFullLabel(candidate.sparkKind)
    val days = candidate.sparkDays
    val label = candidate.sparkLabel?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        days != null && days > 0 -> "$kindPrefix ${days}天"
        label != null -> "$kindPrefix · $label"
        else -> kindPrefix
    }
}

private val SCAN_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatScanTimestamp(epochMs: Long): String {
    val zdt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return SCAN_TIME_FORMATTER.format(zdt)
}
