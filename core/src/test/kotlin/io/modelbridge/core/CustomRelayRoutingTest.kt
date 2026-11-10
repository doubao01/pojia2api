package io.modelbridge.core

import io.modelbridge.spi.AuthKind
import io.modelbridge.spi.Credential
import io.modelbridge.spi.ModelInfo
import io.modelbridge.spi.Provider
import io.modelbridge.spi.ProviderContext
import io.modelbridge.spi.ValidationResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 自定义中转站账号按各自 baseUrl 进入模型目录与路由上下文。 */
class CustomRelayRoutingTest {
    @Test
    fun `custom accounts pass their base url into provider context`() = runTest {
        val provider = BaseUrlObservingProvider()
        val registry = ProviderRegistry().apply { register(provider) }
        val accounts = InMemoryAccountStore(
            listOf(
                Account(
                    id = "custom-8080",
                    providerId = "custom",
                    label = "本机 :8080",
                    credential = Credential.None,
                    baseUrl = "http://127.0.0.1:8080/v1",
                ),
                Account(
                    id = "custom-9000",
                    providerId = "custom",
                    label = "本机 :9000",
                    credential = Credential.None,
                    baseUrl = "http://127.0.0.1:9000/v1",
                ),
            ),
        )
        val catalog = ModelCatalog(registry, accounts)
        val router = Router(registry, accounts)

        val models = catalog.build { provider, account ->
            object : ProviderContext {
                override val credential = account.credential
                override val http = StubHttpClient()
                override val baseUrl = account.baseUrl
            }
        }.models
        router.updateCatalog(models)

        assertEquals(
            listOf("http://127.0.0.1:8080/v1", "http://127.0.0.1:9000/v1"),
            provider.baseUrls,
        )
        val targets = router.resolve("relay-model")
        assertEquals(2, targets.size)
        assertEquals("custom-8080", targets[0].account.id)
        assertEquals("custom-9000", targets[1].account.id)
    }

    private class BaseUrlObservingProvider : Provider {
        override val id = "custom"
        override val displayName = "自定义中转站"
        override val authKind = AuthKind.None
        override val requiresCredential = false
        val baseUrls = mutableListOf<String>()

        override suspend fun listModels(context: ProviderContext): List<ModelInfo> {
            baseUrls += context.baseUrl
            return listOf(ModelInfo("relay-model", id))
        }

        override suspend fun validate(context: ProviderContext): ValidationResult =
            ValidationResult.Valid

        override suspend fun chat(
            context: ProviderContext,
            request: io.modelbridge.spi.ChatRequest,
        ): io.modelbridge.spi.ChatResult = error("chat is not used by routing test")
    }
}
