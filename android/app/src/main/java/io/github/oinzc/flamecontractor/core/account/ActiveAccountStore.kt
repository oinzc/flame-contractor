package io.github.oinzc.flamecontractor.core.account

import android.content.Context
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ActiveAccountStore(
    initialAccountId: String = DEFAULT_ACCOUNT_ID,
    private val onPersist: (String) -> Unit = {},
) {
    private val _activeAccountId = MutableStateFlow(
        initialAccountId.trim().ifEmpty { DEFAULT_ACCOUNT_ID },
    )
    val activeAccountId: StateFlow<String> = _activeAccountId.asStateFlow()

    fun currentAccountId(): String = _activeAccountId.value

    fun selectAccount(accountId: String) {
        val clean = accountId.trim().takeIf { it.isNotEmpty() } ?: return
        if (_activeAccountId.value != clean) {
            _activeAccountId.value = clean
        }
        onPersist(clean)
    }

    fun reconcileWithAvailableAccounts(accountIds: List<String>): String {
        val current = _activeAccountId.value
        val next = when {
            current in accountIds -> current
            accountIds.isNotEmpty() -> accountIds.first()
            else -> DEFAULT_ACCOUNT_ID
        }
        if (next != current) {
            _activeAccountId.value = next
            onPersist(next)
        }
        return next
    }

    companion object {
        private const val PREFS_NAME = "flame_active_account"
        private const val KEY_ACTIVE_ACCOUNT_ID = "active_account_id"

        fun fromContext(context: Context): ActiveAccountStore {
            val prefs = (context.applicationContext ?: context)
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_ACTIVE_ACCOUNT_ID, DEFAULT_ACCOUNT_ID)
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_ACCOUNT_ID
            return ActiveAccountStore(
                initialAccountId = saved,
                onPersist = { id ->
                    prefs.edit().putString(KEY_ACTIVE_ACCOUNT_ID, id).apply()
                },
            )
        }
    }
}
