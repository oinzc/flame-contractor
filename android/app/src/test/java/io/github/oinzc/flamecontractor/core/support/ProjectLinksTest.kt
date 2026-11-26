package io.github.oinzc.flamecontractor.core.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectLinksTest {

    @Test
    fun `canonical repository and issue URLs target oinzc flame-contractor`() {
        assertEquals("https://github.com/oinzc/flame-contractor", ProjectLinks.REPO_URL)
        assertEquals("https://github.com/oinzc/flame-contractor/releases", ProjectLinks.RELEASES_URL)
        assertEquals("https://github.com/oinzc/flame-contractor/issues", ProjectLinks.ISSUES_URL)
        assertEquals("https://github.com/oinzc/flame-contractor/discussions", ProjectLinks.DISCUSSIONS_URL)
        assertEquals(
            "https://github.com/oinzc/flame-contractor/issues/new?template=feature_request.yml",
            ProjectLinks.buildFeatureRequestUrl(),
        )
    }

    @Test
    fun `android bug report url pre-fills template version environment and os safely`() {
        val url = ProjectLinks.buildAndroidBugReportUrl(
            versionName = "0.5.0",
            versionCode = 500,
            androidRelease = "15",
            apiLevel = 35,
            selectorDigest = "60dbb6a6f9e1abcd",
        )
        assertTrue(url.startsWith("https://github.com/oinzc/flame-contractor/issues/new?"))
        assertTrue(url.contains("template=bug_report.yml"))
        assertTrue(url.contains("environment=Android+App"))
        assertTrue(url.contains("version=v0.5.0+%28500%29"))
        assertTrue(url.contains("Android+15+%28API+35%29"))
        assertTrue(url.contains("60dbb6a6f9e1"))
        assertFalse(url.contains("60dbb6a6f9e1abcd"))
    }

    @Test
    fun `desensitized environment summary omits secrets and formats cleanly`() {
        val summary = ProjectLinks.buildDesensitizedEnvSummary(
            versionName = "0.5.0-debug",
            versionCode = 1,
            androidRelease = "15",
            apiLevel = 35,
            manufacturer = "Xiaomi",
            selectorDigest = "60dbb6a6f9e1",
            survivalPassed = 3,
            survivalTotal = 4,
        )
        assertTrue(summary.contains("v0.5.0-debug (1)"))
        assertTrue(summary.contains("Android 15 (API 35)"))
        assertTrue(summary.contains("Xiaomi"))
        assertTrue(summary.contains("60dbb6a6f9e1"))
        assertTrue(summary.contains("3/4"))
        assertTrue(summary.contains("品牌：Xiaomi"))
    }

    @Test
    fun `env summary and bug report url include deduplicated device model when provided`() {
        val sameBrandSummary = ProjectLinks.buildDesensitizedEnvSummary(
            versionName = "0.6.2",
            versionCode = 62,
            androidRelease = "15",
            apiLevel = 35,
            manufacturer = "Xiaomi",
            selectorDigest = "60dbb6a6f9e1",
            survivalPassed = 4,
            survivalTotal = 5,
            deviceBrand = "xiaomi",
            deviceModel = "23113RKC6C",
        )
        assertTrue(sameBrandSummary.contains("机型：Xiaomi 23113RKC6C"))
        assertFalse(sameBrandSummary.contains("品牌："))

        val subBrandSummary = ProjectLinks.buildDesensitizedEnvSummary(
            versionName = "0.6.2",
            versionCode = 62,
            androidRelease = "15",
            apiLevel = 35,
            manufacturer = "Xiaomi",
            selectorDigest = "60dbb6a6f9e1",
            survivalPassed = 4,
            survivalTotal = 5,
            deviceBrand = "Redmi",
            deviceModel = "23113RKC6C",
        )
        assertTrue(subBrandSummary.contains("机型：Xiaomi / Redmi 23113RKC6C"))

        val prefixDedupSummary = ProjectLinks.buildDesensitizedEnvSummary(
            versionName = "0.6.2",
            versionCode = 62,
            androidRelease = "15",
            apiLevel = 35,
            manufacturer = "Xiaomi",
            selectorDigest = "60dbb6a6f9e1",
            survivalPassed = 4,
            survivalTotal = 5,
            deviceBrand = "Redmi",
            deviceModel = "Redmi K70 Pro",
        )
        assertTrue(prefixDedupSummary.contains("机型：Xiaomi / Redmi K70 Pro"))
        assertFalse(prefixDedupSummary.contains("Redmi Redmi"))

        val url = ProjectLinks.buildAndroidBugReportUrl(
            versionName = "0.6.2",
            versionCode = 62,
            androidRelease = "15",
            apiLevel = 35,
            selectorDigest = "60dbb6a6f9e1",
            deviceBrand = "Redmi",
            deviceModel = "23113RKC6C",
        )
        assertTrue(url.contains("Redmi+23113RKC6C"))
        assertTrue(url.contains("selector+60dbb6a6f9e1"))

        val dedupUrl = ProjectLinks.buildAndroidBugReportUrl(
            versionName = "0.6.2",
            versionCode = 62,
            androidRelease = "15",
            apiLevel = 35,
            selectorDigest = "60dbb6a6f9e1",
            deviceBrand = "Redmi",
            deviceModel = "Redmi K70 Pro",
        )
        assertTrue(dedupUrl.contains("Redmi+K70+Pro"))
        assertFalse(dedupUrl.contains("Redmi+Redmi"))
    }

    @Test
    fun `device model helpers reject injected tokens urls cookies and nicknames`() {
        val maliciousInputs = listOf(
            "https://api.day.app/MySecretBarkKey9988",
            "Redmi token=abc12345",
            "23113RKC6C cookie=sessionid_xyz",
            "MS4wLjABAAAA_LeakSecUid_001",
            "864201051234567",
            "密友张三丰隐私昵称",
            "SuperSecretOpaqueTokenValue987654",
        )
        for (bad in maliciousInputs) {
            val summary = ProjectLinks.buildDesensitizedEnvSummary(
                versionName = "0.6.2",
                versionCode = 62,
                androidRelease = "15",
                apiLevel = 35,
                manufacturer = "Xiaomi",
                selectorDigest = "60dbb6a6f9e1",
                survivalPassed = 4,
                survivalTotal = 5,
                deviceBrand = bad,
                deviceModel = bad,
            )
            assertFalse("Summary leaked malicious value [$bad]: $summary", summary.contains(bad))
            assertTrue("Expected fallback to brand line when model rejected", summary.contains("品牌：Xiaomi"))

            val url = ProjectLinks.buildAndroidBugReportUrl(
                versionName = "0.6.2",
                versionCode = 62,
                androidRelease = "15",
                apiLevel = 35,
                selectorDigest = "60dbb6a6f9e1",
                deviceBrand = bad,
                deviceModel = bad,
            )
            assertFalse("URL leaked malicious value [$bad]: $url", url.contains("MySecretBarkKey"))
            assertFalse("URL leaked malicious value [$bad]: $url", url.contains("sessionid"))
            assertFalse("URL leaked malicious value [$bad]: $url", url.contains("864201051234567"))
            assertFalse("URL leaked malicious value [$bad]: $url", url.contains("MS4wLjAB"))
        }
    }
}
