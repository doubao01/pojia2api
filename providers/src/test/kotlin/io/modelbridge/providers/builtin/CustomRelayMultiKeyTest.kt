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
import org.junit.jupiter.api.Test

/** 3.10.0 自定义上游的多个 API Key：401 时依次换下一个。 */
class CustomRelayMultiKeyTest {
    @Test
    fun `chat retries with the next api key after 401`() = runTest {
        val http = KeyRotationHttp()
        val provider = CustomRelayProvider()
        val context = object : ProviderContext {
            override val credential = Credential.ApiKey("bad-key")
            override val http = http
            override val baseUrl = "http://127.0.0.1:8080/v1"
            override val extraApiKeys = listOf("good-key")
        }

        val result = provider.chat(
            context,
            ChatRequest(
                model = "demo",
                messages = listOf(ChatMessage(ChatMessage.Role.User, listOf())),
            ),
        )

        assertEquals(listOf("Bearer bad-key", "Bearer good-key"), http.authorizations)
        val response = (result as io.modelbridge.spi.ChatResult.Complete).response
        assertEquals("demo", response.model)
        val text = response.choices.single().message.content
            .filterIsInstance<io.modelbridge.spi.ContentPart.Text>()
            .joinToString("") { it.text }
        assertEquals("ok", text)
    }

    @Test
    fun `listModels retries with the next api key after 401`() = runTest {
        val http = KeyRotationHttp(models = true)
        val provider = CustomRelayProvider()
        val context = object : ProviderContext {
            override val credential = Credential.ApiKey("bad-key")
            override val http = http
            override val baseUrl = "http://127.0.0.1:8080/v1"
            override val extraApiKeys = listOf("good-key")
        }

        val models = provider.listModels(context)

        assertEquals(listOf("Bearer bad-key", "Bearer good-key"), http.authorizations)
        assertEquals(listOf("demo"), models.map { it.id })
    }

    @Test
    fun `listModels stops at the first working key instead of trying all`() = runTest {
        val http = KeyRotationHttp(models = true, extraKeys = listOf("good-key", "spare-key"))
        val provider = CustomRelayProvider()
        val context = object : ProviderContext {
            override val credential = Credential.ApiKey("bad-key")
            override val http = http
            override val baseUrl = "http://127.0.0.1:8080/v1"
            override val extraApiKeys = listOf("good-key", "spare-key")
        }

        provider.listModels(context)

        // 第二个 key 就成功了，第三个不该再发请求（否则白白消耗额度）
        assertEquals(listOf("Bearer bad-key", "Bearer good-key"), http.authorizations)
    }

    /**
     * 主密钥 401、附加密钥 200。
     *
     * [models] 为真时响应模型列表接口，否则响应对话接口。
     */
    private class KeyRotationHttp(
        private val models: Boolean = false,
        private val extraKeys: List<String> = listOf("good-key"),
    ) : HttpClient {
        val authorizations = mutableListOf<String>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            val auth = request.headers["Authorization"].orEmpty()
            authorizations += auth
            val ok = auth.removePrefix("Bearer ") in extraKeys
            val status = if (ok) 200 else 401
            val body = when {
                !ok -> """{"error":{"message":"bad key"}}"""
                models -> """{"object":"list","data":[{"id":"demo","object":"model"}]}"""
                else -> """{"id":"1","object":"chat.completion","created":0,"model":"demo","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
            }
            return object : HttpResponse {
                override val statusCode = status
                override val headers: Map<String, String> = emptyMap()
                override suspend fun bodyBytes() = body.encodeToByteArray()
                override fun bodyLines() = body.lineSequence()
            }
        }
    }
}
