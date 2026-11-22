package io.github.oinzc.flamecontractor.core.account

import android.content.Context
import android.webkit.CookieManager
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID

object WebViewProfileRegistry {

    const val DEFAULT_PROFILE_NAME: String = Profile.DEFAULT_PROFILE_NAME

    private val SAFE_SUFFIX_REGEX = Regex("""[^A-Za-z0-9_-]""")

    fun profileNameForAccount(accountId: String): String {
        val clean = accountId.trim()
        if (clean.isEmpty() || clean == DEFAULT_ACCOUNT_ID) {
            return DEFAULT_PROFILE_NAME
        }
        val safe = SAFE_SUFFIX_REGEX.replace(clean, "_").take(48)
        return "acct_$safe"
    }

    fun isMultiProfileSupported(): Boolean = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    } catch (_: Throwable) {
        false
    }

    fun cookieManagerForAccount(accountId: String): CookieManager =
        cookieManagerForProfileName(profileNameForAccount(accountId))

    fun cookieManagerForProfileName(profileName: String): CookieManager {
        if (profileName == DEFAULT_PROFILE_NAME || !isMultiProfileSupported()) {
            return CookieManager.getInstance()
        }
        return ProfileStore.getInstance().getOrCreateProfile(profileName).cookieManager
    }

    fun clearAccountCookies(accountId: String, onCleared: () -> Unit = {}) {
        val cm = try {
            cookieManagerForAccount(accountId)
        } catch (_: Throwable) {
            null
        }
        if (cm == null) {
            onCleared()
            return
        }
        try {
            cm.removeAllCookies {
                cm.removeSessionCookies {
                    try {
                        cm.flush()
                    } catch (_: Throwable) {
                    }
                    onCleared()
                }
            }
        } catch (_: Throwable) {
            onCleared()
        }
    }
}

class PendingProfileCleanupStore(
    context: Context,
) {
    private val prefs = (context.applicationContext ?: context)
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enqueue(profileName: String) {
        val clean = profileName.trim()
        if (clean.isEmpty() || clean == WebViewProfileRegistry.DEFAULT_PROFILE_NAME) return
        val next = readPendingSet().toMutableSet().apply { add(clean) }
        prefs.edit().putStringSet(KEY_PENDING_PROFILES, next).apply()
    }

    fun listPending(): Set<String> = readPendingSet()

    /**
     * 必须在进程冷启动且任何 WebView 初始化目标 Profile 前调用，避免触发现行 Chromium
     * `IllegalStateException("Cannot delete in-use profile")` 限制。
     */
    fun cleanupPendingProfiles(): Int {
        val pending = readPendingSet()
        if (pending.isEmpty()) return 0
        if (!WebViewProfileRegistry.isMultiProfileSupported()) return 0

        val store = try {
            ProfileStore.getInstance()
        } catch (_: Throwable) {
            return 0
        }
        val existingNames = try {
            store.getAllProfileNames().toSet()
        } catch (_: Throwable) {
            emptySet()
        }

        val remaining = mutableSetOf<String>()
        var deletedCount = 0
        for (name in pending) {
            if (name == WebViewProfileRegistry.DEFAULT_PROFILE_NAME) continue
            if (existingNames.isNotEmpty() && name !in existingNames) {
                deletedCount += 1
                continue
            }
            val removed = try {
                store.deleteProfile(name)
            } catch (_: Throwable) {
                false
            }
            if (removed) {
                deletedCount += 1
            } else {
                val stillPresent = try {
                    name in store.getAllProfileNames()
                } catch (_: Throwable) {
                    true
                }
                if (stillPresent) {
                    remaining += name
                } else {
                    deletedCount += 1
                }
            }
        }
        prefs.edit().putStringSet(KEY_PENDING_PROFILES, remaining).apply()
        return deletedCount
    }

    private fun readPendingSet(): Set<String> =
        prefs.getStringSet(KEY_PENDING_PROFILES, emptySet())?.toSet().orEmpty()

    companion object {
        private const val PREFS_NAME = "flame_pending_profile_cleanup"
        private const val KEY_PENDING_PROFILES = "pending_profiles"
    }
}
