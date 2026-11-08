package io.modelbridge.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.modelbridge.app.prefs.GatewaySettings
import io.modelbridge.app.service.ServiceManager
import io.modelbridge.app.ui.ProviderGroup
import io.modelbridge.core.Account
import io.modelbridge.core.ProviderRegistry
import io.modelbridge.spi.Credential
import io.modelbridge.spi.ModelInfo
import io.modelbridge.spi.Provider
import io.modelbridge.spi.ProviderContext
import io.modelbridge.spi.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    fun startGateway(port: Int = 8080) {
        serviceManager.start(port)
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

    init {
        refreshModels()
    }

    // ---------- 模型 ----------

    /** 拉取一次模型目录。 */
    fun refreshModels() {
        _loadingModels.value = true
        _modelsError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.gateway.refreshCatalog() }
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

    // ---------- 设置 ----------

    fun updateSettings(block: (GatewaySettings) -> GatewaySettings) {
        container.settings.edit(block)
        container.applySettings()
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
            val provider = registry.find(account.providerId)
            val result = if (provider == null) {
                "供应商未找到"
            } else {
                runCatching {
                    provider.validate(contextFor(provider, account))
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
