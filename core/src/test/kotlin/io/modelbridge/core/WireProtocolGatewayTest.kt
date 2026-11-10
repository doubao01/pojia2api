package io.modelbridge.core

import io.modelbridge.core.http.HttpResult
import io.modelbridge.core.http.ServerRequest
import io.modelbridge.core.policy.InMemoryPolicyStore
import io.modelbridge.core.policy.Policy
import io.modelbridge.spi.ChatMessage
import io.modelbridge.spi.ContentPart
import io.modelbridge.spi.Credential
import io.modelbridge.spi.ModelInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WireProtocolGatewayTest {
    private class Fixture(policies: List<Policy> = emptyList()) {
        val provider = FakeProvider("demo", models = listOf(ModelInfo("demo-1", "demo")))
        val registry = ProviderRegistry().apply { register(provider) }
        val accounts = InMemoryAccountStore(
            listOf(Account("a1", "demo", "账号", credential = Credential.ApiKey("fixture-key"))),
        )
        val health = HealthTracker()
        val gateway = Gateway(
            Router(registry, accounts, health),
            ModelCatalog(registry, accounts),
            registry,
            accounts,
            health,
            StubContextFactory().factory,
            config = GatewayConfig(maxRetries = 0),
            policyStore = InMemoryPolicyStore(policies),
        )
    }

    private fun request(path: String, body: String) = ServerRequest(
        method = "POST",
        path = path,
        query = "",
        version = "HTTP/1.1",
        headers = emptyMap(),
        body = body.encodeToByteArray(),
    )

    private fun firstText(provider: FakeProvider): String = provider.calls
        .single()
        .messages
        .flatMap { it.content }
        .filterIsInstance<ContentPart.Text>()
        .joinToString("\n") { it.text }

    @Test
    fun `responses endpoint converts input instructions and emits response object`() = runBlocking {
        val fixture = Fixture()
        fixture.gateway.refreshCatalog()
        val body = """
            {
              "model": "demo-1",
              "instructions": "be brief",
              "input": [
                {"role": "user", "content": [{"type":"input_text","text":"hello"}]}
              ],
              "max_output_tokens": 64
            }
        """.trimIndent()

        val result = fixture.gateway.handle(request("/v1/responses", body)) as HttpResult.Buffered
        val json = Json.parseToJsonElement(result.body.decodeToString()).jsonObject

        assertEquals(200, result.status)
        assertEquals("response", json["object"]!!.jsonPrimitive.content)
        assertEquals("demo-1", json["model"]!!.jsonPrimitive.content)
        assertEquals("completed", json["status"]!!.jsonPrimitive.content)
        assertTrue(firstText(fixture.provider).contains("be brief"))
        assertTrue(firstText(fixture.provider).contains("hello"))
        assertEquals(64, fixture.provider.calls.single().maxTokens)
    }

    @Test
    fun `messages endpoint converts system and content blocks and emits Anthropic shape`() = runBlocking {
        val fixture = Fixture()
        fixture.gateway.refreshCatalog()
        val body = """
            {
              "model": "demo-1",
              "system": [{"type":"text","text":"be terse"}],
              "messages": [
                {"role": "user", "content": [{"type":"text","text":"hello"}]}
              ],
              "max_tokens": 32
            }
        """.trimIndent()

        val result = fixture.gateway.handle(request("/v1/messages", body)) as HttpResult.Buffered
        val json = Json.parseToJsonElement(result.body.decodeToString()).jsonObject

        assertEquals(200, result.status)
        assertEquals("message", json["type"]!!.jsonPrimitive.content)
        assertEquals("assistant", json["role"]!!.jsonPrimitive.content)
        assertEquals("demo-1", json["model"]!!.jsonPrimitive.content)
        assertTrue(firstText(fixture.provider).contains("be terse"))
        assertTrue(firstText(fixture.provider).contains("hello"))
        assertEquals(32, fixture.provider.calls.single().maxTokens)
    }

    @Test
    fun `responses strategy injection uses instructions`() = runBlocking {
        val fixture = Fixture(listOf(Policy("p1", "policy", listOf("demo-1"), "policy rule")))
        fixture.gateway.refreshCatalog()
        val body = """{"model":"demo-1","input":"hello"}"""

        val result = fixture.gateway.handle(request("/responses", body)) as HttpResult.Buffered

        assertEquals(200, result.status)
        val messages = fixture.provider.calls.single().messages
        assertTrue(messages.first().role == ChatMessage.Role.System)
        assertTrue(firstText(fixture.provider).contains("policy rule"))
    }

    @Test
    fun `messages strategy injection uses system`() = runBlocking {
        val fixture = Fixture(listOf(Policy("p1", "policy", listOf("demo-1"), "policy rule")))
        fixture.gateway.refreshCatalog()
        val body = """{"model":"demo-1","messages":[{"role":"user","content":"hello"}]}"""

        val result = fixture.gateway.handle(request("/messages", body)) as HttpResult.Buffered

        assertEquals(200, result.status)
        val messages = fixture.provider.calls.single().messages
        assertTrue(messages.first().role == ChatMessage.Role.System)
        assertTrue(firstText(fixture.provider).contains("policy rule"))
    }

    @Test
    fun `malformed protocol body returns bad request`() = runBlocking {
        val fixture = Fixture()
        fixture.gateway.refreshCatalog()

        val result = fixture.gateway.handle(request("/v1/responses", "not-json")) as HttpResult.Buffered

        assertEquals(400, result.status)
        assertTrue(result.body.decodeToString().contains("请求体不是合法"))
    }
}
