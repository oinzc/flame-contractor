package io.github.oinzc.flamecontractor.core.selectors

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SelectorBundleValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun rejectsChatUrlOverrideAndControlCharacters() {
        val base = SelectorBundle.defaults()

        val invalidPayloads = listOf(
            // 锁死 CHAT_URL（即使与基线相同也禁止覆写）
            """{"CHAT_URL": "https://evil.example.com"}""",
            """{"CHAT_URL": "https://www.douyin.com/chat"}""",
            // 未知键名
            """{"UNKNOWN_SELECTOR_KEY": ".foo"}""",
            // 类型不匹配：数组键传字符串，字符串键传数组或数字
            """{"SEND_BUTTONS": ".single-string"}""",
            """{"CHAT_EDITOR": [".array-for-string"]}""",
            """{"CHAT_EDITOR": 123}""",
            """{"CHAT_EDITOR": null}""",
            // 空串或纯空白
            """{"CHAT_EDITOR": ""}""",
            """{"CHAT_EDITOR": "   "}""",
            // 长度超过 512
            """{"CHAT_EDITOR": "${"a".repeat(513)}"}""",
            // 列表为空或超过 20 项
            """{"SEND_BUTTONS": []}""",
            """{"SEND_BUTTONS": [${(1..21).joinToString(",") { "\".btn-$it\"" }}]}""",
            // 列表元素非法（空串 / 非字符串 / 超长）
            """{"SEND_BUTTONS": [".ok", "   "]}""",
            """{"SEND_BUTTONS": [".ok", 42]}""",
            """{"SEND_BUTTONS": ["${"b".repeat(513)}"]}""",
            // 禁止控制字符 \r / \n / \u0000（中间或首尾均拦截）
            """{"CHAT_EDITOR": ".a\n.b"}""",
            """{"CHAT_EDITOR": ".a\r\n.b"}""",
            """{"CHAT_EDITOR": ".a\r"}""",
            """{"CHAT_EDITOR": ".a\u0000.b"}""",
            """{"SEND_BUTTONS": [".ok", ".bad\nselector"]}""",
            """{"SEND_BUTTONS": [".bad\u0000selector"]}""",
            // 非对象根或非法 JSON
            """[".not-an-object"]""",
            """not-json""",
            """""",
        )

        for (payload in invalidPayloads) {
            try {
                SelectorBundleValidator.validateOverrides(base, payload)
                fail("Expected IllegalArgumentException for payload: $payload")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }

        // 明确确认 JSON 转义的 \u0000 / \r / \n 确实由控制字符规则拦截而非语法错误
        for (ctrlPayload in listOf(
            """{"CHAT_EDITOR": ".a\n.b"}""",
            """{"CHAT_EDITOR": ".a\r\n.b"}""",
            """{"CHAT_EDITOR": ".a\u0000.b"}""",
        )) {
            try {
                SelectorBundleValidator.validateOverrides(base, ctrlPayload)
                fail("Expected control char rejection for $ctrlPayload")
            } catch (exc: IllegalArgumentException) {
                assertTrue(
                    "Expected control-char error message, got: ${exc.message}",
                    exc.message?.contains("换行或控制字符") == true,
                )
            }
        }

        // 合法边界值：单串恰好 512 字符、列表恰好 20 项
        val boundaryJson = """
            {
              "CHAT_EDITOR": "  ${"x".repeat(512)}  ",
              "SEND_BUTTONS": [${(1..20).joinToString(",") { "\" .btn-$it \"" }}]
            }
        """.trimIndent()
        val validated = SelectorBundleValidator.validateOverrides(base, boundaryJson)
        assertEquals("x".repeat(512), validated["CHAT_EDITOR"])
        @Suppress("UNCHECKED_CAST")
        val buttons = validated["SEND_BUTTONS"] as List<String>
        assertEquals(20, buttons.size)
        assertEquals(".btn-1", buttons.first())
        assertEquals(".btn-20", buttons.last())
    }

    @Test
    fun computes12HexDigestMatchingPythonBundle() {
        val assetFile = locateAssetBundle()
        val rawAssetJson = assetFile.readText(Charsets.UTF_8)
        val overrideFile = File(tempFolder.root, "selectors_override.json")
        val store = SelectorBundleStore(rawAssetJson, overrideFile)

        // 1) 无覆写时：基线与内置默认完全一致，且 12 位小写十六进制 SHA-256 与规范化（LF）基线文件一致
        assertEquals(SelectorBundle.defaults(), store.base())
        val expectedBaseDigest = sha256Hex12(rawAssetJson.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
        val actualBaseDigest = store.digest()
        assertEquals(12, actualBaseDigest.length)
        assertTrue(actualBaseDigest.matches(Regex("[0-9a-f]{12}")))
        assertEquals(expectedBaseDigest, actualBaseDigest)
        // 与 backend/app/execution/selectors_bundle.json (LF) 校验值对齐
        assertEquals("60dbb6a6f9e1", actualBaseDigest)
        assertTrue(store.overrides().isEmpty())

        // 2) 有覆写时：对排序键 UTF-8 JSON（对齐 Python json.dumps(..., ensure_ascii=False, sort_keys=True)）求 SHA-256 前 12 位
        store.saveOverrides("""{"CONVERSATION_LIST": ".customList"}""")
        val overrideDigest = store.digest()
        assertEquals(12, overrideDigest.length)
        assertTrue(overrideDigest.matches(Regex("[0-9a-f]{12}")))
        assertEquals("7070d4f2a6e3", overrideDigest)
        assertEquals(".customList", store.current().conversationList)
        assertEquals(".conversationConversationListwrapper", store.base().conversationList)

        // 3) 清除覆写后：恢复基线摘要且删除覆写文件
        store.clearOverrides()
        assertFalse(overrideFile.exists())
        assertEquals(expectedBaseDigest, store.digest())
        assertEquals(".conversationConversationListwrapper", store.current().conversationList)
    }

    @Test
    fun savesOverridesAtomicallyPersistsAcrossReloadAndIgnoresTamperedChatUrlOnDisk() {
        val assetFile = locateAssetBundle()
        val rawAssetJson = assetFile.readText(Charsets.UTF_8)
        val overrideFile = File(tempFolder.root, "selectors_override.json")
        val store = SelectorBundleStore(rawAssetJson, overrideFile)

        val updated = store.saveOverrides(
            """
            {
              "CONVERSATION_LIST": ".customHotList",
              "STICKER_BUTTONS": ["svg.newStickerBtn", "button[aria-label*='表情']"]
            }
            """.trimIndent(),
        )
        assertEquals(".customHotList", updated.conversationList)
        assertEquals(listOf("svg.newStickerBtn", "button[aria-label*='表情']"), updated.stickerButtons)
        assertEquals("https://www.douyin.com/chat", updated.chatUrl)
        assertTrue(overrideFile.isFile)
        assertFalse(File(tempFolder.root, "selectors_override.json.tmp").exists())

        // 重新构造 Store 能持久读回覆写项
        val reloaded = SelectorBundleStore(rawAssetJson, overrideFile)
        assertEquals(".customHotList", reloaded.current().conversationList)
        assertEquals(2, reloaded.overrides().size)

        // 磁盘文件若被手工写入非法 CHAT_URL 与合法覆写：读取时安全忽略 CHAT_URL
        overrideFile.writeText(
            """
            {
              "CHAT_URL": "http://127.0.0.1:9999/ssrf",
              "CHAT_EDITOR": "div.custom-editor[contenteditable='true']"
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val afterTamper = SelectorBundleStore(rawAssetJson, overrideFile)
        assertEquals("https://www.douyin.com/chat", afterTamper.current().chatUrl)
        assertEquals("div.custom-editor[contenteditable='true']", afterTamper.current().chatEditor)
        assertEquals(mapOf("CHAT_EDITOR" to "div.custom-editor[contenteditable='true']"), afterTamper.overrides())

        // 保存与基线完全相同的值时，自动清理覆写文件
        afterTamper.saveOverrides("""{"CHAT_EDITOR": "div[data-slate-editor=\"true\"][contenteditable=\"true\"]"}""")
        assertFalse(overrideFile.exists())
        assertTrue(afterTamper.overrides().isEmpty())
    }

    private fun locateAssetBundle(): File {
        val candidates = listOf(
            File("src/main/assets/selectors_bundle.json"),
            File("app/src/main/assets/selectors_bundle.json"),
            File("android/app/src/main/assets/selectors_bundle.json"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate selectors_bundle.json from ${File(".").absolutePath}")
    }

    private fun sha256Hex12(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }
}
