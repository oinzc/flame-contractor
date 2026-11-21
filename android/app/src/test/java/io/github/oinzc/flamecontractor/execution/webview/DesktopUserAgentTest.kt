package io.github.oinzc.flamecontractor.execution.webview

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopUserAgentTest {

    @Test
    fun extractsChromiumVersionIntoWindowsDesktopUa() {
        val defaultUa =
            "Mozilla/5.0 (Linux; Android 13; M2105K81C Build/TKQ1.221114.001; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/157.0.8103.50 Safari/537.36"

        val actual = DesktopWebViewFactory.buildDesktopUserAgent(defaultUa)

        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/157.0.8103.50 Safari/537.36",
            actual,
        )
    }

    @Test
    fun fallsBackToSafeVersionWhenTokenMissing() {
        val actual = DesktopWebViewFactory.buildDesktopUserAgent("CustomAndroidWebView/1.0")

        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36",
            actual,
        )
    }
}
