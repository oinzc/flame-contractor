package io.github.oinzc.flamecontractor.ui.settings

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oinzc.flamecontractor.core.notify.BarkNotifier
import io.github.oinzc.flamecontractor.core.notify.EncryptedSecretStore
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundleStore
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.scheduler.ScheduleCoordinator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsRoute(
    database: FlameDatabase,
    selectorBundleStore: SelectorBundleStore,
    secretStore: EncryptedSecretStore,
    barkNotifier: BarkNotifier,
    scheduleCoordinator: ScheduleCoordinator,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val factory = remember(
        context,
        database,
        selectorBundleStore,
        secretStore,
        barkNotifier,
        scheduleCoordinator,
    ) {
        SettingsViewModel.factory(
            context = context,
            database = database,
            selectorBundleStore = selectorBundleStore,
            secretStore = secretStore,
            barkNotifier = barkNotifier,
            scheduleCoordinator = scheduleCoordinator,
        )
    }
    val viewModel: SettingsViewModel = viewModel(factory = factory)
    val uiState by viewModel.uiState.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSurvivalChecks()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshSurvivalChecks()
        if (!granted) {
            RomSurvivalChecker.openNotificationSettings(context)
        }
    }

    val requestOrOpenNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val opened = RomSurvivalChecker.openNotificationSettings(context)
            if (!opened) {
                Toast.makeText(context, "未能直接打开系统通知设置页，请前往系统设置手动开启", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val handleSurvivalAction: (SurvivalCheckId) -> Unit = { checkId ->
        when (checkId) {
            SurvivalCheckId.NOTIFICATIONS -> requestOrOpenNotifications()
            SurvivalCheckId.BATTERY_OPTIMIZATION -> {
                if (!RomSurvivalChecker.openBatteryOptimizationSettings(context)) {
                    Toast.makeText(context, "未能打开系统省电设置，请在系统设置中放行本应用", Toast.LENGTH_SHORT).show()
                }
            }
            SurvivalCheckId.EXACT_ALARM -> {
                if (!RomSurvivalChecker.openExactAlarmSettings(context)) {
                    Toast.makeText(context, "未能打开精确闹钟权限设置页", Toast.LENGTH_SHORT).show()
                }
            }
            SurvivalCheckId.MIUI_AUTOSTART_AND_BACKGROUND -> {
                if (!RomSurvivalChecker.openMiuiAutostartSettings(context)) {
                    Toast.makeText(context, "未能打开自启动设置页，请在系统设置中允许自启动", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    SettingsScreen(
        uiState = uiState,
        onAccountEnabledChanged = viewModel::onAccountEnabledChanged,
        onSendWindowStartChanged = viewModel::onSendWindowStartChanged,
        onSendWindowEndChanged = viewModel::onSendWindowEndChanged,
        onSendTimeHhmmChanged = viewModel::onSendTimeHhmmChanged,
        onJitterMaxMinutesChanged = viewModel::onJitterMaxMinutesChanged,
        onCooldownMinSecChanged = viewModel::onCooldownMinSecChanged,
        onCooldownMaxSecChanged = viewModel::onCooldownMaxSecChanged,
        onSaveScheduleConfig = viewModel::saveScheduleConfig,
        onResetScheduleDraft = viewModel::resetScheduleDraftFromSaved,
        onBarkInputChanged = viewModel::onBarkInputChanged,
        onSaveBarkConfig = viewModel::saveBarkConfig,
        onClearBarkConfig = viewModel::clearBarkConfig,
        onSendBarkTestProbe = viewModel::sendBarkTestProbe,
        onRequestOrOpenNotifications = requestOrOpenNotifications,
        onOpenChannelSettings = { channelId ->
            if (!RomSurvivalChecker.openNotificationChannelSettings(context, channelId)) {
                Toast.makeText(context, "未能打开通知通道设置页", Toast.LENGTH_SHORT).show()
            }
        },
        onOpenSurvivalAction = handleSurvivalAction,
        onOpenMiuiPowerHideSettings = {
            if (!RomSurvivalChecker.openMiuiPowerHideSettings(context)) {
                Toast.makeText(context, "未能打开神隐/省电设置页", Toast.LENGTH_SHORT).show()
            }
        },
        onAutostartConfirmedChanged = viewModel::setAutostartGuidanceConfirmed,
        onRefreshSurvivalChecks = viewModel::refreshSurvivalChecks,
        onTriggerStructureProbe = viewModel::triggerStructureProbe,
        onOpenProbeReportDialog = viewModel::openProbeReportDialog,
        onDismissProbeDialog = viewModel::dismissProbeDialog,
        onConfirmRecoverDomBroken = viewModel::confirmRecoverDomBrokenFromProbe,
        onOpenOverrideEditor = viewModel::openOverrideEditor,
        onDismissOverrideDialog = viewModel::dismissOverrideDialog,
        onOverrideJsonInputChanged = viewModel::onOverrideJsonInputChanged,
        onSaveSelectorOverrides = viewModel::saveSelectorOverrides,
        onClearSelectorOverrides = viewModel::clearSelectorOverrides,
        onGenerateDiagnosticsReport = viewModel::generateDiagnosticsReport,
        onDismissDiagnosticsDialog = viewModel::dismissDiagnosticsDialog,
        onCopyDiagnostics = { text -> copyDiagnosticsToClipboard(context, text) },
        onShareDiagnostics = { text -> shareDiagnosticsText(context, text) },
        modifier = modifier,
    )
}

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onAccountEnabledChanged: (Boolean) -> Unit,
    onSendWindowStartChanged: (String) -> Unit,
    onSendWindowEndChanged: (String) -> Unit,
    onSendTimeHhmmChanged: (String) -> Unit,
    onJitterMaxMinutesChanged: (String) -> Unit,
    onCooldownMinSecChanged: (String) -> Unit,
    onCooldownMaxSecChanged: (String) -> Unit,
    onSaveScheduleConfig: () -> Unit,
    onResetScheduleDraft: () -> Unit,
    onBarkInputChanged: (String) -> Unit,
    onSaveBarkConfig: () -> Unit,
    onClearBarkConfig: () -> Unit,
    onSendBarkTestProbe: () -> Unit,
    onRequestOrOpenNotifications: () -> Unit,
    onOpenChannelSettings: (String) -> Unit,
    onOpenSurvivalAction: (SurvivalCheckId) -> Unit,
    onOpenMiuiPowerHideSettings: () -> Unit,
    onAutostartConfirmedChanged: (Boolean) -> Unit,
    onRefreshSurvivalChecks: () -> Unit,
    onTriggerStructureProbe: () -> Unit,
    onOpenProbeReportDialog: () -> Unit,
    onDismissProbeDialog: () -> Unit,
    onConfirmRecoverDomBroken: () -> Unit,
    onOpenOverrideEditor: () -> Unit,
    onDismissOverrideDialog: () -> Unit,
    onOverrideJsonInputChanged: (String) -> Unit,
    onSaveSelectorOverrides: () -> Unit,
    onClearSelectorOverrides: () -> Unit,
    onGenerateDiagnosticsReport: () -> Unit,
    onDismissDiagnosticsDialog: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
    onShareDiagnostics: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsPageHeader(uiState = uiState)

            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        ScheduleSettingsCard(
                            uiState = uiState,
                            onAccountEnabledChanged = onAccountEnabledChanged,
                            onSendWindowStartChanged = onSendWindowStartChanged,
                            onSendWindowEndChanged = onSendWindowEndChanged,
                            onSendTimeHhmmChanged = onSendTimeHhmmChanged,
                            onJitterMaxMinutesChanged = onJitterMaxMinutesChanged,
                            onCooldownMinSecChanged = onCooldownMinSecChanged,
                            onCooldownMaxSecChanged = onCooldownMaxSecChanged,
                            onSaveScheduleConfig = onSaveScheduleConfig,
                            onResetScheduleDraft = onResetScheduleDraft,
                        )
                        NotificationsAndBarkCard(
                            uiState = uiState,
                            onBarkInputChanged = onBarkInputChanged,
                            onSaveBarkConfig = onSaveBarkConfig,
                            onClearBarkConfig = onClearBarkConfig,
                            onSendBarkTestProbe = onSendBarkTestProbe,
                            onRequestOrOpenNotifications = onRequestOrOpenNotifications,
                            onOpenChannelSettings = onOpenChannelSettings,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        RomSurvivalCard(
                            uiState = uiState,
                            onOpenSurvivalAction = onOpenSurvivalAction,
                            onOpenMiuiPowerHideSettings = onOpenMiuiPowerHideSettings,
                            onAutostartConfirmedChanged = onAutostartConfirmedChanged,
                            onRefreshSurvivalChecks = onRefreshSurvivalChecks,
                        )
                        DiagnosticsAndSelectorsCard(
                            uiState = uiState,
                            onTriggerStructureProbe = onTriggerStructureProbe,
                            onOpenProbeReportDialog = onOpenProbeReportDialog,
                            onOpenOverrideEditor = onOpenOverrideEditor,
                            onClearSelectorOverrides = onClearSelectorOverrides,
                            onGenerateDiagnosticsReport = onGenerateDiagnosticsReport,
                        )
                    }
                }
            } else {
                ScheduleSettingsCard(
                    uiState = uiState,
                    onAccountEnabledChanged = onAccountEnabledChanged,
                    onSendWindowStartChanged = onSendWindowStartChanged,
                    onSendWindowEndChanged = onSendWindowEndChanged,
                    onSendTimeHhmmChanged = onSendTimeHhmmChanged,
                    onJitterMaxMinutesChanged = onJitterMaxMinutesChanged,
                    onCooldownMinSecChanged = onCooldownMinSecChanged,
                    onCooldownMaxSecChanged = onCooldownMaxSecChanged,
                    onSaveScheduleConfig = onSaveScheduleConfig,
                    onResetScheduleDraft = onResetScheduleDraft,
                )
                NotificationsAndBarkCard(
                    uiState = uiState,
                    onBarkInputChanged = onBarkInputChanged,
                    onSaveBarkConfig = onSaveBarkConfig,
                    onClearBarkConfig = onClearBarkConfig,
                    onSendBarkTestProbe = onSendBarkTestProbe,
                    onRequestOrOpenNotifications = onRequestOrOpenNotifications,
                    onOpenChannelSettings = onOpenChannelSettings,
                )
                RomSurvivalCard(
                    uiState = uiState,
                    onOpenSurvivalAction = onOpenSurvivalAction,
                    onOpenMiuiPowerHideSettings = onOpenMiuiPowerHideSettings,
                    onAutostartConfirmedChanged = onAutostartConfirmedChanged,
                    onRefreshSurvivalChecks = onRefreshSurvivalChecks,
                )
                DiagnosticsAndSelectorsCard(
                    uiState = uiState,
                    onTriggerStructureProbe = onTriggerStructureProbe,
                    onOpenProbeReportDialog = onOpenProbeReportDialog,
                    onOpenOverrideEditor = onOpenOverrideEditor,
                    onClearSelectorOverrides = onClearSelectorOverrides,
                    onGenerateDiagnosticsReport = onGenerateDiagnosticsReport,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (uiState.showProbeDialog) {
        StructureProbeReportDialog(
            uiState = uiState,
            onDismiss = onDismissProbeDialog,
            onRetrigger = onTriggerStructureProbe,
            onConfirmRecoverDomBroken = onConfirmRecoverDomBroken,
        )
    }

    if (uiState.showOverrideDialog) {
        SelectorOverrideEditorDialog(
            uiState = uiState,
            onDismiss = onDismissOverrideDialog,
            onJsonChanged = onOverrideJsonInputChanged,
            onSave = onSaveSelectorOverrides,
        )
    }

    val diagnostics = uiState.diagnosticsReport
    if (uiState.showDiagnosticsDialog && diagnostics != null) {
        SanitizedDiagnosticsDialog(
            report = diagnostics,
            onDismiss = onDismissDiagnosticsDialog,
            onCopy = onCopyDiagnostics,
            onShare = onShareDiagnostics,
        )
    }
}

@Composable
private fun SettingsPageHeader(uiState: SettingsUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "设置与诊断维护",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "同日调度、双通道推送、HyperOS 保活与只读诊断",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusChip(
            label = accountStatusLabel(uiState.accountStatus),
            isHealthy = uiState.accountStatus == AccountStatus.HEALTHY,
            isError = uiState.accountStatus == AccountStatus.DOM_BROKEN ||
                uiState.accountStatus == AccountStatus.AUTH_EXPIRED ||
                uiState.accountStatus == AccountStatus.VERIFICATION_REQUIRED ||
                uiState.accountStatus == AccountStatus.COOLDOWN,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSettingsCard(
    uiState: SettingsUiState,
    onAccountEnabledChanged: (Boolean) -> Unit,
    onSendWindowStartChanged: (String) -> Unit,
    onSendWindowEndChanged: (String) -> Unit,
    onSendTimeHhmmChanged: (String) -> Unit,
    onJitterMaxMinutesChanged: (String) -> Unit,
    onCooldownMinSecChanged: (String) -> Unit,
    onCooldownMaxSecChanged: (String) -> Unit,
    onSaveScheduleConfig: () -> Unit,
    onResetScheduleDraft: () -> Unit,
) {
    SectionCard(
        icon = Icons.Outlined.Schedule,
        title = "定时调度与冷却",
        subtitle = "每日严格限制在同一自然日内发送，不支持跨午夜窗口",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "每日自动定时续火",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "本地时区：${uiState.accountTimezone}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.enabledInput,
                onCheckedChange = onAccountEnabledChanged,
            )
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = uiState.sendWindowStartInput,
                onValueChange = onSendWindowStartChanged,
                label = { Text("窗口开始 (HH:mm)") },
                placeholder = { Text("20:00") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = uiState.sendWindowEndInput,
                onValueChange = onSendWindowEndChanged,
                label = { Text("窗口结束 (<=23:55)") },
                placeholder = { Text("22:00") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = uiState.sendTimeHhmmInput,
                onValueChange = onSendTimeHhmmChanged,
                label = { Text("账号固定时刻 (可选)") },
                placeholder = { Text("留空按窗口") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = uiState.jitterMaxMinutesInput,
                onValueChange = onJitterMaxMinutesChanged,
                label = { Text("随机抖动上限 (0–15分)") },
                placeholder = { Text("15") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = uiState.cooldownMinSecInput,
                onValueChange = onCooldownMinSecChanged,
                label = { Text("冷却下限 (秒)") },
                placeholder = { Text("3") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = uiState.cooldownMaxSecInput,
                onValueChange = onCooldownMaxSecChanged,
                label = { Text("冷却上限 (秒)") },
                placeholder = { Text("8") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        uiState.scheduleError?.let { err ->
            FeedbackBanner(message = err, isError = true)
        }
        uiState.scheduleMessage?.let { msg ->
            FeedbackBanner(message = msg, isError = false)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onSaveScheduleConfig,
                enabled = !uiState.isSavingSchedule,
            ) {
                if (uiState.isSavingSchedule) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(if (uiState.hasUnsavedScheduleChanges) "保存调度配置 *" else "保存调度配置")
            }
            if (uiState.hasUnsavedScheduleChanges) {
                OutlinedButton(
                    onClick = onResetScheduleDraft,
                    enabled = !uiState.isSavingSchedule,
                ) {
                    Text("还原")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationsAndBarkCard(
    uiState: SettingsUiState,
    onBarkInputChanged: (String) -> Unit,
    onSaveBarkConfig: () -> Unit,
    onClearBarkConfig: () -> Unit,
    onSendBarkTestProbe: () -> Unit,
    onRequestOrOpenNotifications: () -> Unit,
    onOpenChannelSettings: (String) -> Unit,
) {
    val survival = uiState.survivalSnapshot
    SectionCard(
        icon = Icons.Outlined.NotificationsActive,
        title = "本机通知与 Bark 推送",
        subtitle = "Bark 密钥经 Android Keystore AES-GCM 加密落盘，界面仅回显脱敏摘要",
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "本机通知与分类通道",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    StatusChip(
                        label = if (survival.notificationsGranted) "已授权" else "未授权",
                        isHealthy = survival.notificationsGranted,
                        isError = !survival.notificationsGranted,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = onRequestOrOpenNotifications,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(if (survival.notificationsGranted) "系统通知总设置" else "开启通知权限")
                    }
                    OutlinedButton(
                        onClick = { onOpenChannelSettings(LocalNotifier.CHANNEL_DAILY_SUMMARY) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            if (survival.dailySummaryChannelEnabled) "每日汇总通道 ✓" else "每日汇总通道 (已关)",
                        )
                    }
                    OutlinedButton(
                        onClick = { onOpenChannelSettings(LocalNotifier.CHANNEL_ALERTS_URGENT) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            if (survival.urgentAlertsChannelEnabled) "高优告警通道 ✓" else "高优告警通道 (已关)",
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Bark 远程推送",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            StatusChip(
                label = if (uiState.barkConfigured) "已加密保存" else "未配置",
                isHealthy = uiState.barkConfigured,
                isError = false,
            )
        }

        uiState.maskedBarkUrl?.let { masked ->
            Text(
                text = "当前已生效地址（脱敏）：$masked",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = uiState.barkInputText,
            onValueChange = onBarkInputChanged,
            label = { Text("Bark 地址或整段 curl 命令") },
            placeholder = { Text("https://api.day.app/<key> 或直接粘贴 Bark App 复制的 curl") },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )

        uiState.barkError?.let { err ->
            FeedbackBanner(message = err, isError = true)
        }
        uiState.barkMessage?.let { msg ->
            FeedbackBanner(message = msg, isError = false)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onSaveBarkConfig,
                enabled = !uiState.isSavingBark && !uiState.isProbingBark,
            ) {
                Text("保存并加密")
            }
            OutlinedButton(
                onClick = onSendBarkTestProbe,
                enabled = uiState.barkConfigured && !uiState.isSavingBark && !uiState.isProbingBark,
            ) {
                if (uiState.isProbingBark) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("发送测试推送")
            }
            if (uiState.barkConfigured || uiState.barkInputText.isNotEmpty()) {
                TextButton(
                    onClick = onClearBarkConfig,
                    enabled = !uiState.isSavingBark && !uiState.isProbingBark,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("清空 Bark")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RomSurvivalCard(
    uiState: SettingsUiState,
    onOpenSurvivalAction: (SurvivalCheckId) -> Unit,
    onOpenMiuiPowerHideSettings: () -> Unit,
    onAutostartConfirmedChanged: (Boolean) -> Unit,
    onRefreshSurvivalChecks: () -> Unit,
) {
    val snapshot = uiState.survivalSnapshot
    SectionCard(
        icon = Icons.Outlined.Security,
        title = "后台稳定性体检",
        subtitle = if (snapshot.isXiaomiOrHyperOs) {
            "检测到 ${snapshot.manufacturer} 设备（MIUI / HyperOS），已启用专项自启动与神隐模式引导"
        } else {
            "当前机型：${snapshot.manufacturer} · 核对 4 项后台定时与通知保活策略"
        },
        headerTrailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusChip(
                    label = "${snapshot.passedCount}/${snapshot.totalCount}",
                    isHealthy = snapshot.passedCount == snapshot.totalCount,
                    isError = !snapshot.coreAllPassed,
                )
                TextButton(
                    onClick = onRefreshSurvivalChecks,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "重新检测",
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("刷新")
                }
            }
        },
    ) {
        for (item in snapshot.items) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (item.passed) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (item.passed) {
                                    Icons.Outlined.CheckCircle
                                } else {
                                    Icons.Outlined.WarningAmber
                                },
                                contentDescription = null,
                                tint = if (item.passed) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        StatusChip(
                            label = item.statusLabel,
                            isHealthy = item.passed,
                            isError = !item.passed && !item.advisoryOnly,
                        )
                    }

                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onOpenSurvivalAction(item.id) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(item.actionLabel)
                        }
                        if (item.id == SurvivalCheckId.MIUI_AUTOSTART_AND_BACKGROUND && snapshot.isXiaomiOrHyperOs) {
                            OutlinedButton(
                                onClick = onOpenMiuiPowerHideSettings,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text("神隐 / 省电策略")
                            }
                        }
                    }

                    if (item.id == SurvivalCheckId.MIUI_AUTOSTART_AND_BACKGROUND && snapshot.isXiaomiOrHyperOs) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = item.passed,
                                onCheckedChange = onAutostartConfirmedChanged,
                            )
                            Text(
                                text = "我已在系统设置开启「自启动」并设为省电「无限制」",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticsAndSelectorsCard(
    uiState: SettingsUiState,
    onTriggerStructureProbe: () -> Unit,
    onOpenProbeReportDialog: () -> Unit,
    onOpenOverrideEditor: () -> Unit,
    onClearSelectorOverrides: () -> Unit,
    onGenerateDiagnosticsReport: () -> Unit,
) {
    SectionCard(
        icon = Icons.Outlined.Tune,
        title = "网页结构体检、定位器与脱敏诊断",
        subtitle = "所有诊断严格只读与脱敏，绝不自动写入聊天或导出个人隐私",
    ) {
        if (uiState.accountStatus == AccountStatus.DOM_BROKEN) {
            FeedbackBanner(
                message = "当前账号处于页面结构异常（DOM_BROKEN）。请运行「网页结构体检」或保存有效「定位器覆写」以恢复运行。",
                isError = true,
            )
        }
        uiState.domRecoveryMessage?.let { msg ->
            FeedbackBanner(message = msg, isError = false)
        }

        // 1. 只读网页结构体检
        Text(
            text = "网页结构体检（只读）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        if (uiState.isProbeRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        uiState.probeRuntimeMessage?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onTriggerStructureProbe,
                enabled = !uiState.isProbeRunning,
            ) {
                Icon(
                    imageVector = Icons.Outlined.VerifiedUser,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (uiState.isProbeRunning) "正在体检…" else "运行网页结构体检")
            }
            if (uiState.latestProbeReport != null) {
                OutlinedButton(onClick = onOpenProbeReportDialog) {
                    Text("查看体检报告")
                }
            }
        }

        HorizontalDivider()

        // 2. 定位器摘要与热覆写
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "定位器基线与本地覆写",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "12 位摘要：${uiState.selectorDigest.ifEmpty { "加载中" }}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(
                label = if (uiState.hasSelectorOverrides) "已启用本地覆写" else "默认基线",
                isHealthy = true,
                isError = false,
            )
        }
        uiState.selectorMessage?.let { msg ->
            FeedbackBanner(message = msg, isError = false)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onOpenOverrideEditor,
                enabled = !uiState.isSavingOverrides,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Code,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("编辑覆写 JSON")
            }
            if (uiState.hasSelectorOverrides) {
                TextButton(
                    onClick = onClearSelectorOverrides,
                    enabled = !uiState.isSavingOverrides,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("恢复默认")
                }
            }
        }

        HorizontalDivider()

        // 3. 脱敏诊断报告导出
        Text(
            text = "脱敏诊断导出",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "仅导出应用与 WebView 版本、定位器摘要、账号状态枚举、保活状态及尝试计数；绝不包含昵称、UID、Cookie、消息正文与完整 Bark Key。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        uiState.diagnosticsError?.let { err ->
            FeedbackBanner(message = err, isError = true)
        }
        Button(
            onClick = onGenerateDiagnosticsReport,
            enabled = !uiState.isBuildingDiagnostics,
        ) {
            Icon(
                imageVector = Icons.Outlined.BugReport,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (uiState.isBuildingDiagnostics) "正在生成…" else "导出脱敏诊断报告")
        }
    }
}

@Composable
private fun StructureProbeReportDialog(
    uiState: SettingsUiState,
    onDismiss: () -> Unit,
    onRetrigger: () -> Unit,
    onConfirmRecoverDomBroken: () -> Unit,
) {
    val report = uiState.latestProbeReport
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网页只读结构体检") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "严格只读探测页面元素，绝不写入字符或点击发送。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (uiState.isProbeRunning && report == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = uiState.probeRuntimeMessage ?: "正在后台离屏 WebView 核对页面结构…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else if (report == null) {
                    Text(
                        text = uiState.probeRuntimeMessage ?: "暂未收到体检报告。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    FeedbackBanner(
                        message = report.summary,
                        isError = !report.coreSkeletonOk,
                    )
                    Text(
                        text = "登录态：${if (report.loggedIn) "已登录" else "未登录"} | " +
                            "核心骨架：${if (report.coreSkeletonOk) "正常" else "异常"} | " +
                            "摘要：${report.bundleDigest}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (report.webViewVersion.isNotBlank()) {
                        Text(
                            text = "WebView：${report.webViewVersion} · 时间：${formatEpochMs(report.checkedAtEpochMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    for (item in report.items) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = if (item.hit) {
                                    Icons.Outlined.CheckCircle
                                } else {
                                    Icons.Outlined.ErrorOutline
                                },
                                contentDescription = null,
                                tint = if (item.hit) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(16.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${item.label} (${item.key})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = buildString {
                                        append(if (item.hit) "命中 (${item.matchedCount})" else "未命中")
                                        item.detail?.takeIf { it.isNotBlank() }?.let { d ->
                                            append(" · ").append(d)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (report != null && report.coreSkeletonOk && uiState.accountStatus == AccountStatus.DOM_BROKEN) {
                Button(onClick = onConfirmRecoverDomBroken) {
                    Text("确认恢复运行")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!uiState.isProbeRunning) {
                    TextButton(onClick = onRetrigger) {
                        Text("重新体检")
                    }
                }
                if (report != null && report.coreSkeletonOk && uiState.accountStatus == AccountStatus.DOM_BROKEN) {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        },
    )
}

@Composable
private fun SelectorOverrideEditorDialog(
    uiState: SettingsUiState,
    onDismiss: () -> Unit,
    onJsonChanged: (String) -> Unit,
    onSave: () -> Unit,
) {
    var showReference by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑定位器覆写 JSON") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "锁定规则：CHAT_URL 不可修改；仅允许已知定位器键名，禁止换行/控制字符，单串不超 512 字符，数组 1–20 项。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = uiState.overrideJsonInput,
                    onValueChange = onJsonChanged,
                    label = { Text("覆写 JSON 对象") },
                    minLines = 6,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                uiState.overrideError?.let { err ->
                    FeedbackBanner(message = err, isError = true)
                }
                TextButton(onClick = { showReference = !showReference }) {
                    Text(if (showReference) "收起内置基线参考" else "查看内置基线键名与默认值")
                }
                if (showReference && uiState.overrideBaselineReferenceJson.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SelectionContainer {
                            Text(
                                text = uiState.overrideBaselineReferenceJson,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !uiState.isSavingOverrides,
            ) {
                Text(if (uiState.isSavingOverrides) "校验保存中…" else "校验并保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !uiState.isSavingOverrides) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun SanitizedDiagnosticsDialog(
    report: SanitizedDiagnosticsReport,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    var showJsonFormat by remember { mutableStateOf(false) }
    val activePayload = if (showJsonFormat) report.jsonReport else report.textReport

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("脱敏诊断报告") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!showJsonFormat) {
                        Button(
                            onClick = { showJsonFormat = false },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("可读摘要")
                        }
                        OutlinedButton(
                            onClick = { showJsonFormat = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("JSON")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { showJsonFormat = false },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("可读摘要")
                        }
                        Button(
                            onClick = { showJsonFormat = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("JSON")
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 360.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = activePayload,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCopy(activePayload) }) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("复制")
                }
                Button(onClick = { onShare(activePayload) }) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("分享")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    headerTrailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (headerTrailing != null) {
                    headerTrailing()
                }
            }
            content()
        }
    }
}

@Composable
private fun FeedbackBanner(
    message: String,
    isError: Boolean,
) {
    val containerColor: Color = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor: Color = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatusChip(
    label: String,
    isHealthy: Boolean,
    isError: Boolean,
) {
    val bgColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        isHealthy -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val fgColor = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        isHealthy -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = bgColor,
        contentColor = fgColor,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun accountStatusLabel(status: AccountStatus): String = when (status) {
    AccountStatus.PENDING_LOGIN -> "待扫码登录"
    AccountStatus.HEALTHY -> "健康"
    AccountStatus.RUNNING -> "执行中"
    AccountStatus.PAUSED -> "已暂停"
    AccountStatus.AUTH_EXPIRED -> "登录已失效"
    AccountStatus.VERIFICATION_REQUIRED -> "需安全验证"
    AccountStatus.DOM_BROKEN -> "页面结构异常"
    AccountStatus.COOLDOWN -> "连续失败熔断"
}

private fun formatEpochMs(epochMs: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

private fun copyDiagnosticsToClipboard(context: Context, text: String) {
    try {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("flame_sanitized_diagnostics", text))
            Toast.makeText(context, "已复制脱敏诊断报告到剪贴板", Toast.LENGTH_SHORT).show()
        }
    } catch (_: Throwable) {
        Toast.makeText(context, "复制剪贴板失败", Toast.LENGTH_SHORT).show()
    }
}

private fun shareDiagnosticsText(context: Context, text: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "抖音续火工脱敏诊断报告")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(sendIntent, "分享脱敏诊断报告")
    if (context !is Activity) {
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "未找到可用的文本分享应用", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "系统阻止打开分享选择器", Toast.LENGTH_SHORT).show()
    }
}
