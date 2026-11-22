package io.github.oinzc.flamecontractor.ui.history

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.domain.DailyJobBucket
import io.github.oinzc.flamecontractor.domain.HistoryStatsSummary
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val factory = remember(database) { HistoryViewModel.factory(database) }
    val viewModel: HistoryViewModel = viewModel(factory = factory)
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var isExportingCsv by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
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
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
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
    modifier: Modifier = Modifier,
) {
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val isWide = maxWidth >= 600.dp
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") {
                    HistoryHeaderSection(
                        windowDays = state.windowDays,
                        today = state.today,
                        isExportingCsv = isExportingCsv,
                        onSelectWindowDays = onSelectWindowDays,
                        onExportCsvClick = onExportCsvClick,
                    )
                }

                item(key = "kpi") {
                    HistoryKpiGrid(
                        summary = state.summary,
                        onNavigateToTodayReview = onNavigateToTodayReview,
                    )
                }

                item(key = "chart") {
                    DailyTrendChartCard(
                        summary = state.summary,
                        selectedDate = state.selectedDate,
                        onToggleDate = onToggleDate,
                        onClearDateFilter = onClearDateFilter,
                    )
                }

                item(key = "filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (state.selectedDate != null) {
                                    "执行明细 · ${state.selectedDate}"
                                } else {
                                    "近 ${state.windowDays} 天执行明细"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "共 ${displayJobs.size} 条 · 点击查看时间线",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (groupedByDate.size > 1) {
                                    val showAllGroups = expandAllOverride == true
                                    TextButton(
                                        onClick = {
                                            userToggledDates = emptyMap()
                                            expandAllOverride = if (showAllGroups) null else true
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = if (showAllGroups) "仅看最新" else "展开全部日期",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }
                        }

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(HistoryStatusFilter.entries) { filter ->
                                val count = state.filterCounts[filter] ?: 0
                                FilterChip(
                                    selected = state.statusFilter == filter,
                                    onClick = { onSelectStatusFilter(filter) },
                                    label = { Text("${filter.label} ($count)") },
                                )
                            }
                        }

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("按好友名、日期或摘要筛选明细…") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "清除搜索",
                                        )
                                    }
                                }
                            },
                        )
                    }
                }

                if (state.isLoading) {
                    item(key = "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (displayJobs.isEmpty()) {
                    item(key = "empty") {
                        EmptyHistoryCard(
                            windowDays = state.windowDays,
                            hasAnyJobsInWindow = state.totalJobsInWindow > 0,
                            hasActiveFilter = state.selectedDate != null ||
                                state.statusFilter != HistoryStatusFilter.ALL ||
                                trimmedQuery.isNotEmpty(),
                            onClearAllFilters = {
                                searchQuery = ""
                                onClearAllFilters()
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

                        item(key = "date_hdr_$dateKey") {
                            HistoryDateGroupHeader(
                                businessDate = dateKey,
                                items = groupItems,
                                isExpanded = isExpanded,
                                onToggleExpand = {
                                    userToggledDates = userToggledDates + (dateKey to !isExpanded)
                                },
                            )
                        }

                        if (isExpanded) {
                            val visibleItems = if (showAllForDate) {
                                groupItems
                            } else {
                                groupItems.take(DEFAULT_VISIBLE_JOBS_PER_DAY)
                            }
                            if (isWide) {
                                items(
                                    items = visibleItems.chunked(2),
                                    key = { pair -> "row_${dateKey}_${pair.first().job.id}" },
                                ) { pair ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(IntrinsicSize.Max),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        HistoryJobCard(
                                            item = pair[0],
                                            onClick = { onSelectJob(pair[0].job.id) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight(),
                                        )
                                        if (pair.size > 1) {
                                            HistoryJobCard(
                                                item = pair[1],
                                                onClick = { onSelectJob(pair[1].job.id) },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight(),
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            } else {
                                items(
                                    items = visibleItems,
                                    key = { it.job.id },
                                ) { item ->
                                    HistoryJobCard(
                                        item = item,
                                        onClick = { onSelectJob(item.job.id) },
                                    )
                                }
                            }

                            if (groupItems.size > DEFAULT_VISIBLE_JOBS_PER_DAY) {
                                item(key = "date_more_$dateKey") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        TextButton(
                                            onClick = {
                                                showAllForDates = if (showAllForDate) {
                                                    showAllForDates - dateKey
                                                } else {
                                                    showAllForDates + dateKey
                                                }
                                            },
                                        ) {
                                            Text(
                                                text = if (showAllForDate) {
                                                    "收起至前 $DEFAULT_VISIBLE_JOBS_PER_DAY 条"
                                                } else {
                                                    "展开当日剩余 ${groupItems.size - DEFAULT_VISIBLE_JOBS_PER_DAY} 条（共 ${groupItems.size} 条）"
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    state.selectedJobDetail?.let { detail ->
        HistoryJobDetailDialog(
            detail = detail,
            onDismiss = onDismissJobDetail,
            onNavigateToTodayReview = {
                onDismissJobDetail()
                onNavigateToTodayReview()
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryHeaderSection(
    windowDays: Int,
    today: LocalDate,
    isExportingCsv: Boolean,
    onSelectWindowDays: (Int) -> Unit,
    onExportCsvClick: () -> Unit,
) {
    val startDate = today.minusDays((windowDays - 1).toLong())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "历史与统计",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "统计区间：$startDate 至 $today",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onExportCsvClick,
                enabled = !isExportingCsv,
            ) {
                if (isExportingCsv) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("导出中…")
                } else {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("导出 CSV")
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = windowDays == 7,
                onClick = { onSelectWindowDays(7) },
                label = { Text("7天") },
            )
            FilterChip(
                selected = windowDays == 30,
                onClick = { onSelectWindowDays(30) },
                label = { Text("30天") },
            )
            Text(
                text = "导出仅含脱敏字段，不含昵称与聊天内容",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun HistoryKpiGrid(
    summary: HistoryStatsSummary,
    onNavigateToTodayReview: () -> Unit,
) {
    val semantics = FlameThemeTokens.semanticColors
    val rateText = summary.successRate?.let { formatSuccessRatePercent(it) } ?: "--"
    val rateSub = if (summary.totalJobs > 0) {
        "共 ${summary.totalJobs} 次已结算"
    } else {
        "近 ${summary.windowDays} 日暂无数据"
    }
    val hasUnknown = summary.unknownJobs > 0
    val issueAccent = when {
        hasUnknown -> semantics.warning
        summary.failedJobs > 0 -> semantics.danger
        else -> semantics.healthy
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val isWide = maxWidth >= 600.dp
        if (isWide) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                KpiCard(
                    title = "成功率",
                    value = rateText,
                    subtitle = rateSub,
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                KpiCard(
                    title = "当前 / 最长连胜",
                    value = "${summary.currentSuccessStreak} / ${summary.bestSuccessStreak} 天",
                    subtitle = "成功日：有成功且零失败未知",
                    accentColor = semantics.healthy,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                KpiCard(
                    title = "成功 / 总任务",
                    value = "${summary.succeededJobs} / ${summary.totalJobs}",
                    subtitle = "已取消 ${summary.cancelledJobs} 次",
                    accentColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                UnknownAndFailureKpiCard(
                    unknownJobs = summary.unknownJobs,
                    failedJobs = summary.failedJobs,
                    accentColor = issueAccent,
                    onNavigateToTodayReview = onNavigateToTodayReview,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    KpiCard(
                        title = "成功率",
                        value = rateText,
                        subtitle = rateSub,
                        accentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    KpiCard(
                        title = "当前 / 最长连胜",
                        value = "${summary.currentSuccessStreak} / ${summary.bestSuccessStreak} 天",
                        subtitle = "零失败未知日计入连胜",
                        accentColor = semantics.healthy,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    KpiCard(
                        title = "成功 / 总任务",
                        value = "${summary.succeededJobs} / ${summary.totalJobs}",
                        subtitle = "已取消 ${summary.cancelledJobs} 次",
                        accentColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    UnknownAndFailureKpiCard(
                        unknownJobs = summary.unknownJobs,
                        failedJobs = summary.failedJobs,
                        accentColor = issueAccent,
                        onNavigateToTodayReview = onNavigateToTodayReview,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun KpiCard(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun UnknownAndFailureKpiCard(
    unknownJobs: Int,
    failedJobs: Int,
    accentColor: Color,
    onNavigateToTodayReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantics = FlameThemeTokens.semanticColors
    val hasUnknown = unknownJobs > 0
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (hasUnknown) {
                semantics.warningContainer.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        border = if (hasUnknown) {
            BorderStroke(1.dp, semantics.warning.copy(alpha = 0.5f))
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "待核对 / 失败",
                style = MaterialTheme.typography.labelMedium,
                color = if (hasUnknown) semantics.onWarningContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$unknownJobs / $failedJobs",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (hasUnknown) {
                Text(
                    text = "前往「今日」核对 →",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = semantics.onWarningContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onNavigateToTodayReview)
                        .padding(vertical = 2.dp),
                )
            } else {
                Text(
                    text = if (failedJobs > 0) "失败记录可点击查看耗时" else "无异常与待核对",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
    val semantics = FlameThemeTokens.semanticColors
    val maxDayTotal = summary.daily.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "每日执行趋势",
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ChartLegendDot(color = semantics.healthy, label = "成功")
                    ChartLegendDot(color = semantics.sparkGold, label = "待核对")
                    ChartLegendDot(color = semantics.danger, label = "失败")
                    if (summary.cancelledJobs > 0) {
                        ChartLegendDot(color = semantics.neutral, label = "取消")
                    }
                    ChartLegendDot(color = MaterialTheme.colorScheme.outlineVariant, label = "无任务")
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
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
                                    maxBarWidth = 36.dp,
                                    horizontalPadding = 2.dp,
                                    onClick = { onToggleDate(bucket.date) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    maxWidth >= 560.dp -> {
                        val tickIndices = remember(summary.daily.size) {
                            computeAxisTickIndices(summary.daily.size, 6)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
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
                                        color = if (isTickSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
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
                                val showDateTick = isSelected || index == 0 || isLast || index % 5 == 0
                                DayStackedBarColumn(
                                    bucket = bucket,
                                    maxDayTotal = maxDayTotal,
                                    isSelected = isSelected,
                                    showLabel = showDateTick,
                                    reserveLabelSpace = true,
                                    maxBarWidth = 16.dp,
                                    horizontalPadding = 2.dp,
                                    onClick = { onToggleDate(bucket.date) },
                                    modifier = Modifier.width(40.dp),
                                )
                            }
                        }
                    }
                }
            }

            val selectedBucket = selectedDate?.let { date ->
                summary.daily.firstOrNull { it.date == date }
            }
            if (selectedBucket != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${selectedBucket.date}：成功 ${selectedBucket.succeeded} · 待核对 ${selectedBucket.unknown} · 失败 ${selectedBucket.failed} · 取消 ${selectedBucket.cancelled}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = onClearDateFilter,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("清除日期", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            } else {
                Text(
                    text = "点击任意日期柱条可筛选当日明细，再次点击取消筛选",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    val semantics = FlameThemeTokens.semanticColors
    val maxBarHeightDp = 92f
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        Color.Transparent
    }
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = horizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(94.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (bucket.total == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .widthIn(max = maxBarWidth)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                )
            } else {
                val barHeight = (maxBarHeightDp * (bucket.total.toFloat() / maxDayTotal.toFloat()))
                    .coerceIn(12f, maxBarHeightDp)
                    .dp
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.68f)
                        .widthIn(max = maxBarWidth)
                        .height(barHeight)
                        .clip(RoundedCornerShape(4.dp)),
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
                                .background(semantics.sparkGold),
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
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
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

@Composable
private fun HistoryDateGroupHeader(
    businessDate: String,
    items: List<HistoryJobItem>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val succeededCount = items.count { it.job.status == JobStatus.SUCCEEDED }
    val unknownCount = items.count { it.job.status == JobStatus.UNKNOWN_REQUIRES_REVIEW }
    val failedCount = items.count {
        it.job.status == JobStatus.FAILED_RETRYABLE || it.job.status == JobStatus.FAILED_TERMINAL
    }
    val summaryParts = buildList {
        if (succeededCount > 0) add("成功 $succeededCount")
        if (unknownCount > 0) add("待核对 $unknownCount")
        if (failedCount > 0) add("失败 $failedCount")
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onToggleExpand),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = businessDate,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${items.size} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (summaryParts.isNotEmpty()) {
                    Text(
                        text = summaryParts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (isExpanded) "收起该日明细" else "展开该日明细",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChartLegendDot(
    color: Color,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryJobCard(
    item: HistoryJobItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val job = item.job
    val badge = jobStatusBadge(job.status)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = badge.icon,
                    contentDescription = null,
                    tint = badge.fg,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = item.targetName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.targetDeleted) {
                    HistoryPill(
                        text = "已删除",
                        contentColor = FlameThemeTokens.semanticColors.onNeutralContainer,
                        containerColor = FlameThemeTokens.semanticColors.neutralContainer,
                    )
                }
                HistoryPill(
                    text = if (item.isDailySlot) "每日" else "测试",
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                )
                HistoryPill(
                    text = badge.label,
                    contentColor = badge.fg,
                    containerColor = badge.bg,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${job.businessDate} · 尝试 ${job.attemptCount} 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val timeText = job.confirmedAt?.let { "确认于 ${formatEpochTimestamp(it)}" }
                    ?: "更新于 ${formatEpochTimestamp(job.updatedAt)}"
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val detailLine = buildString {
                job.failureClass?.let { fc ->
                    append("分类：")
                    append(failureClassLabel(fc))
                }
                val summary = job.summaryDetail?.takeIf { it.isNotBlank() }
                if (summary != null) {
                    if (isNotEmpty()) append(" · ")
                    append(summary)
                }
            }
            if (detailLine.isNotEmpty()) {
                Text(
                    text = detailLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyHistoryCard(
    windowDays: Int,
    hasAnyJobsInWindow: Boolean,
    hasActiveFilter: Boolean,
    onClearAllFilters: () -> Unit,
) {
    OutlinedCard(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (!hasAnyJobsInWindow) "近 $windowDays 天暂无执行记录" else "当前筛选条件下无记录",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = if (!hasAnyJobsInWindow) {
                    "当每日自动续火或单发测试产生执行记录后，可在此回溯每日趋势与尝试耗时。"
                } else {
                    "可尝试切换状态筛选芯片或清除日期过滤。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasActiveFilter) {
                TextButton(onClick = onClearAllFilters) {
                    Text("重置全部筛选")
                }
            }
        }
    }
}

@Composable
private fun HistoryJobDetailDialog(
    detail: HistoryJobDetailUiState,
    onDismiss: () -> Unit,
    onNavigateToTodayReview: () -> Unit,
) {
    val item = detail.item
    val job = item.job
    val badge = jobStatusBadge(job.status)
    val semantics = FlameThemeTokens.semanticColors

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = badge.icon,
                contentDescription = null,
                tint = badge.fg,
            )
        },
        title = {
            Text(
                text = if (item.targetDeleted) {
                    "${item.targetName}（已删除）"
                } else {
                    item.targetName
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "业务日：${job.businessDate} · ${if (item.isDailySlot) "每日自动/批量槽位" else "单发测试槽位"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "状态：${badge.label} · 总尝试 ${job.attemptCount} 次",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        job.failureClass?.let { fc ->
                            Text(
                                text = "失败分类：${failureClassLabel(fc)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        job.scheduledAt?.let { scheduledAt ->
                            Text(
                                text = "计划时刻：${formatEpochTimestampWithSec(scheduledAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        job.confirmedAt?.let { confirmedAt ->
                            Text(
                                text = "确认完成：${formatEpochTimestampWithSec(confirmedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        job.summaryDetail?.takeIf { it.isNotBlank() }?.let { summary ->
                            Text(
                                text = "脱敏摘要：$summary",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "发送尝试时间线",
                    style = MaterialTheme.typography.titleSmall,
                )

                when {
                    detail.isLoadingAttempts -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "正在加载尝试明细…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    detail.attemptsLoadError != null -> {
                        Text(
                            text = detail.attemptsLoadError,
                            style = MaterialTheme.typography.bodySmall,
                            color = semantics.danger,
                        )
                    }

                    detail.attempts.isEmpty() -> {
                        Text(
                            text = "暂无底层发送尝试记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        detail.attempts.forEach { attempt ->
                            HistoryAttemptTimelineRow(attempt = attempt)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (job.status == JobStatus.UNKNOWN_REQUIRES_REVIEW) {
                Button(
                    onClick = onNavigateToTodayReview,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = semantics.danger,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("前往「今日」人工核对")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun HistoryAttemptTimelineRow(attempt: SendAttemptEntity) {
    val semantics = FlameThemeTokens.semanticColors
    val (statusLabel, statusColor) = when (attempt.status) {
        AttemptStatus.SUCCEEDED -> "成功" to semantics.healthy
        AttemptStatus.FAILED -> "失败" to semantics.danger
        AttemptStatus.RUNNING -> "进行中" to MaterialTheme.colorScheme.primary
    }
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
        null -> "--"
        else -> attempt.inputMethodUsed
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "第 ${attempt.attemptNo} 次 · $stageLabel",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                )
            }

            val metaParts = buildList {
                add("方式：$methodLabel")
                attempt.failureClass?.let { add("分类：${failureClassLabel(it)}") }
                attempt.errorCode?.takeIf { it.isNotBlank() }?.let { add("错误码：$it") }
            }
            Text(
                text = metaParts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
            Text(
                text = "时间：$startText - $endText（$durationSuffix）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryPill(
    text: String,
    contentColor: Color,
    containerColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private data class JobBadgeStyle(
    val label: String,
    val fg: Color,
    val bg: Color,
    val icon: ImageVector,
)

@Composable
private fun jobStatusBadge(status: JobStatus): JobBadgeStyle {
    val semantics = FlameThemeTokens.semanticColors
    return when (status) {
        JobStatus.SUCCEEDED -> JobBadgeStyle(
            label = "已成功",
            fg = semantics.onHealthyContainer,
            bg = semantics.healthyContainer,
            icon = Icons.Filled.CheckCircle,
        )
        JobStatus.UNKNOWN_REQUIRES_REVIEW -> JobBadgeStyle(
            label = "待核对",
            fg = semantics.onWarningContainer,
            bg = semantics.warningContainer,
            icon = Icons.AutoMirrored.Filled.HelpOutline,
        )
        JobStatus.FAILED_RETRYABLE -> JobBadgeStyle(
            label = "待重试",
            fg = semantics.onWarningContainer,
            bg = semantics.warningContainer,
            icon = Icons.Filled.Refresh,
        )
        JobStatus.FAILED_TERMINAL -> JobBadgeStyle(
            label = "失败",
            fg = semantics.onDangerContainer,
            bg = semantics.dangerContainer,
            icon = Icons.Filled.ErrorOutline,
        )
        JobStatus.CANCELLED -> JobBadgeStyle(
            label = "已取消",
            fg = semantics.onNeutralContainer,
            bg = semantics.neutralContainer,
            icon = Icons.Filled.Cancel,
        )
        JobStatus.SCHEDULED -> JobBadgeStyle(
            label = "已计划",
            fg = semantics.onNeutralContainer,
            bg = semantics.neutralContainer,
            icon = Icons.Filled.Schedule,
        )
        JobStatus.LEASED,
        JobStatus.OPENING_CHAT,
        JobStatus.SENDING,
        JobStatus.CONFIRMING,
        -> JobBadgeStyle(
            label = "执行中",
            fg = MaterialTheme.colorScheme.onPrimaryContainer,
            bg = MaterialTheme.colorScheme.primaryContainer,
            icon = Icons.Filled.WarningAmber,
        )
    }
}

private fun formatSuccessRatePercent(rate: Double): String {
    val pct = (rate * 100.0).coerceIn(0.0, 100.0)
    return if (pct == 0.0 || pct == 100.0) {
        String.format(Locale.US, "%.0f%%", pct)
    } else {
        String.format(Locale.US, "%.1f%%", pct)
    }
}

private fun formatEpochTimestamp(epochMs: Long): String {
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
    return DATE_TIME_FORMATTER.format(zdt)
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
private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val DATE_TIME_SEC_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
private val TIME_SEC_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
