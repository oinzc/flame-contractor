package io.github.oinzc.flamecontractor.ui.history

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oinzc.flamecontractor.core.storage.DiagnosticScreenshotStore
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.domain.DailyJobBucket
import io.github.oinzc.flamecontractor.domain.HistoryStatsSummary
import io.github.oinzc.flamecontractor.ui.components.DiagnosticScreenshotCard
import io.github.oinzc.flamecontractor.ui.components.FlameBentoCard
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameEmptyStateCard
import io.github.oinzc.flamecontractor.ui.components.FlameFilterChipItem
import io.github.oinzc.flamecontractor.ui.components.FlameFilterChipRow
import io.github.oinzc.flamecontractor.ui.components.FlameGroupedSection
import io.github.oinzc.flamecontractor.ui.components.FlameIconButton
import io.github.oinzc.flamecontractor.ui.components.FlameListRow
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameModalSheet
import io.github.oinzc.flamecontractor.ui.components.FlameRowAvatar
import io.github.oinzc.flamecontractor.ui.components.FlameSearchPill
import io.github.oinzc.flamecontractor.ui.components.FlameSheetActionRow
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.FlameTopBar
import io.github.oinzc.flamecontractor.ui.components.FlameTopPillButton
import io.github.oinzc.flamecontractor.ui.components.flameDockBottomPadding
import io.github.oinzc.flamecontractor.ui.components.resolveFlameToneColors
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HistoryRoute(
    database: FlameDatabase,
    onNavigateToTodayReview: () -> Unit,
    onOpenSettings: (SettingsSection?) -> Unit = {},
    modifier: Modifier = Modifier,
    accountId: String = DEFAULT_ACCOUNT_ID,
    diagnosticScreenshotStore: DiagnosticScreenshotStore? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val resolvedScreenshotStore = diagnosticScreenshotStore ?: remember(context) {
        DiagnosticScreenshotStore(context)
    }
    val factory = remember(database, accountId) {
        HistoryViewModel.factory(database = database, accountId = accountId)
    }
    val viewModel: HistoryViewModel = viewModel(
        key = "history_$accountId",
        factory = factory,
    )
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var isExportingCsv by remember { mutableStateOf(false) }

    LaunchedEffect(accountId) {
        viewModel.refresh()
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isExportingCsv = true
            try {
                val csvText = viewModel.buildExportCsv()
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("无法打开文件写入流")
                    stream.use { out ->
                        out.write(csvText.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
                snackbarHostState.showSnackbar("脱敏执行记录 CSV 已导出")
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                snackbarHostState.showSnackbar("导出失败：${err.message ?: "无法写入所选文件"}")
            } finally {
                isExportingCsv = false
            }
        }
    }

    HistoryScreen(
        state = uiState,
        snackbarHostState = snackbarHostState,
        isExportingCsv = isExportingCsv,
        onSelectWindowDays = viewModel::setWindowDays,
        onExportCsvClick = {
            try {
                exportCsvLauncher.launch(viewModel.defaultExportFileName())
            } catch (_: ActivityNotFoundException) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("系统未提供文件保存选择器，无法导出 CSV")
                }
            }
        },
        onToggleDate = viewModel::toggleSelectedDate,
        onClearDateFilter = viewModel::clearDateFilter,
        onClearAllFilters = viewModel::clearAllFilters,
        onSelectStatusFilter = viewModel::setStatusFilter,
        onSelectJob = viewModel::selectJob,
        onDismissJobDetail = viewModel::dismissJobDetail,
        onNavigateToTodayReview = onNavigateToTodayReview,
        onOpenSettings = { onOpenSettings(null) },
        resolveAttemptScreenshotFile = { attemptId ->
            resolvedScreenshotStore.resolveAttemptFile(attemptId)
        },
        resolveJobScreenshotFile = { jobId ->
            resolvedScreenshotStore.resolveJobLatestFile(jobId)
        },
        modifier = modifier,
    )
}

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    snackbarHostState: SnackbarHostState,
    isExportingCsv: Boolean,
    onSelectWindowDays: (Int) -> Unit,
    onExportCsvClick: () -> Unit,
    onToggleDate: (LocalDate) -> Unit,
    onClearDateFilter: () -> Unit,
    onClearAllFilters: () -> Unit,
    onSelectStatusFilter: (HistoryStatusFilter) -> Unit,
    onSelectJob: (String) -> Unit,
    onDismissJobDetail: () -> Unit,
    onNavigateToTodayReview: () -> Unit,
    onOpenSettings: () -> Unit = {},
    resolveAttemptScreenshotFile: (String) -> File? = { null },
    resolveJobScreenshotFile: (String) -> File? = { null },
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    var searchQuery by remember { mutableStateOf("") }
    val trimmedQuery = searchQuery.trim()
    val displayJobs = remember(state.filteredJobs, trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            state.filteredJobs
        } else {
            val q = trimmedQuery.lowercase(Locale.ROOT)
            state.filteredJobs.filter { item ->
                item.targetName.lowercase(Locale.ROOT).contains(q) ||
                    item.job.businessDate.contains(trimmedQuery) ||
                    (item.job.summaryDetail?.lowercase(Locale.ROOT)?.contains(q) == true)
            }
        }
    }
    val groupedByDate = remember(displayJobs) {
        displayJobs.groupBy { it.job.businessDate }.entries.toList()
    }
    var userToggledDates by remember(state.windowDays, state.selectedDate, state.statusFilter, trimmedQuery) {
        mutableStateOf<Map<String, Boolean>>(emptyMap())
    }
    var expandAllOverride by remember(state.windowDays, state.selectedDate, state.statusFilter, trimmedQuery) {
        mutableStateOf<Boolean?>(null)
    }
    var showAllForDates by remember(state.windowDays, state.selectedDate, state.statusFilter, trimmedQuery) {
        mutableStateOf<Set<String>>(emptySet())
    }

    val windowChips = remember {
        listOf(
            FlameFilterChipItem(key = "7", label = "近 7 天"),
            FlameFilterChipItem(key = "14", label = "近 14 天"),
            FlameFilterChipItem(key = "30", label = "近 30 天"),
        )
    }
    val statusChips = remember(state.filterCounts) {
        HistoryStatusFilter.entries.map { filter ->
            FlameFilterChipItem(
                key = filter.name,
                label = filter.label,
                count = state.filterCounts[filter] ?: 0,
            )
        }
    }
    val startDate = remember(state.today, state.windowDays) {
        state.today.minusDays((state.windowDays - 1).toLong())
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = palette.canvas,
        contentColor = palette.ink,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 86.dp),
            )
        },
        topBar = {
            FlameTopBar(
                title = "记录",
                actions = {
                    FlameTopPillButton(
                        text = if (isExportingCsv) "导出中…" else "导出 CSV",
                        leadingIcon = if (isExportingCsv) null else Icons.Outlined.FileDownload,
                        enabled = !isExportingCsv,
                        onClick = onExportCsvClick,
                    )
                    FlameIconButton(
                        icon = Icons.Outlined.Settings,
                        contentDescription = "设置与维护",
                        onClick = onOpenSettings,
                    )
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 860.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = flameDockBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 时间窗分段器 + 区间说明
                item(key = "window_chips") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FlameFilterChipRow(
                            items = windowChips,
                            selectedKey = state.windowDays.toString(),
                            onSelect = { key ->
                                key.toIntOrNull()?.let(onSelectWindowDays)
                            },
                        )
                        Text(
                            text = "统计区间：$startDate 至 ${state.today} · CSV 导出仅含脱敏字段",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkTertiary,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }

                // 3 列等宽 Bento 指标块（成功率 / 总成功 / 待核对）
                item(key = "bento_kpis") {
                    HistoryBentoRow(
                        summary = state.summary,
                        onNavigateToTodayReview = onNavigateToTodayReview,
                    )
                }

                // 可交互圆角堆叠每日趋势柱状图卡（含当前连胜 · 最佳连胜）
                item(key = "trend_chart") {
                    DailyTrendChartCard(
                        summary = state.summary,
                        selectedDate = state.selectedDate,
                        onToggleDate = onToggleDate,
                        onClearDateFilter = onClearDateFilter,
                    )
                }

                // 明细筛选区：标题 + 展开控制 + 状态筛选芯片 + 胶囊搜索框
                item(key = "detail_filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (state.selectedDate != null) {
                                    "执行明细 · ${state.selectedDate}"
                                } else {
                                    "近 ${state.windowDays} 天执行明细"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            FlameMiniTag(
                                text = "${displayJobs.size} 条",
                                tone = FlameTone.NEUTRAL,
                            )
                            if (groupedByDate.size > 1) {
                                val showAllGroups = expandAllOverride == true
                                Text(
                                    text = if (showAllGroups) "仅看最新" else "展开全部日期",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.inkSecondary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .clickable {
                                            userToggledDates = emptyMap()
                                            expandAllOverride = if (showAllGroups) null else true
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                )
                            }
                        }

                        FlameFilterChipRow(
                            items = statusChips,
                            selectedKey = state.statusFilter.name,
                            onSelect = { key ->
                                HistoryStatusFilter.entries
                                    .firstOrNull { it.name == key }
                                    ?.let(onSelectStatusFilter)
                            },
                        )

                        FlameSearchPill(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            placeholder = "按好友名、日期或摘要筛选明细…",
                        )
                    }
                }

                // 明细列表：加载态 / 空态 / 按日期折叠分组的 FlameGroupedSection
                if (state.isLoading) {
                    item(key = "loading") {
                        FlameCard(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 28.dp),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.5.dp,
                                    color = palette.monoCtaContainer,
                                )
                            }
                        }
                    }
                } else if (displayJobs.isEmpty()) {
                    item(key = "empty") {
                        val hasActiveFilter = state.selectedDate != null ||
                            state.statusFilter != HistoryStatusFilter.ALL ||
                            trimmedQuery.isNotEmpty()
                        FlameEmptyStateCard(
                            icon = Icons.Outlined.History,
                            title = if (state.totalJobsInWindow == 0) {
                                "近 ${state.windowDays} 天暂无执行记录"
                            } else {
                                "当前筛选条件下无记录"
                            },
                            subtitle = if (state.totalJobsInWindow == 0) {
                                "当每日自动续火或单发测试产生执行记录后，可在此回溯每日趋势与尝试耗时。"
                            } else {
                                "可尝试切换状态筛选芯片、清除日期过滤或清空搜索关键词。"
                            },
                            actionLabel = if (hasActiveFilter) "重置全部筛选" else null,
                            onAction = if (hasActiveFilter) {
                                {
                                    searchQuery = ""
                                    onClearAllFilters()
                                }
                            } else {
                                null
                            },
                        )
                    }
                } else {
                    groupedByDate.forEachIndexed { groupIndex, entry ->
                        val dateKey = entry.key
                        val groupItems = entry.value
                        val defaultExpanded = state.selectedDate != null ||
                            state.statusFilter != HistoryStatusFilter.ALL ||
                            trimmedQuery.isNotEmpty() ||
                            groupIndex == 0
                        val isExpanded = userToggledDates[dateKey] ?: expandAllOverride ?: defaultExpanded
                        val showAllForDate = dateKey in showAllForDates

                        item(key = "date_group_$dateKey") {
                            HistoryDateGroupSection(
                                businessDate = dateKey,
                                groupItems = groupItems,
                                isExpanded = isExpanded,
                                showAllForDate = showAllForDate,
                                onToggleExpand = {
                                    userToggledDates = userToggledDates + (dateKey to !isExpanded)
                                },
                                onToggleShowAll = {
                                    showAllForDates = if (showAllForDate) {
                                        showAllForDates - dateKey
                                    } else {
                                        showAllForDates + dateKey
                                    }
                                },
                                onSelectJob = onSelectJob,
                            )
                        }
                    }
                }
            }
        }
    }

    state.selectedJobDetail?.let { detail ->
        HistoryJobTimelineSheet(
            detail = detail,
            resolveAttemptScreenshotFile = resolveAttemptScreenshotFile,
            resolveJobScreenshotFile = resolveJobScreenshotFile,
            onDismiss = onDismissJobDetail,
            onNavigateToTodayReview = {
                onDismissJobDetail()
                onNavigateToTodayReview()
            },
        )
    }
}

@Composable
private fun HistoryBentoRow(
    summary: HistoryStatsSummary,
    onNavigateToTodayReview: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val rateDigits = summary.successRate?.let { formatSuccessRateDigits(it) } ?: "--"
    val rateSuffix = if (summary.successRate != null) "%" else null
    val hasUnknown = summary.unknownJobs > 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlameBentoCard(
            label = "成功率",
            value = rateDigits,
            valueSuffix = rateSuffix,
            valueColor = palette.ink,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            footer = {
                Text(
                    text = if (summary.totalJobs > 0) "共 ${summary.totalJobs} 条" else "暂无数据",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )

        FlameBentoCard(
            label = "总成功",
            value = summary.succeededJobs.toString(),
            valueSuffix = "次",
            valueColor = semantics.healthy,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            footer = {
                Text(
                    text = if (summary.cancelledJobs > 0) {
                        "取消 ${summary.cancelledJobs} 条"
                    } else {
                        "已确认送达"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )

        FlameBentoCard(
            label = "待核对",
            value = summary.unknownJobs.toString(),
            valueSuffix = if (hasUnknown) "条" else null,
            valueColor = if (hasUnknown) semantics.warning else palette.ink,
            onClick = if (hasUnknown) onNavigateToTodayReview else null,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            footer = {
                when {
                    hasUnknown -> {
                        Text(
                            text = "去核对 ›",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = semantics.warning,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    summary.failedJobs > 0 -> {
                        Text(
                            text = "失败 ${summary.failedJobs} 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = semantics.danger,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    else -> {
                        Text(
                            text = "全部正常",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DailyTrendChartCard(
    summary: HistoryStatsSummary,
    selectedDate: LocalDate?,
    onToggleDate: (LocalDate) -> Unit,
    onClearDateFilter: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val maxDayTotal = summary.daily.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1

    FlameCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        contentPadding = PaddingValues(15.dp),
    ) {
        // 卡片标题 + 连胜摘要副行 + 已选日期清除按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "每日发送趋势",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocalFireDepartment,
                        contentDescription = null,
                        tint = semantics.sparkGold,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "当前连胜 ${summary.currentSuccessStreak} 天 · 最佳 ${summary.bestSuccessStreak} 天",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = palette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (selectedDate != null) {
                Surface(
                    onClick = onClearDateFilter,
                    shape = RoundedCornerShape(999.dp),
                    color = palette.surfaceSubtle,
                    contentColor = palette.inkSecondary,
                    border = BorderStroke(1.dp, palette.hairline),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "清除日期筛选",
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = "清除",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 紧凑图例
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChartLegendDot(color = semantics.healthy, label = "成功")
            ChartLegendDot(color = semantics.warning, label = "待核对")
            ChartLegendDot(color = semantics.danger, label = "失败")
            if (summary.cancelledJobs > 0) {
                ChartLegendDot(color = semantics.neutral, label = "取消")
            }
            ChartLegendDot(color = palette.hairlineStrong, label = "无任务")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 可交互堆叠圆角柱状图（7天均分 / 宽屏全览 / 14&30天横向滚动默认定位最新日期）
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isScrollableMode = summary.daily.size > 7 && maxWidth < 560.dp
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    summary.daily.size <= 7 -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            summary.daily.forEach { bucket ->
                                DayStackedBarColumn(
                                    bucket = bucket,
                                    maxDayTotal = maxDayTotal,
                                    isSelected = bucket.date == selectedDate,
                                    showLabel = true,
                                    reserveLabelSpace = true,
                                    maxBarWidth = 30.dp,
                                    horizontalPadding = 2.dp,
                                    onClick = { onToggleDate(bucket.date) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    !isScrollableMode -> {
                        val tickIndices = remember(summary.daily.size) {
                            computeAxisTickIndices(summary.daily.size, 6)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                summary.daily.forEach { bucket ->
                                    DayStackedBarColumn(
                                        bucket = bucket,
                                        maxDayTotal = maxDayTotal,
                                        isSelected = bucket.date == selectedDate,
                                        showLabel = false,
                                        reserveLabelSpace = false,
                                        maxBarWidth = 18.dp,
                                        horizontalPadding = 1.dp,
                                        onClick = { onToggleDate(bucket.date) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                tickIndices.forEach { idx ->
                                    val date = summary.daily[idx].date
                                    val isTickSelected = date == selectedDate
                                    Text(
                                        text = SHORT_DAY_FORMATTER.format(date),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isTickSelected) palette.ink else palette.inkTertiary,
                                        fontWeight = if (isTickSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        val listState = rememberLazyListState()
                        LaunchedEffect(summary.windowDays, summary.daily.lastOrNull()?.date) {
                            if (summary.daily.isNotEmpty() && summary.windowDays > 7) {
                                listState.scrollToItem(summary.daily.lastIndex)
                            }
                        }
                        val tickStep = if (summary.windowDays <= 14) 2 else 5
                        LazyRow(
                            state = listState,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            itemsIndexed(
                                items = summary.daily,
                                key = { _, bucket -> bucket.date.toString() },
                            ) { index, bucket ->
                                val isLast = index == summary.daily.lastIndex
                                val isSelected = bucket.date == selectedDate
                                val showDateTick = isSelected || index == 0 || isLast || index % tickStep == 0
                                DayStackedBarColumn(
                                    bucket = bucket,
                                    maxDayTotal = maxDayTotal,
                                    isSelected = isSelected,
                                    showLabel = showDateTick,
                                    reserveLabelSpace = true,
                                    maxBarWidth = 18.dp,
                                    horizontalPadding = 2.dp,
                                    onClick = { onToggleDate(bucket.date) },
                                    modifier = Modifier.width(42.dp),
                                )
                            }
                        }
                    }
                }

                val selectedBucket = selectedDate?.let { date ->
                    summary.daily.firstOrNull { it.date == date }
                }
                if (selectedBucket != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = palette.surfaceSubtle,
                        border = BorderStroke(1.dp, palette.hairlineStrong),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val detailText = buildString {
                                append("${selectedBucket.date}：成功 ${selectedBucket.succeeded}")
                                append(" · 待核对 ${selectedBucket.unknown}")
                                append(" · 失败 ${selectedBucket.failed}")
                                if (selectedBucket.cancelled > 0) {
                                    append(" · 取消 ${selectedBucket.cancelled}")
                                }
                            }
                            Text(
                                text = detailText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = palette.ink,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "取消筛选",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.monoCtaContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onClearDateFilter)
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                } else {
                    Text(
                        text = if (isScrollableMode) {
                            "点击任意柱条筛选当日明细，左右滑动查看更多日期"
                        } else {
                            "点击任意日期柱条可筛选当日明细，再次点击取消"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayStackedBarColumn(
    bucket: DailyJobBucket,
    maxDayTotal: Int,
    isSelected: Boolean,
    showLabel: Boolean,
    reserveLabelSpace: Boolean,
    maxBarWidth: Dp,
    horizontalPadding: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val maxBarHeightDp = 96f
    val targetBarHeight = if (bucket.total == 0) {
        3.dp
    } else {
        (maxBarHeightDp * (bucket.total.toFloat() / maxDayTotal.toFloat()))
            .coerceIn(14f, maxBarHeightDp)
            .dp
    }
    val animatedHeight by animateDpAsState(
        targetValue = targetBarHeight,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "history_bar_height_${bucket.date}",
    )

    val containerColor = if (isSelected) palette.surfaceSubtle else Color.Transparent
    val borderColor = if (isSelected) palette.monoCtaContainer else Color.Transparent

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp, horizontal = horizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(98.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (bucket.total == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .widthIn(max = maxBarWidth)
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.hairlineStrong.copy(alpha = 0.7f)),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.66f)
                        .widthIn(max = maxBarWidth)
                        .height(animatedHeight)
                        .clip(RoundedCornerShape(6.dp)),
                ) {
                    if (bucket.cancelled > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(bucket.cancelled.toFloat())
                                .background(semantics.neutral),
                        )
                    }
                    if (bucket.failed > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(bucket.failed.toFloat())
                                .background(semantics.danger),
                        )
                    }
                    if (bucket.unknown > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(bucket.unknown.toFloat())
                                .background(semantics.warning),
                        )
                    }
                    if (bucket.succeeded > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(bucket.succeeded.toFloat())
                                .background(semantics.healthy),
                        )
                    }
                }
            }
        }

        if (reserveLabelSpace) {
            Text(
                text = if (showLabel) SHORT_DAY_FORMATTER.format(bucket.date) else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) palette.ink else palette.inkTertiary,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun ChartLegendDot(
    color: Color,
    label: String,
) {
    val palette = FlameThemeTokens.palette
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.inkSecondary,
        )
    }
}

@Composable
private fun HistoryDateGroupSection(
    businessDate: String,
    groupItems: List<HistoryJobItem>,
    isExpanded: Boolean,
    showAllForDate: Boolean,
    onToggleExpand: () -> Unit,
    onToggleShowAll: () -> Unit,
    onSelectJob: (String) -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val summaryText = remember(groupItems) {
        val succeededCount = groupItems.count { it.job.status == JobStatus.SUCCEEDED }
        val unknownCount = groupItems.count { it.job.status == JobStatus.UNKNOWN_REQUIRES_REVIEW }
        val failedCount = groupItems.count {
            it.job.status == JobStatus.FAILED_RETRYABLE || it.job.status == JobStatus.FAILED_TERMINAL
        }
        buildList {
            if (succeededCount > 0) add("成功 $succeededCount")
            if (unknownCount > 0) add("待核对 $unknownCount")
            if (failedCount > 0) add("失败 $failedCount")
        }.joinToString(" · ")
    }

    FlameGroupedSection {
        // 可点击折叠/展开的日期分组头行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = businessDate,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
            )
            FlameMiniTag(
                text = "${groupItems.size} 条",
                tone = FlameTone.NEUTRAL,
            )
            if (summaryText.isNotEmpty()) {
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            Icon(
                imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (isExpanded) "收起该日明细" else "展开该日明细",
                tint = palette.inkTertiary,
                modifier = Modifier.size(19.dp),
            )
        }

        if (isExpanded) {
            HorizontalDivider(thickness = 1.dp, color = palette.hairline)

            val visibleItems = if (showAllForDate) {
                groupItems
            } else {
                groupItems.take(DEFAULT_VISIBLE_JOBS_PER_DAY)
            }
            val hasPagination = groupItems.size > DEFAULT_VISIBLE_JOBS_PER_DAY

            visibleItems.forEachIndexed { idx, item ->
                HistoryJobListRow(
                    item = item,
                    showDivider = idx < visibleItems.lastIndex || hasPagination,
                    onClick = { onSelectJob(item.job.id) },
                )
            }

            if (hasPagination) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleShowAll)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (showAllForDate) {
                            "收起至前 $DEFAULT_VISIBLE_JOBS_PER_DAY 条"
                        } else {
                            "展开当日剩余 ${groupItems.size - DEFAULT_VISIBLE_JOBS_PER_DAY} 条（共 ${groupItems.size} 条）"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.inkSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryJobListRow(
    item: HistoryJobItem,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val job = item.job
    val style = jobStatusStyle(job.status)
    val subtitle = remember(job) {
        val timeText = job.confirmedAt?.let { formatEpochTimeShort(it) }
            ?: formatEpochTimeShort(job.updatedAt)
        val parts = buildList {
            add(timeText)
            add("尝试 ${job.attemptCount} 次")
            job.failureClass?.let { add(failureClassLabel(it)) }
            job.summaryDetail
                ?.replace('\n', ' ')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { add(it) }
        }
        parts.joinToString(" · ")
    }

    FlameListRow(
        title = item.targetName,
        subtitle = subtitle,
        showDivider = showDivider,
        dividerIndent = 60.dp,
        onClick = onClick,
        leading = {
            FlameRowAvatar(tone = style.tone) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        titleTag = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (item.targetDeleted) {
                    FlameMiniTag(text = "已删除", tone = FlameTone.NEUTRAL)
                }
                FlameMiniTag(
                    text = if (item.isDailySlot) "每日" else "测试",
                    tone = FlameTone.NEUTRAL,
                )
            }
        },
        trailing = {
            FlameStatusPill(
                text = style.label,
                tone = style.tone,
                showDot = false,
            )
        },
    )
}

@Composable
private fun HistoryJobTimelineSheet(
    detail: HistoryJobDetailUiState,
    resolveAttemptScreenshotFile: (String) -> File?,
    resolveJobScreenshotFile: (String) -> File?,
    onDismiss: () -> Unit,
    onNavigateToTodayReview: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val item = detail.item
    val job = item.job
    val style = jobStatusStyle(job.status)
    val sheetTitle = if (item.targetDeleted) {
        "${item.targetName}（已删除）"
    } else {
        item.targetName
    }
    val attemptScreenshots = remember(detail.attempts) {
        detail.attempts.associate { attempt ->
            val file = if (attempt.status != AttemptStatus.SUCCEEDED || job.status == JobStatus.UNKNOWN_REQUIRES_REVIEW) {
                resolveAttemptScreenshotFile(attempt.id)
            } else {
                null
            }
            attempt.id to file
        }
    }
    val hasAnyAttemptScreenshot = remember(attemptScreenshots) {
        attemptScreenshots.values.any { it != null }
    }
    val fallbackJobScreenshot = remember(job.id, job.status, job.updatedAt, detail.isLoadingAttempts, hasAnyAttemptScreenshot) {
        if (!detail.isLoadingAttempts && job.status != JobStatus.SUCCEEDED && !hasAnyAttemptScreenshot) {
            resolveJobScreenshotFile(job.id)
        } else {
            null
        }
    }

    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = sheetTitle,
        subtitle = "${job.businessDate} · ${if (item.isDailySlot) "每日自动/批量槽位" else "单发测试槽位"}",
        trailingHeader = {
            FlameStatusPill(
                text = style.label,
                tone = style.tone,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 任务元信息摘要卡
            FlameCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                containerColor = palette.surfaceSubtle,
                borderColor = palette.hairline,
                contentPadding = PaddingValues(13.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TimelineMetaRow(label = "执行状态", value = "${style.label} · 总尝试 ${job.attemptCount} 次")
                    job.failureClass?.let { fc ->
                        TimelineMetaRow(label = "失败分类", value = failureClassLabel(fc))
                    }
                    job.scheduledAt?.let { scheduledAt ->
                        TimelineMetaRow(label = "计划时刻", value = formatEpochTimestampWithSec(scheduledAt))
                    }
                    job.confirmedAt?.let { confirmedAt ->
                        TimelineMetaRow(label = "确认完成", value = formatEpochTimestampWithSec(confirmedAt))
                    }
                    job.summaryDetail?.takeIf { it.isNotBlank() }?.let { summary ->
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = palette.hairline,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                        Text(
                            text = "脱敏摘要",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkTertiary,
                        )
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.ink,
                        )
                    }
                }
            }

            Text(
                text = "发送尝试时间线",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.inkTertiary,
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            when {
                detail.isLoadingAttempts -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(999.dp)),
                            color = palette.monoCtaContainer,
                            trackColor = palette.surfaceSubtle,
                        )
                        Text(
                            text = "正在加载尝试明细…",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                        )
                    }
                }

                detail.attemptsLoadError != null -> {
                    Surface(
                        color = semantics.dangerContainer,
                        border = BorderStroke(1.dp, semantics.dangerBorder),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = detail.attemptsLoadError,
                            style = MaterialTheme.typography.bodySmall,
                            color = semantics.onDangerContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }

                detail.attempts.isEmpty() -> {
                    Surface(
                        color = palette.surfaceSubtle,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "暂无底层发送尝试记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        detail.attempts.forEach { attempt ->
                            HistoryAttemptTimelineItem(
                                attempt = attempt,
                                screenshotFile = attemptScreenshots[attempt.id],
                            )
                        }
                    }
                }
            }

            if (fallbackJobScreenshot != null) {
                DiagnosticScreenshotCard(
                    file = fallbackJobScreenshot,
                    title = "现场诊断截图",
                    refreshKey = job.updatedAt,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (job.status == JobStatus.UNKNOWN_REQUIRES_REVIEW) {
                FlameSheetActionRow(
                    text = "去今日核对",
                    trailingText = "人工确认发送结果",
                    onClick = onNavigateToTodayReview,
                    primary = true,
                    danger = true,
                )
            }
            FlameSheetActionRow(
                text = "关闭",
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun TimelineMetaRow(
    label: String,
    value: String,
) {
    val palette = FlameThemeTokens.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkTertiary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = palette.ink,
        )
    }
}

@Composable
private fun HistoryAttemptTimelineItem(
    attempt: SendAttemptEntity,
    screenshotFile: File? = null,
) {
    val palette = FlameThemeTokens.palette
    val (statusLabel, statusTone) = when (attempt.status) {
        AttemptStatus.SUCCEEDED -> "成功" to FlameTone.HEALTHY
        AttemptStatus.FAILED -> "失败" to FlameTone.DANGER
        AttemptStatus.RUNNING -> "进行中" to FlameTone.EMBER
    }
    val toneColors = resolveFlameToneColors(statusTone)
    val stageLabel = when (attempt.stage) {
        "opening_chat" -> "打开会话"
        "sending" -> "写入发送"
        "confirming" -> "等待确认"
        "auth_probe" -> "登录探测"
        else -> attempt.stage
    }
    val methodLabel = when (attempt.inputMethodUsed) {
        "js_execCommand" -> "JS 写入"
        "android_ime" -> "IME 输入"
        "sticker_click" -> "表情点击"
        "image_chooser" -> "图片选择上传"
        "image_datatransfer" -> "图片注入上传"
        "image_upload" -> "图片上传"
        null -> "--"
        else -> attempt.inputMethodUsed
    }
    val durationSuffix = attempt.finishedAt?.let { finished ->
        val elapsedMs = (finished - attempt.startedAt).coerceAtLeast(0L)
        if (elapsedMs < 1_000L) {
            "耗时 ${elapsedMs}ms"
        } else {
            String.format(Locale.US, "耗时 %.1fs", elapsedMs / 1000.0)
        }
    } ?: "尚未结束"
    val startText = formatEpochTimeOnly(attempt.startedAt)
    val endText = attempt.finishedAt?.let { formatEpochTimeOnly(it) } ?: "进行中"

    FlameCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 14.dp,
        containerColor = palette.surface,
        borderColor = palette.hairline,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(toneColors.accent),
            )
            Text(
                text = "第 ${attempt.attemptNo} 次 · $stageLabel",
                style = MaterialTheme.typography.titleSmall,
                color = palette.ink,
                modifier = Modifier.weight(1f),
            )
            FlameMiniTag(text = statusLabel, tone = statusTone)
        }

        Spacer(modifier = Modifier.height(6.dp))

        val metaParts = buildList {
            add("方式：$methodLabel")
            attempt.failureClass?.let { add("分类：${failureClassLabel(it)}") }
            attempt.errorCode?.takeIf { it.isNotBlank() }?.let { add("错误码：$it") }
        }
        Text(
            text = metaParts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkSecondary,
        )

        Spacer(modifier = Modifier.height(3.dp))

        Text(
            text = "$startText – $endText（$durationSuffix）",
            style = MaterialTheme.typography.labelSmall,
            color = palette.inkTertiary,
        )

        if (screenshotFile != null) {
            Spacer(modifier = Modifier.height(8.dp))
            DiagnosticScreenshotCard(
                file = screenshotFile,
                title = "第 ${attempt.attemptNo} 次尝试现场截图",
                refreshKey = attempt.finishedAt ?: attempt.startedAt,
            )
        }
    }
}

private data class JobStatusStyle(
    val label: String,
    val tone: FlameTone,
    val icon: ImageVector,
)

private fun jobStatusStyle(status: JobStatus): JobStatusStyle = when (status) {
    JobStatus.SUCCEEDED -> JobStatusStyle(
        label = "已成功",
        tone = FlameTone.HEALTHY,
        icon = Icons.Outlined.CheckCircle,
    )

    JobStatus.UNKNOWN_REQUIRES_REVIEW -> JobStatusStyle(
        label = "待核对",
        tone = FlameTone.WARNING,
        icon = Icons.AutoMirrored.Outlined.HelpOutline,
    )

    JobStatus.FAILED_RETRYABLE -> JobStatusStyle(
        label = "待重试",
        tone = FlameTone.WARNING,
        icon = Icons.Outlined.Refresh,
    )

    JobStatus.FAILED_TERMINAL -> JobStatusStyle(
        label = "失败",
        tone = FlameTone.DANGER,
        icon = Icons.Outlined.ErrorOutline,
    )

    JobStatus.CANCELLED -> JobStatusStyle(
        label = "已取消",
        tone = FlameTone.NEUTRAL,
        icon = Icons.Outlined.Cancel,
    )

    JobStatus.SCHEDULED -> JobStatusStyle(
        label = "已计划",
        tone = FlameTone.NEUTRAL,
        icon = Icons.Outlined.Schedule,
    )

    JobStatus.LEASED,
    JobStatus.OPENING_CHAT,
    JobStatus.SENDING,
    JobStatus.CONFIRMING,
    -> JobStatusStyle(
        label = "执行中",
        tone = FlameTone.EMBER,
        icon = Icons.Outlined.WarningAmber,
    )
}

private fun computeAxisTickIndices(totalCount: Int, targetTicks: Int = 6): List<Int> {
    if (totalCount <= 0) return emptyList()
    if (totalCount == 1) return listOf(0)
    if (totalCount <= targetTicks) return (0 until totalCount).toList()
    val indices = LinkedHashSet<Int>(targetTicks)
    for (i in 0 until targetTicks) {
        val ratio = i.toFloat() / (targetTicks - 1).toFloat()
        val index = (ratio * (totalCount - 1).toFloat()).roundToInt().coerceIn(0, totalCount - 1)
        indices.add(index)
    }
    indices.add(0)
    indices.add(totalCount - 1)
    return indices.sorted()
}

private fun formatSuccessRateDigits(rate: Double): String {
    val pct = (rate * 100.0).coerceIn(0.0, 100.0)
    return if (pct == 0.0 || pct == 100.0) {
        String.format(Locale.US, "%.0f", pct)
    } else {
        String.format(Locale.US, "%.1f", pct)
    }
}

private fun formatEpochTimeShort(epochMs: Long): String {
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
    return TIME_SHORT_FORMATTER.format(zdt)
}

private fun formatEpochTimestampWithSec(epochMs: Long): String {
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
    return DATE_TIME_SEC_FORMATTER.format(zdt)
}

private fun formatEpochTimeOnly(epochMs: Long): String {
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
    return TIME_SEC_FORMATTER.format(zdt)
}

private fun failureClassLabel(failureClass: FailureClass): String = when (failureClass) {
    FailureClass.NETWORK -> "网络异常"
    FailureClass.AUTH -> "登录失效"
    FailureClass.VERIFICATION -> "风控验证"
    FailureClass.TARGET_UNCERTAIN -> "目标/身份不确定"
    FailureClass.DOM_CHANGED -> "页面结构变化"
    FailureClass.UNKNOWN_OUTCOME -> "发送结果未明"
    FailureClass.OTHER -> "其他错误"
}

private const val DEFAULT_VISIBLE_JOBS_PER_DAY = 10

private val SHORT_DAY_FORMATTER = DateTimeFormatter.ofPattern("M/d")
private val TIME_SHORT_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME_SEC_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
private val TIME_SEC_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
