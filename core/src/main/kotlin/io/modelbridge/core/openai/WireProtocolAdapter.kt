package io.modelbridge.core.openai

import io.modelbridge.spi.ChatMessage
import io.modelbridge.spi.ChatRequest
import io.modelbridge.spi.ChatResult
import io.modelbridge.spi.ContentPart
import io.modelbridge.spi.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** 3.10.0 dex 中并列开放的三个 HTTP 对话协议入口。 */
enum class WireProtocol {
    Chat,
    Responses,
    Messages,
}

/**
 * `/chat/completions`、`/responses`、`/messages` 与内核统一 ChatRequest 的转换。
 *
 * 只覆盖 3.10.0 静态字符串和常见协议字段；无法识别的内容按文本块提取，
 * 解析失败由 Gateway 转成 400，不伪造响应。
 */
object WireProtocolAdapter {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun decodeChatRequest(body: String, protocol: WireProtocol): ChatRequest {
        val root = parseObject(body)
        validateProtocolRequest(root, protocol)
        return when (protocol) {
            WireProtocol.Chat -> Mapper.toChatRequest(
                json.decodeFromString(ChatCompletionRequest.serializer(), body),
            )
            WireProtocol.Responses -> decodeResponses(root)
            WireProtocol.Messages -> decodeMessages(root)
        }
    }

    private fun validateMessages(messages: JsonArray, protocol: WireProtocol) {
        if (messages.isEmpty()) throw IllegalArgumentException("messages 必须是非空数组")
        val pendingToolIds = mutableSetOf<String>()
        val seenToolIds = mutableSetOf<String>()
        messages.forEachIndexed { index, element ->
            val message = element as? JsonObject ?: throw IllegalArgumentException("messages[$index] 必须是对象")
            val role = message["role"].primitiveText().orEmpty().lowercase()
            if (role !in setOf("system", "developer", "user", "assistant", "tool", "function", "model")) {
                throw IllegalArgumentException("不支持的消息角色: $role")
            }
            val content = message["content"]
            if (content != null && content !is JsonNull && content !is JsonPrimitive && content !is JsonArray) {
                throw IllegalArgumentException("消息 content 必须是字符串、数组或 null")
            }
            if (content is JsonArray) {
                content.forEach { block ->
                    if (block !is JsonObject && block !is JsonPrimitive) {
                        throw IllegalArgumentException("消息 content block 必须是对象或字符串")
                    }
                }
            }
            val toolCalls = message["tool_calls"] as? JsonArray
            if (message.containsKey("tool_calls") && toolCalls == null) {
                throw IllegalArgumentException("assistant.tool_calls 必须是数组")
            }
            if (role == "user" && (toolCalls != null || message.containsKey("tool_call_id"))) {
                throw IllegalArgumentException("] 的 user 不允许携带工具字段")
            }
            if (role == "assistant" && toolCalls != null && content is JsonArray &&
                content.any { it is JsonObject }
            ) {
                throw IllegalArgumentException("] 的 assistant 只允许文本与 tool_calls")
            }
            toolCalls?.forEach { call ->
                val obj = call as? JsonObject ?: throw IllegalArgumentException("tool_call 必须是对象")
                if (obj["type"].primitiveText() != "function") {
                    throw IllegalArgumentException("] 只支持 type=function")
                }
                val id = obj["id"].primitiveText()
                    ?: throw IllegalArgumentException("tool_call 缺少 id")
                if (!seenToolIds.add(id)) {
                    throw IllegalArgumentException("] 重复的 tool_call_id: $id")
                }
                val function = obj["function"] as? JsonObject
                    ?: throw IllegalArgumentException("tool_call 缺少 function.")
                if (function["name"].primitiveText().isNullOrBlank()) {
                    throw IllegalArgumentException("tool_call 缺少 function.name")
                }
                val arguments = function["arguments"] as? JsonPrimitive
                    ?: throw IllegalArgumentException("].function.arguments 必须是 JSON 对象字符串")
                runCatching { json.parseToJsonElement(arguments.content).jsonObject }
                    .getOrElse { throw IllegalArgumentException("].function.arguments 必须是 JSON 对象") }
                pendingToolIds += id
            }
            if (role == "tool" || role == "function") {
                val callId = message["tool_call_id"].primitiveText()
                    ?: throw IllegalArgumentException("] tool_call_id 缺失")
                if (callId !in pendingToolIds) {
                    throw IllegalArgumentException("] 的 tool_call_id 没有待响应的 assistant tool_call")
                }
                pendingToolIds -= callId
            }
        }
        if (protocol == WireProtocol.Messages && messages.all { element ->
                (element as? JsonObject)?.get("role").primitiveText()?.lowercase() in setOf("system", "developer")
            }
        ) {
            throw IllegalArgumentException("system/developer 之外至少需要一条对话消息")
        }
    }

    /**
     * 3.10.0 dex 列出了三个协议的请求校验错误与扩展参数取值限制。
     * 这里只校验本地转换即可确定的字段；tools/tool_calls 的完整会话语义仍由上游决定。
     */
    private fun validateProtocolRequest(root: JsonObject, protocol: WireProtocol) {
        val messages = when (protocol) {
            WireProtocol.Chat -> root["messages"] as? JsonArray
            WireProtocol.Messages -> root["messages"] as? JsonArray
            WireProtocol.Responses -> {
                val input = root["input"]
                if (input is JsonArray && input.all { it is JsonObject }) input else null
            }
        }
        messages?.let { validateMessages(it, protocol) }
        root["tools"]?.let { tools ->
            if (tools !is JsonArray) throw IllegalArgumentException("tools 必须是数组")
            tools.forEach { tool ->
                val obj = tool as? JsonObject ?: throw IllegalArgumentException("tool 必须是对象")
                val function = obj["function"] as? JsonObject
                    ?: throw IllegalArgumentException("tool.function 必须是对象")
                if (obj["type"].primitiveText() != "function") {
                    throw IllegalArgumentException("] 只支持 type=function")
                }
                if (function["name"].primitiveText().isNullOrBlank()) {
                    throw IllegalArgumentException("].function.name 无效")
                }
                function["parameters"]?.let { parameters ->
                    if (parameters !is JsonObject) {
                        throw IllegalArgumentException("].function.parameters 必须是 JSON Schema 对象")
                    }
                }
                function["strict"]?.let { strict ->
                    if (strict !is JsonPrimitive || strict.contentOrNull?.toBooleanStrictOrNull() == null) {
                        throw IllegalArgumentException("].function.strict 必须是布尔值")
                    }
                    if (strict.contentOrNull?.toBooleanStrictOrNull() == true) {
                        throw IllegalArgumentException("].function.strict=true 暂不支持")
                    }
                }
            }
        }
        if (protocol == WireProtocol.Chat) {
            root["tool_choice"]?.let { choice ->
                if (choice is JsonObject) {
                    val type = choice["type"].primitiveText()
                    if (type == "function") {
                        val function = choice["function"] as? JsonObject
                        val name = function?.get("name").primitiveText()
                        if (name == null) throw IllegalArgumentException("tool_choice 指定的工具不存在")
                    }
                }
            }
        } else {
            val reasoningEffort = root["reasoning_effort"].primitiveText()
            ?: (root["reasoning"] as? JsonObject)?.get("effort").primitiveText()
            if (reasoningEffort != null && reasoningEffort !in setOf("low", "high", "max")) {
                throw IllegalArgumentException("reasoning_effort 仅支持 low / high / max")
            }
            root["parallel_tool_calls"]?.let { value ->
                if (value !is JsonPrimitive || value.contentOrNull?.toBooleanStrictOrNull() == null) {
                    throw IllegalArgumentException("parallel_tool_calls 必须是布尔值")
                }
            }
            root["tool_choice"]?.let { choice ->
                val name = when (choice) {
                    is JsonPrimitive -> choice.contentOrNull
                    is JsonObject -> {
                        if (choice.keys.any { it !in setOf("type", "function", "name") }) {
                            throw IllegalArgumentException("tool_choice 包含不允许的字段")
                        }
                        (choice["name"] ?: choice["function"]).toolChoice()
                    }
                    else -> throw IllegalArgumentException("tool_choice 只支持 auto、none、required 或指定 function")
                }
                if (name.isNullOrBlank() || (name !in setOf("auto", "none", "required") && choice !is JsonObject)) {
                    throw IllegalArgumentException("tool_choice 只支持 auto、none、required 或指定 function")
                }
            }
        }
        if (protocol == WireProtocol.Messages) {
            root["context_window"]?.let {
                throw IllegalArgumentException("不支持 context_window 参数")
            }
        } else {
            val window = root["context_window"].primitiveText()
            if (window != null && window !in setOf("200K", "500K", "1M")) {
                throw IllegalArgumentException("context_window 仅支持 200K / 500K / 1M")
            }
        }
    }

    private fun decodeResponses(root: JsonObject): ChatRequest {
        val messages = mutableListOf<ChatMessage>()
        val instructions = root["instructions"].text()
        if (instructions.isNotBlank()) messages += system(instructions)

        when (val input = root["input"]) {
            is JsonPrimitive -> messages += user(input.contentOrNull.orEmpty())
            is JsonArray -> input.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val role = obj["role"].text().ifBlank { "user" }
                val text = obj["content"].text()
                messages += message(role, text)
            }
            is JsonObject -> messages += user(input.text())
            null -> Unit
            else -> Unit
        }
        if (messages.none { it.role != ChatMessage.Role.System }) {
            throw IllegalArgumentException("responses.input 没有可用消息")
        }
        return ChatRequest(
            model = root["model"].text(),
            messages = messages,
            temperature = root.double("temperature"),
            topP = root.double("top_p"),
            maxTokens = root.int("max_output_tokens", "max_tokens"),
            stream = root.boolean("stream"),
            jsonMode = root["text"] != null,
            toolChoice = root["tool_choice"].toolChoice(),
            extras = root.protocolExtras(
                "reasoning_effort" to listOf("reasoning_effort"),
                "context_window" to listOf("context_window"),
                "parallel_tool_calls" to listOf("parallel_tool_calls"),
                "top_k" to listOf("top_k"),
                "metadata" to listOf("metadata"),
            ) + reasoningEffort(root),
        )
    }

    private fun decodeMessages(root: JsonObject): ChatRequest {
        val messages = mutableListOf<ChatMessage>()
        val systemText = root["system"].multiText()
        if (systemText.isNotBlank()) messages += system(systemText)
        val input = root["messages"] as? JsonArray
            ?: throw IllegalArgumentException("messages 不是数组")
        input.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            messages += message(
                obj["role"].text().ifBlank { "user" },
                obj["content"].multiText(),
            )
        }
        if (messages.none { it.role != ChatMessage.Role.System }) {
            throw IllegalArgumentException("messages 中没有对话消息")
        }
        return ChatRequest(
            model = root["model"].text(),
            messages = messages,
            temperature = root.double("temperature"),
            topP = root.double("top_p"),
            maxTokens = root.int("max_tokens", "max_output_tokens"),
            stop = root.stringList("stop_sequences"),
            stream = root.boolean("stream"),
            toolChoice = root["tool_choice"].toolChoice(),
            extras = root.protocolExtras(
                "top_k" to listOf("top_k"),
                "thinking" to listOf("thinking"),
                "reasoning_effort" to listOf("reasoning_effort"),
                "metadata" to listOf("metadata"),
            ),
        )
    }

    fun encodeComplete(
        protocol: WireProtocol,
        id: String,
        model: String,
        created: Long,
        result: ChatResult.Complete,
    ): String {
        val response = result.response
        val text = response.choices.firstOrNull()?.message?.content
            ?.filterIsInstance<ContentPart.Text>()
            ?.joinToString("") { it.text }
            .orEmpty()
        val finish = response.choices.firstOrNull()?.finishReason
        return when (protocol) {
            WireProtocol.Chat -> json.encodeToString(
                ChatCompletionResponse.serializer(),
                ChatCompletionResponse(
                    id = id.ifBlank { "chatcmpl-${UUID.randomUUID()}" },
                    created = created,
                    model = model,
                    choices = response.choices.map { choice ->
                        ChatCompletionResponse.WireChoice(
                            index = choice.index,
                            message = Mapper.toWireMessage(choice.message),
                            finishReason = choice.finishReason,
                        )
                    },
                    usage = response.usage?.let { WireUsage(it.promptTokens, it.completionTokens, it.totalTokens) },
                ),
            )
            WireProtocol.Responses -> buildJsonObject {
                put("id", id.ifBlank { "resp_${UUID.randomUUID()}" })
                put("object", "response")
                put("created_at", created)
                put("model", model)
                put("status", "completed")
                put("output", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "msg_${UUID.randomUUID()}")
                        put("type", "message")
                        put("role", "assistant")
                        put("status", "completed")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "output_text")
                                put("text", text)
                                put("annotations", buildJsonArray {})
                            })
                        })
                    })
                })
                response.usage?.let { usage ->
                    put("usage", buildJsonObject {
                        put("input_tokens", usage.promptTokens)
                        put("output_tokens", usage.completionTokens)
                        put("total_tokens", usage.totalTokens)
                    })
                }
            }.toString()
            WireProtocol.Messages -> buildJsonObject {
                put("id", id.ifBlank { "msg_${UUID.randomUUID()}" })
                put("type", "message")
                put("role", "assistant")
                put("model", model)
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                })
                put("stop_reason", finish ?: "end_turn")
                put("stop_sequence", JsonPrimitive(null as String?))
                response.usage?.let { usage ->
                    put("usage", buildJsonObject {
                        put("input_tokens", usage.promptTokens)
                        put("output_tokens", usage.completionTokens)
                        put("cache_read_input_tokens", usage.cachedTokens)
                    })
                }
            }.toString()
        }
    }

    fun encodeStreamChunk(
        protocol: WireProtocol,
        id: String,
        model: String,
        created: Long,
        index: Int,
        text: String?,
        finishReason: String?,
        usage: Usage?,
    ): ByteArray = when (protocol) {
        WireProtocol.Chat -> {
            val event = ChatCompletionChunk(
                id = id.ifBlank { "chatcmpl-${UUID.randomUUID()}" },
                created = created,
                model = model,
                choices = listOf(
                    ChatCompletionChunk.WireDeltaChoice(
                        index = index,
                        delta = WireDelta(content = text),
                        finishReason = finishReason,
                    ),
                ),
                usage = usage?.let { WireUsage(it.promptTokens, it.completionTokens, it.totalTokens) },
            )
            "data: ${json.encodeToString(ChatCompletionChunk.serializer(), event)}\n\n".toByteArray()
        }
        WireProtocol.Responses -> {
            val payload = buildJsonObject {
                put("type", if (finishReason != null) "response.completed" else "response.output_text.delta")
                put("delta", text.orEmpty())
                put("response", buildJsonObject {
                    put("id", id)
                    put("object", "response")
                    put("created_at", created)
                    put("model", model)
                    put("status", if (finishReason != null) "completed" else "in_progress")
                })
            }
            "event: ${payload["type"]!!.jsonPrimitive.content}\ndata: $payload\n\n".toByteArray()
        }
        WireProtocol.Messages -> {
            val type = when {
                finishReason != null -> "message_stop"
                text != null -> "content_block_delta"
                else -> "message_start"
            }
            val payload = buildJsonObject {
                put("type", type)
                if (type == "content_block_delta") {
                    put("index", index)
                    put("delta", buildJsonObject {
                        put("type", "text_delta")
                        put("text", text.orEmpty())
                    })
                }
                if (type == "message_start") {
                    put("message", buildJsonObject {
                        put("id", id)
                        put("type", "message")
                        put("role", "assistant")
                        put("model", model)
                        put("content", buildJsonArray {})
                    })
                }
            }
            "event: $type\ndata: $payload\n\n".toByteArray()
        }
    }

    private fun parseObject(body: String): JsonObject =
        runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw IllegalArgumentException("请求体不是 JSON 对象，无法应用协议转换") }

    private fun JsonElement?.primitiveText(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun system(text: String) = message("system", text)
    private fun user(text: String) = message("user", text)

    private fun message(role: String, text: String): ChatMessage = ChatMessage(
        role = when (role.lowercase()) {
            "system", "developer", "instructions" -> ChatMessage.Role.System
            "assistant", "model" -> ChatMessage.Role.Assistant
            "tool", "function" -> ChatMessage.Role.Tool
            else -> ChatMessage.Role.User
        },
        content = listOf(ContentPart.Text(text)),
    )

    private fun JsonElement?.text(): String = when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonObject -> (this["text"] ?: this["content"]).text()
        is JsonArray -> multiText()
        else -> ""
    }

    private fun JsonElement?.multiText(): String = when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonObject -> (this["text"] ?: this["input_text"] ?: this["content"]).text()
        is JsonArray -> this.mapNotNull { element ->
            val value = when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> (element["text"] ?: element["input_text"] ?: element["content"]).text()
                else -> null
            }
            value?.takeIf { it.isNotBlank() }
        }.joinToString("\n")
        else -> ""
    }

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private fun JsonObject.int(vararg keys: String): Int? = keys.asSequence()
        .mapNotNull { (this[it] as? JsonPrimitive)?.longOrNull?.toInt() }
        .firstOrNull()

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    /**
     * 3.10.0 多协议扩展标量按 JSON 文本放进 extras；Provider 转发时再解析回原始类型。
     * 已映射到 ChatRequest 顶层字段的 tools/tool_choice 不走这里。
     */
    private fun JsonObject.protocolExtras(
        vararg fields: Pair<String, List<String>>,
    ): Map<String, String> = buildMap {
        fields.forEach { (target, aliases) ->
            val value = aliases.asSequence().mapNotNull { this@protocolExtras[it] }.firstOrNull()
            if (value != null) put(target, value.toString())
        }
    }

    private fun reasoningEffort(root: JsonObject): Map<String, String> {
        val reasoning = root["reasoning"] as? JsonObject ?: return emptyMap()
        val effort = (reasoning["effort"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return emptyMap()
        return mapOf("reasoning_effort" to JsonPrimitive(effort).toString())
    }

    private fun JsonElement?.toolChoice(): String? = when (this) {
        is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() }
        is JsonObject -> (this["name"] ?: this["function"]).let { function ->
            when (function) {
                is JsonObject -> (function["name"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> function.contentOrNull
                else -> null
            }
        }
        else -> null
    }
}
