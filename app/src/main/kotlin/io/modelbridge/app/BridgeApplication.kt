package io.modelbridge.app

import android.app.Application
import io.modelbridge.core.Gateway
import io.modelbridge.core.GatewayConfig
import io.modelbridge.core.HealthTracker
import io.modelbridge.core.InMemoryAccountStore
import io.modelbridge.core.ModelCatalog
import io.modelbridge.core.ProviderRegistry
import io.modelbridge.core.Router
import io.modelbridge.core.http.JvmHttpClient
import io.modelbridge.providers.BuiltInProviders
import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.Provider
import io.modelbridge.spi.ProviderContext

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

    /** 供应商注册表，内置 16 家。 */
    val registry: ProviderRegistry = ProviderRegistry().apply {
        BuiltInProviders.registerInto(this)
    }

    /** 账号仓储；生产实现后续替换为加密 SharedPreferences。 */
    val accountStore = InMemoryAccountStore()

    private val health = HealthTracker()

    val catalog = ModelCatalog(registry, accountStore)

    val router = Router(registry, accountStore, health)

    /** 构造已认证的调用上下文。 */
    private val contextFactory: (Provider, io.modelbridge.core.Account) -> ProviderContext =
        { provider, account ->
            object : ProviderContext {
                override val credential = account.credential
                override val http = httpClient
                override val baseUrl = account.baseUrl
            }
        }

    val gateway = Gateway(
        router = router,
        catalog = catalog,
        registry = registry,
        accounts = accountStore,
        health = health,
        contextFactory = contextFactory,
        config = GatewayConfig(),
    )
}
