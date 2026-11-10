package io.modelbridge.app

import io.modelbridge.app.prefs.GatewaySettings
import io.modelbridge.app.prefs.SettingsStore
import io.modelbridge.app.prefs.StatsStore
import io.modelbridge.core.GatewayCounters
import io.modelbridge.core.Gateway
import io.modelbridge.core.LogRedactor
import io.modelbridge.core.http.HttpServer
import io.modelbridge.providers.auth.CredentialRefreshSummary
import io.modelbridge.spi.Usage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * 网关的运行控制：启停 HTTP 服务，维护一份简单的内存日志与统计。
 *
 * 日志刻意做在内存里、上限可配（见 [GatewaySettings.logLimit]），
 * 避免界面无限累积导致 OOM。
 */
class GatewayRunner(
    private val gatewayProvider: () -> Gateway,
    private val settings: SettingsStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** 每次重拉模型目录前刷新即将到期的 OAuth 凭据。 */
    private val beforeCatalogRefresh: suspend () -> CredentialRefreshSummary = {
        CredentialRefreshSummary()
    },
    private val maintenanceIntervalMillis: Long = 5 * 60 * 1000L,
    private val statsStore: StatsStore? = null,
) {
    /** 运行状态。 */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 实际监听端口。 */
    private val _port = MutableStateFlow(0)
    val port: StateFlow<Int> = _port.asStateFlow()

    /** 当前是否仅本机可访问。 */
    private val _loopbackOnly = MutableStateFlow(false)
    val loopbackOnly: StateFlow<Boolean> = _loopbackOnly.asStateFlow()

    /** 启动失败原因；成功时为 null。 */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** 最近的日志条目，新的在最后。 */
    /**
     * 最近一次上游返回的用量。
     *
     * 网关在写出响应时回调，随后 append 记录日志时取用；
     * 用 @Volatile 是因为回调可能发生在 HTTP 工作线程。
     */
    @Volatile
    private var lastUsage: Usage? = null

    /** 供 Gateway 构造时注入，把上游用量接进来。 */
    fun reportUsage(usage: Usage) {
        lastUsage = usage
    }

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()
    private val counters = GatewayCounters()
    private val _stats = MutableStateFlow(counters.snapshot())
    /** 独立累计统计，不受日志上限裁剪影响。 */
    val stats: StateFlow<GatewayCounters.Snapshot> = _stats.asStateFlow()

    init {
        statsStore?.let { store ->
            counters.restore(store.load())
            _stats.value = counters.snapshot()
        }
    }

    private var server: HttpServer? = null
    private var refreshJob: Job? = null
    private var maintenanceJob: Job? = null

    /**
     * 启动网关。
     *
     * 端口与监听地址取自设置：`port = 0` 表示由系统分配；
     * `allowLan = false` 时只监听回环地址。
     *
     * @return 实际监听端口；失败返回 -1
     */
    fun start(port: Int? = null): Int {
        if (_running.value) return _port.value
        _lastError.value = null

        val current = settings.current()
        val usePort = port ?: current.port
        val bindAddress = if (current.allowLan) null else InetAddress.getLoopbackAddress()

        return try {
            val httpServer = HttpServer(
                port = usePort,
                bindAddress = bindAddress,
            )
            httpServer.start { request ->
                runCatching {
                    val started = System.currentTimeMillis()
                    val gateway = gatewayProvider()
                    val result = gateway.handle(request)
                    val cost = System.currentTimeMillis() - started
                    val diagnostics = buildList {
                        gateway.policyName?.let { add("策略「$it」") }
                        gateway.lastPolicyError?.let { add("未注入策略：$it") }
                        gateway.lastFingerprintDiagnostic?.let { add(it) }
                    }.joinToString("；").ifBlank { null }
                    append(
                        LogEntry(
                            time = started,
                            method = request.method,
                            path = request.path,
                            status = statusOf(result),
                            costMillis = cost,
                            message = LogRedactor.redact(diagnostics),
                            upstreamId = gateway.lastUpstreamId,
                        ),
                    )
                    result
                }.getOrElse { error ->
                    append(
                        LogEntry(
                            time = System.currentTimeMillis(),
                            method = request.method,
                            path = request.path,
                            status = 500,
                            message = LogRedactor.redact(error.message),
                            costMillis = 0,
                        ),
                    )
                    io.modelbridge.core.http.HttpResult.Buffered(
                        status = 500,
                        body = """{"error":{"message":"internal error"}}""".encodeToByteArray(),
                    )
                }
            }

            server = httpServer
            _port.value = httpServer.boundPort
            _loopbackOnly.value = !current.allowLan
            _running.value = true

            // 刷一次模型目录，避免首次请求空转
            refreshJob = scope.launch { runCatching { refreshCatalogInternal(force = true) } }

            // 网关常驻时定期维护账号凭据，避免长期挂机后 OAuth 到期失效。
            maintenanceJob = scope.launch {
                while (true) {
                    delay(maintenanceIntervalMillis)
                    runCatching { refreshCatalogInternal(force = false) }
                        .onFailure { error ->
                            if (error is kotlinx.coroutines.CancellationException) throw error
                        }
                }
            }
            _port.value
        } catch (e: Exception) {
            val msg = LogRedactor.redact(e.message ?: e::class.java.simpleName) ?: "未知错误"
            _lastError.value = msg
            append(
                LogEntry(
                    time = System.currentTimeMillis(),
                    method = "-",
                    path = "-",
                    status = 0,
                    message = "启动失败：$msg",
                ),
            )
            -1
        }
    }

    /** 停止网关。 */
    fun stop() {
        server?.stop()
        server = null
        refreshJob?.cancel()
        refreshJob = null
        maintenanceJob?.cancel()
        maintenanceJob = null
        _running.value = false
        _port.value = 0
    }

    /** 清空日志（原版日志页的「清空」）；累计统计保留。 */
    fun clearLog() {
        _log.value = emptyList()
    }

    /** 重置统计：累计请求/耗时/token 与日志一起清掉。 */
    fun resetStats() {
        counters.reset()
        _stats.value = counters.snapshot()
        statsStore?.clear()
        clearLog()
    }

    /** 手动刷新一次模型目录。 */
    fun refreshCatalog() = scope.launch {
        runCatching { refreshCatalogInternal(force = true) }
    }

    /**
     * 刷新凭据后重拉模型目录。
     *
     * 供应商模型列表缓存按账号缓存；只改凭据不失效缓存，
     * 仍会把旧令牌拉取的模型暴露到刷新后的目录里。
     */
    private suspend fun refreshCatalogInternal(force: Boolean) {
        val summary = runCatching { beforeCatalogRefresh() }
            .getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                CredentialRefreshSummary()
            }
        gatewayProvider().refreshCatalog(force = force || summary.updated > 0)
    }

    /** 缓存命中率：按累计上游用量计算；没有用量数据时返回 null。 */
    fun cacheHitRate(): Double? = counters.snapshot().cacheHitRate

    /** 累计 token 用量，供首页统计展示。 */
    fun tokenTotals(): Pair<Long, Long> = counters.snapshot().let {
        it.promptTokens to it.completionTokens
    }

    /** 累计统计快照，供首页一次性读取，避免多次计算。 */
    fun statsSnapshot(): GatewayCounters.Snapshot = _stats.value

    private fun statusOf(result: io.modelbridge.core.http.HttpResult): Int = when (result) {
        is io.modelbridge.core.http.HttpResult.Buffered -> result.status
        is io.modelbridge.core.http.HttpResult.Streamed -> result.status
    }

    private fun append(entry: LogEntry) {
        val limit = settings.current().logLimit.coerceIn(50, 20_000)
        val current = _log.value
        // 把本次请求拿到的用量附到这条日志上，取完即清，避免串到下一条
        val usage = lastUsage
        lastUsage = null
        val filled = usage?.let {
            entry.copy(
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                cachedTokens = it.cachedTokens,
            )
        } ?: entry
        counters.record(
            status = filled.status,
            costMillis = filled.costMillis,
            promptTokens = filled.promptTokens,
            completionTokens = filled.completionTokens,
            cachedTokens = filled.cachedTokens,
            upstreamId = filled.upstreamId,
        )
        _stats.value = counters.snapshot()
        statsStore?.save(_stats.value)
        _log.value = (current + filled).takeLast(limit)
    }
}

/** 一条访问日志。 */
data class LogEntry(
    val time: Long,
    val method: String,
    val path: String,
    val status: Int,
    val costMillis: Long = 0,
    val message: String? = null,
    /** 上游返回的提示词 token，0 表示这次没有拿到用量。 */
    val promptTokens: Int = 0,
    /** 上游返回的补全 token。 */
    val completionTokens: Int = 0,
    /** 提示词里命中缓存的部分。 */
    val cachedTokens: Int = 0,
    /** 本次实际尝试/命中的上游 `providerId/accountId`。 */
    val upstreamId: String? = null,
)
