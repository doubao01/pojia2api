package io.modelbridge.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 清理客户端请求正文里的身份痕迹。
 *
 * 3.10.0 dex 文案明确说明：只清理对话内容里的「计费头整行、Claude Code /
 * Codex 身份句、模板句、空模板参数」，模型和工具参数不动。解析或清洗失败时
 * 按原文发出，不阻断请求。
 */
object ClientFingerprintStripper {
    /** 3.9.3 `z/g.java:7`：优先按原版替换为中性身份，再清理剩余计费/模板痕迹。 */
    private val identityReplacements = listOf(
        "You are Claude Code, Anthropic's CLI for Claude" to "You are a coding assistant",
        "You are Claude Code, Anthropic’s CLI for Claude" to "You are a coding assistant",
        "You are ZCode, an interactive coding agent" to "You are an interactive coding agent",
        "You are a coding agent running in the Codex CLI tool" to "You are a coding agent running in a terminal CLI tool",
        "You are a coding agent running in the Codex CLI" to "You are a coding agent running in a terminal CLI",
        "You are running as a coding agent in the Codex CLI" to "You are running as a coding agent in a terminal CLI",
        "You are Claude Code" to "You are a coding assistant",
        "You are ZCode" to "You are an interactive coding agent",
        "You are an AI agent powered by DeepSeek Harness" to "You are an AI agent powered by a local coding harness",
        "You are Codex" to "You are a coding agent",
        "The Codex CLI is an open source project led by OpenAI." to "This coding CLI is an open source project.",
    )

    /** 命中并改写的规则名，用于「去掉客户端指纹：…」诊断输出；不记录正文。 */
    private val ruleNames = mapOf(
        "You are ZCode, an interactive coding agent" to "ZCode 身份句",
        "You are a coding agent running in the Codex CLI tool" to "Codex CLI tool 身份句",
        "You are a coding agent running in the Codex CLI" to "Codex CLI 身份句",
        "You are running as a coding agent in the Codex CLI" to "Codex CLI 运行身份句",
        "You are Claude Code" to "Claude Code 身份句",
        "You are ZCode" to "ZCode 简称",
        "You are an AI agent powered by DeepSeek Harness" to "DS Harness 身份句",
        "You are Codex" to "Codex 身份句",
        "The Codex CLI is an open source project led by OpenAI." to "Codex 开源声明",
        "x-anthropic-billing-hdr" to "Anthropic 计费头",
        "anthropic-beta" to "Anthropic Beta 头",
        "anthropic-version" to "Anthropic Version 头",
        "{{" to "模板占位行",
        "\"arguments\":{}" to "空模板参数",
    )

    /** 仅清理替换后仍残留的原始客户端标识整行；中性身份句必须保留。 */
    private val identityPatterns = listOf(
        Regex("""(?m)^\s*You are Claude Code(?:, Anthropic['’]s (?:official )?CLI for Claude)?\s*${'$'}"""),
        Regex("""(?m)^\s*You are Codex\s*${'$'}"""),
        Regex("""(?m)^\s*You are a coding agent running in (?:the )?Codex CLI(?: tool)?\s*${'$'}"""),
        Regex("""(?m)^\s*You are running as a coding agent in (?:a terminal CLI|the Codex CLI)\s*${'$'}"""),
        Regex("""(?m)^\s*You are a personal assistant running inside OpenClaw\.\s*${'$'}"""),
        Regex("""(?m)^\s*You are an AI agent powered by DeepSeek Harness\s*${'$'}"""),
        Regex("""(?m)^\s*(?<!the )Codex CLI is an open source project led by OpenAI\.\s*${'$'}"""),
        Regex("""(?m)^\s*You are ZCode(?:, an interactive coding agent)?\s*${'$'}"""),
    )
    private val templateLine = Regex("""(?m)^\s*\{\{\s*[A-Za-z0-9_.\-]{0,40}\s*\}\}\s*${'$'}""")
    private val emptyTemplateCall = Regex("""\s*,\s*"arguments"\s*:\s*\{\}\s*""")

    fun strip(body: String): String = stripWithReport(body).body

    /**
     * 返回改写后的正文和命中的规则名。原版会把命中规则写入日志；
     * 这里只暴露规则名，不暴露请求正文、令牌或 Cookie。
     */
    fun stripWithReport(body: String): StripResult {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return StripResult(body, emptyMap(), false)
        val messages = root["messages"] as? JsonArray ?: return StripResult(body, emptyMap(), true)
        val hits = LinkedHashMap<String, Int>()
        val cleaned = JsonArray(messages.map { cleanMessage(it, hits) })
        return StripResult(JsonObject(root + ("messages" to cleaned)).toString(), hits, true)
    }

    private fun cleanMessage(element: JsonElement, hits: MutableMap<String, Int>): JsonElement {
        val obj = element as? JsonObject ?: return element
        val content = obj["content"] ?: return obj
        val cleanedContent = when (content) {
            is JsonPrimitive -> JsonPrimitive(cleanText(content.contentOrNull.orEmpty(), hits))
            is JsonArray -> JsonArray(content.map { part ->
                val block = part as? JsonObject ?: return@map part
                val text = block["text"] as? JsonPrimitive ?: return@map part
                JsonObject(block + ("text" to JsonPrimitive(cleanText(text.contentOrNull.orEmpty(), hits))))
            })
            else -> content
        }
        return JsonObject(obj + ("content" to cleanedContent))
    }

    private fun cleanText(text: String, hits: MutableMap<String, Int>): String {
        var value = text
        identityReplacements.forEach { (source, replacement) ->
            if (value.contains(source, ignoreCase = true)) {
                value = value.replace(source, replacement, ignoreCase = true)
                record(hits, ruleNames[source].orEmpty())
            }
        }
        identityPatterns.forEach { pattern ->
            if (pattern.containsMatchIn(value)) {
                value = pattern.replace(value, "")
                record(hits, "客户端身份整行")
            }
        }
        val billingHeader = Regex(
            """(?mi)^\s*(?:anthropic-(?:beta|version)|x-anthropic-billing-hdr)\s*:.*\r?\n?""",
        )
        if (billingHeader.containsMatchIn(value)) {
            value = billingHeader.replace(value, "")
            record(hits, "Anthropic 计费头")
        }
        if (templateLine.containsMatchIn(value)) {
            value = templateLine.replace(value, "")
            record(hits, "模板占位行")
        }
        if (emptyTemplateCall.containsMatchIn(value)) {
            value = emptyTemplateCall.replace(value, "")
            record(hits, "空模板参数")
        }
        return value
            .lineSequence()
            .map { it.trimEnd() }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun record(hits: MutableMap<String, Int>, rule: String) {
        if (rule.isBlank()) return
        hits[rule] = (hits[rule] ?: 0) + 1
    }

    data class StripResult(
        val body: String,
        val rules: Map<String, Int>,
        val parsed: Boolean,
    ) {
        val diagnostic: String get() = if (rules.isEmpty()) "" else
            "去掉客户端指纹：" + rules.entries.joinToString("、") { "${it.key}=${it.value}" }
    }
}
