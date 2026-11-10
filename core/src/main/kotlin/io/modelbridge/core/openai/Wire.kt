package io.modelbridge.core.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * OpenAI Chat Completions 兼容的线上格式。
 *
 * 这里刻意只覆盖实际会用到的字段，其余通过 [extra] 透传，
 * 避免上游新增字段时客户端解析失败。
 */

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<WireMessage>,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val stop: JsonElement? = null,
    val stream: Boolean = false,
    val tools: List<WireTool>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    @SerialName("response_format") val responseFormat: JsonElement? = null,
    @SerialName("n") val choices: Int? = null,
    val user: String? = null,
) {
    /** 归一化后的最大 token 数。 */
    val effectiveMaxTokens: Int?
        get() = maxTokens ?: maxCompletionTokens

    /** stop 字段既可能是字符串也可能是数组。 */
    fun stopSequences(): List<String> = when (val s = stop) {
        null -> emptyList()
        is JsonPrimitive -> listOfNotNull(s.contentOrNull)
        is JsonArray -> s.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }
}

@Serializable
data class WireMessage(
    val role: String,
    val content: JsonElement? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
) {
    /** content 既可能是纯字符串，也可能是多段数组。 */
    fun textContent(): String = when (val c = content) {
        null -> ""
        is JsonPrimitive -> c.contentOrNull ?: ""
        is JsonArray -> c.mapNotNull { part ->
            (part as? JsonObject)
                ?.get("text")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
        }.joinToString("")
        else -> ""
    }
}

@Serializable
data class WireToolCall(
    val id: String,
    val type: String = "function",
    val function: WireFunctionCall? = null,
)

@Serializable
data class WireFunctionCall(
    val name: String,
    val arguments: String = "{}",
)

@Serializable
data class WireTool(
    val type: String = "function",
    val function: WireFunctionDef,
)

@Serializable
data class WireFunctionDef(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement? = null,
)

/* ---------- 响应 ---------- */

@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<WireChoice>,
    val usage: WireUsage? = null,
) {
    @Serializable
    data class WireChoice(
        val index: Int,
        val message: WireMessage,
        @SerialName("finish_reason") val finishReason: String? = null,
    )
}

@Serializable
data class ChatCompletionChunk(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<WireDeltaChoice>,
    val usage: WireUsage? = null,
) {
    @Serializable
    data class WireDeltaChoice(
        val index: Int = 0,
        val delta: WireDelta = WireDelta(),
        @SerialName("finish_reason") val finishReason: String? = null,
    )
}

@Serializable
data class WireDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCallDelta>? = null,
)

@Serializable
data class WireToolCallDelta(
    val index: Int,
    val id: String? = null,
    val type: String? = "function",
    val function: WireFunctionDelta? = null,
)

@Serializable
data class WireFunctionDelta(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class WireUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

/* ---------- 模型列表 ---------- */

@Serializable
data class ModelListResponse(
    @SerialName("object") val objectType: String = "list",
    val data: List<ModelObject>,
)

@Serializable
data class ModelObject(
    val id: String,
    @SerialName("object") val objectType: String = "model",
    val created: Long = 0,
    @SerialName("owned_by") val ownedBy: String = "modelbridge",
    @SerialName("context_length") val contextLength: Long? = null,
    @SerialName("context_window") val contextWindow: Long? = null,
    @SerialName("max_context_tokens") val maxContextTokens: Long? = null,
    @SerialName("max_input_tokens") val maxInputTokens: Long? = null,
    @SerialName("max_model_len") val maxModelLen: Long? = null,
    val capabilities: ModelCapabilities? = null,
    @SerialName("supports_tools") val supportsTools: Boolean? = null,
    @SerialName("supports_function_calling") val supportsFunctionCalling: Boolean? = null,
    @SerialName("supports_reasoning") val supportsReasoning: Boolean? = null,
    @SerialName("supports_thinking") val supportsThinking: Boolean? = null,
    @SerialName("reasoning_levels") val reasoningLevels: List<String>? = null,
)

@Serializable
data class ModelCapabilities(
    val tools: Boolean,
    @SerialName("tool_calls") val toolCalls: Boolean,
    @SerialName("function_calling") val functionCalling: Boolean,
    val reasoning: Boolean,
    val thinking: Boolean,
)

/* ---------- 错误 ---------- */

@Serializable
data class ErrorResponse(
    val error: ErrorBody,
) {
    @Serializable
    data class ErrorBody(
        val message: String,
        val type: String,
        val code: String? = null,
    )
}
