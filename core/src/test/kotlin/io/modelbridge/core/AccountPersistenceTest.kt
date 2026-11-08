package io.modelbridge.core

import io.modelbridge.spi.Credential
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 存储必须能真正写入并重新读取所有凭据，而不只是 Account 有注解。 */
class AccountPersistenceTest {
    @Test
    fun `all credential types round trip with policy binding`() {
        val credentials = listOf(
            Credential.None,
            Credential.ApiKey("test-key"),
            Credential.Cookies("test_cookie=value"),
            Credential.OAuth("test-access", "test-refresh", 123456L),
            Credential.Session("test-phone", "test-session"),
        )
        val accounts = credentials.mapIndexed { index, credential ->
            Account(
                id = "a$index", providerId = "demo", label = "test account",
                credential = credential, fixedPolicyId = "policy-1",
            )
        }
        val json = Json { encodeDefaults = true }
        val serializer = ListSerializer(Account.serializer())
        val encoded = json.encodeToString(serializer, accounts)
        val decoded = json.decodeFromString(serializer, encoded)
        assertEquals(accounts, decoded)
    }
}
