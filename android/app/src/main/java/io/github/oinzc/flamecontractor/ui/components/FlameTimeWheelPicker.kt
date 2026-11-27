package io.github.oinzc.flamecontractor.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Immutable
data class FlameClockTime(
    val hour: Int,
    val minute: Int,
) {
    init {
        require(hour in 0..23) { "hour must be in 0..23, got $hour" }
        require(minute in 0..59) { "minute must be in 0..59, got $minute" }
    }

    fun toMinutesOfDay(): Int = hour * 60 + minute

    fun toHhmm(): String = String.format(Locale.US, "%02d:%02d", hour, minute)

    companion object {
        private val STRICT_HHMM_REGEX = Regex("^(\\d{2}):(\\d{2})$")

        fun fromMinutesOfDay(minutes: Int): FlameClockTime {
            val clamped = minutes.coerceIn(0, 23 * 60 + 59)
            return FlameClockTime(hour = clamped / 60, minute = clamped % 60)
        }

        fun parseOrNull(raw: String?): FlameClockTime? {
            val clean = raw?.trim().orEmpty()
            val match = STRICT_HHMM_REGEX.matchEntire(clean) ?: return null
            val h = match.groupValues[1].toIntOrNull() ?: return null
            val m = match.groupValues[2].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return FlameClockTime(hour = h, minute = m)
        }

        fun parseOrDefault(raw: String?, fallback: FlameClockTime): FlameClockTime =
            parseOrNull(raw) ?: fallback
    }
}

enum class ScheduleTriggerMode {
    WINDOW,
    FIXED_TIME,
}

private const val MAX_WINDOW_END_MINUTES = 23 * 60 + 55
private const val MAX_WINDOW_START_MINUTES = 23 * 60 + 50
private const val MIN_WINDOW_END_MINUTES = 5
private const val AUTO_ADJUST_GAP_MINUTES = 30

fun clampClockTime(
    time: FlameClockTime,
    minTime: FlameClockTime = FlameClockTime(0, 0),
    maxTime: FlameClockTime = FlameClockTime(23, 59),
): FlameClockTime {
    val low = minOf(minTime.toMinutesOfDay(), maxTime.toMinutesOfDay())
    val high = maxOf(minTime.toMinutesOfDay(), maxTime.toMinutesOfDay())
    return FlameClockTime.fromMinutesOfDay(time.toMinutesOfDay().coerceIn(low, high))
}

fun normalizeWindowPairAfterStartChange(
    newStart: FlameClockTime,
    currentEnd: FlameClockTime,
): Pair<FlameClockTime, FlameClockTime> {
    val startMin = newStart.toMinutesOfDay().coerceIn(0, MAX_WINDOW_START_MINUTES)
    val clampedStart = FlameClockTime.fromMinutesOfDay(startMin)
    val rawEndMin = currentEnd.toMinutesOfDay().coerceIn(MIN_WINDOW_END_MINUTES, MAX_WINDOW_END_MINUTES)
    val adjustedEndMin = if (startMin >= rawEndMin) {
        (startMin + AUTO_ADJUST_GAP_MINUTES).coerceAtMost(MAX_WINDOW_END_MINUTES)
    } else {
        rawEndMin
    }
    return clampedStart to FlameClockTime.fromMinutesOfDay(adjustedEndMin)
}

fun normalizeWindowPairAfterEndChange(
    currentStart: FlameClockTime,
    newEnd: FlameClockTime,
): Pair<FlameClockTime, FlameClockTime> {
    val endMin = newEnd.toMinutesOfDay().coerceIn(MIN_WINDOW_END_MINUTES, MAX_WINDOW_END_MINUTES)
    val clampedEnd = FlameClockTime.fromMinutesOfDay(endMin)
    val rawStartMin = currentStart.toMinutesOfDay().coerceIn(0, MAX_WINDOW_START_MINUTES)
    val adjustedStartMin = if (rawStartMin >= endMin) {
        (endMin - AUTO_ADJUST_GAP_MINUTES).coerceAtLeast(0)
    } else {
        rawStartMin
    }
    return FlameClockTime.fromMinutesOfDay(adjustedStartMin) to clampedEnd
}

fun buildSchedulePlainExplanation(
    enabled: Boolean,
    mode: ScheduleTriggerMode,
    windowStart: String,
    windowEnd: String,
    fixedTime: String,
    skipOfficialLitToday: Boolean,
): String {
    if (!enabled) {
        return "自动续火总开关已关闭，每天不会自动发消息；你仍可随时在首页手动点发送。"
    }
    val skipSuffix = if (skipOfficialLitToday) {
        "到点若检测到对方今天火花已亮，会自动跳过不打扰。"
    } else {
        ""
    }
    return when (mode) {
        ScheduleTriggerMode.WINDOW -> {
            val start = FlameClockTime.parseOrDefault(windowStart, FlameClockTime(20, 0)).toHhmm()
            val end = FlameClockTime.parseOrDefault(windowEnd, FlameClockTime(22, 0)).toHhmm()
            "每天会在 $start 到 $end 之间自动选一个不固定的时刻开始续火，更接近真人作息；如果当时手机断网，会在 $end 前自动补发，过点则当天不再打扰。$skipSuffix"
        }

        ScheduleTriggerMode.FIXED_TIME -> {
            val fixed = FlameClockTime.parseOrDefault(fixedTime, FlameClockTime(20, 30)).toHhmm()
            "每天到 $fixed 准时开始自动续火；如果当时手机关机或没网，会在 60 分钟宽限期内自动补发，超过宽限期当天不再补发。$skipSuffix"
        }
    }
}

fun buildRhythmSummaryText(
    jitterMaxMinutes: Int,
    cooldownMinSec: Int,
    cooldownMaxSec: Int,
): String {
    val safeJitter = jitterMaxMinutes.coerceIn(0, 15)
    val safeMin = cooldownMinSec.coerceAtLeast(1)
    val safeMax = cooldownMaxSec.coerceAtLeast(safeMin)
    if (safeJitter == 15 && safeMin == 3 && safeMax == 8) {
        return "已启用默认安全节奏：出发随机顺延 0–15 分钟 · 好友间隔 3–8 秒"
    }
    val jitterLabel = if (safeJitter == 0) "准点不顺延" else "出发顺延 0–${safeJitter} 分钟"
    return "当前节奏：$jitterLabel · 好友间隔 ${safeMin}–${safeMax} 秒"
}

private const val WHEEL_CYCLES = 200
private const val WHEEL_CENTER_CYCLE = WHEEL_CYCLES / 2
private val WHEEL_ROW_HEIGHT = 44.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlameTimeWheelPickerSheet(
    title: String,
    initialTime: FlameClockTime,
    onConfirm: (FlameClockTime) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    minTime: FlameClockTime = FlameClockTime(0, 0),
    maxTime: FlameClockTime = FlameClockTime(23, 59),
    presetTimes: List<FlameClockTime> = listOf(
        FlameClockTime(8, 0),
        FlameClockTime(12, 0),
        FlameClockTime(20, 0),
        FlameClockTime(21, 30),
        FlameClockTime(22, 0),
    ),
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val safeInitial = remember(initialTime, minTime, maxTime) {
        clampClockTime(initialTime, minTime, maxTime)
    }
    var selectedHour by remember(safeInitial) { mutableIntStateOf(safeInitial.hour) }
    var selectedMinute by remember(safeInitial) { mutableIntStateOf(safeInitial.minute) }

    val rawTime = FlameClockTime(hour = selectedHour, minute = selectedMinute)
    val clampedTime = clampClockTime(rawTime, minTime, maxTime)
    val isClampedToBounds = rawTime != clampedTime

    val hourListState = rememberLazyListState(
        initialFirstVisibleItemIndex = (WHEEL_CENTER_CYCLE * 24 + safeInitial.hour) - 2,
    )
    val minuteListState = rememberLazyListState(
        initialFirstVisibleItemIndex = (WHEEL_CENTER_CYCLE * 60 + safeInitial.minute) - 2,
    )

    val filteredPresets = remember(presetTimes, minTime, maxTime) {
        val low = minOf(minTime.toMinutesOfDay(), maxTime.toMinutesOfDay())
        val high = maxOf(minTime.toMinutesOfDay(), maxTime.toMinutesOfDay())
        presetTimes.filter { it.toMinutesOfDay() in low..high }.distinct()
    }

    val scrollToTime: (FlameClockTime) -> Unit = { target ->
        val safeTarget = clampClockTime(target, minTime, maxTime)
        haptics.performFlameLightTick()
        coroutineScope.launch {
            launch {
                val baseH = (hourListState.firstVisibleItemIndex + 2).let { cur ->
                    val cycleStart = (cur / 24) * 24
                    cycleStart + safeTarget.hour
                }
                hourListState.animateScrollToItem((baseH - 2).coerceAtLeast(0))
            }
            launch {
                val baseM = (minuteListState.firstVisibleItemIndex + 2).let { cur ->
                    val cycleStart = (cur / 60) * 60
                    cycleStart + safeTarget.minute
                }
                minuteListState.animateScrollToItem((baseM - 2).coerceAtLeast(0))
            }
        }
    }

    // 滚动停稳后若越界，自动轻弹回合规范围
    LaunchedEffect(hourListState, minuteListState, minTime, maxTime) {
        snapshotFlow {
            hourListState.isScrollInProgress || minuteListState.isScrollInProgress
        }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) {
                    val cur = FlameClockTime(selectedHour, selectedMinute)
                    val clamped = clampClockTime(cur, minTime, maxTime)
                    if (cur != clamped) {
                        val targetHourIdx = ((hourListState.firstVisibleItemIndex + 2) / 24) * 24 + clamped.hour
                        val targetMinIdx = ((minuteListState.firstVisibleItemIndex + 2) / 60) * 60 + clamped.minute
                        coroutineScope.launch {
                            hourListState.animateScrollToItem((targetHourIdx - 2).coerceAtLeast(0))
                        }
                        coroutineScope.launch {
                            minuteListState.animateScrollToItem((targetMinIdx - 2).coerceAtLeast(0))
                        }
                    }
                }
            }
    }

    FlameModalSheet(
        onDismissRequest = onDismiss,
        title = title,
        subtitle = subtitle,
        maxDialogWidth = 420.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部大号等宽实时时间预览
            Surface(
                color = palette.surfaceSubtle,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, palette.hairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = clampedTime.toHhmm(),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = 38.sp,
                            lineHeight = 42.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = palette.ink,
                    )
                    Text(
                        text = if (isClampedToBounds) {
                            "已自动限制在可用范围 ${minTime.toHhmm()} – ${maxTime.toHhmm()} 内"
                        } else {
                            "24 小时制 · 上下滑动或点选快捷时刻"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isClampedToBounds) semantics.warning else palette.inkTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (filteredPresets.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    filteredPresets.forEach { preset ->
                        val isActive = clampedTime == preset
                        Surface(
                            onClick = { scrollToTime(preset) },
                            shape = RoundedCornerShape(999.dp),
                            color = if (isActive) palette.monoCtaContainer else palette.surfaceSubtle,
                            contentColor = if (isActive) palette.monoCtaContent else palette.inkSecondary,
                            border = BorderStroke(
                                1.dp,
                                if (isActive) palette.monoCtaContainer else palette.hairline,
                            ),
                        ) {
                            Text(
                                text = preset.toHhmm(),
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            // 闹钟双滚轮主区
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WHEEL_ROW_HEIGHT * 5),
                contentAlignment = Alignment.Center,
            ) {
                // 中央选中指示带
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WHEEL_ROW_HEIGHT),
                    shape = RoundedCornerShape(14.dp),
                    color = palette.surfaceSubtle,
                    border = BorderStroke(1.dp, palette.hairlineStrong),
                ) {}

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    ClockWheelColumn(
                        unitCount = 24,
                        unitLabel = "时",
                        listState = hourListState,
                        onCenterValueChanged = { nextHour ->
                            if (selectedHour != nextHour) {
                                selectedHour = nextHour
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )

                    Text(
                        text = ":",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = palette.ink,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )

                    ClockWheelColumn(
                        unitCount = 60,
                        unitLabel = "分",
                        listState = minuteListState,
                        onCenterValueChanged = { nextMinute ->
                            if (selectedMinute != nextMinute) {
                                selectedMinute = nextMinute
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            HorizontalDivider(thickness = 1.dp, color = palette.hairline)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FlameSheetActionRow(
                    text = "取消",
                    primary = false,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                FlameSheetActionRow(
                    text = "确定 ${clampedTime.toHhmm()}",
                    primary = true,
                    onClick = { onConfirm(clampedTime) },
                    modifier = Modifier.weight(1.35f),
                )
            }
        }
    }
}

@Composable
private fun ClockWheelColumn(
    unitCount: Int,
    unitLabel: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onCenterValueChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FlameThemeTokens.palette
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rowHeightPx = with(density) { WHEEL_ROW_HEIGHT.toPx() }
    val totalCount = unitCount * WHEEL_CYCLES
    val snapFling = rememberSnapFlingBehavior(lazyListState = listState)

    val centerItemIndex by remember(listState, rowHeightPx) {
        derivedStateOf {
            val offsetRatio = if (rowHeightPx > 0f) {
                listState.firstVisibleItemScrollOffset / rowHeightPx
            } else {
                0f
            }
            val addend = if (offsetRatio >= 0.5f) 3 else 2
            (listState.firstVisibleItemIndex + addend).coerceIn(0, totalCount - 1)
        }
    }

    LaunchedEffect(listState, unitCount) {
        var isFirstEmission = true
        snapshotFlow { centerItemIndex % unitCount }
            .distinctUntilChanged()
            .collect { value ->
                onCenterValueChanged(value)
                if (isFirstEmission) {
                    isFirstEmission = false
                } else if (listState.isScrollInProgress) {
                    haptics.performFlameLightTick()
                }
            }
    }

    LazyColumn(
        state = listState,
        flingBehavior = snapFling,
        modifier = modifier
            .height(WHEEL_ROW_HEIGHT * 5)
            .semantics {
                contentDescription = "${unitLabel}滚轮选择器"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(count = totalCount, key = { it }) { index ->
            val value = index % unitCount
            val dist = abs(index - centerItemIndex)
            val isCenter = dist == 0
            val itemAlpha = when (dist) {
                0 -> 1f
                1 -> 0.52f
                else -> 0.24f
            }
            val interaction = remember { MutableInteractionSource() }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WHEEL_ROW_HEIGHT)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = {
                            if (!isCenter) {
                                haptics.performFlameLightTick()
                                coroutineScope.launch {
                                    listState.animateScrollToItem((index - 2).coerceAtLeast(0))
                                }
                            }
                        },
                    )
                    .alpha(itemAlpha),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = String.format(Locale.US, "%02d", value),
                        style = (if (isCenter) {
                            MaterialTheme.typography.headlineSmall
                        } else {
                            MaterialTheme.typography.titleMedium
                        }).copy(
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Medium,
                            fontFeatureSettings = "tnum",
                        ),
                        color = if (isCenter) palette.ink else palette.inkSecondary,
                    )
                    if (isCenter) {
                        Text(
                            text = unitLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.inkTertiary,
                        )
                    }
                }
            }
        }
    }
}
