package io.github.oinzc.flamecontractor.execution

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.oinzc.flamecontractor.core.notify.BarkNotifier
import io.github.oinzc.flamecontractor.core.notify.EncryptedSecretStore
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.core.notify.NotifyLevel
import io.github.oinzc.flamecontractor.core.notify.UrgentCategory
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundleStore
import io.github.oinzc.flamecontractor.data.db.AccountDao
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunStatus
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobDao
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.domain.AccountHealthReducer
import io.github.oinzc.flamecontractor.domain.AuthProbeOutcome
import io.github.oinzc.flamecontractor.domain.BatchTargetOutcome
import io.github.oinzc.flamecontractor.domain.ManualSendDecision
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.domain.TemplatePoolPicker
import io.github.oinzc.flamecontractor.scheduler.DailySchedulePlanner
import io.github.oinzc.flamecontractor.scheduler.ExecutionDispatchContract
import io.github.oinzc.flamecontractor.scheduler.ScheduleCoordinator
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 限时代价的 `dataSync` 离屏前台执行服务：
 * - 单次运行受 `ExecutionMutex` 互斥锁与 `10 分钟` `PARTIAL_WAKE_LOCK` 硬超时约束；
 * - 启动即先执行 `SendGuard.recoverCrashedJobs` 收敛僵尸单；
 * - 严格贯彻 `SendGuard`、`TemplatePoolPicker`、`AccountHealthReducer` 与双通道通知；
 * - 结束或异常退出时强制收敛进行中 `CONFIRMING` 为 `UNKNOWN_REQUIRES_REVIEW`，
 *   冲刷 Cookie、销毁 WebView 并调用 `ScheduleCoordinator.rescheduleAll()`。
 */
class ExecutionForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
    private val queueLock = Any()
    private val pendingIntents = ArrayDeque<Intent>()

    private var wakeLock: PowerManager.WakeLock? = null
    private var hardTimeoutJob: Job? = null
    private var workerJob: Job? = null

    private lateinit var database: FlameDatabase
    private lateinit var selectorStore: SelectorBundleStore
    private lateinit var secretStore: EncryptedSecretStore
    private lateinit var barkNotifier: BarkNotifier
    private lateinit var localNotifier: LocalNotifier
    private lateinit var executor: DouyinWebExecutor

    @Volatile
    private var activeConfirmingJobId: String? = null

    @Volatile
    private var activeConfirmingAttemptNo: Int? = null

    @Volatile
    private var activeRunningAccountId: String? = null

    @Volatile
    private var activeBatchOutcomes: List<BatchTargetOutcome> = emptyList()

    override fun onCreate() {
        super.onCreate()
        val appCtx = applicationContext ?: this
        database = ServiceDatabaseHolder.getInstance(appCtx)
        selectorStore = SelectorBundleStore(appCtx)
        secretStore = EncryptedSecretStore(appCtx)
        barkNotifier = BarkNotifier(baseUrlProvider = { secretStore.readBarkUrl() })
        localNotifier = LocalNotifier(appCtx)
        executor = DouyinWebExecutor(appCtx, selectorStore)
        ensureProgressChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteForeground("正在准备执行任务…")
        acquireWakeLock()
        armHardTimeout()
        synchronized(queueLock) {
            if (intent != null) {
                pendingIntents.addLast(intent)
            }
            if (workerJob?.isActive != true) {
                startWorkerLoopLocked()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        convergeInterruptState(includeRunningAccount = false)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        convergeInterruptState(includeRunningAccount = true)
        hardTimeoutJob?.cancel()
        synchronized(queueLock) {
            pendingIntents.clear()
            workerJob?.cancel()
            workerJob = null
        }
        serviceJob.cancel()
        releaseWakeLock()
        try {
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {
        }
        ExecutionRuntimeBus.update {
            it.copy(
                running = false,
                currentTask = null,
                lastUpdatedAtEpochMs = System.currentTimeMillis(),
            )
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWorkerLoopLocked() {
        workerJob = serviceScope.launch {
            while (isActive) {
                val nextIntent = synchronized(queueLock) {
                    if (pendingIntents.isEmpty()) null else pendingIntents.removeFirst()
                } ?: break
                handleCommandSafely(nextIntent)
            }
            withContext(NonCancellable) {
                val shouldContinue = synchronized(queueLock) {
                    if (pendingIntents.isNotEmpty() && isActive) {
                        startWorkerLoopLocked()
                        true
                    } else {
                        workerJob = null
                        false
                    }
                }
                if (!shouldContinue) {
                    try {
                        ScheduleCoordinator(this@ExecutionForegroundService, database).rescheduleAll()
                    } catch (_: Throwable) {
                    }
                    withContext(Dispatchers.Main) {
                        val restartWorker = synchronized(queueLock) {
                            if (pendingIntents.isNotEmpty()) {
                                if (workerJob?.isActive != true) {
                                    startWorkerLoopLocked()
                                }
                                true
                            } else {
                                false
                            }
                        }
                        if (!restartWorker) {
                            releaseWakeLock()
                            ServiceCompat.stopForeground(
                                this@ExecutionForegroundService,
                                ServiceCompat.STOP_FOREGROUND_REMOVE,
                            )
                            stopSelf()
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleCommandSafely(intent: Intent) {
        val action = intent.action ?: ExecutionDispatchContract.ACTION_RUN_SCHEDULED
        val accountId = intent.getStringExtra(ExecutionDispatchContract.EXTRA_ACCOUNT_ID)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ACCOUNT_ID
        val reason = intent.getStringExtra(ExecutionDispatchContract.EXTRA_TRIGGER_REASON)
            ?: action

        try {
            recoverCrashedJobsOnStartup(accountId)

            when (action) {
                ExecutionDispatchContract.ACTION_RUN_SCHEDULED ->
                    runBatchCommand(accountId = accountId, scheduledOnly = true, reason = reason)

                ACTION_RUN_MANUAL_BATCH ->
                    runBatchCommand(accountId = accountId, scheduledOnly = false, reason = reason)

                ExecutionDispatchContract.ACTION_AUTH_RECHECK ->
                    runAuthRecheckCommand(accountId = accountId)

                ACTION_RUN_SINGLE_SEND -> {
                    val targetId = intent.getStringExtra(EXTRA_TARGET_ID).orEmpty()
                    val overrideText = intent.getStringExtra(EXTRA_OVERRIDE_TEXT)
                    if (targetId.isNotBlank()) {
                        runSingleSendCommand(accountId, targetId, overrideText)
                    }
                }

                ACTION_RUN_MANUAL_SCAN ->
                    runManualScanCommand(accountId = accountId)

                ACTION_REPAIR_IDENTITY -> {
                    val targetId = intent.getStringExtra(EXTRA_TARGET_ID).orEmpty()
                    if (targetId.isNotBlank()) {
                        runSingleIdentityRepairCommand(accountId, targetId)
                    }
                }

                ACTION_PROBE_STRUCTURE ->
                    runStructureProbeCommand(accountId = accountId)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (exc: Throwable) {
            Log.e(TAG, "Command failed action=$action type=${exc.javaClass.simpleName}")
            ExecutionRuntimeBus.update {
                it.copy(
                    running = false,
                    currentTask = null,
                    lastMessage = "执行任务发生异常（${exc.javaClass.simpleName}）",
                    lastUpdatedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun recoverCrashedJobsOnStartup(accountId: String) {
        val nowMs = System.currentTimeMillis()
        val hasUnknown = ExecutionServiceStoreOps.recoverInterruptedAccountAndJobs(
            accountId = accountId,
            accountDao = database.accountDao(),
            sendJobDao = database.sendJobDao(),
            nowMs = nowMs,
            isMutexLocked = ExecutionMutex.isLocked(),
        )
        if (hasUnknown) {
            sendUrgentBoth(
                category = UrgentCategory.UNKNOWN_REQUIRES_REVIEW,
                title = "发现未确认的发送任务",
                body = "上次执行中断时已有消息进入发送或确认阶段，请到首页核对结果，系统已阻止自动重发。",
            )
        }
    }

    private suspend fun runBatchCommand(
        accountId: String,
        scheduledOnly: Boolean,
        reason: String,
    ) {
        val lockTimeout = if (scheduledOnly) SCHEDULED_LOCK_WAIT_MS else MANUAL_LOCK_WAIT_MS
        val owner = if (scheduledOnly) "scheduled_batch" else "manual_batch"

        val acquired = ExecutionMutex.tryWithLock(owner = owner, timeoutMs = lockTimeout) {
            executeBatchLocked(accountId = accountId, scheduledOnly = scheduledOnly, reason = reason)
        }
        if (acquired == null) {
            Log.i(TAG, "Batch skipped due to ExecutionMutex busy scheduled=$scheduledOnly")
            ExecutionRuntimeBus.update {
                it.copy(
                    running = false,
                    currentTask = null,
                    lastMessage = if (scheduledOnly) {
                        "当前正在扫码或扫描，定时任务已延后等待下轮检查"
                    } else {
                        "当前已有任务正在执行，请稍后再试"
                    },
                    lastUpdatedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun executeBatchLocked(
        accountId: String,
        scheduledOnly: Boolean,
        reason: String,
    ) {
        val account = database.accountDao().getAccount(accountId) ?: return
        if (!account.enabled || account.status != AccountStatus.HEALTHY) {
            if (!scheduledOnly) {
                ExecutionRuntimeBus.update {
                    it.copy(
                        running = false,
                        currentTask = null,
                        lastMessage = "账号当前处于非健康/暂停状态，请先按首页提示恢复后再发",
                        lastUpdatedAtEpochMs = System.currentTimeMillis(),
                    )
                }
            }
            return
        }

        val now = Instant.now()
        val nowMs = now.toEpochMilli()
        val zoneId = DailySchedulePlanner.resolveZoneId(account.timezone)
        val today = ZonedDateTime.ofInstant(now, zoneId).toLocalDate()
        val todayStr = today.toString()

        val enabledTargets = database.targetDao().listEnabledTargets(accountId)
        val todayJobs = database.sendJobDao().listJobsForDate(accountId, todayStr)
        val jobsByTarget = todayJobs.groupBy { it.targetId }

        val targetsToRun: List<ContractTargetEntity> = if (scheduledOnly) {
            val networkDeferCountToday = countNetworkDefers(todayJobs)
            val evaluation = DailySchedulePlanner.evaluateNow(
                now = now,
                account = account,
                targets = enabledTargets,
                todayJobs = todayJobs,
                zoneId = zoneId,
                networkDeferCountToday = networkDeferCountToday,
            )
            evaluation.dueTargets.map { it.target }
        } else {
            enabledTargets.filter { target ->
                SendGuard.canDispatchDaily(jobsByTarget[target.id].orEmpty())
            }
        }

        if (targetsToRun.isEmpty()) {
            if (!scheduledOnly) {
                ExecutionRuntimeBus.update {
                    it.copy(
                        running = false,
                        currentTask = null,
                        lastMessage = "今日所有启用好友已完成续火，或存在待核对记录需人工终裁",
                        lastUpdatedAtEpochMs = System.currentTimeMillis(),
                    )
                }
            }
            return
        }

        val taskLabel = if (scheduledOnly) "正在执行今日自动续火…" else "正在执行今日续火…"
        updateProgressState(taskLabel, 0, targetsToRun.size)
        promoteForeground("正在检查抖音登录态…")

        val outcomes = mutableListOf<BatchTargetOutcome>()
        var succeededCount = 0
        var skippedCount = 0
        var failedCount = 0
        var unknownCount = 0
        var abortedByAuth = false

        executor.withOffscreenSession { session ->
            val authResult = executor.checkAuthAndIdentity(session = session)
            val authTransition = AccountHealthReducer.onAuthProbeResult(
                account = account,
                outcome = authResult.outcome,
                nowEpochMs = System.currentTimeMillis(),
            )
            database.accountDao().update(authTransition.account)

            if (authResult.outcome != AuthProbeOutcome.AUTHENTICATED) {
                abortedByAuth = true
                handleAuthFailureTransition(
                    account = authTransition.account,
                    authResult = authResult,
                    targetsToRun = targetsToRun,
                    todayStr = todayStr,
                    scheduleRecheckIn10Min = authTransition.scheduleRecheckIn10Min,
                    shouldNotifyUrgent = authTransition.shouldNotifyUrgent,
                )
                return@withOffscreenSession
            }

            if (authResult.selfUid != null || authResult.douyinNickname != null) {
                val activeCount = database.targetDao().countActiveTargets(accountId)
                val switchDecision = AccountSwitchGuard.checkLoginIdentity(
                    prevUid = account.selfUid,
                    newUid = authResult.selfUid,
                    prevNick = account.douyinNickname,
                    newNick = authResult.douyinNickname,
                    hasTargets = activeCount > 0,
                )
                if (switchDecision == SwitchDecision.REQUIRE_USER_CHOICE) {
                    abortedByAuth = true
                    database.accountDao().update(
                        authTransition.account.copy(
                            status = AccountStatus.PAUSED,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    ScheduleCoordinator(this@ExecutionForegroundService, database).cancelAllAlarms()
                    ExecutionRuntimeBus.update {
                        it.copy(
                            running = false,
                            currentTask = null,
                            needsLoginChoicePrompt = true,
                            lastMessage = "检测到当前登录的抖音号与历史名单不一致，已暂停发送等待确认",
                            lastUpdatedAtEpochMs = System.currentTimeMillis(),
                        )
                    }
                    sendUrgentBoth(
                        category = UrgentCategory.AUTH_EXPIRED,
                        title = "检测到抖音账号变更",
                        body = "当前网页登录账号与此前绑定账号不一致，已暂停自动续火，请打开 App 确认处理。",
                    )
                    return@withOffscreenSession
                }
                database.accountDao().updateLoginIdentity(
                    accountId = accountId,
                    selfUid = authResult.selfUid ?: account.selfUid,
                    douyinNickname = authResult.douyinNickname ?: account.douyinNickname,
                    verifiedAt = System.currentTimeMillis(),
                )
            }

            val runningAccount = database.accountDao().getAccount(accountId)?.copy(
                status = AccountStatus.RUNNING,
                updatedAt = System.currentTimeMillis(),
            ) ?: authTransition.account.copy(status = AccountStatus.RUNNING)
            database.accountDao().update(runningAccount)
            activeRunningAccountId = accountId
            activeBatchOutcomes = emptyList()

            val minCooldownMs = runningAccount.cooldownMinSec.coerceIn(0, 60) * 1000L
            val maxCooldownMs = runningAccount.cooldownMaxSec.coerceIn(
                runningAccount.cooldownMinSec.coerceIn(0, 60),
                120,
            ) * 1000L

            var batchFinalizedNormally = false
            try {
                for ((index, target) in targetsToRun.withIndex()) {
                    if (index > 0 && maxCooldownMs > 0L) {
                        val waitMs = if (maxCooldownMs == minCooldownMs) {
                            minCooldownMs
                        } else {
                            Random.nextLong(minCooldownMs, maxCooldownMs + 1L)
                        }
                        promoteForeground("冷却等待中（${index}/${targetsToRun.size}）…")
                        delay(waitMs)
                    }

                    val progressText = "正在执行今日续火任务 ${index + 1}/${targetsToRun.size}"
                    promoteForeground(progressText)
                    updateProgressState(progressText, index, targetsToRun.size)

                    val outcome = executeSingleTargetJob(
                        session = session,
                        account = runningAccount,
                        target = target,
                        businessDate = todayStr,
                        slotId = SendGuard.DAILY_SLOT_ID,
                        zoneId = zoneId,
                        overrideText = null,
                        isVisibleActivity = false,
                    )

                    when (outcome.outcomeType) {
                        TargetRunKind.SKIPPED -> skippedCount++
                        TargetRunKind.SUCCEEDED -> {
                            succeededCount++
                            outcomes.add(BatchTargetOutcome(JobStatus.SUCCEEDED))
                        }
                        TargetRunKind.UNKNOWN -> {
                            unknownCount++
                            outcomes.add(
                                BatchTargetOutcome(
                                    status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                                    failureClass = FailureClass.UNKNOWN_OUTCOME,
                                ),
                            )
                        }
                        TargetRunKind.FAILED -> {
                            failedCount++
                            outcomes.add(
                                BatchTargetOutcome(
                                    status = outcome.finalJobStatus ?: JobStatus.FAILED_TERMINAL,
                                    failureClass = outcome.failureClass,
                                    coreSkeletonBroken = outcome.coreSkeletonBroken,
                                ),
                            )
                        }
                    }
                    activeBatchOutcomes = outcomes.toList()

                    updateProgressState(progressText, index + 1, targetsToRun.size)

                    if (outcome.coreSkeletonBroken || outcome.failureClass == FailureClass.DOM_CHANGED) {
                        break
                    }
                }

                val finalizedAccount = ExecutionServiceStoreOps.finalizeBatchAccountIfRunning(
                    accountId = accountId,
                    accountDao = database.accountDao(),
                    outcomes = outcomes,
                    nowMs = System.currentTimeMillis(),
                )
                batchFinalizedNormally = true

                when (finalizedAccount?.status) {
                    AccountStatus.DOM_BROKEN -> sendUrgentBoth(
                        category = UrgentCategory.DOM_BROKEN,
                        title = "抖音页面核心结构异常",
                        body = "检测到聊天页核心元素失效，已停止自动发送。请前往设置页运行「网页结构体检」。",
                    )
                    AccountStatus.COOLDOWN -> sendUrgentBoth(
                        category = UrgentCategory.COOLDOWN,
                        title = "连续失败熔断保护已触发",
                        body = "连续 ${finalizedAccount.failureStreak} 轮批处理均以终态失败收场，请打开 App 核对目标与模板后解除熔断。",
                    )
                    else -> Unit
                }
            } finally {
                withContext(NonCancellable) {
                    activeRunningAccountId = null
                    activeBatchOutcomes = emptyList()
                    if (!batchFinalizedNormally) {
                        ExecutionServiceStoreOps.finalizeBatchAccountIfRunning(
                            accountId = accountId,
                            accountDao = database.accountDao(),
                            outcomes = outcomes,
                            nowMs = System.currentTimeMillis(),
                        )
                    }
                }
            }
        }

        if (!abortedByAuth) {
            val summary = "今日续火完成：成功 $succeededCount / 跳过 $skippedCount / 失败 $failedCount" +
                if (unknownCount > 0) " / 待核对 $unknownCount" else ""
            if (succeededCount + failedCount + unknownCount > 0) {
                localNotifier.notifyDailySummary(
                    title = "抖音续火工执行汇总",
                    body = summary,
                )
                barkNotifier.send(
                    title = "抖音续火工执行汇总",
                    body = summary,
                    level = NotifyLevel.ACTIVE,
                )
            }
            ExecutionRuntimeBus.update {
                it.copy(
                    running = false,
                    currentTask = null,
                    lastMessage = summary,
                    lastUpdatedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
        Log.i(TAG, "Batch finished reason=$reason ok=$succeededCount skip=$skippedCount fail=$failedCount unk=$unknownCount")
    }

    private suspend fun handleAuthFailureTransition(
        account: AccountEntity,
        authResult: AuthCheckResult,
        targetsToRun: List<ContractTargetEntity>,
        todayStr: String,
        scheduleRecheckIn10Min: Boolean,
        shouldNotifyUrgent: Boolean,
    ) {
        val now = Instant.now()
        val nowMs = now.toEpochMilli()

        when (authResult.outcome) {
            AuthProbeOutcome.NETWORK_ERROR -> {
                // 记录一条不递增 retry attemptCount 的 NETWORK 尝试，供 DailySchedulePlanner 顺延 10 分钟（最多 5 次）
                val firstTarget = targetsToRun.firstOrNull()
                if (firstTarget != null) {
                    ExecutionServiceStoreOps.recordNetworkDeferAttempt(
                        accountId = account.id,
                        target = firstTarget,
                        todayStr = todayStr,
                        sendJobDao = database.sendJobDao(),
                        nowMs = nowMs,
                    )
                }
                ExecutionRuntimeBus.update {
                    it.copy(
                        running = false,
                        currentTask = null,
                        lastMessage = "当前网络异常，本轮不计入鉴权失败并等待 10 分钟顺延",
                        lastUpdatedAtEpochMs = nowMs,
                    )
                }
            }

            AuthProbeOutcome.VERIFICATION_REQUIRED -> {
                if (shouldNotifyUrgent) {
                    sendUrgentBoth(
                        category = UrgentCategory.VERIFICATION_REQUIRED,
                        title = "抖音触发身份验证",
                        body = "检测到风控身份验证弹窗，已立即停止发送。请打开 App 进入保留态验证页完成校验。",
                    )
                }
                ExecutionRuntimeBus.update {
                    it.copy(
                        running = false,
                        currentTask = null,
                        lastMessage = "检测到身份验证风控弹窗，已停发等待人工完成验证",
                        lastUpdatedAtEpochMs = nowMs,
                    )
                }
            }

            AuthProbeOutcome.UNAUTHENTICATED -> {
                if (authResult.skeletonMissingDespiteSession) {
                    val broken = account.copy(
                        status = AccountStatus.DOM_BROKEN,
                        authFailCount = 0,
                        updatedAt = nowMs,
                    )
                    database.accountDao().update(broken)
                    sendUrgentBoth(
                        category = UrgentCategory.DOM_BROKEN,
                        title = "抖音页面核心结构异常",
                        body = "已登录状态下未能渲染会话列表骨架，请到设置页运行「网页结构体检」。",
                    )
                    ExecutionRuntimeBus.update {
                        it.copy(
                            running = false,
                            currentTask = null,
                            lastMessage = "检测到页面核心骨架未渲染，已标记结构异常待体检",
                            lastUpdatedAtEpochMs = nowMs,
                        )
                    }
                    return
                }

                if (scheduleRecheckIn10Min) {
                    val coordinator = ScheduleCoordinator(this@ExecutionForegroundService, database)
                    coordinator.rescheduleAll(now)
                    coordinator.scheduleAuthRecheckIn10Minutes(now)
                    ExecutionRuntimeBus.update {
                        it.copy(
                            running = false,
                            currentTask = null,
                            lastMessage = "首次未检出登录态，已预约 10 分钟后自动复判",
                            lastUpdatedAtEpochMs = nowMs,
                        )
                    }
                } else if (shouldNotifyUrgent) {
                    sendUrgentBoth(
                        category = UrgentCategory.AUTH_EXPIRED,
                        title = "抖音登录态已失效",
                        body = "连续两次确认未检测到已登录会话列表，请打开 App 重新扫码登录。",
                    )
                    ExecutionRuntimeBus.update {
                        it.copy(
                            running = false,
                            currentTask = null,
                            lastMessage = "连续 2 次确认登录态失效，请重新扫码",
                            lastUpdatedAtEpochMs = nowMs,
                        )
                    }
                }
            }

            AuthProbeOutcome.AUTHENTICATED -> Unit
        }
    }

    private suspend fun runAuthRecheckCommand(accountId: String) {
        val acquired = ExecutionMutex.tryWithLock("auth_recheck", SCHEDULED_LOCK_WAIT_MS) {
            val account = database.accountDao().getAccount(accountId) ?: return@tryWithLock
            if (!account.enabled || account.status != AccountStatus.HEALTHY) return@tryWithLock

            promoteForeground("正在复判抖音登录状态…")
            updateProgressState("正在复判登录态…", 0, 1)

            val authResult = executor.checkAuthAndIdentity()
            val now = Instant.now()
            val nowMs = now.toEpochMilli()
            val transition = AccountHealthReducer.onAuthProbeResult(
                account = account,
                outcome = authResult.outcome,
                nowEpochMs = nowMs,
            )
            database.accountDao().update(transition.account)

            when (authResult.outcome) {
                AuthProbeOutcome.AUTHENTICATED -> {
                    if (authResult.selfUid != null || authResult.douyinNickname != null) {
                        database.accountDao().updateLoginIdentity(
                            accountId = accountId,
                            selfUid = authResult.selfUid ?: account.selfUid,
                            douyinNickname = authResult.douyinNickname ?: account.douyinNickname,
                            verifiedAt = nowMs,
                        )
                    }
                    ExecutionRuntimeBus.update {
                        it.copy(
                            running = false,
                            currentTask = null,
                            lastMessage = "登录态复判通过，已恢复正常调度",
                            lastUpdatedAtEpochMs = nowMs,
                        )
                    }
                    executeBatchLocked(
                        accountId = accountId,
                        scheduledOnly = true,
                        reason = "auth_recheck_recovered",
                    )
                }
                AuthProbeOutcome.VERIFICATION_REQUIRED -> {
                    sendUrgentBoth(
                        category = UrgentCategory.VERIFICATION_REQUIRED,
                        title = "抖音触发身份验证",
                        body = "复判期间检测到安全验证弹窗，请打开 App 完成身份验证。",
                    )
                }
                AuthProbeOutcome.UNAUTHENTICATED -> {
                    if (transition.shouldNotifyUrgent) {
                        sendUrgentBoth(
                            category = UrgentCategory.AUTH_EXPIRED,
                            title = "抖音登录态已失效",
                            body = "10 分钟延迟复判仍未检出登录态，请重新扫码登录。",
                        )
                    } else if (transition.scheduleRecheckIn10Min) {
                        val coordinator = ScheduleCoordinator(this@ExecutionForegroundService, database)
                        coordinator.rescheduleAll(now)
                        coordinator.scheduleAuthRecheckIn10Minutes(now)
                    }
                }
                AuthProbeOutcome.NETWORK_ERROR -> Unit
            }
        }
        if (acquired == null) {
            Log.i(TAG, "Auth recheck deferred: ExecutionMutex busy")
        }
    }

    private suspend fun runSingleSendCommand(
        accountId: String,
        targetId: String,
        overrideText: String?,
    ) {
        val acquired = ExecutionMutex.tryWithLock("single_send", MANUAL_LOCK_WAIT_MS) {
            val account = database.accountDao().getAccount(accountId) ?: return@tryWithLock
            val target = database.targetDao().getById(targetId)
            if (target == null || target.deletedAt != null) {
                ExecutionRuntimeBus.update {
                    it.copy(running = false, currentTask = null, lastMessage = "好友不存在或已删除")
                }
                return@tryWithLock
            }

            val zoneId = DailySchedulePlanner.resolveZoneId(account.timezone)
            val todayStr = ZonedDateTime.ofInstant(Instant.now(), zoneId).toLocalDate().toString()
            val todayJobs = database.sendJobDao().listJobsForTargetDate(accountId, targetId, todayStr)
            if (SendGuard.checkManualSend(todayJobs) == ManualSendDecision.BLOCKED_UNKNOWN_OR_INFLIGHT) {
                ExecutionRuntimeBus.update {
                    it.copy(
                        running = false,
                        currentTask = null,
                        lastMessage = "请先在首页横幅核对今日未知发送结果",
                        lastUpdatedAtEpochMs = System.currentTimeMillis(),
                    )
                }
                return@tryWithLock
            }

            promoteForeground("正在执行单发测试…")
            updateProgressState("正在执行单发测试…", 0, 1)

            executor.withOffscreenSession { session ->
                val auth = executor.checkAuthAndIdentity(session = session)
                val transition = AccountHealthReducer.onAuthProbeResult(
                    account = account,
                    outcome = auth.outcome,
                    nowEpochMs = System.currentTimeMillis(),
                )
                database.accountDao().update(transition.account)
                if (auth.outcome != AuthProbeOutcome.AUTHENTICATED) {
                    if (transition.shouldNotifyUrgent) {
                        val cat = if (auth.outcome == AuthProbeOutcome.VERIFICATION_REQUIRED) {
                            UrgentCategory.VERIFICATION_REQUIRED
                        } else {
                            UrgentCategory.AUTH_EXPIRED
                        }
                        sendUrgentBoth(cat, "登录校验未通过", auth.detail ?: "请检查登录态或网络")
                    }
                    ExecutionRuntimeBus.update {
                        it.copy(
                            running = false,
                            currentTask = null,
                            lastMessage = auth.detail ?: "登录态或网络检查未通过",
                            lastUpdatedAtEpochMs = System.currentTimeMillis(),
                        )
                    }
                    return@withOffscreenSession
                }

                val slotId = SendGuard.manualSlotId(System.currentTimeMillis())
                val outcome = executeSingleTargetJob(
                    session = session,
                    account = transition.account,
                    target = target,
                    businessDate = todayStr,
                    slotId = slotId,
                    zoneId = zoneId,
                    overrideText = overrideText,
                    isVisibleActivity = false,
                )

                val latestAccount = database.accountDao().getAccount(accountId) ?: transition.account
                if (outcome.outcomeType == TargetRunKind.SUCCEEDED) {
                    database.accountDao().update(
                        AccountHealthReducer.onSingleSendSucceeded(
                            latestAccount,
                            System.currentTimeMillis(),
                        ),
                    )
                } else if (outcome.coreSkeletonBroken || outcome.failureClass == FailureClass.DOM_CHANGED) {
                    database.accountDao().update(
                        AccountHealthReducer.onBatchFinished(
                            latestAccount,
                            listOf(
                                BatchTargetOutcome(
                                    status = outcome.finalJobStatus ?: JobStatus.FAILED_TERMINAL,
                                    failureClass = outcome.failureClass,
                                    coreSkeletonBroken = true,
                                ),
                            ),
                        ),
                    )
                    sendUrgentBoth(
                        UrgentCategory.DOM_BROKEN,
                        "抖音页面核心结构异常",
                        "单发测试检测到核心定位缺失，请到设置页运行「网页结构体检」。",
                    )
                }

                ExecutionRuntimeBus.update {
                    it.copy(
                        running = false,
                        currentTask = null,
                        progressCurrent = 1,
                        progressTotal = 1,
                        lastMessage = outcome.message,
                        lastUpdatedAtEpochMs = System.currentTimeMillis(),
                    )
                }
            }
        }
        if (acquired == null) {
            ExecutionRuntimeBus.update {
                it.copy(
                    running = false,
                    currentTask = null,
                    lastMessage = "当前有其他 WebView 任务正在运行，请稍后再试",
                    lastUpdatedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun runManualScanCommand(accountId: String) {
        val acquired = ExecutionMutex.tryWithLock("manual_scan", MANUAL_LOCK_WAIT_MS) {
            val scanId = UUID.randomUUID().toString()
            val startedAt = System.currentTimeMillis()
            val scanEntity = ScanRunEntity(
                id = scanId,
                accountId = accountId,
                source = "manual",
                status = ScanRunStatus.RUNNING,
                startedAt = startedAt,
            )
            database.scanRunDao().insert(scanEntity)

            promoteForeground("正在扫描抖音会话与官方火花…")
            ExecutionRuntimeBus.update {
                it.copy(
                    running = true,
                    currentTask = "正在扫描抖音会话…",
                    scanDiscoveredCount = 0,
                    lastUpdatedAtEpochMs = startedAt,
                )
            }

            val allTargets = database.targetDao().listActiveTargets(accountId)
            val result = executor.scanAllChats(
                existingTargets = allTargets,
                scanRunId = scanId,
                onProgress = { count ->
                    promoteForeground("已扫描发现 $count 条会话…")
                    ExecutionRuntimeBus.update {
                        it.copy(
                            scanDiscoveredCount = count,
                            lastUpdatedAtEpochMs = System.currentTimeMillis(),
                        )
                    }
                },
            )

            val finishedAt = System.currentTimeMillis()
            when (result) {
                is ScanExecutionResult.Success -> {
                    val encoded = ScanReconciliationCodec.encode(result.snapshot)
                    database.scanRunDao().update(
                        scanEntity.copy(
                            status = ScanRunStatus.COMPLETED,
                            discoveredCount = result.snapshot.discoveredCount,
                            sparkCount = result.snapshot.sparkCount,
                            reconciliationJson = encoded,
                            finishedAt = finishedAt,
                        ),
                    )
                    database.accountDao().getAccount(accountId)?.let { acc ->
                        val authTransition = AccountHealthReducer.onAuthProbeResult(
                            acc,
                            AuthProbeOutcome.AUTHENTICATED,
                            finishedAt,
                        )
                        database.accountDao().update(authTransition.account)
                        if (result.selfUid != null || result.douyinNickname != null) {
                            database.accountDao().updateLoginIdentity(
                                accountId = accountId,
                                selfUid = result.selfUid ?: acc.selfUid,
                                douyinNickname = result.douyinNickname ?: acc.douyinNickname,
                                verifiedAt = finishedAt,
                            )
                        }
                    }
                    ExecutionRuntimeBus.update {
                        it.copy(
                            running = false,
                            currentTask = null,
                            scanDiscoveredCount = result.snapshot.discoveredCount,
                            latestScanSnapshot = result.snapshot,
                            lastMessage = "扫描完成：共 ${result.snapshot.discoveredCount} 条会话，含官方火花 ${result.snapshot.sparkCount} 条",
                            lastUpdatedAtEpochMs = finishedAt,
                        )
                    }
                }

                is ScanExecutionResult.AuthOrGateFailure -> {
                    database.scanRunDao().update(
                        scanEntity.copy(
                            status = ScanRunStatus.FAILED,
                            finishedAt = finishedAt,
                        ),
                    )
                    database.accountDao().getAccount(accountId)?.let { acc ->
                        val transition = AccountHealthReducer.onAuthProbeResult(acc, result.outcome, finishedAt)
                        database.accountDao().update(transition.account)
                        if (transition.shouldNotifyUrgent) {
                            val cat = if (result.outcome == AuthProbeOutcome.VERIFICATION_REQUIRED) {
                                UrgentCategory.VERIFICATION_REQUIRED
                            } else {
                                UrgentCategory.AUTH_EXPIRED
                            }
                            sendUrgentBoth(cat, "扫描前登录校验未通过", result.detail)
                        }
                    }
                    ExecutionRuntimeBus.update {
                        it.copy(
                            running = false,
                            currentTask = null,
                            lastMessage = result.detail,
                            lastUpdatedAtEpochMs = finishedAt,
                        )
                    }
                }

                is ScanExecutionResult.Error -> {
                    database.scanRunDao().update(
                        scanEntity.copy(
                            status = ScanRunStatus.FAILED,
                            finishedAt = finishedAt,
                        ),
                    )
                    ExecutionRuntimeBus.update {
                        it.copy(
                            running = false,
                            currentTask = null,
                            lastMessage = result.detail,
                            lastUpdatedAtEpochMs = finishedAt,
                        )
                    }
                }
            }
        }
        if (acquired == null) {
            ExecutionRuntimeBus.update {
                it.copy(
                    running = false,
                    currentTask = null,
                    lastMessage = "当前有其他 WebView 任务正在运行，请稍后再试",
                    lastUpdatedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun runSingleIdentityRepairCommand(
        accountId: String,
        targetId: String,
    ) {
        val acquired = ExecutionMutex.tryWithLock("identity_repair", MANUAL_LOCK_WAIT_MS) {
            val target = database.targetDao().getById(targetId)
            if (target == null || target.deletedAt != null) {
                ExecutionRuntimeBus.update {
                    it.copy(running = false, currentTask = null, lastMessage = "目标不存在或已删除")
                }
                return@tryWithLock
            }

            promoteForeground("正在补采目标唯一身份…")
            updateProgressState("正在补采目标身份…", 0, 1)

            executor.withOffscreenSession { session ->
                val auth = executor.checkAuthAndIdentity(session = session)
                if (auth.outcome != AuthProbeOutcome.AUTHENTICATED) {
                    ExecutionRuntimeBus.update {
                        it.copy(
                            running = false,
                            currentTask = null,
                            lastMessage = auth.detail ?: "当前未登录，无法补采身份",
                            lastUpdatedAtEpochMs = System.currentTimeMillis(),
                        )
                    }
                    return@withOffscreenSession
                }

                val repair = executor.repairTargetIdentityInSession(session, target)
                if (repair.repaired) {
                    val nowMs = System.currentTimeMillis()
                    try {
                        database.targetDao().update(
                            target.copy(
                                platformUserId = repair.platformUserId ?: target.platformUserId,
                                platformHandle = repair.platformHandle ?: target.platformHandle,
                                platformConversationId = repair.platformConversationId ?: target.platformConversationId,
                                conversationType = repair.conversationType ?: target.conversationType,
                                updatedAt = nowMs,
                            ),
                        )
                    } catch (_: Throwable) {
                        // 唯一索引冲突时保留原记录，绝不覆写其它目标
                    }
                }

                ExecutionRuntimeBus.update {
                    it.copy(
                        running = false,
                        currentTask = null,
                        progressCurrent = 1,
                        progressTotal = 1,
                        lastMessage = repair.detail,
                        lastUpdatedAtEpochMs = System.currentTimeMillis(),
                    )
                }
            }
        }
        if (acquired == null) {
            ExecutionRuntimeBus.update {
                it.copy(
                    running = false,
                    currentTask = null,
                    lastMessage = "当前有其他 WebView 任务正在运行，请稍后再试",
                    lastUpdatedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun runStructureProbeCommand(accountId: String) {
        val acquired = ExecutionMutex.tryWithLock("structure_probe", MANUAL_LOCK_WAIT_MS) {
            promoteForeground("正在执行只读网页结构体检…")
            updateProgressState("正在执行网页结构体检…", 0, 1)
            val sampleTarget = database.targetDao().listEnabledTargets(accountId).firstOrNull()
            val report = executor.probeStructure(sampleTarget = sampleTarget)
            ExecutionRuntimeBus.update {
                it.copy(
                    running = false,
                    currentTask = null,
                    progressCurrent = 1,
                    progressTotal = 1,
                    latestProbeReport = report,
                    lastMessage = report.summary,
                    lastUpdatedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
        if (acquired == null) {
            ExecutionRuntimeBus.update {
                it.copy(
                    running = false,
                    currentTask = null,
                    lastMessage = "当前有其他 WebView 任务正在运行，请稍后再试",
                    lastUpdatedAtEpochMs = System.currentTimeMillis(),
                )
            }
        }
    }

    private enum class TargetRunKind {
        SKIPPED,
        SUCCEEDED,
        FAILED,
        UNKNOWN,
    }

    private data class TargetRunOutcome(
        val outcomeType: TargetRunKind,
        val finalJobStatus: JobStatus? = null,
        val failureClass: FailureClass? = null,
        val coreSkeletonBroken: Boolean = false,
        val message: String = "",
    )

    private suspend fun executeSingleTargetJob(
        session: DouyinWebExecutor.SessionHandle,
        account: AccountEntity,
        target: ContractTargetEntity,
        businessDate: String,
        slotId: String,
        zoneId: ZoneId,
        overrideText: String?,
        isVisibleActivity: Boolean,
    ): TargetRunOutcome {
        val sendJobDao = database.sendJobDao()
        val freshTarget = database.targetDao().getById(target.id)
        if (freshTarget == null || freshTarget.deletedAt != null ||
            (slotId == SendGuard.DAILY_SLOT_ID && !freshTarget.enabled)
        ) {
            return TargetRunOutcome(TargetRunKind.SKIPPED, message = "好友已暂停或删除")
        }

        val todayJobs = sendJobDao.listJobsForTargetDate(account.id, freshTarget.id, businessDate)
        if (slotId == SendGuard.DAILY_SLOT_ID) {
            if (!SendGuard.canDispatchDaily(todayJobs)) {
                return TargetRunOutcome(TargetRunKind.SKIPPED, message = "当日已完成、取消或待核对，已跳过")
            }
        } else {
            if (SendGuard.hasBlockingUnknownOrInflight(todayJobs)) {
                return TargetRunOutcome(TargetRunKind.SKIPPED, message = "存在待核对记录，已硬禁止拦截")
            }
        }

        val resolved = resolveSendPayload(
            target = freshTarget,
            overrideText = overrideText,
            zoneId = zoneId,
        ) ?: return TargetRunOutcome(TargetRunKind.SKIPPED, message = "未绑定可用模板或文案池今日无候选")

        val nowMs = System.currentTimeMillis()
        val allocated = ExecutionServiceStoreOps.allocateJobAndAttemptForSend(
            accountId = account.id,
            target = freshTarget,
            templateId = resolved.templateId,
            businessDate = businessDate,
            slotId = slotId,
            sendJobDao = sendJobDao,
            nowMs = nowMs,
        ) ?: return TargetRunOutcome(TargetRunKind.SKIPPED, message = "当日任务状态不可再次派发")

        if (resolved.poolItemId != null) {
            // 仅在真正准备触发发送前更新文案池 LRU，列表预览绝不消耗
            database.templateDao().markPoolItemUsed(resolved.poolItemId, nowMs)
        }

        var currentAttempt = allocated.attempt
        var currentJob = allocated.job
        val attemptRowNo = allocated.nextAttemptRowNo

        val execResult = try {
            if (resolved.sticker != null) {
                executor.sendSticker(
                    target = freshTarget,
                    stickerName = resolved.sticker.stickerName,
                    stickerLabel = resolved.sticker.stickerLabel,
                    stickerCategory = resolved.sticker.stickerCategory,
                    fallbackIndex = resolved.sticker.fallbackIndex,
                    session = session,
                    onStageSending = {
                        val ts = System.currentTimeMillis()
                        activeConfirmingJobId = currentJob.id
                        activeConfirmingAttemptNo = attemptRowNo
                        currentJob = currentJob.copy(status = JobStatus.SENDING, updatedAt = ts)
                        currentAttempt = currentAttempt.copy(stage = "sending")
                        sendJobDao.updateJob(currentJob)
                        sendJobDao.updateAttempt(currentAttempt)
                    },
                    onStageConfirming = {
                        val ts = System.currentTimeMillis()
                        activeConfirmingJobId = currentJob.id
                        activeConfirmingAttemptNo = attemptRowNo
                        currentJob = currentJob.copy(status = JobStatus.CONFIRMING, updatedAt = ts)
                        currentAttempt = currentAttempt.copy(stage = "confirming", inputMethodUsed = "sticker_click")
                        sendJobDao.updateJob(currentJob)
                        sendJobDao.updateAttempt(currentAttempt)
                    },
                )
            } else {
                executor.sendText(
                    target = freshTarget,
                    text = resolved.text.orEmpty(),
                    isVisibleActivity = isVisibleActivity,
                    session = session,
                    onStageSending = {
                        val ts = System.currentTimeMillis()
                        activeConfirmingJobId = currentJob.id
                        activeConfirmingAttemptNo = attemptRowNo
                        currentJob = currentJob.copy(status = JobStatus.SENDING, updatedAt = ts)
                        currentAttempt = currentAttempt.copy(stage = "sending")
                        sendJobDao.updateJob(currentJob)
                        sendJobDao.updateAttempt(currentAttempt)
                    },
                    onStageConfirming = {
                        val ts = System.currentTimeMillis()
                        activeConfirmingJobId = currentJob.id
                        activeConfirmingAttemptNo = attemptRowNo
                        currentJob = currentJob.copy(status = JobStatus.CONFIRMING, updatedAt = ts)
                        currentAttempt = currentAttempt.copy(stage = "confirming", inputMethodUsed = "js_execCommand")
                        sendJobDao.updateJob(currentJob)
                        sendJobDao.updateAttempt(currentAttempt)
                    },
                )
            }
        } catch (ce: CancellationException) {
            if (currentJob.status == JobStatus.CONFIRMING || currentJob.status == JobStatus.SENDING) {
                withContext(NonCancellable) {
                    val cancelMs = System.currentTimeMillis()
                    sendJobDao.updateJobOutcome(
                        id = currentJob.id,
                        status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                        failureClass = FailureClass.UNKNOWN_OUTCOME,
                        summaryDetail = "执行协程被取消时任务已进入发送确认期，请人工核对结果",
                        confirmedAt = null,
                        updatedAt = cancelMs,
                    )
                    sendJobDao.updateAttempt(
                        currentAttempt.copy(
                            status = AttemptStatus.FAILED,
                            failureClass = FailureClass.UNKNOWN_OUTCOME,
                            finishedAt = cancelMs,
                        ),
                    )
                }
            }
            throw ce
        } finally {
            activeConfirmingJobId = null
            activeConfirmingAttemptNo = null
        }

        val doneMs = System.currentTimeMillis()
        backfillObservedIdentityIfNeeded(freshTarget, execResult, doneMs)

        val finalStatus = when (execResult.jobStatus) {
            JobStatus.FAILED_RETRYABLE -> SendGuard.classifyRetryableFailure(allocated.nextRetryAttemptCount)
            else -> execResult.jobStatus
        }
        val confirmedAt = if (finalStatus == JobStatus.SUCCEEDED) doneMs else null

        sendJobDao.updateJob(
            currentJob.copy(
                status = finalStatus,
                failureClass = execResult.failureClass,
                summaryDetail = execResult.summaryDetail,
                confirmedAt = confirmedAt,
                updatedAt = doneMs,
            ),
        )
        sendJobDao.updateAttempt(
            currentAttempt.copy(
                status = if (finalStatus == JobStatus.SUCCEEDED) AttemptStatus.SUCCEEDED else AttemptStatus.FAILED,
                failureClass = execResult.failureClass,
                inputMethodUsed = execResult.inputMethodUsed ?: currentAttempt.inputMethodUsed,
                errorCode = execResult.failureClass?.wireValue,
                finishedAt = doneMs,
            ),
        )

        return when (finalStatus) {
            JobStatus.SUCCEEDED -> {
                database.targetDao().recordSendConfirmed(freshTarget.id, doneMs)
                database.accountDao().updateLastSuccessAt(account.id, doneMs)
                TargetRunOutcome(
                    outcomeType = TargetRunKind.SUCCEEDED,
                    finalJobStatus = JobStatus.SUCCEEDED,
                    message = execResult.summaryDetail ?: "发送已确认成功",
                )
            }
            JobStatus.UNKNOWN_REQUIRES_REVIEW -> {
                sendUrgentBoth(
                    category = UrgentCategory.UNKNOWN_REQUIRES_REVIEW,
                    title = "发送结果未明，待人工核对",
                    body = "已触发发送动作但页面确认超时，系统已硬禁止自动重发，请打开 App 核对。",
                )
                TargetRunOutcome(
                    outcomeType = TargetRunKind.UNKNOWN,
                    finalJobStatus = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                    failureClass = FailureClass.UNKNOWN_OUTCOME,
                    message = execResult.summaryDetail ?: "发送结果未确认，已阻止重发",
                )
            }
            else -> TargetRunOutcome(
                outcomeType = TargetRunKind.FAILED,
                finalJobStatus = finalStatus,
                failureClass = execResult.failureClass,
                coreSkeletonBroken = execResult.coreSkeletonBroken,
                message = execResult.summaryDetail ?: "发送失败",
            )
        }
    }

    private data class StickerPayload(
        val stickerName: String,
        val stickerLabel: String?,
        val stickerCategory: String?,
        val fallbackIndex: Int?,
    )

    private data class ResolvedPayload(
        val templateId: String?,
        val text: String? = null,
        val poolItemId: String? = null,
        val sticker: StickerPayload? = null,
    )

    private suspend fun resolveSendPayload(
        target: ContractTargetEntity,
        overrideText: String?,
        zoneId: ZoneId,
    ): ResolvedPayload? {
        if (!overrideText.isNullOrBlank()) {
            return ResolvedPayload(
                templateId = null,
                text = renderTemplateMessage(
                    template = overrideText,
                    targetName = target.nicknameSnapshot,
                    streakDays = target.streakDays,
                    zoneId = zoneId,
                ),
            )
        }
        val templateId = target.templateId ?: return null
        val template: MessageTemplateEntity = database.templateDao().getTemplateById(templateId)
            ?: return null
        if (!template.enabled) return null

        return when (template.type) {
            TemplateType.TEXT -> {
                val content = template.content.trim()
                if (content.isEmpty()) null else ResolvedPayload(
                    templateId = template.id,
                    text = renderTemplateMessage(
                        template = content,
                        targetName = target.nicknameSnapshot,
                        streakDays = target.streakDays,
                        zoneId = zoneId,
                    ),
                )
            }
            TemplateType.POOL -> {
                val items = database.templateDao().listEnabledPoolItems(template.id)
                val weekdayIndex = TemplatePoolPicker.weekdayIndexOf(ZonedDateTime.now(zoneId).dayOfWeek)
                val picked = TemplatePoolPicker.pick(items, weekdayIndex) ?: return null
                ResolvedPayload(
                    templateId = template.id,
                    text = renderTemplateMessage(
                        template = picked.content,
                        targetName = target.nicknameSnapshot,
                        streakDays = target.streakDays,
                        zoneId = zoneId,
                    ),
                    poolItemId = picked.id,
                )
            }
            TemplateType.STICKER -> {
                val name = template.stickerName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                ResolvedPayload(
                    templateId = template.id,
                    sticker = StickerPayload(
                        stickerName = name,
                        stickerLabel = template.stickerLabel,
                        stickerCategory = template.stickerCategory,
                        fallbackIndex = template.stickerFallbackIndex,
                    ),
                )
            }
        }
    }

    private suspend fun backfillObservedIdentityIfNeeded(
        target: ContractTargetEntity,
        result: SendExecutionResult,
        nowMs: Long,
    ) {
        val newUid = if (target.platformUserId == null) result.observedPlatformUserId else null
        val newHandle = if (target.platformHandle == null) result.observedPlatformHandle else null
        val newCid = if (target.platformConversationId == null) result.observedPlatformConversationId else null
        val newType = if (target.conversationType == null) result.observedConversationType else null
        if (newUid == null && newCid == null && newHandle == null) return
        try {
            database.targetDao().update(
                target.copy(
                    platformUserId = newUid ?: target.platformUserId,
                    platformHandle = newHandle ?: target.platformHandle,
                    platformConversationId = newCid ?: target.platformConversationId,
                    conversationType = newType ?: target.conversationType,
                    updatedAt = nowMs,
                ),
            )
        } catch (_: Throwable) {
        }
    }

    private suspend fun countNetworkDefers(todayJobs: List<SendJobEntity>): Int {
        var count = 0
        val sendJobDao = database.sendJobDao()
        for (job in todayJobs) {
            val attempts = sendJobDao.listAttemptsForJob(job.id)
            val failedNetwork = attempts.count {
                it.failureClass == FailureClass.NETWORK && it.status == AttemptStatus.FAILED
            }
            count += if (failedNetwork > 0) {
                failedNetwork
            } else if (job.status == JobStatus.FAILED_RETRYABLE && job.failureClass == FailureClass.NETWORK) {
                1
            } else {
                0
            }
        }
        return count
    }

    private suspend fun sendUrgentBoth(
        category: UrgentCategory,
        title: String,
        body: String,
    ) {
        localNotifier.notifyUrgentAlert(category, title, body)
        barkNotifier.send(title = title, body = body, level = NotifyLevel.TIME_SENSITIVE)
    }

    private fun updateProgressState(task: String, current: Int, total: Int) {
        ExecutionRuntimeBus.update {
            it.copy(
                running = true,
                currentTask = task,
                progressCurrent = current,
                progressTotal = total,
                lastUpdatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    private fun convergeInterruptState(includeRunningAccount: Boolean) {
        val jobId = activeConfirmingJobId
        val attemptNo = activeConfirmingAttemptNo
        val runningAccId = if (includeRunningAccount) activeRunningAccountId else null
        val outcomesSnapshot = if (includeRunningAccount) activeBatchOutcomes else emptyList()
        if (jobId == null && runningAccId == null) return

        activeConfirmingJobId = null
        activeConfirmingAttemptNo = null
        if (includeRunningAccount) {
            activeRunningAccountId = null
            activeBatchOutcomes = emptyList()
        }

        try {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(2_000L) {
                    val nowMs = System.currentTimeMillis()
                    if (jobId != null) {
                        val dao = database.sendJobDao()
                        val job = dao.getJobById(jobId)
                        if (job != null && (job.status == JobStatus.CONFIRMING || job.status == JobStatus.SENDING)) {
                            dao.updateJobOutcome(
                                id = job.id,
                                status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                                failureClass = FailureClass.UNKNOWN_OUTCOME,
                                summaryDetail = "服务中断时任务已进入发送确认期，请人工核对结果",
                                confirmedAt = null,
                                updatedAt = nowMs,
                            )
                            if (attemptNo != null) {
                                dao.listAttemptsForJob(job.id)
                                    .firstOrNull { it.attemptNo == attemptNo && it.status == AttemptStatus.RUNNING }
                                    ?.let { att ->
                                        dao.updateAttempt(
                                            att.copy(
                                                status = AttemptStatus.FAILED,
                                                failureClass = FailureClass.UNKNOWN_OUTCOME,
                                                finishedAt = nowMs,
                                            ),
                                        )
                                    }
                            }
                            localNotifier.notifyUrgentAlert(
                                category = UrgentCategory.UNKNOWN_REQUIRES_REVIEW,
                                title = "任务被中断，待核对发送结果",
                                body = "执行服务被中止时已有消息进入确认期，已转待复核状态并禁止自动重发。",
                            )
                        }
                    }
                    if (runningAccId != null) {
                        ExecutionServiceStoreOps.finalizeBatchAccountIfRunning(
                            accountId = runningAccId,
                            accountDao = database.accountDao(),
                            outcomes = outcomesSnapshot,
                            nowMs = nowMs,
                        )
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun armHardTimeout() {
        hardTimeoutJob?.cancel()
        hardTimeoutJob = serviceScope.launch {
            delay(HARD_TIMEOUT_MS)
            Log.w(TAG, "ExecutionForegroundService hit 10m hard timeout")
            convergeInterruptState(includeRunningAccount = true)
            workerJob?.cancel()
            releaseWakeLock()
            withContext(Dispatchers.Main) {
                ServiceCompat.stopForeground(
                    this@ExecutionForegroundService,
                    ServiceCompat.STOP_FOREGROUND_REMOVE,
                )
                stopSelf()
            }
        }
    }

    private fun promoteForeground(contentText: String) {
        val notification = buildNotification(contentText)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(contentText: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("抖音续火工执行中")
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun ensureProgressChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台续火执行进度",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                description = "定时自动续火与手动扫描期间的短时前台保活提示"
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        lock.setReferenceCounted(false)
        lock.acquire(HARD_TIMEOUT_MS)
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Throwable) {
        } finally {
            wakeLock = null
        }
    }

    private fun renderTemplateMessage(
        template: String,
        targetName: String,
        streakDays: Int,
        zoneId: ZoneId,
    ): String {
        val now = ZonedDateTime.now(zoneId)
        val weekdayChar = "一二三四五六日"[now.dayOfWeek.value - 1]
        val values = mapOf(
            "target_name" to targetName,
            "streak_days" to streakDays.toString(),
            "weekday" to "星期$weekdayChar",
            "date" to DATE_FORMATTER.format(now),
        )
        return TEMPLATE_TOKEN_REGEX.replace(template) { match ->
            val key = match.groupValues[1]
            values[key] ?: match.value
        }
    }

    companion object {
        private const val TAG = "ExecutionFgService"
        private const val CHANNEL_ID = "flame_execution_progress"
        private const val NOTIFICATION_ID = 2002
        private const val WAKE_LOCK_TAG = "FlameContractor::ExecutionWakeLock"
        private const val HARD_TIMEOUT_MS = 10L * 60L * 1000L
        private const val SCHEDULED_LOCK_WAIT_MS = 30_000L
        private const val MANUAL_LOCK_WAIT_MS = 10_000L

        const val ACTION_RUN_MANUAL_BATCH = "io.github.oinzc.flamecontractor.action.RUN_MANUAL_BATCH"
        const val ACTION_RUN_SINGLE_SEND = "io.github.oinzc.flamecontractor.action.RUN_SINGLE_SEND"
        const val ACTION_RUN_MANUAL_SCAN = "io.github.oinzc.flamecontractor.action.RUN_MANUAL_SCAN"
        const val ACTION_REPAIR_IDENTITY = "io.github.oinzc.flamecontractor.action.REPAIR_IDENTITY"
        const val ACTION_PROBE_STRUCTURE = "io.github.oinzc.flamecontractor.action.PROBE_STRUCTURE"

        const val EXTRA_TARGET_ID = "extra_target_id"
        const val EXTRA_OVERRIDE_TEXT = "extra_override_text"

        private val TEMPLATE_TOKEN_REGEX = Regex("""\{\{\s*([a-z_]+)\s*\}\}""")
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd")

        fun attachDatabase(database: FlameDatabase) {
            ServiceDatabaseHolder.attach(database)
        }

        fun enqueueScheduledRun(
            context: Context,
            accountId: String = DEFAULT_ACCOUNT_ID,
            reason: String = "manual_trigger",
        ): Boolean {
            val intent = ExecutionDispatchContract.createExecutionServiceIntent(
                context = context,
                action = ExecutionDispatchContract.ACTION_RUN_SCHEDULED,
                accountId = accountId,
                triggerReason = reason,
            )
            return startServiceSafe(context, intent)
        }

        fun enqueueManualBatch(
            context: Context,
            accountId: String = DEFAULT_ACCOUNT_ID,
        ): Boolean = startAction(
            context = context,
            action = ACTION_RUN_MANUAL_BATCH,
            accountId = accountId,
            reason = "ui_manual_batch",
        )

        fun enqueueSingleSend(
            context: Context,
            targetId: String,
            overrideText: String? = null,
            accountId: String = DEFAULT_ACCOUNT_ID,
        ): Boolean = startAction(
            context = context,
            action = ACTION_RUN_SINGLE_SEND,
            accountId = accountId,
            reason = "ui_single_send",
        ) {
            putExtra(EXTRA_TARGET_ID, targetId)
            if (overrideText != null) {
                putExtra(EXTRA_OVERRIDE_TEXT, overrideText)
            }
        }

        fun enqueueManualScan(
            context: Context,
            accountId: String = DEFAULT_ACCOUNT_ID,
        ): Boolean = startAction(
            context = context,
            action = ACTION_RUN_MANUAL_SCAN,
            accountId = accountId,
            reason = "ui_manual_scan",
        )

        fun enqueueSingleIdentityRepair(
            context: Context,
            targetId: String,
            accountId: String = DEFAULT_ACCOUNT_ID,
        ): Boolean = startAction(
            context = context,
            action = ACTION_REPAIR_IDENTITY,
            accountId = accountId,
            reason = "ui_repair_identity",
        ) {
            putExtra(EXTRA_TARGET_ID, targetId)
        }

        fun enqueueStructureProbe(
            context: Context,
            accountId: String = DEFAULT_ACCOUNT_ID,
        ): Boolean = startAction(
            context = context,
            action = ACTION_PROBE_STRUCTURE,
            accountId = accountId,
            reason = "ui_structure_probe",
        )

        private fun startAction(
            context: Context,
            action: String,
            accountId: String,
            reason: String,
            extras: Intent.() -> Unit = {},
        ): Boolean {
            val appContext = context.applicationContext ?: context
            val intent = Intent(appContext, ExecutionForegroundService::class.java).apply {
                this.action = action
                putExtra(ExecutionDispatchContract.EXTRA_ACCOUNT_ID, accountId)
                putExtra(ExecutionDispatchContract.EXTRA_TRIGGER_REASON, reason)
                extras()
            }
            return startServiceSafe(appContext, intent)
        }

        private fun startServiceSafe(context: Context, intent: Intent): Boolean {
            val appContext = context.applicationContext ?: context
            return try {
                ContextCompat.startForegroundService(appContext, intent)
                true
            } catch (ex: SecurityException) {
                Log.w(TAG, "Cannot start foreground service: ${ex.javaClass.simpleName}")
                false
            } catch (ex: IllegalStateException) {
                Log.w(TAG, "Foreground service start disallowed: ${ex.javaClass.simpleName}")
                false
            }
        }
    }
}

internal data class AllocatedSendAttempt(
    val job: SendJobEntity,
    val attempt: SendAttemptEntity,
    val nextRetryAttemptCount: Int,
    val nextAttemptRowNo: Int,
)

/**
 * 与服务持久化相关的纯接口逻辑抽离，便于在纯 JVM 单元测试下用内存 DAO 验证唯一约束与状态收敛。
 */
internal object ExecutionServiceStoreOps {

    suspend fun recoverInterruptedAccountAndJobs(
        accountId: String,
        accountDao: AccountDao,
        sendJobDao: SendJobDao,
        nowMs: Long,
        isMutexLocked: Boolean,
    ): Boolean {
        val inProgress = sendJobDao.listJobsByStatuses(
            accountId = accountId,
            statuses = SendGuard.CRASH_IN_PROGRESS_STATUSES.toList(),
        )
        var hasUnknown = false
        if (inProgress.isNotEmpty()) {
            val recovered = SendGuard.recoverCrashedJobs(inProgress, nowMs)
            for (job in recovered) {
                sendJobDao.updateJob(job)
                val attempts = sendJobDao.listAttemptsForJob(job.id)
                for (attempt in attempts) {
                    if (attempt.status == AttemptStatus.RUNNING) {
                        sendJobDao.updateAttempt(
                            attempt.copy(
                                status = AttemptStatus.FAILED,
                                failureClass = job.failureClass,
                                finishedAt = nowMs,
                            ),
                        )
                    }
                }
                if (job.status == JobStatus.UNKNOWN_REQUIRES_REVIEW) {
                    hasUnknown = true
                }
            }
        }

        if (!isMutexLocked) {
            val account = accountDao.getAccount(accountId)
            if (account != null && account.status == AccountStatus.RUNNING) {
                accountDao.update(
                    account.copy(
                        status = AccountStatus.HEALTHY,
                        updatedAt = nowMs,
                    ),
                )
            }
        }

        return hasUnknown
    }

    suspend fun recordNetworkDeferAttempt(
        accountId: String,
        target: ContractTargetEntity,
        todayStr: String,
        sendJobDao: SendJobDao,
        nowMs: Long,
    ): SendAttemptEntity {
        val existing = sendJobDao.getJobBySlot(
            accountId = accountId,
            targetId = target.id,
            businessDate = todayStr,
            slotId = SendGuard.DAILY_SLOT_ID,
        )
        val job = if (existing != null) {
            existing
        } else {
            val inserted = SendJobEntity(
                accountId = accountId,
                targetId = target.id,
                templateId = target.templateId,
                businessDate = todayStr,
                messageSlotId = SendGuard.DAILY_SLOT_ID,
                status = JobStatus.FAILED_RETRYABLE,
                failureClass = FailureClass.NETWORK,
                summaryDetail = "网络异常，等待自动顺延重检",
                createdAt = nowMs,
                updatedAt = nowMs,
            )
            sendJobDao.insertIgnore(inserted)
            sendJobDao.getJobBySlot(
                accountId = accountId,
                targetId = target.id,
                businessDate = todayStr,
                slotId = SendGuard.DAILY_SLOT_ID,
            ) ?: inserted
        }
        sendJobDao.updateJob(
            job.copy(
                status = JobStatus.FAILED_RETRYABLE,
                failureClass = FailureClass.NETWORK,
                summaryDetail = "网络不可用，等待 10 分钟后顺延重检",
                updatedAt = nowMs,
            ),
        )
        val nextAttemptRowNo = (sendJobDao.listAttemptsForJob(job.id).maxOfOrNull { it.attemptNo } ?: 0) + 1
        val attempt = SendAttemptEntity(
            jobId = job.id,
            attemptNo = nextAttemptRowNo,
            stage = "auth_probe",
            status = AttemptStatus.FAILED,
            failureClass = FailureClass.NETWORK,
            errorCode = "network_unavailable",
            startedAt = nowMs,
            finishedAt = nowMs,
        )
        sendJobDao.insertAttempt(attempt)
        return attempt
    }

    suspend fun allocateJobAndAttemptForSend(
        accountId: String,
        target: ContractTargetEntity,
        templateId: String?,
        businessDate: String,
        slotId: String,
        sendJobDao: SendJobDao,
        nowMs: Long,
    ): AllocatedSendAttempt? {
        val job = if (slotId == SendGuard.DAILY_SLOT_ID) {
            val existing = sendJobDao.getJobBySlot(accountId, target.id, businessDate, slotId)
            if (existing != null) {
                if (!SendGuard.canDispatchDaily(listOf(existing))) {
                    return null
                }
                existing
            } else {
                val created = SendJobEntity(
                    accountId = accountId,
                    targetId = target.id,
                    templateId = templateId,
                    businessDate = businessDate,
                    messageSlotId = slotId,
                    status = JobStatus.LEASED,
                    scheduledAt = nowMs,
                    createdAt = nowMs,
                    updatedAt = nowMs,
                )
                sendJobDao.insertIgnore(created)
                sendJobDao.getJobBySlot(accountId, target.id, businessDate, slotId) ?: created
            }
        } else {
            val created = SendJobEntity(
                accountId = accountId,
                targetId = target.id,
                templateId = templateId,
                businessDate = businessDate,
                messageSlotId = slotId,
                status = JobStatus.LEASED,
                scheduledAt = nowMs,
                createdAt = nowMs,
                updatedAt = nowMs,
            )
            sendJobDao.insertIgnore(created)
            sendJobDao.getJobBySlot(accountId, target.id, businessDate, slotId) ?: created
        }

        val nextRetryAttemptCount = job.attemptCount + 1
        // send_attempts.attempt_no 在单据维度严格单调递增，与受 RETRY_MAX_COUNT 约束的 job.attemptCount 解耦
        val nextAttemptRowNo = (sendJobDao.listAttemptsForJob(job.id).maxOfOrNull { it.attemptNo } ?: 0) + 1
        val attempt = SendAttemptEntity(
            jobId = job.id,
            attemptNo = nextAttemptRowNo,
            stage = "opening_chat",
            status = AttemptStatus.RUNNING,
            startedAt = nowMs,
        )
        sendJobDao.insertAttempt(attempt)

        val updatedJob = job.copy(
            templateId = templateId,
            status = JobStatus.OPENING_CHAT,
            attemptCount = nextRetryAttemptCount,
            updatedAt = nowMs,
        )
        sendJobDao.updateJob(updatedJob)
        val persistedJob = sendJobDao.getJobById(job.id) ?: updatedJob
        return AllocatedSendAttempt(
            job = persistedJob,
            attempt = attempt,
            nextRetryAttemptCount = nextRetryAttemptCount,
            nextAttemptRowNo = nextAttemptRowNo,
        )
    }

    suspend fun finalizeBatchAccountIfRunning(
        accountId: String,
        accountDao: AccountDao,
        outcomes: List<BatchTargetOutcome>,
        nowMs: Long,
    ): AccountEntity? {
        val latest = accountDao.getAccount(accountId) ?: return null
        if (latest.status != AccountStatus.RUNNING) return null
        val finalized = AccountHealthReducer.onBatchFinished(
            account = latest,
            outcomes = outcomes,
            nowEpochMs = nowMs,
        )
        accountDao.update(finalized)
        return finalized
    }
}

internal object ServiceDatabaseHolder {
    @Volatile
    private var instance: FlameDatabase? = null

    fun attach(db: FlameDatabase) {
        instance = db
    }

    fun getInstance(context: Context): FlameDatabase {
        val existing = instance
        if (existing != null) return existing
        return synchronized(this) {
            instance ?: FlameDatabase.create(context.applicationContext ?: context).also {
                instance = it
            }
        }
    }
}
