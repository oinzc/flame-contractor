package io.github.oinzc.flamecontractor.app

import android.app.Application
import android.util.Log
import androidx.room.withTransaction
import io.github.oinzc.flamecontractor.core.notify.UrgentCategory
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.execution.ExecutionForegroundService
import io.github.oinzc.flamecontractor.execution.ExecutionMutex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：
 * - 懒加载创建 [AppContainer] 并向后台服务注入共享数据库实例；
 * - 确保通知通道注册；
 * - 在 IO 协程执行冷启动僵尸单收敛（`SENDING`/`CONFIRMING` 必转 `UNKNOWN_REQUIRES_REVIEW`），
 *   并在无执行锁持有时恢复遗留 `RUNNING` 账号状态，随后刷新兜底周期任务与当日闹钟。
 */
class FlameApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val appContainer = container
        ExecutionForegroundService.attachDatabase(appContainer.database)
        appContainer.localNotifier.ensureChannels()

        appScope.launch {
            try {
                val hasUnknown = if (!ExecutionMutex.isLocked()) {
                    val token = ExecutionMutex.tryAcquireSession(COLD_START_OWNER)
                    if (token != null) {
                        try {
                            performColdStartRecovery(database = appContainer.database)
                        } finally {
                            ExecutionMutex.releaseSession(token)
                        }
                    } else {
                        false
                    }
                } else {
                    false
                }

                if (hasUnknown) {
                    appContainer.localNotifier.notifyUrgentAlert(
                        category = UrgentCategory.UNKNOWN_REQUIRES_REVIEW,
                        title = "发现未确认的发送任务",
                        body = "上次应用中断时已有消息进入发送或确认阶段，请到首页核对结果，系统已阻止自动重发。",
                    )
                }

                appContainer.scheduleCoordinator.ensureSafetyNetPeriodicWork()
                appContainer.scheduleCoordinator.rescheduleAll()
            } catch (ce: CancellationException) {
                throw ce
            } catch (exc: Throwable) {
                Log.w(TAG, "Cold-start recovery failed: ${exc.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val TAG = "FlameApplication"
        private const val COLD_START_OWNER = "app_cold_start"

        internal suspend fun performColdStartRecovery(
            database: FlameDatabase,
            nowMs: Long = System.currentTimeMillis(),
        ): Boolean = database.withTransaction {
            val accountDao = database.accountDao()
            val sendJobDao = database.sendJobDao()
            val accounts = accountDao.listAll()
            val accountIds = if (accounts.isEmpty()) {
                listOf(DEFAULT_ACCOUNT_ID)
            } else {
                accounts.map { it.id }
            }

            var hasUnknown = false
            for (accountId in accountIds) {
                val crashedJobs = sendJobDao.listJobsByStatuses(
                    accountId = accountId,
                    statuses = SendGuard.CRASH_IN_PROGRESS_STATUSES.toList(),
                )
                if (crashedJobs.isNotEmpty()) {
                    val recoveredJobs = SendGuard.recoverCrashedJobs(crashedJobs, nowMs)
                    for (job in recoveredJobs) {
                        sendJobDao.updateJob(job)
                        sendJobDao.listAttemptsForJob(job.id)
                            .filter { it.status == AttemptStatus.RUNNING }
                            .forEach { runningAttempt ->
                                sendJobDao.updateAttempt(
                                    runningAttempt.copy(
                                        status = AttemptStatus.FAILED,
                                        failureClass = job.failureClass,
                                        finishedAt = nowMs,
                                    ),
                                )
                            }
                        if (job.status == JobStatus.UNKNOWN_REQUIRES_REVIEW) {
                            hasUnknown = true
                        }
                    }
                }
            }

            for (account in accountDao.listAll()) {
                if (account.status == AccountStatus.RUNNING) {
                    accountDao.update(
                        account.copy(
                            status = AccountStatus.HEALTHY,
                            updatedAt = nowMs,
                        ),
                    )
                }
            }
            hasUnknown
        }
    }
}
