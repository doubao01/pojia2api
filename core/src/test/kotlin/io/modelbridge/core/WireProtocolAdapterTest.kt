package io.modelbridge.core

import io.modelbridge.core.openai.WireProtocol
import io.modelbridge.core.openai.WireProtocolAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WireProtocolAdapterTest {
    @Test
    fun `responses scalars and reasoning object map to typed extras`() {
        val request = WireProtocolAdapter.decodeChatRequest(
            """{
                "model":"demo",
                "input":"hello",
                "reasoning":{"effort":"high"},
                "parallel_tool_calls":false,
                "context_window":"200K",
                "top_k":40,
                "tool_choice":{"type":"function","function":{"name":"lookup"}}
            }""",
            WireProtocol.Responses,
        )

        assertEquals("lookup", request.toolChoice)
        assertEquals("\"high\"", request.extras["reasoning_effort"])
        assertEquals("false", request.extras["parallel_tool_calls"])
        assertEquals("\"200K\"", request.extras["context_window"])
        assertEquals("40", request.extras["top_k"])
    }

    @Test
    fun `messages thinking and top_k map to extras`() {
        val request = WireProtocolAdapter.decodeChatRequest(
            """{
                "model":"demo",
                "messages":[{"role":"user","content":"hello"}],
                "thinking":{"type":"enabled"},
                "top_k":7,
                "tool_choice":"auto"
            }""",
            WireProtocol.Messages,
        )

        assertEquals("auto", request.toolChoice)
        assertEquals("7", request.extras["top_k"])
        assertEquals("{\"type\":\"enabled\"}", request.extras["thinking"])
    }

    @Test
    fun `responses validates dex parameter limits`() {
        val cases = listOf(
            """"reasoning_effort":"medium"""" to "reasoning_effort 仅支持 low / high / max",
            """"parallel_tool_calls":"yes"""" to "parallel_tool_calls 必须是布尔值",
            """"context_window":"2M"""" to "context_window 仅支持 200K / 500K / 1M",
            """"tool_choice":"sometimes"""" to "tool_choice 只支持 auto、none、required 或指定 function",
        )
        cases.forEach { (extra, expected) ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                WireProtocolAdapter.decodeChatRequest(
                    """{"model":"demo","input":"hello",$extra}""",
                    WireProtocol.Responses,
                )
            }
            assertEquals(expected, error.message)
        }
    }

    @Test
    fun `messages rejects context_window but allows valid reasoning`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            WireProtocolAdapter.decodeChatRequest(
                """{"model":"demo","messages":[{"role":"user","content":"hello"}],"context_window":"200K"}""",
                WireProtocol.Messages,
            )
        }
        assertEquals("不支持 context_window 参数", error.message)

        val request = WireProtocolAdapter.decodeChatRequest(
            """{"model":"demo","messages":[{"role":"user","content":"hello"}],"reasoning":{"effort":"max"}}""",
            WireProtocol.Messages,
        )
        assertTrue(request.messages.isNotEmpty())
    }

    @Test
    fun `chat validates message content and tool call chain`() {
        val cases = listOf(
            """{"model":"demo","messages":[]}""" to "messages 必须是非空数组",
            """{"model":"demo","messages":[{"role":"alien","content":"hi"}]}""" to "不支持的消息角色: alien",
            """{"model":"demo","messages":[{"role":"user","content":{"a":1}}]}""" to "消息 content 必须是字符串、数组或 null",
            """{"model":"demo","messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"lookup","arguments":"oops"}}]}]}""" to "].function.arguments 必须是 JSON 对象",
            """{"model":"demo","messages":[{"role":"tool","content":"result","tool_call_id":"missing"}]}""" to "] 的 tool_call_id 没有待响应的 assistant tool_call",
        )
        cases.forEach { (body, expected) ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                WireProtocolAdapter.decodeChatRequest(body, WireProtocol.Chat)
            }
            assertEquals(expected, error.message)
        }
    }

    @Test
    fun `chat accepts well formed function tool call conversation`() {
        val request = WireProtocolAdapter.decodeChatRequest(
            """{
              "model":"demo",
              "messages":[
                {"role":"user","content":"hi"},
                {"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"lookup","arguments":"{\"q\":\"x\"}"}}]},
                {"role":"tool","content":"ok","tool_call_id":"c1"}
              ],
              "tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object"},"strict":false}}],
              "tool_choice":{"type":"function","function":{"name":"lookup"}}
            }""",
            WireProtocol.Chat,
        )

        assertEquals(3, request.messages.size)
        assertEquals(1, request.tools.size)
        assertEquals("lookup", request.messages[1].toolCalls.single().name)
    }

    @Test
    fun `tools validate function schema and strict flag`() {
        val cases = listOf(
            """"tools":"bad"""" to "tools 必须是数组",
            """"tools":[{"type":"other","function":{"name":"x"}}]""" to "] 只支持 type=function",
            """"tools":[{"type":"function","function":{"name":"","parameters":{"type":"object"}}}]""" to "].function.name 无效",
            """"tools":[{"type":"function","function":{"name":"x","parameters":[]}}]""" to "].function.parameters 必须是 JSON Schema 对象",
            """"tools":[{"type":"function","function":{"name":"x","strict":true}}]""" to "].function.strict=true 暂不支持",
        )
        cases.forEach { (extra, expected) ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                WireProtocolAdapter.decodeChatRequest(
                    """{"model":"demo","messages":[{"role":"user","content":"hi"}],$extra}""",
                    WireProtocol.Chat,
                )
            }
            assertEquals(expected, error.message)
        }
    }
}
