package io.github.oinzc.flamecontractor.ui.targets

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.oinzc.flamecontractor.core.identity.MatchStatus
import io.github.oinzc.flamecontractor.core.storage.AndroidTemplateImageStore
import io.github.oinzc.flamecontractor.core.storage.TemplateImageStorage
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunEntity
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.domain.ManualSendDecision
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.execution.ExecutionForegroundService
import io.github.oinzc.flamecontractor.execution.ExecutionLiveState
import io.github.oinzc.flamecontractor.execution.ExecutionRuntimeBus
import io.github.oinzc.flamecontractor.execution.ScanReconciliationBuilder
import io.github.oinzc.flamecontractor.execution.ScanReconciliationCodec
import io.github.oinzc.flamecontractor.execution.ScanReconciliationSnapshot
import io.github.oinzc.flamecontractor.execution.ScannedChatCandidate
import io.github.oinzc.flamecontractor.scheduler.DailySchedulePlanner
import io.github.oinzc.flamecontractor.scheduler.ScheduleCoordinator
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 好友与扫描对账仓库接口（纯 Kotlin，支持 JVM 单元测试零 Robolectric 注入假实现）。
 */
interface TargetsRepository {
    fun observeAccount(accountId: String = DEFAULT_ACCOUNT_ID): Flow<AccountEntity?>
    fun observeActiveTargets(accountId: String = DEFAULT_ACCOUNT_ID): Flow<List<ContractTargetEntity>>
    fun observeTemplates(accountId: String = DEFAULT_ACCOUNT_ID): Flow<List<MessageTemplateEntity>>
    fun observeJobsForDate(accountId: String, businessDate: String): Flow<List<SendJobEntity>>
    fun observeLatestScanRun(accountId: String = DEFAULT_ACCOUNT_ID): Flow<ScanRunEntity?>

    suspend fun getAccount(accountId: String = DEFAULT_ACCOUNT_ID): AccountEntity?
    suspend fun listActiveTargets(accountId: String = DEFAULT_ACCOUNT_ID): List<ContractTargetEntity>
    suspend fun listJobsForTargetDate(
        accountId: String,
        targetId: String,
        businessDate: String,
    ): List<SendJobEntity>

    suspend fun findByNicknameIncludingDeleted(
        accountId: String,
        nickname: String,
    ): ContractTargetEntity?

    suspend fun findByPlatformUserIdIncludingDeleted(
        accountId: String,
        platformUserId: String,
    ): ContractTargetEntity?

    suspend fun findByPlatformConversationIdIncludingDeleted(
        accountId: String,
        platformConversationId: String,
    ): ContractTargetEntity?

    suspend fun insertTarget(target: ContractTargetEntity)
    suspend fun updateTarget(target: ContractTargetEntity)
    suspend fun softDeleteTarget(id: String, deletedAt: Long)

    suspend fun reviveTarget(
        id: String,
        nicknameSnapshot: String,
        platformUserId: String?,
        platformHandle: String?,
        platformConversationId: String?,
        conversationType: String?,
        enabled: Boolean,
        updatedAt: Long,
    )

    suspend fun updateOfficialSparkSnapshot(
        id: String,
        sparkDays: Int?,
        sparkKind: String?,
        sparkLabel: String?,
        scannedAt: Long,
    )

    suspend fun getLatestScanRun(accountId: String = DEFAULT_ACCOUNT_ID): ScanRunEntity?
}

interface TargetExecutionGateway {
    val liveState: StateFlow<ExecutionLiveState>
    fun enqueueManualScan(accountId: String = DEFAULT_ACCOUNT_ID): Boolean
    fun enqueueSingleSend(
        targetId: String,
        overrideText: String? = null,
        accountId: String = DEFAULT_ACCOUNT_ID,
    ): Boolean

    fun enqueueSingleIdentityRepair(
        targetId: String,
        accountId: String = DEFAULT_ACCOUNT_ID,
    ): Boolean
}

interface TargetScheduleGateway {
    suspend fun rescheduleAll(now: Instant = Instant.now()): Instant?
}

class RoomTargetsRepository(
    private val database: FlameDatabase,
) : TargetsRepository {

    private val accountDao = database.accountDao()
    private val targetDao = database.targetDao()
    private val templateDao = database.templateDao()
    private val sendJobDao = database.sendJobDao()
    private val scanRunDao = database.scanRunDao()

    override fun observeAccount(accountId: String): Flow<AccountEntity?> =
        accountDao.observeAccount(accountId)

    override fun observeActiveTargets(accountId: String): Flow<List<ContractTargetEntity>> =
        targetDao.observeActiveTargets(accountId)

    override fun observeTemplates(accountId: String): Flow<List<MessageTemplateEntity>> =
        templateDao.observeTemplates(accountId)

    override fun observeJobsForDate(accountId: String, businessDate: String): Flow<List<SendJobEntity>> =
        sendJobDao.observeJobsForDate(accountId, businessDate)

    override fun observeLatestScanRun(accountId: String): Flow<ScanRunEntity?> =
        scanRunDao.observeLatestScanRun(accountId)

    override suspend fun getAccount(accountId: String): AccountEntity? =
        accountDao.getAccount(accountId)

    override suspend fun listActiveTargets(accountId: String): List<ContractTargetEntity> =
        targetDao.listActiveTargets(accountId)

    override suspend fun listJobsForTargetDate(
        accountId: String,
        targetId: String,
        businessDate: String,
    ): List<SendJobEntity> =
        sendJobDao.listJobsForTargetDate(accountId, targetId, businessDate)

    override suspend fun findByNicknameIncludingDeleted(
        accountId: String,
        nickname: String,
    ): ContractTargetEntity? =
        targetDao.findByNicknameIncludingDeleted(accountId, nickname)

    override suspend fun findByPlatformUserIdIncludingDeleted(
        accountId: String,
        platformUserId: String,
    ): ContractTargetEntity? =
        targetDao.findByPlatformUserIdIncludingDeleted(accountId, platformUserId)

    override suspend fun findByPlatformConversationIdIncludingDeleted(
        accountId: String,
        platformConversationId: String,
    ): ContractTargetEntity? =
        targetDao.findByPlatformConversationIdIncludingDeleted(accountId, platformConversationId)

    override suspend fun insertTarget(target: ContractTargetEntity) {
        targetDao.insert(target)
    }

    override suspend fun updateTarget(target: ContractTargetEntity) {
        targetDao.update(target)
    }

    override suspend fun softDeleteTarget(id: String, deletedAt: Long) {
        targetDao.softDelete(id, deletedAt)
    }

    override suspend fun reviveTarget(
        id: String,
        nicknameSnapshot: String,
        platformUserId: String?,
        platformHandle: String?,
        platformConversationId: String?,
        conversationType: String?,
        enabled: Boolean,
        updatedAt: Long,
    ) {
        targetDao.reviveTarget(
            id = id,
            nicknameSnapshot = nicknameSnapshot,
            platformUserId = platformUserId,
            platformHandle = platformHandle,
            platformConversationId = platformConversationId,
            conversationType = conversationType,
            enabled = enabled,
            updatedAt = updatedAt,
        )
    }

    override suspend fun updateOfficialSparkSnapshot(
        id: String,
        sparkDays: Int?,
        sparkKind: String?,
        sparkLabel: String?,
        scannedAt: Long,
    ) {
        targetDao.updateOfficialSparkSnapshot(
            id = id,
            sparkDays = sparkDays,
            sparkKind = sparkKind,
            sparkLabel = sparkLabel,
            scannedAt = scannedAt,
        )
    }

    override suspend fun getLatestScanRun(accountId: String): ScanRunEntity? =
        scanRunDao.getLatestScanRun(accountId)
}

class AndroidTargetExecutionGateway(
    context: Context,
) : TargetExecutionGateway {

    private val appContext = context.applicationContext ?: context
    override val liveState: StateFlow<ExecutionLiveState> = ExecutionRuntimeBus.state

    override fun enqueueManualScan(accountId: String): Boolean =
        ExecutionForegroundService.enqueueManualScan(appContext, accountId)

    override fun enqueueSingleSend(
        targetId: String,
        overrideText: String?,
        accountId: String,
    ): Boolean =
        ExecutionForegroundService.enqueueSingleSend(
            context = appContext,
            targetId = targetId,
            overrideText = overrideText,
            accountId = accountId,
        )

    override fun enqueueSingleIdentityRepair(
        targetId: String,
        accountId: String,
    ): Boolean =
        ExecutionForegroundService.enqueueSingleIdentityRepair(
            context = appContext,
            targetId = targetId,
            accountId = accountId,
        )
}

class AndroidTargetScheduleGateway(
    private val scheduleCoordinator: ScheduleCoordinator,
) : TargetScheduleGateway {
    override suspend fun rescheduleAll(now: Instant): Instant? =
        scheduleCoordinator.rescheduleAll(now)
}

enum class TargetFilter(val label: String) {
    ALL("全部"),
    HAS_SPARK("有火花"),
    UNBOUND("未绑身份"),
    DISABLED("已禁用"),
}

data class TargetItemUi(
    val target: ContractTargetEntity,
    val templateName: String?,
    val templateType: TemplateType? = null,
    val isTemplateMissingImage: Boolean = false,
    val hasBoundIdentity: Boolean,
    val isAmbiguousInLatestScan: Boolean,
    val hasOfficialSpark: Boolean,
    val todayStatusLabel: String?,
    val hasBlockingTodayJob: Boolean,
    val hasSucceededTodayJob: Boolean,
)

data class TargetEditorState(
    val isNew: Boolean,
    val editingTargetId: String?,
    val nickname: String,
    val conversationType: String,
    val typeLockedReason: String? = null,
    val templateId: String?,
    val sendTimeHhmm: String,
    val enabled: Boolean,
    val errorMessage: String? = null,
)

sealed interface SingleSendDialogState {
    data object None : SingleSendDialogState

    data class Blocked(
        val targetId: String,
        val targetName: String,
        val message: String,
    ) : SingleSendDialogState

    data class ConfirmAlreadySucceeded(
        val target: ContractTargetEntity,
        val templateName: String?,
        val templateType: TemplateType? = null,
        val isTemplateMissingImage: Boolean = false,
        val overrideText: String = "",
        val message: String = "今日已发送成功，确认要额外补发一条测试消息吗？",
    ) : SingleSendDialogState

    data class ConfirmSend(
        val target: ContractTargetEntity,
        val templateName: String?,
        val templateType: TemplateType? = null,
        val isTemplateMissingImage: Boolean = false,
        val overrideText: String = "",
    ) : SingleSendDialogState
}

data class ScanCandidateUi(
    val candidate: ScannedChatCandidate,
    val key: String,
    val effectivelyAlreadyImported: Boolean,
    val isAmbiguous: Boolean,
    val hasOfficialSpark: Boolean,
    val selectable: Boolean,
    val selected: Boolean,
    val statusNote: String,
)

data class ScanReconcileState(
    val snapshotKey: String? = null,
    val scanRunId: String? = null,
    val scannedAtEpochMs: Long? = null,
    val discoveredCount: Int = 0,
    val sparkCount: Int = 0,
    val searchQuery: String = "",
    val defaultTemplateId: String? = null,
    val allCandidates: List<ScanCandidateUi> = emptyList(),
    val filteredCandidates: List<ScanCandidateUi> = emptyList(),
    val selectedCount: Int = 0,
    val importableSparkCount: Int = 0,
    val alreadyImportedWithSparkCount: Int = 0,
    val importing: Boolean = false,
    val errorMessage: String? = null,
)

data class TargetsUiState(
    val accountStatus: AccountStatus = AccountStatus.PENDING_LOGIN,
    val searchQuery: String = "",
    val filter: TargetFilter = TargetFilter.ALL,
    val allTargetsCount: Int = 0,
    val filterCounts: Map<TargetFilter, Int> = emptyMap(),
    val items: List<TargetItemUi> = emptyList(),
    val templates: List<MessageTemplateEntity> = emptyList(),
    val missingImageTemplateIds: Set<String> = emptySet(),
    val scanRunning: Boolean = false,
    val scanDiscoveredCount: Int = 0,
    val scanTaskLabel: String? = null,
    val hasAvailableSnapshot: Boolean = false,
    val isReconcileSheetOpen: Boolean = false,
    val reconcileState: ScanReconcileState = ScanReconcileState(),
    val editorState: TargetEditorState? = null,
    val deleteConfirmTarget: ContractTargetEntity? = null,
    val singleSendDialog: SingleSendDialogState = SingleSendDialogState.None,
    val feedbackMessage: String? = null,
    val isBusy: Boolean = false,
)

private data class SnapshotSelectionState(
    val initializedKey: String? = null,
    val selectedKeys: Set<String> = emptySet(),
    val importedKeys: Set<String> = emptySet(),
)

private data class LocalUiControls(
    val searchQuery: String = "",
    val filter: TargetFilter = TargetFilter.ALL,
    val reconcileSearchQuery: String = "",
    val reconcileDefaultTemplateId: String? = null,
    val isReconcileSheetOpen: Boolean = false,
    val importing: Boolean = false,
    val reconcileErrorMessage: String? = null,
    val editorState: TargetEditorState? = null,
    val deleteConfirmTarget: ContractTargetEntity? = null,
    val singleSendDialog: SingleSendDialogState = SingleSendDialogState.None,
    val feedbackMessage: String? = null,
    val isBusy: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TargetsViewModel(
    private val repository: TargetsRepository,
    private val executionGateway: TargetExecutionGateway,
    private val scheduleGateway: TargetScheduleGateway,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
    private val imageStore: TemplateImageStorage? = null,
    private val nowInstant: () -> Instant = { Instant.now() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope
        get() = externalScope ?: viewModelScope

    private val selectionTracker = MutableStateFlow(SnapshotSelectionState())
    private val localControls = MutableStateFlow(LocalUiControls())
    private val refreshTick = MutableStateFlow(0L)

    private val _uiState = MutableStateFlow(TargetsUiState())
    val uiState: StateFlow<TargetsUiState> = _uiState.asStateFlow()

    @Volatile
    private var pendingOpenReconcileAfterScan: Boolean = false

    @Volatile
    private var scanOriginSnapshotKey: String? = null

    @Volatile
    private var scanRequestBusUpdatedAt: Long = 0L

    @Volatile
    private var sawRunningDuringScanRequest: Boolean = false

    init {
        val accountFlow = repository.observeAccount(accountId)
        val targetsFlow = repository.observeActiveTargets(accountId)
        val templatesWithMissingFlow = combine(
            repository.observeTemplates(accountId),
            refreshTick,
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
        val todayJobsFlow = accountFlow.flatMapLatest { account ->
            val todayStr = computeBusinessDate(account?.timezone)
            repository.observeJobsForDate(accountId, todayStr)
        }
        val snapshotFlow = combine(
            executionGateway.liveState,
            repository.observeLatestScanRun(accountId),
        ) { live, scanRun ->
            resolveLatestSnapshot(live, scanRun)
        }.onEach { snapshot ->
            if (snapshot != null) {
                ensureOneTimePreselection(snapshot)
            }
        }

        // 监听手动扫描完成生命周期，严格校验账号归属后再自动打开对账抽屉
        scope.launch {
            executionGateway.liveState.collect { live ->
                if (!pendingOpenReconcileAfterScan) return@collect
                if (live.running) {
                    if (isLiveEventForAccount(live)) {
                        sawRunningDuringScanRequest = true
                    }
                    return@collect
                }
                val liveSnap = if (live.latestScanSnapshotAccountId == accountId) {
                    live.latestScanSnapshot
                } else {
                    null
                }
                val liveKey = liveSnap?.let(::snapshotIdentityKey)
                if (liveSnap != null && liveKey != scanOriginSnapshotKey) {
                    pendingOpenReconcileAfterScan = false
                    sawRunningDuringScanRequest = false
                    ensureOneTimePreselection(liveSnap)
                    localControls.update {
                        it.copy(
                            isReconcileSheetOpen = true,
                            reconcileErrorMessage = null,
                        )
                    }
                } else if (sawRunningDuringScanRequest ||
                    (isLiveEventForAccount(live) && live.lastUpdatedAtEpochMs != scanRequestBusUpdatedAt)
                ) {
                    pendingOpenReconcileAfterScan = false
                    sawRunningDuringScanRequest = false
                    val msg = live.lastMessage
                    if (!msg.isNullOrBlank()) {
                        localControls.update { it.copy(feedbackMessage = msg) }
                    }
                }
            }
        }

        scope.launch {
            val dataBundleFlow = combine(
                accountFlow,
                targetsFlow,
                templatesWithMissingFlow,
                todayJobsFlow,
                snapshotFlow,
            ) { account, targets, templatePair, todayJobs, snapshot ->
                DataBundle(
                    account = account,
                    targets = targets,
                    templates = templatePair.first,
                    missingImageTemplateIds = templatePair.second,
                    todayJobs = todayJobs,
                    snapshot = snapshot,
                )
            }

            combine(
                dataBundleFlow,
                executionGateway.liveState,
                selectionTracker,
                localControls,
            ) { data, live, selection, controls ->
                buildUiState(data, live, selection, controls)
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun refreshTemplateStates() {
        refreshTick.value += 1L
    }

    private fun isLiveEventForAccount(live: ExecutionLiveState): Boolean =
        live.activeAccountId == accountId ||
            (live.activeAccountId == null && accountId == DEFAULT_ACCOUNT_ID)

    private data class DataBundle(
        val account: AccountEntity?,
        val targets: List<ContractTargetEntity>,
        val templates: List<MessageTemplateEntity>,
        val missingImageTemplateIds: Set<String>,
        val todayJobs: List<SendJobEntity>,
        val snapshot: ScanReconciliationSnapshot?,
    )

    private fun resolveLatestSnapshot(
        live: ExecutionLiveState,
        scanRun: ScanRunEntity?,
    ): ScanReconciliationSnapshot? {
        val liveSnap = if (live.latestScanSnapshotAccountId == accountId) {
            live.latestScanSnapshot
        } else {
            null
        }
        val dbSnap = scanRun
            ?.takeIf { it.accountId == accountId }
            ?.let { ScanReconciliationCodec.decodeOrNull(it.reconciliationJson) }
        return when {
            liveSnap != null && dbSnap != null -> {
                if (dbSnap.scannedAtEpochMs > liveSnap.scannedAtEpochMs) dbSnap else liveSnap
            }
            liveSnap != null -> liveSnap
            else -> dbSnap
        }
    }

    private fun ensureOneTimePreselection(snapshot: ScanReconciliationSnapshot) {
        val key = snapshotIdentityKey(snapshot)
        selectionTracker.update { current ->
            if (current.initializedKey == key) {
                current
            } else {
                val sorted = ScanReconciliationBuilder.sortAndComputePreselection(snapshot.candidates)
                val initialSelected = sorted
                    .filter { it.preselected }
                    .map(::candidateKey)
                    .toSet()
                SnapshotSelectionState(
                    initializedKey = key,
                    selectedKeys = initialSelected,
                    importedKeys = emptySet(),
                )
            }
        }
    }

    private fun buildUiState(
        data: DataBundle,
        live: ExecutionLiveState,
        selection: SnapshotSelectionState,
        controls: LocalUiControls,
    ): TargetsUiState {
        val templateById = data.templates.associateBy { it.id }
        val jobsByTarget = data.todayJobs.groupBy { it.targetId }
        val snapshot = data.snapshot
        val snapshotKey = snapshot?.let(::snapshotIdentityKey)

        val sortedCandidates = if (snapshot != null) {
            ScanReconciliationBuilder.sortAndComputePreselection(snapshot.candidates)
        } else {
            emptyList()
        }

        val ambiguousNamesInLatestScan = sortedCandidates
            .filter { it.matchStatus == MatchStatus.AMBIGUOUS }
            .map { it.displayName }
            .toSet()

        val allItemUis = data.targets.map { target ->
            val targetJobs = jobsByTarget[target.id].orEmpty()
            val hasBlocking = SendGuard.hasBlockingUnknownOrInflight(targetJobs)
            val hasSucceeded = targetJobs.any { it.status == JobStatus.SUCCEEDED }
            val hasBound = target.hasBoundIdentity()
            val hasSpark = ScanReconciliationBuilder.hasOfficialSpark(
                sparkDays = target.sparkDays,
                sparkKind = target.sparkKind,
                sparkLabel = target.sparkLabel,
            )
            val tpl = target.templateId?.let { templateById[it] }
            TargetItemUi(
                target = target,
                templateName = tpl?.name,
                templateType = tpl?.type,
                isTemplateMissingImage = tpl != null && tpl.id in data.missingImageTemplateIds,
                hasBoundIdentity = hasBound,
                isAmbiguousInLatestScan = !hasBound && target.nicknameSnapshot in ambiguousNamesInLatestScan,
                hasOfficialSpark = hasSpark,
                todayStatusLabel = resolveTodayStatusLabel(targetJobs, target.enabled),
                hasBlockingTodayJob = hasBlocking,
                hasSucceededTodayJob = hasSucceeded,
            )
        }

        val filterCounts = mapOf(
            TargetFilter.ALL to allItemUis.size,
            TargetFilter.HAS_SPARK to allItemUis.count { it.hasOfficialSpark },
            TargetFilter.UNBOUND to allItemUis.count { !it.hasBoundIdentity },
            TargetFilter.DISABLED to allItemUis.count { !it.target.enabled },
        )

        val q = controls.searchQuery.trim()
        val filteredItems = allItemUis.filter { item ->
            val matchesFilter = when (controls.filter) {
                TargetFilter.ALL -> true
                TargetFilter.HAS_SPARK -> item.hasOfficialSpark
                TargetFilter.UNBOUND -> !item.hasBoundIdentity
                TargetFilter.DISABLED -> !item.target.enabled
            }
            if (!matchesFilter) return@filter false
            if (q.isEmpty()) return@filter true
            val t = item.target
            t.nicknameSnapshot.contains(q, ignoreCase = true) ||
                (t.platformHandle?.contains(q, ignoreCase = true) == true) ||
                (t.sparkLabel?.contains(q, ignoreCase = true) == true) ||
                (item.templateName?.contains(q, ignoreCase = true) == true) ||
                (t.conversationType == "group" && (q == "群" || q == "群聊")) ||
                (t.conversationType != "group" && q == "好友")
        }

        val effectiveSelectedKeys = when {
            snapshot == null -> emptySet()
            selection.initializedKey == snapshotKey -> selection.selectedKeys
            else -> sortedCandidates.filter { it.preselected }.map(::candidateKey).toSet()
        }
        val sessionImportedKeys = if (selection.initializedKey == snapshotKey) {
            selection.importedKeys
        } else {
            emptySet()
        }

        val candidateUis = sortedCandidates.map { c ->
            val cKey = candidateKey(c)
            val isImported = c.alreadyImported || cKey in sessionImportedKeys
            val isAmbiguous = c.matchStatus == MatchStatus.AMBIGUOUS
            val hasSpark = ScanReconciliationBuilder.hasOfficialSpark(c)
            val selectable = !isImported && !isAmbiguous
            val selected = selectable && cKey in effectiveSelectedKeys
            val note = when {
                isAmbiguous -> c.matchDetail ?: "同名对应多个平台账号或群会话，已禁止自动导入"
                isImported -> "已在好友列表中"
                c.matchStatus == MatchStatus.NOT_OBSERVED -> "本次未捕获到稳定 ID，可导入后补采身份"
                else -> c.matchDetail ?: "已唯一匹配，可安全导入"
            }
            ScanCandidateUi(
                candidate = c,
                key = cKey,
                effectivelyAlreadyImported = isImported,
                isAmbiguous = isAmbiguous,
                hasOfficialSpark = hasSpark,
                selectable = selectable,
                selected = selected,
                statusNote = note,
            )
        }

        val rq = controls.reconcileSearchQuery.trim()
        val filteredCandidates = if (rq.isEmpty()) {
            candidateUis
        } else {
            candidateUis.filter { ui ->
                val c = ui.candidate
                c.displayName.contains(rq, ignoreCase = true) ||
                    (c.platformHandle?.contains(rq, ignoreCase = true) == true) ||
                    (c.sparkLabel?.contains(rq, ignoreCase = true) == true) ||
                    (c.sparkDays != null && "${c.sparkDays}天".contains(rq, ignoreCase = true)) ||
                    (c.conversationType == "group" && (rq == "群" || rq == "群聊")) ||
                    (ui.isAmbiguous && (rq == "冲突" || rq == "同名"))
            }
        }

        val reconcileState = ScanReconcileState(
            snapshotKey = snapshotKey,
            scanRunId = snapshot?.scanRunId,
            scannedAtEpochMs = snapshot?.scannedAtEpochMs,
            discoveredCount = snapshot?.discoveredCount ?: 0,
            sparkCount = snapshot?.sparkCount ?: 0,
            searchQuery = controls.reconcileSearchQuery,
            defaultTemplateId = controls.reconcileDefaultTemplateId,
            allCandidates = candidateUis,
            filteredCandidates = filteredCandidates,
            selectedCount = candidateUis.count { it.selected },
            importableSparkCount = candidateUis.count {
                it.hasOfficialSpark && !it.effectivelyAlreadyImported && it.candidate.matchStatus == MatchStatus.BOUND
            },
            alreadyImportedWithSparkCount = candidateUis.count {
                it.effectivelyAlreadyImported && hasSparkPayload(it.candidate)
            },
            importing = controls.importing,
            errorMessage = controls.reconcileErrorMessage,
        )

        val belongsToThisAccount = isLiveEventForAccount(live)
        val scanTaskActive = (live.running && belongsToThisAccount && live.currentTask?.contains("扫描") == true) ||
            pendingOpenReconcileAfterScan

        return TargetsUiState(
            accountStatus = data.account?.status ?: AccountStatus.PENDING_LOGIN,
            searchQuery = controls.searchQuery,
            filter = controls.filter,
            allTargetsCount = allItemUis.size,
            filterCounts = filterCounts,
            items = filteredItems,
            templates = data.templates,
            missingImageTemplateIds = data.missingImageTemplateIds,
            scanRunning = scanTaskActive,
            scanDiscoveredCount = if (belongsToThisAccount) live.scanDiscoveredCount else 0,
            scanTaskLabel = if (scanTaskActive) live.currentTask else null,
            hasAvailableSnapshot = snapshot != null,
            isReconcileSheetOpen = controls.isReconcileSheetOpen,
            reconcileState = reconcileState,
            editorState = controls.editorState,
            deleteConfirmTarget = controls.deleteConfirmTarget,
            singleSendDialog = controls.singleSendDialog,
            feedbackMessage = controls.feedbackMessage,
            isBusy = controls.isBusy,
        )
    }

    fun onSearchQueryChanged(query: String) {
        localControls.update { it.copy(searchQuery = query) }
    }

    fun onFilterSelected(filter: TargetFilter) {
        localControls.update { it.copy(filter = filter) }
    }

    fun startManualScan() {
        val currentState = _uiState.value
        scanOriginSnapshotKey = currentState.reconcileState.snapshotKey
        scanRequestBusUpdatedAt = executionGateway.liveState.value.lastUpdatedAtEpochMs
        sawRunningDuringScanRequest = false
        pendingOpenReconcileAfterScan = true
        val enqueued = executionGateway.enqueueManualScan(accountId)
        if (!enqueued) {
            pendingOpenReconcileAfterScan = false
            localControls.update {
                it.copy(feedbackMessage = "无法启动前台扫描服务，请检查应用通知与后台运行权限")
            }
        }
    }

    fun openReconcileSheet() {
        localControls.update {
            it.copy(
                isReconcileSheetOpen = true,
                reconcileErrorMessage = null,
            )
        }
    }

    fun dismissReconcileSheet() {
        localControls.update {
            it.copy(
                isReconcileSheetOpen = false,
                reconcileErrorMessage = null,
            )
        }
    }

    fun onReconcileSearchChanged(query: String) {
        localControls.update { it.copy(reconcileSearchQuery = query) }
    }

    fun onReconcileDefaultTemplateChanged(templateId: String?) {
        localControls.update { it.copy(reconcileDefaultTemplateId = templateId) }
    }

    fun toggleCandidateSelection(candidate: ScannedChatCandidate) {
        if (candidate.matchStatus == MatchStatus.AMBIGUOUS) return
        val cKey = candidateKey(candidate)
        val currentUi = _uiState.value.reconcileState
        val uiItem = currentUi.allCandidates.firstOrNull { it.key == cKey } ?: return
        if (!uiItem.selectable) return

        val snapKey = currentUi.snapshotKey ?: return
        selectionTracker.update { tracker ->
            val baseKeys = if (tracker.initializedKey == snapKey) {
                tracker.selectedKeys
            } else {
                currentUi.allCandidates.filter { it.selected }.map { it.key }.toSet()
            }
            val nextKeys = if (cKey in baseKeys) baseKeys - cKey else baseKeys + cKey
            tracker.copy(initializedKey = snapKey, selectedKeys = nextKeys)
        }
    }

    fun selectAllImportableSparks() {
        val currentUi = _uiState.value.reconcileState
        val snapKey = currentUi.snapshotKey ?: return
        val pickKeys = currentUi.allCandidates
            .filter {
                it.selectable &&
                    it.hasOfficialSpark &&
                    it.candidate.matchStatus == MatchStatus.BOUND
            }
            .map { it.key }
            .toSet()
        selectionTracker.update { tracker ->
            tracker.copy(initializedKey = snapKey, selectedKeys = pickKeys)
        }
    }

    fun clearCandidateSelection() {
        val currentUi = _uiState.value.reconcileState
        val snapKey = currentUi.snapshotKey ?: return
        selectionTracker.update { tracker ->
            tracker.copy(initializedKey = snapKey, selectedKeys = emptySet())
        }
    }

    fun importSelectedCandidates() {
        val reconcile = _uiState.value.reconcileState
        if (reconcile.importing) return
        val selectedItems = reconcile.allCandidates.filter { it.selected && it.selectable }
        if (selectedItems.isEmpty()) return

        localControls.update { it.copy(importing = true, reconcileErrorMessage = null) }
        val defaultTemplateId = reconcile.defaultTemplateId
        val snapKey = reconcile.snapshotKey

        scope.launch {
            var createdCount = 0
            var revivedCount = 0
            var refreshedCount = 0
            var failedCount = 0
            val newlyImportedKeys = mutableSetOf<String>()
            var lastError: String? = null

            try {
                withContext(ioDispatcher) {
                    for (item in selectedItems) {
                        val candidate = item.candidate
                        val cleanName = candidate.displayName.trim()
                        if (cleanName.isEmpty() || candidate.matchStatus == MatchStatus.AMBIGUOUS) {
                            continue
                        }
                        val nowMs = nowInstant().toEpochMilli()
                        val isBound = candidate.matchStatus == MatchStatus.BOUND
                        val pUserId = if (isBound) candidate.platformUserId?.trim()?.takeIf { it.isNotEmpty() } else null
                        val pHandle = if (isBound) candidate.platformHandle?.trim()?.takeIf { it.isNotEmpty() } else null
                        val pConvId = if (isBound) candidate.platformConversationId?.trim()?.takeIf { it.isNotEmpty() } else null
                        val convType = candidate.conversationType.trim().takeIf { it.isNotEmpty() }
                            ?: if (pConvId != null) "group" else "friend"

                        try {
                            val byUserId = pUserId?.let {
                                repository.findByPlatformUserIdIncludingDeleted(accountId, it)
                            }
                            val byConvId = if (byUserId == null && pConvId != null) {
                                repository.findByPlatformConversationIdIncludingDeleted(accountId, pConvId)
                            } else {
                                null
                            }
                            val byNickname = if (byUserId == null && byConvId == null) {
                                repository.findByNicknameIncludingDeleted(accountId, cleanName)
                            } else {
                                null
                            }
                            val existing = byUserId ?: byConvId ?: byNickname

                            if (existing != null) {
                                val conflictReason = checkIdentityConflict(existing, pUserId, pConvId)
                                if (conflictReason != null) {
                                    failedCount++
                                    lastError = conflictReason
                                    continue
                                }

                                val nicknameConflict = if (existing.nicknameSnapshot != cleanName) {
                                    repository.findByNicknameIncludingDeleted(accountId, cleanName)
                                } else {
                                    null
                                }
                                val safeNickname = if (nicknameConflict != null && nicknameConflict.id != existing.id) {
                                    existing.nicknameSnapshot
                                } else {
                                    cleanName
                                }

                                val nextUid = pUserId ?: existing.platformUserId
                                val nextHandle = pHandle ?: existing.platformHandle
                                val nextCid = pConvId ?: existing.platformConversationId
                                val nextType = existing.conversationType ?: convType

                                if (existing.deletedAt != null) {
                                    repository.reviveTarget(
                                        id = existing.id,
                                        nicknameSnapshot = safeNickname,
                                        platformUserId = nextUid,
                                        platformHandle = nextHandle,
                                        platformConversationId = nextCid,
                                        conversationType = nextType,
                                        enabled = true,
                                        updatedAt = nowMs,
                                    )
                                    if (defaultTemplateId != null && existing.templateId == null) {
                                        repository.updateTarget(
                                            existing.copy(
                                                deletedAt = null,
                                                enabled = true,
                                                nicknameSnapshot = safeNickname,
                                                platformUserId = nextUid,
                                                platformHandle = nextHandle,
                                                platformConversationId = nextCid,
                                                conversationType = nextType,
                                                templateId = defaultTemplateId,
                                                updatedAt = nowMs,
                                            ),
                                        )
                                    }
                                    if (hasSparkPayload(candidate)) {
                                        repository.updateOfficialSparkSnapshot(
                                            id = existing.id,
                                            sparkDays = candidate.sparkDays,
                                            sparkKind = candidate.sparkKind,
                                            sparkLabel = candidate.sparkLabel,
                                            scannedAt = nowMs,
                                        )
                                    }
                                    revivedCount++
                                } else {
                                    if (safeNickname != existing.nicknameSnapshot ||
                                        nextUid != existing.platformUserId ||
                                        nextHandle != existing.platformHandle ||
                                        nextCid != existing.platformConversationId ||
                                        nextType != existing.conversationType
                                    ) {
                                        // 仅刷新身份信息，严禁覆写本地 streakDays
                                        repository.updateTarget(
                                            existing.copy(
                                                nicknameSnapshot = safeNickname,
                                                platformUserId = nextUid,
                                                platformHandle = nextHandle,
                                                platformConversationId = nextCid,
                                                conversationType = nextType,
                                                updatedAt = nowMs,
                                            ),
                                        )
                                    }
                                    if (hasSparkPayload(candidate)) {
                                        repository.updateOfficialSparkSnapshot(
                                            id = existing.id,
                                            sparkDays = candidate.sparkDays,
                                            sparkKind = candidate.sparkKind,
                                            sparkLabel = candidate.sparkLabel,
                                            scannedAt = nowMs,
                                        )
                                    }
                                    refreshedCount++
                                }
                            } else {
                                val newId = UUID.randomUUID().toString()
                                repository.insertTarget(
                                    ContractTargetEntity(
                                        id = newId,
                                        accountId = accountId,
                                        nicknameSnapshot = cleanName,
                                        platformUserId = pUserId,
                                        platformHandle = pHandle,
                                        platformConversationId = pConvId,
                                        conversationType = convType,
                                        templateId = defaultTemplateId,
                                        enabled = true,
                                        streakDays = 0,
                                        createdAt = nowMs,
                                        updatedAt = nowMs,
                                    ),
                                )
                                if (hasSparkPayload(candidate)) {
                                    repository.updateOfficialSparkSnapshot(
                                        id = newId,
                                        sparkDays = candidate.sparkDays,
                                        sparkKind = candidate.sparkKind,
                                        sparkLabel = candidate.sparkLabel,
                                        scannedAt = nowMs,
                                    )
                                }
                                createdCount++
                            }
                            newlyImportedKeys.add(item.key)
                        } catch (ex: Throwable) {
                            failedCount++
                            lastError = "导入出现冲突（${ex.javaClass.simpleName}）"
                        }
                    }
                    scheduleGateway.rescheduleAll(nowInstant())
                }
            } finally {
                if (snapKey != null && newlyImportedKeys.isNotEmpty()) {
                    selectionTracker.update { tracker ->
                        if (tracker.initializedKey == snapKey) {
                            tracker.copy(
                                selectedKeys = tracker.selectedKeys - newlyImportedKeys,
                                importedKeys = tracker.importedKeys + newlyImportedKeys,
                            )
                        } else {
                            tracker
                        }
                    }
                }
                val summary = buildString {
                    val parts = mutableListOf<String>()
                    if (createdCount > 0) parts += "新增 $createdCount 位"
                    if (revivedCount > 0) parts += "恢复 $revivedCount 位"
                    if (refreshedCount > 0) parts += "刷新 $refreshedCount 位"
                    if (parts.isEmpty()) {
                        append("未导入新好友")
                    } else {
                        append("导入完成：")
                        append(parts.joinToString("，"))
                    }
                    if (failedCount > 0) {
                        append("（$failedCount 条因身份冲突跳过）")
                    }
                }
                localControls.update {
                    it.copy(
                        importing = false,
                        isReconcileSheetOpen = failedCount > 0,
                        reconcileErrorMessage = if (failedCount > 0) lastError else null,
                        feedbackMessage = summary,
                    )
                }
            }
        }
    }

    fun syncExistingOfficialSparks() {
        val reconcile = _uiState.value.reconcileState
        if (reconcile.importing) return
        val existingCandidates = reconcile.allCandidates.filter { it.effectivelyAlreadyImported }
        if (existingCandidates.isEmpty()) return

        localControls.update { it.copy(importing = true, reconcileErrorMessage = null) }
        scope.launch {
            var syncedCount = 0
            try {
                withContext(ioDispatcher) {
                    val nowMs = nowInstant().toEpochMilli()
                    for (item in existingCandidates) {
                        val candidate = item.candidate
                        if (candidate.matchStatus == MatchStatus.AMBIGUOUS) continue
                        val isBound = candidate.matchStatus == MatchStatus.BOUND
                        val pUserId = if (isBound) candidate.platformUserId?.trim()?.takeIf { it.isNotEmpty() } else null
                        val pHandle = if (isBound) candidate.platformHandle?.trim()?.takeIf { it.isNotEmpty() } else null
                        val pConvId = if (isBound) candidate.platformConversationId?.trim()?.takeIf { it.isNotEmpty() } else null

                        val target = (pUserId?.let {
                            repository.findByPlatformUserIdIncludingDeleted(accountId, it)
                        } ?: pConvId?.let {
                            repository.findByPlatformConversationIdIncludingDeleted(accountId, it)
                        } ?: repository.findByNicknameIncludingDeleted(accountId, candidate.displayName.trim()))
                            ?.takeIf { it.deletedAt == null }
                            ?: continue

                        if (checkIdentityConflict(target, pUserId, pConvId) != null) continue

                        if ((pUserId != null && target.platformUserId == null) ||
                            (pConvId != null && target.platformConversationId == null) ||
                            (pHandle != null && target.platformHandle != pHandle)
                        ) {
                            repository.updateTarget(
                                target.copy(
                                    platformUserId = target.platformUserId ?: pUserId,
                                    platformHandle = pHandle ?: target.platformHandle,
                                    platformConversationId = target.platformConversationId ?: pConvId,
                                    updatedAt = nowMs,
                                ),
                            )
                        }
                        if (hasSparkPayload(candidate)) {
                            repository.updateOfficialSparkSnapshot(
                                id = target.id,
                                sparkDays = candidate.sparkDays,
                                sparkKind = candidate.sparkKind,
                                sparkLabel = candidate.sparkLabel,
                                scannedAt = nowMs,
                            )
                        } else {
                            repository.updateOfficialSparkSnapshot(
                                id = target.id,
                                sparkDays = null,
                                sparkKind = null,
                                sparkLabel = "无火花",
                                scannedAt = nowMs,
                            )
                        }
                        syncedCount++
                    }
                    scheduleGateway.rescheduleAll(nowInstant())
                }
            } finally {
                localControls.update {
                    it.copy(
                        importing = false,
                        feedbackMessage = "已同步 $syncedCount 位现有好友的官方火花快照（本机连续天数不变）",
                    )
                }
            }
        }
    }

    fun openCreateTargetDialog() {
        val defaultTemplateId = _uiState.value.templates.firstOrNull { it.enabled }?.id
        localControls.update {
            it.copy(
                editorState = TargetEditorState(
                    isNew = true,
                    editingTargetId = null,
                    nickname = "",
                    conversationType = "friend",
                    typeLockedReason = null,
                    templateId = defaultTemplateId,
                    sendTimeHhmm = "",
                    enabled = true,
                ),
            )
        }
    }

    fun openEditTargetDialog(target: ContractTargetEntity) {
        val lockedReason = when {
            !target.platformUserId.isNullOrBlank() -> "已绑定抖音用户 ID，不可切换为群聊"
            !target.platformConversationId.isNullOrBlank() -> "已绑定群会话 ID，不可切换为好友"
            else -> null
        }
        localControls.update {
            it.copy(
                editorState = TargetEditorState(
                    isNew = false,
                    editingTargetId = target.id,
                    nickname = target.nicknameSnapshot,
                    conversationType = target.conversationType ?: "friend",
                    typeLockedReason = lockedReason,
                    templateId = target.templateId,
                    sendTimeHhmm = target.sendTimeHhmm.orEmpty(),
                    enabled = target.enabled,
                ),
            )
        }
    }

    fun dismissTargetEditor() {
        localControls.update { it.copy(editorState = null) }
    }

    fun updateEditorNickname(nickname: String) {
        localControls.update { state ->
            state.copy(
                editorState = state.editorState?.copy(nickname = nickname, errorMessage = null),
            )
        }
    }

    fun updateEditorType(conversationType: String) {
        localControls.update { state ->
            val ed = state.editorState ?: return@update state
            if (ed.typeLockedReason != null) return@update state
            state.copy(editorState = ed.copy(conversationType = conversationType, errorMessage = null))
        }
    }

    fun updateEditorTemplate(templateId: String?) {
        localControls.update { state ->
            state.copy(editorState = state.editorState?.copy(templateId = templateId, errorMessage = null))
        }
    }

    fun updateEditorSendTime(sendTimeHhmm: String) {
        localControls.update { state ->
            state.copy(
                editorState = state.editorState?.copy(sendTimeHhmm = sendTimeHhmm, errorMessage = null),
            )
        }
    }

    fun updateEditorEnabled(enabled: Boolean) {
        localControls.update { state ->
            state.copy(editorState = state.editorState?.copy(enabled = enabled, errorMessage = null))
        }
    }

    fun saveTargetEditor() {
        val editor = localControls.value.editorState ?: return
        val cleanName = editor.nickname.trim()
        if (cleanName.isEmpty()) {
            setEditorError("请输入好友或群聊显示名称")
            return
        }
        val cleanTime = editor.sendTimeHhmm.trim().takeIf { it.isNotEmpty() }
        if (cleanTime != null && !isValidHhmm(cleanTime)) {
            setEditorError("固定发送时刻须为 HH:mm（00:00 – 23:59）")
            return
        }

        localControls.update { it.copy(isBusy = true) }
        scope.launch {
            try {
                val nowMs = nowInstant().toEpochMilli()
                val resultMsg = withContext(ioDispatcher) {
                    val nicknameOwner = repository.findByNicknameIncludingDeleted(accountId, cleanName)
                    if (editor.isNew) {
                        if (nicknameOwner != null) {
                            if (nicknameOwner.deletedAt == null) {
                                return@withContext SaveResult.Error("该昵称已在当前好友列表中")
                            }
                            // 命中软删除记录：自动复活并保留 streakDays / 已有身份
                            val nextType = if (nicknameOwner.platformUserId == null &&
                                nicknameOwner.platformConversationId == null
                            ) {
                                editor.conversationType
                            } else {
                                nicknameOwner.conversationType
                            }
                            repository.reviveTarget(
                                id = nicknameOwner.id,
                                nicknameSnapshot = cleanName,
                                platformUserId = nicknameOwner.platformUserId,
                                platformHandle = nicknameOwner.platformHandle,
                                platformConversationId = nicknameOwner.platformConversationId,
                                conversationType = nextType,
                                enabled = editor.enabled,
                                updatedAt = nowMs,
                            )
                            repository.updateTarget(
                                nicknameOwner.copy(
                                    deletedAt = null,
                                    enabled = editor.enabled,
                                    nicknameSnapshot = cleanName,
                                    conversationType = nextType,
                                    templateId = editor.templateId,
                                    sendTimeHhmm = cleanTime,
                                    updatedAt = nowMs,
                                ),
                            )
                            scheduleGateway.rescheduleAll(nowInstant())
                            return@withContext SaveResult.Success("已恢复历史删除记录并更新配置")
                        }

                        repository.insertTarget(
                            ContractTargetEntity(
                                accountId = accountId,
                                nicknameSnapshot = cleanName,
                                conversationType = editor.conversationType,
                                templateId = editor.templateId,
                                enabled = editor.enabled,
                                sendTimeHhmm = cleanTime,
                                streakDays = 0,
                                createdAt = nowMs,
                                updatedAt = nowMs,
                            ),
                        )
                        scheduleGateway.rescheduleAll(nowInstant())
                        SaveResult.Success("已新增续火对象")
                    } else {
                        val existingId = editor.editingTargetId
                            ?: return@withContext SaveResult.Error("目标 ID 丢失")
                        val existing = repository.listActiveTargets(accountId)
                            .firstOrNull { it.id == existingId }
                            ?: return@withContext SaveResult.Error("目标不存在或已被删除")
                        if (nicknameOwner != null && nicknameOwner.id != existing.id) {
                            return@withContext if (nicknameOwner.deletedAt == null) {
                                SaveResult.Error("该昵称已被其他好友占用")
                            } else {
                                SaveResult.Error("该昵称存在已删除历史记录，请直接新增同名以复活恢复")
                            }
                        }
                        val nextType = if (existing.platformUserId == null &&
                            existing.platformConversationId == null
                        ) {
                            editor.conversationType
                        } else {
                            existing.conversationType
                        }
                        repository.updateTarget(
                            existing.copy(
                                nicknameSnapshot = cleanName,
                                conversationType = nextType,
                                templateId = editor.templateId,
                                sendTimeHhmm = cleanTime,
                                enabled = editor.enabled,
                                updatedAt = nowMs,
                            ),
                        )
                        scheduleGateway.rescheduleAll(nowInstant())
                        SaveResult.Success("已更新好友配置")
                    }
                }

                when (resultMsg) {
                    is SaveResult.Error -> localControls.update { state ->
                        state.copy(
                            isBusy = false,
                            editorState = state.editorState?.copy(errorMessage = resultMsg.message),
                        )
                    }
                    is SaveResult.Success -> localControls.update {
                        it.copy(
                            isBusy = false,
                            editorState = null,
                            feedbackMessage = resultMsg.message,
                        )
                    }
                }
            } catch (ex: Throwable) {
                localControls.update { state ->
                    state.copy(
                        isBusy = false,
                        editorState = state.editorState?.copy(
                            errorMessage = "保存失败（${ex.javaClass.simpleName}）",
                        ),
                    )
                }
            }
        }
    }

    private sealed interface SaveResult {
        data class Success(val message: String) : SaveResult
        data class Error(val message: String) : SaveResult
    }

    private fun setEditorError(message: String) {
        localControls.update { state ->
            state.copy(editorState = state.editorState?.copy(errorMessage = message))
        }
    }

    fun toggleTargetEnabled(target: ContractTargetEntity, enabled: Boolean) {
        scope.launch {
            withContext(ioDispatcher) {
                val nowMs = nowInstant().toEpochMilli()
                repository.updateTarget(target.copy(enabled = enabled, updatedAt = nowMs))
                scheduleGateway.rescheduleAll(nowInstant())
            }
        }
    }

    fun requestDeleteTarget(target: ContractTargetEntity) {
        localControls.update { it.copy(deleteConfirmTarget = target) }
    }

    fun dismissDeleteConfirm() {
        localControls.update { it.copy(deleteConfirmTarget = null) }
    }

    fun confirmDeleteTarget() {
        val target = localControls.value.deleteConfirmTarget ?: return
        localControls.update { it.copy(deleteConfirmTarget = null, isBusy = true) }
        scope.launch {
            try {
                withContext(ioDispatcher) {
                    repository.softDeleteTarget(target.id, nowInstant().toEpochMilli())
                    scheduleGateway.rescheduleAll(nowInstant())
                }
                localControls.update {
                    it.copy(
                        isBusy = false,
                        feedbackMessage = "已移出续火名单（保留发送审计，再次扫码可复活）",
                    )
                }
            } finally {
                localControls.update { it.copy(isBusy = false) }
            }
        }
    }

    fun requestIdentityRepair(target: ContractTargetEntity) {
        val ok = executionGateway.enqueueSingleIdentityRepair(target.id, accountId)
        localControls.update {
            it.copy(
                feedbackMessage = if (ok) {
                    "已加入单独补采身份队列，正在离屏读取稳定 ID…"
                } else {
                    "无法启动后台补采服务，请检查应用通知与后台运行权限"
                },
            )
        }
    }

    fun requestSingleSend(target: ContractTargetEntity) {
        scope.launch {
            val (decision, boundTemplate) = withContext(ioDispatcher) {
                val account = repository.getAccount(accountId)
                val todayStr = computeBusinessDate(account?.timezone)
                val todayJobs = repository.listJobsForTargetDate(accountId, target.id, todayStr)
                val d = SendGuard.checkManualSend(todayJobs)
                val tpl = target.templateId?.let { id ->
                    _uiState.value.templates.firstOrNull { it.id == id }
                }
                d to tpl
            }
            val missingImg = boundTemplate != null &&
                boundTemplate.id in _uiState.value.missingImageTemplateIds

            val nextDialog = when (decision) {
                ManualSendDecision.BLOCKED_UNKNOWN_OR_INFLIGHT ->
                    SingleSendDialogState.Blocked(
                        targetId = target.id,
                        targetName = target.nicknameSnapshot,
                        message = "请先在『今日』首页核对未知发送结果或等待进行中任务结束",
                    )

                ManualSendDecision.CONFIRM_ALREADY_SUCCEEDED ->
                    SingleSendDialogState.ConfirmAlreadySucceeded(
                        target = target,
                        templateName = boundTemplate?.name,
                        templateType = boundTemplate?.type,
                        isTemplateMissingImage = missingImg,
                    )

                ManualSendDecision.ALLOWED ->
                    SingleSendDialogState.ConfirmSend(
                        target = target,
                        templateName = boundTemplate?.name,
                        templateType = boundTemplate?.type,
                        isTemplateMissingImage = missingImg,
                    )
            }
            localControls.update { it.copy(singleSendDialog = nextDialog) }
        }
    }

    fun updateSingleSendOverrideText(text: String) {
        localControls.update { state ->
            val updated = when (val dlg = state.singleSendDialog) {
                is SingleSendDialogState.ConfirmAlreadySucceeded -> dlg.copy(overrideText = text)
                is SingleSendDialogState.ConfirmSend -> dlg.copy(overrideText = text)
                else -> dlg
            }
            state.copy(singleSendDialog = updated)
        }
    }

    fun dismissSingleSendDialog() {
        localControls.update { it.copy(singleSendDialog = SingleSendDialogState.None) }
    }

    fun confirmSingleSend() {
        val dialog = localControls.value.singleSendDialog
        val (target, rawOverrideText) = when (dialog) {
            is SingleSendDialogState.ConfirmAlreadySucceeded -> dialog.target to dialog.overrideText
            is SingleSendDialogState.ConfirmSend -> dialog.target to dialog.overrideText
            is SingleSendDialogState.Blocked,
            SingleSendDialogState.None,
            -> return
        }

        scope.launch {
            // 二次硬校验 SendGuard：即使在弹窗期间产生了未决任务，也绝不允许绕过发送
            val rechecked = withContext(ioDispatcher) {
                val account = repository.getAccount(accountId)
                val todayStr = computeBusinessDate(account?.timezone)
                val todayJobs = repository.listJobsForTargetDate(accountId, target.id, todayStr)
                SendGuard.checkManualSend(todayJobs)
            }

            if (rechecked == ManualSendDecision.BLOCKED_UNKNOWN_OR_INFLIGHT) {
                localControls.update {
                    it.copy(
                        singleSendDialog = SingleSendDialogState.Blocked(
                            targetId = target.id,
                            targetName = target.nicknameSnapshot,
                            message = "请先在『今日』首页核对未知发送结果或等待进行中任务结束",
                        ),
                    )
                }
                return@launch
            }

            val overrideText = rawOverrideText.trim().takeIf { it.isNotEmpty() }
            if (overrideText == null && target.templateId == null) {
                localControls.update {
                    it.copy(feedbackMessage = "该好友未绑定模板，请输入临时测试文本后再发送")
                }
                return@launch
            }

            val started = executionGateway.enqueueSingleSend(
                targetId = target.id,
                overrideText = overrideText,
                accountId = accountId,
            )
            localControls.update {
                it.copy(
                    singleSendDialog = SingleSendDialogState.None,
                    feedbackMessage = if (started) {
                        "已发起单发测试，请稍候查看发送结果"
                    } else {
                        "无法启动前台发送服务，请检查通知与后台运行权限"
                    },
                )
            }
        }
    }

    fun clearFeedbackMessage() {
        localControls.update { it.copy(feedbackMessage = null) }
    }

    private fun computeBusinessDate(timezone: String?): String {
        val zoneId = DailySchedulePlanner.resolveZoneId(timezone)
        return ZonedDateTime.ofInstant(nowInstant(), zoneId).toLocalDate().toString()
    }

    private fun checkIdentityConflict(
        existing: ContractTargetEntity,
        pUserId: String?,
        pConvId: String?,
    ): String? {
        if (pUserId != null && existing.platformUserId != null && existing.platformUserId != pUserId) {
            return "稳定用户身份与已有记录不一致，已跳过导入"
        }
        if (pConvId != null && existing.platformConversationId != null && existing.platformConversationId != pConvId) {
            return "稳定群会话身份与已有记录不一致，已跳过导入"
        }
        if (pUserId != null && existing.platformConversationId != null) {
            return "用户身份与已有群会话记录冲突，已跳过导入"
        }
        if (pConvId != null && existing.platformUserId != null) {
            return "群会话身份与已有好友记录冲突，已跳过导入"
        }
        return null
    }

    private fun resolveTodayStatusLabel(
        targetJobs: List<SendJobEntity>,
        enabled: Boolean,
    ): String? {
        val latest = targetJobs.maxByOrNull { it.updatedAt }
        return when {
            SendGuard.hasBlockingUnknownOrInflight(targetJobs) -> {
                if (targetJobs.any { it.status == JobStatus.UNKNOWN_REQUIRES_REVIEW }) {
                    "今日待核对"
                } else {
                    "今日发送中"
                }
            }
            latest?.status == JobStatus.SUCCEEDED -> "今日已发送"
            latest?.status == JobStatus.CANCELLED -> "今日已取消"
            latest?.status == JobStatus.FAILED_TERMINAL -> "今日终态失败"
            latest?.status == JobStatus.FAILED_RETRYABLE -> "今日待重试"
            !enabled -> "已暂停"
            else -> null
        }
    }

    companion object {
        private val STRICT_HHMM_REGEX = Regex("""^\d{2}:\d{2}$""")

        fun isValidHhmm(raw: String): Boolean {
            if (!STRICT_HHMM_REGEX.matches(raw)) return false
            val hour = raw.substring(0, 2).toIntOrNull() ?: return false
            val minute = raw.substring(3, 5).toIntOrNull() ?: return false
            return hour in 0..23 && minute in 0..59
        }

        fun hasSparkPayload(candidate: ScannedChatCandidate): Boolean =
            candidate.sparkDays != null ||
                !candidate.sparkKind.isNullOrBlank() ||
                !candidate.sparkLabel.isNullOrBlank()

        internal fun candidateKey(candidate: ScannedChatCandidate): String =
            buildString {
                append(candidate.displayName)
                append('\u0000')
                append(candidate.conversationType)
                append('\u0000')
                append(candidate.platformUserId.orEmpty())
                append('\u0000')
                append(candidate.platformConversationId.orEmpty())
            }

        internal fun snapshotIdentityKey(snapshot: ScanReconciliationSnapshot): String =
            "${snapshot.scanRunId}:${snapshot.scannedAtEpochMs}"

        fun factory(
            context: Context,
            database: FlameDatabase,
            scheduleCoordinator: ScheduleCoordinator,
            accountId: String = DEFAULT_ACCOUNT_ID,
            imageStore: TemplateImageStorage = AndroidTemplateImageStore(context),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TargetsViewModel(
                    repository = RoomTargetsRepository(database),
                    executionGateway = AndroidTargetExecutionGateway(context),
                    scheduleGateway = AndroidTargetScheduleGateway(scheduleCoordinator),
                    accountId = accountId,
                    imageStore = imageStore,
                ) as T
            }
        }
    }
}

private fun ContractTargetEntity.hasBoundIdentity(): Boolean =
    when (conversationType) {
        "group" -> !platformConversationId.isNullOrBlank()
        else -> !platformUserId.isNullOrBlank() || !platformConversationId.isNullOrBlank()
    }
