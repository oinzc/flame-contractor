package io.github.oinzc.flamecontractor.execution

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountSwitchGuardTest {

    @Test
    fun requiresUserChoiceWhenSelfUidChangesWithExistingTargets() {
        val decision = AccountSwitchGuard.checkLoginIdentity(
            prevUid = "sec_uid_account_a",
            newUid = "sec_uid_account_b",
            prevNick = "测试账号A",
            newNick = "测试账号A",
            hasTargets = true,
        )

        assertEquals(SwitchDecision.REQUIRE_USER_CHOICE, decision)
    }

    @Test
    fun allowsSeamlessReloginWhenSameSelfUidMatches() {
        val decision = AccountSwitchGuard.checkLoginIdentity(
            prevUid = "sec_uid_account_a",
            newUid = "sec_uid_account_a",
            prevNick = "旧昵称",
            newNick = "改名后的新昵称",
            hasTargets = true,
        )

        assertEquals(SwitchDecision.SAME_ACCOUNT_OR_FIRST_BIND, decision)
    }

    @Test
    fun allowsSwitchWhenNoTargetsOrNoPreviousIdentity() {
        val noTargets = AccountSwitchGuard.checkLoginIdentity(
            prevUid = "sec_uid_account_a",
            newUid = "sec_uid_account_b",
            prevNick = "账号A",
            newNick = "账号B",
            hasTargets = false,
        )
        val firstBind = AccountSwitchGuard.checkLoginIdentity(
            prevUid = null,
            newUid = "sec_uid_account_b",
            prevNick = null,
            newNick = "账号B",
            hasTargets = true,
        )

        assertEquals(SwitchDecision.SAME_ACCOUNT_OR_FIRST_BIND, noTargets)
        assertEquals(SwitchDecision.SAME_ACCOUNT_OR_FIRST_BIND, firstBind)
    }

    @Test
    fun fallsBackToNormalizedNicknameWhenUidMissing() {
        val sameNormalized = AccountSwitchGuard.checkLoginIdentity(
            prevUid = null,
            newUid = null,
            prevNick = "测试　账号  Alpha",
            newNick = " 测试 账号 Alpha ",
            hasTargets = true,
        )
        val differentNickname = AccountSwitchGuard.checkLoginIdentity(
            prevUid = "sec_uid_account_a",
            newUid = null,
            prevNick = "测试账号A",
            newNick = "测试账号B",
            hasTargets = true,
        )

        assertEquals(SwitchDecision.SAME_ACCOUNT_OR_FIRST_BIND, sameNormalized)
        assertEquals(SwitchDecision.REQUIRE_USER_CHOICE, differentNickname)
    }
}
