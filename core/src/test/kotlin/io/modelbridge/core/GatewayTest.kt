package io.modelbridge.core

import io.modelbridge.core.http.HttpResult
import io.modelbridge.core.http.ServerRequest
import io.modelbridge.spi.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** 网关的端到端测试：鉴权、模型列表、错误映射。 */
class GatewayTest {

    private val provider = FakeProvider(
        "demo",
        displayName = "演示",
        models = listOf(ModelInfo("demo-1", "demo")),
    )

    private fun makeGateway(config: GatewayConfig): Pair<Gateway, StubContextFactory> {
        val registry = ProviderRegistry().apply { register(provider) }
        val accounts = InMemoryAccountStore(
            listOf(Account("a1", "demo", "账号", credential = Credential.ApiKey("k"))),
        )
        val health = HealthTracker()
        val catalog = ModelCatalog(registry, accounts)
        val router = Router(registry, accounts, health)
        val ctxFactory = StubContextFactory()
        val gateway = Gateway(
            router = router,
            catalog = catalog,
            registry = registry,
            accounts = accounts,
            health = health,
            contextFactory = ctxFactory.factory,
            config = config,
        )
        return gateway to ctxFactory
    }

    private fun request(
        path: String,
        method: String = "GET",
        key: String? = null,
        body: String = "",
    ): ServerRequest = ServerRequest(
        method = method,
        path = path,
        query = "",
        version = "HTTP/1.1",
        headers = buildMap {
            if (key != null) put("authorization", "Bearer $key")
        },
        body = body.encodeToByteArray(),
    )

    @Test
    fun `未配置 key 时不鉴权`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig())
        val result = gateway.handle(request("/health"))
        val buffered = result as HttpResult.Buffered
        assertEquals(200, buffered.status)
    }

    @Test
    fun `配置 key 后缺少 key 返回 401`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig(apiKeys = setOf("secret")))
        val result = gateway.handle(request("/health"))
        assertEquals(401, (result as HttpResult.Buffered).status)
    }

    @Test
    fun `配置 key 后错误 key 返回 401`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig(apiKeys = setOf("secret")))
        val result = gateway.handle(request("/health", key = "wrong"))
        assertEquals(401, (result as HttpResult.Buffered).status)
    }

    @Test
    fun `正确 key 通过鉴权`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig(apiKeys = setOf("secret")))
        val result = gateway.handle(request("/health", key = "secret"))
        assertEquals(200, (result as HttpResult.Buffered).status)
    }

    @Test
    fun `模型列表只含启用账号`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig())
        gateway.refreshCatalog()
        val result = gateway.handle(request("/v1/models"))
        val buffered = result as HttpResult.Buffered
        val json = buffered.body.decodeToString()
        assertEquals(200, buffered.status)
        assertTrue(json.contains("demo-1"), json)
    }

    @Test
    fun `未知路径返回 404`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig())
        val result = gateway.handle(request("/nope"))
        assertEquals(404, (result as HttpResult.Buffered).status)
    }

    @Test
    fun `chat 请求体为空返回 400`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig())
        val result = gateway.handle(request("/v1/chat/completions", method = "POST"))
        assertEquals(400, (result as HttpResult.Buffered).status)
    }

    @Test
    fun `chat 调用未知模型返回 404`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig())
        gateway.refreshCatalog()
        val body = """{"model":"missing","messages":[{"role":"user","content":"hi"}]}"""
        val result = gateway.handle(request("/v1/chat/completions", method = "POST", body = body))
        assertEquals(404, (result as HttpResult.Buffered).status)
    }

    @Test
    fun `chat 成功返回完整响应`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig())
        gateway.refreshCatalog()
        val body = """{"model":"demo-1","messages":[{"role":"user","content":"hi"}]}"""
        val result = gateway.handle(request("/v1/chat/completions", method = "POST", body = body))
        val buffered = result as HttpResult.Buffered
        assertEquals(200, buffered.status)
        val json = buffered.body.decodeToString()
        assertTrue(json.contains("chat.completion"), json)
        assertTrue(json.contains("ok"), json)
    }

    @Test
    fun `上行全部失败时返回 503`() = runTest {
        val flaky = FakeProvider(
            "flaky",
            models = listOf(ModelInfo("f-1", "flaky")),
            chatFailWith = ProviderException("临时故障", retryable = true),
        )
        val registry = ProviderRegistry().apply { registerAll(listOf(flaky, provider)) }
        val accounts = InMemoryAccountStore(
            listOf(
                Account("a-flaky", "flaky", "坏", credential = Credential.ApiKey("k")),
                Account("a-ok", "demo", "好", credential = Credential.ApiKey("k")),
            ),
        )
        val health = HealthTracker()
        val catalog = ModelCatalog(registry, accounts)
        val router = Router(registry, accounts, health)
        val gateway = Gateway(router, catalog, registry, accounts, health, StubContextFactory().factory)

        gateway.refreshCatalog()
        val body = """{"model":"f-1","messages":[{"role":"user","content":"hi"}]}"""
        val result = gateway.handle(request("/v1/chat/completions", method = "POST", body = body))
        val buffered = result as HttpResult.Buffered
        // f-1 只有 flaky 一个账号，失败后应判定为全部不可用
        assertEquals(503, buffered.status)
    }

    @Test
    fun `Gateway 各错误类型的状态码映射`() {
        assertEquals(400, GatewayError.BadRequest("x").statusCode)
        assertEquals(401, GatewayError.Unauthorized().statusCode)
        assertEquals(404, GatewayError.NotFound("x").statusCode)
        assertEquals(413, GatewayError.TooLarge("x").statusCode)
        assertEquals(503, GatewayError.UpstreamUnavailable("x").statusCode)
        assertEquals(500, GatewayError.Internal("x").statusCode)
    }
}
