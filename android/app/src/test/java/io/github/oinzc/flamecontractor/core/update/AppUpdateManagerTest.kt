package io.github.oinzc.flamecontractor.core.update

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppUpdateManagerTest {

    private lateinit var tempCacheDir: File
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        tempCacheDir = Files.createTempDirectory("flame_update_test_").toFile()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        tempCacheDir.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun manifestJson(
        versionName: String = "0.6.1",
        versionCode: Long = 601L,
        minVersionCode: Long = 0L,
        fileSize: Long = 1024L,
        sha256: String = "a".repeat(64),
        downloadUrl: String = server.url("/android/releases/FlameContractor-$versionName.apk").toString(),
        releasePageUrl: String = "https://github.com/oinzc/flame-contractor/releases/tag/v$versionName",
        changelog: String = "- 新增自动检查更新",
        recentReleasesJson: String? = null,
    ): String {
        val recentField = if (recentReleasesJson != null) {
            ",\n          \"recentReleases\": $recentReleasesJson"
        } else {
            ""
        }
        return """
        {
          "schemaVersion": 1,
          "platform": "android",
          "versionName": "$versionName",
          "versionCode": $versionCode,
          "minVersionCode": $minVersionCode,
          "publishedAt": "2026-11-26T00:00:00Z",
          "fileSize": $fileSize,
          "sha256": "$sha256",
          "downloadUrl": "$downloadUrl",
          "releasePageUrl": "$releasePageUrl",
          "changelog": "$changelog"$recentField
        }
        """.trimIndent()
    }

    private fun createManager(
        manifestUrl: () -> String = { server.url("/android/latest.json").toString() },
        prefsStore: AppUpdatePreferencesStore = AppUpdatePreferencesStore(),
        currentVersionName: String = "0.6.0",
        currentVersionCode: Long = 3L,
        isDebugVariant: Boolean = false,
        clockMs: () -> Long = { 1_700_000_000_000L },
        allowCleartextForTests: Boolean = true,
        maxApkBytes: Long = AppUpdateManager.MAX_APK_BYTES,
        canRequestPackageInstalls: (Context) -> Boolean = { true },
        installerLauncher: (Context, File) -> Unit = { _, _ -> },
        logCollector: MutableList<String>? = null,
    ): AppUpdateManager = AppUpdateManager(
        preferencesStore = prefsStore,
        manifestUrlProvider = manifestUrl,
        currentVersionName = currentVersionName,
        currentVersionCode = currentVersionCode,
        isDebugVariant = isDebugVariant,
        cacheDir = tempCacheDir,
        clockMillis = clockMs,
        allowCleartextForTests = allowCleartextForTests,
        maxApkBytes = maxApkBytes,
        canRequestPackageInstalls = canRequestPackageInstalls,
        installerLauncher = installerLauncher,
        diagnosticLog = { category, event, level, detail ->
            logCollector?.add("[$level][$category] $event $detail")
        },
    )

    @Test
    fun `unconfigured manifest url silently skips auto check and returns friendly message on manual check`() =
        runBlocking {
            val manager = createManager(manifestUrl = { "" })
            assertFalse(manager.state.value.isConfigured)

            val autoResult = manager.checkForUpdates(manual = false)
            assertTrue(autoResult is AppUpdateCheckResult.NotConfigured)
            assertEquals(AppUpdateStatus.Idle, manager.state.value.status)
            assertFalse(manager.state.value.showDialog)
            assertEquals(0, server.requestCount)

            val manualResult = manager.checkForUpdates(manual = true)
            assertTrue(manualResult is AppUpdateCheckResult.NotConfigured)
            assertEquals(
                "当前构建未配置自动更新源，可前往 GitHub Releases 获取最新版",
                (manualResult as AppUpdateCheckResult.NotConfigured).message,
            )
            val errStatus = manager.state.value.status as? AppUpdateStatus.Error
            assertNotNull(errStatus)
            assertFalse(errStatus!!.retryable)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `auto check enforces 6h throttle and toggle while manual check bypasses both`() = runBlocking {
        val now = AtomicLong(1_700_000_000_000L)
        val manager = createManager(clockMs = { now.get() })

        server.enqueue(MockResponse().setResponseCode(200).setBody(manifestJson(versionName = "0.6.0", versionCode = 3L)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(manifestJson(versionName = "0.6.1", versionCode = 601L)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(manifestJson(versionName = "0.6.1", versionCode = 601L)))

        // 首次自动检查 -> 最新
        assertEquals(AppUpdateCheckResult.UpToDate, manager.checkForUpdates(manual = false))
        assertEquals(1, server.requestCount)
        assertEquals(AppUpdateStatus.UpToDate, manager.state.value.status)

        // 1 小时内再次自动检查 -> 节流跳过，不发网络请求
        now.addAndGet(60L * 60L * 1000L)
        assertEquals(AppUpdateCheckResult.Skipped, manager.checkForUpdates(manual = false))
        assertEquals(1, server.requestCount)

        // 手动检查绕过 6 小时节流
        val manualRes = manager.checkForUpdates(manual = true)
        assertTrue(manualRes is AppUpdateCheckResult.UpdateAvailable)
        assertEquals(2, server.requestCount)
        assertTrue(manager.state.value.showDialog)

        // 关闭自动检查后，即使超过 6 小时也不自动检查
        manager.setAutoCheckEnabled(false)
        assertFalse(manager.state.value.autoCheckEnabled)
        now.addAndGet(AppUpdatePreferencesStore.DEFAULT_AUTO_CHECK_INTERVAL_MS + 1_000L)
        assertEquals(AppUpdateCheckResult.Skipped, manager.checkForUpdates(manual = false))
        assertEquals(2, server.requestCount)

        // 手动检查依然可工作
        assertTrue(manager.checkForUpdates(manual = true) is AppUpdateCheckResult.UpdateAvailable)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `auto check swallows network and parse failures silently while manual check surfaces error`() =
        runBlocking {
            val logs = mutableListOf<String>()
            val now = AtomicLong(1_700_000_000_000L)
            val manager = createManager(clockMs = { now.get() }, logCollector = logs)

            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{bad json"))
            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))

            // 自动检查 503：静默恢复 Idle，不弹错，记录脱敏日志（不含完整 URL）
            val autoFail = manager.checkForUpdates(manual = false)
            assertTrue(autoFail is AppUpdateCheckResult.Error)
            assertEquals(AppUpdateStatus.Idle, manager.state.value.status)
            assertFalse(manager.state.value.showDialog)
            assertTrue(logs.any { it.contains("check_http_error") && it.contains("code=503") })
            assertTrue(logs.none { it.contains("http://") || it.contains("https://") })

            // 手动检查遇到畸形 JSON：明确切换 Error 状态
            val manualBadJson = manager.checkForUpdates(manual = true)
            assertTrue(manualBadJson is AppUpdateCheckResult.Error)
            assertTrue(manager.state.value.status is AppUpdateStatus.Error)
            assertFalse(manager.state.value.showDialog)

            // 手动检查 404
            val manual404 = manager.checkForUpdates(manual = true)
            assertTrue(manual404 is AppUpdateCheckResult.Error)
            assertTrue((manual404 as AppUpdateCheckResult.Error).message.contains("404"))
        }

    @Test
    fun `ignore version suppresses auto dialog for non-mandatory updates but mandatory updates cannot be ignored`() =
        runBlocking {
            val now = AtomicLong(1_700_000_000_000L)
            val prefsStore = AppUpdatePreferencesStore()
            val manager = createManager(
                prefsStore = prefsStore,
                currentVersionName = "0.6.0",
                currentVersionCode = 3L,
                clockMs = { now.get() },
            )

            // 1. 非强更 0.6.1 (601)
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    manifestJson(versionName = "0.6.1", versionCode = 601L, minVersionCode = 0L),
                ),
            )
            val first = manager.checkForUpdates(manual = false) as AppUpdateCheckResult.UpdateAvailable
            assertFalse(first.isMandatory)
            assertFalse(first.ignoredByUser)
            assertTrue(manager.state.value.showDialog)

            // 忽略该版本 -> 记录 ignoredVersionCode 且关闭弹窗
            manager.ignoreCurrentUpdate()
            assertEquals(601L, prefsStore.preferences.value.ignoredVersionCode)
            assertEquals(601L, manager.state.value.ignoredVersionCode)
            assertFalse(manager.state.value.showDialog)

            // 6 小时后自动检查同一版本 -> 不自动弹窗
            now.addAndGet(AppUpdatePreferencesStore.DEFAULT_AUTO_CHECK_INTERVAL_MS + 10L)
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    manifestJson(versionName = "0.6.1", versionCode = 601L, minVersionCode = 0L),
                ),
            )
            val secondAuto = manager.checkForUpdates(manual = false) as AppUpdateCheckResult.UpdateAvailable
            assertTrue(secondAuto.ignoredByUser)
            assertFalse(manager.state.value.showDialog)

            // 手动检查绕过忽略记录 -> 直接弹窗
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    manifestJson(versionName = "0.6.1", versionCode = 601L, minVersionCode = 0L),
                ),
            )
            val manualRes = manager.checkForUpdates(manual = true) as AppUpdateCheckResult.UpdateAvailable
            assertFalse(manualRes.ignoredByUser)
            assertTrue(manager.state.value.showDialog)

            // 2. 强制更新（minVersionCode = 600 > currentVersionCode 3），即使 ignoredVersionCode 已预设为 602 也强制弹窗
            prefsStore.setIgnoredVersionCode(602L)
            now.addAndGet(AppUpdatePreferencesStore.DEFAULT_AUTO_CHECK_INTERVAL_MS + 10L)
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    manifestJson(versionName = "0.6.2", versionCode = 602L, minVersionCode = 600L),
                ),
            )
            val mandatoryAuto = manager.checkForUpdates(manual = false) as AppUpdateCheckResult.UpdateAvailable
            assertTrue(mandatoryAuto.isMandatory)
            assertFalse(mandatoryAuto.ignoredByUser)
            assertTrue(manager.state.value.showDialog)
            assertTrue(manager.state.value.isMandatoryUpdate)

            // 强更调用 ignoreCurrentUpdate 无效，不关闭弹窗且不改写忽略版本
            prefsStore.clearIgnoredVersion()
            manager.ignoreCurrentUpdate()
            assertEquals(0L, prefsStore.preferences.value.ignoredVersionCode)
            assertTrue(manager.state.value.showDialog)
        }

    @Test
    fun `production mode blocks cleartext http manifest and download urls`() = runBlocking {
        // 生产模式（allowCleartextForTests = false）遇到 http:// manifestUrl 视为未配置安全源
        val httpManager = createManager(
            manifestUrl = { "http://example.com/android/latest.json" },
            allowCleartextForTests = false,
        )
        assertFalse(httpManager.state.value.isConfigured)
        val res = httpManager.checkForUpdates(manual = true)
        assertTrue(res is AppUpdateCheckResult.NotConfigured)

        // 即使已有 release 构造了非 https downloadUrl，生产模式 startDownload 也会二次拦截
        val strictCheck = AppUpdateManifestParser.validateDownloadUrl(
            raw = "http://dl.example.com/FlameContractor-0.6.1.apk",
            allowCleartext = false,
        )
        assertNull(strictCheck)
    }

    @Test
    fun `streaming download reports progress verifies sha256 renames apk and cleans old apks`() =
        runBlocking {
            val updatesDir = File(tempCacheDir, AppUpdateManager.APK_UPDATES_DIR_NAME).apply { mkdirs() }
            val oldApk1 = File(updatesDir, "FlameContractor-0.5.9.apk").apply { writeText("old-1") }
            val oldApk2 = File(updatesDir, "FlameContractor-0.6.0.apk").apply { writeText("old-2") }
            val stalePart = File(updatesDir, "FlameContractor-0.6.0.apk.part").apply { writeText("stale") }

            val apkBytes = ByteArray(128 * 1024) { index -> (index * 31 and 0xFF).toByte() }
            val expectedSha = sha256Hex(apkBytes)
            // 使用大写 SHA-256 验证忽略大小写比对
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    manifestJson(
                        versionName = "0.6.1",
                        versionCode = 601L,
                        fileSize = apkBytes.size.toLong(),
                        sha256 = expectedSha.uppercase(Locale.ROOT),
                    ),
                ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(Buffer().write(apkBytes))
                    .throttleBody(32 * 1024, 25, TimeUnit.MILLISECONDS),
            )

            val manager = createManager()
            assertTrue(manager.checkForUpdates(manual = true) is AppUpdateCheckResult.UpdateAvailable)

            val seenStatuses = mutableListOf<AppUpdateStatus>()
            val collectorJob = launch(Dispatchers.Default) {
                manager.state.collect { state ->
                    seenStatuses.add(state.status)
                }
            }

            manager.startDownload()
            val finalState = withTimeout(10_000L) {
                manager.state.first {
                    it.status is AppUpdateStatus.ReadyToInstall || it.status is AppUpdateStatus.Error
                }
            }
            collectorJob.cancel()

            assertTrue("Expected ReadyToInstall but got ${finalState.status}", finalState.status is AppUpdateStatus.ReadyToInstall)
            val ready = finalState.status as AppUpdateStatus.ReadyToInstall
            val apkFile = File(ready.apkFilePath)
            assertTrue(apkFile.isFile)
            assertEquals("FlameContractor-0.6.1.apk", apkFile.name)
            assertArrayEquals(apkBytes, apkFile.readBytes())

            // .part 临时文件与历史旧 APK 均已清理
            assertFalse(File(updatesDir, "FlameContractor-0.6.1.apk.part").exists())
            assertFalse(oldApk1.exists())
            assertFalse(oldApk2.exists())
            assertFalse(stalePart.exists())

            // 曾观测到流式下载进度状态
            val downloadingSnapshots = seenStatuses.filterIsInstance<AppUpdateStatus.Downloading>()
            assertTrue(downloadingSnapshots.isNotEmpty())
            assertTrue(downloadingSnapshots.any { (it.progressPercent ?: -1) in 0..100 })
        }

    @Test
    fun `sha256 mismatch deletes temp file and transitions to error state`() = runBlocking {
        val apkBytes = "tampered-apk-payload".toByteArray(Charsets.UTF_8)
        val wrongSha = "f".repeat(64)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                manifestJson(
                    versionName = "0.6.1",
                    versionCode = 601L,
                    fileSize = apkBytes.size.toLong(),
                    sha256 = wrongSha,
                ),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes)))

        val manager = createManager()
        assertTrue(manager.checkForUpdates(manual = true) is AppUpdateCheckResult.UpdateAvailable)
        manager.startDownload()

        val finalState = withTimeout(10_000L) {
            manager.state.first { it.status is AppUpdateStatus.Error || it.status is AppUpdateStatus.ReadyToInstall }
        }
        assertTrue(finalState.status is AppUpdateStatus.Error)
        val err = finalState.status as AppUpdateStatus.Error
        assertEquals("安装包校验失败，已拦截安装，请重试", err.message)
        assertTrue(err.retryable)
        assertNotNull(err.release)

        val updatesDir = File(tempCacheDir, AppUpdateManager.APK_UPDATES_DIR_NAME)
        val remainingFiles = updatesDir.listFiles().orEmpty().toList()
        assertTrue("Expected no apk or part files left, found: $remainingFiles", remainingFiles.isEmpty())
    }

    @Test
    fun `download enforces max apk byte limit and cleans up part file`() = runBlocking {
        val oversized = ByteArray(2048) { 7 }
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                manifestJson(
                    versionName = "0.6.1",
                    versionCode = 601L,
                    fileSize = 512L,
                    sha256 = sha256Hex(oversized),
                ),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setChunkedBody(Buffer().write(oversized), 512))

        val manager = createManager(maxApkBytes = 1024L)
        assertTrue(manager.checkForUpdates(manual = true) is AppUpdateCheckResult.UpdateAvailable)
        manager.startDownload()

        val finalState = withTimeout(10_000L) {
            manager.state.first { it.status is AppUpdateStatus.Error || it.status is AppUpdateStatus.ReadyToInstall }
        }
        assertTrue(finalState.status is AppUpdateStatus.Error)
        val err = finalState.status as AppUpdateStatus.Error
        assertFalse(err.retryable)
        assertTrue(err.message.contains("150 MB"))

        val updatesDir = File(tempCacheDir, AppUpdateManager.APK_UPDATES_DIR_NAME)
        assertTrue(updatesDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cancelDownload aborts streaming deletes part file and reverts to UpdateAvailable`() =
        runBlocking {
            val largeBytes = ByteArray(256 * 1024) { 3 }
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    manifestJson(
                        versionName = "0.6.1",
                        versionCode = 601L,
                        fileSize = largeBytes.size.toLong(),
                        sha256 = sha256Hex(largeBytes),
                    ),
                ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(Buffer().write(largeBytes))
                    .throttleBody(4 * 1024, 150, TimeUnit.MILLISECONDS),
            )

            val manager = createManager()
            assertTrue(manager.checkForUpdates(manual = true) is AppUpdateCheckResult.UpdateAvailable)
            manager.startDownload()

            // 等待至少读到第一批字节进入 Downloading
            withTimeout(5_000L) {
                manager.state.first {
                    val s = it.status
                    s is AppUpdateStatus.Downloading && s.downloadedBytes > 0L
                }
            }

            // 下载中默认 dismissDialog 保持弹窗不误关
            manager.dismissDialog()
            assertTrue(manager.state.value.showDialog)

            manager.cancelDownload()
            assertTrue(manager.state.value.status is AppUpdateStatus.UpdateAvailable)

            // 稍等 IO 协程彻底退出并验证 .part 文件已清理
            val updatesDir = File(tempCacheDir, AppUpdateManager.APK_UPDATES_DIR_NAME)
            val deadline = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < deadline && updatesDir.listFiles().orEmpty().isNotEmpty()) {
                Thread.sleep(30L)
            }
            assertTrue(
                "Expected .part file cleaned up after cancel",
                updatesDir.listFiles().orEmpty().none { it.name.endsWith(".part") },
            )

            // 非下载中允许关闭弹窗并支持从设置页重新打开
            manager.dismissDialog()
            assertFalse(manager.state.value.showDialog)
            manager.reopenUpdateDialog()
            assertTrue(manager.state.value.showDialog)
        }

    @Test
    fun `installReadyApk handles unknown sources permission and onHostResumed auto-continues`() =
        runBlocking {
            val apkBytes = "valid-apk-content".toByteArray(Charsets.UTF_8)
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    manifestJson(
                        versionName = "0.6.1",
                        versionCode = 601L,
                        fileSize = apkBytes.size.toLong(),
                        sha256 = sha256Hex(apkBytes),
                    ),
                ),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes)))

            val permissionGranted = AtomicBoolean(false)
            val launchedFiles = mutableListOf<File>()
            val manager = createManager(
                canRequestPackageInstalls = { permissionGranted.get() },
                installerLauncher = { _, file -> launchedFiles.add(file) },
            )
            val fakeContext = ContextWrapper(null)

            assertTrue(manager.checkForUpdates(manual = true) is AppUpdateCheckResult.UpdateAvailable)
            manager.startDownload()
            withTimeout(10_000L) {
                manager.state.first { it.status is AppUpdateStatus.ReadyToInstall }
            }

            // 权限未开启：置 pendingInstallAfterPermissionGrant = true 并返回 NeedsUnknownSourcesPermission
            assertEquals(
                AppUpdateInstallOutcome.NeedsUnknownSourcesPermission,
                manager.installReadyApk(fakeContext),
            )
            assertTrue(manager.state.value.pendingInstallAfterPermissionGrant)
            assertTrue(launchedFiles.isEmpty())

            // 返回宿主但用户未授权：保持等待，不拉起安装
            assertNull(manager.onHostResumed(fakeContext))
            assertTrue(manager.state.value.pendingInstallAfterPermissionGrant)
            assertTrue(launchedFiles.isEmpty())

            // 用户授权后返回宿主：自动清除标记并续接拉起系统安装器
            permissionGranted.set(true)
            assertEquals(AppUpdateInstallOutcome.Launched, manager.onHostResumed(fakeContext))
            assertFalse(manager.state.value.pendingInstallAfterPermissionGrant)
            assertEquals(1, launchedFiles.size)
            assertEquals("FlameContractor-0.6.1.apk", launchedFiles.single().name)
        }

    @Test
    fun `cleanupStaleApks removes old apks and parts in Idle state`() {
        val updatesDir = File(tempCacheDir, AppUpdateManager.APK_UPDATES_DIR_NAME).apply { mkdirs() }
        val staleApk1 = File(updatesDir, "FlameContractor-0.5.0.apk").apply { writeText("old") }
        val staleApk2 = File(updatesDir, "FlameContractor-0.6.0.apk").apply { writeText("old2") }
        val stalePart = File(updatesDir, "FlameContractor-0.6.1.apk.part").apply { writeText("part") }
        val unrelated = File(updatesDir, "readme.txt").apply { writeText("keep") }

        val manager = createManager()
        val deleted = manager.cleanupStaleApks()

        assertEquals(3, deleted)
        assertFalse(staleApk1.exists())
        assertFalse(staleApk2.exists())
        assertFalse(stalePart.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `loadRecentReleases fetches caches refreshes and keeps update status intact`() = runBlocking {
        val recentJsonFirst = """
            [
              {"versionName": "0.8.0", "versionCode": 800, "publishedAt": "2026-11-28", "changelog": "• 平板自适应重构"},
              {"versionName": "0.9.0", "versionCode": 900, "publishedAt": "2026-11-28", "changelog": "• 默认极简模式"},
              {"versionName": "0.7.0", "versionCode": 700, "publishedAt": "2026-11-26", "changelog": "• 极简模式首版"}
            ]
        """.trimIndent()
        val recentJsonSecond = """
            [
              {"versionName": "0.9.1", "versionCode": 901, "publishedAt": "2026-11-29", "changelog": "• 补丁修复"},
              {"versionName": "0.9.0", "versionCode": 900, "publishedAt": "2026-11-28", "changelog": "• 默认极简模式"}
            ]
        """.trimIndent()

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                manifestJson(
                    versionName = "0.9.0",
                    versionCode = 900L,
                    changelog = "• 默认极简模式",
                    recentReleasesJson = recentJsonFirst,
                ),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                manifestJson(
                    versionName = "0.9.1",
                    versionCode = 901L,
                    changelog = "• 补丁修复",
                    recentReleasesJson = recentJsonSecond,
                ),
            ),
        )

        val manager = createManager(currentVersionName = "0.7.0", currentVersionCode = 700L)
        val firstResult = manager.loadRecentReleases()
        assertTrue(firstResult.isSuccess)
        val firstList = firstResult.getOrThrow()
        assertEquals(listOf(900L, 800L, 700L), firstList.map { it.versionCode })
        assertEquals(firstList, manager.state.value.recentReleases)
        assertEquals(AppUpdateStatus.Idle, manager.state.value.status)
        assertFalse(manager.state.value.showDialog)
        assertEquals(1, server.requestCount)

        // 第二次非强制刷新直接命中缓存，不发网络请求
        val cachedResult = manager.loadRecentReleases(forceRefresh = false)
        assertTrue(cachedResult.isSuccess)
        assertEquals(firstList, cachedResult.getOrThrow())
        assertEquals(1, server.requestCount)

        // 强制刷新发起新请求并覆盖缓存
        val refreshedResult = manager.loadRecentReleases(forceRefresh = true)
        assertTrue(refreshedResult.isSuccess)
        val refreshedList = refreshedResult.getOrThrow()
        assertEquals(listOf(901L, 900L), refreshedList.map { it.versionCode })
        assertEquals(refreshedList, manager.state.value.recentReleases)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `loadRecentReleases falls back to current changelog on legacy manifest and reports friendly errors`() =
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    manifestJson(
                        versionName = "0.9.0",
                        versionCode = 900L,
                        changelog = "• 仅最新版更新说明",
                    ),
                ),
            )
            server.enqueue(MockResponse().setResponseCode(404))

            val manager = createManager(currentVersionName = "0.7.0", currentVersionCode = 700L)
            val fallbackResult = manager.loadRecentReleases()
            assertTrue(fallbackResult.isSuccess)
            val fallbackItems = fallbackResult.getOrThrow()
            assertEquals(1, fallbackItems.size)
            assertEquals("0.9.0", fallbackItems.single().versionName)
            assertEquals(900L, fallbackItems.single().versionCode)
            assertEquals("• 仅最新版更新说明", fallbackItems.single().changelog)

            val httpErr = manager.loadRecentReleases(forceRefresh = true)
            assertTrue(httpErr.isFailure)
            val errMsg = httpErr.exceptionOrNull()?.message.orEmpty()
            assertTrue("Unexpected error message: $errMsg", errMsg.contains("404") || errMsg.contains("暂不可用"))

            val unconfiguredManager = createManager(manifestUrl = { "" })
            val unconfiguredErr = unconfiguredManager.loadRecentReleases()
            assertTrue(unconfiguredErr.isFailure)
            assertTrue(
                unconfiguredErr.exceptionOrNull()?.message.orEmpty().contains("未配置自动更新源"),
            )
        }
}
