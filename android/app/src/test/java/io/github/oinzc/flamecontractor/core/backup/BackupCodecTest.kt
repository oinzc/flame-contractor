package io.github.oinzc.flamecontractor.core.backup

import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.data.db.TemplatePoolItemEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.execution.ExecJson
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    @Test
    fun encodeProducesBackendCompatibleSchemaAndStripsSecrets() {
        val exportedAtMs = Instant.parse("2026-11-22T12:30:00Z").toEpochMilli()
        val account = AccountEntity(
            id = DEFAULT_ACCOUNT_ID,
            displayName = "主力账号",
            status = AccountStatus.HEALTHY,
            enabled = true,
            timezone = "Asia/Shanghai",
            sendWindowStart = "20:00",
            sendWindowEnd = "22:00",
            sendTimeHhmm = "20:30",
            jitterMaxMinutes = 10,
            cooldownMinSec = 5,
            cooldownMaxSec = 15,
            authFailCount = 2,
            failureStreak = 4,
            selfUid = "MS4wLjAB_secret_self_uid",
            douyinNickname = "真实个人昵称",
            lastVerifiedAt = exportedAtMs - 10_000L,
            lastSuccessAt = exportedAtMs - 5_000L,
            createdAt = exportedAtMs - 100_000L,
            updatedAt = exportedAtMs - 1_000L,
            autoScanEnabled = false,
            autoScanIntervalHours = 12,
            lastAutoScanAttemptAt = exportedAtMs - 3_000L,
        )
        val templates = listOf(
            MessageTemplateEntity(
                id = "tpl-text",
                accountId = DEFAULT_ACCOUNT_ID,
                name = "日常文本",
                type = TemplateType.TEXT,
                content = "早安续火",
                createdAt = exportedAtMs - 50_000L,
                updatedAt = exportedAtMs - 50_000L,
            ),
            MessageTemplateEntity(
                id = "tpl-pool",
                accountId = DEFAULT_ACCOUNT_ID,
                name = "随机池",
                type = TemplateType.POOL,
                content = "",
                createdAt = exportedAtMs - 40_000L,
                updatedAt = exportedAtMs - 40_000L,
            ),
            MessageTemplateEntity(
                id = "tpl-sticker",
                accountId = DEFAULT_ACCOUNT_ID,
                name = "比心",
                type = TemplateType.STICKER,
                content = "",
                stickerName = "比心",
                stickerLabel = "比心",
                stickerCategory = "默认",
                stickerFallbackIndex = 1,
                createdAt = exportedAtMs - 30_000L,
                updatedAt = exportedAtMs - 30_000L,
            ),
        )
        val poolItems = listOf(
            TemplatePoolItemEntity(
                id = "pool-1",
                templateId = "tpl-pool",
                content = "打卡 {{target_name}}",
                weekdayMask = 0b0011111,
                enabled = true,
                lastUsedAt = exportedAtMs - 20_000L,
                createdAt = exportedAtMs - 35_000L,
                updatedAt = exportedAtMs - 20_000L,
            ),
        )
        val targets = listOf(
            ContractTargetEntity(
                id = "tgt-active",
                accountId = DEFAULT_ACCOUNT_ID,
                nicknameSnapshot = "好友A",
                platformUserId = "puid-a",
                platformHandle = "handle-a",
                conversationType = "friend",
                templateId = "tpl-text",
                enabled = true,
                sparkDays = 18,
                sparkKind = "fire",
                sparkLabel = "18天",
                sparkScannedAt = exportedAtMs - 6_000L,
                streakDays = 19,
                lastSentAt = exportedAtMs - 5_000L,
                lastConfirmedAt = exportedAtMs - 4_500L,
                deletedAt = null,
                createdAt = exportedAtMs - 25_000L,
                updatedAt = exportedAtMs - 4_500L,
            ),
            ContractTargetEntity(
                id = "tgt-deleted",
                accountId = DEFAULT_ACCOUNT_ID,
                nicknameSnapshot = "已删群聊",
                platformConversationId = "conv-deleted",
                conversationType = "group",
                templateId = "tpl-sticker",
                enabled = false,
                streakDays = 3,
                deletedAt = exportedAtMs - 2_000L,
                createdAt = exportedAtMs - 20_000L,
                updatedAt = exportedAtMs - 2_000L,
            ),
        )
        val jobs = listOf(
            SendJobEntity(
                id = "job-daily",
                accountId = DEFAULT_ACCOUNT_ID,
                targetId = "tgt-active",
                templateId = "tpl-text",
                businessDate = "2026-11-22",
                messageSlotId = SendGuard.DAILY_SLOT_ID,
                status = JobStatus.SUCCEEDED,
                scheduledAt = exportedAtMs - 8_000L,
                attemptCount = 1,
                summaryDetail = "发送已确认",
                confirmedAt = exportedAtMs - 4_500L,
                createdAt = exportedAtMs - 9_000L,
                updatedAt = exportedAtMs - 4_500L,
            ),
        )
        val attempts = listOf(
            SendAttemptEntity(
                id = "att-1",
                jobId = "job-daily",
                attemptNo = 1,
                stage = "confirming",
                status = AttemptStatus.SUCCEEDED,
                inputMethodUsed = "execCommand",
                errorCode = null,
                startedAt = exportedAtMs - 5_200L,
                finishedAt = exportedAtMs - 4_500L,
            ),
        )

        val rawJson = BackupCodec.encode(
            snapshot = BackupExportSnapshot(
                account = account,
                templates = templates,
                poolItems = poolItems,
                targets = targets,
                jobs = jobs,
                attempts = attempts,
            ),
            exportedAtEpochMs = exportedAtMs,
        )

        // 严禁包含任何鉴权/会话/设备隐私字段
        assertFalse(rawJson.contains("selfUid"))
        assertFalse(rawJson.contains("self_uid"))
        assertFalse(rawJson.contains("MS4wLjAB_secret_self_uid"))
        assertFalse(rawJson.contains("authFailCount"))
        assertFalse(rawJson.contains("auth_fail_count"))
        assertFalse(rawJson.contains("failureStreak"))
        assertFalse(rawJson.contains("failure_streak"))
        assertFalse(rawJson.contains("douyinNickname"))
        assertFalse(rawJson.contains("douyin_nickname"))
        assertFalse(rawJson.contains("真实个人昵称"))

        val root = ExecJson.parseObject(rawJson)
        assertEquals("flame-contractor", root["app"])
        assertEquals(1L, root["schemaVersion"])
        assertEquals("2026-11-22T12:30:00Z", root["exportedAt"])

        @Suppress("UNCHECKED_CAST")
        val data = root["data"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val exportedAccounts = data["accounts"] as List<Map<String, Any?>>
        assertEquals(1, exportedAccounts.size)
        assertEquals("pending_login", exportedAccounts[0]["status"])
        assertEquals("主力账号", exportedAccounts[0]["display_name"])

        @Suppress("UNCHECKED_CAST")
        val exportedSchedules = data["schedules"] as List<Any?>
        assertTrue(exportedSchedules.isEmpty())

        @Suppress("UNCHECKED_CAST")
        val exportedTargets = data["targets"] as List<Map<String, Any?>>
        assertEquals(2, exportedTargets.size)
        assertNotNull(exportedTargets.first { it["id"] == "tgt-deleted" }["deleted_at"])

        @Suppress("UNCHECKED_CAST")
        val ext = root["androidExtensions"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val extAccount = ext["account"] as Map<String, Any?>
        assertEquals(10L, extAccount["jitterMaxMinutes"])
        assertEquals(5L, extAccount["cooldownMinSec"])
        assertEquals(15L, extAccount["cooldownMaxSec"])
        assertEquals(false, extAccount["autoScanEnabled"])
        assertEquals(12L, extAccount["autoScanIntervalHours"])

        @Suppress("UNCHECKED_CAST")
        val summaryDetails = ext["jobSummaryDetails"] as Map<String, Any?>
        assertEquals("发送已确认", summaryDetails["job-daily"])

        @Suppress("UNCHECKED_CAST")
        val attemptMeta = ext["attemptMeta"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val att1Meta = attemptMeta["att-1"] as Map<String, Any?>
        assertEquals("execCommand", att1Meta["inputMethodUsed"])
    }

    @Test
    fun encodeMapsConversationTypeAndMessageSlotIdSafely() {
        assertEquals("user", BackupCodec.mapConversationTypeToBackup("friend"))
        assertEquals("group", BackupCodec.mapConversationTypeToBackup("group"))
        assertNull(BackupCodec.mapConversationTypeToBackup("weird"))
        assertNull(BackupCodec.mapConversationTypeToBackup(null))

        assertEquals("friend", BackupCodec.mapConversationTypeFromBackup("user"))
        assertEquals("friend", BackupCodec.mapConversationTypeFromBackup("friend"))
        assertEquals("group", BackupCodec.mapConversationTypeFromBackup("group"))
        assertNull(BackupCodec.mapConversationTypeFromBackup("unknown"))
        assertNull(BackupCodec.mapConversationTypeFromBackup(null))

        assertEquals("tpl-1:v1", BackupCodec.mapSlotIdToBackup("daily", "tpl-1"))
        assertEquals("raw:daily", BackupCodec.mapSlotIdToBackup("daily", null))
        assertEquals(
            "manual:1700000000000",
            BackupCodec.mapSlotIdToBackup("manual:1700000000000", "tpl-1"),
        )
        val overlongSlot = "manual:" + "a".repeat(80)
        val hashedSlot = BackupCodec.mapSlotIdToBackup(overlongSlot, "tpl-1")
        assertTrue(hashedSlot.startsWith("raw:m:"))
        assertTrue(hashedSlot.length <= 64)

        assertEquals("daily", BackupCodec.resolveSlotIdFromBackup("tpl-1:v1", "j1", null))
        assertEquals("daily", BackupCodec.resolveSlotIdFromBackup("tpl-1:v3", "j2", null))
        assertEquals("daily", BackupCodec.resolveSlotIdFromBackup("daily", "j3", null))
        assertEquals(
            "manual:import:j4",
            BackupCodec.resolveSlotIdFromBackup("tpl-1:v1:force:1700000000", "j4", null),
        )
        assertEquals(
            "manual:import:j5",
            BackupCodec.resolveSlotIdFromBackup("raw:1700000000", "j5", null),
        )
        assertEquals(
            "manual:1700000000000",
            BackupCodec.resolveSlotIdFromBackup(
                "raw:m:1234567890ab",
                "j6",
                "manual:1700000000000",
            ),
        )

        val now = 1_795_348_800_000L
        val rawJson = BackupCodec.encode(
            snapshot = BackupExportSnapshot(
                account = AccountEntity(id = DEFAULT_ACCOUNT_ID),
                templates = listOf(
                    MessageTemplateEntity(id = "tpl-1", name = "T1", content = "hi"),
                ),
                poolItems = emptyList(),
                targets = listOf(
                    ContractTargetEntity(
                        id = "tgt-f",
                        nicknameSnapshot = "好友",
                        conversationType = "friend",
                        templateId = "tpl-1",
                    ),
                    ContractTargetEntity(
                        id = "tgt-g",
                        nicknameSnapshot = "群",
                        conversationType = "group",
                        templateId = "tpl-1",
                    ),
                ),
                jobs = listOf(
                    SendJobEntity(
                        id = "job-d",
                        targetId = "tgt-f",
                        templateId = "tpl-1",
                        businessDate = "2026-11-22",
                        messageSlotId = "daily",
                        status = JobStatus.SUCCEEDED,
                    ),
                    SendJobEntity(
                        id = "job-m",
                        targetId = "tgt-f",
                        templateId = "tpl-1",
                        businessDate = "2026-11-22",
                        messageSlotId = "manual:1700000000000",
                        status = JobStatus.SUCCEEDED,
                    ),
                ),
                attempts = emptyList(),
            ),
            exportedAtEpochMs = now,
        )

        val inspection = BackupCodec.inspect(rawJson)
        assertEquals(2, inspection.targetCount)
        assertEquals("friend", inspection.parsed.targets.first { it.sourceId == "tgt-f" }.conversationType)
        assertEquals("group", inspection.parsed.targets.first { it.sourceId == "tgt-g" }.conversationType)
        assertEquals("manual:1700000000000", inspection.parsed.jobOriginalSlots["job-m"])
        val parsedRoot = ExecJson.parseObject(rawJson)
        @Suppress("UNCHECKED_CAST")
        val data = parsedRoot["data"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val dataTargets = data["targets"] as List<Map<String, Any?>>
        assertEquals("user", dataTargets.first { it["id"] == "tgt-f" }["conversation_type"])
        assertEquals("group", dataTargets.first { it["id"] == "tgt-g" }["conversation_type"])
        @Suppress("UNCHECKED_CAST")
        val dataJobs = data["jobs"] as List<Map<String, Any?>>
        assertEquals("tpl-1:v1", dataJobs.first { it["id"] == "job-d" }["message_slot_id"])
    }

    @Test
    fun inspectSupportsCamelAndSnakeCaseAndNaiveUtcTimestamps() {
        val expectedMillis = Instant.parse("2026-11-22T12:00:00Z").toEpochMilli()
        assertEquals(expectedMillis, BackupCodec.parseTimestampOrNull("2026-11-22T12:00:00"))
        assertEquals(expectedMillis, BackupCodec.parseTimestampOrNull("2026-11-22T12:00:00Z"))
        assertEquals(expectedMillis, BackupCodec.parseTimestampOrNull("2026-11-22T20:00:00+08:00"))
        assertNull(BackupCodec.parseTimestampOrNull(null))
        assertNull(BackupCodec.parseTimestampOrNull("   "))
        assertNull(BackupCodec.parseTimestampOrNull("invalid-ts"))

        val camelJson = """
            {
              "app": "flame-contractor",
              "schemaVersion": 1,
              "exportedAt": "2026-11-22T12:00:00",
              "data": {
                "accounts": [
                  {
                    "id": "acc-1",
                    "display_name": "桌面账号",
                    "status": "healthy",
                    "timezone": "Asia/Shanghai",
                    "send_window_start": "20:00",
                    "send_window_end": "22:00",
                    "enabled": true
                  }
                ],
                "templates": [
                  {
                    "id": "tpl-pool",
                    "name": "文案池",
                    "type": "pool",
                    "content": "",
                    "enabled": true,
                    "created_at": "2026-11-22T20:00:00+08:00",
                    "updated_at": "2026-11-22T12:00:00Z"
                  },
                  {
                    "id": "tpl-img",
                    "name": "早安图",
                    "type": "image",
                    "image_path": "/tmp/morning.png",
                    "enabled": true
                  }
                ],
                "poolItems": [
                  {
                    "id": "pi-1",
                    "template_id": "tpl-pool",
                    "content": "每日打卡",
                    "weekday_mask": 0,
                    "enabled": true,
                    "last_used_at": "2026-11-22T12:00:00"
                  }
                ],
                "targets": [],
                "schedules": [],
                "jobs": [],
                "attempts": []
              }
            }
        """.trimIndent()

        val camelInspection = BackupCodec.inspect(camelJson)
        assertEquals(expectedMillis, camelInspection.parsed.exportedAtEpochMs)
        assertEquals(2, camelInspection.templateCount)
        assertEquals(1, camelInspection.missingImageTemplateCount)
        assertEquals(1, camelInspection.parsed.poolItems.size)
        assertEquals(expectedMillis, camelInspection.parsed.poolItems[0].lastUsedAtEpochMs)
        val parsedImg = camelInspection.parsed.templates.first { it.sourceId == "tpl-img" }
        assertTrue(parsedImg.isImageTemplate)
        assertEquals(TemplateType.IMAGE, parsedImg.type)

        val snakeJson = """
            {
              "app": "flame-contractor",
              "schema_version": 1,
              "exported_at": "2026-11-22T20:00:00+08:00",
              "data": {
                "accounts": [
                  {
                    "id": "acc-1",
                    "display_name": "桌面账号",
                    "status": "pending_login",
                    "timezone": "Asia/Shanghai",
                    "send_window_start": "20:00",
                    "send_window_end": "22:00",
                    "enabled": true
                  }
                ],
                "templates": [
                  {
                    "id": "tpl-text",
                    "name": "文本",
                    "type": "text",
                    "content": "hi"
                  }
                ],
                "pool_items": [
                  {
                    "id": "pi-2",
                    "template_id": "tpl-text",
                    "content": "ignored"
                  }
                ],
                "targets": [],
                "jobs": [],
                "attempts": []
              }
            }
        """.trimIndent()

        val snakeInspection = BackupCodec.inspect(snakeJson)
        assertEquals(expectedMillis, snakeInspection.parsed.exportedAtEpochMs)
        assertEquals(1, snakeInspection.templateCount)
        assertEquals(0, snakeInspection.missingImageTemplateCount)
        assertEquals(1, snakeInspection.parsed.poolItems.size)
    }

    @Test
    fun inspectRejectsOversizedOrMalformedOrUnsupportedSchema() {
        val oversized = "a".repeat(BackupCodec.MAX_BACKUP_BYTES + 1)
        val sizeEx = assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.inspect(oversized)
        }
        assertTrue(sizeEx.message.orEmpty().contains("20 MB"))

        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.inspect("{not valid json")
        }

        val wrongApp = """{"app":"other-app","schemaVersion":1,"data":{"accounts":[{"id":"1","display_name":"A"}]}}"""
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.inspect(wrongApp)
        }

        val wrongVersion = """{"app":"flame-contractor","schemaVersion":2,"data":{"accounts":[{"id":"1","display_name":"A"}]}}"""
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.inspect(wrongVersion)
        }

        val emptyAccounts = """{"app":"flame-contractor","schemaVersion":1,"data":{"accounts":[]}}"""
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.inspect(emptyAccounts)
        }
    }

    @Test
    fun resolveRawDailyRestoresDailySlotWithoutAndroidExtensions() {
        assertEquals(
            SendGuard.DAILY_SLOT_ID,
            BackupCodec.resolveSlotIdFromBackup("raw:daily", "job-raw-1", null),
        )
        assertEquals(
            SendGuard.DAILY_SLOT_ID,
            BackupCodec.resolveSlotIdFromBackup("  raw:daily  ", "job-raw-2", "   "),
        )
        assertEquals(
            "manual:import:job-raw-force",
            BackupCodec.resolveSlotIdFromBackup("raw:daily:force:1700", "job-raw-force", null),
        )

        val now = 1_795_348_800_000L
        val rawJson = BackupCodec.encode(
            snapshot = BackupExportSnapshot(
                account = AccountEntity(id = DEFAULT_ACCOUNT_ID),
                templates = emptyList(),
                poolItems = emptyList(),
                targets = listOf(
                    ContractTargetEntity(
                        id = "tgt-1",
                        nicknameSnapshot = "好友甲",
                        conversationType = "friend",
                    ),
                ),
                jobs = listOf(
                    SendJobEntity(
                        id = "job-no-tpl",
                        targetId = "tgt-1",
                        templateId = null,
                        businessDate = "2026-11-22",
                        messageSlotId = SendGuard.DAILY_SLOT_ID,
                        status = JobStatus.SUCCEEDED,
                    ),
                ),
                attempts = emptyList(),
            ),
            exportedAtEpochMs = now,
        )
        val rootWithoutExt = ExecJson.parseObject(rawJson).toMutableMap().apply {
            remove("androidExtensions")
        }
        val strippedJson = ExecJson.encode(rootWithoutExt)
        val inspection = BackupCodec.inspect(strippedJson)
        val parsedJob = inspection.parsed.jobs.single()
        assertEquals("raw:daily", parsedJob.messageSlotId)
        assertEquals(
            SendGuard.DAILY_SLOT_ID,
            BackupCodec.resolveSlotIdFromBackup(
                backupSlot = parsedJob.messageSlotId,
                jobId = parsedJob.sourceId,
                originalSlotFromExt = inspection.parsed.jobOriginalSlots[parsedJob.sourceId],
            ),
        )
    }

    @Test
    fun inspectFallsBackToSnakeCaseWhenCamelCaseAliasIsNull() {
        val expectedMillis = Instant.parse("2026-11-22T12:00:00Z").toEpochMilli()
        val mixedJson = """
            {
              "app": "flame-contractor",
              "schemaVersion": null,
              "schema_version": 1,
              "exportedAt": null,
              "exported_at": "2026-11-22T12:00:00Z",
              "androidExtensions": null,
              "android_extensions": {
                "account": {
                  "jitterMaxMinutes": null,
                  "jitter_max_minutes": 7,
                  "autoScanEnabled": null,
                  "auto_scan_enabled": false
                },
                "jobSummaryDetails": null,
                "job_summary_details": {
                  "job-1": "扩展摘要"
                }
              },
              "data": {
                "accounts": [
                  {
                    "id": "acc-1",
                    "display_name": "桌面账号",
                    "status": "pending_login",
                    "timezone": "Asia/Shanghai",
                    "send_window_start": "20:00",
                    "send_window_end": "22:00"
                  }
                ],
                "templates": [
                  {
                    "id": "tpl-pool",
                    "name": "随机池",
                    "type": "pool",
                    "content": ""
                  }
                ],
                "poolItems": null,
                "pool_items": [
                  {
                    "id": "pi-1",
                    "template_id": "tpl-pool",
                    "content": "文案1"
                  }
                ],
                "targets": [],
                "jobs": [],
                "attempts": []
              }
            }
        """.trimIndent()

        val inspection = BackupCodec.inspect(mixedJson)
        assertEquals(1, inspection.parsed.schemaVersion)
        assertEquals(expectedMillis, inspection.parsed.exportedAtEpochMs)
        assertEquals(1, inspection.parsed.poolItems.size)
        assertEquals("pi-1", inspection.parsed.poolItems.single().sourceId)
        assertEquals(7, inspection.parsed.androidAccountExt?.jitterMaxMinutes)
        assertEquals(false, inspection.parsed.androidAccountExt?.autoScanEnabled)
        assertEquals("扩展摘要", inspection.parsed.jobSummaryDetails["job-1"])
    }

    @Test
    fun inspectOversizedErrorReportsDecimalMbAndExactByteCount() {
        val oneByteOver = "a".repeat(BackupCodec.MAX_BACKUP_BYTES + 1)
        val oneByteEx = assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.inspect(oneByteOver)
        }
        val oneByteMsg = oneByteEx.message.orEmpty()
        assertTrue(oneByteMsg.contains("20 MB"))
        assertFalse(oneByteMsg.contains("实际 20 MB"))
        assertTrue(oneByteMsg.contains("20.00 MB"))
        assertTrue(oneByteMsg.contains((BackupCodec.MAX_BACKUP_BYTES + 1).toString()))

        val twentyOneHalfMb = "a".repeat(21 * 1024 * 1024 + 512 * 1024)
        val halfMbEx = assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.inspect(twentyOneHalfMb)
        }
        assertTrue(halfMbEx.message.orEmpty().contains("21.50 MB"))
    }

    @Test
    fun encodeAndInspectImageTemplateRoundtripOmitsImagePathKey() {
        val now = Instant.parse("2026-11-23T08:00:00Z").toEpochMilli()
        val secretLocalImagePath = "/data/user/0/io.github.oinzc.flamecontractor/files/template_images/secret-photo-uuid.png"
        val rawJson = BackupCodec.encode(
            snapshot = BackupExportSnapshot(
                account = AccountEntity(id = DEFAULT_ACCOUNT_ID),
                templates = listOf(
                    MessageTemplateEntity(
                        id = "tpl-image-1",
                        accountId = DEFAULT_ACCOUNT_ID,
                        name = "每日图片",
                        type = TemplateType.IMAGE,
                        content = "应被清空的文本",
                        imagePath = secretLocalImagePath,
                        enabled = true,
                        createdAt = now - 1_000L,
                        updatedAt = now,
                    ),
                    MessageTemplateEntity(
                        id = "tpl-text-1",
                        accountId = DEFAULT_ACCOUNT_ID,
                        name = "文本",
                        type = TemplateType.TEXT,
                        content = "早安",
                        createdAt = now - 1_000L,
                        updatedAt = now,
                    ),
                ),
                poolItems = emptyList(),
                targets = listOf(
                    ContractTargetEntity(
                        id = "tgt-img-1",
                        accountId = DEFAULT_ACCOUNT_ID,
                        nicknameSnapshot = "好友图",
                        platformUserId = "puid-img-1",
                        conversationType = "friend",
                        templateId = "tpl-image-1",
                    ),
                ),
                jobs = emptyList(),
                attempts = emptyList(),
            ),
            exportedAtEpochMs = now,
        )

        // 严格验证 JSON 文本与模板对象均不含 "image_path" 键（不可写入 null，以免覆盖桌面端路径）与本地路径
        assertFalse(rawJson.contains("image_path"))
        assertFalse(rawJson.contains("imagePath"))
        assertFalse(rawJson.contains("secret-photo-uuid.png"))
        assertFalse(rawJson.contains("template_images"))

        val root = ExecJson.parseObject(rawJson)
        @Suppress("UNCHECKED_CAST")
        val data = root["data"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val exportedTemplates = data["templates"] as List<Map<String, Any?>>
        assertEquals(2, exportedTemplates.size)
        val exportedImageTpl = exportedTemplates.first { it["id"] == "tpl-image-1" }
        assertEquals("image", exportedImageTpl["type"])
        assertEquals("", exportedImageTpl["content"])
        assertFalse(exportedImageTpl.containsKey("image_path"))
        assertFalse(exportedTemplates.first { it["id"] == "tpl-text-1" }.containsKey("image_path"))

        val inspection = BackupCodec.inspect(rawJson)
        assertEquals(2, inspection.templateCount)
        assertEquals(1, inspection.missingImageTemplateCount)
        val parsedImageTpl = inspection.parsed.templates.first { it.sourceId == "tpl-image-1" }
        assertEquals(TemplateType.IMAGE, parsedImageTpl.type)
        assertTrue(parsedImageTpl.isImageTemplate)
        assertEquals("", parsedImageTpl.content)
        assertEquals("tpl-image-1", inspection.parsed.targets.single().templateId)
    }
}
