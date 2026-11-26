package io.github.oinzc.flamecontractor.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateModelsTest {

    private val validSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun sampleRelease(
        versionName: String = "0.6.1",
        versionCode: Long = 601L,
        minVersionCode: Long = 0L,
        downloadUrl: String = "https://dl.example.com/android/releases/FlameContractor-0.6.1.apk",
    ): AppUpdateRelease = AppUpdateRelease(
        schemaVersion = 1,
        platform = "android",
        versionName = versionName,
        versionCode = versionCode,
        minVersionCode = minVersionCode,
        publishedAt = "2026-11-26T00:00:00Z",
        fileSize = 18_234_567L,
        sha256 = validSha256,
        downloadUrl = downloadUrl,
        releasePageUrl = "https://github.com/oinzc/flame-contractor/releases/tag/v0.6.1",
        changelog = "修复已知问题",
    )

    @Test
    fun `AppVersionComparator handles leading v variant suffixes prereleases and build metadata`() {
        assertEquals(0, AppVersionComparator.compareSemver("v0.6.1", "0.6.1"))
        assertEquals(0, AppVersionComparator.compareSemver("V0.6.1", "0.6.1"))
        assertEquals(0, AppVersionComparator.compareSemver("0.6.1+20261126", "0.6.1"))
        assertEquals(0, AppVersionComparator.compareSemver("0.6.1", "0.6.1-debug"))
        assertEquals(0, AppVersionComparator.compareSemver("0.6.1", "0.6.1-demo"))

        // 正式版高于同版本的预发布版，且自动剥离 -debug 后缀
        assertTrue(AppVersionComparator.compareSemver("0.6.1", "0.6.1-dev") > 0)
        assertTrue(AppVersionComparator.compareSemver("0.6.1", "0.6.1-dev-debug") > 0)
        assertTrue(AppVersionComparator.compareSemver("0.6.1", "0.6.0-dev-debug") > 0)
        assertTrue(AppVersionComparator.compareSemver("0.6.1", "0.6.1-rc.1") > 0)
        assertTrue(AppVersionComparator.compareSemver("0.6.1", "0.6.1-rc.1-debug") > 0)

        // 本地更高版本开发包不可被旧远端误判降级
        assertTrue(AppVersionComparator.compareSemver("0.6.1", "0.6.2-dev-debug") < 0)

        // 预发布标签间按 SemVer 2.0 规则排序（纯数字按数值，非数字按字典序）
        assertTrue(AppVersionComparator.compareSemver("0.6.1-rc.2", "0.6.1-rc.1") > 0)
        assertTrue(AppVersionComparator.compareSemver("0.6.1-rc.10", "0.6.1-rc.2") > 0)
        assertTrue(AppVersionComparator.compareSemver("0.6.1-beta.1", "0.6.1-alpha.9") > 0)
        assertTrue(AppVersionComparator.compareSemver("0.6.1-rc.1", "0.6.1-rc") > 0)
        assertTrue(AppVersionComparator.compareSemver("0.6.1-1", "0.6.1-alpha") < 0)

        // 主/次/补丁常规比较
        assertTrue(AppVersionComparator.compareSemver("1.0.0", "0.99.99") > 0)
        assertTrue(AppVersionComparator.compareSemver("0.7.0", "0.6.99") > 0)
        assertEquals(0, AppVersionComparator.compareSemver("0.6", "0.6.0"))

        // 非法版本号返回 null 或抛异常
        assertNull(AppVersionComparator.parseOrNull(""))
        assertNull(AppVersionComparator.parseOrNull("not-a-version"))
        assertNull(AppVersionComparator.parseOrNull("0.6.1-"))
        assertNull(AppVersionComparator.parseOrNull("0.6.1-a..b"))
        assertNull(AppVersionComparator.compareSemverOrNull("0.6.1", "invalid"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `compareSemver throws on invalid version string`() {
        AppVersionComparator.compareSemver("invalid", "0.6.1")
    }

    @Test
    fun `evaluateUpdate enforces both versionCode and semver guard plus minVersionCode mandatory rule`() {
        val remote061 = sampleRelease(versionName = "0.6.1", versionCode = 601L, minVersionCode = 0L)

        // 0.6.0 (3) -> 0.6.1 (601): 有更新
        val fromRelease060 = AppVersionComparator.evaluateUpdate(remote061, "0.6.0", 3L)
        assertTrue(fromRelease060.isUpdateAvailable)
        assertFalse(fromRelease060.isMandatory)

        // 本地 0.6.0-dev-debug (1) -> 0.6.1 (601): 有更新
        val fromDevDebug060 = AppVersionComparator.evaluateUpdate(remote061, "0.6.0-dev-debug", 1L)
        assertTrue(fromDevDebug060.isUpdateAvailable)
        assertFalse(fromDevDebug060.isMandatory)

        // 本地 0.6.1-dev-debug (1) -> 0.6.1 (601): 有更新
        val fromDevDebug061 = AppVersionComparator.evaluateUpdate(remote061, "0.6.1-dev-debug", 1L)
        assertTrue(fromDevDebug061.isUpdateAvailable)

        // 本地更高版本 0.6.2-dev-debug (1) 不被旧远端 0.6.1 (601) 误报降级
        val fromHigherDev062 = AppVersionComparator.evaluateUpdate(remote061, "0.6.2-dev-debug", 1L)
        assertFalse(fromHigherDev062.isUpdateAvailable)
        assertFalse(fromHigherDev062.isMandatory)

        // 同版本不提示更新
        val sameVersion = AppVersionComparator.evaluateUpdate(remote061, "0.6.1", 601L)
        assertFalse(sameVersion.isUpdateAvailable)

        // versionName 更高但 versionCode 不大于当前不提示更新
        val lowerCode = AppVersionComparator.evaluateUpdate(
            sampleRelease(versionName = "0.6.2", versionCode = 601L),
            "0.6.1",
            601L,
        )
        assertFalse(lowerCode.isUpdateAvailable)

        // minVersionCode 强制更新判定
        val mandatoryRemote = sampleRelease(versionName = "0.6.2", versionCode = 602L, minVersionCode = 601L)
        val belowMin = AppVersionComparator.evaluateUpdate(mandatoryRemote, "0.6.0", 3L)
        assertTrue(belowMin.isUpdateAvailable)
        assertTrue(belowMin.isMandatory)

        val equalMin = AppVersionComparator.evaluateUpdate(mandatoryRemote, "0.6.1", 601L)
        assertTrue(equalMin.isUpdateAvailable)
        assertFalse(equalMin.isMandatory)

        // 已是最新版本时即使 minVersionCode 设置异常也不应误判强制更新
        val alreadyLatest = AppVersionComparator.evaluateUpdate(
            sampleRelease(versionName = "0.6.2", versionCode = 602L, minVersionCode = 700L),
            "0.6.2",
            602L,
        )
        assertFalse(alreadyLatest.isUpdateAvailable)
        assertFalse(alreadyLatest.isMandatory)
    }

    @Test
    fun `manifest parser parses valid latest json and normalizes sha256`() {
        val raw = """
            {
              "schemaVersion": 1,
              "platform": "android",
              "versionName": "0.6.1",
              "versionCode": 601,
              "minVersionCode": 500,
              "publishedAt": "2026-11-26T00:00:00Z",
              "fileSize": 18234567,
              "sha256": "${validSha256.uppercase()}",
              "downloadUrl": "https://dl.20190415.cloud/android/releases/FlameContractor-0.6.1-android-universal-release.apk",
              "releasePageUrl": "https://github.com/oinzc/flame-contractor/releases/tag/v0.6.1",
              "changelog": "- 新增应用内检查更新\n- 优化诊断日志"
            }
        """.trimIndent()

        val release = AppUpdateManifestParser.parse(raw)
        assertEquals(1, release.schemaVersion)
        assertEquals("android", release.platform)
        assertEquals("0.6.1", release.versionName)
        assertEquals(601L, release.versionCode)
        assertEquals(500L, release.minVersionCode)
        assertEquals("2026-11-26T00:00:00Z", release.publishedAt)
        assertEquals(18_234_567L, release.fileSize)
        assertEquals(validSha256, release.sha256)
        assertEquals(
            "https://dl.20190415.cloud/android/releases/FlameContractor-0.6.1-android-universal-release.apk",
            release.downloadUrl,
        )
        assertEquals("https://github.com/oinzc/flame-contractor/releases/tag/v0.6.1", release.releasePageUrl)
        assertTrue(release.changelog.contains("新增应用内检查更新"))
    }

    @Test
    fun `manifest parser rejects non-https downloadUrl invalid sha256 wrong platform and bad schema`() {
        fun jsonWith(
            schema: String = "1",
            platform: String = "\"android\"",
            versionName: String = "\"0.6.1\"",
            versionCode: String = "601",
            sha256: String = "\"$validSha256\"",
            downloadUrl: String = "\"https://dl.example.com/app.apk\"",
            releasePageUrl: String = "\"https://github.com/oinzc/flame-contractor/releases\"",
        ): String = """
            {
              "schemaVersion": $schema,
              "platform": $platform,
              "versionName": $versionName,
              "versionCode": $versionCode,
              "sha256": $sha256,
              "downloadUrl": $downloadUrl,
              "releasePageUrl": $releasePageUrl
            }
        """.trimIndent()

        val badCases = listOf(
            "" to "empty_manifest",
            "{not json" to "invalid_json",
            jsonWith(schema = "2") to "unsupported_schema_version",
            jsonWith(schema = "1.5") to "invalid_schema_version",
            jsonWith(platform = "\"ios\"") to "invalid_platform",
            jsonWith(versionName = "\"bad_ver\"") to "invalid_version_name",
            jsonWith(versionCode = "0") to "invalid_version_code",
            jsonWith(sha256 = "\"deadbeef\"") to "invalid_sha256",
            jsonWith(sha256 = "\"${"g".repeat(64)}\"") to "invalid_sha256",
            jsonWith(downloadUrl = "\"http://dl.example.com/app.apk\"") to "invalid_download_url",
            jsonWith(downloadUrl = "\"file:///sdcard/app.apk\"") to "invalid_download_url",
            jsonWith(downloadUrl = "\"https://dl.example.com/app.apk\\n\"") to "invalid_download_url",
        )

        for ((payload, expectedReason) in badCases) {
            val error = runCatching { AppUpdateManifestParser.parse(payload, allowCleartext = false) }
                .exceptionOrNull() as? AppUpdateManifestException
            assertNotNull("Expected AppUpdateManifestException for $expectedReason", error)
            assertEquals(expectedReason, error!!.reasonCode)
        }

        // 测试模式下允许 http:// downloadUrl；生产模式对非 https releasePageUrl 做安全置空
        val httpDownloadAllowed = AppUpdateManifestParser.parse(
            jsonWith(downloadUrl = "\"http://127.0.0.1:8080/app.apk\""),
            allowCleartext = true,
        )
        assertEquals("http://127.0.0.1:8080/app.apk", httpDownloadAllowed.downloadUrl)

        val sanitizedReleasePage = AppUpdateManifestParser.parse(
            jsonWith(releasePageUrl = "\"http://insecure.example.com/release\""),
            allowCleartext = false,
        )
        assertNull(sanitizedReleasePage.releasePageUrl)
    }
}
