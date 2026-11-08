package io.modelbridge.core

import io.modelbridge.core.http.HttpResult
import io.modelbridge.core.http.ServerRequest
import io.modelbridge.spi.AuthKind
import io.modelbridge.spi.ChatRequest
import io.modelbridge.spi.ChatResult
import io.modelbridge.spi.Credential
import io.modelbridge.spi.ModelInfo
import io.modelbridge.spi.Provider
import io.modelbridge.spi.ProviderContext
import io.modelbridge.spi.ProviderException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 设置项「重试次数」与「请求超时」必须真的影响转发行为。
 *
 * 之前这两项只写进 SharedPreferences，网关构造时并不读取，
 * 所以界面改了也没有任何效果。这里从网关入口验证真实行为。
 */
class GatewayRetryTest {

    /** 前 [failures] 次调用抛可重试异常，之后成功；记录调用次数。 */
    private class FlakyProvider(
        override val id: String = "flaky",
        private val failures: Int,
        private val delayMillis: Long = 0,
    ) : Provider {
        override val displayName: String = id
        override val authKind: AuthKind = AuthKind.ApiKey
        var calls: Int = 0
            private set

        override suspend fun listModels(context: ProviderContext): List<ModelInfo> =
            listOf(ModelInfo("flaky-1", id))

        override suspend fun chat(context: ProviderContext, request: ChatRequest): ChatResult {
            calls++
            if (delayMillis > 0) delay(delayMillis)
            if (calls <= failures) {
                throw ProviderException("上游抖动", statusCode = 503, retryable = true)
            }
            return ChatResult.Complete(
                io.modelbridge.spi.ChatResponse(
                    id = "ok-${calls}",
                    model = request.model,
                    choices = listOf(
                        io.modelbridge.spi.ChatResponse.Choice(
                            index = 0,
                            message = io.modelbridge.spi.ChatMessage(
                                role = io.modelbridge.spi.ChatMessage.Role.Assistant,
                                content = listOf(io.modelbridge.spi.ContentPart.Text("ok")),
                            ),
                            finishReason = "stop",
                        ),
                    ),
                ),
            )
        }
    }

    private fun gateway(provider: Provider, config: GatewayConfig): Gateway {
        val registry = ProviderRegistry().apply { register(provider) }
        val accounts = InMemoryAccountStore(
            listOf(Account("a1", provider.id, "账号", credential = Credential.ApiKey("k"))),
        )
        val health = HealthTracker(
            // 阈值调高，避免冷却提前中断重试计数
            failureThreshold = 99,
            cooldownMillis = 60_000,
        )
        return Gateway(
            Router(registry, accounts, health),
            ModelCatalog(registry, accounts),
            registry,
            accounts,
            health,
            StubContextFactory().factory,
            config = config,
        )
    }

    private fun request() = ServerRequest(
        method = "POST",
        path = "/v1/chat/completions",
        query = "",
        version = "HTTP/1.1",
        headers = emptyMap(),
        body = """{"model":"flaky-1","messages":[{"role":"user","content":"hi"}]}""".encodeToByteArray(),
    )

    @Test
    fun `重试次数为 0 时只调用一次上游`() = runBlocking {
        val provider = FlakyProvider(failures = 1)
        val gateway = gateway(provider, GatewayConfig(maxRetries = 0))
        gateway.refreshCatalog()

        val result = gateway.handle(request())

        assertEquals(1, provider.calls, "maxRetries=0 不应重试")
        assertTrue(result is HttpResult.Buffered && result.status >= 500, "应返回上游失败")
    }

    @Test
    fun `重试次数为 2 时同一账号会被再试并最终成功`() = runBlocking {
        val provider = FlakyProvider(failures = 2)
        val gateway = gateway(provider, GatewayConfig(maxRetries = 2))
        gateway.refreshCatalog()

        val result = gateway.handle(request())

        assertEquals(3, provider.calls, "失败 2 次后第 3 次应成功")
        assertEquals(200, (result as HttpResult.Buffered).status)
    }

    @Test
    fun `重试次数用尽后返回失败而不是无限重试`() = runBlocking {
        val provider = FlakyProvider(failures = 99)
        val gateway = gateway(provider, GatewayConfig(maxRetries = 1))
        gateway.refreshCatalog()

        val result = gateway.handle(request())

        assertEquals(2, provider.calls, "maxRetries=1 表示最多两次调用")
        assertTrue((result as HttpResult.Buffered).status >= 500)
    }

    @Test
    fun `请求超时会中断慢上游并返回失败`() = runBlocking {
        // 上游要 5 秒，超时设 1 秒：必须提前失败，不能等到上游返回
        val provider = FlakyProvider(failures = 0, delayMillis = 5_000)
        val gateway = gateway(
            provider,
            GatewayConfig(maxRetries = 0, requestTimeoutSeconds = 1),
        )
        gateway.refreshCatalog()

        val started = System.currentTimeMillis()
        val result = gateway.handle(request())
        val elapsed = System.currentTimeMillis() - started

        assertTrue((result as HttpResult.Buffered).status >= 500, "超时应返回失败")
        assertTrue(elapsed < 4_000, "应在超时附近返回，实际耗时 ${elapsed}ms")
    }

    @Test
    fun `超时设为 0 表示不限，慢上游可以正常完成`() = runBlocking {
        val provider = FlakyProvider(failures = 0, delayMillis = 300)
        val gateway = gateway(
            provider,
            GatewayConfig(maxRetries = 0, requestTimeoutSeconds = 0),
        )
        gateway.refreshCatalog()

        val result = gateway.handle(request())

        assertEquals(200, (result as HttpResult.Buffered).status)
    }
}
