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

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.name())

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
    ): String {
        val versionParam = formatVersionLabel(versionName, versionCode)
        val osParam = buildString {
            append("Android ")
            append(androidRelease.trim().ifEmpty { "Unknown" })
            if (apiLevel > 0) {
                append(" (API ")
                append(apiLevel)
                append(")")
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
     * 仅包含版本号、系统版本、厂商类别与只读摘要，严禁包含好友昵称、账号 ID、Cookie 或推送密钥。
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
    ): String = buildString {
        appendLine("应用：Flame Contractor (Android App)")
        appendLine("版本：${formatVersionLabel(versionName, versionCode)}")
        appendLine("系统：Android ${androidRelease.trim().ifEmpty { "Unknown" }} (API $apiLevel)")
        appendLine("品牌：${manufacturer.trim().ifEmpty { "Unknown" }}")
        appendLine("定位器摘要：${selectorDigest.trim().ifEmpty { "未加载" }}")
        append("保活自检：$survivalPassed/$survivalTotal 通过（已脱敏，不含账号与好友信息）")
    }
}
