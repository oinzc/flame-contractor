package io.github.oinzc.flamecontractor.ui.shell

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import io.github.oinzc.flamecontractor.app.FlameApplication
import io.github.oinzc.flamecontractor.core.account.WebViewProfileRegistry
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.core.theme.ThemePreferencesStore
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.execution.LoginMode
import io.github.oinzc.flamecontractor.execution.LoginWebViewActivity
import io.github.oinzc.flamecontractor.ui.history.HistoryRoute
import io.github.oinzc.flamecontractor.ui.settings.SettingsRoute
import io.github.oinzc.flamecontractor.ui.targets.TargetsRoute
import io.github.oinzc.flamecontractor.ui.templates.TemplatesRoute
import io.github.oinzc.flamecontractor.ui.theme.FlameTheme
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import io.github.oinzc.flamecontractor.ui.today.TodayRoute
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
                var lastHandledLaunchNonce by rememberSaveable { mutableStateOf(0L) }
                val launchSignal = pendingLaunchSignal

                val activeAccountId by container.activeAccountStore.activeAccountId.collectAsState()
                val accounts by remember(container) {
                    container.database.accountDao().observeAll()
                }.collectAsState(initial = emptyList())

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
                        lastHandledLaunchNonce = launchSignal.nonce
                    }
                }

                LaunchedEffect(navState.selectedTab, navState.isSettingsOpen) {
                    if (navState.selectedTab != MainTab.TEMPLATES || navState.isSettingsOpen) {
                        isFullscreenEditorOpen = false
                    }
                }

                BackHandler(enabled = navState.isSettingsOpen) {
                    val (next, _) = navState.navigateBack()
                    navState = next
                }

                val currentAccount = accounts.firstOrNull { it.id == activeAccountId }
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
                val urgentNonceForActive = if (urgentCategoryForActive != null) {
                    launchSignal.nonce
                } else {
                    0L
                }

                val openSettings: (SettingsSection?) -> Unit = { section ->
                    isFullscreenEditorOpen = false
                    navState = navState.openSettings(section)
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    AdaptiveNavShell(
                        selectedTab = navState.selectedTab,
                        onSelectTab = { tab ->
                            isFullscreenEditorOpen = false
                            navState = navState.selectTab(tab)
                        },
                        todayAlertBadge = hasTodayAlert,
                        hideNavigationChrome = navState.isSettingsOpen || isFullscreenEditorOpen,
                        currentAccount = currentAccount,
                        totalAccountCount = accounts.size.coerceAtLeast(1),
                        otherAccountAlertBadge = hasOtherAccountAlert,
                        onOpenAccountSwitcher = { showAccountManagerSheet = true },
                        onOpenSettings = { openSettings(null) },
                        todayContent = {
                            TodayRoute(
                                container = container,
                                onOpenTab = { targetTab ->
                                    isFullscreenEditorOpen = false
                                    navState = navState.selectTab(targetTab)
                                },
                                onOpenSettings = openSettings,
                                onOpenAccountSwitcher = { showAccountManagerSheet = true },
                                otherAccountAlertBadge = hasOtherAccountAlert,
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
                                accountId = activeAccountId,
                                imageStore = container.templateImageStore,
                            )
                        },
                        templatesContent = {
                            TemplatesRoute(
                                database = container.database,
                                onOpenSettings = openSettings,
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
                                accountId = activeAccountId,
                            )
                        },
                    )

                    if (navState.isSettingsOpen) {
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
                                activeSection = navState.activeSettingsSection,
                                onSelectSection = { section ->
                                    navState = navState.openSettings(section)
                                },
                                onBack = {
                                    val (next, _) = navState.navigateBack()
                                    navState = next
                                },
                                backupManager = container.backupManager,
                                accountId = activeAccountId,
                            )
                        }
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
                        onOpenLoginForAccount = { id ->
                            loginLauncher.launch(
                                LoginWebViewActivity.createIntent(
                                    context = this@MainActivity,
                                    mode = LoginMode.PRESERVE_SESSION,
                                    accountId = id,
                                ),
                            )
                        },
                        onDismiss = { showAccountManagerSheet = false },
                    )
                }
            }
        }
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
