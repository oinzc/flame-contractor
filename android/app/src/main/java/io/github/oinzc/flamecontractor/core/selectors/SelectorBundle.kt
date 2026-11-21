package io.github.oinzc.flamecontractor.core.selectors

import java.util.Locale

data class SelectorBundle(
    val chatUrl: String,
    val conversationList: String,
    val conversationItem: String,
    val conversationTitle: String,
    val conversationStreak: String,
    val conversationStreakDays: String,
    val searchResultItem: String,
    val searchNameSelectors: List<String>,
    val searchNameExactSelectors: List<String>,
    val searchChatBtn: String,
    val rightTitle: String,
    val searchInput: String,
    val searchInputs: List<String>,
    val chatEditor: String,
    val chatEditorSelectors: List<String>,
    val imageInputs: List<String>,
    val imageUploadTrigger: String,
    val imageDialogSend: List<String>,
    val sendFailureMarkers: List<String>,
    val sendButtons: List<String>,
    val mediaMessageBoxes: String,
    val mediaMessageContent: String,
    val messageTime: String,
    val stickerButtons: List<String>,
    val stickerPanels: List<String>,
    val stickerItem: String,
    val stickerItemName: String,
) {
    fun toMap(): Map<String, Any> = linkedMapOf(
        "CHAT_URL" to chatUrl,
        "CONVERSATION_LIST" to conversationList,
        "CONVERSATION_ITEM" to conversationItem,
        "CONVERSATION_TITLE" to conversationTitle,
        "CONVERSATION_STREAK" to conversationStreak,
        "CONVERSATION_STREAK_DAYS" to conversationStreakDays,
        "SEARCH_RESULT_ITEM" to searchResultItem,
        "SEARCH_NAME_SELECTORS" to searchNameSelectors.toList(),
        "SEARCH_NAME_EXACT_SELECTORS" to searchNameExactSelectors.toList(),
        "SEARCH_CHAT_BTN" to searchChatBtn,
        "RIGHT_TITLE" to rightTitle,
        "SEARCH_INPUT" to searchInput,
        "SEARCH_INPUTS" to searchInputs.toList(),
        "CHAT_EDITOR" to chatEditor,
        "CHAT_EDITOR_SELECTORS" to chatEditorSelectors.toList(),
        "IMAGE_INPUTS" to imageInputs.toList(),
        "IMAGE_UPLOAD_TRIGGER" to imageUploadTrigger,
        "IMAGE_DIALOG_SEND" to imageDialogSend.toList(),
        "SEND_FAILURE_MARKERS" to sendFailureMarkers.toList(),
        "SEND_BUTTONS" to sendButtons.toList(),
        "MEDIA_MESSAGE_BOXES" to mediaMessageBoxes,
        "MEDIA_MESSAGE_CONTENT" to mediaMessageContent,
        "MESSAGE_TIME" to messageTime,
        "STICKER_BUTTONS" to stickerButtons.toList(),
        "STICKER_PANELS" to stickerPanels.toList(),
        "STICKER_ITEM" to stickerItem,
        "STICKER_ITEM_NAME" to stickerItemName,
    )

    operator fun get(key: String): Any? = toMap()[key]

    fun merge(overrides: Map<String, Any>): SelectorBundle {
        if (overrides.isEmpty()) return this
        val merged = toMap().toMutableMap()
        for ((key, value) in overrides) {
            if (key in NON_EDITABLE_KEYS || key !in merged) continue
            merged[key] = copyValue(value)
        }
        return fromMap(merged)
    }

    companion object {
        const val KEY_CHAT_URL = "CHAT_URL"

        val NON_EDITABLE_KEYS: Set<String> = setOf(KEY_CHAT_URL)

        val ALL_KEYS: List<String> = listOf(
            "CHAT_URL",
            "CONVERSATION_LIST",
            "CONVERSATION_ITEM",
            "CONVERSATION_TITLE",
            "CONVERSATION_STREAK",
            "CONVERSATION_STREAK_DAYS",
            "SEARCH_RESULT_ITEM",
            "SEARCH_NAME_SELECTORS",
            "SEARCH_NAME_EXACT_SELECTORS",
            "SEARCH_CHAT_BTN",
            "RIGHT_TITLE",
            "SEARCH_INPUT",
            "SEARCH_INPUTS",
            "CHAT_EDITOR",
            "CHAT_EDITOR_SELECTORS",
            "IMAGE_INPUTS",
            "IMAGE_UPLOAD_TRIGGER",
            "IMAGE_DIALOG_SEND",
            "SEND_FAILURE_MARKERS",
            "SEND_BUTTONS",
            "MEDIA_MESSAGE_BOXES",
            "MEDIA_MESSAGE_CONTENT",
            "MESSAGE_TIME",
            "STICKER_BUTTONS",
            "STICKER_PANELS",
            "STICKER_ITEM",
            "STICKER_ITEM_NAME",
        )

        val EDITABLE_KEYS: List<String> = ALL_KEYS.filterNot { it in NON_EDITABLE_KEYS }

        fun defaults(): SelectorBundle = SelectorBundle(
            chatUrl = "https://www.douyin.com/chat",
            conversationList = ".conversationConversationListwrapper",
            conversationItem = ".conversationConversationItemwrapper",
            conversationTitle = ".conversationConversationItemtitle",
            conversationStreak = "[class*=\"StreakstreakContainer\"]",
            conversationStreakDays = "[class*=\"StreaknormalText\"]",
            searchResultItem = "div[class*=\"SearchPanelitembox\"], " +
                "div[class*=\"SearchPanelitem-box\"], " +
                "div[class*=\"SearchPanelitem_box\"]",
            searchNameSelectors = listOf(
                "[class*=\"SearchPanelitemtitle\"]",
                "[class*=\"SearchPanelitemTitle\"]",
                "[class*=\"SearchPanelitem_title\"]",
                "[class*=\"SearchPanelitem-title\"]",
                "[class*=\"SearchPanelitemname\"]",
                "[class*=\"SearchPanelitemName\"]",
                "[class*=\"SearchPanelitem_name\"]",
                "[class*=\"SearchPanelitem-name\"]",
            ),
            searchNameExactSelectors = listOf(
                "[class*=\"SearchPanelitemsearch_highlight\"]",
                "[class*=\"SearchPanelitemsearch-highlight\"]",
                "[class*=\"SearchPanelitemsearchHighlight\"]",
            ),
            searchChatBtn = "div[class*=\"SearchPanelitemchat_btn\"]",
            rightTitle = ".RightPanelHeadertitleContainer",
            searchInput = "input.semi-input[placeholder=\"搜索\"][type=\"text\"]",
            searchInputs = listOf(
                "input.semi-input[placeholder=\"搜索\"][type=\"text\"]",
                "input[placeholder=\"搜索\"]",
            ),
            chatEditor = "div[data-slate-editor=\"true\"][contenteditable=\"true\"]",
            chatEditorSelectors = listOf(
                "div[data-slate-editor=\"true\"][contenteditable=\"true\"]",
                "[class*=\"messageEditor\"] [contenteditable=\"true\"]",
                ".messageEditorinputArea[contenteditable=\"true\"]",
            ),
            imageInputs = listOf(
                "input[type=\"file\"][accept*=\"image\"]",
                "input[type=\"file\"]",
            ),
            imageUploadTrigger = ".semi-upload-add",
            imageDialogSend = listOf(
                ".semi-modal button[class*=\"primary\"]",
                "button[class*=\"semi-button-primary\"]",
                "[class*=\"confirm\"] button[class*=\"primary\"]",
            ),
            sendFailureMarkers = listOf(
                "[class*=\"sendFailed\"]",
                "[class*=\"SendFailed\"]",
                "[class*=\"ContentSideSendStatusretry\"]",
                "[class*=\"SendStatusretry\"]",
                "[aria-label*=\"重试\"]",
                "[title*=\"重试\"]",
            ),
            sendButtons = listOf(
                "[class*=\"messageMsgInputpublishBtn\"]",
                ".e2e-send-msg-bt",
                "button[aria-label*=\"发送\"]",
                "[role=\"button\"][aria-label*=\"发送\"]",
            ),
            mediaMessageBoxes = "[class*=\"messageMessageBoxmessageBox\"]",
            mediaMessageContent = "[class*=\"contentBox\"]",
            messageTime = "[class*=\"timeLayout\"]",
            stickerButtons = listOf(
                "svg.messageMsgInputiconAction",
                "button[aria-label*=\"表情\"]",
                "[role=\"button\"][aria-label*=\"表情\"]",
                "[title*=\"表情\"]",
            ),
            stickerPanels = listOf(
                ".componentsemojiemojiPanel",
                "[class*=\"emojiPanel\"]",
                "[role=\"dialog\"]",
                "[class*=\"sticker\"]",
            ),
            stickerItem = ".emojiEmojiItememojiItem",
            stickerItemName = ".emojiEmojiItememojiItemDesc",
        )

        fun fromJson(rawJson: String): SelectorBundle {
            val baseMap = defaults().toMap().toMutableMap()
            val parsed = SelectorJsonCodec.parse(rawJson)
            if (parsed !is Map<*, *>) {
                throw IllegalArgumentException("定位器基线必须为 JSON 对象")
            }
            for ((rawKey, rawVal) in parsed) {
                val key = rawKey as? String ?: continue
                if (rawVal == null || key !in baseMap) continue
                val expected = baseMap[key]
                when (expected) {
                    is String -> if (rawVal is String) baseMap[key] = rawVal
                    is List<*> -> if (rawVal is List<*> && rawVal.all { it is String }) {
                        @Suppress("UNCHECKED_CAST")
                        baseMap[key] = (rawVal as List<String>).toList()
                    }
                }
            }
            return fromMap(baseMap)
        }

        fun fromMap(values: Map<String, Any>): SelectorBundle {
            val fallback = defaults()
            fun str(key: String, default: String): String =
                (values[key] as? String) ?: default

            fun strList(key: String, default: List<String>): List<String> {
                val raw = values[key] as? List<*> ?: return default
                if (raw.any { it !is String }) return default
                @Suppress("UNCHECKED_CAST")
                return (raw as List<String>).toList()
            }

            return SelectorBundle(
                chatUrl = str("CHAT_URL", fallback.chatUrl),
                conversationList = str("CONVERSATION_LIST", fallback.conversationList),
                conversationItem = str("CONVERSATION_ITEM", fallback.conversationItem),
                conversationTitle = str("CONVERSATION_TITLE", fallback.conversationTitle),
                conversationStreak = str("CONVERSATION_STREAK", fallback.conversationStreak),
                conversationStreakDays = str("CONVERSATION_STREAK_DAYS", fallback.conversationStreakDays),
                searchResultItem = str("SEARCH_RESULT_ITEM", fallback.searchResultItem),
                searchNameSelectors = strList("SEARCH_NAME_SELECTORS", fallback.searchNameSelectors),
                searchNameExactSelectors = strList("SEARCH_NAME_EXACT_SELECTORS", fallback.searchNameExactSelectors),
                searchChatBtn = str("SEARCH_CHAT_BTN", fallback.searchChatBtn),
                rightTitle = str("RIGHT_TITLE", fallback.rightTitle),
                searchInput = str("SEARCH_INPUT", fallback.searchInput),
                searchInputs = strList("SEARCH_INPUTS", fallback.searchInputs),
                chatEditor = str("CHAT_EDITOR", fallback.chatEditor),
                chatEditorSelectors = strList("CHAT_EDITOR_SELECTORS", fallback.chatEditorSelectors),
                imageInputs = strList("IMAGE_INPUTS", fallback.imageInputs),
                imageUploadTrigger = str("IMAGE_UPLOAD_TRIGGER", fallback.imageUploadTrigger),
                imageDialogSend = strList("IMAGE_DIALOG_SEND", fallback.imageDialogSend),
                sendFailureMarkers = strList("SEND_FAILURE_MARKERS", fallback.sendFailureMarkers),
                sendButtons = strList("SEND_BUTTONS", fallback.sendButtons),
                mediaMessageBoxes = str("MEDIA_MESSAGE_BOXES", fallback.mediaMessageBoxes),
                mediaMessageContent = str("MEDIA_MESSAGE_CONTENT", fallback.mediaMessageContent),
                messageTime = str("MESSAGE_TIME", fallback.messageTime),
                stickerButtons = strList("STICKER_BUTTONS", fallback.stickerButtons),
                stickerPanels = strList("STICKER_PANELS", fallback.stickerPanels),
                stickerItem = str("STICKER_ITEM", fallback.stickerItem),
                stickerItemName = str("STICKER_ITEM_NAME", fallback.stickerItemName),
            )
        }

        fun toCanonicalSortedJson(bundle: SelectorBundle): String =
            SelectorJsonCodec.encodeCanonicalSorted(bundle.toMap())

        fun toPrettyJson(values: Map<String, Any>): String =
            SelectorJsonCodec.encodePretty(values)

        internal fun copyValue(value: Any): Any = when (value) {
            is List<*> -> value.toList()
            else -> value
        }
    }
}

internal object SelectorJsonCodec {

    private const val MAX_DEPTH = 64

    fun parse(raw: String): Any? {
        val parser = Parser(raw)
        val result = parser.parseValue(depth = 0)
        parser.skipWhitespace()
        if (!parser.isAtEnd()) {
            throw IllegalArgumentException("JSON 末尾存在多余字符")
        }
        return result
    }

    /**
     * 严格对齐 Python `json.dumps(values, ensure_ascii=False, sort_keys=True)` 输出格式，
     * 确保跨端 12 位 SHA-256 摘要完全一致。
     */
    fun encodeCanonicalSorted(values: Map<String, Any>): String {
        val sb = StringBuilder()
        sb.append('{')
        val sortedKeys = values.keys.sorted()
        sortedKeys.forEachIndexed { index, key ->
            if (index > 0) sb.append(", ")
            appendEscapedString(sb, key)
            sb.append(": ")
            appendCanonicalValue(sb, values[key])
        }
        sb.append('}')
        return sb.toString()
    }

    fun encodePretty(values: Map<String, Any>): String {
        val sb = StringBuilder()
        sb.append("{\n")
        val entries = values.entries.toList()
        entries.forEachIndexed { index, (key, value) ->
            sb.append("  ")
            appendEscapedString(sb, key)
            sb.append(": ")
            when (value) {
                is String -> appendEscapedString(sb, value)
                is List<*> -> {
                    if (value.isEmpty()) {
                        sb.append("[]")
                    } else {
                        sb.append("[\n")
                        value.forEachIndexed { itemIdx, item ->
                            sb.append("    ")
                            appendEscapedString(sb, item.toString())
                            if (itemIdx < value.lastIndex) sb.append(',')
                            sb.append('\n')
                        }
                        sb.append("  ]")
                    }
                }
                else -> throw IllegalArgumentException("不支持序列化的定位器值类型")
            }
            if (index < entries.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun appendCanonicalValue(sb: StringBuilder, value: Any?) {
        when (value) {
            is String -> appendEscapedString(sb, value)
            is List<*> -> {
                sb.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) sb.append(", ")
                    appendCanonicalValue(sb, item)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("不支持序列化的定位器值类型")
        }
    }

    private fun appendEscapedString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun isAtEnd(): Boolean = pos >= src.length

        fun skipWhitespace() {
            while (pos < src.length) {
                when (src[pos]) {
                    ' ', '\t', '\r', '\n' -> pos++
                    else -> return
                }
            }
        }

        fun parseValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) {
                throw IllegalArgumentException("JSON 嵌套层数超过上限")
            }
            skipWhitespace()
            if (isAtEnd()) throw IllegalArgumentException("JSON 内容不完整")
            return when (src[pos]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("JSON 语法错误")
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            pos++ // '{'
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (!isAtEnd() && src[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (isAtEnd() || src[pos] != '"') {
                    throw IllegalArgumentException("JSON 对象键必须为字符串")
                }
                val key = parseString()
                skipWhitespace()
                if (isAtEnd() || src[pos] != ':') {
                    throw IllegalArgumentException("JSON 对象缺少冒号分隔符")
                }
                pos++ // ':'
                val value = parseValue(depth)
                map[key] = value
                skipWhitespace()
                if (isAtEnd()) throw IllegalArgumentException("JSON 对象未闭合")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("JSON 对象缺少逗号或闭合括号")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            pos++ // '['
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (!isAtEnd() && src[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue(depth))
                skipWhitespace()
                if (isAtEnd()) throw IllegalArgumentException("JSON 数组未闭合")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("JSON 数组缺少逗号或闭合括号")
                }
            }
        }

        private fun parseString(): String {
            pos++ // opening '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        if (pos >= src.length) throw IllegalArgumentException("JSON 字符串转义不完整")
                        val esc = src[pos++]
                        when (esc) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) {
                                    throw IllegalArgumentException("JSON Unicode 转义不完整")
                                }
                                val hex = src.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("JSON Unicode 转义无效")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("JSON 包含不支持的转义字符")
                        }
                    }
                    ch.code < 0x20 -> throw IllegalArgumentException("JSON 字符串包含未转义控制字符")
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("JSON 字符串未闭合")
        }

        private fun parseLiteral(expected: String, value: Any?): Any? {
            if (!src.startsWith(expected, pos)) {
                throw IllegalArgumentException("JSON 字面量无效")
            }
            pos += expected.length
            return value
        }

        private fun parseNumber(): Number {
            val start = pos
            if (src[pos] == '-') pos++
            if (isAtEnd()) throw IllegalArgumentException("JSON 数字格式无效")
            if (src[pos] == '0') {
                pos++
            } else if (src[pos] in '1'..'9') {
                while (!isAtEnd() && src[pos] in '0'..'9') pos++
            } else {
                throw IllegalArgumentException("JSON 数字格式无效")
            }
            var isFloating = false
            if (!isAtEnd() && src[pos] == '.') {
                isFloating = true
                pos++
                if (isAtEnd() || src[pos] !in '0'..'9') {
                    throw IllegalArgumentException("JSON 小数格式无效")
                }
                while (!isAtEnd() && src[pos] in '0'..'9') pos++
            }
            if (!isAtEnd() && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloating = true
                pos++
                if (!isAtEnd() && (src[pos] == '+' || src[pos] == '-')) pos++
                if (isAtEnd() || src[pos] !in '0'..'9') {
                    throw IllegalArgumentException("JSON 指数格式无效")
                }
                while (!isAtEnd() && src[pos] in '0'..'9') pos++
            }
            val token = src.substring(start, pos)
            return if (isFloating) {
                token.toDoubleOrNull() ?: throw IllegalArgumentException("JSON 浮点数溢出")
            } else {
                token.toLongOrNull() ?: token.toDoubleOrNull()
                    ?: throw IllegalArgumentException("JSON 整数溢出")
            }
        }
    }
}
