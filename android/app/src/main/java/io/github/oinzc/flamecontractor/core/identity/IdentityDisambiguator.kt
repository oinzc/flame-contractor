package io.github.oinzc.flamecontractor.core.identity

import java.text.Normalizer

/**
 * 抖音用户信息接口或 IndexedDB 本地缓存中观察到的单条用户身份快照。
 */
data class IdentityRecord(
    val platformUserId: String,
    val platformHandle: String? = null,
    val platformHandleSource: String? = null,
    val nickname: String? = null,
    val remarkName: String? = null,
)

enum class MatchStatus {
    BOUND,
    AMBIGUOUS,
    NOT_OBSERVED,
}

data class IdentityMatch(
    val status: MatchStatus,
    val platformUserId: String? = null,
    val platformHandle: String? = null,
    val platformNickname: String? = null,
    val source: String? = null,
    val candidateCount: Int = 0,
    val detail: String? = null,
)

data class ConversationMatch(
    val status: MatchStatus,
    val platformConversationId: String? = null,
    val conversationType: String? = null,
    val candidateCount: Int = 0,
    val detail: String? = null,
)

/**
 * 严格身份消歧与右侧标题归一化工具，与桌面端 `douyin_chat.py` 保持零语义偏差：
 * - 显示名统一经 NFKC + 连续空白折叠 + trim 标准化；
 * - 同一规范化显示名对应多个不同平台用户 ID 或群会话 ID 时必须返回 [MatchStatus.AMBIGUOUS]；
 * - 绝不基于模糊包含猜测目标，也不在诊断 detail 中拼接任何真实昵称或 ID。
 */
object IdentityDisambiguator {
    private val WHITESPACE_REGEX = Regex("""[\s\p{Z}\u0085]+""")
    private val UNREAD_COUNT_SUFFIX_REGEX = Regex("""\([0-9]+\)$""")

    fun normalizeLabel(label: String): String {
        if (label.isEmpty()) return ""
        val nfkc = Normalizer.normalize(label, Normalizer.Form.NFKC)
        return WHITESPACE_REGEX.replace(nfkc, " ").trim { it == ' ' }
    }

    fun stripUnreadSuffix(rawRightTitle: String?): String? {
        if (rawRightTitle.isNullOrEmpty()) return null
        for (line in rawRightTitle.lineSequence()) {
            val primary = line.trimUnicodeWhitespace()
            if (primary.isNotEmpty()) {
                val stripped = UNREAD_COUNT_SUFFIX_REGEX.replace(primary, "").trimUnicodeWhitespace()
                return stripped.ifEmpty { null }
            }
        }
        return null
    }

    fun matchUser(label: String, records: Collection<IdentityRecord>): IdentityMatch {
        val normalized = normalizeLabel(label)
        if (normalized.isEmpty()) {
            return IdentityMatch(
                status = MatchStatus.NOT_OBSERVED,
                detail = "本次扫描未观察到带此名称的用户身份数据，未写入稳定身份",
            )
        }

        val recordsById = LinkedHashMap<String, IdentityRecord>()
        val matchedIds = LinkedHashSet<String>()

        for (raw in records) {
            val userId = nonBlank(raw.platformUserId) ?: continue
            val incoming = IdentityRecord(
                platformUserId = userId,
                platformHandle = nonBlank(raw.platformHandle),
                platformHandleSource = nonBlank(raw.platformHandleSource),
                nickname = nonBlank(raw.nickname),
                remarkName = nonBlank(raw.remarkName),
            )
            val previous = recordsById[userId]
            recordsById[userId] = if (previous == null) {
                incoming
            } else {
                IdentityRecord(
                    platformUserId = userId,
                    platformHandle = incoming.platformHandle ?: previous.platformHandle,
                    platformHandleSource = incoming.platformHandleSource ?: previous.platformHandleSource,
                    nickname = incoming.nickname ?: previous.nickname,
                    remarkName = incoming.remarkName ?: previous.remarkName,
                )
            }

            if (matchesNormalizedLabel(incoming, normalized)) {
                matchedIds.add(userId)
            }
        }

        if (matchedIds.isEmpty()) {
            return IdentityMatch(
                status = MatchStatus.NOT_OBSERVED,
                detail = "本次扫描未观察到带此名称的用户身份数据，未写入稳定身份",
            )
        }
        if (matchedIds.size > 1) {
            return IdentityMatch(
                status = MatchStatus.AMBIGUOUS,
                candidateCount = matchedIds.size,
                detail = "同名对应多个平台用户，已阻止自动绑定",
            )
        }

        val platformUserId = matchedIds.first()
        val record = recordsById[platformUserId]
        val source = record?.let { resolveMatchSource(it, normalized) }
        val detail = if (source != null) {
            "通过${identitySourceLabel(source)}唯一匹配"
        } else {
            "已唯一匹配平台身份"
        }

        return IdentityMatch(
            status = MatchStatus.BOUND,
            platformUserId = platformUserId,
            platformHandle = record?.platformHandle,
            platformNickname = record?.nickname,
            source = source,
            candidateCount = 1,
            detail = detail,
        )
    }

    fun matchGroup(label: String, records: Collection<ConversationRecord>): ConversationMatch {
        val normalized = normalizeLabel(label)
        if (normalized.isEmpty()) {
            return ConversationMatch(
                status = MatchStatus.NOT_OBSERVED,
                detail = "本次扫描未观察到带唯一 ID 的群会话数据，未猜测绑定",
            )
        }

        val recordsById = LinkedHashMap<String, ConversationRecord>()
        val matchedIds = LinkedHashSet<String>()

        for (record in records) {
            val conversationId = nonBlank(record.platformConversationId) ?: continue
            val title = nonBlank(record.title) ?: continue
            recordsById[conversationId] = record
            if (normalizeLabel(title) == normalized) {
                matchedIds.add(conversationId)
            }
        }

        if (matchedIds.isEmpty()) {
            return ConversationMatch(
                status = MatchStatus.NOT_OBSERVED,
                detail = "本次扫描未观察到带唯一 ID 的群会话数据，未猜测绑定",
            )
        }
        if (matchedIds.size > 1) {
            return ConversationMatch(
                status = MatchStatus.AMBIGUOUS,
                candidateCount = matchedIds.size,
                detail = "同名对应多个群会话，已阻止自动绑定",
            )
        }

        val conversationId = matchedIds.first()
        val matchedRecord = recordsById[conversationId]
        return ConversationMatch(
            status = MatchStatus.BOUND,
            platformConversationId = conversationId,
            conversationType = nonBlank(matchedRecord?.conversationType) ?: "group",
            candidateCount = 1,
            detail = "通过群会话 ID 唯一匹配群聊",
        )
    }

    private fun matchesNormalizedLabel(record: IdentityRecord, normalized: String): Boolean {
        val candidates = arrayOf(record.remarkName, record.nickname, record.platformHandle)
        for (value in candidates) {
            if (value != null && normalizeLabel(value) == normalized) {
                return true
            }
        }
        return false
    }

    private fun resolveMatchSource(record: IdentityRecord, normalized: String): String? {
        if (record.remarkName != null && normalizeLabel(record.remarkName) == normalized) {
            return "remark_name"
        }
        if (record.nickname != null && normalizeLabel(record.nickname) == normalized) {
            return "nickname"
        }
        if (record.platformHandle != null && normalizeLabel(record.platformHandle) == normalized) {
            return record.platformHandleSource
        }
        return null
    }

    private fun identitySourceLabel(source: String): String = when (source) {
        "remark_name" -> "备注名"
        "nickname" -> "平台昵称"
        "unique_id", "short_id" -> "抖音号"
        else -> "平台身份"
    }

    private fun nonBlank(value: String?): String? =
        value?.trimUnicodeWhitespace()?.takeIf { it.isNotEmpty() }

    private fun String.trimUnicodeWhitespace(): String =
        trim { ch -> ch.isWhitespace() || Character.isSpaceChar(ch) || ch == '\u0085' }
}
