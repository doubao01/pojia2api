package io.modelbridge.core

import io.modelbridge.spi.Credential
import io.modelbridge.spi.ModelInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CredentialExpiryTest {
    private val now = 2_000_000_000_000L

    @Test
    fun `expired session is detected`() {
        val expired = Account(
            id = "loomy-1",
            providerId = "loomy",
            label = "Loomy",
            credential = Credential.Session("13800000000", "fixture-session"),
            authExpiresAtMillis = now - 1,
        )
        val valid = expired.copy(authExpiresAtMillis = now + 1)
        val unknown = expired.copy(authExpiresAtMillis = 0)

        assertTrue(expired.isExpired(now))
        assertFalse(valid.isExpired(now))
        assertFalse(unknown.isExpired(now))
    }

    @Test
    fun `account archive keeps auth expiry`() {
        val account = Account(
            id = "loomy-1",
            providerId = "loomy",
            label = "Loomy",
            credential = Credential.Session("13800000000", "fixture-session"),
            authExpiresAtMillis = 4_000_000_000_000L,
        )

        val raw = AccountArchive.export(listOf(account))
        val parsed = AccountArchive.parseAccounts(raw).single()

        assertTrue(raw.contains("expiresAtMillis"))
        assertEquals(4_000_000_000_000L, parsed.authExpiresAtMillis)
        assertEquals(Credential.Session("13800000000", "fixture-session"), parsed.credential)
    }

    @Test
    fun `expired accounts are excluded from catalog and routes`() = runBlocking {
        val provider = FakeProvider("demo", models = listOf(ModelInfo("demo-1", "demo")))
        val registry = ProviderRegistry().apply { register(provider) }
        val accounts = InMemoryAccountStore(
            listOf(
                Account(
                    id = "expired",
                    providerId = "demo",
                    label = "过期",
                    credential = Credential.Session("13800000000", "fixture-session"),
                    authExpiresAtMillis = now - 1,
                ),
                Account(
                    id = "valid",
                    providerId = "demo",
                    label = "可用",
                    credential = Credential.ApiKey("fixture-key"),
                    authExpiresAtMillis = now + 1,
                ),
            ),
        )
        val catalog = ModelCatalog(registry, accounts, clockMillis = { now })
        val models = catalog.build(StubContextFactory().factory).models
        val router = Router(registry, accounts, clockMillis = { now })
        router.updateCatalog(models)

        val targets = router.resolve("demo-1")

        assertEquals(1, models.size)
        assertEquals(1, targets.size)
        assertEquals("valid", targets.single().account.id)
    }

    @Test
    fun `all expired accounts produce no usable targets`() = runBlocking {
        val provider = FakeProvider("demo", models = listOf(ModelInfo("demo-1", "demo")))
        val registry = ProviderRegistry().apply { register(provider) }
        val accounts = InMemoryAccountStore(
            listOf(
                Account(
                    id = "expired",
                    providerId = "demo",
                    label = "过期",
                    credential = Credential.Session("13800000000", "fixture-session"),
                    authExpiresAtMillis = now - 1,
                ),
            ),
        )
        val catalog = ModelCatalog(registry, accounts, clockMillis = { now })

        val models = catalog.build(StubContextFactory().factory).models

        assertTrue(models.isEmpty())
    }
}
