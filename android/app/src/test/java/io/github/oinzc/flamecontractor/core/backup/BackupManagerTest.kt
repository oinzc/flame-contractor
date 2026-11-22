package io.github.oinzc.flamecontractor.core.backup

import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.DEFAULT_TEXT_TEMPLATE_ID
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.data.db.TemplatePoolItemEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.domain.SendGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerTest {

    @Test
    fun importSingleAndMultiAccountSelectionAndPreservesLocalAuthState() = runTest {
        val store = FakeBackupStore(
            account = AccountEntity(
                id = DEFAULT_ACCOUNT_ID,
                displayName = "旧本地名",
                status = AccountStatus.HEALTHY,
                selfUid = "u_local",
                douyinNickname = "本地登录昵称",
                authFailCount = 0,
                failureStreak = 1,
                lastVerifiedAt = 9_000L,
                lastSuccessAt = 8_000L,
                lastAutoScanAttemptAt = 7_500L,
            ),
        )
        var scheduleChangedCalls = 0
        val manager = BackupManager(
            store = store,
            onScheduleChanged = { scheduleChangedCalls++ },
            clockMillis = { 50_000L },
        )

        val multiAccountJson = """
            {
              "app": "flame-contractor",
              "schemaVersion": 1,
              "exportedAt": "2026-11-22T12:00:00Z",
              "androidExtensions": {
                "account": {
                  "jitterMaxMinutes": 10,
                  "cooldownMinSec": 5,
                  "cooldownMaxSec": 12,
                  "autoScanEnabled": false,
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
                    "timezone": "Asia/Tokyo",
                    "send_window_start": "21:00",
                    "send_window_end": "23:00",
                    "send_time_hhmm": "21:15"
                  }
                ],
                "templates": [],
                "poolItems": [],
                "targets": [
                  {
                    "id": "t-acc1",
                    "account_id": "acc-1",
                    "nickname_snapshot": "一号好友",
                    "conversation_type": "user",
                    "enabled": true,
                    "streak_days": 9
                  },
                  {
                    "id": "t-acc2",
                    "account_id": "acc-2",
                    "nickname_snapshot": "二号好友",
                    "conversation_type": "user",
                    "enabled": true,
                    "streak_days": 5
                  }
                ],
                "jobs": [],
                "attempts": []
              }
            }
        """.trimIndent()

        val inspection = manager.inspectJson(multiAccountJson)
        assertEquals(2, inspection.accounts.size)

        val report = manager.importBackup(
            parsed = inspection.parsed,
            selectedSourceAccountId = "acc-2",
        )
        assertEquals("桌面二号", report.sourceAccountName)
        assertEquals(1, report.targetsInserted)
        assertEquals(1, scheduleChangedCalls)
        assertEquals(1, store.transactionCount)

        val updatedAccount = store.getAccount(DEFAULT_ACCOUNT_ID)!!
        assertEquals("桌面二号", updatedAccount.displayName)
        assertEquals("Asia/Tokyo", updatedAccount.timezone)
        assertEquals("21:00", updatedAccount.sendWindowStart)
        assertEquals("23:00", updatedAccount.sendWindowEnd)
        assertEquals("21:15", updatedAccount.sendTimeHhmm)
        assertEquals(10, updatedAccount.jitterMaxMinutes)
        assertEquals(5, updatedAccount.cooldownMinSec)
        assertEquals(12, updatedAccount.cooldownMaxSec)
        assertFalse(updatedAccount.autoScanEnabled)
        assertEquals(12, updatedAccount.autoScanIntervalHours)
        // 本地鉴权与登录身份绝对不被覆盖
        assertEquals(AccountStatus.HEALTHY, updatedAccount.status)
        assertEquals("u_local", updatedAccount.selfUid)
        assertEquals("本地登录昵称", updatedAccount.douyinNickname)
        assertEquals(0, updatedAccount.authFailCount)
        assertEquals(1, updatedAccount.failureStreak)
        assertEquals(9_000L, updatedAccount.lastVerifiedAt)
        assertEquals(7_500L, updatedAccount.lastAutoScanAttemptAt)

        // 只导入了 acc-2 的好友
        val targets = store.listAllTargetsIncludingDeleted(DEFAULT_ACCOUNT_ID)
        assertEquals(listOf("二号好友"), targets.map { it.nicknameSnapshot })

        // 若本地原本是 PENDING_LOGIN，即使导入携带 status="healthy" 也绝不置为 HEALTHY
        val pendingStore = FakeBackupStore(
            account = AccountEntity(id = DEFAULT_ACCOUNT_ID, status = AccountStatus.PENDING_LOGIN),
        )
        val pendingManager = BackupManager(store = pendingStore, clockMillis = { 60_000L })
        val forgedHealthyJson = """
            {
              "app": "flame-contractor",
              "schemaVersion": 1,
              "exportedAt": "2026-11-22T12:00:00Z",
              "data": {
                "accounts": [
                  {
                    "id": "acc-x",
                    "display_name": "新号",
                    "status": "healthy",
                    "timezone": "Asia/Shanghai",
                    "send_window_start": "20:00",
                    "send_window_end": "22:00"
                  }
                ],
                "templates": [],
                "poolItems": [],
                "targets": [],
                "jobs": [],
                "attempts": []
              }
            }
        """.trimIndent()
        val forgedInspection = pendingManager.inspectJson(forgedHealthyJson)
        pendingManager.importBackup(forgedInspection.parsed, "acc-x")
        assertEquals(AccountStatus.PENDING_LOGIN, pendingStore.getAccount(DEFAULT_ACCOUNT_ID)!!.status)
    }

    @Test
    fun importSkipsImageTemplatesWithoutClearingExistingTargetTemplate() = runTest {
        val localTpl = MessageTemplateEntity(
            id = "tpl-local",
            accountId = DEFAULT_ACCOUNT_ID,
            name = "本地文本",
            type = TemplateType.TEXT,
            content = "早安",
        )
        val existingTargetA = ContractTargetEntity(
            id = "local-tgt-a",
            accountId = DEFAULT_ACCOUNT_ID,
            nicknameSnapshot = "好友A",
            platformUserId = "puid-a",
            conversationType = "friend",
            templateId = "tpl-local",
            enabled = true,
        )
        val store = FakeBackupStore(
            account = AccountEntity(id = DEFAULT_ACCOUNT_ID),
            templates = listOf(localTpl),
            targets = listOf(existingTargetA),
        )
        val manager = BackupManager(store = store, clockMillis = { 50_000L })

        val backupJson = """
            {
              "app": "flame-contractor",
              "schemaVersion": 1,
              "exportedAt": "2026-11-22T12:00:00Z",
              "data": {
                "accounts": [
                  {
                    "id": "pc-acc",
                    "display_name": "桌面号",
                    "status": "pending_login",
                    "timezone": "Asia/Shanghai",
                    "send_window_start": "20:00",
                    "send_window_end": "22:00"
                  }
                ],
                "templates": [
                  {
                    "id": "tpl-img",
                    "name": "早安图",
                    "type": "image",
                    "image_path": "goodmorning.png",
                    "enabled": true
                  },
                  {
                    "id": "tpl-txt",
                    "name": "问候语",
                    "type": "text",
                    "content": "打卡",
                    "enabled": true
                  }
                ],
                "poolItems": [],
                "targets": [
                  {
                    "id": "pc-tgt-a",
                    "account_id": "pc-acc",
                    "platform_user_id": "puid-a",
                    "nickname_snapshot": "好友A",
                    "conversation_type": "user",
                    "template_id": "tpl-img",
                    "enabled": true
                  },
                  {
                    "id": "pc-tgt-b",
                    "account_id": "pc-acc",
                    "platform_user_id": "puid-b",
                    "nickname_snapshot": "好友B",
                    "conversation_type": "user",
                    "template_id": "tpl-img",
                    "enabled": true
                  }
                ],
                "jobs": [],
                "attempts": []
              }
            }
        """.trimIndent()

        val inspection = manager.inspectJson(backupJson)
        assertEquals(1, inspection.templateCount)
        assertEquals(1, inspection.skippedImageTemplateCount)

        val report = manager.importBackup(inspection.parsed, "pc-acc")
        assertEquals(1, report.skippedImageTemplates)
        assertEquals(1, report.templatesInserted)
        assertEquals(1, report.targetsUpdated)
        assertEquals(1, report.targetsInserted)
        assertTrue(report.warnings.any { it.contains("图片模板") })

        val templates = store.listTemplates(DEFAULT_ACCOUNT_ID)
        assertNull(templates.firstOrNull { it.id == "tpl-img" })
        assertNotNull(templates.firstOrNull { it.id == "tpl-txt" })

        val targets = store.listAllTargetsIncludingDeleted(DEFAULT_ACCOUNT_ID)
        val mergedA = targets.first { it.platformUserId == "puid-a" }
        val insertedB = targets.first { it.platformUserId == "puid-b" }
        assertEquals("tpl-local", mergedA.templateId)
        assertNull(insertedB.templateId)
    }

    @Test
    fun importResolvesSameNameDifferentTypeTemplateCollisionWithSuffix() = runTest {
        val localDailyText = MessageTemplateEntity(
            id = DEFAULT_TEXT_TEMPLATE_ID,
            accountId = DEFAULT_ACCOUNT_ID,
            name = "每日续火",
            type = TemplateType.TEXT,
            content = "本地默认文本",
        )
        val existingImportedName = MessageTemplateEntity(
            id = "tpl-already-imported",
            accountId = DEFAULT_ACCOUNT_ID,
            name = "每日续火 (导入)",
            type = TemplateType.STICKER,
            stickerName = "比心",
        )
        val store = FakeBackupStore(
            account = AccountEntity(id = DEFAULT_ACCOUNT_ID),
            templates = listOf(localDailyText, existingImportedName),
        )
        val manager = BackupManager(store = store, clockMillis = { 50_000L })

        val backupJson = """
            {
              "app": "flame-contractor",
              "schemaVersion": 1,
              "exportedAt": "2026-11-22T12:00:00Z",
              "data": {
                "accounts": [
                  {
                    "id": "pc-acc",
                    "display_name": "桌面号",
                    "status": "pending_login",
                    "timezone": "Asia/Shanghai",
                    "send_window_start": "20:00",
                    "send_window_end": "22:00"
                  }
                ],
                "templates": [
                  {
                    "id": "pc-pool-1",
                    "name": "每日续火",
                    "type": "pool",
                    "content": "",
                    "enabled": true
                  },
                  {
                    "id": "pc-text-2",
                    "name": "早安文本",
                    "type": "text",
                    "content": "早安"
                  }
                ],
                "poolItems": [
                  {
                    "id": "pc-pi-1",
                    "template_id": "pc-pool-1",
                    "content": "池文案1",
                    "weekday_mask": 0,
                    "enabled": true
                  },
                  {
                    "id": "pc-pi-ignored",
                    "template_id": "pc-text-2",
                    "content": "非池模板项应忽略",
                    "weekday_mask": 0,
                    "enabled": true
                  }
                ],
                "targets": [
                  {
                    "id": "pc-tgt-1",
                    "account_id": "pc-acc",
                    "nickname_snapshot": "好友A",
                    "conversation_type": "user",
                    "template_id": "pc-pool-1",
                    "enabled": true
                  }
                ],
                "jobs": [
                  {
                    "id": "pc-job-1",
                    "account_id": "pc-acc",
                    "target_id": "pc-tgt-1",
                    "template_id": "pc-pool-1",
                    "business_date": "2026-11-22",
                    "message_slot_id": "pc-pool-1:v1",
                    "status": "succeeded",
                    "attempt_count": 1
                  }
                ],
                "attempts": []
              }
            }
        """.trimIndent()

        val report = manager.importBackup(manager.inspectJson(backupJson).parsed, "pc-acc")
        assertEquals(2, report.templatesInserted)
        assertEquals(1, report.poolItemsMerged)
        assertTrue(report.warnings.any { it.contains("每日续火 (导入 2)") })

        val templates = store.listTemplates(DEFAULT_ACCOUNT_ID)
        val originalDaily = templates.first { it.id == DEFAULT_TEXT_TEMPLATE_ID }
        assertEquals(TemplateType.TEXT, originalDaily.type)
        assertEquals("本地默认文本", originalDaily.content)

        val renamedPool = templates.first { it.name == "每日续火 (导入 2)" }
        assertEquals(TemplateType.POOL, renamedPool.type)
        assertNotEquals("pc-pool-1", renamedPool.id)

        val poolItems = store.listPoolItems(renamedPool.id)
        assertEquals(1, poolItems.size)
        assertEquals("池文案1", poolItems[0].content)
        assertTrue(store.listPoolItems("pc-text-2").isEmpty())

        val target = store.listAllTargetsIncludingDeleted(DEFAULT_ACCOUNT_ID).single()
        assertEquals(renamedPool.id, target.templateId)

        val job = store.listAllJobs(DEFAULT_ACCOUNT_ID).single()
        assertEquals(renamedPool.id, job.templateId)
        assertEquals(SendGuard.DAILY_SLOT_ID, job.messageSlotId)
    }

    @Test
    fun importPrefersActiveTargetOverSoftDeletedAndMaxStreakDays() = runTest {
        val localActive = ContractTargetEntity(
            id = "loc-active",
            accountId = DEFAULT_ACCOUNT_ID,
            nicknameSnapshot = "活跃好友",
            platformUserId = "uid-1",
            conversationType = "friend",
            enabled = true,
            sparkDays = 10,
            sparkKind = "fire",
            sparkLabel = "10天",
            sparkScannedAt = 5_000L,
            streakDays = 10,
            lastSentAt = 5_000L,
            lastConfirmedAt = 5_000L,
            deletedAt = null,
        )
        val localDeleted = ContractTargetEntity(
            id = "loc-deleted",
            accountId = DEFAULT_ACCOUNT_ID,
            nicknameSnapshot = "待复活好友",
            platformUserId = "uid-2",
            conversationType = "friend",
            enabled = false,
            sparkDays = 4,
            sparkKind = "fire",
            sparkLabel = "4天",
            sparkScannedAt = 3_000L,
            streakDays = 4,
            deletedAt = 2_000L,
        )
        val localCollisionOwner = ContractTargetEntity(
            id = "loc-collision",
            accountId = DEFAULT_ACCOUNT_ID,
            nicknameSnapshot = "碰撞昵称",
            platformUserId = "uid-real-local",
            conversationType = "friend",
            enabled = true,
        )
        val store = FakeBackupStore(
            account = AccountEntity(id = DEFAULT_ACCOUNT_ID),
            targets = listOf(localActive, localDeleted, localCollisionOwner),
        )
        val manager = BackupManager(store = store, clockMillis = { 50_000L })

        val backupJson = """
            {
              "app": "flame-contractor",
              "schemaVersion": 1,
              "exportedAt": "2026-11-22T12:00:00Z",
              "data": {
                "accounts": [
                  {
                    "id": "pc-acc",
                    "display_name": "桌面号",
                    "status": "pending_login",
                    "timezone": "Asia/Shanghai",
                    "send_window_start": "20:00",
                    "send_window_end": "22:00"
                  }
                ],
                "templates": [],
                "poolItems": [],
                "targets": [
                  {
                    "id": "pc-1",
                    "account_id": "pc-acc",
                    "platform_user_id": "uid-1",
                    "nickname_snapshot": "活跃好友",
                    "conversation_type": "user",
                    "enabled": false,
                    "spark_days": 2,
                    "spark_kind": "fire",
                    "spark_label": "2天",
                    "spark_scanned_at": "1970-01-01T00:00:03Z",
                    "streak_days": 3,
                    "deleted_at": "1970-01-01T00:00:04Z"
                  },
                  {
                    "id": "pc-2",
                    "account_id": "pc-acc",
                    "platform_user_id": "uid-2",
                    "nickname_snapshot": "待复活好友",
                    "conversation_type": "user",
                    "enabled": true,
                    "spark_days": 15,
                    "spark_kind": "fire",
                    "spark_label": "15天",
                    "spark_scanned_at": "1970-01-01T00:00:06Z",
                    "streak_days": 12,
                    "deleted_at": null
                  },
                  {
                    "id": "pc-bad-collision",
                    "account_id": "pc-acc",
                    "platform_user_id": "uid-different",
                    "nickname_snapshot": "碰撞昵称",
                    "conversation_type": "user",
                    "enabled": true,
                    "streak_days": 99
                  }
                ],
                "jobs": [
                  {
                    "id": "job-for-skipped-target",
                    "account_id": "pc-acc",
                    "target_id": "pc-bad-collision",
                    "business_date": "2026-11-22",
                    "message_slot_id": "raw:1",
                    "status": "succeeded"
                  }
                ],
                "attempts": []
              }
            }
        """.trimIndent()

        val report = manager.importBackup(manager.inspectJson(backupJson).parsed, "pc-acc")
        assertEquals(2, report.targetsUpdated)
        assertEquals(1, report.targetsSkipped)
        assertEquals(0, report.jobsInserted)
        assertTrue(report.warnings.any { it.contains("碰撞昵称") })

        val targets = store.listAllTargetsIncludingDeleted(DEFAULT_ACCOUNT_ID).associateBy { it.id }
        val t1 = targets.getValue("loc-active")
        assertNull(t1.deletedAt)
        assertTrue(t1.enabled)
        assertEquals(10, t1.streakDays)
        assertEquals(10, t1.sparkDays)

        val t2 = targets.getValue("loc-deleted")
        assertNull(t2.deletedAt)
        assertTrue(t2.enabled)
        assertEquals(12, t2.streakDays)
        assertEquals(15, t2.sparkDays)
        assertEquals(6_000L, t2.sparkScannedAt)

        val t3 = targets.getValue("loc-collision")
        assertEquals("uid-real-local", t3.platformUserId)
        assertEquals(0, t3.streakDays)
    }

    @Test
    fun importProtectsCrossMidnightWindowAndUnknownRequiresReviewJobs() = runTest {
        val localTarget = ContractTargetEntity(
            id = "tgt-a",
            accountId = DEFAULT_ACCOUNT_ID,
            nicknameSnapshot = "好友A",
            platformUserId = "uid-a",
            conversationType = "friend",
        )
        val protectedLocalJob = SendJobEntity(
            id = "local-unk-job",
            accountId = DEFAULT_ACCOUNT_ID,
            targetId = "tgt-a",
            businessDate = "2026-11-22",
            messageSlotId = SendGuard.DAILY_SLOT_ID,
            status = JobStatus.UNKNOWN_REQUIRES_REVIEW,
            attemptCount = 2,
            failureClass = FailureClass.UNKNOWN_OUTCOME,
            summaryDetail = "本地待核对",
        )
        val store = FakeBackupStore(
            account = AccountEntity(
                id = DEFAULT_ACCOUNT_ID,
                sendWindowStart = "20:00",
                sendWindowEnd = "22:00",
            ),
            targets = listOf(localTarget),
            jobs = listOf(protectedLocalJob),
        )
        val manager = BackupManager(store = store, clockMillis = { 50_000L })

        val backupJson = """
            {
              "app": "flame-contractor",
              "schemaVersion": 1,
              "exportedAt": "2026-11-22T12:00:00Z",
              "androidExtensions": {
                "account": {
                  "jitterMaxMinutes": 99,
                  "cooldownMinSec": -5,
                  "cooldownMaxSec": 120,
                  "autoScanEnabled": true,
                  "autoScanIntervalHours": 999
                },
                "attemptMeta": {
                  "att-run": {
                    "inputMethodUsed": "keyboardFallback",
                    "errorCode": "TIMEOUT"
                  }
                }
              },
              "data": {
                "accounts": [
                  {
                    "id": "pc-acc",
                    "display_name": "桌面号",
                    "status": "pending_login",
                    "timezone": "Invalid/Timezone",
                    "send_window_start": "23:00",
                    "send_window_end": "01:00"
                  }
                ],
                "templates": [],
                "poolItems": [],
                "targets": [
                  {
                    "id": "pc-tgt-a",
                    "account_id": "pc-acc",
                    "platform_user_id": "uid-a",
                    "nickname_snapshot": "好友A",
                    "conversation_type": "user"
                  }
                ],
                "jobs": [
                  {
                    "id": "pc-job-override-attempt",
                    "account_id": "pc-acc",
                    "target_id": "pc-tgt-a",
                    "business_date": "2026-11-22",
                    "message_slot_id": "tpl-1:v1",
                    "status": "succeeded",
                    "attempt_count": 1
                  },
                  {
                    "id": "pc-job-sending",
                    "account_id": "pc-acc",
                    "target_id": "pc-tgt-a",
                    "business_date": "2026-11-21",
                    "message_slot_id": "tpl-1:v1",
                    "status": "sending",
                    "attempt_count": 1
                  },
                  {
                    "id": "pc-job-confirming",
                    "account_id": "pc-acc",
                    "target_id": "pc-tgt-a",
                    "business_date": "2026-11-20",
                    "message_slot_id": "tpl-1:v1",
                    "status": "confirming",
                    "attempt_count": 1
                  },
                  {
                    "id": "pc-job-leased",
                    "account_id": "pc-acc",
                    "target_id": "pc-tgt-a",
                    "business_date": "2026-11-19",
                    "message_slot_id": "tpl-1:v1",
                    "status": "leased",
                    "attempt_count": 1
                  }
                ],
                "attempts": [
                  {
                    "id": "att-ignored-for-protected",
                    "job_id": "pc-job-override-attempt",
                    "attempt_no": 1,
                    "stage": "confirming",
                    "status": "succeeded"
                  },
                  {
                    "id": "att-run",
                    "job_id": "pc-job-sending",
                    "attempt_no": 1,
                    "stage": "sending",
                    "status": "running",
                    "started_at": "2026-11-21T12:00:00Z"
                  }
                ]
              }
            }
        """.trimIndent()

        val report = manager.importBackup(manager.inspectJson(backupJson).parsed, "pc-acc")
        assertEquals(1, report.skippedProtectedJobs)
        assertEquals(3, report.jobsInserted)
        assertTrue(report.warnings.any { it.contains("23:00–01:00") })
        assertTrue(report.warnings.any { it.contains("Invalid/Timezone") })

        val account = store.getAccount(DEFAULT_ACCOUNT_ID)!!
        assertEquals("20:00", account.sendWindowStart)
        assertEquals("22:00", account.sendWindowEnd)
        assertEquals(15, account.jitterMaxMinutes)
        assertEquals(0, account.cooldownMinSec)
        assertEquals(60, account.cooldownMaxSec)
        assertEquals(168, account.autoScanIntervalHours)

        val jobsByDate = store.listAllJobs(DEFAULT_ACCOUNT_ID).associateBy { it.businessDate }
        assertEquals(JobStatus.UNKNOWN_REQUIRES_REVIEW, jobsByDate.getValue("2026-11-22").status)
        assertEquals("local-unk-job", jobsByDate.getValue("2026-11-22").id)
        assertEquals(JobStatus.UNKNOWN_REQUIRES_REVIEW, jobsByDate.getValue("2026-11-21").status)
        assertEquals(JobStatus.UNKNOWN_REQUIRES_REVIEW, jobsByDate.getValue("2026-11-20").status)
        assertEquals(JobStatus.FAILED_RETRYABLE, jobsByDate.getValue("2026-11-19").status)

        val attempts = store.listAllAttemptsForAccount(DEFAULT_ACCOUNT_ID)
        assertEquals(1, attempts.size)
        assertEquals(AttemptStatus.FAILED, attempts[0].status)
        assertEquals("keyboardFallback", attempts[0].inputMethodUsed)
        assertEquals("TIMEOUT", attempts[0].errorCode)
    }

    @Test
    fun importMapsBackendDailySlotsAndPreventsSameDayDuplicateSend() = runTest {
        val store = FakeBackupStore(
            account = AccountEntity(id = DEFAULT_ACCOUNT_ID),
        )
        var scheduleChangedCalls = 0
        val manager = BackupManager(
            store = store,
            onScheduleChanged = { scheduleChangedCalls++ },
            clockMillis = { 50_000L },
        )

        // 先验证超限/坏 JSON 零事务调用
        assertThrows(IllegalArgumentException::class.java) {
            manager.inspectJson("{corrupted")
        }
        assertEquals(0, store.transactionCount)

        val backupJson = """
            {
              "app": "flame-contractor",
              "schemaVersion": 1,
              "exportedAt": "2026-11-22T12:00:00Z",
              "data": {
                "accounts": [
                  {
                    "id": "pc-acc",
                    "display_name": "桌面号",
                    "status": "pending_login",
                    "timezone": "Asia/Shanghai",
                    "send_window_start": "20:00",
                    "send_window_end": "22:00"
                  }
                ],
                "templates": [
                  {
                    "id": "tpl-a",
                    "name": "模板A",
                    "type": "text",
                    "content": "A"
                  },
                  {
                    "id": "tpl-b",
                    "name": "模板B",
                    "type": "text",
                    "content": "B"
                  }
                ],
                "poolItems": [],
                "targets": [
                  {
                    "id": "pc-tgt-a",
                    "account_id": "pc-acc",
                    "platform_user_id": "uid-a",
                    "nickname_snapshot": "好友A",
                    "conversation_type": "user",
                    "template_id": "tpl-b"
                  }
                ],
                "jobs": [
                  {
                    "id": "job-fail",
                    "account_id": "pc-acc",
                    "target_id": "pc-tgt-a",
                    "template_id": "tpl-a",
                    "business_date": "2026-11-22",
                    "message_slot_id": "tpl-a:v1",
                    "status": "failed_terminal",
                    "attempt_count": 3
                  },
                  {
                    "id": "job-ok",
                    "account_id": "pc-acc",
                    "target_id": "pc-tgt-a",
                    "template_id": "tpl-b",
                    "business_date": "2026-11-22",
                    "message_slot_id": "tpl-b:v1",
                    "status": "succeeded",
                    "attempt_count": 1
                  },
                  {
                    "id": "job-force",
                    "account_id": "pc-acc",
                    "target_id": "pc-tgt-a",
                    "template_id": "tpl-b",
                    "business_date": "2026-11-22",
                    "message_slot_id": "tpl-b:v1:force:1700000000",
                    "status": "succeeded",
                    "attempt_count": 1
                  }
                ],
                "attempts": []
              }
            }
        """.trimIndent()

        val report = manager.importBackup(
            parsed = manager.inspectJson(backupJson).parsed,
            selectedSourceAccountId = "pc-acc",
            includeHistory = true,
        )
        assertEquals(3, report.jobsInserted)
        assertEquals(1, scheduleChangedCalls)

        val importedJobs = store.listAllJobs(DEFAULT_ACCOUNT_ID)
        val okJob = importedJobs.first { it.id == "job-ok" }
        val failJob = importedJobs.first { it.id == "job-fail" }
        val forceJob = importedJobs.first { it.id == "job-force" }

        assertEquals(SendGuard.DAILY_SLOT_ID, okJob.messageSlotId)
        assertEquals("manual:import:job-fail", failJob.messageSlotId)
        assertEquals("manual:import:job-force", forceJob.messageSlotId)
        assertFalse(SendGuard.canDispatchDaily(importedJobs))

        // 验证 includeHistory = false 不写入历史任务
        val noHistoryStore = FakeBackupStore(account = AccountEntity(id = DEFAULT_ACCOUNT_ID))
        val noHistoryManager = BackupManager(store = noHistoryStore, clockMillis = { 50_000L })
        val noHistReport = noHistoryManager.importBackup(
            parsed = noHistoryManager.inspectJson(backupJson).parsed,
            selectedSourceAccountId = "pc-acc",
            includeHistory = false,
        )
        assertEquals(0, noHistReport.jobsInserted)
        assertTrue(noHistoryStore.listAllJobs(DEFAULT_ACCOUNT_ID).isEmpty())

        // 验证本地已 SUCCEEDED 的 daily 任务不会被较旧备份中的 SCHEDULED 降级
        val downgradeBackup = """
            {
              "app": "flame-contractor",
              "schemaVersion": 1,
              "exportedAt": "2026-11-22T10:00:00Z",
              "data": {
                "accounts": [{"id":"pc-acc","display_name":"桌面号","status":"pending_login"}],
                "templates": [],
                "poolItems": [],
                "targets": [
                  {
                    "id": "pc-tgt-a",
                    "account_id": "pc-acc",
                    "platform_user_id": "uid-a",
                    "nickname_snapshot": "好友A",
                    "conversation_type": "user"
                  }
                ],
                "jobs": [
                  {
                    "id": "job-stale",
                    "account_id": "pc-acc",
                    "target_id": "pc-tgt-a",
                    "business_date": "2026-11-22",
                    "message_slot_id": "tpl-b:v1",
                    "status": "scheduled",
                    "attempt_count": 0
                  }
                ],
                "attempts": []
              }
            }
        """.trimIndent()
        val downgradeReport = manager.importBackup(
            parsed = manager.inspectJson(downgradeBackup).parsed,
            selectedSourceAccountId = "pc-acc",
        )
        assertTrue(downgradeReport.warnings.any { it.contains("拒绝被较早状态") })
        val stillSucceeded = store.listAllJobs(DEFAULT_ACCOUNT_ID).first { it.messageSlotId == SendGuard.DAILY_SLOT_ID }
        assertEquals(JobStatus.SUCCEEDED, stillSucceeded.status)
    }

    @Test
    fun exportAndImportRoundtripIsLosslessAndIdempotent() = runTest {
        val sourceStore = FakeBackupStore(
            account = AccountEntity(
                id = DEFAULT_ACCOUNT_ID,
                displayName = "迁移源",
                status = AccountStatus.HEALTHY,
                timezone = "Asia/Shanghai",
                sendWindowStart = "19:30",
                sendWindowEnd = "21:30",
                jitterMaxMinutes = 8,
                cooldownMinSec = 4,
                cooldownMaxSec = 9,
                selfUid = "secret_uid_never_exported",
                autoScanEnabled = false,
                autoScanIntervalHours = 48,
            ),
            templates = listOf(
                MessageTemplateEntity(
                    id = "tpl-pool",
                    accountId = DEFAULT_ACCOUNT_ID,
                    name = "随机池",
                    type = TemplateType.POOL,
                ),
                MessageTemplateEntity(
                    id = "tpl-sticker",
                    accountId = DEFAULT_ACCOUNT_ID,
                    name = "比心",
                    type = TemplateType.STICKER,
                    stickerName = "比心",
                    stickerLabel = "比心",
                ),
            ),
            poolItems = listOf(
                TemplatePoolItemEntity(
                    id = "pi-1",
                    templateId = "tpl-pool",
                    content = "打卡1",
                    weekdayMask = 0,
                    lastUsedAt = 1_000L,
                ),
            ),
            targets = listOf(
                ContractTargetEntity(
                    id = "t-friend",
                    accountId = DEFAULT_ACCOUNT_ID,
                    nicknameSnapshot = "好友甲",
                    platformUserId = "puid-1",
                    conversationType = "friend",
                    templateId = "tpl-pool",
                    streakDays = 14,
                ),
                ContractTargetEntity(
                    id = "t-group-del",
                    accountId = DEFAULT_ACCOUNT_ID,
                    nicknameSnapshot = "群聊乙",
                    platformConversationId = "conv-2",
                    conversationType = "group",
                    templateId = "tpl-sticker",
                    enabled = false,
                    deletedAt = 2_000L,
                ),
            ),
            jobs = listOf(
                SendJobEntity(
                    id = "j-daily",
                    accountId = DEFAULT_ACCOUNT_ID,
                    targetId = "t-friend",
                    templateId = "tpl-pool",
                    businessDate = "2026-11-22",
                    messageSlotId = SendGuard.DAILY_SLOT_ID,
                    status = JobStatus.SUCCEEDED,
                    attemptCount = 1,
                    summaryDetail = "发送成功详情",
                    confirmedAt = 3_000L,
                ),
                SendJobEntity(
                    id = "j-manual",
                    accountId = DEFAULT_ACCOUNT_ID,
                    targetId = "t-friend",
                    templateId = "tpl-sticker",
                    businessDate = "2026-11-22",
                    messageSlotId = "manual:1700000000123",
                    status = JobStatus.FAILED_TERMINAL,
                    attemptCount = 1,
                    failureClass = FailureClass.DOM_CHANGED,
                ),
            ),
            attempts = listOf(
                SendAttemptEntity(
                    id = "a-1",
                    jobId = "j-daily",
                    attemptNo = 1,
                    stage = "confirming",
                    status = AttemptStatus.SUCCEEDED,
                    inputMethodUsed = "keyboard",
                    errorCode = null,
                    startedAt = 2_500L,
                    finishedAt = 3_000L,
                ),
            ),
        )
        val sourceManager = BackupManager(store = sourceStore, clockMillis = { 10_000L })
        val exportedJson = sourceManager.exportJson(DEFAULT_ACCOUNT_ID)

        val destStore = FakeBackupStore(
            account = AccountEntity(
                id = DEFAULT_ACCOUNT_ID,
                status = AccountStatus.PENDING_LOGIN,
            ),
        )
        val destManager = BackupManager(store = destStore, clockMillis = { 20_000L })
        val inspection = destManager.inspectJson(exportedJson)

        val firstReport = destManager.importBackup(inspection.parsed, DEFAULT_ACCOUNT_ID)
        assertEquals(2, firstReport.templatesInserted)
        assertEquals(1, firstReport.poolItemsMerged)
        assertEquals(2, firstReport.targetsInserted)
        assertEquals(2, firstReport.jobsInserted)

        val restoredAccount = destStore.getAccount(DEFAULT_ACCOUNT_ID)!!
        assertEquals(AccountStatus.PENDING_LOGIN, restoredAccount.status)
        assertNull(restoredAccount.selfUid)
        assertEquals(8, restoredAccount.jitterMaxMinutes)
        assertEquals(4, restoredAccount.cooldownMinSec)
        assertEquals(9, restoredAccount.cooldownMaxSec)
        assertFalse(restoredAccount.autoScanEnabled)
        assertEquals(48, restoredAccount.autoScanIntervalHours)

        val restoredTargets = destStore.listAllTargetsIncludingDeleted(DEFAULT_ACCOUNT_ID).associateBy { it.id }
        assertEquals("friend", restoredTargets.getValue("t-friend").conversationType)
        assertEquals("group", restoredTargets.getValue("t-group-del").conversationType)
        assertEquals(2_000L, restoredTargets.getValue("t-group-del").deletedAt)

        val restoredJobs = destStore.listAllJobs(DEFAULT_ACCOUNT_ID).associateBy { it.id }
        assertEquals(SendGuard.DAILY_SLOT_ID, restoredJobs.getValue("j-daily").messageSlotId)
        assertEquals("发送成功详情", restoredJobs.getValue("j-daily").summaryDetail)
        assertEquals("manual:1700000000123", restoredJobs.getValue("j-manual").messageSlotId)

        val restoredAttempts = destStore.listAllAttemptsForAccount(DEFAULT_ACCOUNT_ID)
        assertEquals(1, restoredAttempts.size)
        assertEquals("keyboard", restoredAttempts[0].inputMethodUsed)

        // 第二次幂等导入：零新增，全走更新
        val secondReport = destManager.importBackup(inspection.parsed, DEFAULT_ACCOUNT_ID)
        assertEquals(0, secondReport.templatesInserted)
        assertEquals(2, secondReport.templatesUpdated)
        assertEquals(0, secondReport.targetsInserted)
        assertEquals(2, secondReport.targetsUpdated)
        assertEquals(0, secondReport.jobsInserted)
        assertEquals(2, secondReport.jobsUpdated)
    }

    private class FakeBackupStore(
        account: AccountEntity? = null,
        templates: List<MessageTemplateEntity> = emptyList(),
        poolItems: List<TemplatePoolItemEntity> = emptyList(),
        targets: List<ContractTargetEntity> = emptyList(),
        jobs: List<SendJobEntity> = emptyList(),
        attempts: List<SendAttemptEntity> = emptyList(),
    ) : BackupStore {
        private val accounts = linkedMapOf<String, AccountEntity>().apply {
            if (account != null) put(account.id, account)
        }
        private val templatesMap = linkedMapOf<String, MessageTemplateEntity>().apply {
            templates.forEach { put(it.id, it) }
        }
        private val poolItemsMap = linkedMapOf<String, TemplatePoolItemEntity>().apply {
            poolItems.forEach { put(it.id, it) }
        }
        private val targetsMap = linkedMapOf<String, ContractTargetEntity>().apply {
            targets.forEach { put(it.id, it) }
        }
        private val jobsMap = linkedMapOf<String, SendJobEntity>().apply {
            jobs.forEach { put(it.id, it) }
        }
        private val attemptsMap = linkedMapOf<String, SendAttemptEntity>().apply {
            attempts.forEach { put(it.id, it) }
        }

        var transactionCount: Int = 0
            private set

        override suspend fun <T> runInTransaction(block: suspend () -> T): T {
            transactionCount++
            return block()
        }

        override suspend fun getAccount(accountId: String): AccountEntity? = accounts[accountId]

        override suspend fun upsertAccount(account: AccountEntity) {
            accounts[account.id] = account
        }

        override suspend fun listTemplates(accountId: String): List<MessageTemplateEntity> =
            templatesMap.values.filter { it.accountId == accountId }

        override suspend fun upsertTemplate(template: MessageTemplateEntity) {
            val conflict = templatesMap.values.firstOrNull {
                it.id != template.id &&
                    it.accountId == template.accountId &&
                    it.name == template.name
            }
            require(conflict == null) {
                "UNIQUE constraint failed: message_templates(account_id, name): ${template.name}"
            }
            templatesMap[template.id] = template
        }

        override suspend fun listPoolItems(templateId: String): List<TemplatePoolItemEntity> =
            poolItemsMap.values.filter { it.templateId == templateId }

        override suspend fun upsertPoolItem(item: TemplatePoolItemEntity) {
            poolItemsMap[item.id] = item
        }

        override suspend fun listAllTargetsIncludingDeleted(accountId: String): List<ContractTargetEntity> =
            targetsMap.values.filter { it.accountId == accountId }

        override suspend fun upsertTarget(target: ContractTargetEntity) {
            val nickConflict = targetsMap.values.firstOrNull {
                it.id != target.id &&
                    it.accountId == target.accountId &&
                    it.nicknameSnapshot == target.nicknameSnapshot
            }
            require(nickConflict == null) {
                "UNIQUE constraint failed: contract_targets(account_id, nickname_snapshot)"
            }
            if (target.platformUserId != null) {
                val puidConflict = targetsMap.values.firstOrNull {
                    it.id != target.id &&
                        it.accountId == target.accountId &&
                        it.platformUserId == target.platformUserId
                }
                require(puidConflict == null) {
                    "UNIQUE constraint failed: contract_targets(account_id, platform_user_id)"
                }
            }
            if (target.platformConversationId != null) {
                val pcidConflict = targetsMap.values.firstOrNull {
                    it.id != target.id &&
                        it.accountId == target.accountId &&
                        it.platformConversationId == target.platformConversationId
                }
                require(pcidConflict == null) {
                    "UNIQUE constraint failed: contract_targets(account_id, platform_conversation_id)"
                }
            }
            targetsMap[target.id] = target
        }

        override suspend fun listAllJobs(accountId: String): List<SendJobEntity> =
            jobsMap.values.filter { it.accountId == accountId }

        override suspend fun upsertJob(job: SendJobEntity) {
            val slotConflict = jobsMap.values.firstOrNull {
                it.id != job.id &&
                    it.accountId == job.accountId &&
                    it.targetId == job.targetId &&
                    it.businessDate == job.businessDate &&
                    it.messageSlotId == job.messageSlotId
            }
            require(slotConflict == null) {
                "UNIQUE constraint failed: send_jobs(account_id, target_id, business_date, message_slot_id)"
            }
            jobsMap[job.id] = job
        }

        override suspend fun listAllAttemptsForAccount(accountId: String): List<SendAttemptEntity> {
            val accountJobIds = jobsMap.values.filter { it.accountId == accountId }.map { it.id }.toSet()
            return attemptsMap.values.filter { it.jobId in accountJobIds }
        }

        override suspend fun upsertAttempt(attempt: SendAttemptEntity) {
            val nkConflict = attemptsMap.values.firstOrNull {
                it.id != attempt.id &&
                    it.jobId == attempt.jobId &&
                    it.attemptNo == attempt.attemptNo
            }
            require(nkConflict == null) {
                "UNIQUE constraint failed: send_attempts(job_id, attempt_no)"
            }
            attemptsMap[attempt.id] = attempt
        }
    }
}
