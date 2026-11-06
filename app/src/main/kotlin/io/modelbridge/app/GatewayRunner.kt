package io.modelbridge.app

import io.modelbridge.core.Gateway
import io.modelbridge.core.http.HttpServer
import io.modelbridge.core.http.ServerRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 网关的运行控制：启停 HTTP 服务，维护一份简单的内存日志。
 *
 * 日志刻意做在内存里、有上限，避免界面无限累积导致 OOM。
 * 后续如需持久化再替换为文件落盘。
 */
class GatewayRunner(
    private val gateway: Gateway,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** 运行状态。 */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 实际监听端口。 */
    private val _port = MutableStateFlow(0)
    val port: StateFlow<Int> = _port.asStateFlow()

    /** 最近的日志条目，新的在最后。 */
    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private var server: HttpServer? = null
    private var refreshJob: Job? = null

    /** 单页日志上限。 */
    private val maxLogEntries = 500

    /**
     * 启动网关。
     *
     * @param port 监听端口，0 表示由系统分配
     * @return 实际监听端口；失败返回 -1
     */
    fun start(port: Int = 8080): Int {
        if (_running.value) return _port.value

        return try {
            val httpServer = HttpServer(port)
            httpServer.start { request ->
                runCatching {
                    val started = System.currentTimeMillis()
                    val result = gateway.handle(request)
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
            _running.value = true

            // 刷一次模型目录，避免首次请求空转
            refreshJob = scope.launch {
                runCatching { gateway.refreshCatalog() }
            }
            _port.value
        } catch (e: Exception) {
            append(
                LogEntry(
                    time = System.currentTimeMillis(),
                    method = "-",
                    path = "-",
                    status = 0,
                    message = "启动失败：${e.message}",
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

    /** 手动刷新一次模型目录。 */
    fun refreshCatalog() = scope.launch {
        runCatching { gateway.refreshCatalog() }
    }

    private fun statusOf(result: io.modelbridge.core.http.HttpResult): Int = when (result) {
        is io.modelbridge.core.http.HttpResult.Buffered -> result.status
        is io.modelbridge.core.http.HttpResult.Streamed -> result.status
    }

    private fun append(entry: LogEntry) {
        val current = _log.value
        _log.value = (current + entry).takeLast(maxLogEntries)
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
