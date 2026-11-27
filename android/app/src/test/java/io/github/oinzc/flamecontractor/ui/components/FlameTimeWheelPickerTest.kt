package io.github.oinzc.flamecontractor.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlameTimeWheelPickerTest {

    @Test
    fun `parses and formats clock times cleanly`() {
        assertEquals(FlameClockTime(20, 30), FlameClockTime.parseOrNull("20:30"))
        assertEquals(FlameClockTime(8, 5), FlameClockTime.parseOrNull(" 08:05 "))
        assertNull(FlameClockTime.parseOrNull("20：30"))
        assertNull(FlameClockTime.parseOrNull("25:00"))
        assertEquals(FlameClockTime(21, 0), FlameClockTime.parseOrDefault("bad", FlameClockTime(21, 0)))
        assertEquals("07:09", FlameClockTime(7, 9).toHhmm())
    }

    @Test
    fun `clampClockTime enforces min and max bounds`() {
        assertEquals(
            FlameClockTime(8, 0),
            clampClockTime(
                time = FlameClockTime(6, 30),
                minTime = FlameClockTime(8, 0),
                maxTime = FlameClockTime(23, 55),
            ),
        )
        assertEquals(
            FlameClockTime(23, 55),
            clampClockTime(
                time = FlameClockTime(23, 59),
                minTime = FlameClockTime(0, 0),
                maxTime = FlameClockTime(23, 55),
            ),
        )
    }

    @Test
    fun `normalizeWindowPair prevents inverted or out-of-range same-day windows`() {
        val (s1, e1) = normalizeWindowPairAfterStartChange(
            newStart = FlameClockTime(22, 30),
            currentEnd = FlameClockTime(21, 0),
        )
        assertEquals("22:30", s1.toHhmm())
        assertEquals("23:00", e1.toHhmm())

        val (s2, e2) = normalizeWindowPairAfterStartChange(
            newStart = FlameClockTime(23, 59),
            currentEnd = FlameClockTime(23, 0),
        )
        assertEquals("23:50", s2.toHhmm())
        assertEquals("23:55", e2.toHhmm())

        val (s3, e3) = normalizeWindowPairAfterEndChange(
            currentStart = FlameClockTime(10, 0),
            newEnd = FlameClockTime(9, 15),
        )
        assertEquals("08:45", s3.toHhmm())
        assertEquals("09:15", e3.toHhmm())
    }

    @Test
    fun `plain language explanations reflect enabled, mode, and rhythm`() {
        val disabled = buildSchedulePlainExplanation(
            enabled = false,
            mode = ScheduleTriggerMode.WINDOW,
            windowStart = "20:00",
            windowEnd = "22:00",
            fixedTime = "20:30",
            skipOfficialLitToday = true,
        )
        assertTrue(disabled.contains("总开关已关闭"))

        val windowText = buildSchedulePlainExplanation(
            enabled = true,
            mode = ScheduleTriggerMode.WINDOW,
            windowStart = "19:00",
            windowEnd = "22:30",
            fixedTime = "20:30",
            skipOfficialLitToday = true,
        )
        assertTrue(windowText.contains("19:00 到 22:30"))
        assertTrue(windowText.contains("已亮"))

        val fixedText = buildSchedulePlainExplanation(
            enabled = true,
            mode = ScheduleTriggerMode.FIXED_TIME,
            windowStart = "19:00",
            windowEnd = "22:30",
            fixedTime = "20:30",
            skipOfficialLitToday = false,
        )
        assertTrue(fixedText.contains("20:30 准时"))

        assertTrue(buildRhythmSummaryText(15, 3, 8).contains("默认安全节奏"))
        assertTrue(buildRhythmSummaryText(0, 5, 10).contains("准点不顺延"))
        assertTrue(buildRhythmSummaryText(0, 5, 10).contains("5–10 秒"))
    }
}
