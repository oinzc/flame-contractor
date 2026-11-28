package io.github.oinzc.flamecontractor.ui.update

import io.github.oinzc.flamecontractor.core.support.ProjectLinks
import io.github.oinzc.flamecontractor.core.update.AppUpdateRelease
import io.github.oinzc.flamecontractor.core.update.AppUpdateReleaseHistoryItem
import io.github.oinzc.flamecontractor.core.update.AppUpdateStatus
import io.github.oinzc.flamecontractor.core.update.AppUpdateUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateUiFormatterTest {

    private fun sampleRelease(
        versionName: String = "0.6.1",
        versionCode: Long = 601L,
        minVersionCode: Long = 0L,
        releasePageUrl: String? = "https://github.com/oinzc/flame-contractor/releases/tag/v0.6.1",
    ): AppUpdateRelease = AppUpdateRelease(
        schemaVersion = 1,
        platform = "android",
        versionName = versionName,
        versionCode = versionCode,
        minVersionCode = minVersionCode,
        publishedAt = "2026-11-26T01:49:55Z",
        fileSize = 19_293_798L,
        sha256 = "0".repeat(64),
        downloadUrl = "https://updates.example.com/android/FlameContractor-0.6.1.apk",
        releasePageUrl = releasePageUrl,
        changelog = "修复部分机型定时调度问题",
    )

    private fun sampleUiState(
        isConfigured: Boolean = true,
        currentVersionName: String = "0.6.0-dev",
        currentVersionCode: Long = 600L,
        status: AppUpdateStatus = AppUpdateStatus.Idle,
    ): AppUpdateUiState = AppUpdateUiState(
        isConfigured = isConfigured,
        autoCheckEnabled = true,
        currentVersionName = currentVersionName,
        currentVersionCode = currentVersionCode,
        isDebugVariant = false,
        status = status,
    )

    @Test
    fun `formatApkFileSize handles negative zero bytes KB MB and GB boundaries`() {
        assertEquals("0 B", formatApkFileSize(-1L))
        assertEquals("0 B", formatApkFileSize(Long.MIN_VALUE))
        assertEquals("0 B", formatApkFileSize(0L))
        assertEquals("1 B", formatApkFileSize(1L))
        assertEquals("1023 B", formatApkFileSize(1023L))
        assertEquals("1.0 KB", formatApkFileSize(1024L))
        assertEquals("1.5 KB", formatApkFileSize(1536L))
        assertEquals("512.0 KB", formatApkFileSize(512L * 1024L))
        assertEquals("1.0 MB", formatApkFileSize(1024L * 1024L))
        assertEquals("18.4 MB", formatApkFileSize(19_293_798L))
        assertEquals("1.00 GB", formatApkFileSize(1024L * 1024L * 1024L))
        assertEquals("1.50 GB", formatApkFileSize(1536L * 1024L * 1024L))
    }

    @Test
    fun `formatUpdatePublishedDate parses ISO timestamps and rejects invalid input`() {
        assertEquals("2026-11-26", formatUpdatePublishedDate("2026-11-26T01:49:55Z"))
        assertEquals("2026-11-26", formatUpdatePublishedDate("2026-11-26T09:49:55+08:00"))
        assertEquals("2026-01-02", formatUpdatePublishedDate("2026-01-02T23:59:59-05:00"))
        assertEquals("2026-03-15", formatUpdatePublishedDate("2026-03-15"))
        assertEquals("2026-03-15", formatUpdatePublishedDate("2026-03-15T08:15:00"))
        assertEquals("2026-07-08", formatUpdatePublishedDate("  2026-07-08T00:00:00Z  "))
        assertEquals("", formatUpdatePublishedDate(""))
        assertEquals("", formatUpdatePublishedDate("   "))
        assertEquals("", formatUpdatePublishedDate("not-a-date"))
        assertEquals("", formatUpdatePublishedDate("2026/05/01"))
        assertEquals("", formatUpdatePublishedDate("2026-13-45"))
    }

    @Test
    fun `formatLastCheckedLabel handles null zero skew and relative units`() {
        val now = 1_760_000_000_000L
        assertEquals("尚未检查", formatLastCheckedLabel(null, now))
        assertEquals("尚未检查", formatLastCheckedLabel(0L, now))
        assertEquals("尚未检查", formatLastCheckedLabel(-5000L, now))
        // 系统时钟回拨（上次检查时间晚于当前时间）温和显示刚刚检查
        assertEquals("刚刚检查", formatLastCheckedLabel(now + 10_000L, now))
        assertEquals("刚刚检查", formatLastCheckedLabel(now, now))
        assertEquals("刚刚检查", formatLastCheckedLabel(now - 59_999L, now))
        assertEquals("1 分钟前检查", formatLastCheckedLabel(now - 60_000L, now))
        assertEquals("59 分钟前检查", formatLastCheckedLabel(now - 59L * 60_000L, now))
        assertEquals("1 小时前检查", formatLastCheckedLabel(now - 60L * 60_000L, now))
        assertEquals("23 小时前检查", formatLastCheckedLabel(now - 23L * 3_600_000L, now))
        assertEquals("1 天前检查", formatLastCheckedLabel(now - 24L * 3_600_000L, now))
        assertEquals("7 天前检查", formatLastCheckedLabel(now - 7L * 86_400_000L, now))
    }

    @Test
    fun `formatVersionTransitionTag avoids duplicate v prefix and handles blank values`() {
        assertEquals("v0.6.0-dev -> v0.6.1", formatVersionTransitionTag("0.6.0-dev", "0.6.1"))
        assertEquals("v0.6.0 -> v0.6.1", formatVersionTransitionTag("v0.6.0", "V0.6.1"))
        assertEquals("v? -> v0.6.1", formatVersionTransitionTag("  ", "0.6.1"))
    }

    @Test
    fun `formatUpdateDownloadProgressSummary handles known unknown total and clamps percent`() {
        assertEquals(
            "已下载 5.0 MB / 10.0 MB · 50%",
            formatUpdateDownloadProgressSummary(
                downloadedBytes = 5L * 1024L * 1024L,
                totalBytes = 10L * 1024L * 1024L,
                progressPercent = 50,
            ),
        )
        assertEquals(
            "已下载 10.0 MB / 10.0 MB · 100%",
            formatUpdateDownloadProgressSummary(
                downloadedBytes = 10L * 1024L * 1024L,
                totalBytes = 10L * 1024L * 1024L,
                progressPercent = 140,
            ),
        )
        assertEquals(
            "已下载 2.0 MB / 10.0 MB",
            formatUpdateDownloadProgressSummary(
                downloadedBytes = 2L * 1024L * 1024L,
                totalBytes = 10L * 1024L * 1024L,
                progressPercent = null,
            ),
        )
        assertEquals(
            "已下载 512.0 KB",
            formatUpdateDownloadProgressSummary(
                downloadedBytes = 512L * 1024L,
                totalBytes = 0L,
                progressPercent = null,
            ),
        )
    }

    @Test
    fun `formatUpdateStatusSummary covers all status variants and unconfigured state`() {
        val release = sampleRelease(versionName = "0.6.2")
        assertEquals(
            "当前构建未配置自动更新源",
            formatUpdateStatusSummary(sampleUiState(isConfigured = false, status = AppUpdateStatus.Idle)),
        )
        assertEquals(
            "正在检查更新…",
            formatUpdateStatusSummary(sampleUiState(status = AppUpdateStatus.Checking)),
        )
        assertEquals(
            "已是最新版本",
            formatUpdateStatusSummary(sampleUiState(status = AppUpdateStatus.UpToDate)),
        )
        assertEquals(
            "发现新版本 v0.6.2",
            formatUpdateStatusSummary(
                sampleUiState(status = AppUpdateStatus.UpdateAvailable(release, isMandatory = false)),
            ),
        )
        assertEquals(
            "正在下载 v0.6.2（42%）",
            formatUpdateStatusSummary(
                sampleUiState(
                    status = AppUpdateStatus.Downloading(
                        release = release,
                        downloadedBytes = 420L,
                        totalBytes = 1000L,
                        progressPercent = 42,
                    ),
                ),
            ),
        )
        assertEquals(
            "正在下载 v0.6.2…",
            formatUpdateStatusSummary(
                sampleUiState(
                    status = AppUpdateStatus.Downloading(
                        release = release,
                        downloadedBytes = 420L,
                        totalBytes = 0L,
                        progressPercent = null,
                    ),
                ),
            ),
        )
        assertEquals(
            "正在校验 v0.6.2 安装包完整性…",
            formatUpdateStatusSummary(sampleUiState(status = AppUpdateStatus.Verifying(release))),
        )
        assertEquals(
            "新版本 v0.6.2 已下载就绪，可立即安装",
            formatUpdateStatusSummary(
                sampleUiState(status = AppUpdateStatus.ReadyToInstall(release, "/tmp/test.apk")),
            ),
        )
        assertEquals(
            "网络连接失败，请稍后重试",
            formatUpdateStatusSummary(
                sampleUiState(status = AppUpdateStatus.Error(message = "网络连接失败，请稍后重试")),
            ),
        )
    }

    @Test
    fun `resolveReleaseFallbackUrl prefers releasePageUrl and falls back to ProjectLinks`() {
        val withPage = sampleRelease(releasePageUrl = "https://github.com/oinzc/flame-contractor/releases/tag/v0.6.1")
        assertEquals(
            "https://github.com/oinzc/flame-contractor/releases/tag/v0.6.1",
            resolveReleaseFallbackUrl(withPage),
        )
        assertEquals(ProjectLinks.RELEASES_URL, resolveReleaseFallbackUrl(sampleRelease(releasePageUrl = null)))
        assertEquals(ProjectLinks.RELEASES_URL, resolveReleaseFallbackUrl(sampleRelease(releasePageUrl = "   ")))
        assertEquals(ProjectLinks.RELEASES_URL, resolveReleaseFallbackUrl(null))
    }

    @Test
    fun `canDismissUpdateSheetByScrim blocks mandatory updates and active download or verification`() {
        val optionalRelease = sampleRelease(minVersionCode = 0L)
        val mandatoryRelease = sampleRelease(minVersionCode = 601L)

        assertTrue(
            canDismissUpdateSheetByScrim(
                sampleUiState(status = AppUpdateStatus.UpdateAvailable(optionalRelease, isMandatory = false)),
            ),
        )
        assertFalse(
            canDismissUpdateSheetByScrim(
                sampleUiState(status = AppUpdateStatus.UpdateAvailable(mandatoryRelease, isMandatory = true)),
            ),
        )
        assertFalse(
            canDismissUpdateSheetByScrim(
                sampleUiState(
                    status = AppUpdateStatus.Downloading(
                        release = optionalRelease,
                        downloadedBytes = 100L,
                        totalBytes = 1000L,
                        progressPercent = 10,
                    ),
                ),
            ),
        )
        assertFalse(
            canDismissUpdateSheetByScrim(
                sampleUiState(status = AppUpdateStatus.Verifying(optionalRelease)),
            ),
        )
        assertTrue(
            canDismissUpdateSheetByScrim(
                sampleUiState(status = AppUpdateStatus.ReadyToInstall(optionalRelease, "/tmp/a.apk")),
            ),
        )
        assertFalse(
            canDismissUpdateSheetByScrim(
                sampleUiState(status = AppUpdateStatus.ReadyToInstall(mandatoryRelease, "/tmp/a.apk")),
            ),
        )
        assertTrue(
            canDismissUpdateSheetByScrim(
                sampleUiState(status = AppUpdateStatus.Error("网络错误", release = optionalRelease)),
            ),
        )
        assertFalse(
            canDismissUpdateSheetByScrim(
                sampleUiState(status = AppUpdateStatus.Error("网络错误", release = mandatoryRelease)),
            ),
        )
    }

    @Test
    fun `parseUpdateChangelog formats markdown sections, clean bullets, and filters CI noise`() {
        val rawMarkdown = """
            ### 新增
            - **默认极简模式**：打开应用后默认进入极简首页，`0px` 无感切换。
              - 保留直达反馈按钮。

            ### 修复与改进
            - **修复四栏闪烁**：底部标签切换更顺滑。
        """.trimIndent()

        val parsedMd = parseUpdateChangelog(rawMarkdown)
        assertEquals(2, parsedMd.size)
        assertEquals("新增功能", parsedMd[0].title)
        assertEquals(UpdateChangelogTone.FEATURE, parsedMd[0].tone)
        assertEquals(2, parsedMd[0].items.size)
        assertEquals("默认极简模式", parsedMd[0].items[0].lead)
        assertEquals("打开应用后默认进入极简首页，0px 无感切换。", parsedMd[0].items[0].body)
        assertFalse(parsedMd[0].items[0].isSubItem)
        assertTrue(parsedMd[0].items[1].isSubItem)
        assertEquals("保留直达反馈按钮。", parsedMd[0].items[1].body)

        assertEquals("修复与优化", parsedMd[1].title)
        assertEquals(UpdateChangelogTone.FIX, parsedMd[1].tone)
        assertEquals("修复四栏闪烁", parsedMd[1].items[0].lead)

        val cleanManifestNotes = """
            【新增功能】
            • 闹钟滚轮选时：不再需要手输冒号。
              · 兼容平板居中弹窗

            【修复与优化】
            • 修复标准模式闪烁
        """.trimIndent()
        val parsedClean = parseUpdateChangelog(cleanManifestNotes)
        assertEquals(2, parsedClean.size)
        assertEquals("闹钟滚轮选时", parsedClean[0].items[0].lead)
        assertTrue(parsedClean[0].items[1].isSubItem)
        assertEquals("修复标准模式闪烁", parsedClean[1].items[0].body)

        val dependabotOnly = """
            ## What's Changed
            * chore(ci): bump actions/setup-java from 4 to 6 by @dependabot[bot] in https://github.com/o/r/pull/28
            * chore(ci): bump gradle/actions from 4 to 6 by @dependabot[bot] in https://github.com/o/r/pull/26

            ## New Contributors
            * @dependabot[bot] made their first contribution in https://github.com/o/r/pull/28

            **Full Changelog**: https://github.com/o/r/compare/v0.7.2...v0.8.0
        """.trimIndent()
        val parsedNoise = parseUpdateChangelog(dependabotOnly)
        assertEquals(1, parsedNoise.size)
        assertEquals("本次更新包含稳定性改进与体验优化。", parsedNoise[0].items.single().body)

        val parsedBlank = parseUpdateChangelog("   \n  ")
        assertEquals("本次更新包含稳定性改进与体验优化。", parsedBlank.single().items.single().body)
    }

    @Test
    fun `partitionReleaseHistory splits skipped and earlier versions with deduplication and blank filtering`() {
        val releases = listOf(
            AppUpdateReleaseHistoryItem("0.9.0", 900L, "2026-11-28", "• 最新版 0.9.0"),
            AppUpdateReleaseHistoryItem("0.7.2", 702L, "2026-11-27", "• 跳过今日已亮火花"),
            AppUpdateReleaseHistoryItem("0.8.0", 800L, "2026-11-28", "• 平板自适应重构"),
            AppUpdateReleaseHistoryItem("0.8.0", 800L, "2026-11-28", "• 重复版本应忽略"),
            AppUpdateReleaseHistoryItem("0.7.1", 701L, "2026-11-26", "   "),
            AppUpdateReleaseHistoryItem("0.7.0", 700L, "2026-11-26", "• 极简模式首版"),
            AppUpdateReleaseHistoryItem("0.6.2", 602L, "2026-11-26", "• 分离更新与反馈页面"),
        )

        val jumped = partitionReleaseHistory(
            currentVersionCode = 700L,
            targetVersionCode = 900L,
            recentReleases = releases,
        )
        assertEquals(listOf(800L, 702L), jumped.skippedVersions.map { it.versionCode })
        assertEquals(listOf(700L, 602L), jumped.earlierVersions.map { it.versionCode })
        assertEquals(listOf(800L, 702L, 700L, 602L), jumped.allHistory.map { it.versionCode })

        val sequential = partitionReleaseHistory(
            currentVersionCode = 800L,
            targetVersionCode = 900L,
            recentReleases = releases,
        )
        assertTrue(sequential.skippedVersions.isEmpty())
        assertEquals(listOf(800L, 702L, 700L, 602L), sequential.earlierVersions.map { it.versionCode })
    }
}
