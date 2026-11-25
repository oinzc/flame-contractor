package io.github.oinzc.flamecontractor.ui.shell

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.Looper
import android.webkit.WebSettings
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.oinzc.flamecontractor.app.FlameApplication
import io.github.oinzc.flamecontractor.core.account.WebViewProfileRegistry
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.core.notify.UrgentCategory
import io.github.oinzc.flamecontractor.core.setup.SetupOnboardingStore
import io.github.oinzc.flamecontractor.core.support.FeedbackPreFillDraft
import io.github.oinzc.flamecontractor.core.theme.ThemePreferencesStore
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.execution.LoginMode
import io.github.oinzc.flamecontractor.execution.LoginWebViewActivity
import io.github.oinzc.flamecontractor.execution.login.LoginReturnHelper
import io.github.oinzc.flamecontractor.ui.components.FlameMotionSpec
import io.github.oinzc.flamecontractor.ui.components.rememberReducedMotion
import io.github.oinzc.flamecontractor.ui.history.HistoryRoute
import io.github.oinzc.flamecontractor.ui.settings.RomSurvivalChecker
import io.github.oinzc.flamecontractor.ui.settings.SettingsRoute
import io.github.oinzc.flamecontractor.ui.settings.SettingsViewModel
import io.github.oinzc.flamecontractor.ui.setup.OnboardingWizardScreen
import io.github.oinzc.flamecontractor.ui.setup.SetupReadinessBuilder
import io.github.oinzc.flamecontractor.ui.targets.TargetsRoute
import io.github.oinzc.flamecontractor.ui.templates.TemplatesRoute
import io.github.oinzc.flamecontractor.ui.theme.FlameTheme
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import io.github.oinzc.flamecontractor.ui.today.TodayRoute
import io.github.oinzc.flamecontractor.widget.FlameStatusWidgetUpdater
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private data class LaunchIntentSignal(
    val accountId: String?,
    val category: String?,
    val nonce: Long,
)

class MainActivity : AppCompatActivity() {

    private var pendingLaunchSignal by mutableStateOf<LaunchIntentSignal?>(null)
    private var signalCounter: Long = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            captureLaunchIntent(intent)
        }

        val container = (application as FlameApplication).container

        // 首帧空闲后轻量预热 Chromium WebView 提供程序，减少首次进入扫码登录页的引擎冷启动耗时
        window.decorView.post {
            Looper.myQueue().addIdleHandler {
                runCatching { WebSettings.getDefaultUserAgent(applicationContext) }
                false
            }
        }

        setContent {
            val themePreference by container.themePreferencesStore.preference.collectAsState()
            val isDarkTheme = ThemePreferencesStore.resolveIsDark(
                mode = themePreference.mode,
                systemDark = isSystemInDarkTheme(),
            )

            DisposableEffect(isDarkTheme) {
                val barStyle = if (isDarkTheme) {
                    SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                } else {
                    SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                }
                enableEdgeToEdge(
                    statusBarStyle = barStyle,
                    navigationBarStyle = barStyle,
                )
                onDispose {}
            }

            FlameTheme(preference = themePreference) {
                var navState by rememberSaveable(stateSaver = MainNavStateSaver) {
                    mutableStateOf(MainNavState())
                }
                var isFullscreenEditorOpen by rememberSaveable { mutableStateOf(false) }
                var showAccountManagerSheet by rememberSaveable { mutableStateOf(false) }
                var showOnboardingWizard by rememberSaveable { mutableStateOf(false) }
                var hasEvaluatedAutoOnboarding by rememberSaveable { mutableStateOf(false) }
                var onboardingInitialStep by rememberSaveable { mutableStateOf(0) }
                var lastHandledLaunchNonce by rememberSaveable { mutableStateOf(0L) }
                var pendingFeedbackDraft by remember { mutableStateOf<FeedbackPreFillDraft?>(null) }
                val launchSignal = pendingLaunchSignal

                val onboardingState by container.setupOnboardingStore.state.collectAsState()
                val activeAccountId by container.activeAccountStore.activeAccountId.collectAsState()
                val accounts by remember(container) {
                    container.database.accountDao().observeAll()
                }.collectAsState(initial = emptyList())

                val activeEnabledTargetCount by remember(container, activeAccountId) {
                    container.database.targetDao().observeActiveTargets(activeAccountId)
                        .map { list -> list.count { it.enabled } }
                }.collectAsState(initial = -1)

                var survivalSnapshot by remember {
                    mutableStateOf(RomSurvivalChecker.inspect(applicationContext))
                }
                var survivalRefreshNonce by remember { mutableStateOf(0L) }
                val refreshSurvivalSnapshot: () -> Unit = {
                    survivalSnapshot = RomSurvivalChecker.inspect(applicationContext)
                    survivalRefreshNonce += 1L
                }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            refreshSurvivalSnapshot()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val unknownCountsByAccount by remember(container) {
                    container.database.accountDao().observeAll().flatMapLatest { list ->
                        if (list.isEmpty()) {
                            flowOf(emptyMap())
                        } else {
                            combine(
                                list.map { acc ->
                                    container.database.sendJobDao()
                                        .observeJobsByStatus(acc.id, JobStatus.UNKNOWN_REQUIRES_REVIEW)
                                        .map { jobs -> acc.id to jobs.size }
                                },
                            ) { pairs -> pairs.toMap() }
                        }
                    }
                }.collectAsState(initial = emptyMap())

                val loginLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult(),
                ) {
                    // LoginWebViewActivity updates Room directly; flows re-emit automatically.
                }

                LaunchedEffect(accounts) {
                    if (accounts.isNotEmpty()) {
                        container.activeAccountStore.reconcileWithAvailableAccounts(accounts.map { it.id })
                    }
                }

                LaunchedEffect(launchSignal, accounts) {
                    if (launchSignal != null && launchSignal.nonce != lastHandledLaunchNonce) {
                        val targetAccountId = launchSignal.accountId
                        if (targetAccountId != null &&
                            (accounts.isEmpty() || accounts.any { it.id == targetAccountId })
                        ) {
                            container.activeAccountStore.selectAccount(targetAccountId)
                        }
                        navState = navState.applyLaunchIntent(
                            category = launchSignal.category,
                            hasAccountTarget = targetAccountId != null,
                        )
                        isFullscreenEditorOpen = false
                        showAccountManagerSheet = false
                        lastHandledLaunchNonce = launchSignal.nonce
                    }
                }

                LaunchedEffect(navState.selectedTab, navState.isSettingsOpen) {
                    if (navState.selectedTab != MainTab.TEMPLATES || navState.isSettingsOpen) {
                        isFullscreenEditorOpen = false
                    }
                }

                BackHandler(enabled = navState.isSettingsOpen && !showOnboardingWizard) {
                    val (next, _) = navState.navigateBack()
                    navState = next
                }

                val currentAccount = accounts.firstOrNull { it.id == activeAccountId }
                val setupReadiness = SetupReadinessBuilder.build(
                    survivalSnapshot = survivalSnapshot,
                    accountStatus = currentAccount?.status ?: AccountStatus.PENDING_LOGIN,
                    enabledTargetCount = activeEnabledTargetCount.coerceAtLeast(0),
                )

                LaunchedEffect(
                    accounts,
                    activeEnabledTargetCount,
                    onboardingState.onboardingCompleted,
                    launchSignal,
                ) {
                    if (hasEvaluatedAutoOnboarding) return@LaunchedEffect
                    if (accounts.isEmpty() || activeEnabledTargetCount < 0) return@LaunchedEffect
                    if (launchSignal != null) {
                        hasEvaluatedAutoOnboarding = true
                        return@LaunchedEffect
                    }
                    hasEvaluatedAutoOnboarding = true
                    val shouldAutoLaunch = SetupOnboardingStore.shouldAutoLaunchOnboarding(
                        onboardingCompleted = onboardingState.onboardingCompleted,
                        accountStatus = currentAccount?.status ?: AccountStatus.PENDING_LOGIN,
                        enabledTargetCount = activeEnabledTargetCount,
                        allCoreSystemReady = survivalSnapshot.coreAllPassed,
                    )
                    if (shouldAutoLaunch) {
                        onboardingInitialStep = onboardingState.lastStepIndex
                        showOnboardingWizard = true
                    }
                }

                val openLoginForAccount: (String) -> Unit = { targetId ->
                    val targetStatus = accounts.firstOrNull { it.id == targetId }?.status
                    val mode = if (targetStatus == AccountStatus.VERIFICATION_REQUIRED) {
                        LoginMode.INSPECT_OR_VERIFY
                    } else {
                        LoginMode.PRESERVE_SESSION
                    }
                    loginLauncher.launch(
                        LoginWebViewActivity.createIntent(
                            context = this@MainActivity,
                            mode = mode,
                            accountId = targetId,
                        ),
                    )
                }

                val openInspectWebViewForAccount: (String) -> Unit = { targetId ->
                    loginLauncher.launch(
                        LoginWebViewActivity.createIntent(
                            context = this@MainActivity,
                            mode = LoginMode.INSPECT_OR_VERIFY,
                            accountId = targetId,
                        ),
                    )
                }

                val startOnboardingWizard: () -> Unit = {
                    refreshSurvivalSnapshot()
                    onboardingInitialStep = 0
                    showOnboardingWizard = true
                }
                val hasTodayAlert = currentAccount?.let {
                    hasAccountAttention(it, unknownCountsByAccount[it.id] ?: 0)
                } ?: ((unknownCountsByAccount[activeAccountId] ?: 0) > 0)
                val hasOtherAccountAlert = accounts.any { acc ->
                    acc.id != activeAccountId &&
                        hasAccountAttention(acc, unknownCountsByAccount[acc.id] ?: 0)
                }

                val urgentCategoryForActive = launchSignal
                    ?.takeIf { it.accountId == null || it.accountId == activeAccountId }
                    ?.category
                    ?.takeIf { cat -> UrgentCategory.entries.any { it.name == cat } }
                val urgentNonceForActive = if (urgentCategoryForActive != null) {
                    launchSignal.nonce
                } else {
                    0L
                }

                val openSettings: (SettingsSection?) -> Unit = { section ->
                    isFullscreenEditorOpen = false
                    navState = navState.openSettings(section)
                }
                val openFeedbackDirect: () -> Unit = {
                    isFullscreenEditorOpen = false
                    navState = navState.openSettings(
                        section = SettingsSection.ABOUT,
                        returnToTabOnBack = true,
                    )
                }
                val openFeedbackWithDraft: (FeedbackPreFillDraft) -> Unit = { draft ->
                    pendingFeedbackDraft = draft
                    isFullscreenEditorOpen = false
                    navState = navState.openSettings(
                        section = SettingsSection.ABOUT,
                        returnToTabOnBack = true,
                    )
                }
                val buildQuickDiagnosticsReportText: suspend () -> String = {
                    SettingsViewModel.buildQuickSanitizedDiagnosticsText(
                        context = applicationContext,
                        database = container.database,
                        selectorBundleStore = container.selectorBundleStore,
                        secretStore = container.secretStore,
                        diagnosticLogStore = container.diagnosticLogStore,
                        accountId = activeAccountId,
                    )
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    AdaptiveNavShell(
                        selectedTab = navState.selectedTab,
                        onSelectTab = { tab ->
                            isFullscreenEditorOpen = false
                            navState = navState.selectTab(tab)
                        },
                        todayAlertBadge = hasTodayAlert,
                        hideNavigationChrome = navState.isSettingsOpen || isFullscreenEditorOpen || showOnboardingWizard,
                        currentAccount = currentAccount,
                        totalAccountCount = accounts.size.coerceAtLeast(1),
                        otherAccountAlertBadge = hasOtherAccountAlert,
                        onOpenAccountSwitcher = { showAccountManagerSheet = true },
                        onOpenFeedback = openFeedbackDirect,
                        onOpenSettings = { openSettings(null) },
                        todayContent = {
                            TodayRoute(
                                container = container,
                                onOpenTab = { targetTab ->
                                    isFullscreenEditorOpen = false
                                    navState = navState.selectTab(targetTab)
                                },
                                onOpenSettings = openSettings,
                                onOpenFeedback = openFeedbackDirect,
                                onOpenFeedbackWithDraft = openFeedbackWithDraft,
                                onBuildDiagnosticsReportText = buildQuickDiagnosticsReportText,
                                onOpenAccountSwitcher = { showAccountManagerSheet = true },
                                otherAccountAlertBadge = hasOtherAccountAlert,
                                setupReadiness = if (activeEnabledTargetCount >= 0) setupReadiness else null,
                                onStartOnboardingWizard = startOnboardingWizard,
                                initialUrgentCategory = urgentCategoryForActive,
                                urgentSignalNonce = urgentNonceForActive,
                                accountId = activeAccountId,
                            )
                        },
                        targetsContent = {
                            TargetsRoute(
                                database = container.database,
                                scheduleCoordinator = container.scheduleCoordinator,
                                onNavigateToTodayReview = {
                                    isFullscreenEditorOpen = false
                                    navState = navState.selectTab(MainTab.TODAY)
                                },
                                onOpenSettings = openSettings,
                                onOpenFeedback = openFeedbackDirect,
                                accountId = activeAccountId,
                                imageStore = container.templateImageStore,
                            )
                        },
                        templatesContent = {
                            TemplatesRoute(
                                database = container.database,
                                onOpenSettings = openSettings,
                                onOpenFeedback = openFeedbackDirect,
                                onEditorVisibleChanged = { visible -> isFullscreenEditorOpen = visible },
                                accountId = activeAccountId,
                                imageStore = container.templateImageStore,
                            )
                        },
                        historyContent = {
                            HistoryRoute(
                                database = container.database,
                                onNavigateToTodayReview = {
                                    isFullscreenEditorOpen = false
                                    navState = navState.selectTab(MainTab.TODAY)
                                },
                                onOpenSettings = openSettings,
                                onOpenFeedback = openFeedbackDirect,
                                onOpenFeedbackWithDraft = openFeedbackWithDraft,
                                onBuildDiagnosticsReportText = buildQuickDiagnosticsReportText,
                                accountId = activeAccountId,
                            )
                        },
                    )

                    val reducedMotion = rememberReducedMotion()
                    var renderedSettingsSection by remember { mutableStateOf(navState.activeSettingsSection) }
                    if (navState.isSettingsOpen) {
                        renderedSettingsSection = navState.activeSettingsSection
                    }

                    AnimatedVisibility(
                        visible = navState.isSettingsOpen,
                        enter = if (reducedMotion) {
                            EnterTransition.None
                        } else {
                            fadeIn(
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
                            )
                        },
                        exit = if (reducedMotion) {
                            ExitTransition.None
                        } else {
                            fadeOut(
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
                            )
                        },
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = FlameThemeTokens.palette.canvas,
                        ) {
                            SettingsRoute(
                                database = container.database,
                                selectorBundleStore = container.selectorBundleStore,
                                secretStore = container.secretStore,
                                barkNotifier = container.barkNotifier,
                                scheduleCoordinator = container.scheduleCoordinator,
                                themePreferencesStore = container.themePreferencesStore,
                                activeSection = renderedSettingsSection,
                                onSelectSection = { section ->
                                    navState = navState.openSettings(section)
                                },
                                onStartOnboardingWizard = startOnboardingWizard,
                                onOpenLoginForActiveAccount = { openLoginForAccount(activeAccountId) },
                                onOpenInspectWebViewForActiveAccount = { openInspectWebViewForAccount(activeAccountId) },
                                onOpenTargetsTab = {
                                    isFullscreenEditorOpen = false
                                    navState = navState.selectTab(MainTab.TARGETS)
                                },
                                survivalRefreshNonce = survivalRefreshNonce,
                                onBack = {
                                    val (next, _) = navState.navigateBack()
                                    navState = next
                                },
                                backupManager = container.backupManager,
                                accountId = activeAccountId,
                                diagnosticLogStore = container.diagnosticLogStore,
                                pendingFeedbackDraft = pendingFeedbackDraft,
                                onConsumeFeedbackDraft = { nonce ->
                                    if (pendingFeedbackDraft?.nonce == nonce) {
                                        pendingFeedbackDraft = null
                                    }
                                },
                                onOpenFeedbackWithDraft = openFeedbackWithDraft,
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = showOnboardingWizard,
                        enter = if (reducedMotion) {
                            EnterTransition.None
                        } else {
                            fadeIn(
                                animationSpec = tween(
                                    durationMillis = FlameMotionSpec.SUBPAGE_ENTER_MS,
                                    easing = FlameMotionSpec.easeOut,
                                ),
                            )
                        },
                        exit = if (reducedMotion) {
                            ExitTransition.None
                        } else {
                            fadeOut(
                                animationSpec = tween(
                                    durationMillis = FlameMotionSpec.SUBPAGE_EXIT_MS,
                                    easing = FlameMotionSpec.easeOut,
                                ),
                            )
                        },
                    ) {
                        OnboardingWizardScreen(
                            readiness = setupReadiness,
                            activeAccountDisplayName = currentAccount?.displayName.orEmpty(),
                            initialStepIndex = onboardingInitialStep,
                            onStepChanged = { stepIdx ->
                                container.setupOnboardingStore.setLastStepIndex(stepIdx)
                            },
                            onRefreshSurvival = refreshSurvivalSnapshot,
                            onSetAutostartConfirmed = { confirmed ->
                                RomSurvivalChecker.setAutostartGuidanceConfirmed(applicationContext, confirmed)
                                refreshSurvivalSnapshot()
                            },
                            onOpenLogin = { openLoginForAccount(activeAccountId) },
                            onFinishAndOpenTargets = {
                                container.setupOnboardingStore.markCompleted()
                                showOnboardingWizard = false
                                isFullscreenEditorOpen = false
                                navState = navState.selectTab(MainTab.TARGETS)
                            },
                            onFinishToToday = {
                                container.setupOnboardingStore.markCompleted()
                                showOnboardingWizard = false
                                isFullscreenEditorOpen = false
                                navState = navState.selectTab(MainTab.TODAY)
                            },
                            onSkip = {
                                container.setupOnboardingStore.markCompleted()
                                showOnboardingWizard = false
                            },
                        )
                    }
                }

                if (showAccountManagerSheet) {
                    AccountManagerSheet(
                        accounts = accounts,
                        activeAccountId = activeAccountId,
                        unknownCountByAccount = unknownCountsByAccount,
                        isMultiProfileSupported = WebViewProfileRegistry.isMultiProfileSupported(),
                        accountManager = container.accountManager,
                        onSelectAccount = { id -> container.activeAccountStore.selectAccount(id) },
                        onOpenLoginForAccount = { id -> openLoginForAccount(id) },
                        onDismiss = { showAccountManagerSheet = false },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LoginReturnHelper.cancelReturnNotification(applicationContext)
    }

    override fun onStop() {
        super.onStop()
        FlameStatusWidgetUpdater.requestUpdate(applicationContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureLaunchIntent(intent)
    }

    private fun captureLaunchIntent(intent: Intent?) {
        val accountIdExtra = intent?.getStringExtra(LocalNotifier.EXTRA_ACCOUNT_ID)
            ?.takeIf { it.isNotBlank() }
        val categoryExtra = intent?.getStringExtra(LocalNotifier.EXTRA_URGENT_CATEGORY)
            ?.takeIf { it.isNotBlank() }
        if (accountIdExtra == null && categoryExtra == null) return

        signalCounter += 1L
        pendingLaunchSignal = LaunchIntentSignal(
            accountId = accountIdExtra,
            category = categoryExtra,
            nonce = signalCounter,
        )
    }
}
