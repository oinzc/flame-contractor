package io.github.oinzc.flamecontractor.ui.templates

import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.DEFAULT_STICKER_MORNING_TEMPLATE_ID
import io.github.oinzc.flamecontractor.data.db.DEFAULT_STICKER_SPARK_TEMPLATE_ID
import io.github.oinzc.flamecontractor.data.db.DEFAULT_STICKER_TEMPLATE_ID
import io.github.oinzc.flamecontractor.data.db.DEFAULT_TEXT_TEMPLATE_ID
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.TemplatePoolItemEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.domain.TemplatePoolPicker
import java.lang.reflect.Proxy
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TemplatesViewModelTest {

    private class FakeTemplatesRepository : TemplatesRepository {
        val templatesState = MutableStateFlow<List<MessageTemplateEntity>>(emptyList())
        val poolItemsState = MutableStateFlow<List<TemplatePoolItemEntity>>(emptyList())
        val targetsState = MutableStateFlow<List<ContractTargetEntity>>(emptyList())

        override fun observeTemplates(accountId: String): Flow<List<MessageTemplateEntity>> =
            templatesState.map { list ->
                list.filter { it.accountId == accountId }.sortedBy { it.createdAt }
            }

        override fun observePoolItems(templateId: String): Flow<List<TemplatePoolItemEntity>> =
            poolItemsState.map { list ->
                list.filter { it.templateId == templateId }.sortedWith(
                    compareBy<TemplatePoolItemEntity>({ it.createdAt }, { it.id }),
                )
            }

        override fun observeActiveTargets(accountId: String): Flow<List<ContractTargetEntity>> =
            targetsState.map { list ->
                list.filter { it.accountId == accountId && it.deletedAt == null }
            }

        override suspend fun listTemplates(accountId: String): List<MessageTemplateEntity> =
            templatesState.value.filter { it.accountId == accountId }

        override suspend fun getTemplateById(id: String): MessageTemplateEntity? =
            templatesState.value.firstOrNull { it.id == id }

        override suspend fun listPoolItems(templateId: String): List<TemplatePoolItemEntity> =
            poolItemsState.value.filter { it.templateId == templateId }
                .sortedWith(compareBy<TemplatePoolItemEntity>({ it.createdAt }, { it.id }))

        override suspend fun countActiveTargetsByTemplate(templateId: String): Int =
            targetsState.value.count { it.templateId == templateId && it.deletedAt == null }

        override suspend fun upsertTemplate(template: MessageTemplateEntity) {
            val existing = templatesState.value.any { it.id == template.id }
            templatesState.value = if (existing) {
                templatesState.value.map { if (it.id == template.id) template else it }
            } else {
                templatesState.value + template
            }
        }

        override suspend fun updateTemplate(template: MessageTemplateEntity) {
            templatesState.value = templatesState.value.map {
                if (it.id == template.id) template else it
            }
        }

        override suspend fun deleteTemplateById(id: String) {
            templatesState.value = templatesState.value.filterNot { it.id == id }
            poolItemsState.value = poolItemsState.value.filterNot { it.templateId == id }
            targetsState.value = targetsState.value.map {
                if (it.templateId == id) it.copy(templateId = null) else it
            }
        }

        override suspend fun upsertPoolItem(item: TemplatePoolItemEntity) {
            val existing = poolItemsState.value.any { it.id == item.id }
            poolItemsState.value = if (existing) {
                poolItemsState.value.map { if (it.id == item.id) item else it }
            } else {
                poolItemsState.value + item
            }
        }

        override suspend fun updatePoolItem(item: TemplatePoolItemEntity) {
            poolItemsState.value = poolItemsState.value.map {
                if (it.id == item.id) item else it
            }
        }

        override suspend fun deletePoolItemById(id: String) {
            poolItemsState.value = poolItemsState.value.filterNot { it.id == id }
        }
    }

    @Test
    fun poolItemWeekdayMask0AndBitwiseSaveCorrectly() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = FakeTemplatesRepository()
            val poolTemplate = MessageTemplateEntity(
                id = "tpl-pool-1",
                accountId = DEFAULT_ACCOUNT_ID,
                name = "早晚轮换池",
                type = TemplateType.POOL,
                createdAt = 1_000L,
            )
            repo.templatesState.value = listOf(poolTemplate)

            val vm = TemplatesViewModel(
                repository = repo,
                nowInstant = { Instant.parse("2026-11-21T12:00:00Z") },
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )
            advanceUntilIdle()

            // 1. 保存「每天 (weekdayMask = 0)」文案
            vm.updateInlinePoolInput(poolTemplate.id, "每天问候")
            vm.setInlinePoolEveryday(poolTemplate.id)
            vm.addInlinePoolItem(poolTemplate.id)
            advanceUntilIdle()

            val everydayItem = repo.poolItemsState.value.first { it.content == "每天问候" }
            assertEquals(0, everydayItem.weekdayMask)
            for (day in 0..6) {
                assertTrue(TemplatePoolPicker.isWeekdayMatched(everydayItem.weekdayMask, day))
            }

            // 2. 通过独立多选 周一(0)、周三(2)、周日(6) 保存精确位掩码
            vm.updateInlinePoolInput(poolTemplate.id, "特定星期问候")
            vm.toggleInlinePoolWeekday(poolTemplate.id, 0)
            vm.toggleInlinePoolWeekday(poolTemplate.id, 2)
            vm.toggleInlinePoolWeekday(poolTemplate.id, 6)
            vm.addInlinePoolItem(poolTemplate.id)
            advanceUntilIdle()

            val bitwiseItem = repo.poolItemsState.value.first { it.content == "特定星期问候" }
            val expectedMask = (1 shl 0) or (1 shl 2) or (1 shl 6)
            assertEquals(expectedMask, bitwiseItem.weekdayMask)
            assertTrue(TemplatePoolPicker.isWeekdayMatched(bitwiseItem.weekdayMask, 0))
            assertFalse(TemplatePoolPicker.isWeekdayMatched(bitwiseItem.weekdayMask, 1))
            assertTrue(TemplatePoolPicker.isWeekdayMatched(bitwiseItem.weekdayMask, 2))
            assertFalse(TemplatePoolPicker.isWeekdayMatched(bitwiseItem.weekdayMask, 3))
            assertTrue(TemplatePoolPicker.isWeekdayMatched(bitwiseItem.weekdayMask, 6))

            // 3. 补齐剩余 4 天使 7 天全选时，自动归一化保存为 weekdayMask = 0（每天）
            for (day in listOf(1, 3, 4, 5)) {
                vm.togglePoolItemWeekday(poolTemplate.id, bitwiseItem.id, day)
            }
            advanceUntilIdle()

            val allDaysNormalized = repo.poolItemsState.value.first { it.id == bitwiseItem.id }
            assertEquals(0, allDaysNormalized.weekdayMask)

            // 4. 从每天切换回单日 周五(4)，再恢复每天
            vm.togglePoolItemWeekday(poolTemplate.id, allDaysNormalized.id, 4)
            advanceUntilIdle()
            assertEquals(1 shl 4, repo.poolItemsState.value.first { it.id == bitwiseItem.id }.weekdayMask)

            vm.setPoolItemEveryday(poolTemplate.id, allDaysNormalized.id)
            advanceUntilIdle()
            assertEquals(0, repo.poolItemsState.value.first { it.id == bitwiseItem.id }.weekdayMask)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun previewingAndEditingPoolDoesNotMutateLastUsedAt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = FakeTemplatesRepository()
            val poolTemplate = MessageTemplateEntity(
                id = "tpl-pool-lru",
                accountId = DEFAULT_ACCOUNT_ID,
                name = "LRU 测试池",
                type = TemplateType.POOL,
                createdAt = 1_000L,
            )
            val p1 = TemplatePoolItemEntity(
                id = "item-1",
                templateId = poolTemplate.id,
                content = "旧句1",
                weekdayMask = 0,
                enabled = true,
                lastUsedAt = 10_000L,
                createdAt = 1_000L,
                updatedAt = 10_000L,
            )
            val p2 = TemplatePoolItemEntity(
                id = "item-2",
                templateId = poolTemplate.id,
                content = "旧句2",
                weekdayMask = 0,
                enabled = true,
                lastUsedAt = 20_000L,
                createdAt = 2_000L,
                updatedAt = 20_000L,
            )
            repo.templatesState.value = listOf(poolTemplate)
            repo.poolItemsState.value = listOf(p1, p2)

            val vm = TemplatesViewModel(
                repository = repo,
                nowInstant = { Instant.parse("2026-11-21T12:00:00Z") },
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )
            advanceUntilIdle()

            // 多次预览下一条发送候选项，绝不更改 lastUsedAt
            repeat(3) {
                val picked = vm.previewPoolNextPick(poolTemplate.id, weekdayIndex = 0)
                assertEquals("item-1", picked?.id)
            }
            assertEquals(10_000L, repo.poolItemsState.value.first { it.id == "item-1" }.lastUsedAt)
            assertEquals(20_000L, repo.poolItemsState.value.first { it.id == "item-2" }.lastUsedAt)

            // 直接编辑单条句子内容与星期掩码，绝不更改 lastUsedAt
            vm.updatePoolItem(
                templateId = poolTemplate.id,
                itemId = "item-1",
                content = "内联修改后的句子1",
                weekdayMask = 1 shl 0,
                enabled = true,
            )
            advanceUntilIdle()
            assertEquals(10_000L, repo.poolItemsState.value.first { it.id == "item-1" }.lastUsedAt)

            // 通过模板编辑弹窗整体保存文案池模板，同样严格保留各条既有 lastUsedAt
            vm.openEditTemplate(poolTemplate)
            advanceUntilIdle()
            vm.updateEditorPoolDraftContent(1, "弹窗修改后的句子2")
            vm.toggleEditorPoolDraftWeekday(1, 5)
            vm.saveTemplateEditor()
            advanceUntilIdle()

            assertNull(vm.uiState.value.editorState)
            val afterP1 = repo.poolItemsState.value.first { it.id == "item-1" }
            val afterP2 = repo.poolItemsState.value.first { it.id == "item-2" }
            assertEquals("内联修改后的句子1", afterP1.content)
            assertEquals(10_000L, afterP1.lastUsedAt)
            assertEquals("弹窗修改后的句子2", afterP2.content)
            assertEquals(1 shl 5, afterP2.weekdayMask)
            assertEquals(20_000L, afterP2.lastUsedAt)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun deletingReferencedTemplatePromptsWithCountAndCreatesDefaultStickerBixin() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = FakeTemplatesRepository()
            val tpl = MessageTemplateEntity(
                id = "tpl-in-use",
                name = "常用问候",
                type = TemplateType.TEXT,
                content = "你好",
            )
            repo.templatesState.value = listOf(tpl)
            repo.targetsState.value = listOf(
                ContractTargetEntity(id = "t1", nicknameSnapshot = "好友1", templateId = tpl.id),
                ContractTargetEntity(id = "t2", nicknameSnapshot = "好友2", templateId = tpl.id),
            )

            val vm = TemplatesViewModel(
                repository = repo,
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )
            advanceUntilIdle()

            vm.requestDeleteTemplate(tpl)
            advanceUntilIdle()
            val confirm = vm.uiState.value.deleteConfirmState
            assertNotNull(confirm)
            assertEquals(2, confirm?.activeReferenceCount)

            vm.confirmDeleteTemplate()
            advanceUntilIdle()
            assertTrue(repo.templatesState.value.isEmpty())

            // 新建原生表情模板默认填充「比心」并支持兜底索引
            vm.openCreateTemplate(TemplateType.STICKER)
            advanceUntilIdle()
            assertEquals("比心", vm.uiState.value.editorState?.stickerName)
            vm.updateEditorName("默认比心表情")
            vm.updateEditorStickerCategory("常用")
            vm.updateEditorStickerFallbackIndex("0")
            vm.saveTemplateEditor()
            advanceUntilIdle()

            val savedSticker = repo.templatesState.value.first()
            assertEquals(TemplateType.STICKER, savedSticker.type)
            assertEquals("比心", savedSticker.stickerName)
            assertEquals("常用", savedSticker.stickerCategory)
            assertEquals(0, savedSticker.stickerFallbackIndex)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun applyStickerPresetUpdatesFieldsWithoutOverwritingCustomName() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = FakeTemplatesRepository()
            val vm = TemplatesViewModel(
                repository = repo,
                externalScope = vmScope,
                ioDispatcher = dispatcher,
            )

            vm.openCreateTemplate(TemplateType.STICKER)
            advanceUntilIdle()
            assertEquals("比心", vm.uiState.value.editorState?.stickerName)
            assertEquals("", vm.uiState.value.editorState?.name)

            vm.applyStickerPreset("早上好")
            advanceUntilIdle()
            var editor = vm.uiState.value.editorState!!
            assertEquals("早上好", editor.name)
            assertEquals("早上好", editor.stickerName)
            assertEquals("早上好", editor.stickerLabel)

            vm.applyStickerPreset("续火花")
            advanceUntilIdle()
            editor = vm.uiState.value.editorState!!
            assertEquals("续火花", editor.name)
            assertEquals("续火花", editor.stickerName)
            assertEquals("续火花", editor.stickerLabel)

            // 用户已自定义模板名称和标签后，切换表情预设不会覆盖自定义内容
            vm.updateEditorName("早安专属")
            vm.updateEditorStickerLabel("自定义展示")
            vm.applyStickerPreset("比心")
            advanceUntilIdle()
            editor = vm.uiState.value.editorState!!
            assertEquals("早安专属", editor.name)
            assertEquals("比心", editor.stickerName)
            assertEquals("自定义展示", editor.stickerLabel)
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun databaseSeedAndMigrationIncludeMorningAndSparkStickers() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val recordingDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") {
                val sql = args?.getOrNull(0) as? String ?: ""
                val bindArgs = if (args != null && args.size >= 2 && args[1] is Array<*>) {
                    (args[1] as Array<*>).toList()
                } else {
                    emptyList()
                }
                calls += sql to bindArgs
                null
            } else {
                when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        } as SupportSQLiteDatabase

        FlameDatabase.seedInitialData(
            db = recordingDb,
            nowEpochMs = 1_000L,
            defaultTimezone = "Asia/Shanghai",
        )
        assertEquals(5, calls.size)
        assertTrue(calls[0].first.contains("INSERT OR IGNORE INTO accounts"))
        assertEquals(DEFAULT_ACCOUNT_ID, calls[0].second.firstOrNull())

        val seededStickers = calls.subList(1, 4)
        assertEquals(
            listOf(
                DEFAULT_STICKER_TEMPLATE_ID,
                DEFAULT_STICKER_MORNING_TEMPLATE_ID,
                DEFAULT_STICKER_SPARK_TEMPLATE_ID,
            ),
            seededStickers.map { it.second[0] },
        )
        assertEquals(listOf("比心", "早上好", "续火花"), seededStickers.map { it.second[2] })
        assertEquals(listOf("比心", "早上好", "续火花"), seededStickers.map { it.second[5] })
        assertTrue(seededStickers.all { it.second.size == 13 })
        assertTrue(seededStickers.all { it.first.contains("INSERT OR IGNORE INTO message_templates") })
        assertTrue(seededStickers.all { it.first.contains("WHERE EXISTS (SELECT 1 FROM accounts WHERE id = ?)") })
        assertEquals(DEFAULT_TEXT_TEMPLATE_ID, calls[4].second[0])

        calls.clear()
        FlameDatabase.MIGRATION_1_2.migrate(recordingDb)
        assertEquals(2, calls.size)
        assertEquals(
            listOf(DEFAULT_STICKER_MORNING_TEMPLATE_ID, DEFAULT_STICKER_SPARK_TEMPLATE_ID),
            calls.map { it.second[0] },
        )
        assertEquals(listOf("早上好", "续火花"), calls.map { it.second[5] })
        assertTrue(calls.all { it.second.size == 13 })
        assertTrue(calls.all { it.first.contains("INSERT OR IGNORE INTO message_templates") })
        assertTrue(calls.all { it.first.contains("WHERE EXISTS (SELECT 1 FROM accounts WHERE id = ?)") })
    }
}
