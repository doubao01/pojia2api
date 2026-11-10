package io.modelbridge.core

import io.modelbridge.core.http.HttpResult
import io.modelbridge.core.http.ServerRequest
import io.modelbridge.spi.Credential
import io.modelbridge.spi.ModelInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpstreamHintRoutingTest {
    private class Fixture {
        val providerA = FakeProvider("alpha", models = listOf(ModelInfo("demo-1", "alpha")))
        val providerB = FakeProvider("beta", models = listOf(ModelInfo("demo-1", "beta")))
        val registry = ProviderRegistry().apply {
            register(providerA)
            register(providerB)
        }
        val accounts = InMemoryAccountStore(
            listOf(
                Account("a1", "alpha", "猫爪", credential = Credential.ApiKey("fixture-key"), priority = 0),
                Account("b1", "beta", "另一家", credential = Credential.ApiKey("fixture-key"), priority = 1),
            ),
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
        )
    }

    private fun request(model: String) = ServerRequest(
        method = "POST",
        path = "/v1/chat/completions",
        query = "",
        version = "HTTP/1.1",
        headers = emptyMap(),
        body = """{"model":"$model","messages":[{"role":"user","content":"hello"}]}""".encodeToByteArray(),
    )

    @Test
    fun `catalog exposes duplicate model ids for different providers`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.gateway.refreshCatalog()

        assertEquals(2, result.models.size)
        assertEquals(setOf("alpha", "beta"), result.models.map { it.providerId }.toSet())
    }

    @Test
    fun `models endpoint deduplicates ids exposed to clients`() = runBlocking {
        val fixture = Fixture()
        fixture.gateway.refreshCatalog()

        val response = fixture.gateway.handle(
            ServerRequest(
                method = "GET",
                path = "/v1/models",
                query = "",
                version = "HTTP/1.1",
                headers = emptyMap(),
                body = ByteArray(0),
            ),
        ) as HttpResult.Buffered
        val body = response.body.decodeToString()
        val ids = kotlinx.serialization.json.Json.parseToJsonElement(body)
            .jsonObject["data"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }

        assertEquals(200, response.status)
        assertEquals(listOf("demo-1"), ids)
    }

    @Test
    fun `plain model id uses first available provider`() = runBlocking {
        val fixture = Fixture()
        fixture.gateway.refreshCatalog()

        val response = fixture.gateway.handle(request("demo-1"))

        assertTrue(response is HttpResult.Buffered)
        assertEquals(200, (response as HttpResult.Buffered).status)
        assertEquals(1, fixture.providerA.calls.size)
        assertEquals(0, fixture.providerB.calls.size)
        assertEquals("demo-1", fixture.providerA.calls.single().model)
    }

    @Test
    fun `at nickname routes only to matching upstream and strips suffix`() = runBlocking {
        val fixture = Fixture()
        fixture.gateway.refreshCatalog()

        val response = fixture.gateway.handle(request("demo-1@另一家"))

        assertEquals(200, (response as HttpResult.Buffered).status)
        assertEquals(0, fixture.providerA.calls.size)
        assertEquals(1, fixture.providerB.calls.size)
        assertEquals("demo-1", fixture.providerB.calls.single().model)
    }

    @Test
    fun `unknown nickname returns not found`() = runBlocking {
        val fixture = Fixture()
        fixture.gateway.refreshCatalog()

        val response = fixture.gateway.handle(request("demo-1@不存在"))

        assertEquals(404, (response as HttpResult.Buffered).status)
        assertEquals(0, fixture.providerA.calls.size)
        assertEquals(0, fixture.providerB.calls.size)
    }
}
