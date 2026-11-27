package io.github.oinzc.flamecontractor.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.oinzc.flamecontractor.core.storage.OfficialSparkLitStore
import io.github.oinzc.flamecontractor.core.storage.SchedulePreferencesStore
import io.github.oinzc.flamecontractor.data.db.AccountDao
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.ScanRunDao
import io.github.oinzc.flamecontractor.data.db.SendJobDao
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.data.db.TargetDao
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.widget.FlameStatusWidgetUpdater
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

internal object SchedulerDatabaseHolder {

    @Volatile
    private var instance: FlameDatabase? = null

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

internal data class ScheduleReadSnapshot(
    val accountId: String,
    val accountEnabled: Boolean,
    val accountStatus: AccountStatus,
    val crashInProgressCount: Int,
    val evaluation: ScheduleEvaluation,
    val autoScanEvaluation: AutoScanEvaluation? = null,
)

internal data class AccountDispatchPlan(
    val snapshot: ScheduleReadSnapshot,
    val action: String,
)

internal data class MultiAccountScheduleAggregation(
    val snapshots: List<ScheduleReadSnapshot>,
    val schedulableSnapshots: List<ScheduleReadSnapshot>,
    val hasAnySchedulableAccount: Boolean,
    val earliestNextWakeAt: Instant?,
    val earliestNextAutoScanAt: Instant?,
    val dispatchPlans: List<AccountDispatchPlan>,
    val primarySnapshot: ScheduleReadSnapshot?,
)

/**
 * 纯 JVM 多账号调度评估与聚合器（零 android.* 依赖），供 [ScheduleCoordinator] 与 JVM 单元测试共享。
 */
internal object MultiAccountSchedulePlanner {

    suspend fun evaluateAccount(
        account: AccountEntity,
        targetDao: TargetDao,
        sendJobDao: SendJobDao,
        scanRunDao: ScanRunDao,
        now: Instant = Instant.now(),
        staleCrashRecoveryMillis: Long? = null,
        officialSparkLitStore: OfficialSparkLitStore? = null,
        schedulePreferencesStore: SchedulePreferencesStore? = null,
    ): ScheduleReadSnapshot {
        val zoneId = DailySchedulePlanner.resolveZoneId(account.timezone)
        val todayStr = ZonedDateTime.ofInstant(now, zoneId).toLocalDate().toString()
        val targets = targetDao.listEnabledTargets(account.id)
        val todayJobs = sendJobDao.listJobsForDate(account.id, todayStr)

        var networkDeferCountToday = 0
        for (job in todayJobs) {
            val attempts = sendJobDao.listAttemptsForJob(job.id)
            val failedNetworkAttempts = attempts.count {
                it.failureClass == FailureClass.NETWORK && it.status == AttemptStatus.FAILED
            }
            networkDeferCountToday += if (failedNetworkAttempts > 0) {
                failedNetworkAttempts
            } else if (job.status == JobStatus.FAILED_RETRYABLE && job.failureClass == FailureClass.NETWORK) {
                1
            } else {
                0
            }
        }

        val crashInProgressCount = todayJobs.count { it.status in SendGuard.CRASH_IN_PROGRESS_STATUSES }
        val effectiveJobs = if (staleCrashRecoveryMillis != null) {
            projectStaleInProgressJobs(
                jobs = todayJobs,
                nowEpochMs = now.toEpochMilli(),
                staleThresholdMillis = staleCrashRecoveryMillis,
            )
        } else {
            todayJobs
        }

        val skipOfficialLitIds = if (schedulePreferencesStore?.isSkipOfficialLitToday(account.id) == true) {
            officialSparkLitStore?.todayLitTargetIds(account.id, todayStr).orEmpty()
        } else {
            emptySet()
        }

        val evaluation = DailySchedulePlanner.evaluateNow(
            now = now,
            account = account,
            targets = targets,
            todayJobs = effectiveJobs,
            zoneId = zoneId,
            networkDeferCountToday = networkDeferCountToday,
            skipOfficialLitTargetIds = skipOfficialLitIds,
        )

        val latestCompletedScan = scanRunDao.getLatestCompletedScanRun(account.id)
        val latestAnyScan = scanRunDao.getLatestScanRun(account.id)
        val autoScanEvaluation = AutoScanPlanner.evaluate(
            now = now,
            account = account,
            enabledTargets = targets,
            latestCompletedScan = latestCompletedScan,
            latestAnyScan = latestAnyScan,
            zoneId = zoneId,
        )

        return ScheduleReadSnapshot(
            accountId = account.id,
            accountEnabled = account.enabled,
            accountStatus = account.status,
            crashInProgressCount = crashInProgressCount,
            evaluation = evaluation,
            autoScanEvaluation = autoScanEvaluation,
        )
    }

    suspend fun evaluateAllAccounts(
        accountDao: AccountDao,
        targetDao: TargetDao,
        sendJobDao: SendJobDao,
        scanRunDao: ScanRunDao,
        now: Instant = Instant.now(),
        staleCrashRecoveryMillis: Long? = null,
        officialSparkLitStore: OfficialSparkLitStore? = null,
        schedulePreferencesStore: SchedulePreferencesStore? = null,
    ): MultiAccountScheduleAggregation {
        val accounts = accountDao.listAll()
        if (accounts.isEmpty()) {
            return aggregate(emptyList())
        }
        val snapshots = accounts.map { account ->
            evaluateAccount(
                account = account,
                targetDao = targetDao,
                sendJobDao = sendJobDao,
                scanRunDao = scanRunDao,
                now = now,
                staleCrashRecoveryMillis = staleCrashRecoveryMillis,
                officialSparkLitStore = officialSparkLitStore,
                schedulePreferencesStore = schedulePreferencesStore,
            )
        }
        return aggregate(snapshots)
    }

    suspend fun inspectNextAutoScanAt(
        accountDao: AccountDao,
        targetDao: TargetDao,
        sendJobDao: SendJobDao,
        scanRunDao: ScanRunDao,
        now: Instant = Instant.now(),
        accountId: String = DEFAULT_ACCOUNT_ID,
    ): Instant? {
        val account = accountDao.getAccount(accountId) ?: return null
        val snapshot = evaluateAccount(
            account = account,
            targetDao = targetDao,
            sendJobDao = sendJobDao,
            scanRunDao = scanRunDao,
            now = now,
            staleCrashRecoveryMillis = null,
        )
        val autoScanEval = snapshot.autoScanEvaluation ?: return null
        return if (autoScanEval.eligible) autoScanEval.nextAutoScanAt else null
    }

    fun aggregate(snapshots: List<ScheduleReadSnapshot>): MultiAccountScheduleAggregation {
        val schedulableSnapshots = snapshots.filter {
            it.accountEnabled && it.accountStatus == AccountStatus.HEALTHY
        }
        val earliestNextWakeAt = schedulableSnapshots
            .mapNotNull { it.evaluation.nextWakeAt }
            .minOrNull()
        val earliestNextAutoScanAt = schedulableSnapshots
            .mapNotNull { snapshot ->
                snapshot.autoScanEvaluation?.takeIf { it.eligible }?.nextAutoScanAt
            }
            .minOrNull()
        val dispatchPlans = schedulableSnapshots.mapNotNull { snapshot ->
            val action = ExecutionDispatchContract.decideAction(
                evaluation = snapshot.evaluation,
                autoScanEvaluation = snapshot.autoScanEvaluation,
            )
            if (action != null) {
                AccountDispatchPlan(snapshot = snapshot, action = action)
            } else {
                null
            }
        }
        val primarySnapshot = dispatchPlans.firstOrNull()?.snapshot
            ?: schedulableSnapshots.firstOrNull { it.crashInProgressCount > 0 }
            ?: snapshots.firstOrNull { it.accountId == DEFAULT_ACCOUNT_ID }
            ?: snapshots.firstOrNull()

        return MultiAccountScheduleAggregation(
            snapshots = snapshots,
            schedulableSnapshots = schedulableSnapshots,
            hasAnySchedulableAccount = schedulableSnapshots.isNotEmpty(),
            earliestNextWakeAt = earliestNextWakeAt,
            earliestNextAutoScanAt = earliestNextAutoScanAt,
            dispatchPlans = dispatchPlans,
            primarySnapshot = primarySnapshot,
        )
    }

    fun projectStaleInProgressJobs(
        jobs: List<SendJobEntity>,
        nowEpochMs: Long,
        staleThresholdMillis: Long,
    ): List<SendJobEntity> = jobs.map { job ->
        if (job.status !in SendGuard.CRASH_IN_PROGRESS_STATUSES) {
            job
        } else if (nowEpochMs - job.updatedAt < staleThresholdMillis) {
            job
        } else {
            val recoveredStatus = SendGuard.classifyCrashedJob(job)
            if (recoveredStatus == job.status) {
                job
            } else {
                job.copy(status = recoveredStatus)
            }
        }
    }
}

/**
 * 调度协调器：
 * - 遍历所有账号，基于 DailySchedulePlanner 与 AutoScanPlanner 评估同日防过窗下次唤醒时刻；
 * - 汇总最早 `nextWakeAt`（`4101`）与最早 `nextAutoScanAt`（`4104`）注册 AlarmManager 主闹钟；
 * - 到期触发时逐个为各到期账号分别派发执行或自动扫描指令；
 * - 注册 WorkManager 每 30 分钟纯 SQLite 兜底任务 SafetyNetWorker。
 */
class ScheduleCoordinator private constructor(
    private val appContext: Context,
    private val databaseProvider: () -> FlameDatabase,
) {

    constructor(context: Context) : this(
        appContext = context.applicationContext ?: context,
        databaseProvider = { SchedulerDatabaseHolder.getInstance(context.applicationContext ?: context) },
    )

    constructor(context: Context, database: FlameDatabase) : this(
        appContext = context.applicationContext ?: context,
        databaseProvider = { database },
    )

    private val alarmManager: AlarmManager?
        get() = appContext.getSystemService(AlarmManager::class.java)

    private fun resolveOfficialSparkLitStore(): OfficialSparkLitStore =
        OfficialSparkLitStore.globalOrNull() ?: OfficialSparkLitStore(appContext)

    private fun resolveSchedulePreferencesStore(): SchedulePreferencesStore =
        SchedulePreferencesStore.resolve(appContext)

    internal suspend fun evaluateCurrentSchedule(
        now: Instant = Instant.now(),
        staleCrashRecoveryMillis: Long? = null,
        accountId: String = DEFAULT_ACCOUNT_ID,
    ): ScheduleReadSnapshot? {
        val db = databaseProvider()
        val account = db.accountDao().getAccount(accountId) ?: return null
        return MultiAccountSchedulePlanner.evaluateAccount(
            account = account,
            targetDao = db.targetDao(),
            sendJobDao = db.sendJobDao(),
            scanRunDao = db.scanRunDao(),
            now = now,
            staleCrashRecoveryMillis = staleCrashRecoveryMillis,
            officialSparkLitStore = resolveOfficialSparkLitStore(),
            schedulePreferencesStore = resolveSchedulePreferencesStore(),
        )
    }

    internal suspend fun evaluateAndDispatchIfNeeded(
        triggerSource: String,
        now: Instant = Instant.now(),
        staleCrashRecoveryMillis: Long? = null,
    ): ScheduleReadSnapshot? {
        val db = databaseProvider()
        val aggregation = MultiAccountSchedulePlanner.evaluateAllAccounts(
            accountDao = db.accountDao(),
            targetDao = db.targetDao(),
            sendJobDao = db.sendJobDao(),
            scanRunDao = db.scanRunDao(),
            now = now,
            staleCrashRecoveryMillis = staleCrashRecoveryMillis,
            officialSparkLitStore = resolveOfficialSparkLitStore(),
            schedulePreferencesStore = resolveSchedulePreferencesStore(),
        )
        for (plan in aggregation.dispatchPlans) {
            ExecutionDispatchContract.dispatchEvaluationIfNeeded(
                context = appContext,
                accountId = plan.snapshot.accountId,
                evaluation = plan.snapshot.evaluation,
                triggerSource = triggerSource,
                autoScanEvaluation = plan.snapshot.autoScanEvaluation,
            )
        }
        rescheduleAll(now)
        return aggregation.primarySnapshot
    }

    suspend fun inspectNextAutoScanAt(
        now: Instant = Instant.now(),
        accountId: String = DEFAULT_ACCOUNT_ID,
    ): Instant? {
        val db = databaseProvider()
        return MultiAccountSchedulePlanner.inspectNextAutoScanAt(
            accountDao = db.accountDao(),
            targetDao = db.targetDao(),
            sendJobDao = db.sendJobDao(),
            scanRunDao = db.scanRunDao(),
            now = now,
            accountId = accountId,
        )
    }

    suspend fun rescheduleAll(now: Instant = Instant.now()): Instant? {
        val nextWakeAt = rescheduleAllAlarms(now)
        FlameStatusWidgetUpdater.requestUpdate(appContext)
        return nextWakeAt
    }

    private suspend fun rescheduleAllAlarms(now: Instant): Instant? {
        val db = databaseProvider()
        val aggregation = MultiAccountSchedulePlanner.evaluateAllAccounts(
            accountDao = db.accountDao(),
            targetDao = db.targetDao(),
            sendJobDao = db.sendJobDao(),
            scanRunDao = db.scanRunDao(),
            now = now,
            staleCrashRecoveryMillis = null,
            officialSparkLitStore = resolveOfficialSparkLitStore(),
            schedulePreferencesStore = resolveSchedulePreferencesStore(),
        )
        if (!aggregation.hasAnySchedulableAccount) {
            cancelAllAlarms()
            return null
        }

        ensureSafetyNetPeriodicWork()
        cancelAlarm(REQUEST_CODE_AUTH_RECHECK, KIND_AUTH_RECHECK)
        cancelAlarm(REQUEST_CODE_RETRY_BACKOFF, KIND_RETRY_BACKOFF)

        val nextAutoScanAt = aggregation.earliestNextAutoScanAt
        if (nextAutoScanAt != null) {
            val autoScanIntent = createAlarmPendingIntent(
                requestCode = REQUEST_CODE_AUTO_SCAN,
                kind = KIND_AUTO_SCAN,
            )
            if (autoScanIntent != null) {
                scheduleAlarmAt(nextAutoScanAt.toEpochMilli(), autoScanIntent)
            }
        } else {
            cancelAlarm(REQUEST_CODE_AUTO_SCAN, KIND_AUTO_SCAN)
        }

        val nextWakeAt = aggregation.earliestNextWakeAt
        if (nextWakeAt == null) {
            cancelAlarm(REQUEST_CODE_MAIN_WAKE, KIND_SCHEDULED_WAKE)
            return null
        }

        val pendingIntent = createAlarmPendingIntent(
            requestCode = REQUEST_CODE_MAIN_WAKE,
            kind = KIND_SCHEDULED_WAKE,
        ) ?: return null

        scheduleAlarmAt(nextWakeAt.toEpochMilli(), pendingIntent)
        return nextWakeAt
    }

    fun scheduleAuthRecheckIn10Minutes(now: Instant = Instant.now()): Instant {
        val recheckAt = now.plus(Duration.ofMinutes(DailySchedulePlanner.AUTH_RECHECK_DELAY_MINUTES))
        val pendingIntent = createAlarmPendingIntent(
            requestCode = REQUEST_CODE_AUTH_RECHECK,
            kind = KIND_AUTH_RECHECK,
        )
        if (pendingIntent != null) {
            scheduleAlarmAt(recheckAt.toEpochMilli(), pendingIntent)
        }
        return recheckAt
    }

    fun scheduleRetryBackoff(
        attemptNo: Int,
        now: Instant = Instant.now(),
        cutoffAt: Instant? = null,
    ): Instant? {
        if (attemptNo !in 1..SendGuard.RETRY_MAX_COUNT) {
            cancelAlarm(REQUEST_CODE_RETRY_BACKOFF, KIND_RETRY_BACKOFF)
            return null
        }
        val backoffMinutes = DailySchedulePlanner.RETRY_BACKOFF_MINUTES[attemptNo - 1]
        val retryAt = now.plus(Duration.ofMinutes(backoffMinutes))
        if (cutoffAt != null && !retryAt.isBefore(cutoffAt)) {
            cancelAlarm(REQUEST_CODE_RETRY_BACKOFF, KIND_RETRY_BACKOFF)
            return null
        }
        val pendingIntent = createAlarmPendingIntent(
            requestCode = REQUEST_CODE_RETRY_BACKOFF,
            kind = KIND_RETRY_BACKOFF,
        ) ?: return null
        scheduleAlarmAt(retryAt.toEpochMilli(), pendingIntent)
        return retryAt
    }

    fun cancelAllAlarms() {
        cancelAlarm(REQUEST_CODE_MAIN_WAKE, KIND_SCHEDULED_WAKE)
        cancelAlarm(REQUEST_CODE_AUTH_RECHECK, KIND_AUTH_RECHECK)
        cancelAlarm(REQUEST_CODE_RETRY_BACKOFF, KIND_RETRY_BACKOFF)
        cancelAlarm(REQUEST_CODE_AUTO_SCAN, KIND_AUTO_SCAN)
    }

    fun ensureSafetyNetPeriodicWork() {
        val request = PeriodicWorkRequestBuilder<SafetyNetWorker>(
            SAFETY_NET_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .addTag(WORK_NAME_SAFETY_NET)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            WORK_NAME_SAFETY_NET,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelSafetyNetPeriodicWork() {
        WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME_SAFETY_NET)
    }

    private fun scheduleAlarmAt(triggerAtMillis: Long, pendingIntent: PendingIntent) {
        val manager = alarmManager ?: return
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.canScheduleExactAlarms()
        } else {
            true
        }
        if (canExact) {
            try {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
                return
            } catch (ex: SecurityException) {
                Log.w(TAG, "Exact alarm denied, falling back to inexact allowWhileIdle: ${ex.javaClass.simpleName}")
            }
        }
        try {
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        } catch (ex: SecurityException) {
            Log.w(TAG, "Inexact alarm denied: ${ex.javaClass.simpleName}")
        }
    }

    private fun cancelAlarm(requestCode: Int, kind: String) {
        val manager = alarmManager ?: return
        val existing = createAlarmPendingIntent(
            requestCode = requestCode,
            kind = kind,
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        manager.cancel(existing)
        existing.cancel()
    }

    private fun createAlarmPendingIntent(
        requestCode: Int,
        kind: String,
        flags: Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    ): PendingIntent? {
        val intent = Intent(appContext, AlarmWakeReceiver::class.java).apply {
            action = ACTION_ALARM_WAKE
            putExtra(EXTRA_ALARM_KIND, kind)
        }
        return PendingIntent.getBroadcast(appContext, requestCode, intent, flags)
    }

    companion object {
        private const val TAG = "ScheduleCoordinator"
        const val WORK_NAME_SAFETY_NET = "flame_safety_net_periodic"
        const val SAFETY_NET_INTERVAL_MINUTES = 30L
        const val ACTION_ALARM_WAKE = "io.github.oinzc.flamecontractor.action.ALARM_WAKE"
        const val EXTRA_ALARM_KIND = "alarm_kind"
        const val KIND_SCHEDULED_WAKE = "scheduled_wake"
        const val KIND_AUTH_RECHECK = "auth_recheck"
        const val KIND_RETRY_BACKOFF = "retry_backoff"
        const val KIND_AUTO_SCAN = "auto_scan"

        const val REQUEST_CODE_MAIN_WAKE = 4101
        private const val REQUEST_CODE_AUTH_RECHECK = 4102
        private const val REQUEST_CODE_RETRY_BACKOFF = 4103
        const val REQUEST_CODE_AUTO_SCAN = 4104
    }
}
