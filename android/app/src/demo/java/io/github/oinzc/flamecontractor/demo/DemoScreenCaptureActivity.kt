package io.github.oinzc.flamecontractor.demo

import android.content.Intent
import android.content.pm.ActivityInfo
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.oinzc.flamecontractor.BuildConfig
import io.github.oinzc.flamecontractor.app.FlameApplication
import io.github.oinzc.flamecontractor.core.theme.ThemeMode
import io.github.oinzc.flamecontractor.core.theme.ThemePreferencesStore
import io.github.oinzc.flamecontractor.core.theme.UiDisplayMode
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.ui.components.LocalFlameReducedMotion
import io.github.oinzc.flamecontractor.ui.components.ProvideFlameWindowLayout
import io.github.oinzc.flamecontractor.ui.history.HistoryRoute
import io.github.oinzc.flamecontractor.ui.settings.SettingsRoute
import io.github.oinzc.flamecontractor.ui.shell.AdaptiveNavShell
import io.github.oinzc.flamecontractor.ui.shell.MainTab
import io.github.oinzc.flamecontractor.ui.shell.SettingsSection
import io.github.oinzc.flamecontractor.ui.targets.TargetsRoute
import io.github.oinzc.flamecontractor.ui.templates.TemplatesRoute
import io.github.oinzc.flamecontractor.ui.theme.FlameTheme
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import io.github.oinzc.flamecontractor.ui.today.TodayRoute

/**
 * 演示变体专用截屏宿主页（仅编译进 `demo` APK，绝不进入 `debug` / `release`）。
 * 渲染 100% 真实的生产 Jetpack Compose 页面，支持极简/标准双模式与手机/平板双方向。
 */
class DemoScreenCaptureActivity : AppCompatActivity() {

    private var requestedScreen by mutableStateOf("simple")
    private var requestedMode by mutableStateOf("simple")
    private var requestedTheme by mutableStateOf("dark")
    private var requestedOrientationMode by mutableStateOf("portrait")
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
                val targetThemeMode = if (requestedTheme.equals("light", ignoreCase = true)) {
                    ThemeMode.LIGHT
                } else {
                    ThemeMode.DARK
                }
                val targetDisplayMode = if (requestedMode.equals("simple", ignoreCase = true)) {
                    UiDisplayMode.SIMPLE
                } else {
                    UiDisplayMode.STANDARD
                }
                container.themePreferencesStore.setThemeMode(targetThemeMode)
                container.themePreferencesStore.setDisplayMode(targetDisplayMode)
                if (!seeded) {
                    DemoDataSeeder.seedDatabase(applicationContext, container)
                    seeded = true
                }
                Log.i(
                    TAG,
                    "DEMO_SCREEN_READY screen=$requestedScreen mode=$requestedMode theme=$requestedTheme orientation=$requestedOrientationMode",
                )
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
                CompositionLocalProvider(LocalFlameReducedMotion provides true) {
                    ProvideFlameWindowLayout {
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
                            val isSimpleMode = themePreference.displayMode == UiDisplayMode.SIMPLE
                            val shortVersionLabel = remember {
                                val core = BuildConfig.VERSION_NAME
                                    .trim()
                                    .removePrefix("v")
                                    .removePrefix("V")
                                    .substringBefore('-')
                                    .trim()
                                if (core.isEmpty()) "" else "v$core"
                            }

                            val settingsSection = when (requestedScreen.lowercase()) {
                                "schedule" -> SettingsSection.SCHEDULE
                                "updates" -> SettingsSection.UPDATES
                                "about" -> SettingsSection.ABOUT
                                else -> null
                            }
                            val selectedTab = when (requestedScreen.lowercase()) {
                                "targets" -> MainTab.TARGETS
                                "templates" -> MainTab.TEMPLATES
                                "history" -> MainTab.HISTORY
                                else -> MainTab.TODAY
                            }

                            if (settingsSection != null) {
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
                                        activeSection = settingsSection,
                                        onSelectSection = {},
                                        onStartOnboardingWizard = {},
                                        onOpenLoginForActiveAccount = {},
                                        onOpenInspectWebViewForActiveAccount = {},
                                        onOpenTargetsTab = {},
                                        survivalRefreshNonce = 0L,
                                        onBack = {},
                                        backupManager = container.backupManager,
                                        accountId = DEFAULT_ACCOUNT_ID,
                                        diagnosticLogStore = container.diagnosticLogStore,
                                        pendingFeedbackDraft = null,
                                        onConsumeFeedbackDraft = {},
                                        onOpenFeedbackWithDraft = {},
                                        appUpdateManager = container.appUpdateManager,
                                    )
                                }
                            } else {
                                AdaptiveNavShell(
                                    selectedTab = selectedTab,
                                    onSelectTab = {},
                                    todayAlertBadge = false,
                                    hideNavigationChrome = isSimpleMode,
                                    currentAccount = currentAccount,
                                    totalAccountCount = accounts.size.coerceAtLeast(1),
                                    otherAccountAlertBadge = false,
                                    enabledTargetCount = 8,
                                    isSettingsSelected = false,
                                    appVersionLabel = shortVersionLabel,
                                    onOpenAccountSwitcher = {},
                                    onSwitchToSimpleMode = {},
                                    onOpenFeedback = {},
                                    onOpenSettings = {},
                                    todayContent = {
                                        TodayRoute(
                                            container = container,
                                            onOpenTab = {},
                                            onOpenSettings = {},
                                            onOpenFeedback = {},
                                            onOpenFeedbackWithDraft = {},
                                            onBuildDiagnosticsReportText = { "" },
                                            onOpenAccountSwitcher = {},
                                            otherAccountAlertBadge = false,
                                            setupReadiness = null,
                                            onStartOnboardingWizard = {},
                                            accountId = DEFAULT_ACCOUNT_ID,
                                            displayMode = themePreference.displayMode,
                                            onSwitchToStandardMode = {},
                                            onSwitchToSimpleMode = {},
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
                                            onBack = null,
                                        )
                                    },
                                    templatesContent = {
                                        TemplatesRoute(
                                            database = container.database,
                                            onOpenSettings = {},
                                            onOpenFeedback = {},
                                            onEditorVisibleChanged = {},
                                            accountId = DEFAULT_ACCOUNT_ID,
                                            imageStore = container.templateImageStore,
                                            onBack = null,
                                        )
                                    },
                                    historyContent = {
                                        HistoryRoute(
                                            database = container.database,
                                            onNavigateToTodayReview = {},
                                            onOpenSettings = {},
                                            onOpenFeedback = {},
                                            onOpenFeedbackWithDraft = {},
                                            onBuildDiagnosticsReportText = { "" },
                                            accountId = DEFAULT_ACCOUNT_ID,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntentExtras()
    }

    private fun applyIntentExtras() {
        val rawScreen = intent?.getStringExtra(EXTRA_SCREEN)?.takeIf { it.isNotBlank() } ?: "simple"
        val rawMode = intent?.getStringExtra(EXTRA_MODE)?.takeIf { it.isNotBlank() }
            ?: if (rawScreen.equals("simple", ignoreCase = true)) "simple" else "standard"
        val rawTheme = intent?.getStringExtra(EXTRA_THEME)?.takeIf { it.isNotBlank() } ?: "dark"
        val rawOrientation = intent?.getStringExtra(EXTRA_ORIENTATION)?.takeIf { it.isNotBlank() } ?: "portrait"

        requestedScreen = rawScreen
        requestedMode = rawMode
        requestedTheme = rawTheme
        requestedOrientationMode = rawOrientation

        val targetOrientation = if (rawOrientation.equals("landscape", ignoreCase = true)) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (getRequestedOrientation() != targetOrientation) {
            setRequestedOrientation(targetOrientation)
        }
        launchNonce += 1L
    }

    companion object {
        private const val TAG = "DemoScreenCapture"
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_MODE = "mode"
        const val EXTRA_THEME = "theme"
        const val EXTRA_ORIENTATION = "orientation"
    }
}
