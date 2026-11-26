package io.github.oinzc.flamecontractor.core.support

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 项目官方开源仓库与问题反馈直达链接（纯 JVM 工具，零隐私外泄）。
 */
object ProjectLinks {
    const val REPO_DISPLAY_PATH: String = "github.com/oinzc/flame-contractor"
    const val REPO_URL: String = "https://github.com/oinzc/flame-contractor"
    const val RELEASES_URL: String = "$REPO_URL/releases"
    const val ISSUES_URL: String = "$REPO_URL/issues"
    const val DISCUSSIONS_URL: String = "$REPO_URL/discussions"
    const val BUG_REPORT_TEMPLATE: String = "bug_report.yml"
    const val FEATURE_REQUEST_TEMPLATE: String = "feature_request.yml"

    private const val MAX_DEVICE_TOKEN_LEN: Int = 48
    private val SAFE_DEVICE_TOKEN_REGEX = Regex("""^[A-Za-z0-9 ._\-/()]+$""")
    private val SENSITIVE_DEVICE_WORD_REGEX = Regex(
        """(cookie|session|ttwid|sid_tt|odin|csrf|device_key|secret|token|bearer|sec_uid|password|image_path|template_images)""",
        RegexOption.IGNORE_CASE,
    )
    private val URL_REGEX = Regex("""https?://[^\s"'`<>\\]+""", RegexOption.IGNORE_CASE)
    private val DOUYIN_SEC_UID_REGEX = Regex("""\bMS4wLjAB[A-Za-z0-9_-]{8,}""")
    private val LONG_DIGIT_ID_REGEX = Regex("""\b\d{14,}\b""")
    private val LONG_OPAQUE_TOKEN_REGEX = Regex("""\b[A-Za-z0-9_-]{24,}\b""")

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.name())

    internal fun sanitizeDeviceToken(raw: String, maxLength: Int = MAX_DEVICE_TOKEN_LEN): String {
        val normalized = raw
            .replace(Regex("""[\r\n\t]+"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        if (normalized.isEmpty()) return ""
        if (SENSITIVE_DEVICE_WORD_REGEX.containsMatchIn(normalized) ||
            URL_REGEX.containsMatchIn(normalized) ||
            DOUYIN_SEC_UID_REGEX.containsMatchIn(normalized) ||
            LONG_DIGIT_ID_REGEX.containsMatchIn(normalized) ||
            LONG_OPAQUE_TOKEN_REGEX.containsMatchIn(normalized)
        ) {
            return ""
        }
        if (!SAFE_DEVICE_TOKEN_REGEX.matches(normalized)) {
            return ""
        }
        return normalized.take(maxLength).trim()
    }

    private fun startsWithWord(text: String, prefix: String): Boolean {
        if (prefix.isEmpty() || text.length < prefix.length) return false
        if (!text.startsWith(prefix, ignoreCase = true)) return false
        return text.length == prefix.length || text[prefix.length] in charArrayOf(' ', '-', '_', '/')
    }

    private fun formatDeviceModelLabel(
        manufacturer: String,
        deviceBrand: String,
        deviceModel: String,
    ): String {
        if (deviceModel.isEmpty()) return ""
        if (manufacturer.isNotEmpty() &&
            deviceBrand.isNotEmpty() &&
            !manufacturer.equals(deviceBrand, ignoreCase = true)
        ) {
            return when {
                startsWithWord(deviceModel, deviceBrand) -> "$manufacturer / $deviceModel"
                startsWithWord(deviceModel, manufacturer) -> deviceModel
                else -> "$manufacturer / $deviceBrand $deviceModel"
            }
        }
        val primaryBrand = when {
            manufacturer.isNotEmpty() && deviceBrand.isNotEmpty() && manufacturer.equals(deviceBrand, ignoreCase = true) -> manufacturer
            deviceBrand.isNotEmpty() -> deviceBrand
            else -> manufacturer
        }
        return when {
            primaryBrand.isEmpty() -> deviceModel
            startsWithWord(deviceModel, primaryBrand) -> deviceModel
            else -> "$primaryBrand $deviceModel"
        }
    }

    fun formatVersionLabel(versionName: String, versionCode: Int): String {
        val cleanName = versionName.trim().ifEmpty { "dev" }
        val prefixed = if (cleanName.startsWith("v", ignoreCase = true)) cleanName else "v$cleanName"
        return if (versionCode > 0) "$prefixed ($versionCode)" else prefixed
    }

    fun buildAndroidBugReportUrl(
        versionName: String,
        versionCode: Int,
        androidRelease: String,
        apiLevel: Int,
        selectorDigest: String = "",
        deviceBrand: String = "",
        deviceModel: String = "",
    ): String {
        val versionParam = formatVersionLabel(versionName, versionCode)
        val safeBrand = sanitizeDeviceToken(deviceBrand, maxLength = 32)
        val safeModel = sanitizeDeviceToken(deviceModel, maxLength = 32)
        val osParam = buildString {
            append("Android ")
            append(androidRelease.trim().ifEmpty { "Unknown" })
            if (apiLevel > 0) {
                append(" (API ")
                append(apiLevel)
                append(")")
            }
            if (safeModel.isNotEmpty()) {
                val deviceLabel = formatDeviceModelLabel(
                    manufacturer = "",
                    deviceBrand = safeBrand,
                    deviceModel = safeModel,
                ).take(MAX_DEVICE_TOKEN_LEN).trim()
                if (deviceLabel.isNotEmpty()) {
                    append(" · ")
                    append(deviceLabel)
                }
            }
            val digest = selectorDigest.trim()
            if (digest.isNotEmpty()) {
                append(" · selector ")
                append(digest.take(12))
            }
        }
        return "$REPO_URL/issues/new" +
            "?template=${encodeQuery(BUG_REPORT_TEMPLATE)}" +
            "&version=${encodeQuery(versionParam)}" +
            "&environment=${encodeQuery("Android App")}" +
            "&os=${encodeQuery(osParam)}"
    }

    fun buildFeatureRequestUrl(): String =
        "$REPO_URL/issues/new?template=${encodeQuery(FEATURE_REQUEST_TEMPLATE)}"

    /**
     * 仅包含版本号、系统版本、非唯一设备兼容型号标识与只读摘要，严禁包含好友昵称、账号 ID、Cookie、推送密钥或设备追踪 ID。
     */
    fun buildDesensitizedEnvSummary(
        versionName: String,
        versionCode: Int,
        androidRelease: String,
        apiLevel: Int,
        manufacturer: String,
        selectorDigest: String,
        survivalPassed: Int,
        survivalTotal: Int,
        deviceBrand: String = "",
        deviceModel: String = "",
    ): String = buildString {
        val safeManufacturer = sanitizeDeviceToken(manufacturer)
        val safeBrand = sanitizeDeviceToken(deviceBrand)
        val safeModel = sanitizeDeviceToken(deviceModel)
        appendLine("应用：Flame Contractor (Android App)")
        appendLine("版本：${formatVersionLabel(versionName, versionCode)}")
        appendLine("系统：Android ${androidRelease.trim().ifEmpty { "Unknown" }} (API $apiLevel)")
        if (safeModel.isNotEmpty()) {
            val deviceLabel = formatDeviceModelLabel(
                manufacturer = safeManufacturer,
                deviceBrand = safeBrand,
                deviceModel = safeModel,
            )
            appendLine("机型：$deviceLabel")
        } else {
            val brandLabel = when {
                safeManufacturer.isNotEmpty() &&
                    safeBrand.isNotEmpty() &&
                    !safeManufacturer.equals(safeBrand, ignoreCase = true) -> "$safeManufacturer / $safeBrand"
                safeManufacturer.isNotEmpty() -> safeManufacturer
                safeBrand.isNotEmpty() -> safeBrand
                else -> "Unknown"
            }
            appendLine("品牌：$brandLabel")
        }
        appendLine("定位器摘要：${selectorDigest.trim().ifEmpty { "未加载" }}")
        append("保活自检：$survivalPassed/$survivalTotal 通过（已脱敏，不含账号与好友信息）")
    }
}
