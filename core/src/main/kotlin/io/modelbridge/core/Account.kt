package io.modelbridge.core

import io.modelbridge.spi.AuthKind
import io.modelbridge.spi.Credential
import io.modelbridge.spi.ModelInfo
import kotlinx.serialization.Serializable
import io.modelbridge.spi.Provider
import io.modelbridge.spi.ProviderContext
import io.modelbridge.spi.ProviderException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一个已配置的上游账号。
 *
 * @property enabled 是否启用。停用的账号不参与模型列表，也不参与路由。
 * @property credential 凭据
 * @property baseUrl 上游地址，空表示用供应商默认地址
 * @property priority 越小优先级越高，用于多账号负载
 */
@Serializable
data class Account(
    val id: String,
    val providerId: String,
    val label: String,
    val enabled: Boolean = true,
    val credential: Credential = Credential.None,
    val baseUrl: String = "",
    val priority: Int = 0,
    /** 该账号下被禁用的模型 ID，用于精细控制。 */
    val disabledModels: Set<String> = emptySet(),
    /** 设备授权区域，例如 cn/global。 */
    val authRegion: String = "",
    /** 供应商返回的用户 ID。 */
    val authUid: String = "",
    /** 企业/租户 ID，WorkBuddy 刷新时需要。 */
    val authTenantId: String = "",
    /** 设备授权返回的域名。 */
    val authDomain: String = "",
    /** 供应商返回的昵称。 */
    val authNickname: String = "",
    /** 令牌类型，默认 Bearer。 */
    val authTokenKind: String = "Bearer",
    /** 凭据到期时间（毫秒）；0 表示未知或永不过期。 */
    val authExpiresAtMillis: Long = 0,
    /** 固定绑定策略；null 按模型自动匹配，停用或已删除的策略不注入。 */
    val fixedPolicyId: String? = null,
) {
    /** 凭据是否已过期；0 表示未知或永不过期。 */
    fun isExpired(nowMillis: Long): Boolean =
        authExpiresAtMillis > 0 && nowMillis >= authExpiresAtMillis

    init {
        require(id.isNotBlank()) { "账号 id 不能为空" }
        require(providerId.isNotBlank()) { "providerId 不能为空" }
    }
}

/** 账号仓储抽象，便于测试注入与后续换存储实现。 */
interface AccountStore {
    fun all(): List<Account>
    fun byProvider(providerId: String): List<Account>
    fun find(id: String): Account?
    fun upsert(account: Account)
    fun delete(id: String)

    /** 账号集合的可观察流，变更时界面可据此刷新。 */
    val accountsFlow: StateFlow<List<Account>>
}

/**
 * 内存实现，线程安全。生产环境由 Android 侧用加密 SharedPreferences 替换。
 */
class InMemoryAccountStore(initial: List<Account> = emptyList()) : AccountStore {
    private val map = LinkedHashMap<String, Account>()
    private val lock = Any()

    private val _accountsFlow = MutableStateFlow<List<Account>>(emptyList())
    override val accountsFlow: StateFlow<List<Account>> = _accountsFlow.asStateFlow()

    init {
        initial.forEach { map[it.id] = it }
        emit()
    }

    override fun all(): List<Account> = synchronized(lock) { map.values.toList() }

    override fun byProvider(providerId: String): List<Account> =
        synchronized(lock) { map.values.filter { it.providerId == providerId } }

    override fun find(id: String): Account? = synchronized(lock) { map[id] }

    override fun upsert(account: Account) {
        synchronized(lock) { map[account.id] = account }
        emit()
    }

    override fun delete(id: String) {
        synchronized(lock) { map.remove(id) }
        emit()
    }

    private fun emit() {
        _accountsFlow.value = all()
    }
}

/**
 * 模型目录：把「账号 + 供应商」聚合成客户端可见的模型清单。
 *
 * 核心规则（这是本项目区别于原实现的地方，也是需求所在）：
 * 1. 只有**启用**的账号贡献模型；
 * 2. 账号级 [Account.disabledModels] 里的模型被剔除；
 * 3. 同名模型来自同一供应商的多个账号时去重，保留优先级最高的那个；
 *    来自不同供应商的同名模型分别保留，供 `@上游昵称` 指定路由；
 * 4. 没有任何启用账号的供应商，其模型**完全不出现在列表里**。
 *
 * @param cache 模型列表的缓存，TTL 默认 60 秒，到期后重新拉取。
 */
class ModelCatalog(
    private val registry: ProviderRegistry,
    private val accounts: AccountStore,
    private val cache: Cache<String, List<ModelInfo>> = Cache.create(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * 构建当前可见的模型列表。
     *
     * 供应商拉取失败不影响其它供应商，失败会被跳过并记录在 [ModelCatalogResult.errors]。
     */
    suspend fun build(
        contextFactory: (Provider, Account) -> ProviderContext,
    ): ModelCatalogResult {
        val models = LinkedHashMap<String, LinkedHashMap<String, ModelInfo>>()
        val errors = mutableListOf<CatalogError>()

        for (provider in registry.all()) {
            val now = clockMillis()
            val enabledAccounts = accounts.byProvider(provider.id)
                .filter { it.enabled && !it.isExpired(now) }
                .sortedBy { it.priority }

            if (enabledAccounts.isEmpty()) {
                // 没有启用账号 -> 该供应商的模型一个都不暴露
                continue
            }

            for (account in enabledAccounts) {
                val key = "${provider.id}|${account.id}"
                val fetched: List<ModelInfo> = try {
                    cache.get(key) ?: provider.listModels(contextFactory(provider, account)).also {
                        cache.put(key, it)
                    }
                } catch (e: ProviderException) {
                    // 缓存不能只存错误，但也要保存到下次，避免重复调用
                    errors += CatalogError(provider.id, account.id, e.message ?: "拉取失败", e.statusCode)
                    continue
                } catch (e: Exception) {
                    errors += CatalogError(provider.id, account.id, e.message ?: "未知错误", 500)
                    continue
                }

                for (model in fetched) {
                    if (model.id in account.disabledModels) continue
                    val byProvider = models.getOrPut(model.id) { linkedMapOf() }
                    // 同一供应商、同名模型保留先到的（优先级更高的账号）
                    if (!byProvider.containsKey(provider.id)) {
                        byProvider[provider.id] = model
                    }
                }
            }
        }

        return ModelCatalogResult(models.values.flatMap { it.values }, errors)
    }

    /**
     * 使缓存立刻失效。
     */
    suspend fun invalidateCache() {
        cache.invalidateAll()
    }
}

/** 模型目录构建结果。 */
data class ModelCatalogResult(
    val models: List<ModelInfo>,
    val errors: List<CatalogError>,
)

/** 单个供应商/账号的拉取失败。 */
data class CatalogError(
    val providerId: String,
    val accountId: String,
    val message: String,
    val statusCode: Int,
)

/** 供应商是否需要凭据，供 UI 判断是否显示「去登录」。 */
fun Provider.needsLogin(): Boolean =
    requiresCredential && authKind != AuthKind.None
