package io.github.oinzc.flamecontractor.core.account

import androidx.room.withTransaction
import io.github.oinzc.flamecontractor.core.storage.TemplateImageStorage
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AccountManager(
    private val database: FlameDatabase,
    private val activeAccountStore: ActiveAccountStore,
    private val imageStore: TemplateImageStorage,
    private val pendingProfileCleanupStore: PendingProfileCleanupStore,
    private val isMultiProfileSupported: () -> Boolean = { WebViewProfileRegistry.isMultiProfileSupported() },
    private val onClearAccountCookies: (suspend (String) -> Unit)? = null,
    private val onScheduleChanged: suspend () -> Unit = {},
    private val isAccountBusy: (String) -> Boolean = { false },
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun createAccount(displayName: String): AccountEntity {
        val cleanName = displayName.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("账号名称不能为空")
        if (!isMultiProfileSupported()) {
            throw IllegalStateException("当前系统 WebView 不支持多环境隔离，无法添加新抖音号")
        }
        val nowMs = clockMillis()
        val existing = database.accountDao().listAll()
        if (existing.any { it.displayName.equals(cleanName, ignoreCase = true) }) {
            throw IllegalArgumentException("已存在同名账号「$cleanName」")
        }

        val newAccount = AccountEntity(
            id = UUID.randomUUID().toString(),
            displayName = cleanName,
            timezone = ZoneId.systemDefault().id,
            createdAt = nowMs,
            updatedAt = nowMs,
        )
        database.withTransaction {
            database.accountDao().upsert(newAccount)
            FlameDatabase.seedDefaultTemplatesForAccount(
                database = database,
                accountId = newAccount.id,
                nowEpochMs = nowMs,
            )
        }
        activeAccountStore.selectAccount(newAccount.id)
        onScheduleChanged()
        return newAccount
    }

    suspend fun renameAccount(accountId: String, displayName: String): AccountEntity {
        val cleanName = displayName.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("账号名称不能为空")
        val allAccounts = database.accountDao().listAll()
        val existing = allAccounts.firstOrNull { it.id == accountId }
            ?: throw IllegalArgumentException("账号不存在")
        if (allAccounts.any { it.id != accountId && it.displayName.equals(cleanName, ignoreCase = true) }) {
            throw IllegalArgumentException("已存在同名账号「$cleanName」")
        }
        val updated = existing.copy(
            displayName = cleanName,
            updatedAt = clockMillis(),
        )
        database.accountDao().update(updated)
        return updated
    }

    suspend fun setAccountEnabled(accountId: String, enabled: Boolean): AccountEntity {
        val existing = database.accountDao().getAccount(accountId)
            ?: throw IllegalArgumentException("账号不存在")
        val updated = existing.copy(
            enabled = enabled,
            updatedAt = clockMillis(),
        )
        database.accountDao().update(updated)
        onScheduleChanged()
        return updated
    }

    suspend fun deleteAccount(accountId: String): Boolean {
        if (isAccountBusy(accountId)) {
            throw IllegalStateException("该账号正在执行后台任务，请等待完成后再删除")
        }
        val accounts = database.accountDao().listAll()
        if (accounts.none { it.id == accountId }) return false
        if (accounts.size <= 1) {
            throw IllegalStateException("至少保留 1 个抖音账号")
        }

        val candidatePaths = database.templateDao().listTemplates(accountId)
            .mapNotNull { it.imagePath?.trim()?.takeIf { p -> p.isNotEmpty() } }
            .toSet()

        database.withTransaction {
            database.sendJobDao().deleteAllByAccount(accountId)
            database.targetDao().deleteAllByAccount(accountId)
            database.scanRunDao().deleteAllByAccount(accountId)
            database.templateDao().deleteAllByAccount(accountId)
            database.accountDao().deleteById(accountId)
        }

        if (candidatePaths.isNotEmpty()) {
            val remainingRefs = database.templateDao().listAllReferencedImagePaths().toSet()
            for (path in candidatePaths) {
                imageStore.deleteIfUnreferenced(path, remainingRefs)
            }
        }

        if (onClearAccountCookies != null) {
            onClearAccountCookies.invoke(accountId)
        } else {
            defaultClearAccountCookies(accountId)
        }
        val profileName = WebViewProfileRegistry.profileNameForAccount(accountId)
        if (profileName != WebViewProfileRegistry.DEFAULT_PROFILE_NAME) {
            pendingProfileCleanupStore.enqueue(profileName)
        }

        val remainingIds = database.accountDao().listAll().map { it.id }
        activeAccountStore.reconcileWithAvailableAccounts(remainingIds)
        onScheduleChanged()
        return true
    }

    private suspend fun defaultClearAccountCookies(accountId: String) {
        suspendCancellableCoroutine { cont ->
            WebViewProfileRegistry.clearAccountCookies(accountId) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    companion object {
        private val DEFAULT_NUMBERED_NAME_REGEX = Regex("""^抖音号\s*\d+$""")
        private val DEFAULT_PLACEHOLDER_NAMES = setOf("默认抖音号", "默认账号")

        /**
         * 为新增抖音号生成不与现有任意账号冲突的最小编号默认名（`抖音号 2`、`抖音号 3`…）。
         */
        fun suggestNextDefaultAccountName(existingDisplayNames: Collection<String>): String {
            val occupied = existingDisplayNames
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            var index = 2
            while (true) {
                val candidate = "抖音号 $index"
                if (candidate.lowercase() !in occupied) {
                    return candidate
                }
                index += 1
            }
        }

        fun isDefaultPlaceholderName(displayName: String?): Boolean {
            val clean = displayName?.trim().orEmpty()
            if (clean.isEmpty()) return true
            if (clean in DEFAULT_PLACEHOLDER_NAMES) return true
            return DEFAULT_NUMBERED_NAME_REGEX.matches(clean)
        }

        /**
         * 登录成功拿到绑定抖音昵称后的自动命名策略（策略 A）：
         * - 若当前仍是默认占位名（`默认抖音号` / `默认账号` / `抖音号 N`）或更换绑定了另一抖音号，
         *   则自动同步为绑定的抖音昵称，并与其余账号自动去重（重名追加 `(2)`、`(3)` 后缀）；
         * - 若为同一抖音号重新登录且用户已手动设置自定义备注，保留用户自定义名称。
         */
        fun resolveSyncedDisplayNameAfterLogin(
            currentDisplayName: String,
            newDouyinNickname: String?,
            isSwitchedDouyinAccount: Boolean,
            otherAccountDisplayNames: Collection<String>,
        ): String {
            val cleanNick = newDouyinNickname?.trim()?.takeIf { it.isNotEmpty() }
                ?: return currentDisplayName
            val shouldSync = isSwitchedDouyinAccount || isDefaultPlaceholderName(currentDisplayName)
            if (!shouldSync) return currentDisplayName

            val occupied = otherAccountDisplayNames
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            if (cleanNick.lowercase() !in occupied) {
                return cleanNick
            }
            var suffix = 2
            while (true) {
                val candidate = "$cleanNick ($suffix)"
                if (candidate.lowercase() !in occupied) {
                    return candidate
                }
                suffix += 1
            }
        }
    }
}
