package io.modelbridge.core

import io.modelbridge.spi.ModelInfo
import io.modelbridge.spi.Provider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 路由目标：某个模型的请求应当交给哪个供应商的哪个账号。
 */
data class RouteTarget(
    val provider: Provider,
    val account: Account,
    val model: ModelInfo,
)

/**
 * 上游健康状态与冷却。
 *
 * 某个账号连续失败达到阈值后进入冷却期，期间不再被选中；
 * 冷却结束后自动恢复，不需要人工干预。
 *
 * 设计要点：用单调递增的失败计数 + 时间戳，避免依赖系统时钟回拨。
 */
class HealthTracker(
    private val failureThreshold: Int = 3,
    private val cooldownMillis: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class State(
        val consecutiveFailures: Int = 0,
        val cooldownUntil: Long = 0,
    )

    private val states = HashMap<String, State>()
    private val mutex = Mutex()

    /** 该账号当前是否可用（不在冷却期）。 */
    suspend fun isAvailable(accountId: String): Boolean = mutex.withLock {
        val s = states[accountId] ?: return@withLock true
        clock() >= s.cooldownUntil
    }

    /** 记录一次成功，清零失败计数。 */
    suspend fun recordSuccess(accountId: String) = mutex.withLock {
        states.remove(accountId)
        Unit
    }

    /** 记录一次失败，返回是否因此进入冷却。 */
    suspend fun recordFailure(accountId: String): Boolean = mutex.withLock {
        val prev = states[accountId] ?: State()
        val failures = prev.consecutiveFailures + 1
        val shouldCooldown = failures >= failureThreshold
        states[accountId] = State(
            consecutiveFailures = if (shouldCooldown) 0 else failures,
            cooldownUntil = if (shouldCooldown) clock() + cooldownMillis else prev.cooldownUntil,
        )
        shouldCooldown
    }

    /** 冷却剩余毫秒数，0 表示可用。用于 Retry-After 提示。 */
    suspend fun remainingCooldownMillis(accountId: String): Long = mutex.withLock {
        val s = states[accountId] ?: return@withLock 0
        maxOf(0, s.cooldownUntil - clock())
    }

    /** 批量读取冷却剩余毫秒数；只返回仍在冷却中的账号，便于界面一次刷新。 */
    suspend fun coolingAccounts(): Map<String, Long> = mutex.withLock {
        val now = clock()
        states.mapNotNull { (id, state) ->
            val remaining = maxOf(0, state.cooldownUntil - now)
            if (remaining > 0) id to remaining else null
        }.toMap()
    }

    suspend fun reset() = mutex.withLock { states.clear() }
}

/**
 * 请求路由器。
 *
 * 职责：
 * - 把客户端传来的模型 ID 解析成可用的 [RouteTarget]；
 * - 同名模型在多个账号间做故障转移；
 * - 结合 [HealthTracker] 跳过冷却中的账号。
 */
class Router(
    private val registry: ProviderRegistry,
    private val accounts: AccountStore,
    private val health: HealthTracker = HealthTracker(),
    /** 模型目录快照，由 [ModelCatalog] 定期刷新。 */
    private var catalog: List<ModelInfo> = emptyList(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** 更新模型目录快照。 */
    suspend fun updateCatalog(models: List<ModelInfo>) = mutex.withLock {
        catalog = models
    }

    /** 当前已知模型。 */
    suspend fun knownModels(): List<ModelInfo> = mutex.withLock { catalog }

    /**
     * 解析模型对应的全部候选目标，按优先级排序。
     *
     * 一次解析可能返回多个目标：同名模型配置在多个账号上时，
     * 调用方应依次尝试，前面的失败再换下一个（故障转移）。
     */
    suspend fun resolve(modelId: String): List<RouteTarget> {
        val snapshot = mutex.withLock { catalog }
        val matched = snapshot.filter { it.id == modelId }
        if (matched.isEmpty()) {
            throw GatewayError.NotFound("模型不存在或未启用：$modelId")
        }

        val targets = mutableListOf<RouteTarget>()
        for (model in matched) {
            val provider = registry.find(model.providerId) ?: continue
            val now = clockMillis()
            val candidates = accounts.byProvider(provider.id)
                .filter {
                    it.enabled &&
                        !it.isExpired(now) &&
                        model.id !in it.disabledModels
                }
                .sortedBy { it.priority }

            for (account in candidates) {
                if (health.isAvailable(account.id)) {
                    targets += RouteTarget(provider, account, model)
                }
            }
        }

        if (targets.isEmpty()) {
            // 区分「模型不存在」与「模型存在但账号都在冷却」
            val cooldown = matched.any { m ->
                accounts.byProvider(m.providerId).any { it.enabled }
            }
            throw if (cooldown) {
                GatewayError.UpstreamUnavailable(
                    message = "模型 $modelId 的账号暂不可用（全部失败或冷却中）",
                    retryAfterSeconds = 30,
                )
            } else {
                GatewayError.NotFound("模型 $modelId 没有可用的启用账号")
            }
        }
        return targets
    }
}
