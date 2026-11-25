package io.github.oinzc.flamecontractor.execution.login

import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.execution.ExecJson
import io.github.oinzc.flamecontractor.execution.LoginMode
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class LoginDisplayMode {
    NATIVE_QR,
    WEB_FALLBACK,
}

enum class QrVisualStage {
    CONNECTING,
    GENERATING_QR,
    READY,
    SCANNED,
    CONFIRMED,
    EXPIRED,
    LOAD_TIMEOUT,
}

enum class QrConnectStatus {
    NEW,
    SCANNED,
    CONFIRMED,
    EXPIRED,
    UNKNOWN,
}

enum class LoginReturnEvent(
    val title: String,
    val body: String,
    val actionLabel: String,
) {
    QR_CONFIRMED(
        title = "抖音已确认登录",
        body = "授权已通过，点此立即返回焰之契约者完成同步",
        actionLabel = "返回应用",
    ),
    LOGIN_SUCCEEDED(
        title = "抖音登录成功",
        body = "已完成账号绑定，点此立即返回焰之契约者",
        actionLabel = "返回应用",
    ),
    VERIFICATION_REQUIRED(
        title = "需要完成安全验证",
        body = "抖音要求新设备身份验证，点此返回完成验证",
        actionLabel = "去验证",
    ),
    ACCOUNT_SWITCH_CONFIRM(
        title = "检测到更换抖音账号",
        body = "点此返回选择如何处理原有好友名单",
        actionLabel = "去确认",
    ),
}

enum class DouyinClientVariant(
    val packageName: String,
    val scheme: String,
    val displayName: String,
) {
    DOUYIN_MAIN(
        packageName = "com.ss.android.ugc.aweme",
        scheme = "snssdk1128",
        displayName = "抖音",
    ),
    DOUYIN_LITE(
        packageName = "com.ss.android.ugc.aweme.lite",
        scheme = "snssdk2329",
        displayName = "抖音极速版",
    ),
    DOUYIN_VOLCANO(
        packageName = "com.ss.android.ugc.live",
        scheme = "snssdk1112",
        displayName = "抖音火山版",
    ),
}

data class QrCodePayload(
    val base64Png: String,
    val validatedIndexUrl: String?,
    val expireAtEpochMs: Long,
)

object LoginQrParser {

    const val DEFAULT_QR_TTL_MS: Long = 180_000L

    private val ALLOWED_QR_HOST_SUFFIXES = listOf(
        "amemv.com",
        "douyin.com",
        "snssdk.com",
        "zijieapi.com",
    )

    private val DATA_IMAGE_PREFIX_REGEX = Regex(
        pattern = """^data:image/png;base64,""",
        option = RegexOption.IGNORE_CASE,
    )

    private val BASE64_CHARS_REGEX = Regex("""^[A-Za-z0-9+/=_-]+$""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    fun stripBase64ImagePrefix(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://")) return null

        val body = if (lower.startsWith("data:")) {
            val match = DATA_IMAGE_PREFIX_REGEX.find(trimmed) ?: return null
            trimmed.substring(match.range.last + 1)
        } else {
            trimmed
        }

        val compact = WHITESPACE_REGEX.replace(body, "")
        if (compact.isEmpty() || !BASE64_CHARS_REGEX.matches(compact)) return null
        return compact
    }

    fun validateQrIndexUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.any { it.code <= 0x20 }) return null

        val uri = try {
            URI(trimmed)
        } catch (_: Throwable) {
            return null
        }

        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return null
        if (uri.rawUserInfo != null || uri.userInfo != null) return null

        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if (host.isEmpty()) return null
        val hostAllowed = ALLOWED_QR_HOST_SUFFIXES.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }
        if (!hostAllowed) return null

        val path = uri.rawPath.orEmpty()
        if (path.isBlank() || path == "/") return null

        return trimmed
    }

    @Suppress("UNCHECKED_CAST")
    fun parseGetQrCodeJson(rawJson: String?, nowEpochMs: Long): QrCodePayload? {
        if (rawJson.isNullOrBlank()) return null
        val root = ExecJson.parseOrNull(rawJson) as? Map<String, Any?> ?: return null
        val data = (root["data"] as? Map<String, Any?>) ?: root

        val rawQr = (data["qrcode"] as? String) ?: (data["qrcodeBase64"] as? String)
        val base64Png = stripBase64ImagePrefix(rawQr) ?: return null

        val rawIdxUrl = (data["qrcode_index_url"] as? String) ?: (data["qrcodeIndexUrl"] as? String)
        val validatedIndexUrl = validateQrIndexUrl(rawIdxUrl)

        val rawExpire = (data["expire_time"] as? Number)?.toLong()
            ?: (data["expireTime"] as? Number)?.toLong()
            ?: (data["expire_time"] as? String)?.trim()?.toLongOrNull()
            ?: (data["expireTime"] as? String)?.trim()?.toLongOrNull()

        val candidateMs = when {
            rawExpire == null || rawExpire <= 0L -> 0L
            rawExpire < 100_000_000_000L -> rawExpire * 1_000L
            else -> rawExpire
        }
        val expireAtEpochMs = if (candidateMs > nowEpochMs + 5_000L) {
            candidateMs
        } else {
            nowEpochMs + DEFAULT_QR_TTL_MS
        }

        return QrCodePayload(
            base64Png = base64Png,
            validatedIndexUrl = validatedIndexUrl,
            expireAtEpochMs = expireAtEpochMs,
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun parseQrConnectJson(rawJson: String?): QrConnectStatus {
        if (rawJson.isNullOrBlank()) return QrConnectStatus.UNKNOWN
        val root = ExecJson.parseOrNull(rawJson) as? Map<String, Any?> ?: return QrConnectStatus.UNKNOWN
        val data = (root["data"] as? Map<String, Any?>) ?: root
        val rawStatus = (data["status"] as? String)?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (rawStatus) {
            "new" -> QrConnectStatus.NEW
            "scanned", "scan" -> QrConnectStatus.SCANNED
            "confirmed", "confirm" -> QrConnectStatus.CONFIRMED
            "expired" -> QrConnectStatus.EXPIRED
            else -> QrConnectStatus.UNKNOWN
        }
    }
}

object DouyinSchemeBuilder {

    fun resolvePreferredDouyinClient(installedPackages: Set<String>): DouyinClientVariant? =
        DouyinClientVariant.entries.firstOrNull { it.packageName in installedPackages }

    fun buildConfirmLoginDeepLink(
        variant: DouyinClientVariant,
        validatedIndexUrl: String,
    ): String {
        val encoded = URLEncoder.encode(validatedIndexUrl, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return "${variant.scheme}://webview?url=$encoded"
    }

    fun buildScanCameraDeepLink(variant: DouyinClientVariant): String =
        "${variant.scheme}://aweme/scan"
}

object LoginSessionReducer {

    const val QR_LOAD_TIMEOUT_MS: Long = 20_000L

    fun initialDisplayMode(loginMode: LoginMode): LoginDisplayMode =
        if (loginMode == LoginMode.INSPECT_OR_VERIFY) {
            LoginDisplayMode.WEB_FALLBACK
        } else {
            LoginDisplayMode.NATIVE_QR
        }

    fun shouldAutoFinishOnAuthenticated(
        loginMode: LoginMode,
        initialStatus: AccountStatus,
        userInitiated: Boolean,
    ): Boolean {
        if (userInitiated) return true
        if (loginMode != LoginMode.INSPECT_OR_VERIFY) return true
        return initialStatus == AccountStatus.VERIFICATION_REQUIRED
    }

    fun resolveUpdatedIndexUrl(
        incomingUrl: String?,
        previousUrl: String?,
        fromAuthoritativeNetwork: Boolean,
        imageChanged: Boolean,
    ): String? {
        if (fromAuthoritativeNetwork) {
            return incomingUrl
        }
        return incomingUrl ?: if (!imageChanged) previousUrl else null
    }

    fun shouldTriggerQrLoadTimeout(
        displayMode: LoginDisplayMode,
        stage: QrVisualStage,
        hasQrBitmap: Boolean,
        verificationRequired: Boolean,
        inspectedHealthyOnce: Boolean,
        loadStartedAtEpochMs: Long,
        nowEpochMs: Long,
    ): Boolean {
        if (displayMode != LoginDisplayMode.NATIVE_QR) return false
        if (hasQrBitmap || verificationRequired || inspectedHealthyOnce) return false
        if (loadStartedAtEpochMs <= 0L) return false
        if (stage != QrVisualStage.CONNECTING && stage != QrVisualStage.GENERATING_QR) return false
        return nowEpochMs - loadStartedAtEpochMs >= QR_LOAD_TIMEOUT_MS
    }

    fun shouldMarkDomExpired(
        domExpired: Boolean,
        hasQrBitmap: Boolean,
        currentStage: QrVisualStage,
    ): Boolean = domExpired &&
        hasQrBitmap &&
        (currentStage == QrVisualStage.READY || currentStage == QrVisualStage.SCANNED)

    fun shouldReturnToForegroundFromBackground(isActivityResumed: Boolean): Boolean =
        !isActivityResumed

    /**
     * 后台回跳到 `LoginWebViewActivity` 时绝不能再次携带 `CLEAR_AND_RELOGIN`，
     * 防止极少数 Activity 重建竞态把刚刚授权成功的 Cookie 误清空。
     */
    fun safeReturnLoginMode(mode: LoginMode): LoginMode =
        if (mode == LoginMode.CLEAR_AND_RELOGIN) LoginMode.PRESERVE_SESSION else mode
}

enum class LoginSubresourceAction {
    ALLOW,
    BLOCK_EMPTY,
    STUB_EMPTY_FEED,
}

/**
 * 原生二维码登录阶段（未暴露底层网页且未触发安全验证）的轻量资源过滤器：
 * 拦截未登录态下抖音网页在后台偷偷拉起的推荐视频流、音频处理脚本、特效视频、封面图与网页字体，
 * 释放网络带宽与渲染 CPU 给通行证与二维码接口；绝不拦截脚本、样式、风控/验证码、IM 及登录接口。
 */
object LoginResourceFilter {

    private val PROTECTED_KEYWORDS = listOf(
        "passport",
        "sso.",
        "/sso/",
        "qrcode",
        "qrconnect",
        "captcha",
        "verify",
        "bdturing",
        "mssdk",
        "webmssdk",
        "bdms",
        "uc-secure",
        "ucenter",
        "ttwid",
        "login",
        "/im/",
        "imapi",
        "conversation",
        "message",
    )

    private val EMPTY_FEED_PATHS = listOf(
        "/aweme/v1/web/tab/feed",
        "/aweme/v1/web/channel/feed",
        "/aweme/v1/web/aweme/post",
        "/aweme/v1/web/discover",
        "/aweme/v1/web/hot/search/list",
    )

    private val SKIPPED_HOST_SUFFIXES = listOf(
        "douyinvod.com",
        "bytevcloudapi.com",
        "ixigua.com",
        "byteeffecttos.com",
        "toutiaovod.com",
        "vlabvod.com",
        "douyinpic.com",
    )

    private val SKIPPED_URL_SUBSTRINGS = listOf(
        "mime_type=video",
        "mime_type=audio",
        "/video/tos/",
        "/aweme/v1/play",
        "mammon-worklet-processor",
    )

    private val SKIPPED_EXTENSIONS = listOf(
        ".mp4",
        ".m4s",
        ".m3u8",
        ".webm",
        ".flv",
        ".mp3",
        ".aac",
        ".ogg",
        ".wav",
        ".woff",
        ".woff2",
        ".ttf",
        ".otf",
        ".eot",
        ".webp",
        ".heic",
        ".avif",
        ".jpg",
        ".jpeg",
        ".gif",
    )

    fun classifySubresource(
        rawUrl: String?,
        isForMainFrame: Boolean,
        hasRangeHeader: Boolean = false,
    ): LoginSubresourceAction {
        if (isForMainFrame) return LoginSubresourceAction.ALLOW
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return LoginSubresourceAction.ALLOW
        val lower = trimmed.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return LoginSubresourceAction.ALLOW
        }

        val uri = try {
            URI(trimmed)
        } catch (_: Throwable) {
            return LoginSubresourceAction.ALLOW
        }
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.').orEmpty()
        val path = uri.rawPath?.lowercase(Locale.ROOT).orEmpty()
        val query = uri.rawQuery?.lowercase(Locale.ROOT).orEmpty()
        if (host.isEmpty()) return LoginSubresourceAction.ALLOW

        if (PROTECTED_KEYWORDS.any { keyword -> host.contains(keyword) || path.contains(keyword) }) {
            return LoginSubresourceAction.ALLOW
        }

        if (EMPTY_FEED_PATHS.any { feedPath -> path.contains(feedPath) }) {
            return LoginSubresourceAction.STUB_EMPTY_FEED
        }

        if (hasRangeHeader) {
            return LoginSubresourceAction.BLOCK_EMPTY
        }

        if (SKIPPED_HOST_SUFFIXES.any { suffix -> host == suffix || host.endsWith(".$suffix") }) {
            return LoginSubresourceAction.BLOCK_EMPTY
        }

        val pathAndQuery = if (query.isEmpty()) path else "$path?$query"
        if (SKIPPED_URL_SUBSTRINGS.any { token -> pathAndQuery.contains(token) }) {
            return LoginSubresourceAction.BLOCK_EMPTY
        }

        if (SKIPPED_EXTENSIONS.any { ext -> path.endsWith(ext) }) {
            return LoginSubresourceAction.BLOCK_EMPTY
        }

        return LoginSubresourceAction.ALLOW
    }

    fun shouldSkipDuringNativeQr(
        rawUrl: String?,
        isForMainFrame: Boolean,
        hasRangeHeader: Boolean = false,
    ): Boolean = classifySubresource(
        rawUrl = rawUrl,
        isForMainFrame = isForMainFrame,
        hasRangeHeader = hasRangeHeader,
    ) != LoginSubresourceAction.ALLOW
}
