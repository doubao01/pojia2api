package io.modelbridge.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.modelbridge.app.ui.ProviderGroup
import io.modelbridge.core.Account
import io.modelbridge.core.ProviderRegistry
import io.modelbridge.spi.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 持有界面状态、连接内核的 ViewModel。
 *
 * 刻意只做一个：所有页面共用它，避免多个 ViewModel 交叉订阅内核。
 */
class ViewModels(
    private val container: AppContainer,
    val runner: GatewayRunner,
) : ViewModel() {

    private val registry: ProviderRegistry = container.registry

    /** 模型目录。 */
    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _loadingModels = MutableStateFlow(false)
    val loadingModels: StateFlow<Boolean> = _loadingModels.asStateFlow()

    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()

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

    init {
        refreshModels()
    }

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

    private fun buildGroups(): List<ProviderGroup> =
        registry.all().map { provider ->
            ProviderGroup(
                provider = provider,
                accounts = container.accountStore.byProvider(provider.id),
            )
        }
}
