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
 * 系统开机、应用更新、手动改时与时区变更广播接收器。
 *
 * 仅触发纯 SQLite 重算闹钟（ScheduleCoordinator.rescheduleAll）并确保 30 分钟兜底任务注册，
 * 绝不在系统时间事件中盲发过期单据或绕过 DailySchedulePlanner.evaluateNow 检查。
 */
class SystemTimeBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val appContext = context.applicationContext ?: context
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(RECEIVER_TIMEOUT_MS) {
                    val coordinator = ScheduleCoordinator(appContext)
                    coordinator.ensureSafetyNetPeriodicWork()
                    val nextWakeAt = coordinator.rescheduleAll(Instant.now())
                    Log.i(
                        TAG,
                        "Rescheduled after system event action=$action hasNextWake=${nextWakeAt != null}",
                    )
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                Log.w(TAG, "System event reschedule failed action=$action type=${ex.javaClass.simpleName}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SystemTimeBootReceiver"
        private const val RECEIVER_TIMEOUT_MS = 8_000L

        private val SUPPORTED_ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
