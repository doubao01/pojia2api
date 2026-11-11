package io.modelbridge.providers

import io.modelbridge.spi.ChatChunk
import io.modelbridge.spi.ChatMessage
import io.modelbridge.spi.ChatRequest
import io.modelbridge.spi.ChatResponse
import io.modelbridge.spi.ChatResult
import io.modelbridge.spi.ContentPart
import io.modelbridge.spi.Credential
import io.modelbridge.spi.ModelInfo
import io.modelbridge.spi.Provider
import io.modelbridge.spi.ProviderContext
import io.modelbridge.spi.ProviderException
import io.modelbridge.spi.ToolCall
import io.modelbridge.spi.ToolCallDelta
import io.modelbridge.spi.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import io.modelbridge.spi.HttpRequest

/**
 * 面向「OpenAI 兼容 HTTP 接口」的供应商基类。
 *
 * 绝大多数上游（官方 API、自建中转、聚合站）都实现了这一套协议：
 * `GET /models`、`POST /chat/completions`，支持 SSE 流式。
 * 差异通常只有 baseUrl、鉴权头写法、以及少量字段名。
 *
 * 子类通常只需要给出 [id]、[displayName]、[defaultBaseUrl] 与 [authKind]，
 * 必要时重写 [authHeaders] 或 [extraChatFields]。
 */
abstract class OpenAiCompatibleProvider : Provider {

    /** 未显式配置 baseUrl 时使用的默认地址。 */
    abstract val defaultBaseUrl: String

    /** 模型列表路径，默认 `/models`。 */
    open val modelsPath: String get() = "/models"

    /** 对话路径，默认 `/chat/completions`。 */
    open val chatPath: String get() = "/chat/completions"

    override val requiresCredential: Boolean
        get() = authKind != io.modelbridge.spi.AuthKind.None

    protected val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
    }

    /** 组装鉴权请求头；子类可覆盖以适配非标准写法。 */
    protected open fun authHeaders(credential: Credential): Map<String, String> = when (credential) {
        is Credential.ApiKey -> mapOf("Authorization" to "Bearer ${credential.key}")
        is Credential.OAuth -> mapOf("Authorization" to "${credential.tokenType} ${credential.accessToken}")
        is Credential.Cookies -> mapOf("Cookie" to credential.raw)
        is Credential.Session -> mapOf("Authorization" to "Bearer ${credential.token}")
        Credential.None -> emptyMap()
    }

    /** 附加到对话请求体的字段，供子类注入厂商特有参数。 */
    protected open fun extraChatFields(request: ChatRequest): Map<String, JsonElement> = emptyMap()

    /** 解析模型条目；子类可覆盖以适配不同的模型列表结构。 */
    protected open fun parseModels(body: String): List<ModelInfo> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrElse {
            // 3.10.0 dex：「模型响应不是合法 JSON」
            throw ProviderException("$displayName 模型响应不是合法 JSON：${it.message.orEmpty().take(120)}")
        }
        val array = when {
            root is JsonObject -> root["data"] as? JsonArray ?: root["models"] as? JsonArray
            root is JsonArray -> root
            else -> null
        } ?: throw ProviderException("$displayName 模型响应里找不到模型数组")

        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            ModelInfo(
                id = id,
                providerId = this.id,
                displayName = obj["name"]?.jsonPrimitive?.contentOrNull ?: id,
                contextLength = obj["context_length"]?.jsonPrimitive?.longOrNull
                    ?: obj["context_window"]?.jsonPrimitive?.longOrNull
                    ?: 0,
                supportsTools = obj.booleanCapability(
                    "supports_tools", "supports_function_calling",
                    capabilitiesKeys = listOf("tools", "tool_calls", "function_calling"),
                ),
                supportsReasoning = obj.booleanCapability(
                    "supports_reasoning", "supports_thinking",
                    capabilitiesKeys = listOf("reasoning", "thinking"),
                ),
                extras = obj.reasoningLevels(),
            )
        }
    }

    /** 从常见 OpenAI 兼容模型字段或 capabilities 对象读取布尔能力。 */
    private fun JsonObject.booleanCapability(
        vararg topLevelKeys: String,
        capabilitiesKeys: List<String>,
    ): Boolean {
        topLevelKeys.forEach { key ->
            val value = this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            if (value == true) return true
        }
        val capabilities = this["capabilities"] as? JsonObject ?: return false
        return capabilitiesKeys.any { key ->
            capabilities[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
        }
    }

    /** 推理级别原样保留为逗号分隔字符串，网关输出时再拆回 JSON 数组。 */
    private fun JsonObject.reasoningLevels(): Map<String, String> {
        val levels = (this["reasoning_levels"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyMap()
        return mapOf("reasoning_levels" to levels.joinToString(","))
    }

    override suspend fun listModels(context: ProviderContext): List<ModelInfo> {
        val url = joinUrl(context.baseUrl.ifBlank { defaultBaseUrl }, modelsPath)
        // 3.10.0 自定义上游可配置多个 API Key：401/403 时依次换下一个。
        val responses = credentialCandidates(context).map { credential ->
            context.http.execute(
                HttpRequest(
                    method = "GET",
                    url = url,
                    headers = defaultHeaders() + authHeaders(credential),
                ),
            ) to credential
        }
        val (response, _) = responses.firstOrNull { it.first.statusCode !in setOf(401, 403) }
            ?: responses.last()

        if (response.statusCode !in 200..299) {
            val detail = runCatching { response.bodyBytes().decodeToString() }.getOrDefault("")
            throw ProviderException(
                message = "$displayName 拉取模型失败（HTTP ${response.statusCode}）：${detail.take(200)}",
                statusCode = if (response.statusCode == 401) 401 else 502,
            )
        }

        return parseModels(response.bodyBytes().decodeToString())
    }

    override suspend fun chat(context: ProviderContext, request: ChatRequest): ChatResult {
        val url = joinUrl(context.baseUrl.ifBlank { defaultBaseUrl }, chatPath)
        val body = buildRequestBody(request).toString().encodeToByteArray()

        val response = context.http.execute(
            HttpRequest(
                method = "POST",
                url = url,
                headers = defaultHeaders() + authHeaders(context.credential) + mapOf(
                    "Content-Type" to "application/json",
                ),
                body = body,
            ),
        )

        if (response.statusCode in setOf(401, 403) && context.extraApiKeys.isNotEmpty()) {
            for (key in context.extraApiKeys) {
                val retry = context.http.execute(
                    HttpRequest(
                        method = "POST",
                        url = url,
                        headers = defaultHeaders() + authHeaders(Credential.ApiKey(key)) + mapOf(
                            "Content-Type" to "application/json",
                        ),
                        body = body,
                    ),
                )
                if (retry.statusCode !in setOf(401, 403)) {
                    return respond(retry, request)
                }
            }
        }

        return respond(response, request)
    }

    /** 首个密钥 + 附加密钥，用于自定义上游的多密钥轮换。 */
    private fun credentialCandidates(context: ProviderContext): List<Credential> =
        listOf(context.credential) + context.extraApiKeys.map { Credential.ApiKey(it) }

    /** 统一处理对话响应：错误映射与流式/非流式解析。 */
    private suspend fun respond(
        response: io.modelbridge.spi.HttpResponse,
        request: ChatRequest,
    ): ChatResult {
        if (response.statusCode !in 200..299) {
            val detail = runCatching { response.bodyBytes().decodeToString() }.getOrDefault("")
            throw ProviderException(
                message = "$displayName 调用失败（HTTP ${response.statusCode}）：${detail.take(300)}",
                statusCode = mapStatus(response.statusCode),
                retryable = response.statusCode >= 500 || response.statusCode == 429,
            )
        }

        return if (request.stream) {
            ChatResult.Stream(parseSse(response.bodyLines(), request.model))
        } else {
            ChatResult.Complete(parseCompletion(response.bodyBytes().decodeToString(), request.model))
        }
    }

    /** 解析非流式响应。 */
    protected open fun parseCompletion(body: String, model: String): ChatResponse {
        val root = json.parseToJsonElement(body).jsonObject
        val id = root["id"]?.jsonPrimitive?.contentOrNull ?: "chatcmpl-${System.currentTimeMillis()}"
        val choices = (root["choices"] as? JsonArray).orEmpty().mapIndexed { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexed ChatResponse.Choice(index, emptyMessage())
            val message = obj["message"] as? JsonObject
            ChatResponse.Choice(
                index = obj["index"]?.jsonPrimitive?.intOrNull ?: index,
                message = parseMessage(message),
                finishReason = obj["finish_reason"]?.jsonPrimitive?.contentOrNull,
            )
        }

        return ChatResponse(
            id = id,
            model = model,
            choices = choices.ifEmpty { listOf(ChatResponse.Choice(0, emptyMessage())) },
            usage = parseUsage(root["usage"]),
        )
    }

    private fun emptyMessage() = ChatMessage(ChatMessage.Role.Assistant)

    private fun parseMessage(obj: JsonObject?): ChatMessage {
        if (obj == null) return emptyMessage()
        val text = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val toolCalls = (obj["tool_calls"] as? JsonArray).orEmpty().mapNotNull { element ->
            val call = element as? JsonObject ?: return@mapNotNull null
            val fn = call["function"] as? JsonObject ?: return@mapNotNull null
            ToolCall(
                id = call["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                name = fn["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                arguments = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
            )
        }
        return ChatMessage(
            role = ChatMessage.Role.Assistant,
            content = if (text.isEmpty()) emptyList() else listOf(ContentPart.Text(text)),
            toolCalls = toolCalls,
        )
    }

    private fun parseUsage(element: JsonElement?): Usage? {
        val obj = element as? JsonObject ?: return null
        val prompt = obj["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: return null
        val completion = obj["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        return Usage(prompt, completion, obj["total_tokens"]?.jsonPrimitive?.intOrNull ?: prompt + completion)
    }

    /**
     * 解析 SSE 流。
     *
     * 容忍三种常见写法：`data: {...}`、`data:{...}`、以及心跳注释行。
     * 结束标记 `[DONE]` 会停止迭代。
     */
    protected open fun parseSse(lines: Sequence<String>, model: String): Sequence<ChatChunk> = sequence {
        var id = "chatcmpl-${System.currentTimeMillis()}"
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(":")) continue
            if (!line.startsWith("data:")) continue

            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            if (payload.isEmpty()) continue

            val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
            obj["id"]?.jsonPrimitive?.contentOrNull?.let { id = it }

            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
            val delta = choice["delta"] as? JsonObject
            val text = delta?.get("content")?.jsonPrimitive?.contentOrNull
            val roleName = delta?.get("role")?.jsonPrimitive?.contentOrNull

            val toolDeltas = (delta?.get("tool_calls") as? JsonArray).orEmpty().mapNotNull { element ->
                val call = element as? JsonObject ?: return@mapNotNull null
                val fn = call["function"] as? JsonObject
                ToolCallDelta(
                    index = call["index"]?.jsonPrimitive?.intOrNull ?: 0,
                    id = call["id"]?.jsonPrimitive?.contentOrNull,
                    name = fn?.get("name")?.jsonPrimitive?.contentOrNull,
                    argumentsDelta = fn?.get("arguments")?.jsonPrimitive?.contentOrNull,
                )
            }

            yield(
                ChatChunk(
                    id = id,
                    model = model,
                    index = choice["index"]?.jsonPrimitive?.intOrNull ?: 0,
                    deltaText = text,
                    deltaToolCalls = toolDeltas,
                    role = roleName?.let(::roleOf),
                    finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
                    usage = parseUsage(obj["usage"]),
                ),
            )
        }
    }

    private fun roleOf(name: String): ChatMessage.Role? = when (name.lowercase()) {
        "system" -> ChatMessage.Role.System
        "user" -> ChatMessage.Role.User
        "assistant" -> ChatMessage.Role.Assistant
        "tool" -> ChatMessage.Role.Tool
        else -> null
    }

    /** 构造对话请求体。 */
    protected open fun buildRequestBody(request: ChatRequest): JsonObject = buildJsonObject {
        // 多协议扩展字段先按原始 JSON 类型写入；下面显式字段会覆盖同名键。
        val reserved = setOf(
            "model", "messages", "stream", "temperature", "top_p", "max_tokens", "stop",
            "tools", "tool_choice", "response_format",
        )
        request.extras.forEach { (key, raw) ->
            if (key.isBlank() || key in reserved) return@forEach
            val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return@forEach
            put(key, element)
        }
        put("model", request.model)
        put("stream", request.stream)
        putJsonArray("messages") {
            for (message in request.messages) {
                addJsonObject {
                    put("role", roleName(message.role))
                    val text = message.content.filterIsInstance<ContentPart.Text>()
                        .joinToString("") { it.text }
                    put("content", text)
                    if (message.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            for (call in message.toolCalls) {
                                addJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    }
                                }
                            }
                        }
                    }
                    message.toolCallId?.let { put("tool_call_id", it) }
                }
            }
        }

        request.temperature?.let { put("temperature", it) }
        request.topP?.let { put("top_p", it) }
        request.maxTokens?.let { put("max_tokens", it) }
        if (request.stop.isNotEmpty()) {
            putJsonArray("stop") { request.stop.forEach { add(it) } }
        }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                for (tool in request.tools) {
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", json.parseToJsonElement(tool.parametersJson))
                        }
                    }
                }
            }
        }
        request.toolChoice?.let { put("tool_choice", it) }
        if (request.jsonMode) {
            putJsonObject("response_format") { put("type", "json_object") }
        }
        for ((k, v) in extraChatFields(request)) put(k, v)
    }

    private fun roleName(role: ChatMessage.Role): String = when (role) {
        ChatMessage.Role.System -> "system"
        ChatMessage.Role.User -> "user"
        ChatMessage.Role.Assistant -> "assistant"
        ChatMessage.Role.Tool -> "tool"
    }

    /** 所有请求都会带的头。 */
    protected open fun defaultHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json, text/event-stream",
        "User-Agent" to "modelbridge/1.0",
    )

    private fun mapStatus(status: Int): Int = when (status) {
        401, 403 -> 401
        404 -> 404
        429 -> 429
        in 500..599 -> 502
        else -> 502
    }

    /** 拼接 baseUrl 与 path，避免出现双斜杠。 */
    protected fun joinUrl(base: String, path: String): String {
        val trimmedBase = base.trimEnd('/')
        val trimmedPath = if (path.startsWith("/")) path else "/$path"
        return trimmedBase + trimmedPath
    }
}

private fun JsonElement?.orEmpty(): List<JsonElement> = (this as? JsonArray) ?: emptyList()
