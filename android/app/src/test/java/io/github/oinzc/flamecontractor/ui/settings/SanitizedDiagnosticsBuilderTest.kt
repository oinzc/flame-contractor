package io.github.oinzc.flamecontractor.ui.settings

import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.execution.ExecJson
import io.github.oinzc.flamecontractor.execution.StructureProbeItem
import io.github.oinzc.flamecontractor.execution.StructureProbeReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SanitizedDiagnosticsBuilderTest {

    @Test
    fun redactsAllPiiAndBarkKeysLeavingOnlyVersionsDigestAndCounts() {
        val secretSelfUid = "MS4wLjABAAAA_RealSelfUidSecret_998877"
        val secretDouyinNickname = "王小明的私人抖音主号"
        val secretAccountAlias = "内部备用号别名"
        val secretTargetNickname = "张三丰测试密友"
        val secretTargetSecUid = "MS4wLjABAAAA_FriendSecUidSecret_112233"
        val secretTargetHandle = "dy_private_handle_42"
        val secretGroupConvId = "7398765432109876543"
        val secretCookie = "sessionid=abc987xyz654321; ttwid=1%7CsecretTtwidTokenValue999; sid_tt=secretSidToken888"
        val secretMsgBody = "早安宝子今天也要一起续火呀！"
        val secretBarkKey = "SuperSecretBarkDeviceKey9942"
        val rawBarkUrl = "https://api.day.app/$secretBarkKey"

        val account = AccountEntity(
            id = DEFAULT_ACCOUNT_ID,
            displayName = secretAccountAlias,
            status = AccountStatus.DOM_BROKEN,
            enabled = true,
            sendWindowStart = "20:00",
            sendWindowEnd = "22:00",
            authFailCount = 1,
            failureStreak = 2,
            selfUid = secretSelfUid,
            douyinNickname = secretDouyinNickname,
        )

        val targets = listOf(
            ContractTargetEntity(
                id = "t-1",
                nicknameSnapshot = secretTargetNickname,
                platformUserId = secretTargetSecUid,
                platformHandle = secretTargetHandle,
                conversationType = "friend",
                enabled = true,
                streakDays = 18,
            ),
            ContractTargetEntity(
                id = "t-2",
                nicknameSnapshot = "内部核心群聊",
                platformConversationId = secretGroupConvId,
                conversationType = "group",
                enabled = false,
                streakDays = 5,
            ),
            ContractTargetEntity(
                id = "t-deleted",
                nicknameSnapshot = "已软删好友隐私名",
                enabled = true,
                deletedAt = 1_000L,
            ),
        )

        val jobs = listOf(
            SendJobEntity(
                id = "job-1",
                targetId = "t-1",
                businessDate = "2026-11-21",
                messageSlotId = "daily",
                status = JobStatus.SUCCEEDED,
                attemptCount = 1,
                summaryDetail = "发送给 $secretTargetNickname 成功，消息正文：$secretMsgBody",
            ),
            SendJobEntity(
                id = "job-2",
                targetId = "t-2",
                businessDate = "2026-11-21",
                messageSlotId = "daily",
                status = JobStatus.FAILED_TERMINAL,
                attemptCount = 2,
                failureClass = FailureClass.DOM_CHANGED,
                summaryDetail = "Cookie=$secretCookie",
            ),
        )

        val attempts = listOf(
            SendAttemptEntity(
                id = "att-1",
                jobId = "job-1",
                attemptNo = 1,
                stage = "confirming",
                status = AttemptStatus.SUCCEEDED,
                errorCode = null,
            ),
            SendAttemptEntity(
                id = "att-2",
                jobId = "job-2",
                attemptNo = 1,
                stage = "opening_chat",
                status = AttemptStatus.FAILED,
                failureClass = FailureClass.DOM_CHANGED,
                errorCode = "dom_selector_missing",
            ),
            SendAttemptEntity(
                id = "att-3",
                jobId = "job-2",
                attemptNo = 2,
                stage = "auth_probe",
                status = AttemptStatus.FAILED,
                failureClass = FailureClass.NETWORK,
                // 模拟调用方或上游意外把敏感 URL/Cookie 混进 errorCode
                errorCode = "err_$rawBarkUrl?cookie=$secretCookie",
            ),
        )

        val probeReport = StructureProbeReport(
            checkedAtEpochMs = 1_763_740_000_000L,
            loggedIn = true,
            coreSkeletonOk = false,
            bundleDigest = "60dbb6a6f9e1",
            webViewVersion = "157.0.8103.50",
            items = listOf(
                StructureProbeItem("CONVERSATION_LIST", "会话列表", hit = true, matchedCount = 12),
                StructureProbeItem(
                    key = "CHAT_EDITOR_SELECTORS",
                    label = "消息输入框",
                    hit = false,
                    matchedCount = 0,
                    detail = "误包含隐私 $secretTargetNickname $rawBarkUrl",
                ),
            ),
            summary = "误包含 $secretDouyinNickname",
        )

        val report = SanitizedDiagnosticsBuilder.build(
            RawDiagnosticsContext(
                generatedAtEpochMs = 1_763_740_500_000L,
                appVersionName = "0.4.0-dev",
                appVersionCode = 1L,
                androidSdkInt = 35,
                androidRelease = "15",
                webViewVersion = "157.0.8103.50",
                selectorDigest = "60dbb6a6f9e1",
                hasSelectorOverrides = true,
                account = account,
                targets = targets,
                todayJobs = jobs,
                recentAttempts = attempts,
                notificationsGranted = true,
                batteryOptimizationIgnored = true,
                exactAlarmAllowed = false,
                autostartGuidanceConfirmed = true,
                rawBarkUrl = rawBarkUrl,
                latestProbeReport = probeReport,
                extraRawStrings = listOf(secretCookie, secretMsgBody),
            ),
        )

        // 1. 白名单版本、摘要、账号枚举、4 项保活布尔值与数量必须保留准确
        assertEquals("0.4.0-dev", report.appVersionName)
        assertEquals(1L, report.appVersionCode)
        assertEquals(35, report.androidSdkInt)
        assertEquals("15", report.androidRelease)
        assertEquals("157.0.8103.50", report.webViewVersion)
        assertEquals("60dbb6a6f9e1", report.selectorDigest)
        assertTrue(report.hasSelectorOverrides)
        assertEquals("dom_broken", report.accountStatus)
        assertTrue(report.accountEnabled)
        assertEquals(1, report.authFailCount)
        assertEquals(2, report.failureStreak)

        assertTrue(report.survival.notificationsGranted)
        assertTrue(report.survival.batteryOptimizationIgnored)
        assertFalse(report.survival.exactAlarmAllowed)
        assertTrue(report.survival.autostartGuidanceConfirmed)

        assertTrue(report.bark.configured)
        assertEquals("https://api.day.app/****9942", report.bark.maskedUrl)

        assertEquals(2, report.activeTargetCount)
        assertEquals(1, report.enabledTargetCount)
        assertEquals(2, report.todayJobCount)
        assertEquals(mapOf("succeeded" to 1, "failed_terminal" to 1), report.todayJobStatusCounts)
        assertEquals(3, report.recentAttemptCount)
        assertEquals(mapOf("succeeded" to 1, "failed" to 2), report.recentAttemptStatusCounts)
        assertEquals(mapOf("dom_changed" to 1, "network" to 1), report.recentAttemptFailureClassCounts)
        assertEquals(mapOf("dom_selector_missing" to 1, "redacted_code" to 1), report.recentAttemptErrorCodeCounts)

        // 2. 文本与 JSON 均不得出现任何个人隐私、UID、群 ID、Cookie、聊天内容或完整 Bark key
        val combinedOutputs = listOf(report.textReport, report.jsonReport)
        val forbiddenSubstrings = listOf(
            secretSelfUid,
            "RealSelfUidSecret",
            secretDouyinNickname,
            secretAccountAlias,
            secretTargetNickname,
            "内部核心群聊",
            "已软删好友隐私名",
            secretTargetSecUid,
            secretTargetHandle,
            secretGroupConvId,
            secretCookie,
            "abc987xyz654321",
            "secretTtwidTokenValue999",
            secretMsgBody,
            secretBarkKey,
            rawBarkUrl,
        )
        for (output in combinedOutputs) {
            for (secret in forbiddenSubstrings) {
                assertFalse("Diagnostic output leaked secret [$secret]", output.contains(secret))
            }
            assertTrue(output.contains("0.4.0-dev"))
            assertTrue(output.contains("157.0.8103.50"))
            assertTrue(output.contains("60dbb6a6f9e1"))
            assertTrue(output.contains("dom_broken"))
            assertTrue(output.contains("****9942"))
            assertTrue(output.contains("dom_selector_missing"))
            assertTrue(output.contains("CHAT_EDITOR_SELECTORS"))
        }

        // 3. JSON 输出须保持合法可解析且完整保留白名单字段键名（不得被长 token 正则误替换）
        val parsedJson = ExecJson.parseObject(report.jsonReport)
        assertEquals("0.4.0-dev", parsedJson["appVersionName"])
        assertEquals("dom_broken", parsedJson["accountStatus"])
        @Suppress("UNCHECKED_CAST")
        val survivalMap = parsedJson["survivalChecks"] as Map<String, Any?>
        assertEquals(true, survivalMap["batteryOptimizationIgnored"])
        assertEquals(true, survivalMap["autostartGuidanceConfirmed"])
        assertEquals(false, survivalMap["exactAlarmAllowed"])
        assertTrue(parsedJson.containsKey("recentAttemptFailureClassCounts"))
        assertTrue(parsedJson.containsKey("recentAttemptErrorCodeCounts"))
    }

    @Test
    fun redactsAdHocBarkCurlAndCookiesViaTextSanitizer() {
        val rawCurlLeak = """
            curl -X POST https://bark.custom.host/push -d '{"device_key":"MyCurlSecretKey1234"}'
            https://api.day.app/RealSecretKey8877/title/body?note=****
            Cookie: sessionid=raw_cookie_value_9988; ttwid=abc1234567890
            sec_uid=MS4wLjABAAAA_LeakSecUidValue_001
        """.trimIndent()

        val redacted = SanitizedDiagnosticsBuilder.redactSensitiveText(rawCurlLeak)

        assertFalse(redacted.contains("MyCurlSecretKey1234"))
        assertFalse(redacted.contains("RealSecretKey8877"))
        assertFalse(redacted.contains("raw_cookie_value_9988"))
        assertFalse(redacted.contains("abc1234567890"))
        assertFalse(redacted.contains("MS4wLjABAAAA_LeakSecUidValue_001"))
    }
}
