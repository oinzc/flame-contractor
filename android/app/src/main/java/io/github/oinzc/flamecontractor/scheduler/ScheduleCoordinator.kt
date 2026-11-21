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
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.domain.SendGuard
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
)

/**
 * 调度协调器：
 * - 基于 DailySchedulePlanner 与 Room 只读状态计算同日防过窗下次唤醒时刻；
 * - 注册 AlarmManager 主唤醒（API 31+ 有权限用精确闹钟，无权限降级 setAndAllowWhileIdle）；
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

    internal suspend fun evaluateCurrentSchedule(
        now: Instant = Instant.now(),
        staleCrashRecoveryMillis: Long? = null,
    ): ScheduleReadSnapshot? {
        val db = databaseProvider()
        val account = db.accountDao().getAccount(DEFAULT_ACCOUNT_ID) ?: return null
        val zoneId = DailySchedulePlanner.resolveZoneId(account.timezone)
        val todayStr = ZonedDateTime.ofInstant(now, zoneId).toLocalDate().toString()
        val targets = db.targetDao().listEnabledTargets(account.id)
        val sendJobDao = db.sendJobDao()
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

        val evaluation = DailySchedulePlanner.evaluateNow(
            now = now,
            account = account,
            targets = targets,
            todayJobs = effectiveJobs,
            zoneId = zoneId,
            networkDeferCountToday = networkDeferCountToday,
        )

        return ScheduleReadSnapshot(
            accountId = account.id,
            accountEnabled = account.enabled,
            accountStatus = account.status,
            crashInProgressCount = crashInProgressCount,
            evaluation = evaluation,
        )
    }

    internal suspend fun evaluateAndDispatchIfNeeded(
        triggerSource: String,
        now: Instant = Instant.now(),
        staleCrashRecoveryMillis: Long? = null,
    ): ScheduleReadSnapshot? {
        val snapshot = evaluateCurrentSchedule(
            now = now,
            staleCrashRecoveryMillis = staleCrashRecoveryMillis,
        )
        if (snapshot != null) {
            ExecutionDispatchContract.dispatchEvaluationIfNeeded(
                context = appContext,
                accountId = snapshot.accountId,
                evaluation = snapshot.evaluation,
                triggerSource = triggerSource,
            )
        }
        rescheduleAll(now)
        return snapshot
    }

    suspend fun rescheduleAll(now: Instant = Instant.now()): Instant? {
        val snapshot = evaluateCurrentSchedule(now = now, staleCrashRecoveryMillis = null)
        if (snapshot == null || !snapshot.accountEnabled || snapshot.accountStatus != AccountStatus.HEALTHY) {
            cancelAllAlarms()
            return null
        }

        ensureSafetyNetPeriodicWork()
        cancelAlarm(REQUEST_CODE_AUTH_RECHECK, KIND_AUTH_RECHECK)
        cancelAlarm(REQUEST_CODE_RETRY_BACKOFF, KIND_RETRY_BACKOFF)

        val nextWakeAt = snapshot.evaluation.nextWakeAt
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

    private fun projectStaleInProgressJobs(
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

        private const val REQUEST_CODE_MAIN_WAKE = 4101
        private const val REQUEST_CODE_AUTH_RECHECK = 4102
        private const val REQUEST_CODE_RETRY_BACKOFF = 4103
    }
}
