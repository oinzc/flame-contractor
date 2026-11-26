package io.github.oinzc.flamecontractor.ui.settings

import io.github.oinzc.flamecontractor.core.notify.BarkUrlNormalizer
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.AttemptStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.ScanRunEntity
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.execution.StructureProbeReport
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class RawDiagnosticsContext(
    val generatedAtEpochMs: Long = System.currentTimeMillis(),
    val appVersionName: String,
    val appVersionCode: Long,
    val androidSdkInt: Int,
    val androidRelease: String,
    val webViewVersion: String? = null,
    val selectorDigest: String,
    val hasSelectorOverrides: Boolean,
    val account: AccountEntity? = null,
    val targets: List<ContractTargetEntity> = emptyList(),
    val todayJobs: List<SendJobEntity> = emptyList(),
    val recentAttempts: List<SendAttemptEntity> = emptyList(),
    val survivalSnapshot: RomSurvivalSnapshot? = null,
    val notificationsGranted: Boolean = survivalSnapshot?.notificationsGranted ?: false,
    val batteryOptimizationIgnored: Boolean = survivalSnapshot?.batteryOptimizationIgnored ?: false,
    val exactAlarmAllowed: Boolean = survivalSnapshot?.exactAlarmAllowed ?: false,
    val autostartGuidanceConfirmed: Boolean = survivalSnapshot?.autostartGuidanceConfirmed ?: false,
    val rawBarkUrl: String? = null,
    val latestProbeReport: StructureProbeReport? = null,
    val extraRawStrings: List<String> = emptyList(),
    val latestAutoScan: ScanRunEntity? = null,
    val totalAccountCount: Int = 1,
    val runtimeLogLines: List<String> = emptyList(),
    val recentSkipSummary: String? = null,
    val unboundTemplateTargetCount: Int = 0,
    val deviceManufacturer: String? = survivalSnapshot?.manufacturer,
    val deviceBrand: String? = null,
    val deviceModel: String? = null,
    val romDisplay: String? = null,
)

data class SanitizedSurvivalSummary(
    val notificationsGranted: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val exactAlarmAllowed: Boolean,
    val autostartGuidanceConfirmed: Boolean,
    val overlayAutoReturnAllowed: Boolean? = null,
    val backgroundPopupAllowed: Boolean? = null,
)

data class SanitizedBarkSummary(
    val configured: Boolean,
    val maskedUrl: String?,
)

data class SanitizedProbeSummary(
    val checkedAtIso: String,
    val loggedIn: Boolean,
    val coreSkeletonOk: Boolean,
    val passedItemCount: Int,
    val totalItemCount: Int,
    val failedKeys: List<String>,
)

data class SanitizedDiagnosticsReport(
    val generatedAtIso: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val androidSdkInt: Int,
    val androidRelease: String,
    val webViewVersion: String,
    val selectorDigest: String,
    val hasSelectorOverrides: Boolean,
    val totalAccountCount: Int = 1,
    val accountStatus: String,
    val accountEnabled: Boolean,
    val authFailCount: Int,
    val failureStreak: Int,
    val autoScanEnabled: Boolean,
    val autoScanIntervalHours: Int,
    val latestAutoScanStatus: String?,
    val latestAutoScanAtIso: String?,
    val survival: SanitizedSurvivalSummary,
    val bark: SanitizedBarkSummary,
    val activeTargetCount: Int,
    val enabledTargetCount: Int,
    val todayJobCount: Int,
    val todayJobStatusCounts: Map<String, Int>,
    val recentAttemptCount: Int,
    val recentAttemptStatusCounts: Map<String, Int>,
    val recentAttemptFailureClassCounts: Map<String, Int>,
    val recentAttemptErrorCodeCounts: Map<String, Int>,
    val latestProbe: SanitizedProbeSummary?,
    val textReport: String,
    val jsonReport: String,
    val unboundTemplateTargetCount: Int = 0,
    val recentSkipSummary: String? = null,
    val runtimeLogLines: List<String> = emptyList(),
    val deviceManufacturer: String = "unknown",
    val deviceBrand: String = "unknown",
    val deviceModel: String = "unknown",
    val romDisplay: String = "unknown",
)

/**
 * 脱敏诊断报告构建器。
 *
 * 硬隐私白名单与双重脱敏防线：
 * - 严格仅输出版本、SDK、非个人身份的硬件/系统兼容标识（厂商/品牌/型号/ROM 版本）、
 *   WebView 版本、12 位定位器摘要与覆写标志、账号状态枚举、
 *   保活状态布尔值、脱敏 Bark 状态、目标/任务数量与近期尝试状态码/失败分类计数；
 * - 绝不读取或拼接 Build.SERIAL / ANDROID_ID / IMEI / MAC 等设备唯一追踪 ID，
 *   也绝不读取 selfUid、douyinNickname、displayName、nicknameSnapshot、
 *   platformUserId、platformHandle、platformConversationId、summaryDetail、reconciliationJson、
 *   Cookie、消息正文或完整 Bark key；
 * - 即使误传含完整 Bark URL、Cookie、sec_uid、长数字 ID 或已知 PII 字符串，
 *   也在字段入口与输出出口处额外经字符白名单、正则与词典强制脱敏。
 */
object SanitizedDiagnosticsBuilder {

    private val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    private val SAFE_VERSION_REGEX = Regex("""^[A-Za-z0-9._+-]{1,48}$""")
    private val SAFE_DEVICE_FIELD_REGEX = Regex("""^[A-Za-z0-9 ._\-/()]+$""")
    private const val MAX_DEVICE_IDENT_LEN = 48
    private const val MAX_ROM_DISPLAY_LEN = 64
    private val SAFE_DIGEST_REGEX = Regex("""^[0-9a-fA-F]{12}$""")
    private val SAFE_ERROR_CODE_REGEX = Regex("""^[A-Za-z0-9_.:-]{1,36}$""")
    private val SAFE_PROBE_KEY_REGEX = Regex("""^[A-Z0-9_]{1,40}$""")

    private val URL_WITH_PATH_REGEX = Regex("""https?://[^\s"'`<>\\]+""", RegexOption.IGNORE_CASE)
    private val DEVICE_KEY_KV_REGEX =
        Regex("""(device_key["']?\s*[:=]\s*["']?)([^"'\s,}]+)""", RegexOption.IGNORE_CASE)
    private val COOKIE_HEADER_REGEX =
        Regex("""\b(cookie\s*[:=]\s*)([^\r\n]+)""", RegexOption.IGNORE_CASE)
    private val CREDENTIAL_KV_REGEX =
        Regex(
            """\b(sessionid|sid_tt|ttwid|odin_tt|passport_csrf_token|csrf_token|authorization|bearer|token|secret)(["']?\s*[:=]\s*["']?|\s+)([^\s;,&"'\r\n]+)""",
            RegexOption.IGNORE_CASE,
        )
    private val DOUYIN_SEC_UID_REGEX = Regex("""\bMS4wLjAB[A-Za-z0-9_-]{8,}""")
    private val LONG_DIGIT_ID_REGEX = Regex("""\b\d{14,}\b""")
    private val LONG_OPAQUE_TOKEN_REGEX = Regex("""\b[A-Za-z0-9_-]{24,}\b""")
    private val TEMPLATE_IMAGE_PATH_REGEX =
        Regex("""(?:file://[^\s"'`<>\\]+|[^\s"'`<>\\]*template_images[/\\][^\s"'`<>\\]+)""", RegexOption.IGNORE_CASE)
    private val ALREADY_MASKED_BARK_URL_REGEX =
        Regex("""^https?://[^/?#\s]+/(?:[^/?#\s]+/)*\*{4}[A-Za-z0-9_-]{0,4}(?:/push)?$""", RegexOption.IGNORE_CASE)

    private val SAFE_SCHEMA_IDENTIFIERS = setOf(
        "batteryOptimizationIgnored",
        "autostartGuidanceConfirmed",
        "overlayAutoReturnAllowed",
        "todayJobStatusCounts",
        "recentAttemptStatusCounts",
        "recentAttemptFailureClassCounts",
        "recentAttemptErrorCodeCounts",
        "unboundTemplateTargetCount",
        "UNKNOWN_REQUIRES_REVIEW",
        "VERIFICATION_REQUIRED",
        "unknown_requires_review",
        "verification_required",
    )

    private val SENSITIVE_WORD_REGEX =
        Regex("""(cookie|session|ttwid|sid_tt|odin|csrf|device_key|secret|token|bearer|sec_uid|password|image_path|template_images)""", RegexOption.IGNORE_CASE)

    fun build(input: RawDiagnosticsContext): SanitizedDiagnosticsReport {
        val sensitiveTokens = collectSensitiveTokens(input)

        val safeAppVersionName = sanitizeShortToken(input.appVersionName, fallback = "unknown", sensitiveTokens)
        val safeAndroidRelease = sanitizeShortToken(input.androidRelease, fallback = "unknown", sensitiveTokens)
        val safeDeviceManufacturer = sanitizeDeviceField(
            raw = input.deviceManufacturer,
            maxLength = MAX_DEVICE_IDENT_LEN,
            sensitiveTokens = sensitiveTokens,
        )
        val safeDeviceBrand = sanitizeDeviceField(
            raw = input.deviceBrand,
            maxLength = MAX_DEVICE_IDENT_LEN,
            sensitiveTokens = sensitiveTokens,
        )
        val safeDeviceModel = sanitizeDeviceField(
            raw = input.deviceModel,
            maxLength = MAX_DEVICE_IDENT_LEN,
            sensitiveTokens = sensitiveTokens,
        )
        val safeRomDisplay = sanitizeDeviceField(
            raw = input.romDisplay,
            maxLength = MAX_ROM_DISPLAY_LEN,
            sensitiveTokens = sensitiveTokens,
        )
        val safeWebViewVersion = sanitizeVersionString(input.webViewVersion, sensitiveTokens)
        val safeSelectorDigest = sanitizeDigest(input.selectorDigest, sensitiveTokens)

        val totalAccountCount = maxOf(
            if (input.account != null) 1 else 0,
            input.totalAccountCount.coerceAtLeast(0),
        )
        val accountStatus = (input.account?.status ?: AccountStatus.PENDING_LOGIN).wireValue
        val accountEnabled = input.account?.enabled ?: false
        val authFailCount = input.account?.authFailCount ?: 0
        val failureStreak = input.account?.failureStreak ?: 0
        val autoScanEnabled = input.account?.autoScanEnabled ?: false
        val autoScanIntervalHours = input.account?.autoScanIntervalHours ?: 24
        val latestAutoScanStatus = input.latestAutoScan?.status?.wireValue
        val latestAutoScanAtIso = input.latestAutoScan?.let { scan ->
            formatIso(scan.finishedAt ?: scan.startedAt)
        }

        val activeTargets = input.targets.filter { it.deletedAt == null }
        val activeTargetCount = activeTargets.size
        val enabledTargetCount = activeTargets.count { it.enabled }
        val unboundTemplateTargetCount = input.unboundTemplateTargetCount.coerceAtLeast(0)
        val sanitizedSkipSummary = sanitizeRecentSkipSummary(input.recentSkipSummary, sensitiveTokens)
        val sanitizedRuntimeLogLines = sanitizeRuntimeLogLines(input.runtimeLogLines, sensitiveTokens)

        val todayJobStatusCounts = linkedMapOf<String, Int>()
        for (status in JobStatus.entries) {
            val count = input.todayJobs.count { it.status == status }
            if (count > 0) {
                todayJobStatusCounts[status.wireValue] = count
            }
        }

        val attemptStatusCounts = linkedMapOf<String, Int>()
        for (status in AttemptStatus.entries) {
            val count = input.recentAttempts.count { it.status == status }
            if (count > 0) {
                attemptStatusCounts[status.wireValue] = count
            }
        }

        val failureClassCounts = linkedMapOf<String, Int>()
        for (fc in FailureClass.entries) {
            val count = input.recentAttempts.count { it.failureClass == fc }
            if (count > 0) {
                failureClassCounts[fc.wireValue] = count
            }
        }

        val errorCodeCounts = linkedMapOf<String, Int>()
        for (attempt in input.recentAttempts) {
            val rawCode = attempt.errorCode?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val safeCode = sanitizeErrorCode(rawCode, sensitiveTokens)
            errorCodeCounts[safeCode] = (errorCodeCounts[safeCode] ?: 0) + 1
        }

        val barkSummary = sanitizeBarkStatus(input.rawBarkUrl, sensitiveTokens)

        val probeSummary = input.latestProbeReport?.let { report ->
            val failedKeys = report.items
                .filter { !it.hit }
                .map { item ->
                    if (SAFE_PROBE_KEY_REGEX.matches(item.key)) item.key else "UNKNOWN_KEY"
                }
            SanitizedProbeSummary(
                checkedAtIso = formatIso(report.checkedAtEpochMs),
                loggedIn = report.loggedIn,
                coreSkeletonOk = report.coreSkeletonOk,
                passedItemCount = report.items.count { it.hit },
                totalItemCount = report.items.size,
                failedKeys = failedKeys,
            )
        }

        val survivalSummary = SanitizedSurvivalSummary(
            notificationsGranted = input.notificationsGranted,
            batteryOptimizationIgnored = input.batteryOptimizationIgnored,
            exactAlarmAllowed = input.exactAlarmAllowed,
            autostartGuidanceConfirmed = input.autostartGuidanceConfirmed,
            overlayAutoReturnAllowed = input.survivalSnapshot?.overlayAutoReturnAllowed,
            backgroundPopupAllowed = input.survivalSnapshot?.backgroundPopupAllowed,
        )

        val generatedAtIso = formatIso(input.generatedAtEpochMs)

        val rawText = renderTextReport(
            generatedAtIso = generatedAtIso,
            appVersionName = safeAppVersionName,
            appVersionCode = input.appVersionCode,
            androidSdkInt = input.androidSdkInt,
            androidRelease = safeAndroidRelease,
            deviceManufacturer = safeDeviceManufacturer,
            deviceBrand = safeDeviceBrand,
            deviceModel = safeDeviceModel,
            romDisplay = safeRomDisplay,
            webViewVersion = safeWebViewVersion,
            selectorDigest = safeSelectorDigest,
            hasSelectorOverrides = input.hasSelectorOverrides,
            totalAccountCount = totalAccountCount,
            accountStatus = accountStatus,
            accountEnabled = accountEnabled,
            authFailCount = authFailCount,
            failureStreak = failureStreak,
            autoScanEnabled = autoScanEnabled,
            autoScanIntervalHours = autoScanIntervalHours,
            latestAutoScanStatus = latestAutoScanStatus,
            latestAutoScanAtIso = latestAutoScanAtIso,
            survival = survivalSummary,
            bark = barkSummary,
            activeTargetCount = activeTargetCount,
            enabledTargetCount = enabledTargetCount,
            unboundTemplateTargetCount = unboundTemplateTargetCount,
            recentSkipSummary = sanitizedSkipSummary,
            todayJobCount = input.todayJobs.size,
            todayJobStatusCounts = todayJobStatusCounts,
            recentAttemptCount = input.recentAttempts.size,
            attemptStatusCounts = attemptStatusCounts,
            failureClassCounts = failureClassCounts,
            errorCodeCounts = errorCodeCounts,
            probeSummary = probeSummary,
            runtimeLogLines = sanitizedRuntimeLogLines,
        )

        val rawJson = renderJsonReport(
            generatedAtIso = generatedAtIso,
            appVersionName = safeAppVersionName,
            appVersionCode = input.appVersionCode,
            androidSdkInt = input.androidSdkInt,
            androidRelease = safeAndroidRelease,
            deviceManufacturer = safeDeviceManufacturer,
            deviceBrand = safeDeviceBrand,
            deviceModel = safeDeviceModel,
            romDisplay = safeRomDisplay,
            webViewVersion = safeWebViewVersion,
            selectorDigest = safeSelectorDigest,
            hasSelectorOverrides = input.hasSelectorOverrides,
            totalAccountCount = totalAccountCount,
            accountStatus = accountStatus,
            accountEnabled = accountEnabled,
            authFailCount = authFailCount,
            failureStreak = failureStreak,
            autoScanEnabled = autoScanEnabled,
            autoScanIntervalHours = autoScanIntervalHours,
            latestAutoScanStatus = latestAutoScanStatus,
            latestAutoScanAtIso = latestAutoScanAtIso,
            survival = survivalSummary,
            bark = barkSummary,
            activeTargetCount = activeTargetCount,
            enabledTargetCount = enabledTargetCount,
            unboundTemplateTargetCount = unboundTemplateTargetCount,
            recentSkipSummary = sanitizedSkipSummary,
            todayJobCount = input.todayJobs.size,
            todayJobStatusCounts = todayJobStatusCounts,
            recentAttemptCount = input.recentAttempts.size,
            attemptStatusCounts = attemptStatusCounts,
            failureClassCounts = failureClassCounts,
            errorCodeCounts = errorCodeCounts,
            probeSummary = probeSummary,
            runtimeLogLines = sanitizedRuntimeLogLines,
        )

        val finalSafeText = redactSensitiveText(rawText, sensitiveTokens)
        val finalSafeJson = redactSensitiveText(rawJson, sensitiveTokens)

        return SanitizedDiagnosticsReport(
            generatedAtIso = generatedAtIso,
            appVersionName = safeAppVersionName,
            appVersionCode = input.appVersionCode,
            androidSdkInt = input.androidSdkInt,
            androidRelease = safeAndroidRelease,
            webViewVersion = safeWebViewVersion,
            selectorDigest = safeSelectorDigest,
            hasSelectorOverrides = input.hasSelectorOverrides,
            totalAccountCount = totalAccountCount,
            accountStatus = accountStatus,
            accountEnabled = accountEnabled,
            authFailCount = authFailCount,
            failureStreak = failureStreak,
            autoScanEnabled = autoScanEnabled,
            autoScanIntervalHours = autoScanIntervalHours,
            latestAutoScanStatus = latestAutoScanStatus,
            latestAutoScanAtIso = latestAutoScanAtIso,
            survival = survivalSummary,
            bark = barkSummary,
            activeTargetCount = activeTargetCount,
            enabledTargetCount = enabledTargetCount,
            todayJobCount = input.todayJobs.size,
            todayJobStatusCounts = todayJobStatusCounts,
            recentAttemptCount = input.recentAttempts.size,
            recentAttemptStatusCounts = attemptStatusCounts,
            recentAttemptFailureClassCounts = failureClassCounts,
            recentAttemptErrorCodeCounts = errorCodeCounts,
            latestProbe = probeSummary,
            textReport = finalSafeText,
            jsonReport = finalSafeJson,
            unboundTemplateTargetCount = unboundTemplateTargetCount,
            recentSkipSummary = sanitizedSkipSummary,
            runtimeLogLines = sanitizedRuntimeLogLines,
            deviceManufacturer = safeDeviceManufacturer,
            deviceBrand = safeDeviceBrand,
            deviceModel = safeDeviceModel,
            romDisplay = safeRomDisplay,
        )
    }

    fun redactSensitiveText(
        raw: String,
        knownSensitiveTokens: Set<String> = emptySet(),
    ): String {
        var text = raw

        // 1. 清除已知个人隐私字段（昵称、UID、会话 ID、原始输入中的敏感词）
        val sortedTokens = knownSensitiveTokens
            .filter { it.length >= 2 }
            .sortedByDescending { it.length }
        for (token in sortedTokens) {
            if (text.contains(token)) {
                text = text.replace(token, "[REDACTED]")
            }
        }

        // 2. 脱敏 HTTP/HTTPS URL 与本地私有图片路径
        text = TEMPLATE_IMAGE_PATH_REGEX.replace(text, "[REDACTED_PATH]")
        text = URL_WITH_PATH_REGEX.replace(text) { match ->
            redactUrlMatch(match.value)
        }

        // 3. 脱敏 device_key=... / "device_key": "..."
        text = DEVICE_KEY_KV_REGEX.replace(text) { mr ->
            "${mr.groupValues[1]}[REDACTED]"
        }

        // 4. 脱敏 Cookie 与凭证键值对
        text = COOKIE_HEADER_REGEX.replace(text) { mr ->
            "${mr.groupValues[1]}[REDACTED]"
        }
        text = CREDENTIAL_KV_REGEX.replace(text) { mr ->
            "${mr.groupValues[1]}${mr.groupValues[2]}[REDACTED]"
        }

        // 5. 脱敏抖音 sec_uid、长数字会话 ID（>=14位）及疑似长高熵密钥
        text = DOUYIN_SEC_UID_REGEX.replace(text, "[REDACTED_UID]")
        text = LONG_DIGIT_ID_REGEX.replace(text, "[REDACTED_ID]")
        text = LONG_OPAQUE_TOKEN_REGEX.replace(text) { mr ->
            val token = mr.value
            if (token in SAFE_SCHEMA_IDENTIFIERS ||
                token.startsWith("UNKNOWN_") ||
                token.startsWith("VERIFICATION_") ||
                token.startsWith("FAILED_")
            ) {
                token
            } else {
                "[REDACTED_TOKEN]"
            }
        }

        return text
    }

    private fun redactUrlMatch(rawUrl: String): String {
        val cleaned = rawUrl.trimEnd(',', ';', ')', '}')
        if (ALREADY_MASKED_BARK_URL_REGEX.matches(cleaned)) {
            return cleaned
        }
        val normalizedAndMasked = try {
            val normalized = BarkUrlNormalizer.normalize(cleaned)
            BarkUrlNormalizer.mask(normalized)
        } catch (_: Throwable) {
            null
        }
        if (!normalizedAndMasked.isNullOrBlank() && ALREADY_MASKED_BARK_URL_REGEX.matches(normalizedAndMasked)) {
            return normalizedAndMasked
        }
        val schemeHost = Regex("""^(https?://[^/?#\s]+)""", RegexOption.IGNORE_CASE).find(cleaned)?.groupValues?.getOrNull(1)
        return if (!schemeHost.isNullOrBlank()) {
            "$schemeHost/[REDACTED_PATH]"
        } else {
            "[REDACTED_URL]"
        }
    }

    private fun collectSensitiveTokens(input: RawDiagnosticsContext): Set<String> {
        val tokens = LinkedHashSet<String>()
        fun addToken(value: String?) {
            val cleaned = value?.trim().orEmpty()
            if (cleaned.length >= 2) {
                tokens.add(cleaned)
            }
        }

        input.account?.let { acc ->
            addToken(acc.selfUid)
            addToken(acc.douyinNickname)
            addToken(acc.displayName)
        }
        for (target in input.targets) {
            addToken(target.nicknameSnapshot)
            addToken(target.platformUserId)
            addToken(target.platformHandle)
            addToken(target.platformConversationId)
        }
        for (job in input.todayJobs) {
            addToken(job.summaryDetail)
        }
        for (extra in input.extraRawStrings) {
            addToken(extra)
        }
        input.rawBarkUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { rawBark ->
            // 如果调用方不小心传入了完整 URL 或 curl，抽出其中的 device_key 也放入脱敏词典
            val keyFromRaw = try {
                val normalized = BarkUrlNormalizer.normalize(rawBark)
                BarkUrlNormalizer.extractDeviceKey(normalized)
            } catch (_: Throwable) {
                BarkUrlNormalizer.extractDeviceKey(rawBark)
            }
            if (keyFromRaw.isNotBlank() && !keyFromRaw.startsWith("****")) {
                addToken(keyFromRaw)
            }
        }
        return tokens
    }

    private fun sanitizeBarkStatus(
        rawOrMaskedBarkUrl: String?,
        sensitiveTokens: Set<String>,
    ): SanitizedBarkSummary {
        val cleaned = rawOrMaskedBarkUrl?.trim().orEmpty()
        if (cleaned.isEmpty()) {
            return SanitizedBarkSummary(configured = false, maskedUrl = null)
        }

        val masked = try {
            if (ALREADY_MASKED_BARK_URL_REGEX.matches(cleaned)) {
                cleaned
            } else {
                val normalized = BarkUrlNormalizer.normalize(cleaned)
                BarkUrlNormalizer.mask(normalized)
            }
        } catch (_: Throwable) {
            null
        }

        val safeMasked = if (masked.isNullOrBlank() || masked == "****") {
            "****"
        } else {
            redactSensitiveText(masked, sensitiveTokens)
        }
        return SanitizedBarkSummary(configured = true, maskedUrl = safeMasked)
    }

    private fun sanitizeVersionString(raw: String?, sensitiveTokens: Set<String>): String {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.isEmpty()) return "unknown"
        if (sensitiveTokens.any { cleaned.contains(it) } ||
            SENSITIVE_WORD_REGEX.containsMatchIn(cleaned) ||
            URL_WITH_PATH_REGEX.containsMatchIn(cleaned)
        ) {
            return "redacted"
        }
        val firstToken = cleaned.substringBefore(' ').trim()
        return if (SAFE_VERSION_REGEX.matches(firstToken)) {
            firstToken
        } else {
            "redacted"
        }
    }

    private fun sanitizeShortToken(
        raw: String?,
        fallback: String,
        sensitiveTokens: Set<String>,
    ): String {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.isEmpty()) return fallback
        if (sensitiveTokens.any { cleaned.contains(it) } ||
            SENSITIVE_WORD_REGEX.containsMatchIn(cleaned) ||
            URL_WITH_PATH_REGEX.containsMatchIn(cleaned)
        ) {
            return fallback
        }
        return if (SAFE_VERSION_REGEX.matches(cleaned)) cleaned else fallback
    }

    private fun sanitizeDeviceField(
        raw: String?,
        maxLength: Int,
        sensitiveTokens: Set<String>,
    ): String {
        val normalized = raw
            ?.replace(Regex("""[\r\n\t]+"""), " ")
            ?.replace(Regex("""\s{2,}"""), " ")
            ?.trim()
            .orEmpty()
        if (normalized.isEmpty()) return "unknown"
        if (sensitiveTokens.any { normalized.contains(it) } ||
            SENSITIVE_WORD_REGEX.containsMatchIn(normalized) ||
            URL_WITH_PATH_REGEX.containsMatchIn(normalized) ||
            DEVICE_KEY_KV_REGEX.containsMatchIn(normalized) ||
            COOKIE_HEADER_REGEX.containsMatchIn(normalized) ||
            CREDENTIAL_KV_REGEX.containsMatchIn(normalized) ||
            DOUYIN_SEC_UID_REGEX.containsMatchIn(normalized) ||
            LONG_DIGIT_ID_REGEX.containsMatchIn(normalized) ||
            LONG_OPAQUE_TOKEN_REGEX.containsMatchIn(normalized) ||
            TEMPLATE_IMAGE_PATH_REGEX.containsMatchIn(normalized)
        ) {
            return "unknown"
        }
        if (!SAFE_DEVICE_FIELD_REGEX.matches(normalized)) {
            return "unknown"
        }
        val clamped = normalized.take(maxLength).trim()
        if (clamped.isEmpty()) return "unknown"
        val redacted = redactSensitiveText(clamped, sensitiveTokens).trim()
        return if (redacted == clamped) clamped else "unknown"
    }

    private fun isKnownDeviceValue(value: String): Boolean =
        value.isNotBlank() && !value.equals("unknown", ignoreCase = true)

    private fun formatDeviceSummary(
        manufacturer: String,
        brand: String,
        model: String,
    ): String {
        val m = manufacturer.takeIf(::isKnownDeviceValue)
        val b = brand.takeIf(::isKnownDeviceValue)
        val d = model.takeIf(::isKnownDeviceValue)
        val vendorLabel = when {
            m != null && b != null && m.equals(b, ignoreCase = true) -> m
            m != null && b != null -> "$m / $b"
            m != null -> m
            b != null -> b
            else -> null
        }
        return when {
            vendorLabel != null && d != null && d.equals(vendorLabel, ignoreCase = true) -> vendorLabel
            vendorLabel != null && d != null -> "$vendorLabel · $d"
            vendorLabel != null -> vendorLabel
            d != null -> d
            else -> "unknown"
        }
    }

    private fun sanitizeDigest(raw: String?, sensitiveTokens: Set<String>): String {
        val cleaned = raw?.trim().orEmpty()
        if (SAFE_DIGEST_REGEX.matches(cleaned) && sensitiveTokens.none { cleaned.contains(it) }) {
            return cleaned.lowercase(Locale.ROOT)
        }
        return "unknown"
    }

    private fun sanitizeErrorCode(raw: String, sensitiveTokens: Set<String>): String {
        if (sensitiveTokens.any { raw.contains(it) }) return "redacted_code"
        if (SENSITIVE_WORD_REGEX.containsMatchIn(raw) ||
            URL_WITH_PATH_REGEX.containsMatchIn(raw) ||
            DOUYIN_SEC_UID_REGEX.containsMatchIn(raw) ||
            LONG_DIGIT_ID_REGEX.containsMatchIn(raw)
        ) {
            return "redacted_code"
        }
        return if (SAFE_ERROR_CODE_REGEX.matches(raw)) {
            raw
        } else {
            "redacted_code"
        }
    }

    private fun sanitizeRecentSkipSummary(
        raw: String?,
        sensitiveTokens: Set<String>,
    ): String? {
        val cleaned = raw
            ?.replace(Regex("""[\r\n\t]+"""), " ")
            ?.replace(Regex("""\s{2,}"""), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val redacted = redactSensitiveText(cleaned, sensitiveTokens).trim()
        return redacted.take(240).ifEmpty { null }
    }

    private fun sanitizeRuntimeLogLines(
        rawLines: List<String>,
        sensitiveTokens: Set<String>,
        maxLines: Int = 120,
        maxTotalChars: Int = 10_000,
    ): List<String> {
        if (rawLines.isEmpty()) return emptyList()
        val sanitized = rawLines
            .asSequence()
            .map { line ->
                line.replace(Regex("""[\r\n\t]+"""), " ")
                    .replace(Regex("""\s{2,}"""), " ")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .toList()
            .takeLast(maxLines)
            .map { line ->
                val redacted = redactSensitiveText(line, sensitiveTokens).trim()
                if (redacted.length <= 320) redacted else redacted.take(319).trimEnd() + "…"
            }
            .filter { it.isNotEmpty() }

        if (sanitized.isEmpty()) return emptyList()
        val keptReversed = ArrayList<String>(sanitized.size)
        var totalChars = 0
        for (i in sanitized.lastIndex downTo 0) {
            val line = sanitized[i]
            val extra = if (keptReversed.isEmpty()) line.length else line.length + 1
            if (totalChars + extra > maxTotalChars) break
            keptReversed.add(line)
            totalChars += extra
        }
        if (keptReversed.isEmpty()) {
            return listOf(sanitized.last().takeLast(maxTotalChars))
        }
        keptReversed.reverse()
        return keptReversed
    }

    private fun formatIso(epochMs: Long): String =
        ISO_FORMATTER.format(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC))

    private fun renderTextReport(
        generatedAtIso: String,
        appVersionName: String,
        appVersionCode: Long,
        androidSdkInt: Int,
        androidRelease: String,
        deviceManufacturer: String,
        deviceBrand: String,
        deviceModel: String,
        romDisplay: String,
        webViewVersion: String,
        selectorDigest: String,
        hasSelectorOverrides: Boolean,
        totalAccountCount: Int,
        accountStatus: String,
        accountEnabled: Boolean,
        authFailCount: Int,
        failureStreak: Int,
        autoScanEnabled: Boolean,
        autoScanIntervalHours: Int,
        latestAutoScanStatus: String?,
        latestAutoScanAtIso: String?,
        survival: SanitizedSurvivalSummary,
        bark: SanitizedBarkSummary,
        activeTargetCount: Int,
        enabledTargetCount: Int,
        unboundTemplateTargetCount: Int,
        recentSkipSummary: String?,
        todayJobCount: Int,
        todayJobStatusCounts: Map<String, Int>,
        recentAttemptCount: Int,
        attemptStatusCounts: Map<String, Int>,
        failureClassCounts: Map<String, Int>,
        errorCodeCounts: Map<String, Int>,
        probeSummary: SanitizedProbeSummary?,
        runtimeLogLines: List<String>,
    ): String = buildString {
        appendLine("=== 抖音续火工脱敏诊断报告 ===")
        appendLine("生成时间 (UTC): $generatedAtIso")
        appendLine("应用版本: $appVersionName ($appVersionCode)")
        appendLine("Android 系统: SDK $androidSdkInt (Android $androidRelease)")
        appendLine("设备型号: ${formatDeviceSummary(deviceManufacturer, deviceBrand, deviceModel)}")
        if (isKnownDeviceValue(romDisplay)) {
            appendLine("系统 ROM: $romDisplay")
        }
        appendLine("WebView 内核版本: $webViewVersion")
        appendLine("定位器摘要: $selectorDigest (本地覆写: ${if (hasSelectorOverrides) "是" else "否"})")
        appendLine()
        appendLine("[账号状态（已脱敏）]")
        appendLine("账号总数: $totalAccountCount | 状态: $accountStatus | 总开关: ${if (accountEnabled) "开启" else "关闭"}")
        appendLine("连续鉴权失败次数: $authFailCount | 连续批处理终态失败次数: $failureStreak")
        appendLine("官方火花自动同步: ${if (autoScanEnabled) "开启" else "关闭"} | 周期: ${autoScanIntervalHours}小时")
        appendLine(
            "最近自动扫描: ${latestAutoScanStatus ?: "无"}" +
                (latestAutoScanAtIso?.let { " | 时间 (UTC): $it" } ?: ""),
        )
        appendLine()
        appendLine("[后台保活体检]")
        appendLine("通知权限: ${survival.notificationsGranted}")
        appendLine("电池优化白名单: ${survival.batteryOptimizationIgnored}")
        appendLine("精确闹钟权限: ${survival.exactAlarmAllowed}")
        appendLine("自启动与后台确认: ${survival.autostartGuidanceConfirmed}")
        if (survival.overlayAutoReturnAllowed != null) {
            appendLine("悬浮窗自动跳回: ${survival.overlayAutoReturnAllowed}")
        }
        if (survival.backgroundPopupAllowed != null) {
            appendLine("后台弹出界面: ${survival.backgroundPopupAllowed}")
        }
        appendLine()
        appendLine("[Bark 推送（已脱敏）]")
        appendLine("已配置: ${bark.configured}${bark.maskedUrl?.let { " | 脱敏地址: $it" } ?: ""}")
        appendLine()
        appendLine("[目标与任务统计]")
        appendLine("活跃对象总数: $activeTargetCount | 已启用对象数: $enabledTargetCount")
        appendLine("未绑定模板的启用好友数: $unboundTemplateTargetCount")
        if (!recentSkipSummary.isNullOrBlank()) {
            appendLine("最近跳过摘要: $recentSkipSummary")
        }
        appendLine("今日任务总数: $todayJobCount")
        appendLine("今日任务状态分布: ${formatCounts(todayJobStatusCounts)}")
        appendLine("近期发送尝试总计: $recentAttemptCount")
        appendLine("近期尝试状态分布: ${formatCounts(attemptStatusCounts)}")
        appendLine("近期失败分类分布: ${formatCounts(failureClassCounts)}")
        appendLine("近期错误码计数: ${formatCounts(errorCodeCounts)}")
        if (probeSummary != null) {
            appendLine()
            appendLine("[最近只读结构体检]")
            appendLine("时间 (UTC): ${probeSummary.checkedAtIso}")
            appendLine(
                "已登录: ${probeSummary.loggedIn} | 核心骨架正常: ${probeSummary.coreSkeletonOk} " +
                    "| 命中: ${probeSummary.passedItemCount}/${probeSummary.totalItemCount}",
            )
            appendLine(
                "未命中键: ${if (probeSummary.failedKeys.isEmpty()) "无" else probeSummary.failedKeys.joinToString(", ")}",
            )
        }
        appendLine()
        appendLine("[近期脱敏运行轨迹]")
        if (runtimeLogLines.isEmpty()) {
            appendLine("无")
        } else {
            for (line in runtimeLogLines) {
                appendLine(line)
            }
        }
    }.trimEnd()

    private fun formatCounts(counts: Map<String, Int>): String =
        if (counts.isEmpty()) {
            "无"
        } else {
            counts.entries.joinToString(", ") { "${it.key}=${it.value}" }
        }

    private fun renderJsonReport(
        generatedAtIso: String,
        appVersionName: String,
        appVersionCode: Long,
        androidSdkInt: Int,
        androidRelease: String,
        deviceManufacturer: String,
        deviceBrand: String,
        deviceModel: String,
        romDisplay: String,
        webViewVersion: String,
        selectorDigest: String,
        hasSelectorOverrides: Boolean,
        totalAccountCount: Int,
        accountStatus: String,
        accountEnabled: Boolean,
        authFailCount: Int,
        failureStreak: Int,
        autoScanEnabled: Boolean,
        autoScanIntervalHours: Int,
        latestAutoScanStatus: String?,
        latestAutoScanAtIso: String?,
        survival: SanitizedSurvivalSummary,
        bark: SanitizedBarkSummary,
        activeTargetCount: Int,
        enabledTargetCount: Int,
        unboundTemplateTargetCount: Int,
        recentSkipSummary: String?,
        todayJobCount: Int,
        todayJobStatusCounts: Map<String, Int>,
        recentAttemptCount: Int,
        attemptStatusCounts: Map<String, Int>,
        failureClassCounts: Map<String, Int>,
        errorCodeCounts: Map<String, Int>,
        probeSummary: SanitizedProbeSummary?,
        runtimeLogLines: List<String>,
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        appendField(sb, 1, "generatedAtIso", generatedAtIso, comma = true)
        appendField(sb, 1, "appVersionName", appVersionName, comma = true)
        appendNumberField(sb, 1, "appVersionCode", appVersionCode, comma = true)
        appendNumberField(sb, 1, "androidSdkInt", androidSdkInt, comma = true)
        appendField(sb, 1, "androidRelease", androidRelease, comma = true)
        appendField(sb, 1, "deviceManufacturer", deviceManufacturer, comma = true)
        appendField(sb, 1, "deviceBrand", deviceBrand, comma = true)
        appendField(sb, 1, "deviceModel", deviceModel, comma = true)
        appendField(sb, 1, "romDisplay", romDisplay, comma = true)
        appendField(sb, 1, "webViewVersion", webViewVersion, comma = true)
        appendField(sb, 1, "selectorDigest", selectorDigest, comma = true)
        appendBoolField(sb, 1, "hasSelectorOverrides", hasSelectorOverrides, comma = true)
        appendNumberField(sb, 1, "totalAccountCount", totalAccountCount, comma = true)
        appendField(sb, 1, "accountStatus", accountStatus, comma = true)
        appendBoolField(sb, 1, "accountEnabled", accountEnabled, comma = true)
        appendNumberField(sb, 1, "authFailCount", authFailCount, comma = true)
        appendNumberField(sb, 1, "failureStreak", failureStreak, comma = true)
        appendBoolField(sb, 1, "autoScanEnabled", autoScanEnabled, comma = true)
        appendNumberField(sb, 1, "autoScanIntervalHours", autoScanIntervalHours, comma = true)
        appendNullableStringField(sb, 1, "latestAutoScanStatus", latestAutoScanStatus, comma = true)
        appendNullableStringField(sb, 1, "latestAutoScanAtIso", latestAutoScanAtIso, comma = true)

        sb.append("  \"survivalChecks\": {\n")
        appendBoolField(sb, 2, "notificationsGranted", survival.notificationsGranted, comma = true)
        appendBoolField(sb, 2, "batteryOptimizationIgnored", survival.batteryOptimizationIgnored, comma = true)
        appendBoolField(sb, 2, "exactAlarmAllowed", survival.exactAlarmAllowed, comma = true)
        val hasExtraSurvival = survival.overlayAutoReturnAllowed != null || survival.backgroundPopupAllowed != null
        appendBoolField(sb, 2, "autostartGuidanceConfirmed", survival.autostartGuidanceConfirmed, comma = hasExtraSurvival)
        if (survival.overlayAutoReturnAllowed != null) {
            appendBoolField(
                sb,
                2,
                "overlayAutoReturnAllowed",
                survival.overlayAutoReturnAllowed,
                comma = survival.backgroundPopupAllowed != null,
            )
        }
        if (survival.backgroundPopupAllowed != null) {
            appendBoolField(sb, 2, "backgroundPopupAllowed", survival.backgroundPopupAllowed, comma = false)
        }
        sb.append("  },\n")

        sb.append("  \"bark\": {\n")
        appendBoolField(sb, 2, "configured", bark.configured, comma = true)
        appendNullableStringField(sb, 2, "maskedUrl", bark.maskedUrl, comma = false)
        sb.append("  },\n")

        appendNumberField(sb, 1, "activeTargetCount", activeTargetCount, comma = true)
        appendNumberField(sb, 1, "enabledTargetCount", enabledTargetCount, comma = true)
        appendNumberField(sb, 1, "unboundTemplateTargetCount", unboundTemplateTargetCount, comma = true)
        appendNullableStringField(sb, 1, "recentSkipSummary", recentSkipSummary, comma = true)
        appendNumberField(sb, 1, "todayJobCount", todayJobCount, comma = true)
        appendIntMapField(sb, 1, "todayJobStatusCounts", todayJobStatusCounts, comma = true)
        appendNumberField(sb, 1, "recentAttemptCount", recentAttemptCount, comma = true)
        appendIntMapField(sb, 1, "recentAttemptStatusCounts", attemptStatusCounts, comma = true)
        appendIntMapField(sb, 1, "recentAttemptFailureClassCounts", failureClassCounts, comma = true)
        appendIntMapField(sb, 1, "recentAttemptErrorCodeCounts", errorCodeCounts, comma = true)

        sb.append("  \"latestProbe\": ")
        if (probeSummary == null) {
            sb.append("null,\n")
        } else {
            sb.append("{\n")
            appendField(sb, 2, "checkedAtIso", probeSummary.checkedAtIso, comma = true)
            appendBoolField(sb, 2, "loggedIn", probeSummary.loggedIn, comma = true)
            appendBoolField(sb, 2, "coreSkeletonOk", probeSummary.coreSkeletonOk, comma = true)
            appendNumberField(sb, 2, "passedItemCount", probeSummary.passedItemCount, comma = true)
            appendNumberField(sb, 2, "totalItemCount", probeSummary.totalItemCount, comma = true)
            appendStringListField(sb, 2, "failedKeys", probeSummary.failedKeys, comma = false)
            sb.append("  },\n")
        }
        appendStringListField(sb, 1, "runtimeLogLines", runtimeLogLines, comma = false)
        sb.append("}")
        return sb.toString()
    }

    private fun appendIndent(sb: StringBuilder, indent: Int) {
        repeat(indent) { sb.append("  ") }
    }

    private fun appendField(sb: StringBuilder, indent: Int, key: String, value: String, comma: Boolean) {
        appendIndent(sb, indent)
        appendEscapedJsonString(sb, key)
        sb.append(": ")
        appendEscapedJsonString(sb, value)
        if (comma) sb.append(',')
        sb.append('\n')
    }

    private fun appendNullableStringField(sb: StringBuilder, indent: Int, key: String, value: String?, comma: Boolean) {
        appendIndent(sb, indent)
        appendEscapedJsonString(sb, key)
        sb.append(": ")
        if (value == null) sb.append("null") else appendEscapedJsonString(sb, value)
        if (comma) sb.append(',')
        sb.append('\n')
    }

    private fun appendNumberField(sb: StringBuilder, indent: Int, key: String, value: Number, comma: Boolean) {
        appendIndent(sb, indent)
        appendEscapedJsonString(sb, key)
        sb.append(": ").append(value)
        if (comma) sb.append(',')
        sb.append('\n')
    }

    private fun appendBoolField(sb: StringBuilder, indent: Int, key: String, value: Boolean, comma: Boolean) {
        appendIndent(sb, indent)
        appendEscapedJsonString(sb, key)
        sb.append(": ").append(value)
        if (comma) sb.append(',')
        sb.append('\n')
    }

    private fun appendIntMapField(sb: StringBuilder, indent: Int, key: String, map: Map<String, Int>, comma: Boolean) {
        appendIndent(sb, indent)
        appendEscapedJsonString(sb, key)
        sb.append(": {")
        if (map.isNotEmpty()) {
            sb.append('\n')
            val entries = map.entries.toList()
            entries.forEachIndexed { idx, (k, v) ->
                appendIndent(sb, indent + 1)
                appendEscapedJsonString(sb, k)
                sb.append(": ").append(v)
                if (idx < entries.lastIndex) sb.append(',')
                sb.append('\n')
            }
            appendIndent(sb, indent)
        }
        sb.append('}')
        if (comma) sb.append(',')
        sb.append('\n')
    }

    private fun appendStringListField(sb: StringBuilder, indent: Int, key: String, list: List<String>, comma: Boolean) {
        appendIndent(sb, indent)
        appendEscapedJsonString(sb, key)
        sb.append(": [")
        list.forEachIndexed { idx, item ->
            if (idx > 0) sb.append(", ")
            appendEscapedJsonString(sb, item)
        }
        sb.append(']')
        if (comma) sb.append(',')
        sb.append('\n')
    }

    private fun appendEscapedJsonString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }
}
