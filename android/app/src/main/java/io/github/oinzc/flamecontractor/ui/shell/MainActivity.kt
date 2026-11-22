package io.github.oinzc.flamecontractor.ui.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.oinzc.flamecontractor.app.FlameApplication
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.core.notify.UrgentCategory
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.ui.history.HistoryRoute
import io.github.oinzc.flamecontractor.ui.settings.SettingsRoute
import io.github.oinzc.flamecontractor.ui.targets.TargetsRoute
import io.github.oinzc.flamecontractor.ui.templates.TemplatesRoute
import io.github.oinzc.flamecontractor.ui.theme.FlameTheme
import io.github.oinzc.flamecontractor.ui.today.TodayRoute

private data class UrgentIntentSignal(
    val category: String,
    val nonce: Long,
)

class MainActivity : AppCompatActivity() {

    private var pendingUrgentSignal by mutableStateOf<UrgentIntentSignal?>(null)
    private var signalCounter: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        captureUrgentIntent(intent)

        val container = (application as FlameApplication).container

        setContent {
            FlameTheme {
                var selectedTab by rememberSaveable { mutableStateOf(MainTab.TODAY) }
                val urgentSignal = pendingUrgentSignal

                LaunchedEffect(urgentSignal) {
                    if (urgentSignal != null) {
                        selectedTab = if (urgentSignal.category == UrgentCategory.SPARK_DETERIORATING.name) {
                            MainTab.TARGETS
                        } else {
                            MainTab.TODAY
                        }
                    }
                }

                val account by container.database.accountDao()
                    .observeAccount(DEFAULT_ACCOUNT_ID)
                    .collectAsState(initial = null)
                val unknownJobs by container.database.sendJobDao()
                    .observeJobsByStatus(DEFAULT_ACCOUNT_ID, JobStatus.UNKNOWN_REQUIRES_REVIEW)
                    .collectAsState(initial = emptyList())

                val hasTodayAlert = unknownJobs.isNotEmpty() || account?.status in setOf(
                    AccountStatus.AUTH_EXPIRED,
                    AccountStatus.VERIFICATION_REQUIRED,
                    AccountStatus.DOM_BROKEN,
                    AccountStatus.COOLDOWN,
                )

                AdaptiveNavShell(
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    todayAlertBadge = hasTodayAlert,
                    todayContent = {
                        TodayRoute(
                            container = container,
                            onOpenTab = { targetTab -> selectedTab = targetTab },
                            initialUrgentCategory = urgentSignal?.category,
                            urgentSignalNonce = urgentSignal?.nonce ?: 0L,
                        )
                    },
                    targetsContent = {
                        TargetsRoute(
                            database = container.database,
                            scheduleCoordinator = container.scheduleCoordinator,
                            onNavigateToTodayReview = { selectedTab = MainTab.TODAY },
                        )
                    },
                    templatesContent = {
                        TemplatesRoute(
                            database = container.database,
                        )
                    },
                    historyContent = {
                        HistoryRoute(
                            database = container.database,
                            onNavigateToTodayReview = { selectedTab = MainTab.TODAY },
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
                        )
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureUrgentIntent(intent)
    }

    private fun captureUrgentIntent(intent: Intent?) {
        val category = intent?.getStringExtra(LocalNotifier.EXTRA_URGENT_CATEGORY)
            ?.takeIf { it.isNotBlank() }
            ?: return
        signalCounter += 1L
        pendingUrgentSignal = UrgentIntentSignal(
            category = category,
            nonce = signalCounter,
        )
    }
}
