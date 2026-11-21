package io.github.oinzc.flamecontractor.scheduler

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 调度层与 Task 6 ExecutionForegroundService 之间的共享启动契约。
 * 零反射直接通过 ComponentName(packageName, EXECUTION_SERVICE_CLASS) 构造显式 Intent，
 * 并在后台启动受限时安全捕获异常避免崩溃。
 */
object ExecutionDispatchContract {

    private const val TAG = "ExecutionDispatch"

    const val EXECUTION_SERVICE_CLASS = "io.github.oinzc.flamecontractor.execution.ExecutionForegroundService"
    const val ACTION_RUN_SCHEDULED = "io.github.oinzc.flamecontractor.action.RUN_SCHEDULED"
    const val ACTION_AUTH_RECHECK = "io.github.oinzc.flamecontractor.action.AUTH_RECHECK"
    const val EXTRA_TRIGGER_REASON = "trigger_reason"
    const val EXTRA_ACCOUNT_ID = "account_id"

    fun createExecutionServiceIntent(
        context: Context,
        action: String,
        accountId: String,
        triggerReason: String,
    ): Intent {
        val appContext = context.applicationContext ?: context
        return Intent(action).apply {
            component = ComponentName(appContext.packageName, EXECUTION_SERVICE_CLASS)
            putExtra(EXTRA_ACCOUNT_ID, accountId)
            putExtra(EXTRA_TRIGGER_REASON, triggerReason)
        }
    }

    fun startExecutionForegroundService(
        context: Context,
        action: String,
        accountId: String,
        triggerReason: String,
    ): Boolean {
        val appContext = context.applicationContext ?: context
        val intent = createExecutionServiceIntent(
            context = appContext,
            action = action,
            accountId = accountId,
            triggerReason = triggerReason,
        )
        return try {
            ContextCompat.startForegroundService(appContext, intent)
            true
        } catch (ex: SecurityException) {
            Log.w(TAG, "Foreground service start denied action=$action reason=$triggerReason type=${ex.javaClass.simpleName}")
            false
        } catch (ex: IllegalStateException) {
            // 包含 Android 12+ ForegroundServiceStartNotAllowedException
            Log.w(TAG, "Foreground service start disallowed action=$action reason=$triggerReason type=${ex.javaClass.simpleName}")
            false
        }
    }

    fun dispatchEvaluationIfNeeded(
        context: Context,
        accountId: String,
        evaluation: ScheduleEvaluation,
        triggerSource: String,
    ): Boolean = when {
        evaluation.shouldTriggerAuthRecheck -> startExecutionForegroundService(
            context = context,
            action = ACTION_AUTH_RECHECK,
            accountId = accountId,
            triggerReason = "${triggerSource}_auth_recheck",
        )

        evaluation.dueTargets.isNotEmpty() -> startExecutionForegroundService(
            context = context,
            action = ACTION_RUN_SCHEDULED,
            accountId = accountId,
            triggerReason = "${triggerSource}_scheduled",
        )

        else -> false
    }
}
