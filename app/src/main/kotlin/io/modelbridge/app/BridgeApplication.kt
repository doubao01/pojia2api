package io.modelbridge.app

import android.app.Application
import io.modelbridge.app.prefs.AccountPreferences
import io.modelbridge.app.prefs.PrefsPolicyStore
import io.modelbridge.app.prefs.SettingsStore
import io.modelbridge.core.AccountStore
import io.modelbridge.core.Gateway
import io.modelbridge.core.GatewayConfig
import io.modelbridge.core.HealthTracker
import io.modelbridge.core.ModelCatalog
import io.modelbridge.core.ProviderRegistry
import io.modelbridge.core.Router
import io.modelbridge.core.http.JvmHttpClient
import io.modelbridge.providers.BuiltInProviders
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.Provider
import io.modelbridge.spi.ProviderContext
import io.modelbridge.spi.Usage

/**
 * 应用级状态容器。
 *
 * 只持有「进程内唯一」的对象：注册表、账号仓储、网关本身。
 * 界面状态由各 ViewModel 自行持有，这里不越界。
 */
class BridgeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * 手工实现的简易依赖容器。
 *
 * 不引入 Hilt/Koin：这个项目的依赖图很小，手工组装更直观、
 * 编译更快、也更容易被阅读者理解。
 */
class AppContainer(private val app: Application) {

    val httpClient: HttpClient = JvmHttpClient()

    /** 供应商注册表，内置 14 家。 */
    val registry: ProviderRegistry = ProviderRegistry().apply {
        BuiltInProviders.registerInto(this)
    }

    /** 设置，持久化在 SharedPreferences。 */
    val settings = SettingsStore(app)

    /** 账号仓储，持久化在 SharedPreferences，重启不丢。 */
    val accountStore = AccountPreferences(app)

    /** 策略仓储，持久化在 SharedPreferences，重启不丢。 */
    val policyStore = PrefsPolicyStore(app)

    /**
     * 健康追踪。阈值取自设置，修改后需重建容器或调用 [applySettings]。
     */
    private var health = HealthTracker(
        failureThreshold = settings.current().failureThreshold,
        cooldownMillis = settings.current().cooldownSeconds * 1000L,
    )

    val catalog = ModelCatalog(registry, accountStore)

    var router = Router(registry, accountStore, health)
        private set

    /** 构造已认证的调用上下文。 */
    private val contextFactory: (Provider, io.modelbridge.core.Account) -> ProviderContext =
        { _, account ->
            object : ProviderContext {
                override val credential = account.credential
                override val http = httpClient
                override val baseUrl = account.baseUrl
            }
        }

    /**
     * 网关配置随设置变化。访问密钥不为空即启用鉴权。
     */
    private fun buildConfig(): GatewayConfig {
        val s = settings.current()
        return GatewayConfig(
            apiKeys = s.accessKey
                .takeIf { it.isNotBlank() }
                ?.let { setOf(it) }
                ?: emptySet(),
            maxRetries = s.maxRetries,
            requestTimeoutSeconds = s.requestTimeoutSeconds,
            stripClientFingerprint = s.stripClientFingerprint,
        )
    }

    /**
     * 上游用量回调，由 [GatewayRunner] 注册。
     *
     * 应用层不直接持有 runner（两者生命周期不同），用回调解耦。
     */
    @Volatile
    var onUsage: ((Usage) -> Unit)? = null

    var gateway = Gateway(
        router = router,
        catalog = catalog,
        registry = registry,
        accounts = accountStore,
        health = health,
        contextFactory = contextFactory,
        config = buildConfig(),
        onUsage = { onUsage?.invoke(it) },
        policyStore = policyStore,
    )
        private set

    /**
     * 设置变更后重建受影响的组件。
     *
     * 健康阈值与鉴权密钥是构造期注入的，改完必须重建；
     * 端口/日志上限等在 [GatewayRunner.start] 时读取，无需重建。
     */
    fun applySettings() {
        val s = settings.current()
        health = HealthTracker(
            failureThreshold = s.failureThreshold,
            cooldownMillis = s.cooldownSeconds * 1000L,
        )
        router = Router(registry, accountStore, health)
        gateway = Gateway(
            router = router,
            catalog = catalog,
            registry = registry,
            accounts = accountStore,
            health = health,
            contextFactory = contextFactory,
            config = buildConfig(),
            onUsage = { onUsage?.invoke(it) },
            policyStore = policyStore,
        )
    }
}
