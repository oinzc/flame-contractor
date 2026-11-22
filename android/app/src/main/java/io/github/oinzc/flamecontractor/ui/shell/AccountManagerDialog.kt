package io.github.oinzc.flamecontractor.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.oinzc.flamecontractor.core.account.AccountManager
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.ui.theme.FlameThemeTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AccountStatusStyle(
    val label: String,
    val dotColor: Color,
    val fg: Color,
    val bg: Color,
)

internal fun isAccountAlertStatus(status: AccountStatus): Boolean = status in setOf(
    AccountStatus.AUTH_EXPIRED,
    AccountStatus.VERIFICATION_REQUIRED,
    AccountStatus.DOM_BROKEN,
    AccountStatus.COOLDOWN,
)

internal fun hasAccountAttention(
    account: AccountEntity,
    unknownCount: Int,
): Boolean = unknownCount > 0 || isAccountAlertStatus(account.status)

@Composable
internal fun accountStatusStyle(status: AccountStatus): AccountStatusStyle {
    val semantics = FlameThemeTokens.semanticColors
    return when (status) {
        AccountStatus.HEALTHY -> AccountStatusStyle(
            label = "健康",
            dotColor = semantics.healthy,
            fg = semantics.onHealthyContainer,
            bg = semantics.healthyContainer,
        )
        AccountStatus.RUNNING -> AccountStatusStyle(
            label = "执行中",
            dotColor = MaterialTheme.colorScheme.primary,
            fg = MaterialTheme.colorScheme.onPrimaryContainer,
            bg = MaterialTheme.colorScheme.primaryContainer,
        )
        AccountStatus.PENDING_LOGIN -> AccountStatusStyle(
            label = "待扫码",
            dotColor = semantics.warning,
            fg = semantics.onWarningContainer,
            bg = semantics.warningContainer,
        )
        AccountStatus.PAUSED -> AccountStatusStyle(
            label = "已暂停",
            dotColor = semantics.neutral,
            fg = semantics.onNeutralContainer,
            bg = semantics.neutralContainer,
        )
        AccountStatus.AUTH_EXPIRED -> AccountStatusStyle(
            label = "登录失效",
            dotColor = semantics.danger,
            fg = semantics.onDangerContainer,
            bg = semantics.dangerContainer,
        )
        AccountStatus.VERIFICATION_REQUIRED -> AccountStatusStyle(
            label = "需验证",
            dotColor = semantics.warning,
            fg = semantics.onWarningContainer,
            bg = semantics.warningContainer,
        )
        AccountStatus.DOM_BROKEN -> AccountStatusStyle(
            label = "结构异常",
            dotColor = semantics.danger,
            fg = semantics.onDangerContainer,
            bg = semantics.dangerContainer,
        )
        AccountStatus.COOLDOWN -> AccountStatusStyle(
            label = "已熔断",
            dotColor = semantics.warning,
            fg = semantics.onWarningContainer,
            bg = semantics.warningContainer,
        )
    }
}

@Composable
fun AccountManagerDialog(
    accounts: List<AccountEntity>,
    activeAccountId: String,
    unknownCountByAccount: Map<String, Int>,
    isMultiProfileSupported: Boolean,
    accountManager: AccountManager,
    onSelectAccount: (String) -> Unit,
    onOpenLoginForAccount: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var isBusy by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var createNameInput by remember { mutableStateOf("") }
    var createError by remember { mutableStateOf<String?>(null) }

    var renamingAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }

    var deletingAccount by remember { mutableStateOf<AccountEntity?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("多账号管理与切换")
                    Text(
                        text = "共 ${accounts.size} 个账号 · WebView Profile 隔离运行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                if (feedbackMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = feedbackMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                if (!isMultiProfileSupported) {
                    Surface(
                        color = FlameThemeTokens.semanticColors.warningContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = FlameThemeTokens.semanticColors.warning,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "当前系统 WebView 不支持多环境隔离，已禁用新增抖音号，仅保留默认账号运行。",
                                style = MaterialTheme.typography.bodySmall,
                                color = FlameThemeTokens.semanticColors.onWarningContainer,
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = accounts, key = { it.id }) { acc ->
                        val unknownCount = unknownCountByAccount[acc.id] ?: 0
                        AccountCardRow(
                            account = acc,
                            isActive = acc.id == activeAccountId,
                            unknownCount = unknownCount,
                            canDelete = accounts.size > 1,
                            busy = isBusy,
                            onSelect = {
                                onSelectAccount(acc.id)
                                onDismiss()
                            },
                            onToggleEnabled = { nextEnabled ->
                                errorMessage = null
                                feedbackMessage = null
                                isBusy = true
                                coroutineScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            accountManager.setAccountEnabled(acc.id, nextEnabled)
                                        }
                                        feedbackMessage = if (nextEnabled) {
                                            "已启用「${acc.displayName}」自动调度"
                                        } else {
                                            "已停用「${acc.displayName}」自动调度"
                                        }
                                    } catch (ce: CancellationException) {
                                        throw ce
                                    } catch (ex: Throwable) {
                                        errorMessage = ex.message ?: "更新账号状态失败"
                                    } finally {
                                        isBusy = false
                                    }
                                }
                            },
                            onOpenLogin = {
                                onSelectAccount(acc.id)
                                onDismiss()
                                onOpenLoginForAccount(acc.id)
                            },
                            onRequestRename = {
                                renamingAccount = acc
                                renameInput = acc.displayName
                                renameError = null
                            },
                            onRequestDelete = {
                                deletingAccount = acc
                            },
                        )
                    }
                }

                if (accounts.size <= 1) {
                    Text(
                        text = "至少保留 1 个抖音账号；新增账号后可按需删除其他账号。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = {
                            createNameInput = "抖音号 ${accounts.size + 1}"
                            createError = null
                            showCreateDialog = true
                        },
                        enabled = !isBusy && isMultiProfileSupported,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("添加抖音号")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isBusy,
            ) {
                Text("关闭")
            }
        },
    )

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { if (!isBusy) showCreateDialog = false },
            title = { Text("添加抖音号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "系统将为新账号分配独立 WebView 环境，并自动初始化默认续火文本与 3 个表情模板。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (createError != null) {
                        Text(
                            text = createError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedTextField(
                        value = createNameInput,
                        onValueChange = {
                            createNameInput = it
                            createError = null
                        },
                        label = { Text("账号备注名称") },
                        placeholder = { Text("例如：副号 / 备用号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !isBusy,
                    onClick = {
                        isBusy = true
                        createError = null
                        coroutineScope.launch {
                            try {
                                val created = withContext(Dispatchers.IO) {
                                    accountManager.createAccount(createNameInput)
                                }
                                showCreateDialog = false
                                errorMessage = null
                                feedbackMessage = "已创建「${created.displayName}」并自动切换为当前账号"
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (ex: Throwable) {
                                createError = ex.message ?: "创建账号失败"
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                ) {
                    Text("创建并切换")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isBusy,
                    onClick = { showCreateDialog = false },
                ) {
                    Text("取消")
                }
            },
        )
    }

    renamingAccount?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!isBusy) renamingAccount = null },
            title = { Text("重命名账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (renameError != null) {
                        Text(
                            text = renameError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = {
                            renameInput = it
                            renameError = null
                        },
                        label = { Text("新备注名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !isBusy,
                    onClick = {
                        isBusy = true
                        renameError = null
                        coroutineScope.launch {
                            try {
                                val updated = withContext(Dispatchers.IO) {
                                    accountManager.renameAccount(target.id, renameInput)
                                }
                                renamingAccount = null
                                errorMessage = null
                                feedbackMessage = "已重命名为「${updated.displayName}」"
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (ex: Throwable) {
                                renameError = ex.message ?: "重命名失败"
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isBusy,
                    onClick = { renamingAccount = null },
                ) {
                    Text("取消")
                }
            },
        )
    }

    deletingAccount?.let { target ->
        val semantics = FlameThemeTokens.semanticColors
        AlertDialog(
            onDismissRequest = { if (!isBusy) deletingAccount = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = semantics.danger,
                )
            },
            title = { Text("确认删除账号「${target.displayName}」？") },
            text = {
                Text(
                    "删除后将立即清空该账号下的所有好友、模板、发送历史、登录态 Cookie，并回收无引用本地图片；独立 WebView Profile 将在下次冷启动彻底销毁。此操作不可恢复。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                Button(
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = semantics.danger,
                        contentColor = Color.White,
                    ),
                    onClick = {
                        isBusy = true
                        errorMessage = null
                        feedbackMessage = null
                        coroutineScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    accountManager.deleteAccount(target.id)
                                }
                                deletingAccount = null
                                feedbackMessage = "已删除账号「${target.displayName}」并清理关联数据"
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (ex: Throwable) {
                                deletingAccount = null
                                errorMessage = ex.message ?: "删除账号失败"
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isBusy,
                    onClick = { deletingAccount = null },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountCardRow(
    account: AccountEntity,
    isActive: Boolean,
    unknownCount: Int,
    canDelete: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenLogin: () -> Unit,
    onRequestRename: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val style = accountStatusStyle(account.status)
    val semantics = FlameThemeTokens.semanticColors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = if (isActive) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = isActive,
                    onClick = if (!busy) onSelect else null,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(style.dotColor),
                        )
                        Text(
                            text = account.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    val sub = account.douyinNickname?.takeIf { it.isNotBlank() }?.let {
                        "已绑：$it"
                    } ?: "尚未绑定抖音账号昵称"
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = account.enabled,
                    enabled = !busy,
                    onCheckedChange = onToggleEnabled,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isActive) {
                    AccountChipPill(
                        text = "当前",
                        fg = MaterialTheme.colorScheme.onPrimaryContainer,
                        bg = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
                AccountChipPill(
                    text = style.label,
                    fg = style.fg,
                    bg = style.bg,
                )
                if (!account.enabled) {
                    AccountChipPill(
                        text = "已停用",
                        fg = semantics.onNeutralContainer,
                        bg = semantics.neutralContainer,
                    )
                }
                if (unknownCount > 0) {
                    AccountChipPill(
                        text = "$unknownCount 待核对",
                        fg = semantics.onDangerContainer,
                        bg = semantics.dangerContainer,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onOpenLogin,
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("扫码", style = MaterialTheme.typography.labelMedium)
                }
                IconButton(
                    onClick = onRequestRename,
                    enabled = !busy,
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "重命名账号",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onRequestDelete,
                    enabled = !busy && canDelete,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除账号",
                        tint = if (canDelete) semantics.danger else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountChipPill(
    text: String,
    fg: Color,
    bg: Color,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = bg,
        contentColor = fg,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
