package io.modelbridge.core

import io.modelbridge.core.openai.WireProtocol
import io.modelbridge.core.openai.WireProtocolAdapter
import org.junit.jupiter.api.Assertions.assertEquals
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
}
