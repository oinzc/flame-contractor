package io.github.oinzc.flamecontractor.ui.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.oinzc.flamecontractor.app.FlameApplication
import io.github.oinzc.flamecontractor.core.account.WebViewProfileRegistry
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.core.notify.UrgentCategory
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.execution.LoginMode
import io.github.oinzc.flamecontractor.execution.LoginWebViewActivity
import io.github.oinzc.flamecontractor.ui.history.HistoryRoute
import io.github.oinzc.flamecontractor.ui.settings.SettingsRoute
import io.github.oinzc.flamecontractor.ui.targets.TargetsRoute
import io.github.oinzc.flamecontractor.ui.templates.TemplatesRoute
import io.github.oinzc.flamecontractor.ui.theme.FlameTheme
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
            FlameTheme {
                var selectedTab by rememberSaveable { mutableStateOf(MainTab.TODAY) }
                var showAccountManagerDialog by rememberSaveable { mutableStateOf(false) }
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
                        if (launchSignal.category != null) {
                            selectedTab = if (launchSignal.category == UrgentCategory.SPARK_DETERIORATING.name) {
                                MainTab.TARGETS
                            } else {
                                MainTab.TODAY
                            }
                        } else if (targetAccountId != null) {
                            selectedTab = MainTab.TODAY
                        }
                        lastHandledLaunchNonce = launchSignal.nonce
                    }
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

                AdaptiveNavShell(
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    todayAlertBadge = hasTodayAlert,
                    currentAccount = currentAccount,
                    totalAccountCount = accounts.size.coerceAtLeast(1),
                    otherAccountAlertBadge = hasOtherAccountAlert,
                    onOpenAccountSwitcher = { showAccountManagerDialog = true },
                    todayContent = {
                        TodayRoute(
                            container = container,
                            onOpenTab = { targetTab -> selectedTab = targetTab },
                            initialUrgentCategory = urgentCategoryForActive,
                            urgentSignalNonce = urgentNonceForActive,
                            accountId = activeAccountId,
                        )
                    },
                    targetsContent = {
                        TargetsRoute(
                            database = container.database,
                            scheduleCoordinator = container.scheduleCoordinator,
                            onNavigateToTodayReview = { selectedTab = MainTab.TODAY },
                            accountId = activeAccountId,
                            imageStore = container.templateImageStore,
                        )
                    },
                    templatesContent = {
                        TemplatesRoute(
                            database = container.database,
                            accountId = activeAccountId,
                            imageStore = container.templateImageStore,
                        )
                    },
                    historyContent = {
                        HistoryRoute(
                            database = container.database,
                            onNavigateToTodayReview = { selectedTab = MainTab.TODAY },
                            accountId = activeAccountId,
                        )
                    },
                    settingsContent = {
                        SettingsRoute(
                            database = container.database,
                            selectorBundleStore = container.selectorBundleStore,
                            secretStore = container.secretStore,
                            barkNotifier = container.barkNotifier,
                            scheduleCoordinator = container.scheduleCoordinator,
                            backupManager = container.backupManager,
                            accountId = activeAccountId,
                        )
                    },
                )

                if (showAccountManagerDialog) {
                    AccountManagerDialog(
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
                        onDismiss = { showAccountManagerDialog = false },
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
