package io.modelbridge.spi

/**
 * 统一格式的对话补全请求。
 *
 * 刻意贴近 OpenAI Chat Completions 的形状，因为绝大多数上游都兼容它，
 * 这样适配器只需要处理差异部分。
 */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val stop: List<String> = emptyList(),
    val stream: Boolean = false,
    /** 工具定义，未提供表示不使用工具。 */
    val tools: List<ToolSpec> = emptyList(),
    /** 工具选择策略：`auto`、`none`、`required` 或指定函数名。 */
    val toolChoice: String? = null,
    /** 要求上游返回 JSON 对象。 */
    val jsonMode: Boolean = false,
    /** 附加参数，原样透传给上游。 */
    val extras: Map<String, String> = emptyMap(),
)

/** 一条对话消息。 */
data class ChatMessage(
    val role: Role,
    val content: List<ContentPart> = emptyList(),
    /** 工具调用请求（仅 assistant 消息携带）。 */
    val toolCalls: List<ToolCall> = emptyList(),
    /** 工具执行结果（仅 tool 消息携带）。 */
    val toolCallId: String? = null,
) {
    enum class Role { System, User, Assistant, Tool }
}

/** 消息内容片段。 */
sealed interface ContentPart {
    data class Text(val text: String) : ContentPart

    /** 图片，[data] 为 base64 或 URL，由 [mimeType] 区分。 */
    data class Image(val data: String, val mimeType: String = "image/png") : ContentPart
}

/** 一次工具调用请求。 */
data class ToolCall(
    val id: String,
    val name: String,
    /** JSON 字符串形式的参数。 */
    val arguments: String,
)

/** 工具定义。 */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema 字符串。 */
    val parametersJson: String,
)

/** 上游返回结果。 */
sealed interface ChatResult {
    /** 非流式完整响应。 */
    data class Complete(val response: ChatResponse) : ChatResult

    /** 流式增量响应。 */
    data class Stream(val chunks: Sequence<ChatChunk>) : ChatResult
}

/** 非流式响应。 */
data class ChatResponse(
    val id: String,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null,
) {
    data class Choice(
        val index: Int,
        val message: ChatMessage,
        val finishReason: String? = null,
    )
}

/** 流式增量。 */
data class ChatChunk(
    val id: String,
    val model: String,
    val index: Int = 0,
    /** 本片新增的文本内容，可为空。 */
    val deltaText: String? = null,
    /** 本片新增的工具调用。 */
    val deltaToolCalls: List<ToolCallDelta> = emptyList(),
    val role: ChatMessage.Role? = null,
    val finishReason: String? = null,
    /** 仅最后一片携带用量。 */
    val usage: Usage? = null,
)

/** 流式工具调用增量。 */
data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    /** 参数片段，需要按 [index] 累加拼接。 */
    val argumentsDelta: String? = null,
)

/**
 * token 用量。
 *
 * [cachedTokens] 是提示词里命中缓存的部分。不同厂商字段名不同
 * （OpenAI 的 prompt_tokens_details.cached_tokens、
 *  Anthropic 的 cache_read_input_tokens、
 *  DeepSeek 的 prompt_cache_hit_tokens），各 Provider 负责归一到这里。
 */
data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int = promptTokens + completionTokens,
    val cachedTokens: Int = 0,
) {
    /** 缓存命中率（0–100）。没有提示词时返回 null，不编造数字。 */
    val cacheHitRate: Double?
        get() = if (promptTokens > 0) cachedTokens * 100.0 / promptTokens else null
}
