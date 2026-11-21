package io.github.oinzc.flamecontractor.app

import android.content.Context
import io.github.oinzc.flamecontractor.core.notify.BarkNotifier
import io.github.oinzc.flamecontractor.core.notify.EncryptedSecretStore
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundleStore
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.domain.AccountHealthReducer
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.domain.TemplatePoolPicker
import io.github.oinzc.flamecontractor.execution.DouyinWebExecutor
import io.github.oinzc.flamecontractor.execution.ExecutionForegroundService
import io.github.oinzc.flamecontractor.scheduler.ScheduleCoordinator

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
    val webExecutor: DouyinWebExecutor = DouyinWebExecutor(appContext, selectorBundleStore)

    val sendGuard: SendGuard = SendGuard
    val accountHealthReducer: AccountHealthReducer = AccountHealthReducer
    val templatePoolPicker: TemplatePoolPicker = TemplatePoolPicker

    init {
        ExecutionForegroundService.attachDatabase(database)
    }
}
