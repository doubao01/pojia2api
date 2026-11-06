package io.modelbridge.core.openai

import io.modelbridge.spi.ChatMessage
import io.modelbridge.spi.ChatRequest
import io.modelbridge.spi.ContentPart
import io.modelbridge.spi.ToolCall
import io.modelbridge.spi.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 线上格式与内部模型之间的转换。
 *
 * 所有转换都是纯函数，便于单元测试。
 */
object Mapper {
    /**
     * 把客户端请求翻译成内部 [ChatRequest]。
     *
     * @throws IllegalArgumentException 当角色或消息格式非法
     */
    fun toChatRequest(wire: ChatCompletionRequest): ChatRequest = ChatRequest(
        model = wire.model,
        messages = wire.messages.map(::toChatMessage),
        temperature = wire.temperature,
        topP = wire.topP,
        maxTokens = wire.effectiveMaxTokens,
        stop = wire.stopSequences(),
        stream = wire.stream,
        tools = wire.tools.orEmpty().map(::toToolSpec),
        toolChoice = wire.toolChoice?.let(::toolChoiceToString),
        jsonMode = wire.responseFormat != null,
    )

    private fun toChatMessage(message: WireMessage): ChatMessage {
        val role = when (message.role.lowercase()) {
            "system", "developer" -> ChatMessage.Role.System
            "user" -> ChatMessage.Role.User
            "assistant" -> ChatMessage.Role.Assistant
            "tool", "function" -> ChatMessage.Role.Tool
            else -> throw IllegalArgumentException("不支持的消息角色：${message.role}")
        }

        val parts = buildList {
            val text = message.textContent()
            if (text.isNotEmpty()) add(ContentPart.Text(text))
        }

        return ChatMessage(
            role = role,
            content = parts,
            toolCalls = message.toolCalls.orEmpty().map { call ->
                ToolCall(
                    id = call.id,
                    name = call.function?.name.orEmpty(),
                    arguments = call.function?.arguments ?: "{}",
                )
            },
            toolCallId = message.toolCallId,
        )
    }

    private fun toToolSpec(tool: WireTool): ToolSpec = ToolSpec(
        name = tool.function.name,
        description = tool.function.description.orEmpty(),
        parametersJson = tool.function.parameters?.toString() ?: "{}",
    )

    private fun toolChoiceToString(element: JsonElement): String =
        when (element) {
            is JsonPrimitive -> element.content
            else -> "auto"
        }

    /** 把内部 assistant 消息翻译成线上格式。 */
    fun toWireMessage(message: ChatMessage): WireMessage {
        val role = when (message.role) {
            ChatMessage.Role.System -> "system"
            ChatMessage.Role.User -> "user"
            ChatMessage.Role.Assistant -> "assistant"
            ChatMessage.Role.Tool -> "tool"
        }

        val text = message.content
            .filterIsInstance<ContentPart.Text>()
            .joinToString("") { it.text }

        return WireMessage(
            role = role,
            content = JsonPrimitive(text),
            toolCalls = message.toolCalls.takeIf { it.isNotEmpty() }?.map { call ->
                WireToolCall(
                    id = call.id,
                    function = WireFunctionCall(call.name, call.arguments),
                )
            },
            toolCallId = message.toolCallId,
        )
    }

    /** 构造 OpenAI 风格的错误体。 */
    fun errorBody(message: String, type: String, code: String? = null): ErrorResponse =
        ErrorResponse(ErrorResponse.ErrorBody(message = message, type = type, code = code))

    /** 供 SSE 使用的紧凑 JSON。 */
    val sseJson: Json = Json {
        encodeDefaults = false
        explicitNulls = false
    }
}
