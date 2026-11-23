package io.github.oinzc.flamecontractor.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import io.github.oinzc.flamecontractor.R
import io.github.oinzc.flamecontractor.app.FlameApplication
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.scheduler.DailySchedulePlanner
import io.github.oinzc.flamecontractor.ui.shell.MainActivity
import io.github.oinzc.flamecontractor.ui.shell.MainNavState
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private data class WidgetQueryData(
    val account: AccountEntity?,
    val enabledTargets: List<ContractTargetEntity>,
    val todayJobs: List<SendJobEntity>,
    val unknownReviewCount: Int,
    val networkDeferCountToday: Int,
    val now: Instant,
)

private data class StatusPillStyle(
    @DrawableRes val bgRes: Int,
    @ColorRes val textColorRes: Int,
)

/**
 * 零新增依赖原生桌面状态小组件：
 * - 只读查询本地 SQLite 展示当前活跃账号的今日进度、健康状态与下次排程；
 * - 顶部右侧 `↻` 仅触发本地只读刷新广播（严禁触发任何发送动作）；
 * - 底部右侧胶囊在正常态跳转「好友/扫描对账」，在异常/待核对态切换为高对比「立即处理 →」跳转「今日」；
 * - 当宽度缩窄至 `2×2`（`OPTION_APPWIDGET_MIN_WIDTH in 1..200`）时自动隐藏右下角胶囊并将状态标签折行展示。
 */
class FlameStatusAppWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val appContext = context.applicationContext ?: context
            val manager = AppWidgetManager.getInstance(appContext) ?: return
            val component = ComponentName(appContext, FlameStatusAppWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids != null && ids.isNotEmpty()) {
                refreshWidgetsAsync(
                    context = appContext,
                    appWidgetManager = manager,
                    appWidgetIds = ids,
                    overrideWidthById = emptyMap(),
                )
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isEmpty()) return
        refreshWidgetsAsync(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetIds = appWidgetIds,
            overrideWidthById = emptyMap(),
        )
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        val minWidthDp = newOptions?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        refreshWidgetsAsync(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetIds = intArrayOf(appWidgetId),
            overrideWidthById = mapOf(appWidgetId to minWidthDp),
        )
    }

    private fun refreshWidgetsAsync(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        overrideWidthById: Map<Int, Int>,
    ) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext ?: context
        widgetScope.launch {
            try {
                withTimeoutOrNull(UPDATE_TIMEOUT_MS) {
                    val queryData = loadWidgetQueryData(appContext)
                    for (widgetId in appWidgetIds) {
                        val minWidthDp = overrideWidthById[widgetId]
                            ?: appWidgetManager.getAppWidgetOptions(widgetId)
                                ?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                            ?: 0
                        val snapshot = FlameStatusWidgetCalculator.calculate(
                            account = queryData.account,
                            enabledTargets = queryData.enabledTargets,
                            todayJobs = queryData.todayJobs,
                            unknownReviewCount = queryData.unknownReviewCount,
                            now = queryData.now,
                            minWidthDp = minWidthDp,
                            networkDeferCountToday = queryData.networkDeferCountToday,
                        )
                        val views = buildRemoteViews(appContext, snapshot)
                        appWidgetManager.updateAppWidget(widgetId, views)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (exc: Throwable) {
                Log.w(TAG, "Widget update failed: ${exc.javaClass.simpleName}")
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private suspend fun loadWidgetQueryData(appContext: Context): WidgetQueryData {
        val now = Instant.now()
        val container = (appContext as? FlameApplication)?.container
            ?: return WidgetQueryData(
                account = null,
                enabledTargets = emptyList(),
                todayJobs = emptyList(),
                unknownReviewCount = 0,
                networkDeferCountToday = 0,
                now = now,
            )

        val db = container.database
        val allAccounts = db.accountDao().listAll()
        val preferredAccountId = container.activeAccountStore.currentAccountId()
        val activeAccount = allAccounts.firstOrNull { it.id == preferredAccountId }
            ?: allAccounts.firstOrNull { it.id == DEFAULT_ACCOUNT_ID }
            ?: allAccounts.firstOrNull()

        if (activeAccount == null) {
            return WidgetQueryData(
                account = null,
                enabledTargets = emptyList(),
                todayJobs = emptyList(),
                unknownReviewCount = 0,
                networkDeferCountToday = 0,
                now = now,
            )
        }

        val zoneId = DailySchedulePlanner.resolveZoneId(activeAccount.timezone)
        val todayStr = ZonedDateTime.ofInstant(now, zoneId).toLocalDate().toString()
        val enabledTargets = db.targetDao().listEnabledTargets(activeAccount.id)
        val todayJobs = db.sendJobDao().listJobsForDate(activeAccount.id, todayStr)
        val unknownJobs = db.sendJobDao().listJobsByStatuses(
            accountId = activeAccount.id,
            statuses = listOf(JobStatus.UNKNOWN_REQUIRES_REVIEW),
        )
        val networkDeferCountToday = todayJobs.count {
            it.status == JobStatus.FAILED_RETRYABLE && it.failureClass == FailureClass.NETWORK
        }

        return WidgetQueryData(
            account = activeAccount,
            enabledTargets = enabledTargets,
            todayJobs = todayJobs,
            unknownReviewCount = unknownJobs.size,
            networkDeferCountToday = networkDeferCountToday,
            now = now,
        )
    }

    private fun buildRemoteViews(
        context: Context,
        snapshot: FlameStatusWidgetSnapshot,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_flame_status)

        views.setTextViewText(R.id.widget_account_name, snapshot.accountName)
        views.setTextViewText(R.id.widget_status_pill, snapshot.statusPillText)
        views.setTextViewText(R.id.widget_status_pill_compact, snapshot.statusPillText)
        views.setTextViewText(R.id.widget_progress_headline, snapshot.progressHeadline)
        views.setTextViewText(R.id.widget_footer_hint, snapshot.footerHint)
        views.setTextViewText(R.id.widget_action_pill, snapshot.actionLabel)

        val statusStyle = resolveStatusPillStyle(snapshot.statusTone)
        views.setInt(R.id.widget_status_pill, "setBackgroundResource", statusStyle.bgRes)
        views.setInt(R.id.widget_status_pill_compact, "setBackgroundResource", statusStyle.bgRes)
        applyTextColorResource(context, views, R.id.widget_status_pill, statusStyle.textColorRes)
        applyTextColorResource(context, views, R.id.widget_status_pill_compact, statusStyle.textColorRes)

        views.setViewVisibility(
            R.id.widget_status_pill,
            if (snapshot.isCompactWidth) View.GONE else View.VISIBLE,
        )
        views.setViewVisibility(
            R.id.widget_status_pill_compact,
            if (snapshot.isCompactWidth) View.VISIBLE else View.GONE,
        )
        views.setViewVisibility(
            R.id.widget_action_pill,
            if (snapshot.showActionPill) View.VISIBLE else View.GONE,
        )

        val actionBgRes = if (snapshot.isAlert) {
            R.drawable.widget_action_pill_alert
        } else {
            R.drawable.widget_action_pill_normal
        }
        val actionTextColorRes = if (snapshot.isAlert) {
            R.color.widget_action_alert_text
        } else {
            R.color.widget_action_normal_text
        }
        views.setInt(R.id.widget_action_pill, "setBackgroundResource", actionBgRes)
        applyTextColorResource(context, views, R.id.widget_action_pill, actionTextColorRes)

        views.setOnClickPendingIntent(
            R.id.widget_root,
            createLaunchPendingIntent(
                context = context,
                accountId = snapshot.accountId,
                category = MainNavState.SHORTCUT_TODAY,
                requestCode = REQUEST_CODE_OPEN_TODAY,
            ),
        )
        views.setOnClickPendingIntent(
            R.id.widget_action_pill,
            createLaunchPendingIntent(
                context = context,
                accountId = snapshot.accountId,
                category = snapshot.actionCategory,
                requestCode = if (snapshot.isAlert) REQUEST_CODE_ACTION_ALERT else REQUEST_CODE_ACTION_TARGETS,
            ),
        )
        views.setOnClickPendingIntent(
            R.id.widget_refresh_btn,
            createRefreshPendingIntent(context),
        )

        return views
    }

    private fun resolveStatusPillStyle(tone: WidgetStatusTone): StatusPillStyle = when (tone) {
        WidgetStatusTone.HEALTHY -> StatusPillStyle(
            bgRes = R.drawable.widget_status_pill_healthy,
            textColorRes = R.color.widget_status_healthy_text,
        )

        WidgetStatusTone.WARNING -> StatusPillStyle(
            bgRes = R.drawable.widget_status_pill_warning,
            textColorRes = R.color.widget_status_warning_text,
        )

        WidgetStatusTone.DANGER -> StatusPillStyle(
            bgRes = R.drawable.widget_status_pill_danger,
            textColorRes = R.color.widget_status_danger_text,
        )

        WidgetStatusTone.NEUTRAL -> StatusPillStyle(
            bgRes = R.drawable.widget_status_pill_neutral,
            textColorRes = R.color.widget_status_neutral_text,
        )

        WidgetStatusTone.RUNNING -> StatusPillStyle(
            bgRes = R.drawable.widget_status_pill_running,
            textColorRes = R.color.widget_status_running_text,
        )
    }

    private fun applyTextColorResource(
        context: Context,
        views: RemoteViews,
        viewId: Int,
        @ColorRes colorRes: Int,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColor(viewId, "setTextColor", colorRes)
        } else {
            views.setTextColor(viewId, ContextCompat.getColor(context, colorRes))
        }
    }

    private fun createLaunchPendingIntent(
        context: Context,
        accountId: String,
        category: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(LocalNotifier.EXTRA_ACCOUNT_ID, accountId)
            putExtra(LocalNotifier.EXTRA_URGENT_CATEGORY, category)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createRefreshPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, FlameStatusAppWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_REFRESH,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "io.github.oinzc.flamecontractor.widget.ACTION_REFRESH"

        private const val TAG = "FlameStatusWidget"
        private const val UPDATE_TIMEOUT_MS = 8_000L
        private const val REQUEST_CODE_OPEN_TODAY = 5201
        private const val REQUEST_CODE_ACTION_TARGETS = 5202
        private const val REQUEST_CODE_ACTION_ALERT = 5203
        private const val REQUEST_CODE_REFRESH = 5204

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * 桌面小组件安全刷新触发器：若桌面未放置小组件则直接空操作返回。
 */
object FlameStatusWidgetUpdater {

    fun requestUpdate(context: Context) {
        runCatching {
            val appContext = context.applicationContext ?: context
            val manager = AppWidgetManager.getInstance(appContext) ?: return
            val component = ComponentName(appContext, FlameStatusAppWidgetProvider::class.java)
            val appWidgetIds = manager.getAppWidgetIds(component)
            if (appWidgetIds == null || appWidgetIds.isEmpty()) return

            val updateIntent = Intent(appContext, FlameStatusAppWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
            }
            appContext.sendBroadcast(updateIntent)
        }
    }
}
