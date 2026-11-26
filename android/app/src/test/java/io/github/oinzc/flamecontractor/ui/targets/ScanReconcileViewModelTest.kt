package io.github.oinzc.flamecontractor.ui.targets

import android.net.Uri
import io.github.oinzc.flamecontractor.core.identity.MatchStatus
import io.github.oinzc.flamecontractor.core.storage.OfficialSparkLitStore
import io.github.oinzc.flamecontractor.core.storage.StoredTemplateImage
import io.github.oinzc.flamecontractor.core.storage.TemplateImageStorage
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.DEFAULT_STICKER_SPARK_TEMPLATE_ID
import io.github.oinzc.flamecontractor.data.db.DEFAULT_STICKER_TEMPLATE_ID
import io.github.oinzc.flamecontractor.data.db.DEFAULT_TEXT_TEMPLATE_ID
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
import io.github.oinzc.flamecontractor.ui.components.officialSparkKindFullLabel
import io.github.oinzc.flamecontractor.ui.components.officialSparkKindShortLabel
import java.io.File
import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
        val extraAccounts = MutableStateFlow<Map<String, AccountEntity>>(emptyMap())
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

        override fun observeAccount(accountId: String): Flow<AccountEntity?> =
            combine(accountState, extraAccounts) { primary, extras ->
                extras[accountId] ?: primary?.takeIf { it.id == accountId }
            }

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

        override suspend fun getAccount(accountId: String): AccountEntity? =
            extraAccounts.value[accountId] ?: accountState.value?.takeIf { it.id == accountId }

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
        val singleSendAccountIds = mutableListOf<String>()
        var manualScanCount = 0
        val manualScanAccountIds = mutableListOf<String>()
        val identityRepairRequests = mutableListOf<String>()

        override fun enqueueManualScan(accountId: String): Boolean {
            manualScanCount++
            manualScanAccountIds.add(accountId)
            return true
        }

        override fun enqueueSingleSend(
            targetId: String,
            overrideText: String?,
            accountId: String,
        ): Boolean {
            singleSendRequests.add(targetId to overrideText)
            singleSendAccountIds.add(accountId)
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
        sparkLitToday: Boolean? = null,
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
        sparkLitToday = sparkLitToday,
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
                latestScanSnapshotAccountId = DEFAULT_ACCOUNT_ID,
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
                latestScanSnapshotAccountId = DEFAULT_ACCOUNT_ID,
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
                latestScanSnapshotAccountId = DEFAULT_ACCOUNT_ID,
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
                latestScanSnapshotAccountId = DEFAULT_ACCOUNT_ID,
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

    @Test
    fun syncExistingTargetsWritesNoSparkMarkerWhenObservedWithoutSpark() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = FakeTargetsRepository()
            val gateway = FakeExecutionGateway()
            val scheduler = FakeScheduleGateway()
            val fixedNow = Instant.parse("2026-11-22T12:00:00Z")

            val observedNoSpark = ContractTargetEntity(
                id = "t-active",
                nicknameSnapshot = "老好友",
                platformUserId = "sec_old_friend",
                conversationType = "friend",
                sparkDays = 10,
                sparkKind = "normal",
                sparkLabel = "10天",
                streakDays = 14,
                createdAt = 100L,
            )
            val unseenTarget = ContractTargetEntity(
                id = "t-unseen",
                nicknameSnapshot = "未扫到好友",
                platformUserId = "sec_unseen",
                conversationType = "friend",
                sparkDays = 20,
                sparkKind = "normal",
                sparkLabel = "20天",
                streakDays = 7,
                createdAt = 200L,
            )
            val ambiguousTarget = ContractTargetEntity(
                id = "t-ambig",
                nicknameSnapshot = "重名冲突",
                platformUserId = null,
                conversationType = "friend",
                sparkDays = 30,
                sparkKind = "normal",
                sparkLabel = "30天",
                streakDays = 5,
                createdAt = 300L,
            )
            repo.targetsState.value = listOf(observedNoSpark, unseenTarget, ambiguousTarget)

            val vm = TargetsViewModel(
                repository = repo,
                executionGateway = gateway,
                scheduleGateway = scheduler,
                nowInstant = { fixedNow },
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )

            gateway.mutableLiveState.value = ExecutionLiveState(
                latestScanSnapshotAccountId = DEFAULT_ACCOUNT_ID,
                latestScanSnapshot = ScanReconciliationSnapshot(
                    scanRunId = "scan-no-spark",
                    scannedAtEpochMs = 20_000L,
                    discoveredCount = 2,
                    sparkCount = 0,
                    candidates = listOf(
                        candidate(
                            name = "老好友",
                            type = "friend",
                            sparkDays = null,
                            sparkKind = null,
                            sparkLabel = null,
                            matchStatus = MatchStatus.BOUND,
                            userId = "sec_old_friend",
                            alreadyImported = true,
                        ),
                        candidate(
                            name = "重名冲突",
                            type = "friend",
                            sparkDays = null,
                            sparkKind = null,
                            sparkLabel = null,
                            matchStatus = MatchStatus.AMBIGUOUS,
                            alreadyImported = true,
                        ),
                    ),
                ),
            )
            advanceUntilIdle()

            vm.syncExistingOfficialSparks()
            advanceUntilIdle()

            val updatedObserved = repo.targetsState.value.first { it.id == "t-active" }
            assertNull(updatedObserved.sparkDays)
            assertNull(updatedObserved.sparkKind)
            assertEquals("无火花", updatedObserved.sparkLabel)
            assertEquals(fixedNow.toEpochMilli(), updatedObserved.sparkScannedAt)
            assertEquals(14, updatedObserved.streakDays)

            val untouchedUnseen = repo.targetsState.value.first { it.id == "t-unseen" }
            assertEquals(20, untouchedUnseen.sparkDays)
            assertEquals("normal", untouchedUnseen.sparkKind)
            assertEquals("20天", untouchedUnseen.sparkLabel)
            assertNull(untouchedUnseen.sparkScannedAt)

            val untouchedAmbiguous = repo.targetsState.value.first { it.id == "t-ambig" }
            assertEquals(30, untouchedAmbiguous.sparkDays)
            assertEquals("30天", untouchedAmbiguous.sparkLabel)

            assertEquals(1, repo.sparkSnapshotUpdateCalls)
            assertTrue(vm.uiState.value.feedbackMessage?.contains("1 位") == true)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun ignoresForeignAccountScanSnapshotsAndRunningProgress() = runTest {
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
                accountId = DEFAULT_ACCOUNT_ID,
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )

            gateway.mutableLiveState.value = ExecutionLiveState(
                running = true,
                activeAccountId = "acct-2",
                currentTask = "正在扫描二号好友",
                latestScanSnapshotAccountId = "acct-2",
                latestScanSnapshot = ScanReconciliationSnapshot(
                    scanRunId = "scan-foreign",
                    scannedAtEpochMs = 30_000L,
                    discoveredCount = 1,
                    sparkCount = 1,
                    candidates = listOf(candidate(name = "二号好友", sparkDays = 7, sparkLabel = "7天")),
                ),
            )
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.scanRunning)
            assertFalse(state.hasAvailableSnapshot)
            assertTrue(state.reconcileState.allCandidates.isEmpty())

            vm.startManualScan()
            advanceUntilIdle()

            // 其他账号完成扫描也不会替当前账号自动弹出对账弹窗
            gateway.mutableLiveState.value = gateway.mutableLiveState.value.copy(
                running = false,
                activeAccountId = "acct-2",
                lastUpdatedAtEpochMs = 40_000L,
                latestScanSnapshotAccountId = "acct-2",
                latestScanSnapshot = ScanReconciliationSnapshot(
                    scanRunId = "scan-foreign-done",
                    scannedAtEpochMs = 40_000L,
                    discoveredCount = 1,
                    sparkCount = 1,
                    candidates = listOf(candidate(name = "二号好友", sparkDays = 7, sparkLabel = "7天")),
                ),
            )
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isReconcileSheetOpen)
            assertFalse(vm.uiState.value.hasAvailableSnapshot)
            assertTrue(vm.uiState.value.reconcileState.allCandidates.isEmpty())

            // 当前账号完成扫描后才会自动打开属于本号的对账弹窗
            gateway.mutableLiveState.value = ExecutionLiveState(
                running = false,
                activeAccountId = DEFAULT_ACCOUNT_ID,
                lastUpdatedAtEpochMs = 50_000L,
                latestScanSnapshotAccountId = DEFAULT_ACCOUNT_ID,
                latestScanSnapshot = ScanReconciliationSnapshot(
                    scanRunId = "scan-own-done",
                    scannedAtEpochMs = 50_000L,
                    discoveredCount = 1,
                    sparkCount = 1,
                    candidates = listOf(candidate(name = "一号好友", sparkDays = 9, sparkLabel = "9天")),
                ),
            )
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isReconcileSheetOpen)
            assertTrue(vm.uiState.value.hasAvailableSnapshot)
            assertEquals(
                listOf("一号好友"),
                vm.uiState.value.reconcileState.allCandidates.map { it.candidate.displayName },
            )
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun isolatesTargetsByAccountMarksMissingImageTemplatesAndForwardsAccountId() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val acct2 = "acct-2"
            val imageStore = FakeTargetsImageStorage(existingPaths = mutableSetOf("images/present.jpg"))
            val repo = FakeTargetsRepository().apply {
                extraAccounts.value = mapOf(
                    acct2 to AccountEntity(
                        id = acct2,
                        displayName = "二号",
                        status = AccountStatus.HEALTHY,
                        timezone = "Asia/Shanghai",
                    ),
                )
                templatesState.value = listOf(
                    MessageTemplateEntity(
                        id = "tpl-default",
                        accountId = DEFAULT_ACCOUNT_ID,
                        name = "默认文本",
                        type = TemplateType.TEXT,
                        content = "hello",
                    ),
                    MessageTemplateEntity(
                        id = "tpl-img-missing",
                        accountId = acct2,
                        name = "缺图模板",
                        type = TemplateType.IMAGE,
                        imagePath = "images/gone.jpg",
                    ),
                )
                val defaultTarget = ContractTargetEntity(
                    id = "t-default",
                    accountId = DEFAULT_ACCOUNT_ID,
                    nicknameSnapshot = "默认好友",
                    templateId = "tpl-default",
                    platformUserId = "u-default",
                )
                val acct2Target = ContractTargetEntity(
                    id = "t-acct2",
                    accountId = acct2,
                    nicknameSnapshot = "二号好友",
                    templateId = "tpl-img-missing",
                    platformUserId = "u-acct2",
                )
                targetsState.value = listOf(defaultTarget, acct2Target)
            }
            val gateway = FakeExecutionGateway()
            val scheduler = FakeScheduleGateway()
            val vm = TargetsViewModel(
                repository = repo,
                executionGateway = gateway,
                scheduleGateway = scheduler,
                accountId = acct2,
                imageStore = imageStore,
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(1, state.items.size)
            val item = state.items.single()
            assertEquals("t-acct2", item.target.id)
            assertEquals(TemplateType.IMAGE, item.templateType)
            assertTrue(item.isTemplateMissingImage)
            assertTrue("tpl-img-missing" in state.missingImageTemplateIds)

            vm.requestSingleSend(item.target)
            advanceUntilIdle()
            val dlg = vm.uiState.value.singleSendDialog as SingleSendDialogState.ConfirmSend
            assertEquals(TemplateType.IMAGE, dlg.templateType)
            assertTrue(dlg.isTemplateMissingImage)

            vm.confirmSingleSend()
            vm.startManualScan()
            advanceUntilIdle()
            assertEquals(listOf(acct2), gateway.singleSendAccountIds)
            assertEquals(listOf(acct2), gateway.manualScanAccountIds)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun scanImportAndManualCreateDefaultToSparkStickerTemplateAndRespectExplicitOverride() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = FakeTargetsRepository().apply {
                // 即使「比心」或自定义文本模板排在前面，仍优先命中内置「续火花」表情模板
                templatesState.value = listOf(
                    MessageTemplateEntity(
                        id = DEFAULT_STICKER_TEMPLATE_ID,
                        name = "比心",
                        type = TemplateType.STICKER,
                        stickerName = "比心",
                        stickerLabel = "比心",
                        createdAt = 100L,
                    ),
                    MessageTemplateEntity(
                        id = DEFAULT_TEXT_TEMPLATE_ID,
                        name = "每日续火",
                        type = TemplateType.TEXT,
                        content = "早安打卡",
                        createdAt = 150L,
                    ),
                    MessageTemplateEntity(
                        id = DEFAULT_STICKER_SPARK_TEMPLATE_ID,
                        name = "续火花",
                        type = TemplateType.STICKER,
                        stickerName = "续火花",
                        stickerLabel = "续火花",
                        createdAt = 200L,
                    ),
                )
            }
            val gateway = FakeExecutionGateway()
            val scheduler = FakeScheduleGateway()
            val vm = TargetsViewModel(
                repository = repo,
                executionGateway = gateway,
                scheduleGateway = scheduler,
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )

            gateway.mutableLiveState.value = ExecutionLiveState(
                latestScanSnapshotAccountId = DEFAULT_ACCOUNT_ID,
                latestScanSnapshot = ScanReconciliationSnapshot(
                    scanRunId = "scan-default-tpl",
                    scannedAtEpochMs = 1_000L,
                    discoveredCount = 2,
                    sparkCount = 2,
                    candidates = listOf(
                        candidate(name = "好友甲", sparkDays = 10, sparkLabel = "10天"),
                        candidate(name = "好友乙", sparkDays = 8, sparkLabel = "8天"),
                    ),
                ),
            )
            advanceUntilIdle()

            // 1. 扫描对账抽屉默认自动选中「续火花」表情模板
            assertEquals(DEFAULT_STICKER_SPARK_TEMPLATE_ID, vm.uiState.value.reconcileState.defaultTemplateId)

            // 手工新增弹窗同样默认绑定「续火花」表情模板
            vm.openCreateTargetDialog()
            advanceUntilIdle()
            assertEquals(DEFAULT_STICKER_SPARK_TEMPLATE_ID, vm.uiState.value.editorState?.templateId)
            vm.dismissTargetEditor()

            // 只勾选「好友甲」并导入，应自动绑定「续火花」
            val friendB = vm.uiState.value.reconcileState.allCandidates.first { it.candidate.displayName == "好友乙" }.candidate
            vm.toggleCandidateSelection(friendB)
            advanceUntilIdle()
            vm.importSelectedCandidates()
            advanceUntilIdle()

            val importedA = repo.targetsState.value.first { it.nicknameSnapshot == "好友甲" }
            assertEquals(DEFAULT_STICKER_SPARK_TEMPLATE_ID, importedA.templateId)

            // 2. 用户显式选择「暂不绑定模板」(null) 后，必须严格尊重用户选择
            vm.onReconcileDefaultTemplateChanged(null)
            vm.toggleCandidateSelection(friendB)
            advanceUntilIdle()
            assertNull(vm.uiState.value.reconcileState.defaultTemplateId)

            vm.importSelectedCandidates()
            advanceUntilIdle()
            val importedB = repo.targetsState.value.first { it.nicknameSnapshot == "好友乙" }
            assertNull(importedB.templateId)

            // 3. 多账号作用域模板 ID（acct-2:default-sticker-xuhuohua）同样优先命中
            val multiTemplates = listOf(
                MessageTemplateEntity(
                    id = "acct-2:$DEFAULT_STICKER_TEMPLATE_ID",
                    accountId = "acct-2",
                    name = "比心",
                    type = TemplateType.STICKER,
                ),
                MessageTemplateEntity(
                    id = "acct-2:$DEFAULT_STICKER_SPARK_TEMPLATE_ID",
                    accountId = "acct-2",
                    name = "续火花",
                    type = TemplateType.STICKER,
                    stickerName = "续火花",
                    stickerLabel = "续火花",
                ),
            )
            assertEquals(
                "acct-2:$DEFAULT_STICKER_SPARK_TEMPLATE_ID",
                TargetsViewModel.resolvePreferredDefaultTemplateId(multiTemplates),
            )

            // 4. 备份恢复的同名「续火花」表情模板与回退策略（其他表情 -> 任意启用模板）
            val renamedSparkTemplates = listOf(
                MessageTemplateEntity(id = "txt-1", name = "早安", type = TemplateType.TEXT, content = "hi"),
                MessageTemplateEntity(id = "backup-spark", name = "续火花", type = TemplateType.STICKER, stickerName = "续火花"),
            )
            assertEquals("backup-spark", TargetsViewModel.resolvePreferredDefaultTemplateId(renamedSparkTemplates))

            val otherStickerFallback = listOf(
                MessageTemplateEntity(id = "txt-1", name = "早安", type = TemplateType.TEXT, content = "hi"),
                MessageTemplateEntity(id = "stk-bixin", name = "比心", type = TemplateType.STICKER, stickerName = "比心"),
            )
            assertEquals("stk-bixin", TargetsViewModel.resolvePreferredDefaultTemplateId(otherStickerFallback))

            val textOnlyFallback = listOf(
                MessageTemplateEntity(id = "txt-only", name = "自定义文本", type = TemplateType.TEXT, content = "hi"),
            )
            assertEquals("txt-only", TargetsViewModel.resolvePreferredDefaultTemplateId(textOnlyFallback))
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun importingAndSyncingCandidatesWritesOfficialSparkLitTodayAndExposesOnTargetItems() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = FakeTargetsRepository()
            val gateway = FakeExecutionGateway()
            val scheduler = FakeScheduleGateway()
            val litStore = OfficialSparkLitStore()
            val fixedNow = Instant.parse("2026-11-21T12:00:00Z")
            val todayStr = "2026-11-21"

            val vm = TargetsViewModel(
                repository = repo,
                executionGateway = gateway,
                scheduleGateway = scheduler,
                officialSparkLitStore = litStore,
                nowInstant = { fixedNow },
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )

            gateway.mutableLiveState.value = ExecutionLiveState(
                latestScanSnapshotAccountId = DEFAULT_ACCOUNT_ID,
                latestScanSnapshot = ScanReconciliationSnapshot(
                    scanRunId = "scan-lit-import",
                    scannedAtEpochMs = fixedNow.toEpochMilli(),
                    discoveredCount = 3,
                    sparkCount = 3,
                    candidates = listOf(
                        candidate(
                            name = "亮火知己",
                            sparkDays = 42,
                            sparkKind = "soulmate",
                            sparkLabel = "42天",
                            sparkLitToday = true,
                        ),
                        candidate(
                            name = "灰火搭子",
                            sparkDays = 18,
                            sparkKind = "mate",
                            sparkLabel = "18天",
                            sparkLitToday = false,
                        ),
                        candidate(
                            name = "未知挚友",
                            sparkDays = 9,
                            sparkKind = "bosom",
                            sparkLabel = "9天",
                            sparkLitToday = null,
                        ),
                    ),
                ),
            )
            advanceUntilIdle()

            vm.importSelectedCandidates()
            advanceUntilIdle()

            val itemsByName = vm.uiState.value.items.associateBy { it.target.nicknameSnapshot }
            val litItem = itemsByName["亮火知己"]
            val grayItem = itemsByName["灰火搭子"]
            val unknownItem = itemsByName["未知挚友"]
            assertNotNull(litItem)
            assertNotNull(grayItem)
            assertNotNull(unknownItem)

            // 导入立即可见今日亮/灰态，且绝不改写本机 streakDays
            assertEquals(true, litItem?.officialSparkLitToday)
            assertEquals("抖音今日已亮", litItem?.todayStatusLabel)
            assertEquals(0, litItem?.target?.streakDays)
            assertTrue(litStore.isOfficiallyLitToday(litItem!!.target.id, todayStr))

            assertEquals(false, grayItem?.officialSparkLitToday)
            assertEquals(false, litStore.getTodayLitState(grayItem!!.target.id, todayStr))

            assertNull(unknownItem?.officialSparkLitToday)
            assertNull(litStore.getTodayLitState(unknownItem!!.target.id, todayStr))

            // 同步已有好友火花快照时同步更新亮/灰态（灰火转亮火，无火花清除旧态）
            gateway.mutableLiveState.value = ExecutionLiveState(
                latestScanSnapshotAccountId = DEFAULT_ACCOUNT_ID,
                latestScanSnapshot = ScanReconciliationSnapshot(
                    scanRunId = "scan-lit-sync",
                    scannedAtEpochMs = fixedNow.toEpochMilli() + 60_000L,
                    discoveredCount = 2,
                    sparkCount = 1,
                    candidates = listOf(
                        candidate(
                            name = "灰火搭子",
                            sparkDays = 19,
                            sparkKind = "mate",
                            sparkLabel = "19天",
                            alreadyImported = true,
                            sparkLitToday = true,
                        ),
                        candidate(
                            name = "亮火知己",
                            sparkDays = null,
                            sparkKind = null,
                            sparkLabel = null,
                            alreadyImported = true,
                            sparkLitToday = null,
                        ),
                    ),
                ),
            )
            advanceUntilIdle()

            vm.syncExistingOfficialSparks()
            advanceUntilIdle()

            assertTrue(litStore.isOfficiallyLitToday(grayItem.target.id, todayStr))
            assertNull(litStore.getTodayLitState(litItem.target.id, todayStr))
            val refreshedGray = vm.uiState.value.items.first { it.target.id == grayItem.target.id }
            assertEquals(true, refreshedGray.officialSparkLitToday)
            assertEquals("抖音今日已亮", refreshedGray.todayStatusLabel)

            // 昨日旧快照导入不得覆盖或清空今日 OfficialSparkLitStore 记录
            gateway.mutableLiveState.value = ExecutionLiveState(
                latestScanSnapshotAccountId = DEFAULT_ACCOUNT_ID,
                latestScanSnapshot = ScanReconciliationSnapshot(
                    scanRunId = "scan-yesterday",
                    scannedAtEpochMs = fixedNow.minusSeconds(86_400L).toEpochMilli(),
                    discoveredCount = 1,
                    sparkCount = 1,
                    candidates = listOf(
                        candidate(
                            name = "昨日新友",
                            sparkDays = 5,
                            sparkKind = "couple",
                            sparkLabel = "5天",
                            alreadyImported = false,
                            sparkLitToday = true,
                        ),
                    ),
                ),
            )
            advanceUntilIdle()

            vm.importSelectedCandidates()
            advanceUntilIdle()

            val yesterdayTarget = repo.targetsState.value.first { it.nicknameSnapshot == "昨日新友" }
            assertFalse(litStore.isOfficiallyLitToday(yesterdayTarget.id, todayStr))
            assertTrue(litStore.isOfficiallyLitToday(grayItem.target.id, todayStr))
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun officialSparkKindLabelsCoverAllSixDouyinTypes() {
        assertEquals("情侣", officialSparkKindShortLabel("couple"))
        assertEquals("家人", officialSparkKindShortLabel("family"))
        assertEquals("普通", officialSparkKindShortLabel("normal"))
        assertEquals("知己", officialSparkKindShortLabel("soulmate"))
        assertEquals("搭子", officialSparkKindShortLabel("mate"))
        assertEquals("挚友", officialSparkKindShortLabel("bosom"))
        assertNull(officialSparkKindShortLabel("unknown"))

        assertEquals("情侣火花", officialSparkKindFullLabel("couple"))
        assertEquals("家人火花", officialSparkKindFullLabel("family"))
        assertEquals("普通火花", officialSparkKindFullLabel("normal"))
        assertEquals("知己火花", officialSparkKindFullLabel("soulmate"))
        assertEquals("搭子火花", officialSparkKindFullLabel("mate"))
        assertEquals("挚友火花", officialSparkKindFullLabel("bosom"))
        assertEquals("官方火花", officialSparkKindFullLabel(null))
    }

    @Test
    fun importingUnknownConversationTypeKeepsNullTypeWhileBoundGroupPersistsGroupId() = runTest {
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

            val boundGroup = candidate(
                name = "保时捷车友会",
                type = "group",
                sparkDays = 60,
                sparkKind = "normal",
                convId = "7310000000000000888",
                matchStatus = MatchStatus.BOUND,
            )
            val unknownSession = candidate(
                name = "未识别会话甲",
                type = "unknown",
                sparkDays = 10,
                sparkKind = "normal",
                matchStatus = MatchStatus.NOT_OBSERVED,
            )
            val boundFriend = candidate(
                name = "老友乙",
                type = "friend",
                sparkDays = 20,
                sparkKind = "normal",
                matchStatus = MatchStatus.BOUND,
            )

            gateway.mutableLiveState.value = ExecutionLiveState(
                latestScanSnapshotAccountId = DEFAULT_ACCOUNT_ID,
                latestScanSnapshot = ScanReconciliationSnapshot(
                    scanRunId = "scan-group-unknown",
                    scannedAtEpochMs = 5_000L,
                    discoveredCount = 3,
                    sparkCount = 3,
                    candidates = listOf(boundGroup, unknownSession, boundFriend),
                ),
            )
            advanceUntilIdle()

            // 搜索「群聊」只命中群聊，搜索「好友」只命中已识别好友（不误命中 unknown）
            vm.onReconcileSearchChanged("群聊")
            advanceUntilIdle()
            assertEquals(listOf("保时捷车友会"), vm.uiState.value.reconcileState.filteredCandidates.map { it.candidate.displayName })

            vm.onReconcileSearchChanged("好友")
            advanceUntilIdle()
            assertEquals(listOf("老友乙"), vm.uiState.value.reconcileState.filteredCandidates.map { it.candidate.displayName })

            vm.onReconcileSearchChanged("")
            advanceUntilIdle()

            // 手动勾选未识别会话，和默认预选的群聊/好友一起导入
            val unknownCand = vm.uiState.value.reconcileState.allCandidates.first { it.candidate.displayName == "未识别会话甲" }.candidate
            vm.toggleCandidateSelection(unknownCand)
            advanceUntilIdle()

            vm.importSelectedCandidates()
            advanceUntilIdle()

            val byName = repo.targetsState.value.associateBy { it.nicknameSnapshot }
            val importedGroup = byName.getValue("保时捷车友会")
            assertEquals("group", importedGroup.conversationType)
            assertEquals("7310000000000000888", importedGroup.platformConversationId)
            assertNull(importedGroup.platformUserId)

            val importedUnknown = byName.getValue("未识别会话甲")
            assertNull(importedUnknown.conversationType)
            assertNull(importedUnknown.platformUserId)
            assertNull(importedUnknown.platformConversationId)

            val importedFriend = byName.getValue("老友乙")
            assertEquals("friend", importedFriend.conversationType)
            assertEquals("sec_老友乙", importedFriend.platformUserId)
        } finally {
            vmScope.cancel()
        }
    }

    private class FakeTargetsImageStorage(
        private val existingPaths: MutableSet<String> = mutableSetOf(),
    ) : TemplateImageStorage {
        override fun resolveExistingFile(storedPath: String?): File? =
            storedPath?.takeIf { it in existingPaths }?.let { File(it) }

        override suspend fun importFromStream(input: InputStream, mimeTypeHint: String?): StoredTemplateImage =
            StoredTemplateImage("images/fake.jpg", mimeTypeHint ?: "image/jpeg", 100L, 100, 100)

        override fun deleteIfUnreferenced(storedPath: String?, referencedPaths: Set<String>): Boolean = false

        override fun contentUriFor(file: File): Uri? = null
    }
}
