package io.github.oinzc.flamecontractor.ui.settings

import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.ScanRunEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunStatus
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
        assertEquals(1, (parsedJson["totalAccountCount"] as Number).toInt())
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

    @Test
    fun includesAutoScanFieldsWithoutRedactingKeysAndNeverLeaksAlertSummary() {
        val sensitiveAlertSummary = "好友敏感昵称(12天 -> 1天后消失)"
        val secretScanReconciliation = "{\"nickname\":\"绝密好友扫描快照\"}"
        val scanFinishedAtMs = 1_763_739_012_000L

        val account = AccountEntity(
            id = DEFAULT_ACCOUNT_ID,
            status = AccountStatus.HEALTHY,
            enabled = true,
            autoScanEnabled = true,
            autoScanIntervalHours = 12,
        )
        val latestAutoScan = ScanRunEntity(
            id = "scan-auto-1",
            accountId = DEFAULT_ACCOUNT_ID,
            source = "automatic",
            status = ScanRunStatus.COMPLETED,
            discoveredCount = 15,
            sparkCount = 10,
            reconciliationJson = secretScanReconciliation,
            startedAt = scanFinishedAtMs - 8_000L,
            finishedAt = scanFinishedAtMs,
            alertSummary = sensitiveAlertSummary,
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
                hasSelectorOverrides = false,
                account = account,
                latestAutoScan = latestAutoScan,
            ),
        )

        assertTrue(report.autoScanEnabled)
        assertEquals(12, report.autoScanIntervalHours)
        assertEquals("completed", report.latestAutoScanStatus)
        val expectedScanIso = java.time.Instant.ofEpochMilli(scanFinishedAtMs).toString()
        assertEquals(expectedScanIso, report.latestAutoScanAtIso)

        val parsedJson = ExecJson.parseObject(report.jsonReport)
        assertEquals(true, parsedJson["autoScanEnabled"])
        assertEquals(12, (parsedJson["autoScanIntervalHours"] as Number).toInt())
        assertEquals("completed", parsedJson["latestAutoScanStatus"])
        assertEquals(expectedScanIso, parsedJson["latestAutoScanAtIso"])

        for (output in listOf(report.textReport, report.jsonReport)) {
            assertFalse("Key name must not be redacted: $output", output.contains("[REDACTED_TOKEN]"))
            assertFalse("Alert summary leaked: $output", output.contains("好友敏感昵称"))
            assertFalse("Alert summary leaked: $output", output.contains(sensitiveAlertSummary))
            assertFalse("Scan reconciliation leaked: $output", output.contains("绝密好友扫描快照"))
            assertTrue("Expected completed status in output: $output", output.contains("completed"))
            assertTrue("Expected scan ISO time in output: $output", output.contains(expectedScanIso))
        }

        val disabledReport = SanitizedDiagnosticsBuilder.build(
            RawDiagnosticsContext(
                generatedAtEpochMs = 1_763_740_500_000L,
                appVersionName = "0.4.0-dev",
                appVersionCode = 1L,
                androidSdkInt = 35,
                androidRelease = "15",
                selectorDigest = "60dbb6a6f9e1",
                hasSelectorOverrides = false,
                account = AccountEntity(
                    id = DEFAULT_ACCOUNT_ID,
                    autoScanEnabled = false,
                    autoScanIntervalHours = 48,
                ),
                latestAutoScan = null,
            ),
        )
        assertFalse(disabledReport.autoScanEnabled)
        assertEquals(48, disabledReport.autoScanIntervalHours)
        assertEquals(null, disabledReport.latestAutoScanStatus)
        assertEquals(null, disabledReport.latestAutoScanAtIso)
        val disabledJson = ExecJson.parseObject(disabledReport.jsonReport)
        assertEquals(false, disabledJson["autoScanEnabled"])
        assertEquals(48, (disabledJson["autoScanIntervalHours"] as Number).toInt())
        assertEquals(null, disabledJson["latestAutoScanStatus"])
        assertEquals(null, disabledJson["latestAutoScanAtIso"])
    }

    @Test
    fun includesTotalAccountCountAndNeverLeaksShortImagePathNicknameOrUid() {
        val shortAlias = "小号B"
        val shortDouyinNick = "短昵称"
        val shortUid = "MS4wLjAB_uid1"
        val shortTargetNick = "密友Z"
        val shortImagePath = "template_images/a1.jpg"
        val shortImageFileUri = "file:///data/x.png"
        assertTrue(shortImagePath.length < 24)
        assertTrue(shortUid.length < 24)

        val report = SanitizedDiagnosticsBuilder.build(
            RawDiagnosticsContext(
                generatedAtEpochMs = 1_763_740_500_000L,
                appVersionName = "0.4.0-dev",
                appVersionCode = 1L,
                androidSdkInt = 35,
                androidRelease = "15",
                webViewVersion = "157.0.8103.50",
                selectorDigest = "60dbb6a6f9e1",
                hasSelectorOverrides = false,
                account = AccountEntity(
                    id = "acc-2",
                    displayName = shortAlias,
                    status = AccountStatus.HEALTHY,
                    selfUid = shortUid,
                    douyinNickname = shortDouyinNick,
                ),
                targets = listOf(
                    ContractTargetEntity(
                        id = "t-short",
                        accountId = "acc-2",
                        nicknameSnapshot = shortTargetNick,
                        platformUserId = shortUid,
                    ),
                ),
                todayJobs = listOf(
                    SendJobEntity(
                        id = "job-img",
                        accountId = "acc-2",
                        targetId = "t-short",
                        businessDate = "2026-11-23",
                        messageSlotId = "daily",
                        status = JobStatus.FAILED_TERMINAL,
                        summaryDetail = "缺少图片：$shortImagePath",
                    ),
                ),
                recentAttempts = listOf(
                    SendAttemptEntity(
                        id = "att-img",
                        jobId = "job-img",
                        attemptNo = 1,
                        stage = "sending",
                        status = AttemptStatus.FAILED,
                        failureClass = FailureClass.OTHER,
                        errorCode = "err_template_images/b2.png",
                    ),
                ),
                latestProbeReport = StructureProbeReport(
                    checkedAtEpochMs = 1_763_740_000_000L,
                    loggedIn = true,
                    coreSkeletonOk = true,
                    bundleDigest = "60dbb6a6f9e1",
                    webViewVersion = "157.0.8103.50",
                    items = listOf(
                        StructureProbeItem(
                            key = "IMAGE_UPLOAD",
                            label = "图片上传",
                            hit = true,
                            matchedCount = 1,
                            detail = shortImagePath,
                        ),
                    ),
                    summary = "包含 $shortAlias $shortImagePath",
                ),
                extraRawStrings = listOf(shortImagePath),
                totalAccountCount = 4,
            ),
        )

        assertEquals(4, report.totalAccountCount)
        val parsedJson = ExecJson.parseObject(report.jsonReport)
        assertEquals(4, (parsedJson["totalAccountCount"] as Number).toInt())
        assertTrue(report.textReport.contains("账号总数: 4"))

        for (output in listOf(report.textReport, report.jsonReport)) {
            assertFalse("Key totalAccountCount must stay under <24 chars and unredacted", output.contains("[REDACTED_TOKEN]"))
            assertFalse(output.contains(shortAlias))
            assertFalse(output.contains(shortDouyinNick))
            assertFalse(output.contains(shortUid))
            assertFalse(output.contains(shortTargetNick))
            assertFalse(output.contains(shortImagePath))
            assertFalse(output.contains("a1.jpg"))
            assertFalse(output.contains("b2.png"))
        }

        val redactedDirect = SanitizedDiagnosticsBuilder.redactSensitiveText(
            "err $shortImagePath and $shortImageFileUri",
        )
        assertFalse(redactedDirect.contains("template_images"))
        assertFalse(redactedDirect.contains("a1.jpg"))
        assertFalse(redactedDirect.contains("x.png"))
    }

    @Test
    fun includesRuntimeLogsUnboundCountAndSkipSummaryWithFinalRedactionGate() {
        val leakNickname = "密友隐私名ABC"
        val leakBarkKey = "SecretBarkKeyInLog9988"
        val leakSecUid = "MS4wLjABAAAA_LogLeakUid_7766"

        val report = SanitizedDiagnosticsBuilder.build(
            RawDiagnosticsContext(
                generatedAtEpochMs = 1_763_740_500_000L,
                appVersionName = "0.6.1",
                appVersionCode = 61L,
                androidSdkInt = 35,
                androidRelease = "15",
                webViewVersion = "135.0.0.0",
                selectorDigest = "60dbb6a6f9e1",
                hasSelectorOverrides = false,
                targets = listOf(
                    ContractTargetEntity(
                        id = "t-unbound",
                        nicknameSnapshot = leakNickname,
                        enabled = true,
                        templateId = null,
                    ),
                ),
                unboundTemplateTargetCount = 3,
                recentSkipSummary = "3 位未绑定可用模板，请到「好友」页绑定（含误传入 $leakNickname）",
                runtimeLogLines = listOf(
                    "2026-11-25T08:15:02Z [WARN] [SEND] target_lookup_miss visibleRows=12 scrollSteps=6 searchHit=false",
                    "2026-11-25T08:15:10Z [WARN] [AUTH] bad_line $leakNickname $leakSecUid https://api.day.app/$leakBarkKey",
                ),
            ),
        )

        assertEquals(3, report.unboundTemplateTargetCount)
        assertEquals(2, report.runtimeLogLines.size)
        assertTrue(report.textReport.contains("未绑定模板的启用好友数: 3"))
        assertTrue(report.textReport.contains("最近跳过摘要:"))
        assertTrue(report.textReport.contains("[近期脱敏运行轨迹]"))
        assertTrue(report.textReport.contains("target_lookup_miss visibleRows=12 scrollSteps=6 searchHit=false"))

        val parsedJson = ExecJson.parseObject(report.jsonReport)
        assertEquals(3, (parsedJson["unboundTemplateTargetCount"] as Number).toInt())
        @Suppress("UNCHECKED_CAST")
        val jsonLogs = parsedJson["runtimeLogLines"] as List<String>
        assertEquals(2, jsonLogs.size)

        for (output in listOf(report.textReport, report.jsonReport)) {
            assertFalse("Key unboundTemplateTargetCount must not be redacted", output.contains("[REDACTED_TOKEN]"))
            assertFalse(output.contains(leakNickname))
            assertFalse(output.contains(leakBarkKey))
            assertFalse(output.contains(leakSecUid))
        }
    }

    @Test
    fun includesDeviceModelRomDisplayWithVendorDeduplicationAndOverlaySurvival() {
        val survivalSnapshot = RomSurvivalChecker.buildSnapshot(
            checkedAtEpochMs = 1_763_740_500_000L,
            isXiaomiOrHyperOs = true,
            manufacturer = "Xiaomi",
            notificationsGranted = true,
            batteryOptimizationIgnored = true,
            exactAlarmAllowed = true,
            autostartGuidanceConfirmed = true,
            overlayAutoReturnAllowed = true,
            backgroundPopupAllowed = false,
            vendorFamily = OemVendorFamily.XIAOMI,
        )

        val subBrandReport = SanitizedDiagnosticsBuilder.build(
            RawDiagnosticsContext(
                generatedAtEpochMs = 1_763_740_500_000L,
                appVersionName = "0.6.2",
                appVersionCode = 62L,
                androidSdkInt = 35,
                androidRelease = "15",
                webViewVersion = "135.0.0.0",
                selectorDigest = "60dbb6a6f9e1",
                hasSelectorOverrides = false,
                survivalSnapshot = survivalSnapshot,
                deviceManufacturer = "Xiaomi",
                deviceBrand = "Redmi",
                deviceModel = "23113RKC6C",
                romDisplay = "OS2.0.105.0.VNCCNXM",
            ),
        )

        assertEquals("Xiaomi", subBrandReport.deviceManufacturer)
        assertEquals("Redmi", subBrandReport.deviceBrand)
        assertEquals("23113RKC6C", subBrandReport.deviceModel)
        assertEquals("OS2.0.105.0.VNCCNXM", subBrandReport.romDisplay)
        assertEquals(true, subBrandReport.survival.overlayAutoReturnAllowed)
        assertEquals(false, subBrandReport.survival.backgroundPopupAllowed)

        assertTrue(subBrandReport.textReport.contains("Android 系统: SDK 35 (Android 15)\n设备型号: Xiaomi / Redmi · 23113RKC6C\n系统 ROM: OS2.0.105.0.VNCCNXM"))
        assertTrue(subBrandReport.textReport.contains("悬浮窗自动跳回: true"))
        assertTrue(subBrandReport.textReport.contains("后台弹出界面: false"))

        val parsedJson = ExecJson.parseObject(subBrandReport.jsonReport)
        assertEquals("Xiaomi", parsedJson["deviceManufacturer"])
        assertEquals("Redmi", parsedJson["deviceBrand"])
        assertEquals("23113RKC6C", parsedJson["deviceModel"])
        assertEquals("OS2.0.105.0.VNCCNXM", parsedJson["romDisplay"])
        @Suppress("UNCHECKED_CAST")
        val survivalMap = parsedJson["survivalChecks"] as Map<String, Any?>
        assertEquals(true, survivalMap["overlayAutoReturnAllowed"])
        assertEquals(false, survivalMap["backgroundPopupAllowed"])
        assertFalse("Schema keys must not be redacted", subBrandReport.jsonReport.contains("[REDACTED_TOKEN]"))

        // manufacturer 与 brand 忽略大小写相同时自动去重，且无 ROM 时不输出系统 ROM 行
        val sameBrandReport = SanitizedDiagnosticsBuilder.build(
            RawDiagnosticsContext(
                generatedAtEpochMs = 1_763_740_500_000L,
                appVersionName = "0.6.2",
                appVersionCode = 62L,
                androidSdkInt = 35,
                androidRelease = "15",
                selectorDigest = "60dbb6a6f9e1",
                hasSelectorOverrides = false,
                deviceManufacturer = "Xiaomi",
                deviceBrand = "xiaomi",
                deviceModel = "24129PN74C",
                romDisplay = null,
            ),
        )
        assertTrue(sameBrandReport.textReport.contains("设备型号: Xiaomi · 24129PN74C"))
        assertFalse(sameBrandReport.textReport.contains("Xiaomi / xiaomi"))
        assertFalse(sameBrandReport.textReport.contains("系统 ROM:"))
        assertEquals("unknown", sameBrandReport.romDisplay)

        // 全未知时输出 unknown
        val unknownDeviceReport = SanitizedDiagnosticsBuilder.build(
            RawDiagnosticsContext(
                generatedAtEpochMs = 1_763_740_500_000L,
                appVersionName = "0.6.2",
                appVersionCode = 62L,
                androidSdkInt = 35,
                androidRelease = "15",
                selectorDigest = "60dbb6a6f9e1",
                hasSelectorOverrides = false,
            ),
        )
        assertTrue(unknownDeviceReport.textReport.contains("设备型号: unknown"))
        assertFalse(unknownDeviceReport.textReport.contains("系统 ROM:"))
    }

    @Test
    fun rejectsMaliciousDeviceFieldsAndClampsOverlongSafeRomDisplay() {
        val secretNickname = "张三丰测试密友"
        val secretBarkUrl = "https://api.day.app/SecretBarkKey123456"
        val secretCookie = "cookie=sessionid_abc987"
        val secretToken = "token=BearerSecret9988"

        val injectedReport = SanitizedDiagnosticsBuilder.build(
            RawDiagnosticsContext(
                generatedAtEpochMs = 1_763_740_500_000L,
                appVersionName = "0.6.2",
                appVersionCode = 62L,
                androidSdkInt = 35,
                androidRelease = "15",
                selectorDigest = "60dbb6a6f9e1",
                hasSelectorOverrides = false,
                targets = listOf(
                    ContractTargetEntity(
                        id = "t-1",
                        nicknameSnapshot = secretNickname,
                    ),
                ),
                deviceManufacturer = "Xiaomi $secretToken",
                deviceBrand = secretBarkUrl,
                deviceModel = "23113RKC6C $secretCookie",
                romDisplay = "HyperOS $secretNickname",
            ),
        )

        assertEquals("unknown", injectedReport.deviceManufacturer)
        assertEquals("unknown", injectedReport.deviceBrand)
        assertEquals("unknown", injectedReport.deviceModel)
        assertEquals("unknown", injectedReport.romDisplay)
        for (output in listOf(injectedReport.textReport, injectedReport.jsonReport)) {
            assertFalse(output.contains("SecretBarkKey123456"))
            assertFalse(output.contains("sessionid_abc987"))
            assertFalse(output.contains("BearerSecret9988"))
            assertFalse(output.contains(secretNickname))
        }

        // 15 位数字 IMEI 或长高熵不透明串必须拦截
        val imeiReport = SanitizedDiagnosticsBuilder.build(
            RawDiagnosticsContext(
                generatedAtEpochMs = 1_763_740_500_000L,
                appVersionName = "0.6.2",
                appVersionCode = 62L,
                androidSdkInt = 35,
                androidRelease = "15",
                selectorDigest = "60dbb6a6f9e1",
                hasSelectorOverrides = false,
                deviceModel = "864201051234567",
                romDisplay = "OpaqueTrackingTokenABCDEF123456789",
            ),
        )
        assertEquals("unknown", imeiReport.deviceModel)
        assertEquals("unknown", imeiReport.romDisplay)

        // 合法字符集但超长的 ROM 展示名须安全钳制在 64 字符以内
        val overlongSafeRom = "MagicOS 8.0.0.165(C00E165R4P1) release-keys build.2025.03.18.global.cn.extra"
        val clampedReport = SanitizedDiagnosticsBuilder.build(
            RawDiagnosticsContext(
                generatedAtEpochMs = 1_763_740_500_000L,
                appVersionName = "0.6.2",
                appVersionCode = 62L,
                androidSdkInt = 35,
                androidRelease = "15",
                selectorDigest = "60dbb6a6f9e1",
                hasSelectorOverrides = false,
                deviceManufacturer = "HONOR",
                deviceBrand = "HONOR",
                deviceModel = "BVL-AN16",
                romDisplay = overlongSafeRom,
            ),
        )
        assertTrue(clampedReport.romDisplay.length <= 64)
        assertTrue(clampedReport.romDisplay.startsWith("MagicOS 8.0.0.165(C00E165R4P1)"))
        assertTrue(clampedReport.textReport.contains("设备型号: HONOR · BVL-AN16"))
        assertTrue(clampedReport.textReport.contains("系统 ROM: ${clampedReport.romDisplay}"))
    }
}
