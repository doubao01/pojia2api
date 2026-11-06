package io.modelbridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.modelbridge.app.ViewModels
import io.modelbridge.core.Account
import io.modelbridge.spi.AuthKind
import io.modelbridge.spi.Credential
import io.modelbridge.spi.Provider

/**
 * 供应商页：按供应商分组列出账号，可登录、测试、启停、删除。
 *
 * 对照原版「供应商」页的动作：登录账号（网页登录 / 设备授权 /
 * 手机验证码 / OAuth / 无需登录）、手动添加、检测连通性、删除。
 */
@Composable
fun AccountsScreen(vms: ViewModels) {
    val groups by vms.providerGroups.collectAsState()
    val checks by vms.checks.collectAsState()

    var editing by remember { mutableStateOf<AccountEdit?>(null) }
    var webLogin by remember { mutableStateOf<AccountEdit?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "供应商与账号",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppStyle.BodyText,
        )
        Text(
            "关掉的账号不会出现在模型列表里",
            fontSize = 12.sp,
            color = AppStyle.SubtitleText,
            modifier = Modifier.padding(top = 2.dp),
        )

        Column(
            modifier = Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(AppStyle.CardSpacing),
        ) {
            groups.forEach { group ->
                ProviderCard(
                    vms = vms,
                    group = group,
                    checks = checks,
                    onAdd = { editing = AccountEdit(provider = group.provider) },
                    onEdit = { acc -> editing = AccountEdit(provider = group.provider, account = acc) },
                    onWebLogin = { webLogin = AccountEdit(provider = group.provider) },
                )
            }
        }
    }

    // 手动添加 / 编辑凭据
    editing?.let { edit ->
        AccountEditDialog(
            edit = edit,
            onDismiss = { editing = null },
            onSave = { label, credential, baseUrl ->
                vms.saveAccount(
                    id = edit.account?.id,
                    providerId = edit.provider.id,
                    label = label,
                    credential = credential,
                    baseUrl = baseUrl,
                )
                editing = null
            },
        )
    }

    // 网页登录（WebView 抓 Cookie）
    webLogin?.let { edit ->
        WebLoginDialog(
            provider = edit.provider,
            onDismiss = { webLogin = null },
            onCookie = { raw ->
                vms.saveAccount(
                    providerId = edit.provider.id,
                    label = "${edit.provider.displayName}（网页登录）",
                    credential = Credential.Cookies(raw),
                )
                webLogin = null
            },
        )
    }
}

/** 编辑态：只带 account 表示改已有的，否则是新增。 */
data class AccountEdit(
    val provider: Provider,
    val account: Account? = null,
)

@Composable
private fun ProviderCard(
    vms: ViewModels,
    group: ProviderGroup,
    checks: Map<String, String>,
    onAdd: () -> Unit,
    onEdit: (Account) -> Unit,
    onWebLogin: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppStyle.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {

            // ---- 供应商标题行 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        group.provider.displayName,
                        fontSize = 15.sp,
                        color = AppStyle.BodyText,
                    )
                    Text(
                        text = buildString {
                            append(group.provider.id)
                            append(" · ").append(authLabel(group.provider.authKind))
                            if (group.accounts.isNotEmpty()) {
                                append(" · 共 ").append(group.accounts.size).append(" 个账号")
                            }
                        },
                        fontSize = 12.sp,
                        color = AppStyle.SubtitleText,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = group.anyEnabled,
                    onCheckedChange = { vms.toggleProvider(group.provider.id, !group.anyEnabled) },
                )
            }

            // ---- 账号列表 ----
            if (group.accounts.isEmpty()) {
                Text(
                    text = "还没有账号",
                    fontSize = 12.sp,
                    color = AppStyle.SubtitleText,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                group.accounts.forEach { acc ->
                    AccountRow(
                        account = acc,
                        checkResult = checks[acc.id],
                        onToggle = { vms.toggleAccount(acc.id, it) },
                        onCheck = { vms.checkAccount(acc) },
                        onEdit = { onEdit(acc) },
                        onDelete = { vms.deleteAccount(acc.id) },
                    )
                }
            }

            // ---- 动作 ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (group.provider.authKind) {
                    AuthKind.None -> {
                        TextButton(onClick = onAdd) { Text("添加账号", fontSize = 13.sp) }
                    }
                    AuthKind.ApiKey -> {
                        TextButton(onClick = onAdd) { Text("添加密钥", fontSize = 13.sp) }
                    }
                    AuthKind.Cookie -> {
                        TextButton(onClick = onWebLogin) { Text("网页登录", fontSize = 13.sp) }
                        TextButton(onClick = onAdd) { Text("手动填 Cookie", fontSize = 13.sp) }
                    }
                    AuthKind.OAuth -> {
                        TextButton(onClick = onWebLogin) { Text("OAuth 登录", fontSize = 13.sp) }
                        TextButton(onClick = onAdd) { Text("手动添加", fontSize = 13.sp) }
                    }
                    AuthKind.PhoneCode -> {
                        TextButton(onClick = onAdd) { Text("手机号验证码登录", fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: Account,
    checkResult: String?,
    onToggle: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.label.ifBlank { "未命名" },
                    fontSize = 13.sp,
                    color = AppStyle.BodyText,
                )
                Text(
                    text = buildString {
                        append(credentialLabel(account.credential))
                        if (account.baseUrl.isNotBlank()) append(" · ${account.baseUrl}")
                        checkResult?.let { append(" · $it") }
                    },
                    fontSize = 11.sp,
                    color = when {
                        checkResult == null -> AppStyle.SubtitleText
                        checkResult == "可用" -> AppStyle.BodyText
                        checkResult == "检测中…" -> AppStyle.SubtitleText
                        else -> AppStyle.Accent
                    },
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Switch(checked = account.enabled, onCheckedChange = onToggle)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            TextButton(onClick = onCheck) { Text("检测", fontSize = 12.sp) }
            TextButton(onClick = onEdit) { Text("编辑", fontSize = 12.sp) }
            TextButton(onClick = { confirmDelete = true }) {
                Text("删除", fontSize = 12.sp, color = AppStyle.Accent)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除账号", fontSize = 17.sp) },
            text = { Text("确定删除「${account.label}」？此操作不可撤销。", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = AppStyle.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

/** 凭据类型的中文描述，用于账号行副标题。 */
internal fun credentialLabel(credential: Credential): String = when (credential) {
    is Credential.None -> "无需凭据"
    is Credential.ApiKey -> "API Key"
    is Credential.Cookies -> "Cookie"
    is Credential.Session -> "会话"
    is Credential.OAuth -> "OAuth 令牌"
}

internal fun authLabel(kind: AuthKind): String = when (kind) {
    AuthKind.None -> "无需登录"
    AuthKind.ApiKey -> "API Key"
    AuthKind.Cookie -> "Cookie"
    AuthKind.OAuth -> "OAuth 登录"
    AuthKind.PhoneCode -> "手机验证码"
}

/** 界面用的供应商分组：一个供应商下挂它的账号。 */
data class ProviderGroup(
    val provider: Provider,
    val accounts: List<Account>,
) {
    val anyEnabled: Boolean get() = accounts.any { it.enabled }
    val enabledCount: Int get() = accounts.count { it.enabled }
}
