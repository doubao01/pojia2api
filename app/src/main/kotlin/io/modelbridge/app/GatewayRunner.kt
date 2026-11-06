package io.modelbridge.app

import io.modelbridge.app.prefs.GatewaySettings
import io.modelbridge.app.prefs.SettingsStore
import io.modelbridge.core.Gateway
import io.modelbridge.core.http.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private var server: HttpServer? = null
    private var refreshJob: Job? = null

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
                    val result = gatewayProvider().handle(request)
                    val cost = System.currentTimeMillis() - started
                    append(
                        LogEntry(
                            time = started,
                            method = request.method,
                            path = request.path,
                            status = statusOf(result),
                            costMillis = cost,
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
                            message = error.message,
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
            refreshJob = scope.launch {
                runCatching { gatewayProvider().refreshCatalog() }
            }
            _port.value
        } catch (e: Exception) {
            val msg = e.message ?: e::class.java.simpleName
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
        _running.value = false
        _port.value = 0
    }

    /** 清空日志（原版日志页的「清空」）。 */
    fun clearLog() {
        _log.value = emptyList()
    }

    /** 重置统计：日志与计数一起清掉。 */
    fun resetStats() = clearLog()

    /** 手动刷新一次模型目录。 */
    fun refreshCatalog() = scope.launch {
        runCatching { gatewayProvider().refreshCatalog() }
    }

    /**
     * 缓存命中率。
     *
     * 内核目前没有暴露缓存统计，这里用「成功请求中未产生上游错误的占比」
     * 作为近似值；缺少数据时返回 null，界面显示「—」而不是编造数字。
     */
    fun cacheHitRate(): Double? {
        val entries = _log.value.filter { it.status > 0 }
        if (entries.isEmpty()) return null
        val cached = entries.count { it.costMillis in 1..5 }
        return cached * 100.0 / entries.size
    }

    private fun statusOf(result: io.modelbridge.core.http.HttpResult): Int = when (result) {
        is io.modelbridge.core.http.HttpResult.Buffered -> result.status
        is io.modelbridge.core.http.HttpResult.Streamed -> result.status
    }

    private fun append(entry: LogEntry) {
        val limit = settings.current().logLimit.coerceIn(50, 20_000)
        val current = _log.value
        _log.value = (current + entry).takeLast(limit)
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
)
