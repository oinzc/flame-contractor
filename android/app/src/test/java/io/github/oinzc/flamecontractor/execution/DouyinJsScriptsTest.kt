package io.github.oinzc.flamecontractor.execution

import io.github.oinzc.flamecontractor.core.identity.MatchStatus
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinJsScriptsTest {

    @Test
    fun indexedDbScriptUsesPerStoreClosureAndEventTargetResultWithoutSharedVar() {
        val script = DouyinJsScripts.buildReadIndexedDbIdentitiesJs()

        assertTrue(script.contains("stores.forEach"))
        assertTrue(script.contains("ev.target.result"))
        assertFalse(script.contains("var allReq"))
        assertFalse(Regex("""for\s*\(\s*var\b""").containsMatchIn(script))
    }

    @Test
    fun textScriptsContainZeroWidthStripRegexAndExecCommand() {
        val bundle = SelectorBundle.defaults()
        val insertScript = DouyinJsScripts.buildInsertTextExecCommandJs(
            bundle = bundle,
            text = "第一行 \"引号\" \\斜杠\n第二行",
        )
        val checkEmptyScript = DouyinJsScripts.buildCheckEditorEmptyJs(bundle)
        val confirmScript = DouyinJsScripts.buildSampleTextConfirmJs(bundle, "第一行\"引号\"\\斜杠第二行")

        assertTrue(DouyinJsScripts.ZERO_WIDTH_REGEX_PATTERN.contains("\\u200B"))
        assertTrue(insertScript.contains("document.execCommand('insertText'"))
        assertTrue(insertScript.contains("""[\s\u200B\u200C\u200D\uFEFF]+"""))
        assertTrue(insertScript.contains("""\"引号\""""))
        assertTrue(insertScript.contains("""\\斜杠"""))
        assertTrue(checkEmptyScript.contains("""[\s\u200B\u200C\u200D\uFEFF]+"""))
        assertTrue(confirmScript.contains("""[\s\u200B\u200C\u200D\uFEFF]+"""))
        assertTrue(confirmScript.contains("isFromMe"))
    }

    @Test
    fun scanReconciliationCodecRoundTripsAndPreservesPreselectedAndAmbiguousFields() {
        val rawCandidates = listOf(
            ScannedChatCandidate(
                displayName = "无火花普通好友",
                conversationType = "friend",
                sparkDays = null,
                sparkKind = null,
                sparkLabel = "无火花",
                matchStatus = MatchStatus.BOUND,
                platformUserId = "uid_plain",
                platformHandle = "handle_plain",
                platformConversationId = null,
                matchDetail = "通过平台昵称唯一匹配",
                alreadyImported = false,
                existingTargetId = null,
                preselected = false,
            ),
            ScannedChatCandidate(
                displayName = "同名冲突火花好友",
                conversationType = "friend",
                sparkDays = 300,
                sparkKind = "normal",
                sparkLabel = null,
                matchStatus = MatchStatus.AMBIGUOUS,
                platformUserId = null,
                platformHandle = null,
                platformConversationId = null,
                matchDetail = "同名对应多个平台用户，已阻止自动绑定",
                alreadyImported = false,
                existingTargetId = null,
                preselected = false,
            ),
            ScannedChatCandidate(
                displayName = "唯一火花群聊",
                conversationType = "group",
                sparkDays = 120,
                sparkKind = "normal",
                sparkLabel = null,
                matchStatus = MatchStatus.BOUND,
                platformUserId = null,
                platformHandle = null,
                platformConversationId = "7310000000000000001",
                matchDetail = "通过抖音会话 protobuf 唯一匹配群聊",
                alreadyImported = false,
                existingTargetId = null,
                preselected = true,
            ),
            ScannedChatCandidate(
                displayName = "已导入高火花",
                conversationType = "friend",
                sparkDays = 500,
                sparkKind = "couple",
                sparkLabel = null,
                matchStatus = MatchStatus.BOUND,
                platformUserId = "uid_imported",
                platformHandle = null,
                platformConversationId = null,
                matchDetail = "通过备注名唯一匹配",
                alreadyImported = true,
                existingTargetId = "target-1",
                preselected = false,
            ),
        )

        val sorted = ScanReconciliationBuilder.sortAndComputePreselection(rawCandidates)
        assertEquals(
            listOf("同名冲突火花好友", "唯一火花群聊", "已导入高火花", "无火花普通好友"),
            sorted.map { it.displayName },
        )
        assertFalse(sorted[0].preselected)
        assertTrue(sorted[1].preselected)
        assertFalse(sorted[2].preselected)
        assertFalse(sorted[3].preselected)

        val snapshot = ScanReconciliationSnapshot(
            scanRunId = "scan-001",
            scannedAtEpochMs = 1_763_760_000_000L,
            discoveredCount = sorted.size,
            sparkCount = 3,
            candidates = sorted,
        )

        val encoded = ScanReconciliationCodec.encode(snapshot)
        val decoded = ScanReconciliationCodec.decode(encoded)

        assertEquals(snapshot, decoded)
        assertEquals(MatchStatus.AMBIGUOUS, decoded.candidates[0].matchStatus)
        assertFalse(decoded.candidates[0].preselected)
        assertTrue(decoded.candidates[1].preselected)
        assertEquals("7310000000000000001", decoded.candidates[1].platformConversationId)
        assertEquals("target-1", decoded.candidates[2].existingTargetId)
        assertNull(ScanReconciliationCodec.decodeOrNull("{}"))
    }

    @Test
    fun executionMutexRejectsContentionWithinTimeout() = runTest {
        val token = ExecutionMutex.tryAcquireSession("login_test")
        assertTrue(token != null)
        try {
            val blockedResult = ExecutionMutex.tryWithLock("scheduled_test", timeoutMs = 50L) {
                "should_not_run"
            }
            assertNull(blockedResult)
        } finally {
            ExecutionMutex.releaseSession(token)
        }
        val ran = ExecutionMutex.tryWithLock("scheduled_test", timeoutMs = 100L) {
            "ok"
        }
        assertEquals("ok", ran)
    }
}
