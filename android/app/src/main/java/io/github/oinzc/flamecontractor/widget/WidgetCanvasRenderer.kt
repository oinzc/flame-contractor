package io.github.oinzc.flamecontractor.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import kotlin.math.roundToInt

/**
 * 原生零依赖高清抗锯齿小组件图形渲染器（进度圆环 / 橙绿渐变进度条 / 近 7 日打卡条）。
 */
object WidgetCanvasRenderer {

    fun renderProgressRingBitmap(
        density: Float,
        sizeDp: Float,
        strokeDp: Float,
        progressFraction: Float,
        isAllDone: Boolean,
        isAlert: Boolean,
        isDarkSurface: Boolean,
    ): Bitmap {
        val d = density.coerceAtLeast(1f)
        val sizePx = (sizeDp * d).roundToInt().coerceAtLeast(48)
        val strokePx = (strokeDp * d).coerceAtLeast(4f)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val pad = strokePx / 2f + 1.5f * d
        val rect = RectF(pad, pad, sizePx - pad, sizePx - pad)
        val cx = sizePx / 2f
        val cy = sizePx / 2f

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            color = when {
                isAlert && isDarkSurface -> Color.parseColor("#FF2E1618")
                isAlert -> Color.parseColor("#FFFEE2E2")
                isDarkSurface -> Color.parseColor("#24FFFFFF")
                else -> Color.parseColor("#FFECEFF4")
            }
        }
        canvas.drawOval(rect, trackPaint)

        val clamped = progressFraction.coerceIn(0f, 1f)
        val sweep = when {
            isAlert -> 125f
            isAllDone -> 360f
            clamped <= 0f -> 0f
            else -> (clamped * 360f).coerceAtLeast(18f)
        }

        if (sweep > 0f) {
            val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokePx
                strokeCap = Paint.Cap.ROUND
            }
            when {
                isAlert -> {
                    arcPaint.color = if (isDarkSurface) {
                        Color.parseColor("#FFF87171")
                    } else {
                        Color.parseColor("#FFDC2626")
                    }
                }

                isAllDone -> {
                    arcPaint.color = if (isDarkSurface) {
                        Color.parseColor("#FF34D399")
                    } else {
                        Color.parseColor("#FF059669")
                    }
                }

                else -> {
                    val startColor = if (isDarkSurface) Color.parseColor("#FFF97316") else Color.parseColor("#FFEA580C")
                    val midColor = if (isDarkSurface) Color.parseColor("#FFFB923C") else Color.parseColor("#FFF97316")
                    val endColor = if (isDarkSurface) Color.parseColor("#FF34D399") else Color.parseColor("#FF059669")
                    val sweepShader = SweepGradient(
                        cx,
                        cy,
                        intArrayOf(startColor, midColor, endColor, startColor),
                        floatArrayOf(0f, 0.48f, 0.92f, 1f),
                    )
                    val matrix = Matrix().apply { postRotate(-90f, cx, cy) }
                    sweepShader.setLocalMatrix(matrix)
                    arcPaint.shader = sweepShader
                }
            }
            canvas.drawArc(rect, -90f, sweep, false, arcPaint)
        }

        return bitmap
    }

    fun renderProgressBarBitmap(
        density: Float,
        widthDp: Float,
        heightDp: Float,
        progressFraction: Float,
        isAllDone: Boolean,
        isAlert: Boolean,
        isDarkSurface: Boolean,
    ): Bitmap {
        val d = density.coerceAtLeast(1f)
        val w = (widthDp * d).roundToInt().coerceAtLeast(64)
        val h = (heightDp * d).roundToInt().coerceAtLeast(8)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = h / 2f

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (isDarkSurface) Color.parseColor("#24FFFFFF") else Color.parseColor("#FFE8EBF0")
        }
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, trackPaint)

        val clamped = progressFraction.coerceIn(0f, 1f)
        val fillFraction = when {
            isAlert -> 0.35f
            isAllDone -> 1f
            clamped <= 0f -> 0f
            else -> clamped.coerceAtLeast(0.06f)
        }
        if (fillFraction > 0f) {
            val fillW = (w * fillFraction).coerceAtLeast(h.toFloat())
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            when {
                isAlert -> fillPaint.color = if (isDarkSurface) Color.parseColor("#FFF87171") else Color.parseColor("#FFDC2626")
                isAllDone -> fillPaint.color = if (isDarkSurface) Color.parseColor("#FF34D399") else Color.parseColor("#FF059669")
                else -> {
                    val cStart = if (isDarkSurface) Color.parseColor("#FFF97316") else Color.parseColor("#FFEA580C")
                    val cEnd = if (isDarkSurface) Color.parseColor("#FF34D399") else Color.parseColor("#FF059669")
                    fillPaint.shader = LinearGradient(0f, 0f, fillW, 0f, cStart, cEnd, Shader.TileMode.CLAMP)
                }
            }
            canvas.drawRoundRect(RectF(0f, 0f, fillW, h.toFloat()), radius, radius, fillPaint)
        }

        return bitmap
    }

    fun render7DayStripBitmap(
        density: Float,
        days: List<WidgetDayStatus>,
        isDarkSurface: Boolean,
    ): Bitmap {
        val d = density.coerceAtLeast(1f)
        val cellSize = 18f * d
        val gap = 4.5f * d
        val count = days.size.coerceIn(1, 7)
        val widthPx = (count * cellSize + (count - 1) * gap).roundToInt().coerceAtLeast(32)
        val heightPx = (20f * d).roundToInt().coerceAtLeast(20)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val topY = (heightPx - cellSize) / 2f
        val corner = 5.5f * d
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * d
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 9.5f * d
            typeface = Typeface.DEFAULT_BOLD
        }
        val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        days.take(7).forEachIndexed { idx, status ->
            val left = idx * (cellSize + gap)
            val rect = RectF(left + 0.5f * d, topY + 0.5f * d, left + cellSize - 0.5f * d, topY + cellSize - 0.5f * d)
            when (status) {
                WidgetDayStatus.DONE, WidgetDayStatus.TODAY_DONE -> {
                    fillPaint.color = if (isDarkSurface) Color.parseColor("#2E10B981") else Color.parseColor("#FFECFDF5")
                    strokePaint.color = if (isDarkSurface) Color.parseColor("#6634D399") else Color.parseColor("#FFA7F3D0")
                    checkPaint.color = if (isDarkSurface) Color.parseColor("#FF34D399") else Color.parseColor("#FF059669")
                    canvas.drawRoundRect(rect, corner, corner, fillPaint)
                    canvas.drawRoundRect(rect, corner, corner, strokePaint)
                    val cx = rect.centerX()
                    val cy = rect.centerY()
                    val r = cellSize * 0.23f
                    canvas.drawLine(cx - r, cy + r * 0.05f, cx - r * 0.2f, cy + r * 0.85f, checkPaint)
                    canvas.drawLine(cx - r * 0.2f, cy + r * 0.85f, cx + r * 1.05f, cy - r * 0.75f, checkPaint)
                }

                WidgetDayStatus.TODAY_IN_PROGRESS -> {
                    fillPaint.color = if (isDarkSurface) Color.parseColor("#33F97316") else Color.parseColor("#FFFFF4EC")
                    strokePaint.color = if (isDarkSurface) Color.parseColor("#FFFB923C") else Color.parseColor("#FFEA580C")
                    textPaint.color = if (isDarkSurface) Color.parseColor("#FFFB923C") else Color.parseColor("#FFEA580C")
                    canvas.drawRoundRect(rect, corner, corner, fillPaint)
                    canvas.drawRoundRect(rect, corner, corner, strokePaint)
                    val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                    canvas.drawText("今", rect.centerX(), baseline, textPaint)
                }

                WidgetDayStatus.ALERT -> {
                    fillPaint.color = if (isDarkSurface) Color.parseColor("#33EF4444") else Color.parseColor("#FFFEF2F2")
                    strokePaint.color = if (isDarkSurface) Color.parseColor("#FFF87171") else Color.parseColor("#FFDC2626")
                    textPaint.color = if (isDarkSurface) Color.parseColor("#FFF87171") else Color.parseColor("#FFDC2626")
                    canvas.drawRoundRect(rect, corner, corner, fillPaint)
                    canvas.drawRoundRect(rect, corner, corner, strokePaint)
                    val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                    canvas.drawText("!", rect.centerX(), baseline, textPaint)
                }

                WidgetDayStatus.EMPTY -> {
                    fillPaint.color = if (isDarkSurface) Color.parseColor("#14FFFFFF") else Color.parseColor("#FFF1F3F6")
                    strokePaint.color = if (isDarkSurface) Color.parseColor("#1FFFFFFF") else Color.parseColor("#FFE2E6EE")
                    canvas.drawRoundRect(rect, corner, corner, fillPaint)
                    canvas.drawRoundRect(rect, corner, corner, strokePaint)
                    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (isDarkSurface) Color.parseColor("#FF525B6E") else Color.parseColor("#FFB4BCCB")
                    }
                    canvas.drawCircle(rect.centerX(), rect.centerY(), 1.6f * d, dotPaint)
                }
            }
        }

        return bitmap
    }
}
