package io.github.oinzc.flamecontractor.domain

import android.content.Context
import androidx.room.withTransaction
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import java.time.Instant
import java.time.ZoneId

object LocalStreakCounter {

    private const val MAINTENANCE_PREFS_NAME = "flame_db_maintenance"
    internal const val KEY_SAME_DAY_STREAK_RECONCILED_V1 = "streak_same_day_reconciled_v1"

    /**
     * 仅当此前从未确认过发送，或上次确认日期（按账号时区 [zoneId]）与本次确认日期不在同一自然日时，
     * 才将本机 `streak_days` 累加 1；同一自然日内的重复发送（如多次手动单发测试）不得重复累加。
     */
    fun shouldIncrementStreakForConfirmation(
        previousConfirmedAtEpochMs: Long?,
        newConfirmedAtEpochMs: Long,
        zoneId: ZoneId,
    ): Boolean {
        if (previousConfirmedAtEpochMs == null) return true
        val previousDate = Instant.ofEpochMilli(previousConfirmedAtEpochMs)
            .atZone(zoneId)
            .toLocalDate()
        val newDate = Instant.ofEpochMilli(newConfirmedAtEpochMs)
            .atZone(zoneId)
            .toLocalDate()
        return previousDate != newDate
    }

    /**
     * 修复因旧版同日多次发送导致虚高的 `streak_days`：
     * - 若历史成功任务总数高于不重复业务日期数，扣除同日重复成功次数 `(total - distinct)`；
     * - 下限取 `distinctSucceededBusinessDates`，同时保留备份迁移带来的历史基线（如 `24 -> 21`）。
     */
    fun reconcileInflatedStreakDays(
        currentStreakDays: Int,
        totalSucceededJobs: Int,
        distinctSucceededBusinessDates: Int,
    ): Int {
        val safeTotal = totalSucceededJobs.coerceAtLeast(0)
        val safeDistinct = distinctSucceededBusinessDates.coerceAtLeast(0)
        return if (safeTotal > safeDistinct) {
            val duplicateCount = safeTotal - safeDistinct
            maxOf(safeDistinct, currentStreakDays - duplicateCount).coerceAtLeast(0)
        } else {
            currentStreakDays.coerceAtLeast(0)
        }
    }

    suspend fun reconcileInflatedTargetsInDatabase(
        database: FlameDatabase,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Int = database.withTransaction {
        val countsByTarget = database.sendJobDao()
            .listSucceededJobCountsByTarget()
            .associateBy { it.targetId }
        if (countsByTarget.isEmpty()) return@withTransaction 0

        var healedCount = 0
        val accountIds = (database.accountDao().listAll().map { it.id } + DEFAULT_ACCOUNT_ID).distinct()
        val targetDao = database.targetDao()
        for (accountId in accountIds) {
            val targets = targetDao.listAllTargetsIncludingDeleted(accountId)
            for (target in targets) {
                val stats = countsByTarget[target.id] ?: continue
                val nextStreak = reconcileInflatedStreakDays(
                    currentStreakDays = target.streakDays,
                    totalSucceededJobs = stats.totalSucceeded,
                    distinctSucceededBusinessDates = stats.distinctBusinessDates,
                )
                if (nextStreak != target.streakDays) {
                    targetDao.updateStreakDays(
                        id = target.id,
                        streakDays = nextStreak,
                        updatedAt = nowEpochMs,
                    )
                    healedCount += 1
                }
            }
        }
        healedCount
    }

    suspend fun runStartupSameDayStreakReconciliationOnce(
        context: Context,
        database: FlameDatabase,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Int {
        val prefs = (context.applicationContext ?: context)
            .getSharedPreferences(MAINTENANCE_PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SAME_DAY_STREAK_RECONCILED_V1, false)) {
            return 0
        }
        return try {
            val healedCount = reconcileInflatedTargetsInDatabase(
                database = database,
                nowEpochMs = nowEpochMs,
            )
            prefs.edit().putBoolean(KEY_SAME_DAY_STREAK_RECONCILED_V1, true).apply()
            healedCount
        } catch (_: Throwable) {
            0
        }
    }
}
