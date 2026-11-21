package io.github.oinzc.flamecontractor.core.notify

import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BarkNotifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Throwable) {
        }
    }

    @Test
    fun sendsV2JsonPostWithLevelQueryAndPreservesExistingParams() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":200,"message":"success"}"""))
        val secretKey = "TestBarkDeviceKey9876"
        val baseUrl = server.url("/$secretKey?group=flame&sound=minuet").toString()
        val notifier = BarkNotifier(
            baseUrlProvider = { baseUrl },
            client = OkHttpClient(),
            timeoutSec = 6L,
        )

        assertTrue(notifier.enabled)
        val ok = notifier.send(
            title = "续火结果待核对",
            body = "存在 1 条未知状态任务，请人工核对",
            level = NotifyLevel.TIME_SENSITIVE,
        )

        assertTrue(ok)
        val recorded = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/$secretKey", recorded.requestUrl?.encodedPath)
        assertEquals("flame", recorded.requestUrl?.queryParameter("group"))
        assertEquals("minuet", recorded.requestUrl?.queryParameter("sound"))
        assertEquals("timeSensitive", recorded.requestUrl?.queryParameter("level"))
        assertTrue(recorded.getHeader("Content-Type")?.startsWith("application/json") == true)

        val requestBody = recorded.body.readUtf8()
        assertTrue(requestBody.contains(""""device_key":"$secretKey""""))
        assertTrue(requestBody.contains(""""title":"续火结果待核对""""))
        assertTrue(requestBody.contains(""""body":"存在 1 条未知状态任务，请人工核对""""))
        assertEquals(
            BarkSendRecord("续火结果待核对", "存在 1 条未知状态任务，请人工核对", NotifyLevel.TIME_SENSITIVE),
            notifier.lastSent,
        )
    }

    @Test
    fun failsSilentlyOnHttpErrorsAndNetworkFailure() = runTest {
        // 1) HTTP 500 静默返回 false
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))
        val baseUrl = server.url("/DeviceKeyHttp500").toString()
        val notifier = BarkNotifier(
            baseUrlProvider = { baseUrl },
            client = OkHttpClient(),
            timeoutSec = 2L,
        )
        assertFalse(notifier.send("标题", "内容", NotifyLevel.CRITICAL))

        // 2) 网络中断（服务已关闭）静默返回 false 且不抛异常
        server.shutdown()
        assertFalse(notifier.send("标题", "内容", NotifyLevel.ACTIVE))
    }

    @Test
    fun skipsWhenUnconfiguredOrMissingDeviceKeyAndSupportsInjectedSender() = runTest {
        val unconfigured = BarkNotifier(baseUrlProvider = { null })
        assertFalse(unconfigured.enabled)
        assertFalse(unconfigured.send("t", "b"))

        val missingKey = BarkNotifier(baseUrlProvider = { server.url("/push").toString() })
        assertFalse(missingKey.send("t", "b"))

        val calls = mutableListOf<Triple<String, String, NotifyLevel>>()
        val injected = BarkNotifier(
            baseUrlProvider = { "https://api.day.app/AbCdEf123456" },
            sender = { title, body, level -> calls.add(Triple(title, body, level)) },
        )
        assertTrue(injected.send("测试标题", "测试内容", NotifyLevel.PASSIVE))
        assertEquals(listOf(Triple("测试标题", "测试内容", NotifyLevel.PASSIVE)), calls)

        val throwingSender = BarkNotifier(
            baseUrlProvider = { "https://api.day.app/AbCdEf123456" },
            sender = { _, _, _ -> throw RuntimeException("boom secret AbCdEf123456") },
        )
        assertFalse(throwingSender.send("标题", "内容"))
    }

    @Test
    fun probeRejectsFalsePositiveAndNeverLeaksSecretKey() = runTest {
        val secret = "SuperSecretBarkKey7788"
        val baseUrl = server.url("/$secret").toString()
        val notifier = BarkNotifier(
            baseUrlProvider = { baseUrl },
            client = OkHttpClient(),
            timeoutSec = 2L,
        )

        // 1) HTTP 200 但返回 HTML
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>wrong host $secret</html>"))
        val htmlRes = notifier.probe("测试", "内容")
        assertFalse(htmlRes.ok)
        assertFalse(htmlRes.detail.contains(secret))

        // 2) HTTP 200 但 Bark 业务错误 code=400
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"code":400,"message":"bad key $secret"}"""),
        )
        val bizRes = notifier.probe("测试", "内容")
        assertFalse(bizRes.ok)
        assertTrue(bizRes.detail.contains("400"))
        assertFalse(bizRes.detail.contains(secret))

        // 3) HTTP 400
        server.enqueue(MockResponse().setResponseCode(400).setBody("invalid $secret"))
        val httpRes = notifier.probe("测试", "内容")
        assertFalse(httpRes.ok)
        assertTrue(httpRes.detail.contains("400"))
        assertFalse(httpRes.detail.contains(secret))

        // 4) HTTP 200 且 code=200
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":200,"message":"success"}"""))
        val okRes = notifier.probe("测试", "内容")
        assertTrue(okRes.ok)
        assertFalse(okRes.detail.contains(secret))
    }

    @Test
    fun encryptedSecretStoreEncryptsWithAesGcmAndNeverWritesPlaintext() {
        val keyGen = KeyGenerator.getInstance("AES").apply { init(256) }
        val secretKey = keyGen.generateKey()
        val secretFile = File(tempFolder.root, "bark_secret.enc")
        val store = EncryptedSecretStore(secretFile = secretFile, keyProvider = { secretKey })

        assertNull(store.readBarkUrl())

        val plainUrl = "https://api.day.app/UltraSecretDeviceKey9999"
        store.saveBarkUrl(plainUrl)
        assertTrue(secretFile.isFile)

        // 磁盘密文绝不含明文 URL 或密钥
        val diskBytes = secretFile.readBytes()
        val diskText = String(diskBytes, Charsets.ISO_8859_1)
        assertFalse(diskText.contains("UltraSecretDeviceKey9999"))
        assertFalse(diskText.contains("api.day.app"))
        assertEquals(plainUrl, store.readBarkUrl())

        // 每次保存使用随机 IV，密文不同
        store.saveBarkUrl(plainUrl)
        val secondBytes = secretFile.readBytes()
        assertNotEquals(diskBytes.toList(), secondBytes.toList())
        assertEquals(plainUrl, store.readBarkUrl())

        // 密文被篡改时 GCM 认证失败，安全返回 null 而不崩溃
        val tampered = secondBytes.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0xFF).toByte()
        secretFile.writeBytes(tampered)
        assertNull(store.readBarkUrl())

        // 传 null 清空并删除文件
        store.saveBarkUrl(null)
        assertFalse(secretFile.exists())
        assertNull(store.readBarkUrl())
    }
}
