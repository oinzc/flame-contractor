package io.github.oinzc.flamecontractor.core.notify

import java.util.Locale

object BarkUrlNormalizer {

    const val MAX_BARK_URL_LEN = 255
    const val MAX_BARK_RAW_INPUT_LEN = 2048

    private val CURL_URL_REGEX = Regex("""https?://[^\s'"`\\]+""", RegexOption.IGNORE_CASE)
    private val JSON_DEVICE_KEY_REGEX =
        Regex("""(?:\\?["'])device_key(?:\\?["'])\s*:\s*(?:\\?["'])([^"'\\]+)(?:\\?["'])""")
    private val FORM_DEVICE_KEY_REGEX = Regex("""(?:^|[&\s'"?])device_key=([^&\s'"]+)""")
    private val PERCENT_ENCODED_REGEX = Regex("""%[0-9a-fA-F]{2}""")
    private val POST_METHOD_REGEX = Regex("""-X\s+POST\b""", RegexOption.IGNORE_CASE)
    private val SCHEME_PREFIX_REGEX = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):(.*)$""")

    private val PLACEHOLDER_KEYS = setOf(
        "device_key",
        "devicekey",
        "your_key",
        "your_device_key",
        "your-device-key",
        "placeholder",
        "你的设备key",
    )

    fun normalize(rawInput: String): String {
        val cleaned = rawInput.trim()
        if (cleaned.isEmpty()) {
            throw IllegalArgumentException("Bark 推送地址不能为空")
        }
        if (cleaned.length > MAX_BARK_RAW_INPUT_LEN) {
            throw IllegalArgumentException("输入内容长度不能超过 $MAX_BARK_RAW_INPUT_LEN 个字符")
        }

        val isCurl = cleaned.lowercase(Locale.ROOT).startsWith("curl") || cleaned.any { it.isWhitespace() }
        val candidateUrl = if (isCurl) {
            val match = CURL_URL_REGEX.find(cleaned)
                ?: throw IllegalArgumentException("未能从粘贴内容中识别出 http:// 或 https:// Bark 服务地址")
            match.value.trimEnd(',', ';')
        } else {
            cleaned
        }

        val parts = splitUrl(candidateUrl)
        val scheme = parts.scheme.lowercase(Locale.ROOT)
        if (scheme !in setOf("http", "https") || !isValidNetloc(parts.netloc)) {
            throw IllegalArgumentException("Bark 推送地址必须以 http:// 或 https:// 开头并包含合法域名")
        }

        val netloc = parts.netloc
        val segments = parts.path.split('/').filter { it.isNotEmpty() }.toMutableList()
        val bodyKey = if (isCurl) extractDeviceKeyFromBody(cleaned) else ""

        if (bodyKey.isNotEmpty() && (segments.isEmpty() || segments.last().equals("push", ignoreCase = true))) {
            if (segments.isNotEmpty() && segments.last().equals("push", ignoreCase = true)) {
                segments.removeAt(segments.lastIndex)
            }
            if (segments.lastOrNull() != bodyKey) {
                segments.add(bodyKey)
            }
        } else {
            if (
                segments.size >= 3 &&
                segments[segments.size - 2].equals("title", ignoreCase = true) &&
                segments.last().equals("body", ignoreCase = true)
            ) {
                segments.removeAt(segments.lastIndex)
                segments.removeAt(segments.lastIndex)
            } else if (
                netloc.equals("api.day.app", ignoreCase = true) &&
                segments.size >= 2 &&
                !segments[0].equals("push", ignoreCase = true) &&
                !(segments.size == 2 && segments[1].equals("push", ignoreCase = true))
            ) {
                val first = segments[0]
                segments.clear()
                segments.add(first)
            } else if (isCurl && !POST_METHOD_REGEX.containsMatchIn(cleaned)) {
                while (
                    segments.size >= 2 &&
                    !segments.last().equals("push", ignoreCase = true) &&
                    isExampleMessageSegment(segments.last())
                ) {
                    segments.removeAt(segments.lastIndex)
                }
            }
        }

        if (segments.isEmpty()) {
            throw IllegalArgumentException(
                "Bark 推送地址缺少设备 Key 路径段，请按 https://api.day.app/<device_key> 格式填写",
            )
        }

        val normalized = "$scheme://$netloc/${segments.joinToString("/")}"
        val deviceKey = extractDeviceKey(normalized)
        if (deviceKey.isEmpty()) {
            throw IllegalArgumentException(
                "Bark 推送地址缺少设备 Key 路径段，请按 https://api.day.app/<device_key> 格式填写",
            )
        }
        if (isPlaceholderDeviceKey(deviceKey)) {
            throw IllegalArgumentException("请将示例中的占位文本替换为真实的 Bark 设备 Key")
        }
        if (normalized.length > MAX_BARK_URL_LEN) {
            throw IllegalArgumentException("Bark 推送地址长度不能超过 $MAX_BARK_URL_LEN 个字符")
        }
        return normalized
    }

    fun mask(rawUrl: String?): String? {
        val cleaned = rawUrl?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        val parts = splitUrl(cleaned)
        if (parts.scheme.isEmpty() || parts.netloc.isEmpty()) {
            return "****"
        }
        val segments = parts.path.split('/').filter { it.isNotEmpty() }.toMutableList()
        var keyIdx: Int? = null
        for (i in segments.lastIndex downTo 0) {
            if (!segments[i].equals("push", ignoreCase = true)) {
                keyIdx = i
                break
            }
        }
        if (keyIdx != null) {
            val key = segments[keyIdx]
            segments[keyIdx] = if (key.length > 4) "****${key.takeLast(4)}" else "****"
        }
        val maskedPath = if (segments.isNotEmpty()) "/${segments.joinToString("/")}" else ""
        return "${parts.scheme}://${parts.netloc}$maskedPath"
    }

    fun extractDeviceKey(baseUrl: String): String {
        val parts = splitUrl(baseUrl.trim())
        val segments = parts.path.split('/').filter { it.isNotEmpty() }
        for (i in segments.lastIndex downTo 0) {
            if (!segments[i].equals("push", ignoreCase = true)) {
                return segments[i]
            }
        }
        return ""
    }

    private fun extractDeviceKeyFromBody(rawInput: String): String {
        val start = rawInput.indexOf('{')
        val end = rawInput.lastIndexOf('}')
        if (start != -1 && end > start) {
            val candidate = rawInput.substring(start, end + 1)
            val parsed = BarkJsonCodec.parseObjectOrNull(candidate)
            val rawKey = parsed?.get("device_key") as? String
            if (!rawKey.isNullOrBlank()) {
                return rawKey.trim()
            }
        }

        val jsonMatch = JSON_DEVICE_KEY_REGEX.find(rawInput)
        if (jsonMatch != null) {
            return jsonMatch.groupValues[1].trim()
        }

        val formMatch = FORM_DEVICE_KEY_REGEX.find(rawInput)
        if (formMatch != null) {
            return formMatch.groupValues[1].trim()
        }
        return ""
    }

    private fun isExampleMessageSegment(segment: String): Boolean {
        // 含尖括号/花括号的占位段留给后续占位符校验报错，不误当作示例正文剥离
        if (segment.any { it in "<>{}[]" }) return false
        val lower = segment.lowercase(Locale.ROOT)
        if (lower == "title" || lower == "body") return true
        if (PERCENT_ENCODED_REGEX.containsMatchIn(segment)) return true
        return segment.any { it.code > 127 }
    }

    private fun isPlaceholderDeviceKey(deviceKey: String): Boolean {
        if (deviceKey.any { it in "<>{}[]" || it.isWhitespace() }) {
            return true
        }
        return deviceKey.lowercase(Locale.ROOT) in PLACEHOLDER_KEYS
    }

    private fun isValidNetloc(netloc: String): Boolean {
        if (netloc.isEmpty() || netloc.any { it.isWhitespace() || it in "<>{}" }) return false
        val hostPort = netloc.substringAfterLast('@')
        if (hostPort.isEmpty()) return false
        if (hostPort.startsWith('[')) {
            val closeIdx = hostPort.indexOf(']')
            if (closeIdx <= 1) return false
            val rest = hostPort.substring(closeIdx + 1)
            if (rest.isEmpty()) return true
            if (!rest.startsWith(':')) return false
            val port = rest.substring(1).toIntOrNull() ?: return false
            return port in 1..65535
        }
        val colonIdx = hostPort.indexOf(':')
        if (colonIdx == -1) return true
        if (colonIdx == 0) return false
        val portStr = hostPort.substring(colonIdx + 1)
        val port = portStr.toIntOrNull() ?: return false
        return port in 1..65535
    }

    private data class SplitUrl(
        val scheme: String,
        val netloc: String,
        val path: String,
    )

    private fun splitUrl(raw: String): SplitUrl {
        val match = SCHEME_PREFIX_REGEX.matchEntire(raw)
            ?: return SplitUrl(scheme = "", netloc = "", path = raw)
        val scheme = match.groupValues[1]
        var remainder = match.groupValues[2]

        val hashIdx = remainder.indexOf('#')
        if (hashIdx >= 0) remainder = remainder.substring(0, hashIdx)
        val queryIdx = remainder.indexOf('?')
        if (queryIdx >= 0) remainder = remainder.substring(0, queryIdx)

        if (!remainder.startsWith("//")) {
            return SplitUrl(scheme = scheme, netloc = "", path = remainder)
        }
        val afterSlashes = remainder.substring(2)
        val slashIdx = afterSlashes.indexOf('/')
        return if (slashIdx == -1) {
            SplitUrl(scheme = scheme, netloc = afterSlashes, path = "")
        } else {
            SplitUrl(
                scheme = scheme,
                netloc = afterSlashes.substring(0, slashIdx),
                path = afterSlashes.substring(slashIdx),
            )
        }
    }
}

internal object BarkJsonCodec {

    private const val MAX_DEPTH = 32

    fun encodeStringObject(fields: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append('{')
        var first = true
        for ((key, value) in fields) {
            if (!first) sb.append(',')
            first = false
            appendEscapedString(sb, key)
            sb.append(':')
            appendEscapedString(sb, value)
        }
        sb.append('}')
        return sb.toString()
    }

    fun parseObjectOrNull(raw: String): Map<String, Any?>? {
        return try {
            val parser = Parser(raw)
            val parsed = parser.parseValue(0)
            parser.skipWhitespace()
            if (!parser.isAtEnd()) return null
            @Suppress("UNCHECKED_CAST")
            parsed as? Map<String, Any?>
        } catch (_: Throwable) {
            null
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
            while (pos < src.length && (src[pos] == ' ' || src[pos] == '\t' || src[pos] == '\r' || src[pos] == '\n')) {
                pos++
            }
        }

        fun parseValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw IllegalArgumentException("max depth exceeded")
            skipWhitespace()
            if (isAtEnd()) throw IllegalArgumentException("unexpected end")
            return when (src[pos]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("unexpected token")
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            pos++
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (!isAtEnd() && src[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (isAtEnd() || src[pos] != '"') throw IllegalArgumentException("expected key")
                val key = parseString()
                skipWhitespace()
                if (isAtEnd() || src[pos] != ':') throw IllegalArgumentException("expected colon")
                pos++
                map[key] = parseValue(depth)
                skipWhitespace()
                if (isAtEnd()) throw IllegalArgumentException("unclosed object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("invalid object delimiter")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            pos++
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (!isAtEnd() && src[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue(depth))
                skipWhitespace()
                if (isAtEnd()) throw IllegalArgumentException("unclosed array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("invalid array delimiter")
                }
            }
        }

        private fun parseString(): String {
            pos++
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        if (pos >= src.length) throw IllegalArgumentException("truncated escape")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw IllegalArgumentException("truncated unicode")
                                val code = src.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("invalid unicode")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("invalid escape: $esc")
                        }
                    }
                    ch.code < 0x20 -> throw IllegalArgumentException("control char in string")
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("unclosed string")
        }

        private fun parseLiteral(token: String, value: Any?): Any? {
            if (!src.startsWith(token, pos)) throw IllegalArgumentException("invalid literal")
            pos += token.length
            return value
        }

        private fun parseNumber(): Number {
            val start = pos
            if (src[pos] == '-') pos++
            if (isAtEnd()) throw IllegalArgumentException("invalid number")
            if (src[pos] == '0') {
                pos++
            } else if (src[pos] in '1'..'9') {
                while (!isAtEnd() && src[pos] in '0'..'9') pos++
            } else {
                throw IllegalArgumentException("invalid number")
            }
            var floating = false
            if (!isAtEnd() && src[pos] == '.') {
                floating = true
                pos++
                if (isAtEnd() || src[pos] !in '0'..'9') throw IllegalArgumentException("invalid fraction")
                while (!isAtEnd() && src[pos] in '0'..'9') pos++
            }
            if (!isAtEnd() && (src[pos] == 'e' || src[pos] == 'E')) {
                floating = true
                pos++
                if (!isAtEnd() && (src[pos] == '+' || src[pos] == '-')) pos++
                if (isAtEnd() || src[pos] !in '0'..'9') throw IllegalArgumentException("invalid exponent")
                while (!isAtEnd() && src[pos] in '0'..'9') pos++
            }
            val rawNum = src.substring(start, pos)
            return if (floating) {
                rawNum.toDoubleOrNull() ?: throw IllegalArgumentException("number overflow")
            } else {
                rawNum.toLongOrNull() ?: rawNum.toDoubleOrNull()
                    ?: throw IllegalArgumentException("number overflow")
            }
        }
    }
}
