package io.modelbridge.providers.auth

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class LoomySmsAuthTest {
    private val material = LoomySigningMaterial(
        appId = "fixture-app",
        accessKey = "fixture-access",
        secret = "fixture-secret",
    )

    @Test
    fun `sendCode returns msgid without sending a second request`() = runBlocking {
        val http = DeviceHttpFake().enqueue(
            body = """{"data":{"msgid":"fixture-msg"}}""",
        )
        val auth = LoomySmsAuth(
            http = http,
            material = material,
            clockMillis = { 1_000_000L },
            nonce = { "fixture-nonce" },
        )

        val challenge = auth.sendCode("+8613812345678")

        assertEquals("13812345678", challenge.phone)
        assertEquals("fixture-msg", challenge.messageId)
        assertEquals(1, http.requests.size)
        assertEquals("POST", http.requests.single().method)
        assertTrue(http.requests.single().url.endsWith("/login/phone/sendMsgCode"))
        assertTrue(http.requests.single().headers["Authorization"].orEmpty().startsWith("account fixture-access:"))
        assertTrue(http.requests.single().headers["Content-MD5"].orEmpty().isNotBlank())
    }

    @Test
    fun `checkCode returns session with fourteen day expiry`() = runBlocking {
        val http = DeviceHttpFake()
            .enqueue(body = """{"data":{"msgid":"fixture-msg"}}""")
            .enqueue(body = """{"data":{"session":"fixture-session","userid":"u-1"}}""")
        val now = 2_000_000L
        val auth = LoomySmsAuth(
            http = http,
            material = material,
            clockMillis = { now },
            nonce = { "fixture-nonce" },
        )

        val challenge = auth.sendCode("13812345678")
        val session = auth.checkCode(challenge, "123456")

        assertEquals("13812345678", session.phone)
        assertEquals("fixture-session", session.session)
        assertEquals("u-1", session.userId)
        assertEquals(now + 1_209_600_000L, session.expiresAtMillis)
        assertTrue(http.requests[1].url.endsWith("/login/phone/checkCode"))
        val body = authTestBody(http.requests[1])
        val param = body["param"]!!.toString()
        assertTrue(param.contains("fixture-msg"))
        assertTrue(param.contains("123456"))
    }

    @Test
    fun `invalid phone is rejected before network`() = runBlocking {
        val http = DeviceHttpFake()
        val auth = LoomySmsAuth(http, material)

        val error = runCatching { auth.sendCode("123") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, http.requests.size)
    }

    @Test
    fun `expired challenge is rejected before check request`() = runBlocking {
        val http = DeviceHttpFake().enqueue(body = """{"data":{"msgid":"fixture-msg"}}""")
        var now = 3_000_000L
        val auth = LoomySmsAuth(http, material, clockMillis = { now })
        val challenge = auth.sendCode("13812345678")
        now += 300_001L

        val error = runCatching { auth.checkCode(challenge, "123456") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `signer is injectable and never exposes secret in request body`() = runBlocking {
        var receivedSecret = ""
        val signer = LoomyRequestSigner { injected, _, _, _, _, _ ->
            receivedSecret = injected.secret
            mapOf("Authorization" to "fixture")
        }
        val http = DeviceHttpFake().enqueue(body = """{"data":{"msgid":"fixture-msg"}}""")
        LoomySmsAuth(http, material.copy(secret = "test-only-secret"), signer = signer).sendCode("13812345678")

        assertEquals("test-only-secret", receivedSecret)
        val requestBody = http.requests.single().body!!.toString(StandardCharsets.UTF_8)
        assertTrue("test-only-secret" !in requestBody)
    }
}
