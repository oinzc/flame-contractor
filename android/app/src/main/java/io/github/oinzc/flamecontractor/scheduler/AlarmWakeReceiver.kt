package io.github.oinzc.flamecontractor.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AlarmManager 主唤醒广播接收器。
 *
 * 严格只读 SQLite 并经过 DailySchedulePlanner.evaluateNow(...) 核验同日/过窗边界后
 * 按需通过 ExecutionDispatchContract 拉起前台执行服务，严禁在接收器中接触 WebView。
 */
class AlarmWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != null && action != ScheduleCoordinator.ACTION_ALARM_WAKE) {
            return
        }
        val appContext = context.applicationContext ?: context
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(RECEIVER_TIMEOUT_MS) {
                    val snapshot = ScheduleCoordinator(appContext).evaluateAndDispatchIfNeeded(
                        triggerSource = "alarm",
                        now = Instant.now(),
                        staleCrashRecoveryMillis = null,
                    )
                    if (snapshot != null) {
                        val evaluation = snapshot.evaluation
                        Log.i(
                            TAG,
                            "Alarm wake evaluated due=${evaluation.dueTargets.size} " +
                                "authRecheck=${evaluation.shouldTriggerAuthRecheck} " +
                                "expired=${evaluation.expiredTargets.size}",
                        )
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                Log.w(TAG, "Alarm wake evaluation failed: ${ex.javaClass.simpleName}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AlarmWakeReceiver"
        private const val RECEIVER_TIMEOUT_MS = 8_000L
    }
}
