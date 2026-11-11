package io.modelbridge.providers.workbuddy

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
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class WorkBuddyProviderTest {

    private val userMessage = listOf(
        ChatMessage(ChatMessage.Role.User, listOf(ContentPart.Text("hi"))),
    )

    @Test
    fun `chat sends the full official identity header set`() = runTest {
        val http = RecordingHttp(sse = listOf("""data: {"choices":[{"delta":{"content":"ok"}}]}"""))
        WorkBuddyProvider().chat(context(http), ChatRequest(model = "m", messages = userMessage))

        val headers = http.requests.single().headers
        // 会话头族缺一个都会被上游判为非法请求
        for (key in listOf(
            "X-Conversation-ID",
            "X-Conversation-Request-ID",
            "X-Root-Request-ID",
            "X-Conversation-Message-ID",
            "X-Request-ID",
        )) {
            assertTrue(headers[key].orEmpty().isNotBlank(), "缺少 $key")
        }
        assertEquals(headers["X-Conversation-Request-ID"], headers["X-Root-Request-ID"])
        assertEquals(headers["X-Conversation-Message-ID"], headers["X-Request-ID"])
        assertEquals("craft", headers["X-Agent-Intent"])
        assertEquals("conversation", headers["X-Agent-Purpose"])
        assertEquals("main", headers["X-Agent-Type"])
        assertEquals("WorkBuddy", headers["X-IDE-Type"])
        assertEquals(WorkBuddyIdentity.DESKTOP_VERSION_CN, headers["X-IDE-Version"])
        assertEquals(WorkBuddyIdentity.DESKTOP_UA_CN, headers["User-Agent"])
        assertEquals("www.codebuddy.cn", headers["X-Domain"])
        assertEquals("Bearer tok", headers["Authorization"])
        assertEquals("account-uid", headers["X-User-Id"])
        // 个人号 X-Private-Data 为 false
        assertEquals("false", headers["X-Private-Data"])
    }

    @Test
    fun `chat request body is gzipped and hits the chat endpoint`() = runTest {
        val http = RecordingHttp(sse = listOf("""data: {"choices":[{"delta":{"content":"ok"}}]}"""))
        WorkBuddyProvider().chat(context(http), ChatRequest(model = "m", messages = userMessage))

        val request = http.requests.single()
        assertEquals("https://copilot.tencent.com/v2/chat/completions", request.url)
        assertEquals("POST", request.method)
        assertEquals("gzip", request.headers["Content-Encoding"])
        // 上游要求流式，非流式请求也要以 stream=true 发出
        val body = unzip(request.body!!)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals(true, json["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("m", json["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `conversation id is stable per account across calls`() = runTest {
        val provider = WorkBuddyProvider()
        val http = RecordingHttp(sse = listOf("""data: {"choices":[{"delta":{"content":"ok"}}]}"""))
        provider.chat(context(http), ChatRequest(model = "m", messages = userMessage))
        provider.chat(context(http), ChatRequest(model = "m", messages = userMessage))

        val first = http.requests[0].headers["X-Conversation-ID"]
        val second = http.requests[1].headers["X-Conversation-ID"]
        assertEquals(first, second, "同一账号的会话 id 必须恒定，否则每次请求都是一次性会话")
        // 但每轮的请求 id 必须不同
        assertFalse(
            http.requests[0].headers["X-Conversation-Request-ID"] ==
                http.requests[1].headers["X-Conversation-Request-ID"],
        )
    }

    @Test
    fun `enterprise account sets private data true and tenant headers`() = runTest {
        val http = RecordingHttp(sse = listOf("""data: {"choices":[{"delta":{"content":"ok"}}]}"""))
        WorkBuddyProvider().chat(
            context(http, tenantId = "ent-1"),
            ChatRequest(model = "m", messages = userMessage),
        )

        val headers = http.requests.single().headers
        assertEquals("true", headers["X-Private-Data"])
        assertEquals("ent-1", headers["X-Enterprise-Id"])
        assertEquals("ent-1", headers["X-Tenant-Id"])
    }

    @Test
    fun `stream mode yields text deltas`() = runTest {
        val http = RecordingHttp(
            sse = listOf(
                """data: {"id":"s1","choices":[{"index":0,"delta":{"role":"assistant"}}]}""",
                """data: {"choices":[{"delta":{"content":"he"}}]}""",
                """data: {"choices":[{"delta":{"content":"llo"},"finish_reason":"stop"}]}""",
                "data: [DONE]",
            ),
        )
        val result = WorkBuddyProvider().chat(
            context(http),
            ChatRequest(model = "m", messages = userMessage, stream = true),
        )

        val chunks = (result as ChatResult.Stream).chunks.toList()
        assertEquals("hello", chunks.mapNotNull { it.deltaText }.joinToString(""))
        assertEquals("stop", chunks.last().finishReason)
    }

    @Test
    fun `non-stream mode aggregates the sse stream`() = runTest {
        val http = RecordingHttp(
            sse = listOf(
                """data: {"choices":[{"delta":{"content":"he"}}]}""",
                """data: {"choices":[{"delta":{"content":"llo"},"finish_reason":"stop"}]}""",
                "data: [DONE]",
            ),
        )
        val result = WorkBuddyProvider().chat(
            context(http),
            ChatRequest(model = "m", messages = userMessage),
        )

        val response = (result as ChatResult.Complete).response
        val text = response.choices.single().message.content
            .filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        assertEquals("hello", text)
        assertEquals("stop", response.choices.single().finishReason)
    }

    @Test
    fun `banned account surfaces upstream code 11140`() = runTest {
        val http = RecordingHttp(
            status = 403,
            body = """{"code":11140,"msg":"request illegal","displayMsg":{"zh":"账号存在异常行为，已被限制使用"}}""",
        )
        val error = runCatching {
            WorkBuddyProvider().chat(context(http), ChatRequest(model = "m", messages = userMessage))
        }.exceptionOrNull()

        assertTrue(error is ProviderException)
        val message = error!!.message.orEmpty()
        assertTrue(message.contains("11140"), "错误信息应保留上游 code：$message")
        assertTrue(message.contains("风控"), "错误信息应指出风控封禁：$message")
        assertEquals(401, (error as ProviderException).statusCode)
    }

    @Test
    fun `quota exhausted surfaces upstream code 14018 as retryable`() = runTest {
        val http = RecordingHttp(
            status = 429,
            body = """{"error":{"data":{"code":14018,"msg":"额度已用尽"}}}""",
        )
        val error = runCatching {
            WorkBuddyProvider().chat(context(http), ChatRequest(model = "m", messages = userMessage))
        }.exceptionOrNull() as ProviderException

        assertTrue(error.message.orEmpty().contains("14018"))
        assertEquals(429, error.statusCode)
        assertTrue(error.retryable)
    }

    @Test
    fun `models are parsed from the personal models endpoint`() = runTest {
        val body = """
            {"code":0,"data":{"models":[
              {"id":"deepseek-v4.1-flash","name":"DeepSeek V4.1 Flash","maxInputTokens":256000,
               "maxOutputTokens":32000,"supportsToolCall":true,"supportsReasoning":true,"vendor":"f"}
            ]}}
        """.trimIndent()
        val http = RecordingHttp(status = 200, body = body)
        val models = WorkBuddyProvider().listModels(context(http))

        assertEquals(1, models.size)
        val model = models.single()
        assertEquals("deepseek-v4.1-flash", model.id)
        assertEquals("DeepSeek V4.1 Flash", model.displayName)
        assertEquals(256000, model.contextLength)
        assertTrue(model.supportsTools)
        assertTrue(model.supportsReasoning)
        assertEquals("https://www.codebuddy.cn/console/enterprises/personal/models", http.requests.single().url)
    }

    @Test
    fun `models fall back to agent model list when models array is absent`() = runTest {
        val body = """
            {"code":0,"data":{"agents":[{"models":["auto","glm-5.3"]}]}}
        """.trimIndent()
        val http = RecordingHttp(status = 200, body = body)
        val models = WorkBuddyProvider().listModels(context(http))

        assertEquals(listOf("auto", "glm-5.3"), models.map { it.id })
    }

    @Test
    fun `missing credential is rejected before any request`() = runTest {
        val http = RecordingHttp(sse = listOf("data: [DONE]"))
        val error = runCatching {
            WorkBuddyProvider().chat(
                context(http, credential = Credential.None),
                ChatRequest(model = "m", messages = userMessage),
            )
        }.exceptionOrNull()

        assertTrue(error is ProviderException)
        assertTrue(http.requests.isEmpty(), "缺凭据时不应发出请求")
    }

    private fun context(
        http: HttpClient,
        tenantId: String = "",
        credential: Credential = Credential.OAuth(accessToken = "tok"),
    ): ProviderContext = object : ProviderContext {
        override val credential = credential
        override val http = http
        override val baseUrl = ""
        override val accountUid = "account-uid"
        override val tenantId = tenantId
        override val region = "cn"
        override val accountDomain = "www.codebuddy.cn"
    }

    private fun unzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).readBytes().decodeToString()

    private class RecordingHttp(
        private val status: Int = 200,
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
                override val headers = emptyMap<String, String>()
                override suspend fun bodyBytes() = payload.encodeToByteArray()
                override fun bodyLines(): Sequence<String> = lines.asSequence()
            }
        }
    }
}
