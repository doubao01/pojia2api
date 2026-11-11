package io.modelbridge.providers.kilo

import io.modelbridge.spi.ChatMessage
import io.modelbridge.spi.ChatRequest
import io.modelbridge.spi.ChatResult
import io.modelbridge.spi.ContentPart
import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import io.modelbridge.spi.HttpResponse
import io.modelbridge.spi.ProviderContext
import io.modelbridge.spi.ProviderException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KiloProviderTest {

    private val userMessage = listOf(
        ChatMessage(ChatMessage.Role.User, listOf(ContentPart.Text("hi"))),
    )

    @Test
    fun `chat uses the gateway path not the plain v1 path`() = runTest {
        val http = RecordingHttp(sse = listOf("""data: {"choices":[{"delta":{"content":"ok"}}]}"""))
        KiloProvider().chat(context(http), ChatRequest(model = "m", messages = userMessage))

        assertEquals(
            "https://kilo.ai/api/gateway/v1/chat/completions",
            http.requests.single().url,
            "对话端点是 /api/gateway/v1/...，不是 /v1/...",
        )
    }

    @Test
    fun `anonymous request carries no authorization header`() = runTest {
        val http = RecordingHttp(sse = listOf("data: [DONE]"))
        KiloProvider().chat(context(http), ChatRequest(model = "m", messages = userMessage))

        assertNull(
            http.requests.single().headers["Authorization"],
            "匿名调免费模型不带凭据，带了反而可能被拒",
        )
    }

    @Test
    fun `api key is forwarded when present`() = runTest {
        val http = RecordingHttp(sse = listOf("data: [DONE]"))
        KiloProvider().chat(
            context(http, credential = Credential.ApiKey("sk-kilo")),
            ChatRequest(model = "m", messages = userMessage),
        )
        assertEquals("Bearer sk-kilo", http.requests.single().headers["Authorization"])
    }

    @Test
    fun `max tokens fallback prevents empty answers from reasoning models`() = runTest {
        val http = RecordingHttp(sse = listOf("data: [DONE]"))
        KiloProvider().chat(context(http), ChatRequest(model = "m", messages = userMessage))

        val body = Json.parseToJsonElement(http.requests.single().body!!.decodeToString()).jsonObject
        assertEquals(
            KiloProvider.DEFAULT_MAX_TOKENS,
            body["max_tokens"]!!.jsonPrimitive.int,
            "未指定时必须兜底，否则思考链会吃光预算导致正文为空",
        )
        assertTrue(
            KiloProvider.DEFAULT_MAX_TOKENS >= 2000,
            "实测 max_tokens=100 时多数模型返回空正文，兜底值必须够大",
        )
    }

    @Test
    fun `explicit max tokens is respected`() = runTest {
        val http = RecordingHttp(sse = listOf("data: [DONE]"))
        KiloProvider().chat(
            context(http),
            ChatRequest(model = "m", messages = userMessage, maxTokens = 512),
        )
        val body = Json.parseToJsonElement(http.requests.single().body!!.decodeToString()).jsonObject
        assertEquals(512, body["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `anonymous model list exposes only free models`() = runTest {
        val body = """
            {"data":[
              {"id":"cohere/north-mini-code:free","name":"Cohere Free","isFree":true,
               "context_length":256000,"supported_parameters":["tools","reasoning"]},
              {"id":"anthropic/claude-opus-5.5","name":"Claude Opus","isFree":false,
               "context_length":200000,"supported_parameters":["tools"]}
            ]}
        """.trimIndent()
        val http = RecordingHttp(status = 200, contentType = "application/json", body = body)
        val models = KiloProvider().listModels(context(http))

        assertEquals(1, models.size, "匿名时只暴露 isFree 模型，否则调用方会撞 401")
        val free = models.single()
        assertEquals("cohere/north-mini-code:free", free.id)
        assertEquals(256000, free.contextLength)
        assertTrue(free.supportsTools)
        assertTrue(free.supportsReasoning)
        assertEquals("true", free.extras["free"])
        assertEquals("https://kilo.ai/api/gateway/models", http.requests.single().url)
    }

    @Test
    fun `keyed account sees every model`() = runTest {
        val body = """
            {"data":[
              {"id":"cohere/north-mini-code:free","isFree":true,"supported_parameters":["tools"]},
              {"id":"anthropic/claude-opus-5.5","isFree":false,"supported_parameters":["tools"]}
            ]}
        """.trimIndent()
        val http = RecordingHttp(status = 200, contentType = "application/json", body = body)
        val models = KiloProvider().listModels(
            context(http, credential = Credential.ApiKey("sk-kilo")),
        )
        assertEquals(2, models.size)
    }

    @Test
    fun `model list accepts a bare array as well as a data envelope`() = runTest {
        val body = """[{"id":"openrouter/free","isFree":true,"supported_parameters":["tools"]}]"""
        val http = RecordingHttp(status = 200, contentType = "application/json", body = body)
        val models = KiloProvider().listModels(context(http))
        assertEquals("openrouter/free", models.single().id)
    }

    @Test
    fun `paid model refusal surfaces the upstream code with a hint`() = runTest {
        val http = RecordingHttp(
            status = 401,
            contentType = "application/json",
            body = """{"error":{"code":"PAID_MODEL_AUTH_REQUIRED","message":"You need to sign in to use this model."},"error_type":"paid_model_auth_required"}""",
        )
        val error = runCatching {
            KiloProvider().chat(context(http), ChatRequest(model = "gpt-5", messages = userMessage))
        }.exceptionOrNull() as ProviderException

        assertTrue(error.message.orEmpty().contains("PAID_MODEL_AUTH_REQUIRED"))
        assertTrue(error.message.orEmpty().contains("isFree"))
        assertEquals(401, error.statusCode)
    }

    @Test
    fun `unknown model id surfaces model_not_found with the id hint`() = runTest {
        val http = RecordingHttp(
            status = 404,
            contentType = "application/json",
            body = """{"error":"The requested model 'x' does not exist.","error_type":"model_not_found"}""",
        )
        val error = runCatching {
            KiloProvider().chat(context(http), ChatRequest(model = "x", messages = userMessage))
        }.exceptionOrNull() as ProviderException

        assertTrue(error.message.orEmpty().contains("model_not_found"))
        assertEquals(404, error.statusCode)
    }

    @Test
    fun `rate limit is retryable`() = runTest {
        val http = RecordingHttp(
            status = 429,
            contentType = "application/json",
            body = """{"error":{"message":"Rate limit exceeded: limit_rpd"}}""",
        )
        val error = runCatching {
            KiloProvider().chat(context(http), ChatRequest(model = "m", messages = userMessage))
        }.exceptionOrNull() as ProviderException

        assertEquals(429, error.statusCode)
        assertTrue(error.retryable)
    }

    @Test
    fun `completion parses content and zero cost usage`() = runTest {
        val body = """
            {"id":"gen-1","model":"cohere/north-mini-code:free",
             "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":21,"completion_tokens":24,"total_tokens":45,"cost":0}}
        """.trimIndent()
        val http = RecordingHttp(status = 200, contentType = "application/json", body = body)
        val result = KiloProvider().chat(
            context(http),
            ChatRequest(model = "m", messages = userMessage),
        )
        val response = (result as ChatResult.Complete).response
        val text = response.choices.single().message.content
            .filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        assertEquals("hello", text)
        assertEquals(21, response.usage?.promptTokens)
        assertEquals(24, response.usage?.completionTokens)
    }

    @Test
    fun `stream mode yields deltas`() = runTest {
        val http = RecordingHttp(
            sse = listOf(
                """data: {"choices":[{"delta":{"content":"he"}}]}""",
                """data: {"choices":[{"delta":{"content":"llo"},"finish_reason":"stop"}]}""",
                "data: [DONE]",
            ),
        )
        val result = KiloProvider().chat(
            context(http),
            ChatRequest(model = "m", messages = userMessage, stream = true),
        )
        val chunks = (result as ChatResult.Stream).chunks.toList()
        assertEquals("hello", chunks.mapNotNull { it.deltaText }.joinToString(""))
    }

    @Test
    fun `non stream request aggregates the stream`() = runTest {
        val http = RecordingHttp(
            sse = listOf(
                """data: {"choices":[{"delta":{"content":"he"}}]}""",
                """data: {"choices":[{"delta":{"content":"llo"}}]}""",
                "data: [DONE]",
            ),
        )
        val result = KiloProvider().chat(
            context(http),
            ChatRequest(model = "m", messages = userMessage),
        )
        val response = (result as ChatResult.Complete).response
        val text = response.choices.single().message.content
            .filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        assertEquals("hello", text)
    }

    @Test
    fun `tools are forwarded to the upstream`() = runTest {
        val http = RecordingHttp(sse = listOf("data: [DONE]"))
        KiloProvider().chat(
            context(http),
            ChatRequest(
                model = "m",
                messages = userMessage,
                tools = listOf(
                    io.modelbridge.spi.ToolSpec(
                        name = "get_weather",
                        description = "weather",
                        parametersJson = """{"type":"object","properties":{}}""",
                    ),
                ),
            ),
        )
        val body = Json.parseToJsonElement(http.requests.single().body!!.decodeToString()).jsonObject
        assertFalse(body["tools"] == null, "工具声明必须透传")
    }

    private fun context(
        http: HttpClient,
        credential: Credential = Credential.None,
    ): ProviderContext = object : ProviderContext {
        override val credential = credential
        override val http = http
        override val baseUrl = ""
        override val accountUid = "kilo-account"
    }

    private class RecordingHttp(
        private val status: Int = 200,
        private val contentType: String = "text/event-stream",
        private val body: String = "",
        private val sse: List<String> = emptyList(),
    ) : HttpClient {
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            val lines = sse
            val payload = if (lines.isEmpty()) body else lines.joinToString("\n") + "\n"
            return object : HttpResponse {
                override val statusCode = status
                override val headers = mapOf("Content-Type" to contentType)
                override suspend fun bodyBytes() = payload.encodeToByteArray()
                override fun bodyLines(): Sequence<String> = lines.asSequence()
            }
        }
    }
}
