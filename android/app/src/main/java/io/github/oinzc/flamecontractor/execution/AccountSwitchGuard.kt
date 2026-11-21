package io.github.oinzc.flamecontractor.execution

import io.github.oinzc.flamecontractor.core.identity.IdentityDisambiguator

enum class SwitchDecision {
    SAME_ACCOUNT_OR_FIRST_BIND,
    REQUIRE_USER_CHOICE,
}

/**
 * 扫码登录后换号检测守卫（对齐设计规范 §6.5）：
 * 防止切换登录其他抖音号后拿着旧号的 `platform_user_id` / `platform_conversation_id` 盲发。
 */
object AccountSwitchGuard {

    fun checkLoginIdentity(
        prevUid: String?,
        newUid: String?,
        prevNick: String?,
        newNick: String?,
        hasTargets: Boolean,
    ): SwitchDecision {
        if (!hasTargets) {
            return SwitchDecision.SAME_ACCOUNT_OR_FIRST_BIND
        }

        val cleanPrevUid = prevUid?.trim()?.takeIf { it.isNotEmpty() }
        val cleanNewUid = newUid?.trim()?.takeIf { it.isNotEmpty() }
        val cleanPrevNick = IdentityDisambiguator.normalizeLabel(prevNick.orEmpty()).takeIf { it.isNotEmpty() }
        val cleanNewNick = IdentityDisambiguator.normalizeLabel(newNick.orEmpty()).takeIf { it.isNotEmpty() }

        if (cleanPrevUid == null && cleanPrevNick == null) {
            return SwitchDecision.SAME_ACCOUNT_OR_FIRST_BIND
        }

        if (cleanPrevUid != null && cleanNewUid != null) {
            return if (cleanPrevUid == cleanNewUid) {
                SwitchDecision.SAME_ACCOUNT_OR_FIRST_BIND
            } else {
                SwitchDecision.REQUIRE_USER_CHOICE
            }
        }

        if (cleanPrevNick != null && cleanNewNick != null) {
            return if (cleanPrevNick == cleanNewNick) {
                SwitchDecision.SAME_ACCOUNT_OR_FIRST_BIND
            } else {
                SwitchDecision.REQUIRE_USER_CHOICE
            }
        }

        return SwitchDecision.SAME_ACCOUNT_OR_FIRST_BIND
    }
}
