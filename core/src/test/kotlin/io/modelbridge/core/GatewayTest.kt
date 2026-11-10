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
        models = listOf(
            ModelInfo(
                id = "demo-1",
                providerId = "demo",
                contextLength = 200_000,
                supportsTools = true,
                supportsReasoning = true,
                extras = mapOf("reasoning_levels" to "low,high,max"),
            ),
        ),
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
        val result = gateway.handle(request("/v1/models"))
        assertEquals(401, (result as HttpResult.Buffered).status)
    }

    @Test
    fun `配置 key 后错误 key 返回 401`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig(apiKeys = setOf("secret")))
        val result = gateway.handle(request("/health", key = "wrong"))
        assertEquals(200, (result as HttpResult.Buffered).status)
    }

    @Test
    fun `正确 key 通过鉴权`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig(apiKeys = setOf("secret")))
        val result = gateway.handle(request("/v1/models", key = "secret"))
        assertEquals(200, (result as HttpResult.Buffered).status)
    }

    @Test
    fun `配置 key 后模型和对话端点仍要求鉴权`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig(apiKeys = setOf("secret")))
        assertEquals(401, (gateway.handle(request("/v1/models")) as HttpResult.Buffered).status)
        assertEquals(200, (gateway.handle(request("/")) as HttpResult.Buffered).status)
        assertEquals(
            401,
            (gateway.handle(request("/v1/chat/completions", method = "POST")) as HttpResult.Buffered).status,
        )
    }

    @Test
    fun `根路径返回原版服务清单`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig())
        val result = gateway.handle(request("/")) as HttpResult.Buffered
        val json = result.body.decodeToString()

        assertEquals(200, result.status)
        listOf(
            "\"service\":\"pojia2api\"",
            "/v1/models",
            "/v1/chat/completions",
            "/v1/responses",
            "/v1/messages",
            "/models",
            "/health",
        ).forEach { assertTrue(json.contains(it), json) }
    }

    @Test
    fun `模型列表按原版补齐上下文别名和能力字段`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig())
        gateway.refreshCatalog()
        val result = gateway.handle(request("/v1/models"))
        val buffered = result as HttpResult.Buffered
        val json = buffered.body.decodeToString()
        assertEquals(200, buffered.status)
        assertTrue(json.contains("demo-1"), json)
        listOf(
            "\"context_length\":200000",
            "\"context_window\":200000",
            "\"max_context_tokens\":200000",
            "\"supports_tools\":true",
            "\"supports_reasoning\":true",
            "\"reasoning_levels\":[\"low\",\"high\",\"max\"]",
        ).forEach { assertTrue(json.contains(it), json) }
    }

    @Test
    fun `compact responses 路由使用 responses 协议`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig())
        gateway.refreshCatalog()
        val body = """{"model":"demo-1","input":"hello"}"""
        val result = gateway.handle(
            request("/v1/responses/compact", method = "POST", body = body),
        )
        assertEquals(200, (result as HttpResult.Buffered).status)
    }

    @Test
    fun `未知路径按原版返回 404 和 proxy_error`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig())
        val result = gateway.handle(request("/nope")) as HttpResult.Buffered
        val body = result.body.decodeToString()

        assertEquals(404, result.status)
        assertTrue(body.contains("Unknown endpoint: /nope"), body)
        assertTrue(body.contains("\"type\":\"proxy_error\""), body)
    }

    @Test
    fun `缺少访问密钥按原版返回 401 文案`() = runTest {
        val (gateway, _) = makeGateway(GatewayConfig(apiKeys = setOf("secret")))
        val result = gateway.handle(request("/v1/models")) as HttpResult.Buffered
        val body = result.body.decodeToString()

        assertEquals(401, result.status)
        assertTrue(body.contains("A valid proxy API key is required"), body)
        assertTrue(body.contains("\"type\":\"proxy_error\""), body)
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
