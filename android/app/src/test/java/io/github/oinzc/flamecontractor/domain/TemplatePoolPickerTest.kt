package io.github.oinzc.flamecontractor.domain

import io.github.oinzc.flamecontractor.data.db.TemplatePoolItemEntity
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TemplatePoolPickerTest {

    private fun poolItem(
        id: String,
        content: String = "文案-$id",
        weekdayMask: Int = 0,
        enabled: Boolean = true,
        lastUsedAt: Long? = null,
    ): TemplatePoolItemEntity = TemplatePoolItemEntity(
        id = id,
        templateId = "tpl-pool-1",
        content = content,
        weekdayMask = weekdayMask,
        enabled = enabled,
        lastUsedAt = lastUsedAt,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Test
    fun filtersByWeekdayBitmaskAndOrdersNeverUsedThenLru() {
        val mondayMask = 1 shl 0
        val tuesdayMask = 1 shl 1
        val sundayMask = 1 shl 6

        val items = listOf(
            poolItem(id = "item-disabled", weekdayMask = 0, enabled = false, lastUsedAt = null),
            poolItem(id = "item-tue-only", weekdayMask = tuesdayMask, lastUsedAt = null),
            poolItem(id = "item-used-newer", weekdayMask = 0, lastUsedAt = 2_000L),
            poolItem(id = "item-used-older", weekdayMask = mondayMask, lastUsedAt = 1_000L),
            poolItem(id = "item-never-b", weekdayMask = 0, lastUsedAt = null),
            poolItem(id = "item-never-a", weekdayMask = mondayMask, lastUsedAt = null),
            poolItem(id = "item-sun-only", weekdayMask = sundayMask, lastUsedAt = null),
        )

        // 周一 (0): 从未用过优先，其次按 id 升序 -> item-never-a
        val mondayPick = TemplatePoolPicker.pick(items, weekdayIndex = 0)
        assertNotNull(mondayPick)
        assertEquals("item-never-a", mondayPick?.id)

        // 若所有从未用过项均已使用，按 lastUsedAt 最早优先 -> item-used-older
        val allUsedItems = listOf(
            poolItem(id = "item-b", weekdayMask = 0, lastUsedAt = 5_000L),
            poolItem(id = "item-a", weekdayMask = mondayMask, lastUsedAt = 3_000L),
            poolItem(id = "item-c", weekdayMask = 0, lastUsedAt = 3_000L),
        )
        val lruPick = TemplatePoolPicker.pick(allUsedItems, weekdayIndex = 0)
        assertEquals("item-a", lruPick?.id)

        // 周日 (6): 仅 item-sun-only、item-never-b、item-used-newer 命中，item-never-b 排在 item-sun-only 前
        val sundayPick = TemplatePoolPicker.pick(items, weekdayIndex = TemplatePoolPicker.weekdayIndexOf(DayOfWeek.SUNDAY))
        assertEquals("item-never-b", sundayPick?.id)
    }

    @Test
    fun previewDoesNotConsumeLastUsedAt() {
        val items = listOf(
            poolItem(id = "item-1", content = "早安续火", lastUsedAt = null),
            poolItem(id = "item-2", content = "晚安续火", lastUsedAt = null),
        )

        val preview1 = TemplatePoolPicker.pick(items, weekdayIndex = 2)
        val preview2 = TemplatePoolPicker.pick(items, weekdayIndex = 2)

        assertEquals("item-1", preview1?.id)
        assertEquals("item-1", preview2?.id)
        assertNull(items[0].lastUsedAt)
        assertNull(items[1].lastUsedAt)

        // 模拟实际发送时通过 DAO markPoolItemUsed 更新后，再选才会轮换到 item-2
        val afterConsume = listOf(
            items[0].copy(lastUsedAt = 10_000L, updatedAt = 10_000L),
            items[1],
        )
        val nextPick = TemplatePoolPicker.pick(afterConsume, weekdayIndex = 2)
        assertEquals("item-2", nextPick?.id)
    }

    @Test
    fun returnsNullWhenNoEnabledItemMatchesWeekday() {
        val items = listOf(
            poolItem(id = "item-disabled", weekdayMask = 0, enabled = false),
            poolItem(id = "item-wed-only", weekdayMask = 1 shl 2, enabled = true),
        )

        assertNull(TemplatePoolPicker.pick(items, weekdayIndex = 0))
        assertNull(TemplatePoolPicker.pick(emptyList(), weekdayIndex = 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeWeekdayIndex() {
        TemplatePoolPicker.pick(emptyList(), weekdayIndex = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWeekdayIndexAboveSix() {
        TemplatePoolPicker.pick(emptyList(), weekdayIndex = 7)
    }

    @Test
    fun matchesCombinedBitmasksForWorkdaysAndFullWeek() {
        val workdaysMask = 0b0011111 // 周一..周五 (bit0..bit4)
        val allBitsMask = TemplatePoolPicker.WEEKDAY_MASK_MAX // 127

        val items = listOf(
            poolItem(id = "item-workday", weekdayMask = workdaysMask),
        )

        for (day in 0..4) {
            assertEquals("item-workday", TemplatePoolPicker.pick(items, weekdayIndex = day)?.id)
        }
        for (day in 5..6) {
            assertNull(TemplatePoolPicker.pick(items, weekdayIndex = day))
            assertEquals(true, TemplatePoolPicker.isWeekdayMatched(allBitsMask, day))
        }
    }
}
