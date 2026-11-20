package io.modelbridge.providers.builtin

import io.modelbridge.spi.ChatMessage
import io.modelbridge.spi.ChatRequest
import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import io.modelbridge.spi.HttpResponse
import io.modelbridge.spi.ProviderContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 自定义上游的多密钥轮换。
 *
 * 判断标准：换钥能改变结果才换（认证失败、限流、欠费、上游故障、网络异常），
 * 请求本身有问题（400/404）就不换，避免白白多打一次。
 */
class CustomRelayMultiKeyTest {

    private fun context(http: HttpClient, extra: List<String>) = object : ProviderContext {
        override val credential = Credential.ApiKey("key-0")
        override val http = http
        override val baseUrl = "http://127.0.0.1:8080/v1"
        override val extraApiKeys = extra
    }

    private fun request() = ChatRequest(
        model = "demo",
        messages = listOf(ChatMessage(ChatMessage.Role.User, listOf())),
    )

    @Test
    fun `chat retries with the next api key after 401`() = runTest {
        val http = ScriptedHttp(401 to "", 200 to CHAT_OK)
        val result = CustomRelayProvider().chat(context(http, listOf("key-1")), request())

        assertEquals(listOf("key-0", "key-1"), http.keys)
        val response = (result as io.modelbridge.spi.ChatResult.Complete).response
        assertEquals("ok", response.choices.single().message.content
            .filterIsInstance<io.modelbridge.spi.ContentPart.Text>().joinToString("") { it.text })
    }

    @Test
    fun `chat rotates on 429 rate limit`() = runTest {
        val http = ScriptedHttp(429 to "", 200 to CHAT_OK)
        CustomRelayProvider().chat(context(http, listOf("key-1")), request())

        assertEquals(listOf("key-0", "key-1"), http.keys)
    }

    @Test
    fun `chat rotates on 402 insufficient balance`() = runTest {
        val http = ScriptedHttp(402 to "", 200 to CHAT_OK)
        CustomRelayProvider().chat(context(http, listOf("key-1")), request())

        assertEquals(listOf("key-0", "key-1"), http.keys)
    }

    @Test
    fun `chat rotates on 5xx upstream failure`() = runTest {
        val http = ScriptedHttp(503 to "", 200 to CHAT_OK)
        CustomRelayProvider().chat(context(http, listOf("key-1")), request())

        assertEquals(listOf("key-0", "key-1"), http.keys)
    }

    @Test
    fun `chat does not rotate on 400 because the request itself is wrong`() = runTest {
        val http = ScriptedHttp(400 to """{"error":"bad request"}""")

        assertThrows(io.modelbridge.spi.ProviderException::class.java) {
            kotlinx.coroutines.test.runTest {
                CustomRelayProvider().chat(context(http, listOf("key-1", "key-2")), request())
            }
        }
        // 只打了第一个 key：换钥救不了参数错误
        assertEquals(listOf("key-0"), http.keys)
    }

    @Test
    fun `chat stops rotating once a key succeeds`() = runTest {
        val http = ScriptedHttp(401 to "", 200 to CHAT_OK)
        CustomRelayProvider().chat(context(http, listOf("key-1", "key-2")), request())

        // key-1 成功，key-2 不该再发请求
        assertEquals(listOf("key-0", "key-1"), http.keys)
    }

    @Test
    fun `chat retries on network exception`() = runTest {
        val http = ScriptedHttp(
            200 to CHAT_OK,
            failAt = 0,
        )
        CustomRelayProvider().chat(context(http, listOf("key-1")), request())

        assertEquals(listOf("key-0", "key-1"), http.keys)
    }

    @Test
    fun `chat throws the last error when every key fails`() = runTest {
        val http = ScriptedHttp(401 to "", 401 to "")

        assertThrows(io.modelbridge.spi.ProviderException::class.java) {
            kotlinx.coroutines.test.runTest {
                CustomRelayProvider().chat(context(http, listOf("key-1")), request())
            }
        }
        assertEquals(listOf("key-0", "key-1"), http.keys)
    }

    @Test
    fun `listModels rotates on 429`() = runTest {
        val http = ScriptedHttp(429 to "", 200 to MODELS_OK)
        val models = CustomRelayProvider().listModels(context(http, listOf("key-1")))

        assertEquals(listOf("key-0", "key-1"), http.keys)
        assertEquals(listOf("demo"), models.map { it.id })
    }

    @Test
    fun `listModels does not rotate on 404`() = runTest {
        val http = ScriptedHttp(404 to "")

        assertThrows(io.modelbridge.spi.ProviderException::class.java) {
            kotlinx.coroutines.test.runTest {
                CustomRelayProvider().listModels(context(http, listOf("key-1")))
            }
        }
        assertEquals(listOf("key-0"), http.keys)
    }

    @Test
    fun `listModels stops at the first working key`() = runTest {
        val http = ScriptedHttp(401 to "", 200 to MODELS_OK)
        CustomRelayProvider().listModels(context(http, listOf("key-1", "key-2")))

        assertEquals(listOf("key-0", "key-1"), http.keys)
    }

    /**
     * 按脚本依次返回响应，记录每次用的密钥。
     *
     * [failAt] 指定第几次调用抛网络异常（0 起算）。
     */
    private class ScriptedHttp(
        private vararg val steps: Pair<Int, String>,
        private val failAt: Int = -1,
    ) : HttpClient {
        val keys = mutableListOf<String>()
        private var index = 0

        override suspend fun execute(request: HttpRequest): HttpResponse {
            keys += request.headers["Authorization"].orEmpty().removePrefix("Bearer ")
            val current = index++
            if (current == failAt) throw java.io.IOException("connection reset")

            val (status, body) = steps.getOrElse(current) { steps.last() }
            return object : HttpResponse {
                override val statusCode = status
                override val headers: Map<String, String> = emptyMap()
                override suspend fun bodyBytes() = body.encodeToByteArray()
                override fun bodyLines() = body.lineSequence()
            }
        }
    }

    private companion object {
        const val CHAT_OK =
            """{"id":"1","object":"chat.completion","created":0,"model":"demo","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        const val MODELS_OK = """{"object":"list","data":[{"id":"demo","object":"model"}]}"""
    }
}
