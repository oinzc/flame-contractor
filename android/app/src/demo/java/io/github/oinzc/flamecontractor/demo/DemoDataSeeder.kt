package io.github.oinzc.flamecontractor.demo

import android.content.Context
import androidx.room.withTransaction
import io.github.oinzc.flamecontractor.app.AppContainer
import io.github.oinzc.flamecontractor.core.identity.MatchStatus
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.DEFAULT_STICKER_TEMPLATE_ID
import io.github.oinzc.flamecontractor.data.db.DEFAULT_TEXT_TEMPLATE_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunStatus
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.data.db.TemplatePoolItemEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.execution.ScanReconciliationCodec
import io.github.oinzc.flamecontractor.execution.ScanReconciliationSnapshot
import io.github.oinzc.flamecontractor.execution.ScannedChatCandidate
import io.github.oinzc.flamecontractor.ui.settings.RomSurvivalChecker
import io.github.oinzc.flamecontractor.widget.FlameStatusWidgetCalculator
import io.github.oinzc.flamecontractor.widget.FlameStatusWidgetSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 100% 虚构演示数据集（与 backend/scripts/demo_server.py 保持一致，绝不包含任何真实个人数据）。
 */
internal object DemoDataSeeder {

    const val BACKUP_ACCOUNT_ID = "demo-backup"
    const val POOL_TEMPLATE_ID = "demo-tpl-pool-daily"
    const val TEXT_TEMPLATE_ID = DEFAULT_TEXT_TEMPLATE_ID
    const val STICKER_TEMPLATE_ID = DEFAULT_STICKER_TEMPLATE_ID

    val DEMO_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    data class DemoTargetSpec(
        val id: String,
        val nickname: String,
        val conversationType: String,
        val sendTimeHhmm: String?,
        val streakDays: Int,
        val sparkDays: Int?,
        val sparkKind: String?,
        val sparkLabel: String?,
        val templateId: String,
        val sentToday: Boolean,
        val todaySentTime: LocalTime?,
    )

    val DAILY_TARGETS: List<DemoTargetSpec> = listOf(
        DemoTargetSpec(
            id = "demo-target-01",
            nickname = "小满",
            conversationType = "user",
            sendTimeHhmm = "07:30",
            streakDays = 412,
            sparkDays = 412,
            sparkKind = "normal",
            sparkLabel = null,
            templateId = POOL_TEMPLATE_ID,
            sentToday = true,
            todaySentTime = LocalTime.of(7, 30, 12),
        ),
        DemoTargetSpec(
            id = "demo-target-02",
            nickname = "阿哲",
            conversationType = "user",
            sendTimeHhmm = null,
            streakDays = 268,
            sparkDays = 268,
            sparkKind = "normal",
            sparkLabel = null,
            templateId = POOL_TEMPLATE_ID,
            sentToday = true,
            todaySentTime = LocalTime.of(12, 18, 9),
        ),
        DemoTargetSpec(
            id = "demo-target-03",
            nickname = "林间有风",
            conversationType = "user",
            sendTimeHhmm = null,
            streakDays = 187,
            sparkDays = 187,
            sparkKind = "couple",
            sparkLabel = null,
            templateId = STICKER_TEMPLATE_ID,
            sentToday = true,
            todaySentTime = LocalTime.of(12, 18, 21),
        ),
        DemoTargetSpec(
            id = "demo-target-04",
            nickname = "大学室友群",
            conversationType = "group",
            sendTimeHhmm = null,
            streakDays = 154,
            sparkDays = 154,
            sparkKind = "normal",
            sparkLabel = null,
            templateId = TEXT_TEMPLATE_ID,
            sentToday = true,
            todaySentTime = LocalTime.of(12, 18, 34),
        ),
        DemoTargetSpec(
            id = "demo-target-05",
            nickname = "周末去爬山",
            conversationType = "user",
            sendTimeHhmm = null,
            streakDays = 96,
            sparkDays = 96,
            sparkKind = "normal",
            sparkLabel = null,
            templateId = POOL_TEMPLATE_ID,
            sentToday = false,
            todaySentTime = null,
        ),
        DemoTargetSpec(
            id = "demo-target-06",
            nickname = "豆豆不吃香菜",
            conversationType = "user",
            sendTimeHhmm = null,
            streakDays = 63,
            sparkDays = 63,
            sparkKind = "family",
            sparkLabel = null,
            templateId = POOL_TEMPLATE_ID,
            sentToday = true,
            todaySentTime = LocalTime.of(12, 18, 46),
        ),
        DemoTargetSpec(
            id = "demo-target-07",
            nickname = "橘子汽水",
            conversationType = "user",
            sendTimeHhmm = null,
            streakDays = 41,
            sparkDays = null,
            sparkKind = "normal",
            sparkLabel = "重燃中 2/3",
            templateId = POOL_TEMPLATE_ID,
            sentToday = false,
            todaySentTime = null,
        ),
        DemoTargetSpec(
            id = "demo-target-08",
            nickname = "晚自习小分队",
            conversationType = "group",
            sendTimeHhmm = null,
            streakDays = 22,
            sparkDays = 22,
            sparkKind = "normal",
            sparkLabel = null,
            templateId = TEXT_TEMPLATE_ID,
            sentToday = true,
            todaySentTime = LocalTime.of(12, 18, 57),
        ),
    )

    data class SeededDemoDataset(
        val account: AccountEntity,
        val targets: List<ContractTargetEntity>,
        val todayJobs: List<SendJobEntity>,
        val recentJobs7Days: List<SendJobEntity>,
        val todayDate: LocalDate,
        val widgetNow: Instant,
    )

    suspend fun seedDatabase(
        context: Context,
        container: AppContainer,
    ): SeededDemoDataset = withContext(Dispatchers.IO) {
        val nowInstant = Instant.now()
        val nowZdt = ZonedDateTime.ofInstant(nowInstant, DEMO_ZONE)
        val today = nowZdt.toLocalDate()
        val nowMs = nowInstant.toEpochMilli()
        val verifiedAtMs = nowInstant.minusSeconds(24L * 60L).toEpochMilli()
        val scannedAtMs = nowInstant.minusSeconds(26L * 60L).toEpochMilli()
        val createdBaseMs = ZonedDateTime.of(today.minusDays(410), LocalTime.of(9, 0), DEMO_ZONE)
            .toInstant()
            .toEpochMilli()

        val primaryAccount = AccountEntity(
            id = DEFAULT_ACCOUNT_ID,
            displayName = "日常号",
            status = AccountStatus.HEALTHY,
            enabled = true,
            timezone = DEMO_ZONE.id,
            sendWindowStart = "20:00",
            sendWindowEnd = "22:00",
            sendTimeHhmm = "21:30",
            jitterMaxMinutes = 10,
            cooldownMinSec = 3,
            cooldownMaxSec = 8,
            authFailCount = 0,
            failureStreak = 0,
            selfUid = "demo-uid-wanfeng-001",
            douyinNickname = "晚风",
            lastVerifiedAt = verifiedAtMs,
            lastSuccessAt = ZonedDateTime.of(today, LocalTime.of(12, 19, 1), DEMO_ZONE).toInstant().toEpochMilli(),
            createdAt = createdBaseMs,
            updatedAt = nowMs,
            autoScanEnabled = true,
            autoScanIntervalHours = 6,
            lastAutoScanAttemptAt = scannedAtMs,
        )

        val backupAccount = AccountEntity(
            id = BACKUP_ACCOUNT_ID,
            displayName = "备用号",
            status = AccountStatus.HEALTHY,
            enabled = true,
            timezone = DEMO_ZONE.id,
            sendWindowStart = "20:00",
            sendWindowEnd = "22:00",
            sendTimeHhmm = null,
            jitterMaxMinutes = 15,
            cooldownMinSec = 3,
            cooldownMaxSec = 8,
            authFailCount = 0,
            failureStreak = 0,
            selfUid = "demo-uid-shanhai-002",
            douyinNickname = "山海",
            lastVerifiedAt = verifiedAtMs,
            lastSuccessAt = verifiedAtMs,
            createdAt = createdBaseMs + 86_400_000L * 110L,
            updatedAt = nowMs,
            autoScanEnabled = true,
            autoScanIntervalHours = 24,
            lastAutoScanAttemptAt = scannedAtMs,
        )

        val poolTemplate = MessageTemplateEntity(
            id = POOL_TEMPLATE_ID,
            accountId = DEFAULT_ACCOUNT_ID,
            name = "每日文案池",
            type = TemplateType.POOL,
            content = "",
            enabled = true,
            createdAt = createdBaseMs + 1000L,
            updatedAt = nowMs,
        )
        val textTemplate = MessageTemplateEntity(
            id = TEXT_TEMPLATE_ID,
            accountId = DEFAULT_ACCOUNT_ID,
            name = "晚安问候",
            type = TemplateType.TEXT,
            content = "晚安，今天也续上啦 🔥",
            enabled = true,
            createdAt = createdBaseMs + 2000L,
            updatedAt = nowMs,
        )
        val stickerTemplate = MessageTemplateEntity(
            id = STICKER_TEMPLATE_ID,
            accountId = DEFAULT_ACCOUNT_ID,
            name = "比心表情",
            type = TemplateType.STICKER,
            content = "",
            stickerName = "比心",
            stickerLabel = "比心",
            stickerCategory = "默认",
            enabled = true,
            createdAt = createdBaseMs + 3000L,
            updatedAt = nowMs,
        )

        val poolLines = listOf(
            "今天也来续一下火 🔥" to 0,
            "周一快乐，这周也一起加油" to 0b0000001,
            "周五啦，周末有什么安排？" to 0b0010000,
            "刚看到一个好笑的视频，晚点发你" to 0,
            "晚安，明天见 🌙" to 0,
        )

        val targets = DAILY_TARGETS.mapIndexed { idx, spec ->
            val isGroup = spec.conversationType == "group"
            val lastConfirmedMs = if (spec.sentToday && spec.todaySentTime != null) {
                ZonedDateTime.of(today, spec.todaySentTime.plusSeconds(4), DEMO_ZONE).toInstant().toEpochMilli()
            } else {
                ZonedDateTime.of(today.minusDays(1), LocalTime.of(21, 30, 15 + idx * 7), DEMO_ZONE)
                    .toInstant()
                    .toEpochMilli()
            }
            ContractTargetEntity(
                id = spec.id,
                accountId = DEFAULT_ACCOUNT_ID,
                nicknameSnapshot = spec.nickname,
                platformUserId = if (isGroup) null else "demo-uid-daily-${idx + 1}",
                platformHandle = if (isGroup) null else "demo_friend_0${idx + 1}",
                platformConversationId = if (isGroup) "demo-conv-daily-${idx + 1}" else null,
                conversationType = spec.conversationType,
                templateId = spec.templateId,
                enabled = true,
                sendTimeHhmm = spec.sendTimeHhmm,
                sparkDays = spec.sparkDays,
                sparkKind = spec.sparkKind,
                sparkLabel = spec.sparkLabel,
                sparkScannedAt = scannedAtMs,
                streakDays = spec.streakDays,
                lastSentAt = lastConfirmedMs - 4_000L,
                lastConfirmedAt = lastConfirmedMs,
                deletedAt = null,
                createdAt = createdBaseMs + (idx + 1) * 10_000L,
                updatedAt = nowMs,
            )
        }

        val allJobs = mutableListOf<SendJobEntity>()
        val allAttempts = mutableListOf<SendAttemptEntity>()

        // 30 天真实执行记录：前 29 天每天 8 条（仅第 28 天前有 1 条网络波动失败，形成 99.6% 成功率 + 28 天连胜）
        for (offset in 29 downTo 1) {
            val day = today.minusDays(offset.toLong())
            val dayStr = day.toString()
            DAILY_TARGETS.forEachIndexed { idx, spec ->
                val sendTime = if (spec.sendTimeHhmm == "07:30") {
                    LocalTime.of(7, 30, 10 + (offset % 9))
                } else {
                    LocalTime.of(21, 30, 8 + idx * 6)
                }
                val startMs = ZonedDateTime.of(day, sendTime, DEMO_ZONE).toInstant().toEpochMilli()
                val durationMs = 3_400L + ((idx * 430L + offset * 170L) % 2_100L)
                val endMs = startMs + durationMs
                val isSingleHistoricFailure = (offset == 28 && idx == 4)
                val jobId = "demo-job-$dayStr-${idx + 1}"

                val job = SendJobEntity(
                    id = jobId,
                    accountId = DEFAULT_ACCOUNT_ID,
                    targetId = spec.id,
                    templateId = spec.templateId,
                    businessDate = dayStr,
                    messageSlotId = SendGuard.DAILY_SLOT_ID,
                    status = if (isSingleHistoricFailure) JobStatus.FAILED_TERMINAL else JobStatus.SUCCEEDED,
                    scheduledAt = startMs - 5_000L,
                    attemptCount = if (isSingleHistoricFailure) 3 else 1,
                    failureClass = if (isSingleHistoricFailure) FailureClass.NETWORK else null,
                    summaryDetail = if (isSingleHistoricFailure) {
                        "夜间网络短暂波动，重试后达到当日上限"
                    } else {
                        "终态已确认 · 新气泡可见"
                    },
                    confirmedAt = if (isSingleHistoricFailure) null else endMs,
                    createdAt = startMs,
                    updatedAt = endMs,
                )
                allJobs += job
                allAttempts += SendAttemptEntity(
                    id = "demo-att-$dayStr-${idx + 1}-1",
                    jobId = jobId,
                    attemptNo = 1,
                    stage = if (isSingleHistoricFailure) "opening_chat" else "confirming",
                    status = if (isSingleHistoricFailure) AttemptStatus.FAILED else AttemptStatus.SUCCEEDED,
                    failureClass = if (isSingleHistoricFailure) FailureClass.NETWORK else null,
                    inputMethodUsed = if (spec.templateId == STICKER_TEMPLATE_ID) "sticker_click" else "clipboard_paste",
                    errorCode = if (isSingleHistoricFailure) "ERR_NETWORK_TIMEOUT" else null,
                    startedAt = startMs,
                    finishedAt = endMs,
                )
            }
        }

        // 今日 6/8 已完成记录（按展示清晰顺序排列）
        val todayStr = today.toString()
        val todayJobs = mutableListOf<SendJobEntity>()
        DAILY_TARGETS.forEachIndexed { idx, spec ->
            if (!spec.sentToday || spec.todaySentTime == null) return@forEachIndexed
            val startMs = ZonedDateTime.of(today, spec.todaySentTime, DEMO_ZONE).toInstant().toEpochMilli()
            val endMs = startMs + 3_800L + idx * 260L
            val jobId = "demo-job-$todayStr-${idx + 1}"
            val job = SendJobEntity(
                id = jobId,
                accountId = DEFAULT_ACCOUNT_ID,
                targetId = spec.id,
                templateId = spec.templateId,
                businessDate = todayStr,
                messageSlotId = SendGuard.DAILY_SLOT_ID,
                status = JobStatus.SUCCEEDED,
                scheduledAt = startMs - 4_000L,
                attemptCount = 1,
                failureClass = null,
                summaryDetail = "终态已确认 · 输入框清空且新气泡出现",
                confirmedAt = endMs,
                createdAt = startMs,
                updatedAt = endMs,
            )
            todayJobs += job
            allJobs += job
            allAttempts += SendAttemptEntity(
                id = "demo-att-$todayStr-${idx + 1}-1",
                jobId = jobId,
                attemptNo = 1,
                stage = "confirming",
                status = AttemptStatus.SUCCEEDED,
                failureClass = null,
                inputMethodUsed = if (spec.templateId == STICKER_TEMPLATE_ID) "sticker_click" else "clipboard_paste",
                errorCode = null,
                startedAt = startMs,
                finishedAt = endMs,
            )
        }

        // 最近一次扫描对账快照（含 8 位已导入好友 + 2 位新发现火花会话）
        val scanCandidates = targets.map { t ->
            ScannedChatCandidate(
                displayName = t.nicknameSnapshot,
                conversationType = t.conversationType ?: "user",
                sparkDays = t.sparkDays,
                sparkKind = t.sparkKind,
                sparkLabel = t.sparkLabel,
                matchStatus = MatchStatus.BOUND,
                platformUserId = t.platformUserId,
                platformHandle = t.platformHandle,
                platformConversationId = t.platformConversationId,
                matchDetail = "已唯一绑定",
                alreadyImported = true,
                existingTargetId = t.id,
                preselected = false,
            )
        } + listOf(
            ScannedChatCandidate(
                displayName = "健身搭子",
                conversationType = "user",
                sparkDays = 5,
                sparkKind = "normal",
                sparkLabel = null,
                matchStatus = MatchStatus.BOUND,
                platformUserId = "demo-uid-new-01",
                platformHandle = "demo_gym_buddy",
                platformConversationId = null,
                matchDetail = "新发现带火花会话",
                alreadyImported = false,
                existingTargetId = null,
                preselected = true,
            ),
            ScannedChatCandidate(
                displayName = "新认识的朋友",
                conversationType = "user",
                sparkDays = null,
                sparkKind = null,
                sparkLabel = null,
                matchStatus = MatchStatus.NOT_OBSERVED,
                platformUserId = null,
                platformHandle = null,
                platformConversationId = null,
                matchDetail = null,
                alreadyImported = false,
                existingTargetId = null,
                preselected = false,
            ),
        )
        val scanRunId = "demo-scan-run-latest"
        val scanSnapshot = ScanReconciliationSnapshot(
            scanRunId = scanRunId,
            scannedAtEpochMs = scannedAtMs,
            discoveredCount = scanCandidates.size,
            sparkCount = 9,
            candidates = scanCandidates,
        )
        val scanRun = ScanRunEntity(
            id = scanRunId,
            accountId = DEFAULT_ACCOUNT_ID,
            source = "automatic",
            status = ScanRunStatus.COMPLETED,
            discoveredCount = scanCandidates.size,
            sparkCount = 9,
            reconciliationJson = ScanReconciliationCodec.encode(scanSnapshot),
            startedAt = scannedAtMs - 38_000L,
            finishedAt = scannedAtMs,
            alertSummary = null,
        )

        val db = container.database
        db.withTransaction {
            db.clearAllTables()
            db.accountDao().upsert(primaryAccount)
            db.accountDao().upsert(backupAccount)

            db.templateDao().upsertTemplate(poolTemplate)
            db.templateDao().upsertTemplate(textTemplate)
            db.templateDao().upsertTemplate(stickerTemplate)
            poolLines.forEachIndexed { idx, (line, mask) ->
                db.templateDao().upsertPoolItem(
                    TemplatePoolItemEntity(
                        id = "demo-pool-item-${idx + 1}",
                        templateId = POOL_TEMPLATE_ID,
                        content = line,
                        weekdayMask = mask,
                        enabled = true,
                        lastUsedAt = nowMs - (idx + 1) * 86_400_000L,
                        createdAt = createdBaseMs + 4000L + idx * 100L,
                        updatedAt = nowMs,
                    ),
                )
            }

            targets.forEach { db.targetDao().upsert(it) }
            allJobs.forEach { db.sendJobDao().upsertJob(it) }
            allAttempts.forEach { db.sendJobDao().upsertAttempt(it) }
            db.scanRunDao().insert(scanRun)
        }

        // 跳过新手引导并标记厂商自启动确认，避免权限引导条干扰演示截屏
        container.activeAccountStore.selectAccount(DEFAULT_ACCOUNT_ID)
        container.setupOnboardingStore.markCompleted()
        RomSurvivalChecker.setAutostartGuidanceConfirmed(context, true)

        // 严格禁止演示变体保留任何定时闹钟或后台周期任务
        container.scheduleCoordinator.cancelAllAlarms()
        container.scheduleCoordinator.cancelSafetyNetPeriodicWork()

        val since7DaysStr = today.minusDays(6).toString()
        val recentJobs7Days = allJobs.filter { it.businessDate >= since7DaysStr }
        // 固定展示 21:10（21:30 发火前）以便所有小组件稳定展示 21:30 下次排程
        val widgetNow = ZonedDateTime.of(today, LocalTime.of(21, 10, 0), DEMO_ZONE).toInstant()

        SeededDemoDataset(
            account = primaryAccount,
            targets = targets,
            todayJobs = todayJobs,
            recentJobs7Days = recentJobs7Days,
            todayDate = today,
            widgetNow = widgetNow,
        )
    }

    fun buildInProgressSnapshot(dataset: SeededDemoDataset): FlameStatusWidgetSnapshot =
        FlameStatusWidgetCalculator.calculate(
            account = dataset.account,
            enabledTargets = dataset.targets,
            todayJobs = dataset.todayJobs,
            unknownReviewCount = 0,
            now = dataset.widgetNow,
            minWidthDp = 340,
            networkDeferCountToday = 0,
            recentJobs7Days = dataset.recentJobs7Days,
        )

    fun buildAllDoneSnapshot(dataset: SeededDemoDataset): FlameStatusWidgetSnapshot {
        val todayStr = dataset.todayDate.toString()
        val allDoneNow = ZonedDateTime.of(dataset.todayDate, LocalTime.of(21, 32, 0), DEMO_ZONE).toInstant()
        val allDoneTodayJobs = dataset.targets.mapIndexed { idx, t ->
            val endMs = allDoneNow.minusSeconds((8 - idx) * 12L).toEpochMilli()
            SendJobEntity(
                id = "demo-all-done-$todayStr-${idx + 1}",
                accountId = dataset.account.id,
                targetId = t.id,
                templateId = t.templateId,
                businessDate = todayStr,
                messageSlotId = SendGuard.DAILY_SLOT_ID,
                status = JobStatus.SUCCEEDED,
                scheduledAt = endMs - 4_000L,
                attemptCount = 1,
                confirmedAt = endMs,
                createdAt = endMs - 4_000L,
                updatedAt = endMs,
            )
        }
        val recentWithAllDone = dataset.recentJobs7Days.filterNot { it.businessDate == todayStr } + allDoneTodayJobs
        return FlameStatusWidgetCalculator.calculate(
            account = dataset.account,
            enabledTargets = dataset.targets,
            todayJobs = allDoneTodayJobs,
            unknownReviewCount = 0,
            now = allDoneNow,
            minWidthDp = 340,
            networkDeferCountToday = 0,
            recentJobs7Days = recentWithAllDone,
        )
    }

    fun buildAlertSnapshot(dataset: SeededDemoDataset): FlameStatusWidgetSnapshot =
        FlameStatusWidgetCalculator.calculate(
            account = dataset.account,
            enabledTargets = dataset.targets,
            todayJobs = dataset.todayJobs,
            unknownReviewCount = 1,
            now = dataset.widgetNow,
            minWidthDp = 340,
            networkDeferCountToday = 0,
            recentJobs7Days = dataset.recentJobs7Days,
        )
}
