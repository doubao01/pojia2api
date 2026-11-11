package io.modelbridge.providers.workbuddy

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
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * WorkBuddy / CodeBuddy 官方桌面端接入（`workbuddy`）。
 *
 * 与 [io.modelbridge.providers.builtin.CodeBuddyProvider] 的区别：后者把上游当
 * 普通 OpenAI 兼容接口调，只有 Authorization；本类复刻官方桌面端的完整出站形态，
 * 补上渠道校验要求的会话头族与身份头。
 *
 * 上游要求（实测）：
 * - 必须流式，非流式直接回 `11101 Non-stream chat request is currently not supported`；
 * - 请求体 gzip 压缩并带 `Content-Encoding: gzip`；
 * - 会话头族缺一个就会被判为非法请求。
 *
 * 错误码含义：
 * - 401：token 失效，需重新授权；
 * - 403 + 11140：账号被风控封禁，改请求头或换身份都不解除；
 * - 429 + 14018：额度用尽。
 */
class WorkBuddyProvider : Provider {

    override val id: String = "workbuddy"

    override val displayName: String = "WorkBuddy 桌面端"

    override val authKind: AuthKind get() = AuthKind.OAuth

    override val loginUrl: String? get() = "https://www.codebuddy.cn/login"

    override val cookieDomains: List<String> get() = listOf("www.codebuddy.cn", "codebuddy.cn")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
    }

    /** 会话 id 按账号缓存：官方客户端同一会话内恒定，每请求现生成是最明显的非人类特征。 */
    private val conversations = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun chatBase(context: ProviderContext): String =
        if (WorkBuddyIdentity.isGlobal(context.region, context.accountDomain)) {
            WorkBuddyIdentity.CHAT_BASE_GLOBAL
        } else {
            WorkBuddyIdentity.CHAT_BASE_CN
        }

    private fun billBase(context: ProviderContext): String =
        if (WorkBuddyIdentity.isGlobal(context.region, context.accountDomain)) {
            WorkBuddyIdentity.CHAT_BASE_GLOBAL
        } else {
            WorkBuddyIdentity.BILL_BASE_CN
        }

    private fun domain(context: ProviderContext): String =
        if (WorkBuddyIdentity.isGlobal(context.region, context.accountDomain)) {
            WorkBuddyIdentity.DOMAIN_GLOBAL
        } else {
            WorkBuddyIdentity.DOMAIN_CN
        }

    private fun token(context: ProviderContext): String {
        val credential = context.credential as? Credential.OAuth
            ?: throw ProviderException("WorkBuddy 需要 OAuth 凭据", statusCode = 401)
        if (credential.accessToken.isBlank()) {
            throw ProviderException("WorkBuddy 凭据缺少 accessToken", statusCode = 401)
        }
        return credential.accessToken
    }

    /** 会话 id：按账号稳定复用，避免每次请求都变成一次性会话。 */
    private fun conversationId(context: ProviderContext): String {
        val key = context.accountUid.ifBlank { token(context).take(16) }
        return conversations.computeIfAbsent(key) { WorkBuddyIdentity.uuid7() }
    }

    /** 官方桌面端 chat 请求头。 */
    private fun chatHeaders(context: ProviderContext, conversationId: String): Map<String, String> {
        val turnRequestId = WorkBuddyIdentity.messageId()
        val messageId = WorkBuddyIdentity.messageId()
        val headers = linkedMapOf(
            "X-Agent-Intent" to "craft",
            "X-Agent-Purpose" to "conversation",
            "X-Agent-Type" to "main",
            "X-IDE-Name" to "WorkBuddy",
            "X-IDE-Type" to "WorkBuddy",
            "X-IDE-Version" to WorkBuddyIdentity.DESKTOP_VERSION_CN,
            "X-Private-Data" to if (context.tenantId.isNotBlank()) "true" else "false",
            "X-Product" to "SaaS",
            "X-Domain" to domain(context),
            "User-Agent" to WorkBuddyIdentity.DESKTOP_UA_CN,
            "X-User-Id" to context.accountUid.ifBlank { "anonymous" },
            "Authorization" to "Bearer ${token(context)}",
            "X-Conversation-ID" to conversationId,
            "X-Conversation-Request-ID" to turnRequestId,
            "X-Root-Request-ID" to turnRequestId,
            "X-Conversation-Message-ID" to messageId,
            "X-Request-ID" to messageId,
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to billBase(context),
            "Referer" to billBase(context) + "/",
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
        headers.putAll(WorkBuddyIdentity.traceHeaders(withParent = true))
        if (context.tenantId.isNotBlank()) {
            headers["X-Enterprise-Id"] = context.tenantId
            headers["X-Tenant-Id"] = context.tenantId
        }
        return headers
    }

    /** 计费类请求头（额度、签到、任务）。 */
    private fun billingHeaders(context: ProviderContext): Map<String, String> {
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to WorkBuddyIdentity.DESKTOP_UA_CN,
            "Origin" to billBase(context),
            "Referer" to billBase(context) + "/",
            "Authorization" to "Bearer ${token(context)}",
            "X-User-Id" to context.accountUid.ifBlank { "anonymous" },
            "X-Domain" to domain(context),
            "X-Request-ID" to WorkBuddyIdentity.messageId(),
            "X-Machine-ID" to WorkBuddyIdentity.machineId(context.accountUid),
            "X-Session-ID" to WorkBuddyIdentity.sessionId(context.accountUid),
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
        if (context.tenantId.isNotBlank()) {
            headers["X-Enterprise-Id"] = context.tenantId
            headers["X-Tenant-Id"] = context.tenantId
        }
        return headers
    }

    /**
     * 拉取模型列表。
     *
     * 走官方控制台的 `personal/models`，返回 `data.models`；不可用时回退到
     * 配置里的 `data.agents[].models` 汇总，避免一个端点抖动就整家不可用。
     */
    override suspend fun listModels(context: ProviderContext): List<ModelInfo> {
        val response = context.http.execute(
            HttpRequest(
                method = "GET",
                url = billBase(context) + "/console/enterprises/personal/models",
                headers = billingHeaders(context),
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
        if (root.long("code") != 0L) {
            throw ProviderException(
                message = "$displayName 模型接口返回异常（code=${root.long("code")}）",
                statusCode = 502,
            )
        }
        val data = root["data"] as? JsonObject
        val models = (data?.get("models") as? JsonArray).orEmpty()

        val parsed = models.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val modelId = obj.text("id")
            if (modelId.isBlank()) return@mapNotNull null
            ModelInfo(
                id = modelId,
                providerId = id,
                displayName = obj.text("name").ifBlank { modelId },
                contextLength = obj.obj("contextWindow")?.long("defaultLength")
                    ?: obj.long("maxInputTokens") ?: 0,
                supportsTools = obj.boolean("supportsToolCall"),
                supportsReasoning = obj.boolean("supportsReasoning"),
                extras = buildMap {
                    obj.text("vendor").takeIf { it.isNotBlank() }?.let { put("vendor", it) }
                    obj.long("maxOutputTokens")?.takeIf { it > 0 }?.let { put("max_output_tokens", "$it") }
                },
            )
        }
        if (parsed.isNotEmpty()) return parsed

        // 回退：从 agents[].models 汇总
        val agents = (data?.get("agents") as? JsonArray).orEmpty()
        val ids = linkedSetOf<String>()
        for (element in agents) {
            val agent = element as? JsonObject ?: continue
            for (modelElement in (agent["models"] as? JsonArray).orEmpty()) {
                (modelElement as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let(ids::add)
            }
        }
        if (ids.isEmpty()) throw ProviderException("$displayName 没有可用的模型")
        return ids.map { ModelInfo(id = it, providerId = id) }
    }

    override suspend fun chat(context: ProviderContext, request: ChatRequest): ChatResult {
        val conversationId = conversationId(context)
        val payload = buildBody(request)
        val response = context.http.execute(
            HttpRequest(
                method = "POST",
                url = chatBase(context) + "/v2/chat/completions",
                headers = chatHeaders(context, conversationId) + mapOf("Content-Encoding" to "gzip"),
                body = gzip(payload),
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

        // 上游只支持流式，非流式请求也要把 SSE 聚合成完整响应
        return if (request.stream) {
            ChatResult.Stream(parseSse(response.bodyLines(), request.model))
        } else {
            ChatResult.Complete(aggregate(response.bodyLines(), request.model))
        }
    }

    /** 把上游错误体翻成可读信息，保留上游原始错误码便于排查。 */
    private fun describeError(status: Int, detail: String): String {
        val root = runCatching { json.parseToJsonElement(detail).jsonObject }.getOrNull()
        val upstreamCode = root?.obj("error")?.obj("data")?.long("code")
            ?: root?.long("code")
        val message = root?.obj("error")?.obj("data")?.text("msg")
            ?: root?.obj("displayMsg")?.text("zh")
            ?: root?.text("msg")
        val hint = when {
            status == 401 -> "凭据失效，需重新授权"
            upstreamCode == 11140L -> "账号被风控封禁"
            upstreamCode == 14018L -> "额度用尽"
            upstreamCode == 11101L -> "请求被拒（非流式等）"
            else -> ""
        }
        return buildString {
            append(displayName).append(" 调用失败（HTTP ").append(status)
            if (upstreamCode != null) append("，code=").append(upstreamCode)
            append("）")
            if (!message.isNullOrBlank()) append("：").append(message.take(200))
            if (hint.isNotBlank()) append("（").append(hint).append("）")
        }
    }

    /**
     * 构造请求体。
     *
     * `stream` 恒为 true：上游对非流式直接回 `11101`，所以无论客户端要不要流式，
     * 发给上游的永远是流式，非流式由 [aggregate] 在本地聚合。
     */
    private fun buildBody(request: ChatRequest): ByteArray = buildJsonObject {
        put("model", request.model)
        put("stream", true)
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

    /** 解析 SSE 增量。容忍 `data:{...}`、心跳注释与空 delta。 */
    private fun parseSse(lines: Sequence<String>, model: String): Sequence<ChatChunk> = sequence {
        var id = "wb-${System.currentTimeMillis()}"
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(":")) continue
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            if (payload.isEmpty()) continue

            val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
                ?: continue
            obj["id"]?.jsonPrimitive?.contentOrNull?.let { id = it }

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

    /** 把 SSE 聚合成完整响应，供非流式客户端使用。 */
    private fun aggregate(lines: Sequence<String>, model: String): ChatResponse {
        val text = StringBuilder()
        val toolCalls = linkedMapOf<Int, ToolCallBuilder>()
        var id = "wb-${System.currentTimeMillis()}"
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

    /** 上游要求 gzip；Content-Length 交给 HTTP 客户端按压缩后字节计算。 */
    private fun gzip(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size)
        GZIPOutputStream(out).use { it.write(payload) }
        return out.toByteArray()
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
}

/** 便捷取值：字段缺失或为 null 时返回空串。 */
private fun JsonObject?.text(key: String): String =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject?.int(key: String): Int? =
    (this?.get(key) as? JsonPrimitive)?.intOrNull

private fun JsonObject?.long(key: String): Long? =
    (this?.get(key) as? JsonPrimitive)?.longOrNull

private fun JsonObject?.boolean(key: String): Boolean =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.equals("true", ignoreCase = true) == true

private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
