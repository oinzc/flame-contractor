package io.github.oinzc.flamecontractor.core.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigInteger

class DouyinProtobufParserTest {

    @Test
    fun parsesValidGroupProtobufWithMatchingVarintId() {
        val payload = concat(
            bytesField(50, groupRecord("7000000000000000001", "测试群聊A")),
            bytesField(
                50,
                bytesField(12, groupRecord("7000000000000000002", "测试小队&伙伴")),
            ),
        )

        val records = DouyinProtobufParser.parseGroupConversations(payload)

        assertEquals(
            listOf(
                ConversationRecord(
                    platformConversationId = "7000000000000000001",
                    title = "测试群聊A",
                    conversationType = "group",
                ),
                ConversationRecord(
                    platformConversationId = "7000000000000000002",
                    title = "测试小队&伙伴",
                    conversationType = "group",
                ),
            ),
            records,
        )
    }

    @Test
    fun rejectsCorruptedOrMismatchedField1Field2Protobuf() {
        val mismatchedId = concat(
            bytesField(1, "7000000000000000001".toByteArray(Charsets.US_ASCII)),
            varintField(2, BigInteger("7000000000000000999")),
            varintField(3, 2L),
            bytesField(5, "不匹配群".toByteArray(Charsets.UTF_8)),
        )
        val missingField2 = concat(
            bytesField(1, "7000000000000000002".toByteArray(Charsets.US_ASCII)),
            varintField(3, 2L),
            bytesField(5, "缺字段群".toByteArray(Charsets.UTF_8)),
        )
        val truncatedRecord = groupRecord("7000000000000000003", "被截断群").copyOfRange(0, 18)
        val badWireTypeGroup = concat(
            varint((1 shl 3) or 3), // start-group wire type 3
            varint((1 shl 3) or 4), // end-group wire type 4
            groupRecord("7000000000000000004", "带组编码群"),
        )
        val zeroFieldNumber = concat(
            varint(0L),
            groupRecord("7000000000000000005", "零号字段群"),
        )
        val overlongVarint = byteArrayOf(
            0x08, // field 1 varint
            0x81.toByte(), 0x81.toByte(), 0x81.toByte(), 0x81.toByte(), 0x81.toByte(),
            0x81.toByte(), 0x81.toByte(), 0x81.toByte(), 0x81.toByte(), 0x81.toByte(),
            0x01,
        )

        val wrappedPayload = concat(
            bytesField(50, mismatchedId),
            bytesField(50, missingField2),
            bytesField(50, truncatedRecord),
            bytesField(50, badWireTypeGroup),
            bytesField(50, zeroFieldNumber),
            bytesField(50, overlongVarint),
        )

        assertTrue(DouyinProtobufParser.parseGroupConversations(wrappedPayload).isEmpty())
        assertTrue(DouyinProtobufParser.parseGroupConversations(truncatedRecord).isEmpty())
        assertTrue(DouyinProtobufParser.parseGroupConversations(badWireTypeGroup).isEmpty())
        assertTrue(DouyinProtobufParser.parseGroupConversations(ByteArray(0)).isEmpty())
    }

    @Test
    fun skipsNonGroupConversationType() {
        val payload = concat(
            bytesField(50, groupRecord("7000000000000000010", "单聊会话", kind = 1)),
            bytesField(50, groupRecord("7000000000000000011", "未知会话", kind = 3)),
            bytesField(50, groupRecord("7000000000000000012", "正式群聊", kind = 2)),
        )

        val records = DouyinProtobufParser.parseGroupConversations(payload)

        assertEquals(
            listOf(ConversationRecord("7000000000000000012", "正式群聊")),
            records,
        )
    }

    @Test
    fun enforcesMaxNestingDepthOf10() {
        val atDepth10 = nest(groupRecord("7000000000000000020", "十层嵌套群"), depth = 10)
        val atDepth11 = nest(groupRecord("7000000000000000021", "十一层超限群"), depth = 11)

        assertEquals(
            listOf(ConversationRecord("7000000000000000020", "十层嵌套群")),
            DouyinProtobufParser.parseGroupConversations(atDepth10),
        )
        assertTrue(DouyinProtobufParser.parseGroupConversations(atDepth11).isEmpty())
    }

    @Test
    fun validatesField1LengthBoundsAndUnsigned64BitVarint() {
        val fourteenDigits = groupRecord("12345678901234", "十四位过短")
        val fifteenDigits = groupRecord("123456789012345", "十五位边界群")
        val twentyDigitsUnsigned = groupRecord("10000000000000000001", "二十位无符号群")
        val nonDigitField1 = concat(
            bytesField(1, "700000000000000000a".toByteArray(Charsets.US_ASCII)),
            varintField(2, 7000000000000000000L),
            varintField(3, 2L),
            bytesField(5, "含字母编号".toByteArray(Charsets.UTF_8)),
        )
        val twentySixDigits = concat(
            bytesField(1, "12345678901234567890123456".toByteArray(Charsets.US_ASCII)),
            varintField(2, 123L),
            varintField(3, 2L),
            bytesField(5, "二十六位过长".toByteArray(Charsets.UTF_8)),
        )

        val payload = concat(
            bytesField(50, fourteenDigits),
            bytesField(50, fifteenDigits),
            bytesField(50, twentyDigitsUnsigned),
            bytesField(50, nonDigitField1),
            bytesField(50, twentySixDigits),
        )

        assertEquals(
            listOf(
                ConversationRecord("123456789012345", "十五位边界群"),
                ConversationRecord("10000000000000000001", "二十位无符号群"),
            ),
            DouyinProtobufParser.parseGroupConversations(payload),
        )
    }

    @Test
    fun validatesField5Utf8LengthTrimmingAndControlCharacters() {
        val exact80 = "群".repeat(80)
        val over80 = "群".repeat(81)
        val validTrimmed = groupRecord("7000000000000000030", "  \u3000中文 群名  ")
        val validMaxLen = groupRecord("7000000000000000031", exact80)
        val tooLong = groupRecord("7000000000000000032", over80)
        val blankTitle = groupRecord("7000000000000000033", "   \t  ")
        val embeddedNewline = groupRecord("7000000000000000034", "第一行\n第二行")
        val leadingNul = groupRecord("7000000000000000035", "\u0000前导控制符")
        val invalidUtf8 = concat(
            bytesField(1, "7000000000000000036".toByteArray(Charsets.US_ASCII)),
            varintField(2, 7000000000000000036L),
            varintField(3, 2L),
            bytesField(5, byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xE4.toByte())),
        )

        val payload = concat(
            bytesField(50, validTrimmed),
            bytesField(50, validMaxLen),
            bytesField(50, tooLong),
            bytesField(50, blankTitle),
            bytesField(50, embeddedNewline),
            bytesField(50, leadingNul),
            bytesField(50, invalidUtf8),
        )

        assertEquals(
            listOf(
                ConversationRecord("7000000000000000030", "中文 群名"),
                ConversationRecord("7000000000000000031", exact80),
            ),
            DouyinProtobufParser.parseGroupConversations(payload),
        )
    }

    @Test
    fun handlesFixed64Fixed32DeduplicatesExactPairsAndPreservesSameTitleDifferentIds() {
        val withFixedFields = concat(
            fixed64Field(9, 0x0102030405060708L),
            fixed32Field(10, 0x11223344),
            groupRecord("7000000000000000040", "同名测试群"),
        )
        val duplicateSameIdAndTitle = groupRecord("7000000000000000040", "同名测试群")
        val sameTitleDifferentId = groupRecord("7000000000000000041", "同名测试群")

        val payload = concat(
            bytesField(50, withFixedFields),
            bytesField(50, duplicateSameIdAndTitle),
            bytesField(50, sameTitleDifferentId),
        )

        assertEquals(
            listOf(
                ConversationRecord("7000000000000000040", "同名测试群"),
                ConversationRecord("7000000000000000041", "同名测试群"),
            ),
            DouyinProtobufParser.parseGroupConversations(payload),
        )
    }

    @Test
    fun parsesRootLevelRecordSingleCharTitleAndIgnoresCorruptSiblingField() {
        val rootSingleChar = groupRecord("7000000000000000050", "群")
        assertEquals(
            listOf(ConversationRecord("7000000000000000050", "群")),
            DouyinProtobufParser.parseGroupConversations(rootSingleChar),
        )

        val corruptSiblingWithGroupWire = concat(
            varint((2 shl 3) or 3),
            varint((2 shl 3) or 4),
        )
        val outerWithCorruptAndValid = concat(
            bytesField(10, corruptSiblingWithGroupWire),
            bytesField(11, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())),
            bytesField(50, groupRecord("7000000000000000051", "A")),
        )

        assertEquals(
            listOf(ConversationRecord("7000000000000000051", "A")),
            DouyinProtobufParser.parseGroupConversations(outerWithCorruptAndValid),
        )
    }

    @Test
    fun parsesColonPrefixedGroupConversationIdAndRejectsSingleChatPrefix() {
        val validColonGroup = concat(
            bytesField(1, "0:2:7310000000000000099".toByteArray(Charsets.US_ASCII)),
            varintField(2, BigInteger("7310000000000000099")),
            varintField(3, 2L),
            bytesField(5, "车友群测试".toByteArray(Charsets.UTF_8)),
        )
        val singleChatPrefix = concat(
            bytesField(1, "0:1:7310000000000000098".toByteArray(Charsets.US_ASCII)),
            varintField(2, BigInteger("7310000000000000098")),
            varintField(3, 2L),
            bytesField(5, "伪装单聊前缀".toByteArray(Charsets.UTF_8)),
        )
        val mismatchedColonVarint = concat(
            bytesField(1, "0:2:7310000000000000097".toByteArray(Charsets.US_ASCII)),
            varintField(2, BigInteger("7310000000000000000")),
            varintField(3, 2L),
            bytesField(5, "不匹配前缀群".toByteArray(Charsets.UTF_8)),
        )

        val payload = concat(
            bytesField(50, validColonGroup),
            bytesField(50, singleChatPrefix),
            bytesField(50, mismatchedColonVarint),
        )

        assertEquals(
            listOf(ConversationRecord("7310000000000000099", "车友群测试")),
            DouyinProtobufParser.parseGroupConversations(payload),
        )
    }

    private fun nest(inner: ByteArray, depth: Int): ByteArray {
        var current = inner
        repeat(depth) {
            current = bytesField(50, current)
        }
        return current
    }

    private fun groupRecord(
        conversationId: String,
        title: String,
        kind: Long = 2L,
    ): ByteArray = concat(
        bytesField(1, conversationId.toByteArray(Charsets.US_ASCII)),
        varintField(2, BigInteger(conversationId)),
        varintField(3, kind),
        bytesField(5, title.toByteArray(Charsets.UTF_8)),
    )

    private fun bytesField(fieldNumber: Int, payload: ByteArray): ByteArray = concat(
        varint((fieldNumber.toLong() shl 3) or 2L),
        varint(payload.size.toLong()),
        payload,
    )

    private fun varintField(fieldNumber: Int, value: Long): ByteArray = concat(
        varint(fieldNumber.toLong() shl 3),
        varint(value),
    )

    private fun varintField(fieldNumber: Int, value: BigInteger): ByteArray = concat(
        varint(fieldNumber.toLong() shl 3),
        varint(value),
    )

    private fun fixed64Field(fieldNumber: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varint((fieldNumber.toLong() shl 3) or 1L))
        for (shift in 0 until 64 step 8) {
            out.write(((value ushr shift) and 0xFFL).toInt())
        }
        return out.toByteArray()
    }

    private fun fixed32Field(fieldNumber: Int, value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varint((fieldNumber.toLong() shl 3) or 5L))
        for (shift in 0 until 32 step 8) {
            out.write((value ushr shift) and 0xFF)
        }
        return out.toByteArray()
    }

    private fun varint(value: Long): ByteArray = varint(BigInteger.valueOf(value))

    private fun varint(value: BigInteger): ByteArray {
        val out = ByteArrayOutputStream()
        var remaining = value
        val mask = BigInteger.valueOf(0x7FL)
        val threshold = BigInteger.valueOf(0x80L)
        while (remaining >= threshold) {
            out.write(remaining.and(mask).toInt() or 0x80)
            remaining = remaining.shiftRight(7)
        }
        out.write(remaining.toInt())
        return out.toByteArray()
    }

    private fun concat(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (chunk in chunks) {
            out.write(chunk)
        }
        return out.toByteArray()
    }
}
