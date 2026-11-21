package io.github.oinzc.flamecontractor.core.identity

import java.math.BigInteger

/**
 * 抖音会话初始化 protobuf 响应中解析出的群聊身份记录。
 */
data class ConversationRecord(
    val platformConversationId: String,
    val title: String,
    val conversationType: String = "group",
)

/**
 * 无外部 proto 依赖的轻量 wire-format 解码器，用于从 `/v1/message/get_message_by_init`
 * 二进制响应中提取群会话唯一 ID 与展示名称。
 *
 * 严格约束：
 * - 最大嵌套深度 10，防止深层恶意或异常嵌套消耗栈空间；
 * - 遇到 group wire type（3/4）或未知 wire type 时拒绝当前消息并返回空，宁可不绑定；
 * - 严格要求 field 1（15–25 位 ASCII 数字串）== field 2（varint）、field 3 == 2（群会话）、
 *   field 5 为 1..80 字符的无控制字符有效 UTF-8 文本。
 */
object DouyinProtobufParser {
    private const val MAX_NESTING_DEPTH = 10
    private const val MAX_NESTED_BYTES = 4_000_000
    private const val MIN_CONVERSATION_ID_DIGITS = 15
    private const val MAX_CONVERSATION_ID_DIGITS = 25
    private const val GROUP_CONVERSATION_TYPE = 2L
    private const val MAX_TITLE_CODE_POINTS = 80

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    fun parseGroupConversations(data: ByteArray): List<ConversationRecord> {
        if (data.isEmpty()) return emptyList()
        val records = mutableListOf<ConversationRecord>()
        val seen = HashSet<Pair<String, String>>()
        visitMessage(
            data = data,
            start = 0,
            end = data.size,
            depth = 0,
            records = records,
            seen = seen,
        )
        return records
    }

    private fun visitMessage(
        data: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        records: MutableList<ConversationRecord>,
        seen: MutableSet<Pair<String, String>>,
    ) {
        val decoded = decodeMessage(data, start, end) ?: return

        if (
            decoded.hasGroupType &&
            decoded.field1Slices.isNotEmpty() &&
            decoded.field2Varints.isNotEmpty() &&
            decoded.field5Slices.isNotEmpty()
        ) {
            val conversationId = firstValidConversationId(data, decoded.field1Slices)
            if (conversationId != null) {
                val expectedId = BigInteger(conversationId)
                val idMatched = decoded.field2Varints.any { it.toBigInteger() == expectedId }
                if (idMatched) {
                    val title = firstValidTitle(data, decoded.field5Slices)
                    if (title != null && seen.add(conversationId to title)) {
                        records.add(
                            ConversationRecord(
                                platformConversationId = conversationId,
                                title = title,
                            ),
                        )
                    }
                }
            }
        }

        if (depth >= MAX_NESTING_DEPTH) return
        for (packed in decoded.nestedSlices) {
            visitMessage(
                data = data,
                start = rangeStart(packed),
                end = rangeEnd(packed),
                depth = depth + 1,
                records = records,
                seen = seen,
            )
        }
    }

    private fun decodeMessage(data: ByteArray, start: Int, end: Int): DecodedMessage? {
        var offset = start
        val field1Slices = mutableListOf<Long>()
        val field2Varints = mutableListOf<VarintValue>()
        var hasGroupType = false
        val field5Slices = mutableListOf<Long>()
        val nestedSlices = mutableListOf<Long>()

        while (offset < end) {
            val key = readVarint(data, offset, end) ?: return null
            offset = key.nextOffset
            val wireType = (key.low63 and 0x7L).toInt()
            val fieldNumberPositive = key.high7 > 0 || (key.low63 ushr 3) > 0L
            if (!fieldNumberPositive) {
                return null
            }
            val smallFieldNumber = if (key.high7 == 0) (key.low63 ushr 3) else -1L

            when (wireType) {
                WIRE_VARINT -> {
                    val value = readVarint(data, offset, end) ?: return null
                    offset = value.nextOffset
                    if (smallFieldNumber == 2L) {
                        field2Varints.add(VarintValue(value.low63, value.high7))
                    } else if (
                        smallFieldNumber == 3L &&
                        value.high7 == 0 &&
                        value.low63 == GROUP_CONVERSATION_TYPE
                    ) {
                        hasGroupType = true
                    }
                }
                WIRE_FIXED64 -> {
                    if (end - offset < 8) return null
                    offset += 8
                }
                WIRE_LENGTH_DELIMITED -> {
                    val lengthResult = readVarint(data, offset, end) ?: return null
                    offset = lengthResult.nextOffset
                    val remaining = (end - offset).toLong()
                    if (lengthResult.high7 != 0 || lengthResult.low63 > remaining) {
                        return null
                    }
                    val length = lengthResult.low63.toInt()
                    val sliceStart = offset
                    val sliceEnd = offset + length
                    offset = sliceEnd
                    if (smallFieldNumber == 1L) {
                        field1Slices.add(packRange(sliceStart, sliceEnd))
                    } else if (smallFieldNumber == 5L) {
                        field5Slices.add(packRange(sliceStart, sliceEnd))
                    }
                    if (length in 2..MAX_NESTED_BYTES) {
                        nestedSlices.add(packRange(sliceStart, sliceEnd))
                    }
                }
                WIRE_FIXED32 -> {
                    if (end - offset < 4) return null
                    offset += 4
                }
                else -> {
                    // 不解析 group wire type (3/4) 或未知类型，遇到即安全放弃该消息。
                    return null
                }
            }
        }

        return DecodedMessage(
            field1Slices = field1Slices,
            field2Varints = field2Varints,
            hasGroupType = hasGroupType,
            field5Slices = field5Slices,
            nestedSlices = nestedSlices,
        )
    }

    private fun readVarint(data: ByteArray, start: Int, end: Int): VarintRead? {
        var offset = start
        var low63 = 0L
        var shift = 0
        while (offset < end && shift < 70) {
            val byte = data[offset].toInt() and 0xFF
            offset += 1
            if (shift < 63) {
                low63 = low63 or ((byte and 0x7F).toLong() shl shift)
                if ((byte and 0x80) == 0) {
                    return VarintRead(low63 = low63, high7 = 0, nextOffset = offset)
                }
            } else {
                if ((byte and 0x80) != 0) {
                    return null
                }
                return VarintRead(low63 = low63, high7 = byte and 0x7F, nextOffset = offset)
            }
            shift += 7
        }
        return null
    }

    private fun firstValidConversationId(data: ByteArray, slices: List<Long>): String? {
        for (packed in slices) {
            val start = rangeStart(packed)
            val end = rangeEnd(packed)
            val length = end - start
            if (length !in MIN_CONVERSATION_ID_DIGITS..MAX_CONVERSATION_ID_DIGITS) continue
            var allAsciiDigits = true
            for (index in start until end) {
                val b = data[index].toInt() and 0xFF
                if (b < '0'.code || b > '9'.code) {
                    allAsciiDigits = false
                    break
                }
            }
            if (allAsciiDigits) {
                return String(data, start, length, Charsets.US_ASCII)
            }
        }
        return null
    }

    private fun firstValidTitle(data: ByteArray, slices: List<Long>): String? {
        for (packed in slices) {
            val title = decodeProtoText(data, rangeStart(packed), rangeEnd(packed))
            if (title != null) return title
        }
        return null
    }

    private fun decodeProtoText(data: ByteArray, start: Int, end: Int): String? {
        val raw = try {
            data.decodeToString(
                startIndex = start,
                endIndex = end,
                throwOnInvalidSequence = true,
            )
        } catch (_: CharacterCodingException) {
            return null
        }
        val text = raw.trim { ch -> ch.isWhitespace() || Character.isSpaceChar(ch) || ch == '\u0085' }
        if (text.isEmpty()) return null
        if (text.codePointCount(0, text.length) > MAX_TITLE_CODE_POINTS) return null
        for (ch in text) {
            if (ch.code < 32 || Character.isISOControl(ch)) {
                return null
            }
        }
        return text
    }

    private fun packRange(start: Int, end: Int): Long =
        (start.toLong() shl 32) or (end.toLong() and 0xFFFF_FFFFL)

    private fun rangeStart(packed: Long): Int = (packed ushr 32).toInt()

    private fun rangeEnd(packed: Long): Int = (packed and 0xFFFF_FFFFL).toInt()

    private class VarintRead(
        val low63: Long,
        val high7: Int,
        val nextOffset: Int,
    )

    private class VarintValue(
        val low63: Long,
        val high7: Int,
    ) {
        fun toBigInteger(): BigInteger =
            if (high7 == 0) {
                BigInteger.valueOf(low63)
            } else {
                BigInteger.valueOf(low63).or(BigInteger.valueOf(high7.toLong()).shiftLeft(63))
            }
    }

    private class DecodedMessage(
        val field1Slices: List<Long>,
        val field2Varints: List<VarintValue>,
        val hasGroupType: Boolean,
        val field5Slices: List<Long>,
        val nestedSlices: List<Long>,
    )
}
