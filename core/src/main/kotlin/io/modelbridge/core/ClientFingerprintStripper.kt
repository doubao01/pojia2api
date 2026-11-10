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
    private val identityPatterns = listOf(
        Regex("""(?m)^\s*You are Claude Code(?:, Anthropic['’]s (?:official )?CLI for Claude)?\s*${'$'}"""),
        Regex("""(?m)^\s*You are Codex\s*${'$'}"""),
        Regex("""(?m)^\s*You are a coding agent(?: running in (?:a terminal CLI(?: tool)?|the Codex CLI(?: tool)?))?\s*${'$'}"""),
        Regex("""(?m)^\s*You are a personal assistant running inside OpenClaw\.\s*${'$'}"""),
        Regex("""(?m)^\s*You are an AI agent powered by (?:DeepSeek Harness|a local coding harness)\s*${'$'}"""),
        Regex("""(?m)^\s*(?<!the )Codex CLI is an open source project led by OpenAI\.\s*${'$'}"""),
        Regex("""(?m)^\s*You are (?:an interactive coding agent|running as a coding agent(?: in (?:a terminal CLI|the Codex CLI))?)\s*${'$'}"""),
    )
    private val templateLine = Regex("""(?m)^\s*\{\{\s*[A-Za-z0-9_.\-]{0,40}\s*\}\}\s*${'$'}""")
    private val emptyTemplateCall = Regex("""\s*,\s*"arguments"\s*:\s*\{\}\s*""")

    fun strip(body: String): String {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return body
        val messages = root["messages"] as? JsonArray ?: return body
        val cleaned = JsonArray(messages.map { cleanMessage(it) })
        return JsonObject(root + ("messages" to cleaned)).toString()
    }

    private fun cleanMessage(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        val content = obj["content"] ?: return obj
        val cleanedContent = when (content) {
            is JsonPrimitive -> JsonPrimitive(cleanText(content.contentOrNull.orEmpty()))
            is JsonArray -> JsonArray(content.map { part ->
                val block = part as? JsonObject ?: return@map part
                val text = block["text"] as? JsonPrimitive ?: return@map part
                JsonObject(block + ("text" to JsonPrimitive(cleanText(text.contentOrNull.orEmpty()))))
            })
            else -> content
        }
        return JsonObject(obj + ("content" to cleanedContent))
    }

    private fun cleanText(text: String): String {
        var value = text
        identityPatterns.forEach { value = it.replace(value, "") }
        value = Regex("""(?m)^\s*x-anthropic-billing-hdr\s*:.*${'$'}""", RegexOption.IGNORE_CASE)
            .replace(value, "")
        value = Regex("""(?mi)^\s*(?:anthropic-(?:beta|version)|x-anthropic-billing-hdr)\s*:.*\r?\n?""")
            .replace(value, "")
        value = templateLine.replace(value, "")
        value = emptyTemplateCall.replace(value, "")
        return value
            .lineSequence()
            .map { it.trimEnd() }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}
