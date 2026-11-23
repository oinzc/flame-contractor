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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.ui.components.FlameCard
import io.github.oinzc.flamecontractor.ui.components.FlameMiniTag
import io.github.oinzc.flamecontractor.ui.components.FlameModalSheet
import io.github.oinzc.flamecontractor.ui.components.FlameStatusPill
import io.github.oinzc.flamecontractor.ui.components.FlameTone
import io.github.oinzc.flamecontractor.ui.components.FlameTopPillButton
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

internal fun accountStatusTone(status: AccountStatus): FlameTone = when (status) {
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

@Composable
internal fun accountStatusStyle(status: AccountStatus): AccountStatusStyle {
    val palette = FlameThemeTokens.palette
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
            dotColor = palette.ember,
            fg = palette.ember,
            bg = palette.emberContainer,
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
    AccountManagerSheet(
        accounts = accounts,
        activeAccountId = activeAccountId,
        unknownCountByAccount = unknownCountByAccount,
        isMultiProfileSupported = isMultiProfileSupported,
        accountManager = accountManager,
        onSelectAccount = onSelectAccount,
        onOpenLoginForAccount = onOpenLoginForAccount,
        onDismiss = onDismiss,
    )
}

@Composable
fun AccountManagerSheet(
    accounts: List<AccountEntity>,
    activeAccountId: String,
    unknownCountByAccount: Map<String, Int>,
    isMultiProfileSupported: Boolean,
    accountManager: AccountManager,
    onSelectAccount: (String) -> Unit,
    onOpenLoginForAccount: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
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

    FlameModalSheet(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = "多账号管理与切换",
        subtitle = "共 ${accounts.size} 个账号 · WebView Profile 隔离运行",
        trailingHeader = {
            FlameTopPillButton(
                text = "新增",
                leadingIcon = Icons.Default.Add,
                primary = true,
                enabled = !isBusy && isMultiProfileSupported,
                onClick = {
                    createNameInput = "抖音号 ${accounts.size + 1}"
                    createError = null
                    showCreateDialog = true
                },
            )
        },
    ) {
        if (isBusy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            )
        }

        if (errorMessage != null) {
            Surface(
                color = semantics.dangerContainer,
                border = BorderStroke(1.dp, semantics.dangerBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            ) {
                Text(
                    text = errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantics.onDangerContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        if (feedbackMessage != null) {
            Surface(
                color = semantics.healthyContainer,
                border = BorderStroke(1.dp, semantics.healthy.copy(alpha = 0.28f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            ) {
                Text(
                    text = feedbackMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = semantics.onHealthyContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        if (!isMultiProfileSupported) {
            Surface(
                color = semantics.warningContainer,
                border = BorderStroke(1.dp, semantics.warningBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = semantics.warning,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "当前系统 WebView 不支持多环境隔离，已禁用新增抖音号，仅保留默认账号运行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = semantics.onWarningContainer,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            items(items = accounts, key = { it.id }) { acc ->
                val unknownCount = unknownCountByAccount[acc.id] ?: 0
                val deleteNotice = buildAccountDeleteNotice(acc.id, accounts.size)
                AccountSheetItemCard(
                    account = acc,
                    isActive = acc.id == activeAccountId,
                    unknownCount = unknownCount,
                    canDelete = deleteNotice.canDelete,
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
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = buildAccountDeleteNotice(DEFAULT_ACCOUNT_ID, accounts.size).detailMessage,
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkTertiary,
            )
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { if (!isBusy) showCreateDialog = false },
            containerColor = palette.surface,
            titleContentColor = palette.ink,
            textContentColor = palette.inkSecondary,
            title = { Text("添加抖音号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "系统将为新账号分配独立 WebView 环境，并自动初始化默认续火文本与 3 个表情模板。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                    )
                    if (createError != null) {
                        Text(
                            text = createError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = semantics.danger,
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.monoCtaContainer,
                        contentColor = palette.monoCtaContent,
                    ),
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
            containerColor = palette.surface,
            titleContentColor = palette.ink,
            textContentColor = palette.inkSecondary,
            title = { Text("重命名账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (renameError != null) {
                        Text(
                            text = renameError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = semantics.danger,
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.monoCtaContainer,
                        contentColor = palette.monoCtaContent,
                    ),
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
        val deleteNotice = buildAccountDeleteNotice(target.id, accounts.size)
        AlertDialog(
            onDismissRequest = { if (!isBusy) deletingAccount = null },
            containerColor = palette.surface,
            titleContentColor = palette.ink,
            textContentColor = palette.inkSecondary,
            icon = {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = semantics.danger,
                )
            },
            title = { Text("确认删除账号「${target.displayName}」？") },
            text = {
                Text(
                    text = deleteNotice.detailMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
                )
            },
            confirmButton = {
                Button(
                    enabled = !isBusy && deleteNotice.canDelete,
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
private fun AccountSheetItemCard(
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
    val palette = FlameThemeTokens.palette
    val semantics = FlameThemeTokens.semanticColors
    val style = accountStatusStyle(account.status)
    val tone = accountStatusTone(account.status)

    FlameCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        containerColor = if (isActive) palette.surfaceSubtle else palette.surface,
        borderColor = if (isActive) palette.monoCtaContainer.copy(alpha = 0.45f) else palette.hairline,
        contentPadding = PaddingValues(13.dp),
        onClick = if (!busy) onSelect else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (isActive) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Outlined.RadioButtonUnchecked
                },
                contentDescription = if (isActive) "当前激活账号" else "切换至该账号",
                tint = if (isActive) palette.monoCtaContainer else palette.hairlineStrong,
                modifier = Modifier.size(21.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
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
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isActive) {
                        FlameMiniTag(text = "当前", tone = FlameTone.EMBER)
                    }
                }
                val sub = account.douyinNickname?.takeIf { it.isNotBlank() }?.let {
                    "已绑：$it"
                } ?: "尚未绑定抖音账号昵称"
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary,
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

        Spacer(modifier = Modifier.height(8.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FlameStatusPill(
                text = style.label,
                tone = tone,
            )
            if (!account.enabled) {
                FlameMiniTag(text = "自动调度已停用", tone = FlameTone.NEUTRAL)
            }
            if (unknownCount > 0) {
                FlameMiniTag(text = "$unknownCount 条待核对", tone = FlameTone.DANGER)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(thickness = 1.dp, color = palette.hairline)
        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(enabled = !busy, onClick = onOpenLogin),
                shape = RoundedCornerShape(999.dp),
                color = palette.surface,
                contentColor = palette.ink,
                border = BorderStroke(1.dp, palette.hairline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "扫码登录",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onRequestRename,
                    enabled = !busy,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "重命名账号",
                        tint = palette.inkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onRequestDelete,
                    enabled = !busy && canDelete,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "删除账号",
                        tint = if (canDelete) semantics.danger else palette.hairlineStrong,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
