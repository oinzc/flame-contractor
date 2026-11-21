package io.github.oinzc.flamecontractor.execution

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import io.github.oinzc.flamecontractor.core.identity.ConversationMatch
import io.github.oinzc.flamecontractor.core.identity.ConversationRecord
import io.github.oinzc.flamecontractor.core.identity.DouyinProtobufParser
import io.github.oinzc.flamecontractor.core.identity.IdentityDisambiguator
import io.github.oinzc.flamecontractor.core.identity.IdentityMatch
import io.github.oinzc.flamecontractor.core.identity.IdentityRecord
import io.github.oinzc.flamecontractor.core.identity.MatchStatus
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundle
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundleStore
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.FailureClass
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.domain.AuthProbeOutcome
import io.github.oinzc.flamecontractor.execution.webview.DesktopWebViewFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class AuthCheckResult(
    val outcome: AuthProbeOutcome,
    val selfUid: String? = null,
    val douyinNickname: String? = null,
    val webViewVersion: String = "",
    val skeletonMissingDespiteSession: Boolean = false,
    val detail: String? = null,
)

sealed class ScanExecutionResult {
    data class Success(
        val snapshot: ScanReconciliationSnapshot,
        val selfUid: String? = null,
        val douyinNickname: String? = null,
    ) : ScanExecutionResult()

    data class AuthOrGateFailure(
        val outcome: AuthProbeOutcome,
        val detail: String,
    ) : ScanExecutionResult()

    data class Error(
        val failureClass: FailureClass,
        val detail: String,
    ) : ScanExecutionResult()
}

data class SendExecutionResult(
    val jobStatus: JobStatus,
    val failureClass: FailureClass? = null,
    val coreSkeletonBroken: Boolean = false,
    val inputMethodUsed: String? = null,
    val summaryDetail: String? = null,
    val observedPlatformUserId: String? = null,
    val observedPlatformHandle: String? = null,
    val observedPlatformConversationId: String? = null,
    val observedConversationType: String? = null,
)

data class IdentityRepairResult(
    val repaired: Boolean,
    val ambiguous: Boolean = false,
    val platformUserId: String? = null,
    val platformHandle: String? = null,
    val platformConversationId: String? = null,
    val conversationType: String? = null,
    val detail: String,
)

/**
 * 桌面模式 WebView 完整执行引擎：
 * - 负责只读全量扫描、网页结构体检、身份补采与安全可控的文本/表情发送确认；
 * - 全程在内存维护网络旁路与 IndexedDB 身份记录，绝不落盘或打印任何真实昵称/UID/Cookie；
 * - 调用 `withOffscreenSession` 或单次方法结束后在 `finally` 冲刷 Cookie 并销毁自建离屏 WebView。
 */
class DouyinWebExecutor(
    context: Context,
    private val selectorBundleStore: SelectorBundleStore =
        SelectorBundleStore(context.applicationContext ?: context),
) {
    private val appContext: Context = context.applicationContext ?: context

    class SessionHandle internal constructor(
        internal val webView: WebView,
        internal val identityStore: IdentityStore,
        internal val bridge: FlameBridge,
        internal val mainFrameError: AtomicReference<Pair<Int, String>?>,
        internal val httpError: AtomicReference<Pair<Int, String>?>,
        internal val documentStartSupported: Boolean,
        internal val webViewVersion: String,
        internal val pageFinished: AtomicBoolean = AtomicBoolean(false),
    ) {
        internal var warmedUp: Boolean = false
        internal var cachedIdentitiesLoaded: Boolean = false
        internal var fallbackHookInjected: Boolean = false
    }

    internal class IdentityStore {
        private val lock = Any()
        private val usersById = LinkedHashMap<String, IdentityRecord>()
        private val groupsById = LinkedHashMap<String, ConversationRecord>()

        fun clear() = synchronized(lock) {
            usersById.clear()
            groupsById.clear()
        }

        fun ingestIdentityJson(rawJson: String) {
            val list = ExecJson.parseOrNull(rawJson) as? List<*> ?: return
            val incoming = ArrayList<IdentityRecord>(list.size)
            for (item in list) {
                @Suppress("UNCHECKED_CAST")
                val obj = item as? Map<String, Any?> ?: continue
                val secUid = firstNonEmpty(
                    obj.stringOrNull("sec_uid"),
                    obj.stringOrNull("sec_user_id"),
                    obj.stringOrNull("secUid"),
                    obj.stringOrNull("secUserId"),
                )
                val uid = firstNonEmpty(
                    obj.stringOrNull("uid"),
                    obj.stringOrNull("user_id"),
                    obj.stringOrNull("userId"),
                )
                val platformUserId = secUid ?: uid ?: continue
                val uniqueId = obj.stringOrNull("unique_id") ?: obj.stringOrNull("uniqueId")
                val shortId = obj.stringOrNull("short_id") ?: obj.stringOrNull("shortId")
                val handle = uniqueId ?: shortId
                val handleSource = when {
                    uniqueId != null -> "unique_id"
                    shortId != null -> "short_id"
                    else -> null
                }
                val nickname = obj.stringOrNull("nickname") ?: obj.stringOrNull("nickName")
                val remarkName = obj.stringOrNull("remark_name") ?: obj.stringOrNull("remarkName")
                incoming.add(
                    IdentityRecord(
                        platformUserId = platformUserId,
                        platformHandle = handle,
                        platformHandleSource = handleSource,
                        nickname = nickname,
                        remarkName = remarkName,
                    ),
                )
            }
            if (incoming.isEmpty()) return
            synchronized(lock) {
                for (record in incoming) {
                    val prev = usersById[record.platformUserId]
                    usersById[record.platformUserId] = if (prev == null) {
                        record
                    } else {
                        IdentityRecord(
                            platformUserId = record.platformUserId,
                            platformHandle = record.platformHandle ?: prev.platformHandle,
                            platformHandleSource = record.platformHandleSource ?: prev.platformHandleSource,
                            nickname = record.nickname ?: prev.nickname,
                            remarkName = record.remarkName ?: prev.remarkName,
                        )
                    }
                }
            }
        }

        fun ingestProtoBase64(base64Str: String) {
            if (base64Str.isBlank()) return
            val bytes = try {
                Base64.decode(base64Str, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                return
            }
            if (bytes.isEmpty()) return
            val parsed = DouyinProtobufParser.parseGroupConversations(bytes)
            if (parsed.isEmpty()) return
            synchronized(lock) {
                for (conv in parsed) {
                    groupsById[conv.platformConversationId] = conv
                }
            }
        }

        fun matchUser(label: String): IdentityMatch {
            val snapshot = synchronized(lock) { usersById.values.toList() }
            return IdentityDisambiguator.matchUser(label, snapshot)
        }

        fun matchGroup(label: String): ConversationMatch {
            val snapshot = synchronized(lock) { groupsById.values.toList() }
            return IdentityDisambiguator.matchGroup(label, snapshot)
        }

        fun userIdsForLabel(label: String): Set<String> {
            val normalized = IdentityDisambiguator.normalizeLabel(label)
            if (normalized.isEmpty()) return emptySet()
            val snapshot = synchronized(lock) { usersById.values.toList() }
            val ids = LinkedHashSet<String>()
            for (record in snapshot) {
                val labels = arrayOf(record.remarkName, record.nickname, record.platformHandle)
                if (labels.any { it != null && IdentityDisambiguator.normalizeLabel(it) == normalized }) {
                    ids.add(record.platformUserId)
                }
            }
            return ids
        }

        fun groupIdsForLabel(label: String): Set<String> {
            val normalized = IdentityDisambiguator.normalizeLabel(label)
            if (normalized.isEmpty()) return emptySet()
            val snapshot = synchronized(lock) { groupsById.values.toList() }
            val ids = LinkedHashSet<String>()
            for (record in snapshot) {
                if (IdentityDisambiguator.normalizeLabel(record.title) == normalized) {
                    ids.add(record.platformConversationId)
                }
            }
            return ids
        }

        private fun firstNonEmpty(vararg values: String?): String? =
            values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    internal class FlameBridge(
        private val identityStore: IdentityStore,
        private val scope: CoroutineScope,
    ) {
        private val pendingAsyncCalls = ConcurrentHashMap<String, CompletableDeferred<String>>()
        private val pendingJobs = ConcurrentHashMap.newKeySet<Job>()

        @JavascriptInterface
        fun onIdentityRecords(json: String) {
            val job = scope.launch(Dispatchers.Default) {
                try {
                    identityStore.ingestIdentityJson(json)
                } catch (_: Throwable) {
                }
            }
            trackJob(job)
        }

        @JavascriptInterface
        fun onInitProtoBase64(base64: String) {
            val job = scope.launch(Dispatchers.Default) {
                try {
                    identityStore.ingestProtoBase64(base64)
                } catch (_: Throwable) {
                }
            }
            trackJob(job)
        }

        @JavascriptInterface
        fun onAsyncResult(callId: String, json: String) {
            pendingAsyncCalls.remove(callId)?.complete(json)
        }

        fun registerAsyncCall(callId: String): CompletableDeferred<String> {
            val deferred = CompletableDeferred<String>()
            pendingAsyncCalls[callId] = deferred
            return deferred
        }

        fun cancelAsyncCall(callId: String) {
            pendingAsyncCalls.remove(callId)?.cancel()
        }

        suspend fun awaitPendingJobs(timeoutMs: Long = 2_000L) {
            val snapshot = pendingJobs.toList()
            if (snapshot.isEmpty()) return
            withTimeoutOrNull(timeoutMs) {
                for (job in snapshot) {
                    if (job.isActive) job.join()
                }
            }
        }

        fun clearAll() {
            for ((_, deferred) in pendingAsyncCalls) {
                deferred.cancel()
            }
            pendingAsyncCalls.clear()
        }

        private fun trackJob(job: Job) {
            pendingJobs.add(job)
            job.invokeOnCompletion { pendingJobs.remove(job) }
        }
    }

    suspend fun <T> withOffscreenSession(block: suspend (SessionHandle) -> T): T {
        val bundle = selectorBundleStore.current()
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val identityStore = IdentityStore()
        val bridge = FlameBridge(identityStore, bridgeScope)
        val mainFrameError = AtomicReference<Pair<Int, String>?>(null)
        val httpError = AtomicReference<Pair<Int, String>?>(null)
        val pageFinished = AtomicBoolean(false)
        val webViewRef = AtomicReference<WebView?>(null)

        return try {
            val session = withContext(Dispatchers.Main) {
                val docStartJs = DouyinJsScripts.buildIdentityCaptureDocumentStartJs()
                val (createdWebView, config) = DesktopWebViewFactory.createOffscreenWebView(
                    context = appContext,
                    widthPx = 1440,
                    heightPx = 900,
                    onPageFinished = { originPath ->
                        if (originPath.startsWith("http://") || originPath.startsWith("https://")) {
                            pageFinished.set(true)
                        }
                    },
                    onMainFrameError = { code, desc ->
                        mainFrameError.compareAndSet(null, code to desc)
                    },
                    documentStartScript = docStartJs,
                    onReceivedHttpError = { code, _ ->
                        httpError.compareAndSet(null, code to "http_error")
                    },
                )
                createdWebView.addJavascriptInterface(bridge, DouyinJsScripts.DEFAULT_BRIDGE_NAME)
                webViewRef.set(createdWebView)
                val webViewPkgVersion = WebViewCompat.getCurrentWebViewPackage(appContext)?.versionName.orEmpty()
                createdWebView.loadUrl(bundle.chatUrl)
                SessionHandle(
                    webView = createdWebView,
                    identityStore = identityStore,
                    bridge = bridge,
                    mainFrameError = mainFrameError,
                    httpError = httpError,
                    documentStartSupported = config.documentStartSupported,
                    webViewVersion = webViewPkgVersion,
                    pageFinished = pageFinished,
                )
            }
            block(session)
        } finally {
            withContext(NonCancellable) {
                bridge.clearAll()
                bridgeScope.coroutineContext[Job]?.cancel()
                withContext(Dispatchers.Main) {
                    try {
                        CookieManager.getInstance().flush()
                    } catch (_: Throwable) {
                    }
                    webViewRef.get()?.let { wv ->
                        try {
                            wv.stopLoading()
                            wv.removeJavascriptInterface(DouyinJsScripts.DEFAULT_BRIDGE_NAME)
                            wv.destroy()
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
    }

    suspend fun checkAuthAndIdentity(
        session: SessionHandle? = null,
        timeoutMs: Long = AUTH_WAIT_TIMEOUT_MS,
    ): AuthCheckResult {
        if (session != null) {
            return checkAuthInSession(session, timeoutMs)
        }
        return withOffscreenSession { created ->
            checkAuthInSession(created, timeoutMs)
        }
    }

    suspend fun scanAllChats(
        existingTargets: List<ContractTargetEntity> = emptyList(),
        scanRunId: String = UUID.randomUUID().toString(),
        onProgress: (suspend (discoveredCount: Int) -> Unit)? = null,
        session: SessionHandle? = null,
    ): ScanExecutionResult {
        if (session == null) {
            return withOffscreenSession { created ->
                scanAllChatsInSession(created, existingTargets, scanRunId, onProgress)
            }
        }
        return scanAllChatsInSession(session, existingTargets, scanRunId, onProgress)
    }

    suspend fun probeStructure(
        sampleTarget: ContractTargetEntity? = null,
        session: SessionHandle? = null,
        existingWebView: WebView? = null,
    ): StructureProbeReport {
        if (session != null) {
            return probeStructureInSession(session, sampleTarget)
        }
        if (existingWebView != null) {
            val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val identityStore = IdentityStore()
            val bridge = FlameBridge(identityStore, bridgeScope)
            val tempSession = SessionHandle(
                webView = existingWebView,
                identityStore = identityStore,
                bridge = bridge,
                mainFrameError = AtomicReference(null),
                httpError = AtomicReference(null),
                documentStartSupported = true,
                webViewVersion = WebViewCompat.getCurrentWebViewPackage(appContext)?.versionName.orEmpty(),
            )
            return try {
                probeStructureInSession(tempSession, sampleTarget)
            } finally {
                bridge.clearAll()
                bridgeScope.coroutineContext[Job]?.cancel()
            }
        }
        return withOffscreenSession { created ->
            probeStructureInSession(created, sampleTarget)
        }
    }

    suspend fun sendText(
        target: ContractTargetEntity,
        text: String,
        isVisibleActivity: Boolean = false,
        session: SessionHandle? = null,
        onStageSending: (suspend () -> Unit)? = null,
        onStageConfirming: (suspend () -> Unit)? = null,
    ): SendExecutionResult {
        if (session == null) {
            return withOffscreenSession { created ->
                val auth = checkAuthInSession(created, AUTH_WAIT_TIMEOUT_MS)
                if (auth.outcome != AuthProbeOutcome.AUTHENTICATED) {
                    return@withOffscreenSession SendExecutionResult(
                        jobStatus = JobStatus.FAILED_RETRYABLE,
                        failureClass = authOutcomeToFailureClass(auth.outcome),
                        coreSkeletonBroken = auth.skeletonMissingDespiteSession,
                        summaryDetail = auth.detail ?: "登录态或网络检查未通过",
                    )
                }
                sendTextInSession(
                    session = created,
                    target = target,
                    text = text,
                    isVisibleActivity = isVisibleActivity,
                    onStageSending = onStageSending,
                    onStageConfirming = onStageConfirming,
                )
            }
        }
        return sendTextInSession(
            session = session,
            target = target,
            text = text,
            isVisibleActivity = isVisibleActivity,
            onStageSending = onStageSending,
            onStageConfirming = onStageConfirming,
        )
    }

    suspend fun sendSticker(
        target: ContractTargetEntity,
        stickerName: String,
        stickerLabel: String? = null,
        stickerCategory: String? = null,
        fallbackIndex: Int? = null,
        session: SessionHandle? = null,
        onStageSending: (suspend () -> Unit)? = null,
        onStageConfirming: (suspend () -> Unit)? = null,
    ): SendExecutionResult {
        if (session == null) {
            return withOffscreenSession { created ->
                val auth = checkAuthInSession(created, AUTH_WAIT_TIMEOUT_MS)
                if (auth.outcome != AuthProbeOutcome.AUTHENTICATED) {
                    return@withOffscreenSession SendExecutionResult(
                        jobStatus = JobStatus.FAILED_RETRYABLE,
                        failureClass = authOutcomeToFailureClass(auth.outcome),
                        coreSkeletonBroken = auth.skeletonMissingDespiteSession,
                        summaryDetail = auth.detail ?: "登录态或网络检查未通过",
                    )
                }
                sendStickerInSession(
                    session = created,
                    target = target,
                    stickerName = stickerName,
                    stickerLabel = stickerLabel,
                    stickerCategory = stickerCategory,
                    fallbackIndex = fallbackIndex,
                    onStageSending = onStageSending,
                    onStageConfirming = onStageConfirming,
                )
            }
        }
        return sendStickerInSession(
            session = session,
            target = target,
            stickerName = stickerName,
            stickerLabel = stickerLabel,
            stickerCategory = stickerCategory,
            fallbackIndex = fallbackIndex,
            onStageSending = onStageSending,
            onStageConfirming = onStageConfirming,
        )
    }

    internal suspend fun repairTargetIdentityInSession(
        session: SessionHandle,
        target: ContractTargetEntity,
    ): IdentityRepairResult {
        val bundle = selectorBundleStore.current()
        val opened = openConversation(
            session = session,
            bundle = bundle,
            targetName = target.nicknameSnapshot,
            expectedUserId = target.platformUserId,
            expectedConversationId = target.platformConversationId,
        )
        if (!opened.opened) {
            return IdentityRepairResult(
                repaired = false,
                detail = "会话列表中未找到该好友，未补采到稳定身份",
            )
        }
        if (opened.ambiguous) {
            return IdentityRepairResult(
                repaired = false,
                ambiguous = true,
                detail = "同名对应多个平台会话或账号，已阻止自动绑定",
            )
        }
        if (!opened.exact) {
            return IdentityRepairResult(
                repaired = false,
                detail = "右侧会话标题与目标名不完全一致，已阻止自动绑定",
            )
        }
        val uid = opened.observedPlatformUserId
        val cid = opened.observedPlatformConversationId
        if (uid == null && cid == null) {
            return IdentityRepairResult(
                repaired = false,
                detail = "已打开会话，但本次未观察到唯一平台 ID",
            )
        }
        return IdentityRepairResult(
            repaired = true,
            platformUserId = uid,
            platformHandle = opened.observedPlatformHandle,
            platformConversationId = cid,
            conversationType = opened.observedConversationType ?: if (cid != null) "group" else "friend",
            detail = "已安全补采并核验唯一平台身份",
        )
    }

    internal suspend fun sendTextInSession(
        session: SessionHandle,
        target: ContractTargetEntity,
        text: String,
        isVisibleActivity: Boolean = false,
        onStageSending: (suspend () -> Unit)? = null,
        onStageConfirming: (suspend () -> Unit)? = null,
    ): SendExecutionResult {
        val bundle = selectorBundleStore.current()
        val opened = openConversation(
            session = session,
            bundle = bundle,
            targetName = target.nicknameSnapshot,
            expectedUserId = target.platformUserId,
            expectedConversationId = target.platformConversationId,
        )
        if (!opened.opened || !opened.exact) {
            val detail = when {
                !opened.opened -> "未能在会话列表中定位目标会话"
                opened.ambiguous -> "同名存在多个候选账号或群聊，已拒绝发送"
                else -> "右侧会话标题或稳定身份与目标不一致，已拒绝发送"
            }
            return SendExecutionResult(
                jobStatus = JobStatus.FAILED_TERMINAL,
                failureClass = FailureClass.TARGET_UNCERTAIN,
                summaryDetail = detail,
            )
        }

        onStageSending?.invoke()

        val editorReady = waitForEditorReady(session, bundle, EDITOR_READY_TIMEOUT_MS)
        if (!editorReady) {
            return SendExecutionResult(
                jobStatus = JobStatus.FAILED_TERMINAL,
                failureClass = FailureClass.DOM_CHANGED,
                coreSkeletonBroken = true,
                summaryDetail = "核心页面结构缺失：未找到可见聊天输入框",
            )
        }

        val emptyCheck = evalObject(session.webView, DouyinJsScripts.buildCheckEditorEmptyJs(bundle))
        if (emptyCheck == null || !emptyCheck.boolOrDefault("editorVisible", false)) {
            return SendExecutionResult(
                jobStatus = JobStatus.FAILED_RETRYABLE,
                failureClass = FailureClass.OTHER,
                summaryDetail = "输入框未就绪，已跳过本次发送",
            )
        }
        if (!emptyCheck.boolOrDefault("empty", false)) {
            return SendExecutionResult(
                jobStatus = JobStatus.FAILED_RETRYABLE,
                failureClass = FailureClass.OTHER,
                summaryDetail = "输入框存在残留草稿，已中止发送防误拼接",
            )
        }

        var inputMethod = "js_execCommand"
        val insertRes = evalObject(
            session.webView,
            DouyinJsScripts.buildInsertTextExecCommandJs(bundle, text),
        )
        val insertOk = insertRes?.boolOrDefault("ok", false) == true
        delay(220L)

        var verifyRes = evalObject(
            session.webView,
            DouyinJsScripts.buildVerifyEditorTextAndSendReadyJs(bundle, text),
        )
        var textMatched = insertOk && verifyRes?.boolOrDefault("textMatched", false) == true

        if (!textMatched && isVisibleActivity) {
            val committed = tryAndroidImeCommit(session.webView, text)
            if (committed) {
                inputMethod = "android_ime"
                delay(220L)
                verifyRes = evalObject(
                    session.webView,
                    DouyinJsScripts.buildVerifyEditorTextAndSendReadyJs(bundle, text),
                )
                textMatched = verifyRes?.boolOrDefault("textMatched", false) == true
            }
        }

        if (!textMatched) {
            return SendExecutionResult(
                jobStatus = JobStatus.FAILED_RETRYABLE,
                failureClass = FailureClass.OTHER,
                inputMethodUsed = inputMethod,
                summaryDetail = "写入后去零宽字符强校验未通过，尚未触发发送",
                observedPlatformUserId = opened.observedPlatformUserId,
                observedPlatformHandle = opened.observedPlatformHandle,
                observedPlatformConversationId = opened.observedPlatformConversationId,
                observedConversationType = opened.observedConversationType,
            )
        }

        if (verifyRes?.boolOrDefault("sendButtonReady", false) != true) {
            return SendExecutionResult(
                jobStatus = JobStatus.FAILED_RETRYABLE,
                failureClass = FailureClass.DOM_CHANGED,
                coreSkeletonBroken = true,
                inputMethodUsed = inputMethod,
                summaryDetail = "发送按钮未呈现可用状态，尚未触发发送",
            )
        }

        waitForMessageListSettled(
            session = session,
            bundle = bundle,
            minWaitMs = 500L,
            maxWaitMs = 1_500L,
        )

        val normalizedKey = DouyinJsScripts.stripBlankAndZeroWidth(text)
        val beforeConfirmSnapshot = evalObject(
            session.webView,
            DouyinJsScripts.buildSampleTextConfirmJs(bundle, normalizedKey),
        )
        val beforeOutgoingMultiset = SendConfirmer.toMultiset(
            beforeConfirmSnapshot?.stringListOrEmpty("outgoingSignatures").orEmpty(),
        )

        // 先将状态落为 CONFIRMING 再点击不可逆发送按钮，防止进程强杀后漏判为可重试
        onStageConfirming?.invoke()

        val clickRes = evalObject(session.webView, DouyinJsScripts.buildClickSendButtonJs(bundle))
        var lastOutgoingMatched = false
        val outcome = SendConfirmer.confirmTextAfterClick(
            clickResult = clickRes,
            timeoutMs = SendConfirmer.DEFAULT_CONFIRM_TIMEOUT_MS,
            pollIntervalMs = SendConfirmer.DEFAULT_POLL_INTERVAL_MS,
            clockMs = { SystemClock.elapsedRealtime() },
        ) {
            val pollObj = evalObject(
                session.webView,
                DouyinJsScripts.buildSampleTextConfirmJs(bundle, normalizedKey),
            )
            val failureHit = pollObj?.boolOrDefault("failureMarkerHit", false) == true
            val afterMultiset = SendConfirmer.toMultiset(
                pollObj?.stringListOrEmpty("outgoingSignatures").orEmpty(),
            )
            val outgoingMatched = SendConfirmer.diffMediaMultiset(beforeOutgoingMultiset, afterMultiset) > 0
            if (outgoingMatched) lastOutgoingMatched = true
            val editorFound = pollObj?.boolOrDefault("editorFound", false) == true
            val editorEmpty = editorFound && (pollObj?.boolOrDefault("editorEmpty", false) == true)
            TextPollSample(
                failureMarkerHit = failureHit,
                outgoingTextMatched = outgoingMatched,
                editorEmpty = editorEmpty,
            )
        }
        return when (outcome) {
            ConfirmOutcome.SUCCEEDED -> SendExecutionResult(
                jobStatus = JobStatus.SUCCEEDED,
                inputMethodUsed = inputMethod,
                summaryDetail = if (lastOutgoingMatched) {
                    "已确认发送成功（outgoing 气泡已渲染）"
                } else {
                    "已确认发送成功（输入框连续 3 次清空且无失败标记）"
                },
                observedPlatformUserId = opened.observedPlatformUserId,
                observedPlatformHandle = opened.observedPlatformHandle,
                observedPlatformConversationId = opened.observedPlatformConversationId,
                observedConversationType = opened.observedConversationType,
            )
            ConfirmOutcome.RETRYABLE_FAILURE -> SendExecutionResult(
                jobStatus = JobStatus.FAILED_RETRYABLE,
                failureClass = FailureClass.OTHER,
                inputMethodUsed = inputMethod,
                summaryDetail = if (SendConfirmer.classifyIrreversibleClickResult(clickRes) == ClickDispatchOutcome.ABORT_RETRYABLE) {
                    "未能触发发送按钮点击"
                } else {
                    "页面出现明确发送失败提示，可按退避规则重试"
                },
            )
            ConfirmOutcome.UNKNOWN_REQUIRES_REVIEW,
            ConfirmOutcome.CONFIRMING_PENDING,
            -> SendExecutionResult(
                jobStatus = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                failureClass = FailureClass.UNKNOWN_OUTCOME,
                inputMethodUsed = inputMethod,
                summaryDetail = "已触发发送但确认超时，已阻止自动重发，请人工核对",
                observedPlatformUserId = opened.observedPlatformUserId,
                observedPlatformHandle = opened.observedPlatformHandle,
                observedPlatformConversationId = opened.observedPlatformConversationId,
                observedConversationType = opened.observedConversationType,
            )
        }
    }

    internal suspend fun sendStickerInSession(
        session: SessionHandle,
        target: ContractTargetEntity,
        stickerName: String,
        stickerLabel: String? = null,
        stickerCategory: String? = null,
        fallbackIndex: Int? = null,
        onStageSending: (suspend () -> Unit)? = null,
        onStageConfirming: (suspend () -> Unit)? = null,
    ): SendExecutionResult {
        val bundle = selectorBundleStore.current()
        val opened = openConversation(
            session = session,
            bundle = bundle,
            targetName = target.nicknameSnapshot,
            expectedUserId = target.platformUserId,
            expectedConversationId = target.platformConversationId,
        )
        if (!opened.opened || !opened.exact) {
            val detail = when {
                !opened.opened -> "未能在会话列表中定位目标会话"
                opened.ambiguous -> "同名存在多个候选账号或群聊，已拒绝发送"
                else -> "右侧会话标题或稳定身份与目标不一致，已拒绝发送"
            }
            return SendExecutionResult(
                jobStatus = JobStatus.FAILED_TERMINAL,
                failureClass = FailureClass.TARGET_UNCERTAIN,
                summaryDetail = detail,
            )
        }

        onStageSending?.invoke()

        if (!waitForEditorReady(session, bundle, EDITOR_READY_TIMEOUT_MS)) {
            return SendExecutionResult(
                jobStatus = JobStatus.FAILED_TERMINAL,
                failureClass = FailureClass.DOM_CHANGED,
                coreSkeletonBroken = true,
                summaryDetail = "核心页面结构缺失：未找到可见聊天输入框",
            )
        }

        // 先等当前会话的历史消息和图片完成初步水合，避免与发送动作并发渲染造成基线失真
        waitForMessageListSettled(session, bundle)

        return try {
            val alreadyOpenRes = evalObject(
                session.webView,
                DouyinJsScripts.buildCheckStickerPanelReadyJs(bundle),
            )
            val alreadyReady = alreadyOpenRes?.boolOrDefault("panelFound", false) == true &&
                (alreadyOpenRes.intOrNull("itemCount") ?: 0) > 0
            if (!alreadyReady) {
                val openBtnRes = evalObject(
                    session.webView,
                    DouyinJsScripts.buildOpenStickerPanelJs(bundle),
                )
                if (openBtnRes?.boolOrDefault("clicked", false) != true) {
                    return SendExecutionResult(
                        jobStatus = JobStatus.FAILED_RETRYABLE,
                        failureClass = FailureClass.OTHER,
                        inputMethodUsed = "sticker_click",
                        summaryDetail = "未找到表情面板触发按钮",
                    )
                }

                val panelReady = waitForStickerPanelItems(session, bundle, STICKER_PANEL_TIMEOUT_MS)
                if (!panelReady) {
                    return SendExecutionResult(
                        jobStatus = JobStatus.FAILED_RETRYABLE,
                        failureClass = FailureClass.OTHER,
                        inputMethodUsed = "sticker_click",
                        summaryDetail = "表情面板打开后未渲染出可用表情项",
                    )
                }
            }

            // 必须在表情面板就绪后、点击表情前一刻采样发送前媒体基线，防止历史气泡延迟渲染误判成功
            val beforeMediaObj = evalObject(
                session.webView,
                DouyinJsScripts.buildSampleOutgoingMediaMultisetJs(bundle),
            )
            val beforeMultiset = SendConfirmer.toMultiset(
                beforeMediaObj?.stringListOrEmpty("signatures").orEmpty(),
            )

            onStageConfirming?.invoke()

            val clickStickerRes = evalObject(
                session.webView,
                DouyinJsScripts.buildClickStickerJs(
                    bundle = bundle,
                    stickerName = stickerName,
                    stickerLabel = stickerLabel,
                    stickerCategory = stickerCategory,
                    fallbackIndex = fallbackIndex,
                ),
            )
            if (SendConfirmer.classifyIrreversibleClickResult(clickStickerRes) ==
                ClickDispatchOutcome.ABORT_RETRYABLE
            ) {
                return SendExecutionResult(
                    jobStatus = JobStatus.FAILED_RETRYABLE,
                    failureClass = FailureClass.OTHER,
                    inputMethodUsed = "sticker_click",
                    summaryDetail = "表情面板中未匹配到目标表情",
                )
            }
            val clickMethod = clickStickerRes?.stringOrNull("method") ?: "unknown"
            val targetTag = clickStickerRes?.stringOrNull("targetTag").orEmpty().ifBlank { "?" }

            val firstOutcome = SendConfirmer.confirmStickerAfterClick(
                clickResult = clickStickerRes,
                beforeMultiset = beforeMultiset,
                postClickSettleMs = STICKER_POST_CLICK_SETTLE_MS,
                timeoutMs = SendConfirmer.DEFAULT_CONFIRM_TIMEOUT_MS,
                pollIntervalMs = SendConfirmer.DEFAULT_POLL_INTERVAL_MS,
                clockMs = { SystemClock.elapsedRealtime() },
            ) {
                sampleStickerPoll(session, bundle)
            }
            if (firstOutcome == ConfirmOutcome.SUCCEEDED) {
                return SendExecutionResult(
                    jobStatus = JobStatus.SUCCEEDED,
                    inputMethodUsed = "sticker_click",
                    summaryDetail = "表情发送已通过新增非头像媒体签名确认",
                    observedPlatformUserId = opened.observedPlatformUserId,
                    observedPlatformHandle = opened.observedPlatformHandle,
                    observedPlatformConversationId = opened.observedPlatformConversationId,
                    observedConversationType = opened.observedConversationType,
                )
            }
            if (firstOutcome == ConfirmOutcome.RETRYABLE_FAILURE) {
                return SendExecutionResult(
                    jobStatus = JobStatus.FAILED_RETRYABLE,
                    failureClass = FailureClass.OTHER,
                    inputMethodUsed = "sticker_click",
                    summaryDetail = "页面出现明确发送失败提示，可按退避规则重试",
                )
            }

            // 未一点即发时，仅在发布按钮明确可用（或求值结果未知）时补点一次发布按钮并再次轮询 15s
            val sendBtnRes = evalObject(session.webView, DouyinJsScripts.buildClickSendButtonJs(bundle))
            if (SendConfirmer.classifyIrreversibleClickResult(sendBtnRes) ==
                ClickDispatchOutcome.PROCEED_TO_CONFIRM
            ) {
                val secondOutcome = SendConfirmer.confirmStickerAfterClick(
                    clickResult = sendBtnRes,
                    beforeMultiset = beforeMultiset,
                    postClickSettleMs = 0L,
                    timeoutMs = SendConfirmer.DEFAULT_CONFIRM_TIMEOUT_MS,
                    pollIntervalMs = SendConfirmer.DEFAULT_POLL_INTERVAL_MS,
                    clockMs = { SystemClock.elapsedRealtime() },
                ) {
                    sampleStickerPoll(session, bundle)
                }
                if (secondOutcome == ConfirmOutcome.SUCCEEDED) {
                    return SendExecutionResult(
                        jobStatus = JobStatus.SUCCEEDED,
                        inputMethodUsed = "sticker_click",
                        summaryDetail = "表情通过发布按钮补发后已确认成功",
                        observedPlatformUserId = opened.observedPlatformUserId,
                        observedPlatformHandle = opened.observedPlatformHandle,
                        observedPlatformConversationId = opened.observedPlatformConversationId,
                        observedConversationType = opened.observedConversationType,
                    )
                }
                if (secondOutcome == ConfirmOutcome.RETRYABLE_FAILURE) {
                    return SendExecutionResult(
                        jobStatus = JobStatus.FAILED_RETRYABLE,
                        failureClass = FailureClass.OTHER,
                        inputMethodUsed = "sticker_click",
                        summaryDetail = "表情发送触发失败重试标记",
                    )
                }
            }

            SendExecutionResult(
                jobStatus = JobStatus.UNKNOWN_REQUIRES_REVIEW,
                failureClass = FailureClass.UNKNOWN_OUTCOME,
                inputMethodUsed = "sticker_click",
                summaryDetail = "已点击目标表情（$clickMethod/$targetTag，发送前媒体数 ${beforeMultiset.values.sum()}）但未检测到新增媒体气泡，已阻止重复点击",
                observedPlatformUserId = opened.observedPlatformUserId,
                observedPlatformHandle = opened.observedPlatformHandle,
                observedPlatformConversationId = opened.observedPlatformConversationId,
                observedConversationType = opened.observedConversationType,
            )
        } finally {
            withContext(NonCancellable) {
                evalObject(session.webView, DouyinJsScripts.buildResetInputFocusJs(bundle))
            }
        }
    }

    private suspend fun sampleStickerPoll(
        session: SessionHandle,
        bundle: SelectorBundle,
    ): StickerPollSample {
        val sampleObj = evalObject(
            session.webView,
            DouyinJsScripts.buildSampleOutgoingMediaMultisetJs(bundle),
        )
        val failureHit = sampleObj?.boolOrDefault("failureMarkerHit", false) == true
        val afterMultiset = SendConfirmer.toMultiset(
            sampleObj?.stringListOrEmpty("signatures").orEmpty(),
        )
        return StickerPollSample(
            failureMarkerHit = failureHit,
            afterMultiset = afterMultiset,
        )
    }

    private suspend fun checkAuthInSession(
        session: SessionHandle,
        timeoutMs: Long,
    ): AuthCheckResult {
        val bundle = selectorBundleStore.current()
        val startMs = SystemClock.elapsedRealtime()
        val deadline = startMs + timeoutMs
        var reloadedSkeletonOnce = false
        var consecutiveVerificationSignals = 0
        var consecutiveLoginSignals = 0

        while (true) {
            val probe = evalObject(
                session.webView,
                DouyinJsScripts.buildAuthAndSelfIdentityProbeJs(bundle),
            )
            val mainErr = session.mainFrameError.get()
            val httpErr = session.httpError.get()
            val nowMs = SystemClock.elapsedRealtime()
            val elapsed = nowMs - startMs

            if (probe != null) {
                val isDouyinHost = probe.boolOrDefault("isDouyinHost", false)
                if (!session.documentStartSupported && isDouyinHost && !session.fallbackHookInjected) {
                    session.fallbackHookInjected = true
                    evalJsRaw(session.webView, DouyinJsScripts.buildIdentityCaptureDocumentStartJs())
                }
                consecutiveVerificationSignals = if (probe.boolOrDefault("verificationRequired", false)) {
                    consecutiveVerificationSignals + 1
                } else {
                    0
                }
                consecutiveLoginSignals = if (probe.boolOrDefault("hasQrOrLoginSignal", false)) {
                    consecutiveLoginSignals + 1
                } else {
                    0
                }
            } else {
                consecutiveVerificationSignals = 0
                consecutiveLoginSignals = 0
            }

            when (
                evaluateAuthPollDecision(
                    probe = probe,
                    mainErr = mainErr,
                    pageFinished = session.pageFinished.get(),
                    elapsedMs = elapsed,
                    consecutiveVerificationSignals = consecutiveVerificationSignals,
                    consecutiveLoginSignals = consecutiveLoginSignals,
                )
            ) {
                AuthPollDecision.NETWORK_ERROR -> return AuthCheckResult(
                    outcome = AuthProbeOutcome.NETWORK_ERROR,
                    webViewVersion = session.webViewVersion,
                    detail = "页面未能正常加载（网络异常）",
                )

                AuthPollDecision.VERIFICATION_REQUIRED -> return AuthCheckResult(
                    outcome = AuthProbeOutcome.VERIFICATION_REQUIRED,
                    webViewVersion = session.webViewVersion,
                    detail = "检测到抖音安全验证弹窗，请在页面内完成验证",
                )

                AuthPollDecision.AUTHENTICATED -> {
                    // 真机 M1 实证：CONVERSATION_LIST 首次出现后停 1000ms 让火花徽标与行水合完成
                    delay(CONVERSATION_LIST_HYDRATION_DELAY_MS)
                    val refreshed = evalObject(
                        session.webView,
                        DouyinJsScripts.buildAuthAndSelfIdentityProbeJs(bundle),
                    ) ?: probe
                    return AuthCheckResult(
                        outcome = AuthProbeOutcome.AUTHENTICATED,
                        selfUid = refreshed?.stringOrNull("selfUid"),
                        douyinNickname = refreshed?.stringOrNull("douyinNickname"),
                        webViewVersion = session.webViewVersion,
                        detail = "登录态有效",
                    )
                }

                AuthPollDecision.UNAUTHENTICATED -> return AuthCheckResult(
                    outcome = AuthProbeOutcome.UNAUTHENTICATED,
                    webViewVersion = session.webViewVersion,
                    detail = "页面存在扫码或登录提示，请重新扫码登录",
                )

                AuthPollDecision.CONTINUE -> {
                    val hasUserInfo = probe?.boolOrDefault("hasLocalStorageUserInfo", false) == true
                    if (hasUserInfo && !reloadedSkeletonOnce && elapsed >= 6_000L) {
                        reloadedSkeletonOnce = true
                        withContext(Dispatchers.Main) {
                            session.webView.reload()
                        }
                        delay(2_500L)
                        continue
                    }
                }
            }

            if (SystemClock.elapsedRealtime() >= deadline) {
                val isBlank = probe?.boolOrDefault("isBlankPage", false) == true
                if (mainErr != null || (httpErr != null && httpErr.first >= 500) || probe == null ||
                    probe.boolOrDefault("isErrorPage", false) || isBlank || !probe.boolOrDefault("isDouyinHost", false)
                ) {
                    return AuthCheckResult(
                        outcome = AuthProbeOutcome.NETWORK_ERROR,
                        webViewVersion = session.webViewVersion,
                        detail = "加载抖音聊天页超时或网络异常",
                    )
                }
                val skeletonBroken = probe.boolOrDefault("hasLocalStorageUserInfo", false) &&
                    !probe.boolOrDefault("hasQrOrLoginSignal", false)
                return AuthCheckResult(
                    outcome = AuthProbeOutcome.UNAUTHENTICATED,
                    webViewVersion = session.webViewVersion,
                    skeletonMissingDespiteSession = skeletonBroken,
                    detail = if (skeletonBroken) "已保留会话信息但未渲染会话列表，可能结构已变更" else "未检测到有效登录态",
                )
            }
            delay(500L)
        }
    }

    private suspend fun scanAllChatsInSession(
        session: SessionHandle,
        existingTargets: List<ContractTargetEntity>,
        scanRunId: String,
        onProgress: (suspend (discoveredCount: Int) -> Unit)?,
    ): ScanExecutionResult {
        val auth = checkAuthInSession(session, AUTH_WAIT_TIMEOUT_MS)
        if (auth.outcome != AuthProbeOutcome.AUTHENTICATED) {
            return ScanExecutionResult.AuthOrGateFailure(
                outcome = auth.outcome,
                detail = auth.detail ?: "未登录或网络不可用，无法扫描会话",
            )
        }

        val bundle = selectorBundleStore.current()
        evalObject(session.webView, DouyinJsScripts.buildScrollConversationListStepJs(bundle, 0))
        delay(300L)

        data class RawScannedRow(
            val title: String,
            val sparkDays: Int?,
            val sparkKind: String?,
            val sparkLabel: String?,
            val hasSparkInPreview: Boolean,
        )

        val discovered = LinkedHashMap<String, RawScannedRow>()
        var idleRounds = 0

        for (round in 1..SCAN_MAX_ROUNDS) {
            val countBefore = discovered.size
            val pageObj = evalObject(session.webView, DouyinJsScripts.buildScrollAndCollectChatsJs(bundle))
            if (pageObj == null || !pageObj.boolOrDefault("listFound", false)) {
                if (discovered.isEmpty()) {
                    return ScanExecutionResult.Error(
                        failureClass = FailureClass.DOM_CHANGED,
                        detail = "未找到左侧会话列表容器",
                    )
                }
                break
            }

            val rawItems = pageObj["items"] as? List<*> ?: emptyList<Any?>()
            for (raw in rawItems) {
                @Suppress("UNCHECKED_CAST")
                val row = raw as? Map<String, Any?> ?: continue
                val title = row.stringOrNull("title") ?: continue
                if (!discovered.containsKey(title)) {
                    discovered[title] = RawScannedRow(
                        title = title,
                        sparkDays = row.intOrNull("streakDays"),
                        sparkKind = row.stringOrNull("streakKind"),
                        sparkLabel = row.stringOrNull("streakLabel"),
                        hasSparkInPreview = row.boolOrDefault("hasSparkInPreview", false),
                    )
                }
            }

            onProgress?.invoke(discovered.size)

            idleRounds = if (discovered.size == countBefore) idleRounds + 1 else 0
            val atBottom = pageObj.boolOrDefault("atBottom", false)
            if (atBottom || idleRounds >= SCAN_IDLE_ROUNDS) {
                break
            }
            evalObject(
                session.webView,
                DouyinJsScripts.buildScrollConversationListStepJs(bundle, SCAN_SCROLL_STEP_PX),
            )
            delay(800L)
        }

        session.bridge.awaitPendingJobs(2_000L)
        loadIndexedDbIdentitiesOnce(session)

        val activeTargets = existingTargets.filter { it.deletedAt == null }
        val deletedTargets = existingTargets.filter { it.deletedAt != null }

        val rawCandidates = discovered.values.map { row ->
            val userMatch = session.identityStore.matchUser(row.title)
            val groupMatch = session.identityStore.matchGroup(row.title)

            val (convType, matchStatus, pUserId, pHandle, pConvId, matchDetail) = resolveCandidateIdentity(
                userMatch = userMatch,
                groupMatch = groupMatch,
            )

            val activeMatch = matchExistingTarget(
                displayName = row.title,
                conversationType = convType,
                platformUserId = pUserId,
                platformConversationId = pConvId,
                targets = activeTargets,
            )
            val deletedMatch = if (activeMatch == null) {
                matchExistingTarget(
                    displayName = row.title,
                    conversationType = convType,
                    platformUserId = pUserId,
                    platformConversationId = pConvId,
                    targets = deletedTargets,
                )
            } else {
                null
            }

            val finalLabel = row.sparkLabel
                ?: if (row.sparkDays == null && row.hasSparkInPreview) "火花" else null

            ScannedChatCandidate(
                displayName = row.title,
                conversationType = convType,
                sparkDays = row.sparkDays,
                sparkKind = row.sparkKind,
                sparkLabel = finalLabel,
                matchStatus = matchStatus,
                platformUserId = pUserId,
                platformHandle = pHandle,
                platformConversationId = pConvId,
                matchDetail = matchDetail,
                alreadyImported = activeMatch != null,
                existingTargetId = activeMatch?.id ?: deletedMatch?.id,
                preselected = false,
            )
        }

        val sortedCandidates = ScanReconciliationBuilder.sortAndComputePreselection(rawCandidates)
        val sparkCount = sortedCandidates.count { ScanReconciliationBuilder.hasOfficialSpark(it) }
        val snapshot = ScanReconciliationSnapshot(
            scanRunId = scanRunId,
            scannedAtEpochMs = System.currentTimeMillis(),
            discoveredCount = sortedCandidates.size,
            sparkCount = sparkCount,
            candidates = sortedCandidates,
        )
        return ScanExecutionResult.Success(
            snapshot = snapshot,
            selfUid = auth.selfUid,
            douyinNickname = auth.douyinNickname,
        )
    }

    private suspend fun probeStructureInSession(
        session: SessionHandle,
        sampleTarget: ContractTargetEntity?,
    ): StructureProbeReport {
        val checkedAt = System.currentTimeMillis()
        val bundle = selectorBundleStore.current()
        val digest = selectorBundleStore.digest()
        val auth = checkAuthInSession(session, AUTH_WAIT_TIMEOUT_MS)

        if (auth.outcome != AuthProbeOutcome.AUTHENTICATED) {
            val baseObj = evalObject(session.webView, DouyinJsScripts.buildStructureProbeJs(bundle))
            val listItem = parseProbeItem(baseObj, "conversationList", "CONVERSATION_LIST", "会话列表容器")
            val convItem = parseProbeItem(baseObj, "conversationItem", "CONVERSATION_ITEM", "会话列表项")
            val searchItem = parseProbeItem(baseObj, "searchInputs", "SEARCH_INPUTS", "顶部会话搜索框")
            val items = listOf(
                listItem,
                convItem,
                searchItem,
                StructureProbeItem("RIGHT_TITLE", "右侧会话标题栏", false, 0, "未登录，未核验右侧区域"),
                StructureProbeItem("CHAT_EDITOR_SELECTORS", "消息输入框", false, 0, "未登录，未核验右侧区域"),
                StructureProbeItem("SEND_BUTTONS", "发送按钮", false, 0, "未登录，未核验右侧区域"),
                StructureProbeItem("STICKER_BUTTONS", "表情面板触发按钮", false, 0, "未登录，未核验表情区域"),
                StructureProbeItem("STICKER_PANELS", "表情面板容器", false, 0, "未登录，未核验表情区域"),
                StructureProbeItem("STICKER_ITEM", "表情项与「比心」核验", false, 0, "未登录，未核验表情区域"),
            )
            return StructureProbeReport(
                checkedAtEpochMs = checkedAt,
                loggedIn = false,
                coreSkeletonOk = false,
                bundleDigest = digest,
                webViewVersion = session.webViewVersion,
                items = items,
                summary = auth.detail ?: "当前未处于已登录聊天页，请先完成扫码或验证后再体检",
            )
        }

        // 只读打开首个可见或指定样本会话，绝不写入任何文字或点击发送
        val sampleClick = evalObject(
            session.webView,
            DouyinJsScripts.buildOpenSampleConversationForProbeJs(bundle),
        )
        var sampleOpened = sampleClick?.boolOrDefault("clicked", false) == true
        if (!sampleOpened && sampleTarget != null) {
            val opened = openConversation(
                session = session,
                bundle = bundle,
                targetName = sampleTarget.nicknameSnapshot,
                expectedUserId = sampleTarget.platformUserId,
                expectedConversationId = sampleTarget.platformConversationId,
            )
            sampleOpened = opened.opened
        }
        if (sampleOpened) {
            delay(700L)
        }

        var stickerPanelOpenedForProbe = false
        val probeObj = try {
            if (sampleOpened) {
                val openSticker = evalObject(
                    session.webView,
                    DouyinJsScripts.buildOpenStickerPanelJs(bundle),
                )
                if (openSticker?.boolOrDefault("clicked", false) == true) {
                    stickerPanelOpenedForProbe = true
                    waitForStickerPanelItems(session, bundle, 3_000L)
                }
            }
            evalObject(session.webView, DouyinJsScripts.buildStructureProbeJs(bundle))
        } finally {
            if (stickerPanelOpenedForProbe) {
                withContext(NonCancellable) {
                    evalObject(session.webView, DouyinJsScripts.buildResetInputFocusJs(bundle))
                }
            }
        }

        val listItem = parseProbeItem(probeObj, "conversationList", "CONVERSATION_LIST", "会话列表容器")
        val convItem = parseProbeItem(probeObj, "conversationItem", "CONVERSATION_ITEM", "会话列表项")
        val searchItem = parseProbeItem(probeObj, "searchInputs", "SEARCH_INPUTS", "顶部会话搜索框")
        val rightTitleItem = parseProbeItem(
            probeObj,
            "rightTitle",
            "RIGHT_TITLE",
            "右侧会话标题栏",
            skippedDetail = if (!sampleOpened) "当前暂无可打开的样本会话" else null,
        )
        val editorItem = parseProbeItem(
            probeObj,
            "chatEditorSelectors",
            "CHAT_EDITOR_SELECTORS",
            "消息输入框",
            skippedDetail = if (!sampleOpened) "当前暂无可打开的样本会话" else null,
        )
        val sendItemRaw = parseProbeItem(
            probeObj,
            "sendButtons",
            "SEND_BUTTONS",
            "发送按钮",
        )
        val sendItem = if (sampleOpened && !sendItemRaw.hit) {
            sendItemRaw.copy(detail = "空输入状态下未渲染发布按钮（输入文字后按需出现，属正常现象）")
        } else {
            sendItemRaw
        }
        val stickerBtnItem = parseProbeItem(probeObj, "stickerButtons", "STICKER_BUTTONS", "表情面板触发按钮")
        val stickerPanelItem = parseProbeItem(probeObj, "stickerPanels", "STICKER_PANELS", "表情面板容器")
        @Suppress("UNCHECKED_CAST")
        val stickerItemMap = probeObj?.get("stickerItem") as? Map<String, Any?>
        val stickerItemCount = stickerItemMap?.intOrNull("count") ?: 0
        val stickerItemHit = stickerItemMap?.boolOrDefault("hit", false) == true
        val heartFound = stickerItemMap?.boolOrDefault("heartFound", false) == true
        val stickerItem = StructureProbeItem(
            key = "STICKER_ITEM",
            label = "表情项与「比心」核验",
            hit = stickerItemHit,
            matchedCount = stickerItemCount,
            detail = when {
                !sampleOpened -> "当前暂无可打开的样本会话"
                stickerItemHit && heartFound -> "检测到 $stickerItemCount 个表情项，且已匹配「比心」"
                stickerItemHit -> "检测到 $stickerItemCount 个表情项（首屏未见名为「比心」的描述）"
                else -> "未检测到表情项节点"
            },
        )

        val items = listOf(
            listItem,
            convItem,
            searchItem,
            rightTitleItem,
            editorItem,
            sendItem,
            stickerBtnItem,
            stickerPanelItem,
            stickerItem,
        )

        val coreSkeletonOk = listItem.hit && searchItem.hit &&
            (!sampleOpened || (rightTitleItem.hit && editorItem.hit))

        val missingRequired = buildList {
            if (!listItem.hit) add(listItem.label)
            if (!searchItem.hit) add(searchItem.label)
            if (sampleOpened) {
                if (!rightTitleItem.hit) add(rightTitleItem.label)
                if (!editorItem.hit) add(editorItem.label)
                if (!stickerBtnItem.hit) add(stickerBtnItem.label)
                if (stickerBtnItem.hit && !stickerPanelItem.hit) add(stickerPanelItem.label)
                if (stickerPanelItem.hit && !stickerItem.hit) add(stickerItem.label)
            }
        }
        val summary = if (missingRequired.isEmpty()) {
            "页面核心骨架及常用定位器全部正常（只读体检，未写入或发送任何字符）"
        } else {
            "检测到 ${missingRequired.size} 项定位器未命中：${missingRequired.joinToString("、")}"
        }

        return StructureProbeReport(
            checkedAtEpochMs = checkedAt,
            loggedIn = true,
            coreSkeletonOk = coreSkeletonOk,
            bundleDigest = digest,
            webViewVersion = session.webViewVersion,
            items = items,
            summary = summary,
        )
    }

    private data class OpenConversationResult(
        val opened: Boolean,
        val exact: Boolean,
        val ambiguous: Boolean = false,
        val observedPlatformUserId: String? = null,
        val observedPlatformHandle: String? = null,
        val observedPlatformConversationId: String? = null,
        val observedConversationType: String? = null,
    )

    private suspend fun openConversation(
        session: SessionHandle,
        bundle: SelectorBundle,
        targetName: String,
        expectedUserId: String?,
        expectedConversationId: String?,
    ): OpenConversationResult {
        if (!session.warmedUp) {
            for (i in 0 until WARMUP_SCROLLS) {
                val res = evalObject(
                    session.webView,
                    DouyinJsScripts.buildScrollConversationListStepJs(bundle, SCAN_SCROLL_STEP_PX),
                )
                if (res?.boolOrDefault("listFound", false) != true ||
                    res.boolOrDefault("atBottom", false)
                ) {
                    break
                }
                delay(700L)
            }
            evalObject(session.webView, DouyinJsScripts.buildScrollConversationListStepJs(bundle, 0))
            session.warmedUp = true
        }

        // 1) 优先通过搜索框精确搜索
        val searchFilled = evalObject(
            session.webView,
            DouyinJsScripts.buildFillSearchInputJs(bundle, targetName),
        )?.boolOrDefault("found", false) == true

        var sawMismatch = false
        var sawAmbiguous = false

        if (searchFilled) {
            delay(2_000L)
            val searchDeadline = SystemClock.elapsedRealtime() + SEARCH_RESULTS_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < searchDeadline) {
                val firstProbe = evalObject(
                    session.webView,
                    DouyinJsScripts.buildSearchAndOpenJs(bundle, targetName, 0),
                )
                val totalMatches = firstProbe?.intOrNull("totalMatches") ?: 0
                if (totalMatches > 0) {
                    val outcome = verifyAndTryRemainingCandidates(
                        session = session,
                        bundle = bundle,
                        targetName = targetName,
                        expectedUserId = expectedUserId,
                        expectedConversationId = expectedConversationId,
                        totalMatches = totalMatches,
                        firstClicked = firstProbe?.boolOrDefault("clicked", false) == true,
                    ) { nextIdx ->
                        evalObject(
                            session.webView,
                            DouyinJsScripts.buildSearchAndOpenJs(bundle, targetName, nextIdx),
                        )?.boolOrDefault("clicked", false) == true
                    }
                    if (outcome.opened && outcome.exact) return outcome
                    if (outcome.opened) sawMismatch = true
                    if (outcome.ambiguous) sawAmbiguous = true
                    break
                }
                delay(900L)
            }
            // 清空搜索框恢复列表
            evalObject(session.webView, DouyinJsScripts.buildFillSearchInputJs(bundle, ""))
            delay(800L)
        }

        // 2) 兜底滚动左侧会话列表查找精确标题行
        evalObject(session.webView, DouyinJsScripts.buildScrollConversationListStepJs(bundle, 0))
        delay(300L)
        for (scrollRound in 0 until MAX_LIST_SCROLLS) {
            val firstProbe = evalObject(
                session.webView,
                DouyinJsScripts.buildClickListItemByTitleJs(bundle, targetName, 0),
            )
            val totalMatches = firstProbe?.intOrNull("totalMatches") ?: 0
            if (totalMatches > 0) {
                val outcome = verifyAndTryRemainingCandidates(
                    session = session,
                    bundle = bundle,
                    targetName = targetName,
                    expectedUserId = expectedUserId,
                    expectedConversationId = expectedConversationId,
                    totalMatches = totalMatches,
                    firstClicked = firstProbe?.boolOrDefault("clicked", false) == true,
                ) { nextIdx ->
                    evalObject(
                        session.webView,
                        DouyinJsScripts.buildClickListItemByTitleJs(bundle, targetName, nextIdx),
                    )?.boolOrDefault("clicked", false) == true
                }
                if (outcome.opened && outcome.exact) return outcome
                if (outcome.opened) sawMismatch = true
                if (outcome.ambiguous) sawAmbiguous = true
            }
            val step = evalObject(
                session.webView,
                DouyinJsScripts.buildScrollConversationListStepJs(bundle, SCAN_SCROLL_STEP_PX),
            )
            if (step?.boolOrDefault("atBottom", false) == true) break
            delay(800L)
        }

        return OpenConversationResult(
            opened = sawMismatch,
            exact = false,
            ambiguous = sawAmbiguous,
        )
    }

    private suspend fun verifyAndTryRemainingCandidates(
        session: SessionHandle,
        bundle: SelectorBundle,
        targetName: String,
        expectedUserId: String?,
        expectedConversationId: String?,
        totalMatches: Int,
        firstClicked: Boolean,
        clickIndex: suspend (Int) -> Boolean,
    ): OpenConversationResult {
        var anyOpened = false
        var anyAmbiguous = false
        for (idx in 0 until totalMatches) {
            val clicked = if (idx == 0) firstClicked else clickIndex(idx)
            if (!clicked) continue
            val confirmed = confirmOpenedPanel(
                session = session,
                bundle = bundle,
                targetName = targetName,
                expectedUserId = expectedUserId,
                expectedConversationId = expectedConversationId,
            )
            if (confirmed.opened && confirmed.exact) {
                return confirmed
            }
            if (confirmed.opened) anyOpened = true
            if (confirmed.ambiguous) anyAmbiguous = true
        }
        return OpenConversationResult(opened = anyOpened, exact = false, ambiguous = anyAmbiguous)
    }

    private suspend fun confirmOpenedPanel(
        session: SessionHandle,
        bundle: SelectorBundle,
        targetName: String,
        expectedUserId: String?,
        expectedConversationId: String?,
    ): OpenConversationResult {
        val normalizedTarget = IdentityDisambiguator.normalizeLabel(targetName)
        val deadline = SystemClock.elapsedRealtime() + OPEN_CONFIRM_TIMEOUT_MS
        var lastPrimary: String? = null

        while (true) {
            val titleObj = evalObject(session.webView, DouyinJsScripts.buildReadRightTitleJs(bundle))
            val rawTitle = titleObj?.stringOrNull("rawTitle")
            val primary = IdentityDisambiguator.stripUnreadSuffix(rawTitle)
            if (primary != null) lastPrimary = primary
            val normalizedPrimary = primary?.let { IdentityDisambiguator.normalizeLabel(it) }

            session.bridge.awaitPendingJobs(600L)

            when {
                expectedUserId != null -> {
                    val lookupLabel = if (normalizedPrimary == normalizedTarget) targetName else (primary ?: "")
                    var ids = session.identityStore.userIdsForLabel(lookupLabel)
                    if (ids.isEmpty() && !session.cachedIdentitiesLoaded) {
                        loadIndexedDbIdentitiesOnce(session)
                        ids = session.identityStore.userIdsForLabel(lookupLabel)
                    }
                    if (ids == setOf(expectedUserId)) {
                        val match = session.identityStore.matchUser(lookupLabel)
                        return OpenConversationResult(
                            opened = true,
                            exact = true,
                            observedPlatformUserId = expectedUserId,
                            observedPlatformHandle = match.platformHandle,
                            observedConversationType = "friend",
                        )
                    }
                    if (ids.size == 1 && normalizedPrimary == normalizedTarget) {
                        return OpenConversationResult(opened = true, exact = false)
                    }
                    if (ids.size > 1) {
                        return OpenConversationResult(opened = true, exact = false, ambiguous = true)
                    }
                }

                expectedConversationId != null -> {
                    val lookupLabel = if (normalizedPrimary == normalizedTarget) targetName else (primary ?: "")
                    val ids = session.identityStore.groupIdsForLabel(lookupLabel)
                    if (ids == setOf(expectedConversationId)) {
                        return OpenConversationResult(
                            opened = true,
                            exact = true,
                            observedPlatformConversationId = expectedConversationId,
                            observedConversationType = "group",
                        )
                    }
                    if (ids.size == 1 && normalizedPrimary == normalizedTarget) {
                        return OpenConversationResult(opened = true, exact = false)
                    }
                    if (ids.size > 1) {
                        return OpenConversationResult(opened = true, exact = false, ambiguous = true)
                    }
                }

                else -> {
                    if (normalizedPrimary != null && normalizedPrimary == normalizedTarget) {
                        if (!session.cachedIdentitiesLoaded) {
                            loadIndexedDbIdentitiesOnce(session)
                        }
                        val userMatch = session.identityStore.matchUser(targetName)
                        val groupMatch = session.identityStore.matchGroup(targetName)
                        val isAmbiguous = userMatch.status == MatchStatus.AMBIGUOUS ||
                            groupMatch.status == MatchStatus.AMBIGUOUS ||
                            (userMatch.status == MatchStatus.BOUND && groupMatch.status == MatchStatus.BOUND)
                        if (isAmbiguous) {
                            return OpenConversationResult(opened = true, exact = false, ambiguous = true)
                        }
                        return OpenConversationResult(
                            opened = true,
                            exact = true,
                            observedPlatformUserId = userMatch.platformUserId,
                            observedPlatformHandle = userMatch.platformHandle,
                            observedPlatformConversationId = groupMatch.platformConversationId,
                            observedConversationType = when {
                                groupMatch.status == MatchStatus.BOUND -> "group"
                                userMatch.status == MatchStatus.BOUND -> "friend"
                                else -> null
                            },
                        )
                    }
                }
            }

            if (SystemClock.elapsedRealtime() >= deadline) {
                return OpenConversationResult(opened = lastPrimary != null, exact = false)
            }
            delay(500L)
        }
    }

    private suspend fun waitForEditorReady(
        session: SessionHandle,
        bundle: SelectorBundle,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val res = evalObject(session.webView, DouyinJsScripts.buildCheckEditorEmptyJs(bundle))
            if (res?.boolOrDefault("editorVisible", false) == true) {
                return true
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return false
            }
            delay(250L)
        }
    }

    private suspend fun waitForStickerPanelItems(
        session: SessionHandle,
        bundle: SelectorBundle,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val res = evalObject(session.webView, DouyinJsScripts.buildCheckStickerPanelReadyJs(bundle))
            if (res?.boolOrDefault("panelFound", false) == true &&
                (res.intOrNull("itemCount") ?: 0) > 0
            ) {
                return true
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return false
            }
            delay(300L)
        }
    }

    private suspend fun waitForMessageListSettled(
        session: SessionHandle,
        bundle: SelectorBundle,
        minWaitMs: Long = MESSAGE_LIST_MIN_SETTLE_MS,
        maxWaitMs: Long = MESSAGE_LIST_MAX_SETTLE_MS,
    ) {
        val startMs = SystemClock.elapsedRealtime()
        val deadline = startMs + maxWaitMs
        var previous: MediaSettleSample? = null
        var stablePolls = 0

        while (true) {
            val sampleObj = evalObject(
                session.webView,
                DouyinJsScripts.buildSampleOutgoingMediaMultisetJs(bundle),
            )
            val current = if (sampleObj != null) {
                MediaSettleSample(
                    totalBoxes = sampleObj.intOrNull("totalBoxes") ?: 0,
                    pendingImgCount = sampleObj.intOrNull("pendingImgCount") ?: 0,
                    signatures = sampleObj.stringListOrEmpty("signatures"),
                )
            } else {
                null
            }

            if (current != null) {
                val unchanged = previous != null &&
                    current.pendingImgCount == 0 &&
                    previous.totalBoxes == current.totalBoxes &&
                    previous.signatures == current.signatures
                stablePolls = if (unchanged) stablePolls + 1 else 1
                val elapsedMs = SystemClock.elapsedRealtime() - startMs
                if (isMessageListSettled(
                        previous = previous,
                        current = current,
                        stablePolls = stablePolls,
                        elapsedMs = elapsedMs,
                        minWaitMs = minWaitMs,
                    )
                ) {
                    return
                }
                previous = current
            } else {
                stablePolls = 0
                previous = null
            }

            if (SystemClock.elapsedRealtime() >= deadline) {
                return
            }
            delay(MESSAGE_LIST_POLL_INTERVAL_MS)
        }
    }

    private suspend fun loadIndexedDbIdentitiesOnce(session: SessionHandle) {
        if (session.cachedIdentitiesLoaded) return
        session.cachedIdentitiesLoaded = true
        val callId = UUID.randomUUID().toString()
        val deferred = session.bridge.registerAsyncCall(callId)
        try {
            val script = DouyinJsScripts.buildReadIndexedDbIdentitiesJs(
                bridgeName = DouyinJsScripts.DEFAULT_BRIDGE_NAME,
                callId = callId,
            )
            evalJsRaw(session.webView, script)
            val json = withTimeoutOrNull(3_000L) { deferred.await() }
            if (!json.isNullOrBlank()) {
                session.identityStore.ingestIdentityJson(json)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
        } finally {
            session.bridge.cancelAsyncCall(callId)
        }
    }

    private suspend fun tryAndroidImeCommit(webView: WebView, text: String): Boolean =
        withContext(Dispatchers.Main) {
            try {
                webView.requestFocus()
                val ic = webView.onCreateInputConnection(EditorInfo()) ?: return@withContext false
                ic.commitText(text, 1)
                true
            } catch (_: Throwable) {
                false
            }
        }

    private suspend fun evalJsRaw(webView: WebView, script: String): String? =
        withTimeoutOrNull(JS_EVAL_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    try {
                        webView.evaluateJavascript(script) { value ->
                            if (cont.isActive) {
                                cont.resume(value)
                            }
                        }
                    } catch (_: Throwable) {
                        if (cont.isActive) {
                            cont.resume(null)
                        }
                    }
                }
            }
        }

    @Suppress("UNCHECKED_CAST")
    private suspend fun evalObject(webView: WebView, script: String): Map<String, Any?>? {
        val raw = evalJsRaw(webView, script) ?: return null
        return ExecJson.unwrapEvaluateResult(raw) as? Map<String, Any?>
    }

    private data class CandidateIdentityTuple(
        val conversationType: String,
        val matchStatus: MatchStatus,
        val platformUserId: String?,
        val platformHandle: String?,
        val platformConversationId: String?,
        val matchDetail: String?,
    )

    private fun resolveCandidateIdentity(
        userMatch: IdentityMatch,
        groupMatch: ConversationMatch,
    ): CandidateIdentityTuple {
        if (groupMatch.status == MatchStatus.BOUND && userMatch.status == MatchStatus.BOUND) {
            return CandidateIdentityTuple(
                conversationType = "friend",
                matchStatus = MatchStatus.AMBIGUOUS,
                platformUserId = null,
                platformHandle = null,
                platformConversationId = null,
                matchDetail = "同名同时命中好友与群聊，已阻止自动绑定",
            )
        }
        if (groupMatch.status == MatchStatus.AMBIGUOUS) {
            return CandidateIdentityTuple(
                conversationType = "group",
                matchStatus = MatchStatus.AMBIGUOUS,
                platformUserId = null,
                platformHandle = null,
                platformConversationId = null,
                matchDetail = groupMatch.detail,
            )
        }
        if (groupMatch.status == MatchStatus.BOUND) {
            return CandidateIdentityTuple(
                conversationType = "group",
                matchStatus = MatchStatus.BOUND,
                platformUserId = null,
                platformHandle = null,
                platformConversationId = groupMatch.platformConversationId,
                matchDetail = groupMatch.detail,
            )
        }
        if (userMatch.status == MatchStatus.AMBIGUOUS) {
            return CandidateIdentityTuple(
                conversationType = "friend",
                matchStatus = MatchStatus.AMBIGUOUS,
                platformUserId = null,
                platformHandle = null,
                platformConversationId = null,
                matchDetail = userMatch.detail,
            )
        }
        if (userMatch.status == MatchStatus.BOUND) {
            return CandidateIdentityTuple(
                conversationType = "friend",
                matchStatus = MatchStatus.BOUND,
                platformUserId = userMatch.platformUserId,
                platformHandle = userMatch.platformHandle,
                platformConversationId = null,
                matchDetail = userMatch.detail,
            )
        }
        return CandidateIdentityTuple(
            conversationType = "friend",
            matchStatus = MatchStatus.NOT_OBSERVED,
            platformUserId = null,
            platformHandle = null,
            platformConversationId = null,
            matchDetail = userMatch.detail,
        )
    }

    private fun matchExistingTarget(
        displayName: String,
        conversationType: String,
        platformUserId: String?,
        platformConversationId: String?,
        targets: List<ContractTargetEntity>,
    ): ContractTargetEntity? {
        if (platformConversationId != null) {
            targets.firstOrNull { it.platformConversationId == platformConversationId }?.let { return it }
        }
        if (platformUserId != null) {
            targets.firstOrNull { it.platformUserId == platformUserId }?.let { return it }
            val normName = IdentityDisambiguator.normalizeLabel(displayName)
            val conflictingSameName = targets.any {
                IdentityDisambiguator.normalizeLabel(it.nicknameSnapshot) == normName &&
                    !it.platformUserId.isNullOrBlank()
            }
            if (conflictingSameName) return null
        }

        val normalizedDisplay = IdentityDisambiguator.normalizeLabel(displayName)
        for (target in targets) {
            if (IdentityDisambiguator.normalizeLabel(target.nicknameSnapshot) != normalizedDisplay) {
                continue
            }
            if (!target.conversationType.isNullOrBlank() && target.conversationType != conversationType) {
                continue
            }
            if (!target.platformUserId.isNullOrBlank() && platformUserId != null &&
                target.platformUserId != platformUserId
            ) {
                continue
            }
            if (!target.platformConversationId.isNullOrBlank() &&
                (platformConversationId == null || target.platformConversationId != platformConversationId)
            ) {
                continue
            }
            return target
        }
        return null
    }

    private fun parseProbeItem(
        probeObj: Map<String, Any?>?,
        jsonKey: String,
        key: String,
        label: String,
        skippedDetail: String? = null,
    ): StructureProbeItem {
        if (skippedDetail != null) {
            return StructureProbeItem(key = key, label = label, hit = false, matchedCount = 0, detail = skippedDetail)
        }
        @Suppress("UNCHECKED_CAST")
        val section = probeObj?.get(jsonKey) as? Map<String, Any?>
        val hit = section?.boolOrDefault("hit", false) == true
        val count = section?.intOrNull("count") ?: 0
        val detail = if (hit) "命中 $count 处" else "未命中目标元素"
        return StructureProbeItem(
            key = key,
            label = label,
            hit = hit,
            matchedCount = count,
            detail = detail,
        )
    }

    private fun authOutcomeToFailureClass(outcome: AuthProbeOutcome): FailureClass = when (outcome) {
        AuthProbeOutcome.NETWORK_ERROR -> FailureClass.NETWORK
        AuthProbeOutcome.VERIFICATION_REQUIRED -> FailureClass.VERIFICATION
        AuthProbeOutcome.UNAUTHENTICATED -> FailureClass.AUTH
        AuthProbeOutcome.AUTHENTICATED -> FailureClass.OTHER
    }

    internal enum class AuthPollDecision {
        CONTINUE,
        NETWORK_ERROR,
        VERIFICATION_REQUIRED,
        AUTHENTICATED,
        UNAUTHENTICATED,
    }

    internal data class MediaSettleSample(
        val totalBoxes: Int,
        val pendingImgCount: Int,
        val signatures: List<String>,
    )

    companion object {
        private const val AUTH_WAIT_TIMEOUT_MS = 20_000L
        private const val CONVERSATION_LIST_HYDRATION_DELAY_MS = 1_000L
        private const val SEARCH_RESULTS_TIMEOUT_MS = 12_000L
        private const val OPEN_CONFIRM_TIMEOUT_MS = 8_000L
        private const val EDITOR_READY_TIMEOUT_MS = 8_000L
        private const val STICKER_PANEL_TIMEOUT_MS = 5_000L
        private const val STICKER_POST_CLICK_SETTLE_MS = 800L
        private const val MESSAGE_LIST_POLL_INTERVAL_MS = 350L
        private const val MESSAGE_LIST_MIN_SETTLE_MS = 700L
        private const val MESSAGE_LIST_MAX_SETTLE_MS = 2_500L
        private const val MESSAGE_LIST_EMPTY_MIN_WAIT_MS = 1_500L
        private const val JS_EVAL_TIMEOUT_MS = 5_000L
        private const val WARMUP_SCROLLS = 5
        private const val MAX_LIST_SCROLLS = 8
        private const val SCAN_MAX_ROUNDS = 15
        private const val SCAN_IDLE_ROUNDS = 3
        private const val SCAN_SCROLL_STEP_PX = 900

        internal fun isMessageListSettled(
            previous: MediaSettleSample?,
            current: MediaSettleSample,
            stablePolls: Int,
            elapsedMs: Long,
            minWaitMs: Long,
            emptyMinWaitMs: Long = MESSAGE_LIST_EMPTY_MIN_WAIT_MS,
        ): Boolean =
            previous != null &&
                current.pendingImgCount == 0 &&
                previous.totalBoxes == current.totalBoxes &&
                previous.signatures == current.signatures &&
                stablePolls >= 2 &&
                elapsedMs >= minWaitMs &&
                (current.totalBoxes > 0 || elapsedMs >= emptyMinWaitMs)

        internal fun evaluateAuthPollDecision(
            probe: Map<String, Any?>?,
            mainErr: Pair<Int, String>?,
            pageFinished: Boolean,
            elapsedMs: Long,
            consecutiveVerificationSignals: Int,
            consecutiveLoginSignals: Int,
        ): AuthPollDecision {
            if (probe != null) {
                val isDouyinHost = probe.boolOrDefault("isDouyinHost", false)
                val isBlankPage = probe.boolOrDefault("isBlankPage", false)
                val isErrorPage = probe.boolOrDefault("isErrorPage", false)
                val loggedIn = probe.boolOrDefault("loggedIn", false)
                val verificationRequired = probe.boolOrDefault("verificationRequired", false)
                val hasUserInfo = probe.boolOrDefault("hasLocalStorageUserInfo", false)
                val hasLoginSignal = probe.boolOrDefault("hasQrOrLoginSignal", false)

                if (isErrorPage) {
                    return AuthPollDecision.NETWORK_ERROR
                }
                if (isBlankPage && mainErr == null) {
                    return AuthPollDecision.CONTINUE
                }
                if (mainErr != null && !isDouyinHost && (!isBlankPage || pageFinished || elapsedMs >= 1_500L)) {
                    return AuthPollDecision.NETWORK_ERROR
                }
                if (loggedIn) {
                    return AuthPollDecision.AUTHENTICATED
                }
                if (verificationRequired && (pageFinished || consecutiveVerificationSignals >= 2)) {
                    return AuthPollDecision.VERIFICATION_REQUIRED
                }
                if (hasLoginSignal && !hasUserInfo && pageFinished && consecutiveLoginSignals >= 3) {
                    return AuthPollDecision.UNAUTHENTICATED
                }
                return AuthPollDecision.CONTINUE
            }

            if (mainErr != null && (pageFinished || elapsedMs >= 1_500L)) {
                return AuthPollDecision.NETWORK_ERROR
            }
            return AuthPollDecision.CONTINUE
        }
    }
}
