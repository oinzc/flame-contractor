package io.github.oinzc.flamecontractor.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.domain.HistoryCsvExporter
import io.github.oinzc.flamecontractor.domain.HistoryStatsCalculator
import io.github.oinzc.flamecontractor.domain.HistoryStatsSummary
import io.github.oinzc.flamecontractor.domain.SendGuard
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 历史与统计页仓库接口（支持纯 JVM 假仓库单测）。
 */
interface HistoryRepository {
    fun observeJobsSince(accountId: String, sinceDate: String): Flow<List<SendJobEntity>>
    suspend fun listAllTargetsIncludingDeleted(accountId: String = DEFAULT_ACCOUNT_ID): List<ContractTargetEntity>
    suspend fun listAllJobs(accountId: String = DEFAULT_ACCOUNT_ID): List<SendJobEntity>
    suspend fun listAttemptsForJob(jobId: String): List<SendAttemptEntity>
}

class RoomHistoryRepository(
    private val database: FlameDatabase,
) : HistoryRepository {

    override fun observeJobsSince(accountId: String, sinceDate: String): Flow<List<SendJobEntity>> =
        database.sendJobDao().observeJobsSince(accountId, sinceDate)

    override suspend fun listAllTargetsIncludingDeleted(accountId: String): List<ContractTargetEntity> =
        database.targetDao().listAllTargetsIncludingDeleted(accountId)

    override suspend fun listAllJobs(accountId: String): List<SendJobEntity> =
        database.sendJobDao().listAllJobs(accountId)

    override suspend fun listAttemptsForJob(jobId: String): List<SendAttemptEntity> =
        database.sendJobDao().listAttemptsForJob(jobId)
}

enum class HistoryStatusFilter(val label: String) {
    ALL("全部"),
    SUCCEEDED("成功"),
    FAILED("失败"),
    UNKNOWN("待核对"),
    CANCELLED("已取消"),
}

data class HistoryJobItem(
    val job: SendJobEntity,
    val targetName: String,
    val targetDeleted: Boolean,
    val isDailySlot: Boolean,
)

data class HistoryJobDetailUiState(
    val item: HistoryJobItem,
    val attempts: List<SendAttemptEntity> = emptyList(),
    val isLoadingAttempts: Boolean = false,
    val attemptsLoadError: String? = null,
)

data class HistoryUiState(
    val isLoading: Boolean = true,
    val windowDays: Int = HistoryViewModel.DEFAULT_WINDOW_DAYS,
    val today: LocalDate = LocalDate.now(),
    val selectedDate: LocalDate? = null,
    val statusFilter: HistoryStatusFilter = HistoryStatusFilter.ALL,
    val summary: HistoryStatsSummary = HistoryStatsCalculator.summarize(
        jobs = emptyList(),
        today = today,
        windowDays = HistoryViewModel.DEFAULT_WINDOW_DAYS,
    ),
    val filteredJobs: List<HistoryJobItem> = emptyList(),
    val totalJobsInWindow: Int = 0,
    val filterCounts: Map<HistoryStatusFilter, Int> = HistoryStatusFilter.entries.associateWith { 0 },
    val selectedJobDetail: HistoryJobDetailUiState? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val repository: HistoryRepository,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    customScope: CoroutineScope? = null,
) : ViewModel() {

    private val vmScope: CoroutineScope = customScope ?: viewModelScope

    private val todayFlow = MutableStateFlow(LocalDate.now(clock))
    private val windowDaysFlow = MutableStateFlow(DEFAULT_WINDOW_DAYS)
    private val selectedDateFlow = MutableStateFlow<LocalDate?>(null)
    private val statusFilterFlow = MutableStateFlow(HistoryStatusFilter.ALL)
    private val refreshTickFlow = MutableStateFlow(0L)
    private val detailStateFlow = MutableStateFlow<HistoryJobDetailUiState?>(null)

    @Volatile
    private var latestItemsById: Map<String, HistoryJobItem> = emptyMap()

    private val jobsAndTargetsFlow: Flow<JobsAndTargets> = combine(
        todayFlow,
        windowDaysFlow,
        refreshTickFlow,
    ) { today, windowDays, _ ->
        today to windowDays
    }.flatMapLatest { (today, windowDays) ->
        val sinceDate = today.minusDays((windowDays - 1).toLong()).toString()
        repository.observeJobsSince(accountId, sinceDate).map { jobs ->
            val targets = withContext(ioDispatcher) {
                repository.listAllTargetsIncludingDeleted(accountId)
            }
            JobsAndTargets(
                today = today,
                windowDays = windowDays,
                jobs = jobs,
                targets = targets,
            )
        }
    }

    val uiState: StateFlow<HistoryUiState> = combine(
        jobsAndTargetsFlow,
        selectedDateFlow,
        statusFilterFlow,
        detailStateFlow,
    ) { data, selectedDate, statusFilter, detailState ->
        buildUiState(
            data = data,
            selectedDate = selectedDate,
            statusFilter = statusFilter,
            detailState = detailState,
        )
    }.stateIn(
        scope = vmScope,
        started = SharingStarted.Eagerly,
        initialValue = HistoryUiState(
            isLoading = true,
            windowDays = DEFAULT_WINDOW_DAYS,
            today = todayFlow.value,
        ),
    )

    fun setWindowDays(days: Int) {
        val normalized = days.coerceIn(1, 365)
        if (windowDaysFlow.value == normalized) return
        windowDaysFlow.value = normalized
        val today = todayFlow.value
        val minDate = today.minusDays((normalized - 1).toLong())
        selectedDateFlow.update { current ->
            if (current != null && (current.isBefore(minDate) || current.isAfter(today))) null else current
        }
    }

    fun setStatusFilter(filter: HistoryStatusFilter) {
        statusFilterFlow.value = filter
    }

    fun toggleSelectedDate(date: LocalDate) {
        selectedDateFlow.update { current ->
            if (current == date) null else date
        }
    }

    fun clearDateFilter() {
        selectedDateFlow.value = null
    }

    fun clearAllFilters() {
        selectedDateFlow.value = null
        statusFilterFlow.value = HistoryStatusFilter.ALL
    }

    fun refresh() {
        val currentToday = LocalDate.now(clock)
        todayFlow.value = currentToday
        val windowDays = windowDaysFlow.value
        val minDate = currentToday.minusDays((windowDays - 1).toLong())
        selectedDateFlow.update { current ->
            if (current != null && (current.isBefore(minDate) || current.isAfter(currentToday))) null else current
        }
        refreshTickFlow.value += 1L
    }

    fun selectJob(jobId: String) {
        val item = latestItemsById[jobId]
            ?: uiState.value.filteredJobs.firstOrNull { it.job.id == jobId }
            ?: return
        detailStateFlow.value = HistoryJobDetailUiState(
            item = item,
            attempts = emptyList(),
            isLoadingAttempts = true,
            attemptsLoadError = null,
        )
        vmScope.launch {
            try {
                val attempts = withContext(ioDispatcher) {
                    repository.listAttemptsForJob(jobId)
                }
                detailStateFlow.update { current ->
                    if (current?.item?.job?.id == jobId) {
                        current.copy(
                            attempts = attempts.sortedBy { it.attemptNo },
                            isLoadingAttempts = false,
                            attemptsLoadError = null,
                        )
                    } else {
                        current
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                detailStateFlow.update { current ->
                    if (current?.item?.job?.id == jobId) {
                        current.copy(
                            isLoadingAttempts = false,
                            attemptsLoadError = "读取发送尝试明细失败，请稍后重试",
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun dismissJobDetail() {
        detailStateFlow.value = null
    }

    suspend fun buildExportCsv(): String = withContext(ioDispatcher) {
        val allJobs = repository.listAllJobs(accountId)
        HistoryCsvExporter.encode(allJobs)
    }

    fun defaultExportFileName(): String = HistoryCsvExporter.defaultFileName(LocalDate.now(clock))

    private fun buildUiState(
        data: JobsAndTargets,
        selectedDate: LocalDate?,
        statusFilter: HistoryStatusFilter,
        detailState: HistoryJobDetailUiState?,
    ): HistoryUiState {
        val startDate = data.today.minusDays((data.windowDays - 1).toLong())
        val windowJobs = data.jobs
            .filter { job ->
                val date = runCatching { LocalDate.parse(job.businessDate) }.getOrNull()
                date != null && !date.isBefore(startDate) && !date.isAfter(data.today)
            }
            .sortedWith(
                compareByDescending<SendJobEntity> { it.businessDate }
                    .thenByDescending { it.createdAt }
                    .thenByDescending { it.id },
            )

        val summary = HistoryStatsCalculator.summarize(
            jobs = windowJobs,
            today = data.today,
            windowDays = data.windowDays,
        )

        val targetsById = data.targets.associateBy { it.id }
        val windowItems = windowJobs.map { job ->
            val target = targetsById[job.targetId]
            HistoryJobItem(
                job = job,
                targetName = target?.nicknameSnapshot ?: "未知好友",
                targetDeleted = target == null || target.deletedAt != null,
                isDailySlot = job.messageSlotId == SendGuard.DAILY_SLOT_ID,
            )
        }
        val itemsById = windowItems.associateBy { it.job.id }
        latestItemsById = itemsById

        val dateFilteredItems = if (selectedDate != null) {
            val targetDateStr = selectedDate.toString()
            windowItems.filter { it.job.businessDate == targetDateStr }
        } else {
            windowItems
        }

        val filterCounts = mapOf(
            HistoryStatusFilter.ALL to dateFilteredItems.size,
            HistoryStatusFilter.SUCCEEDED to dateFilteredItems.count { it.job.status == JobStatus.SUCCEEDED },
            HistoryStatusFilter.FAILED to dateFilteredItems.count {
                it.job.status == JobStatus.FAILED_RETRYABLE || it.job.status == JobStatus.FAILED_TERMINAL
            },
            HistoryStatusFilter.UNKNOWN to dateFilteredItems.count {
                it.job.status == JobStatus.UNKNOWN_REQUIRES_REVIEW
            },
            HistoryStatusFilter.CANCELLED to dateFilteredItems.count { it.job.status == JobStatus.CANCELLED },
        )

        val filteredJobs = dateFilteredItems.filter { item ->
            when (statusFilter) {
                HistoryStatusFilter.ALL -> true
                HistoryStatusFilter.SUCCEEDED -> item.job.status == JobStatus.SUCCEEDED
                HistoryStatusFilter.FAILED -> {
                    item.job.status == JobStatus.FAILED_RETRYABLE || item.job.status == JobStatus.FAILED_TERMINAL
                }
                HistoryStatusFilter.UNKNOWN -> item.job.status == JobStatus.UNKNOWN_REQUIRES_REVIEW
                HistoryStatusFilter.CANCELLED -> item.job.status == JobStatus.CANCELLED
            }
        }

        val syncedDetail = detailState?.let { current ->
            val refreshedItem = itemsById[current.item.job.id]
            if (refreshedItem != null) current.copy(item = refreshedItem) else current
        }

        return HistoryUiState(
            isLoading = false,
            windowDays = data.windowDays,
            today = data.today,
            selectedDate = selectedDate,
            statusFilter = statusFilter,
            summary = summary,
            filteredJobs = filteredJobs,
            totalJobsInWindow = windowItems.size,
            filterCounts = filterCounts,
            selectedJobDetail = syncedDetail,
        )
    }

    private data class JobsAndTargets(
        val today: LocalDate,
        val windowDays: Int,
        val jobs: List<SendJobEntity>,
        val targets: List<ContractTargetEntity>,
    )

    companion object {
        const val DEFAULT_WINDOW_DAYS = 30

        fun factory(
            database: FlameDatabase,
            accountId: String = DEFAULT_ACCOUNT_ID,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HistoryViewModel(
                        repository = RoomHistoryRepository(database),
                        accountId = accountId,
                    ) as T
                }
            }
    }
}
