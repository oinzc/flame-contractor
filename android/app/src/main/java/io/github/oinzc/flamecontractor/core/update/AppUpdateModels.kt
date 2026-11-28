package io.github.oinzc.flamecontractor.core.update

import io.github.oinzc.flamecontractor.execution.ExecJson
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 更新清单中单条历史版本日志摘要（用于应用内免代理查看历史/跨版本变更）。
 */
data class AppUpdateReleaseHistoryItem(
    val versionName: String,
    val versionCode: Long,
    val publishedAt: String? = null,
    val changelog: String,
)

/**
 * 远端 Android 更新清单（`latest.json`）解析后的不可变版本发布契约。
 */
data class AppUpdateRelease(
    val schemaVersion: Int,
    val platform: String,
    val versionName: String,
    val versionCode: Long,
    val minVersionCode: Long = 0L,
    val publishedAt: String? = null,
    val fileSize: Long = 0L,
    val sha256: String,
    val downloadUrl: String,
    val releasePageUrl: String? = null,
    val changelog: String = "",
    val recentReleases: List<AppUpdateReleaseHistoryItem> = emptyList(),
)

/**
 * 解析后的语义化版本号（SemVer 2.0），支持规范排序比较。
 */
data class ParsedSemver(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val preRelease: List<String> = emptyList(),
) : Comparable<ParsedSemver> {

    override fun compareTo(other: ParsedSemver): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }

        val leftPre = preRelease
        val rightPre = other.preRelease
        if (leftPre.isEmpty() && rightPre.isEmpty()) return 0
        // 正式版优先级严格高于同主次补丁号的预发布版（如 0.6.1 > 0.6.1-dev）
        if (leftPre.isEmpty()) return 1
        if (rightPre.isEmpty()) return -1

        val minLen = minOf(leftPre.size, rightPre.size)
        for (i in 0 until minLen) {
            val cmp = comparePreReleaseIdentifier(leftPre[i], rightPre[i])
            if (cmp != 0) return cmp
        }
        return leftPre.size.compareTo(rightPre.size)
    }

    companion object {
        private fun comparePreReleaseIdentifier(left: String, right: String): Int {
            val leftNumeric = left.isNotEmpty() && left.all { it in '0'..'9' }
            val rightNumeric = right.isNotEmpty() && right.all { it in '0'..'9' }
            return when {
                leftNumeric && rightNumeric -> compareUnsignedDecimalStrings(left, right)
                // SemVer 2.0 §11.4.3: 纯数字标识符优先级低于非数字标识符
                leftNumeric && !rightNumeric -> -1
                !leftNumeric && rightNumeric -> 1
                else -> left.compareTo(right)
            }
        }

        private fun compareUnsignedDecimalStrings(left: String, right: String): Int {
            val a = left.trimStart('0').ifEmpty { "0" }
            val b = right.trimStart('0').ifEmpty { "0" }
            a.length.compareTo(b.length).let { if (it != 0) return it }
            return a.compareTo(b)
        }
    }
}

data class AppUpdateEvaluation(
    val isUpdateAvailable: Boolean,
    val isMandatory: Boolean,
)

/**
 * 纯函数语义化版本比较器与更新判定规则：
 * - 兼容前导 `v`/`V`（如 `v0.6.1`）、构建元数据（`+...`）、预发布标签（`0.6.1-rc.1`、`0.6.0-dev`）
 *   及 Android 变体后缀（`-debug`、`-demo`，比较前自动剥离）；
 * - 判定有更新：`remote.versionCode > currentVersionCode` 且
 *   `compareSemver(remote.versionName, currentVersionName) >= 0`；
 * - 判定强制更新：`isUpdateAvailable && remote.minVersionCode > 0 && currentVersionCode < remote.minVersionCode`。
 */
object AppVersionComparator {

    private val VARIANT_SUFFIXES = listOf("-debug", "-demo")
    private val SEMVER_REGEX = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?$""")
    private val PRE_RELEASE_ID_REGEX = Regex("""^[0-9A-Za-z-]+$""")

    fun normalizeVersionString(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("v", ignoreCase = true) && text.length > 1 && text[1].isDigit()) {
            text = text.substring(1)
        }
        val plusIdx = text.indexOf('+')
        if (plusIdx >= 0) {
            text = text.substring(0, plusIdx)
        }
        // 剥离构建变体后缀（如 0.6.0-dev-debug -> 0.6.0-dev，0.6.1-debug -> 0.6.1）
        var stripped = true
        while (stripped) {
            stripped = false
            for (suffix in VARIANT_SUFFIXES) {
                if (text.length > suffix.length && text.endsWith(suffix, ignoreCase = true)) {
                    text = text.substring(0, text.length - suffix.length)
                    stripped = true
                }
            }
        }
        return text.trim()
    }

    fun parseOrNull(raw: String?): ParsedSemver? {
        if (raw.isNullOrBlank()) return null
        val normalized = normalizeVersionString(raw)
        val match = SEMVER_REGEX.matchEntire(normalized) ?: return null
        val major = match.groupValues[1].toLongOrNull() ?: return null
        val minor = match.groupValues[2].toLongOrNull() ?: return null
        val patchStr = match.groupValues[3]
        val patch = if (patchStr.isEmpty()) 0L else (patchStr.toLongOrNull() ?: return null)
        val preRaw = match.groupValues[4]
        val preRelease = if (preRaw.isEmpty()) {
            emptyList()
        } else {
            val parts = preRaw.split('.')
            if (parts.any { it.isEmpty() || !PRE_RELEASE_ID_REGEX.matches(it) }) {
                return null
            }
            parts
        }
        return ParsedSemver(
            major = major,
            minor = minor,
            patch = patch,
            preRelease = preRelease,
        )
    }

    fun compareSemverOrNull(remoteVersionName: String, currentVersionName: String): Int? {
        val left = parseOrNull(remoteVersionName) ?: return null
        val right = parseOrNull(currentVersionName) ?: return null
        return left.compareTo(right)
    }

    fun compareSemver(remoteVersionName: String, currentVersionName: String): Int {
        val left = parseOrNull(remoteVersionName)
            ?: throw IllegalArgumentException("无法解析版本号：$remoteVersionName")
        val right = parseOrNull(currentVersionName)
            ?: throw IllegalArgumentException("无法解析版本号：$currentVersionName")
        return left.compareTo(right)
    }

    fun evaluateUpdate(
        release: AppUpdateRelease,
        currentVersionName: String,
        currentVersionCode: Long,
    ): AppUpdateEvaluation {
        val semverCmp = compareSemverOrNull(release.versionName, currentVersionName)
        val isUpdateAvailable = release.versionCode > currentVersionCode &&
            semverCmp != null &&
            semverCmp >= 0
        val isMandatory = isUpdateAvailable &&
            release.minVersionCode > 0L &&
            currentVersionCode < release.minVersionCode
        return AppUpdateEvaluation(
            isUpdateAvailable = isUpdateAvailable,
            isMandatory = isMandatory,
        )
    }
}

/**
 * 更新清单解析异常（携带面向用户的可读中文说明与可重试标记）。
 */
class AppUpdateManifestException(
    val userMessage: String,
    val reasonCode: String,
    val retryable: Boolean = true,
    cause: Throwable? = null,
) : IllegalArgumentException(userMessage, cause)

/**
 * 纯 Kotlin（基于 [ExecJson]，零 `org.json` 依赖）的 `latest.json` 清单解析与安全校验器。
 */
object AppUpdateManifestParser {

    const val SUPPORTED_SCHEMA_VERSION: Int = 1
    const val EXPECTED_PLATFORM: String = "android"
    const val MAX_MANIFEST_BYTES: Int = 256 * 1024
    const val MAX_VERSION_NAME_LEN: Int = 64
    const val MAX_CHANGELOG_CHARS: Int = 16_000
    const val MAX_RECENT_RELEASES: Int = 12
    const val MAX_HISTORY_CHANGELOG_CHARS: Int = 4_000

    private val SHA256_HEX_REGEX = Regex("""^[0-9a-fA-F]{64}$""")
    private val WHITESPACE_OR_CONTROL_REGEX = Regex("""[\s\u0000-\u001F\u007F]""")

    fun parse(
        rawJson: String,
        allowCleartext: Boolean = false,
    ): AppUpdateRelease {
        if (rawJson.isBlank()) {
            throw AppUpdateManifestException(
                userMessage = "更新清单内容为空，请稍后重试",
                reasonCode = "empty_manifest",
            )
        }
        val byteLen = rawJson.toByteArray(Charsets.UTF_8).size
        if (byteLen > MAX_MANIFEST_BYTES) {
            throw AppUpdateManifestException(
                userMessage = "更新清单体积异常，已拦截解析",
                reasonCode = "manifest_too_large",
                retryable = false,
            )
        }

        val root = try {
            ExecJson.parseObject(rawJson)
        } catch (exc: Throwable) {
            throw AppUpdateManifestException(
                userMessage = "更新清单 JSON 格式无效，请稍后重试",
                reasonCode = "invalid_json",
                cause = exc,
            )
        }

        val schemaVersion = parseExactInt(root["schemaVersion"])
            ?: throw AppUpdateManifestException(
                userMessage = "更新清单缺少有效的 schemaVersion",
                reasonCode = "invalid_schema_version",
                retryable = false,
            )
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw AppUpdateManifestException(
                userMessage = "暂不支持该更新清单协议版本（v$schemaVersion），请前往发布页手动更新",
                reasonCode = "unsupported_schema_version",
                retryable = false,
            )
        }

        val platform = (root["platform"] as? String)?.trim()?.lowercase(Locale.ROOT)
        if (platform != EXPECTED_PLATFORM) {
            throw AppUpdateManifestException(
                userMessage = "更新清单平台标识不匹配，已拦截",
                reasonCode = "invalid_platform",
                retryable = false,
            )
        }

        val versionName = (root["versionName"] as? String)?.trim().orEmpty()
        if (versionName.isEmpty() ||
            versionName.length > MAX_VERSION_NAME_LEN ||
            WHITESPACE_OR_CONTROL_REGEX.containsMatchIn(versionName) ||
            AppVersionComparator.parseOrNull(versionName) == null
        ) {
            throw AppUpdateManifestException(
                userMessage = "更新清单版本号格式无效，已拦截",
                reasonCode = "invalid_version_name",
                retryable = false,
            )
        }

        val versionCode = parseExactLong(root["versionCode"])
        if (versionCode == null || versionCode <= 0L) {
            throw AppUpdateManifestException(
                userMessage = "更新清单缺少有效的 versionCode，已拦截",
                reasonCode = "invalid_version_code",
                retryable = false,
            )
        }

        val rawMinVersionCode = root["minVersionCode"]
        val minVersionCode = if (rawMinVersionCode == null) {
            0L
        } else {
            val parsed = parseExactLong(rawMinVersionCode)
            if (parsed == null || parsed < 0L) {
                throw AppUpdateManifestException(
                    userMessage = "更新清单 minVersionCode 格式无效，已拦截",
                    reasonCode = "invalid_min_version_code",
                    retryable = false,
                )
            }
            parsed
        }

        val rawPublishedAt = root["publishedAt"]
        if (rawPublishedAt != null && rawPublishedAt !is String) {
            throw AppUpdateManifestException(
                userMessage = "更新清单 publishedAt 字段类型无效",
                reasonCode = "invalid_published_at",
                retryable = false,
            )
        }
        val publishedAt = (rawPublishedAt as? String)?.trim()?.takeIf { it.isNotEmpty() }

        val rawFileSize = root["fileSize"]
        val fileSize = if (rawFileSize == null) {
            0L
        } else {
            val parsed = parseExactLong(rawFileSize)
            if (parsed == null || parsed < 0L) {
                throw AppUpdateManifestException(
                    userMessage = "更新清单安装包大小字段无效，已拦截",
                    reasonCode = "invalid_file_size",
                    retryable = false,
                )
            }
            parsed
        }

        val sha256Raw = (root["sha256"] as? String)?.trim().orEmpty()
        if (!SHA256_HEX_REGEX.matches(sha256Raw)) {
            throw AppUpdateManifestException(
                userMessage = "更新清单缺少有效的 SHA-256 校验摘要，已拦截",
                reasonCode = "invalid_sha256",
                retryable = false,
            )
        }
        val sha256 = sha256Raw.lowercase(Locale.ROOT)

        val downloadUrlRaw = (root["downloadUrl"] as? String).orEmpty()
        val downloadUrl = validateDownloadUrl(downloadUrlRaw, allowCleartext)
            ?: throw AppUpdateManifestException(
                userMessage = "更新清单中的安装包链接不安全（仅支持 HTTPS），已拦截",
                reasonCode = "invalid_download_url",
                retryable = false,
            )

        val releasePageUrlRaw = (root["releasePageUrl"] as? String).orEmpty()
        val releasePageUrl = if (releasePageUrlRaw.isBlank()) {
            null
        } else {
            validateReleasePageUrl(releasePageUrlRaw, allowCleartext)
        }

        val changelog = ((root["changelog"] as? String) ?: "")
            .trim()
            .take(MAX_CHANGELOG_CHARS)
        val recentReleases = parseRecentReleases(root["recentReleases"])

        return AppUpdateRelease(
            schemaVersion = schemaVersion,
            platform = EXPECTED_PLATFORM,
            versionName = versionName,
            versionCode = versionCode,
            minVersionCode = minVersionCode,
            publishedAt = publishedAt,
            fileSize = fileSize,
            sha256 = sha256,
            downloadUrl = downloadUrl,
            releasePageUrl = releasePageUrl,
            changelog = changelog,
            recentReleases = recentReleases,
        )
    }

    private fun parseRecentReleases(raw: Any?): List<AppUpdateReleaseHistoryItem> {
        val rawList = raw as? List<*> ?: return emptyList()
        val items = mutableListOf<AppUpdateReleaseHistoryItem>()
        val seenCodes = mutableSetOf<Long>()

        for (entry in rawList) {
            val obj = entry as? Map<*, *> ?: continue
            val rawVersion = (obj["versionName"] as? String)?.trim().orEmpty()
            if (rawVersion.isEmpty() ||
                rawVersion.length > MAX_VERSION_NAME_LEN ||
                WHITESPACE_OR_CONTROL_REGEX.containsMatchIn(rawVersion) ||
                AppVersionComparator.parseOrNull(rawVersion) == null
            ) {
                continue
            }
            val normalizedVersion = if (
                rawVersion.startsWith("v", ignoreCase = true) &&
                rawVersion.length > 1 &&
                rawVersion[1].isDigit()
            ) {
                rawVersion.substring(1)
            } else {
                rawVersion
            }

            val vCode = parseExactLong(obj["versionCode"]) ?: continue
            if (vCode <= 0L) continue

            val publishedAt = (obj["publishedAt"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val changelogText = ((obj["changelog"] as? String) ?: "")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim()
                .take(MAX_HISTORY_CHANGELOG_CHARS)
            if (changelogText.isEmpty()) continue

            if (!seenCodes.add(vCode)) continue
            items.add(
                AppUpdateReleaseHistoryItem(
                    versionName = normalizedVersion,
                    versionCode = vCode,
                    publishedAt = publishedAt,
                    changelog = changelogText,
                ),
            )
        }

        return items
            .sortedByDescending { it.versionCode }
            .take(MAX_RECENT_RELEASES)
    }

    internal fun validateDownloadUrl(raw: String, allowCleartext: Boolean): String? {
        if (raw.isEmpty() || WHITESPACE_OR_CONTROL_REGEX.containsMatchIn(raw)) {
            return null
        }
        val parsed = raw.toHttpUrlOrNull() ?: return null
        val allowed = if (allowCleartext) {
            parsed.scheme == "https" || parsed.scheme == "http"
        } else {
            parsed.scheme == "https"
        }
        return if (allowed) parsed.toString() else null
    }

    private fun validateReleasePageUrl(raw: String, allowCleartext: Boolean): String? {
        if (WHITESPACE_OR_CONTROL_REGEX.containsMatchIn(raw)) return null
        val parsed = raw.toHttpUrlOrNull() ?: return null
        val allowed = if (allowCleartext) {
            parsed.scheme == "https" || parsed.scheme == "http"
        } else {
            parsed.scheme == "https"
        }
        return if (allowed) parsed.toString() else null
    }

    private fun parseExactInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.toInt().takeIf { value in Int.MIN_VALUE..Int.MAX_VALUE }
        else -> null
    }

    private fun parseExactLong(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        else -> null
    }
}

/**
 * 更新引擎当前阶段状态（供 Compose UI 精确渲染按钮、进度条与状态提示）。
 */
sealed interface AppUpdateStatus {
    data object Idle : AppUpdateStatus

    data object Checking : AppUpdateStatus

    data object UpToDate : AppUpdateStatus

    data class UpdateAvailable(
        val release: AppUpdateRelease,
        val isMandatory: Boolean,
    ) : AppUpdateStatus

    data class Downloading(
        val release: AppUpdateRelease,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progressPercent: Int?,
    ) : AppUpdateStatus

    data class Verifying(
        val release: AppUpdateRelease,
    ) : AppUpdateStatus

    data class ReadyToInstall(
        val release: AppUpdateRelease,
        val apkFilePath: String,
    ) : AppUpdateStatus

    data class Error(
        val message: String,
        val retryable: Boolean = true,
        val release: AppUpdateRelease? = null,
    ) : AppUpdateStatus
}

/**
 * 不可变全局更新 UI 状态，供 UI 层直接 `collectAsState()` 消费。
 */
data class AppUpdateUiState(
    val isConfigured: Boolean,
    val autoCheckEnabled: Boolean,
    val currentVersionName: String,
    val currentVersionCode: Long,
    val isDebugVariant: Boolean,
    val lastCheckedAtEpochMs: Long? = null,
    val ignoredVersionCode: Long? = null,
    val status: AppUpdateStatus = AppUpdateStatus.Idle,
    val showDialog: Boolean = false,
    val pendingInstallAfterPermissionGrant: Boolean = false,
    val recentReleases: List<AppUpdateReleaseHistoryItem> = emptyList(),
) {
    /** 当前状态关联的目标更新版本（若已发现新版 / 正在下载 / 校验 / 待安装 / 下载出错）。 */
    val activeRelease: AppUpdateRelease?
        get() = when (val s = status) {
            is AppUpdateStatus.UpdateAvailable -> s.release
            is AppUpdateStatus.Downloading -> s.release
            is AppUpdateStatus.Verifying -> s.release
            is AppUpdateStatus.ReadyToInstall -> s.release
            is AppUpdateStatus.Error -> s.release
            AppUpdateStatus.Idle,
            AppUpdateStatus.Checking,
            AppUpdateStatus.UpToDate -> null
        }

    /** 当前关联更新是否属于强制更新（不可忽略）。 */
    val isMandatoryUpdate: Boolean
        get() = when (val s = status) {
            is AppUpdateStatus.UpdateAvailable -> s.isMandatory
            is AppUpdateStatus.Downloading -> isReleaseMandatory(s.release)
            is AppUpdateStatus.Verifying -> isReleaseMandatory(s.release)
            is AppUpdateStatus.ReadyToInstall -> isReleaseMandatory(s.release)
            is AppUpdateStatus.Error -> s.release?.let(::isReleaseMandatory) ?: false
            AppUpdateStatus.Idle,
            AppUpdateStatus.Checking,
            AppUpdateStatus.UpToDate -> false
        }

    /** 是否已有可展示或正在处理的更新任务（用于设置页角标与重开弹窗）。 */
    val hasAvailableUpdate: Boolean
        get() = activeRelease != null

    private fun isReleaseMandatory(release: AppUpdateRelease): Boolean =
        release.minVersionCode > 0L && currentVersionCode < release.minVersionCode
}

/**
 * 单次 `checkForUpdates(manual)` 的结构化返回结果。
 */
sealed interface AppUpdateCheckResult {
    data object Skipped : AppUpdateCheckResult

    data class NotConfigured(val message: String) : AppUpdateCheckResult

    data object UpToDate : AppUpdateCheckResult

    data class UpdateAvailable(
        val release: AppUpdateRelease,
        val isMandatory: Boolean,
        val ignoredByUser: Boolean = false,
    ) : AppUpdateCheckResult

    data class Error(
        val message: String,
        val retryable: Boolean = true,
    ) : AppUpdateCheckResult
}

/**
 * 调用 `installReadyApk(context)` 时的执行结果。
 */
sealed interface AppUpdateInstallOutcome {
    data object Launched : AppUpdateInstallOutcome

    data object NeedsUnknownSourcesPermission : AppUpdateInstallOutcome

    data class Failed(val message: String) : AppUpdateInstallOutcome
}
