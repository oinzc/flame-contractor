package io.github.oinzc.flamecontractor.app

import android.app.Application
import android.util.Log
import androidx.room.withTransaction
import io.github.oinzc.flamecontractor.core.account.PendingProfileCleanupStore
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.core.notify.UrgentCategory
import io.github.oinzc.flamecontractor.data.db.AccountDao
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendJobDao
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
 * - 在任何 WebView 初始化前执行待清理非默认 Profile 延迟销毁；
 * - 懒加载创建 [AppContainer] 并向后台服务注入共享数据库实例；
 * - 确保通知通道注册；
 * - 在 IO 协程执行冷启动僵尸单收敛（`SENDING`/`CONFIRMING` 必转 `UNKNOWN_REQUIRES_REVIEW`），
 *   并在无执行锁持有时恢复遗留 `RUNNING` 账号状态，随后按账号发送隔离通知并刷新全部账号闹钟。
 */
class FlameApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 必须抢在任何 WebView 或后台执行服务初始化之前销毁待清理的已删除账号 Profile
        try {
            PendingProfileCleanupStore(this).cleanupPendingProfiles()
        } catch (ce: CancellationException) {
            throw ce
        } catch (exc: Throwable) {
            Log.w(TAG, "Pending profile cleanup failed: ${exc.javaClass.simpleName}")
        }

        val appContainer = container
        ExecutionForegroundService.attachDatabase(appContainer.database)
        appContainer.localNotifier.ensureChannels()

        appScope.launch {
            try {
                val recoveryReport = if (!ExecutionMutex.isAnyLocked()) {
                    val token = ExecutionMutex.tryAcquireGlobalSession(COLD_START_OWNER)
                    if (token != null) {
                        try {
                            performColdStartRecovery(database = appContainer.database)
                        } finally {
                            ExecutionMutex.releaseSession(token)
                        }
                    } else {
                        ColdStartRecoveryReport()
                    }
                } else {
                    ColdStartRecoveryReport()
                }

                for (alert in recoveryReport.unknownAlerts) {
                    val isMultiAccount = recoveryReport.totalAccountCount > 1 ||
                        alert.accountId != DEFAULT_ACCOUNT_ID
                    val title = LocalNotifier.formatWithAccountPrefix(
                        text = "发现未确认的发送任务",
                        accountDisplayName = alert.displayName,
                        isMultiAccount = isMultiAccount,
                    )
                    val body = LocalNotifier.formatWithAccountPrefix(
                        text = "上次应用中断时已有消息进入发送或确认阶段，请到首页核对结果，系统已阻止自动重发。",
                        accountDisplayName = alert.displayName,
                        isMultiAccount = isMultiAccount,
                    )
                    appContainer.localNotifier.notifyUrgentAlert(
                        category = UrgentCategory.UNKNOWN_REQUIRES_REVIEW,
                        title = title,
                        body = body,
                        accountId = alert.accountId,
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

    internal data class ColdStartUnknownAlert(
        val accountId: String,
        val displayName: String,
    )

    internal data class ColdStartRecoveryReport(
        val unknownAlerts: List<ColdStartUnknownAlert> = emptyList(),
        val totalAccountCount: Int = 1,
    ) {
        val hasUnknown: Boolean get() = unknownAlerts.isNotEmpty()
    }

    companion object {
        private const val TAG = "FlameApplication"
        private const val COLD_START_OWNER = "app_cold_start"
        private const val FALLBACK_DEFAULT_ACCOUNT_NAME = "默认抖音号"

        internal suspend fun performColdStartRecovery(
            database: FlameDatabase,
            nowMs: Long = System.currentTimeMillis(),
        ): ColdStartRecoveryReport = database.withTransaction {
            performColdStartRecovery(
                accountDao = database.accountDao(),
                sendJobDao = database.sendJobDao(),
                nowMs = nowMs,
            )
        }

        internal suspend fun performColdStartRecovery(
            accountDao: AccountDao,
            sendJobDao: SendJobDao,
            nowMs: Long = System.currentTimeMillis(),
        ): ColdStartRecoveryReport {
            val accounts = accountDao.listAll()
            val targets = if (accounts.isEmpty()) {
                listOf(DEFAULT_ACCOUNT_ID to FALLBACK_DEFAULT_ACCOUNT_NAME)
            } else {
                accounts.map { it.id to it.displayName }
            }

            val unknownAlerts = mutableListOf<ColdStartUnknownAlert>()
            for ((accountId, displayName) in targets) {
                val crashedJobs = sendJobDao.listJobsByStatuses(
                    accountId = accountId,
                    statuses = SendGuard.CRASH_IN_PROGRESS_STATUSES.toList(),
                )
                var accountHasUnknown = false
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
                            accountHasUnknown = true
                        }
                    }
                }
                if (accountHasUnknown) {
                    unknownAlerts += ColdStartUnknownAlert(
                        accountId = accountId,
                        displayName = displayName,
                    )
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

            return ColdStartRecoveryReport(
                unknownAlerts = unknownAlerts,
                totalAccountCount = maxOf(accounts.size, 1),
            )
        }
    }
}
