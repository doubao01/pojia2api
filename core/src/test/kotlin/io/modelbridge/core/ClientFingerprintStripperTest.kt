package io.modelbridge.core

import io.modelbridge.core.http.ServerRequest
import io.modelbridge.spi.ContentPart
import io.modelbridge.spi.Credential
import io.modelbridge.spi.ModelInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientFingerprintStripperTest {
    private val requestBody = """
        {
          "model": "demo-1",
          "temperature": 0.7,
          "tools": [{"type":"function","function":{"name":"lookup","parameters":{"type":"object"}}}],
          "messages": [
            {
              "role": "system",
              "content": "x-anthropic-billing-hdr: fixture\nYou are Claude Code, Anthropic's CLI for Claude\nYou are Codex\nThe Codex CLI is an open source project led by OpenAI.\nYou are a coding agent running in the Codex CLI\n{{tool_definitions}}\nKeep this rule"
            },
            {
              "role": "assistant",
              "content": [{"type":"text","text":"You are Claude Code\n{\"tool_calls\": [{\"name\": \"lookup\", \"arguments\": {}}]}"}]
            },
            {"role": "user", "content": "hello"}
          ]
        }
    """.trimIndent()

    private fun gateway(strip: Boolean, provider: FakeProvider): Gateway {
        val registry = ProviderRegistry().apply { register(provider) }
        val accounts = InMemoryAccountStore(
            listOf(Account("a1", "demo", "账号", credential = Credential.ApiKey("fixture-key"))),
        )
        val health = HealthTracker()
        return Gateway(
            Router(registry, accounts, health),
            ModelCatalog(registry, accounts),
            registry,
            accounts,
            health,
            StubContextFactory().factory,
            config = GatewayConfig(stripClientFingerprint = strip, maxRetries = 0),
        )
    }

    private fun request() = ServerRequest(
        method = "POST",
        path = "/v1/chat/completions",
        query = "",
        version = "HTTP/1.1",
        headers = emptyMap(),
        body = requestBody.encodeToByteArray(),
    )

    private fun firstText(provider: FakeProvider): String = provider.calls
        .single()
        .messages
        .first()
        .content
        .filterIsInstance<ContentPart.Text>()
        .joinToString("") { it.text }

    @Test
    fun `开关关闭时客户端身份句保持原样`() = runBlocking {
        val provider = FakeProvider("demo", models = listOf(ModelInfo("demo-1", "demo")))
        val gateway = gateway(strip = false, provider)
        gateway.refreshCatalog()
        gateway.handle(request())

        val text = firstText(provider)
        assertTrue(text.contains("You are Claude Code"))
        assertTrue(text.contains("x-anthropic-billing-hdr"))
        assertTrue(text.contains("{{tool_definitions}}"))
        assertEquals(0.7, provider.calls.single().temperature)
        assertEquals(1, provider.calls.single().tools.size)
    }

    @Test
    fun `开关开启时清理身份痕迹但保留普通规则和参数`() = runBlocking {
        val provider = FakeProvider("demo", models = listOf(ModelInfo("demo-1", "demo")))
        val gateway = gateway(strip = true, provider)
        gateway.refreshCatalog()
        gateway.handle(request())

        val text = firstText(provider)
        assertFalse(text.contains("You are Claude Code"))
        assertFalse(text.contains("You are Codex"))
        assertFalse(text.contains("x-anthropic-billing-hdr"))
        assertFalse(text.contains("{{tool_definitions}}"))
        assertTrue(text.contains("Keep this rule"))
        assertEquals("demo-1", provider.calls.single().model)
        assertEquals(0.7, provider.calls.single().temperature)
        assertEquals(1, provider.calls.single().tools.size)
    }

    @Test
    fun `解析失败时按原文发出`() {
        val invalid = "not-json"
        assertEquals(invalid, ClientFingerprintStripper.strip(invalid))
    }

    @Test
    fun `内容块数组中的文本也会被清理`() {
        val raw = """{"messages":[{"role":"user","content":[{"type":"text","text":"You are Codex\nhello"}]}]}"""
        val cleaned = ClientFingerprintStripper.strip(raw)
        assertFalse(cleaned.contains("You are Codex"))
        assertTrue(cleaned.contains("hello"))
    }
}
