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
import io.modelbridge.spi.ToolCall
import io.modelbridge.spi.ToolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenCodeProviderTest {

    private val userMessage = listOf(
        ChatMessage(ChatMessage.Role.User, listOf(ContentPart.Text("hi"))),
    )

    // ---------- 门禁适配：条件 1（env 块） ----------

    @Test
    fun `gate injects the environment block when there is no system message`() {
        val adapted = OpenCodeGate.adapt(ChatRequest(model = "m", messages = userMessage))

        val system = adapted.messages.first()
        assertEquals(ChatMessage.Role.System, system.role)
        val text = system.content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        assertTrue(text.contains("<env>"))
        assertTrue(text.contains("Working directory:"))
        assertEquals(2, adapted.messages.size, "原有消息必须保留")
    }

    @Test
    fun `gate appends the environment block to an existing system message`() {
        val original = listOf(
            ChatMessage(ChatMessage.Role.System, listOf(ContentPart.Text("You are a helper."))),
        ) + userMessage
        val adapted = OpenCodeGate.adapt(ChatRequest(model = "m", messages = original))

        assertEquals(2, adapted.messages.size, "不新增 system 消息")
        val text = adapted.messages.first().content
            .filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        assertTrue(text.startsWith("You are a helper."), "原提示词必须保留")
        assertTrue(text.contains("Working directory:"))
    }

    @Test
    fun `gate does not duplicate an environment block that is already present`() {
        val existing = "<env>\n  Working directory: /tmp/x\n</env>"
        val original = listOf(
            ChatMessage(ChatMessage.Role.System, listOf(ContentPart.Text(existing))),
        ) + userMessage
        val adapted = OpenCodeGate.adapt(ChatRequest(model = "m", messages = original))

        val text = adapted.messages.first().content
            .filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        assertEquals(existing, text, "已有 env 块时不应重复注入")
    }

    // ---------- 门禁适配：条件 2（read 工具） ----------

    @Test
    fun `gate declares the official read tool when no tools are given`() {
        val adapted = OpenCodeGate.adapt(ChatRequest(model = "m", messages = userMessage))

        val read = adapted.tools.single { it.name == "read" }
        assertTrue(read.description.contains("Read a file from the local filesystem"))
        // 描述与 schema 缺一不可
        assertTrue(read.parametersJson.contains("filePath"))
        assertTrue(read.parametersJson.contains("\"required\":[\"filePath\"]"))
    }

    @Test
    fun `gate adds read alongside a bash-only tool set`() {
        val bashOnly = listOf(
            ToolSpec(name = "bash", description = "run", parametersJson = """{"type":"object"}"""),
        )
        val adapted = OpenCodeGate.adapt(
            ChatRequest(model = "m", messages = userMessage, tools = bashOnly),
        )

        assertEquals(listOf("bash", "read"), adapted.tools.map { it.name })
    }

    @Test
    fun `gate keeps a caller supplied read declaration untouched`() {
        val custom = listOf(
            ToolSpec(name = "read", description = "my own", parametersJson = """{"type":"object"}"""),
        )
        val adapted = OpenCodeGate.adapt(
            ChatRequest(model = "m", messages = userMessage, tools = custom),
        )

        assertEquals(1, adapted.tools.size)
        assertEquals("my own", adapted.tools.single().description, "不应覆盖调用方的声明")
    }

    // ---------- 门禁适配：工具名别名 ----------

    @Test
    fun `gate renames the two tool names that cause refusal`() {
        val tools = listOf(
            ToolSpec(name = "subagent_fork", description = "d", parametersJson = "{}"),
            ToolSpec(name = "working_directory", description = "d", parametersJson = "{}"),
            ToolSpec(name = "grep", description = "d", parametersJson = "{}"),
        )
        val adapted = OpenCodeGate.adapt(
            ChatRequest(model = "m", messages = userMessage, tools = tools),
        )

        val names = adapted.tools.map { it.name }
        assertTrue(names.contains("agent_fork"))
        assertTrue(names.contains("cwd_tool"))
        assertFalse(names.contains("subagent_fork"))
        assertFalse(names.contains("working_directory"))
        assertTrue(names.contains("grep"), "其它工具名不受影响")
        assertTrue(names.contains("read"))
    }

    @Test
    fun `tool names are restored on the way back`() {
        assertEquals("subagent_fork", OpenCodeGate.restoreToolName("agent_fork"))
        assertEquals("working_directory", OpenCodeGate.restoreToolName("cwd_tool"))
        assertEquals("grep", OpenCodeGate.restoreToolName("grep"))
    }

    @Test
    fun `gate rewrites tool call names in history`() {
        val history = listOf(
            ChatMessage(
                ChatMessage.Role.Assistant,
                toolCalls = listOf(
                    ToolCall("c1", "subagent_fork", "{}"),
                    ToolCall("c2", "grep", "{}"),
                ),
            ),
        )
        val adapted = OpenCodeGate.adapt(ChatRequest(model = "m", messages = history))

        val names = adapted.messages.flatMap { it.toolCalls }.map { it.name }
        assertTrue(names.contains("agent_fork"))
        assertTrue(names.contains("grep"))
    }

    // ---------- 是否走门禁 ----------

    @Test
    fun `anonymous free model goes through the gate`() {
        assertTrue(OpenCodeGate.needsAdaptation(apiKey = "", model = "deepseek-v4.1-flash-free"))
        assertTrue(OpenCodeGate.needsAdaptation(apiKey = "", model = "gpt-5"))
    }

    @Test
    fun `keyed paid model skips the gate`() {
        assertFalse(OpenCodeGate.needsAdaptation(apiKey = "sk-x", model = "gpt-5"))
    }

    // ---------- 请求形态 ----------

    @Test
    fun `chat sends only the headers that matter`() = runTest {
        val http = RecordingHttp(
            contentType = "text/event-stream",
            sse = listOf("""data: {"choices":[{"delta":{"content":"ok"}}]}"""),
        )
        OpenCodeProvider().chat(context(http), ChatRequest(model = "m", messages = userMessage))

        val headers = http.requests.single().headers
        assertEquals("Bearer public", headers["Authorization"])
        assertEquals("opencode/latest/2.0.29/cli", headers["User-Agent"])
        assertEquals("application/json", headers["Content-Type"])
        // 会话头已确认对门禁无影响，不再发送
        assertNull(headers["x-opencode-session"])
        assertNull(headers["x-opencode-session-id"])
        assertNull(headers["x-session-affinity"])
        assertNull(headers["X-Session-Id"])
        assertNull(headers["x-opencode-project"])
        assertNull(headers["x-opencode-client"])
    }

    @Test
    fun `api key replaces the public bearer token`() = runTest {
        val http = RecordingHttp(contentType = "text/event-stream", sse = listOf("data: [DONE]"))
        OpenCodeProvider().chat(
            context(http, credential = Credential.ApiKey("sk-test")),
            ChatRequest(model = "gpt-5", messages = userMessage),
        )
        assertEquals("Bearer sk-test", http.requests.single().headers["Authorization"])
    }

    @Test
    fun `chat body carries the gate adaptation and always streams`() = runTest {
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
        // 门禁条件 1：env 块进了 body
        val messages = body["messages"]!!.jsonArray
        val systemText = messages.first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(systemText.contains("Working directory:"))
        // 门禁条件 2：read 工具进了 body
        val toolNames = body["tools"]!!.jsonArray.map {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        }
        assertTrue(toolNames.contains("read"))
    }

    // ---------- TLS 套件常量 ----------

    @Test
    fun `tls cipher list matches the official seventeen suites in order`() {
        assertEquals(17, OpenCodeTls.CIPHER_SUITES.size)
        assertEquals("TLS_AES_128_GCM_SHA256", OpenCodeTls.CIPHER_SUITES.first())
        assertEquals("AES256-SHA", OpenCodeTls.CIPHER_SUITES.last())
        assertEquals(OpenCodeTls.CIPHER_SUITES.joinToString(":"), OpenCodeTls.CIPHER_SUITE_STRING)
        // CBC-SHA 套件不能删，删了同样被拒
        assertTrue(OpenCodeTls.CIPHER_SUITES.contains("AES128-SHA"))
        assertEquals(6, OpenCodeTls.JDK_DISABLED_BY_DEFAULT.size)
    }

    // ---------- 响应解析 ----------

    @Test
    fun `stream mode yields text deltas`() = runTest {
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
            ChatRequest(model = "m", messages = userMessage, stream = true),
        )
        val chunks = (result as ChatResult.Stream).chunks.toList()
        assertEquals("hello", chunks.mapNotNull { it.deltaText }.joinToString(""))
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

    @Test
    fun `tool calls coming back as aliases are restored`() = runTest {
        val http = RecordingHttp(
            contentType = "text/event-stream",
            sse = listOf(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"agent_fork","arguments":"{}"}}]}}]}""",
                "data: [DONE]",
            ),
        )
        val result = OpenCodeProvider().chat(
            context(http),
            ChatRequest(model = "m", messages = userMessage),
        )
        val response = (result as ChatResult.Complete).response
        assertEquals("subagent_fork", response.choices.single().message.toolCalls.single().name)
    }

    // ---------- 错误映射 ----------

    @Test
    fun `free tier refusal keeps the upstream type and does not claim quota exhaustion`() = runTest {
        val http = RecordingHttp(
            status = 429,
            contentType = "application/json",
            body = """{"type":"error","error":{"type":"FreeUsageLimitError","message":"Free usage limit reached."}}""",
        )
        val error = runCatching {
            OpenCodeProvider().chat(context(http), ChatRequest(model = "m", messages = userMessage))
        }.exceptionOrNull() as ProviderException

        assertTrue(error.message.orEmpty().contains("FreeUsageLimitError"))
        assertTrue(
            error.message.orEmpty().contains("门禁"),
            "提示要说明也可能是门禁拒绝，而不是断言额度耗尽：${error.message}",
        )
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
            OpenCodeProvider().chat(context(http), ChatRequest(model = "gpt-5", messages = userMessage))
        }.exceptionOrNull() as ProviderException

        assertTrue(error.message.orEmpty().contains("需要 API Key"))
        assertEquals(401, error.statusCode)
    }

    // ---------- 模型列表 ----------

    @Test
    fun `models parse nested capability objects and mark free models`() = runTest {
        val body = """
            {"object":"list","data":[
              {"id":"deepseek-v4.1-flash-free","name":"DeepSeek Flash",
               "limit":{"context":1000000,"output":32000},
               "capabilities":{"tools":{"supported":true},"reasoning":{"supported":true}}},
              {"id":"gpt-5","name":"GPT-5","limit":{"context":400000},
               "capabilities":{"tools":{"supported":true}}}
            ]}
        """.trimIndent()
        val http = RecordingHttp(status = 200, contentType = "application/json", body = body)
        val models = OpenCodeProvider().listModels(context(http))

        assertEquals(2, models.size)
        val free = models.first { it.id.endsWith("-free") }
        assertEquals(1000000, free.contextLength)
        assertTrue(free.supportsTools, "tools 是嵌套对象，必须读 supported")
        assertTrue(free.supportsReasoning)
        assertEquals("true", free.extras["free"])
        assertNull(models.first { it.id == "gpt-5" }.extras["free"])
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
