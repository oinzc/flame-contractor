package io.github.oinzc.flamecontractor.execution

import io.github.oinzc.flamecontractor.core.identity.MatchStatus
import io.github.oinzc.flamecontractor.core.storage.OfficialSparkLitStore
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.domain.SendGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionModelsTest {

    @Test
    fun inferSparkLitTodayDistinguishesGrayIconsLitIconsAndConservativeUnknownCases() {
        val grayVariants = listOf(
            "couple/gray_couple.png",
            "normal/gray_normal.png",
            "family/gray_family.png",
            "soulmate/gray_soulmate.png",
            "mate/gray_mate.png",
            "bosom/gray_bosom.png",
            "normal/GRAY_normal.png",
            "https://lf3-cdn.douyin.com/obj/flame_icon/bosom/gray_bosom.png?x-expires=123",
        )
        for (variant in grayVariants) {
            assertEquals(
                "Expected gray icon $variant -> false",
                false,
                ScanReconciliationBuilder.inferSparkLitTodayFromIconVariant(
                    iconVariant = variant,
                    sparkDays = 42,
                    sparkLabel = null,
                ),
            )
        }

        val litVariants = listOf(
            "normal/normal_normal.png",
            "couple/couple_normal.png",
            "family/family_normal.png",
            "soulmate/soulmate_normal.png",
            "mate/mate_normal.png",
            "bosom/bosom_normal.png",
            "https://lf3-cdn.douyin.com/obj/flame_icon/normal/normal_normal.png?sig=abc",
        )
        for (variant in litVariants) {
            assertEquals(
                "Expected lit icon $variant -> true",
                true,
                ScanReconciliationBuilder.inferSparkLitTodayFromIconVariant(
                    iconVariant = variant,
                    sparkDays = 18,
                    sparkLabel = null,
                ),
            )
        }

        // 保守回退 null：无图标、天数为 0 / null、异常文本（重燃/消失）、非法路径
        assertNull(
            ScanReconciliationBuilder.inferSparkLitTodayFromIconVariant(
                iconVariant = null,
                sparkDays = 18,
                sparkLabel = null,
            ),
        )
        assertNull(
            ScanReconciliationBuilder.inferSparkLitTodayFromIconVariant(
                iconVariant = "normal/normal_normal.png",
                sparkDays = 0,
                sparkLabel = null,
            ),
        )
        assertNull(
            ScanReconciliationBuilder.inferSparkLitTodayFromIconVariant(
                iconVariant = "normal/normal_normal.png",
                sparkDays = null,
                sparkLabel = null,
            ),
        )
        assertNull(
            ScanReconciliationBuilder.inferSparkLitTodayFromIconVariant(
                iconVariant = "normal/normal_normal.png",
                sparkDays = 12,
                sparkLabel = "重燃中 1/3",
            ),
        )
        assertNull(
            ScanReconciliationBuilder.inferSparkLitTodayFromIconVariant(
                iconVariant = "normal/normal_normal.png",
                sparkDays = 12,
                sparkLabel = "2天后消失",
            ),
        )
        assertNull(
            ScanReconciliationBuilder.inferSparkLitTodayFromIconVariant(
                iconVariant = "https://example.com/no_marker/normal.png",
                sparkDays = 12,
                sparkLabel = null,
            ),
        )
        assertNull(
            ScanReconciliationBuilder.inferSparkLitTodayFromIconVariant(
                iconVariant = "../secret/normal.png",
                sparkDays = 12,
                sparkLabel = null,
            ),
        )
        assertNull(
            ScanReconciliationBuilder.inferSparkLitTodayFromIconVariant(
                iconVariant = "normal/",
                sparkDays = 12,
                sparkLabel = null,
            ),
        )
    }

    @Test
    fun inferSparkKindRecognizesAllSixDouyinSparkKindsWithoutSoulmateMateCollision() {
        assertEquals("couple", ScanReconciliationBuilder.inferSparkKindFromIconVariant("couple/gray_couple.png"))
        assertEquals("family", ScanReconciliationBuilder.inferSparkKindFromIconVariant("family/gray_family.png"))
        assertEquals("normal", ScanReconciliationBuilder.inferSparkKindFromIconVariant("normal/normal_normal.png"))
        assertEquals("soulmate", ScanReconciliationBuilder.inferSparkKindFromIconVariant("soulmate/gray_soulmate.png"))
        assertEquals("mate", ScanReconciliationBuilder.inferSparkKindFromIconVariant("mate/gray_mate.png"))
        assertEquals("bosom", ScanReconciliationBuilder.inferSparkKindFromIconVariant("bosom/gray_bosom.png"))
        assertNull(ScanReconciliationBuilder.inferSparkKindFromIconVariant("unknown/icon.png"))
    }

    @Test
    fun codecRoundTripsSparkLitTodayAndDecodesLegacyJsonWithoutFieldAsNull() {
        val snapshot = ScanReconciliationSnapshot(
            scanRunId = "scan-lit-1",
            scannedAtEpochMs = 1_764_000_000_000L,
            discoveredCount = 3,
            sparkCount = 3,
            candidates = listOf(
                candidate(name = "已亮好友", sparkKind = "normal", sparkDays = 20, litToday = true),
                candidate(name = "灰火知己", sparkKind = "soulmate", sparkDays = 88, litToday = false),
                candidate(name = "未知搭子", sparkKind = "mate", sparkDays = null, sparkLabel = "重燃中", litToday = null),
            ),
        )

        val encoded = ScanReconciliationCodec.encode(snapshot)
        val decoded = ScanReconciliationCodec.decode(encoded)
        assertEquals(snapshot, decoded)
        assertEquals(true, decoded.candidates[0].sparkLitToday)
        assertEquals(false, decoded.candidates[1].sparkLitToday)
        assertNull(decoded.candidates[2].sparkLitToday)

        // 兼容老版本无 sparkLitToday 字段的持久化 JSON
        val legacyJson = """
            {
              "scanRunId": "scan-legacy",
              "scannedAtEpochMs": 1764000000000,
              "discoveredCount": 1,
              "sparkCount": 1,
              "candidates": [
                {
                  "displayName": "旧快照好友",
                  "conversationType": "friend",
                  "sparkDays": 15,
                  "sparkKind": "bosom",
                  "sparkLabel": null,
                  "matchStatus": "BOUND",
                  "platformUserId": "uid_1",
                  "platformHandle": null,
                  "platformConversationId": null,
                  "matchDetail": null,
                  "alreadyImported": true,
                  "existingTargetId": "t1",
                  "preselected": false
                }
              ]
            }
        """.trimIndent()
        val legacyDecoded = ScanReconciliationCodec.decode(legacyJson)
        assertEquals(1, legacyDecoded.candidates.size)
        assertNull(legacyDecoded.candidates[0].sparkLitToday)
        assertEquals("bosom", legacyDecoded.candidates[0].sparkKind)
    }

    @Test
    fun extractOfficialSparkLitUpdatesMatchesActiveImportedTargetsAndSkipsAmbiguous() {
        val activeTarget1 = target("t1", "好友A")
        val activeTarget2 = target("t2", "好友B")
        val activeTarget3 = target("t3", "同名好友")
        val deletedTarget = target("t_del", "已删好友", deletedAt = 999L)

        val candidates = listOf(
            candidate(name = "好友A", sparkKind = "normal", sparkDays = 10, litToday = true, alreadyImported = true, existingTargetId = "t1"),
            candidate(name = "好友B", sparkKind = "couple", sparkDays = 20, litToday = false, alreadyImported = true, existingTargetId = "t2"),
            candidate(
                name = "同名好友",
                sparkKind = "family",
                sparkDays = 30,
                litToday = true,
                matchStatus = MatchStatus.AMBIGUOUS,
                alreadyImported = true,
                existingTargetId = "t3",
            ),
            candidate(name = "未导入好友", sparkKind = "bosom", sparkDays = 5, litToday = true, alreadyImported = false, existingTargetId = null),
            candidate(name = "已删好友", sparkKind = "mate", sparkDays = 5, litToday = true, alreadyImported = true, existingTargetId = "t_del"),
        )

        val updates = ExecutionServiceStoreOps.extractOfficialSparkLitUpdates(
            existingTargets = listOf(activeTarget1, activeTarget2, activeTarget3, deletedTarget),
            candidates = candidates,
        )
        assertEquals(mapOf("t1" to true, "t2" to false), updates)
    }

    @Test
    fun makeupBatchSkipsOfficiallyLitTargetsWhileKeepingUnknownBlockedAndGrayPending() {
        val businessDate = "2026-11-25"
        val litStore = OfficialSparkLitStore()
        litStore.recordScanResults(
            accountId = DEFAULT_ACCOUNT_ID,
            businessDate = businessDate,
            updates = mapOf(
                "t_lit" to true,
                "t_gray" to false,
                "t_unknown_lit" to true,
            ),
            scannedAtEpochMs = 1_000L,
        )

        val tLocalDone = target("t_local_done", "本机已发")
        val tLit = target("t_lit", "抖音已亮")
        val tGray = target("t_gray", "抖音灰火")
        val tUnknownLit = target("t_unknown_lit", "待核对即使亮火也拦截")

        val jobsByTarget = mapOf(
            tLocalDone.id to listOf(job(tLocalDone.id, businessDate, JobStatus.SUCCEEDED)),
            tUnknownLit.id to listOf(job(tUnknownLit.id, businessDate, JobStatus.UNKNOWN_REQUIRES_REVIEW)),
        )

        val plan = ExecutionServiceStoreOps.planManualMakeupBatch(
            enabledTargets = listOf(tLocalDone, tLit, tGray, tUnknownLit),
            jobsByTarget = jobsByTarget,
            businessDate = businessDate,
            officialSparkLitStore = litStore,
        )

        assertEquals(listOf(tGray), plan.targetsToRun)
        assertEquals(listOf(tLit), plan.officialLitSkippedTargets)
        // tUnknownLit 即使在 OfficialSparkLitStore 为 true 也绝不能归入 officialLitSkippedTargets 或 targetsToRun
        assertFalse(plan.targetsToRun.any { it.id == tUnknownLit.id })
        assertFalse(plan.officialLitSkippedTargets.any { it.id == tUnknownLit.id })

        // 全部已发或抖音已点亮时的 noop 提示语
        val allCoveredMessage = ExecutionServiceStoreOps.resolveManualBatchNoopMessage(
            enabledTargets = listOf(tLocalDone, tLit),
            jobsByTarget = mapOf(tLocalDone.id to listOf(job(tLocalDone.id, businessDate, JobStatus.SUCCEEDED))),
            businessDate = businessDate,
            forceAll = false,
            preSkippedCount = 1,
            preSkipSummary = "1 位${ExecutionServiceStoreOps.SKIP_REASON_OFFICIAL_LIT_TODAY}",
            officialSparkLitStore = litStore,
        )
        assertEquals("今日启用好友已全部续过火（含抖音已点亮），无需补发", allCoveredMessage)

        // 若存在 UNKNOWN_REQUIRES_REVIEW，即使该好友抖音已点亮，仍必须优先报待核对拦截
        val blockedMessage = ExecutionServiceStoreOps.resolveManualBatchNoopMessage(
            enabledTargets = listOf(tLocalDone, tLit, tUnknownLit),
            jobsByTarget = jobsByTarget,
            businessDate = businessDate,
            forceAll = false,
            preSkippedCount = 1,
            preSkipSummary = "1 位${ExecutionServiceStoreOps.SKIP_REASON_OFFICIAL_LIT_TODAY}",
            officialSparkLitStore = litStore,
        )
        assertTrue(blockedMessage.contains("待核对"))
    }

    private fun candidate(
        name: String,
        sparkKind: String? = "normal",
        sparkDays: Int? = 10,
        sparkLabel: String? = null,
        litToday: Boolean? = null,
        matchStatus: MatchStatus = MatchStatus.BOUND,
        alreadyImported: Boolean = false,
        existingTargetId: String? = null,
    ): ScannedChatCandidate = ScannedChatCandidate(
        displayName = name,
        conversationType = "friend",
        sparkDays = sparkDays,
        sparkKind = sparkKind,
        sparkLabel = sparkLabel,
        matchStatus = matchStatus,
        platformUserId = if (matchStatus == MatchStatus.BOUND) "uid_$name" else null,
        platformHandle = null,
        platformConversationId = null,
        matchDetail = null,
        alreadyImported = alreadyImported,
        existingTargetId = existingTargetId,
        preselected = false,
        sparkLitToday = litToday,
    )

    private fun target(
        id: String,
        name: String,
        deletedAt: Long? = null,
    ): ContractTargetEntity = ContractTargetEntity(
        id = id,
        accountId = DEFAULT_ACCOUNT_ID,
        nicknameSnapshot = name,
        templateId = "tpl_1",
        deletedAt = deletedAt,
    )

    private fun job(
        targetId: String,
        businessDate: String,
        status: JobStatus,
    ): SendJobEntity = SendJobEntity(
        id = "job_$targetId",
        accountId = DEFAULT_ACCOUNT_ID,
        targetId = targetId,
        businessDate = businessDate,
        messageSlotId = SendGuard.DAILY_SLOT_ID,
        status = status,
    )
}
