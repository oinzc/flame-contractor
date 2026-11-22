package io.github.oinzc.flamecontractor.app

import android.content.Context
import io.github.oinzc.flamecontractor.core.account.AccountManager
import io.github.oinzc.flamecontractor.core.account.ActiveAccountStore
import io.github.oinzc.flamecontractor.core.account.PendingProfileCleanupStore
import io.github.oinzc.flamecontractor.core.account.WebViewProfileRegistry
import io.github.oinzc.flamecontractor.core.backup.BackupManager
import io.github.oinzc.flamecontractor.core.backup.RoomBackupStore
import io.github.oinzc.flamecontractor.core.notify.BarkNotifier
import io.github.oinzc.flamecontractor.core.notify.EncryptedSecretStore
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundleStore
import io.github.oinzc.flamecontractor.core.storage.AndroidTemplateImageStore
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.domain.AccountHealthReducer
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.domain.TemplatePoolPicker
import io.github.oinzc.flamecontractor.execution.DouyinWebExecutor
import io.github.oinzc.flamecontractor.execution.ExecutionForegroundService
import io.github.oinzc.flamecontractor.execution.ExecutionRuntimeBus
import io.github.oinzc.flamecontractor.scheduler.ScheduleCoordinator
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 轻量手动依赖注入容器，持有全应用单实例核心存储、通知、调度与执行器依赖。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext ?: context

    val database: FlameDatabase = FlameDatabase.create(appContext)
    val selectorBundleStore: SelectorBundleStore = SelectorBundleStore(appContext)
    val secretStore: EncryptedSecretStore = EncryptedSecretStore(appContext)
    val barkNotifier: BarkNotifier = BarkNotifier(baseUrlProvider = { secretStore.readBarkUrl() })
    val localNotifier: LocalNotifier = LocalNotifier(appContext)
    val scheduleCoordinator: ScheduleCoordinator = ScheduleCoordinator(appContext, database)
    val templateImageStore: AndroidTemplateImageStore = AndroidTemplateImageStore(appContext)
    val pendingProfileCleanupStore: PendingProfileCleanupStore = PendingProfileCleanupStore(appContext)
    val activeAccountStore: ActiveAccountStore = ActiveAccountStore.fromContext(appContext)
    val accountManager: AccountManager = AccountManager(
        database = database,
        activeAccountStore = activeAccountStore,
        imageStore = templateImageStore,
        pendingProfileCleanupStore = pendingProfileCleanupStore,
        onClearAccountCookies = { accountId ->
            withTimeoutOrNull(3_000L) {
                withContext(Dispatchers.Main.immediate) {
                    suspendCancellableCoroutine { cont ->
                        WebViewProfileRegistry.clearAccountCookies(accountId) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                }
            }
        },
        onScheduleChanged = { scheduleCoordinator.rescheduleAll() },
        isAccountBusy = { accountId ->
            val live = ExecutionRuntimeBus.state.value
            live.running && (live.activeAccountId == null || live.activeAccountId == accountId)
        },
    )
    val backupManager: BackupManager = BackupManager(
        store = RoomBackupStore(database),
        onScheduleChanged = { scheduleCoordinator.rescheduleAll() },
    )
    val webExecutor: DouyinWebExecutor = DouyinWebExecutor(appContext, selectorBundleStore)

    val sendGuard: SendGuard = SendGuard
    val accountHealthReducer: AccountHealthReducer = AccountHealthReducer
    val templatePoolPicker: TemplatePoolPicker = TemplatePoolPicker

    init {
        ExecutionForegroundService.attachDatabase(database)
    }
}
