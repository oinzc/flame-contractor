package io.github.oinzc.flamecontractor.ui.targets

import io.github.oinzc.flamecontractor.core.identity.MatchStatus
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunStatus
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.execution.ExecutionLiveState
import io.github.oinzc.flamecontractor.execution.ScanReconciliationCodec
import io.github.oinzc.flamecontractor.execution.ScanReconciliationSnapshot
import io.github.oinzc.flamecontractor.execution.ScannedChatCandidate
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanReconcileViewModelTest {

    private class FakeTargetsRepository : TargetsRepository {
        val accountState = MutableStateFlow<AccountEntity?>(
            AccountEntity(
                id = DEFAULT_ACCOUNT_ID,
                status = AccountStatus.HEALTHY,
                timezone = "Asia/Shanghai",
            ),
        )
        val targetsState = MutableStateFlow<List<ContractTargetEntity>>(emptyList())
        val templatesState = MutableStateFlow<List<MessageTemplateEntity>>(
            listOf(
                MessageTemplateEntity(
                    id = "tpl-text",
                    name = "每日续火",
                    type = TemplateType.TEXT,
                    content = "打卡",
                ),
            ),
        )
        val jobsState = MutableStateFlow<List<SendJobEntity>>(emptyList())
        val scanRunsState = MutableStateFlow<List<ScanRunEntity>>(emptyList())

        var sparkSnapshotUpdateCalls = 0
            private set
        var reviveCalls = 0
            private set

        override fun observeAccount(accountId: String): Flow<AccountEntity?> = accountState

        override fun observeActiveTargets(accountId: String): Flow<List<ContractTargetEntity>> =
            targetsState.map { list ->
                list.filter { it.accountId == accountId && it.deletedAt == null }
                    .sortedBy { it.createdAt }
            }

        override fun observeTemplates(accountId: String): Flow<List<MessageTemplateEntity>> =
            templatesState.map { list -> list.filter { it.accountId == accountId } }

        override fun observeJobsForDate(
            accountId: String,
            businessDate: String,
        ): Flow<List<SendJobEntity>> =
            jobsState.map { list ->
                list.filter { it.accountId == accountId && it.businessDate == businessDate }
            }

        override fun observeLatestScanRun(accountId: String): Flow<ScanRunEntity?> =
            scanRunsState.map { list ->
                list.filter { it.accountId == accountId }.maxByOrNull { it.startedAt }
            }

        override suspend fun getAccount(accountId: String): AccountEntity? = accountState.value

        override suspend fun listActiveTargets(accountId: String): List<ContractTargetEntity> =
            targetsState.value.filter { it.accountId == accountId && it.deletedAt == null }

        override suspend fun listJobsForTargetDate(
            accountId: String,
            targetId: String,
            businessDate: String,
        ): List<SendJobEntity> =
            jobsState.value.filter {
                it.accountId == accountId && it.targetId == targetId && it.businessDate == businessDate
            }

        override suspend fun findByNicknameIncludingDeleted(
            accountId: String,
            nickname: String,
        ): ContractTargetEntity? =
            targetsState.value.firstOrNull {
                it.accountId == accountId && it.nicknameSnapshot == nickname
            }

        override suspend fun findByPlatformUserIdIncludingDeleted(
            accountId: String,
            platformUserId: String,
        ): ContractTargetEntity? =
            targetsState.value.firstOrNull {
                it.accountId == accountId && it.platformUserId == platformUserId
            }

        override suspend fun findByPlatformConversationIdIncludingDeleted(
            accountId: String,
            platformConversationId: String,
        ): ContractTargetEntity? =
            targetsState.value.firstOrNull {
                it.accountId == accountId && it.platformConversationId == platformConversationId
            }

        override suspend fun insertTarget(target: ContractTargetEntity) {
            targetsState.value = targetsState.value + target
        }

        override suspend fun updateTarget(target: ContractTargetEntity) {
            targetsState.value = targetsState.value.map {
                if (it.id == target.id) target else it
            }
        }

        override suspend fun softDeleteTarget(id: String, deletedAt: Long) {
            targetsState.value = targetsState.value.map {
                if (it.id == id) it.copy(deletedAt = deletedAt, enabled = false, updatedAt = deletedAt) else it
            }
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
            reviveCalls++
            targetsState.value = targetsState.value.map {
                if (it.id == id) {
                    it.copy(
                        deletedAt = null,
                        enabled = enabled,
                        nicknameSnapshot = nicknameSnapshot,
                        platformUserId = platformUserId,
                        platformHandle = platformHandle,
                        platformConversationId = platformConversationId,
                        conversationType = conversationType,
                        updatedAt = updatedAt,
                    )
                } else {
                    it
                }
            }
        }

        override suspend fun updateOfficialSparkSnapshot(
            id: String,
            sparkDays: Int?,
            sparkKind: String?,
            sparkLabel: String?,
            scannedAt: Long,
        ) {
            sparkSnapshotUpdateCalls++
            targetsState.value = targetsState.value.map {
                if (it.id == id) {
                    it.copy(
                        sparkDays = sparkDays,
                        sparkKind = sparkKind,
                        sparkLabel = sparkLabel,
                        sparkScannedAt = scannedAt,
                        updatedAt = scannedAt,
                    )
                } else {
                    it
                }
            }
        }

        override suspend fun getLatestScanRun(accountId: String): ScanRunEntity? =
            scanRunsState.value.filter { it.accountId == accountId }.maxByOrNull { it.startedAt }
    }

    private class FakeExecutionGateway : TargetExecutionGateway {
        val mutableLiveState = MutableStateFlow(ExecutionLiveState())
        override val liveState: StateFlow<ExecutionLiveState> = mutableLiveState.asStateFlow()

        val singleSendRequests = mutableListOf<Pair<String, String?>>()
        var manualScanCount = 0
        val identityRepairRequests = mutableListOf<String>()

        override fun enqueueManualScan(accountId: String): Boolean {
            manualScanCount++
            return true
        }

        override fun enqueueSingleSend(
            targetId: String,
            overrideText: String?,
            accountId: String,
        ): Boolean {
            singleSendRequests.add(targetId to overrideText)
            return true
        }

        override fun enqueueSingleIdentityRepair(targetId: String, accountId: String): Boolean {
            identityRepairRequests.add(targetId)
            return true
        }
    }

    private class FakeScheduleGateway : TargetScheduleGateway {
        var rescheduleCount = 0
            private set

        override suspend fun rescheduleAll(now: Instant): Instant? {
            rescheduleCount++
            return null
        }
    }

    private fun candidate(
        name: String,
        type: String = "friend",
        sparkDays: Int? = null,
        sparkKind: String? = null,
        sparkLabel: String? = null,
        matchStatus: MatchStatus = MatchStatus.BOUND,
        userId: String? = "sec_$name",
        convId: String? = null,
        alreadyImported: Boolean = false,
        matchDetail: String? = null,
    ): ScannedChatCandidate = ScannedChatCandidate(
        displayName = name,
        conversationType = type,
        sparkDays = sparkDays,
        sparkKind = sparkKind,
        sparkLabel = sparkLabel,
        matchStatus = matchStatus,
        platformUserId = if (type == "group" || matchStatus != MatchStatus.BOUND) null else userId,
        platformHandle = if (type == "group" || matchStatus != MatchStatus.BOUND) null else "handle_$name",
        platformConversationId = if (type == "group" && matchStatus == MatchStatus.BOUND) (convId ?: "conv_$name") else null,
        matchDetail = matchDetail,
        alreadyImported = alreadyImported,
        existingTargetId = null,
        preselected = false,
    )

    @Test
    fun sortsOfficialSparksBeforeNonSparksAndPreselectsUnimportedValidOnesOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = FakeTargetsRepository()
            val gateway = FakeExecutionGateway()
            val scheduler = FakeScheduleGateway()
            val fixedNow = Instant.parse("2026-11-21T12:00:00Z")

            val vm = TargetsViewModel(
                repository = repo,
                executionGateway = gateway,
                scheduleGateway = scheduler,
                nowInstant = { fixedNow },
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )

            val candidates = listOf(
                candidate(name = "普通好友", sparkDays = null, matchStatus = MatchStatus.BOUND, alreadyImported = false),
                candidate(name = "已导入火花", sparkDays = 100, sparkKind = "normal", sparkLabel = "100天", matchStatus = MatchStatus.BOUND, alreadyImported = true),
                candidate(name = "冲突火花", sparkDays = 50, sparkKind = "normal", sparkLabel = "50天", matchStatus = MatchStatus.AMBIGUOUS, alreadyImported = false, matchDetail = "同名对应多个平台用户"),
                candidate(name = "未观察火花", sparkDays = 30, sparkKind = "normal", sparkLabel = "30天", matchStatus = MatchStatus.NOT_OBSERVED, alreadyImported = false),
                candidate(name = "低火花未导入", sparkDays = 5, sparkKind = "normal", sparkLabel = "5天", matchStatus = MatchStatus.BOUND, alreadyImported = false),
                candidate(name = "高火花未导入", sparkDays = 20, sparkKind = "couple", sparkLabel = "20天", matchStatus = MatchStatus.BOUND, alreadyImported = false),
            )

            gateway.mutableLiveState.value = ExecutionLiveState(
                latestScanSnapshot = ScanReconciliationSnapshot(
                    scanRunId = "scan-1",
                    scannedAtEpochMs = 1_000L,
                    discoveredCount = candidates.size,
                    sparkCount = 5,
                    candidates = candidates,
                ),
            )
            advanceUntilIdle()

            val initialReconcile = vm.uiState.value.reconcileState
            assertEquals(
                listOf("冲突火花", "未观察火花", "高火花未导入", "低火花未导入", "已导入火花", "普通好友"),
                initialReconcile.allCandidates.map { it.candidate.displayName },
            )

            val selectedNamesInitial = initialReconcile.allCandidates
                .filter { it.selected }
                .map { it.candidate.displayName }
                .toSet()
            assertEquals(setOf("高火花未导入", "低火花未导入"), selectedNamesInitial)

            // 用户手动取消「高火花未导入」，并勾选「普通好友」
            val highSpark = initialReconcile.allCandidates.first { it.candidate.displayName == "高火花未导入" }.candidate
            val normalFriend = initialReconcile.allCandidates.first { it.candidate.displayName == "普通好友" }.candidate
            vm.toggleCandidateSelection(highSpark)
            vm.toggleCandidateSelection(normalFriend)
            advanceUntilIdle()

            // 后续同一快照下触发状态刷新（如 LiveState 字段更新或目标列表变化），绝不能覆盖用户手动选择
            gateway.mutableLiveState.value = gateway.mutableLiveState.value.copy(
                lastMessage = "刷新消息",
                lastUpdatedAtEpochMs = 2_000L,
            )
            repo.targetsState.value = listOf(
                ContractTargetEntity(id = "other", nicknameSnapshot = "旁路新增", createdAt = 100L),
            )
            advanceUntilIdle()

            val afterRefreshSelected = vm.uiState.value.reconcileState.allCandidates
                .filter { it.selected }
                .map { it.candidate.displayName }
                .toSet()
            assertEquals(setOf("低火花未导入", "普通好友"), afterRefreshSelected)

            // 一键全选可导入火花对象 / 清空选择
            vm.selectAllImportableSparks()
            advanceUntilIdle()
            assertEquals(
                setOf("高火花未导入", "低火花未导入"),
                vm.uiState.value.reconcileState.allCandidates.filter { it.selected }.map { it.candidate.displayName }.toSet(),
            )

            vm.clearCandidateSelection()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.reconcileState.allCandidates.none { it.selected })
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun disablesSelectionForAmbiguousEntries() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = FakeTargetsRepository()
            val gateway = FakeExecutionGateway()
            val scheduler = FakeScheduleGateway()

            val vm = TargetsViewModel(
                repository = repo,
                executionGateway = gateway,
                scheduleGateway = scheduler,
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )

            val ambiguous = candidate(
                name = "同名好友",
                sparkDays = 88,
                sparkKind = "normal",
                sparkLabel = "88天",
                matchStatus = MatchStatus.AMBIGUOUS,
                alreadyImported = false,
                matchDetail = "同名对应多个平台用户，已阻止自动绑定",
            )
            val valid = candidate(
                name = "唯一好友",
                sparkDays = 10,
                sparkKind = "normal",
                sparkLabel = "10天",
                matchStatus = MatchStatus.BOUND,
                alreadyImported = false,
            )

            gateway.mutableLiveState.value = ExecutionLiveState(
                latestScanSnapshot = ScanReconciliationSnapshot(
                    scanRunId = "scan-ambig",
                    scannedAtEpochMs = 2_000L,
                    discoveredCount = 2,
                    sparkCount = 2,
                    candidates = listOf(ambiguous, valid),
                ),
            )
            advanceUntilIdle()

            val ambigUi = vm.uiState.value.reconcileState.allCandidates.first { it.candidate.displayName == "同名好友" }
            assertTrue(ambigUi.isAmbiguous)
            assertFalse(ambigUi.selectable)
            assertFalse(ambigUi.selected)
            assertTrue(ambigUi.statusNote.contains("同名"))

            // 尝试手动勾选或全选火花均不可选中同名冲突项
            vm.toggleCandidateSelection(ambigUi.candidate)
            vm.selectAllImportableSparks()
            advanceUntilIdle()

            val ambigAfter = vm.uiState.value.reconcileState.allCandidates.first { it.candidate.displayName == "同名好友" }
            assertFalse(ambigAfter.selected)

            vm.importSelectedCandidates()
            advanceUntilIdle()
            assertEquals(listOf("唯一好友"), repo.targetsState.value.map { it.nicknameSnapshot })
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun singleTargetManualSendHardBlocksUnknownTodayAndPromptsOnceIfAlreadySucceeded() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = FakeTargetsRepository()
            val gateway = FakeExecutionGateway()
            val scheduler = FakeScheduleGateway()
            val fixedNow = Instant.parse("2026-11-21T12:00:00Z")
            val todayStr = "2026-11-21"

            val targetUnknown = ContractTargetEntity(
                id = "t-unknown",
                nicknameSnapshot = "待核对好友",
                templateId = "tpl-text",
            )
            val targetSucceeded = ContractTargetEntity(
                id = "t-ok",
                nicknameSnapshot = "今日已发好友",
                templateId = "tpl-text",
            )
            val targetReady = ContractTargetEntity(
                id = "t-ready",
                nicknameSnapshot = "待发好友",
                templateId = "tpl-text",
            )
            repo.targetsState.value = listOf(targetUnknown, targetSucceeded, targetReady)
            repo.jobsState.value = listOf(
                SendJobEntity(
                    id = "job-unk",
                    targetId = targetUnknown.id,
                    businessDate = todayStr,
                    messageSlotId = SendGuard.manualSlotId(1700000000000L),
                    status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                ),
                SendJobEntity(
                    id = "job-ok",
                    targetId = targetSucceeded.id,
                    businessDate = todayStr,
                    messageSlotId = SendGuard.DAILY_SLOT_ID,
                    status = JobStatus.SUCCEEDED,
                ),
            )

            val vm = TargetsViewModel(
                repository = repo,
                executionGateway = gateway,
                scheduleGateway = scheduler,
                nowInstant = { fixedNow },
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )
            advanceUntilIdle()

            // 1. 存在 UNKNOWN_REQUIRES_REVIEW 时必须硬禁止拦截，且绝不允许绕过发送
            vm.requestSingleSend(targetUnknown)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.singleSendDialog is SingleSendDialogState.Blocked)
            vm.confirmSingleSend()
            advanceUntilIdle()
            assertTrue(gateway.singleSendRequests.isEmpty())
            vm.dismissSingleSendDialog()

            // 2. 今日已 SUCCEEDED 时弹出二次确认，确认后才放行单发（支持自定义临时文本）
            vm.requestSingleSend(targetSucceeded)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.singleSendDialog is SingleSendDialogState.ConfirmAlreadySucceeded)
            assertTrue(gateway.singleSendRequests.isEmpty())

            vm.updateSingleSendOverrideText("临时测试问候")
            vm.confirmSingleSend()
            advanceUntilIdle()
            assertEquals(listOf("t-ok" to "临时测试问候"), gateway.singleSendRequests)
            assertEquals(SingleSendDialogState.None, vm.uiState.value.singleSendDialog)

            // 3. 今日无阻断且未成功时直接进入普通发送确认弹窗
            vm.requestSingleSend(targetReady)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.singleSendDialog is SingleSendDialogState.ConfirmSend)
            vm.confirmSingleSend()
            advanceUntilIdle()
            assertEquals(
                listOf("t-ok" to "临时测试问候", "t-ready" to null),
                gateway.singleSendRequests,
            )
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun officialSparkSnapshotImportNeverOverwritesLocalStreakDays() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = FakeTargetsRepository()
            val gateway = FakeExecutionGateway()
            val scheduler = FakeScheduleGateway()
            val fixedNow = Instant.parse("2026-11-21T12:00:00Z")

            val existingActive = ContractTargetEntity(
                id = "target-active",
                nicknameSnapshot = "老好友",
                platformUserId = null,
                conversationType = "friend",
                sparkDays = 3,
                sparkKind = "normal",
                sparkLabel = "3天",
                streakDays = 14,
                createdAt = 100L,
            )
            val softDeleted = ContractTargetEntity(
                id = "target-deleted",
                nicknameSnapshot = "老群聊",
                platformConversationId = "9001",
                conversationType = "group",
                enabled = false,
                deletedAt = 5_000L,
                sparkDays = 8,
                streakDays = 21,
                createdAt = 200L,
            )
            repo.targetsState.value = listOf(existingActive, softDeleted)

            val vm = TargetsViewModel(
                repository = repo,
                executionGateway = gateway,
                scheduleGateway = scheduler,
                nowInstant = { fixedNow },
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )

            val candidates = listOf(
                candidate(
                    name = "老好友",
                    type = "friend",
                    sparkDays = 99,
                    sparkKind = "couple",
                    sparkLabel = "99天合养",
                    matchStatus = MatchStatus.BOUND,
                    userId = "sec_old_friend",
                    alreadyImported = false,
                ),
                candidate(
                    name = "老群聊",
                    type = "group",
                    sparkDays = 45,
                    sparkKind = "normal",
                    sparkLabel = "45天",
                    matchStatus = MatchStatus.BOUND,
                    convId = "9001",
                    alreadyImported = false,
                ),
                candidate(
                    name = "新朋友",
                    type = "friend",
                    sparkDays = 12,
                    sparkKind = "normal",
                    sparkLabel = "12天",
                    matchStatus = MatchStatus.BOUND,
                    userId = "sec_new_friend",
                    alreadyImported = false,
                ),
            )

            gateway.mutableLiveState.value = ExecutionLiveState(
                latestScanSnapshot = ScanReconciliationSnapshot(
                    scanRunId = "scan-import",
                    scannedAtEpochMs = 9_000L,
                    discoveredCount = 3,
                    sparkCount = 3,
                    candidates = candidates,
                ),
            )
            advanceUntilIdle()

            vm.importSelectedCandidates()
            advanceUntilIdle()

            val all = repo.targetsState.value.associateBy { it.nicknameSnapshot }
            val activeAfter = all["老好友"]
            assertNotNull(activeAfter)
            assertEquals("sec_old_friend", activeAfter?.platformUserId)
            assertEquals(99, activeAfter?.sparkDays)
            assertEquals("couple", activeAfter?.sparkKind)
            assertEquals(14, activeAfter?.streakDays)

            val revivedAfter = all["老群聊"]
            assertNotNull(revivedAfter)
            assertNull(revivedAfter?.deletedAt)
            assertTrue(revivedAfter?.enabled == true)
            assertEquals(45, revivedAfter?.sparkDays)
            assertEquals(21, revivedAfter?.streakDays)

            val createdAfter = all["新朋友"]
            assertNotNull(createdAfter)
            assertEquals("sec_new_friend", createdAfter?.platformUserId)
            assertEquals(12, createdAfter?.sparkDays)
            assertEquals(0, createdAfter?.streakDays)

            assertEquals(1, repo.reviveCalls)
            assertEquals(3, repo.sparkSnapshotUpdateCalls)
            assertEquals(1, scheduler.rescheduleCount)

            // 后续对 alreadyImported = true 的快照同步已有好友火花，同样绝不覆写本机 streakDays
            gateway.mutableLiveState.value = ExecutionLiveState(
                latestScanSnapshot = ScanReconciliationSnapshot(
                    scanRunId = "scan-sync-existing",
                    scannedAtEpochMs = 15_000L,
                    discoveredCount = 1,
                    sparkCount = 1,
                    candidates = listOf(
                        candidate(
                            name = "老好友",
                            type = "friend",
                            sparkDays = 105,
                            sparkKind = "couple",
                            sparkLabel = "105天合养",
                            matchStatus = MatchStatus.BOUND,
                            userId = "sec_old_friend",
                            alreadyImported = true,
                        ),
                    ),
                ),
            )
            advanceUntilIdle()
            vm.syncExistingOfficialSparks()
            advanceUntilIdle()

            val afterSync = repo.targetsState.value.first { it.nicknameSnapshot == "老好友" }
            assertEquals(105, afterSync.sparkDays)
            assertEquals(14, afterSync.streakDays)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun loadsSnapshotFromScanRunDaoWhenRuntimeBusEmptyAndRevivesManualAdd() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = FakeTargetsRepository()
            val gateway = FakeExecutionGateway()
            val scheduler = FakeScheduleGateway()
            val fixedNow = Instant.parse("2026-11-21T12:00:00Z")

            val deletedTarget = ContractTargetEntity(
                id = "t-del",
                nicknameSnapshot = "曾删好友",
                streakDays = 7,
                enabled = false,
                deletedAt = 1_000L,
            )
            repo.targetsState.value = listOf(deletedTarget)

            val storedSnapshot = ScanReconciliationSnapshot(
                scanRunId = "scan-db-1",
                scannedAtEpochMs = 8_000L,
                discoveredCount = 1,
                sparkCount = 1,
                candidates = listOf(
                    candidate(name = "恢复候选", sparkDays = 6, sparkKind = "normal", sparkLabel = "6天"),
                ),
            )
            repo.scanRunsState.value = listOf(
                ScanRunEntity(
                    id = "scan-db-1",
                    status = ScanRunStatus.COMPLETED,
                    discoveredCount = 1,
                    sparkCount = 1,
                    reconciliationJson = ScanReconciliationCodec.encode(storedSnapshot),
                    startedAt = 7_500L,
                    finishedAt = 8_000L,
                ),
            )

            val vm = TargetsViewModel(
                repository = repo,
                executionGateway = gateway,
                scheduleGateway = scheduler,
                nowInstant = { fixedNow },
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )
            advanceUntilIdle()

            assertTrue(vm.uiState.value.hasAvailableSnapshot)
            assertEquals("scan-db-1", vm.uiState.value.reconcileState.scanRunId)
            assertEquals(1, vm.uiState.value.reconcileState.allCandidates.size)

            // 手工录入校验 HH:mm 且命中软删除记录时复活并保留 streakDays
            vm.openCreateTargetDialog()
            vm.updateEditorNickname("曾删好友")
            vm.updateEditorSendTime("25:99")
            vm.saveTargetEditor()
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.editorState?.errorMessage)

            vm.updateEditorSendTime("21:30")
            vm.saveTargetEditor()
            advanceUntilIdle()

            assertNull(vm.uiState.value.editorState)
            val revived = repo.targetsState.value.first { it.id == "t-del" }
            assertNull(revived.deletedAt)
            assertTrue(revived.enabled)
            assertEquals("21:30", revived.sendTimeHhmm)
            assertEquals(7, revived.streakDays)
        } finally {
            vmScope.cancel()
        }
    }
}
