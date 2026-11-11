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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import io.modelbridge.core.AccountCheckStatus
import io.modelbridge.core.AccountStatusFormat
import io.modelbridge.spi.AuthKind
import io.modelbridge.spi.Credential
import io.modelbridge.spi.Provider
import androidx.compose.ui.platform.LocalContext
import io.modelbridge.app.ConfigTransfer

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
    val accounts by vms.accounts.collectAsState()
    val credits by vms.accountCredits.collectAsState()
    val customRelays by vms.customRelays.collectAsState()
    val allModels by vms.models.collectAsState()
    val scanned by vms.scannedPorts.collectAsState()
    val scanning by vms.scanning.collectAsState()
    val context = LocalContext.current
    val transfer = remember { ConfigTransfer(context) }
    val accountIds = accounts.map { it.id }
    LaunchedEffect(accountIds) { accountIds.forEach { vms.refreshAccountCredit(it) } }

    var editing by remember { mutableStateOf<AccountEdit?>(null) }
    var showScanResults by remember { mutableStateOf(false) }
    val selectedGateways = remember { mutableStateMapOf<Int, Boolean>() }
    LaunchedEffect(scanned) {
        scanned.forEach { gateway ->
            if (gateway.port !in selectedGateways) selectedGateways[gateway.port] = true
        }
        selectedGateways.keys.filterNot { port -> scanned.any { it.port == port } }
            .forEach(selectedGateways::remove)
    }
    var webLogin by remember { mutableStateOf<AccountEdit?>(null) }
    var deviceLogin by remember { mutableStateOf<Provider?>(null) }
    var loomyLogin by remember { mutableStateOf(false) }

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

        // ---------- 账号级操作 ----------
        GroupTitle("账号")
        SettingsGroup {
            ActionRow(
                label = "导入账号",
                hint = "从 JSON 文件读取",
                onClick = {
                    transfer.importAccounts { list -> vms.importAccounts(list) }
                },
            )
            RowDivider()
            ActionRow(
                label = "导出账号",
                hint = "保存为 JSON 文件",
                onClick = {
                    if (accounts.isEmpty()) {
                        vms.toast("还没有登录账号")
                    } else {
                        transfer.exportAccounts(accounts)
                    }
                },
            )
            RowDivider()
            ActionRow(
                label = if (scanning) "正在扫描" else "扫描本机端口",
                hint = if (scanning) {
                    "探测 127.0.0.1 端口…"
                } else if (scanned.isEmpty()) {
                    "扫描本机端口自动发现，或手动添加任意 OpenAI 兼容地址"
                } else {
                    "发现：${scanned.joinToString(", ") { it.display }}"
                },
                onClick = { if (!scanning) vms.scanLocalPorts() },
            )
            if (!scanning && scanned.isNotEmpty()) {
                RowDivider()
                ActionRow(
                    label = "发现 ${scanned.size} 个网关",
                    value = "加入",
                    hint = "选择要加入的自定义中转站",
                    onClick = { showScanResults = true },
                )
            }
        }

        GroupTitle("自定义中转站")
        SettingsGroup {
            if (customRelays.isEmpty()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("还没有中转站", fontSize = 14.sp, color = AppStyle.BodyText)
                    Text(
                        "扫描本机端口自动发现，或手动添加任意 OpenAI 兼容地址",
                        fontSize = 12.sp,
                        color = AppStyle.SubtitleText,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                customRelays.forEachIndexed { index, relay ->
                    if (index > 0) RowDivider()
                    RelayRow(
                        relay = relay,
                        checked = checks[relay.id],
                        models = allModels.filter { it.providerId == relay.providerId },
                        onToggle = { vms.toggleAccount(relay.id, it) },
                        onCheck = { vms.checkAccount(relay) },
                        onDelete = { vms.deleteAccount(relay.id) },
                    )
                }
            }
            RowDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { if (!scanning) vms.scanLocalPorts() }) {
                    Text("扫描本机端口", fontSize = 12.sp)
                }
                TextButton(onClick = {
                    vms.customRelayProvider()?.let { editing = AccountEdit(provider = it) }
                }) { Text("手动添加", fontSize = 12.sp) }
                TextButton(onClick = { vms.checkAllCustomRelays() }) {
                    Text("检测连通性", fontSize = 12.sp)
                }
            }
        }

        GroupTitle("供应商")

        Column(
            modifier = Modifier.padding(top = 0.dp),
            verticalArrangement = Arrangement.spacedBy(AppStyle.CardSpacing),
        ) {
            groups.forEach { group ->
                ProviderCard(
                    vms = vms,
                    group = group,
                    checks = checks,
                    credits = credits,
                    onAdd = { editing = AccountEdit(provider = group.provider) },
                    onEdit = { acc -> editing = AccountEdit(provider = group.provider, account = acc) },
                    onWebLogin = { webLogin = AccountEdit(provider = group.provider) },
                    onDeviceLogin = { deviceLogin = group.provider },
                    onSmsLogin = { loomyLogin = true },
                )
            }
        }
    }

    if (showScanResults) {
        AlertDialog(
            onDismissRequest = { showScanResults = false },
            title = { Text("发现 ${scanned.size} 个网关") },
            text = {
                Column {
                    scanned.forEach { gateway ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selectedGateways[gateway.port] ?: true,
                                onCheckedChange = { selectedGateways[gateway.port] = it },
                            )
                            Text(gateway.display, fontSize = 13.sp, color = AppStyle.BodyText)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = scanned.filter { selectedGateways[it.port] ?: true }
                    vms.addScannedGateways(chosen)
                    showScanResults = false
                }) { Text("加入") }
            },
            dismissButton = {
                TextButton(onClick = { showScanResults = false }) { Text("取消") }
            },
        )
    }

    // 手动添加 / 编辑凭据
    editing?.let { edit ->
        AccountEditDialog(
            edit = edit,
            onDismiss = { editing = null },
            onSave = { label, credential, baseUrl ->
                val normalizedBaseUrl = if (edit.provider.id == "custom") {
                    io.modelbridge.core.ScannedGatewayParser.normalizeBaseUrl(baseUrl)
                } else {
                    baseUrl
                }
                vms.saveAccount(
                    id = edit.account?.id,
                    providerId = edit.provider.id,
                    label = label,
                    credential = credential,
                    baseUrl = normalizedBaseUrl,
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

    // Qoder / CodeBuddy 设备授权（WebView + 后台轮询）
    deviceLogin?.let { provider ->
        DeviceLoginDialog(
            provider = provider,
            vms = vms,
            onDismiss = { deviceLogin = null },
        )
    }

    // Loomy 手机验证码登录：发送短信与确认验证码分两步
    if (loomyLogin) {
        LoomySmsDialog(
            vms = vms,
            onDismiss = { loomyLogin = false },
        )
    }
}

@Composable
private fun RelayRow(
    relay: Account,
    checked: String?,
    models: List<io.modelbridge.spi.ModelInfo>,
    onToggle: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(relay.label.ifBlank { relay.baseUrl }, fontSize = 14.sp, color = AppStyle.BodyText)
            Text(
                buildList {
                    add(relay.baseUrl.ifBlank { "未填写地址" })
                    // 原版上游行：地址 · N 个模型（首个 等）· 固定策略 · 冷却中
                    if (models.isNotEmpty()) {
                        add("${models.size} 个模型（${models.first().id}${if (models.size > 1) " 等" else ""}）")
                    } else {
                        add("0 个模型")
                    }
                    if (relay.fixedPolicyId != null) add("固定策略")
                    add(AccountStatusFormat.enabledLabel(relay.enabled))
                    checked?.let { add(it) }
                }.joinToString(" · "),
                fontSize = 11.sp,
                color = AppStyle.SubtitleText,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Switch(checked = relay.enabled, onCheckedChange = onToggle)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        TextButton(onClick = onCheck) { Text("检测连通性", fontSize = 12.sp) }
        TextButton(onClick = onDelete) { Text("删除", fontSize = 12.sp, color = AppStyle.Accent) }
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
    credits: Map<String, ViewModels.AccountCreditUiState>,
    onAdd: () -> Unit,
    onEdit: (Account) -> Unit,
    onWebLogin: () -> Unit,
    onDeviceLogin: () -> Unit,
    onSmsLogin: () -> Unit,
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
                    text = "还没有登录账号",
                    fontSize = 12.sp,
                    color = AppStyle.SubtitleText,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                group.accounts.forEach { acc ->
                    AccountRow(
                        account = acc,
                        checkResult = checks[acc.id],
                        creditState = credits[acc.id],
                        onToggle = { vms.toggleAccount(acc.id, it) },
                        onCheck = { vms.checkAccount(acc) },
                        onEdit = { onEdit(acc) },
                        onDelete = { vms.deleteAccount(acc.id) },
                        vms = vms,
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
                        if (group.provider.id == "qoder" || group.provider.id == "codebuddy") {
                            TextButton(onClick = onDeviceLogin) { Text("设备授权", fontSize = 13.sp) }
                        } else {
                            TextButton(onClick = onWebLogin) { Text("OAuth 登录", fontSize = 13.sp) }
                        }
                        TextButton(onClick = onAdd) { Text("手动添加", fontSize = 13.sp) }
                    }
                    AuthKind.PhoneCode -> {
                        TextButton(onClick = onSmsLogin) { Text("手机号验证码登录", fontSize = 13.sp) }
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
    creditState: ViewModels.AccountCreditUiState?,
    onToggle: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    vms: ViewModels,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var selectPolicy by remember { mutableStateOf(false) }
    val policies by vms.policies.collectAsState()

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
                        val region = AccountStatusFormat.regionLabel(account.authRegion, account.authDomain)
                        if (region.isNotBlank()) append(" · $region")
                        append(" · ").append(AccountStatusFormat.enabledLabel(account.enabled))
                        AccountStatusFormat.expiryLabel(
                            account.authExpiresAtMillis,
                            System.currentTimeMillis(),
                        ).takeIf { it.isNotBlank() }?.let { append(" · $it") }
                        if (account.baseUrl.isNotBlank()) append(" · ${account.baseUrl}")
                        append(" · ").append(checkResult ?: AccountCheckStatus.UNKNOWN)
                    },
                    fontSize = 11.sp,
                    color = when {
                        checkResult == null -> AppStyle.SubtitleText
                        AccountCheckStatus.isReachable(checkResult) -> AppStyle.BodyText
                        checkResult == AccountCheckStatus.PENDING -> AppStyle.SubtitleText
                        else -> AppStyle.Accent
                    },
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Switch(checked = account.enabled, onCheckedChange = onToggle)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            TextButton(onClick = onCheck) {
                Text(
                    if (checkResult == AccountCheckStatus.PENDING) "正在检测" else "检测连通性",
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = onEdit) { Text("编辑", fontSize = 12.sp) }
            TextButton(onClick = { vms.refreshAccountCredential(account.id) }) {
                Text("刷新凭证", fontSize = 12.sp)
            }
            TextButton(onClick = { selectPolicy = true }) { Text("策略", fontSize = 12.sp) }
            TextButton(onClick = { confirmDelete = true }) {
                Text("删除", fontSize = 12.sp, color = AppStyle.Accent)
            }
        }
        val bound = policies.firstOrNull { it.id == account.fixedPolicyId }
        val quota = creditState?.quota
        val canRefreshCredit = vms.supportsAccountCredit(account.providerId)
        val canCheckIn = vms.supportsAccountCheckIn(account.providerId)
        if (canRefreshCredit || canCheckIn) {
            Text(
                text = buildList {
                    add(
                        when {
                            creditState?.busy == true -> "可用积分：查询中…"
                            quota?.known == true -> "可用积分：${quota.balance}"
                            quota?.error?.isNotBlank() == true -> "可用积分：查询失败"
                            else -> "可用积分：…"
                        },
                    )
                    if (canCheckIn) {
                        add(if (creditState?.checkedInToday == true) "今日已签到" else "今日未签到")
                    }
                    quota?.detail?.takeIf { it.isNotBlank() }?.let { add("额度：${it}") }
                    quota?.error?.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString(" · "),
                fontSize = 11.sp,
                color = AppStyle.SubtitleText,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                if (canRefreshCredit) {
                    TextButton(
                        enabled = creditState?.busy != true,
                        onClick = { vms.refreshAccountCredit(account.id) },
                    ) { Text("刷新积分", fontSize = 12.sp) }
                }
                if (canCheckIn) {
                    TextButton(
                        enabled = creditState?.busy != true,
                        onClick = { vms.checkInAccount(account.id) },
                    ) { Text("签到", fontSize = 12.sp) }
                }
            }
        }
        if (account.fixedPolicyId != null) {
            Text(
                "固定策略：${bound?.name ?: "已删除"}" + if (bound?.enabled == false) " · 已停用" else "",
                fontSize = 11.sp,
                color = AppStyle.SubtitleText,
            )
        }
    }

    if (selectPolicy) {
        AlertDialog(
            onDismissRequest = { selectPolicy = false },
            title = { Text("固定策略") },
            text = {
                Column {
                    TextButton(onClick = { vms.bindPolicy(account.id, null); selectPolicy = false }) {
                        Text("不固定，按模型自动匹配")
                    }
                    policies.forEach { policy ->
                        TextButton(onClick = { vms.bindPolicy(account.id, policy.id); selectPolicy = false }) {
                            Text(policy.name + if (policy.enabled) "" else " · 已停用")
                        }
                    }
                    if (policies.isEmpty()) Text("先到首页「策略管理」新建策略")
                }
            },
            confirmButton = { TextButton(onClick = { selectPolicy = false }) { Text("取消") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除账号", fontSize = 17.sp) },
            text = { Text("确定删除「${account.label}」？本地凭证将一并清除。", fontSize = 14.sp) },
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
