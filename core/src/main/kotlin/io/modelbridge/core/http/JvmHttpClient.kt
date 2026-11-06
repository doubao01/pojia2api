package io.modelbridge.core.http

import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import io.modelbridge.spi.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 基于 [HttpURLConnection] 的默认客户端。
 *
 * 选它而不是 OkHttp 的原因：Android 与 JVM 都自带，零依赖，
 * 网关本身不需要连接池的复杂特性。
 */
class JvmHttpClient : HttpClient {
    override suspend fun execute(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                connectTimeout = request.connectTimeoutMillis
                readTimeout = request.readTimeoutMillis
                instanceFollowRedirects = false
                for ((k, v) in request.headers) setRequestProperty(k, v)
                request.body?.let { bytes ->
                    doOutput = true
                    setFixedLengthStreamingMode(bytes.size)
                    outputStream.use { it.write(bytes) }
                }
            }

            val status = connection.responseCode
            val headers = buildMap {
                for ((k, values) in connection.headerFields) {
                    if (k == null) continue
                    put(k.lowercase(), values.joinToString(", "))
                }
            }

            val stream: InputStream? = if (status >= 400) {
                connection.errorStream
            } else {
                runCatching { connection.inputStream }.getOrNull()
            }

            UrlConnectionResponse(connection, status, headers, stream)
        }
}

/** [HttpURLConnection] 的响应包装。 */
private class UrlConnectionResponse(
    private val connection: HttpURLConnection,
    override val statusCode: Int,
    override val headers: Map<String, String>,
    private val stream: InputStream?,
) : HttpResponse {

    override suspend fun bodyBytes(): ByteArray = withContext(Dispatchers.IO) {
        stream?.use { it.readBytes() } ?: ByteArray(0)
    }

    override fun bodyLines(): Sequence<String> = sequence {
        val input = stream ?: return@sequence
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        reader.use {
            while (true) {
                val line = it.readLine() ?: break
                yield(line)
            }
        }
    }

    /** 释放底层连接，避免 keep-alive 泄漏。 */
    fun disconnect() {
        connection.disconnect()
    }
}
