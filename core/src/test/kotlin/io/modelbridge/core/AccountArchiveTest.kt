package io.modelbridge.core

import io.modelbridge.spi.Credential
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountArchiveTest {
    private val workBuddyFixture = """
        {
          "account": {
            "enterpriseId": "",
            "nickname": "test-user",
            "uid": "fixture-uid"
          },
          "auth": {
            "accessToken": "fixture-access",
            "domain": "www.codebuddy.cn",
            "expiresAt": 1796626464,
            "realm": "cn",
            "refreshToken": "fixture-refresh"
          }
        }
    """.trimIndent()

    @Test
    fun `WorkBuddy single object is accepted and mapped to OAuth metadata`() {
        val accounts = AccountArchive.parseAccounts(workBuddyFixture)

        assertEquals(1, accounts.size)
        val account = accounts.single()
        assertEquals("codebuddy", account.providerId)
        assertEquals("fixture-uid", account.authUid)
        assertEquals("test-user", account.label)
        assertEquals("cn", account.authRegion)
        assertEquals("www.codebuddy.cn", account.authDomain)
        assertTrue(account.credential is Credential.OAuth)
        val oauth = account.credential as Credential.OAuth
        assertEquals("fixture-access", oauth.accessToken)
        assertEquals("fixture-refresh", oauth.refreshToken)
        assertEquals(1796626464L * 1000L, oauth.expiresAtMillis)
    }

    @Test
    fun `original account array with secret object is accepted`() {
        val raw = """
            [{
              "provider": "codebuddy",
              "uid": "fixture-uid",
              "nickname": "test-user",
              "secret": {
                "accessToken": "fixture-access",
                "refreshToken": "fixture-refresh",
                "expiresAt": 1796626464,
                "domain": "www.workbuddy.ai",
                "realm": "global",
                "enterpriseId": "tenant",
                "nickname": "test-user",
                "uid": "fixture-uid"
              }
            }]
        """.trimIndent()

        val account = AccountArchive.parseAccounts(raw).single()

        assertEquals("codebuddy", account.providerId)
        assertEquals("global", account.authRegion)
        assertEquals("tenant", account.authTenantId)
        assertTrue(account.credential is Credential.OAuth)
    }

    @Test
    fun `native ModelBridge account array keeps fixed policy and credential`() {
        val original = Account(
            id = "account-1",
            providerId = "codebuddy",
            label = "WB",
            credential = Credential.OAuth(
                accessToken = "fixture-access",
                refreshToken = "fixture-refresh",
                expiresAtMillis = 123_456_789L,
            ),
            fixedPolicyId = "policy-1",
            authRegion = "cn",
            authUid = "uid-1",
        )

        val reloaded = AccountArchive.parseAccounts(AccountArchive.export(listOf(original))).single()

        assertEquals(original.id, reloaded.id)
        assertEquals(original.fixedPolicyId, reloaded.fixedPolicyId)
        assertEquals(original.credential, reloaded.credential)
        assertEquals("cn", reloaded.authRegion)
    }

    @Test
    fun `export produces array that parses back with one account`() {
        val account = Account(
            id = "q1",
            providerId = "qoder",
            label = "Qoder",
            credential = Credential.OAuth("fixture-access", "fixture-refresh", 222_222_222L),
            authUid = "qoder-uid",
            authRegion = "cn",
            authDomain = "qoder.com.cn",
            authTokenKind = "drt",
        )

        val raw = AccountArchive.export(listOf(account))
        val parsed = AccountArchive.parseAccounts(raw)

        assertTrue(raw.trimStart().startsWith("["))
        assertEquals(1, parsed.size)
        assertEquals("qoder", parsed.single().providerId)
        assertEquals("qoder-uid", parsed.single().authUid)
        assertEquals("drt", parsed.single().authTokenKind)
    }

    @Test
    fun `malformed or empty archive is rejected`() {
        val empty = runCatching { AccountArchive.parseAccounts("[]") }.exceptionOrNull()
        val malformed = runCatching { AccountArchive.parseAccounts("not-json") }.exceptionOrNull()

        assertTrue(empty is IllegalArgumentException)
        assertTrue(malformed != null)
        assertFalse(empty?.message.isNullOrBlank())
    }
}
