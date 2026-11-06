package io.modelbridge.core

import io.modelbridge.spi.AuthKind
import io.modelbridge.spi.ChatMessage
import io.modelbridge.spi.ChatRequest
import io.modelbridge.spi.ChatResult
import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import io.modelbridge.spi.HttpResponse
import io.modelbridge.spi.ModelInfo
import io.modelbridge.spi.Provider
import io.modelbridge.spi.ProviderContext
import io.modelbridge.spi.ProviderException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 静态桩 HTTP 客户端：按构造时给定的规则应答，便于断言请求内容。
 */
class StubHttpClient : HttpClient {
    val requests = mutableListOf<HttpRequest>()

    /** key = 请求路径或方法+路径，value = (状态码, 响应体) */
    var handler: (HttpRequest) -> Pair<Int, String> = { _ -> 200 to "{}" }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        val (status, body) = handler(request)
        val bytes = body.encodeToByteArray()
        return object : HttpResponse {
            override val statusCode = status
            override val headers: Map<String, String> = emptyMap()
            override suspend fun bodyBytes() = bytes
            override fun bodyLines() = body.split("\n").asSequence()
        }
    }
}

/** 固定返回模型的供应商，用于测试聚合与过滤。 */
class FakeProvider(
    override val id: String,
    override val displayName: String = id,
    override val authKind: AuthKind = AuthKind.ApiKey,
    private val models: List<ModelInfo> = emptyList(),
    private val failWith: ProviderException? = null,
    /** 对话调用时才抛异常，不影响模型列表拉取。 */
    private val chatFailWith: ProviderException? = null,
) : Provider {
    val calls = mutableListOf<ChatRequest>()

    override suspend fun listModels(context: ProviderContext): List<ModelInfo> {
        failWith?.let { throw it }
        return models
    }

    override suspend fun chat(context: ProviderContext, request: ChatRequest): ChatResult {
        calls += request
        (chatFailWith ?: failWith)?.let { throw it }
        return ChatResult.Complete(
            io.modelbridge.spi.ChatResponse(
                id = "test-id",
                model = request.model,
                choices = listOf(
                    io.modelbridge.spi.ChatResponse.Choice(
                        index = 0,
                        message = ChatMessage(
                            role = ChatMessage.Role.Assistant,
                            content = listOf(io.modelbridge.spi.ContentPart.Text("ok")),
                        ),
                        finishReason = "stop",
                    ),
                ),
            ),
        )
    }
}

class StubContextFactory {
    val contexts = mutableListOf<Pair<Provider, Account>>()

    val factory: (Provider, Account) -> ProviderContext = { provider, account ->
        contexts += provider to account
        object : ProviderContext {
            override val credential = account.credential
            override val http = StubHttpClient()
            override val baseUrl = account.baseUrl
        }
    }
}

class ModelCatalogTest {

    @Test
    fun `停用账号的模型不进入目录`() = runTest {
        val provider = FakeProvider("p1", models = listOf(model("m1", "p1")))
        val registry = ProviderRegistry().apply { register(provider) }
        val accounts = InMemoryAccountStore(listOf(account("a1", "p1", enabled = false)))

        val result = ModelCatalog(registry, accounts).build(StubContextFactory().factory)

        assertTrue(result.models.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `没有账号的供应商不出现在目录`() = runTest {
        val provider = FakeProvider("p1", models = listOf(model("m1", "p1")))
        val registry = ProviderRegistry().apply { register(provider) }

        val result = ModelCatalog(registry, InMemoryAccountStore()).build(StubContextFactory().factory)

        assertTrue(result.models.isEmpty())
    }

    @Test
    fun `启用账号的模型进入目录`() = runTest {
        val provider = FakeProvider(
            "p1",
            models = listOf(model("m1", "p1"), model("m2", "p1")),
        )
        val registry = ProviderRegistry().apply { register(provider) }
        val accounts = InMemoryAccountStore(listOf(account("a1", "p1")))

        val result = ModelCatalog(registry, accounts).build(StubContextFactory().factory)

        assertEquals(listOf("m1", "m2"), result.models.map { it.id })
    }

    @Test
    fun `账号级禁用模型被剔除`() = runTest {
        val provider = FakeProvider("p1", models = listOf(model("m1", "p1"), model("m2", "p1")))
        val registry = ProviderRegistry().apply { register(provider) }
        val accounts = InMemoryAccountStore(
            listOf(account("a1", "p1", disabledModels = setOf("m2"))),
        )

        val result = ModelCatalog(registry, accounts).build(StubContextFactory().factory)

        assertEquals(listOf("m1"), result.models.map { it.id })
    }

    @Test
    fun `单个供应商失败不影响其它供应商`() = runTest {
        val ok = FakeProvider("ok", models = listOf(model("m1", "ok")))
        val bad = FakeProvider(
            "bad",
            models = listOf(model("m2", "bad")),
            failWith = ProviderException("挂了", retryable = true),
        )
        val registry = ProviderRegistry().apply { registerAll(listOf(ok, bad)) }
        val accounts = InMemoryAccountStore(
            listOf(account("a1", "ok"), account("a2", "bad")),
        )

        val result = ModelCatalog(registry, accounts).build(StubContextFactory().factory)

        assertEquals(listOf("m1"), result.models.map { it.id })
        assertEquals(1, result.errors.size)
        assertEquals("bad", result.errors.first().providerId)
    }

    @Test
    fun `重复注册同一 id 抛异常`() {
        val registry = ProviderRegistry().apply { register(FakeProvider("p1")) }
        assertThrows<IllegalStateException> { registry.register(FakeProvider("p1")) }
    }

    @Test
    fun `provider id 必须小写`() {
        assertThrows<IllegalArgumentException> {
            ProviderRegistry().register(FakeProvider("P1"))
        }
    }

    private fun model(id: String, providerId: String) =
        ModelInfo(id = id, providerId = providerId)

    private fun account(
        id: String,
        providerId: String,
        enabled: Boolean = true,
        disabledModels: Set<String> = emptySet(),
    ) = Account(
        id = id,
        providerId = providerId,
        label = id,
        enabled = enabled,
        credential = Credential.ApiKey("key"),
        disabledModels = disabledModels,
    )
}

class HealthTrackerTest {

    @Test
    fun `连续失败到达阈值后进入冷却`() = runTest {
        val now = MutableList(1) { 1000L }
        val tracker = HealthTracker(
            failureThreshold = 3,
            cooldownMillis = 10_000,
            clock = { now[0] },
        )

        assertTrue(tracker.isAvailable("a1"))
        assertFalse(tracker.recordFailure("a1")) // 1 次
        assertTrue(tracker.isAvailable("a1"))
        assertFalse(tracker.recordFailure("a1")) // 2 次
        assertTrue(tracker.isAvailable("a1"))
        assertTrue(tracker.recordFailure("a1")) // 3 次 -> 冷却
        assertFalse(tracker.isAvailable("a1"))
    }

    @Test
    fun `冷却结束后恢复可用`() = runTest {
        val now = MutableList(1) { 0L }
        val tracker = HealthTracker(
            failureThreshold = 1,
            cooldownMillis = 10_000,
            clock = { now[0] },
        )

        tracker.recordFailure("a1")
        assertFalse(tracker.isAvailable("a1"))
        assertEquals(10_000, tracker.remainingCooldownMillis("a1"))

        now[0] = 9_999
        assertFalse(tracker.isAvailable("a1"))

        now[0] = 10_000
        assertTrue(tracker.isAvailable("a1"))
    }

    @Test
    fun `成功调用立即恢复`() = runTest {
        val tracker = HealthTracker(failureThreshold = 2, cooldownMillis = 10_000)
        tracker.recordFailure("a1")
        tracker.recordSuccess("a1")
        assertTrue(tracker.isAvailable("a1"))
    }

    @Test
    fun `未失败的账号剩余冷却为零`() = runTest {
        val tracker = HealthTracker()
        assertEquals(0, tracker.remainingCooldownMillis("unknown"))
    }
}

class RouterTest {

    @Test
    fun `未知模型抛 NotFound`() = runTest {
        val router = Router(ProviderRegistry(), InMemoryAccountStore())
        val e = assertThrows<GatewayError.NotFound> { router.resolve("nope") }
        assertTrue(e.message!!.contains("nope"))
    }

    @Test
    fun `冷却中的账号被跳过`() = runTest {
        val now = MutableList(1) { 0L }
        val provider = FakeProvider("p1", models = listOf(model("m1", "p1")))
        val registry = ProviderRegistry().apply { register(provider) }
        val accounts = InMemoryAccountStore(
            listOf(
                account("hot", "p1", priority = 0),
                account("cold", "p1", priority = 1),
            ),
        )
        val health = HealthTracker(failureThreshold = 1, cooldownMillis = 10_000, clock = { now[0] })
        health.recordFailure("hot")
        assertTrue(health.isAvailable("cold"))

        val router = Router(registry, accounts, health)
        router.updateCatalog(listOf(model("m1", "p1")))

        val targets = router.resolve("m1")
        assertEquals(listOf("cold"), targets.map { it.account.id })
    }

    @Test
    fun `全部冷却时抛 UpstreamUnavailable`() = runTest {
        val provider = FakeProvider("p1", models = listOf(model("m1", "p1")))
        val registry = ProviderRegistry().apply { register(provider) }
        val accounts = InMemoryAccountStore(listOf(account("a1", "p1")))
        val health = HealthTracker(failureThreshold = 1, cooldownMillis = 10_000)
        health.recordFailure("a1")

        val router = Router(registry, accounts, health)
        router.updateCatalog(listOf(model("m1", "p1")))

        val e = assertThrows<GatewayError.UpstreamUnavailable> { router.resolve("m1") }
        assertEquals(30, e.retryAfterSeconds)
    }

    @Test
    fun `候选按账号优先级排序`() = runTest {
        val provider = FakeProvider("p1", models = listOf(model("m1", "p1")))
        val registry = ProviderRegistry().apply { register(provider) }
        val accounts = InMemoryAccountStore(
            listOf(
                account("low", "p1", priority = 10),
                account("high", "p1", priority = 1),
            ),
        )

        val router = Router(registry, accounts)
        router.updateCatalog(listOf(model("m1", "p1")))

        val targets = router.resolve("m1")
        assertEquals(listOf("high", "low"), targets.map { it.account.id })
    }

    private fun model(id: String, providerId: String) =
        ModelInfo(id = id, providerId = providerId)

    private fun account(
        id: String,
        providerId: String,
        enabled: Boolean = true,
        priority: Int = 0,
    ) = Account(
        id = id,
        providerId = providerId,
        label = id,
        enabled = enabled,
        credential = Credential.ApiKey("key"),
        priority = priority,
    )
}
