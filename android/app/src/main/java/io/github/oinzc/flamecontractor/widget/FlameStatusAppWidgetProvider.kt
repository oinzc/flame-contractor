package io.github.oinzc.flamecontractor.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import io.github.oinzc.flamecontractor.R
import io.github.oinzc.flamecontractor.app.FlameApplication
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.core.theme.ThemePreferencesStore
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class FlameWidgetKind(
    val title: String,
    val sizeBadge: String,
    val subtitle: String,
    val providerClass: Class<out AppWidgetProvider>,
) {
    RING_PANORAMA(
        title = "圆环全景卡",
        sizeBadge = "4×2",
        subtitle = "渐变进度圆环 + 最高火花 + 下次排程 + 快捷直达",
        providerClass = FlameStatusAppWidgetProvider::class.java,
    ),
    COMPACT_RING(
        title = "进度圆环（小号）",
        sizeBadge = "2×2",
        subtitle = "居中渐变圆环 + 最高火花与下次时刻，不占桌面空间",
        providerClass = FlameCompactRingWidgetProvider::class.java,
    ),
    MINI_STRIP(
        title = "极简状态条",
        sizeBadge = "4×1",
        subtitle = "单行超薄横条，仅占 1 行高度，一眼看今日进度",
        providerClass = FlameStripWidgetProvider::class.java,
    ),
    STREAK_7DAY(
        title = "7 日连胜卡",
        sizeBadge = "4×2",
        subtitle = "超大等宽数字进度条 + 近 7 日真实续火打卡方格",
        providerClass = FlameStreak7DayWidgetProvider::class.java,
    ),
    FRIENDS_BOARD(
        title = "好友火花榜",
        sizeBadge = "4×2",
        subtitle = "置顶展示 Top 3 好友今日发送状态与真实官方火花",
        providerClass = FlameFriendsListWidgetProvider::class.java,
    ),
    ;

    companion object {
        fun fromWireName(raw: String?): FlameWidgetKind? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

enum class WidgetPinOutcome {
    DISPATCHED,
    UNSUPPORTED,
    FAILED,
}

data class WidgetPinEvent(
    val kind: FlameWidgetKind,
    val timestampMs: Long = System.currentTimeMillis(),
)

object WidgetPinEventBus {
    private val _events = MutableSharedFlow<WidgetPinEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<WidgetPinEvent> = _events.asSharedFlow()

    internal fun emitPinned(kind: FlameWidgetKind) {
        _events.tryEmit(WidgetPinEvent(kind = kind))
    }
}

private data class WidgetQueryData(
    val account: AccountEntity?,
    val enabledTargets: List<ContractTargetEntity>,
    val todayJobs: List<SendJobEntity>,
    val recentJobs7Days: List<SendJobEntity>,
    val unknownReviewCount: Int,
    val networkDeferCountToday: Int,
    val now: Instant,
    val surface: ResolvedWidgetSurface,
    val hideFriendNames: Boolean,
)

private data class WidgetVisualTheme(
    val surface: ResolvedWidgetSurface,
    @DrawableRes val cardBgNormal: Int,
    @DrawableRes val cardBgAlert: Int,
    @DrawableRes val tileBg: Int,
    @DrawableRes val sparkPillBg: Int,
    @DrawableRes val refreshBg: Int,
    @DrawableRes val refreshIcon: Int,
    @DrawableRes val pendingFriendIcon: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textMuted: Int,
    val sparkColor: Int,
    val healthyColor: Int,
    val dangerColor: Int,
    val warningColor: Int,
)

/**
 * 5 款多尺寸小组件统一底座：只读查询 SQLite、统一应用独立外观主题与高清 Canvas 绘图。
 */
abstract class BaseFlameAppWidgetProvider(
    private val kind: FlameWidgetKind,
) : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext ?: context
        when (intent.action) {
            ACTION_REFRESH -> {
                FlameStatusWidgetUpdater.requestUpdateAll(appContext)
                return
            }

            ACTION_WIDGET_PINNED -> {
                val pinnedKind = FlameWidgetKind.fromWireName(intent.getStringExtra(EXTRA_WIDGET_KIND)) ?: kind
                WidgetPinEventBus.emitPinned(pinnedKind)
                FlameStatusWidgetUpdater.requestUpdateAll(appContext)
                return
            }
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isEmpty()) return
        refreshWidgetsAsync(context, appWidgetManager, appWidgetIds, emptyMap())
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
                    val theme = resolveVisualTheme(queryData.surface)
                    val density = appContext.resources.displayMetrics.density
                    for (widgetId in appWidgetIds) {
                        val widthDp = overrideWidthById[widgetId]
                            ?: runCatching {
                                appWidgetManager.getAppWidgetOptions(widgetId)
                                    ?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                                    ?: 0
                            }.getOrDefault(0)

                        val snapshot = FlameStatusWidgetCalculator.calculate(
                            account = queryData.account,
                            enabledTargets = queryData.enabledTargets,
                            todayJobs = queryData.todayJobs,
                            unknownReviewCount = queryData.unknownReviewCount,
                            now = queryData.now,
                            minWidthDp = widthDp,
                            networkDeferCountToday = queryData.networkDeferCountToday,
                            recentJobs7Days = queryData.recentJobs7Days,
                        )
                        val views = buildRemoteViews(
                            context = appContext,
                            snapshot = snapshot,
                            theme = theme,
                            density = density,
                            hideFriendNames = queryData.hideFriendNames,
                        )
                        appWidgetManager.updateAppWidget(widgetId, views)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (exc: Throwable) {
                Log.w(TAG, "刷新小组件(${kind.name})失败: ${exc.javaClass.simpleName}")
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    private suspend fun loadWidgetQueryData(context: Context): WidgetQueryData {
        val now = Instant.now()
        val widgetPrefs = WidgetPreferencesStore.fromContext(context).preferences.value
        val appThemeMode = ThemePreferencesStore.fromContext(context).preference.value.mode
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val surface = WidgetPreferencesStore.resolveSurface(
            style = widgetPrefs.appearanceStyle,
            appThemeMode = appThemeMode,
            systemDark = systemDark,
        )

        val app = context.applicationContext as? FlameApplication
            ?: return WidgetQueryData(
                account = null,
                enabledTargets = emptyList(),
                todayJobs = emptyList(),
                recentJobs7Days = emptyList(),
                unknownReviewCount = 0,
                networkDeferCountToday = 0,
                now = now,
                surface = surface,
                hideFriendNames = widgetPrefs.hideFriendNames,
            )

        val container = app.container
        val database = container.database
        val activeId = container.activeAccountStore.activeAccountId.value
            .trim()
            .ifEmpty { DEFAULT_ACCOUNT_ID }

        val account = database.accountDao().getAccount(activeId)
            ?: database.accountDao().listAll().firstOrNull()
        val resolvedAccountId = account?.id ?: activeId
        val zoneId = DailySchedulePlanner.resolveZoneId(account?.timezone)
        val todayDate = ZonedDateTime.ofInstant(now, zoneId).toLocalDate()
        val todayStr = todayDate.toString()
        val since7DaysStr = todayDate.minusDays(6).toString()

        val enabledTargets = database.targetDao().listEnabledTargets(resolvedAccountId)
        val todayJobs = database.sendJobDao().listJobsForDate(resolvedAccountId, todayStr)
        val recentJobs7Days = runCatching {
            database.sendJobDao().observeJobsSince(resolvedAccountId, since7DaysStr).first()
        }.getOrDefault(todayJobs)

        val unknownReviewCount = database.sendJobDao()
            .listJobsByStatuses(resolvedAccountId, listOf(JobStatus.UNKNOWN_REQUIRES_REVIEW))
            .size

        val networkDeferCountToday = todayJobs.count {
            it.status == JobStatus.FAILED_RETRYABLE && it.failureClass == FailureClass.NETWORK
        }

        return WidgetQueryData(
            account = account,
            enabledTargets = enabledTargets,
            todayJobs = todayJobs,
            recentJobs7Days = recentJobs7Days,
            unknownReviewCount = unknownReviewCount,
            networkDeferCountToday = networkDeferCountToday,
            now = now,
            surface = surface,
            hideFriendNames = widgetPrefs.hideFriendNames,
        )
    }

    private fun buildRemoteViews(
        context: Context,
        snapshot: FlameStatusWidgetSnapshot,
        theme: WidgetVisualTheme,
        density: Float,
        hideFriendNames: Boolean,
    ): RemoteViews = when (kind) {
        FlameWidgetKind.RING_PANORAMA -> buildRingPanoramaViews(context, snapshot, theme, density)
        FlameWidgetKind.COMPACT_RING -> buildCompactRingViews(context, snapshot, theme, density)
        FlameWidgetKind.MINI_STRIP -> buildMiniStripViews(context, snapshot, theme)
        FlameWidgetKind.STREAK_7DAY -> buildStreak7DayViews(context, snapshot, theme, density)
        FlameWidgetKind.FRIENDS_BOARD -> buildFriendsBoardViews(context, snapshot, theme, hideFriendNames)
    }

    private fun buildRingPanoramaViews(
        context: Context,
        snapshot: FlameStatusWidgetSnapshot,
        theme: WidgetVisualTheme,
        density: Float,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_flame_status)
        views.setInt(
            R.id.widget_root,
            "setBackgroundResource",
            if (snapshot.isAlert) theme.cardBgAlert else theme.cardBgNormal,
        )

        val ringBitmap = WidgetCanvasRenderer.renderProgressRingBitmap(
            density = density,
            sizeDp = 100f,
            strokeDp = 8.5f,
            progressFraction = snapshot.progressFraction,
            isAllDone = snapshot.isAllDoneToday,
            isAlert = snapshot.isAlert,
            isDarkSurface = theme.surface.isDark,
        )
        views.setImageViewBitmap(R.id.widget_ring_image, ringBitmap)
        views.setTextViewText(
            R.id.widget_ring_primary,
            if (snapshot.isAlert) "!" else "${snapshot.succeededTodayCount}/${snapshot.enabledTargetCount}",
        )
        views.setTextColor(
            R.id.widget_ring_primary,
            when {
                snapshot.isAlert -> theme.dangerColor
                snapshot.isAllDoneToday -> theme.healthyColor
                else -> theme.textPrimary
            },
        )
        views.setTextViewText(
            R.id.widget_ring_caption,
            when {
                snapshot.isAlert -> "需人工处理"
                snapshot.isAllDoneToday -> "✓ 已完成"
                else -> "今日进度"
            },
        )
        views.setTextColor(
            R.id.widget_ring_caption,
            when {
                snapshot.isAlert -> theme.dangerColor
                snapshot.isAllDoneToday -> theme.healthyColor
                else -> theme.textSecondary
            },
        )

        views.setTextViewText(R.id.widget_account_name, snapshot.accountName)
        views.setTextColor(R.id.widget_account_name, theme.textPrimary)
        bindStatusPill(views, R.id.widget_status_pill, snapshot.statusPillText, snapshot.statusTone, theme)
        bindRefreshButton(context, views, theme)

        if (snapshot.isAlert) {
            views.setViewVisibility(R.id.widget_kpi_row, View.GONE)
            views.setViewVisibility(R.id.widget_alert_summary, View.VISIBLE)
            views.setTextViewText(R.id.widget_alert_summary, snapshot.alertDetailText)
            views.setTextColor(R.id.widget_alert_summary, theme.textSecondary)

            views.setInt(R.id.widget_action_bar, "setBackgroundResource", R.drawable.widget_cta_alert_bg)
            views.setTextViewText(
                R.id.widget_action_left,
                if (snapshot.unknownReviewCount > 0) "查看现场截图并终裁" else "打开应用查看详情",
            )
            views.setTextColor(R.id.widget_action_left, Color.WHITE)
            views.setTextViewText(R.id.widget_action_right, "立即处理 →")
            views.setTextColor(R.id.widget_action_right, Color.WHITE)
        } else {
            views.setViewVisibility(R.id.widget_kpi_row, View.VISIBLE)
            views.setViewVisibility(R.id.widget_alert_summary, View.GONE)

            views.setInt(R.id.widget_kpi_spark_tile, "setBackgroundResource", theme.tileBg)
            views.setInt(R.id.widget_kpi_next_tile, "setBackgroundResource", theme.tileBg)
            views.setTextColor(R.id.widget_kpi_spark_label, theme.textMuted)
            views.setTextColor(R.id.widget_kpi_next_label, theme.textMuted)
            views.setTextViewText(R.id.widget_kpi_spark_value, snapshot.sparkHighlightText)
            views.setTextColor(R.id.widget_kpi_spark_value, theme.sparkColor)
            views.setTextViewText(R.id.widget_kpi_next_value, snapshot.nextScheduleShort)
            views.setTextColor(R.id.widget_kpi_next_value, theme.textPrimary)

            views.setInt(R.id.widget_action_bar, "setBackgroundResource", theme.tileBg)
            val leftHint = when {
                snapshot.enabledTargetCount == 0 -> "先去导入续火好友"
                snapshot.isAllDoneToday -> "今日已全部续完"
                else -> "待续 ${snapshot.remainingCount} 位"
            }
            views.setTextViewText(R.id.widget_action_left, leftHint)
            views.setTextColor(R.id.widget_action_left, theme.textSecondary)
            views.setTextViewText(R.id.widget_action_right, "扫描对账 →")
            views.setTextColor(R.id.widget_action_right, theme.textPrimary)
        }

        views.setOnClickPendingIntent(
            R.id.widget_root,
            createLaunchPendingIntent(context, snapshot.accountId, MainNavState.SHORTCUT_TODAY, 5301),
        )
        views.setOnClickPendingIntent(
            R.id.widget_action_bar,
            createLaunchPendingIntent(
                context = context,
                accountId = snapshot.accountId,
                category = snapshot.actionCategory,
                requestCode = if (snapshot.isAlert) 5302 else 5303,
            ),
        )
        return views
    }

    private fun buildCompactRingViews(
        context: Context,
        snapshot: FlameStatusWidgetSnapshot,
        theme: WidgetVisualTheme,
        density: Float,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_flame_compact_ring)
        views.setInt(
            R.id.widget_root,
            "setBackgroundResource",
            if (snapshot.isAlert) theme.cardBgAlert else theme.cardBgNormal,
        )

        val headerText = when {
            snapshot.isAlert -> "● ${snapshot.statusLabel}"
            snapshot.isAllDoneToday -> "● 已全部续完"
            else -> "● ${snapshot.accountName}"
        }
        views.setTextViewText(R.id.widget_compact_header, headerText)
        views.setTextColor(
            R.id.widget_compact_header,
            when {
                snapshot.isAlert -> theme.dangerColor
                snapshot.isAllDoneToday -> theme.healthyColor
                else -> theme.textPrimary
            },
        )
        bindRefreshButton(context, views, theme)

        val ringBitmap = WidgetCanvasRenderer.renderProgressRingBitmap(
            density = density,
            sizeDp = 84f,
            strokeDp = 7.5f,
            progressFraction = snapshot.progressFraction,
            isAllDone = snapshot.isAllDoneToday,
            isAlert = snapshot.isAlert,
            isDarkSurface = theme.surface.isDark,
        )
        views.setImageViewBitmap(R.id.widget_ring_image, ringBitmap)
        views.setTextViewText(
            R.id.widget_ring_primary,
            if (snapshot.isAlert) "!" else "${snapshot.succeededTodayCount}/${snapshot.enabledTargetCount}",
        )
        views.setTextColor(
            R.id.widget_ring_primary,
            when {
                snapshot.isAlert -> theme.dangerColor
                snapshot.isAllDoneToday -> theme.healthyColor
                else -> theme.textPrimary
            },
        )
        views.setTextViewText(
            R.id.widget_ring_caption,
            when {
                snapshot.isAlert -> "点此处理"
                snapshot.isAllDoneToday -> "✓ 已完成"
                else -> "今日续火"
            },
        )
        views.setTextColor(
            R.id.widget_ring_caption,
            when {
                snapshot.isAlert -> theme.dangerColor
                snapshot.isAllDoneToday -> theme.healthyColor
                else -> theme.textSecondary
            },
        )

        views.setTextViewText(R.id.widget_compact_spark, snapshot.sparkCompactText)
        views.setTextColor(R.id.widget_compact_spark, theme.sparkColor)
        views.setTextViewText(R.id.widget_compact_next, snapshot.nextScheduleShort)
        views.setTextColor(R.id.widget_compact_next, theme.textSecondary)

        views.setOnClickPendingIntent(
            R.id.widget_root,
            createLaunchPendingIntent(context, snapshot.accountId, MainNavState.SHORTCUT_TODAY, 5311),
        )
        return views
    }

    private fun buildMiniStripViews(
        context: Context,
        snapshot: FlameStatusWidgetSnapshot,
        theme: WidgetVisualTheme,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_flame_mini_strip)
        views.setInt(
            R.id.widget_root,
            "setBackgroundResource",
            if (snapshot.isAlert) theme.cardBgAlert else theme.cardBgNormal,
        )
        views.setTextViewText(
            R.id.widget_strip_progress,
            if (snapshot.enabledTargetCount == 0) "暂无好友" else "今日 ${snapshot.succeededTodayCount}/${snapshot.enabledTargetCount}",
        )
        views.setTextColor(R.id.widget_strip_progress, theme.textPrimary)

        val compactStatus = if (snapshot.statusLabel == "运行正常") "● 正常" else snapshot.statusPillText
        bindStatusPill(views, R.id.widget_status_pill, compactStatus, snapshot.statusTone, theme)

        val metaText = if (snapshot.isAlert) {
            snapshot.footerHint
        } else {
            "${snapshot.sparkHighlightText.removePrefix("🔥 ").let { "最高 $it" }} · 下次 ${snapshot.nextScheduleShort}"
        }
        views.setTextViewText(R.id.widget_strip_meta, metaText)
        views.setTextColor(R.id.widget_strip_meta, theme.textSecondary)
        bindRefreshButton(context, views, theme)

        if (snapshot.isAlert) {
            views.setInt(R.id.widget_action_pill, "setBackgroundResource", R.drawable.widget_cta_alert_bg)
            views.setTextViewText(R.id.widget_action_pill, "处理 ›")
            views.setTextColor(R.id.widget_action_pill, Color.WHITE)
        } else {
            views.setInt(R.id.widget_action_pill, "setBackgroundResource", theme.tileBg)
            views.setTextViewText(R.id.widget_action_pill, "对账 ›")
            views.setTextColor(R.id.widget_action_pill, theme.textPrimary)
        }

        views.setOnClickPendingIntent(
            R.id.widget_root,
            createLaunchPendingIntent(context, snapshot.accountId, MainNavState.SHORTCUT_TODAY, 5321),
        )
        views.setOnClickPendingIntent(
            R.id.widget_action_pill,
            createLaunchPendingIntent(
                context = context,
                accountId = snapshot.accountId,
                category = snapshot.actionCategory,
                requestCode = if (snapshot.isAlert) 5322 else 5323,
            ),
        )
        return views
    }

    private fun buildStreak7DayViews(
        context: Context,
        snapshot: FlameStatusWidgetSnapshot,
        theme: WidgetVisualTheme,
        density: Float,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_flame_streak_7day)
        views.setInt(
            R.id.widget_root,
            "setBackgroundResource",
            if (snapshot.isAlert) theme.cardBgAlert else theme.cardBgNormal,
        )
        views.setTextViewText(R.id.widget_account_name, "🔥 ${snapshot.accountName}")
        views.setTextColor(R.id.widget_account_name, theme.textPrimary)
        bindStatusPill(views, R.id.widget_status_pill, snapshot.statusPillText, snapshot.statusTone, theme)
        views.setTextViewText(R.id.widget_next_time, snapshot.nextScheduleShort)
        views.setTextColor(R.id.widget_next_time, theme.textMuted)
        bindRefreshButton(context, views, theme)

        views.setTextViewText(R.id.widget_hero_done, snapshot.succeededTodayCount.toString())
        views.setTextColor(
            R.id.widget_hero_done,
            when {
                snapshot.isAlert -> theme.dangerColor
                snapshot.isAllDoneToday -> theme.healthyColor
                else -> theme.textPrimary
            },
        )
        views.setTextViewText(
            R.id.widget_hero_total,
            if (snapshot.enabledTargetCount == 0) "位 · 暂未启用好友" else "/ ${snapshot.enabledTargetCount} 位已完成",
        )
        views.setTextColor(R.id.widget_hero_total, theme.textSecondary)

        views.setInt(R.id.widget_spark_pill, "setBackgroundResource", theme.sparkPillBg)
        views.setTextViewText(R.id.widget_spark_pill, "最高 ${snapshot.sparkHighlightText}")
        views.setTextColor(R.id.widget_spark_pill, theme.sparkColor)

        val barBitmap = WidgetCanvasRenderer.renderProgressBarBitmap(
            density = density,
            widthDp = 280f,
            heightDp = 7f,
            progressFraction = snapshot.progressFraction,
            isAllDone = snapshot.isAllDoneToday,
            isAlert = snapshot.isAlert,
            isDarkSurface = theme.surface.isDark,
        )
        views.setImageViewBitmap(R.id.widget_progress_bar_image, barBitmap)

        views.setTextColor(R.id.widget_7day_label, theme.textMuted)
        val stripBitmap = WidgetCanvasRenderer.render7DayStripBitmap(
            density = density,
            days = snapshot.recent7Days.ifEmpty { List(7) { WidgetDayStatus.EMPTY } },
            isDarkSurface = theme.surface.isDark,
        )
        views.setImageViewBitmap(R.id.widget_7day_strip_image, stripBitmap)

        views.setTextViewText(R.id.widget_action_link, snapshot.actionLabel)
        views.setTextColor(
            R.id.widget_action_link,
            if (snapshot.isAlert) theme.dangerColor else theme.textPrimary,
        )

        views.setOnClickPendingIntent(
            R.id.widget_root,
            createLaunchPendingIntent(context, snapshot.accountId, MainNavState.SHORTCUT_TODAY, 5331),
        )
        views.setOnClickPendingIntent(
            R.id.widget_action_link,
            createLaunchPendingIntent(
                context = context,
                accountId = snapshot.accountId,
                category = snapshot.actionCategory,
                requestCode = if (snapshot.isAlert) 5332 else 5333,
            ),
        )
        return views
    }

    private fun buildFriendsBoardViews(
        context: Context,
        snapshot: FlameStatusWidgetSnapshot,
        theme: WidgetVisualTheme,
        hideFriendNames: Boolean,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_flame_friends_board)
        views.setInt(
            R.id.widget_root,
            "setBackgroundResource",
            if (snapshot.isAlert) theme.cardBgAlert else theme.cardBgNormal,
        )
        views.setTextColor(R.id.widget_friends_title, theme.textPrimary)
        views.setTextViewText(
            R.id.widget_friends_progress,
            "${snapshot.succeededTodayCount}/${snapshot.enabledTargetCount}",
        )
        views.setTextColor(R.id.widget_friends_progress, theme.sparkColor)

        val pillText = if (snapshot.isAlert) snapshot.statusPillText else "${snapshot.nextScheduleShort} 自动"
        val pillTone = if (snapshot.isAlert) snapshot.statusTone else WidgetStatusTone.HEALTHY
        bindStatusPill(views, R.id.widget_status_pill, pillText, pillTone, theme)
        bindRefreshButton(context, views, theme)

        val rowSlots = listOf(
            Triple(R.id.widget_friend_row_1, R.id.widget_friend_icon_1, R.id.widget_friend_name_1 to R.id.widget_friend_badge_1),
            Triple(R.id.widget_friend_row_2, R.id.widget_friend_icon_2, R.id.widget_friend_name_2 to R.id.widget_friend_badge_2),
            Triple(R.id.widget_friend_row_3, R.id.widget_friend_icon_3, R.id.widget_friend_name_3 to R.id.widget_friend_badge_3),
        )

        if (snapshot.topFriends.isEmpty()) {
            views.setViewVisibility(R.id.widget_friends_rows, View.GONE)
            views.setViewVisibility(R.id.widget_friends_empty, View.VISIBLE)
            views.setTextColor(R.id.widget_friends_empty, theme.textSecondary)
        } else {
            views.setViewVisibility(R.id.widget_friends_rows, View.VISIBLE)
            views.setViewVisibility(R.id.widget_friends_empty, View.GONE)
            rowSlots.forEachIndexed { idx, (rowId, iconId, textPair) ->
                val (nameId, badgeId) = textPair
                val item = snapshot.topFriends.getOrNull(idx)
                if (item == null) {
                    views.setViewVisibility(rowId, View.INVISIBLE)
                } else {
                    views.setViewVisibility(rowId, View.VISIBLE)
                    views.setInt(rowId, "setBackgroundResource", theme.tileBg)
                    views.setImageViewResource(
                        iconId,
                        if (item.isSentToday) R.drawable.widget_ic_friend_done else theme.pendingFriendIcon,
                    )
                    views.setTextViewText(
                        nameId,
                        if (hideFriendNames) item.maskedName else item.displayName,
                    )
                    views.setTextColor(nameId, if (item.isSentToday) theme.textPrimary else theme.textSecondary)
                    views.setTextViewText(badgeId, item.badgeText)
                    if (item.isSparkBadge) {
                        views.setInt(badgeId, "setBackgroundResource", theme.sparkPillBg)
                        views.setTextColor(badgeId, theme.sparkColor)
                    } else {
                        views.setInt(
                            badgeId,
                            "setBackgroundResource",
                            if (theme.surface.isDark) R.drawable.widget_pill_neu_dark else R.drawable.widget_pill_neu_light,
                        )
                        views.setTextColor(
                            badgeId,
                            if (item.isSentToday) theme.healthyColor else theme.warningColor,
                        )
                    }
                }
            }
        }

        views.setOnClickPendingIntent(
            R.id.widget_root,
            createLaunchPendingIntent(
                context = context,
                accountId = snapshot.accountId,
                category = if (snapshot.isAlert) MainNavState.SHORTCUT_TODAY else MainNavState.SHORTCUT_TARGETS,
                requestCode = 5341,
            ),
        )
        return views
    }

    private fun bindStatusPill(
        views: RemoteViews,
        viewId: Int,
        text: String,
        tone: WidgetStatusTone,
        theme: WidgetVisualTheme,
    ) {
        val isDark = theme.surface.isDark
        val (bgRes, fgColor) = when (tone) {
            WidgetStatusTone.HEALTHY -> (if (isDark) R.drawable.widget_pill_ok_dark else R.drawable.widget_pill_ok_light) to theme.healthyColor
            WidgetStatusTone.RUNNING -> (if (isDark) R.drawable.widget_pill_ok_dark else R.drawable.widget_pill_ok_light) to theme.healthyColor
            WidgetStatusTone.WARNING -> (if (isDark) R.drawable.widget_pill_warn_dark else R.drawable.widget_pill_warn_light) to theme.warningColor
            WidgetStatusTone.DANGER -> (if (isDark) R.drawable.widget_pill_err_dark else R.drawable.widget_pill_err_light) to theme.dangerColor
            WidgetStatusTone.NEUTRAL -> (if (isDark) R.drawable.widget_pill_neu_dark else R.drawable.widget_pill_neu_light) to theme.textSecondary
        }
        views.setInt(viewId, "setBackgroundResource", bgRes)
        views.setTextViewText(viewId, text)
        views.setTextColor(viewId, fgColor)
    }

    private fun bindRefreshButton(
        context: Context,
        views: RemoteViews,
        theme: WidgetVisualTheme,
    ) {
        views.setInt(R.id.widget_refresh_button, "setBackgroundResource", theme.refreshBg)
        views.setImageViewResource(R.id.widget_refresh_button, theme.refreshIcon)
        views.setOnClickPendingIntent(
            R.id.widget_refresh_button,
            createRefreshPendingIntent(context),
        )
    }

    private fun resolveVisualTheme(surface: ResolvedWidgetSurface): WidgetVisualTheme = when (surface) {
        ResolvedWidgetSurface.LIGHT -> WidgetVisualTheme(
            surface = surface,
            cardBgNormal = R.drawable.widget_bg_light,
            cardBgAlert = R.drawable.widget_bg_alert_light,
            tileBg = R.drawable.widget_tile_light,
            sparkPillBg = R.drawable.widget_spark_pill_light,
            refreshBg = R.drawable.widget_refresh_bg_light,
            refreshIcon = R.drawable.widget_ic_refresh_light,
            pendingFriendIcon = R.drawable.widget_ic_friend_pending_light,
            textPrimary = Color.parseColor("#FF111318"),
            textSecondary = Color.parseColor("#FF4B5262"),
            textMuted = Color.parseColor("#FF6B7280"),
            sparkColor = Color.parseColor("#FFEA580C"),
            healthyColor = Color.parseColor("#FF059669"),
            dangerColor = Color.parseColor("#FFDC2626"),
            warningColor = Color.parseColor("#FFB45309"),
        )

        ResolvedWidgetSurface.DARK_CARBON -> WidgetVisualTheme(
            surface = surface,
            cardBgNormal = R.drawable.widget_bg_dark,
            cardBgAlert = R.drawable.widget_bg_alert_dark,
            tileBg = R.drawable.widget_tile_dark,
            sparkPillBg = R.drawable.widget_spark_pill_dark,
            refreshBg = R.drawable.widget_refresh_bg_dark,
            refreshIcon = R.drawable.widget_ic_refresh_dark,
            pendingFriendIcon = R.drawable.widget_ic_friend_pending_dark,
            textPrimary = Color.parseColor("#FFF4F5F7"),
            textSecondary = Color.parseColor("#FFA9B1BF"),
            textMuted = Color.parseColor("#FF8A92A3"),
            sparkColor = Color.parseColor("#FFFB923C"),
            healthyColor = Color.parseColor("#FF34D399"),
            dangerColor = Color.parseColor("#FFF87171"),
            warningColor = Color.parseColor("#FFFBBF24"),
        )

        ResolvedWidgetSurface.GLASS_DARK -> WidgetVisualTheme(
            surface = surface,
            cardBgNormal = R.drawable.widget_bg_glass,
            cardBgAlert = R.drawable.widget_bg_alert_dark,
            tileBg = R.drawable.widget_tile_glass,
            sparkPillBg = R.drawable.widget_spark_pill_dark,
            refreshBg = R.drawable.widget_refresh_bg_dark,
            refreshIcon = R.drawable.widget_ic_refresh_dark,
            pendingFriendIcon = R.drawable.widget_ic_friend_pending_dark,
            textPrimary = Color.parseColor("#FFF8FAFC"),
            textSecondary = Color.parseColor("#FFCBD5E1"),
            textMuted = Color.parseColor("#FF94A3B8"),
            sparkColor = Color.parseColor("#FFFB923C"),
            healthyColor = Color.parseColor("#FF34D399"),
            dangerColor = Color.parseColor("#FFF87171"),
            warningColor = Color.parseColor("#FFFBBF24"),
        )
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
        val intent = Intent(context, kind.providerClass).apply {
            action = ACTION_REFRESH
        }
        return PendingIntent.getBroadcast(
            context,
            5400 + kind.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "io.github.oinzc.flamecontractor.widget.ACTION_REFRESH"
        const val ACTION_WIDGET_PINNED = "io.github.oinzc.flamecontractor.widget.ACTION_WIDGET_PINNED"
        const val EXTRA_WIDGET_KIND = "extra_widget_kind"

        private const val TAG = "FlameWidgets"
        private const val UPDATE_TIMEOUT_MS = 8_000L
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * ③ 圆环全景卡（4×2）：保留原类名，保证已放置的老版桌面小组件无缝升级。
 */
class FlameStatusAppWidgetProvider : BaseFlameAppWidgetProvider(FlameWidgetKind.RING_PANORAMA)

/** ① 进度圆环小号（2×2） */
class FlameCompactRingWidgetProvider : BaseFlameAppWidgetProvider(FlameWidgetKind.COMPACT_RING)

/** ② 极简状态条（4×1） */
class FlameStripWidgetProvider : BaseFlameAppWidgetProvider(FlameWidgetKind.MINI_STRIP)

/** ④ 7 日连胜卡（4×2） */
class FlameStreak7DayWidgetProvider : BaseFlameAppWidgetProvider(FlameWidgetKind.STREAK_7DAY)

/** ⑤ 好友火花榜（4×2） */
class FlameFriendsListWidgetProvider : BaseFlameAppWidgetProvider(FlameWidgetKind.FRIENDS_BOARD)

/**
 * 5 款多尺寸桌面小组件统一刷新与防重复固定触发器。
 */
object FlameStatusWidgetUpdater {

    fun getPlacedCountsByKind(context: Context): Map<FlameWidgetKind, Int> {
        val appContext = context.applicationContext ?: context
        val manager = runCatching { AppWidgetManager.getInstance(appContext) }.getOrNull()
            ?: return FlameWidgetKind.entries.associateWith { 0 }
        return FlameWidgetKind.entries.associateWith { kind ->
            runCatching {
                val component = ComponentName(appContext, kind.providerClass)
                manager.getAppWidgetIds(component)?.size ?: 0
            }.getOrDefault(0)
        }
    }

    fun getPlacedWidgetCount(context: Context): Int =
        getPlacedCountsByKind(context).values.sum()

    fun requestPinWidget(context: Context, kind: FlameWidgetKind): WidgetPinOutcome {
        return runCatching {
            val appContext = context.applicationContext ?: context
            val manager = AppWidgetManager.getInstance(appContext) ?: return WidgetPinOutcome.UNSUPPORTED
            if (!manager.isRequestPinAppWidgetSupported) {
                return WidgetPinOutcome.UNSUPPORTED
            }
            val component = ComponentName(appContext, kind.providerClass)
            val callbackIntent = Intent(appContext, kind.providerClass).apply {
                action = BaseFlameAppWidgetProvider.ACTION_WIDGET_PINNED
                putExtra(BaseFlameAppWidgetProvider.EXTRA_WIDGET_KIND, kind.name)
            }
            val successCallback = PendingIntent.getBroadcast(
                appContext,
                5500 + kind.ordinal,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val accepted = manager.requestPinAppWidget(component, null, successCallback)
            if (accepted) WidgetPinOutcome.DISPATCHED else WidgetPinOutcome.UNSUPPORTED
        }.getOrElse { WidgetPinOutcome.FAILED }
    }

    fun requestUpdate(context: Context) = requestUpdateAll(context)

    fun requestUpdateAll(context: Context) {
        val appContext = context.applicationContext ?: context
        val manager = runCatching { AppWidgetManager.getInstance(appContext) }.getOrNull() ?: return
        for (kind in FlameWidgetKind.entries) {
            runCatching {
                val component = ComponentName(appContext, kind.providerClass)
                val ids = manager.getAppWidgetIds(component)
                if (ids != null && ids.isNotEmpty()) {
                    val intent = Intent(appContext, kind.providerClass).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                    appContext.sendBroadcast(intent)
                }
            }
        }
    }
}
