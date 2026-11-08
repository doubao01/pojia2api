package io.modelbridge.core.policy

import io.modelbridge.core.Account
import io.modelbridge.core.Gateway
import io.modelbridge.core.GatewayConfig
import io.modelbridge.core.HealthTracker
import io.modelbridge.core.InMemoryAccountStore
import io.modelbridge.core.ModelCatalog
import io.modelbridge.core.ProviderRegistry
import io.modelbridge.core.Router
import io.modelbridge.core.StubContextFactory
import io.modelbridge.core.FakeProvider
import io.modelbridge.core.http.HttpResult
import io.modelbridge.core.http.HttpServer
import io.modelbridge.core.http.JvmHttpClient
import io.modelbridge.core.http.ServerRequest
import io.modelbridge.spi.ChatMessage
import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpRequest
import io.modelbridge.spi.ModelInfo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

/** 不只测试匹配器，验证策略从实际 HTTP 入口到上游收到的消息。 */
class PolicyGatewayTest {
    private val provider = FakeProvider("demo", models = listOf(ModelInfo("demo-1", "demo")))

    private fun gateway(store: PolicyStore, fixed: String? = null): Gateway {
        val registry = ProviderRegistry().apply { register(provider) }
        val accounts = InMemoryAccountStore(listOf(
            Account("a1", "demo", "账号", credential = Credential.ApiKey("test-key"), fixedPolicyId = fixed),
        ))
        val health = HealthTracker()
        return Gateway(
            Router(registry, accounts, health),
            ModelCatalog(registry, accounts), registry, accounts, health,
            StubContextFactory().factory,
            config = GatewayConfig(publicModelPrefix = "mb/"),
            policyStore = store,
        )
    }

    private fun request() = ServerRequest(
        method = "POST", path = "/v1/chat/completions", query = "", version = "HTTP/1.1",
        headers = emptyMap(),
        body = """{"model":"mb/demo-1","messages":[{"role":"system","content":"client rule"},{"role":"user","content":"hello"}]}""".encodeToByteArray(),
    )

    @Test
    fun `实际 HTTP 请求会注入策略并保留客户端 system`() = runBlocking {
        val store = InMemoryPolicyStore(listOf(Policy("p1", "policy", listOf("demo-1"), "policy rule")))
        val gateway = gateway(store)
        gateway.refreshCatalog()
        val server = HttpServer(0, bindAddress = InetAddress.getLoopbackAddress())
        server.start(gateway::handle)
        try {
            val response = JvmHttpClient().execute(HttpRequest(
                method = "POST",
                url = "http://127.0.0.1:${server.boundPort}/v1/chat/completions",
                headers = mapOf("Content-Type" to "application/json", "Connection" to "close"),
                body = request().body,
            ))
            val body = response.bodyBytes().decodeToString()
            assertEquals(200, response.statusCode, body)
            assertTrue(body.contains("chat.completion"))
            val received = provider.calls.single()
            assertEquals(3, received.messages.size)
            assertEquals(ChatMessage.Role.System, received.messages[0].role)
            assertEquals("policy rule", (received.messages[0].content.single() as io.modelbridge.spi.ContentPart.Text).text)
            assertEquals("client rule", (received.messages[1].content.single() as io.modelbridge.spi.ContentPart.Text).text)
            assertEquals("demo-1", received.model)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `账号固定策略优先于模型匹配`() = runTest {
        val store = InMemoryPolicyStore(listOf(
            Policy("exact", "exact", listOf("demo-1"), "exact rule"),
            Policy("fixed", "fixed", listOf("other-model"), "fixed rule"),
        ))
        val gateway = gateway(store, fixed = "fixed")
        gateway.refreshCatalog()
        val response = gateway.handle(request()) as HttpResult.Buffered
        assertEquals(200, response.status)
        val message = provider.calls.single().messages.first()
        assertEquals("fixed rule", (message.content.single() as io.modelbridge.spi.ContentPart.Text).text)
    }

    @Test
    fun `故障转移使用新账号固定策略且不叠加旧策略`() = runTest {
        val calls = mutableListOf<io.modelbridge.spi.ChatRequest>()
        val upstream = object : io.modelbridge.spi.Provider {
            override val id = "demo"
            override val displayName = "demo"
            override val authKind = io.modelbridge.spi.AuthKind.None
            override suspend fun listModels(context: io.modelbridge.spi.ProviderContext) =
                listOf(ModelInfo("demo-1", id))
            override suspend fun chat(context: io.modelbridge.spi.ProviderContext, request: io.modelbridge.spi.ChatRequest): io.modelbridge.spi.ChatResult {
                calls += request
                if (context.baseUrl == "first") {
                    throw io.modelbridge.spi.ProviderException("temporary", retryable = true)
                }
                return provider.chat(context, request)
            }
        }
        val registry = ProviderRegistry().apply { register(upstream) }
        val accounts = InMemoryAccountStore(listOf(
            Account("first", "demo", "first", baseUrl = "first", fixedPolicyId = "p1", priority = 0),
            Account("second", "demo", "second", baseUrl = "second", fixedPolicyId = "p2", priority = 1),
        ))
        val health = HealthTracker()
        val policies = InMemoryPolicyStore(listOf(
            Policy("p1", "first", instruction = "first policy"),
            Policy("p2", "second", instruction = "second policy"),
        ))
        val gateway = Gateway(
            Router(registry, accounts, health), ModelCatalog(registry, accounts), registry, accounts,
            health, StubContextFactory().factory, GatewayConfig(publicModelPrefix = "mb/"),
            policyStore = policies,
        )
        gateway.refreshCatalog()
        assertEquals(200, (gateway.handle(request()) as HttpResult.Buffered).status)
        assertEquals(2, calls.size)
        assertEquals(3, calls[1].messages.size)
        val first = calls[1].messages[0].content.single() as io.modelbridge.spi.ContentPart.Text
        assertEquals("second policy", first.text)
        assertEquals("client rule", (calls[1].messages[1].content.single() as io.modelbridge.spi.ContentPart.Text).text)
    }

    @Test
    fun `停用固定策略后不会注入通用策略`() = runTest {
        val store = InMemoryPolicyStore(listOf(
            Policy("fixed", "fixed", instruction = "fixed rule", enabled = false),
            Policy("general", "general", instruction = "general rule"),
        ))
        val gateway = gateway(store, fixed = "fixed")
        gateway.refreshCatalog()
        assertEquals(200, (gateway.handle(request()) as HttpResult.Buffered).status)
        assertEquals(2, provider.calls.single().messages.size)
    }
}
