package io.modelbridge.providers.opencode

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
 * OpenCode 推理渠道（`opencode`）。
 *
 * 匿名访问用 `Authorization: Bearer public`。免费层按**请求内容**判定是否放行，
 * 不看请求头——实测中 `x-opencode-*` 会话头对结果没有任何影响，故不再发送。
 *
 * 免费层需要同时满足三个条件（见 [OpenCodeGate] 与 [OpenCodeTls]）：
 *
 * 1. 系统提示词含 `<env>` 块，且块内有 `Working directory:` 一行；
 * 2. 声明官方 `read` 工具，描述与参数 schema 都要匹配（只声明 `bash` 会被拒）；
 * 3. TLS ClientHello 提供官方 CLI 的 17 个加密套件。
 *
 * 前两项由 [OpenCodeGate] 在请求体上完成。第三项在传输层，JVM 内难以精确复现
 * （JDK 默认禁用其中的 CBC-SHA 套件），因此本 provider 需要一个能控制 TLS 的
 * 本地转发层；见 [OpenCodeTls] 的说明。
 *
 * 凭据两种形态：
 * - [Credential.None]：匿名，只能调 `-free` 结尾的免费模型；
 * - [Credential.ApiKey]：带 key，可调全部模型。
 *
 * 注意 `FreeUsageLimitError` 不等于配额耗尽——门禁拒绝也返回它。
 */
class OpenCodeProvider : Provider {

    override val id: String = "opencode"

    override val displayName: String = "OpenCode"

    /** 匿名即可用，所以是 None；填了 key 就走 ApiKey。 */
    override val authKind: AuthKind get() = AuthKind.None

    override val requiresCredential: Boolean get() = false

    override val loginUrl: String? get() = "https://opencode.ai"

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

    /**
     * 请求头。
     *
     * 只保留真正影响结果的几项：鉴权、UA、协议类型。会话头已确认无效，不再发送。
     */
    private fun headers(context: ProviderContext): Map<String, String> {
        val key = apiKey(context)
        return linkedMapOf(
            // 匿名时官方 CLI 就是发 "Bearer public"
            "Authorization" to "Bearer ${key.ifBlank { "public" }}",
            "User-Agent" to USER_AGENT,
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/event-stream",
        )
    }

    private fun baseUrl(context: ProviderContext): String =
        context.baseUrl.ifBlank { INFERENCE_BASE }

    /** 免费模型是否走门禁适配：带 key 且非免费模型时不改写。 */
    private fun needsGate(context: ProviderContext, model: String): Boolean =
        OpenCodeGate.needsAdaptation(apiKey(context), model)

    /**
     * 拉取模型列表。
     *
     * 返回的 `limit` / `capabilities` 结构比 OpenAI 的 models 丰富，
     * 这里把上下文窗口、工具、推理能力都映射出来。
     */
    override suspend fun listModels(context: ProviderContext): List<ModelInfo> {
        val response = context.http.execute(
            HttpRequest(
                method = "GET",
                url = baseUrl(context) + "/v1/models",
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
            .getOrNull() as? JsonObject
            ?: throw ProviderException("$displayName 模型响应不是合法 JSON")
        val data = (root["data"] as? JsonArray).orEmpty()

        val models = data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val modelId = obj.text("id")
            if (modelId.isBlank()) return@mapNotNull null
            val capabilities = obj["capabilities"] as? JsonObject
            val limit = obj["limit"] as? JsonObject
            ModelInfo(
                id = modelId,
                providerId = id,
                displayName = obj.text("name").ifBlank { modelId },
                contextLength = limit?.long("context") ?: 0,
                supportsTools = capabilities?.capability("tools") ?: false,
                supportsReasoning = capabilities?.capability("reasoning") ?: false,
                extras = buildMap {
                    limit?.long("output")?.takeIf { it > 0 }?.let { put("max_output_tokens", "$it") }
                    obj.text("family").takeIf { it.isNotBlank() }?.let { put("family", it) }
                    // 免费模型不需要 API Key，标出来方便客户端过滤
                    if (modelId.endsWith("-free")) put("free", "true")
                },
            )
        }
        if (models.isEmpty()) throw ProviderException("$displayName 没有可用的模型")
        return models
    }

    override suspend fun chat(context: ProviderContext, request: ChatRequest): ChatResult {
        // 免费层按内容判定：先注入 env 块、补官方 read 声明、换掉被拒的工具名
        val adapted = if (needsGate(context, request.model)) {
            OpenCodeGate.adapt(request)
        } else {
            request
        }
        val payload = buildBody(adapted)
        val response = context.http.execute(
            HttpRequest(
                method = "POST",
                url = baseUrl(context) + "/v1/chat/completions",
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
        // 上游可能回 SSE，也可能直接回完整 JSON
        return if (contentType.contains("text/event-stream", ignoreCase = true)) {
            if (adapted.stream) {
                ChatResult.Stream(parseSse(response.bodyLines(), adapted.model))
            } else {
                ChatResult.Complete(aggregate(response.bodyLines(), adapted.model))
            }
        } else {
            val body = response.bodyBytes().decodeToString()
            ChatResult.Complete(parseCompletion(body, adapted.model))
        }
    }

    /** 把上游错误翻成可读信息，保留原始错误类型便于排查。 */
    private fun describeError(status: Int, detail: String): String {
        val root = runCatching { json.parseToJsonElement(detail).jsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        val type = error?.text("type") ?: root?.text("type")
        val message = error?.text("message") ?: root?.text("message")
        val hint = when {
            // 这个错误码不能断定为配额耗尽：门禁拒绝也返回它
            type == "FreeUsageLimitError" -> "免费层拒绝：额度可能已用尽，也可能未通过内容门禁"
            type == "AuthError" -> "该模型需要 API Key"
            type == "authentication_error" -> "凭据无效"
            status == 401 -> "凭据无效或该模型需要 API Key"
            status == 429 -> "额度或频率受限"
            else -> ""
        }
        return buildString {
            append(displayName).append(" 调用失败（HTTP ").append(status)
            if (!type.isNullOrBlank()) append("，type=").append(type)
            append("）")
            if (!message.isNullOrBlank()) append("：").append(message.take(200))
            if (hint.isNotBlank()) append("（").append(hint).append("）")
        }
    }

    /** 官方 CLI 的请求体：恒为流式，并要求返回用量。 */
    private fun buildBody(request: ChatRequest): ByteArray = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        put("stream_options", buildJsonObject { put("include_usage", true) })
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
        request.maxTokens?.let { put("max_tokens", it) }
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

    /** 解析 SSE 增量。 */
    private fun parseSse(lines: Sequence<String>, model: String): Sequence<ChatChunk> = sequence {
        var id = "chatcmpl-oc-${System.currentTimeMillis()}"
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

    /** 非流式响应。 */
    private fun parseCompletion(body: String, model: String): ChatResponse {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw ProviderException("$displayName 响应不是合法 JSON")
        val id = root.text("id").ifBlank { "chatcmpl-oc-${System.currentTimeMillis()}" }
        val choices = (root["choices"] as? JsonArray).orEmpty().mapIndexed { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexed ChatResponse.Choice(index, emptyMessage())
            val message = obj["message"] as? JsonObject
            ChatResponse.Choice(
                index = obj.int("index") ?: index,
                message = parseMessage(message),
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
                // 门禁会把两个工具名换成别名，这里翻回宿主工具名
                name = OpenCodeGate.restoreToolName(function.text("name")),
                arguments = function.text("arguments").ifBlank { "{}" },
            )
        }
        return ChatMessage(
            role = ChatMessage.Role.Assistant,
            content = if (text.isEmpty()) emptyList() else listOf(ContentPart.Text(text)),
            toolCalls = toolCalls,
        )
    }

    /** 把 SSE 聚合成完整响应。 */
    private fun aggregate(lines: Sequence<String>, model: String): ChatResponse {
        val text = StringBuilder()
        val toolCalls = linkedMapOf<Int, ToolCallBuilder>()
        var id = "chatcmpl-oc-${System.currentTimeMillis()}"
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
                    name = OpenCodeGate.restoreToolName(builder.name),
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
        return Usage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = obj.int("total_tokens") ?: (prompt + completion),
            cachedTokens = obj.obj("prompt_tokens_details")?.int("cached_tokens") ?: 0,
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
        /** 推理端点基址。 */
        const val INFERENCE_BASE = "https://opencode.ai/inference"

        /** 官方 CLI 的 UA 形态。 */
        const val USER_AGENT = "opencode/latest/2.0.29/cli"
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

/**
 * 读取 `capabilities.<key>.supported`。
 *
 * 上游把能力写成嵌套对象 `{"tools":{"supported":true}}`，不是直接的布尔值；
 * 同时兼容个别条目直接给布尔值的写法。
 */
private fun JsonObject?.capability(key: String): Boolean {
    val value = this?.get(key) ?: return false
    if (value is JsonPrimitive) {
        return value.contentOrNull?.equals("true", ignoreCase = true) == true
    }
    return (value as? JsonObject)?.boolean("supported") ?: false
}

private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
