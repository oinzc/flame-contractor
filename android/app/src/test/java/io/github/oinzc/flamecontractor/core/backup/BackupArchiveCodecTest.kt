package io.github.oinzc.flamecontractor.core.backup

import android.net.Uri
import io.github.oinzc.flamecontractor.core.storage.StoredTemplateImage
import io.github.oinzc.flamecontractor.core.storage.TemplateImageStorage
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.MessageTemplateEntity
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.data.db.TemplatePoolItemEntity
import io.github.oinzc.flamecontractor.data.db.TemplateType
import io.github.oinzc.flamecontractor.execution.ExecJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupArchiveCodecTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val samplePng = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    )
    private val sampleJpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
    )
    private val sampleGif = "GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000;".encodeToByteArray()
    private val sampleWebp = byteArrayOf(
        'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
        0x10, 0x00, 0x00, 0x00,
        'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
        'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte(),
    )

    @Test
    fun detectImageFormatIdentifiesJpegPngGifWebpAndRejectsNonImages() {
        assertEquals(DetectedImageFormat.PNG, BackupArchiveCodec.detectImageFormat(samplePng))
        assertEquals(DetectedImageFormat.JPEG, BackupArchiveCodec.detectImageFormat(sampleJpeg))
        assertEquals(DetectedImageFormat.GIF, BackupArchiveCodec.detectImageFormat(sampleGif))
        assertEquals(DetectedImageFormat.GIF, BackupArchiveCodec.detectImageFormat("GIF87a1234".encodeToByteArray()))
        assertEquals(DetectedImageFormat.WEBP, BackupArchiveCodec.detectImageFormat(sampleWebp))

        assertNull(BackupArchiveCodec.detectImageFormat(ByteArray(0)))
        assertNull(BackupArchiveCodec.detectImageFormat("<html>not image</html>".encodeToByteArray()))
        assertNull(BackupArchiveCodec.detectImageFormat("<svg></svg>".encodeToByteArray()))
        assertNull(BackupArchiveCodec.detectImageFormat("RIFF0000AVI ".encodeToByteArray()))
        assertNull(BackupArchiveCodec.detectImageFormat("GIF88a1234".encodeToByteArray()))
    }

    @Test
    fun exportZipProducesInteroperableManifestAndSkipsMissingOrInvalidImages() {
        val dir = tempFolder.newFolder("export_images")
        val pngFile = File(dir, "ok.png").apply { writeBytes(samplePng) }
        val jpgFile = File(dir, "ok.jpg").apply { writeBytes(sampleJpeg) }
        val fakeImageFile = File(dir, "fake.png").apply { writeText("not-a-real-image") }

        val templates = listOf(
            MessageTemplateEntity(
                id = "tpl-png-1",
                accountId = DEFAULT_ACCOUNT_ID,
                name = "早安PNG",
                type = TemplateType.IMAGE,
                imagePath = pngFile.absolutePath,
            ),
            MessageTemplateEntity(
                id = "tpl-jpg-2",
                accountId = DEFAULT_ACCOUNT_ID,
                name = "晚安JPG",
                type = TemplateType.IMAGE,
                imagePath = jpgFile.absolutePath,
            ),
            MessageTemplateEntity(
                id = "tpl-missing-3",
                accountId = DEFAULT_ACCOUNT_ID,
                name = "缺图模板",
                type = TemplateType.IMAGE,
                imagePath = File(dir, "deleted.png").absolutePath,
            ),
            MessageTemplateEntity(
                id = "tpl-fake-4",
                accountId = DEFAULT_ACCOUNT_ID,
                name = "伪造非图文件",
                type = TemplateType.IMAGE,
                imagePath = fakeImageFile.absolutePath,
            ),
            MessageTemplateEntity(
                id = "tpl-text-5",
                accountId = DEFAULT_ACCOUNT_ID,
                name = "纯文本",
                type = TemplateType.TEXT,
                content = "续火",
            ),
        )

        val jsonContent = """{"app":"flame-contractor","schemaVersion":1,"data":{"accounts":[{"id":"default","display_name":"主号"}],"templates":[]}}"""
        val out = ByteArrayOutputStream()
        val exportResult = BackupArchiveCodec.exportZip(
            outputStream = out,
            jsonContent = jsonContent,
            templates = templates,
            resolveImageFile = { path -> path?.let(::File)?.takeIf { it.isFile && it.length() > 0L } },
        )

        assertEquals(2, exportResult.bundledImageCount)
        assertEquals(2, exportResult.skippedImageCount)

        // 直接检查标准 PKZIP 条目与 manifest.json 结构
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entries[entry.name] = zis.readBytes()
                zis.closeEntry()
            }
        }

        assertEquals(
            setOf(
                "backup.json",
                "manifest.json",
                "template_images/tpl-png-1.png",
                "template_images/tpl-jpg-2.jpg",
            ),
            entries.keys,
        )
        assertEquals(jsonContent, entries.getValue("backup.json").toString(Charsets.UTF_8))
        assertArrayEquals(samplePng, entries.getValue("template_images/tpl-png-1.png"))
        assertArrayEquals(sampleJpeg, entries.getValue("template_images/tpl-jpg-2.jpg"))

        val manifest = ExecJson.parseObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        assertEquals(1, (manifest["format_version"] as Number).toInt())
        assertEquals("flame-contractor", manifest["app"])
        @Suppress("UNCHECKED_CAST")
        val manifestImages = manifest["images"] as Map<String, Any?>
        assertEquals(
            mapOf(
                "tpl-png-1" to "template_images/tpl-png-1.png",
                "tpl-jpg-2" to "template_images/tpl-jpg-2.jpg",
            ),
            manifestImages,
        )
    }

    @Test
    fun readArchiveOrJsonAutoDetectsZipVsPlainJsonAndSupportsPythonLayout() {
        val rawJson = """{"app":"flame-contractor","schemaVersion":1,"data":{"accounts":[{"id":"a1","display_name":"主号"}]}}"""

        // 1. 纯 JSON 自动识别
        val jsonPayload = BackupArchiveCodec.readArchiveOrJson(ByteArrayInputStream(rawJson.toByteArray(Charsets.UTF_8)))
        assertFalse(jsonPayload.isZipArchive)
        assertEquals(rawJson, jsonPayload.rawJson)
        assertTrue(jsonPayload.bundledImagesByTemplateId.isEmpty())

        // 2. 模拟 Python 后端导出（含 template_images/ 目录条目 + 4 种图片格式 + manifest.json）
        val zipBytes = buildZipBytes(
            listOf(
                ZipSpec("template_images/", ByteArray(0), isDir = true),
                ZipSpec("backup.json", rawJson.toByteArray(Charsets.UTF_8)),
                ZipSpec(
                    "manifest.json",
                    """{"format_version":1,"app":"flame-contractor","images":{"id-png":"template_images/id-png.png","id-jpg":"template_images/id-jpg.jpeg","id-gif":"template_images/id-gif.gif","id-webp":"template_images/id-webp.webp"}}"""
                        .toByteArray(Charsets.UTF_8),
                ),
                ZipSpec("template_images/id-png.png", samplePng),
                ZipSpec("template_images/id-jpg.jpeg", sampleJpeg),
                ZipSpec("template_images/id-gif.gif", sampleGif),
                ZipSpec("template_images/id-webp.webp", sampleWebp),
            ),
        )

        val zipPayload = BackupArchiveCodec.readArchiveOrJson(ByteArrayInputStream(zipBytes))
        assertTrue(zipPayload.isZipArchive)
        assertEquals(rawJson, zipPayload.rawJson)
        assertEquals(4, zipPayload.bundledImagesByTemplateId.size)
        assertEquals("image/png", zipPayload.bundledImagesByTemplateId.getValue("id-png").mimeType)
        assertEquals("image/jpeg", zipPayload.bundledImagesByTemplateId.getValue("id-jpg").mimeType)
        assertEquals("image/gif", zipPayload.bundledImagesByTemplateId.getValue("id-gif").mimeType)
        assertEquals("image/webp", zipPayload.bundledImagesByTemplateId.getValue("id-webp").mimeType)
        assertArrayEquals(sampleWebp, zipPayload.bundledImagesByTemplateId.getValue("id-webp").bytes)
    }

    @Test
    fun readArchiveOrJsonRejectsZipSlipUnauthorizedEntriesAndDuplicates() {
        val validJsonBytes = """{"app":"flame-contractor","schemaVersion":1,"data":{"accounts":[]}}""".toByteArray(Charsets.UTF_8)

        val maliciousNames = listOf(
            "../evil.json",
            "template_images/../../etc/passwd.png",
            "/etc/passwd",
            "C:/windows/win.ini",
            "template_images\\evil.png",
            "template_images/bad.svg",
            "template_images/has space.png",
            "template_images/nested/file.png",
            "evil.sh",
            "template_images/ctrl\u0000.png",
        )
        for (badName in maliciousNames) {
            val zip = buildZipBytes(
                listOf(
                    ZipSpec("backup.json", validJsonBytes),
                    ZipSpec(badName, samplePng),
                ),
            )
            assertThrows("Expected rejection for entry [$badName]", IllegalArgumentException::class.java) {
                BackupArchiveCodec.readArchiveOrJson(ByteArrayInputStream(zip))
            }
        }

        // 拒绝缺少 backup.json
        val missingBackupZip = buildZipBytes(
            listOf(ZipSpec("manifest.json", """{"format_version":1,"images":{}}""".toByteArray())),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.readArchiveOrJson(ByteArrayInputStream(missingBackupZip))
        }

        // 拒绝空压缩包与空输入流
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.readArchiveOrJson(ByteArrayInputStream(ByteArray(0)))
        }
        val emptyZip = buildZipBytes(emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.readArchiveOrJson(ByteArrayInputStream(emptyZip))
        }
    }

    @Test
    fun readArchiveOrJsonEnforcesEntryCountAndUncompressedSizeCaps() {
        val validJsonBytes = """{"app":"flame-contractor","schemaVersion":1,"data":{"accounts":[]}}""".toByteArray(Charsets.UTF_8)

        // 1. 条目数超过 200 必须拒绝
        val tooManyEntries = ArrayList<ZipSpec>(202).apply {
            add(ZipSpec("backup.json", validJsonBytes))
            for (i in 1..201) {
                add(ZipSpec("template_images/tpl_$i.png", samplePng))
            }
        }
        val manyEntriesZip = buildZipBytes(tooManyEntries)
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.readArchiveOrJson(ByteArrayInputStream(manyEntriesZip))
        }

        // 2. backup.json 解压后超过 20 MB（高压缩率零字节炸弹）必须拒绝
        val bombJsonZip = buildZipBytes(
            listOf(
                ZipSpec("backup.json", ByteArray(BackupArchiveCodec.MAX_JSON_BYTES + 1) { ' '.code.toByte() }),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.readArchiveOrJson(ByteArrayInputStream(bombJsonZip))
        }

        // 3. GIF 超过 5 MB 必须拒绝（含伪装成 .jpg 的 GIF 魔数）
        val oversizedGif = ByteArray(BackupArchiveCodec.MAX_GIF_BYTES + 1).apply {
            sampleGif.copyInto(this)
        }
        val gifZip = buildZipBytes(
            listOf(
                ZipSpec("backup.json", validJsonBytes),
                ZipSpec("template_images/big_gif.gif", oversizedGif),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.readArchiveOrJson(ByteArrayInputStream(gifZip))
        }
        val disguisedGifZip = buildZipBytes(
            listOf(
                ZipSpec("backup.json", validJsonBytes),
                ZipSpec("template_images/disguised_gif.jpg", oversizedGif),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.readArchiveOrJson(ByteArrayInputStream(disguisedGifZip))
        }

        // 4. 总解压体积超过 80 MB（6 张 14 MB 合法 PNG 头 = 84 MB）必须拒绝
        val chunk14MbPng = ByteArray(14 * 1024 * 1024).apply {
            samplePng.copyInto(this)
        }
        val totalBombSpecs = ArrayList<ZipSpec>().apply {
            add(ZipSpec("backup.json", validJsonBytes))
            for (i in 1..6) {
                add(ZipSpec("template_images/img_$i.png", chunk14MbPng))
            }
        }
        val totalBombZip = buildZipBytes(totalBombSpecs)
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.readArchiveOrJson(ByteArrayInputStream(totalBombZip))
        }
    }

    @Test
    fun backupManagerZipRoundTripRestoresImagesWithRemapNaturalKeyMergeAndCleanup() = runTest {
        // 源端环境：导出完整 .zip（含 2 张真图 + 1 个缺图模板 + 1 个文本模板）
        val sourceDir = tempFolder.newFolder("src_images")
        val sourceImageStore = FakeTemplateImageStorage(sourceDir)
        val srcPng = sourceImageStore.writeDirect("src_id.png", samplePng)
        val srcJpg = sourceImageStore.writeDirect("src_name.jpg", sampleJpeg)

        val sourceStore = FakeArchiveBackupStore(
            accounts = listOf(AccountEntity(id = DEFAULT_ACCOUNT_ID, displayName = "源主号")),
            templates = listOf(
                MessageTemplateEntity(
                    id = "tpl-img-by-id",
                    accountId = DEFAULT_ACCOUNT_ID,
                    name = "新早安图",
                    type = TemplateType.IMAGE,
                    imagePath = srcPng.absolutePath,
                ),
                MessageTemplateEntity(
                    id = "src-tpl-img-name",
                    accountId = DEFAULT_ACCOUNT_ID,
                    name = "同名晚安图",
                    type = TemplateType.IMAGE,
                    imagePath = srcJpg.absolutePath,
                ),
                MessageTemplateEntity(
                    id = "tpl-img-missing",
                    accountId = DEFAULT_ACCOUNT_ID,
                    name = "缺图模板",
                    type = TemplateType.IMAGE,
                    imagePath = null,
                ),
                MessageTemplateEntity(
                    id = "tpl-txt",
                    accountId = DEFAULT_ACCOUNT_ID,
                    name = "纯文本",
                    type = TemplateType.TEXT,
                    content = "打卡",
                ),
            ),
            targets = listOf(
                ContractTargetEntity(
                    id = "tgt-1",
                    accountId = DEFAULT_ACCOUNT_ID,
                    nicknameSnapshot = "好友一",
                    platformUserId = "puid-1",
                    conversationType = "friend",
                    templateId = "tpl-img-by-id",
                ),
                ContractTargetEntity(
                    id = "tgt-2",
                    accountId = DEFAULT_ACCOUNT_ID,
                    nicknameSnapshot = "好友二",
                    platformUserId = "puid-2",
                    conversationType = "friend",
                    templateId = "src-tpl-img-name",
                ),
            ),
        )
        val sourceManager = BackupManager(
            store = sourceStore,
            clockMillis = { 100_000L },
            templateImageStore = sourceImageStore,
        )
        val zipOut = ByteArrayOutputStream()
        val exportResult = sourceManager.exportZipToStream(DEFAULT_ACCOUNT_ID, zipOut)
        assertEquals(2, exportResult.bundledImageCount)
        assertEquals(1, exportResult.skippedImageCount)

        // 目标端环境：已有同名但不同 ID 的本地图片模板（带旧图）以及二号账号用于测试多账号重映射
        val targetDir = tempFolder.newFolder("dst_images")
        val targetImageStore = FakeTemplateImageStorage(targetDir)
        val oldLocalFile = targetImageStore.writeDirect("old_to_replace.jpg", sampleJpeg)

        val targetStore = FakeArchiveBackupStore(
            accounts = listOf(
                AccountEntity(id = DEFAULT_ACCOUNT_ID, displayName = "目标主号"),
                AccountEntity(id = "acc-2", displayName = "目标二号"),
            ),
            templates = listOf(
                MessageTemplateEntity(
                    id = "local-existing-name-id",
                    accountId = DEFAULT_ACCOUNT_ID,
                    name = "同名晚安图",
                    type = TemplateType.IMAGE,
                    imagePath = oldLocalFile.absolutePath,
                ),
            ),
        )
        val targetManager = BackupManager(
            store = targetStore,
            clockMillis = { 200_000L },
            templateImageStore = targetImageStore,
        )

        // 1. 解析并预览 .zip
        val payload = BackupArchiveCodec.readArchiveOrJson(ByteArrayInputStream(zipOut.toByteArray()))
        val inspection = targetManager.inspectArchive(payload)
        assertTrue(inspection.isZipArchive)
        assertEquals(2, inspection.bundledImageCount)
        assertEquals(1, inspection.missingImageTemplateCount)

        // 2. 导入至主号：验证按 ID 插入与按自然键 (accountId, name) 合并均恢复图片，且旧未引用图被清理
        val report = targetManager.importBackup(
            parsed = inspection.parsed,
            selectedSourceAccountId = DEFAULT_ACCOUNT_ID,
            includeHistory = true,
            targetAccountId = DEFAULT_ACCOUNT_ID,
            bundledImagesByTemplateId = payload.bundledImagesByTemplateId,
        )
        assertEquals(2, report.restoredImagesCount)
        assertEquals(1, report.missingImageTemplateCount)
        assertEquals(3, report.templatesInserted)
        assertEquals(1, report.templatesUpdated)

        val dstTemplates = targetStore.listTemplates(DEFAULT_ACCOUNT_ID).associateBy { it.id }
        val restoredById = dstTemplates.getValue("tpl-img-by-id")
        assertNotNull(restoredById.imagePath)
        assertArrayEquals(samplePng, File(restoredById.imagePath!!).readBytes())

        val restoredByName = dstTemplates.getValue("local-existing-name-id")
        assertNotNull(restoredByName.imagePath)
        assertNotEquals(oldLocalFile.absolutePath, restoredByName.imagePath)
        assertArrayEquals(sampleJpeg, File(restoredByName.imagePath!!).readBytes())
        // 被替换的旧图片已被清理
        assertFalse(oldLocalFile.exists())

        // 缺图模板仍为 null 且保留定义
        assertNull(dstTemplates.getValue("tpl-img-missing").imagePath)

        // 好友绑定正确指向重映射后的本地模板 ID
        val dstTargets = targetStore.listAllTargetsIncludingDeleted(DEFAULT_ACCOUNT_ID).associateBy { it.nicknameSnapshot }
        assertEquals("tpl-img-by-id", dstTargets.getValue("好友一").templateId)
        assertEquals("local-existing-name-id", dstTargets.getValue("好友二").templateId)

        // 3. 同一份 .zip 再导入至二号账号：所有主键独立重映射，图片独立落盘绑定，不覆写主号
        val acc2Report = targetManager.importBackup(
            parsed = inspection.parsed,
            selectedSourceAccountId = DEFAULT_ACCOUNT_ID,
            includeHistory = true,
            targetAccountId = "acc-2",
            bundledImagesByTemplateId = payload.bundledImagesByTemplateId,
        )
        assertEquals(2, acc2Report.restoredImagesCount)
        assertEquals(1, acc2Report.missingImageTemplateCount)

        val acc2Templates = targetStore.listTemplates("acc-2")
        val acc2PngTpl = acc2Templates.first { it.name == "新早安图" }
        assertNotEquals("tpl-img-by-id", acc2PngTpl.id)
        assertNotNull(acc2PngTpl.imagePath)
        assertNotEquals(restoredById.imagePath, acc2PngTpl.imagePath)
        assertArrayEquals(samplePng, File(acc2PngTpl.imagePath!!).readBytes())
        // 主号图片仍然完好
        assertTrue(File(restoredById.imagePath!!).isFile)
    }

    private data class ZipSpec(
        val name: String,
        val bytes: ByteArray,
        val isDir: Boolean = false,
    )

    private fun buildZipBytes(specs: List<ZipSpec>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, Charsets.UTF_8).use { zos ->
            for (spec in specs) {
                val entry = ZipEntry(spec.name)
                zos.putNextEntry(entry)
                if (!spec.isDir && spec.bytes.isNotEmpty()) {
                    zos.write(spec.bytes)
                }
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private class FakeTemplateImageStorage(
        private val rootDir: File,
    ) : TemplateImageStorage {

        fun writeDirect(fileName: String, bytes: ByteArray): File {
            if (!rootDir.exists()) rootDir.mkdirs()
            return File(rootDir, fileName).apply { writeBytes(bytes) }
        }

        override fun resolveExistingFile(storedPath: String?): File? {
            val clean = storedPath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val candidate = File(clean)
            val rootCanonical = rootDir.canonicalFile.path.trimEnd(File.separatorChar) + File.separator
            val fileCanonical = candidate.canonicalFile
            if (!fileCanonical.path.startsWith(rootCanonical)) return null
            return fileCanonical.takeIf { it.isFile && it.length() > 0L }
        }

        override suspend fun importFromStream(
            input: InputStream,
            mimeTypeHint: String?,
        ): StoredTemplateImage {
            val bytes = input.readBytes()
            val detected = BackupArchiveCodec.detectImageFormat(bytes)
                ?: throw IllegalArgumentException("Invalid image bytes")
            if (!rootDir.exists()) rootDir.mkdirs()
            val dest = File(rootDir, "${UUID.randomUUID()}.${detected.extension}")
            dest.writeBytes(bytes)
            return StoredTemplateImage(
                storedPath = dest.absolutePath,
                mimeType = detected.mimeType,
                sizeBytes = bytes.size.toLong(),
                widthPx = 64,
                heightPx = 64,
            )
        }

        override fun deleteIfUnreferenced(
            storedPath: String?,
            referencedPaths: Set<String>,
        ): Boolean {
            val file = resolveExistingFile(storedPath) ?: return false
            val targetCanonical = file.canonicalPath
            val stillRef = referencedPaths.any { ref ->
                resolveExistingFile(ref)?.canonicalPath == targetCanonical
            }
            if (stillRef) return false
            return file.delete()
        }

        override fun contentUriFor(file: File): Uri? = null
    }

    private class FakeArchiveBackupStore(
        accounts: List<AccountEntity>,
        templates: List<MessageTemplateEntity> = emptyList(),
        targets: List<ContractTargetEntity> = emptyList(),
    ) : BackupStore {
        private val accountsMap = linkedMapOf<String, AccountEntity>().apply {
            accounts.forEach { put(it.id, it) }
        }
        private val templatesMap = linkedMapOf<String, MessageTemplateEntity>().apply {
            templates.forEach { put(it.id, it) }
        }
        private val poolItemsMap = linkedMapOf<String, TemplatePoolItemEntity>()
        private val targetsMap = linkedMapOf<String, ContractTargetEntity>().apply {
            targets.forEach { put(it.id, it) }
        }
        private val jobsMap = linkedMapOf<String, SendJobEntity>()
        private val attemptsMap = linkedMapOf<String, SendAttemptEntity>()

        override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
        override suspend fun getAccount(accountId: String): AccountEntity? = accountsMap[accountId]
        override suspend fun upsertAccount(account: AccountEntity) {
            accountsMap[account.id] = account
        }
        override suspend fun listTemplates(accountId: String): List<MessageTemplateEntity> =
            templatesMap.values.filter { it.accountId == accountId }
        override suspend fun templateIdExistsGlobally(templateId: String): Boolean =
            templatesMap.containsKey(templateId)
        override suspend fun upsertTemplate(template: MessageTemplateEntity) {
            templatesMap[template.id] = template
        }
        override suspend fun listPoolItems(templateId: String): List<TemplatePoolItemEntity> =
            poolItemsMap.values.filter { it.templateId == templateId }
        override suspend fun poolItemIdExistsGlobally(poolItemId: String): Boolean =
            poolItemsMap.containsKey(poolItemId)
        override suspend fun upsertPoolItem(item: TemplatePoolItemEntity) {
            poolItemsMap[item.id] = item
        }
        override suspend fun listAllTargetsIncludingDeleted(accountId: String): List<ContractTargetEntity> =
            targetsMap.values.filter { it.accountId == accountId }
        override suspend fun targetIdExistsGlobally(targetId: String): Boolean =
            targetsMap.containsKey(targetId)
        override suspend fun upsertTarget(target: ContractTargetEntity) {
            targetsMap[target.id] = target
        }
        override suspend fun listAllJobs(accountId: String): List<SendJobEntity> =
            jobsMap.values.filter { it.accountId == accountId }
        override suspend fun jobIdExistsGlobally(jobId: String): Boolean =
            jobsMap.containsKey(jobId)
        override suspend fun upsertJob(job: SendJobEntity) {
            jobsMap[job.id] = job
        }
        override suspend fun listAllAttemptsForAccount(accountId: String): List<SendAttemptEntity> {
            val jobIds = jobsMap.values.filter { it.accountId == accountId }.map { it.id }.toSet()
            return attemptsMap.values.filter { it.jobId in jobIds }
        }
        override suspend fun attemptIdExistsGlobally(attemptId: String): Boolean =
            attemptsMap.containsKey(attemptId)
        override suspend fun upsertAttempt(attempt: SendAttemptEntity) {
            attemptsMap[attempt.id] = attempt
        }
        override suspend fun listAllReferencedImagePaths(): Set<String> =
            templatesMap.values.mapNotNull { it.imagePath?.trim()?.takeIf(String::isNotEmpty) }.toSet()
    }
}
