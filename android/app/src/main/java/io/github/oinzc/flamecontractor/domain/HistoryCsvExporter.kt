package io.github.oinzc.flamecontractor.domain

import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import java.time.Instant
import java.time.LocalDate

/**
 * 纯 JVM 脱敏历史任务 CSV 导出器（零 android.* 依赖，对齐后端 `/api/stats/export.csv`）。
 * 严禁输出好友昵称、抖音号、UID 或聊天摘要正文。
 */
object HistoryCsvExporter {

    const val HEADER =
        "business_date,account_id,target_id,status,failure_class,attempt_count,scheduled_at,confirmed_at,message_slot_id"

    fun defaultFileName(date: LocalDate = LocalDate.now()): String = "flame-jobs-$date.csv"

    fun encode(jobs: List<SendJobEntity>): String {
        val sortedJobs = jobs.sortedWith(
            compareBy<SendJobEntity> { it.businessDate }
                .thenBy { it.createdAt }
                .thenBy { it.id },
        )

        val rows = ArrayList<String>(sortedJobs.size + 1)
        rows += HEADER
        for (job in sortedJobs) {
            val columns = listOf(
                job.businessDate,
                job.accountId,
                job.targetId,
                job.status.wireValue,
                job.failureClass?.wireValue ?: "",
                job.attemptCount.toString(),
                job.scheduledAt?.let { Instant.ofEpochMilli(it).toString() } ?: "",
                job.confirmedAt?.let { Instant.ofEpochMilli(it).toString() } ?: "",
                job.messageSlotId,
            )
            rows += columns.joinToString(separator = ",") { escapeRfc4180(it) }
        }
        return rows.joinToString(separator = "\n")
    }

    private fun escapeRfc4180(value: String): String {
        val needsQuoting = value.any { ch ->
            ch == ',' || ch == '"' || ch == '\n' || ch == '\r'
        }
        if (!needsQuoting) return value
        return buildString(value.length + 4) {
            append('"')
            for (ch in value) {
                if (ch == '"') {
                    append("\"\"")
                } else {
                    append(ch)
                }
            }
            append('"')
        }
    }
}
