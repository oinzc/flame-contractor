package io.github.oinzc.flamecontractor.core.support

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class FeedbackKind(
    val wireValue: String,
    val label: String,
    val placeholder: String,
) {
    BUG(
        wireValue = "bug",
        label = "Bug 反馈",
        placeholder = "描述你遇到的问题、出现时机与看到的提示信息（至少 5 个字）…",
    ),
    FEATURE(
        wireValue = "feature",
        label = "功能建议",
        placeholder = "描述希望新增的功能或希望优化的使用场景（至少 5 个字）…",
    ),
    QUESTION(
        wireValue = "question",
        label = "使用疑问",
        placeholder = "写下你在使用或配置过程中遇到的疑问（至少 5 个字）…",
    ),
}

data class FeedbackSubmissionRequest(
    val kind: FeedbackKind,
    val title: String,
    val content: String,
    val contact: String,
    val version: String,
    val environment: String,
    val includeEnv: Boolean,
    val client: String = "android",
)

sealed class FeedbackSubmitResult {
    data class Success(
        val issueNumber: Int?,
        val issueUrl: String?,
        val message: String,
    ) : FeedbackSubmitResult()

    data class Failure(
        val message: String,
        val retryable: Boolean = true,
    ) : FeedbackSubmitResult()
}

/**
 * 应用内直连反馈提交客户端（基于 OkHttp，纯 JVM 可单测，零隐私外泄）。
 */
class FeedbackSubmitter(
    private val endpointProvider: () -> String,
    client: OkHttpClient = defaultHttpClient(),
) {
    private val effectiveClient: OkHttpClient = client.newBuilder()
        .callTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean
        get() = normalizeEndpoint(endpointProvider()) != null

    suspend fun submit(input: FeedbackSubmissionRequest): FeedbackSubmitResult {
        val validationError = validateInput(
            title = input.title,
            content = input.content,
            contact = input.contact,
        )
        if (validationError != null) {
            return FeedbackSubmitResult.Failure(validationError, retryable = false)
        }

        val endpoint = normalizeEndpoint(endpointProvider())
            ?: return FeedbackSubmitResult.Failure(
                message = "当前构建尚未配置直连反馈中转地址，请先复制脱敏反馈文本或前往 GitHub 提交。",
                retryable = false,
            )

        val payloadJson = encodeRequestJson(input)

        return try {
            withContext(Dispatchers.IO) {
                val httpReq = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/json")
                    .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                effectiveClient.newCall(httpReq).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    parseRelayResponse(resp.code, resp.isSuccessful, bodyStr)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ioe: IOException) {
            FeedbackSubmitResult.Failure(
                message = "网络连接失败，请检查网络后重试（已为你保留填写内容）",
                retryable = true,
            )
        } catch (_: Throwable) {
            FeedbackSubmitResult.Failure(
                message = "提交时发生异常，请稍后重试或复制反馈内容",
                retryable = true,
            )
        }
    }

    companion object {
        const val MIN_CONTENT_LEN: Int = 5
        const val MAX_CONTENT_LEN: Int = 2000
        const val MAX_TITLE_LEN: Int = 100
        const val MAX_CONTACT_LEN: Int = 100
        private const val TIMEOUT_SEC: Long = 15L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()

        fun normalizeEndpoint(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val parsed = trimmed.toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "https" && parsed.scheme != "http") return null
            return parsed.toString()
        }

        fun validateInput(
            title: String,
            content: String,
            contact: String,
        ): String? {
            val cleanContent = content.trim()
            if (cleanContent.length < MIN_CONTENT_LEN) {
                return "请至少输入 $MIN_CONTENT_LEN 个字的反馈内容"
            }
            if (cleanContent.length > MAX_CONTENT_LEN) {
                return "反馈内容请控制在 $MAX_CONTENT_LEN 字以内"
            }
            if (title.trim().length > MAX_TITLE_LEN) {
                return "标题请控制在 $MAX_TITLE_LEN 字以内"
            }
            if (contact.trim().length > MAX_CONTACT_LEN) {
                return "联系方式请控制在 $MAX_CONTACT_LEN 字以内"
            }
            return null
        }

        fun buildClipboardFallbackText(input: FeedbackSubmissionRequest): String = buildString {
            appendLine("【${input.kind.label}】${input.title.trim().ifEmpty { "应用内反馈" }}")
            appendLine()
            appendLine(input.content.trim())
            if (input.includeEnv && input.environment.isNotBlank()) {
                appendLine()
                appendLine("--- 运行环境（已脱敏）---")
                appendLine(input.environment.trim())
            }
            if (input.contact.isNotBlank()) {
                appendLine()
                append("联系方式：${input.contact.trim()}")
            }
        }

        internal fun encodeRequestJson(input: FeedbackSubmissionRequest): String = buildString {
            append('{')
            append("\"kind\":"); appendJsonString(input.kind.wireValue); append(',')
            append("\"title\":"); appendJsonString(input.title.trim()); append(',')
            append("\"content\":"); appendJsonString(input.content.trim()); append(',')
            append("\"contact\":"); appendJsonString(input.contact.trim()); append(',')
            append("\"client\":"); appendJsonString(input.client.trim().ifEmpty { "android" }); append(',')
            append("\"version\":"); appendJsonString(input.version.trim()); append(',')
            append("\"environment\":"); appendJsonString(if (input.includeEnv) input.environment.trim() else ""); append(',')
            append("\"includeEnv\":"); append(if (input.includeEnv) "true" else "false"); append(',')
            append("\"website\":\"\"")
            append('}')
        }

        internal fun parseRelayResponse(
            statusCode: Int,
            isSuccessful: Boolean,
            bodyStr: String,
        ): FeedbackSubmitResult {
            val message = extractJsonString(bodyStr, "message")
            val error = extractJsonString(bodyStr, "error")
            val issueNumber = extractJsonInt(bodyStr, "issueNumber")
            val issueUrl = extractJsonString(bodyStr, "issueUrl")

            if (isSuccessful) {
                val okMessage = message?.takeIf { it.isNotBlank() }
                    ?: if (issueNumber != null) {
                        "反馈已提交（#$issueNumber），感谢你的帮助！"
                    } else {
                        "反馈已提交成功，感谢你的帮助！"
                    }
                return FeedbackSubmitResult.Success(
                    issueNumber = issueNumber,
                    issueUrl = issueUrl,
                    message = okMessage,
                )
            }

            val errMessage = error?.takeIf { it.isNotBlank() } ?: when (statusCode) {
                400 -> "提交内容格式不符合要求，请检查后重试"
                413 -> "提交内容过长，请精简后重试"
                429 -> "提交过于频繁，请稍后再试"
                503 -> "反馈服务暂未就绪，请稍后再试"
                else -> "提交失败（HTTP $statusCode），请稍后重试"
            }
            return FeedbackSubmitResult.Failure(
                message = errMessage,
                retryable = statusCode >= 500 || statusCode == 429,
            )
        }

        private fun StringBuilder.appendJsonString(value: String) {
            append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch.code < 0x20) {
                            append("\\u")
                            append(ch.code.toString(16).padStart(4, '0'))
                        } else {
                            append(ch)
                        }
                    }
                }
            }
            append('"')
        }

        private fun extractJsonString(json: String, key: String): String? {
            val keyToken = "\"$key\""
            val keyIndex = json.indexOf(keyToken)
            if (keyIndex < 0) return null
            val colonIndex = json.indexOf(':', keyIndex + keyToken.length)
            if (colonIndex < 0) return null
            var i = colonIndex + 1
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < json.length) {
                val ch = json[i]
                if (ch == '\\' && i + 1 < json.length) {
                    val next = json[i + 1]
                    when (next) {
                        '"' -> { sb.append('"'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        '/' -> { sb.append('/'); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'u' -> {
                            if (i + 5 < json.length) {
                                val hex = json.substring(i + 2, i + 6).toIntOrNull(16)
                                if (hex != null) sb.append(hex.toChar())
                                i += 6
                            } else {
                                i += 2
                            }
                        }
                        else -> { sb.append(next); i += 2 }
                    }
                } else if (ch == '"') {
                    return sb.toString()
                } else {
                    sb.append(ch)
                    i++
                }
            }
            return null
        }

        private fun extractJsonInt(json: String, key: String): Int? {
            val keyToken = "\"$key\""
            val keyIndex = json.indexOf(keyToken)
            if (keyIndex < 0) return null
            val colonIndex = json.indexOf(':', keyIndex + keyToken.length)
            if (colonIndex < 0) return null
            var i = colonIndex + 1
            while (i < json.length && json[i].isWhitespace()) i++
            val start = i
            while (i < json.length && json[i].isDigit()) i++
            if (i == start) return null
            return json.substring(start, i).toIntOrNull()
        }
    }
}
