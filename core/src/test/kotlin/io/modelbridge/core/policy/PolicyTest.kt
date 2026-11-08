package io.modelbridge.core.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 策略匹配与注入的单元测试。
 *
 * 规则对照原版 `p007h/u.c()` 与 `p007h/u.a()`：
 * 精确命中优先、通用策略兜底、一次只注入一条。
 */
class PolicyTest {

    private fun policy(
        id: String,
        models: List<String> = emptyList(),
        instruction: String = "be nice",
        enabled: Boolean = true,
        builtin: Boolean = false,
    ) = Policy(
        id = id,
        name = "策略-$id",
        models = models,
        instruction = instruction,
        enabled = enabled,
        builtin = builtin,
    )

    // ---------- 匹配 ----------

    @Test
    fun `精确命中的策略优先于通用策略`() {
        val exact = policy("exact", models = listOf("gpt-4o"))
        val universal = policy("universal")

        val matched = PolicyMatcher.match(
            policies = listOf(universal, exact),
            candidateModelIds = listOf("gpt-4o"),
        )

        assertEquals("exact", matched?.id)
    }

    @Test
    fun `没有精确命中时回落到通用策略`() {
        val exact = policy("exact", models = listOf("gpt-4o"))
        val universal = policy("universal")

        val matched = PolicyMatcher.match(
            policies = listOf(exact, universal),
            candidateModelIds = listOf("other-model"),
        )

        assertEquals("universal", matched?.id)
    }

    @Test
    fun `停用的策略不参与匹配`() {
        val disabled = policy("off", models = listOf("gpt-4o"), enabled = false)

        val matched = PolicyMatcher.match(
            policies = listOf(disabled),
            candidateModelIds = listOf("gpt-4o"),
        )

        assertNull(matched)
    }

    @Test
    fun `没有候选模型时只取通用策略`() {
        val exact = policy("exact", models = listOf("gpt-4o"))
        val universal = policy("universal")

        val matched = PolicyMatcher.match(
            policies = listOf(exact, universal),
            candidateModelIds = emptyList(),
        )

        assertEquals("universal", matched?.id)
    }

    @Test
    fun `固定绑定的策略直接生效`() {
        val fixed = policy("fixed", models = listOf("gpt-4o"))
        val universal = policy("universal")

        val matched = PolicyMatcher.match(
            policies = listOf(universal, fixed),
            candidateModelIds = listOf("unrelated"),
            fixedPolicyId = "fixed",
        )

        assertEquals("fixed", matched?.id)
    }

    @Test
    fun `空策略列表返回 null`() {
        assertNull(
            PolicyMatcher.match(emptyList(), listOf("gpt-4o")),
        )
    }

    // ---------- 注入：chat ----------

    @Test
    fun `chat 协议把策略作为 system 消息前置`() {
        val body = """{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}"""

        val result = PolicyInjector.inject(body, "TRANSCRIBE", InjectionProtocol.Chat)
        assertTrue(result is InjectionResult.Applied)

        val obj = Json.parseToJsonElement((result as InjectionResult.Applied).body) as JsonObject
        val messages = obj["messages"]!!.jsonArray

        assertEquals(2, messages.size, "应变成 system + 原 user 两条")
        assertEquals(
            "system",
            messages[0].jsonObject["role"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "TRANSCRIBE",
            messages[0].jsonObject["content"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "user",
            messages[1].jsonObject["role"]!!.jsonPrimitive.content,
            "客户端原有消息必须保留且顺序在后",
        )
    }

    // ---------- 注入：responses ----------

    @Test
    fun `responses 协议写进 instructions`() {
        val body = """{"model":"gpt-4o","input":"hello"}"""

        val result = PolicyInjector.inject(body, "BE BRIEF", InjectionProtocol.Responses)
        assertTrue(result is InjectionResult.Applied)

        val obj = Json.parseToJsonElement((result as InjectionResult.Applied).body) as JsonObject
        assertEquals("BE BRIEF", obj["instructions"]!!.jsonPrimitive.content)
        assertEquals("hello", obj["input"]!!.jsonPrimitive.content, "其它字段不能丢")
    }

    @Test
    fun `responses 协议保留客户端已有 instructions`() {
        val body = """{"instructions":"原有说明","input":"hi"}"""

        val result = PolicyInjector.inject(body, "新增", InjectionProtocol.Responses)
        val obj = Json.parseToJsonElement((result as InjectionResult.Applied).body) as JsonObject
        val value = obj["instructions"]!!.jsonPrimitive.content

        assertTrue(value.startsWith("新增"), "策略应在前面")
        assertTrue(value.contains("原有说明"), "原内容必须保留")
    }

    // ---------- 注入：messages ----------

    @Test
    fun `messages 协议写进 system 字段`() {
        val body = """{"model":"claude","messages":[{"role":"user","content":"hi"}]}"""

        val result = PolicyInjector.inject(body, "RULE", InjectionProtocol.Messages)
        assertTrue(result is InjectionResult.Applied)

        val obj = Json.parseToJsonElement((result as InjectionResult.Applied).body) as JsonObject
        assertEquals("RULE", obj["system"]!!.jsonPrimitive.content)
        assertNotNull(obj["messages"], "原有 messages 不能丢")
    }

    // ---------- 边界 ----------

    @Test
    fun `空正文时跳过注入`() {
        assertEquals(
            InjectionResult.Skipped,
            PolicyInjector.inject("""{"messages":[]}""", "  ", InjectionProtocol.Chat),
        )
    }

    @Test
    fun `非法 JSON 返回失败而不是抛异常`() {
        val result = PolicyInjector.inject("not json", "X", InjectionProtocol.Chat)
        assertTrue(result is InjectionResult.Failed, "应返回 Failed，让调用方按原文转发")
    }

    @Test
    fun `chat 协议缺 messages 时返回失败`() {
        val result = PolicyInjector.inject("""{"model":"x"}""", "X", InjectionProtocol.Chat)
        assertTrue(result is InjectionResult.Failed)
    }

    @Test
    fun `内置策略不可删除`() {
        val store = InMemoryPolicyStore(
            listOf(policy("builtin", builtin = true)),
        )
        store.delete("builtin")
        assertNotNull(store.find("builtin"), "内置策略应保留")
    }

    @Test
    fun `普通策略可以删除`() {
        val store = InMemoryPolicyStore(listOf(policy("custom")))
        store.delete("custom")
        assertNull(store.find("custom"))
    }
}
