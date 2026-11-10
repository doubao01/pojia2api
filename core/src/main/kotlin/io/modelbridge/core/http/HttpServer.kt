package io.modelbridge.core.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets

/** 一个解析后的 HTTP 请求。 */
class ServerRequest(
    val method: String,
    val path: String,
    val query: String,
    val version: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    /** 取请求头，名称不区分大小写。 */
    fun header(name: String): String? = headers[name.lowercase()]

    /** 解析查询参数。 */
    fun queryParam(name: String): String? =
        query.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == name }
            ?.getOrNull(1)

    val isKeepAlive: Boolean
        get() = when {
            version != "HTTP/1.1" -> false
            else -> header("connection")?.contains("close", ignoreCase = true) != true
        }
}

/** 处理器产出的响应。 */
sealed interface HttpResult {
    /** 一次性写出全部内容。 */
    class Buffered(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    ) : HttpResult

    /** 逐块写出，用于 SSE 流式响应。 */
    class Streamed(
        val status: Int = 200,
        val headers: Map<String, String> = emptyMap(),
        val chunks: Sequence<ByteArray>,
    ) : HttpResult
}

/** 路由处理函数。 */
typealias HttpHandler = suspend (ServerRequest) -> HttpResult

/**
 * 极简 HTTP/1.1 服务器。
 *
 * 只实现网关需要的子集：
 * - 请求体支持 `Content-Length` 与 `Transfer-Encoding: chunked`
 * - 支持 keep-alive 复用连接
 * - 支持流式响应（SSE）
 * - 请求头同时接受 CRLF 与裸 LF 换行
 *
 * 刻意不引入 Netty 或 Ktor：网关要跑在手机上，依赖越少体积越小，
 * 也更容易被审计。
 */
class HttpServer(
    private val port: Int,
    private val maxHeaderBytes: Int = 64 * 1024,
    private val maxBodyBytes: Int = 32 * 1024 * 1024,
    private val idleTimeoutMillis: Int = 75_000,
    /**
     * 监听地址。默认监听所有网卡（局域网可访问）；
     * 传 [java.net.InetAddress.getLoopbackAddress] 则仅本机可访问。
     */
    private val bindAddress: java.net.InetAddress? = null,
) {
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private var acceptJob: Job? = null

    /** 是否正在监听。 */
    val isRunning: Boolean get() = serverSocket != null

    /** 实际监听端口，未启动为 0（允许 port=0 由系统分配）。 */
    val boundPort: Int get() = serverSocket?.localPort ?: 0

    /**
     * 启动监听。重复调用会抛异常。
     */
    fun start(handler: HttpHandler) {
        check(serverSocket == null) { "服务器已启动" }

        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, port))
        }
        serverSocket = socket

        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = serverScope
        acceptJob = serverScope.launch {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    continue
                }
                launch { serveConnection(client, handler) }
            }
        }
    }

    /** 停止监听并释放端口。 */
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        serverSocket?.let { runCatching { it.close() } }
        serverSocket = null
        scope?.cancel()
        scope = null
    }

    private suspend fun serveConnection(client: Socket, handler: HttpHandler) {
        client.use { socket ->
            runCatching {
                socket.soTimeout = idleTimeoutMillis
                socket.tcpNoDelay = true

                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()

                while (true) {
                    val request = readRequest(input, output) ?: return@runCatching
                    val result = try {
                        handler(request)
                    } catch (e: Exception) {
                        HttpResult.Buffered(
                            status = 500,
                            body = """{"error":{"message":"${e.message.orEmpty().escapeJson()}","type":"server_error"}}"""
                                .toByteArray(StandardCharsets.UTF_8),
                        )
                    }

                    when (result) {
                        is HttpResult.Buffered -> HttpResponses.writeBuffered(output, result)
                        is HttpResult.Streamed -> HttpResponses.writeStreamed(output, result)
                    }
                    output.flush()

                    val keepAlive = request.isKeepAlive && result !is HttpResult.Streamed
                    if (!keepAlive) return@runCatching
                }
            }
        }
    }

    /** 读一个完整请求；对端正常关闭时返回 null。 */
    private fun readRequest(input: InputStream, output: OutputStream): ServerRequest? {
        val head = readHead(input) ?: return null
        if (head.isBlank()) return null

        val lines = head.split("\r\n", "\n")
        val requestLine = lines.firstOrNull()?.trim().orEmpty()
        if (requestLine.isEmpty()) return null

        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val headers = HashMap<String, String>(lines.size * 2)
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val sep = line.indexOf(':')
            if (sep <= 0) continue
            headers[line.take(sep).trim().lowercase()] = line.substring(sep + 1).trim()
        }

        val (path, query) = splitTarget(parts[1])
        val method = parts[0].uppercase()
        // 3.10.0 `C/RunnableC0013m.java`：POST/PUT/PATCH 支持 Expect: 100-continue。
        val hasBodyMethod = method == "POST" || method == "PUT" || method == "PATCH"
        val expect = headers["expect"]
        if (hasBodyMethod && expect?.contains("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()
        }
        val body = if (hasBodyMethod) readBody(input, headers) else ByteArray(0)

        return ServerRequest(
            method = method,
            path = path,
            query = query,
            version = parts.getOrElse(2) { "HTTP/1.1" },
            headers = headers,
            body = body,
        )
    }

    /**
     * 读取请求头，直到遇到空行。
     *
     * 终止条件同时接受 `\r\n\r\n` 与 `\n\n`，
     * 这样脚本类客户端（curl 之外的实现）不会因为少写 CR 而被误判。
     *
     * @return 不含结尾空行的头部文本；连接已关闭且未读到任何内容时返回 null
     */
    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(1024)
        var last4 = 0L // 用低 32 位记录最近 4 个字节
        var total = 0

        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (total == 0) null else buffer.toString(StandardCharsets.ISO_8859_1.name())
            }
            buffer.write(b)
            total++

            last4 = ((last4 shl 8) or b.toLong()) and 0xFFFFFFFFL

            val crlfcrlf = last4 == 0x0D0A0D0AL
            val lflf = (last4 and 0xFFFF) == 0x0A0AL
            if (crlfcrlf || lflf) break

            if (total > maxHeaderBytes) {
                throw IllegalStateException("请求头超过 $maxHeaderBytes 字节")
            }
        }

        val text = buffer.toString(StandardCharsets.ISO_8859_1.name())
        return text.trimEnd('\r', '\n')
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return readChunked(input)
        }

        val length = headers["content-length"]?.toLongOrNull() ?: return ByteArray(0)
        if (length <= 0) return ByteArray(0)
        if (length > maxBodyBytes) throw IllegalStateException("请求体超过 $maxBodyBytes 字节")

        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }
        return if (read == body.size) body else body.copyOf(read)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.trim().substringBefore(';').toIntOrNull(16) ?: break
            if (size == 0) {
                readLine(input)
                break
            }
            if (out.size() + size > maxBodyBytes) {
                throw IllegalStateException("分块请求体超过 $maxBodyBytes 字节")
            }
            val chunk = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(chunk, read, size - read)
                if (n < 0) break
                read += n
            }
            out.write(chunk, 0, read)
            readLine(input)
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (b == '\n'.code) return buffer.toString("ISO-8859-1")
            if (b != '\r'.code) buffer.write(b)
        }
    }

    private fun splitTarget(target: String): Pair<String, String> {
        val idx = target.indexOf('?')
        return if (idx < 0) target to "" else target.substring(0, idx) to target.substring(idx + 1)
    }
}

private fun String.escapeJson(): String = buildString(length) {
    for (c in this@escapeJson) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}

/** 响应序列化工具。 */
object HttpResponses {
    private val statusText = mapOf(
        200 to "OK",
        201 to "Created",
        204 to "No Content",
        400 to "Bad Request",
        401 to "Unauthorized",
        403 to "Forbidden",
        404 to "Not Found",
        405 to "Method Not Allowed",
        408 to "Request Timeout",
        413 to "Payload Too Large",
        429 to "Too Many Requests",
        500 to "Internal Server Error",
        502 to "Bad Gateway",
        503 to "Service Unavailable",
        504 to "Gateway Timeout",
    )

    /** 写出普通响应。 */
    fun writeBuffered(output: OutputStream, result: HttpResult.Buffered) {
        val headers = LinkedHashMap(result.headers)
        headers["Content-Length"] = result.body.size.toString()
        headers.putIfAbsent("Connection", "keep-alive")
        headers.putIfAbsent("Content-Type", "application/json; charset=utf-8")

        val head = buildString {
            append("HTTP/1.1 ").append(result.status).append(' ')
                .append(statusText[result.status] ?: "Status").append("\r\n")
            for ((k, v) in headers) append(k).append(": ").append(v).append("\r\n")
            append("\r\n")
        }

        output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        if (result.body.isNotEmpty()) output.write(result.body)
    }

    /** 写出 SSE 流式响应（chunked 编码）。 */
    fun writeStreamed(output: OutputStream, result: HttpResult.Streamed) {
        val headers = LinkedHashMap(result.headers)
        headers.putIfAbsent("Content-Type", "text/event-stream; charset=utf-8")
        headers.putIfAbsent("Cache-Control", "no-cache")
        headers.putIfAbsent("Connection", "close")
        headers.putIfAbsent("Transfer-Encoding", "chunked")

        val head = buildString {
            append("HTTP/1.1 ").append(result.status).append(' ')
                .append(statusText[result.status] ?: "Status").append("\r\n")
            for ((k, v) in headers) append(k).append(": ").append(v).append("\r\n")
            append("\r\n")
        }

        output.write(head.toByteArray(StandardCharsets.ISO_8859_1))

        for (chunk in result.chunks) {
            if (chunk.isEmpty()) continue
            output.write("${Integer.toHexString(chunk.size)}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.write(chunk)
            output.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()
        }

        output.write("0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    }
}
