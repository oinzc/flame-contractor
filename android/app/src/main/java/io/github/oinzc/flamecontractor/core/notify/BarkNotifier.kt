package io.github.oinzc.flamecontractor.core.notify

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class NotifyLevel(val wireValue: String) {
    ACTIVE("active"),
    TIME_SENSITIVE("timeSensitive"),
    CRITICAL("critical"),
    PASSIVE("passive"),
}

data class BarkSendRecord(
    val title: String,
    val body: String,
    val level: NotifyLevel,
)

data class BarkProbeResult(
    val ok: Boolean,
    val detail: String,
)

class BarkNotifier(
    private val baseUrlProvider: () -> String?,
    client: OkHttpClient = defaultHttpClient(DEFAULT_TIMEOUT_SEC),
    private val timeoutSec: Long = DEFAULT_TIMEOUT_SEC,
    private val sender: (suspend (title: String, body: String, level: NotifyLevel) -> Unit)? = null,
) {
    private val effectiveTimeoutSec: Long = timeoutSec.coerceAtLeast(1L)

    private val effectiveClient: OkHttpClient = client.newBuilder()
        .callTimeout(effectiveTimeoutSec, TimeUnit.SECONDS)
        .connectTimeout(effectiveTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(effectiveTimeoutSec, TimeUnit.SECONDS)
        .writeTimeout(effectiveTimeoutSec, TimeUnit.SECONDS)
        .build()

    @Volatile
    var lastSent: BarkSendRecord? = null
        private set

    val enabled: Boolean
        get() = !baseUrlProvider()?.trim().isNullOrEmpty()

    suspend fun send(
        title: String,
        body: String,
        level: NotifyLevel = NotifyLevel.ACTIVE,
    ): Boolean {
        val baseUrl = baseUrlProvider()?.trim().orEmpty()
        if (baseUrl.isEmpty()) return false
        val targetUrl = resolveUrl(baseUrl, level) ?: return false

        lastSent = BarkSendRecord(title = title, body = body, level = level)

        val injectedSender = sender
        if (injectedSender != null) {
            return try {
                injectedSender(title, body, level)
                true
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                false
            }
        }

        val deviceKey = BarkUrlNormalizer.extractDeviceKey(baseUrl)
        if (deviceKey.isEmpty()) return false

        val jsonPayload = BarkJsonCodec.encodeStringObject(
            linkedMapOf(
                "device_key" to deviceKey,
                "title" to title,
                "body" to body,
            ),
        )

        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(targetUrl)
                    .post(jsonPayload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                effectiveClient.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun probe(
        title: String,
        body: String,
        level: NotifyLevel = NotifyLevel.ACTIVE,
    ): BarkProbeResult {
        val baseUrl = baseUrlProvider()?.trim().orEmpty()
        if (baseUrl.isEmpty()) {
            return BarkProbeResult(false, "尚未配置 Bark 推送地址")
        }
        val targetUrl = resolveUrl(baseUrl, level)
            ?: return BarkProbeResult(false, "Bark 推送地址格式无效，请检查后重试")

        lastSent = BarkSendRecord(title = title, body = body, level = level)

        val injectedSender = sender
        if (injectedSender != null) {
            return try {
                injectedSender(title, body, level)
                BarkProbeResult(true, "测试推送已送达 Bark 服务，请检查设备通知")
            } catch (ce: CancellationException) {
                throw ce
            } catch (exc: Throwable) {
                BarkProbeResult(false, "连接 Bark 服务失败（${exc.javaClass.simpleName}）")
            }
        }

        val deviceKey = BarkUrlNormalizer.extractDeviceKey(baseUrl)
        if (deviceKey.isEmpty()) {
            return BarkProbeResult(
                false,
                "Bark 推送地址缺少设备 Key 路径段，请按 https://api.day.app/<device_key> 格式配置",
            )
        }

        val jsonPayload = BarkJsonCodec.encodeStringObject(
            linkedMapOf(
                "device_key" to deviceKey,
                "title" to title,
                "body" to body,
            ),
        )

        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(targetUrl)
                    .post(jsonPayload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                effectiveClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use BarkProbeResult(
                            false,
                            "Bark 服务返回 HTTP ${response.code}，请检查推送地址是否正确",
                        )
                    }
                    val respText = response.body?.string().orEmpty()
                    val parsed = BarkJsonCodec.parseObjectOrNull(respText)
                        ?: return@use BarkProbeResult(
                            false,
                            "推送地址未返回合法的 Bark JSON 响应，请检查是否指向 Bark API 服务",
                        )
                    val rawCode = parsed["code"]
                    if (rawCode != null) {
                        val intCode = (rawCode as? Long)?.toInt()
                        if (intCode != 200) {
                            val safeCode = intCode?.toString() ?: "异常"
                            return@use BarkProbeResult(
                                false,
                                "Bark 服务返回业务错误（code $safeCode），请检查推送地址与设备 Key",
                            )
                        }
                    }
                    BarkProbeResult(true, "测试推送已送达 Bark 服务，请检查设备通知")
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (exc: Throwable) {
            // 仅返回异常类名，绝不泄露 URL、device_key 或底层异常详情
            BarkProbeResult(false, "连接 Bark 服务失败（${exc.javaClass.simpleName}）")
        }
    }

    internal fun resolveUrl(baseUrl: String, level: NotifyLevel): HttpUrl? {
        val parsed = baseUrl.toHttpUrlOrNull() ?: return null
        return parsed.newBuilder()
            .removeAllQueryParameters("level")
            .addQueryParameter("level", level.wireValue)
            .build()
    }

    companion object {
        const val DEFAULT_TIMEOUT_SEC = 6L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(timeoutSec: Long): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(timeoutSec, TimeUnit.SECONDS)
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
    }
}
