package io.modelbridge.core

import io.modelbridge.spi.Credential
import io.modelbridge.spi.ModelInfo
import io.modelbridge.spi.Provider
import io.modelbridge.spi.ProviderContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CredentialRefreshCatalogTest {
    @Test
    fun `invalidate cache rebuilds models with refreshed credential`() = runTest {
        val provider = CredentialObservingProvider()
        val registry = ProviderRegistry().apply { register(provider) }
        val accounts = InMemoryAccountStore(listOf(
            Account(
                id = "a1",
                providerId = "p1",
                label = "账号",
                credential = Credential.OAuth("old-fixture-access", "fixture-refresh", NOW + 60_000),
                authExpiresAtMillis = NOW + 60_000,
            ),
        ))
        val catalog = ModelCatalog(registry, accounts, clockMillis = { NOW })
        val factory: (Provider, Account) -> ProviderContext = { _, account ->
            object : ProviderContext {
                override val credential = account.credential
                override val http = StubHttpClient()
                override val baseUrl = account.baseUrl
            }
        }

        assertEquals(listOf("old-fixture-access"), catalog.build(factory).models.map { it.id })

        accounts.upsert(accounts.find("a1")!!.copy(
            credential = Credential.OAuth("new-fixture-access", "new-fixture-refresh", NOW + 3_600_000),
            authExpiresAtMillis = NOW + 3_600_000,
        ))
        catalog.invalidateCache()

        assertEquals(listOf("new-fixture-access"), catalog.build(factory).models.map { it.id })
    }

    private class CredentialObservingProvider : Provider {
        override val id = "p1"
        override val displayName = "p1"
        override val authKind = io.modelbridge.spi.AuthKind.OAuth
        override val requiresCredential = true

        override suspend fun listModels(context: ProviderContext): List<ModelInfo> {
            val credential = context.credential as Credential.OAuth
            return listOf(ModelInfo(credential.accessToken, id))
        }

        override suspend fun chat(
            context: ProviderContext,
            request: io.modelbridge.spi.ChatRequest,
        ): io.modelbridge.spi.ChatResult = error("chat is not used by catalog tests")
    }

    private companion object {
        const val NOW = 2_000_000_000_000L
    }
}
