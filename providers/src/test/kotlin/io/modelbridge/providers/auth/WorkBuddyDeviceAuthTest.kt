package io.modelbridge.providers.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

@OptIn(ExperimentalCoroutinesApi::class)
class WorkBuddyDeviceAuthTest {
    private val now = 1_800_000_000_000L
    private val ticket = """{"code":0,"data":{"state":"fixture state/+","authUrl":"https://www.codebuddy.cn/auth?state=fixture"}}"""

    @Test
    fun `begin posts empty JSON to CLI auth state endpoint`() = runTest {
        val http = DeviceHttpFake().enqueue(body = ticket)
        val client = WorkBuddyDeviceAuth(http, { now })
        val session = client.begin("cn")
        val request = http.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://copilot.tencent.com/v2/plugin/auth/state?platform=CLI", request.url)
        assertEquals("{}", request.body!!.decodeToString())
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("fixture state/+", session.state)
        assertEquals("https://www.codebuddy.cn/auth?state=fixture", session.loginUrl)
        assertEquals("codebuddy", session.providerId)
        assertEquals(now + 300_000, session.expiresAtMillis)
        assertEquals(3_000L, session.pollIntervalMillis)
    }

    @Test
    fun `global begin uses workbuddy auth host`() = runTest {
        val http = DeviceHttpFake().enqueue(body = ticket)
        val client = WorkBuddyDeviceAuth(http, { now })
        val session = client.begin("global")
        assertEquals("www.workbuddy.ai", URI(http.requests.single().url).host)
        assertEquals("global", session.region)
    }

    @Test
    fun `begin rejects missing state auth URL HTTP errors and malformed data safely`() = runTest {
        val cases = listOf(
            401 to "secret-response",
            200 to "not-json",
            200 to """{"code":11217,"msg":"secret-response"}""",
            200 to """{"code":0,"data":{"authUrl":"https://example.com"}}""",
            200 to """{"code":0,"data":{"state":"fixture-state"}}""",
            200 to """{"code":0,"data":{"state":"fixture-state","authUrl":"javascript:secret-response"}}""",
        )
        for ((status, body) in cases) {
            val client = WorkBuddyDeviceAuth(DeviceHttpFake().enqueue(status, body), { now })
            val error = assertInstanceOf(DeviceAuthException::class.java, runCatching { client.begin() }.exceptionOrNull())
            assertFalse(error.message.orEmpty().contains("secret-response"))
            assertNull(error.cause)
        }
    }

    @Test
    fun `11217 stays pending and polling encodes server state as UTF8`() = runTest {
        val http = DeviceHttpFake().enqueue(body = ticket)
            .enqueue(body = """{"code":11217,"msg":"waiting"}""")
        val client = WorkBuddyDeviceAuth(http, { now })
        val session = client.begin()
        assertEquals(DeviceAuthPollResult.Pending, client.poll(session))
        val request = http.requests.last()
        assertEquals("GET", request.method)
        assertEquals("/v2/plugin/auth/token", URI(request.url).path)
        assertEquals(session.state, authTestQuery(request.url)["state"])
        assertTrue(request.url.contains("fixture+state%2F%2B"))
        assertNull(request.body)
        assertEquals(DeviceAuthSessionStatus.Waiting, session.status)
    }

    @Test
    fun `poll success parses token aliases profile and absolute expiry`() = runTest {
        val http = DeviceHttpFake().enqueue(body = ticket).enqueue(body = """{
            "code":0,"data":{
                "access_token":"fixture-access","refresh_token":"fixture-refresh","expires_at":1800000060,
                "user_id":"fixture-user","entId":"fixture-tenant","nickName":"fixture-name",
                "domain":"copilot.tencent.com"
            }
        }""")
        val client = WorkBuddyDeviceAuth(http, { now })
        val session = client.begin()
        val success = assertInstanceOf(DeviceAuthPollResult.Success::class.java, client.poll(session))
        val value = success.credentials
        assertEquals("fixture-access", value.credential.accessToken)
        assertEquals("fixture-refresh", value.credential.refreshToken)
        assertEquals(now + 60_000, value.expiresAtMillis)
        assertEquals("fixture-user", value.uid)
        assertEquals("fixture-tenant", value.tenantId)
        assertEquals("fixture-name", value.nickname)
        assertEquals("copilot.tencent.com", value.domain)
        assertEquals("cn", value.region)
        assertEquals(DeviceAuthSessionStatus.Succeeded, session.status)
        assertInstanceOf(DeviceAuthPollResult.Failed::class.java, client.poll(session))
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `JWT claims supply identity and global issuer without being considered a verified signature`() = runTest {
        val access = authTestJwt("""{"sub":"jwt-user","tenant_id":"jwt-tenant","iss":"https://www.workbuddy.ai"}""")
        val http = DeviceHttpFake().enqueue(body = ticket).enqueue(body = """{
            "code":0,"data":{"accessToken":"$access","refreshToken":"fixture-refresh","expiresIn":90}
        }""")
        val client = WorkBuddyDeviceAuth(http, { now })
        val result = assertInstanceOf(DeviceAuthPollResult.Success::class.java, client.poll(client.begin()))
        assertEquals("jwt-user", result.credentials.uid)
        assertEquals("jwt-tenant", result.credentials.tenantId)
        assertEquals("global", result.credentials.region)
        assertEquals(now + 90_000, result.credentials.expiresAtMillis)
    }

    @Test
    fun `missing access token is failure and browser cookies do not grant success`() = runTest {
        val http = DeviceHttpFake().enqueue(body = ticket)
            .enqueue(body = """{"code":0,"data":{"cookie":"fixture-cookie"}}""")
        val client = WorkBuddyDeviceAuth(http, { now })
        val result = assertInstanceOf(DeviceAuthPollResult.Failed::class.java, client.poll(client.begin()))
        assertEquals(DeviceAuthError.MissingAccessToken, result.failure.error)
    }

    @Test
    fun `poll HTTP malformed JSON and nonzero business errors are failed without response data`() = runTest {
        val cases = listOf(
            403 to "canary-body",
            200 to "invalid-json-canary",
            200 to """{"code":9,"msg":"canary-body","token":"canary-body"}""",
            200 to """{"code":0}""",
        )
        for ((status, body) in cases) {
            val http = DeviceHttpFake().enqueue(body = ticket).enqueue(status, body)
            val client = WorkBuddyDeviceAuth(http, { now })
            val result = assertInstanceOf(DeviceAuthPollResult.Failed::class.java, client.poll(client.begin()))
            assertFalse(result.toString().contains("canary-body"))
        }
    }

    @Test
    fun `expired and cancelled sessions do not poll network`() = runTest {
        var time = now
        val http = DeviceHttpFake().enqueue(body = ticket).enqueue(body = ticket)
        val client = WorkBuddyDeviceAuth(http, { time })
        val cancelled = client.begin()
        client.cancel(cancelled)
        assertEquals(DeviceAuthPollResult.Cancelled, client.poll(cancelled))
        val expired = client.begin()
        time += 300_001
        assertEquals(DeviceAuthPollResult.Expired, client.poll(expired))
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `cancel while polling stops child request and prevents success`() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val http = DeviceHttpFake().enqueue(body = ticket).enqueue {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        val client = WorkBuddyDeviceAuth(http, { now })
        val session = client.begin()
        val polling = async { client.poll(session) }
        started.await()
        client.cancel(session)
        assertEquals(DeviceAuthPollResult.Cancelled, polling.await())
        assertTrue(cancelled)
        assertNotEquals(DeviceAuthSessionStatus.Succeeded, session.status)
    }

    @Test
    fun `caller cancellation remains a CancellationException`() = runTest {
        val started = CompletableDeferred<Unit>()
        val http = DeviceHttpFake().enqueue(body = ticket).enqueue { started.complete(Unit); awaitCancellation() }
        val client = WorkBuddyDeviceAuth(http, { now })
        val session = client.begin()
        val polling = async { client.poll(session) }
        started.await()
        polling.cancel()
        assertInstanceOf(CancellationException::class.java, runCatching { polling.await() }.exceptionOrNull())
        assertNotEquals(DeviceAuthSessionStatus.Succeeded, session.status)
    }

    @Test
    fun `refresh uses GET no body and enterprise headers but no bearer`() = runTest {
        val http = DeviceHttpFake().enqueue(body = """{"code":0,"data":{"accessToken":"new-access","expiresIn":3600}}""")
        val client = WorkBuddyDeviceAuth(http, { now })
        val old = authTestCredentials("codebuddy", uid = "user", tenantId = "enterprise", domain = "copilot.tencent.com")
        val result = assertInstanceOf(DeviceAuthRefreshResult.Updated::class.java, client.refresh(old))
        val request = http.requests.single()
        assertEquals("GET", request.method)
        assertNull(request.body)
        assertEquals("https://copilot.tencent.com/v2/plugin/auth/token/refresh", request.url)
        assertEquals("fixture-refresh", request.headers["X-Refresh-Token"])
        assertEquals("workbuddy", request.headers["X-Auth-Refresh-Source"])
        assertEquals("enterprise", request.headers["X-Enterprise-Id"])
        assertEquals("enterprise", request.headers["X-Tenant-Id"])
        assertEquals("CLI/2.63.2 CodeBuddy/2.63.2", request.headers["User-Agent"])
        assertEquals("https://www.codebuddy.cn", request.headers["Origin"])
        assertFalse(request.headers.containsKey("Authorization"))
        assertEquals(old.credential.refreshToken, result.credentials.credential.refreshToken)
        assertEquals(old.domain, result.credentials.domain)
        assertEquals(old.uid, result.credentials.uid)
        assertEquals(old.tenantId, result.credentials.tenantId)
        assertEquals(now + 3_600_000, result.credentials.expiresAtMillis)
    }

    @Test
    fun `refresh preserves absolute expiry if response has no relative ttl`() = runTest {
        val http = DeviceHttpFake().enqueue(body = """{"code":0,"data":{"accessToken":"new-access"}}""")
        val client = WorkBuddyDeviceAuth(http, { now })
        val old = authTestCredentials("codebuddy", expiresAtMillis = now + 30_000)
        val result = assertInstanceOf(DeviceAuthRefreshResult.Updated::class.java, client.refresh(old))
        assertEquals(old.expiresAtMillis, result.credentials.expiresAtMillis)
    }

    @Test
    fun `refresh derives international endpoint from issuer or domain`() = runTest {
        val http = DeviceHttpFake().enqueue(body = """{"code":0,"data":{"accessToken":"new-access","expiresIn":3600}}""")
        val client = WorkBuddyDeviceAuth(http, { now })
        val old = authTestCredentials("codebuddy", domain = "global")
        val result = assertInstanceOf(DeviceAuthRefreshResult.Updated::class.java, client.refresh(old))
        assertEquals("https://www.workbuddy.ai/v2/plugin/auth/token/refresh", http.requests.single().url)
        assertEquals("global", result.credentials.region)
    }

    @Test
    fun `refresh failure backs off for sixty seconds and then can retry`() = runTest {
        var time = now
        val http = DeviceHttpFake().enqueue(500, "secret-error")
            .enqueue(body = """{"code":0,"data":{"accessToken":"new-access","expiresIn":300}}""")
        val client = WorkBuddyDeviceAuth(http, { time })
        val old = authTestCredentials("codebuddy")
        val failed = assertInstanceOf(DeviceAuthRefreshResult.Failed::class.java, client.refresh(old))
        assertEquals(60_000L, failed.retryAfterMillis)
        time += 59_999
        val backedOff = assertInstanceOf(DeviceAuthRefreshResult.Unchanged::class.java, client.refresh(old))
        assertEquals(DeviceAuthRefreshSkip.Backoff, backedOff.reason)
        assertEquals(1L, backedOff.retryAfterMillis)
        assertEquals(1, http.requests.size)
        time++
        assertInstanceOf(DeviceAuthRefreshResult.Updated::class.java, client.refresh(old))
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `cached still-valid refreshed credentials avoid another HTTP call`() = runTest {
        val http = DeviceHttpFake().enqueue(body = """{"code":0,"data":{"accessToken":"new-access","expiresIn":3600}}""")
        val client = WorkBuddyDeviceAuth(http, { now })
        val old = authTestCredentials("codebuddy")
        val first = assertInstanceOf(DeviceAuthRefreshResult.Updated::class.java, client.refresh(old))
        val second = assertInstanceOf(DeviceAuthRefreshResult.Updated::class.java, client.refresh(old))
        assertEquals(first.credentials, second.credentials)
        assertTrue(second.fromCache)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `concurrent refreshes are single-flight even when replacement ttl is absent`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val http = DeviceHttpFake().enqueue {
            started.complete(Unit)
            release.await()
            DeviceHttpResponse(200, """{"code":0,"data":{"accessToken":"new-access"}}""")
        }
        val client = WorkBuddyDeviceAuth(http, { now })
        val old = authTestCredentials("codebuddy")
        val first = async { client.refresh(old) }
        started.await()
        val second = async { client.refresh(old) }
        runCurrent()
        release.complete(Unit)
        val a = assertInstanceOf(DeviceAuthRefreshResult.Updated::class.java, first.await())
        val b = assertInstanceOf(DeviceAuthRefreshResult.Updated::class.java, second.await())
        assertEquals(a.credentials, b.credentials)
        assertTrue(b.fromCache)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `a cancelled refresh is not cached as a failure or backoff`() = runTest {
        val started = CompletableDeferred<Unit>()
        val http = DeviceHttpFake().enqueue { started.complete(Unit); awaitCancellation() }
            .enqueue(body = """{"code":0,"data":{"accessToken":"retry-access","expiresIn":3600}}""")
        val client = WorkBuddyDeviceAuth(http, { now })
        val old = authTestCredentials("codebuddy")
        val refreshing = async { client.refresh(old) }
        started.await()
        refreshing.cancel()
        assertInstanceOf(CancellationException::class.java, runCatching { refreshing.await() }.exceptionOrNull())
        assertInstanceOf(DeviceAuthRefreshResult.Updated::class.java, client.refresh(old))
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `refresh checks validity and missing refresh without network`() = runTest {
        val http = DeviceHttpFake()
        val client = WorkBuddyDeviceAuth(http, { now })
        val valid = authTestCredentials("codebuddy", expiresAtMillis = now + 121_000)
        val noRefresh = authTestCredentials("codebuddy", refreshToken = null)
        assertEquals(DeviceAuthRefreshSkip.StillValid,
            assertInstanceOf(DeviceAuthRefreshResult.Unchanged::class.java, client.refresh(valid)).reason)
        assertEquals(DeviceAuthRefreshSkip.NoRefreshToken,
            assertInstanceOf(DeviceAuthRefreshResult.Unchanged::class.java, client.refresh(noRefresh)).reason)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `business error message is sanitized even when upstream echoes secret`() = runTest {
        val canary = "SHOULD-NOT-LOG-FIXTURE"
        val http = DeviceHttpFake().enqueue(body = """{"code":99,"msg":"$canary","token":"$canary"}""")
        val client = WorkBuddyDeviceAuth(http, { now })
        val old = authTestCredentials("codebuddy", accessToken = canary, refreshToken = canary)
        val result = assertInstanceOf(DeviceAuthRefreshResult.Failed::class.java, client.refresh(old))
        assertEquals(99L, result.failure.businessCode)
        assertFalse(result.toString().contains(canary))
        assertFalse(old.toString().contains(canary))
        assertFalse(DeviceAuthPollResult.Success(old).toString().contains(canary))
    }
}
