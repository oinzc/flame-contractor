package io.github.oinzc.flamecontractor.domain

import io.github.oinzc.flamecontractor.core.identity.MatchStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.execution.ScannedChatCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SparkDeteriorationDetectorTest {

    private fun target(
        id: String = "t-1",
        nickname: String = "好友A",
        conversationType: String? = "friend",
        platformUserId: String? = "uid_$id",
        platformConversationId: String? = null,
        enabled: Boolean = true,
        sparkDays: Int? = null,
        sparkKind: String? = null,
        sparkLabel: String? = null,
        deletedAt: Long? = null,
    ): ContractTargetEntity = ContractTargetEntity(
        id = id,
        accountId = DEFAULT_ACCOUNT_ID,
        nicknameSnapshot = nickname,
        platformUserId = platformUserId,
        platformConversationId = platformConversationId,
        conversationType = conversationType,
        enabled = enabled,
        sparkDays = sparkDays,
        sparkKind = sparkKind,
        sparkLabel = sparkLabel,
        deletedAt = deletedAt,
    )

    private fun candidate(
        displayName: String = "好友A",
        conversationType: String = "friend",
        sparkDays: Int? = null,
        sparkKind: String? = null,
        sparkLabel: String? = null,
        matchStatus: MatchStatus = MatchStatus.BOUND,
        platformUserId: String? = "uid_t-1",
        platformConversationId: String? = null,
    ): ScannedChatCandidate = ScannedChatCandidate(
        displayName = displayName,
        conversationType = conversationType,
        sparkDays = sparkDays,
        sparkKind = sparkKind,
        sparkLabel = sparkLabel,
        matchStatus = matchStatus,
        platformUserId = platformUserId,
        platformHandle = null,
        platformConversationId = platformConversationId,
        matchDetail = null,
        alreadyImported = true,
        existingTargetId = null,
        preselected = false,
    )

    @Test
    fun alertsWhenNumericSparkTransitionsToDisappearCountdown() {
        val nowMs = 1_700_000_000_000L
        val existing = listOf(
            target(
                id = "t-1",
                nickname = "小满",
                sparkDays = 15,
                sparkKind = "normal",
                sparkLabel = "15天",
            ),
        )
        val scanned = listOf(
            candidate(
                displayName = "小满",
                sparkDays = null,
                sparkKind = "normal",
                sparkLabel = "3天后火花将消失",
                platformUserId = "uid_t-1",
            ),
        )

        val result = SparkDeteriorationDetector.evaluate(existing, scanned, nowMs)

        assertEquals(1, result.updates.size)
        val update = result.updates.single()
        assertEquals("t-1", update.targetId)
        assertEquals(null, update.sparkDays)
        assertEquals("normal", update.sparkKind)
        assertEquals("3天后火花将消失", update.sparkLabel)
        assertEquals(nowMs, update.scannedAtEpochMs)

        assertEquals(1, result.alerts.size)
        assertEquals("t-1", result.alerts.single().targetId)
        assertEquals("小满", result.alerts.single().displayName)
        assertEquals("15天 → 3天后火花将消失", result.alerts.single().summary)
    }

    @Test
    fun alertsOnCountdownShorteningAndIgnoresSameOrExtendedCountdown() {
        val nowMs = 1_700_000_100_000L
        val existing = listOf(
            target(id = "t-shorter", nickname = "缩短", sparkKind = "normal", sparkLabel = "5天后消失"),
            target(id = "t-same", nickname = "不变", sparkKind = "normal", sparkLabel = "3天后消失"),
            target(id = "t-synonym", nickname = "同义", sparkKind = "normal", sparkLabel = "3天后消失"),
            target(id = "t-longer", nickname = "延长", sparkKind = "normal", sparkLabel = "2天后消失"),
            target(id = "t-saved", nickname = "救回", sparkKind = "normal", sparkLabel = "2天后消失"),
        )
        val scanned = listOf(
            candidate(displayName = "缩短", sparkKind = "normal", sparkLabel = "2天后消失", platformUserId = "uid_t-shorter"),
            candidate(displayName = "不变", sparkKind = "normal", sparkLabel = "3天后消失", platformUserId = "uid_t-same"),
            candidate(displayName = "同义", sparkKind = "normal", sparkLabel = "3天后火花将消失", platformUserId = "uid_t-synonym"),
            candidate(displayName = "延长", sparkKind = "normal", sparkLabel = "5天后消失", platformUserId = "uid_t-longer"),
            candidate(displayName = "救回", sparkDays = 10, sparkKind = "normal", sparkLabel = null, platformUserId = "uid_t-saved"),
        )

        val result = SparkDeteriorationDetector.evaluate(existing, scanned, nowMs)

        assertEquals(5, result.updates.size)
        assertEquals(listOf("t-shorter"), result.alerts.map { it.targetId })
        assertEquals("5天后消失 → 2天后消失", result.alerts.single().summary)
    }

    @Test
    fun alertsOnDisappearToRekindleAndRekindleProgressDropOnly() {
        val nowMs = 1_700_000_200_000L
        val existing = listOf(
            target(id = "t-rekindle", nickname = "跌入重燃", sparkKind = "normal", sparkLabel = "2天后消失"),
            target(id = "t-drop", nickname = "重燃回退", sparkKind = "normal", sparkLabel = "重燃中 2/3"),
            target(id = "t-up", nickname = "重燃恢复", sparkKind = "normal", sparkLabel = "重燃中 1/3"),
            target(id = "t-from-zero", nickname = "从零重燃", sparkKind = null, sparkLabel = "无火花"),
            target(id = "t-first-danger", nickname = "首次濒危", sparkKind = null, sparkLabel = null),
            target(id = "t-preview-noise", nickname = "普通提火花", sparkKind = null, sparkLabel = "无火花"),
        )
        val scanned = listOf(
            candidate(displayName = "跌入重燃", sparkKind = "normal", sparkLabel = "重燃中 1/3", platformUserId = "uid_t-rekindle"),
            candidate(displayName = "重燃回退", sparkKind = "normal", sparkLabel = "重燃中 (1/3)", platformUserId = "uid_t-drop"),
            candidate(displayName = "重燃恢复", sparkKind = "normal", sparkLabel = "重燃中 2/3", platformUserId = "uid_t-up"),
            candidate(displayName = "从零重燃", sparkKind = "normal", sparkLabel = "重燃中 1/3", platformUserId = "uid_t-from-zero"),
            candidate(displayName = "首次濒危", sparkKind = "normal", sparkLabel = "1天后消失", platformUserId = "uid_t-first-danger"),
            candidate(displayName = "普通提火花", sparkKind = null, sparkLabel = "今天一起养火花吧", platformUserId = "uid_t-preview-noise"),
        )

        val result = SparkDeteriorationDetector.evaluate(existing, scanned, nowMs)

        assertEquals(6, result.updates.size)
        assertEquals(
            listOf("t-rekindle", "t-drop", "t-first-danger"),
            result.alerts.map { it.targetId },
        )
        assertEquals("2天后消失 → 重燃中 1/3", result.alerts[0].summary)
        assertEquals("重燃中 2/3 → 重燃中 (1/3)", result.alerts[1].summary)
        assertEquals("1天后消失", result.alerts[2].summary)
    }

    @Test
    fun writesNoSparkMarkerAndAlertsOnceWithoutReAlerting() {
        val nowMs = 1_700_000_300_000L
        val existing = listOf(
            target(id = "t-lost", nickname = "断火好友", sparkDays = 10, sparkKind = "normal", sparkLabel = null),
            target(id = "t-kind-only", nickname = "图标断火", sparkDays = null, sparkKind = "normal", sparkLabel = null),
            target(id = "t-already-none", nickname = "已断火", sparkDays = null, sparkKind = null, sparkLabel = "无火花"),
            target(id = "t-preview-out", nickname = "普通预览退出", sparkDays = null, sparkKind = null, sparkLabel = "聊聊火花"),
        )
        val scanned = listOf(
            candidate(displayName = "断火好友", platformUserId = "uid_t-lost"),
            candidate(displayName = "图标断火", platformUserId = "uid_t-kind-only"),
            candidate(displayName = "已断火", platformUserId = "uid_t-already-none"),
            candidate(displayName = "普通预览退出", platformUserId = "uid_t-preview-out"),
        )

        val result = SparkDeteriorationDetector.evaluate(existing, scanned, nowMs)

        assertEquals(4, result.updates.size)
        for (update in result.updates) {
            assertEquals(null, update.sparkDays)
            assertEquals(null, update.sparkKind)
            assertEquals("无火花", update.sparkLabel)
        }
        assertEquals(listOf("t-lost", "t-kind-only"), result.alerts.map { it.targetId })
        assertEquals("10天 → 无火花", result.alerts[0].summary)
        assertEquals("无火花", result.alerts[1].summary)
    }

    @Test
    fun respectsMatchingPriorityAmbiguousDeletedUnobservedAndDisabledTargets() {
        val nowMs = 1_700_000_400_000L
        val existing = listOf(
            target(id = "t-unseen", nickname = "未滚到", sparkDays = 20),
            target(id = "t-deleted", nickname = "已删除", sparkDays = 20, deletedAt = 500L),
            target(id = "t-disabled", nickname = "已暂停", enabled = false, sparkDays = 10, sparkKind = "normal"),
            target(id = "t-ambig", nickname = "重名好友", sparkDays = 15, platformUserId = null),
            target(id = "t-renamed", nickname = "旧昵称", sparkDays = 30, platformUserId = "uid_renamed"),
            target(
                id = "t-group",
                nickname = "铁粉群",
                conversationType = "group",
                platformUserId = null,
                platformConversationId = "conv_9001",
                sparkDays = 40,
            ),
            target(id = "t-unbound", nickname = "手加 好友", platformUserId = null, sparkDays = 12),
            target(id = "t-conflict", nickname = "同名冲突", platformUserId = "uid_real", sparkDays = 50),
        )
        val scanned = listOf(
            candidate(displayName = "已删除", sparkDays = null, platformUserId = "uid_t-deleted"),
            candidate(displayName = "已暂停", sparkDays = null, platformUserId = "uid_t-disabled"),
            candidate(
                displayName = "重名好友",
                sparkDays = null,
                matchStatus = MatchStatus.AMBIGUOUS,
                platformUserId = null,
            ),
            candidate(
                displayName = "新昵称",
                sparkDays = null,
                sparkKind = "normal",
                sparkLabel = "1天后消失",
                platformUserId = "uid_renamed",
            ),
            // Duplicate candidate for same target should be ignored on second occurrence
            candidate(
                displayName = "新昵称",
                sparkDays = 99,
                sparkKind = "normal",
                sparkLabel = "99天",
                platformUserId = "uid_renamed",
            ),
            candidate(
                displayName = "铁粉群",
                conversationType = "group",
                sparkDays = null,
                sparkKind = "normal",
                sparkLabel = "2天后消失",
                platformUserId = null,
                platformConversationId = "conv_9001",
            ),
            // Unbound friend matched by normalized nickname when scan didn't observe userId
            candidate(
                displayName = "手加　好友",
                sparkDays = null,
                matchStatus = MatchStatus.NOT_OBSERVED,
                platformUserId = null,
            ),
            // Candidate with different platformUserId must NOT fall back to nickname match on t-conflict
            candidate(
                displayName = "同名冲突",
                sparkDays = null,
                matchStatus = MatchStatus.BOUND,
                platformUserId = "uid_other",
            ),
        )

        val result = SparkDeteriorationDetector.evaluate(existing, scanned, nowMs)

        assertEquals(
            setOf("t-disabled", "t-renamed", "t-group", "t-unbound"),
            result.updates.map { it.targetId }.toSet(),
        )
        assertEquals(4, result.updates.size)

        val disabledUpdate = result.updates.first { it.targetId == "t-disabled" }
        assertEquals("无火花", disabledUpdate.sparkLabel)

        val renamedUpdate = result.updates.first { it.targetId == "t-renamed" }
        assertEquals("1天后消失", renamedUpdate.sparkLabel)

        assertTrue(result.alerts.none { it.targetId == "t-disabled" })
        assertTrue(result.alerts.none { it.targetId == "t-unseen" })
        assertTrue(result.alerts.none { it.targetId == "t-deleted" })
        assertTrue(result.alerts.none { it.targetId == "t-ambig" })
        assertTrue(result.alerts.none { it.targetId == "t-conflict" })
        assertEquals(
            listOf("t-renamed", "t-group", "t-unbound"),
            result.alerts.map { it.targetId },
        )
    }
}
