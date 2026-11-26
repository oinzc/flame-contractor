package io.github.oinzc.flamecontractor.core.support

import java.io.File
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FeedbackDraftStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newStore(
        storage: InMemoryFeedbackDraftStorage = InMemoryFeedbackDraftStorage(),
        imagesDir: File = File(tempFolder.root, FeedbackDraftStore.IMAGES_DIR_NAME),
    ): FeedbackDraftStore = FeedbackDraftStore(
        storage = storage,
        imagesDir = imagesDir,
    )

    private fun sampleJpegBytes(extraLen: Int = 16): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(extraLen) { idx -> (idx + 1).toByte() }

    private fun samplePngBytes(extraLen: Int = 16): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(extraLen) { idx -> (idx + 7).toByte() }

    private fun sampleWebpBytes(extraLen: Int = 16): ByteArray =
        "RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.ISO_8859_1) +
            ByteArray(extraLen) { idx -> (idx + 3).toByte() }

    private fun sampleEncodedImage(
        id: String = "img-1",
        name: String = "screenshot_1.jpg",
        mimeType: String = "image/jpeg",
        bytes: ByteArray = sampleJpegBytes(),
        widthPx: Int = 1080,
        heightPx: Int = 1920,
    ): EncodedFeedbackImage = EncodedFeedbackImage(
        id = id,
        attachment = FeedbackImageAttachment(
            name = name,
            mimeType = mimeType,
            dataBase64 = Base64.getEncoder().encodeToString(bytes),
            sizeBytes = bytes.size,
        ),
        previewBytes = bytes,
        widthPx = widthPx,
        heightPx = heightPx,
    )

    @Test
    fun `empty store returns clean default draft values`() {
        val store = newStore()
        val snapshot = store.loadDraft()

        assertEquals(FeedbackKind.BUG, snapshot.kind)
        assertEquals("", snapshot.title)
        assertEquals("", snapshot.content)
        assertTrue(snapshot.includeEnv)
        assertTrue(snapshot.includeDiagnosticLog)
        assertTrue(snapshot.images.isEmpty())
        assertFalse(snapshot.hasRestorableContent)
    }

    @Test
    fun `saveTextDraft persists and reloads kind title content and toggles cleanly`() {
        val storage = InMemoryFeedbackDraftStorage()
        val store = newStore(storage = storage)

        store.saveTextDraft(
            kind = FeedbackKind.FEATURE,
            title = "希望支持导出记录",
            content = "第一行描述\n第二行补充 \"引号\" 与路径 \\test\\",
            includeEnv = false,
            includeDiagnosticLog = false,
        )

        val reloaded = newStore(storage = storage).loadDraft()
        assertEquals(FeedbackKind.FEATURE, reloaded.kind)
        assertEquals("希望支持导出记录", reloaded.title)
        assertEquals("第一行描述\n第二行补充 \"引号\" 与路径 \\test\\", reloaded.content)
        assertFalse(reloaded.includeEnv)
        assertFalse(reloaded.includeDiagnosticLog)
        assertTrue(reloaded.hasRestorableContent)
        assertTrue(reloaded.images.isEmpty())
    }

    @Test
    fun `text draft clamps oversized strings ignores unknown kind and purges legacy contact keys`() {
        val storage = InMemoryFeedbackDraftStorage(
            initial = mapOf(
                FeedbackDraftStore.KEY_KIND to "NOT_A_REAL_KIND",
                FeedbackDraftStore.KEY_TITLE to "标".repeat(FeedbackSubmitter.MAX_TITLE_LEN + 25),
                FeedbackDraftStore.KEY_CONTENT to "正".repeat(FeedbackSubmitter.MAX_CONTENT_LEN + 50),
                "contact" to "13800138000",
                "contact_input" to "wechat_secret",
            ),
        )
        val store = newStore(storage = storage)

        val loaded = store.loadDraft()
        assertEquals(FeedbackKind.BUG, loaded.kind)
        assertEquals(FeedbackSubmitter.MAX_TITLE_LEN, loaded.title.length)
        assertEquals(FeedbackSubmitter.MAX_CONTENT_LEN, loaded.content.length)
        assertFalse(storage.contains("contact"))
        assertFalse(storage.contains("contact_input"))

        // 即使再次写入也不会持久化任何 contact 字段
        store.saveTextDraft(
            kind = FeedbackKind.QUESTION,
            title = "咨询定时规则",
            content = "夜间定时窗口是如何计算的？",
            includeEnv = true,
            includeDiagnosticLog = true,
        )
        assertFalse(storage.snapshotKeys().any { it.contains("contact", ignoreCase = true) })
    }

    @Test
    fun `saveImages persists up to three images reloads accurately and prunes removed files`() {
        val imagesDir = File(tempFolder.root, "feedback_draft_images")
        val storage = InMemoryFeedbackDraftStorage()
        val store = newStore(storage = storage, imagesDir = imagesDir)

        val img1 = sampleEncodedImage(
            id = "id-jpg",
            name = "screen_a.jpg",
            mimeType = "image/jpeg",
            bytes = sampleJpegBytes(32),
            widthPx = 720,
            heightPx = 1280,
        )
        val img2 = sampleEncodedImage(
            id = "id-png",
            name = "screen_b.png",
            mimeType = "image/png",
            bytes = samplePngBytes(48),
            widthPx = 800,
            heightPx = 600,
        )
        val img3 = sampleEncodedImage(
            id = "id-webp",
            name = "screen_c.webp",
            mimeType = "image/webp",
            bytes = sampleWebpBytes(24),
            widthPx = 500,
            heightPx = 500,
        )
        val img4Overflow = sampleEncodedImage(
            id = "id-overflow",
            name = "screen_4.jpg",
            mimeType = "image/jpeg",
            bytes = sampleJpegBytes(20),
        )

        store.saveImages(listOf(img1, img2, img3, img4Overflow))

        val reloaded = newStore(storage = storage, imagesDir = imagesDir).loadDraft()
        assertEquals(3, reloaded.images.size)
        assertTrue(reloaded.hasRestorableContent)

        assertEquals("id-jpg", reloaded.images[0].id)
        assertEquals("screen_a.jpg", reloaded.images[0].attachment.name)
        assertEquals("image/jpeg", reloaded.images[0].attachment.mimeType)
        assertEquals(img1.previewBytes.size, reloaded.images[0].attachment.sizeBytes)
        assertEquals(img1.attachment.dataBase64, reloaded.images[0].attachment.dataBase64)
        assertArrayEquals(img1.previewBytes, reloaded.images[0].previewBytes)
        assertEquals(720, reloaded.images[0].widthPx)
        assertEquals(1280, reloaded.images[0].heightPx)

        assertEquals("id-png", reloaded.images[1].id)
        assertEquals("image/png", reloaded.images[1].attachment.mimeType)
        assertArrayEquals(img2.previewBytes, reloaded.images[1].previewBytes)

        assertEquals("id-webp", reloaded.images[2].id)
        assertEquals("image/webp", reloaded.images[2].attachment.mimeType)
        assertArrayEquals(img3.previewBytes, reloaded.images[2].previewBytes)

        // 移除前两张后仅保留 1 张，旧文件应被清理
        store.saveImages(listOf(img3))
        val afterRemove = newStore(storage = storage, imagesDir = imagesDir).loadImages()
        assertEquals(1, afterRemove.size)
        assertEquals("id-webp", afterRemove[0].id)

        val remainingFiles = imagesDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        assertEquals(2, remainingFiles.size) // images_meta.json + 1 image file
        assertTrue(FeedbackDraftStore.METADATA_FILE_NAME in remainingFiles)
    }

    @Test
    fun `loadImages blocks path traversal illegal filenames oversized and non-image payloads`() {
        val imagesDir = File(tempFolder.root, "feedback_draft_images")
        imagesDir.mkdirs()

        // 在父目录放置一个真实 JPEG，验证越界路径绝不会被读取
        val outsideSecret = File(tempFolder.root, "outside_secret.jpg")
        outsideSecret.writeBytes(sampleJpegBytes(40))

        // 子目录文件，也不允许通过相对路径读取
        val nestedDir = File(imagesDir, "nested")
        nestedDir.mkdirs()
        File(nestedDir, "nested.jpg").writeBytes(sampleJpegBytes(40))

        // 超大文件（800 KB + 1）
        val oversizedFile = File(imagesDir, "oversized.jpg")
        oversizedFile.writeBytes(sampleJpegBytes(FeedbackSubmitter.MAX_IMAGE_BYTES))

        // 伪装扩展名的纯文本文件
        val fakeImageFile = File(imagesDir, "not_an_image.jpg")
        fakeImageFile.writeText("plain text pretending to be jpeg")

        // 唯一合法图片
        val validBytes = samplePngBytes(20)
        val validFile = File(imagesDir, "valid_ok.png")
        validFile.writeBytes(validBytes)

        val maliciousMeta = FeedbackDraftMetaJson.encode(
            listOf(
                DraftImageMetaEntry("t1", "a.jpg", "image/jpeg", "../outside_secret.jpg", 44, 100, 100),
                DraftImageMetaEntry("t2", "b.jpg", "image/jpeg", "..\\outside_secret.jpg", 44, 100, 100),
                DraftImageMetaEntry("t3", "c.jpg", "image/jpeg", outsideSecret.absolutePath, 44, 100, 100),
                DraftImageMetaEntry("t4", "d.jpg", "image/jpeg", "nested/nested.jpg", 44, 100, 100),
                DraftImageMetaEntry("t5", "e.jpg", "image/jpeg", FeedbackDraftStore.METADATA_FILE_NAME, 44, 100, 100),
                DraftImageMetaEntry("t6", "f.jpg", "image/jpeg", "oversized.jpg", oversizedFile.length().toInt(), 100, 100),
                DraftImageMetaEntry("t7", "g.jpg", "image/jpeg", "not_an_image.jpg", 32, 100, 100),
                DraftImageMetaEntry("t8", "h.jpg", "image/jpeg", "missing.jpg", 32, 100, 100),
                DraftImageMetaEntry("valid-id", "good.png", "image/png", "valid_ok.png", validBytes.size, 320, 240),
            ),
        )
        File(imagesDir, FeedbackDraftStore.METADATA_FILE_NAME).writeText(maliciousMeta, Charsets.UTF_8)

        val store = newStore(imagesDir = imagesDir)
        val loaded = store.loadImages()
        assertEquals(1, loaded.size)
        assertEquals("valid-id", loaded[0].id)
        assertEquals("good.png", loaded[0].attachment.name)
        assertEquals("image/png", loaded[0].attachment.mimeType)
        assertArrayEquals(validBytes, loaded[0].previewBytes)

        // 直接校验文件名安全防御工具
        assertFalse(FeedbackDraftStore.isSafeCacheFileName("../a.jpg"))
        assertFalse(FeedbackDraftStore.isSafeCacheFileName("..\\a.jpg"))
        assertFalse(FeedbackDraftStore.isSafeCacheFileName("sub/a.jpg"))
        assertFalse(FeedbackDraftStore.isSafeCacheFileName(FeedbackDraftStore.METADATA_FILE_NAME))
        assertFalse(FeedbackDraftStore.isSafeCacheFileName(""))
        assertNull(FeedbackDraftStore.resolveSafeCacheFile(imagesDir, "../outside_secret.jpg"))
        assertTrue(FeedbackDraftStore.isSafeCacheFileName("img_0_abc123.jpg"))
    }

    @Test
    fun `clearDraft removes both text preferences and cached image files completely`() {
        val imagesDir = File(tempFolder.root, "feedback_draft_images")
        val storage = InMemoryFeedbackDraftStorage()
        val store = newStore(storage = storage, imagesDir = imagesDir)

        store.saveTextDraft(
            kind = FeedbackKind.QUESTION,
            title = "待清理标题",
            content = "待清理正文内容",
            includeEnv = false,
            includeDiagnosticLog = false,
        )
        store.saveImages(listOf(sampleEncodedImage()))
        assertTrue(imagesDir.exists())
        assertTrue((imagesDir.listFiles()?.size ?: 0) >= 2)

        store.clearDraft()

        assertTrue(storage.snapshotKeys().isEmpty())
        assertFalse(imagesDir.exists() && (imagesDir.listFiles()?.isNotEmpty() == true))

        val afterClear = store.loadDraft()
        assertEquals(FeedbackKind.BUG, afterClear.kind)
        assertEquals("", afterClear.title)
        assertEquals("", afterClear.content)
        assertTrue(afterClear.includeEnv)
        assertTrue(afterClear.includeDiagnosticLog)
        assertTrue(afterClear.images.isEmpty())
        assertFalse(afterClear.hasRestorableContent)
    }

    @Test
    fun `corrupted metadata degrades safely without throwing and deduplicates duplicate ids`() {
        val imagesDir = File(tempFolder.root, "feedback_draft_images")
        imagesDir.mkdirs()
        val metaFile = File(imagesDir, FeedbackDraftStore.METADATA_FILE_NAME)
        val store = newStore(imagesDir = imagesDir)

        val corruptedPayloads = listOf(
            "",
            "{not valid json",
            """{"version":1,"images":"not-an-array"}""",
            """[1, 2, 3]""",
            """{"version":1,"images":[{"fileName":12345}]}""",
            """{"version":1,"images":[null, 42, "str"]}""",
        )
        for (payload in corruptedPayloads) {
            metaFile.writeText(payload, Charsets.UTF_8)
            assertTrue("Expected safe empty list for payload: $payload", store.loadImages().isEmpty())
        }

        // 重复 id 在反序列化时自动去重，防止 Compose LazyRow key 冲突崩溃
        File(imagesDir, "img_a.jpg").writeBytes(sampleJpegBytes(12))
        File(imagesDir, "img_b.jpg").writeBytes(sampleJpegBytes(14))
        metaFile.writeText(
            FeedbackDraftMetaJson.encode(
                listOf(
                    DraftImageMetaEntry("dup-id", "a.jpg", "image/jpeg", "img_a.jpg", 16, 100, 100),
                    DraftImageMetaEntry("dup-id", "b.jpg", "image/jpeg", "img_b.jpg", 18, 100, 100),
                ),
            ),
            Charsets.UTF_8,
        )
        val deduped = store.loadImages()
        assertEquals(2, deduped.size)
        assertEquals(2, deduped.map { it.id }.toSet().size)
    }

    @Test
    fun `mergeFeedbackPreFillDraft fills empty draft and protects existing user title or content`() {
        val preFill = FeedbackPreFillDraft(
            kind = FeedbackKind.BUG,
            title = "自动预填标题",
            content = "自动预填的故障正文描述",
            attachDiagnosticLog = true,
            nonce = 100L,
        )

        // 空草稿：正常应用预填
        val fromEmpty = mergeFeedbackPreFillDraft(
            current = FeedbackTextDraft(
                kind = FeedbackKind.FEATURE,
                title = "",
                content = "",
                includeEnv = false,
                includeDiagnosticLog = false,
            ),
            preFill = preFill,
        )
        assertEquals(FeedbackKind.BUG, fromEmpty.kind)
        assertEquals("自动预填标题", fromEmpty.title)
        assertEquals("自动预填的故障正文描述", fromEmpty.content)
        assertFalse(fromEmpty.includeEnv)
        assertTrue(fromEmpty.includeDiagnosticLog)

        // 已有手写标题：不覆盖
        val withTitle = FeedbackTextDraft(
            kind = FeedbackKind.FEATURE,
            title = "用户手写标题",
            content = "",
            includeEnv = true,
            includeDiagnosticLog = false,
        )
        assertEquals(withTitle, mergeFeedbackPreFillDraft(withTitle, preFill))

        // 已有手写正文：不覆盖
        val withContent = FeedbackTextDraft(
            kind = FeedbackKind.QUESTION,
            title = "",
            content = "用户已经写了半段反馈正文",
            includeEnv = false,
            includeDiagnosticLog = false,
        )
        assertEquals(withContent, mergeFeedbackPreFillDraft(withContent, preFill))
    }
}
