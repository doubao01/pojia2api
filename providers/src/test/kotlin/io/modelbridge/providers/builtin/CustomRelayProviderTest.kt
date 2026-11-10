package io.modelbridge.providers.builtin

import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import io.modelbridge.spi.HttpResponse
import io.modelbridge.spi.ProviderContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CustomRelayProviderTest {
    @Test
    fun `models use the account v1 base url`() = runTest {
        val http = RecordingHttp()
        val provider = CustomRelayProvider()

        val models = provider.listModels(context(http, "http://127.0.0.1:8080/v1"))
        assertEquals(listOf("relay-a", "relay-b"), models.map { it.id })
        assertEquals("http://127.0.0.1:8080/v1/models", http.requests.single().url)
        assertEquals(200_000L, models[1].contextLength)
        assertEquals(true, models[1].supportsTools)
        assertEquals(true, models[1].supportsReasoning)
        assertEquals("low,high,max", models[1].extras["reasoning_levels"])
    }

    private fun context(http: HttpClient, baseUrl: String) = object : ProviderContext {
        override val credential = Credential.None
        override val http = http
        override val baseUrl = baseUrl
    }

    private class RecordingHttp : HttpClient {
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            val body = """{"data":[
              {"id":"relay-a"},
              {"id":"relay-b","context_window":200000,"capabilities":{"tools":true,"reasoning":true},"reasoning_levels":["low","high","max"]}
            ]}"""
            return object : HttpResponse {
                override val statusCode = 200
                override val headers: Map<String, String> = emptyMap()
                override suspend fun bodyBytes() = body.encodeToByteArray()
                override fun bodyLines() = body.lineSequence()
            }
        }
    }
}
