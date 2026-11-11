package io.modelbridge.providers.kilo

import io.modelbridge.spi.AuthKind
import io.modelbridge.spi.ChatChunk
import io.modelbridge.spi.ChatMessage
import io.modelbridge.spi.ChatRequest
import io.modelbridge.spi.ChatResponse
import io.modelbridge.spi.ChatResult
import io.modelbridge.spi.ContentPart
import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpRequest
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Kilo AI 公共网关（`kilo`）。
 *
 * 匿名即可调用标 `isFree` 的模型，无需登录或 API Key。实测 12 个免费模型里
 * 9 个能正常产出回答，其中多数可写代码；响应里 `usage.cost` 为 0。
 *
 * 端点（与 OpenAI 兼容路径不同，需注意）：
 * - 模型列表：`/api/gateway/models`（不是 `/v1/models`）
 * - 对话：`/api/gateway/v1/chat/completions`
 *
 * `model` 必须用列表里给出的 `id` 原值（形如 `cohere/north-mini-code:free`）；
 * 传 slug 会返回 `model_not_found`。
 *
 * 两个实测注意点：
 * - 模型带推理能力，`max_tokens` 过小时思维链会吃光预算、正文为空。所以这里
 *   给未指定上限的请求兜一个下限，避免调用方拿到空响应。
 * - `kilo-auto/*` 与部分 `*-free` 模型在长推理下可能只产出思维链，正文为空；
 *   属于模型自身行为，不是渠道故障。
 */
class KiloProvider : Provider {

    override val id: String = "kilo"

    override val displayName: String = "Kilo AI"

    /** 匿名可用，填了 key 就走 ApiKey（可调非免费模型）。 */
    override val authKind: AuthKind get() = AuthKind.None

    override val requiresCredential: Boolean get() = false

    override val loginUrl: String? get() = "https://kilo.ai"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
    }

    private fun apiKey(context: ProviderContext): String = when (val credential = context.credential) {
        is Credential.ApiKey -> credential.key
        is Credential.OAuth -> credential.accessToken
        is Credential.Session -> credential.token
        else -> ""
    }

    private fun headers(context: ProviderContext): Map<String, String> {
        val key = apiKey(context)
        return buildMap {
            put("Content-Type", "application/json")
            put("Accept", "application/json")
            put("User-Agent", "modelbridge/1.0")
            // 匿名时不带 Authorization，网关本来就允许无凭据调免费模型
            if (key.isNotBlank()) put("Authorization", "Bearer $key")
        }
    }

    private fun baseUrl(context: ProviderContext): String =
        context.baseUrl.ifBlank { GATEWAY_BASE }

    /**
     * 拉取模型列表。
     *
     * 默认只暴露 `isFree` 的模型——匿名调用非免费模型会 401，暴露出来只会让
     * 调用方以为渠道坏了。配了 key 时把全部模型都列出来。
     */
    override suspend fun listModels(context: ProviderContext): List<ModelInfo> {
        val response = context.http.execute(
            HttpRequest(
                method = "GET",
                url = baseUrl(context) + "/api/gateway/models",
                headers = headers(context),
                connectTimeoutMillis = 15_000,
                readTimeoutMillis = 30_000,
            ),
        )
        if (response.statusCode !in 200..299) {
            throw ProviderException(
                message = "$displayName 拉取模型失败（HTTP ${response.statusCode}）",
                statusCode = mapStatus(response.statusCode),
                retryable = response.statusCode >= 500 || response.statusCode == 429,
            )
        }
        val root = runCatching { json.parseToJsonElement(response.bodyBytes().decodeToString()) }
            .getOrNull()
        // 上游有两种形态：{data:[...]} 或直接数组
        val items: List<JsonElement> = when (root) {
            is JsonObject -> (root["data"] as? JsonArray)?.toList().orEmpty()
            is JsonArray -> root.toList()
            else -> throw ProviderException("$displayName 模型响应不是合法 JSON")
        }
        val withKey = apiKey(context).isNotBlank()

        val models = items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val modelId = obj.text("id")
            if (modelId.isBlank()) return@mapNotNull null
            val isFree = obj.boolean("isFree")
            // 匿名时只列免费模型
            if (!withKey && !isFree) return@mapNotNull null
            val supported = (obj["supported_parameters"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ModelInfo(
                id = modelId,
                providerId = id,
                displayName = obj.text("name").ifBlank { modelId },
                contextLength = obj.long("context_length") ?: 0,
                supportsTools = supported.contains("tools"),
                supportsReasoning = supported.contains("reasoning"),
                extras = buildMap {
                    if (isFree) put("free", "true")
                },
            )
        }
        if (models.isEmpty()) throw ProviderException("$displayName 没有可用的模型")
        return models
    }

    override suspend fun chat(context: ProviderContext, request: ChatRequest): ChatResult {
        val payload = buildBody(request)
        val response = context.http.execute(
            HttpRequest(
                method = "POST",
                url = baseUrl(context) + "/api/gateway/v1/chat/completions",
                headers = headers(context),
                body = payload,
                connectTimeoutMillis = 20_000,
                readTimeoutMillis = 300_000,
                streaming = true,
            ),
        )

        if (response.statusCode !in 200..299) {
            val detail = runCatching { response.bodyBytes().decodeToString() }.getOrDefault("")
            throw ProviderException(
                message = describeError(response.statusCode, detail),
                statusCode = mapStatus(response.statusCode),
                retryable = response.statusCode >= 500 || response.statusCode == 429,
            )
        }

        val contentType = response.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value.orEmpty()
        return if (contentType.contains("text/event-stream", ignoreCase = true)) {
            if (request.stream) {
                ChatResult.Stream(parseSse(response.bodyLines(), request.model))
            } else {
                ChatResult.Complete(aggregate(response.bodyLines(), request.model))
            }
        } else {
            ChatResult.Complete(parseCompletion(response.bodyBytes().decodeToString(), request.model))
        }
    }

    private fun describeError(status: Int, detail: String): String {
        val root = runCatching { json.parseToJsonElement(detail).jsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        val code = error?.text("code") ?: root?.text("error_type")
        val message = error?.text("message") ?: root?.text("error")
        val hint = when {
            code == "PAID_MODEL_AUTH_REQUIRED" -> "该模型需要登录，匿名只能用 isFree 模型"
            code == "model_not_found" -> "模型 id 不对，要用列表里的 id 原值"
            status == 401 -> "需要凭据"
            status == 429 -> "频率或每日额度受限"
            else -> ""
        }
        return buildString {
            append(displayName).append(" 调用失败（HTTP ").append(status)
            if (!code.isNullOrBlank()) append("，").append(code)
            append("）")
            if (!message.isNullOrBlank()) append("：").append(message.take(200))
            if (hint.isNotBlank()) append("（").append(hint).append("）")
        }
    }

    /**
     * 构造请求体。
     *
     * `max_tokens` 有下限：这些模型都带推理，预算太小时思维链会把输出吃光，
     * 调用方只拿到空正文却不报错。未指定时兜一个够用的值。
     */
    private fun buildBody(request: ChatRequest): ByteArray = buildJsonObject {
        put("model", request.model)
        put("stream", request.stream)
        put("max_tokens", request.maxTokens ?: DEFAULT_MAX_TOKENS)
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
                                    put("function", buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    })
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
        if (request.stop.isNotEmpty()) {
            putJsonArray("stop") { request.stop.forEach { add(it) } }
        }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                for (tool in request.tools) {
                    addJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", json.parseToJsonElement(tool.parametersJson))
                        })
                    }
                }
            }
        }
        request.toolChoice?.let { put("tool_choice", it) }
        if (request.jsonMode) {
            put("response_format", buildJsonObject { put("type", "json_object") })
        }
    }.toString().encodeToByteArray()

    private fun roleName(role: ChatMessage.Role): String = when (role) {
        ChatMessage.Role.System -> "system"
        ChatMessage.Role.User -> "user"
        ChatMessage.Role.Assistant -> "assistant"
        ChatMessage.Role.Tool -> "tool"
    }

    private fun parseSse(lines: Sequence<String>, model: String): Sequence<ChatChunk> = sequence {
        var id = "kilo-${System.currentTimeMillis()}"
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(":")) continue
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            if (payload.isEmpty()) continue

            val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
                ?: continue
            obj.text("id").takeIf { it.isNotBlank() }?.let { id = it }

            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            val delta = choice?.get("delta") as? JsonObject
            val text = delta?.text("content")?.takeIf { it.isNotEmpty() }
            val toolDeltas = (delta?.get("tool_calls") as? JsonArray).orEmpty().mapNotNull { element ->
                val call = element as? JsonObject ?: return@mapNotNull null
                val function = call["function"] as? JsonObject
                ToolCallDelta(
                    index = call.int("index") ?: 0,
                    id = call.text("id").takeIf { it.isNotBlank() },
                    name = function?.text("name")?.takeIf { it.isNotBlank() },
                    argumentsDelta = function?.text("arguments")?.takeIf { it.isNotEmpty() },
                )
            }

            yield(
                ChatChunk(
                    id = id,
                    model = model,
                    index = choice?.int("index") ?: 0,
                    deltaText = text,
                    deltaToolCalls = toolDeltas,
                    role = delta?.text("role")?.let(::roleOf),
                    finishReason = choice?.text("finish_reason")?.takeIf { it.isNotBlank() },
                    usage = parseUsage(obj["usage"]),
                ),
            )
        }
    }

    private fun parseCompletion(body: String, model: String): ChatResponse {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw ProviderException("$displayName 响应不是合法 JSON")
        val id = root.text("id").ifBlank { "kilo-${System.currentTimeMillis()}" }
        val choices = (root["choices"] as? JsonArray).orEmpty().mapIndexed { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexed ChatResponse.Choice(index, emptyMessage())
            ChatResponse.Choice(
                index = obj.int("index") ?: index,
                message = parseMessage(obj["message"] as? JsonObject),
                finishReason = obj.text("finish_reason").takeIf { it.isNotBlank() },
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
        val text = obj.text("content")
        val toolCalls = (obj["tool_calls"] as? JsonArray).orEmpty().mapNotNull { element ->
            val call = element as? JsonObject ?: return@mapNotNull null
            val function = call["function"] as? JsonObject ?: return@mapNotNull null
            ToolCall(
                id = call.text("id"),
                name = function.text("name"),
                arguments = function.text("arguments").ifBlank { "{}" },
            )
        }
        return ChatMessage(
            role = ChatMessage.Role.Assistant,
            content = if (text.isEmpty()) emptyList() else listOf(ContentPart.Text(text)),
            toolCalls = toolCalls,
        )
    }

    private fun aggregate(lines: Sequence<String>, model: String): ChatResponse {
        val text = StringBuilder()
        val toolCalls = linkedMapOf<Int, ToolCallBuilder>()
        var id = "kilo-${System.currentTimeMillis()}"
        var finishReason: String? = null
        var usage: Usage? = null

        for (chunk in parseSse(lines, model)) {
            id = chunk.id
            chunk.deltaText?.let(text::append)
            for (delta in chunk.deltaToolCalls) {
                val builder = toolCalls.getOrPut(delta.index) { ToolCallBuilder() }
                delta.id?.let { builder.id = it }
                delta.name?.let { builder.name = it }
                delta.argumentsDelta?.let(builder.arguments::append)
            }
            chunk.finishReason?.let { finishReason = it }
            chunk.usage?.let { usage = it }
        }

        val message = ChatMessage(
            role = ChatMessage.Role.Assistant,
            content = if (text.isEmpty()) emptyList() else listOf(ContentPart.Text(text.toString())),
            toolCalls = toolCalls.entries.sortedBy { it.key }.mapNotNull { (_, builder) ->
                if (builder.name.isBlank()) return@mapNotNull null
                ToolCall(
                    id = builder.id.ifBlank { "call_${builder.name}" },
                    name = builder.name,
                    arguments = builder.arguments.toString().ifBlank { "{}" },
                )
            },
        )
        return ChatResponse(
            id = id,
            model = model,
            choices = listOf(ChatResponse.Choice(0, message, finishReason)),
            usage = usage,
        )
    }

    private fun parseUsage(element: JsonElement?): Usage? {
        val obj = element as? JsonObject ?: return null
        val prompt = obj.int("prompt_tokens") ?: return null
        val completion = obj.int("completion_tokens") ?: 0
        val details = obj.obj("prompt_tokens_details")
        return Usage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = obj.int("total_tokens") ?: (prompt + completion),
            cachedTokens = details?.int("cached_tokens") ?: 0,
        )
    }

    private fun roleOf(name: String): ChatMessage.Role? = when (name.lowercase()) {
        "system" -> ChatMessage.Role.System
        "user" -> ChatMessage.Role.User
        "assistant" -> ChatMessage.Role.Assistant
        "tool" -> ChatMessage.Role.Tool
        else -> null
    }

    private fun mapStatus(status: Int): Int = when (status) {
        401, 403 -> 401
        404 -> 404
        429 -> 429
        else -> 502
    }

    private class ToolCallBuilder {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    companion object {
        /** 网关基址。注意对话路径是 `/api/gateway/v1/...` 而非 `/v1/...`。 */
        const val GATEWAY_BASE = "https://kilo.ai"

        /**
         * 未指定 `max_tokens` 时的兜底值。
         *
         * 这些模型都带推理，预算太小会让思维链吃光输出、正文为空。
         * 实测 100 时多数模型返回空正文，2000 时全部正常。
         */
        const val DEFAULT_MAX_TOKENS = 2048
    }
}

private fun JsonObject?.text(key: String): String =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject?.int(key: String): Int? =
    (this?.get(key) as? JsonPrimitive)?.intOrNull

private fun JsonObject?.long(key: String): Long? =
    (this?.get(key) as? JsonPrimitive)?.longOrNull

private fun JsonObject?.boolean(key: String): Boolean =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.equals("true", ignoreCase = true) == true

private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
