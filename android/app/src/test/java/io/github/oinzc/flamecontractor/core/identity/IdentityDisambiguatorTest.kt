package io.github.oinzc.flamecontractor.core.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentityDisambiguatorTest {

    @Test
    fun marksDuplicateNfkcNormalizedNicknamesAmbiguous() {
        val userRecords = listOf(
            IdentityRecord(
                platformUserId = "sec-test-user-1",
                platformHandle = "handle_one",
                platformHandleSource = "unique_id",
                nickname = "ＡＢＣ\u3000测试好友",
                remarkName = null,
            ),
            IdentityRecord(
                platformUserId = "sec-test-user-2",
                platformHandle = "handle_two",
                platformHandleSource = "unique_id",
                nickname = "平台昵称乙",
                remarkName = "ABC   测试好友",
            ),
        )

        val userMatch = IdentityDisambiguator.matchUser("  ABC 测试好友  ", userRecords)

        assertEquals(MatchStatus.AMBIGUOUS, userMatch.status)
        assertNull(userMatch.platformUserId)
        assertEquals(2, userMatch.candidateCount)

        val groupRecords = listOf(
            ConversationRecord(
                platformConversationId = "7000000000000000101",
                title = "周末　打卡群（1）",
            ),
            ConversationRecord(
                platformConversationId = "7000000000000000102",
                title = "周末 打卡群(1)",
            ),
        )

        val groupMatch = IdentityDisambiguator.matchGroup("周末  打卡群(1)", groupRecords)

        assertEquals(MatchStatus.AMBIGUOUS, groupMatch.status)
        assertNull(groupMatch.platformConversationId)
        assertEquals(2, groupMatch.candidateCount)
    }

    @Test
    fun bindsSingleExactUserAndStripsUnreadCountSuffix() {
        assertEquals("测试好友", IdentityDisambiguator.stripUnreadSuffix("测试好友\n(9)"))
        assertEquals("测试好友", IdentityDisambiguator.stripUnreadSuffix("测试好友(9)\n861"))
        assertEquals("测试好友", IdentityDisambiguator.stripUnreadSuffix("测试好友 (12)\n重燃中 2/3"))
        assertEquals("测试好友(备注)", IdentityDisambiguator.stripUnreadSuffix("测试好友(备注)\n16"))
        assertEquals("测试好友(9)特别版", IdentityDisambiguator.stripUnreadSuffix("测试好友(9)特别版\n4 天后消失"))

        val cleanedTitle = IdentityDisambiguator.stripUnreadSuffix("测试好友(9)\n重燃中 1/3")!!
        val records = listOf(
            IdentityRecord(
                platformUserId = "sec-test-unique-1",
                platformHandle = "test_handle_01",
                platformHandleSource = "unique_id",
                nickname = "平台原昵称",
                remarkName = "测试好友",
            ),
            IdentityRecord(
                platformUserId = "sec-test-other-2",
                platformHandle = "test_handle_02",
                platformHandleSource = "unique_id",
                nickname = "测试好友同学",
                remarkName = null,
            ),
        )

        val match = IdentityDisambiguator.matchUser(cleanedTitle, records)

        assertEquals(MatchStatus.BOUND, match.status)
        assertEquals("sec-test-unique-1", match.platformUserId)
        assertEquals("test_handle_01", match.platformHandle)
        assertEquals("平台原昵称", match.platformNickname)
        assertEquals("remark_name", match.source)
        assertEquals(1, match.candidateCount)
    }

    @Test
    fun normalizesChineseAndWhitespaceBoundariesCorrectly() {
        assertEquals(
            "测试 中文 ABC (1)",
            IdentityDisambiguator.normalizeLabel("  测试\u3000\t中文\u00A0\n ＡＢＣ  （１） "),
        )
        assertEquals("", IdentityDisambiguator.normalizeLabel("   \t\r\n\u3000\u00A0 "))
        assertNull(IdentityDisambiguator.stripUnreadSuffix(null))
        assertNull(IdentityDisambiguator.stripUnreadSuffix(""))
        assertNull(IdentityDisambiguator.stripUnreadSuffix("   \r\n\t\u3000  "))
        assertNull(IdentityDisambiguator.stripUnreadSuffix("(9)"))
        assertEquals(
            "中文群名",
            IdentityDisambiguator.stripUnreadSuffix("\n\r\n  中文群名(99)  \n火花 30"),
        )
        assertEquals(
            "中文群名(1)",
            IdentityDisambiguator.stripUnreadSuffix("中文群名(1)(2)"),
        )
    }

    @Test
    fun deduplicatesRepeatedSnapshotsForSameUserAndSupportsHandleLookup() {
        val records = listOf(
            IdentityRecord(
                platformUserId = "sec-same-user",
                platformHandle = "short_1001",
                platformHandleSource = "short_id",
                nickname = "测试用户甲",
                remarkName = null,
            ),
            IdentityRecord(
                platformUserId = "sec-same-user",
                platformHandle = null,
                platformHandleSource = null,
                nickname = "测试用户甲",
                remarkName = "好友备注甲",
            ),
        )

        val byRemark = IdentityDisambiguator.matchUser("好友备注甲", records)
        assertEquals(MatchStatus.BOUND, byRemark.status)
        assertEquals("sec-same-user", byRemark.platformUserId)
        assertEquals("short_1001", byRemark.platformHandle)
        assertEquals("测试用户甲", byRemark.platformNickname)
        assertEquals("remark_name", byRemark.source)
        assertEquals(1, byRemark.candidateCount)

        val byHandle = IdentityDisambiguator.matchUser("short_1001", records)
        assertEquals(MatchStatus.BOUND, byHandle.status)
        assertEquals("sec-same-user", byHandle.platformUserId)
        assertEquals("short_id", byHandle.source)

        val missing = IdentityDisambiguator.matchUser("不存在的好友", records)
        assertEquals(MatchStatus.NOT_OBSERVED, missing.status)
        assertNull(missing.platformUserId)
        assertEquals(0, missing.candidateCount)

        val blank = IdentityDisambiguator.matchUser("  \u3000 ", records)
        assertEquals(MatchStatus.NOT_OBSERVED, blank.status)
        assertEquals(0, blank.candidateCount)
    }

    @Test
    fun matchesUniqueGroupDeduplicatesSameIdAndReturnsNotObservedWhenMissing() {
        val records = listOf(
            ConversationRecord("7000000000000000201", "每日打卡群"),
            ConversationRecord("7000000000000000201", "每日打卡群"),
            ConversationRecord("7000000000000000202", "另一测试群"),
        )

        val bound = IdentityDisambiguator.matchGroup("  每日打卡群 ", records)
        assertEquals(MatchStatus.BOUND, bound.status)
        assertEquals("7000000000000000201", bound.platformConversationId)
        assertEquals("group", bound.conversationType)
        assertEquals(1, bound.candidateCount)

        val missing = IdentityDisambiguator.matchGroup("未采集群", records)
        assertEquals(MatchStatus.NOT_OBSERVED, missing.status)
        assertNull(missing.platformConversationId)
        assertEquals(0, missing.candidateCount)

        val blank = IdentityDisambiguator.matchGroup("   ", records)
        assertEquals(MatchStatus.NOT_OBSERVED, blank.status)
        assertEquals(0, blank.candidateCount)
    }

    @Test
    fun marksCrossFieldNicknameAndHandleCollisionAmbiguousWithoutLeakingPiiInDetail() {
        val records = listOf(
            IdentityRecord(
                platformUserId = "sec-cross-1",
                platformHandle = "handle_alpha",
                platformHandleSource = "unique_id",
                nickname = "共享标识名",
                remarkName = null,
            ),
            IdentityRecord(
                platformUserId = "sec-cross-2",
                platformHandle = "共享标识名",
                platformHandleSource = "unique_id",
                nickname = "另一个用户",
                remarkName = null,
            ),
        )

        val match = IdentityDisambiguator.matchUser("共享标识名", records)

        assertEquals(MatchStatus.AMBIGUOUS, match.status)
        assertNull(match.platformUserId)
        assertEquals(2, match.candidateCount)
        val detail = match.detail.orEmpty()
        assertEquals(false, detail.contains("共享标识名"))
        assertEquals(false, detail.contains("sec-cross"))
    }
}
