package io.modelbridge.providers.opencode

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenCodeProviderTest {

    private val userMessage = listOf(
        ChatMessage(ChatMessage.Role.User, listOf(ContentPart.Text("hi"))),
    )

    @Test
    fun `session id matches the official 30 character shape`() {
        val session = OpenCodeIdentity.sessionId("437537b1eb52224ef7822e02")
        assertEquals(30, session.length, "会话 id 固定 30 字符")
        assertTrue(session.startsWith("ses_"), "会话 id 以 ses_ 开头")
        // ses_ + 12 hex + 14 base62
        assertTrue(session.substring(4, 16).all { it in "0123456789abcdef" }, "前 12 位是 hex")
        assertTrue(
            session.substring(16).all { it.isLetterOrDigit() },
            "后 14 位是 base62",
        )
    }

    @Test
    fun `session id is deterministic for the same seed`() {
        val first = OpenCodeIdentity.sessionId("seed-a")
        val second = OpenCodeIdentity.sessionId("seed-a")
        assertEquals(first, second, "同一 seed 必须恒得同一会话 id")
        assertFalse(first == OpenCodeIdentity.sessionId("seed-b"), "不同 seed 必须得到不同 id")
    }

    @Test
    fun `project id is 40 hex characters`() {
        val project = OpenCodeIdentity.projectId("seed-a")
        assertEquals(40, project.length)
        assertTrue(project.all { it in "0123456789abcdef" })
    }

    @Test
    fun `chat sends the official cli session headers`() = runTest {
        val http = RecordingHttp(
            contentType = "text/event-stream",
            sse = listOf("""data: {"choices":[{"delta":{"content":"ok"}}]}"""),
        )
        OpenCodeProvider().chat(context(http), ChatRequest(model = "m", messages = userMessage))

        val headers = http.requests.single().headers
        // 匿名访问时官方 CLI 发的就是 Bearer public
        assertEquals("Bearer public", headers["Authorization"])
        assertEquals("opencode/latest/2.0.29/cli", headers["User-Agent"])
        assertEquals("cli", headers["x-opencode-client"])
        // 四个会话头必须是同一个 id
        val session = headers["x-opencode-session"]
        assertTrue(!session.isNullOrBlank())
        assertEquals(session, headers["x-opencode-session-id"])
        assertEquals(session, headers["x-session-affinity"])
        assertEquals(session, headers["X-Session-Id"])
        assertEquals(40, headers["x-opencode-project"]?.length)
    }

    @Test
    fun `api key replaces the public bearer token`() = runTest {
        val http = RecordingHttp(
            contentType = "text/event-stream",
            sse = listOf("data: [DONE]"),
        )
        OpenCodeProvider().chat(
            context(http, credential = Credential.ApiKey("sk-test")),
            ChatRequest(model = "m", messages = userMessage),
        )
        assertEquals("Bearer sk-test", http.requests.single().headers["Authorization"])
    }

    @Test
    fun `session headers stay stable per account across calls`() = runTest {
        val provider = OpenCodeProvider()
        val http = RecordingHttp(
            contentType = "text/event-stream",
            sse = listOf("data: [DONE]"),
        )
        provider.chat(context(http), ChatRequest(model = "m", messages = userMessage))
        provider.chat(context(http), ChatRequest(model = "m", messages = userMessage))

        assertEquals(
            http.requests[0].headers["x-opencode-session"],
            http.requests[1].headers["x-opencode-session"],
            "同一账号的会话 id 必须恒定",
        )
    }

    @Test
    fun `different accounts get different sessions`() = runTest {
        val provider = OpenCodeProvider()
        val http = RecordingHttp(contentType = "text/event-stream", sse = listOf("data: [DONE]"))
        provider.chat(context(http, uid = "account-a"), ChatRequest(model = "m", messages = userMessage))
        provider.chat(context(http, uid = "account-b"), ChatRequest(model = "m", messages = userMessage))

        assertFalse(
            http.requests[0].headers["x-opencode-session"] ==
                http.requests[1].headers["x-opencode-session"],
            "不同账号不能共用会话 id",
        )
    }

    @Test
    fun `chat body always streams and asks for usage`() = runTest {
        val http = RecordingHttp(contentType = "text/event-stream", sse = listOf("data: [DONE]"))
        OpenCodeProvider().chat(context(http), ChatRequest(model = "m", messages = userMessage))

        val request = http.requests.single()
        assertEquals("https://opencode.ai/inference/v1/chat/completions", request.url)
        val body = Json.parseToJsonElement(request.body!!.decodeToString()).jsonObject
        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            true,
            body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content.toBoolean(),
        )
    }

    @Test
    fun `free usage limit surfaces upstream error type`() = runTest {
        val http = RecordingHttp(
            status = 429,
            contentType = "application/json",
            body = """
                {"type":"error","error":{"type":"FreeUsageLimitError",
                 "message":"Free usage limit reached."}}
            """.trimIndent(),
        )
        val error = runCatching {
            OpenCodeProvider().chat(context(http), ChatRequest(model = "m", messages = userMessage))
        }.exceptionOrNull() as ProviderException

        assertTrue(error.message.orEmpty().contains("FreeUsageLimitError"))
        assertTrue(error.message.orEmpty().contains("按出口 IP 计"))
        assertEquals(429, error.statusCode)
        assertTrue(error.retryable)
    }

    @Test
    fun `missing api key for paid model surfaces auth error`() = runTest {
        val http = RecordingHttp(
            status = 401,
            contentType = "application/json",
            body = """{"type":"error","error":{"type":"AuthError","message":"Missing API key."}}""",
        )
        val error = runCatching {
            OpenCodeProvider().chat(context(http), ChatRequest(model = "m", messages = userMessage))
        }.exceptionOrNull() as ProviderException

        assertTrue(error.message.orEmpty().contains("AuthError"))
        assertTrue(error.message.orEmpty().contains("需要 API Key"))
        assertEquals(401, error.statusCode)
    }

    @Test
    fun `models parse limit and capabilities`() = runTest {
        val body = """
            {"object":"list","data":[
              {"id":"deepseek-v4.1-flash-free","name":"DeepSeek Flash",
               "limit":{"context":1000000,"output":32000},
               "capabilities":{"tools":{"supported":true},"reasoning":{"supported":true}},
               "family":"deepseek"},
              {"id":"gpt-5","name":"GPT-5",
               "limit":{"context":400000},
               "capabilities":{"tools":{"supported":true}}}
            ]}
        """.trimIndent()
        val http = RecordingHttp(status = 200, contentType = "application/json", body = body)
        val models = OpenCodeProvider().listModels(context(http))

        assertEquals(2, models.size)
        val free = models.first { it.id == "deepseek-v4.1-flash-free" }
        assertEquals(1000000, free.contextLength)
        assertTrue(free.supportsTools)
        assertTrue(free.supportsReasoning)
        assertEquals("true", free.extras["free"], "免费模型应标记出来")
        assertEquals("https://opencode.ai/inference/v1/models", http.requests.single().url)
        // 付费模型不带 free 标记
        assertEquals(null, models.first { it.id == "gpt-5" }.extras["free"])
    }

    @Test
    fun `non stream request aggregates the sse stream`() = runTest {
        val http = RecordingHttp(
            contentType = "text/event-stream",
            sse = listOf(
                """data: {"choices":[{"delta":{"content":"he"}}]}""",
                """data: {"choices":[{"delta":{"content":"llo"},"finish_reason":"stop"}]}""",
                "data: [DONE]",
            ),
        )
        val result = OpenCodeProvider().chat(
            context(http),
            ChatRequest(model = "m", messages = userMessage),
        )
        val response = (result as ChatResult.Complete).response
        val text = response.choices.single().message.content
            .filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        assertEquals("hello", text)
    }

    private fun context(
        http: HttpClient,
        credential: Credential = Credential.None,
        uid: String = "account-uid",
    ): ProviderContext = object : ProviderContext {
        override val credential = credential
        override val http = http
        override val baseUrl = ""
        override val accountUid = uid
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
