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

    @Test
    fun `multi key and identity metadata round trip`() {
        val account = Account(
            id = "relay-1",
            providerId = "custom",
            label = "本机网关",
            credential = Credential.ApiKey("first-key"),
            baseUrl = "http://127.0.0.1:8080/v1",
            priority = 3,
            disabledModels = setOf("demo-2"),
            authRegion = "cn",
            authUid = "uid-1",
            authTenantId = "tenant-1",
            authDomain = "www.codebuddy.cn",
            authNickname = "昵称",
            authTokenKind = "Bearer",
            authExpiresAtMillis = 1_800_000_000_000L,
            fixedPolicyId = "policy-9",
            extraApiKeys = listOf("second-key", "third-key"),
        )
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(Account.serializer(), account)
        val decoded = json.decodeFromString(Account.serializer(), encoded)
        assertEquals(account, decoded)
    }
}
