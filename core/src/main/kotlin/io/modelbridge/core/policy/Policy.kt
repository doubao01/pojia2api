package io.modelbridge.core.policy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一条「策略」：一段前置正文，按模型匹配后注入到转发请求里。
 *
 * 对应原应用「网关管家」的策略库。语义（与原版一致）：
 *
 * 1. **命中规则**：按「映射后的实际上游模型 ID」精确匹配；
 *    未绑定模型的策略是通用策略，对所有模型生效，但优先级低于精确命中。
 * 2. **一次请求最多用一条策略**，不做拼接。
 * 3. **每条开关独立生效**：停用后不参与自动匹配，
 *    上游固定绑定的那条也会跳过注入。
 *
 * @property id 唯一标识
 * @property name 名称，界面显示用
 * @property models 绑定的模型 ID 列表；为空表示通用策略
 * @property instruction 策略正文（注入内容）
 * @property builtin 是否内置策略（内置的不可删除）
 * @property enabled 是否启用
 */
@Serializable
data class Policy(
    val id: String,
    val name: String,
    val models: List<String> = emptyList(),
    val instruction: String = "",
    val builtin: Boolean = false,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "策略 id 不能为空" }
    }

    /** 是否是通用策略（未绑定任何模型）。 */
    val isUniversal: Boolean get() = models.isEmpty()
}

/**
 * 注入位置。不同协议的注入点不同，与原版一致：
 * - [Chat]：作为独立 system 消息前置到 `messages`
 * - [Responses]：写进 `instructions` 字段
 * - [Messages]：写进 `system` 字段
 */
enum class InjectionProtocol {
    Chat,
    Responses,
    Messages,
}

/** 注入结果。 */
sealed interface InjectionResult {
    /** 注入成功，[body] 是新的请求体。 */
    data class Applied(val body: String, val policyName: String) : InjectionResult

    /** 无需注入（没有策略，或正文为空）。 */
    data object Skipped : InjectionResult

    /** 注入失败，[reason] 说明原因，调用方应原样转发并记录。 */
    data class Failed(val reason: String) : InjectionResult
}

/**
 * 策略匹配器：从一组策略里挑出本次请求该用的那一条。
 *
 * 实现对照原版 `p007h/u.c()`：
 * 1. 先过滤出启用的策略；
 * 2. 若指定了 [fixedPolicyId]（上游固定绑定），直接取它；
 * 3. 否则按候选模型 ID 找精确命中的；
 * 4. 都没有则取第一条通用策略；
 * 5. 仍没有就返回 null。
 */
object PolicyMatcher {

    /**
     * @param policies 全部策略
     * @param candidateModelIds 本次请求可能对应的上游模型 ID（映射后的），按优先级排列
     * @param fixedPolicyId 上游固定绑定的策略 id，可为 null
     */
    fun match(
        policies: List<Policy>,
        candidateModelIds: List<String>,
        fixedPolicyId: String? = null,
    ): Policy? {
        val enabled = policies.filter { it.enabled }

        if (!fixedPolicyId.isNullOrBlank()) {
            return enabled.firstOrNull { it.id == fixedPolicyId }
        }

        val ids = candidateModelIds.filter { it.isNotBlank() }.toSet()
        if (ids.isEmpty()) {
            return enabled.firstOrNull { it.isUniversal }
        }

        // 精确命中优先
        enabled.firstOrNull { p -> p.models.any { it in ids } }?.let { return it }

        // 通用策略兜底
        return enabled.firstOrNull { it.isUniversal }
    }
}

/**
 * 把策略正文注入到请求体里。
 *
 * 纯字符串进、纯字符串出，方便单测；解析失败不会破坏原请求。
 */
object PolicyInjector {

    /**
     * @param body 原始请求体（JSON 文本）
     * @param instruction 策略正文
     * @param protocol 目标协议
     */
    fun inject(body: String, instruction: String, protocol: InjectionProtocol): InjectionResult {
        if (instruction.isBlank()) return InjectionResult.Skipped

        val parsed = runCatching {
            // parseToJsonElement 是 Json 实例的成员，不是顶层函数
            Json.parseToJsonElement(body)
        }.getOrNull()
            ?: return InjectionResult.Failed("请求体不是合法 JSON，无法注入策略")

        val obj = parsed as? JsonObject
            ?: return InjectionResult.Failed("请求体不是 JSON 对象，无法注入策略")

        return when (protocol) {
            InjectionProtocol.Chat -> injectChat(obj, instruction)
            InjectionProtocol.Responses -> injectResponses(obj, instruction)
            InjectionProtocol.Messages -> injectMessages(obj, instruction)
        }
    }

    /**
     * chat 协议：把策略作为一条独立 system 消息放在 messages 最前面，
     * 客户端原有的消息一律保留。
     */
    private fun injectChat(
        obj: JsonObject,
        instruction: String,
    ): InjectionResult {
        val messages = obj["messages"] as? JsonArray
            ?: return InjectionResult.Failed("messages 不是数组")

        val systemMessage = JsonObject(
            mapOf(
                "role" to JsonPrimitive("system"),
                "content" to JsonPrimitive(instruction),
            ),
        )

        val merged = JsonArray(
            listOf<JsonElement>(systemMessage) + messages,
        )

        val out = JsonObject(
            obj + ("messages" to merged),
        )
        return InjectionResult.Applied(out.toString(), "")
    }

    /** responses 协议：写进 instructions 字段。 */
    private fun injectResponses(
        obj: JsonObject,
        instruction: String,
    ): InjectionResult {
        val original = obj["instructions"]
        if (original != null && original !is JsonPrimitive) {
            return InjectionResult.Failed("instructions 不是文本，保留原请求")
        }
        if (original is JsonPrimitive && !original.isString && original != kotlinx.serialization.json.JsonNull) {
            return InjectionResult.Failed("instructions 不是文本，保留原请求")
        }
        val existing = (original as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }

        // 已有 instructions 时前置，保留客户端原内容
        val value = if (existing != null) "$instruction\n\n$existing" else instruction

        val out = JsonObject(
            obj + ("instructions" to JsonPrimitive(value)),
        )
        return InjectionResult.Applied(out.toString(), "")
    }

    /** messages（Anthropic）协议：写进顶层 system 字段。 */
    private fun injectMessages(
        obj: JsonObject,
        instruction: String,
    ): InjectionResult {
        val existing = obj["system"]
        val merged = when {
            existing == null || existing == kotlinx.serialization.json.JsonNull -> JsonPrimitive(instruction)
            existing is JsonPrimitive && existing.isString -> {
                val text = existing.content
                JsonPrimitive(if (text.isBlank()) instruction else "$instruction\n\n$text")
            }
            existing is JsonArray -> {
                val block = JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(instruction)))
                JsonArray(listOf(block) + existing)
            }
            else -> return InjectionResult.Failed("system 不是文本或内容数组，保留原请求")
        }

        val out = JsonObject(obj + ("system" to merged))
        return InjectionResult.Applied(out.toString(), "")
    }
}
