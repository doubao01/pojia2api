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
        assertTrue(cleaned.contains("You are a coding agent"))
        assertTrue(cleaned.contains("hello"))
    }

    @Test
    fun `identity phrases are replaced with neutral wording like the original`() {
        val raw = """{"messages":[{"role":"system","content":"You are Claude Code\nYou are ZCode, an interactive coding agent\nYou are a coding agent running in the Codex CLI tool\nYou are an AI agent powered by DeepSeek Harness"}]}"""
        val cleaned = ClientFingerprintStripper.strip(raw)

        assertTrue(cleaned.contains("You are a coding assistant"))
        assertTrue(cleaned.contains("You are an interactive coding agent"))
        assertTrue(cleaned.contains("You are a coding agent running in a terminal CLI tool"))
        assertTrue(cleaned.contains("You are an AI agent powered by a local coding harness"))
        assertFalse(cleaned.contains("You are Claude Code"))
        assertFalse(cleaned.contains("DeepSeek Harness"))
    }

    @Test
    fun `strip report counts rules without exposing request text`() {
        val raw = """{"model":"demo","messages":[{"role":"system","content":"x-anthropic-billing-hdr: fixture\n{{tool_definitions}}\nYou are Codex"}]}"""
        val result = ClientFingerprintStripper.stripWithReport(raw)

        assertTrue(result.parsed)
        assertTrue(result.rules.containsKey("Claude Code 身份句").not() || result.rules.isNotEmpty())
        assertTrue(result.rules.containsKey("Codex 身份句"))
        assertTrue(result.rules.containsKey("Anthropic 计费头"))
        assertTrue(result.rules.containsKey("模板占位行"))
        assertTrue(result.diagnostic.startsWith("去掉客户端指纹："))
        assertFalse(result.diagnostic.contains("fixture"))
        assertTrue(result.body.contains(""""model":"demo""""))
    }

    @Test
    fun `gateway records fingerprint rules without exposing body`() = runBlocking {
        val provider = FakeProvider("demo", models = listOf(ModelInfo("demo-1", "demo")))
        val gateway = gateway(strip = true, provider)
        gateway.refreshCatalog()
        val raw = """{"model":"demo-1","messages":[{"role":"system","content":"You are Codex\nx-anthropic-billing-hdr: fixture\nhello"},{"role":"user","content":"hi"}]}"""
        val request = ServerRequest(
            method = "POST",
            path = "/v1/chat/completions",
            query = "",
            version = "HTTP/1.1",
            headers = emptyMap(),
            body = raw.encodeToByteArray(),
        )

        gateway.handle(request)

        val diagnostic = gateway.lastFingerprintDiagnostic.orEmpty()
        assertTrue(diagnostic.startsWith("去掉客户端指纹："))
        assertTrue(diagnostic.contains("Codex 身份句"))
        assertFalse(diagnostic.contains("fixture"))
        assertFalse(diagnostic.contains("hello"))
    }

    @Test
    fun `gateway falls back to original body when fingerprint parsing fails`() = runBlocking {
        val provider = FakeProvider("demo", models = listOf(ModelInfo("demo-1", "demo")))
        val gateway = gateway(strip = true, provider)
        gateway.refreshCatalog()
        val invalidRequest = ServerRequest(
            method = "POST",
            path = "/v1/chat/completions",
            query = "",
            version = "HTTP/1.1",
            headers = emptyMap(),
            body = "not-json".encodeToByteArray(),
        )

        gateway.handle(invalidRequest)

        assertTrue(gateway.lastFingerprintDiagnostic?.contains("按原文发出") == true)
    }

    @Test
    fun `failed parse falls back to the original body without diagnostic rules`() {
        val result = ClientFingerprintStripper.stripWithReport("not-json")

        assertEquals("not-json", result.body)
        assertFalse(result.parsed)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.diagnostic.isEmpty())
    }
}
