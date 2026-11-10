package io.modelbridge.app

import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.modelbridge.app.prefs.GatewaySettings
import io.modelbridge.app.service.FloatingBallService
import io.modelbridge.app.service.ServiceManager
import io.modelbridge.app.ui.ProviderGroup
import io.modelbridge.core.Account
import io.modelbridge.core.ProviderRegistry
import io.modelbridge.providers.auth.DeviceAuthClient
import io.modelbridge.providers.auth.DeviceAuthCredentials
import io.modelbridge.providers.auth.DeviceAuthPollResult
import io.modelbridge.providers.auth.DeviceAuthSession
import io.modelbridge.providers.auth.LoomySession
import io.modelbridge.providers.auth.LoomySmsAuth
import io.modelbridge.providers.auth.LoomySmsChallenge
import io.modelbridge.providers.auth.QoderClientIds
import io.modelbridge.providers.auth.QoderDeviceAuth
import io.modelbridge.providers.auth.WorkBuddyDeviceAuth
import io.modelbridge.providers.account.AccountActionResult
import io.modelbridge.providers.account.AccountCapability
import io.modelbridge.providers.account.AccountQuota
import io.modelbridge.providers.account.ProviderTask
import io.modelbridge.providers.account.ProviderTaskList
import io.modelbridge.spi.Credential
import io.modelbridge.spi.ModelInfo
import io.modelbridge.spi.Provider
import io.modelbridge.spi.ProviderContext
import io.modelbridge.spi.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 持有界面状态、连接内核的 ViewModel。
 *
 * 刻意只做一个：所有页面共用它，避免多个 ViewModel 交叉订阅内核。
 */
class ViewModels(
    private val container: AppContainer,
    val runner: GatewayRunner,
    private val serviceManager: ServiceManager,
) : ViewModel() {

    /**
     * 启动网关。
     *
     * 走后台服务而非直接调 runner：Android 会回收后台进程，
     * 网关被回收后手机上的客户端就连不上了。
     */
    fun startGateway(port: Int = container.settings.current().port) {
        try {
            serviceManager.start(port)
        } catch (error: RuntimeException) {
            toast("无法启动前台服务，请保持应用在前台后重试")
        }
    }

    /** 停止网关。 */
    fun stopGateway() {
        serviceManager.stop()
    }

    private val registry: ProviderRegistry = container.registry

    /** 模型目录。 */
    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _loadingModels = MutableStateFlow(false)
    val loadingModels: StateFlow<Boolean> = _loadingModels.asStateFlow()

    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()

    /** 设置。 */
    val settings: StateFlow<GatewaySettings> = container.settings.settings

    /** 账号。 */
    val accounts: StateFlow<List<Account>> = container.accountStore.accountsFlow

    /** 供应商分组。账号变更时自动重建。 */
    val providerGroups: StateFlow<List<ProviderGroup>> = container.accountStore
        .accountsFlow
        .map { buildGroups() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = buildGroups(),
        )

    /** 访问日志。 */
    val log: StateFlow<List<LogEntry>> = runner.log

    val policies: StateFlow<List<io.modelbridge.core.policy.Policy>> =
        container.policyStore.policiesFlow

    fun savePolicy(policy: io.modelbridge.core.policy.Policy, onSaved: () -> Unit) {
        if (policy.name.isBlank()) {
            toast("填个名称")
            return
        }
        if (policy.instruction.isBlank()) {
            toast("策略正文不能为空")
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { container.policyStore.upsert(policy) }
                toast("策略已保存")
                onSaved()
            } catch (error: Exception) {
                toast("策略无法保存")
            }
        }
    }

    fun togglePolicy(policy: io.modelbridge.core.policy.Policy, enabled: Boolean) {
        savePolicy(policy.copy(enabled = enabled)) {}
    }

    fun deletePolicy(policy: io.modelbridge.core.policy.Policy, onDeleted: () -> Unit = {}) {
        if (policy.builtin) {
            toast("内置策略不可删除")
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { container.policyStore.delete(policy.id) }
                toast("策略已删除")
                onDeleted()
            } catch (error: Exception) {
                toast("策略无法删除")
            }
        }
    }

    fun importPolicies(list: List<io.modelbridge.core.policy.Policy>) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { container.policyStore.replaceAll(list) }
                toast("已导入 ${list.size} 条策略")
            } catch (error: Exception) {
                toast("策略无法导入，原存档已保留")
            }
        }
    }

    fun bindPolicy(accountId: String, policyId: String?) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val account = container.accountStore.find(accountId) ?: error("账号不存在")
                    container.accountStore.upsert(account.copy(fixedPolicyId = policyId))
                }
                toast("策略绑定已更新")
            } catch (error: Exception) {
                toast("策略绑定无法保存")
            }
        }
    }

    /** 连通性检测结果，按账号 id 索引。 */
    private val _checks = MutableStateFlow<Map<String, String>>(emptyMap())
    val checks: StateFlow<Map<String, String>> = _checks.asStateFlow()

    /** 一次性提示消息（Toast 用）。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    data class TaskQuotaUiState(
        val busy: Boolean = false,
        val quotas: Map<String, AccountQuota> = emptyMap(),
        val tasks: List<ProviderTask> = emptyList(),
        val tasksError: String = "",
        val lastResult: AccountActionResult? = null,
        val lastRefreshedAtMillis: Long = 0,
    )

    private val _taskQuota = MutableStateFlow(TaskQuotaUiState())
    val taskQuota: StateFlow<TaskQuotaUiState> = _taskQuota.asStateFlow()

    init {
        refreshModels()
        refreshTaskQuota()
    }

    /** 登录/导入账号后刷新一次任务与额度；无可用账号时明确提示。 */
    fun refreshTaskQuota() {
        if (_taskQuota.value.busy) return
        _taskQuota.value = _taskQuota.value.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val accounts = container.accountStore.all().filter { it.enabled }
            val quotas = accounts.mapNotNull { account ->
                val quota = container.accountServices.credit(account)
                account.id to quota
            }.toMap()
            val tasks = accounts.firstNotNullOfOrNull { account ->
                val result = container.accountServices.tasks(account)
                result.takeIf { it.tasks.isNotEmpty() }
            } ?: ProviderTaskList(error = "没有可显示的任务")
            _taskQuota.value = _taskQuota.value.copy(
                busy = false,
                quotas = quotas,
                tasks = tasks.tasks,
                tasksError = tasks.error,
                lastRefreshedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun runAccountAction(capability: AccountCapability) {
        if (_taskQuota.value.busy) return
        _taskQuota.value = _taskQuota.value.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val accounts = container.accountStore.all().filter { it.enabled }
            val results = accounts.map { account ->
                account.label to when (capability) {
                    AccountCapability.CheckIn -> container.accountServices.checkIn(account)
                    AccountCapability.Tasks -> container.accountServices.claimTasks(account)
                    else -> AccountActionResult(false, "该操作不支持", unsupported = true)
                }
            }
            val actionable = results.filterNot { it.second.unsupported }
            val success = actionable.count { it.second.ok }
            val failed = actionable.count { !it.second.ok }
            val skipped = results.count { it.second.unsupported }
            val summary = buildList {
                if (success > 0) add("成功 $success 个")
                if (failed > 0) add("失败 $failed 个")
                if (skipped > 0) add("跳过 $skipped 个")
            }.joinToString("；").ifBlank { "没有可执行的账号" }
            val details = actionable.joinToString("；") { (label, result) ->
                "$label：${result.message.ifBlank { if (result.ok) "已完成" else "未知原因" }}"
            }
            val result = AccountActionResult(
                ok = success > 0,
                message = listOf(summary, details).filter { it.isNotBlank() }.joinToString("；"),
            )
            _taskQuota.value = _taskQuota.value.copy(busy = false, lastResult = result)
            toast(result.message)
        }
    }

    fun checkInAllAccounts() = runAccountAction(AccountCapability.CheckIn)
    fun claimAllAccountTasks() = runAccountAction(AccountCapability.Tasks)

    // ---------- 模型 ----------

    /** 拉取一次模型目录；同时触发共享 runner 的凭据维护与目录重拉。 */
    fun refreshModels() {
        _loadingModels.value = true
        _modelsError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.credentialRefresher.refreshDue() }
            runCatching { container.gateway.refreshCatalog(force = true) }
                .onSuccess { result ->
                    _models.value = result.models
                    if (result.errors.isNotEmpty()) {
                        _modelsError.value = result.errors
                            .joinToString("；") { "${it.providerId}: ${it.message}" }
                    }
                }
                .onFailure { _modelsError.value = it.message ?: "刷新失败" }
            _loadingModels.value = false
        }
    }

    // ---------- 设备授权登录 ----------

    data class DeviceLoginUiState(
        val providerId: String,
        val session: DeviceAuthSession? = null,
        val pending: Boolean = true,
        val message: String = "正在准备登录…",
        val finished: Boolean = false,
    )

    private val _deviceLogin = MutableStateFlow<DeviceLoginUiState?>(null)
    val deviceLogin: StateFlow<DeviceLoginUiState?> = _deviceLogin.asStateFlow()
    private var deviceLoginJob: kotlinx.coroutines.Job? = null
    private var deviceLoginClient: DeviceAuthClient? = null

    fun beginDeviceLogin(providerId: String, region: String = "cn") {
        if (providerId != "qoder" && providerId != "codebuddy") {
            toast("该供应商暂未接入设备授权")
            return
        }
        deviceLoginJob?.cancel()
        deviceLoginJob = viewModelScope.launch(Dispatchers.IO) {
            val client: DeviceAuthClient = when (providerId) {
                "qoder" -> {
                    val cn = BuildConfig.QODER_CLIENT_ID_CN
                    val global = BuildConfig.QODER_CLIENT_ID_GLOBAL
                    val selected = if (region == "global") global else cn
                    if (selected.isBlank()) {
                        throw IllegalStateException("Qoder 客户端标识未配置，请通过构建环境注入")
                    }
                    QoderDeviceAuth(container.httpClient, QoderClientIds(cn = cn, global = global))
                }
                else -> WorkBuddyDeviceAuth(container.httpClient)
            }
            deviceLoginClient = client
            try {
                val session = client.begin(region)
                _deviceLogin.value = DeviceLoginUiState(
                    providerId = providerId,
                    session = session,
                    message = "请在下方页面完成登录，等待授权确认",
                )
                val deadline = minOf(session.expiresAtMillis, System.currentTimeMillis() + 300_000)
                while (kotlinx.coroutines.currentCoroutineContext().isActive &&
                    System.currentTimeMillis() < deadline
                ) {
                    when (val result = client.poll(session)) {
                        DeviceAuthPollResult.Pending -> {
                            _deviceLogin.value = _deviceLogin.value?.copy(
                                pending = true,
                                message = "等待授权确认…",
                            )
                            delay(session.pollIntervalMillis)
                        }
                        is DeviceAuthPollResult.Success -> {
                            saveDeviceCredentials(result.credentials)
                            _deviceLogin.value = _deviceLogin.value?.copy(
                                pending = false,
                                message = "登录成功：${result.credentials.nickname.ifBlank { "已登录" }}",
                                finished = true,
                            )
                            return@launch
                        }
                        DeviceAuthPollResult.Cancelled -> {
                            _deviceLogin.value = null
                            return@launch
                        }
                        DeviceAuthPollResult.Expired -> {
                            _deviceLogin.value = _deviceLogin.value?.copy(
                                pending = false, message = "授权已过期，请重新开始", finished = true,
                            )
                            return@launch
                        }
                        is DeviceAuthPollResult.Failed -> {
                            _deviceLogin.value = _deviceLogin.value?.copy(
                                pending = false, message = result.failure.message, finished = true,
                            )
                            return@launch
                        }
                    }
                }
                client.cancel(session)
                _deviceLogin.value = _deviceLogin.value?.copy(
                    pending = false, message = "授权超时，请重新开始", finished = true,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _deviceLogin.value = DeviceLoginUiState(
                    providerId = providerId, pending = false,
                    message = error.message ?: "无法开始设备授权", finished = true,
                )
            }
        }
    }

    private fun saveDeviceCredentials(credentials: DeviceAuthCredentials) {
        val label = credentials.nickname.ifBlank { "${credentials.providerId}（设备授权）" }
        saveAccount(
            providerId = credentials.providerId,
            label = label,
            credential = credentials.credential,
            authRegion = credentials.region,
            authUid = credentials.uid,
            authTenantId = credentials.tenantId,
            authDomain = credentials.domain,
            authNickname = credentials.nickname,
            authTokenKind = credentials.tokenKind.ifBlank { credentials.credential.tokenType },
            authExpiresAtMillis = credentials.credential.expiresAtMillis,
        )
    }

    fun cancelDeviceLogin() {
        deviceLoginJob?.cancel()
        deviceLoginJob = null
        deviceLogin?.value?.session?.let { deviceLoginClient?.cancel(it) }
        deviceLoginClient = null
        _deviceLogin.value = null
    }

    override fun onCleared() {
        cancelDeviceLogin()
        super.onCleared()
    }


    // ---------- Loomy 手机验证码登录 ----------

    data class LoomyLoginUiState(
        val phone: String = "",
        val challenge: LoomySmsChallenge? = null,
        val busy: Boolean = false,
        val message: String = "请输入手机号，点击发送验证码",
        val finished: Boolean = false,
    )

    private val _loomyLogin = MutableStateFlow(LoomyLoginUiState())
    val loomyLogin: StateFlow<LoomyLoginUiState> = _loomyLogin.asStateFlow()

    fun sendLoomyCode(phone: String) {
        val auth = container.loomySmsAuth
        if (auth == null) {
            _loomyLogin.value = LoomyLoginUiState(
                phone = phone,
                message = "Loomy 签名材料未配置，暂不能发送短信",
            )
            return
        }
        _loomyLogin.value = LoomyLoginUiState(phone = phone, busy = true, message = "正在发送验证码…")
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { auth.sendCode(phone) }
                .onSuccess { challenge ->
                    _loomyLogin.value = LoomyLoginUiState(
                        phone = challenge.phone,
                        challenge = challenge,
                        message = "验证码已发送，请输入短信中的验证码",
                    )
                }
                .onFailure { error ->
                    _loomyLogin.value = LoomyLoginUiState(
                        phone = phone,
                        message = error.message ?: "验证码发送失败",
                    )
                }
        }
    }

    fun completeLoomyLogin(code: String) {
        val auth = container.loomySmsAuth
        val state = _loomyLogin.value
        val challenge = state.challenge
        if (auth == null || challenge == null) {
            _loomyLogin.value = state.copy(message = "请先发送验证码")
            return
        }
        _loomyLogin.value = state.copy(busy = true, message = "正在验证…")
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { auth.checkCode(challenge, code) }
                .onSuccess { session ->
                    saveAccount(
                        providerId = "loomy",
                        label = "Loomy（${session.userId.ifBlank { session.phone }}）",
                        credential = Credential.Session(session.phone, session.session),
                        authUid = session.userId,
                        authNickname = session.userId,
                        authTokenKind = "Session",
                        authExpiresAtMillis = session.expiresAtMillis,
                    )
                    _loomyLogin.value = state.copy(
                        busy = false,
                        message = "登录成功",
                        finished = true,
                    )
                }
                .onFailure { error ->
                    _loomyLogin.value = state.copy(busy = false, message = error.message ?: "验证码错误")
                }
        }
    }

    fun resetLoomyLogin() {
        _loomyLogin.value = LoomyLoginUiState()
    }
    fun updateSettings(block: (GatewaySettings) -> GatewaySettings) {
        val before = container.settings.current()
        container.settings.edit(block)
        container.applySettings()
        val after = container.settings.current()
        // 悬浮球不是纯数据：开关或尺寸一变就要真的显示/隐藏
        if (before.showFloatingBall != after.showFloatingBall ||
            before.floatingBallSizeDp != after.floatingBallSizeDp
        ) {
            syncFloatingBall(
                after.showFloatingBall,
                requestPermission = after.showFloatingBall && !before.showFloatingBall,
            )
        }
    }

    /**
     * 按当前设置显示或隐藏悬浮球。
     *
     * 悬浮窗权限被拒绝时保持关闭并提示，避免球显示不出来又不给原因。
     */
    fun syncFloatingBall(show: Boolean, requestPermission: Boolean = false) {
        if (!show) {
            FloatingBallService.apply(container.context, false)
            return
        }
        if (!Settings.canDrawOverlays(container.context)) {
            // 恢复界面、返回授权页时不重复弹窗；只响应开关的主动操作。
            if (requestPermission) {
                runCatching {
                    container.context.startActivity(
                        android.content.Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + container.context.packageName),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure {
                    toast("需要「显示在其他应用上层」权限才能显示悬浮球")
                }
            }
            return
        }
        FloatingBallService.apply(container.context, true)
    }

    fun resetSettings() {
        container.settings.resetToDefaults()
        container.applySettings()
    }

    // ---------- 账号 ----------

    /** 启停某个供应商下的全部账号。 */
    fun toggleProvider(providerId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            container.accountStore.byProvider(providerId).forEach { account ->
                if (account.enabled != enabled) {
                    container.accountStore.upsert(account.copy(enabled = enabled))
                }
            }
            refreshModels()
        }
    }

    /** 启停单个账号。 */
    fun toggleAccount(accountId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val account = container.accountStore.find(accountId) ?: return@launch
            container.accountStore.upsert(account.copy(enabled = enabled))
            refreshModels()
        }
    }

    /**
     * 添加或覆盖一个账号。
     *
     * @param id 传 null 表示新建
     */
    fun saveAccount(
        id: String? = null,
        providerId: String,
        label: String,
        credential: Credential,
        baseUrl: String = "",
        enabled: Boolean = true,
        authRegion: String = "",
        authUid: String = "",
        authTenantId: String = "",
        authDomain: String = "",
        authNickname: String = "",
        authTokenKind: String = "Bearer",
        authExpiresAtMillis: Long = 0,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = id?.let { container.accountStore.find(it) }
            val account = Account(
                id = id ?: "${providerId}-${UUID.randomUUID().toString().take(8)}",
                providerId = providerId,
                label = label.ifBlank { existing?.label ?: providerName(providerId) },
                enabled = enabled,
                credential = credential,
                baseUrl = baseUrl.ifBlank { existing?.baseUrl ?: "" },
                priority = existing?.priority ?: 0,
                disabledModels = existing?.disabledModels ?: emptySet(),
                authRegion = authRegion.ifBlank { existing?.authRegion ?: "" },
                authUid = authUid.ifBlank { existing?.authUid ?: "" },
                authTenantId = authTenantId.ifBlank { existing?.authTenantId ?: "" },
                authDomain = authDomain.ifBlank { existing?.authDomain ?: "" },
                authNickname = authNickname.ifBlank { existing?.authNickname ?: "" },
                authTokenKind = authTokenKind.ifBlank { existing?.authTokenKind ?: "Bearer" },
                authExpiresAtMillis = if (authExpiresAtMillis > 0) {
                    authExpiresAtMillis
                } else {
                    existing?.authExpiresAtMillis ?: 0
                },
                fixedPolicyId = existing?.fixedPolicyId,
            )
            container.accountStore.upsert(account)
            refreshModels()
            _message.value = "已保存 ${account.label}"
        }
    }

    /** 删除账号。 */
    fun deleteAccount(accountId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.accountStore.delete(accountId)
            refreshModels()
            _message.value = "已删除账号"
        }
    }

    /** 批量导入账号（用于「导入账号」与「导入配置」）。 */
    fun importAccounts(list: List<Account>, replace: Boolean = false) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { container.accountStore.importAll(list, replace) }
                refreshModels()
                toast("已导入 ${list.size} 个账号")
            } catch (error: Exception) {
                toast("账号无法导入，原存档已保留")
            }
        }
    }

    /**
     * 检测单个账号的连通性。
     *
     * 直接调用供应商的 `validate`，结果写进 [checks]。
     */
    fun checkAccount(account: Account) {
        _checks.value = _checks.value + (account.id to "检测中…")
        viewModelScope.launch(Dispatchers.IO) {
            container.credentialRefresher.refreshAccount(account.id, ignoreBackoff = true)
            val current = container.accountStore.find(account.id) ?: account
            val provider = registry.find(current.providerId)
            val result = if (provider == null) {
                "供应商未找到"
            } else {
                runCatching {
                    provider.validate(contextFor(provider, current))
                }.fold(
                    onSuccess = { r ->
                        when (r) {
                            is ValidationResult.Valid -> "可用"
                            is ValidationResult.Invalid -> "失败：${r.reason}"
                        }
                    },
                    onFailure = { "失败：${it.message ?: it::class.java.simpleName}" },
                )
            }
            _checks.value = _checks.value + (account.id to result)
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** 显示一条提示。 */
    fun toast(text: String) {
        _message.value = text
    }

    // ---------- 端口扫描 ----------

    /** 扫描发现的、疑似网关的端口。 */
    private val _scannedPorts = MutableStateFlow<List<Int>>(emptyList())
    val scannedPorts: StateFlow<List<Int>> = _scannedPorts.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /**
     * 扫描本机端口，找出可能在跑的网关实例。
     *
     * 对应原版「供应商」页的「扫描本机端口」。只探测回环地址上
     * 常见端口段，逐个尝试 `/v1/models`，能返回 OpenAI 结构才算命中。
     */
    fun scanLocalPorts() {
        if (_scanning.value) return
        _scanning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val found = mutableListOf<Int>()
            // 常见端口优先，再扫两个常用区间，避免全量 65535 太慢
            val candidates = buildList {
                addAll(listOf(8080, 8081, 8000, 3000, 5000, 11434))
                addAll(8000..8020)
                addAll(9000..9010)
            }.distinct()

            for (p in candidates) {
                if (probePort(p)) found += p
            }
            _scannedPorts.value = found
            _scanning.value = false
            _message.value = if (found.isEmpty()) {
                "未发现其它网关实例"
            } else {
                "发现 ${found.size} 个端口：${found.joinToString(", ")}"
            }
        }
    }

    /** 探测单个端口是否是本项目的网关。 */
    private suspend fun probePort(port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 220)
            socket.soTimeout = 400
            socket.getOutputStream().write(
                "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                    .toByteArray(),
            )
            socket.getOutputStream().flush()
            val text = socket.getInputStream().bufferedReader().readText()
            socket.close()
            text.contains("\"object\"") && text.contains("list")
        }.getOrDefault(false)
    }

    // ---------- 内部 ----------

    private fun contextFor(provider: Provider, account: Account): ProviderContext =
        object : ProviderContext {
            override val credential = account.credential
            override val http = container.httpClient
            // 账号没填自定义地址时留空，由供应商实现决定默认地址
            override val baseUrl = account.baseUrl
        }

    private fun providerName(providerId: String): String =
        registry.find(providerId)?.displayName ?: providerId

    private fun buildGroups(): List<ProviderGroup> =
        registry.all().map { provider ->
            ProviderGroup(
                provider = provider,
                accounts = container.accountStore.byProvider(provider.id),
            )
        }
}
