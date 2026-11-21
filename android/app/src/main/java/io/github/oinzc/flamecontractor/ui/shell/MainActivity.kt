package io.github.oinzc.flamecontractor.ui.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.app.FlameApplication
import io.github.oinzc.flamecontractor.core.notify.LocalNotifier
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.JobStatus
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
                        selectedTab = MainTab.TODAY
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
                        TabPlaceholderScreen(
                            title = "好友与扫描对账",
                            description = "在此手动扫描抖音会话并按官方火花导入好友，支持单发测试与独立本机连续天数展示（待接入 TargetsRoute）。",
                            icon = Icons.Outlined.Group,
                        )
                    },
                    templatesContent = {
                        TabPlaceholderScreen(
                            title = "消息模板与文案池",
                            description = "在此管理固定文本、星期轮换 LRU 文案池与抖音原生「比心」表情模板（待接入 TemplatesRoute）。",
                            icon = Icons.Outlined.Forum,
                        )
                    },
                    settingsContent = {
                        TabPlaceholderScreen(
                            title = "设置与保活体检",
                            description = "在此配置同日发送窗口、Bark 通知、小米 HyperOS 保活体检、网页结构体检与定位器覆写（待接入 SettingsRoute）。",
                            icon = Icons.Outlined.Settings,
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

@Composable
private fun TabPlaceholderScreen(
    title: String,
    description: String,
    icon: ImageVector,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
