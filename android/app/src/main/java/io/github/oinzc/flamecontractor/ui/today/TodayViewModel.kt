package io.github.oinzc.flamecontractor.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import io.github.oinzc.flamecontractor.app.AppContainer
import io.github.oinzc.flamecontractor.core.notify.UrgentCategory
import io.github.oinzc.flamecontractor.core.storage.TemplateImageStorage
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.domain.AccountHealthReducer
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.execution.ExecutionLiveState
import io.github.oinzc.flamecontractor.execution.ExecutionRuntimeBus
import io.github.oinzc.flamecontractor.execution.LoginMode
import io.github.oinzc.flamecontractor.scheduler.DailySchedulePlanner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface TodayRepository {
    fun observeAccount(accountId: String): Flow<AccountEntity?>
    fun observeAllAccounts(): Flow<List<AccountEntity>> = flowOf(emptyList())
    fun observeActiveTargets(accountId: String): Flow<List<ContractTargetEntity>>
    fun observeJobsForDate(accountId: String, businessDate: String): Flow<List<SendJobEntity>>
    fun observeJobsByStatus(accountId: String, status: JobStatus): Flow<List<SendJobEntity>>
    fun observeRecentJobs(accountId: String, limit: Int): Flow<List<SendJobEntity>>
    fun observeTemplates(accountId: String): Flow<List<MessageTemplateEntity>>
    suspend fun listAttemptsForJob(jobId: String): List<SendAttemptEntity>
    suspend fun resolveUnknownJobAsSucceeded(jobId: String, nowEpochMs: Long): Boolean
    suspend fun resolveUnknownJobAsCancelled(jobId: String, nowEpochMs: Long): Boolean
    suspend fun clearAccountCooldown(accountId: String, nowEpochMs: Long): AccountEntity?
}

interface TodayScheduleGateway {
    suspend fun rescheduleAll(now: Instant = Instant.now()): Instant?
}

interface TodayExecutionLauncher {
    fun enqueueManualBatch(accountId: String): Boolean
}

class RoomTodayRepository(
    private val database: FlameDatabase,
) : TodayRepository {

    override fun observeAccount(accountId: String): Flow<AccountEntity?> =
        database.accountDao().observeAccount(accountId)

    override fun observeAllAccounts(): Flow<List<AccountEntity>> =
        database.accountDao().observeAll()

    override fun observeActiveTargets(accountId: String): Flow<List<ContractTargetEntity>> =
        database.targetDao().observeActiveTargets(accountId)

    override fun observeJobsForDate(accountId: String, businessDate: String): Flow<List<SendJobEntity>> =
        database.sendJobDao().observeJobsForDate(accountId, businessDate)

    override fun observeJobsByStatus(accountId: String, status: JobStatus): Flow<List<SendJobEntity>> =
        database.sendJobDao().observeJobsByStatus(accountId, status)

    override fun observeRecentJobs(accountId: String, limit: Int): Flow<List<SendJobEntity>> =
        database.sendJobDao().observeRecentJobs(accountId, limit)

    override fun observeTemplates(accountId: String): Flow<List<MessageTemplateEntity>> =
        database.templateDao().observeTemplates(accountId)

    override suspend fun listAttemptsForJob(jobId: String): List<SendAttemptEntity> =
        database.sendJobDao().listAttemptsForJob(jobId)

    override suspend fun resolveUnknownJobAsSucceeded(jobId: String, nowEpochMs: Long): Boolean =
        database.withTransaction {
            val sendJobDao = database.sendJobDao()
            val job = sendJobDao.getJobById(jobId) ?: return@withTransaction false
            if (job.status != JobStatus.UNKNOWN_REQUIRES_REVIEW) return@withTransaction false

            sendJobDao.updateJobOutcome(
                id = job.id,
                status = JobStatus.SUCCEEDED,
                failureClass = null,
                summaryDetail = "人工核对确认已在抖音发出",
                confirmedAt = nowEpochMs,
                updatedAt = nowEpochMs,
            )
            sendJobDao.listAttemptsForJob(job.id)
                .filter { it.status == AttemptStatus.RUNNING }
                .forEach { runningAttempt ->
                    sendJobDao.updateAttempt(
                        runningAttempt.copy(
                            status = AttemptStatus.SUCCEEDED,
                            failureClass = null,
                            finishedAt = nowEpochMs,
                        ),
                    )
                }

            database.targetDao().recordSendConfirmed(job.targetId, nowEpochMs)

            val accountDao = database.accountDao()
            val account = accountDao.getAccount(job.accountId)
            if (account != null) {
                val updated = AccountHealthReducer.onSingleSendSucceeded(account, nowEpochMs)
                accountDao.update(updated)
            }
            true
        }

    override suspend fun resolveUnknownJobAsCancelled(jobId: String, nowEpochMs: Long): Boolean =
        database.withTransaction {
            val sendJobDao = database.sendJobDao()
            val job = sendJobDao.getJobById(jobId) ?: return@withTransaction false
            if (job.status != JobStatus.UNKNOWN_REQUIRES_REVIEW) return@withTransaction false

            sendJobDao.updateJobOutcome(
                id = job.id,
                status = JobStatus.CANCELLED,
                failureClass = null,
                summaryDetail = "人工核对确认未发出，今日已取消不再自动发送",
                confirmedAt = null,
                updatedAt = nowEpochMs,
            )
            sendJobDao.listAttemptsForJob(job.id)
                .filter { it.status == AttemptStatus.RUNNING }
                .forEach { runningAttempt ->
                    sendJobDao.updateAttempt(
                        runningAttempt.copy(
                            status = AttemptStatus.FAILED,
                            failureClass = FailureClass.UNKNOWN_OUTCOME,
                            finishedAt = nowEpochMs,
                        ),
                    )
                }
            true
        }

    override suspend fun clearAccountCooldown(accountId: String, nowEpochMs: Long): AccountEntity? =
        database.withTransaction {
            val accountDao = database.accountDao()
            val current = accountDao.getAccount(accountId) ?: return@withTransaction null
            val recovered = AccountHealthReducer.recoverFromCooldown(current, nowEpochMs)
            if (recovered != current) {
                accountDao.update(recovered)
            }
            recovered
        }
}

data class UnknownJobReviewItem(
    val job: SendJobEntity,
    val targetName: String,
    val templateLabel: String,
    val isDailySlot: Boolean,
    val isImageMissing: Boolean = false,
)

data class RecentJobItem(
    val job: SendJobEntity,
    val targetName: String,
    val templateLabel: String,
    val isDailySlot: Boolean,
    val isImageMissing: Boolean = false,
    val attempts: List<SendAttemptEntity> = emptyList(),
    val attemptsLoaded: Boolean = false,
    val expanded: Boolean = false,
)

data class SanitizedFailureStat(
    val failureClass: FailureClass,
    val count: Int,
)

enum class ManualBatchBlockReason {
    ACCOUNT_NOT_READY,
    ACCOUNT_PAUSED,
    UNKNOWN_OR_INFLIGHT,
    NO_ENABLED_TARGETS,
    ALL_DONE_OR_SKIPPED_TODAY,
    SERVICE_START_FAILED,
}

data class ManualBatchFeedback(
    val reason: ManualBatchBlockReason,
    val title: String,
    val message: String,
    val canJumpToUnknownReview: Boolean = false,
    val canJumpToLogin: Boolean = false,
    val canJumpToSettings: Boolean = false,
    val canRecoverCooldown: Boolean = false,
    val canJumpToTargets: Boolean = false,
)

sealed class TodayUiEffect {
    data class OpenLogin(val mode: LoginMode, val accountId: String) : TodayUiEffect()
    data class ShowSnackbar(val message: String) : TodayUiEffect()
}

data class TodayUiState(
    val isLoading: Boolean = true,
    val businessDate: String = "",
    val account: AccountEntity? = null,
    val enabledTargetCount: Int = 0,
    val pendingTargetCount: Int = 0,
    val succeededTodayCount: Int = 0,
    val issueTodayCount: Int = 0,
    val nextAutoTriggerAtEpochMs: Long? = null,
    val authRecheckAtEpochMs: Long? = null,
    val unknownReviewJobs: List<UnknownJobReviewItem> = emptyList(),
    val recentJobs: List<RecentJobItem> = emptyList(),
    val cooldownFailureStats: List<SanitizedFailureStat> = emptyList(),
    val executionState: ExecutionLiveState = ExecutionLiveState(),
    val otherRunningAccountName: String? = null,
    val otherRunningTaskLabel: String? = null,
    val missingImageTemplateCount: Int = 0,
    val missingImageEnabledTargetCount: Int = 0,
    val showReloginChoiceDialog: Boolean = false,
    val activeUnknownReviewJob: UnknownJobReviewItem? = null,
    val showCooldownDetailDialog: Boolean = false,
    val showDomBrokenDialog: Boolean = false,
    val urgentAuthPromptCategory: UrgentCategory? = null,
    val manualBatchFeedback: ManualBatchFeedback? = null,
    val hasBlockingTodayJob: Boolean = false,
    val isResolvingAction: Boolean = false,
    val maxLocalStreakDays: Int = 0,
    val pausedTargetCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val repository: TodayRepository,
    private val scheduleGateway: TodayScheduleGateway,
    private val executionLauncher: TodayExecutionLauncher,
    executionStateFlow: StateFlow<ExecutionLiveState> = ExecutionRuntimeBus.state,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
    private val imageStore: TemplateImageStorage? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    customScope: CoroutineScope? = null,
) : ViewModel() {

    private val vmScope: CoroutineScope = customScope ?: viewModelScope

    private val businessDateFlow = MutableStateFlow(currentBusinessDate())
    private val controlFlow = MutableStateFlow(ControlState())
    private val refreshTickFlow = MutableStateFlow(0L)
    private val resolvingJobIds = HashSet<String>()
    private var lastHandledUrgentNonce: Long = 0L

    private val _effects = MutableSharedFlow<TodayUiEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<TodayUiEffect> = _effects.asSharedFlow()

    private val dataFlow: Flow<DbSnapshot> = combine(
        repository.observeAccount(accountId),
        repository.observeActiveTargets(accountId),
        businessDateFlow.flatMapLatest { date -> repository.observeJobsForDate(accountId, date) },
        repository.observeJobsByStatus(accountId, JobStatus.UNKNOWN_REQUIRES_REVIEW),
        repository.observeRecentJobs(accountId, RECENT_JOBS_LIMIT),
    ) { account, targets, todayJobs, unknownJobs, recentJobs ->
        DbSnapshot(
            account = account,
            targets = targets,
            todayJobs = todayJobs,
            unknownJobs = unknownJobs,
            recentJobs = recentJobs,
        )
    }

    private val templatesWithMissingFlow: Flow<Pair<List<MessageTemplateEntity>, Set<String>>> = combine(
        repository.observeTemplates(accountId),
        refreshTickFlow,
    ) { templates, _ ->
        val missingIds = withContext(ioDispatcher) {
            templates.filter { tpl ->
                tpl.type == TemplateType.IMAGE && if (imageStore != null) {
                    !imageStore.isImageAvailable(tpl.imagePath)
                } else {
                    tpl.imagePath.isNullOrBlank()
                }
            }.mapTo(HashSet()) { it.id }
        }
        templates to missingIds
    }

    private val metaFlow: Flow<MetaSnapshot> = combine(
        templatesWithMissingFlow,
        repository.observeAllAccounts(),
        executionStateFlow,
        businessDateFlow,
        controlFlow,
    ) { templatePair, allAccounts, execState, date, controls ->
        MetaSnapshot(
            templates = templatePair.first,
            missingImageTemplateIds = templatePair.second,
            allAccounts = allAccounts,
            executionState = execState,
            businessDate = date,
            controls = controls,
        )
    }

    val uiState: StateFlow<TodayUiState> = combine(dataFlow, metaFlow) { db, meta ->
        buildUiState(db, meta)
    }.stateIn(
        scope = vmScope,
        started = SharingStarted.Eagerly,
        initialValue = TodayUiState(
            isLoading = true,
            businessDate = businessDateFlow.value,
        ),
    )

    init {
        vmScope.launch {
            combine(
                dataFlow,
                controlFlow.map { it.pendingUrgentCategory }.distinctUntilChanged(),
            ) { db, pending ->
                db to pending
            }.collect { (db, pending) ->
                if (pending == null) return@collect
                when (pending) {
                    UrgentCategory.UNKNOWN_REQUIRES_REVIEW -> {
                        val firstUnknown = db.unknownJobs.firstOrNull()
                        if (firstUnknown != null) {
                            controlFlow.update {
                                it.copy(
                                    pendingUrgentCategory = null,
                                    activeUnknownReviewJobId = firstUnknown.id,
                                )
                            }
                        }
                    }

                    UrgentCategory.COOLDOWN -> {
                        if (db.account?.status == AccountStatus.COOLDOWN) {
                            controlFlow.update {
                                it.copy(
                                    pendingUrgentCategory = null,
                                    showCooldownDetailDialog = true,
                                )
                            }
                        }
                    }

                    UrgentCategory.DOM_BROKEN -> {
                        if (db.account?.status == AccountStatus.DOM_BROKEN) {
                            controlFlow.update {
                                it.copy(
                                    pendingUrgentCategory = null,
                                    showDomBrokenDialog = true,
                                )
                            }
                        }
                    }

                    UrgentCategory.AUTH_EXPIRED,
                    UrgentCategory.VERIFICATION_REQUIRED,
                    -> {
                        val status = db.account?.status
                        if (status == AccountStatus.AUTH_EXPIRED ||
                            status == AccountStatus.VERIFICATION_REQUIRED
                        ) {
                            controlFlow.update {
                                it.copy(
                                    pendingUrgentCategory = null,
                                    urgentAuthPromptCategory = pending,
                                )
                            }
                        }
                    }

                    UrgentCategory.SPARK_DETERIORATING -> {
                        controlFlow.update { it.copy(pendingUrgentCategory = null) }
                    }
                }
            }
        }
    }

    fun refreshBusinessDate() {
        val current = currentBusinessDate()
        if (businessDateFlow.value != current) {
            businessDateFlow.value = current
        }
        refreshTickFlow.value += 1L
    }

    fun handleUrgentCategory(categoryName: String?, signalNonce: Long = 0L) {
        if (categoryName.isNullOrBlank()) return
        if (signalNonce != 0L && signalNonce == lastHandledUrgentNonce) return
        val category = UrgentCategory.entries.firstOrNull { it.name == categoryName } ?: return
        if (signalNonce != 0L) {
            lastHandledUrgentNonce = signalNonce
        }
        controlFlow.update { it.copy(pendingUrgentCategory = category) }
    }

    fun onReloginClick() {
        val current = uiState.value
        val status = current.account?.status ?: AccountStatus.PENDING_LOGIN
        when (status) {
            AccountStatus.VERIFICATION_REQUIRED -> {
                _effects.tryEmit(TodayUiEffect.OpenLogin(LoginMode.INSPECT_OR_VERIFY, accountId))
            }

            AccountStatus.PENDING_LOGIN,
            AccountStatus.AUTH_EXPIRED,
            -> {
                _effects.tryEmit(TodayUiEffect.OpenLogin(LoginMode.PRESERVE_SESSION, accountId))
            }

            AccountStatus.RUNNING -> {
                if (current.executionState.running) {
                    _effects.tryEmit(TodayUiEffect.ShowSnackbar("当前正在执行任务，请等待完成后再操作登录"))
                } else {
                    controlFlow.update { it.copy(showReloginChoiceDialog = true) }
                }
            }

            AccountStatus.HEALTHY,
            AccountStatus.PAUSED,
            AccountStatus.COOLDOWN,
            AccountStatus.DOM_BROKEN,
            -> {
                controlFlow.update { it.copy(showReloginChoiceDialog = true) }
            }
        }
    }

    fun dismissReloginChoiceDialog() {
        controlFlow.update { it.copy(showReloginChoiceDialog = false) }
    }

    fun chooseReloginPreserveSession() {
        controlFlow.update { it.copy(showReloginChoiceDialog = false) }
        _effects.tryEmit(TodayUiEffect.OpenLogin(LoginMode.PRESERVE_SESSION, accountId))
    }

    fun chooseReloginClearAndRelogin() {
        controlFlow.update { it.copy(showReloginChoiceDialog = false) }
        _effects.tryEmit(TodayUiEffect.OpenLogin(LoginMode.CLEAR_AND_RELOGIN, accountId))
    }

    fun openUnknownReview(jobId: String? = null) {
        val targetJobId = jobId ?: uiState.value.unknownReviewJobs.firstOrNull()?.job?.id ?: return
        controlFlow.update { it.copy(activeUnknownReviewJobId = targetJobId) }
    }

    fun dismissUnknownReview() {
        controlFlow.update { it.copy(activeUnknownReviewJobId = null) }
    }

    fun resolveUnknownAsSucceeded(jobId: String) {
        if (!resolvingJobIds.add(jobId)) return
        controlFlow.update { it.copy(isResolvingAction = true) }
        vmScope.launch {
            try {
                val nowMs = clock.millis()
                val resolved = withContext(ioDispatcher) {
                    repository.resolveUnknownJobAsSucceeded(jobId, nowMs)
                }
                if (resolved) {
                    withContext(ioDispatcher) {
                        scheduleGateway.rescheduleAll(clock.instant())
                    }
                    controlFlow.update { state ->
                        state.copy(
                            activeUnknownReviewJobId = if (state.activeUnknownReviewJobId == jobId) {
                                null
                            } else {
                                state.activeUnknownReviewJobId
                            },
                            manualBatchFeedback = null,
                        )
                    }
                    _effects.tryEmit(TodayUiEffect.ShowSnackbar("已标为发送成功，连续天数已 +1"))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                _effects.tryEmit(TodayUiEffect.ShowSnackbar("更新记录失败，请稍后重试"))
            } finally {
                resolvingJobIds.remove(jobId)
                controlFlow.update { it.copy(isResolvingAction = false) }
            }
        }
    }

    fun resolveUnknownAsCancelled(jobId: String) {
        if (!resolvingJobIds.add(jobId)) return
        controlFlow.update { it.copy(isResolvingAction = true) }
        vmScope.launch {
            try {
                val nowMs = clock.millis()
                val resolved = withContext(ioDispatcher) {
                    repository.resolveUnknownJobAsCancelled(jobId, nowMs)
                }
                if (resolved) {
                    withContext(ioDispatcher) {
                        scheduleGateway.rescheduleAll(clock.instant())
                    }
                    controlFlow.update { state ->
                        state.copy(
                            activeUnknownReviewJobId = if (state.activeUnknownReviewJobId == jobId) {
                                null
                            } else {
                                state.activeUnknownReviewJobId
                            },
                            manualBatchFeedback = null,
                        )
                    }
                    _effects.tryEmit(TodayUiEffect.ShowSnackbar("已标为今日取消，不再自动发送"))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                _effects.tryEmit(TodayUiEffect.ShowSnackbar("更新记录失败，请稍后重试"))
            } finally {
                resolvingJobIds.remove(jobId)
                controlFlow.update { it.copy(isResolvingAction = false) }
            }
        }
    }

    fun openCooldownDetail() {
        controlFlow.update { it.copy(showCooldownDetailDialog = true) }
    }

    fun dismissCooldownDetail() {
        controlFlow.update { it.copy(showCooldownDetailDialog = false) }
    }

    fun clearCooldown() {
        if (controlFlow.value.isResolvingAction) return
        controlFlow.update { it.copy(isResolvingAction = true) }
        vmScope.launch {
            try {
                val nowMs = clock.millis()
                withContext(ioDispatcher) {
                    repository.clearAccountCooldown(accountId, nowMs)
                    scheduleGateway.rescheduleAll(clock.instant())
                }
                controlFlow.update {
                    it.copy(
                        showCooldownDetailDialog = false,
                        manualBatchFeedback = null,
                    )
                }
                _effects.tryEmit(TodayUiEffect.ShowSnackbar("已解除熔断并恢复定时运行"))
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                _effects.tryEmit(TodayUiEffect.ShowSnackbar("解除熔断失败，请重试"))
            } finally {
                controlFlow.update { it.copy(isResolvingAction = false) }
            }
        }
    }

    fun dismissDomBrokenDialog() {
        controlFlow.update { it.copy(showDomBrokenDialog = false) }
    }

    fun dismissUrgentAuthPrompt() {
        controlFlow.update { it.copy(urgentAuthPromptCategory = null) }
    }

    fun confirmUrgentAuthPrompt() {
        val category = controlFlow.value.urgentAuthPromptCategory
        controlFlow.update { it.copy(urgentAuthPromptCategory = null) }
        val mode = when (category) {
            UrgentCategory.VERIFICATION_REQUIRED -> LoginMode.INSPECT_OR_VERIFY
            else -> LoginMode.PRESERVE_SESSION
        }
        _effects.tryEmit(TodayUiEffect.OpenLogin(mode, accountId))
    }

    fun onTriggerManualBatchClick() {
        val snapshot = uiState.value
        val account = snapshot.account
        val unknownJobs = snapshot.unknownReviewJobs

        if (unknownJobs.isNotEmpty() || snapshot.hasBlockingTodayJob) {
            controlFlow.update {
                it.copy(
                    manualBatchFeedback = ManualBatchFeedback(
                        reason = ManualBatchBlockReason.UNKNOWN_OR_INFLIGHT,
                        title = "已硬禁止执行今日续火",
                        message = "当前存在发送结果未明待核对或正在确认中的任务。为防重复发送，必须先核对确认结果后才能继续。",
                        canJumpToUnknownReview = unknownJobs.isNotEmpty(),
                    ),
                )
            }
            return
        }

        if (account == null) {
            controlFlow.update {
                it.copy(
                    manualBatchFeedback = ManualBatchFeedback(
                        reason = ManualBatchBlockReason.ACCOUNT_NOT_READY,
                        title = "尚未完成登录配置",
                        message = "请先扫码登录抖音网页端后再执行今日续火。",
                        canJumpToLogin = true,
                    ),
                )
            }
            return
        }

        when (account.status) {
            AccountStatus.PENDING_LOGIN,
            AccountStatus.AUTH_EXPIRED,
            -> {
                controlFlow.update {
                    it.copy(
                        manualBatchFeedback = ManualBatchFeedback(
                            reason = ManualBatchBlockReason.ACCOUNT_NOT_READY,
                            title = "需要先登录抖音网页端",
                            message = "当前账号处于${accountStatusLabel(account.status)}状态，请先完成扫码登录。",
                            canJumpToLogin = true,
                        ),
                    )
                }
                return
            }

            AccountStatus.VERIFICATION_REQUIRED -> {
                controlFlow.update {
                    it.copy(
                        manualBatchFeedback = ManualBatchFeedback(
                            reason = ManualBatchBlockReason.ACCOUNT_NOT_READY,
                            title = "需要先完成身份验证",
                            message = "检测到安全验证风控提示，请先前往保留态网页完成验证。",
                            canJumpToLogin = true,
                        ),
                    )
                }
                return
            }

            AccountStatus.COOLDOWN -> {
                controlFlow.update {
                    it.copy(
                        manualBatchFeedback = ManualBatchFeedback(
                            reason = ManualBatchBlockReason.ACCOUNT_NOT_READY,
                            title = "账号处于连续失败熔断中",
                            message = "已连续多轮发送失败并熔断暂停，请核查问题后点击「解除熔断并恢复运行」。",
                            canRecoverCooldown = true,
                        ),
                    )
                }
                return
            }

            AccountStatus.DOM_BROKEN -> {
                controlFlow.update {
                    it.copy(
                        manualBatchFeedback = ManualBatchFeedback(
                            reason = ManualBatchBlockReason.ACCOUNT_NOT_READY,
                            title = "页面核心结构异常",
                            message = "抖音网页选择器未能匹配核心骨架，请先前往设置页运行网页结构体检或定位器覆写。",
                            canJumpToSettings = true,
                        ),
                    )
                }
                return
            }

            AccountStatus.PAUSED -> {
                controlFlow.update {
                    it.copy(
                        manualBatchFeedback = ManualBatchFeedback(
                            reason = ManualBatchBlockReason.ACCOUNT_PAUSED,
                            title = "账号当前已暂停",
                            message = "请先在设置页开启自动调度总开关或恢复账号运行状态。",
                            canJumpToSettings = true,
                        ),
                    )
                }
                return
            }

            AccountStatus.RUNNING -> {
                _effects.tryEmit(TodayUiEffect.ShowSnackbar("续火任务正在执行中，请留意进度提示"))
                return
            }

            AccountStatus.HEALTHY -> Unit
        }

        if (!account.enabled) {
            controlFlow.update {
                it.copy(
                    manualBatchFeedback = ManualBatchFeedback(
                        reason = ManualBatchBlockReason.ACCOUNT_PAUSED,
                        title = "自动续火开关未开启",
                        message = "当前账号总开关处于关闭状态，请先在「设置」页启用后再执行。",
                        canJumpToSettings = true,
                    ),
                )
            }
            return
        }

        if (snapshot.enabledTargetCount == 0) {
            controlFlow.update {
                it.copy(
                    manualBatchFeedback = ManualBatchFeedback(
                        reason = ManualBatchBlockReason.NO_ENABLED_TARGETS,
                        title = "暂无启用的续火好友",
                        message = "请先前往「好友」页扫码导入火花对象或手动启用需要续火的好友。",
                        canJumpToTargets = true,
                    ),
                )
            }
            return
        }

        if (snapshot.pendingTargetCount == 0) {
            controlFlow.update {
                it.copy(
                    manualBatchFeedback = ManualBatchFeedback(
                        reason = ManualBatchBlockReason.ALL_DONE_OR_SKIPPED_TODAY,
                        title = "今日续火已全部处理",
                        message = "今日所有启用好友已完成发送或已标为取消/终态跳过。若需针对单个好友补发测试，请前往「好友」页使用「单发测试」。",
                        canJumpToTargets = true,
                    ),
                )
            }
            return
        }

        val started = executionLauncher.enqueueManualBatch(account.id)
        if (!started) {
            controlFlow.update {
                it.copy(
                    manualBatchFeedback = ManualBatchFeedback(
                        reason = ManualBatchBlockReason.SERVICE_START_FAILED,
                        title = "未能启动执行服务",
                        message = "系统当前限制启动前台服务，请确认应用在前台并检查后台稳定性权限。",
                        canJumpToSettings = true,
                    ),
                )
            }
        } else {
            _effects.tryEmit(TodayUiEffect.ShowSnackbar("已开始执行今日续火任务"))
        }
    }

    fun dismissManualBatchFeedback() {
        controlFlow.update { it.copy(manualBatchFeedback = null) }
    }

    fun toggleJobExpanded(jobId: String) {
        val currentExpanded = controlFlow.value.expandedJobIds
        if (jobId in currentExpanded) {
            controlFlow.update { it.copy(expandedJobIds = it.expandedJobIds - jobId) }
            return
        }
        controlFlow.update { it.copy(expandedJobIds = it.expandedJobIds + jobId) }
        vmScope.launch {
            try {
                val loaded = withContext(ioDispatcher) {
                    repository.listAttemptsForJob(jobId)
                }
                controlFlow.update { state ->
                    state.copy(
                        attemptsByJob = state.attemptsByJob + (jobId to loaded),
                        loadedAttemptJobIds = state.loadedAttemptJobIds + jobId,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                controlFlow.update { state ->
                    state.copy(loadedAttemptJobIds = state.loadedAttemptJobIds + jobId)
                }
            }
        }
    }

    private fun buildUiState(db: DbSnapshot, meta: MetaSnapshot): TodayUiState {
        val account = db.account
        val targetsById = db.targets.associateBy { it.id }
        val templatesById = meta.templates.associateBy { it.id }
        val enabledTargets = db.targets.filter { it.enabled }
        val pausedTargetCount = db.targets.count { !it.enabled }
        val maxLocalStreakDays = db.targets.maxOfOrNull { it.streakDays } ?: 0
        val todayJobsByTarget = db.todayJobs.groupBy { it.targetId }

        val pendingCount = enabledTargets.count { target ->
            SendGuard.canDispatchDaily(todayJobsByTarget[target.id].orEmpty())
        }
        val dailySucceededTargets = db.todayJobs
            .filter { it.messageSlotId == SendGuard.DAILY_SLOT_ID && it.status == JobStatus.SUCCEEDED }
            .mapTo(HashSet()) { it.targetId }

        val unknownIds = db.unknownJobs.mapTo(HashSet()) { it.id }
        val todayTerminalErrors = db.todayJobs.count {
            it.id !in unknownIds && it.status == JobStatus.FAILED_TERMINAL
        }
        val issueCount = unknownIds.size + todayTerminalErrors

        val scheduleEvaluation = if (account != null) {
            val zoneId = DailySchedulePlanner.resolveZoneId(account.timezone)
            DailySchedulePlanner.evaluateNow(
                now = clock.instant(),
                account = account,
                targets = enabledTargets,
                todayJobs = db.todayJobs,
                zoneId = zoneId,
            )
        } else {
            null
        }

        val missingIds = meta.missingImageTemplateIds
        val missingEnabledTargetCount = enabledTargets.count { it.templateId in missingIds }

        val rawExec = meta.executionState
        val execBelongsToCurrent = rawExec.activeAccountId == accountId ||
            (rawExec.activeAccountId == null && accountId == DEFAULT_ACCOUNT_ID)
        val scopedExecutionState = if (execBelongsToCurrent) {
            rawExec
        } else {
            ExecutionLiveState(
                running = false,
                activeAccountId = rawExec.activeAccountId,
            )
        }
        val otherRunningAccountName = if (rawExec.running &&
            rawExec.activeAccountId != null &&
            rawExec.activeAccountId != accountId
        ) {
            meta.allAccounts.firstOrNull { it.id == rawExec.activeAccountId }?.displayName ?: "其他账号"
        } else {
            null
        }
        val otherRunningTaskLabel = if (otherRunningAccountName != null) {
            rawExec.currentTask
        } else {
            null
        }

        val unknownItems = db.unknownJobs.map { job ->
            UnknownJobReviewItem(
                job = job,
                targetName = targetsById[job.targetId]?.nicknameSnapshot ?: "已归档好友",
                templateLabel = resolveTemplateLabel(job, templatesById, missingIds),
                isDailySlot = job.messageSlotId == SendGuard.DAILY_SLOT_ID,
                isImageMissing = job.templateId in missingIds,
            )
        }

        val recentItems = db.recentJobs.map { job ->
            val attempts = meta.controls.attemptsByJob[job.id].orEmpty()
            RecentJobItem(
                job = job,
                targetName = targetsById[job.targetId]?.nicknameSnapshot ?: "已归档好友",
                templateLabel = resolveTemplateLabel(job, templatesById, missingIds),
                isDailySlot = job.messageSlotId == SendGuard.DAILY_SLOT_ID,
                isImageMissing = job.templateId in missingIds,
                attempts = attempts,
                attemptsLoaded = job.id in meta.controls.loadedAttemptJobIds,
                expanded = job.id in meta.controls.expandedJobIds,
            )
        }

        val failureStats = db.recentJobs
            .filter { it.status == JobStatus.FAILED_TERMINAL || it.status == JobStatus.FAILED_RETRYABLE }
            .mapNotNull { it.failureClass }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { SanitizedFailureStat(failureClass = it.key, count = it.value) }

        val activeReview = meta.controls.activeUnknownReviewJobId?.let { activeId ->
            unknownItems.firstOrNull { it.job.id == activeId }
        }

        return TodayUiState(
            isLoading = false,
            businessDate = meta.businessDate,
            account = account,
            enabledTargetCount = enabledTargets.size,
            pendingTargetCount = pendingCount,
            succeededTodayCount = dailySucceededTargets.size,
            issueTodayCount = issueCount,
            nextAutoTriggerAtEpochMs = scheduleEvaluation?.nextWakeAt?.toEpochMilli(),
            authRecheckAtEpochMs = scheduleEvaluation?.authRecheckAt?.toEpochMilli(),
            unknownReviewJobs = unknownItems,
            recentJobs = recentItems,
            cooldownFailureStats = failureStats,
            executionState = scopedExecutionState,
            otherRunningAccountName = otherRunningAccountName,
            otherRunningTaskLabel = otherRunningTaskLabel,
            missingImageTemplateCount = missingIds.size,
            missingImageEnabledTargetCount = missingEnabledTargetCount,
            showReloginChoiceDialog = meta.controls.showReloginChoiceDialog,
            activeUnknownReviewJob = activeReview,
            showCooldownDetailDialog = meta.controls.showCooldownDetailDialog,
            showDomBrokenDialog = meta.controls.showDomBrokenDialog,
            urgentAuthPromptCategory = meta.controls.urgentAuthPromptCategory,
            manualBatchFeedback = meta.controls.manualBatchFeedback,
            hasBlockingTodayJob = SendGuard.hasBlockingUnknownOrInflight(db.todayJobs),
            isResolvingAction = meta.controls.isResolvingAction,
            maxLocalStreakDays = maxLocalStreakDays,
            pausedTargetCount = pausedTargetCount,
        )
    }

    private fun resolveTemplateLabel(
        job: SendJobEntity,
        templatesById: Map<String, MessageTemplateEntity>,
        missingImageTemplateIds: Set<String>,
    ): String {
        val tpl = job.templateId?.let { templatesById[it] }
        if (tpl != null) {
            return when (tpl.type) {
                TemplateType.STICKER -> "${tpl.name}（表情）"
                TemplateType.POOL -> "${tpl.name}（文案池）"
                TemplateType.TEXT -> "${tpl.name}（文本）"
                TemplateType.IMAGE -> if (tpl.id in missingImageTemplateIds) {
                    "${tpl.name}（图片 · 待补图）"
                } else {
                    "${tpl.name}（图片）"
                }
            }
        }
        return if (job.messageSlotId.startsWith(SendGuard.MANUAL_SLOT_PREFIX)) {
            "手动单发文本"
        } else {
            "默认模板"
        }
    }

    private fun currentBusinessDate(): String = LocalDate.now(clock).toString()

    private data class DbSnapshot(
        val account: AccountEntity?,
        val targets: List<ContractTargetEntity>,
        val todayJobs: List<SendJobEntity>,
        val unknownJobs: List<SendJobEntity>,
        val recentJobs: List<SendJobEntity>,
    )

    private data class MetaSnapshot(
        val templates: List<MessageTemplateEntity>,
        val missingImageTemplateIds: Set<String>,
        val allAccounts: List<AccountEntity>,
        val executionState: ExecutionLiveState,
        val businessDate: String,
        val controls: ControlState,
    )

    private data class ControlState(
        val showReloginChoiceDialog: Boolean = false,
        val activeUnknownReviewJobId: String? = null,
        val showCooldownDetailDialog: Boolean = false,
        val showDomBrokenDialog: Boolean = false,
        val urgentAuthPromptCategory: UrgentCategory? = null,
        val manualBatchFeedback: ManualBatchFeedback? = null,
        val expandedJobIds: Set<String> = emptySet(),
        val attemptsByJob: Map<String, List<SendAttemptEntity>> = emptyMap(),
        val loadedAttemptJobIds: Set<String> = emptySet(),
        val pendingUrgentCategory: UrgentCategory? = null,
        val isResolvingAction: Boolean = false,
    )

    companion object {
        private const val RECENT_JOBS_LIMIT = 40

        fun factory(
            container: AppContainer,
            accountId: String = DEFAULT_ACCOUNT_ID,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TodayViewModel(
                        repository = RoomTodayRepository(container.database),
                        scheduleGateway = object : TodayScheduleGateway {
                            override suspend fun rescheduleAll(now: Instant): Instant? =
                                container.scheduleCoordinator.rescheduleAll(now)
                        },
                        executionLauncher = object : TodayExecutionLauncher {
                            override fun enqueueManualBatch(accountId: String): Boolean =
                                io.github.oinzc.flamecontractor.execution.ExecutionForegroundService
                                    .enqueueManualBatch(container.appContext, accountId)
                        },
                        accountId = accountId,
                        imageStore = container.templateImageStore,
                    ) as T
                }
            }
    }
}

internal fun accountStatusLabel(status: AccountStatus): String = when (status) {
    AccountStatus.PENDING_LOGIN -> "待扫码登录"
    AccountStatus.HEALTHY -> "健康"
    AccountStatus.RUNNING -> "执行中"
    AccountStatus.PAUSED -> "已暂停"
    AccountStatus.AUTH_EXPIRED -> "登录已失效"
    AccountStatus.VERIFICATION_REQUIRED -> "需身份验证"
    AccountStatus.DOM_BROKEN -> "结构异常"
    AccountStatus.COOLDOWN -> "失败熔断"
}
