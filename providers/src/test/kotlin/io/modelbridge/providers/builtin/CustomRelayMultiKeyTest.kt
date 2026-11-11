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

    private class KeyRotationHttp : HttpClient {
        val authorizations = mutableListOf<String>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            val auth = request.headers["Authorization"].orEmpty()
            authorizations += auth
            val ok = auth == "Bearer good-key"
            val status = if (ok) 200 else 401
            val body = if (ok) {
                """{"id":"1","object":"chat.completion","created":0,"model":"demo","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
            } else {
                """{"error":{"message":"bad key"}}"""
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
