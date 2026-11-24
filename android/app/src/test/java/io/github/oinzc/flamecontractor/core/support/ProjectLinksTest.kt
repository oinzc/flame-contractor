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
    }
}
