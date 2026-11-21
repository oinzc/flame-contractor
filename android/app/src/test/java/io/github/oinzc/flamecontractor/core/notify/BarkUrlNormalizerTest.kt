package io.github.oinzc.flamecontractor.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class BarkUrlNormalizerTest {

    @Test
    fun normalizesPlainUrlGetCurlFormPostAndJsonPushCurl() {
        // 1) 纯 URL（含尾斜杠与中文示例段）
        assertEquals(
            "https://api.day.app/AbCdEf123456",
            BarkUrlNormalizer.normalize("https://api.day.app/AbCdEf123456"),
        )
        assertEquals(
            "https://api.day.app/AbCdEf123456",
            BarkUrlNormalizer.normalize("https://api.day.app/AbCdEf123456/"),
        )
        assertEquals(
            "https://api.day.app/ExampleDeviceKey123456",
            BarkUrlNormalizer.normalize("https://api.day.app/ExampleDeviceKey123456/这里改成你自己的推送内容"),
        )

        // 2) GET curl（官方域名与自建前缀 + /title/body?query）
        assertEquals(
            "https://api.day.app/ExampleDeviceKey123456",
            BarkUrlNormalizer.normalize(
                "curl -X GET https://api.day.app/ExampleDeviceKey123456/title/body?group=example&ttl=600",
            ),
        )
        assertEquals(
            "https://bark.example.com/prefix/ExampleDeviceKey123456",
            BarkUrlNormalizer.normalize(
                "curl -X GET https://bark.example.com/prefix/ExampleDeviceKey123456/title/body?group=example&ttl=600",
            ),
        )
        assertEquals(
            "https://bark.example.com/prefix/ExampleDeviceKey123456",
            BarkUrlNormalizer.normalize(
                "curl https://bark.example.com/prefix/ExampleDeviceKey123456/测试内容/%E6%B5%8B%E8%AF%95",
            ),
        )

        // 3) 多行 POST 表单 curl
        val formPostCurl = """
            curl -X POST https://api.day.app/ExampleDeviceKey123456 \
              -d "title=title" \
              -d "body=body" \
              -d "group=example" \
              -d "ttl=600"
        """.trimIndent()
        assertEquals(
            "https://api.day.app/ExampleDeviceKey123456",
            BarkUrlNormalizer.normalize(formPostCurl),
        )

        // 表单中携带 device_key 发往 /push
        val formPushCurl = """
            curl -X POST https://api.day.app/push \
              -d "device_key=ExampleDeviceKey123456&title=title&body=body"
        """.trimIndent()
        assertEquals(
            "https://api.day.app/ExampleDeviceKey123456",
            BarkUrlNormalizer.normalize(formPushCurl),
        )

        // 4) 多行 POST JSON /push curl（官方域名与自建子路径）
        val jsonPushCurl = """
            curl -X POST https://api.day.app/push \
              -H "Content-Type: application/json" \
              -d '{
                "device_key": "ExampleDeviceKey123456",
                "title": "title",
                "body": "body",
                "group": "example",
                "ttl": 600
              }'
        """.trimIndent()
        assertEquals(
            "https://api.day.app/ExampleDeviceKey123456",
            BarkUrlNormalizer.normalize(jsonPushCurl),
        )

        val selfHostedJsonPushCurl =
            """curl -X POST https://bark.example.com/sub/push -H "Content-Type: application/json" -d '{"device_key": "ExampleDeviceKey123456"}'"""
        assertEquals(
            "https://bark.example.com/sub/ExampleDeviceKey123456",
            BarkUrlNormalizer.normalize(selfHostedJsonPushCurl),
        )

        // 5) extractDeviceKey 兼容 /<key> 与 /<key>/push
        assertEquals("abc123", BarkUrlNormalizer.extractDeviceKey("https://api.day.app/abc123"))
        assertEquals("abc123", BarkUrlNormalizer.extractDeviceKey("https://api.day.app/abc123/push"))
        assertEquals("", BarkUrlNormalizer.extractDeviceKey("https://api.day.app/push"))
    }

    @Test
    fun rejectsPlaceholderDeviceKey() {
        val badInputs = listOf(
            "",
            "   ",
            "ftp://api.day.app/key123456",
            "https://api.day.app/",
            "not-a-url",
            "https://api.day.app/push",
            "https://api.day.app/<device_key>",
            "https://api.day.app/<你的设备Key>",
            "https://api.day.app/{device_key}",
            "https://api.day.app/device_key",
            "https://api.day.app/YOUR_DEVICE_KEY",
            """curl -X POST https://api.day.app/push -d '{"title": "only"}'""",
            """curl -X POST https://api.day.app/push -d '{"device_key": "<device_key>"}'""",
            "https://api.day.app/" + "a".repeat(250),
            "curl https://api.day.app/key123 " + "x".repeat(2050),
        )

        for (bad in badInputs) {
            try {
                BarkUrlNormalizer.normalize(bad)
                fail("Expected IllegalArgumentException for input: $bad")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun masksDeviceKeyKeepingLast4Chars() {
        assertEquals(
            "https://api.day.app/****3456",
            BarkUrlNormalizer.mask("https://api.day.app/AbCdEf123456"),
        )
        assertEquals(
            "https://bark.example.com/custom/****ey99/push",
            BarkUrlNormalizer.mask("https://bark.example.com/custom/SecretKey99/push"),
        )
        assertEquals(
            "https://api.day.app/****3456",
            BarkUrlNormalizer.mask("https://api.day.app/AbCdEf123456?level=critical"),
        )
        assertEquals(
            "https://api.day.app/****",
            BarkUrlNormalizer.mask("https://api.day.app/abc"),
        )
        assertEquals(
            "https://api.day.app/****",
            BarkUrlNormalizer.mask("https://api.day.app/abcd"),
        )
        assertEquals("****", BarkUrlNormalizer.mask("not-a-url"))
        assertNull(BarkUrlNormalizer.mask(null))
        assertNull(BarkUrlNormalizer.mask("   "))
    }
}
