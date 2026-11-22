package io.github.oinzc.flamecontractor.ui.templates

import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.oinzc.flamecontractor.core.storage.StoredTemplateImage
import io.github.oinzc.flamecontractor.core.storage.TemplateImageStorage
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TemplatesViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeTemplateImageStorage(
        private val rootDir: File,
    ) : TemplateImageStorage {

        override fun resolveExistingFile(storedPath: String?): File? {
            val clean = storedPath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val f = File(clean)
            return if (f.isFile && f.length() > 0L) f else null
        }

        override suspend fun importFromStream(
            input: InputStream,
            mimeTypeHint: String?,
        ): StoredTemplateImage {
            val bytes = input.readBytes()
            if (bytes.isEmpty()) throw IllegalArgumentException("所选图片内容为空")
            rootDir.mkdirs()
            val ext = when (mimeTypeHint) {
                "image/png" -> "png"
                "image/gif" -> "gif"
                else -> "jpg"
            }
            val dest = File(rootDir, "${UUID.randomUUID()}.$ext")
            dest.writeBytes(bytes)
            return StoredTemplateImage(
                storedPath = dest.absolutePath,
                mimeType = mimeTypeHint ?: "image/jpeg",
                sizeBytes = dest.length(),
                widthPx = 640,
                heightPx = 480,
            )
        }

        override fun deleteIfUnreferenced(
            storedPath: String?,
            referencedPaths: Set<String>,
        ): Boolean {
            val file = resolveExistingFile(storedPath) ?: return false
            val targetCanonical = file.canonicalPath
            val stillReferenced = referencedPaths.any { ref ->
                resolveExistingFile(ref)?.canonicalPath == targetCanonical
            }
            if (stillReferenced) return false
            return file.delete()
        }

        override fun contentUriFor(file: File): Uri? = null
    }

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

        override suspend fun listAllReferencedImagePaths(): List<String> =
            templatesState.value.mapNotNull { it.imagePath?.takeIf { p -> p.isNotBlank() } }

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

    @Test
    fun imageTemplateCrudValidatesMissingImageAndCleansOrphansOnReplaceCancelAndDelete() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = FakeTemplatesRepository()
            val imageStore = FakeTemplateImageStorage(tempFolder.newFolder("tpl_images"))
            val vm = TemplatesViewModel(
                repository = repo,
                imageStore = imageStore,
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )
            advanceUntilIdle()

            // 1. 新建图片模板未选图时禁止保存并提示缺图告警
            vm.openCreateTemplate(TemplateType.IMAGE)
            vm.updateEditorName("早安图")
            vm.saveTemplateEditor()
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.editorState?.errorMessage)
            assertTrue(vm.uiState.value.editorState?.errorMessage!!.contains("缺少本地图片"))
            assertTrue(repo.templatesState.value.isEmpty())

            // 2. 选图 1 后再更换为图 2：未保存的图 1 应立即被清理，避免孤儿文件残留
            vm.importEditorImage("image/png") { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
            advanceUntilIdle()
            val stagedPath1 = vm.uiState.value.editorState?.imagePath!!
            assertTrue(File(stagedPath1).exists())

            vm.importEditorImage("image/jpeg") { ByteArrayInputStream(byteArrayOf(4, 5, 6, 7)) }
            advanceUntilIdle()
            val stagedPath2 = vm.uiState.value.editorState?.imagePath!!
            assertFalse(File(stagedPath1).exists())
            assertTrue(File(stagedPath2).exists())

            vm.saveTemplateEditor()
            advanceUntilIdle()
            assertNull(vm.uiState.value.editorState)
            val created = repo.templatesState.value.single()
            assertEquals(TemplateType.IMAGE, created.type)
            assertEquals(stagedPath2, created.imagePath)
            assertEquals(1, vm.uiState.value.imageTemplates.size)
            assertTrue(vm.uiState.value.imageTemplates.single().imageAvailable)

            // 3. 编辑模板选入新图 3 后取消编辑：图 3 被清理，原已保存图 2 保持完好
            vm.openEditTemplate(created)
            advanceUntilIdle()
            vm.importEditorImage("image/gif") { ByteArrayInputStream(byteArrayOf(8, 9, 10)) }
            advanceUntilIdle()
            val stagedPath3 = vm.uiState.value.editorState?.imagePath!!
            assertTrue(File(stagedPath3).exists())

            vm.dismissTemplateEditor()
            advanceUntilIdle()
            assertFalse(File(stagedPath3).exists())
            assertTrue(File(stagedPath2).exists())

            // 4. 编辑换图并保存（图 2 -> 图 4）：保存后旧图 2 成为无引用图并被删除
            vm.openEditTemplate(created)
            advanceUntilIdle()
            vm.importEditorImage("image/png") { ByteArrayInputStream(byteArrayOf(11, 12, 13)) }
            advanceUntilIdle()
            val path4 = vm.uiState.value.editorState?.imagePath!!
            vm.saveTemplateEditor()
            advanceUntilIdle()

            assertFalse(File(stagedPath2).exists())
            assertTrue(File(path4).exists())
            assertEquals(path4, repo.templatesState.value.single().imagePath)

            // 5. 若另一模板引用同一路径，删除模板时保留磁盘文件；无任何引用时才彻底删除
            val sharedRefTemplate = MessageTemplateEntity(
                id = "tpl-shared",
                accountId = "acct_2",
                name = "跨号复用图",
                type = TemplateType.IMAGE,
                imagePath = path4,
            )
            repo.upsertTemplate(sharedRefTemplate)
            advanceUntilIdle()

            val updatedCreated = repo.templatesState.value.first { it.id == created.id }
            vm.requestDeleteTemplate(updatedCreated)
            advanceUntilIdle()
            vm.confirmDeleteTemplate()
            advanceUntilIdle()
            assertTrue(File(path4).exists())

            // 移除剩余引用后，再删最后模板（或清理）验证无引用图可删除
            repo.deleteTemplateById(sharedRefTemplate.id)
            val soloFile = imageStore.importFromStream(ByteArrayInputStream(byteArrayOf(1, 2)), "image/png").storedPath
            val soloTpl = MessageTemplateEntity(
                id = "tpl-solo",
                accountId = DEFAULT_ACCOUNT_ID,
                name = "独立图片",
                type = TemplateType.IMAGE,
                imagePath = soloFile,
            )
            repo.upsertTemplate(soloTpl)
            advanceUntilIdle()

            vm.requestDeleteTemplate(soloTpl)
            advanceUntilIdle()
            vm.confirmDeleteTemplate()
            advanceUntilIdle()
            assertFalse(File(soloFile).exists())
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun missingLocalImageFileHighlightsErrorAndBlocksSave() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = FakeTemplatesRepository()
            val imageStore = FakeTemplateImageStorage(tempFolder.newFolder("missing_check"))
            val missingTpl = MessageTemplateEntity(
                id = "tpl-missing",
                accountId = DEFAULT_ACCOUNT_ID,
                name = "换机导入图片",
                type = TemplateType.IMAGE,
                imagePath = null,
            )
            repo.templatesState.value = listOf(missingTpl)

            val vm = TemplatesViewModel(
                repository = repo,
                imageStore = imageStore,
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )
            advanceUntilIdle()

            val card = vm.uiState.value.imageTemplates.single()
            assertFalse(card.imageAvailable)

            vm.openEditTemplate(missingTpl)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.editorState!!.imageAvailable)

            vm.saveTemplateEditor()
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.editorState?.errorMessage)
            assertTrue(vm.uiState.value.editorState?.errorMessage!!.contains("缺少本地图片"))
        } finally {
            vmScope.cancel()
        }
    }

    @Test
    fun templatesViewModelIsolatesTemplatesAndTargetsByAccount() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repo = FakeTemplatesRepository()
            val imageStore = FakeTemplateImageStorage(tempFolder.newFolder("iso_images"))
            repo.templatesState.value = listOf(
                MessageTemplateEntity(
                    id = "tpl-a1",
                    accountId = DEFAULT_ACCOUNT_ID,
                    name = "账号一模板",
                    type = TemplateType.TEXT,
                    content = "hi 1",
                ),
                MessageTemplateEntity(
                    id = "tpl-a2",
                    accountId = "acct_2",
                    name = "账号二模板",
                    type = TemplateType.TEXT,
                    content = "hi 2",
                ),
            )
            repo.targetsState.value = listOf(
                ContractTargetEntity(id = "t-1", accountId = DEFAULT_ACCOUNT_ID, nicknameSnapshot = "好友一", templateId = "tpl-a1"),
                ContractTargetEntity(id = "t-2", accountId = "acct_2", nicknameSnapshot = "好友二", templateId = "tpl-a2"),
            )

            val vmAcct2 = TemplatesViewModel(
                repository = repo,
                accountId = "acct_2",
                imageStore = imageStore,
                ioDispatcher = dispatcher,
                externalScope = vmScope,
            )
            advanceUntilIdle()

            assertEquals(listOf("账号二模板"), vmAcct2.uiState.value.textTemplates.map { it.template.name })
            assertEquals(listOf("好友二"), vmAcct2.uiState.value.sampleTargets.map { it.nicknameSnapshot })

            vmAcct2.openCreateTemplate(TemplateType.TEXT)
            vmAcct2.updateEditorName("账号二新模板")
            vmAcct2.updateEditorContent("hello acct2")
            vmAcct2.saveTemplateEditor()
            advanceUntilIdle()

            val newlyCreated = repo.templatesState.value.first { it.name == "账号二新模板" }
            assertEquals("acct_2", newlyCreated.accountId)
            assertEquals(2, vmAcct2.uiState.value.totalCount)
        } finally {
            vmScope.cancel()
        }
    }
}
