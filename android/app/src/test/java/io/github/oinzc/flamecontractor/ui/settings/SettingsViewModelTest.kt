package io.github.oinzc.flamecontractor.ui.settings

import io.github.oinzc.flamecontractor.core.backup.BackupCodec
import io.github.oinzc.flamecontractor.core.backup.BackupImportReport
import io.github.oinzc.flamecontractor.core.backup.BackupInspection
import io.github.oinzc.flamecontractor.core.backup.BackupManager
import io.github.oinzc.flamecontractor.core.backup.BackupStore
import io.github.oinzc.flamecontractor.core.notify.BarkNotifier
import io.github.oinzc.flamecontractor.core.notify.BarkProbeResult
import io.github.oinzc.flamecontractor.core.notify.EncryptedSecretStore
import io.github.oinzc.flamecontractor.core.notify.NotifyLevel
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundle
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundleStore
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunStatus
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.data.db.TemplatePoolItemEntity
import io.github.oinzc.flamecontractor.execution.ExecJson
import io.github.oinzc.flamecontractor.execution.StructureProbeItem
import io.github.oinzc.flamecontractor.execution.StructureProbeReport
import java.io.File
import java.io.InputStream
import java.time.Instant
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun rejectsCrossMidnightWindowInput() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val initialAccount = AccountEntity(
            id = DEFAULT_ACCOUNT_ID,
            status = AccountStatus.HEALTHY,
            enabled = true,
            sendWindowStart = "20:00",
            sendWindowEnd = "22:00",
            jitterMaxMinutes = 15,
            cooldownMinSec = 3,
            cooldownMaxSec = 8,
        )
        val repo = FakeSettingsAccountRepository(initialAccount)
        val scheduler = FakeScheduleGateway()
        val vmScope = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val vm = createViewModel(
                vmScope = vmScope,
                dispatcher = dispatcher,
                accountRepo = repo,
                scheduleGateway = scheduler,
            )
            advanceUntilIdle()

            // 1. 跨午夜窗口 23:00 -> 01:00 必须拦截且不写入库、不调用 rescheduleAll
            vm.onSendWindowStartChanged("23:00")
            vm.onSendWindowEndChanged("01:00")
            vm.saveScheduleConfig()
            advanceUntilIdle()

            var state = vm.uiState.value
            assertNotNull(state.scheduleError)
            assertTrue(
                "Expected cross-midnight message, got: ${state.scheduleError}",
                state.scheduleError!!.contains("跨午夜"),
            )
            assertEquals(0, repo.updateCount)
            assertEquals(0, scheduler.rescheduleCount)
            assertEquals("20:00", repo.currentAccount.sendWindowStart)
            assertEquals("22:00", repo.currentAccount.sendWindowEnd)

            // 2. 结束时刻超过 23:55 (例如 23:58) 必须拒绝
            vm.onSendWindowStartChanged("22:00")
            vm.onSendWindowEndChanged("23:58")
            vm.saveScheduleConfig()
            advanceUntilIdle()

            state = vm.uiState.value
            assertNotNull(state.scheduleError)
            assertEquals(0, repo.updateCount)
            assertEquals(0, scheduler.rescheduleCount)

            // 3. 开始等于结束 (20:00 -> 20:00) 必须拒绝
            vm.onSendWindowStartChanged("20:00")
            vm.onSendWindowEndChanged("20:00")
            vm.saveScheduleConfig()
            advanceUntilIdle()

            state = vm.uiState.value
            assertNotNull(state.scheduleError)
            assertEquals(0, repo.updateCount)
            assertEquals(0, scheduler.rescheduleCount)

            // 4. 非法固定时刻或非法冷却区间也必须拒绝
            vm.onSendWindowStartChanged("19:30")
            vm.onSendWindowEndChanged("21:45")
            vm.onSendTimeHhmmChanged("25:10")
            vm.saveScheduleConfig()
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.scheduleError)
            assertEquals(0, repo.updateCount)

            vm.onSendTimeHhmmChanged("")
            vm.onCooldownMinSecChanged("10")
            vm.onCooldownMaxSecChanged("5")
            vm.saveScheduleConfig()
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.scheduleError)
            assertEquals(0, repo.updateCount)

            // 5. 合法同日窗口与冷却区间应落库并立即触发 rescheduleAll()
            vm.onCooldownMinSecChanged("4")
            vm.onCooldownMaxSecChanged("9")
            vm.onJitterMaxMinutesChanged("10")
            vm.saveScheduleConfig()
            advanceUntilIdle()

            state = vm.uiState.value
            assertNull("state=$state", state.scheduleError)
            assertEquals("state=$state, updateCount=${repo.updateCount}", 1, repo.updateCount)
            assertEquals(1, scheduler.rescheduleCount)
            assertEquals("19:30", repo.currentAccount.sendWindowStart)
            assertEquals("21:45", repo.currentAccount.sendWindowEnd)
            assertEquals(10, repo.currentAccount.jitterMaxMinutes)
            assertEquals(4, repo.currentAccount.cooldownMinSec)
            assertEquals(9, repo.currentAccount.cooldownMaxSec)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun parsesAndMasksPastedBarkCurlAndPreservesSecretInKeystore() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val keyGen = KeyGenerator.getInstance("AES").apply { init(256) }
        val aesKey = keyGen.generateKey()
        val secretFile = File(tempFolder.root, "bark_secret.enc")
        val secretStore = EncryptedSecretStore(secretFile = secretFile, keyProvider = { aesKey })

        val sentProbePayloads = mutableListOf<Triple<String, String, NotifyLevel>>()
        val barkNotifier = BarkNotifier(
            baseUrlProvider = { secretStore.readBarkUrl() },
            sender = { title, body, level ->
                sentProbePayloads.add(Triple(title, body, level))
            },
        )
        val barkGateway = AndroidBarkGateway(secretStore, barkNotifier)
        val vmScope = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val vm = createViewModel(
                vmScope = vmScope,
                dispatcher = dispatcher,
                barkGateway = barkGateway,
            )
            advanceUntilIdle()

            // 初始未配置
            assertFalse(vm.uiState.value.barkConfigured)
            assertNull(vm.uiState.value.maskedBarkUrl)
            assertEquals("", vm.uiState.value.barkInputText)

            // 1. 拒绝占位符 curl 输入，不写入加密库
            vm.onBarkInputChanged("""curl -X POST https://api.day.app/push -d '{"device_key": "<device_key>"}'""")
            vm.saveBarkConfig()
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.barkError)
            assertFalse(vm.uiState.value.barkConfigured)
            assertNull(secretStore.readBarkUrl())
            assertFalse(secretFile.exists())

            // 2. 粘贴 Bark App 复制出的多行 POST JSON curl，保存并加密
            val secretKey = "MyRealBarkDeviceKey7749"
            val pastedCurl = """
                curl -X POST https://api.day.app/push \
                  -H "Content-Type: application/json" \
                  -d '{
                    "device_key": "$secretKey",
                    "title": "title",
                    "body": "body"
                  }'
            """.trimIndent()
            vm.onBarkInputChanged(pastedCurl)
            vm.saveBarkConfig()
            advanceUntilIdle()

            val stateAfterSave = vm.uiState.value
            assertNull("stateAfterSave=$stateAfterSave", stateAfterSave.barkError)
            assertTrue("stateAfterSave=$stateAfterSave", stateAfterSave.barkConfigured)
            assertEquals("https://api.day.app/****7749", stateAfterSave.maskedBarkUrl)
            // UI 状态中绝不长留明文或暴露完整 device_key
            assertEquals("https://api.day.app/****7749", stateAfterSave.barkInputText)
            assertFalse(stateAfterSave.barkInputText.contains(secretKey))
            assertFalse(stateAfterSave.barkMessage.orEmpty().contains(secretKey))

            // 底层 EncryptedSecretStore 已持久化密文且解密得到规范化明文 URL
            assertTrue(secretFile.isFile)
            val rawDiskBytes = String(secretFile.readBytes(), Charsets.ISO_8859_1)
            assertFalse(rawDiskBytes.contains(secretKey))
            assertEquals("https://api.day.app/$secretKey", secretStore.readBarkUrl())

            // 3. 发送测试推送应通过 EncryptedSecretStore 读取密钥并投递到 BarkNotifier.probe
            vm.sendBarkTestProbe()
            advanceUntilIdle()

            assertEquals(1, sentProbePayloads.size)
            assertNull(vm.uiState.value.barkError)
            assertTrue(vm.uiState.value.barkMessage?.contains("已送达") == true)

            // 4. 清空 Bark 后密文文件删除且 UI 状态清空
            vm.clearBarkConfig()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.barkConfigured)
            assertNull(vm.uiState.value.maskedBarkUrl)
            assertEquals("", vm.uiState.value.barkInputText)
            assertFalse(secretFile.exists())
            assertNull(secretStore.readBarkUrl())
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun resetsDomBrokenAfterVerifiedStructureProbeOrValidOverride() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)

        // 场景 A：账号处于 DOM_BROKEN，运行只读网页结构体检且 coreSkeletonOk == true 后恢复 HEALTHY 并重排
        val repoA = FakeSettingsAccountRepository(
            AccountEntity(
                id = DEFAULT_ACCOUNT_ID,
                status = AccountStatus.DOM_BROKEN,
                enabled = true,
            ),
        )
        val schedulerA = FakeScheduleGateway()
        val probeGatewayA = FakeProbeGateway()
        val selectorStoreA = createRealSelectorStore("subA")
        val vmScopeA = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val vmA = createViewModel(
                vmScope = vmScopeA,
                dispatcher = dispatcher,
                accountRepo = repoA,
                scheduleGateway = schedulerA,
                selectorGateway = StoreSettingsSelectorGateway(selectorStoreA),
                probeGateway = probeGatewayA,
            )
            advanceUntilIdle()

            assertEquals("uiState=${vmA.uiState.value}", AccountStatus.DOM_BROKEN, vmA.uiState.value.accountStatus)

            // 1) 未过关的体检报告（coreSkeletonOk = false）不得解除 DOM_BROKEN
            vmA.triggerStructureProbe()
            assertEquals(1, probeGatewayA.triggerCount)
            probeGatewayA.emitReport(
                StructureProbeReport(
                    checkedAtEpochMs = 10_000L,
                    loggedIn = true,
                    coreSkeletonOk = false,
                    bundleDigest = selectorStoreA.digest(),
                    webViewVersion = "157.0.8103.50",
                    items = listOf(
                        StructureProbeItem("CONVERSATION_LIST", "会话列表", hit = false, matchedCount = 0),
                    ),
                    summary = "核心列表未命中",
                ),
            )
            advanceUntilIdle()
            assertEquals(AccountStatus.DOM_BROKEN, repoA.currentAccount.status)
            assertEquals(0, schedulerA.rescheduleCount)

            // 2) 核心骨架通过的只读体检报告到达后，自动通过 AccountHealthReducer.recoverFromDomBroken 恢复 HEALTHY 并 rescheduleAll
            vmA.triggerStructureProbe()
            assertEquals(2, probeGatewayA.triggerCount)
            probeGatewayA.emitReport(
                StructureProbeReport(
                    checkedAtEpochMs = 20_000L,
                    loggedIn = true,
                    coreSkeletonOk = true,
                    bundleDigest = selectorStoreA.digest(),
                    webViewVersion = "157.0.8103.50",
                    items = listOf(
                        StructureProbeItem("CONVERSATION_LIST", "会话列表", hit = true, matchedCount = 15),
                        StructureProbeItem("CHAT_EDITOR_SELECTORS", "消息输入框", hit = true, matchedCount = 1),
                    ),
                    summary = "核心骨架全部正常",
                ),
            )
            advanceUntilIdle()
            assertEquals(AccountStatus.HEALTHY, repoA.currentAccount.status)
            assertEquals(AccountStatus.HEALTHY, vmA.uiState.value.accountStatus)
            assertEquals(1, schedulerA.rescheduleCount)
        } finally {
            vmScopeA.cancel()
        }

        // 场景 B：账号处于 DOM_BROKEN，定位器覆写校验拦截 CHAT_URL 后仍保持 DOM_BROKEN；有效覆写保存后恢复 HEALTHY 并重排
        val repoB = FakeSettingsAccountRepository(
            AccountEntity(
                id = DEFAULT_ACCOUNT_ID,
                status = AccountStatus.DOM_BROKEN,
                enabled = true,
            ),
        )
        val schedulerB = FakeScheduleGateway()
        val selectorStoreB = createRealSelectorStore("subB")
        val vmScopeB = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val vmB = createViewModel(
                vmScope = vmScopeB,
                dispatcher = dispatcher,
                accountRepo = repoB,
                scheduleGateway = schedulerB,
                selectorGateway = StoreSettingsSelectorGateway(selectorStoreB),
            )
            advanceUntilIdle()

            // 1) 尝试修改锁定的 CHAT_URL 必须被拒绝且账号维持 DOM_BROKEN
            vmB.openOverrideEditor()
            advanceUntilIdle()
            vmB.onOverrideJsonInputChanged("""{"CHAT_URL": "https://evil.example.com", "CONVERSATION_LIST": ".x"}""")
            vmB.saveSelectorOverrides()
            advanceUntilIdle()

            assertNotNull(vmB.uiState.value.overrideError)
            assertTrue(vmB.uiState.value.overrideError!!.contains("CHAT_URL"))
            assertTrue(vmB.uiState.value.showOverrideDialog)
            assertFalse(selectorStoreB.hasOverrides())
            assertEquals(AccountStatus.DOM_BROKEN, repoB.currentAccount.status)
            assertEquals(0, schedulerB.rescheduleCount)

            // 2) 保存合法的局部定位器覆写后，摘要更新、hasSelectorOverrides = true、恢复 HEALTHY 且 rescheduleAll()
            vmB.onOverrideJsonInputChanged("""{"CONVERSATION_LIST": ".newConversationListWrapper"}""")
            vmB.saveSelectorOverrides()
            advanceUntilIdle()

            assertNull(vmB.uiState.value.overrideError)
            assertFalse(vmB.uiState.value.showOverrideDialog)
            assertTrue(vmB.uiState.value.hasSelectorOverrides)
            assertEquals(selectorStoreB.digest(), vmB.uiState.value.selectorDigest)
            assertEquals(AccountStatus.HEALTHY, repoB.currentAccount.status)
            assertEquals(AccountStatus.HEALTHY, vmB.uiState.value.accountStatus)
            assertEquals(1, schedulerB.rescheduleCount)

            // 3) 恢复默认定位器后应清除本地覆写并还原基线摘要
            vmB.clearSelectorOverrides()
            advanceUntilIdle()
            assertFalse(vmB.uiState.value.hasSelectorOverrides)
            assertFalse(selectorStoreB.hasOverrides())
            assertEquals(selectorStoreB.digest(), vmB.uiState.value.selectorDigest)
        } finally {
            vmScopeB.cancel()
        }
    }

    @Test
    fun generatesSanitizedDiagnosticsReportRedactingAccountPiiAndBarkSecret() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val secretUid = "MS4wLjABAAAA_VmSecretUid_7788"
        val secretNick = "隐私账号昵称_不应出现"
        val secretKey = "VmSecretBarkKey5566"

        val repo = FakeSettingsAccountRepository(
            AccountEntity(
                id = DEFAULT_ACCOUNT_ID,
                displayName = "隐私别名",
                status = AccountStatus.HEALTHY,
                enabled = true,
                selfUid = secretUid,
                douyinNickname = secretNick,
            ),
        )
        val keyGen = KeyGenerator.getInstance("AES").apply { init(256) }
        val aesKey = keyGen.generateKey()
        val secretStore = EncryptedSecretStore(
            secretFile = File(tempFolder.root, "diag_bark.enc"),
            keyProvider = { aesKey },
        )
        secretStore.saveBarkUrl("https://api.day.app/$secretKey")
        val barkGateway = AndroidBarkGateway(secretStore, BarkNotifier(baseUrlProvider = { secretStore.readBarkUrl() }))

        val vmScope = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val vm = createViewModel(
                vmScope = vmScope,
                dispatcher = dispatcher,
                accountRepo = repo,
                barkGateway = barkGateway,
            )
            advanceUntilIdle()

            vm.generateDiagnosticsReport()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.showDiagnosticsDialog)
            val report = state.diagnosticsReport!!
            assertEquals("healthy", report.accountStatus)
            assertTrue(report.bark.configured)
            assertEquals("https://api.day.app/****5566", report.bark.maskedUrl)
            assertFalse(report.textReport.contains(secretUid))
            assertFalse(report.textReport.contains(secretNick))
            assertFalse(report.textReport.contains(secretKey))
            assertFalse(report.jsonReport.contains(secretUid))
            assertFalse(report.jsonReport.contains(secretNick))
            assertFalse(report.jsonReport.contains(secretKey))
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun validatesAutoScanConfigAndReschedulesOnValidSave() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val initialAccount = AccountEntity(
            id = DEFAULT_ACCOUNT_ID,
            status = AccountStatus.HEALTHY,
            enabled = true,
            autoScanEnabled = true,
            autoScanIntervalHours = 24,
        )
        val latestScan = ScanRunEntity(
            id = "auto-scan-1",
            accountId = DEFAULT_ACCOUNT_ID,
            source = "automatic",
            status = ScanRunStatus.COMPLETED,
            discoveredCount = 8,
            sparkCount = 6,
            startedAt = 1_763_739_000_000L,
            finishedAt = 1_763_739_015_000L,
            alertSummary = "发现 1 位好友火花告急",
        )
        val repo = FakeSettingsAccountRepository(initialAccount, initialAutoScan = latestScan)
        val scheduler = FakeScheduleGateway(nextAutoScanAt = Instant.ofEpochMilli(1_763_782_200_000L))
        val vmScope = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val vm = createViewModel(
                vmScope = vmScope,
                dispatcher = dispatcher,
                accountRepo = repo,
                scheduleGateway = scheduler,
            )
            advanceUntilIdle()

            assertTrue(vm.uiState.value.autoScanEnabledInput)
            assertEquals("24", vm.uiState.value.autoScanIntervalHoursInput)
            assertEquals("auto-scan-1", vm.uiState.value.latestAutoScanRun?.id)
            assertNotNull(vm.uiState.value.nextAutoScanTimeText)

            // 1. 0 小时或 169 小时或非数字必须拦截且不写入、不调用 rescheduleAll
            for (badValue in listOf("0", "169", "-5", "abc", "")) {
                vm.onAutoScanIntervalHoursChanged(badValue)
                vm.saveAutoScanConfig()
                advanceUntilIdle()

                assertNotNull("Expected error for [$badValue]", vm.uiState.value.autoScanError)
                assertEquals(0, repo.updateCount)
                assertEquals(0, scheduler.rescheduleCount)
                assertEquals(24, repo.currentAccount.autoScanIntervalHours)
            }

            // 2. 快捷芯片选择 12h + 关闭自动同步 -> 合法保存并触发 rescheduleAll
            vm.selectAutoScanPresetHours(12)
            assertEquals("12", vm.uiState.value.autoScanIntervalHoursInput)
            assertNull(vm.uiState.value.autoScanError)
            vm.onAutoScanEnabledChanged(false)
            assertTrue(vm.uiState.value.hasUnsavedAutoScanChanges)

            scheduler.nextAutoScanAt = null
            vm.saveAutoScanConfig()
            advanceUntilIdle()

            assertNull(vm.uiState.value.autoScanError)
            assertNotNull(vm.uiState.value.autoScanMessage)
            assertFalse(vm.uiState.value.hasUnsavedAutoScanChanges)
            assertEquals(1, repo.updateCount)
            assertEquals(1, scheduler.rescheduleCount)
            assertFalse(repo.currentAccount.autoScanEnabled)
            assertEquals(12, repo.currentAccount.autoScanIntervalHours)
            assertNull(vm.uiState.value.nextAutoScanTimeText)

            // 3. 重新开启并保存 48h，刷新下次自动同步时间
            scheduler.nextAutoScanAt = Instant.ofEpochMilli(1_763_820_000_000L)
            vm.onAutoScanEnabledChanged(true)
            vm.selectAutoScanPresetHours(48)
            vm.saveAutoScanConfig()
            advanceUntilIdle()

            assertNull(vm.uiState.value.autoScanError)
            assertEquals(2, repo.updateCount)
            assertEquals(2, scheduler.rescheduleCount)
            assertTrue(repo.currentAccount.autoScanEnabled)
            assertEquals(48, repo.currentAccount.autoScanIntervalHours)
            assertNotNull(vm.uiState.value.nextAutoScanTimeText)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun exportsInspectsMultiAccountBackupAndImportsWithOptions() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FakeSettingsBackupStore(
            account = AccountEntity(
                id = DEFAULT_ACCOUNT_ID,
                displayName = "当前本地号",
                status = AccountStatus.HEALTHY,
                selfUid = "u_local_secret",
            ),
        )
        var backupScheduleRescheduleCount = 0
        val backupManager = BackupManager(
            store = store,
            onScheduleChanged = { backupScheduleRescheduleCount++ },
            clockMillis = { 1_763_740_500_000L },
        )
        val backupGateway = AndroidBackupGateway(backupManager)
        val repo = FakeSettingsAccountRepository(store.account)
        val vmScope = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val vm = createViewModel(
                vmScope = vmScope,
                dispatcher = dispatcher,
                accountRepo = repo,
                backupGateway = backupGateway,
            )
            advanceUntilIdle()

            // 1. 导出 JSON 返回合法结构
            val exportedJson = vm.prepareExportBackupJson()
            val exportedObj = ExecJson.parseObject(exportedJson)
            assertEquals("flame-contractor", exportedObj["app"])
            assertEquals(1, (exportedObj["schemaVersion"] as Number).toInt())

            // 2. 非法 JSON 报错且不弹窗
            vm.inspectBackupFile("{not-valid-json")
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.backupError)
            assertFalse(vm.uiState.value.showBackupPreviewDialog)

            // 3. 含 2 个源账号 + 1 个 image 模板的备份打开预览弹窗并清除旧错误
            val multiAccountBackupJson = """
                {
                  "app": "flame-contractor",
                  "schemaVersion": 1,
                  "exportedAt": "2026-11-22T12:00:00Z",
                  "androidExtensions": {
                    "account": {
                      "jitterMaxMinutes": 8,
                      "cooldownMinSec": 4,
                      "cooldownMaxSec": 10,
                      "autoScanEnabled": true,
                      "autoScanIntervalHours": 12
                    }
                  },
                  "data": {
                    "accounts": [
                      {
                        "id": "acc-1",
                        "display_name": "桌面一号",
                        "status": "pending_login",
                        "enabled": true,
                        "timezone": "Asia/Shanghai",
                        "send_window_start": "19:00",
                        "send_window_end": "21:00"
                      },
                      {
                        "id": "acc-2",
                        "display_name": "桌面二号",
                        "status": "pending_login",
                        "enabled": true,
                        "timezone": "Asia/Shanghai",
                        "send_window_start": "20:30",
                        "send_window_end": "22:30"
                      }
                    ],
                    "templates": [
                      {
                        "id": "tpl-img",
                        "name": "图片模板",
                        "type": "image",
                        "content": ""
                      },
                      {
                        "id": "tpl-txt",
                        "name": "问候文本",
                        "type": "text",
                        "content": "续火打卡"
                      }
                    ],
                    "poolItems": [],
                    "targets": [
                      {
                        "id": "t-2",
                        "account_id": "acc-2",
                        "nickname_snapshot": "二号好友A",
                        "conversation_type": "user",
                        "template_id": "tpl-txt",
                        "enabled": true,
                        "streak_days": 7
                      }
                    ],
                    "jobs": [
                      {
                        "id": "job-2",
                        "account_id": "acc-2",
                        "target_id": "t-2",
                        "template_id": "tpl-txt",
                        "business_date": "2026-11-22",
                        "message_slot_id": "tpl-txt:v1",
                        "status": "succeeded"
                      }
                    ],
                    "attempts": []
                  }
                }
            """.trimIndent()

            vm.inspectBackupFile(multiAccountBackupJson)
            advanceUntilIdle()

            val previewState = vm.uiState.value
            assertNull(previewState.backupError)
            assertTrue(previewState.showBackupPreviewDialog)
            assertEquals(2, previewState.backupInspection?.accounts?.size)
            assertEquals(1, previewState.backupInspection?.skippedImageTemplateCount)
            assertEquals("acc-1", previewState.selectedBackupAccountId)
            assertTrue(previewState.includeBackupHistory)

            // 切换源账号为 acc-2，并取消勾选 includeHistory
            vm.onSelectBackupAccount("acc-2")
            vm.onIncludeBackupHistoryChanged(false)
            assertEquals("acc-2", vm.uiState.value.selectedBackupAccountId)
            assertFalse(vm.uiState.value.includeBackupHistory)

            // 确认导入
            vm.confirmImportBackup()
            advanceUntilIdle()

            val afterImport = vm.uiState.value
            assertFalse(afterImport.showBackupPreviewDialog)
            assertNull(afterImport.backupError)
            assertNotNull(afterImport.backupMessage)
            val report = afterImport.lastBackupImportReport
            assertNotNull(report)
            assertEquals("桌面二号", report!!.sourceAccountName)
            assertEquals(1, report.templatesInserted)
            assertEquals(1, report.skippedImageTemplates)
            assertEquals(1, report.targetsInserted)
            // includeHistory = false 时不应导入历史任务
            assertEquals(0, report.jobsInserted)
            assertEquals(1, backupScheduleRescheduleCount)
            // 本地登录态不可被 pending_login 覆盖
            assertEquals(AccountStatus.HEALTHY, store.account.status)
            assertEquals("u_local_secret", store.account.selfUid)
            // 重新打开预览弹窗后再取消关闭，确认预览状态被清理
            vm.inspectBackupFile(multiAccountBackupJson)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.showBackupPreviewDialog)
            vm.dismissBackupPreviewDialog()
            assertFalse(vm.uiState.value.showBackupPreviewDialog)
            assertNull(vm.uiState.value.backupInspection)
            assertNull(vm.uiState.value.selectedBackupAccountId)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun readUtf8StreamWithLimitEnforces20MbPlus1Cap() {
        val exactLimitStream = repeatingByteStream(totalBytes = BackupCodec.MAX_BACKUP_BYTES, byteValue = 'a'.code)
        val exactResult = readUtf8StreamWithLimit(exactLimitStream)
        assertEquals(BackupCodec.MAX_BACKUP_BYTES, exactResult.length)

        val overLimitStream = repeatingByteStream(totalBytes = BackupCodec.MAX_BACKUP_BYTES + 1, byteValue = 'b'.code)
        var rejected = false
        try {
            readUtf8StreamWithLimit(overLimitStream)
        } catch (expected: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Expected stream exceeding 20 MB + 1 bytes to be rejected", rejected)
    }

    private fun repeatingByteStream(totalBytes: Int, byteValue: Int): InputStream =
        object : InputStream() {
            private var remaining = totalBytes
            override fun read(): Int {
                if (remaining <= 0) return -1
                remaining--
                return byteValue
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(len, remaining)
                b.fill(byteValue.toByte(), off, off + count)
                remaining -= count
                return count
            }
        }

    @Test
    fun diagnosticsIncludesLatestAutoScanWithoutLeakingAlertSummary() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val secretAlert = "绝密好友昵称(9天 -> 1天后消失)"
        val repo = FakeSettingsAccountRepository(
            initialAccount = AccountEntity(
                id = DEFAULT_ACCOUNT_ID,
                status = AccountStatus.HEALTHY,
                autoScanEnabled = true,
                autoScanIntervalHours = 12,
            ),
            initialAutoScan = ScanRunEntity(
                id = "auto-run-diag",
                source = "automatic",
                status = ScanRunStatus.COMPLETED,
                startedAt = 1_763_739_000_000L,
                finishedAt = 1_763_739_005_000L,
                alertSummary = secretAlert,
            ),
        )
        val vmScope = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val vm = createViewModel(
                vmScope = vmScope,
                dispatcher = dispatcher,
                accountRepo = repo,
            )
            advanceUntilIdle()

            vm.generateDiagnosticsReport()
            advanceUntilIdle()

            val report = vm.uiState.value.diagnosticsReport!!
            assertTrue(report.autoScanEnabled)
            assertEquals(12, report.autoScanIntervalHours)
            assertEquals("completed", report.latestAutoScanStatus)
            assertNotNull(report.latestAutoScanAtIso)
            assertFalse(report.textReport.contains("绝密好友昵称"))
            assertFalse(report.jsonReport.contains("绝密好友昵称"))
        } finally {
            vmScope.cancel()
        }
    }

    private fun createRealSelectorStore(subDir: String): SelectorBundleStore {
        val assetCandidates = listOf(
            File("src/main/assets/selectors_bundle.json"),
            File("app/src/main/assets/selectors_bundle.json"),
            File("android/app/src/main/assets/selectors_bundle.json"),
        )
        val assetText = assetCandidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: SelectorBundle.toPrettyJson(SelectorBundle.defaults().toMap())
        val dir = tempFolder.newFolder(subDir)
        return SelectorBundleStore(
            baseRawJson = assetText,
            overrideFile = File(dir, SelectorBundleStore.OVERRIDE_FILE_NAME),
        )
    }

    private fun createViewModel(
        vmScope: CoroutineScope,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        accountRepo: FakeSettingsAccountRepository = FakeSettingsAccountRepository(),
        scheduleGateway: FakeScheduleGateway = FakeScheduleGateway(),
        selectorGateway: SettingsSelectorGateway = StoreSettingsSelectorGateway(createRealSelectorStore("default-${System.nanoTime()}")),
        barkGateway: SettingsBarkGateway = FakeBarkGateway(),
        probeGateway: FakeProbeGateway = FakeProbeGateway(),
        survivalGateway: SettingsSurvivalGateway = FakeSurvivalGateway(),
        envProvider: SettingsDiagnosticsEnvProvider = FakeDiagnosticsEnvProvider(),
        backupGateway: SettingsBackupGateway = FakeBackupGateway(),
    ): SettingsViewModel = SettingsViewModel(
        accountRepo = accountRepo,
        scheduleGateway = scheduleGateway,
        selectorGateway = selectorGateway,
        barkGateway = barkGateway,
        probeGateway = probeGateway,
        survivalGateway = survivalGateway,
        envProvider = envProvider,
        backupGateway = backupGateway,
        ioDispatcher = dispatcher,
        clockMillis = { 1_763_740_500_000L },
        coroutineScope = vmScope,
    )

    private class FakeSettingsAccountRepository(
        initialAccount: AccountEntity = AccountEntity(id = DEFAULT_ACCOUNT_ID, status = AccountStatus.HEALTHY),
        initialAutoScan: ScanRunEntity? = null,
    ) : SettingsAccountRepository {
        private val accountFlow = MutableStateFlow<AccountEntity?>(initialAccount)
        private val autoScanFlow = MutableStateFlow<ScanRunEntity?>(initialAutoScan)
        var currentAccount: AccountEntity = initialAccount
            private set
        var updateCount: Int = 0
            private set

        override fun observeAccount(accountId: String): Flow<AccountEntity?> = accountFlow

        override suspend fun getAccount(accountId: String): AccountEntity = currentAccount

        override suspend fun updateAccount(account: AccountEntity) {
            updateCount += 1
            currentAccount = account
            accountFlow.value = account
        }

        override suspend fun countActiveTargets(accountId: String): Int = 0
        override suspend fun listActiveTargets(accountId: String): List<ContractTargetEntity> = emptyList()
        override suspend fun listJobsForDate(accountId: String, businessDate: String): List<SendJobEntity> = emptyList()
        override suspend fun listRecentAttempts(accountId: String, limit: Int): List<SendAttemptEntity> = emptyList()
        override suspend fun getLatestAutoScanRun(accountId: String): ScanRunEntity? = autoScanFlow.value
        override fun observeLatestAutoScanRun(accountId: String): Flow<ScanRunEntity?> = autoScanFlow
    }

    private class FakeScheduleGateway(
        var nextAutoScanAt: Instant? = null,
    ) : SettingsScheduleGateway {
        var rescheduleCount: Int = 0
            private set

        override suspend fun rescheduleAll(): Instant {
            rescheduleCount += 1
            return Instant.ofEpochMilli(1_763_745_600_000L)
        }

        override suspend fun inspectNextAutoScanAt(): Instant? = nextAutoScanAt
    }

    private class FakeBackupGateway : SettingsBackupGateway {
        override suspend fun exportBackupJson(): String = "{}"
        override fun inspectBackup(rawJson: String): BackupInspection = BackupCodec.inspect(rawJson)
        override suspend fun importBackup(
            rawJson: String,
            selectedSourceAccountId: String,
            includeHistory: Boolean,
        ): BackupImportReport = BackupImportReport(sourceAccountName = selectedSourceAccountId)
    }

    private class FakeSettingsBackupStore(
        var account: AccountEntity,
    ) : BackupStore {
        private val templates = linkedMapOf<String, MessageTemplateEntity>()
        private val poolItems = linkedMapOf<String, TemplatePoolItemEntity>()
        private val targets = linkedMapOf<String, ContractTargetEntity>()
        private val jobs = linkedMapOf<String, SendJobEntity>()
        private val attempts = linkedMapOf<String, SendAttemptEntity>()

        override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
        override suspend fun getAccount(accountId: String): AccountEntity? =
            if (account.id == accountId) account else null
        override suspend fun upsertAccount(account: AccountEntity) {
            this.account = account
        }
        override suspend fun listTemplates(accountId: String): List<MessageTemplateEntity> =
            templates.values.filter { it.accountId == accountId }
        override suspend fun upsertTemplate(template: MessageTemplateEntity) {
            templates[template.id] = template
        }
        override suspend fun listPoolItems(templateId: String): List<TemplatePoolItemEntity> =
            poolItems.values.filter { it.templateId == templateId }
        override suspend fun upsertPoolItem(item: TemplatePoolItemEntity) {
            poolItems[item.id] = item
        }
        override suspend fun listAllTargetsIncludingDeleted(accountId: String): List<ContractTargetEntity> =
            targets.values.filter { it.accountId == accountId }
        override suspend fun upsertTarget(target: ContractTargetEntity) {
            targets[target.id] = target
        }
        override suspend fun listAllJobs(accountId: String): List<SendJobEntity> =
            jobs.values.filter { it.accountId == accountId }
        override suspend fun upsertJob(job: SendJobEntity) {
            jobs[job.id] = job
        }
        override suspend fun listAllAttemptsForAccount(accountId: String): List<SendAttemptEntity> =
            attempts.values.toList()
        override suspend fun upsertAttempt(attempt: SendAttemptEntity) {
            attempts[attempt.id] = attempt
        }
    }

    private class FakeBarkGateway : SettingsBarkGateway {
        private var storedUrl: String? = null
        override fun readMaskedBarkUrl(): String? = storedUrl?.let { "https://api.day.app/****" + it.takeLast(4) }
        override fun readRawBarkUrl(): String? = storedUrl
        override fun hasBarkUrl(): Boolean = !storedUrl.isNullOrBlank()
        override fun saveBarkUrl(normalizedUrl: String?) {
            storedUrl = normalizedUrl
        }

        override suspend fun probe(title: String, body: String, level: NotifyLevel): BarkProbeResult =
            BarkProbeResult(true, "ok")
    }

    private class FakeProbeGateway : SettingsProbeGateway {
        private val reportsFlow = MutableStateFlow<StructureProbeReport?>(null)
        private val runningFlow = MutableStateFlow(false)
        private val messageFlow = MutableStateFlow<String?>(null)

        var triggerCount: Int = 0
            private set

        override val probeReports: Flow<StructureProbeReport?> = reportsFlow
        override val runtimeRunning: Flow<Boolean> = runningFlow
        override val runtimeMessage: Flow<String?> = messageFlow

        override fun triggerStructureProbe(): Boolean {
            triggerCount += 1
            runningFlow.value = true
            return true
        }

        override fun clearLatestProbeReport() {
            reportsFlow.value = null
        }

        fun emitReport(report: StructureProbeReport) {
            runningFlow.value = false
            reportsFlow.value = report
        }
    }

    private class FakeSurvivalGateway : SettingsSurvivalGateway {
        private var confirmed = false
        override fun inspect(): RomSurvivalSnapshot = RomSurvivalChecker.buildSnapshot(
            checkedAtEpochMs = 1_000L,
            isXiaomiOrHyperOs = true,
            manufacturer = "Xiaomi",
            notificationsGranted = true,
            batteryOptimizationIgnored = true,
            exactAlarmAllowed = true,
            autostartGuidanceConfirmed = confirmed,
        )

        override fun setAutostartConfirmed(confirmed: Boolean) {
            this.confirmed = confirmed
        }
    }

    private class FakeDiagnosticsEnvProvider : SettingsDiagnosticsEnvProvider {
        override fun appVersionName(): String = "0.4.0-dev"
        override fun appVersionCode(): Long = 1L
        override fun androidSdkInt(): Int = 35
        override fun androidRelease(): String = "15"
        override fun webViewVersion(): String = "157.0.8103.50"
    }
}
