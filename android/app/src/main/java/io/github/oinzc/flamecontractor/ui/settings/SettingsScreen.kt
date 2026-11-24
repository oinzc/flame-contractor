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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oinzc.flamecontractor.core.backup.BackupArchiveCodec
import io.github.oinzc.flamecontractor.core.backup.BackupCodec
import io.github.oinzc.flamecontractor.core.backup.BackupManager
import io.github.oinzc.flamecontractor.core.backup.RoomBackupStore
import io.github.oinzc.flamecontractor.core.notify.BarkNotifier
import io.github.oinzc.flamecontractor.core.notify.EncryptedSecretStore
import io.github.oinzc.flamecontractor.core.selectors.SelectorBundleStore
import io.github.oinzc.flamecontractor.core.theme.ThemeMode
import io.github.oinzc.flamecontractor.core.theme.ThemePreferencesStore
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.FlameDatabase
import io.github.oinzc.flamecontractor.data.db.ScanRunEntity
import io.github.oinzc.flamecontractor.data.db.ScanRunStatus
import io.github.oinzc.flamecontractor.scheduler.DailySchedulePlanner
import io.github.oinzc.flamecontractor.scheduler.ScheduleCoordinator
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.rememberReducedMotion
import io.github.oinzc.flamecontractor.ui.components.resolveFlameToneColors
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsRoute(
    database: FlameDatabase,
    selectorBundleStore: SelectorBundleStore,
    secretStore: EncryptedSecretStore,
    barkNotifier: BarkNotifier,
    scheduleCoordinator: ScheduleCoordinator,
    themePreferencesStore: ThemePreferencesStore? = null,
    activeSection: SettingsSection? = null,
    onSelectSection: (SettingsSection?) -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    backupManager: BackupManager = BackupManager(
        store = RoomBackupStore(database),
        onScheduleChanged = { scheduleCoordinator.rescheduleAll() },
    ),
    accountId: String = DEFAULT_ACCOUNT_ID,
) {
    val context = LocalContext.current
    val resolvedAccountId = accountId.trim().ifEmpty { DEFAULT_ACCOUNT_ID }
    val resolvedThemeStore = remember(context, themePreferencesStore) {
        themePreferencesStore ?: ThemePreferencesStore.fromContext(context)
    }
    val themePreference by resolvedThemeStore.preference.collectAsState()

    val factory = remember(
        context,
        database,
        selectorBundleStore,
        secretStore,
        barkNotifier,
        scheduleCoordinator,
        backupManager,
        resolvedAccountId,
    ) {
        SettingsViewModel.factory(
            context = context,
            database = database,
            selectorBundleStore = selectorBundleStore,
            secretStore = secretStore,
            barkNotifier = barkNotifier,
            scheduleCoordinator = scheduleCoordinator,
            backupManager = backupManager,
            accountId = resolvedAccountId,
        )
    }
    val viewModel: SettingsViewModel = viewModel(
        key = "settings_$resolvedAccountId",
        factory = factory,
    )
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var isSafIoBusy by remember(resolvedAccountId) { mutableStateOf(false) }

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

    val exportZipBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isSafIoBusy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    val outputStream = try {
                        context.contentResolver.openOutputStream(uri, "wt")
                    } catch (_: Throwable) {
                        null
                    } ?: context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("无法打开目标文件输出流")
                    outputStream.use { stream ->
                        viewModel.exportBackupZipToStream(stream)
                    }
                }
                viewModel.onBackupZipExportCompleted(result)
            } catch (ce: CancellationException) {
                throw ce
            } catch (exc: Throwable) {
                viewModel.onBackupExportFailed(
                    exc.message?.takeIf { it.isNotBlank() }
                        ?: "导出完整备份失败（${exc.javaClass.simpleName}）",
                )
            } finally {
                isSafIoBusy = false
            }
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isSafIoBusy = true
            try {
                val json = viewModel.prepareExportBackupJson()
                withContext(Dispatchers.IO) {
                    val outputStream = try {
                        context.contentResolver.openOutputStream(uri, "wt")
                    } catch (_: Throwable) {
                        null
                    } ?: context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("无法打开目标文件输出流")
                    outputStream.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                        stream.flush()
                    }
                }
                viewModel.onBackupExportCompleted()
            } catch (ce: CancellationException) {
                throw ce
            } catch (exc: Throwable) {
                viewModel.onBackupExportFailed(
                    exc.message?.takeIf { it.isNotBlank() }
                        ?: "导出备份失败（${exc.javaClass.simpleName}）",
                )
            } finally {
                isSafIoBusy = false
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isSafIoBusy = true
            try {
                val payload = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BackupArchiveCodec.readArchiveOrJson(stream)
                    } ?: throw IOException("无法读取所选备份文件")
                }
                viewModel.inspectBackupPayload(payload)
            } catch (ce: CancellationException) {
                throw ce
            } catch (exc: Throwable) {
                viewModel.onBackupReadFailed(
                    exc.message?.takeIf { it.isNotBlank() }
                        ?: "读取备份文件失败（${exc.javaClass.simpleName}）",
                )
            } finally {
                isSafIoBusy = false
            }
        }
    }

    val launchZipBackupExport: () -> Unit = {
        val zoneId = DailySchedulePlanner.resolveZoneId(uiState.accountTimezone)
        val suggestedName = BackupArchiveCodec.defaultZipBackupFileName(LocalDate.now(zoneId))
        try {
            exportZipBackupLauncher.launch(suggestedName)
        } catch (_: ActivityNotFoundException) {
            viewModel.onBackupExportFailed("未找到系统文件保存选择器")
        }
    }

    val launchJsonBackupExport: () -> Unit = {
        val zoneId = DailySchedulePlanner.resolveZoneId(uiState.accountTimezone)
        val suggestedName = BackupCodec.defaultBackupFileName(LocalDate.now(zoneId))
        try {
            exportBackupLauncher.launch(suggestedName)
        } catch (_: ActivityNotFoundException) {
            viewModel.onBackupExportFailed("未找到系统文件保存选择器")
        }
    }

    val launchBackupImport: () -> Unit = {
        try {
            importBackupLauncher.launch(
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/json",
                    "text/plain",
                    "*/*",
                ),
            )
        } catch (_: ActivityNotFoundException) {
            viewModel.onBackupReadFailed("未找到系统文件选择器")
        }
    }

    val openChannelSettings: (String) -> Unit = { channelId ->
        if (!RomSurvivalChecker.openNotificationChannelSettings(context, channelId)) {
            Toast.makeText(context, "未能打开通知通道设置页", Toast.LENGTH_SHORT).show()
        }
    }

    val openMiuiPowerHideSettings: () -> Unit = {
        if (!RomSurvivalChecker.openMiuiPowerHideSettings(context)) {
            Toast.makeText(context, "未能打开神隐/省电设置页", Toast.LENGTH_SHORT).show()
        }
    }

    val reducedMotion = rememberReducedMotion()
    AnimatedContent(
        targetState = activeSection,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            when {
                reducedMotion -> EnterTransition.None togetherWith ExitTransition.None
                targetState != null && initialState == null -> {
                    (fadeIn(
                        animationSpec = tween(
                            durationMillis = FlameMotionSpec.SUBPAGE_ENTER_MS,
                            easing = FlameMotionSpec.easeOut,
                        ),
                    ) + slideInHorizontally(
                        animationSpec = tween(
                            durationMillis = FlameMotionSpec.SUBPAGE_ENTER_MS,
                            easing = FlameMotionSpec.easeOut,
                        ),
                        initialOffsetX = { it / FlameMotionSpec.SUBPAGE_SLIDE_FRACTION },
                    )) togetherWith (fadeOut(
                        animationSpec = tween(
                            durationMillis = FlameMotionSpec.SUBPAGE_EXIT_MS,
                            easing = FlameMotionSpec.easeOut,
                        ),
                    ) + slideOutHorizontally(
                        animationSpec = tween(
                            durationMillis = FlameMotionSpec.SUBPAGE_EXIT_MS,
                            easing = FlameMotionSpec.easeOut,
                        ),
                        targetOffsetX = { -it / (FlameMotionSpec.SUBPAGE_SLIDE_FRACTION * 2) },
                    ))
                }

                targetState == null && initialState != null -> {
                    (fadeIn(
                        animationSpec = tween(
                            durationMillis = FlameMotionSpec.SUBPAGE_ENTER_MS,
                            easing = FlameMotionSpec.easeOut,
                        ),
                    ) + slideInHorizontally(
                        animationSpec = tween(
                            durationMillis = FlameMotionSpec.SUBPAGE_ENTER_MS,
                            easing = FlameMotionSpec.easeOut,
                        ),
                        initialOffsetX = { -it / (FlameMotionSpec.SUBPAGE_SLIDE_FRACTION * 2) },
                    )) togetherWith (fadeOut(
                        animationSpec = tween(
                            durationMillis = FlameMotionSpec.SUBPAGE_EXIT_MS,
                            easing = FlameMotionSpec.easeOut,
                        ),
                    ) + slideOutHorizontally(
                        animationSpec = tween(
                            durationMillis = FlameMotionSpec.SUBPAGE_EXIT_MS,
                            easing = FlameMotionSpec.easeOut,
                        ),
                        targetOffsetX = { it / FlameMotionSpec.SUBPAGE_SLIDE_FRACTION },
                    ))
                }

                else -> {
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = 160,
                            easing = FlameMotionSpec.easeOut,
                        ),
                    ) togetherWith fadeOut(
                        animationSpec = tween(
                            durationMillis = 130,
                            easing = FlameMotionSpec.easeOut,
                        ),
                    )
                }
            }
        },
        label = "settings_section_transition",
    ) { section ->
        when (section) {
            null -> SettingsHubScreen(
                uiState = uiState,
                themePreference = themePreference,
                onSelectSection = { target -> onSelectSection(target) },
                onBack = onBack,
            )

            SettingsSection.APPEARANCE -> SettingsAppearancePage(
                themePreference = themePreference,
                onSelectThemeMode = resolvedThemeStore::setThemeMode,
                onBack = onBack,
            )

            SettingsSection.SCHEDULE -> SettingsSchedulePage(
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
                onAutoScanEnabledChanged = viewModel::onAutoScanEnabledChanged,
                onAutoScanIntervalHoursChanged = viewModel::onAutoScanIntervalHoursChanged,
                onSelectAutoScanPresetHours = viewModel::selectAutoScanPresetHours,
                onSaveAutoScanConfig = viewModel::saveAutoScanConfig,
                onResetAutoScanDraft = viewModel::resetAutoScanDraftFromSaved,
                onBack = onBack,
            )

            SettingsSection.NOTIFICATIONS -> SettingsNotificationsPage(
                uiState = uiState,
                onBarkInputChanged = viewModel::onBarkInputChanged,
                onSaveBarkConfig = viewModel::saveBarkConfig,
                onClearBarkConfig = viewModel::clearBarkConfig,
                onSendBarkTestProbe = viewModel::sendBarkTestProbe,
                onRequestOrOpenNotifications = requestOrOpenNotifications,
                onOpenChannelSettings = openChannelSettings,
                onBack = onBack,
            )

            SettingsSection.SURVIVAL -> SettingsSurvivalPage(
                uiState = uiState,
                onOpenSurvivalAction = handleSurvivalAction,
                onOpenMiuiPowerHideSettings = openMiuiPowerHideSettings,
                onAutostartConfirmedChanged = viewModel::setAutostartGuidanceConfirmed,
                onRefreshSurvivalChecks = viewModel::refreshSurvivalChecks,
                onBack = onBack,
            )

            SettingsSection.BACKUP -> SettingsBackupPage(
                uiState = uiState,
                isBackupSafBusy = isSafIoBusy,
                onLaunchZipBackupExport = launchZipBackupExport,
                onLaunchJsonBackupExport = launchJsonBackupExport,
                onLaunchBackupImport = launchBackupImport,
                onSelectBackupAccount = viewModel::onSelectBackupAccount,
                onIncludeBackupHistoryChanged = viewModel::onIncludeBackupHistoryChanged,
                onConfirmImportBackup = viewModel::confirmImportBackup,
                onDismissBackupPreview = viewModel::dismissBackupPreviewDialog,
                onBack = onBack,
            )

            SettingsSection.DIAGNOSTICS -> SettingsDiagnosticsPage(
                uiState = uiState,
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
                onBack = onBack,
            )
        }
    }
}

@Composable
internal fun SettingsPageScrollColumn(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    verticalSpacing: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        ) {
            content()
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun SettingsFeedbackBanner(
    message: String,
    tone: FlameTone,
    modifier: Modifier = Modifier,
) {
    val toneColors = resolveFlameToneColors(tone)
    val icon = when (tone) {
        FlameTone.HEALTHY -> Icons.Outlined.CheckCircle
        FlameTone.WARNING -> Icons.Outlined.WarningAmber
        FlameTone.DANGER -> Icons.Outlined.ErrorOutline
        FlameTone.EMBER,
        FlameTone.NEUTRAL,
        -> Icons.Outlined.Info
    }
    Surface(
        color = toneColors.container,
        contentColor = toneColors.onContainer,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, toneColors.border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = toneColors.fg,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = toneColors.onContainer,
            )
        }
    }
}

@Composable
internal fun SettingsPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val palette = FlameThemeTokens.palette
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 42.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.monoCtaContainer,
            contentColor = palette.monoCtaContent,
            disabledContainerColor = palette.monoCtaContainer.copy(alpha = 0.35f),
            disabledContentColor = palette.monoCtaContent.copy(alpha = 0.72f),
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 2.dp,
                color = palette.monoCtaContent,
            )
            Spacer(modifier = Modifier.width(6.dp))
        } else if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SettingsSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val palette = FlameThemeTokens.palette
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 42.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (enabled) palette.hairlineStrong else palette.hairline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = palette.surface,
            contentColor = palette.ink,
            disabledContainerColor = palette.surfaceSubtle,
            disabledContentColor = palette.inkTertiary,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 2.dp,
                color = palette.ink,
            )
            Spacer(modifier = Modifier.width(6.dp))
        } else if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SettingsDangerTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val semantics = FlameThemeTokens.semanticColors
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 42.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = semantics.danger,
            disabledContentColor = semantics.danger.copy(alpha = 0.4f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun settingsSwitchColors(): SwitchColors {
    val palette = FlameThemeTokens.palette
    return SwitchDefaults.colors(
        checkedThumbColor = palette.monoCtaContent,
        checkedTrackColor = palette.monoCtaContainer,
        checkedBorderColor = palette.monoCtaContainer,
        uncheckedThumbColor = palette.surface,
        uncheckedTrackColor = palette.hairlineStrong,
        uncheckedBorderColor = palette.hairlineStrong,
        disabledCheckedThumbColor = palette.monoCtaContent.copy(alpha = 0.6f),
        disabledCheckedTrackColor = palette.monoCtaContainer.copy(alpha = 0.35f),
        disabledUncheckedThumbColor = palette.surface.copy(alpha = 0.7f),
        disabledUncheckedTrackColor = palette.surfaceSubtle,
        disabledUncheckedBorderColor = palette.hairline,
    )
}

@Composable
internal fun settingsTextFieldColors(): TextFieldColors {
    val palette = FlameThemeTokens.palette
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = palette.monoCtaContainer,
        unfocusedBorderColor = palette.hairlineStrong,
        focusedLabelColor = palette.ink,
        unfocusedLabelColor = palette.inkSecondary,
        cursorColor = palette.monoCtaContainer,
        focusedTextColor = palette.ink,
        unfocusedTextColor = palette.ink,
        focusedPlaceholderColor = palette.inkTertiary,
        unfocusedPlaceholderColor = palette.inkTertiary,
        focusedContainerColor = palette.surface,
        unfocusedContainerColor = palette.surfaceSubtle.copy(alpha = 0.45f),
    )
}

internal fun settingsAccountStatusLabel(status: AccountStatus): String = when (status) {
    AccountStatus.PENDING_LOGIN -> "待扫码登录"
    AccountStatus.HEALTHY -> "健康"
    AccountStatus.RUNNING -> "执行中"
    AccountStatus.PAUSED -> "已暂停"
    AccountStatus.AUTH_EXPIRED -> "登录已失效"
    AccountStatus.VERIFICATION_REQUIRED -> "需安全验证"
    AccountStatus.DOM_BROKEN -> "页面结构异常"
    AccountStatus.COOLDOWN -> "连续失败熔断"
}

internal fun settingsAccountStatusTone(status: AccountStatus): FlameTone = when (status) {
    AccountStatus.HEALTHY -> FlameTone.HEALTHY
    AccountStatus.RUNNING -> FlameTone.EMBER
    AccountStatus.PENDING_LOGIN,
    AccountStatus.VERIFICATION_REQUIRED,
    AccountStatus.COOLDOWN,
    -> FlameTone.WARNING

    AccountStatus.PAUSED -> FlameTone.NEUTRAL

    AccountStatus.AUTH_EXPIRED,
    AccountStatus.DOM_BROKEN,
    -> FlameTone.DANGER
}

internal fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色模式"
    ThemeMode.DARK -> "深色碳黑"
}

internal fun scanRunStatusLabel(status: ScanRunStatus): String = when (status) {
    ScanRunStatus.RUNNING -> "同步中"
    ScanRunStatus.COMPLETED -> "已完成"
    ScanRunStatus.FAILED -> "失败"
    ScanRunStatus.INTERRUPTED -> "已中断"
    ScanRunStatus.CANCELLED -> "已取消"
}

internal fun formatScanDuration(scanRun: ScanRunEntity): String? {
    val finished = scanRun.finishedAt ?: return null
    val elapsedMs = (finished - scanRun.startedAt).coerceAtLeast(0L)
    return if (elapsedMs < 1000L) {
        "<1s"
    } else {
        "${elapsedMs / 1000L}s"
    }
}

internal fun formatSettingsEpochMs(epochMs: Long): String =
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
