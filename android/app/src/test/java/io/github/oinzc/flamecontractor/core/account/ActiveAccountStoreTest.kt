package io.github.oinzc.flamecontractor.core.account

import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveAccountStoreTest {

    @Test
    fun selectAccountTrimsUpdatesAndPersists() {
        val persisted = mutableListOf<String>()
        val store = ActiveAccountStore(
            initialAccountId = "   ",
            onPersist = { persisted += it },
        )
        assertEquals(DEFAULT_ACCOUNT_ID, store.currentAccountId())

        store.selectAccount(" acct_2 ")
        assertEquals("acct_2", store.currentAccountId())
        assertEquals(listOf("acct_2"), persisted)

        store.selectAccount("   ")
        assertEquals("acct_2", store.currentAccountId())
        assertEquals(listOf("acct_2"), persisted)
    }

    @Test
    fun reconcileWithAvailableAccountsFallsBackWhenDeletedOrEmpty() {
        val persisted = mutableListOf<String>()
        val store = ActiveAccountStore(
            initialAccountId = "acct_deleted",
            onPersist = { persisted += it },
        )

        val fallback = store.reconcileWithAvailableAccounts(listOf(DEFAULT_ACCOUNT_ID, "acct_2"))
        assertEquals(DEFAULT_ACCOUNT_ID, fallback)
        assertEquals(DEFAULT_ACCOUNT_ID, store.currentAccountId())
        assertEquals(listOf(DEFAULT_ACCOUNT_ID), persisted)

        val stayed = store.reconcileWithAvailableAccounts(listOf(DEFAULT_ACCOUNT_ID, "acct_2"))
        assertEquals(DEFAULT_ACCOUNT_ID, stayed)
        assertEquals(listOf(DEFAULT_ACCOUNT_ID), persisted)

        store.selectAccount("acct_2")
        val resetToDefault = store.reconcileWithAvailableAccounts(emptyList())
        assertEquals(DEFAULT_ACCOUNT_ID, resetToDefault)
        assertEquals(DEFAULT_ACCOUNT_ID, store.currentAccountId())
    }

    @Test
    fun profileNameMappingIsolatesDefaultAndSanitizesCustomIds() {
        assertEquals(
            WebViewProfileRegistry.DEFAULT_PROFILE_NAME,
            WebViewProfileRegistry.profileNameForAccount(""),
        )
        assertEquals(
            WebViewProfileRegistry.DEFAULT_PROFILE_NAME,
            WebViewProfileRegistry.profileNameForAccount(DEFAULT_ACCOUNT_ID),
        )
        assertEquals(
            "acct_sub_account-01",
            WebViewProfileRegistry.profileNameForAccount(" sub:account-01 "),
        )
    }
}
