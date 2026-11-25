package io.github.oinzc.flamecontractor.demo

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.RemoteViews
import io.github.oinzc.flamecontractor.app.FlameApplication
import io.github.oinzc.flamecontractor.widget.FlameStatusAppWidgetProvider
import io.github.oinzc.flamecontractor.widget.FlameStatusWidgetSnapshot
import io.github.oinzc.flamecontractor.widget.FlameWidgetKind
import io.github.oinzc.flamecontractor.widget.ResolvedWidgetSurface
import io.github.oinzc.flamecontractor.widget.WidgetRefreshPhase
import io.github.oinzc.flamecontractor.widget.WidgetRefreshState
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 演示变体专用真实桌面小组件渲染抓取器（仅编译进 `demo` APK）。
 * 继承原生 [Activity] 并传入 `applicationContext` 给 [RemoteViews.apply]，
 * 与系统桌面 `AppWidgetHostView` 使用完全一致的原生 framework View 充气链路（避免 AppCompatImageView 替换），
 * 在真机物理密度下输出保留原生透明通道的 ARGB_8888 PNG。
 */
class WidgetCaptureActivity : Activity() {

    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class CaptureJob(
        val fileName: String,
        val kind: FlameWidgetKind,
        val widthDp: Int,
        val heightDp: Int,
        val surface: ResolvedWidgetSurface,
        val snapshot: FlameStatusWidgetSnapshot,
        val hideFriendNames: Boolean = false,
        val refreshState: WidgetRefreshState = WidgetRefreshState.IDLE,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val stage = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        setContentView(stage)

        val container = (application as FlameApplication).container

        captureScope.launch {
            try {
                val dataset = DemoDataSeeder.seedDatabase(applicationContext, container)
                val inProgress = DemoDataSeeder.buildInProgressSnapshot(dataset)
                val allDone = DemoDataSeeder.buildAllDoneSnapshot(dataset)
                val alert = DemoDataSeeder.buildAlertSnapshot(dataset)

                val jobs = listOf(
                    // Column A: 半透明深色玻璃 (GLASS_DARK)
                    CaptureJob(
                        fileName = "widget_glass_compact_ring_progress.png",
                        kind = FlameWidgetKind.COMPACT_RING,
                        widthDp = 164,
                        heightDp = 164,
                        surface = ResolvedWidgetSurface.GLASS_DARK,
                        snapshot = inProgress,
                    ),
                    CaptureJob(
                        fileName = "widget_glass_compact_ring_done.png",
                        kind = FlameWidgetKind.COMPACT_RING,
                        widthDp = 164,
                        heightDp = 164,
                        surface = ResolvedWidgetSurface.GLASS_DARK,
                        snapshot = allDone,
                    ),
                    CaptureJob(
                        fileName = "widget_glass_mini_strip.png",
                        kind = FlameWidgetKind.MINI_STRIP,
                        widthDp = 340,
                        heightDp = 76,
                        surface = ResolvedWidgetSurface.GLASS_DARK,
                        snapshot = inProgress,
                    ),
                    CaptureJob(
                        fileName = "widget_glass_ring_panorama.png",
                        kind = FlameWidgetKind.RING_PANORAMA,
                        widthDp = 340,
                        heightDp = 164,
                        surface = ResolvedWidgetSurface.GLASS_DARK,
                        snapshot = inProgress,
                    ),
                    CaptureJob(
                        fileName = "widget_glass_ring_panorama_refreshing.png",
                        kind = FlameWidgetKind.RING_PANORAMA,
                        widthDp = 340,
                        heightDp = 164,
                        surface = ResolvedWidgetSurface.GLASS_DARK,
                        snapshot = inProgress,
                        refreshState = WidgetRefreshState(phase = WidgetRefreshPhase.REFRESHING),
                    ),
                    // Column B: 深色碳黑 (DARK_CARBON)
                    CaptureJob(
                        fileName = "widget_dark_streak_7day.png",
                        kind = FlameWidgetKind.STREAK_7DAY,
                        widthDp = 340,
                        heightDp = 164,
                        surface = ResolvedWidgetSurface.DARK_CARBON,
                        snapshot = inProgress,
                        refreshState = WidgetRefreshState(
                            phase = WidgetRefreshPhase.JUST_UPDATED,
                            updatedAtHhmm = "21:32",
                        ),
                    ),
                    CaptureJob(
                        fileName = "widget_dark_friends_masked.png",
                        kind = FlameWidgetKind.FRIENDS_BOARD,
                        widthDp = 340,
                        heightDp = 164,
                        surface = ResolvedWidgetSurface.DARK_CARBON,
                        snapshot = inProgress,
                        hideFriendNames = true,
                    ),
                    CaptureJob(
                        fileName = "widget_dark_ring_panorama_alert.png",
                        kind = FlameWidgetKind.RING_PANORAMA,
                        widthDp = 340,
                        heightDp = 164,
                        surface = ResolvedWidgetSurface.DARK_CARBON,
                        snapshot = alert,
                    ),
                    // Column C: 浅色纯白 (LIGHT)
                    CaptureJob(
                        fileName = "widget_light_ring_panorama.png",
                        kind = FlameWidgetKind.RING_PANORAMA,
                        widthDp = 340,
                        heightDp = 164,
                        surface = ResolvedWidgetSurface.LIGHT,
                        snapshot = inProgress,
                    ),
                    CaptureJob(
                        fileName = "widget_light_friends_full.png",
                        kind = FlameWidgetKind.FRIENDS_BOARD,
                        widthDp = 340,
                        heightDp = 164,
                        surface = ResolvedWidgetSurface.LIGHT,
                        snapshot = inProgress,
                        hideFriendNames = false,
                    ),
                    CaptureJob(
                        fileName = "widget_light_mini_strip.png",
                        kind = FlameWidgetKind.MINI_STRIP,
                        widthDp = 340,
                        heightDp = 76,
                        surface = ResolvedWidgetSurface.LIGHT,
                        snapshot = inProgress,
                    ),
                )

                val outDir = File(getExternalFilesDir(null) ?: filesDir, "widget-shots").apply {
                    mkdirs()
                }
                val provider = FlameStatusAppWidgetProvider()
                val density = resources.displayMetrics.density
                val remoteHostContext = applicationContext

                for (job in jobs) {
                    val theme = provider.resolveVisualTheme(job.surface)
                    val remoteViews: RemoteViews = provider.buildRemoteViews(
                        context = remoteHostContext,
                        targetKind = job.kind,
                        snapshot = job.snapshot,
                        theme = theme,
                        density = density,
                        hideFriendNames = job.hideFriendNames,
                        refreshState = job.refreshState,
                    )

                    val widthPx = (job.widthDp * density).roundToInt()
                    val heightPx = (job.heightDp * density).roundToInt()

                    stage.removeAllViews()
                    val holder = FrameLayout(remoteHostContext).apply {
                        setBackgroundColor(Color.TRANSPARENT)
                        layoutParams = FrameLayout.LayoutParams(widthPx, heightPx)
                    }
                    val inflatedView = remoteViews.apply(remoteHostContext, holder)
                    holder.addView(
                        inflatedView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    stage.addView(holder)

                    val wSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
                    val hSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
                    holder.measure(wSpec, hSpec)
                    holder.layout(0, 0, widthPx, heightPx)

                    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    holder.draw(canvas)

                    val outFile = File(outDir, job.fileName)
                    withContext(Dispatchers.IO) {
                        FileOutputStream(outFile).use { fos ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                        }
                    }
                    bitmap.recycle()
                    Log.i(TAG, "CAPTURED ${outFile.absolutePath} (${widthPx}x${heightPx})")
                }

                Log.i(TAG, "DEMO_WIDGET_CAPTURE_DONE dir=${outDir.absolutePath} count=${jobs.size}")
            } catch (err: Throwable) {
                Log.e(TAG, "DEMO_WIDGET_CAPTURE_FAILED: ${err.message}", err)
            }
        }
    }

    override fun onDestroy() {
        captureScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WidgetCaptureActivity"
    }
}
