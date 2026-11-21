package io.github.oinzc.flamecontractor.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.webkit.WebViewCompat
import io.github.oinzc.flamecontractor.core.notify.BarkNotifier
import io.github.oinzc.flamecontractor.core.notify.BarkProbeResult
import io.github.oinzc.flamecontractor.core.notify.BarkUrlNormalizer
import io.github.oinzc.flamecontractor.core.notify.EncryptedSecretStore
import io.github.oinzc.flamecontractor.core.notify.NotifyLevel
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundle
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundleStore
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.SendAttemptEntity
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.domain.AccountHealthReducer
import io.github.oinzc.flamecontractor.execution.ExecutionForegroundService
import io.github.oinzc.flamecontractor.execution.ExecutionRuntimeBus
import io.github.oinzc.flamecontractor.execution.StructureProbeReport
import io.github.oinzc.flamecontractor.scheduler.DailySchedulePlanner
import io.github.oinzc.flamecontractor.scheduler.ScheduleCoordinator
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface SettingsAccountRepository {
    fun observeAccount(accountId: String = DEFAULT_ACCOUNT_ID): Flow<AccountEntity?>
    suspend fun getAccount(accountId: String = DEFAULT_ACCOUNT_ID): AccountEntity?
    suspend fun updateAccount(account: AccountEntity)
    suspend fun countActiveTargets(accountId: String = DEFAULT_ACCOUNT_ID): Int
    suspend fun listActiveTargets(accountId: String = DEFAULT_ACCOUNT_ID): List<ContractTargetEntity>
    suspend fun listJobsForDate(accountId: String, businessDate: String): List<SendJobEntity>
    suspend fun listRecentAttempts(accountId: String, limit: Int = 50): List<SendAttemptEntity>
}

interface SettingsScheduleGateway {
    suspend fun rescheduleAll(): Instant?
}

interface SettingsSelectorGateway {
    fun digest(): String
    fun hasOverrides(): Boolean
    fun basePrettyJson(): String
    fun editableOverridesPrettyJson(): String
    fun saveOverrides(rawJson: String): SelectorBundle
    fun clearOverrides(): SelectorBundle
}

interface SettingsBarkGateway {
    fun readMaskedBarkUrl(): String?
    fun readRawBarkUrl(): String?
    fun hasBarkUrl(): Boolean
    fun saveBarkUrl(normalizedUrl: String?)
    suspend fun probe(
        title: String,
        body: String,
        level: NotifyLevel = NotifyLevel.ACTIVE,
    ): BarkProbeResult
}

interface SettingsProbeGateway {
    val probeReports: Flow<StructureProbeReport?>
    val runtimeRunning: Flow<Boolean>
    val runtimeMessage: Flow<String?>
    fun triggerStructureProbe(): Boolean
    fun clearLatestProbeReport()
}

interface SettingsSurvivalGateway {
    fun inspect(): RomSurvivalSnapshot
    fun setAutostartConfirmed(confirmed: Boolean)
}

interface SettingsDiagnosticsEnvProvider {
    fun appVersionName(): String
    fun appVersionCode(): Long
    fun androidSdkInt(): Int
    fun androidRelease(): String
    fun webViewVersion(): String?
}

data class SettingsUiState(
    val isLoading: Boolean = true,
    val accountStatus: AccountStatus = AccountStatus.PENDING_LOGIN,
    val accountTimezone: String = java.time.ZoneId.systemDefault().id,
    val enabledInput: Boolean = true,
    val sendWindowStartInput: String = "20:00",
    val sendWindowEndInput: String = "22:00",
    val sendTimeHhmmInput: String = "",
    val jitterMaxMinutesInput: String = "15",
    val cooldownMinSecInput: String = "3",
    val cooldownMaxSecInput: String = "8",
    val hasUnsavedScheduleChanges: Boolean = false,
    val isSavingSchedule: Boolean = false,
    val scheduleError: String? = null,
    val scheduleMessage: String? = null,
    val nextScheduledWakeText: String? = null,

    val barkConfigured: Boolean = false,
    val maskedBarkUrl: String? = null,
    val barkInputText: String = "",
    val isSavingBark: Boolean = false,
    val isProbingBark: Boolean = false,
    val barkError: String? = null,
    val barkMessage: String? = null,

    val survivalSnapshot: RomSurvivalSnapshot = RomSurvivalChecker.buildSnapshot(
        checkedAtEpochMs = 0L,
        isXiaomiOrHyperOs = false,
        manufacturer = "Android",
        notificationsGranted = false,
        batteryOptimizationIgnored = false,
        exactAlarmAllowed = false,
        autostartGuidanceConfirmed = false,
    ),

    val selectorDigest: String = "",
    val hasSelectorOverrides: Boolean = false,
    val showOverrideDialog: Boolean = false,
    val overrideJsonInput: String = "{}",
    val overrideBaselineReferenceJson: String = "",
    val isSavingOverrides: Boolean = false,
    val overrideError: String? = null,
    val selectorMessage: String? = null,

    val isProbeRunning: Boolean = false,
    val probeRuntimeMessage: String? = null,
    val latestProbeReport: StructureProbeReport? = null,
    val showProbeDialog: Boolean = false,
    val domRecoveryMessage: String? = null,

    val isBuildingDiagnostics: Boolean = false,
    val showDiagnosticsDialog: Boolean = false,
    val diagnosticsReport: SanitizedDiagnosticsReport? = null,
    val diagnosticsError: String? = null,
)

class SettingsViewModel(
    private val accountRepo: SettingsAccountRepository,
    private val scheduleGateway: SettingsScheduleGateway,
    private val selectorGateway: SettingsSelectorGateway,
    private val barkGateway: SettingsBarkGateway,
    private val probeGateway: SettingsProbeGateway,
    private val survivalGateway: SettingsSurvivalGateway,
    private val envProvider: SettingsDiagnosticsEnvProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val accountId: String = DEFAULT_ACCOUNT_ID,
    coroutineScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var scheduleFormDirty = false
    private var initialProbeSeen = false
    private var initialProbeReportRef: StructureProbeReport? = null
    private var awaitingProbeResult = false
    private var lastHandledProbeReportRef: StructureProbeReport? = null

    init {
        loadInitialSnapshot()
        observeAccountChanges()
        observeProbeStream()
    }

    private fun loadInitialSnapshot() {
        scope.launch {
            val loaded = withContext(ioDispatcher) {
                val survival = survivalGateway.inspect()
                val digest = selectorGateway.digest()
                val hasOverrides = selectorGateway.hasOverrides()
                val baseRef = selectorGateway.basePrettyJson()
                val maskedBark = barkGateway.readMaskedBarkUrl()
                val hasBark = barkGateway.hasBarkUrl()
                InitialSnapshot(
                    survival = survival,
                    digest = digest,
                    hasOverrides = hasOverrides,
                    baseReferenceJson = baseRef,
                    maskedBark = maskedBark,
                    hasBark = hasBark,
                )
            }
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    survivalSnapshot = loaded.survival,
                    selectorDigest = loaded.digest,
                    hasSelectorOverrides = loaded.hasOverrides,
                    overrideBaselineReferenceJson = loaded.baseReferenceJson,
                    barkConfigured = loaded.hasBark,
                    maskedBarkUrl = loaded.maskedBark,
                    barkInputText = if (state.barkInputText.isEmpty()) {
                        loaded.maskedBark.orEmpty()
                    } else {
                        state.barkInputText
                    },
                )
            }
        }
    }

    private fun observeAccountChanges() {
        scope.launch {
            accountRepo.observeAccount(accountId).collect { account ->
                if (account == null) return@collect
                _uiState.update { state ->
                    if (!scheduleFormDirty) {
                        state.copy(
                            isLoading = false,
                            accountStatus = account.status,
                            accountTimezone = account.timezone,
                            enabledInput = account.enabled,
                            sendWindowStartInput = account.sendWindowStart,
                            sendWindowEndInput = account.sendWindowEnd,
                            sendTimeHhmmInput = account.sendTimeHhmm.orEmpty(),
                            jitterMaxMinutesInput = account.jitterMaxMinutes.toString(),
                            cooldownMinSecInput = account.cooldownMinSec.toString(),
                            cooldownMaxSecInput = account.cooldownMaxSec.toString(),
                            hasUnsavedScheduleChanges = false,
                        )
                    } else {
                        state.copy(
                            isLoading = false,
                            accountStatus = account.status,
                            accountTimezone = account.timezone,
                        )
                    }
                }
            }
        }
    }

    private fun observeProbeStream() {
        scope.launch {
            probeGateway.runtimeRunning.collect { running ->
                _uiState.update { it.copy(isProbeRunning = running) }
            }
        }
        scope.launch {
            probeGateway.runtimeMessage.collect { msg ->
                _uiState.update { it.copy(probeRuntimeMessage = msg) }
            }
        }
        scope.launch {
            probeGateway.probeReports.collect { report ->
                if (!initialProbeSeen) {
                    initialProbeSeen = true
                    initialProbeReportRef = report
                    if (!awaitingProbeResult) {
                        _uiState.update { it.copy(latestProbeReport = report) }
                        return@collect
                    }
                }

                if (report == null) {
                    _uiState.update { it.copy(latestProbeReport = null) }
                    return@collect
                }

                val isNewEmission = awaitingProbeResult ||
                    (report !== initialProbeReportRef && report !== lastHandledProbeReportRef)

                _uiState.update { it.copy(latestProbeReport = report) }

                if (isNewEmission) {
                    awaitingProbeResult = false
                    lastHandledProbeReportRef = report
                    if (report.coreSkeletonOk) {
                        recoverDomBrokenIfEligible(
                            reason = "网页结构体检核心骨架通过（只读体检），账号已恢复健康运行状态",
                        )
                    }
                }
            }
        }
    }

    fun onAccountEnabledChanged(enabled: Boolean) {
        scheduleFormDirty = true
        _uiState.update {
            it.copy(
                enabledInput = enabled,
                hasUnsavedScheduleChanges = true,
                scheduleError = null,
                scheduleMessage = null,
            )
        }
    }

    fun onSendWindowStartChanged(value: String) {
        scheduleFormDirty = true
        _uiState.update {
            it.copy(
                sendWindowStartInput = value,
                hasUnsavedScheduleChanges = true,
                scheduleError = null,
                scheduleMessage = null,
            )
        }
    }

    fun onSendWindowEndChanged(value: String) {
        scheduleFormDirty = true
        _uiState.update {
            it.copy(
                sendWindowEndInput = value,
                hasUnsavedScheduleChanges = true,
                scheduleError = null,
                scheduleMessage = null,
            )
        }
    }

    fun onSendTimeHhmmChanged(value: String) {
        scheduleFormDirty = true
        _uiState.update {
            it.copy(
                sendTimeHhmmInput = value,
                hasUnsavedScheduleChanges = true,
                scheduleError = null,
                scheduleMessage = null,
            )
        }
    }

    fun onJitterMaxMinutesChanged(value: String) {
        scheduleFormDirty = true
        _uiState.update {
            it.copy(
                jitterMaxMinutesInput = value,
                hasUnsavedScheduleChanges = true,
                scheduleError = null,
                scheduleMessage = null,
            )
        }
    }

    fun onCooldownMinSecChanged(value: String) {
        scheduleFormDirty = true
        _uiState.update {
            it.copy(
                cooldownMinSecInput = value,
                hasUnsavedScheduleChanges = true,
                scheduleError = null,
                scheduleMessage = null,
            )
        }
    }

    fun onCooldownMaxSecChanged(value: String) {
        scheduleFormDirty = true
        _uiState.update {
            it.copy(
                cooldownMaxSecInput = value,
                hasUnsavedScheduleChanges = true,
                scheduleError = null,
                scheduleMessage = null,
            )
        }
    }

    fun resetScheduleDraftFromSaved() {
        scheduleFormDirty = false
        scope.launch {
            val saved = withContext(ioDispatcher) { accountRepo.getAccount(accountId) } ?: return@launch
            _uiState.update {
                it.copy(
                    enabledInput = saved.enabled,
                    sendWindowStartInput = saved.sendWindowStart,
                    sendWindowEndInput = saved.sendWindowEnd,
                    sendTimeHhmmInput = saved.sendTimeHhmm.orEmpty(),
                    jitterMaxMinutesInput = saved.jitterMaxMinutes.toString(),
                    cooldownMinSecInput = saved.cooldownMinSec.toString(),
                    cooldownMaxSecInput = saved.cooldownMaxSec.toString(),
                    hasUnsavedScheduleChanges = false,
                    scheduleError = null,
                    scheduleMessage = null,
                )
            }
        }
    }

    fun saveScheduleConfig() {
        val current = _uiState.value
        val start = current.sendWindowStartInput.trim()
        val end = current.sendWindowEndInput.trim()

        val windowError = validateWindowInputs(start, end)
        if (windowError != null) {
            _uiState.update { it.copy(scheduleError = windowError, scheduleMessage = null) }
            return
        }

        val fixedTimeRaw = current.sendTimeHhmmInput.trim()
        val fixedTime: String? = if (fixedTimeRaw.isEmpty()) {
            null
        } else {
            if (parseStrictLocalTime(fixedTimeRaw) == null) {
                _uiState.update {
                    it.copy(
                        scheduleError = "固定发送时刻格式须为 HH:mm（00:00–23:59），留空则使用每日发送窗口",
                        scheduleMessage = null,
                    )
                }
                return
            }
            fixedTimeRaw
        }

        val jitter = current.jitterMaxMinutesInput.trim().toIntOrNull()
        if (jitter == null || jitter !in 0..MAX_JITTER_MINUTES) {
            _uiState.update {
                it.copy(
                    scheduleError = "抖动上限须为 0 至 $MAX_JITTER_MINUTES 分钟之间的整数",
                    scheduleMessage = null,
                )
            }
            return
        }

        val cooldownMin = current.cooldownMinSecInput.trim().toIntOrNull()
        val cooldownMax = current.cooldownMaxSecInput.trim().toIntOrNull()
        if (cooldownMin == null || cooldownMax == null || cooldownMin < 1 || cooldownMax < 1 || cooldownMin > cooldownMax) {
            _uiState.update {
                it.copy(
                    scheduleError = "好友间冷却区间须为正整数，且最小秒数不大于最大秒数",
                    scheduleMessage = null,
                )
            }
            return
        }

        _uiState.update { it.copy(isSavingSchedule = true, scheduleError = null, scheduleMessage = null) }
        scope.launch {
            try {
                val (updated, nextWake) = withContext(ioDispatcher) {
                    val existing = accountRepo.getAccount(accountId) ?: AccountEntity(id = accountId)
                    val nextAccount = existing.copy(
                        enabled = current.enabledInput,
                        sendWindowStart = start,
                        sendWindowEnd = end,
                        sendTimeHhmm = fixedTime,
                        jitterMaxMinutes = jitter,
                        cooldownMinSec = cooldownMin,
                        cooldownMaxSec = cooldownMax,
                        updatedAt = clockMillis(),
                    )
                    accountRepo.updateAccount(nextAccount)
                    val wakeAt = scheduleGateway.rescheduleAll()
                    nextAccount to wakeAt
                }

                scheduleFormDirty = false
                val wakeText = nextWake?.let { formatInstantHhmm(it, updated.timezone) }
                val message = when {
                    !updated.enabled -> "已保存：每日自动定时总开关已关闭"
                    wakeText != null -> "已保存并重算定时计划，下次自动唤醒：$wakeText"
                    else -> "已保存调度配置并刷新定时计划"
                }
                _uiState.update {
                    it.copy(
                        isSavingSchedule = false,
                        accountStatus = updated.status,
                        accountTimezone = updated.timezone,
                        enabledInput = updated.enabled,
                        sendWindowStartInput = updated.sendWindowStart,
                        sendWindowEndInput = updated.sendWindowEnd,
                        sendTimeHhmmInput = updated.sendTimeHhmm.orEmpty(),
                        jitterMaxMinutesInput = updated.jitterMaxMinutes.toString(),
                        cooldownMinSecInput = updated.cooldownMinSec.toString(),
                        cooldownMaxSecInput = updated.cooldownMaxSec.toString(),
                        hasUnsavedScheduleChanges = false,
                        scheduleError = null,
                        scheduleMessage = message,
                        nextScheduledWakeText = wakeText,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (exc: Throwable) {
                _uiState.update {
                    it.copy(
                        isSavingSchedule = false,
                        scheduleError = "保存调度配置失败（${exc.javaClass.simpleName}）",
                    )
                }
            }
        }
    }

    fun onBarkInputChanged(value: String) {
        _uiState.update {
            it.copy(
                barkInputText = value,
                barkError = null,
                barkMessage = null,
            )
        }
    }

    fun saveBarkConfig() {
        val current = _uiState.value
        val rawInput = current.barkInputText.trim()
        if (rawInput.isEmpty()) {
            _uiState.update {
                it.copy(
                    barkError = "请先输入 Bark 推送地址或粘贴 Bark App 复制的 curl 命令",
                    barkMessage = null,
                )
            }
            return
        }

        if (current.barkConfigured && rawInput == current.maskedBarkUrl) {
            _uiState.update {
                it.copy(
                    barkError = null,
                    barkMessage = "当前已加密保存该配置；如需更换请重新粘贴完整 Bark 地址或 curl 命令",
                )
            }
            return
        }

        val normalized = try {
            BarkUrlNormalizer.normalize(rawInput)
        } catch (exc: IllegalArgumentException) {
            _uiState.update {
                it.copy(
                    barkError = exc.message ?: "Bark 推送地址格式无效",
                    barkMessage = null,
                )
            }
            return
        }

        val masked = BarkUrlNormalizer.mask(normalized).orEmpty()
        _uiState.update { it.copy(isSavingBark = true, barkError = null, barkMessage = null) }

        scope.launch {
            try {
                withContext(ioDispatcher) {
                    barkGateway.saveBarkUrl(normalized)
                }
                // UI 状态中绝不保留明文 URL / key，保存后立刻替换为脱敏回显
                _uiState.update {
                    it.copy(
                        isSavingBark = false,
                        barkConfigured = true,
                        maskedBarkUrl = masked,
                        barkInputText = masked,
                        barkError = null,
                        barkMessage = "Bark 推送地址已加密保存，仅保留脱敏回显",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (exc: Throwable) {
                _uiState.update {
                    it.copy(
                        isSavingBark = false,
                        barkError = "保存加密 Bark 配置失败（${exc.javaClass.simpleName}）",
                    )
                }
            }
        }
    }

    fun clearBarkConfig() {
        _uiState.update { it.copy(isSavingBark = true, barkError = null, barkMessage = null) }
        scope.launch {
            try {
                withContext(ioDispatcher) {
                    barkGateway.saveBarkUrl(null)
                }
                _uiState.update {
                    it.copy(
                        isSavingBark = false,
                        barkConfigured = false,
                        maskedBarkUrl = null,
                        barkInputText = "",
                        barkError = null,
                        barkMessage = "已清空加密保存的 Bark 配置",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (exc: Throwable) {
                _uiState.update {
                    it.copy(
                        isSavingBark = false,
                        barkError = "清空 Bark 配置失败（${exc.javaClass.simpleName}）",
                    )
                }
            }
        }
    }

    fun sendBarkTestProbe() {
        val current = _uiState.value
        val trimmedInput = current.barkInputText.trim()
        val expectedMasked = current.maskedBarkUrl.orEmpty()
        if (trimmedInput.isNotEmpty() && trimmedInput != expectedMasked) {
            _uiState.update {
                it.copy(
                    barkError = "检测到尚未保存的新输入内容，请先点击「保存并加密」再发送测试推送",
                    barkMessage = null,
                )
            }
            return
        }
        if (!current.barkConfigured) {
            _uiState.update {
                it.copy(
                    barkError = "尚未配置 Bark 推送地址，请先粘贴并保存",
                    barkMessage = null,
                )
            }
            return
        }

        _uiState.update { it.copy(isProbingBark = true, barkError = null, barkMessage = null) }
        scope.launch {
            val result = try {
                withContext(ioDispatcher) {
                    barkGateway.probe(
                        title = "抖音续火工测试推送",
                        body = "Bark 通知通道配置正常，这是一条测试推送",
                        level = NotifyLevel.ACTIVE,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (exc: Throwable) {
                BarkProbeResult(false, "测试推送失败（${exc.javaClass.simpleName}）")
            }

            val safeDetail = SanitizedDiagnosticsBuilder.redactSensitiveText(result.detail)
            _uiState.update {
                it.copy(
                    isProbingBark = false,
                    barkError = if (result.ok) null else safeDetail,
                    barkMessage = if (result.ok) safeDetail else null,
                )
            }
        }
    }

    fun refreshSurvivalChecks() {
        scope.launch {
            val snapshot = withContext(ioDispatcher) { survivalGateway.inspect() }
            _uiState.update { it.copy(survivalSnapshot = snapshot) }
        }
    }

    fun setAutostartGuidanceConfirmed(confirmed: Boolean) {
        scope.launch {
            val snapshot = withContext(ioDispatcher) {
                survivalGateway.setAutostartConfirmed(confirmed)
                survivalGateway.inspect()
            }
            _uiState.update { it.copy(survivalSnapshot = snapshot) }
        }
    }

    fun triggerStructureProbe() {
        awaitingProbeResult = true
        probeGateway.clearLatestProbeReport()
        val launched = probeGateway.triggerStructureProbe()
        if (!launched) {
            awaitingProbeResult = false
        }
        _uiState.update {
            it.copy(
                showProbeDialog = true,
                domRecoveryMessage = null,
                probeRuntimeMessage = if (launched) {
                    "已请求启动只读网页结构体检…"
                } else {
                    "无法启动前台体检服务，请检查系统后台限制后重试"
                },
            )
        }
    }

    fun openProbeReportDialog() {
        _uiState.update { it.copy(showProbeDialog = true) }
    }

    fun dismissProbeDialog() {
        _uiState.update { it.copy(showProbeDialog = false) }
    }

    fun confirmRecoverDomBrokenFromProbe() {
        val report = _uiState.value.latestProbeReport ?: return
        if (!report.coreSkeletonOk) return
        scope.launch {
            recoverDomBrokenIfEligible(
                reason = "已确认只读结构体检通过，账号已从结构异常恢复健康状态",
            )
        }
    }

    fun openOverrideEditor() {
        scope.launch {
            val (editableJson, baseRef) = withContext(ioDispatcher) {
                selectorGateway.editableOverridesPrettyJson() to selectorGateway.basePrettyJson()
            }
            _uiState.update {
                it.copy(
                    showOverrideDialog = true,
                    overrideJsonInput = editableJson,
                    overrideBaselineReferenceJson = baseRef,
                    overrideError = null,
                )
            }
        }
    }

    fun dismissOverrideDialog() {
        _uiState.update { it.copy(showOverrideDialog = false, overrideError = null) }
    }

    fun onOverrideJsonInputChanged(value: String) {
        _uiState.update { it.copy(overrideJsonInput = value, overrideError = null) }
    }

    fun saveSelectorOverrides() {
        val rawJson = _uiState.value.overrideJsonInput
        _uiState.update { it.copy(isSavingOverrides = true, overrideError = null, selectorMessage = null) }

        scope.launch {
            try {
                val saveOutcome = withContext(ioDispatcher) {
                    selectorGateway.saveOverrides(rawJson)
                    val digest = selectorGateway.digest()
                    val hasOverrides = selectorGateway.hasOverrides()
                    Triple(digest, hasOverrides, true)
                }
                val newDigest = saveOutcome.first
                val hasOverrides = saveOutcome.second

                _uiState.update {
                    it.copy(
                        isSavingOverrides = false,
                        showOverrideDialog = false,
                        selectorDigest = newDigest,
                        hasSelectorOverrides = hasOverrides,
                        overrideError = null,
                        selectorMessage = if (hasOverrides) {
                            "已保存本地定位器覆写（摘要：$newDigest）"
                        } else {
                            "覆写内容与默认基线一致，已清理本地覆写（摘要：$newDigest）"
                        },
                    )
                }

                if (hasOverrides) {
                    recoverDomBrokenIfEligible(
                        reason = "已保存有效定位器覆写，账号已从结构异常恢复健康并重新规划调度",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (exc: IllegalArgumentException) {
                _uiState.update {
                    it.copy(
                        isSavingOverrides = false,
                        overrideError = exc.message ?: "定位器覆写 JSON 校验失败",
                    )
                }
            } catch (exc: Throwable) {
                _uiState.update {
                    it.copy(
                        isSavingOverrides = false,
                        overrideError = "保存定位器覆写失败（${exc.javaClass.simpleName}）",
                    )
                }
            }
        }
    }

    fun clearSelectorOverrides() {
        _uiState.update { it.copy(isSavingOverrides = true, selectorMessage = null) }
        scope.launch {
            try {
                val (digest, hasOverrides) = withContext(ioDispatcher) {
                    selectorGateway.clearOverrides()
                    selectorGateway.digest() to selectorGateway.hasOverrides()
                }
                _uiState.update {
                    it.copy(
                        isSavingOverrides = false,
                        selectorDigest = digest,
                        hasSelectorOverrides = hasOverrides,
                        selectorMessage = "已恢复默认基线定位器（摘要：$digest）",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (exc: Throwable) {
                _uiState.update {
                    it.copy(
                        isSavingOverrides = false,
                        selectorMessage = "恢复默认定位器失败（${exc.javaClass.simpleName}）",
                    )
                }
            }
        }
    }

    fun generateDiagnosticsReport() {
        _uiState.update {
            it.copy(
                isBuildingDiagnostics = true,
                diagnosticsError = null,
            )
        }
        scope.launch {
            try {
                val report = withContext(ioDispatcher) {
                    val account = accountRepo.getAccount(accountId)
                    val targets = accountRepo.listActiveTargets(accountId)
                    val nowMs = clockMillis()
                    val zoneId = DailySchedulePlanner.resolveZoneId(account?.timezone)
                    val todayStr = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zoneId)
                        .toLocalDate()
                        .toString()
                    val todayJobs = accountRepo.listJobsForDate(accountId, todayStr)
                    val recentAttempts = accountRepo.listRecentAttempts(accountId, limit = 50)
                    val survival = survivalGateway.inspect()
                    val digest = selectorGateway.digest()
                    val hasOverrides = selectorGateway.hasOverrides()
                    val rawBark = barkGateway.readRawBarkUrl()
                    val probeReport = _uiState.value.latestProbeReport

                    SanitizedDiagnosticsBuilder.build(
                        RawDiagnosticsContext(
                            generatedAtEpochMs = nowMs,
                            appVersionName = envProvider.appVersionName(),
                            appVersionCode = envProvider.appVersionCode(),
                            androidSdkInt = envProvider.androidSdkInt(),
                            androidRelease = envProvider.androidRelease(),
                            webViewVersion = envProvider.webViewVersion(),
                            selectorDigest = digest,
                            hasSelectorOverrides = hasOverrides,
                            account = account,
                            targets = targets,
                            todayJobs = todayJobs,
                            recentAttempts = recentAttempts,
                            survivalSnapshot = survival,
                            rawBarkUrl = rawBark,
                            latestProbeReport = probeReport,
                        ),
                    )
                }

                _uiState.update {
                    it.copy(
                        isBuildingDiagnostics = false,
                        showDiagnosticsDialog = true,
                        diagnosticsReport = report,
                        diagnosticsError = null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (exc: Throwable) {
                _uiState.update {
                    it.copy(
                        isBuildingDiagnostics = false,
                        diagnosticsError = "生成脱敏诊断报告失败（${exc.javaClass.simpleName}）",
                    )
                }
            }
        }
    }

    fun dismissDiagnosticsDialog() {
        _uiState.update { it.copy(showDiagnosticsDialog = false) }
    }

    private suspend fun recoverDomBrokenIfEligible(reason: String): Boolean {
        val recoveredAccount = withContext(ioDispatcher) {
            val account = accountRepo.getAccount(accountId) ?: return@withContext null
            if (account.status != AccountStatus.DOM_BROKEN) return@withContext null
            val next = AccountHealthReducer.recoverFromDomBroken(account, nowEpochMs = clockMillis())
            if (next == account) return@withContext null
            accountRepo.updateAccount(next)
            scheduleGateway.rescheduleAll()
            next
        } ?: return false

        _uiState.update {
            it.copy(
                accountStatus = recoveredAccount.status,
                domRecoveryMessage = reason,
            )
        }
        return true
    }

    private fun validateWindowInputs(start: String, end: String): String? {
        val parsedStart = parseStrictLocalTime(start)
        val parsedEnd = parseStrictLocalTime(end)
        if (parsedStart == null || parsedEnd == null) {
            return "发送窗口格式须为 HH:mm（例如 20:00 与 22:00）"
        }
        if (DailySchedulePlanner.validateWindow(start, end)) {
            return null
        }
        if (!parsedStart.isBefore(parsedEnd)) {
            return "不支持跨午夜窗口：开始时间必须严格早于结束时间（00:00 <= 开始 < 结束 <= 23:55）"
        }
        if (parsedEnd.isAfter(DailySchedulePlanner.MAX_WINDOW_END)) {
            return "发送窗口结束时间不得晚于 23:55（首版不支持跨午夜调度）"
        }
        return "发送窗口不合法，须满足 00:00 <= 开始 < 结束 <= 23:55"
    }

    private fun parseStrictLocalTime(raw: String): LocalTime? {
        if (!HHMM_REGEX.matches(raw)) return null
        val hour = raw.substring(0, 2).toIntOrNull() ?: return null
        val minute = raw.substring(3, 5).toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    private fun formatInstantHhmm(instant: Instant, timezoneId: String): String {
        val zone = DailySchedulePlanner.resolveZoneId(timezoneId)
        val zdt = ZonedDateTime.ofInstant(instant, zone)
        return TIME_DISPLAY_FORMATTER.format(zdt)
    }

    private data class InitialSnapshot(
        val survival: RomSurvivalSnapshot,
        val digest: String,
        val hasOverrides: Boolean,
        val baseReferenceJson: String,
        val maskedBark: String?,
        val hasBark: Boolean,
    )

    companion object {
        const val MAX_JITTER_MINUTES = 15
        private val HHMM_REGEX = Regex("^\\d{2}:\\d{2}$")
        private val TIME_DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        fun factory(
            context: Context,
            database: FlameDatabase,
            selectorBundleStore: SelectorBundleStore,
            secretStore: EncryptedSecretStore,
            barkNotifier: BarkNotifier,
            scheduleCoordinator: ScheduleCoordinator,
        ): ViewModelProvider.Factory {
            val appContext = context.applicationContext ?: context
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                        return SettingsViewModel(
                            accountRepo = RoomSettingsAccountRepository(database),
                            scheduleGateway = AndroidScheduleGateway(scheduleCoordinator),
                            selectorGateway = StoreSettingsSelectorGateway(selectorBundleStore),
                            barkGateway = AndroidBarkGateway(secretStore, barkNotifier),
                            probeGateway = RuntimeBusProbeGateway(appContext),
                            survivalGateway = AndroidSurvivalGateway(appContext),
                            envProvider = AndroidDiagnosticsEnvProvider(appContext),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

internal class RoomSettingsAccountRepository(
    private val database: FlameDatabase,
) : SettingsAccountRepository {

    override fun observeAccount(accountId: String): Flow<AccountEntity?> =
        database.accountDao().observeAccount(accountId)

    override suspend fun getAccount(accountId: String): AccountEntity? =
        database.accountDao().getAccount(accountId)

    override suspend fun updateAccount(account: AccountEntity) {
        database.accountDao().update(account)
    }

    override suspend fun countActiveTargets(accountId: String): Int =
        database.targetDao().countActiveTargets(accountId)

    override suspend fun listActiveTargets(accountId: String): List<ContractTargetEntity> =
        database.targetDao().listActiveTargets(accountId)

    override suspend fun listJobsForDate(accountId: String, businessDate: String): List<SendJobEntity> =
        database.sendJobDao().listJobsForDate(accountId, businessDate)

    override suspend fun listRecentAttempts(accountId: String, limit: Int): List<SendAttemptEntity> {
        val recentJobs = database.sendJobDao().observeRecentJobs(accountId, limit = 25).first()
        val allAttempts = ArrayList<SendAttemptEntity>()
        for (job in recentJobs) {
            allAttempts.addAll(database.sendJobDao().listAttemptsForJob(job.id))
        }
        return allAttempts
            .sortedByDescending { it.startedAt }
            .take(limit)
    }
}

internal class AndroidScheduleGateway(
    private val coordinator: ScheduleCoordinator,
) : SettingsScheduleGateway {
    override suspend fun rescheduleAll(): Instant? = coordinator.rescheduleAll()
}

internal class StoreSettingsSelectorGateway(
    private val store: SelectorBundleStore,
) : SettingsSelectorGateway {

    override fun digest(): String = store.digest()

    override fun hasOverrides(): Boolean = store.hasOverrides()

    override fun basePrettyJson(): String = SelectorBundle.toPrettyJson(store.base().toMap())

    override fun editableOverridesPrettyJson(): String {
        val existing = store.overrides()
        return if (existing.isEmpty()) "{}" else SelectorBundle.toPrettyJson(existing)
    }

    override fun saveOverrides(rawJson: String): SelectorBundle = store.saveOverrides(rawJson)

    override fun clearOverrides(): SelectorBundle = store.clearOverrides()
}

internal class AndroidBarkGateway(
    private val secretStore: EncryptedSecretStore,
    private val barkNotifier: BarkNotifier,
) : SettingsBarkGateway {

    override fun readMaskedBarkUrl(): String? = secretStore.readMaskedBarkUrl()

    override fun readRawBarkUrl(): String? = secretStore.readBarkUrl()

    override fun hasBarkUrl(): Boolean = secretStore.hasBarkUrl()

    override fun saveBarkUrl(normalizedUrl: String?) {
        secretStore.saveBarkUrl(normalizedUrl)
    }

    override suspend fun probe(title: String, body: String, level: NotifyLevel): BarkProbeResult =
        barkNotifier.probe(title = title, body = body, level = level)
}

internal class RuntimeBusProbeGateway(
    private val appContext: Context,
) : SettingsProbeGateway {

    override val probeReports: Flow<StructureProbeReport?> =
        ExecutionRuntimeBus.state.map { it.latestProbeReport }.distinctUntilChanged()

    override val runtimeRunning: Flow<Boolean> =
        ExecutionRuntimeBus.state.map { it.running }.distinctUntilChanged()

    override val runtimeMessage: Flow<String?> =
        ExecutionRuntimeBus.state.map { it.lastMessage }.distinctUntilChanged()

    override fun triggerStructureProbe(): Boolean =
        ExecutionForegroundService.enqueueStructureProbe(appContext)

    override fun clearLatestProbeReport() {
        ExecutionRuntimeBus.clearProbeReport()
    }
}

internal class AndroidSurvivalGateway(
    private val appContext: Context,
) : SettingsSurvivalGateway {

    override fun inspect(): RomSurvivalSnapshot = RomSurvivalChecker.inspect(appContext)

    override fun setAutostartConfirmed(confirmed: Boolean) {
        RomSurvivalChecker.setAutostartGuidanceConfirmed(appContext, confirmed)
    }
}

internal class AndroidDiagnosticsEnvProvider(
    private val appContext: Context,
) : SettingsDiagnosticsEnvProvider {

    override fun appVersionName(): String = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        info.versionName?.takeIf { it.isNotBlank() } ?: "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    override fun appVersionCode(): Long = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    } catch (_: PackageManager.NameNotFoundException) {
        0L
    } catch (_: Throwable) {
        0L
    }

    override fun androidSdkInt(): Int = Build.VERSION.SDK_INT

    override fun androidRelease(): String = Build.VERSION.RELEASE ?: "unknown"

    override fun webViewVersion(): String? = try {
        WebViewCompat.getCurrentWebViewPackage(appContext)?.versionName
    } catch (_: Throwable) {
        null
    }
}
