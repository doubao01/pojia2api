package io.modelbridge.core

import io.modelbridge.core.http.HttpResult
import io.modelbridge.core.http.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.Socket

/** 3.10.0 HTTP 服务的请求体与 100-continue 行为。 */
class HttpServerBodyTest {
    @Test
    fun `POST with expect continue receives interim response and body`() = runBlocking {
        val received = mutableListOf<String>()
        val server = HttpServer(0, bindAddress = InetAddress.getLoopbackAddress())
        server.start { request ->
            received += request.method
            received += request.body.decodeToString()
            HttpResult.Buffered(status = 200, body = "ok".toByteArray())
        }
        try {
            Socket("127.0.0.1", server.boundPort).use { socket ->
                socket.soTimeout = 5_000
                val output = socket.getOutputStream()
                val payload = """{"hello":"world"}"""
                val head = "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Expect: 100-continue\r\n" +
                    "Content-Length: ${payload.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n"
                output.write(head.toByteArray(Charsets.ISO_8859_1))
                output.flush()

                val interim = ByteArray(25)
                val n = socket.getInputStream().read(interim)
                val interimText = interim.copyOf(n.coerceAtLeast(0)).decodeToString()
                assertTrue(interimText.startsWith("HTTP/1.1 100 Continue"), interimText)

                output.write(payload.toByteArray())
                output.flush()
                val response = socket.getInputStream().readBytes().decodeToString()
                assertTrue(response.startsWith("HTTP/1.1 200 OK"), response)
            }
            assertEquals("POST", received[0])
            assertEquals("""{"hello":"world"}""", received[1])
        } finally {
            server.stop()
        }
    }

    @Test
    fun `PUT and PATCH request bodies are read`() = runBlocking {
        val received = mutableListOf<String>()
        val server = HttpServer(0, bindAddress = InetAddress.getLoopbackAddress())
        server.start { request ->
            received += "${request.method}:${request.body.decodeToString()}"
            HttpResult.Buffered(status = 204)
        }
        try {
            listOf("PUT", "PATCH").forEach { method ->
                Socket("127.0.0.1", server.boundPort).use { socket ->
                    socket.soTimeout = 5_000
                    val payload = method.lowercase()
                    val head = "$method /v1/chat/completions HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\n" +
                        "Content-Length: ${payload.toByteArray().size}\r\n" +
                        "Connection: close\r\n\r\n" + payload
                    socket.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
                    socket.getOutputStream().flush()
                    val response = socket.getInputStream().readBytes().decodeToString()
                    assertTrue(response.startsWith("HTTP/1.1 204"), response)
                }
            }
            assertEquals(listOf("PUT:put", "PATCH:patch"), received)
        } finally {
            server.stop()
        }
    }
}
