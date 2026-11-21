package io.github.oinzc.flamecontractor.scheduler

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * 每 30 分钟运行一次的纯 SQLite 调度兜底 Worker。
 *
 * 严格只读 SQLite（严禁接触或初始化 WebView），对当日遗留 CRASH_IN_PROGRESS_STATUSES
 * 与正常调度计划执行评估，仅在 shouldTriggerAuthRecheck 或 dueTargets 非空时拉起执行服务，
 * 并始终调用 ScheduleCoordinator.rescheduleAll() 同步精确闹钟。
 */
class SafetyNetWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val snapshot = ScheduleCoordinator(applicationContext).evaluateAndDispatchIfNeeded(
                triggerSource = "safety_net",
                now = Instant.now(),
                staleCrashRecoveryMillis = STALE_CRASH_THRESHOLD_MS,
            )
            if (snapshot != null) {
                val evaluation = snapshot.evaluation
                Log.i(
                    TAG,
                    "SafetyNet checked due=${evaluation.dueTargets.size} " +
                        "authRecheck=${evaluation.shouldTriggerAuthRecheck} " +
                        "expired=${evaluation.expiredTargets.size} " +
                        "crashInProgress=${snapshot.crashInProgressCount}",
                )
            }
            Result.success()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Throwable) {
            Log.w(TAG, "SafetyNet check failed: ${ex.javaClass.simpleName}")
            Result.success()
        }
    }

    companion object {
        private const val TAG = "SafetyNetWorker"
        private const val STALE_CRASH_THRESHOLD_MS = 10L * 60L * 1000L
    }
}
