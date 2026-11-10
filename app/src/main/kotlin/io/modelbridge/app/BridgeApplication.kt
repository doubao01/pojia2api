package io.modelbridge.app

import android.app.Application
import io.modelbridge.app.prefs.AccountPreferences
import io.modelbridge.app.prefs.DailyCheckinStore
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
import io.modelbridge.providers.auth.AccountCredentialRefresher
import io.modelbridge.providers.auth.DeviceAuthClient
import io.modelbridge.providers.auth.LoomySigningMaterial
import io.modelbridge.providers.auth.LoomySmsAuth
import io.modelbridge.providers.auth.QoderClientIds
import io.modelbridge.providers.auth.QoderDeviceAuth
import io.modelbridge.providers.auth.WorkBuddyDeviceAuth
import io.modelbridge.providers.account.AccountServiceRegistry
import io.modelbridge.providers.account.AStudioAccountService
import io.modelbridge.providers.account.CatpawAccountService
import io.modelbridge.providers.account.LoomyAccountService
import io.modelbridge.providers.account.MeooAccountService
import io.modelbridge.providers.account.QoderAccountService
import io.modelbridge.providers.account.RaccoonAccountService
import io.modelbridge.providers.account.TabbitAccountService
import io.modelbridge.providers.account.TraeAccountService
import io.modelbridge.providers.account.WorkBuddyAccountService
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

    /** 应用级 Context，供需要 Context 的辅助组件使用。 */
    val context: Application get() = app

    val httpClient: HttpClient = JvmHttpClient()

    /**
     * Loomy 短信签名材料由构建期注入 BuildConfig；公开构建没有这些值时保持 null。
     * UI 会明确提示缺少配置，不会自动发送短信或伪造登录成功。
     */
    val loomySmsAuth: LoomySmsAuth? = run {
        val appId = BuildConfig.LOOMY_APP_ID
        val accessKey = BuildConfig.LOOMY_ACCESS_KEY
        val secret = BuildConfig.LOOMY_SECRET
        if (appId.isBlank() || accessKey.isBlank() || secret.isBlank()) {
            null
        } else {
            LoomySmsAuth(
                http = httpClient,
                material = LoomySigningMaterial(
                    appId = appId,
                    accessKey = accessKey,
                    secret = secret,
                ),
            )
        }
    }

    /** 供应商注册表，内置 14 家。 */
    val registry: ProviderRegistry = ProviderRegistry().apply {
        BuiltInProviders.registerInto(this)
    }

    /** 设置，持久化在 SharedPreferences。 */
    val settings = SettingsStore(app)

    /** 设备授权刷新客户端；公开构建没有 Qoder 客户端标识时仍保留 WorkBuddy 刷新。 */
    val deviceAuthClients: Map<String, DeviceAuthClient> = buildMap {
        put("codebuddy", WorkBuddyDeviceAuth(httpClient))
        val ids = QoderClientIds(
            cn = BuildConfig.QODER_CLIENT_ID_CN,
            global = BuildConfig.QODER_CLIENT_ID_GLOBAL,
        )
        if (ids.cn.isNotBlank() || ids.global.isNotBlank()) {
            put("qoder", QoderDeviceAuth(httpClient, ids))
        }
    }

    /** 账号仓储，持久化在 SharedPreferences，重启不丢。 */
    val accountStore = AccountPreferences(app)

    /** 本地每日签到状态，按账号+自然日裁剪，不冒充服务端签到记录。 */
    val dailyCheckinStore = DailyCheckinStore(app)

    /** 到期前刷新 OAuth 凭据，避免已保存账号到期后直接退出路由。 */
    val credentialRefresher = AccountCredentialRefresher(
        accounts = accountStore,
        clients = deviceAuthClients,
    )

    /** 已按 3.9.3 静态契约接入的供应商账号业务；未接入的供应商由注册表明确返回不支持。 */
    val accountServices = AccountServiceRegistry(
        services = listOf(
            WorkBuddyAccountService(httpClient),
            QoderAccountService(httpClient),
            LoomyAccountService(httpClient),
            CatpawAccountService(httpClient),
            RaccoonAccountService(httpClient),
            TabbitAccountService(httpClient),
            MeooAccountService(httpClient),
            TraeAccountService(httpClient),
            AStudioAccountService(httpClient),
        ),
    )

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
     * 进程内唯一运行控制器；Activity 重建、服务启动与日志页都使用这一份。
     * 不能让 Activity 和 Service 各创建一个监听器，否则首页永远看不到服务状态。
     */
    val runner: GatewayRunner by lazy {
        GatewayRunner(
            gatewayProvider = { gateway },
            settings = settings,
            beforeCatalogRefresh = { credentialRefresher.refreshDue() },
        ).also {
            onUsage = it::reportUsage
        }
    }

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
