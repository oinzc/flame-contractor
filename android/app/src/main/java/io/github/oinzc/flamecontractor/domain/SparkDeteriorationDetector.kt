package io.github.oinzc.flamecontractor.domain

import io.github.oinzc.flamecontractor.core.identity.IdentityDisambiguator
import io.github.oinzc.flamecontractor.core.identity.MatchStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.execution.ScanReconciliationBuilder
import io.github.oinzc.flamecontractor.execution.ScannedChatCandidate

data class TargetSparkSnapshotUpdate(
    val targetId: String,
    val sparkDays: Int?,
    val sparkKind: String?,
    val sparkLabel: String?,
    val scannedAtEpochMs: Long,
)

data class SparkDeteriorationAlert(
    val targetId: String,
    val displayName: String,
    val summary: String,
)

data class SparkScanEvaluation(
    val updates: List<TargetSparkSnapshotUpdate>,
    val alerts: List<SparkDeteriorationAlert>,
)

/**
 * 纯 JVM 官方火花恶化检测器（零 android.* 依赖），1:1 对齐后端 `scan_reconciliation.py`：
 * - 仅匹配活跃目标（deletedAt == null），跳过 MatchStatus.AMBIGUOUS；
 * - 匹配优先级：platformUserId -> platformConversationId -> 规范化 nicknameSnapshot（含身份冲突保护）；
 * - 观察无火花时落库 `"无火花"` 以避免下周期重复告警；未观察到的目标完全不触碰；
 * - 已停用目标（enabled == false）仍刷新快照，但绝不产生告警。
 */
object SparkDeteriorationDetector {

    val DISAPPEAR_DAYS_REGEX = Regex("""(\d+)\s*天后(?:火花(?:将)?)?消失""")
    val REKINDLE_PROGRESS_REGEX = Regex("""重燃中\s*\(?(\d+)\s*/\s*(\d+)\)?""")
    const val NO_SPARK_LABEL = "无火花"

    fun evaluate(
        existingTargets: List<ContractTargetEntity>,
        candidates: List<ScannedChatCandidate>,
        scannedAtEpochMs: Long,
    ): SparkScanEvaluation {
        val activeTargets = existingTargets.filter { it.deletedAt == null }
        val processedTargetIds = HashSet<String>()
        val updates = mutableListOf<TargetSparkSnapshotUpdate>()
        val alerts = mutableListOf<SparkDeteriorationAlert>()

        for (candidate in candidates) {
            if (candidate.matchStatus == MatchStatus.AMBIGUOUS) {
                continue
            }
            val target = matchActiveTarget(candidate, activeTargets) ?: continue
            if (!processedTargetIds.add(target.id)) {
                continue
            }

            val hasSpark = ScanReconciliationBuilder.hasOfficialSpark(candidate)
            val currDays: Int?
            val currKind: String?
            val currLabel: String?
            if (hasSpark) {
                currDays = cleanDays(candidate.sparkDays)
                currKind = cleanText(candidate.sparkKind)
                currLabel = cleanText(candidate.sparkLabel)
            } else {
                currDays = null
                currKind = null
                currLabel = NO_SPARK_LABEL
            }

            updates += TargetSparkSnapshotUpdate(
                targetId = target.id,
                sparkDays = currDays,
                sparkKind = currKind,
                sparkLabel = currLabel,
                scannedAtEpochMs = scannedAtEpochMs,
            )

            if (!target.enabled) {
                continue
            }

            val prevDays = cleanDays(target.sparkDays)
            val prevKind = cleanText(target.sparkKind)
            val prevLabel = cleanText(target.sparkLabel)

            val deteriorated = if (!hasSpark) {
                (prevDays != null && prevDays > 0) ||
                    isDangerLabel(prevLabel) ||
                    (prevKind != null && prevLabel != NO_SPARK_LABEL)
            } else if (currDays == null && isDangerLabel(currLabel)) {
                when {
                    prevDays != null && prevDays > 0 -> true
                    prevDays == null && prevLabel == null -> true
                    prevLabel != null && prevLabel != NO_SPARK_LABEL ->
                        isLabelRegression(prevLabel, currLabel!!)
                    else -> false
                }
            } else {
                false
            }

            if (!deteriorated) {
                continue
            }

            val displayName = cleanText(target.nicknameSnapshot)
                ?: cleanText(candidate.displayName)
                ?: "未命名好友"
            val prevDesc = formatSparkState(prevDays, prevLabel)
            val currDesc = formatSparkState(currDays, currLabel) ?: NO_SPARK_LABEL
            val summary = if (prevDesc != null) "$prevDesc → $currDesc" else currDesc
            alerts += SparkDeteriorationAlert(
                targetId = target.id,
                displayName = displayName,
                summary = summary,
            )
        }

        return SparkScanEvaluation(updates = updates, alerts = alerts)
    }

    private fun matchActiveTarget(
        candidate: ScannedChatCandidate,
        activeTargets: List<ContractTargetEntity>,
    ): ContractTargetEntity? {
        val isBound = candidate.matchStatus == MatchStatus.BOUND
        val pUserId = if (isBound) cleanText(candidate.platformUserId) else null
        val pConvId = if (isBound) cleanText(candidate.platformConversationId) else null
        val normName = IdentityDisambiguator.normalizeLabel(candidate.displayName)
        val convType = cleanText(candidate.conversationType)

        if (pUserId != null) {
            val byUserId = activeTargets.firstOrNull { cleanText(it.platformUserId) == pUserId }
            if (byUserId != null) {
                return byUserId.takeUnless { hasIdentityConflict(it, pUserId, pConvId, convType) }
            }
            if (normName.isNotEmpty()) {
                val conflictingSameName = activeTargets.any {
                    IdentityDisambiguator.normalizeLabel(it.nicknameSnapshot) == normName &&
                        cleanText(it.platformUserId) != null
                }
                if (conflictingSameName) {
                    return null
                }
            }
        }

        if (pConvId != null) {
            val byConvId = activeTargets.firstOrNull { cleanText(it.platformConversationId) == pConvId }
            if (byConvId != null) {
                return byConvId.takeUnless { hasIdentityConflict(it, pUserId, pConvId, convType) }
            }
        }

        if (normName.isEmpty()) {
            return null
        }

        val nameMatches = activeTargets.filter { target ->
            if (IdentityDisambiguator.normalizeLabel(target.nicknameSnapshot) != normName) {
                return@filter false
            }
            val targetConvType = cleanText(target.conversationType)
            if (targetConvType != null && convType != null && targetConvType != convType) {
                return@filter false
            }
            if (hasIdentityConflict(target, pUserId, pConvId, convType)) {
                return@filter false
            }
            val targetConvId = cleanText(target.platformConversationId)
            if (targetConvId != null && pConvId == null) {
                return@filter false
            }
            true
        }
        return nameMatches.singleOrNull()
    }

    private fun hasIdentityConflict(
        target: ContractTargetEntity,
        pUserId: String?,
        pConvId: String?,
        convType: String?,
    ): Boolean {
        val targetUserId = cleanText(target.platformUserId)
        val targetConvId = cleanText(target.platformConversationId)
        if (pUserId != null && targetUserId != null && targetUserId != pUserId) return true
        if (pConvId != null && targetConvId != null && targetConvId != pConvId) return true
        if (pUserId != null && targetConvId != null) return true
        if (pConvId != null && targetUserId != null) return true
        if (targetUserId != null && convType == "group") return true
        if (targetConvId != null && convType == "friend") return true
        return false
    }

    private fun isDangerLabel(label: String?): Boolean {
        if (label == null) return false
        return DISAPPEAR_DAYS_REGEX.containsMatchIn(label) ||
            REKINDLE_PROGRESS_REGEX.containsMatchIn(label)
    }

    private fun isLabelRegression(prevLabel: String, currLabel: String): Boolean {
        if (prevLabel == currLabel) return false
        val prevDis = DISAPPEAR_DAYS_REGEX.find(prevLabel)
        val currDis = DISAPPEAR_DAYS_REGEX.find(currLabel)
        if (prevDis != null && currDis != null) {
            val prevN = prevDis.groupValues[1].toIntOrNull() ?: return false
            val currN = currDis.groupValues[1].toIntOrNull() ?: return false
            return currN < prevN
        }

        val prevRek = REKINDLE_PROGRESS_REGEX.find(prevLabel)
        val currRek = REKINDLE_PROGRESS_REGEX.find(currLabel)
        if (prevRek != null && currRek != null) {
            val prevProgress = prevRek.groupValues[1].toIntOrNull() ?: return false
            val currProgress = currRek.groupValues[1].toIntOrNull() ?: return false
            return currProgress < prevProgress
        }

        // 从「N 天后消失」跌入「重燃中」代表正式断火进入重燃，属恶化
        if (prevDis != null && currRek != null) {
            return true
        }
        return false
    }

    private fun formatSparkState(days: Int?, label: String?): String? = when {
        days != null -> "${days}天"
        label != null -> label
        else -> null
    }

    private fun cleanDays(days: Int?): Int? = days?.takeIf { it >= 0 }

    private fun cleanText(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
}
