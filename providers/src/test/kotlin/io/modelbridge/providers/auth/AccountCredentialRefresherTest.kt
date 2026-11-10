package io.modelbridge.providers.auth

import io.modelbridge.core.Account
import io.modelbridge.core.InMemoryAccountStore
import io.modelbridge.spi.Credential
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountCredentialRefresherTest {
    @Test
    fun `due enabled oauth account is refreshed, persisted and skipped on next scan`() = runTest {
        var now = NOW
        var calls = 0
        val account = dueAccount(providerId = "qoder")
        val store = InMemoryAccountStore(listOf(account, validAccount(), disabledAccount()))
        val client = FakeRefreshClient { credentials ->
            calls++
            DeviceAuthRefreshResult.Updated(credentials.copy(
                credential = credentials.credential.copy(
                    accessToken = "new-fixture-access",
                    refreshToken = "new-fixture-refresh",
                    expiresAtMillis = now + 3_600_000,
                ),
                nickname = "refreshed-fixture-name",
                tenantId = "fixture-tenant",
            ))
        }
        val refresher = refresher(store, client, clock = { now })

        val first = refresher.refreshDue()
        val saved = store.find(account.id)!!
        val savedCredential = saved.credential as Credential.OAuth

        assertEquals(3, first.scanned)
        assertEquals(1, first.attempted)
        assertEquals(1, first.updated)
        assertEquals(2, first.skipped)
        assertEquals("new-fixture-access", savedCredential.accessToken)
        assertEquals("new-fixture-refresh", savedCredential.refreshToken)
        assertEquals(now + 3_600_000, savedCredential.expiresAtMillis)
        assertEquals(now + 3_600_000, saved.authExpiresAtMillis)
        assertEquals("fixture-tenant", saved.authTenantId)
        assertEquals("refreshed-fixture-name", saved.authNickname)
        assertEquals("cn", saved.authRegion)
        assertEquals("fixture-user", saved.authUid)
        assertEquals("drt", saved.authTokenKind)

        val second = refresher.refreshDue()
        assertEquals(1, calls)
        assertEquals(0, second.attempted)
        assertEquals(3, second.skipped)
    }

    @Test
    fun `omitted or stale replacement expiry preserves the known deadline and backs off`() = runTest {
        var now = NOW
        var calls = 0
        val store = InMemoryAccountStore(listOf(dueAccount()))
        val client = FakeRefreshClient { credentials ->
            calls++
            DeviceAuthRefreshResult.Updated(credentials.copy(
                credential = credentials.credential.copy(
                    accessToken = "rotated-fixture-access-$calls",
                    refreshToken = "rotated-fixture-refresh-$calls",
                    expiresAtMillis = 0,
                ),
            ))
        }
        val refresher = refresher(store, client, clock = { now })

        val first = refresher.refreshDue()
        val saved = store.find("due")!!
        assertEquals(1, first.updated)
        assertEquals(NOW + 60_000, saved.authExpiresAtMillis)
        assertEquals(NOW + 60_000, (saved.credential as Credential.OAuth).expiresAtMillis)
        assertEquals(0, refresher.refreshDue().attempted)
        now += 61_000
        assertEquals(1, refresher.refreshDue().attempted)
        assertEquals(2, calls)
    }

    @Test
    fun `account metadata is mapped back into provider refresh credentials`() = runTest {
        val account = dueAccount().copy(
            authRegion = "global",
            authTenantId = "fixture-tenant",
            authDomain = "www.codebuddy.cn",
            authTokenKind = "Bearer",
        )
        val store = InMemoryAccountStore(listOf(account))
        lateinit var seen: DeviceAuthCredentials
        val client = FakeRefreshClient { credentials ->
            seen = credentials
            DeviceAuthRefreshResult.Unchanged(DeviceAuthRefreshSkip.Backoff)
        }
        val refresher = refresher(store, client, clock = { NOW })

        refresher.refreshDue()

        assertEquals("codebuddy", seen.providerId)
        assertEquals("global", seen.region)
        assertEquals("fixture-user", seen.uid)
        assertEquals("fixture-tenant", seen.tenantId)
        assertEquals("www.codebuddy.cn", seen.domain)
        assertEquals("fixture-name", seen.nickname)
        assertEquals("Bearer", seen.tokenKind)
        assertEquals("fixture-refresh", seen.credential.refreshToken)
    }

    @Test
    fun `expiry and unknown or non oauth accounts are skipped without network`() = runTest {
        val client = FakeRefreshClient { error("Unexpected refresh") }
        val store = InMemoryAccountStore(listOf(
            validAccount(),
            dueAccount().copy(authExpiresAtMillis = 0, credential = Credential.OAuth(
                accessToken = "fixture-access", refreshToken = "fixture-refresh", expiresAtMillis = 0,
            )),
            dueAccount().copy(id = "api", credential = Credential.ApiKey("fixture-key")),
            dueAccount().copy(id = "session", credential = Credential.Session("phone", "session")),
            dueAccount().copy(id = "no-refresh", credential = Credential.OAuth("fixture-access")),
            dueAccount().copy(id = "unsupported", providerId = "other"),
        ))
        val summary = refresher(store, client, clock = { NOW }).refreshDue()

        assertEquals(6, summary.scanned)
        assertEquals(0, summary.attempted)
        assertEquals(6, summary.skipped)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `failed refresh backs off and a later successful exchange persists rotated token`() = runTest {
        var now = NOW
        var calls = 0
        val store = InMemoryAccountStore(listOf(dueAccount()))
        val client = FakeRefreshClient { credentials ->
            calls++
            if (calls == 1) {
                DeviceAuthRefreshResult.Failed(
                    DeviceAuthFailure(DeviceAuthError.HttpError, httpStatus = 401),
                )
            } else {
                DeviceAuthRefreshResult.Updated(credentials.copy(
                    credential = credentials.credential.copy(
                        accessToken = "rotated-fixture-access",
                        refreshToken = "rotated-fixture-refresh",
                        expiresAtMillis = now + 3_600_000,
                    ),
                ))
            }
        }
        val refresher = refresher(store, client, clock = { now })

        val failed = refresher.refreshDue()
        val unchanged = refresher.refreshDue()
        now += 61_000
        val updated = refresher.refreshDue()

        assertEquals(1, failed.failed)
        assertEquals(0, unchanged.attempted)
        assertEquals(1, updated.updated)
        assertEquals(2, calls)
        assertEquals("rotated-fixture-access",
            (store.find("due")!!.credential as Credential.OAuth).accessToken)
    }

    @Test
    fun `manual refresh can bypass local backoff while provider backoff remains authoritative`() = runTest {
        var calls = 0
        val store = InMemoryAccountStore(listOf(dueAccount()))
        val client = FakeRefreshClient { credentials ->
            calls++
            if (calls == 1) {
                DeviceAuthRefreshResult.Failed(DeviceAuthFailure(DeviceAuthError.NetworkError))
            } else {
                DeviceAuthRefreshResult.Unchanged(DeviceAuthRefreshSkip.Backoff)
            }
        }
        val refresher = refresher(store, client, clock = { NOW })

        refresher.refreshDue()
        val manual = refresher.refreshAccount("due", ignoreBackoff = true)
        refresher.refreshDue()

        assertFalse(manual)
        assertEquals(2, calls)
        assertEquals(0, refresher.refreshDue().attempted)
    }

    @Test
    fun `concurrent account edits are never overwritten by an in-flight refresh`() = runTest {
        val store = InMemoryAccountStore(listOf(dueAccount()))
        val client = FakeRefreshClient { credentials ->
            store.upsert(store.find("due")!!.copy(
                credential = Credential.OAuth("user-edited-fixture-access", null, NOW + 7_200_000),
            ))
            DeviceAuthRefreshResult.Updated(credentials.copy(
                credential = credentials.credential.copy(
                    accessToken = "stale-response-fixture-access",
                    expiresAtMillis = NOW + 3_600_000,
                ),
            ))
        }
        val summary = refresher(store, client, clock = { NOW }).refreshDue()

        assertEquals(0, summary.updated)
        assertEquals(1, summary.unchanged)
        assertEquals("user-edited-fixture-access",
            (store.find("due")!!.credential as Credential.OAuth).accessToken)
    }

    @Test
    fun `provider exceptions become counted failures without cancelling the scan`() = runTest {
        val store = InMemoryAccountStore(listOf(dueAccount(), dueAccount().copy(id = "due-2")))
        var calls = 0
        val client = FakeRefreshClient {
            calls++
            throw IllegalStateException("fixture network failure")
        }
        val summary = refresher(store, client, clock = { NOW }).refreshDue()

        assertEquals(2, summary.failed)
        assertEquals(0, summary.skipped)
        assertEquals(2, calls)
    }

    private fun refresher(
        store: InMemoryAccountStore,
        client: DeviceAuthClient,
        clock: () -> Long,
    ) = AccountCredentialRefresher(
        accounts = store,
        clients = mapOf("codebuddy" to client, "qoder" to client),
        clockMillis = clock,
        thresholdSeconds = 300,
        minimumRetryMillis = 60_000,
    )

    private fun dueAccount(providerId: String = "codebuddy") = Account(
        id = "due",
        providerId = providerId,
        label = "fixture-name",
        credential = Credential.OAuth(
            accessToken = "fixture-access",
            refreshToken = "fixture-refresh",
            expiresAtMillis = NOW + 60_000,
        ),
        authRegion = "cn",
        authUid = "fixture-user",
        authNickname = "fixture-name",
        authTokenKind = "Bearer",
        authExpiresAtMillis = NOW + 60_000,
    )

    private fun validAccount() = dueAccount().copy(
        id = "valid",
        credential = Credential.OAuth(
            accessToken = "valid-fixture-access",
            refreshToken = "valid-fixture-refresh",
            expiresAtMillis = NOW + 3_600_000,
        ),
        authExpiresAtMillis = NOW + 3_600_000,
    )

    private fun disabledAccount() = dueAccount().copy(id = "disabled", enabled = false)

    private class FakeRefreshClient(
        private val block: (DeviceAuthCredentials) -> DeviceAuthRefreshResult,
    ) : DeviceAuthClient {
        val requests = mutableListOf<DeviceAuthCredentials>()

        override suspend fun begin(region: String): DeviceAuthSession =
            error("begin is not used by refresher tests")

        override suspend fun poll(session: DeviceAuthSession): DeviceAuthPollResult =
            error("poll is not used by refresher tests")

        override fun cancel(session: DeviceAuthSession) = Unit

        override suspend fun refresh(
            credentials: DeviceAuthCredentials,
            thresholdSeconds: Long,
        ): DeviceAuthRefreshResult {
            requests += credentials
            return block(credentials)
        }
    }

    private companion object {
        const val NOW = 2_000_000_000_000L
    }
}
