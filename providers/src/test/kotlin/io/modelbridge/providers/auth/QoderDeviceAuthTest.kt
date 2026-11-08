package io.modelbridge.providers.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class QoderDeviceAuthTest {
    private val now = 1_800_000_000_000L
    private val ids = QoderClientIds(cn = "public-fixture-cn", global = "public-fixture-global")
    private val fixedUuid = "12345678-1234-1234-1234-123456789abc"

    private fun auth(
        http: DeviceHttpFake,
        clock: () -> Long = { now },
        enrich: Boolean = false,
    ): QoderDeviceAuth = QoderDeviceAuth(
        http = http,
        clientIds = ids,
        clockMillis = clock,
        monotonicNanos = { 12345L },
        randomBytes = { count -> ByteArray(count) { it.toByte() } },
        uuid = { fixedUuid },
        completeUserInfo = enrich,
    )

    @Test
    fun `begin constructs regional PKCE challenge without a network call`() = runTest {
        val http = DeviceHttpFake()
        val client = auth(http)
        val session = client.begin("cn")
        val query = authTestQuery(session.loginUrl)
        val verifier = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32) { it.toByte() })
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
        )

        assertEquals("https://qoder.com.cn/device/selectAccounts", session.loginUrl.substringBefore('?'))
        assertEquals(expectedChallenge, query["challenge"])
        assertEquals("S256", query["challenge_method"])
        assertEquals(fixedUuid, query["nonce"])
        assertEquals("qoder-work-cn://", query["redirect_uri"])
        assertEquals(ids.cn, query["client_id"])
        assertEquals(fixedUuid, query["machine_id"])
        assertFalse(query.containsKey("verifier"))
        assertFalse(query.containsKey("device_code"))
        assertTrue(session.state.startsWith("qd-"))
        assertEquals(now + 600_000, session.expiresAtMillis)
        assertEquals(3_000L, session.pollIntervalMillis)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `global begin removes nonce hyphens and omits redirect uri`() = runTest {
        val client = auth(DeviceHttpFake())
        val session = client.begin("global")
        val query = authTestQuery(session.loginUrl)
        assertEquals("qoder.com", URI(session.loginUrl).host)
        assertEquals(fixedUuid.replace("-", ""), query["nonce"])
        assertEquals(ids.global, query["client_id"])
        assertFalse(query.containsKey("redirect_uri"))
    }

    @Test
    fun `unknown region falls back to cn and local states stay unique`() = runTest {
        val client = auth(DeviceHttpFake())
        val first = client.begin("unknown")
        val second = client.begin("unknown")
        assertEquals("cn", first.region)
        assertNotEquals(first.state, second.state)
    }

    @Test
    fun `missing client identifier is rejected without network or secret output`() = runTest {
        val http = DeviceHttpFake()
        val client = QoderDeviceAuth(http, QoderClientIds("", ""))
        val failure = runCatching { client.begin() }.exceptionOrNull()
        val error = assertInstanceOf(DeviceAuthException::class.java, failure)
        assertEquals(DeviceAuthError.MissingClientId, error.failure.error)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `poll uses nonce verifier query and treats 202 and 404 as pending`() = runTest {
        val http = DeviceHttpFake().enqueue(202, "").enqueue(404, "not-json")
        val client = auth(http)
        val session = client.begin("global")
        repeat(2) { assertEquals(DeviceAuthPollResult.Pending, client.poll(session)) }
        val request = http.requests.first()
        val query = authTestQuery(request.url)
        assertEquals("GET", request.method)
        assertEquals("openapi.qoder.sh", URI(request.url).host)
        assertEquals("/api/v1/deviceToken/poll", URI(request.url).path)
        assertEquals(fixedUuid.replace("-", ""), query["nonce"])
        assertEquals("S256", query["challenge_method"])
        assertEquals(43, query["verifier"]!!.length)
        assertNull(request.body)
        assertEquals("application/json", request.headers["Accept"])
        assertEquals("Qoder/1.1.64", request.headers["User-Agent"])
        assertEquals(15_000, request.connectTimeoutMillis)
        assertEquals(20_000, request.readTimeoutMillis)
        assertEquals(DeviceAuthSessionStatus.Waiting, session.status)
    }

    @Test
    fun `malformed json and 2xx response without top-level token stay pending`() = runTest {
        val http = DeviceHttpFake().enqueue(body = "not-json")
            .enqueue(body = """{"data":{"token":"nested-not-a-token"}}""")
        val client = auth(http)
        val session = client.begin()
        assertEquals(DeviceAuthPollResult.Pending, client.poll(session))
        assertEquals(DeviceAuthPollResult.Pending, client.poll(session))
        assertEquals(DeviceAuthSessionStatus.Waiting, session.status)
    }

    @Test
    fun `successful poll normalizes aliases milliseconds and jrt token kind`() = runTest {
        val http = DeviceHttpFake().enqueue(body = """{
            "device_token":"jt-fixture-access", "refreshToken":"jrt-fixture-refresh",
            "user_id":"fixture-uid", "name":"fixture-name", "expires_at":1800000030000
        }""")
        val client = auth(http)
        val session = client.begin()
        val result = assertInstanceOf(DeviceAuthPollResult.Success::class.java, client.poll(session))
        val value = result.credentials
        assertEquals("jt-fixture-access", value.credential.accessToken)
        assertEquals("jrt-fixture-refresh", value.credential.refreshToken)
        assertEquals(now + 30_000, value.expiresAtMillis)
        assertEquals("fixture-uid", value.uid)
        assertEquals("fixture-name", value.nickname)
        assertEquals("jrt", value.tokenKind)
        assertEquals(DeviceAuthSessionStatus.Succeeded, session.status)
        assertInstanceOf(DeviceAuthPollResult.Failed::class.java, client.poll(session))
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `seconds ISO and relative expiry values normalize at boundary`() = runTest {
        val iso = "2027-01-15T08:00:00+08:00"
        val http = DeviceHttpFake()
            .enqueue(body = """{"token":"fixture-a","expires_at":1800000030}""")
            .enqueue(body = """{"token":"fixture-b","expires_at":"$iso"}""")
            .enqueue(body = """{"token":"fixture-c","expires_in":90}""")
        val client = auth(http)
        val first = assertInstanceOf(DeviceAuthPollResult.Success::class.java, client.poll(client.begin()))
        val second = assertInstanceOf(DeviceAuthPollResult.Success::class.java, client.poll(client.begin()))
        val third = assertInstanceOf(DeviceAuthPollResult.Success::class.java, client.poll(client.begin()))
        assertEquals(now + 30_000, first.credentials.expiresAtMillis)
        assertEquals(OffsetDateTime.parse(iso).toInstant().toEpochMilli(), second.credentials.expiresAtMillis)
        assertEquals(now + 90_000, third.credentials.expiresAtMillis)
    }

    @Test
    fun `userinfo enriches identity through bearer request and data wrapper`() = runTest {
        val http = DeviceHttpFake().enqueue(body = """{"token":"fixture-access","expires_in":3600}""")
            .enqueue(body = """{"data":{"id":"profile-uid","username":"profile-name"}}""")
        val client = auth(http, enrich = true)
        val result = assertInstanceOf(DeviceAuthPollResult.Success::class.java, client.poll(client.begin()))
        assertEquals("profile-uid", result.credentials.uid)
        assertEquals("profile-name", result.credentials.nickname)
        assertEquals("/api/v1/userinfo", URI(http.requests.last().url).path)
        assertEquals("Bearer fixture-access", http.requests.last().headers["Authorization"])
    }

    @Test
    fun `optional profile failure preserves token and uses original MD5 identity fallback`() = runTest {
        val http = DeviceHttpFake().enqueue(body = """{"token":"fixture-access"}""")
            .enqueue(503, "secret-error-body")
        val client = auth(http, enrich = true)
        val result = assertInstanceOf(DeviceAuthPollResult.Success::class.java, client.poll(client.begin()))
        assertEquals(authTokenIdentity("fixture-access", "MD5"), result.credentials.uid)
        assertEquals("fixture-access", result.credentials.credential.accessToken)
    }

    @Test
    fun `expired session is removed without calling HTTP`() = runTest {
        var time = now
        val http = DeviceHttpFake()
        val client = auth(http, { time })
        val session = client.begin()
        time += 600_001
        assertEquals(DeviceAuthPollResult.Expired, client.poll(session))
        assertEquals(DeviceAuthSessionStatus.Expired, session.status)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `cancel before polling prevents HTTP and a success result`() = runTest {
        val http = DeviceHttpFake()
        val client = auth(http)
        val session = client.begin()
        client.cancel(session)
        assertEquals(DeviceAuthPollResult.Cancelled, client.poll(session))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `cancel during HTTP cancels child poll and cannot save success`() = runTest {
        val started = CompletableDeferred<Unit>()
        var wasCancelled = false
        val http = DeviceHttpFake().enqueue {
            started.complete(Unit)
            try { awaitCancellation() } finally { wasCancelled = true }
        }
        val client = auth(http)
        val session = client.begin()
        val polling = async { client.poll(session) }
        started.await()
        client.cancel(session)
        assertEquals(DeviceAuthPollResult.Cancelled, polling.await())
        assertTrue(wasCancelled)
        assertEquals(DeviceAuthSessionStatus.Cancelled, session.status)
    }

    @Test
    fun `caller cancellation propagates rather than becoming network failure`() = runTest {
        val started = CompletableDeferred<Unit>()
        val http = DeviceHttpFake().enqueue { started.complete(Unit); awaitCancellation() }
        val client = auth(http)
        val session = client.begin()
        val polling = async { client.poll(session) }
        started.await()
        polling.cancel()
        val error = runCatching { polling.await() }.exceptionOrNull()
        assertInstanceOf(CancellationException::class.java, error)
        assertNotEquals(DeviceAuthSessionStatus.Succeeded, session.status)
    }

    @Test
    fun `another auth instance cannot poll or cancel a session it does not own`() = runTest {
        val http = DeviceHttpFake().enqueue(202, "")
        val first = auth(http)
        val second = auth(DeviceHttpFake())
        val session = first.begin()
        second.cancel(session)
        assertInstanceOf(DeviceAuthPollResult.Failed::class.java, second.poll(session))
        assertEquals(DeviceAuthPollResult.Pending, first.poll(session))
    }

    @Test
    fun `duplicate concurrent poll does not issue a second HTTP call`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val http = DeviceHttpFake().enqueue {
            started.complete(Unit)
            release.await()
            DeviceHttpResponse(202, "")
        }
        val client = auth(http)
        val session = client.begin()
        val first = async { client.poll(session) }
        started.await()
        assertEquals(DeviceAuthPollResult.Pending, client.poll(session))
        release.complete(Unit)
        assertEquals(DeviceAuthPollResult.Pending, first.await())
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `jrt refresh posts both aliases using old bearer and keeps omitted metadata`() = runTest {
        val http = DeviceHttpFake().enqueue(body = """{"access_token":"new-fixture","expires_in":3600}""")
        val client = auth(http)
        val old = authTestCredentials("qoder", tokenKind = "jrt")
        val result = assertInstanceOf(DeviceAuthRefreshResult.Updated::class.java, client.refresh(old))
        val request = http.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://openapi.qoder.com.cn/api/v1/jobToken/exchange", request.url)
        assertEquals("fixture-refresh", authTestBody(request)["refresh_token"]!!.jsonPrimitive.content)
        assertEquals("fixture-refresh", authTestBody(request)["refreshToken"]!!.jsonPrimitive.content)
        assertEquals("Bearer fixture-access", request.headers["Authorization"])
        assertEquals("https://qoder.com.cn", request.headers["Origin"])
        assertEquals("https://qoder.com.cn/", request.headers["Referer"])
        assertEquals("fixture-refresh", result.credentials.credential.refreshToken)
        assertEquals(old.uid, result.credentials.uid)
        assertEquals(now + 3_600_000, result.credentials.expiresAtMillis)
    }

    @Test
    fun `drt refresh uses global deviceToken endpoint`() = runTest {
        val http = DeviceHttpFake().enqueue(body = """{"token":"new-fixture","refresh_token":"new-refresh"}""")
        val client = auth(http)
        val result = assertInstanceOf(DeviceAuthRefreshResult.Updated::class.java,
            client.refresh(authTestCredentials("qoder", region = "global")))
        assertEquals("https://openapi.qoder.sh/api/v1/deviceToken/refresh", http.requests.single().url)
        assertEquals("new-refresh", result.credentials.credential.refreshToken)
    }

    @Test
    fun `refresh skips valid credentials or absent refresh token without network`() = runTest {
        val http = DeviceHttpFake()
        val client = auth(http)
        val valid = authTestCredentials("qoder", expiresAtMillis = now + 121_000)
        val absent = authTestCredentials("qoder", refreshToken = null)
        assertEquals(DeviceAuthRefreshSkip.StillValid,
            assertInstanceOf(DeviceAuthRefreshResult.Unchanged::class.java, client.refresh(valid)).reason)
        assertEquals(DeviceAuthRefreshSkip.NoRefreshToken,
            assertInstanceOf(DeviceAuthRefreshResult.Unchanged::class.java, client.refresh(absent)).reason)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `refresh errors and DTO diagnostic output never include secrets`() = runTest {
        val canary = "DO-NOT-PRINT-FIXTURE"
        val http = DeviceHttpFake().enqueue(401, """{"token":"$canary","msg":"$canary"}""")
        val client = auth(http)
        val old = authTestCredentials("qoder", accessToken = canary, refreshToken = canary)
        val failure = assertInstanceOf(DeviceAuthRefreshResult.Failed::class.java, client.refresh(old))
        assertEquals(DeviceAuthError.HttpError, failure.failure.error)
        assertFalse(failure.toString().contains(canary))
        assertFalse(old.toString().contains(canary))
        assertEquals(canary, old.credential.accessToken)
        val session = client.begin()
        assertFalse(session.toString().contains(session.state))
        assertFalse(session.toString().contains(session.loginUrl))
    }

    @Test
    fun `simultaneous refreshes issue one request and return same new credentials`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val http = DeviceHttpFake().enqueue {
            started.complete(Unit)
            release.await()
            DeviceHttpResponse(200, """{"token":"new-fixture","expires_in":3600}""")
        }
        val client = auth(http)
        val old = authTestCredentials("qoder")
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
}
