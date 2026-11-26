package io.github.oinzc.flamecontractor.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.oinzc.flamecontractor.core.diagnostics.DiagnosticLogStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Android 应用内自动检查更新核心引擎：
 * - 单例由 `AppContainer` 持有，复用 OkHttp 负责拉取 `latest.json` 与流式下载 APK；
 * - 支持自动检查（6 小时节流、静默失败、尊重忽略版本）与手动检查（绕过节流与忽略）；
 * - 生产环境强制要求 `https://` 清单与下载链接（禁止 HTTPS->HTTP 降级重定向）；
 * - 下载过程受 150 MB 体积上限保护，完成后流式计算 SHA-256 校验，通过后原子落盘并清理旧包；
 * - 支持 `canRequestPackageInstalls()` 授权引导与返回 App 自动续接拉起系统安装器。
 */
class AppUpdateManager(
    private val preferencesStore: AppUpdatePreferencesStore,
    private val manifestUrlProvider: () -> String,
    private val currentVersionName: String,
    private val currentVersionCode: Long,
    private val isDebugVariant: Boolean,
    private val cacheDir: File,
    okHttpClient: OkHttpClient = defaultHttpClient(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val allowCleartextForTests: Boolean = false,
    private val minAutoCheckIntervalMs: Long = AppUpdatePreferencesStore.DEFAULT_AUTO_CHECK_INTERVAL_MS,
    private val maxApkBytes: Long = MAX_APK_BYTES,
    private val canRequestPackageInstalls: (Context) -> Boolean = ::defaultCanRequestPackageInstalls,
    private val installerLauncher: (Context, File) -> Unit = ::defaultLaunchApkInstaller,
    private val diagnosticLog: (category: String, event: String, level: String, detail: String) -> Unit =
        { category, event, level, detail ->
            DiagnosticLogStore.recordGlobal(
                category = category,
                event = event,
                level = level,
                detail = detail,
            )
        },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val manifestClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(MANIFEST_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(MANIFEST_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .callTimeout(MANIFEST_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(allowCleartextForTests)
        .build()

    private val downloadClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(DOWNLOAD_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .callTimeout(0L, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(allowCleartextForTests)
        .build()

    private val checkMutex = Mutex()
    private val downloadLock = Any()

    private var downloadJob: Job? = null
    private var activeDownloadCall: Call? = null
    private var activePartFileName: String? = null
    private var activeDownloadRunId: Long = 0L
    private var nextDownloadRunId: Long = 0L

    private val updatesDir: File
        get() = File(cacheDir, APK_UPDATES_DIR_NAME)

    private val _state = MutableStateFlow(buildInitialState())
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private fun buildInitialState(): AppUpdateUiState {
        val prefs = preferencesStore.preferences.value
        val configured = normalizeManifestUrl(manifestUrlProvider(), allowCleartextForTests) != null
        return AppUpdateUiState(
            isConfigured = configured,
            autoCheckEnabled = prefs.autoCheckEnabled,
            currentVersionName = currentVersionName,
            currentVersionCode = currentVersionCode,
            isDebugVariant = isDebugVariant,
            lastCheckedAtEpochMs = prefs.lastAutoCheckAtEpochMs.takeIf { it > 0L },
            ignoredVersionCode = prefs.ignoredVersionCode.takeIf { it > 0L },
            status = AppUpdateStatus.Idle,
            showDialog = false,
            pendingInstallAfterPermissionGrant = false,
        )
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        preferencesStore.setAutoCheckEnabled(enabled)
        _state.update { it.copy(autoCheckEnabled = enabled) }
    }

    /**
     * 执行版本更新检查：
     * - `manual = false`：遵循自动检查开关与 6h 节流，断网/超时/解析失败静默吞掉不弹错；
     * - `manual = true`：绕过节流与忽略版本，立即展示明确结果。
     */
    suspend fun checkForUpdates(manual: Boolean): AppUpdateCheckResult {
        val currentStatus = _state.value.status
        if (currentStatus is AppUpdateStatus.Downloading || currentStatus is AppUpdateStatus.Verifying) {
            if (manual) {
                _state.update { it.copy(showDialog = true) }
            }
            return AppUpdateCheckResult.Skipped
        }

        if (!checkMutex.tryLock()) {
            return AppUpdateCheckResult.Skipped
        }

        try {
            val manifestUrl = normalizeManifestUrl(manifestUrlProvider(), allowCleartextForTests)
            if (manifestUrl == null) {
                _state.update { it.copy(isConfigured = false) }
                return if (manual) {
                    _state.update {
                        it.copy(
                            isConfigured = false,
                            status = AppUpdateStatus.Error(
                                message = UNCONFIGURED_MESSAGE,
                                retryable = false,
                                release = null,
                            ),
                            showDialog = false,
                        )
                    }
                    diagnosticLog(LOG_CATEGORY, "check_unconfigured", "INFO", "manual=true")
                    AppUpdateCheckResult.NotConfigured(UNCONFIGURED_MESSAGE)
                } else {
                    AppUpdateCheckResult.NotConfigured(UNCONFIGURED_MESSAGE)
                }
            }

            val nowMs = clockMillis()
            if (!manual && !preferencesStore.shouldRunAutoCheck(nowMs, minAutoCheckIntervalMs)) {
                return AppUpdateCheckResult.Skipped
            }

            val previousStatus = _state.value.status
            preferencesStore.recordCheckAttempt(nowMs)
            _state.update {
                it.copy(
                    isConfigured = true,
                    lastCheckedAtEpochMs = nowMs,
                    status = AppUpdateStatus.Checking,
                )
            }

            val release = try {
                fetchAndParseManifest(manifestUrl)
            } catch (ce: CancellationException) {
                if (_state.value.status == AppUpdateStatus.Checking) {
                    _state.update { it.copy(status = fallbackRestoredStatus(previousStatus)) }
                }
                throw ce
            } catch (exc: AppUpdateManifestException) {
                diagnosticLog(
                    LOG_CATEGORY,
                    "check_manifest_invalid",
                    "WARN",
                    "manual=$manual reason=${exc.reasonCode}",
                )
                return handleCheckFailure(
                    manual = manual,
                    previousStatus = previousStatus,
                    message = exc.userMessage,
                    retryable = exc.retryable,
                )
            } catch (exc: HttpStatusException) {
                diagnosticLog(
                    LOG_CATEGORY,
                    "check_http_error",
                    "WARN",
                    "manual=$manual code=${exc.statusCode}",
                )
                val msg = when (exc.statusCode) {
                    404 -> "更新清单暂不可用（HTTP 404），请稍后再试"
                    in 500..599 -> "更新服务暂时繁忙（HTTP ${exc.statusCode}），请稍后重试"
                    else -> "检查更新失败（HTTP ${exc.statusCode}），请稍后重试"
                }
                return handleCheckFailure(
                    manual = manual,
                    previousStatus = previousStatus,
                    message = msg,
                    retryable = true,
                )
            } catch (exc: IOException) {
                diagnosticLog(
                    LOG_CATEGORY,
                    "check_network_error",
                    "WARN",
                    "manual=$manual err=${exc.javaClass.simpleName}",
                )
                return handleCheckFailure(
                    manual = manual,
                    previousStatus = previousStatus,
                    message = "网络连接失败，无法检查更新，请检查网络后重试",
                    retryable = true,
                )
            } catch (exc: Throwable) {
                diagnosticLog(
                    LOG_CATEGORY,
                    "check_unexpected_error",
                    "WARN",
                    "manual=$manual err=${exc.javaClass.simpleName}",
                )
                return handleCheckFailure(
                    manual = manual,
                    previousStatus = previousStatus,
                    message = "检查更新时发生异常，请稍后重试",
                    retryable = true,
                )
            }

            preferencesStore.recordKnownLatestRelease(release.versionName, release.versionCode)
            val evaluation = AppVersionComparator.evaluateUpdate(
                release = release,
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode,
            )

            if (!evaluation.isUpdateAvailable) {
                _state.update {
                    it.copy(
                        isConfigured = true,
                        status = AppUpdateStatus.UpToDate,
                        showDialog = false,
                        pendingInstallAfterPermissionGrant = false,
                    )
                }
                diagnosticLog(
                    LOG_CATEGORY,
                    "check_up_to_date",
                    "INFO",
                    "manual=$manual current=$currentVersionName($currentVersionCode) remote=${release.versionName}(${release.versionCode})",
                )
                return AppUpdateCheckResult.UpToDate
            }

            val ignoredCode = preferencesStore.preferences.value.ignoredVersionCode
            val isIgnored = !evaluation.isMandatory &&
                ignoredCode > 0L &&
                ignoredCode == release.versionCode
            val shouldOpenDialog = manual || evaluation.isMandatory || !isIgnored

            val preservedReady = (previousStatus as? AppUpdateStatus.ReadyToInstall)?.takeIf { ready ->
                ready.release.versionCode == release.versionCode &&
                    ready.release.sha256.equals(release.sha256, ignoreCase = true) &&
                    File(ready.apkFilePath).let { it.isFile && it.length() > 0L }
            }

            val nextStatus = preservedReady ?: AppUpdateStatus.UpdateAvailable(
                release = release,
                isMandatory = evaluation.isMandatory,
            )

            _state.update {
                it.copy(
                    isConfigured = true,
                    ignoredVersionCode = ignoredCode.takeIf { code -> code > 0L },
                    status = nextStatus,
                    showDialog = shouldOpenDialog,
                )
            }
            diagnosticLog(
                LOG_CATEGORY,
                "check_update_available",
                "INFO",
                "manual=$manual remote=${release.versionName}(${release.versionCode}) mandatory=${evaluation.isMandatory} ignored=$isIgnored",
            )
            return AppUpdateCheckResult.UpdateAvailable(
                release = release,
                isMandatory = evaluation.isMandatory,
                ignoredByUser = if (manual) false else isIgnored,
            )
        } finally {
            checkMutex.unlock()
        }
    }

    /**
     * 开始流式下载当前已发现的更新包，下载完成后自动进行 SHA-256 严格校验并清理历史旧包。
     */
    fun startDownload() {
        val currentStatus = _state.value.status
        val release = when (currentStatus) {
            is AppUpdateStatus.UpdateAvailable -> currentStatus.release
            is AppUpdateStatus.Error -> currentStatus.release ?: return
            is AppUpdateStatus.ReadyToInstall -> {
                val existing = File(currentStatus.apkFilePath)
                if (existing.isFile && existing.length() > 0L) {
                    _state.update { it.copy(showDialog = true) }
                    return
                }
                currentStatus.release
            }
            is AppUpdateStatus.Downloading,
            is AppUpdateStatus.Verifying,
            AppUpdateStatus.Idle,
            AppUpdateStatus.Checking,
            AppUpdateStatus.UpToDate -> return
        }

        val validatedUrl = AppUpdateManifestParser.validateDownloadUrl(
            raw = release.downloadUrl,
            allowCleartext = allowCleartextForTests,
        )
        if (validatedUrl == null) {
            _state.update {
                it.copy(
                    status = AppUpdateStatus.Error(
                        message = "更新包下载地址不安全（仅支持 HTTPS），已拦截下载",
                        retryable = false,
                        release = release,
                    ),
                    showDialog = true,
                )
            }
            diagnosticLog(LOG_CATEGORY, "download_blocked_url", "WARN", "version=${release.versionName}")
            return
        }

        if (release.fileSize > maxApkBytes) {
            _state.update {
                it.copy(
                    status = AppUpdateStatus.Error(
                        message = SIZE_LIMIT_EXCEEDED_MESSAGE,
                        retryable = false,
                        release = release,
                    ),
                    showDialog = true,
                )
            }
            diagnosticLog(LOG_CATEGORY, "download_blocked_size", "WARN", "version=${release.versionName}")
            return
        }

        synchronized(downloadLock) {
            if (downloadJob?.isActive == true && activeDownloadRunId != 0L) {
                return
            }
            val runId = ++nextDownloadRunId
            activeDownloadRunId = runId
            val safeVersion = sanitizeVersionForFileName(release.versionName)
            val partName = "FlameContractor-$safeVersion$APK_PART_SUFFIX"
            activePartFileName = partName

            val initialTotal = release.fileSize.coerceAtLeast(0L)
            _state.update {
                it.copy(
                    status = AppUpdateStatus.Downloading(
                        release = release,
                        downloadedBytes = 0L,
                        totalBytes = initialTotal,
                        progressPercent = if (initialTotal > 0L) 0 else null,
                    ),
                    showDialog = true,
                    pendingInstallAfterPermissionGrant = false,
                )
            }

            downloadJob = scope.launch {
                executeDownloadPipeline(
                    runId = runId,
                    release = release,
                    downloadUrl = validatedUrl,
                    safeVersion = safeVersion,
                )
            }
        }
    }

    /**
     * 取消正在进行的下载任务并立即清理 `.part` 临时文件，回退到可重新下载状态。
     */
    fun cancelDownload() {
        val activeRelease = _state.value.activeRelease
        val callToCancel: Call?
        val jobToCancel: Job?
        val partNameToDelete: String?
        synchronized(downloadLock) {
            activeDownloadRunId = 0L
            callToCancel = activeDownloadCall
            jobToCancel = downloadJob
            partNameToDelete = activePartFileName
            activeDownloadCall = null
            downloadJob = null
            activePartFileName = null
        }

        runCatching { callToCancel?.cancel() }
        jobToCancel?.cancel()

        partNameToDelete?.let { name ->
            resolveInUpdatesDir(name)?.let { file ->
                runCatching { if (file.exists()) file.delete() }
            }
        }
        deleteAllPartFiles(excludeFileName = null)

        val currentStatus = _state.value.status
        if ((currentStatus is AppUpdateStatus.Downloading || currentStatus is AppUpdateStatus.Verifying) &&
            activeRelease != null
        ) {
            val isMandatory = activeRelease.minVersionCode > 0L &&
                currentVersionCode < activeRelease.minVersionCode
            _state.update {
                it.copy(
                    status = AppUpdateStatus.UpdateAvailable(
                        release = activeRelease,
                        isMandatory = isMandatory,
                    ),
                    pendingInstallAfterPermissionGrant = false,
                )
            }
            diagnosticLog(LOG_CATEGORY, "download_cancelled", "INFO", "version=${activeRelease.versionName}")
        }
    }

    /**
     * 忽略当前更新版本（仅非强制更新生效；强制更新严禁忽略）。
     */
    fun ignoreCurrentUpdate() {
        val current = _state.value
        val release = current.activeRelease ?: return
        if (current.isMandatoryUpdate) {
            return
        }
        if (current.status is AppUpdateStatus.Downloading || current.status is AppUpdateStatus.Verifying) {
            cancelDownload()
        }
        preferencesStore.setIgnoredVersionCode(release.versionCode)
        _state.update {
            it.copy(
                ignoredVersionCode = release.versionCode,
                showDialog = false,
                pendingInstallAfterPermissionGrant = false,
            )
        }
        diagnosticLog(
            LOG_CATEGORY,
            "update_ignored",
            "INFO",
            "version=${release.versionName}(${release.versionCode})",
        )
    }

    /**
     * 关闭更新弹窗：
     * - 非下载/校验中默认允许关闭；
     * - 下载/校验中默认保持弹窗避免误触丢失进度，传入 `allowDuringDownload = true` 时允许后台继续下载。
     */
    fun dismissDialog(allowDuringDownload: Boolean = false) {
        val currentStatus = _state.value.status
        val isBusy = currentStatus is AppUpdateStatus.Downloading || currentStatus is AppUpdateStatus.Verifying
        if (isBusy && !allowDuringDownload) {
            return
        }
        _state.update {
            it.copy(
                showDialog = false,
                pendingInstallAfterPermissionGrant = false,
            )
        }
    }

    /**
     * 设置页点击“发现新版本”或后台下载入口时重新弹出更新 BottomSheet。
     */
    fun reopenUpdateDialog() {
        if (_state.value.hasAvailableUpdate) {
            _state.update { it.copy(showDialog = true) }
        }
    }

    /**
     * 拉起系统 APK 安装器：
     * - 未开启“安装未知应用”权限时标记 `pendingInstallAfterPermissionGrant = true` 并返回 [AppUpdateInstallOutcome.NeedsUnknownSourcesPermission]；
     * - 已授权时通过 `FileProvider` 生成授权 URI 并启动系统安装界面。
     */
    fun installReadyApk(context: Context): AppUpdateInstallOutcome {
        val ready = _state.value.status as? AppUpdateStatus.ReadyToInstall
            ?: return AppUpdateInstallOutcome.Failed("安装包尚未下载完成，请先下载更新")

        val apkFile = File(ready.apkFilePath)
        val verifiedFile = resolveExistingInUpdatesDir(apkFile.name)
        val canonicalReadyPath = runCatching { apkFile.canonicalFile.absolutePath }.getOrNull()
        if (verifiedFile == null || verifiedFile.absolutePath != canonicalReadyPath) {
            _state.update {
                it.copy(
                    status = AppUpdateStatus.Error(
                        message = "缓存的安装包已失效或被系统清理，请重新下载",
                        retryable = true,
                        release = ready.release,
                    ),
                    pendingInstallAfterPermissionGrant = false,
                )
            }
            return AppUpdateInstallOutcome.Failed("缓存的安装包已失效或被系统清理，请重新下载")
        }

        if (!canRequestPackageInstalls(context)) {
            _state.update { it.copy(pendingInstallAfterPermissionGrant = true) }
            diagnosticLog(
                LOG_CATEGORY,
                "install_needs_permission",
                "INFO",
                "version=${ready.release.versionName}",
            )
            return AppUpdateInstallOutcome.NeedsUnknownSourcesPermission
        }

        _state.update { it.copy(pendingInstallAfterPermissionGrant = false) }
        return try {
            installerLauncher(context, verifiedFile)
            diagnosticLog(
                LOG_CATEGORY,
                "install_launched",
                "INFO",
                "version=${ready.release.versionName}",
            )
            AppUpdateInstallOutcome.Launched
        } catch (exc: Throwable) {
            diagnosticLog(
                LOG_CATEGORY,
                "install_launch_failed",
                "ERROR",
                "version=${ready.release.versionName} err=${exc.javaClass.simpleName}",
            )
            AppUpdateInstallOutcome.Failed("无法拉起系统安装器，请检查系统安装权限或前往发布页手动安装")
        }
    }

    /**
     * 构造跳转系统“允许安装未知来源应用”设置页的 Intent。
     */
    fun createUnknownSourcesSettingsIntent(context: Context): Intent =
        Companion.createUnknownSourcesSettingsIntent(context)

    /**
     * 宿主 Activity `onResume` 回调：若此前等待未知来源授权且现已授权，自动清除标记并续接安装。
     */
    fun onHostResumed(context: Context): AppUpdateInstallOutcome? {
        val current = _state.value
        if (!current.pendingInstallAfterPermissionGrant) return null
        if (current.status !is AppUpdateStatus.ReadyToInstall) {
            _state.update { it.copy(pendingInstallAfterPermissionGrant = false) }
            return null
        }
        if (!canRequestPackageInstalls(context)) {
            return null
        }
        _state.update { it.copy(pendingInstallAfterPermissionGrant = false) }
        return installReadyApk(context)
    }

    /**
     * 清理 `cacheDir/apk_updates/` 下的历史旧 `.apk` 与残留 `.part` 文件（保留当前就绪或正在下载的文件）。
     * 返回实际删除的文件数量。
     */
    fun cleanupStaleApks(): Int {
        return try {
            val dir = updatesDir
            if (!dir.isDirectory) return 0
            val keepApkName = (_state.value.status as? AppUpdateStatus.ReadyToInstall)
                ?.let { File(it.apkFilePath).name }
            val keepPartName = synchronized(downloadLock) { activePartFileName }

            val files = dir.listFiles() ?: return 0
            var deleted = 0
            for (file in files) {
                if (!file.isFile) continue
                val name = file.name
                val isManagedFile = name.endsWith(APK_SUFFIX) || name.endsWith(APK_PART_SUFFIX)
                if (!isManagedFile) continue
                if (name == keepApkName || name == keepPartName) continue
                if (file.delete()) {
                    deleted++
                }
            }
            if (deleted > 0) {
                diagnosticLog(LOG_CATEGORY, "cleanup_stale_apks", "INFO", "deleted=$deleted")
            }
            deleted
        } catch (_: Throwable) {
            0
        }
    }

    private suspend fun fetchAndParseManifest(manifestUrl: String): AppUpdateRelease =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(manifestUrl)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            manifestClient.newCall(request).execute().use { response ->
                if (!allowCleartextForTests && !response.request.url.isHttps) {
                    throw AppUpdateManifestException(
                        userMessage = "更新源链接被重定向到非 HTTPS 地址，已拦截",
                        reasonCode = "insecure_redirect",
                        retryable = false,
                    )
                }
                if (!response.isSuccessful) {
                    throw HttpStatusException(response.code)
                }
                val body = response.body ?: throw AppUpdateManifestException(
                    userMessage = "更新清单响应为空，请稍后重试",
                    reasonCode = "empty_body",
                )
                val declaredLen = body.contentLength()
                if (declaredLen > AppUpdateManifestParser.MAX_MANIFEST_BYTES) {
                    throw AppUpdateManifestException(
                        userMessage = "更新清单体积异常，已拦截解析",
                        reasonCode = "manifest_too_large",
                        retryable = false,
                    )
                }
                val rawBytes = readBoundedBytes(
                    input = body.byteStream(),
                    maxBytes = AppUpdateManifestParser.MAX_MANIFEST_BYTES,
                ) ?: throw AppUpdateManifestException(
                    userMessage = "更新清单体积异常，已拦截解析",
                    reasonCode = "manifest_too_large",
                    retryable = false,
                )
                val rawText = rawBytes.toString(Charsets.UTF_8)
                AppUpdateManifestParser.parse(
                    rawJson = rawText,
                    allowCleartext = allowCleartextForTests,
                )
            }
        }

    private suspend fun executeDownloadPipeline(
        runId: Long,
        release: AppUpdateRelease,
        downloadUrl: String,
        safeVersion: String,
    ) {
        val dir = updatesDir
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            setDownloadError(runId, release, "无法创建更新缓存目录，请检查存储空间后重试", retryable = true)
            clearActiveDownloadHandles(runId)
            return
        }

        val targetName = "FlameContractor-$safeVersion$APK_SUFFIX"
        val partName = "FlameContractor-$safeVersion$APK_PART_SUFFIX"
        val targetFile = resolveInUpdatesDir(targetName)
        val partFile = resolveInUpdatesDir(partName)
        if (targetFile == null || partFile == null) {
            setDownloadError(runId, release, "更新包文件路径异常，已拦截下载", retryable = false)
            clearActiveDownloadHandles(runId)
            return
        }

        val call = downloadClient.newCall(
            Request.Builder()
                .url(downloadUrl)
                .header("Accept", "*/*")
                .get()
                .build(),
        )
        synchronized(downloadLock) {
            if (activeDownloadRunId != runId) {
                return
            }
            activeDownloadCall = call
        }

        runCatching { if (partFile.exists()) partFile.delete() }

        var succeeded = false
        try {
            val downloadedBytes = withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    currentCoroutineContext().ensureActive()
                    if (!isCurrentDownloadRun(runId)) {
                        throw CancellationException("Download superseded or cancelled")
                    }
                    if (!allowCleartextForTests && !response.request.url.isHttps) {
                        throw DownloadSecurityException("安装包下载被重定向到非 HTTPS 地址，已拦截")
                    }
                    if (!response.isSuccessful) {
                        throw HttpStatusException(response.code)
                    }
                    val body = response.body ?: throw IOException("Empty download body")
                    val contentLength = body.contentLength()
                    if (contentLength > maxApkBytes) {
                        throw DownloadSizeLimitException()
                    }
                    val expectedTotal = when {
                        contentLength > 0L -> contentLength
                        release.fileSize > 0L -> release.fileSize
                        else -> 0L
                    }

                    emitDownloadingState(
                        runId = runId,
                        release = release,
                        downloadedBytes = 0L,
                        totalBytes = expectedTotal,
                    )

                    var written = 0L
                    var lastEmitNanos = System.nanoTime()
                    var lastEmittedPercent = if (expectedTotal > 0L) 0 else -1
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)

                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                if (!isCurrentDownloadRun(runId)) {
                                    throw CancellationException("Download cancelled by user")
                                }
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue

                                written += read.toLong()
                                if (written > maxApkBytes) {
                                    throw DownloadSizeLimitException()
                                }
                                output.write(buffer, 0, read)

                                val effectiveTotal = if (expectedTotal > 0L) {
                                    maxOf(expectedTotal, written)
                                } else {
                                    0L
                                }
                                val percent = computeProgressPercent(written, effectiveTotal)
                                val nowNanos = System.nanoTime()
                                val shouldEmit = (nowNanos - lastEmitNanos >= PROGRESS_EMIT_INTERVAL_NANOS) ||
                                    (percent != null && percent == 100 && lastEmittedPercent != 100) ||
                                    (percent != null && percent > lastEmittedPercent && lastEmittedPercent == 0 && written == read.toLong())
                                if (shouldEmit) {
                                    lastEmitNanos = nowNanos
                                    lastEmittedPercent = percent ?: -1
                                    emitDownloadingState(
                                        runId = runId,
                                        release = release,
                                        downloadedBytes = written,
                                        totalBytes = effectiveTotal,
                                    )
                                }
                            }
                            output.flush()
                        }
                    }

                    if (written <= 0L) {
                        throw IOException("Downloaded APK is empty")
                    }
                    val finalTotal = if (expectedTotal > 0L) maxOf(expectedTotal, written) else written
                    emitDownloadingState(
                        runId = runId,
                        release = release,
                        downloadedBytes = written,
                        totalBytes = finalTotal,
                    )
                    written
                }
            }

            currentCoroutineContext().ensureActive()
            if (!isCurrentDownloadRun(runId)) {
                throw CancellationException("Download cancelled by user")
            }

            updateStateIfCurrentRun(runId) { it.copy(status = AppUpdateStatus.Verifying(release)) }

            val actualSha256 = withContext(Dispatchers.IO) {
                computeFileSha256Hex(partFile, runId)
            }
            if (!isCurrentDownloadRun(runId)) {
                throw CancellationException("Download cancelled during verification")
            }
            if (!actualSha256.equals(release.sha256, ignoreCase = true)) {
                deletePartFileIfNoNewerRun(runId, partFile)
                runCatching { if (targetFile.exists()) targetFile.delete() }
                diagnosticLog(
                    LOG_CATEGORY,
                    "sha256_mismatch",
                    "ERROR",
                    "version=${release.versionName}",
                )
                setDownloadError(
                    runId = runId,
                    release = release,
                    message = SHA256_MISMATCH_MESSAGE,
                    retryable = true,
                )
                return
            }

            val renamed = moveAtomically(partFile, targetFile)
            if (!renamed || !targetFile.isFile) {
                deletePartFileIfNoNewerRun(runId, partFile)
                setDownloadError(
                    runId = runId,
                    release = release,
                    message = "安装包保存失败，请检查设备存储空间后重试",
                    retryable = true,
                )
                return
            }

            succeeded = true
            cleanupOtherFilesInUpdatesDir(keepFileName = targetFile.name)
            diagnosticLog(
                LOG_CATEGORY,
                "download_verified",
                "INFO",
                "version=${release.versionName} bytes=$downloadedBytes",
            )
            updateStateIfCurrentRun(runId) {
                it.copy(
                    status = AppUpdateStatus.ReadyToInstall(
                        release = release,
                        apkFilePath = targetFile.absolutePath,
                    ),
                )
            }
        } catch (ce: CancellationException) {
            deletePartFileIfNoNewerRun(runId, partFile)
            if (isCurrentDownloadRun(runId)) {
                throw ce
            }
        } catch (exc: DownloadSecurityException) {
            deletePartFileIfNoNewerRun(runId, partFile)
            diagnosticLog(LOG_CATEGORY, "download_insecure_redirect", "WARN", "version=${release.versionName}")
            setDownloadError(runId = runId, release = release, message = exc.message ?: "下载地址不安全，已拦截", retryable = false)
        } catch (_: DownloadSizeLimitException) {
            deletePartFileIfNoNewerRun(runId, partFile)
            diagnosticLog(LOG_CATEGORY, "download_size_exceeded", "WARN", "version=${release.versionName}")
            setDownloadError(runId = runId, release = release, message = SIZE_LIMIT_EXCEEDED_MESSAGE, retryable = false)
        } catch (exc: HttpStatusException) {
            deletePartFileIfNoNewerRun(runId, partFile)
            diagnosticLog(
                LOG_CATEGORY,
                "download_http_error",
                "WARN",
                "version=${release.versionName} code=${exc.statusCode}",
            )
            setDownloadError(
                runId = runId,
                release = release,
                message = "安装包下载失败（HTTP ${exc.statusCode}），请重试",
                retryable = true,
            )
        } catch (exc: Throwable) {
            deletePartFileIfNoNewerRun(runId, partFile)
            if (!isCurrentDownloadRun(runId)) {
                return
            }
            diagnosticLog(
                LOG_CATEGORY,
                "download_failed",
                "WARN",
                "version=${release.versionName} err=${exc.javaClass.simpleName}",
            )
            setDownloadError(
                runId = runId,
                release = release,
                message = "安装包下载中断，请检查网络后重试",
                retryable = true,
            )
        } finally {
            if (!succeeded) {
                deletePartFileIfNoNewerRun(runId, partFile)
            }
            clearActiveDownloadHandles(runId)
        }
    }

    private fun isCurrentDownloadRun(runId: Long): Boolean = synchronized(downloadLock) {
        activeDownloadRunId == runId
    }

    private fun updateStateIfCurrentRun(
        runId: Long,
        transform: (AppUpdateUiState) -> AppUpdateUiState,
    ) {
        if (!isCurrentDownloadRun(runId)) return
        _state.update { current ->
            if (isCurrentDownloadRun(runId)) transform(current) else current
        }
    }

    private fun deletePartFileIfNoNewerRun(runId: Long, partFile: File) {
        val canDelete = synchronized(downloadLock) {
            activeDownloadRunId == runId || activeDownloadRunId == 0L
        }
        if (canDelete) {
            runCatching { if (partFile.exists()) partFile.delete() }
        }
    }

    private fun handleCheckFailure(
        manual: Boolean,
        previousStatus: AppUpdateStatus,
        message: String,
        retryable: Boolean,
    ): AppUpdateCheckResult.Error {
        if (manual) {
            _state.update {
                it.copy(
                    status = AppUpdateStatus.Error(
                        message = message,
                        retryable = retryable,
                        release = null,
                    ),
                    showDialog = false,
                )
            }
        } else {
            _state.update {
                it.copy(
                    status = fallbackRestoredStatus(previousStatus),
                    showDialog = false,
                )
            }
        }
        return AppUpdateCheckResult.Error(
            message = message,
            retryable = retryable,
        )
    }

    private fun fallbackRestoredStatus(previousStatus: AppUpdateStatus): AppUpdateStatus =
        if (previousStatus == AppUpdateStatus.Checking) AppUpdateStatus.Idle else previousStatus

    private fun emitDownloadingState(
        runId: Long,
        release: AppUpdateRelease,
        downloadedBytes: Long,
        totalBytes: Long,
    ) {
        val percent = computeProgressPercent(downloadedBytes, totalBytes)
        updateStateIfCurrentRun(runId) { current ->
            current.copy(
                status = AppUpdateStatus.Downloading(
                    release = release,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    progressPercent = percent,
                ),
            )
        }
    }

    private fun setDownloadError(
        runId: Long,
        release: AppUpdateRelease,
        message: String,
        retryable: Boolean,
    ) {
        updateStateIfCurrentRun(runId) {
            it.copy(
                status = AppUpdateStatus.Error(
                    message = message,
                    retryable = retryable,
                    release = release,
                ),
            )
        }
    }

    private fun clearActiveDownloadHandles(runId: Long) {
        synchronized(downloadLock) {
            if (activeDownloadRunId == runId) {
                activeDownloadRunId = 0L
                activeDownloadCall = null
                activePartFileName = null
                downloadJob = null
            }
        }
    }

    private fun cleanupOtherFilesInUpdatesDir(keepFileName: String) {
        runCatching {
            val dir = updatesDir
            if (!dir.isDirectory) return
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (!file.isFile) continue
                val name = file.name
                if (name == keepFileName) continue
                if (name.endsWith(APK_SUFFIX) || name.endsWith(APK_PART_SUFFIX)) {
                    file.delete()
                }
            }
        }
    }

    private fun deleteAllPartFiles(excludeFileName: String?) {
        runCatching {
            val dir = updatesDir
            if (!dir.isDirectory) return
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (!file.isFile) continue
                if (file.name == excludeFileName) continue
                if (file.name.endsWith(APK_PART_SUFFIX)) {
                    file.delete()
                }
            }
        }
    }

    private fun resolveInUpdatesDir(fileName: String): File? {
        return try {
            val canonicalRoot = updatesDir.canonicalFile
            val candidate = File(canonicalRoot, fileName).canonicalFile
            val rootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
            if (!candidate.path.startsWith(rootPrefix)) null else candidate
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveExistingInUpdatesDir(fileName: String): File? {
        val target = resolveInUpdatesDir(fileName) ?: return null
        return if (target.isFile && target.length() > 0L) target else null
    }

    private suspend fun computeFileSha256Hex(file: File, runId: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        FileInputStream(file).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!isCurrentDownloadRun(runId)) {
                    throw CancellationException("Verification cancelled by user")
                }
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    digest.update(buffer, 0, read)
                }
            }
        }
        val hash = digest.digest()
        return buildString(hash.size * 2) {
            for (byte in hash) {
                append(String.format(Locale.ROOT, "%02x", byte.toInt() and 0xFF))
            }
        }
    }

    private fun moveAtomically(src: File, dest: File): Boolean {
        if (src.renameTo(dest)) return true
        if (dest.exists()) {
            dest.delete()
            if (src.renameTo(dest)) return true
        }
        return try {
            src.inputStream().use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output, STREAM_BUFFER_BYTES)
                    output.flush()
                }
            }
            src.delete()
            true
        } catch (_: Throwable) {
            runCatching { dest.delete() }
            false
        }
    }

    private fun readBoundedBytes(input: java.io.InputStream, maxBytes: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        input.use { stream ->
            while (true) {
                val read = stream.read(buf)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                out.write(buf, 0, read)
            }
        }
        return out.toByteArray()
    }

    private class HttpStatusException(val statusCode: Int) : IOException("HTTP $statusCode")
    private class DownloadSizeLimitException : IOException("APK exceeds max allowed size")
    private class DownloadSecurityException(message: String) : IOException(message)

    companion object {
        const val APK_UPDATES_DIR_NAME: String = "apk_updates"
        const val APK_MIME_TYPE: String = "application/vnd.android.package-archive"
        const val MAX_APK_BYTES: Long = 150L * 1024L * 1024L
        const val UNCONFIGURED_MESSAGE: String =
            "当前构建未配置自动更新源，可前往 GitHub Releases 获取最新版"
        const val SHA256_MISMATCH_MESSAGE: String =
            "安装包校验失败，已拦截安装，请重试"
        const val SIZE_LIMIT_EXCEEDED_MESSAGE: String =
            "安装包体积超过安全上限（150 MB），已拦截下载"

        private const val LOG_CATEGORY: String = "UPDATE"
        private const val APK_SUFFIX: String = ".apk"
        private const val APK_PART_SUFFIX: String = ".apk.part"
        private const val STREAM_BUFFER_BYTES: Int = 16 * 1024
        private const val PROGRESS_EMIT_INTERVAL_NANOS: Long = 120_000_000L
        private const val MANIFEST_CONNECT_TIMEOUT_SEC: Long = 10L
        private const val MANIFEST_READ_TIMEOUT_SEC: Long = 15L
        private const val MANIFEST_CALL_TIMEOUT_SEC: Long = 20L
        private const val DOWNLOAD_CONNECT_TIMEOUT_SEC: Long = 15L
        private const val DOWNLOAD_READ_TIMEOUT_SEC: Long = 30L

        internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

        internal fun normalizeManifestUrl(
            raw: String?,
            allowCleartext: Boolean = false,
        ): String? = AppUpdateManifestParser.validateDownloadUrl(
            raw = raw?.trim().orEmpty(),
            allowCleartext = allowCleartext,
        )

        internal fun sanitizeVersionForFileName(versionName: String): String {
            val cleaned = buildString(versionName.length.coerceAtMost(48)) {
                for (ch in versionName.trim()) {
                    if (length >= 48) break
                    if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '_') {
                        append(ch)
                    } else {
                        append('_')
                    }
                }
            }.trim('.', '_', '-')
            return cleaned.ifEmpty { "latest" }
        }

        internal fun computeProgressPercent(downloadedBytes: Long, totalBytes: Long): Int? {
            if (totalBytes <= 0L) return null
            val raw = ((downloadedBytes * 100L) / totalBytes).toInt()
            return raw.coerceIn(0, 100)
        }

        fun createUnknownSourcesSettingsIntent(context: Context): Intent {
            val packageUri = Uri.parse("package:${context.packageName}")
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }

        internal fun defaultCanRequestPackageInstalls(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
            } else {
                true
            }
        }

        internal fun defaultLaunchApkInstaller(context: Context, apkFile: File) {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
