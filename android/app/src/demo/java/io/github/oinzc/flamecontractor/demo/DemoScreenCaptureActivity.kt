package io.github.oinzc.flamecontractor.demo

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.oinzc.flamecontractor.app.FlameApplication
import io.github.oinzc.flamecontractor.core.theme.ThemeMode
import io.github.oinzc.flamecontractor.core.theme.ThemePreferencesStore
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.ui.history.HistoryRoute
import io.github.oinzc.flamecontractor.ui.settings.SettingsRoute
import io.github.oinzc.flamecontractor.ui.shell.AdaptiveNavShell
import io.github.oinzc.flamecontractor.ui.shell.MainTab
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.targets.TargetsRoute
import io.github.oinzc.flamecontractor.ui.theme.FlameTheme
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import io.github.oinzc.flamecontractor.ui.today.TodayRoute

/**
 * 演示变体专用截屏宿主页（仅编译进 `demo` APK，绝不进入 `debug` / `release`）。
 * 渲染 100% 真实的生产 Jetpack Compose 页面（TodayRoute / TargetsRoute / HistoryRoute / SettingsRoute）。
 */
class DemoScreenCaptureActivity : AppCompatActivity() {

    private var requestedScreen by mutableStateOf("today")
    private var requestedTheme by mutableStateOf("dark")
    private var launchNonce by mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        applyIntentExtras()

        val container = (application as FlameApplication).container

        setContent {
            var seeded by remember { mutableStateOf(false) }

            LaunchedEffect(launchNonce) {
                val targetMode = if (requestedTheme.equals("light", ignoreCase = true)) {
                    ThemeMode.LIGHT
                } else {
                    ThemeMode.DARK
                }
                container.themePreferencesStore.setThemeMode(targetMode)
                if (!seeded) {
                    DemoDataSeeder.seedDatabase(applicationContext, container)
                    seeded = true
                }
                Log.i(TAG, "DEMO_SCREEN_READY screen=$requestedScreen theme=$requestedTheme")
            }

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
                val palette = FlameThemeTokens.palette
                if (!seeded) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(palette.canvas),
                    )
                } else {
                    val accounts by remember(container) {
                        container.database.accountDao().observeAll()
                    }.collectAsState(initial = emptyList())
                    val currentAccount = accounts.firstOrNull { it.id == DEFAULT_ACCOUNT_ID }

                    val isAbout = requestedScreen.equals("about", ignoreCase = true)
                    val selectedTab = when (requestedScreen.lowercase()) {
                        "targets" -> MainTab.TARGETS
                        "history" -> MainTab.HISTORY
                        else -> MainTab.TODAY
                    }

                    if (isAbout) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = palette.canvas,
                        ) {
                            SettingsRoute(
                                database = container.database,
                                selectorBundleStore = container.selectorBundleStore,
                                secretStore = container.secretStore,
                                barkNotifier = container.barkNotifier,
                                scheduleCoordinator = container.scheduleCoordinator,
                                themePreferencesStore = container.themePreferencesStore,
                                activeSection = SettingsSection.ABOUT,
                                onSelectSection = {},
                                onStartOnboardingWizard = {},
                                onOpenLoginForActiveAccount = {},
                                onOpenTargetsTab = {},
                                survivalRefreshNonce = 0L,
                                onBack = {},
                                backupManager = container.backupManager,
                                accountId = DEFAULT_ACCOUNT_ID,
                            )
                        }
                    } else {
                        AdaptiveNavShell(
                            selectedTab = selectedTab,
                            onSelectTab = {},
                            todayAlertBadge = false,
                            hideNavigationChrome = false,
                            currentAccount = currentAccount,
                            totalAccountCount = accounts.size.coerceAtLeast(1),
                            otherAccountAlertBadge = false,
                            onOpenAccountSwitcher = {},
                            onOpenFeedback = {},
                            onOpenSettings = {},
                            todayContent = {
                                TodayRoute(
                                    container = container,
                                    onOpenTab = {},
                                    onOpenSettings = {},
                                    onOpenFeedback = {},
                                    onOpenAccountSwitcher = {},
                                    otherAccountAlertBadge = false,
                                    setupReadiness = null,
                                    onStartOnboardingWizard = {},
                                    accountId = DEFAULT_ACCOUNT_ID,
                                )
                            },
                            targetsContent = {
                                TargetsRoute(
                                    database = container.database,
                                    scheduleCoordinator = container.scheduleCoordinator,
                                    onNavigateToTodayReview = {},
                                    onOpenSettings = {},
                                    onOpenFeedback = {},
                                    accountId = DEFAULT_ACCOUNT_ID,
                                    imageStore = container.templateImageStore,
                                )
                            },
                            templatesContent = {},
                            historyContent = {
                                HistoryRoute(
                                    database = container.database,
                                    onNavigateToTodayReview = {},
                                    onOpenSettings = {},
                                    onOpenFeedback = {},
                                    accountId = DEFAULT_ACCOUNT_ID,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntentExtras()
    }

    private fun applyIntentExtras() {
        requestedScreen = intent?.getStringExtra(EXTRA_SCREEN)?.takeIf { it.isNotBlank() } ?: "today"
        requestedTheme = intent?.getStringExtra(EXTRA_THEME)?.takeIf { it.isNotBlank() } ?: "dark"
        launchNonce += 1L
    }

    companion object {
        private const val TAG = "DemoScreenCapture"
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_THEME = "theme"
    }
}
