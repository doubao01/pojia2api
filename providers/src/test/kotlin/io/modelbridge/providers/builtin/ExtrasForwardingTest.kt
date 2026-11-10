package io.modelbridge.providers.builtin

import io.modelbridge.spi.ChatMessage
import io.modelbridge.spi.ChatRequest
import io.modelbridge.spi.ContentPart
import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import io.modelbridge.spi.HttpResponse
import io.modelbridge.spi.ProviderContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExtrasForwardingTest {
    @Test
    fun `extras keep original JSON scalar types in upstream body`() = runTest {
        val http = RecordingHttp()
        val context = object : ProviderContext {
            override val credential = Credential.None
            override val http = http
            override val baseUrl = "http://127.0.0.1:8080/v1"
        }

        CustomRelayProvider().chat(
            context,
            ChatRequest(
                model = "demo",
                messages = listOf(
                    ChatMessage(ChatMessage.Role.User, listOf(ContentPart.Text("hello"))),
                ),
                extras = mapOf(
                    "top_k" to "40",
                    "parallel_tool_calls" to "false",
                    "reasoning_effort" to "\"high\"",
                ),
            ),
        )

        val body = Json.parseToJsonElement(
            http.requests.single().body!!.decodeToString(),
        ).jsonObject
        assertEquals("http://127.0.0.1:8080/v1/chat/completions", http.requests.single().url)
        assertEquals(40, body["top_k"]!!.jsonPrimitive.int)
        assertEquals(false, body["parallel_tool_calls"]!!.jsonPrimitive.boolean)
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
    }

    private class RecordingHttp : HttpClient {
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            val payload = """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
            return object : HttpResponse {
                override val statusCode = 200
                override val headers: Map<String, String> = emptyMap()
                override suspend fun bodyBytes() = payload.encodeToByteArray()
                override fun bodyLines() = payload.lineSequence()
            }
        }
    }
}
