package io.github.oinzc.flamecontractor.domain

import io.github.oinzc.flamecontractor.data.db.TemplatePoolItemEntity
import java.time.DayOfWeek

/**
 * 文案池轮换器：星期位掩码过滤 + LRU 确定性选句。
 *
 * 与桌面端 `backend/app/execution/pool.py` 保持一致：
 * - `weekdayIndex`: 0=周一 .. 6=周日
 * - `weekdayMask == 0` 表示每天不限，否则校验 `(weekdayMask shr weekdayIndex) and 1 == 1`
 * - 排序键 `(lastUsedAt != null, lastUsedAt ?: Long.MIN_VALUE, id)`
 * - 纯只读选取，绝不修改 `lastUsedAt`；仅在实际发送触发时由调用方经 `TemplateDao.markPoolItemUsed` 记账
 */
object TemplatePoolPicker {

    const val WEEKDAY_MASK_ALL = 0
    const val WEEKDAY_MASK_MAX = 127

    fun pick(
        items: List<TemplatePoolItemEntity>,
        weekdayIndex: Int,
    ): TemplatePoolItemEntity? {
        require(weekdayIndex in 0..6) { "weekdayIndex must be in 0..6, got $weekdayIndex" }
        val candidates = items.filter { item ->
            item.enabled && isWeekdayMatched(item.weekdayMask, weekdayIndex)
        }
        if (candidates.isEmpty()) return null
        return candidates.minWith(
            compareBy<TemplatePoolItemEntity>(
                { it.lastUsedAt != null },
                { it.lastUsedAt ?: Long.MIN_VALUE },
                { it.id },
            ),
        )
    }

    fun isWeekdayMatched(weekdayMask: Int, weekdayIndex: Int): Boolean {
        require(weekdayIndex in 0..6) { "weekdayIndex must be in 0..6, got $weekdayIndex" }
        if (weekdayMask == WEEKDAY_MASK_ALL) return true
        return ((weekdayMask shr weekdayIndex) and 1) == 1
    }

    fun weekdayIndexOf(dayOfWeek: DayOfWeek): Int = dayOfWeek.value - 1
}
