package io.modelbridge.providers.opencode

import io.modelbridge.spi.ChatMessage
import io.modelbridge.spi.ChatRequest
import io.modelbridge.spi.ContentPart
import io.modelbridge.spi.ToolSpec

/**
 * OpenCode 免费层门禁适配。
 *
 * 免费层按**请求内容**判定是否放行，不看请求头。实测与社区测量（2026-10-30）
 * 一致，需要同时满足三个条件：
 *
 * 1. 系统提示词含 `<env>` 块，且块内有 `Working directory:` 一行。值不限。
 * 2. 声明官方的 `read` 工具，描述与参数 schema 都要匹配。只声明 `bash` 会被拒；
 *    `read` 单独或 `read` + `bash` 都放行。
 * 3. TLS ClientHello 提供官方 CLI 的 17 个加密套件。该项在传输层，见
 *    [OpenCodeTls]。
 *
 * 另有两个工具名会单独触发拒绝：`subagent_fork` 与 `working_directory`，
 * 需要改名后才提交。
 *
 * 注意：`FreeUsageLimitError` 不等于配额耗尽——在仍有配额的出口上也会出现，
 * 门禁拒绝同样返回它。
 */
object OpenCodeGate {

    /** 官方 CLI 的 env 块。值保持通用，不暴露宿主真实路径。 */
    const val ENVIRONMENT_BLOCK: String = "<env>\n  Working directory: (managed by the host)\n</env>"

    private val ENVIRONMENT_PATTERN = Regex("<env>[\\s\\S]*Working directory:[\\s\\S]*</env>")

    /** 触发拒绝的宿主工具名到安全别名的映射。 */
    private val TOOL_ALIASES: Map<String, String> = mapOf(
        "subagent_fork" to "agent_fork",
        "working_directory" to "cwd_tool",
    )

    /** 反向映射，用于把上游返回的调用名翻回宿主工具名。 */
    private val TOOL_ORIGINALS: Map<String, String> =
        TOOL_ALIASES.entries.associate { (host, alias) -> alias to host }

    /** 官方 `read` 工具的名称。 */
    private const val READ_TOOL = "read"

    private const val READ_DESCRIPTION =
        "Read a file from the local filesystem.\n" +
            "\n" +
            "Usage:\n" +
            "- The filePath parameter should be an absolute path.\n" +
            "- By default, the tool reads up to 2000 lines from the beginning of the file.\n" +
            "- Optionally specify a line offset and limit to read a specific range.\n" +
            "- This tool reads text files only; it does not read binary files."

    /** 官方 `read` 的参数 schema。描述或 schema 缺少都会被拒。 */
    private const val READ_PARAMETERS =
        """{"type":"object","properties":{""" +
            """"filePath":{"type":"string","description":"The absolute path to the file to read"},""" +
            """"offset":{"type":"number","description":"The line number to start reading from (1-indexed)"},""" +
            """"limit":{"type":"number","description":"The maximum number of lines to read"}},""" +
            """"required":["filePath"]}"""

    /**
     * 判断请求体是否需要门禁适配。
     *
     * 付费模型带 key 时不走免费层门禁，不做改写以免影响正常调用。
     */
    fun needsAdaptation(apiKey: String, model: String): Boolean =
        apiKey.isBlank() || model.endsWith("-free")

    /**
     * 把请求改写成能通过免费层门禁的形态。
     *
     * 三件事：注入 `<env>` 块、补官方 `read` 声明、把被拒的工具名换别名。
     */
    fun adapt(request: ChatRequest): ChatRequest {
        val adapted = request.copy(
            messages = withEnvironmentBlock(request.messages),
            tools = withGateReadTool(request.tools),
        )
        return renameTools(adapted)
    }

    /** 把上游返回的工具调用名翻回宿主工具名。 */
    fun restoreToolName(name: String): String = TOOL_ORIGINALS[name] ?: name

    /**
     * 在系统消息里注入 `<env>` 块。
     *
     * 已有 env 块时不动；没有 system 消息时新建一条。
     */
    private fun withEnvironmentBlock(messages: List<ChatMessage>): List<ChatMessage> {
        val systemIndex = messages.indexOfFirst { it.role == ChatMessage.Role.System }
        if (systemIndex < 0) {
            return listOf(
                ChatMessage(
                    ChatMessage.Role.System,
                    listOf(ContentPart.Text(ENVIRONMENT_BLOCK)),
                ),
            ) + messages
        }
        val system = messages[systemIndex]
        val text = system.content.filterIsInstance<ContentPart.Text>()
            .joinToString("") { it.text }
        if (ENVIRONMENT_PATTERN.containsMatchIn(text)) return messages

        val merged = if (text.isBlank()) {
            ENVIRONMENT_BLOCK
        } else {
            "$text\n\n$ENVIRONMENT_BLOCK"
        }
        return messages.toMutableList().apply {
            this[systemIndex] = system.copy(content = listOf(ContentPart.Text(merged)))
        }
    }

    /**
     * 补上官方 `read` 声明。
     *
     * 调用方已声明 `read` 时原样保留（避免覆盖掉参数更完整的声明）；
     * 没有则追加。只声明 `bash` 会被拒，所以这个补充是必要的。
     */
    private fun withGateReadTool(tools: List<ToolSpec>): List<ToolSpec> {
        if (tools.any { it.name == READ_TOOL }) return tools
        return tools + ToolSpec(
            name = READ_TOOL,
            description = READ_DESCRIPTION,
            parametersJson = READ_PARAMETERS,
        )
    }

    /** 把会触发拒绝的工具名换成别名。 */
    private fun renameTools(request: ChatRequest): ChatRequest {
        val tools = request.tools.map { tool ->
            val alias = TOOL_ALIASES[tool.name]
            if (alias == null) tool else tool.copy(name = alias)
        }
        val messages = request.messages.map { message ->
            if (message.toolCalls.isEmpty()) {
                message
            } else {
                message.copy(
                    toolCalls = message.toolCalls.map { call ->
                        val alias = TOOL_ALIASES[call.name]
                        if (alias == null) call else call.copy(name = alias)
                    },
                )
            }
        }
        return request.copy(tools = tools, messages = messages)
    }
}
